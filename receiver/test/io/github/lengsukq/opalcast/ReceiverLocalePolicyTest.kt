package io.github.lengsukq.opalcast

import io.github.lengsukq.opalcast.prefs.ReceiverLocalePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class ReceiverLocalePolicyTest {

    @Test
    fun normalizeLanguagePrefHandlesNullBlankSupportedAndUnknownValues() {
        assertEquals(ReceiverLocalePolicy.LANG_SYSTEM, ReceiverLocalePolicy.normalizeLanguagePref(null))
        assertEquals(ReceiverLocalePolicy.LANG_SYSTEM, ReceiverLocalePolicy.normalizeLanguagePref(""))
        assertEquals(ReceiverLocalePolicy.LANG_SYSTEM, ReceiverLocalePolicy.normalizeLanguagePref("   "))
        assertEquals(ReceiverLocalePolicy.LANG_SYSTEM, ReceiverLocalePolicy.normalizeLanguagePref("system"))
        assertEquals(ReceiverLocalePolicy.LANG_SYSTEM, ReceiverLocalePolicy.normalizeLanguagePref("SYSTEM"))
        assertEquals(ReceiverLocalePolicy.LANG_SYSTEM, ReceiverLocalePolicy.normalizeLanguagePref("ja-JP"))
        assertEquals(ReceiverLocalePolicy.LANG_SYSTEM, ReceiverLocalePolicy.normalizeLanguagePref("fr"))

        assertEquals(ReceiverLocalePolicy.LANG_ZH_CN, ReceiverLocalePolicy.normalizeLanguagePref("zh"))
        assertEquals(ReceiverLocalePolicy.LANG_ZH_CN, ReceiverLocalePolicy.normalizeLanguagePref("zh-CN"))
        assertEquals(ReceiverLocalePolicy.LANG_ZH_CN, ReceiverLocalePolicy.normalizeLanguagePref("zh_CN"))
        assertEquals(ReceiverLocalePolicy.LANG_ZH_CN, ReceiverLocalePolicy.normalizeLanguagePref("zh-Hans"))
        assertEquals(ReceiverLocalePolicy.LANG_ZH_CN, ReceiverLocalePolicy.normalizeLanguagePref("zh-Hans-CN"))

        assertEquals(ReceiverLocalePolicy.LANG_EN, ReceiverLocalePolicy.normalizeLanguagePref("en"))
        assertEquals(ReceiverLocalePolicy.LANG_EN, ReceiverLocalePolicy.normalizeLanguagePref("en-US"))
        assertEquals(ReceiverLocalePolicy.LANG_EN, ReceiverLocalePolicy.normalizeLanguagePref("en_GB"))
    }

    @Test
    fun resolveEffectiveLanguageTagHonorsManualOverrideOverSystemLocale() {
        assertEquals(
            ReceiverLocalePolicy.LANG_ZH_CN,
            ReceiverLocalePolicy.resolveEffectiveLanguageTag(ReceiverLocalePolicy.LANG_ZH_CN, listOf("en-US")),
        )
        assertEquals(
            ReceiverLocalePolicy.LANG_ZH_CN,
            ReceiverLocalePolicy.resolveEffectiveLanguageTag("zh-Hans", listOf("ja-JP")),
        )
        assertEquals(
            ReceiverLocalePolicy.LANG_EN,
            ReceiverLocalePolicy.resolveEffectiveLanguageTag(ReceiverLocalePolicy.LANG_EN, listOf("zh-CN")),
        )
        assertEquals(
            ReceiverLocalePolicy.LANG_EN,
            ReceiverLocalePolicy.resolveEffectiveLanguageTag("en-GB", listOf("zh-Hans-CN")),
        )
    }

    @Test
    fun resolveEffectiveLanguageTagFollowsSystemLocaleWithEnglishFallback() {
        for (tag in listOf("zh", "zh-CN", "zh-Hans", "zh-SG", "zh-TW", "zh-HK", "zh_CN", "ZH-HANT-TW")) {
            assertEquals(
                "Expected zh-CN for system tag $tag",
                ReceiverLocalePolicy.LANG_ZH_CN,
                ReceiverLocalePolicy.resolveEffectiveLanguageTag(ReceiverLocalePolicy.LANG_SYSTEM, listOf(tag)),
            )
        }
        assertEquals(
            ReceiverLocalePolicy.LANG_ZH_CN,
            ReceiverLocalePolicy.resolveEffectiveLanguageTag(null, listOf("en-US", "zh-CN")),
        )

        for (tag in listOf("en", "en-US", "en-GB", "ja-JP", "ko-KR", "de-DE", "fr-FR")) {
            assertEquals(
                "Expected en fallback for system tag $tag",
                ReceiverLocalePolicy.LANG_EN,
                ReceiverLocalePolicy.resolveEffectiveLanguageTag(ReceiverLocalePolicy.LANG_SYSTEM, listOf(tag)),
            )
        }
        assertEquals(
            ReceiverLocalePolicy.LANG_EN,
            ReceiverLocalePolicy.resolveEffectiveLanguageTag("unknown", emptyList()),
        )
    }

    @Test
    fun toLocaleMapsToCanonicalJavaLocales() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, ReceiverLocalePolicy.toLocale(ReceiverLocalePolicy.LANG_ZH_CN))
        assertEquals(Locale.ENGLISH, ReceiverLocalePolicy.toLocale(ReceiverLocalePolicy.LANG_EN))
        assertEquals(Locale.ENGLISH, ReceiverLocalePolicy.toLocale(ReceiverLocalePolicy.LANG_SYSTEM))
    }

    @Test
    fun xmlStringResourcesMaintainFullKeyAndPlaceholderParity() {
        val enFile = resolveResourceFile("res/values/strings.xml")
        val zhFile = resolveResourceFile("res/values-zh/strings.xml")
        assertTrue("Missing English strings.xml at ${enFile.path}", enFile.isFile)
        assertTrue("Missing Simplified Chinese strings.xml at ${zhFile.path}", zhFile.isFile)

        val enStrings = parseStringsXml(enFile)
        val zhStrings = parseStringsXml(zhFile)

        assertTrue("English strings.xml should not be empty", enStrings.isNotEmpty())
        assertEquals("String key sets between values/strings.xml and values-zh/strings.xml must match 100%", enStrings.keys, zhStrings.keys)

        val placeholderRegex = Regex("""%\d+\$[sd]""")
        for ((key, enVal) in enStrings) {
            val zhVal = zhStrings.getValue(key)
            val enPlaceholders = placeholderRegex.findAll(enVal).map { it.value }.toSet()
            val zhPlaceholders = placeholderRegex.findAll(zhVal).map { it.value }.toSet()
            assertEquals("Format placeholders mismatch for key '$key'", enPlaceholders, zhPlaceholders)
        }
    }

    private fun resolveResourceFile(relativeFromReceiver: String): File {
        val direct = File(relativeFromReceiver)
        if (direct.isFile) return direct
        val fromRoot = File("receiver", relativeFromReceiver)
        if (fromRoot.isFile) return fromRoot
        return direct
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            val name = el.attributes?.getNamedItem("name")?.nodeValue?.trim().orEmpty()
            if (name.isNotEmpty()) {
                out[name] = el.textContent.orEmpty()
            }
        }
        return out
    }
}
