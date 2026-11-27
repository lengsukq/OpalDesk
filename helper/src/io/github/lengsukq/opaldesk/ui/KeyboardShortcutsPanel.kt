package io.github.lengsukq.opaldesk.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout

/**
 * Stable identifiers for toggleable OpalDesk desktop global shortcuts.
 * Persisted as comma-separated [prefKey] values in [AppPrefs].
 */
enum class WorkstationShortcutId(val prefKey: String) {
    OPEN_SPOTLIGHT("open_spotlight"),
    SWITCH_WINDOW("alt_tab"),
    CLOSE_WINDOW("ctrl_w"),
    TOGGLE_FULLSCREEN("alt_enter"),
    EXIT_FULLSCREEN_ESC("esc_exit_fullscreen"),
    WINDOW_TILING("alt_tiling"),
    ;

    companion object {
        val ALL_PREF_KEYS: Set<String> = entries.map { it.prefKey }.toSet()
    }
}

typealias GlobalShortcutId = WorkstationShortcutId

data class ShortcutCheatItem(
    val keys: List<String>,
    val title: String,
    val subtitle: String,
    val toggleId: WorkstationShortcutId? = null,
)

sealed interface GlobalShortcutAction {
    data object OpenSpotlight : GlobalShortcutAction
    data object SwitchNextWindow : GlobalShortcutAction
    data object CloseFocusedWindow : GlobalShortcutAction
    data object ToggleFullscreen : GlobalShortcutAction
    data object ExitFullscreen : GlobalShortcutAction
    data class ApplyTiling(val layout: WindowTilingLayout) : GlobalShortcutAction
}

/**
 * Single Source of Truth for OpalDesk workstation keyboard shortcuts, shared by
 * [WorkstationKeyDispatcher], [DexKeyboardShortcutsPopover], [ConsoleKeyboardShortcutsCard],
 * and JVM unit tests.
 */
object WorkstationShortcutCatalog {
    val windowShortcuts: List<ShortcutCheatItem> = listOf(
        ShortcutCheatItem(
            keys = listOf("Alt", "Space"),
            title = "全局搜索 Spotlight",
            subtitle = "亦支持 Ctrl+K · 秒搜窗口、应用、桌面指令与设置",
            toggleId = WorkstationShortcutId.OPEN_SPOTLIGHT,
        ),
        ShortcutCheatItem(
            keys = listOf("Alt", "Tab"),
            title = "切换聚焦窗口",
            subtitle = "在已打开窗口间循环切换",
            toggleId = WorkstationShortcutId.SWITCH_WINDOW,
        ),
        ShortcutCheatItem(
            keys = listOf("Ctrl", "W"),
            title = "关闭当前窗口",
            subtitle = "与浏览器标签或终端冲突时可单独关闭",
            toggleId = WorkstationShortcutId.CLOSE_WINDOW,
        ),
        ShortcutCheatItem(
            keys = listOf("Alt", "Enter"),
            title = "全屏 / 还原窗口",
            subtitle = "切换当前聚焦窗口的真全屏状态",
            toggleId = WorkstationShortcutId.TOGGLE_FULLSCREEN,
        ),
        ShortcutCheatItem(
            keys = listOf("Esc"),
            title = "退出窗口全屏",
            subtitle = "仅在窗口全屏时生效，非全屏直接透传应用",
            toggleId = WorkstationShortcutId.EXIT_FULLSCREEN_ESC,
        ),
    )

    val tilingShortcuts: List<ShortcutCheatItem> = listOf(
        ShortcutCheatItem(listOf("Alt", "2"), "左右双分屏", "两列等宽", WorkstationShortcutId.WINDOW_TILING),
        ShortcutCheatItem(listOf("Alt", "3"), "三列并排", "三列等宽", WorkstationShortcutId.WINDOW_TILING),
        ShortcutCheatItem(listOf("Alt", "4"), "四列并排", "四列等宽", WorkstationShortcutId.WINDOW_TILING),
        ShortcutCheatItem(listOf("Alt", "5"), "五列并排", "五列等宽", WorkstationShortcutId.WINDOW_TILING),
        ShortcutCheatItem(listOf("Alt", "G"), "2×2 四宫格", "四窗网格", WorkstationShortcutId.WINDOW_TILING),
        ShortcutCheatItem(listOf("Alt", "6"), "3×2 六宫格", "六窗网格", WorkstationShortcutId.WINDOW_TILING),
        ShortcutCheatItem(listOf("Alt", "8"), "4×2 八宫格", "八窗网格", WorkstationShortcutId.WINDOW_TILING),
    )

