package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.compositor.AppCatalogRepository
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tab 1: Display & Peripherals — keeps Appearance and Quick FPS/Scale cards compact and expanded
 * by default while tucking heavy configuration panels behind Liquid Glass [OpalCollapsibleSectionCard]s
 * with live summary pills for fast visual scanning and Spotlight jump-to-expand.
 */
@Composable
fun ConsoleDisplaySettingsTab(
    status: DaemonStatus,
    remote: RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    detectedExtDisplays: List<BridgeService.ExternalDisplayInfo> = emptyList(),
    onProjectSpecificDisplay: (Int, ExternalDisplayTransport, Boolean) -> Unit = { _, _, _ -> },
    onPickCustomWallpaper: () -> Unit,
    navController: ConsoleNavigationController? = null,
) {
    val ctx = LocalContext.current
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val extUiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val trackpadSens by AppPrefs.trackpadSensitivityFlow.collectAsState()
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    val mouseTarget by AppPrefs.mouseTargetFlow.collectAsState()
    val projectionMode by AppPrefs.externalProjectionModeFlow.collectAsState()
    val overscanPct by AppPrefs.externalOverscanPctFlow.collectAsState()
    var autoExtDisplay by remember { mutableStateOf(AppPrefs.isAutoExternalDisplayEnabled(ctx)) }
    val autoShowPhoneTrackpad by AppPrefs.autoShowPhoneTrackpadFlow.collectAsState()
    val autoBlackoutOnCast by AppPrefs.autoBlackoutOnCastFlow.collectAsState()
    val autoCustomResEnabled by AppPrefs.autoCustomResolutionEnabledFlow.collectAsState()
    val defaultCustomRes by AppPrefs.defaultCustomResolutionFlow.collectAsState()
    val globalShortcutsEnabled by AppPrefs.globalShortcutsEnabledFlow.collectAsState()
    val disabledShortcutIds by AppPrefs.disabledShortcutIdsFlow.collectAsState()
    val dexImeDisplayMode by AppPrefs.dexImeDisplayModeFlow.collectAsState()
    val dexLockedImeId by AppPrefs.dexLockedImeIdFlow.collectAsState()
    val autoBridgeImeOnCast by AppPrefs.autoBridgeImeOnCastFlow.collectAsState()
    val imeState by BridgeClient.imeStateFlow.collectAsState()
    val profileState by ProfileConfigStore.stateFlow.collectAsState()
    val workingProfile = profileState.workingConfig
    val isDark = OpalPalette.isDark

    LaunchedEffect(Unit) {
        ProfileConfigStore.hydrate(ctx)
        AppPrefs.hydrateFlows(ctx)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Top 2-Column Bento Quick Tiles (Smart Auto-Adapt + Host Physical Screen-Off)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardBentoTile(
                title = "一键智能适配",
                subtitle = "恢复最佳分辨率、高刷与 DPI",
                badgeText = "推荐",
                accent = OpalPalette.AccentBlue,
                active = true,
                iconKind = 0,
                onClick = {
                    AppPrefs.setExternalUiScale(ctx, 1.25f)
                    AppPrefs.setAutoCustomResolutionEnabled(ctx, false)
                    AppPrefs.setTrackpadSensitivity(ctx, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    BridgeClient.setPointerSpeedAsync(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    BridgeClient.updateDisplayPrefsAsync(targetFps = 0, windowDpiScalePct = 100)
                    if (extDisplay != null) {
                        BridgeClient.setExternalDisplayModeAsync(extDisplay.displayId, 0, 0, 0f)
                    } else if (remote.connected) {
                        BridgeClient.setExternalDisplayModeAsync(-1, 0, 0, 0f)
                    }
                    Toast.makeText(ctx, "已恢复全自动最佳分辨率、帧率与 DPI 配置", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            )
            DashboardBentoTile(
                title = if (status.hostScreenOff) "点亮本机屏幕" else "本机物理息屏",
                subtitle = if (status.hostScreenOff) "省电运行中 · 点按亮屏" else "外接/投屏时熄屏省电",
                badgeText = if (status.hostScreenOff) "已息屏" else "亮屏",
                accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentCyan,
                active = status.hostScreenOff,
                iconKind = 1,
                onClick = {
                    if (status.connected) {
                        toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff)
                    } else {
                        Toast.makeText(ctx, "请先激活多窗引擎", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        // 1. Expanded by default: Appearance Style (Light / Follow System / Dark)
        MacGlassSectionCard(
            modifier = Modifier.consoleSearchAnchor(navController, ConsoleSectionKeys.APPEARANCE),
            contentPadding = PaddingValues(15.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "液态毛玻璃外观", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                OpalText(
                    text = when (themeMode) {
                        AppPrefs.THEME_DARK -> "深色玻璃"
                        AppPrefs.THEME_SYSTEM -> "跟随系统"
                        else -> "浅色玻璃"
                    },
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.height(9.dp))
            OpalSegmentedRow(
                options = listOf("浅色", "跟随系统", "深色"),
                selectedIndex = themeMode.coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK),
                onSelect = { AppPrefs.setThemeMode(ctx, it) },
            )
        }

        // 2. Expanded by default: Quick FPS, Output UI Scale & Supersample Summary
        MacGlassSectionCard(
            modifier = Modifier.consoleSearchAnchor(navController, ConsoleSectionKeys.FPS_DPI),
            contentPadding = PaddingValues(15.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "帧率、缩放与画质", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                val fpsLabel = if (status.targetFps > 0) "${status.targetFps}Hz" else "自动帧率"
                OpalStatusBadge(
                    label = "$fpsLabel · ${(extUiScale * 100f).roundToInt()}%",
                    color = OpalPalette.AccentBlue,
                )
            }
            Spacer(Modifier.height(10.dp))

            OpalText(text = "目标帧率", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(5.dp))
            val fpsOptions = listOf(0, 60, 90, 100, 120)
            val fpsIdx = fpsOptions.indexOf(status.targetFps).let { if (it >= 0) it else 0 }
            OpalSegmentedRow(
                options = listOf("自动", "60Hz", "90Hz", "100Hz", "120Hz"),
                selectedIndex = fpsIdx,
                onSelect = { idx ->
                    BridgeClient.updateDisplayPrefsAsync(
                        targetFps = fpsOptions[idx],
                        windowDpiScalePct = status.windowDpiScalePct,
                    )
                },
            )

            Spacer(Modifier.height(12.dp))
            OpalText(text = "桌面界面缩放（175% / 200% 适合客厅电视远看）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(5.dp))
            val scaleOpts = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            val scaleIdx = scaleOpts.indexOfFirst { abs(it - extUiScale) < 0.08f }.let { if (it >= 0) it else 1 }
            OpalSegmentedRow(
                options = listOf("100%", "125%", "150%", "175% TV", "200% TV"),
                selectedIndex = scaleIdx,
                onSelect = { idx ->
                    AppPrefs.setExternalUiScale(ctx, scaleOpts[idx])
                },
            )

            Spacer(Modifier.height(12.dp))
            OpalText(text = "窗口渲染超采样（清晰度 / 发热权衡）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(3.dp))
            val supersampleHint = when (status.windowSupersampleMode) {
                BridgeProtocol.WindowSupersample.BALANCED ->
                    "适度超采（上限 1.45× / 1080p）：降低约 40% GPU 发热并保持常规应用清晰"
                BridgeProtocol.WindowSupersample.NATIVE_1X ->
                    "1:1 物理像素点对点渲染：文字最锐利、发热最低（若微信等应用字体偏大请切回兼容超采）"
                else ->
                    "完整向手机高 DPI 超采：最大化兼容微信等固定密度应用，多窗 GPU 负载较高"
            }
            OpalText(text = supersampleHint, fontSize = 11.sp, color = OpalPalette.TextMuted)
            Spacer(Modifier.height(5.dp))
            val supersampleModes = listOf(
                BridgeProtocol.WindowSupersample.COMPAT,
                BridgeProtocol.WindowSupersample.BALANCED,
                BridgeProtocol.WindowSupersample.NATIVE_1X,
            )
            val supersampleIdx = supersampleModes.indexOf(status.windowSupersampleMode).let { if (it >= 0) it else 0 }
            OpalSegmentedRow(
                options = listOf("兼容超采", "均衡省电", "1:1 原生"),
                selectedIndex = supersampleIdx,
                onSelect = { idx ->
                    BridgeClient.updateDisplayPrefsAsync(
                        targetFps = status.targetFps,
                        windowDpiScalePct = status.windowDpiScalePct,
                        windowSupersampleMode = supersampleModes[idx],
                    )
                },
            )

            Spacer(Modifier.height(10.dp))
            OpalSwitchRow(
                title = "投屏 / 外接屏自动息屏省电",
                subtitle = "进入外接显示器或远程投屏 3 秒后自动熄灭本机屏幕以省电降温",
                checked = autoBlackoutOnCast,
                onCheckedChange = { enabled ->
                    AppPrefs.setAutoBlackoutOnCast(ctx, enabled)
                },
            )
        }

        ConsoleSectionTitle("详细显示与外设配置")

        // 3. Collapsible: External Display & Wireless TV
        val extSummaryText = when {
            extDisplay != null -> formatCompactResolutionBadge(extDisplay.width, extDisplay.height, extDisplay.refreshRateHz)
            autoExtDisplay -> if (overscanPct > 0.1f) "自动满屏 · 边距 ${overscanPct}%" else "自动满屏"
            else -> "手动投射"
        }
        OpalCollapsibleSectionCard(
            title = "外接屏与无线投电视",
            subtitle = "自动满屏、手机触控板、副屏投射模式与电视安全边距",
            sectionKey = ConsoleSectionKeys.EXTERNAL_DISPLAY,
            controller = navController,
            summaryText = extSummaryText,
            summaryAccent = if (extDisplay != null) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
            defaultExpanded = false,
        ) {
            OpalSwitchRow(
                title = "接入时自动满屏工作站",
                subtitle = "消除镜像黑边，按外接屏 / 无线电视原生分辨率输出",
                checked = autoExtDisplay,
                onCheckedChange = {
                    autoExtDisplay = it
                    AppPrefs.setAutoExternalDisplayEnabled(ctx, it)
                },
            )
            OpalSwitchRow(
                title = "接入外接屏时自动显示手机触控板",
                subtitle = "连上外接屏启动工作站时自动在手机拉起触控板",
                checked = autoShowPhoneTrackpad,
                onCheckedChange = { enabled ->
                    AppPrefs.setAutoShowPhoneTrackpadEnabled(ctx, enabled)
                },
            )

            Spacer(Modifier.height(10.dp))
            OpalText(text = "副屏投射模式", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = "自动模式下有线屏优先直投、无线电视优先标准 AOSP Presentation（规避黑屏并自动启用手机触控板）",
                fontSize = 11.sp,
                color = OpalPalette.TextMuted,
                lineHeight = 15.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            OpalSegmentedRow(
                options = listOf("自动 (推荐)", "强制 Presentation"),
                selectedIndex = projectionMode.coerceIn(AppPrefs.PROJECTION_MODE_AUTO, AppPrefs.PROJECTION_MODE_PRESENTATION),
                onSelect = { mode -> AppPrefs.setExternalProjectionMode(ctx, mode) },
            )

            Spacer(Modifier.height(12.dp))
            OpalText(text = "电视防裁边安全边距（Overscan）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = "若电视过扫描裁切了顶栏或程序坞，可实时增加纯黑安全留白",
                fontSize = 11.sp,
                color = OpalPalette.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            val overscanOpts = listOf(0f, 2f, 3.5f, 5f)
            val overscanIdx = overscanOpts.indexOfFirst { abs(it - overscanPct) < 0.25f }.let { if (it >= 0) it else 0 }
            OpalSegmentedRow(
                options = listOf("0% 关闭", "2%", "3.5%", "5%"),
                selectedIndex = overscanIdx,
                onSelect = { idx -> AppPrefs.setExternalOverscanPct(ctx, overscanOpts[idx]) },
            )

            Spacer(Modifier.height(12.dp))
            OpalButton(
                label = "打开系统无线投屏（Miracast）",
                filled = false,
                compact = true,
                accent = OpalPalette.AccentBlue,
                onClick = { WirelessDisplayHelper.openSystemWirelessCastSettings(ctx) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (detectedExtDisplays.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    detectedExtDisplays.forEach { d ->
                        val tag = WirelessDisplayHelper.formatTransportLabel(d.transport)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(OpalPalette.ChipFill)
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                OpalText(
                                    text = "${d.name} ($tag)",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                OpalText(
                                    text = "#${d.displayId} · ${d.width}×${d.height} @ ${d.refreshRateHz}Hz",
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = OpalPalette.TextMuted,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OpalButton(
                                label = "投射",
                                compact = true,
                                enabled = status.connected,
                                onClick = {
                                    onProjectSpecificDisplay(
                                        d.displayId,
                                        d.transport,
                                        ExternalDisplayPolicy.shouldForcePresentationForManualLaunch(
                                            d.transport,
                                            d.isPresentationCapable,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // 4. Collapsible: Custom Resolution & Refresh Rate
        val resolutionSummary = when {
            extDisplay != null -> formatCompactResolutionBadge(extDisplay.width, extDisplay.height, extDisplay.refreshRateHz)
            remote.connected -> formatCompactResolutionBadge(
                remote.width,
                remote.height,
                (remote.fpsX100 / 100f).roundToInt().coerceAtLeast(24),
            )
            autoCustomResEnabled -> formatCompactResolutionBadge(defaultCustomRes.width, defaultCustomRes.height, defaultCustomRes.fps)
            else -> "自适应 EDID"
        }
        OpalCollapsibleSectionCard(
            title = "自定义分辨率与刷新率",
            subtitle = "EDID 原生模式、2K/4K/带鱼屏预设与 15 秒防黑屏回退",
            sectionKey = ConsoleSectionKeys.CUSTOM_RESOLUTION,
            controller = navController,
            summaryText = resolutionSummary,
            summaryAccent = if (extDisplay != null || remote.connected || autoCustomResEnabled) {
                OpalPalette.AccentCyan
            } else {
                OpalPalette.TextSecondary
            },
            defaultExpanded = false,
        ) {
            OutputResolutionSectionContent(
                extDisplay = extDisplay,
                remote = remote,
            )
        }

        // 5. Collapsible: Per-App & Global Custom DPI
        val customDpiCount = workingProfile.appCustomDpis.size
        val perAppDpiSummary = when {
            customDpiCount > 0 -> "自定义 $customDpiCount 项"
            workingProfile.globalCustomDpi > 0 -> "全局 ${workingProfile.globalCustomDpi}"
            else -> "自动适配"
        }
        OpalCollapsibleSectionCard(
            title = "单 App 独立 DPI",
            subtitle = "自定义全局窗口密度或管理已配置的单个应用独立 DPI",
            sectionKey = ConsoleSectionKeys.PER_APP_DPI,
            controller = navController,
            summaryText = perAppDpiSummary,
            summaryAccent = if (customDpiCount > 0 || workingProfile.globalCustomDpi > 0) {
                OpalPalette.AccentBlue
            } else {
                OpalPalette.TextSecondary
            },
            defaultExpanded = false,
        ) {
            val isCustomGlobal = workingProfile.globalCustomDpi > 0
            var draftGlobalDpi by remember(workingProfile.globalCustomDpi) {
                mutableIntStateOf(workingProfile.globalCustomDpi.coerceAtLeast(ProfileConfigStore.MIN_DPI))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "全局窗口 DPI", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                OpalText(
                    text = if (isCustomGlobal) "$draftGlobalDpi DPI" else "自动适配",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.AccentBlue,
                )
            }
            Spacer(Modifier.height(6.dp))
            OpalSegmentedRow(
                options = listOf("自动适配", "自定义全局 DPI"),
                selectedIndex = if (isCustomGlobal) 1 else 0,
                onSelect = { idx ->
                    if (idx == 0) {
                        BridgeClient.setGlobalCustomDpiAsync(ctx, 0)
                    } else {
                        val initDpi = if (workingProfile.globalCustomDpi > 0) workingProfile.globalCustomDpi else 240
                        draftGlobalDpi = initDpi
                        BridgeClient.setGlobalCustomDpiAsync(ctx, initDpi)
                    }
                },
            )
            if (isCustomGlobal) {
                Spacer(Modifier.height(8.dp))
                OpalGlassSlider(
                    value = draftGlobalDpi.toFloat(),
                    valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                    onValueChange = { v ->
                        draftGlobalDpi = ((v / 10f).roundToInt() * 10)
                            .coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                    },
                    onValueChangeFinished = {
                        if (draftGlobalDpi != workingProfile.globalCustomDpi) {
                            BridgeClient.setGlobalCustomDpiAsync(ctx, draftGlobalDpi)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

            Spacer(Modifier.height(12.dp))
            if (workingProfile.appCustomDpis.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(OpalPalette.ChipFill)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    OpalText(
                        text = "尚未单独设置应用 DPI。进入 DeX 桌面后，可点击顶栏 DPI 按钮或长按应用图标实时可视化调节单 App 独立 DPI。",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                        lineHeight = 16.sp,
                    )
                }
            } else {
                OpalText(
                    text = "已配置的独立应用 DPI (${workingProfile.appCustomDpis.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    workingProfile.appCustomDpis.entries.sortedBy { it.key }.forEach { (pkg, dpi) ->
                        val appLabel = remember(pkg) {
                            runCatching {
                                val pm = ctx.packageManager
                                val info = pm.getApplicationInfo(pkg, 0)
                                pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
                            }.getOrNull() ?: pkg
                        }
                        val icon = remember(pkg) { AppCatalogRepository.getAppIcon(ctx, pkg) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(OpalPalette.ChipFill)
                                .border(0.7.dp, OpalPalette.BorderHairline, RoundedCornerShape(11.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = appLabel,
                                        modifier = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)),
                                    )
                                }
                                Column {
                                    OpalText(text = appLabel, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    OpalText(
                                        text = pkg,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OpalText(
                                    text = "$dpi DPI",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = OpalPalette.AccentBlue,
                                )
                                OpalButton(
                                    label = "重置",
                                    compact = true,
                                    filled = false,
                                    accent = OpalPalette.AccentDanger,
                                    onClick = {
                                        BridgeClient.setAppCustomDpiAsync(ctx, pkg, 0)
                                        Toast.makeText(ctx, "已恢复「$appLabel」跟随全局 DPI", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 6. Collapsible: Peripherals, Pointer Speed & Desktop Wallpaper
        val mouseShort = if (mouseTarget == AppPrefs.MOUSE_TARGET_EXTERNAL) "优先外接" else "跟随本机"
        val periSummary = if (customWallpaperPath.isNotBlank()) {
            "相册壁纸 · ${String.format(Locale.US, "%.2f×", trackpadSens)}"
        } else {
            "$mouseShort · ${String.format(Locale.US, "%.2f×", trackpadSens)}"
        }
        OpalCollapsibleSectionCard(
            title = "外接键鼠与桌面壁纸",
            subtitle = "键鼠输出目标、指针速度调节与预设 / 相册壁纸",
            sectionKey = ConsoleSectionKeys.PERIPHERALS_WALLPAPER,
            controller = navController,
            summaryText = periSummary,
            summaryAccent = if (status.boundMouseCount > 0) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
            defaultExpanded = false,
        ) {
            OpalText(
                text = "外接键鼠输出目标",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = when {
                    status.boundMouseCount > 0 ->
                        "已将 ${status.boundMouseCount} 个外接键鼠设备优先绑定至投屏/外接端（后台切出也生效）"
                    status.connectedMouseCount > 0 && mouseTarget == AppPrefs.MOUSE_TARGET_EXTERNAL ->
                        "已检测到 ${status.connectedMouseCount} 个外接键鼠设备；开启外接或投屏后自动接管至投屏端"
                    status.connectedMouseCount > 0 ->
                        "已检测到 ${status.connectedMouseCount} 个外接键鼠设备，当前作用于本机屏幕"
                    else ->
                        "连接蓝牙 / USB 外置键盘或鼠标后，投屏与外接屏时默认优先控制投屏端"
                },
                fontSize = 11.sp,
                color = if (status.boundMouseCount > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            OpalSegmentedRow(
                options = listOf("跟随本机屏幕", "优先投屏 / 外接端"),
                selectedIndex = mouseTarget.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL),
                onSelect = { nextMode ->
                    AppPrefs.setMouseTarget(ctx, nextMode)
                    val activeExtId = resolveActiveExternalDisplayId(extDisplay)
                    BridgeClient.setMouseTargetAsync(nextMode, activeExtId) { bound ->
                        val msg = when {
                            nextMode == AppPrefs.MOUSE_TARGET_LOCAL -> "外接键鼠已切回本机屏幕"
                            bound > 0 -> "已将外接键鼠优先路由至投屏/外接端"
                            else -> "已设为优先投屏端，接入外接键鼠或开始投屏后立即生效"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "指针移动速度（同步触控板与鼠标）",
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OpalText(
                    text = String.format(Locale.US, "%.2f× · 复位", trackpadSens),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.AccentBlue,
                    maxLines = 1,
                    modifier = Modifier.clickable {
                        AppPrefs.setTrackpadSensitivity(ctx, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                        BridgeClient.setPointerSpeedAsync(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            OpalGlassSlider(
                value = trackpadSens,
                valueRange = AppPrefs.MIN_TRACKPAD_SENSITIVITY..AppPrefs.MAX_TRACKPAD_SENSITIVITY,
                onValueChange = { next ->
                    AppPrefs.setTrackpadSensitivity(ctx, next)
                    BridgeClient.setPointerSpeedAsync(next)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "0.50× 精细", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
                OpalText(text = "1.45× 标准", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
                OpalText(text = "3.00× 疾速", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = if (customWallpaperPath.isNotBlank()) "桌面壁纸（已启用相册壁纸）" else "桌面壁纸（支持右键 / 长按更换）",
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (customWallpaperPath.isNotBlank()) {
                        OpalButton(
                            label = "恢复预设",
                            compact = true,
                            filled = false,
                            onClick = {
                                WallpaperStorage.clearCustomWallpaper(ctx)
                                Toast.makeText(ctx, "已恢复预设壁纸", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    OpalButton(
                        label = "从相册选壁纸",
                        compact = true,
                        onClick = onPickCustomWallpaper,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val themes = if (isDark) {
                listOf(
                    "红杉深海" to listOf(Color(0xFF142842), Color(0xFF2A9D8F)),
                    "索诺玛暮色" to listOf(Color(0xFF2B2752), Color(0xFFE07A5F)),
                    "极地冰川" to listOf(Color(0xFF0E293D), Color(0xFF38BDF8)),
                    "曜石暗夜" to listOf(Color(0xFF1C1F26), Color(0xFF4B5563)),
                )
            } else {
                listOf(
                    "晴空云雾" to listOf(Color(0xFFC8DEFB), Color(0xFF60A5FA)),
                    "晨曦薄暮" to listOf(Color(0xFFFBDCE8), Color(0xFFF472B6)),
                    "冰川清露" to listOf(Color(0xFFBFE7F4), Color(0xFF38BDF8)),
                    "素白霜华" to listOf(Color(0xFFE9EEF6), Color(0xFFCBD5E1)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                themes.forEachIndexed { idx, (name, grad) ->
                    val selected = customWallpaperPath.isBlank() && idx == wallpaperPreset
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                WallpaperStorage.clearCustomWallpaper(ctx)
                                AppPrefs.setWallpaperPreset(ctx, idx)
                            }
                            .padding(vertical = 3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(grad))
                                .border(
                                    width = if (selected) 1.8.dp else 0.8.dp,
                                    color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                    shape = RoundedCornerShape(10.dp),
                                ),
                        )
                        Spacer(Modifier.height(4.dp))
                        OpalText(
                            text = name,
                            fontSize = 11.sp,
                            color = if (selected) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // 7. Collapsible: Keyboard Shortcuts
        val activeShortcutCount = WorkstationShortcutCatalog.countActiveGlobalShortcuts(
            globalShortcutsEnabled,
            disabledShortcutIds,
        )
        val shortcutsSummary = if (!globalShortcutsEnabled || activeShortcutCount == 0) {
            "已关闭"
        } else {
            "已启 $activeShortcutCount 项"
        }
        OpalCollapsibleSectionCard(
            title = "键盘快捷键",
            subtitle = "Spotlight、Alt+Tab 切窗、分屏排版与输入法直通",
            sectionKey = ConsoleSectionKeys.KEYBOARD_SHORTCUTS,
            controller = navController,
            summaryText = shortcutsSummary,
            summaryAccent = if (globalShortcutsEnabled && activeShortcutCount > 0) {
                OpalPalette.AccentEmerald
            } else {
                OpalPalette.TextMuted
            },
            defaultExpanded = false,
        ) {
            ConsoleKeyboardShortcutsSectionContent()
        }

        // 8. Collapsible: DeX Input Method (IME)
        val yuyanActive = imeState.pluginImeActive || DexImeCatalog.isYuyanPluginEnabledForDex(
            pluginInstalled = imeState.pluginImeInstalled,
            lockedImeId = dexLockedImeId,
            autoBridgeOnCast = autoBridgeImeOnCast,
            displayMode = dexImeDisplayMode,
        )
        val imeSummary = when {
            yuyanActive -> "语燕输入法"
            dexLockedImeId.isNotBlank() -> DexImeCatalog.fallbackLabelForImeId(dexLockedImeId).substringBefore(" (").take(10)
            else -> DexImeCatalog.displayModeTitle(dexImeDisplayMode)
        }
        OpalCollapsibleSectionCard(
            title = "DeX 输入法",
            subtitle = "屏内显示位置、语燕插件与独立键盘锁定",
            sectionKey = ConsoleSectionKeys.DEX_IME,
            controller = navController,
            summaryText = imeSummary,
            summaryAccent = if (yuyanActive || dexLockedImeId.isNotBlank()) {
                OpalPalette.AccentEmerald
            } else {
                OpalPalette.AccentBlue
            },
            defaultExpanded = false,
        ) {
            ConsoleDexImeSectionContent()
        }
    }
}

internal fun formatCompactResolutionBadge(width: Int, height: Int, hz: Int): String {
    val label = when {
        (width == 3840 && height == 2160) || (width == 2160 && height == 3840) -> "4K"
        (width == 2560 && height == 1440) || (width == 1440 && height == 2560) -> "2K"
        (width == 1920 && height == 1080) || (width == 1080 && height == 1920) -> "1080p"
        (width == 1280 && height == 720) || (width == 720 && height == 1280) -> "720p"
        width > 0 && height > 0 -> "${width}×${height}"
        else -> "自适应"
    }
    return if (hz > 0) "$label · ${hz}Hz" else label
}
