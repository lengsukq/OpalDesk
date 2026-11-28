package io.github.lengsukq.opaldesk.widgets

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.roundToInt

enum class DesktopWidgetCategory {
    MEDIA,
    TIME,
    SYSTEM,
    INFO,
    PRODUCTIVITY,
}

enum class DesktopWidgetType {
    DESKTOP_HUB,
    MEDIA_PLAYER,
    CLOCK_TIMER,
    WORKSTATION_STATUS,
    NOTIFICATION_OTP,
    QUICK_NOTES,
    APP_GROUP_LAUNCHER,
    TODO_CHECKLIST,
    WEATHER_FORECAST,
    SYSTEM_MONITOR,
    CALENDAR_MONTH,
    QUICK_ACTION_TILES,
}

enum class WidgetSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

data class DesktopWidgetSpec(
    val type: DesktopWidgetType,
    val category: DesktopWidgetCategory,
    val titleZh: String,
    val titleEn: String,
    val subtitleZh: String,
    val subtitleEn: String,
    val defaultColSpan: Int,
    val defaultRowSpan: Int,
    val minColSpan: Int,
    val minRowSpan: Int,
    val maxColSpan: Int,
    val maxRowSpan: Int,
    val accentColorHex: Long,
    val allowMultiple: Boolean = true,
)

data class DesktopWidgetInstance(
    val id: String,
    val type: DesktopWidgetType,
    val col: Int,
    val row: Int,
    val colSpan: Int,
    val rowSpan: Int,
    val configJson: String = "{}",
)

data class WidgetMediaSnapshot(
    val title: String,
    val artist: String,
    val appName: String,
    val isPlaying: Boolean,
    val hasSession: Boolean,
    val artworkBitmap: Bitmap? = null,
)

data class WidgetNotificationItem(
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestampMs: Long,
)

data class WidgetAppShortcut(
    val packageName: String,
    val label: String,
    val iconBitmap: Bitmap? = null,
)

data class WidgetWorkstationSnapshot(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val isConnected: Boolean,
    val connectionLabel: String,
    val activeWindowCount: Int,
    val isPhoneScreenOff: Boolean,
    val isMuted: Boolean,
)

