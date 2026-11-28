package io.github.lengsukq.opaldesk.compositor

import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.ResizeEdge
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.ui.SpotlightLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowResizeBridgePolicyTest {

    @Test
    fun noSettleDelayWhenDimensionsUnchangedOrInvalid() {
        assertEquals(0L, ResizeBridgePolicy.computeMinSettleDelayMs(874, 1548, 874, 1548, 1.0f))
        assertEquals(0L, ResizeBridgePolicy.computeMinSettleDelayMs(0, 1548, 874, 1250, 1.0f))
        assertEquals(0L, ResizeBridgePolicy.computeMinSettleDelayMs(874, 1548, -1, 1250, 1.0f))
    }

    @Test
    fun shrinkingUsesLongerSettleWindowToCoverSurfaceFlingerProjectionAndShellTransition() {
        val shrinkMs = ResizeBridgePolicy.computeMinSettleDelayMs(
            oldW = 898,
            oldH = 2030,
            newW = 874,
            newH = 1548,
            transitionAnimScale = 1.0f,
        )
        val expandMs = ResizeBridgePolicy.computeMinSettleDelayMs(
            oldW = 874,
            oldH = 1250,
            newW = 1154,
            newH = 1852,
            transitionAnimScale = 1.0f,
        )
        assertEquals(ResizeBridgePolicy.SHRINK_MIN_SETTLE_MS, shrinkMs)
        assertEquals(ResizeBridgePolicy.EXPAND_MIN_SETTLE_MS, expandMs)
        assertTrue("Shrink settle window must cover SurfaceFlinger/ShellTransition", shrinkMs > expandMs)
    }

    @Test
    fun disabledSystemTransitionAnimationsUseShortBlastSyncSettleWindow() {
        val noAnimShrink = ResizeBridgePolicy.computeMinSettleDelayMs(1194, 2090, 898, 2030, 0f)
        val noAnimExpand = ResizeBridgePolicy.computeMinSettleDelayMs(874, 1250, 1154, 1852, 0f)
        assertEquals(ResizeBridgePolicy.NO_ANIM_MIN_SETTLE_MS, noAnimShrink)
        assertEquals(ResizeBridgePolicy.NO_ANIM_MIN_SETTLE_MS, noAnimExpand)
    }

    @Test
    fun extremeAnimationScaleIsBoundedSafely() {
        val slowScale = ResizeBridgePolicy.computeMinSettleDelayMs(1200, 2000, 800, 1200, 5.0f)
        val fastScale = ResizeBridgePolicy.computeMinSettleDelayMs(1200, 2000, 800, 1200, 0.25f)
        assertTrue(slowScale <= ResizeBridgePolicy.MAX_MIN_SETTLE_MS)
        assertTrue(fastScale >= ResizeBridgePolicy.NO_ANIM_MIN_SETTLE_MS)
    }

    @Test
    fun computeResizeAtOddDensityStillProducesEvenAlignedMinDimensions() {
        // Xiaomi 14 Pro: 1440x3200 @ 560dpi (density = 3.5f -> 250dp = 875px odd)
        val base = WindowRect(100, 120, 1294, 2210)
        val shrunk = CompositorGeometry.computeResize(
            base = base,
            edge = ResizeEdge.SE,
            dx = -1000,
            dy = -2000,
            canvasW = 1440,
            canvasH = 3200,
            density = 3.5f,
        )
        assertEquals("Width must be even-aligned to match daemon clampWindowFrame", 0, shrunk.width and 1)
        assertEquals("Height must be even-aligned to match daemon clampWindowFrame", 0, shrunk.height and 1)
        assertEquals(874, shrunk.width)
        assertEquals(840, shrunk.height)
    }

    @Test
    fun spotlightTopOffsetClearsPhoneCameraCutoutAndPreservesMonitorPlacement() {
        // Xiaomi 14 Pro portrait (411dp x 914dp, punch-hole cutout = 168px / 3.5 = 48dp)
        val phoneWithCutout = SpotlightLayoutPolicy.computeTopOffsetDp(
            maxHeightDp = 914f,
            isCompact = true,
            safeTopInsetDp = 48f,
        )
        assertEquals(72f, phoneWithCutout, 0.01f)
        assertTrue("Search bar must sit comfortably below 48dp camera cutout", phoneWithCutout >= 48f + 24f)

        // Compact phone without reported cutout still clears DeX top bar + top camera zone
        val phoneNoCutout = SpotlightLayoutPolicy.computeTopOffsetDp(
            maxHeightDp = 844f,
            isCompact = true,
            safeTopInsetDp = 0f,
        )
        assertEquals(SpotlightLayoutPolicy.COMPACT_MIN_TOP_OFFSET_DP, phoneNoCutout, 0.01f)

        // External 1080p monitor keeps classic centered-upper placement
        val monitor = SpotlightLayoutPolicy.computeTopOffsetDp(
            maxHeightDp = 1080f,
            isCompact = false,
            safeTopInsetDp = 0f,
        )
        assertEquals(SpotlightLayoutPolicy.WIDE_MAX_TOP_OFFSET_DP, monitor, 0.01f)

        // Landscape phone stays compact vertically while respecting safe floor
        val landscapePhone = SpotlightLayoutPolicy.computeTopOffsetDp(
            maxHeightDp = 411f,
            isCompact = false,
            safeTopInsetDp = 0f,
        )
        assertEquals(SpotlightLayoutPolicy.WIDE_MIN_TOP_OFFSET_DP, landscapePhone, 0.01f)
    }
}
