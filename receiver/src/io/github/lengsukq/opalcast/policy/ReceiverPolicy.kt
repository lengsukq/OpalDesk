package io.github.lengsukq.opalcast.policy

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure JVM-testable policies for OpalCast (standalone universal 32/64-bit Android 5.0–17
 * Cast Receiver). Zero Android framework runtime dependencies.
 */
object ReceiverPolicy {
    const val DEFAULT_CAST_PORT = 19878
    const val PROJECT_INTRO_URL = "https://lengsukq.github.io/OpalDesk/"
    const val PROJECT_GITHUB_URL = "https://github.com/lengsukq/OpalDesk"
    const val COMMUNITY_QQ_GROUP_NUMBER = "1042317917"

    fun buildQqGroupDeepLink(groupNumber: String = COMMUNITY_QQ_GROUP_NUMBER): String =
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=${groupNumber.trim()}&card_type=group&source=qrcode"

    const val CODEC_PREF_AUTO = 0
    const val CODEC_PREF_H264 = 1
    const val CODEC_PREF_HEVC = 2

    const val WIRE_CODEC_H264 = "h264"
    const val WIRE_CODEC_HEVC = "hevc"

    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"

    const val TRANSPORT_HINT_WIFI_TLS = "wifi_tls"
    const val TRANSPORT_HINT_USB_TETHER = "usb_tether"

    const val MIN_BITRATE_KBPS = 2_500
    const val MAX_WIFI_BITRATE_KBPS = 32_000
    const val MAX_USB_TETHER_BITRATE_KBPS = 50_000

    private const val MIN_LONG_EDGE = 640
    private const val MIN_SHORT_EDGE = 480

    data class CodecDescriptor(
        val name: String,
        val isEncoder: Boolean,
        val supportedTypes: List<String>,
        val isSoftwareOnlyFlag: Boolean? = null,
        val supportsLowLatency: Boolean = false,
    )

    data class PreferredDecoderChoice(
        val hwName: String?,
        val supportsLowLatency: Boolean,
        val swName: String?,
    )

    data class HardwareDecoderSummary(
        val hasHardwareAvc: Boolean,
        val hasHardwareHevc: Boolean,
    )

    data class PeerCastRequestSpec(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val fps: Int,
    )

    data class ParsedEndpoint(
        val host: String,
        val port: Int,
    )

    fun normalizeCodecPreference(raw: Int): Int = when (raw) {
        CODEC_PREF_H264 -> CODEC_PREF_H264
        CODEC_PREF_HEVC -> CODEC_PREF_HEVC
        else -> CODEC_PREF_AUTO
    }

    fun is64BitAbiList(supportedAbis: Array<String>): Boolean =
        supportedAbis.any { abi ->
            val clean = abi.trim().lowercase(Locale.ROOT)
            clean == "arm64-v8a" || clean == "x86_64" || clean.contains("64")
        }

    fun primaryAbiLabel(supportedAbis: Array<String>): String {
        val primary = supportedAbis.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (primary.isEmpty()) return "未知架构"
        val bits = if (primary.contains("64")) "64位" else "32位"
        return "$primary · $bits"
    }

    /**
     * Identifies software-only MediaCodec decoder names across Android 5–17 (including API 21–28
     * where `MediaCodecInfo.isSoftwareOnly` does not exist).
     */
    fun isSoftwareDecoderName(codecName: String): Boolean {
        val lower = codecName.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return true
        return lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.startsWith("omx.ffmpeg.") ||
            lower.startsWith("omx.avcodec.") ||
            lower.startsWith("avcodec.") ||
            lower.contains(".sw.") ||
            lower.endsWith(".sw") ||
            lower.contains("soft") ||
            lower.contains("swdec")
    }

    fun isCodecSoftwareOnly(codec: CodecDescriptor): Boolean =
        codec.isSoftwareOnlyFlag ?: isSoftwareDecoderName(codec.name)

