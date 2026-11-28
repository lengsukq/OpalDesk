package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opaldesk.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal data class FloatingHeaderRefs(
    val headerView: LinearLayout,
    val statusDotView: View,
    val titleTextView: TextView,
    val blackoutBtnContainer: FrameLayout?,
    val blackoutIconView: MirrorGlyphIconView?,
    val imeBtnContainer: FrameLayout?,
    val imeIconView: MirrorGlyphIconView?,
)

internal fun buildFloatingHeaderBar(
    ctx: Context,
    isMirrorMode: Boolean,
    isDark: Boolean,
    headerBg: Int,
    titleInk: Int,
    pillBg: Int,
    pillBorder: Int,
    pillInk: Int,
    initialTitle: String,
    remoteBlackout: Boolean,
    onClose: () -> Unit,
    onCollapse: () -> Unit,
    onZoomOrRotate: () -> Unit,
    onToggleBlackout: () -> Unit,
    onBack: () -> Unit,
    onToggleIme: () -> Unit,
): FloatingHeaderRefs {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).roundToInt()

    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(headerBg)
        val padStart = if (isMirrorMode) dp(10) else dp(12)
        val padEnd = if (isMirrorMode) dp(8) else dp(10)
        setPadding(padStart, dp(6), padEnd, dp(6))
    }

    val lightsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val lightPx = if (isMirrorMode) dp(12) else dp(13)
    val lightGap = if (isMirrorMode) dp(6) else dp(7)
    val lightTail = if (isMirrorMode) dp(8) else dp(10)
    lightsRow.addView(
        buildFloatingTrafficLightDot(ctx, 0xFFFF5F57.toInt(), 0xFFE0443E.toInt(), onClose),
        LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightGap },
    )
    lightsRow.addView(
        buildFloatingTrafficLightDot(ctx, 0xFFFEBC2E.toInt(), 0xFFD89E24.toInt(), onCollapse),
        LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightGap },
    )
    lightsRow.addView(
        buildFloatingTrafficLightDot(ctx, 0xFF28C840.toInt(), 0xFF1AAB29.toInt(), onZoomOrRotate),
        LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightTail },
    )
    header.addView(lightsRow)

    val dot = View(ctx).apply {
        background = floatingCircleDrawable(0xFFF59E0B.toInt())
    }
    header.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
        rightMargin = if (isMirrorMode) dp(4) else dp(6)
    })

    val titleTv = TextView(ctx).apply {
        text = initialTitle
        setTextColor(titleInk)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isMirrorMode) 12f else 12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        rightMargin = dp(4)
    })

    var blackoutBtn: FrameLayout? = null
    var blackoutGlyph: MirrorGlyphIconView? = null
    var imeBtn: FrameLayout? = null
    var imeGlyph: MirrorGlyphIconView? = null

    if (isMirrorMode) {
        val (bBtn, bGlyph) = buildFloatingChromeIconButton(
            context = ctx,
            glyph = MirrorGlyph.SCREEN_OFF,
            contentDesc = ctx.getString(if (remoteBlackout) R.string.peercast_screen_on else R.string.peercast_screen_off),
            iconSizeDp = 13.5f,
            isDark = isDark,
            onClick = onToggleBlackout,
        )
        blackoutBtn = bBtn
        blackoutGlyph = bGlyph
        header.addView(bBtn, LinearLayout.LayoutParams(dp(30), dp(26)).apply { rightMargin = dp(5) })

        val (iBtn, iGlyph) = buildFloatingChromeIconButton(
            context = ctx,
            glyph = MirrorGlyph.KEYBOARD,
            contentDesc = ctx.getString(R.string.taskbar_fs_ime),
            iconSizeDp = 14f,
            isDark = isDark,
            onClick = onToggleIme,
        )
        imeBtn = iBtn
        imeGlyph = iGlyph
        header.addView(iBtn, LinearLayout.LayoutParams(dp(30), dp(26)))
    } else {
        header.addView(
            buildFloatingHeaderPill(ctx, ctx.getString(R.string.common_back), pillBg, pillBorder, pillInk, onBack),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(5)
            },
        )
        header.addView(
            buildFloatingHeaderPill(ctx, ctx.getString(R.string.taskbar_fs_ime), pillBg, pillBorder, pillInk, onToggleIme),
        )
    }

    return FloatingHeaderRefs(
        headerView = header,
        statusDotView = dot,
        titleTextView = titleTv,
        blackoutBtnContainer = blackoutBtn,
        blackoutIconView = blackoutGlyph,
        imeBtnContainer = imeBtn,
        imeIconView = imeGlyph,
    )
}

internal data class FloatingBodyRefs(
    val bodyView: FrameLayout,
    val textureView: TextureView,
    val overlayBanner: LinearLayout,
    val overlayBannerText: TextView,
    val overlayRetryBtn: TextView,
)

