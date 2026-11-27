package io.github.lengsukq.opaldesk

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorSceneParser
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.DexImeRuntimeState
import io.github.lengsukq.opaldesk.protocol.DexImeStateParser
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
    private const val TAG = "OpalBridgeClient"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExec = Executors.newCachedThreadPool { r ->
        Thread(r, "OpalClientIo").apply { isDaemon = true }
    }
    private val inputExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalInputFast").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }
    private val companionSyncExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalCompanionSync").apply { isDaemon = true }
    }

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var appContext: Context? = null

    fun bindAppContext(ctx: Context?) {
        val app = ctx?.applicationContext ?: return
        appContext = app
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.hydrate(app)
        io.github.lengsukq.opaldesk.prefs.AppPrefs.hydrateFlows(app)
    }

    private val _statusFlow = MutableStateFlow(DaemonStatus())
    val statusFlow: StateFlow<DaemonStatus> = _statusFlow.asStateFlow()

    private val _sceneFlow = MutableStateFlow(CompositorScene())
    val sceneFlow: StateFlow<CompositorScene> = _sceneFlow.asStateFlow()

    private val _remoteStatusFlow = MutableStateFlow(RemoteStreamStatus())
    val remoteStatusFlow: StateFlow<RemoteStreamStatus> = _remoteStatusFlow.asStateFlow()

    private val _pendingPairingFlow = MutableStateFlow<PendingPairingRequest?>(null)
    val pendingPairingFlow: StateFlow<PendingPairingRequest?> = _pendingPairingFlow.asStateFlow()

    private val _trustedPeersFlow = MutableStateFlow<List<TrustedPeerEntry>>(emptyList())
    val trustedPeersFlow: StateFlow<List<TrustedPeerEntry>> = _trustedPeersFlow.asStateFlow()

    private val _imeStateFlow = MutableStateFlow(DexImeRuntimeState())
    val imeStateFlow: StateFlow<DexImeRuntimeState> = _imeStateFlow.asStateFlow()

    private val activeDexSessionTags = CopyOnWriteArraySet<String>()

    data class ImeBridgeRequest(
        val windowId: Int,
        val show: Boolean,
        val inputType: Int,
        val seq: Long,
    )

    private val imeReqSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val _imeBridgeRequestFlow = MutableStateFlow<ImeBridgeRequest?>(null)
    val imeBridgeRequestFlow: StateFlow<ImeBridgeRequest?> = _imeBridgeRequestFlow.asStateFlow()

    private val daemonLogSyncLock = Any()
    private val surfaceLogSyncGen = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var lastDaemonLogSyncMs = 0L

    private val _remoteTrackpadCursorFlow = MutableStateFlow<Offset?>(null)
    val remoteTrackpadCursorFlow: StateFlow<Offset?> = _remoteTrackpadCursorFlow.asStateFlow()

    fun updateRemoteTrackpadCursor(pos: Offset?) {
        _remoteTrackpadCursorFlow.value = pos
    }

    private const val MAX_SNAPSHOT_CACHE_KB = 16 * 1024 // 16 MiB cap by actual Bitmap byte allocation
    private val binderReadyListeners = CopyOnWriteArraySet<Runnable>()
    private val snapshotCache = object : LruCache<String, Bitmap>(MAX_SNAPSHOT_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    @Volatile
    private var localDesktopMousePinned: Boolean = false

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
                val audioMode = io.github.lengsukq.opaldesk.prefs.AppPrefs.getAudioRouteMode(ctx)
                transact(next, BridgeProtocol.Tx.SET_AUDIO_ROUTE_MODE, oneway = false, writeArgs = { d ->
                    d.writeInt(audioMode)
                }) { it.readInt() }
                syncDexImeConfigSync(next, ctx)
            }
            if (activeDexSessionTags.isNotEmpty()) {
                transact(next, BridgeProtocol.Tx.SET_DEX_IME_SESSION_ACTIVE, oneway = false, writeArgs = { d ->
                    d.writeInt(1)
                }) { it.readInt() }
            }
            if (localDesktopMousePinned) {
                val mode = BridgeProtocol.MouseTarget.PIN_LOCAL_DISPLAY0
                transact(next, BridgeProtocol.Tx.SET_MOUSE_TARGET, oneway = false, writeArgs = { d ->
                    d.writeInt(mode)
                    d.writeInt(-1)
                }) { it.readInt() }
            }
            refreshStatus()
            refreshSceneSnapshot()
            refreshRemoteStatus()
            refreshTrustedPeers()
            refreshPendingPairing()
            refreshDexImeState()
            syncDaemonLogsThrottledLocked(minIntervalMs = 0L, maxLines = 400)
            io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.pushSnapshotToDaemon()
            io.github.lengsukq.opaldesk.notifications.OpalMediaSessionMonitor.pushCurrentStateToDaemon()
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
            )
        }?.also { st ->
            _statusFlow.value = st
            if (st.connected && !st.rootEnhanced && io.github.lengsukq.opaldesk.prefs.AppPrefs.remoteCameraEnabledFlow.value) {
                io.github.lengsukq.opaldesk.prefs.AppPrefs.setRemoteCameraEnabled(appContext, false)
            }
        } ?: DaemonStatus(connected = false).also { _statusFlow.value = it }
    }

    @Volatile
    private var lastAutoResAppliedRemoteDisplayId: Int = -1

    fun refreshRemoteStatus(): RemoteStreamStatus {
        val svc = getService()
        if (svc == null) {
            val empty = RemoteStreamStatus()
            _remoteStatusFlow.value = empty
            lastAutoResAppliedRemoteDisplayId = -1
            return empty
        }
        return transact(svc, BridgeProtocol.Tx.GET_REMOTE_STREAM_STATUS, oneway = false, writeArgs = {}) { r ->
            RemoteStreamStatus(
                connected = r.readInt() != 0,
                sinkType = r.readInt(),
                peerName = r.readString().orEmpty(),
                width = r.readInt(),
                height = r.readInt(),
                fpsX100 = r.readInt(),
                bitrateKbps = r.readInt(),
                wifiEnabled = r.readInt() != 0,
                wifiPort = r.readInt(),
                certFingerprint = r.readString().orEmpty(),
                activeSessionPermanent = r.readInt() != 0,
                trustedCount = r.readInt(),
                lanIp = r.readString().orEmpty(),
                desktopDisplayId = if (r.dataAvail() >= 4) r.readInt() else -1,
                usbTetherIp = if (r.dataAvail() > 0) r.readString().orEmpty() else "",
                usbTetherInterface = if (r.dataAvail() > 0) r.readString().orEmpty() else "",
                tlsBindScope = if (r.dataAvail() >= 4) r.readInt() else BridgeProtocol.TlsBindScope.ALL,
            )
        }?.also { st ->
            _remoteStatusFlow.value = st
            maybeAutoApplyCustomResolutionToRemote(st)
        } ?: _remoteStatusFlow.value
    }

    private fun maybeAutoApplyCustomResolutionToRemote(st: RemoteStreamStatus) {
        if (!st.hasActiveDesktopCast || st.desktopDisplayId <= 0) {
            lastAutoResAppliedRemoteDisplayId = -1
            return
        }
        if (st.desktopDisplayId == lastAutoResAppliedRemoteDisplayId) return
        lastAutoResAppliedRemoteDisplayId = st.desktopDisplayId
        val ctx = appContext ?: return
        if (!AppPrefs.isAutoCustomResolutionEnabled(ctx)) return
        val pref = AppPrefs.getDefaultCustomResolution(ctx)
        if (pref.width != st.width || pref.height != st.height) {
            setExternalDisplayModeAsync(-1, pref.width, pref.height, pref.fps.toFloat())
        }
    }

    fun setWifiStreamEnabledAsync(
        enabled: Boolean,
        port: Int = 19878,
        bindScope: Int = BridgeProtocol.TlsBindScope.ALL,
        onDone: (() -> Unit)? = null,
    ) {
        ioExec.execute {
            val svc = getService()
            if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.SET_WIFI_STREAM_ENABLED,
                    oneway = false,
                    writeArgs = { d ->
                        d.writeInt(if (enabled) 1 else 0)
                        d.writeInt(port)
                        d.writeInt(bindScope)
                    },
                ) { r ->
                    r.readInt()
                    r.readInt()
                    r.readString()
                    if (r.dataAvail() >= 4) r.readInt()
                }
                refreshRemoteStatus()
                refreshPendingPairing()
            }
            if (onDone != null) mainHandler.post(onDone)
        }
    }

    fun refreshTrustedPeers(): List<TrustedPeerEntry> {
        val svc = getService()
        if (svc == null) {
            _trustedPeersFlow.value = emptyList()
            return emptyList()
        }
        val json = transact(svc, BridgeProtocol.Tx.GET_TRUSTED_PEERS, oneway = false, writeArgs = {}) { r ->
            r.readString().orEmpty()
        } ?: "[]"
        return PairingJsonParser.parseTrustedPeers(json).also { _trustedPeersFlow.value = it }
    }

    fun refreshPendingPairing(): PendingPairingRequest? {
        val svc = getService()
        if (svc == null) {
            _pendingPairingFlow.value = null
            return null
        }
        val json = transact(svc, BridgeProtocol.Tx.GET_PENDING_PAIRING, oneway = false, writeArgs = {}) { r ->
            r.readString().orEmpty()
        } ?: ""
        return PairingJsonParser.parsePending(json).also { _pendingPairingFlow.value = it }
    }

    fun respondToPairingAsync(
        requestId: String,
        decision: Int,
        ctx: Context? = null,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        if (ctx != null) {
            BridgeService.updatePairingNotification(ctx, null)
        }
        ioExec.execute {
            val svc = getService()
            val ok = if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.RESPOND_PAIRING,
                    oneway = false,
                    writeArgs = { d ->
                        d.writeString(requestId)
                        d.writeInt(decision)
                    },
                ) { it.readInt() != 0 } ?: false
            } else {
                false
            }
            val nextPending = refreshPendingPairing()
            if (ctx != null) {
                BridgeService.updatePairingNotification(ctx, nextPending)
            }
            refreshTrustedPeers()
            refreshRemoteStatus()
            if (onDone != null) mainHandler.post { onDone(ok) }
        }
    }

    fun revokeTrustedPeerAsync(clientId: String, onDone: ((Boolean) -> Unit)? = null) {
        ioExec.execute {
            val svc = getService()
            val ok = if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.REVOKE_TRUSTED_PEER,
                    oneway = false,
                    writeArgs = { d -> d.writeString(clientId) },
                ) { it.readInt() != 0 } ?: false
            } else {
                false
            }
            refreshTrustedPeers()
            refreshRemoteStatus()
            if (onDone != null) mainHandler.post { onDone(ok) }
        }
    }

    fun disconnectRemoteStreamAsync() {
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(svc, BridgeProtocol.Tx.DISCONNECT_REMOTE_STREAM, oneway = false, writeArgs = {}) { it.readInt() }
            refreshRemoteStatus()
        }
    }

    fun setExternalDisplayModeAsync(displayId: Int, width: Int, height: Int, fps: Float, onDone: ((Boolean) -> Unit)? = null) {
        ioExec.execute {
            val svc = getService()
            val ok = if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.SET_EXTERNAL_DISPLAY_MODE,
                    oneway = false,
                    writeArgs = { d ->
                        d.writeInt(displayId)
                        d.writeInt(width)
                        d.writeInt(height)
                        d.writeInt((fps * 100f).toInt())
                    },
                ) { it.readInt() != 0 } ?: false
            } else {
                false
            }
            if (ok) {
                refreshRemoteStatus()
                refreshStatus()
                BridgeService.requestExternalDisplayRescan(0L)
                BridgeService.requestExternalDisplayRescan(350L)
            }
            if (onDone != null) mainHandler.post { onDone(ok) }
        }
    }

    fun setHostScreenPowerAsync(screenOn: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        ioExec.execute {
            val svc = getService()
            val ok = if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.SET_HOST_SCREEN_POWER,
                    oneway = false,
                    writeArgs = { d -> d.writeInt(if (screenOn) 1 else 0) },
                ) { it.readInt() != 0 } ?: false
            } else {
                false
            }
            refreshStatus()
            if (onDone != null) mainHandler.post { onDone(ok) }
        }
    }

    fun tryWakeHostScreenFromKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!_statusFlow.value.hostScreenOff) return false
        val isWakeKey = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            android.view.KeyEvent.KEYCODE_POWER,
            android.view.KeyEvent.KEYCODE_WAKEUP -> true
            else -> false
        }
        if (!isWakeKey) return false
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            setHostScreenPowerAsync(true)
        }
        return true
    }

    fun updateDisplayPrefsAsync(
        targetFps: Int,
        windowDpiScalePct: Int,
        windowSupersampleMode: Int = -1,
    ) {
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.persistWorkingDisplayPrefs(
            ctx = appContext,
            targetFps = targetFps,
            windowDpiScalePct = windowDpiScalePct,
        )
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.UPDATE_DISPLAY_PREFS,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(targetFps)
                    d.writeInt(windowDpiScalePct)
                    if (windowSupersampleMode >= 0) {
                        d.writeInt(windowSupersampleMode)
                    }
                },
            ) { it.readInt() }
            refreshStatus()
            refreshSceneSnapshot()
        }
    }

    fun setRemoteMediaConfigAsync(
        ctx: Context?,
        micEnabled: Boolean,
        cameraEnabled: Boolean,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        bindAppContext(ctx)
        val curStatus = _statusFlow.value
        val canUseRootCamera = curStatus.connected && curStatus.rootEnhanced
        if (cameraEnabled && !canUseRootCamera) {
            io.github.lengsukq.opaldesk.prefs.AppPrefs.setRemoteCameraEnabled(ctx, false)
            if (onDone != null) mainHandler.post { onDone(false) }
            return
        }
        val effectiveCamera = cameraEnabled && canUseRootCamera
        io.github.lengsukq.opaldesk.prefs.AppPrefs.setRemoteMicEnabled(ctx, micEnabled)
        io.github.lengsukq.opaldesk.prefs.AppPrefs.setRemoteCameraEnabled(ctx, effectiveCamera)
        ioExec.execute {
            val svc = getService()
            val ok = if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.SET_REMOTE_MEDIA_CONFIG,
                    oneway = false,
                    writeArgs = { d ->
                        d.writeInt(if (micEnabled) 1 else 0)
                        d.writeInt(if (effectiveCamera) 1 else 0)
                    },
                ) { it.readInt() != 0 } ?: false
            } else {
                false
            }
            if (svc != null) {
                val updated = refreshStatus()
                if (!updated.rootEnhanced && effectiveCamera) {
                    io.github.lengsukq.opaldesk.prefs.AppPrefs.setRemoteCameraEnabled(ctx, false)
                }
            }
            if (onDone != null) mainHandler.post { onDone(ok) }
        }
    }

    fun setAudioRouteModeAsync(
        ctx: Context?,
        mode: Int,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        bindAppContext(ctx)
        val clamped = mode.coerceIn(
            BridgeProtocol.AudioRoute.ALL_TO_DEX,
            BridgeProtocol.AudioRoute.SPLIT_BY_APP,
        )
        io.github.lengsukq.opaldesk.prefs.AppPrefs.setAudioRouteMode(ctx, clamped)
        ioExec.execute {
            val svc = getService()
            val ok = if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.SET_AUDIO_ROUTE_MODE,
                    oneway = false,
                    writeArgs = { d -> d.writeInt(clamped) },
                ) { it.readInt() != 0 } ?: false
            } else {
                false
            }
            if (svc != null) {
                refreshStatus()
            }
            if (onDone != null) mainHandler.post { onDone(ok) }
        }
    }

    /**
     * Launches [ExternalDesktopActivity] directly onto an external physical/wireless [displayId]
     * via the shell daemon (`uid=2000`), bypassing Android 14–16 BAL and unprivileged cross-display
     * launch restrictions.
     */
    fun launchExternalDesktopOnDisplayAsync(
        displayId: Int,
        isWireless: Boolean = false,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        if (displayId <= 0) {
            if (onDone != null) mainHandler.post { onDone(false) }
            return
        }
        ioExec.execute {
            val svc = getService()
            val ok = if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.LAUNCH_EXTERNAL_DESKTOP,
                    oneway = false,
                    writeArgs = { d ->
                        d.writeInt(displayId)
                        d.writeInt(if (isWireless) 1 else 0)
                    },
                ) { it.readInt() != 0 } ?: false
            } else {
                false
            }
            if (onDone != null) mainHandler.post { onDone(ok) }
        }
    }

    private val dpiExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalDpiApply").apply { isDaemon = true }
    }
    private val globalDpiGen = java.util.concurrent.atomic.AtomicLong(0L)
    private val appDpiGenByPkg = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun setGlobalCustomDpiAsync(ctx: Context?, globalCustomDpi: Int) {
        bindAppContext(ctx)
        val updated = io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.setWorkingGlobalCustomDpi(globalCustomDpi)
        val gen = globalDpiGen.incrementAndGet()
        dpiExec.execute {
            if (globalDpiGen.get() != gen) return@execute
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.SET_ADVANCED_DPI_CONFIG,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(updated.globalCustomDpi)
                    d.writeInt(0)
                    d.writeString("")
                },
            ) { it.readInt() }
            if (globalDpiGen.get() == gen) {
                refreshStatus()
                refreshSceneSnapshot()
            }
        }
    }

    fun setAppCustomDpiAsync(ctx: Context?, pkg: String, customDpi: Int) {
        bindAppContext(ctx)
        val clamped = io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.clampDpiOrAuto(customDpi)
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.setWorkingAppCustomDpi(pkg, clamped)
        val gen = globalDpiGen.incrementAndGet()
        appDpiGenByPkg[pkg] = gen
        dpiExec.execute {
            if (appDpiGenByPkg[pkg] != gen) return@execute
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.SET_APP_CUSTOM_DPI,
                oneway = false,
                writeArgs = { d ->
                    d.writeString(pkg)
                    d.writeInt(clamped)
                },
            ) { it.readInt() }
            if (appDpiGenByPkg[pkg] == gen) {
                refreshSceneSnapshot()
            }
        }
    }

    fun syncProfileToDaemonAsync(
        profile: io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig,
        onDone: (() -> Unit)? = null,
    ) {
        ioExec.execute {
            val svc = getService()
            if (svc != null) {
                syncProfileToDaemonSync(svc, profile)
                refreshStatus()
                refreshSceneSnapshot()
            }
            if (onDone != null) mainHandler.post(onDone)
        }
    }

    private fun syncProfileToDaemonSync(
        svc: IBinder,
        profile: io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig,
    ) {
        transact(
            svc,
            BridgeProtocol.Tx.UPDATE_COMPOSITOR_CONFIG,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(profile.inputBackendMode)
                d.writeInt(profile.maxVdCount)
                d.writeInt(-1)
                d.writeInt(-1)
                d.writeInt(-1)
            },
        ) { it.readInt() }
        transact(
            svc,
            BridgeProtocol.Tx.UPDATE_DISPLAY_PREFS,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(profile.targetFps)
                d.writeInt(profile.windowDpiScalePct)
            },
        ) { it.readInt() }
        transact(
            svc,
            BridgeProtocol.Tx.SYNC_APP_WINDOW_BOUNDS,
            oneway = false,
            writeArgs = { d -> d.writeString(profile.formatAppBoundsForDaemon()) },
        ) { it.readInt() }
        transact(
            svc,
            BridgeProtocol.Tx.SET_ADVANCED_DPI_CONFIG,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(profile.globalCustomDpi)
                d.writeInt(1)
                d.writeString(profile.formatAppDpisForDaemon())
            },
        ) { it.readInt() }
    }

    fun pullDaemonWindowBoundsSync() {
        val svc = getService() ?: return
        val json = transact(svc, BridgeProtocol.Tx.GET_ADVANCED_CONFIG_STATE, oneway = false, writeArgs = {}) {
            it.readString().orEmpty()
        }
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.mergeDaemonBoundsIntoWorking(json)
    }

    fun pullDaemonWindowBoundsAsync(onDone: (() -> Unit)? = null) {
        ioExec.execute {
            pullDaemonWindowBoundsSync()
            if (onDone != null) mainHandler.post(onDone)
        }
    }

    fun restoreSavedLayoutAsync(
        profile: io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig,
        canvasW: Int,
        canvasH: Int,
        onDone: ((Int) -> Unit)? = null,
    ) {
        val layout = profile.savedLayout
        if (layout.isEmpty()) {
            if (onDone != null) mainHandler.post { onDone(0) }
            return
        }
        ioExec.execute {
            val w = canvasW.coerceAtLeast(360).toFloat()
            val h = canvasH.coerceAtLeast(360).toFloat()
            var restored = 0
            for (entry in layout.sortedBy { it.zOrder }) {
                val l = (entry.leftRatio * w).toInt()
                val t = (entry.topRatio * h).toInt()
                val r = (entry.rightRatio * w).toInt()
                val b = (entry.bottomRatio * h).toInt()
                val winId = openWindow(entry.pkg, entry.activityClass, entry.profileCode, l, t, r, b)
                if (winId > 0) {
                    restored++
                    if (entry.presentation != BridgeProtocol.Presentation.FLOATING) {
                        updateWindowFrameAsync(winId, l, t, r, b, entry.presentation)
                    }
                    if (entry.minimized) {
                        sendWindowActionAsync(winId, BridgeProtocol.WinAction.MINIMIZE)
                    }
                }
            }
            refreshSceneSnapshot()
            if (onDone != null) mainHandler.post { onDone(restored) }
        }
    }

    fun setMouseTargetAsync(
        targetMode: Int,
        externalDisplayId: Int,
        onDone: ((Int) -> Unit)? = null,
    ) {
        ioExec.execute {
            val svc = getService()
            val bound = if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.SET_MOUSE_TARGET,
                    oneway = false,
                    writeArgs = { d ->
                        d.writeInt(targetMode)
                        d.writeInt(externalDisplayId)
                    },
                ) { r ->
                    r.readInt()
                    if (r.dataAvail() >= 4) r.readInt() else 0
                } ?: 0
            } else {
                0
            }
            refreshStatus()
            if (onDone != null) mainHandler.post { onDone(bound) }
        }
    }

    fun setLocalDesktopMousePinAsync(pinned: Boolean) {
        localDesktopMousePinned = pinned
        val mode = if (pinned) {
            BridgeProtocol.MouseTarget.PIN_LOCAL_DISPLAY0
        } else {
            BridgeProtocol.MouseTarget.UNPIN_LOCAL_DISPLAY0
        }
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.SET_MOUSE_TARGET,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(mode)
                    d.writeInt(-1)
                },
            ) { it.readInt() }
        }
    }

    /**
     * Releases active external monitor mouse routing back to Display 0 without overwriting the
     * user's saved `MouseTarget.EXTERNAL` preference.
     */
    fun releaseExternalMouseRoutingAsync() {
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.SET_MOUSE_TARGET,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(BridgeProtocol.MouseTarget.RELEASE_EXTERNAL)
                    d.writeInt(-1)
                },
            ) { it.readInt() }
            refreshStatus()
        }
    }

    /**
     * Restores Display 0 mouse cursor & host screen power when the user swipes OpalDesk away from
     * Android Recents while keeping background sessions recoverable.
     */
    fun onHostTaskRemoved(hasPhysicalExternal: Boolean) {
        localDesktopMousePinned = false
        ioExec.execute {
            val svc = getService() ?: return@execute
            if (_statusFlow.value.hostScreenOff && !_remoteStatusFlow.value.connected && !hasPhysicalExternal) {
                transact(
                    svc,
                    BridgeProtocol.Tx.SET_HOST_SCREEN_POWER,
                    oneway = false,
                    writeArgs = { d -> d.writeInt(1) },
                ) { it.readInt() }
            }
            val releaseMode = if (!hasPhysicalExternal && !_remoteStatusFlow.value.connected) {
                BridgeProtocol.MouseTarget.RELEASE_EXTERNAL
            } else {
                BridgeProtocol.MouseTarget.UNPIN_LOCAL_DISPLAY0
            }
            transact(
                svc,
                BridgeProtocol.Tx.SET_MOUSE_TARGET,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(releaseMode)
                    d.writeInt(-1)
                },
            ) { it.readInt() }
            refreshStatus()
        }
    }

    fun setPointerSpeedAsync(sensitivity: Float) {
        inputExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.SET_POINTER_SPEED,
                oneway = true,
                writeArgs = { d -> d.writeFloat(sensitivity) },
                readReply = { Unit },
            )
        }
    }

    fun refreshSceneSnapshot(): CompositorScene {
        val svc = getService() ?: return _sceneFlow.value
        val json = transact(svc, BridgeProtocol.Tx.GET_SCENE_SNAPSHOT, oneway = false, writeArgs = {}) { reply ->
            reply.readString()
        }
        if (!json.isNullOrBlank()) {
            val parsed = CompositorSceneParser.parse(json)
            _sceneFlow.value = parsed
            return parsed
        }
        return _sceneFlow.value
    }

    fun openWindow(
        pkg: String,
        activityClass: String = "",
        profileCode: Int = BridgeProtocol.Profile.AUTO,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): Int {
        val svc = getService() ?: run {
            OpalLogStore.w("IPC", "openWindow($pkg) aborted: daemon not connected")
            return -1
        }
        val winId = transact(
            svc,
            BridgeProtocol.Tx.OPEN_WINDOW,
            oneway = false,
            writeArgs = { d ->
                d.writeString(pkg)
                d.writeString(activityClass)
                d.writeInt(profileCode)
                d.writeInt(left)
                d.writeInt(top)
                d.writeInt(right)
                d.writeInt(bottom)
            },
        ) { it.readInt() } ?: -1
        if (winId > 0) {
            OpalLogStore.i("Window", "openWindow($pkg) -> wid=$winId")
            scheduleWindowLifecycleLogSync(delaysMs = longArrayOf(400L), minIntervalMs = 2000L)
        } else {
            OpalLogStore.w("Window", "openWindow($pkg) failed (wid=$winId)")
        }
        return winId
    }

    fun attachWindowSurface(
        windowId: Int,
        surface: Surface,
        contentW: Int,
        contentH: Int,
        dpi: Int,
        fps: Float,
    ): Int {
        val svc = getService() ?: run {
            OpalLogStore.w("Surface", "attachWindowSurface(wid=$windowId) aborted: daemon not connected")
            return -1
        }
        val displayId = transact(
            svc,
            BridgeProtocol.Tx.ATTACH_WINDOW_SURFACE,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(windowId)
                d.writeInt(contentW)
                d.writeInt(contentH)
                d.writeInt(dpi)
                d.writeInt((fps * 100f).toInt())
                d.writeInt(1)
                surface.writeToParcel(d, 0)
            },
        ) { it.readInt() } ?: -1
        if (displayId > 0) {
            OpalLogStore.i("Surface", "attachWindowSurface wid=$windowId -> displayId=$displayId (${contentW}x${contentH}@${dpi}dpi)")
            scheduleWindowLifecycleLogSync(delaysMs = longArrayOf(280L, 1100L), minIntervalMs = 900L)
        } else {
            OpalLogStore.e("Surface", "attachWindowSurface FAILED wid=$windowId (${contentW}x${contentH}@${dpi}dpi)")
        }
        return displayId
    }

    fun detachWindowSurface(windowId: Int, releaseDisplay: Boolean): Boolean {
        val svc = getService() ?: return false
        return transact(
            svc,
            BridgeProtocol.Tx.DETACH_WINDOW_SURFACE,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(windowId)
                d.writeInt(if (releaseDisplay) 1 else 0)
            },
        ) { it.readInt() != 0 } ?: false
    }

    fun updateWindowFrameAsync(
        windowId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        presentation: String = BridgeProtocol.Presentation.FLOATING,
    ) {
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.UPDATE_WINDOW_FRAME,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(windowId)
                    d.writeInt(left)
                    d.writeInt(top)
                    d.writeInt(right)
                    d.writeInt(bottom)
                    d.writeString(presentation)
                },
            ) { it.readInt() }
        }
    }

    fun resizeWindowDisplayOneway(windowId: Int, contentW: Int, contentH: Int, dpi: Int = 0) {
        inputExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.RESIZE_WINDOW_DISPLAY,
                oneway = true,
                writeArgs = { d ->
                    d.writeInt(windowId)
                    d.writeInt(contentW)
                    d.writeInt(contentH)
                    d.writeInt(dpi)
                },
                readReply = { Unit },
            )
        }
    }

    fun sendWindowActionAsync(windowId: Int, action: Int) {
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.WINDOW_ACTION,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(windowId)
                    d.writeInt(action)
                },
            ) { it.readInt() }
        }
    }

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
    ) {
        inputExec.execute {
            val svc = getService() ?: return@execute
            val count = pointerIds.size
            if (count <= 0) return@execute
            transact(
                svc,
                BridgeProtocol.Tx.INJECT_WINDOW_MOTION,
                oneway = true,
                writeArgs = { d ->
                    d.writeInt(windowId)
                    d.writeInt(action)
                    d.writeLong(downTime)
                    d.writeLong(eventTime)
                    d.writeInt(source)
                    d.writeInt(buttonState)
                    d.writeInt(actionButton)
                    d.writeFloat(hScroll)
                    d.writeFloat(vScroll)
                    d.writeInt(count)
                    for (i in 0 until count) {
                        d.writeInt(pointerIds[i])
                        d.writeInt(toolTypes[i])
                        d.writeFloat(xs[i])
                        d.writeFloat(ys[i])
                        d.writeFloat(pressures[i])
                    }
                },
                readReply = { Unit },
            )
        }
    }

    fun injectWindowKeyAsync(
        windowId: Int,
        action: Int,
        keyCode: Int,
        metaState: Int,
        deviceId: Int = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
        scanCode: Int = 0,
        repeatCount: Int = 0,
    ) {
        inputExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.INJECT_WINDOW_KEY,
                oneway = true,
                writeArgs = { d ->
                    d.writeInt(windowId)
                    d.writeInt(action)
                    d.writeInt(keyCode)
                    d.writeInt(metaState)
                    d.writeInt(deviceId)
                    d.writeInt(scanCode)
                    d.writeInt(repeatCount)
                },
                readReply = { Unit },
            )
        }
    }

    fun injectRemoteTrackpadOneway(
        action: Int,
        x: Float,
        y: Float,
        hScroll: Float = 0f,
        vScroll: Float = 0f,
        buttonState: Int = 0,
    ) {
        inputExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.INJECT_REMOTE_TRACKPAD,
                oneway = true,
                writeArgs = { d ->
                    d.writeInt(action)
                    d.writeFloat(x)
                    d.writeFloat(y)
                    d.writeFloat(hScroll)
                    d.writeFloat(vScroll)
                    d.writeInt(buttonState)
                },
                readReply = { Unit },
            )
        }
    }

    fun setLocalTextFieldFocusedOneway(
        focused: Boolean,
        hostDisplayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ) {
        sendLocalImeFocus(focused = focused, hostDisplayId = hostDisplayId, oneway = true, onAck = null)
    }

    /**
     * Synchronously notifies the daemon of local IME focus on [hostDisplayId] (waiting for
     * `focusDisplay` Binder completion off the main thread) before invoking [onAck] on main.
     */
    fun setLocalTextFieldFocusedAndAwaitAck(
        focused: Boolean,
        hostDisplayId: Int = android.view.Display.DEFAULT_DISPLAY,
        onAck: () -> Unit,
    ) {
        sendLocalImeFocus(focused = focused, hostDisplayId = hostDisplayId, oneway = false, onAck = onAck)
    }

    private fun sendLocalImeFocus(
        focused: Boolean,
        hostDisplayId: Int,
        oneway: Boolean,
        onAck: (() -> Unit)?,
    ) {
        inputExec.execute {
            val svc = getService()
            if (svc != null) {
                transact(
                    svc,
                    BridgeProtocol.Tx.SET_LOCAL_IME_FOCUS,
                    oneway = oneway,
                    writeArgs = { d ->
                        d.writeInt(if (focused) 1 else 0)
                        d.writeInt(hostDisplayId.coerceAtLeast(0))
                    },
                    readReply = { r -> if (!oneway && r.dataAvail() >= 4) r.readInt() else 1 },
                )
            }
            if (onAck != null) {
                mainHandler.post(onAck)
            }
        }
    }

    private fun syncDexImeConfigSync(svc: IBinder, ctx: Context) {
        val displayMode = AppPrefs.getDexImeDisplayMode(ctx)
        val lockedIme = AppPrefs.getDexLockedImeId(ctx)
        val muteOnCast = AppPrefs.isMuteHostImeOnCastEnabled(ctx)
        val hardKbd = AppPrefs.getHardKbdSoftImeMode(ctx)
        val autoBridgeCast = AppPrefs.isAutoBridgeImeOnCastEnabled(ctx)
        transact(svc, BridgeProtocol.Tx.SET_DEX_IME_CONFIG, oneway = false, writeArgs = { d ->
            d.writeInt(displayMode)
            d.writeString(lockedIme)
            d.writeInt(if (muteOnCast) 1 else 0)
            d.writeInt(hardKbd)
            d.writeInt(if (autoBridgeCast) 1 else 0)
        }) { r ->
            r.readInt()
            if (r.dataAvail() > 0) r.readString()
        }
    }

    fun setDexImeConfigAsync(
        ctx: Context?,
        displayMode: Int = AppPrefs.dexImeDisplayModeFlow.value,
        lockedImeId: String = AppPrefs.dexLockedImeIdFlow.value,
        muteHostOnCast: Boolean = AppPrefs.muteHostImeOnCastFlow.value,
        showWithHardKbd: Int = AppPrefs.hardKbdSoftImeModeFlow.value,
        autoBridgeImeOnCast: Boolean = AppPrefs.autoBridgeImeOnCastFlow.value,
        onDone: ((ok: Boolean, activeImeId: String) -> Unit)? = null,
    ) {
        if (ctx != null) {
            AppPrefs.setDexImeDisplayMode(ctx, displayMode)
            AppPrefs.setDexLockedImeId(ctx, lockedImeId)
            AppPrefs.setMuteHostImeOnCastEnabled(ctx, muteHostOnCast)
            AppPrefs.setHardKbdSoftImeMode(ctx, showWithHardKbd)
            AppPrefs.setAutoBridgeImeOnCastEnabled(ctx, autoBridgeImeOnCast)
        }
        ioExec.execute {
            val svc = getService()
            if (svc == null) {
                if (onDone != null) mainHandler.post { onDone(false, "") }
                return@execute
            }
            val result = transact(svc, BridgeProtocol.Tx.SET_DEX_IME_CONFIG, oneway = false, writeArgs = { d ->
                d.writeInt(displayMode)
                d.writeString(lockedImeId.trim())
                d.writeInt(if (muteHostOnCast) 1 else 0)
                d.writeInt(showWithHardKbd)
                d.writeInt(if (autoBridgeImeOnCast) 1 else 0)
            }) { r ->
                val ok = r.readInt() != 0
                val activeIme = if (r.dataAvail() > 0) r.readString().orEmpty() else ""
                ok to activeIme
            }
            refreshDexImeState()
            if (onDone != null) {
                mainHandler.post { onDone(result?.first == true, result?.second.orEmpty()) }
            }
        }
    }

    fun setDexImeSessionActive(tag: String, active: Boolean) {
        val wasActive = activeDexSessionTags.isNotEmpty()
        if (active) {
            activeDexSessionTags.add(tag)
        } else {
            activeDexSessionTags.remove(tag)
        }
        val nowActive = activeDexSessionTags.isNotEmpty()
        if (wasActive == nowActive) return
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(svc, BridgeProtocol.Tx.SET_DEX_IME_SESSION_ACTIVE, oneway = false, writeArgs = { d ->
                d.writeInt(if (nowActive) 1 else 0)
            }) { it.readInt() }
            refreshDexImeState()
        }
    }

    fun refreshDexImeState(): DexImeRuntimeState {
        val svc = getService()
        if (svc == null) {
            val empty = DexImeRuntimeState()
            _imeStateFlow.value = empty
            return empty
        }
        val json = transact(svc, BridgeProtocol.Tx.GET_SYSTEM_IME_STATE, oneway = false, writeArgs = {}) { r ->
            r.readString().orEmpty()
        }
        return DexImeStateParser.parse(json).also { _imeStateFlow.value = it }
    }

    fun refreshDexImeStateAsync(onDone: ((DexImeRuntimeState) -> Unit)? = null) {
        ioExec.execute {
            val state = refreshDexImeState()
            if (onDone != null) mainHandler.post { onDone(state) }
        }
    }

    /**
     * Reports a 0ms event from [io.github.lengsukq.opaldesk.ime.OpalDeskImeService] to the daemon
     * so remote OpalCast clients receive instant keyboard show/hide triggers without polling.
     */
    fun syncNotificationsAsync(json: String) {
        if (json.isBlank()) return
        companionSyncExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.SYNC_NOTIFICATIONS,
                oneway = false,
                writeArgs = { d -> d.writeString(json) },
            ) { it.readInt() }
        }
    }

    fun syncMediaStateAsync(json: String) {
        if (json.isBlank()) return
        companionSyncExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.SYNC_MEDIA_STATE,
                oneway = false,
                writeArgs = { d -> d.writeString(json) },
            ) { it.readInt() }
        }
    }

    fun notifyBridgeImeEventAsync(
        eventType: Int,
        imeDisplayId: Int,
        inputType: Int = 0,
        pkg: String = "",
        onYieldResult: ((String) -> Unit)? = null,
    ) {
        inputExec.execute {
            val svc = getService()
            if (svc == null) {
                if (onYieldResult != null) mainHandler.post { onYieldResult("") }
                return@execute
            }
            val oneway = onYieldResult == null && eventType != BridgeProtocol.BridgeImeEvent.YIELD_TO_DISPLAY0
            val yieldedImeId = transact(
                svc,
                BridgeProtocol.Tx.NOTIFY_BRIDGE_IME_EVENT,
                oneway = oneway,
                writeArgs = { d ->
                    d.writeInt(eventType)
                    d.writeInt(imeDisplayId)
                    d.writeInt(inputType)
                    d.writeString(pkg)
                },
            ) { r ->
                if (!oneway) {
                    r.readInt()
                    if (r.dataAvail() > 0) r.readString().orEmpty() else ""
                } else {
                    ""
                }
            }.orEmpty()
            if (eventType == BridgeProtocol.BridgeImeEvent.YIELD_TO_DISPLAY0) {
                ioExec.execute { refreshDexImeState() }
            }
            if (onYieldResult != null) {
                mainHandler.post { onYieldResult(yieldedImeId) }
            }
        }
    }

    fun fetchWindowSnapshotBitmap(windowId: Int, snapshotVer: Long): Bitmap? {
        val cacheKey = "$windowId:$snapshotVer"
        snapshotCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        val svc = getService() ?: return null
        val bytes = transact(
            svc,
            BridgeProtocol.Tx.GET_WINDOW_SNAPSHOT,
            oneway = false,
            writeArgs = { d -> d.writeInt(windowId) },
        ) { it.createByteArray() }
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bmp ->
                snapshotCache.put(cacheKey, bmp)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun updateCompositorConfigAsync(
        inputBackendMode: Int = -1,
        maxVdCount: Int = -1,
        canvasW: Int = -1,
        canvasH: Int = -1,
        canvasDpi: Int = -1,
    ) {
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.UPDATE_COMPOSITOR_CONFIG,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(inputBackendMode)
                    d.writeInt(maxVdCount)
                    d.writeInt(canvasW)
                    d.writeInt(canvasH)
                    d.writeInt(canvasDpi)
                },
            ) { it.readInt() }
            refreshStatus()
            refreshSceneSnapshot()
        }
    }

    fun closeAllWindowsAsync(removeTasks: Boolean) {
        ioExec.execute {
            val svc = getService() ?: return@execute
            transact(
                svc,
                BridgeProtocol.Tx.CLOSE_ALL_WINDOWS,
                oneway = false,
                writeArgs = { d -> d.writeInt(if (removeTasks) 1 else 0) },
            ) { it.readInt() }
            refreshSceneSnapshot()
        }
    }

    fun fetchDaemonLogs(maxLines: Int = 300): String {
        val svc = getService() ?: return ""
        val logs = transact(
            svc,
            BridgeProtocol.Tx.GET_DAEMON_LOGS,
            oneway = false,
            writeArgs = { d -> d.writeInt(maxLines.coerceIn(20, 500)) },
        ) { it.readString().orEmpty() }.orEmpty()
        if (logs.isNotBlank()) {
            OpalLogStore.updateDaemonLogs(logs)
            lastDaemonLogSyncMs = android.os.SystemClock.uptimeMillis()
        }
        return logs
    }

    private fun syncDaemonLogsThrottledLocked(minIntervalMs: Long, maxLines: Int = 350) {
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(daemonLogSyncLock) {
            if (minIntervalMs > 0L && now - lastDaemonLogSyncMs < minIntervalMs) return
            lastDaemonLogSyncMs = now
        }
        fetchDaemonLogs(maxLines)
    }

    private fun scheduleWindowLifecycleLogSync(delaysMs: LongArray, minIntervalMs: Long) {
        val gen = surfaceLogSyncGen.incrementAndGet()
        delaysMs.forEach { delayMs ->
            mainHandler.postDelayed({
                if (surfaceLogSyncGen.get() != gen || !isBinderAliveFast()) return@postDelayed
                ioExec.execute {
                    syncDaemonLogsThrottledLocked(minIntervalMs = minIntervalMs, maxLines = 350)
                }
            }, delayMs)
        }
    }

    fun fetchDaemonLogsAsync(maxLines: Int = 300, onDone: ((String) -> Unit)? = null) {
        ioExec.execute {
            val text = fetchDaemonLogs(maxLines)
            if (onDone != null) mainHandler.post { onDone(text) }
        }
    }

    fun shutdownDaemonSync(
        closeWindows: Boolean = true,
        removeTasks: Boolean = false,
        waitForExitMs: Long = 0L,
    ) {
        localDesktopMousePinned = false
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

    private inline fun <T> transact(
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
