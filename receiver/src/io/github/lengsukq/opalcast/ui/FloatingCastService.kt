package io.github.lengsukq.opalcast.ui

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.net.UsbTetherDiscovery
import io.github.lengsukq.opalcast.net.WifiLowLatencyLock
import io.github.lengsukq.opalcast.policy.FloatingGeometry
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.prefs.ReceiverLocalePolicy
import io.github.lengsukq.opalcast.prefs.ReceiverPrefs
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android 5.0–17 (API 21–37) floating overlay window for standalone remote apps and phone screen mirror.
 * Zero AndroidX / Compose dependencies; pure standard AOSP overlay APIs.
 */
class FloatingCastService : Service() {

    data class ActiveFloatingInfo(
        val host: String,
        val port: Int,
        val peerName: String,
        val appPackage: String,
        val appLabel: String,
        val appUserId: Int = 0,
        val isMirror: Boolean = false,
    )

    companion object {
        private const val TAG = "OpalCastFloat"
        private const val CHANNEL_ID = "opalcast_floating_cast"
        private const val NOTIF_ID = 4930
        const val MIRROR_SENTINEL_PKG = "__opalcast_mirror__"

        const val ACTION_START = "io.github.lengsukq.opalcast.FLOATING_START"
        const val ACTION_STOP = "io.github.lengsukq.opalcast.FLOATING_STOP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PEER_LABEL = "peer_label"
        const val EXTRA_APP_PACKAGE = "app_pkg"
        const val EXTRA_APP_ACTIVITY = "app_cls"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_APP_USER_ID = "app_user_id"
        const val EXTRA_HANDOVER_TOKEN = "handover_token"
        const val EXTRA_IS_MIRROR = "is_mirror"

        private val stateListeners = CopyOnWriteArrayList<(ActiveFloatingInfo?) -> Unit>()
        private val staticHandler = Handler(Looper.getMainLooper())

        @Volatile
        var activeInfo: ActiveFloatingInfo? = null
            private set

        fun addStateListener(l: (ActiveFloatingInfo?) -> Unit) {
            stateListeners.addIfAbsent(l)
            val snap = activeInfo
            staticHandler.post { l(snap) }
        }

        fun removeStateListener(l: (ActiveFloatingInfo?) -> Unit) {
            stateListeners.remove(l)
        }

        private fun updateActiveInfo(info: ActiveFloatingInfo?) {
            activeInfo = info
            staticHandler.post {
                for (l in stateListeners) l(info)
            }
        }

        fun startApp(
            ctx: Context,
            host: String,
            port: Int,
            peerLabel: String,
            appPackage: String,
            appActivity: String,
            appLabel: String,
            appUserId: Int = 0,
            handoverToken: String = "",
        ): Boolean {
            if (!AndroidApiCompat.canDrawOverlays(ctx)) return false
            val intent = Intent(ctx.applicationContext, FloatingCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PEER_LABEL, peerLabel)
                putExtra(EXTRA_APP_PACKAGE, appPackage)
                putExtra(EXTRA_APP_ACTIVITY, appActivity)
                putExtra(EXTRA_APP_LABEL, appLabel)
                putExtra(EXTRA_APP_USER_ID, appUserId.coerceAtLeast(0))
                putExtra(EXTRA_HANDOVER_TOKEN, handoverToken)
                putExtra(EXTRA_IS_MIRROR, false)
            }
            AndroidApiCompat.startForegroundServiceCompat(ctx, intent)
            return true
        }

