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
    onOpenFaq: () -> Unit = {},
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
        ConsoleNotificationAndCompanionCard(
            notifAccessGranted = notifAccessGranted,
            callControlEnabled = callControlEnabled,
            bleNearbyEnabled = bleNearbyEnabled,
            bleRuntime = bleRuntime,
            permRefreshTick = permRefreshTick,
            navController = navController,
            onRequestCallPermissions = { callPermLauncher.launch(it) },
            onRequestBlePermissions = { blePermLauncher.launch(it) },
            onBumpPermTick = { permRefreshTick++ },
        )

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
                    OpalText(text = stringResource(R.string.settings_faq_row_title), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    OpalText(
                        text = stringResource(R.string.settings_faq_row_sub),
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                OpalButton(
                    label = stringResource(R.string.settings_faq_open_btn),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentEmerald,
                    onClick = onOpenFaq,
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
        ConsoleAudioMediaCard(
            audioRouteMode = audioRouteMode,
            splitAudioOn = splitAudioOn,
            remoteMicEnabled = remoteMicEnabled,
            remoteCameraEnabled = remoteCameraEnabled,
            canUseRootCamera = canUseRootCamera,
            effectiveCameraEnabled = effectiveCameraEnabled,
            navController = navController,
        )

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
        val effectiveQuota = status.maxVdCount.takeIf { it > 0 } ?: profileState.workingConfig.maxVdCount
        ConsoleEngineLowLevelCard(
            status = status,
            effectiveQuota = effectiveQuota,
            inputBlocked = inputBlocked,
            navController = navController,
            onStopEngine = onStopEngine,
        )
    }
}