    val passthroughShortcuts: List<ShortcutCheatItem> = listOf(
        ShortcutCheatItem(
            keys = listOf("Shift", "Space"),
            title = "切换中英文 / 输入法",
            subtitle = "亦支持 Ctrl+Space 与键盘地球键，直达当前窗口",
        ),
        ShortcutCheatItem(
            keys = listOf("Ctrl", "C / V / A"),
            title = "复制 / 粘贴 / 全选",
            subtitle = "始终直接透传给当前应用，不受快捷键开关影响",
        ),
    )

    val allShortcuts: List<ShortcutCheatItem> = windowShortcuts + tilingShortcuts + passthroughShortcuts

    val totalGlobalShortcutCount: Int = windowShortcuts.size + tilingShortcuts.size

    fun countActiveGlobalShortcuts(globalEnabled: Boolean, disabledIds: Set<String>): Int {
        if (!globalEnabled) return 0
        var active = 0
        for (item in windowShortcuts) {
            val id = item.toggleId?.prefKey ?: continue
            if (id !in disabledIds) active++
        }
        if (WorkstationShortcutId.WINDOW_TILING.prefKey !in disabledIds) {
            active += tilingShortcuts.size
        }
        return active
    }

    fun hasCustomizations(globalEnabled: Boolean, disabledIds: Set<String>): Boolean =
        !globalEnabled || disabledIds.any { it in WorkstationShortcutId.ALL_PREF_KEYS }

    fun formatCompactStatus(globalEnabled: Boolean, disabledIds: Set<String>): String {
        val active = countActiveGlobalShortcuts(globalEnabled, disabledIds)
        return when {
            !globalEnabled || active == 0 -> "已关闭"
            active == totalGlobalShortcutCount -> "已开启 · ${totalGlobalShortcutCount}项"
            else -> "已开启 · $active/${totalGlobalShortcutCount}项"
        }
    }

    fun formatDetailSubtitle(globalEnabled: Boolean, disabledIds: Set<String>): String {
        val active = countActiveGlobalShortcuts(globalEnabled, disabledIds)
        return when {
            !globalEnabled || active == 0 -> "已关闭 · 组合键直接透传给当前应用"
            active == totalGlobalShortcutCount -> "全部 $totalGlobalShortcutCount 项生效 · 查看列表或按需关闭"
            else -> "已启用 $active/$totalGlobalShortcutCount 项 · 点击查看与调整"
        }
    }

    fun serializeDisabledIds(disabledIds: Set<String>): String =
        disabledIds
            .asSequence()
            .map { it.trim() }
            .filter { it in WorkstationShortcutId.ALL_PREF_KEYS }
            .distinct()
            .sorted()
            .joinToString(",")