        fun startMirror(
            ctx: Context,
            host: String,
            port: Int,
            peerLabel: String,
            handoverToken: String = "",
        ): Boolean {
            if (!AndroidApiCompat.canDrawOverlays(ctx)) return false
            val displayPeer = peerLabel.ifBlank { host }
            val intent = Intent(ctx.applicationContext, FloatingCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PEER_LABEL, displayPeer)
                putExtra(EXTRA_APP_PACKAGE, MIRROR_SENTINEL_PKG)
                putExtra(EXTRA_APP_ACTIVITY, "")
                putExtra(EXTRA_APP_LABEL, ctx.getString(R.string.float_mirror_label_fmt, displayPeer))
                putExtra(EXTRA_HANDOVER_TOKEN, handoverToken)
                putExtra(EXTRA_IS_MIRROR, true)
            }
            AndroidApiCompat.startForegroundServiceCompat(ctx, intent)
            return true
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(Intent(ctx.applicationContext, FloatingCastService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var textureView: TextureView? = null
    private var activeSurface: Surface? = null
    private var statusDotView: View? = null
    private var titleTextView: TextView? = null
    private var blackoutBtnContainer: FrameLayout? = null
    private var blackoutIconView: FloatingGlyphIconView? = null
    private var imeBtnContainer: FrameLayout? = null
    private var imeIconView: FloatingGlyphIconView? = null
    private var overlayBanner: LinearLayout? = null
    private var overlayBannerText: TextView? = null
    private var overlayRetryBtn: TextView? = null
    private var imeInputBar: LinearLayout? = null
    private var imeEditText: EditText? = null

    private var client: OpalCastClient? = null
    private var wifiLowLatencyLock: WifiLowLatencyLock? = null
    private var host: String = ""
    private var port: Int = ReceiverPolicy.DEFAULT_CAST_PORT
    private var peerLabel: String = ""
    private var appPackage: String = ""
    private var appActivity: String = ""
    private var appLabel: String = ""
    private var appUserId: Int = 0
    private var handoverToken: String = ""
    private var isMirrorMode = false
    private var attachedMirrorMode: Boolean? = null
    private var remoteBlackout = false
    private var mirrorVideoW = 1080
    private var mirrorVideoH = 2400
    private var lastSnappedMirrorW = 0
    private var lastSnappedMirrorH = 0
    private var isLandscape = false
    private var isCollapsed = false
    private var isImeBarVisible = false

    private var expandedW = 0
    private var expandedH = 0
    private var savedX = 48
    private var savedY = 140

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ReceiverLocalePolicy.wrapContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val nextHost = intent?.getStringExtra(EXTRA_HOST).orEmpty().trim()
        val nextPort = (intent?.getIntExtra(EXTRA_PORT, ReceiverPolicy.DEFAULT_CAST_PORT)
            ?: ReceiverPolicy.DEFAULT_CAST_PORT).coerceIn(1, 65535)
        val nextIsMirror = intent?.getBooleanExtra(EXTRA_IS_MIRROR, false) == true
        val nextPkg = intent?.getStringExtra(EXTRA_APP_PACKAGE).orEmpty().trim()
            .ifBlank { if (nextIsMirror) MIRROR_SENTINEL_PKG else "" }
        val nextCls = if (nextIsMirror) "" else intent?.getStringExtra(EXTRA_APP_ACTIVITY).orEmpty().trim()
        val nextPeerLabel = intent?.getStringExtra(EXTRA_PEER_LABEL).orEmpty().ifBlank { nextHost }
        val nextAppLabel = intent?.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank {
            if (nextIsMirror) getString(R.string.float_mirror_label_fmt, nextPeerLabel) else nextPkg
        }
        val nextUserId = if (nextIsMirror) 0 else (intent?.getIntExtra(EXTRA_APP_USER_ID, 0) ?: 0).coerceAtLeast(0)
        val nextHandover = intent?.getStringExtra(EXTRA_HANDOVER_TOKEN).orEmpty().trim()

        FloatingCastChromeHelper.startForegroundNotification(
            service = this,
            channelId = CHANNEL_ID,
            notifId = NOTIF_ID,
            appName = nextAppLabel.ifBlank {
                getString(if (nextIsMirror) R.string.float_default_mirror_name else R.string.float_default_app_name)
            },
            peerName = nextPeerLabel.ifBlank { getString(R.string.float_default_peer_name) },
            isMirror = nextIsMirror,
        )

        if (!AndroidApiCompat.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.perm_overlay_required), Toast.LENGTH_SHORT).show()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (nextHost.isEmpty() || (!nextIsMirror && nextPkg.isEmpty())) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        val modeChanged = (attachedMirrorMode != null && attachedMirrorMode != nextIsMirror)
        host = nextHost
        port = nextPort
        peerLabel = nextPeerLabel
        appPackage = nextPkg
        appActivity = nextCls
        appLabel = nextAppLabel
        appUserId = nextUserId
        handoverToken = nextHandover
        isMirrorMode = nextIsMirror
        remoteBlackout = false
        lastSnappedMirrorW = 0
        lastSnappedMirrorH = 0

        updateActiveInfo(ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel, appUserId = appUserId, isMirror = isMirrorMode))
        if (!isMirrorMode) ReceiverPrefs.recordAppLaunch(this, ReceiverPolicy.formatAppKey(nextPkg, nextUserId))
        ReceiverPrefs.recordRecentPeer(this, host, port, peerLabel)

