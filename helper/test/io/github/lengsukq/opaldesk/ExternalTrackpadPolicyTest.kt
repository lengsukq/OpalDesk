package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.external.DirectExternalTrackpadSink
import io.github.lengsukq.opaldesk.external.ExternalDesktopMode
import io.github.lengsukq.opaldesk.external.ExternalDesktopSession
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.ExternalTrackpadPolicy
import io.github.lengsukq.opaldesk.external.TrackpadTargetMode
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalTrackpadPolicyTest {

    @Before
    fun setUp() {
        ExternalTrackpadBridge.resetForTest()
    }

    @After
    fun tearDown() {
        ExternalTrackpadBridge.resetForTest()
    }

    @Test
    fun isPhysicalExternalDisplayFiltersInternalPrivateAndOffDisplays() {
        val pres = ExternalDisplayPolicy.FLAG_PRESENTATION
        val privPres = pres or ExternalDisplayPolicy.FLAG_PRIVATE

        assertTrue(ExternalDisplayPolicy.isPhysicalExternalDisplay(2, true, "HDMI Screen", pres, state = 2))
        assertFalse(ExternalDisplayPolicy.isPhysicalExternalDisplay(0, true, "Built-in", pres, state = 2))
        assertFalse(ExternalDisplayPolicy.isPhysicalExternalDisplay(2, false, "HDMI Screen", pres, state = 2))
        assertFalse(
            ExternalDisplayPolicy.isPhysicalExternalDisplay(
                2,
                true,
                "HDMI Screen",
                pres,
                state = ExternalDisplayPolicy.STATE_OFF,
            ),
        )
        assertFalse(ExternalDisplayPolicy.isPhysicalExternalDisplay(5, true, "OpalWin-1", pres, state = 2))
        assertFalse(ExternalDisplayPolicy.isPhysicalExternalDisplay(6, true, "OpalDesk-Remote", pres, state = 2))
        assertFalse(ExternalDisplayPolicy.isPhysicalExternalDisplay(3, true, "Private Overlay", privPres, state = 2))
        assertFalse(ExternalDisplayPolicy.isPhysicalExternalDisplay(3, true, "Secondary", flags = 0, state = 2))
    }

    @Test
    fun isRemoteCastDisplayDistinguishesRemoteVirtualDisplayFromPhysicalMonitor() {
        val activeCast = RemoteStreamStatus(connected = true, desktopDisplayId = 9)
        assertTrue(ExternalDisplayPolicy.isRemoteCastDisplay(9, "OpalDesk-Remote", RemoteStreamStatus()))
        assertTrue(ExternalDisplayPolicy.isRemoteCastDisplay(9, "VirtualDisplay", activeCast))
        assertFalse(ExternalDisplayPolicy.isRemoteCastDisplay(2, "USB-C Monitor", activeCast))
        assertFalse(ExternalDisplayPolicy.isRemoteCastDisplay(0, "OpalDesk-Remote", activeCast))
    }

    @Test
    fun resolveTrackpadTargetSupportsDirectExtendedPresentationAndRemoteCast() {
        val idleRemote = RemoteStreamStatus()
        val activeCast = RemoteStreamStatus(connected = true, desktopDisplayId = 12)
        val standaloneOnly = RemoteStreamStatus(connected = true, desktopDisplayId = -1)

        val presentationSession = ExternalDesktopSession(
            mode = ExternalDesktopMode.PRESENTATION,
            displayId = 4,
            displayName = "Dell 2K",
            widthPx = 2560,
            heightPx = 1440,
            refreshRateHz = 100,
            isRemoteCastDisplay = false,
        )
        val directPhysicalSession = ExternalDesktopSession(
            mode = ExternalDesktopMode.DIRECT_EXTENDED,
            displayId = 2,
            displayName = "LG 4K",
            widthPx = 3840,
            heightPx = 2160,
            refreshRateHz = 60,
            isRemoteCastDisplay = false,
        )
        val directRemoteSession = ExternalDesktopSession(
            mode = ExternalDesktopMode.DIRECT_EXTENDED,
            displayId = 12,
            displayName = "OpalDesk-Remote",
            widthPx = 1920,
            heightPx = 1080,
            refreshRateHz = 120,
            isRemoteCastDisplay = true,
        )

        assertEquals(
            TrackpadTargetMode.PHYSICAL_PRESENTATION,
            ExternalTrackpadPolicy.resolveTrackpadTarget(idleRemote, presentationSession),
        )
        assertEquals(
            TrackpadTargetMode.PHYSICAL_DIRECT_EXTENDED,
            ExternalTrackpadPolicy.resolveTrackpadTarget(idleRemote, directPhysicalSession),
        )
        assertEquals(
            TrackpadTargetMode.REMOTE_CAST,
            ExternalTrackpadPolicy.resolveTrackpadTarget(activeCast, directRemoteSession),
        )
        assertEquals(
            TrackpadTargetMode.REMOTE_CAST,
            ExternalTrackpadPolicy.resolveTrackpadTarget(activeCast, ExternalDesktopSession()),
        )
        assertEquals(
            TrackpadTargetMode.NONE,
            ExternalTrackpadPolicy.resolveTrackpadTarget(standaloneOnly, ExternalDesktopSession()),
        )
        assertEquals("LG 4K · 3840×2160 @ 60Hz", directPhysicalSession.summary)
        assertTrue(directPhysicalSession.isPhysicalActive)
        assertFalse(directRemoteSession.isPhysicalActive)
    }

    @Test
    fun autoLaunchAndExternalControlsRespectModeAndPreference() {
        val directPhysical = ExternalDesktopSession(
            mode = ExternalDesktopMode.DIRECT_EXTENDED,
            displayId = 2,
            displayName = "HDMI",
            widthPx = 1920,
            heightPx = 1080,
            refreshRateHz = 60,
            isRemoteCastDisplay = false,
        )
        val directRemote = directPhysical.copy(displayId = 10, displayName = "OpalDesk-Remote", isRemoteCastDisplay = true)
        val presentation = directPhysical.copy(mode = ExternalDesktopMode.PRESENTATION)
        val activeCast = RemoteStreamStatus(connected = true, desktopDisplayId = 10)

        assertTrue(ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(directPhysical, autoShowEnabled = true, alreadyVisible = false))
        assertFalse(ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(directPhysical, autoShowEnabled = false, alreadyVisible = false))
        assertFalse(ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(directPhysical, autoShowEnabled = true, alreadyVisible = true))
        assertFalse(ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(presentation, autoShowEnabled = true, alreadyVisible = false))
        assertFalse(ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(directRemote, autoShowEnabled = true, alreadyVisible = false))

        assertTrue(ExternalTrackpadPolicy.canControlPhoneTrackpad(isExternalMonitor = true, RemoteStreamStatus(), directPhysical))
        assertTrue(ExternalTrackpadPolicy.canControlPhoneTrackpad(isExternalMonitor = true, RemoteStreamStatus(), presentation))
        assertTrue(ExternalTrackpadPolicy.canControlPhoneTrackpad(isExternalMonitor = true, activeCast, directRemote))
        assertFalse(ExternalTrackpadPolicy.canControlPhoneTrackpad(isExternalMonitor = false, RemoteStreamStatus(), directPhysical))
        assertFalse(ExternalTrackpadPolicy.canControlPhoneTrackpad(isExternalMonitor = true, RemoteStreamStatus(), ExternalDesktopSession()))
    }

    @Test
    fun clampCursorPositionStaysWithinDisplayBounds() {
        assertEquals(0f to 0f, ExternalTrackpadPolicy.clampCursorPosition(-25f, -10f, 1920, 1080))
        assertEquals(1919f to 1079f, ExternalTrackpadPolicy.clampCursorPosition(2400f, 1500f, 1920, 1080))
        assertEquals(512.5f to 300f, ExternalTrackpadPolicy.clampCursorPosition(512.5f, 300f, 1920, 1080))
    }

    @Test
    fun bridgeEnforcesOwnerTokenAndCloserLifecycle() {
        val ownerA = Any()
        val ownerB = Any()
        val sessionA = ExternalDesktopSession(ExternalDesktopMode.DIRECT_EXTENDED, 2, "HDMI", 1920, 1080, 60)

        ExternalTrackpadBridge.publishSession(ownerA, sessionA)
        assertEquals(sessionA, ExternalTrackpadBridge.sessionFlow.value)

        // Stale non-owner cannot wipe active session
        ExternalTrackpadBridge.clearSessionIfOwner(ownerB)
        assertEquals(sessionA, ExternalTrackpadBridge.sessionFlow.value)

        ExternalTrackpadBridge.clearSessionIfOwner(ownerA)
        assertFalse(ExternalTrackpadBridge.sessionFlow.value.isActive)

        var closeCount = 0
        val closerA: () -> Unit = { closeCount += 1 }
        val closerB: () -> Unit = { closeCount += 10 }
        ExternalTrackpadBridge.setPhoneTrackpadVisible(true, closerA)
        assertTrue(ExternalTrackpadBridge.phoneTrackpadVisibleFlow.value)
        assertTrue(ExternalTrackpadBridge.requestClosePhoneTrackpad())
        assertEquals(1, closeCount)

        // Switching to closerB and dismissing stale closerA must not clear closerB or visibility
        ExternalTrackpadBridge.setPhoneTrackpadVisible(true, closerB)
        ExternalTrackpadBridge.setPhoneTrackpadVisible(false, closerA)
        assertTrue(ExternalTrackpadBridge.phoneTrackpadVisibleFlow.value)
        assertTrue(ExternalTrackpadBridge.requestClosePhoneTrackpad())
        assertEquals(11, closeCount)

        ExternalTrackpadBridge.setPhoneTrackpadVisible(false, closerB)
        assertFalse(ExternalTrackpadBridge.phoneTrackpadVisibleFlow.value)
        assertFalse(ExternalTrackpadBridge.requestClosePhoneTrackpad())

        val sink = object : DirectExternalTrackpadSink {
            override val displayId: Int = 2
            override fun currentSurfaceWidthPx(): Int = 1920
            override fun currentSurfaceHeightPx(): Int = 1080
            override fun currentCursorX(): Float = 960f
            override fun currentCursorY(): Float = 540f
            override fun moveCursorBy(dxPx: Float, dyPx: Float) = Unit
            override fun setPrimaryButton(pressed: Boolean) = Unit
            override fun tapPrimary() = Unit
            override fun scrollAtCursor(hScroll: Float, vScroll: Float) = Unit
            override fun hideCursor() = Unit
            override fun closeExternalDesktop() = Unit
        }
        ExternalTrackpadBridge.registerDirectSink(sink)
        assertSame(sink, ExternalTrackpadBridge.getDirectSink())
        assertEquals(960f to 540f, ExternalTrackpadBridge.directCursorFlow.value)
        ExternalTrackpadBridge.unregisterDirectSink(sink)
        assertNull(ExternalTrackpadBridge.getDirectSink())
    }

    @Test
    fun classifyExternalDisplayIdentifiesWiredWirelessTvAndOtherExternal() {
        val pres = ExternalDisplayPolicy.FLAG_PRESENTATION
        val privPres = pres or ExternalDisplayPolicy.FLAG_PRIVATE

        // Standard wired USB-C / HDMI monitor
        assertEquals(
            ExternalDisplayTransport.WIRED,
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 2,
                isValid = true,
                name = "Dell U2723QE",
                flags = pres,
                displayType = ExternalDisplayPolicy.TYPE_EXTERNAL,
            ),
        )

        // Standard Miracast / Wi-Fi Display TV (TYPE_WIFI)
        assertEquals(
            ExternalDisplayTransport.WIRELESS_TV,
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 3,
                isValid = true,
                name = "Sony BRAVIA",
                flags = pres,
                displayType = ExternalDisplayPolicy.TYPE_WIFI,
            ),
        )

        // Miracast TYPE_WIFI even if ROM omitted FLAG_PRESENTATION
        assertEquals(
            ExternalDisplayTransport.WIRELESS_TV,
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 3,
                isValid = true,
                name = "Wireless Display",
                flags = 0,
                displayType = ExternalDisplayPolicy.TYPE_WIFI,
            ),
        )

        // OEM Wireless Cast virtual display exposing FLAG_PRESENTATION
        assertEquals(
            ExternalDisplayTransport.WIRELESS_TV,
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 4,
                isValid = true,
                name = "MiCast",
                flags = pres,
                displayType = ExternalDisplayPolicy.TYPE_VIRTUAL,
            ),
        )

        // Public MediaRouter live-video presentation route with unknown Display.getType()
        assertEquals(
            ExternalDisplayTransport.WIRELESS_TV,
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 5,
                isValid = true,
                name = "Living Room",
                flags = 0,
                displayType = ExternalDisplayPolicy.TYPE_UNKNOWN,
                isMediaRouterPresentation = true,
            ),
        )

        // Keyword fallback when TYPE_UNKNOWN + presentation flag (word-boundary safe for "tv")
        assertEquals(
            ExternalDisplayTransport.WIRELESS_TV,
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 6,
                isValid = true,
                name = "客厅电视",
                flags = pres,
                displayType = ExternalDisplayPolicy.TYPE_UNKNOWN,
            ),
        )
        assertEquals(
            ExternalDisplayTransport.WIRED,
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 7,
                isValid = true,
                name = "Creative Monitor",
                flags = pres,
                displayType = ExternalDisplayPolicy.TYPE_UNKNOWN,
            ),
        )

        // Generic virtual display without FLAG_PRESENTATION (e.g. screen recorder) must be ignored
        assertNull(
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 8,
                isValid = true,
                name = "ScreenRecorder",
                flags = 0,
                displayType = ExternalDisplayPolicy.TYPE_VIRTUAL,
            ),
        )

        // Active OpalCast remote desktop display and internal displays must be ignored
        assertNull(
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 9,
                isValid = true,
                name = "RemoteScreen",
                flags = pres,
                displayType = ExternalDisplayPolicy.TYPE_VIRTUAL,
                activeRemoteDesktopDisplayId = 9,
            ),
        )
        assertNull(
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 10,
                isValid = true,
                name = "OpalWin-1",
                flags = pres,
                displayType = ExternalDisplayPolicy.TYPE_VIRTUAL,
            ),
        )
        assertNull(
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = 11,
                isValid = true,
                name = "Private TV",
                flags = privPres,
                displayType = ExternalDisplayPolicy.TYPE_WIFI,
            ),
        )

        // External non-virtual display lacking FLAG_PRESENTATION is surfaced only as manual OTHER_EXTERNAL
        val manualOnly = ExternalDisplayPolicy.classifyExternalDisplay(
            displayId = 12,
            isValid = true,
            name = "HDMI-2",
            flags = 0,
            displayType = ExternalDisplayPolicy.TYPE_EXTERNAL,
        )
        assertEquals(ExternalDisplayTransport.OTHER_EXTERNAL, manualOnly)
        assertFalse(ExternalDisplayPolicy.isAutoProjectable(manualOnly))
        assertTrue(ExternalDisplayPolicy.isAutoProjectable(ExternalDisplayTransport.WIRED))
        assertTrue(ExternalDisplayPolicy.isAutoProjectable(ExternalDisplayTransport.WIRELESS_TV))
    }

    @Test
    fun presentationPreferenceAndTvModesAndOverscanMathWorkCorrectly() {
        assertFalse(
            ExternalDisplayPolicy.shouldPreferPresentationMode(
                ExternalDisplayTransport.WIRED,
                ExternalDisplayPolicy.PROJECTION_MODE_AUTO,
            ),
        )
        assertTrue(
            ExternalDisplayPolicy.shouldPreferPresentationMode(
                ExternalDisplayTransport.WIRED,
                ExternalDisplayPolicy.PROJECTION_MODE_PRESENTATION,
            ),
        )
        assertTrue(
            ExternalDisplayPolicy.shouldPreferPresentationMode(
                ExternalDisplayTransport.WIRELESS_TV,
                ExternalDisplayPolicy.PROJECTION_MODE_AUTO,
            ),
        )

        // 24/25/30Hz 4K/1080p TVs are supported alongside 60-120Hz monitors
        assertTrue(ExternalDisplayPolicy.isSupportedExternalMode(3840, 2160, 30f))
        assertTrue(ExternalDisplayPolicy.isSupportedExternalMode(1920, 1080, 24f))
        assertTrue(ExternalDisplayPolicy.isSupportedExternalMode(1920, 1080, 60f))
        assertFalse(ExternalDisplayPolicy.isSupportedExternalMode(800, 600, 60f))
        assertFalse(ExternalDisplayPolicy.isSupportedExternalMode(1920, 1080, 15f))

        // TV overscan inset clamping
        assertEquals(0 to 0, ExternalDisplayPolicy.computeOverscanInsetsPx(1920, 1080, 0f))
        assertEquals(67 to 38, ExternalDisplayPolicy.computeOverscanInsetsPx(1920, 1080, 3.5f))
        assertEquals(96 to 54, ExternalDisplayPolicy.computeOverscanInsetsPx(1920, 1080, 5f))
        assertEquals(115 to 65, ExternalDisplayPolicy.computeOverscanInsetsPx(1920, 1080, 20f))
        assertEquals(0 to 0, ExternalDisplayPolicy.computeOverscanInsetsPx(640, 360, 5f))

        // Wireless session summary uses friendly default and 24Hz floor
        val tvSession = ExternalDesktopSession(
            mode = ExternalDesktopMode.PRESENTATION,
            displayId = 4,
            displayName = "",
            widthPx = 1920,
            heightPx = 1080,
            refreshRateHz = 24,
            isWirelessDisplay = true,
        )
        assertEquals("无线电视 · 1920×1080 @ 24Hz", tvSession.summary)
    }
}
