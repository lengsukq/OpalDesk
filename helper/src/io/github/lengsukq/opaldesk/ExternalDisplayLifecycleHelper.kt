package io.github.lengsukq.opaldesk

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.external.DirectExternalTrackpadSink
import io.github.lengsukq.opaldesk.external.ExternalDesktopMode
import io.github.lengsukq.opaldesk.external.ExternalDesktopSession
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalPointerDispatcher
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper
import kotlin.math.roundToInt

private const val EXT_LIFECYCLE_TAG = "OpalExtDesktop"
private const val SHELL_RELAUNCH_DELAY_MS = 140L

internal fun applyPreferredPeakDisplayMode(
    window: Window,
    targetDisplay: Display?,
    targetFps: Int = BridgeClient.statusFlow.value.targetFps,
) {
    val disp = targetDisplay ?: return
    val cur = disp.mode ?: return
    val supported = disp.supportedModes ?: return
    val candidates = supported.map {
        ExternalDisplayPolicy.DisplayModeCandidate(
            modeId = it.modeId,
            width = it.physicalWidth,
            height = it.physicalHeight,
            refreshRate = it.refreshRate,
        )
    }
    val chosen = ExternalDisplayPolicy.selectDisplayModeForTargetFps(
        modes = candidates,
        currentWidth = cur.physicalWidth,
        currentHeight = cur.physicalHeight,
        targetFps = targetFps,
    )
    val lp = window.attributes
    if (chosen == null) {
        if (lp.preferredDisplayModeId != 0 || lp.preferredRefreshRate != 0f) {
            lp.preferredDisplayModeId = 0
            lp.preferredRefreshRate = 0f
            window.attributes = lp
        }
        return
    }
    if (lp.preferredDisplayModeId != chosen.modeId || kotlin.math.abs(lp.preferredRefreshRate - chosen.refreshRate) > 0.5f) {
        lp.preferredDisplayModeId = chosen.modeId
        lp.preferredRefreshRate = chosen.refreshRate
        window.attributes = lp
    }
}

@Suppress("DEPRECATION")
internal fun maybeAutoNormalizeExternalDisplay(
    ctx: Context,
    targetDisplay: Display,
    primaryW: Int,
    primaryH: Int,
    lastAutoNormalizedDisplayId: Int,
): Int {
    if (!BridgeClient.statusFlow.value.connected) return lastAutoNormalizedDisplayId
    if (lastAutoNormalizedDisplayId == targetDisplay.displayId) return lastAutoNormalizedDisplayId
    val mode = targetDisplay.mode ?: return lastAutoNormalizedDisplayId
    val nativeW = mode.physicalWidth
    val nativeH = mode.physicalHeight
    if (nativeW < 320 || nativeH < 320) return lastAutoNormalizedDisplayId
    val realPt = android.graphics.Point()
    runCatching { targetDisplay.getRealSize(realPt) }
    val (extXDpi, extYDpi) = WirelessDisplayHelper.queryDisplayRealDpi(targetDisplay)
    val rawModes = targetDisplay.supportedModes
        .orEmpty()
        .map {
            ExternalDisplayPolicy.RawExternalMode(
                width = it.physicalWidth,
                height = it.physicalHeight,
                refreshRate = it.refreshRate,
                isSynthetic = WirelessDisplayHelper.isModeSyntheticOrAnisotropy(it),
            )
        }
    val hasAnisotropy = ExternalDisplayPolicy.detectAnisotropyScaleFactor(
        nativeW = nativeW,
        nativeH = nativeH,
        xDpi = extXDpi,
        yDpi = extYDpi,
        rawModes = rawModes,
    ) > ExternalDisplayPolicy.ANISOTROPY_DPI_RATIO_THRESHOLD
    val userNative1To1 = AppPrefs.isExternalNative1To1Enabled(ctx)
    if (!ExternalDisplayPolicy.shouldAutoNormalizeExternalOnEnter(
            nativeW = nativeW,
            nativeH = nativeH,
            realW = realPt.x,
            realH = realPt.y,
            primaryW = primaryW,
            primaryH = primaryH,
            hasAnisotropy = hasAnisotropy,
            userNative1To1Enabled = userNative1To1,
            autoCustomResolutionEnabled = AppPrefs.isAutoCustomResolutionEnabled(ctx),
        )
    ) {
        return lastAutoNormalizedDisplayId
    }
    val scalingMode = if (userNative1To1) {
        BridgeProtocol.ExternalScaling.NATIVE_1TO1
    } else {
        BridgeProtocol.ExternalScaling.SMART_AUTO
    }
    OpalLogStore.i(
        EXT_LIFECYCLE_TAG,
        "Auto-normalizing Presentation displayId=${targetDisplay.displayId} " +
            "real=${realPt.x}x${realPt.y} -> native=${nativeW}x${nativeH} " +
            "(anisotropy=$hasAnisotropy, native1To1=$userNative1To1)",
    )
    BridgeClient.setExternalDisplayModeAsync(
        displayId = targetDisplay.displayId,
        width = nativeW,
        height = nativeH,
        fps = 0f,
        scalingMode = scalingMode,
    )
    return targetDisplay.displayId
}

