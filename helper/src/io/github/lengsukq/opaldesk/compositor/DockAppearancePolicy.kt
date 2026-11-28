package io.github.lengsukq.opaldesk.compositor

import io.github.lengsukq.opaldesk.prefs.DockGlassStyle
import io.github.lengsukq.opaldesk.prefs.DockSizePreset
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Persistent user customization state for the bottom floating Liquid Glass Dock.
 */
data class DockStyleConfig(
    val visible: Boolean = true,
    val opacity: Float = DockAppearancePolicy.DEFAULT_OPACITY,
    val scale: Float = DockAppearancePolicy.DEFAULT_SCALE,
)

/**
 * Pure dp layout metrics for [DexMacTaskbar] computed from user scale & viewport class.
 */
data class DockLayoutMetrics(
    val cornerRadiusDp: Float,
    val leadBtnDp: Float,
    val searchBtnDp: Float,
    val navBtnDp: Float,
    val appTileDp: Float,
    val appIconDp: Float,
    val horizontalPaddingDp: Float,
    val verticalPaddingDp: Float,
    val itemSpacingDp: Float,
    val bottomMarginDp: Float,
    val dividerHeightDp: Float,
    val maxWidthDp: Float,
    val estimatedTotalHeightDp: Float,
)

/**
 * Pure JVM-testable policy & math for Dock visibility, background opacity, size scaling,
 * and dynamic bottom workspace inset calculation (zero Android framework dependencies).
 */
object DockAppearancePolicy {
    const val MIN_OPACITY = 0.0f
    const val MAX_OPACITY = 1.0f
    const val DEFAULT_OPACITY = 0.65f

    const val MIN_SCALE = 0.75f
    const val MAX_SCALE = 1.35f
    const val DEFAULT_SCALE = 1.00f

    /** Reserved bottom inset when Dock is hidden, auto-hidden, or in Floating Orb mode. */
    const val HIDDEN_DOCK_INSET_DP = 0f

    const val MIN_ORB_OPACITY = 0.25f
    const val MAX_ORB_OPACITY = 1.0f
    const val DEFAULT_ORB_OPACITY = 0.85f
    private const val HIGH_OPACITY_TARGET_ALPHA = 0.94f

    fun clampOpacity(raw: Float): Float =
        if (raw.isFinite()) raw.coerceIn(MIN_OPACITY, MAX_OPACITY) else DEFAULT_OPACITY

    fun clampScale(raw: Float): Float =
        if (raw.isFinite()) raw.coerceIn(MIN_SCALE, MAX_SCALE) else DEFAULT_SCALE

    fun sanitize(config: DockStyleConfig): DockStyleConfig = DockStyleConfig(
        visible = config.visible,
        opacity = clampOpacity(config.opacity),
        scale = clampScale(config.scale),
    )

    /**
     * Maps user opacity (`0.0..1.0`, default `0.65`) onto a theme glass color's alpha:
     * - `0.0`: `0f` (crystal clear — icons float cleanly over wallpaper)
     * - `0.65` (default): exact `baseAlpha` (preserves default Liquid Glass look)
     * - `1.0`: boosts smoothly up to `max(baseAlpha, 0.94f)` for high-contrast frosted readability
     */
    fun scaleGlassAlpha(baseAlpha: Float, opacity: Float): Float {
        val base = if (baseAlpha.isFinite()) baseAlpha.coerceIn(0f, 1f) else 0f
        val op = clampOpacity(opacity)
        if (op <= 0.001f) return 0f
        if (op <= DEFAULT_OPACITY) {
            return (base * (op / DEFAULT_OPACITY)).coerceIn(0f, 1f)
        }
        val t = ((op - DEFAULT_OPACITY) / (MAX_OPACITY - DEFAULT_OPACITY)).coerceIn(0f, 1f)
        val topAlpha = max(base, HIGH_OPACITY_TARGET_ALPHA)
        return (base + (topAlpha - base) * t).coerceIn(0f, 1f)
    }

