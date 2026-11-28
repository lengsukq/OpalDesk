package io.github.lengsukq.opalcast.policy

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure JVM-testable policies for OpalCast (standalone universal 32/64-bit Android 5.0–17
 * Cast Receiver). Zero Android framework runtime dependencies.
 */
object ReceiverPolicy {
    const val DEFAULT_CAST_PORT = 19878
    const val MODALITY_UNKNOWN = 0
    const val MODALITY_TOUCH = 1
    const val MODALITY_MOUSE = 2
    const val MODALITY_KEYBOARD = 3
    const val TOUCH_TAP_FRESH_WINDOW_MS = 5_000L

    /**
     * Universal receiver soft-keyboard policy matching OpalDesk host:
     * - No physical keyboard connected -> always show soft keyboard.
     * - Physical/Bluetooth keyboard connected -> show only after a fresh touchscreen tap.
     */
    fun shouldAutoShowReceiverSoftKeyboard(
        hasPhysicalKeyboard: Boolean,
        lastModality: Int,
        msSinceLastTouch: Long,
    ): Boolean {
        if (!hasPhysicalKeyboard) return true
        return lastModality == MODALITY_TOUCH && msSinceLastTouch in 0L..TOUCH_TAP_FRESH_WINDOW_MS
    }

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

    const val PROFILE_CLONE = "clone"
    const val PROFILE_WORK = "work"
    const val PROFILE_PRIVATE = "private"
    const val PROFILE_GENERIC = "profile"

    fun formatAppKey(pkg: String, userId: Int = 0): String {
        val clean = pkg.trim()
        return if (userId > 0) "$clean#u$userId" else clean
    }

    fun normalizeProfileType(raw: String?, userId: Int): String {
        if (userId <= 0) return ""
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            PROFILE_CLONE -> PROFILE_CLONE
            PROFILE_WORK -> PROFILE_WORK
            PROFILE_PRIVATE -> PROFILE_PRIVATE
            PROFILE_GENERIC -> PROFILE_GENERIC
            else -> if (userId == 999 || userId == 95 || userId == 128) PROFILE_CLONE else PROFILE_GENERIC
        }
    }

    fun profileSearchKeywords(userId: Int, profileType: String = ""): List<String> {
        if (userId <= 0) return emptyList()
        val out = ArrayList<String>(10)
        out.add("u$userId")
        out.add("user $userId")
        out.add("分身")
        out.add("多开")
        out.add("双开")
        out.add("clone")
        out.add("dual")
        when (normalizeProfileType(profileType, userId)) {
            PROFILE_WORK -> {
                out.add("工作")
                out.add("work")
                out.add("企业")
            }
            PROFILE_PRIVATE -> {
                out.add("隐私")
                out.add("私密")
                out.add("private")
            }
        }
        return out
    }

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

    data class PrimaryAbiInfo(
        val abi: String,
        val is64Bit: Boolean,
    )

    fun primaryAbiInfo(supportedAbis: Array<String>): PrimaryAbiInfo? {
        val primary = supportedAbis.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (primary.isEmpty()) return null
        return PrimaryAbiInfo(abi = primary, is64Bit = primary.contains("64"))
    }

    fun primaryAbiLabel(supportedAbis: Array<String>): String {
        val info = primaryAbiInfo(supportedAbis) ?: return "未知架构"
        val bits = if (info.is64Bit) "64位" else "32位"
        return "${info.abi} · $bits"
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
        val safeMin = ((minBufBytes.coerceAtLeast(4096) + 4095) / 4096) * 4096
        val bytesPerSecond = sampleRate.coerceIn(16_000, 96_000) * channels.coerceIn(1, 2) * 2
        val target64Ms = (bytesPerSecond * 64L / 1000L).toInt()
        val frameAligned = ((target64Ms + 4095) / 4096) * 4096
        return maxOf(safeMin, frameAligned, 12_288)
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
