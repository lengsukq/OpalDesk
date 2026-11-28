package io.github.lengsukq.opaldesk

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lengsukq.opaldesk.compositor.OpalCompositorWorkspace
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.external.ExternalDesktopMode
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.NativeImeBridgeHost
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.ui.rememberNativeImeHostController

/**
 * Primary on-device Fullscreen Immersive Multi-Window Workstation Host (Display 0).
 *
 * Hosts [OpalCompositorWorkspace] at native display refresh rate (60–144Hz), eliminates
 * `SYSTEM_ALERT_WINDOW` split-brain overlays, and routes physical keyboard shortcuts
 * (`Alt+Tab`, `Alt+2/3/4/G` tiling, `Ctrl+W`, `Back`, and typing) into the active `VirtualDisplay` window session.
 */
class DesktopActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(OpalLocalePolicy.wrapContext(newBase))
    }

    companion object {
        const val EXTRA_FORCE_LANDSCAPE = "force_landscape"
    }

    private var imeVisible by mutableStateOf(false)
    private var localOverlayNeedsFocus by mutableStateOf(false)
    private var activeImeController: io.github.lengsukq.opaldesk.ui.NativeImeHostController? = null
    private var composingText by mutableStateOf("")

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpalLogStore.init(applicationContext)
        val currentDisplayId = display?.displayId
            ?: Display.DEFAULT_DISPLAY
        OpalLogStore.i("Desktop", "DesktopActivity.onCreate displayId=$currentDisplayId")
        if (currentDisplayId > Display.DEFAULT_DISPLAY) {
            val extIntent = Intent(this, ExternalDesktopActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("targetDisplayId", currentDisplayId)
            }
            DisplayLaunchHelper.launchOnDisplay(this, extIntent, currentDisplayId)
            finish()
            return
        }
        if (BridgeClient.remoteStatusFlow.value.connected) {
            Toast.makeText(this, getString(R.string.toast_cast_locks_local_desktop), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        applyOrientationPreference(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        BridgeClient.requestBinder(this)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    WorkstationKeyDispatcher.handleDesktopBack(
                        hostSoftImeVisible = imeVisible,
                        onHideHostSoftIme = { activeImeController?.hideKeyboard() },
                    )
                }
            },
        )

        setContent {
            OpalTheme {
                val imeController = rememberNativeImeHostController()
                activeImeController = imeController
                val status by BridgeClient.statusFlow.collectAsState()
                val imeState by BridgeClient.imeStateFlow.collectAsState()
                val imeRequest by BridgeClient.imeBridgeRequestFlow.collectAsState()
                val scene by BridgeClient.sceneFlow.collectAsState()
                val remote by BridgeClient.remoteStatusFlow.collectAsState()
                val localTextFieldFocused by OpalImeFocusTracker.localComposeTextFieldFocusedFlow.collectAsState()
                val pluginState by io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.pluginStateFlow.collectAsState()
                val pluginSoftKbdVisible by io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.softKeyboardVisibleFlow.collectAsState()
                val inputBindings by BridgeClient.inputBindingsFlow.collectAsState()
                var lastHandledImeSeq by remember {
                    mutableLongStateOf(BridgeClient.imeBridgeRequestFlow.value?.seq ?: 0L)
                }

                val focusedLiveDisplayId = scene.focusedWindow
                    ?.takeIf { !it.isMinimized && it.isLive }
                    ?.displayId ?: -1
                val hostNotFocusable = WorkstationKeyDispatcher.shouldSetCompositorWindowNotFocusable(
                    focusedWindowDisplayId = focusedLiveDisplayId,
                    keyboardRoutedDisplayId = scene.keyboardRoutedDisplayId,
                    bridgeImeActive = imeState.bridgeImeActive ||
                        imeState.effectiveWindowImePolicy == BridgeProtocol.DisplayImePolicy.LOCAL,
                    localOverlayNeedsFocus = localOverlayNeedsFocus,
                    localTextFieldFocused = localTextFieldFocused,
                    hostSoftImeVisible = imeVisible,
                    hasEnabledKeyBindings = io.github.lengsukq.opaldesk.input.hasEnabledKeyBinding(inputBindings),
                )
                LaunchedEffect(hostNotFocusable) {
                    setHostWindowNotFocusable(hostNotFocusable)
                }

                // Sync host panel refresh rate with the user's targetFps so LTPO displays don't stay throttled at 60Hz
                LaunchedEffect(status.targetFps) {
                    applyPreferredDisplayRefreshRate(status.targetFps)
                }

                // Never allow Local DeX to stay active concurrently with an outgoing remote cast
                LaunchedEffect(remote.connected) {
                    if (remote.connected) {
                        finish()
                    }
                }

                LaunchedEffect(imeRequest?.seq) {
                    val req = imeRequest ?: return@LaunchedEffect
                    if (req.seq <= lastHandledImeSeq) return@LaunchedEffect
                    lastHandledImeSeq = req.seq
                    if (io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.isPluginSessionConnected) {
                        if (!req.show) {
                            io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.hideSoftKeyboard()
                            if (imeVisible) imeController.hideKeyboard()
                        }
                        return@LaunchedEffect
                    }
                    if (req.show) {
                        val activeLocally = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                            !BridgeClient.remoteStatusFlow.value.connected &&
                            ExternalTrackpadBridge.sessionFlow.value.mode == ExternalDesktopMode.NONE
                        val focused = BridgeClient.sceneFlow.value.focusedWindow
                        if (activeLocally &&
                            focused != null && !focused.isMinimized &&
                            (req.windowId <= 0 || req.windowId == focused.windowId) &&
                            !OpalImeFocusTracker.localComposeTextFieldFocused
                        ) {
                            setHostWindowNotFocusable(false)
                            imeController.showKeyboard(req.inputType)
                        }
                    } else if (imeVisible) {
                        imeController.hideKeyboard()
                    }
                }

                LaunchedEffect(scene.focusedWindow?.windowId, scene.focusedWindow?.isMinimized) {
                    val focused = scene.focusedWindow
                    if (focused == null || focused.isMinimized) {
                        io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.hideSoftKeyboard()
                        if (imeVisible && !OpalImeFocusTracker.localComposeTextFieldFocused) {
                            imeController.hideKeyboard()
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NativeImeBridgeHost(
                        controller = imeController,
                        hostWindow = window,
                        onSendKey = { action, keyCode, meta ->
                            val focused = BridgeClient.sceneFlow.value.focusedWindow
                            if (focused != null && !focused.isMinimized) {
                                BridgeClient.injectWindowKeyAsync(focused.windowId, action, keyCode, meta)
                            }
                        },
                        onCommitText = { txt ->
                            WorkstationKeyDispatcher.commitTextToFocusedWindow(this@DesktopActivity, txt)
                        },
                        onComposingChanged = { composingText = it },
                        onImeVisibilityChanged = { vis ->
                            imeVisible = vis
                            if (!vis) composingText = ""
                        },
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                    OpalCompositorWorkspace(
                        isExternalMonitor = false,
                        imeActive = imeVisible || (pluginState.pluginConnected && pluginSoftKbdVisible),
                        onToggleSoftKeyboard = {
                            if (io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.isPluginSessionConnected) {
                                io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.toggleSoftKeyboard()
                            } else {
                                if (!imeVisible) setHostWindowNotFocusable(false)
                                imeController.toggleKeyboard(imeVisible)
                            }
                        },
                        onLocalOverlayNeedsFocusChanged = { localOverlayNeedsFocus = it },
                        onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                        onOpenConsole = ::openConsole,
                        onOpenConsoleSection = { tab, sectionKey -> openConsoleSection(tab, sectionKey) },
                        onExitDesktop = { finish() },
                    )
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
                }
            }
        }
        applyImmersiveFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOrientationPreference(intent)
    }

    private fun applyOrientationPreference(srcIntent: Intent?) {
        val forceLandscape = srcIntent?.getBooleanExtra(EXTRA_FORCE_LANDSCAPE, false) == true
        requestedOrientation = if (forceLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStart() {
        super.onStart()
        if (!BridgeClient.remoteStatusFlow.value.connected) {
            BridgeClient.setLocalDesktopMousePinAsync(true)
            BridgeClient.setDexImeSessionActive("local_desktop", true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (BridgeClient.remoteStatusFlow.value.connected) {
            Toast.makeText(this, "投屏进行中，已锁定本机桌面", Toast.LENGTH_SHORT).show()
            BridgeClient.setLocalDesktopMousePinAsync(false)
            BridgeClient.setDexImeSessionActive("local_desktop", false)
            finish()
            return
        }
        applyImmersiveFullscreen()
        applyPreferredDisplayRefreshRate(BridgeClient.statusFlow.value.targetFps)
        BridgeClient.requestBinder(this)
        BridgeClient.setLocalDesktopMousePinAsync(true)
        BridgeClient.setDexImeSessionActive("local_desktop", true)
        Thread({
            BridgeClient.refreshStatus()
            BridgeClient.refreshSceneSnapshot()
            BridgeClient.fetchInputBindingsAsync()
            val r = BridgeClient.refreshRemoteStatus()
            if (r.connected) {
                runOnUiThread {
                    BridgeClient.setLocalDesktopMousePinAsync(false)
                    BridgeClient.setDexImeSessionActive("local_desktop", false)
                    finish()
                }
            }
        }, "OpalDesktopSync").start()
    }

    override fun onStop() {
        BridgeClient.setLocalDesktopMousePinAsync(false)
        BridgeClient.setDexImeSessionActive("local_desktop", false)
        super.onStop()
    }

    override fun onDestroy() {
        BridgeClient.setLocalDesktopMousePinAsync(false)
        BridgeClient.setDexImeSessionActive("local_desktop", false)
        if (BridgeClient.statusFlow.value.hostScreenOff) {
            BridgeClient.setHostScreenPowerAsync(true)
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BridgeClient.onTrimMemory(level)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BridgeClient.tryWakeHostScreenFromKeyEvent(event)) return true
        if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true
        if (WorkstationKeyDispatcher.handleActiveSpotlightKeyEvent(event)) return true

        val isPhysicalKey = WorkstationKeyDispatcher.isPhysicalKeyboardEvent(event)
        val forwardImeSwitch = WorkstationKeyDispatcher.shouldForwardImeSwitchToWindow(
            localComposeTextFieldFocused = OpalImeFocusTracker.localComposeTextFieldFocused,
            hostSoftImeVisible = imeVisible,
            isPhysicalKeyEvent = isPhysicalKey,
        )

        // When a physical keyboard is typing directly into a managed VirtualDisplay window (with
        // DISPLAY_IME_POLICY_LOCAL), forward Ctrl+Space / Shift+Space / LanguageSwitch directly to
        // the window so its local CJK IME toggles cleanly instead of hijacking Display 0 focus.
        if (WorkstationKeyDispatcher.isImeSwitchShortcut(event.keyCode, event.metaState)) {
            if (forwardImeSwitch && WorkstationKeyDispatcher.forwardToFocusedWindow(event, forwardImeSwitch = true)) {
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && !imeVisible && !OpalImeFocusTracker.localComposeTextFieldFocused && !isPhysicalKey) {
                val focused = BridgeClient.sceneFlow.value.focusedWindow
                if (focused != null && !focused.isMinimized) activeImeController?.showKeyboard()
            }
            return super.dispatchKeyEvent(event)
        }

        // Keep all keystrokes (both ACTION_DOWN and ACTION_UP) inside focused local Compose text boxes
        // so unconsumed ACTION_UP events from BasicTextField never fall through to background windows.
        if (OpalImeFocusTracker.localComposeTextFieldFocused) {
            if (super.dispatchKeyEvent(event)) return true
            if (event.keyCode == KeyEvent.KEYCODE_BACK && WorkstationKeyDispatcher.hasActiveLocalOverlay()) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    WorkstationKeyDispatcher.dismissActiveLocalOverlay()
                }
                return true
            }
            return false
        }

        if (imeVisible && super.dispatchKeyEvent(event)) {
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK && WorkstationKeyDispatcher.hasActiveLocalOverlay()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                WorkstationKeyDispatcher.dismissActiveLocalOverlay()
            }
            return true
        }

        // Forward physical/external keyboard keystrokes directly to the focused VirtualDisplay session
        // BEFORE super.dispatchKeyEvent can swallow Space/Enter/Tab/Arrows for Compose focus traversal
        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event, forwardImeSwitch = forwardImeSwitch)) {
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                WorkstationKeyDispatcher.handleDesktopBack(
                    hostSoftImeVisible = imeVisible,
                    onHideHostSoftIme = { activeImeController?.hideKeyboard() },
                )
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent?): Boolean {
        if (super.dispatchGenericMotionEvent(ev)) return true
        return io.github.lengsukq.opaldesk.input.LocalInputBindingExecutor.dispatchDesktopFallbackMouseGenericMotion(ev)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (super.dispatchTouchEvent(ev)) return true
        return io.github.lengsukq.opaldesk.input.LocalInputBindingExecutor.shouldConsumeDesktopFallbackMouseTouch(ev)
    }

    private fun openConsole() {
        startActivity(
            MainActivity.createLaunchIntent(this, fromDesktop = true),
        )
    }

    private fun openConsoleSection(tabIndex: Int, sectionKey: String) {
        startActivity(
            MainActivity.createLaunchIntent(
                ctx = this,
                fromDesktop = true,
                focusTab = tabIndex,
                focusSectionKey = sectionKey,
            ),
        )
    }

    private fun applyPreferredDisplayRefreshRate(targetFps: Int) {
        val disp = display ?: return
        val cur = disp.mode ?: return
        val supported = disp.supportedModes ?: return
        val candidates = supported.map {
            io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy.DisplayModeCandidate(
                modeId = it.modeId,
                width = it.physicalWidth,
                height = it.physicalHeight,
                refreshRate = it.refreshRate,
            )
        }
        val chosen = io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy.selectDisplayModeForTargetFps(
            modes = candidates,
            currentWidth = cur.physicalWidth,
            currentHeight = cur.physicalHeight,
            targetFps = targetFps,
        ) ?: return
        val lp = window.attributes
        if (lp.preferredDisplayModeId != chosen.modeId || kotlin.math.abs(lp.preferredRefreshRate - chosen.refreshRate) > 0.5f) {
            lp.preferredDisplayModeId = chosen.modeId
            lp.preferredRefreshRate = chosen.refreshRate
            window.attributes = lp
        }
    }

    private fun setHostWindowNotFocusable(notFocusable: Boolean) {
        val hasFlag = (window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        if (notFocusable == hasFlag) return
        if (notFocusable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val decor = window.decorView
        WindowCompat.getInsetsController(window, decor).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
