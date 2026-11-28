package io.github.lengsukq.opaldesk

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.util.LruCache
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.diagnostics.DaemonPostmortemCollector
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.input.CapturedInputEvent
import io.github.lengsukq.opaldesk.input.InputBindingRule
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorSceneParser
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.DexImeRuntimeState
import io.github.lengsukq.opaldesk.protocol.IpcTrustVerifier
import io.github.lengsukq.opaldesk.protocol.PairingJsonParser
import io.github.lengsukq.opaldesk.protocol.PendingPairingRequest
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.TrustedPeerEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Thread-safe Binder IPC client for the OpalDesk v2 Universal Compositor Daemon.
 *
 * Exposes reactive [sceneFlow] and [statusFlow] streams, 120Hz non-blocking `FLAG_ONEWAY`
 * input/resize dispatchers, and an in-memory [Bitmap] cache for hibernated window snapshots.
 */
object BridgeClient {
    internal const val TAG = "OpalBridgeClient"
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val ioExec = Executors.newCachedThreadPool { r ->
        Thread(r, "OpalClientIo").apply { isDaemon = true }
    }
    internal val inputExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalInputFast").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }
    internal val companionSyncExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalCompanionSync").apply { isDaemon = true }
    }
    internal val dpiExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalDpiApply").apply { isDaemon = true }
    }
    internal val globalDpiGen = java.util.concurrent.atomic.AtomicLong(0L)
    internal val appDpiGenByPkg = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    internal var appContext: Context? = null

    fun bindAppContext(ctx: Context?) {
        val app = ctx?.applicationContext ?: return
        appContext = app
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.hydrate(app)
        io.github.lengsukq.opaldesk.prefs.AppPrefs.hydrateFlows(app)
    }

    internal val _statusFlow = MutableStateFlow(DaemonStatus())
    val statusFlow: StateFlow<DaemonStatus> = _statusFlow.asStateFlow()

    internal val _sceneFlow = MutableStateFlow(CompositorScene())
    val sceneFlow: StateFlow<CompositorScene> = _sceneFlow.asStateFlow()

    internal val _remoteStatusFlow = MutableStateFlow(RemoteStreamStatus())
    val remoteStatusFlow: StateFlow<RemoteStreamStatus> = _remoteStatusFlow.asStateFlow()

    internal val _pendingPairingFlow = MutableStateFlow<PendingPairingRequest?>(null)
    val pendingPairingFlow: StateFlow<PendingPairingRequest?> = _pendingPairingFlow.asStateFlow()

    internal val _trustedPeersFlow = MutableStateFlow<List<TrustedPeerEntry>>(emptyList())
    val trustedPeersFlow: StateFlow<List<TrustedPeerEntry>> = _trustedPeersFlow.asStateFlow()

    internal val _imeStateFlow = MutableStateFlow(DexImeRuntimeState())
    val imeStateFlow: StateFlow<DexImeRuntimeState> = _imeStateFlow.asStateFlow()

    internal val _inputBindingsFlow = MutableStateFlow(InputBindingRule.defaultRules())
    val inputBindingsFlow: StateFlow<List<InputBindingRule>> = _inputBindingsFlow.asStateFlow()

    internal val activeDexSessionTags = CopyOnWriteArraySet<String>()

    data class ImeBridgeRequest(
        val windowId: Int,
        val show: Boolean,
        val inputType: Int,
        val seq: Long,
    )

    private val imeReqSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val _imeBridgeRequestFlow = MutableStateFlow<ImeBridgeRequest?>(null)
    val imeBridgeRequestFlow: StateFlow<ImeBridgeRequest?> = _imeBridgeRequestFlow.asStateFlow()

    internal val daemonLogSyncLock = Any()
    internal val surfaceLogSyncGen = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    internal var lastDaemonLogSyncMs = 0L

    private val _remoteTrackpadCursorFlow = MutableStateFlow<Offset?>(null)
    val remoteTrackpadCursorFlow: StateFlow<Offset?> = _remoteTrackpadCursorFlow.asStateFlow()

    fun updateRemoteTrackpadCursor(pos: Offset?) {
        _remoteTrackpadCursorFlow.value = pos
    }

    private const val MAX_SNAPSHOT_CACHE_KB = 16 * 1024 // 16 MiB cap by actual Bitmap byte allocation
    private val binderReadyListeners = CopyOnWriteArraySet<Runnable>()
    internal val snapshotCache = object : LruCache<String, Bitmap>(MAX_SNAPSHOT_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    @Volatile
    internal var localDesktopMousePinned: Boolean = false

    @Volatile
    internal var pendingExternalMouseTarget: PendingExternalMouseTarget? = null

    fun isLocalDesktopPinned(): Boolean = localDesktopMousePinned

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                snapshotCache.evictAll()
                io.github.lengsukq.opaldesk.compositor.LiveWindowPreviewRegistry.trimMemory(clearAll = true)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                snapshotCache.trimToSize(MAX_SNAPSHOT_CACHE_KB / 2)
                io.github.lengsukq.opaldesk.compositor.LiveWindowPreviewRegistry.trimMemory(clearAll = false)
            }
        }
    }

    fun isBinderAliveFast(): Boolean = binder?.isBinderAlive == true

    fun getService(): IBinder? {
        val b = binder
        return if (b != null && b.pingBinder()) b else null
    }

    fun setOnBinderReady(cb: Runnable) {
        binderReadyListeners.add(cb)
        if (isBinderAliveFast()) mainHandler.post(cb)
    }

    fun removeOnBinderReady(cb: Runnable) {
        binderReadyListeners.remove(cb)
    }

    fun setBinder(candidate: IBinder?) {
        if (!IpcTrustVerifier.isValidDaemonBinder(candidate)) return
        val next = candidate ?: return
        if (binder === next) return
        binder = next
        OpalLogStore.i("IPC", "Daemon Binder connected")
        DaemonPostmortemCollector.onDaemonBinderConnected()
        try {
            next.linkToDeath({
                if (binder === next) {
                    binder = null
                    _statusFlow.value = DaemonStatus(connected = false)
                    _imeStateFlow.value = DexImeRuntimeState()
                    OpalLogStore.w("IPC", "Daemon Binder died (linkToDeath)")
                    DaemonPostmortemCollector.onUnexpectedDaemonDeathAsync(appContext)
                    io.github.lengsukq.opaldesk.service.BaseOpalTileService.requestAllTilesUpdate(appContext)
                }
            }, 0)
        } catch (_: Throwable) {
        }
        ioExec.execute {
            val ctx = appContext
            if (ctx != null) {
                io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.hydrate(ctx)
                io.github.lengsukq.opaldesk.prefs.AppPrefs.hydrateFlows(ctx)
                syncProfileToDaemonSync(next, io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.workingConfig)
                val st = refreshStatus()
                val micOn = io.github.lengsukq.opaldesk.prefs.AppPrefs.isRemoteMicEnabled(ctx)
                val camOn = st.rootEnhanced && io.github.lengsukq.opaldesk.prefs.AppPrefs.isRemoteCameraEnabled(ctx)
                if (!st.rootEnhanced && io.github.lengsukq.opaldesk.prefs.AppPrefs.isRemoteCameraEnabled(ctx)) {
                    io.github.lengsukq.opaldesk.prefs.AppPrefs.setRemoteCameraEnabled(ctx, false)
                }
                transact(next, BridgeProtocol.Tx.SET_REMOTE_MEDIA_CONFIG, oneway = false, writeArgs = { d ->
                    d.writeInt(if (micOn) 1 else 0)
                    d.writeInt(if (camOn) 1 else 0)
                }) { it.readInt() }
                syncAudioPrefsOnConnectSync(next, ctx)
                syncDexImeConfigSync(next, ctx)
            }
            if (activeDexSessionTags.isNotEmpty()) {
                transact(next, BridgeProtocol.Tx.SET_DEX_IME_SESSION_ACTIVE, oneway = false, writeArgs = { d ->
                    d.writeInt(1)
                }) { it.readInt() }
            }
            replayPendingMouseTargetsSync(next)
            refreshStatus()
            refreshSceneSnapshot()
            refreshRemoteStatus()
            refreshTrustedPeers()
            refreshPendingPairing()
            refreshDexImeState()
            fetchInputBindingsAsync()
            syncDaemonLogsThrottledLocked(minIntervalMs = 0L, maxLines = 400)
            io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.pushSnapshotToDaemon()
            io.github.lengsukq.opaldesk.notifications.OpalMediaSessionMonitor.pushCurrentStateToDaemon()
            io.github.lengsukq.opaldesk.notifications.OpalCallControlBridge.pushCurrentStateToDaemon()
            io.github.lengsukq.opaldesk.cast.BleNearbyService.syncEnabledState(appContext)
            io.github.lengsukq.opaldesk.service.BaseOpalTileService.requestAllTilesUpdate(appContext)
        }
        binderReadyListeners.forEach { mainHandler.post(it) }
    }

    fun onPushedPairingJson(pairingJson: String?): PendingPairingRequest? {
        val parsed = PairingJsonParser.parsePending(pairingJson)
        _pendingPairingFlow.value = parsed
        return parsed
    }

    fun onPushedImeRequest(windowId: Int, show: Boolean, inputType: Int) {
        _imeBridgeRequestFlow.value = ImeBridgeRequest(
            windowId = windowId,
            show = show,
            inputType = inputType,
            seq = imeReqSeq.incrementAndGet(),
        )
    }

    fun onPushedRemoteStateChanged() {
        ioExec.execute {
            val r = refreshRemoteStatus()
            refreshStatus()
            OpalPeerCastMutex.syncDaemonSendingState(r.connected, r.peerName)
        }
    }

    fun onPushedSceneJson(sceneJson: String, pushedSeq: Long) {
        val currentSeq = _sceneFlow.value.seq
        if (pushedSeq > 0L && pushedSeq < currentSeq) return
        val parsed = CompositorSceneParser.parse(sceneJson)
        if (parsed.seq >= currentSeq || currentSeq == 0L) {
            _sceneFlow.value = parsed
            val curSt = _statusFlow.value
            if (curSt.connected) {
                _statusFlow.value = curSt.copy(
                    activeVdCount = parsed.activeVdCount,
                    maxVdCount = parsed.maxVdCount,
                    windowCount = parsed.windows.size,
                    canvasWidth = parsed.canvasWidth,
                    canvasHeight = parsed.canvasHeight,
                    canvasDpi = parsed.canvasDpi,
                )
            }
        }
    }

    fun requestBinder(ctx: Context?) {
        if (isBinderAliveFast()) return
        val appCtx = ctx?.applicationContext
        ioExec.execute {
            if (getService() != null) return@execute
            try {
                LocalSocket().use { sock ->
                    sock.connect(
                        LocalSocketAddress(
                            BridgeProtocol.CTL_SOCKET_NAME,
                            LocalSocketAddress.Namespace.ABSTRACT,
                        ),
                    )
                    sock.soTimeout = 800
                    sock.outputStream.write("NEED_BINDER\n".toByteArray(StandardCharsets.UTF_8))
                    sock.outputStream.flush()
                }
            } catch (_: Throwable) {
            }
            if (appCtx != null) {
                try {
                    appCtx.sendBroadcast(Intent(BridgeProtocol.ACTION_NEED_BINDER))
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Synchronously attempts to bind to an already-running daemon over [BridgeProtocol.CTL_SOCKET_NAME]
     * within [timeoutMs] and returns the refreshed [DaemonStatus] (also hydrating scene and remote flows).
     */
    fun connectAndRefreshStatusSync(ctx: Context?, timeoutMs: Long = 900L): DaemonStatus {
        bindAppContext(ctx)
        if (getService() != null) {
            val st = refreshStatus()
            if (st.connected) {
                refreshSceneSnapshot()
                refreshRemoteStatus()
                return st
            }
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val listener = Runnable { latch.countDown() }
        binderReadyListeners.add(listener)
        try {
            if (getService() == null) {
                var daemonListening = false
                try {
                    LocalSocket().use { sock ->
                        sock.connect(
                            LocalSocketAddress(
                                BridgeProtocol.CTL_SOCKET_NAME,
                                LocalSocketAddress.Namespace.ABSTRACT,
                            ),
                        )
                        sock.soTimeout = timeoutMs.toInt().coerceIn(250, 1500)
                        sock.outputStream.write("NEED_BINDER\n".toByteArray(StandardCharsets.UTF_8))
                        sock.outputStream.flush()
                        sock.inputStream.read()
                        daemonListening = true
                    }
                } catch (_: Throwable) {
                }
                val appCtx = ctx?.applicationContext ?: appContext
                if (appCtx != null) {
                    try {
                        appCtx.sendBroadcast(Intent(BridgeProtocol.ACTION_NEED_BINDER))
                    } catch (_: Throwable) {
                    }
                }
                if (getService() == null) {
                    val waitMs = if (daemonListening) timeoutMs.coerceAtLeast(300L) else minOf(timeoutMs, 260L)
                    try {
                        latch.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        } finally {
            binderReadyListeners.remove(listener)
        }
        val st = refreshStatus()
        if (st.connected) {
            refreshSceneSnapshot()
            refreshRemoteStatus()
        }
        return st
    }

    fun getStatus(): DaemonStatus = refreshStatus()

    fun refreshStatus(): DaemonStatus {
        val svc = getService()
        if (svc == null) {
            val disconnected = DaemonStatus(connected = false)
            _statusFlow.value = disconnected
            return disconnected
        }
        return transact(svc, BridgeProtocol.Tx.GET_DAEMON_STATUS, oneway = false, writeArgs = {}) { reply ->
            DaemonStatus(
                connected = true,
                protocolVersion = reply.readInt(),
                daemonUid = reply.readInt(),
                injectStatus = reply.readInt(),
                inputBackendMode = reply.readInt(),
                activeVdCount = reply.readInt(),
                maxVdCount = reply.readInt(),
                windowCount = reply.readInt(),
                rootEnhanced = reply.readInt() != 0,
                canvasWidth = reply.readInt(),
                canvasHeight = reply.readInt(),
                canvasDpi = reply.readInt(),
                targetFps = if (reply.dataAvail() >= 4) reply.readInt() else 0,
                windowDpiScalePct = if (reply.dataAvail() >= 4) reply.readInt() else 100,
                mouseTargetMode = if (reply.dataAvail() >= 4) reply.readInt() else BridgeProtocol.MouseTarget.EXTERNAL,
                boundMouseCount = if (reply.dataAvail() >= 4) reply.readInt() else 0,
                connectedMouseCount = if (reply.dataAvail() >= 4) reply.readInt() else 0,
                mouseRoutedDisplayId = if (reply.dataAvail() >= 4) reply.readInt() else -1,
                hostScreenOff = if (reply.dataAvail() >= 4) reply.readInt() != 0 else false,
                globalCustomDpi = if (reply.dataAvail() >= 4) reply.readInt() else 0,
                remoteMicEnabled = if (reply.dataAvail() >= 4) reply.readInt() != 0 else false,
                remoteCameraEnabled = if (reply.dataAvail() >= 4) reply.readInt() != 0 else false,
                audioRouteMode = if (reply.dataAvail() >= 4) reply.readInt() else BridgeProtocol.AudioRoute.SPLIT_BY_APP,
                windowSupersampleMode = if (reply.dataAvail() >= 4) reply.readInt() else BridgeProtocol.WindowSupersample.COMPAT,
                forcePhoneSpeaker = if (reply.dataAvail() >= 4) reply.readInt() != 0 else false,
            )
        }?.also { st ->
            _statusFlow.value = st
            if (st.connected && !st.rootEnhanced && io.github.lengsukq.opaldesk.prefs.AppPrefs.remoteCameraEnabledFlow.value) {
                io.github.lengsukq.opaldesk.prefs.AppPrefs.setRemoteCameraEnabled(appContext, false)
            }
        } ?: DaemonStatus(connected = false).also { _statusFlow.value = it }
    }

    @Volatile
    internal var lastAutoResAppliedRemoteDisplayId: Int = -1

    // ---- Remote Cast & Companion Sync Facade (BridgeClientRemoteCast.kt) ----
    fun refreshRemoteStatus(): RemoteStreamStatus = refreshRemoteStatusImpl()

    fun setWifiStreamEnabledAsync(
        enabled: Boolean,
        port: Int = 19878,
        bindScope: Int = BridgeProtocol.TlsBindScope.ALL,
        onDone: (() -> Unit)? = null,
    ) = setWifiStreamEnabledAsyncImpl(enabled, port, bindScope, onDone)

    fun refreshTrustedPeers(): List<TrustedPeerEntry> = refreshTrustedPeersImpl()
    fun refreshPendingPairing(): PendingPairingRequest? = refreshPendingPairingImpl()

    fun respondToPairingAsync(
        requestId: String,
        decision: Int,
        ctx: Context? = null,
        onDone: ((Boolean) -> Unit)? = null,
    ) = respondToPairingAsyncImpl(requestId, decision, ctx, onDone)

    fun revokeTrustedPeerAsync(clientId: String, onDone: ((Boolean) -> Unit)? = null) =
        revokeTrustedPeerAsyncImpl(clientId, onDone)

    fun disconnectRemoteStreamAsync() = disconnectRemoteStreamAsyncImpl()

    fun setRemoteMediaConfigAsync(
        ctx: Context?,
        micEnabled: Boolean,
        cameraEnabled: Boolean,
        onDone: ((Boolean) -> Unit)? = null,
    ) = setRemoteMediaConfigAsyncImpl(ctx, micEnabled, cameraEnabled, onDone)

    fun setAudioRouteModeAsync(
        ctx: Context?,
        mode: Int,
        onDone: ((Boolean) -> Unit)? = null,
    ) = setAudioRouteModeAsyncImpl(ctx, mode, onDone)

    fun setForcePhoneSpeakerAsync(
        ctx: Context?,
        enabled: Boolean,
        persist: Boolean = true,
        onDone: ((Boolean) -> Unit)? = null,
    ) = setForcePhoneSpeakerAsyncImpl(ctx, enabled, persist, onDone)

    fun injectRemoteTrackpadOneway(
        action: Int,
        x: Float,
        y: Float,
        hScroll: Float = 0f,
        vScroll: Float = 0f,
        buttonState: Int = 0,
    ) = injectRemoteTrackpadOnewayImpl(action, x, y, hScroll, vScroll, buttonState)

    fun syncNotificationsAsync(json: String) = syncNotificationsAsyncImpl(json)
    fun syncMediaStateAsync(json: String) = syncMediaStateAsyncImpl(json)
    fun syncCallStateAsync(callStateJson: String) = syncCallStateAsyncImpl(callStateJson)
    fun triggerHotspotSync(clientId: String, nonceHex: String, hmacHex: String): String =
        triggerHotspotSyncImpl(clientId, nonceHex, hmacHex)

    // ---- Display, Input, DPI, Profile & IME Facade (BridgeClientDisplayPrefs.kt) ----
    fun setExternalDisplayModeAsync(
        displayId: Int,
        width: Int,
        height: Int,
        fps: Float,
        scalingMode: Int = BridgeProtocol.ExternalScaling.SMART_AUTO,
        onDone: ((Boolean) -> Unit)? = null,
    ) = setExternalDisplayModeAsyncImpl(displayId, width, height, fps, scalingMode, onDone)

    fun setHostScreenPowerAsync(screenOn: Boolean, onDone: ((Boolean) -> Unit)? = null) =
        setHostScreenPowerAsyncImpl(screenOn, onDone)

    fun tryWakeHostScreenFromKeyEvent(event: android.view.KeyEvent): Boolean =
        tryWakeHostScreenFromKeyEventImpl(event)

    fun updateDisplayPrefsAsync(
        targetFps: Int,
        windowDpiScalePct: Int,
        windowSupersampleMode: Int = -1,
    ) = updateDisplayPrefsAsyncImpl(targetFps, windowDpiScalePct, windowSupersampleMode)

    fun launchExternalDesktopOnDisplayAsync(
        displayId: Int,
        isWireless: Boolean = false,
        onDone: ((Boolean) -> Unit)? = null,
    ) = launchExternalDesktopOnDisplayAsyncImpl(displayId, isWireless, onDone)

    fun setGlobalCustomDpiAsync(ctx: Context?, globalCustomDpi: Int) =
        setGlobalCustomDpiAsyncImpl(ctx, globalCustomDpi)

    fun setAppCustomDpiAsync(ctx: Context?, pkg: String, customDpi: Int) =
        setAppCustomDpiAsyncImpl(ctx, pkg, customDpi)

    fun syncProfileToDaemonAsync(
        profile: io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig,
        onDone: (() -> Unit)? = null,
    ) = syncProfileToDaemonAsyncImpl(profile, onDone)

    fun pullDaemonWindowBoundsSync() = pullDaemonWindowBoundsSyncImpl()
    fun pullDaemonWindowBoundsAsync(onDone: (() -> Unit)? = null) = pullDaemonWindowBoundsAsyncImpl(onDone)

    fun setMouseTargetAsync(
        targetMode: Int,
        externalDisplayId: Int,
        onDone: ((Int) -> Unit)? = null,
    ) = setMouseTargetAsyncImpl(targetMode, externalDisplayId, onDone)

    fun setLocalDesktopMousePinAsync(pinned: Boolean) = setLocalDesktopMousePinAsyncImpl(pinned)
    fun releaseExternalMouseRoutingAsync() = releaseExternalMouseRoutingAsyncImpl()
    fun onHostTaskRemoved(hasPhysicalExternal: Boolean) = onHostTaskRemovedImpl(hasPhysicalExternal)
    fun setPointerSpeedAsync(sensitivity: Float) = setPointerSpeedAsyncImpl(sensitivity)

    fun setLocalTextFieldFocusedOneway(
        focused: Boolean,
        hostDisplayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ) = sendLocalImeFocus(focused = focused, hostDisplayId = hostDisplayId, oneway = true, onAck = null)

    fun setLocalTextFieldFocusedAndAwaitAck(
        focused: Boolean,
        hostDisplayId: Int = android.view.Display.DEFAULT_DISPLAY,
        onAck: () -> Unit,
    ) = sendLocalImeFocus(focused = focused, hostDisplayId = hostDisplayId, oneway = false, onAck = onAck)

    fun setDexImeConfigAsync(
        ctx: Context?,
        displayMode: Int = AppPrefs.dexImeDisplayModeFlow.value,
        lockedImeId: String = AppPrefs.dexLockedImeIdFlow.value,
        muteHostOnCast: Boolean = AppPrefs.muteHostImeOnCastFlow.value,
        showWithHardKbd: Int = AppPrefs.hardKbdSoftImeModeFlow.value,
        autoBridgeImeOnCast: Boolean = AppPrefs.autoBridgeImeOnCastFlow.value,
        onDone: ((ok: Boolean, activeImeId: String) -> Unit)? = null,
    ) = setDexImeConfigAsyncImpl(ctx, displayMode, lockedImeId, muteHostOnCast, showWithHardKbd, autoBridgeImeOnCast, onDone)

    fun setDexImeSessionActive(tag: String, active: Boolean) = setDexImeSessionActiveImpl(tag, active)
    fun refreshDexImeState(): DexImeRuntimeState = refreshDexImeStateImpl()
    fun refreshDexImeStateAsync(onDone: ((DexImeRuntimeState) -> Unit)? = null) = refreshDexImeStateAsyncImpl(onDone)

    fun notifyBridgeImeEventAsync(
        eventType: Int,
        imeDisplayId: Int,
        inputType: Int = 0,
        pkg: String = "",
        onYieldResult: ((String) -> Unit)? = null,
    ) = notifyBridgeImeEventAsyncImpl(eventType, imeDisplayId, inputType, pkg, onYieldResult)

    // ---- Input Bindings Facade (BridgeClientInputBindings.kt) ----
    fun fetchInputBindingsAsync(onDone: ((List<InputBindingRule>) -> Unit)? = null) = fetchInputBindingsAsyncImpl(onDone)
    fun saveInputBindingsAsync(rules: List<InputBindingRule>, onDone: ((Boolean, List<InputBindingRule>) -> Unit)? = null) = saveInputBindingsAsyncImpl(rules, onDone)
    fun resetInputBindingsAsync(onDone: ((Boolean, List<InputBindingRule>) -> Unit)? = null) = resetInputBindingsAsyncImpl(onDone)
    fun controlInputBindingCaptureAsync(op: Int, onResult: (Boolean, CapturedInputEvent?) -> Unit) = controlInputBindingCaptureAsyncImpl(op, onResult)

    // ---- Window Operations & Logs Facade (BridgeClientWindowOps.kt) ----
    fun refreshSceneSnapshot(): CompositorScene = refreshSceneSnapshotImpl()
    fun fetchSecondaryUserAppsJson(): String = fetchSecondaryUserAppsJsonImpl()

    fun openWindow(
        pkg: String,
        activityClass: String = "",
        profileCode: Int = BridgeProtocol.Profile.AUTO,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
        userId: Int = 0,
    ): Int = openWindowImpl(pkg, activityClass, profileCode, left, top, right, bottom, userId)

    fun attachWindowSurface(
        windowId: Int,
        surface: Surface,
        contentW: Int,
        contentH: Int,
        dpi: Int,
        fps: Float,
    ): Int = attachWindowSurfaceImpl(windowId, surface, contentW, contentH, dpi, fps)

    fun detachWindowSurface(windowId: Int, releaseDisplay: Boolean): Boolean =
        detachWindowSurfaceImpl(windowId, releaseDisplay)

    fun updateWindowFrameAsync(
        windowId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        presentation: String = BridgeProtocol.Presentation.FLOATING,
    ) = updateWindowFrameAsyncImpl(windowId, left, top, right, bottom, presentation)

    fun resizeWindowDisplayOneway(windowId: Int, contentW: Int, contentH: Int, dpi: Int = 0) =
        resizeWindowDisplayOnewayImpl(windowId, contentW, contentH, dpi)

    fun sendWindowActionAsync(windowId: Int, action: Int) = sendWindowActionAsyncImpl(windowId, action)

    fun injectWindowMotionOneway(
        windowId: Int,
        action: Int,
        downTime: Long,
        eventTime: Long,
        source: Int,
        buttonState: Int,
        actionButton: Int,
        hScroll: Float,
        vScroll: Float,
        pointerIds: IntArray,
        toolTypes: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        pressures: FloatArray,
    ) = injectWindowMotionOnewayImpl(
        windowId, action, downTime, eventTime, source, buttonState, actionButton,
        hScroll, vScroll, pointerIds, toolTypes, xs, ys, pressures,
    )

    fun injectWindowKeyAsync(
        windowId: Int,
        action: Int,
        keyCode: Int,
        metaState: Int,
        deviceId: Int = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
        scanCode: Int = 0,
        repeatCount: Int = 0,
    ) = injectWindowKeyAsyncImpl(windowId, action, keyCode, metaState, deviceId, scanCode, repeatCount)

    fun fetchWindowSnapshotBitmap(windowId: Int, snapshotVer: Long): Bitmap? =
        fetchWindowSnapshotBitmapImpl(windowId, snapshotVer)

    fun updateCompositorConfigAsync(
        inputBackendMode: Int = -1,
        maxVdCount: Int = -1,
        canvasW: Int = -1,
        canvasH: Int = -1,
        canvasDpi: Int = -1,
        dockInsetDp: Int = -1,
    ) = updateCompositorConfigAsyncImpl(inputBackendMode, maxVdCount, canvasW, canvasH, canvasDpi, dockInsetDp)

    fun closeAllWindowsAsync(removeTasks: Boolean) = closeAllWindowsAsyncImpl(removeTasks)

    fun restoreSavedLayoutAsync(
        profile: io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig,
        canvasW: Int,
        canvasH: Int,
        onDone: ((Int) -> Unit)? = null,
    ) = restoreSavedLayoutAsyncImpl(profile, canvasW, canvasH, onDone)

    fun fetchDaemonLogs(maxLines: Int = 300): String = fetchDaemonLogsImpl(maxLines)
    fun fetchDaemonLogsAsync(maxLines: Int = 300, onDone: ((String) -> Unit)? = null) =
        fetchDaemonLogsAsyncImpl(maxLines, onDone)

    fun shutdownDaemonSync(
        closeWindows: Boolean = true,
        removeTasks: Boolean = false,
        waitForExitMs: Long = 0L,
    ) {
        localDesktopMousePinned = false
        pendingExternalMouseTarget = null
        val svc = getService()
        var sentShutdownViaBinder = false
        if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_MOUSE_TARGET,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(BridgeProtocol.MouseTarget.RELEASE_EXTERNAL)
                    d.writeInt(-1)
                },
            ) { it.readInt() }
            if (closeWindows) {
                transact(
                    svc,
                    BridgeProtocol.Tx.CLOSE_ALL_WINDOWS,
                    oneway = false,
                    writeArgs = { d -> d.writeInt(if (removeTasks) 1 else 0) },
                ) { it.readInt() }
            }
            sentShutdownViaBinder = (
                transact(svc, BridgeProtocol.Tx.SHUTDOWN, oneway = false, writeArgs = {}) { it.readInt() } != null
                )
        }
        if (!sentShutdownViaBinder) {
            // Fallback to abstract control socket so daemon shutdown & mouse/focus cleanup never
            // get skipped if the Activity exited before Binder finished connecting!
            sendControlSocketShutdown(timeoutMs = 1500)
        }
        if (waitForExitMs > 0L) {
            waitForDaemonControlSocketExit(waitForExitMs)
        }
        binder = null
        _statusFlow.value = DaemonStatus(connected = false)
        _sceneFlow.value = CompositorScene()
        _remoteStatusFlow.value = RemoteStreamStatus()
        _pendingPairingFlow.value = null
        _trustedPeersFlow.value = emptyList()
        _imeStateFlow.value = DexImeRuntimeState()
        activeDexSessionTags.clear()
        io.github.lengsukq.opaldesk.service.BaseOpalTileService.requestAllTilesUpdate(appContext)
    }

    private fun sendControlSocketShutdown(timeoutMs: Int): Boolean {
        return try {
            LocalSocket().use { sock ->
                sock.connect(
                    LocalSocketAddress(
                        BridgeProtocol.CTL_SOCKET_NAME,
                        LocalSocketAddress.Namespace.ABSTRACT,
                    ),
                )
                sock.soTimeout = timeoutMs
                sock.outputStream.write("SHUTDOWN\n".toByteArray(StandardCharsets.UTF_8))
                sock.outputStream.flush()
                sock.inputStream.read()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun waitForDaemonControlSocketExit(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var fallbackSent = false
        while (System.currentTimeMillis() < deadline) {
            val stillListening = try {
                LocalSocket().use { sock ->
                    sock.connect(
                        LocalSocketAddress(
                            BridgeProtocol.CTL_SOCKET_NAME,
                            LocalSocketAddress.Namespace.ABSTRACT,
                        ),
                    )
                }
                true
            } catch (_: Throwable) {
                false
            }
            if (!stillListening) return
            if (!fallbackSent && System.currentTimeMillis() + (timeoutMs / 2) >= deadline) {
                fallbackSent = true
                sendControlSocketShutdown(timeoutMs = 800)
            }
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    fun shutdownDaemonAsync(closeWindows: Boolean = false, removeTasks: Boolean = false) {
        ioExec.execute {
            shutdownDaemonSync(closeWindows = closeWindows, removeTasks = removeTasks)
        }
    }

    internal inline fun <T> transact(
        svc: IBinder,
        code: Int,
        oneway: Boolean,
        writeArgs: (Parcel) -> Unit,
        readReply: (Parcel) -> T,
    ): T? {
        val data = Parcel.obtain()
        val reply = if (oneway) null else Parcel.obtain()
        return try {
            data.writeInterfaceToken(BridgeProtocol.BINDER_DESC)
            writeArgs(data)
            val flags = if (oneway) IBinder.FLAG_ONEWAY else 0
            if (!svc.transact(code, data, reply, flags)) return null
            if (reply != null) readReply(reply) else null
        } catch (t: Throwable) {
            Log.d(TAG, "Binder tx($code) failed: ${t.message}")
            null
        } finally {
            reply?.recycle()
            data.recycle()
        }
    }
}
