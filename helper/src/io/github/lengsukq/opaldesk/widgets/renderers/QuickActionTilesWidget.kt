package io.github.lengsukq.opaldesk.widgets.renderers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetCallbacks
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetInstance
import io.github.lengsukq.opaldesk.widgets.WidgetAppShortcut
import io.github.lengsukq.opaldesk.widgets.WidgetSizeClass
import io.github.lengsukq.opaldesk.widgets.WidgetWorkstationSnapshot

private data class QuickTileAction(
    val id: String,
    val title: String,
    val subtitle: String,
    val accent: Color,
    val active: Boolean,
    val onClick: () -> Unit,
    val icon: @Composable (Color) -> Unit,
)

@Composable
fun QuickActionTilesWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    workstation: WidgetWorkstationSnapshot,
    apps: List<WidgetAppShortcut>,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val canQuickSplit = apps.size >= 2

    val allTiles = listOf(
        QuickTileAction(
            id = "screen_blackout",
            title = if (workstation.isPhoneScreenOff) {
                if (isZh) "已息屏" else "Blackout"
            } else {
                if (isZh) "亮屏中" else "Screen On"
            },
            subtitle = if (workstation.isPhoneScreenOff) {
                if (isZh) "点按唤醒" else "Tap wake"
            } else {
                if (isZh) "本机息屏" else "Dim phone"
            },
            accent = colors.accentBlue,
            active = workstation.isPhoneScreenOff,
            onClick = callbacks.onTogglePhoneScreenOff,
            icon = { tint -> PhoneScreenTileGlyph(isOff = workstation.isPhoneScreenOff, tint = tint) },
        ),
        QuickTileAction(
            id = "split_layout",
            title = if (canQuickSplit) {
                if (isZh) "一键分屏" else "Split View"
            } else {
                if (isZh) "窗口布局" else "Layouts"
            },
            subtitle = if (canQuickSplit) {
                if (isZh) "左右双开" else "Dual pane"
            } else {
                if (isZh) "控制中心" else "Arrange"
            },
            accent = colors.accentCyan,
            active = false,
            onClick = {
                if (canQuickSplit) {
                    callbacks.onSplitLaunchApps(apps[0].packageName, apps[1].packageName)
                } else {
                    callbacks.onOpenControlCenter()
                }
            },
            icon = { tint -> WidgetGlyphIcon(WidgetGlyph.SPLIT_SCREEN, tint = tint, size = 16.dp) },
        ),
        QuickTileAction(
            id = "trackpad",
            title = if (isZh) "触控板" else "Trackpad",
            subtitle = if (isZh) "虚拟指针" else "Pointer",
            accent = Color(0xFF5E5CE6),
            active = false,
            onClick = callbacks.onOpenTrackpad,
            icon = { tint -> WidgetGlyphIcon(WidgetGlyph.TRACKPAD, tint = tint, size = 16.dp) },
        ),
        QuickTileAction(
            id = "mute",
            title = if (workstation.isMuted) {
                if (isZh) "已静音" else "Muted"
            } else {
                if (isZh) "声音开" else "Sound"
            },
            subtitle = if (workstation.isMuted) {
                if (isZh) "点按恢复" else "Tap unmute"
            } else {
                if (isZh) "一键静音" else "Tap mute"
            },
            accent = if (workstation.isMuted) colors.accentDanger else colors.accentAmber,
            active = workstation.isMuted,
            onClick = callbacks.onToggleMute,
            icon = { tint ->
                WidgetGlyphIcon(
                    glyph = if (workstation.isMuted) WidgetGlyph.VOLUME_MUTE else WidgetGlyph.VOLUME_ON,
                    tint = tint,
                    size = 16.dp,
                )
            },
        ),
        QuickTileAction(
            id = "spotlight",
            title = if (isZh) "全局搜索" else "Spotlight",
            subtitle = if (isZh) "快捷启动" else "Search",
            accent = colors.accentBlue,
            active = false,
            onClick = callbacks.onOpenSpotlight,
            icon = { tint -> SpotlightTileGlyph(tint = tint) },
        ),
        QuickTileAction(
            id = "control_center",
            title = if (isZh) "控制中心" else "Control",
            subtitle = if (isZh) "系统开关" else "Toggles",
            accent = colors.accentEmerald,
            active = false,
            onClick = callbacks.onOpenControlCenter,
            icon = { tint -> SlidersTileGlyph(tint = tint) },
        ),
        QuickTileAction(
            id = "widget_store",
            title = if (isZh) "组件商店" else "Store",
            subtitle = if (isZh) "添加组件" else "Widgets",
            accent = Color(0xFFBF5AF2),
            active = false,
            onClick = callbacks.onOpenWidgetStore,
            icon = { tint -> StoreGridTileGlyph(tint = tint) },
        ),
        QuickTileAction(
            id = "edit_layout",
            title = if (isZh) "编辑布局" else "Edit Grid",
            subtitle = if (isZh) "排版整理" else "Customize",
            accent = Color(0xFFFF6B6B),
            active = false,
            onClick = callbacks.onToggleWidgetEditMode,
            icon = { tint -> LayoutEditTileGlyph(tint = tint) },
        ),
    )

    val (gridCols, gridRows) = when {
        instance.colSpan >= 4 -> 4 to 2
        instance.colSpan == 3 -> 3 to 2
        instance.rowSpan >= 3 -> 2 to 3
        else -> 2 to 2
    }
    val visibleTiles = allTiles.take(gridCols * gridRows)
    val rows = visibleTiles.chunked(gridCols)
    val showSubtitle = sizeClass != WidgetSizeClass.COMPACT && instance.rowSpan >= 3

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = if (workstation.isPhoneScreenOff) colors.accentBlue else colors.accentCyan,
            centerFractionX = 0.20f,
            centerFractionY = 0.18f,
            radiusFraction = 0.78f,
            alpha = 0.14f,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { rowTiles ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowTiles.forEach { tile ->
                        QuickGlassSquareTile(
                            tile = tile,
                            showSubtitle = showSubtitle,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickGlassSquareTile(
    tile: QuickTileAction,
    showSubtitle: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val isDark = colors.isDark
    val shape = RoundedCornerShape(16.dp)

    val fillBrush = if (tile.active) {
        Brush.verticalGradient(
            listOf(
                tile.accent.copy(alpha = if (isDark) 0.38f else 0.24f),
                tile.accent.copy(alpha = if (isDark) 0.18f else 0.12f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                if (isDark) Color(0x26FFFFFF) else Color(0x8AFFFFFF),
                if (isDark) Color(0x12FFFFFF) else Color(0x4EFFFFFF),
            ),
        )
    }
    val borderBrush = if (tile.active) {
        Brush.verticalGradient(
            listOf(tile.accent.copy(alpha = 0.78f), tile.accent.copy(alpha = 0.34f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                colors.glassRimTop.copy(alpha = if (isDark) 0.32f else 0.85f),
                colors.borderHairline,
            ),
        )
    }
    val iconBg = if (tile.active) {
        tile.accent
    } else {
        tile.accent.copy(alpha = if (isDark) 0.20f else 0.14f)
    }
    val iconTint = if (tile.active) Color.White else tile.accent

    Column(
        modifier = modifier
            .clip(shape)
            .background(fillBrush)
            .border(0.9.dp, borderBrush, shape)
            .clickable(onClick = tile.onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(iconBg)
                .border(
                    0.7.dp,
                    if (tile.active) Color.White.copy(alpha = 0.42f) else tile.accent.copy(alpha = 0.28f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            tile.icon(iconTint)
        }
        OpalText(
            text = tile.title,
            color = if (tile.active) tile.accent else colors.textPrimary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (showSubtitle) {
            OpalText(
                text = tile.subtitle,
                color = colors.textMuted,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PhoneScreenTileGlyph(isOff: Boolean, tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.55.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.24f, h * 0.10f),
            size = Size(w * 0.52f, h * 0.80f),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(sw),
        )
        if (isOff) {
            drawLine(tint, Offset(w * 0.16f, h * 0.82f), Offset(w * 0.84f, h * 0.18f), sw, StrokeCap.Round)
        } else {
            drawCircle(tint, radius = w * 0.08f, center = Offset(w * 0.50f, h * 0.44f))
            drawLine(tint, Offset(w * 0.42f, h * 0.76f), Offset(w * 0.58f, h * 0.76f), sw * 0.9f, StrokeCap.Round)
        }
    }
}

@Composable
private fun SpotlightTileGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.65.dp.toPx()
        drawCircle(tint, radius = w * 0.28f, center = Offset(w * 0.44f, h * 0.44f), style = Stroke(sw))
        drawLine(tint, Offset(w * 0.65f, h * 0.65f), Offset(w * 0.85f, h * 0.85f), sw, StrokeCap.Round)
    }
}

@Composable
private fun SlidersTileGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.55.dp.toPx()
        drawLine(tint, Offset(w * 0.16f, h * 0.32f), Offset(w * 0.84f, h * 0.32f), sw, StrokeCap.Round)
        drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.38f, h * 0.32f))
        drawLine(tint, Offset(w * 0.16f, h * 0.68f), Offset(w * 0.84f, h * 0.68f), sw, StrokeCap.Round)
        drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.64f, h * 0.68f))
    }
}

@Composable
private fun StoreGridTileGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(1.8.dp.toPx())
        drawRoundRect(tint, Offset(w * 0.14f, h * 0.14f), Size(w * 0.30f, h * 0.30f), r)
        drawRoundRect(tint, Offset(w * 0.56f, h * 0.14f), Size(w * 0.30f, h * 0.30f), r)
        drawRoundRect(tint, Offset(w * 0.14f, h * 0.56f), Size(w * 0.30f, h * 0.30f), r)
        drawRoundRect(tint.copy(alpha = 0.65f), Offset(w * 0.56f, h * 0.56f), Size(w * 0.30f, h * 0.30f), r)
    }
}

@Composable
private fun LayoutEditTileGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.5.dp.toPx()
        val r = CornerRadius(2.dp.toPx())
        drawRoundRect(tint, Offset(w * 0.14f, h * 0.16f), Size(w * 0.72f, h * 0.28f), r, Stroke(sw))
        drawRoundRect(tint, Offset(w * 0.14f, h * 0.56f), Size(w * 0.32f, h * 0.28f), r, Stroke(sw))
        drawRoundRect(tint, Offset(w * 0.54f, h * 0.56f), Size(w * 0.32f, h * 0.28f), r, Stroke(sw))
    }
}
