package io.github.lengsukq.opalcast.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import io.github.lengsukq.opalcast.prefs.ReceiverPrefs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * OpalCast companion design tokens — Emerald-Teal & Aurora Cyan Liquid Glass palette,
 * intentionally distinct from OpalDesk's obsidian-navy / indigo host workstation theme.
 */
data class OpalCastPalette(
    val isDark: Boolean,
    val bgCanvas: Int,
    val bgCard: Int,
    val bgElevated: Int,
    val border: Int,
    val borderStrong: Int,
    val inkPrimary: Int,
    val inkSecondary: Int,
    val inkMuted: Int,
    val accent: Int,
    val accentSoft: Int,
    val accentBorder: Int,
    val secondaryAccent: Int,
    val secondarySoft: Int,
    val amber: Int,
    val amberSoft: Int,
    val danger: Int,
    val dangerSoft: Int,
    val chipBg: Int,
    val inputBg: Int,
)

object OpalCastTheme {
    private val Dark = OpalCastPalette(
        isDark = true,
        bgCanvas = 0xFF071318.toInt(),
        bgCard = 0xFF0E1F27.toInt(),
        bgElevated = 0xFF142A34.toInt(),
        border = 0x2679F2D0,
        borderStrong = 0x4D2DD4BF,
        inkPrimary = 0xFFF0FDF9.toInt(),
        inkSecondary = 0xFFA7D3CC.toInt(),
        inkMuted = 0xFF6B9994.toInt(),
        accent = 0xFF14B8A6.toInt(),
        accentSoft = 0x2914B8A6,
        accentBorder = 0x662DD4BF,
        secondaryAccent = 0xFF38BDF8.toInt(),
        secondarySoft = 0x2438BDF8,
        amber = 0xFFF59E0B.toInt(),
        amberSoft = 0x29F59E0B,
        danger = 0xFFF87171.toInt(),
        dangerSoft = 0x28EF4444,
        chipBg = 0x1AFFFFFF,
        inputBg = 0xFF091820.toInt(),
    )

    private val Light = OpalCastPalette(
        isDark = false,
        bgCanvas = 0xFFF0F9F8.toInt(),
        bgCard = 0xFFFFFFFF.toInt(),
        bgElevated = 0xFFE6F5F3.toInt(),
        border = 0x240F766E,
        borderStrong = 0x4D0D9488,
        inkPrimary = 0xFF082226.toInt(),
        inkSecondary = 0xFF2D5B5E.toInt(),
        inkMuted = 0xFF608385.toInt(),
        accent = 0xFF0D9488.toInt(),
        accentSoft = 0x1A0D9488,
        accentBorder = 0x550D9488,
        secondaryAccent = 0xFF0284C7.toInt(),
        secondarySoft = 0x180284C7,
        amber = 0xFFD97706.toInt(),
        amberSoft = 0x1CF59E0B,
        danger = 0xFFDC2626.toInt(),
        dangerSoft = 0x1ADC2626,
        chipBg = 0x120F766E,
        inputBg = 0xFFF7FCFB.toInt(),
    )

    fun resolve(ctx: Context): OpalCastPalette {
        val dark = when (ReceiverPrefs.getThemeMode(ctx)) {
            ReceiverPrefs.THEME_DARK -> true
            ReceiverPrefs.THEME_LIGHT -> false
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return if (dark) Dark else Light
    }

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).roundToInt()

    fun roundedDrawable(
        ctx: Context,
        fillColor: Int,
        radiusDp: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeDp: Float = 0f,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeDp > 0f && strokeColor != Color.TRANSPARENT) {
            setStroke(max(1, (strokeDp * ctx.resources.displayMetrics.density).roundToInt()), strokeColor)
        }
    }

    fun buildBadge(
        ctx: Context,
        text: String,
        bgColor: Int,
        textColor: Int,
        borderColor: Int = Color.TRANSPARENT,
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = roundedDrawable(ctx, bgColor, 99, borderColor, if (borderColor != Color.TRANSPARENT) 0.8f else 0f)
        setPadding(dp(ctx, 9), dp(ctx, 3), dp(ctx, 9), dp(ctx, 3))
    }

    fun buildPillButton(
        ctx: Context,
        label: String,
        bgColor: Int,
        textColor: Int,
        borderColor: Int = Color.TRANSPARENT,
        bold: Boolean = true,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = roundedDrawable(ctx, bgColor, 12, borderColor, if (borderColor != Color.TRANSPARENT) 1f else 0f)
        setPadding(dp(ctx, 13), dp(ctx, 9), dp(ctx, 13), dp(ctx, 9))
        setOnClickListener { onClick() }
    }
}
