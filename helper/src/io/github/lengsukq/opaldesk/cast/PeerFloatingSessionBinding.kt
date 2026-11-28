package io.github.lengsukq.opaldesk.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import kotlin.math.max
import kotlin.math.roundToInt

internal data class FloatingSessionCallbacks(
    val onWaitingForApproval: () -> Unit,
    val onAuthenticated: (peerName: String, authToken: String, permanent: Boolean) -> Unit,
    val onRemoteBlackoutChanged: (blackout: Boolean) -> Unit,
    val onVideoSizeChanged: (width: Int, height: Int, fps: Int) -> Unit,
    val onRemoteImeRequest: (show: Boolean, inputType: Int) -> Unit,
    val onError: (message: String) -> Unit,
    val onDisconnected: (reason: String) -> Unit,
)

internal fun createAndStartFloatingPeerClient(
    ctx: Context,
    mainHandler: Handler,
    host: String,
    port: Int,
    isMirrorMode: Boolean,
    appPackage: String,
    appActivity: String,
    appUserId: Int,
    expandedW: Int,
    expandedH: Int,
    handoverToken: String,
    existingWifiLock: WifiLowLatencyLock?,
    activeSurface: Surface?,
    callbacks: FloatingSessionCallbacks,
): Pair<OpalCastPeerClient, WifiLowLatencyLock> {
    val dm = ctx.resources.displayMetrics
    val (streamW, streamH, reqDpi) = if (isMirrorMode) {
        val maxEdge = max(dm.widthPixels, dm.heightPixels).coerceIn(1280, 2400)
        Triple(maxEdge, (maxEdge * 10 / 16).coerceAtLeast(720), dm.densityDpi.coerceIn(160, 480))
    } else {
        computeFloatingStreamSpec(ctx, expandedW, expandedH)
    }
    val savedTok = AppPrefs.getPeerAuthToken(ctx, host)
    val tokenToUse = savedTok.ifBlank { handoverToken }

    val boundNet = if (AppPrefs.isUsbTetherCastEnabled(ctx)) {
        UsbTetherPeerDetector.resolveBoundNetworkForHost(host)
    } else {
        null
    }
    val wifiLock = existingWifiLock ?: WifiLowLatencyLock(ctx, "OpalDesk:FloatingCastRx")
    if (boundNet == null) {
        wifiLock.acquire()
    } else {
        wifiLock.release()
    }
    val peerClient = OpalCastPeerClient(
        host = host,
        port = port,
        savedAuthToken = tokenToUse,
        clientId = AppPrefs.getOrCreatePeerClientId(ctx),
        reqWidth = streamW,
        reqHeight = streamH,
        reqDpi = reqDpi,
        reqFps = if (isMirrorMode) resolveFloatingDefaultRefreshRate(ctx) else 60,
        sessionRole = if (isMirrorMode) {
            OpalCastPeerClient.SESSION_ROLE_DESKTOP
        } else {
            OpalCastPeerClient.SESSION_ROLE_STANDALONE_APP
        },
        appPackage = if (isMirrorMode) "" else appPackage,
        appActivity = if (isMirrorMode) "" else appActivity,
        appUserId = if (isMirrorMode) 0 else appUserId,
        targetNetwork = boundNet,
        audioEnabled = isMirrorMode,
        initialDisplayMode = if (isMirrorMode) {
            OpalCastPeerClient.DISPLAY_MODE_MIRROR
        } else {
            OpalCastPeerClient.DISPLAY_MODE_DEX
        },
        listener = object : OpalCastPeerClient.Listener {
            override fun onWaitingForApproval() {
                mainHandler.post { callbacks.onWaitingForApproval() }
            }

            override fun onAuthenticated(
                peerName: String,
                authToken: String,
                permanent: Boolean,
                certFingerprint: String,
            ) {
                if (permanent && authToken.isNotBlank()) {
                    AppPrefs.savePeerAuthToken(ctx, host, authToken)
                }
                OpalPeerCastMutex.updateReceivingPeerName(peerName)
                mainHandler.post { callbacks.onAuthenticated(peerName, authToken, permanent) }
            }

            override fun onRemoteBlackoutChanged(blackout: Boolean) {
                if (!isMirrorMode) return
                mainHandler.post { callbacks.onRemoteBlackoutChanged(blackout) }
            }

            override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) {
                mainHandler.post { callbacks.onVideoSizeChanged(width, height, fps) }
            }

            override fun onRemoteImeRequest(show: Boolean, inputType: Int) {
                mainHandler.post { callbacks.onRemoteImeRequest(show, inputType) }
            }

            override fun onError(message: String) {
                mainHandler.post { callbacks.onError(message) }
            }

            override fun onDisconnected(reason: String) {
                mainHandler.post { callbacks.onDisconnected(reason) }
            }
        },
    )
    activeSurface?.let { s ->
        if (s.isValid) peerClient.attachSurface(s)
    }
    peerClient.start()
    return peerClient to wifiLock
}

