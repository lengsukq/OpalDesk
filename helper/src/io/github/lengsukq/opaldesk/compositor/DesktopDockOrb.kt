package io.github.lengsukq.opaldesk.compositor

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.lengsukq.opaldesk.prefs.DockCustomizationStore
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.LocalOpalGlassBackdrop
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val ORB_HOVER_COLLAPSE_DELAY_MS = 600L
private const val ORB_IDLE_DIM_DELAY_MS = 3_000L
private const val ORB_VISUAL_SIZE_DP = 48f
private const val ORB_HALO_PAD_DP = 7f
private const val ORB_CONTAINER_SIZE_DP = ORB_VISUAL_SIZE_DP + ORB_HALO_PAD_DP * 2f

/**
 * Hosts either the standard bottom [DexMacTaskbar] or the floating Liquid Glass "Assistive Orb"
 * mode when `DockCustomizationConfig.orbMode` is enabled.
 */
@Composable
fun DesktopDockOrbHost(
    allApps: List<InstalledAppEntry>,
    dockPinnedPkgs: List<String>,
    windows: List<CompositorWindow>,
    canvasW: Int,
    canvasH: Int,
    showAppDrawer: Boolean,
    showExpose: Boolean,
    showSpotlight: Boolean,
    showControlCenter: Boolean,
    hasOpenOverlay: Boolean,
    collapsePulse: Long,
    isCompactWidth: Boolean,
    dockOpacity: Float,
    dockScale: Float,
    imeActive: Boolean,
    onToggleSoftKeyboard: (() -> Unit)?,
    onToggleAppDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleControlCenter: () -> Unit,
    onToggleExpose: () -> Unit,
    onSendBackToFocused: () -> Unit,
    onShowDesktopToggle: () -> Unit,
    onLaunchOrToggleApp: (String, String, Int) -> Unit,
    onContextApp: (String, CompositorWindow?, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val dockCustom by remember(ctx) { DockCustomizationStore.observe(ctx) }.collectAsState()

    if (!dockCustom.orbMode) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            DexMacTaskbar(
                allApps = allApps,
                dockPinnedPkgs = dockPinnedPkgs,
                windows = windows,
                showAppDrawer = showAppDrawer,
                showExpose = showExpose,
                showSpotlight = showSpotlight,
                showControlCenter = showControlCenter,
                hasOpenOverlay = hasOpenOverlay,
                isCompactWidth = isCompactWidth,
                dockOpacity = dockOpacity,
                dockScale = dockScale,
                imeActive = imeActive,
                onToggleSoftKeyboard = onToggleSoftKeyboard,
                onToggleAppDrawer = onToggleAppDrawer,
                onOpenSearch = onOpenSearch,
                onToggleControlCenter = onToggleControlCenter,
                onToggleExpose = onToggleExpose,
                onSendBackToFocused = onSendBackToFocused,
                onShowDesktopToggle = onShowDesktopToggle,
                onLaunchOrToggleApp = onLaunchOrToggleApp,
                onContextApp = onContextApp,
            )
        }
        return
    }

    val orbSizePx = with(density) { ORB_VISUAL_SIZE_DP.dp.toPx() }
    val haloPadPx = with(density) { ORB_HALO_PAD_DP.dp.toPx() }
    val containerSizePx = orbSizePx + haloPadPx * 2f
    val edgeMarginPx = with(density) { 10.dp.toPx() }
    val topBoundPx = with(density) { (CompositorGeometry.TOP_BAR_DP + 10f).dp.toPx() } - haloPadPx
    val bottomBoundPx = (canvasH.toFloat() - orbSizePx - edgeMarginPx - haloPadPx).coerceAtLeast(topBoundPx)
    val leftEdgePx = edgeMarginPx - haloPadPx
    val rightEdgePx = (canvasW.toFloat() - orbSizePx - edgeMarginPx - haloPadPx).coerceAtLeast(leftEdgePx)

    var orbExpanded by remember { mutableStateOf(false) }
    var expandedByHover by remember { mutableStateOf(false) }
    var isHoveringOrb by remember { mutableStateOf(false) }
    var isHoveringExpandedDock by remember { mutableStateOf(false) }
    var isPressingOrb by remember { mutableStateOf(false) }
    var isDraggingOrb by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var isIdleDimmed by remember { mutableStateOf(false) }
    var lastCollapsePulse by remember { mutableLongStateOf(collapsePulse) }

    val targetInitialX = if (dockCustom.orbDockedRight) rightEdgePx else leftEdgePx
    val targetInitialY = topBoundPx + (bottomBoundPx - topBoundPx) * dockCustom.orbVerticalFraction.coerceIn(0.05f, 0.95f)
    var rawOrbX by remember { mutableFloatStateOf(targetInitialX) }
    var rawOrbY by remember { mutableFloatStateOf(targetInitialY) }

    LaunchedEffect(canvasW, canvasH, dockCustom.orbDockedRight, dockCustom.orbVerticalFraction, isDraggingOrb) {
        if (!isDraggingOrb && canvasW > 0 && canvasH > 0) {
            rawOrbX = if (dockCustom.orbDockedRight) rightEdgePx else leftEdgePx
            rawOrbY = topBoundPx + (bottomBoundPx - topBoundPx) * dockCustom.orbVerticalFraction.coerceIn(0.05f, 0.95f)
        }
    }

    LaunchedEffect(collapsePulse) {
        if (collapsePulse != lastCollapsePulse) {
            lastCollapsePulse = collapsePulse
            if (orbExpanded) {
                orbExpanded = false
                expandedByHover = false
                lastInteractionMs = SystemClock.uptimeMillis()
            }
        }
    }

    val anyPopoverOpen = hasOpenOverlay || showAppDrawer || showControlCenter || showSpotlight || showExpose

    // Mouse hover leave auto-collapse (~600ms)
    LaunchedEffect(orbExpanded, expandedByHover, isHoveringOrb, isHoveringExpandedDock, anyPopoverOpen) {
        if (orbExpanded && expandedByHover && !isHoveringOrb && !isHoveringExpandedDock && !anyPopoverOpen) {
            delay(ORB_HOVER_COLLAPSE_DELAY_MS)
            orbExpanded = false
            expandedByHover = false
            lastInteractionMs = SystemClock.uptimeMillis()
        }
    }

    // Idle dimming after 3 seconds without interaction
    LaunchedEffect(dockCustom.orbIdleDim, orbExpanded, isHoveringOrb, isDraggingOrb, isPressingOrb, lastInteractionMs) {
        if (!dockCustom.orbIdleDim || orbExpanded || isHoveringOrb || isDraggingOrb || isPressingOrb) {
            isIdleDimmed = false
            return@LaunchedEffect
        }
        delay(ORB_IDLE_DIM_DELAY_MS)
        isIdleDimmed = true
    }

    val posSpec = if (isDraggingOrb) {
        snap<Float>()
    } else {
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    }
    val animatedOrbX by animateFloatAsState(targetValue = rawOrbX, animationSpec = posSpec, label = "orbX")
    val animatedOrbY by animateFloatAsState(targetValue = rawOrbY, animationSpec = posSpec, label = "orbY")

    val activeOpacity = DockAppearancePolicy.clampOrbOpacity(dockCustom.orbOpacity)
    val targetOrbAlpha = DockAppearancePolicy.computeOrbTargetAlpha(
        orbOpacity = activeOpacity,
        idleDimEnabled = dockCustom.orbIdleDim,
        isIdleDimmed = isIdleDimmed,
    )
    val animatedOrbAlpha by animateFloatAsState(
        targetValue = targetOrbAlpha,
        animationSpec = tween(durationMillis = if (isIdleDimmed) 420 else 180, easing = FastOutSlowInEasing),
        label = "orbAlpha",
    )

    val orbScaleTarget = when {
        isPressingOrb -> 0.93f
        isHoveringOrb || isDraggingOrb -> 1.07f
        else -> 1.0f
    }
    val animatedOrbScale by animateFloatAsState(
        targetValue = orbScaleTarget,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium),
        label = "orbScale",
    )

    val isDockedRight = (animatedOrbX + containerSizePx * 0.5f) >= (canvasW.coerceAtLeast(1) * 0.5f)
    val expandOrigin = TransformOrigin(if (isDockedRight) 0.92f else 0.08f, 1f)
    val expandSpringFloat = spring<Float>(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow)
    val expandSpringOffset = spring<IntOffset>(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow)

    Box(modifier = modifier.fillMaxSize()) {
        // Transparent outside-click scrim behind the expanded Dock so tapping/clicking anywhere
        // outside (desktop, icons, widgets, or live app windows) collapses back to the orb.
        if (DockAppearancePolicy.shouldShowOrbDockOutsideDismissScrim(
                orbMode = true,
                orbExpanded = orbExpanded,
                anyPopoverOpen = anyPopoverOpen,
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            orbExpanded = false
                            expandedByHover = false
                            lastInteractionMs = SystemClock.uptimeMillis()
                        }
                    },
            )
        }

        // Expanded Dock with coordinated spring scale + fade + edge-directional slide
        AnimatedVisibility(
            visible = orbExpanded,
            enter = fadeIn(tween(180)) +
                scaleIn(animationSpec = expandSpringFloat, initialScale = 0.84f, transformOrigin = expandOrigin) +
                slideIn(animationSpec = expandSpringOffset) { fullSize ->
                    IntOffset(
                        x = if (isDockedRight) (fullSize.width * 0.14f).roundToInt() else -(fullSize.width * 0.14f).roundToInt(),
                        y = (fullSize.height * 0.42f).roundToInt(),
                    )
                },
            exit = fadeOut(tween(150)) +
                scaleOut(
                    animationSpec = tween(170, easing = FastOutSlowInEasing),
                    targetScale = 0.86f,
                    transformOrigin = expandOrigin,
                ) +
                slideOut(animationSpec = tween(170, easing = FastOutSlowInEasing)) { fullSize ->
                    IntOffset(
                        x = if (isDockedRight) (fullSize.width * 0.12f).roundToInt() else -(fullSize.width * 0.12f).roundToInt(),
                        y = (fullSize.height * 0.36f).roundToInt(),
                    )
                },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.any { it.type == PointerType.Mouse }) {
                                when (ev.type) {
                                    PointerEventType.Enter, PointerEventType.Move -> {
                                        isHoveringExpandedDock = true
                                        lastInteractionMs = SystemClock.uptimeMillis()
                                    }
                                    PointerEventType.Exit -> {
                                        isHoveringExpandedDock = false
                                        expandedByHover = true
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            DexMacTaskbar(
                allApps = allApps,
                dockPinnedPkgs = dockPinnedPkgs,
                windows = windows,
                showAppDrawer = showAppDrawer,
                showExpose = showExpose,
                showSpotlight = showSpotlight,
                showControlCenter = showControlCenter,
                hasOpenOverlay = true,
                isCompactWidth = isCompactWidth,
                dockOpacity = dockOpacity,
                dockScale = dockScale,
                imeActive = imeActive,
                onToggleSoftKeyboard = onToggleSoftKeyboard,
                onToggleAppDrawer = onToggleAppDrawer,
                onOpenSearch = onOpenSearch,
                onToggleControlCenter = onToggleControlCenter,
                onToggleExpose = onToggleExpose,
                onSendBackToFocused = onSendBackToFocused,
                onShowDesktopToggle = onShowDesktopToggle,
                onLaunchOrToggleApp = onLaunchOrToggleApp,
                onContextApp = onContextApp,
                onCollapseToOrb = {
                    orbExpanded = false
                    expandedByHover = false
                    lastInteractionMs = SystemClock.uptimeMillis()
                },
            )
        }

        // Liquid Glass Floating Assistive Orb
        AnimatedVisibility(
            visible = !orbExpanded,
            enter = fadeIn(tween(180)) + scaleIn(
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
                initialScale = 0.72f,
            ),
            exit = fadeOut(tween(130)) + scaleOut(tween(140), targetScale = 0.76f),
            modifier = Modifier.offset {
                IntOffset(animatedOrbX.roundToInt(), animatedOrbY.roundToInt())
            },
        ) {
            LiquidGlassDockOrb(
                orbOpacity = activeOpacity,
                animatedAlpha = animatedOrbAlpha,
                animatedScale = animatedOrbScale,
                isActive = isHoveringOrb || isDraggingOrb || isPressingOrb,
                modifier = Modifier
                    .pointerInput(dockCustom.orbHoverExpand) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.any { it.type == PointerType.Mouse }) {
                                    when (ev.type) {
                                        PointerEventType.Enter, PointerEventType.Move -> {
                                            isHoveringOrb = true
                                            lastInteractionMs = SystemClock.uptimeMillis()
                                            if (dockCustom.orbHoverExpand && !isDraggingOrb) {
                                                expandedByHover = true
                                                orbExpanded = true
                                            }
                                        }
                                        PointerEventType.Exit -> isHoveringOrb = false
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressingOrb = true
                                lastInteractionMs = SystemClock.uptimeMillis()
                                tryAwaitRelease()
                                isPressingOrb = false
                            },
                            onTap = {
                                lastInteractionMs = SystemClock.uptimeMillis()
                                expandedByHover = false
                                orbExpanded = !orbExpanded
                            },
                        )
                    }
                    .pointerInput(canvasW, canvasH, dockCustom.orbSnapToEdge) {
                        detectDragGestures(
                            onDragStart = {
                                isDraggingOrb = true
                                isPressingOrb = true
                                lastInteractionMs = SystemClock.uptimeMillis()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                rawOrbX = (rawOrbX + dragAmount.x).coerceIn(leftEdgePx, rightEdgePx)
                                rawOrbY = (rawOrbY + dragAmount.y).coerceIn(topBoundPx, bottomBoundPx)
                            },
                            onDragEnd = {
                                isDraggingOrb = false
                                isPressingOrb = false
                                lastInteractionMs = SystemClock.uptimeMillis()
                                val snapRight = (rawOrbX + containerSizePx * 0.5f) >= (canvasW.coerceAtLeast(1) * 0.5f)
                                if (dockCustom.orbSnapToEdge) {
                                    rawOrbX = if (snapRight) rightEdgePx else leftEdgePx
                                }
                                rawOrbY = rawOrbY.coerceIn(topBoundPx, bottomBoundPx)
                                val fracY = ((rawOrbY - topBoundPx) / (bottomBoundPx - topBoundPx).coerceAtLeast(1f))
                                    .coerceIn(0.05f, 0.95f)
                                DockCustomizationStore.updateOrbPosition(ctx, snapRight, fracY)
                            },
                            onDragCancel = {
                                isDraggingOrb = false
                                isPressingOrb = false
                                lastInteractionMs = SystemClock.uptimeMillis()
                            },
                        )
                    },
            )
        }
    }
}

