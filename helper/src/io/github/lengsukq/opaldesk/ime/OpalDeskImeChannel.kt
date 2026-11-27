package io.github.lengsukq.opaldesk.ime

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.IpcTrustVerifier
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class InputModality {
    UNKNOWN,
    TOUCH,
    MOUSE,
    KEYBOARD,
}

enum class ImeLanguageMode {
    CHINESE,
    ENGLISH,
}

data class WindowTapAnchor(
    val windowId: Int = -1,
    val displayId: Int = -1,
    val canvasX: Float = 0f,
    val canvasY: Float = 0f,
    val modality: InputModality = InputModality.UNKNOWN,
    val uptimeMs: Long = 0L,
)

/** Canvas-space caret rectangle used to anchor the Compositor Floating Candidate Bar. */
data class CanvasCaretAnchor(
    val valid: Boolean = false,
    val x: Float = 0f,
    val topY: Float = 0f,
    val bottomY: Float = 0f,
)

/** Live composition & candidate state streamed from the optional YuyanIme companion plugin APK. */
data class PluginCompositionSnapshot(
    val pluginConnected: Boolean = false,
    val languageMode: ImeLanguageMode = ImeLanguageMode.CHINESE,
    val schemaId: String = "pinyin",
    val schemaLabel: String = "语燕全拼",
    val rawBuffer: String = "",
    val displayComposing: String = "",
    val candidates: List<String> = emptyList(),
    val pageIndex: Int = 0,
    val pageSize: Int = 7,
    val highlightedInPage: Int = 0,
) {
    val isComposing: Boolean get() = rawBuffer.isNotEmpty() || displayComposing.isNotEmpty()
    val pageCount: Int
        get() = if (candidates.isEmpty()) 0 else ((candidates.size + pageSize - 1) / pageSize)
    val visibleCandidates: List<String>
        get() {
            if (candidates.isEmpty()) return emptyList()
            val safePage = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val from = safePage * pageSize
            val to = (from + pageSize).coerceAtMost(candidates.size)
            return if (from < to) candidates.subList(from, to) else emptyList()
        }
}

/** Immutable snapshot of the editor currently attached to [OpalDeskImeService] or `OpalYuyanImeService`. */
data class ImeEditorSnapshot(
    val serviceBound: Boolean = false,
    val inputActive: Boolean = false,
    val packageName: String = "",
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    val fieldId: Int = 0,
    val acceptingText: Boolean = false,
    val imeDisplayId: Int = -1,
)

/**
 * Caret anchor coordinates in the target app's display space (transformed via `CursorAnchorInfo.matrix`),
 * captured via `InputMethodService.onUpdateCursorAnchorInfo` for floating candidate-bar positioning.
 */
data class ImeCursorAnchorSnapshot(
    val valid: Boolean = false,
    val insertionMarkerHorizontal: Float = 0f,
    val insertionMarkerTop: Float = 0f,
    val insertionMarkerBaseline: Float = 0f,
    val insertionMarkerBottom: Float = 0f,
    val imeDisplayId: Int = -1,
)

/** Pure JVM-testable adapter abstracting Android's `InputConnection`. */
interface ImeConnectionAdapter {
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
    fun commitText(text: String, newCursorPosition: Int): Boolean
    fun setComposingText(text: String, newCursorPosition: Int): Boolean
    fun finishComposingText(): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun sendEnterKey(): Boolean
    fun performEditorAction(editorAction: Int): Boolean
    fun sendKeyTap(keyCode: Int, metaState: Int = 0): Boolean = false
}

/**
 * Pure JVM-testable state machine, policy evaluator, and text-commit engine behind [OpalDeskImeChannel].
 *
 * Keeps the main `:helper` APK 100% free of embedded dictionary/Pinyin bloat while orchestrating:
 * 1) Zero-clipboard UTF-8 commits (`OpalDeskImeService` pipe or external `OpalYuyanImeService` plugin),
 * 2) Display 0 native phone IME smart handoff,
 * 3) Touch vs Bluetooth mouse soft-keyboard popup policy, and
 * 4) `CursorAnchorInfo` VirtualDisplay-to-Canvas coordinate projection.
 */