internal fun resolveFloatingDarkTheme(ctx: Context): Boolean = when (AppPrefs.themeModeFlow.value) {
    AppPrefs.THEME_DARK -> true
    AppPrefs.THEME_SYSTEM ->
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    else -> false
}

internal fun resolveFloatingDefaultRefreshRate(ctx: Context): Int {
    return try {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        (defaultDisplay?.mode?.refreshRate?.roundToInt() ?: 60).coerceIn(60, 120)
    } catch (_: Throwable) {
        60
    }
}

internal fun buildFloatingImeInputBar(
    ctx: Context,
    isDark: Boolean,
    imeBarBg: Int,
    titleInk: Int,
    appLabel: String,
    onSendText: () -> Unit,
): Pair<LinearLayout, EditText> {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).roundToInt()
    val imeBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(imeBarBg)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        visibility = View.GONE
    }
    val edit = EditText(ctx).apply {
        hint = ctx.getString(R.string.floating_ime_hint, appLabel)
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
                onSendText()
                true
            } else {
                false
            }
        }
    }
    val sendBtn = TextView(ctx).apply {
        text = ctx.getString(R.string.common_send)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(0xFF007AFF.toInt())
            cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(12), dp(5), dp(12), dp(5))
        setOnClickListener { onSendText() }
    }
    imeBar.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    imeBar.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp(8)
    })
    return imeBar to edit
}

