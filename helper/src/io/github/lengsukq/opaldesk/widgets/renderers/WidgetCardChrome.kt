package io.github.lengsukq.opaldesk.widgets.renderers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText

/**
 * Translucent liquid-glass badge pill with subtle luminous rim and optional status dot or icon.
 */
@Composable
fun WidgetGlassBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    dotColor: Color? = null,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
) {
    val colors = LocalOpalColors.current
    val isDark = colors.isDark
    val shape = CircleShape
    val fill = if (accent != null) {
        accent.copy(alpha = if (isDark) 0.20f else 0.13f)
    } else {
        if (isDark) Color(0x22FFFFFF) else Color(0x52FFFFFF)
    }
    val borderColor = if (accent != null) {
        accent.copy(alpha = if (isDark) 0.44f else 0.30f)
    } else {
        colors.borderSubtle
    }
    val contentColor = accent ?: colors.textSecondary

    Row(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(0.8.dp, borderColor, shape)
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        if (leadingIcon != null) {
            leadingIcon(contentColor)
        }
        OpalText(
            text = text,
            color = contentColor,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Liquid-glass circular or capsule action button with active/filled luminous highlight.
 */
@Composable
fun WidgetGlassActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    accent: Color = OpalPalette.AccentBlue,
    active: Boolean = false,
    filled: Boolean = false,
    circular: Boolean = label == null,
    size: Dp = 32.dp,
    enabled: Boolean = true,
    contentDescription: String? = null,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    val colors = LocalOpalColors.current
    val isDark = colors.isDark
    val shape = if (circular) CircleShape else RoundedCornerShape(12.dp)
    val fillBrush = when {
        !enabled -> Brush.verticalGradient(listOf(colors.chipFill, colors.chipFill))
        filled -> Brush.verticalGradient(
            listOf(
                accent.copy(alpha = 0.96f),
                accent.copy(alpha = 0.80f),
            ),
        )
        active -> Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (isDark) 0.30f else 0.20f),
                accent.copy(alpha = if (isDark) 0.16f else 0.10f),
            ),
        )
        else -> Brush.verticalGradient(
            listOf(
                if (isDark) Color(0x28FFFFFF) else Color(0x88FFFFFF),
                if (isDark) Color(0x14FFFFFF) else Color(0x4EFFFFFF),
            ),
        )
    }
    val borderBrush = when {
        !enabled -> Brush.verticalGradient(listOf(colors.borderHairline, colors.borderHairline))
        filled -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.48f), accent.copy(alpha = 0.45f)),
        )
        active -> Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.62f), accent.copy(alpha = 0.24f)),
        )
        else -> Brush.verticalGradient(
            listOf(
                colors.glassRimTop.copy(alpha = if (isDark) 0.34f else 0.88f),
                colors.borderHairline,
            ),
        )
    }
    val contentTint = when {
        !enabled -> colors.textMuted
        filled -> Color.White
        active -> accent
        else -> colors.textPrimary
    }

    val sizeModifier = if (circular && label == null) {
        Modifier.size(size)
    } else {
        Modifier.height(size)
    }

    Row(
        modifier = modifier
            .then(sizeModifier)
            .clip(shape)
            .background(fillBrush)
            .border(0.85.dp, borderBrush, shape)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (label != null) 11.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            icon(contentTint)
        }
        if (label != null) {
            OpalText(
                text = label,
                color = contentTint,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Soft radial liquid-glass ambient glow orb positioned inside a widget container.
 */
@Composable
fun WidgetAmbientGlow(
    accent: Color,
    modifier: Modifier = Modifier,
    centerFractionX: Float = 0.85f,
    centerFractionY: Float = 0.16f,
    radiusFraction: Float = 0.72f,
    alpha: Float? = null,
) {
    val isDark = LocalOpalColors.current.isDark
    val peakAlpha = alpha ?: if (isDark) 0.22f else 0.14f
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width * centerFractionX, size.height * centerFractionY)
        val radius = size.maxDimension * radiusFraction.coerceAtLeast(0.15f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to accent.copy(alpha = peakAlpha),
                    0.52f to accent.copy(alpha = peakAlpha * 0.34f),
                    1f to Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

/**
 * Inner frosted glass capsule surface (14.dp–16.dp) with subtle specular top highlight.
 */
@Composable
fun WidgetInnerPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    accent: Color? = null,
    active: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalOpalColors.current
    val isDark = colors.isDark
    val shape = RoundedCornerShape(cornerRadius)
    val fillBrush = when {
        active && accent != null -> Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (isDark) 0.26f else 0.16f),
                accent.copy(alpha = if (isDark) 0.12f else 0.07f),
            ),
        )
        accent != null -> Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (isDark) 0.14f else 0.10f),
                if (isDark) Color(0x16FFFFFF) else Color(0x5AFFFFFF),
            ),
        )
        else -> Brush.verticalGradient(
            listOf(
                if (isDark) Color(0x1EFFFFFF) else Color(0x78FFFFFF),
                if (isDark) Color(0x10FFFFFF) else Color(0x42FFFFFF),
            ),
        )
    }
    val borderBrush = if (active && accent != null) {
        Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.62f), accent.copy(alpha = 0.24f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                colors.glassRimTop.copy(alpha = if (isDark) 0.30f else 0.85f),
                colors.borderHairline,
            ),
        )
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(fillBrush)
            .border(0.85.dp, borderBrush, shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Centered frosted-glass empty state placeholder for widgets with no active items.
 */
@Composable
fun WidgetEmptyState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    accent: Color = OpalPalette.AccentBlue,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    val colors = LocalOpalColors.current
    val isDark = colors.isDark
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (isDark) 0.18f else 0.12f))
                    .border(0.85.dp, accent.copy(alpha = if (isDark) 0.38f else 0.26f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                icon(accent)
            }
            Spacer(Modifier.height(8.dp))
        }
        OpalText(
            text = title,
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = subtitle,
                color = colors.textMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

enum class WidgetGlyph {
    PREV,
    PLAY,
    PAUSE,
    NEXT,
    MUSIC_NOTE,
    VOLUME_ON,
    VOLUME_MUTE,
    CLOCK,
    TIMER,
    RESET,
    BOLT,
    TRACKPAD,
    BELL,
    COPY,
    CHECK,
    PASTE,
    TRASH,
    PLUS,
    SPLIT_SCREEN,
}

/**
 * Crisp geometric vector icons drawn with Compose Canvas (zero Material icon dependency).
 */
@Composable
fun WidgetGlyphIcon(
    glyph: WidgetGlyph,
    tint: Color,
    size: Dp = 15.dp,
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.65.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (glyph) {
            WidgetGlyph.PREV -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), sw, StrokeCap.Round)
                val p = Path().apply {
                    moveTo(w * 0.78f, h * 0.22f)
                    lineTo(w * 0.34f, h * 0.50f)
                    lineTo(w * 0.78f, h * 0.78f)
                    close()
                }
                drawPath(p, tint)
            }
            WidgetGlyph.NEXT -> {
                drawLine(tint, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), sw, StrokeCap.Round)
                val p = Path().apply {
                    moveTo(w * 0.22f, h * 0.22f)
                    lineTo(w * 0.66f, h * 0.50f)
                    lineTo(w * 0.22f, h * 0.78f)
                    close()
                }
                drawPath(p, tint)
            }
            WidgetGlyph.PLAY -> {
                val p = Path().apply {
                    moveTo(w * 0.30f, h * 0.18f)
                    lineTo(w * 0.82f, h * 0.50f)
                    lineTo(w * 0.30f, h * 0.82f)
                    close()
                }
                drawPath(p, tint)
            }
            WidgetGlyph.PAUSE -> {
                val barW = w * 0.19f
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.20f), Size(barW, h * 0.60f), CornerRadius(2.dp.toPx()))
                drawRoundRect(tint, Offset(w * 0.57f, h * 0.20f), Size(barW, h * 0.60f), CornerRadius(2.dp.toPx()))
            }
            WidgetGlyph.MUSIC_NOTE -> {
                drawLine(tint, Offset(w * 0.42f, h * 0.24f), Offset(w * 0.42f, h * 0.72f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.78f, h * 0.16f), Offset(w * 0.78f, h * 0.64f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.42f, h * 0.24f), Offset(w * 0.78f, h * 0.16f), sw * 1.2f, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.32f, h * 0.74f))
                drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.68f, h * 0.66f))
            }
            WidgetGlyph.VOLUME_ON -> {
                val speaker = Path().apply {
                    moveTo(w * 0.14f, h * 0.38f)
                    lineTo(w * 0.34f, h * 0.38f)
                    lineTo(w * 0.54f, h * 0.20f)
                    lineTo(w * 0.54f, h * 0.80f)
                    lineTo(w * 0.34f, h * 0.62f)
                    lineTo(w * 0.14f, h * 0.62f)
                    close()
                }
                drawPath(speaker, tint)
                drawArc(tint, -42f, 84f, false, Offset(w * 0.52f, h * 0.26f), Size(w * 0.30f, h * 0.48f), style = stroke)
            }
            WidgetGlyph.VOLUME_MUTE -> {
                val speaker = Path().apply {
                    moveTo(w * 0.12f, h * 0.38f)
                    lineTo(w * 0.30f, h * 0.38f)
                    lineTo(w * 0.48f, h * 0.22f)
                    lineTo(w * 0.48f, h * 0.78f)
                    lineTo(w * 0.30f, h * 0.62f)
                    lineTo(w * 0.12f, h * 0.62f)
                    close()
                }
                drawPath(speaker, tint)
                drawLine(tint, Offset(w * 0.62f, h * 0.34f), Offset(w * 0.88f, h * 0.66f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.88f, h * 0.34f), Offset(w * 0.62f, h * 0.66f), sw, StrokeCap.Round)
            }
            WidgetGlyph.CLOCK -> {
                drawCircle(tint, radius = w * 0.36f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.50f), Offset(w * 0.50f, h * 0.28f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.50f), Offset(w * 0.68f, h * 0.56f), sw, StrokeCap.Round)
            }
            WidgetGlyph.TIMER -> {
                drawLine(tint, Offset(w * 0.38f, h * 0.12f), Offset(w * 0.62f, h * 0.12f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.33f, center = Offset(w * 0.50f, h * 0.56f), style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.56f), Offset(w * 0.64f, h * 0.42f), sw, StrokeCap.Round)
            }
            WidgetGlyph.RESET -> {
                drawArc(tint, -45f, 285f, false, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), style = stroke)
                drawLine(tint, Offset(w * 0.66f, h * 0.16f), Offset(w * 0.82f, h * 0.30f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.82f, h * 0.12f), Offset(w * 0.82f, h * 0.30f), sw, StrokeCap.Round)
            }
            WidgetGlyph.BOLT -> {
                val p = Path().apply {
                    moveTo(w * 0.58f, h * 0.10f)
                    lineTo(w * 0.26f, h * 0.54f)
                    lineTo(w * 0.50f, h * 0.54f)
                    lineTo(w * 0.42f, h * 0.90f)
                    lineTo(w * 0.74f, h * 0.46f)
                    lineTo(w * 0.50f, h * 0.46f)
                    close()
                }
                drawPath(p, tint)
            }
            WidgetGlyph.TRACKPAD -> {
                drawRoundRect(tint, Offset(w * 0.12f, h * 0.18f), Size(w * 0.76f, h * 0.64f), CornerRadius(2.4.dp.toPx()), stroke)
                drawLine(tint, Offset(w * 0.14f, h * 0.64f), Offset(w * 0.86f, h * 0.64f), sw * 0.85f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.64f), Offset(w * 0.50f, h * 0.82f), sw * 0.85f, StrokeCap.Round)
            }
            WidgetGlyph.BELL -> {
                drawArc(tint, 180f, 180f, false, Offset(w * 0.24f, h * 0.16f), Size(w * 0.52f, h * 0.52f), style = stroke)
                drawLine(tint, Offset(w * 0.24f, h * 0.42f), Offset(w * 0.18f, h * 0.70f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, h * 0.42f), Offset(w * 0.82f, h * 0.70f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.16f, h * 0.70f), Offset(w * 0.84f, h * 0.70f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.08f, center = Offset(w * 0.50f, h * 0.84f))
            }
            WidgetGlyph.COPY -> {
                drawRoundRect(tint, Offset(w * 0.32f, h * 0.16f), Size(w * 0.50f, h * 0.56f), CornerRadius(2.dp.toPx()), stroke)
                drawRoundRect(tint, Offset(w * 0.18f, h * 0.30f), Size(w * 0.50f, h * 0.56f), CornerRadius(2.dp.toPx()), stroke)
            }
            WidgetGlyph.CHECK -> {
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.74f)
                    lineTo(w * 0.82f, h * 0.28f)
                }
                drawPath(p, tint, style = stroke)
            }
            WidgetGlyph.PASTE -> {
                drawRoundRect(tint, Offset(w * 0.20f, h * 0.22f), Size(w * 0.60f, h * 0.66f), CornerRadius(2.dp.toPx()), stroke)
                drawRoundRect(tint, Offset(w * 0.36f, h * 0.12f), Size(w * 0.28f, h * 0.16f), CornerRadius(1.5.dp.toPx()))
            }
            WidgetGlyph.TRASH -> {
                drawLine(tint, Offset(w * 0.18f, h * 0.26f), Offset(w * 0.82f, h * 0.26f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.62f, h * 0.16f), sw, StrokeCap.Round)
                drawRoundRect(tint, Offset(w * 0.26f, h * 0.26f), Size(w * 0.48f, h * 0.58f), CornerRadius(2.dp.toPx()), stroke)
            }
            WidgetGlyph.PLUS -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.20f), Offset(w * 0.50f, h * 0.80f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.80f, h * 0.50f), sw, StrokeCap.Round)
            }
            WidgetGlyph.SPLIT_SCREEN -> {
                drawRoundRect(tint, Offset(w * 0.12f, h * 0.18f), Size(w * 0.76f, h * 0.64f), CornerRadius(2.4.dp.toPx()), stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.20f), Offset(w * 0.50f, h * 0.80f), sw, StrokeCap.Round)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Smart OTP Verification Code Extraction (Chinese & English SMS / Notifications)
