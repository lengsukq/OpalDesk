package io.github.lengsukq.opaldesk.cast

/**
 * Pure-JVM frame record queued on the Android peer uplink sender channel.
 */
internal class OpalPeerUplinkFrame(
    val type: Int,
    val flags: Int,
    val tsUs: Long,
    val payload: ByteArray,
)

/**
 * Pure-JVM uplink pointer/touch move coalescer and hardware video decoder operating-rate policy
 * for [OpalCastPeerClient].
 */
internal object OpalPeerUplinkCoalescer {
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

    /**
     * Picks MediaCodec decoder `KEY_OPERATING_RATE` candidates without pinning SoC VDEC/DDR clocks
     * to `Short.MAX_VALUE` (32767) on Android 11+ devices that already expose `FEATURE_LowLatency`.
     */
    fun resolveDecoderOperatingRates(remoteFps: Int, supportsLowLatency: Boolean): IntArray {
        val clampedFps = remoteFps.coerceIn(MIN_OPERATING_RATE_FPS, MAX_OPERATING_RATE_FPS)
        return if (supportsLowLatency) {
            intArrayOf(clampedFps, 0)
        } else {
            intArrayOf(maxOf(FALLBACK_NON_LOW_LATENCY_FPS, clampedFps), 0)
        }
    }

    /**
     * Builds an ordered list of decoder configuration fallback plans from lowest-latency down to a
     * bare AOSP minimal format (omitting `KEY_LOW_LATENCY`, `KEY_PRIORITY`, and `KEY_OPERATING_RATE`).
     * Prevents mid-tier SoC decoders (e.g. Lenovo TB331FC HEVC) from failing permanently with
     * `CodecException` when `KEY_LOW_LATENCY=1` or high operating rates are unsupported.
     */
    fun buildDecoderConfigPlans(remoteFps: Int, supportsLowLatency: Boolean): List<DecoderConfigPlan> {
        val clampedFps = remoteFps.coerceIn(MIN_OPERATING_RATE_FPS, MAX_OPERATING_RATE_FPS)
        return if (supportsLowLatency) {
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

    /**
     * Collapses adjacent pure pointer-move frames in [raw] into [out] while strictly preserving
     * ordering and every discrete event (DOWN, UP, POINTER_DOWN/UP, button changes, scroll, key, control).
     */
    fun coalesceBatch(raw: List<OpalPeerUplinkFrame>, out: MutableList<OpalPeerUplinkFrame>) {
        for (pkt in raw) {
            if (out.isNotEmpty() && canReplacePreviousMove(out[out.lastIndex], pkt)) {
                out[out.lastIndex] = pkt
            } else {
                out.add(pkt)
            }
        }
    }

    fun canReplacePreviousMove(prev: OpalPeerUplinkFrame, next: OpalPeerUplinkFrame): Boolean {
        if (prev.type != next.type) return false
        return when (prev.type) {
            TYPE_INPUT_TOUCH -> isSameTouchMoveShape(prev.payload, next.payload)
            TYPE_INPUT_MOUSE -> isSameMouseMoveButtons(prev.payload, next.payload)
            else -> false
        }
    }

    private fun isSameTouchMoveShape(prev: ByteArray, next: ByteArray): Boolean {
        if (prev.size < 3 || next.size < 3) return false
        val prevAction = prev[0].toInt() and 0xFF
        val nextAction = next[0].toInt() and 0xFF
        return prevAction == TOUCH_MOVE && nextAction == TOUCH_MOVE && prev[2] == next[2]
    }

    private fun isSameMouseMoveButtons(prev: ByteArray, next: ByteArray): Boolean {
        if (prev.size < 13 || next.size < 13) return false
        val prevAction = prev[0].toInt() and 0xFF
        val nextAction = next[0].toInt() and 0xFF
        if (prevAction != MOUSE_MOVE_ABS || nextAction != MOUSE_MOVE_ABS) return false
        // Mouse buttons bitmask lives at payload offset 9..12 (Big-Endian int32).
        return prev[9] == next[9] && prev[10] == next[10] && prev[11] == next[11] && prev[12] == next[12]
    }
}
