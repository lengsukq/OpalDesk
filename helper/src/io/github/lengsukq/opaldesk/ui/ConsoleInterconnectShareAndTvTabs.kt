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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.UsbTetherGatewayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.TrustedPeerEntry
import io.github.lengsukq.opaldesk.web.WebActivationPolicy

@Composable
internal fun InterconnectShareTab(
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
        title = stringResource(R.string.interconnect_share_switch_title),
        subtitle = when {
            peerRole.isReceiving -> stringResource(R.string.interconnect_share_sub_receiving)
            remote.isLanShareActive -> stringResource(R.string.interconnect_share_sub_active)
            remote.isUsbTetherOnlyMode -> stringResource(R.string.interconnect_share_sub_usb_only)
            else -> stringResource(R.string.interconnect_share_sub_idle)
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
        CopyableInfoLine(ctx, stringResource(R.string.interconnect_lan_address), endpoint)
        if (remote.shortCertFingerprint.isNotEmpty()) {
            CopyableInfoLine(ctx, stringResource(R.string.interconnect_tls_fingerprint), remote.shortCertFingerprint)
        }
        CopyableInfoLine(ctx, stringResource(R.string.interconnect_web_cast_url), webCastUrl)
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
                text = stringResource(R.string.interconnect_web_qr_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = OpalPalette.AccentBlue,
            )
            OpalText(
                text = stringResource(if (showWebCastQr) R.string.common_collapse else R.string.common_expand),
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
                    text = stringResource(R.string.interconnect_web_qr_hint),
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
                OpalText(text = stringResource(R.string.interconnect_usb_channel_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                OpalStatusBadge(label = "Beta", color = OpalPalette.AccentAmber)
            }
            val (statusLabel, statusColor) = when (usbState) {
                UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE -> stringResource(R.string.interconnect_usb_status_only) to OpalPalette.AccentEmerald
                UsbTetherGatewayPolicy.SenderShareState.REUSING_LAN_SHARE -> stringResource(R.string.interconnect_usb_status_reuse) to OpalPalette.AccentEmerald
                UsbTetherGatewayPolicy.SenderShareState.USB_READY_IDLE -> stringResource(R.string.interconnect_usb_status_ready) to OpalPalette.AccentCyan
                else -> stringResource(R.string.interconnect_usb_status_none) to OpalPalette.TextMuted
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
                    label = stringResource(R.string.interconnect_usb_open_settings),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentBlue,
                    onClick = { openTetherSettings(ctx) },
                    modifier = Modifier.weight(1.3f),
                )
                OpalButton(
                    label = stringResource(R.string.interconnect_usb_recheck),
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
        CopyableInfoLine(ctx, stringResource(R.string.interconnect_usb_addr_label, ifaceTag), "${remote.usbTetherIp}:$port")

        if (usbState == UsbTetherGatewayPolicy.SenderShareState.REUSING_LAN_SHARE) {
            OpalText(
                text = stringResource(R.string.interconnect_usb_reuse_hint),
                fontSize = 11.5.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 16.sp,
            )
            return@Column
        }

        OpalSwitchRow(
            title = stringResource(R.string.interconnect_usb_only_title),
            subtitle = stringResource(R.string.interconnect_usb_only_sub),
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
                            ctx.getString(R.string.interconnect_usb_bind_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
        if (usbState == UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE &&
            remote.shortCertFingerprint.isNotEmpty()
        ) {
            CopyableInfoLine(ctx, stringResource(R.string.interconnect_tls_fingerprint), remote.shortCertFingerprint)
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
                    text = stringResource(R.string.interconnect_trusted_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                )
                OpalText(
                    text = stringResource(R.string.interconnect_trusted_count, trustedPeers.size),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (trustedPeers.isNotEmpty()) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
                )
            }
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
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when {
                    !connected -> OpalText(
                        text = stringResource(R.string.interconnect_trusted_need_engine),
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextMuted,
                    )
                    trustedPeers.isEmpty() -> OpalText(
                        text = stringResource(R.string.interconnect_trusted_empty),
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
                                    label = stringResource(R.string.interconnect_revoke_all),
                                    accent = OpalPalette.AccentDanger,
                                    filled = false,
                                    compact = true,
                                    onClick = {
                                        BridgeClient.revokeTrustedPeerAsync("*") {
                                            Toast.makeText(ctx, ctx.getString(R.string.interconnect_revoked_all_toast), Toast.LENGTH_SHORT).show()
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
                                    label = stringResource(R.string.interconnect_revoke),
                                    accent = OpalPalette.AccentDanger,
                                    filled = false,
                                    compact = true,
                                    onClick = {
                                        BridgeClient.revokeTrustedPeerAsync(peer.clientId) {
                                            Toast.makeText(ctx, ctx.getString(R.string.interconnect_revoked_one_toast, peer.clientName), Toast.LENGTH_SHORT).show()
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
internal fun InterconnectTvCastTab(
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
                        label = stringResource(R.string.interconnect_project_dex),
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
                text = stringResource(R.string.interconnect_tv_empty),
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 16.5.sp,
            )
        }
    }

    Spacer(Modifier.height(9.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OpalButton(
            label = stringResource(R.string.interconnect_open_miracast),
            accent = OpalPalette.AccentBlue,
            compact = true,
            onClick = { WirelessDisplayHelper.openSystemWirelessCastSettings(ctx) },
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = stringResource(R.string.interconnect_landscape_mirror),
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
        text = stringResource(R.string.interconnect_tv_tips),
        fontSize = 11.sp,
        color = OpalPalette.TextMuted,
        lineHeight = 15.5.sp,
    )
}
