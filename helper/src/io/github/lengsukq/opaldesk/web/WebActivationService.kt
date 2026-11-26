package io.github.lengsukq.opaldesk.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.lengsukq.opaldesk.AdbBootstrap
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.adb.AdbProcessRunner
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in, short-lived (5 min auto-expire) foreground service that exposes a TLS + WebSocket
 * LAN activation bridge (`:19879`) for the GitHub Pages Web Receiver (`https://lengsukq.github.io/OpalDesk/cast/`).
 *
 * Security invariants:
 * - OFF by default; starts only on explicit user action and auto-stops after timeout or activation handover.
 * - Protected by a 6-digit one-time session code ([WebActivationPolicy.SessionCodeGate]) + strict Origin allowlist.
 * - Never accepts arbitrary remote hosts or shell commands: all ADB operations strictly target this device via
 *   [AdbBootstrap] and [AdbProcessRunner.isAllowedLocalHost].
 */
class WebActivationService : Service(), MiniWebSocketServer.Listener {
    data class State(
        val running: Boolean = false,
        val lanIp: String = "",
        val port: Int = WebActivationPolicy.DEFAULT_ACTIVATION_PORT,
        val sessionCode: String = "",
        val shortCertFp: String = "",
        val connectedPeers: Int = 0,
        val expiresAtMs: Long = 0L,
        val busy: Boolean = false,
        val lastMessage: String = "",
        val lastError: Boolean = false,
        val pairingPort: Int = 0,
        val connectPort: Int = 0,
    ) {
        val activationUrl: String
            get() = if (running && lanIp.isNotEmpty() && port > 0 && sessionCode.isNotEmpty()) {
                WebActivationPolicy.buildWebActivationUrl(lanIp, port, WebActivationPolicy.DEFAULT_CAST_PORT, sessionCode)
            } else {
                ""
            }
    }

