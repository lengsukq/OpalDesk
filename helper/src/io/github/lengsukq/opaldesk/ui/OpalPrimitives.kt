package io.github.lengsukq.opaldesk.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Semantic macOS Liquid Frosted Glass (液态毛玻璃) color tokens supporting:
 * - **Light (浅色, Default)**: translucent frosted pearl glass refracting daylight gradients with crisp dark ink
 * - **Follow System (跟随系统)**: automatically tracks Android system light/dark mode
 * - **Dark (深色)**: translucent smoked-obsidian frosted glass with luminous light ink
 */
@Immutable
data class OpalThemeColors(
    val isDark: Boolean,
    val bgDeep: Color,
    val bgElevated: Color,
    val glassSurface: Color,
    val glassPopover: Color,
    val glassMenuBarTop: Color,
    val glassMenuBarBottom: Color,
    val glassDockTop: Color,
    val glassDockBottom: Color,
    val glassRimTop: Color,
    val glassRimMid: Color,
    val glassRimBottom: Color,
    val glassSpecularTop: Color,
    val glassShadowColor: Color,
    val glassPopoverTop: Color,
    val glassPopoverBottom: Color,
    val glassHeaderFocused: Color,
    val glassHeaderUnfocused: Color,
    val windowCanvasBg: Color,
    val borderSubtle: Color,
    val borderHairline: Color,
    val borderFocused: Color,
    val chipFill: Color,
    val chipActiveFill: Color,
    val dividerColor: Color,
    val inputBg: Color,
    val segmentedTrack: Color,
    val segmentedSelected: Color,
    val segmentedSelectedBorder: Color,
    val switchTrackOff: Color,
    val scrimColor: Color,
    val trackpadDeckBg: Color,
    val trackpadPadBg: Color,
    val trackpadButtonBg: Color,
    val accentBlue: Color,
    val accentCyan: Color,
    val accentEmerald: Color,
    val accentAmber: Color,
    val accentDanger: Color,
    val trafficInactive: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
)

private val LightOpalColors = OpalThemeColors(
    isDark = false,
    bgDeep = Color(0xFFE4ECF8),
    bgElevated = Color(0xB8F5F8FE),
    glassSurface = Color(0x96FFFFFF),
    glassPopover = Color(0xBAFFFFFF),
    glassMenuBarTop = Color(0x8AFFFFFF),
    glassMenuBarBottom = Color(0x66EAF1FC),
    glassDockTop = Color(0x9CFFFFFF),
    glassDockBottom = Color(0x6CEAF1FD),
    glassRimTop = Color(0xFCFFFFFF),
    glassRimMid = Color(0x9CFFFFFF),
    glassRimBottom = Color(0x5A94A3B8),
    glassSpecularTop = Color(0x8CFFFFFF),
    glassShadowColor = Color(0x2E1E3A5F),
    glassPopoverTop = Color(0xE0FFFFFF),
    glassPopoverBottom = Color(0xCCEBF2FC),
    glassHeaderFocused = Color(0xCCFFFFFF),
    glassHeaderUnfocused = Color(0x99EEF3FA),
    windowCanvasBg = Color(0xFFF6F8FC),
    borderSubtle = Color(0x240F172A),
    borderHairline = Color(0x140F172A),
    borderFocused = Color(0x5A007AFF),
    chipFill = Color(0x120F172A),
    chipActiveFill = Color(0x26007AFF),
    dividerColor = Color(0x1E0F172A),
    inputBg = Color(0x8CFFFFFF),
    segmentedTrack = Color(0x140F172A),
    segmentedSelected = Color(0xE0FFFFFF),
    segmentedSelectedBorder = Color(0x240F172A),
    switchTrackOff = Color(0x360F172A),
    scrimColor = Color(0x680F172A),
    trackpadDeckBg = Color(0xFFE2EAF6),
    trackpadPadBg = Color(0x8CFFFFFF),
    trackpadButtonBg = Color(0x9EFFFFFF),
    accentBlue = Color(0xFF007AFF),
    accentCyan = Color(0xFF0284C7),
    accentEmerald = Color(0xFF16A34A),
    accentAmber = Color(0xFFD97706),
    accentDanger = Color(0xFFDC2626),
    trafficInactive = Color(0xFFC2C8D4),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF42526B),
    textMuted = Color(0xFF64748B),
)

private val DarkOpalColors = OpalThemeColors(
    isDark = true,
    bgDeep = Color(0xFF0B0E15),
    bgElevated = Color(0xAA181C26),
    glassSurface = Color(0x881D222E),
    glassPopover = Color(0xB8181C26),
    glassMenuBarTop = Color(0x7E181D2A),
    glassMenuBarBottom = Color(0x6210141F),
    glassDockTop = Color(0x862B3246),
    glassDockBottom = Color(0x94141824),
    glassRimTop = Color(0x70FFFFFF),
    glassRimMid = Color(0x32FFFFFF),
    glassRimBottom = Color(0x1CFFFFFF),
    glassSpecularTop = Color(0x24FFFFFF),
    glassShadowColor = Color(0x99000000),
    glassPopoverTop = Color(0xD8242A3A),
    glassPopoverBottom = Color(0xE0131722),
    glassHeaderFocused = Color(0xC6232836),
    glassHeaderUnfocused = Color(0x94191D28),
    windowCanvasBg = Color(0xFF0A0B0F),
    borderSubtle = Color(0x2EFFFFFF),
    borderHairline = Color(0x1AFFFFFF),
    borderFocused = Color(0x58FFFFFF),
    chipFill = Color(0x16FFFFFF),
    chipActiveFill = Color(0x2CFFFFFF),
    dividerColor = Color(0x26FFFFFF),
    inputBg = Color(0x5E090C14),
    segmentedTrack = Color(0x5A0A0D14),
    segmentedSelected = Color(0xCC3B4152),
    segmentedSelectedBorder = Color(0x40FFFFFF),
    switchTrackOff = Color(0xFF343948),
    scrimColor = Color(0xCC04070F),
    trackpadDeckBg = Color(0xFF0D1018),
    trackpadPadBg = Color(0x80161B28),
    trackpadButtonBg = Color(0x8E1D2332),
    accentBlue = Color(0xFF0A84FF),
    accentCyan = Color(0xFF64D2FF),
    accentEmerald = Color(0xFF30D158),
    accentAmber = Color(0xFFFF9F0A),
    accentDanger = Color(0xFFFF453A),
    trafficInactive = Color(0xFF4A4E58),
    textPrimary = Color(0xFFF5F7FB),
    textSecondary = Color(0xFFA6AEBC),
    textMuted = Color(0xFF737B8C),
)

