package io.github.lengsukq.opaldesk.compositor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.ResizeEdge
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
internal fun HeaderSubtleIconButton(
    glyph: ChromeGlyph,
    focused: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(
            glyph = glyph,
            tint = if (focused) OpalPalette.TextSecondary else OpalPalette.TextMuted,
            size = 13.dp,
        )
    }
}

@Composable
internal fun HeaderPinIconButton(
    pinned: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val tint = when {
        pinned -> OpalPalette.AccentBlue
        focused -> OpalPalette.TextSecondary
        else -> OpalPalette.TextMuted
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (pinned) OpalPalette.AccentBlue.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                width = if (pinned) 0.75.dp else 0.dp,
                color = if (pinned) OpalPalette.AccentBlue.copy(alpha = 0.50f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.45.dp.toPx()
            // Minimalist 45-degree pushpin: head bar, body stem, collar bar, needle
            drawLine(tint, Offset(w * 0.56f, h * 0.12f), Offset(w * 0.88f, h * 0.44f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.68f, h * 0.32f), Offset(w * 0.44f, h * 0.56f), strokeWidth = stroke * 1.15f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.30f, h * 0.42f), Offset(w * 0.58f, h * 0.70f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.44f, h * 0.56f), Offset(w * 0.14f, h * 0.86f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun BoxScope.ResizeHandleZone(
    edge: ResizeEdge,
    alignment: Alignment,
    sizeModifier: Modifier,
    window: CompositorWindow,
    headerHeightPx: Int,
    getFrameCoords: () -> LayoutCoordinates?,
    canvasW: Int,
    canvasH: Int,
    densityVal: Float,
    onDragStarted: () -> Unit,
    onLiveFrameChanged: (WindowRect) -> Unit,
    onCommitFrame: (WindowRect) -> Unit,
    onDragCancelled: () -> Unit,
    showGrip: Boolean = false,
) {
    val latestWin by rememberUpdatedState(window)
    val latestHeaderPx by rememberUpdatedState(headerHeightPx)
    val latestGetFrameCoords by rememberUpdatedState(getFrameCoords)
    val latestOnStart by rememberUpdatedState(onDragStarted)
    val latestOnLive by rememberUpdatedState(onLiveFrameChanged)
    val latestOnCommit by rememberUpdatedState(onCommitFrame)
    val latestOnCancel by rememberUpdatedState(onDragCancelled)
    var handleCoords by remember(window.windowId, edge) { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = Modifier
            .align(alignment)
            .zIndex(20f)
            .then(sizeModifier)
            .onGloballyPositioned { handleCoords = it }
            .pointerInput(window.windowId, edge, canvasW, canvasH) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Consume immediately so TextureView underneath never steals corner/edge resize drags;
                    // if the gesture ends without crossing drag slop, forward it as a genuine tap/click
                    // into the underlying VirtualDisplay so bottom-left / bottom-right buttons remain clickable.
                    down.consume()
                    val startRoot = handleCoords?.localToRoot(down.position) ?: down.position
                    val dragSlopPx = if (down.type == PointerType.Mouse) {
                        maxOf(4.0, (3f * densityVal).toDouble())
                    } else {
                        viewConfiguration.touchSlop.toDouble().coerceAtLeast(8.0)
                    }
                    val base = latestWin.frame
                    var cur = base
                    var moved = false
                    var committed = false

                    if (!latestWin.focused) {
                        BridgeClient.sendWindowActionAsync(latestWin.windowId, BridgeProtocol.WinAction.FOCUS)
                    }

                    try {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Main)
                            val change = ev.changes.firstOrNull { it.id == down.id }
                                ?: ev.changes.firstOrNull()
                                ?: break
                            if (change.pressed) {
                                change.consume()
                                val curRoot = handleCoords?.localToRoot(change.position) ?: change.position
                                val dx = (curRoot.x - startRoot.x).roundToInt()
                                val dy = (curRoot.y - startRoot.y).roundToInt()
                                if (!moved && hypot(dx.toDouble(), dy.toDouble()) >= dragSlopPx) {
                                    moved = true
                                    latestOnStart()
                                }
                                if (moved) {
                                    cur = CompositorGeometry.computeResize(
                                        base,
                                        edge,
                                        dx,
                                        dy,
                                        canvasW,
                                        canvasH,
                                        densityVal,
                                    )
                                    // Smooth 120fps client GPU frame scaling during drag;
                                    // avoid mid-drag VirtualDisplay.resize storms that desync SurfaceFlinger/WM.
                                    latestOnLive(cur)
                                }
                            } else {
                                change.consume()
                                if (moved && cur != base) {
                                    committed = true
                                    latestOnCommit(cur)
                                    BridgeClient.updateWindowFrameAsync(
                                        windowId = latestWin.windowId,
                                        left = cur.left,
                                        top = cur.top,
                                        right = cur.right,
                                        bottom = cur.bottom,
                                        presentation = BridgeProtocol.Presentation.FLOATING,
                                    )
                                } else if (!moved) {
                                    // Plain tap/click inside a corner or edge resize zone: pass through to window content.
                                    forwardResizeZoneTapToWindow(
                                        win = latestWin,
                                        headerHeightPx = latestHeaderPx,
                                        frameCoords = latestGetFrameCoords(),
                                        handleCoords = handleCoords,
                                        localPos = change.position,
                                        pointerType = down.type,
                                        downTimeMs = down.uptimeMillis,
                                        upTimeMs = change.uptimeMillis,
                                    )
                                }
                                break
                            }
                        }
                    } finally {
                        if (moved && !committed) {
                            latestOnCancel()
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (showGrip) {
            val gripColor = if (window.focused) {
                OpalPalette.TextSecondary.copy(alpha = 0.72f)
            } else {
                OpalPalette.TextMuted.copy(alpha = 0.42f)
            }
            Canvas(
                modifier = Modifier
                    .padding(6.dp)
                    .size(11.dp),
            ) {
                val stroke = 1.4.dp.toPx()
                drawLine(gripColor, Offset(size.width * 0.22f, size.height), Offset(size.width, size.height * 0.22f), stroke, StrokeCap.Round)
                drawLine(gripColor, Offset(size.width * 0.62f, size.height), Offset(size.width, size.height * 0.62f), stroke, StrokeCap.Round)
            }
        }
    }
}

private fun forwardResizeZoneTapToWindow(
    win: CompositorWindow,
    headerHeightPx: Int,
    frameCoords: LayoutCoordinates?,
    handleCoords: LayoutCoordinates?,
    localPos: Offset,
    pointerType: PointerType,
    downTimeMs: Long,
    upTimeMs: Long,
) {
    if (win.isHibernated) {
        BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.WAKE)
        return
    }
    if (!win.isLive || win.displayId <= 0) return
    if (frameCoords == null || !frameCoords.isAttached || handleCoords == null || !handleCoords.isAttached) return

    val inFrame = frameCoords.localPositionOf(handleCoords, localPos)
    val surfX = inFrame.x
    val surfY = inFrame.y - headerHeightPx
    val surfW = frameCoords.size.width.coerceAtLeast(1)
    val surfH = (frameCoords.size.height - headerHeightPx).coerceAtLeast(1)
    if (surfX < 0f || surfX > surfW.toFloat() || surfY < 0f || surfY > surfH.toFloat()) return

    val canvasX = win.frame.left + surfX
    val canvasY = win.frame.top + headerHeightPx + surfY
    if (CompositorGeometry.isPointOccludedByHigherWindow(
            target = win,
            canvasX = canvasX,
            canvasY = canvasY,
            visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
        )
    ) {
        return
    }

    val mappedX = CompositorGeometry.mapViewToDisplayCoord(surfX, surfW, win.contentWidth.coerceAtLeast(2))
    val mappedY = CompositorGeometry.mapViewToDisplayCoord(surfY, surfH, win.contentHeight.coerceAtLeast(2))

    val isMouse = pointerType == PointerType.Mouse
    val isStylus = pointerType == PointerType.Stylus
    val source = when {
        isMouse -> InputDevice.SOURCE_MOUSE
        isStylus -> InputDevice.SOURCE_STYLUS
        else -> InputDevice.SOURCE_TOUCHSCREEN
    }
    val toolType = when {
        isMouse -> MotionEvent.TOOL_TYPE_MOUSE
        isStylus -> MotionEvent.TOOL_TYPE_STYLUS
        else -> MotionEvent.TOOL_TYPE_FINGER
    }
    val downTime = downTimeMs.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
    val upTime = (upTimeMs.takeIf { it > 0L } ?: SystemClock.uptimeMillis()).coerceAtLeast(downTime + 1L)

    BridgeClient.injectWindowMotionOneway(
        windowId = win.windowId,
        action = MotionEvent.ACTION_DOWN,
        downTime = downTime,
        eventTime = downTime,
        source = source,
        buttonState = if (isMouse) MotionEvent.BUTTON_PRIMARY else 0,
        actionButton = 0,
        hScroll = 0f,
        vScroll = 0f,
        pointerIds = intArrayOf(0),
        toolTypes = intArrayOf(toolType),
        xs = floatArrayOf(mappedX),
        ys = floatArrayOf(mappedY),
        pressures = floatArrayOf(1f),
    )
    BridgeClient.injectWindowMotionOneway(
        windowId = win.windowId,
        action = MotionEvent.ACTION_UP,
        downTime = downTime,
        eventTime = upTime,
        source = source,
        buttonState = 0,
        actionButton = 0,
        hScroll = 0f,
        vScroll = 0f,
        pointerIds = intArrayOf(0),
        toolTypes = intArrayOf(toolType),
        xs = floatArrayOf(mappedX),
        ys = floatArrayOf(mappedY),
        pressures = floatArrayOf(0f),
    )
}
