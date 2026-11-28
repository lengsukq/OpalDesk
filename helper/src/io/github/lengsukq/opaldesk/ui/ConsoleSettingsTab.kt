package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.cast.BleNearbyService
import io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector
import io.github.lengsukq.opaldesk.notifications.OpalCallControlBridge
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.update.AppUpdateLogic
import io.github.lengsukq.opaldesk.update.AppUpdateManager

/**
 * Tab 2: System Settings — keeps high-frequency cards (DeX & Cross-Device Notification Sync,
 * Startup & App Drawer Behavior, App Update & Feature Guide) crisp and immediately scannable,
 * while wrapping heavy / advanced cards behind [OpalCollapsibleSectionCard]s with live status pills.
 */
@Composable
fun ConsoleEngineSettingsTab(
    status: DaemonStatus,
    scene: CompositorScene,
    onOpenActivation: () -> Unit,
    onOpenUpdateModal: () -> Unit,
    onOpenFeatureGuide: () -> Unit = {},
    onStopEngine: () -> Unit,
    navController: ConsoleNavigationController? = null,
) {
    val ctx = LocalContext.current
    val autoEnterDex by AppPrefs.autoEnterDexFlow.collectAsState()
    val sortByUsage by AppPrefs.sortAppsByUsageFlow.collectAsState()
    val showDesktopGuideIcon by AppPrefs.showDesktopGuideIconFlow.collectAsState()
    val autoCheckUpdate by AppPrefs.autoCheckUpdateFlow.collectAsState()
    val updateState by AppUpdateManager.stateFlow.collectAsState()
    val remoteMicEnabled by AppPrefs.remoteMicEnabledFlow.collectAsState()
    val remoteCameraEnabled by AppPrefs.remoteCameraEnabledFlow.collectAsState()
    val audioRouteMode by AppPrefs.audioRouteModeFlow.collectAsState()
    val usbTetherEnabled by AppPrefs.usbTetherCastEnabledFlow.collectAsState()
    val callControlEnabled by AppPrefs.callControlSyncEnabledFlow.collectAsState()
    val bleNearbyEnabled by AppPrefs.bleNearbyEnabledFlow.collectAsState()
    val bleRuntime by BleNearbyService.stateFlow.collectAsState()
    val notifAccessGranted by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.accessGrantedFlow.collectAsState()
    val languagePref by AppPrefs.languagePrefFlow.collectAsState()
    val profileState by ProfileConfigStore.stateFlow.collectAsState()
    val currentVersion = remember(ctx) { AppUpdateManager.getCurrentVersionName(ctx) }
    var permRefreshTick by remember { mutableIntStateOf(0) }

    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        permRefreshTick++
        OpalCallControlBridge.syncEnabledState(ctx)
        val hasPhone = OpalCallControlBridge.hasReadPhoneStatePermission(ctx)
        val hasAnswer = OpalCallControlBridge.hasAnswerPhoneCallsPermission(ctx)
        val msg = when {
            hasPhone && hasAnswer -> ctx.getString(R.string.settings_call_toast_full)
            hasPhone -> ctx.getString(R.string.settings_call_toast_partial)
            else -> ctx.getString(R.string.settings_call_toast_fallback)
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    val blePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        permRefreshTick++
        val granted = BleNearbyService.hasRequiredPermissions(ctx)
        if (granted) {
            AppPrefs.setBleNearbyEnabled(ctx, true)
            BleNearbyService.start(ctx)
            Toast.makeText(ctx, ctx.getString(R.string.settings_ble_toast_on), Toast.LENGTH_SHORT).show()
        } else {
            AppPrefs.setBleNearbyEnabled(ctx, false)
            BleNearbyService.stop()
            Toast.makeText(ctx, ctx.getString(R.string.settings_ble_toast_denied), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        AppPrefs.hydrateFlows(ctx)
        ProfileConfigStore.hydrate(ctx)
        io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.refreshAccessState(ctx)
        OpalCallControlBridge.syncEnabledState(ctx)
        BleNearbyService.refreshState(ctx)
        permRefreshTick++
    }

    val inputBlocked = status.connected && status.injectStatus == BridgeProtocol.InputBackend.STATUS_BLOCKED
    val canUseRootCamera = status.connected && status.rootEnhanced
    val effectiveCameraEnabled = canUseRootCamera && remoteCameraEnabled
    val splitAudioOn = audioRouteMode == BridgeProtocol.AudioRoute.SPLIT_BY_APP

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Top 2-Column Bento Quick Tiles (Engine Status + App Update)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardBentoTile(
                title = stringResource(if (status.connected) R.string.settings_reactivate_title else R.string.settings_activate_title),
                subtitle = if (status.connected) {
                    stringResource(R.string.settings_engine_sub_on, status.daemonUid, status.activeVdCount, status.maxVdCount)
                } else {
                    stringResource(R.string.settings_engine_sub_off)
                },
                badgeText = stringResource(if (status.connected) R.string.common_running else R.string.common_not_activated),
                accent = if (status.connected) OpalPalette.AccentEmerald else OpalPalette.AccentAmber,
                active = status.connected,
                iconKind = 0,
                onClick = onOpenActivation,
                modifier = Modifier.weight(1f),
            )
            val hasUpdate = updateState is AppUpdateManager.UpdateState.UpdateAvailable ||
                updateState is AppUpdateManager.UpdateState.ReadyToInstall
            val updateBadge = when (val st = updateState) {
                is AppUpdateManager.UpdateState.UpdateAvailable -> st.release.tagName
                is AppUpdateManager.UpdateState.ReadyToInstall -> stringResource(R.string.settings_update_badge_ready)
                is AppUpdateManager.UpdateState.Downloading -> stringResource(R.string.settings_update_badge_downloading)
                is AppUpdateManager.UpdateState.Checking -> stringResource(R.string.settings_update_badge_checking)
                else -> "v$currentVersion"
            }
            DashboardBentoTile(
                title = stringResource(R.string.settings_update_title),
                subtitle = stringResource(if (hasUpdate) R.string.settings_update_sub_avail else R.string.settings_update_sub_check),
                badgeText = updateBadge,
                accent = if (hasUpdate) OpalPalette.AccentBlue else OpalPalette.AccentCyan,
                active = hasUpdate,
                iconKind = 1,
                onClick = {
                    when (updateState) {
                        is AppUpdateManager.UpdateState.UpdateAvailable,
                        is AppUpdateManager.UpdateState.Downloading,
                        is AppUpdateManager.UpdateState.ReadyToInstall,
                        -> onOpenUpdateModal()
                        else -> {
                            AppUpdateManager.checkForUpdates(ctx, userInitiated = true)
                            onOpenUpdateModal()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (inputBlocked) {
            TouchInputTroubleshootingTipCard(
                ctx = ctx,
                inputBlocked = true,
            )
        }

        // 0. Language Selector (Follow System / 简体中文 / English)
        MacGlassSectionCard(
            contentPadding = PaddingValues(15.dp),
        ) {
            val langOptions = listOf(
                OpalLocalePolicy.LANG_SYSTEM,
                OpalLocalePolicy.LANG_ZH_CN,
                OpalLocalePolicy.LANG_EN,
            )
            val selectedLangIdx = langOptions.indexOf(OpalLocalePolicy.normalizeLanguagePref(languagePref)).coerceAtLeast(0)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpalText(text = stringResource(R.string.lang_section_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    OpalText(
                        text = stringResource(R.string.lang_section_subtitle),
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OpalSegmentedRow(
                options = listOf(
                    stringResource(R.string.lang_option_system),
                    stringResource(R.string.lang_option_zh_cn),
                    stringResource(R.string.lang_option_en),
                ),
                selectedIndex = selectedLangIdx,
                onSelect = { idx ->
                    val chosen = langOptions.getOrElse(idx) { OpalLocalePolicy.LANG_SYSTEM }
                    if (chosen != OpalLocalePolicy.normalizeLanguagePref(languagePref)) {
                        OpalLocalePolicy.applyRuntimeLocale(ctx, chosen)
                    }
                },
            )
        }

        // 1. High-Frequency Card: DeX & Cross-Device Notification Sync (+ Wave 2 Companion Hook)
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
                    onClick = {
                        io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.openNotificationAccessSettings(ctx)
                    },
                )
            }

            // === WAVE 2 COMPANION HOOK: Call Control & BLE Nearby Discovery Toggles ===
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
                            callPermLauncher.launch(missing.toTypedArray())
                        } else {
                            OpalCallControlBridge.syncEnabledState(ctx)
                            permRefreshTick++
                            Toast.makeText(ctx, ctx.getString(R.string.settings_call_toast_full), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        AppPrefs.setCallControlSyncEnabled(ctx, false)
                        OpalCallControlBridge.stop(ctx)
                        permRefreshTick++
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
                                    callPermLauncher.launch(missing.toTypedArray())
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
                            blePermLauncher.launch(needed.toTypedArray())
                        } else {
                            AppPrefs.setBleNearbyEnabled(ctx, true)
                            BleNearbyService.start(ctx)
                            permRefreshTick++
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
                        permRefreshTick++
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
                                    blePermLauncher.launch(needed.toTypedArray())
                                }
                            },
                        )
                    }
                }
            }
        }

        // 2. High-Frequency Card: Startup Behavior & App List
        MacGlassSectionCard(
            modifier = Modifier.consoleSearchAnchor(navController, ConsoleSectionKeys.STARTUP_APPS),
            contentPadding = PaddingValues(15.dp),
        ) {
            OpalText(text = stringResource(R.string.settings_startup_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalSwitchRow(
                title = stringResource(R.string.settings_auto_dex_title),
                subtitle = stringResource(R.string.settings_auto_dex_sub),
                checked = autoEnterDex,
                onCheckedChange = { enabled -> AppPrefs.setAutoEnterDexEnabled(ctx, enabled) },
            )
            OpalSwitchRow(
                title = stringResource(R.string.settings_sort_usage_title),
                subtitle = stringResource(R.string.settings_sort_usage_sub),
                checked = sortByUsage,
                onCheckedChange = { enabled -> AppPrefs.setSortAppsByUsage(ctx, enabled) },
            )
            OpalSwitchRow(
                title = stringResource(R.string.settings_guide_icon_title),
                subtitle = stringResource(R.string.settings_guide_icon_sub),
                checked = showDesktopGuideIcon,
                onCheckedChange = { enabled -> AppPrefs.setDesktopGuideIconVisible(ctx, enabled) },
            )
        }

        // 3. High-Frequency Card: App Update & Feature Guide
        MacGlassSectionCard(
            modifier = Modifier.consoleSearchAnchor(navController, ConsoleSectionKeys.APP_UPDATE_GUIDE),
            contentPadding = PaddingValues(15.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpalText(text = stringResource(R.string.settings_update_guide_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    OpalText(
                        text = stringResource(R.string.settings_current_ver_sub, currentVersion),
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                when (val st = updateState) {
                    is AppUpdateManager.UpdateState.UpdateAvailable ->
                        OpalStatusBadge(label = stringResource(R.string.settings_badge_new_ver, st.release.tagName), color = OpalPalette.AccentBlue)
                    is AppUpdateManager.UpdateState.ReadyToInstall ->
                        OpalStatusBadge(label = stringResource(R.string.settings_badge_ready_install, st.release.tagName), color = OpalPalette.AccentEmerald)
                    is AppUpdateManager.UpdateState.Downloading -> {
                        val pct = if (st.totalBytes > 0L) ((st.downloadedBytes * 100L) / st.totalBytes).toInt() else 0
                        OpalStatusBadge(label = stringResource(R.string.settings_badge_downloading_pct, pct), color = OpalPalette.AccentBlue)
                    }
                    is AppUpdateManager.UpdateState.UpToDate ->
                        OpalStatusBadge(label = stringResource(R.string.settings_badge_up_to_date), color = OpalPalette.AccentEmerald)
                    is AppUpdateManager.UpdateState.Checking ->
                        OpalStatusBadge(label = stringResource(R.string.settings_badge_checking), color = OpalPalette.AccentCyan)
                    else -> {}
                }
            }

            if (updateState is AppUpdateManager.UpdateState.Error) {
                Spacer(Modifier.height(7.dp))
                OpalText(
                    text = (updateState as AppUpdateManager.UpdateState.Error).message,
                    fontSize = 11.5.sp,
                    color = OpalPalette.AccentDanger,
                )
            }

            Spacer(Modifier.height(6.dp))
            OpalSwitchRow(
                title = stringResource(R.string.settings_auto_update_title),
                subtitle = stringResource(R.string.settings_auto_update_sub),
                checked = autoCheckUpdate,
                onCheckedChange = { enabled -> AppPrefs.setAutoCheckUpdate(ctx, enabled) },
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val primaryLabel = when (val st = updateState) {
                    is AppUpdateManager.UpdateState.Checking -> stringResource(R.string.settings_btn_checking)
                    is AppUpdateManager.UpdateState.UpdateAvailable -> stringResource(R.string.settings_btn_update_to, st.release.tagName)
                    is AppUpdateManager.UpdateState.Downloading -> stringResource(R.string.settings_btn_view_download)
                    is AppUpdateManager.UpdateState.ReadyToInstall -> stringResource(R.string.settings_btn_install_ver, st.release.tagName)
                    else -> stringResource(R.string.settings_btn_check_update)
                }
                val primaryAccent = when (updateState) {
                    is AppUpdateManager.UpdateState.ReadyToInstall -> OpalPalette.AccentEmerald
                    else -> OpalPalette.AccentBlue
                }
                OpalButton(
                    label = primaryLabel,
                    compact = true,
                    filled = updateState is AppUpdateManager.UpdateState.UpdateAvailable ||
                        updateState is AppUpdateManager.UpdateState.ReadyToInstall,
                    enabled = updateState !is AppUpdateManager.UpdateState.Checking,
                    accent = primaryAccent,
                    onClick = {
                        when (updateState) {
                            is AppUpdateManager.UpdateState.UpdateAvailable,
                            is AppUpdateManager.UpdateState.Downloading,
                            is AppUpdateManager.UpdateState.ReadyToInstall,
                            -> onOpenUpdateModal()
                            else -> {
                                AppUpdateManager.checkForUpdates(ctx, userInitiated = true)
                                onOpenUpdateModal()
                            }
                        }
                    },
                    modifier = Modifier.weight(1.2f),
                )
                OpalButton(
                    label = "Releases ↗",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentCyan,
                    onClick = {
                        AppUpdateManager.openReleaseInBrowser(ctx, AppUpdateLogic.ALL_RELEASES_WEB_URL)
                    },
                    modifier = Modifier.weight(0.9f),
                )
            }

            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(OpalPalette.DividerColor),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    OpalText(text = stringResource(R.string.settings_guide_row_title), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    OpalText(
                        text = stringResource(R.string.settings_guide_row_sub),
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                OpalButton(
                    label = stringResource(R.string.settings_guide_open_btn),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentBlue,
                    onClick = onOpenFeatureGuide,
                )
            }
        }

        ConsoleSectionTitle(stringResource(R.string.settings_advanced_section))

        // 4. Collapsible: Configuration Slots & Auto Layout Restore
        val activeSlot = profileState.activeSavedSlot
        val slotSummary = if (profileState.hasUnsavedChanges) {
            stringResource(R.string.settings_slot_unsaved, profileState.activeSlotId + 1)
        } else {
            stringResource(R.string.settings_slot_named, profileState.activeSlotId + 1, activeSlot.name.take(6))
        }
        OpalCollapsibleSectionCard(
            title = stringResource(R.string.settings_slots_title),
            subtitle = stringResource(R.string.settings_slots_sub),
            sectionKey = ConsoleSectionKeys.SLOTS_AUTO_LAYOUT,
            controller = navController,
            summaryText = slotSummary,
            summaryAccent = if (profileState.hasUnsavedChanges) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
            defaultExpanded = false,
        ) {
            ConsoleSlotsAndLayoutContent(status = status, scene = scene)
        }

        // 5. Collapsible: Audio Split Routing & Remote Mic / Camera
        val audioSummary = when {
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
            summaryAccent = if (splitAudioOn || remoteMicEnabled || effectiveCameraEnabled) {
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

        // 6. Collapsible: Experimental USB Tethering Cast (Beta)
        OpalCollapsibleSectionCard(
            title = stringResource(R.string.settings_usb_beta_title),
            subtitle = stringResource(R.string.settings_usb_beta_sub),
            sectionKey = ConsoleSectionKeys.USB_TETHER_BETA,
            controller = navController,
            summaryText = stringResource(if (usbTetherEnabled) R.string.settings_usb_beta_on else R.string.common_disabled),
            summaryAccent = if (usbTetherEnabled) OpalPalette.AccentCyan else OpalPalette.TextMuted,
            defaultExpanded = false,
        ) {
            OpalSwitchRow(
                title = stringResource(R.string.settings_usb_beta_switch_title),
                subtitle = stringResource(R.string.settings_usb_beta_switch_sub),
                checked = usbTetherEnabled,
                onCheckedChange = { enabled ->
                    AppPrefs.setUsbTetherCastEnabled(ctx, enabled)
                    if (enabled) {
                        UsbTetherPeerDetector.start(ctx)
                        Thread({ BridgeClient.refreshRemoteStatus() }, "OpalUsbPrefRefresh").start()
                    } else {
                        UsbTetherPeerDetector.stop()
                        if (BridgeClient.remoteStatusFlow.value.isUsbTetherOnlyMode) {
                            BridgeClient.setWifiStreamEnabledAsync(false, 19878, BridgeProtocol.TlsBindScope.ALL)
                        }
                    }
                },
            )
        }

        // 7. Collapsible: Low-level Engine Input Backend & VirtualDisplay Pool Quota
        val backendShort = when (status.inputBackendMode) {
            BridgeProtocol.InputBackend.SYSTEM -> stringResource(R.string.settings_backend_system)
            BridgeProtocol.InputBackend.UHID -> "UHID"
            else -> stringResource(R.string.common_auto)
        }
        val effectiveQuota = status.maxVdCount.takeIf { it > 0 } ?: profileState.workingConfig.maxVdCount
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
}
