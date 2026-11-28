package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout

enum class ChromeGlyph {
    BACK,
    MINIMIZE,
    MAXIMIZE,
    RESTORE,
    CLOSE,
    ORIENTATION,
    APPS_GRID,
    EXPOSE,
    CONSOLE,
    SEARCH,
    RELOAD,
    HOME_DESKTOP,
    TILE_LEFT,
    TILE_RIGHT,
    CONTROL_CENTER,
    WIFI,
    WIFI_OFF,
    KEYBOARD,
    EXTERNAL_MONITOR,
    LOCK,
    SCREEN_OFF,
    MIC,
    CAMERA,
    CAMERA_FLIP,
    HOME_HOUSE,
    EXIT_FULLSCREEN,
    CHEVRON_UP,
    CHEVRON_DOWN,
}

@Composable
fun OpalGlyphButton(
    glyph: ChromeGlyph,
    tint: Color = OpalPalette.TextPrimary,
    size: Dp = 28.dp,
    dangerHover: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        dangerHover -> OpalPalette.AccentDanger.copy(alpha = 0.14f)
        active -> OpalPalette.ChipActiveFill
        else -> OpalPalette.ChipFill
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(glyph = glyph, tint = tint, size = size * 0.50f)
    }
}

@Composable
fun OpalGlyphIcon(
    glyph: ChromeGlyph,
    tint: Color = OpalPalette.TextPrimary,
    size: Dp = 15.dp,
) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 1.6.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        when (glyph) {
            ChromeGlyph.BACK -> {
                drawLine(tint, Offset(w * 0.68f, h * 0.16f), Offset(w * 0.28f, h * 0.5f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.5f), Offset(w * 0.68f, h * 0.84f), sw, StrokeCap.Round)
            }
            ChromeGlyph.MINIMIZE -> {
                drawLine(tint, Offset(w * 0.18f, h * 0.52f), Offset(w * 0.82f, h * 0.52f), sw, StrokeCap.Round)
            }
            ChromeGlyph.MAXIMIZE -> {
                drawRoundRect(tint, Offset(w * 0.16f, h * 0.16f), Size(w * 0.68f, h * 0.68f), CornerRadius(2.dp.toPx()), Stroke(sw))
            }
            ChromeGlyph.RESTORE -> {
                drawRoundRect(tint, Offset(w * 0.28f, h * 0.14f), Size(w * 0.56f, h * 0.56f), CornerRadius(1.8.dp.toPx()), Stroke(sw * 0.88f))
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.30f), Size(w * 0.56f, h * 0.56f), CornerRadius(1.8.dp.toPx()), Stroke(sw))
            }
            ChromeGlyph.CLOSE -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), sw, StrokeCap.Round)
            }
            ChromeGlyph.ORIENTATION -> {
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.12f), Size(w * 0.52f, h * 0.76f), CornerRadius(2.dp.toPx()), Stroke(sw))
            }
            ChromeGlyph.APPS_GRID -> {
                val r = w * 0.105f
                for (gx in listOf(0.20f, 0.50f, 0.80f)) {
                    for (gy in listOf(0.20f, 0.50f, 0.80f)) {
                        drawCircle(tint, radius = r, center = Offset(w * gx, h * gy))
                    }
                }
            }
            ChromeGlyph.EXPOSE -> {
                drawRoundRect(tint, Offset(w * 0.08f, h * 0.14f), Size(w * 0.37f, h * 0.32f), CornerRadius(1.6.dp.toPx()), Stroke(sw * 0.9f))
                drawRoundRect(tint, Offset(w * 0.55f, h * 0.14f), Size(w * 0.37f, h * 0.32f), CornerRadius(1.6.dp.toPx()), Stroke(sw * 0.9f))
                drawRoundRect(tint, Offset(w * 0.20f, h * 0.56f), Size(w * 0.60f, h * 0.30f), CornerRadius(1.6.dp.toPx()), Stroke(sw * 0.9f))
            }
            ChromeGlyph.CONSOLE, ChromeGlyph.CONTROL_CENTER -> {
                drawLine(tint, Offset(w * 0.12f, h * 0.30f), Offset(w * 0.88f, h * 0.30f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.12f, h * 0.70f), Offset(w * 0.88f, h * 0.70f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.34f, h * 0.30f))
                drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.66f, h * 0.70f))
            }
            ChromeGlyph.SEARCH -> {
                drawCircle(tint, radius = w * 0.30f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(sw))
                drawLine(tint, Offset(w * 0.65f, h * 0.65f), Offset(w * 0.86f, h * 0.86f), sw * 1.08f, StrokeCap.Round)
            }
            ChromeGlyph.RELOAD -> {
                drawArc(
                    color = tint,
                    startAngle = -55f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.66f, h * 0.14f), Offset(w * 0.82f, h * 0.30f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.82f, h * 0.12f), Offset(w * 0.82f, h * 0.30f), sw, StrokeCap.Round)
            }
            ChromeGlyph.HOME_DESKTOP -> {
                drawCircle(tint, radius = w * 0.32f, center = Offset(w * 0.50f, h * 0.50f), style = Stroke(sw))
            }
            ChromeGlyph.TILE_LEFT -> {
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.16f), Size(w * 0.80f, h * 0.68f), CornerRadius(2.dp.toPx()), Stroke(sw * 0.9f))
                drawRoundRect(tint.copy(alpha = 0.75f), Offset(w * 0.15f, h * 0.22f), Size(w * 0.34f, h * 0.56f), CornerRadius(1.2.dp.toPx()))
            }
            ChromeGlyph.TILE_RIGHT -> {
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.16f), Size(w * 0.80f, h * 0.68f), CornerRadius(2.dp.toPx()), Stroke(sw * 0.9f))
                drawRoundRect(tint.copy(alpha = 0.75f), Offset(w * 0.51f, h * 0.22f), Size(w * 0.34f, h * 0.56f), CornerRadius(1.2.dp.toPx()))
            }
            ChromeGlyph.WIFI -> {
                drawArc(tint, 225f, 90f, false, Offset(w * 0.08f, h * 0.18f), Size(w * 0.84f, h * 0.84f), style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(tint, 225f, 90f, false, Offset(w * 0.24f, h * 0.38f), Size(w * 0.52f, h * 0.52f), style = Stroke(sw, cap = StrokeCap.Round))
                drawCircle(tint, radius = w * 0.085f, center = Offset(w * 0.50f, h * 0.78f))
            }
            ChromeGlyph.WIFI_OFF -> {
                val muted = tint.copy(alpha = 0.55f)
                drawArc(muted, 225f, 90f, false, Offset(w * 0.08f, h * 0.18f), Size(w * 0.84f, h * 0.84f), style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(muted, 225f, 90f, false, Offset(w * 0.24f, h * 0.38f), Size(w * 0.52f, h * 0.52f), style = Stroke(sw, cap = StrokeCap.Round))
                drawCircle(muted, radius = w * 0.085f, center = Offset(w * 0.50f, h * 0.78f))
                drawLine(tint, Offset(w * 0.16f, h * 0.16f), Offset(w * 0.84f, h * 0.84f), sw, StrokeCap.Round)
            }
            ChromeGlyph.KEYBOARD -> {
                drawRoundRect(tint, Offset(w * 0.08f, h * 0.22f), Size(w * 0.84f, h * 0.56f), CornerRadius(2.dp.toPx()), Stroke(sw * 0.9f))
                drawLine(tint, Offset(w * 0.28f, h * 0.62f), Offset(w * 0.72f, h * 0.62f), sw, StrokeCap.Round)
                for (gx in listOf(0.26f, 0.42f, 0.58f, 0.74f)) {
                    drawCircle(tint, radius = w * 0.045f, center = Offset(w * gx, h * 0.42f))
                }
            }
            ChromeGlyph.EXTERNAL_MONITOR -> {
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.14f), Size(w * 0.80f, h * 0.56f), CornerRadius(2.dp.toPx()), Stroke(sw))
                drawLine(tint, Offset(w * 0.34f, h * 0.84f), Offset(w * 0.66f, h * 0.84f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.84f), sw, StrokeCap.Round)
            }
            ChromeGlyph.LOCK -> {
                drawRoundRect(
                    tint,
                    Offset(w * 0.20f, h * 0.46f),
                    Size(w * 0.60f, h * 0.42f),
                    CornerRadius(2.2.dp.toPx()),
                    Stroke(sw * 0.95f),
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.31f, h * 0.14f),
                    size = Size(w * 0.38f, h * 0.38f),
                    style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.31f, h * 0.33f), Offset(w * 0.31f, h * 0.46f), sw * 0.95f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.69f, h * 0.33f), Offset(w * 0.69f, h * 0.46f), sw * 0.95f, StrokeCap.Round)
            }
            ChromeGlyph.SCREEN_OFF -> {
                drawArc(
                    color = tint,
                    startAngle = -52f,
                    sweepAngle = 284f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.18f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.50f, h * 0.10f), Offset(w * 0.50f, h * 0.46f), sw, StrokeCap.Round)
            }
            ChromeGlyph.MIC -> {
                drawRoundRect(
                    tint,
                    Offset(w * 0.34f, h * 0.10f),
                    Size(w * 0.32f, h * 0.46f),
                    CornerRadius(w * 0.16f),
                    Stroke(sw),
                )
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.20f, h * 0.24f),
                    size = Size(w * 0.60f, h * 0.46f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.88f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.68f, h * 0.88f), sw, StrokeCap.Round)
            }
            ChromeGlyph.CAMERA -> {
                drawRoundRect(
                    tint,
                    Offset(w * 0.08f, h * 0.24f),
                    Size(w * 0.54f, h * 0.52f),
                    CornerRadius(2.2.dp.toPx()),
                    Stroke(sw),
                )
                val lens = Path().apply {
                    moveTo(w * 0.64f, h * 0.40f)
                    lineTo(w * 0.92f, h * 0.27f)
                    lineTo(w * 0.92f, h * 0.73f)
                    lineTo(w * 0.64f, h * 0.60f)
                    close()
                }
                drawPath(lens, tint, style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ChromeGlyph.CAMERA_FLIP -> {
                drawArc(
                    color = tint,
                    startAngle = 210f,
                    sweepAngle = 125f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.68f, h * 0.28f), Offset(w * 0.82f, h * 0.34f), sw * 0.95f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.82f, h * 0.19f), Offset(w * 0.82f, h * 0.34f), sw * 0.95f, StrokeCap.Round)
                drawArc(
                    color = tint,
                    startAngle = 30f,
                    sweepAngle = 125f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.32f, h * 0.72f), Offset(w * 0.18f, h * 0.66f), sw * 0.95f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.18f, h * 0.81f), Offset(w * 0.18f, h * 0.66f), sw * 0.95f, StrokeCap.Round)
            }
            ChromeGlyph.HOME_HOUSE -> {
                val roof = Path().apply {
                    moveTo(w * 0.12f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.15f)
                    lineTo(w * 0.88f, h * 0.48f)
                }
                drawPath(roof, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val walls = Path().apply {
                    moveTo(w * 0.24f, h * 0.44f)
                    lineTo(w * 0.24f, h * 0.84f)
                    lineTo(w * 0.76f, h * 0.84f)
                    lineTo(w * 0.76f, h * 0.44f)
                }
                drawPath(walls, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ChromeGlyph.EXIT_FULLSCREEN -> {
                val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val corners = listOf(
                    Triple(Offset(w * 0.14f, h * 0.38f), Offset(w * 0.38f, h * 0.38f), Offset(w * 0.38f, h * 0.14f)),
                    Triple(Offset(w * 0.86f, h * 0.38f), Offset(w * 0.62f, h * 0.38f), Offset(w * 0.62f, h * 0.14f)),
                    Triple(Offset(w * 0.14f, h * 0.62f), Offset(w * 0.38f, h * 0.62f), Offset(w * 0.38f, h * 0.86f)),
                    Triple(Offset(w * 0.86f, h * 0.62f), Offset(w * 0.62f, h * 0.62f), Offset(w * 0.62f, h * 0.86f)),
                )
                corners.forEach { (a, b, c) ->
                    val p = Path().apply {
                        moveTo(a.x, a.y)
                        lineTo(b.x, b.y)
                        lineTo(c.x, c.y)
                    }
                    drawPath(p, tint, style = stroke)
                }
            }
            ChromeGlyph.CHEVRON_UP -> {
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.64f)
                    lineTo(w * 0.50f, h * 0.34f)
                    lineTo(w * 0.80f, h * 0.64f)
                }
                drawPath(p, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ChromeGlyph.CHEVRON_DOWN -> {
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.36f)
                    lineTo(w * 0.50f, h * 0.66f)
                    lineTo(w * 0.80f, h * 0.36f)
                }
                drawPath(p, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * Crisp miniature geometric preview diagram for each pro desktop multi-window tiling preset.
 */
@Composable
fun TilingLayoutPreviewIcon(
    layout: WindowTilingLayout,
    tint: Color = OpalPalette.TextPrimary,
    size: Dp = 26.dp,
) {
    Canvas(modifier = Modifier.size(width = size * 1.45f, height = size)) {
        val w = this.size.width
        val h = this.size.height
        val pad = 1.5.dp.toPx()
        val gap = 2.dp.toPx()
        val cr = CornerRadius(2.4.dp.toPx(), 2.4.dp.toPx())
        val stroke = Stroke(width = 1.25.dp.toPx())
        val fill = tint.copy(alpha = 0.20f)
        val innerW = w - pad * 2f
        val innerH = h - pad * 2f

        fun drawPane(x: Float, y: Float, pw: Float, ph: Float) {
            drawRoundRect(fill, Offset(x, y), Size(pw, ph), cr)
            drawRoundRect(tint, Offset(x, y), Size(pw, ph), cr, style = stroke)
        }

        when (layout) {
            WindowTilingLayout.SPLIT_2_COLS -> {
                val cw = (innerW - gap) / 2f
                drawPane(pad, pad, cw, innerH)
                drawPane(pad + cw + gap, pad, cw, innerH)
            }
            WindowTilingLayout.SPLIT_2_MAIN_SIDE -> {
                val mainW = (innerW - gap) * 0.64f
                val sideW = innerW - gap - mainW
                drawPane(pad, pad, mainW, innerH)
                drawPane(pad + mainW + gap, pad, sideW, innerH)
            }
            WindowTilingLayout.SPLIT_3_COLS -> {
                val cw = (innerW - gap * 2f) / 3f
                for (i in 0..2) drawPane(pad + i * (cw + gap), pad, cw, innerH)
            }
            WindowTilingLayout.SPLIT_3_MAIN_STACK -> {
                val mainW = (innerW - gap) * 0.56f
                val sideW = innerW - gap - mainW
                val sh = (innerH - gap) / 2f
                drawPane(pad, pad, mainW, innerH)
                drawPane(pad + mainW + gap, pad, sideW, sh)
                drawPane(pad + mainW + gap, pad + sh + gap, sideW, sh)
            }
            WindowTilingLayout.SPLIT_4_COLS -> {
                val cw = (innerW - gap * 3f) / 4f
                for (i in 0..3) drawPane(pad + i * (cw + gap), pad, cw, innerH)
            }
            WindowTilingLayout.SPLIT_4_GRID -> {
                val cw = (innerW - gap) / 2f
                val ch = (innerH - gap) / 2f
                drawPane(pad, pad, cw, ch)
                drawPane(pad + cw + gap, pad, cw, ch)
                drawPane(pad, pad + ch + gap, cw, ch)
                drawPane(pad + cw + gap, pad + ch + gap, cw, ch)
            }
            WindowTilingLayout.SPLIT_5_COLS -> {
                val g = gap * 0.72f
                val cw = (innerW - g * 4f) / 5f
                for (i in 0..4) drawPane(pad + i * (cw + g), pad, cw, innerH)
            }
            WindowTilingLayout.SPLIT_6_GRID -> {
                val cw = (innerW - gap * 2f) / 3f
                val ch = (innerH - gap) / 2f
                for (r in 0..1) {
                    for (c in 0..2) {
                        drawPane(pad + c * (cw + gap), pad + r * (ch + gap), cw, ch)
                    }
                }
            }
            WindowTilingLayout.SPLIT_8_GRID -> {
                val g = gap * 0.78f
                val cw = (innerW - g * 3f) / 4f
                val ch = (innerH - gap) / 2f
                for (r in 0..1) {
                    for (c in 0..3) {
                        drawPane(pad + c * (cw + g), pad + r * (ch + gap), cw, ch)
                    }
                }
            }
            WindowTilingLayout.CASCADE -> {
                val pw = innerW * 0.62f
                val ph = innerH * 0.64f
                drawPane(pad, pad, pw, ph)
                drawPane(pad + innerW * 0.19f, pad + innerH * 0.18f, pw, ph)
                drawPane(pad + innerW * 0.38f, pad + innerH * 0.36f, pw, ph)
            }
        }
    }
}