val LocalOpalColors = staticCompositionLocalOf { LightOpalColors }

/**
 * Root theme provider subscribing live to [AppPrefs.themeModeFlow] so switching between
 * Frosted White Glass (简白大玻璃), Follow System (跟随系统), and Dark Mode (暗色模式) updates
 * the Console, Local Desktop, External Monitor Presentation, and Trackpad in instant lockstep.
 */
@Composable
fun OpalTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    AppPrefs.hydrateFlows(ctx)
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppPrefs.THEME_DARK -> true
        AppPrefs.THEME_SYSTEM -> systemDark
        else -> false
    }
    val colors = if (isDark) DarkOpalColors else LightOpalColors
    CompositionLocalProvider(LocalOpalColors provides colors) {
        content()
    }
}

object OpalPalette {
    val isDark: Boolean @Composable get() = LocalOpalColors.current.isDark
    val BgDeep: Color @Composable get() = LocalOpalColors.current.bgDeep
    val BgElevated: Color @Composable get() = LocalOpalColors.current.bgElevated
    val GlassSurface: Color @Composable get() = LocalOpalColors.current.glassSurface
    val GlassPopover: Color @Composable get() = LocalOpalColors.current.glassPopover
    val GlassHeaderFocused: Color @Composable get() = LocalOpalColors.current.glassHeaderFocused
    val GlassHeaderUnfocused: Color @Composable get() = LocalOpalColors.current.glassHeaderUnfocused
    val WindowCanvasBg: Color @Composable get() = LocalOpalColors.current.windowCanvasBg

    val BorderSubtle: Color @Composable get() = LocalOpalColors.current.borderSubtle
    val BorderHairline: Color @Composable get() = LocalOpalColors.current.borderHairline
    val BorderFocused: Color @Composable get() = LocalOpalColors.current.borderFocused
    val ChipFill: Color @Composable get() = LocalOpalColors.current.chipFill
    val ChipActiveFill: Color @Composable get() = LocalOpalColors.current.chipActiveFill
    val DividerColor: Color @Composable get() = LocalOpalColors.current.dividerColor
    val ScrimColor: Color @Composable get() = LocalOpalColors.current.scrimColor
    val TrackpadDeckBg: Color @Composable get() = LocalOpalColors.current.trackpadDeckBg
    val TrackpadPadBg: Color @Composable get() = LocalOpalColors.current.trackpadPadBg
    val TrackpadButtonBg: Color @Composable get() = LocalOpalColors.current.trackpadButtonBg

    val AccentBlue: Color @Composable get() = LocalOpalColors.current.accentBlue
    val AccentCyan: Color @Composable get() = LocalOpalColors.current.accentCyan
    val AccentEmerald: Color @Composable get() = LocalOpalColors.current.accentEmerald
    val AccentAmber: Color @Composable get() = LocalOpalColors.current.accentAmber
    val AccentDanger: Color @Composable get() = LocalOpalColors.current.accentDanger

    val TrafficRed = Color(0xFFFF5F57)
    val TrafficRedBorder = Color(0xFFE0443E)
    val TrafficYellow = Color(0xFFFEBC2E)
    val TrafficYellowBorder = Color(0xFFD89E24)
    val TrafficGreen = Color(0xFF28C840)
    val TrafficGreenBorder = Color(0xFF1AAB29)
    val TrafficInactive: Color @Composable get() = LocalOpalColors.current.trafficInactive

    val TextPrimary: Color @Composable get() = LocalOpalColors.current.textPrimary
    val TextSecondary: Color @Composable get() = LocalOpalColors.current.textSecondary
    val TextMuted: Color @Composable get() = LocalOpalColors.current.textMuted

    val LiquidDockFill: Brush
        @Composable get() {
            val c = LocalOpalColors.current
            return Brush.verticalGradient(listOf(c.glassDockTop, c.glassDockBottom))
        }

    @Composable
    fun dockGlassFill(opacity: Float): Brush {
        val c = LocalOpalColors.current
        val topAlpha = io.github.lengsukq.opaldesk.compositor.DockAppearancePolicy
            .scaleGlassAlpha(c.glassDockTop.alpha, opacity)
        val bottomAlpha = io.github.lengsukq.opaldesk.compositor.DockAppearancePolicy
            .scaleGlassAlpha(c.glassDockBottom.alpha, opacity)
        return Brush.verticalGradient(
            listOf(
                c.glassDockTop.copy(alpha = topAlpha),
                c.glassDockBottom.copy(alpha = bottomAlpha),
            ),
        )
    }

    @Composable
    fun dockGlassRim(opacity: Float): Brush {
        val c = LocalOpalColors.current
        val scale = io.github.lengsukq.opaldesk.compositor.DockAppearancePolicy
            .rimAndEffectScale(opacity)
        return Brush.verticalGradient(
            listOf(
                c.glassRimTop.copy(alpha = (c.glassRimTop.alpha * scale).coerceIn(0f, 1f)),
                c.glassRimMid.copy(alpha = (c.glassRimMid.alpha * scale).coerceIn(0f, 1f)),
                c.glassRimBottom.copy(alpha = (c.glassRimBottom.alpha * scale).coerceIn(0f, 1f)),
            ),
        )
    }

    val LiquidGlassRim: Brush
        @Composable get() {
            val c = LocalOpalColors.current
            return Brush.verticalGradient(listOf(c.glassRimTop, c.glassRimMid, c.glassRimBottom))
        }

    val LiquidPopoverFill: Brush
        @Composable get() {
            val c = LocalOpalColors.current
            return Brush.verticalGradient(listOf(c.glassPopoverTop, c.glassPopoverBottom))
        }

    /** 100% opaque elevated surface for high-legibility panels (e.g. DeX Control Center over busy app windows). */
    val SolidPopoverFill: Brush
        @Composable get() {
            val c = LocalOpalColors.current
            return if (c.isDark) {
                Brush.verticalGradient(listOf(Color(0xFF202533), Color(0xFF171B26)))
            } else {
                Brush.verticalGradient(listOf(Color(0xFFFCFDFE), Color(0xFFF3F6FB)))
            }
        }

