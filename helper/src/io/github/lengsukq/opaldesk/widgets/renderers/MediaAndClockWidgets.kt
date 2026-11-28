package io.github.lengsukq.opaldesk.widgets.renderers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun MediaPlayerWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    media: WidgetMediaSnapshot,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val accent = if (media.isPlaying) colors.accentBlue else colors.accentCyan
    val trackTitle = media.title.ifBlank {
        if (isZh) "暂无媒体播放" else "Nothing Playing"
    }
    val trackArtist = when {
        media.artist.isNotBlank() -> media.artist
        media.hasSession -> if (isZh) "未知艺术家" else "Unknown Artist"
        else -> if (isZh) "打开音乐应用即可同步控制" else "Play audio on phone to control"
    }
    val appLabel = media.appName.ifBlank {
        if (isZh) "媒体中心" else "Now Playing"
    }

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = accent,
            centerFractionX = 0.18f,
            centerFractionY = 0.22f,
            radiusFraction = 0.80f,
        )
        when (sizeClass) {
            WidgetSizeClass.COMPACT -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MediaArtworkCover(
                        bitmap = media.artworkBitmap,
                        accent = accent,
                        isPlaying = media.isPlaying,
                        size = 50.dp,
                        cornerRadius = 14.dp,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        OpalText(
                            text = trackTitle,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        OpalText(
                            text = trackArtist,
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                    MediaTransportRow(
                        isPlaying = media.isPlaying,
                        accent = accent,
                        buttonSize = 28.dp,
                        primarySize = 34.dp,
                        callbacks = callbacks,
                        isZh = isZh,
                    )
                }
            }

            WidgetSizeClass.MEDIUM -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MediaArtworkCover(
                            bitmap = media.artworkBitmap,
                            accent = accent,
                            isPlaying = media.isPlaying,
                            size = 64.dp,
                            cornerRadius = 16.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            WidgetGlassBadge(
                                text = appLabel,
                                accent = if (media.hasSession) accent else null,
                                dotColor = if (media.isPlaying) colors.accentEmerald else null,
                            )
                            OpalText(
                                text = trackTitle,
                                color = colors.textPrimary,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            OpalText(
                                text = trackArtist,
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MediaRhythmVisualizer(
                            isPlaying = media.isPlaying,
                            accent = accent,
                            barCount = 12,
                            modifier = Modifier
                                .weight(1f)
                                .height(18.dp)
                                .padding(end = 12.dp),
                        )
                        MediaTransportRow(
                            isPlaying = media.isPlaying,
                            accent = accent,
                            buttonSize = 32.dp,
                            primarySize = 38.dp,
                            callbacks = callbacks,
                            isZh = isZh,
                        )
                    }
                }
            }

            WidgetSizeClass.EXPANDED -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MediaArtworkCover(
                            bitmap = media.artworkBitmap,
                            accent = accent,
                            isPlaying = media.isPlaying,
                            size = 82.dp,
                            cornerRadius = 16.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            WidgetGlassBadge(
                                text = appLabel,
                                accent = if (media.hasSession) accent else null,
                                dotColor = if (media.isPlaying) colors.accentEmerald else null,
                            )
                            OpalText(
                                text = trackTitle,
                                color = colors.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                            )
                            OpalText(
                                text = trackArtist,
                                color = colors.textSecondary,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                            )
                        }
                    }
                    MediaRhythmVisualizer(
                        isPlaying = media.isPlaying,
                        accent = accent,
                        barCount = 24,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        WidgetGlassActionButton(
                            onClick = callbacks.onToggleMute,
                            label = if (isZh) "静音" else "Mute",
                            circular = false,
                            size = 34.dp,
                            icon = { tint -> WidgetGlyphIcon(WidgetGlyph.VOLUME_MUTE, tint, 14.dp) },
                        )
                        MediaTransportRow(
                            isPlaying = media.isPlaying,
                            accent = accent,
                            buttonSize = 36.dp,
                            primarySize = 44.dp,
                            callbacks = callbacks,
                            isZh = isZh,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaArtworkCover(
    bitmap: android.graphics.Bitmap?,
    accent: Color,
    isPlaying: Boolean,
    size: Dp,
    cornerRadius: Dp,
) {
    val colors = LocalOpalColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = if (colors.isDark) 0.42f else 0.30f),
                        colors.accentCyan.copy(alpha = if (colors.isDark) 0.26f else 0.22f),
                        if (colors.isDark) Color(0xFF1A2030) else Color(0xFFDDE8FA),
                    ),
                ),
            )
            .border(
                width = 0.9.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (colors.isDark) 0.44f else 0.90f),
                        accent.copy(alpha = 0.24f),
                    ),
                ),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Glass vinyl disc rings + luminous note
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val maxR = this.size.minDimension * 0.42f
                drawCircle(
                    color = Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.35f),
                    radius = maxR,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = Color.White.copy(alpha = if (colors.isDark) 0.08f else 0.28f),
                    radius = maxR * 0.68f,
                    center = center,
                    style = Stroke(width = 0.85.dp.toPx()),
                )
            }
            WidgetGlyphIcon(
                glyph = WidgetGlyph.MUSIC_NOTE,
                tint = if (colors.isDark) Color.White.copy(alpha = 0.92f) else accent,
                size = size * 0.36f,
            )
        }
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.accentEmerald)
                    .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape),
            )
        }
    }
}

