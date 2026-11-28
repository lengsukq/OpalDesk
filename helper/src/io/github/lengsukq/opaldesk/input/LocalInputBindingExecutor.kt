package io.github.lengsukq.opaldesk.input

import android.view.InputDevice
import android.view.MotionEvent
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.compositor.applyWindowTilingLayout
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Executes any of the 10 unified [InputBindingAction]s locally in `:helper` (local display or
 * physical external monitor compositor), mirroring `:bridge`'s `OpalCastCompositorBridge` behavior.
 */
object LocalInputBindingExecutor {
    private val _recentEventSummary = MutableStateFlow<String?>(null)
    val recentEventSummary: StateFlow<String?> = _recentEventSummary.asStateFlow()

    fun recordRecentCapturedEvent(ev: CapturedInputEvent, source: String = "") {
        if (!ev.isValid) return
        val sourceLabel = when (source) {
            "daemon_cast" -> "远程投屏"
            "local_mouse" -> "本机鼠标"
            "local_key" -> "本机键盘"
            else -> ""
        }
        val prefix = if (sourceLabel.isNotEmpty()) "已记录 ($sourceLabel)" else "已记录"
        _recentEventSummary.value = "$prefix：${ev.displayLabel} (${ev.hexCode})"
    }

    fun recordRecentMatchedEvent(
        triggerType: String,
        code: Int,
        metaMask: Int,
        action: InputBindingAction,
    ) {
        val badge = formatTriggerBadge(triggerType, code, metaMask)
        val hex = "0x${code.toString(16).uppercase(Locale.US)}"
        _recentEventSummary.value = "已命中：$badge ($hex) → ${action.labelZh}"
    }

