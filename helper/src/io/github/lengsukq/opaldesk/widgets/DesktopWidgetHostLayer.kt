package io.github.lengsukq.opaldesk.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.compositor.InstalledAppEntry
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.widgets.renderers.AppGroupLauncherWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.CalendarWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.ClockTimerWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.DesktopHubCarouselWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.MediaPlayerWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.NotificationOtpWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.QuickActionTilesWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.QuickNotesWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.SystemMonitorWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.TodoChecklistWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.WeatherWidgetContent
import io.github.lengsukq.opaldesk.widgets.renderers.WorkstationStatusWidgetContent
import java.util.Locale
import kotlin.math.roundToInt

private data class WidgetGhostPreview(
    val col: Int,
    val row: Int,
    val colSpan: Int,
    val rowSpan: Int,
    val accent: Color,
)

@Composable
fun DesktopWidgetHostLayer(
    instances: List<DesktopWidgetInstance>,
    isEditMode: Boolean,
    mediaSnapshot: WidgetMediaSnapshot,
    workstationSnapshot: WidgetWorkstationSnapshot,
    notifications: List<WidgetNotificationItem>,
    appShortcuts: List<WidgetAppShortcut>,
    callbacks: DesktopWidgetCallbacks,
    desktopShortcuts: List<DesktopGridShortcutItem> = emptyList(),
    onOpenGuide: () -> Unit = {},
    onContextGuide: (Offset) -> Unit = {},
    onLaunchDesktopApp: (InstalledAppEntry) -> Unit = {},
    onContextDesktopApp: (String, Offset) -> Unit = { _, _ -> },
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onOpenWidgetStore: () -> Unit,
    onMoveWidget: (id: String, col: Int, row: Int) -> Unit,
    onResizeWidget: (id: String, colSpan: Int, rowSpan: Int) -> Unit,
    onRemoveWidget: (id: String) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val isZh = remember(ctx) {
        val lang = runCatching {
            ctx.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language
        }.getOrNull() ?: Locale.getDefault().language
        lang.equals("zh", ignoreCase = true)
    }
    var activeGhost by remember(isEditMode) { mutableStateOf<WidgetGhostPreview?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isPortraitCompact = maxWidth < maxHeight && maxWidth < 600.dp
        val gridCols = if (isPortraitCompact) {
            DesktopWidgetGridMath.PORTRAIT_GRID_COLS
        } else {
            DesktopWidgetGridMath.DEFAULT_GRID_COLS
        }
        val displayInstances = remember(instances, isPortraitCompact, gridCols) {
            if (isPortraitCompact) {
                DesktopWidgetGridMath.computePortraitLayout(
                    instances = instances,
                    portraitCols = gridCols,
                    portraitRows = DesktopWidgetGridMath.PORTRAIT_DEFAULT_GRID_ROWS,
                )
            } else {
                instances
            }
        }
        val baseGridRows = if (isPortraitCompact) {
            DesktopWidgetGridMath.PORTRAIT_DEFAULT_GRID_ROWS
        } else {
            DesktopWidgetGridMath.DEFAULT_GRID_ROWS
        }
        val shortcutSlots = remember(desktopShortcuts.size, displayInstances, gridCols, baseGridRows, isPortraitCompact) {
            DesktopWidgetGridMath.allocateShortcutSlots(
                iconCount = desktopShortcuts.size,
                occupiedWidgets = displayInstances,
                gridCols = gridCols,
                gridRows = baseGridRows,
                isPortrait = isPortraitCompact,
            )
        }
        val contentRows = remember(displayInstances, shortcutSlots, baseGridRows, isPortraitCompact) {
            val maxWidgetRow = displayInstances.maxOfOrNull { it.row + it.rowSpan } ?: 0
            val maxShortcutRow = shortcutSlots.maxOfOrNull { it.second + 1 } ?: 0
            if (isPortraitCompact) {
                maxOf(DesktopWidgetGridMath.PORTRAIT_MIN_GRID_ROWS, maxWidgetRow, maxShortcutRow)
            } else {
                maxOf(baseGridRows, maxWidgetRow, maxShortcutRow)
            }
        }

        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val topClearanceDp = if (isEditMode) 56.dp else if (isPortraitCompact) 6.dp else 0.dp
        val topClearancePx = with(density) { topClearanceDp.toPx() }
        val availableGridHeightPx = (heightPx - topClearancePx).coerceAtLeast(1f)
        val cellW = widthPx / gridCols.toFloat()
        val cellH = if (isPortraitCompact) {
            val minCellH = with(density) { 84.dp.toPx() }
            val maxCellH = with(density) { 104.dp.toPx() }
            (cellW * 1.04f).coerceIn(minCellH, maxCellH)
        } else {
            availableGridHeightPx / DesktopWidgetGridMath.DEFAULT_GRID_ROWS.toFloat()
        }
        val cardGapPx = with(density) { (if (isPortraitCompact) 10.dp else 10.dp).toPx() }
        val totalGridHeightDp = with(density) { (contentRows * cellH + if (isPortraitCompact) 16.dp.toPx() else 0f).toDp() }
        val scrollState = rememberScrollState()

        val gridContainerModifier = if (isPortraitCompact) {
            Modifier
                .fillMaxSize()
                .padding(top = topClearanceDp)
                .verticalScroll(scrollState)
        } else {
            Modifier
                .fillMaxSize()
                .padding(top = topClearanceDp)
        }

        Box(modifier = gridContainerModifier) {
            Box(
                modifier = if (isPortraitCompact) {
                    Modifier
                        .fillMaxWidth()
                        .height(totalGridHeightDp)
                } else {
                    Modifier.fillMaxSize()
                },
            ) {
                if (isEditMode) {
                    WidgetGridAlignmentDots(
                        gridCols = gridCols,
                        gridRows = contentRows,
                        cellW = cellW,
                        cellH = cellH,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (!isPortraitCompact) {
                    activeGhost?.let { ghost ->
                        val gx = (ghost.col * cellW + cardGapPx * 0.5f).roundToInt()
                        val gy = (ghost.row * cellH + cardGapPx * 0.5f).roundToInt()
                        val gw = (ghost.colSpan * cellW - cardGapPx).coerceAtLeast(48f)
                        val gh = (ghost.rowSpan * cellH - cardGapPx).coerceAtLeast(48f)
                        val ghostShape = RoundedCornerShape(24.dp)
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(gx, gy) }
                                .size(
                                    width = with(density) { gw.toDp() },
                                    height = with(density) { gh.toDp() },
                                )
                                .zIndex(1f)
                                .clip(ghostShape)
                                .background(ghost.accent.copy(alpha = 0.16f))
                                .border(2.dp, ghost.accent.copy(alpha = 0.78f), ghostShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(ghost.accent.copy(alpha = 0.24f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                OpalText(
                                    text = "${ghost.colSpan}×${ghost.rowSpan}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                // Unified Desktop App Shortcuts (1x1 grid cells with zero widget overlap)
                desktopShortcuts.forEachIndexed { idx, shortcutItem ->
                    val slot = shortcutSlots.getOrNull(idx) ?: return@forEachIndexed
                    key(shortcutItem.stableKey) {
                        val sx = (slot.first * cellW).roundToInt()
                        val sy = (slot.second * cellH).roundToInt()
                        DesktopGridShortcutTile(
                            item = shortcutItem,
                            isEditMode = isEditMode,
                            onOpenGuide = onOpenGuide,
                            onContextGuide = onContextGuide,
                            onLaunchApp = onLaunchDesktopApp,
                            onContextApp = onContextDesktopApp,
                            modifier = Modifier
                                .offset { IntOffset(sx, sy) }
                                .size(
                                    width = with(density) { cellW.toDp() },
                                    height = with(density) { cellH.toDp() },
                                )
                                .zIndex(6f),
                        )
                    }
                }

                for (instance in displayInstances) {
                    key(instance.id) {
                        DesktopWidgetCardSlot(
                            instance = instance,
                            isEditMode = isEditMode,
                            allowDragResize = !isPortraitCompact,
                            isZh = isZh,
                            cellW = cellW,
                            cellH = cellH,
                            cardGapPx = cardGapPx,
                            gridCols = gridCols,
                            gridRows = contentRows,
                            mediaSnapshot = mediaSnapshot,
                            workstationSnapshot = workstationSnapshot,
                            notifications = notifications,
                            appShortcuts = appShortcuts,
                            callbacks = callbacks,
                            onEnterEditMode = onEnterEditMode,
                            onGhostChanged = { activeGhost = it },
                            onMoveCommitted = { col, row ->
                                activeGhost = null
                                onMoveWidget(instance.id, col, row)
                            },
                            onResizeCommitted = { cSpan, rSpan ->
                                activeGhost = null
                                onResizeWidget(instance.id, cSpan, rSpan)
                            },
                            onRemove = { onRemoveWidget(instance.id) },
                        )
                    }
                }
            }
        }

        if (isEditMode) {
            WidgetEditActionPill(
                isZh = isZh,
                onOpenWidgetStore = onOpenWidgetStore,
                onResetDefaults = onResetDefaults,
                onDone = onExitEditMode,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .zIndex(50f),
            )
        }
    }
}

@Composable
private fun DesktopWidgetCardSlot(
    instance: DesktopWidgetInstance,
    isEditMode: Boolean,
    allowDragResize: Boolean,
    isZh: Boolean,
    cellW: Float,
    cellH: Float,
    cardGapPx: Float,
    gridCols: Int,
    gridRows: Int,
    mediaSnapshot: WidgetMediaSnapshot,
    workstationSnapshot: WidgetWorkstationSnapshot,
    notifications: List<WidgetNotificationItem>,
    appShortcuts: List<WidgetAppShortcut>,
    callbacks: DesktopWidgetCallbacks,
    onEnterEditMode: () -> Unit,
    onGhostChanged: (WidgetGhostPreview?) -> Unit,
    onMoveCommitted: (Int, Int) -> Unit,
    onResizeCommitted: (Int, Int) -> Unit,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    val colors = LocalOpalColors.current
    val spec = DesktopWidgetCatalog.specOf(instance.type)
    val accent = Color(spec.accentColorHex)

    var dragDx by remember(instance.id, instance.col, instance.row, isEditMode) { mutableFloatStateOf(0f) }
    var dragDy by remember(instance.id, instance.col, instance.row, isEditMode) { mutableFloatStateOf(0f) }
    var resizeDw by remember(instance.id, instance.colSpan, instance.rowSpan, isEditMode) { mutableFloatStateOf(0f) }
    var resizeDh by remember(instance.id, instance.colSpan, instance.rowSpan, isEditMode) { mutableFloatStateOf(0f) }
    var isDragging by remember(instance.id, isEditMode) { mutableStateOf(false) }

    val baseX = instance.col * cellW + cardGapPx * 0.5f
    val baseY = instance.row * cellH + cardGapPx * 0.5f
    val baseW = (instance.colSpan * cellW - cardGapPx).coerceAtLeast(96f)
    val baseH = (instance.rowSpan * cellH - cardGapPx).coerceAtLeast(80f)

    val renderX = (baseX + dragDx).roundToInt()
    val renderY = (baseY + dragDy).roundToInt()
    val renderW = (baseW + resizeDw).coerceAtLeast(96f)
    val renderH = (baseH + resizeDh).coerceAtLeast(80f)

    val sizeClass = remember(instance.colSpan, instance.rowSpan) {
        DesktopWidgetGridMath.computeSizeClass(instance.colSpan, instance.rowSpan)
    }
    val cardShape = RoundedCornerShape(24.dp)
    val glassFill = Brush.verticalGradient(
        listOf(
            if (colors.isDark) Color(0x7A242D42) else Color(0xA4FFFFFF),
            if (colors.isDark) Color(0x6C131826) else Color(0x7AEAF1FC),
        ),
    )
    val editRim = Brush.linearGradient(
        listOf(
            accent.copy(alpha = 0.92f),
            OpalPalette.AccentCyan.copy(alpha = 0.85f),
            accent.copy(alpha = 0.72f),
        ),
    )

    Box(
        modifier = Modifier
            .offset { IntOffset(renderX, renderY) }
            .size(
                width = with(density) { renderW.toDp() },
                height = with(density) { renderH.toDp() },
            )
            .zIndex(if (isDragging) 30f else 10f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassSurface(
                    shape = cardShape,
                    fill = glassFill,
                    rim = if (isEditMode) editRim else OpalPalette.LiquidGlassRim,
                    elevation = if (isDragging) 26.dp else 18.dp,
                    borderWidth = if (isEditMode) 1.6.dp else 1.dp,
                )
                .then(
                    if (isEditMode && allowDragResize) {
                        Modifier.pointerInput(instance.id, instance.col, instance.row, instance.colSpan, instance.rowSpan, cellW, cellH) {
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    onGhostChanged(
                                        WidgetGhostPreview(instance.col, instance.row, instance.colSpan, instance.rowSpan, accent),
                                    )
                                },
                                onDragCancel = {
                                    isDragging = false
                                    dragDx = 0f
                                    dragDy = 0f
                                    onGhostChanged(null)
                                },
                                onDragEnd = {
                                    val (targetCol, targetRow) = DesktopWidgetGridMath.snapToGrid(
                                        pixelX = instance.col * cellW + dragDx,
                                        pixelY = instance.row * cellH + dragDy,
                                        cellWidthPx = cellW,
                                        cellHeightPx = cellH,
                                        colSpan = instance.colSpan,
                                        rowSpan = instance.rowSpan,
                                        gridCols = gridCols,
                                        gridRows = gridRows,
                                    )
                                    isDragging = false
                                    dragDx = 0f
                                    dragDy = 0f
                                    onMoveCommitted(targetCol, targetRow)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragDx += dragAmount.x
                                    dragDy += dragAmount.y
                                    val (tc, tr) = DesktopWidgetGridMath.snapToGrid(
                                        pixelX = instance.col * cellW + dragDx,
                                        pixelY = instance.row * cellH + dragDy,
                                        cellWidthPx = cellW,
                                        cellHeightPx = cellH,
                                        colSpan = instance.colSpan,
                                        rowSpan = instance.rowSpan,
                                        gridCols = gridCols,
                                        gridRows = gridRows,
                                    )
                                    onGhostChanged(WidgetGhostPreview(tc, tr, instance.colSpan, instance.rowSpan, accent))
                                },
                            )
                        }
                    } else if (!isEditMode) {
                        Modifier.pointerInput(instance.id) {
                            detectTapGestures(onLongPress = { onEnterEditMode() })
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(12.dp),
        ) {
            when (instance.type) {
                DesktopWidgetType.DESKTOP_HUB -> DesktopHubCarouselWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    media = mediaSnapshot,
                    status = workstationSnapshot,
                    notifications = notifications,
                    callbacks = callbacks,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.MEDIA_PLAYER -> MediaPlayerWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    media = mediaSnapshot,
                    callbacks = callbacks,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.CLOCK_TIMER -> ClockTimerWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.WORKSTATION_STATUS -> WorkstationStatusWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    status = workstationSnapshot,
                    callbacks = callbacks,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.NOTIFICATION_OTP -> NotificationOtpWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    notifications = notifications,
                    callbacks = callbacks,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.QUICK_NOTES -> QuickNotesWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    callbacks = callbacks,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.APP_GROUP_LAUNCHER -> AppGroupLauncherWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    apps = appShortcuts,
                    callbacks = callbacks,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.TODO_CHECKLIST -> TodoChecklistWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    callbacks = callbacks,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.WEATHER_FORECAST -> WeatherWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.SYSTEM_MONITOR -> SystemMonitorWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.CALENDAR_MONTH -> CalendarWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopWidgetType.QUICK_ACTION_TILES -> QuickActionTilesWidgetContent(
                    instance = instance,
                    sizeClass = sizeClass,
                    workstation = workstationSnapshot,
                    apps = appShortcuts,
                    callbacks = callbacks,
                    isZh = isZh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (isEditMode) {
            // Top-right frosted delete button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (colors.isDark) Color(0xE02A181C) else Color(0xF0FFF1F2))
                    .border(1.dp, OpalPalette.AccentDanger.copy(alpha = 0.65f), CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                OpalGlyphIcon(glyph = ChromeGlyph.CLOSE, tint = OpalPalette.AccentDanger, size = 11.dp)
            }

            // Bottom-right resize grip handle (landscape 12x7 grid only; portrait uses adaptive 4-col spans)
            if (allowDragResize) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 5.dp, y = 5.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (colors.isDark) Color(0xE0232A3C) else Color(0xF2FFFFFF))
                        .border(1.2.dp, accent.copy(alpha = 0.78f), CircleShape)
                        .pointerInput(instance.id, instance.col, instance.row, instance.colSpan, instance.rowSpan, cellW, cellH) {
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    onGhostChanged(
                                        WidgetGhostPreview(instance.col, instance.row, instance.colSpan, instance.rowSpan, accent),
                                    )
                                },
                                onDragCancel = {
                                    isDragging = false
                                    resizeDw = 0f
                                    resizeDh = 0f
                                    onGhostChanged(null)
                                },
                                onDragEnd = {
                                    val rawColSpan = ((instance.colSpan * cellW + resizeDw) / cellW).roundToInt()
                                    val rawRowSpan = ((instance.rowSpan * cellH + resizeDh) / cellH).roundToInt()
                                    val clamped = DesktopWidgetGridMath.clampInstance(
                                        instance.copy(colSpan = rawColSpan, rowSpan = rawRowSpan),
                                        gridCols = gridCols,
                                        gridRows = gridRows,
                                    )
                                    isDragging = false
                                    resizeDw = 0f
                                    resizeDh = 0f
                                    onResizeCommitted(clamped.colSpan, clamped.rowSpan)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    resizeDw += dragAmount.x
                                    resizeDh += dragAmount.y
                                    val rawColSpan = ((instance.colSpan * cellW + resizeDw) / cellW).roundToInt()
                                    val rawRowSpan = ((instance.rowSpan * cellH + resizeDh) / cellH).roundToInt()
                                    val clamped = DesktopWidgetGridMath.clampInstance(
                                        instance.copy(colSpan = rawColSpan, rowSpan = rawRowSpan),
                                        gridCols = gridCols,
                                        gridRows = gridRows,
                                    )
                                    onGhostChanged(
                                        WidgetGhostPreview(clamped.col, clamped.row, clamped.colSpan, clamped.rowSpan, accent),
                                    )
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        val sw = 1.6.dp.toPx()
                        drawLine(accent, Offset(size.width * 0.25f, size.height * 0.85f), Offset(size.width * 0.85f, size.height * 0.25f), sw, StrokeCap.Round)
                        drawLine(accent, Offset(size.width * 0.58f, size.height * 0.85f), Offset(size.width * 0.85f, size.height * 0.58f), sw, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetGridAlignmentDots(
    gridCols: Int,
    gridRows: Int,
    cellW: Float,
    cellH: Float,
    modifier: Modifier = Modifier,
) {
    val dotColor = if (OpalPalette.isDark) Color.White.copy(alpha = 0.22f) else Color(0xFF0F172A).copy(alpha = 0.16f)
    Canvas(modifier = modifier) {
        val r = 2.dp.toPx()
        for (row in 0..gridRows) {
            val y = row * cellH
            for (col in 0..gridCols) {
                val x = col * cellW
                drawCircle(color = dotColor, radius = r, center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun WidgetEditActionPill(
    isZh: Boolean,
    onOpenWidgetStore: () -> Unit,
    onResetDefaults: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .liquidGlassSurface(
                shape = CircleShape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 22.dp,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpalButton(
            label = if (isZh) "+ 组件商店" else "+ Widget Store",
            onClick = onOpenWidgetStore,
            accent = OpalPalette.AccentCyan,
            filled = false,
            compact = true,
        )
        OpalButton(
            label = if (isZh) "清空组件" else "Clear All",
            onClick = onResetDefaults,
            accent = OpalPalette.AccentAmber,
            filled = false,
            compact = true,
        )
        OpalButton(
            label = if (isZh) "完成编辑" else "Done",
            onClick = onDone,
            accent = OpalPalette.AccentBlue,
            filled = true,
            compact = true,
        )
    }
}
