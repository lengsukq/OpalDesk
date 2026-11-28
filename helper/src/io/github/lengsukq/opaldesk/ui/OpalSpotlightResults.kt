package io.github.lengsukq.opaldesk.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.search.SpotlightCategory
import io.github.lengsukq.opaldesk.search.SpotlightItem

internal sealed interface SpotlightListEntry {
    data class Header(val id: String, val title: String, val count: Int) : SpotlightListEntry
    data class Row(val item: SpotlightItem, val flatIndex: Int) : SpotlightListEntry
}

@Composable
internal fun SpotlightQuickChipsBar(
    quickChips: List<Pair<String, String>>,
    onSelectChip: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        quickChips.forEach { (label, fillQuery) ->
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(OpalPalette.ChipFill)
                    .border(0.75.dp, OpalPalette.BorderHairline, CircleShape)
                    .clickable { onSelectChip(fillQuery) }
                    .padding(horizontal = 10.dp, vertical = 4.5.dp),
            ) {
                OpalText(
                    text = label,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(OpalPalette.DividerColor.copy(alpha = 0.65f)),
    )
}

@Composable
internal fun SpotlightEmptyResults(trimmedQuery: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        OpalText(
            text = stringResource(R.string.spotlight_no_results, trimmedQuery),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextPrimary,
        )
        OpalText(
            text = stringResource(R.string.spotlight_empty_tips),
            fontSize = 11.5.sp,
            color = OpalPalette.TextMuted,
        )
    }
}

