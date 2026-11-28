package io.github.lengsukq.opalcast.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.net.OpalCastClient
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class FloatingGlyph { BACK, HOME, RECENTS, SCREEN_OFF, KEYBOARD }

internal class FloatingGlyphIconView(
    ctx: Context,
    private val glyph: FloatingGlyph,
    initialTint: Int,
) : View(ctx) {
    private var tintColor = initialTint
    private val density = resources.displayMetrics.density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = initialTint
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = initialTint
    }
    private val path = Path()
    private val rect = RectF()

    fun setIconTint(color: Int) {
        if (tintColor != color) {
            tintColor = color
            strokePaint.color = color
            fillPaint.color = color
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sw = (1.65f * density).coerceAtLeast(1.8f)
        strokePaint.color = tintColor
        strokePaint.strokeWidth = sw
        fillPaint.color = tintColor
        when (glyph) {
            FloatingGlyph.BACK -> {
                path.reset()
                path.moveTo(w * 0.63f, h * 0.17f)
                path.lineTo(w * 0.30f, h * 0.50f)
                path.lineTo(w * 0.63f, h * 0.83f)
                canvas.drawPath(path, strokePaint)
            }
            FloatingGlyph.HOME -> {
                path.reset()
                path.moveTo(w * 0.13f, h * 0.48f)
                path.lineTo(w * 0.50f, h * 0.15f)
                path.lineTo(w * 0.87f, h * 0.48f)
                canvas.drawPath(path, strokePaint)
                val r = 1.8f * density
                path.reset()
                path.moveTo(w * 0.24f, h * 0.44f)
                path.lineTo(w * 0.24f, h * 0.84f - r)
                path.quadTo(w * 0.24f, h * 0.84f, w * 0.24f + r, h * 0.84f)
                path.lineTo(w * 0.76f - r, h * 0.84f)
                path.quadTo(w * 0.76f, h * 0.84f, w * 0.76f, h * 0.84f - r)
                path.lineTo(w * 0.76f, h * 0.44f)
                canvas.drawPath(path, strokePaint)
            }
            FloatingGlyph.RECENTS -> {
                val cr = 2.4f * density
                rect.set(w * 0.30f, h * 0.14f, w * 0.86f, h * 0.68f)
                canvas.drawRoundRect(rect, cr, cr, strokePaint)
                rect.set(w * 0.14f, h * 0.31f, w * 0.70f, h * 0.86f)
                canvas.drawRoundRect(rect, cr, cr, strokePaint)
            }
            FloatingGlyph.SCREEN_OFF -> {
                rect.set(w * 0.16f, h * 0.18f, w * 0.84f, h * 0.86f)
                canvas.drawArc(rect, -52f, 284f, false, strokePaint)
                canvas.drawLine(w * 0.50f, h * 0.10f, w * 0.50f, h * 0.47f, strokePaint)
            }
            FloatingGlyph.KEYBOARD -> {
                val cr = 2.0f * density
                strokePaint.strokeWidth = sw * 0.90f
                rect.set(w * 0.08f, h * 0.22f, w * 0.92f, h * 0.78f)
                canvas.drawRoundRect(rect, cr, cr, strokePaint)
                canvas.drawLine(w * 0.28f, h * 0.62f, w * 0.72f, h * 0.62f, strokePaint)
                val dotR = (w * 0.052f).coerceAtLeast(1.1f * density)
                for (gx in floatArrayOf(0.26f, 0.42f, 0.58f, 0.74f)) {
                    canvas.drawCircle(w * gx, h * 0.42f, dotR, fillPaint)
                }
            }
        }
    }
}

internal data class FloatingWindowViews(
    val container: LinearLayout,
    val header: LinearLayout,
    val resizeGrip: View,
    val textureView: TextureView,
    val statusDotView: View,
    val titleTextView: TextView,
    val blackoutBtnContainer: FrameLayout?,
    val blackoutIconView: FloatingGlyphIconView?,
    val imeBtnContainer: FrameLayout?,
    val imeIconView: FloatingGlyphIconView?,
    val overlayBanner: LinearLayout,
    val overlayBannerText: TextView,
    val overlayRetryBtn: TextView,
    val imeInputBar: LinearLayout,
    val imeEditText: EditText,
)