    fun parseDisabledIds(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it in WorkstationShortcutId.ALL_PREF_KEYS }
            .toSet()
    }

    /**
     * Pure JVM-testable resolver mapping a physical/remote `ACTION_DOWN` key event onto a
     * [GlobalShortcutAction], respecting both the master switch and per-shortcut disables.
     */
    fun resolveGlobalShortcutAction(
        keyCode: Int,
        metaState: Int,
        hasFocusedWindow: Boolean,
        focusedIsFullscreen: Boolean,
        globalEnabled: Boolean,
        disabledIds: Set<String>,
    ): GlobalShortcutAction? {
        if (!globalEnabled) return null
        val altPressed = (metaState and (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON)) != 0
        val ctrlPressed = (metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON)) != 0

        if ((altPressed && keyCode == KeyEvent.KEYCODE_SPACE) ||
            (ctrlPressed && keyCode == KeyEvent.KEYCODE_K)
        ) {
            return if (WorkstationShortcutId.OPEN_SPOTLIGHT.prefKey !in disabledIds) {
                GlobalShortcutAction.OpenSpotlight
            } else {
                null
            }
        }
        if (altPressed && keyCode == KeyEvent.KEYCODE_TAB) {
            return if (WorkstationShortcutId.SWITCH_WINDOW.prefKey !in disabledIds) {
                GlobalShortcutAction.SwitchNextWindow
            } else {
                null
            }
        }
        if (altPressed && keyCode == KeyEvent.KEYCODE_ENTER && hasFocusedWindow) {
            return if (WorkstationShortcutId.TOGGLE_FULLSCREEN.prefKey !in disabledIds) {
                GlobalShortcutAction.ToggleFullscreen
            } else {
                null
            }
        }
        if (altPressed) {
            val layout = when (keyCode) {
                KeyEvent.KEYCODE_2 -> WindowTilingLayout.SPLIT_2_COLS
                KeyEvent.KEYCODE_3 -> WindowTilingLayout.SPLIT_3_COLS
                KeyEvent.KEYCODE_4 -> WindowTilingLayout.SPLIT_4_COLS
                KeyEvent.KEYCODE_5 -> WindowTilingLayout.SPLIT_5_COLS
                KeyEvent.KEYCODE_6 -> WindowTilingLayout.SPLIT_6_GRID
                KeyEvent.KEYCODE_8 -> WindowTilingLayout.SPLIT_8_GRID
                KeyEvent.KEYCODE_G -> WindowTilingLayout.SPLIT_4_GRID
                else -> null
            }
            if (layout != null) {
                return if (WorkstationShortcutId.WINDOW_TILING.prefKey !in disabledIds) {
                    GlobalShortcutAction.ApplyTiling(layout)
                } else {
                    null
                }
            }
        }
        if (ctrlPressed && keyCode == KeyEvent.KEYCODE_W && hasFocusedWindow) {
            return if (WorkstationShortcutId.CLOSE_WINDOW.prefKey !in disabledIds) {
                GlobalShortcutAction.CloseFocusedWindow
            } else {
                null
            }
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && hasFocusedWindow && focusedIsFullscreen) {
            return if (WorkstationShortcutId.EXIT_FULLSCREEN_ESC.prefKey !in disabledIds) {
                GlobalShortcutAction.ExitFullscreen
            } else {
                null
            }
        }
        return null
    }
}

/**
 * Floating Liquid Glass Keyboard Shortcuts Cheatsheet & Toggle Panel inside DeX Desktop.
 */
@Composable
fun DexKeyboardShortcutsPopover(
    maxHeight: Dp = 560.dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val globalEnabled by AppPrefs.globalShortcutsEnabledFlow.collectAsState()
    val disabledIds by AppPrefs.disabledShortcutIdsFlow.collectAsState()
    LaunchedEffect(Unit) { AppPrefs.hydrateFlows(ctx) }

    val activeCount = WorkstationShortcutCatalog.countActiveGlobalShortcuts(globalEnabled, disabledIds)
    val customized = WorkstationShortcutCatalog.hasCustomizations(globalEnabled, disabledIds)
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .widthIn(max = 368.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.SolidPopoverFill,
                rim = OpalPalette.SolidPopoverRim,
                elevation = 26.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.weight(1f),
            ) {
                OpalText(text = "键盘快捷键", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                OpalStatusBadge(
                    label = WorkstationShortcutCatalog.formatCompactStatus(globalEnabled, disabledIds),
                    color = if (globalEnabled && activeCount > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (customized) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.ChipActiveFill)
                            .clickable { AppPrefs.resetKeyboardShortcuts(ctx) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        OpalText(
                            text = "恢复默认",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.AccentBlue,
                        )
                    }
                }
                OpalButton(label = "完成", compact = true, filled = false, onClick = onDismiss)
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            KeyboardShortcutsBody(
                globalEnabled = globalEnabled,
                disabledIds = disabledIds,
                compact = true,
                onToggleGlobal = { AppPrefs.setGlobalShortcutsEnabled(ctx, it) },
                onToggleItem = { id, enabled -> AppPrefs.setShortcutEnabled(ctx, id, enabled) },
            )
        }
    }
}

/**
 * Inner content for [OpalCollapsibleSectionCard] in Tab 1 ("显示与外设") without nested outer glass cards.
 */