internal fun startFloatingForegroundNotification(
    service: Service,
    tag: String,
    channelId: String,
    notifId: Int,
    appTitle: String,
    hostName: String,
    mirror: Boolean,
) {
    val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    val ch = NotificationChannel(
        channelId,
        service.getString(R.string.floating_notif_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = service.getString(R.string.floating_notif_channel_desc)
    }
    nm.createNotificationChannel(ch)

    val openPi = PendingIntent.getActivity(
        service,
        3931,
        Intent(service, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val stopPi = PendingIntent.getService(
        service,
        3932,
        Intent(service, PeerFloatingAppService::class.java).apply { action = PeerFloatingAppService.ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val contentText = if (mirror) {
        service.getString(R.string.floating_notif_mirror_text, hostName)
    } else {
        service.getString(R.string.floating_notif_app_text, hostName)
    }
    val notif = Notification.Builder(service, channelId)
        .setSmallIcon(R.drawable.ic_stat_opaldesk)
        .setContentTitle(service.getString(R.string.floating_notif_title, appTitle))
        .setContentText(contentText)
        .setContentIntent(openPi)
        .addAction(Notification.Action.Builder(null, service.getString(R.string.floating_notif_close), stopPi).build())
        .setOngoing(true)
        .build()

    try {
        if (Build.VERSION.SDK_INT >= 34) {
            service.startForeground(
                notifId,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            service.startForeground(notifId, notif)
        }
    } catch (t: Throwable) {
        OpalLogStore.w(tag, "Floating foreground warning", t)
    }
}

internal fun expandFloatingMirrorToFullscreen(
    ctx: Context,
    tag: String,
    host: String,
    port: Int,
    peerLabel: String,
) {
    val label = peerLabel.ifBlank { host }
    val intent = Intent(ctx, PeerCastViewerActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(PeerCastViewerActivity.EXTRA_HOST, host)
        putExtra(PeerCastViewerActivity.EXTRA_PORT, port)
        putExtra(PeerCastViewerActivity.EXTRA_LABEL, label)
        putExtra(PeerCastViewerActivity.EXTRA_INITIAL_DISPLAY_MODE, OpalCastPeerClient.DISPLAY_MODE_MIRROR)
    }
    try {
        ctx.startActivity(intent)
    } catch (t: Throwable) {
        OpalLogStore.w(tag, "Failed to expand mirror to fullscreen: ${t.message}")
    }
}

internal fun applyFloatingWindowResizeAndClamp(
    ctx: Context,
    lp: WindowManager.LayoutParams,
    rv: View,
    targetW: Int,
    targetH: Int,
    onUpdated: (w: Int, h: Int, x: Int, y: Int) -> Unit,
    onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
) {
    val dm = ctx.resources.displayMetrics
    lp.width = targetW
    lp.height = targetH
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
    onUpdated(lp.width, lp.height, lp.x, lp.y)
    onUpdateLayout(rv, lp)
}

@Suppress("DEPRECATION")
internal fun showFloatingImeBarOverlay(
    ctx: Context,
    tag: String,
    mainHandler: Handler,
    lp: WindowManager.LayoutParams,
    rv: View,
    bar: LinearLayout,
    edit: EditText,
    isStillVisible: () -> Boolean,
    onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
) {
    OpalLogStore.i(tag, "Opening floating IME bar on receiver")
    bar.visibility = View.VISIBLE
    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
    onUpdateLayout(rv, lp)
    rv.requestLayout()
    edit.requestFocus()
    mainHandler.postDelayed({
        if (!isStillVisible()) return@postDelayed
        edit.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm != null && !imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)) {
            imm.showSoftInput(edit, InputMethodManager.SHOW_FORCED)
        }
    }, 120L)
}

internal fun hideFloatingImeBarOverlay(
    ctx: Context,
    lp: WindowManager.LayoutParams?,
    rv: View?,
    bar: LinearLayout?,
    edit: EditText?,
    onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
) {
    if (edit != null) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(edit.windowToken, 0)
        edit.clearFocus()
    }
    bar?.visibility = View.GONE
    if (lp != null && rv != null) {
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        onUpdateLayout(rv, lp)
    }
}

internal data class FloatingOverlayTheme(
    val isDark: Boolean,
    val containerBg: Int,
    val containerStroke: Int,
    val headerBg: Int,
    val dividerColor: Int,
    val titleInk: Int,
    val pillBg: Int,
    val pillBorder: Int,
    val pillInk: Int,
    val imeBarBg: Int,
    val canvasBg: Int,
    val bannerBg: Int,
)

internal fun resolveFloatingOverlayTheme(ctx: Context): FloatingOverlayTheme {
    val isDark = resolveFloatingDarkTheme(ctx)
    return FloatingOverlayTheme(
        isDark = isDark,
        containerBg = if (isDark) 0xF2191E2A.toInt() else 0xF5F6F9FE.toInt(),
        containerStroke = if (isDark) 0x3AFFFFFF else 0xC8D2DEEE.toInt(),
        headerBg = if (isDark) 0xEB222838.toInt() else 0xF0FFFFFF.toInt(),
        dividerColor = if (isDark) 0x24FFFFFF else 0x1A0F172A,
        titleInk = if (isDark) 0xFFF5F7FB.toInt() else 0xFF0F172A.toInt(),
        pillBg = if (isDark) 0x1CFFFFFF else 0x100F172A,
        pillBorder = if (isDark) 0x2EFFFFFF else 0x1E0F172A,
        pillInk = if (isDark) 0xFFD8E1F0.toInt() else 0xFF334155.toInt(),
        imeBarBg = if (isDark) 0xF5151A25.toInt() else 0xF5EEF3FA.toInt(),
        canvasBg = if (isDark) 0xFF0B0E15.toInt() else 0xFFF4F7FC.toInt(),
        bannerBg = if (isDark) 0xE6101520.toInt() else 0xEBF4F8FE.toInt(),
    )
}

internal fun buildFloatingRootContainer(
    ctx: Context,
    theme: FloatingOverlayTheme,
): LinearLayout {
    val density = ctx.resources.displayMetrics.density
    val cornerRadiusPx = (20 * density).roundToInt().toFloat()
    val strokePx = max(2, (1.25f * density).roundToInt())
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(theme.containerBg)
            cornerRadius = cornerRadiusPx
            setStroke(strokePx, theme.containerStroke)
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
}

internal fun buildFloatingOverlayLayoutParams(
    width: Int,
    height: Int,
    x: Int,
    y: Int,
): WindowManager.LayoutParams = WindowManager.LayoutParams(
    width,
    height,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.START
    this.x = x
    this.y = y
}

internal fun launchFloatingOverlayService(ctx: Context, intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.applicationContext.startForegroundService(intent)
    } else {
        ctx.applicationContext.startService(intent)
    }
}

internal fun startFloatingOverlayApp(
    ctx: Context,
    host: String,
    port: Int,
    peerLabel: String,
    appPackage: String,
    appActivity: String,
    appLabel: String,
    appUserId: Int,
    handoverToken: String,
    isMirror: Boolean,
): Boolean {
    if (!android.provider.Settings.canDrawOverlays(ctx)) return false
    val displayPeer = peerLabel.ifBlank { host }
    val intent = Intent(ctx.applicationContext, PeerFloatingAppService::class.java).apply {
        action = PeerFloatingAppService.ACTION_START
        putExtra(PeerFloatingAppService.EXTRA_HOST, host)
        putExtra(PeerFloatingAppService.EXTRA_PORT, port)
        putExtra(PeerFloatingAppService.EXTRA_PEER_LABEL, if (isMirror) displayPeer else peerLabel)
        putExtra(PeerFloatingAppService.EXTRA_APP_PACKAGE, if (isMirror) PeerFloatingAppService.MIRROR_SENTINEL_PKG else appPackage)
        putExtra(PeerFloatingAppService.EXTRA_APP_ACTIVITY, if (isMirror) "" else appActivity)
        putExtra(PeerFloatingAppService.EXTRA_APP_LABEL, if (isMirror) "$displayPeer · 主屏镜像" else appLabel)
        if (!isMirror) {
            putExtra(PeerFloatingAppService.EXTRA_APP_USER_ID, appUserId.coerceAtLeast(0))
        }
        putExtra(PeerFloatingAppService.EXTRA_HANDOVER_TOKEN, handoverToken)
        putExtra(PeerFloatingAppService.EXTRA_IS_MIRROR, isMirror)
    }
    launchFloatingOverlayService(ctx, intent)
    return true
}
