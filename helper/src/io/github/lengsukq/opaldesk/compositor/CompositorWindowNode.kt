package io.github.lengsukq.opaldesk.compositor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.ResizeEdge
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.MacTrafficLightButton
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.TrafficLightType
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Unified Single-RenderNode macOS-styled Window Container:
 * Traffic-light header chrome, squircle-like rounded clip, layered elevation shadow,
 * 8-way live resize handles, and hardware `TextureView` move & resize in strict GPU lockstep.
 */
@Composable
fun CompositorWindowFrameNode(
    window: CompositorWindow,
    canvasW: Int,
    canvasH: Int,
    dockInsetDp: Float = CompositorGeometry.DOCK_INSET_DP,
    onSnapPreviewChanged: (WindowRect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val densityVal = density.density
    val currentWin by rememberUpdatedState(window)
    val currentDockInsetDp by rememberUpdatedState(dockInsetDp)

    var isInteracting by remember(window.windowId) { mutableStateOf(false) }
    var liveFrame by remember(window.windowId) { mutableStateOf(window.frame) }
    var pendingCommitFrame by remember(window.windowId) { mutableStateOf<WindowRect?>(null) }

    // Keep displaying locally committed frame until daemon confirms it (eliminates drop flicker)
    LaunchedEffect(window.frame, isInteracting, pendingCommitFrame) {
        if (isInteracting) return@LaunchedEffect
        val pending = pendingCommitFrame
        if (pending == null || window.frame == pending) {
            pendingCommitFrame = null
            liveFrame = window.frame
        } else {
            delay(360L)
            pendingCommitFrame = null
            liveFrame = currentWin.frame
        }
    }

    val activeFrame = if (isInteracting || pendingCommitFrame != null) liveFrame else window.frame
    val isFullscreen = window.isFullscreen
    val winShape = RoundedCornerShape(
        when {
            isFullscreen -> 0.dp
            window.isMaximized -> 8.dp
            else -> 14.dp
        },
    )
    val borderColor = if (window.focused) OpalPalette.BorderFocused else OpalPalette.BorderHairline
    val headerBg = if (window.focused) OpalPalette.GlassHeaderFocused else OpalPalette.GlassHeaderUnfocused
    val appIcon = remember(window.pkg) { AppCatalogRepository.getAppIcon(ctx, window.pkg) }

    val frameWidthDp = with(density) { activeFrame.width.coerceAtLeast(220).toDp() }
    val frameHeightDp = with(density) { activeFrame.height.coerceAtLeast(220).toDp() }
    val headerHeightPx = if (isFullscreen) 0 else with(density) { CompositorGeometry.HEADER_HEIGHT_DP.dp.roundToPx() }
    val showSnapTools = frameWidthDp >= 540.dp
    val showReloadButton = frameWidthDp >= 360.dp

    var frameCoords by remember(window.windowId) { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier
            .offset { IntOffset(activeFrame.left, activeFrame.top) }
            .size(width = frameWidthDp, height = frameHeightDp)
            .onGloballyPositioned { frameCoords = it }
            // Focus immediately on initial press (zero double-tap delay, zero 120Hz hover churn) and absorb
            // any leftover unconsumed pointer event on the bottom-up Main pass (NEVER Final, which is
            // top-down and would cancel AndroidView TextureView ACTION_MOVE drags/scrolls!).
            .pointerInput(window.windowId, "focus_and_shield") {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!currentWin.focused) {
                        val pt = down.position
                        val occluded = CompositorGeometry.isPointOccludedByHigherWindow(
                            target = currentWin,
                            canvasX = currentWin.frame.left + pt.x,
                            canvasY = currentWin.frame.top + pt.y,
                            visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                        )
                        if (!occluded) {
                            BridgeClient.sendWindowActionAsync(currentWin.windowId, BridgeProtocol.WinAction.FOCUS)
                        }
                    }
                    // Main pass runs bottom-up (children first, then parent): safely absorbs clicks
                    // on empty chrome without interfering with AndroidView(TextureView) scrolling!
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        ev.changes.forEach { if (!it.isConsumed) it.consume() }
                        if (ev.changes.all { !it.pressed }) break
                    }
                }
            },
    ) {
        // Keep a SINGLE stable Column + WindowSurfaceHost composition slot across FLOATING / MAXIMIZED / FULLSCREEN
        // so entering or exiting fullscreen in streaming apps (Moonlight, UU远程) NEVER unmounts/recreates TextureView,
        // while skipping offscreen shadow/clip/border RenderNode layers in fullscreen to save GPU VRAM.
        val columnModifier = if (isFullscreen) {
            Modifier
                .fillMaxSize()
                .background(OpalPalette.WindowCanvasBg)
        } else {
            Modifier
                .fillMaxSize()
                .shadow(
                    elevation = if (window.focused) 24.dp else 8.dp,
                    shape = winShape,
                    clip = false,
                )
                .clip(winShape)
                .background(OpalPalette.WindowCanvasBg)
                .border(0.9.dp, borderColor, winShape)
        }
        Column(
            modifier = columnModifier,
        ) {
            if (!isFullscreen) {
            // macOS 36dp Title Bar Chrome — unified root-space drag + tap/double-tap gesture
            var titleCoords by remember(window.windowId) { mutableStateOf<LayoutCoordinates?>(null) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CompositorGeometry.HEADER_HEIGHT_DP.dp)
                    .background(headerBg)
                    .onGloballyPositioned { titleCoords = it }
                    .pointerInput(window.windowId, canvasW, canvasH) {
                        var lastTapMs = 0L
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val startRoot = titleCoords?.localToRoot(down.position) ?: down.position
                            val slop = viewConfiguration.touchSlop
                            var dragging = false
                            var startFrame = currentWin.frame
                            var lastLive = startFrame
                            var activeSnap: String? = null
                            var committed = false

                            try {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Main)
                                    val change = ev.changes.firstOrNull { it.id == down.id }
                                        ?: ev.changes.firstOrNull()
                                        ?: break
                                    val curRoot = titleCoords?.localToRoot(change.position) ?: change.position
                                    val dx = curRoot.x - startRoot.x
                                    val dy = curRoot.y - startRoot.y

                                    if (!dragging && change.pressed && hypot(dx, dy) >= slop) {
                                        dragging = true
                                        isInteracting = true
                                        pendingCommitFrame = null
                                        activeSnap = null
                                        startFrame = if (currentWin.presentation != BridgeProtocol.Presentation.FLOATING) {
                                            val portraitCanvas = canvasH > canvasW
                                            val w = (canvasW * (if (portraitCanvas) 0.90f else 0.56f))
                                                .roundToInt().coerceAtLeast(320)
                                            val h = (canvasH * (if (portraitCanvas) 0.54f else 0.68f))
                                                .roundToInt().coerceAtLeast(300)
                                            val l = (startRoot.x.roundToInt() - w / 2)
                                                .coerceIn(10, (canvasW - w - 10).coerceAtLeast(10))
                                            val t = (currentWin.frame.top + 6).coerceAtLeast(
                                                CompositorGeometry.dpToPx(CompositorGeometry.TOP_BAR_DP, densityVal),
                                            )
                                            WindowRect(l, t, l + w, t + h)
                                        } else {
                                            currentWin.frame
                                        }
                                        lastLive = startFrame
                                        liveFrame = startFrame
                                        if (!currentWin.focused) {
                                            BridgeClient.sendWindowActionAsync(
                                                currentWin.windowId,
                                                BridgeProtocol.WinAction.FOCUS,
                                            )
                                        }
                                    }

                                    if (dragging) {
                                        if (change.pressed) {
                                            change.consume()
                                            val next = CompositorGeometry.clampMove(
                                                startFrame,
                                                dx.roundToInt(),
                                                dy.roundToInt(),
                                                canvasW,
                                                canvasH,
                                                densityVal,
                                            )
                                            lastLive = next
                                            liveFrame = next

                                            val snap = CompositorGeometry.detectSnapPresentation(
                                                curRoot.x.roundToInt().coerceIn(0, canvasW),
                                                curRoot.y.roundToInt().coerceIn(0, canvasH),
                                                canvasW,
                                                canvasH,
                                                densityVal,
                                            )
                                            activeSnap = snap
                                            onSnapPreviewChanged(
                                                snap?.let {
                                                    CompositorGeometry.computeSnapPreviewRect(
                                                        it, canvasW, canvasH, densityVal, currentDockInsetDp,
                                                    )
                                                },
                                            )
                                        } else {
                                            change.consume()
                                            val snap = activeSnap
                                            val finalFrame = if (snap != null) {
                                                CompositorGeometry.computeSnapPreviewRect(
                                                    snap, canvasW, canvasH, densityVal, currentDockInsetDp,
                                                )
                                            } else {
                                                lastLive
                                            }
                                            liveFrame = finalFrame
                                            pendingCommitFrame = finalFrame
                                            committed = true
                                            BridgeClient.updateWindowFrameAsync(
                                                windowId = currentWin.windowId,
                                                left = finalFrame.left,
                                                top = finalFrame.top,
                                                right = finalFrame.right,
                                                bottom = finalFrame.bottom,
                                                presentation = snap ?: BridgeProtocol.Presentation.FLOATING,
                                            )
                                            break
                                        }
                                    } else if (!change.pressed) {
                                        if (!change.isConsumed) {
                                            val now = SystemClock.uptimeMillis()
                                            if (now - lastTapMs <= 280L) {
                                                lastTapMs = 0L
                                                BridgeClient.sendWindowActionAsync(
                                                    currentWin.windowId,
                                                    BridgeProtocol.WinAction.TOGGLE_MAXIMIZE,
                                                )
                                            } else {
                                                lastTapMs = now
                                                if (!currentWin.focused) {
                                                    BridgeClient.sendWindowActionAsync(
                                                        currentWin.windowId,
                                                        BridgeProtocol.WinAction.FOCUS,
                                                    )
                                                }
                                            }
                                        }
                                        break
                                    }
                                }
                            } finally {
                                onSnapPreviewChanged(null)
                                if (dragging && !committed) {
                                    pendingCommitFrame = null
                                    liveFrame = currentWin.frame
                                }
                                isInteracting = false
                            }
                        }
                    }
                    .padding(horizontal = 10.dp),
            ) {
                // Left Cluster: macOS Traffic Lights + Android Back
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MacTrafficLightButton(TrafficLightType.CLOSE, window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.CLOSE)
                    }
                    MacTrafficLightButton(TrafficLightType.MINIMIZE, window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.MINIMIZE)
                    }
                    MacTrafficLightButton(TrafficLightType.ZOOM, window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.TOGGLE_MAXIMIZE)
                    }
                    Spacer(Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(OpalPalette.DividerColor),
                    )
                    Spacer(Modifier.width(3.dp))
                    HeaderSubtleIconButton(glyph = ChromeGlyph.BACK, focused = window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.SEND_BACK)
                    }
                }

                // Flexible Centered App Icon + Title (physically isolated between left & right clusters)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon,
                            contentDescription = window.title,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (frameWidthDp >= 250.dp) {
                        OpalText(
                            text = window.title,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (window.focused) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }

                // Right Cluster: Optional Snap Left/Right (wide windows only), Pin icon, Profile Switcher, Reload
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (showSnapTools) {
                        HeaderSubtleIconButton(glyph = ChromeGlyph.TILE_LEFT, focused = window.focused) {
                            val rect = CompositorGeometry.computeSnapPreviewRect(
                                BridgeProtocol.Presentation.SNAP_LEFT, canvasW, canvasH, densityVal, currentDockInsetDp,
                            )
                            BridgeClient.updateWindowFrameAsync(
                                window.windowId, rect.left, rect.top, rect.right, rect.bottom,
                                BridgeProtocol.Presentation.SNAP_LEFT,
                            )
                        }
                        HeaderSubtleIconButton(glyph = ChromeGlyph.TILE_RIGHT, focused = window.focused) {
                            val rect = CompositorGeometry.computeSnapPreviewRect(
                                BridgeProtocol.Presentation.SNAP_RIGHT, canvasW, canvasH, densityVal, currentDockInsetDp,
                            )
                            BridgeClient.updateWindowFrameAsync(
                                window.windowId, rect.left, rect.top, rect.right, rect.bottom,
                                BridgeProtocol.Presentation.SNAP_RIGHT,
                            )
                        }
                    }

                    val pinnedIds by io.github.lengsukq.opaldesk.protocol.OpalWindowPinStore.pinnedIdsFlow.collectAsState()
                    val isPinned = pinnedIds.contains(window.windowId)
                    HeaderPinIconButton(
                        pinned = isPinned,
                        focused = window.focused,
                    ) {
                        io.github.lengsukq.opaldesk.protocol.OpalWindowPinStore.toggle(window.windowId)
                    }

                    val profileLabel = when (window.profileCode) {
                        BridgeProtocol.Profile.PORTRAIT -> "竖屏"
                        BridgeProtocol.Profile.TABLET -> "平板"
                        else -> "桌面"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (window.focused) OpalPalette.ChipFill else Color.Transparent)
                            .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(6.dp))
                            .clickable {
                                val nextAct = when (window.profileCode) {
                                    BridgeProtocol.Profile.DESKTOP -> BridgeProtocol.WinAction.SET_PROFILE_TABLET
                                    BridgeProtocol.Profile.TABLET -> BridgeProtocol.WinAction.SET_PROFILE_PORTRAIT
                                    else -> BridgeProtocol.WinAction.SET_PROFILE_DESKTOP
                                }
                                BridgeClient.sendWindowActionAsync(window.windowId, nextAct)
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        OpalText(
                            text = profileLabel,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (window.focused) OpalPalette.TextPrimary else OpalPalette.TextMuted,
                            maxLines = 1,
                        )
                    }

                    if (showReloadButton) {
                        HeaderSubtleIconButton(glyph = ChromeGlyph.RELOAD, focused = window.focused) {
                            BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.RELOAD_APP)
                        }
                    }
                }
            }

            // Hairline separator beneath macOS titlebar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.75.dp)
                    .background(OpalPalette.DividerColor),
            )
            }

            // Live VirtualDisplay TextureView Surface Host
            WindowSurfaceHost(
                window = window,
                headerHeightPx = headerHeightPx,
                onTouchFocusRequested = {
                    BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.FOCUS)
                },
                modifier = Modifier.weight(1f),
            )
        }

        // 8-Way Live GPU Resize Handles (pure GPU frame preview during drag; single atomic VirtualDisplay resize on commit)
        if (!window.isMaximized && !isFullscreen) {
            val onStart = {
                isInteracting = true
                pendingCommitFrame = null
            }
            val onCommit: (WindowRect) -> Unit = { committed ->
                liveFrame = committed
                pendingCommitFrame = committed
                isInteracting = false
            }
            val onCancel = {
                pendingCommitFrame = null
                isInteracting = false
                liveFrame = currentWin.frame
            }

            val getFrameCoords = { frameCoords }
            ResizeHandleZone(ResizeEdge.E, Alignment.CenterEnd, Modifier.width(14.dp).height((frameHeightDp - 52.dp).coerceAtLeast(36.dp)), currentWin, headerHeightPx, getFrameCoords, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.W, Alignment.CenterStart, Modifier.width(14.dp).height((frameHeightDp - 52.dp).coerceAtLeast(36.dp)), currentWin, headerHeightPx, getFrameCoords, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.S, Alignment.BottomCenter, Modifier.height(14.dp).width((frameWidthDp - 52.dp).coerceAtLeast(36.dp)), currentWin, headerHeightPx, getFrameCoords, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.N, Alignment.TopCenter, Modifier.height(5.dp).width((frameWidthDp - 180.dp).coerceAtLeast(36.dp)), currentWin, headerHeightPx, getFrameCoords, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.SE, Alignment.BottomEnd, Modifier.size(26.dp), currentWin, headerHeightPx, getFrameCoords, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel, showGrip = true)
            ResizeHandleZone(ResizeEdge.SW, Alignment.BottomStart, Modifier.size(26.dp), currentWin, headerHeightPx, getFrameCoords, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.NE, Alignment.TopEnd, Modifier.size(16.dp), currentWin, headerHeightPx, getFrameCoords, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.NW, Alignment.TopStart, Modifier.size(16.dp), currentWin, headerHeightPx, getFrameCoords, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
        }
    }
}

@Composable
private fun HeaderSubtleIconButton(
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
private fun HeaderPinIconButton(
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
private fun BoxScope.ResizeHandleZone(
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
