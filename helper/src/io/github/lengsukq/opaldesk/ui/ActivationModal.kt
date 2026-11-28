package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.AdbBootstrap
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.web.WebActivationService

/**
 * Beginner-friendly 4-channel Rootless-First Activation Modal:
 * 1. Built-in Wireless Debugging (`libadb.so` + mDNS auto-discovery + notification 6-digit reply)
 * 2. Shizuku / Sui 1-Tap Activation
 * 3. PC ADB 1-line command
 * 4. Optional Root (`su` -> drops to `uid=2000` `u:r:shell:s0`, with optional `--root-enhance`)
 */
@Composable
fun ActivationModal(
    needsLocalNetworkPerm: Boolean = false,
    onRequestLocalNetworkPerm: () -> Unit = {},
    onDismiss: () -> Unit,
    onActivated: () -> Unit,
) {
    val ctx = LocalContext.current
    val daemonStatus by BridgeClient.statusFlow.collectAsState()
    val webActState by WebActivationService.stateFlow.collectAsState()
    // Clear sUserStopping whenever the user explicitly opens ActivationModal so a prior
    // "停止引擎" action never causes BridgeProvider to reject the newly started daemon's Binder.
    LaunchedEffect(Unit) {
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(ctx)
    }
    // Remember whether the daemon was already connected when the user explicitly opened this modal
    // (e.g. tapping "重新激活"): never auto-dismiss immediately on open; only auto-dismiss after
    // a fresh connection transition (disconnected -> connected) or explicit bootstrap completion.
    val initiallyConnected = remember { BridgeClient.statusFlow.value.connected }
    var sawDisconnected by remember { mutableStateOf(!initiallyConnected) }
    LaunchedEffect(daemonStatus.connected) {
        if (!daemonStatus.connected) {
            sawDisconnected = true
        } else if (sawDisconnected) {
            onActivated()
        }
    }
    var tabIndex by remember {
        mutableIntStateOf(
            when {
                WebActivationService.isRunning -> 1
                ShizukuBootstrap.isBinderAlive() -> 2
                else -> 0
            },
        )
    }

    var isBusy by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf("") }
    var feedbackError by remember { mutableStateOf(false) }

    // Wireless ADB live discovery state
    var pairPortText by remember { mutableStateOf("") }
    var pairCodeText by remember { mutableStateOf("") }
    var connectPortText by remember { mutableStateOf("") }

    // Shizuku state
    var shizukuSummary by remember { mutableStateOf(ShizukuBootstrap.buildStateSummary(ctx)) }
    var autoShizuku by remember { mutableStateOf(AppPrefs.isAutoShizukuEnabled(ctx)) }

    // Root state
    var autoRoot by remember { mutableStateOf(AppPrefs.isAutoRootEnabled(ctx)) }
    var rootEnhance by remember { mutableStateOf(AppPrefs.isRootEnhanceEnabled(ctx)) }
    var copiedPcCmd by remember { mutableStateOf(false) }

    val progressListener = remember {
        object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) {
                isBusy = true
                feedbackError = false
                feedbackText = message.orEmpty()
            }

            override fun onResult(success: Boolean, message: String?) {
                isBusy = false
                feedbackError = !success
                feedbackText = message.orEmpty()
                if (success) {
                    onActivated()
                }
            }
        }
    }

    LaunchedEffect(needsLocalNetworkPerm) {
        if (!needsLocalNetworkPerm) {
            AdbBootstrap.startDiscovery(ctx) { state ->
                if (state != null) {
                    if (state.pairingPort > 0) pairPortText = state.pairingPort.toString()
                    if (state.connectPort > 0) connectPortText = state.connectPort.toString()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        AdbBootstrap.startDiscovery(ctx) { state ->
            if (state != null) {
                if (state.pairingPort > 0) pairPortText = state.pairingPort.toString()
                if (state.connectPort > 0) connectPortText = state.connectPort.toString()
            }
        }
        val cur = AdbBootstrap.getDiscoveryState()
        if (cur.pairingPort > 0) pairPortText = cur.pairingPort.toString()
        if (cur.connectPort > 0) connectPortText = cur.connectPort.toString()

        ShizukuBootstrap.bindUiCallbacks(
            onStateChanged = { shizukuSummary = ShizukuBootstrap.buildStateSummary(ctx) },
            onPermissionResult = { granted ->
                shizukuSummary = ShizukuBootstrap.buildStateSummary(ctx)
                if (granted) {
                    isBusy = true
                    AdbBootstrap.shizukuBootstrapAsync(ctx, autoShizuku, progressListener)
                }
            },
        )
        onDispose {
            AdbBootstrap.stopDiscoveryIfIdle()
            ShizukuBootstrap.unbindUiCallbacks()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(enabled = !isBusy, onClick = onDismiss)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlassCard(
            modifier = Modifier
                .widthIn(max = 580.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures {} },
            cornerRadius = 26.dp,
            fillColor = OpalPalette.GlassPopover,
            contentPadding = PaddingValues(22.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = stringResource(R.string.activation_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    OpalText(
                        text = stringResource(R.string.activation_subtitle),
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                OpalGlyphButton(glyph = ChromeGlyph.CLOSE, size = 28.dp) {
                    if (!isBusy) onDismiss()
                }
            }

            Spacer(Modifier.height(14.dp))
            OpalSegmentedRow(
                options = listOf(
                    stringResource(R.string.activation_tab_wireless),
                    stringResource(R.string.activation_tab_web),
                    stringResource(R.string.activation_tab_shizuku),
                    stringResource(R.string.activation_tab_pc),
                    stringResource(R.string.activation_tab_root),
                ),
                selectedIndex = tabIndex,
                onSelect = { if (!isBusy) tabIndex = it },
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tabIndex) {
                    0 -> WirelessAdbActivationPane(
                        ctx = ctx,
                        isBusy = isBusy,
                        needsLocalNetworkPerm = needsLocalNetworkPerm,
                        onRequestLocalNetworkPerm = onRequestLocalNetworkPerm,
                        pairPortText = pairPortText,
                        onPairPortChange = { pairPortText = it.filter(Char::isDigit) },
                        pairCodeText = pairCodeText,
                        onPairCodeChange = { pairCodeText = it.filter(Char::isDigit).take(6) },
                        connectPortText = connectPortText,
                        onConnectPortChange = { connectPortText = it.filter(Char::isDigit) },
                        onPairAndStart = {
                            val pPort = pairPortText.toIntOrNull() ?: 0
                            val cPort = connectPortText.toIntOrNull() ?: 0
                            AdbBootstrap.pairAndBootstrapAsync(
                                ctx,
                                AdbBootstrap.getDiscoveryState().pairingHost,
                                pPort,
                                pairCodeText,
                                AdbBootstrap.getDiscoveryState().connectHost,
                                cPort,
                                progressListener,
                            )
                        },
                        onDirectConnect = {
                            val cPort = connectPortText.toIntOrNull() ?: 0
                            AdbBootstrap.connectAndBootstrapAsync(
                                ctx,
                                AdbBootstrap.getDiscoveryState().connectHost,
                                cPort,
                                progressListener,
                            )
                        },
                    )

                    1 -> WebLanActivationPane(
                        ctx = ctx,
                        state = webActState,
                        needsLocalNetworkPerm = needsLocalNetworkPerm,
                        onRequestLocalNetworkPerm = onRequestLocalNetworkPerm,
                        onCopyUrl = { url ->
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk Web Activation", url))
                            feedbackError = false
                            feedbackText = ctx.getString(R.string.activation_web_copied)
                        },
                    )

                    2 -> ShizukuActivationPane(
                        ctx = ctx,
                        isBusy = isBusy,
                        summary = shizukuSummary,
                        autoShizuku = autoShizuku,
                        onAutoShizukuChange = {
                            autoShizuku = it
                            AppPrefs.setAutoShizukuEnabled(ctx, it)
                        },
                        onActivate = {
                            if (ShizukuBootstrap.isReadyAndGranted()) {
                                AdbBootstrap.shizukuBootstrapAsync(ctx, autoShizuku, progressListener)
                            } else if (!ShizukuBootstrap.requestPermissionIfPossible()) {
                                ShizukuBootstrap.openShizukuAppOrWebsite(ctx)
                            }
                        },
                    )

                    3 -> PcAdbActivationPane(
                        copied = copiedPcCmd,
                        onCopy = { cmd ->
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk ADB", cmd))
                            copiedPcCmd = true
                            feedbackError = false
                            feedbackText = ctx.getString(R.string.activation_pc_copied)
                        },
                    )

                    4 -> OptionalRootActivationPane(
                        isBusy = isBusy,
                        autoRoot = autoRoot,
                        onAutoRootChange = {
                            autoRoot = it
                            AppPrefs.setAutoRootEnabled(ctx, it)
                        },
                        rootEnhance = rootEnhance,
                        onRootEnhanceChange = {
                            rootEnhance = it
                            AppPrefs.setRootEnhanceEnabled(ctx, it)
                        },
                        onActivateRoot = {
                            AdbBootstrap.rootBootstrapAsync(
                                ctx = ctx,
                                markAutoRoot = autoRoot,
                                rootEnhance = rootEnhance,
                                listener = progressListener,
                            )
                        },
                    )
                }

                if (feedbackText.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (feedbackError) OpalPalette.AccentDanger.copy(alpha = 0.16f)
                                else OpalPalette.AccentBlue.copy(alpha = 0.16f),
                            )
                            .padding(12.dp),
                    ) {
                        OpalText(
                            text = feedbackText,
                            fontSize = 12.5.sp,
                            color = if (feedbackError) OpalPalette.AccentDanger else OpalPalette.TextPrimary,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WirelessAdbActivationPane(
    ctx: Context,
    isBusy: Boolean,
    needsLocalNetworkPerm: Boolean,
    onRequestLocalNetworkPerm: () -> Unit,
    pairPortText: String,
    onPairPortChange: (String) -> Unit,
    pairCodeText: String,
    onPairCodeChange: (String) -> Unit,
    connectPortText: String,
    onConnectPortChange: (String) -> Unit,
    onPairAndStart: () -> Unit,
    onDirectConnect: () -> Unit,
) {
    OpalText(
        text = stringResource(R.string.activation_wireless_desc),
        fontSize = 12.5.sp,
        color = OpalPalette.TextSecondary,
        lineHeight = 18.sp,
    )
    if (needsLocalNetworkPerm) {
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(OpalPalette.AccentAmber.copy(alpha = 0.14f))
                .border(1.dp, OpalPalette.AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OpalText(
                    text = stringResource(R.string.activation_local_net_desc),
                    fontSize = 12.sp,
                    color = OpalPalette.TextPrimary,
                    lineHeight = 17.sp,
                )
                OpalButton(
                    label = stringResource(R.string.activation_grant_local_net),
                    enabled = !isBusy,
                    accent = OpalPalette.AccentAmber,
                    onClick = onRequestLocalNetworkPerm,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    OpalButton(
        label = stringResource(R.string.activation_step1_btn),
        enabled = !isBusy,
        accent = OpalPalette.AccentCyan,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            AdbBootstrap.showPairingNotification(ctx, ctx.getString(R.string.activation_notif_hint))
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
                }
            }
        },
    )

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OpalTextInput(
            value = pairPortText,
            onValueChange = onPairPortChange,
            placeholder = stringResource(R.string.activation_pair_port_hint),
            modifier = Modifier.weight(1f),
        )
        OpalTextInput(
            value = pairCodeText,
            onValueChange = onPairCodeChange,
            placeholder = stringResource(R.string.activation_pair_code_hint),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OpalTextInput(
            value = connectPortText,
            onValueChange = onConnectPortChange,
            placeholder = stringResource(R.string.activation_connect_port_hint),
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = stringResource(R.string.activation_direct_connect),
            enabled = !isBusy && connectPortText.isNotBlank(),
            filled = false,
            onClick = onDirectConnect,
        )
    }
    Spacer(Modifier.height(10.dp))
    OpalButton(
        label = stringResource(if (isBusy) R.string.activation_working else R.string.activation_step2_btn),
        enabled = !isBusy && pairCodeText.length >= 6,
        onClick = onPairAndStart,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OpalText(
        text = stringResource(R.string.activation_wireless_tip),
        fontSize = 11.5.sp,
        color = OpalPalette.TextMuted,
        lineHeight = 16.5.sp,
    )
}

@Composable
private fun ShizukuActivationPane(
    ctx: Context,
    isBusy: Boolean,
    summary: String,
    autoShizuku: Boolean,
    onAutoShizukuChange: (Boolean) -> Unit,
    onActivate: () -> Unit,
) {
    OpalText(text = summary, fontSize = 13.sp, color = OpalPalette.TextPrimary, lineHeight = 19.sp)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OpalButton(
            label = stringResource(if (ShizukuBootstrap.isReadyAndGranted()) R.string.activation_shizuku_go else R.string.activation_shizuku_auth),
            enabled = !isBusy,
            onClick = onActivate,
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = stringResource(R.string.activation_shizuku_open),
            enabled = !isBusy,
            filled = false,
            onClick = { ShizukuBootstrap.openShizukuAppOrWebsite(ctx) },
        )
    }
    Spacer(Modifier.height(8.dp))
    OpalSwitchRow(
        title = stringResource(R.string.activation_shizuku_auto_title),
        subtitle = stringResource(R.string.activation_shizuku_auto_sub),
        checked = autoShizuku,
        onCheckedChange = onAutoShizukuChange,
    )
}

@Composable
private fun PcAdbActivationPane(
    copied: Boolean,
    onCopy: (String) -> Unit,
) {
    val cmd = remember { AdbBootstrap.buildPcAdbCommand() }
    OpalText(
        text = stringResource(R.string.activation_pc_desc),
        fontSize = 12.5.sp,
        color = OpalPalette.TextSecondary,
        lineHeight = 18.sp,
    )
    Spacer(Modifier.height(10.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (OpalPalette.isDark) Color(0xFF070B14) else Color(0xFFF1F5F9))
            .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        OpalText(
            text = cmd,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = OpalPalette.AccentCyan,
            maxLines = 5,
            lineHeight = 16.sp,
        )
    }
    Spacer(Modifier.height(12.dp))
    OpalButton(
        label = stringResource(if (copied) R.string.activation_pc_copied_btn else R.string.activation_pc_copy_btn),
        onClick = { onCopy(cmd) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OptionalRootActivationPane(
    isBusy: Boolean,
    autoRoot: Boolean,
    onAutoRootChange: (Boolean) -> Unit,
    rootEnhance: Boolean,
    onRootEnhanceChange: (Boolean) -> Unit,
    onActivateRoot: () -> Unit,
) {
    OpalText(
        text = stringResource(R.string.activation_root_desc),
        fontSize = 12.5.sp,
        color = OpalPalette.TextSecondary,
        lineHeight = 18.sp,
    )
    Spacer(Modifier.height(10.dp))
    OpalSwitchRow(
        title = stringResource(R.string.activation_root_enhance_title),
        subtitle = stringResource(R.string.activation_root_enhance_sub),
        checked = rootEnhance,
        onCheckedChange = onRootEnhanceChange,
    )
    OpalSwitchRow(
        title = stringResource(R.string.activation_root_auto_title),
        subtitle = stringResource(R.string.activation_root_auto_sub),
        checked = autoRoot,
        onCheckedChange = onAutoRootChange,
    )
    Spacer(Modifier.height(12.dp))
    OpalButton(
        label = stringResource(if (isBusy) R.string.activation_root_busy else R.string.activation_root_go),
        enabled = !isBusy,
        accent = OpalPalette.AccentEmerald,
        onClick = onActivateRoot,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WebLanActivationPane(
    ctx: Context,
    state: WebActivationService.State,
    needsLocalNetworkPerm: Boolean,
    onRequestLocalNetworkPerm: () -> Unit,
    onCopyUrl: (String) -> Unit,
) {
    OpalText(
        text = stringResource(R.string.activation_web_desc),
        fontSize = 12.5.sp,
        color = OpalPalette.TextSecondary,
        lineHeight = 18.sp,
    )
    if (needsLocalNetworkPerm) {
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(OpalPalette.AccentAmber.copy(alpha = 0.14f))
                .border(1.dp, OpalPalette.AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OpalText(
                    text = stringResource(R.string.activation_web_local_net),
                    fontSize = 12.sp,
                    color = OpalPalette.TextPrimary,
                    lineHeight = 17.sp,
                )
                OpalButton(
                    label = stringResource(R.string.activation_grant_local_net),
                    accent = OpalPalette.AccentAmber,
                    onClick = onRequestLocalNetworkPerm,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (!state.running) {
        OpalButton(
            label = stringResource(R.string.activation_web_start),
            enabled = !needsLocalNetworkPerm,
            accent = OpalPalette.AccentBlue,
            modifier = Modifier.fillMaxWidth(),
            onClick = { WebActivationService.start(ctx) },
        )
        if (state.lastMessage.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            OpalText(
                text = state.lastMessage,
                fontSize = 12.sp,
                color = if (state.lastError) OpalPalette.AccentDanger else OpalPalette.TextSecondary,
            )
        }
        return
    }

    val actUrl = state.activationUrl
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OpalPalette.ChipFill)
            .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (actUrl.isNotEmpty()) {
            OpalQrCodeCard(content = actUrl, qrSize = 164.dp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                OpalText(
                    text = stringResource(R.string.activation_web_service_addr, state.lanIp, state.port),
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                val portHint = stringResource(
                    R.string.activation_web_ports,
                    if (state.pairingPort > 0) state.pairingPort.toString() else stringResource(R.string.activation_web_port_waiting),
                    if (state.connectPort > 0) state.connectPort.toString() else stringResource(R.string.activation_web_port_waiting),
                )
                OpalText(text = portHint, fontSize = 11.sp, color = OpalPalette.TextSecondary)
            }
            Spacer(Modifier.width(8.dp))
            OpalStatusBadge(
                label = stringResource(R.string.activation_web_code, state.sessionCode),
                color = OpalPalette.AccentEmerald,
            )
        }
        if (state.lastMessage.isNotBlank()) {
            OpalText(
                text = state.lastMessage,
                fontSize = 11.5.sp,
                color = if (state.lastError) OpalPalette.AccentDanger else OpalPalette.AccentCyan,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OpalButton(
                label = stringResource(R.string.activation_web_copy_url),
                compact = true,
                filled = false,
                enabled = actUrl.isNotEmpty(),
                onClick = { onCopyUrl(actUrl) },
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = stringResource(R.string.activation_web_stop),
                compact = true,
                filled = false,
                accent = OpalPalette.AccentDanger,
                onClick = { WebActivationService.stop(ctx) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    OpalButton(
        label = stringResource(R.string.activation_web_open_dev),
        accent = OpalPalette.AccentCyan,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
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
                }
            }
        },
    )
}