    fun hasHardwareDecoder(codecs: List<CodecDescriptor>, mime: String): Boolean =
        codecs.any { info ->
            !info.isEncoder &&
                !isCodecSoftwareOnly(info) &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    fun summarizeHardwareDecoders(codecs: List<CodecDescriptor>): HardwareDecoderSummary =
        HardwareDecoderSummary(
            hasHardwareAvc = hasHardwareDecoder(codecs, MIME_AVC),
            hasHardwareHevc = hasHardwareDecoder(codecs, MIME_HEVC),
        )

    fun resolvePreferredCastCodec(preference: Int, hasHardwareHevcDecoder: Boolean): String =
        when (normalizeCodecPreference(preference)) {
            CODEC_PREF_H264 -> WIRE_CODEC_H264
            CODEC_PREF_HEVC -> WIRE_CODEC_HEVC
            else -> if (hasHardwareHevcDecoder) WIRE_CODEC_HEVC else WIRE_CODEC_H264
        }

    fun selectPreferredDecoder(codecs: List<CodecDescriptor>, mime: String): PreferredDecoderChoice {
        var hwLowLatency: String? = null
        var hwFirst: String? = null
        var swFirst: String? = null
        for (info in codecs) {
            if (info.isEncoder) continue
            val matched = info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            if (!matched) continue
            if (isCodecSoftwareOnly(info)) {
                if (swFirst == null) swFirst = info.name
                continue
            }
            if (hwFirst == null) hwFirst = info.name
            if (hwLowLatency == null && info.supportsLowLatency) {
                hwLowLatency = info.name
            }
        }
        return if (hwLowLatency != null) {
            PreferredDecoderChoice(hwName = hwLowLatency, supportsLowLatency = true, swName = swFirst)
        } else {
            PreferredDecoderChoice(hwName = hwFirst, supportsLowLatency = false, swName = swFirst)
        }
    }

    /**
     * Filters supported TLS protocol names to TLS 1.3 and TLS 1.2 (newest first), matching
     * OpalDesk `RemoteStreamServer` across Android 5.0–17.
     */
    fun filterSecureTlsProtocols(supported: Array<String>?): Array<String> {
        if (supported == null || supported.isEmpty()) return emptyArray()
        val ordered = ArrayList<String>(2)
        if (supported.any { it == "TLSv1.3" }) ordered.add("TLSv1.3")
        if (supported.any { it == "TLSv1.2" }) ordered.add("TLSv1.2")
        return ordered.toTypedArray()
    }

    /**
     * Selects `TYPE_APPLICATION_OVERLAY` (2038) on Android 8.0+ (API 26+) and
     * `TYPE_PHONE` (2002) on Android 5.0–7.1 (API 21–25).
     */
    fun overlayWindowType(sdkInt: Int): Int =
        if (sdkInt >= 26) 2038 else 2002

    fun resolveTransportHint(isUsbTether: Boolean): String =
        if (isUsbTether) TRANSPORT_HINT_USB_TETHER else TRANSPORT_HINT_WIFI_TLS

    fun parseHostPort(rawInput: String?, defaultPort: Int = DEFAULT_CAST_PORT): ParsedEndpoint? {
        val text = rawInput?.trim().orEmpty()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        // Bracketed IPv6: [fe80::1]:19878 or [::1]
        if (text.startsWith("[")) {
            val closeIdx = text.indexOf(']')
            if (closeIdx <= 1) return null
            val host = text.substring(1, closeIdx).trim()
            if (host.isEmpty()) return null
            val rest = text.substring(closeIdx + 1)
            if (rest.isEmpty()) return ParsedEndpoint(host, defaultPort)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return ParsedEndpoint(host, port)
        }
        val colonCount = text.count { it == ':' }
        return when {
            colonCount == 0 -> ParsedEndpoint(text, defaultPort)
            colonCount == 1 -> {
                val idx = text.indexOf(':')
                val host = text.substring(0, idx).trim()
                val port = text.substring(idx + 1).trim().toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                ParsedEndpoint(host, port)
            }
            else -> ParsedEndpoint(text, defaultPort) // Bare IPv6 address
        }
    }

    /**
     * Computes an aspect-ratio-preserving stream request spec for Android phones, tablets,
     * and TV boxes across low-end 720p to high-refresh 2.5K/3K displays.
     */
    fun computePeerCastRequestSpec(
        displayW: Int,
        displayH: Int,
        densityDpi: Int,
        refreshRateHz: Float,
        sdkInt: Int = 30,
    ): PeerCastRequestSpec {
        val rawW = displayW.coerceAtLeast(MIN_SHORT_EDGE)
        val rawH = displayH.coerceAtLeast(MIN_SHORT_EDGE)
        val portrait = rawH > rawW
        // Cap older Android 5–8 decoders (API 21–27) at 1920x1200@60 for rock-solid HW decode headroom.
        val maxLong = if (sdkInt >= 28) 2560 else 1920
        val maxShort = if (sdkInt >= 28) 1920 else 1200
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
        val maxFps = if (sdkInt >= 28) 120 else 60
        val cleanFps = refreshRateHz.roundToInt().coerceIn(60, maxFps)
        return PeerCastRequestSpec(width = outW, height = outH, dpi = scaledDpi, fps = cleanFps)
    }

    fun computeAudioTrackBufferBytes(minBufBytes: Int, sampleRate: Int, channels: Int): Int {
        val safeMin = minBufBytes.coerceAtLeast(4096)
        val bytesPerSecond = sampleRate.coerceIn(16_000, 96_000) * channels.coerceIn(1, 2) * 2
        val target160Ms = (bytesPerSecond * 160L / 1000L).toInt()
        val frameAligned = ((target160Ms + 4095) / 4096) * 4096
        return maxOf(safeMin * 4, frameAligned, 32_768)
    }

    fun computeTargetBitrateKbps(
        width: Int,
        height: Int,
        fps: Int,
        isStandalone: Boolean,
        isUsbTether: Boolean,
    ): Int {
        val safeW = width.coerceAtLeast(320)
        val safeH = height.coerceAtLeast(320)
        val safeFps = fps.coerceIn(30, 120)
        val pixels = safeW.toLong() * safeH.toLong()
        val highFps = safeFps >= 85
        val maxCap = if (isUsbTether) MAX_USB_TETHER_BITRATE_KBPS else MAX_WIFI_BITRATE_KBPS

        val baseKbps = if (isStandalone) {
            when {
                pixels >= 2_200_000L && highFps -> 15_000
                pixels >= 2_200_000L -> 12_000
                pixels >= 1_200_000L -> 10_000
                else -> 9_000
            }
        } else {
            when {
                pixels >= 5_500_000L && highFps -> 26_000
                pixels >= 5_500_000L -> 22_000
                pixels >= 3_200_000L && highFps -> 22_000
                pixels >= 3_200_000L -> 18_000
                pixels >= 2_000_000L && highFps -> 18_000
                pixels >= 2_000_000L -> 15_000
                highFps -> 14_000
                else -> 12_000
            }
        }
        val scaled = if (isUsbTether) (baseKbps * 3) / 2 else baseKbps
        return scaled.coerceIn(MIN_BITRATE_KBPS, maxCap)
    }
}

class UplinkFrame(
    val type: Int,
    val flags: Int,
    val tsUs: Long,
    val payload: ByteArray,
)

object UplinkCoalescer {
    const val TYPE_INPUT_MOUSE = 0x23
    const val TYPE_INPUT_TOUCH = 0x24
    const val MOUSE_MOVE_ABS = 0
    const val TOUCH_MOVE = 2