class OpalDeskImeChannelCore {
    companion object {
        const val MAX_COMMIT_CHARS = 65_536
        const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000
        const val TOUCH_TAP_FRESH_WINDOW_MS = 1_800L

        fun isAcceptingTextInput(inputType: Int, imeOptions: Int): Boolean =
            inputType != 0 || imeOptions != 0

        fun normalizeNewlines(raw: String): String {
            if (!raw.contains('\r')) return raw
            return raw.replace("\r\n", "\n").replace('\r', '\n')
        }

        /**
         * Decides whether the managed DeX/Plugin IME should yield back to the user's original phone
         * IME (`switchInputMethod(originalImeId)`) because the user tapped an editable text box on
         * Phone Display 0 while DeX is running on an external monitor or wireless cast.
         */
        fun shouldYieldToDisplay0OriginalIme(
            imeDisplayId: Int,
            localDesktopPinned: Boolean,
            acceptingText: Boolean,
            focusedLiveWindowPkg: String?,
            focusedLiveWindowImePolicyLocal: Boolean,
            targetPkg: String,
            hostScreenOff: Boolean = false,
            openLiveWindowPkgs: Set<String> = emptySet(),
        ): Boolean {
            if (!acceptingText) return false
            if (localDesktopPinned) return false
            if (hostScreenOff) return false
            if (imeDisplayId > 0) return false
            // During InputMethodService.onStartInput(), IMMS has not always moved the IME window
            // to the OpalWin-* VirtualDisplay yet, so imeDisplayId can momentarily report 0.
            // Never yield away from Yuyan/OpalDesk IME when targetPkg is already a live DeX window.
            if (!focusedLiveWindowPkg.isNullOrEmpty() && focusedLiveWindowPkg == targetPkg) {
                return false
            }
            if (targetPkg.isNotEmpty() && openLiveWindowPkgs.contains(targetPkg)) {
                return false
            }
            return true
        }

        /**
         * Decides whether `OpalCompositorWorkspace` should automatically display the Yuyan plugin's
         * Compositor Desktop Soft Keyboard when an editable field requests soft input.
         */
        fun shouldAutoShowCompositorSoftKeyboard(
            acceptingText: Boolean,
            isRemoteCastDisplay: Boolean,
            physicalKeyboardDetected: Boolean,
            showImeWithHardKeyboardMode: Int,
            lastModality: InputModality,
            msSinceLastPointerDown: Long,
        ): Boolean {
            if (!acceptingText || isRemoteCastDisplay) return false
            if (!physicalKeyboardDetected) return true
            if (showImeWithHardKeyboardMode == BridgeProtocol.HardKbdSoftIme.SHOW) return true
            if (showImeWithHardKeyboardMode == BridgeProtocol.HardKbdSoftIme.HIDE) return false
            return lastModality == InputModality.TOUCH &&
                msSinceLastPointerDown in 0L..TOUCH_TAP_FRESH_WINDOW_MS
        }

        /**
         * Maps a `CursorAnchorInfo` caret position (in `OpalWin-*` VirtualDisplay pixel space) or
         * fallback last-tap anchor into `OpalCompositorWorkspace` canvas coordinates.
         */
        fun mapDisplayAnchorToCanvas(
            window: CompositorWindow?,
            cursorAnchor: ImeCursorAnchorSnapshot,
            tapAnchor: WindowTapAnchor,
            headerHeightPx: Int,
            cropTopDisplayPx: Int = 0,
        ): CanvasCaretAnchor {
            if (window == null || window.isMinimized) {
                return CanvasCaretAnchor(valid = false)
            }
            val effectiveHeader = if (window.isFullscreen) 0 else headerHeightPx.coerceAtLeast(0)
            val contentLeft = window.frame.left.toFloat()
            val contentTop = (window.frame.top + effectiveHeader).toFloat()
            val viewW = window.frame.width.coerceAtLeast(1).toFloat()
            val viewH = (window.frame.height - effectiveHeader).coerceAtLeast(1).toFloat()

            val cursorMatchesWindow = cursorAnchor.valid &&
                (cursorAnchor.imeDisplayId <= 0 || cursorAnchor.imeDisplayId == window.displayId)
            if (cursorMatchesWindow && window.contentWidth > 0 && window.contentHeight > 0) {
                val cw = window.contentWidth.coerceAtLeast(1).toFloat()
                val safeCrop = cropTopDisplayPx.coerceIn(0, (window.contentHeight - 2).coerceAtLeast(0))
                val visibleCh = (window.contentHeight - safeCrop).coerceAtLeast(1).toFloat()

                val normX = (cursorAnchor.insertionMarkerHorizontal / cw).coerceIn(0f, 1f)
                val normTop = ((cursorAnchor.insertionMarkerTop - safeCrop) / visibleCh).coerceIn(0f, 1f)
                val normBot = ((cursorAnchor.insertionMarkerBottom - safeCrop) / visibleCh).coerceIn(normTop, 1f)

                return CanvasCaretAnchor(
                    valid = true,
                    x = contentLeft + normX * viewW,
                    topY = contentTop + normTop * viewH,
                    bottomY = contentTop + normBot * viewH,
                )
            }

            if (tapAnchor.windowId == window.windowId) {
                val clampedX = tapAnchor.canvasX.coerceIn(contentLeft, contentLeft + viewW)
                val clampedY = tapAnchor.canvasY.coerceIn(contentTop, contentTop + viewH)
                return CanvasCaretAnchor(
                    valid = true,
                    x = clampedX,
                    topY = (clampedY - 22f).coerceAtLeast(contentTop),
                    bottomY = (clampedY + 8f).coerceAtMost(contentTop + viewH),
                )
            }

            return CanvasCaretAnchor(
                valid = true,
                x = contentLeft + viewW * 0.35f,
                topY = contentTop + viewH * 0.72f,
                bottomY = contentTop + viewH * 0.78f,
            )
        }
    }