    /**
     * Scales border rim, top specular sheen, and exterior shadow down to `0f` as opacity approaches `0%`,
     * preventing a ghostly rectangular outline when the user selects a fully transparent Dock background.
     */
    fun rimAndEffectScale(opacity: Float): Float {
        val op = clampOpacity(opacity)
        if (op <= 0.001f) return 0f
        return (op / DEFAULT_OPACITY).coerceIn(0f, 1f)
    }

    fun computeMetrics(scale: Float, isCompactWidth: Boolean): DockLayoutMetrics {
        val s = clampScale(scale)
        val baseCorner = if (isCompactWidth) 19f else 22f
        val baseLead = if (isCompactWidth) 34f else 40f
        val baseSearch = 38f
        val baseNav = if (isCompactWidth) 30f else 34f
        val baseTile = if (isCompactWidth) 40f else 46f
        val baseIcon = if (isCompactWidth) 28f else 32f
        val baseHPad = if (isCompactWidth) 7f else 10f
        val baseVPad = if (isCompactWidth) 5f else 6f
        val baseSpacing = if (isCompactWidth) 3.5f else 5f
        val bottomMargin = if (isCompactWidth) 8f else 10f
        val baseDivider = 26f

        val tile = (baseTile * s).coerceAtLeast(30f)
        val vPad = (baseVPad * s).coerceAtLeast(3.5f)
        val estHeight = tile + vPad * 2f + bottomMargin

        return DockLayoutMetrics(
            cornerRadiusDp = (baseCorner * s).coerceIn(14f, 30f),
            leadBtnDp = (baseLead * s).coerceAtLeast(26f),
            searchBtnDp = (baseSearch * s).coerceAtLeast(28f),
            navBtnDp = (baseNav * s).coerceAtLeast(24f),
            appTileDp = tile,
            appIconDp = (baseIcon * s).coerceAtLeast(20f),
            horizontalPaddingDp = (baseHPad * s).coerceAtLeast(5f),
            verticalPaddingDp = vPad,
            itemSpacingDp = (baseSpacing * s).coerceAtLeast(2.5f),
            bottomMarginDp = bottomMargin,
            dividerHeightDp = (baseDivider * s).coerceAtLeast(18f),
            maxWidthDp = 780f * max(1f, s),
            estimatedTotalHeightDp = estHeight,
        )
    }

    fun sizePresetScale(preset: DockSizePreset): Float = when (preset) {
        DockSizePreset.COMPACT -> 0.85f
        DockSizePreset.STANDARD -> 1.00f
        DockSizePreset.LARGE -> 1.18f
    }

    fun effectiveGlassOpacity(baseOpacity: Float, glassStyle: DockGlassStyle): Float {
        val op = clampOpacity(baseOpacity)
        return when (glassStyle) {
            DockGlassStyle.CLEAR -> (op * 0.45f).coerceIn(0.14f, 0.38f)
            DockGlassStyle.BALANCED -> op
            DockGlassStyle.FROSTED -> max(op, 0.88f).coerceIn(0.84f, 0.98f)
        }
    }

    fun glassRimBoost(glassStyle: DockGlassStyle): Float = when (glassStyle) {
        DockGlassStyle.CLEAR -> 0.62f
        DockGlassStyle.BALANCED -> 1.00f
        DockGlassStyle.FROSTED -> 1.25f
    }

    /**
     * Returns true only when the standard Dock is pinned visible at the bottom edge and should
     * reserve bottom workspace height. Floating Orb Mode, hidden Dock, and auto-hidden Dock all
     * return false so both desktop widgets/icons and app windows reclaim the full bottom area.
     */
    fun reservesBottomSpace(
        visible: Boolean,
        autoHide: Boolean = false,
        orbMode: Boolean = false,
    ): Boolean = visible && !autoHide && !orbMode