@Suppress("DEPRECATION")
internal fun buildAndPublishDirectSession(
    ctx: Context,
    displayManager: DisplayManager?,
    activityDisplay: Display?,
    currentDisplayId: Int,
    requestedWireless: Boolean,
    owner: Any,
): ExternalDesktopSession {
    if (currentDisplayId <= Display.DEFAULT_DISPLAY) {
        return ExternalDesktopSession()
    }
    val curDisplay = displayManager?.getDisplay(currentDisplayId)
        ?: activityDisplay?.takeIf { it.displayId == currentDisplayId }
    val curMode = curDisplay?.mode
    val realPt = android.graphics.Point()
    if (curDisplay != null) {
        runCatching { curDisplay.getRealSize(realPt) }
    }
    val (primaryW, primaryH) = WirelessDisplayHelper.queryPrimaryDisplaySizePx(displayManager)
    val (widthPx, heightPx) = ExternalDisplayPolicy.resolveEffectiveDisplaySize(
        modeW = curMode?.physicalWidth ?: ctx.resources.displayMetrics.widthPixels,
        modeH = curMode?.physicalHeight ?: ctx.resources.displayMetrics.heightPixels,
        realW = realPt.x,
        realH = realPt.y,
        primaryW = primaryW,
        primaryH = primaryH,
    )
    val hz = (curMode?.refreshRate ?: 60f).roundToInt().coerceAtLeast(24)
    val dispName = curDisplay?.name ?: "外接屏"
    val isRemoteCast = ExternalDisplayPolicy.isRemoteCastDisplay(
        displayId = currentDisplayId,
        displayName = dispName,
        remote = BridgeClient.remoteStatusFlow.value,
    )
    val inspectedTransport = curDisplay?.let { d ->
        ExternalDisplayPolicy.classifyExternalDisplay(
            displayId = d.displayId,
            isValid = d.isValid,
            name = d.name,
            flags = d.flags,
            state = d.state,
            displayType = WirelessDisplayHelper.queryDisplayTypeSafe(d),
        )
    }
    val isWireless = !isRemoteCast &&
        (requestedWireless || inspectedTransport == ExternalDisplayTransport.WIRELESS_TV)
    val session = ExternalDesktopSession(
        mode = ExternalDesktopMode.DIRECT_EXTENDED,
        displayId = currentDisplayId,
        displayName = dispName,
        widthPx = widthPx,
        heightPx = heightPx,
        refreshRateHz = hz,
        isRemoteCastDisplay = isRemoteCast,
        isWirelessDisplay = isWireless,
    )
    ExternalTrackpadBridge.publishSession(owner, session)
    return session
}

internal fun createDirectTrackpadSink(
    activity: ComponentActivity,
    extDisplayId: Int,
    dispatcher: ExternalPointerDispatcher,
): DirectExternalTrackpadSink = object : DirectExternalTrackpadSink {
    override val displayId: Int = extDisplayId
    override fun currentSurfaceWidthPx(): Int = dispatcher.currentSurfaceWidthPx()
    override fun currentSurfaceHeightPx(): Int = dispatcher.currentSurfaceHeightPx()
    override fun currentCursorX(): Float = dispatcher.cursorPosState.value.x
    override fun currentCursorY(): Float = dispatcher.cursorPosState.value.y
    override fun moveCursorBy(dxPx: Float, dyPx: Float) = activity.runOnUiThread { dispatcher.moveCursorBy(dxPx, dyPx) }
    override fun setPrimaryButton(pressed: Boolean) = activity.runOnUiThread { dispatcher.setPrimaryButton(pressed) }
    override fun tapPrimary() = activity.runOnUiThread { dispatcher.tapPrimary() }
    override fun scrollAtCursor(hScroll: Float, vScroll: Float) = activity.runOnUiThread { dispatcher.scrollAtCursor(hScroll, vScroll) }
    override fun hideCursor() = activity.runOnUiThread { dispatcher.hideCursor() }
    override fun closeExternalDesktop() = activity.runOnUiThread { activity.finish() }
}