    val SolidPopoverRim: Brush
        @Composable get() {
            val c = LocalOpalColors.current
            return if (c.isDark) {
                Brush.verticalGradient(listOf(Color(0xFF3E475E), Color(0xFF2B3144)))
            } else {
                Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD5DEEC)))
            }
        }

    val MenuBarFill: Brush
        @Composable get() {
            val c = LocalOpalColors.current
            return Brush.verticalGradient(listOf(c.glassMenuBarTop, c.glassMenuBarBottom))
        }
}

/**
 * Authentic macOS Liquid Frosted Glass (液态毛玻璃) optical surface modifier:
 * combines exterior-only diffused halo shadow (masked via ClipOp.Difference so zero shadow
 * ever bleeds inside translucent glass), frosted fill, convex top specular sheen, and refractive rim.
 */
@Composable
fun Modifier.liquidGlassSurface(
    shape: Shape,
    fill: Brush = OpalPalette.LiquidDockFill,
    rim: Brush = OpalPalette.LiquidGlassRim,
    elevation: Dp = 18.dp,
    borderWidth: Dp = 1.dp,
    effectAlphaScale: Float = 1f,
): Modifier {
    val c = LocalOpalColors.current
    val effScale = effectAlphaScale.coerceIn(0f, 1f)
    val specularTop = c.glassSpecularTop.copy(alpha = (c.glassSpecularTop.alpha * effScale).coerceIn(0f, 1f))
    val shadowCol = c.glassShadowColor
    val base = this
        .drawBehind {
            if (elevation > 0.dp && effScale > 0.001f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                val innerPath = Path().apply { addOutline(outline) }
                val elevPx = elevation.toPx().coerceAtMost(26.dp.toPx())
                clipPath(innerPath, clipOp = ClipOp.Difference) {
                    val layers = 4
                    for (i in 1..layers) {
                        val t = i.toFloat() / layers.toFloat()
                        val spread = elevPx * 0.24f * t
                        val yOff = elevPx * 0.26f * t
                        val alpha = shadowCol.alpha * effScale * (0.44f * (1f - t * 0.72f))
                        val outerOutline = shape.createOutline(
                            Size(size.width + spread * 2f, size.height + spread * 2f),
                            layoutDirection,
                            this@drawBehind,
                        )
                        translate(left = -spread, top = yOff - spread * 0.65f) {
                            drawOutline(
                                outline = outerOutline,
                                color = shadowCol.copy(alpha = alpha),
                            )
                        }
                    }
                }
            }
        }
        .clip(shape)
        .background(fill)
        .drawWithContent {
            // Subtle liquid glass optical sheen across the upper convex arc
            if (effScale > 0.001f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to specularTop,
                            0.38f to specularTop.copy(alpha = specularTop.alpha * 0.22f),
                            0.65f to Color.Transparent,
                            1.0f to Color.Transparent,
                        ),
                    ),
                )
            }
            drawContent()
        }
    return if (borderWidth > 0.dp && effScale > 0.001f) {
        base.border(borderWidth, rim, shape)
    } else {
        base
    }
}

/**
 * Single-gesture unified desktop context-menu modifier (zero race between right-click and tap!):
 * - Physical Mouse Right-Click (`BUTTON_SECONDARY`) fires [onContextAction] immediately with
 *   exact root-canvas pixel coordinates and drains the gesture until release so [onPrimaryTap] NEVER fires.
 * - Touchscreen Long-Press fires [onContextAction] ("长按等于右键") with exact root-canvas coordinates.
 * - Left-Click / Single Tap fires [onPrimaryTap] cleanly; dragging beyond touchSlop yields to scrolling.
 */
@Composable
fun Modifier.onTapOrContextAction(
    vararg keys: Any?,
    onPrimaryTap: () -> Unit = {},
    onContextAction: (Offset) -> Unit,
): Modifier {
    val rootOriginRef = remember { mutableStateOf(Offset.Zero) }
    val curPrimary by rememberUpdatedState(onPrimaryTap)
    val curContext by rememberUpdatedState(onContextAction)

    return this
        .onGloballyPositioned { coords ->
            rootOriginRef.value = coords.positionInRoot()
        }
        .pointerInput(keys.contentHashCode()) {
            val touchSlop = viewConfiguration.touchSlop
            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downPos = down.position
                val buttons = currentEvent.buttons

                // 1. Mouse right-click: invoke context menu immediately at exact cursor root position
                if (buttons.isSecondaryPressed) {
                    down.consume()
                    curContext(rootOriginRef.value + downPos)
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.all { !it.pressed }) break
                    }
                    return@awaitEachGesture
                }

                // 2. Ignore tertiary / side buttons for primary tap
                if (buttons.isTertiaryPressed || buttons.isBackPressed || buttons.isForwardPressed) {
                    return@awaitEachGesture
                }

                // 3. Primary click / finger touch: wait for release within slop or long-press timeout
                var lastPos = downPos
                val releasedCleanly = withTimeoutOrNull(longPressTimeout) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        lastPos = change.position
                        val movedDist = (change.position - downPos).getDistance()
                        if (movedDist > touchSlop) {
                            return@withTimeoutOrNull false
                        }
                        // Only treat as cancelled by a parent scroll container if a movement event was consumed;
                        // ignore parent backdrop tap-shields that consume the stationary initial down event.
                        if (change.isConsumed && change.position != change.previousPosition) {
                            return@withTimeoutOrNull false
                        }
                        if (!change.pressed) {
                            if (change.isConsumed) return@withTimeoutOrNull false
                            change.consume()
                            return@withTimeoutOrNull true
                        }
                    }
                }

                if (releasedCleanly == true) {
                    curPrimary()
                } else if (releasedCleanly == null) {
                    // Timed out while still holding inside touchSlop -> Long-Press == Right-Click!
                    val held = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (held != null && held.pressed && (lastPos - downPos).getDistance() <= touchSlop) {
                        held.consume()
                        curContext(rootOriginRef.value + lastPos)
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.all { !it.pressed }) break
                        }
                    }
                }
            }
        }
}

@Composable
fun OpalText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = OpalPalette.TextPrimary,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            lineHeight = lineHeight,
            textAlign = textAlign,
        ),
    )
}

