package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.guide.OpalFaqCatalog
import io.github.lengsukq.opaldesk.guide.OpalFaqCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OpalFaqCatalogTest {

    @Test
    fun catalogIsNonEmptyAndPinnedFirstEntryCoversUsbDebuggingTouchTroubleshooting() {
        val zhEntries = OpalFaqCatalog.entries(Locale.SIMPLIFIED_CHINESE)
        val enEntries = OpalFaqCatalog.entries(Locale.US)

        assertTrue("Expected at least 8 ZH FAQ entries", zhEntries.size >= 8)
        assertTrue("Expected at least 8 EN FAQ entries", enEntries.size >= 8)

        val zhFirst = zhEntries.first()
        assertEquals(OpalFaqCatalog.ID_TOUCH_UNRESPONSIVE, zhFirst.id)
        assertEquals(OpalFaqCategory.TOUCH_INPUT, zhFirst.category)
        val zhJoined = (listOf(zhFirst.question, zhFirst.summary) + zhFirst.steps).joinToString(" ")
        assertTrue("ZH first FAQ must mention USB 调试", zhJoined.contains("USB 调试"))
        assertTrue("ZH first FAQ must mention 安全设置", zhJoined.contains("安全设置"))
        assertTrue("ZH first FAQ must mention 禁止权限监控", zhJoined.contains("禁止权限监控"))
        assertTrue("ZH first FAQ must mention 触控 or 点击", zhJoined.contains("触控") && zhJoined.contains("点击"))

        val enFirst = enEntries.first()
        assertEquals(OpalFaqCatalog.ID_TOUCH_UNRESPONSIVE, enFirst.id)
        assertEquals(OpalFaqCategory.TOUCH_INPUT, enFirst.category)
        val enJoined = (listOf(enFirst.question, enFirst.summary) + enFirst.steps).joinToString(" ")
        assertTrue("EN first FAQ must mention USB Debugging", enJoined.contains("USB Debugging"))
        assertTrue("EN first FAQ must mention Security Settings", enJoined.contains("Security Settings"))
        assertTrue("EN first FAQ must mention Disable Permission Monitoring", enJoined.contains("Disable Permission Monitoring"))
    }

    @Test
    fun bilingualCatalogsHaveSymmetricIdsCategoriesAndCompleteContent() {
        val zh = OpalFaqCatalog.entries(Locale.CHINA)
        val en = OpalFaqCatalog.entries(Locale.ENGLISH)

        assertEquals(zh.size, en.size)
        assertEquals(zh.map { it.id }, en.map { it.id })
        assertEquals(zh.map { it.category }, en.map { it.category })
        assertEquals(zh.size, zh.map { it.id }.toSet().size)

        for (entry in zh + en) {
            assertTrue("id must not be blank", entry.id.isNotBlank())
            assertTrue("category must not be ALL", entry.category != OpalFaqCategory.ALL)
            assertTrue("question must not be blank for ${entry.id}", entry.question.isNotBlank())
            assertTrue("summary must not be blank for ${entry.id}", entry.summary.isNotBlank())
            assertTrue("steps must not be empty for ${entry.id}", entry.steps.size >= 3)
            assertTrue("all steps must be non-blank for ${entry.id}", entry.steps.all { it.isNotBlank() })
            assertTrue("keywords must not be empty for ${entry.id}", entry.keywords.isNotEmpty())
        }

        for (cat in OpalFaqCategory.entries.filter { it != OpalFaqCategory.ALL }) {
            assertTrue("Category $cat should have at least 1 ZH entry", zh.any { it.category == cat })
            assertTrue("Category $cat should have at least 1 EN entry", en.any { it.category == cat })
            assertTrue(cat.label(Locale.SIMPLIFIED_CHINESE).isNotBlank())
            assertTrue(cat.label(Locale.US).isNotBlank())
        }
    }

    @Test
    fun localeSelectionFollowsChineseAndFallsBackToEnglish() {
        assertTrue(OpalFaqCatalog.isChineseLocale(Locale.SIMPLIFIED_CHINESE))
        assertTrue(OpalFaqCatalog.isChineseLocale(Locale.TRADITIONAL_CHINESE))
        assertFalse(OpalFaqCatalog.isChineseLocale(Locale.ENGLISH))
        assertFalse(OpalFaqCatalog.isChineseLocale(Locale.JAPAN))

        assertEquals(OpalFaqCatalog.ZH_ENTRIES, OpalFaqCatalog.entries(Locale.CHINA))
        assertEquals(OpalFaqCatalog.EN_ENTRIES, OpalFaqCatalog.entries(Locale.US))
        assertEquals(OpalFaqCatalog.EN_ENTRIES, OpalFaqCatalog.entries(Locale.JAPAN))
    }

    @Test
    fun categoryAndKeywordFilteringWorkAccurately() {
        val touchOnly = OpalFaqCatalog.search(
            category = OpalFaqCategory.TOUCH_INPUT,
            locale = Locale.SIMPLIFIED_CHINESE,
        )
        assertTrue(touchOnly.isNotEmpty())
        assertTrue(touchOnly.all { it.category == OpalFaqCategory.TOUCH_INPUT })
        assertEquals(OpalFaqCatalog.ID_TOUCH_UNRESPONSIVE, touchOnly.first().id)

        val hijackHits = OpalFaqCatalog.search(
            query = "侧键 录制",
            locale = Locale.SIMPLIFIED_CHINESE,
        )
        assertEquals(listOf(OpalFaqCatalog.ID_CUSTOM_KEY_MOUSE_HIJACK), hijackHits.map { it.id })

        val yuyanHits = OpalFaqCatalog.search(
            query = "语燕",
            locale = Locale.SIMPLIFIED_CHINESE,
        )
        assertEquals(listOf(OpalFaqCatalog.ID_YUYAN_IME_KEYBOARD), yuyanHits.map { it.id })

        val enDpiHits = OpalFaqCatalog.search(
            query = "dpi overscan",
            category = OpalFaqCategory.DISPLAY_ACTIVATION,
            locale = Locale.ENGLISH,
        )
        assertEquals(listOf(OpalFaqCatalog.ID_APP_ASPECT_DPI), enDpiHits.map { it.id })

        val wrongCategoryHits = OpalFaqCatalog.search(
            query = "dpi",
            category = OpalFaqCategory.CAST_CONNECTION,
            locale = Locale.ENGLISH,
        )
        assertTrue(wrongCategoryHits.isEmpty())

        val noHits = OpalFaqCatalog.search(
            query = "nonexistent_xyz_token_999",
            locale = Locale.SIMPLIFIED_CHINESE,
        )
        assertTrue(noHits.isEmpty())
    }
}