    private const val MIN_OPERATING_RATE_FPS = 60
    private const val MAX_OPERATING_RATE_FPS = 165
    private const val FALLBACK_NON_LOW_LATENCY_FPS = 120

    data class DecoderConfigPlan(
        val lowLatency: Boolean,
        val priorityRealtime: Boolean,
        val operatingRate: Int,
    )

    fun buildDecoderConfigPlans(
        remoteFps: Int,
        supportsLowLatency: Boolean,
        sdkInt: Int = 30,
    ): List<DecoderConfigPlan> {
        // Android 5.0–5.1 (API 21–22) lacks KEY_PRIORITY & KEY_OPERATING_RATE: use bare format.
        if (sdkInt < 23) {
            return listOf(DecoderConfigPlan(lowLatency = false, priorityRealtime = false, operatingRate = 0))
        }
        val clampedFps = remoteFps.coerceIn(MIN_OPERATING_RATE_FPS, MAX_OPERATING_RATE_FPS)
        return if (supportsLowLatency && sdkInt >= 30) {
            listOf(
                DecoderConfigPlan(lowLatency = true, priorityRealtime = true, operatingRate = clampedFps),
                DecoderConfigPlan(lowLatency = true, priorityRealtime = true, operatingRate = 0),
                DecoderConfigPlan(lowLatency = false, priorityRealtime = true, operatingRate = 0),
                DecoderConfigPlan(lowLatency = false, priorityRealtime = false, operatingRate = 0),
            )
        } else {
            listOf(
                DecoderConfigPlan(lowLatency = false, priorityRealtime = true, operatingRate = maxOf(FALLBACK_NON_LOW_LATENCY_FPS, clampedFps)),
                DecoderConfigPlan(lowLatency = false, priorityRealtime = true, operatingRate = 0),
                DecoderConfigPlan(lowLatency = false, priorityRealtime = false, operatingRate = 0),
            )
        }
    }

