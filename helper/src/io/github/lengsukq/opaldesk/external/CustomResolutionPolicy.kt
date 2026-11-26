package io.github.lengsukq.opaldesk.external

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom output resolution specification shared across USB-C / HDMI external displays,
 * wireless displays, and OpalCast remote streaming sessions.
 */
data class CustomResolutionSpec(
    val width: Int,
    val height: Int,
    val fps: Int = 0, // 0 = auto / preserve native refresh rate
    val label: String = "",
) {
    val key: String
        get() = "${width}x${height}@${fps}"

    val displayText: String
        get() = if (fps > 0) "${width}×${height} · ${fps}Hz" else "${width}×${height} · 自动Hz"

    val shortResolutionText: String
        get() = "${width}×${height}"
}

data class CustomResolutionParseResult(
    val spec: CustomResolutionSpec?,
    val error: String = "",
    val adjustedNote: String = "",
)

data class PeerCastRequestSpec(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
)

/**
 * Pure JVM-testable validation, preset serialization, aspect-ratio labeling, and PeerCast
 * screen-dimension negotiation policies.
 */
object CustomResolutionPolicy {
    const val MIN_LONG_EDGE = 640
    const val MIN_SHORT_EDGE = 480
    const val MAX_LONG_EDGE = 4096
    const val MAX_SHORT_EDGE = 2560
    const val MIN_FPS = 24
    const val MAX_FPS = 144
    const val MAX_SAVED_PRESETS = 12
    const val REVERT_CONFIRM_SECONDS = 15

    val DEFAULT_CUSTOM_SPEC = CustomResolutionSpec(1920, 1080, 60, "1080p")

    /**
     * Built-in quick resolution presets spanning 16:9, 2K, 4K, 16:10, 21:9 ultrawide,
     * lightweight 720p, and 9:16 vertical monitors.
     */
    val BUILTIN_PRESETS: List<CustomResolutionSpec> = listOf(
        CustomResolutionSpec(1920, 1080, 60, "1080p 16:9"),
        CustomResolutionSpec(1920, 1080, 120, "1080p 高刷"),
        CustomResolutionSpec(2560, 1440, 60, "2K 16:9"),
        CustomResolutionSpec(2560, 1440, 120, "2K 高刷"),
        CustomResolutionSpec(2560, 1600, 60, "2.5K 16:10"),
        CustomResolutionSpec(2880, 1800, 60, "Mac 16:10"),
        CustomResolutionSpec(3440, 1440, 60, "21:9 带鱼屏"),
        CustomResolutionSpec(2560, 1080, 60, "21:9 准带鱼"),
        CustomResolutionSpec(3840, 2160, 60, "4K UHD"),
        CustomResolutionSpec(1280, 720, 60, "720p 流畅"),
        CustomResolutionSpec(1080, 1920, 60, "竖屏 9:16"),
    )

    fun sanitize(width: Int, height: Int, fps: Int = 0, label: String = ""): CustomResolutionSpec? {
        if (width <= 0 || height <= 0) return null
        val portrait = height > width
        val minW = if (portrait) MIN_SHORT_EDGE else MIN_LONG_EDGE
        val maxW = if (portrait) MAX_SHORT_EDGE else MAX_LONG_EDGE
        val minH = if (portrait) MIN_LONG_EDGE else MIN_SHORT_EDGE
        val maxH = if (portrait) MAX_LONG_EDGE else MAX_SHORT_EDGE

        var w = width.coerceIn(minW, maxW)
        var h = height.coerceIn(minH, maxH)
        w = (w and 1.inv()).coerceAtLeast(minW)
        h = (h and 1.inv()).coerceAtLeast(minH)

        val cleanFps = if (fps <= 0) 0 else fps.coerceIn(MIN_FPS, MAX_FPS)
        val cleanLabel = sanitizeLabel(label)
        return CustomResolutionSpec(width = w, height = h, fps = cleanFps, label = cleanLabel)
    }

    fun parseUserInput(
        widthRaw: String,
        heightRaw: String,
        fpsRaw: String,
        labelRaw: String = "",
    ): CustomResolutionParseResult {
        val wStr = widthRaw.trim()
        val hStr = heightRaw.trim()
        val fStr = fpsRaw.trim()
        if (wStr.isEmpty() || hStr.isEmpty()) {
            return CustomResolutionParseResult(null, error = "请输入宽度和高度（像素）")
        }
        val rawW = wStr.toIntOrNull()
            ?: return CustomResolutionParseResult(null, error = "宽度必须为整数，例如 2560")
        val rawH = hStr.toIntOrNull()
            ?: return CustomResolutionParseResult(null, error = "高度必须为整数，例如 1440")

        val longEdge = max(rawW, rawH)
        val shortEdge = min(rawW, rawH)
        if (longEdge < MIN_LONG_EDGE || shortEdge < MIN_SHORT_EDGE) {
            return CustomResolutionParseResult(
                null,
                error = "分辨率过小（长边需 ≥ $MIN_LONG_EDGE，短边需 ≥ $MIN_SHORT_EDGE）",
            )
        }
        if (longEdge > MAX_LONG_EDGE || shortEdge > MAX_SHORT_EDGE) {
            return CustomResolutionParseResult(
                null,
                error = "分辨率超出上限（最大支持 ${MAX_LONG_EDGE}×${MAX_SHORT_EDGE}）",
            )
        }

        val rawFps = if (fStr.isEmpty() || fStr == "0") {
            0
        } else {
            val parsedFps = fStr.toIntOrNull()
                ?: return CustomResolutionParseResult(null, error = "刷新率请留空（自动）或输入 $MIN_FPS~$MAX_FPS 的整数")
            if (parsedFps !in MIN_FPS..MAX_FPS) {
                return CustomResolutionParseResult(null, error = "刷新率需在 $MIN_FPS~$MAX_FPS Hz 之间（留空为自动）")
            }
            parsedFps
        }

        val spec = sanitize(rawW, rawH, rawFps, labelRaw)
            ?: return CustomResolutionParseResult(null, error = "分辨率参数无效")

        val note = if (spec.width != rawW || spec.height != rawH) {
            "已自动偶数对齐为 ${spec.width}×${spec.height}"
        } else {
            ""
        }
        return CustomResolutionParseResult(spec = spec, adjustedNote = note)
    }