internal data class FloatingWindowCallbacks(
    val onClose: () -> Unit,
    val onCollapse: () -> Unit,
    val onExpandOrRotate: () -> Unit,
    val onToggleBlackout: () -> Unit,
    val onToggleImeBar: () -> Unit,
    val onBack: () -> Unit,
    val onHome: () -> Unit,
    val onRecents: () -> Unit,
    val onSendImeText: () -> Unit,
    val onReconnect: () -> Unit,
    val getClient: () -> OpalCastClient?,
    val surfaceListener: TextureView.SurfaceTextureListener,
)

/**
 * Pure Framework Views chrome builders & foreground notification helper for [FloatingCastService].
 */
internal object FloatingCastChromeHelper {
    fun buildTrafficLightDot(
        ctx: Context,
        fillColor: Int,
        borderColor: Int,
        onClick: () -> Unit,
    ): View = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(max(1, (0.8f * ctx.resources.displayMetrics.density).roundToInt()), borderColor)
        }
        setOnClickListener { onClick() }
    }

    fun buildHeaderPill(
        ctx: Context,
        label: String,
        bgColor: Int,
        borderColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = OpalCastTheme.roundedDrawable(ctx, bgColor, 10, borderColor, 0.7f)
        setPadding(OpalCastTheme.dp(ctx, 9), OpalCastTheme.dp(ctx, 4), OpalCastTheme.dp(ctx, 9), OpalCastTheme.dp(ctx, 4))
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun buildChromeIconButton(
        ctx: Context,
        glyph: FloatingGlyph,
        desc: String,
        sizeDp: Float,
        isDark: Boolean,
        onClick: () -> Unit,
    ): Pair<FrameLayout, FloatingGlyphIconView> {
        val idleInk = if (isDark) 0xFFA7D3CC.toInt() else 0xFF2D5B5E.toInt()
        val iconPx = (sizeDp * ctx.resources.displayMetrics.density).roundToInt()
        val icon = FloatingGlyphIconView(ctx, glyph, idleInk)
        val btn = FrameLayout(ctx).apply {
            contentDescription = desc
            isClickable = true
            isFocusable = false
            addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        return btn to icon
    }

    @SuppressLint("ClickableViewAccessibility")
    fun buildDockNavIconButton(
        ctx: Context,
        glyph: FloatingGlyph,
        desc: String,
        isDark: Boolean,
        onClick: () -> Unit,
    ): FrameLayout {
        val idleInk = if (isDark) 0xFFF0FDF9.toInt() else 0xFF082226.toInt()
        val iconPx = (15.5f * ctx.resources.displayMetrics.density).roundToInt()
        val icon = FloatingGlyphIconView(ctx, glyph, idleInk)
        return FrameLayout(ctx).apply {
            contentDescription = desc
            isClickable = true
            isFocusable = false
            addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
    }

    fun buildResizeGripView(ctx: Context, isDark: Boolean): View {
        val density = ctx.resources.displayMetrics.density
        val gripTint = if (isDark) 0x77A7D3CC.toInt() else 0x660F766E.toInt()
        return object : View(ctx) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gripTint
                strokeWidth = (1.65f * density).coerceAtLeast(2.0f)
                strokeCap = Paint.Cap.ROUND
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val pad = OpalCastTheme.dp(ctx, 7).toFloat()
                val w = width.toFloat() - pad
                val h = height.toFloat() - pad
                val span = OpalCastTheme.dp(ctx, 9).toFloat()
                canvas.drawLine(w - span, h, w, h - span, paint)
                canvas.drawLine(w - span * 0.50f, h, w, h - span * 0.50f, paint)
            }
        }
    }

    fun buildMirrorNavDock(
        ctx: Context,
        headerBg: Int,
        isDark: Boolean,
        resizeGrip: View,
        onBack: () -> Unit,
        onHome: () -> Unit,
        onRecents: () -> Unit,
    ): FrameLayout {
        val density = ctx.resources.displayMetrics.density
        val dp4 = OpalCastTheme.dp(ctx, 4)
        val dp2 = OpalCastTheme.dp(ctx, 2)
        val bottomFrame = FrameLayout(ctx).apply { setBackgroundColor(headerBg) }
        val capsuleBg = if (isDark) 0x16FFFFFF else 0xCCE6F5F3.toInt()
        val capsuleRim = if (isDark) 0x26FFFFFF else 0x220F766E
        val sepColor = if (isDark) 0x1FFFFFFF else 0x1A0F766E
        val navCapsule = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OpalCastTheme.roundedDrawable(ctx, capsuleBg, 15, capsuleRim, 0.8f)
            setPadding(dp4, dp2, dp4, dp2)
        }
        fun addSep() {
            navCapsule.addView(
                View(ctx).apply { setBackgroundColor(sepColor) },
                LinearLayout.LayoutParams(max(1, (0.8f * density).roundToInt()), OpalCastTheme.dp(ctx, 12)),
            )
        }
        val btnW = OpalCastTheme.dp(ctx, 46)
        val btnH = OpalCastTheme.dp(ctx, 26)
        navCapsule.addView(buildDockNavIconButton(ctx, FloatingGlyph.BACK, ctx.getString(R.string.common_back), isDark, onBack), LinearLayout.LayoutParams(btnW, btnH))
        addSep()
        navCapsule.addView(buildDockNavIconButton(ctx, FloatingGlyph.HOME, ctx.getString(R.string.common_home), isDark, onHome), LinearLayout.LayoutParams(btnW, btnH))
        addSep()
        navCapsule.addView(buildDockNavIconButton(ctx, FloatingGlyph.RECENTS, ctx.getString(R.string.common_recents), isDark, onRecents), LinearLayout.LayoutParams(btnW, btnH))
        bottomFrame.addView(navCapsule, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, OpalCastTheme.dp(ctx, 30), Gravity.CENTER))
        bottomFrame.addView(resizeGrip, FrameLayout.LayoutParams(OpalCastTheme.dp(ctx, 34), OpalCastTheme.dp(ctx, 34), Gravity.BOTTOM or Gravity.END))
        return bottomFrame
    }

    fun buildBubblePill(ctx: Context, initial: String, isMirrorMode: Boolean): TextView =
        TextView(ctx).apply {
            text = initial
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isMirrorMode) 0xEE0891B2.toInt() else 0xEE0D9488.toInt())
                setStroke(OpalCastTheme.dp(ctx, 2), 0xAAFFFFFF.toInt())
            }
            elevation = OpalCastTheme.dp(ctx, 12).toFloat()
        }

    fun refreshBlackoutAppearance(
        ctx: Context,
        btn: FrameLayout?,
        glyph: FloatingGlyphIconView?,
        remoteBlackout: Boolean,
    ) {
        if (btn == null || glyph == null) return
        val isDark = OpalCastTheme.resolve(ctx).isDark
        btn.contentDescription = ctx.getString(if (remoteBlackout) R.string.viewer_chip_screen_on else R.string.viewer_chip_screen_off)
        if (remoteBlackout) {
            glyph.setIconTint(if (isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt())
            btn.background = OpalCastTheme.roundedDrawable(ctx, if (isDark) 0x30F59E0B else 0x24F59E0B, 13, 0x7AF59E0B, 0.85f)
        } else {
            glyph.setIconTint(if (isDark) 0xFFA7D3CC.toInt() else 0xFF2D5B5E.toInt())
            btn.background = OpalCastTheme.roundedDrawable(ctx, if (isDark) 0x18FFFFFF else 0x0F0F766E, 13, if (isDark) 0x26FFFFFF else 0x1A0F766E, 0.7f)
        }
    }

    fun refreshImeButtonAppearance(
        ctx: Context,
        btn: FrameLayout?,
        glyph: FloatingGlyphIconView?,
        isImeBarVisible: Boolean,
    ) {
        if (btn == null || glyph == null) return
        val pal = OpalCastTheme.resolve(ctx)
        btn.contentDescription = ctx.getString(if (isImeBarVisible) R.string.float_desc_hide_keyboard else R.string.viewer_chip_keyboard)
        if (isImeBarVisible) {
            glyph.setIconTint(pal.accent)
            btn.background = OpalCastTheme.roundedDrawable(ctx, pal.accentSoft, 13, pal.accentBorder, 0.85f)
        } else {
            glyph.setIconTint(pal.inkSecondary)
            btn.background = OpalCastTheme.roundedDrawable(ctx, if (pal.isDark) 0x18FFFFFF else 0x0F0F766E, 13, if (pal.isDark) 0x26FFFFFF else 0x1A0F766E, 0.7f)
        }
    }

    fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun startForegroundNotification(
        service: Service,
        channelId: String,
        notifId: Int,
        appName: String,
        peerName: String,
        isMirror: Boolean,
    ) {
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        AndroidApiCompat.ensureNotificationChannel(
            nm,
            channelId,
            service.getString(R.string.float_notif_channel_name),
            service.getString(R.string.float_notif_channel_desc),
        )
        val immFlag = AndroidApiCompat.pendingIntentImmutableFlag()
        val openHome = PendingIntent.getActivity(
            service,
            0,
            Intent(service, CastHomeActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or immFlag,
        )
        val stopPending = PendingIntent.getService(
            service,
            1,
            Intent(service, FloatingCastService::class.java).apply { action = FloatingCastService.ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or immFlag,
        )
        val title = if (isMirror) {
            service.getString(R.string.float_notif_title_mirror_fmt, peerName)
        } else {
            service.getString(R.string.float_notif_title_app_fmt, appName)
        }
        val body = if (isMirror) {
            service.getString(R.string.float_notif_body_mirror_fmt, peerName)
        } else {
            service.getString(R.string.float_notif_body_app_fmt, appName, peerName)
        }
        val builder = AndroidApiCompat.createNotificationBuilder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_opalcast)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(openHome)
        AndroidApiCompat.addNotificationAction(
            service,
            builder,
            android.R.drawable.ic_menu_close_clear_cancel,
            service.getString(R.string.home_floating_close),
            stopPending,
        )
        AndroidApiCompat.startForegroundCompat(service, notifId, builder.build())
    }

    @SuppressLint("ClickableViewAccessibility")
    fun buildFloatingWindowHierarchy(
        ctx: Context,
        isMirrorMode: Boolean,
        titleText: String,
        appLabel: String,
        callbacks: FloatingWindowCallbacks,
    ): FloatingWindowViews {
        val dm = ctx.resources.displayMetrics
        val dp = { v: Int -> OpalCastTheme.dp(ctx, v) }
        val pal = OpalCastTheme.resolve(ctx)
        val isDark = pal.isDark
        val containerBg = if (isDark) 0xF20D1E25.toInt() else 0xF5F4FBFA.toInt()
        val containerStroke = if (isDark) 0x452DD4BF else 0xC899D6CF.toInt()
        val headerBg = if (isDark) 0xEB12262F.toInt() else 0xF2FFFFFF.toInt()
        val dividerColor = if (isDark) 0x2679F2D0 else 0x1A0F766E
        val titleInk = pal.inkPrimary
        val pillBg = if (isDark) 0x1CFFFFFF else 0x120F766E
        val pillBorder = if (isDark) 0x2EFFFFFF else 0x220F766E
        val pillInk = pal.inkSecondary
        val imeBarBg = if (isDark) 0xF50A171D.toInt() else 0xF5EBF7F5.toInt()
        val canvasBg = if (isDark) 0xFF060E12.toInt() else 0xFFF2FAF9.toInt()
        val bannerBg = if (isDark) 0xE608141A.toInt() else 0xEBF2FAF9.toInt()
        val cornerRadiusPx = dp(20).toFloat()

        val strokePx = max(2, (1.25f * dm.density).roundToInt())
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(containerBg)
                cornerRadius = cornerRadiusPx
                setStroke(strokePx, containerStroke)
            }
            setPadding(strokePx, strokePx, strokePx, strokePx)
            elevation = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBg)
            setPadding(if (isMirrorMode) dp(10) else dp(12), dp(6), if (isMirrorMode) dp(8) else dp(10), dp(6))
        }

        val lightsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lightPx = if (isMirrorMode) dp(12) else dp(13)
        val lightGap = if (isMirrorMode) dp(6) else dp(7)
        val lightTail = if (isMirrorMode) dp(8) else dp(10)
        lightsRow.addView(
            buildTrafficLightDot(ctx, 0xFFFF5F57.toInt(), 0xFFE0443E.toInt(), callbacks.onClose),
            LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightGap },
        )
        lightsRow.addView(
            buildTrafficLightDot(ctx, 0xFFFEBC2E.toInt(), 0xFFD89E24.toInt(), callbacks.onCollapse),
            LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightGap },
        )
        lightsRow.addView(
            buildTrafficLightDot(ctx, 0xFF28C840.toInt(), 0xFF1AAB29.toInt(), callbacks.onExpandOrRotate),
            LinearLayout.LayoutParams(lightPx, lightPx).apply { rightMargin = lightTail },
        )
        header.addView(lightsRow)

        val dot = View(ctx).apply { background = circleDrawable(0xFFF59E0B.toInt()) }
        header.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(5) })

        val titleTv = TextView(ctx).apply {
            text = titleText
            setTextColor(titleInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })

        var blackoutBtn: FrameLayout? = null
        var blackoutIcon: FloatingGlyphIconView? = null
        var imeBtn: FrameLayout? = null
        var imeIcon: FloatingGlyphIconView? = null
        if (isMirrorMode) {
            val (bBtn, bIcon) = buildChromeIconButton(ctx, FloatingGlyph.SCREEN_OFF, ctx.getString(R.string.viewer_chip_screen_off), 13.5f, isDark, callbacks.onToggleBlackout)
            blackoutBtn = bBtn
            blackoutIcon = bIcon
            header.addView(bBtn, LinearLayout.LayoutParams(dp(30), dp(26)).apply { rightMargin = dp(5) })

            val (iBtn, iIcon) = buildChromeIconButton(ctx, FloatingGlyph.KEYBOARD, ctx.getString(R.string.viewer_chip_keyboard), 14f, isDark, callbacks.onToggleImeBar)
            imeBtn = iBtn
            imeIcon = iIcon
            header.addView(iBtn, LinearLayout.LayoutParams(dp(30), dp(26)))
        } else {
            header.addView(
                buildHeaderPill(ctx, ctx.getString(R.string.common_back), pillBg, pillBorder, pillInk, callbacks.onBack),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5) },
            )
            header.addView(buildHeaderPill(ctx, ctx.getString(R.string.viewer_chip_keyboard), pillBg, pillBorder, pillInk, callbacks.onToggleImeBar))
        }

        container.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        container.addView(View(ctx).apply { setBackgroundColor(dividerColor) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1))))

        val imeBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(imeBarBg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        val edit = EditText(ctx).apply {
            hint = ctx.getString(R.string.float_ime_hint_fmt, appLabel)
            setHintTextColor(pal.inkMuted)
            setTextColor(titleInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                ) {
                    callbacks.onSendImeText()
                    true
                } else {
                    false
                }
            }
        }
        val sendBtn = OpalCastTheme.buildPillButton(ctx, ctx.getString(R.string.common_send), pal.accent, Color.WHITE, onClick = callbacks.onSendImeText)
        imeBar.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        imeBar.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        container.addView(imeBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = FrameLayout(ctx).apply { setBackgroundColor(canvasBg) }
        val tv = TextureView(ctx).apply {
            isOpaque = true
            surfaceTextureListener = callbacks.surfaceListener
            setOnTouchListener { v, ev ->
                val c = callbacks.getClient() ?: return@setOnTouchListener true
                if (c.isMouseMotion(ev)) c.sendMouseEvent(ev, v.width, v.height)
                else c.sendTouchEvent(ev, v.width, v.height)
                true
            }
            setOnGenericMotionListener { v, ev ->
                callbacks.getClient()?.handleGenericMouseMotion(ev, v.width, v.height) ?: false
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
            text = ctx.getString(R.string.float_connecting)
            setTextColor(titleInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        val retryBtn = OpalCastTheme.buildPillButton(ctx, ctx.getString(R.string.common_reconnect), pal.accent, Color.WHITE, onClick = callbacks.onReconnect)
            .apply { visibility = View.GONE }
        banner.addView(bannerTv)
        banner.addView(retryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        body.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val resizeGrip = buildResizeGripView(ctx, isDark)
        if (!isMirrorMode) {
            body.addView(resizeGrip, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
        }
        container.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (isMirrorMode) {
            container.addView(View(ctx).apply { setBackgroundColor(dividerColor) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1))))
            val bottomFrame = buildMirrorNavDock(ctx, headerBg, isDark, resizeGrip, callbacks.onBack, callbacks.onHome, callbacks.onRecents)
            container.addView(bottomFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }

        return FloatingWindowViews(
            container = container, header = header, resizeGrip = resizeGrip, textureView = tv,
            statusDotView = dot, titleTextView = titleTv,
            blackoutBtnContainer = blackoutBtn, blackoutIconView = blackoutIcon,
            imeBtnContainer = imeBtn, imeIconView = imeIcon,
            overlayBanner = banner, overlayBannerText = bannerTv, overlayRetryBtn = retryBtn,
            imeInputBar = imeBar, imeEditText = edit,
        )
    }
}