    fun coalesceBatch(raw: List<UplinkFrame>, out: MutableList<UplinkFrame>) {
        for (pkt in raw) {
            if (out.isNotEmpty() && canReplacePreviousMove(out[out.lastIndex], pkt)) {
                out[out.lastIndex] = pkt
            } else {
                out.add(pkt)
            }
        }
    }

    fun canReplacePreviousMove(prev: UplinkFrame, next: UplinkFrame): Boolean {
        if (prev.type != next.type) return false
        return when (prev.type) {
            TYPE_INPUT_TOUCH -> isSameTouchMoveShape(prev.payload, next.payload)
            TYPE_INPUT_MOUSE -> isSameMouseMoveButtons(prev.payload, next.payload)
            else -> false
        }
    }

    private fun isSameTouchMoveShape(prev: ByteArray, next: ByteArray): Boolean {
        if (prev.size < 3 || next.size < 3) return false
        return (prev[0].toInt() and 0xFF) == TOUCH_MOVE &&
            (next[0].toInt() and 0xFF) == TOUCH_MOVE &&
            prev[2] == next[2]
    }

    private fun isSameMouseMoveButtons(prev: ByteArray, next: ByteArray): Boolean {
        if (prev.size < 13 || next.size < 13) return false
        if ((prev[0].toInt() and 0xFF) != MOUSE_MOVE_ABS || (next[0].toInt() and 0xFF) != MOUSE_MOVE_ABS) {
            return false
        }
        return prev[9] == next[9] && prev[10] == next[10] && prev[11] == next[11] && prev[12] == next[12]
    }
}

object FloatingGeometry {
    data class Bounds(val width: Int, val height: Int)
    data class Point(val x: Int, val y: Int)

    private const val TABLET_MIN_SCREEN_DP = 580f

    fun dpToPx(dp: Int, density: Float): Int = (dp * density.coerceAtLeast(0.5f)).roundToInt()

    fun safeCoerceIn(value: Int, minVal: Int, maxVal: Int): Int {
        val safeMax = max(1, maxVal)
        val safeMin = min(minVal, safeMax)
        return value.coerceIn(safeMin, safeMax)
    }

    fun safeCoerceRangeFloor(value: Int, minVal: Int, maxVal: Int): Int {
        val safeMax = max(minVal, maxVal)
        return value.coerceIn(minVal, safeMax)
    }

    fun computeWindowSize(screenW: Int, screenH: Int, density: Float, isLandscape: Boolean): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val minScreen = min(safeScreenW, safeScreenH)
        val minScreenDp = minScreen / safeDensity
        val maxAllowedW = max(1, (safeScreenW * 0.92f).roundToInt())
        val width: Int
        val height: Int

