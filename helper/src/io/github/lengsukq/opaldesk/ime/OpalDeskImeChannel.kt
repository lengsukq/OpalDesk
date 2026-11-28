package io.github.lengsukq.opaldesk.ime

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.IpcTrustVerifier
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton channel coordinating:
 * 1) In-process lightweight `OpalDeskImeService` (0-KB pipe for OpalCast PC/Mac/Tablet sessions), and
 * 2) Optional cert-verified `OpalYuyanImeService` companion plugin APK (`io.github.lengsukq.opaldesk.ime`),
 *    powering the Compositor's Floating Candidate Bar & Yuyan Desktop Soft Keyboard.
 */
object OpalDeskImeChannel {
    private const val MAIN_THREAD_COMMIT_TIMEOUT_MS = 350L

    private val core = OpalDeskImeChannelCore()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var pluginSessionBinder: IBinder? = null

    private val _editorStateFlow = MutableStateFlow(ImeEditorSnapshot())
    val editorStateFlow: StateFlow<ImeEditorSnapshot> = _editorStateFlow.asStateFlow()

    private val _cursorAnchorFlow = MutableStateFlow(ImeCursorAnchorSnapshot())
    val cursorAnchorFlow: StateFlow<ImeCursorAnchorSnapshot> = _cursorAnchorFlow.asStateFlow()

    private val _pluginStateFlow = MutableStateFlow(PluginCompositionSnapshot())
    val pluginStateFlow: StateFlow<PluginCompositionSnapshot> = _pluginStateFlow.asStateFlow()

    private val _softKeyboardVisibleFlow = MutableStateFlow(false)
    val softKeyboardVisibleFlow: StateFlow<Boolean> = _softKeyboardVisibleFlow.asStateFlow()

    private val _floatingKeyboardModeFlow = MutableStateFlow(false)
    val floatingKeyboardModeFlow: StateFlow<Boolean> = _floatingKeyboardModeFlow.asStateFlow()

    private val _lastTapAnchorFlow = MutableStateFlow(WindowTapAnchor())
    val lastTapAnchorFlow: StateFlow<WindowTapAnchor> = _lastTapAnchorFlow.asStateFlow()

    val isPluginSessionConnected: Boolean
        get() = pluginSessionBinder?.pingBinder() == true

    fun recordWindowPointerDown(
        windowId: Int,
        displayId: Int,
        canvasX: Float,
        canvasY: Float,
        modality: InputModality,
        uptimeMs: Long = SystemClock.uptimeMillis(),
    ) {
        _lastTapAnchorFlow.value = WindowTapAnchor(
            windowId = windowId,
            displayId = displayId,
            canvasX = canvasX,
            canvasY = canvasY,
            modality = modality,
            uptimeMs = uptimeMs,
        )
    }

    // ---- In-process lightweight OpalDeskImeService hooks ----

    fun attachService(adapter: ImeConnectionAdapter) {
        core.onServiceAttached(adapter)
        publishSnapshots()
    }

    fun detachService(adapter: ImeConnectionAdapter) {
        core.onServiceDetached(adapter)
        publishSnapshots()
    }

    fun onStartInput(
        packageName: String,
        inputType: Int,
        imeOptions: Int,
        fieldId: Int,
        imeDisplayId: Int = -1,
    ) {
        core.onStartInput(packageName, inputType, imeOptions, fieldId, imeDisplayId)
        publishSnapshots()
    }

    fun onFinishInput() {
        core.onFinishInput()
        publishSnapshots()
    }

    fun onCursorAnchorUpdated(snapshot: ImeCursorAnchorSnapshot) {
        core.onCursorAnchorUpdated(snapshot)
        _cursorAnchorFlow.value = core.currentCursorAnchor()
    }

    // ---- Out-of-process cert-pinned YuyanIme Companion Plugin sync (`BridgeProvider.pluginImeSync`) ----

