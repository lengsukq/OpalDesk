package io.github.lengsukq.opaldesk.prefs

import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import java.util.Locale
import kotlin.math.roundToInt

/**
 * JSON serialization and deserialization codec for [ProfileConfigStore],
 * plus daemon-reported window bounds parsing.
 */
internal object ProfileConfigCodec {
    fun parseDaemonBounds(advancedStateJson: String?): Map<String, SavedAppWindowBounds> {
        if (advancedStateJson.isNullOrBlank()) return emptyMap()
        val root = MiniJson.parseObject(advancedStateJson) ?: return emptyMap()
        val boundsObj = root.obj("appWindowBounds") ?: return emptyMap()
        val parsed = LinkedHashMap<String, SavedAppWindowBounds>()
        for ((pkg, rawVal) in boundsObj) {
            val o = rawVal as? Map<*, *> ?: continue
            parsed[pkg] = SavedAppWindowBounds(
                leftRatio = o.int("l", 1000) / 10000f,
                topRatio = o.int("t", 1000) / 10000f,
                rightRatio = o.int("r", 7000) / 10000f,
                bottomRatio = o.int("b", 8000) / 10000f,
                presentation = o.str("p", BridgeProtocol.Presentation.FLOATING),
            )
        }
        return parsed
    }

    fun toJson(state: ProfileStoreState): String = buildString(1024) {
        append("{\"version\":1")
        append(",\"activeProfileId\":").append(state.activeSlotId.coerceIn(0, ProfileConfigStore.SLOT_COUNT - 1))
        append(",\"autoMatchTargetSlot\":").append(state.autoMatchTargetSlot)
        append(",\"profiles\":[")
        state.slots.forEachIndexed { idx, p ->
            if (idx > 0) append(',')
            append("{\"id\":").append(p.id)
            append(",\"name\":").append(MiniJson.quote(p.name))
            append(",\"globalCustomDpi\":").append(p.globalCustomDpi)
            append(",\"windowDpiScalePct\":").append(p.windowDpiScalePct)
            append(",\"targetFps\":").append(p.targetFps)
            append(",\"externalUiScale\":").append(String.format(Locale.US, "%.4f", p.externalUiScale.toDouble()))
            append(",\"maxVdCount\":").append(p.maxVdCount)
            append(",\"inputBackendMode\":").append(p.inputBackendMode)
            append(",\"keepLayoutOnExit\":").append(p.keepLayoutOnExit)
            append(",\"autoRestoreLayout\":").append(p.autoRestoreLayout)

            append(",\"boundTargets\":[")
            var firstT = true
            p.boundTargets.forEach { t ->
                if (t.isNotBlank()) {
                    if (!firstT) append(',')
                    firstT = false
                    append(MiniJson.quote(t))
                }
            }
            append(']')

            append(",\"appCustomDpis\":{")
            var firstD = true
            p.appCustomDpis.forEach { (k, v) ->
                if (!firstD) append(',')
                firstD = false
                append(MiniJson.quote(k)).append(':').append(v)
            }
            append('}')

            append(",\"appTopCropDps\":{")
            var firstC = true
            p.appTopCropDps.forEach { (k, v) ->
                if (!firstC) append(',')
                firstC = false
                append(MiniJson.quote(k)).append(':').append(v)
            }
            append('}')

            append(",\"appWindowBounds\":{")
            var firstB = true
            p.appWindowBounds.forEach { (k, b) ->
                if (!firstB) append(',')
                firstB = false
                append(MiniJson.quote(k)).append(":{")
                append("\"l\":").append((b.leftRatio * 10000f).roundToInt())
                append(",\"t\":").append((b.topRatio * 10000f).roundToInt())
                append(",\"r\":").append((b.rightRatio * 10000f).roundToInt())
                append(",\"b\":").append((b.bottomRatio * 10000f).roundToInt())
                append(",\"p\":").append(MiniJson.quote(b.presentation))
                append('}')
            }
            append('}')

            append(",\"savedLayout\":[")
            p.savedLayout.forEachIndexed { lIdx, w ->
                if (lIdx > 0) append(',')
                append("{\"pkg\":").append(MiniJson.quote(w.pkg))
                append(",\"cls\":").append(MiniJson.quote(w.activityClass))
                append(",\"profileCode\":").append(w.profileCode)
                append(",\"l\":").append((w.leftRatio * 10000f).roundToInt())
                append(",\"t\":").append((w.topRatio * 10000f).roundToInt())
                append(",\"r\":").append((w.rightRatio * 10000f).roundToInt())
                append(",\"b\":").append((w.bottomRatio * 10000f).roundToInt())
                append(",\"p\":").append(MiniJson.quote(w.presentation))
                append(",\"min\":").append(w.minimized)
                append(",\"z\":").append(w.zOrder)
                if (w.userId > 0) {
                    append(",\"userId\":").append(w.userId)
                }
                append('}')
            }
            append("]}")
        }
        append("]}")
    }

