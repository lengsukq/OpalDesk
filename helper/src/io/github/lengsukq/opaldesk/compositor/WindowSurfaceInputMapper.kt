package io.github.lengsukq.opaldesk.compositor

import android.graphics.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.input.InputBindingRule
import io.github.lengsukq.opaldesk.input.LocalInputBindingExecutor
import io.github.lengsukq.opaldesk.input.extractMouseButtonCode
import io.github.lengsukq.opaldesk.input.findMatchingMouseBinding
import io.github.lengsukq.opaldesk.input.normalizeAndroidMetaState
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import java.util.concurrent.atomic.AtomicLong

/**
 * Intercepts Compose [PointerEventType.Scroll] and unpressed mouse hover directly:
 * `AndroidComposeView` intercepts `GenericMotionEvent` (`ACTION_SCROLL` & `ACTION_HOVER_MOVE`)
 * into Compose `PointerInput` rather than `AndroidView.onGenericMotionListener` whenever
 * ancestor `pointerInput` modifiers exist. Forwarding hover here keeps the VirtualDisplay
 * cursor continuously synchronized before every USB mouse click.
 */
internal fun Modifier.windowSurfaceHoverAndScrollInput(
    windowId: Int,
    getCurrentWindow: () -> CompositorWindow,
    getCurrentHeaderPx: () -> Int,
    getCurrentCropTopPx: () -> Int,
    lastHoverEventTime: AtomicLong,
): Modifier = pointerInput(windowId, "mouse_hover_and_scroll") {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val change = ev.changes.firstOrNull() ?: continue
            if (ev.type == PointerEventType.Scroll) {
                val delta = change.scrollDelta
                // Android app windows inside OpalWin-* run at higher content DPI and apply
                // native wheel-step scaling (RecyclerView / WebView), making raw trackpad
                // ticks 2x-3x faster than the desktop App Drawer; damp slightly for parity.
                val appScrollDamp = 0.55f
                val vScroll = -delta.y * appScrollDamp
                val hScroll = delta.x * appScrollDamp
                if (vScroll != 0f || hScroll != 0f) {
                    val win = getCurrentWindow()
                    val canvasX = win.frame.left + change.position.x
                    val canvasY = win.frame.top + getCurrentHeaderPx() + change.position.y
                    val occluded = CompositorGeometry.isPointOccludedByHigherWindow(
                        target = win,
                        canvasX = canvasX,
                        canvasY = canvasY,
                        visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                    )
                    if (!occluded && win.isLive && win.displayId > 0) {
                        change.consume()
                        val mappedX = CompositorGeometry.mapViewToDisplayCoord(
                            change.position.x,
                            size.width,
                            win.contentWidth.coerceAtLeast(2),
                        )
                        val mappedY = mapViewYToDisplayY(
                            change.position.y,
                            size.height,
                            win.contentHeight.coerceAtLeast(2),
                            getCurrentCropTopPx(),
                        )
                        val now = SystemClock.uptimeMillis()
                        BridgeClient.injectWindowMotionOneway(
                            windowId = win.windowId,
                            action = MotionEvent.ACTION_SCROLL,
                            downTime = now,
                            eventTime = now,
                            source = InputDevice.SOURCE_MOUSE,
                            buttonState = 0,
                            actionButton = 0,
                            hScroll = hScroll,
                            vScroll = vScroll,
                            pointerIds = intArrayOf(0),
                            toolTypes = intArrayOf(MotionEvent.TOOL_TYPE_MOUSE),
                            xs = floatArrayOf(mappedX),
                            ys = floatArrayOf(mappedY),
                            pressures = floatArrayOf(0f),
                        )
                    }
                }
            } else if (
                change.type == PointerType.Mouse &&
                !change.pressed &&
                !change.previousPressed &&
                (ev.type == PointerEventType.Move ||
                    ev.type == PointerEventType.Enter ||
                    ev.type == PointerEventType.Exit)
            ) {
                val win = getCurrentWindow()
                if (win.isLive && win.displayId > 0 && size.width > 0 && size.height > 0) {
                    val canvasX = win.frame.left + change.position.x
                    val canvasY = win.frame.top + getCurrentHeaderPx() + change.position.y
                    val occluded = ev.type != PointerEventType.Exit && CompositorGeometry.isPointOccludedByHigherWindow(
                        target = win,
                        canvasX = canvasX,
                        canvasY = canvasY,
                        visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                    )
                    val evtTime = change.uptimeMillis.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
                    val isHoverMove = ev.type == PointerEventType.Move
                    val prevHoverTime = lastHoverEventTime.get()
                    val hoverAllowed = !occluded && prevHoverTime != evtTime &&
                        (!isHoverMove || (evtTime - prevHoverTime) >= 16L)
                    if (hoverAllowed && lastHoverEventTime.compareAndSet(prevHoverTime, evtTime)) {
                        val hoverAction = when (ev.type) {
                            PointerEventType.Enter -> MotionEvent.ACTION_HOVER_ENTER
                            PointerEventType.Exit -> MotionEvent.ACTION_HOVER_EXIT
                            else -> MotionEvent.ACTION_HOVER_MOVE
                        }
                        val mappedX = CompositorGeometry.mapViewToDisplayCoord(
                            change.position.x,
                            size.width,
                            win.contentWidth.coerceAtLeast(2),
                        )
                        val mappedY = mapViewYToDisplayY(
                            change.position.y,
                            size.height,
                            win.contentHeight.coerceAtLeast(2),
                            getCurrentCropTopPx(),
                        )
                        BridgeClient.injectWindowMotionOneway(
                            windowId = win.windowId,
                            action = hoverAction,
                            downTime = evtTime,
                            eventTime = evtTime,
                            source = InputDevice.SOURCE_MOUSE,
                            buttonState = 0,
                            actionButton = 0,
                            hScroll = 0f,
                            vScroll = 0f,
                            pointerIds = intArrayOf(0),
                            toolTypes = intArrayOf(MotionEvent.TOOL_TYPE_MOUSE),
                            xs = floatArrayOf(mappedX),
                            ys = floatArrayOf(mappedY),
                            pressures = floatArrayOf(0f),
                        )
                    }
                }
            }
        }
    }
}

