package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.prefs.ProfileStoreState
import io.github.lengsukq.opaldesk.prefs.SavedAppWindowBounds
import io.github.lengsukq.opaldesk.prefs.SavedLayoutWindow
import io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileConfigStoreTest {

    @Test
    fun fiveSlotsRoundTripJsonPreservingCustomDpiBoundsAndSavedLayout() {
        val base = ProfileConfigStore.defaultSlots().toMutableList()
        assertEquals(5, base.size)

        base[2] = WorkspaceProfileConfig(
            id = 2,
            name = "投屏 MacBook",
            globalCustomDpi = 220,
            windowDpiScalePct = 100,
            targetFps = 120,
            externalUiScale = 1.25f,
            maxVdCount = 8,
            inputBackendMode = BridgeProtocol.InputBackend.UHID,
            keepLayoutOnExit = true,
            autoRestoreLayout = true,
            appCustomDpis = mapOf("com.tencent.mm" to 320, "tv.danmaku.bili" to 260),
            appWindowBounds = mapOf(
                "com.tencent.mm" to SavedAppWindowBounds(0.10f, 0.12f, 0.45f, 0.88f, BridgeProtocol.Presentation.FLOATING),
            ),
            savedLayout = listOf(
                SavedLayoutWindow(
                    pkg = "com.tencent.mm",
                    activityClass = "com.tencent.mm.ui.LauncherUI",
                    profileCode = BridgeProtocol.Profile.PORTRAIT,
                    leftRatio = 0.05f,
                    topRatio = 0.10f,
                    rightRatio = 0.35f,
                    bottomRatio = 0.90f,
                    presentation = BridgeProtocol.Presentation.FLOATING,
                    minimized = false,
                    zOrder = 2,
                ),
            ),
        )

        val state = ProfileStoreState(activeSlotId = 2, slots = base, workingConfig = base[2])
        val json = ProfileConfigStore.toJson(state)
        val parsed = ProfileConfigStore.fromJson(json)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.activeSlotId)
        assertEquals(5, parsed.slots.size)
        assertFalse(parsed.hasUnsavedChanges)

        val p2 = parsed.activeSavedSlot
        assertEquals("投屏 MacBook", p2.name)
        assertEquals(220, p2.globalCustomDpi)
        assertEquals(120, p2.targetFps)
        assertEquals(8, p2.maxVdCount)
        assertEquals(BridgeProtocol.InputBackend.UHID, p2.inputBackendMode)
        assertTrue(p2.keepLayoutOnExit)
        assertTrue(p2.autoRestoreLayout)
        assertEquals(320, p2.appCustomDpis["com.tencent.mm"])
        assertEquals(260, p2.appCustomDpis["tv.danmaku.bili"])
        assertEquals(1, p2.appWindowBounds.size)
        assertEquals(1, p2.savedLayout.size)
        assertEquals("com.tencent.mm", p2.savedLayout[0].pkg)

        val dpisPayload = p2.formatAppDpisForDaemon()
        assertTrue(dpisPayload.contains("com.tencent.mm=320"))
        assertTrue(dpisPayload.contains("tv.danmaku.bili=260"))
        val boundsPayload = p2.formatAppBoundsForDaemon()
        assertTrue(boundsPayload.contains("com.tencent.mm=0.1000,0.1200,0.4500,0.8800,FLOATING"))
    }

    @Test
    fun workingChangesDoNotOverwriteSavedSlotUntilExplicitSaveOnExit() {
        ProfileConfigStore.loadSlot(null, 0)
        val beforeSlot1 = ProfileConfigStore.stateFlow.value.slots[1]
        assertEquals(0, beforeSlot1.globalCustomDpi)

        ProfileConfigStore.setWorkingGlobalCustomDpi(280)
        ProfileConfigStore.setWorkingAppCustomDpi("com.tencent.mm", 340)
        assertTrue(ProfileConfigStore.stateFlow.value.hasUnsavedChanges)
        // Saved slot 0 and slot 1 remain untouched until user explicitly saves to a slot
        assertEquals(0, ProfileConfigStore.stateFlow.value.slots[0].globalCustomDpi)
        assertEquals(0, ProfileConfigStore.stateFlow.value.slots[1].globalCustomDpi)

        // User chooses slot 1 on exit -> commits working config into slot 1
        val saved = ProfileConfigStore.saveWorkingToSlot(null, targetSlotId = 1, scene = null, saveCurrentLayout = false)
        assertEquals(1, saved.id)
        assertEquals(280, ProfileConfigStore.stateFlow.value.slots[1].globalCustomDpi)
        assertEquals(340, ProfileConfigStore.stateFlow.value.slots[1].appCustomDpis["com.tencent.mm"])
        assertFalse(ProfileConfigStore.stateFlow.value.hasUnsavedChanges)
    }

    @Test
    fun clampDpiOrAutoRejectsOutOfRangeValuesCleanly() {
        assertEquals(0, ProfileConfigStore.clampDpiOrAuto(0))
        assertEquals(0, ProfileConfigStore.clampDpiOrAuto(-50))
        assertEquals(120, ProfileConfigStore.clampDpiOrAuto(80))
        assertEquals(560, ProfileConfigStore.clampDpiOrAuto(900))
        assertEquals(280, ProfileConfigStore.clampDpiOrAuto(280))
    }

    @Test
    fun autoMatchesDisplayTargetAndRemembersSlotBindingOnExitSave() {
        val local = ProfileConfigStore.resolveDisplayTarget(isExternalMonitor = false, remoteConnected = false)
        assertEquals("local", local.key)
        assertEquals(0, local.defaultSlotId)

        val ext = ProfileConfigStore.resolveDisplayTarget(
            isExternalMonitor = true,
            remoteConnected = false,
            externalDisplayName = "DELL U2723QE",
            externalWidth = 2560,
            externalHeight = 1440,
        )
        assertEquals("ext:dell u2723qe@2560x1440", ext.key)
        assertEquals(1, ext.defaultSlotId)

        val mac = ProfileConfigStore.resolveDisplayTarget(
            isExternalMonitor = false,
            remoteConnected = true,
            remotePeerName = "MacBook Pro",
        )
        assertEquals("cast:macbook pro", mac.key)
        assertEquals(2, mac.defaultSlotId)

        val tablet = ProfileConfigStore.resolveDisplayTarget(
            isExternalMonitor = false,
            remoteConnected = true,
            remotePeerName = "Xiaomi Pad 6",
        )
        assertEquals("cast:xiaomi pad 6", tablet.key)
        assertEquals(3, tablet.defaultSlotId)

        // Saving Mac session into slot 4 binds "cast:macbook pro" exclusively to slot 4
        ProfileConfigStore.saveWorkingToSlot(null, targetSlotId = 4, bindTargetKey = mac.key)
        assertEquals(4, ProfileConfigStore.resolveSlotIdForTarget(mac))

        // Re-saving same Mac into slot 2 moves the binding cleanly to slot 2
        ProfileConfigStore.saveWorkingToSlot(null, targetSlotId = 2, bindTargetKey = mac.key)
        assertEquals(2, ProfileConfigStore.resolveSlotIdForTarget(mac))
        assertFalse(ProfileConfigStore.stateFlow.value.slots[4].boundTargets.contains(mac.key))
    }

    @Test
    fun externalUiScalePersistsAcrossSlotLoadsAndAutoMatch() {
        val originalScale = AppPrefs.externalUiScaleFlow.value
        try {
            ProfileConfigStore.setAutoMatchTargetSlot(null, true)
            ProfileConfigStore.loadSlot(null, 0)

            // User picks 100% (1.0f) in display settings
            AppPrefs.setExternalUiScale(null, 1.0f)
            assertEquals(1.0f, AppPrefs.externalUiScaleFlow.value, 0.001f)
            assertFalse(ProfileConfigStore.stateFlow.value.hasUnsavedChanges)

            // Switching slots manually or via display target auto-match must NOT revert UI scale
            ProfileConfigStore.loadSlot(null, 1)
            assertEquals(1.0f, AppPrefs.externalUiScaleFlow.value, 0.001f)

            val macTarget = ProfileConfigStore.resolveDisplayTarget(
                isExternalMonitor = false,
                remoteConnected = true,
                remotePeerName = "MacBook Pro",
            )
            ProfileConfigStore.autoSelectSlotForTarget(null, macTarget)
            assertEquals(1.0f, AppPrefs.externalUiScaleFlow.value, 0.001f)

            // Switching to 175% TV and saving without touching slot UI scale preserves 1.75f
            AppPrefs.setExternalUiScale(null, 1.75f)
            ProfileConfigStore.saveWorkingToSlot(null, targetSlotId = 0, scene = null, saveCurrentLayout = false)
            ProfileConfigStore.loadSlot(null, 3)
            assertEquals(1.75f, AppPrefs.externalUiScaleFlow.value, 0.001f)
        } finally {
            AppPrefs.setExternalUiScale(null, originalScale)
        }
    }

    @Test
    fun persistWorkingDisplayPrefsSurvivesActiveSlotReloadWithoutResettingFpsToZero() {
        ProfileConfigStore.loadSlot(null, 0)
        ProfileConfigStore.persistWorkingDisplayPrefs(ctx = null, targetFps = 120, windowDpiScalePct = 115)

        assertEquals(120, ProfileConfigStore.workingConfig.targetFps)
        assertEquals(115, ProfileConfigStore.workingConfig.windowDpiScalePct)
        assertEquals(120, ProfileConfigStore.stateFlow.value.activeSavedSlot.targetFps)
        assertEquals(115, ProfileConfigStore.stateFlow.value.activeSavedSlot.windowDpiScalePct)

        // Simulating Helper restart / re-hydration from serialized JSON preserves 120Hz instead of reverting to 0
        val roundTripped = ProfileConfigStore.fromJson(ProfileConfigStore.toJson(ProfileConfigStore.stateFlow.value))
        assertNotNull(roundTripped)
        assertEquals(120, roundTripped!!.activeSavedSlot.targetFps)
        assertEquals(115, roundTripped.activeSavedSlot.windowDpiScalePct)
    }
}
