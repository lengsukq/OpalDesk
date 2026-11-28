package io.github.lengsukq.opaldesk.cast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.rememberNativeImeHostController

/**
 * Fullscreen hardware-accelerated Android <-> Android OpalCast v1.1 Peer Receiver Workstation.
 *
 * Enforces strict one-way casting mutual exclusion ([OpalPeerCastMutex]): while receiving a remote
 * Android device's desktop, local Wi-Fi sending & NSD advertising are automatically paused so
 * simultaneous mutual casting (A <-> B) can never occur.
 */
class PeerCastViewerActivity : ComponentActivity() {
    companion object {
        private const val MUTEX_TAG = "desktop_viewer"
        const val EXTRA_HOST = "peer_host"
        const val EXTRA_PORT = "peer_port"
        const val EXTRA_LABEL = "peer_label"
        const val EXTRA_INITIAL_DISPLAY_MODE = "initial_display_mode"
    }

    private var client: OpalCastPeerClient? = null
    private var wifiLowLatencyLock: WifiLowLatencyLock? = null
    private var micCapturer: PeerMicCapturer? = null
    private var cameraCapturer: PeerCameraCapturer? = null
    private var activeSurface: android.view.Surface? = null
    private var targetHost: String = ""
    private var targetPort: Int = 19878
    private var targetLabel: String = ""
    private var initialDisplayMode: Int = OpalCastPeerClient.DISPLAY_MODE_DEX
    private var reqWidth: Int = 1920
    private var reqHeight: Int = 1080
    private var reqDpi: Int = 240
    private var reqFps: Int = 60

