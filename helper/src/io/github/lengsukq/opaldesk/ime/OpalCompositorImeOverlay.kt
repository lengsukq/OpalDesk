package io.github.lengsukq.opaldesk.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.DexImeCatalog
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlin.math.roundToInt

/**
 * Pure-Compose Cross-Display Compositor IME Overlay (`OpalCompositorImeOverlay`):
 *
 * Keeps `:helper` 100% free of embedded dictionary bloat while rendering live composition,
 * cursor-following candidates, and a dockable/floating Yuyan desktop soft keyboard directly inside
 * `OpalCompositorWorkspace` when the cert-verified `:ime-plugin` (`io.github.lengsukq.opaldesk.ime`)
 * is installed and connected.
 *
 * Architectural Wins:
 * 1. **Zero Small-Window Clipping**: Candidate bars and soft keyboards are rendered in full canvas
 *    coordinates rather than squeezed inside a 360dp `OpalWin-*` VirtualDisplay.
 * 2. **Zero Focus Theft**: All candidate & key taps are pointer gestures on a `FLAG_NOT_FOCUSABLE`
 *    compositor host window, so `TopFocusedDisplayId` and `InputConnection` stay locked on the target app.
 * 3. **Orientation-Agnostic**: Eliminates stock YuyanIme's multi-orientation layout bug (#406) by
 *    computing metrics dynamically from `canvasW` × `canvasH`.
 */
