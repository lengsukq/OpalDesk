package io.github.lengsukq.opaldesk.cast

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
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

internal fun dispatchPeerCastKeyEvent(
    event: KeyEvent,
    connected: Boolean,
    imeVisible: Boolean,
    client: OpalCastPeerClient?,
    superDispatch: (KeyEvent) -> Boolean,
): Boolean {
    if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
    if (connected && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
        if (!WorkstationKeyDispatcher.isForwardableKey(event.keyCode)) {
            return superDispatch(event)
        }
        // Physical/Bluetooth keyboard events bypass the receiver tablet's local IME completely
        // and stream directly to the casting phone as if the keyboard were attached to the phone.
        val isPhysicalKey = WorkstationKeyDispatcher.isPhysicalKeyboardEvent(event)
        val isDirectShortcut = event.isAltPressed || event.isCtrlPressed || event.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (!isPhysicalKey && imeVisible && !isDirectShortcut && superDispatch(event)) {
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