// ---------------------------------------------------------------------------

private val OTP_KEYWORD_BEFORE_REGEX = Regex(
    """(?:验证码|校验码|动态码|安全码|登录码|认证码|确认码|随机码|激活码|动态口令|口令|verification\s*code|security\s*code|one[- ]time(?:\s+pass)?code|passcode|auth(?:entication)?\s*code|login\s*code|pin\s*code|otp|code)[^0-9]{0,18}(?<!\d)(\d{4,8})(?![\d\-:/元块%]|\.\d)(?!\s*年)""",
    setOf(RegexOption.IGNORE_CASE),
)

private val OTP_KEYWORD_AFTER_REGEX = Regex(
    """(?<!\d)(\d{4,8})(?!\d)[^0-9]{0,16}(?:为|是)?(?:您的|你的|本次)?(?:验证码|校验码|动态码|安全码|登录码|认证码|is\s+your\s+(?:verification|security|login|one[- ]time)?\s*code|verification\s*code|otp)""",
    setOf(RegexOption.IGNORE_CASE),
)

private val OTP_CONTEXT_HINT_REGEX = Regex(
    """验证码|校验码|动态码|安全码|登录码|认证码|确认码|口令|verify|verification|security\s*code|login\s*code|otp|passcode|one[- ]time|2fa|sign[- ]in\s*code|auth\s*code""",
    setOf(RegexOption.IGNORE_CASE),
)

