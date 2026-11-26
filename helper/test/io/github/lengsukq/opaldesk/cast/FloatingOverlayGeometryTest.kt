package io.github.lengsukq.opaldesk.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingOverlayGeometryTest {

    @Test
    fun landscapePhoneReproducingOpaldeskBAndC_doesNotThrow() {
        // Exact Redmi K30 Pro Zoom Edition landscape dimensions from Sentry OPALDESK-B & OPALDESK-C:
        // density = 2.75 (440dpi) => dp(340) = 935, while 1037 * 0.74 = 767 (< 935!)
        val portraitBounds = FloatingOverlayGeometry.computeWindowSize(
            screenW = 2313,
            screenH = 1037,
            density = 2.75f,
            isLandscape = false,
        )
        assertTrue("width must be positive and within screen", portraitBounds.width in 1..2313)
        assertTrue("height must clamp to max screen budget (767) without throwing", portraitBounds.height in 1..767)

        val landscapeBounds = FloatingOverlayGeometry.computeWindowSize(
            screenW = 2313,
            screenH = 1037,
            density = 2.75f,
            isLandscape = true,
        )
        assertTrue("landscape width within screen", landscapeBounds.width in 1..2313)
        assertTrue("landscape height within 64% budget", landscapeBounds.height in 1..664)
    }

    @Test
    fun portraitPhoneAndTabletBoundsRemainReadableAndSafe() {
        val phonePortrait = FloatingOverlayGeometry.computeWindowSize(
            screenW = 1080,
            screenH = 2400,
            density = 2.75f,
            isLandscape = false,
        )
        assertTrue(phonePortrait.width in 600..950)
        assertTrue(phonePortrait.height in 935..1776)

        val phoneToLandscape = FloatingOverlayGeometry.computeWindowSize(
            screenW = 1080,
            screenH = 2400,
            density = 2.75f,
            isLandscape = true,
        )
        assertTrue(phoneToLandscape.width in 800..994)
        assertTrue(phoneToLandscape.height in 500..1536)

        val tabletPortrait = FloatingOverlayGeometry.computeWindowSize(
            screenW = 2560,
            screenH = 1600,
            density = 2.0f,
            isLandscape = false,
        )
        assertEquals(780, tabletPortrait.width)
        assertEquals(1312, tabletPortrait.height)
    }

    @Test
    fun degenerateTinyScreenNeverThrowsOnSizeDragResizeOrBubble() {
        val tinyBounds = FloatingOverlayGeometry.computeWindowSize(
            screenW = 320,
            screenH = 240,
            density = 3.0f,
            isLandscape = false,
        )
        assertTrue(tinyBounds.width in 1..320)
        assertTrue(tinyBounds.height in 1..240)

        val resized = FloatingOverlayGeometry.clampResizeSize(
            targetW = 500,
            targetH = 500,
            screenW = 320,
            screenH = 240,
            density = 3.0f,
        )
        assertTrue(resized.width in 1..320)
        assertTrue(resized.height in 1..240)

        val dragged = FloatingOverlayGeometry.clampDragPosition(
            targetX = 999,
            targetY = 999,
            windowW = tinyBounds.width,
            screenW = 320,
            screenH = 240,
            density = 3.0f,
        )
        assertTrue(dragged.x >= -tinyBounds.width / 2)
        assertTrue(dragged.y >= 48)

        val visible = FloatingOverlayGeometry.clampVisiblePosition(
            x = -50,
            y = 500,
            windowW = tinyBounds.width,
            windowH = tinyBounds.height,
            screenW = 320,
            screenH = 240,
            density = 3.0f,
        )
        assertTrue(visible.x >= 24)
        assertTrue(visible.y >= 72)

        val bubble = FloatingOverlayGeometry.clampBubblePosition(
            targetX = 500,
            targetY = 500,
            screenW = 100,
            screenH = 100,
            density = 3.0f,
        )
        assertEquals(0, bubble.x)
        assertEquals(72, bubble.y)
    }
}