        if (isLandscape) {
            val targetMaxW = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(620, safeDensity), (safeScreenW * 0.72f).roundToInt())
            } else {
                min(dpToPx(580, safeDensity), (safeScreenW * 0.88f).roundToInt())
            }
            width = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                safeCoerceIn(dpToPx(620, safeDensity), dpToPx(240, safeDensity), targetMaxW)
            } else {
                safeCoerceIn((safeScreenW * 0.84f).roundToInt(), dpToPx(320, safeDensity), targetMaxW)
            }.coerceAtMost(maxAllowedW)
            val maxAllowedH = max(1, (safeScreenH * 0.64f).roundToInt())
            height = safeCoerceIn((width * 0.62f).roundToInt(), dpToPx(240, safeDensity), maxAllowedH)
        } else if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
            val maxTabletW = max(1, (safeScreenW * 0.44f).roundToInt())
            width = safeCoerceIn(dpToPx(390, safeDensity), dpToPx(230, safeDensity), maxTabletW)
                .coerceAtMost(maxAllowedW)
            val maxAllowedH = max(1, (safeScreenH * 0.82f).roundToInt())
            height = safeCoerceIn((width * 1.72f).roundToInt(), dpToPx(440, safeDensity), maxAllowedH)
        } else {
            val maxPhoneW = min(dpToPx(330, safeDensity), maxAllowedW)
            width = safeCoerceIn((minScreen * 0.56f).roundToInt(), dpToPx(230, safeDensity), maxPhoneW)
            val maxAllowedH = max(1, (safeScreenH * 0.74f).roundToInt())
            height = safeCoerceIn((width * 1.70f).roundToInt(), dpToPx(340, safeDensity), maxAllowedH)
        }
        return Bounds(width = max(1, width), height = max(1, height))
    }

    fun clampResizeSize(targetW: Int, targetH: Int, screenW: Int, screenH: Int, density: Float): Bounds {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minW = dpToPx(210, safeDensity)
        val maxW = max(1, (max(1, screenW) * 0.92f).roundToInt())
        val minH = dpToPx(260, safeDensity)
        val maxH = max(1, (max(1, screenH) * 0.86f).roundToInt())
        return Bounds(safeCoerceIn(targetW, minW, maxW), safeCoerceIn(targetH, minH, maxH))
    }

    fun clampDragPosition(targetX: Int, targetY: Int, windowW: Int, screenW: Int, screenH: Int, density: Float): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minX = -max(0, windowW) / 2
        val maxX = max(minX, max(1, screenW) - dpToPx(48, safeDensity))
        val minY = dpToPx(16, safeDensity)
        val maxY = max(minY, max(1, screenH) - dpToPx(56, safeDensity))
        return Point(targetX.coerceIn(minX, maxX), targetY.coerceIn(minY, maxY))
    }

    fun clampVisiblePosition(x: Int, y: Int, windowW: Int, windowH: Int, screenW: Int, screenH: Int, density: Float): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val padX = dpToPx(8, safeDensity)
        val padY = dpToPx(24, safeDensity)
        return Point(
            x = safeCoerceRangeFloor(x, padX, max(1, screenW) - windowW - padX),
            y = safeCoerceRangeFloor(y, padY, max(1, screenH) - windowH - padY),
        )
    }

    fun clampBubblePosition(targetX: Int, targetY: Int, screenW: Int, screenH: Int, density: Float): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minY = dpToPx(24, safeDensity)
        return Point(
            x = safeCoerceRangeFloor(targetX, 0, max(1, screenW) - dpToPx(52, safeDensity)),
            y = safeCoerceRangeFloor(targetY, minY, max(1, screenH) - dpToPx(64, safeDensity)),
        )
    }

    fun computeAspectMatchedWindowSize(
        videoW: Int,
        videoH: Int,
        chromeHeightPx: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val safeChrome = max(0, chromeHeightPx)
        val aspect = (max(2, videoW).toFloat() / max(2, videoH).toFloat()).coerceIn(0.35f, 3.0f)

        val maxW = max(1, (safeScreenW * 0.90f).roundToInt())
        val maxContentH = max(1, (safeScreenH * 0.84f).roundToInt() - safeChrome)
        val minScreenDp = min(safeScreenW, safeScreenH) / safeDensity

        val desiredContentH: Int
        val desiredContentW: Int
        if (aspect < 1f) {
            val capH = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(660, safeDensity), maxContentH)
            } else {
                min(dpToPx(540, safeDensity), maxContentH)
            }
            desiredContentH = max(1, capH)
            desiredContentW = max(1, (desiredContentH * aspect).roundToInt())
        } else {
            val capW = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(680, safeDensity), maxW)
            } else {
                min(dpToPx(560, safeDensity), maxW)
            }
            desiredContentW = max(1, capW)
            desiredContentH = max(1, (desiredContentW / aspect).roundToInt())
        }
        return fitAspectIntoBounds(desiredContentW, desiredContentH, aspect, safeChrome, maxW, maxContentH)
    }

    fun clampAspectLockedResizeSize(
        targetW: Int,
        targetH: Int,
        videoW: Int,
        videoH: Int,
        chromeHeightPx: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val safeChrome = max(0, chromeHeightPx)
        val aspect = (max(2, videoW).toFloat() / max(2, videoH).toFloat()).coerceIn(0.35f, 3.0f)

        val maxW = max(1, (safeScreenW * 0.92f).roundToInt())
        val maxContentH = max(1, (safeScreenH * 0.86f).roundToInt() - safeChrome)
        val minW = min(dpToPx(200, safeDensity), maxW)
        val minContentH = min(dpToPx(200, safeDensity), maxContentH)

        val desiredW = max(minW, targetW)
        val desiredContentH = max(minContentH, targetH - safeChrome)
        val fromW = fitAspectIntoBounds(desiredW, max(1, (desiredW / aspect).roundToInt()), aspect, safeChrome, maxW, maxContentH)
        val fromH = fitAspectIntoBounds(max(1, (desiredContentH * aspect).roundToInt()), desiredContentH, aspect, safeChrome, maxW, maxContentH)

        val errW = abs(fromW.width - targetW) + abs(fromW.height - targetH)
        val errH = abs(fromH.width - targetW) + abs(fromH.height - targetH)
        val chosen = if (errW <= errH) fromW else fromH
        val clampedW = max(minW, min(maxW, chosen.width))
        val lockedContentH = max(1, (clampedW / aspect).roundToInt()).coerceAtMost(maxContentH)
        val lockedW = max(1, (lockedContentH * aspect).roundToInt()).coerceAtMost(maxW)
        return Bounds(width = lockedW, height = lockedContentH + safeChrome)
    }

    private fun fitAspectIntoBounds(
        rawW: Int,
        rawContentH: Int,
        aspect: Float,
        chromeHeightPx: Int,
        maxW: Int,
        maxContentH: Int,
    ): Bounds {
        var w = max(1, rawW)
        var h = max(1, rawContentH)
        if (w > maxW) {
            w = maxW
            h = max(1, (w / aspect).roundToInt())
        }
        if (h > maxContentH) {
            h = maxContentH
            w = max(1, (h * aspect).roundToInt())
        }
        if (w > maxW) {
            w = maxW
            h = max(1, (w / aspect).roundToInt())
        }
        return Bounds(width = max(1, w), height = max(1, h) + chromeHeightPx)
    }
}

