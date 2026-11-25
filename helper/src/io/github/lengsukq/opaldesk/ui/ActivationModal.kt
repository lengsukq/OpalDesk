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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.AdbBootstrap
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
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
                    OpalText(text = "激活 OpalDesk 通用多窗引擎", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    OpalText(
                        text = "95%+ 核心能力完全免 Root（运行于标准 ADB Shell uid=2000），适配全品牌 Android 11–17 设备",
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
                options = listOf("无线调试", "Web激活", "Shizuku", "电脑ADB", "Root"),
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
                            feedbackText = "已复制 Web 局域网激活链接，在电脑/平板浏览器打开即可。"
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
                            feedbackText = "已复制跨平台单行 ADB 激活命令，在电脑终端粘贴回车即可。"
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
        text = "免电脑 · 内置本机无线调试激活：点击下方按钮打开「开发者选项 → 无线调试」，在通知栏直接输入 6 位配对码即可完成激活。",
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
                    text = "Android 17 需要「本地网络」权限才能自动发现本机无线调试端口（未授权时仍可手动输入端口）。",
                    fontSize = 12.sp,
                    color = OpalPalette.TextPrimary,
                    lineHeight = 17.sp,
                )
                OpalButton(
                    label = "立即授权本地网络权限",
                    enabled = !isBusy,
                    accent = OpalPalette.AccentAmber,
                    onClick = onRequestLocalNetworkPerm,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    OpalButton(
        label = "① 发送配对通知并打开「开发者选项」",
        enabled = !isBusy,
        accent = OpalPalette.AccentCyan,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            AdbBootstrap.showPairingNotification(ctx, "请开启「无线调试 → 使用配对码配对设备」，直接在通知中输入 6 位配对码")
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
            placeholder = "配对端口 (mDNS 自动发现)",
            modifier = Modifier.weight(1f),
        )
        OpalTextInput(
            value = pairCodeText,
            onValueChange = onPairCodeChange,
            placeholder = "6 位配对码",
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OpalTextInput(
            value = connectPortText,
            onValueChange = onConnectPortChange,
            placeholder = "连接端口 (无线调试主页端口)",
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = "已配对·直连",
            enabled = !isBusy && connectPortText.isNotBlank(),
            filled = false,
            onClick = onDirectConnect,
        )
    }
    Spacer(Modifier.height(10.dp))
    OpalButton(
        label = if (isBusy) "正在执行无线调试激活…" else "② 配对并启动守护进程",
        enabled = !isBusy && pairCodeText.length >= 6,
        onClick = onPairAndStart,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OpalText(
        text = "提示：若未连接电脑 USB 调试，激活成功后请保持系统「无线调试」开启（部分机型关闭无线调试会连带终止后台守护进程）。",
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
            label = if (ShizukuBootstrap.isReadyAndGranted()) "Shizuku 一键秒激活" else "授权并激活 Shizuku",
            enabled = !isBusy,
            onClick = onActivate,
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = "打开 Shizuku",
            enabled = !isBusy,
            filled = false,
            onClick = { ShizukuBootstrap.openShizukuAppOrWebsite(ctx) },
        )
    }
    Spacer(Modifier.height(8.dp))
    OpalSwitchRow(
        title = "打开应用时自动通过 Shizuku 静默重连",
        subtitle = "设备重启且 Shizuku 已就绪时自动拉起守护进程",
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
        text = "将手机通过数据线连接电脑（开启 USB 调试），在 macOS / Linux / Windows 终端执行以下单行命令即可：",
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
        label = if (copied) "已复制到剪贴板 ✓" else "复制单行 ADB 激活命令",
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
        text = "遵循最小权限安全设计：Root (Magisk / KernelSU / APatch) 仅用于校验 JAR 哈希与可选全局增强，随后立即强制降权至标准 Shell (uid=2000, u:r:shell:s0) 运行。",
        fontSize = 12.5.sp,
        color = OpalPalette.TextSecondary,
        lineHeight = 18.sp,
    )
    Spacer(Modifier.height(10.dp))
    OpalSwitchRow(
        title = "启用可选 Root 增强 (~5% 顽固应用解锁)",
        subtitle = "预置 AOSP 全局可调尺寸开关 (force_resizable_activities)，解决极少数死锁方向的老旧应用",
        checked = rootEnhance,
        onCheckedChange = onRootEnhanceChange,
    )
    OpalSwitchRow(
        title = "打开应用时自动静默激活",
        subtitle = "已授予 Root 权限后免手动点击",
        checked = autoRoot,
        onCheckedChange = onAutoRootChange,
    )
    Spacer(Modifier.height(12.dp))
    OpalButton(
        label = if (isBusy) "正在请求 Root 并降权启动…" else "Root 一键授权并降权激活",
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
        text = "免数据线 · 免电脑装 ADB：开启下方临时局域网激活服务后，用电脑或平板浏览器扫码打开 GitHub Pages，再把手机停留在系统「无线调试 → 配对码」弹窗即可在电脑上远程输入配对码完成激活并自动进入 Web 投屏。",
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
                    text = "Android 17 需要「本地网络」权限，才能开启局域网 Web 激活服务并接收电脑/平板浏览器连接。",
                    fontSize = 12.sp,
                    color = OpalPalette.TextPrimary,
                    lineHeight = 17.sp,
                )
                OpalButton(
                    label = "立即授权本地网络权限",
                    accent = OpalPalette.AccentAmber,
                    onClick = onRequestLocalNetworkPerm,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (!state.running) {
        OpalButton(
            label = "开启 Web 局域网激活服务（5 分钟自动关闭）",
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
                    text = "局域网服务 ${state.lanIp}:${state.port}",
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                val portHint = buildString {
                    append("配对端口: ")
                    append(if (state.pairingPort > 0) state.pairingPort.toString() else "等待发现")
                    append(" · 连接端口: ")
                    append(if (state.connectPort > 0) state.connectPort.toString() else "等待发现")
                }
                OpalText(text = portHint, fontSize = 11.sp, color = OpalPalette.TextSecondary)
            }
            Spacer(Modifier.width(8.dp))
            OpalStatusBadge(
                label = "访问码 ${state.sessionCode}",
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
                label = "复制 Web 链接",
                compact = true,
                filled = false,
                enabled = actUrl.isNotEmpty(),
                onClick = { onCopyUrl(actUrl) },
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = "停止服务",
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
        label = "打开系统「开发者选项 → 无线调试」（后台保持服务运行）",
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