@Composable
fun OpalCompositorImeOverlay(
    focusedWindow: CompositorWindow?,
    canvasW: Int,
    canvasH: Int,
    dockInsetDp: Float,
    modifier: Modifier = Modifier,
) {
    val pluginState by OpalDeskImeChannel.pluginStateFlow.collectAsState()
    val editorState by OpalDeskImeChannel.editorStateFlow.collectAsState()
    val cursorAnchor by OpalDeskImeChannel.cursorAnchorFlow.collectAsState()
    val softKbdVisible by OpalDeskImeChannel.softKeyboardVisibleFlow.collectAsState()
    val floatingKbdMode by OpalDeskImeChannel.floatingKeyboardModeFlow.collectAsState()
    val tapAnchor by OpalDeskImeChannel.lastTapAnchorFlow.collectAsState()

    val win = focusedWindow?.takeIf { !it.isMinimized && it.isLive } ?: return
    if (!pluginState.pluginConnected) return

    val density = LocalDensity.current
    val headerPx = with(density) { CompositorGeometry.HEADER_HEIGHT_DP.dp.toPx() }.roundToInt()
    val topSafePx = with(density) { (CompositorGeometry.TOP_BAR_DP + 6f).dp.toPx() }.roundToInt()
    val edgeMarginPx = with(density) { 10.dp.toPx() }.roundToInt()

    val showSoftKeyboard = softKbdVisible && editorState.inputActive && editorState.acceptingText
    val showFloatingCandidateBar = pluginState.isComposing && (!showSoftKeyboard || floatingKbdMode)

    if (!showFloatingCandidateBar && !showSoftKeyboard) return

    Box(modifier = modifier.fillMaxSize()) {
        if (showFloatingCandidateBar) {
            val caret = remember(win, cursorAnchor, tapAnchor, headerPx) {
                OpalDeskImeChannelCore.mapDisplayAnchorToCanvas(
                    window = win,
                    cursorAnchor = cursorAnchor,
                    tapAnchor = tapAnchor,
                    headerHeightPx = headerPx,
                )
            }
            var barWidthPx by remember { mutableIntStateOf(with(density) { 420.dp.toPx() }.roundToInt()) }
            var barHeightPx by remember { mutableIntStateOf(with(density) { 64.dp.toPx() }.roundToInt()) }
            val gapPx = with(density) { 8.dp.toPx() }

            val maxX = (canvasW - barWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            val clampedX = caret.x.roundToInt().coerceIn(edgeMarginPx, maxX)
            val belowY = (caret.bottomY + gapPx).roundToInt()
            val aboveY = (caret.topY - barHeightPx - gapPx).roundToInt()
            val maxBottomY = (canvasH - barHeightPx - edgeMarginPx).coerceAtLeast(topSafePx)
            val clampedY = if (belowY <= maxBottomY) {
                belowY.coerceAtLeast(topSafePx)
            } else {
                aboveY.coerceIn(topSafePx, maxBottomY)
            }

            OpalYuyanFloatingCandidateBar(
                state = pluginState,
                modifier = Modifier
                    .offset { IntOffset(clampedX, clampedY) }
                    .onSizeChanged { sz ->
                        if (sz.width > 0) barWidthPx = sz.width
                        if (sz.height > 0) barHeightPx = sz.height
                    },
            )
        }

        if (showSoftKeyboard) {
            if (floatingKbdMode) {
                var dragOffsetX by remember(canvasW) { mutableFloatStateOf(0f) }
                var dragOffsetY by remember(canvasH) { mutableFloatStateOf(0f) }
                var kbdW by remember { mutableIntStateOf(with(density) { 460.dp.toPx() }.roundToInt()) }
                var kbdH by remember { mutableIntStateOf(with(density) { 260.dp.toPx() }.roundToInt()) }
                val baseX = ((canvasW - kbdW) * 0.5f).coerceAtLeast(edgeMarginPx.toFloat())
                val baseY = (canvasH - kbdH - with(density) { (dockInsetDp + 16f).dp.toPx() })
                    .coerceAtLeast(topSafePx.toFloat())
                val finalX = (baseX + dragOffsetX).roundToInt()
                    .coerceIn(edgeMarginPx, (canvasW - kbdW - edgeMarginPx).coerceAtLeast(edgeMarginPx))
                val finalY = (baseY + dragOffsetY).roundToInt()
                    .coerceIn(topSafePx, (canvasH - kbdH - edgeMarginPx).coerceAtLeast(topSafePx))

                OpalYuyanDesktopSoftKeyboard(
                    state = pluginState,
                    floatingMode = true,
                    onDragDelta = { dx, dy ->
                        dragOffsetX += dx
                        dragOffsetY += dy
                    },
                    modifier = Modifier
                        .offset { IntOffset(finalX, finalY) }
                        .widthIn(max = 468.dp)
                        .onSizeChanged { sz ->
                            if (sz.width > 0) kbdW = sz.width
                            if (sz.height > 0) kbdH = sz.height
                        },
                )
            } else {
                val bottomPad = (if (dockInsetDp > 4f) dockInsetDp + 6f else 10f).dp
                OpalYuyanDesktopSoftKeyboard(
                    state = pluginState,
                    floatingMode = false,
                    onDragDelta = { _, _ -> },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = bottomPad)
                        .widthIn(max = 780.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OpalYuyanFloatingCandidateBar(
    state: PluginCompositionSnapshot,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = modifier
            .widthIn(min = 240.dp, max = 640.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.SolidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 22.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Top preedit + schema chip row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpalText(
                text = state.displayComposing.ifEmpty { state.rawBuffer },
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = OpalPalette.AccentBlue,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(OpalPalette.ChipFill)
                    .clickable { OpalDeskImeChannel.cycleSchema() }
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                OpalText(
                    text = state.schemaLabel,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }

        // Numbered horizontal candidates row
        CandidateStripRow(state = state)
    }
}

@Composable
private fun CandidateStripRow(
    state: PluginCompositionSnapshot,
    modifier: Modifier = Modifier,
) {
    val visible = state.visibleCandidates
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (visible.isEmpty()) {
            OpalText(
                text = "空格上屏原串",
                fontSize = 12.sp,
                color = OpalPalette.TextMuted,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        } else {
            visible.forEachIndexed { idx, word ->
                val highlighted = idx == state.highlightedInPage
                val pillShape = RoundedCornerShape(9.dp)
                Row(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(
                            if (highlighted) {
                                OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.24f else 0.14f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .border(
                            width = if (highlighted) 0.8.dp else 0.dp,
                            color = if (highlighted) OpalPalette.AccentBlue.copy(alpha = 0.45f) else Color.Transparent,
                            shape = pillShape,
                        )
                        .clickable { OpalDeskImeChannel.onSelectVisibleCandidate(idx) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    OpalText(
                        text = "${idx + 1}.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (highlighted) OpalPalette.AccentBlue else OpalPalette.TextMuted,
                    )
                    OpalText(
                        text = word,
                        fontSize = 14.sp,
                        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (highlighted) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        maxLines = 1,
                    )
                }
            }
        }

        if (state.pageCount > 1) {
            Spacer(Modifier.width(4.dp))
            CandidatePageButton(
                label = "‹",
                enabled = state.pageIndex > 0,
                onClick = { OpalDeskImeChannel.onPrevCandidatePage() },
            )
            OpalText(
                text = "${state.pageIndex + 1}/${state.pageCount}",
                fontSize = 10.5.sp,
                color = OpalPalette.TextMuted,
            )
            CandidatePageButton(
                label = "›",
                enabled = state.pageIndex < state.pageCount - 1,
                onClick = { OpalDeskImeChannel.onNextCandidatePage() },
            )
        }
    }
}

@Composable
private fun CandidatePageButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(OpalPalette.ChipFill)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) OpalPalette.TextPrimary else OpalPalette.TextMuted.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun OpalYuyanDesktopSoftKeyboard(
    state: PluginCompositionSnapshot,
    floatingMode: Boolean,
    onDragDelta: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var symbolLayer by remember { mutableStateOf(false) }
    var shiftUpper by remember { mutableStateOf(false) }
    val isT9 = !symbolLayer && state.languageMode == ImeLanguageMode.CHINESE && state.schemaId == "t9"
    val keyHeight = if (floatingMode) 37.dp else 42.dp
    val shape = RoundedCornerShape(if (floatingMode) 18.dp else 20.dp)

    Column(
        modifier = modifier
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.SolidPopoverFill,
                rim = OpalPalette.SolidPopoverRim,
                elevation = 26.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(horizontal = if (floatingMode) 8.dp else 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Top Toolbar / Integrated Composing & Candidate Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (floatingMode) {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDragDelta(dragAmount.x, dragAmount.y)
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (!floatingMode && state.isComposing) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpalText(
                        text = state.displayComposing.ifEmpty { state.rawBuffer },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = OpalPalette.AccentBlue,
                        maxLines = 1,
                    )
                    CandidateStripRow(state = state)
                }
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (floatingMode) {
                        OpalText(
                            text = "⠿ 语燕",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = OpalPalette.AccentBlue,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    DexImeCatalog.YUYAN_PLUGIN_SCHEMAS.forEach { (id, label) ->
                        val active = state.languageMode == ImeLanguageMode.CHINESE && state.schemaId == id
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (active) {
                                        OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.24f else 0.14f)
                                    } else {
                                        OpalPalette.ChipFill
                                    },
                                )
                                .clickable {
                                    symbolLayer = false
                                    if (state.languageMode == ImeLanguageMode.ENGLISH) {
                                        OpalDeskImeChannel.toggleLanguageMode()
                                    }
                                    OpalDeskImeChannel.selectSchema(id)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            OpalText(
                                text = label,
                                fontSize = 10.5.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ToolbarChipButton(
                    label = if (state.languageMode == ImeLanguageMode.CHINESE) "中" else "EN",
                    active = state.languageMode == ImeLanguageMode.CHINESE,
                    onClick = { OpalDeskImeChannel.toggleLanguageMode() },
                )
                ToolbarChipButton(
                    label = if (symbolLayer) "返回" else "?123",
                    active = symbolLayer,
                    onClick = { symbolLayer = !symbolLayer },
                )
                ToolbarChipButton(
                    label = if (floatingMode) "贴底" else "悬浮",
                    active = floatingMode,
                    onClick = { OpalDeskImeChannel.toggleFloatingKeyboardMode() },
                )
                ToolbarChipButton(
                    label = "收起",
                    active = false,
                    onClick = { OpalDeskImeChannel.hideSoftKeyboard() },
                )
            }
        }

        when {
            symbolLayer -> SymbolKeyboardGrid(
                isChinese = state.languageMode == ImeLanguageMode.CHINESE,
                keyHeight = keyHeight,
                onBackToLetters = { symbolLayer = false },
            )
            isT9 -> YuyanT9KeyboardGrid(
                state = state,
                keyHeight = if (floatingMode) 42.dp else 46.dp,
            )
            else -> YuyanQwertyKeyboardGrid(
                state = state,
                shiftUpper = shiftUpper,
                keyHeight = keyHeight,
                onToggleShift = { shiftUpper = !shiftUpper },
                onClearOneShotShift = { if (shiftUpper) shiftUpper = false },
            )
        }
    }
}

@Composable
private fun ToolbarChipButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) {
                    OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.24f else 0.14f)
                } else {
                    OpalPalette.ChipFill
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun YuyanQwertyKeyboardGrid(
    state: PluginCompositionSnapshot,
    shiftUpper: Boolean,
    keyHeight: Dp,
    onToggleShift: () -> Unit,
    onClearOneShotShift: () -> Unit,
) {
    val row1 = "qwertyuiop"
    val row2 = "asdfghjkl"
    val row3 = "zxcvbnm"
    val isCn = state.languageMode == ImeLanguageMode.CHINESE

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // Row 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row1.forEach { ch ->
                val out = if (shiftUpper) ch.uppercaseChar() else ch
                SoftKeyCap(
                    label = out.toString(),
                    height = keyHeight,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        OpalDeskImeChannel.onSoftKeyChar(out)
                        onClearOneShotShift()
                    },
                )
            }
        }
        // Row 2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row2.forEach { ch ->
                val out = if (shiftUpper) ch.uppercaseChar() else ch
                SoftKeyCap(
                    label = out.toString(),
                    height = keyHeight,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        OpalDeskImeChannel.onSoftKeyChar(out)
                        onClearOneShotShift()
                    },
                )
            }
        }
        // Row 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SoftKeyCap(
                label = if (shiftUpper) "⇧ 大写" else "⇧",
                height = keyHeight,
                accent = if (shiftUpper) OpalPalette.AccentBlue else null,
                modifier = Modifier.weight(1.35f),
                onClick = onToggleShift,
            )
            row3.forEach { ch ->
                val out = if (shiftUpper) ch.uppercaseChar() else ch
                SoftKeyCap(
                    label = out.toString(),
                    height = keyHeight,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        OpalDeskImeChannel.onSoftKeyChar(out)
                        onClearOneShotShift()
                    },
                )
            }
            SoftKeyCap(
                label = "⌫",
                height = keyHeight,
                functional = true,
                modifier = Modifier.weight(1.35f),
                onClick = { OpalDeskImeChannel.onSoftBackspace() },
            )
        }
        // Row 4: Bottom bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SoftKeyCap(
                label = if (isCn) "中/英" else "EN/中",
                height = keyHeight,
                functional = true,
                modifier = Modifier.weight(1.3f),
                onClick = { OpalDeskImeChannel.toggleLanguageMode() },
            )
            SoftKeyCap(
                label = if (isCn) "，" else ",",
                height = keyHeight,
                functional = true,
                modifier = Modifier.weight(0.95f),
                onClick = { OpalDeskImeChannel.onSoftKeyChar(if (isCn) '，' else ',') },
            )
            SoftKeyCap(
                label = if (isCn) "${state.schemaLabel} · 空格" else "English · Space",
                height = keyHeight,
                modifier = Modifier.weight(4.2f),
                onClick = { OpalDeskImeChannel.onSoftSpace() },
            )
            SoftKeyCap(
                label = if (isCn) "。" else ".",
                height = keyHeight,
                functional = true,
                modifier = Modifier.weight(0.95f),
                onClick = { OpalDeskImeChannel.onSoftKeyChar(if (isCn) '。' else '.') },
            )
            SoftKeyCap(
                label = if (isCn) "？" else "'",
                height = keyHeight,
                functional = true,
                modifier = Modifier.weight(0.95f),
                onClick = { OpalDeskImeChannel.onSoftKeyChar(if (isCn) '？' else '\'') },
            )
            SoftKeyCap(
                label = "回车 ↵",
                height = keyHeight,
                accent = OpalPalette.AccentBlue,
                modifier = Modifier.weight(1.55f),
                onClick = { OpalDeskImeChannel.onSoftEnter() },
            )
        }
    }
}

