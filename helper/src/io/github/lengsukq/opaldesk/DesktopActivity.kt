package io.github.lengsukq.opaldesk

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
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
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
    companion object {
        const val EXTRA_FORCE_LANDSCAPE = "force_landscape"
    }

    private var imeVisible by mutableStateOf(false)
    private var activeImeController: io.github.lengsukq.opaldesk.ui.NativeImeHostController? = null
    private var composingText by mutableStateOf("")

    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread({
            val saved = WallpaperStorage.importWallpaperFromUri(this, uri)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (saved != null) "已更换桌面壁纸" else "无法读取所选图片",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "OpalWallpaperImport").start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpalLogStore.init(applicationContext)
        val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
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
            Toast.makeText(this, "投屏进行中，无法进入本机桌面，请使用本机触控板或先断开投屏", Toast.LENGTH_SHORT).show()
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
                    val focused = BridgeClient.sceneFlow.value.focusedWindow
                    if (focused != null && !focused.isMinimized) {
                        BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.SEND_BACK)
                    } else {
                        openConsole()
                    }
                }
            },
        )

        setContent {
            OpalTheme {
                val imeController = rememberNativeImeHostController()
                activeImeController = imeController
                val imeRequest by BridgeClient.imeBridgeRequestFlow.collectAsState()
                val scene by BridgeClient.sceneFlow.collectAsState()
                val remote by BridgeClient.remoteStatusFlow.collectAsState()
                var lastHandledImeSeq by remember {
                    mutableLongStateOf(BridgeClient.imeBridgeRequestFlow.value?.seq ?: 0L)
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
                    if (req.show) {
                        val focused = BridgeClient.sceneFlow.value.focusedWindow
                        if (focused != null && !focused.isMinimized
                            && (req.windowId <= 0 || req.windowId == focused.windowId)
                            && !OpalImeFocusTracker.localComposeTextFieldFocused
                        ) {
                            imeController.showKeyboard(req.inputType)
                        }
                    } else if (imeVisible) {
                        imeController.hideKeyboard()
                    }
                }

                LaunchedEffect(scene.focusedWindow?.windowId, scene.focusedWindow?.isMinimized) {
                    val focused = scene.focusedWindow
                    if (imeVisible && !OpalImeFocusTracker.localComposeTextFieldFocused
                        && (focused == null || focused.isMinimized)
                    ) {
                        imeController.hideKeyboard()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NativeImeBridgeHost(
                        controller = imeController,
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
                        imeActive = imeVisible,
                        onToggleSoftKeyboard = { imeController.toggleKeyboard(imeVisible) },
                        onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                        onOpenConsole = ::openConsole,
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
        }
    }

    override fun onResume() {
        super.onResume()
        if (BridgeClient.remoteStatusFlow.value.connected) {
            Toast.makeText(this, "投屏进行中，已锁定本机桌面", Toast.LENGTH_SHORT).show()
            BridgeClient.setLocalDesktopMousePinAsync(false)
            finish()
            return
        }
        applyImmersiveFullscreen()
        BridgeClient.requestBinder(this)
        BridgeClient.setLocalDesktopMousePinAsync(true)
        Thread({
            BridgeClient.refreshStatus()
            BridgeClient.refreshSceneSnapshot()
            val r = BridgeClient.refreshRemoteStatus()
            if (r.connected) {
                runOnUiThread {
                    BridgeClient.setLocalDesktopMousePinAsync(false)
                    finish()
                }
            }
        }, "OpalDesktopSync").start()
    }

    override fun onStop() {
        BridgeClient.setLocalDesktopMousePinAsync(false)
        super.onStop()
    }

    override fun onDestroy() {
        BridgeClient.setLocalDesktopMousePinAsync(false)
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
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true

        // Never swallow physical/Bluetooth keyboard IME language-switch shortcuts (Ctrl+Space,
        // Shift+Space, Meta+Space, LanguageSwitch); ensure the native IME host is active and let
        // Android's IME framework handle switching between Chinese and English.
        if (WorkstationKeyDispatcher.isImeSwitchShortcut(event.keyCode, event.metaState)) {
            if (event.action == KeyEvent.ACTION_DOWN && !imeVisible && !OpalImeFocusTracker.localComposeTextFieldFocused) {
                val focused = BridgeClient.sceneFlow.value.focusedWindow
                if (focused != null && !focused.isMinimized) activeImeController?.showKeyboard()
            }
            return super.dispatchKeyEvent(event)
        }

        // Let active Compose text boxes (e.g. App Drawer search) or active soft IME consume keystrokes first
        if ((OpalImeFocusTracker.localComposeTextFieldFocused || imeVisible) && super.dispatchKeyEvent(event)) {
            return true
        }

        // Forward physical/external keyboard keystrokes directly to the focused VirtualDisplay session
        // BEFORE super.dispatchKeyEvent can swallow Space/Enter/Tab/Arrows for Compose focus traversal
        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun openConsole() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
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
