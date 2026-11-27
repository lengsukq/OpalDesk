package io.github.lengsukq.opalcast.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.net.UsbTetherDiscovery
import io.github.lengsukq.opalcast.net.WifiLowLatencyLock
import io.github.lengsukq.opalcast.policy.FloatingGeometry
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.prefs.ReceiverPrefs
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
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
                putExtra(EXTRA_APP_LABEL, "$displayPeer · 主屏镜像")
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
    private var blackoutIconView: GlyphIconView? = null
    private var imeBtnContainer: FrameLayout? = null
    private var imeIconView: GlyphIconView? = null
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
            if (nextIsMirror) "$nextPeerLabel · 主屏镜像" else nextPkg
        }
        val nextHandover = intent?.getStringExtra(EXTRA_HANDOVER_TOKEN).orEmpty().trim()

        startForegroundNotification(
            nextAppLabel.ifBlank { if (nextIsMirror) "主屏镜像" else "远程应用" },
            nextPeerLabel.ifBlank { "远端设备" },
            nextIsMirror,
        )

        if (!AndroidApiCompat.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予 OpalCast 悬浮窗权限", Toast.LENGTH_SHORT).show()
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
        handoverToken = nextHandover
        isMirrorMode = nextIsMirror
        remoteBlackout = false
        lastSnappedMirrorW = 0
        lastSnappedMirrorH = 0

        updateActiveInfo(ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel, isMirror = isMirrorMode))
        if (!isMirrorMode) ReceiverPrefs.recordAppLaunch(this, nextPkg)
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
        updateOverlayStatus("正在连接 $peerLabel…", dotColor = 0xFFF59E0B.toInt(), showRetry = false)
        val dm = resources.displayMetrics
        val (streamW, streamH, reqDpi) = if (isMirrorMode) {
            val maxEdge = max(dm.widthPixels, dm.heightPixels).coerceIn(1280, 2400)
            Triple(maxEdge, (maxEdge * 10 / 16).coerceAtLeast(720), dm.densityDpi.coerceIn(160, 480))
        } else {
            computeStreamSpecForView(expandedW, expandedH)
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
            targetNetwork = boundNet,
            audioEnabled = isMirrorMode,
            initialDisplayMode = if (isMirrorMode) OpalCastClient.DISPLAY_MODE_MIRROR else OpalCastClient.DISPLAY_MODE_DEX,
            preferredCodec = AndroidApiCompat.resolvePreferredCastCodec(ReceiverPrefs.getVideoCodecPref(this)),
            listener = object : OpalCastClient.Listener {
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
                        ReceiverPrefs.savePeerAuthToken(this@FloatingCastService, host, authToken)
                    }
                    mainHandler.post {
                        peerLabel = peerName.ifBlank { peerLabel }
                        if (isMirrorMode) {
                            appLabel = "$peerLabel · 主屏镜像"
                            titleTextView?.text = peerLabel
                        }
                        updateActiveInfo(ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel, isMirror = isMirrorMode))
                        val hint = if (isMirrorMode) "正在镜像 $peerLabel 主屏…" else "正在启动 $appLabel…"
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
                        statusDotView?.background = circleDrawable(0xFF14B8A6.toInt())
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
                            updateOverlayStatus(reason.ifBlank { "连接已断开" }, dotColor = 0xFFEF4444.toInt(), showRetry = true)
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

        val pal = OpalCastTheme.resolve(this)
        val isDark = pal.isDark
        val containerBg = if (isDark) 0xF20D1E25.toInt() else 0xF5F4FBFA.toInt()
        val containerStroke = if (isDark) 0x452DD4BF else 0xC899D6CF.toInt()
        val headerBg = if (isDark) 0xEB12262F.toInt() else 0xF2FFFFFF.toInt()
        val dividerColor = if (isDark) 0x2679F2D0 else 0x1A0F766E
        val titleInk = pal.inkPrimary
        val pillBg = if (isDark) 0x1CFFFFFF else 0x120F766E
        val pillBorder = if (isDark) 0x2EFFFFFF else 0x220F766E
        val pillInk = pal.inkSecondary
        val imeBarBg = if (isDark) 0xF50A171D.toInt() else 0xF5EBF7F5.toInt()
        val canvasBg = if (isDark) 0xFF060E12.toInt() else 0xFFF2FAF9.toInt()
        val bannerBg = if (isDark) 0xE608141A.toInt() else 0xEBF2FAF9.toInt()
        val cornerRadiusPx = dp(20).toFloat()

        val strokePx = max(2, (1.25f * dm.density).roundToInt())
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(containerBg)
                cornerRadius = cornerRadiusPx
                setStroke(strokePx, containerStroke)
            }
            setPadding(strokePx, strokePx, strokePx, strokePx)
            elevation = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
        }

        // Title bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBg)
            setPadding(if (isMirrorMode) dp(10) else dp(12), dp(6), if (isMirrorMode) dp(8) else dp(10), dp(6))
        }

        val lightsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lightPx = if (isMirrorMode) dp(12) else dp(13)
        val lightGap = if (isMirrorMode) dp(6) else dp(7)
        val lightTail = if (isMirrorMode) dp(8) else dp(10)
        lightsRow.addView(
            buildTrafficLightDot(0xFFFF5F57.toInt(), 0xFFE0443E.toInt()) { stopSelf() },
            LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightGap },
        )
        lightsRow.addView(
            buildTrafficLightDot(0xFFFEBC2E.toInt(), 0xFFD89E24.toInt()) { collapseToBubble() },
            LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightGap },
        )
        lightsRow.addView(
            buildTrafficLightDot(0xFF28C840.toInt(), 0xFF1AAB29.toInt()) {
                if (isMirrorMode) expandMirrorToFullscreen() else toggleOrientation()
            },
            LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightTail },
        )
        header.addView(lightsRow)

        val dot = View(this).apply { background = circleDrawable(0xFFF59E0B.toInt()) }
        statusDotView = dot
        header.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(5) })

        val titleTv = TextView(this).apply {
            text = if (isMirrorMode) peerLabel else appLabel
            setTextColor(titleInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleTextView = titleTv
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4)
        })

        if (isMirrorMode) {
            val (bBtn, bIcon) = buildChromeIconButton(Glyph.SCREEN_OFF, "熄屏", 13.5f, isDark) {
                remoteBlackout = !remoteBlackout
                refreshBlackoutAppearance()
                client?.sendSystemAction(OpalCastClient.SYS_TOGGLE_BLACKOUT, if (remoteBlackout) 1 else 0)
            }
            blackoutBtnContainer = bBtn
            blackoutIconView = bIcon
            refreshBlackoutAppearance()
            header.addView(bBtn, LinearLayout.LayoutParams(dp(30), dp(26)).apply { rightMargin = dp(5) })

            val (iBtn, iIcon) = buildChromeIconButton(Glyph.KEYBOARD, "键盘", 14f, isDark) { toggleImeBar() }
            imeBtnContainer = iBtn
            imeIconView = iIcon
            refreshImeButtonAppearance()
            header.addView(iBtn, LinearLayout.LayoutParams(dp(30), dp(26)))
        } else {
            blackoutBtnContainer = null
            blackoutIconView = null
            imeBtnContainer = null
            imeIconView = null
            header.addView(
                buildHeaderPill("返回", pillBg, pillBorder, pillInk) {
                    client?.sendSystemAction(OpalCastClient.SYS_BACK)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(5)
                },
            )
            header.addView(buildHeaderPill("键盘", pillBg, pillBorder, pillInk) { toggleImeBar() })
        }

        installDragMoveListener(header)
        container.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        container.addView(
            View(this).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1))),
        )

        // Inline IME bar
        val imeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(imeBarBg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        val edit = EditText(this).apply {
            hint = "输入文字发送到 $appLabel…"
            setHintTextColor(pal.inkMuted)
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
        val sendBtn = OpalCastTheme.buildPillButton(this, "发送", pal.accent, Color.WHITE) { sendImeBarText() }
        imeBar.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        imeBar.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        imeInputBar = imeBar
        container.addView(imeBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Opaque TextureView body
        val body = FrameLayout(this).apply { setBackgroundColor(canvasBg) }
        val tv = TextureView(this).apply {
            isOpaque = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
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
            setOnTouchListener { v, ev ->
                val c = client ?: return@setOnTouchListener true
                if (c.isMouseMotion(ev)) c.sendMouseEvent(ev, v.width, v.height)
                else c.sendTouchEvent(ev, v.width, v.height)
                true
            }
            setOnGenericMotionListener { v, ev ->
                client?.handleGenericMouseMotion(ev, v.width, v.height) ?: false
            }
        }
        textureView = tv
        body.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
        val retryBtn = OpalCastTheme.buildPillButton(this, "重新连接", pal.accent, Color.WHITE) {
            tearDownSessionOnly()
            connectRemoteSession()
        }.apply { visibility = View.GONE }
        overlayBanner = banner
        overlayBannerText = bannerTv
        overlayRetryBtn = retryBtn
        banner.addView(bannerTv)
        banner.addView(retryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        body.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val gripTint = if (isDark) 0x77A7D3CC.toInt() else 0x660F766E.toInt()
        val resizeGrip = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gripTint
                strokeWidth = (1.65f * dm.density).coerceAtLeast(2.0f)
                strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val pad = dp(7).toFloat()
                val w = width.toFloat() - pad
                val h = height.toFloat() - pad
                val span = dp(9).toFloat()
                canvas.drawLine(w - span, h, w, h - span, paint)
                canvas.drawLine(w - span * 0.50f, h, w, h - span * 0.50f, paint)
            }
        }
        installResizeListener(resizeGrip)
        if (!isMirrorMode) {
            body.addView(resizeGrip, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
        }
        container.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (isMirrorMode) {
            container.addView(
                View(this).apply { setBackgroundColor(dividerColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1))),
            )
            val bottomFrame = FrameLayout(this).apply { setBackgroundColor(headerBg) }
            val capsuleBg = if (isDark) 0x16FFFFFF else 0xCCE6F5F3.toInt()
            val capsuleRim = if (isDark) 0x26FFFFFF else 0x220F766E
            val sepColor = if (isDark) 0x1FFFFFFF else 0x1A0F766E
            val navCapsule = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = OpalCastTheme.roundedDrawable(this@FloatingCastService, capsuleBg, 15, capsuleRim, 0.8f)
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            fun addSep() {
                navCapsule.addView(
                    View(this).apply { setBackgroundColor(sepColor) },
                    LinearLayout.LayoutParams(max(1, (0.8f * dm.density).roundToInt()), dp(12)),
                )
            }
            navCapsule.addView(buildDockNavIconButton(Glyph.BACK, "返回", isDark) { client?.sendSystemAction(OpalCastClient.SYS_BACK) }, LinearLayout.LayoutParams(dp(46), dp(26)))
            addSep()
            navCapsule.addView(buildDockNavIconButton(Glyph.HOME, "主页", isDark) { client?.sendSystemAction(OpalCastClient.SYS_HOME) }, LinearLayout.LayoutParams(dp(46), dp(26)))
            addSep()
            navCapsule.addView(buildDockNavIconButton(Glyph.RECENTS, "多任务", isDark) { client?.sendSystemAction(OpalCastClient.SYS_RECENTS) }, LinearLayout.LayoutParams(dp(46), dp(26)))
            bottomFrame.addView(navCapsule, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30), Gravity.CENTER))
            bottomFrame.addView(resizeGrip, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
            container.addView(bottomFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }

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
            Toast.makeText(this, "无法创建悬浮窗：${t.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
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

    private fun buildHeaderPill(label: String, bgColor: Int, borderColor: Int, textColor: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = OpalCastTheme.roundedDrawable(this@FloatingCastService, bgColor, 10, borderColor, 0.7f)
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
                    val clamped = FloatingGeometry.clampDragPosition(
                        startX + (ev.rawX - downRawX).roundToInt(),
                        startY + (ev.rawY - downRawY).roundToInt(),
                        lp.width, dm.widthPixels, dm.heightPixels, dm.density,
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
                    val clamped = if (isMirrorMode) {
                        FloatingGeometry.clampAspectLockedResizeSize(
                            startW + (ev.rawX - downRawX).roundToInt(),
                            startH + (ev.rawY - downRawY).roundToInt(),
                            mirrorVideoW, mirrorVideoH, floatingChromeHeightPx(),
                            dm.widthPixels, dm.heightPixels, dm.density,
                        )
                    } else {
                        FloatingGeometry.clampResizeSize(
                            startW + (ev.rawX - downRawX).roundToInt(),
                            startH + (ev.rawY - downRawY).roundToInt(),
                            dm.widthPixels, dm.heightPixels, dm.density,
                        )
                    }
                    lp.width = clamped.width
                    lp.height = clamped.height
                    expandedW = lp.width
                    expandedH = lp.height
                    safeUpdateViewLayout(rv, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isMirrorMode) commitRemoteResize()
                    true
                }
                else -> false
            }
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
        val btn = blackoutBtnContainer ?: return
        val glyph = blackoutIconView ?: return
        val isDark = OpalCastTheme.resolve(this).isDark
        btn.contentDescription = if (remoteBlackout) "亮屏" else "熄屏"
        if (remoteBlackout) {
            glyph.setIconTint(if (isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt())
            btn.background = OpalCastTheme.roundedDrawable(this, if (isDark) 0x30F59E0B else 0x24F59E0B, 13, 0x7AF59E0B, 0.85f)
        } else {
            glyph.setIconTint(if (isDark) 0xFFA7D3CC.toInt() else 0xFF2D5B5E.toInt())
            btn.background = OpalCastTheme.roundedDrawable(this, if (isDark) 0x18FFFFFF else 0x0F0F766E, 13, if (isDark) 0x26FFFFFF else 0x1A0F766E, 0.7f)
        }
    }

    private fun refreshImeButtonAppearance() {
        val btn = imeBtnContainer ?: return
        val glyph = imeIconView ?: return
        val pal = OpalCastTheme.resolve(this)
        btn.contentDescription = if (isImeBarVisible) "收起键盘" else "键盘"
        if (isImeBarVisible) {
            glyph.setIconTint(pal.accent)
            btn.background = OpalCastTheme.roundedDrawable(this, pal.accentSoft, 13, pal.accentBorder, 0.85f)
        } else {
            glyph.setIconTint(pal.inkSecondary)
            btn.background = OpalCastTheme.roundedDrawable(this, if (pal.isDark) 0x18FFFFFF else 0x0F0F766E, 13, if (pal.isDark) 0x26FFFFFF else 0x1A0F766E, 0.7f)
        }
    }

    private enum class Glyph { BACK, HOME, RECENTS, SCREEN_OFF, KEYBOARD }

    private inner class GlyphIconView(ctx: Context, private val glyph: Glyph, initialTint: Int) : View(ctx) {
        private var tintColor = initialTint
        private val density = resources.displayMetrics.density
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = initialTint
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = initialTint
        }
        private val path = Path()
        private val rect = RectF()

        fun setIconTint(color: Int) {
            if (tintColor != color) {
                tintColor = color
                strokePaint.color = color
                fillPaint.color = color
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val sw = (1.65f * density).coerceAtLeast(1.8f)
            strokePaint.color = tintColor
            strokePaint.strokeWidth = sw
            fillPaint.color = tintColor
            when (glyph) {
                Glyph.BACK -> {
                    path.reset()
                    path.moveTo(w * 0.63f, h * 0.17f)
                    path.lineTo(w * 0.30f, h * 0.50f)
                    path.lineTo(w * 0.63f, h * 0.83f)
                    canvas.drawPath(path, strokePaint)
                }
                Glyph.HOME -> {
                    path.reset()
                    path.moveTo(w * 0.13f, h * 0.48f)
                    path.lineTo(w * 0.50f, h * 0.15f)
                    path.lineTo(w * 0.87f, h * 0.48f)
                    canvas.drawPath(path, strokePaint)
                    val r = 1.8f * density
                    path.reset()
                    path.moveTo(w * 0.24f, h * 0.44f)
                    path.lineTo(w * 0.24f, h * 0.84f - r)
                    path.quadTo(w * 0.24f, h * 0.84f, w * 0.24f + r, h * 0.84f)
                    path.lineTo(w * 0.76f - r, h * 0.84f)
                    path.quadTo(w * 0.76f, h * 0.84f, w * 0.76f, h * 0.84f - r)
                    path.lineTo(w * 0.76f, h * 0.44f)
                    canvas.drawPath(path, strokePaint)
                }
                Glyph.RECENTS -> {
                    val cr = 2.4f * density
                    rect.set(w * 0.30f, h * 0.14f, w * 0.86f, h * 0.68f)
                    canvas.drawRoundRect(rect, cr, cr, strokePaint)
                    rect.set(w * 0.14f, h * 0.31f, w * 0.70f, h * 0.86f)
                    canvas.drawRoundRect(rect, cr, cr, strokePaint)
                }
                Glyph.SCREEN_OFF -> {
                    rect.set(w * 0.16f, h * 0.18f, w * 0.84f, h * 0.86f)
                    canvas.drawArc(rect, -52f, 284f, false, strokePaint)
                    canvas.drawLine(w * 0.50f, h * 0.10f, w * 0.50f, h * 0.47f, strokePaint)
                }
                Glyph.KEYBOARD -> {
                    val cr = 2.0f * density
                    strokePaint.strokeWidth = sw * 0.90f
                    rect.set(w * 0.08f, h * 0.22f, w * 0.92f, h * 0.78f)
                    canvas.drawRoundRect(rect, cr, cr, strokePaint)
                    canvas.drawLine(w * 0.28f, h * 0.62f, w * 0.72f, h * 0.62f, strokePaint)
                    val dotR = (w * 0.052f).coerceAtLeast(1.1f * density)
                    for (gx in floatArrayOf(0.26f, 0.42f, 0.58f, 0.74f)) {
                        canvas.drawCircle(w * gx, h * 0.42f, dotR, fillPaint)
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildChromeIconButton(glyph: Glyph, desc: String, sizeDp: Float, isDark: Boolean, onClick: () -> Unit): Pair<FrameLayout, GlyphIconView> {
        val idleInk = if (isDark) 0xFFA7D3CC.toInt() else 0xFF2D5B5E.toInt()
        val iconPx = (sizeDp * resources.displayMetrics.density).roundToInt()
        val icon = GlyphIconView(this, glyph, idleInk)
        val btn = FrameLayout(this).apply {
            contentDescription = desc
            isClickable = true
            isFocusable = false
            addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        return btn to icon
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildDockNavIconButton(glyph: Glyph, desc: String, isDark: Boolean, onClick: () -> Unit): FrameLayout {
        val idleInk = if (isDark) 0xFFF0FDF9.toInt() else 0xFF082226.toInt()
        val iconPx = (15.5f * resources.displayMetrics.density).roundToInt()
        val icon = GlyphIconView(this, glyph, idleInk)
        return FrameLayout(this).apply {
            contentDescription = desc
            isClickable = true
            isFocusable = false
            addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
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
        val (streamW, streamH, reqDpi) = computeStreamSpecForView(expandedW, expandedH)
        client?.sendResizeConfig(streamW, streamH, reqDpi, 60)
    }

    private fun computeStreamSpecForView(viewW: Int, viewH: Int): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val contentH = max(dp(180), viewH - dp(41))
        val ratio = viewW.toFloat() / contentH.toFloat()
        val targetLong = 1440
        val rawW = if (ratio >= 1f) targetLong else (targetLong * ratio).roundToInt().coerceIn(544, targetLong)
        val rawH = if (ratio >= 1f) (targetLong / ratio).roundToInt().coerceIn(544, targetLong) else targetLong
        val streamW = (rawW and 7.inv()).coerceAtLeast(544)
        val streamH = (rawH and 7.inv()).coerceAtLeast(544)
        val viewWdp = viewW / density
        val viewHdp = contentH / density
        val reqDpi = if (streamH >= streamW) {
            (streamW * 160f / viewWdp.coerceIn(330f, 430f)).roundToInt().coerceIn(240, 480)
        } else {
            (streamH * 160f / viewHdp.coerceIn(340f, 520f)).roundToInt().coerceIn(240, 480)
        }
        return Triple(streamW, streamH, reqDpi)
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
        val initial = if (isMirrorMode) "镜" else appLabel.trim().take(1).ifEmpty { "浮" }
        val pill = TextView(this).apply {
            text = initial
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isMirrorMode) 0xEE0891B2.toInt() else 0xEE0D9488.toInt())
                setStroke(dp(2), 0xAAFFFFFF.toInt())
            }
            elevation = dp(12).toFloat()
        }
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
                        val clamped = FloatingGeometry.clampBubblePosition(
                            startX + dx, startY + dy, dm.widthPixels, dm.heightPixels, dm.density,
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
        statusDotView?.background = circleDrawable(dotColor)
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

    private fun startForegroundNotification(appName: String, peerName: String, isMirror: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        AndroidApiCompat.ensureNotificationChannel(nm, CHANNEL_ID, "OpalCast 远程悬浮窗", "跨设备悬浮小窗与主屏镜像投屏通道")
        val immFlag = AndroidApiCompat.pendingIntentImmutableFlag()
        val openHome = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CastHomeActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or immFlag,
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingCastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or immFlag,
        )
        val title = if (isMirror) "主屏镜像悬浮窗 · $peerName" else "远程应用悬浮窗 · $appName"
        val body = if (isMirror) "正在以悬浮小窗实时镜像 $peerName 主屏" else "正在流式运行来自 $peerName 的 $appName"
        val builder = AndroidApiCompat.createNotificationBuilder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opalcast)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(openHome)
        AndroidApiCompat.addNotificationAction(
            this,
            builder,
            android.R.drawable.ic_menu_close_clear_cancel,
            "关闭小窗",
            stopPending,
        )
        val notif = builder.build()
        AndroidApiCompat.startForegroundCompat(this, NOTIF_ID, notif)
    }

    private fun stopForegroundAndSelf() {
        runCatching { AndroidApiCompat.stopForegroundRemoveCompat(this) }
        stopSelf()
    }

    private fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(value: Int): Int = OpalCastTheme.dp(this, value)
}