object UsbTetherPolicy {
    const val DEFAULT_CAST_PORT = ReceiverPolicy.DEFAULT_CAST_PORT
    const val BETA_DISCLAIMER = "Beta 测试功能：受各系统 USB 网卡驱动与机型策略差异影响，不保证所有设备均可正常使用。"

    data class UsbTetherCandidate(
        val interfaceName: String,
        val gatewayIp: String,
        val localIp: String = "",
    )

    data class CastHealthInfo(
        val deviceName: String,
        val certShortFp: String,
        val protocolVersion: Int,
    )

    data class DiscoveredUsbTetherPeer(
        val displayName: String,
        val host: String,
        val port: Int,
        val interfaceName: String,
        val shortFingerprint: String = "",
        val fullFingerprint: String = "",
    )

    private val STR_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val BOOL_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*(true|false)""")
    private val INT_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*(-?\d+)""")

    fun isLikelyUsbTetherRoute(interfaceName: String?, gatewayIp: String?): Boolean {
        val gw = gatewayIp?.trim().orEmpty()
        if (!isPrivateIpv4(gw)) return false
        val iface = interfaceName?.trim()?.lowercase(Locale.US).orEmpty()
        if (iface.isEmpty()) return true
        if (iface.startsWith("wlan") || iface.startsWith("swlan") || iface.startsWith("ap") ||
            iface.startsWith("rmnet") || iface.startsWith("ccmni") ||
            iface.startsWith("tun") || iface.startsWith("tap") || iface.startsWith("ppp") ||
            iface.startsWith("lo") || iface.startsWith("dummy")
        ) {
            return false
        }
        return iface.startsWith("eth") || iface.startsWith("usb") ||
            iface.startsWith("ncm") || iface.startsWith("rndis") || iface.startsWith("en")
    }

    fun isPrivateIpv4(raw: String?): Boolean {
        val parts = raw?.trim()?.split('.') ?: return false
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return false
            if (v !in 0..255) return false
            octets[i] = v
        }
        if (octets[0] == 10) return true
        if (octets[0] == 172 && octets[1] in 16..31) return true
        if (octets[0] == 192 && octets[1] == 168) return true
        return false
    }

    fun parseCastHealthJson(rawJson: String?): CastHealthInfo? {
        val body = rawJson?.trim().orEmpty()
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        val bools = HashMap<String, Boolean>()
        BOOL_FIELD_REGEX.findAll(body).forEach { m -> bools[m.groupValues[1]] = (m.groupValues[2] == "true") }
        if (bools["ok"] != true) return null

        val strs = HashMap<String, String>()
        STR_FIELD_REGEX.findAll(body).forEach { m -> strs[m.groupValues[1]] = unescapeJson(m.groupValues[2]) }
        if (strs["role"] != "cast") return null

        val ints = HashMap<String, Int>()
        INT_FIELD_REGEX.findAll(body).forEach { m -> m.groupValues[2].toIntOrNull()?.let { ints[m.groupValues[1]] = it } }
        val devName = strs["deviceName"].orEmpty().trim().ifEmpty { "Android 设备" }
        val shortFp = strs["certShortFp"].orEmpty().trim()
        val protoVer = ints["protocolVersion"] ?: 1
        return CastHealthInfo(devName, shortFp, protoVer)
    }

    fun formatShortFingerprint(hex: String?): String {
        val clean = hex.orEmpty().replace(":", "").trim().uppercase(Locale.US)
        if (clean.length < 12) return clean
        return "${clean.substring(0, 4)}-${clean.substring(4, 8)}-${clean.substring(8, 12)}"
    }

    private fun unescapeJson(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '"', '\\', '/' -> sb.append(next)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}

