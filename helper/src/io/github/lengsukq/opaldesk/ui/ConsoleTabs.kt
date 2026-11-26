package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.update.AppUpdateLogic
import io.github.lengsukq.opaldesk.update.AppUpdateManager
import io.github.lengsukq.opaldesk.web.WebActivationPolicy
import java.util.Locale
import kotlin.math.abs

@Composable
fun MacGlassSectionCard(
    modifier: Modifier = Modifier,
    accentBorder: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val rimBrush = if (accentBorder != null) {
        Brush.verticalGradient(listOf(accentBorder.copy(alpha = 0.62f), accentBorder.copy(alpha = 0.18f)))
    } else {
        OpalPalette.LiquidGlassRim
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidDockFill,
                rim = rimBrush,
                elevation = if (OpalPalette.isDark) 12.dp else 9.dp,
            )
            .padding(contentPadding),
    ) {
        content()
    }
}

/**
 * Tab 1: Workstation Home — Apple HIG inspired 3-card layout with one focal Hero CTA per state,
 * zero duplicate buttons, clean 2-mode Cross-Device Interconnect switcher, and progressive disclosure.
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
) {
    val ctx = LocalContext.current
    val extSession by ExternalTrackpadBridge.sessionFlow.collectAsState()
    val peerRole by OpalPeerCastMutex.roleFlow.collectAsState()
    val discoveredPeers by OpalPeerDiscovery.discoveredPeersFlow.collectAsState()
    val trustedPeers by BridgeClient.trustedPeersFlow.collectAsState()
    val activeFloating by PeerFloatingAppService.activeFlow.collectAsState()

    var castModeTab by remember { mutableIntStateOf(if (remote.wifiEnabled || remote.connected) 1 else 0) }
    var showManualConnect by remember { mutableStateOf(false) }
    var showWebCastQr by remember { mutableStateOf(false) }
    var wifiBusy by remember { mutableStateOf(false) }
    var peerHostText by remember { mutableStateOf("") }
    var peerLabelText by remember { mutableStateOf("") }

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
        val enable = !remote.wifiEnabled
        if (enable) {
            val check = OpalPeerCastMutex.validateEnableSending(peerRole.isReceiving, peerRole.peerName)
            if (!check.allowed) {
                Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                return
            }
        }
        wifiBusy = true
        BridgeClient.setWifiStreamEnabledAsync(enable, 19878) {
            wifiBusy = false
            val latest = BridgeClient.remoteStatusFlow.value
            OpalPeerDiscovery.syncAdvertise(ctx, latest.wifiEnabled, latest.wifiPort, latest.shortCertFingerprint)
            if (latest.wifiEnabled) castModeTab = 1
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Luminous Spatial Hero Stage Card (Consumer Dashboard Focal Point)
        val heroAccent = when {
            !status.connected -> OpalPalette.AccentAmber
            remote.connected -> OpalPalette.AccentEmerald
            physicalExtActive -> OpalPalette.AccentBlue
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
                .liquidGlassSurface(
                    shape = heroShape,
                    fill = heroBgBrush,
                    rim = Brush.verticalGradient(listOf(heroAccent.copy(alpha = 0.65f), heroAccent.copy(alpha = 0.16f))),
                    elevation = if (isDark) 16.dp else 12.dp,
                )
                .padding(20.dp),
        ) {
            when {
                !status.connected -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OpalText(text = "开启多窗桌面", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            OpalText(
                                text = "激活引擎后即可进入桌面、满屏外接或无线投屏",
                                fontSize = 12.5.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        DashboardHeroIconBadge(accent = heroAccent, active = false)
                    }
                    Spacer(Modifier.height(16.dp))
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
                                    fontSize = 17.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            OpalText(
                                text = "$peerName · ${remote.width}×${remote.height} @ ${fps}Hz",
                                fontSize = 12.5.sp,
                                color = OpalPalette.AccentEmerald,
                            )
                        }
                        if (remote.activeSessionPermanent) {
                            OpalStatusBadge(label = "永久信任", color = OpalPalette.AccentEmerald)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    if (remote.hasActiveDesktopCast) {
                        OpalButton(
                            label = "打开本机触控板",
                            accent = OpalPalette.AccentBlue,
                            onClick = onOpenRemoteTrackpad,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
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
                    Spacer(Modifier.height(10.dp))
                    OpalText(
                        text = "投屏期间已锁定本机 DeX 桌面，避免双端画面冲突",
                        fontSize = 11.5.sp,
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
                                    fontSize = 17.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            OpalText(
                                text = activeExtSummary.ifBlank {
                                    if (extSession.isWirelessDisplay) "无线电视副屏已连接" else "物理外接显示器已连接"
                                },
                                fontSize = 12.5.sp,
                                color = OpalPalette.AccentCyan,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OpalButton(
                        label = "打开本机触控板",
                        onClick = { onOpenExternalOrTrackpad(activeExtDisplayId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
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
                        Spacer(Modifier.height(8.dp))
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
                                OpalText(text = "多窗桌面", fontSize = 21.sp, fontWeight = FontWeight.Bold)
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
                                fontSize = 12.5.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        DashboardHeroIconBadge(accent = heroAccent, active = true)
                    }

                    if (status.hostScreenOff) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(OpalPalette.AccentEmerald.copy(alpha = 0.14f))
                                .border(0.8.dp, OpalPalette.AccentEmerald.copy(alpha = 0.36f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(
                                text = "本机物理屏幕处于息屏状态",
                                fontSize = 12.5.sp,
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

                    Spacer(Modifier.height(12.dp))
                    QuickSlotSwitcherStrip(
                        compact = true,
                        showRestoreButton = false,
                    )

                    Spacer(Modifier.height(12.dp))
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
                        Spacer(Modifier.height(10.dp))
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
            modifier = Modifier.fillMaxWidth(),
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
            val sharingOn = status.connected && remote.wifiEnabled && !peerRole.isReceiving
            DashboardBentoTile(
                title = "无线共享",
                subtitle = when {
                    peerRole.isReceiving -> "正在接收其他设备"
                    sharingOn -> if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:${if (remote.wifiPort > 0) remote.wifiPort else 19878}" else "服务已开启"
                    else -> "局域网免 PIN 投给电脑/平板"
                },
                badgeText = when {
                    peerRole.isReceiving -> "接收中"
                    sharingOn -> "已开启"
                    else -> "未开启"
                },
                accent = if (sharingOn) OpalPalette.AccentEmerald else OpalPalette.AccentCyan,
                active = sharingOn,
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

        // 3. Nearby Devices & Interconnect Card
        ConsoleSectionTitle("跨设备互联")
        MacGlassSectionCard(
            accentBorder = when {
                peerRole.isReceiving || activeFloating != null -> OpalPalette.AccentBlue
                remote.wifiEnabled -> OpalPalette.AccentEmerald.copy(alpha = 0.45f)
                else -> null
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "跨设备互联", fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
                when {
                    remote.connected -> OpalStatusBadge(label = remote.channelLabel, color = OpalPalette.AccentEmerald)
                    peerRole.isReceiving -> OpalStatusBadge(label = "接收中", color = OpalPalette.AccentBlue)
                    remote.wifiEnabled -> OpalStatusBadge(label = "无线共享已开", color = OpalPalette.AccentEmerald)
                }
            }

            if (activeFloating != null) {
                val fl = activeFloating!!
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.AccentBlue.copy(alpha = 0.13f))
                        .border(0.8.dp, OpalPalette.AccentBlue.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
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

            Spacer(Modifier.height(12.dp))
            OpalSegmentedRow(
                options = listOf("连接设备", "共享本机", "投电视"),
                selectedIndex = castModeTab.coerceIn(0, 2),
                onSelect = { castModeTab = it },
            )

            Spacer(Modifier.height(14.dp))
            if (castModeTab == 0) {
                // Mode 0: Connect to another device (Desktop Cast or Standalone Floating Window)
                if (discoveredPeers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        discoveredPeers.forEach { peer ->
                            val addr = "${peer.host}:${peer.port}"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(OpalPalette.ChipFill)
                                    .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        OpalText(
                                            text = peer.displayName,
                                            fontSize = 14.sp,
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
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.ChipFill)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
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
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.ChipFill)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
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

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showManualConnect = !showManualConnect }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpalText(
                        text = if (showManualConnect) "收起手动输入 ▴" else "手动输入 IP 地址 ▾",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = OpalPalette.AccentBlue,
                    )
                }

                if (showManualConnect) {
                    Spacer(Modifier.height(8.dp))
                    OpalTextInput(
                        value = peerHostText,
                        onValueChange = { peerHostText = it },
                        placeholder = "输入对方 IP:端口 (如 192.168.1.8:19878)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
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
            } else if (castModeTab == 1) {
                // Mode 1: Share this device to Mac or another Android device
                OpalSwitchRow(
                    title = "允许局域网设备连接本机",
                    subtitle = when {
                        peerRole.isReceiving -> "正在接收其他设备画面，已暂时锁定共享"
                        remote.wifiEnabled -> "TLS 加密服务运行中 · 免 PIN 实时弹窗确认授权"
                        else -> "有线 USB 连接 Mac 无需开启；无线连接时打开此开关"
                    },
                    checked = remote.wifiEnabled && !peerRole.isReceiving,
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
                        BridgeClient.setWifiStreamEnabledAsync(enable, 19878) {
                            wifiBusy = false
                            val latest = BridgeClient.remoteStatusFlow.value
                            OpalPeerDiscovery.syncAdvertise(ctx, latest.wifiEnabled, latest.wifiPort, latest.shortCertFingerprint)
                        }
                    },
                )

                if (status.connected && remote.wifiEnabled && !peerRole.isReceiving) {
                    val port = if (remote.wifiPort > 0) remote.wifiPort else 19878
                    val endpoint = if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:$port" else ":$port"
                    val webCastUrl = if (remote.lanIp.isNotEmpty()) {
                        WebActivationPolicy.buildWebCastUrl(remote.lanIp, port)
                    } else {
                        WebActivationPolicy.DEFAULT_WEB_CAST_URL
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(OpalPalette.ChipFill)
                            .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(15.dp))
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CopyableInfoLine(ctx, "局域网地址", endpoint)
                        if (remote.shortCertFingerprint.isNotEmpty()) {
                            CopyableInfoLine(ctx, "TLS 证书短指纹", remote.shortCertFingerprint)
                        }
                        CopyableInfoLine(ctx, "Web 浏览器投屏链接", webCastUrl)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showWebCastQr = !showWebCastQr }
                                .padding(vertical = 3.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OpalText(
                                text = if (showWebCastQr) "收起 Web 扫码投屏二维码 ▴" else "展开 Web 扫码投屏二维码 ▾",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = OpalPalette.AccentBlue,
                            )
                        }
                        if (showWebCastQr) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                OpalQrCodeCard(content = webCastUrl, qrSize = 168.dp)
                            }
                            OpalText(
                                text = "使用电脑 / 平板 Chrome 或 Edge 扫码打开 GitHub Pages 即可直连投屏（免安装客户端）",
                                fontSize = 11.sp,
                                color = OpalPalette.TextMuted,
                            )
                        }
                    }
                }

                if (status.connected && trustedPeers.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(OpalPalette.ChipFill)
                            .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(15.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(
                                text = "已永久信任 (${trustedPeers.size})",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (trustedPeers.size >= 2) {
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
                                    OpalText(text = peer.clientName, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
            } else {
                // Mode 2: System Wireless Display (Miracast / Wi-Fi Display) TV Casting
                val tvCandidates = detectedExtDisplays.ifEmpty { listOfNotNull(extDisplay) }
                if (tvCandidates.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tvCandidates.forEach { disp ->
                            val transportLbl = WirelessDisplayHelper.formatTransportLabel(disp.transport)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(OpalPalette.ChipFill)
                                    .border(0.8.dp, OpalPalette.AccentEmerald.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(
                                        text = "${disp.name} · $transportLbl",
                                        fontSize = 13.5.sp,
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
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.ChipFill)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        OpalText(
                            text = "尚未检测到无线电视副屏。先点下方按钮通过系统「无线投屏（Miracast）」连接电视。",
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                            lineHeight = 17.sp,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
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

                Spacer(Modifier.height(8.dp))
                OpalText(
                    text = "• 支持 Miracast 独立副屏的机型连上电视后会自动以 Presentation 模式满屏投射 DeX，手机变为触控板。\n" +
                        "• 仅支持主屏镜像的机型，在系统投屏后点击「横屏镜像 DeX」即可横屏满画幅显示在电视上。",
                    fontSize = 11.sp,
                    color = OpalPalette.TextMuted,
                    lineHeight = 16.sp,
                )
            }
        }

        // 4. Whisper-Quiet Single-Line Upgrade Re-activation Tip
        UpgradeReactivateTipCard(
            connected = status.connected,
            onOpenActivation = onOpenActivation,
        )

        // 5. Minimalist Footer Exit Action
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
                    .padding(horizontal = 24.dp, vertical = 10.dp),
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

/**
 * Tab 2: Unified Display & Quality Settings — shares ONE identical control flow across USB-C / HDMI
 * external monitors AND Wi-Fi / USB remote casting (Auto-Adapt, Resolution & Refresh Rate, Target FPS,
 * Window DPI Scale, Output UI Scale, Host Physical Screen-Off, External Keyboard/Mouse Routing, Pointer
 * Speed, and Wallpapers).
 */
