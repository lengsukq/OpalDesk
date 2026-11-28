package io.github.lengsukq.opaldesk.input

import java.util.Locale

/**
 * Built-in target actions supported by both `:bridge` (USB/Wi-Fi cast & daemon compositor)
 * and `:helper` (local / external display compositor).
 */
enum class InputBindingAction(
    val actionId: String,
    val labelZh: String,
    val labelEn: String,
) {
    BACK("back", "返回", "Back"),
    TOGGLE_MAXIMIZE("toggle_maximize", "最大化 / 还原窗口", "Toggle Maximize"),
    TOGGLE_FULLSCREEN("toggle_fullscreen", "全屏 / 退出全屏", "Toggle Fullscreen"),
    SNAP_LEFT("snap_left", "左半屏贴靠", "Snap Left"),
    SNAP_RIGHT("snap_right", "右半屏贴靠", "Snap Right"),
    TILE_2_SPLIT("tile_2_split", "左右双分屏", "2-Column Split"),
    CLOSE_WINDOW("close_window", "关闭当前窗口", "Close Window"),
    MINIMIZE_WINDOW("minimize_window", "最小化窗口", "Minimize Window"),
    SWITCH_WINDOW("switch_window", "切换聚焦窗口", "Switch Window"),
    SPOTLIGHT("spotlight", "全局搜索 Spotlight", "Spotlight Search"),
    ;

    fun displayName(isChinese: Boolean): String = if (isChinese) labelZh else labelEn

    companion object {
        fun fromId(actionId: String?): InputBindingAction? {
            val norm = actionId?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.actionId == norm }
        }
    }
}

/**
 * Immutable rule model for unified keyboard & mouse input interception/remapping.
 * Matches `io.github.lengsukq.opaldesk.daemon.input.InputBindingRule` JSON schema 1:1.
 */
data class InputBindingRule(
    val id: String,
    val enabled: Boolean = true,
    val triggerType: String,
    val code: Int,
    val metaMask: Int = META_NONE,
    val actionId: String,
    val label: String = "",
) {
    fun normalized(): InputBindingRule = copy(
        id = id.trim(),
        triggerType = normalizeTriggerType(triggerType),
        metaMask = metaMask and META_ALL_MASK,
        actionId = normalizeActionId(actionId),
        label = label.trim(),
    )

    fun isValid(): Boolean =
        normalizeTriggerType(triggerType).isNotEmpty() &&
            code > 0 &&
            normalizeActionId(actionId).isNotEmpty()

    fun matches(eventTriggerType: String, eventCode: Int, normalizedMetaMask: Int): Boolean =
        enabled &&
            code == eventCode &&
            (metaMask and META_ALL_MASK) == (normalizedMetaMask and META_ALL_MASK) &&
            normalizeTriggerType(triggerType) == normalizeTriggerType(eventTriggerType)

    companion object {
        const val TRIGGER_KEY = "key"
        const val TRIGGER_MOUSE = "mouse"

        const val META_NONE = 0
        const val META_ALT = 1
        const val META_CTRL = 2
        const val META_SHIFT = 4
        const val META_SUPER = 8
        const val META_ALL_MASK = META_ALT or META_CTRL or META_SHIFT or META_SUPER

        const val MOUSE_BUTTON_PRIMARY = 0x01
        const val MOUSE_BUTTON_SECONDARY = 0x02
        const val MOUSE_BUTTON_TERTIARY = 0x04
        const val MOUSE_BUTTON_BACK = 0x08
        const val MOUSE_BUTTON_FORWARD = 0x10

        const val MAX_RULES = 128

        fun normalizeTriggerType(raw: String?): String {
            val lower = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return if (lower == TRIGGER_KEY || lower == TRIGGER_MOUSE) lower else ""
        }

        fun normalizeActionId(raw: String?): String = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()

        fun defaultRules(): List<InputBindingRule> = listOf(
            InputBindingRule(
                id = "default_mouse_back",
                enabled = true,
                triggerType = TRIGGER_MOUSE,
                code = MOUSE_BUTTON_BACK,
                metaMask = META_NONE,
                actionId = InputBindingAction.BACK.actionId,
                label = "Mouse Back -> Back",
            ),
            InputBindingRule(
                id = "default_mouse_right_back",
                enabled = true,
                triggerType = TRIGGER_MOUSE,
                code = MOUSE_BUTTON_SECONDARY,
                metaMask = META_NONE,
                actionId = InputBindingAction.BACK.actionId,
                label = "Mouse Right -> Back",
            ),
        )
    }
}