    /**
     * Effective bottom workspace inset reserved for the Dock during tiling, edge snapping,
     * window maximization, desktop widget/shortcut layout, and overlay positioning.
     * Always returns `0f` when Floating Orb Mode, hidden Dock, or auto-hide Dock is active.
     */
    fun computeEffectiveDockInsetDp(
        visible: Boolean,
        scale: Float,
        isCompactWidth: Boolean,
        sizePreset: DockSizePreset = DockSizePreset.STANDARD,
        floatingStyle: Boolean = true,
        autoHide: Boolean = false,
        orbMode: Boolean = false,
    ): Float {
        if (!reservesBottomSpace(visible = visible, autoHide = autoHide, orbMode = orbMode)) {
            return HIDDEN_DOCK_INSET_DP
        }
        val combinedScale = clampScale(scale * sizePresetScale(sizePreset))
        val metrics = computeMetrics(combinedScale, isCompactWidth)
        val marginAdj = if (floatingStyle) 0f else metrics.bottomMarginDp
        return (metrics.estimatedTotalHeightDp - marginAdj + 2f).coerceAtLeast(44f)
    }

    fun clampOrbOpacity(raw: Float): Float =
        if (raw.isFinite()) raw.coerceIn(MIN_ORB_OPACITY, MAX_ORB_OPACITY) else DEFAULT_ORB_OPACITY

    /**
     * Computes the target overall alpha for the Floating Assistive Orb, honoring the user's
     * configured opacity (`25%..100%`) and applying a subtle non-muddy idle dim when resting.
     */
    fun computeOrbTargetAlpha(orbOpacity: Float, idleDimEnabled: Boolean, isIdleDimmed: Boolean): Float {
        val active = clampOrbOpacity(orbOpacity)
        if (!idleDimEnabled || !isIdleDimmed) return active
        return (active * 0.62f).coerceIn(0.20f, active)
    }

    /**
     * Maps user orb opacity (`0.25..1.0`) to a balanced glass refraction & rim intensity factor
     * so the orb stays luminous and translucent at low opacity without turning muddy grey.
     */
    fun computeOrbGlassIntensity(orbOpacity: Float): Float {
        val op = clampOrbOpacity(orbOpacity)
        return ((op - MIN_ORB_OPACITY) / (MAX_ORB_OPACITY - MIN_ORB_OPACITY)).coerceIn(0f, 1f)
    }

    fun formatOpacityLabel(opacity: Float): String {
        val op = clampOpacity(opacity)
        val pct = (op * 100f).roundToInt().coerceIn(0, 100)
        return if (pct == 0) "全透明" else "$pct%"
    }

    fun formatScaleLabel(scale: Float): String {
        val pct = (clampScale(scale) * 100f).roundToInt()
        return "$pct%"
    }

    fun isDefaultOpacity(opacity: Float): Boolean =
        kotlin.math.abs(clampOpacity(opacity) - DEFAULT_OPACITY) < 0.015f

    fun isDefaultScale(scale: Float): Boolean =
        kotlin.math.abs(clampScale(scale) - DEFAULT_SCALE) < 0.015f

    fun defaultInsetDp(): Float = CompositorGeometry.DOCK_INSET_DP

    /**
     * Returns true when any floating workspace popover or context menu (such as the App Drawer,
     * Control Center, Notification Center, utility popovers, or right-click menus) is open and
     * needs a full-screen outside-click dismiss scrim above live app windows and desktop widgets.
     */
    fun shouldShowPopoverOutsideDismissScrim(
        showAppDrawer: Boolean,
        showControlCenter: Boolean,
        showNotificationCenter: Boolean,
        hasFullscreenWindow: Boolean,
        showDpiPopover: Boolean,
        showImePopover: Boolean,
        showShortcutsPopover: Boolean,
        hasContextMenu: Boolean,
    ): Boolean =
        showAppDrawer ||
            showControlCenter ||
            (showNotificationCenter && !hasFullscreenWindow) ||
            showDpiPopover ||
            showImePopover ||
            showShortcutsPopover ||
            hasContextMenu

    /**
     * Returns true when the Floating Assistive Orb's expanded Dock bar should install a full-screen
     * outside-click dismiss scrim to auto-collapse back into the orb. Suppressed while any higher
     * popover/overlay is open so the popover's own outside-click handler remains in charge.
     */
    fun shouldShowOrbDockOutsideDismissScrim(
        orbMode: Boolean,
        orbExpanded: Boolean,
        anyPopoverOpen: Boolean,
    ): Boolean = orbMode && orbExpanded && !anyPopoverOpen
}