object CameraGeometry {
    data class RotatedNv21(val data: ByteArray, val width: Int, val height: Int)

    fun normalizeFacing(facing: String?): String = when (facing?.trim()?.lowercase(Locale.US)) {
        "back", "rear", "environment" -> "back"
        else -> "front"
    }

    fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        1 -> 90
        2 -> 180
        3 -> 270
        else -> 0
    }

    fun computeUprightRotationCw(sensorOrientation: Int, isFront: Boolean, displayRotationDeg: Int): Int {
        val sensor = ((sensorOrientation % 360) + 360) % 360
        val disp = ((displayRotationDeg % 360) + 360) % 360
        return if (isFront) (sensor + disp) % 360 else (sensor - disp + 360) % 360
    }

    fun rotateNv21(src: ByteArray, width: Int, height: Int, cwDeg: Int): RotatedNv21 {
        val norm = ((cwDeg % 360) + 360) % 360
        if (norm == 0 || width <= 0 || height <= 0 || (width and 1) != 0 || (height and 1) != 0) {
            return RotatedNv21(src, width, height)
        }
        val ySize = width * height
        val expectedLen = ySize + (ySize / 2)
        if (src.size < expectedLen) return RotatedNv21(src, width, height)

        val dst = ByteArray(expectedLen)
        val uvW = width / 2
        val uvH = height / 2
        return when (norm) {
            90 -> {
                val dstW = height
                for (y in 0 until height) {
                    val rowBase = y * width
                    val dstX = height - 1 - y
                    for (x in 0 until width) dst[x * dstW + dstX] = src[rowBase + x]
                }
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    val dstUx = uvH - 1 - uy
                    for (ux in 0 until uvW) {
                        val sIdx = srcRow + ux * 2
                        val dIdx = ySize + (ux * uvH + dstUx) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, dstW, width)
            }
            180 -> {
                for (y in 0 until height) {
                    val srcRow = y * width
                    val dstRow = (height - 1 - y) * width
                    for (x in 0 until width) dst[dstRow + (width - 1 - x)] = src[srcRow + x]
                }
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    val dstRow = ySize + (uvH - 1 - uy) * width
                    for (ux in 0 until uvW) {
                        val sIdx = srcRow + ux * 2
                        val dIdx = dstRow + (uvW - 1 - ux) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, width, height)
            }
            270 -> {
                val dstW = height
                for (y in 0 until height) {
                    val rowBase = y * width
                    for (x in 0 until width) dst[(width - 1 - x) * dstW + y] = src[rowBase + x]
                }
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    for (ux in 0 until uvW) {
                        val sIdx = srcRow + ux * 2
                        val dIdx = ySize + ((uvW - 1 - ux) * uvH + uy) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, dstW, width)
            }
            else -> RotatedNv21(src, width, height)
        }
    }
}

