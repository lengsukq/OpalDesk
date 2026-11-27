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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.cast.BleNearbyService
import io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector
import io.github.lengsukq.opaldesk.notifications.OpalCallControlBridge
import io.github.lengsukq.opaldesk.prefs.AppPrefs
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
            hasPhone && hasAnswer -> "已开启 Mac 来电控制与快捷拨号"
            hasPhone -> "已开启来电状态同步（未授接听/挂断权限时将由通知或按键回退）"
            else -> "已开启来电控制（未授电话权限，将使用通知兜底与拨号盘回退）"
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
            Toast.makeText(ctx, "已开启蓝牙近场发现与一键热点", Toast.LENGTH_SHORT).show()
        } else {
            AppPrefs.setBleNearbyEnabled(ctx, false)
            BleNearbyService.stop()
            Toast.makeText(ctx, "未授予蓝牙广播与连接权限，无法开启近场发现", Toast.LENGTH_SHORT).show()
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
                title = if (status.connected) "重新激活引擎" else "激活多窗引擎",
                subtitle = if (status.connected) "uid=${status.daemonUid} · ${status.activeVdCount}/${status.maxVdCount} 虚拟屏" else "点击选择激活方式",
                badgeText = if (status.connected) "运行中" else "未激活",
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
                is AppUpdateManager.UpdateState.ReadyToInstall -> "待安装"
                is AppUpdateManager.UpdateState.Downloading -> "下载中"
                is AppUpdateManager.UpdateState.Checking -> "检查中"
                else -> "v$currentVersion"
            }
            DashboardBentoTile(
                title = "应用更新",
                subtitle = if (hasUpdate) "发现新版本，点击查看" else "点击检查 GitHub 新版本",
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
                    OpalText(text = "DeX 与跨端通知同步", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    OpalText(
                        text = if (notifAccessGranted) {
                            "已授权通知使用权 · 手机消息与验证码实时同步到 DeX 与跨端设备"
                        } else {
                            "开启通知使用权后，可在 DeX 桌面与关联设备实时接收并打开消息"
                        },
                        fontSize = 11.5.sp,
                        color = if (notifAccessGranted) OpalPalette.AccentEmerald else OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                OpalButton(
                    label = if (notifAccessGranted) "管理权限" else "去开启",
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
                title = "Mac 来电控制浮窗与快捷拨号",
                subtitle = "开启后按需申请电话与通讯录权限，支持 Mac 实时来电浮窗、接听/拒接、静音免提与快捷拨号",
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
                            Toast.makeText(ctx, "已开启 Mac 来电控制与快捷拨号", Toast.LENGTH_SHORT).show()
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
                    callPhoneGranted && callAnswerGranted -> "电话权限已就绪" to OpalPalette.AccentEmerald
                    callPhoneGranted || notifAccessGranted -> "部分授权（通知/按键兜底）" to OpalPalette.AccentAmber
                    else -> "待授权电话权限" to OpalPalette.AccentAmber
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OpalStatusBadge(label = callBadge.first, color = callBadge.second)
                    if (!callPhoneGranted || !callAnswerGranted) {
                        OpalButton(
                            label = "补全权限",
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
                title = "蓝牙近场发现与一键热点",
                subtitle = if (!bleSupported) {
                    "当前设备不支持低功耗蓝牙外设广播"
                } else {
                    "低功耗 BLE 广播设备指纹供附近已信任 Mac 自动发现，并支持 HMAC 鉴权远程触发个人热点"
                },
                checked = bleNearbyEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        if (!bleSupported) {
                            Toast.makeText(ctx, "当前设备不支持低功耗蓝牙广播", Toast.LENGTH_SHORT).show()
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
                                "已开启蓝牙近场发现与一键热点"
                            } else {
                                "已开启，打开系统蓝牙后将自动开始近场广播"
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
                    !bleSupported -> "设备不支持 BLE" to OpalPalette.TextMuted
                    !blePermGranted -> "待授权蓝牙权限" to OpalPalette.AccentAmber
                    !bleBtOn -> "请开启系统蓝牙" to OpalPalette.AccentAmber
                    bleRuntime.advertising -> "BLE 近场广播中" to OpalPalette.AccentEmerald
                    else -> "BLE 已就绪" to OpalPalette.AccentCyan
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OpalStatusBadge(label = bleBadge.first, color = bleBadge.second)
                    if (bleSupported && !blePermGranted) {
                        OpalButton(
                            label = "授权蓝牙",
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
            OpalText(text = "启动行为与应用列表", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalSwitchRow(
                title = "打开应用自动进入 DeX 模式",
                subtitle = "仅冷启动且引擎就绪时直接进入本机 DeX 桌面，未激活或投屏中留在控制台",
                checked = autoEnterDex,
                onCheckedChange = { enabled -> AppPrefs.setAutoEnterDexEnabled(ctx, enabled) },
            )
            OpalSwitchRow(
                title = "应用列表按打开次数排序",
                subtitle = "同步作用于 DeX 应用抽屉与远程独立应用小窗，关闭后按名称排序",
                checked = sortByUsage,
                onCheckedChange = { enabled -> AppPrefs.setSortAppsByUsage(ctx, enabled) },
            )
            OpalSwitchRow(
                title = "在 DeX 桌面显示「使用指南」图标",
                subtitle = "关闭后隐藏桌面图标，仍可从控制中心或下方随时打开",
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
                    OpalText(text = "应用更新与使用指南", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    OpalText(
                        text = "当前版本 v$currentVersion · GitHub Releases 直连校验",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                when (val st = updateState) {
                    is AppUpdateManager.UpdateState.UpdateAvailable ->
                        OpalStatusBadge(label = "新版本 ${st.release.tagName}", color = OpalPalette.AccentBlue)
                    is AppUpdateManager.UpdateState.ReadyToInstall ->
                        OpalStatusBadge(label = "待安装 ${st.release.tagName}", color = OpalPalette.AccentEmerald)
                    is AppUpdateManager.UpdateState.Downloading -> {
                        val pct = if (st.totalBytes > 0L) ((st.downloadedBytes * 100L) / st.totalBytes).toInt() else 0
                        OpalStatusBadge(label = "下载中 $pct%", color = OpalPalette.AccentBlue)
                    }
                    is AppUpdateManager.UpdateState.UpToDate ->
                        OpalStatusBadge(label = "已是最新", color = OpalPalette.AccentEmerald)
                    is AppUpdateManager.UpdateState.Checking ->
                        OpalStatusBadge(label = "检查中…", color = OpalPalette.AccentCyan)
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
                title = "启动时自动检查新版本",
                subtitle = "每 6 小时静默检查一次 GitHub Releases，发现正式版后在顶栏轻提醒",
                checked = autoCheckUpdate,
                onCheckedChange = { enabled -> AppPrefs.setAutoCheckUpdate(ctx, enabled) },
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val primaryLabel = when (val st = updateState) {
                    is AppUpdateManager.UpdateState.Checking -> "正在检查…"
                    is AppUpdateManager.UpdateState.UpdateAvailable -> "更新至 ${st.release.tagName}"
                    is AppUpdateManager.UpdateState.Downloading -> "查看下载"
                    is AppUpdateManager.UpdateState.ReadyToInstall -> "安装 ${st.release.tagName}"
                    else -> "检查更新"
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
                    OpalText(text = "OpalDesk 使用指南", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    OpalText(
                        text = "特性速览、快捷键技巧与排障文档 · 云端热更新",
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                OpalButton(
                    label = "打开指南",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentBlue,
                    onClick = onOpenFeatureGuide,
                )
            }
        }

        ConsoleSectionTitle("高级与引擎设置")

        // 4. Collapsible: Configuration Slots & Auto Layout Restore
        val activeSlot = profileState.activeSavedSlot
        val slotSummary = if (profileState.hasUnsavedChanges) {
            "槽位 ${profileState.activeSlotId + 1} · 未保存"
        } else {
            "槽位 ${profileState.activeSlotId + 1} · ${activeSlot.name.take(6)}"
        }
        OpalCollapsibleSectionCard(
            title = "配置槽位与自动布局",
            subtitle = "5 组场景独立保存 DPI、应用窗口位置与自动恢复布局",
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
            splitAudioOn && (remoteMicEnabled || effectiveCameraEnabled) -> "独立分流 · 远端媒体"
            splitAudioOn -> "独立分流"
            remoteMicEnabled || effectiveCameraEnabled -> "整机 · 远端媒体"
            else -> "整机传 DeX"
        }
        OpalCollapsibleSectionCard(
            title = "音频分流与远端麦摄",
            subtitle = "手机 / DeX 应用独立发声，以及远端麦克风与 Root 摄像头映射",
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
            OpalText(text = "投屏声音分流模式", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = if (splitAudioOn) {
                    "默认应用独立分流：手机主屏应用由手机发声，DeX 窗口内应用由投屏端发声（Android 11+ 免 Root，实时生效）"
                } else {
                    "当前整机声音统一传至 DeX 投屏端；切回「应用独立分流」可恢复手机与 DeX 各自发声"
                },
                fontSize = 11.5.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(7.dp))
            OpalSegmentedRow(
                options = listOf("整机传 DeX", "应用独立分流"),
                selectedIndex = audioRouteMode.coerceIn(
                    BridgeProtocol.AudioRoute.ALL_TO_DEX,
                    BridgeProtocol.AudioRoute.SPLIT_BY_APP,
                ),
                onSelect = { mode ->
                    BridgeClient.setAudioRouteModeAsync(ctx, mode) {
                        val msg = if (mode == BridgeProtocol.AudioRoute.SPLIT_BY_APP) {
                            "已开启应用独立音频分流：手机与 DeX 各自发声"
                        } else {
                            "已切换为整机声音传至 DeX 投屏端"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            OpalSwitchRow(
                title = "允许远端麦克风回传",
                subtitle = "开启后允许投屏端（平板 / Web / Mac）将麦克风实时注入主机（Android 13+ 免 Root）",
                checked = remoteMicEnabled,
                onCheckedChange = { enabled ->
                    BridgeClient.setRemoteMediaConfigAsync(ctx, enabled, effectiveCameraEnabled) {
                        Toast.makeText(
                            ctx,
                            if (enabled) "已开启远端麦克风回传" else "已关闭远端麦克风回传",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            OpalSwitchRow(
                title = "允许 Root 摄像头映射",
                subtitle = if (!canUseRootCamera) {
                    "需 Root 权限 · 请先在「激活」中使用 Root 增强模式启动引擎后才可开启"
                } else {
                    "Root 增强已就绪 · Android 15+ 原生注册系统虚拟摄像头（桌面应用可直接调用）；低版本输出 @opaldesk_cam_feed"
                },
                checked = effectiveCameraEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !canUseRootCamera) {
                        if (remoteCameraEnabled) {
                            AppPrefs.setRemoteCameraEnabled(ctx, false)
                        }
                        Toast.makeText(
                            ctx,
                            "未获得 Root 增强权限，无法开启摄像头映射（请先使用 Root 模式激活引擎）",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@OpalSwitchRow
                    }
                    BridgeClient.setRemoteMediaConfigAsync(ctx, remoteMicEnabled, enabled) { ok ->
                        val msg = when {
                            !enabled -> "已关闭 Root 摄像头映射"
                            ok -> "已开启 Root 摄像头映射"
                            else -> "未获得 Root 增强权限，无法开启摄像头映射"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        // 6. Collapsible: Experimental USB Tethering Cast (Beta)
        OpalCollapsibleSectionCard(
            title = "实验性 USB 网络共享 (Beta)",
            subtitle = "免 USB 调试数据线直连投屏通道（默认关闭）",
            sectionKey = ConsoleSectionKeys.USB_TETHER_BETA,
            controller = navController,
            summaryText = if (usbTetherEnabled) "Beta · 已开启" else "未开启",
            summaryAccent = if (usbTetherEnabled) OpalPalette.AccentCyan else OpalPalette.TextMuted,
            defaultExpanded = false,
        ) {
            OpalSwitchRow(
                title = "启用 USB 网络共享自动识别与隔离监听",
                subtitle = "开启后在「跨设备互联」展示 USB 网络共享直连通道（免 USB 调试 / 免 ADB），支持手机仅 USB 网卡隔离监听，以及 Windows / macOS / Android 平板自动识别。",
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
            BridgeProtocol.InputBackend.SYSTEM -> "系统直通"
            BridgeProtocol.InputBackend.UHID -> "UHID"
            else -> "自动"
        }
        val effectiveQuota = status.maxVdCount.takeIf { it > 0 } ?: profileState.workingConfig.maxVdCount
        OpalCollapsibleSectionCard(
            title = "引擎底层：输入路由与虚拟屏配额",
            subtitle = "触摸输入回退策略、活跃虚拟屏并发上限与进程控制",
            sectionKey = ConsoleSectionKeys.ENGINE_LOWLEVEL,
            controller = navController,
            summaryText = "$backendShort · 配额 $effectiveQuota",
            summaryAccent = if (inputBlocked) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
            defaultExpanded = false,
        ) {
            OpalText(
                text = "默认系统直通；如遇小米 / 澎湃等机型触摸被拦截，可切换至 Linux /dev/uhid 虚拟外设。",
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
                options = listOf("自动回退 (推荐)", "IInputManager 注入", "Linux /dev/uhid"),
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
                text = "活跃虚拟屏并发上限（超出部分自动休眠为快照）",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(7.dp))
            val poolOptions = listOf(3, 4, 5, 6, 8, 10)
            val curMaxIdx = poolOptions.indexOf(effectiveQuota).let { if (it >= 0) it else 3 }
            OpalSegmentedRow(
                options = poolOptions.map { "$it 屏" },
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
                        label = "关闭所有窗口",
                        filled = false,
                        compact = true,
                        accent = OpalPalette.AccentAmber,
                        onClick = { BridgeClient.closeAllWindowsAsync(removeTasks = true) },
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "停止引擎",
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