@Composable
private fun MediaTransportRow(
    isPlaying: Boolean,
    accent: Color,
    buttonSize: Dp,
    primarySize: Dp,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WidgetGlassActionButton(
            onClick = callbacks.onMediaPrev,
            circular = true,
            size = buttonSize,
            contentDescription = if (isZh) "上一首" else "Previous",
            icon = { tint -> WidgetGlyphIcon(WidgetGlyph.PREV, tint, buttonSize * 0.44f) },
        )
        WidgetGlassActionButton(
            onClick = callbacks.onMediaPlayPause,
            accent = accent,
            filled = true,
            circular = true,
            size = primarySize,
            contentDescription = if (isPlaying) {
                if (isZh) "暂停" else "Pause"
            } else {
                if (isZh) "播放" else "Play"
            },
            icon = { tint ->
                WidgetGlyphIcon(
                    glyph = if (isPlaying) WidgetGlyph.PAUSE else WidgetGlyph.PLAY,
                    tint = tint,
                    size = primarySize * 0.44f,
                )
            },
        )
        WidgetGlassActionButton(
            onClick = callbacks.onMediaNext,
            circular = true,
            size = buttonSize,
            contentDescription = if (isZh) "下一首" else "Next",
            icon = { tint -> WidgetGlyphIcon(WidgetGlyph.NEXT, tint, buttonSize * 0.44f) },
        )
    }
}

