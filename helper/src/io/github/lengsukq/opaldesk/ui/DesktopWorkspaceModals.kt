package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.compositor.AppCatalogRepository
import io.github.lengsukq.opaldesk.compositor.InstalledAppEntry
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import kotlin.math.roundToInt

private val DPI_PRESET_OPTIONS = listOf(160, 200, 240, 320, 400)

/**
 * Unified Exit & Save-to-Slot confirmation modal used both when exiting Desktop ("退出 DeX")
 * and when completely exiting OpalDesk ("完全退出 OpalDesk").
 * Lets the user choose whether to save the current working config & layout and which of the 5 slots
 * (1..5) to save into, without forcing saves on every minor tweak during use.
 */
@Composable
fun ExitSaveSlotModal(
    title: String,
    subtitle: String,
    confirmLabel: String,
    openWindowCount: Int,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onExitWithoutSaving: () -> Unit,
    onSaveToSlotAndExit: (slotId: Int, saveLayout: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    var selectedSlotId by remember { mutableIntStateOf(storeState.activeSlotId) }
    var includeLayout by remember { mutableStateOf(storeState.workingConfig.keepLayoutOnExit) }
    var showSlotPicker by remember { mutableStateOf(false) }
    val selectedSlot = storeState.slots.firstOrNull { it.id == selectedSlotId } ?: storeState.activeSavedSlot
    val hasChangesToSave = storeState.hasUnsavedChanges || openWindowCount > 0

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(enabled = !busy, onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        val modalMaxH = (maxHeight - 16.dp).coerceAtLeast(240.dp)
        OpalGlassCard(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .heightIn(max = modalMaxH)
                .pointerInput(Unit) { detectTapGestures {} },
            cornerRadius = 24.dp,
            fillColor = OpalPalette.GlassPopover,
            contentPadding = PaddingValues(18.dp),
        ) {
            OpalText(text = title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = if (hasChangesToSave) subtitle else stringResource(R.string.slot_exit_no_changes),
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 17.sp,
            )

            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExitSlotSummaryBanner(
                    selectedSlot = selectedSlot,
                    showSlotPicker = showSlotPicker,
                    busy = busy,
                    onTogglePicker = { showSlotPicker = !showSlotPicker },
                )

                if (showSlotPicker) {
                    ExitSlotPickerList(
                        slots = storeState.slots,
                        selectedSlotId = selectedSlotId,
                        busy = busy,
                        onSelectSlot = { slotId ->
                            selectedSlotId = slotId
                            showSlotPicker = false
                        },
                    )
                }

                if (openWindowCount > 0) {
                    OpalSwitchRow(
                        title = stringResource(R.string.slot_exit_include_layout, openWindowCount),
                        subtitle = stringResource(R.string.slot_exit_include_layout_sub),
                        checked = includeLayout,
                        onCheckedChange = { includeLayout = it },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OpalButton(
                    label = stringResource(R.string.common_cancel),
                    compact = true,
                    filled = false,
                    enabled = !busy,
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.7f),
                )
                OpalButton(
                    label = stringResource(if (hasChangesToSave) R.string.slot_exit_discard else R.string.slot_exit_direct),
                    compact = true,
                    filled = !hasChangesToSave,
                    accent = if (hasChangesToSave) OpalPalette.AccentDanger else OpalPalette.AccentBlue,
                    enabled = !busy,
                    onClick = onExitWithoutSaving,
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = if (busy) stringResource(R.string.slot_exit_saving) else confirmLabel,
                    compact = true,
                    filled = hasChangesToSave,
                    enabled = !busy,
                    onClick = { onSaveToSlotAndExit(selectedSlotId, includeLayout) },
                    modifier = Modifier.weight(1.25f),
                )
            }
        }
    }
}

@Composable
private fun ExitSlotSummaryBanner(
    selectedSlot: WorkspaceProfileConfig,
    showSlotPicker: Boolean,
    busy: Boolean,
    onTogglePicker: () -> Unit,
) {
    val summaryShape = RoundedCornerShape(13.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(summaryShape)
            .background(OpalPalette.AccentBlue.copy(alpha = 0.14f))
            .border(1.dp, OpalPalette.AccentBlue.copy(alpha = 0.55f), summaryShape)
            .clickable(enabled = !busy, onClick = onTogglePicker)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            OpalText(
                text = stringResource(R.string.slot_exit_save_to, selectedSlot.id + 1, selectedSlot.name),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = OpalPalette.AccentBlue,
                maxLines = 1,
            )
            val dpiSummary = if (selectedSlot.globalCustomDpi > 0) "${selectedSlot.globalCustomDpi} DPI" else stringResource(R.string.slot_auto_dpi)
            OpalText(
                text = stringResource(R.string.slot_exit_saved_meta, dpiSummary, selectedSlot.appCustomDpis.size, selectedSlot.savedLayout.size),
                fontSize = 10.5.sp,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
        OpalText(
            text = stringResource(if (showSlotPicker) R.string.common_collapse else R.string.slot_exit_switch_slot),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.AccentBlue,
        )
    }
}

@Composable
private fun ExitSlotPickerList(
    slots: List<WorkspaceProfileConfig>,
    selectedSlotId: Int,
    busy: Boolean,
    onSelectSlot: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        slots.forEach { slot ->
            val selected = slot.id == selectedSlotId
            val rowShape = RoundedCornerShape(11.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(rowShape)
                    .background(if (selected) OpalPalette.AccentBlue.copy(alpha = 0.16f) else OpalPalette.ChipFill)
                    .border(
                        width = if (selected) 1.1.dp else 0.7.dp,
                        color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                        shape = rowShape,
                    )
                    .clickable(enabled = !busy) { onSelectSlot(slot.id) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(
                        text = stringResource(R.string.slot_exit_slot_label, slot.id + 1, slot.name),
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        maxLines = 1,
                    )
                    val dpiSummary = if (slot.globalCustomDpi > 0) "${slot.globalCustomDpi} DPI" else stringResource(R.string.slot_auto_dpi)
                    OpalText(
                        text = stringResource(R.string.slot_meta_line, dpiSummary, slot.appCustomDpis.size, slot.savedLayout.size),
                        fontSize = 10.sp,
                        color = OpalPalette.TextMuted,
                        maxLines = 1,
                    )
                }
                if (selected) {
                    OpalStatusBadge(label = stringResource(R.string.slot_exit_current_badge), color = OpalPalette.AccentBlue)
                }
            }
        }
    }
}

/**
 * Compact modal for adjusting a single app's custom DPI directly from the Desktop Workspace
 * (App Context Menu). Applies live to the running session; saved permanently when user saves to a slot.
 */
@Composable
fun PerAppDpiEditorModal(
    pkg: String,
    appLabel: String,
    currentCustomDpi: Int,
    globalCustomDpi: Int,
    onApplyDpi: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dpiVal by remember(pkg, currentCustomDpi) {
        mutableStateOf(
            when {
                currentCustomDpi in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI -> currentCustomDpi
                globalCustomDpi in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI -> globalCustomDpi
                else -> 260
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(onClick = onDismiss)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlassCard(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures {} },
            cornerRadius = 22.dp,
            fillColor = OpalPalette.GlassPopover,
            contentPadding = PaddingValues(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "自定义应用 DPI", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    OpalText(text = "$appLabel · $pkg", fontSize = 11.sp, color = OpalPalette.TextSecondary, maxLines = 1)
                }
                OpalText(
                    text = "$dpiVal DPI",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.AccentBlue,
                )
            }
            Spacer(Modifier.height(12.dp))
            OpalGlassSlider(
                value = dpiVal.toFloat(),
                valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                onValueChange = { v -> dpiVal = snapDpiStep(v) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DPI_PRESET_OPTIONS.forEach { preset ->
                    OpalButton(
                        label = "$preset",
                        compact = true,
                        filled = dpiVal == preset,
                        accent = if (dpiVal == preset) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                        onClick = { dpiVal = preset },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpalButton(
                    label = "跟随全局",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentAmber,
                    onClick = {
                        onApplyDpi(0)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = "取消",
                    compact = true,
                    filled = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.8f),
                )
                OpalButton(
                    label = "立即应用",
                    compact = true,
                    onClick = {
                        onApplyDpi(dpiVal)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
    }
}

/**
 * Compact in-DeX live-preview floating DPI panel (no opaque scrim so open windows remain visible
 * while adjusting Global DPI or currently open window DPI in real time). All edits mutate the
 * active working profile and are saved alongside the desktop layout into the selected slot on exit.
 */
@Composable
fun DexDpiSettingsPopover(
    openWindows: List<CompositorWindow>,
    allApps: List<InstalledAppEntry>,
    maxHeight: Dp = 560.dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    val working = storeState.workingConfig
    val activeSlot = storeState.activeSavedSlot

    val appLabelByPkg = remember(allApps) { allApps.associate { it.pkg to it.label } }
    val openByPkg = remember(openWindows) { openWindows.associateBy { it.pkg } }
    val distinctOpenWindows = remember(openWindows) { openWindows.distinctBy { it.pkg } }

    val shape = RoundedCornerShape(20.dp)
    val cardShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .widthIn(max = 344.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 24.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                OpalText(text = "实时 DPI", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                OpalStatusBadge(
                    label = if (storeState.hasUnsavedChanges) "槽位 ${activeSlot.id + 1} · 未保存" else "槽位 ${activeSlot.id + 1}",
                    color = if (storeState.hasUnsavedChanges) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
                )
            }
            OpalButton(label = "完成", compact = true, filled = false, onClick = onDismiss)
        }

        GlobalDpiControlCard(
            globalCustomDpi = working.globalCustomDpi,
            cardShape = cardShape,
            onCommitDpi = { dpi -> BridgeClient.setGlobalCustomDpiAsync(ctx, dpi) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "当前窗口 (${distinctOpenWindows.size})",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = "拖动松手实时生效",
                    fontSize = 10.sp,
                    color = OpalPalette.TextMuted,
                )
            }

            if (distinctOpenWindows.isEmpty()) {
                OpalText(
                    text = "打开任意应用后，可在此实时拖动调节它的独立 DPI",
                    fontSize = 11.sp,
                    color = OpalPalette.TextMuted,
                )
            } else {
                distinctOpenWindows.forEach { win ->
                    OpenWindowDpiControlCard(
                        win = win,
                        customDpi = working.appCustomDpis[win.pkg] ?: 0,
                        globalCustomDpi = working.globalCustomDpi,
                        cropDp = working.appTopCropDps[win.pkg] ?: 0,
                        cardShape = cardShape,
                        onCommitDpi = { dpi -> BridgeClient.setAppCustomDpiAsync(ctx, win.pkg, dpi) },
                        onToggleTopCrop = { ProfileConfigStore.toggleWorkingAppTopCrop(win.pkg) },
                        onUpdateTopCropDp = { dp -> ProfileConfigStore.setWorkingAppTopCropDp(win.pkg, dp) },
                    )
                }
            }
        }

        val closedOverrides = working.appCustomDpis.entries.filter { !openByPkg.containsKey(it.key) }
        if (closedOverrides.isNotEmpty()) {
            ClosedAppDpiOverridesSection(
                closedOverrides = closedOverrides,
                appLabelByPkg = appLabelByPkg,
                onRemoveOverride = { pkg -> BridgeClient.setAppCustomDpiAsync(ctx, pkg, 0) },
            )
        }
    }
}

@Composable
private fun GlobalDpiControlCard(
    globalCustomDpi: Int,
    cardShape: RoundedCornerShape,
    onCommitDpi: (Int) -> Unit,
) {
    val isCustomGlobal = globalCustomDpi > 0
    var draftGlobalDpi by remember(globalCustomDpi) {
        mutableIntStateOf(globalCustomDpi.coerceAtLeast(ProfileConfigStore.MIN_DPI))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(OpalPalette.ChipFill)
            .border(0.7.dp, OpalPalette.BorderHairline, cardShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = "全局 DPI", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            OpalText(
                text = if (isCustomGlobal) "$draftGlobalDpi DPI" else "自动适配",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = OpalPalette.AccentBlue,
            )
        }
        OpalSegmentedRow(
            options = listOf("自动适配", "自定义"),
            selectedIndex = if (isCustomGlobal) 1 else 0,
            onSelect = { idx ->
                if (idx == 0) {
                    onCommitDpi(0)
                } else {
                    val initDpi = if (globalCustomDpi > 0) globalCustomDpi else 240
                    draftGlobalDpi = initDpi
                    onCommitDpi(initDpi)
                }
            },
        )
        if (isCustomGlobal) {
            OpalGlassSlider(
                value = draftGlobalDpi.toFloat(),
                valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                onValueChange = { v -> draftGlobalDpi = snapDpiStep(v) },
                onValueChangeFinished = {
                    if (draftGlobalDpi != globalCustomDpi) {
                        onCommitDpi(draftGlobalDpi)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DPI_PRESET_OPTIONS.forEach { preset ->
                    OpalButton(
                        label = "$preset",
                        compact = true,
                        filled = draftGlobalDpi == preset,
                        accent = if (draftGlobalDpi == preset) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                        onClick = {
                            draftGlobalDpi = preset
                            onCommitDpi(preset)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenWindowDpiControlCard(
    win: CompositorWindow,
    customDpi: Int,
    globalCustomDpi: Int,
    cropDp: Int,
    cardShape: RoundedCornerShape,
    onCommitDpi: (Int) -> Unit,
    onToggleTopCrop: () -> Unit,
    onUpdateTopCropDp: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val effectiveDpi = when {
        customDpi > 0 -> customDpi
        win.contentDpi in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI -> win.contentDpi
        globalCustomDpi > 0 -> globalCustomDpi
        else -> 240
    }
    var draftWinDpi by remember(win.pkg, effectiveDpi) { mutableIntStateOf(effectiveDpi) }
    val icon = remember(win.pkg) { AppCatalogRepository.getAppIcon(ctx, win.pkg) }
    val isCropTop = cropDp > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(OpalPalette.ChipFill)
            .border(
                width = if (customDpi > 0) 0.9.dp else 0.65.dp,
                color = if (customDpi > 0) OpalPalette.AccentBlue.copy(alpha = 0.55f) else OpalPalette.BorderHairline,
                shape = cardShape,
            )
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = win.title,
                        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
                OpalText(text = win.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(
                    text = if (customDpi > 0 || draftWinDpi != effectiveDpi) "$draftWinDpi" else "自动 ${win.contentDpi}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (customDpi > 0 || draftWinDpi != effectiveDpi) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                )
                if (customDpi > 0) {
                    OpalText(
                        text = "重置",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentAmber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onCommitDpi(0) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        OpalGlassSlider(
            value = draftWinDpi.toFloat(),
            valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
            onValueChange = { v -> draftWinDpi = snapDpiStep(v) },
            onValueChangeFinished = {
                if (draftWinDpi != customDpi) {
                    onCommitDpi(draftWinDpi)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCropTop) OpalPalette.AccentBlue.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(onClick = onToggleTopCrop)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "裁切顶部状态栏留白",
                    fontSize = 10.5.sp,
                    color = if (isCropTop) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                    fontWeight = if (isCropTop) FontWeight.SemiBold else FontWeight.Normal,
                )
                OpalText(
                    text = if (isCropTop) "${cropDp}dp · 点击关闭" else "关闭",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCropTop) OpalPalette.AccentBlue else OpalPalette.TextMuted,
                )
            }
            if (isCropTop) {
                OpalGlassSlider(
                    value = cropDp.toFloat(),
                    valueRange = 8f..ProfileConfigStore.MAX_TOP_CROP_DP.toFloat(),
                    onValueChange = { v ->
                        val snapped = ((v / 2f).roundToInt() * 2)
                            .coerceIn(8, ProfileConfigStore.MAX_TOP_CROP_DP)
                        if (snapped != cropDp) {
                            onUpdateTopCropDp(snapped)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ClosedAppDpiOverridesSection(
    closedOverrides: List<Map.Entry<String, Int>>,
    appLabelByPkg: Map<String, String>,
    onRemoveOverride: (String) -> Unit,
) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OpalText(
            text = "已存独立 DPI · 未打开 (${closedOverrides.size})",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
        )
        closedOverrides.forEach { (pkg, dpi) ->
            val label = appLabelByPkg[pkg] ?: pkg
            val icon = remember(pkg) { AppCatalogRepository.getAppIcon(ctx, pkg) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(OpalPalette.ChipFill)
                    .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = label,
                            modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
                        )
                    }
                    OpalText(text = label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpalText(
                        text = "$dpi DPI",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = OpalPalette.AccentBlue,
                    )
                    OpalText(
                        text = "移除",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentDanger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onRemoveOverride(pkg) }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

private fun snapDpiStep(value: Float): Int =
    ((value / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
