package io.github.lengsukq.opaldesk.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.notifications.OpalNotificationStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.DexImeCatalog
import io.github.lengsukq.opaldesk.ui.DexImeOption
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassSlider
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalSwitchRow
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.update.AppUpdateManager
import java.util.Locale

@Composable
internal fun DexAudioSettingsSection(
    bridge: BridgeClient,
    isZh: Boolean,
) {
    val ctx = LocalContext.current
    val status by bridge.statusFlow.collectAsState()
    val forcePhoneSpeaker by AppPrefs.forcePhoneSpeakerFlow.collectAsState()
    val audioRouteMode by AppPrefs.audioRouteModeFlow.collectAsState()
    val remoteMicEnabled by AppPrefs.remoteMicEnabledFlow.collectAsState()
    val remoteCameraEnabled by AppPrefs.remoteCameraEnabledFlow.collectAsState()
    val notifAccessGranted by OpalNotificationStore.accessGrantedFlow.collectAsState()

    val canUseRootCamera = status.connected && status.rootEnhanced
    val effectiveCamera = canUseRootCamera && remoteCameraEnabled

    // 1. Audio Output & Cast Mute Routing
    DexSettingsGroupCard(
        title = if (isZh) "音频输出与投屏静音" else "Audio Output & Cast Routing",
        subtitle = if (isZh) "解决便携屏无音响问题，或控制投屏时本机静音策略" else "Route audio to phone speaker or split between host and DeX",
    ) {
        OpalSwitchRow(
            title = if (isZh) "全部用本机播放（便携屏无音响）" else "Force Phone Speaker Playback",
            subtitle = if (isZh) "强制将所有外接屏与 DeX 媒体声音保留在手机扬声器/耳机输出" else "Keep all DeX & external display audio playing on the phone",
            checked = forcePhoneSpeaker,
            onCheckedChange = { enabled ->
                bridge.setForcePhoneSpeakerAsync(ctx, enabled) {
                    Toast.makeText(
                        ctx,
                        if (enabled) {
                            if (isZh) "已开启全部用本机播放" else "Phone speaker playback enabled"
                        } else {
                            if (isZh) "已恢复默认音频输出路由" else "Default audio routing restored"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        DexSettingsDivider()
        OpalText(
            text = if (isZh) "投屏音频分流模式" else "Cast Audio Routing Mode",
            fontSize = 12.5.sp,
            color = OpalPalette.TextSecondary,
        )
        OpalSegmentedRow(
            options = if (isZh) listOf("投屏时本机静音", "按应用智能分流") else listOf("Mute Host on Cast", "Split by App"),
            selectedIndex = audioRouteMode.coerceIn(
                BridgeProtocol.AudioRoute.ALL_TO_DEX,
                BridgeProtocol.AudioRoute.SPLIT_BY_APP,
            ),
            onSelect = { mode ->
                bridge.setAudioRouteModeAsync(ctx, mode) {
                    Toast.makeText(
                        ctx,
                        if (mode == BridgeProtocol.AudioRoute.ALL_TO_DEX) {
                            if (isZh) "已切换为投屏时本机静音（全部传至接收端）" else "Host muted during cast (all audio to receiver)"
                        } else {
                            if (isZh) "已切换为按应用智能分流" else "Switched to per-app split audio"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        DexSettingsDivider()
        OpalSwitchRow(
            title = if (isZh) "允许接收端麦克风回传" else "Remote Microphone Uplink",
            subtitle = if (isZh) "无线投屏时可使用电脑/平板麦克风进行语音通话或录音" else "Use receiver microphone during remote cast sessions",
            checked = remoteMicEnabled,
            onCheckedChange = { enabled ->
                bridge.setRemoteMediaConfigAsync(ctx, enabled, effectiveCamera)
            },
        )
        OpalSwitchRow(
            title = if (isZh) "虚拟摄像头回传（需 Root 增强）" else "Remote Camera Uplink (Root)",
            subtitle = if (canUseRootCamera) {
                if (isZh) "Root 增强已就绪，可桥接接收端摄像头" else "Root enhancement active for virtual camera bridge"
            } else {
                if (isZh) "当前为免 Root 模式，此项仅在 Root 增强下可用" else "Requires Root enhancement mode on host daemon"
            },
            checked = effectiveCamera,
            onCheckedChange = { enabled ->
                if (enabled && !canUseRootCamera) {
                    Toast.makeText(
                        ctx,
                        if (isZh) "需要启用 Root 增强模式才能使用虚拟摄像头" else "Root enhancement required for virtual camera",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@OpalSwitchRow
                }
                bridge.setRemoteMediaConfigAsync(ctx, remoteMicEnabled, enabled)
            },
        )
    }

    // 2. Media Control & Notification Listener Permission
    DexSettingsGroupCard(
        title = if (isZh) "媒体控制与通知使用权" else "Media Control & Notification Access",
        subtitle = if (isZh) "授权后可在 DeX 顶栏与控制中心实时同步音乐封面、进度与通知" else "Enable to sync now-playing media controls and notifications",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OpalStatusBadge(
                    label = if (notifAccessGranted) {
                        if (isZh) "通知使用权已授权" else "Notification Access Granted"
                    } else {
                        if (isZh) "未授予通知使用权" else "Notification Access Needed"
                    },
                    color = if (notifAccessGranted) OpalPalette.AccentEmerald else OpalPalette.AccentAmber,
                )
                OpalText(
                    text = if (notifAccessGranted) {
                        if (isZh) "媒体播放控制与跨设备通知同步已正常工作" else "Media session controls and notification sync are active"
                    } else {
                        if (isZh) "点击右侧按钮前往系统设置勾选 OpalDesk" else "Tap to grant Notification Access for OpalDesk in system settings"
                    },
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                OpalButton(
                    label = if (notifAccessGranted) (if (isZh) "管理权限" else "Manage") else (if (isZh) "一键授权" else "Grant Now"),
                    compact = true,
                    filled = !notifAccessGranted,
                    accent = if (notifAccessGranted) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                    onClick = { OpalNotificationStore.openNotificationAccessSettings(ctx) },
                )
                OpalButton(
                    label = if (isZh) "刷新状态" else "Refresh",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentCyan,
                    onClick = { OpalNotificationStore.refreshAccessState(ctx) },
                )
            }
        }
    }
}

@Composable
internal fun DexInputImeSettingsSection(
    bridge: BridgeClient,
    isZh: Boolean,
    onOpenKeyBindings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val imeState by bridge.imeStateFlow.collectAsState()
    val inputBindings by bridge.inputBindingsFlow.collectAsState()
    val displayMode by AppPrefs.dexImeDisplayModeFlow.collectAsState()
    val lockedImeId by AppPrefs.dexLockedImeIdFlow.collectAsState()
    val muteHostOnCast by AppPrefs.muteHostImeOnCastFlow.collectAsState()
    val hardKbdMode by AppPrefs.hardKbdSoftImeModeFlow.collectAsState()
    val autoBridgeOnCast by AppPrefs.autoBridgeImeOnCastFlow.collectAsState()
    val trackpadSensitivity by AppPrefs.trackpadSensitivityFlow.collectAsState()
    val mouseTarget by AppPrefs.mouseTargetFlow.collectAsState()
    val globalShortcuts by AppPrefs.globalShortcutsEnabledFlow.collectAsState()

    var localImes by remember { mutableStateOf<List<DexImeOption>>(emptyList()) }
    LaunchedEffect(Unit) {
        localImes = DexImeCatalog.loadLocalEnabledImes(ctx)
        bridge.refreshDexImeStateAsync()
    }
    val imeOptions = remember(localImes, imeState.enabledImeIds, lockedImeId, imeState.currentImeId, imeState.pluginImeInstalled) {
        DexImeCatalog.mergeOptions(
            localOptions = localImes,
            daemonEnabledIds = imeState.enabledImeIds,
            lockedImeId = lockedImeId,
            currentSystemImeId = imeState.currentImeId,
            pluginImeInstalled = imeState.pluginImeInstalled,
        )
    }
    val yuyanEnabled = DexImeCatalog.isYuyanPluginEnabledForDex(
        pluginInstalled = imeState.pluginImeInstalled,
        lockedImeId = lockedImeId,
        autoBridgeOnCast = autoBridgeOnCast,
        displayMode = displayMode,
    )

    // 1. DeX IME Mode & Yuyan Synergy
    DexSettingsGroupCard(
        title = if (isZh) "DeX 输入法与语燕协同" else "DeX IME & Yuyan Synergy",
        subtitle = if (isZh) "配置外接屏输入法显示位置、锁定输入法与实体键盘行为" else "Configure IME display routing, keyboard lock and hardware keyboard policy",
    ) {
        OpalText(text = if (isZh) "DeX 输入法显示模式" else "DeX IME Display Mode", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        val modeIdx = displayMode.coerceIn(
            BridgeProtocol.DexImeDisplayMode.AUTO,
            BridgeProtocol.DexImeDisplayMode.FALLBACK_HOST,
        )
        OpalSegmentedRow(
            options = if (isZh) listOf("智能跟随", "始终在 DeX 显示", "回落手机主屏") else listOf("Auto", "Always DeX", "Host Screen"),
            selectedIndex = modeIdx,
            onSelect = { nextMode ->
                bridge.setDexImeConfigAsync(ctx, displayMode = nextMode)
            },
        )
        OpalSwitchRow(
            title = if (isZh) "启用语燕输入法 DeX 协同" else "Enable Yuyan IME Synergy",
            subtitle = if (imeState.pluginImeInstalled) {
                if (isZh) "已检测到内置语燕插件，开启后在 DeX 中自动锁定并直通候选词" else "Yuyan plugin detected; auto-locks and bridges candidates in DeX"
            } else {
                if (isZh) "未检测到语燕插件，开启后将预设锁定并在安装后生效" else "Yuyan plugin not detected yet; preference will apply once installed"
            },
            checked = yuyanEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    bridge.setDexImeConfigAsync(
                        ctx = ctx,
                        lockedImeId = BridgeProtocol.OPALDESK_IME_PLUGIN_ID,
                        autoBridgeImeOnCast = true,
                    )
                } else {
                    val nextLocked = if (DexImeCatalog.isYuyanPluginIme(lockedImeId)) "" else lockedImeId
                    bridge.setDexImeConfigAsync(
                        ctx = ctx,
                        lockedImeId = nextLocked,
                        autoBridgeImeOnCast = false,
                    )
                }
            },
        )
        if (imeOptions.isNotEmpty()) {
            OpalText(text = if (isZh) "DeX 锁定输入法" else "Locked IME in DeX", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
            val displayChoices = listOf("" to (if (isZh) "不锁定（跟随系统）" else "Follow System")) +
                imeOptions.take(3).map { it.id to it.label }
            val selectedImeIdx = displayChoices.indexOfFirst { it.first == lockedImeId }.let { if (it >= 0) it else 0 }
            OpalSegmentedRow(
                options = displayChoices.map { it.second.take(8) },
                selectedIndex = selectedImeIdx,
                onSelect = { idx ->
                    bridge.setDexImeConfigAsync(ctx, lockedImeId = displayChoices[idx].first)
                },
            )
        }
        OpalSwitchRow(
            title = if (isZh) "投屏时自动接管直输通道" else "Auto-bridge IME on Cast",
            subtitle = if (isZh) "远端电脑键盘直接输入中文，无需反复切换系统输入法" else "Type smoothly from remote computer keyboard during cast",
            checked = autoBridgeOnCast,
            onCheckedChange = { enabled -> bridge.setDexImeConfigAsync(ctx, autoBridgeImeOnCast = enabled) },
        )
        OpalSwitchRow(
            title = if (isZh) "投屏时静音手机端软键盘" else "Mute Host Soft Keyboard on Cast",
            subtitle = if (isZh) "避免在无线投屏输入时在手机屏幕上频繁弹出虚拟键盘" else "Prevent soft keyboard popups on phone while casting",
            checked = muteHostOnCast,
            onCheckedChange = { enabled -> bridge.setDexImeConfigAsync(ctx, muteHostOnCast = enabled) },
        )
        OpalText(text = if (isZh) "实体键盘连接时软键盘策略" else "Soft IME with Hardware Keyboard", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        val hardKbdValues = listOf(
            BridgeProtocol.HardKbdSoftIme.SYSTEM,
            BridgeProtocol.HardKbdSoftIme.HIDE,
            BridgeProtocol.HardKbdSoftIme.SHOW,
        )
        val hardKbdIdx = hardKbdValues.indexOf(hardKbdMode).let { if (it >= 0) it else 0 }
        OpalSegmentedRow(
            options = if (isZh) listOf("跟随系统", "隐藏软键盘", "保持显示") else listOf("System", "Hide", "Show"),
            selectedIndex = hardKbdIdx,
            onSelect = { idx -> bridge.setDexImeConfigAsync(ctx, showWithHardKbd = hardKbdValues[idx]) },
        )
    }

    // 2. Key Bindings, Pointer & Trackpad
    DexSettingsGroupCard(
        title = if (isZh) "键鼠映射与触控板灵敏度" else "Key Bindings & Pointer",
        subtitle = if (isZh) "自定义全局快捷键、鼠标指针速度与焦点路由" else "Customize key/mouse bindings, pointer speed and routing",
    ) {
        DexSettingsActionRow(
            title = if (isZh) "自定义键鼠映射编辑器" else "Key & Mouse Binding Editor",
            subtitle = if (isZh) "当前已配置 ${inputBindings.size} 条映射规则" else "${inputBindings.size} custom binding rules configured",
            actionLabel = if (isZh) "打开编辑器" else "Open Editor",
            filled = true,
            onClick = {
                onDismiss()
                onOpenKeyBindings()
            },
        )
        DexSettingsDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = if (isZh) "触控板与指针灵敏度" else "Trackpad & Pointer Speed", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OpalStatusBadge(label = String.format(Locale.US, "%.2fx", trackpadSensitivity), color = OpalPalette.AccentAmber)
        }
        OpalGlassSlider(
            value = trackpadSensitivity.coerceIn(AppPrefs.MIN_TRACKPAD_SENSITIVITY, AppPrefs.MAX_TRACKPAD_SENSITIVITY),
            valueRange = AppPrefs.MIN_TRACKPAD_SENSITIVITY..AppPrefs.MAX_TRACKPAD_SENSITIVITY,
            onValueChange = { next ->
                AppPrefs.setTrackpadSensitivity(ctx, next)
                bridge.setPointerSpeedAsync(next)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OpalText(text = if (isZh) "外接鼠标默认目标屏" else "Default Mouse Target", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        OpalSegmentedRow(
            options = if (isZh) listOf("手机本机屏", "外接 DeX 屏") else listOf("Host Screen", "External DeX"),
            selectedIndex = mouseTarget.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL),
            onSelect = { AppPrefs.setMouseTarget(ctx, it) },
        )
        OpalSwitchRow(
            title = if (isZh) "启用全局工作站快捷键" else "Enable Global Workstation Shortcuts",
            subtitle = if (isZh) "支持 Alt+Tab、Win+Space 搜索、分屏等桌面快捷键" else "Allow Alt+Tab, Spotlight search and window tiling hotkeys",
            checked = globalShortcuts,
            onCheckedChange = { AppPrefs.setGlobalShortcutsEnabled(ctx, it) },
        )
    }
}

@Composable
internal fun DexWindowsSettingsSection(
    bridge: BridgeClient,
    isZh: Boolean,
) {
    val ctx = LocalContext.current
    val status by bridge.statusFlow.collectAsState()
    val profileState by ProfileConfigStore.stateFlow.collectAsState()
    val sortByUsage by AppPrefs.sortAppsByUsageFlow.collectAsState()
    val showGuideIcon by AppPrefs.showDesktopGuideIconFlow.collectAsState()

    val working = profileState.workingConfig
    val effectiveQuota = status.maxVdCount.takeIf { it > 0 } ?: working.maxVdCount

    // 1. Multitasking & Layout Memory
    DexSettingsGroupCard(
        title = if (isZh) "窗口形态与多任务" else "Window Behavior & Multitasking",
        subtitle = if (isZh) "管理退出时的窗口布局记忆、虚拟屏配额与分屏辅助" else "Configure layout retention, virtual display quota and multitasking",
    ) {
        OpalSwitchRow(
            title = if (isZh) "退出桌面时记住窗口布局" else "Keep Window Layout on Exit",
            subtitle = if (isZh) "保留各应用窗口大小与自由/分屏位置偏好" else "Remember each app's floating or tiled window bounds",
            checked = working.keepLayoutOnExit,
            onCheckedChange = { ProfileConfigStore.setWorkingKeepLayoutOnExit(it) },
        )
        OpalSwitchRow(
            title = if (isZh) "启动桌面时自动恢复上次布局" else "Auto-restore Saved Layout",
            subtitle = if (isZh) "重新进入 DeX 桌面时自动打开当前插槽已保存的窗口" else "Reopen saved windows automatically when entering DeX",
            checked = working.autoRestoreLayout,
            onCheckedChange = { ProfileConfigStore.setWorkingAutoRestoreLayout(it) },
        )
        DexSettingsDivider()
        OpalText(text = if (isZh) "并发活跃窗口配额 (VirtualDisplay)" else "Active Window Pool Quota", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        val quotaOptions = listOf(4, 6, 8, 10)
        val quotaIdx = quotaOptions.indexOf(effectiveQuota).let { if (it >= 0) it else 1 }
        OpalSegmentedRow(
            options = quotaOptions.map { if (isZh) "$it 窗口" else "$it Wins" },
            selectedIndex = quotaIdx,
            onSelect = { idx ->
                val nextQuota = quotaOptions[idx]
                ProfileConfigStore.setWorkingMaxVdCount(nextQuota)
                bridge.updateCompositorConfigAsync(maxVdCount = nextQuota)
            },
        )
        OpalText(text = if (isZh) "窗口输入注入后端" else "Window Input Backend", fontSize = 12.5.sp, color = OpalPalette.TextSecondary)
        val backendIdx = when (if (status.connected) status.inputBackendMode else working.inputBackendMode) {
            BridgeProtocol.InputBackend.SYSTEM -> 1
            BridgeProtocol.InputBackend.UHID -> 2
            else -> 0
        }
        OpalSegmentedRow(
            options = if (isZh) listOf("智能自动", "系统直通", "UHID 虚拟外设") else listOf("Auto", "System", "UHID"),
            selectedIndex = backendIdx,
            onSelect = { idx ->
                val mode = when (idx) {
                    1 -> BridgeProtocol.InputBackend.SYSTEM
                    2 -> BridgeProtocol.InputBackend.UHID
                    else -> BridgeProtocol.InputBackend.AUTO
                }
                ProfileConfigStore.setWorkingInputBackendMode(mode)
                bridge.updateCompositorConfigAsync(inputBackendMode = mode)
            },
        )
    }

    // 2. App Drawer & Quick Maintenance
    DexSettingsGroupCard(
        title = if (isZh) "桌面效率与窗口维护" else "Desktop Helpers & Maintenance",
        subtitle = if (isZh) "控制应用抽屉排序与桌面快捷图标" else "Adjust app drawer sorting and quick workspace cleanup",
    ) {
        OpalSwitchRow(
            title = if (isZh) "按使用频率智能排序应用" else "Sort Apps by Usage",
            subtitle = if (isZh) "在应用抽屉中优先展示最常用的应用" else "Pin frequently launched apps near the top",
            checked = sortByUsage,
            onCheckedChange = { AppPrefs.setSortAppsByUsage(ctx, it) },
        )
        OpalSwitchRow(
            title = if (isZh) "显示桌面新手引导图标" else "Show Desktop Guide Icon",
            subtitle = if (isZh) "在桌面保留 OpalDesk 使用指南快捷入口" else "Keep the OpalDesk feature guide icon on desktop",
            checked = showGuideIcon,
            onCheckedChange = { AppPrefs.setDesktopGuideIconVisible(ctx, it) },
        )
        if (status.connected) {
            DexSettingsDivider()
            DexSettingsActionRow(
                title = if (isZh) "清理当前桌面窗口" else "Close All Open Windows",
                subtitle = if (isZh) "当前共有 ${status.windowCount} 个打开的窗口" else "${status.windowCount} open windows right now",
                actionLabel = if (isZh) "关闭全部窗口" else "Close All",
                accent = OpalPalette.AccentAmber,
                onClick = { bridge.closeAllWindowsAsync(removeTasks = false) },
            )
        }
    }
}

@Composable
internal fun DexSystemDiagSettingsSection(
    bridge: BridgeClient,
    isZh: Boolean,
    onOpenFaq: () -> Unit,
    onUploadLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val status by bridge.statusFlow.collectAsState()
    val versionName = remember(ctx) { AppUpdateManager.getCurrentVersionName(ctx) }
    var autoShizuku by remember { mutableStateOf(AppPrefs.isAutoShizukuEnabled(ctx)) }
    var autoRoot by remember { mutableStateOf(AppPrefs.isAutoRootEnabled(ctx)) }

    // 1. Shizuku / Daemon Runtime Status
    DexSettingsGroupCard(
        title = if (isZh) "Shizuku / 守护进程状态" else "Shizuku & Daemon Status",
        subtitle = if (isZh) "查看主机合成器运行状态与自动激活偏好" else "Inspect host compositor health and activation preferences",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalStatusBadge(
                label = if (status.connected) {
                    if (isZh) "引擎运行中" else "Engine Running"
                } else {
                    if (isZh) "守护进程离线" else "Daemon Offline"
                },
                color = if (status.connected) OpalPalette.AccentEmerald else OpalPalette.AccentAmber,
            )
            OpalButton(
                label = if (isZh) "刷新状态" else "Refresh",
                compact = true,
                filled = false,
                accent = OpalPalette.AccentCyan,
                onClick = {
                    Thread({
                        bridge.refreshStatus()
                        bridge.refreshRemoteStatus()
                    }, "DexSettingsStatusRefresh").start()
                },
            )
        }
        OpalText(text = status.privilegeSummary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        OpalText(
            text = if (status.connected) {
                val base = "${status.canvasWidth}×${status.canvasHeight} · ${status.canvasDpi}dpi · VD ${status.activeVdCount}/${status.maxVdCount}"
                "$base · ${status.injectBackendLabel}"
            } else {
                if (isZh) "请前往控制台通过 Shizuku、无线调试或 Root 激活引擎" else "Activate the engine from Console via Shizuku, Wireless ADB or Root"
            },
            fontSize = 11.5.sp,
            color = OpalPalette.TextSecondary,
        )
        DexSettingsDivider()
        OpalSwitchRow(
            title = if (isZh) "启动时自动通过 Shizuku 激活" else "Auto-activate via Shizuku",
            subtitle = if (isZh) "检测到 Shizuku 已授权时自动拉起主机守护进程" else "Start host daemon automatically when Shizuku is ready",
            checked = autoShizuku,
            onCheckedChange = { enabled ->
                autoShizuku = enabled
                AppPrefs.setAutoShizukuEnabled(ctx, enabled)
            },
        )
        OpalSwitchRow(
            title = if (isZh) "启动时自动尝试 Root 激活" else "Auto-activate via Root",
            subtitle = if (isZh) "仅在已授予 Root 权限的设备上启用" else "Only for devices with Root permission granted",
            checked = autoRoot,
            onCheckedChange = { enabled ->
                autoRoot = enabled
                AppPrefs.setAutoRootEnabled(ctx, enabled)
            },
        )
    }

    // 2. Diagnostics, FAQ & Version Info
    DexSettingsGroupCard(
        title = if (isZh) "诊断日志、帮助与版本" else "Diagnostics, FAQ & Version",
        subtitle = if (isZh) "遇到黑屏、输入或投屏问题时可一键上报诊断信息" else "Upload diagnostics logs or browse troubleshooting FAQ",
    ) {
        DexSettingsActionRow(
            title = if (isZh) "一键上传诊断日志" else "Upload Diagnostic Logs",
            subtitle = if (isZh) "打包脱敏后的主机与守护进程日志用于快速定位问题" else "Package sanitized host & daemon logs for troubleshooting",
            actionLabel = if (isZh) "上传日志" else "Upload Logs",
            filled = true,
            accent = OpalPalette.AccentBlue,
            onClick = {
                onDismiss()
                onUploadLogs()
            },
        )
        DexSettingsDivider()
        DexSettingsActionRow(
            title = if (isZh) "常见问题 FAQ" else "Frequently Asked Questions",
            subtitle = if (isZh) "查看外接屏、输入法、触控板与投屏排障指南" else "Browse guides for external display, IME, trackpad and casting",
            actionLabel = if (isZh) "打开 FAQ" else "Open FAQ",
            accent = OpalPalette.AccentEmerald,
            onClick = {
                onDismiss()
                onOpenFaq()
            },
        )
        DexSettingsDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OpalText(text = if (isZh) "OpalDesk 版本信息" else "OpalDesk Version", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                OpalText(
                    text = if (isZh) "协议版本 v${if (status.protocolVersion > 0) status.protocolVersion else BridgeProtocol.PROTOCOL_VERSION}" else "Protocol v${if (status.protocolVersion > 0) status.protocolVersion else BridgeProtocol.PROTOCOL_VERSION}",
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                )
            }
            OpalStatusBadge(label = "v$versionName", color = OpalPalette.AccentCyan)
        }
    }
}
