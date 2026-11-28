package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus

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
                                Toast.makeText(ctx, ctx.getString(R.string.slot_loaded_toast, slot.id + 1, loaded.name), Toast.LENGTH_SHORT).show()
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
                    val dpiBadge = if (slot.globalCustomDpi > 0) "${slot.globalCustomDpi} DPI" else stringResource(R.string.slot_auto_dpi)
                    OpalText(
                        text = stringResource(R.string.slot_meta_line, dpiBadge, slot.appCustomDpis.size, slot.savedLayout.size),
                        fontSize = 10.sp,
                        color = OpalPalette.TextMuted,
                        maxLines = 1,
                    )
                }
            }
        }

        OpalSwitchRow(
            title = stringResource(R.string.slot_auto_match_title),
            subtitle = stringResource(R.string.slot_auto_match_sub),
            checked = storeState.autoMatchTargetSlot,
            onCheckedChange = { ProfileConfigStore.setAutoMatchTargetSlot(ctx, it) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = stringResource(R.string.slot_current_summary, activeSlot.name, activeSlot.boundTargets.size, working.appWindowBounds.size),
                fontSize = 11.5.sp,
                color = OpalPalette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OpalButton(
                label = stringResource(if (showRenameRow) R.string.common_collapse_short else R.string.common_rename),
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
                    placeholder = stringResource(R.string.slot_rename_placeholder),
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = stringResource(R.string.slot_save_name),
                    compact = true,
                    onClick = {
                        ProfileConfigStore.renameSlot(ctx, activeSlot.id, editingProfileName)
                        showRenameRow = false
                        Toast.makeText(ctx, ctx.getString(R.string.slot_name_updated_toast), Toast.LENGTH_SHORT).show()
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
            title = stringResource(R.string.slot_keep_layout_title),
            subtitle = stringResource(R.string.slot_keep_layout_sub),
            checked = working.keepLayoutOnExit,
            onCheckedChange = { ProfileConfigStore.setWorkingKeepLayoutOnExit(it) },
        )
        OpalSwitchRow(
            title = stringResource(R.string.slot_auto_restore_title),
            subtitle = stringResource(R.string.slot_auto_restore_sub),
            checked = working.autoRestoreLayout,
            onCheckedChange = { ProfileConfigStore.setWorkingAutoRestoreLayout(it) },
        )

        if (activeSlot.savedLayout.isNotEmpty()) {
            OpalButton(
                label = stringResource(R.string.slot_restore_now_btn, activeSlot.id + 1, activeSlot.savedLayout.size),
                compact = true,
                filled = false,
                accent = OpalPalette.AccentEmerald,
                enabled = status.connected,
                onClick = {
                    BridgeClient.restoreSavedLayoutAsync(activeSlot, status.canvasWidth, status.canvasHeight) { count ->
                        Toast.makeText(ctx, ctx.getString(R.string.slot_restored_toast, count), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Advanced Settings section in Console "设置" tab:
 * - 5 persistent Android-side workspace configuration slots
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
                    text = stringResource(R.string.slot_quick_title),
                    fontSize = if (compact) 11.5.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = stringResource(if (storeState.autoMatchTargetSlot) R.string.slot_quick_auto_on else R.string.slot_quick_manual),
                    fontSize = 10.sp,
                    color = if (storeState.autoMatchTargetSlot) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
                )
            }
            if (showRestoreButton && activeSlot.savedLayout.isNotEmpty()) {
                OpalText(
                    text = stringResource(R.string.slot_quick_restore, activeSlot.savedLayout.size),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            BridgeClient.restoreSavedLayoutAsync(activeSlot, canvasW, canvasH) { count ->
                                Toast.makeText(ctx, ctx.getString(R.string.slot_restored_toast, count), Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(ctx, ctx.getString(R.string.slot_switched_toast, slot.id + 1, loaded.name), Toast.LENGTH_SHORT).show()
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