@Composable
fun OpalGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    fillColor: Color = OpalPalette.GlassSurface,
    borderColor: Color = OpalPalette.BorderSubtle,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(fillColor)
            .border(0.9.dp, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun OpalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = OpalPalette.AccentBlue,
    filled: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(if (compact) 10.dp else 13.dp)
    val bg = when {
        !enabled -> OpalPalette.ChipFill
        filled -> accent
        else -> accent.copy(alpha = if (OpalPalette.isDark) 0.16f else 0.12f)
    }
    val borderCol = when {
        !enabled -> OpalPalette.BorderHairline
        filled -> Color.White.copy(alpha = 0.24f)
        else -> accent.copy(alpha = if (OpalPalette.isDark) 0.36f else 0.28f)
    }
    val textCol = when {
        !enabled -> OpalPalette.TextMuted
        filled -> Color.White
        else -> accent
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(0.8.dp, borderCol, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 7.dp else 10.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            color = textCol,
            fontSize = if (compact) 12.sp else 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun OpalTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    val colors = LocalOpalColors.current
    val shape = RoundedCornerShape(11.dp)
    var fieldFocused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (fieldFocused) {
                OpalImeFocusTracker.setLocalTextFieldFocused(false)
            }
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        modifier = Modifier.onFocusChanged { st ->
            fieldFocused = st.isFocused
            OpalImeFocusTracker.setLocalTextFieldFocused(st.isFocused)
        },
        textStyle = TextStyle(
            color = colors.textPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
        ),
        cursorBrush = SolidColor(colors.accentBlue),
        decorationBox = { inner ->
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(colors.inputBg)
                    .border(0.8.dp, colors.borderSubtle, shape)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    OpalText(text = placeholder, color = colors.textMuted, fontSize = 13.sp, maxLines = 1)
                }
                inner()
            }
        },
    )
}

@Composable
fun OpalSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            OpalText(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                OpalText(text = subtitle, fontSize = 12.sp, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(25.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accentEmerald else colors.switchTrackOff)
                .padding(2.5.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
fun OpalSegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.segmentedTrack)
            .border(0.8.dp, colors.borderHairline, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val pillShape = RoundedCornerShape(9.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(pillShape)
                    .background(if (selected) colors.segmentedSelected else Color.Transparent)
                    .border(
                        width = if (selected) 0.75.dp else 0.dp,
                        color = if (selected) colors.segmentedSelectedBorder else Color.Transparent,
                        shape = pillShape,
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                OpalText(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Smooth macOS-style stepless continuous glass slider supporting both tap-to-jump and fluid dragging.
 */
@Composable
fun OpalGlassSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val density = LocalDensity.current
    val curOnChange by rememberUpdatedState(onValueChange)
    val curOnFinished by rememberUpdatedState(onValueChangeFinished)
    var trackWidthPx by remember { mutableIntStateOf(1) }

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumbSizeDp = 20.dp
    val thumbSizePx = with(density) { thumbSizeDp.toPx() }
    val usablePx = (trackWidthPx - thumbSizePx).coerceAtLeast(1f)

    fun updateFromPx(xPx: Float) {
        val norm = ((xPx - thumbSizePx * 0.5f) / usablePx).coerceIn(0f, 1f)
        curOnChange(valueRange.start + norm * span)
    }

    Box(
        modifier = modifier
            .height(30.dp)
            .onSizeChanged { trackWidthPx = it.width.coerceAtLeast(1) }
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    updateFromPx(offset.x)
                    curOnFinished?.invoke()
                }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragEnd = { curOnFinished?.invoke() },
                    onDragCancel = { curOnFinished?.invoke() },
                ) { change, _ ->
                    change.consume()
                    updateFromPx(change.position.x)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Track background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(colors.segmentedTrack)
                .border(0.6.dp, colors.borderHairline, CircleShape),
        )
        // Active filled portion
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(colors.accentBlue),
        )
        // Glossy macOS circular white thumb
        Box(
            modifier = Modifier
                .offset { IntOffset((fraction * usablePx).roundToInt(), 0) }
                .size(thumbSizeDp)
                .shadow(5.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .border(0.8.dp, Color(0x300F172A), CircleShape),
        )
    }
}

@Composable
fun OpalStatusBadge(label: String, color: Color) {
    val isDark = OpalPalette.isDark
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (isDark) 0.15f else 0.12f))
            .border(0.8.dp, color.copy(alpha = if (isDark) 0.36f else 0.28f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.5.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.5.dp))
        OpalText(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

enum class TrafficLightType { CLOSE, MINIMIZE, ZOOM }

@Composable
fun MacTrafficLightButton(
    type: TrafficLightType,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val inactive = OpalPalette.TrafficInactive
    val fill = when {
        !focused -> inactive
        type == TrafficLightType.CLOSE -> OpalPalette.TrafficRed
        type == TrafficLightType.MINIMIZE -> OpalPalette.TrafficYellow
        else -> OpalPalette.TrafficGreen
    }
    val stroke = when {
        !focused -> OpalPalette.BorderHairline
        type == TrafficLightType.CLOSE -> OpalPalette.TrafficRedBorder
        type == TrafficLightType.MINIMIZE -> OpalPalette.TrafficYellowBorder
        else -> OpalPalette.TrafficGreenBorder
    }

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.5.dp)
                .clip(CircleShape)
                .background(fill)
                .border(0.75.dp, stroke, CircleShape),
        )
    }
}

enum class ChromeGlyph {
    BACK,
    MINIMIZE,
    MAXIMIZE,
    RESTORE,
    CLOSE,
    ORIENTATION,
    APPS_GRID,
    EXPOSE,
    CONSOLE,
    SEARCH,
    RELOAD,
    HOME_DESKTOP,
    TILE_LEFT,
    TILE_RIGHT,
    CONTROL_CENTER,
    WIFI,
    WIFI_OFF,
    KEYBOARD,
    EXTERNAL_MONITOR,
    LOCK,
    SCREEN_OFF,
    MIC,
    CAMERA,
    CAMERA_FLIP,
    HOME_HOUSE,
    EXIT_FULLSCREEN,
    CHEVRON_UP,
    CHEVRON_DOWN,
}

/**
 * Compact capsule icon button shared by top-center floating control pills (Fullscreen overlay & PeerCast bar).
 */