private val OTP_STANDALONE_DIGITS_REGEX = Regex("""(?<![\d¥$€£￥.\-:/])(\d{4,8})(?![\d.\-:/元块%])""")

/**
 * Extracts a 4–8 digit verification code from Chinese or English notification/SMS text.
 * Returns null when the message does not appear to contain an OTP code.
 */
fun extractOtpCode(text: String): String? {
    if (text.isBlank()) return null
    val normalized = text.replace('\n', ' ').replace('\r', ' ')
    OTP_KEYWORD_BEFORE_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.let { return it }
    OTP_KEYWORD_AFTER_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.let { return it }
    if (OTP_CONTEXT_HINT_REGEX.containsMatchIn(normalized)) {
        return OTP_STANDALONE_DIGITS_REGEX.findAll(normalized)
            .map { it.groupValues[1] }
            .firstOrNull { candidate ->
                // Avoid plain calendar years like 2025/2026 when a real 6-digit code isn't found
                !(candidate.length == 4 && (candidate.startsWith("19") || candidate.startsWith("20")))
            }
    }
    return null
}

// ---------------------------------------------------------------------------
// Lightweight Pure-Kotlin Config Codec for QuickNotes & TodoChecklist
// ---------------------------------------------------------------------------

data class QuickNotesConfig(
    val noteText: String = "",
    val tintIndex: Int = 0,
)

