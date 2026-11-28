package io.github.lengsukq.opaldesk.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
data class OpalGlassBackdropState(
    val blurredWallpaper: ImageBitmap? = null,
    val gradientStops: List<Color> = emptyList(),
    val glowA: Color = Color.Transparent,
    val glowB: Color = Color.Transparent,
    val glowC: Color = Color.Transparent,
    val rootSize: IntSize = IntSize.Zero,
    val rootOriginInRoot: Offset = Offset.Zero,
    val active: Boolean = false,
)

val LocalOpalGlassBackdrop = compositionLocalOf { OpalGlassBackdropState() }

/**
 * Curated macOS-inspired atmospheric desktop wallpapers engineered to shine gently behind
 * translucent Liquid Frosted Glass (液态毛玻璃) in both Light (浅色) and Dark (深色) modes.
 */
@Composable
fun OpalAmbientBackdrop(
    modifier: Modifier = Modifier,
    wallpaperPreset: Int = 0,
    suspendBackdrop: Boolean = false,
    consoleScrim: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = OpalPalette.isDark
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    var loadedWallpaper by remember(customWallpaperPath) {
        mutableStateOf(WallpaperStorage.getCachedWallpaperFast(customWallpaperPath))
    }
    LaunchedEffect(customWallpaperPath, suspendBackdrop) {
        if (suspendBackdrop || customWallpaperPath.isBlank()) return@LaunchedEffect
        val fast = WallpaperStorage.getCachedWallpaperFast(customWallpaperPath)
        if (fast != null) {
            loadedWallpaper = fast
        } else {
            loadedWallpaper = withContext(Dispatchers.IO) {
                WallpaperStorage.loadCachedWallpaper(customWallpaperPath)
            }
        }
    }
    val customBitmap = if (suspendBackdrop) null else loadedWallpaper
    val spec = resolveWallpaperGradientSpec(wallpaperPreset, isDark)
    var blurredWallpaper by remember(customBitmap) { mutableStateOf<ImageBitmap?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(customBitmap) {
        blurredWallpaper = if (customBitmap == null) {
            null
        } else {
            withContext(Dispatchers.Default) { createBlurredBackdropBitmap(customBitmap) }
        }
    }

    val rootModifier = when {
        suspendBackdrop -> modifier.background(OpalPalette.WindowCanvasBg)
        customBitmap != null -> modifier
        else -> modifier.background(Brush.linearGradient(colors = spec.stops))
    }

    val backdropState = remember(
        blurredWallpaper, spec, rootSize, rootOrigin, suspendBackdrop,
    ) {
        OpalGlassBackdropState(
            blurredWallpaper = blurredWallpaper,
            gradientStops = spec.stops,
            glowA = spec.glowA,
            glowB = spec.glowB,
            glowC = spec.glowC,
            rootSize = rootSize,
            rootOriginInRoot = rootOrigin,
            active = !suspendBackdrop && rootSize.width > 0 && rootSize.height > 0,
        )
    }

    // IMPORTANT: Keep a single unconditional Box + content() call site across suspendBackdrop /
    // customBitmap transitions so entering or exiting 100% fullscreen NEVER unmounts/remounts
    // OpalCompositorWorkspace or destroys active WindowSurfaceHost TextureViews!
    Box(
        modifier = rootModifier.onGloballyPositioned { coords ->
            rootSize = coords.size
            rootOrigin = coords.positionInRoot()
        },
    ) {
        if (!suspendBackdrop) {
            if (customBitmap != null) {
                val wallpaperModifier = if (consoleScrim && Build.VERSION.SDK_INT >= 31) {
                    Modifier.fillMaxSize().blur(18.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Image(
                    bitmap = customBitmap,
                    contentDescription = "Desktop Wallpaper",
                    contentScale = ContentScale.Crop,
                    modifier = wallpaperModifier,
                )
                val veilColor = when {
                    consoleScrim && isDark -> Color(0xBD0A101D)
                    consoleScrim -> Color(0xCCEEF3FA)
                    isDark -> Color(0x38060910)
                    else -> Color(0x16FFFFFF)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(veilColor),
                )
            } else {
                // Multi-stop feathered radial gradients match the atmospheric glow without a costly
                // full-screen 2K/4K RenderEffect Gaussian blur pass on every compositor frame.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to spec.glowA,
                            0.45f to spec.glowA.copy(alpha = spec.glowA.alpha * 0.42f),
                            1.0f to Color.Transparent,
                            center = Offset(size.width * 0.20f, size.height * 0.22f),
                            radius = size.maxDimension * 0.58f,
                        ),
                        center = Offset(size.width * 0.20f, size.height * 0.22f),
                        radius = size.maxDimension * 0.58f,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to spec.glowB,
                            0.45f to spec.glowB.copy(alpha = spec.glowB.alpha * 0.42f),
                            1.0f to Color.Transparent,
                            center = Offset(size.width * 0.82f, size.height * 0.72f),
                            radius = size.maxDimension * 0.56f,
                        ),
                        center = Offset(size.width * 0.82f, size.height * 0.72f),
                        radius = size.maxDimension * 0.56f,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to spec.glowC,
                            0.45f to spec.glowC.copy(alpha = spec.glowC.alpha * 0.42f),
                            1.0f to Color.Transparent,
                            center = Offset(size.width * 0.52f, size.height * 0.88f),
                            radius = size.maxDimension * 0.44f,
                        ),
                        center = Offset(size.width * 0.52f, size.height * 0.88f),
                        radius = size.maxDimension * 0.44f,
                    )
                }
            }
        }
        CompositionLocalProvider(LocalOpalGlassBackdrop provides backdropState) {
            content()
        }
    }
}

