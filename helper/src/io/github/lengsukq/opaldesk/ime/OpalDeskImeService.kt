package io.github.lengsukq.opaldesk.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore

/**
 * Phase 1 built-in invisible channel IME for OpalDesk.
 *
 * - Never renders an on-screen keyboard on Display 0 or inside isolated `OpalWin-*` VirtualDisplays
 *   (`onEvaluateInputViewShown() == false`), guaranteeing zero phone-screen keyboard popups.
 * - Binds directly to the focused app's real Android [InputConnection] inside the same process as
 *   `BridgeProvider` and `OpalCompositorWorkspace`, enabling zero-clipboard UTF-8 `commitText()`
 *   delivery for Mac/Windows/Web/Tablet OpalCast sessions and external-monitor trackpad typing.
 * - Streams live [EditorInfo] and [CursorAnchorInfo] snapshots into [OpalDeskImeChannel] as the
 *   foundation for Phase 2 desktop-canvas floating candidate bars and soft keyboards.
 */
class OpalDeskImeService : InputMethodService() {
    companion object {
        private const val TAG = "OpalBridgeIME"
    }

    private val connectionAdapter = object : ImeConnectionAdapter {
        override fun beginBatchEdit(): Boolean =
            runCatching { currentInputConnection?.beginBatchEdit() == true }.getOrDefault(false)

        override fun endBatchEdit(): Boolean =
            runCatching { currentInputConnection?.endBatchEdit() == true }.getOrDefault(false)

        override fun commitText(text: String, newCursorPosition: Int): Boolean =
            runCatching { currentInputConnection?.commitText(text, newCursorPosition) == true }.getOrDefault(false)

        override fun setComposingText(text: String, newCursorPosition: Int): Boolean =
            runCatching { currentInputConnection?.setComposingText(text, newCursorPosition) == true }.getOrDefault(false)

        override fun finishComposingText(): Boolean =
            runCatching { currentInputConnection?.finishComposingText() == true }.getOrDefault(false)

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            runCatching { currentInputConnection?.deleteSurroundingText(beforeLength, afterLength) == true }
                .getOrDefault(false)

        override fun sendEnterKey(): Boolean =
            runCatching {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                true
            }.getOrDefault(false)

        override fun performEditorAction(editorAction: Int): Boolean =
            runCatching { currentInputConnection?.performEditorAction(editorAction) == true }.getOrDefault(false)
    }

    override fun onCreate() {
        super.onCreate()
        OpalLogStore.init(applicationContext)
        OpalDeskImeChannel.attachService(connectionAdapter)
        OpalLogStore.i(TAG, "OpalDeskImeService created and attached")
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return false
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = View(this).apply {
        visibility = View.GONE
        layoutParams = ViewGroup.LayoutParams(0, 0)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val pkg = attribute?.packageName.orEmpty()
        val inputType = attribute?.inputType ?: 0
        val imeOptions = attribute?.imeOptions ?: 0
        val fieldId = attribute?.fieldId ?: 0
        OpalDeskImeChannel.onStartInput(pkg, inputType, imeOptions, fieldId)
        if (OpalDeskImeChannelCore.isAcceptingTextInput(inputType, imeOptions)) {
            runCatching {
                currentInputConnection?.requestCursorUpdates(
                    InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
                )
            }
        }
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        val info = cursorAnchorInfo ?: return
        val h = info.insertionMarkerHorizontal
        val top = info.insertionMarkerTop
        val baseline = info.insertionMarkerBaseline
        val bottom = info.insertionMarkerBottom
        val valid = !h.isNaN() && !top.isNaN() && !bottom.isNaN()
        if (valid) {
            OpalDeskImeChannel.onCursorAnchorUpdated(
                ImeCursorAnchorSnapshot(
                    valid = true,
                    insertionMarkerHorizontal = h,
                    insertionMarkerTop = top,
                    insertionMarkerBaseline = if (baseline.isNaN()) bottom else baseline,
                    insertionMarkerBottom = bottom,
                ),
            )
        }
    }

    override fun onFinishInput() {
        OpalDeskImeChannel.onFinishInput()
        super.onFinishInput()
    }

    override fun onDestroy() {
        OpalDeskImeChannel.detachService(connectionAdapter)
        OpalLogStore.i(TAG, "OpalDeskImeService destroyed")
        super.onDestroy()
    }
}
