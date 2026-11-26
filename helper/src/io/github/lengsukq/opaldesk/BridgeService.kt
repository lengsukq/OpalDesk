package io.github.lengsukq.opaldesk

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Display
import kotlin.math.roundToInt
import io.github.lengsukq.opaldesk.adb.AdbProcessRunner
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.PendingPairingRequest
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper
import io.github.lengsukq.opaldesk.web.WebActivationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Lightweight foreground session service and automatic USB-C / HDMI external physical monitor watcher.
 *
 * Requires ZERO overlay (`SYSTEM_ALERT_WINDOW`) or Accessibility permissions: when a physical
 * external display is connected, automatically projects [ExternalDesktopActivity] onto that display.
 */
class BridgeService : Service() {
    companion object {
        private const val TAG = "OpalBridgeService"
        private const val CHANNEL_ID = "opaldesk_compositor_session"
        private const val NOTIF_ID = 3910
        private const val PAIRING_CHANNEL_ID = "opaldesk_pairing_requests"
        private const val PAIRING_NOTIF_ID = 3915

        fun updatePairingNotification(ctx: Context?, pending: PendingPairingRequest?) {
            val appCtx = ctx?.applicationContext ?: return
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (pending == null) {
                nm.cancel(PAIRING_NOTIF_ID)
                return
            }
            val channel = NotificationChannel(
                PAIRING_CHANNEL_ID,
                "无线投屏连接确认",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "当局域网内其他设备请求连接投屏时立即弹出确认横幅"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)

            val openIntent = Intent(appCtx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val openPi = PendingIntent.getActivity(
                appCtx,
                3916,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            fun buildAction(code: Int, decision: Int, label: String): Notification.Action {
                val i = Intent(appCtx, PairingActionReceiver::class.java).apply {
                    action = PairingActionReceiver.ACTION_PAIRING_DECISION
                    putExtra(PairingActionReceiver.EXTRA_REQUEST_ID, pending.requestId)
                    putExtra(PairingActionReceiver.EXTRA_DECISION, decision)
                }
                val pi = PendingIntent.getBroadcast(
                    appCtx,
                    code,
                    i,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                return Notification.Action.Builder(null, label, pi).build()
            }

            val remainingMs = (pending.expiresAtMs - System.currentTimeMillis()).coerceIn(5_000L, 65_000L)
            val peerDesc = if (pending.peerIp.isNotBlank()) "${pending.clientName} (${pending.peerIp})" else pending.clientName
            val notif = Notification.Builder(appCtx, PAIRING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_opaldesk)
                .setContentTitle("收到投屏连接请求")
                .setContentText("$peerDesc 请求连接本机，是否允许？")
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(openPi)
                .setAutoCancel(false)
                .setTimeoutAfter(remainingMs)
                .addAction(buildAction(3921, BridgeProtocol.PairingDecision.APPROVE_PERMANENT, "永久允许"))
                .addAction(buildAction(3922, BridgeProtocol.PairingDecision.APPROVE_ONCE, "仅本次允许"))
                .addAction(buildAction(3923, BridgeProtocol.PairingDecision.REJECT, "拒绝"))
                .build()
            try {
                nm.notify(PAIRING_NOTIF_ID, notif)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to post pairing notification: ${t.message}")
            }
        }

        @Volatile
        var sUserStopping: Boolean = false

        @Volatile
        private var activeInstance: BridgeService? = null

        private val _externalDisplayFlow = MutableStateFlow<ExternalDisplayInfo?>(null)
        val externalDisplayFlow: StateFlow<ExternalDisplayInfo?> = _externalDisplayFlow.asStateFlow()

        private val _detectedExternalDisplaysFlow = MutableStateFlow<List<ExternalDisplayInfo>>(emptyList())
        val detectedExternalDisplaysFlow: StateFlow<List<ExternalDisplayInfo>> =
            _detectedExternalDisplaysFlow.asStateFlow()

        fun requestExternalDisplayRescan(delayMs: Long = 0L) {
            val svc = activeInstance ?: return
            if (delayMs > 0L) {
                svc.handler.postDelayed({ svc.scanExternalDisplays(autoLaunchNew = false) }, delayMs)
            } else {
                svc.handler.post { svc.scanExternalDisplays(autoLaunchNew = false) }
            }
        }

        /**
         * Launches [ExternalDesktopActivity] onto [displayId] using the optimal hosting mode:
         * wireless Miracast / Wi-Fi Displays (or user-selected Presentation mode) open on
         * Display 0 and project a native [android.app.Presentation] onto the TV/monitor,
         * while wired USB-C / HDMI monitors default to Direct Extended launch with automatic
         * Presentation fallback.
         */
        fun projectToDisplay(
            ctx: Context,
            displayId: Int,
            transport: ExternalDisplayTransport = ExternalDisplayTransport.WIRED,
            forcePresentation: Boolean = false,
            isPresentationCapable: Boolean = true,
        ) {
            val isWireless = transport == ExternalDisplayTransport.WIRELESS_TV
            val preferPresentation = isPresentationCapable && (
                forcePresentation || ExternalDisplayPolicy.shouldPreferPresentationMode(
                    transport = transport,
                    projectionModePref = AppPrefs.getExternalProjectionMode(ctx),
                    isPresentationCapable = isPresentationCapable,
                )
            )
            val intent = Intent(ctx, ExternalDesktopActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
                putExtra(ExternalDesktopActivity.EXTRA_TARGET_DISPLAY_ID, displayId)
                putExtra(ExternalDesktopActivity.EXTRA_PREFER_PRESENTATION, preferPresentation)
                putExtra(ExternalDesktopActivity.EXTRA_IS_WIRELESS_DISPLAY, isWireless)
            }
            if (preferPresentation) {
                DisplayLaunchHelper.launchOnDisplay0(ctx, intent)
                return
            }
            // Prefer shell-daemon launch (uid=2000) for Direct Extended displays so Android 14–16
            // BAL & unprivileged cross-display restrictions never silently bounce the Activity to Display 0.
            if (BridgeClient.statusFlow.value.connected) {
                val appCtx = ctx.applicationContext
                BridgeClient.launchExternalDesktopOnDisplayAsync(displayId, isWireless) { ok ->
                    if (!ok) {
                        DisplayLaunchHelper.launchOnDisplay(appCtx, intent, displayId)
                    }
                }
            } else {
                DisplayLaunchHelper.launchOnDisplay(ctx, intent, displayId)
            }
        }

        fun ensureStarted(ctx: Context?) {
            val appCtx = ctx?.applicationContext ?: return
            if (sUserStopping) return
            try {
                val intent = Intent(appCtx, BridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "startForegroundService deferred: ${t.message}")
            }
        }

        fun stopAllAndExit(ctx: Context) {
            val appCtx = ctx.applicationContext
            sUserStopping = true
            OpalLogStore.i("Service", "Stopping daemon and helper background services")
            stopAuxiliaryServicesAndDiscovery(appCtx)
            BridgeClient.shutdownDaemonAsync(closeWindows = true, removeTasks = false)
            runCatching { appCtx.stopService(Intent(appCtx, BridgeService::class.java)) }
        }

        /**
         * Performs an ordered full teardown: stops the daemon engine first and waits for it to exit,
         * then stops foreground/auxiliary services, local ADB server, all Activity tasks, and finally
         * terminates the app process itself.
         */
        fun completeExitApp(activity: Activity) {
            val appCtx = activity.applicationContext
            sUserStopping = true
            OpalLogStore.i("Service", "Complete exit requested by user: stopping daemon engine first")
            stopAuxiliaryServicesAndDiscovery(appCtx)
            ExternalTrackpadBridge.requestClosePhoneTrackpad()
            ExternalTrackpadBridge.getDirectSink()?.closeExternalDesktop()

            val finished = AtomicBoolean(false)
            val mainHandler = Handler(Looper.getMainLooper())
            val finishAndTerminate = Runnable {
                if (!finished.compareAndSet(false, true)) return@Runnable
                runCatching { appCtx.stopService(Intent(appCtx, BridgeService::class.java)) }
                runCatching {
                    val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    am?.appTasks?.forEach { task -> runCatching { task.finishAndRemoveTask() } }
                }
                runCatching { activity.finishAffinity() }
                runCatching { activity.finishAndRemoveTask() }
                mainHandler.postDelayed({
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }, 120L)
            }

            // Hard safety watchdog so UI exit never stalls if IPC or local ADB hangs,
            // while giving the daemon enough time to finish closing VirtualDisplays and exit first.
            mainHandler.postDelayed(finishAndTerminate, 3500L)
            Thread({
                runCatching {
                    BridgeClient.shutdownDaemonSync(
                        closeWindows = true,
                        removeTasks = false,
                        waitForExitMs = 2000L,
                    )
                }
                runCatching { appCtx.stopService(Intent(appCtx, BridgeService::class.java)) }
                runCatching {
                    if (AdbProcessRunner.getPrivateAdbSocketFile(appCtx).exists()) {
                        AdbProcessRunner.stopLocalAdbServerSync(appCtx)
                    }
                }
                OpalLogStore.flushBlocking(220L)
                mainHandler.post(finishAndTerminate)
            }, "OpalCompleteExit").start()
        }

        private fun stopAuxiliaryServicesAndDiscovery(appCtx: Context) {
            runCatching { WebActivationService.stop(appCtx) }
            runCatching { PeerFloatingAppService.stop(appCtx) }
            runCatching { OpalPeerDiscovery.stopAdvertise() }
            runCatching { OpalPeerDiscovery.stopBrowsing() }
            runCatching { AdbBootstrap.stopDiscovery() }
            runCatching { AdbBootstrap.cancelPairingNotification(appCtx) }
            runCatching { updatePairingNotification(appCtx, null) }
        }
    }

    data class ExternalDisplayMode(
        val width: Int,
        val height: Int,
        val refreshRateHz: Int,
    )

    data class ExternalDisplayInfo(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val refreshRateHz: Int,
        val supportedModes: List<ExternalDisplayMode> = emptyList(),
        val transport: ExternalDisplayTransport = ExternalDisplayTransport.WIRED,
        val isPresentationCapable: Boolean = true,
        val nativeWidth: Int = width,
        val nativeHeight: Int = height,
    ) {
        val isWirelessDisplay: Boolean
            get() = transport == ExternalDisplayTransport.WIRELESS_TV

        val isCustomSizeOverride: Boolean
            get() = ExternalDisplayPolicy.isCustomForcedOverride(width, height, nativeWidth, nativeHeight)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var mediaRouter: MediaRouter? = null
    private var mediaRouterRegistered = false
    private var lastProjectedDisplayId = -1
    private var screenWakeReceiverRegistered = false

    @Suppress("DEPRECATION")
    private val mediaRouterCallback = object : MediaRouter.SimpleCallback() {
        override fun onRoutePresentationDisplayChanged(router: MediaRouter?, info: MediaRouter.RouteInfo?) {
            handler.post { scanExternalDisplays(autoLaunchNew = true) }
        }

        override fun onRouteSelected(router: MediaRouter?, type: Int, info: MediaRouter.RouteInfo?) {
            if ((type and MediaRouter.ROUTE_TYPE_LIVE_VIDEO) != 0) {
                handler.post { scanExternalDisplays(autoLaunchNew = true) }
            }
        }

        override fun onRouteUnselected(router: MediaRouter?, type: Int, info: MediaRouter.RouteInfo?) {
            if ((type and MediaRouter.ROUTE_TYPE_LIVE_VIDEO) != 0) {
                handler.post { scanExternalDisplays(autoLaunchNew = false) }
            }
        }
    }

    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                if (BridgeClient.statusFlow.value.hostScreenOff) {
                    BridgeClient.setHostScreenPowerAsync(true)
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scanExternalDisplays(autoLaunchNew = true)
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == lastProjectedDisplayId) lastProjectedDisplayId = -1
            scanExternalDisplays(autoLaunchNew = false)
        }
        override fun onDisplayChanged(displayId: Int) = scanExternalDisplays(autoLaunchNew = false)
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        startSessionForeground()
        displayManager = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.also { dm ->
            dm.registerDisplayListener(displayListener, handler)
        }
        @Suppress("DEPRECATION")
        runCatching {
            val mr = getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
            if (mr != null) {
                mr.addCallback(
                    MediaRouter.ROUTE_TYPE_LIVE_VIDEO,
                    mediaRouterCallback,
                )
                mediaRouter = mr
                mediaRouterRegistered = true
            }
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenWakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenWakeReceiver, filter)
            }
            screenWakeReceiverRegistered = true
        }
        scanExternalDisplays(autoLaunchNew = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSessionForeground()
        scanExternalDisplays(autoLaunchNew = false)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        OpalLogStore.i("Service", "App task swiped from Recents -> restoring Display 0 mouse focus")
        BridgeClient.onHostTaskRemoved(
            hasPhysicalExternal = ExternalTrackpadBridge.sessionFlow.value.isPhysicalActive,
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        if (screenWakeReceiverRegistered) {
            runCatching { unregisterReceiver(screenWakeReceiver) }
            screenWakeReceiverRegistered = false
        }
        if (mediaRouterRegistered) {
            @Suppress("DEPRECATION")
            runCatching { mediaRouter?.removeCallback(mediaRouterCallback) }
            mediaRouterRegistered = false
        }
        mediaRouter = null
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        _externalDisplayFlow.value = null
        _detectedExternalDisplaysFlow.value = emptyList()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BridgeClient.onTrimMemory(level)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun scanExternalDisplays(autoLaunchNew: Boolean) {
        val dm = displayManager ?: return
        val remoteDesktopId = BridgeClient.remoteStatusFlow.value.desktopDisplayId
        val inspected = WirelessDisplayHelper.inspectExternalDisplays(this, dm, remoteDesktopId)
        val allInfos = inspected.map { item ->
            val d = item.display
            val mode = d.mode
            val rawModes = d.supportedModes
                .orEmpty()
                .map { Triple(it.physicalWidth, it.physicalHeight, it.refreshRate) }
            val modes = ExternalDisplayPolicy.curateExternalDisplayModes(rawModes, maxCount = 18)
                .map { ExternalDisplayMode(it.width, it.height, it.refreshRateHz) }
            val modeW = mode?.physicalWidth ?: 0
            val modeH = mode?.physicalHeight ?: 0
            val realPt = Point()
            runCatching { d.getRealSize(realPt) }
            val (effW, effH) = ExternalDisplayPolicy.resolveEffectiveDisplaySize(modeW, modeH, realPt.x, realPt.y)
            val nativeW = if (modeW >= 320) modeW else effW
            val nativeH = if (modeH >= 320) modeH else effH
            ExternalDisplayInfo(
                displayId = d.displayId,
                name = item.displayName,
                width = effW,
                height = effH,
                refreshRateHz = (mode?.refreshRate ?: 60f).roundToInt().coerceAtLeast(24),
                supportedModes = modes,
                transport = item.transport,
                isPresentationCapable = item.isPresentationCapable,
                nativeWidth = nativeW,
                nativeHeight = nativeH,
            )
        }
        _detectedExternalDisplaysFlow.value = allInfos

        val primary = allInfos.firstOrNull { ExternalDisplayPolicy.isAutoProjectable(it.transport) }
        if (primary == null) {
            _externalDisplayFlow.value = null
            lastProjectedDisplayId = -1
            return
        }
        _externalDisplayFlow.value = primary

        val isNewPrimary = autoLaunchNew && primary.displayId != lastProjectedDisplayId
        if (isNewPrimary && BridgeClient.statusFlow.value.connected) {
            if (AppPrefs.isAutoCustomResolutionEnabled(this)) {
                val pref = AppPrefs.getDefaultCustomResolution(this)
                if (pref.width != primary.width || pref.height != primary.height) {
                    BridgeClient.setExternalDisplayModeAsync(
                        primary.displayId,
                        pref.width,
                        pref.height,
                        pref.fps.toFloat(),
                    )
                }
            }
            if (AppPrefs.isAutoExternalDisplayEnabled(this)) {
                lastProjectedDisplayId = primary.displayId
                projectToExternalDisplay(this, primary.displayId, primary.transport, primary.isPresentationCapable)
            }
        }
    }

    fun projectToExternalDisplay(
        ctx: Context,
        displayId: Int,
        transport: ExternalDisplayTransport = ExternalDisplayTransport.WIRED,
        isPresentationCapable: Boolean = true,
    ) {
        projectToDisplay(ctx, displayId, transport, isPresentationCapable = isPresentationCapable)
    }

    private fun startSessionForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpalDesk 桌面引擎服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持多窗口虚拟屏池与外接显示器自动投射运行"
        }
        nm.createNotificationChannel(channel)

        val openPi = PendingIntent.getActivity(
            this,
            3911,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opaldesk)
            .setContentTitle("OpalDesk 通用多窗合成引擎")
            .setContentText("点击打开主控台或进入桌面工作站")
            .setContentIntent(openPi)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground warning: ${t.message}")
        }
    }
}
