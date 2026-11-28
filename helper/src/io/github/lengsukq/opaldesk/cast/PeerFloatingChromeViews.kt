package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Custom Android View chrome primitives, vector glyph rendering, and stateful pill styling
 * extracted from [PeerFloatingAppService].
 */
internal enum class MirrorGlyph { BACK, HOME, RECENTS, SCREEN_OFF, KEYBOARD }

internal class MirrorGlyphIconView(
    context: Context,
    private val glyph: MirrorGlyph,
    initialTint: Int,
) : View(context) {
    private var tintColor: Int = initialTint
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
    private val tmpPath = Path()
    private val tmpRect = RectF()

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
            MirrorGlyph.BACK -> {
                // Optically centered SF-style chevron (<)
                tmpPath.reset()
                tmpPath.moveTo(w * 0.63f, h * 0.17f)
                tmpPath.lineTo(w * 0.30f, h * 0.50f)
                tmpPath.lineTo(w * 0.63f, h * 0.83f)
                canvas.drawPath(tmpPath, strokePaint)
            }
            MirrorGlyph.HOME -> {
                // Refined minimal house silhouette matching OpalPrimitives ChromeGlyph.HOME_HOUSE
                tmpPath.reset()
                tmpPath.moveTo(w * 0.13f, h * 0.48f)
                tmpPath.lineTo(w * 0.50f, h * 0.15f)
                tmpPath.lineTo(w * 0.87f, h * 0.48f)
                canvas.drawPath(tmpPath, strokePaint)

                val r = 1.8f * density
                tmpPath.reset()
                tmpPath.moveTo(w * 0.24f, h * 0.44f)
                tmpPath.lineTo(w * 0.24f, h * 0.84f - r)
                tmpPath.quadTo(w * 0.24f, h * 0.84f, w * 0.24f + r, h * 0.84f)
                tmpPath.lineTo(w * 0.76f - r, h * 0.84f)
                tmpPath.quadTo(w * 0.76f, h * 0.84f, w * 0.76f, h * 0.84f - r)
                tmpPath.lineTo(w * 0.76f, h * 0.44f)
                canvas.drawPath(tmpPath, strokePaint)
            }
            MirrorGlyph.RECENTS -> {
                // Layered app-switcher cards (inspired by iPadOS Stage Manager / SF square.on.square)
                val cr = 2.4f * density
                val backAlpha = ((Color.alpha(tintColor) * 0.62f).roundToInt()).coerceIn(0, 255)
                strokePaint.color = Color.argb(backAlpha, Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor))
                strokePaint.strokeWidth = sw * 0.90f
                tmpPath.reset()
                tmpPath.moveTo(w * 0.33f, h * 0.27f)
                tmpPath.lineTo(w * 0.33f, h * 0.14f + cr)
                tmpPath.quadTo(w * 0.33f, h * 0.14f, w * 0.33f + cr, h * 0.14f)
                tmpPath.lineTo(w * 0.86f - cr, h * 0.14f)
                tmpPath.quadTo(w * 0.86f, h * 0.14f, w * 0.86f, h * 0.14f + cr)
                tmpPath.lineTo(w * 0.86f, h * 0.67f - cr)
                tmpPath.quadTo(w * 0.86f, h * 0.67f, w * 0.86f - cr, h * 0.67f)
                tmpPath.lineTo(w * 0.72f, h * 0.67f)
                canvas.drawPath(tmpPath, strokePaint)

                strokePaint.color = tintColor
                strokePaint.strokeWidth = sw
                tmpRect.set(w * 0.14f, h * 0.31f, w * 0.69f, h * 0.86f)
                canvas.drawRoundRect(tmpRect, cr, cr, strokePaint)
            }
            MirrorGlyph.SCREEN_OFF -> {
                // Universal power / screen-sleep glyph (open top arc + stem)
                tmpRect.set(w * 0.16f, h * 0.18f, w * 0.84f, h * 0.86f)
                canvas.drawArc(tmpRect, -52f, 284f, false, strokePaint)
                canvas.drawLine(w * 0.50f, h * 0.10f, w * 0.50f, h * 0.47f, strokePaint)
            }
            MirrorGlyph.KEYBOARD -> {
                val cr = 2.0f * density
                strokePaint.strokeWidth = sw * 0.90f
                tmpRect.set(w * 0.08f, h * 0.22f, w * 0.92f, h * 0.78f)
                canvas.drawRoundRect(tmpRect, cr, cr, strokePaint)
                canvas.drawLine(w * 0.28f, h * 0.62f, w * 0.72f, h * 0.62f, strokePaint)
                val dotR = (w * 0.052f).coerceAtLeast(1.1f * density)
                for (gx in floatArrayOf(0.26f, 0.42f, 0.58f, 0.74f)) {
                    canvas.drawCircle(w * gx, h * 0.42f, dotR, fillPaint)
                }
            }
        }
    }
}

internal fun buildFloatingTrafficLightDot(
    context: Context,
    fillColor: Int,
    borderColor: Int,
    onClick: () -> Unit,
): View = View(context).apply {
    val density = context.resources.displayMetrics.density
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fillColor)
        setStroke(max(1, (0.8f * density).roundToInt()), borderColor)
    }
    setOnClickListener { onClick() }
}

