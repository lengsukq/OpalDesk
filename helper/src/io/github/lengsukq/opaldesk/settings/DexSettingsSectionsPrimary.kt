package io.github.lengsukq.opaldesk.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.compositor.WorkspaceUiState
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.DockCustomizationConfig
import io.github.lengsukq.opaldesk.prefs.DockCustomizationStore
import io.github.lengsukq.opaldesk.prefs.DockGlassStyle
import io.github.lengsukq.opaldesk.prefs.DockSizePreset
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassSlider
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalSwitchRow
import io.github.lengsukq.opaldesk.ui.OpalText
import kotlin.math.roundToInt

@Composable
internal fun DexAppearanceSettingsSection(
    bridge: BridgeClient,
    uiState: WorkspaceUiState,
    isZh: Boolean,
    onOpenWidgetStore: () -> Unit,
    onToggleWidgetEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    val languagePref by AppPrefs.languagePrefFlow.collectAsState()
    val profileState by ProfileConfigStore.stateFlow.collectAsState()
    val scene by bridge.sceneFlow.collectAsState()

    // 1. Theme & Wallpaper
    DexSettingsGroupCard(
        title = if (isZh) "主题与壁纸" else "Theme & Wallpaper",
        subtitle = if (isZh) "切换液态毛玻璃明暗模式与内置氛围壁纸" else "Switch liquid glass theme and ambient wallpapers",
    ) {
        OpalSegmentedRow(
            options = if (isZh) listOf("浅色玻璃", "跟随系统", "深色玻璃") else listOf("Light", "System", "Dark"),
            selectedIndex = themeMode.coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK),
            onSelect = { AppPrefs.setThemeMode(ctx, it) },
        )
        Spacer(Modifier.height(4.dp))
        val presetLabels = if (isZh) {
            listOf("海湾晴空", "霞光暮色", "冰川青蓝", "星夜静谧")
        } else {
            listOf("Azure Sky", "Sunset Glow", "Glacier Cyan", "Obsidian Mist")
        }
        val presetSwatches = listOf(
            listOf(Color(0xFF4F8FF7), Color(0xFFA78BFA)),
            listOf(Color(0xFFF472B6), Color(0xFFFB923C)),
            listOf(Color(0xFF38BDF8), Color(0xFF34D399)),
            listOf(Color(0xFF334155), Color(0xFF64748B)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presetSwatches.forEachIndexed { idx, stops ->
                val active = customWallpaperPath.isBlank() && wallpaperPreset == idx
                val tileShape = RoundedCornerShape(14.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(tileShape)
                        .border(
                            width = if (active) 1.8.dp else 0.8.dp,
                            color = if (active) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            shape = tileShape,
                        )
                        .clickable {
                            AppPrefs.clearCustomWallpaper(ctx)
                            AppPrefs.setWallpaperPreset(ctx, idx)
                        }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(stops)),
                    )
                    OpalText(
                        text = presetLabels[idx],
                        fontSize = 10.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        if (customWallpaperPath.isNotBlank()) {
            DexSettingsDivider()
            DexSettingsActionRow(
                title = if (isZh) "当前正在使用自定义壁纸" else "Custom wallpaper active",
                subtitle = if (isZh) "清除后将恢复上方内置液态氛围壁纸" else "Clear to restore built-in ambient wallpapers",
                actionLabel = if (isZh) "清除自定义" else "Clear",
                accent = OpalPalette.AccentAmber,
                onClick = { AppPrefs.clearCustomWallpaper(ctx) },
            )
        }
    }

    // 2. Desktop Profile Slots (Slot 0..4)
    DexSettingsGroupCard(
        title = if (isZh) "桌面配置插槽 (Slot 1–5)" else "Desktop Profile Slots (1–5)",
        subtitle = if (isZh) "不同显示器与投屏设备可保存独立 DPI、帧率与窗口布局" else "Keep independent DPI, FPS and layouts per display",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            profileState.slots.forEach { slot ->
                val active = slot.id == profileState.activeSlotId
                DexSettingsInlineChip(
                    label = "${slot.id + 1}",
                    selected = active,
                    onClick = {
                        val loaded = ProfileConfigStore.loadSlot(ctx, slot.id)
                        bridge.syncProfileToDaemonAsync(loaded)
                        Toast.makeText(
                            ctx,
                            if (isZh) "已切换到插槽 ${slot.id + 1} · ${slot.name}" else "Switched to Slot ${slot.id + 1} · ${slot.name}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val activeSlot = profileState.activeSavedSlot
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OpalText(
                    text = if (isZh) "当前插槽：${activeSlot.id + 1} · ${activeSlot.name}" else "Active Slot: ${activeSlot.id + 1} · ${activeSlot.name}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                OpalText(
                    text = if (profileState.hasUnsavedChanges) {
                        if (isZh) "有未保存的实时调整" else "Unsaved live tweaks pending"
                    } else {
                        if (isZh) "已保存 ${activeSlot.savedLayout.size} 个窗口布局" else "${activeSlot.savedLayout.size} saved windows"
                    },
                    fontSize = 11.5.sp,
                    color = if (profileState.hasUnsavedChanges) OpalPalette.AccentAmber else OpalPalette.TextSecondary,
                )
            }
            OpalButton(
                label = if (isZh) "保存到当前插槽" else "Save Slot",
                compact = true,
                filled = profileState.hasUnsavedChanges,
                onClick = {
                    ProfileConfigStore.saveWorkingToSlot(
                        ctx = ctx,
                        targetSlotId = profileState.activeSlotId,
                        scene = scene,
                        saveCurrentLayout = true,
                    )
                    Toast.makeText(
                        ctx,
                        if (isZh) "已保存到插槽 ${profileState.activeSlotId + 1}" else "Saved to Slot ${profileState.activeSlotId + 1}",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
        OpalSwitchRow(
            title = if (isZh) "按显示设备自动匹配插槽" else "Auto-match slot by display target",
            subtitle = if (isZh) "连接外接显示器或无线投屏时自动载入对应方案" else "Load matching slot automatically for external/cast displays",
            checked = profileState.autoMatchTargetSlot,
            onCheckedChange = { ProfileConfigStore.setAutoMatchTargetSlot(ctx, it) },
        )
    }

    // 3. Desktop Widgets & Quick Actions
    DexSettingsGroupCard(
        title = if (isZh) "桌面小组件" else "Desktop Widgets",
        subtitle = if (isZh) "自由添加时钟、天气、快捷操作等毛玻璃桌面卡片" else "Add frosted glass widgets and customize layout",
    ) {
        DexSettingsActionRow(
            title = if (isZh) "组件商店" else "Widget Store",
            subtitle = if (isZh) "浏览并添加新的桌面小组件到当前插槽" else "Browse and add desktop widgets to the current slot",
            actionLabel = if (isZh) "打开商店" else "Open Store",
            filled = true,
            onClick = {
                onDismiss()
                onOpenWidgetStore()
            },
        )
        DexSettingsDivider()
        DexSettingsActionRow(
            title = if (isZh) "编辑桌面组件" else "Edit Desktop Widgets",
            subtitle = if (uiState.isWidgetEditMode) {
                if (isZh) "当前正处于桌面组件编辑模式" else "Widget edit mode is currently active"
            } else {
                if (isZh) "调整组件位置、尺寸或移除组件" else "Drag, resize, or remove widgets on desktop"
            },
            actionLabel = if (uiState.isWidgetEditMode) {
                if (isZh) "退出编辑" else "Finish Edit"
            } else {
                if (isZh) "开始编辑" else "Edit Mode"
            },
            accent = if (uiState.isWidgetEditMode) OpalPalette.AccentEmerald else OpalPalette.AccentCyan,
            onClick = {
                onDismiss()
                onToggleWidgetEdit()
            },
        )
    }

    // 4. App Language
    DexSettingsGroupCard(
        title = if (isZh) "应用语言" else "App Language",
        subtitle = if (isZh) "即时切换界面语言，无需重启服务" else "Switch interface language immediately",
    ) {
        val langOptions = listOf(OpalLocalePolicy.LANG_SYSTEM, OpalLocalePolicy.LANG_ZH_CN, OpalLocalePolicy.LANG_EN)
        val selectedIdx = langOptions.indexOf(OpalLocalePolicy.normalizeLanguagePref(languagePref)).coerceAtLeast(0)
        OpalSegmentedRow(
            options = if (isZh) listOf("跟随系统", "简体中文", "English") else listOf("System", "简体中文", "English"),
            selectedIndex = selectedIdx,
            onSelect = { idx ->
                val chosen = langOptions.getOrElse(idx) { OpalLocalePolicy.LANG_SYSTEM }
                if (chosen != OpalLocalePolicy.normalizeLanguagePref(languagePref)) {
                    OpalLocalePolicy.applyRuntimeLocale(ctx, chosen)
                }
            },
        )
    }
}

@Composable
internal fun DexDockSettingsSection(isZh: Boolean) {
    val ctx = LocalContext.current
    val config by DockCustomizationStore.observe(ctx).collectAsState()

    // 1. Live Miniature Preview Bar / Orb Preview
    DexDockLivePreviewCard(config = config, isZh = isZh)

    // 2. Orb Mode (小白点模式 / 悬浮球) dedicated frosted glass card
    DexSettingsGroupCard(
        title = if (isZh) "小白点模式（悬浮球）" else "Floating Orb Mode",
        subtitle = if (isZh) "将底栏收起为可拖动毛玻璃悬浮小白点，释放底部视野" else "Collapse the Dock into a draggable frosted glass orb",
    ) {
        OpalSwitchRow(
            title = if (isZh) "启用小白点模式" else "Enable Floating Orb Mode",
            subtitle = if (isZh) "开启后底栏自动收起为悬浮小白点，轻点或悬停即可展开" else "Collapse taskbar into a floating orb; tap or hover to expand",
            checked = config.orbMode,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(orbMode = enabled) }
            },
        )
        DexSettingsDivider()
        val orbOpacity = config.clampedOrbOpacity()
        val opacityPct = (orbOpacity * 100f).roundToInt().coerceIn(25, 100)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                OpalText(
                    text = if (isZh) "小白点透明度" else "Orb Opacity",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                OpalText(
                    text = if (isZh) "调节悬浮球毛玻璃透光度，与闲置半透明协同" else "Adjust frosted glass translucency alongside idle dimming",
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            OpalStatusBadge(label = "$opacityPct%", color = OpalPalette.AccentBlue)
        }
        OpalGlassSlider(
            value = orbOpacity,
            valueRange = 0.25f..1.0f,
            onValueChange = { next ->
                DockCustomizationStore.update(ctx) { it.copy(orbOpacity = next.coerceIn(0.25f, 1.0f)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OpalSwitchRow(
            title = if (isZh) "靠边自动吸附" else "Auto-snap to screen edge",
            subtitle = if (isZh) "拖动松手后自动吸附到屏幕左侧或右侧边缘" else "Snap automatically to the nearest left or right edge",
            checked = config.orbSnapToEdge,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(orbSnapToEdge = enabled) }
            },
        )
        OpalSwitchRow(
            title = if (isZh) "鼠标悬浮自动展开 Dock" else "Expand Dock on pointer hover",
            subtitle = if (isZh) "外接键鼠指针移至小白点上方时自动展开底栏" else "Automatically expand the Dock when hovering with a mouse",
            checked = config.orbHoverExpand,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(orbHoverExpand = enabled) }
            },
        )
        OpalSwitchRow(
            title = if (isZh) "闲置半透明防遮挡" else "Dim when idle",
            subtitle = if (isZh) "无操作数秒后进一步降低不透明度，避免遮挡内容" else "Fade gently after inactivity to stay unobtrusive",
            checked = config.orbIdleDim,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(orbIdleDim = enabled) }
            },
        )
    }

    // 3. Dock Size, Style & Glass
    DexSettingsGroupCard(
        title = if (isZh) "底栏尺寸与毛玻璃质感" else "Dock Size & Glass Style",
        subtitle = if (isZh) "自定义底栏图标大小、悬浮贴边形态与玻璃材质" else "Customize icon sizing, floating bar style and glass finish",
    ) {
        OpalText(
            text = if (isZh) "底栏尺寸" else "Dock Size",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = OpalPalette.TextSecondary,
        )
        OpalSegmentedRow(
            options = if (isZh) listOf("紧凑", "标准", "大号") else listOf("Compact", "Standard", "Large"),
            selectedIndex = DockSizePreset.entries.indexOf(config.sizePreset).coerceAtLeast(1),
            onSelect = { idx ->
                val preset = DockSizePreset.entries.getOrElse(idx) { DockSizePreset.STANDARD }
                DockCustomizationStore.update(ctx) { it.copy(sizePreset = preset) }
                AppPrefs.setDockScale(ctx, config.copy(sizePreset = preset).toDockScale())
            },
        )
        Spacer(Modifier.height(4.dp))
        OpalText(
            text = if (isZh) "玻璃质感档位" else "Glass Finish",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = OpalPalette.TextSecondary,
        )
        OpalSegmentedRow(
            options = if (isZh) listOf("通透", "标准", "深邃磨砂") else listOf("Clear", "Balanced", "Frosted"),
            selectedIndex = DockGlassStyle.entries.indexOf(config.glassStyle).coerceAtLeast(1),
            onSelect = { idx ->
                val style = DockGlassStyle.entries.getOrElse(idx) { DockGlassStyle.BALANCED }
                DockCustomizationStore.update(ctx) { it.copy(glassStyle = style) }
                AppPrefs.setDockOpacity(ctx, config.copy(glassStyle = style).toDockOpacity())
            },
        )
        OpalSwitchRow(
            title = if (isZh) "悬浮胶囊样式" else "Floating Pill Style",
            subtitle = if (isZh) "关闭后切换为底部贴边全宽任务栏样式" else "Disable to use an edge-docked full-width bar",
            checked = config.floatingStyle,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(floatingStyle = enabled) }
            },
        )
        OpalSwitchRow(
            title = if (isZh) "自动隐藏底栏" else "Auto-hide Dock",
            subtitle = if (isZh) "非交互时自动收起底栏，移至底部边缘呼出" else "Hide automatically and reveal at the bottom edge",
            checked = config.autoHide,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(autoHide = enabled) }
            },
        )
    }

    // 4. Dock Elements & Reset
    DexSettingsGroupCard(
        title = if (isZh) "底栏元素显示" else "Dock Elements",
        subtitle = if (isZh) "控制快捷入口与运行中应用指示状态" else "Choose which shortcuts and indicators appear on the Dock",
    ) {
        OpalSwitchRow(
            title = if (isZh) "显示搜索图标" else "Show Search Button",
            subtitle = if (isZh) "快速唤起 Spotlight 全局搜索" else "Quick access to Spotlight search",
            checked = config.showSearchButton,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(showSearchButton = enabled) }
            },
        )
        OpalSwitchRow(
            title = if (isZh) "显示设置图标" else "Show Settings Button",
            subtitle = if (isZh) "在底栏常驻 DeX 设置快捷入口" else "Keep DeX Settings shortcut pinned on the Dock",
            checked = config.showSettingsButton,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(showSettingsButton = enabled) }
            },
        )
        OpalSwitchRow(
            title = if (isZh) "显示运行中应用" else "Show Running Apps",
            subtitle = if (isZh) "在底栏动态展示当前后台与前台窗口图标" else "Display active app icons on the Dock",
            checked = config.showRunningApps,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(showRunningApps = enabled) }
            },
        )
        OpalSwitchRow(
            title = if (isZh) "显示运行指示点" else "Show Running Indicator Dots",
            subtitle = if (isZh) "在已打开应用图标下方显示圆点标记" else "Show small dots under running app icons",
            checked = config.showRunningDots,
            onCheckedChange = { enabled ->
                DockCustomizationStore.update(ctx) { it.copy(showRunningDots = enabled) }
            },
        )
        DexSettingsDivider()
        DexSettingsActionRow(
            title = if (isZh) "恢复默认 Dock" else "Reset Default Dock",
            subtitle = if (isZh) "将尺寸、质感、元素显示与小白点恢复出厂配置" else "Restore all Dock & Orb customization options to defaults",
            actionLabel = if (isZh) "恢复默认" else "Reset",
            accent = OpalPalette.AccentAmber,
            onClick = {
                DockCustomizationStore.reset(ctx)
                AppPrefs.resetDockAppearance(ctx)
                Toast.makeText(ctx, if (isZh) "已恢复默认 Dock 配置" else "Dock settings reset to defaults", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun DexDockLivePreviewCard(config: DockCustomizationConfig, isZh: Boolean) {
    val isDark = OpalPalette.isDark
    val previewBg = Brush.linearGradient(
        if (isDark) listOf(Color(0xFF0E1B31), Color(0xFF1F3558), Color(0xFF282047))
        else listOf(Color(0xFFCDE2FC), Color(0xFFDAD7FA), Color(0xFFF6D9EB)),
    )
    val glassAlpha = when (config.glassStyle) {
        DockGlassStyle.CLEAR -> if (isDark) 0.28f else 0.34f
        DockGlassStyle.BALANCED -> if (isDark) 0.54f else 0.62f
        DockGlassStyle.FROSTED -> if (isDark) 0.80f else 0.86f
    }
    val iconSize = when (config.sizePreset) {
        DockSizePreset.COMPACT -> 22.dp
        DockSizePreset.STANDARD -> 27.dp
        DockSizePreset.LARGE -> 32.dp
    }

    DexSettingsGroupCard(
        title = if (isZh) "实时效果预览" else "Live Preview",
        subtitle = if (config.orbMode) {
            val pct = (config.clampedOrbOpacity() * 100f).roundToInt()
            if (isZh) "小白点悬浮球模式 · 透明度 $pct%" else "Floating Orb Mode · Opacity $pct%"
        } else {
            val styleTag = if (config.floatingStyle) (if (isZh) "悬浮" else "Floating") else (if (isZh) "贴边" else "Edge")
            val hideTag = if (config.autoHide) (if (isZh) " · 自动隐藏" else " · Auto-hide") else ""
            if (isZh) "实时微缩 Dock（$styleTag$hideTag）" else "Live Mini Dock ($styleTag$hideTag)"
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth().height(104.dp).clip(RoundedCornerShape(16.dp))
                .background(previewBg).border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(16.dp)),
        ) {
            if (config.orbMode) {
                val orbAlpha = config.clampedOrbOpacity()
                val effectiveAlpha = if (config.orbIdleDim) (orbAlpha * 0.86f).coerceIn(0.22f, 1f) else orbAlpha
                val orbDiameter = when (config.sizePreset) {
                    DockSizePreset.COMPACT -> 36.dp
                    DockSizePreset.STANDARD -> 42.dp
                    DockSizePreset.LARGE -> 48.dp
                }
                val orbAlign = if (config.orbDockedRight) Alignment.CenterEnd else Alignment.CenterStart
                val orbPadding = if (config.orbSnapToEdge) 8.dp else 24.dp

                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OpalStatusBadge(if (config.orbSnapToEdge) (if (isZh) "靠边吸附" else "Edge Snap") else (if (isZh) "自由悬浮" else "Free Float"), OpalPalette.AccentBlue)
                    if (config.orbHoverExpand) {
                        OpalStatusBadge(if (isZh) "悬停展开" else "Hover Expand", OpalPalette.AccentEmerald)
                    }
                }

                val orbBrush = Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = (effectiveAlpha * 0.92f).coerceIn(0.20f, 0.98f)),
                        (if (isDark) Color(0xFF28344B) else Color(0xFFDCE8FC)).copy(alpha = effectiveAlpha),
                    ),
                )
                val orbRim = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = (effectiveAlpha * 0.95f).coerceIn(0.25f, 1f)),
                        Color.White.copy(alpha = (effectiveAlpha * 0.30f).coerceIn(0.10f, 0.50f)),
                    ),
                )
                Box(
                    modifier = Modifier
                        .align(orbAlign).padding(horizontal = orbPadding).size(orbDiameter)
                        .clip(CircleShape).background(orbBrush).border(1.4.dp, orbRim, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(orbDiameter * 0.44f).clip(CircleShape)
                            .background(Color.White.copy(alpha = (effectiveAlpha * 0.88f).coerceIn(0.22f, 0.96f)))
                            .border(1.dp, OpalPalette.AccentBlue.copy(alpha = 0.45f), CircleShape),
                    )
                }
            } else {
                val dockShape = if (config.floatingStyle) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                val dockFill = (if (isDark) Color(0xFF192132) else Color.White).copy(alpha = glassAlpha)
                val dockModifier = if (config.floatingStyle) {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp, start = 14.dp, end = 14.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                }
                Row(
                    modifier = dockModifier.clip(dockShape).background(dockFill)
                        .border(0.9.dp, Color.White.copy(alpha = if (isDark) 0.28f else 0.72f), dockShape)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                ) {
                    MiniDockTile(iconSize, Color(0xFF0A84FF), ChromeGlyph.APPS_GRID, showDot = false)
                    if (config.showSearchButton) {
                        MiniDockTile(iconSize, Color(0xFF0284C7), ChromeGlyph.SEARCH, showDot = false)
                    }
                    if (config.showRunningApps) {
                        MiniDockTile(iconSize, Color(0xFF30D158), ChromeGlyph.HOME_DESKTOP, showDot = config.showRunningDots)
                        MiniDockTile(iconSize, Color(0xFFFF9F0A), ChromeGlyph.EXPOSE, showDot = config.showRunningDots)
                        MiniDockTile(iconSize, Color(0xFFFF375F), ChromeGlyph.MIC, showDot = false)
                    }
                    if (config.showSettingsButton) {
                        MiniDockTile(iconSize, Color(0xFF64748B), ChromeGlyph.CONTROL_CENTER, showDot = config.showRunningDots)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniDockTile(size: androidx.compose.ui.unit.Dp, accent: Color, glyph: ChromeGlyph, showDot: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.30f)).background(accent.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center,
        ) { OpalGlyphIcon(glyph = glyph, tint = Color.White, size = size * 0.50f) }
        if (showDot) {
            Box(modifier = Modifier.size(3.5.dp).clip(CircleShape).background(OpalPalette.TextPrimary.copy(alpha = 0.75f)))
        }
    }
}

