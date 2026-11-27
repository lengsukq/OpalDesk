package io.github.lengsukq.opaldesk.ui

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.cast.DiscoveredOpalPeer
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.cast.UsbTetherGatewayPolicy
import io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.TrustedPeerEntry
import io.github.lengsukq.opaldesk.web.WebActivationPolicy

internal const val DEFAULT_OPAL_CAST_PORT = 19878

private fun parseManualPeerEndpoint(rawInput: String): Pair<String, Int> {
    val trimmed = rawInput.trim()
    val colon = trimmed.lastIndexOf(':')
    if (colon <= 0) return trimmed to DEFAULT_OPAL_CAST_PORT
    val host = trimmed.substring(0, colon).trim()
    val port = trimmed.substring(colon + 1).trim().toIntOrNull() ?: DEFAULT_OPAL_CAST_PORT
    return host to port
}

/**
 * Compact Cross-Device Interconnect Card used on Tab 0 (Workstation Home).
 * Keeps active sessions & discovered peers visible while tucking manual IP, Web QR,
 * and trusted peer management into collapsible sub-rows.
 */
@Composable
internal fun ConsoleInterconnectCard(
    status: DaemonStatus,
    remote: RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    detectedExtDisplays: List<BridgeService.ExternalDisplayInfo>,
    peerRole: OpalPeerCastMutex.CastRoleState,
    discoveredPeers: List<DiscoveredOpalPeer>,
    trustedPeers: List<TrustedPeerEntry>,
    activeFloating: PeerFloatingAppService.ActiveFloatingInfo?,
    usbTetherEnabled: Boolean,
    usbTetherPeers: List<UsbTetherGatewayPolicy.DiscoveredUsbTetherPeer>,
    usbTetherScanning: Boolean,
    needsLocalNetworkPerm: Boolean,
    castModeTab: Int,
    onSelectCastModeTab: (Int) -> Unit,
    wifiBusy: Boolean,
    onSetWifiBusy: (Boolean) -> Unit,
    navController: ConsoleNavigationController?,
    onRequestLocalNetworkPerm: () -> Unit,
    onEnterLandscapeMirrorDesktop: () -> Unit,
    onProjectSpecificDisplay: (Int, ExternalDisplayTransport, Boolean) -> Unit,
    onConnectPeerCast: (String, Int, String) -> Unit,
    onOpenFloatingAppPicker: (String, Int, String) -> Unit,
    onOpenActivation: () -> Unit,
) {
    val ctx = LocalContext.current
    var localManualConnectExpanded by remember { mutableStateOf(false) }
    var localTrustedPeersExpanded by remember { mutableStateOf(false) }
    var showWebCastQr by remember { mutableStateOf(false) }
    var peerHostText by remember { mutableStateOf("") }

    val manualConnectExpanded = navController?.isExpanded(ConsoleSectionKeys.MANUAL_CONNECT, false)
        ?: localManualConnectExpanded
    val trustedPeersExpanded = navController?.isExpanded(ConsoleSectionKeys.TRUSTED_PEERS, false)
        ?: localTrustedPeersExpanded

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
            Spacer(Modifier.height(10.dp))
            ActiveFloatingPeerBanner(ctx = ctx, activeFloating = activeFloating)
        }

        Spacer(Modifier.height(10.dp))
        OpalSegmentedRow(
            options = listOf("连接设备", "共享本机", "投电视"),
            selectedIndex = castModeTab.coerceIn(0, 2),
            onSelect = onSelectCastModeTab,
        )

        Spacer(Modifier.height(11.dp))
        when (castModeTab) {
            0 -> InterconnectConnectTab(
                remote = remote,
                discoveredPeers = discoveredPeers,
                usbTetherEnabled = usbTetherEnabled,
                usbTetherPeers = usbTetherPeers,
                usbTetherScanning = usbTetherScanning,
                needsLocalNetworkPerm = needsLocalNetworkPerm,
                manualConnectExpanded = manualConnectExpanded,
                onToggleManualConnect = {
                    val next = !manualConnectExpanded
                    if (navController != null) {
                        navController.setExpanded(ConsoleSectionKeys.MANUAL_CONNECT, next)
                    } else {
                        localManualConnectExpanded = next
                    }
                },
                peerHostText = peerHostText,
                onPeerHostTextChange = { peerHostText = it },
                navController = navController,
                onRequestLocalNetworkPerm = onRequestLocalNetworkPerm,
                onConnectPeerCast = onConnectPeerCast,
                onOpenFloatingAppPicker = onOpenFloatingAppPicker,
            )
            1 -> InterconnectShareTab(
                ctx = ctx,
                status = status,
                remote = remote,
                peerRole = peerRole,
                trustedPeers = trustedPeers,
                usbTetherEnabled = usbTetherEnabled,
                wifiBusy = wifiBusy,
                onSetWifiBusy = onSetWifiBusy,
                showWebCastQr = showWebCastQr,
                onToggleWebCastQr = { showWebCastQr = !showWebCastQr },
                trustedPeersExpanded = trustedPeersExpanded,
                onToggleTrustedPeers = {
                    val next = !trustedPeersExpanded
                    if (navController != null) {
                        navController.setExpanded(ConsoleSectionKeys.TRUSTED_PEERS, next)
                    } else {
                        localTrustedPeersExpanded = next
                    }
                },
                navController = navController,
            )
            else -> InterconnectTvCastTab(
                ctx = ctx,
                status = status,
                extDisplay = extDisplay,
                detectedExtDisplays = detectedExtDisplays,
                onOpenActivation = onOpenActivation,
                onEnterLandscapeMirrorDesktop = onEnterLandscapeMirrorDesktop,
                onProjectSpecificDisplay = onProjectSpecificDisplay,
            )
        }
    }
}

