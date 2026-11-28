package io.github.lengsukq.opaldesk.compositor

import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.FullscreenTrafficLightGlyphMode
import io.github.lengsukq.opaldesk.ui.TrafficLightType
import io.github.lengsukq.opaldesk.ui.WindowTrafficLightPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTrafficLightPolicyTest {

    @Test
    fun orderedTypesStrictlyFollowCloseMinimizeFullscreen() {
        assertEquals(
            listOf(
                TrafficLightType.CLOSE,
                TrafficLightType.MINIMIZE,
                TrafficLightType.FULLSCREEN,
            ),
            WindowTrafficLightPolicy.ORDERED_TYPES,
        )
    }

    @Test
    fun winActionsMapRedCloseYellowMinimizeGreenToggleFullscreen() {
        assertEquals(BridgeProtocol.WinAction.CLOSE, WindowTrafficLightPolicy.winActionFor(TrafficLightType.CLOSE))
        assertEquals(BridgeProtocol.WinAction.MINIMIZE, WindowTrafficLightPolicy.winActionFor(TrafficLightType.MINIMIZE))
        assertEquals(BridgeProtocol.WinAction.TOGGLE_FULLSCREEN, WindowTrafficLightPolicy.winActionFor(TrafficLightType.FULLSCREEN))
    }

    @Test
    fun greenGlyphAndShrinkBridgeFollowFullscreenState() {
        assertEquals(
            FullscreenTrafficLightGlyphMode.ENTER_FULLSCREEN,
            WindowTrafficLightPolicy.greenGlyphMode(isFullscreen = false),
        )
        assertFalse(WindowTrafficLightPolicy.shouldArmShrinkOnFullscreenToggle(isFullscreen = false))

        assertEquals(
            FullscreenTrafficLightGlyphMode.EXIT_FULLSCREEN,
            WindowTrafficLightPolicy.greenGlyphMode(isFullscreen = true),
        )
        assertTrue(WindowTrafficLightPolicy.shouldArmShrinkOnFullscreenToggle(isFullscreen = true))
    }

    @Test
    fun fullscreenTopHoverRevealRespectsManualCollapseGuard() {
        assertFalse(WindowTrafficLightPolicy.shouldRevealFullscreenBarOnMouseHover(elapsedSinceManualCollapseMs = 0L))
        assertFalse(WindowTrafficLightPolicy.shouldRevealFullscreenBarOnMouseHover(elapsedSinceManualCollapseMs = 649L))
        assertTrue(WindowTrafficLightPolicy.shouldRevealFullscreenBarOnMouseHover(elapsedSinceManualCollapseMs = 650L))
        assertTrue(WindowTrafficLightPolicy.shouldRevealFullscreenBarOnMouseHover(elapsedSinceManualCollapseMs = 2_000L))
    }
}
