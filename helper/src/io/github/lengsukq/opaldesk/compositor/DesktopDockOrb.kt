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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lengsukq.opaldesk.prefs.DockCustomizationStore
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val ORB_HOVER_COLLAPSE_DELAY_MS = 600L
private const val ORB_IDLE_DIM_DELAY_MS = 3_000L

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

    val orbSizePx = with(density) { 48.dp.toPx() }
    val edgeMarginPx = with(density) { 10.dp.toPx() }
    val topBoundPx = with(density) { (CompositorGeometry.TOP_BAR_DP + 10f).dp.toPx() }
    val bottomBoundPx = (canvasH.toFloat() - orbSizePx - edgeMarginPx).coerceAtLeast(topBoundPx)
    val leftEdgePx = edgeMarginPx
    val rightEdgePx = (canvasW.toFloat() - orbSizePx - edgeMarginPx).coerceAtLeast(leftEdgePx)

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

    val activeOpacity = dockCustom.orbOpacity.coerceIn(0.25f, 1.0f)
    val targetOrbAlpha = if (isIdleDimmed && dockCustom.orbIdleDim) {
        (activeOpacity * 0.58f).coerceAtLeast(0.22f)
    } else {
        activeOpacity
    }
    val animatedOrbAlpha by animateFloatAsState(
        targetValue = targetOrbAlpha,
        animationSpec = tween(durationMillis = if (isIdleDimmed) 420 else 180, easing = FastOutSlowInEasing),
        label = "orbAlpha",
    )

    val orbScaleTarget = when {
        isPressingOrb -> 0.92f
        isHoveringOrb || isDraggingOrb -> 1.08f
        else -> 1.0f
    }
    val animatedOrbScale by animateFloatAsState(
        targetValue = orbScaleTarget,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "orbScale",
    )

    val isDockedRight = (animatedOrbX + orbSizePx * 0.5f) >= (canvasW.coerceAtLeast(1) * 0.5f)
    val expandOrigin = TransformOrigin(if (isDockedRight) 0.92f else 0.08f, 1f)
    val expandSpringFloat = spring<Float>(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow)
    val expandSpringOffset = spring<IntOffset>(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow)

    Box(modifier = modifier.fillMaxSize()) {
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
                                val snapRight = (rawOrbX + orbSizePx * 0.5f) >= (canvasW.coerceAtLeast(1) * 0.5f)
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
    val pulseTransition = rememberInfiniteTransition(label = "orbPulse")
    val coreBreath by pulseTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "coreBreath",
    )
    val glassOpacity = orbOpacity.coerceIn(0.25f, 1.0f)
    val effectScale = (glassOpacity * 1.15f).coerceIn(0.45f, 1.3f)

    Box(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .alpha(animatedAlpha)
            .liquidGlassSurface(
                shape = CircleShape,
                fill = OpalPalette.dockGlassFill(glassOpacity),
                rim = OpalPalette.dockGlassRim(glassOpacity),
                elevation = (if (isActive) 22f else 16f).dp,
                borderWidth = 1.15.dp,
                effectAlphaScale = effectScale,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val r = size.minDimension * 0.5f

            // Outer frosted refraction ring
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.58f to Color.White.copy(alpha = if (colors.isDark) 0.14f else 0.34f),
                        0.82f to colors.accentCyan.copy(alpha = if (isActive) 0.24f else 0.12f),
                        1.0f to Color.White.copy(alpha = if (colors.isDark) 0.36f else 0.72f),
                    ),
                    center = Offset(cx, cy),
                    radius = r * 0.88f,
                ),
                radius = r * 0.86f,
                center = Offset(cx, cy),
            )

            // Dual frosted inner ring stroke
            drawCircle(
                color = Color.White.copy(alpha = if (colors.isDark) 0.40f else 0.78f),
                radius = r * 0.54f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.3.dp.toPx()),
            )

            // Soft luminous halo behind the core dot
            val haloRadius = r * 0.44f * coreBreath
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        (if (isActive) colors.accentCyan else Color.White).copy(alpha = if (isActive) 0.48f else 0.30f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = haloRadius,
                ),
                radius = haloRadius,
                center = Offset(cx, cy),
            )

            // Breathing core dot with crisp specular highlight
            val coreRadius = r * 0.27f * coreBreath
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.96f),
                        Color.White.copy(alpha = if (colors.isDark) 0.74f else 0.84f),
                    ),
                    center = Offset(cx - coreRadius * 0.22f, cy - coreRadius * 0.24f),
                    radius = coreRadius * 1.15f,
                ),
                radius = coreRadius,
                center = Offset(cx, cy),
            )
        }
    }
}