@Composable
fun OpalTopPillIconButton(
    glyph: ChromeGlyph,
    contentDescription: String,
    tint: Color = OpalPalette.TextPrimary,
    active: Boolean = false,
    activeColor: Color = OpalPalette.AccentBlue,
    onClick: () -> Unit,
) {
    val effectiveTint = if (active) activeColor else tint
    val bg = if (active) {
        activeColor.copy(alpha = if (OpalPalette.isDark) 0.24f else 0.16f)
    } else {
        OpalPalette.ChipFill
    }
    val borderColor = if (active) {
        activeColor.copy(alpha = if (OpalPalette.isDark) 0.48f else 0.36f)
    } else {
        Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 26.dp)
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, borderColor, CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(glyph = glyph, tint = effectiveTint, size = 13.5.dp)
    }
}

/**
 * Matching-height capsule text (or optional leading icon + text) chip for domain-specific top-pill actions
 * whose semantics are clearer with concise wording than an icon alone.
 */
@Composable
fun OpalTopPillChip(
    label: String,
    glyph: ChromeGlyph? = null,
    tint: Color = OpalPalette.TextPrimary,
    active: Boolean = false,
    activeColor: Color = OpalPalette.AccentBlue,
    filled: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = if (danger) OpalPalette.AccentDanger else activeColor
    val bg = when {
        filled -> accent
        danger -> OpalPalette.AccentDanger.copy(alpha = if (OpalPalette.isDark) 0.20f else 0.12f)
        active -> accent.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.14f)
        else -> OpalPalette.ChipFill
    }
    val borderColor = when {
        filled -> Color.White.copy(alpha = 0.22f)
        danger -> OpalPalette.AccentDanger.copy(alpha = if (OpalPalette.isDark) 0.42f else 0.30f)
        active -> accent.copy(alpha = if (OpalPalette.isDark) 0.42f else 0.30f)
        else -> Color.Transparent
    }
    val contentTint = when {
        filled -> Color.White
        danger -> OpalPalette.AccentDanger
        else -> tint
    }
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (glyph != null) 8.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (glyph != null) {
            OpalGlyphIcon(glyph = glyph, tint = contentTint, size = 12.dp)
        }
        OpalText(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentTint,
            maxLines = 1,
        )
    }
}

@Composable
fun OpalTopPillDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(14.dp)
            .background(OpalPalette.DividerColor),
    )
}

@Composable
fun OpalGlyphButton(
    glyph: ChromeGlyph,
    tint: Color = OpalPalette.TextPrimary,
    size: Dp = 28.dp,
    dangerHover: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        dangerHover -> OpalPalette.AccentDanger.copy(alpha = 0.14f)
        active -> OpalPalette.ChipActiveFill
        else -> OpalPalette.ChipFill
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(glyph = glyph, tint = tint, size = size * 0.50f)
    }
}