    private val lock = Any()
    private var adapter: ImeConnectionAdapter? = null
    private var snapshot = ImeEditorSnapshot()
    private var cursorAnchor = ImeCursorAnchorSnapshot()

    fun currentEditorSnapshot(): ImeEditorSnapshot = synchronized(lock) { snapshot }
    fun currentCursorAnchor(): ImeCursorAnchorSnapshot = synchronized(lock) { cursorAnchor }

    fun onServiceAttached(connectionAdapter: ImeConnectionAdapter) {
        synchronized(lock) {
            adapter = connectionAdapter
            snapshot = snapshot.copy(serviceBound = true)
        }
    }

    fun onServiceDetached(connectionAdapter: ImeConnectionAdapter) {
        synchronized(lock) {
            if (adapter === connectionAdapter) {
                adapter = null
                snapshot = ImeEditorSnapshot()
                cursorAnchor = ImeCursorAnchorSnapshot()
            }
        }
    }

    fun onStartInput(
        packageName: String,
        inputType: Int,
        imeOptions: Int,
        fieldId: Int,
        imeDisplayId: Int = -1,
    ) {
        val cleanPkg = packageName.trim()
        val accepting = isAcceptingTextInput(inputType, imeOptions)
        synchronized(lock) {
            snapshot = ImeEditorSnapshot(
                serviceBound = adapter != null,
                inputActive = true,
                packageName = cleanPkg,
                inputType = inputType,
                imeOptions = imeOptions,
                fieldId = fieldId,
                acceptingText = accepting,
                imeDisplayId = imeDisplayId,
            )
            cursorAnchor = ImeCursorAnchorSnapshot()
        }
    }

    fun onFinishInput() {
        synchronized(lock) {
            snapshot = snapshot.copy(
                inputActive = false,
                packageName = "",
                inputType = 0,
                imeOptions = 0,
                fieldId = 0,
                acceptingText = false,
                imeDisplayId = -1,
            )
            cursorAnchor = ImeCursorAnchorSnapshot()
        }
    }

    fun onCursorAnchorUpdated(next: ImeCursorAnchorSnapshot) {
        synchronized(lock) {
            if (snapshot.inputActive) {
                cursorAnchor = next
            }
        }
    }

    fun canCommitNow(
        text: String,
        rejectSelfPackage: Boolean,
        selfPackageName: String,
    ): Boolean {
        if (text.isEmpty() || text.length > MAX_COMMIT_CHARS) return false
        val st = synchronized(lock) {
            if (adapter == null) return false
            snapshot
        }
        if (!st.serviceBound || !st.inputActive || !st.acceptingText) return false
        if (rejectSelfPackage && selfPackageName.isNotEmpty() && st.packageName == selfPackageName) {
            return false
        }
        return true
    }

    fun commitTextDirect(
        text: String,
        rejectSelfPackage: Boolean = false,
        selfPackageName: String = "",
    ): Boolean {
        if (!canCommitNow(text, rejectSelfPackage, selfPackageName)) return false
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false

        val normalized = normalizeNewlines(text)
        if (!normalized.contains('\n')) {
            return conn.commitText(normalized, 1)
        }

        val multiLine = (st.inputType and TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val lines = normalized.split('\n')
        conn.beginBatchEdit()
        return try {
            var allOk = true
            lines.forEachIndexed { idx, segment ->
                if (segment.isNotEmpty()) {
                    if (!conn.commitText(segment, 1)) allOk = false
                }
                if (idx < lines.lastIndex) {
                    val newlineOk = if (multiLine) conn.commitText("\n", 1) else conn.sendEnterKey()
                    if (!newlineOk) allOk = false
                }
            }
            allOk
        } finally {
            conn.endBatchEdit()
        }
    }

    fun setComposingTextDirect(text: String): Boolean {
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false
        return if (text.isEmpty()) {
            conn.setComposingText("", 1)
            conn.finishComposingText()
        } else {
            conn.setComposingText(text, 1)
        }
    }

    fun finishComposingTextDirect(): Boolean {
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false
        return conn.finishComposingText()
    }

    fun deleteSurroundingTextDirect(beforeLength: Int, afterLength: Int): Boolean {
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false
        return conn.deleteSurroundingText(beforeLength.coerceAtLeast(0), afterLength.coerceAtLeast(0))
    }

    fun performEditorActionDirect(editorAction: Int): Boolean {
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false
        return conn.performEditorAction(editorAction)
    }

    fun sendKeyTapDirect(keyCode: Int, metaState: Int = 0): Boolean {
        val conn = synchronized(lock) { adapter } ?: return false
        return conn.sendKeyTap(keyCode, metaState)
    }
}

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
