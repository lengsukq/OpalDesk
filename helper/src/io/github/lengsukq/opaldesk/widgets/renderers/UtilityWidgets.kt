package io.github.lengsukq.opaldesk.widgets.renderers

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetInstance
import io.github.lengsukq.opaldesk.widgets.WidgetSizeClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// System Performance Monitor Widget
// ---------------------------------------------------------------------------

private data class SystemMonitorSample(
    val ramPercent: Float = 0f,
    val ramUsedGb: Float = 0f,
    val ramTotalGb: Float = 0f,
    val storagePercent: Float = 0f,
    val storageUsedGb: Float = 0f,
    val storageTotalGb: Float = 0f,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val batteryTempC: Float = 0f,
    val batteryLevel: Int = 100,
)

@Composable
fun SystemMonitorWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val colors = LocalOpalColors.current
    var sample by remember(instance.id) { mutableStateOf(SystemMonitorSample()) }

    LaunchedEffect(ctx, instance.id) {
        var prevRx = safeTrafficBytes { TrafficStats.getTotalRxBytes() }
        var prevTx = safeTrafficBytes { TrafficStats.getTotalTxBytes() }
        var prevTimeMs = SystemClock.elapsedRealtime()

        while (true) {
            val next = withContext(Dispatchers.IO) {
                val nowMs = SystemClock.elapsedRealtime()
                val curRx = safeTrafficBytes { TrafficStats.getTotalRxBytes() }
                val curTx = safeTrafficBytes { TrafficStats.getTotalTxBytes() }
                val dtSec = ((nowMs - prevTimeMs).coerceAtLeast(250L)) / 1000.0
                val rxRate = if (curRx >= 0L && prevRx >= 0L && curRx >= prevRx) {
                    ((curRx - prevRx) / dtSec).toLong()
                } else {
                    0L
                }
                val txRate = if (curTx >= 0L && prevTx >= 0L && curTx >= prevTx) {
                    ((curTx - prevTx) / dtSec).toLong()
                } else {
                    0L
                }
                prevRx = curRx
                prevTx = curTx
                prevTimeMs = nowMs
                collectSystemMetrics(ctx, rxRate, txRate)
            }
            sample = next
            delay(2_000L)
        }
    }

    val ramColor = metricTintColor(sample.ramPercent, colors.accentCyan, colors.accentAmber, colors.accentDanger)
    val storageColor = metricTintColor(sample.storagePercent, colors.accentEmerald, colors.accentAmber, colors.accentDanger)
    val tempColor = when {
        sample.batteryTempC >= 42f -> colors.accentDanger
        sample.batteryTempC >= 37f -> colors.accentAmber
        else -> colors.accentBlue
    }
    val isCompact = sizeClass == WidgetSizeClass.COMPACT
    val isExpanded = sizeClass == WidgetSizeClass.EXPANDED

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = ramColor,
            centerFractionX = 0.18f,
            centerFractionY = 0.16f,
            radiusFraction = 0.72f,
        )
        WidgetAmbientGlow(
            accent = storageColor,
            centerFractionX = 0.86f,
            centerFractionY = 0.82f,
            radiusFraction = 0.62f,
            alpha = 0.12f,
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header bar: title + live battery temperature badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                WidgetGlassBadge(
                    text = if (isZh) "性能仪表" else "System",
                    accent = ramColor,
                    dotColor = colors.accentEmerald,
                )
                WidgetGlassBadge(
                    text = if (sample.batteryTempC > 0f) {
                        String.format(Locale.US, "%.1f°C", sample.batteryTempC)
                    } else {
                        "${sample.batteryLevel}%"
                    },
                    accent = tempColor,
                )
            }

            // Arc gauges row
            val gaugeSize = when {
                isCompact -> 54.dp
                isExpanded -> 86.dp
                else -> 68.dp
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LiquidArcGauge(
                    progress = sample.ramPercent,
                    valueText = "${(sample.ramPercent * 100f).roundToInt()}%",
                    label = if (isZh) "内存" else "RAM",
                    sublabel = if (!isCompact && sample.ramTotalGb > 0f) {
                        String.format(Locale.US, "%.1f/%.0fG", sample.ramUsedGb, sample.ramTotalGb)
                    } else {
                        null
                    },
                    accent = ramColor,
                    gaugeSize = gaugeSize,
                )
                LiquidArcGauge(
                    progress = sample.storagePercent,
                    valueText = "${(sample.storagePercent * 100f).roundToInt()}%",
                    label = if (isZh) "存储" else "Disk",
                    sublabel = if (!isCompact && sample.storageTotalGb > 0f) {
                        String.format(Locale.US, "%.0f/%.0fG", sample.storageUsedGb, sample.storageTotalGb)
                    } else {
                        null
                    },
                    accent = storageColor,
                    gaugeSize = gaugeSize,
                )
                if (isExpanded) {
                    val tempNorm = ((sample.batteryTempC - 20f) / 30f).coerceIn(0f, 1f)
                    LiquidArcGauge(
                        progress = tempNorm,
                        valueText = String.format(Locale.US, "%.0f°", sample.batteryTempC),
                        label = if (isZh) "电池温度" else "Temp",
                        sublabel = "${sample.batteryLevel}%",
                        accent = tempColor,
                        gaugeSize = gaugeSize,
                    )
                }
            }

            // Bottom real-time network speed capsule
            WidgetInnerPanel(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OpalText(
                            text = "↓",
                            color = colors.accentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        OpalText(
                            text = formatNetRate(sample.rxBytesPerSec),
                            color = colors.textPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OpalText(
                            text = "↑",
                            color = colors.accentEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        OpalText(
                            text = formatNetRate(sample.txBytesPerSec),
                            color = colors.textPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidArcGauge(
    progress: Float,
    valueText: String,
    label: String,
    sublabel: String?,
    accent: Color,
    gaugeSize: Dp,
) {
    val colors = LocalOpalColors.current
    val clamped = progress.coerceIn(0f, 1f)
    val trackColor = if (colors.isDark) Color(0x28FFFFFF) else Color(0x240F172A)
    val startAngle = 135f
    val maxSweep = 270f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier.size(gaugeSize),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = (size.minDimension * 0.11f).coerceAtLeast(4.5.dp.toPx())
                val inset = strokePx * 0.65f
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val topLeft = Offset(inset, inset)

                drawArc(
                    color = trackColor,
                    startAngle = startAngle,
                    sweepAngle = maxSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                val sweep = (maxSweep * clamped).coerceAtLeast(4f)
                drawArc(
                    brush = Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.65f), accent),
                    ),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                val tipAngleRad = (startAngle + sweep) * (PI / 180.0)
                val cx = size.width * 0.5f + cos(tipAngleRad).toFloat() * (arcSize.width * 0.5f)
                val cy = size.height * 0.5f + sin(tipAngleRad).toFloat() * (arcSize.height * 0.5f)
                drawCircle(
                    color = accent.copy(alpha = 0.32f),
                    radius = strokePx * 0.95f,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.92f),
                    radius = strokePx * 0.32f,
                    center = Offset(cx, cy),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OpalText(
                    text = valueText,
                    color = colors.textPrimary,
                    fontSize = if (gaugeSize >= 80.dp) 15.sp else 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                OpalText(
                    text = label,
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }
        if (!sublabel.isNullOrBlank()) {
            OpalText(
                text = sublabel,
                color = colors.textSecondary,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

private fun collectSystemMetrics(
    context: Context,
    rxBytesPerSec: Long,
    txBytesPerSec: Long,
): SystemMonitorSample {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am?.getMemoryInfo(memInfo)
    val totalMem = memInfo.totalMem.coerceAtLeast(1L)
    val usedMem = (totalMem - memInfo.availMem).coerceIn(0L, totalMem)
    val ramPct = (usedMem.toDouble() / totalMem.toDouble()).toFloat().coerceIn(0f, 1f)
    val gib = 1024f * 1024f * 1024f

    var storagePct = 0f
    var storageUsedGb = 0f
    var storageTotalGb = 0f
    runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes.coerceAtLeast(1L)
        val availBytes = stat.availableBytes.coerceIn(0L, totalBytes)
        val usedBytes = totalBytes - availBytes
        storagePct = (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        storageUsedGb = usedBytes / gib
        storageTotalGb = totalBytes / gib
    }

    var tempC = 0f
    var levelPct = 100
    runCatching {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val tempTenths = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            if (tempTenths > 0) tempC = tempTenths / 10f
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level >= 0) levelPct = ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
        }
    }

    return SystemMonitorSample(
        ramPercent = ramPct,
        ramUsedGb = usedMem / gib,
        ramTotalGb = totalMem / gib,
        storagePercent = storagePct,
        storageUsedGb = storageUsedGb,
        storageTotalGb = storageTotalGb,
        rxBytesPerSec = rxBytesPerSec,
        txBytesPerSec = txBytesPerSec,
        batteryTempC = tempC,
        batteryLevel = levelPct,
    )
}

private fun safeTrafficBytes(reader: () -> Long): Long = runCatching {
    val value = reader()
    if (value == TrafficStats.UNSUPPORTED.toLong() || value < 0L) -1L else value
}.getOrDefault(-1L)

private fun formatNetRate(bytesPerSec: Long): String {
    val safe = bytesPerSec.coerceAtLeast(0L)
    return when {
        safe >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", safe / (1024.0 * 1024.0))
        safe >= 1024L -> String.format(Locale.US, "%.0f KB/s", safe / 1024.0)
        else -> "$safe B/s"
    }
}

private fun metricTintColor(
    progress: Float,
    normal: Color,
    warn: Color,
    high: Color,
): Color = when {
    progress >= 0.85f -> high
    progress >= 0.70f -> warn
    else -> normal
}

// ---------------------------------------------------------------------------
// Minimalist Liquid Glass Monthly Calendar Widget
// ---------------------------------------------------------------------------

private data class CalendarDayCell(
    val day: Int,
    val inCurrentMonth: Boolean,
    val isToday: Boolean,
    val isWeekend: Boolean,
)

@Composable
fun CalendarWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val accent = Color(0xFF5E5CE6)
    val todayCal = remember { Calendar.getInstance() }
    val todayYear = todayCal.get(Calendar.YEAR)
    val todayMonth = todayCal.get(Calendar.MONTH)
    val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)

    var monthOffset by remember(instance.id) { mutableIntStateOf(0) }
    var selectedDay by remember(instance.id, monthOffset) { mutableIntStateOf(-1) }

    val displayCal = remember(monthOffset, todayYear, todayMonth) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, todayYear)
            set(Calendar.MONTH, todayMonth)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, monthOffset)
        }
    }
    val displayYear = displayCal.get(Calendar.YEAR)
    val displayMonth = displayCal.get(Calendar.MONTH)
    val isCurrentMonth = displayYear == todayYear && displayMonth == todayMonth

    val cells = remember(displayYear, displayMonth, isZh, todayYear, todayMonth, todayDay) {
        buildMonthGridCells(
            year = displayYear,
            month = displayMonth,
            mondayFirst = isZh,
            todayYear = todayYear,
            todayMonth = todayMonth,
            todayDay = todayDay,
        )
    }
    val weekHeaders = remember(isZh) {
        if (isZh) listOf("一", "二", "三", "四", "五", "六", "日")
        else listOf("S", "M", "T", "W", "T", "F", "S")
    }
    val monthTitle = remember(displayYear, displayMonth, isZh) {
        if (isZh) {
            "${displayYear}年 ${displayMonth + 1}月"
        } else {
            val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            "${names[displayMonth.coerceIn(0, 11)]} $displayYear"
        }
    }
    val isExpanded = sizeClass == WidgetSizeClass.EXPANDED

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = accent,
            centerFractionX = 0.85f,
            centerFractionY = 0.14f,
            radiusFraction = 0.74f,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (isExpanded) 8.dp else 5.dp),
        ) {
            // Header: Month title + Today button + Prev/Next month buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = monthTitle,
                    color = colors.textPrimary,
                    fontSize = if (isExpanded) 15.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (!isCurrentMonth) accent.copy(alpha = if (colors.isDark) 0.28f else 0.18f)
                                else OpalPalette.ChipFill,
                            )
                            .border(
                                0.75.dp,
                                if (!isCurrentMonth) accent.copy(alpha = 0.55f) else colors.borderHairline,
                                CircleShape,
                            )
                            .clickable {
                                monthOffset = 0
                                selectedDay = -1
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        OpalText(
                            text = if (isZh) "今天" else "Today",
                            color = if (!isCurrentMonth) accent else colors.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    CalendarNavButton(label = "‹", onClick = { monthOffset -= 1 })
                    CalendarNavButton(label = "›", onClick = { monthOffset += 1 })
                }
            }

            // Weekday header row
            Row(modifier = Modifier.fillMaxWidth()) {
                weekHeaders.forEachIndexed { idx, dayName ->
                    val isWeekendCol = if (isZh) idx >= 5 else (idx == 0 || idx == 6)
                    OpalText(
                        text = dayName,
                        color = if (isWeekendCol) accent.copy(alpha = 0.85f) else colors.textMuted,
                        fontSize = if (isExpanded) 11.sp else 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Month day grid (5 or 6 rows)
            val rows = remember(cells) { cells.chunked(7) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { week ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        week.forEach { cell ->
                            val isSelected = cell.inCurrentMonth && cell.day == selectedDay && !cell.isToday
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = cell.inCurrentMonth) {
                                        selectedDay = if (selectedDay == cell.day) -1 else cell.day
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (cell.isToday) {
                                    Box(
                                        modifier = Modifier
                                            .size(if (isExpanded) 30.dp else 23.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.radialGradient(
                                                    listOf(
                                                        OpalPalette.AccentBlue,
                                                        accent,
                                                    ),
                                                ),
                                            )
                                            .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        OpalText(
                                            text = cell.day.toString(),
                                            color = Color.White,
                                            fontSize = if (isExpanded) 12.sp else 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                        )
                                    }
                                } else {
                                    val bg = if (isSelected) {
                                        accent.copy(alpha = if (colors.isDark) 0.24f else 0.14f)
                                    } else {
                                        Color.Transparent
                                    }
                                    val border = if (isSelected) {
                                        accent.copy(alpha = 0.50f)
                                    } else {
                                        Color.Transparent
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(if (isExpanded) 28.dp else 22.dp)
                                            .clip(CircleShape)
                                            .background(bg)
                                            .border(0.8.dp, border, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val textColor = when {
                                            !cell.inCurrentMonth -> colors.textMuted.copy(alpha = 0.34f)
                                            isSelected -> accent
                                            cell.isWeekend -> colors.textSecondary
                                            else -> colors.textPrimary
                                        }
                                        OpalText(
                                            text = cell.day.toString(),
                                            color = textColor,
                                            fontSize = if (isExpanded) 11.5.sp else 10.sp,
                                            fontWeight = if (cell.inCurrentMonth) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarNavButton(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalOpalColors.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (colors.isDark) Color(0x22FFFFFF) else Color(0x70FFFFFF))
            .border(0.75.dp, colors.borderHairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private fun buildMonthGridCells(
    year: Int,
    month: Int,
    mondayFirst: Boolean,
    todayYear: Int,
    todayMonth: Int,
    todayDay: Int,
): List<CalendarDayCell> {
    val firstCal = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val daysInMonth = firstCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDow = firstCal.get(Calendar.DAY_OF_WEEK) // Sun=1..Sat=7
    val leadingBlanks = if (mondayFirst) {
        (firstDow - Calendar.MONDAY + 7) % 7
    } else {
        firstDow - Calendar.SUNDAY
    }

    val prevCal = (firstCal.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
    val prevMonthDays = prevCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val totalSlots = if (leadingBlanks + daysInMonth > 35) 42 else 35
    val out = ArrayList<CalendarDayCell>(totalSlots)

    for (slot in 0 until totalSlots) {
        val col = slot % 7
        val isWeekend = if (mondayFirst) col >= 5 else (col == 0 || col == 6)
        when {
            slot < leadingBlanks -> {
                val day = prevMonthDays - leadingBlanks + 1 + slot
                out.add(CalendarDayCell(day = day, inCurrentMonth = false, isToday = false, isWeekend = isWeekend))
            }
            slot < leadingBlanks + daysInMonth -> {
                val day = slot - leadingBlanks + 1
                val isToday = year == todayYear && month == todayMonth && day == todayDay
                out.add(CalendarDayCell(day = day, inCurrentMonth = true, isToday = isToday, isWeekend = isWeekend))
            }
            else -> {
                val day = slot - leadingBlanks - daysInMonth + 1
                out.add(CalendarDayCell(day = day, inCurrentMonth = false, isToday = false, isWeekend = isWeekend))
            }
        }
    }
    return out
}
