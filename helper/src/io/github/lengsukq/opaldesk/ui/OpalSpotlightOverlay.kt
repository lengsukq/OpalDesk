package io.github.lengsukq.opaldesk.ui

import android.view.Display
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.search.OpalSpotlightEngine
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

/**
 * Pure JVM-testable top-inset layout policy for [OpalSpotlightOverlay] so punch-hole cameras,
 * notches, and status bars never occlude the search input on phones or tablets while preserving
 * centered upper placement on external monitors.
 */
internal object SpotlightLayoutPolicy {
    const val COMPACT_MIN_TOP_OFFSET_DP = 64f
    const val COMPACT_CUTOUT_CLEARANCE_DP = 24f
    const val WIDE_MIN_TOP_OFFSET_DP = 44f
    const val WIDE_MAX_TOP_OFFSET_DP = 92f
    const val WIDE_CUTOUT_CLEARANCE_DP = 20f

    fun computeTopOffsetDp(
        maxHeightDp: Float,
        isCompact: Boolean,
        safeTopInsetDp: Float,
    ): Float {
        val inset = safeTopInsetDp.coerceAtLeast(0f)
        val safeFloor = inset + if (isCompact) COMPACT_CUTOUT_CLEARANCE_DP else WIDE_CUTOUT_CLEARANCE_DP
        val preferred = if (isCompact) {
            maxOf(COMPACT_MIN_TOP_OFFSET_DP, safeFloor)
        } else {
            maxOf((maxHeightDp * 0.10f).coerceIn(WIDE_MIN_TOP_OFFSET_DP, WIDE_MAX_TOP_OFFSET_DP), safeFloor)
        }
        val maxAllowed = (maxHeightDp * 0.28f).coerceAtLeast(safeFloor)
        return preferred.coerceAtMost(maxAllowed)
    }
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
    placeholder: String = "",
    quickChips: List<Pair<String, String>> = emptyList(),
    controller: OpalSpotlightController = rememberOpalSpotlightController(),
    iconProvider: (@Composable (SpotlightItem) -> ImageBitmap?)? = null,
    onSelectItem: (SpotlightItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val resolvedPlaceholder = placeholder.ifBlank { stringResource(R.string.spotlight_default_placeholder) }
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

    val recentHeader = stringResource(R.string.spotlight_section_recent)
    val runningWinHeader = stringResource(R.string.spotlight_section_windows)
    val (listEntries, flatSelectable) = remember(results, trimmedQuery, recentIds) {
        buildDisplayEntries(
            results = results.map { it.item },
            isEmptyQuery = trimmedQuery.isEmpty(),
            recentIdSet = recentIds.toSet(),
            ctx = ctx,
            recentHeader = recentHeader,
            runningWinHeader = runningWinHeader,
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
        val density = LocalDensity.current
        val cutoutInsetPx = maxOf(
            WindowInsets.displayCutout.getTop(density),
            hostView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0,
        )
        val statusInsetPx = WindowInsets.statusBars.getTop(density)
        val safeTopInsetDp = with(density) { maxOf(cutoutInsetPx, statusInsetPx).toDp() }
        val topOffset = SpotlightLayoutPolicy.computeTopOffsetDp(
            maxHeightDp = maxHeight.value,
            isCompact = isCompact,
            safeTopInsetDp = safeTopInsetDp.value,
        ).dp
        val panelMaxH = (maxHeight - topOffset - 24.dp).coerceIn(200.dp, 560.dp)
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
                placeholder = resolvedPlaceholder,
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
                text = if (isCompact) stringResource(R.string.common_cancel) else "Esc",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (isCompact) null else FontFamily.Monospace,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
    }
}
