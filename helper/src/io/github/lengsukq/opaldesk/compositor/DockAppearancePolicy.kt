package io.github.lengsukq.opaldesk.compositor

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

    const val HIDDEN_DOCK_INSET_DP = 10f
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

    /**
     * Effective bottom workspace inset reserved for the Dock during tiling, edge snapping,
     * desktop shortcut layout, and App Drawer positioning.
     */
    fun computeEffectiveDockInsetDp(
        visible: Boolean,
        scale: Float,
        isCompactWidth: Boolean,
    ): Float {
        if (!visible) return HIDDEN_DOCK_INSET_DP
        val metrics = computeMetrics(scale, isCompactWidth)
        return (metrics.estimatedTotalHeightDp + 2f).coerceAtLeast(48f)
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
}
