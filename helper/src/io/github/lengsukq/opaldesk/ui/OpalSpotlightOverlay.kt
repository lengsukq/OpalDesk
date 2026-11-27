package io.github.lengsukq.opaldesk.ui

import android.view.Display
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.search.OpalSpotlightEngine
import io.github.lengsukq.opaldesk.search.SpotlightCategory
import io.github.lengsukq.opaldesk.search.SpotlightItem
import kotlinx.coroutines.delay

/**
 * Keyboard & selection controller for [OpalSpotlightOverlay] so Host [android.app.Activity]
 * `dispatchKeyEvent` (both `MainActivity` and DeX `DesktopActivity`/`ExternalDesktopActivity`)
 * can drive `↑ / ↓ / Enter / Esc` cleanly before focus traversal or VirtualDisplay forwarding.
 */
@Stable
class OpalSpotlightController {
    var selectedIndex by mutableIntStateOf(0)
        internal set

    internal var currentSelectableItems: List<SpotlightItem> = emptyList()
    internal var onExecuteItem: ((SpotlightItem) -> Unit)? = null
    internal var onDismissOverlay: (() -> Unit)? = null
    private val consumedDownKeys = HashSet<Int>()

    fun resetSelection() {
        selectedIndex = 0
    }

    fun selectIndex(index: Int) {
        if (currentSelectableItems.isEmpty()) {
            selectedIndex = 0
            return
        }
        selectedIndex = index.coerceIn(0, currentSelectableItems.lastIndex)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            return consumedDownKeys.remove(code)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val count = currentSelectableItems.size
                if (count > 0) {
                    selectedIndex = if (selectedIndex <= 0) count - 1 else selectedIndex - 1
                }
                consumedDownKeys.add(code)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val count = currentSelectableItems.size
                if (count > 0) {
                    selectedIndex = (selectedIndex + 1) % count
                }
                consumedDownKeys.add(code)
                return true
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                val chosen = currentSelectableItems.getOrNull(selectedIndex)
                if (chosen != null) {
                    onExecuteItem?.invoke(chosen)
                }
                consumedDownKeys.add(code)
                return true
            }
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BACK,
            -> {
                onDismissOverlay?.invoke()
                consumedDownKeys.add(code)
                return true
            }
            else -> return false
        }
    }
}

@Composable
fun rememberOpalSpotlightController(): OpalSpotlightController = remember { OpalSpotlightController() }

private sealed interface SpotlightListEntry {
    data class Header(val id: String, val title: String, val count: Int) : SpotlightListEntry
    data class Row(val item: SpotlightItem, val flatIndex: Int) : SpotlightListEntry
}

/**
 * Unified macOS Spotlight-style Liquid Glass centered search modal shared by Host Console
 * (`MainActivity`) and DeX Desktop (`OpalCompositorWorkspace`).
 *
 * Built exclusively on `androidx.compose.foundation` + [OpalPrimitives] (zero Material3).
 */