    companion object {
        private const val TAG = "OpalWebActSvc"
        private const val CHANNEL_ID = "opaldesk_web_activation"
        private const val NOTIF_ID = 3935
        private const val ACTION_START = "io.github.lengsukq.opaldesk.action.WEB_ACT_START"
        private const val ACTION_STOP = "io.github.lengsukq.opaldesk.action.WEB_ACT_STOP"
        private const val DISCOVERY_POLL_MS = 1000L
        private const val POST_ACTIVATION_GRACE_MS = 5000L

        private val _stateFlow = MutableStateFlow(State())
        val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(ctx: Context) {
            val appCtx = ctx.applicationContext
            if (!OpalPeerDiscovery.hasLocalNetworkPermission(appCtx)) {
                _stateFlow.value = _stateFlow.value.copy(
                    running = false,
                    lastError = true,
                    lastMessage = "Android 17 需要先授予「本地网络」权限，才能接收局域网浏览器连接。",
                )
                return
            }
            val intent = Intent(appCtx, WebActivationService::class.java).apply { action = ACTION_START }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (t: Throwable) {
                OpalLogStore.w(TAG, "Failed to start WebActivationService", t)
            }
        }

        fun stop(ctx: Context) {
            val appCtx = ctx.applicationContext
            try {
                appCtx.stopService(Intent(appCtx, WebActivationService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalWebActIo").apply { isDaemon = true }
    }
    private val codeGate = WebActivationPolicy.SessionCodeGate()
    private val activationBusy = AtomicBoolean(false)

    @Volatile
    private var server: MiniWebSocketServer? = null

    @Volatile
    private var lastPushedDiscoveryKey: String = ""

    @Volatile
    private var autoStopScheduled: Boolean = false

    private val expireRunnable = Runnable {
        OpalLogStore.i(TAG, "WebActivationService auto-expired after 5 minutes")
        stopSelf()
    }

    private val discoveryPollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val ds = AdbBootstrap.getDiscoveryState()
            val curIp = AdbProcessRunner.getLocalIpv4()
            val daemonUp = BridgeClient.statusFlow.value.connected
            val key = "$curIp|${ds.pairingPort}|${ds.connectPort}|$daemonUp|${ShizukuBootstrap.isReadyAndGranted()}"
            if (key != lastPushedDiscoveryKey) {
                lastPushedDiscoveryKey = key
                _stateFlow.value = _stateFlow.value.copy(
                    lanIp = curIp,
                    pairingPort = ds.pairingPort,
                    connectPort = ds.connectPort,
                )
                broadcastState()
            }
            mainHandler.postDelayed(this, DISCOVERY_POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundBanner("正在启动 Web 局域网激活服务…")
        if (server == null) {
            startActivationServerAsync()
        } else {
            resetAutoExpireTimer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(expireRunnable)
        mainHandler.removeCallbacks(discoveryPollRunnable)
        runCatching { server?.stop() }
        server = null
        AdbBootstrap.stopDiscoveryIfIdle()
        _stateFlow.value = State(running = false)
        super.onDestroy()
    }

    private fun startActivationServerAsync() {
        ioExec.execute {
            try {
                val lanIp = AdbProcessRunner.getLocalIpv4()
                val certFile = File(filesDir, "opaldesk_web_act_tls.p12")
                val devName = Build.MODEL?.trim().orEmpty().ifEmpty { "Android" }
                val identity = LocalTlsCert.loadOrCreate(certFile, devName, lanIp)
                val srv = MiniWebSocketServer(
                    identity = identity,
                    deviceName = devName,
                    lanIpProvider = { AdbProcessRunner.getLocalIpv4() },
                    listener = this,
                )
                val port = srv.start()
                server = srv
                isRunning = true
                autoStopScheduled = false
                val code = codeGate.rotate()
                val expiresAt = System.currentTimeMillis() + WebActivationPolicy.AUTO_EXPIRE_MS
                val ds = AdbBootstrap.getDiscoveryState()

                _stateFlow.value = State(
                    running = true,
                    lanIp = lanIp,
                    port = port,
                    sessionCode = code,
                    shortCertFp = identity.shortFingerprint,
                    connectedPeers = 0,
                    expiresAtMs = expiresAt,
                    busy = false,
                    lastMessage = "局域网激活服务已就绪，请用电脑/平板浏览器扫码或输入地址连接",
                    lastError = false,
                    pairingPort = ds.pairingPort,
                    connectPort = ds.connectPort,
                )

                mainHandler.post {
                    AdbBootstrap.ensureDiscoveryStarted(applicationContext)
                    startForegroundBanner("地址 $lanIp:$port · 访问码 $code（5 分钟后自动关闭）")
                    resetAutoExpireTimer()
                    mainHandler.removeCallbacks(discoveryPollRunnable)
                    mainHandler.postDelayed(discoveryPollRunnable, 500L)
                }
                OpalLogStore.i(TAG, "WebActivationService listening on https://$lanIp:$port fp=${identity.shortFingerprint}")
            } catch (t: Throwable) {
                OpalLogStore.e(TAG, "Failed to start WebActivationService", t)
                _stateFlow.value = State(
                    running = false,
                    lastMessage = "启动局域网激活服务失败: ${t.message}",
                    lastError = true,
                )
                mainHandler.post { stopSelf() }
            }
        }
    }

    private fun resetAutoExpireTimer() {
        mainHandler.removeCallbacks(expireRunnable)
        val expiresAt = System.currentTimeMillis() + WebActivationPolicy.AUTO_EXPIRE_MS
        _stateFlow.value = _stateFlow.value.copy(expiresAtMs = expiresAt)
        mainHandler.postDelayed(expireRunnable, WebActivationPolicy.AUTO_EXPIRE_MS)
    }

    // ---- MiniWebSocketServer.Listener ----

    override fun onClientConnected(client: MiniWebSocketServer.WsClient) {
        _stateFlow.value = _stateFlow.value.copy(connectedPeers = _stateFlow.value.connectedPeers + 1)
        val hello = JSONObject().apply {
            put("type", "hello")
            put("deviceName", Build.MODEL?.trim().orEmpty().ifEmpty { "Android" })
            put("requiresAuth", true)
        }
        client.sendText(hello.toString())
    }

    override fun onClientDisconnected(client: MiniWebSocketServer.WsClient) {
        val nextCount = (_stateFlow.value.connectedPeers - 1).coerceAtLeast(0)
        _stateFlow.value = _stateFlow.value.copy(connectedPeers = nextCount)
    }

    override fun onClientTextMessage(client: MiniWebSocketServer.WsClient, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: run {
            sendError(client, "无效的 JSON 消息格式")
            return
        }
        val type = obj.optString("type", "").trim()
        if (type == "auth") {
            handleAuth(client, obj.optString("sessionCode", ""))
            return
        }
        if (!client.authenticated) {
            sendError(client, "尚未通过 6 位会话访问码认证")
            return
        }

        mainHandler.post { resetAutoExpireTimer() }
        when (type) {
            "ping" -> client.sendText(JSONObject().put("type", "pong").toString())
            "refresh_state" -> client.sendText(buildStateJson())
            "pair_and_start" -> handlePairAndStart(client, obj)
            "direct_connect" -> handleDirectConnect(client, obj)
            "shizuku_activate" -> handleShizukuActivate(client, obj)
            else -> sendError(client, "不支持的操作类型: $type")
        }
    }

    private fun handleAuth(client: MiniWebSocketServer.WsClient, candidateCode: String) {
        val res = codeGate.verify(candidateCode)
        if (res.rotated) {
            _stateFlow.value = _stateFlow.value.copy(
                sessionCode = res.newCode,
                lastMessage = "检测到多次错误访问码，已自动刷新新访问码",
                lastError = true,
            )
            val st = _stateFlow.value
            mainHandler.post {
                startForegroundBanner("地址 ${st.lanIp}:${st.port} · 新访问码 ${res.newCode}")
            }
        }
        val reply = JSONObject().apply {
            put("type", "auth_result")
            put("ok", res.ok)
            put("rotated", res.rotated)
            put("message", res.message)
        }
        client.sendText(reply.toString())
        if (res.ok) {
            client.authenticated = true
            client.sendText(buildStateJson())
            updateUiStatus("浏览器 (${client.peerIp}) 已连接，等待激活操作", isError = false)
        }
    }

    private fun handlePairAndStart(client: MiniWebSocketServer.WsClient, obj: JSONObject) {
        val ds = AdbBootstrap.getDiscoveryState()
        val pairPort = obj.optInt("pairPort", ds.pairingPort)
        val connectPort = obj.optInt("connectPort", ds.connectPort)
        val pairCode = obj.optString("pairCode", "").trim()
        val autoEnableCast = obj.optBoolean("autoEnableCast", true)

        if (!WebActivationPolicy.isValidPort(pairPort)) {
            sendError(client, "配对端口无效（需为 1–65535），请确认手机已打开「无线调试 → 使用配对码配对设备」")
            return
        }
        if (!WebActivationPolicy.isValidSixDigitCode(pairCode)) {
            sendError(client, "请输入手机屏幕上显示的 6 位无线调试配对码")
            return
        }
        if (!activationBusy.compareAndSet(false, true)) {
            sendError(client, "正在执行激活任务，请稍候")
            return
        }
        _stateFlow.value = _stateFlow.value.copy(busy = true)
        val localPairHost = ds.pairingHost.ifEmpty { AdbProcessRunner.getLocalIpv4() }
        val localConnectHost = ds.connectHost.ifEmpty { localPairHost }
        AdbBootstrap.pairAndBootstrapAsync(
            ctx = applicationContext,
            pairHost = localPairHost,
            pairPort = pairPort,
            pairCode = pairCode,
            connectHost = localConnectHost,
            connectPort = if (WebActivationPolicy.isValidPort(connectPort)) connectPort else 0,
            listener = createProgressBridge(autoEnableCast),
        )
    }

    private fun handleDirectConnect(client: MiniWebSocketServer.WsClient, obj: JSONObject) {
        val ds = AdbBootstrap.getDiscoveryState()
        val connectPort = obj.optInt("connectPort", ds.connectPort)
        val autoEnableCast = obj.optBoolean("autoEnableCast", true)

        if (!WebActivationPolicy.isValidPort(connectPort)) {
            sendError(client, "连接端口无效，请确认手机无线调试已开启并填写主页显示的端口")
            return
        }
        if (!activationBusy.compareAndSet(false, true)) {
            sendError(client, "正在执行激活任务，请稍候")
            return
        }
        _stateFlow.value = _stateFlow.value.copy(busy = true)
        val localHost = ds.connectHost.ifEmpty { AdbProcessRunner.getLocalIpv4() }
        AdbBootstrap.connectAndBootstrapAsync(
            ctx = applicationContext,
            connectHost = localHost,
            connectPort = connectPort,
            listener = createProgressBridge(autoEnableCast),
        )
    }

    private fun handleShizukuActivate(client: MiniWebSocketServer.WsClient, obj: JSONObject) {
        val autoEnableCast = obj.optBoolean("autoEnableCast", true)
        if (!ShizukuBootstrap.isReadyAndGranted()) {
            sendError(client, "Shizuku 未运行或尚未在手机端授权 OpalDesk")
            return
        }
        if (!activationBusy.compareAndSet(false, true)) {
            sendError(client, "正在执行激活任务，请稍候")
            return
        }
        _stateFlow.value = _stateFlow.value.copy(busy = true)
        AdbBootstrap.shizukuBootstrapAsync(
            ctx = applicationContext,
            markAutoShizuku = false,
            listener = createProgressBridge(autoEnableCast),
        )
    }

    private fun createProgressBridge(autoEnableCast: Boolean): AdbBootstrap.ProgressListener =
        object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) {
                val msg = message.orEmpty()
                updateUiStatus(msg, isError = false)
                broadcastJson(
                    JSONObject().apply {
                        put("type", "progress")
                        put("message", msg)
                    },
                )
            }

            override fun onResult(success: Boolean, message: String?) {
                activationBusy.set(false)
                val msg = message.orEmpty()
                _stateFlow.value = _stateFlow.value.copy(busy = false, lastMessage = msg, lastError = !success)
                val daemonUp = BridgeClient.statusFlow.value.connected
                broadcastJson(
                    JSONObject().apply {
                        put("type", "result")
                        put("ok", success)
                        put("daemonConnected", daemonUp)
                        put("message", msg)
                    },
                )
                broadcastState()
                if (success && daemonUp && autoEnableCast) {
                    enableWifiCastAndHandover()
                }
            }
        }

    private fun enableWifiCastAndHandover() {
        broadcastJson(
            JSONObject().apply {
                put("type", "progress")
                put("message", "引擎已激活！正在自动开启无线投屏服务 (:19878)…")
            },
        )
        BridgeClient.setWifiStreamEnabledAsync(true, WebActivationPolicy.DEFAULT_CAST_PORT) {
            val remote = BridgeClient.remoteStatusFlow.value
            val lanIp = remote.lanIp.ifEmpty { AdbProcessRunner.getLocalIpv4() }
            val castPort = if (remote.wifiPort > 0) remote.wifiPort else WebActivationPolicy.DEFAULT_CAST_PORT
            OpalPeerDiscovery.syncAdvertise(applicationContext, remote.wifiEnabled, castPort, remote.shortCertFingerprint)
            broadcastJson(
                JSONObject().apply {
                    put("type", "ready_for_cast")
                    put("ok", remote.wifiEnabled)
                    put("host", lanIp)
                    put("castPort", castPort)
                    put("shortCertFp", remote.shortCertFingerprint)
                    put("message", if (remote.wifiEnabled) "无线投屏服务已就绪，即将切入 Web 投屏端" else "引擎已激活，请在手机端手动打开无线共享")
                },
            )
            if (remote.wifiEnabled && !autoStopScheduled) {
                autoStopScheduled = true
                mainHandler.postDelayed({ stopSelf() }, POST_ACTIVATION_GRACE_MS)
            }
        }
    }

    private fun buildStateJson(): String {
        val ds = AdbBootstrap.getDiscoveryState()
        val lanIp = AdbProcessRunner.getLocalIpv4()
        val remote = BridgeClient.remoteStatusFlow.value
        val daemonUp = BridgeClient.statusFlow.value.connected
        return JSONObject().apply {
            put("type", "state")
            put("deviceName", Build.MODEL?.trim().orEmpty().ifEmpty { "Android" })
            put("lanIp", lanIp)
            put("daemonConnected", daemonUp)
            put("wifiCastEnabled", remote.wifiEnabled)
            put("castPort", if (remote.wifiPort > 0) remote.wifiPort else WebActivationPolicy.DEFAULT_CAST_PORT)
            put("nativeAdbAvailable", AdbBootstrap.isNativeAdbAvailable(applicationContext))
            put("shizukuAlive", ShizukuBootstrap.isBinderAlive())
            put("shizukuGranted", ShizukuBootstrap.isReadyAndGranted())
            put("busy", activationBusy.get())
            put(
                "discovery",
                JSONObject().apply {
                    put("pairingPort", ds.pairingPort)
                    put("connectPort", ds.connectPort)
                },
            )
            put("pcAdbCmd", AdbBootstrap.buildPcAdbCommand())
        }.toString()
    }

    private fun broadcastState() {
        ioExec.execute {
            server?.broadcastAuthenticated(buildStateJson())
        }
    }

    private fun broadcastJson(obj: JSONObject) {
        val payload = obj.toString()
        ioExec.execute {
            server?.broadcastAuthenticated(payload)
        }
    }

    private fun sendError(client: MiniWebSocketServer.WsClient, message: String) {
        val payload = JSONObject().apply {
            put("type", "error")
            put("message", message)
        }.toString()
        ioExec.execute {
            client.sendText(payload)
        }
    }

    private fun updateUiStatus(msg: String, isError: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(lastMessage = msg, lastError = isError)
    }

    private fun startForegroundBanner(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Web 局域网激活服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "临时运行局域网激活桥接服务（5 分钟自动关闭）"
            },
        )
        val openPi = PendingIntent.getActivity(
            this,
            3931,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            3932,
            Intent(this, WebActivationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opaldesk)
            .setContentTitle("OpalDesk Web 局域网激活运行中")
            .setContentText(contentText)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止服务", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
