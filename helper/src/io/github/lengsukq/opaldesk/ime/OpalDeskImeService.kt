package io.github.lengsukq.opaldesk.ime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher

/**
 * Ultra-lightweight (~5 KB, zero UI, zero embedded dictionary) silent text-commit & event pipe
 * used exclusively during remote `OpalCast` sessions when the optional `OpalDesk 语燕输入法插件`
 * (`io.github.lengsukq.opaldesk.ime`) is not installed.
 *
 * Automatically yields Display 0 back to the user's backed-up phone IME whenever the user taps an
 * editable field on Phone Display 0 while casting.
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

        override fun sendKeyTap(keyCode: Int, metaState: Int): Boolean =
            runCatching {
                if (metaState == 0) {
                    sendDownUpKeyEvents(keyCode)
                    true
                } else {
                    val ic = currentInputConnection ?: return@runCatching false
                    val now = SystemClock.uptimeMillis()
                    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
                    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
                    true
                }
            }.getOrDefault(false)
    }

    override fun onCreate() {
        super.onCreate()
        OpalLogStore.init(applicationContext)
        BridgeClient.bindAppContext(applicationContext)
        OpalDeskImeChannel.attachService(connectionAdapter)
        OpalLogStore.i(TAG, "OpalDeskImeService (lightweight cast pipe) attached")
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
        val imeDispId = resolveCurrentImeDisplayId()
        val accepting = OpalDeskImeChannelCore.isAcceptingTextInput(inputType, imeOptions)

        if (shouldYieldToPhoneIme(imeDispId, pkg, accepting)) {
            yieldToDisplay0OriginalIme(pkg, inputType)
            return
        }

        OpalDeskImeChannel.onStartInput(pkg, inputType, imeOptions, fieldId, imeDispId)
        if (accepting) {
            runCatching {
                currentInputConnection?.requestCursorUpdates(
                    InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
                )
            }
        }
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val attr = currentInputEditorInfo
        val pkg = attr?.packageName.orEmpty()
        val inputType = attr?.inputType ?: 0
        val imeOptions = attr?.imeOptions ?: 0
        val fieldId = attr?.fieldId ?: 0
        val imeDispId = resolveCurrentImeDisplayId()
        val accepting = OpalDeskImeChannelCore.isAcceptingTextInput(inputType, imeOptions)
        if (!accepting) return false

        if (shouldYieldToPhoneIme(imeDispId, pkg, accepting = true)) {
            yieldToDisplay0OriginalIme(pkg, inputType)
            return false
        }

        val curEd = OpalDeskImeChannel.editorStateFlow.value
        if (!curEd.inputActive || curEd.packageName != pkg || curEd.fieldId != fieldId) {
            OpalDeskImeChannel.onStartInput(pkg, inputType, imeOptions, fieldId, imeDispId)
        }
        BridgeClient.notifyBridgeImeEventAsync(
            eventType = BridgeProtocol.BridgeImeEvent.SHOW_REQUESTED,
            imeDisplayId = imeDispId,
            inputType = inputType,
            pkg = pkg,
        )
        return true
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        val info = cursorAnchorInfo ?: return
        val h = info.insertionMarkerHorizontal
        val top = info.insertionMarkerTop
        val baseline = info.insertionMarkerBaseline
        val bottom = info.insertionMarkerBottom
        if (h.isNaN() || top.isNaN() || bottom.isNaN()) return

        val baseVal = if (baseline.isNaN()) bottom else baseline
        val pts = floatArrayOf(h, top, h, baseVal, h, bottom)
        runCatching { info.matrix.mapPoints(pts) }
        if (pts[0].isNaN() || pts[1].isNaN() || pts[5].isNaN()) return

        OpalDeskImeChannel.onCursorAnchorUpdated(
            ImeCursorAnchorSnapshot(
                valid = true,
                insertionMarkerHorizontal = pts[0],
                insertionMarkerTop = minOf(pts[1], pts[5]),
                insertionMarkerBaseline = pts[3],
                insertionMarkerBottom = maxOf(pts[1], pts[5]),
                imeDisplayId = resolveCurrentImeDisplayId(),
            ),
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (BridgeClient.tryWakeHostScreenFromKeyEvent(event)) return true
        if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowHidden() {
        val imeDispId = resolveCurrentImeDisplayId()
        if (imeDispId > 0) {
            BridgeClient.notifyBridgeImeEventAsync(
                eventType = BridgeProtocol.BridgeImeEvent.HIDE_OR_FINISH,
                imeDisplayId = imeDispId,
            )
        }
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        val prevDispId = OpalDeskImeChannel.editorStateFlow.value.imeDisplayId
        OpalDeskImeChannel.onFinishInput()
        if (prevDispId > 0) {
            BridgeClient.notifyBridgeImeEventAsync(
                eventType = BridgeProtocol.BridgeImeEvent.HIDE_OR_FINISH,
                imeDisplayId = prevDispId,
            )
        }
        super.onFinishInput()
    }

    override fun onDestroy() {
        OpalDeskImeChannel.detachService(connectionAdapter)
        super.onDestroy()
    }

    private fun resolveCurrentImeDisplayId(): Int {
        val winDisp = runCatching { window?.window?.decorView?.display?.displayId }.getOrNull()
        if (winDisp != null && winDisp >= 0) return winDisp
        val ctxDisp = runCatching { display?.displayId }.getOrNull()
        if (ctxDisp != null && ctxDisp >= 0) return ctxDisp
        return Display.DEFAULT_DISPLAY
    }

    private fun shouldYieldToPhoneIme(imeDispId: Int, targetPkg: String, accepting: Boolean): Boolean {
        val scene = BridgeClient.sceneFlow.value
        val imeState = BridgeClient.imeStateFlow.value
        val focusedLivePkg = scene.focusedWindow?.takeIf { it.isLive && !it.isMinimized }?.pkg
        val policyIsLocal = imeState.effectiveWindowImePolicy == BridgeProtocol.DisplayImePolicy.LOCAL
        return OpalDeskImeChannelCore.shouldYieldToDisplay0OriginalIme(
            imeDisplayId = imeDispId,
            localDesktopPinned = BridgeClient.isLocalDesktopPinned(),
            acceptingText = accepting,
            focusedLiveWindowPkg = focusedLivePkg,
            focusedLiveWindowImePolicyLocal = policyIsLocal,
            targetPkg = targetPkg,
        )
    }

    private fun yieldToDisplay0OriginalIme(pkg: String, inputType: Int) {
        val cachedOrig = BridgeClient.imeStateFlow.value.originalImeId.trim()
        if (cachedOrig.isNotEmpty() && !BridgeProtocol.isManagedOpalImeId(cachedOrig)) {
            runCatching { switchInputMethod(cachedOrig) }
        }
        BridgeClient.notifyBridgeImeEventAsync(
            eventType = BridgeProtocol.BridgeImeEvent.YIELD_TO_DISPLAY0,
            imeDisplayId = Display.DEFAULT_DISPLAY,
            inputType = inputType,
            pkg = pkg,
        ) { daemonOrig ->
            val resolved = daemonOrig.trim()
            if (resolved.isNotEmpty() &&
                !BridgeProtocol.isManagedOpalImeId(resolved) &&
                resolved != cachedOrig
            ) {
                runCatching { switchInputMethod(resolved) }
            }
        }
    }
}