/**
 * Raw captured input event returned by daemon `TX_INPUT_BINDING_CAPTURE` or local Compose/Motion capture.
 */
data class CapturedInputEvent(
    val triggerType: String,
    val code: Int,
    val metaMask: Int = InputBindingRule.META_NONE,
    val label: String = "",
    val timestampMs: Long = 0L,
) {
    val isValid: Boolean
        get() = InputBindingRule.normalizeTriggerType(triggerType).isNotEmpty() && code > 0

    val hexCode: String
        get() = "0x${code.toString(16).uppercase(Locale.US)}"

    val displayLabel: String
        get() = label.ifBlank { formatTriggerBadge(triggerType, code, metaMask) }
}

private const val ANDROID_META_SHIFT_MASK = 0x01 or 0x40 or 0x80
private const val ANDROID_META_ALT_MASK = 0x02 or 0x10 or 0x20
private const val ANDROID_META_CTRL_MASK = 0x1000 or 0x2000 or 0x4000
private const val ANDROID_META_SUPER_MASK = 0x10000 or 0x20000 or 0x40000

private val MODIFIER_ONLY_KEYCODES = setOf(57, 58, 59, 60, 113, 114, 117, 118)

fun isModifierOnlyKeyCode(keyCode: Int): Boolean = keyCode in MODIFIER_ONLY_KEYCODES

/**
 * Normalizes Android `KeyEvent.metaState` / `MotionEvent.metaState` into the 4-bit
 * cross-platform modifier mask (`ALT=1, CTRL=2, SHIFT=4, SUPER=8`).
 */
fun normalizeAndroidMetaState(metaState: Int): Int {
    var norm = InputBindingRule.META_NONE
    if ((metaState and ANDROID_META_ALT_MASK) != 0) norm = norm or InputBindingRule.META_ALT
    if ((metaState and ANDROID_META_CTRL_MASK) != 0) norm = norm or InputBindingRule.META_CTRL
    if ((metaState and ANDROID_META_SHIFT_MASK) != 0) norm = norm or InputBindingRule.META_SHIFT
    if ((metaState and ANDROID_META_SUPER_MASK) != 0) norm = norm or InputBindingRule.META_SUPER
    return norm
}

/**
 * Formats a concise human-readable trigger badge (e.g. `Mouse Back`, `Mouse 0x20`, `Ctrl+Alt+F4`).
 * Pure JVM safe (does not call `android.view.KeyEvent.keyCodeToString`).
 */
fun formatTriggerBadge(triggerType: String, code: Int, metaMask: Int = 0): String {
    val normType = InputBindingRule.normalizeTriggerType(triggerType)
    val normMeta = metaMask and InputBindingRule.META_ALL_MASK
    val sb = StringBuilder()
    if ((normMeta and InputBindingRule.META_CTRL) != 0) sb.append("Ctrl+")
    if ((normMeta and InputBindingRule.META_ALT) != 0) sb.append("Alt+")
    if ((normMeta and InputBindingRule.META_SHIFT) != 0) sb.append("Shift+")
    if ((normMeta and InputBindingRule.META_SUPER) != 0) sb.append("Meta+")
    if (normType == InputBindingRule.TRIGGER_MOUSE) {
        when (code) {
            InputBindingRule.MOUSE_BUTTON_PRIMARY -> sb.append("Mouse Left")
            InputBindingRule.MOUSE_BUTTON_SECONDARY -> sb.append("Mouse Right")
            InputBindingRule.MOUSE_BUTTON_TERTIARY -> sb.append("Mouse Middle")
            InputBindingRule.MOUSE_BUTTON_BACK -> sb.append("Mouse Back")
            InputBindingRule.MOUSE_BUTTON_FORWARD -> sb.append("Mouse Forward")
            else -> sb.append("Mouse 0x").append(code.toString(16).uppercase(Locale.US))
        }
    } else {
        sb.append(formatAndroidKeyCodeName(code))
    }
    return sb.toString()
}