    fun handlePluginProviderSync(subOp: String, extras: Bundle?): Bundle {
        val reply = Bundle()
        when (subOp) {
            "attach" -> {
                val b = extras?.getBinder("binder")
                if (!IpcTrustVerifier.isValidPluginImeBinder(b)) {
                    reply.putBoolean("ok", false)
                    return reply
                }
                attachPluginBinder(b!!, extras)
                reply.putBoolean("ok", true)
            }
            "detach" -> {
                clearPluginBinder()
                reply.putBoolean("ok", true)
            }
            "state" -> {
                if (!isPluginSessionConnected) {
                    val b = extras?.getBinder("binder")
                    if (IpcTrustVerifier.isValidPluginImeBinder(b)) {
                        attachPluginBinder(b!!, extras)
                    } else {
                        reply.putBoolean("needAttach", true)
                    }
                }
                if (extras != null) {
                    applyPluginStateBundle(extras)
                }
                reply.putBoolean("ok", true)
            }
            "cursor" -> {
                if (extras != null) {
                    val snap = ImeCursorAnchorSnapshot(
                        valid = extras.getBoolean("valid", false),
                        insertionMarkerHorizontal = extras.getFloat("h", 0f),
                        insertionMarkerTop = extras.getFloat("top", 0f),
                        insertionMarkerBaseline = extras.getFloat("baseline", 0f),
                        insertionMarkerBottom = extras.getFloat("bottom", 0f),
                        imeDisplayId = extras.getInt("imeDisplayId", -1),
                    )
                    _cursorAnchorFlow.value = snap
                }
                reply.putBoolean("ok", true)
            }
            "showRequested" -> {
                val imeDispId = extras?.getInt("imeDisplayId", -1) ?: -1
                val inputType = extras?.getInt("inputType", 0) ?: 0
                val pkg = extras?.getString("pkg").orEmpty()
                val curEd = _editorStateFlow.value
                if (!curEd.inputActive || !curEd.acceptingText) {
                    _editorStateFlow.value = curEd.copy(
                        serviceBound = true,
                        inputActive = true,
                        packageName = pkg.ifEmpty { curEd.packageName },
                        inputType = if (inputType != 0) inputType else curEd.inputType,
                        acceptingText = true,
                        imeDisplayId = if (imeDispId >= 0) imeDispId else curEd.imeDisplayId,
                    )
                }
                val imeState = BridgeClient.imeStateFlow.value
                val remoteStatus = BridgeClient.remoteStatusFlow.value
                val extSession = ExternalTrackpadBridge.sessionFlow.value
                val isRemoteCast = remoteStatus.hasActiveDesktopCast || extSession.isRemoteCastDisplay
                onSystemShowInputRequested(
                    acceptingText = true,
                    isRemoteCastDisplay = isRemoteCast,
                    physicalKeyboardDetected = imeState.physicalKeyboardDetected,
                    showImeWithHardKeyboardMode = AppPrefs.hardKbdSoftImeModeFlow.value,
                )
                BridgeClient.notifyBridgeImeEventAsync(
                    eventType = BridgeProtocol.BridgeImeEvent.SHOW_REQUESTED,
                    imeDisplayId = imeDispId,
                    inputType = inputType,
                    pkg = pkg,
                )
                reply.putBoolean("ok", true)
            }
            "hideOrFinish" -> {
                val imeDispId = extras?.getInt("imeDisplayId", -1) ?: -1
                _softKeyboardVisibleFlow.value = false
                if (imeDispId > 0) {
                    BridgeClient.notifyBridgeImeEventAsync(
                        eventType = BridgeProtocol.BridgeImeEvent.HIDE_OR_FINISH,
                        imeDisplayId = imeDispId,
                    )
                }
                reply.putBoolean("ok", true)
            }
            "checkYieldDisplay0" -> {
                val imeDispId = extras?.getInt("imeDisplayId", 0) ?: 0
                val pkg = extras?.getString("pkg").orEmpty()
                val inputType = extras?.getInt("inputType", 0) ?: 0
                val accepting = extras?.getBoolean("accepting", false) == true
                val scene = BridgeClient.sceneFlow.value
                val imeState = BridgeClient.imeStateFlow.value
                val focusedLivePkg = scene.focusedWindow?.takeIf { it.isLive && !it.isMinimized }?.pkg
                val openLivePkgs = scene.windows.filter { it.isLive && !it.isMinimized }.map { it.pkg }.toSet()
                val policyIsLocal = imeState.effectiveWindowImePolicy == BridgeProtocol.DisplayImePolicy.LOCAL
                val shouldYield = OpalDeskImeChannelCore.shouldYieldToDisplay0OriginalIme(
                    imeDisplayId = imeDispId,
                    localDesktopPinned = BridgeClient.isLocalDesktopPinned(),
                    acceptingText = accepting,
                    focusedLiveWindowPkg = focusedLivePkg,
                    focusedLiveWindowImePolicyLocal = policyIsLocal,
                    targetPkg = pkg,
                    hostScreenOff = BridgeClient.statusFlow.value.hostScreenOff,
                    openLiveWindowPkgs = openLivePkgs,
                )
                var origIme = ""
                if (shouldYield) {
                    origIme = imeState.originalImeId.trim()
                        .takeIf { it.isNotEmpty() && !BridgeProtocol.isManagedOpalImeId(it) }
                        .orEmpty()
                    BridgeClient.notifyBridgeImeEventAsync(
                        eventType = BridgeProtocol.BridgeImeEvent.YIELD_TO_DISPLAY0,
                        imeDisplayId = 0,
                        inputType = inputType,
                        pkg = pkg,
                    )
                }
                reply.putBoolean("ok", true)
                reply.putBoolean("shouldYield", shouldYield)
                reply.putString("originalImeId", origIme)
                reply.putBoolean("daemonConnected", BridgeClient.statusFlow.value.connected)
            }
            "globalKey" -> {
                val ev = extras?.let { b ->
                    @Suppress("DEPRECATION")
                    b.getParcelable<KeyEvent>("event")
                }
                var handled = false
                if (ev != null) {
                    handled = BridgeClient.tryWakeHostScreenFromKeyEvent(ev) ||
                        WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(ev) ||
                        WorkstationKeyDispatcher.handleGlobalShortcut(ev)
                    if (!handled && ev.keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_DOWN && _softKeyboardVisibleFlow.value) {
                        hideSoftKeyboard()
                        handled = true
                    }
                }
                reply.putBoolean("ok", true)
                reply.putBoolean("handled", handled)
            }
            else -> reply.putBoolean("ok", false)
        }
        return reply
    }

