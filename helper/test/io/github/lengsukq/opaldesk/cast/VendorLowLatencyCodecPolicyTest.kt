package io.github.lengsukq.opaldesk.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorLowLatencyCodecPolicyTest {
    @Test
    fun detectVendor_recognizesAllSupportedSoCFamilies() {
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.QUALCOMM,
            VendorLowLatencyCodecPolicy.detectVendor("c2.qti.hevc.decoder"),
        )
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.QUALCOMM,
            VendorLowLatencyCodecPolicy.detectVendor("OMX.qcom.video.decoder.avc"),
        )
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.MEDIATEK,
            VendorLowLatencyCodecPolicy.detectVendor("c2.mtk.hevc.decoder"),
        )
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.MEDIATEK,
            VendorLowLatencyCodecPolicy.detectVendor("OMX.MTK.VIDEO.DECODER.HEVC"),
        )
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.HISILICON,
            VendorLowLatencyCodecPolicy.detectVendor("OMX.hisi.video.decoder.hevc"),
        )
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.EXYNOS,
            VendorLowLatencyCodecPolicy.detectVendor("c2.exynos.hevc.decoder"),
        )
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.AMLOGIC,
            VendorLowLatencyCodecPolicy.detectVendor("OMX.amlogic.hevc.decoder.awesome"),
        )
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.GENERIC,
            VendorLowLatencyCodecPolicy.detectVendor("c2.android.hevc.decoder"),
        )
        assertEquals(
            VendorLowLatencyCodecPolicy.CodecVendor.GENERIC,
            VendorLowLatencyCodecPolicy.detectVendor(null),
        )
    }

    @Test
    fun qualcommPlans_injectQtiKeysAndMaxOperatingRateThenFallBackToBare() {
        val plans = VendorLowLatencyCodecPolicy.buildFallbackPlans(
            codecName = "c2.qti.hevc.decoder",
            remoteFps = 120,
            supportsLowLatency = true,
            sdkInt = 34,
        )
        assertTrue(plans.size >= 5)
        val first = plans.first()
        assertEquals("vendor_full", first.tierName)
        assertEquals(1, first.intKeys[VendorLowLatencyCodecPolicy.KEY_LOW_LATENCY])
        assertEquals(1, first.intKeys[VendorLowLatencyCodecPolicy.KEY_QTI_LOW_LATENCY])
        assertEquals(1, first.intKeys[VendorLowLatencyCodecPolicy.KEY_QTI_PICTURE_ORDER])
        assertEquals(Short.MAX_VALUE.toInt(), first.intKeys[VendorLowLatencyCodecPolicy.KEY_OPERATING_RATE])

        val second = plans[1]
        assertEquals("vendor_safe", second.tierName)
        assertEquals(1, second.intKeys[VendorLowLatencyCodecPolicy.KEY_QTI_LOW_LATENCY])
        assertFalse(second.intKeys.containsKey(VendorLowLatencyCodecPolicy.KEY_OPERATING_RATE))

        val last = plans.last()
        assertEquals("bare", last.tierName)
        assertTrue(last.intKeys.isEmpty())
    }

    @Test
    fun mediatekKirinExynosAmlogic_emitExpectedPrivateKeys() {
        val cases = listOf(
            "c2.mtk.hevc.decoder" to VendorLowLatencyCodecPolicy.KEY_MTK_LOW_LATENCY,
            "c2.hisi.hevc.decoder" to VendorLowLatencyCodecPolicy.KEY_HISI_LOW_LATENCY,
            "omx.exynos.hevc.dec" to VendorLowLatencyCodecPolicy.KEY_EXYNOS_LOW_LATENCY,
            "c2.amlogic.hevc.decoder" to VendorLowLatencyCodecPolicy.KEY_AMLOGIC_LOW_LATENCY,
        )
        for ((codecName, expectedKey) in cases) {
            val plans = VendorLowLatencyCodecPolicy.buildFallbackPlans(
                codecName = codecName,
                remoteFps = 90,
                supportsLowLatency = true,
                sdkInt = 33,
            )
            assertEquals("vendor_full", plans.first().tierName)
            assertEquals(1, plans.first().intKeys[expectedKey])
            assertEquals(1, plans.first().intKeys[VendorLowLatencyCodecPolicy.KEY_LOW_LATENCY])
            assertEquals(90, plans.first().intKeys[VendorLowLatencyCodecPolicy.KEY_OPERATING_RATE])
            assertEquals("bare", plans.last().tierName)
            assertTrue(plans.last().intKeys.isEmpty())
        }
    }

    @Test
    fun genericCodecAndLegacyApi22_stepDownSafelyToBare() {
        val genericPlans = VendorLowLatencyCodecPolicy.buildFallbackPlans(
            codecName = null,
            remoteFps = 60,
            supportsLowLatency = false,
            sdkInt = 31,
        )
        assertEquals("priority_operating_rate", genericPlans.first().tierName)
        assertEquals("bare", genericPlans.last().tierName)

        val legacyQcom = VendorLowLatencyCodecPolicy.buildFallbackPlans(
            codecName = "OMX.qcom.video.decoder.avc",
            remoteFps = 60,
            supportsLowLatency = false,
            sdkInt = 22,
        )
        assertFalse(legacyQcom.first().intKeys.containsKey(VendorLowLatencyCodecPolicy.KEY_OPERATING_RATE))
        assertFalse(legacyQcom.first().intKeys.containsKey(VendorLowLatencyCodecPolicy.KEY_LOW_LATENCY))
        assertEquals(1, legacyQcom.first().intKeys[VendorLowLatencyCodecPolicy.KEY_QTI_LOW_LATENCY])
        assertEquals("bare", legacyQcom.last().tierName)
    }
}