private fun formatAndroidKeyCodeName(keyCode: Int): String = when (keyCode) {
    4 -> "Back"
    19 -> "Up"
    20 -> "Down"
    21 -> "Left"
    22 -> "Right"
    61 -> "Tab"
    62 -> "Space"
    66 -> "Enter"
    67 -> "Backspace"
    111 -> "Esc"
    112 -> "Delete"
    122 -> "Home"
    123 -> "End"
    92 -> "PageUp"
    93 -> "PageDown"
    in 7..16 -> ('0'.code + (keyCode - 7)).toChar().toString()
    in 29..54 -> ('A'.code + (keyCode - 29)).toChar().toString()
    in 131..142 -> "F${keyCode - 130}"
    else -> "Key #$keyCode"
}

/**
 * Extracts the newly pressed mouse button bitmask from Android `MotionEvent` fields, supporting
 * standard buttons (`0x02`, `0x04`, `0x08`, `0x10`) as well as extended HID bitmasks (`0x20`, `0x40`, ...).
 */
fun extractMouseButtonCode(actionButton: Int, buttonState: Int, previousButtonState: Int = 0): Int {
    if (actionButton > 0) return actionButton
    val newlyPressed = buttonState and previousButtonState.inv()
    if (newlyPressed > 0) return newlyPressed
    return if (buttonState > 0) buttonState else 0
}

fun findMatchingInputBinding(
    rules: List<InputBindingRule>,
    triggerType: String,
    code: Int,
    androidMetaState: Int = 0,
): InputBindingRule? {
    if (code <= 0 || rules.isEmpty()) return null
    val normType = InputBindingRule.normalizeTriggerType(triggerType)
    if (normType.isEmpty()) return null
    val normMeta = normalizeAndroidMetaState(androidMetaState)
    return rules.firstOrNull { it.matches(normType, code, normMeta) }
}

fun findMatchingMouseBinding(
    rules: List<InputBindingRule>,
    buttonCode: Int,
    androidMetaState: Int = 0,
): InputBindingRule? {
    if (buttonCode <= 0 || rules.isEmpty()) return null
    val normMeta = normalizeAndroidMetaState(androidMetaState)
    rules.firstOrNull { it.matches(InputBindingRule.TRIGGER_MOUSE, buttonCode, normMeta) }?.let { return it }
    if (Integer.bitCount(buttonCode) > 1) {
        return rules.firstOrNull {
            it.enabled &&
                InputBindingRule.normalizeTriggerType(it.triggerType) == InputBindingRule.TRIGGER_MOUSE &&
                (it.metaMask and InputBindingRule.META_ALL_MASK) == normMeta &&
                it.code > 0 &&
                (buttonCode and it.code) == it.code
        }
    }
    return null
}

fun findMatchingKeyBinding(
    rules: List<InputBindingRule>,
    keyCode: Int,
    androidMetaState: Int = 0,
): InputBindingRule? = findMatchingInputBinding(
    rules = rules,
    triggerType = InputBindingRule.TRIGGER_KEY,
    code = keyCode,
    androidMetaState = androidMetaState,
)

fun hasEnabledKeyBinding(rules: List<InputBindingRule>): Boolean =
    rules.any {
        it.enabled &&
            InputBindingRule.normalizeTriggerType(it.triggerType) == InputBindingRule.TRIGGER_KEY &&
            it.isValid()
    }

/**
 * Finds an existing rule that shares the same normalized `(triggerType, code, metaMask)` trigger,
 * excluding [excludeRuleId] (used when editing an existing rule so it doesn't conflict with itself).
 */
fun findConflictingBinding(
    rules: List<InputBindingRule>,
    triggerType: String,
    code: Int,
    metaMask: Int,
    excludeRuleId: String? = null,
): InputBindingRule? {
    val normType = InputBindingRule.normalizeTriggerType(triggerType)
    if (normType.isEmpty() || code <= 0) return null
    val normMeta = metaMask and InputBindingRule.META_ALL_MASK
    val skipId = excludeRuleId?.trim().orEmpty()
    return rules.firstOrNull { rule ->
        val norm = rule.normalized()
        (skipId.isEmpty() || norm.id != skipId) &&
            norm.triggerType == normType &&
            norm.code == code &&
            norm.metaMask == normMeta
    }
}

