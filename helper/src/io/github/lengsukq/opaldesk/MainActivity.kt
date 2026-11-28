package io.github.lengsukq.opaldesk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerCastViewerActivity
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.ExternalTrackpadLauncher
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSpotlightController
import io.github.lengsukq.opaldesk.ui.OpalTheme

/**
 * OpalDesk v1.0.0 macOS Liquid Glass Control Console.
 *
 * Unifies:
 * 1. On-device Local DeX Workstation ([DesktopActivity])
 * 2. USB-C / HDMI Full-Bleed External Monitor & Precision Trackpad ([ExternalDesktopActivity])
 * 3. MacOS OpalCast v1.1 USB + Wi-Fi TLS Remote Streaming
 * 4. Display Resolution / EDID Refresh Rate / FPS / DPI / Auto-Adapt & Engine Pool Tuning
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FROM_DESKTOP = "from_desktop"
        const val EXTRA_BYPASS_AUTO_ENTER_DEX = "bypass_auto_enter_dex"
        const val EXTRA_OPEN_ACTIVATION = "open_activation"
        const val EXTRA_FOCUS_TAB = "focus_tab"
        const val EXTRA_FOCUS_SECTION_KEY = "focus_section_key"

        fun createLaunchIntent(
            ctx: Context,
            fromDesktop: Boolean = false,
            bypassAutoEnterDex: Boolean = false,
            openActivation: Boolean = false,
            focusTab: Int = -1,
            focusSectionKey: String? = null,
        ): Intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (fromDesktop) putExtra(EXTRA_FROM_DESKTOP, true)
            if (bypassAutoEnterDex) putExtra(EXTRA_BYPASS_AUTO_ENTER_DEX, true)
            if (openActivation) putExtra(EXTRA_OPEN_ACTIVATION, true)
            if (focusTab in 0..3) putExtra(EXTRA_FOCUS_TAB, focusTab)
            if (!focusSectionKey.isNullOrBlank()) putExtra(EXTRA_FOCUS_SECTION_KEY, focusSectionKey)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var localNetworkPermGranted by mutableStateOf(true)
    private var autoEnterDexArmed by mutableStateOf(false)
    private var openActivationPulse by mutableIntStateOf(0)
    private var pendingFocusTab by mutableIntStateOf(-1)
    private var pendingFocusSectionKey by mutableStateOf<String?>(null)
    private var pendingFocusPulse by mutableIntStateOf(0)
    private var showSpotlight by mutableStateOf(false)
    private val spotlightController = OpalSpotlightController()
    private var consumedSpotlightToggleKeyUp = false
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }
    private val localNetPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkPermGranted = granted || OpalPeerDiscovery.hasLocalNetworkPermission(this)
        if (localNetworkPermGranted) {
            val r = BridgeClient.remoteStatusFlow.value
            OpalPeerDiscovery.syncAdvertise(this, r.isLanShareActive, r.wifiPort, r.shortCertFingerprint)
            OpalPeerDiscovery.startBrowsing(this, r.lanIp)
        }
    }
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread({
            val saved = WallpaperStorage.importWallpaperFromUri(this, uri)
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(if (saved != null) R.string.toast_wallpaper_updated else R.string.toast_wallpaper_read_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "OpalWallpaperImport").start()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(OpalLocalePolicy.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpalLogStore.init(applicationContext)
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.hydrate(applicationContext)
        BridgeClient.bindAppContext(applicationContext)
        OpalLogStore.i("App", "MainActivity started (API ${Build.VERSION.SDK_INT})")
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotificationPermissionIfNeeded()
        localNetworkPermGranted = OpalPeerDiscovery.hasLocalNetworkPermission(this)
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        BridgeClient.requestBinder(this)
        scheduleSilentAutoBootstrapIfConfigured()
        applyLaunchIntentFlags(intent, isColdCreate = savedInstanceState == null)

        setContent {
            OpalTheme {
                val isDark = OpalPalette.isDark
                LaunchedEffect(isDark) {
                    val ctrl = WindowCompat.getInsetsController(window, window.decorView)
                    ctrl.isAppearanceLightStatusBars = !isDark
                    ctrl.isAppearanceLightNavigationBars = !isDark
                }
                OpalConsoleScreen(
                    needsLocalNetworkPerm = !localNetworkPermGranted,
                    autoEnterDexArmed = autoEnterDexArmed,
                    openActivationPulse = openActivationPulse,
                    pendingFocusTab = pendingFocusTab,
                    pendingFocusSectionKey = pendingFocusSectionKey,
                    pendingFocusPulse = pendingFocusPulse,
                    showSpotlight = showSpotlight,
                    spotlightController = spotlightController,
                    onSetShowSpotlight = { showSpotlight = it },
                    onDisarmAutoEnterDex = { autoEnterDexArmed = false },
                    onRequestLocalNetworkPerm = {
                        if (Build.VERSION.SDK_INT >= 37) {
                            localNetPermLauncher.launch(OpalPeerDiscovery.PERMISSION_ACCESS_LOCAL_NETWORK)
                        }
                    },
                    onEnterDesktop = { launchDesktopWorkstation(forceLandscape = false) },
                    onEnterLandscapeMirrorDesktop = { launchDesktopWorkstation(forceLandscape = true) },
                    onOpenRemoteTrackpad = ::launchRemoteTrackpad,
                    onProjectExternalOrTrackpad = ::launchExternalOrTrackpad,
                    onProjectSpecificDisplay = ::launchSpecificExternalDisplay,
                    onConnectPeerCast = ::launchPeerCastViewer,
                    onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                    onStopEngine = {
                        BridgeService.stopAllAndExit(this)
                    },
                    onCompleteExitApp = {
                        BridgeService.completeExitApp(this)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntentFlags(intent, isColdCreate = false)
    }

    private fun applyLaunchIntentFlags(launchIntent: Intent?, isColdCreate: Boolean) {
        val fromDesktop = launchIntent?.getBooleanExtra(EXTRA_FROM_DESKTOP, false) == true
        val bypassAutoEnter = launchIntent?.getBooleanExtra(EXTRA_BYPASS_AUTO_ENTER_DEX, false) == true
        val openActivation = launchIntent?.getBooleanExtra(EXTRA_OPEN_ACTIVATION, false) == true
        val focusTab = launchIntent?.getIntExtra(EXTRA_FOCUS_TAB, -1) ?: -1
        val focusSection = launchIntent?.getStringExtra(EXTRA_FOCUS_SECTION_KEY)?.trim().orEmpty()
        val hasFocusTarget = focusTab in 0..3 || focusSection.isNotEmpty()
        if (fromDesktop || bypassAutoEnter || openActivation || hasFocusTarget) {
            autoEnterDexArmed = false
        } else if (isColdCreate) {
            autoEnterDexArmed = AppPrefs.isAutoEnterDexEnabled(this)
        }
        if (openActivation) {
            openActivationPulse++
        }
        if (hasFocusTarget) {
            showSpotlight = false
            pendingFocusTab = focusTab
            pendingFocusSectionKey = focusSection.ifEmpty { null }
            pendingFocusPulse++
        }
    }

    override fun onResume() {
        super.onResume()
        BridgeClient.requestBinder(this)
        io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.refreshAccessState(this)
        io.github.lengsukq.opaldesk.notifications.OpalCallControlBridge.syncEnabledState(this)
        io.github.lengsukq.opaldesk.cast.BleNearbyService.syncEnabledState(this)
        localNetworkPermGranted = OpalPeerDiscovery.hasLocalNetworkPermission(this)
        if (localNetworkPermGranted) {
            OpalPeerDiscovery.startBrowsing(this, BridgeClient.remoteStatusFlow.value.lanIp)
        }
        if (AppPrefs.isUsbTetherCastEnabled(this)) {
            io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector.start(this)
        } else {
            io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector.stop()
        }
        Thread({
            BridgeClient.refreshStatus()
            BridgeClient.refreshSceneSnapshot()
            val r = BridgeClient.refreshRemoteStatus()
            BridgeClient.refreshTrustedPeers()
            val pending = BridgeClient.refreshPendingPairing()
            BridgeService.updatePairingNotification(this, pending)
            OpalPeerCastMutex.syncDaemonSendingState(r.connected, r.peerName)
            val hasLocalNet = OpalPeerDiscovery.hasLocalNetworkPermission(this)
            runOnUiThread { localNetworkPermGranted = hasLocalNet }
            if (hasLocalNet) {
                OpalPeerDiscovery.syncAdvertise(this, r.isLanShareActive, r.wifiPort, r.shortCertFingerprint)
                OpalPeerDiscovery.startBrowsing(this, r.lanIp)
            }
        }, "OpalConsoleRefresh").start()
    }

    override fun onStop() {
        autoEnterDexArmed = false
        OpalPeerDiscovery.stopBrowsing()
        io.github.lengsukq.opaldesk.cast.UsbTetherPeerDetector.stop()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BridgeClient.tryWakeHostScreenFromKeyEvent(event)) return true
        val isSpotlightChord = event.keyCode == KeyEvent.KEYCODE_K && (event.isCtrlPressed || event.isMetaPressed)
        if (isSpotlightChord && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            autoEnterDexArmed = false
            showSpotlight = !showSpotlight
            if (showSpotlight) spotlightController.resetSelection()
            consumedSpotlightToggleKeyUp = true
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_K && consumedSpotlightToggleKeyUp) {
            consumedSpotlightToggleKeyUp = false
            return true
        }
        if (showSpotlight && spotlightController.handleKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun launchPeerCastViewer(host: String, port: Int, label: String) {
        val remote = BridgeClient.remoteStatusFlow.value
        val selfIpToGuard = if (remote.usbTetherIp.isNotBlank() && host.trim() == remote.usbTetherIp) {
            remote.usbTetherIp
        } else {
            remote.lanIp
        }
        val check = OpalPeerCastMutex.validateStartReceiving(
            daemonSending = remote.connected,
            sendingPeerName = remote.peerName,
            targetHost = host,
            selfLanIp = selfIpToGuard,
        )
        if (!check.allowed) {
            Toast.makeText(this, check.reason, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, PeerCastViewerActivity::class.java).apply {
                putExtra(PeerCastViewerActivity.EXTRA_HOST, host)
                putExtra(PeerCastViewerActivity.EXTRA_PORT, port)
                putExtra(PeerCastViewerActivity.EXTRA_LABEL, label)
            },
        )
    }

    private fun launchDesktopWorkstation(forceLandscape: Boolean = false) {
        if (BridgeClient.remoteStatusFlow.value.connected) {
            Toast.makeText(
                this,
                getString(R.string.toast_cast_locks_local_desktop),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        startActivity(
            Intent(this, DesktopActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(DesktopActivity.EXTRA_FORCE_LANDSCAPE, forceLandscape)
            },
        )
    }

    private fun launchRemoteTrackpad() {
        if (!ExternalTrackpadLauncher.openPhoneTrackpad(this)) {
            Toast.makeText(this, getString(R.string.toast_not_in_external_or_cast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchExternalOrTrackpad(displayId: Int) {
        if (ExternalTrackpadBridge.sessionFlow.value.isPhysicalActive) {
            ExternalTrackpadLauncher.openPhoneTrackpad(this)
            return
        }
        val matched = BridgeService.detectedExternalDisplaysFlow.value.firstOrNull { it.displayId == displayId }
            ?: BridgeService.externalDisplayFlow.value
        val transport = matched?.transport ?: ExternalDisplayTransport.WIRED
        val presCapable = matched?.isPresentationCapable ?: true
        BridgeService.projectToDisplay(
            ctx = this,
            displayId = displayId,
            transport = transport,
            isPresentationCapable = presCapable,
        )
    }

    private fun launchSpecificExternalDisplay(
        displayId: Int,
        transport: ExternalDisplayTransport,
        forcePresentation: Boolean,
    ) {
        val activeSession = ExternalTrackpadBridge.sessionFlow.value
        if (activeSession.isPhysicalActive && activeSession.displayId == displayId) {
            ExternalTrackpadLauncher.openPhoneTrackpad(this)
            return
        }
        val matched = BridgeService.detectedExternalDisplaysFlow.value.firstOrNull { it.displayId == displayId }
            ?: BridgeService.externalDisplayFlow.value
        val presCapable = matched?.isPresentationCapable ?: true
        BridgeService.projectToDisplay(
            ctx = this,
            displayId = displayId,
            transport = transport,
            forcePresentation = forcePresentation,
            isPresentationCapable = presCapable,
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun scheduleSilentAutoBootstrapIfConfigured() {
        mainHandler.postDelayed({
            if (BridgeClient.statusFlow.value.connected || BridgeService.sUserStopping) return@postDelayed
            if (AppPrefs.isAutoShizukuEnabled(this) && ShizukuBootstrap.isReadyAndGranted()) {
                AdbBootstrap.shizukuBootstrapAsync(this, markAutoShizuku = true, listener = null)
            } else if (AppPrefs.isAutoRootEnabled(this) && AdbBootstrap.hasSuBinaryHint()) {
                AdbBootstrap.rootBootstrapAsync(
                    ctx = this,
                    markAutoRoot = true,
                    rootEnhance = AppPrefs.isRootEnhanceEnabled(this),
                    listener = null,
                )
            }
        }, 700L)
    }
}
