package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure JVM unit tests for [OpalLocalePolicy] and 100% key parity between
 * `helper/res/values/strings.xml` (English default) and `helper/res/values-zh/strings.xml` (Simplified Chinese).
 */
class OpalLocalePolicyTest {

    @Test
    fun normalizeLanguagePref_handlesCanonicalVariantsAndUnknownValues() {
        assertEquals(OpalLocalePolicy.LANG_SYSTEM, OpalLocalePolicy.normalizeLanguagePref(null))
        assertEquals(OpalLocalePolicy.LANG_SYSTEM, OpalLocalePolicy.normalizeLanguagePref(""))
        assertEquals(OpalLocalePolicy.LANG_SYSTEM, OpalLocalePolicy.normalizeLanguagePref("   "))
        assertEquals(OpalLocalePolicy.LANG_SYSTEM, OpalLocalePolicy.normalizeLanguagePref("system"))
        assertEquals(OpalLocalePolicy.LANG_SYSTEM, OpalLocalePolicy.normalizeLanguagePref("SYSTEM"))
        assertEquals(OpalLocalePolicy.LANG_SYSTEM, OpalLocalePolicy.normalizeLanguagePref("fr-FR"))
        assertEquals(OpalLocalePolicy.LANG_SYSTEM, OpalLocalePolicy.normalizeLanguagePref("ja-JP"))

        assertEquals(OpalLocalePolicy.LANG_ZH_CN, OpalLocalePolicy.normalizeLanguagePref("zh"))
        assertEquals(OpalLocalePolicy.LANG_ZH_CN, OpalLocalePolicy.normalizeLanguagePref("zh-CN"))
        assertEquals(OpalLocalePolicy.LANG_ZH_CN, OpalLocalePolicy.normalizeLanguagePref("zh_CN"))
        assertEquals(OpalLocalePolicy.LANG_ZH_CN, OpalLocalePolicy.normalizeLanguagePref("zh-Hans-CN"))
        assertEquals(OpalLocalePolicy.LANG_ZH_CN, OpalLocalePolicy.normalizeLanguagePref("  ZH-hans  "))

        assertEquals(OpalLocalePolicy.LANG_EN, OpalLocalePolicy.normalizeLanguagePref("en"))
        assertEquals(OpalLocalePolicy.LANG_EN, OpalLocalePolicy.normalizeLanguagePref("en-US"))
        assertEquals(OpalLocalePolicy.LANG_EN, OpalLocalePolicy.normalizeLanguagePref("en_GB"))
        assertEquals(OpalLocalePolicy.LANG_EN, OpalLocalePolicy.normalizeLanguagePref(" EN "))
    }

