package io.github.lengsukq.opaldesk.compositor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalSwitchRow
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.QuickSlotSwitcherStrip
import kotlin.math.abs

@Composable
internal fun ControlCenterWindowsTab(
    totalWindows: Int,
    canvasW: Int,
    canvasH: Int,
    showMoreTiling: Boolean,
    onToggleMoreTiling: () -> Unit,
    onApplyTilingLayout: (WindowTilingLayout) -> Unit,
    onMinimizeAll: () -> Unit,
    onCloseAll: () -> Unit,
    onOpenDpiSettings: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenKeyboardShortcuts: () -> Unit,
) {
    QuickSlotSwitcherStrip(
        compact = true,
        showRestoreButton = true,
        canvasW = canvasW,
        canvasH = canvasH,
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = stringResource(R.string.taskbar_cc_tiling_label),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextSecondary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(
                    text = stringResource(R.string.taskbar_cc_shortcuts),
                    fontSize = 10.5.sp,
                    color = OpalPalette.AccentBlue,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(OpalPalette.ChipActiveFill)
                        .clickable(onClick = onOpenKeyboardShortcuts)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                OpalText(
                    text = stringResource(if (showMoreTiling) R.string.common_collapse else R.string.taskbar_cc_more_tiling),
                    fontSize = 11.sp,
                    color = OpalPalette.TextSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleMoreTiling)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        val primaryTiles = listOf(
            WindowTilingLayout.SPLIT_2_COLS,
            WindowTilingLayout.SPLIT_2_MAIN_SIDE,
            WindowTilingLayout.SPLIT_3_COLS,
            WindowTilingLayout.SPLIT_4_GRID,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            primaryTiles.forEach { layout ->
                TilingPresetTile(
                    layout = layout,
                    enabled = totalWindows > 0,
                    onClick = { onApplyTilingLayout(layout) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showMoreTiling) {
            val extraTiles = listOf(
                WindowTilingLayout.SPLIT_3_MAIN_STACK,
                WindowTilingLayout.SPLIT_4_COLS,
                WindowTilingLayout.SPLIT_5_COLS,
                WindowTilingLayout.SPLIT_6_GRID,
                WindowTilingLayout.SPLIT_8_GRID,
                WindowTilingLayout.CASCADE,
            )
            extraTiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { layout ->
                        TilingPresetTile(
                            layout = layout,
                            enabled = totalWindows > 0,
                            onClick = { onApplyTilingLayout(layout) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpalButton(
            label = stringResource(R.string.taskbar_cc_minimize_all),
            compact = true,
            filled = false,
            enabled = totalWindows > 0,
            accent = OpalPalette.AccentBlue,
            onClick = onMinimizeAll,
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = stringResource(R.string.dash_hero_close_all_windows),
            compact = true,
            filled = false,
            enabled = totalWindows > 0,
            accent = OpalPalette.AccentAmber,
            onClick = onCloseAll,
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OpalButton(
            label = stringResource(R.string.taskbar_cc_window_dpi),
            compact = true,
            filled = false,
            accent = OpalPalette.AccentBlue,
            onClick = onOpenDpiSettings,
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = stringResource(R.string.taskbar_cc_ime),
            compact = true,
            filled = false,
            accent = OpalPalette.AccentEmerald,
            onClick = onOpenImeSettings,
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = stringResource(R.string.taskbar_cc_shortcuts),
            compact = true,
            filled = false,
            accent = OpalPalette.AccentCyan,
            onClick = onOpenKeyboardShortcuts,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ControlCenterDisplayTab(
    canvasW: Int,
    canvasH: Int,
    targetFps: Int,
    actualRefreshHz: Int,
    maxSupportedRefreshHz: Int,
    isRemoteCast: Boolean,
    externalUiScale: Float,
    themeMode: Int,
    wallpaperPreset: Int,
    mouseTargetMode: Int,
    isExternalMonitor: Boolean,
    hostScreenOff: Boolean,
    autoBlackoutOnCast: Boolean,
    showHostScreenToggle: Boolean,
    dockVisible: Boolean,
    dockOpacity: Float,
    dockScale: Float,
    infoCardShape: RoundedCornerShape,
    onSelectTargetFps: (Int) -> Unit,
    onOpenResolutionSettings: (() -> Unit)?,
    onToggleAutoBlackout: (Boolean) -> Unit,
    onToggleDockVisible: (Boolean) -> Unit,
    onChangeDockOpacity: (Float) -> Unit,
    onChangeDockScale: (Float) -> Unit,
    onResetDockStyle: () -> Unit,
    onSelectUiScale: (Float) -> Unit,
    onSelectMouseTarget: (Int) -> Unit,
    onSelectThemeMode: (Int) -> Unit,
    onSelectWallpaper: (Int) -> Unit,
    onPickCustomWallpaper: () -> Unit,
) {
    val isDark = OpalPalette.isDark
    val wallpaperColors = if (isDark) {
        listOf(
            Pair(stringResource(R.string.display_wp_dark_0), listOf(Color(0xFF142842), Color(0xFF2A9D8F))),
            Pair(stringResource(R.string.display_wp_dark_1), listOf(Color(0xFF2B2752), Color(0xFFE07A5F))),
            Pair(stringResource(R.string.display_wp_dark_2), listOf(Color(0xFF0E293D), Color(0xFF38BDF8))),
            Pair(stringResource(R.string.display_wp_dark_3), listOf(Color(0xFF1C1F26), Color(0xFF4B5563))),
        )
    } else {
        listOf(
            Pair(stringResource(R.string.display_wp_light_0), listOf(Color(0xFFC8DEFB), Color(0xFF60A5FA))),
            Pair(stringResource(R.string.display_wp_light_1), listOf(Color(0xFFFBDCE8), Color(0xFFF472B6))),
            Pair(stringResource(R.string.display_wp_light_2), listOf(Color(0xFFBFE7F4), Color(0xFF38BDF8))),
            Pair(stringResource(R.string.display_wp_light_3), listOf(Color(0xFFE9EEF6), Color(0xFFCBD5E1))),
        )
    }

    val effActualHz = actualRefreshHz.coerceAtLeast(24)
    val effMaxHz = maxSupportedRefreshHz.coerceAtLeast(effActualHz)
    val fpsReached = targetFps <= 0 || abs(effActualHz - targetFps) <= 2
    val refreshStatusHint = ExternalDisplayPolicy.formatRefreshStatusHint(
        targetFps = targetFps,
        actualRefreshHz = effActualHz,
        maxSupportedHz = effMaxHz,
        isRemoteCast = isRemoteCast,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(infoCardShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, infoCardShape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = stringResource(R.string.taskbar_cc_res_fps),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (fpsReached) OpalPalette.AccentEmerald.copy(alpha = 0.18f)
                        else OpalPalette.AccentAmber.copy(alpha = 0.18f),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                OpalText(
                    text = "${canvasW}×${canvasH} · ${effActualHz}Hz",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (fpsReached) OpalPalette.AccentEmerald else OpalPalette.AccentAmber,
                    maxLines = 1,
                )
            }
        }

        OpalText(
            text = refreshStatusHint,
            fontSize = 10.sp,
            color = OpalPalette.TextSecondary,
            maxLines = 2,
        )

        val fpsOpts = listOf(0, 60, 90, 100, 120)
        val fpsIdx = fpsOpts.indexOf(targetFps).let { if (it >= 0) it else 0 }
        OpalSegmentedRow(
            options = listOf(stringResource(R.string.common_auto), "60Hz", "90Hz", "100Hz", "120Hz"),
            selectedIndex = fpsIdx,
            onSelect = { onSelectTargetFps(fpsOpts[it]) },
        )

        Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { base ->
                    if (onOpenResolutionSettings != null) base.clickable(onClick = onOpenResolutionSettings) else base
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                OpalText(
                    text = stringResource(if (onOpenResolutionSettings != null) R.string.taskbar_cc_custom_res else R.string.taskbar_cc_canvas_res),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                OpalText(
                    text = if (onOpenResolutionSettings != null) {
                        stringResource(R.string.taskbar_cc_res_ext_sub, canvasW, canvasH)
                    } else {
                        stringResource(R.string.taskbar_cc_res_local_sub, canvasW, canvasH)
                    },
                    fontSize = 10.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 2,
                )
            }
            if (onOpenResolutionSettings != null) {
                Spacer(Modifier.width(8.dp))
                OpalButton(
                    label = stringResource(R.string.common_switch),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentBlue,
                    onClick = onOpenResolutionSettings,
                )
            }
        }

        if (showHostScreenToggle) {
            Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
            OpalSwitchRow(
                title = stringResource(R.string.display_auto_blackout_title),
                subtitle = if (hostScreenOff) {
                    stringResource(R.string.display_screen_off_sub_on)
                } else {
                    stringResource(R.string.display_auto_blackout_sub)
                },
                checked = autoBlackoutOnCast,
                onCheckedChange = onToggleAutoBlackout,
            )
        }
    }

    val isDefaultDock = dockVisible &&
        DockAppearancePolicy.isDefaultOpacity(dockOpacity) &&
        DockAppearancePolicy.isDefaultScale(dockScale)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(infoCardShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, infoCardShape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = stringResource(R.string.taskbar_cc_dock_custom),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
            )
            if (!isDefaultDock) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.ChipActiveFill)
                        .clickable(onClick = onResetDockStyle)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    OpalText(
                        text = stringResource(R.string.common_restore_defaults),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentBlue,
                    )
                }
            }
        }

        OpalSwitchRow(
            title = stringResource(R.string.taskbar_cc_dock_visible),
            subtitle = stringResource(if (dockVisible) R.string.taskbar_cc_dock_visible_on else R.string.taskbar_cc_dock_visible_off),
            checked = dockVisible,
            onCheckedChange = onToggleDockVisible,
        )

        if (dockVisible) {
            Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
            ControlCompactSliderRow(
                label = stringResource(R.string.taskbar_cc_dock_opacity),
                valueText = DockAppearancePolicy.formatOpacityLabel(dockOpacity),
                actionLabel = stringResource(if (DockAppearancePolicy.isDefaultOpacity(dockOpacity)) R.string.common_default_checked else R.string.common_default),
                actionActive = DockAppearancePolicy.isDefaultOpacity(dockOpacity),
                actionAccent = OpalPalette.AccentBlue,
                onActionClick = { onChangeDockOpacity(DockAppearancePolicy.DEFAULT_OPACITY) },
                value = dockOpacity,
                valueRange = DockAppearancePolicy.MIN_OPACITY..DockAppearancePolicy.MAX_OPACITY,
                onValueChange = onChangeDockOpacity,
            )

            Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
            ControlCompactSliderRow(
                label = stringResource(R.string.taskbar_cc_dock_scale),
                valueText = DockAppearancePolicy.formatScaleLabel(dockScale),
                actionLabel = stringResource(if (DockAppearancePolicy.isDefaultScale(dockScale)) R.string.common_default_checked else R.string.common_default),
                actionActive = DockAppearancePolicy.isDefaultScale(dockScale),
                actionAccent = OpalPalette.AccentBlue,
                onActionClick = { onChangeDockScale(DockAppearancePolicy.DEFAULT_SCALE) },
                value = dockScale,
                valueRange = DockAppearancePolicy.MIN_SCALE..DockAppearancePolicy.MAX_SCALE,
                onValueChange = onChangeDockScale,
            )
        }
    }

    if (isExternalMonitor) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OpalText(text = stringResource(R.string.taskbar_cc_ui_scale), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
            val scaleOpts = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            val scaleIdx = scaleOpts.indexOfFirst { abs(it - externalUiScale) < 0.08f }.let { if (it >= 0) it else 1 }
            OpalSegmentedRow(
                options = listOf("100%", "125%", "150%", "175%", "200%"),
                selectedIndex = scaleIdx,
                onSelect = { onSelectUiScale(scaleOpts[it]) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OpalText(text = stringResource(R.string.taskbar_cc_mouse_target), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
            OpalSegmentedRow(
                options = listOf(
                    stringResource(R.string.display_mouse_opt_local),
                    stringResource(R.string.display_mouse_ext_short),
                ),
                selectedIndex = mouseTargetMode.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL),
                onSelect = onSelectMouseTarget,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OpalText(text = stringResource(R.string.taskbar_cc_theme), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
        OpalSegmentedRow(
            options = listOf(
                stringResource(R.string.common_light),
                stringResource(R.string.common_follow_system),
                stringResource(R.string.common_dark),
            ),
            selectedIndex = themeMode.coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK),
            onSelect = onSelectThemeMode,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = stringResource(R.string.taskbar_cc_wallpaper), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
            OpalText(
                text = stringResource(R.string.taskbar_cc_pick_gallery),
                fontSize = 11.sp,
                color = OpalPalette.AccentBlue,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onPickCustomWallpaper)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            wallpaperColors.forEachIndexed { idx, (name, grad) ->
                val selected = idx == wallpaperPreset
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectWallpaper(idx) }
                        .padding(vertical = 2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Brush.linearGradient(grad))
                            .border(
                                width = if (selected) 1.6.dp else 0.7.dp,
                                color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                shape = RoundedCornerShape(9.dp),
                            ),
                    )
                    Spacer(Modifier.height(3.dp))
                    OpalText(
                        text = name,
                        fontSize = 10.sp,
                        color = if (selected) OpalPalette.TextPrimary else OpalPalette.TextMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