    fun swapOrientation(width: Int, height: Int): Pair<Int, Int> = height to width

    fun formatAspectRatioLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val ratio = width.toFloat() / height.toFloat()
        val known = listOf(
            (16f / 9f) to "16:9",
            (16f / 10f) to "16:10",
            (21f / 9f) to "21:9",
            (43f / 18f) to "21:9",
            (32f / 9f) to "32:9",
            (4f / 3f) to "4:3",
            (3f / 2f) to "3:2",
            (9f / 16f) to "9:16 竖屏",
            (10f / 16f) to "10:16 竖屏",
            (9f / 21f) to "9:21 竖屏",
            (18f / 43f) to "9:21 竖屏",
            (1f) to "1:1",
        )
        for ((target, tag) in known) {
            if (abs(ratio - target) <= 0.045f) return tag
        }
        return String.format(Locale.US, "%.2f:1", ratio)
    }

    fun sanitizeLabel(raw: String): String =
        raw.trim()
            .replace("|", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace(";", "")
            .take(20)

    fun serializePresets(presets: List<CustomResolutionSpec>): String =
        presets.take(MAX_SAVED_PRESETS).joinToString("\n") { p ->
            "${p.width}|${p.height}|${p.fps}|${sanitizeLabel(p.label)}"
        }

    fun parsePresets(raw: String?): List<CustomResolutionSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<CustomResolutionSpec>()
        for (line in raw.lineSequence()) {
            if (out.size >= MAX_SAVED_PRESETS) break
            val parts = line.trim().split('|')
            if (parts.size < 2) continue
            val w = parts[0].toIntOrNull() ?: continue
            val h = parts[1].toIntOrNull() ?: continue
            val fps = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val label = parts.getOrNull(3).orEmpty()
            val clean = sanitize(w, h, fps, label) ?: continue
            if (seen.add(clean.key)) {
                out.add(clean)
            }
        }
        return out
    }

    fun upsertPreset(
        existing: List<CustomResolutionSpec>,
        spec: CustomResolutionSpec,
    ): List<CustomResolutionSpec> {
        val clean = sanitize(spec.width, spec.height, spec.fps, spec.label) ?: return existing
        val filtered = existing.filterNot { it.key == clean.key }
        return (listOf(clean) + filtered).take(MAX_SAVED_PRESETS)
    }

    fun removePreset(
        existing: List<CustomResolutionSpec>,
        key: String,
    ): List<CustomResolutionSpec> = existing.filterNot { it.key == key }

    /**
     * Computes an aspect-ratio-preserving PeerCast stream specification for Android tablet / phone
     * receivers, eliminating the old non-proportional 2560×1600 clamp that distorted 3:2 / 4:3 tablets.
     */
    fun computePeerCastRequestSpec(
        displayW: Int,
        displayH: Int,
        densityDpi: Int,
        refreshRateHz: Float,
    ): PeerCastRequestSpec {
        val rawW = displayW.coerceAtLeast(MIN_SHORT_EDGE)
        val rawH = displayH.coerceAtLeast(MIN_SHORT_EDGE)
        val portrait = rawH > rawW
        val maxLong = 2560
        val maxShort = 1920
        val longEdge = max(rawW, rawH)
        val shortEdge = min(rawW, rawH)
        val scale = min(
            1.0,
            min(maxLong.toDouble() / longEdge.toDouble(), maxShort.toDouble() / shortEdge.toDouble()),
        )
        var outW = (rawW * scale).roundToInt()
        var outH = (rawH * scale).roundToInt()
        val minW = if (portrait) MIN_SHORT_EDGE else MIN_LONG_EDGE
        val minH = if (portrait) MIN_LONG_EDGE else MIN_SHORT_EDGE
        outW = (outW.coerceIn(minW, if (portrait) maxShort else maxLong) and 1.inv())
        outH = (outH.coerceIn(minH, if (portrait) maxLong else maxShort) and 1.inv())

        val scaledDpi = if (densityDpi > 0) {
            (densityDpi * scale).roundToInt().coerceIn(160, 360)
        } else {
            240
        }
        val cleanFps = refreshRateHz.roundToInt().coerceIn(60, 120)
        return PeerCastRequestSpec(width = outW, height = outH, dpi = scaledDpi, fps = cleanFps)
    }
}
