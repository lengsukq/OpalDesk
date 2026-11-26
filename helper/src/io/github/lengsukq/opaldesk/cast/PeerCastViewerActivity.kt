package io.github.lengsukq.opaldesk.cast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.NativeImeBridgeHost
import io.github.lengsukq.opaldesk.ui.NativeImeHostController
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.OpalTopPillChip
import io.github.lengsukq.opaldesk.ui.OpalTopPillDivider
import io.github.lengsukq.opaldesk.ui.OpalTopPillIconButton
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
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
    private var statusText by mutableStateOf("正在建立 TLS 加密连接…")
    private var connected by mutableStateOf(false)
    private var videoW by mutableIntStateOf(1920)
    private var videoH by mutableIntStateOf(1080)
    private var videoFps by mutableIntStateOf(60)
    private var toolbarExpanded by mutableStateOf(true)
    private var displayMode by mutableIntStateOf(OpalCastPeerClient.DISPLAY_MODE_DEX)
    private var remoteBlackout by mutableStateOf(false)
    private var remoteFullscreenTitle by mutableStateOf("")
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
            Toast.makeText(this, "需要麦克风权限才能向对方手机回传语音", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableRemoteCamera()
        } else {
            Toast.makeText(this, "需要摄像头权限才能映射本机画面", Toast.LENGTH_SHORT).show()
        }
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
                        imeController.showKeyboard(remoteImeInputType)
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
        applyImmersiveFullscreen()
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
                            cameraEnabled = true
                            if (facing.isNotBlank()) {
                                val nextFacing = PeerCameraGeometry.normalizeFacing(facing)
                                if (nextFacing != cameraFacing) {
                                    cameraFacing = nextFacing
                                    cameraCapturer?.switchFacing(nextFacing)
                                }
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

    private fun toggleRemoteMic() {
        if (!connected) {
            Toast.makeText(this, "请等待投屏连接完成后再开启麦克风", Toast.LENGTH_SHORT).show()
            return
        }
        if (micEnabled) {
            disableRemoteMic()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            enableRemoteMic()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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

    private fun toggleRemoteCamera() {
        if (!connected) {
            Toast.makeText(this, "请等待投屏连接完成后再开启摄像头映射", Toast.LENGTH_SHORT).show()
            return
        }
        if (cameraEnabled) {
            disableRemoteCamera()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            enableRemoteCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
        if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
        if (connected && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            if (!WorkstationKeyDispatcher.isForwardableKey(event.keyCode)) {
                return super.dispatchKeyEvent(event)
            }
            // When user intentionally opened the receiver tablet's built-in IME (e.g. Chinese Pinyin),
            // let non-shortcut keys flow through the IME InputConnection first while keeping Alt/Ctrl shortcuts direct.
            val isDirectShortcut = event.isAltPressed || event.isCtrlPressed || event.keyCode == KeyEvent.KEYCODE_ESCAPE
            if (imeVisible && !isDirectShortcut && super.dispatchKeyEvent(event)) {
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                WorkstationKeyDispatcher.recordBackKeyForwarded()
            }
            client?.sendKeyEvent(event.action, event.keyCode, event.metaState, event.repeatCount)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyImmersiveFullscreen() {
        // Lock peak display refresh rate so OEM power governors (e.g. HyperOS MI-SF) never drop
        // the tablet panel down to 30Hz during static desktop frames.
        try {
            val disp = display
            val cur = disp?.mode
            val peak = disp?.supportedModes
                ?.filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }
                ?.maxByOrNull { it.refreshRate }
            if (peak != null) {
                val lp = window.attributes
                lp.preferredDisplayModeId = peak.modeId
                lp.preferredRefreshRate = peak.refreshRate
                window.attributes = lp
            }
        } catch (_: Throwable) {
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun PeerCastViewerContent(
    peerName: String,
    statusText: String,
    errorText: String,
    connected: Boolean,
    videoW: Int,
    videoH: Int,
    toolbarExpanded: Boolean,
    isMirrorMode: Boolean,
    remoteBlackout: Boolean,
    remoteFullscreenTitle: String,
    micEnabled: Boolean,
    cameraEnabled: Boolean,
    cameraFacing: String,
    imeVisible: Boolean,
    composingText: String,
    imeController: NativeImeHostController,
    onToggleToolbar: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleIme: () -> Unit,
    onImeVisibilityChanged: (Boolean) -> Unit,
    onComposingChanged: (String) -> Unit,
    onSendImeKey: (Int, Int, Int) -> Unit,
    onCommitImeText: (String) -> Unit,
    onSurfaceCreated: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onDispatchTouch: (android.view.MotionEvent, Int, Int) -> Unit,
    onDispatchGenericMotion: (android.view.MotionEvent, Int, Int) -> Boolean,
    onOpenFloatingPicker: () -> Unit,
    onRetryConnect: () -> Unit,
    onSendBack: () -> Unit,
    onSendHome: () -> Unit,
    onExitRemoteFullscreen: () -> Unit,
    onToggleDisplayMode: () -> Unit,
    onPopOutMirrorFloating: () -> Unit,
    onToggleBlackout: () -> Unit,
    onRequestIdr: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val ratio = (videoW.toFloat() / videoH.coerceAtLeast(1).toFloat()).coerceIn(0.38f, 3.0f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080D)),
        contentAlignment = Alignment.Center,
    ) {
        val containerRatio = (maxWidth.value / maxHeight.value.coerceAtLeast(1f)).coerceAtLeast(0.1f)
        val fitHeightFirst = containerRatio > ratio

        // Invisible native IME host so the receiver tablet/phone's built-in IME pops up seamlessly
        NativeImeBridgeHost(
            controller = imeController,
            onSendKey = onSendImeKey,
            onCommitText = onCommitImeText,
            onComposingChanged = onComposingChanged,
            onImeVisibilityChanged = onImeVisibilityChanged,
            modifier = Modifier.align(Alignment.BottomStart),
        )

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(ratio, matchHeightConstraintsFirst = fitHeightFirst),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceCreated(holder.surface)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                onSurfaceCreated(holder.surface)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        },
                    )
                    setOnTouchListener { v, ev ->
                        onDispatchTouch(ev, v.width, v.height)
                        true
                    }
                    setOnGenericMotionListener { v, ev ->
                        onDispatchGenericMotion(ev, v.width, v.height)
                    }
                }
            },
        )

        if (!connected) {
            val cardShape = RoundedCornerShape(22.dp)
            Column(
                modifier = Modifier
                    .liquidGlassSurface(
                        shape = cardShape,
                        fill = OpalPalette.LiquidPopoverFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 24.dp,
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OpalText(text = "正在连接 $peerName", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                OpalText(
                    text = if (errorText.isNotBlank()) errorText else statusText,
                    fontSize = 12.5.sp,
                    color = if (errorText.isNotBlank()) OpalPalette.AccentDanger else OpalPalette.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (errorText.isNotBlank()) {
                        OpalButton(
                            label = "重新请求连接",
                            accent = OpalPalette.AccentBlue,
                            compact = true,
                            onClick = onRetryConnect,
                        )
                    }
                    OpalButton(label = "返回主控制台", filled = errorText.isBlank(), compact = true, onClick = onDisconnect)
                }
            }
        }

        // Live IME composing preview pill just above the tablet/phone soft keyboard
        if (composingText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(bottom = 12.dp)
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidPopoverFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 16.dp,
                    )
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                OpalText(
                    text = composingText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                    maxLines = 1,
                )
            }
        }

        // Unified Top-Center Liquid Frosted Glass remote control pill (icons for universal actions,
        // concise text kept for OpalDesk-specific actions & safe Disconnect)
        if (toolbarExpanded) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 12.dp, end = 12.dp)
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 18.dp,
                    )
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (connected) OpalPalette.AccentEmerald else OpalPalette.AccentAmber),
                )
                OpalText(
                    text = "$peerName · $statusText",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 210.dp),
                )
                OpalTopPillDivider()
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.MIC,
                    contentDescription = if (micEnabled) "关闭麦克风" else "开启麦克风",
                    active = micEnabled,
                    activeColor = OpalPalette.AccentEmerald,
                    onClick = onToggleMic,
                )
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.CAMERA,
                    contentDescription = if (cameraEnabled) "关闭摄像头" else "开启摄像头",
                    active = cameraEnabled,
                    activeColor = OpalPalette.AccentEmerald,
                    onClick = onToggleCamera,
                )
                if (cameraEnabled) {
                    OpalTopPillChip(
                        label = if (cameraFacing == "back") "后" else "前",
                        glyph = ChromeGlyph.CAMERA_FLIP,
                        tint = OpalPalette.AccentCyan,
                        active = true,
                        activeColor = OpalPalette.AccentCyan,
                        onClick = onFlipCamera,
                    )
                }
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.KEYBOARD,
                    contentDescription = if (imeVisible) "收起输入法" else "输入法",
                    active = imeVisible,
                    activeColor = OpalPalette.AccentBlue,
                    onClick = onToggleIme,
                )
                OpalTopPillDivider()
                if (remoteFullscreenTitle.isNotEmpty() && !isMirrorMode) {
                    OpalTopPillChip(
                        label = "退出全屏",
                        glyph = ChromeGlyph.EXIT_FULLSCREEN,
                        filled = true,
                        activeColor = OpalPalette.AccentBlue,
                        onClick = onExitRemoteFullscreen,
                    )
                }
                OpalTopPillChip(
                    label = "悬浮窗",
                    tint = OpalPalette.AccentBlue,
                    active = true,
                    activeColor = OpalPalette.AccentBlue,
                    onClick = onOpenFloatingPicker,
                )
                OpalTopPillChip(
                    label = if (isMirrorMode) "切回桌面" else "主屏镜像",
                    tint = if (isMirrorMode) OpalPalette.AccentCyan else OpalPalette.TextPrimary,
                    active = isMirrorMode,
                    activeColor = OpalPalette.AccentCyan,
                    onClick = onToggleDisplayMode,
                )
                if (isMirrorMode) {
                    OpalTopPillChip(
                        label = "悬浮小窗",
                        tint = OpalPalette.AccentBlue,
                        active = true,
                        activeColor = OpalPalette.AccentBlue,
                        onClick = onPopOutMirrorFloating,
                    )
                }
                OpalTopPillChip(
                    label = if (remoteBlackout) "亮屏" else "熄屏",
                    active = remoteBlackout,
                    activeColor = OpalPalette.AccentAmber,
                    onClick = onToggleBlackout,
                )
                OpalTopPillDivider()
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.BACK,
                    contentDescription = "返回",
                    onClick = onSendBack,
                )
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.HOME_HOUSE,
                    contentDescription = "桌面",
                    onClick = onSendHome,
                )
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.RELOAD,
                    contentDescription = "刷新画面",
                    onClick = onRequestIdr,
                )
                OpalTopPillDivider()
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.CHEVRON_UP,
                    contentDescription = "收起",
                    tint = OpalPalette.TextSecondary,
                    onClick = onToggleToolbar,
                )
                OpalTopPillChip(
                    label = "断开",
                    danger = true,
                    onClick = onDisconnect,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 3.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 6f) onToggleToolbar()
                        }
                    }
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 12.dp,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (micEnabled) {
                    OpalTopPillIconButton(
                        glyph = ChromeGlyph.MIC,
                        contentDescription = "关闭麦克风",
                        active = true,
                        activeColor = OpalPalette.AccentEmerald,
                        onClick = onToggleMic,
                    )
                }
                if (cameraEnabled) {
                    OpalTopPillIconButton(
                        glyph = ChromeGlyph.CAMERA,
                        contentDescription = "关闭摄像头",
                        active = true,
                        activeColor = OpalPalette.AccentEmerald,
                        onClick = onToggleCamera,
                    )
                    OpalTopPillChip(
                        label = if (cameraFacing == "back") "后" else "前",
                        glyph = ChromeGlyph.CAMERA_FLIP,
                        tint = OpalPalette.AccentCyan,
                        active = true,
                        activeColor = OpalPalette.AccentCyan,
                        onClick = onFlipCamera,
                    )
                }
                if (remoteFullscreenTitle.isNotEmpty() && !isMirrorMode) {
                    OpalTopPillIconButton(
                        glyph = ChromeGlyph.EXIT_FULLSCREEN,
                        contentDescription = "退出全屏",
                        active = true,
                        activeColor = OpalPalette.AccentBlue,
                        onClick = onExitRemoteFullscreen,
                    )
                }
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.KEYBOARD,
                    contentDescription = if (imeVisible) "收起输入法" else "输入法",
                    active = imeVisible,
                    activeColor = OpalPalette.AccentBlue,
                    onClick = onToggleIme,
                )
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.CHEVRON_DOWN,
                    contentDescription = "展开投屏控制",
                    tint = OpalPalette.TextSecondary,
                    onClick = onToggleToolbar,
                )
            }
        }
    }
}
