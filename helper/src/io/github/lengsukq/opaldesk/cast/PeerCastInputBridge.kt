package io.github.lengsukq.opaldesk.cast

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher

internal fun hasConnectedPhysicalKeyboard(ctx: Context): Boolean {
    val cfg = ctx.resources.configuration
    if (cfg.keyboard != Configuration.KEYBOARD_NOKEYS &&
        cfg.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    ) {
        return true
    }
    return try {
        InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) dev.isExternal else false
            WorkstationKeyDispatcher.isPhysicalKeyboardDevice(
                deviceId = id,
                name = dev.name,
                isVirtual = dev.isVirtual,
                isExternal = ext,
                keyboardType = dev.keyboardType,
            )
        }
    } catch (_: Throwable) {
        false
    }
}

internal fun isSyntheticMouseNavigationKey(event: KeyEvent): Boolean {
    if ((event.source and InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE) return false
    return event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_FORWARD
}

internal fun dispatchPeerCastGenericMotion(
    event: MotionEvent,
    connected: Boolean,
    showAppPicker: Boolean,
    viewW: Int,
    viewH: Int,
    client: OpalCastPeerClient?,
): Boolean {
    if (!connected || showAppPicker || client == null || !client.isMouseMotion(event)) return false
    val masked = event.actionMasked
    if (masked != MotionEvent.ACTION_BUTTON_PRESS && masked != MotionEvent.ACTION_BUTTON_RELEASE) return false
    return client.handleGenericMouseMotion(event, viewW, viewH)
}

internal fun isDirectWorkstationShortcut(event: KeyEvent): Boolean {
    if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) return true
    if (event.isAltPressed || event.isMetaPressed) return true
    if (event.isCtrlPressed && !WorkstationKeyDispatcher.isImeSwitchShortcut(event.keyCode, event.metaState)) {
        return true
    }
    return false
}

internal fun dispatchPeerCastKeyEvent(
    event: KeyEvent,
    connected: Boolean,
    imeVisible: Boolean,
    hardwareImeFocused: Boolean = false,
    client: OpalCastPeerClient?,
    superDispatch: (KeyEvent) -> Boolean,
): Boolean {
    if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
    if (connected && isSyntheticMouseNavigationKey(event)) return true
    if (connected && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
        if (!WorkstationKeyDispatcher.isForwardableKey(event.keyCode)) {
            return superDispatch(event)
        }
        // Local-First DeX input: when the receiver's local IME bridge holds focus (soft keyboard
        // or Bluetooth/USB hardware keyboard inside an active remote text field), let non-shortcut
        // keys flow through the receiver's own IME first; workstation shortcuts (Alt/Ctrl/Esc)
        // bypass the local IME and stream directly to the remote DeX desktop.
        val isDirectShortcut = isDirectWorkstationShortcut(event)
        if ((imeVisible || hardwareImeFocused) && !isDirectShortcut && superDispatch(event)) {
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            WorkstationKeyDispatcher.recordBackKeyForwarded()
        }
        client?.sendKeyEvent(event.action, event.keyCode, event.metaState, event.repeatCount)
        return true
    }
    return superDispatch(event)
}

internal fun applyPeerCastImmersiveFullscreen(window: Window, display: Display?) {
    // Lock peak display refresh rate so OEM power governors (e.g. HyperOS MI-SF) never drop
    // the tablet panel down to 30Hz during static desktop frames.
    try {
        val cur = display?.mode
        val peak = display?.supportedModes
            ?.filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }
            ?.maxByOrNull { it.refreshRate }
        if (peak != null) {
            val lp = window.attributes
            lp.preferredDisplayModeId = peak.modeId
            lp.preferredRefreshRate = peak.refreshRate
            window.attributes = lp
        }
    } catch (_: Throwable) {
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
