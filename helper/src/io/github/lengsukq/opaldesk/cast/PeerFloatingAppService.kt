package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Receiver-side system floating window overlay ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY])
 * that streams a single standalone app (`SESSION_ROLE_STANDALONE_APP`) from a paired remote OpalDesk
 * device so the user can operate local apps simultaneously while interacting with the remote app.
 *
 * Strictly gated behind standard Android [Settings.canDrawOverlays] permission (zero OEM hacks).
 */
class PeerFloatingAppService : Service() {

    data class ActiveFloatingInfo(
        val host: String,
        val port: Int,
        val peerName: String,
        val appPackage: String,
        val appLabel: String,
        val isMirror: Boolean = false,
    )

    companion object {
        private const val TAG = "OpalFloatingApp"
        private const val CHANNEL_ID = "opaldesk_floating_app"
        private const val NOTIF_ID = 3930
        private const val MUTEX_TAG = "floating_app"
        const val MIRROR_SENTINEL_PKG = "__opaldesk_mirror__"

        const val ACTION_START = "io.github.lengsukq.opaldesk.FLOATING_APP_START"
        const val ACTION_STOP = "io.github.lengsukq.opaldesk.FLOATING_APP_STOP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PEER_LABEL = "peer_label"
        const val EXTRA_APP_PACKAGE = "app_pkg"
        const val EXTRA_APP_ACTIVITY = "app_cls"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_APP_USER_ID = "app_user_id"
        const val EXTRA_HANDOVER_TOKEN = "handover_token"
        const val EXTRA_IS_MIRROR = "is_mirror"

        private val _activeFlow = MutableStateFlow<ActiveFloatingInfo?>(null)
        val activeFlow: StateFlow<ActiveFloatingInfo?> = _activeFlow.asStateFlow()

        fun start(
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
            return startFloatingOverlayApp(
                ctx = ctx,
                host = host,
                port = port,
                peerLabel = peerLabel,
                appPackage = appPackage,
                appActivity = appActivity,
                appLabel = appLabel,
                appUserId = appUserId,
                handoverToken = handoverToken,
                isMirror = false,
            )
        }

        fun startMirror(
            ctx: Context,
            host: String,
            port: Int,
            peerLabel: String,
            handoverToken: String = "",
        ): Boolean {
            return startFloatingOverlayApp(
                ctx = ctx,
                host = host,
                port = port,
                peerLabel = peerLabel,
                appPackage = MIRROR_SENTINEL_PKG,
                appActivity = "",
                appLabel = "",
                appUserId = 0,
                handoverToken = handoverToken,
                isMirror = true,
            )
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(Intent(ctx.applicationContext, PeerFloatingAppService::class.java))
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
    private var blackoutIconView: MirrorGlyphIconView? = null
    private var imeBtnContainer: FrameLayout? = null
    private var imeIconView: MirrorGlyphIconView? = null
    private var overlayBanner: LinearLayout? = null
    private var overlayBannerText: TextView? = null
    private var overlayRetryBtn: TextView? = null
    private var imeInputBar: LinearLayout? = null
    private var imeEditText: EditText? = null

    private var client: OpalCastPeerClient? = null
    private var wifiLowLatencyLock: WifiLowLatencyLock? = null
    private var host: String = ""
    private var port: Int = 19878
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy.wrapContext(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        val nextPort = (intent?.getIntExtra(EXTRA_PORT, 19878) ?: 19878).coerceIn(1, 65535)
        val nextIsMirror = intent?.getBooleanExtra(EXTRA_IS_MIRROR, false) == true
        val nextPkg = intent?.getStringExtra(EXTRA_APP_PACKAGE).orEmpty().trim()
            .ifBlank { if (nextIsMirror) MIRROR_SENTINEL_PKG else "" }
        val nextCls = if (nextIsMirror) "" else intent?.getStringExtra(EXTRA_APP_ACTIVITY).orEmpty().trim()
        val nextUserId = if (nextIsMirror) 0 else (intent?.getIntExtra(EXTRA_APP_USER_ID, 0) ?: 0).coerceAtLeast(0)
        val nextPeerLabel = intent?.getStringExtra(EXTRA_PEER_LABEL).orEmpty().ifBlank { nextHost }
        val nextAppLabel = intent?.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank {
            if (nextIsMirror) getString(R.string.floating_mirror_title, nextPeerLabel) else nextPkg
        }
        val nextHandover = intent?.getStringExtra(EXTRA_HANDOVER_TOKEN).orEmpty().trim()

        // Always satisfy startForegroundService() contract before any early validation stopSelf().
        startForegroundNotification(
            nextAppLabel.ifBlank { getString(if (nextIsMirror) R.string.picker_mirror_btn else R.string.floating_remote_app) },
            nextPeerLabel.ifBlank { getString(R.string.common_peer_device) },
            nextIsMirror,
        )

        if (!Settings.canDrawOverlays(this)) {
            OpalLogStore.w(TAG, "Aborting start: SYSTEM_ALERT_WINDOW permission not granted")
            Toast.makeText(this, getString(R.string.floating_need_overlay), Toast.LENGTH_SHORT).show()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        if (nextHost.isEmpty() || (!nextIsMirror && nextPkg.isEmpty())) {
            OpalLogStore.w(TAG, "Aborting start: missing host or appPackage (mirror=$nextIsMirror pkg='$nextPkg')")
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        // Strict one-way casting mutex guard
        val remoteSt = BridgeClient.remoteStatusFlow.value
        val check = OpalPeerCastMutex.validateStartReceiving(
            daemonSending = remoteSt.connected,
            sendingPeerName = remoteSt.peerName,
            targetHost = nextHost,
            selfLanIp = remoteSt.lanIp,
        )
        if (!check.allowed || !OpalPeerCastMutex.beginReceiving(nextHost, nextPort, nextPeerLabel, MUTEX_TAG)) {
            OpalLogStore.w(TAG, "Aborting start: cast mutex rejected (${check.reason.ifBlank { "busy" }})")
            Toast.makeText(this, check.reason.ifBlank { getString(R.string.floating_start_blocked) }, Toast.LENGTH_LONG).show()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        OpalPeerDiscovery.stopAdvertise()
        if (remoteSt.wifiEnabled) {
            BridgeClient.setWifiStreamEnabledAsync(false)
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

        _activeFlow.value = ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel, isMirror = isMirrorMode)
        if (!isMirrorMode) {
            AppPrefs.recordAppLaunch(this, nextPkg)
        }

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
        _activeFlow.value = null
        OpalPeerCastMutex.endReceiving(MUTEX_TAG)
        super.onDestroy()
    }

    private fun tearDownSessionOnly() {
        client?.stop()
        client = null
        wifiLowLatencyLock?.release()
    }

    private fun connectRemoteSession() {
        updateOverlayStatus("正在连接 $peerLabel…", dotColor = 0xFFF59E0B.toInt(), showRetry = false)
        val pendingHandover = handoverToken
        handoverToken = "" // single-use in memory only
        val (peerClient, wifiLock) = createAndStartFloatingPeerClient(
            ctx = this,
            mainHandler = mainHandler,
            host = host,
            port = port,
            isMirrorMode = isMirrorMode,
            appPackage = appPackage,
            appActivity = appActivity,
            appUserId = appUserId,
            expandedW = expandedW,
            expandedH = expandedH,
            handoverToken = pendingHandover,
            existingWifiLock = wifiLowLatencyLock,
            activeSurface = activeSurface,
            callbacks = FloatingSessionCallbacks(
                onWaitingForApproval = {
                    updateOverlayStatus("等待对方设备点击允许…", dotColor = 0xFFF59E0B.toInt(), showRetry = false)
                },
                onAuthenticated = { peerName, _, _ ->
                    peerLabel = peerName.ifBlank { peerLabel }
                    if (isMirrorMode) {
                        appLabel = "$peerLabel · 主屏镜像"
                        titleTextView?.text = peerLabel
                    }
                    _activeFlow.value = ActiveFloatingInfo(
                        host, port, peerLabel, appPackage, appLabel, isMirror = isMirrorMode,
                    )
                    val startHint = if (isMirrorMode) "正在镜像 $peerLabel 主屏…" else "正在启动 $appLabel…"
                    updateOverlayStatus(startHint, dotColor = 0xFF10B981.toInt(), showRetry = false)
                },
                onRemoteBlackoutChanged = { blackout ->
                    remoteBlackout = blackout
                    refreshBlackoutPillAppearance()
                },
                onVideoSizeChanged = { width, height, _ ->
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
                    statusDotView?.background = circleDrawable(0xFF28C840.toInt())
                    overlayBanner?.visibility = View.GONE
                },
                onRemoteImeRequest = { show, _ ->
                    if (show && !isCollapsed) showImeBar() else if (!show && isImeBarVisible) hideImeBar()
                },
                onError = { message ->
                    OpalLogStore.w(TAG, "Remote floating session error (mirror=$isMirrorMode pkg=$appPackage): $message")
                    updateOverlayStatus(message, dotColor = 0xFFEF4444.toInt(), showRetry = true)
                },
                onDisconnected = { reason ->
                    OpalLogStore.i(TAG, "Remote floating session disconnected (mirror=$isMirrorMode pkg=$appPackage): $reason")
                    if (reason == "app_exited" || reason == "Session closed" || reason == "Replaced by new session") {
                        stopSelf()
                        return@FloatingSessionCallbacks
                    }
                    if (overlayBanner?.visibility != View.VISIBLE || overlayRetryBtn?.visibility != View.VISIBLE) {
                        updateOverlayStatus(reason.ifBlank { "连接已断开" }, dotColor = 0xFFEF4444.toInt(), showRetry = true)
                    }
                },
            ),
        )
        client = peerClient
        wifiLowLatencyLock = wifiLock
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureFloatingWindowAttached() {
        if (rootView != null) {
            titleTextView?.text = if (isMirrorMode) peerLabel else appLabel
            if (isCollapsed) expandFromBubble()
            return
        }
        val windowManager = wm ?: return
        val bounds = computeInitialFloatingBounds(
            ctx = this,
            isMirrorMode = isMirrorMode,
            isLandscape = isLandscape,
            mirrorVideoW = mirrorVideoW,
            mirrorVideoH = mirrorVideoH,
            chromeHeightPx = floatingChromeHeightPx(),
        )
        expandedW = bounds.width
        expandedH = bounds.height
        savedX = bounds.x
        savedY = bounds.y

        AppPrefs.hydrateFlows(this)
        val theme = resolveFloatingOverlayTheme(this)
        val container = buildFloatingRootContainer(this, theme)
        container.setOnGenericMotionListener { v, ev ->
            client?.handleGenericMouseMotion(ev, v.width, v.height) == true
        }

        // ---- Authentic macOS Liquid Frosted Glass Title Bar (40dp) ----
        val headerRefs = buildFloatingHeaderBar(
            ctx = this,
            isMirrorMode = isMirrorMode,
            isDark = theme.isDark,
            headerBg = theme.headerBg,
            titleInk = theme.titleInk,
            pillBg = theme.pillBg,
            pillBorder = theme.pillBorder,
            pillInk = theme.pillInk,
            initialTitle = if (isMirrorMode) peerLabel else appLabel,
            remoteBlackout = remoteBlackout,
            onClose = { stopSelf() },
            onCollapse = { collapseToBubble() },
            onZoomOrRotate = { if (isMirrorMode) expandMirrorToFullscreen() else toggleOrientation() },
            onToggleBlackout = {
                val next = !remoteBlackout
                remoteBlackout = next
                refreshBlackoutPillAppearance()
                client?.sendSystemAction(OpalCastPeerClient.SYS_TOGGLE_BLACKOUT, if (next) 1 else 0)
            },
            onBack = { client?.sendSystemAction(OpalCastPeerClient.SYS_BACK) },
            onToggleIme = ::toggleImeBar,
        )
        statusDotView = headerRefs.statusDotView
        titleTextView = headerRefs.titleTextView
        blackoutBtnContainer = headerRefs.blackoutBtnContainer
        blackoutIconView = headerRefs.blackoutIconView
        imeBtnContainer = headerRefs.imeBtnContainer
        imeIconView = headerRefs.imeIconView
        if (isMirrorMode) {
            refreshBlackoutPillAppearance()
            refreshImeButtonAppearance()
        }
        installDragMoveListener(headerRefs.headerView)
        container.addView(headerRefs.headerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        // Hairline macOS titlebar separator
        container.addView(
            View(this).apply { setBackgroundColor(theme.dividerColor) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1))),
        )

        // ---- Optional Inline IME Input Bar (hidden by default so touches outside go to local apps) ----
        val (imeBar, edit) = buildFloatingImeInputBar(
            ctx = this,
            isDark = theme.isDark,
            imeBarBg = theme.imeBarBg,
            titleInk = theme.titleInk,
            appLabel = appLabel,
            onSendText = ::sendImeBarText,
        )
        imeEditText = edit
        imeInputBar = imeBar
        container.addView(imeBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- Live Zero-Flicker Opaque TextureView Content Area + Status Overlay + Corner Resize Handle ----
        val bodyRefs = buildFloatingContentBody(
            ctx = this,
            canvasBg = theme.canvasBg,
            bannerBg = theme.bannerBg,
            titleInk = theme.titleInk,
            getClient = { client },
            onSurfaceReady = { surf ->
                activeSurface?.release()
                activeSurface = surf
                client?.attachSurface(surf)
            },
            onSurfaceDestroyed = {
                client?.attachSurface(null)
                activeSurface?.release()
                activeSurface = null
            },
            onFirstFrameUpdated = {
                if (overlayBanner?.visibility == View.VISIBLE) {
                    overlayBanner?.visibility = View.GONE
                }
            },
            onRetry = {
                tearDownSessionOnly()
                connectRemoteSession()
            },
        )
        val body = bodyRefs.bodyView
        textureView = bodyRefs.textureView
        overlayBanner = bodyRefs.overlayBanner
        overlayBannerText = bodyRefs.overlayBannerText
        overlayRetryBtn = bodyRefs.overlayRetryBtn

        // Sleek macOS 2-line diagonal bottom-right resize grip matching CompositorWindowNode
        val resizeGrip = buildFloatingResizeGripView(this, theme.isDark)
        installResizeListener(resizeGrip)
        if (!isMirrorMode) {
            body.addView(
                resizeGrip,
                FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END),
            )
        }

        container.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (isMirrorMode) {
            // Refined symmetric macOS/Liquid-Glass bottom navigation bezel with vector icons (Back / Home / Recents)
            container.addView(
                View(this).apply { setBackgroundColor(theme.dividerColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1))),
            )
            val bottomFrame = buildFloatingMirrorBottomBar(
                ctx = this,
                isDark = theme.isDark,
                headerBg = theme.headerBg,
                resizeGrip = resizeGrip,
                onBack = { client?.sendSystemAction(OpalCastPeerClient.SYS_BACK) },
                onHome = { client?.sendSystemAction(OpalCastPeerClient.SYS_HOME) },
                onRecents = { client?.sendSystemAction(OpalCastPeerClient.SYS_RECENTS) },
            )
            container.addView(
                bottomFrame,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)),
            )
        }

        val lp = buildFloatingOverlayLayoutParams(expandedW, expandedH, savedX, savedY)

        try {
            windowManager.addView(container, lp)
            rootView = container
            layoutParams = lp
            attachedMirrorMode = isMirrorMode
            OpalLogStore.i(
                TAG,
                "Attached overlay ${expandedW}x${expandedH} at ($savedX,$savedY) " +
                    "screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} " +
                    "density=${resources.displayMetrics.density} mirror=$isMirrorMode landscape=$isLandscape pkg=$appPackage",
            )
        } catch (t: Throwable) {
            OpalLogStore.e(TAG, "Failed to attach floating overlay", t)
            Toast.makeText(this, "无法创建悬浮窗：${t.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragMoveListener(handle: View) {
        installFloatingDragMoveListener(
            ctx = this,
            handle = handle,
            getLayoutParams = { layoutParams },
            getRootView = { rootView },
            onPositionChanged = { x, y ->
                savedX = x
                savedY = y
            },
            onUpdateLayout = ::safeUpdateViewLayout,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installResizeListener(grip: View) {
        installFloatingResizeListener(
            ctx = this,
            grip = grip,
            getLayoutParams = { layoutParams },
            getRootView = { rootView },
            isMirrorMode = { isMirrorMode },
            getMirrorVideoSize = { mirrorVideoW to mirrorVideoH },
            getChromeHeightPx = ::floatingChromeHeightPx,
            onSizeChanged = { w, h ->
                expandedW = w
                expandedH = h
            },
            onUpdateLayout = ::safeUpdateViewLayout,
            onCommitResize = ::commitRemoteResize,
        )
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
        val matched = FloatingOverlayGeometry.computeAspectMatchedWindowSize(
            videoW = videoW,
            videoH = videoH,
            chromeHeightPx = floatingChromeHeightPx(),
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            density = dm.density,
        )
        applyFloatingWindowResizeAndClamp(
            ctx = this,
            lp = lp,
            rv = rv,
            targetW = matched.width,
            targetH = matched.height,
            onUpdated = { w, h, x, y ->
                expandedW = w
                expandedH = h
                savedX = x
                savedY = y
            },
            onUpdateLayout = ::safeUpdateViewLayout,
        )
    }

    private fun expandMirrorToFullscreen() {
        tearDownSessionOnly()
        expandFloatingMirrorToFullscreen(this, TAG, host, port, peerLabel)
        stopSelf()
    }

    private fun refreshBlackoutPillAppearance() {
        val btn = blackoutBtnContainer ?: return
        val glyph = blackoutIconView ?: return
        applyFloatingBlackoutPillStyle(btn, glyph, remoteBlackout, resolveFloatingDarkTheme(this))
    }

    private fun refreshImeButtonAppearance() {
        val btn = imeBtnContainer ?: return
        val glyph = imeIconView ?: return
        applyFloatingImeButtonStyle(btn, glyph, isImeBarVisible, resolveFloatingDarkTheme(this))
    }

    private fun toggleOrientation() {
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        val dm = resources.displayMetrics
        isLandscape = !isLandscape
        val nextSize = FloatingOverlayGeometry.computeWindowSize(
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            density = dm.density,
            isLandscape = isLandscape,
        )
        applyFloatingWindowResizeAndClamp(
            ctx = this,
            lp = lp,
            rv = rv,
            targetW = nextSize.width,
            targetH = nextSize.height,
            onUpdated = { w, h, x, y ->
                expandedW = w
                expandedH = h
                savedX = x
                savedY = y
            },
            onUpdateLayout = ::safeUpdateViewLayout,
        )
        commitRemoteResize()
    }

    private fun commitRemoteResize() {
        val (streamW, streamH, reqDpi) = computeStreamSpecForView(expandedW, expandedH)
        client?.sendResizeConfig(streamW, streamH, reqDpi, 60)
    }

    private fun computeStreamSpecForView(viewW: Int, viewH: Int): Triple<Int, Int, Int> =
        computeFloatingStreamSpec(this, viewW, viewH)

    private fun showImeBar() {
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        val bar = imeInputBar ?: return
        val edit = imeEditText ?: return
        isImeBarVisible = true
        refreshImeButtonAppearance()
        showFloatingImeBarOverlay(
            ctx = this,
            tag = TAG,
            mainHandler = mainHandler,
            lp = lp,
            rv = rv,
            bar = bar,
            edit = edit,
            isStillVisible = { isImeBarVisible },
            onUpdateLayout = ::safeUpdateViewLayout,
        )
    }

    private fun toggleImeBar() {
        if (!isImeBarVisible) showImeBar() else hideImeBar()
    }

    private fun hideImeBar() {
        isImeBarVisible = false
        refreshImeButtonAppearance()
        hideFloatingImeBarOverlay(
            ctx = this,
            lp = layoutParams,
            rv = rootView,
            bar = imeInputBar,
            edit = imeEditText,
            onUpdateLayout = ::safeUpdateViewLayout,
        )
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
        val (pill, bLp) = buildFloatingCollapseBubble(
            ctx = this,
            isMirrorMode = isMirrorMode,
            appLabel = appLabel,
            savedX = savedX,
            savedY = savedY,
            onUpdateLayout = ::safeUpdateViewLayout,
            onExpand = ::expandFromBubble,
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
        isCollapsed = false
        bubbleView?.let { b ->
            try { wm?.removeView(b) } catch (_: Throwable) {}
        }
        bubbleView = null
        bubbleParams = null
        rootView?.visibility = View.VISIBLE
        client?.requestKeyFrame()
    }

    private fun updateOverlayStatus(text: String, dotColor: Int, showRetry: Boolean) {
        statusDotView?.background = circleDrawable(dotColor)
        overlayBannerText?.text = text
        overlayRetryBtn?.visibility = if (showRetry) View.VISIBLE else View.GONE
        overlayBanner?.visibility = View.VISIBLE
    }

    private fun removeAllOverlayViews() {
        bubbleView?.let { v ->
            try { wm?.removeView(v) } catch (_: Throwable) {}
        }
        bubbleView = null
        rootView?.let { v ->
            try { wm?.removeView(v) } catch (_: Throwable) {}
        }
        rootView = null
        activeSurface?.release()
        activeSurface = null
    }

    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            wm?.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable = floatingCircleDrawable(color)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun stopForegroundAndSelf() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startForegroundNotification(appTitle: String, hostName: String, mirror: Boolean = false) {
        startFloatingForegroundNotification(
            service = this,
            tag = TAG,
            channelId = CHANNEL_ID,
            notifId = NOTIF_ID,
            appTitle = appTitle,
            hostName = hostName,
            mirror = mirror,
        )
    }
}