@SuppressLint("ClickableViewAccessibility")
internal fun buildFloatingContentBody(
    ctx: Context,
    canvasBg: Int,
    bannerBg: Int,
    titleInk: Int,
    getClient: () -> OpalCastPeerClient?,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onFirstFrameUpdated: () -> Unit,
    onRetry: () -> Unit,
): FloatingBodyRefs {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).roundToInt()
    val body = FrameLayout(ctx).apply {
        setBackgroundColor(canvasBg)
    }
    val tv = TextureView(ctx).apply {
        isOpaque = true
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val c = getClient()
                val rw = c?.remoteWidth ?: width
                val rh = c?.remoteHeight ?: height
                if (rw >= 2 && rh >= 2) {
                    try { st.setDefaultBufferSize(rw, rh) } catch (_: Throwable) {}
                }
                onSurfaceReady(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                val c = getClient() ?: return
                val rw = c.remoteWidth
                val rh = c.remoteHeight
                if (rw >= 2 && rh >= 2) {
                    try { st.setDefaultBufferSize(rw, rh) } catch (_: Throwable) {}
                }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                onSurfaceDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                onFirstFrameUpdated()
            }
        }
        setOnTouchListener { v, ev ->
            val c = getClient() ?: return@setOnTouchListener true
            if (c.isMouseMotion(ev)) c.sendMouseEvent(ev, v.width, v.height)
            else c.sendTouchEvent(ev, v.width, v.height)
            true
        }
        setOnGenericMotionListener { v, ev ->
            val c = getClient() ?: return@setOnGenericMotionListener false
            c.handleGenericMouseMotion(ev, v.width, v.height)
        }
    }
    body.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val banner = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(bannerBg)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }
    val bannerTv = TextView(ctx).apply {
        text = "正在连接…"
        setTextColor(titleInk)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
    }
    val retryBtn = TextView(ctx).apply {
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
        setOnClickListener { onRetry() }
    }
    banner.addView(bannerTv)
    banner.addView(retryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
    })
    body.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    return FloatingBodyRefs(
        bodyView = body,
        textureView = tv,
        overlayBanner = banner,
        overlayBannerText = bannerTv,
        overlayRetryBtn = retryBtn,
    )
}

@SuppressLint("ClickableViewAccessibility")
internal fun installFloatingDragMoveListener(
    ctx: Context,
    handle: View,
    getLayoutParams: () -> WindowManager.LayoutParams?,
    getRootView: () -> View?,
    onPositionChanged: (x: Int, y: Int) -> Unit,
    onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
) {
    var downRawX = 0f
    var downRawY = 0f
    var startX = 0
    var startY = 0
    handle.setOnTouchListener { _, ev ->
        val lp = getLayoutParams() ?: return@setOnTouchListener false
        val rv = getRootView() ?: return@setOnTouchListener false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = lp.x
                startY = lp.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dm = ctx.resources.displayMetrics
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
                onPositionChanged(lp.x, lp.y)
                onUpdateLayout(rv, lp)
                true
            }
            else -> false
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun installFloatingResizeListener(
    ctx: Context,
    grip: View,
    getLayoutParams: () -> WindowManager.LayoutParams?,
    getRootView: () -> View?,
    isMirrorMode: () -> Boolean,
    getMirrorVideoSize: () -> Pair<Int, Int>,
    getChromeHeightPx: () -> Int,
    onSizeChanged: (w: Int, h: Int) -> Unit,
    onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
    onCommitResize: () -> Unit,
) {
    var downRawX = 0f
    var downRawY = 0f
    var startW = 0
    var startH = 0
    grip.setOnTouchListener { _, ev ->
        val lp = getLayoutParams() ?: return@setOnTouchListener false
        val rv = getRootView() ?: return@setOnTouchListener false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startW = lp.width
                startH = lp.height
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dm = ctx.resources.displayMetrics
                val clamped = if (isMirrorMode()) {
                    val (vw, vh) = getMirrorVideoSize()
                    FloatingOverlayGeometry.clampAspectLockedResizeSize(
                        targetW = startW + (ev.rawX - downRawX).roundToInt(),
                        targetH = startH + (ev.rawY - downRawY).roundToInt(),
                        videoW = vw,
                        videoH = vh,
                        chromeHeightPx = getChromeHeightPx(),
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
                onSizeChanged(lp.width, lp.height)
                onUpdateLayout(rv, lp)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isMirrorMode()) {
                    onCommitResize()
                }
                true
            }
            else -> false
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun buildFloatingCollapseBubble(
    ctx: Context,
    isMirrorMode: Boolean,
    appLabel: String,
    savedX: Int,
    savedY: Int,
    onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
    onExpand: () -> Unit,
): Pair<TextView, WindowManager.LayoutParams> {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).roundToInt()
    val initial = if (isMirrorMode) "镜" else appLabel.trim().take(1).ifEmpty { "浮" }
    val pill = TextView(ctx).apply {
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

    val slop = ViewConfiguration.get(ctx).scaledTouchSlop
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
                    val dm = ctx.resources.displayMetrics
                    val clamped = FloatingOverlayGeometry.clampBubblePosition(
                        targetX = startX + dx,
                        targetY = startY + dy,
                        screenW = dm.widthPixels,
                        screenH = dm.heightPixels,
                        density = dm.density,
                    )
                    bLp.x = clamped.x
                    bLp.y = clamped.y
                    onUpdateLayout(pill, bLp)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) onExpand()
                true
            }
            else -> false
        }
    }
    return pill to bLp
}

