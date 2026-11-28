package io.github.lengsukq.opaldesk.widgets.renderers

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.WidgetSizeClass
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Shared Luminous Gradient Clock Typography (macOS / HyperOS / iOS Grade)
// ---------------------------------------------------------------------------

@Composable
internal fun LiquidGradientClockDigits(
    hours: String,
    minutes: String,
    seconds: String,
    amPm: String,
    digitSize: TextUnit,
    accent: Color,
    showSecondsBadge: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val pulse by rememberInfiniteTransition(label = "clock_colon_breath").animateFloat(
        initialValue = 0.28f,
        targetValue = 0.96f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "clock_colon_alpha",
    )
    val digitBrush = if (colors.isDark) {
        Brush.verticalGradient(
            listOf(Color(0xFFFFFFFF), Color(0xFFEAF2FF), Color(0xFFB3C6E7)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xFF0B1324), Color(0xFF223454), Color(0xFF476089)),
        )
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = hours,
            maxLines = 1,
            style = TextStyle(
                brush = digitBrush,
                fontSize = digitSize,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1.5).sp,
                fontFeatureSettings = "tnum",
            ),
        )
        // Breathing vertical dots colon
        Column(
            modifier = Modifier
                .padding(horizontal = (digitSize.value * 0.08f).coerceIn(2.5f, 6f).dp)
                .alpha(pulse),
            verticalArrangement = Arrangement.spacedBy((digitSize.value * 0.16f).coerceIn(4f, 9f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val dotSize = (digitSize.value * 0.095f).coerceIn(3.5f, 5.5f).dp
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (colors.isDark) 0.92f else 0.78f)),
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (colors.isDark) 0.78f else 0.65f)),
            )
        }
        BasicText(
            text = minutes,
            maxLines = 1,
            style = TextStyle(
                brush = digitBrush,
                fontSize = digitSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1.2).sp,
                fontFeatureSettings = "tnum",
            ),
        )
        if (showSecondsBadge) {
            Spacer(Modifier.width(6.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                OpalText(
                    text = amPm,
                    color = colors.textMuted,
                    fontSize = (digitSize.value * 0.20f).coerceIn(9f, 11.5f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                        .border(0.7.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.5.dp, vertical = 1.dp),
                ) {
                    BasicText(
                        text = seconds,
                        maxLines = 1,
                        style = TextStyle(
                            color = accent,
                            fontSize = (digitSize.value * 0.23f).coerceIn(10f, 13f).sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Digital Clock + Hitokoto (每日一言) Face
// ---------------------------------------------------------------------------

@Composable
internal fun LiquidDigitalClockFace(
    hours: String,
    minutes: String,
    seconds: String,
    amPm: String,
    dateText: String,
    quote: HitokotoQuote,
    accent: Color,
    sizeClass: WidgetSizeClass,
    isZh: Boolean,
    onSwitchDial: () -> Unit,
    onRefreshQuote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val tightPortraitCard = maxHeight < 148.dp || maxWidth < 290.dp
        val digitSize = when {
            sizeClass == WidgetSizeClass.COMPACT -> 36.sp
            tightPortraitCard -> 40.sp
            sizeClass == WidgetSizeClass.MEDIUM -> 48.sp
            else -> 56.sp
        }
        val compactQuote = sizeClass == WidgetSizeClass.COMPACT || tightPortraitCard

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Center-weighted Hero Time + Date Capsule (tap to toggle Analog dial)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onSwitchDial)
                    .padding(vertical = if (compactQuote) 0.dp else 2.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactQuote) 3.dp else 6.dp),
            ) {
                LiquidGradientClockDigits(
                    hours = hours,
                    minutes = minutes,
                    seconds = seconds,
                    amPm = amPm,
                    digitSize = digitSize,
                    accent = accent,
                    showSecondsBadge = true,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WidgetGlassBadge(
                        text = dateText,
                        dotColor = accent,
                    )
                    if (!compactQuote) {
                        OpalText(
                            text = if (isZh) "轻触切模拟表盘" else "Tap for analog",
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Integrated Hitokoto (每日一言) Glass Quote Bar — eliminates empty void
            HitokotoGlassCard(
                quote = quote,
                accent = accent,
                compact = compactQuote,
                onRefresh = onRefreshQuote,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Liquid Glass Analog Clock Dial Face
// ---------------------------------------------------------------------------

@Composable
internal fun LiquidAnalogClockFace(
    nowMs: Long,
    digitalSummary: String,
    dateText: String,
    quote: HitokotoQuote,
    pomodoroSummary: String,
    accent: Color,
    sizeClass: WidgetSizeClass,
    onSwitchDial: () -> Unit,
    onOpenPomodoro: () -> Unit,
    onRefreshQuote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    if (sizeClass == WidgetSizeClass.COMPACT) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(onClick = onSwitchDial),
            contentAlignment = Alignment.Center,
        ) {
            LiquidAnalogDialCanvas(
                nowMs = nowMs,
                accent = accent,
                showMinuteTicks = false,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .padding(2.dp),
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .clickable(onClick = onSwitchDial)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                LiquidAnalogDialCanvas(
                    nowMs = nowMs,
                    accent = accent,
                    showMinuteTicks = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    BasicText(
                        text = digitalSummary,
                        maxLines = 1,
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = if (sizeClass == WidgetSizeClass.EXPANDED) 24.sp else 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    WidgetGlassBadge(text = dateText, dotColor = accent)
                }
                HitokotoGlassCard(
                    quote = quote,
                    accent = accent,
                    compact = true,
                    onRefresh = onRefreshQuote,
                )
                WidgetGlassActionButton(
                    onClick = onOpenPomodoro,
                    label = pomodoroSummary,
                    accent = colors.accentAmber,
                    active = false,
                    circular = false,
                    size = 28.dp,
                    icon = { tint -> WidgetGlyphIcon(WidgetGlyph.TIMER, tint, 12.dp) },
                )
            }
        }
    }
}

@Composable
private fun LiquidAnalogDialCanvas(
    nowMs: Long,
    accent: Color,
    showMinuteTicks: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val sec = cal.get(Calendar.SECOND)
    val min = cal.get(Calendar.MINUTE) + sec / 60f
    val hour = (cal.get(Calendar.HOUR) % 12) + min / 60f

    val secAngle = (sec * 6f - 90f) * (PI / 180.0)
    val minAngle = (min * 6f - 90f) * (PI / 180.0)
    val hourAngle = (hour * 30f - 90f) * (PI / 180.0)
    val secondHandColor = colors.accentAmber

    Canvas(modifier = modifier) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val center = Offset(cx, cy)
        val radius = size.minDimension * 0.48f

        // Translucent frosted glass dial dome
        drawCircle(
            brush = Brush.radialGradient(
                colors = if (colors.isDark) {
                    listOf(Color(0x36FFFFFF), Color(0x181B253A), Color(0x420A0E18))
                } else {
                    listOf(Color(0xCCFFFFFF), Color(0x94EEF3FC), Color(0x6AD7E4F8))
                },
                center = Offset(cx - radius * 0.25f, cy - radius * 0.28f),
                radius = radius * 1.45f,
            ),
            radius = radius,
            center = center,
        )
        // Outer luminous refractive rim
        drawCircle(
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (colors.isDark) 0.50f else 0.96f),
                    accent.copy(alpha = 0.36f),
                    colors.borderHairline,
                ),
            ),
            radius = radius,
            center = center,
            style = Stroke(width = 1.3.dp.toPx()),
        )
        // Subtle inner glow ring
        drawCircle(
            color = accent.copy(alpha = if (colors.isDark) 0.14f else 0.10f),
            radius = radius * 0.88f,
            center = center,
            style = Stroke(width = 0.75.dp.toPx()),
        )

        // Tick marks (12 hour ticks + optional 60 fine minute dots)
        for (i in 0 until 60) {
            val isHour = i % 5 == 0
            if (!isHour && !showMinuteTicks) continue
            val rad = (i * 6.0 - 90.0) * PI / 180.0
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            if (isHour) {
                val isCardinal = i % 15 == 0
                val outer = radius * 0.86f
                val inner = radius * if (isCardinal) 0.72f else 0.77f
                drawLine(
                    color = if (isCardinal) colors.textPrimary.copy(alpha = 0.88f) else colors.textSecondary.copy(alpha = 0.56f),
                    start = Offset(cx + inner * cosA, cy + inner * sinA),
                    end = Offset(cx + outer * cosA, cy + outer * sinA),
                    strokeWidth = (if (isCardinal) 2.0f else 1.25f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
            } else {
                val rTick = radius * 0.82f
                drawCircle(
                    color = colors.textMuted.copy(alpha = 0.28f),
                    radius = 0.75.dp.toPx(),
                    center = Offset(cx + rTick * cosA, cy + rTick * sinA),
                )
            }
        }

        // Hour hand
        val hourLen = radius * 0.46f
        drawLine(
            color = colors.textPrimary,
            start = center,
            end = Offset(cx + (hourLen * cos(hourAngle)).toFloat(), cy + (hourLen * sin(hourAngle)).toFloat()),
            strokeWidth = 3.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Minute hand
        val minLen = radius * 0.66f
        drawLine(
            color = colors.textPrimary.copy(alpha = 0.92f),
            start = center,
            end = Offset(cx + (minLen * cos(minAngle)).toFloat(), cy + (minLen * sin(minAngle)).toFloat()),
            strokeWidth = 2.1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Second hand with counterweight tail + luminous tip
        val secTip = Offset(cx + (radius * 0.76f * cos(secAngle)).toFloat(), cy + (radius * 0.76f * sin(secAngle)).toFloat())
        val secTail = Offset(cx - (radius * 0.18f * cos(secAngle)).toFloat(), cy - (radius * 0.18f * sin(secAngle)).toFloat())
        drawLine(
            color = secondHandColor,
            start = secTail,
            end = secTip,
            strokeWidth = 1.25.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(secondHandColor.copy(alpha = 0.55f), Color.Transparent),
                center = secTip,
                radius = 5.5.dp.toPx(),
            ),
            radius = 5.5.dp.toPx(),
            center = secTip,
        )
        // Central glass rivet
        drawCircle(color = colors.textPrimary, radius = 3.4.dp.toPx(), center = center)
        drawCircle(color = secondHandColor, radius = 1.9.dp.toPx(), center = center)
        drawCircle(color = Color.White.copy(alpha = 0.9f), radius = 0.85.dp.toPx(), center = center)
    }
}

// ---------------------------------------------------------------------------
// Pomodoro Glowing Ring Progress Timer Face
// ---------------------------------------------------------------------------

@Composable
internal fun PomodoroRingTimerFace(
    timerText: String,
    progress: Float,
    isRunning: Boolean,
    isBreakPhase: Boolean,
    accent: Color,
    sizeClass: WidgetSizeClass,
    isZh: Boolean,
    onToggleRun: () -> Unit,
    onReset: () -> Unit,
    onTogglePhase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val phaseLabel = when {
        isRunning && isBreakPhase -> if (isZh) "休息中" else "Resting"
        isRunning -> if (isZh) "专注中" else "Focusing"
        isBreakPhase -> if (isZh) "准备休息" else "Break Ready"
        else -> if (isZh) "准备专注" else "Focus Ready"
    }

    if (sizeClass == WidgetSizeClass.COMPACT) {
        Row(
            modifier = modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PomodoroRingCanvas(
                timerText = timerText,
                phaseLabel = phaseLabel,
                progress = progress,
                accent = accent,
                compact = true,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WidgetGlassActionButton(
                    onClick = onToggleRun,
                    label = if (isRunning) (if (isZh) "暂停" else "Pause") else (if (isZh) "开始" else "Start"),
                    accent = accent,
                    filled = true,
                    circular = false,
                    size = 30.dp,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { tint -> WidgetGlyphIcon(if (isRunning) WidgetGlyph.PAUSE else WidgetGlyph.PLAY, tint, 12.dp) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WidgetGlassActionButton(
                        onClick = onTogglePhase,
                        label = if (isBreakPhase) "25m" else "5m",
                        circular = false,
                        size = 26.dp,
                        modifier = Modifier.weight(1f),
                    )
                    WidgetGlassActionButton(
                        onClick = onReset,
                        circular = true,
                        size = 26.dp,
                        contentDescription = if (isZh) "重置" else "Reset",
                        icon = { tint -> WidgetGlyphIcon(WidgetGlyph.RESET, tint, 12.dp) },
                    )
                }
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PomodoroRingCanvas(
                timerText = timerText,
                phaseLabel = phaseLabel,
                progress = progress,
                accent = accent,
                compact = false,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .padding(2.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                OpalText(
                    text = if (isBreakPhase) (if (isZh) "短暂休整 · 5 分钟" else "Short Break · 5 min")
                    else (if (isZh) "深度专注 · 25 分钟" else "Deep Focus · 25 min"),
                    color = colors.textPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                OpalText(
                    text = if (isZh) "已完成 ${(progress * 100).roundToInt()}%" else "${(progress * 100).roundToInt()}% complete",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WidgetGlassActionButton(
                        onClick = onToggleRun,
                        label = if (isRunning) (if (isZh) "暂停" else "Pause") else (if (isZh) "开始" else "Start"),
                        accent = accent,
                        filled = true,
                        circular = false,
                        size = 32.dp,
                        modifier = Modifier.weight(1f),
                        icon = { tint -> WidgetGlyphIcon(if (isRunning) WidgetGlyph.PAUSE else WidgetGlyph.PLAY, tint, 13.dp) },
                    )
                    WidgetGlassActionButton(
                        onClick = onReset,
                        circular = true,
                        size = 32.dp,
                        contentDescription = if (isZh) "重置" else "Reset",
                        icon = { tint -> WidgetGlyphIcon(WidgetGlyph.RESET, tint, 13.dp) },
                    )
                }
                Spacer(Modifier.height(7.dp))
                WidgetGlassActionButton(
                    onClick = onTogglePhase,
                    label = if (isBreakPhase) (if (isZh) "切回 25m 专注" else "Switch to 25m Focus")
                    else (if (isZh) "切换 5m 休息" else "Switch to 5m Break"),
                    circular = false,
                    size = 28.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PomodoroRingCanvas(
    timerText: String,
    phaseLabel: String,
    progress: Float,
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = (if (compact) 5.5f else 7.5f).dp.toPx()
            val inset = strokeW * 0.9f
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val ringR = arcSize.minDimension * 0.5f

            // Track ring
            drawArc(
                color = if (colors.isDark) Color(0x26FFFFFF) else Color(0x240F172A),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
            // Luminous progress arc
            val sweep = (clamped * 360f).coerceAtLeast(4f)
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(accent.copy(alpha = 0.65f), accent, Color.White.copy(alpha = 0.9f), accent),
                ),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
            // Glowing tip halo
            val tipRad = ((-90f + sweep) * PI / 180.0)
            val tip = Offset(cx + (ringR * cos(tipRad)).toFloat(), cy + (ringR * sin(tipRad)).toFloat())
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.62f), Color.Transparent),
                    center = tip,
                    radius = strokeW * 1.9f,
                ),
                radius = strokeW * 1.9f,
                center = tip,
            )
            drawCircle(color = Color.White, radius = strokeW * 0.34f, center = tip)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = timerText,
                maxLines = 1,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = if (compact) 16.sp else 23.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
            if (!compact) {
                OpalText(
                    text = phaseLabel,
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
