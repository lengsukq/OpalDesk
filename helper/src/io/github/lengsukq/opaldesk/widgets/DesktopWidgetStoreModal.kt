package io.github.lengsukq.opaldesk.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTextInput
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import java.util.Locale

private enum class StoreFilterTab {
    ALL,
    MEDIA,
    TIME,
    SYSTEM,
    INFO,
    PRODUCTIVITY,
    INSTALLED,
}

@Composable
fun DesktopWidgetStoreModal(
    instances: List<DesktopWidgetInstance>,
    maxHeight: Dp,
    onAddWidget: (DesktopWidgetType) -> Unit,
    onRemoveWidget: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onEnterEditMode: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val isZh = remember(ctx) {
        val lang = runCatching {
            ctx.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language
        }.getOrNull() ?: Locale.getDefault().language
        lang.equals("zh", ignoreCase = true)
    }
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(StoreFilterTab.ALL) }

    val countByType = remember(instances) {
        instances.groupingBy { it.type }.eachCount()
    }
    val filteredSpecs = remember(query, selectedTab) {
        val base = when (selectedTab) {
            StoreFilterTab.ALL, StoreFilterTab.INSTALLED -> DesktopWidgetCatalog.specs
            StoreFilterTab.MEDIA -> DesktopWidgetCatalog.byCategory(DesktopWidgetCategory.MEDIA)
            StoreFilterTab.TIME -> DesktopWidgetCatalog.byCategory(DesktopWidgetCategory.TIME)
            StoreFilterTab.SYSTEM -> DesktopWidgetCatalog.byCategory(DesktopWidgetCategory.SYSTEM)
            StoreFilterTab.INFO -> DesktopWidgetCatalog.byCategory(DesktopWidgetCategory.INFO)
            StoreFilterTab.PRODUCTIVITY -> DesktopWidgetCatalog.byCategory(DesktopWidgetCategory.PRODUCTIVITY)
        }
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            base
        } else {
            base.filter { spec ->
                spec.titleZh.lowercase(Locale.ROOT).contains(q) ||
                    spec.titleEn.lowercase(Locale.ROOT).contains(q) ||
                    spec.subtitleZh.lowercase(Locale.ROOT).contains(q) ||
                    spec.subtitleEn.lowercase(Locale.ROOT).contains(q)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor.copy(alpha = 0.34f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        val modalShape = RoundedCornerShape(28.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 880.dp)
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = maxHeight)
                .liquidGlassSurface(
                    shape = modalShape,
                    fill = OpalPalette.LiquidPopoverFill,
                    rim = OpalPalette.LiquidGlassRim,
                    elevation = 30.dp,
                    borderWidth = 1.dp,
                )
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StoreHeaderBar(
                isZh = isZh,
                installedCount = instances.size,
                onEnterEditMode = {
                    onDismiss()
                    onEnterEditMode()
                },
                onDismiss = onDismiss,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OpalTextInput(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = if (isZh) "搜索组件名称或功能…" else "Search widgets or plugins…",
                    modifier = Modifier.weight(1f),
                )
                if (query.isNotEmpty()) {
                    OpalButton(
                        label = if (isZh) "清空" else "Clear",
                        onClick = { query = "" },
                        filled = false,
                        compact = true,
                    )
                }
            }

            StoreCategoryStrip(
                isZh = isZh,
                selectedTab = selectedTab,
                installedCount = instances.size,
                onSelectTab = { selectedTab = it },
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                val twoColumns = maxWidth >= 560.dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (selectedTab == StoreFilterTab.INSTALLED) {
                        InstalledWidgetsSection(
                            isZh = isZh,
                            instances = instances,
                            onRemoveWidget = onRemoveWidget,
                            onResetDefaults = onResetDefaults,
                            onEnterEditMode = {
                                onDismiss()
                                onEnterEditMode()
                            },
                        )
                    } else if (filteredSpecs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 42.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            OpalText(
                                text = if (isZh) "没有找到匹配的桌面组件" else "No matching widgets found",
                                color = OpalPalette.TextMuted,
                                fontSize = 13.5.sp,
                            )
                        }
                    } else if (twoColumns) {
                        filteredSpecs.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEach { spec ->
                                    StoreWidgetCard(
                                        spec = spec,
                                        isZh = isZh,
                                        installedCount = countByType[spec.type] ?: 0,
                                        onAdd = { onAddWidget(spec.type) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        filteredSpecs.forEach { spec ->
                            StoreWidgetCard(
                                spec = spec,
                                isZh = isZh,
                                installedCount = countByType[spec.type] ?: 0,
                                onAdd = { onAddWidget(spec.type) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun StoreHeaderBar(
    isZh: Boolean,
    installedCount: Int,
    onEnterEditMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.linearGradient(
                        listOf(OpalPalette.AccentBlue, OpalPalette.AccentCyan),
                    ),
                )
                .border(0.9.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            OpalGlyphIcon(glyph = ChromeGlyph.APPS_GRID, tint = Color.White, size = 18.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OpalText(
                text = if (isZh) "桌面组件商店" else "Desktop Widget Store",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = OpalPalette.TextPrimary,
            )
            OpalText(
                text = if (isZh) {
                    "液态毛玻璃插件 · 当前桌面已添加 $installedCount 个组件"
                } else {
                    "Liquid glass plugins · $installedCount active on desktop"
                },
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
        }
        OpalButton(
            label = if (isZh) "排版编辑" else "Edit Layout",
            onClick = onEnterEditMode,
            filled = false,
            compact = true,
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(OpalPalette.ChipFill)
                .border(0.8.dp, OpalPalette.BorderSubtle, CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            OpalGlyphIcon(glyph = ChromeGlyph.CLOSE, tint = OpalPalette.TextPrimary, size = 13.dp)
        }
    }
}

@Composable
private fun StoreCategoryStrip(
    isZh: Boolean,
    selectedTab: StoreFilterTab,
    installedCount: Int,
    onSelectTab: (StoreFilterTab) -> Unit,
) {
    val tabs = listOf(
        StoreFilterTab.ALL to (if (isZh) "全部" else "All"),
        StoreFilterTab.MEDIA to (if (isZh) "媒体" else "Media"),
        StoreFilterTab.TIME to (if (isZh) "时间" else "Time"),
        StoreFilterTab.SYSTEM to (if (isZh) "系统" else "System"),
        StoreFilterTab.INFO to (if (isZh) "通知" else "Alerts"),
        StoreFilterTab.PRODUCTIVITY to (if (isZh) "效率" else "Productivity"),
        StoreFilterTab.INSTALLED to (if (isZh) "已添加管理 ($installedCount)" else "Installed ($installedCount)"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { (tab, label) ->
            val active = tab == selectedTab
            val bg = if (active) OpalPalette.AccentBlue else OpalPalette.ChipFill
            val borderCol = if (active) Color.White.copy(alpha = 0.28f) else OpalPalette.BorderHairline
            val textCol = if (active) Color.White else OpalPalette.TextPrimary
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .border(0.8.dp, borderCol, CircleShape)
                    .clickable { onSelectTab(tab) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                OpalText(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = textCol,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StoreWidgetCard(
    spec: DesktopWidgetSpec,
    isZh: Boolean,
    installedCount: Int,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val accent = Color(spec.accentColorHex)
    val shape = RoundedCornerShape(22.dp)
    val canAdd = spec.allowMultiple || installedCount == 0
    val cardFill = Brush.verticalGradient(
        listOf(
            accent.copy(alpha = if (colors.isDark) 0.14f else 0.10f),
            colors.glassSurface,
        ),
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(cardFill)
            .border(1.dp, OpalPalette.LiquidGlassRim, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WidgetBadgeIcon(type = spec.type, accent = accent)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                OpalText(
                    text = if (isZh) spec.titleZh else spec.titleEn,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = OpalPalette.TextPrimary,
                    maxLines = 1,
                )
                OpalText(
                    text = if (isZh) spec.titleEn else spec.titleZh,
                    fontSize = 10.5.sp,
                    color = OpalPalette.TextMuted,
                    maxLines = 1,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installedCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.AccentEmerald.copy(alpha = if (colors.isDark) 0.24f else 0.16f))
                            .border(0.7.dp, OpalPalette.AccentEmerald.copy(alpha = 0.45f), CircleShape)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        OpalText(
                            text = if (isZh) "已添加 $installedCount" else "Added $installedCount",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.AccentEmerald,
                            maxLines = 1,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(OpalPalette.ChipFill)
                        .border(0.7.dp, OpalPalette.BorderHairline, RoundedCornerShape(7.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    OpalText(
                        text = "${spec.defaultColSpan}×${spec.defaultRowSpan}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        WidgetMiniPreview(type = spec.type, accent = accent)

        OpalText(
            text = if (isZh) spec.subtitleZh else spec.subtitleEn,
            fontSize = 12.sp,
            lineHeight = 16.5.sp,
            color = OpalPalette.TextSecondary,
            maxLines = 2,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = "${spec.minColSpan}×${spec.minRowSpan} – ${spec.maxColSpan}×${spec.maxRowSpan}",
                fontSize = 10.5.sp,
                color = OpalPalette.TextMuted,
            )
            OpalButton(
                label = when {
                    !canAdd && isZh -> "已在桌面"
                    !canAdd -> "On Desktop"
                    isZh -> "+ 添加到桌面"
                    else -> "+ Add to Desktop"
                },
                onClick = onAdd,
                accent = accent,
                filled = canAdd,
                enabled = canAdd,
                compact = true,
            )
        }
    }
}

@Composable
private fun InstalledWidgetsSection(
    isZh: Boolean,
    instances: List<DesktopWidgetInstance>,
    onRemoveWidget: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onEnterEditMode: () -> Unit,
) {
    val colors = LocalOpalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OpalPalette.ChipFill)
            .border(0.8.dp, OpalPalette.BorderHairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OpalText(
            text = if (isZh) "桌面共有 ${instances.size} 个运行中的组件" else "${instances.size} active widgets on desktop",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpalButton(
                label = if (isZh) "重置默认" else "Reset Defaults",
                onClick = onResetDefaults,
                accent = OpalPalette.AccentAmber,
                filled = false,
                compact = true,
            )
            OpalButton(
                label = if (isZh) "进入桌面编辑" else "Edit on Desktop",
                onClick = onEnterEditMode,
                accent = OpalPalette.AccentBlue,
                filled = true,
                compact = true,
            )
        }
    }

    if (instances.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OpalText(
                text = if (isZh) "桌面上还没有任何组件" else "No widgets on desktop yet",
                fontSize = 14.sp,
                color = OpalPalette.TextMuted,
            )
            OpalButton(
                label = if (isZh) "恢复推荐默认布局" else "Restore Default Layout",
                onClick = onResetDefaults,
                compact = true,
            )
        }
        return
    }

    instances.forEach { item ->
        val spec = DesktopWidgetCatalog.specOf(item.type)
        val accent = Color(spec.accentColorHex)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.glassSurface)
                .border(0.9.dp, OpalPalette.BorderSubtle, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WidgetBadgeIcon(type = item.type, accent = accent)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OpalText(
                    text = if (isZh) spec.titleZh else spec.titleEn,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                    maxLines = 1,
                )
                OpalText(
                    text = if (isZh) {
                        "位置 (${item.col + 1}, ${item.row + 1}) · 尺寸 ${item.colSpan}×${item.rowSpan}"
                    } else {
                        "Grid (${item.col + 1}, ${item.row + 1}) · Size ${item.colSpan}×${item.rowSpan}"
                    },
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
            OpalButton(
                label = if (isZh) "移除" else "Remove",
                onClick = { onRemoveWidget(item.id) },
                accent = OpalPalette.AccentDanger,
                filled = false,
                compact = true,
            )
        }
    }
}
