package io.github.lengsukq.opalcast.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.Toast
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.media.CastCameraCapturer
import io.github.lengsukq.opalcast.media.CastMicCapturer
import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.net.UsbTetherDiscovery
import io.github.lengsukq.opalcast.net.WifiLowLatencyLock
import io.github.lengsukq.opalcast.policy.CameraGeometry
import io.github.lengsukq.opalcast.policy.InputGuardPolicy
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.prefs.ReceiverLocalePolicy
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
    private lateinit var chrome: CastViewerChromeViews

    private val autoCollapseRunnable = Runnable {
        if (connected && toolbarExpanded) {
            setToolbarExpanded(false)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ReceiverLocalePolicy.wrapContext(newBase))
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
            if (OpalCastClient.sharedSideBackDedup.shouldSuppressDuplicateBackKey(
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.ACTION_DOWN,
                    SystemClock.uptimeMillis(),
                )
            ) {
                return@OnBackInvokedCallback
            }
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
                    chrome.composingPill.text = comp
                    chrome.composingPill.visibility = if (comp.isNotBlank()) View.VISIBLE else View.GONE
                }
            },
            onImeVisibilityChanged = { visible ->
                runOnUiThread { refreshToolbarStates(imeVis = visible) }
            },
        )
        rootFrame.addView(imeBridgeView, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.START))

        chrome = CastViewerToolbarHelper.attachChromeViews(
            activity = this,
            rootFrame = rootFrame,
            initialPeerName = peerName,
            callbacks = CastViewerToolbarCallbacks(
                onScheduleAutoCollapse = ::scheduleAutoCollapse,
                onExpandToolbar = { setToolbarExpanded(true) },
                onCollapseToolbar = { setToolbarExpanded(false) },
                onBack = { client?.sendSystemAction(OpalCastClient.SYS_BACK) },
                onHome = { client?.sendSystemAction(OpalCastClient.SYS_HOME) },
                onToggleDisplayMode = ::toggleDisplayMode,
                onPopOutMirror = ::popOutMirrorToFloating,
                onToggleBlackout = ::toggleBlackout,
                onToggleIme = { imeBridgeView.toggleIme() },
                onToggleMic = ::toggleRemoteMic,
                onToggleCamera = ::toggleRemoteCamera,
                onFlipCamera = ::flipRemoteCamera,
                onOpenAppPicker = {
                    setToolbarExpanded(false)
                    client?.requestAppList()
                    RemoteAppPickerDialog(
                        activity = this,
                        host = targetHost,
                        port = targetPort,
                        peerLabel = peerName,
                        preloadedApps = remoteApps.takeIf { it.isNotEmpty() },
                    ).show()
                },
                onRefreshKeyFrame = { client?.requestKeyFrame() },
                onDisconnect = { finish() },
                onExitFullscreen = {
                    remoteFullscreenTitle = ""
                    chrome.fullscreenExitChip.visibility = View.GONE
                    client?.sendSystemAction(OpalCastClient.SYS_HOME)
                },
                onRetryConnect = ::startPeerSession,
            ),
        )

        setContentView(rootFrame)
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
        chrome.overlayTitle.text = peerName
        chrome.overlaySubtitle.text = getString(R.string.viewer_connecting_tls)
        chrome.overlayRetryBtn.visibility = View.GONE
        chrome.overlayCard.visibility = View.VISIBLE

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
            defaultDisconnectReason = getString(R.string.client_disconnected),
            defaultRejectedMessage = getString(R.string.client_rejected),
            listener = object : OpalCastClient.Listener {
                override fun onWaitingForApproval() {
                    runOnUiThread {
                        chrome.overlaySubtitle.text = getString(R.string.viewer_waiting_approval)
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
                        chrome.overlayTitle.text = resolvedName
                        chrome.overlaySubtitle.text = getString(R.string.viewer_connected_waiting_frame)
                        chrome.statusLabel.text = getString(R.string.viewer_status_connected_fmt, resolvedName)
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
                            chrome.fullscreenExitChip.text = getString(R.string.viewer_exit_fullscreen_fmt, windowTitle)
                            chrome.fullscreenExitChip.visibility = View.VISIBLE
                        } else {
                            chrome.fullscreenExitChip.visibility = View.GONE
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
                        chrome.overlayCard.visibility = View.GONE
                        updateStatusLabel()
                        updateSurfaceAspectBounds()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        chrome.overlayTitle.text = getString(R.string.viewer_error_title)
                        chrome.overlaySubtitle.text = message
                        chrome.overlayRetryBtn.visibility = View.VISIBLE
                        chrome.overlayCard.visibility = View.VISIBLE
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
                        if (chrome.overlayCard.visibility != View.VISIBLE) {
                            chrome.overlayTitle.text = getString(R.string.viewer_disconnected_title)
                            chrome.overlaySubtitle.text = reason
                            chrome.overlayRetryBtn.visibility = View.VISIBLE
                            chrome.overlayCard.visibility = View.VISIBLE
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
        val prefix = if (displayMode == OpalCastClient.DISPLAY_MODE_MIRROR) {
            getString(R.string.viewer_mode_mirror)
        } else {
            getString(R.string.viewer_mode_desktop)
        }
        chrome.statusLabel.text = getString(R.string.viewer_status_stream_fmt, peerName, prefix, videoW, videoH, videoFps)
    }

    private fun setToolbarExpanded(expanded: Boolean) {
        toolbarExpanded = expanded
        chrome.expandedToolbar.visibility = if (expanded) View.VISIBLE else View.GONE
        chrome.collapsedHandle.visibility = if (expanded) View.GONE else View.VISIBLE
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
        CastViewerToolbarHelper.refreshToolbarStates(
            activity = this,
            chrome = chrome,
            displayMode = displayMode,
            remoteBlackout = remoteBlackout,
            imeVis = imeVis,
            micEnabled = micEnabled,
            cameraEnabled = cameraEnabled,
        )
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
            Toast.makeText(this, getString(R.string.perm_overlay_required), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.viewer_float_mirror_failed), Toast.LENGTH_SHORT).show()
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
            context = this,
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
            REQ_MIC -> if (granted) enableRemoteMic() else Toast.makeText(this, getString(R.string.viewer_perm_mic_required), Toast.LENGTH_SHORT).show()
            REQ_CAM -> if (granted) enableRemoteCamera() else Toast.makeText(this, getString(R.string.viewer_perm_camera_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val c = client
        if (connected && c != null && c.isMouseMotion(event) &&
            (AndroidApiCompat.isMouseButtonPress(event) || AndroidApiCompat.isMouseButtonRelease(event))
        ) {
            val w = surfaceView.width.takeIf { it > 0 } ?: rootFrame.width
            val h = surfaceView.height.takeIf { it > 0 } ?: rootFrame.height
            return c.handleGenericMouseMotion(event, w, h)
        }
        return super.onGenericMotionEvent(event)
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
}
