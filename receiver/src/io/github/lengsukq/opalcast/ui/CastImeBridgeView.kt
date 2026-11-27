package io.github.lengsukq.opalcast.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Pure Android Framework (API 21–37, zero AndroidX) invisible 1px native InputConnection view
 * that summons the local Android IME (Chinese Pinyin, voice, handwriting, Gboard) and routes
 * ASCII keys + UTF-8 phrases directly over OpalCast to the remote Android host.
 */
@SuppressLint("ViewConstructor")
internal class CastImeBridgeView(
    ctx: Context,
    private val onSendKey: (action: Int, keyCode: Int, metaState: Int) -> Unit,
    private val onCommitText: (String) -> Unit,
    private val onComposingChanged: (String) -> Unit,
    private val onImeVisibilityChanged: (Boolean) -> Unit,
) : View(ctx) {

    companion object {
        private const val DEFAULT_INPUT_TYPE =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private val keyCharMap: KeyCharacterMap? by lazy {
        runCatching { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }.getOrNull()
    }
    private var activeInputType = DEFAULT_INPUT_TYPE
    private var imeShown = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setOnApplyWindowInsetsListener { _, insets ->
                val vis = insets.isVisible(WindowInsets.Type.ime())
                updateImeVisible(vis)
                insets
            }
        }
    }

    val isImeVisible: Boolean
        get() = imeShown

    private fun updateImeVisible(visible: Boolean) {
        if (imeShown != visible) {
            imeShown = visible
            onImeVisibilityChanged(visible)
            if (!visible) {
                onComposingChanged("")
                clearFocus()
            }
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = activeInputType
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_SEND
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                onComposingChanged("")
                val str = text?.toString().orEmpty()
                if (str.isNotEmpty()) dispatchCommittedString(str)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                onComposingChanged(text?.toString().orEmpty())
                return true
            }

            override fun finishComposingText(): Boolean {
                onComposingChanged("")
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 16)) { sendTap(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength.coerceIn(0, 16)) { sendTap(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                    onSendKey(event.action, event.keyCode, event.metaState)
                    return true
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                onComposingChanged("")
                sendTap(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    @Suppress("DEPRECATION")
    fun showIme(inputType: Int = 0) {
        activeInputType = if (inputType != 0) inputType else DEFAULT_INPUT_TYPE
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { windowInsetsController?.show(WindowInsets.Type.ime()) }
        }
        val shown = imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) ?: false
        if (!shown) {
            imm?.showSoftInput(this, InputMethodManager.SHOW_FORCED)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            updateImeVisible(true)
        }
    }

    fun hideIme() {
        onComposingChanged("")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { windowInsetsController?.hide(WindowInsets.Type.ime()) }
        } else {
            updateImeVisible(false)
        }
        clearFocus()
    }

    fun toggleIme() {
        if (imeShown) hideIme() else showIme()
    }

    private fun dispatchCommittedString(raw: String) {
        val lines = raw.split('\n')
        lines.forEachIndexed { idx, segment ->
            if (segment.isNotEmpty()) {
                if (segment.length <= 64 && segment.all { it.code in 0x20..0x7E }) {
                    val events = keyCharMap?.getEvents(segment.toCharArray())
                    if (events != null && events.isNotEmpty()) {
                        for (ev in events) onSendKey(ev.action, ev.keyCode, ev.metaState)
                    } else {
                        onCommitText(segment)
                    }
                } else {
                    onCommitText(segment)
                }
            }
            if (idx < lines.lastIndex) sendTap(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun sendTap(keyCode: Int) {
        onSendKey(KeyEvent.ACTION_DOWN, keyCode, 0)
        onSendKey(KeyEvent.ACTION_UP, keyCode, 0)
    }
}
