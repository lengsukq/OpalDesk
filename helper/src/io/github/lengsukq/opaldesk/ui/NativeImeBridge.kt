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
import io.github.lengsukq.opaldesk.input.InputBindingRule
import io.github.lengsukq.opaldesk.input.LocalInputBindingExecutor
import io.github.lengsukq.opaldesk.input.findMatchingKeyBinding
import io.github.lengsukq.opaldesk.input.normalizeAndroidMetaState
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether an in-process Compose text field (such as App Drawer search or Trackpad IME bar)
 * currently holds focus so physical keyboards & remote keystreams never lose Space/Enter/Arrow keys
 * to accidental Compose button focus traversal, yet still type cleanly into OpalDesk search boxes.
 */
object OpalImeFocusTracker {
    private val _localComposeTextFieldFocusedFlow = MutableStateFlow(false)
    val localComposeTextFieldFocusedFlow: StateFlow<Boolean> = _localComposeTextFieldFocusedFlow.asStateFlow()

    @Volatile
    var localComposeTextFieldFocused: Boolean = false
        private set

    fun setLocalTextFieldFocused(
        focused: Boolean,
        hostDisplayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ) {
        if (localComposeTextFieldFocused == focused) return
        localComposeTextFieldFocused = focused
        _localComposeTextFieldFocusedFlow.value = focused
        BridgeClient.setLocalTextFieldFocusedOneway(focused, hostDisplayId)
    }
}

enum class DesktopBackAction {
    DISMISS_OVERLAY,
    HIDE_SOFT_IME,
    FORWARD_TO_WINDOW,
    CONSUME_DESKTOP_NOOP,
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
    private val consumedShortcutKeyCodes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val _spotlightTogglePulseFlow = MutableStateFlow(0L)
    val spotlightTogglePulseFlow: StateFlow<Long> = _spotlightTogglePulseFlow.asStateFlow()

    @Volatile
    private var activeSpotlightKeyHandler: ((KeyEvent) -> Boolean)? = null

    @Volatile
    private var activeOverlayBackHandler: (() -> Boolean)? = null

    @Volatile
    private var activeOverlayOpenChecker: (() -> Boolean)? = null

    fun setActiveSpotlightKeyHandler(handler: ((KeyEvent) -> Boolean)?) {
        activeSpotlightKeyHandler = handler
    }

    fun clearActiveSpotlightKeyHandler(expected: ((KeyEvent) -> Boolean)?) {
        if (expected == null || activeSpotlightKeyHandler === expected) {
            activeSpotlightKeyHandler = null
        }
    }

    fun setActiveOverlayBackHandlers(
        isOverlayOpen: (() -> Boolean)?,
        dismissOverlay: (() -> Boolean)?,
    ) {
        activeOverlayOpenChecker = isOverlayOpen
        activeOverlayBackHandler = dismissOverlay
    }

    fun clearActiveOverlayBackHandlers(expectedDismiss: (() -> Boolean)?) {
        if (expectedDismiss == null || activeOverlayBackHandler === expectedDismiss) {
            activeOverlayOpenChecker = null
            activeOverlayBackHandler = null
        }
    }

    fun hasActiveLocalOverlay(): Boolean =
        activeSpotlightKeyHandler != null || activeOverlayOpenChecker?.invoke() == true

