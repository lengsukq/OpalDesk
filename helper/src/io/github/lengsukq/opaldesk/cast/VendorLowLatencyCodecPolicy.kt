package io.github.lengsukq.opaldesk.cast

import java.util.Locale

/**
 * Moonlight-grade (`MediaCodecHelper.java`) SoC vendor low-latency MediaCodec configuration
 * policy with stepped fallback down to a bare AOSP `MediaFormat`.
 *
 * Pure Kotlin with zero Android runtime dependencies so all vendor key mappings and fallback
 * tiers are deterministically verified by JVM unit tests.
 */
object VendorLowLatencyCodecPolicy {
    const val KEY_LOW_LATENCY = "low-latency"
    const val KEY_PRIORITY = "priority"
    const val KEY_OPERATING_RATE = "operating-rate"

    const val KEY_QTI_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
    const val KEY_QTI_PICTURE_ORDER = "vendor.qti-ext-dec-picture-order.enable"
    const val KEY_MTK_LOW_LATENCY = "vdec-lowlatency"
    const val KEY_HISI_LOW_LATENCY = "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req"
    const val KEY_EXYNOS_LOW_LATENCY = "vendor.rtc-ext-dec-low-latency.enable"
    const val KEY_AMLOGIC_LOW_LATENCY = "vendor.low-latency.enable"

    const val QCOM_MAX_OPERATING_RATE = Short.MAX_VALUE.toInt()

    enum class CodecVendor {
        QUALCOMM,
        MEDIATEK,
        HISILICON,
        EXYNOS,
        AMLOGIC,
        GENERIC,
    }

    data class FormatOptionPlan(
        val tierName: String,
        val vendor: CodecVendor,
        val intKeys: Map<String, Int>,
    )

    fun detectVendor(codecName: String?): CodecVendor {
        val lower = codecName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            lower.startsWith("omx.qcom") || lower.startsWith("c2.qti") -> CodecVendor.QUALCOMM
            lower.startsWith("omx.mtk") || lower.startsWith("c2.mtk") -> CodecVendor.MEDIATEK
            lower.startsWith("omx.hisi") || lower.startsWith("c2.hisi") -> CodecVendor.HISILICON
            lower.startsWith("omx.exynos") || lower.startsWith("c2.exynos") -> CodecVendor.EXYNOS
            lower.startsWith("omx.amlogic") || lower.startsWith("c2.amlogic") -> CodecVendor.AMLOGIC
            else -> CodecVendor.GENERIC
        }
    }

    fun vendorLowLatencyKeys(vendor: CodecVendor, sdkInt: Int = 30): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        when (vendor) {
            CodecVendor.QUALCOMM -> {
                out[KEY_QTI_LOW_LATENCY] = 1
                out[KEY_QTI_PICTURE_ORDER] = 1
                if (sdkInt >= 23) {
                    out[KEY_OPERATING_RATE] = QCOM_MAX_OPERATING_RATE
                }
            }
            CodecVendor.MEDIATEK -> out[KEY_MTK_LOW_LATENCY] = 1
            CodecVendor.HISILICON -> out[KEY_HISI_LOW_LATENCY] = 1
            CodecVendor.EXYNOS -> out[KEY_EXYNOS_LOW_LATENCY] = 1
            CodecVendor.AMLOGIC -> out[KEY_AMLOGIC_LOW_LATENCY] = 1
            CodecVendor.GENERIC -> Unit
        }
        return out
    }

    /**
     * Builds an ordered, deduplicated fallback ladder from full vendor + Android 11+ low-latency
     * tuning down to a bare AOSP format (empty map) so `MediaCodec.configure` never crashes
     * permanently on strict OEM HALs.
     */
    fun buildFallbackPlans(
        codecName: String?,
        remoteFps: Int,
        supportsLowLatency: Boolean,
        sdkInt: Int = 30,
    ): List<FormatOptionPlan> {
        val vendor = detectVendor(codecName)
        val clampedFps = remoteFps.coerceIn(60, 165)
        val vendorKeys = vendorLowLatencyKeys(vendor, sdkInt)
        val plans = ArrayList<FormatOptionPlan>(7)
        val seen = HashSet<Map<String, Int>>()

        fun addPlan(tierName: String, keys: Map<String, Int>) {
            if (seen.add(keys)) {
                plans.add(FormatOptionPlan(tierName = tierName, vendor = vendor, intKeys = keys))
            }
        }

        if (vendorKeys.isNotEmpty()) {
            val full = LinkedHashMap<String, Int>()
            if (sdkInt >= 23) full[KEY_PRIORITY] = 0
            if (sdkInt >= 30) full[KEY_LOW_LATENCY] = 1
            full.putAll(vendorKeys)
            if (sdkInt >= 23 && !full.containsKey(KEY_OPERATING_RATE)) {
                full[KEY_OPERATING_RATE] = clampedFps
            }
            addPlan("vendor_full", full)

            val vendorSafe = LinkedHashMap<String, Int>()
            if (sdkInt >= 23) vendorSafe[KEY_PRIORITY] = 0
            for ((k, v) in vendorKeys) {
                if (k != KEY_OPERATING_RATE) vendorSafe[k] = v
            }
            if (vendorSafe.isNotEmpty()) {
                addPlan("vendor_safe", vendorSafe)
            }
        }

        if (sdkInt >= 30 && (supportsLowLatency || vendor != CodecVendor.GENERIC)) {
            addPlan(
                "standard_low_latency_fps",
                linkedMapOf(KEY_PRIORITY to 0, KEY_LOW_LATENCY to 1, KEY_OPERATING_RATE to clampedFps),
            )
            addPlan("standard_low_latency", linkedMapOf(KEY_PRIORITY to 0, KEY_LOW_LATENCY to 1))
        }

        if (sdkInt >= 23) {
            addPlan("priority_operating_rate", linkedMapOf(KEY_PRIORITY to 0, KEY_OPERATING_RATE to maxOf(120, clampedFps)))
            addPlan("priority_only", mapOf(KEY_PRIORITY to 0))
        }

        addPlan("bare", emptyMap())
        return plans
    }
}