/**
 * Inserts or updates [newRule] in [rules] while automatically removing any other rule whose
 * normalized `(triggerType, code, metaMask)` conflicts with [newRule].
 * When [editingRuleId] is provided, the edited rule is updated in-place to preserve list order.
 */
fun upsertInputBindingRule(
    rules: List<InputBindingRule>,
    newRule: InputBindingRule,
    editingRuleId: String? = null,
): List<InputBindingRule> {
    val normalizedNew = newRule.normalized()
    if (!normalizedNew.isValid()) return rules
    val targetEditId = editingRuleId?.trim().orEmpty()
    val out = ArrayList<InputBindingRule>(rules.size + 1)
    var inserted = false
    for (raw in rules) {
        val existing = raw.normalized()
        if (targetEditId.isNotEmpty() && existing.id == targetEditId) {
            if (!inserted) {
                out.add(normalizedNew)
                inserted = true
            }
            continue
        }
        val sameTrigger = existing.triggerType == normalizedNew.triggerType &&
            existing.code == normalizedNew.code &&
            existing.metaMask == normalizedNew.metaMask
        val sameId = normalizedNew.id.isNotEmpty() && existing.id == normalizedNew.id
        if (sameTrigger || sameId) {
            continue
        }
        out.add(existing)
    }
    if (!inserted) {
        out.add(normalizedNew)
    }
    return out.take(InputBindingRule.MAX_RULES)
}

/**
 * Returns a gentle warning string when a recorded keyboard shortcut is commonly intercepted by
 * desktop operating systems or browsers (e.g. `Alt+Tab`, `Ctrl+W`, `Ctrl+T`, `Super+Space`, `Super+Q`),
 * or `null` when the trigger is safe (including all mouse buttons).
 */
fun getReservedShortcutWarning(
    triggerType: String,
    code: Int,
    metaMask: Int,
    isChinese: Boolean = true,
): String? {
    if (InputBindingRule.normalizeTriggerType(triggerType) != InputBindingRule.TRIGGER_KEY) return null
    val normMeta = metaMask and InputBindingRule.META_ALL_MASK
    val isReserved = when (code) {
        61 -> normMeta == InputBindingRule.META_ALT ||
            normMeta == (InputBindingRule.META_ALT or InputBindingRule.META_SHIFT) ||
            normMeta == InputBindingRule.META_SUPER ||
            normMeta == (InputBindingRule.META_SUPER or InputBindingRule.META_SHIFT) // Alt+Tab / Super+Tab
        51 -> normMeta == InputBindingRule.META_CTRL || normMeta == InputBindingRule.META_SUPER // Ctrl+W / Super+W
        48 -> normMeta == InputBindingRule.META_CTRL || normMeta == InputBindingRule.META_SUPER ||
            normMeta == (InputBindingRule.META_CTRL or InputBindingRule.META_SHIFT) ||
            normMeta == (InputBindingRule.META_SUPER or InputBindingRule.META_SHIFT) // Ctrl+T / Super+T
        62 -> normMeta == InputBindingRule.META_SUPER // Super+Space
        45 -> normMeta == InputBindingRule.META_SUPER // Super+Q
        else -> false
    }
    if (!isReserved) return null
    val badge = formatTriggerBadge(InputBindingRule.TRIGGER_KEY, code, normMeta)
    return if (isChinese) {
        "提示：$badge 容易被电脑系统或浏览器优先拦截，投屏时可能无法生效，建议改用其他按键或鼠标侧键。"
    } else {
        "Note: $badge is often intercepted by the host OS or browser during casting; consider another key or mouse side button."
    }
}

fun isReservedSystemShortcut(triggerType: String, code: Int, metaMask: Int): Boolean =
    getReservedShortcutWarning(triggerType, code, metaMask) != null

object InputBindingCodec {
    fun encodeRules(rules: List<InputBindingRule>): String {
        return buildString(256) {
            append('[')
            var count = 0
            for (raw in rules) {
                if (count >= InputBindingRule.MAX_RULES) break
                val rule = raw.normalized()
                if (!rule.isValid()) continue
                if (count > 0) append(',')
                append("{\"id\":").append(quoteJson(rule.id))
                append(",\"enabled\":").append(rule.enabled)
                append(",\"triggerType\":").append(quoteJson(rule.triggerType))
                append(",\"code\":").append(rule.code)
                append(",\"metaMask\":").append(rule.metaMask)
                append(",\"actionId\":").append(quoteJson(rule.actionId))
                append(",\"label\":").append(quoteJson(rule.label))
                append('}')
                count++
            }
            append(']')
        }
    }

