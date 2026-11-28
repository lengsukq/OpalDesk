package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.prefs.ProfileStoreState
import io.github.lengsukq.opaldesk.prefs.SavedLayoutWindow
import io.github.lengsukq.opaldesk.protocol.AppProfileKey
import io.github.lengsukq.opaldesk.ui.AppLauncherSorter
import io.github.lengsukq.opaldesk.ui.UnifiedAppItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppProfileMultiUserTest {

    @Test
    fun formatAndParseAppKeyPreserveUserZeroBackwardCompatibility() {
        assertEquals("com.tencent.mm", AppProfileKey.format("com.tencent.mm", 0))
        assertEquals("com.tencent.mm#u999", AppProfileKey.format("com.tencent.mm", 999))

        assertEquals("com.tencent.mm" to 0, AppProfileKey.parse("com.tencent.mm"))
        assertEquals("com.tencent.mm" to 999, AppProfileKey.parse("com.tencent.mm#u999"))
        assertEquals("com.tencent.mm" to 10, AppProfileKey.parse("com.tencent.mm#u10"))
    }

    @Test
    fun parseSecondaryAppsJsonExtractsAndDeduplicatesClonedApps() {
        val raw = """
            [
              {"pkg":"com.tencent.mm","cls":"com.tencent.mm.ui.LauncherUI","label":"微信","userId":999,"profileType":"clone"},
              {"pkg":"com.tencent.mm","cls":"com.tencent.mm.ui.LauncherUI","label":"微信","userId":999,"profileType":"clone"},
              {"pkg":"com.slack","cls":"com.slack.Main","label":"Slack","userId":10,"profileType":"work"},
              {"pkg":"com.invalid","cls":"","label":"Invalid","userId":0,"profileType":""}
            ]
        """.trimIndent()
        val parsed = AppProfileKey.parseSecondaryAppsJson(raw)
        assertEquals(2, parsed.size)
        assertEquals("com.tencent.mm#u999", parsed[0].appKey)
        assertEquals(999, parsed[0].userId)
        assertEquals(AppProfileKey.PROFILE_CLONE, parsed[0].profileType)
        assertEquals("com.slack#u10", parsed[1].appKey)
        assertEquals(10, parsed[1].userId)
        assertEquals(AppProfileKey.PROFILE_WORK, parsed[1].profileType)
    }

    @Test
    fun appLauncherSorterSupportsProfileKeywordsAndPrimaryBeforeCloneOrdering() {
        val items = listOf(
            UnifiedAppItem(pkg = "com.tencent.mm", activityClass = ".L", label = "WeChat", openCount = 3, userId = 999, profileType = "clone"),
            UnifiedAppItem(pkg = "com.tencent.mm", activityClass = ".L", label = "WeChat", openCount = 3, userId = 0, profileType = ""),
            UnifiedAppItem(pkg = "com.slack", activityClass = ".M", label = "Slack", openCount = 1, userId = 10, profileType = "work"),
        )

        val sorted = AppLauncherSorter.filterAndSort(items, "", sortByUsage = true, locale = Locale.US)
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm#u999", "com.slack#u10"), sorted.map { it.appKey })

        val cloneOnly = AppLauncherSorter.filterAndSort(items, "分身", sortByUsage = false, locale = Locale.CHINA)
        assertEquals(listOf("com.tencent.mm#u999"), cloneOnly.map { it.appKey })

        val workOnly = AppLauncherSorter.filterAndSort(items, "work", sortByUsage = false, locale = Locale.US)
        assertEquals(listOf("com.slack#u10"), workOnly.map { it.appKey })
    }

    @Test
    fun profileConfigStorePreservesSavedLayoutUserId() {
        val defaults = ProfileConfigStore.defaultSlots()
        val layout = listOf(
            SavedLayoutWindow(pkg = "com.tencent.mm", activityClass = ".L", userId = 0, zOrder = 1),
            SavedLayoutWindow(pkg = "com.tencent.mm", activityClass = ".L", userId = 999, zOrder = 2),
        )
        val slot0 = defaults[0].copy(savedLayout = layout)
        val state = ProfileStoreState(
            activeSlotId = 0,
            slots = listOf(slot0) + defaults.drop(1),
            workingConfig = slot0,
        )
        val json = ProfileConfigStore.toJson(state)
        assertTrue(json.contains("\"userId\":999"))

        val restored = ProfileConfigStore.fromJson(json)!!
        val restoredLayout = restored.slots[0].savedLayout
        assertEquals(2, restoredLayout.size)
        assertEquals(0, restoredLayout[0].userId)
        assertEquals(999, restoredLayout[1].userId)
    }
}
