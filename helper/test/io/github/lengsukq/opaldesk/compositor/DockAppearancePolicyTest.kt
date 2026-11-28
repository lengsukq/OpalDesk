package io.github.lengsukq.opaldesk.compositor

import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockAppearancePolicyTest {

    @Test
    fun sanitizeClampsOutOfRangeAndNonFiniteValues() {
        val sanitized = DockAppearancePolicy.sanitize(
            DockStyleConfig(visible = false, opacity = Float.NaN, scale = Float.POSITIVE_INFINITY),
        )
        assertFalse(sanitized.visible)
        assertEquals(DockAppearancePolicy.DEFAULT_OPACITY, sanitized.opacity, 0.0001f)
        assertEquals(DockAppearancePolicy.DEFAULT_SCALE, sanitized.scale, 0.0001f)

        assertEquals(DockAppearancePolicy.MIN_OPACITY, DockAppearancePolicy.clampOpacity(-0.4f), 0.0001f)
        assertEquals(DockAppearancePolicy.MAX_OPACITY, DockAppearancePolicy.clampOpacity(1.8f), 0.0001f)
        assertEquals(DockAppearancePolicy.MIN_SCALE, DockAppearancePolicy.clampScale(0.2f), 0.0001f)
        assertEquals(DockAppearancePolicy.MAX_SCALE, DockAppearancePolicy.clampScale(2.5f), 0.0001f)
    }

    @Test
    fun scaleGlassAlphaPreservesDefaultAndMapsEndpointsSmoothly() {
        val baseTopAlpha = 0x9C / 255f // ~0.612f

        // 0% opacity -> completely clear glass plate and zero rim/shadow
        assertEquals(0f, DockAppearancePolicy.scaleGlassAlpha(baseTopAlpha, 0f), 0.0001f)
        assertEquals(0f, DockAppearancePolicy.rimAndEffectScale(0f), 0.0001f)

        // Default 65% opacity -> exact original Liquid Glass alpha and full rim/shadow
        assertEquals(
            baseTopAlpha,
            DockAppearancePolicy.scaleGlassAlpha(baseTopAlpha, DockAppearancePolicy.DEFAULT_OPACITY),
            0.0001f,
        )
        assertEquals(1f, DockAppearancePolicy.rimAndEffectScale(DockAppearancePolicy.DEFAULT_OPACITY), 0.0001f)

        // 100% opacity -> high-contrast frosted glass (~0.94f)
        val highAlpha = DockAppearancePolicy.scaleGlassAlpha(baseTopAlpha, 1.0f)
        assertTrue("Expected boosted alpha >= 0.93f, got $highAlpha", highAlpha >= 0.93f)

        // Strictly monotonic across 0.0 -> 0.3 -> 0.65 -> 0.85 -> 1.0
        val samples = listOf(0f, 0.3f, 0.65f, 0.85f, 1.0f).map {
            DockAppearancePolicy.scaleGlassAlpha(baseTopAlpha, it)
        }
        for (i in 0 until samples.lastIndex) {
            assertTrue("Expected monotonic increase at index $i: $samples", samples[i] < samples[i + 1])
        }
    }

    @Test
    fun computeMetricsScalesMonotonicallyWithSafeFloors() {
        val small = DockAppearancePolicy.computeMetrics(DockAppearancePolicy.MIN_SCALE, isCompactWidth = false)
        val normal = DockAppearancePolicy.computeMetrics(DockAppearancePolicy.DEFAULT_SCALE, isCompactWidth = false)
        val large = DockAppearancePolicy.computeMetrics(DockAppearancePolicy.MAX_SCALE, isCompactWidth = false)

        assertTrue(small.appTileDp < normal.appTileDp)
        assertTrue(normal.appTileDp < large.appTileDp)
        assertTrue(small.appIconDp < normal.appIconDp)
        assertTrue(normal.appIconDp < large.appIconDp)
        assertTrue(large.maxWidthDp > normal.maxWidthDp)

        val compactSmall = DockAppearancePolicy.computeMetrics(DockAppearancePolicy.MIN_SCALE, isCompactWidth = true)
        assertTrue(compactSmall.navBtnDp >= 24f)
        assertTrue(compactSmall.appIconDp >= 20f)
    }

    @Test
    fun hiddenAndScaledDockUpdatesEffectiveInsetAndReclaimsTilingArea() {
        val defaultInset = DockAppearancePolicy.computeEffectiveDockInsetDp(
            visible = true,
            scale = DockAppearancePolicy.DEFAULT_SCALE,
            isCompactWidth = false,
        )
        assertEquals(CompositorGeometry.DOCK_INSET_DP, defaultInset, 0.5f)

        val hiddenInset = DockAppearancePolicy.computeEffectiveDockInsetDp(
            visible = false,
            scale = DockAppearancePolicy.MAX_SCALE,
            isCompactWidth = false,
        )
        assertEquals(0f, hiddenInset, 0.01f)
        assertFalse(DockAppearancePolicy.reservesBottomSpace(visible = false, autoHide = false, orbMode = false))

        val autoHideInset = DockAppearancePolicy.computeEffectiveDockInsetDp(
            visible = true,
            scale = DockAppearancePolicy.MAX_SCALE,
            isCompactWidth = false,
            autoHide = true,
            orbMode = false,
        )
        assertEquals(0f, autoHideInset, 0.01f)
        assertFalse(DockAppearancePolicy.reservesBottomSpace(visible = true, autoHide = true, orbMode = false))

        val orbModeInset = DockAppearancePolicy.computeEffectiveDockInsetDp(
            visible = true,
            scale = DockAppearancePolicy.MAX_SCALE,
            isCompactWidth = false,
            autoHide = false,
            orbMode = true,
        )
        assertEquals(0f, orbModeInset, 0.01f)
        assertFalse(DockAppearancePolicy.reservesBottomSpace(visible = true, autoHide = false, orbMode = true))

        assertTrue(DockAppearancePolicy.reservesBottomSpace(visible = true, autoHide = false, orbMode = false))
        assertTrue(defaultInset > 0f)

        val largeInset = DockAppearancePolicy.computeEffectiveDockInsetDp(
            visible = true,
            scale = DockAppearancePolicy.MAX_SCALE,
            isCompactWidth = false,
        )
        assertTrue("Larger dock should reserve more inset ($largeInset vs $defaultInset)", largeInset > defaultInset)

        // Verify snapping & tiling reclaim bottom space when Dock is hidden
        val snapWithDock = CompositorGeometry.computeSnapPreviewRect(
            BridgeProtocol.Presentation.SNAP_LEFT, 1920, 1080, 2f, defaultInset,
        )
        val snapHiddenDock = CompositorGeometry.computeSnapPreviewRect(
            BridgeProtocol.Presentation.SNAP_LEFT, 1920, 1080, 2f, hiddenInset,
        )
        assertTrue(snapHiddenDock.bottom > snapWithDock.bottom + 80)

        val tiledWithDock = CompositorGeometry.computeTilingFrames(
            WindowTilingLayout.SPLIT_2_COLS, 2, 1920, 1080, 2f, defaultInset,
        )
        val tiledHiddenDock = CompositorGeometry.computeTilingFrames(
            WindowTilingLayout.SPLIT_2_COLS, 2, 1920, 1080, 2f, hiddenInset,
        )
        assertTrue(tiledHiddenDock[0].bottom > tiledWithDock[0].bottom + 80)
    }

    @Test
    fun orbOpacityAndIdleDimPolicyClampsAndScalesSmoothly() {
        assertEquals(DockAppearancePolicy.MIN_ORB_OPACITY, DockAppearancePolicy.clampOrbOpacity(0.05f), 0.0001f)
        assertEquals(DockAppearancePolicy.MAX_ORB_OPACITY, DockAppearancePolicy.clampOrbOpacity(1.5f), 0.0001f)
        assertEquals(DockAppearancePolicy.DEFAULT_ORB_OPACITY, DockAppearancePolicy.clampOrbOpacity(Float.NaN), 0.0001f)

        // Active state preserves exact clamped opacity
        assertEquals(0.25f, DockAppearancePolicy.computeOrbTargetAlpha(0.25f, idleDimEnabled = true, isIdleDimmed = false), 0.0001f)
        assertEquals(1.0f, DockAppearancePolicy.computeOrbTargetAlpha(1.0f, idleDimEnabled = true, isIdleDimmed = false), 0.0001f)

        // Idle dimming reduces alpha softly without dropping below floor
        val dimmedHigh = DockAppearancePolicy.computeOrbTargetAlpha(1.0f, idleDimEnabled = true, isIdleDimmed = true)
        assertTrue("Expected dimmed alpha < 1.0f and >= 0.5f, got $dimmedHigh", dimmedHigh in 0.5f..0.7f)
        val dimmedLow = DockAppearancePolicy.computeOrbTargetAlpha(0.25f, idleDimEnabled = true, isIdleDimmed = true)
        assertTrue("Expected low dimmed alpha in 0.20..0.25, got $dimmedLow", dimmedLow in 0.20f..0.25f)

        // Intensity maps [0.25..1.0] -> [0.0..1.0]
        assertEquals(0f, DockAppearancePolicy.computeOrbGlassIntensity(0.25f), 0.0001f)
        assertEquals(1f, DockAppearancePolicy.computeOrbGlassIntensity(1.0f), 0.0001f)
        assertEquals(0.5f, DockAppearancePolicy.computeOrbGlassIntensity(0.625f), 0.0001f)
    }

    @Test
    fun labelFormattingMatchesExpectedDisplayStrings() {
        assertEquals("全透明", DockAppearancePolicy.formatOpacityLabel(0f))
        assertEquals("65%", DockAppearancePolicy.formatOpacityLabel(0.65f))
        assertEquals("100%", DockAppearancePolicy.formatOpacityLabel(1f))
        assertEquals("75%", DockAppearancePolicy.formatScaleLabel(0.75f))
        assertEquals("100%", DockAppearancePolicy.formatScaleLabel(1.0f))
        assertEquals("135%", DockAppearancePolicy.formatScaleLabel(1.35f))
    }

    @Test
    fun popoverOutsideDismissScrimActivatesForAppDrawerAndFloatingPopovers() {
        assertFalse(
            DockAppearancePolicy.shouldShowPopoverOutsideDismissScrim(
                showAppDrawer = false,
                showControlCenter = false,
                showNotificationCenter = false,
                hasFullscreenWindow = false,
                showDpiPopover = false,
                showImePopover = false,
                showShortcutsPopover = false,
                hasContextMenu = false,
            ),
        )
        assertTrue(
            DockAppearancePolicy.shouldShowPopoverOutsideDismissScrim(
                showAppDrawer = true,
                showControlCenter = false,
                showNotificationCenter = false,
                hasFullscreenWindow = false,
                showDpiPopover = false,
                showImePopover = false,
                showShortcutsPopover = false,
                hasContextMenu = false,
            ),
        )
        assertTrue(
            DockAppearancePolicy.shouldShowPopoverOutsideDismissScrim(
                showAppDrawer = false,
                showControlCenter = true,
                showNotificationCenter = false,
                hasFullscreenWindow = true,
                showDpiPopover = false,
                showImePopover = false,
                showShortcutsPopover = false,
                hasContextMenu = false,
            ),
        )
        // Notification Center is hidden in true fullscreen, so it must not install a blocking scrim
        assertFalse(
            DockAppearancePolicy.shouldShowPopoverOutsideDismissScrim(
                showAppDrawer = false,
                showControlCenter = false,
                showNotificationCenter = true,
                hasFullscreenWindow = true,
                showDpiPopover = false,
                showImePopover = false,
                showShortcutsPopover = false,
                hasContextMenu = false,
            ),
        )
    }

    @Test
    fun orbDockOutsideDismissScrimOnlyActiveWhenOrbExpandedWithoutHigherPopover() {
        assertTrue(DockAppearancePolicy.shouldShowOrbDockOutsideDismissScrim(orbMode = true, orbExpanded = true, anyPopoverOpen = false))
        assertFalse(DockAppearancePolicy.shouldShowOrbDockOutsideDismissScrim(orbMode = true, orbExpanded = false, anyPopoverOpen = false))
        assertFalse(DockAppearancePolicy.shouldShowOrbDockOutsideDismissScrim(orbMode = false, orbExpanded = true, anyPopoverOpen = false))
        assertFalse(DockAppearancePolicy.shouldShowOrbDockOutsideDismissScrim(orbMode = true, orbExpanded = true, anyPopoverOpen = true))
    }
}