@Composable
private fun MediaRhythmVisualizer(
    isPlaying: Boolean,
    accent: Color,
    barCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val transition = rememberInfiniteTransition(label = "media_rhythm")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rhythm_phase",
    )

    Canvas(modifier = modifier) {
        val count = barCount.coerceAtLeast(4)
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2.dp.toPx())
        val radius = CornerRadius(barW / 2f, barW / 2f)
        for (i in 0 until count) {
            val norm = i.toFloat() / count.toFloat()
            val wave = if (isPlaying) {
                val s1 = sin(phase + i * 0.65f)
                val s2 = sin(phase * 1.4f - i * 0.35f)
                (0.32f + 0.64f * ((s1 * 0.6f + s2 * 0.4f + 1f) * 0.5f)).coerceIn(0.18f, 1f)
            } else {
                0.22f + 0.14f * ((sin(i * 0.8f) + 1f) * 0.5f)
            }
            val barH = size.height * wave
            val top = (size.height - barH) * 0.5f
            val barColor = if (isPlaying) {
                accent.copy(alpha = 0.42f + 0.48f * (1f - kotlin.math.abs(norm - 0.5f) * 1.4f).coerceIn(0f, 1f))
            } else {
                colors.textMuted.copy(alpha = 0.28f)
            }
            drawRoundRect(
                color = barColor,
                topLeft = Offset(i * (barW + gap), top),
                size = Size(barW, barH),
                cornerRadius = radius,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Clock & Pomodoro Focus Timer Widget
// ---------------------------------------------------------------------------

private const val POMODORO_FOCUS_SECONDS = 25 * 60
private const val POMODORO_BREAK_SECONDS = 5 * 60

@Composable
fun ClockTimerWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    var isTimerMode by rememberSaveable(instance.id) { mutableStateOf(false) }
    var isBreakPhase by rememberSaveable(instance.id) { mutableStateOf(false) }
    var remainingSeconds by rememberSaveable(instance.id) { mutableIntStateOf(POMODORO_FOCUS_SECONDS) }
    var isTimerRunning by rememberSaveable(instance.id) { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    LaunchedEffect(isTimerRunning, isBreakPhase) {
        if (!isTimerRunning) return@LaunchedEffect
        while (isTimerRunning && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds = (remainingSeconds - 1).coerceAtLeast(0)
        }
        if (remainingSeconds == 0) {
            isTimerRunning = false
        }
    }

    val accent = when {
        !isTimerMode -> colors.accentBlue
        isBreakPhase -> colors.accentEmerald
        else -> colors.accentAmber
    }

    val locale = if (isZh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
    val timeText = remember(nowMs, sizeClass) {
        val pattern = if (sizeClass == WidgetSizeClass.EXPANDED) "HH:mm:ss" else "HH:mm"
        SimpleDateFormat(pattern, Locale.US).format(Date(nowMs))
    }
    val dateText = remember(nowMs, isZh) {
        val pattern = if (isZh) "M月d日 · EEEE" else "EEE, MMM d"
        SimpleDateFormat(pattern, locale).format(Date(nowMs))
    }
    val timerTotal = if (isBreakPhase) POMODORO_BREAK_SECONDS else POMODORO_FOCUS_SECONDS
    val timerProgress = ((timerTotal - remainingSeconds).toFloat() / timerTotal.toFloat()).coerceIn(0f, 1f)
    val timerText = remember(remainingSeconds) {
        val mins = remainingSeconds / 60
        val secs = remainingSeconds % 60
        "%02d:%02d".format(Locale.US, mins, secs)
    }

    val displayFontSize = when (sizeClass) {
        WidgetSizeClass.COMPACT -> 30.sp
        WidgetSizeClass.MEDIUM -> 38.sp
        WidgetSizeClass.EXPANDED -> 44.sp
    }

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = accent,
            centerFractionX = 0.84f,
            centerFractionY = 0.18f,
            radiusFraction = 0.78f,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top mode pill row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                WidgetGlassBadge(
                    text = when {
                        !isTimerMode -> if (isZh) "时钟" else "Clock"
                        isBreakPhase -> if (isZh) "休息 5m" else "Break 5m"
                        else -> if (isZh) "专注 25m" else "Focus 25m"
                    },
                    accent = accent,
                    leadingIcon = { tint ->
                        WidgetGlyphIcon(
                            glyph = if (isTimerMode) WidgetGlyph.TIMER else WidgetGlyph.CLOCK,
                            tint = tint,
                            size = 11.dp,
                        )
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isTimerMode && sizeClass != WidgetSizeClass.COMPACT) {
                        WidgetGlassActionButton(
                            onClick = {
                                isBreakPhase = !isBreakPhase
                                isTimerRunning = false
                                remainingSeconds = if (isBreakPhase) POMODORO_BREAK_SECONDS else POMODORO_FOCUS_SECONDS
                            },
                            label = if (isBreakPhase) {
                                if (isZh) "25m 专注" else "25m Focus"
                            } else {
                                if (isZh) "5m 休息" else "5m Break"
                            },
                            circular = false,
                            size = 26.dp,
                        )
                    }
                    WidgetGlassActionButton(
                        onClick = { isTimerMode = !isTimerMode },
                        label = if (isTimerMode) {
                            if (isZh) "时钟" else "Clock"
                        } else {
                            if (isZh) "番茄钟" else "Focus"
                        },
                        accent = accent,
                        active = isTimerMode,
                        circular = false,
                        size = 26.dp,
                    )
                }
            }

            // Hero monospace time / countdown
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                OpalText(
                    text = if (isTimerMode) timerText else timeText,
                    color = colors.textPrimary,
                    fontSize = displayFontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                if (!isTimerMode) {
                    OpalText(
                        text = dateText,
                        color = colors.textSecondary,
                        fontSize = if (sizeClass == WidgetSizeClass.COMPACT) 11.5.sp else 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(colors.segmentedTrack),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(timerProgress.coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(accent),
                        )
                    }
                }
            }

            // Footer controls for timer mode or subtle ambient hint in clock mode
            if (isTimerMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WidgetGlassActionButton(
                        onClick = {
                            if (remainingSeconds == 0) {
                                remainingSeconds = timerTotal
                            }
                            isTimerRunning = !isTimerRunning
                        },
                        label = if (isTimerRunning) {
                            if (isZh) "暂停" else "Pause"
                        } else {
                            if (isZh) "开始" else "Start"
                        },
                        accent = accent,
                        filled = true,
                        circular = false,
                        size = 30.dp,
                        modifier = Modifier.weight(1f),
                        icon = { tint ->
                            WidgetGlyphIcon(
                                glyph = if (isTimerRunning) WidgetGlyph.PAUSE else WidgetGlyph.PLAY,
                                tint = tint,
                                size = 12.dp,
                            )
                        },
                    )
                    WidgetGlassActionButton(
                        onClick = {
                            isTimerRunning = false
                            remainingSeconds = timerTotal
                        },
                        circular = true,
                        size = 30.dp,
                        contentDescription = if (isZh) "重置" else "Reset",
                        icon = { tint -> WidgetGlyphIcon(WidgetGlyph.RESET, tint, 13.dp) },
                    )
                }
            } else if (sizeClass == WidgetSizeClass.EXPANDED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OpalText(
                        text = if (isZh) "点击右上角切换番茄专注计时" else "Switch to Pomodoro focus timer above",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