    fun execute(
        actionId: String,
        targetWindow: CompositorWindow?,
        triggerType: String,
        code: Int,
        metaMask: Int,
    ): Boolean {
        val action = InputBindingAction.fromId(actionId) ?: return false
        val scene = BridgeClient.sceneFlow.value
        val win = targetWindow ?: scene.focusedWindow
        val st = BridgeClient.statusFlow.value
        val canvasW = st.canvasWidth.coerceAtLeast(scene.canvasWidth).coerceAtLeast(1)
        val canvasH = st.canvasHeight.coerceAtLeast(scene.canvasHeight).coerceAtLeast(1)
        val density = (st.canvasDpi.takeIf { it > 0 } ?: scene.canvasDpi.takeIf { it > 0 } ?: 160)
            .let { (it / 160f).coerceIn(0.8f, 3.5f) }

        OpalLogStore.i(
            "InputBinding",
            "INTERCEPTED local trigger=$triggerType code=0x${code.toString(16).uppercase(Locale.US)} " +
                "meta=0x${metaMask.toString(16)} -> action=${action.actionId}",
        )
        recordRecentMatchedEvent(triggerType, code, metaMask, action)

        when (action) {
            InputBindingAction.BACK -> {
                val isMouseBack = triggerType == InputBindingRule.TRIGGER_MOUSE &&
                    code == InputBindingRule.MOUSE_BUTTON_BACK
                val claimed = !isMouseBack || WorkstationKeyDispatcher.tryClaimMouseSideBack()
                if (!claimed) return true
                if (targetWindow != null && targetWindow.isLive && targetWindow.displayId > 0) {
                    BridgeClient.sendWindowActionAsync(targetWindow.windowId, BridgeProtocol.WinAction.SEND_BACK)
                } else {
                    WorkstationKeyDispatcher.handleDesktopBack()
                }
            }
            InputBindingAction.TOGGLE_MAXIMIZE -> {
                if (win == null) return false
                BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.TOGGLE_MAXIMIZE)
            }
            InputBindingAction.TOGGLE_FULLSCREEN -> {
                if (win == null) return false
                BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.TOGGLE_FULLSCREEN)
            }
            InputBindingAction.SNAP_LEFT -> {
                if (win == null) return false
                val r = CompositorGeometry.computeSnapPreviewRect(
                    BridgeProtocol.Presentation.SNAP_LEFT,
                    canvasW,
                    canvasH,
                    density,
                    0f,
                )
                BridgeClient.updateWindowFrameAsync(
                    win.windowId,
                    r.left,
                    r.top,
                    r.right,
                    r.bottom,
                    BridgeProtocol.Presentation.SNAP_LEFT,
                )
            }
            InputBindingAction.SNAP_RIGHT -> {
                if (win == null) return false
                val r = CompositorGeometry.computeSnapPreviewRect(
                    BridgeProtocol.Presentation.SNAP_RIGHT,
                    canvasW,
                    canvasH,
                    density,
                    0f,
                )
                BridgeClient.updateWindowFrameAsync(
                    win.windowId,
                    r.left,
                    r.top,
                    r.right,
                    r.bottom,
                    BridgeProtocol.Presentation.SNAP_RIGHT,
                )
            }
            InputBindingAction.TILE_2_SPLIT -> {
                applyWindowTilingLayout(
                    layout = WindowTilingLayout.SPLIT_2_COLS,
                    scene = scene,
                    canvasW = canvasW,
                    canvasH = canvasH,
                    density = density,
                )
            }
            InputBindingAction.CLOSE_WINDOW -> {
                if (win == null) return false
                BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.CLOSE)
            }
            InputBindingAction.MINIMIZE_WINDOW -> {
                if (win == null) return false
                BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
            }
            InputBindingAction.SWITCH_WINDOW -> {
                val candidates = scene.windows.filter { !it.isMinimized }
                if (candidates.size >= 2) {
                    val idx = candidates.indexOfFirst { it.focused }
                    val next = candidates[(idx + 1) % candidates.size]
                    BridgeClient.sendWindowActionAsync(next.windowId, BridgeProtocol.WinAction.FOCUS)
                }
            }
            InputBindingAction.SPOTLIGHT -> {
                WorkstationKeyDispatcher.triggerSpotlightToggle()
            }
        }
        return true
    }

    /**
     * Pure JVM-testable check for auxiliary mouse buttons that should be handled at the desktop
     * fallback layer (excluding primary click and secondary right-click which drives desktop menus).
     */
    fun isDesktopFallbackMouseButton(buttonCode: Int): Boolean =
        buttonCode > 0 &&
            buttonCode != InputBindingRule.MOUSE_BUTTON_PRIMARY &&
            buttonCode != InputBindingRule.MOUSE_BUTTON_SECONDARY

    private fun isMouseEvent(ev: MotionEvent): Boolean =
        (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

    /**
     * Intercepts unconsumed mouse side/middle/extended button presses on desktop empty areas so:
     * 1) Custom mouse bindings (e.g. Forward/Middle/Extended -> Spotlight/Switch/Back) work even
     *    outside app windows, and
     * 2) Mouse Back (`BUTTON_BACK`) in pure desktop state is silently consumed and claims
     *    `SideBackKeyDeduplicator` so Android's synthesized `KEYCODE_BACK` never exits the desktop.
     */
    fun dispatchDesktopFallbackMouseGenericMotion(ev: MotionEvent?): Boolean {
        if (ev == null || !isMouseEvent(ev)) return false
        val masked = ev.actionMasked
        if (masked != MotionEvent.ACTION_BUTTON_PRESS && masked != MotionEvent.ACTION_BUTTON_RELEASE) {
            return false
        }
        val btnCode = extractMouseButtonCode(ev.actionButton, ev.buttonState)
        if (!isDesktopFallbackMouseButton(btnCode)) return false
        if (masked == MotionEvent.ACTION_BUTTON_PRESS) {
            val matched = findMatchingMouseBinding(
                rules = BridgeClient.inputBindingsFlow.value,
                buttonCode = btnCode,
                androidMetaState = ev.metaState,
            )
            if (matched != null) {
                execute(
                    actionId = matched.actionId,
                    targetWindow = null,
                    triggerType = InputBindingRule.TRIGGER_MOUSE,
                    code = btnCode,
                    metaMask = normalizeAndroidMetaState(ev.metaState),
                )
                return true
            }
            if (btnCode == InputBindingRule.MOUSE_BUTTON_BACK) {
                if (WorkstationKeyDispatcher.tryClaimMouseSideBack()) {
                    WorkstationKeyDispatcher.handleDesktopBack()
                }
                return true
            }
            return false
        }
        // Consume matching release for bound auxiliary buttons or side-back so Android never synthesizes KEYCODE_BACK
        val matched = findMatchingMouseBinding(
            rules = BridgeClient.inputBindingsFlow.value,
            buttonCode = btnCode,
            androidMetaState = ev.metaState,
        )
        return matched != null || btnCode == InputBindingRule.MOUSE_BUTTON_BACK
    }

    fun shouldConsumeDesktopFallbackMouseTouch(ev: MotionEvent?): Boolean {
        if (ev == null || !isMouseEvent(ev)) return false
        val auxButtons = ev.buttonState and
            (InputBindingRule.MOUSE_BUTTON_PRIMARY or InputBindingRule.MOUSE_BUTTON_SECONDARY).inv()
        if (auxButtons == 0) return false
        val matched = findMatchingMouseBinding(BridgeClient.inputBindingsFlow.value, auxButtons, ev.metaState)
        return matched != null || (auxButtons and InputBindingRule.MOUSE_BUTTON_BACK) != 0
    }
}