@Composable
fun OpalGlyphIcon(
    glyph: ChromeGlyph,
    tint: Color = OpalPalette.TextPrimary,
    size: Dp = 15.dp,
) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 1.6.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        when (glyph) {
            ChromeGlyph.BACK -> {
                drawLine(tint, Offset(w * 0.68f, h * 0.16f), Offset(w * 0.28f, h * 0.5f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.5f), Offset(w * 0.68f, h * 0.84f), sw, StrokeCap.Round)
            }
            ChromeGlyph.MINIMIZE -> {
                drawLine(tint, Offset(w * 0.18f, h * 0.52f), Offset(w * 0.82f, h * 0.52f), sw, StrokeCap.Round)
            }
            ChromeGlyph.MAXIMIZE -> {
                drawRoundRect(tint, Offset(w * 0.16f, h * 0.16f), Size(w * 0.68f, h * 0.68f), CornerRadius(2.dp.toPx()), Stroke(sw))
            }
            ChromeGlyph.RESTORE -> {
                drawRoundRect(tint, Offset(w * 0.28f, h * 0.14f), Size(w * 0.56f, h * 0.56f), CornerRadius(1.8.dp.toPx()), Stroke(sw * 0.88f))
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.30f), Size(w * 0.56f, h * 0.56f), CornerRadius(1.8.dp.toPx()), Stroke(sw))
            }
            ChromeGlyph.CLOSE -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), sw, StrokeCap.Round)
            }
            ChromeGlyph.ORIENTATION -> {
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.12f), Size(w * 0.52f, h * 0.76f), CornerRadius(2.dp.toPx()), Stroke(sw))
            }
            ChromeGlyph.APPS_GRID -> {
                val r = w * 0.105f
                for (gx in listOf(0.20f, 0.50f, 0.80f)) {
                    for (gy in listOf(0.20f, 0.50f, 0.80f)) {
                        drawCircle(tint, radius = r, center = Offset(w * gx, h * gy))
                    }
                }
            }
            ChromeGlyph.EXPOSE -> {
                drawRoundRect(tint, Offset(w * 0.08f, h * 0.14f), Size(w * 0.37f, h * 0.32f), CornerRadius(1.6.dp.toPx()), Stroke(sw * 0.9f))
                drawRoundRect(tint, Offset(w * 0.55f, h * 0.14f), Size(w * 0.37f, h * 0.32f), CornerRadius(1.6.dp.toPx()), Stroke(sw * 0.9f))
                drawRoundRect(tint, Offset(w * 0.20f, h * 0.56f), Size(w * 0.60f, h * 0.30f), CornerRadius(1.6.dp.toPx()), Stroke(sw * 0.9f))
            }
            ChromeGlyph.CONSOLE, ChromeGlyph.CONTROL_CENTER -> {
                drawLine(tint, Offset(w * 0.12f, h * 0.30f), Offset(w * 0.88f, h * 0.30f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.12f, h * 0.70f), Offset(w * 0.88f, h * 0.70f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.34f, h * 0.30f))
                drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.66f, h * 0.70f))
            }
            ChromeGlyph.SEARCH -> {
                drawCircle(tint, radius = w * 0.30f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(sw))
                drawLine(tint, Offset(w * 0.65f, h * 0.65f), Offset(w * 0.86f, h * 0.86f), sw * 1.08f, StrokeCap.Round)
            }
            ChromeGlyph.RELOAD -> {
                drawArc(
                    color = tint,
                    startAngle = -55f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.66f, h * 0.14f), Offset(w * 0.82f, h * 0.30f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.82f, h * 0.12f), Offset(w * 0.82f, h * 0.30f), sw, StrokeCap.Round)
            }
            ChromeGlyph.HOME_DESKTOP -> {
                drawCircle(tint, radius = w * 0.32f, center = Offset(w * 0.50f, h * 0.50f), style = Stroke(sw))
            }
            ChromeGlyph.TILE_LEFT -> {
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.16f), Size(w * 0.80f, h * 0.68f), CornerRadius(2.dp.toPx()), Stroke(sw * 0.9f))
                drawRoundRect(tint.copy(alpha = 0.75f), Offset(w * 0.15f, h * 0.22f), Size(w * 0.34f, h * 0.56f), CornerRadius(1.2.dp.toPx()))
            }
            ChromeGlyph.TILE_RIGHT -> {
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.16f), Size(w * 0.80f, h * 0.68f), CornerRadius(2.dp.toPx()), Stroke(sw * 0.9f))
                drawRoundRect(tint.copy(alpha = 0.75f), Offset(w * 0.51f, h * 0.22f), Size(w * 0.34f, h * 0.56f), CornerRadius(1.2.dp.toPx()))
            }
            ChromeGlyph.WIFI -> {
                drawArc(tint, 225f, 90f, false, Offset(w * 0.08f, h * 0.18f), Size(w * 0.84f, h * 0.84f), style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(tint, 225f, 90f, false, Offset(w * 0.24f, h * 0.38f), Size(w * 0.52f, h * 0.52f), style = Stroke(sw, cap = StrokeCap.Round))
                drawCircle(tint, radius = w * 0.085f, center = Offset(w * 0.50f, h * 0.78f))
            }
            ChromeGlyph.WIFI_OFF -> {
                val muted = tint.copy(alpha = 0.55f)
                drawArc(muted, 225f, 90f, false, Offset(w * 0.08f, h * 0.18f), Size(w * 0.84f, h * 0.84f), style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(muted, 225f, 90f, false, Offset(w * 0.24f, h * 0.38f), Size(w * 0.52f, h * 0.52f), style = Stroke(sw, cap = StrokeCap.Round))
                drawCircle(muted, radius = w * 0.085f, center = Offset(w * 0.50f, h * 0.78f))
                drawLine(tint, Offset(w * 0.16f, h * 0.16f), Offset(w * 0.84f, h * 0.84f), sw, StrokeCap.Round)
            }
            ChromeGlyph.KEYBOARD -> {
                drawRoundRect(tint, Offset(w * 0.08f, h * 0.22f), Size(w * 0.84f, h * 0.56f), CornerRadius(2.dp.toPx()), Stroke(sw * 0.9f))
                drawLine(tint, Offset(w * 0.28f, h * 0.62f), Offset(w * 0.72f, h * 0.62f), sw, StrokeCap.Round)
                for (gx in listOf(0.26f, 0.42f, 0.58f, 0.74f)) {
                    drawCircle(tint, radius = w * 0.045f, center = Offset(w * gx, h * 0.42f))
                }
            }
            ChromeGlyph.EXTERNAL_MONITOR -> {
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.14f), Size(w * 0.80f, h * 0.56f), CornerRadius(2.dp.toPx()), Stroke(sw))
                drawLine(tint, Offset(w * 0.34f, h * 0.84f), Offset(w * 0.66f, h * 0.84f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.84f), sw, StrokeCap.Round)
            }
            ChromeGlyph.LOCK -> {
                // Padlock body
                drawRoundRect(
                    tint,
                    Offset(w * 0.20f, h * 0.46f),
                    Size(w * 0.60f, h * 0.42f),
                    CornerRadius(2.2.dp.toPx()),
                    Stroke(sw * 0.95f),
                )
                // Shackle arch
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.31f, h * 0.14f),
                    size = Size(w * 0.38f, h * 0.38f),
                    style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.31f, h * 0.33f), Offset(w * 0.31f, h * 0.46f), sw * 0.95f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.69f, h * 0.33f), Offset(w * 0.69f, h * 0.46f), sw * 0.95f, StrokeCap.Round)
            }
            ChromeGlyph.SCREEN_OFF -> {
                // Universal power/sleep icon (open top arc + vertical stem)
                drawArc(
                    color = tint,
                    startAngle = -52f,
                    sweepAngle = 284f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.18f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.50f, h * 0.10f), Offset(w * 0.50f, h * 0.46f), sw, StrokeCap.Round)
            }
            ChromeGlyph.MIC -> {
                drawRoundRect(
                    tint,
                    Offset(w * 0.34f, h * 0.10f),
                    Size(w * 0.32f, h * 0.46f),
                    CornerRadius(w * 0.16f),
                    Stroke(sw),
                )
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.20f, h * 0.24f),
                    size = Size(w * 0.60f, h * 0.46f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.88f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.68f, h * 0.88f), sw, StrokeCap.Round)
            }
            ChromeGlyph.CAMERA -> {
                drawRoundRect(
                    tint,
                    Offset(w * 0.08f, h * 0.24f),
                    Size(w * 0.54f, h * 0.52f),
                    CornerRadius(2.2.dp.toPx()),
                    Stroke(sw),
                )
                val lens = Path().apply {
                    moveTo(w * 0.64f, h * 0.40f)
                    lineTo(w * 0.92f, h * 0.27f)
                    lineTo(w * 0.92f, h * 0.73f)
                    lineTo(w * 0.64f, h * 0.60f)
                    close()
                }
                drawPath(lens, tint, style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ChromeGlyph.CAMERA_FLIP -> {
                drawArc(
                    color = tint,
                    startAngle = 210f,
                    sweepAngle = 125f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.68f, h * 0.28f), Offset(w * 0.82f, h * 0.34f), sw * 0.95f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.82f, h * 0.19f), Offset(w * 0.82f, h * 0.34f), sw * 0.95f, StrokeCap.Round)
                drawArc(
                    color = tint,
                    startAngle = 30f,
                    sweepAngle = 125f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.32f, h * 0.72f), Offset(w * 0.18f, h * 0.66f), sw * 0.95f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.18f, h * 0.81f), Offset(w * 0.18f, h * 0.66f), sw * 0.95f, StrokeCap.Round)
            }
            ChromeGlyph.HOME_HOUSE -> {
                val roof = Path().apply {
                    moveTo(w * 0.12f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.15f)
                    lineTo(w * 0.88f, h * 0.48f)
                }
                drawPath(roof, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val walls = Path().apply {
                    moveTo(w * 0.24f, h * 0.44f)
                    lineTo(w * 0.24f, h * 0.84f)
                    lineTo(w * 0.76f, h * 0.84f)
                    lineTo(w * 0.76f, h * 0.44f)
                }
                drawPath(walls, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ChromeGlyph.EXIT_FULLSCREEN -> {
                val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val corners = listOf(
                    Triple(Offset(w * 0.14f, h * 0.38f), Offset(w * 0.38f, h * 0.38f), Offset(w * 0.38f, h * 0.14f)),
                    Triple(Offset(w * 0.86f, h * 0.38f), Offset(w * 0.62f, h * 0.38f), Offset(w * 0.62f, h * 0.14f)),
                    Triple(Offset(w * 0.14f, h * 0.62f), Offset(w * 0.38f, h * 0.62f), Offset(w * 0.38f, h * 0.86f)),
                    Triple(Offset(w * 0.86f, h * 0.62f), Offset(w * 0.62f, h * 0.62f), Offset(w * 0.62f, h * 0.86f)),
                )
                corners.forEach { (a, b, c) ->
                    val p = Path().apply {
                        moveTo(a.x, a.y)
                        lineTo(b.x, b.y)
                        lineTo(c.x, c.y)
                    }
                    drawPath(p, tint, style = stroke)
                }
            }
            ChromeGlyph.CHEVRON_UP -> {
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.64f)
                    lineTo(w * 0.50f, h * 0.34f)
                    lineTo(w * 0.80f, h * 0.64f)
                }
                drawPath(p, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ChromeGlyph.CHEVRON_DOWN -> {
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.36f)
                    lineTo(w * 0.50f, h * 0.66f)
                    lineTo(w * 0.80f, h * 0.36f)
                }
                drawPath(p, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * Crisp miniature geometric preview diagram for each pro desktop multi-window tiling preset.
 */
@Composable
fun TilingLayoutPreviewIcon(
    layout: WindowTilingLayout,
    tint: Color = OpalPalette.TextPrimary,
    size: Dp = 26.dp,
) {
    Canvas(modifier = Modifier.size(width = size * 1.45f, height = size)) {
        val w = this.size.width
        val h = this.size.height
        val pad = 1.5.dp.toPx()
        val gap = 2.dp.toPx()
        val cr = CornerRadius(2.4.dp.toPx(), 2.4.dp.toPx())
        val stroke = Stroke(width = 1.25.dp.toPx())
        val fill = tint.copy(alpha = 0.20f)
        val innerW = w - pad * 2f
        val innerH = h - pad * 2f

        fun drawPane(x: Float, y: Float, pw: Float, ph: Float) {
            drawRoundRect(fill, Offset(x, y), Size(pw, ph), cr)
            drawRoundRect(tint, Offset(x, y), Size(pw, ph), cr, style = stroke)
        }

        when (layout) {
            WindowTilingLayout.SPLIT_2_COLS -> {
                val cw = (innerW - gap) / 2f
                drawPane(pad, pad, cw, innerH)
                drawPane(pad + cw + gap, pad, cw, innerH)
            }
            WindowTilingLayout.SPLIT_2_MAIN_SIDE -> {
                val mainW = (innerW - gap) * 0.64f
                val sideW = innerW - gap - mainW
                drawPane(pad, pad, mainW, innerH)
                drawPane(pad + mainW + gap, pad, sideW, innerH)
            }
            WindowTilingLayout.SPLIT_3_COLS -> {
                val cw = (innerW - gap * 2f) / 3f
                for (i in 0..2) drawPane(pad + i * (cw + gap), pad, cw, innerH)
            }
            WindowTilingLayout.SPLIT_3_MAIN_STACK -> {
                val mainW = (innerW - gap) * 0.56f
                val sideW = innerW - gap - mainW
                val sh = (innerH - gap) / 2f
                drawPane(pad, pad, mainW, innerH)
                drawPane(pad + mainW + gap, pad, sideW, sh)
                drawPane(pad + mainW + gap, pad + sh + gap, sideW, sh)
            }
            WindowTilingLayout.SPLIT_4_COLS -> {
                val cw = (innerW - gap * 3f) / 4f
                for (i in 0..3) drawPane(pad + i * (cw + gap), pad, cw, innerH)
            }
            WindowTilingLayout.SPLIT_4_GRID -> {
                val cw = (innerW - gap) / 2f
                val ch = (innerH - gap) / 2f
                drawPane(pad, pad, cw, ch)
                drawPane(pad + cw + gap, pad, cw, ch)
                drawPane(pad, pad + ch + gap, cw, ch)
                drawPane(pad + cw + gap, pad + ch + gap, cw, ch)
            }
            WindowTilingLayout.SPLIT_5_COLS -> {
                val g = gap * 0.72f
                val cw = (innerW - g * 4f) / 5f
                for (i in 0..4) drawPane(pad + i * (cw + g), pad, cw, innerH)
            }
            WindowTilingLayout.SPLIT_6_GRID -> {
                val cw = (innerW - gap * 2f) / 3f
                val ch = (innerH - gap) / 2f
                for (r in 0..1) {
                    for (c in 0..2) {
                        drawPane(pad + c * (cw + gap), pad + r * (ch + gap), cw, ch)
                    }
                }
            }
            WindowTilingLayout.SPLIT_8_GRID -> {
                val g = gap * 0.78f
                val cw = (innerW - g * 3f) / 4f
                val ch = (innerH - gap) / 2f
                for (r in 0..1) {
                    for (c in 0..3) {
                        drawPane(pad + c * (cw + g), pad + r * (ch + gap), cw, ch)
                    }
                }
            }
            WindowTilingLayout.CASCADE -> {
                val pw = innerW * 0.62f
                val ph = innerH * 0.64f
                drawPane(pad, pad, pw, ph)
                drawPane(pad + innerW * 0.19f, pad + innerH * 0.18f, pw, ph)
                drawPane(pad + innerW * 0.38f, pad + innerH * 0.36f, pw, ph)
            }
        }
    }
}

/**
 * Curated macOS-inspired atmospheric desktop wallpapers engineered to shine gently behind
 * translucent Liquid Frosted Glass (液态毛玻璃) in both Light (浅色) and Dark (深色) modes.
 */
@Composable
fun OpalAmbientBackdrop(
    modifier: Modifier = Modifier,
    wallpaperPreset: Int = 0,
    suspendBackdrop: Boolean = false,
    consoleScrim: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = OpalPalette.isDark
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    var loadedWallpaper by remember(customWallpaperPath) {
        mutableStateOf(WallpaperStorage.getCachedWallpaperFast(customWallpaperPath))
    }
    LaunchedEffect(customWallpaperPath, suspendBackdrop) {
        if (suspendBackdrop || customWallpaperPath.isBlank()) return@LaunchedEffect
        val fast = WallpaperStorage.getCachedWallpaperFast(customWallpaperPath)
        if (fast != null) {
            loadedWallpaper = fast
        } else {
            loadedWallpaper = withContext(Dispatchers.IO) {
                WallpaperStorage.loadCachedWallpaper(customWallpaperPath)
            }
        }
    }
    val customBitmap = if (suspendBackdrop) null else loadedWallpaper

    val stops = if (isDark) {
        when (wallpaperPreset) {
            1 -> listOf(Color(0xFF0E1324), Color(0xFF212950), Color(0xFF422D5C), Color(0xFF1A1628))
            2 -> listOf(Color(0xFF08141E), Color(0xFF0E2C42), Color(0xFF154760), Color(0xFF0B1722))
            3 -> listOf(Color(0xFF111318), Color(0xFF1C2029), Color(0xFF171A22), Color(0xFF0D0F14))
            else -> listOf(Color(0xFF0A1324), Color(0xFF152D4B), Color(0xFF1F3C64), Color(0xFF12192A))
        }
    } else {
        when (wallpaperPreset) {
            1 -> listOf(Color(0xFFF2E2F8), Color(0xFFFBDCE8), Color(0xFFFDE9DA), Color(0xFFF2EBF9)) // 晨曦薄暮
            2 -> listOf(Color(0xFFD2F0FA), Color(0xFFBFE7F4), Color(0xFFD8F3EF), Color(0xFFECF8FC)) // 冰川清露
            3 -> listOf(Color(0xFFE9EEF6), Color(0xFFDCE3EE), Color(0xFFE7ECF4), Color(0xFFF3F6FA)) // 素白霜华
            else -> listOf(Color(0xFFDCE9FC), Color(0xFFC8DEFB), Color(0xFFE2E8FC), Color(0xFFEEF3FD)) // 晴空云雾
        }
    }
    val glowA = if (isDark) {
        when (wallpaperPreset) {
            1 -> Color(0x52E07A5F)
            2 -> Color(0x4A38BDF8)
            3 -> Color(0x2B94A3B8)
            else -> Color(0x4D2A9D8F)
        }
    } else {
        when (wallpaperPreset) {
            1 -> Color(0x7AF472B6)
            2 -> Color(0x7538BDF8)
            3 -> Color(0x5294A3B8)
            else -> Color(0x7A60A5FA)
        }
    }
    val glowB = if (isDark) {
        when (wallpaperPreset) {
            1 -> Color(0x46818CF8)
            2 -> Color(0x3D2DD4BF)
            3 -> Color(0x2860A5FA)
            else -> Color(0x486366F1)
        }
    } else {
        when (wallpaperPreset) {
            1 -> Color(0x6EFB923C)
            2 -> Color(0x6834D399)
            3 -> Color(0x4860A5FA)
            else -> Color(0x64A78BFA)
        }
    }
    val glowC = if (isDark) {
        Color(0x2838BDF8)
    } else {
        when (wallpaperPreset) {
            1 -> Color(0x52C084FC)
            2 -> Color(0x522DD4BF)
            3 -> Color(0x3ACBD5E1)
            else -> Color(0x5638BDF8)
        }
    }

    val rootModifier = when {
        suspendBackdrop -> modifier.background(OpalPalette.WindowCanvasBg)
        customBitmap != null -> modifier
        else -> modifier.background(Brush.linearGradient(colors = stops))
    }

    // IMPORTANT: Keep a single unconditional Box + content() call site across suspendBackdrop /
    // customBitmap transitions so entering or exiting 100% fullscreen NEVER unmounts/remounts
    // OpalCompositorWorkspace or destroys active WindowSurfaceHost TextureViews!
    Box(modifier = rootModifier) {
        if (!suspendBackdrop) {
            if (customBitmap != null) {
                val wallpaperModifier = if (consoleScrim && Build.VERSION.SDK_INT >= 31) {
                    Modifier.fillMaxSize().blur(18.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Image(
                    bitmap = customBitmap,
                    contentDescription = "Desktop Wallpaper",
                    contentScale = ContentScale.Crop,
                    modifier = wallpaperModifier,
                )
                // Subtle atmospheric veil so frosted liquid glass & text maintain crisp readability over any photo
                val veilColor = when {
                    consoleScrim && isDark -> Color(0xBD0A101D)
                    consoleScrim -> Color(0xCCEEF3FA)
                    isDark -> Color(0x38060910)
                    else -> Color(0x16FFFFFF)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(veilColor),
                )
            } else {
                val blurModifier = if (Build.VERSION.SDK_INT >= 31) {
                    Modifier.fillMaxSize().blur(26.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Canvas(modifier = blurModifier) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowA, Color.Transparent),
                            center = Offset(size.width * 0.20f, size.height * 0.22f),
                            radius = size.maxDimension * 0.56f,
                        ),
                        center = Offset(size.width * 0.20f, size.height * 0.22f),
                        radius = size.maxDimension * 0.56f,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowB, Color.Transparent),
                            center = Offset(size.width * 0.82f, size.height * 0.72f),
                            radius = size.maxDimension * 0.54f,
                        ),
                        center = Offset(size.width * 0.82f, size.height * 0.72f),
                        radius = size.maxDimension * 0.54f,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowC, Color.Transparent),
                            center = Offset(size.width * 0.52f, size.height * 0.88f),
                            radius = size.maxDimension * 0.42f,
                        ),
                        center = Offset(size.width * 0.52f, size.height * 0.88f),
                        radius = size.maxDimension * 0.42f,
                    )
                }
            }
        }
        content()
    }
}
