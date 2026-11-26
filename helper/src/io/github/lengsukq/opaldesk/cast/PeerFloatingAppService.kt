package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
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
    )

    companion object {
        private const val TAG = "OpalFloatingApp"
        private const val CHANNEL_ID = "opaldesk_floating_app"
        private const val NOTIF_ID = 3930
        private const val MUTEX_TAG = "floating_app"

        const val ACTION_START = "io.github.lengsukq.opaldesk.FLOATING_APP_START"
        const val ACTION_STOP = "io.github.lengsukq.opaldesk.FLOATING_APP_STOP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PEER_LABEL = "peer_label"
        const val EXTRA_APP_PACKAGE = "app_pkg"
        const val EXTRA_APP_ACTIVITY = "app_cls"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_HANDOVER_TOKEN = "handover_token"

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
            handoverToken: String = "",
        ): Boolean {
            if (!Settings.canDrawOverlays(ctx)) return false
            val intent = Intent(ctx.applicationContext, PeerFloatingAppService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PEER_LABEL, peerLabel)
                putExtra(EXTRA_APP_PACKAGE, appPackage)
                putExtra(EXTRA_APP_ACTIVITY, appActivity)
                putExtra(EXTRA_APP_LABEL, appLabel)
                putExtra(EXTRA_HANDOVER_TOKEN, handoverToken)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.applicationContext.startForegroundService(intent)
            } else {
                ctx.applicationContext.startService(intent)
            }
            return true
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
    private var overlayBanner: LinearLayout? = null
    private var overlayBannerText: TextView? = null
    private var overlayRetryBtn: TextView? = null
    private var imeInputBar: LinearLayout? = null
    private var imeEditText: EditText? = null

    private var client: OpalCastPeerClient? = null
    private var host: String = ""
    private var port: Int = 19878
    private var peerLabel: String = ""
    private var appPackage: String = ""
    private var appActivity: String = ""
    private var appLabel: String = ""
    private var handoverToken: String = ""
    private var isLandscape = false
    private var isCollapsed = false
    private var isImeBarVisible = false

    private var expandedW = 0
    private var expandedH = 0
    private var savedX = 48
    private var savedY = 140

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
        val nextPkg = intent?.getStringExtra(EXTRA_APP_PACKAGE).orEmpty().trim()
        val nextCls = intent?.getStringExtra(EXTRA_APP_ACTIVITY).orEmpty().trim()
        val nextAppLabel = intent?.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank { nextPkg }
        val nextPeerLabel = intent?.getStringExtra(EXTRA_PEER_LABEL).orEmpty().ifBlank { nextHost }
        val nextHandover = intent?.getStringExtra(EXTRA_HANDOVER_TOKEN).orEmpty().trim()

        // Always satisfy startForegroundService() contract before any early validation stopSelf().
        startForegroundNotification(nextAppLabel.ifBlank { "远程应用" }, nextPeerLabel.ifBlank { "远端设备" })

        if (!Settings.canDrawOverlays(this)) {
            OpalLogStore.w(TAG, "Aborting start: SYSTEM_ALERT_WINDOW permission not granted")
            Toast.makeText(this, "请先授予 OpalDesk 悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        if (nextHost.isEmpty() || nextPkg.isEmpty()) {
            OpalLogStore.w(TAG, "Aborting start: missing host or appPackage (pkg='$nextPkg')")
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
            Toast.makeText(this, check.reason.ifBlank { "当前不可启动远程悬浮窗" }, Toast.LENGTH_LONG).show()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        OpalPeerDiscovery.stopAdvertise()
        if (remoteSt.wifiEnabled) {
            BridgeClient.setWifiStreamEnabledAsync(false)
        }

        host = nextHost
        port = nextPort
        peerLabel = nextPeerLabel
        appPackage = nextPkg
        appActivity = nextCls
        appLabel = nextAppLabel
        handoverToken = nextHandover

        _activeFlow.value = ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel)
        AppPrefs.recordAppLaunch(this, nextPkg)

        tearDownSessionOnly()
        ensureFloatingWindowAttached()
        connectRemoteStandaloneApp()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tearDownSessionOnly()
        removeAllOverlayViews()
        _activeFlow.value = null
        OpalPeerCastMutex.endReceiving(MUTEX_TAG)
        super.onDestroy()
    }

    private fun tearDownSessionOnly() {
        client?.stop()
        client = null
    }

    private fun connectRemoteStandaloneApp() {
        updateOverlayStatus("正在连接 $peerLabel…", dotColor = 0xFFF59E0B.toInt(), showRetry = false)
        val (streamW, streamH, reqDpi) = computeStreamSpecForView(expandedW, expandedH)
        val savedTok = AppPrefs.getPeerAuthToken(this, host)
        val tokenToUse = savedTok.ifBlank { handoverToken }
        handoverToken = "" // single-use in memory only

        val boundNet = if (AppPrefs.isUsbTetherCastEnabled(this)) {
            UsbTetherPeerDetector.resolveBoundNetworkForHost(host)
        } else {
            null
        }
        val peerClient = OpalCastPeerClient(
            host = host,
            port = port,
            savedAuthToken = tokenToUse,
            clientId = AppPrefs.getOrCreatePeerClientId(this),
            reqWidth = streamW,
            reqHeight = streamH,
            reqDpi = reqDpi,
            reqFps = 60,
            sessionRole = OpalCastPeerClient.SESSION_ROLE_STANDALONE_APP,
            appPackage = appPackage,
            appActivity = appActivity,
            targetNetwork = boundNet,
            audioEnabled = false,
            listener = object : OpalCastPeerClient.Listener {
                override fun onWaitingForApproval() {
                    mainHandler.post {
                        updateOverlayStatus("等待对方设备点击允许…", dotColor = 0xFFF59E0B.toInt(), showRetry = false)
                    }
                }

                override fun onAuthenticated(
                    peerName: String,
                    authToken: String,
                    permanent: Boolean,
                    certFingerprint: String,
                ) {
                    if (permanent && authToken.isNotBlank()) {
                        AppPrefs.savePeerAuthToken(this@PeerFloatingAppService, host, authToken)
                    }
                    OpalPeerCastMutex.updateReceivingPeerName(peerName)
                    mainHandler.post {
                        peerLabel = peerName.ifBlank { peerLabel }
                        _activeFlow.value = ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel)
                        updateOverlayStatus("正在启动 $appLabel…", dotColor = 0xFF10B981.toInt(), showRetry = false)
                    }
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) {
                    mainHandler.post {
                        try {
                            textureView?.surfaceTexture?.setDefaultBufferSize(
                                width.coerceAtLeast(2),
                                height.coerceAtLeast(2),
                            )
                        } catch (_: Throwable) {
                        }
                        statusDotView?.background = circleDrawable(0xFF28C840.toInt())
                        overlayBanner?.visibility = View.GONE
                    }
                }

                override fun onRemoteImeRequest(show: Boolean, inputType: Int) {
                    mainHandler.post {
                        if (show && !isCollapsed) {
                            showImeBar()
                        } else if (!show && isImeBarVisible) {
                            hideImeBar()
                        }
                    }
                }

                override fun onError(message: String) {
                    OpalLogStore.w(TAG, "Remote standalone app error (pkg=$appPackage): $message")
                    mainHandler.post {
                        updateOverlayStatus(message, dotColor = 0xFFEF4444.toInt(), showRetry = true)
                    }
                }

                override fun onDisconnected(reason: String) {
                    OpalLogStore.i(TAG, "Remote standalone app disconnected (pkg=$appPackage): $reason")
                    mainHandler.post {
                        if (reason == "app_exited" || reason == "Session closed") {
                            stopSelf()
                            return@post
                        }
                        if (overlayBanner?.visibility != View.VISIBLE || overlayRetryBtn?.visibility != View.VISIBLE) {
                            updateOverlayStatus(reason.ifBlank { "连接已断开" }, dotColor = 0xFFEF4444.toInt(), showRetry = true)
                        }
                    }
                }
            },
        )
        client = peerClient
        activeSurface?.let { s ->
            if (s.isValid) peerClient.attachSurface(s)
        }
        peerClient.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureFloatingWindowAttached() {
        if (rootView != null) {
            titleTextView?.text = appLabel
            if (isCollapsed) expandFromBubble()
            return
        }
        val windowManager = wm ?: return
        val dm = resources.displayMetrics
        val initialSize = FloatingOverlayGeometry.computeWindowSize(
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            density = dm.density,
            isLandscape = isLandscape,
        )
        expandedW = initialSize.width
        expandedH = initialSize.height
        val initialPos = FloatingOverlayGeometry.clampVisiblePosition(
            x = dp(18),
            y = dp(90),
            windowW = expandedW,
            windowH = expandedH,
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            density = dm.density,
        )
        savedX = initialPos.x
        savedY = initialPos.y

        AppPrefs.hydrateFlows(this)
        val isDark = resolveFloatingDarkTheme()
        val containerBg = if (isDark) 0xF2191E2A.toInt() else 0xF5F6F9FE.toInt()
        val containerStroke = if (isDark) 0x3AFFFFFF else 0xC8D2DEEE.toInt()
        val headerBg = if (isDark) 0xEB222838.toInt() else 0xF0FFFFFF.toInt()
        val dividerColor = if (isDark) 0x24FFFFFF else 0x1A0F172A
        val titleInk = if (isDark) 0xFFF5F7FB.toInt() else 0xFF0F172A.toInt()
        val pillBg = if (isDark) 0x1CFFFFFF else 0x100F172A
        val pillBorder = if (isDark) 0x2EFFFFFF else 0x1E0F172A
        val pillInk = if (isDark) 0xFFD8E1F0.toInt() else 0xFF334155.toInt()
        val imeBarBg = if (isDark) 0xF5151A25.toInt() else 0xF5EEF3FA.toInt()
        val canvasBg = if (isDark) 0xFF0B0E15.toInt() else 0xFFF4F7FC.toInt()
        val bannerBg = if (isDark) 0xE6101520.toInt() else 0xEBF4F8FE.toInt()
        val cornerRadiusPx = dp(20).toFloat()

        val strokePx = max(2, (1.25f * resources.displayMetrics.density).roundToInt())
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(containerBg)
                cornerRadius = cornerRadiusPx
                setStroke(strokePx, containerStroke)
            }
            setPadding(strokePx, strokePx, strokePx, strokePx)
            // Keep elevation = 0f on root TYPE_APPLICATION_OVERLAY view: dynamic 3D HWUI shadow on a
            // tight-bounded overlay window gets clipped along left/right edges and flashes on every
            // 60fps TextureView scroll frame on Adreno GPUs. Clean static frosted bezel = zero flicker.
            elevation = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
        }

        // ---- Authentic macOS Liquid Frosted Glass Title Bar (40dp) ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBg)
            setPadding(dp(12), dp(6), dp(10), dp(6))
        }

        // macOS Traffic Lights: Red = Close, Yellow = Collapse to Bubble, Green = Rotate Orientation
        val lightsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lightsRow.addView(
            buildTrafficLightDot(0xFFFF5F57.toInt(), 0xFFE0443E.toInt()) { stopSelf() },
            LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(7) },
        )
        lightsRow.addView(
            buildTrafficLightDot(0xFFFEBC2E.toInt(), 0xFFD89E24.toInt()) { collapseToBubble() },
            LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(7) },
        )
        lightsRow.addView(
            buildTrafficLightDot(0xFF28C840.toInt(), 0xFF1AAB29.toInt()) { toggleOrientation() },
            LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(10) },
        )
        header.addView(lightsRow)

        val dot = View(this).apply {
            background = circleDrawable(0xFFF59E0B.toInt())
        }
        statusDotView = dot
        header.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(6) })

        val titleTv = TextView(this).apply {
            text = appLabel
            setTextColor(titleInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleTextView = titleTv
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })

        header.addView(
            buildHeaderPill("返回", pillBg, pillBorder, pillInk) {
                client?.sendSystemAction(OpalCastPeerClient.SYS_BACK)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(5)
            },
        )
        header.addView(
            buildHeaderPill("键盘", pillBg, pillBorder, pillInk) {
                toggleImeBar()
            },
        )

        installDragMoveListener(header)
        container.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        // Hairline macOS titlebar separator
        container.addView(
            View(this).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1))),
        )

        // ---- Optional Inline IME Input Bar (hidden by default so touches outside go to local apps) ----
        val imeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(imeBarBg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        val edit = EditText(this).apply {
            hint = "输入文字发送到 $appLabel…"
            setHintTextColor(if (isDark) 0x88CBD5E1.toInt() else 0x8864748B.toInt())
            setTextColor(titleInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                ) {
                    sendImeBarText()
                    true
                } else {
                    false
                }
            }
        }
        imeEditText = edit
        val sendBtn = TextView(this).apply {
            text = "发送"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF007AFF.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener { sendImeBarText() }
        }
        imeBar.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        imeBar.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        imeInputBar = imeBar
        container.addView(imeBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- Live Zero-Flicker Opaque TextureView Content Area + Status Overlay + Corner Resize Handle ----
        val body = FrameLayout(this).apply {
            setBackgroundColor(canvasBg)
        }

        val tv = TextureView(this).apply {
            // Strictly opaque video surface prevents HWUI alpha-blend flickering on Qualcomm Adreno overlays
            isOpaque = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    val rw = client?.remoteWidth ?: width
                    val rh = client?.remoteHeight ?: height
                    if (rw >= 2 && rh >= 2) {
                        try {
                            st.setDefaultBufferSize(rw, rh)
                        } catch (_: Throwable) {
                        }
                    }
                    activeSurface?.release()
                    val surf = Surface(st)
                    activeSurface = surf
                    client?.attachSurface(surf)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    val rw = client?.remoteWidth ?: return
                    val rh = client?.remoteHeight ?: return
                    if (rw >= 2 && rh >= 2) {
                        try {
                            st.setDefaultBufferSize(rw, rh)
                        } catch (_: Throwable) {
                        }
                    }
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    client?.attachSurface(null)
                    activeSurface?.release()
                    activeSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    if (overlayBanner?.visibility == View.VISIBLE) {
                        overlayBanner?.visibility = View.GONE
                    }
                }
            }
            setOnTouchListener { v, ev ->
                val c = client ?: return@setOnTouchListener true
                if (c.isMouseMotion(ev)) c.sendMouseEvent(ev, v.width, v.height)
                else c.sendTouchEvent(ev, v.width, v.height)
                true
            }
            setOnGenericMotionListener { v, ev ->
                val c = client ?: return@setOnGenericMotionListener false
                c.handleGenericMouseMotion(ev, v.width, v.height)
            }
        }
        textureView = tv
        body.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Status / approval / retry overlay
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bannerBg)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val bannerTv = TextView(this).apply {
            text = "正在连接…"
            setTextColor(titleInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        val retryBtn = TextView(this).apply {
            text = "重新连接"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF007AFF.toInt())
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), dp(7), dp(16), dp(7))
            visibility = View.GONE
            setOnClickListener {
                tearDownSessionOnly()
                connectRemoteStandaloneApp()
            }
        }
        overlayBanner = banner
        overlayBannerText = bannerTv
        overlayRetryBtn = retryBtn
        banner.addView(bannerTv)
        banner.addView(retryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        body.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Sleek macOS 2-line diagonal bottom-right resize grip matching CompositorWindowNode
        val gripTint = if (isDark) 0x99CBD5E1.toInt() else 0x88475569.toInt()
        val resizeGrip = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gripTint
                strokeWidth = dp(2).toFloat().coerceAtLeast(2.2f)
                strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val pad = dp(6).toFloat()
                val w = width.toFloat() - pad
                val h = height.toFloat() - pad
                val span = dp(10).toFloat()
                canvas.drawLine(w - span, h, w, h - span, paint)
                canvas.drawLine(w - span * 0.52f, h, w, h - span * 0.52f, paint)
            }
        }
        installResizeListener(resizeGrip)
        body.addView(
            resizeGrip,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END),
        )

        container.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val lp = WindowManager.LayoutParams(
            expandedW,
            expandedH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
            OpalLogStore.i(
                TAG,
                "Attached overlay ${expandedW}x${expandedH} at ($savedX,$savedY) " +
                    "screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} " +
                    "density=${resources.displayMetrics.density} landscape=$isLandscape pkg=$appPackage",
            )
        } catch (t: Throwable) {
            OpalLogStore.e(TAG, "Failed to attach floating overlay", t)
            Toast.makeText(this, "无法创建悬浮窗：${t.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun resolveFloatingDarkTheme(): Boolean = when (AppPrefs.themeModeFlow.value) {
        AppPrefs.THEME_DARK -> true
        AppPrefs.THEME_SYSTEM ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    private fun buildTrafficLightDot(fillColor: Int, borderColor: Int, onClick: () -> Unit): View =
        View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                setStroke(max(1, (0.8f * resources.displayMetrics.density).roundToInt()), borderColor)
            }
            setOnClickListener { onClick() }
        }

    private fun buildHeaderPill(
        label: String,
        bgColor: Int,
        borderColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(10).toFloat()
            setStroke(max(1, (0.7f * resources.displayMetrics.density).roundToInt()), borderColor)
        }
        setPadding(dp(9), dp(4), dp(9), dp(4))
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragMoveListener(handle: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val rv = rootView ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    val clamped = FloatingOverlayGeometry.clampDragPosition(
                        targetX = startX + (ev.rawX - downRawX).roundToInt(),
                        targetY = startY + (ev.rawY - downRawY).roundToInt(),
                        windowW = lp.width,
                        screenW = dm.widthPixels,
                        screenH = dm.heightPixels,
                        density = dm.density,
                    )
                    lp.x = clamped.x
                    lp.y = clamped.y
                    savedX = lp.x
                    savedY = lp.y
                    safeUpdateViewLayout(rv, lp)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installResizeListener(grip: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startW = 0
        var startH = 0
        grip.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val rv = rootView ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startW = lp.width
                    startH = lp.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    val clamped = FloatingOverlayGeometry.clampResizeSize(
                        targetW = startW + (ev.rawX - downRawX).roundToInt(),
                        targetH = startH + (ev.rawY - downRawY).roundToInt(),
                        screenW = dm.widthPixels,
                        screenH = dm.heightPixels,
                        density = dm.density,
                    )
                    lp.width = clamped.width
                    lp.height = clamped.height
                    expandedW = lp.width
                    expandedH = lp.height
                    safeUpdateViewLayout(rv, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    commitRemoteResize()
                    true
                }
                else -> false
            }
        }
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
        expandedW = nextSize.width
        expandedH = nextSize.height
        lp.width = expandedW
        lp.height = expandedH
        val clampedPos = FloatingOverlayGeometry.clampVisiblePosition(
            x = lp.x,
            y = lp.y,
            windowW = lp.width,
            windowH = lp.height,
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            density = dm.density,
        )
        lp.x = clampedPos.x
        lp.y = clampedPos.y
        savedX = lp.x
        savedY = lp.y
        safeUpdateViewLayout(rv, lp)
        commitRemoteResize()
    }

    private fun commitRemoteResize() {
        val (streamW, streamH, reqDpi) = computeStreamSpecForView(expandedW, expandedH)
        client?.sendResizeConfig(streamW, streamH, reqDpi, 60, 12_000)
    }

    /**
     * Computes encoder-aligned `(streamW, streamH, reqDpi)` matched 1:1 to this floating window's
     * physical dp dimensions on the receiver screen so text renders at native readable scale
     * (never microscopic on high-DPI tablets) and remains safe even on narrow windows.
     */
    private fun computeStreamSpecForView(viewW: Int, viewH: Int): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val contentH = max(dp(180), viewH - dp(41))
        val ratio = viewW.toFloat() / contentH.toFloat()
        val targetLong = 1440
        val rawW: Int
        val rawH: Int
        if (ratio >= 1f) {
            rawW = targetLong
            rawH = (targetLong / ratio).roundToInt().coerceIn(544, targetLong)
        } else {
            rawH = targetLong
            rawW = (targetLong * ratio).roundToInt().coerceIn(544, targetLong)
        }
        val streamW = (rawW and 7.inv()).coerceAtLeast(544)
        val streamH = (rawH and 7.inv()).coerceAtLeast(544)

        val viewWdp = viewW / density
        val viewHdp = contentH / density
        val reqDpi = if (streamH >= streamW) {
            val targetLogicalWdp = viewWdp.coerceIn(330f, 430f)
            (streamW * 160f / targetLogicalWdp).roundToInt().coerceIn(240, 480)
        } else {
            val targetLogicalHdp = viewHdp.coerceIn(340f, 520f)
            (streamH * 160f / targetLogicalHdp).roundToInt().coerceIn(240, 480)
        }
        return Triple(streamW, streamH, reqDpi)
    }

    @Suppress("DEPRECATION")
    private fun showImeBar() {
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        val bar = imeInputBar ?: return
        val edit = imeEditText ?: return
        OpalLogStore.i(TAG, "Opening floating IME bar on receiver")
        isImeBarVisible = true
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
        val initial = appLabel.trim().take(1).ifEmpty { "浮" }
        val pill = TextView(this).apply {
            text = initial
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE1D4ED8.toInt())
                setStroke(dp(2), 0xAAFFFFFF.toInt())
            }
            elevation = dp(12).toFloat()
        }
        val bLp = WindowManager.LayoutParams(
            dp(52),
            dp(52),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceAtLeast(dp(10))
            y = savedY.coerceAtLeast(dp(48))
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        pill.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = bLp.x
                    startY = bLp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).roundToInt()
                    val dy = (ev.rawY - downY).roundToInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                    if (dragged) {
                        val dm = resources.displayMetrics
                        val clamped = FloatingOverlayGeometry.clampBubblePosition(
                            targetX = startX + dx,
                            targetY = startY + dy,
                            screenW = dm.widthPixels,
                            screenH = dm.heightPixels,
                            density = dm.density,
                        )
                        bLp.x = clamped.x
                        bLp.y = clamped.y
                        safeUpdateViewLayout(pill, bLp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) expandFromBubble()
                    true
                }
                else -> false
            }
        }
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

    private fun circleDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun stopForegroundAndSelf() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startForegroundNotification(appTitle: String, hostName: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "远程应用悬浮窗",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持远程单应用悬浮窗在应用外运行"
        }
        nm.createNotificationChannel(ch)

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
            Intent(this, PeerFloatingAppService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opaldesk)
            .setContentTitle("悬浮窗：$appTitle")
            .setContentText("正在流式运行来自 $hostName 的应用")
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "关闭悬浮窗", stopPi).build())
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "Floating foreground warning", t)
        }
    }
}