    fun decodeRules(json: String?): List<InputBindingRule> {
        val trimmed = json?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        return try {
            val parsed = SimpleJsonReader(trimmed).parseValue()
            val list = if (parsed is List<*>) {
                parsed
            } else if (parsed is Map<*, *>) {
                (parsed["rules"] as? List<*>) ?: return emptyList()
            } else {
                return emptyList()
            }
            val out = ArrayList<InputBindingRule>(minOf(list.size, InputBindingRule.MAX_RULES))
            for (entry in list) {
                if (out.size >= InputBindingRule.MAX_RULES) break
                val obj = entry as? Map<*, *> ?: continue
                val rule = InputBindingRule(
                    id = obj.str("id", ""),
                    enabled = obj.bool("enabled", true),
                    triggerType = obj.str("triggerType", ""),
                    code = obj.int("code", 0),
                    metaMask = obj.int("metaMask", InputBindingRule.META_NONE),
                    actionId = obj.str("actionId", ""),
                    label = obj.str("label", ""),
                ).normalized()
                if (rule.isValid()) {
                    out.add(rule)
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun encodeCapturedEvent(event: CapturedInputEvent): String {
        return buildString(128) {
            append("{\"triggerType\":").append(quoteJson(InputBindingRule.normalizeTriggerType(event.triggerType)))
            append(",\"code\":").append(event.code)
            append(",\"metaMask\":").append(event.metaMask and InputBindingRule.META_ALL_MASK)
            append(",\"label\":").append(quoteJson(event.displayLabel))
            append(",\"timestampMs\":").append(event.timestampMs)
            append('}')
        }
    }

    fun decodeCapturedEvent(json: String?): CapturedInputEvent? {
        val trimmed = json?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return try {
            val obj = SimpleJsonReader(trimmed).parseValue() as? Map<*, *> ?: return null
            val triggerType = InputBindingRule.normalizeTriggerType(obj.str("triggerType", ""))
            val code = obj.int("code", 0)
            if (triggerType.isEmpty() || code <= 0) return null
            val metaMask = obj.int("metaMask", InputBindingRule.META_NONE) and InputBindingRule.META_ALL_MASK
            val label = obj.str("label", "").trim().ifEmpty {
                formatTriggerBadge(triggerType, code, metaMask)
            }
            CapturedInputEvent(
                triggerType = triggerType,
                code = code,
                metaMask = metaMask,
                label = label,
                timestampMs = obj.long("timestampMs", 0L),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun quoteJson(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append(String.format(Locale.US, "\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun Map<*, *>.str(key: String, def: String): String = (this[key] as? String) ?: def
    private fun Map<*, *>.bool(key: String, def: Boolean): Boolean = (this[key] as? Boolean) ?: def
    private fun Map<*, *>.int(key: String, def: Int): Int = (this[key] as? Number)?.toInt() ?: def
    private fun Map<*, *>.long(key: String, def: Long): Long = (this[key] as? Number)?.toLong() ?: def

    private class SimpleJsonReader(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> if (s.startsWith("true", i)) { i += 4; true } else null
                'f' -> if (s.startsWith("false", i)) { i += 5; false } else null
                'n' -> if (s.startsWith("null", i)) { i += 4; null } else null
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // '{'
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (i < s.length) {
                skipWs()
                if (i >= s.length || s[i] != '"') break
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') break
                i++
                map[key] = parseValue()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                break
            }
            return map
        }

        private fun parseArray(): List<Any?> {
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

        private fun parseString(): String {
            i++ // '"'
            val sb = StringBuilder()
            while (i < s.length) {
                val ch = s[i++]
                if (ch == '"') break
                if (ch == '\\' && i < s.length) {
                    when (val esc = s[i++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> if (i + 4 <= s.length) {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Number? {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) {
                if ((s[i] == '+' || s[i] == '-') && i > start && s[i - 1] != 'e' && s[i - 1] != 'E') break
                i++
            }
            val raw = s.substring(start, i)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