data class TodoItemConfig(
    val id: String,
    val text: String,
    val done: Boolean = false,
)

data class TodoChecklistConfig(
    val items: List<TodoItemConfig> = emptyList(),
)

fun encodeQuickNotesConfig(config: QuickNotesConfig): String {
    val safeTint = config.tintIndex.coerceIn(0, 3)
    return """{"noteText":"${escapeJsonString(config.noteText)}","tintIndex":$safeTint}"""
}

fun decodeQuickNotesConfig(configJson: String): QuickNotesConfig {
    if (configJson.isBlank()) return QuickNotesConfig()
    val noteText = extractJsonStringField(configJson, "noteText").orEmpty()
    val tintIndex = extractJsonIntField(configJson, "tintIndex")?.coerceIn(0, 3) ?: 0
    return QuickNotesConfig(noteText = noteText, tintIndex = tintIndex)
}

fun encodeTodoChecklistConfig(config: TodoChecklistConfig): String {
    val sb = StringBuilder(64)
    sb.append("""{"items":[""")
    config.items.forEachIndexed { idx, item ->
        if (idx > 0) sb.append(',')
        sb.append("""{"id":"${escapeJsonString(item.id)}","text":"${escapeJsonString(item.text)}","done":${item.done}}""")
    }
    sb.append("]}")
    return sb.toString()
}

