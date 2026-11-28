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

    @Test
    fun nonPresentationDisplaysNeverForcePresentationAndEscalateCleanlyToShellLaunch() {
        val pubPres = ExternalDisplayPolicy.FLAG_PRESENTATION
        val privPres = ExternalDisplayPolicy.FLAG_PRESENTATION or ExternalDisplayPolicy.FLAG_PRIVATE

        assertTrue(ExternalDisplayPolicy.isPublicPresentationDisplay(pubPres))
        assertFalse(ExternalDisplayPolicy.isPublicPresentationDisplay(0))
        assertFalse(ExternalDisplayPolicy.isPublicPresentationDisplay(privPres))

        // Regression test: manual launch on a non-presentation display must NOT force Presentation
        assertFalse(
            ExternalDisplayPolicy.shouldForcePresentationForManualLaunch(
                transport = ExternalDisplayTransport.OTHER_EXTERNAL,
                isPresentationCapable = false,
            ),
        )
        assertFalse(
            ExternalDisplayPolicy.shouldForcePresentationForManualLaunch(
                transport = ExternalDisplayTransport.WIRED,
                isPresentationCapable = false,
            ),
        )
        assertTrue(
            ExternalDisplayPolicy.shouldForcePresentationForManualLaunch(
                transport = ExternalDisplayTransport.WIRELESS_TV,
                isPresentationCapable = true,
            ),
        )
        assertFalse(
            ExternalDisplayPolicy.shouldPreferPresentationMode(
                transport = ExternalDisplayTransport.WIRED,
                projectionModePref = ExternalDisplayPolicy.PROJECTION_MODE_PRESENTATION,
                isPresentationCapable = false,
            ),
        )

        // Display 0 landing state machine
        assertEquals(
            ExternalDisplayPolicy.Display0ExternalAction.USE_PRESENTATION,
            ExternalDisplayPolicy.resolveDisplay0ExternalAction(
                hasTargetDisplay = true,
                isPublicPresentation = true,
                shellLaunchAlreadyTried = false,
                daemonConnected = true,
            ),
        )
        assertEquals(
            ExternalDisplayPolicy.Display0ExternalAction.RELAUNCH_DIRECT_VIA_SHELL,
            ExternalDisplayPolicy.resolveDisplay0ExternalAction(
                hasTargetDisplay = true,
                isPublicPresentation = false,
                shellLaunchAlreadyTried = false,
                daemonConnected = true,
            ),
        )
        assertEquals(
            ExternalDisplayPolicy.Display0ExternalAction.FALLBACK_LOCAL_ONLY,
            ExternalDisplayPolicy.resolveDisplay0ExternalAction(
                hasTargetDisplay = true,
                isPublicPresentation = false,
                shellLaunchAlreadyTried = true,
                daemonConnected = true,
            ),
        )

        // Presentation failure recovery state machine: 1 fresh retry -> shell direct launch -> local fallback
        assertEquals(
            ExternalDisplayPolicy.PresentationFailureAction.RETRY_PRESENTATION,
            ExternalDisplayPolicy.resolvePresentationFailureAction(
                retryAttempt = 0,
                isPublicPresentation = true,
                shellLaunchAlreadyTried = false,
                daemonConnected = true,
            ),
        )
        assertEquals(
            ExternalDisplayPolicy.PresentationFailureAction.ESCALATE_DIRECT_VIA_SHELL,
            ExternalDisplayPolicy.resolvePresentationFailureAction(
                retryAttempt = 1,
                isPublicPresentation = true,
                shellLaunchAlreadyTried = false,
                daemonConnected = true,
            ),
        )
        assertEquals(
            ExternalDisplayPolicy.PresentationFailureAction.FALLBACK_LOCAL_ONLY,
            ExternalDisplayPolicy.resolvePresentationFailureAction(
                retryAttempt = 1,
                isPublicPresentation = true,
                shellLaunchAlreadyTried = true,
                daemonConnected = true,
            ),
        )

        // Presentation-capable secondary display (e.g. FLAG_PRESENTATION + trusted + vendor high bit)
        // must fall back directly to Display 0 Presentation host after shell launch is denied, and
        // never loop back to shell launch if Presentation later fails.
        val smartDisplayFlags = ExternalDisplayPolicy.FLAG_PRESENTATION or 0x80 or 0x8000
        assertTrue(ExternalDisplayPolicy.isPublicPresentationDisplay(smartDisplayFlags))
        assertTrue(ExternalDisplayPolicy.shouldFallbackToDisplay0AfterShellFailure(isPresentationCapable = true))
        assertFalse(ExternalDisplayPolicy.shouldFallbackToDisplay0AfterShellFailure(isPresentationCapable = false))
        assertEquals(
            ExternalDisplayPolicy.Display0ExternalAction.USE_PRESENTATION,
            ExternalDisplayPolicy.resolveDisplay0ExternalAction(
                hasTargetDisplay = true,
                isPublicPresentation = true,
                shellLaunchAlreadyTried = true,
                daemonConnected = true,
            ),
        )
    }

    @Test
    fun selectPreferredDisplayModePicksPeakRefreshOrHighestSmoothResolution() {
        val modes = listOf(
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 1, width = 1920, height = 1080, refreshRate = 60f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 2, width = 1920, height = 1080, refreshRate = 120f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 3, width = 2560, height = 1440, refreshRate = 144f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 4, width = 3840, height = 2160, refreshRate = 60f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 5, width = 3840, height = 2160, refreshRate = 30f),
        )
        // Same-resolution peak refresh upgrades 1080p@60Hz -> 1080p@120Hz (mode 2).
        assertEquals(
            2,
            ExternalDisplayPolicy.selectPreferredDisplayMode(
                modes = modes,
                currentWidth = 1920,
                currentHeight = 1080,
                preferHighestResolution = false,
            )?.modeId,
        )
        // Highest smooth (>=60Hz) resolution picks 4K@60Hz (mode 4) over 4K@30Hz (mode 5).
        assertEquals(
            4,
            ExternalDisplayPolicy.selectPreferredDisplayMode(
                modes = modes,
                currentWidth = 1920,
                currentHeight = 1080,
                preferHighestResolution = true,
            )?.modeId,
        )
    }

    @Test
    fun selectDisplayModeForTargetFpsMatchesRequestedRateOrFallsBackCleanly() {
        val modes = listOf(
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 1, width = 2560, height = 1600, refreshRate = 60f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 2, width = 2560, height = 1600, refreshRate = 90f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 3, width = 2560, height = 1600, refreshRate = 120f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 4, width = 2560, height = 1600, refreshRate = 144f),
        )
        // Auto (0) selects peak refresh rate
        assertEquals(4, ExternalDisplayPolicy.selectDisplayModeForTargetFps(modes, 2560, 1600, 0)?.modeId)
        // Exact matches for 60 / 90 / 120 Hz
        assertEquals(1, ExternalDisplayPolicy.selectDisplayModeForTargetFps(modes, 2560, 1600, 60)?.modeId)
        assertEquals(2, ExternalDisplayPolicy.selectDisplayModeForTargetFps(modes, 2560, 1600, 90)?.modeId)
        assertEquals(3, ExternalDisplayPolicy.selectDisplayModeForTargetFps(modes, 2560, 1600, 120)?.modeId)
        // 100Hz not directly listed -> picks smallest mode >= 100Hz (120Hz, mode 3)
        assertEquals(3, ExternalDisplayPolicy.selectDisplayModeForTargetFps(modes, 2560, 1600, 100)?.modeId)
        // Refresh status hint clearly reports cap vs active match
        assertTrue(ExternalDisplayPolicy.formatRefreshStatusHint(120, 60, 60, false).contains("上限"))
        assertTrue(ExternalDisplayPolicy.formatRefreshStatusHint(120, 120, 144, false).contains("已生效"))
    }

    @Test
    fun selectDisplayModeForTargetFpsNeverSwitchesResolutionWhenActiveModeAbsentFromSupportedModes() {
        // Reproduces OPALDESK-1A: active mode is 2048x1536@59Hz while Android 17 anisotropy correction
        // exposes 2677x1536@59Hz + 1920x1080@60Hz + 1024x768@60Hz in supportedModes.
        val anisoModes = listOf(
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 1, width = 2677, height = 1536, refreshRate = 59.0f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 2, width = 1920, height = 1080, refreshRate = 60.0f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 3, width = 1600, height = 1200, refreshRate = 60.0f),
            ExternalDisplayPolicy.DisplayModeCandidate(modeId = 4, width = 1024, height = 768, refreshRate = 60.0f),
        )
        assertNull(ExternalDisplayPolicy.selectDisplayModeForTargetFps(anisoModes, 2048, 1536, targetFps = 60))
        assertNull(ExternalDisplayPolicy.selectDisplayModeForTargetFps(anisoModes, 2048, 1536, targetFps = 0))
        assertNull(
            ExternalDisplayPolicy.selectPreferredDisplayMode(
                modes = anisoModes,
                currentWidth = 2048,
                currentHeight = 1536,
                preferHighestResolution = false,
            ),
        )
    }

    @Test
    fun autoBlackoutWorksInPresentationModeAndSuppressesAutoPhoneTrackpad() {
        val directPhysical = ExternalDesktopSession(
            mode = ExternalDesktopMode.DIRECT_EXTENDED,
            displayId = 2,
            isRemoteCastDisplay = false,
        )
        assertTrue(
            ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(
                session = directPhysical,
                autoShowEnabled = true,
                alreadyVisible = false,
                autoBlackoutEnabled = false,
            ),
        )
        assertFalse(
            ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(
                session = directPhysical,
                autoShowEnabled = true,
                alreadyVisible = false,
                autoBlackoutEnabled = true,
            ),
        )

        // Presentation mode shows trackpad shell on Display 0, yet auto-blackout must still trigger once
        assertTrue(
            ExternalTrackpadPolicy.shouldTriggerAutoBlackout(
                isExternalMonitor = true,
                autoBlackoutEnabled = true,
                daemonConnected = true,
                hostScreenOff = false,
                phoneTrackpadVisible = true,
                isPresentationMode = true,
                alreadyTriggeredForSession = false,
            ),
        )
        // Once triggered in this session, manual wake must not be immediately forced back off
        assertFalse(
            ExternalTrackpadPolicy.shouldTriggerAutoBlackout(
                isExternalMonitor = true,
                autoBlackoutEnabled = true,
                daemonConnected = true,
                hostScreenOff = false,
                phoneTrackpadVisible = true,
                isPresentationMode = true,
                alreadyTriggeredForSession = true,
            ),
        )
    }
}
