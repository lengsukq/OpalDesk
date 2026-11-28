package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
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
                title = stringResource(R.string.display_smart_adapt_title),
                subtitle = stringResource(R.string.display_smart_adapt_sub),
                badgeText = stringResource(R.string.common_recommended),
                accent = OpalPalette.AccentBlue,
                active = true,
                iconKind = 0,
                onClick = {
                    AppPrefs.setExternalUiScale(ctx, 1.25f)
                    AppPrefs.setAutoCustomResolutionEnabled(ctx, false)
                    AppPrefs.setExternalNative1To1Enabled(ctx, false)
                    AppPrefs.setTrackpadSensitivity(ctx, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    BridgeClient.setPointerSpeedAsync(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    BridgeClient.updateDisplayPrefsAsync(targetFps = 0, windowDpiScalePct = 100)
                    if (extDisplay != null) {
                        BridgeClient.setExternalDisplayModeAsync(extDisplay.displayId, 0, 0, 0f)
                    } else if (remote.connected) {
                        BridgeClient.setExternalDisplayModeAsync(-1, 0, 0, 0f)
                    }
                    Toast.makeText(ctx, ctx.getString(R.string.display_smart_adapt_toast), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            )
            DashboardBentoTile(
                title = stringResource(if (status.hostScreenOff) R.string.dash_hero_screen_on else R.string.display_screen_off_title),
                subtitle = stringResource(if (status.hostScreenOff) R.string.display_screen_off_sub_on else R.string.display_screen_off_sub_off),
                badgeText = stringResource(if (status.hostScreenOff) R.string.display_screen_off_badge_on else R.string.display_screen_off_badge_off),
                accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentCyan,
                active = status.hostScreenOff,
                iconKind = 1,
                onClick = {
                    if (status.connected) {
                        toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff)
                    } else {
                        Toast.makeText(ctx, ctx.getString(R.string.toast_activate_engine_first), Toast.LENGTH_SHORT).show()
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
                OpalText(text = stringResource(R.string.display_appearance_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                OpalText(
                    text = when (themeMode) {
                        AppPrefs.THEME_DARK -> stringResource(R.string.display_theme_dark_glass)
                        AppPrefs.THEME_SYSTEM -> stringResource(R.string.common_follow_system)
                        else -> stringResource(R.string.display_theme_light_glass)
                    },
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.height(9.dp))
            OpalSegmentedRow(
                options = listOf(
                    stringResource(R.string.common_light),
                    stringResource(R.string.common_follow_system),
                    stringResource(R.string.common_dark),
                ),
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
                OpalText(text = stringResource(R.string.display_fps_scale_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                val fpsLabel = if (status.targetFps > 0) "${status.targetFps}Hz" else stringResource(R.string.display_fps_auto)
                OpalStatusBadge(
                    label = "$fpsLabel · ${(extUiScale * 100f).roundToInt()}%",
                    color = OpalPalette.AccentBlue,
                )
            }
            Spacer(Modifier.height(10.dp))

            OpalText(text = stringResource(R.string.display_target_fps), fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(5.dp))
            val fpsOptions = listOf(0, 60, 90, 100, 120)
            val fpsIdx = fpsOptions.indexOf(status.targetFps).let { if (it >= 0) it else 0 }
            OpalSegmentedRow(
                options = listOf(stringResource(R.string.common_auto), "60Hz", "90Hz", "100Hz", "120Hz"),
                selectedIndex = fpsIdx,
                onSelect = { idx ->
                    BridgeClient.updateDisplayPrefsAsync(
                        targetFps = fpsOptions[idx],
                        windowDpiScalePct = status.windowDpiScalePct,
                    )
                },
            )

            Spacer(Modifier.height(12.dp))
            OpalText(text = stringResource(R.string.display_ui_scale_title), fontSize = 12.sp, color = OpalPalette.TextSecondary)
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
            OpalText(text = stringResource(R.string.display_supersample_title), fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(3.dp))
            val supersampleHint = when (status.windowSupersampleMode) {
                BridgeProtocol.WindowSupersample.BALANCED ->
                    stringResource(R.string.display_supersample_balanced_hint)
                BridgeProtocol.WindowSupersample.NATIVE_1X ->
                    stringResource(R.string.display_supersample_native_hint)
                else ->
                    stringResource(R.string.display_supersample_compat_hint)
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
                options = listOf(
                    stringResource(R.string.display_supersample_compat),
                    stringResource(R.string.display_supersample_balanced),
                    stringResource(R.string.display_supersample_native),
                ),
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
                title = stringResource(R.string.display_auto_blackout_title),
                subtitle = stringResource(R.string.display_auto_blackout_sub),
                checked = autoBlackoutOnCast,
                onCheckedChange = { enabled ->
                    AppPrefs.setAutoBlackoutOnCast(ctx, enabled)
                },
            )
        }

        ConsoleSectionTitle(stringResource(R.string.display_detail_section))

        // 3. Collapsible: External Display & Wireless TV
        ConsoleExternalDisplayCard(
            status = status,
            extDisplay = extDisplay,
            detectedExtDisplays = detectedExtDisplays,
            autoExtDisplay = autoExtDisplay,
            autoShowPhoneTrackpad = autoShowPhoneTrackpad,
            projectionMode = projectionMode,
            overscanPct = overscanPct,
            navController = navController,
            onAutoExtDisplayChange = {
                autoExtDisplay = it
                AppPrefs.setAutoExternalDisplayEnabled(ctx, it)
            },
            onProjectSpecificDisplay = onProjectSpecificDisplay,
        )

        // 4. Collapsible: Custom Resolution & Refresh Rate
        val resolutionSummary = when {
            extDisplay != null -> formatCompactResolutionBadge(extDisplay.width, extDisplay.height, extDisplay.refreshRateHz)
            remote.connected -> formatCompactResolutionBadge(
                remote.width,
                remote.height,
                (remote.fpsX100 / 100f).roundToInt().coerceAtLeast(24),
            )
            autoCustomResEnabled -> formatCompactResolutionBadge(defaultCustomRes.width, defaultCustomRes.height, defaultCustomRes.fps)
            else -> stringResource(R.string.display_res_adaptive_edid)
        }
        OpalCollapsibleSectionCard(
            title = stringResource(R.string.display_res_section_title),
            subtitle = stringResource(R.string.display_res_section_sub),
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
        ConsolePerAppDpiCard(
            workingProfile = workingProfile,
            navController = navController,
        )

        // 6. Collapsible: Peripherals, Pointer Speed & Desktop Wallpaper
        ConsolePeripheralsWallpaperCard(
            status = status,
            extDisplay = extDisplay,
            mouseTarget = mouseTarget,
            trackpadSens = trackpadSens,
            wallpaperPreset = wallpaperPreset,
            customWallpaperPath = customWallpaperPath,
            isDark = isDark,
            navController = navController,
            onPickCustomWallpaper = onPickCustomWallpaper,
        )

        // 7. Collapsible: Keyboard Shortcuts
        val activeShortcutCount = WorkstationShortcutCatalog.countActiveGlobalShortcuts(
            globalShortcutsEnabled,
            disabledShortcutIds,
        )
        val shortcutsSummary = if (!globalShortcutsEnabled || activeShortcutCount == 0) {
            stringResource(R.string.common_disabled)
        } else {
            stringResource(R.string.display_shortcuts_active_count, activeShortcutCount)
        }
        OpalCollapsibleSectionCard(
            title = stringResource(R.string.display_shortcuts_title),
            subtitle = stringResource(R.string.display_shortcuts_sub),
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
            yuyanActive -> stringResource(R.string.display_ime_yuyan_badge)
            dexLockedImeId.isNotBlank() -> DexImeCatalog.fallbackLabelForImeId(dexLockedImeId).substringBefore(" (").take(10)
            else -> DexImeCatalog.displayModeTitle(dexImeDisplayMode)
        }
        OpalCollapsibleSectionCard(
            title = stringResource(R.string.display_ime_title),
            subtitle = stringResource(R.string.display_ime_sub),
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