    private fun attachPluginBinder(binder: IBinder, extras: Bundle?) {
        pluginSessionBinder = binder
        runCatching {
            binder.linkToDeath({
                if (pluginSessionBinder === binder) {
                    clearPluginBinder()
                }
            }, 0)
        }
        val schemaId = extras?.getString("schemaId").orEmpty().ifEmpty { "pinyin" }
        val schemaLabel = extras?.getString("schemaLabel").orEmpty().ifEmpty { "语燕全拼" }
        _pluginStateFlow.value = PluginCompositionSnapshot(
            pluginConnected = true,
            schemaId = schemaId,
            schemaLabel = schemaLabel,
        )
    }

    private fun clearPluginBinder() {
        pluginSessionBinder = null
        _pluginStateFlow.value = PluginCompositionSnapshot(pluginConnected = false)
        _softKeyboardVisibleFlow.value = false
        _cursorAnchorFlow.value = ImeCursorAnchorSnapshot()
        if (!core.currentEditorSnapshot().serviceBound) {
            _editorStateFlow.value = ImeEditorSnapshot()
        }
    }

    private fun applyPluginStateBundle(b: Bundle) {
        val inputActive = b.getBoolean("inputActive", false)
        val pkg = b.getString("pkg").orEmpty()
        val inputType = b.getInt("inputType", 0)
        val imeOptions = b.getInt("imeOptions", 0)
        val fieldId = b.getInt("fieldId", 0)
        val imeDispId = b.getInt("imeDisplayId", -1)
        val accepting = OpalDeskImeChannelCore.isAcceptingTextInput(inputType, imeOptions)

        _editorStateFlow.value = ImeEditorSnapshot(
            serviceBound = true,
            inputActive = inputActive,
            packageName = pkg,
            inputType = inputType,
            imeOptions = imeOptions,
            fieldId = fieldId,
            acceptingText = accepting,
            imeDisplayId = imeDispId,
        )
        if (!inputActive || !accepting) {
            _softKeyboardVisibleFlow.value = false
            _cursorAnchorFlow.value = ImeCursorAnchorSnapshot()
        }

        val langStr = b.getString("langMode", "CN")
        val cands = b.getStringArrayList("candidates")?.toList().orEmpty()
        _pluginStateFlow.value = PluginCompositionSnapshot(
            pluginConnected = isPluginSessionConnected,
            languageMode = if (langStr == "EN") ImeLanguageMode.ENGLISH else ImeLanguageMode.CHINESE,
            schemaId = b.getString("schemaId").orEmpty().ifEmpty { "pinyin" },
            schemaLabel = b.getString("schemaLabel").orEmpty().ifEmpty { "语燕全拼" },
            rawBuffer = b.getString("rawBuffer").orEmpty(),
            displayComposing = b.getString("displayComposing").orEmpty(),
            candidates = cands,
            pageIndex = b.getInt("pageIndex", 0),
            pageSize = b.getInt("pageSize", 7).coerceAtLeast(1),
            highlightedInPage = b.getInt("highlightedInPage", 0),
        )
    }