    private var peerName by mutableStateOf("")
    private var statusText by mutableStateOf("")
    private var connected by mutableStateOf(false)
    private var videoW by mutableIntStateOf(1920)
    private var videoH by mutableIntStateOf(1080)
    private var videoFps by mutableIntStateOf(60)
    private var toolbarExpanded by mutableStateOf(true)
    private var displayMode by mutableIntStateOf(OpalCastPeerClient.DISPLAY_MODE_DEX)
    private var remoteBlackout by mutableStateOf(false)
    private var remoteFullscreenTitle by mutableStateOf("")
    private var selectedBitrateKbps by mutableIntStateOf(0)
    private var toolbarInteractTick by mutableIntStateOf(0)
    private var micEnabled by mutableStateOf(false)
    private var cameraEnabled by mutableStateOf(false)
    private var cameraFacing by mutableStateOf("front")
    private var imeVisible by mutableStateOf(false)
    private var composingText by mutableStateOf("")
    private var remoteImeReqSeq by mutableLongStateOf(0L)
    private var remoteImeShow by mutableStateOf(false)
    private var remoteImeInputType by mutableIntStateOf(0)
    private var errorText by mutableStateOf("")
    private var showAppPicker by mutableStateOf(false)
    private var remoteApps by mutableStateOf<List<OpalCastPeerClient.RemoteAppEntry>>(emptyList())

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableRemoteMic()
        } else {
            Toast.makeText(this, getString(R.string.peercast_need_mic_perm), Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableRemoteCamera()
        } else {
            Toast.makeText(this, getString(R.string.peercast_need_cam_perm), Toast.LENGTH_SHORT).show()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(OpalLocalePolicy.wrapContext(newBase))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim()
        val port = intent.getIntExtra(EXTRA_PORT, 19878).coerceIn(1, 65535)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { host }
        val initMode = intent.getIntExtra(EXTRA_INITIAL_DISPLAY_MODE, OpalCastPeerClient.DISPLAY_MODE_DEX)
        targetHost = host
        targetPort = port
        targetLabel = label
        initialDisplayMode = if (initMode == OpalCastPeerClient.DISPLAY_MODE_MIRROR) {
            OpalCastPeerClient.DISPLAY_MODE_MIRROR
        } else {
            OpalCastPeerClient.DISPLAY_MODE_DEX
        }
        displayMode = initialDisplayMode
        peerName = label

        // Strict one-way guard: refuse if currently sending out or targeting self
        val remoteSt = BridgeClient.remoteStatusFlow.value
        val check = OpalPeerCastMutex.validateStartReceiving(
            daemonSending = remoteSt.connected,
            sendingPeerName = remoteSt.peerName,
            targetHost = host,
            selfLanIp = remoteSt.lanIp,
        )
        if (!check.allowed || !OpalPeerCastMutex.beginReceiving(host, port, label, MUTEX_TAG)) {
            Toast.makeText(this, check.reason.ifBlank { "当前不可建立反向投屏" }, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Auto-pause local Wi-Fi sender & mDNS broadcast so mutual simultaneous casting is impossible
        OpalPeerDiscovery.stopAdvertise()
        if (remoteSt.wifiEnabled) {
            BridgeClient.setWifiStreamEnabledAsync(false)
        }

        val dm = resources.displayMetrics
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val rawW = bounds?.width()?.takeIf { it > 0 } ?: dm.widthPixels
        val rawH = bounds?.height()?.takeIf { it > 0 } ?: dm.heightPixels
        val peerSpec = io.github.lengsukq.opaldesk.external.CustomResolutionPolicy.computePeerCastRequestSpec(
            displayW = rawW,
            displayH = rawH,
            densityDpi = dm.densityDpi,
            refreshRateHz = display?.mode?.refreshRate ?: 60f,
        )
        reqWidth = peerSpec.width
        reqHeight = peerSpec.height
        reqDpi = peerSpec.dpi
        reqFps = peerSpec.fps
        videoW = reqWidth
        videoH = reqHeight
        selectedBitrateKbps = AppPrefs.getCastBitrateKbps(this)

        startPeerSession()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showAppPicker) {
                        showAppPicker = false
                    } else if (connected) {
                        client?.sendSystemAction(OpalCastPeerClient.SYS_BACK)
                    } else {
                        finish()
                    }
                }
            },
        )

        setContent {
            OpalTheme {
                val imeController = rememberNativeImeHostController()
                LaunchedEffect(connected, toolbarExpanded, toolbarInteractTick, showAppPicker) {
                    if (connected && toolbarExpanded && !showAppPicker) {
                        kotlinx.coroutines.delay(if (toolbarInteractTick == 0) 2_200L else 5_000L)
                        toolbarExpanded = false
                    }
                }
                LaunchedEffect(remoteImeReqSeq) {
                    if (remoteImeReqSeq <= 0L || showAppPicker) return@LaunchedEffect
                    if (remoteImeShow) {
                        // When a Bluetooth / USB physical keyboard is attached to the receiver tablet,
                        // do NOT summon the tablet's local IME (NativeImeBridgeHost): forward all
                        // hardware keys directly to the casting phone so the phone's Yuyan IME
                        // handles input and renders its cursor-following candidate bar cleanly.
                        if (!hasConnectedPhysicalKeyboard(this@PeerCastViewerActivity)) {
                            imeController.showKeyboard(remoteImeInputType)
                        } else if (imeVisible) {
                            imeController.hideKeyboard()
                        }
                    } else if (imeVisible) {
                        imeController.hideKeyboard()
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    PeerCastViewerContent(
                        peerName = peerName,
                        statusText = statusText,
                        errorText = errorText,
                        connected = connected,
                        videoW = videoW,
                        videoH = videoH,
                        toolbarExpanded = toolbarExpanded,
                        isMirrorMode = (displayMode == OpalCastPeerClient.DISPLAY_MODE_MIRROR),
                        remoteBlackout = remoteBlackout,
                        remoteFullscreenTitle = remoteFullscreenTitle,
                        selectedBitrateKbps = selectedBitrateKbps,
                        micEnabled = micEnabled,
                        cameraEnabled = cameraEnabled,
                        cameraFacing = cameraFacing,
                        imeVisible = imeVisible,
                        composingText = composingText,
                        imeController = imeController,
                        onToggleToolbar = {
                            toolbarExpanded = !toolbarExpanded
                            if (toolbarExpanded) toolbarInteractTick += 1
                        },
                        onToggleMic = {
                            toolbarInteractTick += 1
                            toggleRemoteMic()
                        },
                        onToggleCamera = {
                            toolbarInteractTick += 1
                            toggleRemoteCamera()
                        },
                        onFlipCamera = {
                            toolbarInteractTick += 1
                            flipRemoteCamera()
                        },
                        onToggleIme = {
                            toolbarInteractTick += 1
                            imeController.toggleKeyboard(imeVisible)
                        },
                        onImeVisibilityChanged = { vis ->
                            imeVisible = vis
                            if (!vis) composingText = ""
                        },
                        onComposingChanged = { composingText = it },
                        onSendImeKey = { action, keyCode, meta ->
                            client?.sendKeyEvent(action, keyCode, meta, 0)
                        },
                        onCommitImeText = { txt ->
                            client?.sendTextEvent(txt, commitPaste = true)
                        },
                        onSurfaceCreated = { s ->
                            activeSurface = s
                            client?.attachSurface(s)
                        },
                        onSurfaceDestroyed = {
                            activeSurface = null
                            client?.attachSurface(null)
                        },
                        onDispatchTouch = { ev, w, h ->
                            val c = client ?: return@PeerCastViewerContent
                            if (c.isMouseMotion(ev)) c.sendMouseEvent(ev, w, h) else c.sendTouchEvent(ev, w, h)
                        },
                        onDispatchGenericMotion = { ev, w, h ->
                            val c = client ?: return@PeerCastViewerContent false
                            c.handleGenericMouseMotion(ev, w, h)
                        },
                        onOpenFloatingPicker = {
                            toolbarExpanded = false
                            client?.requestAppList()
                            showAppPicker = true
                        },
                        onRetryConnect = { startPeerSession() },
                        onSendBack = {
                            toolbarInteractTick += 1
                            client?.sendSystemAction(OpalCastPeerClient.SYS_BACK)
                        },
                        onSendHome = {
                            toolbarInteractTick += 1
                            client?.sendSystemAction(OpalCastPeerClient.SYS_HOME)
                        },
                        onExitRemoteFullscreen = {
                            toolbarInteractTick += 1
                            remoteFullscreenTitle = ""
                            client?.sendSystemAction(OpalCastPeerClient.SYS_HOME)
                        },
                        onToggleDisplayMode = {
                            toolbarInteractTick += 1
                            val nextMode = if (displayMode == OpalCastPeerClient.DISPLAY_MODE_MIRROR) {
                                OpalCastPeerClient.DISPLAY_MODE_DEX
                            } else {
                                OpalCastPeerClient.DISPLAY_MODE_MIRROR
                            }
                            displayMode = nextMode
                            statusText = if (nextMode == OpalCastPeerClient.DISPLAY_MODE_MIRROR) {
                                "正在切换为主屏镜像…"
                            } else {
                                "正在切换回 DeX 桌面…"
                            }
                            client?.sendSystemAction(OpalCastPeerClient.SYS_TOGGLE_DISPLAY_MODE, nextMode)
                        },
                        onPopOutMirrorFloating = {
                            toolbarInteractTick += 1
                            popOutMirrorToFloating()
                        },
                        onToggleBlackout = {
                            toolbarInteractTick += 1
                            val nextBlackout = !remoteBlackout
                            remoteBlackout = nextBlackout
                            client?.sendSystemAction(
                                OpalCastPeerClient.SYS_TOGGLE_BLACKOUT,
                                if (nextBlackout) 1 else 0,
                            )
                        },
                        onSelectBitrateKbps = { kbps ->
                            toolbarInteractTick += 1
                            selectedBitrateKbps = kbps
                            AppPrefs.setCastBitrateKbps(this@PeerCastViewerActivity, kbps)
                            client?.sendResizeConfig(videoW, videoH, reqDpi, videoFps, kbps)
                        },
                        onRequestIdr = {
                            toolbarInteractTick += 1
                            client?.requestKeyFrame()
                        },
                        onDisconnect = { finish() },
                    )

                    if (showAppPicker) {
                        RemoteAppPickerModal(
                            host = targetHost,
                            port = targetPort,
                            peerLabel = peerName,
                            preloadedApps = remoteApps.takeIf { it.isNotEmpty() },
                            onDismiss = { showAppPicker = false },
                            onFloatingLaunched = { showAppPicker = false },
                        )
                    }
                }
            }
        }
        applyPeerCastImmersiveFullscreen(window, display)
    }

    private fun startPeerSession() {
        client?.stop()
        client = null
        connected = false
        displayMode = initialDisplayMode
        remoteBlackout = false
        errorText = ""
        statusText = "正在建立 TLS 加密连接…"

        val boundNet = if (AppPrefs.isUsbTetherCastEnabled(this)) {
            UsbTetherPeerDetector.resolveBoundNetworkForHost(targetHost)
        } else {
            null
        }
        val wifiLock = wifiLowLatencyLock ?: WifiLowLatencyLock(this, "OpalDesk:PeerCastRx").also {
            wifiLowLatencyLock = it
        }
        if (boundNet == null) {
            wifiLock.acquire()
        } else {
            wifiLock.release()
        }
        val peerClient = OpalCastPeerClient(
            host = targetHost,
            port = targetPort,
            savedAuthToken = AppPrefs.getPeerAuthToken(this, targetHost),
            clientId = AppPrefs.getOrCreatePeerClientId(this),
            reqWidth = reqWidth,
            reqHeight = reqHeight,
            reqDpi = reqDpi,
            reqFps = reqFps,
            sessionRole = OpalCastPeerClient.SESSION_ROLE_DESKTOP,
            audioEnabled = true,
            initialDisplayMode = initialDisplayMode,
            targetNetwork = boundNet,
            preferredBitrateKbps = selectedBitrateKbps,
            listener = object : OpalCastPeerClient.Listener {
                override fun onWaitingForApproval() {
                    runOnUiThread {
                        statusText = "等待对方设备点击「允许」…"
                    }
                }

                override fun onAuthenticated(
                    peerName: String,
                    authToken: String,
                    permanent: Boolean,
                    certFingerprint: String,
                ) {
                    if (permanent && authToken.isNotBlank()) {
                        AppPrefs.savePeerAuthToken(this@PeerCastViewerActivity, targetHost, authToken)
                    } else if (!permanent) {
                        AppPrefs.clearPeerAuthToken(this@PeerCastViewerActivity, targetHost)
                    }
                    OpalPeerCastMutex.updateReceivingPeerName(peerName)
                    runOnUiThread {
                        this@PeerCastViewerActivity.peerName = peerName.ifBlank { targetLabel }
                        connected = true
                        errorText = ""
                        statusText = "已连接 · 等待画面关键帧"
                    }
                }

                override fun onAppListReceived(apps: List<OpalCastPeerClient.RemoteAppEntry>) {
                    runOnUiThread {
                        remoteApps = apps
                    }
                }

                override fun onRemoteImeRequest(show: Boolean, inputType: Int) {
                    runOnUiThread {
                        remoteImeShow = show
                        remoteImeInputType = inputType
                        remoteImeReqSeq += 1L
                    }
                }

                override fun onRemoteMicState(active: Boolean, supported: Boolean, message: String) {
                    runOnUiThread {
                        if (!active) {
                            stopLocalMicCaptureOnly()
                            micEnabled = false
                        } else if (micCapturer == null) {
                            startRemoteMicFromHost()
                        } else {
                            micEnabled = true
                        }
                        if (message.isNotBlank()) {
                            Toast.makeText(this@PeerCastViewerActivity, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onRemoteCameraState(
                    active: Boolean,
                    supported: Boolean,
                    rootRequired: Boolean,
                    message: String,
                    facing: String,
                ) {
                    runOnUiThread {
                        if (!active) {
                            stopLocalCameraCaptureOnly()
                            cameraEnabled = false
                        } else {
                            if (facing.isNotBlank()) {
                                val nextFacing = PeerCameraGeometry.normalizeFacing(facing)
                                if (nextFacing != cameraFacing) {
                                    cameraFacing = nextFacing
                                    cameraCapturer?.switchFacing(nextFacing)
                                }
                            }
                            if (cameraCapturer == null) {
                                startRemoteCameraFromHost()
                            } else {
                                cameraEnabled = true
                            }
                        }
                        if (message.isNotBlank()) {
                            Toast.makeText(this@PeerCastViewerActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                override fun onRemoteBlackoutChanged(blackout: Boolean) {
                    runOnUiThread {
                        remoteBlackout = blackout
                    }
                }

                override fun onRemoteFullscreenChanged(windowTitle: String) {
                    runOnUiThread {
                        val enteredFullscreen = remoteFullscreenTitle.isEmpty() && windowTitle.isNotEmpty()
                        remoteFullscreenTitle = windowTitle
                        if (enteredFullscreen && connected && !showAppPicker) {
                            toolbarInteractTick = 0
                            toolbarExpanded = true
                        }
                    }
                }

                override fun onDisplayModeChanged(displayMode: Int) {
                    runOnUiThread {
                        this@PeerCastViewerActivity.displayMode = displayMode
                        if (connected) {
                            val modePrefix = if (displayMode == OpalCastPeerClient.DISPLAY_MODE_MIRROR) "镜像 · " else ""
                            statusText = "${modePrefix}${videoW}×${videoH} @ ${videoFps}Hz"
                        }
                    }
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) {
                    runOnUiThread {
                        videoW = width.coerceAtLeast(2)
                        videoH = height.coerceAtLeast(2)
                        videoFps = fps
                        val modePrefix = if (displayMode == OpalCastPeerClient.DISPLAY_MODE_MIRROR) "镜像 · " else ""
                        statusText = "${modePrefix}${videoW}×${videoH} @ ${videoFps}Hz"
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        errorText = message
                        statusText = message
                    }
                }

                override fun onDisconnected(reason: String) {
                    runOnUiThread {
                        connected = false
                        remoteFullscreenTitle = ""
                        stopLocalMicCaptureOnly()
                        micEnabled = false
                        stopLocalCameraCaptureOnly()
                        cameraEnabled = false
                        if (errorText.isBlank()) statusText = reason
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

    private fun popOutMirrorToFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予 OpalDesk 悬浮窗权限，授权后再点击「悬浮小窗」", Toast.LENGTH_LONG).show()
            try {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(permIntent)
            } catch (_: Throwable) {
            }
            return
        }
        stopLocalMicCaptureOnly()
        stopLocalCameraCaptureOnly()
        client?.stop()
        client = null
        val ok = PeerFloatingAppService.startMirror(
            ctx = this,
            host = targetHost,
            port = targetPort,
            peerLabel = peerName.ifBlank { targetLabel },
        )
        if (ok) {
            finish()
        } else {
            Toast.makeText(this, "无法启动主屏镜像悬浮窗，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
            startPeerSession()
        }
    }

    private fun startRemoteMicFromHost() {
        if (!connected || micCapturer != null) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            enableRemoteMic()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun toggleRemoteMic() {
        if (!connected) {
            Toast.makeText(this, "请等待投屏连接完成后再开启麦克风", Toast.LENGTH_SHORT).show()
            return
        }
        if (micEnabled) {
            disableRemoteMic()
            return
        }
        startRemoteMicFromHost()
    }

    private fun enableRemoteMic() {
        val peer = client ?: return
        stopLocalMicCaptureOnly()
        val cap = PeerMicCapturer(
            sampleRate = PeerMicCapturer.DEFAULT_SAMPLE_RATE,
            onPcmFrame = { pcm -> client?.sendMicFrame(pcm) },
            onError = { err ->
                runOnUiThread {
                    disableRemoteMic()
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
            },
        )
        if (!cap.start()) {
            return
        }
        micCapturer = cap
        micEnabled = true
        peer.sendMicConfig(true, PeerMicCapturer.DEFAULT_SAMPLE_RATE, PeerMicCapturer.DEFAULT_CHANNELS)
    }

    private fun disableRemoteMic() {
        stopLocalMicCaptureOnly()
        micEnabled = false
        client?.sendMicConfig(false, PeerMicCapturer.DEFAULT_SAMPLE_RATE, PeerMicCapturer.DEFAULT_CHANNELS)
    }

    private fun stopLocalMicCaptureOnly() {
        micCapturer?.stop()
        micCapturer = null
    }

    private fun startRemoteCameraFromHost() {
        if (!connected || cameraCapturer != null) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            enableRemoteCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleRemoteCamera() {
        if (!connected) {
            Toast.makeText(this, "请等待投屏连接完成后再开启摄像头映射", Toast.LENGTH_SHORT).show()
            return
        }
        if (cameraEnabled) {
            disableRemoteCamera()
            return
        }
        startRemoteCameraFromHost()
    }

    private fun enableRemoteCamera() {
        val peer = client ?: return
        stopLocalCameraCaptureOnly()
        val desiredFacing = cameraFacing
        val cap = PeerCameraCapturer(
            context = this,
            initialFacing = desiredFacing,
            onJpegFrame = { jpeg -> client?.sendCameraFrame(jpeg) },
            onError = { err ->
                runOnUiThread {
                    disableRemoteCamera()
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
            },
        )
        if (!cap.start()) {
            return
        }
        cameraCapturer = cap
        cameraEnabled = true
        peer.sendCameraConfig(true, facing = desiredFacing)
    }

    private fun flipRemoteCamera() {
        if (!connected || !cameraEnabled) return
        val next = if (cameraFacing == "back") "front" else "back"
        cameraFacing = next
        cameraCapturer?.switchFacing(next)
        client?.sendCameraConfig(true, facing = next)
    }

    private fun disableRemoteCamera() {
        stopLocalCameraCaptureOnly()
        cameraEnabled = false
        client?.sendCameraConfig(false)
    }

    private fun stopLocalCameraCaptureOnly() {
        cameraCapturer?.stop()
        cameraCapturer = null
    }

    override fun onDestroy() {
        stopLocalMicCaptureOnly()
        stopLocalCameraCaptureOnly()
        client?.stop()
        client = null
        wifiLowLatencyLock?.release()
        wifiLowLatencyLock = null
        OpalPeerCastMutex.endReceiving(MUTEX_TAG)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return dispatchPeerCastKeyEvent(
            event = event,
            connected = connected,
            imeVisible = imeVisible,
            client = client,
            superDispatch = { super.dispatchKeyEvent(it) },
        )
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (dispatchPeerCastGenericMotion(
                event = event,
                connected = connected,
                showAppPicker = showAppPicker,
                viewW = window.decorView.width,
                viewH = window.decorView.height,
                client = client,
            )
        ) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}
