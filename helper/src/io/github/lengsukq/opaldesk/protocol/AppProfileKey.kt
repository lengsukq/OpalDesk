package io.github.lengsukq.opaldesk.protocol

import java.util.Locale

/**
 * Pure JVM single source of truth for multi-user / cloned app identity keys, profile classification,
 * and secondary-user catalog JSON decoding shared across `:helper` local compositor and remote cast.
 */
object AppProfileKey {
    const val PROFILE_PRIMARY = ""
    const val PROFILE_CLONE = "clone"
    const val PROFILE_WORK = "work"
    const val PROFILE_PRIVATE = "private"
    const val PROFILE_SECONDARY = "profile"

    data class SecondaryAppRecord(
        val pkg: String,
        val activityClass: String,
        val label: String,
        val userId: Int,
        val profileType: String,
    ) {
        val appKey: String get() = format(pkg, userId)
    }

    /** Formats canonical app key: plain `pkg` for user 0, `pkg#u<userId>` for secondary profiles. */
    fun format(pkg: String, userId: Int): String {
        val clean = pkg.trim()
        if (clean.isEmpty()) return ""
        return if (userId > 0) "$clean#u$userId" else clean
    }

    /** Parses a canonical app key back into `(pkg, userId)`. */
    fun parse(appKey: String): Pair<String, Int> {
        val clean = appKey.trim()
        val idx = clean.lastIndexOf("#u")
        if (idx > 0 && idx + 2 < clean.length) {
            val uid = clean.substring(idx + 2).toIntOrNull()
            if (uid != null && uid > 0) {
                return clean.substring(0, idx) to uid
            }
        }
        return clean to 0
    }

    fun normalizeProfileType(raw: String?, userId: Int): String {
        if (userId <= 0) return PROFILE_PRIMARY
        val clean = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (clean == PROFILE_CLONE || clean == PROFILE_WORK || clean == PROFILE_PRIVATE || clean == PROFILE_SECONDARY) {
            return clean
        }
        return when (userId) {
            999, 95, 128 -> PROFILE_CLONE
            else -> PROFILE_SECONDARY
        }
    }

    /** Extra search keywords so users can type "分身" / "双开" / "clone" / "工作" / "work". */
    fun searchKeywords(profileType: String, userId: Int): List<String> {
        if (userId <= 0) return emptyList()
        return when (normalizeProfileType(profileType, userId)) {
            PROFILE_CLONE -> listOf("分身", "双开", "多开", "clone", "dual", "u$userId")
            PROFILE_WORK -> listOf("工作", "work", "managed", "u$userId")
            PROFILE_PRIVATE -> listOf("隐私", "private", "u$userId")
            else -> listOf("多用户", "profile", "user", "u$userId")
        }
    }

    private val strFieldRegex = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val numFieldRegex = Regex(""""([^"\\]+)"\s*:\s*(-?\d+)""")

    /** Pure-JVM JSON array parser for daemon `TX_GET_SECONDARY_USER_APPS` responses. */
    fun parseSecondaryAppsJson(json: String?): List<SecondaryAppRecord> {
        if (json.isNullOrBlank()) return emptyList()
        val objects = extractFlatObjects(json)
        if (objects.isEmpty()) return emptyList()
        val out = ArrayList<SecondaryAppRecord>(objects.size)
        val seen = HashSet<String>()
        for (rawObj in objects) {
            val strs = HashMap<String, String>()
            strFieldRegex.findAll(rawObj).forEach { m ->
                strs[m.groupValues[1]] = unescapeJson(m.groupValues[2])
            }
            val nums = HashMap<String, Int>()
            numFieldRegex.findAll(rawObj).forEach { m ->
                m.groupValues[2].toIntOrNull()?.let { nums[m.groupValues[1]] = it }
            }
            val pkg = strs["pkg"].orEmpty().trim()
            val userId = (nums["userId"] ?: 0).coerceAtLeast(0)
            if (pkg.isEmpty() || userId <= 0) continue
            val key = format(pkg, userId)
            if (!seen.add(key)) continue
            val cls = strs["cls"].orEmpty().trim()
            val label = strs["label"].orEmpty().trim().ifEmpty { pkg }
            val profileType = normalizeProfileType(strs["profileType"], userId)
            out.add(SecondaryAppRecord(pkg, cls, label, userId, profileType))
        }
        return out
    }

    private fun extractFlatObjects(json: String): List<String> {
        val out = ArrayList<String>()
        var inStr = false
        var escape = false
        var depth = 0
        var start = -1
        for (i in json.indices) {
            val ch = json[i]
            if (escape) {
                escape = false
                continue
            }
            if (inStr) {
                if (ch == '\\') escape = true
                else if (ch == '"') inStr = false
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            out.add(json.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return out
    }

    private fun unescapeJson(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '"', '\\', '/' -> sb.append(next)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(next)
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