    fun onSystemShowInputRequested(
        acceptingText: Boolean,
        isRemoteCastDisplay: Boolean,
        physicalKeyboardDetected: Boolean,
        showImeWithHardKeyboardMode: Int,
        nowUptimeMs: Long = SystemClock.uptimeMillis(),
    ) {
        if (!isPluginSessionConnected) return
        val tap = _lastTapAnchorFlow.value
        val elapsed = if (tap.uptimeMs > 0L && nowUptimeMs >= tap.uptimeMs) {
            nowUptimeMs - tap.uptimeMs
        } else {
            Long.MAX_VALUE
        }
        if (OpalDeskImeChannelCore.shouldAutoShowCompositorSoftKeyboard(
                acceptingText = acceptingText,
                isRemoteCastDisplay = isRemoteCastDisplay,
                physicalKeyboardDetected = physicalKeyboardDetected,
                showImeWithHardKeyboardMode = showImeWithHardKeyboardMode,
                lastModality = tap.modality,
                msSinceLastPointerDown = elapsed,
            )
        ) {
            _softKeyboardVisibleFlow.value = true
        }
    }

    fun showSoftKeyboard() {
        _softKeyboardVisibleFlow.value = true
    }

    fun hideSoftKeyboard() {
        if (_pluginStateFlow.value.isComposing) {
            sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_DISMISS_COMPOSING)
        }
        _softKeyboardVisibleFlow.value = false
        // Notify Android IMMS via requestHideSelf(0) in the plugin service so IMMS marks the IME
        // window hidden and dispatches onShowInputRequested() again on the user's next tap.
        sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_HIDE_SELF)
    }

    fun toggleSoftKeyboard() {
        if (_softKeyboardVisibleFlow.value) hideSoftKeyboard() else showSoftKeyboard()
    }

    fun toggleFloatingKeyboardMode() {
        _floatingKeyboardModeFlow.value = !_floatingKeyboardModeFlow.value
    }

    // ---- Compositor UI -> Plugin Binder Actions ----

    fun onSoftKeyChar(ch: Char, fallbackCommit: ((String) -> Unit)? = null) {
        val pb = pluginSessionBinder
        if (pb != null && pb.pingBinder()) {
            transactPlugin(pb, BridgeProtocol.PluginImeTx.SOFT_KEY_CHAR, oneway = true, writeArgs = { d ->
                d.writeInt(ch.code)
            }) {}
            return
        }
        fallbackCommit?.invoke(ch.toString())
    }

    fun onSoftBackspace(fallbackKeyTap: ((Int) -> Unit)? = null) {
        if (!sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_BACKSPACE)) {
            fallbackKeyTap?.invoke(KeyEvent.KEYCODE_DEL)
        }
    }

    fun onSoftSpace(fallbackCommit: ((String) -> Unit)? = null) {
        if (!sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_SPACE)) {
            fallbackCommit?.invoke(" ")
        }
    }

    fun onSoftEnter(fallbackKeyTap: ((Int) -> Unit)? = null) {
        if (!sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_ENTER)) {
            fallbackKeyTap?.invoke(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun toggleLanguageMode() {
        sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_TOGGLE_LANG)
    }

    fun cycleSchema() {
        sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_CYCLE_SCHEMA)
    }

    fun selectSchema(schemaId: String) {
        val pb = pluginSessionBinder ?: return
        if (!pb.pingBinder()) return
        transactPlugin(pb, BridgeProtocol.PluginImeTx.SELECT_SCHEMA, oneway = true, writeArgs = { d ->
            d.writeString(schemaId)
        }) {}
    }

    fun onSelectVisibleCandidate(visibleIndex: Int) {
        val pb = pluginSessionBinder ?: return
        if (!pb.pingBinder()) return
        transactPlugin(pb, BridgeProtocol.PluginImeTx.SELECT_CANDIDATE, oneway = true, writeArgs = { d ->
            d.writeInt(visibleIndex)
        }) {}
    }

    fun onNextCandidatePage() {
        sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_NEXT_PAGE)
    }

    fun onPrevCandidatePage() {
        sendPluginSpecialActionOneway(BridgeProtocol.PluginImeTx.SPECIAL_PREV_PAGE)
    }

    fun sendPluginKeyTap(keyCode: Int, metaState: Int = 0): Boolean {
        val pb = pluginSessionBinder ?: return false
        if (!pb.pingBinder()) return false
        return transactPlugin(pb, BridgeProtocol.PluginImeTx.SEND_KEY_TAP, oneway = true, writeArgs = { d ->
            d.writeInt(keyCode)
            d.writeInt(metaState)
        }) { true } == true
    }

    private fun sendPluginSpecialActionOneway(actionCode: Int): Boolean {
        val pb = pluginSessionBinder ?: return false
        if (!pb.pingBinder()) return false
        return transactPlugin(pb, BridgeProtocol.PluginImeTx.SOFT_SPECIAL_ACTION, oneway = true, writeArgs = { d ->
            d.writeInt(actionCode)
        }) { true } == true
    }

    /**
     * Synchronously commits UTF-8 text into the currently bound Android `InputConnection` — routing
     * first to the active YuyanIme companion plugin (if bound), or to the lightweight built-in
     * `OpalDeskImeService` pipe.
     */
    fun commitTextSync(
        text: String,
        rejectSelfPackage: Boolean = false,
        selfPackageName: String = "",
    ): Boolean {
        if (text.isEmpty() || text.length > OpalDeskImeChannelCore.MAX_COMMIT_CHARS) return false
        val pb = pluginSessionBinder
        if (pb != null && pb.pingBinder()) {
            val pluginCommitted = transactPlugin(
                pb,
                BridgeProtocol.PluginImeTx.COMMIT_TEXT,
                oneway = false,
                writeArgs = { d ->
                    d.writeString(text)
                    d.writeInt(if (rejectSelfPackage) 1 else 0)
                    d.writeString(selfPackageName)
                },
            ) { r -> r.readInt() != 0 } == true
            if (pluginCommitted) return true
        }
        if (!core.canCommitNow(text, rejectSelfPackage, selfPackageName)) {
            return false
        }
        return runOnMainSync {
            core.commitTextDirect(text, rejectSelfPackage, selfPackageName)
        }
    }

    fun setComposingTextSync(text: String): Boolean = runOnMainSync {
        core.setComposingTextDirect(text)
    }

    fun finishComposingTextSync(): Boolean = runOnMainSync {
        core.finishComposingTextDirect()
    }

    fun deleteSurroundingTextSync(beforeLength: Int, afterLength: Int): Boolean = runOnMainSync {
        core.deleteSurroundingTextDirect(beforeLength, afterLength)
    }

    fun performEditorActionSync(editorAction: Int): Boolean = runOnMainSync {
        core.performEditorActionDirect(editorAction)
    }

    private inline fun <T> transactPlugin(
        binder: IBinder,
        code: Int,
        oneway: Boolean,
        writeArgs: (Parcel) -> Unit,
        readReply: (Parcel) -> T,
    ): T? {
        val data = Parcel.obtain()
        val reply = if (oneway) null else Parcel.obtain()
        return try {
            data.writeInterfaceToken(BridgeProtocol.PLUGIN_IME_BINDER_DESC)
            writeArgs(data)
            val flags = if (oneway) IBinder.FLAG_ONEWAY else 0
            if (!binder.transact(code, data, reply, flags)) return null
            if (reply != null) readReply(reply) else null
        } catch (_: Throwable) {
            null
        } finally {
            reply?.recycle()
            data.recycle()
        }
    }

    private fun runOnMainSync(action: () -> Boolean): Boolean {
        val mainLooper = Looper.getMainLooper() ?: return action()
        if (Looper.myLooper() == mainLooper) {
            return runCatching(action).getOrDefault(false)
        }
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                result.set(runCatching(action).getOrDefault(false))
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        return try {
            if (latch.await(MAIN_THREAD_COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                result.get()
            } else {
                false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun publishSnapshots() {
        if (!isPluginSessionConnected) {
            _editorStateFlow.value = core.currentEditorSnapshot()
            _cursorAnchorFlow.value = core.currentCursorAnchor()
        }
    }
}
