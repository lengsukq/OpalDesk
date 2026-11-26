package io.github.lengsukq.opaldesk.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.compositor.applyWindowTilingLayout
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol

/**
 * Tracks whether an in-process Compose text field (such as App Drawer search or Trackpad IME bar)
 * currently holds focus so physical keyboards & remote keystreams never lose Space/Enter/Arrow keys
 * to accidental Compose button focus traversal, yet still type cleanly into OpalDesk search boxes.
 */
object OpalImeFocusTracker {
    @Volatile
    var localComposeTextFieldFocused: Boolean = false
        private set

    fun setLocalTextFieldFocused(focused: Boolean) {
        if (localComposeTextFieldFocused == focused) return
        localComposeTextFieldFocused = focused
        BridgeClient.setLocalTextFieldFocusedOneway(focused)
    }
}

/**
 * Shared physical/remote keyboard dispatcher across [io.github.lengsukq.opaldesk.DesktopActivity],
 * [io.github.lengsukq.opaldesk.ExternalDesktopActivity], and
 * [io.github.lengsukq.opaldesk.external.ExternalMonitorPresentation].
 */
object WorkstationKeyDispatcher {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textCommitExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalImeTextCommit").apply { isDaemon = true }
    }
    private val consumedShortcutKeyCodes = HashSet<Int>()

    fun handleGlobalShortcut(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            return consumedShortcutKeyCodes.remove(event.keyCode)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val scene = BridgeClient.sceneFlow.value
        val focused = scene.focusedWindow
        val action = WorkstationShortcutCatalog.resolveGlobalShortcutAction(
            keyCode = event.keyCode,
            metaState = event.metaState,
            hasFocusedWindow = focused != null,
            focusedIsFullscreen = focused?.isFullscreen == true,
            globalEnabled = AppPrefs.globalShortcutsEnabledFlow.value,
            disabledIds = AppPrefs.disabledShortcutIdsFlow.value,
        ) ?: return false

        when (action) {
            GlobalShortcutAction.SwitchNextWindow -> {
                val candidates = scene.windows.filter { !it.isMinimized }
                if (candidates.size >= 2) {
                    val idx = candidates.indexOfFirst { it.focused }
                    val next = candidates[(idx + 1) % candidates.size]
                    BridgeClient.sendWindowActionAsync(next.windowId, BridgeProtocol.WinAction.FOCUS)
                }
            }
            is GlobalShortcutAction.ApplyTiling -> {
                val st = BridgeClient.statusFlow.value
                applyWindowTilingLayout(
                    layout = action.layout,
                    scene = scene,
                    canvasW = st.canvasWidth,
                    canvasH = st.canvasHeight,
                    density = (st.canvasDpi / 160f).coerceIn(0.8f, 3.5f),
                )
            }
            GlobalShortcutAction.CloseFocusedWindow -> {
                if (focused != null) {
                    BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.CLOSE)
                }
            }
            GlobalShortcutAction.ToggleFullscreen,
            GlobalShortcutAction.ExitFullscreen,
            -> {
                if (focused != null) {
                    BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.TOGGLE_FULLSCREEN)
                }
            }
        }
        consumedShortcutKeyCodes.add(event.keyCode)
        return true
    }

    /**
     * Pure JVM-testable matcher identifying real physical keyboard devices (Bluetooth / USB / OTG)
     * while rejecting virtual keyboards, soft-keyboard synthetic events (`deviceId <= 0`), and
     * OpalDesk's own kernel UHID keyboards.
     */
    fun isPhysicalKeyboardDevice(
        deviceId: Int,
        name: String?,
        isVirtual: Boolean,
        isExternal: Boolean,
        keyboardType: Int,
    ): Boolean {
        if (deviceId <= 0 || isVirtual) return false
        if (name != null && name.startsWith("OpalDesk Virtual")) return false
        return isExternal || keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    fun isPhysicalKeyboardEvent(event: KeyEvent): Boolean {
        val devId = event.deviceId
        if (devId <= 0) return false
        val dev = event.device ?: return true
        return isPhysicalKeyboardDevice(
            deviceId = devId,
            name = dev.name,
            isVirtual = dev.isVirtual,
            isExternal = dev.isExternal,
            keyboardType = dev.keyboardType,
        )
    }

    /**
     * Pure JVM-testable policy deciding whether an IME switch shortcut (`Shift+Space`, `Ctrl+Space`,
     * `KEYCODE_LANGUAGE_SWITCH`, etc.) should be forwarded directly to the focused window's local IME
     * (physical keyboard mode with `DISPLAY_IME_POLICY_LOCAL`) rather than hijacking focus onto the
     * host Activity's soft-keyboard `NativeImeBridgeHost`.
     */
    fun shouldForwardImeSwitchToWindow(
        localComposeTextFieldFocused: Boolean,
        hostSoftImeVisible: Boolean,
        isPhysicalKeyEvent: Boolean,
    ): Boolean = !localComposeTextFieldFocused && !hostSoftImeVisible && isPhysicalKeyEvent

    /**
     * Pure JVM-testable matcher for physical/Bluetooth keyboard IME language-switch keys and chords
     * (e.g. Ctrl+Space, Shift+Space, Meta+Space, Globe/Language-Switch, CJK toggle keys).
     */
    fun isImeSwitchShortcut(keyCode: Int, metaState: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_LANGUAGE_SWITCH,
            KeyEvent.KEYCODE_SWITCH_CHARSET,
            KeyEvent.KEYCODE_ZENKAKU_HANKAKU,
            KeyEvent.KEYCODE_EISU,
            KeyEvent.KEYCODE_KANA,
            KeyEvent.KEYCODE_HENKAN,
            KeyEvent.KEYCODE_MUHENKAN,
            KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> return true
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            val hasSwitchMod = (metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON)) != 0
            val hasAlt = (metaState and KeyEvent.META_ALT_ON) != 0
            if (hasSwitchMod && !hasAlt) return true
        }
        return false
    }

    fun isForwardableKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH -> false
        else -> true
    }

    fun forwardToFocusedWindow(event: KeyEvent, forwardImeSwitch: Boolean = false): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (!isForwardableKey(event.keyCode)) return false
        if (!forwardImeSwitch && isImeSwitchShortcut(event.keyCode, event.metaState)) return false
        val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return false
        if (focused.isMinimized) return false
        val effDeviceId = if (isPhysicalKeyboardEvent(event)) {
            event.deviceId
        } else {
            KeyCharacterMap.VIRTUAL_KEYBOARD
        }
        BridgeClient.injectWindowKeyAsync(
            windowId = focused.windowId,
            action = event.action,
            keyCode = event.keyCode,
            metaState = event.metaState,
            deviceId = effDeviceId,
            scanCode = event.scanCode,
            repeatCount = event.repeatCount,
        )
        return true
    }

    fun commitTextToFocusedWindow(ctx: Context, text: String) {
        if (text.isEmpty()) return
        val appCtx = ctx.applicationContext
        // Serialize clipboard + Ctrl+V commits on a dedicated single thread so rapid consecutive
        // Chinese IME phrases in apps like WeChat never overwrite each other before pasting lands.
        textCommitExec.execute {
            val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return@execute
            if (focused.isMinimized) return@execute
            val clipLatch = CountDownLatch(1)
            mainHandler.post {
                try {
                    val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk", text))
                } catch (_: Throwable) {
                } finally {
                    clipLatch.countDown()
                }
            }
            try {
                clipLatch.await(150L, TimeUnit.MILLISECONDS)
                Thread.sleep(32L)
                val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl)
                BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl)
                Thread.sleep(45L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

/**
 * Imperative controller for summoning / hiding the host device's native system IME (Xiaomi / Sogou /
 * Gboard / Baidu / handwriting / voice) directly onto a SurfaceView cast stream or VirtualDisplay canvas.
 */
class NativeImeHostController {
    internal var bridgeView: NativeImeBridgeView? = null

    fun showKeyboard(inputType: Int = 0) {
        bridgeView?.showIme(inputType)
    }

    fun hideKeyboard() {
        bridgeView?.hideIme()
    }

    fun toggleKeyboard(currentlyVisible: Boolean) {
        if (currentlyVisible) hideKeyboard() else showKeyboard()
    }
}

@Composable
fun rememberNativeImeHostController(): NativeImeHostController = remember { NativeImeHostController() }

/**
 * Invisible 1dp native [InputConnection] host that summons the device's built-in IME and routes
 * ASCII keys, Chinese/Unicode phrases, backspace, and Enter/Search actions seamlessly.
 */
@Composable
fun NativeImeBridgeHost(
    controller: NativeImeHostController,
    onSendKey: (action: Int, keyCode: Int, metaState: Int) -> Unit,
    onCommitText: (String) -> Unit,
    onComposingChanged: (String) -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val curSendKey by rememberUpdatedState(onSendKey)
    val curCommitText by rememberUpdatedState(onCommitText)
    val curComposing by rememberUpdatedState(onComposingChanged)
    val curVisibility by rememberUpdatedState(onImeVisibilityChanged)

    DisposableEffect(controller) {
        onDispose {
            controller.hideKeyboard()
            controller.bridgeView = null
        }
    }

    AndroidView(
        modifier = modifier.size(1.dp),
        factory = { ctx ->
            NativeImeBridgeView(
                ctx = ctx,
                onSendKey = { a, k, m -> curSendKey(a, k, m) },
                onCommitText = { txt -> curCommitText(txt) },
                onComposingChanged = { comp -> curComposing(comp) },
                onImeVisibilityChanged = { vis -> curVisibility(vis) },
            ).also { v ->
                controller.bridgeView = v
            }
        },
        update = { v ->
            controller.bridgeView = v
        },
    )
}

@SuppressLint("ViewConstructor")
internal class NativeImeBridgeView(
    ctx: Context,
    private val onSendKey: (Int, Int, Int) -> Unit,
    private val onCommitText: (String) -> Unit,
    private val onComposingChanged: (String) -> Unit,
    private val onImeVisibilityChanged: (Boolean) -> Unit,
) : View(ctx) {

    companion object {
        private const val DEFAULT_INPUT_TYPE =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private val keyCharMap: KeyCharacterMap? by lazy {
        try {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        } catch (_: Throwable) {
            null
        }
    }
    private var lastImeVisible = false
    private var pendingShowAfterFocus = false
    private var showAttemptGen = 0L
    private var activeInputType = DEFAULT_INPUT_TYPE

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible != lastImeVisible) {
                lastImeVisible = visible
                onImeVisibilityChanged(visible)
                if (!visible) {
                    onComposingChanged("")
                    if (!pendingShowAfterFocus) {
                        clearFocus()
                        BridgeClient.setLocalTextFieldFocusedOneway(false)
                    }
                }
            }
            insets
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && pendingShowAfterFocus && isAttachedToWindow) {
            pendingShowAfterFocus = false
            triggerShowNow()
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
                if (str.isNotEmpty()) {
                    dispatchCommittedString(str)
                }
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
                repeat(beforeLength.coerceIn(0, 16)) {
                    sendTap(KeyEvent.KEYCODE_DEL)
                }
                repeat(afterLength.coerceIn(0, 16)) {
                    sendTap(KeyEvent.KEYCODE_FORWARD_DEL)
                }
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
        val gen = ++showAttemptGen
        // Tell the daemon to keep Display 0 top-focused so InputMethodManagerService accepts showSoftInput
        // and subsequent forwarded keystrokes do not steal top display focus away from the host Activity.
        BridgeClient.setLocalTextFieldFocusedOneway(true)
        pendingShowAfterFocus = true
        triggerShowNow()
        postDelayed({
            if (!isAttachedToWindow || showAttemptGen != gen) return@postDelayed
            pendingShowAfterFocus = false
            if (!lastImeVisible) {
                triggerShowNow()
            }
        }, 120L)
        postDelayed({
            if (!isAttachedToWindow || showAttemptGen != gen) return@postDelayed
            pendingShowAfterFocus = false
            if (!lastImeVisible) {
                // Soft IME stayed hidden (e.g. a physical keyboard suppresses soft input): release
                // focus and daemon localImeActive lock so window keyboard input never gets stranded.
                clearFocus()
                if (!OpalImeFocusTracker.localComposeTextFieldFocused) {
                    BridgeClient.setLocalTextFieldFocusedOneway(false)
                }
            }
        }, 450L)
    }

    @Suppress("DEPRECATION")
    private fun triggerShowNow() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
        findHostActivity(context)?.window?.let { win ->
            WindowCompat.getInsetsController(win, this).show(WindowInsetsCompat.Type.ime())
        }
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideIme() {
        showAttemptGen++
        pendingShowAfterFocus = false
        onComposingChanged("")
        BridgeClient.setLocalTextFieldFocusedOneway(false)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        findHostActivity(context)?.window?.let { win ->
            WindowCompat.getInsetsController(win, this).hide(WindowInsetsCompat.Type.ime())
        }
        clearFocus()
    }

    private fun dispatchCommittedString(raw: String) {
        val lines = raw.split('\n')
        lines.forEachIndexed { idx, segment ->
            if (segment.isNotEmpty()) {
                if (segment.length <= 64 && segment.all { it.code in 0x20..0x7E }) {
                    val events = keyCharMap?.getEvents(segment.toCharArray())
                    if (events != null && events.isNotEmpty()) {
                        for (ev in events) {
                            onSendKey(ev.action, ev.keyCode, ev.metaState)
                        }
                    } else {
                        onCommitText(segment)
                    }
                } else {
                    onCommitText(segment)
                }
            }
            if (idx < lines.lastIndex) {
                sendTap(KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    private fun sendTap(keyCode: Int, metaState: Int = 0) {
        onSendKey(KeyEvent.ACTION_DOWN, keyCode, metaState)
        onSendKey(KeyEvent.ACTION_UP, keyCode, metaState)
    }

    private tailrec fun findHostActivity(ctx: Context?): Activity? = when (ctx) {
        is Activity -> ctx
        is ContextWrapper -> findHostActivity(ctx.baseContext)
        else -> null
    }
}