@Composable
private fun ActiveFloatingPeerBanner(
    ctx: Context,
    activeFloating: PeerFloatingAppService.ActiveFloatingInfo,
) {
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
                text = "小窗运行中：${activeFloating.appLabel}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.AccentBlue,
                maxLines = 1,
            )
            OpalText(
                text = "来自 ${activeFloating.peerName}",
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

@Composable
private fun InterconnectConnectTab(
    remote: RemoteStreamStatus,
    discoveredPeers: List<DiscoveredOpalPeer>,
    usbTetherEnabled: Boolean,
    usbTetherPeers: List<UsbTetherGatewayPolicy.DiscoveredUsbTetherPeer>,
    usbTetherScanning: Boolean,
    needsLocalNetworkPerm: Boolean,
    manualConnectExpanded: Boolean,
    onToggleManualConnect: () -> Unit,
    peerHostText: String,
    onPeerHostTextChange: (String) -> Unit,
    navController: ConsoleNavigationController?,
    onRequestLocalNetworkPerm: () -> Unit,
    onConnectPeerCast: (String, Int, String) -> Unit,
    onOpenFloatingAppPicker: (String, Int, String) -> Unit,
) {
    if (usbTetherEnabled) {
        UsbTetherPeerDiscoveryBox(
            remote = remote,
            usbTetherPeers = usbTetherPeers,
            usbTetherScanning = usbTetherScanning,
            onConnectPeerCast = onConnectPeerCast,
            onOpenFloatingAppPicker = onOpenFloatingAppPicker,
        )
        Spacer(Modifier.height(9.dp))
    }

    if (discoveredPeers.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            discoveredPeers.forEach { peer ->
                val addr = "${peer.host}:${peer.port}"
                DiscoveredPeerActionCard(
                    title = peer.displayName,
                    subtitle = if (peer.shortFingerprint.isNotEmpty()) "$addr · ${peer.shortFingerprint}" else addr,
                    lockedOut = remote.connected,
                    onConnectDesktop = { onConnectPeerCast(peer.host, peer.port, peer.displayName) },
                    onOpenFloating = { onOpenFloatingAppPicker(peer.host, peer.port, peer.displayName) },
                )
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
            OpalButton(label = "开启发现", compact = true, onClick = onRequestLocalNetworkPerm)
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

    Spacer(Modifier.height(9.dp))
    ManualPeerConnectCollapsible(
        expanded = manualConnectExpanded,
        onToggle = onToggleManualConnect,
        peerHostText = peerHostText,
        onPeerHostTextChange = onPeerHostTextChange,
        lockedOut = remote.connected,
        navController = navController,
        onConnectPeerCast = onConnectPeerCast,
        onOpenFloatingAppPicker = onOpenFloatingAppPicker,
    )
}

@Composable
private fun UsbTetherPeerDiscoveryBox(
    remote: RemoteStreamStatus,
    usbTetherPeers: List<UsbTetherGatewayPolicy.DiscoveredUsbTetherPeer>,
    usbTetherScanning: Boolean,
    onConnectPeerCast: (String, Int, String) -> Unit,
    onOpenFloatingAppPicker: (String, Int, String) -> Unit,
) {
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
                DiscoveredPeerActionCard(
                    title = "${peer.displayName} · ${peer.interfaceName}",
                    subtitle = if (peer.shortFingerprint.isNotEmpty()) "$addr · ${peer.shortFingerprint}" else addr,
                    lockedOut = remote.connected,
                    nestedSurface = true,
                    onConnectDesktop = { onConnectPeerCast(peer.host, peer.port, peer.displayName) },
                    onOpenFloating = { onOpenFloatingAppPicker(peer.host, peer.port, peer.displayName) },
                )
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
}

@Composable
private fun DiscoveredPeerActionCard(
    title: String,
    subtitle: String,
    lockedOut: Boolean,
    nestedSurface: Boolean = false,
    onConnectDesktop: () -> Unit,
    onOpenFloating: () -> Unit,
) {
    val shape = RoundedCornerShape(if (nestedSurface) 12.dp else 14.dp)
    val bg = if (nestedSurface) OpalPalette.GlassPopover.copy(alpha = 0.6f) else OpalPalette.ChipFill
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(0.75.dp, OpalPalette.BorderSubtle, shape)
            .padding(horizontal = if (nestedSurface) 11.dp else 13.dp, vertical = if (nestedSurface) 9.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (nestedSurface) 7.dp else 8.dp),
    ) {
        Column {
            OpalText(
                text = title,
                fontSize = if (nestedSurface) 13.sp else 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            OpalText(
                text = subtitle,
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
                label = if (lockedOut) "投屏锁定中" else "投屏桌面",
                accent = OpalPalette.AccentEmerald,
                compact = true,
                enabled = !lockedOut,
                onClick = onConnectDesktop,
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = "应用小窗",
                accent = OpalPalette.AccentBlue,
                filled = false,
                compact = true,
                enabled = !lockedOut,
                onClick = onOpenFloating,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ManualPeerConnectCollapsible(
    expanded: Boolean,
    onToggle: () -> Unit,
    peerHostText: String,
    onPeerHostTextChange: (String) -> Unit,
    lockedOut: Boolean,
    navController: ConsoleNavigationController?,
    onConnectPeerCast: (String, Int, String) -> Unit,
    onOpenFloatingAppPicker: (String, Int, String) -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .consoleSearchAnchor(navController, ConsoleSectionKeys.MANUAL_CONNECT, shape = shape)
            .clip(shape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
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
                text = if (expanded) "收起 ▴" else "展开 ▾",
                fontSize = 11.sp,
                color = OpalPalette.TextSecondary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
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
                    onValueChange = onPeerHostTextChange,
                    placeholder = "输入对方 IP:端口 (如 192.168.1.8:$DEFAULT_OPAL_CAST_PORT)",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OpalButton(
                        label = if (lockedOut) "投屏锁定中" else "投屏桌面",
                        enabled = !lockedOut && peerHostText.isNotBlank(),
                        compact = true,
                        accent = OpalPalette.AccentEmerald,
                        onClick = {
                            val (host, port) = parseManualPeerEndpoint(peerHostText)
                            onConnectPeerCast(host, port, host)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "应用小窗",
                        enabled = !lockedOut && peerHostText.isNotBlank(),
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentBlue,
                        onClick = {
                            val (host, port) = parseManualPeerEndpoint(peerHostText)
                            onOpenFloatingAppPicker(host, port, host)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InterconnectShareTab(
    ctx: Context,
    status: DaemonStatus,
    remote: RemoteStreamStatus,
    peerRole: OpalPeerCastMutex.CastRoleState,
    trustedPeers: List<TrustedPeerEntry>,
    usbTetherEnabled: Boolean,
    wifiBusy: Boolean,
    onSetWifiBusy: (Boolean) -> Unit,
    showWebCastQr: Boolean,
    onToggleWebCastQr: () -> Unit,
    trustedPeersExpanded: Boolean,
    onToggleTrustedPeers: () -> Unit,
    navController: ConsoleNavigationController?,
) {
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
            onSetWifiBusy(true)
            BridgeClient.setWifiStreamEnabledAsync(enable, DEFAULT_OPAL_CAST_PORT, BridgeProtocol.TlsBindScope.ALL) {
                onSetWifiBusy(false)
                val latest = BridgeClient.remoteStatusFlow.value
                OpalPeerDiscovery.syncAdvertise(ctx, latest.isLanShareActive, latest.wifiPort, latest.shortCertFingerprint)
            }
        },
    )

    if (status.connected && remote.isLanShareActive && !peerRole.isReceiving) {
        Spacer(Modifier.height(8.dp))
        LanShareEndpointCard(
            ctx = ctx,
            remote = remote,
            showWebCastQr = showWebCastQr,
            onToggleWebCastQr = onToggleWebCastQr,
        )
    }

    if (usbTetherEnabled && status.connected && !peerRole.isReceiving) {
        Spacer(Modifier.height(9.dp))
        UsbTetherSenderChannelCard(
            ctx = ctx,
            remote = remote,
            peerRole = peerRole,
            wifiBusy = wifiBusy,
            onSetWifiBusy = onSetWifiBusy,
        )
    }

    Spacer(Modifier.height(9.dp))
    TrustedPeersCollapsible(
        ctx = ctx,
        connected = status.connected,
        trustedPeers = trustedPeers,
        expanded = trustedPeersExpanded,
        onToggle = onToggleTrustedPeers,
        navController = navController,
    )
}

@Composable
private fun LanShareEndpointCard(
    ctx: Context,
    remote: RemoteStreamStatus,
    showWebCastQr: Boolean,
    onToggleWebCastQr: () -> Unit,
) {
    val port = if (remote.wifiPort > 0) remote.wifiPort else DEFAULT_OPAL_CAST_PORT
    val endpoint = if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:$port" else ":$port"
    val webCastUrl = if (remote.lanIp.isNotEmpty()) {
        WebActivationPolicy.buildWebCastUrl(remote.lanIp, port)
    } else {
        WebActivationPolicy.DEFAULT_WEB_CAST_URL
    }

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
                .clickable(onClick = onToggleWebCastQr)
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

@Composable
private fun UsbTetherSenderChannelCard(
    ctx: Context,
    remote: RemoteStreamStatus,
    peerRole: OpalPeerCastMutex.CastRoleState,
    wifiBusy: Boolean,
    onSetWifiBusy: (Boolean) -> Unit,
) {
    val usbState = UsbTetherGatewayPolicy.resolveSenderShareState(remote, featureEnabled = true)
    val port = if (remote.wifiPort > 0) remote.wifiPort else DEFAULT_OPAL_CAST_PORT

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
            val (statusLabel, statusColor) = when (usbState) {
                UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE -> "仅 USB 监听中" to OpalPalette.AccentEmerald
                UsbTetherGatewayPolicy.SenderShareState.REUSING_LAN_SHARE -> "复用共享中" to OpalPalette.AccentEmerald
                UsbTetherGatewayPolicy.SenderShareState.USB_READY_IDLE -> "网卡已就绪" to OpalPalette.AccentCyan
                else -> "未检测到网卡" to OpalPalette.TextMuted
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
                    onClick = { Thread({ BridgeClient.refreshRemoteStatus() }, "OpalUsbRefresh").start() },
                    modifier = Modifier.weight(0.8f),
                )
            }
            return@Column
        }

        val ifaceTag = remote.usbTetherInterface.ifBlank { "usb" }
        CopyableInfoLine(ctx, "USB 共享地址 ($ifaceTag)", "${remote.usbTetherIp}:$port")

        if (usbState == UsbTetherGatewayPolicy.SenderShareState.REUSING_LAN_SHARE) {
            OpalText(
                text = "已复用当前局域网共享服务：在 Windows / macOS / 平板端开启 Beta 自动识别后可直接走此 USB 有线链路。",
                fontSize = 11.5.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 16.sp,
            )
            return@Column
        }

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
                onSetWifiBusy(true)
                val scope = if (enableUsbOnly) {
                    BridgeProtocol.TlsBindScope.USB_TETHER_ONLY
                } else {
                    BridgeProtocol.TlsBindScope.ALL
                }
                BridgeClient.setWifiStreamEnabledAsync(enableUsbOnly, DEFAULT_OPAL_CAST_PORT, scope) {
                    onSetWifiBusy(false)
                    val latest = BridgeClient.remoteStatusFlow.value
                    OpalPeerDiscovery.syncAdvertise(ctx, latest.isLanShareActive, latest.wifiPort, latest.shortCertFingerprint)
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
    }
}

@Composable
private fun TrustedPeersCollapsible(
    ctx: Context,
    connected: Boolean,
    trustedPeers: List<TrustedPeerEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
    navController: ConsoleNavigationController?,
) {
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .consoleSearchAnchor(navController, ConsoleSectionKeys.TRUSTED_PEERS, shape = shape)
            .clip(shape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
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
                text = if (expanded) "收起 ▴" else "展开 ▾",
                fontSize = 11.sp,
                color = OpalPalette.TextSecondary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when {
                    !connected -> OpalText(
                        text = "激活多窗引擎后可查看与撤销永久授权设备。",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextMuted,
                    )
                    trustedPeers.isEmpty() -> OpalText(
                        text = "当前暂无已永久信任的对端设备；无线连接确认弹窗勾选「永久信任」后会出现在此处。",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextMuted,
                        lineHeight = 16.sp,
                    )
                    else -> {
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
    }
}

@Composable
private fun InterconnectTvCastTab(
    ctx: Context,
    status: DaemonStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    detectedExtDisplays: List<BridgeService.ExternalDisplayInfo>,
    onOpenActivation: () -> Unit,
    onEnterLandscapeMirrorDesktop: () -> Unit,
    onProjectSpecificDisplay: (Int, ExternalDisplayTransport, Boolean) -> Unit,
) {
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
