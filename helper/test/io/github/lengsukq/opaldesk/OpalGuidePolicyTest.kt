package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.guide.OpalGuidePolicy
import io.github.lengsukq.opaldesk.update.AppUpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class OpalGuidePolicyTest {

    @Test
    fun bundledDefaultGuideIsValidAndUsesAllowedUrls() {
        val doc = OpalGuidePolicy.DEFAULT_GUIDE
        assertTrue(doc.headline.isNotBlank())
        assertTrue(doc.sections.size >= 2)
        assertTrue(doc.sections.all { it.items.isNotEmpty() })
        assertTrue(doc.links.isNotEmpty())
        for (link in doc.links) {
            if (link.action == OpalGuidePolicy.ACTION_OPEN_URL) {
                assertTrue(
                    "Default guide link must be allowlisted: ${link.url}",
                    OpalGuidePolicy.isAllowedExternalLinkUrl(link.url),
                )
            }
        }
    }

    @Test
    fun repositorySiteGuideJsonParsesCleanly() {
        val candidates = listOf(
            File("../site/guide.json"),
            File("site/guide.json"),
        )
        val guideFile = candidates.firstOrNull { it.isFile }
        assertNotNull("Expected site/guide.json in repository", guideFile)
        val raw = guideFile!!.readText(StandardCharsets.UTF_8)
        val parsed = OpalGuidePolicy.parseGuideJson(raw)
        assertNotNull("site/guide.json must parse cleanly", parsed)
        assertEquals(1, parsed!!.schemaVersion)
        assertTrue(parsed.updatedAt.isNotBlank())
        assertNotNull(parsed.announcement)
        assertEquals(2, parsed.sections.size)
        assertEquals("features", parsed.sections[0].id)
        assertEquals("tips", parsed.sections[1].id)
        assertTrue(parsed.sections[0].items.size >= 4)
        assertTrue(parsed.sections[1].items.size >= 3)
        assertEquals(3, parsed.links.size)
    }

    @Test
    fun parseGuideJsonStripsDisallowedLinksAndInvalidItems() {
        val json = """
            {
              "schemaVersion": 1,
              "updatedAt": "2026-12-01",
              "headline": "自定义热更新标题",
              "subtitle": "云端副标题",
              "announcement": {
                "badge": "热更通知",
                "title": "新功能上线",
                "body": "支持实时更新指南内容。"
              },
              "sections": [
                {
                  "id": "sec1",
                  "title": "特性",
                  "items": [
                    {
                      "badge": "NEW",
                      "accent": "EMERALD",
                      "title": "有效卡片",
                      "body": "有效正文",
                      "action": "open_shortcuts",
                      "actionLabel": "快捷键"
                    },
                    {
                      "title": "",
                      "body": "无标题项应跳过"
                    }
                  ]
                },
                {
                  "id": "empty_sec",
                  "title": "空分组",
                  "items": []
                }
              ],
              "links": [
                { "label": "GitHub", "url": "https://github.com/lengsukq/OpalDesk", "action": "open_url" },
                { "label": "官网", "url": "https://lengsukq.github.io/OpalDesk/", "action": "open_url" },
                { "label": "恶意HTTP", "url": "http://github.com/lengsukq/OpalDesk", "action": "open_url" },
                { "label": "恶意域名", "url": "https://github.com.evil.example/phish", "action": "open_url" },
                { "label": "QQ群", "url": "", "action": "join_qq" }
              ]
            }
        """.trimIndent()

        val doc = OpalGuidePolicy.parseGuideJson(json)
        assertNotNull(doc)
        assertEquals("2026-12-01", doc!!.updatedAt)
        assertEquals("自定义热更新标题", doc.headline)
        assertEquals("热更通知", doc.announcement?.badge)
        assertEquals(1, doc.sections.size)
        assertEquals(1, doc.sections[0].items.size)
        assertEquals("emerald", doc.sections[0].items[0].accent)
        assertEquals(3, doc.links.size)
        assertEquals(listOf("GitHub", "官网", "QQ群"), doc.links.map { it.label })
    }

    @Test
    fun parseGuideJsonRejectsMalformedEmptyOrOversizedPayload() {
        assertNull(OpalGuidePolicy.parseGuideJson(""))
        assertNull(OpalGuidePolicy.parseGuideJson("   "))
        assertNull(OpalGuidePolicy.parseGuideJson("not-json"))
        assertNull(OpalGuidePolicy.parseGuideJson("""{"headline":"no sections"}"""))
        assertNull(OpalGuidePolicy.parseGuideJson("""{"sections":[]}"""))
        val oversized = "{\"sections\":[{\"title\":\"x\",\"items\":[{\"title\":\"a\",\"body\":\"" +
            "x".repeat(130_000) + "\"}]}]}"
        assertNull(OpalGuidePolicy.parseGuideJson(oversized))
    }

    @Test
    fun buildFetchUrlsRespectsPreferredDownloadRouteAndAllowlist() {
        val p1 = OpalGuidePolicy.buildFetchUrls(AppUpdateLogic.DownloadRoute.PROXY_1)
        assertTrue(p1.first().startsWith("https://gh-proxy.com/"))
        assertEquals(4, p1.size)
        assertTrue(p1.all { OpalGuidePolicy.isAllowedFetchUrl(it) })

        val p2 = OpalGuidePolicy.buildFetchUrls(AppUpdateLogic.DownloadRoute.PROXY_2)
        assertTrue(p2.first().startsWith("https://ghfast.top/"))
        assertEquals(4, p2.size)
        assertTrue(p2.all { OpalGuidePolicy.isAllowedFetchUrl(it) })

        val direct = OpalGuidePolicy.buildFetchUrls(AppUpdateLogic.DownloadRoute.DIRECT)
        assertEquals(OpalGuidePolicy.GITHUB_PAGES_GUIDE_URL, direct.first())
        assertEquals(OpalGuidePolicy.RAW_GITHUB_GUIDE_URL, direct[1])
        assertEquals(4, direct.size)
        assertTrue(direct.all { OpalGuidePolicy.isAllowedFetchUrl(it) })
    }

    @Test
    fun allowlistsRejectHttpAndSpoofedHosts() {
        assertTrue(OpalGuidePolicy.isAllowedFetchUrl(OpalGuidePolicy.GITHUB_PAGES_GUIDE_URL))
        assertTrue(OpalGuidePolicy.isAllowedFetchUrl(OpalGuidePolicy.RAW_GITHUB_GUIDE_URL))
        assertFalse(OpalGuidePolicy.isAllowedFetchUrl("http://lengsukq.github.io/OpalDesk/guide.json"))
        assertFalse(OpalGuidePolicy.isAllowedFetchUrl("https://lengsukq.github.io.evil.com/guide.json"))
        assertFalse(OpalGuidePolicy.isAllowedFetchUrl("javascript:alert(1)"))

        assertTrue(OpalGuidePolicy.isAllowedExternalLinkUrl(OpalGuidePolicy.GITHUB_REPO_URL))
        assertTrue(OpalGuidePolicy.isAllowedExternalLinkUrl(OpalGuidePolicy.GITHUB_PAGES_HOME_URL))
        assertFalse(OpalGuidePolicy.isAllowedExternalLinkUrl("http://github.com/lengsukq/OpalDesk"))
        assertFalse(OpalGuidePolicy.isAllowedExternalLinkUrl("https://evil.com/github.com"))
        assertFalse(OpalGuidePolicy.isAllowedExternalLinkUrl(""))
    }

    @Test
    fun formatSourceLabelReflectsSyncState() {
        assertEquals("同步云端…", OpalGuidePolicy.formatSourceLabel(false, "", true))
        assertEquals("云端最新 · 2026-11-26", OpalGuidePolicy.formatSourceLabel(true, "2026-11-26", false))
        assertEquals("云端已同步", OpalGuidePolicy.formatSourceLabel(true, "", false))
        assertEquals("离线内置版", OpalGuidePolicy.formatSourceLabel(false, "2026-11-26", false))
    }
}