internal fun buildFloatingHeaderPill(
    context: Context,
    label: String,
    bgColor: Int,
    borderColor: Int,
    textColor: Int,
    onClick: () -> Unit,
): TextView = TextView(context).apply {
    val density = context.resources.displayMetrics.density
    val dp = { v: Int -> (v * density).roundToInt() }
    text = label
    setTextColor(textColor)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    gravity = Gravity.CENTER
    background = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = dp(10).toFloat()
        setStroke(max(1, (0.7f * density).roundToInt()), borderColor)
    }
    setPadding(dp(9), dp(4), dp(9), dp(4))
    setOnClickListener { onClick() }
}

@SuppressLint("ClickableViewAccessibility")
internal fun buildFloatingChromeIconButton(
    context: Context,
    glyph: MirrorGlyph,
    contentDesc: String,
    iconSizeDp: Float,
    isDark: Boolean,
    onClick: () -> Unit,
): Pair<FrameLayout, MirrorGlyphIconView> {
    val density = context.resources.displayMetrics.density
    val idleInk = if (isDark) 0xFFD8E1F0.toInt() else 0xFF334155.toInt()
    val iconPx = (iconSizeDp * density).roundToInt()
    val icon = MirrorGlyphIconView(context, glyph, idleInk)
    val btn = FrameLayout(context).apply {
        contentDescription = contentDesc
        isClickable = true
        isFocusable = false
        addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(75L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }
            }
            false
        }
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }
    return btn to icon
}

@SuppressLint("ClickableViewAccessibility")
internal fun buildFloatingDockNavIconButton(
    context: Context,
    glyph: MirrorGlyph,
    contentDesc: String,
    isDark: Boolean,
    onClick: () -> Unit,
): FrameLayout {
    val density = context.resources.displayMetrics.density
    val dp = { v: Int -> (v * density).roundToInt() }
    val idleInk = if (isDark) 0xFFE2E8F0.toInt() else 0xFF1E293B.toInt()
    val pressedInk = if (isDark) 0xFF60A5FA.toInt() else 0xFF007AFF.toInt()
    val pressedBg = GradientDrawable().apply {
        setColor(if (isDark) 0x2660A5FA else 0x1C007AFF)
        cornerRadius = dp(12).toFloat()
    }
    val iconPx = (15.5f * density).roundToInt()
    val icon = MirrorGlyphIconView(context, glyph, idleInk)
    return FrameLayout(context).apply {
        contentDescription = contentDesc
        isClickable = true
        isFocusable = false
        addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = pressedBg
                    icon.setIconTint(pressedInk)
                    icon.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.background = null
                    icon.setIconTint(idleInk)
                    icon.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                }
            }
            false
        }
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }
}

internal fun applyFloatingBlackoutPillStyle(
    btn: FrameLayout,
    glyph: MirrorGlyphIconView,
    active: Boolean,
    isDark: Boolean,
) {
    val density = btn.resources.displayMetrics.density
    val dp = { v: Int -> (v * density).roundToInt() }
    btn.contentDescription = if (active) "亮屏" else "熄屏"
    if (active) {
        val activeInk = if (isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
        glyph.setIconTint(activeInk)
        btn.background = GradientDrawable().apply {
            setColor(if (isDark) 0x30F59E0B else 0x24F59E0B)
            cornerRadius = dp(13).toFloat()
            setStroke(max(1, (0.85f * density).roundToInt()), 0x7AF59E0B)
        }
    } else {
        val idleBg = if (isDark) 0x18FFFFFF else 0x0F0F172A
        val idleRim = if (isDark) 0x26FFFFFF else 0x1A0F172A
        val idleInk = if (isDark) 0xFFD8E1F0.toInt() else 0xFF334155.toInt()
        glyph.setIconTint(idleInk)
        btn.background = GradientDrawable().apply {
            setColor(idleBg)
            cornerRadius = dp(13).toFloat()
            setStroke(max(1, (0.7f * density).roundToInt()), idleRim)
        }
    }
}

internal fun applyFloatingImeButtonStyle(
    btn: FrameLayout,
    glyph: MirrorGlyphIconView,
    active: Boolean,
    isDark: Boolean,
) {
    val density = btn.resources.displayMetrics.density
    val dp = { v: Int -> (v * density).roundToInt() }
    btn.contentDescription = if (active) "收起键盘" else "键盘"
    if (active) {
        val activeInk = if (isDark) 0xFF60A5FA.toInt() else 0xFF007AFF.toInt()
        glyph.setIconTint(activeInk)
        btn.background = GradientDrawable().apply {
            setColor(if (isDark) 0x303B82F6 else 0x1E007AFF)
            cornerRadius = dp(13).toFloat()
            setStroke(max(1, (0.85f * density).roundToInt()), 0x6E007AFF)
        }
    } else {
        val idleBg = if (isDark) 0x18FFFFFF else 0x0F0F172A
        val idleRim = if (isDark) 0x26FFFFFF else 0x1A0F172A
        val idleInk = if (isDark) 0xFFD8E1F0.toInt() else 0xFF334155.toInt()
        glyph.setIconTint(idleInk)
        btn.background = GradientDrawable().apply {
            setColor(idleBg)
            cornerRadius = dp(13).toFloat()
            setStroke(max(1, (0.7f * density).roundToInt()), idleRim)
        }
    }
}

internal fun floatingCircleDrawable(color: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