internal fun isMouseMotionEvent(ev: MotionEvent): Boolean =
    (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
        (ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

internal fun applyTopCropTransform(view: FixedBufferTextureView, cropTopDisplayPx: Int, contentH: Int) {
    val viewH = view.height
    if (cropTopDisplayPx <= 0 || contentH <= 2 || viewH <= 0 || cropTopDisplayPx >= contentH - 2) {
        if (view.hasCustomTopCropTransform) {
            view.hasCustomTopCropTransform = false
            view.lastAppliedCropPx = 0
            view.lastAppliedCropContentH = 0
            view.lastAppliedCropViewH = 0
            view.setTransform(null)
        }
        return
    }
    if (view.hasCustomTopCropTransform &&
        view.lastAppliedCropPx == cropTopDisplayPx &&
        view.lastAppliedCropContentH == contentH &&
        view.lastAppliedCropViewH == viewH
    ) {
        return
    }
    val cropRatio = cropTopDisplayPx.toFloat() / contentH.toFloat()
    val scaleY = 1f / (1f - cropRatio)
    val matrix = Matrix().apply {
        setScale(1f, scaleY)
        postTranslate(0f, -cropRatio * viewH * scaleY)
    }
    view.hasCustomTopCropTransform = true
    view.lastAppliedCropPx = cropTopDisplayPx
    view.lastAppliedCropContentH = contentH
    view.lastAppliedCropViewH = viewH
    view.setTransform(matrix)
}

internal fun mapViewYToDisplayY(viewY: Float, viewH: Int, contentH: Int, cropTopDisplayPx: Int): Float {
    if (cropTopDisplayPx <= 0 || cropTopDisplayPx >= contentH - 2) {
        return CompositorGeometry.mapViewToDisplayCoord(viewY, viewH, contentH)
    }
    val visibleH = (contentH - cropTopDisplayPx).coerceAtLeast(2)
    val norm = (viewY / viewH.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    return (cropTopDisplayPx + norm * (visibleH - 1)).coerceIn(0f, (contentH - 1).toFloat())
}

internal fun handleGenericMotionForWindow(
    viewW: Int,
    viewH: Int,
    ev: MotionEvent?,
    win: CompositorWindow,
    headerHeightPx: Int,
    cropTopDisplayPx: Int,
    lastHoverEventTime: AtomicLong,
    onFocusRequested: () -> Unit,
): Boolean {
    if (ev == null || viewW <= 0 || viewH <= 0) return false
    val masked = ev.actionMasked
    if (masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) {
        val canvasX = win.frame.left + ev.x
        val canvasY = win.frame.top + headerHeightPx + ev.y
        if (CompositorGeometry.isPointOccludedByHigherWindow(
                target = win,
                canvasX = canvasX,
                canvasY = canvasY,
                visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
            )
        ) {
            return false
        }
        if (masked == MotionEvent.ACTION_BUTTON_PRESS) {
            if (!win.focused) onFocusRequested()
            val btnCode = extractMouseButtonCode(ev.actionButton, ev.buttonState)
            if (btnCode > 0 && btnCode != MotionEvent.BUTTON_PRIMARY) {
                val matched = findMatchingMouseBinding(
                    rules = BridgeClient.inputBindingsFlow.value,
                    buttonCode = btnCode,
                    androidMetaState = ev.metaState,
                )
                if (matched != null) {
                    LocalInputBindingExecutor.execute(
                        actionId = matched.actionId,
                        targetWindow = win,
                        triggerType = InputBindingRule.TRIGGER_MOUSE,
                        code = btnCode,
                        metaMask = normalizeAndroidMetaState(ev.metaState),
                    )
                    return true
                }
            }
        }
        // Consume mouse button generic events locally without injecting invalid ACTION_BUTTON_PRESS/RELEASE
        // into IInputManager (actual click press/release is delivered cleanly via onTouchListener ACTION_DOWN/UP).
        return isMouseMotionEvent(ev)
    }
    if (masked == MotionEvent.ACTION_HOVER_ENTER ||
        masked == MotionEvent.ACTION_HOVER_MOVE ||
        masked == MotionEvent.ACTION_HOVER_EXIT
    ) {
        val prevHoverTime = lastHoverEventTime.get()
        val isMove = masked == MotionEvent.ACTION_HOVER_MOVE
        if (prevHoverTime == ev.eventTime || (isMove && (ev.eventTime - prevHoverTime) in 0L..15L)) {
            return true
        }
        lastHoverEventTime.set(ev.eventTime)
        return forwardMotionEventToWindow(viewW, viewH, ev, win, headerHeightPx, cropTopDisplayPx, onFocusRequested)
    }
    if (masked == MotionEvent.ACTION_SCROLL) {
        return forwardMotionEventToWindow(viewW, viewH, ev, win, headerHeightPx, cropTopDisplayPx, onFocusRequested)
    }
    return false
}

internal fun forwardMotionEventToWindow(
    viewW: Int,
    viewH: Int,
    ev: MotionEvent?,
    win: CompositorWindow,
    headerHeightPx: Int,
    cropTopDisplayPx: Int,
    onFocusRequested: () -> Unit,
): Boolean {
    if (ev == null || viewW <= 0 || viewH <= 0) return false
    val masked = ev.actionMasked
    val canvasX = win.frame.left + ev.x
    val canvasY = win.frame.top + (if (win.isFullscreen) 0 else headerHeightPx) + ev.y
    // Geometric Z-order occlusion guard: never accept clicks/hovers underneath a higher-Z floating window
    if (masked == MotionEvent.ACTION_DOWN ||
        masked == MotionEvent.ACTION_HOVER_ENTER ||
        masked == MotionEvent.ACTION_HOVER_MOVE ||
        masked == MotionEvent.ACTION_SCROLL
    ) {
        if (CompositorGeometry.isPointOccludedByHigherWindow(
                target = win,
                canvasX = canvasX,
                canvasY = canvasY,
                visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
            )
        ) {
            return false
        }
    }
    if (isMouseMotionEvent(ev)) {
        val nonPrimaryButtons = ev.buttonState and MotionEvent.BUTTON_PRIMARY.inv()
        if (nonPrimaryButtons != 0 &&
            (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN)
        ) {
            val matched = findMatchingMouseBinding(
                rules = BridgeClient.inputBindingsFlow.value,
                buttonCode = nonPrimaryButtons,
                androidMetaState = ev.metaState,
            )
            if (matched != null) {
                // Suppress raw app injection; ACTION_BUTTON_PRESS dispatches the bound action cleanly.
                return true
            }
        }
    }
    if (masked == MotionEvent.ACTION_DOWN) {
        val isMouseDown = isMouseMotionEvent(ev)
        io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.recordWindowPointerDown(
            windowId = win.windowId,
            displayId = win.displayId,
            canvasX = canvasX,
            canvasY = canvasY,
            modality = if (isMouseDown) {
                io.github.lengsukq.opaldesk.ime.InputModality.MOUSE
            } else {
                io.github.lengsukq.opaldesk.ime.InputModality.TOUCH
            },
            uptimeMs = ev.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis(),
        )
    }
    if (!win.isLive || win.displayId <= 0) {
        if (!win.focused && masked == MotionEvent.ACTION_DOWN) {
            onFocusRequested()
        }
        return true
    }

    val count = ev.pointerCount.coerceIn(1, 10)
    val ids = IntArray(count)
    val toolTypes = IntArray(count)
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    val pressures = FloatArray(count)

    val cw = win.contentWidth.coerceAtLeast(2)
    val ch = win.contentHeight.coerceAtLeast(2)
    for (i in 0 until count) {
        ids[i] = ev.getPointerId(i)
        toolTypes[i] = ev.getToolType(i)
        xs[i] = CompositorGeometry.mapViewToDisplayCoord(ev.getX(i), viewW, cw)
        ys[i] = mapViewYToDisplayY(ev.getY(i), viewH, ch, cropTopDisplayPx)
        pressures[i] = ev.getPressure(i)
    }

    val isMouse = isMouseMotionEvent(ev)
    val effSource = when {
        isMouse -> InputDevice.SOURCE_MOUSE
        toolTypes[0] == MotionEvent.TOOL_TYPE_STYLUS -> InputDevice.SOURCE_STYLUS
        else -> InputDevice.SOURCE_TOUCHSCREEN
    }
    val normButtonState = if (
        isMouse &&
        (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_MOVE) &&
        ev.buttonState == 0
    ) {
        MotionEvent.BUTTON_PRIMARY
    } else {
        ev.buttonState
    }
    val hScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_HSCROLL) else 0f
    val vScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f

    BridgeClient.injectWindowMotionOneway(
        windowId = win.windowId,
        action = ev.action,
        downTime = ev.downTime,
        eventTime = ev.eventTime,
        source = effSource,
        buttonState = normButtonState,
        actionButton = 0,
        hScroll = hScroll,
        vScroll = vScroll,
        pointerIds = ids,
        toolTypes = toolTypes,
        xs = xs,
        ys = ys,
        pressures = pressures,
    )
    return true
}
