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
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
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
                putExtra(EXTRA_IS_MIRROR, false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.applicationContext.startForegroundService(intent)
            } else {
                ctx.applicationContext.startService(intent)
            }
            return true
        }

        fun startMirror(
            ctx: Context,
            host: String,
            port: Int,
            peerLabel: String,
            handoverToken: String = "",
        ): Boolean {
            if (!Settings.canDrawOverlays(ctx)) return false
            val displayPeer = peerLabel.ifBlank { host }
            val intent = Intent(ctx.applicationContext, PeerFloatingAppService::class.java).apply {
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
        val nextPort = (intent?.getIntExtra(EXTRA_PORT, 19878) ?: 19878).coerceIn(1, 65535)
        val nextIsMirror = intent?.getBooleanExtra(EXTRA_IS_MIRROR, false) == true
        val nextPkg = intent?.getStringExtra(EXTRA_APP_PACKAGE).orEmpty().trim()
            .ifBlank { if (nextIsMirror) MIRROR_SENTINEL_PKG else "" }
        val nextCls = if (nextIsMirror) "" else intent?.getStringExtra(EXTRA_APP_ACTIVITY).orEmpty().trim()
        val nextPeerLabel = intent?.getStringExtra(EXTRA_PEER_LABEL).orEmpty().ifBlank { nextHost }
        val nextAppLabel = intent?.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank {
            if (nextIsMirror) "$nextPeerLabel · 主屏镜像" else nextPkg
        }
        val nextHandover = intent?.getStringExtra(EXTRA_HANDOVER_TOKEN).orEmpty().trim()

        // Always satisfy startForegroundService() contract before any early validation stopSelf().
        startForegroundNotification(
            nextAppLabel.ifBlank { if (nextIsMirror) "主屏镜像" else "远程应用" },
            nextPeerLabel.ifBlank { "远端设备" },
            nextIsMirror,
        )

        if (!Settings.canDrawOverlays(this)) {
            OpalLogStore.w(TAG, "Aborting start: SYSTEM_ALERT_WINDOW permission not granted")
            Toast.makeText(this, "请先授予 OpalDesk 悬浮窗权限", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, check.reason.ifBlank { "当前不可启动远程悬浮窗" }, Toast.LENGTH_LONG).show()
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
        val dm = resources.displayMetrics
        val (streamW, streamH, reqDpi) = if (isMirrorMode) {
            val maxEdge = max(dm.widthPixels, dm.heightPixels).coerceIn(1280, 2400)
            Triple(maxEdge, (maxEdge * 10 / 16).coerceAtLeast(720), dm.densityDpi.coerceIn(160, 480))
        } else {
            computeStreamSpecForView(expandedW, expandedH)
        }
        val savedTok = AppPrefs.getPeerAuthToken(this, host)
        val tokenToUse = savedTok.ifBlank { handoverToken }
        handoverToken = "" // single-use in memory only

        val boundNet = if (AppPrefs.isUsbTetherCastEnabled(this)) {
            UsbTetherPeerDetector.resolveBoundNetworkForHost(host)
        } else {
            null
        }
        val wifiLock = wifiLowLatencyLock ?: WifiLowLatencyLock(this, "OpalDesk:FloatingCastRx").also {
            wifiLowLatencyLock = it
        }
        if (boundNet == null) {
            wifiLock.acquire()
        } else {
            wifiLock.release()
        }
        val peerClient = OpalCastPeerClient(
            host = host,
            port = port,
            savedAuthToken = tokenToUse,
            clientId = AppPrefs.getOrCreatePeerClientId(this),
            reqWidth = streamW,
            reqHeight = streamH,
            reqDpi = reqDpi,
            reqFps = if (isMirrorMode) resolveDefaultDisplayRefreshRate() else 60,
            sessionRole = if (isMirrorMode) {
                OpalCastPeerClient.SESSION_ROLE_DESKTOP
            } else {
                OpalCastPeerClient.SESSION_ROLE_STANDALONE_APP
            },
            appPackage = if (isMirrorMode) "" else appPackage,
            appActivity = if (isMirrorMode) "" else appActivity,
            targetNetwork = boundNet,
            audioEnabled = isMirrorMode,
            initialDisplayMode = if (isMirrorMode) {
                OpalCastPeerClient.DISPLAY_MODE_MIRROR
            } else {
                OpalCastPeerClient.DISPLAY_MODE_DEX
            },
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
                        if (isMirrorMode) {
                            appLabel = "$peerLabel · 主屏镜像"
                            titleTextView?.text = peerLabel
                        }
                        _activeFlow.value = ActiveFloatingInfo(
                            host, port, peerLabel, appPackage, appLabel, isMirror = isMirrorMode,
                        )
                        val startHint = if (isMirrorMode) "正在镜像 $peerLabel 主屏…" else "正在启动 $appLabel…"
                        updateOverlayStatus(startHint, dotColor = 0xFF10B981.toInt(), showRetry = false)
                    }
                }

                override fun onRemoteBlackoutChanged(blackout: Boolean) {
                    if (!isMirrorMode) return
                    mainHandler.post {
                        remoteBlackout = blackout
                        refreshBlackoutPillAppearance()
                    }
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) {
                    mainHandler.post {
                        val safeW = width.coerceAtLeast(2)
                        val safeH = height.coerceAtLeast(2)
                        try {
                            textureView?.surfaceTexture?.setDefaultBufferSize(safeW, safeH)
                        } catch (_: Throwable) {
                        }
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
                    OpalLogStore.w(TAG, "Remote floating session error (mirror=$isMirrorMode pkg=$appPackage): $message")
                    mainHandler.post {
                        updateOverlayStatus(message, dotColor = 0xFFEF4444.toInt(), showRetry = true)
                    }
                }

                override fun onDisconnected(reason: String) {
                    OpalLogStore.i(TAG, "Remote floating session disconnected (mirror=$isMirrorMode pkg=$appPackage): $reason")
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
        activeSurface?.let { s ->
            if (s.isValid) peerClient.attachSurface(s)
        }
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
            FloatingOverlayGeometry.computeAspectMatchedWindowSize(
                videoW = mirrorVideoW,
                videoH = mirrorVideoH,
                chromeHeightPx = floatingChromeHeightPx(),
                screenW = dm.widthPixels,
                screenH = dm.heightPixels,
                density = dm.density,
            )
        } else {
            FloatingOverlayGeometry.computeWindowSize(
                screenW = dm.widthPixels,
                screenH = dm.heightPixels,
                density = dm.density,
                isLandscape = isLandscape,
            )
        }
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
            val padStart = if (isMirrorMode) dp(10) else dp(12)
            val padEnd = if (isMirrorMode) dp(8) else dp(10)
            setPadding(padStart, dp(6), padEnd, dp(6))
        }

        // macOS Traffic Lights: Red = Close, Yellow = Collapse to Bubble, Green = Expand / Rotate
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

        val dot = View(this).apply {
            background = circleDrawable(0xFFF59E0B.toInt())
        }
        statusDotView = dot
        header.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
            rightMargin = if (isMirrorMode) dp(4) else dp(6)
        })

        val titleTv = TextView(this).apply {
            text = if (isMirrorMode) peerLabel else appLabel
            setTextColor(titleInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isMirrorMode) 12f else 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleTextView = titleTv
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4)
        })

        if (isMirrorMode) {
            val (blackoutBtn, blackoutGlyph) = buildChromeIconButton(
                glyph = MirrorGlyph.SCREEN_OFF,
                contentDesc = if (remoteBlackout) "亮屏" else "熄屏",
                iconSizeDp = 13.5f,
                isDark = isDark,
            ) {
                val next = !remoteBlackout
                remoteBlackout = next
                refreshBlackoutPillAppearance()
                client?.sendSystemAction(OpalCastPeerClient.SYS_TOGGLE_BLACKOUT, if (next) 1 else 0)
            }
            blackoutBtnContainer = blackoutBtn
            blackoutIconView = blackoutGlyph
            refreshBlackoutPillAppearance()
            header.addView(
                blackoutBtn,
                LinearLayout.LayoutParams(dp(30), dp(26)).apply {
                    rightMargin = dp(5)
                },
            )

            val (imeBtn, imeGlyph) = buildChromeIconButton(
                glyph = MirrorGlyph.KEYBOARD,
                contentDesc = "键盘",
                iconSizeDp = 14f,
                isDark = isDark,
            ) {
                toggleImeBar()
            }
            imeBtnContainer = imeBtn
            imeIconView = imeGlyph
            refreshImeButtonAppearance()
            header.addView(imeBtn, LinearLayout.LayoutParams(dp(30), dp(26)))
        } else {
            blackoutBtnContainer = null
            blackoutIconView = null
            imeBtnContainer = null
            imeIconView = null
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
        }

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
                connectRemoteSession()
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
        val gripTint = if (isDark) 0x77CBD5E1.toInt() else 0x66475569.toInt()
        val resizeGrip = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gripTint
                strokeWidth = (1.65f * resources.displayMetrics.density).coerceAtLeast(2.0f)
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
            body.addView(
                resizeGrip,
                FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END),
            )
        }

        container.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (isMirrorMode) {
            // Refined symmetric macOS/Liquid-Glass bottom navigation bezel with vector icons (Back / Home / Recents)
            container.addView(
                View(this).apply { setBackgroundColor(dividerColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1))),
            )
            val bottomFrame = FrameLayout(this).apply {
                setBackgroundColor(headerBg)
            }
            val capsuleBg = if (isDark) 0x16FFFFFF else 0xCCECF1F9.toInt()
            val capsuleRim = if (isDark) 0x26FFFFFF else 0x200F172A
            val sepColor = if (isDark) 0x1FFFFFFF else 0x1A0F172A

            val navCapsule = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(capsuleBg)
                    cornerRadius = dp(15).toFloat()
                    setStroke(max(1, (0.8f * resources.displayMetrics.density).roundToInt()), capsuleRim)
                }
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }

            fun addNavDivider() {
                navCapsule.addView(
                    View(this).apply { setBackgroundColor(sepColor) },
                    LinearLayout.LayoutParams(max(1, (0.8f * resources.displayMetrics.density).roundToInt()), dp(12)),
                )
            }

            navCapsule.addView(
                buildDockNavIconButton(MirrorGlyph.BACK, "返回", isDark) {
                    client?.sendSystemAction(OpalCastPeerClient.SYS_BACK)
                },
                LinearLayout.LayoutParams(dp(46), dp(26)),
            )
            addNavDivider()
            navCapsule.addView(
                buildDockNavIconButton(MirrorGlyph.HOME, "主页", isDark) {
                    client?.sendSystemAction(OpalCastPeerClient.SYS_HOME)
                },
                LinearLayout.LayoutParams(dp(46), dp(26)),
            )
            addNavDivider()
            navCapsule.addView(
                buildDockNavIconButton(MirrorGlyph.RECENTS, "多任务", isDark) {
                    client?.sendSystemAction(OpalCastPeerClient.SYS_RECENTS)
                },
                LinearLayout.LayoutParams(dp(46), dp(26)),
            )

            bottomFrame.addView(
                navCapsule,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(30),
                    Gravity.CENTER,
                ),
            )
            bottomFrame.addView(
                resizeGrip,
                FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END),
            )
            container.addView(
                bottomFrame,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)),
            )
        }

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
                    val clamped = if (isMirrorMode) {
                        FloatingOverlayGeometry.clampAspectLockedResizeSize(
                            targetW = startW + (ev.rawX - downRawX).roundToInt(),
                            targetH = startH + (ev.rawY - downRawY).roundToInt(),
                            videoW = mirrorVideoW,
                            videoH = mirrorVideoH,
                            chromeHeightPx = floatingChromeHeightPx(),
                            screenW = dm.widthPixels,
                            screenH = dm.heightPixels,
                            density = dm.density,
                        )
                    } else {
                        FloatingOverlayGeometry.clampResizeSize(
                            targetW = startW + (ev.rawX - downRawX).roundToInt(),
                            targetH = startH + (ev.rawY - downRawY).roundToInt(),
                            screenW = dm.widthPixels,
                            screenH = dm.heightPixels,
                            density = dm.density,
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
                    if (!isMirrorMode) {
                        commitRemoteResize()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun resolveDefaultDisplayRefreshRate(): Int {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            (defaultDisplay?.mode?.refreshRate?.roundToInt() ?: 60).coerceIn(60, 120)
        } catch (_: Throwable) {
            60
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
        val matched = FloatingOverlayGeometry.computeAspectMatchedWindowSize(
            videoW = videoW,
            videoH = videoH,
            chromeHeightPx = floatingChromeHeightPx(),
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            density = dm.density,
        )
        expandedW = matched.width
        expandedH = matched.height
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
    }

    private fun expandMirrorToFullscreen() {
        val targetHost = host
        val targetPort = port
        val label = peerLabel.ifBlank { host }
        tearDownSessionOnly()
        val intent = Intent(this, PeerCastViewerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(PeerCastViewerActivity.EXTRA_HOST, targetHost)
            putExtra(PeerCastViewerActivity.EXTRA_PORT, targetPort)
            putExtra(PeerCastViewerActivity.EXTRA_LABEL, label)
            putExtra(PeerCastViewerActivity.EXTRA_INITIAL_DISPLAY_MODE, OpalCastPeerClient.DISPLAY_MODE_MIRROR)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "Failed to expand mirror to fullscreen: ${t.message}")
        }
        stopSelf()
    }

    private fun refreshBlackoutPillAppearance() {
        val btn = blackoutBtnContainer ?: return
        val glyph = blackoutIconView ?: return
        val isDark = resolveFloatingDarkTheme()
        btn.contentDescription = if (remoteBlackout) "亮屏" else "熄屏"
        if (remoteBlackout) {
            val activeInk = if (isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
            glyph.setIconTint(activeInk)
            btn.background = GradientDrawable().apply {
                setColor(if (isDark) 0x30F59E0B else 0x24F59E0B)
                cornerRadius = dp(13).toFloat()
                setStroke(max(1, (0.85f * resources.displayMetrics.density).roundToInt()), 0x7AF59E0B)
            }
        } else {
            val idleBg = if (isDark) 0x18FFFFFF else 0x0F0F172A
            val idleRim = if (isDark) 0x26FFFFFF else 0x1A0F172A
            val idleInk = if (isDark) 0xFFD8E1F0.toInt() else 0xFF334155.toInt()
            glyph.setIconTint(idleInk)
            btn.background = GradientDrawable().apply {
                setColor(idleBg)
                cornerRadius = dp(13).toFloat()
                setStroke(max(1, (0.7f * resources.displayMetrics.density).roundToInt()), idleRim)
            }
        }
    }

    private fun refreshImeButtonAppearance() {
        val btn = imeBtnContainer ?: return
        val glyph = imeIconView ?: return
        val isDark = resolveFloatingDarkTheme()
        btn.contentDescription = if (isImeBarVisible) "收起键盘" else "键盘"
        if (isImeBarVisible) {
            val activeInk = if (isDark) 0xFF60A5FA.toInt() else 0xFF007AFF.toInt()
            glyph.setIconTint(activeInk)
            btn.background = GradientDrawable().apply {
                setColor(if (isDark) 0x303B82F6 else 0x1E007AFF)
                cornerRadius = dp(13).toFloat()
                setStroke(max(1, (0.85f * resources.displayMetrics.density).roundToInt()), 0x6E007AFF)
            }
        } else {
            val idleBg = if (isDark) 0x18FFFFFF else 0x0F0F172A
            val idleRim = if (isDark) 0x26FFFFFF else 0x1A0F172A
            val idleInk = if (isDark) 0xFFD8E1F0.toInt() else 0xFF334155.toInt()
            glyph.setIconTint(idleInk)
            btn.background = GradientDrawable().apply {
                setColor(idleBg)
                cornerRadius = dp(13).toFloat()
                setStroke(max(1, (0.7f * resources.displayMetrics.density).roundToInt()), idleRim)
            }
        }
    }

    private enum class MirrorGlyph { BACK, HOME, RECENTS, SCREEN_OFF, KEYBOARD }

    private inner class MirrorGlyphIconView(
        context: Context,
        private val glyph: MirrorGlyph,
        initialTint: Int,
    ) : View(context) {
        private var tintColor: Int = initialTint
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
        private val tmpPath = Path()
        private val tmpRect = RectF()

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
                MirrorGlyph.BACK -> {
                    // Optically centered SF-style chevron (<)
                    tmpPath.reset()
                    tmpPath.moveTo(w * 0.63f, h * 0.17f)
                    tmpPath.lineTo(w * 0.30f, h * 0.50f)
                    tmpPath.lineTo(w * 0.63f, h * 0.83f)
                    canvas.drawPath(tmpPath, strokePaint)
                }
                MirrorGlyph.HOME -> {
                    // Refined minimal house silhouette matching OpalPrimitives ChromeGlyph.HOME_HOUSE
                    tmpPath.reset()
                    tmpPath.moveTo(w * 0.13f, h * 0.48f)
                    tmpPath.lineTo(w * 0.50f, h * 0.15f)
                    tmpPath.lineTo(w * 0.87f, h * 0.48f)
                    canvas.drawPath(tmpPath, strokePaint)

                    val r = 1.8f * density
                    tmpPath.reset()
                    tmpPath.moveTo(w * 0.24f, h * 0.44f)
                    tmpPath.lineTo(w * 0.24f, h * 0.84f - r)
                    tmpPath.quadTo(w * 0.24f, h * 0.84f, w * 0.24f + r, h * 0.84f)
                    tmpPath.lineTo(w * 0.76f - r, h * 0.84f)
                    tmpPath.quadTo(w * 0.76f, h * 0.84f, w * 0.76f, h * 0.84f - r)
                    tmpPath.lineTo(w * 0.76f, h * 0.44f)
                    canvas.drawPath(tmpPath, strokePaint)
                }
                MirrorGlyph.RECENTS -> {
                    // Layered app-switcher cards (inspired by iPadOS Stage Manager / SF square.on.square)
                    val cr = 2.4f * density
                    // Trailing background card peek at muted alpha for depth
                    val backAlpha = ((Color.alpha(tintColor) * 0.62f).roundToInt()).coerceIn(0, 255)
                    strokePaint.color = Color.argb(backAlpha, Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor))
                    strokePaint.strokeWidth = sw * 0.90f
                    tmpPath.reset()
                    tmpPath.moveTo(w * 0.33f, h * 0.27f)
                    tmpPath.lineTo(w * 0.33f, h * 0.14f + cr)
                    tmpPath.quadTo(w * 0.33f, h * 0.14f, w * 0.33f + cr, h * 0.14f)
                    tmpPath.lineTo(w * 0.86f - cr, h * 0.14f)
                    tmpPath.quadTo(w * 0.86f, h * 0.14f, w * 0.86f, h * 0.14f + cr)
                    tmpPath.lineTo(w * 0.86f, h * 0.67f - cr)
                    tmpPath.quadTo(w * 0.86f, h * 0.67f, w * 0.86f - cr, h * 0.67f)
                    tmpPath.lineTo(w * 0.72f, h * 0.67f)
                    canvas.drawPath(tmpPath, strokePaint)

                    // Foreground crisp card
                    strokePaint.color = tintColor
                    strokePaint.strokeWidth = sw
                    tmpRect.set(w * 0.14f, h * 0.31f, w * 0.69f, h * 0.86f)
                    canvas.drawRoundRect(tmpRect, cr, cr, strokePaint)
                }
                MirrorGlyph.SCREEN_OFF -> {
                    // Universal power / screen-sleep glyph (open top arc + stem)
                    tmpRect.set(w * 0.16f, h * 0.18f, w * 0.84f, h * 0.86f)
                    canvas.drawArc(tmpRect, -52f, 284f, false, strokePaint)
                    canvas.drawLine(w * 0.50f, h * 0.10f, w * 0.50f, h * 0.47f, strokePaint)
                }
                MirrorGlyph.KEYBOARD -> {
                    val cr = 2.0f * density
                    strokePaint.strokeWidth = sw * 0.90f
                    tmpRect.set(w * 0.08f, h * 0.22f, w * 0.92f, h * 0.78f)
                    canvas.drawRoundRect(tmpRect, cr, cr, strokePaint)
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
    private fun buildChromeIconButton(
        glyph: MirrorGlyph,
        contentDesc: String,
        iconSizeDp: Float,
        isDark: Boolean,
        onClick: () -> Unit,
    ): Pair<FrameLayout, MirrorGlyphIconView> {
        val idleInk = if (isDark) 0xFFD8E1F0.toInt() else 0xFF334155.toInt()
        val iconPx = (iconSizeDp * resources.displayMetrics.density).roundToInt()
        val icon = MirrorGlyphIconView(this, glyph, idleInk)
        val btn = FrameLayout(this).apply {
            contentDescription = contentDesc
            isClickable = true
            isFocusable = false
            addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(75L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    }
                }
                false
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        return btn to icon
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildDockNavIconButton(
        glyph: MirrorGlyph,
        contentDesc: String,
        isDark: Boolean,
        onClick: () -> Unit,
    ): FrameLayout {
        val idleInk = if (isDark) 0xFFE2E8F0.toInt() else 0xFF1E293B.toInt()
        val pressedInk = if (isDark) 0xFF60A5FA.toInt() else 0xFF007AFF.toInt()
        val pressedBg = GradientDrawable().apply {
            setColor(if (isDark) 0x2660A5FA else 0x1C007AFF)
            cornerRadius = dp(12).toFloat()
        }
        val iconPx = (15.5f * resources.displayMetrics.density).roundToInt()
        val icon = MirrorGlyphIconView(this, glyph, idleInk)
        return FrameLayout(this).apply {
            contentDescription = contentDesc
            isClickable = true
            isFocusable = false
            addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = pressedBg
                        icon.setIconTint(pressedInk)
                        icon.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.background = null
                        icon.setIconTint(idleInk)
                        icon.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                    }
                }
                false
            }
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
        client?.sendResizeConfig(streamW, streamH, reqDpi, 60)
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
                setColor(if (isMirrorMode) 0xEE0891B2.toInt() else 0xEE1D4ED8.toInt())
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

    private fun startForegroundNotification(appTitle: String, hostName: String, mirror: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "远程应用悬浮窗",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持远程单应用与主屏镜像悬浮窗在应用外运行"
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

        val contentText = if (mirror) {
            "正在以悬浮窗镜像显示 $hostName 主屏"
        } else {
            "正在流式运行来自 $hostName 的应用"
        }
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opaldesk)
            .setContentTitle("悬浮窗：$appTitle")
            .setContentText(contentText)
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
