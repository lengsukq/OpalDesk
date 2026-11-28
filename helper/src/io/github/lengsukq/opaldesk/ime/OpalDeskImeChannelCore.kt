package io.github.lengsukq.opaldesk.ime

import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorWindow

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
