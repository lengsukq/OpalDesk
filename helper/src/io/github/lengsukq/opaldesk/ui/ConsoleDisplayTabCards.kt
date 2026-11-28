package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.compositor.AppCatalogRepository
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun ConsoleExternalDisplayCard(
    status: DaemonStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    detectedExtDisplays: List<BridgeService.ExternalDisplayInfo>,
    autoExtDisplay: Boolean,
    autoShowPhoneTrackpad: Boolean,
    projectionMode: Int,
    overscanPct: Float,
    navController: ConsoleNavigationController?,
    onAutoExtDisplayChange: (Boolean) -> Unit,
    onProjectSpecificDisplay: (Int, ExternalDisplayTransport, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val extSummaryText = when {
        extDisplay != null -> formatCompactResolutionBadge(extDisplay.width, extDisplay.height, extDisplay.refreshRateHz)
        autoExtDisplay -> if (overscanPct > 0.1f) {
            stringResource(R.string.display_ext_auto_full_overscan, overscanPct.toString())
        } else {
            stringResource(R.string.display_ext_auto_full)
        }
        else -> stringResource(R.string.display_ext_manual)
    }
    OpalCollapsibleSectionCard(
        title = stringResource(R.string.display_ext_section_title),
        subtitle = stringResource(R.string.display_ext_section_sub),
        sectionKey = ConsoleSectionKeys.EXTERNAL_DISPLAY,
        controller = navController,
        summaryText = extSummaryText,
        summaryAccent = if (extDisplay != null) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
        defaultExpanded = false,
    ) {
        OpalSwitchRow(
            title = stringResource(R.string.display_auto_ext_title),
            subtitle = stringResource(R.string.display_auto_ext_sub),
            checked = autoExtDisplay,
            onCheckedChange = onAutoExtDisplayChange,
        )
        OpalSwitchRow(
            title = stringResource(R.string.display_auto_trackpad_title),
            subtitle = stringResource(R.string.display_auto_trackpad_sub),
            checked = autoShowPhoneTrackpad,
            onCheckedChange = { enabled ->
                AppPrefs.setAutoShowPhoneTrackpadEnabled(ctx, enabled)
            },
        )

        Spacer(Modifier.height(10.dp))
        OpalText(text = stringResource(R.string.display_proj_mode_title), fontSize = 12.sp, color = OpalPalette.TextSecondary)
        Spacer(Modifier.height(3.dp))
        OpalText(
            text = stringResource(R.string.display_proj_mode_hint),
            fontSize = 11.sp,
            color = OpalPalette.TextMuted,
            lineHeight = 15.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        OpalSegmentedRow(
            options = listOf(stringResource(R.string.display_proj_mode_auto), stringResource(R.string.display_proj_mode_pres)),
            selectedIndex = projectionMode.coerceIn(AppPrefs.PROJECTION_MODE_AUTO, AppPrefs.PROJECTION_MODE_PRESENTATION),
            onSelect = { mode -> AppPrefs.setExternalProjectionMode(ctx, mode) },
        )

        Spacer(Modifier.height(12.dp))
        OpalText(text = stringResource(R.string.display_overscan_title), fontSize = 12.sp, color = OpalPalette.TextSecondary)
        Spacer(Modifier.height(3.dp))
        OpalText(
            text = stringResource(R.string.display_overscan_hint),
            fontSize = 11.sp,
            color = OpalPalette.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        val overscanOpts = listOf(0f, 2f, 3.5f, 5f)
        val overscanIdx = overscanOpts.indexOfFirst { abs(it - overscanPct) < 0.25f }.let { if (it >= 0) it else 0 }
        OpalSegmentedRow(
            options = listOf(stringResource(R.string.display_overscan_off), "2%", "3.5%", "5%"),
            selectedIndex = overscanIdx,
            onSelect = { idx -> AppPrefs.setExternalOverscanPct(ctx, overscanOpts[idx]) },
        )

        Spacer(Modifier.height(12.dp))
        OpalButton(
            label = stringResource(R.string.display_open_miracast_btn),
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
                            label = stringResource(R.string.display_project_btn),
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
}

@Composable
internal fun ConsolePerAppDpiCard(
    workingProfile: WorkspaceProfileConfig,
    navController: ConsoleNavigationController?,
) {
    val ctx = LocalContext.current
    val customDpiCount = workingProfile.appCustomDpis.size
    val perAppDpiSummary = when {
        customDpiCount > 0 -> stringResource(R.string.display_dpi_custom_count, customDpiCount)
        workingProfile.globalCustomDpi > 0 -> stringResource(R.string.display_dpi_global_val, workingProfile.globalCustomDpi)
        else -> stringResource(R.string.display_dpi_auto)
    }
    OpalCollapsibleSectionCard(
        title = stringResource(R.string.display_dpi_section_title),
        subtitle = stringResource(R.string.display_dpi_section_sub),
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
            OpalText(text = stringResource(R.string.display_dpi_global_label), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            OpalText(
                text = if (isCustomGlobal) "$draftGlobalDpi DPI" else stringResource(R.string.display_dpi_auto),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = OpalPalette.AccentBlue,
            )
        }
        Spacer(Modifier.height(6.dp))
        OpalSegmentedRow(
            options = listOf(stringResource(R.string.display_dpi_auto), stringResource(R.string.display_dpi_custom_global)),
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
                    text = stringResource(R.string.display_dpi_empty_hint),
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    lineHeight = 16.sp,
                )
            }
        } else {
            OpalText(
                text = stringResource(R.string.display_dpi_configured_list, workingProfile.appCustomDpis.size),
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
                                label = stringResource(R.string.common_reset),
                                compact = true,
                                filled = false,
                                accent = OpalPalette.AccentDanger,
                                onClick = {
                                    BridgeClient.setAppCustomDpiAsync(ctx, pkg, 0)
                                    Toast.makeText(ctx, ctx.getString(R.string.display_dpi_reset_toast, appLabel), Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConsolePeripheralsWallpaperCard(
    status: DaemonStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    mouseTarget: Int,
    trackpadSens: Float,
    wallpaperPreset: Int,
    customWallpaperPath: String,
    isDark: Boolean,
    navController: ConsoleNavigationController?,
    onPickCustomWallpaper: () -> Unit,
) {
    val ctx = LocalContext.current
    val mouseShort = stringResource(if (mouseTarget == AppPrefs.MOUSE_TARGET_EXTERNAL) R.string.display_mouse_ext_short else R.string.display_mouse_local_short)
    val periSummary = if (customWallpaperPath.isNotBlank()) {
        stringResource(R.string.display_album_wallpaper_short, String.format(Locale.US, "%.2f×", trackpadSens))
    } else {
        "$mouseShort · ${String.format(Locale.US, "%.2f×", trackpadSens)}"
    }
    OpalCollapsibleSectionCard(
        title = stringResource(R.string.display_peri_section_title),
        subtitle = stringResource(R.string.display_peri_section_sub),
        sectionKey = ConsoleSectionKeys.PERIPHERALS_WALLPAPER,
        controller = navController,
        summaryText = periSummary,
        summaryAccent = if (status.boundMouseCount > 0) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
        defaultExpanded = false,
    ) {
        OpalText(
            text = stringResource(R.string.display_mouse_target_title),
            fontSize = 12.sp,
            color = OpalPalette.TextSecondary,
        )
        Spacer(Modifier.height(3.dp))
        OpalText(
            text = when {
                status.boundMouseCount > 0 ->
                    stringResource(R.string.display_mouse_bound_hint, status.boundMouseCount)
                status.connectedMouseCount > 0 && mouseTarget == AppPrefs.MOUSE_TARGET_EXTERNAL ->
                    stringResource(R.string.display_mouse_detected_ext_hint, status.connectedMouseCount)
                status.connectedMouseCount > 0 ->
                    stringResource(R.string.display_mouse_detected_local_hint, status.connectedMouseCount)
                else ->
                    stringResource(R.string.display_mouse_idle_hint)
            },
            fontSize = 11.sp,
            color = if (status.boundMouseCount > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        OpalSegmentedRow(
            options = listOf(stringResource(R.string.display_mouse_opt_local), stringResource(R.string.display_mouse_opt_ext)),
            selectedIndex = mouseTarget.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL),
            onSelect = { nextMode ->
                AppPrefs.setMouseTarget(ctx, nextMode)
                val activeExtId = resolveActiveExternalDisplayId(extDisplay)
                BridgeClient.setMouseTargetAsync(nextMode, activeExtId) { bound ->
                    val msg = when {
                        nextMode == AppPrefs.MOUSE_TARGET_LOCAL -> ctx.getString(R.string.display_mouse_toast_local)
                        bound > 0 -> ctx.getString(R.string.display_mouse_toast_ext_bound)
                        else -> ctx.getString(R.string.display_mouse_toast_ext_pending)
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
                text = stringResource(R.string.display_pointer_speed_title),
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OpalText(
                text = stringResource(R.string.display_pointer_speed_reset, String.format(Locale.US, "%.2f×", trackpadSens)),
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
            OpalText(text = stringResource(R.string.display_speed_fine), fontSize = 10.5.sp, color = OpalPalette.TextMuted)
            OpalText(text = stringResource(R.string.display_speed_std), fontSize = 10.5.sp, color = OpalPalette.TextMuted)
            OpalText(text = stringResource(R.string.display_speed_fast), fontSize = 10.5.sp, color = OpalPalette.TextMuted)
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = stringResource(if (customWallpaperPath.isNotBlank()) R.string.display_wallpaper_custom_active else R.string.display_wallpaper_default_label),
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (customWallpaperPath.isNotBlank()) {
                    OpalButton(
                        label = stringResource(R.string.display_wallpaper_restore_preset),
                        compact = true,
                        filled = false,
                        onClick = {
                            WallpaperStorage.clearCustomWallpaper(ctx)
                            Toast.makeText(ctx, ctx.getString(R.string.display_wallpaper_restored_toast), Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                OpalButton(
                    label = stringResource(R.string.display_wallpaper_pick_gallery),
                    compact = true,
                    onClick = onPickCustomWallpaper,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val themes = if (isDark) {
            listOf(
                stringResource(R.string.display_wp_dark_0) to listOf(Color(0xFF142842), Color(0xFF2A9D8F)),
                stringResource(R.string.display_wp_dark_1) to listOf(Color(0xFF2B2752), Color(0xFFE07A5F)),
                stringResource(R.string.display_wp_dark_2) to listOf(Color(0xFF0E293D), Color(0xFF38BDF8)),
                stringResource(R.string.display_wp_dark_3) to listOf(Color(0xFF1C1F26), Color(0xFF4B5563)),
            )
        } else {
            listOf(
                stringResource(R.string.display_wp_light_0) to listOf(Color(0xFFC8DEFB), Color(0xFF60A5FA)),
                stringResource(R.string.display_wp_light_1) to listOf(Color(0xFFFBDCE8), Color(0xFFF472B6)),
                stringResource(R.string.display_wp_light_2) to listOf(Color(0xFFBFE7F4), Color(0xFF38BDF8)),
                stringResource(R.string.display_wp_light_3) to listOf(Color(0xFFE9EEF6), Color(0xFFCBD5E1)),
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
}
