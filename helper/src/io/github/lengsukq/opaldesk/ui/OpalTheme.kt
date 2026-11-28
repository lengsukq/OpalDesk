package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.lengsukq.opaldesk.compositor.DockAppearancePolicy
import io.github.lengsukq.opaldesk.prefs.AppPrefs

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
    glassDockTop = Color(0x8EFFFFFF),
    glassDockBottom = Color(0x5EEAF1FD),
    glassRimTop = Color(0xFCFFFFFF),
    glassRimMid = Color(0x9CFFFFFF),
    glassRimBottom = Color(0x5A94A3B8),
    glassSpecularTop = Color(0x96FFFFFF),
    glassShadowColor = Color(0x2E1E3A5F),
    glassPopoverTop = Color(0xCCFFFFFF),
    glassPopoverBottom = Color(0xB4EBF2FC),
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
    glassDockTop = Color(0x762B344B),
    glassDockBottom = Color(0x82141926),
    glassRimTop = Color(0x70FFFFFF),
    glassRimMid = Color(0x32FFFFFF),
    glassRimBottom = Color(0x1CFFFFFF),
    glassSpecularTop = Color(0x2EFFFFFF),
    glassShadowColor = Color(0x99000000),
    glassPopoverTop = Color(0xC222293B),
    glassPopoverBottom = Color(0xCC121724),
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
        val topAlpha = DockAppearancePolicy.scaleGlassAlpha(c.glassDockTop.alpha, opacity)
        val bottomAlpha = DockAppearancePolicy.scaleGlassAlpha(c.glassDockBottom.alpha, opacity)
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
        val scale = DockAppearancePolicy.rimAndEffectScale(opacity)
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
            return if (c.isDark) {
                Brush.linearGradient(
                    0.0f to Color.White.copy(alpha = 0.42f),
                    0.34f to Color.White.copy(alpha = 0.14f),
                    0.68f to Color.White.copy(alpha = 0.04f),
                    1.0f to Color.White.copy(alpha = 0.20f),
                )
            } else {
                Brush.linearGradient(
                    0.0f to Color.White.copy(alpha = 0.94f),
                    0.34f to Color.White.copy(alpha = 0.46f),
                    0.68f to Color(0x2694A3B8),
                    1.0f to Color.White.copy(alpha = 0.54f),
                )
            }
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