internal fun buildFloatingResizeGripView(ctx: Context, isDark: Boolean): View {
    val density = ctx.resources.displayMetrics.density
    val gripTint = if (isDark) 0x77CBD5E1.toInt() else 0x66475569.toInt()
    return object : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gripTint
            strokeWidth = (1.65f * density).coerceAtLeast(2.0f)
            strokeCap = Paint.Cap.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = (7 * density).roundToInt().toFloat()
            val w = width.toFloat() - pad
            val h = height.toFloat() - pad
            val span = (9 * density).roundToInt().toFloat()
            canvas.drawLine(w - span, h, w, h - span, paint)
            canvas.drawLine(w - span * 0.50f, h, w, h - span * 0.50f, paint)
        }
    }
}

internal fun buildFloatingMirrorBottomBar(
    ctx: Context,
    isDark: Boolean,
    headerBg: Int,
    resizeGrip: View,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
): FrameLayout {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).roundToInt()
    val bottomFrame = FrameLayout(ctx).apply {
        setBackgroundColor(headerBg)
    }
    val capsuleBg = if (isDark) 0x16FFFFFF else 0xCCECF1F9.toInt()
    val capsuleRim = if (isDark) 0x26FFFFFF else 0x200F172A
    val sepColor = if (isDark) 0x1FFFFFFF else 0x1A0F172A

    val navCapsule = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(capsuleBg)
            cornerRadius = dp(15).toFloat()
            setStroke(max(1, (0.8f * density).roundToInt()), capsuleRim)
        }
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    fun addNavDivider() {
        navCapsule.addView(
            View(ctx).apply { setBackgroundColor(sepColor) },
            LinearLayout.LayoutParams(max(1, (0.8f * density).roundToInt()), dp(12)),
        )
    }

    navCapsule.addView(
        buildFloatingDockNavIconButton(ctx, MirrorGlyph.BACK, "返回", isDark, onBack),
        LinearLayout.LayoutParams(dp(46), dp(26)),
    )
    addNavDivider()
    navCapsule.addView(
        buildFloatingDockNavIconButton(ctx, MirrorGlyph.HOME, "主页", isDark, onHome),
        LinearLayout.LayoutParams(dp(46), dp(26)),
    )
    addNavDivider()
    navCapsule.addView(
        buildFloatingDockNavIconButton(ctx, MirrorGlyph.RECENTS, "多任务", isDark, onRecents),
        LinearLayout.LayoutParams(dp(46), dp(26)),
    )

    bottomFrame.addView(
        navCapsule,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30), Gravity.CENTER),
    )
    bottomFrame.addView(
        resizeGrip,
        FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END),
    )
    return bottomFrame
}

/**
 * Computes encoder-aligned `(streamW, streamH, reqDpi)` matched 1:1 to this floating window's
 * physical dp dimensions on the receiver screen so text renders at native readable scale
 * (never microscopic on high-DPI tablets) and remains safe even on narrow windows.
 */
internal fun computeFloatingStreamSpec(ctx: Context, viewW: Int, viewH: Int): Triple<Int, Int, Int> {
    val dm = ctx.resources.displayMetrics
    val density = dm.density.coerceAtLeast(1f)
    val contentH = max((180 * density).roundToInt(), viewH - (41 * density).roundToInt())
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

internal data class InitialFloatingBounds(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
)

internal fun computeInitialFloatingBounds(
    ctx: Context,
    isMirrorMode: Boolean,
    isLandscape: Boolean,
    mirrorVideoW: Int,
    mirrorVideoH: Int,
    chromeHeightPx: Int,
): InitialFloatingBounds {
    val dm = ctx.resources.displayMetrics
    val initialSize = if (isMirrorMode) {
        FloatingOverlayGeometry.computeAspectMatchedWindowSize(
            videoW = mirrorVideoW,
            videoH = mirrorVideoH,
            chromeHeightPx = chromeHeightPx,
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
    val initialPos = FloatingOverlayGeometry.clampVisiblePosition(
        x = (18 * dm.density).roundToInt(),
        y = (90 * dm.density).roundToInt(),
        windowW = initialSize.width,
        windowH = initialSize.height,
        screenW = dm.widthPixels,
        screenH = dm.heightPixels,
        density = dm.density,
    )
    return InitialFloatingBounds(initialSize.width, initialSize.height, initialPos.x, initialPos.y)
}
