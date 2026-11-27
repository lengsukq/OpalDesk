package io.github.lengsukq.opalcast.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.media.CastCameraCapturer
import io.github.lengsukq.opalcast.media.CastMicCapturer
import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.net.UsbTetherDiscovery
import io.github.lengsukq.opalcast.net.WifiLowLatencyLock
import io.github.lengsukq.opalcast.policy.CameraGeometry
import io.github.lengsukq.opalcast.policy.InputGuardPolicy
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.prefs.ReceiverPrefs
import kotlin.math.roundToInt

/**
 * Fullscreen hardware-accelerated OpalCast v1.1 Receiver Workstation (Android 5.0–17, API 21–37).
 * Zero AndroidX / Compose dependencies for instant cold start and zero GC churn at 60–120fps.
 */
class CastViewerActivity : Activity() {
    companion object {
        const val EXTRA_HOST = "peer_host"
        const val EXTRA_PORT = "peer_port"
        const val EXTRA_LABEL = "peer_label"
        const val EXTRA_INITIAL_DISPLAY_MODE = "initial_display_mode"
        private const val REQ_MIC = 101
        private const val REQ_CAM = 102
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: OpalCastClient? = null
    private var wifiLowLatencyLock: WifiLowLatencyLock? = null
    private var micCapturer: CastMicCapturer? = null
    private var cameraCapturer: CastCameraCapturer? = null
    private var activeSurface: Surface? = null
    private var backInvokedCallback: Any? = null

    private var targetHost = ""
    private var targetPort = ReceiverPolicy.DEFAULT_CAST_PORT
    private var targetLabel = ""
    private var initialDisplayMode = OpalCastClient.DISPLAY_MODE_DEX
    private var reqWidth = 1920
    private var reqHeight = 1080
    private var reqDpi = 240
    private var reqFps = 60

    private var peerName = ""
    private var connected = false
    private var videoW = 1920
    private var videoH = 1080
    private var videoFps = 60
    private var displayMode = OpalCastClient.DISPLAY_MODE_DEX
    private var remoteBlackout = false
    private var remoteFullscreenTitle = ""
    private var micEnabled = false
    private var cameraEnabled = false
    private var cameraFacing = "front"
    private var toolbarExpanded = true
    private var remoteApps: List<OpalCastClient.RemoteAppEntry> = emptyList()

    private lateinit var rootFrame: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var imeBridgeView: CastImeBridgeView
    private lateinit var collapsedHandle: TextView
    private lateinit var expandedToolbar: HorizontalScrollView
    private lateinit var statusLabel: TextView
    private lateinit var modeBtn: TextView
    private lateinit var floatMirrorBtn: TextView
    private lateinit var blackoutBtn: TextView
    private lateinit var imeBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var camBtn: TextView
    private lateinit var flipCamBtn: TextView
    private lateinit var fullscreenExitChip: TextView
    private lateinit var composingPill: TextView
    private lateinit var overlayCard: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySubtitle: TextView
    private lateinit var overlayRetryBtn: TextView

    private val autoCollapseRunnable = Runnable {
        if (connected && toolbarExpanded) {
            setToolbarExpanded(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        targetHost = intent.getStringExtra(EXTRA_HOST).orEmpty().trim()
        targetPort = intent.getIntExtra(EXTRA_PORT, ReceiverPolicy.DEFAULT_CAST_PORT).coerceIn(1, 65535)
        targetLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { targetHost }
        initialDisplayMode = if (intent.getIntExtra(EXTRA_INITIAL_DISPLAY_MODE, OpalCastClient.DISPLAY_MODE_DEX) == OpalCastClient.DISPLAY_MODE_MIRROR) {
            OpalCastClient.DISPLAY_MODE_MIRROR
        } else {
            OpalCastClient.DISPLAY_MODE_DEX
        }
        if (targetHost.isEmpty()) {
            finish()
            return
        }
        displayMode = initialDisplayMode
        peerName = targetLabel
        ReceiverPrefs.recordRecentPeer(this, targetHost, targetPort, targetLabel)

        val dm = resources.displayMetrics
        val (rawW, rawH) = AndroidApiCompat.resolveWindowBoundsPx(this)
        val activeDisplay = AndroidApiCompat.resolveActivityDisplay(this)
        val spec = ReceiverPolicy.computePeerCastRequestSpec(
            displayW = rawW,
            displayH = rawH,
            densityDpi = dm.densityDpi,
            refreshRateHz = AndroidApiCompat.resolveDisplayRefreshRateHz(activeDisplay),
            sdkInt = Build.VERSION.SDK_INT,
        )
        reqWidth = spec.width
        reqHeight = spec.height
        reqDpi = spec.dpi
        reqFps = spec.fps
        videoW = reqWidth
        videoH = reqHeight

        buildUi()
        registerBackHandlerApi33()
        AndroidApiCompat.applyPeakRefreshAndImmersive(this)
        startPeerSession()
    }

    private fun registerBackHandlerApi33() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val cb = OnBackInvokedCallback {
            if (connected) {
                client?.sendSystemAction(OpalCastClient.SYS_BACK)
            } else {
                finish()
            }
        }
        backInvokedCallback = cb
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) AndroidApiCompat.applyPeakRefreshAndImmersive(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rootFrame.post { updateSurfaceAspectBounds() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val pal = OpalCastTheme.resolve(this)
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFF040A0E.toInt())
        }

        // Aspect-ratio fitted hardware SurfaceView
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    activeSurface = holder.surface
                    client?.attachSurface(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    activeSurface = holder.surface
                    client?.attachSurface(holder.surface)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    activeSurface = null
                    client?.attachSurface(null)
                }
            })
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
        rootFrame.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        rootFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceAspectBounds() }

        // Invisible 1px native IME host
        imeBridgeView = CastImeBridgeView(
            ctx = this,
            onSendKey = { action, code, meta -> client?.sendKeyEvent(action, code, meta, 0) },
            onCommitText = { text -> client?.sendTextEvent(text, commitPaste = true) },
            onComposingChanged = { comp ->
                runOnUiThread {
                    composingPill.text = comp
                    composingPill.visibility = if (comp.isNotBlank()) View.VISIBLE else View.GONE
                }
            },
            onImeVisibilityChanged = { visible ->
                runOnUiThread { refreshToolbarStates(imeVis = visible) }
            },
        )
        rootFrame.addView(imeBridgeView, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.START))

        // Collapsed top pill handle
        collapsedHandle = TextView(this).apply {
            text = "OpalCast · 工具栏"
            setTextColor(0xE6E0FBF5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = OpalCastTheme.roundedDrawable(this@CastViewerActivity, 0xCC091820.toInt(), 99, 0x552DD4BF, 1f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            visibility = View.GONE
            setOnClickListener { setToolbarExpanded(true) }
        }
        rootFrame.addView(
            collapsedHandle,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(10)
            },
        )

        // Expanded frosted Liquid Glass top toolbar
        val barInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastViewerActivity, 0xE60B1D26.toInt(), 18, 0x552DD4BF, 1f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        statusLabel = TextView(this).apply {
            text = peerName
            setTextColor(0xFFF0FDF9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(8), 0)
        }
        barInner.addView(statusLabel)

        fun addToolChip(label: String, tintBg: Int = 0x1FFFFFFF, tintText: Int = 0xFFE6FFFA.toInt(), onClick: () -> Unit): TextView {
            val tv = TextView(this).apply {
                text = label
                setTextColor(tintText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = OpalCastTheme.roundedDrawable(this@CastViewerActivity, tintBg, 10, 0x2EFFFFFF, 0.8f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    scheduleAutoCollapse(5_000L)
                    onClick()
                }
            }
            barInner.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6)
            })
            return tv
        }

        addToolChip("返回") { client?.sendSystemAction(OpalCastClient.SYS_BACK) }
        addToolChip("主页") { client?.sendSystemAction(OpalCastClient.SYS_HOME) }
        modeBtn = addToolChip("切镜像") { toggleDisplayMode() }
        floatMirrorBtn = addToolChip("悬浮小窗") { popOutMirrorToFloating() }
        blackoutBtn = addToolChip("熄屏") { toggleBlackout() }
        imeBtn = addToolChip("键盘") { imeBridgeView.toggleIme() }
        micBtn = addToolChip("麦克风") { toggleRemoteMic() }
        camBtn = addToolChip("摄像头") { toggleRemoteCamera() }
        flipCamBtn = addToolChip("切镜头") { flipRemoteCamera() }.apply { visibility = View.GONE }
        addToolChip("应用小窗") {
            setToolbarExpanded(false)
            client?.requestAppList()
            RemoteAppPickerDialog(
                activity = this,
                host = targetHost,
                port = targetPort,
                peerLabel = peerName,
                preloadedApps = remoteApps.takeIf { it.isNotEmpty() },
            ).show()
        }
        addToolChip("刷新") { client?.requestKeyFrame() }
        addToolChip("收起") { setToolbarExpanded(false) }
        addToolChip("断开", tintBg = 0x38EF4444, tintText = 0xFFFCA5A5.toInt()) { finish() }

        expandedToolbar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(barInner)
        }
        rootFrame.addView(
            expandedToolbar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(10)
                leftMargin = dp(12)
                rightMargin = dp(12)
            },
        )

        // Remote fullscreen exit helper chip
        fullscreenExitChip = TextView(this).apply {
            text = "退出全屏"
            setTextColor(0xFFF0FDF9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = OpalCastTheme.roundedDrawable(this@CastViewerActivity, 0xD90D9488.toInt(), 99, 0x66FFFFFF, 1f)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            visibility = View.GONE
            setOnClickListener {
                remoteFullscreenTitle = ""
                visibility = View.GONE
                client?.sendSystemAction(OpalCastClient.SYS_HOME)
            }
        }
        rootFrame.addView(
            fullscreenExitChip,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(60)
                rightMargin = dp(14)
            },
        )

        // CJK IME composing preview pill
        composingPill = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = OpalCastTheme.roundedDrawable(this@CastViewerActivity, 0xE60D9488.toInt(), 12, 0x66FFFFFF, 1f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        rootFrame.addView(
            composingPill,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(18)
            },
        )

        // Connection / approval / error overlay card
        overlayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = OpalCastTheme.roundedDrawable(this@CastViewerActivity, 0xEB0B1C24.toInt(), 20, pal.borderStrong, 1.1f)
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
        overlayTitle = TextView(this).apply {
            text = peerName
            setTextColor(0xFFF0FDF9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        overlaySubtitle = TextView(this).apply {
            text = "正在建立 TLS 加密连接…"
            setTextColor(0xFFA7D3CC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        val overlayBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        overlayRetryBtn = OpalCastTheme.buildPillButton(this, "重新连接", pal.accent, Color.WHITE) {
            startPeerSession()
        }.apply { visibility = View.GONE }
        val backBtn = OpalCastTheme.buildPillButton(this, "返回主页", 0x22FFFFFF, 0xFFE6FFFA.toInt(), 0x33FFFFFF) {
            finish()
        }
        overlayBtnRow.addView(overlayRetryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(10)
        })
        overlayBtnRow.addView(backBtn)

        overlayCard.addView(overlayTitle)
        overlayCard.addView(overlaySubtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(16)
        })
        overlayCard.addView(overlayBtnRow)
        rootFrame.addView(
            overlayCard,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        setContentView(rootFrame)
        AndroidApiCompat.observeTopCutoutInset(rootFrame) { cutoutTop ->
            val topMargin = maxOf(dp(14), cutoutTop + dp(8))
            (collapsedHandle.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != topMargin) {
                    lp.topMargin = topMargin
                    collapsedHandle.layoutParams = lp
                }
            }
            (expandedToolbar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != topMargin) {
                    lp.topMargin = topMargin
                    expandedToolbar.layoutParams = lp
                }
            }
            (fullscreenExitChip.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val chipTop = topMargin + dp(48)
                if (lp.topMargin != chipTop) {
                    lp.topMargin = chipTop
                    fullscreenExitChip.layoutParams = lp
                }
            }
        }
        refreshToolbarStates(imeVis = false)
    }

    private fun updateSurfaceAspectBounds() {
        val parentW = rootFrame.width
        val parentH = rootFrame.height
        if (parentW <= 0 || parentH <= 0 || videoW <= 0 || videoH <= 0) return
        val videoAspect = (videoW.toFloat() / videoH.toFloat()).coerceIn(0.35f, 3.0f)
        val parentAspect = parentW.toFloat() / parentH.toFloat()
        val targetW: Int
        val targetH: Int
        if (videoAspect > parentAspect) {
            targetW = parentW
            targetH = (parentW / videoAspect).roundToInt().coerceIn(2, parentH)
        } else {
            targetH = parentH
            targetW = (parentH * videoAspect).roundToInt().coerceIn(2, parentW)
        }
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW
            lp.height = targetH
            surfaceView.layoutParams = lp
        }
    }

    private fun startPeerSession() {
        client?.stop()
        client = null
        connected = false
        displayMode = initialDisplayMode
        remoteBlackout = false
        overlayTitle.text = peerName
        overlaySubtitle.text = "正在建立 TLS 加密连接…"
        overlayRetryBtn.visibility = View.GONE
        overlayCard.visibility = View.VISIBLE

        val boundNet = if (ReceiverPrefs.isUsbTetherEnabled(this)) {
            UsbTetherDiscovery.resolveBoundNetworkForHost(targetHost)
        } else {
            null
        }
        val wifiLock = wifiLowLatencyLock ?: WifiLowLatencyLock(this, "OpalCast:ViewerRx").also {
            wifiLowLatencyLock = it
        }
        if (boundNet == null) wifiLock.acquire() else wifiLock.release()

        val peerClient = OpalCastClient(
            host = targetHost,
            port = targetPort,
            savedAuthToken = ReceiverPrefs.getPeerAuthToken(this, targetHost),
            clientId = ReceiverPrefs.getOrCreateClientId(this),
            reqWidth = reqWidth,
            reqHeight = reqHeight,
            reqDpi = reqDpi,
            reqFps = reqFps,
            sessionRole = OpalCastClient.SESSION_ROLE_DESKTOP,
            audioEnabled = true,
            initialDisplayMode = initialDisplayMode,
            targetNetwork = boundNet,
            preferredCodec = AndroidApiCompat.resolvePreferredCastCodec(ReceiverPrefs.getVideoCodecPref(this)),
            listener = object : OpalCastClient.Listener {
                override fun onWaitingForApproval() {
                    runOnUiThread {
                        overlaySubtitle.text = "等待对方设备点击「允许」…"
                    }
                }

                override fun onAuthenticated(peerName: String, authToken: String, permanent: Boolean, certFingerprint: String) {
                    if (permanent && authToken.isNotBlank()) {
                        ReceiverPrefs.savePeerAuthToken(this@CastViewerActivity, targetHost, authToken)
                    } else if (!permanent) {
                        ReceiverPrefs.clearPeerAuthToken(this@CastViewerActivity, targetHost)
                    }
                    val resolvedName = peerName.ifBlank { targetLabel }
                    ReceiverPrefs.recordRecentPeer(this@CastViewerActivity, targetHost, targetPort, resolvedName)
                    runOnUiThread {
                        this@CastViewerActivity.peerName = resolvedName
                        connected = true
                        overlayTitle.text = resolvedName
                        overlaySubtitle.text = "已连接 · 等待首帧画面…"
                        statusLabel.text = "$resolvedName · 已连接"
                        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
                        scheduleAutoCollapse(2_400L)
                    }
                }

                override fun onAppListReceived(apps: List<OpalCastClient.RemoteAppEntry>) {
                    runOnUiThread { remoteApps = apps }
                }

                override fun onRemoteImeRequest(show: Boolean, inputType: Int) {
                    runOnUiThread {
                        if (show) imeBridgeView.showIme(inputType) else if (imeBridgeView.isImeVisible) imeBridgeView.hideIme()
                    }
                }

                override fun onRemoteMicState(active: Boolean, supported: Boolean, message: String) {
                    runOnUiThread {
                        if (!active) {
                            stopLocalMicCaptureOnly()
                            micEnabled = false
                        } else {
                            micEnabled = true
                        }
                        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
                        if (message.isNotBlank()) Toast.makeText(this@CastViewerActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onRemoteCameraState(active: Boolean, supported: Boolean, rootRequired: Boolean, message: String, facing: String) {
                    runOnUiThread {
                        if (!active) {
                            stopLocalCameraCaptureOnly()
                            cameraEnabled = false
                        } else {
                            cameraEnabled = true
                            if (facing.isNotBlank()) {
                                val nextFacing = CameraGeometry.normalizeFacing(facing)
                                if (nextFacing != cameraFacing) {
                                    cameraFacing = nextFacing
                                    cameraCapturer?.switchFacing(nextFacing)
                                }
                            }
                        }
                        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
                        if (message.isNotBlank()) Toast.makeText(this@CastViewerActivity, message, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onRemoteBlackoutChanged(blackout: Boolean) {
                    runOnUiThread {
                        remoteBlackout = blackout
                        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
                    }
                }

                override fun onRemoteFullscreenChanged(windowTitle: String) {
                    runOnUiThread {
                        remoteFullscreenTitle = windowTitle
                        if (windowTitle.isNotEmpty()) {
                            fullscreenExitChip.text = "退出全屏 · $windowTitle"
                            fullscreenExitChip.visibility = View.VISIBLE
                        } else {
                            fullscreenExitChip.visibility = View.GONE
                        }
                    }
                }

                override fun onDisplayModeChanged(displayMode: Int) {
                    runOnUiThread {
                        this@CastViewerActivity.displayMode = displayMode
                        updateStatusLabel()
                        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
                    }
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) {
                    runOnUiThread {
                        videoW = width.coerceAtLeast(2)
                        videoH = height.coerceAtLeast(2)
                        videoFps = fps
                        overlayCard.visibility = View.GONE
                        updateStatusLabel()
                        updateSurfaceAspectBounds()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        overlayTitle.text = "连接中断"
                        overlaySubtitle.text = message
                        overlayRetryBtn.visibility = View.VISIBLE
                        overlayCard.visibility = View.VISIBLE
                    }
                }

                override fun onDisconnected(reason: String) {
                    runOnUiThread {
                        connected = false
                        stopLocalMicCaptureOnly()
                        stopLocalCameraCaptureOnly()
                        micEnabled = false
                        cameraEnabled = false
                        refreshToolbarStates(imeVis = false)
                        if (overlayCard.visibility != View.VISIBLE) {
                            overlayTitle.text = "投屏已断开"
                            overlaySubtitle.text = reason
                            overlayRetryBtn.visibility = View.VISIBLE
                            overlayCard.visibility = View.VISIBLE
                        }
                    }
                }
            },
        )
        client = peerClient
        activeSurface?.let { s -> if (s.isValid) peerClient.attachSurface(s) }
        peerClient.start()
    }

    private fun updateStatusLabel() {
        val prefix = if (displayMode == OpalCastClient.DISPLAY_MODE_MIRROR) "镜像" else "桌面"
        statusLabel.text = "$peerName · $prefix ${videoW}×${videoH}@${videoFps}Hz"
    }

    private fun setToolbarExpanded(expanded: Boolean) {
        toolbarExpanded = expanded
        expandedToolbar.visibility = if (expanded) View.VISIBLE else View.GONE
        collapsedHandle.visibility = if (expanded) View.GONE else View.VISIBLE
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (expanded && connected) scheduleAutoCollapse(5_000L)
    }

    private fun scheduleAutoCollapse(delayMs: Long) {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (connected && toolbarExpanded) {
            mainHandler.postDelayed(autoCollapseRunnable, delayMs)
        }
    }

    private fun refreshToolbarStates(imeVis: Boolean) {
        val isMirror = (displayMode == OpalCastClient.DISPLAY_MODE_MIRROR)
        modeBtn.text = if (isMirror) "切桌面" else "切镜像"
        floatMirrorBtn.visibility = if (isMirror) View.VISIBLE else View.GONE
        blackoutBtn.text = if (remoteBlackout) "亮屏" else "熄屏"
        blackoutBtn.background = OpalCastTheme.roundedDrawable(
            this,
            if (remoteBlackout) 0x45F59E0B else 0x1FFFFFFF,
            10,
            if (remoteBlackout) 0x88F59E0B.toInt() else 0x2EFFFFFF,
            0.8f,
        )
        imeBtn.text = if (imeVis) "收键盘" else "键盘"
        micBtn.text = if (micEnabled) "闭麦" else "麦克风"
        micBtn.background = OpalCastTheme.roundedDrawable(
            this,
            if (micEnabled) 0x4514B8A6 else 0x1FFFFFFF,
            10,
            if (micEnabled) 0x882DD4BF.toInt() else 0x2EFFFFFF,
            0.8f,
        )
        camBtn.text = if (cameraEnabled) "关镜头" else "摄像头"
        flipCamBtn.visibility = if (cameraEnabled) View.VISIBLE else View.GONE
    }

    private fun toggleDisplayMode() {
        val next = if (displayMode == OpalCastClient.DISPLAY_MODE_MIRROR) {
            OpalCastClient.DISPLAY_MODE_DEX
        } else {
            OpalCastClient.DISPLAY_MODE_MIRROR
        }
        displayMode = next
        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
        client?.sendSystemAction(OpalCastClient.SYS_TOGGLE_DISPLAY_MODE, next)
    }

    private fun toggleBlackout() {
        remoteBlackout = !remoteBlackout
        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
        client?.sendSystemAction(OpalCastClient.SYS_TOGGLE_BLACKOUT, if (remoteBlackout) 1 else 0)
    }

    private fun popOutMirrorToFloating() {
        if (!AndroidApiCompat.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予 OpalCast 悬浮窗权限", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
            return
        }
        stopLocalMicCaptureOnly()
        stopLocalCameraCaptureOnly()
        client?.stop()
        client = null
        val ok = FloatingCastService.startMirror(this, targetHost, targetPort, peerName.ifBlank { targetLabel })
        if (ok) finish() else {
            Toast.makeText(this, "无法启动悬浮镜像窗", Toast.LENGTH_SHORT).show()
            startPeerSession()
        }
    }

    private fun toggleRemoteMic() {
        if (!connected) return
        if (micEnabled) {
            stopLocalMicCaptureOnly()
            micEnabled = false
            refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
            client?.sendMicConfig(false)
            return
        }
        if (AndroidApiCompat.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            enableRemoteMic()
        } else {
            AndroidApiCompat.requestRuntimePermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun enableRemoteMic() {
        val peer = client ?: return
        stopLocalMicCaptureOnly()
        val cap = CastMicCapturer(
            onPcmFrame = { pcm -> client?.sendMicFrame(pcm) },
            onError = { err ->
                runOnUiThread {
                    stopLocalMicCaptureOnly()
                    micEnabled = false
                    refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
            },
        )
        if (!cap.start()) return
        micCapturer = cap
        micEnabled = true
        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
        peer.sendMicConfig(true)
    }

    private fun stopLocalMicCaptureOnly() {
        micCapturer?.stop()
        micCapturer = null
    }

    private fun toggleRemoteCamera() {
        if (!connected) return
        if (cameraEnabled) {
            stopLocalCameraCaptureOnly()
            cameraEnabled = false
            refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
            client?.sendCameraConfig(false)
            return
        }
        if (AndroidApiCompat.hasPermission(this, Manifest.permission.CAMERA)) {
            enableRemoteCamera()
        } else {
            AndroidApiCompat.requestRuntimePermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        }
    }

    private fun enableRemoteCamera() {
        val peer = client ?: return
        stopLocalCameraCaptureOnly()
        val cap = CastCameraCapturer(
            context = this,
            initialFacing = cameraFacing,
            onJpegFrame = { jpeg -> client?.sendCameraFrame(jpeg) },
            onError = { err ->
                runOnUiThread {
                    stopLocalCameraCaptureOnly()
                    cameraEnabled = false
                    refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
            },
        )
        if (!cap.start()) return
        cameraCapturer = cap
        cameraEnabled = true
        refreshToolbarStates(imeVis = imeBridgeView.isImeVisible)
        peer.sendCameraConfig(true, facing = cameraFacing)
    }

    private fun flipRemoteCamera() {
        if (!connected || !cameraEnabled) return
        val next = if (cameraFacing == "back") "front" else "back"
        cameraFacing = next
        cameraCapturer?.switchFacing(next)
        client?.sendCameraConfig(true, facing = next)
    }

    private fun stopLocalCameraCaptureOnly() {
        cameraCapturer?.stop()
        cameraCapturer = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (granted) enableRemoteMic() else Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show()
            REQ_CAM -> if (granted) enableRemoteCamera() else Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        if (OpalCastClient.sharedSideBackDedup.shouldSuppressDuplicateBackKey(event.keyCode, event.action, now)) {
            return true
        }
        if (connected && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            if (!InputGuardPolicy.isForwardableKey(event.keyCode)) {
                return super.dispatchKeyEvent(event)
            }
            val isDirectShortcut = event.isAltPressed || event.isCtrlPressed || event.keyCode == KeyEvent.KEYCODE_ESCAPE
            if (imeBridgeView.isImeVisible && !isDirectShortcut && super.dispatchKeyEvent(event)) {
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                OpalCastClient.sharedSideBackDedup.recordBackKeyForwarded(now)
            }
            client?.sendKeyEvent(event.action, event.keyCode, event.metaState, event.repeatCount)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backInvokedCallback as? OnBackInvokedCallback)?.let { cb ->
                runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb) }
            }
            backInvokedCallback = null
        }
        mainHandler.removeCallbacks(autoCollapseRunnable)
        stopLocalMicCaptureOnly()
        stopLocalCameraCaptureOnly()
        client?.stop()
        client = null
        wifiLowLatencyLock?.release()
        wifiLowLatencyLock = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = OpalCastTheme.dp(this, v)
}
