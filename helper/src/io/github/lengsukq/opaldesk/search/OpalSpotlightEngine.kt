package io.github.lengsukq.opaldesk.search

import java.util.Locale

enum class SpotlightCategory(
    val displayTitle: String,
    val basePriority: Int,
) {
    RUNNING_WINDOW("运行中窗口", 500),
    APP("应用", 400),
    DEX_ACTION("桌面指令", 350),
    CONSOLE_ACTION("快捷操作", 300),
    CONSOLE_SECTION("控制台设置", 250),
}

data class SpotlightItem(
    val id: String,
    val category: SpotlightCategory,
    val title: String,
    val subtitle: String = "",
    val badge: String = "",
    val keywords: List<String> = emptyList(),
    val pinyinInitials: String = "",
    val tabIndex: Int? = null,
    val sectionKey: String? = null,
    val pinned: Boolean = false,
    val usageBoost: Int = 0,
    val payload: String = "",
)

data class SpotlightResult(
    val item: SpotlightItem,
    val score: Int,
)

/**
 * Pure JVM deterministic global search & ranking engine powering both Host Console Spotlight
 * and DeX Desktop Spotlight.
 *
 * Supports:
 * - Multi-token whitespace-separated AND matching
 * - Strict match-quality hierarchy: exact title > prefix > substring > pinyin initials > keyword >
 *   subtitle > badge/payload > ordered ASCII subsequence fuzzy match
 * - Built-in zero-dependency common Chinese-to-pinyin-initial lookup for OpalDesk & Android UI terms
 * - Category priority, recency, and usage boosts
 */
object OpalSpotlightEngine {

    private const val SCORE_TITLE_EXACT = 1200
    private const val SCORE_TITLE_PREFIX = 1000
    private const val SCORE_TITLE_SUBSTRING = 820
    private const val SCORE_PINYIN_PREFIX = 700
    private const val SCORE_PINYIN_SUBSTRING = 640
    private const val SCORE_KEYWORD_EXACT = 560
    private const val SCORE_KEYWORD_PREFIX = 520
    private const val SCORE_KEYWORD_SUBSTRING = 460
    private const val SCORE_SUBTITLE_PREFIX = 340
    private const val SCORE_SUBTITLE_SUBSTRING = 300
    private const val SCORE_META_SUBSTRING = 220
    private const val SCORE_FUZZY_BASE = 140

    private val COMMON_ZH_PINYIN_INITIAL: Map<Char, Char> = buildCommonPinyinMap()

    fun search(
        items: List<SpotlightItem>,
        query: String,
        recentIds: List<String> = emptyList(),
        maxResults: Int = 24,
    ): List<SpotlightResult> {
        if (items.isEmpty() || maxResults <= 0) return emptyList()
        val limit = maxResults.coerceAtLeast(1)
        val recentRank = buildRecentRankMap(recentIds)
        val tokens = tokenize(query)

        if (tokens.isEmpty()) {
            return buildEmptyQuerySuggestions(items, recentRank, limit)
        }

        val results = ArrayList<SpotlightResult>(minOf(items.size, limit * 2))
        for (item in items) {
            val score = scoreItemForTokens(item, tokens, recentRank)
            if (score > 0) {
                results.add(SpotlightResult(item = item, score = score))
            }
        }

        results.sortWith(
            compareByDescending<SpotlightResult> { it.score }
                .thenByDescending { it.item.category.basePriority }
                .thenBy { it.item.title.lowercase(Locale.ROOT) }
                .thenBy { it.item.id },
        )
        return if (results.size <= limit) results else results.subList(0, limit)
    }