/**
 * Generates a compact, vibrantly saturated Gaussian-approximated blurred copy of the desktop
 * wallpaper once on a background thread so every Liquid Glass panel can sample exact blurred
 * backdrop pixels at 120fps with zero per-frame 4K blur overhead.
 */
private fun createBlurredBackdropBitmap(source: ImageBitmap): ImageBitmap? = runCatching {
    val src = source.asAndroidBitmap()
    if (src.width <= 0 || src.height <= 0) return null
    val maxDim = 280
    val scale = maxDim.toFloat() / max(src.width, src.height).coerceAtLeast(1)
    val dstW = (src.width * scale).roundToInt().coerceIn(24, maxDim)
    val dstH = (src.height * scale).roundToInt().coerceIn(24, maxDim)
    val small = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.24f) })
    }
    AndroidCanvas(small).drawBitmap(src, null, Rect(0, 0, dstW, dstH), paint)
    fastBoxBlurInPlace(small, radius = 9, passes = 3)
    small.asImageBitmap()
}.getOrNull()

private fun fastBoxBlurInPlace(bitmap: Bitmap, radius: Int, passes: Int) {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 3 || h < 3 || radius < 1) return
    val pix = IntArray(w * h)
    val tmp = IntArray(w * h)
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)
    val div = radius * 2 + 1

    repeat(passes) {
        for (y in 0 until h) {
            val rowOff = y * w
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val c = pix[rowOff + i.coerceIn(0, w - 1)]
                rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF
            }
            for (x in 0 until w) {
                tmp[rowOff + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addC = pix[rowOff + (x + radius + 1).coerceIn(0, w - 1)]
                val subC = pix[rowOff + (x - radius).coerceIn(0, w - 1)]
                rSum += ((addC shr 16) and 0xFF) - ((subC shr 16) and 0xFF)
                gSum += ((addC shr 8) and 0xFF) - ((subC shr 8) and 0xFF)
                bSum += (addC and 0xFF) - (subC and 0xFF)
            }
        }
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF
            }
            for (y in 0 until h) {
                pix[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addC = tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                val subC = tmp[(y - radius).coerceIn(0, h - 1) * w + x]
                rSum += ((addC shr 16) and 0xFF) - ((subC shr 16) and 0xFF)
                gSum += ((addC shr 8) and 0xFF) - ((subC shr 8) and 0xFF)
                bSum += (addC and 0xFF) - (subC and 0xFF)
            }
        }
    }
    bitmap.setPixels(pix, 0, w, 0, 0, w, h)
}

private data class WallpaperGradientSpec(
    val stops: List<Color>,
    val glowA: Color,
    val glowB: Color,
    val glowC: Color,
)

private fun resolveWallpaperGradientSpec(wallpaperPreset: Int, isDark: Boolean): WallpaperGradientSpec {
    val stops = if (isDark) {
        when (wallpaperPreset) {
            1 -> listOf(Color(0xFF0E1324), Color(0xFF212950), Color(0xFF422D5C), Color(0xFF1A1628))
            2 -> listOf(Color(0xFF08141E), Color(0xFF0E2C42), Color(0xFF154760), Color(0xFF0B1722))
            3 -> listOf(Color(0xFF111318), Color(0xFF1C2029), Color(0xFF171A22), Color(0xFF0D0F14))
            else -> listOf(Color(0xFF0A1324), Color(0xFF152D4B), Color(0xFF1F3C64), Color(0xFF12192A))
        }
    } else {
        when (wallpaperPreset) {
            1 -> listOf(Color(0xFFF2E2F8), Color(0xFFFBDCE8), Color(0xFFFDE9DA), Color(0xFFF2EBF9))
            2 -> listOf(Color(0xFFD2F0FA), Color(0xFFBFE7F4), Color(0xFFD8F3EF), Color(0xFFECF8FC))
            3 -> listOf(Color(0xFFE9EEF6), Color(0xFFDCE3EE), Color(0xFFE7ECF4), Color(0xFFF3F6FA))
            else -> listOf(Color(0xFFDCE9FC), Color(0xFFC8DEFB), Color(0xFFE2E8FC), Color(0xFFEEF3FD))
        }
    }
    val glowA = if (isDark) {
        when (wallpaperPreset) {
            1 -> Color(0x52E07A5F)
            2 -> Color(0x4A38BDF8)
            3 -> Color(0x2B94A3B8)
            else -> Color(0x4D2A9D8F)
        }
    } else {
        when (wallpaperPreset) {
            1 -> Color(0x7AF472B6)
            2 -> Color(0x7538BDF8)
            3 -> Color(0x5294A3B8)
            else -> Color(0x7A60A5FA)
        }
    }
    val glowB = if (isDark) {
        when (wallpaperPreset) {
            1 -> Color(0x46818CF8)
            2 -> Color(0x3D2DD4BF)
            3 -> Color(0x2860A5FA)
            else -> Color(0x486366F1)
        }
    } else {
        when (wallpaperPreset) {
            1 -> Color(0x6EFB923C)
            2 -> Color(0x6834D399)
            3 -> Color(0x4860A5FA)
            else -> Color(0x64A78BFA)
        }
    }
    val glowC = if (isDark) {
        Color(0x2838BDF8)
    } else {
        when (wallpaperPreset) {
            1 -> Color(0x52C084FC)
            2 -> Color(0x522DD4BF)
            3 -> Color(0x3ACBD5E1)
            else -> Color(0x5638BDF8)
        }
    }
    return WallpaperGradientSpec(stops = stops, glowA = glowA, glowB = glowB, glowC = glowC)
}
