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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetCallbacks
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetInstance
import io.github.lengsukq.opaldesk.widgets.WidgetMediaSnapshot
import io.github.lengsukq.opaldesk.widgets.WidgetNotificationItem
import io.github.lengsukq.opaldesk.widgets.WidgetSizeClass
import io.github.lengsukq.opaldesk.widgets.WidgetWorkstationSnapshot
import io.github.lengsukq.opaldesk.widgets.data.WidgetWeatherRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

internal const val HUB_PAGE_COUNT = 4
private const val HUB_VIRTUAL_COUNT = Int.MAX_VALUE

/**
 * Flagship Infinite-Loop 4-Page Liquid Glass Desktop Hub Carousel Widget (`DESKTOP_HUB`):
 * Page 0: Status Overview (Live Clock, Date, Link Breathing Pill, Battery, Windows, Spotlight/Store/Edit)
 * Page 1: Quick Controls (2x3 Tactile Glass Tiles with Neon Active Glow)
 * Page 2: Now Playing Media (Album/Glass Vinyl Cover, Track Metadata, Playback Controls)
 * Page 3: Alerts & OTP (Smart 1-Tap OTP Quick-Paste Banner, Latest Notifications, Notification Center)
 */
@Composable
fun DesktopHubCarouselWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    media: WidgetMediaSnapshot,
    status: WidgetWorkstationSnapshot,
    notifications: List<WidgetNotificationItem>,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val scope = rememberCoroutineScope()
    val initialVirtualPage = remember {
        val mid = HUB_VIRTUAL_COUNT / 2
        mid - (mid % HUB_PAGE_COUNT)
    }
    val pagerState = rememberPagerState(
        initialPage = initialVirtualPage,
        pageCount = { HUB_VIRTUAL_COUNT },
    )
    val realPage = ((pagerState.currentPage % HUB_PAGE_COUNT) + HUB_PAGE_COUNT) % HUB_PAGE_COUNT

    val pageNames = remember(isZh) {
        if (isZh) {
            listOf("状态总览", "快捷控制", "正在播放", "通知速览")
        } else {
            listOf("Overview", "Controls", "Now Playing", "Alerts & OTP")
        }
    }
    val pageAccents = listOf(
        colors.accentCyan,
        colors.accentEmerald,
        colors.accentBlue,
        colors.accentAmber,
    )
    val activeAccent = pageAccents[realPage]
    val isCompact = sizeClass == WidgetSizeClass.COMPACT

    fun jumpToRealPage(targetReal: Int) {
        val cur = pagerState.currentPage
        val curMod = ((cur % HUB_PAGE_COUNT) + HUB_PAGE_COUNT) % HUB_PAGE_COUNT
        var delta = targetReal - curMod
        if (delta > HUB_PAGE_COUNT / 2) delta -= HUB_PAGE_COUNT
        if (delta < -(HUB_PAGE_COUNT / 2)) delta += HUB_PAGE_COUNT
        if (delta != 0) {
            scope.launch { pagerState.animateScrollToPage(cur + delta) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = activeAccent,
            centerFractionX = if (realPage % 2 == 0) 0.16f else 0.84f,
            centerFractionY = 0.18f,
            radiusFraction = 0.82f,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp),
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { virtualPage ->
                val pageIdx = ((virtualPage % HUB_PAGE_COUNT) + HUB_PAGE_COUNT) % HUB_PAGE_COUNT
                val pageOffset = ((pagerState.currentPage - virtualPage) + pagerState.currentPageOffsetFraction)
                    .absoluteValue
                    .coerceIn(0f, 1f)
                val scale = 0.94f + 0.06f * (1f - pageOffset)
                val pageAlpha = 0.56f + 0.44f * (1f - pageOffset)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = pageAlpha
                        },
                ) {
                    when (pageIdx) {
                        0 -> HubOverviewPage(
                            status = status,
                            callbacks = callbacks,
                            isCompact = isCompact,
                            isZh = isZh,
                        )
                        1 -> HubQuickControlsPage(
                            status = status,
                            callbacks = callbacks,
                            isCompact = isCompact,
                            isZh = isZh,
                        )
                        2 -> HubNowPlayingPage(
                            media = media,
                            callbacks = callbacks,
                            isCompact = isCompact,
                            isZh = isZh,
                        )
                        else -> HubAlertsOtpPage(
                            instanceId = instance.id,
                            notifications = notifications,
                            callbacks = callbacks,
                            isCompact = isCompact,
                            isZh = isZh,
                        )
                    }
                }
            }

            HubCarouselFooterBar(
                realPage = realPage,
                pageNames = pageNames,
                pageAccents = pageAccents,
                onPrev = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                onSelectPage = { jumpToRealPage(it) },
            )
        }
    }
}