    /**
     * Extracts lowercase ASCII initials & digits from mixed Chinese/English text using a compact
     * zero-dependency lookup table covering common OpalDesk, Android, and Chinese app characters.
     */
    fun extractPinyinInitials(text: String): String {
        if (text.isBlank()) return ""
        val sb = StringBuilder(text.length)
        var prevWasWordChar = false
        for (ch in text) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> {
                    if (!prevWasWordChar) sb.append(ch)
                    prevWasWordChar = true
                }
                ch in 'A'..'Z' -> {
                    val lower = ch.lowercaseChar()
                    if (!prevWasWordChar || sb.isEmpty()) {
                        sb.append(lower)
                    } else {
                        // CamelCase boundary (e.g. OpalDesk -> od)
                        sb.append(lower)
                    }
                    prevWasWordChar = true
                }
                else -> {
                    prevWasWordChar = false
                    COMMON_ZH_PINYIN_INITIAL[ch]?.let { sb.append(it) }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Returns a compact score in [0..50] if every character of [needle] appears in order inside
     * [haystack] (ASCII subsequence fuzzy match), or -1 if it does not match.
     */
    fun fuzzySubsequenceBonus(needle: String, haystack: String): Int {
        if (needle.length < 2 || haystack.length < needle.length) return -1
        if (!isAsciiAlnum(needle)) return -1
        var nIdx = 0
        var firstMatch = -1
        var lastMatch = -1
        var consecutive = 0
        var prevMatch = -2
        for (hIdx in haystack.indices) {
            if (haystack[hIdx] == needle[nIdx]) {
                if (firstMatch < 0) firstMatch = hIdx
                if (hIdx == prevMatch + 1) consecutive++
                prevMatch = hIdx
                lastMatch = hIdx
                nIdx++
                if (nIdx == needle.length) break
            }
        }
        if (nIdx < needle.length) return -1
        val span = (lastMatch - firstMatch + 1).coerceAtLeast(needle.length)
        val densityBonus = ((needle.length * 30) / span).coerceIn(0, 30)
        val streakBonus = (consecutive * 4).coerceAtMost(20)
        return (densityBonus + streakBonus).coerceIn(0, 50)
    }

    private fun buildEmptyQuerySuggestions(
        items: List<SpotlightItem>,
        recentRank: Map<String, Int>,
        limit: Int,
    ): List<SpotlightResult> {
        val ranked = ArrayList<SpotlightResult>(items.size)
        for (item in items) {
            val rIdx = recentRank[item.id]
            val score = when {
                rIdx != null -> 10_000 - rIdx * 100 + categoryWeight(item.category)
                item.category == SpotlightCategory.RUNNING_WINDOW -> 6_500 + item.usageBoost.coerceIn(0, 120)
                item.pinned -> 5_000 + categoryWeight(item.category) + item.usageBoost.coerceIn(0, 120)
                else -> 0
            }
            if (score > 0) {
                ranked.add(SpotlightResult(item = item, score = score))
            }
        }
        if (ranked.isEmpty()) {
            // Fallback: surface top items by category priority when nothing is pinned or recent
            items.take(limit).forEachIndexed { idx, item ->
                ranked.add(SpotlightResult(item, 1_000 - idx + categoryWeight(item.category)))
            }
        }
        ranked.sortWith(
            compareByDescending<SpotlightResult> { it.score }
                .thenByDescending { it.item.category.basePriority }
                .thenBy { it.item.title.lowercase(Locale.ROOT) }
                .thenBy { it.item.id },
        )
        return if (ranked.size <= limit) ranked else ranked.subList(0, limit)
    }

    private fun scoreItemForTokens(
        item: SpotlightItem,
        tokens: List<String>,
        recentRank: Map<String, Int>,
    ): Int {
        val titleLower = item.title.lowercase(Locale.ROOT)
        val subtitleLower = item.subtitle.lowercase(Locale.ROOT)
        val badgeLower = item.badge.lowercase(Locale.ROOT)
        val payloadLower = item.payload.lowercase(Locale.ROOT)
        val keywordsLower = item.keywords.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        val pinyinCandidates = buildPinyinCandidates(item)

        var tokenSum = 0
        for (token in tokens) {
            val tokenScore = scoreSingleToken(
                token = token,
                titleLower = titleLower,
                subtitleLower = subtitleLower,
                badgeLower = badgeLower,
                payloadLower = payloadLower,
                keywordsLower = keywordsLower,
                pinyinCandidates = pinyinCandidates,
            )
            if (tokenScore <= 0) return 0
            tokenSum += tokenScore
        }

        val recentBoost = recentRank[item.id]?.let { rank -> (160 - rank * 25).coerceAtLeast(35) } ?: 0
        val usageBoost = item.usageBoost.coerceIn(0, 60)
        return tokenSum + categoryWeight(item.category) + recentBoost + usageBoost
    }

    private fun scoreSingleToken(
        token: String,
        titleLower: String,
        subtitleLower: String,
        badgeLower: String,
        payloadLower: String,
        keywordsLower: List<String>,
        pinyinCandidates: List<String>,
    ): Int {
        // 1. Title exact / prefix / substring
        if (titleLower == token) return SCORE_TITLE_EXACT
        if (titleLower.startsWith(token)) return SCORE_TITLE_PREFIX
        if (titleLower.contains(token)) return SCORE_TITLE_SUBSTRING

        // 2. Pinyin initials (explicit catalog initials + auto-extracted title initials)
        var bestPinyin = 0
        for (py in pinyinCandidates) {
            if (py.startsWith(token)) {
                bestPinyin = maxOf(bestPinyin, SCORE_PINYIN_PREFIX)
            } else if (py.contains(token)) {
                bestPinyin = maxOf(bestPinyin, SCORE_PINYIN_SUBSTRING)
            }
        }
        if (bestPinyin > 0) return bestPinyin

        // 3. Keywords (colloquial Chinese synonyms & English abbreviations)
        var bestKeyword = 0
        for (kw in keywordsLower) {
            when {
                kw == token -> bestKeyword = maxOf(bestKeyword, SCORE_KEYWORD_EXACT)
                kw.startsWith(token) -> bestKeyword = maxOf(bestKeyword, SCORE_KEYWORD_PREFIX)
                kw.contains(token) -> bestKeyword = maxOf(bestKeyword, SCORE_KEYWORD_SUBSTRING)
            }
        }
        if (bestKeyword > 0) return bestKeyword

        // 4. Subtitle
        if (subtitleLower.startsWith(token)) return SCORE_SUBTITLE_PREFIX
        if (subtitleLower.contains(token)) return SCORE_SUBTITLE_SUBSTRING

        // 5. Badge / payload (e.g. package name)
        if (badgeLower.contains(token) || payloadLower.contains(token)) return SCORE_META_SUBSTRING

        // 6. Ordered ASCII subsequence fuzzy match
        var bestFuzzy = -1
        bestFuzzy = maxOf(bestFuzzy, fuzzySubsequenceBonus(token, titleLower))
        for (py in pinyinCandidates) {
            bestFuzzy = maxOf(bestFuzzy, fuzzySubsequenceBonus(token, py))
        }
        for (kw in keywordsLower) {
            bestFuzzy = maxOf(bestFuzzy, fuzzySubsequenceBonus(token, kw))
        }
        if (bestFuzzy >= 0) return SCORE_FUZZY_BASE + bestFuzzy

        return 0
    }

    private fun buildPinyinCandidates(item: SpotlightItem): List<String> {
        val out = ArrayList<String>(4)
        if (item.pinyinInitials.isNotBlank()) {
            item.pinyinInitials
                .lowercase(Locale.ROOT)
                .split(',', ' ', '/', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { out.add(it) }
        }
        val autoTitle = extractPinyinInitials(item.title)
        if (autoTitle.isNotEmpty() && autoTitle !in out) {
            out.add(autoTitle)
        }
        return out
    }

    private fun categoryWeight(category: SpotlightCategory): Int = when (category) {
        SpotlightCategory.RUNNING_WINDOW -> 140
        SpotlightCategory.APP -> 70
        SpotlightCategory.DEX_ACTION -> 55
        SpotlightCategory.CONSOLE_ACTION -> 45
        SpotlightCategory.CONSOLE_SECTION -> 35
    }

    private fun buildRecentRankMap(recentIds: List<String>): Map<String, Int> {
        if (recentIds.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, Int>(recentIds.size)
        var idx = 0
        for (raw in recentIds) {
            val id = raw.trim()
            if (id.isNotEmpty() && id !in map) {
                map[id] = idx++
            }
        }
        return map
    }

    private fun tokenize(query: String): List<String> =
        query.trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    private fun isAsciiAlnum(text: String): Boolean {
        for (ch in text) {
            val ok = (ch in 'a'..'z') || (ch in '0'..'9')
            if (!ok) return false
        }
        return text.isNotEmpty()
    }

    private fun buildCommonPinyinMap(): Map<Char, Char> {
        val groups = mapOf(
            'a' to "暗安按爱",
            'b' to "壁本边版备报标并薄冰百哔播宝备",
            'c' to "窗触槽超采测查藏常城册菜重长出",
            'd' to "桌电多点调端动底灯度单低打对大钉抖导独等",
            'e' to "二耳",
            'f' to "分反风返复方发飞放符",
            'g' to "工关高宫更滚光格管共跟感改国",
            'h' to "画互横唤候换回红海黑户环话护核",
            'j' to "激界键截局接进镜简集记机加九减极即居静",
            'k' to "控快跨开卡看口宽框客",
            'l' to "亮连蓝览流录栏离列六冷历联理路力率",
            'm' to "麦模敏密默面描名码慢满门秒明",
            'n' to "内脑能年南暖",
            'p' to "屏配平排澎湃偏漂普盘评",
            'q' to "切全启清浅轻强群企器前去取",
            'r' to "日热软入容认任",
            's' to "设显刷缩深鼠输声摄省双三四上锁扫顺搜手水书商索速实生视时",
            't' to "投通退听推统贴调天图条台同淘腾",
            'w' to "外无网微文卫位完万晚为",
            'x' to "息小休显选新系效详消线校修项序相下信写行",
            'y' to "语燕音隐应移页引原验远永用一有云邮游演易优",
            'z' to "自帧字指主中知志装状最左组止正重值照专账真直",
        )
        val map = HashMap<Char, Char>(512)
        for ((initial, chars) in groups) {
            for (ch in chars) {
                map.putIfAbsent(ch, initial)
            }
        }
        return map
    }
}