        tearDownSessionOnly()
        if (modeChanged) {
            removeAllOverlayViews()
            attachedMirrorMode = null
        }
        ensureFloatingWindowAttached()
        connectRemoteSession()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tearDownSessionOnly()
        removeAllOverlayViews()
        attachedMirrorMode = null
        updateActiveInfo(null)
        super.onDestroy()
    }

    private fun tearDownSessionOnly() {
        client?.stop()
        client = null
        wifiLowLatencyLock?.release()
    }

    private fun connectRemoteSession() {
        updateOverlayStatus(getString(R.string.float_connecting_peer_fmt, peerLabel), dotColor = 0xFFF59E0B.toInt(), showRetry = false)
        val dm = resources.displayMetrics
        val (streamW, streamH, reqDpi) = if (isMirrorMode) {
            val maxEdge = max(dm.widthPixels, dm.heightPixels).coerceIn(1280, 2400)
            Triple(maxEdge, (maxEdge * 10 / 16).coerceAtLeast(720), dm.densityDpi.coerceIn(160, 480))
        } else {
            FloatingCastGestureHandler.computeStreamSpecForView(expandedW, expandedH, dm.density)
        }
        val tokenToUse = ReceiverPrefs.getPeerAuthToken(this, host).ifBlank { handoverToken }
        handoverToken = ""

        val boundNet = if (ReceiverPrefs.isUsbTetherEnabled(this)) {
            UsbTetherDiscovery.resolveBoundNetworkForHost(host)
        } else {
            null
        }
        val wifiLock = wifiLowLatencyLock ?: WifiLowLatencyLock(this, "OpalCast:FloatRx").also {
            wifiLowLatencyLock = it
        }
        if (boundNet == null) wifiLock.acquire() else wifiLock.release()

        val peerClient = OpalCastClient(
            host = host,
            port = port,
            savedAuthToken = tokenToUse,
            clientId = ReceiverPrefs.getOrCreateClientId(this),
            reqWidth = streamW,
            reqHeight = streamH,
            reqDpi = reqDpi,
            reqFps = if (isMirrorMode) AndroidApiCompat.resolveDefaultRefreshRateInt(this) else 60,
            sessionRole = if (isMirrorMode) OpalCastClient.SESSION_ROLE_DESKTOP else OpalCastClient.SESSION_ROLE_STANDALONE_APP,
            appPackage = if (isMirrorMode) "" else appPackage,
            appActivity = if (isMirrorMode) "" else appActivity,
            appUserId = if (isMirrorMode) 0 else appUserId,
            targetNetwork = boundNet,
            audioEnabled = isMirrorMode,
            initialDisplayMode = if (isMirrorMode) OpalCastClient.DISPLAY_MODE_MIRROR else OpalCastClient.DISPLAY_MODE_DEX,
            preferredCodec = AndroidApiCompat.resolvePreferredCastCodec(ReceiverPrefs.getVideoCodecPref(this)),
            preferredBitrateKbps = ReceiverPrefs.getPreferredBitrateKbps(this),
            defaultDisconnectReason = getString(R.string.client_disconnected),
            defaultRejectedMessage = getString(R.string.client_rejected),
            listener = object : OpalCastClient.Listener {
                override fun onWaitingForApproval() {
                    mainHandler.post {
                        updateOverlayStatus(getString(R.string.float_waiting_approval), dotColor = 0xFFF59E0B.toInt(), showRetry = false)
                    }
                }

                override fun onAuthenticated(
                    peerName: String,
                    authToken: String,
                    permanent: Boolean,
                    certFingerprint: String,
                ) {
                    if (permanent && authToken.isNotBlank()) {
                        ReceiverPrefs.savePeerAuthToken(this@FloatingCastService, host, authToken)
                    }
                    mainHandler.post {
                        peerLabel = peerName.ifBlank { peerLabel }
                        if (isMirrorMode) {
                            appLabel = getString(R.string.float_mirror_label_fmt, peerLabel)
                            titleTextView?.text = peerLabel
                        }
                        updateActiveInfo(ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel, appUserId = appUserId, isMirror = isMirrorMode))
                        val hint = if (isMirrorMode) {
                            getString(R.string.float_mirroring_peer_fmt, peerLabel)
                        } else {
                            getString(R.string.float_launching_app_fmt, appLabel)
                        }
                        updateOverlayStatus(hint, dotColor = 0xFF14B8A6.toInt(), showRetry = false)
                    }
                }

                override fun onRemoteBlackoutChanged(blackout: Boolean) {
                    if (!isMirrorMode) return
                    mainHandler.post {
                        remoteBlackout = blackout
                        refreshBlackoutAppearance()
                    }
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) {
                    mainHandler.post {
                        val safeW = width.coerceAtLeast(2)
                        val safeH = height.coerceAtLeast(2)
                        try { textureView?.surfaceTexture?.setDefaultBufferSize(safeW, safeH) } catch (_: Throwable) {}
                        if (isMirrorMode) {
                            mirrorVideoW = safeW
                            mirrorVideoH = safeH
                            if (safeW != lastSnappedMirrorW || safeH != lastSnappedMirrorH) {
                                lastSnappedMirrorW = safeW
                                lastSnappedMirrorH = safeH
                                snapWindowToMirrorAspect(safeW, safeH)
                            }
                        }
                        statusDotView?.background = FloatingCastChromeHelper.circleDrawable(0xFF14B8A6.toInt())
                        overlayBanner?.visibility = View.GONE
                    }
                }

                override fun onRemoteImeRequest(show: Boolean, inputType: Int) {
                    mainHandler.post {
                        if (show && !isCollapsed) showImeBar() else if (!show && isImeBarVisible) hideImeBar()
                    }
                }

                override fun onError(message: String) {
                    Log.w(TAG, "Floating error: $message")
                    mainHandler.post { updateOverlayStatus(message, dotColor = 0xFFEF4444.toInt(), showRetry = true) }
                }

                override fun onDisconnected(reason: String) {
                    mainHandler.post {
                        if (reason == "app_exited" || reason == "Session closed" || reason == "Replaced by new session") {
                            stopSelf()
                            return@post
                        }
                        if (overlayBanner?.visibility != View.VISIBLE || overlayRetryBtn?.visibility != View.VISIBLE) {
                            updateOverlayStatus(
                                reason.ifBlank { getString(R.string.float_disconnected) },
                                dotColor = 0xFFEF4444.toInt(),
                                showRetry = true,
                            )
                        }
                    }
                }
            },
        )
        client = peerClient
        activeSurface?.let { s -> if (s.isValid) peerClient.attachSurface(s) }
        peerClient.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureFloatingWindowAttached() {
        if (rootView != null) {
            titleTextView?.text = if (isMirrorMode) peerLabel else appLabel
            if (isCollapsed) expandFromBubble()
            return
        }
        val windowManager = wm ?: return
        val dm = resources.displayMetrics
        val initialSize = if (isMirrorMode) {
            FloatingGeometry.computeAspectMatchedWindowSize(
                videoW = mirrorVideoW,
                videoH = mirrorVideoH,
                chromeHeightPx = floatingChromeHeightPx(),
                screenW = dm.widthPixels,
                screenH = dm.heightPixels,
                density = dm.density,
            )
        } else {
            FloatingGeometry.computeWindowSize(dm.widthPixels, dm.heightPixels, dm.density, isLandscape)
        }
        expandedW = initialSize.width
        expandedH = initialSize.height
        val initialPos = FloatingGeometry.clampVisiblePosition(
            dp(18), dp(90), expandedW, expandedH, dm.widthPixels, dm.heightPixels, dm.density,
        )
        savedX = initialPos.x
        savedY = initialPos.y

        val surfaceListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val rw = client?.remoteWidth ?: width
                val rh = client?.remoteHeight ?: height
                if (rw >= 2 && rh >= 2) try { st.setDefaultBufferSize(rw, rh) } catch (_: Throwable) {}
                activeSurface?.release()
                val surf = Surface(st)
                activeSurface = surf
                client?.attachSurface(surf)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                val rw = client?.remoteWidth ?: return
                val rh = client?.remoteHeight ?: return
                if (rw >= 2 && rh >= 2) try { st.setDefaultBufferSize(rw, rh) } catch (_: Throwable) {}
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                client?.attachSurface(null)
                activeSurface?.release()
                activeSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                if (overlayBanner?.visibility == View.VISIBLE) overlayBanner?.visibility = View.GONE
            }
        }

        val views = FloatingCastChromeHelper.buildFloatingWindowHierarchy(
            ctx = this,
            isMirrorMode = isMirrorMode,
            titleText = if (isMirrorMode) peerLabel else appLabel,
            appLabel = appLabel,
            callbacks = FloatingWindowCallbacks(
                onClose = { stopSelf() },
                onCollapse = ::collapseToBubble,
                onExpandOrRotate = { if (isMirrorMode) expandMirrorToFullscreen() else toggleOrientation() },
                onToggleBlackout = {
                    remoteBlackout = !remoteBlackout
                    refreshBlackoutAppearance()
                    client?.sendSystemAction(OpalCastClient.SYS_TOGGLE_BLACKOUT, if (remoteBlackout) 1 else 0)
                },
                onToggleImeBar = ::toggleImeBar,
                onBack = { client?.sendSystemAction(OpalCastClient.SYS_BACK) },
                onHome = { client?.sendSystemAction(OpalCastClient.SYS_HOME) },
                onRecents = { client?.sendSystemAction(OpalCastClient.SYS_RECENTS) },
                onSendImeText = ::sendImeBarText,
                onReconnect = {
                    tearDownSessionOnly()
                    connectRemoteSession()
                },
                getClient = { client },
                surfaceListener = surfaceListener,
            ),
        )
        statusDotView = views.statusDotView
        titleTextView = views.titleTextView
        blackoutBtnContainer = views.blackoutBtnContainer
        blackoutIconView = views.blackoutIconView
        imeBtnContainer = views.imeBtnContainer
        imeIconView = views.imeIconView
        textureView = views.textureView
        overlayBanner = views.overlayBanner
        overlayBannerText = views.overlayBannerText
        overlayRetryBtn = views.overlayRetryBtn
        imeInputBar = views.imeInputBar
        imeEditText = views.imeEditText
        refreshBlackoutAppearance()
        refreshImeButtonAppearance()

        FloatingCastGestureHandler.installDragMoveListener(
            handle = views.header,
            getLayoutParams = { layoutParams },
            getRootView = { rootView },
            getDisplayMetrics = { resources.displayMetrics },
            onPositionChanged = { x, y -> savedX = x; savedY = y },
            onUpdateLayout = ::safeUpdateViewLayout,
        )
        FloatingCastGestureHandler.installResizeListener(
            grip = views.resizeGrip,
            getLayoutParams = { layoutParams },
            getRootView = { rootView },
            getDisplayMetrics = { resources.displayMetrics },
            isMirrorMode = { isMirrorMode },
            getMirrorVideoSize = { mirrorVideoW to mirrorVideoH },
            getChromeHeightPx = ::floatingChromeHeightPx,
            onSizeChanged = { w, h -> expandedW = w; expandedH = h },
            onUpdateLayout = ::safeUpdateViewLayout,
            onCommitRemoteResize = ::commitRemoteResize,
        )
        val container = views.container

        val lp = WindowManager.LayoutParams(
            expandedW,
            expandedH,
            AndroidApiCompat.overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        try {
            windowManager.addView(container, lp)
            rootView = container
            layoutParams = lp
            attachedMirrorMode = isMirrorMode
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach overlay", t)
            Toast.makeText(this, getString(R.string.float_create_failed_fmt, t.message ?: t.javaClass.simpleName), Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun floatingChromeHeightPx(): Int {
        val strokePx = max(2, (1.25f * resources.displayMetrics.density).roundToInt())
        val topChrome = dp(40) + max(1, dp(1))
        val bottomChrome = if (isMirrorMode) dp(40) + max(1, dp(1)) else 0
        return topChrome + bottomChrome + strokePx * 2
    }

    private fun snapWindowToMirrorAspect(videoW: Int, videoH: Int) {
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        val dm = resources.displayMetrics
        val matched = FloatingGeometry.computeAspectMatchedWindowSize(
            videoW, videoH, floatingChromeHeightPx(), dm.widthPixels, dm.heightPixels, dm.density,
        )
        expandedW = matched.width
        expandedH = matched.height
        lp.width = expandedW
        lp.height = expandedH
        val clampedPos = FloatingGeometry.clampVisiblePosition(
            lp.x, lp.y, lp.width, lp.height, dm.widthPixels, dm.heightPixels, dm.density,
        )
        lp.x = clampedPos.x
        lp.y = clampedPos.y
        savedX = lp.x
        savedY = lp.y
        safeUpdateViewLayout(rv, lp)
    }

    private fun expandMirrorToFullscreen() {
        val targetHost = host
        val targetPort = port
        val label = peerLabel.ifBlank { host }
        tearDownSessionOnly()
        val intent = Intent(this, CastViewerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CastViewerActivity.EXTRA_HOST, targetHost)
            putExtra(CastViewerActivity.EXTRA_PORT, targetPort)
            putExtra(CastViewerActivity.EXTRA_LABEL, label)
            putExtra(CastViewerActivity.EXTRA_INITIAL_DISPLAY_MODE, OpalCastClient.DISPLAY_MODE_MIRROR)
        }
        try { startActivity(intent) } catch (_: Throwable) {}
        stopSelf()
    }

    private fun refreshBlackoutAppearance() {
        FloatingCastChromeHelper.refreshBlackoutAppearance(this, blackoutBtnContainer, blackoutIconView, remoteBlackout)
    }

    private fun refreshImeButtonAppearance() {
        FloatingCastChromeHelper.refreshImeButtonAppearance(this, imeBtnContainer, imeIconView, isImeBarVisible)
    }

    private fun toggleOrientation() {
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        val dm = resources.displayMetrics
        isLandscape = !isLandscape
        val nextSize = FloatingGeometry.computeWindowSize(dm.widthPixels, dm.heightPixels, dm.density, isLandscape)
        expandedW = nextSize.width
        expandedH = nextSize.height
        lp.width = expandedW
        lp.height = expandedH
        val clampedPos = FloatingGeometry.clampVisiblePosition(
            lp.x, lp.y, lp.width, lp.height, dm.widthPixels, dm.heightPixels, dm.density,
        )
        lp.x = clampedPos.x
        lp.y = clampedPos.y
        savedX = lp.x
        savedY = lp.y
        safeUpdateViewLayout(rv, lp)
        commitRemoteResize()
    }

    private fun commitRemoteResize() {
        val (streamW, streamH, reqDpi) = FloatingCastGestureHandler.computeStreamSpecForView(expandedW, expandedH, resources.displayMetrics.density)
        client?.sendResizeConfig(streamW, streamH, reqDpi, 60)
    }

    @Suppress("DEPRECATION")
    private fun showImeBar() {
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        val bar = imeInputBar ?: return
        val edit = imeEditText ?: return
        isImeBarVisible = true
        refreshImeButtonAppearance()
        bar.visibility = View.VISIBLE
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        safeUpdateViewLayout(rv, lp)
        rv.requestLayout()
        edit.requestFocus()
        mainHandler.postDelayed({
            if (!isImeBarVisible) return@postDelayed
            edit.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm != null && !imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)) {
                imm.showSoftInput(edit, InputMethodManager.SHOW_FORCED)
            }
        }, 120L)
    }

    private fun toggleImeBar() {
        if (!isImeBarVisible) showImeBar() else hideImeBar()
    }

    private fun hideImeBar() {
        isImeBarVisible = false
        refreshImeButtonAppearance()
        val edit = imeEditText
        if (edit != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edit.windowToken, 0)
            edit.clearFocus()
        }
        imeInputBar?.visibility = View.GONE
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        safeUpdateViewLayout(rv, lp)
    }

    private fun sendImeBarText() {
        val edit = imeEditText ?: return
        val txt = edit.text?.toString().orEmpty()
        if (txt.isNotEmpty()) {
            client?.sendTextEvent(txt, commitPaste = true)
            edit.setText("")
        }
        hideImeBar()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun collapseToBubble() {
        if (isCollapsed) return
        hideImeBar()
        isCollapsed = true
        rootView?.visibility = View.GONE

        val windowManager = wm ?: return
        val initial = if (isMirrorMode) {
            getString(R.string.float_bubble_mirror_char)
        } else {
            appLabel.trim().take(1).ifEmpty { getString(R.string.float_bubble_app_fallback_char) }
        }
        val pill = FloatingCastChromeHelper.buildBubblePill(this, initial, isMirrorMode)
        val bLp = WindowManager.LayoutParams(
            dp(52),
            dp(52),
            AndroidApiCompat.overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceAtLeast(dp(10))
            y = savedY.coerceAtLeast(dp(48))
        }

        FloatingCastGestureHandler.installBubbleTouchListener(
            pill = pill,
            bLp = bLp,
            slop = ViewConfiguration.get(this).scaledTouchSlop,
            getDisplayMetrics = { resources.displayMetrics },
            onUpdateLayout = ::safeUpdateViewLayout,
            onExpandFromBubble = ::expandFromBubble,
        )
        try {
            windowManager.addView(pill, bLp)
            bubbleView = pill
            bubbleParams = bLp
        } catch (_: Throwable) {
            expandFromBubble()
        }
    }

    private fun expandFromBubble() {
        bubbleView?.let { b ->
            try { wm?.removeView(b) } catch (_: Throwable) {}
        }
        bubbleView = null
        bubbleParams = null
        isCollapsed = false
        rootView?.visibility = View.VISIBLE
        client?.requestKeyFrame()
    }

    private fun updateOverlayStatus(msg: String, dotColor: Int, showRetry: Boolean) {
        statusDotView?.background = FloatingCastChromeHelper.circleDrawable(dotColor)
        overlayBannerText?.text = msg
        overlayRetryBtn?.visibility = if (showRetry) View.VISIBLE else View.GONE
        overlayBanner?.visibility = View.VISIBLE
    }

    private fun removeAllOverlayViews() {
        rootView?.let { v -> try { wm?.removeView(v) } catch (_: Throwable) {} }
        rootView = null
        layoutParams = null
        bubbleView?.let { v -> try { wm?.removeView(v) } catch (_: Throwable) {} }
        bubbleView = null
        bubbleParams = null
        activeSurface?.release()
        activeSurface = null
        textureView = null
    }

    private fun safeUpdateViewLayout(v: View, lp: WindowManager.LayoutParams) {
        try { wm?.updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    private fun stopForegroundAndSelf() {
        runCatching { AndroidApiCompat.stopForegroundRemoveCompat(this) }
        stopSelf()
    }

    private fun dp(value: Int): Int = OpalCastTheme.dp(this, value)
}