    fun dismissActiveLocalOverlay(): Boolean {
        if (activeSpotlightKeyHandler?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)) == true) {
            return true
        }
        return activeOverlayBackHandler?.invoke() == true
    }

    fun handleActiveSpotlightKeyEvent(event: KeyEvent): Boolean =
        activeSpotlightKeyHandler?.invoke(event) == true

    /**
     * Pure JVM-testable desktop back-button policy:
     * 1) Dismiss any open local overlay/popover (Spotlight, Control Center, App Drawer, etc.),
     * 2) Hide any active soft keyboard,
     * 3) Forward Back to the focused live app window,
     * 4) In pure desktop state, silently consume so external/local desktop never exits accidentally.
     */
    fun resolveDesktopBackAction(
        hasLocalOverlay: Boolean,
        hasSoftImeVisible: Boolean,
        hasFocusedLiveWindow: Boolean,
    ): DesktopBackAction = when {
        hasLocalOverlay -> DesktopBackAction.DISMISS_OVERLAY
        hasSoftImeVisible -> DesktopBackAction.HIDE_SOFT_IME
        hasFocusedLiveWindow -> DesktopBackAction.FORWARD_TO_WINDOW
        else -> DesktopBackAction.CONSUME_DESKTOP_NOOP
    }

    fun handleDesktopBack(
        hostSoftImeVisible: Boolean = false,
        onHideHostSoftIme: (() -> Unit)? = null,
    ): Boolean {
        val pluginSoftKbd = io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.softKeyboardVisibleFlow.value
        val focusedLive = BridgeClient.sceneFlow.value.focusedWindow?.takeIf { !it.isMinimized && it.isLive }
        when (resolveDesktopBackAction(
            hasLocalOverlay = hasActiveLocalOverlay(),
            hasSoftImeVisible = hostSoftImeVisible || pluginSoftKbd,
            hasFocusedLiveWindow = focusedLive != null,
        )) {
            DesktopBackAction.DISMISS_OVERLAY -> dismissActiveLocalOverlay()
            DesktopBackAction.HIDE_SOFT_IME -> {
                if (pluginSoftKbd) io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.hideSoftKeyboard()
                if (hostSoftImeVisible) onHideHostSoftIme?.invoke()
            }
            DesktopBackAction.FORWARD_TO_WINDOW -> focusedLive?.let {
                BridgeClient.sendWindowActionAsync(it.windowId, BridgeProtocol.WinAction.SEND_BACK)
            }
            DesktopBackAction.CONSUME_DESKTOP_NOOP -> Unit
        }
        return true
    }

    fun triggerSpotlightToggle() {
        _spotlightTogglePulseFlow.value = _spotlightTogglePulseFlow.value + 1L
    }

    fun handleGlobalShortcut(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            return consumedShortcutKeyCodes.remove(event.keyCode)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val scene = BridgeClient.sceneFlow.value
        val focused = scene.focusedWindow
        val customRule = findMatchingKeyBinding(
            rules = BridgeClient.inputBindingsFlow.value,
            keyCode = event.keyCode,
            androidMetaState = event.metaState,
        )
        if (customRule != null) {
            val executed = LocalInputBindingExecutor.execute(
                actionId = customRule.actionId,
                targetWindow = focused,
                triggerType = InputBindingRule.TRIGGER_KEY,
                code = event.keyCode,
                metaMask = normalizeAndroidMetaState(event.metaState),
            )
            if (executed) {
                consumedShortcutKeyCodes.add(event.keyCode)
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE &&
            (OpalImeFocusTracker.localComposeTextFieldFocused || hasActiveLocalOverlay())
        ) return false
        val action = WorkstationShortcutCatalog.resolveGlobalShortcutAction(
            keyCode = event.keyCode,
            metaState = event.metaState,
            hasFocusedWindow = focused != null,
            focusedIsFullscreen = focused?.isFullscreen == true,
            globalEnabled = AppPrefs.globalShortcutsEnabledFlow.value,
            disabledIds = AppPrefs.disabledShortcutIdsFlow.value,
        ) ?: return false

        when (action) {
            GlobalShortcutAction.OpenSpotlight -> {
                triggerSpotlightToggle()
            }
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
            isExternal = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) dev.isExternal else false,
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
     * Pure JVM-testable policy deciding whether an external monitor [android.app.Presentation]
     * window should set `FLAG_NOT_FOCUSABLE` while a physical keyboard is routed to a live
     * `OpalWin-*` VirtualDisplay, so hardware mouse clicks inside the app or on CJK IME
     * candidate bars never steal top display focus onto the outer Presentation and dismiss the IME.
     *
     * Checks `keyboardRoutedDisplayId > 0` (rather than exact equality with
     * `focusedWindowDisplayId`) so switching focus between two live windows never momentarily
     * flaps `FLAG_NOT_FOCUSABLE` mid-click while the daemon rebinds keyboard routing (~10ms),
     * which otherwise triggers a WindowManager transition that cancels active pointer input.
     */
    fun shouldAvoidPresentationFocusSteal(
        focusedWindowDisplayId: Int,
        keyboardRoutedDisplayId: Int,
        localOverlayNeedsFocus: Boolean,
        localTextFieldFocused: Boolean,
        hostSoftImeVisible: Boolean,
    ): Boolean = shouldSetCompositorWindowNotFocusable(
        focusedWindowDisplayId = focusedWindowDisplayId,
        keyboardRoutedDisplayId = keyboardRoutedDisplayId,
        bridgeImeActive = false,
        localOverlayNeedsFocus = localOverlayNeedsFocus,
        localTextFieldFocused = localTextFieldFocused,
        hostSoftImeVisible = hostSoftImeVisible,
    )

    /**
     * Unified focus-isolation invariant across ALL compositor host windows (`DesktopActivity`,
     * `ExternalDesktopActivity`, and `ExternalMonitorPresentation`):
     * keeps the host window `FLAG_NOT_FOCUSABLE` whenever a live `OpalWin-*` VirtualDisplay is
     * focused and either OpalDesk's built-in channel IME (`bridgeImeActive`) or physical keyboard
     * routing (`keyboardRoutedDisplayId > 0`) is active, unless an OpalDesk-own overlay or
     * search `TextField` requires focus.
     */
    fun shouldSetCompositorWindowNotFocusable(
        focusedWindowDisplayId: Int,
        keyboardRoutedDisplayId: Int,
        bridgeImeActive: Boolean,
        localOverlayNeedsFocus: Boolean,
        localTextFieldFocused: Boolean,
        hostSoftImeVisible: Boolean,
        hasEnabledKeyBindings: Boolean = false,
    ): Boolean {
        if (hasEnabledKeyBindings || localOverlayNeedsFocus || localTextFieldFocused || hostSoftImeVisible) return false
        if (focusedWindowDisplayId <= 0) return false
        return bridgeImeActive || keyboardRoutedDisplayId > 0
    }

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

    private val sideBackDeduplicator = io.github.lengsukq.opaldesk.protocol.CompositorGeometry.SideBackKeyDeduplicator()

    fun tryClaimMouseSideBack(uptimeMs: Long = android.os.SystemClock.uptimeMillis()): Boolean =
        sideBackDeduplicator.tryClaimSideButtonBack(uptimeMs)

    fun consumeDuplicateMouseBackKey(
        event: KeyEvent,
        uptimeMs: Long = android.os.SystemClock.uptimeMillis(),
    ): Boolean = sideBackDeduplicator.shouldSuppressDuplicateBackKey(event.keyCode, event.action, uptimeMs)

    fun recordBackKeyForwarded(uptimeMs: Long = android.os.SystemClock.uptimeMillis()) {
        sideBackDeduplicator.recordBackKeyForwarded(uptimeMs)
    }

    /**
     * Pure JVM-testable guard deciding whether a host KeyEvent may be forwarded to a background
     * VirtualDisplay window. While any local Compose text field (App Drawer / Spotlight / Widget
     * search / Notes) holds focus, BOTH ACTION_DOWN and ACTION_UP MUST stay on the host display:
     * Compose BasicTextField consumes ACTION_DOWN only, so letting ACTION_UP fall through would
     * inject into the background app and trigger daemon focusDisplay/focusTopTask theft (<1s).
     */
    fun shouldForwardKeyEventToFocusedWindow(
        action: Int,
        flags: Int,
        keyCode: Int,
        metaState: Int,
        forwardImeSwitch: Boolean,
        localComposeTextFieldFocused: Boolean,
    ): Boolean {
        if (localComposeTextFieldFocused) return false
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        if ((flags and KeyEvent.FLAG_CANCELED) != 0) return false
        if (!isForwardableKey(keyCode)) return false
        if (!forwardImeSwitch && isImeSwitchShortcut(keyCode, metaState)) return false
        return true
    }

    fun forwardToFocusedWindow(event: KeyEvent, forwardImeSwitch: Boolean = false): Boolean {
        if (!shouldForwardKeyEventToFocusedWindow(
                action = event.action,
                flags = event.flags,
                keyCode = event.keyCode,
                metaState = event.metaState,
                forwardImeSwitch = forwardImeSwitch,
                localComposeTextFieldFocused = OpalImeFocusTracker.localComposeTextFieldFocused,
            )
        ) return false
        val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return false
        if (focused.isMinimized) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            sideBackDeduplicator.recordBackKeyForwarded(android.os.SystemClock.uptimeMillis())
        }
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
            // Fast path: when OpalDesk's built-in channel IME holds a live InputConnection to a
            // managed app window (never looping back into OpalDesk itself), commit directly with
            // zero clipboard mutation and zero Ctrl+V delay.
            val committedDirectly = io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.commitTextSync(
                text = text,
                rejectSelfPackage = true,
                selfPackageName = appCtx.packageName,
            )
            if (committedDirectly) return@execute
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
    hostWindow: android.view.Window? = null,
    modifier: Modifier = Modifier,
) {
    val curSendKey by rememberUpdatedState(onSendKey)
    val curCommitText by rememberUpdatedState(onCommitText)
    val curComposing by rememberUpdatedState(onComposingChanged)
    val curVisibility by rememberUpdatedState(onImeVisibilityChanged)
    val curHostWindow by rememberUpdatedState(hostWindow)

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
                hostWindowProvider = { curHostWindow },
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
    private val hostWindowProvider: () -> android.view.Window?,
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
    private var claimedLocalImeFocus = false
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
                        if (claimedLocalImeFocus) {
                            claimedLocalImeFocus = false
                            BridgeClient.setLocalTextFieldFocusedOneway(false, resolveHostDisplayId())
                        }
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
        // Focus THIS view's hosting display in system_server (via synchronous Binder ack) BEFORE
        // calling showSoftInput() so Android 14-17 IMMS never rejects or misroutes the IME onto a
        // previously focused OpalWin-* VirtualDisplay or Display 0.
        val hostDispId = resolveHostDisplayId()
        claimedLocalImeFocus = true
        pendingShowAfterFocus = true
        requestFocus()
        BridgeClient.setLocalTextFieldFocusedAndAwaitAck(true, hostDispId) {
            if (!isAttachedToWindow || showAttemptGen != gen) return@setLocalTextFieldFocusedAndAwaitAck
            if (!lastImeVisible) {
                triggerShowNow()
            }
        }
        postDelayed({
            if (!isAttachedToWindow || showAttemptGen != gen) return@postDelayed
            pendingShowAfterFocus = false
            if (!lastImeVisible) {
                triggerShowNow()
            }
        }, 140L)
        postDelayed({
            if (!isAttachedToWindow || showAttemptGen != gen) return@postDelayed
            pendingShowAfterFocus = false
            if (!lastImeVisible) {
                // Soft IME stayed hidden (e.g. a physical keyboard suppresses soft input): release
                // focus and daemon localImeActive lock so window keyboard input never gets stranded.
                clearFocus()
                if (claimedLocalImeFocus) {
                    claimedLocalImeFocus = false
                    if (!OpalImeFocusTracker.localComposeTextFieldFocused) {
                        BridgeClient.setLocalTextFieldFocusedOneway(false, resolveHostDisplayId())
                    }
                }
            }
        }, 650L)
    }

    @Suppress("DEPRECATION")
    private fun triggerShowNow() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
        resolveOwnDisplayWindow()?.let { win ->
            WindowCompat.getInsetsController(win, this).show(WindowInsetsCompat.Type.ime())
        }
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideIme() {
        showAttemptGen++
        pendingShowAfterFocus = false
        onComposingChanged("")
        claimedLocalImeFocus = false
        BridgeClient.setLocalTextFieldFocusedOneway(false, resolveHostDisplayId())
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        resolveOwnDisplayWindow()?.let { win ->
            WindowCompat.getInsetsController(win, this).hide(WindowInsetsCompat.Type.ime())
        }
        clearFocus()
    }

    private fun resolveHostDisplayId(): Int {
        return display?.displayId
            ?: hostWindowProvider()?.decorView?.display?.displayId
            ?: android.view.Display.DEFAULT_DISPLAY
    }

    /**
     * Resolves the Window that actually owns this view's Display. Never falls back to a Display 0
     * Activity Window when this view is hosted inside a secondary-display Presentation.
     */
    private fun resolveOwnDisplayWindow(): android.view.Window? {
        hostWindowProvider()?.let { return it }
        val actWin = findHostActivity(context)?.window ?: return null
        val myDispId = display?.displayId ?: return actWin
        val actDispId = actWin.decorView.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        return if (myDispId == actDispId) actWin else null
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
