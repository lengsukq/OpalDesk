package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.compositor.AppCatalogRepository
import io.github.lengsukq.opaldesk.compositor.InstalledAppEntry
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import kotlin.math.roundToInt

/**
 * Flat inner content for [OpalCollapsibleSectionCard] in Tab 2 ("系统设置") combining 5-slot
 * selection, auto-match, slot renaming, and layout restore / window memory policies cleanly
 * without double-nested glass cards.
 */
@Composable
fun ConsoleSlotsAndLayoutContent(
    status: DaemonStatus,
    scene: CompositorScene,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        ProfileConfigStore.hydrate(ctx)
        BridgeClient.bindAppContext(ctx)
    }

    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    val working = storeState.workingConfig
    val activeSlot = storeState.activeSavedSlot

    var editingProfileName by remember(activeSlot.id, activeSlot.name) { mutableStateOf(activeSlot.name) }
    var showRenameRow by remember(activeSlot.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            storeState.slots.forEach { slot ->
                val selected = slot.id == storeState.activeSlotId
                val shape = RoundedCornerShape(13.dp)
                Column(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (selected) OpalPalette.AccentBlue.copy(alpha = 0.18f) else OpalPalette.ChipFill)
                        .border(
                            width = if (selected) 1.2.dp else 0.75.dp,
                            color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            shape = shape,
                        )
                        .clickable {
                            val loaded = ProfileConfigStore.loadSlot(ctx, slot.id)
                            BridgeClient.syncProfileToDaemonAsync(loaded) {
                                Toast.makeText(ctx, "已载入槽位 ${slot.id + 1}「${loaded.name}」", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    OpalText(
                        text = "${slot.id + 1}. ${slot.name}",
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        maxLines = 1,
                    )
                    val dpiBadge = if (slot.globalCustomDpi > 0) "${slot.globalCustomDpi} DPI" else "自动 DPI"
                    OpalText(
                        text = "$dpiBadge · ${slot.appCustomDpis.size} 应用 · ${slot.savedLayout.size} 窗",
                        fontSize = 10.sp,
                        color = OpalPalette.TextMuted,
                        maxLines = 1,
                    )
                }
            }
        }

        OpalSwitchRow(
            title = "按显示设备自动匹配槽位",
            subtitle = "切换本机、外接屏或投屏端时自动载入对应配置",
            checked = storeState.autoMatchTargetSlot,
            onCheckedChange = { ProfileConfigStore.setAutoMatchTargetSlot(ctx, it) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = "当前：${activeSlot.name}（已绑 ${activeSlot.boundTargets.size} 台 · 记住 ${working.appWindowBounds.size} 窗位置）",
                fontSize = 11.5.sp,
                color = OpalPalette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OpalButton(
                label = if (showRenameRow) "收起" else "重命名",
                compact = true,
                filled = false,
                onClick = { showRenameRow = !showRenameRow },
            )
        }

        if (showRenameRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpalTextInput(
                    value = editingProfileName,
                    onValueChange = { editingProfileName = it },
                    placeholder = "输入槽位名称（如：客厅 4K 屏 / Mac 投屏）",
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = "保存名称",
                    compact = true,
                    onClick = {
                        ProfileConfigStore.renameSlot(ctx, activeSlot.id, editingProfileName)
                        showRenameRow = false
                        Toast.makeText(ctx, "已更新槽位名称", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.75.dp)
                .background(OpalPalette.DividerColor),
        )

        OpalSwitchRow(
            title = "退出弹窗默认勾选「保留当前窗口布局」",
            subtitle = "退出时可自由切换是否保存及选择目标槽位",
            checked = working.keepLayoutOnExit,
            onCheckedChange = { ProfileConfigStore.setWorkingKeepLayoutOnExit(it) },
        )
        OpalSwitchRow(
            title = "进入桌面时自动恢复当前槽位已存布局",
            subtitle = "当前无打开窗口时，进入桌面自动还原已保存的窗口排布",
            checked = working.autoRestoreLayout,
            onCheckedChange = { ProfileConfigStore.setWorkingAutoRestoreLayout(it) },
        )

        if (activeSlot.savedLayout.isNotEmpty()) {
            OpalButton(
                label = "立即恢复槽位 ${activeSlot.id + 1} 已存布局 (${activeSlot.savedLayout.size}窗)",
                compact = true,
                filled = false,
                accent = OpalPalette.AccentEmerald,
                enabled = status.connected,
                onClick = {
                    BridgeClient.restoreSavedLayoutAsync(activeSlot, status.canvasWidth, status.canvasHeight) { count ->
                        Toast.makeText(ctx, "已恢复 $count 个窗口", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Advanced Settings section in Console "设置" tab:
 * - 5 persistent Android-side workspace configuration slots (load any slot for local / external
 *   monitor / casting / receiving device setups; runtime tweaks apply live without auto-overwriting
 *   saved slots until the user chooses a target slot on exit)
 * - Layout restore & exit save defaults (visual DPI tuning lives exclusively inside DeX)
 */
@Composable
fun ConsoleAdvancedSettingsSection(
    status: DaemonStatus,
    scene: CompositorScene,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        ProfileConfigStore.hydrate(ctx)
        BridgeClient.bindAppContext(ctx)
    }

    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    val working = storeState.workingConfig
    val activeSlot = storeState.activeSavedSlot

    var editingProfileName by remember(activeSlot.id, activeSlot.name) { mutableStateOf(activeSlot.name) }
    var showRenameRow by remember(activeSlot.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Card A: 5 Persistent Configuration Slots
        MacGlassSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "场景配置槽位", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    OpalText(
                        text = "为本机、外接屏和投屏端独立保存 DPI 与窗口布局",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OpalStatusBadge(
                    label = if (storeState.hasUnsavedChanges) "槽位 ${storeState.activeSlotId + 1} · 未保存" else "槽位 ${storeState.activeSlotId + 1}/5",
                    color = if (storeState.hasUnsavedChanges) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                storeState.slots.forEach { slot ->
                    val selected = slot.id == storeState.activeSlotId
                    val shape = RoundedCornerShape(13.dp)
                    Column(
                        modifier = Modifier
                            .clip(shape)
                            .background(if (selected) OpalPalette.AccentBlue.copy(alpha = 0.18f) else OpalPalette.ChipFill)
                            .border(
                                width = if (selected) 1.2.dp else 0.75.dp,
                                color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                shape = shape,
                            )
                            .clickable {
                                val loaded = ProfileConfigStore.loadSlot(ctx, slot.id)
                                BridgeClient.syncProfileToDaemonAsync(loaded) {
                                    Toast.makeText(ctx, "已载入槽位 ${slot.id + 1}「${loaded.name}」", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        OpalText(
                            text = "${slot.id + 1}. ${slot.name}",
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                            maxLines = 1,
                        )
                        val dpiBadge = if (slot.globalCustomDpi > 0) "${slot.globalCustomDpi} DPI" else "自动 DPI"
                        OpalText(
                            text = "$dpiBadge · ${slot.appCustomDpis.size} 应用 · ${slot.savedLayout.size} 窗",
                            fontSize = 10.sp,
                            color = OpalPalette.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OpalSwitchRow(
                title = "按显示设备自动匹配槽位",
                subtitle = "切换本机、外接屏或投屏端时自动载入对应配置",
                checked = storeState.autoMatchTargetSlot,
                onCheckedChange = { ProfileConfigStore.setAutoMatchTargetSlot(ctx, it) },
            )

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "当前载入：${activeSlot.name}（已绑 ${activeSlot.boundTargets.size} 台设备 · 记住 ${working.appWindowBounds.size} 窗位置）",
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = if (showRenameRow) "收起" else "重命名",
                    compact = true,
                    filled = false,
                    onClick = { showRenameRow = !showRenameRow },
                )
            }

            if (showRenameRow) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpalTextInput(
                        value = editingProfileName,
                        onValueChange = { editingProfileName = it },
                        placeholder = "输入槽位名称（如：客厅 4K 屏 / Mac 投屏）",
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "保存名称",
                        compact = true,
                        onClick = {
                            ProfileConfigStore.renameSlot(ctx, activeSlot.id, editingProfileName)
                            showRenameRow = false
                            Toast.makeText(ctx, "已更新槽位名称", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }


        // Card C: Layout Restore & Window Memory Policy
        MacGlassSectionCard {
            OpalText(text = "布局恢复与窗口记忆", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = "每个应用关闭时自动记住窗口大小与位置；主动退出 DeX 或应用时可选择将当前配置与布局保存到 1–5 号任意槽位",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))

            OpalSwitchRow(
                title = "退出弹窗默认勾选「保留当前窗口布局」",
                subtitle = "退出时可自由切换是否保存及选择目标槽位",
                checked = working.keepLayoutOnExit,
                onCheckedChange = { ProfileConfigStore.setWorkingKeepLayoutOnExit(it) },
            )
            OpalSwitchRow(
                title = "进入桌面时自动恢复当前槽位已存布局",
                subtitle = "当前无打开窗口时，进入桌面自动还原已保存的窗口排布",
                checked = working.autoRestoreLayout,
                onCheckedChange = { ProfileConfigStore.setWorkingAutoRestoreLayout(it) },
            )

            if (activeSlot.savedLayout.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                OpalButton(
                    label = "立即恢复槽位 ${activeSlot.id + 1} 已存布局 (${activeSlot.savedLayout.size}窗)",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentEmerald,
                    enabled = status.connected,
                    onClick = {
                        BridgeClient.restoreSavedLayoutAsync(activeSlot, status.canvasWidth, status.canvasHeight) { count ->
                            Toast.makeText(ctx, "已恢复 $count 个窗口", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Compact horizontal 1–5 configuration slot quick-switcher strip shared by the Home Dashboard
 * and Desktop Control Center.
 */
@Composable
fun QuickSlotSwitcherStrip(
    compact: Boolean = false,
    showRestoreButton: Boolean = false,
    canvasW: Int = 1920,
    canvasH: Int = 1200,
    onSlotSelected: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    val activeSlot = storeState.activeSavedSlot

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(
                    text = "配置槽位",
                    fontSize = if (compact) 11.5.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = if (storeState.autoMatchTargetSlot) "自动匹配已开" else "手动模式",
                    fontSize = 10.sp,
                    color = if (storeState.autoMatchTargetSlot) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
                )
            }
            if (showRestoreButton && activeSlot.savedLayout.isNotEmpty()) {
                OpalText(
                    text = "恢复布局 (${activeSlot.savedLayout.size}窗)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            BridgeClient.restoreSavedLayoutAsync(activeSlot, canvasW, canvasH) { count ->
                                Toast.makeText(ctx, "已恢复 $count 个窗口", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            storeState.slots.forEach { slot ->
                val selected = slot.id == storeState.activeSlotId
                val pillShape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(if (selected) OpalPalette.AccentBlue.copy(alpha = 0.18f) else OpalPalette.ChipFill)
                        .border(
                            width = if (selected) 1.1.dp else 0.7.dp,
                            color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderHairline,
                            shape = pillShape,
                        )
                        .clickable {
                            val loaded = ProfileConfigStore.loadSlot(ctx, slot.id)
                            BridgeClient.syncProfileToDaemonAsync(loaded) {
                                Toast.makeText(ctx, "已切换至槽位 ${slot.id + 1}「${loaded.name}」", Toast.LENGTH_SHORT).show()
                            }
                            onSlotSelected?.invoke(slot.id)
                        }
                        .padding(horizontal = if (compact) 9.dp else 10.dp, vertical = if (compact) 5.dp else 6.dp),
                ) {
                    OpalText(
                        text = "${slot.id + 1}. ${slot.name}",
                        fontSize = if (compact) 11.sp else 11.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

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
            // Pinned Header
            OpalText(text = title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = if (hasChangesToSave) subtitle else "本次会话未修改配置且无打开窗口，可直接退出或保存到配置槽位。",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 17.sp,
            )

            Spacer(Modifier.height(10.dp))
            // Scrollable Body: Compact selected slot summary + collapsible 5-slot picker
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val summaryShape = RoundedCornerShape(13.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(summaryShape)
                        .background(OpalPalette.AccentBlue.copy(alpha = 0.14f))
                        .border(1.dp, OpalPalette.AccentBlue.copy(alpha = 0.55f), summaryShape)
                        .clickable(enabled = !busy) { showSlotPicker = !showSlotPicker }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OpalText(
                            text = "保存到：槽位 ${selectedSlot.id + 1} · ${selectedSlot.name}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = OpalPalette.AccentBlue,
                            maxLines = 1,
                        )
                        val dpiSummary = if (selectedSlot.globalCustomDpi > 0) "${selectedSlot.globalCustomDpi} DPI" else "自动 DPI"
                        OpalText(
                            text = "已存：$dpiSummary · ${selectedSlot.appCustomDpis.size} 应用 · ${selectedSlot.savedLayout.size} 窗",
                            fontSize = 10.5.sp,
                            color = OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    OpalText(
                        text = if (showSlotPicker) "收起 ▴" else "切换槽位 ▾",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentBlue,
                    )
                }

                if (showSlotPicker) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        storeState.slots.forEach { slot ->
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
                                    .clickable(enabled = !busy) {
                                        selectedSlotId = slot.id
                                        showSlotPicker = false
                                    }
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(
                                        text = "槽位 ${slot.id + 1} · ${slot.name}",
                                        fontSize = 12.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                                        maxLines = 1,
                                    )
                                    val dpiSummary = if (slot.globalCustomDpi > 0) "${slot.globalCustomDpi} DPI" else "自动 DPI"
                                    OpalText(
                                        text = "$dpiSummary · ${slot.appCustomDpis.size} 应用 · ${slot.savedLayout.size} 窗",
                                        fontSize = 10.sp,
                                        color = OpalPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                }
                                if (selected) {
                                    OpalStatusBadge(label = "当前", color = OpalPalette.AccentBlue)
                                }
                            }
                        }
                    }
                }

                if (openWindowCount > 0) {
                    OpalSwitchRow(
                        title = "同时保留当前窗口布局（$openWindowCount 个窗口）",
                        subtitle = "记录已打开窗口的位置、大小、贴边与最小化状态",
                        checked = includeLayout,
                        onCheckedChange = { includeLayout = it },
                    )
                }
            }

            // Pinned Bottom Action Row
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OpalButton(
                    label = "取消",
                    compact = true,
                    filled = false,
                    enabled = !busy,
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.7f),
                )
                OpalButton(
                    label = if (hasChangesToSave) "不保存退出" else "直接退出",
                    compact = true,
                    filled = !hasChangesToSave,
                    accent = if (hasChangesToSave) OpalPalette.AccentDanger else OpalPalette.AccentBlue,
                    enabled = !busy,
                    onClick = onExitWithoutSaving,
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = if (busy) "正在保存…" else confirmLabel,
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
                onValueChange = { v ->
                    dpiVal = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(160, 200, 240, 320, 400).forEach { preset ->
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
        // Compact Header
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

        // Section 1: Global Custom DPI (commit once on release)
        val isCustomGlobal = working.globalCustomDpi > 0
        var draftGlobalDpi by remember(working.globalCustomDpi) { mutableIntStateOf(working.globalCustomDpi.coerceAtLeast(ProfileConfigStore.MIN_DPI)) }
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
                        BridgeClient.setGlobalCustomDpiAsync(ctx, 0)
                    } else {
                        val initDpi = if (working.globalCustomDpi > 0) working.globalCustomDpi else 240
                        draftGlobalDpi = initDpi
                        BridgeClient.setGlobalCustomDpiAsync(ctx, initDpi)
                    }
                },
            )
            if (isCustomGlobal) {
                OpalGlassSlider(
                    value = draftGlobalDpi.toFloat(),
                    valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                    onValueChange = { v ->
                        draftGlobalDpi = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                    },
                    onValueChangeFinished = {
                        if (draftGlobalDpi != working.globalCustomDpi) {
                            BridgeClient.setGlobalCustomDpiAsync(ctx, draftGlobalDpi)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(160, 200, 240, 320, 400).forEach { preset ->
                        OpalButton(
                            label = "$preset",
                            compact = true,
                            filled = draftGlobalDpi == preset,
                            accent = if (draftGlobalDpi == preset) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                            onClick = {
                                draftGlobalDpi = preset
                                BridgeClient.setGlobalCustomDpiAsync(ctx, preset)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Section 2: Currently Open Windows Per-App DPI (live visual adjustment)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "当前窗口 (${openWindows.distinctBy { it.pkg }.size})",
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

            if (openWindows.isEmpty()) {
                OpalText(
                    text = "打开任意应用后，可在此实时拖动调节它的独立 DPI",
                    fontSize = 11.sp,
                    color = OpalPalette.TextMuted,
                )
            } else {
                openWindows.distinctBy { it.pkg }.forEach { win ->
                    val customDpi = working.appCustomDpis[win.pkg] ?: 0
                    val effectiveDpi = when {
                        customDpi > 0 -> customDpi
                        win.contentDpi in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI -> win.contentDpi
                        working.globalCustomDpi > 0 -> working.globalCustomDpi
                        else -> 240
                    }
                    var draftWinDpi by remember(win.pkg, effectiveDpi) { mutableIntStateOf(effectiveDpi) }
                    val icon = remember(win.pkg) { AppCatalogRepository.getAppIcon(ctx, win.pkg) }
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
                                            .clickable { BridgeClient.setAppCustomDpiAsync(ctx, win.pkg, 0) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        OpalGlassSlider(
                            value = draftWinDpi.toFloat(),
                            valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                            onValueChange = { v ->
                                draftWinDpi = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                            },
                            onValueChangeFinished = {
                                if (draftWinDpi != customDpi) {
                                    BridgeClient.setAppCustomDpiAsync(ctx, win.pkg, draftWinDpi)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val cropDp = working.appTopCropDps[win.pkg] ?: 0
                        val isCropTop = cropDp > 0
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isCropTop) OpalPalette.AccentBlue.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable {
                                        ProfileConfigStore.toggleWorkingAppTopCrop(win.pkg)
                                    }
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
                                            ProfileConfigStore.setWorkingAppTopCropDp(win.pkg, snapped)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 3: Compact summary of saved per-app overrides not currently open (remove-only)
        val closedOverrides = working.appCustomDpis.entries.filter { !openByPkg.containsKey(it.key) }
        if (closedOverrides.isNotEmpty()) {
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
                                    .clickable { BridgeClient.setAppCustomDpiAsync(ctx, pkg, 0) }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