@Composable
private fun YuyanT9KeyboardGrid(
    state: PluginCompositionSnapshot,
    keyHeight: Dp,
) {
    val leftPuncts = listOf("，", "。", "？", "！")
    val t9Rows = listOf(
        listOfTriple('1', "分词/'", '\''),
        listOfTriple('2', "ABC", '2'),
        listOfTriple('3', "DEF", '3'),
        listOfTriple('4', "GHI", '4'),
        listOfTriple('5', "JKL", '5'),
        listOfTriple('6', "MNO", '6'),
        listOfTriple('7', "PQRS", '7'),
        listOfTriple('8', "TUV", '8'),
        listOfTriple('9', "WXYZ", '9'),
    ).chunked(3)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Left punctuation column
        Column(
            modifier = Modifier.weight(0.95f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            leftPuncts.forEach { p ->
                SoftKeyCap(
                    label = p,
                    height = keyHeight,
                    functional = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { OpalDeskImeChannel.onSoftKeyChar(p[0]) },
                )
            }
        }

        // Center 3x3 + bottom space row
        Column(
            modifier = Modifier.weight(3.2f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            t9Rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    rowItems.forEach { item ->
                        SoftKeyCap(
                            label = "${item.first} ${item.second}",
                            height = keyHeight,
                            modifier = Modifier.weight(1f),
                            onClick = { OpalDeskImeChannel.onSoftKeyChar(item.third) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SoftKeyCap(
                    label = "中/英",
                    height = keyHeight,
                    functional = true,
                    modifier = Modifier.weight(1f),
                    onClick = { OpalDeskImeChannel.toggleLanguageMode() },
                )
                SoftKeyCap(
                    label = "${state.schemaLabel} · 空格",
                    height = keyHeight,
                    modifier = Modifier.weight(2f),
                    onClick = { OpalDeskImeChannel.onSoftSpace() },
                )
            }
        }

        // Right action column
        Column(
            modifier = Modifier.weight(1.1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SoftKeyCap(
                label = "⌫ 退格",
                height = keyHeight,
                functional = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { OpalDeskImeChannel.onSoftBackspace() },
            )
            SoftKeyCap(
                label = "全拼 26键",
                height = keyHeight,
                functional = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { OpalDeskImeChannel.selectSchema("pinyin") },
            )
            SoftKeyCap(
                label = "0 数字",
                height = keyHeight,
                functional = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { OpalDeskImeChannel.onSoftKeyChar('0') },
            )
            SoftKeyCap(
                label = "回车 ↵",
                height = keyHeight,
                accent = OpalPalette.AccentBlue,
                modifier = Modifier.fillMaxWidth(),
                onClick = { OpalDeskImeChannel.onSoftEnter() },
            )
        }
    }
}

private fun listOfTriple(a: Char, b: String, c: Char): Triple<Char, String, Char> = Triple(a, b, c)

@Composable
private fun SymbolKeyboardGrid(
    isChinese: Boolean,
    keyHeight: Dp,
    onBackToLetters: () -> Unit,
) {
    val row1 = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    val row2 = if (isChinese) {
        listOf('，', '。', '？', '！', '：', '；', '“', '”', '（', '）')
    } else {
        listOf('@', '#', '$', '%', '&', '*', '-', '+', '(', ')')
    }
    val row3 = if (isChinese) {
        listOf('、', '@', '#', '¥', '%', '-', '+', '=', '/', '…')
    } else {
        listOf('!', '"', '\'', ':', ';', '/', '?', '_', '=', '~')
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(row1, row2, row3).forEach { chars ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                chars.forEach { ch ->
                    SoftKeyCap(
                        label = ch.toString(),
                        height = keyHeight,
                        modifier = Modifier.weight(1f),
                        onClick = { OpalDeskImeChannel.onSoftKeyChar(ch) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SoftKeyCap(
                label = "ABC 返回",
                height = keyHeight,
                functional = true,
                modifier = Modifier.weight(1.6f),
                onClick = onBackToLetters,
            )
            SoftKeyCap(
                label = "空格",
                height = keyHeight,
                modifier = Modifier.weight(4.4f),
                onClick = { OpalDeskImeChannel.onSoftSpace() },
            )
            SoftKeyCap(
                label = "⌫",
                height = keyHeight,
                functional = true,
                modifier = Modifier.weight(1.3f),
                onClick = { OpalDeskImeChannel.onSoftBackspace() },
            )
            SoftKeyCap(
                label = "回车 ↵",
                height = keyHeight,
                accent = OpalPalette.AccentBlue,
                modifier = Modifier.weight(1.6f),
                onClick = { OpalDeskImeChannel.onSoftEnter() },
            )
        }
    }
}

@Composable
private fun SoftKeyCap(
    label: String,
    height: Dp,
    modifier: Modifier = Modifier,
    functional: Boolean = false,
    accent: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val isDark = OpalPalette.isDark
    val bg = when {
        accent != null -> accent
        functional -> OpalPalette.ChipFill
        isDark -> Color(0xFF2B3245)
        else -> Color(0xFFFFFFFF)
    }
    val borderCol = when {
        accent != null -> Color.White.copy(alpha = 0.24f)
        else -> OpalPalette.BorderSubtle
    }
    val textCol = when {
        accent != null -> Color.White
        functional -> OpalPalette.TextSecondary
        else -> OpalPalette.TextPrimary
    }
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .border(0.75.dp, borderCol, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            fontSize = if (label.length <= 2) 14.5.sp else 11.5.sp,
            fontWeight = if (accent != null || label.length == 1) FontWeight.SemiBold else FontWeight.Medium,
            color = textCol,
            maxLines = 1,
        )
    }
}