data class DesktopWidgetCallbacks(
    val onMediaPrev: () -> Unit,
    val onMediaPlayPause: () -> Unit,
    val onMediaNext: () -> Unit,
    val onTogglePhoneScreenOff: () -> Unit,
    val onOpenTrackpad: () -> Unit,
    val onToggleMute: () -> Unit,
    val onOpenControlCenter: () -> Unit,
    val onCopyText: (String) -> Unit,
    val onReadClipboard: () -> String?,
    val onLaunchApp: (String) -> Unit,
    val onSplitLaunchApps: (String, String) -> Unit,
    val onUpdateWidgetConfig: (widgetId: String, configJson: String) -> Unit,
    val onOpenSpotlight: () -> Unit = {},
    val onOpenWidgetStore: () -> Unit = {},
    val onToggleWidgetEditMode: () -> Unit = {},
    val onOpenNotifications: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

object DesktopWidgetCatalog {
    val specs: List<DesktopWidgetSpec> = listOf(
        DesktopWidgetSpec(
            type = DesktopWidgetType.DESKTOP_HUB,
            category = DesktopWidgetCategory.SYSTEM,
            titleZh = "桌面灵动中枢",
            titleEn = "Desktop Hub",
            subtitleZh = "无限循环 4 页液态中枢：状态总览、快捷控制、正在播放与通知验证码",
            subtitleEn = "Infinite 4-page liquid glass hub: overview, controls, media & alerts",
            defaultColSpan = 6,
            defaultRowSpan = 3,
            minColSpan = 4,
            minRowSpan = 2,
            maxColSpan = 8,
            maxRowSpan = 4,
            accentColorHex = 0xFF38BDF8L,
            allowMultiple = false,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.CLOCK_TIMER,
            category = DesktopWidgetCategory.TIME,
            titleZh = "时钟与番茄专注",
            titleEn = "Clock & Focus Timer",
            subtitleZh = "液态数字时钟、日期与 25 分钟番茄计时器",
            subtitleEn = "Live digital clock, date & 25-min Pomodoro timer",
            defaultColSpan = 4,
            defaultRowSpan = 2,
            minColSpan = 2,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 4,
            accentColorHex = 0xFF0A84FFL,
            allowMultiple = true,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.MEDIA_PLAYER,
            category = DesktopWidgetCategory.MEDIA,
            titleZh = "音乐与媒体控制",
            titleEn = "Now Playing Media",
            subtitleZh = "实时专辑封面、曲目信息与一键切歌暂停",
            subtitleEn = "Live album art, track metadata & playback controls",
            defaultColSpan = 4,
            defaultRowSpan = 2,
            minColSpan = 3,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 4,
            accentColorHex = 0xFFFF2D55L,
            allowMultiple = false,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.WORKSTATION_STATUS,
            category = DesktopWidgetCategory.SYSTEM,
            titleZh = "工作站状态与捷径",
            titleEn = "Workstation Status",
            subtitleZh = "电量、连接状态、手机熄屏、触控板与静音开关",
            subtitleEn = "Battery, link status, phone blackout, trackpad & mute",
            defaultColSpan = 4,
            defaultRowSpan = 2,
            minColSpan = 2,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 4,
            accentColorHex = 0xFF30D158L,
            allowMultiple = false,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.NOTIFICATION_OTP,
            category = DesktopWidgetCategory.INFO,
            titleZh = "通知与验证码速览",
            titleEn = "Notifications & OTP",
            subtitleZh = "自动提取短信验证码一键复制，聚合最新提醒",
            subtitleEn = "Auto-detect OTP codes for 1-tap copy & live alerts",
            defaultColSpan = 4,
            defaultRowSpan = 3,
            minColSpan = 3,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 5,
            accentColorHex = 0xFFFF9F0AL,
            allowMultiple = false,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.QUICK_NOTES,
            category = DesktopWidgetCategory.PRODUCTIVITY,
            titleZh = "桌面便签与剪贴板",
            titleEn = "Quick Notes & Clip",
            subtitleZh = "随时记录灵感片段，支持一键读取与复制剪贴板",
            subtitleEn = "Scratchpad notes with quick clipboard paste & copy",
            defaultColSpan = 4,
            defaultRowSpan = 3,
            minColSpan = 2,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 5,
            accentColorHex = 0xFFBF5AF2L,
            allowMultiple = true,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.APP_GROUP_LAUNCHER,
            category = DesktopWidgetCategory.PRODUCTIVITY,
            titleZh = "应用组与分屏启动器",
            titleEn = "App Group & Split",
            subtitleZh = "收藏常用工作流应用，一键单开或左右双开分屏",
            subtitleEn = "Pinned workflow apps with 1-click split-screen launch",
            defaultColSpan = 4,
            defaultRowSpan = 2,
            minColSpan = 3,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 4,
            accentColorHex = 0xFF64D2FFL,
            allowMultiple = true,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.TODO_CHECKLIST,
            category = DesktopWidgetCategory.PRODUCTIVITY,
            titleZh = "轻量待办清单",
            titleEn = "Todo Checklist",
            subtitleZh = "快速勾选今日任务、新增条目与完成进度统计",
            subtitleEn = "Check off daily tasks, add items & track progress",
            defaultColSpan = 4,
            defaultRowSpan = 3,
            minColSpan = 3,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 5,
            accentColorHex = 0xFF34C759L,
            allowMultiple = true,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.WEATHER_FORECAST,
            category = DesktopWidgetCategory.INFO,
            titleZh = "实况天气",
            titleEn = "Live Weather",
            subtitleZh = "零 Key 实况气温、体感湿度、逐时与未来 4 天预报及城市切换",
            subtitleEn = "Live temperature, feels-like, hourly & 4-day forecast with city search",
            defaultColSpan = 4,
            defaultRowSpan = 2,
            minColSpan = 2,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 4,
            accentColorHex = 0xFF38BDF8L,
            allowMultiple = false,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.SYSTEM_MONITOR,
            category = DesktopWidgetCategory.SYSTEM,
            titleZh = "性能仪表盘",
            titleEn = "System Monitor",
            subtitleZh = "液态玻璃圆弧仪表盘，实时监测内存、存储、网速与电池温度",
            subtitleEn = "Liquid glass arc gauges for live RAM, storage, network & battery temp",
            defaultColSpan = 4,
            defaultRowSpan = 2,
            minColSpan = 2,
            minRowSpan = 2,
            maxColSpan = 6,
            maxRowSpan = 4,
            accentColorHex = 0xFF30D158L,
            allowMultiple = false,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.CALENDAR_MONTH,
            category = DesktopWidgetCategory.TIME,
            titleZh = "极简月历",
            titleEn = "Calendar",
            subtitleZh = "液态玻璃月视图网格，今日发光高亮、便捷切月与一键回今天",
            subtitleEn = "Minimalist liquid glass monthly grid with glowing today & quick jump",
            defaultColSpan = 3,
            defaultRowSpan = 3,
            minColSpan = 3,
            minRowSpan = 3,
            maxColSpan = 6,
            maxRowSpan = 5,
            accentColorHex = 0xFF5E5CE6L,
            allowMultiple = true,
        ),
        DesktopWidgetSpec(
            type = DesktopWidgetType.QUICK_ACTION_TILES,
            category = DesktopWidgetCategory.SYSTEM,
            titleZh = "快捷方格磁贴",
            titleEn = "Quick Tiles",
            subtitleZh = "圆角毛玻璃快捷方格，一键息屏、分屏、触控板、静音与系统捷径",
            subtitleEn = "Frosted square quick tiles for blackout, split view, trackpad & mute",
            defaultColSpan = 2,
            defaultRowSpan = 2,
            minColSpan = 2,
            minRowSpan = 2,
            maxColSpan = 4,
            maxRowSpan = 3,
            accentColorHex = 0xFF0A84FFL,
            allowMultiple = true,
        ),
    )

    private val specMap: Map<DesktopWidgetType, DesktopWidgetSpec> = specs.associateBy { it.type }

    fun specOf(type: DesktopWidgetType): DesktopWidgetSpec =
        specMap[type] ?: specs.first()

    fun byCategory(category: DesktopWidgetCategory?): List<DesktopWidgetSpec> =
        if (category == null) specs else specs.filter { it.category == category }
}

object DesktopWidgetGridMath {
    const val DEFAULT_GRID_COLS = 12
    const val DEFAULT_GRID_ROWS = 7

    fun clampInstance(
        instance: DesktopWidgetInstance,
        gridCols: Int = DEFAULT_GRID_COLS,
        gridRows: Int = DEFAULT_GRID_ROWS,
    ): DesktopWidgetInstance {
        val safeCols = gridCols.coerceAtLeast(1)
        val safeRows = gridRows.coerceAtLeast(1)
        val spec = DesktopWidgetCatalog.specOf(instance.type)
        val minC = spec.minColSpan.coerceIn(1, safeCols)
        val maxC = spec.maxColSpan.coerceIn(minC, safeCols)
        val minR = spec.minRowSpan.coerceIn(1, safeRows)
        val maxR = spec.maxRowSpan.coerceIn(minR, safeRows)
        val clampedColSpan = instance.colSpan.coerceIn(minC, maxC)
        val clampedRowSpan = instance.rowSpan.coerceIn(minR, maxR)
        val clampedCol = instance.col.coerceIn(0, (safeCols - clampedColSpan).coerceAtLeast(0))
        val clampedRow = instance.row.coerceIn(0, (safeRows - clampedRowSpan).coerceAtLeast(0))
        return instance.copy(
            col = clampedCol,
            row = clampedRow,
            colSpan = clampedColSpan,
            rowSpan = clampedRowSpan,
        )
    }

    fun snapToGrid(
        pixelX: Float,
        pixelY: Float,
        cellWidthPx: Float,
        cellHeightPx: Float,
        colSpan: Int,
        rowSpan: Int,
        gridCols: Int = DEFAULT_GRID_COLS,
        gridRows: Int = DEFAULT_GRID_ROWS,
    ): Pair<Int, Int> {
        val safeCols = gridCols.coerceAtLeast(1)
        val safeRows = gridRows.coerceAtLeast(1)
        val safeSpanC = colSpan.coerceIn(1, safeCols)
        val safeSpanR = rowSpan.coerceIn(1, safeRows)
        val rawCol = if (cellWidthPx > 0.001f) (pixelX / cellWidthPx).roundToInt() else 0
        val rawRow = if (cellHeightPx > 0.001f) (pixelY / cellHeightPx).roundToInt() else 0
        val col = rawCol.coerceIn(0, (safeCols - safeSpanC).coerceAtLeast(0))
        val row = rawRow.coerceIn(0, (safeRows - safeSpanR).coerceAtLeast(0))
        return col to row
    }

    fun computeSizeClass(colSpan: Int, rowSpan: Int): WidgetSizeClass {
        val area = colSpan.coerceAtLeast(1) * rowSpan.coerceAtLeast(1)
        return when {
            colSpan <= 2 || rowSpan <= 1 || area <= 4 -> WidgetSizeClass.COMPACT
            area >= 12 || (colSpan >= 4 && rowSpan >= 3) -> WidgetSizeClass.EXPANDED
            else -> WidgetSizeClass.MEDIUM
        }
    }

    fun findNextAvailableSlot(
        existing: List<DesktopWidgetInstance>,
        colSpan: Int,
        rowSpan: Int,
        gridCols: Int = DEFAULT_GRID_COLS,
        gridRows: Int = DEFAULT_GRID_ROWS,
    ): Pair<Int, Int> {
        val safeCols = gridCols.coerceAtLeast(1)
        val safeRows = gridRows.coerceAtLeast(1)
        val spanC = colSpan.coerceIn(1, safeCols)
        val spanR = rowSpan.coerceIn(1, safeRows)

        val occupied = Array(safeRows) { BooleanArray(safeCols) }
        for (item in existing) {
            val rStart = item.row.coerceIn(0, safeRows)
            val rEnd = (item.row + item.rowSpan).coerceIn(0, safeRows)
            val cStart = item.col.coerceIn(0, safeCols)
            val cEnd = (item.col + item.colSpan).coerceIn(0, safeCols)
            for (r in rStart until rEnd) {
                for (c in cStart until cEnd) {
                    occupied[r][c] = true
                }
            }
        }

        // Prefer columns >= 1 first so column 0 stays clear for left desktop shortcuts when space allows
        val startCols = if (safeCols - spanC >= 1) {
            (1..(safeCols - spanC)).toList() + listOf(0)
        } else {
            (0..(safeCols - spanC)).toList()
        }

        for (r in 0..(safeRows - spanR)) {
            for (c in startCols) {
                var fits = true
                for (dr in 0 until spanR) {
                    for (dc in 0 until spanC) {
                        if (occupied[r + dr][c + dc]) {
                            fits = false
                            break
                        }
                    }
                    if (!fits) break
                }
                if (fits) return c to r
            }
        }
        return 0 to 0
    }

    fun serializeInstances(instances: List<DesktopWidgetInstance>): String {
        val sb = StringBuilder(64 + instances.size * 128)
        sb.append('[')
        instances.forEachIndexed { idx, item ->
            if (idx > 0) sb.append(',')
            sb.append("{\"id\":")
            appendJsonString(sb, item.id)
            sb.append(",\"type\":")
            appendJsonString(sb, item.type.name)
            sb.append(",\"col\":").append(item.col)
            sb.append(",\"row\":").append(item.row)
            sb.append(",\"colSpan\":").append(item.colSpan)
            sb.append(",\"rowSpan\":").append(item.rowSpan)
            sb.append(",\"configJson\":")
            appendJsonString(sb, item.configJson)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun deserializeInstances(json: String): List<DesktopWidgetInstance> {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed[0] != '[') return emptyList()
        val parser = MiniJsonParser(trimmed)
        return runCatching { parser.parseInstanceList() }.getOrDefault(emptyList())
    }

    private fun appendJsonString(out: StringBuilder, value: String) {
        out.append('"')
        for (i in value.indices) {
            val c = value[i]
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append(String.format(Locale.US, "\\u%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun parseInstanceList(): List<DesktopWidgetInstance> {
            val out = ArrayList<DesktopWidgetInstance>()
            skipWs()
            if (!consume('[')) return emptyList()
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (pos < src.length) {
                skipWs()
                parseInstanceObject()?.let { out.add(it) }
                skipWs()
                if (consume(',')) continue
                consume(']')
                break
            }
            return out
        }

        private fun parseInstanceObject(): DesktopWidgetInstance? {
            skipWs()
            if (!consume('{')) return null
            var id = ""
            var typeName = ""
            var col = 0
            var row = 0
            var colSpan = 4
            var rowSpan = 2
            var configJson = "{}"

            skipWs()
            if (peek() == '}') {
                pos++
                return null
            }
            while (pos < src.length) {
                skipWs()
                val key = parseString() ?: break
                skipWs()
                if (!consume(':')) break
                skipWs()
                when (key) {
                    "id" -> id = parseString().orEmpty()
                    "type" -> typeName = parseString().orEmpty()
                    "col" -> col = parseInt() ?: 0
                    "row" -> row = parseInt() ?: 0
                    "colSpan" -> colSpan = parseInt() ?: 4
                    "rowSpan" -> rowSpan = parseInt() ?: 2
                    "configJson" -> configJson = parseString().orEmpty().ifEmpty { "{}" }
                    else -> skipValue()
                }
                skipWs()
                if (consume(',')) continue
                consume('}')
                break
            }
            if (id.isBlank() || typeName.isBlank()) return null
            val type = DesktopWidgetType.entries.firstOrNull { it.name == typeName } ?: return null
            return clampInstance(
                DesktopWidgetInstance(
                    id = id,
                    type = type,
                    col = col,
                    row = row,
                    colSpan = colSpan,
                    rowSpan = rowSpan,
                    configJson = configJson,
                ),
            )
        }

        private fun parseString(): String? {
            skipWs()
            if (!consume('"')) return null
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) break
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= src.length) {
                                    val hex = src.substring(pos, pos + 4)
                                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseInt(): Int? {
            skipWs()
            val start = pos
            if (pos < src.length && (src[pos] == '-' || src[pos] == '+')) pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            return if (pos > start) src.substring(start, pos).toIntOrNull() else null
        }

        private fun skipValue() {
            skipWs()
            when (peek()) {
                '"' -> parseString()
                '{' -> {
                    pos++
                    var depth = 1
                    while (pos < src.length && depth > 0) {
                        when (src[pos]) {
                            '"' -> parseString()
                            '{' -> { depth++; pos++ }
                            '}' -> { depth--; pos++ }
                            else -> pos++
                        }
                    }
                }
                '[' -> {
                    pos++
                    var depth = 1
                    while (pos < src.length && depth > 0) {
                        when (src[pos]) {
                            '"' -> parseString()
                            '[' -> { depth++; pos++ }
                            ']' -> { depth--; pos++ }
                            else -> pos++
                        }
                    }
                }
                else -> {
                    while (pos < src.length && src[pos] != ',' && src[pos] != '}' && src[pos] != ']') {
                        pos++
                    }
                }
            }
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

        private fun consume(expected: Char): Boolean {
            if (pos < src.length && src[pos] == expected) {
                pos++
                return true
            }
            return false
        }
    }
}
