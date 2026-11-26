package io.github.lengsukq.opaldesk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.lengsukq.opaldesk.adb.AdbMdnsDiscovery
import io.github.lengsukq.opaldesk.adb.AdbPairingNotifier
import io.github.lengsukq.opaldesk.adb.AdbProcessRunner
import io.github.lengsukq.opaldesk.adb.DaemonBootstrapScripts
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Thin orchestrator for on-device Wireless ADB, Shizuku/Sui, and Root daemon activation,
 * delegating mDNS discovery, process execution, shell script construction, Shizuku IPC, and
 * RemoteInput notifications to package `io.github.lengsukq.opaldesk.adb`.
 */
object AdbBootstrap {
    private const val TAG = "OpalDeskAdb"
    const val ACTION_PAIR_REPLY = AdbPairingNotifier.ACTION_PAIR_REPLY
    const val KEY_PAIR_INPUT = AdbPairingNotifier.KEY_PAIR_INPUT

    private const val DIAG_WIRELESS = 0
    private const val DIAG_ROOT = 1
    private const val DIAG_SHIZUKU = 2

    @Volatile
    private var lastActivationMethod: Int = -1

    @Volatile
    private var lastWirelessHost: String = ""

    @Volatile
    private var lastWirelessPort: Int = 0

    fun wasRootActivatedRecently(): Boolean = lastActivationMethod == DIAG_ROOT

    private val PAIR_SPLIT_REGEX = Regex("[\\s:]+")
    private val exec = Executors.newSingleThreadExecutor()
    private val bootstrapActiveCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    class DiscoveryState {
        var pairingHost: String = ""
        var pairingPort: Int = 0
        var connectHost: String = ""
        var connectPort: Int = 0
    }

    fun interface DiscoveryListener {
        fun onDiscoveryUpdated(state: DiscoveryState?)
    }

    interface ProgressListener {
        fun onStep(message: String?)
        fun onResult(success: Boolean, message: String?)
    }

    fun getDiscoveryState(): DiscoveryState = AdbMdnsDiscovery.getState()
    fun startDiscovery(ctx: Context, listener: DiscoveryListener?) = AdbMdnsDiscovery.start(ctx, listener)
    fun ensureDiscoveryStarted(ctx: Context) = AdbMdnsDiscovery.ensureStarted(ctx.applicationContext)
    fun stopDiscovery() = AdbMdnsDiscovery.stop()
    fun stopDiscoveryIfIdle() {
        if (AdbPairingNotifier.isShowing || io.github.lengsukq.opaldesk.web.WebActivationService.isRunning) {
            AdbMdnsDiscovery.clearListener()
        } else {
            AdbMdnsDiscovery.stop()
        }
    }

    fun showPairingNotification(ctx: Context, statusText: String?) {
        AdbMdnsDiscovery.ensureStarted(ctx.applicationContext)
        AdbPairingNotifier.show(ctx, statusText, AdbMdnsDiscovery.getState())
    }

    fun cancelPairingNotification(ctx: Context?) = AdbPairingNotifier.cancel(ctx)

    fun getLocalIpv4(): String = AdbProcessRunner.getLocalIpv4()
    fun buildPcAdbCommand(): String = DaemonBootstrapScripts.buildPcAdbCommand()
    fun isNativeAdbAvailable(ctx: Context): Boolean = AdbProcessRunner.isNativeAdbAvailable(ctx)
    fun hasSuBinaryHint(): Boolean = AdbProcessRunner.hasSuBinaryHint()
    fun isAutoRootEnabled(ctx: Context?): Boolean = AppPrefs.isAutoRootEnabled(ctx)
    fun setAutoRootEnabled(ctx: Context?, enabled: Boolean) = AppPrefs.setAutoRootEnabled(ctx, enabled)
    fun isAutoShizukuEnabled(ctx: Context?): Boolean = AppPrefs.isAutoShizukuEnabled(ctx)
    fun setAutoShizukuEnabled(ctx: Context?, enabled: Boolean) = AppPrefs.setAutoShizukuEnabled(ctx, enabled)