@Composable
fun OpalSpotlightOverlay(
    items: List<SpotlightItem>,
    recentIds: List<String>,
    placeholder: String = "搜索设置、应用、桌面指令或拼音首字母…",
    quickChips: List<Pair<String, String>> = emptyList(),
    controller: OpalSpotlightController = rememberOpalSpotlightController(),
    iconProvider: (@Composable (SpotlightItem) -> ImageBitmap?)? = null,
    onSelectItem: (SpotlightItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostView = LocalView.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            if (fieldFocused) {
                val dispId = hostView.display?.displayId ?: Display.DEFAULT_DISPLAY
                OpalImeFocusTracker.setLocalTextFieldFocused(false, dispId)
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(40L)
        runCatching { focusRequester.requestFocus() }
    }

    val trimmedQuery = query.trim()
    val results = remember(items, trimmedQuery, recentIds) {
        OpalSpotlightEngine.search(
            items = items,
            query = trimmedQuery,
            recentIds = recentIds,
            maxResults = if (trimmedQuery.isEmpty()) 14 else 32,
        )
    }

    val (listEntries, flatSelectable) = remember(results, trimmedQuery, recentIds) {
        buildDisplayEntries(
            results = results.map { it.item },
            isEmptyQuery = trimmedQuery.isEmpty(),
            recentIdSet = recentIds.toSet(),
        )
    }

    LaunchedEffect(flatSelectable) {
        controller.currentSelectableItems = flatSelectable
        if (controller.selectedIndex >= flatSelectable.size) {
            controller.resetSelection()
        }
    }

    controller.currentSelectableItems = flatSelectable
    controller.onExecuteItem = onSelectItem
    controller.onDismissOverlay = onDismiss

    // Auto-scroll LazyColumn when navigating with ↑/↓ hardware keys
    LaunchedEffect(controller.selectedIndex, listEntries) {
        if (flatSelectable.isEmpty()) return@LaunchedEffect
        val targetListIdx = listEntries.indexOfFirst {
            it is SpotlightListEntry.Row && it.flatIndex == controller.selectedIndex
        }
        if (targetListIdx >= 0) {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.any { it.index == targetListIdx }
            if (!visible) {
                runCatching { listState.animateScrollToItem((targetListIdx - 1).coerceAtLeast(0)) }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor.copy(alpha = if (OpalPalette.isDark) 0.52f else 0.34f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        val isCompact = maxWidth < 520.dp
        val topOffset = if (isCompact) 26.dp else (maxHeight * 0.10f).coerceIn(36.dp, 88.dp)
        val panelMaxH = (maxHeight - topOffset - 24.dp).coerceIn(240.dp, 560.dp)
        val panelShape = RoundedCornerShape(24.dp)

        Column(
            modifier = Modifier
                .padding(top = topOffset, start = 14.dp, end = 14.dp)
                .widthIn(max = 580.dp)
                .fillMaxWidth()
                .heightIn(max = panelMaxH)
                .liquidGlassSurface(
                    shape = panelShape,
                    fill = OpalPalette.SolidPopoverFill,
                    rim = OpalPalette.SolidPopoverRim,
                    elevation = 30.dp,
                )
                .pointerInput(Unit) { detectTapGestures {} },
        ) {
            SpotlightSearchHeader(
                query = query,
                placeholder = placeholder,
                isCompact = isCompact,
                focusRequester = focusRequester,
                controller = controller,
                onQueryChange = { next ->
                    query = next
                    controller.resetSelection()
                },
                onFocusChanged = { focused ->
                    fieldFocused = focused
                    val dispId = hostView.display?.displayId ?: Display.DEFAULT_DISPLAY
                    OpalImeFocusTracker.setLocalTextFieldFocused(focused, dispId)
                },
                onDismiss = onDismiss,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(OpalPalette.DividerColor),
            )

            if (trimmedQuery.isEmpty() && quickChips.isNotEmpty()) {
                SpotlightQuickChipsBar(
                    quickChips = quickChips,
                    onSelectChip = { fillQuery ->
                        query = fillQuery
                        controller.resetSelection()
                    },
                )
            }

            if (listEntries.isEmpty()) {
                SpotlightEmptyResults(trimmedQuery = trimmedQuery)
            } else {
                SpotlightResultsList(
                    listEntries = listEntries,
                    listState = listState,
                    controller = controller,
                    iconProvider = iconProvider,
                    onSelectItem = onSelectItem,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                )
            }

            if (!isCompact) {
                SpotlightFooterBar(
                    isEmptyQuery = trimmedQuery.isEmpty(),
                    matchCount = flatSelectable.size,
                )
            }
        }
    }
}

@Composable
private fun SpotlightSearchHeader(
    query: String,
    placeholder: String,
    isCompact: Boolean,
    focusRequester: FocusRequester,
    controller: OpalSpotlightController,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OpalGlyphIcon(
            glyph = ChromeGlyph.SEARCH,
            tint = OpalPalette.AccentBlue,
            size = 18.dp,
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                OpalText(
                    text = placeholder,
                    fontSize = 15.sp,
                    color = OpalPalette.TextMuted,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = OpalPalette.TextPrimary,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(OpalPalette.AccentBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .onPreviewKeyEvent { controller.handleKeyEvent(it.nativeKeyEvent) },
            )
        }

        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(OpalPalette.ChipFill)
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                OpalGlyphIcon(
                    glyph = ChromeGlyph.CLOSE,
                    tint = OpalPalette.TextSecondary,
                    size = 11.dp,
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(OpalPalette.ChipFill)
                .border(0.7.dp, OpalPalette.BorderHairline, CircleShape)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            OpalText(
                text = if (isCompact) "取消" else "Esc",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (isCompact) null else FontFamily.Monospace,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SpotlightQuickChipsBar(
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
private fun SpotlightEmptyResults(trimmedQuery: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        OpalText(
            text = "未找到与「$trimmedQuery」匹配的结果",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextPrimary,
        )
        OpalText(
            text = "试试搜索「DPI」「分辨率」「输入法」「投屏」「UHID」或拼音首字母",
            fontSize = 11.5.sp,
            color = OpalPalette.TextMuted,
        )
    }
}

@Composable
private fun SpotlightResultsList(
    listEntries: List<SpotlightListEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
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
private fun SpotlightFooterBar(
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
            text = if (isEmptyQuery) "快捷推荐" else "共 $matchCount 项匹配",
            fontSize = 10.5.sp,
            color = OpalPalette.TextMuted,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpotlightKeyHint("↑↓", "选择")
            SpotlightKeyHint("↵", "执行")
            SpotlightKeyHint("Esc", "关闭")
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
                // Lightning bolt / action command glyph
                drawLine(tint, Offset(w * 0.56f, h * 0.10f), Offset(w * 0.24f, h * 0.54f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.24f, h * 0.54f), Offset(w * 0.54f, h * 0.54f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.54f, h * 0.54f), Offset(w * 0.42f, h * 0.90f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.42f, h * 0.90f), Offset(w * 0.76f, h * 0.44f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, h * 0.44f), Offset(w * 0.48f, h * 0.44f), sw, StrokeCap.Round)
            }
            SpotlightCategory.CONSOLE_ACTION -> {
                // Play / instant launch triangle
                drawLine(tint, Offset(w * 0.26f, h * 0.18f), Offset(w * 0.80f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.80f, h * 0.50f), Offset(w * 0.26f, h * 0.82f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.26f, h * 0.82f), Offset(w * 0.26f, h * 0.18f), sw, StrokeCap.Round)
            }
            SpotlightCategory.CONSOLE_SECTION -> {
                // Settings sliders glyph
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

private fun buildDisplayEntries(
    results: List<SpotlightItem>,
    isEmptyQuery: Boolean,
    recentIdSet: Set<String>,
): Pair<List<SpotlightListEntry>, List<SpotlightItem>> {
    if (results.isEmpty()) return emptyList<SpotlightListEntry>() to emptyList()
    val entries = ArrayList<SpotlightListEntry>(results.size + 6)
    val flat = ArrayList<SpotlightItem>(results.size)

    if (isEmptyQuery) {
        val recents = results.filter { it.id in recentIdSet }
        val others = results.filter { it.id !in recentIdSet }
        if (recents.isNotEmpty()) {
            entries.add(SpotlightListEntry.Header("recent", "最近使用", recents.size))
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
                    val hdr = if (cat == SpotlightCategory.RUNNING_WINDOW) "运行中窗口" else "常用直达 · ${cat.displayTitle}"
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

    // Active query: group by category in order of first appearance (highest-scoring group first)
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