@Composable
fun ConsoleKeyboardShortcutsSectionContent() {
    val ctx = LocalContext.current
    val globalEnabled by AppPrefs.globalShortcutsEnabledFlow.collectAsState()
    val disabledIds by AppPrefs.disabledShortcutIdsFlow.collectAsState()
    LaunchedEffect(Unit) { AppPrefs.hydrateFlows(ctx) }

    val customized = WorkstationShortcutCatalog.hasCustomizations(globalEnabled, disabledIds)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (customized) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = WorkstationShortcutCatalog.formatDetailSubtitle(globalEnabled, disabledIds),
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OpalText(
                    text = "恢复默认",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.ChipActiveFill)
                        .clickable { AppPrefs.resetKeyboardShortcuts(ctx) }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
        KeyboardShortcutsBody(
            globalEnabled = globalEnabled,
            disabledIds = disabledIds,
            compact = false,
            showMasterSwitch = true,
            onToggleGlobal = { AppPrefs.setGlobalShortcutsEnabled(ctx, it) },
            onToggleItem = { id, enabled -> AppPrefs.setShortcutEnabled(ctx, id, enabled) },
        )
    }
}

/**
 * Console Settings card under "显示与外设 → 键鼠与壁纸" allowing users to discover all
 * OpalDesk keyboard shortcuts and toggle them globally or individually before entering DeX.
 */