@Composable
private fun LiquidGlassDockOrb(
    orbOpacity: Float,
    animatedAlpha: Float,
    animatedScale: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val backdrop = LocalOpalGlassBackdrop.current
    val orbOrigin = remember { mutableStateOf(Offset.Zero) }
    val coreBreath = if (isActive) {
        val pulseTransition = rememberInfiniteTransition(label = "orbPulse")
        val breath by pulseTransition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "coreBreath",
        )
        breath
    } else {
        1.0f
    }
    val glassOpacity = DockAppearancePolicy.clampOrbOpacity(orbOpacity)
    val intensity = DockAppearancePolicy.computeOrbGlassIntensity(glassOpacity)
    val isDark = colors.isDark

    // Outer transparent halo host (60dp) strictly contains the smooth radial shadow down to 0% alpha
    // so AnimatedVisibility and graphicsLayer alpha/scale NEVER clip a dark square edge.
    Box(
        modifier = modifier
            .size(ORB_CONTAINER_SIZE_DP.dp)
            .graphicsLayer {
                alpha = animatedAlpha
                scaleX = animatedScale
                scaleY = animatedScale
                clip = false
            },
        contentAlignment = Alignment.Center,
    ) {
        // Pure circular diffused ambient shadow (reaches 0f alpha strictly inside the container)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val maxR = size.minDimension * 0.5f
            val shadowY = cy + 1.5.dp.toPx()
            val shadowR = (maxR - 1.5.dp.toPx()).coerceAtLeast(1f)
            val baseShadowAlpha = (if (isDark) 0.24f else 0.16f) * (0.55f + 0.45f * intensity) *
                if (isActive) 1.18f else 1.0f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Black.copy(alpha = baseShadowAlpha),
                        0.66f to Color.Black.copy(alpha = baseShadowAlpha * 0.52f),
                        0.84f to Color.Black.copy(alpha = baseShadowAlpha * 0.14f),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(cx, shadowY),
                    radius = shadowR,
                ),
                radius = shadowR,
                center = Offset(cx, shadowY),
            )
        }

        // Strictly circular Liquid Glass orb body with luminous hairline rim
        val outerRimBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = if (isDark) (0.72f + 0.18f * intensity) else 0.96f),
                0.38f to Color.White.copy(alpha = if (isDark) (0.24f + 0.14f * intensity) else 0.56f),
                0.72f to Color.White.copy(alpha = if (isDark) 0.10f else 0.24f),
                1.0f to (if (isActive) colors.accentCyan else Color.White).copy(
                    alpha = if (isDark) (0.34f + 0.16f * intensity) else 0.62f,
                ),
            ),
        )

        Box(
            modifier = Modifier
                .size(ORB_VISUAL_SIZE_DP.dp)
                .onGloballyPositioned { coords -> orbOrigin.value = coords.positionInRoot() }
                .clip(CircleShape)
                .drawWithContent {
                    // 1. Pixel-aligned blurred wallpaper refraction clipped strictly to CircleShape
                    if (backdrop.active) {
                        val relX = backdrop.rootOriginInRoot.x - orbOrigin.value.x
                        val relY = backdrop.rootOriginInRoot.y - orbOrigin.value.y
                        val rootW = backdrop.rootSize.width.toFloat().coerceAtLeast(1f)
                        val rootH = backdrop.rootSize.height.toFloat().coerceAtLeast(1f)
                        val blurredBmp = backdrop.blurredWallpaper
                        val refractionAlpha = (0.48f + 0.42f * intensity).coerceIn(0.35f, 0.90f)
                        translate(left = relX, top = relY) {
                            if (blurredBmp != null && blurredBmp.width > 0 && blurredBmp.height > 0) {
                                val scale = maxOf(
                                    rootW / blurredBmp.width.toFloat(),
                                    rootH / blurredBmp.height.toFloat(),
                                )
                                val drawW = (blurredBmp.width * scale).roundToInt().coerceAtLeast(1)
                                val drawH = (blurredBmp.height * scale).roundToInt().coerceAtLeast(1)
                                val offX = ((rootW - drawW) * 0.5f).roundToInt()
                                val offY = ((rootH - drawH) * 0.5f).roundToInt()
                                drawImage(
                                    image = blurredBmp,
                                    dstOffset = IntOffset(offX, offY),
                                    dstSize = IntSize(drawW, drawH),
                                    alpha = refractionAlpha,
                                )
                            } else if (backdrop.gradientStops.size >= 2) {
                                drawRect(
                                    brush = Brush.linearGradient(
                                        colors = backdrop.gradientStops,
                                        start = Offset.Zero,
                                        end = Offset(rootW, rootH),
                                    ),
                                    size = Size(rootW, rootH),
                                    alpha = refractionAlpha,
                                )
                            }
                        }
                    }
                    drawContent()
                }
                .border(width = 1.15.dp, brush = outerRimBrush, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val r = size.minDimension * 0.5f

                // 2. Translucent frosted glass outer body (bright & airy; never muddy dark grey)
                val bodyTop = if (isDark) Color(0xFFFFFFFF).copy(alpha = 0.24f + 0.14f * intensity)
                    else Color(0xFFFFFFFF).copy(alpha = 0.58f + 0.26f * intensity)
                val bodyMid = if (isDark) Color(0xFF8FA8C8).copy(alpha = 0.16f + 0.14f * intensity)
                    else Color(0xFFEAF2FC).copy(alpha = 0.42f + 0.26f * intensity)
                val bodyBot = if (isDark) Color(0xFF1E293B).copy(alpha = 0.28f + 0.20f * intensity)
                    else Color(0xFFD6E4F8).copy(alpha = 0.34f + 0.24f * intensity)
                drawCircle(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(0.0f to bodyTop, 0.52f to bodyMid, 1.0f to bodyBot),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )

                // 3. Top-left liquid-glass specular highlight arc
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = if (isDark) 0.46f else 0.78f),
                            0.45f to Color.White.copy(alpha = if (isDark) 0.12f else 0.24f),
                            1.0f to Color.Transparent,
                        ),
                        center = Offset(cx - r * 0.34f, cy - r * 0.36f),
                        radius = r * 0.82f,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )

                // 4. Concentric inner frosted glass ring (iOS AssistiveTouch-inspired proportions)
                val ringR = r * 0.63f
                drawCircle(
                    color = Color.White.copy(alpha = if (isDark) 0.11f else 0.26f),
                    radius = ringR,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.56f else 0.88f),
                            Color.White.copy(alpha = if (isDark) 0.18f else 0.42f),
                        ),
                        start = Offset(cx - ringR, cy - ringR),
                        end = Offset(cx + ringR, cy + ringR),
                    ),
                    radius = ringR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.0.dp.toPx()),
                )

                // 5. Crisp frosted pearl center core with subtle specular sheen
                val coreR = r * 0.33f * coreBreath
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = 0.98f),
                            0.68f to Color.White.copy(alpha = if (isDark) 0.84f else 0.90f),
                            1.0f to Color.White.copy(alpha = if (isDark) 0.62f else 0.72f),
                        ),
                        center = Offset(cx - coreR * 0.26f, cy - coreR * 0.28f),
                        radius = coreR * 1.25f,
                    ),
                    radius = coreR,
                    center = Offset(cx, cy),
                )
                if (isActive) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.55f),
                        radius = coreR,
                        center = Offset(cx, cy),
                        style = Stroke(width = 0.8.dp.toPx()),
                    )
                }
            }
        }
    }
}
