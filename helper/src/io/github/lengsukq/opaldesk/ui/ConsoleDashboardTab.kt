package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.cast.UsbTetherGatewayPolicy
import io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.web.WebActivationPolicy

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
    var localManualConnectExpanded by remember { mutableStateOf(false) }
    var localTrustedPeersExpanded by remember { mutableStateOf(false) }
    var showWebCastQr by remember { mutableStateOf(false) }
    var wifiBusy by remember { mutableStateOf(false) }
    var peerHostText by remember { mutableStateOf("") }
    var peerLabelText by remember { mutableStateOf("") }

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

    val manualConnectExpanded = navController?.isExpanded(ConsoleSectionKeys.MANUAL_CONNECT, false)
        ?: localManualConnectExpanded
    val trustedPeersExpanded = navController?.isExpanded(ConsoleSectionKeys.TRUSTED_PEERS, false)
        ?: localTrustedPeersExpanded

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
        BridgeClient.setWifiStreamEnabledAsync(enable, 19878, BridgeProtocol.TlsBindScope.ALL) {
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
                    sharingOn -> if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:${if (remote.wifiPort > 0) remote.wifiPort else 19878}" else "服务已开启"
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
        MacGlassSectionCard(
            modifier = Modifier.consoleSearchAnchor(navController, ConsoleSectionKeys.INTERCONNECT),
            accentBorder = when {
                peerRole.isReceiving || activeFloating != null -> OpalPalette.AccentBlue
                remote.wifiEnabled -> OpalPalette.AccentEmerald.copy(alpha = 0.45f)
                else -> null
            },
            contentPadding = PaddingValues(15.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "跨设备互联", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                when {
                    remote.connected -> OpalStatusBadge(label = remote.channelLabel, color = OpalPalette.AccentEmerald)
                    peerRole.isReceiving -> OpalStatusBadge(label = "接收中", color = OpalPalette.AccentBlue)
                    remote.isUsbTetherOnlyMode -> OpalStatusBadge(label = "USB 共享 · Beta", color = OpalPalette.AccentCyan)
                    remote.isLanShareActive -> OpalStatusBadge(label = "无线共享已开", color = OpalPalette.AccentEmerald)
                    discoveredPeers.isNotEmpty() -> OpalStatusBadge(label = "发现 ${discoveredPeers.size} 台", color = OpalPalette.AccentCyan)
                }
            }

            if (activeFloating != null) {
                val fl = activeFloating!!
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.AccentBlue.copy(alpha = 0.13f))
                        .border(0.8.dp, OpalPalette.AccentBlue.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OpalText(
                            text = "小窗运行中：${fl.appLabel}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.AccentBlue,
                            maxLines = 1,
                        )
                        OpalText(
                            text = "来自 ${fl.peerName}",
                            fontSize = 11.sp,
                            color = OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OpalButton(
                        label = "关闭",
                        accent = OpalPalette.AccentDanger,
                        filled = false,
                        compact = true,
                        onClick = { PeerFloatingAppService.stop(ctx) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OpalSegmentedRow(
                options = listOf("连接设备", "共享本机", "投电视"),
                selectedIndex = castModeTab.coerceIn(0, 2),
                onSelect = { castModeTab = it },
            )

            Spacer(Modifier.height(11.dp))
            if (castModeTab == 0) {
                // Beta opt-in: USB Tethering auto-discovered phone peers (hidden when disabled)
                if (usbTetherEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.ChipFill)
                            .border(0.8.dp, OpalPalette.AccentCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OpalText(text = "USB 网络共享直连", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                OpalStatusBadge(label = "Beta", color = OpalPalette.AccentAmber)
                            }
                            OpalButton(
                                label = if (usbTetherScanning) "探测中…" else "刷新",
                                compact = true,
                                filled = false,
                                enabled = !usbTetherScanning,
                                accent = OpalPalette.AccentCyan,
                                onClick = { UsbTetherPeerDetector.triggerScanAsync() },
                            )
                        }
                        if (usbTetherPeers.isNotEmpty()) {
                            usbTetherPeers.forEach { peer ->
                                val addr = "${peer.host}:${peer.port}"
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(OpalPalette.GlassPopover.copy(alpha = 0.6f))
                                        .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 11.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    OpalText(
                                        text = "${peer.displayName} · ${peer.interfaceName}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    OpalText(
                                        text = if (peer.shortFingerprint.isNotEmpty()) "$addr · ${peer.shortFingerprint}" else addr,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OpalButton(
                                            label = if (remote.connected) "投屏锁定中" else "投屏桌面",
                                            accent = OpalPalette.AccentEmerald,
                                            compact = true,
                                            enabled = !remote.connected,
                                            onClick = { onConnectPeerCast(peer.host, peer.port, peer.displayName) },
                                            modifier = Modifier.weight(1f),
                                        )
                                        OpalButton(
                                            label = "应用小窗",
                                            accent = OpalPalette.AccentBlue,
                                            filled = false,
                                            compact = true,
                                            enabled = !remote.connected,
                                            onClick = { onOpenFloatingAppPicker(peer.host, peer.port, peer.displayName) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        } else {
                            OpalText(
                                text = "用数据线连接手机并在对方开启系统「USB 网络共享」与共享服务后点击刷新自动识别。${UsbTetherGatewayPolicy.BETA_DISCLAIMER}",
                                fontSize = 11.sp,
                                color = OpalPalette.TextSecondary,
                                lineHeight = 15.5.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                }

                // Mode 0: Active LAN discovered peers stay directly visible
                if (discoveredPeers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        discoveredPeers.forEach { peer ->
                            val addr = "${peer.host}:${peer.port}"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(OpalPalette.ChipFill)
                                    .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column {
                                    OpalText(
                                        text = peer.displayName,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    OpalText(
                                        text = if (peer.shortFingerprint.isNotEmpty()) "$addr · ${peer.shortFingerprint}" else addr,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OpalButton(
                                        label = if (remote.connected) "投屏锁定中" else "投屏桌面",
                                        accent = OpalPalette.AccentEmerald,
                                        compact = true,
                                        enabled = !remote.connected,
                                        onClick = { onConnectPeerCast(peer.host, peer.port, peer.displayName) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    OpalButton(
                                        label = "应用小窗",
                                        accent = OpalPalette.AccentBlue,
                                        filled = false,
                                        compact = true,
                                        enabled = !remote.connected,
                                        onClick = { onOpenFloatingAppPicker(peer.host, peer.port, peer.displayName) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                } else if (needsLocalNetworkPerm) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(OpalPalette.ChipFill)
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OpalText(
                            text = "授权本地网络权限后可自动发现同局域网设备",
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        OpalButton(
                            label = "开启发现",
                            compact = true,
                            onClick = onRequestLocalNetworkPerm,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(OpalPalette.ChipFill)
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                    ) {
                        OpalText(
                            text = if (remote.connected) {
                                "本机正在向外投屏，单向防循环已暂时锁定接收端"
                            } else {
                                "正在自动发现同一局域网内已开启「共享本机」的设备…"
                            },
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                        )
                    }
                }

                // Compact collapsible sub-row for Manual IP Connect
                Spacer(Modifier.height(9.dp))
                val manualShape = RoundedCornerShape(13.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .consoleSearchAnchor(navController, ConsoleSectionKeys.MANUAL_CONNECT, shape = manualShape)
                        .clip(manualShape)
                        .background(OpalPalette.ChipFill)
                        .border(0.75.dp, OpalPalette.BorderHairline, manualShape),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = !manualConnectExpanded
                                if (navController != null) {
                                    navController.setExpanded(ConsoleSectionKeys.MANUAL_CONNECT, next)
                                } else {
                                    localManualConnectExpanded = next
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OpalText(
                            text = "手动输入 IP 地址连接",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.AccentBlue,
                        )
                        OpalText(
                            text = if (manualConnectExpanded) "收起 ▴" else "展开 ▾",
                            fontSize = 11.sp,
                            color = OpalPalette.TextSecondary,
                        )
                    }
                    AnimatedVisibility(
                        visible = manualConnectExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OpalTextInput(
                                value = peerHostText,
                                onValueChange = { peerHostText = it },
                                placeholder = "输入对方 IP:端口 (如 192.168.1.8:19878)",
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OpalButton(
                                    label = if (remote.connected) "投屏锁定中" else "投屏桌面",
                                    enabled = !remote.connected && peerHostText.isNotBlank(),
                                    compact = true,
                                    accent = OpalPalette.AccentEmerald,
                                    onClick = {
                                        val raw = peerHostText.trim()
                                        val colon = raw.lastIndexOf(':')
                                        val h = if (colon > 0) raw.substring(0, colon).trim() else raw
                                        val p = if (colon > 0) raw.substring(colon + 1).trim().toIntOrNull() ?: 19878 else 19878
                                        onConnectPeerCast(h, p, peerLabelText.ifBlank { h })
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                OpalButton(
                                    label = "应用小窗",
                                    enabled = !remote.connected && peerHostText.isNotBlank(),
                                    compact = true,
                                    filled = false,
                                    accent = OpalPalette.AccentBlue,
                                    onClick = {
                                        val raw = peerHostText.trim()
                                        val colon = raw.lastIndexOf(':')
                                        val h = if (colon > 0) raw.substring(0, colon).trim() else raw
                                        val p = if (colon > 0) raw.substring(colon + 1).trim().toIntOrNull() ?: 19878 else 19878
                                        onOpenFloatingAppPicker(h, p, peerLabelText.ifBlank { h })
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            } else if (castModeTab == 1) {
                // Mode 1: Share this device to Mac or another Android device
                OpalSwitchRow(
                    title = "允许局域网设备连接本机",
                    subtitle = when {
                        peerRole.isReceiving -> "正在接收其他设备画面，已暂时锁定共享"
                        remote.isLanShareActive -> "TLS 加密服务运行中 · 免 PIN 实时弹窗确认授权"
                        remote.isUsbTetherOnlyMode -> "当前处于仅 USB 共享监听模式；开启此项将同时开放局域网 Wi-Fi"
                        else -> "有线 USB 连接 Mac 无需开启；无线连接时打开此开关"
                    },
                    checked = remote.isLanShareActive && !peerRole.isReceiving,
                    onCheckedChange = { enable ->
                        if (!status.connected || wifiBusy) return@OpalSwitchRow
                        if (enable) {
                            val check = OpalPeerCastMutex.validateEnableSending(peerRole.isReceiving, peerRole.peerName)
                            if (!check.allowed) {
                                Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                                return@OpalSwitchRow
                            }
                        }
                        wifiBusy = true
                        BridgeClient.setWifiStreamEnabledAsync(enable, 19878, BridgeProtocol.TlsBindScope.ALL) {
                            wifiBusy = false
                            val latest = BridgeClient.remoteStatusFlow.value
                            OpalPeerDiscovery.syncAdvertise(ctx, latest.isLanShareActive, latest.wifiPort, latest.shortCertFingerprint)
                        }
                    },
                )

                if (status.connected && remote.isLanShareActive && !peerRole.isReceiving) {
                    val port = if (remote.wifiPort > 0) remote.wifiPort else 19878
                    val endpoint = if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:$port" else ":$port"
                    val webCastUrl = if (remote.lanIp.isNotEmpty()) {
                        WebActivationPolicy.buildWebCastUrl(remote.lanIp, port)
                    } else {
                        WebActivationPolicy.DEFAULT_WEB_CAST_URL
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.ChipFill)
                            .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(14.dp))
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        CopyableInfoLine(ctx, "局域网地址", endpoint)
                        if (remote.shortCertFingerprint.isNotEmpty()) {
                            CopyableInfoLine(ctx, "TLS 证书短指纹", remote.shortCertFingerprint)
                        }
                        CopyableInfoLine(ctx, "Web 浏览器投屏链接", webCastUrl)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showWebCastQr = !showWebCastQr }
                                .padding(vertical = 3.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(
                                text = "Web 扫码投屏二维码",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = OpalPalette.AccentBlue,
                            )
                            OpalText(
                                text = if (showWebCastQr) "收起 ▴" else "展开 ▾",
                                fontSize = 11.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                        AnimatedVisibility(
                            visible = showWebCastQr,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OpalQrCodeCard(content = webCastUrl, qrSize = 160.dp)
                                }
                                OpalText(
                                    text = "使用电脑 / 平板 Chrome 或 Edge 扫码打开 GitHub Pages 即可直连投屏（免安装客户端）",
                                    fontSize = 11.sp,
                                    color = OpalPalette.TextMuted,
                                )
                            }
                        }
                    }
                }

                // Beta opt-in: USB Tethering isolated channel card (completely hidden when disabled)
                if (usbTetherEnabled && status.connected && !peerRole.isReceiving) {
                    val usbState = UsbTetherGatewayPolicy.resolveSenderShareState(remote, featureEnabled = true)
                    val port = if (remote.wifiPort > 0) remote.wifiPort else 19878
                    Spacer(Modifier.height(9.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.ChipFill)
                            .border(
                                0.85.dp,
                                if (usbState == UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE) {
                                    OpalPalette.AccentCyan.copy(alpha = 0.55f)
                                } else {
                                    OpalPalette.BorderSubtle
                                },
                                RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OpalText(text = "USB 网络共享通道", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                OpalStatusBadge(label = "Beta", color = OpalPalette.AccentAmber)
                            }
                            val statusLabel = when (usbState) {
                                UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE -> "仅 USB 监听中"
                                UsbTetherGatewayPolicy.SenderShareState.REUSING_LAN_SHARE -> "复用共享中"
                                UsbTetherGatewayPolicy.SenderShareState.USB_READY_IDLE -> "网卡已就绪"
                                else -> "未检测到网卡"
                            }
                            val statusColor = when (usbState) {
                                UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE,
                                UsbTetherGatewayPolicy.SenderShareState.REUSING_LAN_SHARE,
                                -> OpalPalette.AccentEmerald
                                UsbTetherGatewayPolicy.SenderShareState.USB_READY_IDLE -> OpalPalette.AccentCyan
                                else -> OpalPalette.TextMuted
                            }
                            OpalStatusBadge(label = statusLabel, color = statusColor)
                        }
                        OpalText(
                            text = UsbTetherGatewayPolicy.BETA_DISCLAIMER,
                            fontSize = 11.sp,
                            color = OpalPalette.TextMuted,
                            lineHeight = 15.sp,
                        )

                        if (usbState == UsbTetherGatewayPolicy.SenderShareState.NO_USB_INTERFACE) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OpalButton(
                                    label = "去系统开启 USB 共享",
                                    compact = true,
                                    filled = false,
                                    accent = OpalPalette.AccentBlue,
                                    onClick = { openTetherSettings(ctx) },
                                    modifier = Modifier.weight(1.3f),
                                )
                                OpalButton(
                                    label = "刷新检测",
                                    compact = true,
                                    filled = false,
                                    accent = OpalPalette.AccentCyan,
                                    onClick = {
                                        Thread({ BridgeClient.refreshRemoteStatus() }, "OpalUsbRefresh").start()
                                    },
                                    modifier = Modifier.weight(0.8f),
                                )
                            }
                        } else {
                            val ifaceTag = remote.usbTetherInterface.ifBlank { "usb" }
                            CopyableInfoLine(ctx, "USB 共享地址 ($ifaceTag)", "${remote.usbTetherIp}:$port")

                            if (usbState != UsbTetherGatewayPolicy.SenderShareState.REUSING_LAN_SHARE) {
                                OpalSwitchRow(
                                    title = "仅通过 USB 网络共享监听",
                                    subtitle = "仅绑定 USB 共享网卡，不开放 Wi-Fi 端口；拔线自动停止，重插后自动重绑新 IP",
                                    checked = usbState == UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE,
                                    onCheckedChange = { enableUsbOnly ->
                                        if (wifiBusy) return@OpalSwitchRow
                                        if (enableUsbOnly) {
                                            val check = OpalPeerCastMutex.validateEnableSending(peerRole.isReceiving, peerRole.peerName)
                                            if (!check.allowed) {
                                                Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                                                return@OpalSwitchRow
                                            }
                                        }
                                        wifiBusy = true
                                        val scope = if (enableUsbOnly) {
                                            BridgeProtocol.TlsBindScope.USB_TETHER_ONLY
                                        } else {
                                            BridgeProtocol.TlsBindScope.ALL
                                        }
                                        BridgeClient.setWifiStreamEnabledAsync(enableUsbOnly, 19878, scope) {
                                            wifiBusy = false
                                            val latest = BridgeClient.remoteStatusFlow.value
                                            OpalPeerDiscovery.syncAdvertise(
                                                ctx,
                                                latest.isLanShareActive,
                                                latest.wifiPort,
                                                latest.shortCertFingerprint,
                                            )
                                            if (enableUsbOnly && !latest.isUsbTetherOnlyMode) {
                                                Toast.makeText(
                                                    ctx,
                                                    "未能绑定 USB 网络共享网卡，请确认系统已开启「USB 网络共享」",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    },
                                )
                                if (usbState == UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE &&
                                    remote.shortCertFingerprint.isNotEmpty()
                                ) {
                                    CopyableInfoLine(ctx, "TLS 证书短指纹", remote.shortCertFingerprint)
                                }
                            } else {
                                OpalText(
                                    text = "已复用当前局域网共享服务：在 Windows / macOS / 平板端开启 Beta 自动识别后可直接走此 USB 有线链路。",
                                    fontSize = 11.5.sp,
                                    color = OpalPalette.TextSecondary,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }

                // Compact collapsible sub-row for Trusted Peers
                Spacer(Modifier.height(9.dp))
                val trustedShape = RoundedCornerShape(13.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .consoleSearchAnchor(navController, ConsoleSectionKeys.TRUSTED_PEERS, shape = trustedShape)
                        .clip(trustedShape)
                        .background(OpalPalette.ChipFill)
                        .border(0.75.dp, OpalPalette.BorderHairline, trustedShape),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = !trustedPeersExpanded
                                if (navController != null) {
                                    navController.setExpanded(ConsoleSectionKeys.TRUSTED_PEERS, next)
                                } else {
                                    localTrustedPeersExpanded = next
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            OpalText(
                                text = "永久信任设备管理",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpalPalette.TextPrimary,
                            )
                            OpalText(
                                text = if (trustedPeers.isEmpty()) "0 台" else "${trustedPeers.size} 台",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (trustedPeers.isNotEmpty()) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
                            )
                        }
                        OpalText(
                            text = if (trustedPeersExpanded) "收起 ▴" else "展开 ▾",
                            fontSize = 11.sp,
                            color = OpalPalette.TextSecondary,
                        )
                    }
                    AnimatedVisibility(
                        visible = trustedPeersExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            if (!status.connected) {
                                OpalText(
                                    text = "激活多窗引擎后可查看与撤销永久授权设备。",
                                    fontSize = 11.5.sp,
                                    color = OpalPalette.TextMuted,
                                )
                            } else if (trustedPeers.isEmpty()) {
                                OpalText(
                                    text = "当前暂无已永久信任的对端设备；无线连接确认弹窗勾选「永久信任」后会出现在此处。",
                                    fontSize = 11.5.sp,
                                    color = OpalPalette.TextMuted,
                                    lineHeight = 16.sp,
                                )
                            } else {
                                if (trustedPeers.size >= 2) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        OpalButton(
                                            label = "全部撤销",
                                            accent = OpalPalette.AccentDanger,
                                            filled = false,
                                            compact = true,
                                            onClick = {
                                                BridgeClient.revokeTrustedPeerAsync("*") {
                                                    Toast.makeText(ctx, "已撤销所有永久授权设备", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                        )
                                    }
                                }
                                trustedPeers.forEach { peer ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(OpalPalette.GlassPopover.copy(alpha = 0.55f))
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            OpalText(text = peer.clientName, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                            OpalText(
                                                text = peer.clientId,
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = OpalPalette.TextMuted,
                                                maxLines = 1,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        OpalButton(
                                            label = "撤销",
                                            accent = OpalPalette.AccentDanger,
                                            filled = false,
                                            compact = true,
                                            onClick = {
                                                BridgeClient.revokeTrustedPeerAsync(peer.clientId) {
                                                    Toast.makeText(ctx, "已撤销「${peer.clientName}」的永久授权", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Mode 2: System Wireless Display (Miracast / Wi-Fi Display) TV Casting
                val tvCandidates = detectedExtDisplays.ifEmpty { listOfNotNull(extDisplay) }
                if (tvCandidates.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        tvCandidates.forEach { disp ->
                            val transportLbl = WirelessDisplayHelper.formatTransportLabel(disp.transport)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(OpalPalette.ChipFill)
                                    .border(0.8.dp, OpalPalette.AccentEmerald.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(
                                        text = "${disp.name} · $transportLbl",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    OpalText(
                                        text = "Display #${disp.displayId} · ${disp.width}×${disp.height} @ ${disp.refreshRateHz}Hz",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.AccentEmerald,
                                        maxLines = 1,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                OpalButton(
                                    label = "投射 DeX",
                                    accent = OpalPalette.AccentEmerald,
                                    compact = true,
                                    onClick = {
                                        if (!status.connected) {
                                            onOpenActivation()
                                        } else {
                                            onProjectSpecificDisplay(
                                                disp.displayId,
                                                disp.transport,
                                                ExternalDisplayPolicy.shouldForcePresentationForManualLaunch(
                                                    disp.transport,
                                                    disp.isPresentationCapable,
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(OpalPalette.ChipFill)
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                    ) {
                        OpalText(
                            text = "尚未检测到无线电视副屏。先点下方按钮通过系统「无线投屏（Miracast）」连接电视。",
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                            lineHeight = 16.5.sp,
                        )
                    }
                }

                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OpalButton(
                        label = "打开系统无线投屏",
                        accent = OpalPalette.AccentBlue,
                        compact = true,
                        onClick = { WirelessDisplayHelper.openSystemWirelessCastSettings(ctx) },
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "横屏镜像 DeX",
                        accent = OpalPalette.AccentCyan,
                        filled = false,
                        compact = true,
                        onClick = {
                            if (!status.connected) {
                                onOpenActivation()
                            } else {
                                onEnterLandscapeMirrorDesktop()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(7.dp))
                OpalText(
                    text = "• 支持 Miracast 独立副屏的机型连上电视后会自动以 Presentation 模式满屏投射 DeX，手机变为触控板。\n" +
                        "• 仅支持主屏镜像的机型，在系统投屏后点击「横屏镜像 DeX」即可横屏满画幅显示在电视上。",
                    fontSize = 11.sp,
                    color = OpalPalette.TextMuted,
                    lineHeight = 15.5.sp,
                )
            }
        }

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
