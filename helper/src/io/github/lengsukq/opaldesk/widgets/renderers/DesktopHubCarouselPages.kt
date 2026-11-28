package io.github.lengsukq.opaldesk.widgets.renderers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetCallbacks
import io.github.lengsukq.opaldesk.widgets.WidgetMediaSnapshot
import io.github.lengsukq.opaldesk.widgets.WidgetNotificationItem
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun HubNowPlayingPage(
    media: WidgetMediaSnapshot,
    callbacks: DesktopWidgetCallbacks,
    isCompact: Boolean,
    isZh: Boolean,
) {
    val colors = LocalOpalColors.current
    val accent = if (media.isPlaying) colors.accentBlue else colors.accentCyan
    val title = media.title.ifBlank { if (isZh) "暂无媒体播放" else "Nothing Playing" }
    val artist = when {
        media.artist.isNotBlank() -> media.artist
        media.hasSession -> if (isZh) "未知艺术家" else "Unknown Artist"
        else -> if (isZh) "在手机播放音乐即可在此实时控制" else "Play audio on phone to sync controls"
    }
    val sourceLabel = media.appName.ifBlank { if (isZh) "正在播放" else "Now Playing" }
    val artSize = if (isCompact) 72.dp else 94.dp

    WidgetInnerPanel(
        modifier = Modifier.fillMaxSize(),
        cornerRadius = 20.dp,
        accent = accent,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(artSize)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = 0.42f),
                                colors.accentCyan.copy(alpha = 0.24f),
                            ),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = media.artworkBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val c = Offset(size.width * 0.5f, size.height * 0.5f)
                        drawCircle(Color(0x660A0E18), radius = size.minDimension * 0.40f, center = c)
                        drawCircle(Color.White.copy(alpha = 0.24f), radius = size.minDimension * 0.30f, center = c, style = Stroke(1.2.dp.toPx()))
                        drawCircle(Color.White.copy(alpha = 0.16f), radius = size.minDimension * 0.20f, center = c, style = Stroke(1.dp.toPx()))
                        drawCircle(accent, radius = size.minDimension * 0.11f, center = c)
                        drawCircle(Color.White.copy(alpha = 0.85f), radius = size.minDimension * 0.035f, center = c)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WidgetGlassBadge(
                        text = sourceLabel,
                        accent = if (media.hasSession) accent else null,
                        dotColor = if (media.isPlaying) colors.accentEmerald else null,
                    )
                    OpalText(
                        text = title,
                        color = colors.textPrimary,
                        fontSize = if (isCompact) 14.5.sp else 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    OpalText(
                        text = artist,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WidgetGlassActionButton(
                        onClick = callbacks.onMediaPrev,
                        size = 34.dp,
                        accent = accent,
                        contentDescription = if (isZh) "上一首" else "Previous",
                        icon = { tint -> WidgetGlyphIcon(WidgetGlyph.PREV, tint, 14.dp) },
                    )
                    WidgetGlassActionButton(
                        onClick = callbacks.onMediaPlayPause,
                        size = 40.dp,
                        accent = accent,
                        filled = true,
                        contentDescription = if (media.isPlaying) {
                            if (isZh) "暂停" else "Pause"
                        } else {
                            if (isZh) "播放" else "Play"
                        },
                        icon = { tint ->
                            WidgetGlyphIcon(
                                glyph = if (media.isPlaying) WidgetGlyph.PAUSE else WidgetGlyph.PLAY,
                                tint = tint,
                                size = 16.dp,
                            )
                        },
                    )
                    WidgetGlassActionButton(
                        onClick = callbacks.onMediaNext,
                        size = 34.dp,
                        accent = accent,
                        contentDescription = if (isZh) "下一首" else "Next",
                        icon = { tint -> WidgetGlyphIcon(WidgetGlyph.NEXT, tint, 14.dp) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun HubAlertsOtpPage(
    instanceId: String,
    notifications: List<WidgetNotificationItem>,
    callbacks: DesktopWidgetCallbacks,
    isCompact: Boolean,
    isZh: Boolean,
) {
    val colors = LocalOpalColors.current
    val sorted = remember(notifications) { notifications.sortedByDescending { it.timestampMs } }
    val otpMatch = remember(sorted) {
        sorted.firstNotNullOfOrNull { item ->
            extractOtpCode("${item.title} ${item.text}")?.let { it to item }
        }
    }
    var copiedCode by remember(instanceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedCode) {
        if (copiedCode != null) {
            delay(2200L)
            copiedCode = null
        }
    }

    WidgetInnerPanel(
        modifier = Modifier.fillMaxSize(),
        cornerRadius = 20.dp,
        accent = if (otpMatch != null) colors.accentEmerald else colors.accentAmber,
        contentPadding = PaddingValues(12.dp),
    ) {
        if (otpMatch != null) {
            val (code, src) = otpMatch
            val copied = copiedCode == code
            val formatted = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accentEmerald.copy(alpha = if (colors.isDark) 0.24f else 0.16f))
                    .border(0.9.dp, colors.accentEmerald.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(
                        text = if (isZh) "验证码速贴 · ${src.appName}" else "Quick OTP · ${src.appName}",
                        color = colors.accentEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    OpalText(
                        text = formatted,
                        color = colors.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                WidgetGlassActionButton(
                    onClick = {
                        callbacks.onCopyText(code)
                        copiedCode = code
                    },
                    label = if (copied) (if (isZh) "已复制" else "Copied") else (if (isZh) "一键复制" else "Copy"),
                    accent = colors.accentEmerald,
                    filled = true,
                    circular = false,
                    size = 29.dp,
                    icon = { tint -> WidgetGlyphIcon(if (copied) WidgetGlyph.CHECK else WidgetGlyph.COPY, tint, 12.dp) },
                )
            }
            Spacer(Modifier.height(7.dp))
        }

        if (sorted.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                OpalText(
                    text = if (isZh) "暂无新通知 · 收到验证码将自动置顶" else "No new alerts · OTP codes auto-pin here",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        } else {
            val limit = if (otpMatch != null || isCompact) 1 else 2
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sorted.take(limit).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (colors.isDark) Color(0x1EFFFFFF) else Color(0x6CFFFFFF))
                            .border(0.75.dp, colors.borderHairline, RoundedCornerShape(13.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WidgetGlyphIcon(WidgetGlyph.BELL, colors.accentAmber, 13.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            OpalText(
                                text = "${item.appName}  ${item.title}".trim(),
                                color = colors.textPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            if (item.text.isNotBlank()) {
                                OpalText(
                                    text = item.text,
                                    color = colors.textSecondary,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = if (isZh) "共 ${sorted.size} 条通知" else "${sorted.size} alerts",
                color = colors.textMuted,
                fontSize = 10.5.sp,
            )
            WidgetGlassActionButton(
                onClick = callbacks.onOpenNotifications,
                label = if (isZh) "打开通知中心" else "Open Alerts",
                accent = colors.accentBlue,
                active = true,
                circular = false,
                size = 28.dp,
                icon = { tint -> WidgetGlyphIcon(WidgetGlyph.BELL, tint, 12.dp) },
            )
        }
    }
}

@Composable
internal fun HubCarouselFooterBar(
    realPage: Int,
    pageNames: List<String>,
    pageAccents: List<Color>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelectPage: (Int) -> Unit,
) {
    val colors = LocalOpalColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HubArrowPill(isLeft = true, onClick = onPrev)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (idx in 0 until HUB_PAGE_COUNT) {
                val active = idx == realPage
                val accent = pageAccents[idx]
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                accent.copy(alpha = if (colors.isDark) 0.28f else 0.18f)
                            } else {
                                if (colors.isDark) Color(0x22FFFFFF) else Color(0x52FFFFFF)
                            },
                        )
                        .border(
                            width = 0.8.dp,
                            color = if (active) accent.copy(alpha = 0.65f) else colors.borderHairline,
                            shape = CircleShape,
                        )
                        .clickable { onSelectPage(idx) }
                        .padding(
                            horizontal = if (active) 10.dp else 6.dp,
                            vertical = if (active) 3.5.dp else 5.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (active) {
                        OpalText(
                            text = pageNames[idx],
                            color = accent,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(colors.textMuted.copy(alpha = 0.65f)),
                        )
                    }
                }
            }
        }
        HubArrowPill(isLeft = false, onClick = onNext)
    }
}

@Composable
private fun HubArrowPill(
    isLeft: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalOpalColors.current
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (colors.isDark) Color(0x28FFFFFF) else Color(0x7AFFFFFF))
            .border(0.8.dp, colors.borderSubtle, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(11.dp)) {
            val sw = 1.65.dp.toPx()
            val w = size.width
            val h = size.height
            if (isLeft) {
                drawLine(colors.textPrimary, Offset(w * 0.66f, h * 0.18f), Offset(w * 0.30f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(colors.textPrimary, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.66f, h * 0.82f), sw, StrokeCap.Round)
            } else {
                drawLine(colors.textPrimary, Offset(w * 0.34f, h * 0.18f), Offset(w * 0.70f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(colors.textPrimary, Offset(w * 0.70f, h * 0.50f), Offset(w * 0.34f, h * 0.82f), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun HubGearIcon(tint: Color, sizeDp: Float) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val sw = 1.6.dp.toPx()
        val innerR = size.minDimension * 0.17f
        val bodyR = size.minDimension * 0.32f
        val toothOut = size.minDimension * 0.45f
        drawCircle(tint, innerR, Offset(cx, cy), style = Stroke(sw))
        drawCircle(tint, bodyR, Offset(cx, cy), style = Stroke(sw))
        for (i in 0 until 8) {
            val rad = (i * 45.0) * PI / 180.0
            drawLine(
                color = tint,
                start = Offset(cx + (bodyR * cos(rad)).toFloat(), cy + (bodyR * sin(rad)).toFloat()),
                end = Offset(cx + (toothOut * cos(rad)).toFloat(), cy + (toothOut * sin(rad)).toFloat()),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
    }
}