object InputGuardPolicy {
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val ACTION_MOVE = 2
    private const val ACTION_CANCEL = 3
    private const val BUTTON_BACK = 8
    private const val BUTTON_FORWARD = 16
    private const val SIDE_NAV_MASK = BUTTON_BACK or BUTTON_FORWARD

    private const val KEYCODE_BACK = 4
    private const val KEYCODE_HOME = 3
    private const val KEYCODE_VOLUME_UP = 24
    private const val KEYCODE_VOLUME_DOWN = 25
    private const val KEYCODE_POWER = 26
    private const val KEYCODE_VOLUME_MUTE = 164
    private const val KEYCODE_APP_SWITCH = 187
    private const val KEYCODE_SLEEP = 223
    private const val KEYCODE_WAKEUP = 224

    fun isForwardableKey(keyCode: Int): Boolean = when (keyCode) {
        KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN, KEYCODE_VOLUME_MUTE,
        KEYCODE_POWER, KEYCODE_SLEEP, KEYCODE_WAKEUP,
        KEYCODE_HOME, KEYCODE_APP_SWITCH -> false
        else -> true
    }

    /** Suppresses synthetic left-click touch events emitted by Android alongside mouse side-button presses. */
    class SideNavigationTouchFilter {
        private var suppressing = false

        fun shouldSuppress(actionMasked: Int, isMouse: Boolean, buttonState: Int): Boolean {
            if (!isMouse) return false
            val hasSideNav = (buttonState and SIDE_NAV_MASK) != 0
            if (actionMasked == ACTION_DOWN && hasSideNav) {
                suppressing = true
                return true
            }
            if (suppressing) {
                if (actionMasked == ACTION_UP || actionMasked == ACTION_CANCEL) {
                    suppressing = false
                }
                return true
            }
            return hasSideNav && actionMasked == ACTION_MOVE
        }
    }

    /** Deduplicates mouse side-back `ACTION_BUTTON_PRESS(BUTTON_BACK)` vs synthetic `KEYCODE_BACK`. */
    class SideBackKeyDeduplicator(private val dedupWindowMs: Long = 160L) {
        private var lastClaimMs = -1L
        private var lastKeyForwardMs = -1L
        private var suppressUp = false

        @Synchronized
        fun tryClaimSideButtonBack(uptimeMs: Long): Boolean {
            if (lastKeyForwardMs >= 0L && uptimeMs - lastKeyForwardMs in 0L..dedupWindowMs) {
                return false
            }
            lastClaimMs = uptimeMs
            suppressUp = true
            return true
        }

        @Synchronized
        fun recordBackKeyForwarded(uptimeMs: Long) {
            lastKeyForwardMs = uptimeMs
        }

        @Synchronized
        fun shouldSuppressDuplicateBackKey(keyCode: Int, action: Int, uptimeMs: Long): Boolean {
            if (keyCode != KEYCODE_BACK) return false
            if (action == ACTION_DOWN) {
                val dup = lastClaimMs >= 0L && uptimeMs - lastClaimMs in 0L..dedupWindowMs
                suppressUp = dup
                return dup
            }
            if (action == ACTION_UP && suppressUp) {
                suppressUp = false
                return true
            }
            return false
        }
    }
}
