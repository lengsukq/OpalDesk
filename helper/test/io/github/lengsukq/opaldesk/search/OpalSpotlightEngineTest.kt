package io.github.lengsukq.opaldesk.search

import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.ConsoleSectionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpalSpotlightEngineTest {

    @Test
    fun `matches colloquial Chinese synonyms across Host Console catalog`() {
        val items = ConsoleSearchCatalog.buildItems(ConsoleSpotlightState(connected = true))

        val dpiResults = OpalSpotlightEngine.search(items, "字体大")
        assertTrue("Expected PER_APP_DPI or FPS_DPI for 字体大", dpiResults.any {
            it.item.sectionKey == ConsoleSectionKeys.PER_APP_DPI || it.item.sectionKey == ConsoleSectionKeys.FPS_DPI
        })

        val miuiResults = OpalSpotlightEngine.search(items, "澎湃")
        assertTrue("Expected ENGINE_LOWLEVEL for 澎湃", miuiResults.any {
            it.item.sectionKey == ConsoleSectionKeys.ENGINE_LOWLEVEL
        })

        val touchResults = OpalSpotlightEngine.search(items, "点不动")
        assertEquals(ConsoleSectionKeys.ENGINE_LOWLEVEL, touchResults.first().item.sectionKey)

        val imeResults = OpalSpotlightEngine.search(items, "候选框")
        assertEquals(ConsoleSectionKeys.DEX_IME, imeResults.first().item.sectionKey)

        val resResults = OpalSpotlightEngine.search(items, "4k")
        assertEquals(ConsoleSectionKeys.CUSTOM_RESOLUTION, resResults.first().item.sectionKey)

        val tvResults = OpalSpotlightEngine.search(items, "hdmi")
        assertEquals(ConsoleSectionKeys.EXTERNAL_DISPLAY, tvResults.first().item.sectionKey)

        val otpResults = OpalSpotlightEngine.search(items, "验证码")
        assertEquals(ConsoleSectionKeys.NOTIFICATIONS_COMPANION, otpResults.first().item.sectionKey)

        val callResults = OpalSpotlightEngine.search(items, "来电")
        assertEquals(ConsoleSectionKeys.NOTIFICATIONS_COMPANION, callResults.first().item.sectionKey)

        val slotResults = OpalSpotlightEngine.search(items, "槽位")
        assertTrue(slotResults.any { it.item.sectionKey == ConsoleSectionKeys.SLOTS_AUTO_LAYOUT })
        assertTrue(slotResults.any { it.item.sectionKey == ConsoleSectionKeys.SLOTS_QUICK })

        val logResults = OpalSpotlightEngine.search(items, "上报")
        assertTrue(logResults.any { it.item.sectionKey == ConsoleSectionKeys.DIAGNOSTICS_LOGS })
    }

    @Test
    fun `matches pinyin initials both from catalog metadata and built-in Chinese lookup`() {
        val consoleItems = ConsoleSearchCatalog.buildItems()
        val fblResults = OpalSpotlightEngine.search(consoleItems, "fbl")
        assertTrue("Expected CUSTOM_RESOLUTION for pinyin 'fbl'", fblResults.any {
            it.item.sectionKey == ConsoleSectionKeys.CUSTOM_RESOLUTION
        })

        val srfResults = OpalSpotlightEngine.search(consoleItems, "srf")
        assertTrue("Expected DEX_IME for pinyin 'srf'", srfResults.any {
            it.item.sectionKey == ConsoleSectionKeys.DEX_IME
        })

        // Built-in Chinese-to-pinyin-initial extraction without explicit pinyinInitials metadata
        assertEquals("wx", OpalSpotlightEngine.extractPinyinInitials("微信"))
        val appItems = listOf(
            SpotlightItem(
                id = "app:com.tencent.mm",
                category = SpotlightCategory.APP,
                title = "微信",
                subtitle = "com.tencent.mm",
            ),
            SpotlightItem(
                id = "app:com.android.settings",
                category = SpotlightCategory.APP,
                title = "系统设置",
                subtitle = "com.android.settings",
            ),
        )
        val wxResults = OpalSpotlightEngine.search(appItems, "wx")
        assertEquals(1, wxResults.size)
        assertEquals("app:com.tencent.mm", wxResults.first().item.id)
    }

    @Test
    fun `enforces multi-token AND semantics`() {
        val items = ConsoleSearchCatalog.buildItems()

        val matched = OpalSpotlightEngine.search(items, "小米 点不动")
        assertTrue(matched.isNotEmpty())
        assertEquals(ConsoleSectionKeys.ENGINE_LOWLEVEL, matched.first().item.sectionKey)

        val matchedRes = OpalSpotlightEngine.search(items, "4k 刷新率")
        assertTrue(matchedRes.isNotEmpty())
        assertEquals(ConsoleSectionKeys.CUSTOM_RESOLUTION, matchedRes.first().item.sectionKey)

        val unmatched = OpalSpotlightEngine.search(items, "小米 不存在的词xyz")
        assertTrue("Multi-token AND must exclude partial token matches", unmatched.isEmpty())
    }

    @Test
    fun `prioritizes running windows over cold apps on identical match`() {
        val items = listOf(
            SpotlightItem(
                id = "app:com.android.chrome",
                category = SpotlightCategory.APP,
                title = "Chrome",
                subtitle = "com.android.chrome",
            ),
            SpotlightItem(
                id = "win:42",
                category = SpotlightCategory.RUNNING_WINDOW,
                title = "Chrome",
                subtitle = "当前活动 · 1920x1080",
            ),
        )
        val results = OpalSpotlightEngine.search(items, "chrome")
        assertEquals(2, results.size)
        assertEquals(SpotlightCategory.RUNNING_WINDOW, results[0].item.category)
        assertEquals("win:42", results[0].item.id)
        assertEquals(SpotlightCategory.APP, results[1].item.category)
    }

    @Test
    fun `scores exact prefix ahead of substring ahead of pinyin ahead of keyword ahead of subtitle ahead of fuzzy`() {
        val items = listOf(
            SpotlightItem(id = "fuzzy", category = SpotlightCategory.CONSOLE_SECTION, title = "Sample Panel"),
            SpotlightItem(id = "subtitle", category = SpotlightCategory.CONSOLE_SECTION, title = "Other Panel", subtitle = "splendid mode"),
            SpotlightItem(id = "keyword", category = SpotlightCategory.CONSOLE_SECTION, title = "Alpha Panel", keywords = listOf("splx")),
            SpotlightItem(id = "pinyin", category = SpotlightCategory.CONSOLE_SECTION, title = "Beta Panel", pinyinInitials = "spl"),
            SpotlightItem(id = "substring", category = SpotlightCategory.CONSOLE_SECTION, title = "My Spl Tool"),
            SpotlightItem(id = "prefix", category = SpotlightCategory.CONSOLE_SECTION, title = "Split Screen"),
        )
        val results = OpalSpotlightEngine.search(items, "spl")
        assertEquals(
            listOf("prefix", "substring", "pinyin", "keyword", "subtitle", "fuzzy"),
            results.map { it.item.id },
        )
    }

    @Test
    fun `boosts recent items on both empty and active queries and persists max 5 recents`() {
        val items = listOf(
            SpotlightItem(id = "a", category = SpotlightCategory.CONSOLE_SECTION, title = "Display Alpha", pinned = true),
            SpotlightItem(id = "b", category = SpotlightCategory.CONSOLE_SECTION, title = "Display Beta", pinned = true),
            SpotlightItem(id = "c", category = SpotlightCategory.CONSOLE_SECTION, title = "Display Gamma", pinned = false),
        )

        // Empty query returns recents first in exact recency order, followed by pinned items
        val emptyResults = OpalSpotlightEngine.search(items, "   ", recentIds = listOf("c", "b"))
        assertEquals(listOf("c", "b", "a"), emptyResults.map { it.item.id })

        // Active query boosts recent item over equally matched non-recent item
        val activeResults = OpalSpotlightEngine.search(items, "display", recentIds = listOf("c"))
        assertEquals("c", activeResults.first().item.id)

        // AppPrefs recents ring buffer deduplicates, moves to front, and caps at 5
        var recents = emptyList<String>()
        listOf("id1", "id2", "id3", "id4", "id5", "id6", "id3").forEach { id ->
            recents = AppPrefs.updateSpotlightRecents(recents, id)
        }
        assertEquals(listOf("id3", "id6", "id5", "id4", "id2"), recents)
        assertEquals(recents, AppPrefs.parseSpotlightRecents(recents.joinToString(",")))
    }

    @Test
    fun `console and dex catalogs satisfy integrity invariants`() {
        val consoleItems = ConsoleSearchCatalog.buildItems(ConsoleSpotlightState(connected = true))
        assertTrue("Console catalog should have at least 25 items", consoleItems.size >= 25)
        assertEquals("All Console item IDs must be unique", consoleItems.size, consoleItems.map { it.id }.toSet().size)
        consoleItems.forEach { item ->
            assertTrue("Blank id", item.id.isNotBlank())
            assertTrue("Blank title for ${item.id}", item.title.isNotBlank())
        }

        val consoleSections = consoleItems.filter { it.category == SpotlightCategory.CONSOLE_SECTION }
        val coveredKeys = HashSet<String>()
        consoleSections.forEach { section ->
            val tab = section.tabIndex
            val key = section.sectionKey
            assertTrue("Invalid tabIndex $tab for ${section.id}", tab != null && tab in 0..3)
            assertTrue("Missing sectionKey for ${section.id}", !key.isNullOrBlank())
            assertTrue("Unknown sectionKey $key", key in ConsoleSectionKeys.ALL_KEYS)
            assertTrue("Duplicate sectionKey $key", coveredKeys.add(key!!))
        }
        assertEquals(
            "Every ConsoleSectionKeys constant must be registered in ConsoleSearchCatalog",
            ConsoleSectionKeys.ALL_KEYS,
            coveredKeys,
        )

        val dexItems = DexCommandCatalog.buildItems(DexSpotlightState(openWindowCount = 3, hasFocusedWindow = true))
        assertTrue("DeX command catalog should have at least 20 items", dexItems.size >= 20)
        assertEquals("All DeX command IDs must be unique", dexItems.size, dexItems.map { it.id }.toSet().size)
        dexItems.forEach { cmd ->
            assertEquals(SpotlightCategory.DEX_ACTION, cmd.category)
            assertTrue("Blank title for ${cmd.id}", cmd.title.isNotBlank())
            assertFalse("DeX command should not set invalid tabIndex", cmd.tabIndex != null)
        }
    }
}
