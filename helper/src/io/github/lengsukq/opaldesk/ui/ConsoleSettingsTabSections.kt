package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.cast.BleNearbyRuntimeState
import io.github.lengsukq.opaldesk.cast.BleNearbyService
import io.github.lengsukq.opaldesk.notifications.OpalCallControlBridge
import io.github.lengsukq.opaldesk.notifications.OpalNotificationStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DaemonStatus

@Composable
internal fun ConsoleNotificationAndCompanionCard(
    notifAccessGranted: Boolean,
    callControlEnabled: Boolean,
    bleNearbyEnabled: Boolean,
    bleRuntime: BleNearbyRuntimeState,
    permRefreshTick: Int,
    navController: ConsoleNavigationController?,
    onRequestCallPermissions: (Array<String>) -> Unit,
    onRequestBlePermissions: (Array<String>) -> Unit,
    onBumpPermTick: () -> Unit,
) {
    val ctx = LocalContext.current
    MacGlassSectionCard(
        modifier = Modifier.consoleSearchAnchor(navController, ConsoleSectionKeys.NOTIFICATIONS_COMPANION),
        contentPadding = PaddingValues(15.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OpalText(text = stringResource(R.string.settings_notif_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                OpalText(
                    text = if (notifAccessGranted) {
                        stringResource(R.string.settings_notif_sub_granted)
                    } else {
                        stringResource(R.string.settings_notif_sub_denied)
                    },
                    fontSize = 11.5.sp,
                    color = if (notifAccessGranted) OpalPalette.AccentEmerald else OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            OpalButton(
                label = stringResource(if (notifAccessGranted) R.string.settings_notif_manage else R.string.settings_notif_enable),
                compact = true,
                filled = !notifAccessGranted,
                accent = if (notifAccessGranted) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                onClick = { OpalNotificationStore.openNotificationAccessSettings(ctx) },
            )
        }

        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.7.dp)
                .background(OpalPalette.DividerColor),
        )
        Spacer(Modifier.height(8.dp))

        val callPhoneGranted = remember(permRefreshTick, callControlEnabled) {
            OpalCallControlBridge.hasReadPhoneStatePermission(ctx)
        }
        val callAnswerGranted = remember(permRefreshTick, callControlEnabled) {
            OpalCallControlBridge.hasAnswerPhoneCallsPermission(ctx)
        }
        OpalSwitchRow(
            title = stringResource(R.string.settings_call_title),
            subtitle = stringResource(R.string.settings_call_sub),
            checked = callControlEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    AppPrefs.setCallControlSyncEnabled(ctx, true)
                    val missing = OpalCallControlBridge.requiredRuntimePermissions()
                        .filterNot { OpalCallControlBridge.hasPermission(ctx, it) }
                    if (missing.isNotEmpty()) {
                        onRequestCallPermissions(missing.toTypedArray())
                    } else {
                        OpalCallControlBridge.syncEnabledState(ctx)
                        onBumpPermTick()
                        Toast.makeText(ctx, ctx.getString(R.string.settings_call_toast_full), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AppPrefs.setCallControlSyncEnabled(ctx, false)
                    OpalCallControlBridge.stop(ctx)
                    onBumpPermTick()
                }
            },
        )
        if (callControlEnabled) {
            val callBadge = when {
                callPhoneGranted && callAnswerGranted -> stringResource(R.string.settings_call_badge_ready) to OpalPalette.AccentEmerald
                callPhoneGranted || notifAccessGranted -> stringResource(R.string.settings_call_badge_partial) to OpalPalette.AccentAmber
                else -> stringResource(R.string.settings_call_badge_pending) to OpalPalette.AccentAmber
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalStatusBadge(label = callBadge.first, color = callBadge.second)
                if (!callPhoneGranted || !callAnswerGranted) {
                    OpalButton(
                        label = stringResource(R.string.settings_call_grant_btn),
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentAmber,
                        onClick = {
                            val missing = OpalCallControlBridge.requiredRuntimePermissions()
                                .filterNot { OpalCallControlBridge.hasPermission(ctx, it) }
                            if (missing.isNotEmpty()) {
                                onRequestCallPermissions(missing.toTypedArray())
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.7.dp)
                .background(OpalPalette.DividerColor),
        )
        Spacer(Modifier.height(8.dp))

        val bleSupported = remember(permRefreshTick, bleRuntime.supported) {
            BleNearbyService.isBleSupported(ctx)
        }
        val blePermGranted = remember(permRefreshTick, bleRuntime.hasPermissions) {
            BleNearbyService.hasRequiredPermissions(ctx)
        }
        val bleBtOn = remember(permRefreshTick, bleRuntime.bluetoothEnabled) {
            BleNearbyService.isBluetoothEnabled(ctx)
        }
        OpalSwitchRow(
            title = stringResource(R.string.settings_ble_title),
            subtitle = if (!bleSupported) {
                stringResource(R.string.settings_ble_sub_unsupported)
            } else {
                stringResource(R.string.settings_ble_sub)
            },
            checked = bleNearbyEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (!bleSupported) {
                        Toast.makeText(ctx, ctx.getString(R.string.settings_ble_toast_unsupported), Toast.LENGTH_SHORT).show()
                        return@OpalSwitchRow
                    }
                    val needed = BleNearbyService.requiredRuntimePermissions()
                        .filterNot { OpalCallControlBridge.hasPermission(ctx, it) }
                    if (needed.isNotEmpty()) {
                        onRequestBlePermissions(needed.toTypedArray())
                    } else {
                        AppPrefs.setBleNearbyEnabled(ctx, true)
                        BleNearbyService.start(ctx)
                        onBumpPermTick()
                        val hint = if (BleNearbyService.isBluetoothEnabled(ctx)) {
                            ctx.getString(R.string.settings_ble_toast_on)
                        } else {
                            ctx.getString(R.string.settings_ble_toast_bt_off)
                        }
                        Toast.makeText(ctx, hint, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AppPrefs.setBleNearbyEnabled(ctx, false)
                    BleNearbyService.stop()
                    onBumpPermTick()
                }
            },
        )
        if (bleNearbyEnabled || !bleSupported) {
            val bleBadge = when {
                !bleSupported -> stringResource(R.string.settings_ble_badge_unsupported) to OpalPalette.TextMuted
                !blePermGranted -> stringResource(R.string.settings_ble_badge_perm) to OpalPalette.AccentAmber
                !bleBtOn -> stringResource(R.string.settings_ble_badge_bt_off) to OpalPalette.AccentAmber
                bleRuntime.advertising -> stringResource(R.string.settings_ble_badge_adv) to OpalPalette.AccentEmerald
                else -> stringResource(R.string.settings_ble_badge_ready) to OpalPalette.AccentCyan
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalStatusBadge(label = bleBadge.first, color = bleBadge.second)
                if (bleSupported && !blePermGranted) {
                    OpalButton(
                        label = stringResource(R.string.settings_ble_grant_btn),
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentAmber,
                        onClick = {
                            val needed = BleNearbyService.requiredRuntimePermissions()
                                .filterNot { OpalCallControlBridge.hasPermission(ctx, it) }
                            if (needed.isNotEmpty()) {
                                onRequestBlePermissions(needed.toTypedArray())
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConsoleAudioMediaCard(
    audioRouteMode: Int,
    splitAudioOn: Boolean,
    forcePhoneSpeaker: Boolean,
    remoteMicEnabled: Boolean,
    remoteCameraEnabled: Boolean,
    canUseRootCamera: Boolean,
    effectiveCameraEnabled: Boolean,
    navController: ConsoleNavigationController?,
) {
    val ctx = LocalContext.current
    val audioSummary = when {
        forcePhoneSpeaker -> stringResource(R.string.settings_force_phone_speaker_badge)
        splitAudioOn && (remoteMicEnabled || effectiveCameraEnabled) -> stringResource(R.string.settings_audio_sum_split_media)
        splitAudioOn -> stringResource(R.string.settings_audio_sum_split)
        remoteMicEnabled || effectiveCameraEnabled -> stringResource(R.string.settings_audio_sum_all_media)
        else -> stringResource(R.string.settings_audio_sum_all)
    }
    OpalCollapsibleSectionCard(
        title = stringResource(R.string.settings_audio_title),
        subtitle = stringResource(R.string.settings_audio_sub),
        sectionKey = ConsoleSectionKeys.AUDIO_MEDIA,
        controller = navController,
        summaryText = audioSummary,
        summaryAccent = if (forcePhoneSpeaker || splitAudioOn || remoteMicEnabled || effectiveCameraEnabled) {
            OpalPalette.AccentEmerald
        } else {
            OpalPalette.TextMuted
        },
        defaultExpanded = false,
    ) {
        OpalText(text = stringResource(R.string.settings_audio_mode_label), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        OpalText(
            text = if (splitAudioOn) {
                stringResource(R.string.settings_audio_split_hint)
            } else {
                stringResource(R.string.settings_audio_all_hint)
            },
            fontSize = 11.5.sp,
            color = OpalPalette.TextSecondary,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(7.dp))
        OpalSegmentedRow(
            options = listOf(stringResource(R.string.settings_audio_opt_all), stringResource(R.string.settings_audio_opt_split)),
            selectedIndex = audioRouteMode.coerceIn(
                BridgeProtocol.AudioRoute.ALL_TO_DEX,
                BridgeProtocol.AudioRoute.SPLIT_BY_APP,
            ),
            onSelect = { mode ->
                BridgeClient.setAudioRouteModeAsync(ctx, mode) {
                    val msg = if (mode == BridgeProtocol.AudioRoute.SPLIT_BY_APP) {
                        ctx.getString(R.string.settings_audio_toast_split)
                    } else {
                        ctx.getString(R.string.settings_audio_toast_all)
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        OpalSwitchRow(
            title = stringResource(R.string.settings_force_phone_speaker_title),
            subtitle = stringResource(R.string.settings_force_phone_speaker_sub),
            checked = forcePhoneSpeaker,
            onCheckedChange = { enabled ->
                BridgeClient.setForcePhoneSpeakerAsync(ctx, enabled) {
                    val msg = ctx.getString(
                        if (enabled) R.string.settings_force_phone_speaker_toast_on else R.string.settings_force_phone_speaker_toast_off,
                    )
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        OpalSwitchRow(
            title = stringResource(R.string.settings_mic_title),
            subtitle = stringResource(R.string.settings_mic_sub),
            checked = remoteMicEnabled,
            onCheckedChange = { enabled ->
                BridgeClient.setRemoteMediaConfigAsync(ctx, enabled, effectiveCameraEnabled) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(if (enabled) R.string.settings_mic_toast_on else R.string.settings_mic_toast_off),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        OpalSwitchRow(
            title = stringResource(R.string.settings_cam_title),
            subtitle = if (!canUseRootCamera) {
                stringResource(R.string.settings_cam_sub_no_root)
            } else {
                stringResource(R.string.settings_cam_sub_ready)
            },
            checked = effectiveCameraEnabled,
            onCheckedChange = { enabled ->
                if (enabled && !canUseRootCamera) {
                    if (remoteCameraEnabled) {
                        AppPrefs.setRemoteCameraEnabled(ctx, false)
                    }
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.settings_cam_toast_no_root),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@OpalSwitchRow
                }
                BridgeClient.setRemoteMediaConfigAsync(ctx, remoteMicEnabled, enabled) { ok ->
                    val msg = when {
                        !enabled -> ctx.getString(R.string.settings_cam_toast_off)
                        ok -> ctx.getString(R.string.settings_cam_toast_on)
                        else -> ctx.getString(R.string.settings_cam_toast_no_root)
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
internal fun ConsoleEngineLowLevelCard(
    status: DaemonStatus,
    effectiveQuota: Int,
    inputBlocked: Boolean,
    navController: ConsoleNavigationController?,
    onStopEngine: () -> Unit,
) {
    val ctx = LocalContext.current
    val backendShort = when (status.inputBackendMode) {
        BridgeProtocol.InputBackend.SYSTEM -> stringResource(R.string.settings_backend_system)
        BridgeProtocol.InputBackend.UHID -> "UHID"
        else -> stringResource(R.string.common_auto)
    }
    OpalCollapsibleSectionCard(
        title = stringResource(R.string.settings_engine_lowlevel_title),
        subtitle = stringResource(R.string.settings_engine_lowlevel_sub),
        sectionKey = ConsoleSectionKeys.ENGINE_LOWLEVEL,
        controller = navController,
        summaryText = stringResource(R.string.settings_engine_lowlevel_summary, backendShort, effectiveQuota),
        summaryAccent = if (inputBlocked) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
        defaultExpanded = false,
    ) {
        OpalText(
            text = stringResource(R.string.settings_input_hint),
            fontSize = 11.5.sp,
            color = OpalPalette.TextSecondary,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(10.dp))

        val inputIdx = when (status.inputBackendMode) {
            BridgeProtocol.InputBackend.SYSTEM -> 1
            BridgeProtocol.InputBackend.UHID -> 2
            else -> 0
        }
        OpalSegmentedRow(
            options = listOf(
                stringResource(R.string.settings_input_opt_auto),
                stringResource(R.string.settings_input_opt_system),
                stringResource(R.string.settings_input_opt_uhid),
            ),
            selectedIndex = inputIdx,
            onSelect = { idx ->
                val mode = when (idx) {
                    1 -> BridgeProtocol.InputBackend.SYSTEM
                    2 -> BridgeProtocol.InputBackend.UHID
                    else -> BridgeProtocol.InputBackend.AUTO
                }
                ProfileConfigStore.setWorkingInputBackendMode(mode)
                BridgeClient.updateCompositorConfigAsync(inputBackendMode = mode)
            },
        )

        Spacer(Modifier.height(14.dp))
        OpalText(
            text = stringResource(R.string.settings_quota_label),
            fontSize = 12.sp,
            color = OpalPalette.TextSecondary,
        )
        Spacer(Modifier.height(7.dp))
        val poolOptions = listOf(3, 4, 5, 6, 8, 10)
        val curMaxIdx = poolOptions.indexOf(effectiveQuota).let { if (it >= 0) it else 3 }
        OpalSegmentedRow(
            options = poolOptions.map { stringResource(R.string.settings_quota_unit, it) },
            selectedIndex = curMaxIdx,
            onSelect = { idx ->
                ProfileConfigStore.setWorkingMaxVdCount(poolOptions[idx])
                BridgeClient.updateCompositorConfigAsync(maxVdCount = poolOptions[idx])
            },
        )

        if (!inputBlocked) {
            Spacer(Modifier.height(12.dp))
            TouchInputTroubleshootingTipCard(
                ctx = ctx,
                inputBlocked = false,
            )
        }

        if (status.connected) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OpalButton(
                    label = stringResource(R.string.dash_hero_close_all_windows),
                    filled = false,
                    compact = true,
                    accent = OpalPalette.AccentAmber,
                    onClick = { BridgeClient.closeAllWindowsAsync(removeTasks = true) },
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = stringResource(R.string.settings_stop_engine),
                    filled = false,
                    compact = true,
                    accent = OpalPalette.AccentDanger,
                    onClick = onStopEngine,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