    @Test
    fun resolveEffectiveLanguageTag_respectsExplicitPreferenceOverrides() {
        assertEquals(
            OpalLocalePolicy.LANG_ZH_CN,
            OpalLocalePolicy.resolveEffectiveLanguageTag(OpalLocalePolicy.LANG_ZH_CN, listOf("en-US")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_EN,
            OpalLocalePolicy.resolveEffectiveLanguageTag(OpalLocalePolicy.LANG_EN, listOf("zh-CN", "zh-Hans")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_ZH_CN,
            OpalLocalePolicy.resolveEffectiveLanguageTag("zh_Hans", listOf("ja-JP")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_EN,
            OpalLocalePolicy.resolveEffectiveLanguageTag("en-GB", listOf("zh-TW")),
        )
    }

    @Test
    fun resolveEffectiveLanguageTag_followsSystemPrimaryLocaleWithEnglishFallback() {
        assertEquals(
            OpalLocalePolicy.LANG_ZH_CN,
            OpalLocalePolicy.resolveEffectiveLanguageTag(OpalLocalePolicy.LANG_SYSTEM, listOf("zh-CN", "en-US")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_ZH_CN,
            OpalLocalePolicy.resolveEffectiveLanguageTag(null, listOf("zh-Hans-SG")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_ZH_CN,
            OpalLocalePolicy.resolveEffectiveLanguageTag("system", listOf("zh_TW")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_ZH_CN,
            OpalLocalePolicy.resolveEffectiveLanguageTag("system", listOf("zh-HK", "en-US")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_EN,
            OpalLocalePolicy.resolveEffectiveLanguageTag("system", listOf("en-US", "zh-CN")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_EN,
            OpalLocalePolicy.resolveEffectiveLanguageTag("system", listOf("ja-JP", "zh-CN")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_EN,
            OpalLocalePolicy.resolveEffectiveLanguageTag("system", listOf("de-DE")),
        )
        assertEquals(
            OpalLocalePolicy.LANG_EN,
            OpalLocalePolicy.resolveEffectiveLanguageTag("system", emptyList()),
        )
    }

    @Test
    fun toLocale_returnsCanonicalJavaLocales() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, OpalLocalePolicy.toLocale(OpalLocalePolicy.LANG_ZH_CN))
        assertEquals(Locale.SIMPLIFIED_CHINESE, OpalLocalePolicy.toLocale("zh-Hans"))
        assertEquals(Locale.ENGLISH, OpalLocalePolicy.toLocale(OpalLocalePolicy.LANG_EN))
        assertEquals(Locale.ENGLISH, OpalLocalePolicy.toLocale("fr-FR"))
    }

    @Test
    fun stringResources_haveExactKeyParityAndPreserveCanonicalKeys() {
        val enFile = resolveResourceFile("res/values/strings.xml")
        val zhFile = resolveResourceFile("res/values-zh/strings.xml")
        assertTrue("Missing English strings.xml at ${enFile.absolutePath}", enFile.isFile)
        assertTrue("Missing Simplified Chinese strings.xml at ${zhFile.absolutePath}", zhFile.isFile)

        val enEntries = parseStringResources(enFile)
        val zhEntries = parseStringResources(zhFile)

        val missingInZh = enEntries.keys - zhEntries.keys
        val extraInZh = zhEntries.keys - enEntries.keys
        assertTrue("Keys missing in values-zh/strings.xml: $missingInZh", missingInZh.isEmpty())
        assertTrue("Extra keys only in values-zh/strings.xml: $extraInZh", extraInZh.isEmpty())

        val emptyEn = enEntries.filterValues { it.isBlank() }.keys
        val emptyZh = zhEntries.filterValues { it.isBlank() }.keys
        assertTrue("Blank English string values: $emptyEn", emptyEn.isEmpty())
        assertTrue("Blank Chinese string values: $emptyZh", emptyZh.isEmpty())

        val canonicalKeys = listOf(
            "app_name",
            "shortcut_workstation_short",
            "shortcut_workstation_long",
            "shortcut_landscape_short",
            "shortcut_landscape_long",
            "shortcut_activate_short",
            "shortcut_activate_long",
            "shortcut_console_short",
            "shortcut_console_long",
            "tile_enter_dex_label",
            "tile_host_screen_label",
            "tile_wifi_share_label",
            "opaldesk_ime_label",
            "opaldesk_ime_subtype_label",
        )
        canonicalKeys.forEach { key ->
            assertTrue("Missing canonical key '$key' in values/strings.xml", enEntries.containsKey(key))
            assertTrue("Missing canonical key '$key' in values-zh/strings.xml", zhEntries.containsKey(key))
        }
        assertEquals("OpalDesk", enEntries["app_name"])
        assertEquals("OpalDesk", zhEntries["app_name"])
    }

    private fun resolveResourceFile(relativeFromHelper: String): File {
        val direct = File(relativeFromHelper)
        if (direct.isFile) return direct
        val fromRoot = File("helper/$relativeFromHelper")
        if (fromRoot.isFile) return fromRoot
        return direct
    }

    private fun parseStringResources(xmlFile: File): LinkedHashMap<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val nodes = doc.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttribute("name")
            assertTrue("Empty <string name> in ${xmlFile.path}", name.isNotBlank())
            assertTrue("Duplicate key '$name' in ${xmlFile.path}", !result.containsKey(name))
            result[name] = el.textContent ?: ""
        }
        return result
    }
}