@Composable
fun ConsoleKeyboardShortcutsCard(
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val globalEnabled by AppPrefs.globalShortcutsEnabledFlow.collectAsState()
    val disabledIds by AppPrefs.disabledShortcutIdsFlow.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { AppPrefs.hydrateFlows(ctx) }

    val activeCount = WorkstationShortcutCatalog.countActiveGlobalShortcuts(globalEnabled, disabledIds)
    val customized = WorkstationShortcutCatalog.hasCustomizations(globalEnabled, disabledIds)

    MacGlassSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OpalText(text = "外接键盘快捷键", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                OpalText(
                    text = WorkstationShortcutCatalog.formatDetailSubtitle(globalEnabled, disabledIds),
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            OpalStatusBadge(
                label = WorkstationShortcutCatalog.formatCompactStatus(globalEnabled, disabledIds),
                color = if (globalEnabled && activeCount > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
            )
        }

        Spacer(Modifier.height(8.dp))
        OpalSwitchRow(
            title = "启用桌面全局快捷键",
            subtitle = "关闭后，Alt+Tab / Ctrl+W / Alt+Enter / 分屏等组合键全部原样透传给当前应用",
            checked = globalEnabled,
            onCheckedChange = { AppPrefs.setGlobalShortcutsEnabled(ctx, it) },
        )

        Spacer(Modifier.height(6.dp))
        val previewShape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(previewShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, previewShape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                OpalText(
                    text = if (expanded) "收起快捷键列表" else "查看全部快捷键与单独开关",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (customized && expanded) {
                    OpalText(
                        text = "恢复默认",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentBlue,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.ChipActiveFill)
                            .clickable { AppPrefs.resetKeyboardShortcuts(ctx) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                OpalText(
                    text = if (expanded) "收起 ▴" else "展开 ▾",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = OpalPalette.TextSecondary,
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            KeyboardShortcutsBody(
                globalEnabled = globalEnabled,
                disabledIds = disabledIds,
                compact = false,
                showMasterSwitch = false,
                onToggleGlobal = { AppPrefs.setGlobalShortcutsEnabled(ctx, it) },
                onToggleItem = { id, enabled -> AppPrefs.setShortcutEnabled(ctx, id, enabled) },
            )
        }
    }
}

@Composable
private fun KeyboardShortcutsBody(
    globalEnabled: Boolean,
    disabledIds: Set<String>,
    compact: Boolean,
    showMasterSwitch: Boolean = true,
    onToggleGlobal: (Boolean) -> Unit,
    onToggleItem: (WorkstationShortcutId, Boolean) -> Unit,
) {
    val cardShape = RoundedCornerShape(13.dp)
    val sectionGap = if (compact) 8.dp else 10.dp

    Column(verticalArrangement = Arrangement.spacedBy(sectionGap)) {
        if (showMasterSwitch) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(OpalPalette.ChipFill)
                    .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                OpalSwitchRow(
                    title = "启用桌面全局快捷键",
                    subtitle = if (globalEnabled) {
                        "下方各项可按需单独关闭，关闭后将直接透传给应用"
                    } else {
                        "已全部关闭 · 所有组合键原样透传给当前应用"
                    },
                    checked = globalEnabled,
                    onCheckedChange = onToggleGlobal,
                )
            }
        }

        // Section 1: Window Control Shortcuts
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpalText(
                text = "窗口控制",
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextSecondary,
            )
            WorkstationShortcutCatalog.windowShortcuts.forEachIndexed { idx, item ->
                if (idx > 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))
                }
                val toggleId = item.toggleId
                val itemEnabled = toggleId == null || toggleId.prefKey !in disabledIds
                val effectiveOn = globalEnabled && itemEnabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = globalEnabled && toggleId != null) {
                            if (toggleId != null) onToggleItem(toggleId, !itemEnabled)
                        }
                        .padding(vertical = 3.dp)
                        .alpha(if (globalEnabled) 1f else 0.48f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ShortcutKeyCapRow(keys = item.keys, active = effectiveOn)
                            OpalText(
                                text = item.title,
                                fontSize = if (compact) 12.sp else 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (effectiveOn) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                                maxLines = 1,
                            )
                        }
                        OpalText(
                            text = item.subtitle,
                            fontSize = if (compact) 10.sp else 11.sp,
                            color = OpalPalette.TextMuted,
                        )
                    }
                    if (toggleId != null) {
                        Spacer(Modifier.width(8.dp))
                        CompactShortcutToggle(checked = effectiveOn)
                    }
                }
            }
        }

        // Section 2: Tiling Layout Shortcuts (grouped under 1 toggle + crisp 2-col keycap grid)
        val tilingEnabled = WorkstationShortcutId.WINDOW_TILING.prefKey !in disabledIds
        val tilingEffectiveOn = globalEnabled && tilingEnabled
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = globalEnabled) {
                        onToggleItem(WorkstationShortcutId.WINDOW_TILING, !tilingEnabled)
                    }
                    .alpha(if (globalEnabled) 1f else 0.48f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    OpalText(
                        text = "一键分屏排版",
                        fontSize = if (compact) 11.5.sp else 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextPrimary,
                    )
                    OpalText(
                        text = "自动按列或网格排列当前可见窗口",
                        fontSize = if (compact) 10.sp else 11.sp,
                        color = OpalPalette.TextMuted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                CompactShortcutToggle(checked = tilingEffectiveOn)
            }

            Column(
                modifier = Modifier.alpha(if (tilingEffectiveOn) 1f else 0.48f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                WorkstationShortcutCatalog.tilingShortcuts.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pair.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(OpalPalette.GlassSurface.copy(alpha = if (OpalPalette.isDark) 0.45f else 0.65f))
                                    .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 7.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                ShortcutKeyCapRow(keys = item.keys, active = tilingEffectiveOn)
                                Spacer(Modifier.width(4.dp))
                                OpalText(
                                    text = item.title,
                                    fontSize = if (compact) 10.5.sp else 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = OpalPalette.TextPrimary,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Section 3: IME & App Pass-through Reference
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "输入法与常用直通",
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = "始终直通应用",
                    fontSize = 10.sp,
                    color = OpalPalette.TextMuted,
                )
            }
            WorkstationShortcutCatalog.passthroughShortcuts.forEachIndexed { idx, item ->
                if (idx > 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ShortcutKeyCapRow(keys = item.keys, active = true)
                        OpalText(
                            text = item.title,
                            fontSize = if (compact) 11.5.sp else 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = OpalPalette.TextPrimary,
                            maxLines = 1,
                        )
                    }
                    OpalText(
                        text = item.subtitle,
                        fontSize = if (compact) 10.sp else 11.sp,
                        color = OpalPalette.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutKeyCapRow(
    keys: List<String>,
    active: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        keys.forEachIndexed { idx, keyLabel ->
            if (idx > 0) {
                OpalText(
                    text = "+",
                    fontSize = 10.sp,
                    color = OpalPalette.TextMuted,
                )
            }
            val capShape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .clip(capShape)
                    .background(
                        if (active) {
                            OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.12f)
                        } else {
                            OpalPalette.ChipActiveFill
                        },
                    )
                    .border(
                        width = 0.75.dp,
                        color = if (active) OpalPalette.AccentBlue.copy(alpha = 0.45f) else OpalPalette.BorderHairline,
                        shape = capShape,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                OpalText(
                    text = keyLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (active) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CompactShortcutToggle(
    checked: Boolean,
) {
    val colors = LocalOpalColors.current
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(21.dp)
            .clip(CircleShape)
            .background(if (checked) colors.accentEmerald else colors.switchTrackOff)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
