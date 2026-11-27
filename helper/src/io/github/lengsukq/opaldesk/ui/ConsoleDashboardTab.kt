package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus

/**
 * Tab 0: Workstation Home — focal Hero Stage + 2-column Quick Bento + Compact Interconnect card
 * (active sessions & discovered peers stay immediately visible while secondary manual IP, Web QR,
 * and trusted peers tuck cleanly into collapsible sub-rows) + Quick Slot Switcher.
 */
@Composable
fun ConsoleDashboardTab(
    status: DaemonStatus,
    scene: CompositorScene,
    remote: RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    detectedExtDisplays: List<BridgeService.ExternalDisplayInfo> = emptyList(),
    needsLocalNetworkPerm: Boolean = false,
    onRequestLocalNetworkPerm: () -> Unit = {},
    onEnterLocalDesktop: () -> Unit,
    onEnterLandscapeMirrorDesktop: () -> Unit = onEnterLocalDesktop,
    onOpenRemoteTrackpad: () -> Unit,
    onOpenExternalOrTrackpad: (Int) -> Unit,
    onProjectSpecificDisplay: (Int, ExternalDisplayTransport, Boolean) -> Unit = { id, _, _ ->
        onOpenExternalOrTrackpad(id)
    },
    onConnectPeerCast: (String, Int, String) -> Unit,
    onOpenFloatingAppPicker: (String, Int, String) -> Unit,
    onOpenActivation: () -> Unit,
    onRequestCompleteExit: () -> Unit,
    navController: ConsoleNavigationController? = null,
) {
    val ctx = LocalContext.current
    val extSession by ExternalTrackpadBridge.sessionFlow.collectAsState()
    val peerRole by OpalPeerCastMutex.roleFlow.collectAsState()
    val discoveredPeers by OpalPeerDiscovery.discoveredPeersFlow.collectAsState()
    val trustedPeers by BridgeClient.trustedPeersFlow.collectAsState()
    val activeFloating by PeerFloatingAppService.activeFlow.collectAsState()
    val usbTetherEnabled by AppPrefs.usbTetherCastEnabledFlow.collectAsState()
    val usbTetherPeers by UsbTetherPeerDetector.peersFlow.collectAsState()
    val usbTetherScanning by UsbTetherPeerDetector.scanningFlow.collectAsState()

    var castModeTab by remember { mutableIntStateOf(if (remote.wifiEnabled || remote.connected) 1 else 0) }
    var wifiBusy by remember { mutableStateOf(false) }

    // Ensure Spotlight jumps to sub-sections automatically switch to the right Interconnect sub-tab
    LaunchedEffect(navController?.highlightedSectionKey, navController?.highlightPulseSeq) {
        when (navController?.highlightedSectionKey) {
            ConsoleSectionKeys.MANUAL_CONNECT -> castModeTab = 0
            ConsoleSectionKeys.TRUSTED_PEERS -> castModeTab = 1
        }
    }
    LaunchedEffect(navController?.expandedSections) {
        val exp = navController?.expandedSections ?: return@LaunchedEffect
        if (ConsoleSectionKeys.MANUAL_CONNECT in exp && castModeTab != 0 &&
            navController.highlightedSectionKey == ConsoleSectionKeys.MANUAL_CONNECT
        ) {
            castModeTab = 0
        } else if (ConsoleSectionKeys.TRUSTED_PEERS in exp && castModeTab != 1 &&
            navController.highlightedSectionKey == ConsoleSectionKeys.TRUSTED_PEERS
        ) {
            castModeTab = 1
        }
    }

    val isDark = OpalPalette.isDark
    val physicalExtActive = extSession.isPhysicalActive
    val hasPhysicalExt = extDisplay != null || physicalExtActive
    val activeExtDisplayId = extSession.displayId.takeIf { it > 0 } ?: (extDisplay?.displayId ?: -1)
    val activeExtSummary = extSession.summary.ifBlank {
        extDisplay?.let { "${it.name} · ${it.width}×${it.height} @ ${it.refreshRateHz}Hz" }.orEmpty()
    }

    fun toggleWifiShare() {
        if (!status.connected) {
            onOpenActivation()
            return
        }
        if (wifiBusy) return
        val enable = !remote.isLanShareActive
        if (enable) {
            val check = OpalPeerCastMutex.validateEnableSending(peerRole.isReceiving, peerRole.peerName)
            if (!check.allowed) {
                Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                return
            }
        }
        wifiBusy = true
        BridgeClient.setWifiStreamEnabledAsync(enable, DEFAULT_OPAL_CAST_PORT, BridgeProtocol.TlsBindScope.ALL) {
            wifiBusy = false
            val latest = BridgeClient.remoteStatusFlow.value
            OpalPeerDiscovery.syncAdvertise(ctx, latest.isLanShareActive, latest.wifiPort, latest.shortCertFingerprint)
            if (latest.wifiEnabled) castModeTab = 1
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Luminous Spatial Hero Stage Card
        val heroAccent = when {
            !status.connected -> OpalPalette.AccentAmber
            remote.connected -> OpalPalette.AccentEmerald
            else -> OpalPalette.AccentBlue
        }
        val heroShape = RoundedCornerShape(26.dp)
        val heroBgBrush = Brush.linearGradient(
            colors = if (isDark) {
                listOf(heroAccent.copy(alpha = 0.24f), Color(0xE6171E2E), Color(0xEC111622))
            } else {
                listOf(heroAccent.copy(alpha = 0.16f), Color(0xF2FFFFFF), Color(0xE8F6F9FF))
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .consoleSearchAnchor(navController, ConsoleSectionKeys.HERO, shape = heroShape)
                .liquidGlassSurface(
                    shape = heroShape,
                    fill = heroBgBrush,
                    rim = Brush.verticalGradient(listOf(heroAccent.copy(alpha = 0.65f), heroAccent.copy(alpha = 0.16f))),
                    elevation = if (isDark) 16.dp else 12.dp,
                )
                .padding(18.dp),
        ) {
            when {
                !status.connected -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OpalText(text = "开启多窗桌面", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            OpalText(
                                text = "激活引擎后即可进入桌面、满屏外接或无线投屏",
                                fontSize = 12.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        DashboardHeroIconBadge(accent = heroAccent, active = false)
                    }
                    Spacer(Modifier.height(14.dp))
                    OpalButton(
                        label = "激活多窗引擎",
                        onClick = onOpenActivation,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                remote.connected -> {
                    val fps = (remote.fpsX100 / 100).coerceAtLeast(30)
                    val peerName = remote.peerName.ifBlank { "对端设备" }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(OpalPalette.AccentEmerald),
                                )
                                OpalText(
                                    text = if (remote.hasActiveDesktopCast) {
                                        "正在${remote.channelLabel}投屏"
                                    } else {
                                        "独立应用小窗投射中"
                                    },
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            OpalText(
                                text = "$peerName · ${remote.width}×${remote.height} @ ${fps}Hz",
                                fontSize = 12.sp,
                                color = OpalPalette.AccentEmerald,
                            )
                        }
                        if (remote.activeSessionPermanent) {
                            OpalStatusBadge(label = "永久信任", color = OpalPalette.AccentEmerald)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    if (remote.hasActiveDesktopCast) {
                        OpalButton(
                            label = "打开本机触控板",
                            accent = OpalPalette.AccentBlue,
                            onClick = onOpenRemoteTrackpad,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(9.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OpalButton(
                            label = if (status.hostScreenOff) "点亮本机屏幕" else "物理息屏省电",
                            compact = true,
                            filled = status.hostScreenOff,
                            accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                            onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff) },
                            modifier = Modifier.weight(1f),
                        )
                        OpalButton(
                            label = "断开投屏",
                            compact = true,
                            filled = false,
                            accent = OpalPalette.AccentDanger,
                            onClick = { BridgeClient.disconnectRemoteStreamAsync() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OpalText(
                        text = "投屏期间已锁定本机 DeX 桌面，避免双端画面冲突",
                        fontSize = 11.sp,
                        color = OpalPalette.TextMuted,
                    )
                }

                physicalExtActive -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(OpalPalette.AccentBlue),
                                )
                                OpalText(
                                    text = if (extSession.isWirelessDisplay) "无线电视工作站运行中" else "外接屏工作站运行中",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            OpalText(
                                text = activeExtSummary.ifBlank {
                                    if (extSession.isWirelessDisplay) "无线电视副屏已连接" else "物理外接显示器已连接"
                                },
                                fontSize = 12.sp,
                                color = OpalPalette.AccentCyan,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    OpalButton(
                        label = "打开本机触控板",
                        onClick = { onOpenExternalOrTrackpad(activeExtDisplayId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OpalButton(
                            label = if (status.hostScreenOff) "点亮本机屏幕" else "物理息屏省电",
                            compact = true,
                            filled = status.hostScreenOff,
                            accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                            onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff) },
                            modifier = Modifier.weight(1f),
                        )
                        OpalButton(
                            label = "进入本机桌面",
                            compact = true,
                            filled = false,
                            onClick = onEnterLocalDesktop,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (extSession.isWirelessDisplay) {
                        Spacer(Modifier.height(7.dp))
                        OpalText(
                            text = "无线投屏期间建议在触控板顶部使用「暗屏触控」，避免物理息屏导致部分机型断连",
                            fontSize = 11.sp,
                            color = OpalPalette.TextMuted,
                        )
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OpalText(text = "多窗桌面", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                if (scene.windows.isNotEmpty()) {
                                    OpalStatusBadge(label = "${scene.windows.size} 窗口", color = OpalPalette.AccentCyan)
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            OpalText(
                                text = if (scene.windows.isNotEmpty()) {
                                    "工作站运行中 · 点击随时回到桌面"
                                } else {
                                    "引擎已就绪 · 一键进入本机 DeX 桌面"
                                },
                                fontSize = 12.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        DashboardHeroIconBadge(accent = heroAccent, active = true)
                    }

                    if (status.hostScreenOff) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(OpalPalette.AccentEmerald.copy(alpha = 0.14f))
                                .border(0.8.dp, OpalPalette.AccentEmerald.copy(alpha = 0.36f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(
                                text = "本机物理屏幕处于息屏状态",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = OpalPalette.AccentEmerald,
                            )
                            OpalButton(
                                label = "点亮屏幕",
                                compact = true,
                                accent = OpalPalette.AccentEmerald,
                                onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = true) },
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OpalButton(
                            label = "进入本机 DeX 桌面",
                            onClick = onEnterLocalDesktop,
                            modifier = Modifier.weight(1.25f),
                        )
                        if (extDisplay != null) {
                            OpalButton(
                                label = if (extDisplay.isWirelessDisplay) "投射电视 DeX" else "外接屏满屏",
                                accent = OpalPalette.AccentEmerald,
                                onClick = { onOpenExternalOrTrackpad(extDisplay.displayId) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    if (scene.windows.isNotEmpty()) {
                        Spacer(Modifier.height(9.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OpalButton(
                                label = "显示桌面",
                                compact = true,
                                filled = false,
                                onClick = {
                                    scene.visibleWindows.forEach { win ->
                                        BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            OpalButton(
                                label = "关闭全部窗口",
                                compact = true,
                                filled = false,
                                accent = OpalPalette.AccentAmber,
                                onClick = { BridgeClient.closeAllWindowsAsync(removeTasks = true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // 2. Two-Column Consumer Bento Quick Tiles (External Display + Wireless Share)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .consoleSearchAnchor(navController, ConsoleSectionKeys.QUICK_TILES, shape = RoundedCornerShape(22.dp)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val isWirelessExt = extSession.isWirelessDisplay || (extDisplay?.isWirelessDisplay == true)
            DashboardBentoTile(
                title = when {
                    isWirelessExt -> "无线电视投屏"
                    hasPhysicalExt -> "外接显示器"
                    else -> "外接 / 电视投屏"
                },
                subtitle = when {
                    physicalExtActive -> "工作站运行中 · 点开触控板"
                    extDisplay != null && extDisplay.isWirelessDisplay -> "${extDisplay.name} · 点投电视 DeX"
                    extDisplay != null -> "${extDisplay.width}×${extDisplay.height} · 点击满屏"
                    else -> "USB-C 或系统无线投电视"
                },
                badgeText = if (hasPhysicalExt) "已连接" else "去投屏",
                accent = if (hasPhysicalExt) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                active = hasPhysicalExt,
                iconKind = 0,
                onClick = {
                    if (hasPhysicalExt && status.connected) {
                        onOpenExternalOrTrackpad(activeExtDisplayId)
                    } else if (!status.connected) {
                        onOpenActivation()
                    } else {
                        WirelessDisplayHelper.openSystemWirelessCastSettings(ctx)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            val sharingOn = status.connected && remote.isLanShareActive && !peerRole.isReceiving
            val usbOnlyOn = usbTetherEnabled && status.connected && remote.isUsbTetherOnlyMode && !peerRole.isReceiving
            DashboardBentoTile(
                title = "无线共享",
                subtitle = when {
                    peerRole.isReceiving -> "正在接收其他设备"
                    sharingOn -> if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:${if (remote.wifiPort > 0) remote.wifiPort else DEFAULT_OPAL_CAST_PORT}" else "服务已开启"
                    usbOnlyOn -> "USB 共享中 (Beta)"
                    else -> "局域网免 PIN 投给电脑/平板"
                },
                badgeText = when {
                    peerRole.isReceiving -> "接收中"
                    sharingOn -> "已开启"
                    usbOnlyOn -> "USB中"
                    else -> "未开启"
                },
                accent = if (sharingOn || usbOnlyOn) OpalPalette.AccentEmerald else OpalPalette.AccentCyan,
                active = sharingOn || usbOnlyOn,
                iconKind = 1,
                onClick = ::toggleWifiShare,
                modifier = Modifier.weight(1f),
            )
        }

        // Urgent touch-blocked callout ONLY shown on Home if input injection is actually blocked
        if (status.connected && status.injectStatus == BridgeProtocol.InputBackend.STATUS_BLOCKED) {
            TouchInputTroubleshootingTipCard(
                ctx = ctx,
                inputBlocked = true,
            )
        }

        // 3. Compact Cross-Device Interconnect Card
        ConsoleInterconnectCard(
            status = status,
            remote = remote,
            extDisplay = extDisplay,
            detectedExtDisplays = detectedExtDisplays,
            peerRole = peerRole,
            discoveredPeers = discoveredPeers,
            trustedPeers = trustedPeers,
            activeFloating = activeFloating,
            usbTetherEnabled = usbTetherEnabled,
            usbTetherPeers = usbTetherPeers,
            usbTetherScanning = usbTetherScanning,
            needsLocalNetworkPerm = needsLocalNetworkPerm,
            castModeTab = castModeTab,
            onSelectCastModeTab = { castModeTab = it },
            wifiBusy = wifiBusy,
            onSetWifiBusy = { wifiBusy = it },
            navController = navController,
            onRequestLocalNetworkPerm = onRequestLocalNetworkPerm,
            onEnterLandscapeMirrorDesktop = onEnterLandscapeMirrorDesktop,
            onProjectSpecificDisplay = onProjectSpecificDisplay,
            onConnectPeerCast = onConnectPeerCast,
            onOpenFloatingAppPicker = onOpenFloatingAppPicker,
            onOpenActivation = onOpenActivation,
        )

        // 4. Compact Quick Slot Switcher Card
        MacGlassSectionCard(
            modifier = Modifier.consoleSearchAnchor(navController, ConsoleSectionKeys.SLOTS_QUICK),
            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 12.dp),
        ) {
            QuickSlotSwitcherStrip(
                compact = true,
                showRestoreButton = status.connected,
                canvasW = status.canvasWidth.coerceAtLeast(1080),
                canvasH = status.canvasHeight.coerceAtLeast(720),
            )
        }

        // 5. Whisper-Quiet Single-Line Upgrade Re-activation Tip
        UpgradeReactivateTipCard(
            connected = status.connected,
            onOpenActivation = onOpenActivation,
        )

        // 6. Minimalist Footer Exit Action
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(OpalPalette.AccentDanger.copy(alpha = if (isDark) 0.14f else 0.08f))
                    .border(0.8.dp, OpalPalette.AccentDanger.copy(alpha = 0.34f), CircleShape)
                    .clickable(onClick = onRequestCompleteExit)
                    .padding(horizontal = 24.dp, vertical = 9.dp),
            ) {
                OpalText(
                    text = "完全退出 OpalDesk",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentDanger,
                )
            }
            OpalText(
                text = "先停止引擎守护进程，再彻底退出应用",
                fontSize = 11.sp,
                color = OpalPalette.TextMuted,
            )
        }
    }
}
