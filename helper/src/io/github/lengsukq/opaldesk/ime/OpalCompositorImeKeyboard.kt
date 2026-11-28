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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.DexImeCatalog
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface

@Composable
internal fun OpalYuyanDesktopSoftKeyboard(
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