    internal fun tryRunPostmortemOverWirelessSync(ctx: Context, shellCmd: String): String? {
        val appCtx = ctx.applicationContext
        if (!AdbProcessRunner.isNativeAdbAvailable(appCtx) || bootstrapActiveCount.get() > 0) return null
        val ds = getDiscoveryState()
        val host = when {
            lastWirelessHost.isNotBlank() && lastWirelessPort > 0 -> lastWirelessHost
            ds.connectPort > 0 -> ds.connectHost.ifEmpty { getLocalIpv4() }
            else -> ""
        }
        val port = when {
            lastWirelessHost.isNotBlank() && lastWirelessPort > 0 -> lastWirelessPort
            ds.connectPort > 0 -> ds.connectPort
            else -> 0
        }
        if (port !in 1..65535 || !AdbProcessRunner.isAllowedLocalHost(host)) return null
        val fut = exec.submit<String?> {
            if (bootstrapActiveCount.get() > 0) return@submit null
            try {
                val startRes = AdbProcessRunner.runAdb(appCtx, 3000L, "start-server")
                if (startRes.code != 0) return@submit null
                val (target, _) = AdbProcessRunner.connectAdbTarget(appCtx, host, port, timeoutMs = 2500L)
                if (target == null) return@submit null
                AdbProcessRunner.runAdb(appCtx, 3000L, "-s", target, "shell", shellCmd).output.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            } finally {
                AdbProcessRunner.stopLocalAdbServerSync(appCtx)
            }
        }
        return try {
            fut.get(8500L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            fut.cancel(true)
            null
        }
    }

    fun killLocalAdbServerAsync(ctx: Context?) {
        val appCtx = ctx?.applicationContext ?: return
        Thread({ AdbProcessRunner.stopLocalAdbServerSync(appCtx) }, "OpalAdbStop").start()
    }

    fun pairAndBootstrapAsync(
        ctx: Context,
        pairHost: String?,
        pairPort: Int,
        pairCode: String?,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
    ) = submitBootstrap(ctx, listener, "pairAndBootstrap") { appCtx ->
        pairAndBootstrapSync(appCtx, pairHost, pairPort, pairCode, connectHost, connectPort, listener)
    }

    fun connectAndBootstrapAsync(
        ctx: Context,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
    ) = submitBootstrap(ctx, listener, "connectAndBootstrap") { appCtx ->
        if (!AdbProcessRunner.isNativeAdbAvailable(appCtx)) {
            postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用"); return@submitBootstrap
        }
        if (connectPort !in 1..65535) {
            postResult(listener, false, "请填写无线调试主页显示的端口号"); return@submitBootstrap
        }
        try { connectAndBootstrapCore(appCtx, connectHost, connectPort, listener, needStartServer = true) }
        finally { AdbProcessRunner.stopLocalAdbServerSync(appCtx) }
    }

    fun pairFromNotificationAsync(ctx: Context, rawInput: String?, listener: ProgressListener?) =
        submitBootstrap(ctx, listener, "pairFromNotification") { appCtx ->
            val ds = getDiscoveryState()
            val parts = rawInput?.trim()?.replace('：', ':').orEmpty().split(PAIR_SPLIT_REGEX).dropLastWhile { it.isEmpty() }
            var host = ds.pairingHost.ifEmpty { getLocalIpv4() }
            var pairPort = ds.pairingPort
            var code = ""
            when {
                parts.size == 1 -> code = parts[0]
                parts.size == 2 && parts[0].length == 6 && parts[1].length != 6 -> {
                    code = parts[0]; pairPort = parts[1].toIntOrNull() ?: pairPort
                }
                parts.size == 2 -> {
                    pairPort = parts[0].toIntOrNull() ?: pairPort; code = parts[1]
                }
                parts.size >= 3 -> {
                    host = parts[0]; pairPort = parts[1].toIntOrNull() ?: pairPort; code = parts[2]
                }
            }
            when {
                code.isEmpty() -> postResult(listener, false, "请输入 6 位配对码")
                !AdbProcessRunner.isAllowedLocalHost(host) -> postResult(listener, false, "安全拦截：配对地址仅允许本机 IP（拒绝外部地址 $host）")
                pairPort !in 1..65535 -> postResult(listener, false, "未自动发现配对端口，请按「配对码:端口」输入（例如 551857:36565）")
                else -> pairAndBootstrapSync(appCtx, host, pairPort, code, ds.connectHost, ds.connectPort, listener)
            }
        }

    fun rootBootstrapAsync(
        ctx: Context,
        markAutoRoot: Boolean,
        rootEnhance: Boolean = AppPrefs.isRootEnhanceEnabled(ctx),
        listener: ProgressListener?,
    ) = submitBootstrap(ctx, listener, "rootBootstrap") { appCtx ->
        postStep(listener, "正在请求 Root (su) 权限...")
        val staged = DaemonBootstrapScripts.stageBridgeJarWithSha256(appCtx)
        if (staged == null || staged.first.isEmpty() || staged.second.isEmpty()) {
            postResult(listener, false, "无法从 APK 释放并校验 opaldesk.jar"); return@submitBootstrap
        }
        val script = DaemonBootstrapScripts.buildRootBootstrapScript(
            stagedPath = staged.first,
            expectedSha256 = staged.second,
            rootEnhance = rootEnhance,
        )
        val res = AdbProcessRunner.runSu(25000, script)
        val out = res.output.trim()
        if (res.code != 0 || !out.contains("LAUNCH_DONE")) {
            val hint = if (out.contains("NO_ROOT")) {
                "未获取到 Root 权限（请在 Magisk / KernelSU / APatch 弹窗中允许授权，或改用无线调试/Shizuku/ADB 激活）。"
            } else "Root 执行失败（设备可能未 Root 或未对 OpalDesk 授权 Root）：\n$out"
            postResult(listener, false, hint); return@submitBootstrap
        }
        if (markAutoRoot) setAutoRootEnabled(appCtx, true)
        lastActivationMethod = DIAG_ROOT
        awaitDaemonBinderReady(appCtx, DIAG_ROOT, listener, "Root 一键激活成功，OpalDesk 服务已就绪！")
    }

    fun shizukuBootstrapAsync(ctx: Context, markAutoShizuku: Boolean, listener: ProgressListener?) =
        submitBootstrap(ctx, listener, "shizukuBootstrap") { appCtx ->
            if (!ShizukuBootstrap.isBinderAlive()) {
                postResult(listener, false, "未检测到运行中的 Shizuku 服务，请先启动 Shizuku（或使用内置无线调试激活）。")
                return@submitBootstrap
            }
            if (ShizukuBootstrap.isPreV11()) {
                postResult(listener, false, "当前 Shizuku 版本过低（需 v11 及以上）。")
                return@submitBootstrap
            }
            if (!ShizukuBootstrap.isPermissionGranted()) {
                postResult(listener, false, "尚未授予 OpalDesk 使用 Shizuku 的权限，请先点击授权按钮。")
                return@submitBootstrap
            }

            postStep(listener, "正在通过 Shizuku 释放 opaldesk.jar 并拉起守护进程...")
            val jarPair = DaemonBootstrapScripts.readAssetJarBytesWithSha256(appCtx)
            if (jarPair == null || jarPair.first.isEmpty() || jarPair.second.isEmpty()) {
                postResult(listener, false, "无法读取内置 assets/opaldesk.jar，请重新安装完整 APK。")
                return@submitBootstrap
            }
            val (jarBytes, expSha) = jarPair
            val tmpJar = "/data/local/tmp/opd_${DaemonBootstrapScripts.randomHex16()}.jar"
            val stageRes = ShizukuBootstrap.execShell(15000, jarBytes, DaemonBootstrapScripts.buildShizukuStageScript(tmpJar))
            val stdinStaged = stageRes.code == 0 && stageRes.output.contains("STAGE_OK")

            val launchCmd = DaemonBootstrapScripts.buildShizukuLaunchScript(tmpJar, stdinStaged, expSha)
            val res = ShizukuBootstrap.execShell(25000, null, launchCmd)
            if (res.code != 0 || !res.output.contains("LAUNCH_DONE")) {
                ShizukuBootstrap.execShell(2000, null, "rm -f '$tmpJar' 2>/dev/null")
                postResult(listener, false, "Shizuku 启动守护进程失败:\n${res.output}")
                return@submitBootstrap
            }
            if (markAutoShizuku) setAutoShizukuEnabled(appCtx, true)
            lastActivationMethod = DIAG_SHIZUKU
            awaitDaemonBinderReady(appCtx, DIAG_SHIZUKU, listener, "Shizuku 一键激活成功，OpalDesk 服务已就绪！")
        }

    private fun submitBootstrap(ctx: Context, listener: ProgressListener?, label: String, block: (Context) -> Unit) {
        val appCtx = ctx.applicationContext
        // Reset user-stopping flag before any activation attempt so BridgeProvider accepts the
        // newly started daemon's Binder handshake even right after "停止引擎".
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(appCtx)
        OpalLogStore.i("ADB", "Bootstrap started: $label")
        bootstrapActiveCount.incrementAndGet()
        exec.execute {
            try { block(appCtx) }
            catch (t: Throwable) {
                Log.e(TAG, "$label failed", t)
                OpalLogStore.e("ADB", "$label threw exception", t)
                postResult(listener, false, "执行异常: ${t.message}")
            } finally {
                bootstrapActiveCount.decrementAndGet()
            }
        }
    }

    private fun pairAndBootstrapSync(
        appCtx: Context, pairHost: String?, pairPort: Int, pairCode: String?,
        connectHost: String?, connectPort: Int, listener: ProgressListener?,
    ) {
        if (!AdbProcessRunner.isNativeAdbAvailable(appCtx)) { postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用"); return }
        val pHost = pairHost?.trim().orEmpty().ifEmpty { getLocalIpv4() }
        if (!AdbProcessRunner.isAllowedLocalHost(pHost)) { postResult(listener, false, "安全拦截：配对主机必须是本机回环或本机局域网 IP"); return }
        val code = pairCode?.trim().orEmpty()
        if (pairPort !in 1..65535 || code.isEmpty()) { postResult(listener, false, "请填写完整的配对端口和 6 位配对码"); return }

        try {
            postStep(listener, "正在启动内置 ADB 服务...")
            val startRes = AdbProcessRunner.runAdb(appCtx, 10000, "start-server")
            if (startRes.code != 0) { postResult(listener, false, "ADB 服务启动失败:\n${startRes.output}"); return }

            val pairTarget = "$pHost:$pairPort"
            postStep(listener, "正在与 $pairTarget 配对...")
            val pairRes = AdbProcessRunner.runAdb(appCtx, 15000, "pair", pairTarget, code)
            val pOut = pairRes.output.trim()
            val pLow = pOut.lowercase(Locale.ROOT)
            if (pairRes.code != 0 || pLow.contains("failed") || pLow.contains("error")) {
                postResult(listener, false, "配对失败 ($pairTarget):\n$pOut\n提示：请确保没有关闭系统的「与设备配对」弹窗导致配对码刷新。")
                return
            }

            postStep(listener, "配对成功！正在等待无线调试连接端口...")
            val (cHost, cPort) = awaitConnectEndpoint(connectHost?.trim().orEmpty().ifEmpty { pHost }, connectPort)
            if (cPort <= 0) {
                postResult(listener, true, "已成功完成设备配对！请关闭系统的配对弹窗，输入无线调试主界面显示的连接端口后点击「已配对·直连」。")
                return
            }
            connectAndBootstrapCore(appCtx, cHost, cPort, listener, needStartServer = false)
        } finally {
            AdbProcessRunner.stopLocalAdbServerSync(appCtx)
        }
    }

    private fun awaitConnectEndpoint(initialHost: String, initialPort: Int): Pair<String, Int> {
        if (initialPort > 0) return initialHost to initialPort
        val deadline = System.currentTimeMillis() + 4500
        while (System.currentTimeMillis() < deadline) {
            val ds = getDiscoveryState()
            if (ds.connectPort > 0) return ds.connectHost.ifEmpty { initialHost } to ds.connectPort
            try { Thread.sleep(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return initialHost to 0
    }

    private fun connectAndBootstrapCore(
        appCtx: Context, connectHost: String?, connectPort: Int, listener: ProgressListener?, needStartServer: Boolean,
    ) {
        val host = connectHost?.trim().orEmpty().ifEmpty { getLocalIpv4() }
        if (!AdbProcessRunner.isAllowedLocalHost(host)) { postResult(listener, false, "安全拦截：无线调试连接地址仅允许本机 IP"); return }
        postStep(listener, "正在连接无线调试 $host:$connectPort...")
        if (needStartServer) {
            val startRes = AdbProcessRunner.runAdb(appCtx, 8000, "start-server")
            if (startRes.code != 0) { postResult(listener, false, "ADB 服务启动失败:\n${startRes.output}"); return }
        }
        val (target, errOut) = AdbProcessRunner.connectAdbTarget(appCtx, host, connectPort)
        if (target == null) {
            postResult(listener, false, "连接无线调试失败 ($host:$connectPort):\n$errOut\n若为新设备，请先用「配对码」完成配对并确认连接端口已更新。")
            return
        }
        lastActivationMethod = DIAG_WIRELESS
        lastWirelessHost = host
        lastWirelessPort = connectPort

        postStep(listener, "已连接！正在直推 opaldesk.jar 并授权启动守护进程...")
        val staged = DaemonBootstrapScripts.stageBridgeJarWithSha256(appCtx)
        val expectedSha = staged?.second ?: DaemonBootstrapScripts.computeAssetJarSha256(appCtx)
        val remoteTmp = "/data/local/tmp/opd_${DaemonBootstrapScripts.randomHex16()}.jar"
        var pushedOk = false

        if (staged != null && staged.first.isNotEmpty()) {
            val stageFile = File(staged.first)
            if (stageFile.isFile && stageFile.length() > 0L) {
                val pushRes = AdbProcessRunner.runAdb(appCtx, 15000, "-s", target, "push", staged.first, remoteTmp)
                if (pushRes.code == 0) {
                    pushedOk = true
                } else {
                    val pipeRes = AdbProcessRunner.runAdbWithStdinFile(
                        appCtx,
                        15000,
                        stageFile,
                        "-s", target, "shell",
                        "rm -f '$remoteTmp' 2>/dev/null; umask 022; set -C; cat > '$remoteTmp'",
                    )
                    if (pipeRes.code == 0) {
                        pushedOk = true
                    }
                }
            }
        }

        val script = DaemonBootstrapScripts.buildWirelessPostPushScript(remoteTmp, pushedOk, expectedSha256 = expectedSha)
        OpalLogStore.i("ADB", "Running wireless bootstrap script on $target (pushedOk=$pushedOk)")
        val shRes = AdbProcessRunner.runAdb(appCtx, 24000, "-s", target, "shell", script)
        if (shRes.code != 0 || !shRes.output.contains("LAUNCH_DONE")) {
            OpalLogStore.e("ADB", "Wireless shell launch failed rc=${shRes.code}: ${shRes.output.take(400)}")
            try { AdbProcessRunner.runAdb(appCtx, 2000, "-s", target, "shell", "rm -f '$remoteTmp' 2>/dev/null") }
            catch (_: Throwable) {}
            postResult(listener, false, "远程 Shell 启动失败:\n${shRes.output}"); return
        }
        OpalLogStore.i("ADB", "Wireless shell returned LAUNCH_DONE")
        awaitDaemonBinderReady(appCtx, DIAG_WIRELESS, listener, "无线调试激活成功，OpalDesk 服务已就绪！", wirelessTarget = target)
    }

    internal fun formatBinderTimeoutMessage(tailDiag: String): String {
        val cleanTail = tailDiag.trim()
        val offlineHint = if (cleanTail.contains("offline", ignoreCase = true)) {
            "\n提示：检测到无线调试连接已断开；部分机型关闭「无线调试」会连带终止后台守护进程，激活后请保持「无线调试」开启。"
        } else {
            ""
        }
        val tailSection = if (cleanTail.isNotEmpty()) "\n日志摘要:\n$cleanTail" else ""
        return "守护进程命令已下发，但暂未收到 Binder 握手，请稍等几秒或查看 /data/local/tmp/opaldesk.log$offlineHint$tailSection"
    }

    private fun awaitDaemonBinderReady(
        appCtx: Context,
        diagSource: Int,
        listener: ProgressListener?,
        successMsg: String,
        wirelessTarget: String? = null,
    ) {
        postStep(listener, "守护进程已拉起，正在验证 Binder 连接...")
        val deadline = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < deadline) {
            BridgeClient.requestBinder(appCtx)
            if (BridgeClient.getStatus().connected) {
                OpalLogStore.i("ADB", "Daemon Binder verified ready (source=$diagSource)")
                cancelPairingNotification(appCtx); postResult(listener, true, successMsg); return
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        val rawTail = when (diagSource) {
            DIAG_ROOT -> AdbProcessRunner.runSu(3000, "tail -n 12 /data/local/tmp/opaldesk.log 2>/dev/null").output
            DIAG_SHIZUKU -> ShizukuBootstrap.execShell(3000, null, "tail -n 12 /data/local/tmp/opaldesk.log 2>/dev/null").output
            DIAG_WIRELESS -> if (!wirelessTarget.isNullOrBlank()) {
                AdbProcessRunner.runAdb(appCtx, 3000, "-s", wirelessTarget, "shell", "tail -n 12 /data/local/tmp/opaldesk.log 2>/dev/null").output
            } else ""
            else -> ""
        }
        val msg = formatBinderTimeoutMessage(rawTail)
        OpalLogStore.e("ADB", "Daemon Binder handshake timed out (source=$diagSource)${rawTail.trim().takeIf { it.isNotEmpty() }?.let { " | $it" }.orEmpty()}")
        postResult(listener, false, msg)
    }

    private fun postStep(l: ProgressListener?, msg: String?) {
        if (!msg.isNullOrBlank()) OpalLogStore.i("ADB", msg)
        l?.let { mainHandler.post { it.onStep(msg) } }
    }
    private fun postResult(l: ProgressListener?, ok: Boolean, msg: String?) {
        if (!msg.isNullOrBlank()) {
            if (ok) OpalLogStore.i("ADB", "Result OK: $msg") else OpalLogStore.w("ADB", "Result FAIL: $msg")
        }
        l?.let { mainHandler.post { it.onResult(ok, msg) } }
    }
}
