package io.github.lengsukq.opaldesk.widgets.renderers

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.*
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Workstation Status & Quick Controls Widget
// ---------------------------------------------------------------------------

@Composable
fun WorkstationStatusWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    status: WidgetWorkstationSnapshot,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val connAccent = if (status.isConnected) colors.accentEmerald else colors.accentAmber
    val batteryColor = when {
        status.isCharging -> colors.accentEmerald
        status.batteryPercent <= 15 -> colors.accentDanger
        status.batteryPercent <= 25 -> colors.accentAmber
        else -> colors.textPrimary
    }
    val pulseAlpha = if (status.isConnected) 0.96f else 0.68f

    val connLabel = status.connectionLabel.ifBlank {
        if (status.isConnected) {
            if (isZh) "已连接" else "Connected"
        } else {
            if (isZh) "未连接" else "Offline"
        }
    }
    val windowLabel = if (isZh) "${status.activeWindowCount} 窗口" else "${status.activeWindowCount} Win"
    val batteryLabel = "${status.batteryPercent.coerceIn(0, 100)}%"

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = connAccent,
            centerFractionX = 0.14f,
            centerFractionY = 0.14f,
            radiusFraction = 0.72f,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (sizeClass == WidgetSizeClass.COMPACT) 8.dp else 10.dp),
        ) {
            // Top status bar: breathing connection pill + battery + active windows
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(connAccent.copy(alpha = if (colors.isDark) 0.18f else 0.12f))
                        .border(0.8.dp, connAccent.copy(alpha = if (colors.isDark) 0.42f else 0.28f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .alpha(if (status.isConnected) pulseAlpha else 0.7f)
                            .clip(CircleShape)
                            .background(connAccent),
                    )
                    OpalText(
                        text = connLabel,
                        color = connAccent,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WidgetGlassBadge(
                        text = batteryLabel,
                        accent = if (status.isCharging || status.batteryPercent <= 25) batteryColor else null,
                        leadingIcon = if (status.isCharging) {
                            { tint -> WidgetGlyphIcon(WidgetGlyph.BOLT, tint, 11.dp) }
                        } else {
                            null
                        },
                    )
                    if (sizeClass != WidgetSizeClass.COMPACT) {
                        WidgetGlassBadge(text = windowLabel)
                    }
                }
            }

            // Quick action tiles
            if (sizeClass == WidgetSizeClass.COMPACT) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WorkstationQuickTile(
                        label = if (isZh) "息屏" else "Screen",
                        sublabel = null,
                        active = status.isPhoneScreenOff,
                        accent = colors.accentCyan,
                        compact = true,
                        onClick = callbacks.onTogglePhoneScreenOff,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        icon = { tint -> OpalGlyphIcon(ChromeGlyph.SCREEN_OFF, tint = tint, size = 16.dp) },
                    )
                    WorkstationQuickTile(
                        label = if (isZh) "触控板" else "Pad",
                        sublabel = null,
                        active = false,
                        accent = colors.accentBlue,
                        compact = true,
                        onClick = callbacks.onOpenTrackpad,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        icon = { tint -> WidgetGlyphIcon(WidgetGlyph.TRACKPAD, tint = tint, size = 16.dp) },
                    )
                    WorkstationQuickTile(
                        label = if (isZh) "静音" else "Mute",
                        sublabel = null,
                        active = status.isMuted,
                        accent = colors.accentAmber,
                        compact = true,
                        onClick = callbacks.onToggleMute,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        icon = { tint ->
                            WidgetGlyphIcon(
                                glyph = if (status.isMuted) WidgetGlyph.VOLUME_MUTE else WidgetGlyph.VOLUME_ON,
                                tint = tint,
                                size = 16.dp,
                            )
                        },
                    )
                    WorkstationQuickTile(
                        label = if (isZh) "控制" else "Center",
                        sublabel = null,
                        active = false,
                        accent = colors.accentBlue,
                        compact = true,
                        onClick = callbacks.onOpenControlCenter,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        icon = { tint -> OpalGlyphIcon(ChromeGlyph.CONTROL_CENTER, tint = tint, size = 16.dp) },
                    )
                }
            } else {
                val showSublabel = sizeClass == WidgetSizeClass.EXPANDED
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WorkstationQuickTile(
                            label = if (isZh) "手机息屏" else "Screen Off",
                            sublabel = if (showSublabel) {
                                if (status.isPhoneScreenOff) {
                                    if (isZh) "已息屏省电" else "Display off"
                                } else {
                                    if (isZh) "屏幕常亮" else "Display on"
                                }
                            } else {
                                null
                            },
                            active = status.isPhoneScreenOff,
                            accent = colors.accentCyan,
                            compact = false,
                            onClick = callbacks.onTogglePhoneScreenOff,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            icon = { tint -> OpalGlyphIcon(ChromeGlyph.SCREEN_OFF, tint = tint, size = 17.dp) },
                        )
                        WorkstationQuickTile(
                            label = if (isZh) "触控板" else "Trackpad",
                            sublabel = if (showSublabel) {
                                if (isZh) "手势与光标" else "Touch gestures"
                            } else {
                                null
                            },
                            active = false,
                            accent = colors.accentBlue,
                            compact = false,
                            onClick = callbacks.onOpenTrackpad,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            icon = { tint -> WidgetGlyphIcon(WidgetGlyph.TRACKPAD, tint = tint, size = 17.dp) },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WorkstationQuickTile(
                            label = if (isZh) "媒体静音" else "Mute Audio",
                            sublabel = if (showSublabel) {
                                if (status.isMuted) {
                                    if (isZh) "已静音" else "Muted"
                                } else {
                                    if (isZh) "声音开启" else "Sound on"
                                }
                            } else {
                                null
                            },
                            active = status.isMuted,
                            accent = colors.accentAmber,
                            compact = false,
                            onClick = callbacks.onToggleMute,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            icon = { tint ->
                                WidgetGlyphIcon(
                                    glyph = if (status.isMuted) WidgetGlyph.VOLUME_MUTE else WidgetGlyph.VOLUME_ON,
                                    tint = tint,
                                    size = 17.dp,
                                )
                            },
                        )
                        WorkstationQuickTile(
                            label = if (isZh) "控制中心" else "Control Center",
                            sublabel = if (showSublabel) {
                                if (isZh) "亮度与快捷项" else "Quick toggles"
                            } else {
                                null
                            },
                            active = false,
                            accent = colors.accentBlue,
                            compact = false,
                            onClick = callbacks.onOpenControlCenter,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            icon = { tint -> OpalGlyphIcon(ChromeGlyph.CONTROL_CENTER, tint = tint, size = 17.dp) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkstationQuickTile(
    label: String,
    sublabel: String?,
    active: Boolean,
    accent: Color,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val colors = LocalOpalColors.current
    val isDark = colors.isDark
    val shape = RoundedCornerShape(14.dp)
    val fillBrush = if (active) {
        Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (isDark) 0.34f else 0.22f),
                accent.copy(alpha = if (isDark) 0.16f else 0.10f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                if (isDark) Color(0x22FFFFFF) else Color(0x82FFFFFF),
                if (isDark) Color(0x10FFFFFF) else Color(0x46FFFFFF),
            ),
        )
    }
    val borderBrush = if (active) {
        Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (isDark) 0.78f else 0.62f),
                accent.copy(alpha = 0.26f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                colors.glassRimTop.copy(alpha = if (isDark) 0.30f else 0.88f),
                colors.borderHairline,
            ),
        )
    }
    val iconTint = if (active) accent else colors.textPrimary

    Box(
        modifier = modifier
            .clip(shape)
            .background(fillBrush)
            .border(if (active) 1.dp else 0.85.dp, borderBrush, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 11.dp, vertical = if (compact) 7.dp else 9.dp),
        contentAlignment = if (compact) Alignment.Center else Alignment.CenterStart,
    ) {
        if (compact) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                icon(iconTint)
                OpalText(
                    text = label,
                    color = if (active) accent else colors.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                accent.copy(alpha = if (isDark) 0.26f else 0.18f)
                            } else {
                                colors.chipFill
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    icon(iconTint)
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    OpalText(
                        text = label,
                        color = if (active) accent else colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (!sublabel.isNullOrBlank()) {
                        OpalText(
                            text = sublabel,
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Notifications & Smart OTP Quick-Paste Widget
// ---------------------------------------------------------------------------

@Composable
fun NotificationOtpWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    notifications: List<WidgetNotificationItem>,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val sorted = remember(notifications) {
        notifications.sortedByDescending { it.timestampMs }
    }
    val otpMatch = remember(sorted) {
        sorted.firstNotNullOfOrNull { item ->
            val code = extractOtpCode("${item.title} ${item.text}")
            if (code != null) Pair(code, item) else null
        }
    }
    var copiedCode by remember(instance.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedCode) {
        if (copiedCode != null) {
            delay(2200L)
            copiedCode = null
        }
    }

    val maxVisibleItems = when (sizeClass) {
        WidgetSizeClass.COMPACT -> if (otpMatch != null) 1 else 2
        WidgetSizeClass.MEDIUM -> if (otpMatch != null) 2 else 3
        WidgetSizeClass.EXPANDED -> 8
    }

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = if (otpMatch != null) colors.accentEmerald else colors.accentBlue,
            centerFractionX = 0.86f,
            centerFractionY = 0.14f,
            radiusFraction = 0.72f,
        )
        if (sorted.isEmpty()) {
            WidgetEmptyState(
                title = if (isZh) "暂无新通知" else "All Caught Up",
                subtitle = if (isZh) "收到验证码或消息时会自动显示" else "Incoming notifications and OTP codes appear here",
                accent = colors.accentBlue,
                icon = { tint -> WidgetGlyphIcon(WidgetGlyph.BELL, tint, 17.dp) },
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (otpMatch != null) {
                    val (code, sourceItem) = otpMatch
                    val isCopied = copiedCode == code
                    WidgetInnerPanel(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 15.dp,
                        accent = colors.accentEmerald,
                        active = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OpalText(
                                        text = if (isZh) "验证码速贴" else "Quick OTP",
                                        color = colors.accentEmerald,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                    if (sourceItem.appName.isNotBlank()) {
                                        OpalText(
                                            text = "· ${sourceItem.appName}",
                                            color = colors.textSecondary,
                                            fontSize = 10.5.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                OpalText(
                                    text = formatOtpDisplay(code),
                                    color = colors.textPrimary,
                                    fontSize = if (sizeClass == WidgetSizeClass.COMPACT) 20.sp else 23.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            WidgetGlassActionButton(
                                onClick = {
                                    callbacks.onCopyText(code)
                                    copiedCode = code
                                },
                                label = if (isCopied) {
                                    if (isZh) "已复制" else "Copied"
                                } else {
                                    if (isZh) "复制" else "Copy"
                                },
                                accent = colors.accentEmerald,
                                filled = true,
                                circular = false,
                                size = 30.dp,
                                icon = { tint ->
                                    WidgetGlyphIcon(
                                        glyph = if (isCopied) WidgetGlyph.CHECK else WidgetGlyph.COPY,
                                        tint = tint,
                                        size = 13.dp,
                                    )
                                },
                            )
                        }
                    }
                } else if (sizeClass != WidgetSizeClass.COMPACT) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        WidgetGlassBadge(
                            text = if (isZh) "通知中心" else "Notifications",
                            accent = colors.accentBlue,
                            leadingIcon = { tint -> WidgetGlyphIcon(WidgetGlyph.BELL, tint, 11.dp) },
                        )
                        OpalText(
                            text = if (isZh) "${sorted.size} 条" else "${sorted.size}",
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                val listModifier = if (sizeClass == WidgetSizeClass.EXPANDED) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                }
                Column(
                    modifier = listModifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sorted.take(maxVisibleItems).forEach { item ->
                        NotificationRowCard(
                            item = item,
                            compact = sizeClass == WidgetSizeClass.COMPACT,
                            isZh = isZh,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRowCard(
    item: WidgetNotificationItem,
    compact: Boolean,
    isZh: Boolean,
) {
    val colors = LocalOpalColors.current
    val timeAgo = remember(item.timestampMs, isZh) { formatRelativeTime(item.timestampMs, isZh) }
    WidgetInnerPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 13.dp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = item.appName.ifBlank { if (isZh) "系统通知" else "System" },
                color = colors.accentBlue,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            OpalText(
                text = timeAgo,
                color = colors.textMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        if (item.title.isNotBlank()) {
            OpalText(
                text = item.title,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        if (item.text.isNotBlank()) {
            OpalText(
                text = item.text,
                color = colors.textSecondary,
                fontSize = 11.sp,
                maxLines = if (compact) 1 else 2,
            )
        }
    }
}

private fun formatOtpDisplay(code: String): String =
    if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

private fun formatRelativeTime(timestampMs: Long, isZh: Boolean): String {
    if (timestampMs <= 0L) return if (isZh) "刚刚" else "now"
    val diffSec = ((System.currentTimeMillis() - timestampMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> if (isZh) "刚刚" else "now"
        diffSec < 3600L -> {
            val m = diffSec / 60L
            if (isZh) "${m}分钟前" else "${m}m"
        }
        diffSec < 86400L -> {
            val h = diffSec / 3600L
            if (isZh) "${h}小时前" else "${h}h"
        }
        else -> {
            val d = diffSec / 86400L
            if (isZh) "${d}天前" else "${d}d"
        }
    }
}