@Composable
private fun HubOverviewPage(
    status: WidgetWorkstationSnapshot,
    callbacks: DesktopWidgetCallbacks,
    isCompact: Boolean,
    isZh: Boolean,
) {
    val colors = LocalOpalColors.current
    val ctx = LocalContext.current
    val weatherFlow = remember(ctx) { WidgetWeatherRepository.observe(ctx) }
    val weather by weatherFlow.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val locale = if (isZh) Locale.CHINA else Locale.US
    val hoursText = remember(nowMs / 1000L) {
        SimpleDateFormat("HH", Locale.US).format(Date(nowMs))
    }
    val minutesText = remember(nowMs / 1000L) {
        SimpleDateFormat("mm", Locale.US).format(Date(nowMs))
    }
    val secText = remember(nowMs / 1000L) {
        SimpleDateFormat("ss", Locale.US).format(Date(nowMs))
    }
    val amPmText = remember(nowMs / 60_000L) {
        SimpleDateFormat("a", Locale.US).format(Date(nowMs))
    }
    val dateText = remember(nowMs / 60_000L, isZh) {
        SimpleDateFormat(if (isZh) "M月d日 · EEEE" else "EEE, MMM d", locale).format(Date(nowMs))
    }
    val pulse = if (status.isConnected) 0.96f else 0.68f
    val connAccent = if (status.isConnected) colors.accentEmerald else colors.accentAmber
    val connLabel = status.connectionLabel.ifBlank {
        if (status.isConnected) {
            if (isZh) "已连接" else "Connected"
        } else {
            if (isZh) "离线" else "Offline"
        }
    }
    val batteryColor = when {
        status.isCharging -> colors.accentEmerald
        status.batteryPercent <= 15 -> colors.accentDanger
        status.batteryPercent <= 25 -> colors.accentAmber
        else -> colors.textPrimary
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val tightWidth = isCompact || maxWidth < 360.dp
        val tightHeight = maxHeight < 170.dp
        WidgetInnerPanel(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 20.dp,
            accent = colors.accentCyan,
            contentPadding = PaddingValues(
                horizontal = if (tightWidth) 11.dp else 14.dp,
                vertical = if (tightWidth || tightHeight) 8.dp else 12.dp,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(connAccent.copy(alpha = if (colors.isDark) 0.20f else 0.14f))
                        .border(0.8.dp, connAccent.copy(alpha = 0.45f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 3.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .alpha(if (status.isConnected) pulse else 0.7f)
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
                        text = "${status.batteryPercent.coerceIn(0, 100)}%",
                        accent = if (status.isCharging || status.batteryPercent <= 25) batteryColor else null,
                        leadingIcon = if (status.isCharging) {
                            { tint -> WidgetGlyphIcon(WidgetGlyph.BOLT, tint, 11.dp) }
                        } else {
                            null
                        },
                    )
                    WidgetGlassBadge(
                        text = if (isZh) "${status.activeWindowCount} 窗口" else "${status.activeWindowCount} Win",
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Golden-ratio hero row: Left = Refined Gradient Clock + Date; Right = Live Weather Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (tightWidth) 8.dp else 12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1.15f),
                    verticalArrangement = Arrangement.spacedBy(if (tightWidth) 2.dp else 5.dp),
                ) {
                    LiquidGradientClockDigits(
                        hours = hoursText,
                        minutes = minutesText,
                        seconds = secText,
                        amPm = amPmText,
                        digitSize = if (tightWidth) 32.sp else 44.sp,
                        accent = colors.accentCyan,
                        showSecondsBadge = true,
                    )
                    OpalText(
                        text = dateText,
                        color = colors.textSecondary,
                        fontSize = if (tightWidth) 10.5.sp else 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                HubWeatherMiniCard(
                    weather = weather,
                    isCompact = tightWidth,
                    isZh = isZh,
                    onRefresh = {
                        WidgetWeatherRepository.refresh(ctx, force = true)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (tightWidth) 6.dp else 8.dp),
            ) {
                WidgetGlassActionButton(
                    onClick = callbacks.onOpenSpotlight,
                    label = if (isZh) (if (tightWidth) "搜索" else "Spotlight 搜索") else "Spotlight",
                    accent = colors.accentBlue,
                    active = true,
                    circular = false,
                    size = if (tightHeight) 28.dp else 32.dp,
                    modifier = Modifier.weight(if (tightWidth) 1f else 1.2f),
                    icon = { tint -> OpalGlyphIcon(ChromeGlyph.SEARCH, tint = tint, size = 13.dp) },
                )
                WidgetGlassActionButton(
                    onClick = callbacks.onOpenWidgetStore,
                    label = if (isZh) (if (tightWidth) "商店" else "组件商店") else "Store",
                    accent = colors.accentCyan,
                    circular = false,
                    size = if (tightHeight) 28.dp else 32.dp,
                    modifier = Modifier.weight(1f),
                    icon = { tint -> OpalGlyphIcon(ChromeGlyph.APPS_GRID, tint = tint, size = 13.dp) },
                )
                WidgetGlassActionButton(
                    onClick = callbacks.onToggleWidgetEditMode,
                    label = if (isZh) (if (tightWidth) "编辑" else "编辑组件") else "Edit",
                    accent = colors.accentEmerald,
                    circular = false,
                    size = if (tightHeight) 28.dp else 32.dp,
                    modifier = Modifier.weight(1f),
                    icon = { tint -> WidgetGlyphIcon(WidgetGlyph.SPLIT_SCREEN, tint = tint, size = 13.dp) },
                )
            }
        }
    }
}

@Composable
private fun HubQuickControlsPage(
    status: WidgetWorkstationSnapshot,
    callbacks: DesktopWidgetCallbacks,
    isCompact: Boolean,
    isZh: Boolean,
) {
    val colors = LocalOpalColors.current
    val spacing = if (isCompact) 7.dp else 9.dp
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            HubControlTile(
                label = if (isZh) "手机息屏" else "Screen Off",
                sublabel = if (status.isPhoneScreenOff) {
                    if (isZh) "已息屏" else "Off"
                } else {
                    if (isZh) "省电开关" else "Display"
                },
                active = status.isPhoneScreenOff,
                accent = colors.accentCyan,
                compact = isCompact,
                onClick = callbacks.onTogglePhoneScreenOff,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = { tint -> OpalGlyphIcon(ChromeGlyph.SCREEN_OFF, tint = tint, size = 17.dp) },
            )
            HubControlTile(
                label = if (isZh) "虚拟触控板" else "Trackpad",
                sublabel = if (isZh) "手势光标" else "Touch",
                active = false,
                accent = colors.accentBlue,
                compact = isCompact,
                onClick = callbacks.onOpenTrackpad,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = { tint -> WidgetGlyphIcon(WidgetGlyph.TRACKPAD, tint = tint, size = 17.dp) },
            )
            HubControlTile(
                label = if (isZh) "静音" else "Mute",
                sublabel = if (status.isMuted) {
                    if (isZh) "已静音" else "Muted"
                } else {
                    if (isZh) "媒体音量" else "Audio"
                },
                active = status.isMuted,
                accent = colors.accentAmber,
                compact = isCompact,
                onClick = callbacks.onToggleMute,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = { tint ->
                    WidgetGlyphIcon(
                        glyph = if (status.isMuted) WidgetGlyph.VOLUME_MUTE else WidgetGlyph.VOLUME_ON,
                        tint = tint,
                        size = 17.dp,
                    )
                },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            HubControlTile(
                label = if (isZh) "控制中心" else "Control",
                sublabel = if (isZh) "显示与平铺" else "System",
                active = false,
                accent = colors.accentBlue,
                compact = isCompact,
                onClick = callbacks.onOpenControlCenter,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = { tint -> OpalGlyphIcon(ChromeGlyph.CONTROL_CENTER, tint = tint, size = 17.dp) },
            )
            HubControlTile(
                label = if (isZh) "组件商店" else "Widgets",
                sublabel = if (isZh) "添加插件" else "Store",
                active = false,
                accent = Color(0xFFBF5AF2),
                compact = isCompact,
                onClick = callbacks.onOpenWidgetStore,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = { tint -> OpalGlyphIcon(ChromeGlyph.APPS_GRID, tint = tint, size = 17.dp) },
            )
            HubControlTile(
                label = if (isZh) "设置与更多" else "Settings",
                sublabel = if (isZh) "工作站台" else "Console",
                active = false,
                accent = colors.accentEmerald,
                compact = isCompact,
                onClick = callbacks.onOpenSettings,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = { tint -> HubGearIcon(tint = tint, sizeDp = 17f) },
            )
        }
    }
}

@Composable
private fun HubControlTile(
    label: String,
    sublabel: String,
    active: Boolean,
    accent: Color,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val colors = LocalOpalColors.current
    val shape = RoundedCornerShape(18.dp)
    val fill = if (active) {
        Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (colors.isDark) 0.34f else 0.22f),
                accent.copy(alpha = if (colors.isDark) 0.16f else 0.10f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                if (colors.isDark) Color(0x24FFFFFF) else Color(0x8AFFFFFF),
                if (colors.isDark) Color(0x10FFFFFF) else Color(0x4EFFFFFF),
            ),
        )
    }
    val borderBrush = if (active) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.30f)))
    } else {
        Brush.linearGradient(
            listOf(
                colors.glassRimTop.copy(alpha = if (colors.isDark) 0.36f else 0.85f),
                colors.borderHairline,
            ),
        )
    }
    val tint = if (active) accent else colors.textPrimary

    Row(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(if (active) 1.15.dp else 0.85.dp, borderBrush, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 30.dp else 34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(accent.copy(alpha = if (active) 0.26f else if (colors.isDark) 0.14f else 0.10f))
                .border(0.75.dp, accent.copy(alpha = if (active) 0.55f else 0.25f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            icon(tint)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            OpalText(
                text = label,
                color = if (active) accent else colors.textPrimary,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (!compact) {
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