@Composable
internal fun SpotlightResultsList(
    listEntries: List<SpotlightListEntry>,
    listState: LazyListState,
    controller: OpalSpotlightController,
    iconProvider: (@Composable (SpotlightItem) -> ImageBitmap?)?,
    onSelectItem: (SpotlightItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(
            count = listEntries.size,
            key = { idx ->
                when (val e = listEntries[idx]) {
                    is SpotlightListEntry.Header -> "hdr_${e.id}"
                    is SpotlightListEntry.Row -> "row_${e.item.id}"
                }
            },
        ) { idx ->
            when (val entry = listEntries[idx]) {
                is SpotlightListEntry.Header -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 8.dp,
                                end = 8.dp,
                                top = if (idx == 0) 2.dp else 8.dp,
                                bottom = 3.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OpalText(
                            text = entry.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.TextMuted,
                        )
                        OpalText(
                            text = "${entry.count}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = OpalPalette.TextMuted,
                        )
                    }
                }
                is SpotlightListEntry.Row -> {
                    SpotlightResultRow(
                        item = entry.item,
                        selected = entry.flatIndex == controller.selectedIndex,
                        appIcon = iconProvider?.invoke(entry.item),
                        onHover = { controller.selectIndex(entry.flatIndex) },
                        onClick = { onSelectItem(entry.item) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SpotlightFooterBar(
    isEmptyQuery: Boolean,
    matchCount: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.7.dp)
            .background(OpalPalette.DividerColor),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OpalText(
            text = if (isEmptyQuery) stringResource(R.string.spotlight_quick_rec) else stringResource(R.string.spotlight_match_count, matchCount),
            fontSize = 10.5.sp,
            color = OpalPalette.TextMuted,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpotlightKeyHint("↑↓", stringResource(R.string.spotlight_hint_select))
            SpotlightKeyHint("↵", stringResource(R.string.spotlight_hint_run))
            SpotlightKeyHint("Esc", stringResource(R.string.common_close))
        }
    }
}

@Composable
private fun SpotlightResultRow(
    item: SpotlightItem,
    selected: Boolean,
    appIcon: ImageBitmap?,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val isDark = OpalPalette.isDark
    val rowShape = RoundedCornerShape(13.dp)
    val bg = if (selected) {
        OpalPalette.AccentBlue.copy(alpha = if (isDark) 0.24f else 0.13f)
    } else {
        Color.Transparent
    }
    val borderColor = if (selected) {
        OpalPalette.AccentBlue.copy(alpha = if (isDark) 0.58f else 0.42f)
    } else {
        Color.Transparent
    }
    val categoryAccent = when (item.category) {
        SpotlightCategory.RUNNING_WINDOW -> OpalPalette.AccentEmerald
        SpotlightCategory.APP -> OpalPalette.AccentBlue
        SpotlightCategory.DEX_ACTION -> OpalPalette.AccentCyan
        SpotlightCategory.CONSOLE_ACTION -> OpalPalette.AccentBlue
        SpotlightCategory.CONSOLE_SECTION -> OpalPalette.TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(bg)
            .border(0.9.dp, borderColor, rowShape)
            .pointerInput(item.id) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Enter || ev.type == PointerEventType.Move) {
                            onHover()
                        }
                    }
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = item.title,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(categoryAccent.copy(alpha = if (isDark) 0.20f else 0.12f))
                    .border(0.7.dp, categoryAccent.copy(alpha = 0.30f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                SpotlightCategoryGlyph(
                    category = item.category,
                    tint = if (selected) OpalPalette.AccentBlue else categoryAccent,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            OpalText(
                text = item.title,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                maxLines = 1,
            )
            if (item.subtitle.isNotBlank()) {
                OpalText(
                    text = item.subtitle,
                    fontSize = 11.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }

        if (item.badge.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            OpalPalette.AccentBlue.copy(alpha = if (isDark) 0.24f else 0.15f)
                        } else {
                            OpalPalette.ChipFill
                        },
                    )
                    .border(
                        0.7.dp,
                        if (selected) OpalPalette.AccentBlue.copy(alpha = 0.45f) else OpalPalette.BorderHairline,
                        CircleShape,
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                OpalText(
                    text = item.badge,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SpotlightCategoryGlyph(
    category: SpotlightCategory,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = 1.55.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (category) {
            SpotlightCategory.RUNNING_WINDOW -> {
                drawRoundRect(tint, Offset(w * 0.08f, h * 0.14f), Size(w * 0.84f, h * 0.72f), CornerRadius(w * 0.16f), style = stroke)
                drawLine(tint, Offset(w * 0.10f, h * 0.36f), Offset(w * 0.90f, h * 0.36f), sw * 0.85f)
            }
            SpotlightCategory.APP -> {
                val r = w * 0.12f
                drawCircle(tint, r, Offset(w * 0.28f, h * 0.28f))
                drawCircle(tint, r, Offset(w * 0.72f, h * 0.28f))
                drawCircle(tint, r, Offset(w * 0.28f, h * 0.72f))
                drawCircle(tint, r, Offset(w * 0.72f, h * 0.72f))
            }
            SpotlightCategory.DEX_ACTION -> {
                drawLine(tint, Offset(w * 0.56f, h * 0.10f), Offset(w * 0.24f, h * 0.54f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.24f, h * 0.54f), Offset(w * 0.54f, h * 0.54f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.54f, h * 0.54f), Offset(w * 0.42f, h * 0.90f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.42f, h * 0.90f), Offset(w * 0.76f, h * 0.44f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, h * 0.44f), Offset(w * 0.48f, h * 0.44f), sw, StrokeCap.Round)
            }
            SpotlightCategory.CONSOLE_ACTION -> {
                drawLine(tint, Offset(w * 0.26f, h * 0.18f), Offset(w * 0.80f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.80f, h * 0.50f), Offset(w * 0.26f, h * 0.82f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.26f, h * 0.82f), Offset(w * 0.26f, h * 0.18f), sw, StrokeCap.Round)
            }
            SpotlightCategory.CONSOLE_SECTION -> {
                drawLine(tint, Offset(w * 0.12f, h * 0.30f), Offset(w * 0.88f, h * 0.30f), sw * 0.9f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.12f, h * 0.70f), Offset(w * 0.88f, h * 0.70f), sw * 0.9f, StrokeCap.Round)
                drawCircle(tint, w * 0.12f, Offset(w * 0.36f, h * 0.30f))
                drawCircle(tint, w * 0.12f, Offset(w * 0.64f, h * 0.70f))
            }
        }
    }
}

@Composable
private fun SpotlightKeyHint(keyLabel: String, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(OpalPalette.ChipFill)
                .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(5.dp))
                .padding(horizontal = 5.dp, vertical = 1.5.dp),
        ) {
            OpalText(
                text = keyLabel,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = OpalPalette.TextSecondary,
            )
        }
        OpalText(
            text = desc,
            fontSize = 10.sp,
            color = OpalPalette.TextMuted,
        )
    }
}

internal fun buildDisplayEntries(
    results: List<SpotlightItem>,
    isEmptyQuery: Boolean,
    recentIdSet: Set<String>,
    ctx: Context,
    recentHeader: String,
    runningWinHeader: String,
): Pair<List<SpotlightListEntry>, List<SpotlightItem>> {
    if (results.isEmpty()) return emptyList<SpotlightListEntry>() to emptyList()
    val entries = ArrayList<SpotlightListEntry>(results.size + 6)
    val flat = ArrayList<SpotlightItem>(results.size)

    if (isEmptyQuery) {
        val recents = results.filter { it.id in recentIdSet }
        val others = results.filter { it.id !in recentIdSet }
        if (recents.isNotEmpty()) {
            entries.add(SpotlightListEntry.Header("recent", recentHeader, recents.size))
            recents.forEach { item ->
                entries.add(SpotlightListEntry.Row(item, flat.size))
                flat.add(item)
            }
        }
        if (others.isNotEmpty()) {
            val groupedOthers = others.groupBy { it.category }
            val orderedCategories = listOf(
                SpotlightCategory.RUNNING_WINDOW,
                SpotlightCategory.DEX_ACTION,
                SpotlightCategory.CONSOLE_ACTION,
                SpotlightCategory.CONSOLE_SECTION,
                SpotlightCategory.APP,
            )
            orderedCategories.forEach { cat ->
                val list = groupedOthers[cat].orEmpty()
                if (list.isNotEmpty()) {
                    val hdr = if (cat == SpotlightCategory.RUNNING_WINDOW) runningWinHeader else ctx.getString(R.string.spotlight_section_quick, cat.displayTitle)
                    entries.add(SpotlightListEntry.Header("pin_${cat.name}", hdr, list.size))
                    list.forEach { item ->
                        entries.add(SpotlightListEntry.Row(item, flat.size))
                        flat.add(item)
                    }
                }
            }
        }
        return entries to flat
    }

    val groupOrder = LinkedHashMap<SpotlightCategory, MutableList<SpotlightItem>>()
    for (item in results) {
        groupOrder.getOrPut(item.category) { ArrayList() }.add(item)
    }
    groupOrder.forEach { (cat, groupItems) ->
        entries.add(SpotlightListEntry.Header(cat.name, cat.displayTitle, groupItems.size))
        groupItems.forEach { item ->
            entries.add(SpotlightListEntry.Row(item, flat.size))
            flat.add(item)
        }
    }
    return entries to flat
}