fun decodeTodoChecklistConfig(configJson: String): TodoChecklistConfig {
    if (configJson.isBlank()) return TodoChecklistConfig()
    val arrayStart = findKeyArrayStart(configJson, "items") ?: return TodoChecklistConfig()
    val items = mutableListOf<TodoItemConfig>()
    var cursor = arrayStart + 1
    val len = configJson.length
    while (cursor < len) {
        while (cursor < len && (configJson[cursor].isWhitespace() || configJson[cursor] == ',')) cursor++
        if (cursor >= len || configJson[cursor] == ']') break
        if (configJson[cursor] == '{') {
            val objEnd = findMatchingBrace(configJson, cursor) ?: break
            val objSlice = configJson.substring(cursor, objEnd + 1)
            val id = extractJsonStringField(objSlice, "id").orEmpty().ifBlank { "t_${items.size}" }
            val text = extractJsonStringField(objSlice, "text").orEmpty()
            val done = extractJsonBooleanField(objSlice, "done") ?: false
            if (text.isNotBlank()) {
                items += TodoItemConfig(id = id, text = text, done = done)
            }
            cursor = objEnd + 1
        } else {
            cursor++
        }
    }
    return TodoChecklistConfig(items = items)
}

private fun escapeJsonString(raw: String): String {
    val sb = StringBuilder(raw.length + 8)
    for (ch in raw) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    return sb.toString()
}

private fun findJsonKeyValueStart(json: String, key: String): Int? {
    val token = "\"$key\""
    var idx = 0
    val len = json.length
    while (idx < len) {
        val pos = json.indexOf(token, idx)
        if (pos < 0) return null
        var after = pos + token.length
        while (after < len && json[after].isWhitespace()) after++
        if (after < len && json[after] == ':') {
            after++
            while (after < len && json[after].isWhitespace()) after++
            return after
        }
        idx = pos + token.length
    }
    return null
}

private fun extractJsonStringField(json: String, key: String): String? {
    val start = findJsonKeyValueStart(json, key) ?: return null
    if (start >= json.length || json[start] != '"') return null
    val sb = StringBuilder()
    var i = start + 1
    val len = json.length
    while (i < len) {
        val ch = json[i]
        if (ch == '"') return sb.toString()
        if (ch == '\\' && i + 1 < len) {
            i++
            when (val esc = json[i]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    if (i + 4 < len) {
                        val hex = json.substring(i + 1, i + 5)
                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    }
                }
                else -> sb.append(esc)
            }
        } else {
            sb.append(ch)
        }
        i++
    }
    return sb.toString()
}

private fun extractJsonIntField(json: String, key: String): Int? {
    val start = findJsonKeyValueStart(json, key) ?: return null
    var end = start
    if (end < json.length && json[end] == '-') end++
    while (end < json.length && json[end].isDigit()) end++
    return json.substring(start, end).toIntOrNull()
}

private fun extractJsonBooleanField(json: String, key: String): Boolean? {
    val start = findJsonKeyValueStart(json, key) ?: return null
    return when {
        json.startsWith("true", start) -> true
        json.startsWith("false", start) -> false
        else -> null
    }
}

private fun findKeyArrayStart(json: String, key: String): Int? {
    val start = findJsonKeyValueStart(json, key) ?: return null
    return if (start < json.length && json[start] == '[') start else null
}

private fun findMatchingBrace(json: String, openIndex: Int): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in openIndex until json.length) {
        val ch = json[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return null
}