    fun fromJson(raw: String?): ProfileStoreState? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = MiniJson.parseObject(raw) ?: return null
            val activeId = root.int("activeProfileId", 0).coerceIn(0, ProfileConfigStore.SLOT_COUNT - 1)
            val autoMatch = root.bool("autoMatchTargetSlot", true)
            val defaults = ProfileConfigStore.defaultSlots()
            val byId = LinkedHashMap<Int, WorkspaceProfileConfig>()
            defaults.forEach { byId[it.id] = it }

            val arr = root.arr("profiles")
            if (arr != null) {
                for (i in arr.indices) {
                    val po = arr[i] as? Map<*, *> ?: continue
                    val id = po.int("id", i).coerceIn(0, ProfileConfigStore.SLOT_COUNT - 1)
                    val base = defaults[id]

                    val dpisMap = LinkedHashMap<String, Int>()
                    val dpisObj = po.obj("appCustomDpis")
                    if (dpisObj != null) {
                        for ((k, rawV) in dpisObj) {
                            val v = ProfileConfigStore.clampDpiOrAuto((rawV as? Number)?.toInt() ?: 0)
                            if (k.isNotBlank() && v > 0) dpisMap[k] = v
                        }
                    }

                    val topCropMap = LinkedHashMap<String, Int>()
                    val topCropObj = po.obj("appTopCropDps")
                    if (topCropObj != null) {
                        for ((k, rawV) in topCropObj) {
                            val v = ((rawV as? Number)?.toInt() ?: 0)
                                .coerceIn(ProfileConfigStore.MIN_TOP_CROP_DP, ProfileConfigStore.MAX_TOP_CROP_DP)
                            if (k.isNotBlank() && v > 0) topCropMap[k] = v
                        }
                    }

                    val boundsMap = LinkedHashMap<String, SavedAppWindowBounds>()
                    val boundsObj = po.obj("appWindowBounds")
                    if (boundsObj != null) {
                        for ((k, rawBo) in boundsObj) {
                            val bo = rawBo as? Map<*, *> ?: continue
                            if (k.isBlank()) continue
                            boundsMap[k] = SavedAppWindowBounds(
                                leftRatio = bo.int("l", 1000) / 10000f,
                                topRatio = bo.int("t", 1000) / 10000f,
                                rightRatio = bo.int("r", 7000) / 10000f,
                                bottomRatio = bo.int("b", 8000) / 10000f,
                                presentation = bo.str("p", BridgeProtocol.Presentation.FLOATING),
                            )
                        }
                    }

                    val layoutList = ArrayList<SavedLayoutWindow>()
                    val layoutArr = po.arr("savedLayout")
                    if (layoutArr != null) {
                        for (j in layoutArr.indices) {
                            val wo = layoutArr[j] as? Map<*, *> ?: continue
                            val pkg = wo.str("pkg", "").trim()
                            if (pkg.isEmpty()) continue
                            layoutList.add(
                                SavedLayoutWindow(
                                    pkg = pkg,
                                    activityClass = wo.str("cls", ""),
                                    profileCode = wo.int("profileCode", BridgeProtocol.Profile.AUTO),
                                    leftRatio = wo.int("l", 1000) / 10000f,
                                    topRatio = wo.int("t", 1000) / 10000f,
                                    rightRatio = wo.int("r", 7000) / 10000f,
                                    bottomRatio = wo.int("b", 8000) / 10000f,
                                    presentation = wo.str("p", BridgeProtocol.Presentation.FLOATING),
                                    minimized = wo.bool("min", false),
                                    zOrder = wo.int("z", j),
                                    userId = wo.int("userId", 0).coerceAtLeast(0),
                                ),
                            )
                        }
                    }

                    val boundTargetsList = ArrayList<String>()
                    val targetsArr = po.arr("boundTargets")
                    if (targetsArr != null) {
                        for (item in targetsArr) {
                            val t = (item as? String)?.trim().orEmpty()
                            if (t.isNotEmpty() && !boundTargetsList.contains(t)) boundTargetsList.add(t)
                        }
                    }

                    byId[id] = WorkspaceProfileConfig(
                        id = id,
                        name = po.str("name", base.name).trim().ifEmpty { base.name },
                        globalCustomDpi = ProfileConfigStore.clampDpiOrAuto(po.int("globalCustomDpi", 0)),
                        windowDpiScalePct = po.int("windowDpiScalePct", 100).coerceIn(70, 160),
                        targetFps = po.int("targetFps", 0).let { if (it == 0 || it in 30..165) it else 0 },
                        externalUiScale = po.double("externalUiScale", 1.25).toFloat().coerceIn(0.85f, 2.0f),
                        maxVdCount = po.int("maxVdCount", 6).coerceIn(2, 12),
                        inputBackendMode = po.int("inputBackendMode", BridgeProtocol.InputBackend.AUTO)
                            .coerceIn(BridgeProtocol.InputBackend.AUTO, BridgeProtocol.InputBackend.UHID),
                        keepLayoutOnExit = po.bool("keepLayoutOnExit", true),
                        autoRestoreLayout = po.bool("autoRestoreLayout", false),
                        boundTargets = boundTargetsList,
                        appCustomDpis = dpisMap,
                        appTopCropDps = topCropMap,
                        appWindowBounds = boundsMap,
                        savedLayout = layoutList,
                    )
                }
            }
            val list = (0 until ProfileConfigStore.SLOT_COUNT).map { byId[it] ?: defaults[it] }
            ProfileStoreState(
                activeSlotId = activeId,
                slots = list,
                workingConfig = list[activeId],
                hasUnsavedChanges = false,
                autoMatchTargetSlot = autoMatch,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun Map<*, *>.str(key: String, fallback: String): String = (this[key] as? String) ?: fallback
    private fun Map<*, *>.int(key: String, fallback: Int): Int = (this[key] as? Number)?.toInt() ?: fallback
    private fun Map<*, *>.double(key: String, fallback: Double): Double = (this[key] as? Number)?.toDouble() ?: fallback
    private fun Map<*, *>.bool(key: String, fallback: Boolean): Boolean = (this[key] as? Boolean) ?: fallback
    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
    private fun Map<*, *>.arr(key: String): List<Any?>? = this[key] as? List<Any?>

    private object MiniJson {
        fun quote(s: String): String = buildString(s.length + 2) {
            append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) {
                        append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }

        @Suppress("UNCHECKED_CAST")
        fun parseObject(src: String): Map<String, Any?>? = Parser(src).parseValue() as? Map<String, Any?>

        private class Parser(private val s: String) {
            private var i = 0

            private fun skipWs() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun parseValue(): Any? {
                skipWs()
                if (i >= s.length) return null
                return when (s[i]) {
                    '{' -> parseObj()
                    '[' -> parseArr()
                    '"' -> parseStr()
                    't' -> if (s.startsWith("true", i)) { i += 4; true } else null
                    'f' -> if (s.startsWith("false", i)) { i += 5; false } else null
                    'n' -> if (s.startsWith("null", i)) { i += 4; null } else null
                    else -> parseNum()
                }
            }

            private fun parseObj(): Map<String, Any?> {
                val map = LinkedHashMap<String, Any?>()
                i++ // '{'
                skipWs()
                if (i < s.length && s[i] == '}') { i++; return map }
                while (i < s.length) {
                    skipWs()
                    if (i >= s.length || s[i] != '"') break
                    val key = parseStr()
                    skipWs()
                    if (i < s.length && s[i] == ':') i++
                    val v = parseValue()
                    map[key] = v
                    skipWs()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == '}') { i++; break }
                    break
                }
                return map
            }

            private fun parseArr(): List<Any?> {
                val list = ArrayList<Any?>()
                i++ // '['
                skipWs()
                if (i < s.length && s[i] == ']') { i++; return list }
                while (i < s.length) {
                    list.add(parseValue())
                    skipWs()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == ']') { i++; break }
                    break
                }
                return list
            }

            private fun parseStr(): String {
                i++ // opening '"'
                val sb = StringBuilder()
                while (i < s.length) {
                    val ch = s[i++]
                    if (ch == '"') break
                    if (ch == '\\' && i < s.length) {
                        when (val esc = s[i++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> if (i + 4 <= s.length) {
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                if (code != null) { sb.append(code.toChar()); i += 4 }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return sb.toString()
            }

            private fun parseNum(): Number? {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
                val tok = s.substring(start, i)
                return if (tok.contains('.') || tok.contains('e', true)) tok.toDoubleOrNull() else tok.toLongOrNull()
            }
        }
    }
}