@Composable
fun ConsoleDisplaySettingsTab(
    status: DaemonStatus,
    remote: RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    detectedExtDisplays: List<BridgeService.ExternalDisplayInfo> = emptyList(),
    onProjectSpecificDisplay: (Int, ExternalDisplayTransport, Boolean) -> Unit = { _, _, _ -> },
    onPickCustomWallpaper: () -> Unit,
) {
    val ctx = LocalContext.current
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val extUiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val trackpadSens by AppPrefs.trackpadSensitivityFlow.collectAsState()
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    val mouseTarget by AppPrefs.mouseTargetFlow.collectAsState()
    val projectionMode by AppPrefs.externalProjectionModeFlow.collectAsState()
    val overscanPct by AppPrefs.externalOverscanPctFlow.collectAsState()
    var autoExtDisplay by remember { mutableStateOf(AppPrefs.isAutoExternalDisplayEnabled(ctx)) }
    val autoShowPhoneTrackpad by AppPrefs.autoShowPhoneTrackpadFlow.collectAsState()
    val isDark = OpalPalette.isDark

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Top 2-Column Bento Quick Tiles (Smart Auto-Adapt + Host Physical Screen-Off)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardBentoTile(
                title = "一键智能适配",
                subtitle = "恢复最佳分辨率、高刷与 DPI",
                badgeText = "推荐",
                accent = OpalPalette.AccentBlue,
                active = true,
                iconKind = 0,
                onClick = {
                    AppPrefs.setExternalUiScale(ctx, 1.25f)
                    AppPrefs.setTrackpadSensitivity(ctx, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    BridgeClient.setPointerSpeedAsync(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    BridgeClient.updateDisplayPrefsAsync(targetFps = 0, windowDpiScalePct = 100)
                    if (extDisplay != null) {
                        val best = extDisplay.supportedModes.firstOrNull()
                        if (best != null) {
                            BridgeClient.setExternalDisplayModeAsync(
                                extDisplay.displayId, best.width, best.height, best.refreshRateHz.toFloat(),
                            )
                        } else {
                            BridgeClient.setExternalDisplayModeAsync(extDisplay.displayId, 0, 0, 0f)
                        }
                    } else if (remote.connected) {
                        BridgeClient.setExternalDisplayModeAsync(-1, remote.width, remote.height, 60f)
                    }
                    Toast.makeText(ctx, "已恢复全自动最佳分辨率、帧率与 DPI 配置", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            )
            DashboardBentoTile(
                title = if (status.hostScreenOff) "点亮本机屏幕" else "本机物理息屏",
                subtitle = if (status.hostScreenOff) "省电运行中 · 点按亮屏" else "外接/投屏时熄屏省电",
                badgeText = if (status.hostScreenOff) "已息屏" else "亮屏",
                accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentCyan,
                active = status.hostScreenOff,
                iconKind = 1,
                onClick = {
                    if (status.connected) {
                        toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff)
                    } else {
                        Toast.makeText(ctx, "请先激活多窗引擎", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        ConsoleSectionTitle("外观风格")
        // 0. Appearance Style (macOS Liquid Frosted Glass: Light / Follow System / Dark)
        MacGlassSectionCard {
            OpalText(text = "液态毛玻璃外观", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = "默认通透浅色风格，支持跟随系统自动切换或常驻深色玻璃",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            OpalSegmentedRow(
                options = listOf("浅色", "跟随系统", "深色"),
                selectedIndex = themeMode.coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK),
                onSelect = { AppPrefs.setThemeMode(ctx, it) },
            )
        }

        ConsoleSectionTitle("外接屏与无线投电视")
        MacGlassSectionCard {
            OpalSwitchRow(
                title = "接入时自动满屏工作站",
                subtitle = "消除镜像黑边，按外接屏 / 无线电视原生分辨率输出",
                checked = autoExtDisplay,
                onCheckedChange = {
                    autoExtDisplay = it
                    AppPrefs.setAutoExternalDisplayEnabled(ctx, it)
                },
            )
            OpalSwitchRow(
                title = "接入外接屏时自动显示手机触控板",
                subtitle = "连上外接屏启动工作站时自动在手机拉起触控板",
                checked = autoShowPhoneTrackpad,
                onCheckedChange = { enabled ->
                    AppPrefs.setAutoShowPhoneTrackpadEnabled(ctx, enabled)
                },
            )

            Spacer(Modifier.height(10.dp))
            OpalText(text = "副屏投射模式", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = "自动模式下有线屏优先直投、无线电视优先标准 AOSP Presentation（规避黑屏并自动启用手机触控板）",
                fontSize = 11.sp,
                color = OpalPalette.TextMuted,
                lineHeight = 15.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            OpalSegmentedRow(
                options = listOf("自动 (推荐)", "强制 Presentation"),
                selectedIndex = projectionMode.coerceIn(AppPrefs.PROJECTION_MODE_AUTO, AppPrefs.PROJECTION_MODE_PRESENTATION),
                onSelect = { mode -> AppPrefs.setExternalProjectionMode(ctx, mode) },
            )

            Spacer(Modifier.height(12.dp))
            OpalText(text = "电视防裁边安全边距（Overscan）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = "若电视过扫描裁切了顶栏或程序坞，可实时增加纯黑安全留白",
                fontSize = 11.sp,
                color = OpalPalette.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            val overscanOpts = listOf(0f, 2f, 3.5f, 5f)
            val overscanIdx = overscanOpts.indexOfFirst { abs(it - overscanPct) < 0.25f }.let { if (it >= 0) it else 0 }
            OpalSegmentedRow(
                options = listOf("0% 关闭", "2%", "3.5%", "5%"),
                selectedIndex = overscanIdx,
                onSelect = { idx -> AppPrefs.setExternalOverscanPct(ctx, overscanOpts[idx]) },
            )

            Spacer(Modifier.height(12.dp))
            OpalButton(
                label = "打开系统无线投屏（Miracast）",
                filled = false,
                compact = true,
                accent = OpalPalette.AccentBlue,
                onClick = { WirelessDisplayHelper.openSystemWirelessCastSettings(ctx) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (detectedExtDisplays.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    detectedExtDisplays.forEach { d ->
                        val tag = WirelessDisplayHelper.formatTransportLabel(d.transport)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(OpalPalette.ChipFill)
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                OpalText(
                                    text = "${d.name} ($tag)",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                OpalText(
                                    text = "#${d.displayId} · ${d.width}×${d.height} @ ${d.refreshRateHz}Hz",
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = OpalPalette.TextMuted,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OpalButton(
                                label = "投射",
                                compact = true,
                                enabled = status.connected,
                                onClick = {
                                    onProjectSpecificDisplay(
                                        d.displayId,
                                        d.transport,
                                        ExternalDisplayPolicy.shouldForcePresentationForManualLaunch(
                                            d.transport,
                                            d.isPresentationCapable,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        ConsoleSectionTitle("画质与自适应")
        // 1. Unified Output Resolution & Refresh Rate Switcher (USB-C / HDMI EDID & Wi-Fi / USB Cast)
        MacGlassSectionCard {
            OpalText(text = "分辨率与刷新率", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            when {
                extDisplay != null -> {
                    val outPrefix = if (extDisplay.isWirelessDisplay) "当前无线电视输出" else "当前物理外接输出"
                    OpalText(
                        text = "$outPrefix：${extDisplay.name} · ${extDisplay.width}×${extDisplay.height} @ ${extDisplay.refreshRateHz}Hz",
                        fontSize = 12.sp,
                        color = OpalPalette.AccentCyan,
                    )
                    Spacer(Modifier.height(10.dp))
                    val modes = extDisplay.supportedModes
                    if (modes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            modes.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    pair.forEach { m ->
                                        val isCurrent = m.width == extDisplay.width &&
                                            m.height == extDisplay.height &&
                                            m.refreshRateHz == extDisplay.refreshRateHz
                                        OpalButton(
                                            label = "${m.width}×${m.height} · ${m.refreshRateHz}Hz",
                                            compact = true,
                                            filled = isCurrent,
                                            accent = if (isCurrent) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                                            onClick = {
                                                BridgeClient.setExternalDisplayModeAsync(
                                                    extDisplay.displayId, m.width, m.height, m.refreshRateHz.toFloat(),
                                                ) { ok ->
                                                    val msg = if (ok) "已切换输出至 ${m.width}×${m.height}@${m.refreshRateHz}Hz"
                                                    else "当前显示器未接受该模式"
                                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                remote.connected -> {
                    val curFps = (remote.fpsX100 / 100).coerceAtLeast(30)
                    OpalText(
                        text = "当前${remote.channelLabel}输出：${remote.peerName.ifBlank { "对端设备" }} · ${remote.width}×${remote.height} @ ${curFps}Hz",
                        fontSize = 12.sp,
                        color = OpalPalette.AccentCyan,
                    )
                    Spacer(Modifier.height(10.dp))
                    val castPresets = listOf(
                        Triple(1920, 1080, 60),
                        Triple(1920, 1080, 120),
                        Triple(2560, 1440, 60),
                        Triple(2560, 1440, 120),
                        Triple(2560, 1600, 60),
                        Triple(2560, 1600, 120),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        castPresets.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { (w, h, fps) ->
                                    val isCurrent = remote.width == w && remote.height == h && abs(curFps - fps) <= 3
                                    OpalButton(
                                        label = "${w}×${h} · ${fps}Hz",
                                        compact = true,
                                        filled = isCurrent,
                                        accent = if (isCurrent) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                                        onClick = {
                                            BridgeClient.setExternalDisplayModeAsync(-1, w, h, fps.toFloat()) { ok ->
                                                val msg = if (ok) "已动态切换投屏至 ${w}×${h}@${fps}Hz"
                                                else "切换投屏分辨率失败"
                                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                else -> {
                    OpalText(
                        text = "当前未连接 USB-C / HDMI 外接显示器或无线/USB 投屏；任一输出通道连接后，均可在此使用同一套界面直接切换分辨率与刷新率。",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
            }
        }

        // 3. Target Frame Rate & Output UI Scale (Unified for USB-C & Casting; DPI is adjusted visually inside DeX)
        MacGlassSectionCard {
            OpalText(text = "帧率与界面缩放", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            OpalText(text = "目标帧率", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(6.dp))
            val fpsOptions = listOf(0, 60, 90, 100, 120)
            val fpsIdx = fpsOptions.indexOf(status.targetFps).let { if (it >= 0) it else 0 }
            OpalSegmentedRow(
                options = listOf("自动", "60Hz", "90Hz", "100Hz", "120Hz"),
                selectedIndex = fpsIdx,
                onSelect = { idx ->
                    BridgeClient.updateDisplayPrefsAsync(
                        targetFps = fpsOptions[idx],
                        windowDpiScalePct = status.windowDpiScalePct,
                    )
                },
            )

            Spacer(Modifier.height(14.dp))
            OpalText(text = "桌面界面缩放（175% / 200% 适合客厅电视远看）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(6.dp))
            val scaleOpts = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            val scaleIdx = scaleOpts.indexOfFirst { abs(it - extUiScale) < 0.08f }.let { if (it >= 0) it else 1 }
            OpalSegmentedRow(
                options = listOf("100%", "125%", "150%", "175% TV", "200% TV"),
                selectedIndex = scaleIdx,
                onSelect = { idx ->
                    io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.setWorkingExternalUiScale(ctx, scaleOpts[idx])
                },
            )

            Spacer(Modifier.height(10.dp))
            OpalText(
                text = "应用窗口 DPI 请进入 DeX 桌面后，通过顶栏 DPI 入口实时可视化调节",
                fontSize = 11.5.sp,
                color = OpalPalette.TextMuted,
            )
        }

        ConsoleSectionTitle("键鼠与壁纸")
        // 4. Unified External Keyboard & Mouse Routing, Pointer Speed & Wallpapers
        MacGlassSectionCard {
            OpalText(text = "外接键鼠与桌面壁纸", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            OpalText(
                text = "外接键鼠输出目标",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = when {
                    status.boundMouseCount > 0 ->
                        "已将 ${status.boundMouseCount} 个外接键鼠设备优先绑定至投屏/外接端（后台切出也生效）"
                    status.connectedMouseCount > 0 && mouseTarget == AppPrefs.MOUSE_TARGET_EXTERNAL ->
                        "已检测到 ${status.connectedMouseCount} 个外接键鼠设备；开启外接或投屏后自动接管至投屏端"
                    status.connectedMouseCount > 0 ->
                        "已检测到 ${status.connectedMouseCount} 个外接键鼠设备，当前作用于本机屏幕"
                    else ->
                        "连接蓝牙 / USB 外置键盘或鼠标后，投屏与外接屏时默认优先控制投屏端"
                },
                fontSize = 11.sp,
                color = if (status.boundMouseCount > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            OpalSegmentedRow(
                options = listOf("跟随本机屏幕", "优先投屏 / 外接端"),
                selectedIndex = mouseTarget.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL),
                onSelect = { nextMode ->
                    AppPrefs.setMouseTarget(ctx, nextMode)
                    val activeExtId = resolveActiveExternalDisplayId(extDisplay)
                    BridgeClient.setMouseTargetAsync(nextMode, activeExtId) { bound ->
                        val msg = when {
                            nextMode == AppPrefs.MOUSE_TARGET_LOCAL -> "外接键鼠已切回本机屏幕"
                            bound > 0 -> "已将外接键鼠优先路由至投屏/外接端"
                            else -> "已设为优先投屏端，接入外接键鼠或开始投屏后立即生效"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "指针移动速度（同步触控板与鼠标）",
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OpalText(
                    text = String.format(Locale.US, "%.2f× · 复位", trackpadSens),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.AccentBlue,
                    maxLines = 1,
                    modifier = Modifier.clickable {
                        AppPrefs.setTrackpadSensitivity(ctx, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                        BridgeClient.setPointerSpeedAsync(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            OpalGlassSlider(
                value = trackpadSens,
                valueRange = AppPrefs.MIN_TRACKPAD_SENSITIVITY..AppPrefs.MAX_TRACKPAD_SENSITIVITY,
                onValueChange = { next ->
                    AppPrefs.setTrackpadSensitivity(ctx, next)
                    BridgeClient.setPointerSpeedAsync(next)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "0.50× 精细", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
                OpalText(text = "1.45× 标准", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
                OpalText(text = "3.00× 疾速", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = if (customWallpaperPath.isNotBlank()) "桌面壁纸（已启用相册壁纸）" else "桌面壁纸（支持右键 / 长按更换）",
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (customWallpaperPath.isNotBlank()) {
                        OpalButton(
                            label = "恢复预设",
                            compact = true,
                            filled = false,
                            onClick = {
                                WallpaperStorage.clearCustomWallpaper(ctx)
                                Toast.makeText(ctx, "已恢复预设壁纸", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    OpalButton(
                        label = "从相册选壁纸",
                        compact = true,
                        onClick = onPickCustomWallpaper,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val themes = if (isDark) {
                listOf(
                    "红杉深海" to listOf(Color(0xFF142842), Color(0xFF2A9D8F)),
                    "索诺玛暮色" to listOf(Color(0xFF2B2752), Color(0xFFE07A5F)),
                    "极地冰川" to listOf(Color(0xFF0E293D), Color(0xFF38BDF8)),
                    "曜石暗夜" to listOf(Color(0xFF1C1F26), Color(0xFF4B5563)),
                )
            } else {
                listOf(
                    "晴空云雾" to listOf(Color(0xFFC8DEFB), Color(0xFF60A5FA)),
                    "晨曦薄暮" to listOf(Color(0xFFFBDCE8), Color(0xFFF472B6)),
                    "冰川清露" to listOf(Color(0xFFBFE7F4), Color(0xFF38BDF8)),
                    "素白霜华" to listOf(Color(0xFFE9EEF6), Color(0xFFCBD5E1)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                themes.forEachIndexed { idx, (name, grad) ->
                    val selected = customWallpaperPath.isBlank() && idx == wallpaperPreset
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                WallpaperStorage.clearCustomWallpaper(ctx)
                                AppPrefs.setWallpaperPreset(ctx, idx)
                            }
                            .padding(vertical = 3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(grad))
                                .border(
                                    width = if (selected) 1.8.dp else 0.8.dp,
                                    color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                    shape = RoundedCornerShape(10.dp),
                                ),
                        )
                        Spacer(Modifier.height(4.dp))
                        OpalText(
                            text = name,
                            fontSize = 11.sp,
                            color = if (selected) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        ConsoleKeyboardShortcutsCard()
    }
}

/**
 * Tab 3: Engine & Advanced Settings — App Launcher Sorting, Input Backend Routing,
 * VirtualDisplay Pool Quota, Re-Activation & Daemon Shutdown.
 */
@Composable
fun ConsoleEngineSettingsTab(
    status: DaemonStatus,
    scene: CompositorScene,
    onOpenActivation: () -> Unit,
    onOpenUpdateModal: () -> Unit,
    onStopEngine: () -> Unit,
) {
    val ctx = LocalContext.current
    val autoEnterDex by AppPrefs.autoEnterDexFlow.collectAsState()
    val sortByUsage by AppPrefs.sortAppsByUsageFlow.collectAsState()
    val autoCheckUpdate by AppPrefs.autoCheckUpdateFlow.collectAsState()
    val updateState by AppUpdateManager.stateFlow.collectAsState()
    val currentVersion = remember(ctx) { AppUpdateManager.getCurrentVersionName(ctx) }

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

        ConsoleSectionTitle("引擎配置与场景")
        ConsoleAdvancedSettingsSection(status = status, scene = scene)
        TouchInputTroubleshootingTipCard(
            ctx = ctx,
            inputBlocked = status.connected && status.injectStatus == BridgeProtocol.InputBackend.STATUS_BLOCKED,
        )
        ConsoleSectionTitle("跨端音频与媒体")
        MacGlassSectionCard {
            val remoteMicEnabled by AppPrefs.remoteMicEnabledFlow.collectAsState()
            val remoteCameraEnabled by AppPrefs.remoteCameraEnabledFlow.collectAsState()
            val audioRouteMode by AppPrefs.audioRouteModeFlow.collectAsState()
            val canUseRootCamera = status.connected && status.rootEnhanced
            val effectiveCameraEnabled = canUseRootCamera && remoteCameraEnabled
            val splitAudioOn = audioRouteMode == BridgeProtocol.AudioRoute.SPLIT_BY_APP
            androidx.compose.runtime.LaunchedEffect(Unit) {
                AppPrefs.hydrateFlows(ctx)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpalText(text = "音频分流与远端媒体", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OpalText(
                        text = "支持手机 / DeX 按应用独立发声，以及远端麦克风与摄像头映射",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val anyEnabled = splitAudioOn || remoteMicEnabled || effectiveCameraEnabled
                OpalStatusBadge(
                    label = when {
                        splitAudioOn -> "应用分流中"
                        anyEnabled -> "已启用"
                        else -> "默认"
                    },
                    color = if (anyEnabled) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(10.dp))
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

        ConsoleSectionTitle("通用与更新")
        MacGlassSectionCard {
            val notifAccessGranted by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.accessGrantedFlow.collectAsState()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.refreshAccessState(ctx)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpalText(text = "DeX 桌面通知同步", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OpalText(
                        text = if (notifAccessGranted) {
                            "已授权通知使用权 · 手机消息将实时同步到 DeX 右上角"
                        } else {
                            "开启通知使用权后，可在 DeX 桌面实时接收并点击打开手机消息"
                        },
                        fontSize = 12.sp,
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
        }
        MacGlassSectionCard {
            OpalText(text = "启动行为与应用列表", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            OpalSwitchRow(
                title = "打开应用自动进入 DeX 模式",
                subtitle = "仅冷启动时检测多窗引擎；引擎已激活（或 Shizuku / Root 自动激活完成）后直接进入本机 DeX 桌面，未激活或投屏中留在控制台",
                checked = autoEnterDex,
                onCheckedChange = { enabled ->
                    AppPrefs.setAutoEnterDexEnabled(ctx, enabled)
                },
            )
            OpalSwitchRow(
                title = "应用列表按打开次数排序",
                subtitle = "默认开启；同步作用于 DeX 应用抽屉与远程独立应用小窗，关闭后按名称排序",
                checked = sortByUsage,
                onCheckedChange = { enabled ->
                    AppPrefs.setSortAppsByUsage(ctx, enabled)
                },
            )
        }

        MacGlassSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpalText(text = "应用更新", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OpalText(
                        text = "当前版本 v$currentVersion · GitHub Releases 直连校验",
                        fontSize = 12.sp,
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
                Spacer(Modifier.height(8.dp))
                OpalText(
                    text = (updateState as AppUpdateManager.UpdateState.Error).message,
                    fontSize = 12.sp,
                    color = OpalPalette.AccentDanger,
                )
            }

            Spacer(Modifier.height(8.dp))
            OpalSwitchRow(
                title = "启动时自动检查新版本",
                subtitle = "每 6 小时静默检查一次 GitHub Releases，发现正式版更新后在顶栏轻提醒",
                checked = autoCheckUpdate,
                onCheckedChange = { enabled ->
                    AppPrefs.setAutoCheckUpdate(ctx, enabled)
                },
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val primaryLabel = when (val st = updateState) {
                    is AppUpdateManager.UpdateState.Checking -> "正在检查更新…"
                    is AppUpdateManager.UpdateState.UpdateAvailable -> "更新至 ${st.release.tagName}"
                    is AppUpdateManager.UpdateState.Downloading -> "查看下载进度"
                    is AppUpdateManager.UpdateState.ReadyToInstall -> "立即安装 ${st.release.tagName}"
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
                    modifier = Modifier.weight(1.35f),
                )
                OpalButton(
                    label = "GitHub Releases ↗",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentCyan,
                    onClick = {
                        AppUpdateManager.openReleaseInBrowser(ctx, AppUpdateLogic.ALL_RELEASES_WEB_URL)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ConsoleSectionTitle("引擎底层")
        MacGlassSectionCard {
            OpalText(text = "输入路由与虚拟屏配额", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = "默认系统直通；如遇触摸被拦截，可切换至 Linux /dev/uhid 虚拟外设。",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))

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
                    io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.setWorkingInputBackendMode(mode)
                    BridgeClient.updateCompositorConfigAsync(inputBackendMode = mode)
                },
            )

            Spacer(Modifier.height(16.dp))
            OpalText(
                text = "活跃虚拟屏并发上限（超出部分自动休眠为快照）",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            val poolOptions = listOf(3, 4, 5, 6, 8, 10)
            val curMaxIdx = poolOptions.indexOf(status.maxVdCount).let { if (it >= 0) it else 3 }
            OpalSegmentedRow(
                options = poolOptions.map { "$it 屏" },
                selectedIndex = curMaxIdx,
                onSelect = { idx ->
                    io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.setWorkingMaxVdCount(poolOptions[idx])
                    BridgeClient.updateCompositorConfigAsync(maxVdCount = poolOptions[idx])
                },
            )
        }

        if (status.connected) {
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

@Composable
private fun CopyableInfoLine(ctx: Context, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            OpalText(text = label, fontSize = 11.sp, color = OpalPalette.TextMuted)
            OpalText(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(OpalPalette.ChipFill)
                .clickable { copyTextWithToast(ctx, value, "已复制 $label") }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            OpalText(text = "复制", fontSize = 11.sp, color = OpalPalette.AccentCyan, fontWeight = FontWeight.Medium)
        }
    }
}

private fun resolveActiveExternalDisplayId(extDisplay: BridgeService.ExternalDisplayInfo?): Int {
    val session = ExternalTrackpadBridge.sessionFlow.value
    if (session.displayId > 0) return session.displayId
    return extDisplay?.displayId ?: -1
}

private fun copyTextWithToast(ctx: Context, text: String, toastMsg: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk", text))
        Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
    }
}

private fun openDeveloperSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: Throwable) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            Toast.makeText(ctx, "请手动打开系统「设置 → 开发者选项」", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun DashboardHeroIconBadge(
    accent: Color,
    active: Boolean,
) {
    val isDark = OpalPalette.isDark
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(accent.copy(alpha = if (isDark) 0.24f else 0.14f))
            .border(0.9.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = w * 0.095f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val r = CornerRadius(w * 0.14f, w * 0.14f)
            drawRoundRect(accent, Offset(w * 0.05f, h * 0.10f), Size(w * 0.90f, h * 0.62f), r, style = stroke)
            if (active) {
                drawRoundRect(accent.copy(alpha = 0.28f), Offset(w * 0.16f, h * 0.22f), Size(w * 0.30f, h * 0.36f), CornerRadius(w * 0.08f, w * 0.08f))
                drawRoundRect(accent.copy(alpha = 0.45f), Offset(w * 0.54f, h * 0.22f), Size(w * 0.30f, h * 0.36f), CornerRadius(w * 0.08f, w * 0.08f))
            }
            drawLine(accent, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.68f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun DashboardBentoTile(
    title: String,
    subtitle: String,
    badgeText: String,
    accent: Color,
    active: Boolean,
    iconKind: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = OpalPalette.isDark
    val shape = RoundedCornerShape(22.dp)
    val fillBrush = if (active) {
        Brush.verticalGradient(
            if (isDark) listOf(accent.copy(alpha = 0.22f), Color(0xE0161C29))
            else listOf(accent.copy(alpha = 0.14f), Color(0xF0FFFFFF)),
        )
    } else {
        OpalPalette.LiquidDockFill
    }
    val rimBrush = if (active) {
        Brush.verticalGradient(listOf(accent.copy(alpha = 0.62f), accent.copy(alpha = 0.18f)))
    } else {
        OpalPalette.LiquidGlassRim
    }
    Column(
        modifier = modifier
            .liquidGlassSurface(
                shape = shape,
                fill = fillBrush,
                rim = rimBrush,
                elevation = if (isDark) 12.dp else 9.dp,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (isDark) 0.22f else 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(17.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = Stroke(width = w * 0.105f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    if (iconKind == 0) {
                        drawRoundRect(accent, Offset(w * 0.06f, h * 0.12f), Size(w * 0.88f, h * 0.58f), CornerRadius(w * 0.14f, w * 0.14f), style = stroke)
                        drawLine(accent, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                        drawLine(accent, Offset(w * 0.28f, h * 0.88f), Offset(w * 0.72f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    } else {
                        drawArc(accent, startAngle = 215f, sweepAngle = 110f, useCenter = false, topLeft = Offset(w * 0.08f, h * 0.18f), size = Size(w * 0.84f, h * 0.84f), style = stroke)
                        drawArc(accent, startAngle = 215f, sweepAngle = 110f, useCenter = false, topLeft = Offset(w * 0.26f, h * 0.36f), size = Size(w * 0.48f, h * 0.48f), style = stroke)
                        drawCircle(accent, radius = w * 0.10f, center = Offset(w * 0.50f, h * 0.76f))
                    }
                }
            }
            OpalStatusBadge(
                label = badgeText,
                color = if (active) accent else OpalPalette.TextMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OpalText(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            OpalText(
                text = subtitle,
                fontSize = 11.5.sp,
                color = if (active) accent else OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun UpgradeReactivateTipCard(
    connected: Boolean,
    onOpenActivation: () -> Unit,
) {
    val shape = CircleShape
    val accent = OpalPalette.AccentBlue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OpalPalette.LiquidDockFill)
            .border(0.8.dp, OpalPalette.BorderSubtle, shape)
            .clickable(onClick = onOpenActivation)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpalText(text = "↻", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
        OpalText(
            text = "升级版本后建议重新激活一次引擎以生效最新修复",
            fontSize = 11.5.sp,
            color = OpalPalette.TextSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        OpalText(
            text = if (connected) "重新激活 ›" else "去激活 ›",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

@Composable
private fun TouchInputTroubleshootingTipCard(
    ctx: Context,
    inputBlocked: Boolean,
) {
    val accent = if (inputBlocked) OpalPalette.AccentAmber else OpalPalette.AccentCyan
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OpalPalette.LiquidDockFill)
            .border(0.8.dp, accent.copy(alpha = if (inputBlocked) 0.55f else 0.24f), shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            OpalText(text = if (inputBlocked) "!" else "i", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        Column(modifier = Modifier.weight(1f)) {
            OpalText(
                text = if (inputBlocked) "检测到触摸输入受限" else "触摸无反应排查",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (inputBlocked) OpalPalette.AccentAmber else OpalPalette.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            OpalText(
                text = if (inputBlocked) {
                    "当前系统已拦截跨屏触控（含 UHID 虚拟屏绑定），请在手机「开发者选项」开启「USB 调试（安全设置）」后重新连接。"
                } else {
                    "若桌面无法点击，请在开发者选项开启「USB 调试（安全设置）」，或在「设置」切至 /dev/uhid。"
                },
                fontSize = 11.5.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 16.sp,
            )
        }
        OpalButton(
            label = "去设置",
            compact = true,
            filled = inputBlocked,
            accent = accent,
            onClick = { openDeveloperSettings(ctx) },
        )
    }
}

private fun toggleHostScreenPowerWithToast(ctx: Context, screenOn: Boolean) {
    BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
        val msg = when {
            !ok -> "当前设备暂不支持物理息屏控制"
            screenOn -> "已点亮本机屏幕"
            else -> "已物理熄灭本机屏幕（双击屏幕或按音量键/电源键可随时亮屏，断开投屏也会自动亮屏）"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
