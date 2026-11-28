package io.github.lengsukq.opaldesk.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
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

    val win = focusedWindow?.takeIf { !it.isMinimized && it.isLive }
    val isCompositorSelfEditor = editorState.packageName == "io.github.lengsukq.opaldesk"
    if (!pluginState.pluginConnected) return

    var lastLangMode by remember { mutableStateOf<ImeLanguageMode?>(null) }
    var showLangToast by remember { mutableStateOf(false) }
    LaunchedEffect(pluginState.languageMode) {
        val prev = lastLangMode
        lastLangMode = pluginState.languageMode
        if (prev != null && prev != pluginState.languageMode) {
            showLangToast = true
            delay(1_100L)
            showLangToast = false
        }
    }

    val density = LocalDensity.current
    val headerPx = with(density) { CompositorGeometry.HEADER_HEIGHT_DP.dp.toPx() }.roundToInt()
    val topSafePx = with(density) { (CompositorGeometry.TOP_BAR_DP + 6f).dp.toPx() }.roundToInt()
    val edgeMarginPx = with(density) { 10.dp.toPx() }.roundToInt()

    val showSoftKeyboard = softKbdVisible && editorState.inputActive && editorState.acceptingText
    val showFloatingCandidateBar = pluginState.isComposing && (!showSoftKeyboard || floatingKbdMode)
    val showLangBadge = showLangToast && !showFloatingCandidateBar && !showSoftKeyboard

    if (!showFloatingCandidateBar && !showSoftKeyboard && !showLangBadge) return

    Box(modifier = modifier.fillMaxSize()) {
        if (showFloatingCandidateBar || showLangBadge) {
            val caret = remember(win, isCompositorSelfEditor, cursorAnchor, tapAnchor, headerPx, canvasW, canvasH) {
                if (win != null && !isCompositorSelfEditor) {
                    OpalDeskImeChannelCore.mapDisplayAnchorToCanvas(
                        window = win,
                        cursorAnchor = cursorAnchor,
                        tapAnchor = tapAnchor,
                        headerHeightPx = headerPx,
                    )
                } else if (cursorAnchor.valid) {
                    CanvasCaretAnchor(
                        valid = true,
                        x = cursorAnchor.insertionMarkerHorizontal.coerceIn(0f, canvasW.toFloat()),
                        topY = cursorAnchor.insertionMarkerTop.coerceIn(0f, canvasH.toFloat()),
                        bottomY = cursorAnchor.insertionMarkerBottom.coerceIn(0f, canvasH.toFloat()),
                    )
                } else {
                    CanvasCaretAnchor(
                        valid = true,
                        x = (canvasW * 0.35f).coerceAtLeast(edgeMarginPx.toFloat()),
                        topY = canvasH * 0.58f,
                        bottomY = canvasH * 0.62f,
                    )
                }
            }
            var barWidthPx by remember { mutableIntStateOf(with(density) { (if (showFloatingCandidateBar) 420.dp else 72.dp).toPx() }.roundToInt()) }
            var barHeightPx by remember { mutableIntStateOf(with(density) { (if (showFloatingCandidateBar) 64.dp else 32.dp).toPx() }.roundToInt()) }
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

            if (showFloatingCandidateBar) {
                OpalYuyanFloatingCandidateBar(
                    state = pluginState,
                    modifier = Modifier
                        .offset { IntOffset(clampedX, clampedY) }
                        .onSizeChanged { sz ->
                            if (sz.width > 0) barWidthPx = sz.width
                            if (sz.height > 0) barHeightPx = sz.height
                        },
                )
            } else {
                val isCn = pluginState.languageMode == ImeLanguageMode.CHINESE
                Box(
                    modifier = Modifier
                        .offset { IntOffset(clampedX, clampedY) }
                        .onSizeChanged { sz ->
                            if (sz.width > 0) barWidthPx = sz.width
                            if (sz.height > 0) barHeightPx = sz.height
                        }
                        .liquidGlassSurface(
                            shape = RoundedCornerShape(10.dp),
                            fill = OpalPalette.SolidPopoverFill,
                            rim = OpalPalette.LiquidGlassRim,
                            elevation = 16.dp,
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    OpalText(
                        text = if (isCn) "中 · 语燕" else "EN",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCn) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        maxLines = 1,
                    )
                }
            }
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
internal fun CandidateStripRow(
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