@Composable
internal fun DexDisplayCastSettingsSection(bridge: BridgeClient, isZh: Boolean) {
    val ctx = LocalContext.current
    val status by bridge.statusFlow.collectAsState()
    val remoteStatus by bridge.remoteStatusFlow.collectAsState()
    val profileState by ProfileConfigStore.stateFlow.collectAsState()
    val externalUiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val projectionMode by AppPrefs.externalProjectionModeFlow.collectAsState()
    val native1To1 by AppPrefs.externalNative1To1Flow.collectAsState()
    val autoCustomRes by AppPrefs.autoCustomResolutionEnabledFlow.collectAsState()
    val autoBlackout by AppPrefs.autoBlackoutOnCastFlow.collectAsState()
    val autoTrackpad by AppPrefs.autoShowPhoneTrackpadFlow.collectAsState()
    val autoEnterDex by AppPrefs.autoEnterDexFlow.collectAsState()
    val castBitrateKbps by AppPrefs.castBitrateKbpsFlow.collectAsState()

    val curFps = if (status.connected) status.targetFps else profileState.workingConfig.targetFps
    val curDpiPct = if (status.connected) status.windowDpiScalePct else profileState.workingConfig.windowDpiScalePct
    val curGlobalDpi = if (status.connected) status.globalCustomDpi else profileState.workingConfig.globalCustomDpi

    // 1. Clarity, DPI & Frame Rate
    DexSettingsGroupCard(
        title = if (isZh) "分辨率、DPI 与帧率" else "Clarity, DPI & Refresh Rate",
        subtitle = if (isZh) "调节虚拟画布帧率、窗口 DPI 缩放与超采样清晰度" else "Tune target FPS, window DPI scaling and rendering clarity",
    ) {
        OpalText(text = if (isZh) "目标帧率 (FPS)" else "Target FPS", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        val fpsValues = listOf(0, 60, 90, 120)
        val fpsIdx = fpsValues.indexOf(curFps).let { if (it >= 0) it else 0 }
        OpalSegmentedRow(
            options = listOf(if (isZh) "自动" else "Auto", "60Hz", "90Hz", "120Hz"),
            selectedIndex = fpsIdx,
            onSelect = { idx -> bridge.updateDisplayPrefsAsync(fpsValues[idx], curDpiPct, status.windowSupersampleMode) },
        )

        OpalText(text = if (isZh) "窗口内容 DPI 缩放" else "Window DPI Scale", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        val dpiScaleValues = listOf(85, 100, 115, 130)
        val dpiScaleIdx = dpiScaleValues.indexOf(curDpiPct).let { if (it >= 0) it else 1 }
        OpalSegmentedRow(
            options = dpiScaleValues.map { "$it%" },
            selectedIndex = dpiScaleIdx,
            onSelect = { idx -> bridge.updateDisplayPrefsAsync(curFps, dpiScaleValues[idx], status.windowSupersampleMode) },
        )

        OpalText(text = if (isZh) "全局自定义 DPI" else "Global Custom DPI", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        val customDpiPresets = listOf(0, 160, 200, 240, 280)
        val customDpiIdx = customDpiPresets.indexOf(curGlobalDpi).let { if (it >= 0) it else 0 }
        OpalSegmentedRow(
            options = customDpiPresets.map { if (it == 0) (if (isZh) "自动" else "Auto") else "$it" },
            selectedIndex = customDpiIdx,
            onSelect = { idx -> bridge.setGlobalCustomDpiAsync(ctx, customDpiPresets[idx]) },
        )

        OpalText(text = if (isZh) "窗口渲染清晰度" else "Window Clarity Mode", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        val ssIdx = status.windowSupersampleMode.coerceIn(BridgeProtocol.WindowSupersample.COMPAT, BridgeProtocol.WindowSupersample.NATIVE_1X)
        OpalSegmentedRow(
            options = if (isZh) listOf("兼容优先", "均衡清晰", "原生 1:1") else listOf("Compat", "Balanced", "Native 1x"),
            selectedIndex = ssIdx,
            onSelect = { idx -> bridge.updateDisplayPrefsAsync(curFps, curDpiPct, idx) },
        )

        DexSettingsDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = if (isZh) "外接桌面界面缩放" else "External UI Scale", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OpalStatusBadge(label = "${(externalUiScale * 100f).roundToInt()}%", color = OpalPalette.AccentCyan)
        }
        OpalGlassSlider(
            value = externalUiScale.coerceIn(0.85f, 1.50f),
            valueRange = 0.85f..1.50f,
            onValueChange = { AppPrefs.setExternalUiScale(ctx, it) },
            modifier = Modifier.fillMaxWidth(),
        )
        OpalSegmentedRow(
            options = if (isZh) listOf("外接屏自动模式", "独立演示模式") else listOf("Auto External", "Presentation Mode"),
            selectedIndex = projectionMode.coerceIn(AppPrefs.PROJECTION_MODE_AUTO, AppPrefs.PROJECTION_MODE_PRESENTATION),
            onSelect = { AppPrefs.setExternalProjectionMode(ctx, it) },
        )
        OpalSwitchRow(
            title = if (isZh) "外接屏原生 1:1 点对点输出" else "External Native 1:1 Output",
            subtitle = if (isZh) "减少缩放模糊，适配 2K/4K 便携屏与显示器" else "Crisper pixel output on 2K/4K external displays",
            checked = native1To1,
            onCheckedChange = { AppPrefs.setExternalNative1To1Enabled(ctx, it) },
        )
        OpalSwitchRow(
            title = if (isZh) "自动应用自定义分辨率" else "Auto-apply Custom Resolution",
            subtitle = if (isZh) "连接外接显示器时自动套用预设分辨率" else "Automatically apply saved resolution preset on connect",
            checked = autoCustomRes,
            onCheckedChange = { AppPrefs.setAutoCustomResolutionEnabled(ctx, it) },
        )
    }

    // 2. Wireless Cast & Host Display Automation
    DexSettingsGroupCard(
        title = if (isZh) "无线投屏与主机联动" else "Wireless Casting & Host Behavior",
        subtitle = if (isZh) "管理 Wi-Fi 投屏开关、默认码率、自动息屏与触控板" else "Manage wireless cast server, bitrate, auto blackout and trackpad",
    ) {
        OpalSwitchRow(
            title = if (isZh) "开启无线投屏服务 (Wi-Fi TLS)" else "Enable Wireless Cast (Wi-Fi TLS)",
            subtitle = if (remoteStatus.wifiEnabled && remoteStatus.lanIp.isNotBlank()) {
                "${remoteStatus.lanIp}:${remoteStatus.wifiPort}"
            } else {
                if (isZh) "允许局域网内电脑、平板或接收端加密连接本机 DeX" else "Allow encrypted LAN connections from PC, Mac or tablet"
            },
            checked = remoteStatus.wifiEnabled,
            onCheckedChange = { enabled ->
                bridge.setWifiStreamEnabledAsync(enabled = enabled, port = 19878, bindScope = BridgeProtocol.TlsBindScope.ALL)
            },
        )
        OpalText(
            text = if (isZh) "默认投屏码率" else "Default Cast Bitrate",
            fontSize = 12.5.sp,
            color = OpalPalette.TextSecondary,
        )
        val bitratePresets = AppPrefs.CAST_BITRATE_PRESETS_KBPS.toList()
        val bitrateIdx = bitratePresets.indexOf(castBitrateKbps).let { if (it >= 0) it else 0 }
        OpalSegmentedRow(
            options = bitratePresets.map { AppPrefs.formatCastBitrateLabel(it) },
            selectedIndex = bitrateIdx,
            onSelect = { idx -> AppPrefs.setCastBitrateKbps(ctx, bitratePresets[idx]) },
        )
        DexSettingsDivider()
        OpalSwitchRow(
            title = if (isZh) "外接/投屏时自动息屏手机" else "Auto screen-off phone during cast",
            subtitle = if (isZh) "外接显示器或无线投屏建立后自动关闭手机屏幕省电" else "Turn off host screen automatically while casting",
            checked = autoBlackout,
            onCheckedChange = { AppPrefs.setAutoBlackoutOnCast(ctx, it) },
        )
        if (status.connected) {
            DexSettingsActionRow(
                title = if (isZh) "手机主屏电源" else "Host Screen Power",
                subtitle = if (status.hostScreenOff) {
                    if (isZh) "当前手机屏幕已息屏（按音量键可随时唤醒）" else "Host screen is currently off (press volume key to wake)"
                } else {
                    if (isZh) "当前手机屏幕处于亮屏状态" else "Host screen is currently on"
                },
                actionLabel = if (status.hostScreenOff) (if (isZh) "点亮屏幕" else "Wake Screen") else (if (isZh) "立即息屏" else "Screen Off"),
                accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentAmber,
                onClick = { bridge.setHostScreenPowerAsync(screenOn = status.hostScreenOff) },
            )
        }
        OpalSwitchRow(
            title = if (isZh) "外接时自动打开手机触控板" else "Auto-open Phone Trackpad",
            subtitle = if (isZh) "接入外接显示器后在手机屏幕自动弹出全屏触控板" else "Launch full-screen touch trackpad on host when connected",
            checked = autoTrackpad,
            onCheckedChange = { AppPrefs.setAutoShowPhoneTrackpadEnabled(ctx, it) },
        )
        OpalSwitchRow(
            title = if (isZh) "连接外接屏自动进入 DeX 桌面" else "Auto-enter DeX on External Display",
            subtitle = if (isZh) "插入 USB-C/HDMI 显示器后无需手动启动" else "Launch DeX automatically when a monitor is plugged in",
            checked = autoEnterDex,
            onCheckedChange = { AppPrefs.setAutoEnterDexEnabled(ctx, it) },
        )
    }
}
