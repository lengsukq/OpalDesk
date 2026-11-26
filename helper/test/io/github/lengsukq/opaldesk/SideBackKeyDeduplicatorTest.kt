package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideBackKeyDeduplicatorTest {

    @Test
    fun suppressesCompanionBackKeyDownAndUpAfterMouseSideButton() {
        val dedup = CompositorGeometry.SideBackKeyDeduplicator()

        // Mouse BUTTON_BACK press claims back #1 at t=1000ms.
        assertTrue(dedup.tryClaimSideButtonBack(1_000L))

        // Companion Android/BT KEYCODE_BACK DOWN+UP arrives shortly after -> suppressed!
        assertTrue(dedup.shouldSuppressDuplicateBackKey(CompositorGeometry.KEYCODE_BACK, CompositorGeometry.KEY_ACTION_DOWN, 1_018L))
        // Even if matching KEY_UP arrives slightly past the window, it is still suppressed cleanly.
        assertTrue(dedup.shouldSuppressDuplicateBackKey(CompositorGeometry.KEYCODE_BACK, CompositorGeometry.KEY_ACTION_UP, 1_260L))

        // Non-back keys (e.g. KEYCODE_ESCAPE=111) are never suppressed even within the window.
        assertFalse(dedup.shouldSuppressDuplicateBackKey(111, CompositorGeometry.KEY_ACTION_DOWN, 1_020L))

        // Normal Back press after dedup window passes through normally.
        assertFalse(dedup.shouldSuppressDuplicateBackKey(CompositorGeometry.KEYCODE_BACK, CompositorGeometry.KEY_ACTION_DOWN, 1_300L))
        assertFalse(dedup.shouldSuppressDuplicateBackKey(CompositorGeometry.KEYCODE_BACK, CompositorGeometry.KEY_ACTION_UP, 1_340L))
    }

    @Test
    fun suppressesImmediateButtonBackAfterForwardedBackKeyButAllowsFastDoubleClick() {
        val dedup = CompositorGeometry.SideBackKeyDeduplicator()

        // BT combo mouse emits HID Consumer Back key first at t=2000ms.
        assertFalse(dedup.shouldSuppressDuplicateBackKey(CompositorGeometry.KEYCODE_BACK, CompositorGeometry.KEY_ACTION_DOWN, 2_000L))
        dedup.recordBackKeyForwarded(2_000L)

        // Companion BUTTON_BACK 12ms later is rejected (prevents double-back).
        assertFalse(dedup.tryClaimSideButtonBack(2_012L))

        // Intentional fast human click 120ms later is allowed cleanly.
        assertTrue(dedup.tryClaimSideButtonBack(2_120L))
    }
}
