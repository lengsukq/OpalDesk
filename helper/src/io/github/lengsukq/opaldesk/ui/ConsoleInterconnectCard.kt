package io.github.lengsukq.opaldesk.ui

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.cast.DiscoveredOpalPeer
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.cast.UsbTetherGatewayPolicy
import io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.TrustedPeerEntry

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
 * Keeps active sessions & discovered peers visible while delegating Share & TV tabs
 * to [ConsoleInterconnectShareAndTvTabs.kt].
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
            OpalText(text = stringResource(R.string.interconnect_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            when {
                remote.connected -> OpalStatusBadge(label = remote.channelLabel, color = OpalPalette.AccentEmerald)
                peerRole.isReceiving -> OpalStatusBadge(label = stringResource(R.string.dash_tile_receiving_badge), color = OpalPalette.AccentBlue)
                remote.isUsbTetherOnlyMode -> OpalStatusBadge(label = stringResource(R.string.interconnect_badge_usb_beta), color = OpalPalette.AccentCyan)
                remote.isLanShareActive -> OpalStatusBadge(label = stringResource(R.string.interconnect_badge_lan_on), color = OpalPalette.AccentEmerald)
                discoveredPeers.isNotEmpty() -> OpalStatusBadge(label = stringResource(R.string.interconnect_badge_found, discoveredPeers.size), color = OpalPalette.AccentCyan)
            }
        }

        if (activeFloating != null) {
            Spacer(Modifier.height(10.dp))
            ActiveFloatingPeerBanner(ctx = ctx, activeFloating = activeFloating)
        }

        Spacer(Modifier.height(10.dp))
        OpalSegmentedRow(
            options = listOf(
                stringResource(R.string.interconnect_tab_connect),
                stringResource(R.string.interconnect_tab_share),
                stringResource(R.string.interconnect_tab_tv),
            ),
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
                text = stringResource(R.string.interconnect_floating_running, activeFloating.appLabel),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.AccentBlue,
                maxLines = 1,
            )
            OpalText(
                text = stringResource(R.string.interconnect_from_peer, activeFloating.peerName),
                fontSize = 11.sp,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        OpalButton(
            label = stringResource(R.string.common_close),
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
                text = stringResource(R.string.interconnect_local_net_prompt),
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OpalButton(label = stringResource(R.string.interconnect_enable_discovery), compact = true, onClick = onRequestLocalNetworkPerm)
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
                    stringResource(R.string.interconnect_locked_sending)
                } else {
                    stringResource(R.string.interconnect_scanning_lan)
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
                OpalText(text = stringResource(R.string.interconnect_usb_direct_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                OpalStatusBadge(label = "Beta", color = OpalPalette.AccentAmber)
            }
            OpalButton(
                label = stringResource(if (usbTetherScanning) R.string.interconnect_scanning else R.string.common_refresh),
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
                text = stringResource(R.string.interconnect_usb_direct_hint, UsbTetherGatewayPolicy.BETA_DISCLAIMER),
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
                label = stringResource(if (lockedOut) R.string.interconnect_cast_locked else R.string.interconnect_cast_desktop),
                accent = OpalPalette.AccentEmerald,
                compact = true,
                enabled = !lockedOut,
                onClick = onConnectDesktop,
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = stringResource(R.string.interconnect_standalone_app),
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
                text = stringResource(R.string.interconnect_manual_ip_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.AccentBlue,
            )
            OpalText(
                text = stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
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
                    placeholder = stringResource(R.string.interconnect_manual_ip_placeholder, DEFAULT_OPAL_CAST_PORT),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OpalButton(
                        label = stringResource(if (lockedOut) R.string.interconnect_cast_locked else R.string.interconnect_cast_desktop),
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
                        label = stringResource(R.string.interconnect_standalone_app),
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