internal fun findExternalDisplay(
    ctx: Context,
    displayManager: DisplayManager?,
    preferredId: Int,
): WirelessDisplayHelper.InspectedDisplay? {
    val dm = displayManager ?: return null
    val remoteDesktopId = BridgeClient.remoteStatusFlow.value.desktopDisplayId
    val candidates = WirelessDisplayHelper.inspectExternalDisplays(ctx, dm, remoteDesktopId)
    if (preferredId > Display.DEFAULT_DISPLAY) {
        candidates.firstOrNull { it.display.displayId == preferredId }?.let { return it }
    }
    return candidates.firstOrNull { ExternalDisplayPolicy.isAutoProjectable(it.transport) }
        ?: candidates.firstOrNull()
}

internal fun syncExternalMouseRouting(ctx: Context, extDisplayId: Int) {
    if (extDisplayId <= Display.DEFAULT_DISPLAY) return
    if (ExternalTrackpadBridge.sessionFlow.value.isRemoteCastDisplay) {
        // Isolate host USB/Bluetooth peripherals on Display 0 while casting to Windows/macOS
        // so attached USB mice do not jump onto the virtual cast display or paint a duplicate cursor.
        BridgeClient.releaseExternalMouseRoutingAsync()
        return
    }
    val mode = AppPrefs.getMouseTarget(ctx)
    BridgeClient.setMouseTargetAsync(mode, extDisplayId)
}

internal fun scheduleShellDirectLaunchFallback(
    appCtx: Context,
    targetId: Int,
    isWireless: Boolean,
    reason: String,
) {
    OpalLogStore.i(
        EXT_LIFECYCLE_TAG,
        "Escalating external desktop to shell-daemon Direct Extended launch on displayId=$targetId ($reason)",
    )
    Handler(Looper.getMainLooper()).postDelayed({
        BridgeClient.launchExternalDesktopOnDisplayAsync(targetId, isWireless) { ok ->
            if (!ok) {
                OpalLogStore.w(
                    EXT_LIFECYCLE_TAG,
                    "Escalated shell direct launch failed for displayId=$targetId -> reopening Display 0 fallback",
                )
                val fallbackIntent = Intent(appCtx, ExternalDesktopActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                    putExtra(ExternalDesktopActivity.EXTRA_TARGET_DISPLAY_ID, targetId)
                    putExtra(ExternalDesktopActivity.EXTRA_PREFER_PRESENTATION, false)
                    putExtra(ExternalDesktopActivity.EXTRA_IS_WIRELESS_DISPLAY, isWireless)
                    putExtra(ExternalDesktopActivity.EXTRA_SHELL_LAUNCHED, true)
                }
                DisplayLaunchHelper.launchOnDisplay0(appCtx, fallbackIntent)
            }
        }
    }, SHELL_RELAUNCH_DELAY_MS)
}

internal fun openConsoleOnPrimaryDisplay(activity: ComponentActivity) {
    val intent = MainActivity.createLaunchIntent(activity, fromDesktop = true)
    try {
        val opts = ActivityOptions.makeBasic()
            .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            .toBundle()
        activity.startActivity(intent, opts)
    } catch (_: Throwable) {
        activity.startActivity(intent)
    }
}

internal fun openConsoleSectionOnPrimaryDisplay(
    activity: ComponentActivity,
    tabIndex: Int,
    sectionKey: String,
) {
    val intent = MainActivity.createLaunchIntent(
        ctx = activity,
        fromDesktop = true,
        focusTab = tabIndex,
        focusSectionKey = sectionKey,
    )
    try {
        val opts = ActivityOptions.makeBasic()
            .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            .toBundle()
        activity.startActivity(intent, opts)
    } catch (_: Throwable) {
        activity.startActivity(intent)
    }
}

internal fun setDirectWindowNotFocusable(window: Window, notFocusable: Boolean) {
    val hasFlag = (window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
    if (notFocusable == hasFlag) return
    if (notFocusable) {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}

internal fun applyImmersiveFullscreen(window: Window) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val decor = window.decorView
    WindowCompat.getInsetsController(window, decor).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

internal fun syncExternalDesktopAudioRoute(ctx: Context, entering: Boolean) {
    if (entering) {
        if (AppPrefs.isForcePhoneSpeakerEnabled(ctx)) BridgeClient.setForcePhoneSpeakerAsync(ctx, enabled = true, persist = true)
    } else if (AppPrefs.isForcePhoneSpeakerEnabled(ctx)) {
        BridgeClient.setForcePhoneSpeakerAsync(ctx, enabled = false, persist = false)
    }
}
