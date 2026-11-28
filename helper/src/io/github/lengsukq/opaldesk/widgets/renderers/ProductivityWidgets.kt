package io.github.lengsukq.opaldesk.widgets.renderers

import android.view.Display
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.*
import kotlinx.coroutines.delay
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Quick Notes Liquid-Glass Sticky Widget
// ---------------------------------------------------------------------------

private data class NoteTintOption(
    val accent: Color,
    val labelZh: String,
    val labelEn: String,
)

private val NOTE_TINT_OPTIONS = listOf(
    NoteTintOption(Color(0xFFF59E0B), "暖琥珀", "Amber"),
    NoteTintOption(Color(0xFF06B6D4), "冰青", "Cyan"),
    NoteTintOption(Color(0xFFEC4899), "樱粉", "Sakura"),
    NoteTintOption(Color(0xFF10B981), "薄荷", "Mint"),
)

@Composable
fun QuickNotesWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val hostView = LocalView.current
    val decoded = remember(instance.id, instance.configJson) {
        decodeQuickNotesConfig(instance.configJson)
    }
    var noteText by remember(instance.id) { mutableStateOf(decoded.noteText) }
    var tintIndex by remember(instance.id) { mutableIntStateOf(decoded.tintIndex) }
    var lastSavedJson by remember(instance.id) { mutableStateOf(encodeQuickNotesConfig(decoded)) }
    var isFocused by remember(instance.id) { mutableStateOf(false) }
    var copiedFeedback by remember(instance.id) { mutableStateOf(false) }
    val curCallbacks by rememberUpdatedState(callbacks)

    // Sync external config updates when not actively editing
    LaunchedEffect(instance.configJson) {
        if (!isFocused && instance.configJson != lastSavedJson) {
            val next = decodeQuickNotesConfig(instance.configJson)
            noteText = next.noteText
            tintIndex = next.tintIndex
            lastSavedJson = encodeQuickNotesConfig(next)
        }
    }

    fun persistNow(text: String = noteText, tint: Int = tintIndex) {
        val encoded = encodeQuickNotesConfig(QuickNotesConfig(noteText = text, tintIndex = tint))
        if (encoded != lastSavedJson) {
            lastSavedJson = encoded
            curCallbacks.onUpdateWidgetConfig(instance.id, encoded)
        }
    }

    LaunchedEffect(noteText, tintIndex) {
        delay(450L)
        persistNow(noteText, tintIndex)
    }

    LaunchedEffect(copiedFeedback) {
        if (copiedFeedback) {
            delay(1800L)
            copiedFeedback = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isFocused) {
                val dispId = hostView.display?.displayId ?: Display.DEFAULT_DISPLAY
                OpalImeFocusTracker.setLocalTextFieldFocused(false, dispId)
            }
        }
    }

    val activeTint = NOTE_TINT_OPTIONS[tintIndex.coerceIn(0, NOTE_TINT_OPTIONS.lastIndex)]
    val accent = activeTint.accent

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = accent,
            centerFractionX = 0.88f,
            centerFractionY = 0.14f,
            radiusFraction = 0.82f,
            alpha = if (colors.isDark) 0.24f else 0.16f,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: title badge + 4 tint swatches
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                WidgetGlassBadge(
                    text = if (isZh) "速记便签" else "Quick Note",
                    accent = accent,
                    dotColor = accent,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NOTE_TINT_OPTIONS.forEachIndexed { idx, opt ->
                        val selected = idx == tintIndex
                        Box(
                            modifier = Modifier
                                .size(if (selected) 16.dp else 13.dp)
                                .clip(CircleShape)
                                .background(opt.accent.copy(alpha = if (selected) 0.95f else 0.55f))
                                .border(
                                    width = if (selected) 1.5.dp else 0.75.dp,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.45f),
                                    shape = CircleShape,
                                )
                                .clickable {
                                    tintIndex = idx
                                    persistNow(noteText, idx)
                                },
                        )
                    }
                }
            }

            // Editable frosted note surface
            val editorShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(editorShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accent.copy(alpha = if (colors.isDark) 0.15f else 0.11f),
                                if (colors.isDark) Color(0x1CFFFFFF) else Color(0x6EFFFFFF),
                            ),
                        ),
                    )
                    .border(
                        width = 0.85.dp,
                        color = if (isFocused) accent.copy(alpha = 0.62f) else accent.copy(alpha = 0.28f),
                        shape = editorShape,
                    )
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                if (noteText.isEmpty()) {
                    OpalText(
                        text = if (isZh) "记录灵感、临时片段或剪贴内容…" else "Jot down a quick note or snippet…",
                        color = colors.textMuted,
                        fontSize = 12.5.sp,
                        maxLines = 2,
                    )
                }
                BasicTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { st ->
                            val wasFocused = isFocused
                            isFocused = st.isFocused
                            val dispId = hostView.display?.displayId ?: Display.DEFAULT_DISPLAY
                            OpalImeFocusTracker.setLocalTextFieldFocused(st.isFocused, dispId)
                            if (wasFocused && !st.isFocused) {
                                persistNow(noteText, tintIndex)
                            }
                        },
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = if (sizeClass == WidgetSizeClass.COMPACT) 12.sp else 13.sp,
                        lineHeight = if (sizeClass == WidgetSizeClass.COMPACT) 17.sp else 19.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    cursorBrush = SolidColor(accent),
                )
            }

            // Bottom micro-toolbar: Copy, Paste Clipboard, Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val showLabels = sizeClass != WidgetSizeClass.COMPACT
                WidgetGlassActionButton(
                    onClick = {
                        if (noteText.isNotEmpty()) {
                            callbacks.onCopyText(noteText)
                            copiedFeedback = true
                        }
                    },
                    label = if (showLabels) {
                        if (copiedFeedback) (if (isZh) "已复制" else "Copied") else (if (isZh) "复制" else "Copy")
                    } else {
                        null
                    },
                    accent = if (copiedFeedback) colors.accentEmerald else accent,
                    active = copiedFeedback,
                    circular = !showLabels,
                    size = 28.dp,
                    enabled = noteText.isNotEmpty(),
                    contentDescription = if (isZh) "复制" else "Copy",
                    icon = { tint ->
                        WidgetGlyphIcon(
                            glyph = if (copiedFeedback) WidgetGlyph.CHECK else WidgetGlyph.COPY,
                            tint = tint,
                            size = 12.5.dp,
                        )
                    },
                )
                WidgetGlassActionButton(
                    onClick = {
                        val clip = callbacks.onReadClipboard()?.trim().orEmpty()
                        if (clip.isNotEmpty()) {
                            val updated = if (noteText.isBlank()) clip else "$noteText\n$clip"
                            noteText = updated
                            persistNow(updated, tintIndex)
                        }
                    },
                    label = if (showLabels) (if (isZh) "粘贴" else "Paste") else null,
                    accent = accent,
                    circular = !showLabels,
                    size = 28.dp,
                    contentDescription = if (isZh) "粘贴剪贴板" else "Paste Clipboard",
                    icon = { tint -> WidgetGlyphIcon(WidgetGlyph.PASTE, tint, 12.5.dp) },
                )
                Spacer(Modifier.weight(1f))
                WidgetGlassActionButton(
                    onClick = {
                        noteText = ""
                        persistNow("", tintIndex)
                    },
                    label = if (showLabels) (if (isZh) "清空" else "Clear") else null,
                    accent = colors.accentDanger,
                    circular = !showLabels,
                    size = 28.dp,
                    enabled = noteText.isNotEmpty(),
                    contentDescription = if (isZh) "清空" else "Clear",
                    icon = { tint -> WidgetGlyphIcon(WidgetGlyph.TRASH, tint, 12.5.dp) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// App Group Launcher & One-Tap Split-Screen Widget
// ---------------------------------------------------------------------------

private val MONOGRAM_ACCENTS = listOf(
    Color(0xFF0A84FF),
    Color(0xFF06B6D4),
    Color(0xFF10B981),
    Color(0xFFF59E0B),
    Color(0xFFEC4899),
    Color(0xFF8B5CF6),
)

@Composable
fun AppGroupLauncherWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    apps: List<WidgetAppShortcut>,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val columns = when (sizeClass) {
        WidgetSizeClass.COMPACT -> 4
        WidgetSizeClass.MEDIUM -> 4
        WidgetSizeClass.EXPANDED -> 4
    }
    val maxApps = when (sizeClass) {
        WidgetSizeClass.COMPACT -> 4
        WidgetSizeClass.MEDIUM -> 8
        WidgetSizeClass.EXPANDED -> 12
    }
    val visibleApps = remember(apps, maxApps) { apps.take(maxApps) }
    val rows = remember(visibleApps, columns) { visibleApps.chunked(columns) }
    val canSplit = sizeClass != WidgetSizeClass.COMPACT && apps.size >= 2

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = colors.accentBlue,
            centerFractionX = 0.16f,
            centerFractionY = 0.16f,
            radiusFraction = 0.74f,
        )
        if (visibleApps.isEmpty()) {
            WidgetEmptyState(
                title = if (isZh) "暂无常用应用" else "No Pinned Apps",
                subtitle = if (isZh) "添加常用应用即可一键启动或分屏" else "Pin favorite apps for quick launch",
                accent = colors.accentBlue,
                icon = { tint -> OpalGlyphIcon(ChromeGlyph.APPS_GRID, tint = tint, size = 16.dp) },
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sizeClass != WidgetSizeClass.COMPACT) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        WidgetGlassBadge(
                            text = if (isZh) "常用应用" else "Quick Launch",
                            accent = colors.accentBlue,
                            leadingIcon = { tint -> OpalGlyphIcon(ChromeGlyph.APPS_GRID, tint = tint, size = 11.dp) },
                        )
                        if (canSplit) {
                            WidgetGlassActionButton(
                                onClick = {
                                    callbacks.onSplitLaunchApps(apps[0].packageName, apps[1].packageName)
                                },
                                label = if (isZh) "一键左右分屏" else "Split Screen",
                                accent = colors.accentCyan,
                                active = true,
                                circular = false,
                                size = 26.dp,
                                icon = { tint -> WidgetGlyphIcon(WidgetGlyph.SPLIT_SCREEN, tint, 12.dp) },
                            )
                        }
                    }
                }

                val gridModifier = if (sizeClass == WidgetSizeClass.EXPANDED) {
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
                    modifier = gridModifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { rowApps ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowApps.forEach { app ->
                                AppLauncherTile(
                                    app = app,
                                    iconSize = if (sizeClass == WidgetSizeClass.COMPACT) 34.dp else 40.dp,
                                    onClick = { callbacks.onLaunchApp(app.packageName) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - rowApps.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLauncherTile(
    app: WidgetAppShortcut,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val tileShape = RoundedCornerShape(14.dp)
    val iconShape = RoundedCornerShape(12.dp)
    val imageBitmap = remember(app.iconBitmap) { app.iconBitmap?.asImageBitmap() }
    val monogramAccent = remember(app.packageName) {
        MONOGRAM_ACCENTS[abs(app.packageName.hashCode()) % MONOGRAM_ACCENTS.size]
    }
    val initial = remember(app.label, app.packageName) {
        val source = app.label.trim().ifEmpty { app.packageName.substringAfterLast('.') }
        source.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    }

    Column(
        modifier = modifier
            .clip(tileShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (colors.isDark) Color(0x1EFFFFFF) else Color(0x7AFFFFFF),
                        if (colors.isDark) Color(0x0EFFFFFF) else Color(0x40FFFFFF),
                    ),
                ),
            )
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        colors.glassRimTop.copy(alpha = if (colors.isDark) 0.28f else 0.85f),
                        colors.borderHairline,
                    ),
                ),
                shape = tileShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(iconShape)
                .background(
                    if (imageBitmap == null) {
                        Brush.linearGradient(
                            listOf(
                                monogramAccent.copy(alpha = 0.88f),
                                monogramAccent.copy(alpha = 0.56f),
                            ),
                        )
                    } else {
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                    },
                )
                .border(0.75.dp, Color.White.copy(alpha = 0.34f), iconShape),
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = app.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                OpalText(
                    text = initial,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        OpalText(
            text = app.label.ifBlank { app.packageName.substringAfterLast('.') },
            color = colors.textPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Todo Checklist Liquid-Glass Widget
// ---------------------------------------------------------------------------

@Composable
fun TodoChecklistWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    callbacks: DesktopWidgetCallbacks,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val hostView = LocalView.current
    val decoded = remember(instance.id, instance.configJson) {
        decodeTodoChecklistConfig(instance.configJson)
    }
    var items by remember(instance.id) { mutableStateOf(decoded.items) }
    var lastSavedJson by remember(instance.id) { mutableStateOf(encodeTodoChecklistConfig(decoded)) }
    var draftText by remember(instance.id) { mutableStateOf("") }
    var inputFocused by remember(instance.id) { mutableStateOf(false) }
    val curCallbacks by rememberUpdatedState(callbacks)

    LaunchedEffect(instance.configJson) {
        if (instance.configJson != lastSavedJson) {
            val next = decodeTodoChecklistConfig(instance.configJson)
            items = next.items
            lastSavedJson = encodeTodoChecklistConfig(next)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (inputFocused) {
                val dispId = hostView.display?.displayId ?: Display.DEFAULT_DISPLAY
                OpalImeFocusTracker.setLocalTextFieldFocused(false, dispId)
            }
        }
    }

    fun updateItems(nextItems: List<TodoItemConfig>) {
        items = nextItems
        val encoded = encodeTodoChecklistConfig(TodoChecklistConfig(items = nextItems))
        lastSavedJson = encoded
        curCallbacks.onUpdateWidgetConfig(instance.id, encoded)
    }

    fun addDraftItem() {
        val trimmed = draftText.trim()
        if (trimmed.isEmpty()) return
        val newItem = TodoItemConfig(
            id = "t_${System.currentTimeMillis()}_${items.size}",
            text = trimmed,
            done = false,
        )
        draftText = ""
        updateItems(items + newItem)
    }

    val doneCount = items.count { it.done }
    val totalCount = items.size
    val allDone = totalCount > 0 && doneCount == totalCount
    val progressAccent = if (allDone) colors.accentEmerald else colors.accentBlue

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = progressAccent,
            centerFractionX = 0.85f,
            centerFractionY = 0.14f,
            radiusFraction = 0.72f,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top progress capsule + Clear Completed button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                WidgetGlassBadge(
                    text = if (isZh) "待办 $doneCount/$totalCount" else "Tasks $doneCount/$totalCount",
                    accent = progressAccent,
                    dotColor = progressAccent,
                )
                if (doneCount > 0) {
                    WidgetGlassActionButton(
                        onClick = { updateItems(items.filterNot { it.done }) },
                        label = if (isZh) "清理已完成" else "Clear Done",
                        accent = colors.accentEmerald,
                        circular = false,
                        size = 26.dp,
                    )
                }
            }

            // Scrollable checklist body
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    WidgetEmptyState(
                        title = if (isZh) "暂无待办事项" else "No Tasks Yet",
                        subtitle = if (isZh) "在下方输入并点击 + 快速添加" else "Add a task below to get started",
                        accent = colors.accentBlue,
                        icon = { tint -> WidgetGlyphIcon(WidgetGlyph.CHECK, tint, 16.dp) },
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items.forEach { item ->
                        TodoRowItem(
                            item = item,
                            compact = sizeClass == WidgetSizeClass.COMPACT,
                            onToggle = { updateItems(items.map { if (it.id == item.id) it.copy(done = !it.done) else it }) },
                            onDelete = { updateItems(items.filterNot { it.id == item.id }) },
                        )
                    }
                }
            }

            // Bottom quick-add input bar
            val inputShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f).height(32.dp)
                        .clip(inputShape)
                        .background(colors.inputBg)
                        .border(0.85.dp, if (inputFocused) colors.accentBlue.copy(alpha = 0.65f) else colors.borderSubtle, inputShape)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draftText.isEmpty()) {
                        OpalText(
                            text = if (isZh) "添加待办事项…" else "Add a task…",
                            color = colors.textMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = draftText,
                        onValueChange = { next ->
                            if (next.contains('\n')) {
                                draftText = next.replace("\n", "")
                                addDraftItem()
                            } else {
                                draftText = next
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addDraftItem() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { st ->
                                inputFocused = st.isFocused
                                val dispId = hostView.display?.displayId ?: Display.DEFAULT_DISPLAY
                                OpalImeFocusTracker.setLocalTextFieldFocused(st.isFocused, dispId)
                            },
                        textStyle = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(colors.accentBlue),
                    )
                }
                WidgetGlassActionButton(
                    onClick = { addDraftItem() },
                    accent = colors.accentBlue,
                    filled = true,
                    circular = false,
                    size = 32.dp,
                    enabled = draftText.isNotBlank(),
                    contentDescription = if (isZh) "添加待办" else "Add Task",
                    modifier = Modifier.width(34.dp),
                    icon = { tint -> WidgetGlyphIcon(WidgetGlyph.PLUS, tint, 14.dp) },
                )
            }
        }
    }
}

@Composable
private fun TodoRowItem(
    item: TodoItemConfig,
    compact: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalOpalColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (item.done) colors.chipFill.copy(alpha = if (colors.isDark) 0.10f else 0.08f) else if (colors.isDark) Color(0x1CFFFFFF) else Color(0x74FFFFFF),
            )
            .border(0.75.dp, colors.borderHairline, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 9.dp, vertical = if (compact) 6.dp else 7.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp).clip(CircleShape)
                .background(if (item.done) colors.accentEmerald else Color.Transparent)
                .border(1.2.dp, if (item.done) colors.accentEmerald else colors.textMuted.copy(alpha = 0.65f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (item.done) WidgetGlyphIcon(WidgetGlyph.CHECK, tint = Color.White, size = 11.dp)
        }
        BasicText(
            text = item.text,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = if (item.done) colors.textMuted else colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = if (item.done) FontWeight.Normal else FontWeight.Medium,
                textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
            ),
        )
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            OpalGlyphIcon(glyph = ChromeGlyph.CLOSE, tint = colors.textMuted.copy(alpha = 0.78f), size = 10.dp)
        }
    }
}
