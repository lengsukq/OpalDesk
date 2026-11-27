package io.github.lengsukq.opalcast

import io.github.lengsukq.opalcast.policy.CameraGeometry
import io.github.lengsukq.opalcast.policy.FloatingGeometry
import io.github.lengsukq.opalcast.policy.InputGuardPolicy
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.policy.UplinkCoalescer
import io.github.lengsukq.opalcast.policy.UplinkFrame
import io.github.lengsukq.opalcast.policy.UsbTetherPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverPolicyTest {

    @Test
    fun projectMetadataUrlsPointToOfficialShowcaseAndGithubRepo() {
        assertEquals("https://lengsukq.github.io/OpalDesk/", ReceiverPolicy.PROJECT_INTRO_URL)
        assertEquals("https://github.com/lengsukq/OpalDesk", ReceiverPolicy.PROJECT_GITHUB_URL)
        assertEquals("1042317917", ReceiverPolicy.COMMUNITY_QQ_GROUP_NUMBER)
        assertEquals(
            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1042317917&card_type=group&source=qrcode",
            ReceiverPolicy.buildQqGroupDeepLink(),
        )
    }

    @Test
    fun primaryAbiLabelDetects32BitAnd64BitCleanly() {
        assertTrue(ReceiverPolicy.is64BitAbiList(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(ReceiverPolicy.is64BitAbiList(arrayOf("armeabi-v7a", "armeabi")))
        assertFalse(ReceiverPolicy.is64BitAbiList(arrayOf("x86")))
        assertEquals("armeabi-v7a · 32位", ReceiverPolicy.primaryAbiLabel(arrayOf("armeabi-v7a")))
        assertEquals("arm64-v8a · 64位", ReceiverPolicy.primaryAbiLabel(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("x86 · 32位", ReceiverPolicy.primaryAbiLabel(arrayOf("x86")))
        assertEquals("未知架构", ReceiverPolicy.primaryAbiLabel(emptyArray()))
    }

    @Test
    fun codecNegotiationFallsBackToH264OnLegacyDeviceWithoutHwHevc() {
        val legacyCodecs = listOf(
            ReceiverPolicy.CodecDescriptor("OMX.qcom.video.decoder.avc", isEncoder = false, supportedTypes = listOf("video/avc")),
            ReceiverPolicy.CodecDescriptor("OMX.google.hevc.decoder", isEncoder = false, supportedTypes = listOf("video/hevc")),
        )
        val summary = ReceiverPolicy.summarizeHardwareDecoders(legacyCodecs)
        assertTrue(summary.hasHardwareAvc)
        assertFalse(summary.hasHardwareHevc)
        assertEquals("h264", ReceiverPolicy.resolvePreferredCastCodec(ReceiverPolicy.CODEC_PREF_AUTO, summary.hasHardwareHevc))
        assertEquals("hevc", ReceiverPolicy.resolvePreferredCastCodec(ReceiverPolicy.CODEC_PREF_HEVC, summary.hasHardwareHevc))
        assertEquals("h264", ReceiverPolicy.resolvePreferredCastCodec(ReceiverPolicy.CODEC_PREF_H264, hasHardwareHevcDecoder = true))
    }

    @Test
    fun selectPreferredDecoderPrioritizesHwLowLatencyOverSoftware() {
        val codecs = listOf(
            ReceiverPolicy.CodecDescriptor("c2.android.hevc.decoder", false, listOf("video/hevc"), isSoftwareOnlyFlag = true),
            ReceiverPolicy.CodecDescriptor("c2.qti.hevc.decoder", false, listOf("video/hevc"), isSoftwareOnlyFlag = false, supportsLowLatency = false),
            ReceiverPolicy.CodecDescriptor("c2.qti.hevc.decoder.low_latency", false, listOf("video/hevc"), isSoftwareOnlyFlag = false, supportsLowLatency = true),
        )
        val choice = ReceiverPolicy.selectPreferredDecoder(codecs, "video/hevc")
        assertEquals("c2.qti.hevc.decoder.low_latency", choice.hwName)
        assertTrue(choice.supportsLowLatency)
        assertEquals("c2.android.hevc.decoder", choice.swName)
    }

    @Test
    fun decoderPlansAdaptForApi21ThroughApi37() {
        val api21 = UplinkCoalescer.buildDecoderConfigPlans(60, supportsLowLatency = false, sdkInt = 21)
        assertEquals(1, api21.size)
        assertFalse(api21[0].priorityRealtime)
        assertFalse(api21[0].lowLatency)
        assertEquals(0, api21[0].operatingRate)

        val api26 = UplinkCoalescer.buildDecoderConfigPlans(60, supportsLowLatency = true, sdkInt = 26)
        assertTrue(api26.none { it.lowLatency })
        assertTrue(api26.first().priorityRealtime)

        val api34 = UplinkCoalescer.buildDecoderConfigPlans(120, supportsLowLatency = true, sdkInt = 34)
        assertTrue(api34.first().lowLatency)
        assertEquals(120, api34.first().operatingRate)
        assertFalse(api34.last().priorityRealtime)
    }

    @Test
    fun tlsFilterAndOverlayWindowTypeMatchAndroidVersions() {
        assertArrayEquals(
            arrayOf("TLSv1.3", "TLSv1.2"),
            ReceiverPolicy.filterSecureTlsProtocols(arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")),
        )
        assertArrayEquals(
            arrayOf("TLSv1.2"),
            ReceiverPolicy.filterSecureTlsProtocols(arrayOf("SSLv3", "TLSv1", "TLSv1.2")),
        )
        assertEquals(2002, ReceiverPolicy.overlayWindowType(21))
        assertEquals(2002, ReceiverPolicy.overlayWindowType(25))
        assertEquals(2038, ReceiverPolicy.overlayWindowType(26))
        assertEquals(2038, ReceiverPolicy.overlayWindowType(37))
    }

    @Test
    fun parseHostPortHandlesIpv4CustomPortIpv6AndInvalidInput() {
        val v4 = ReceiverPolicy.parseHostPort("192.168.31.105")
        assertNotNull(v4)
        assertEquals("192.168.31.105", v4!!.host)
        assertEquals(19878, v4.port)

        val custom = ReceiverPolicy.parseHostPort("10.0.0.5:20000")
        assertEquals("10.0.0.5", custom?.host)
        assertEquals(20000, custom?.port)

        val v6 = ReceiverPolicy.parseHostPort("[fe80::1]:19879")
        assertEquals("fe80::1", v6?.host)
        assertEquals(19879, v6?.port)

        assertNull(ReceiverPolicy.parseHostPort(""))
        assertNull(ReceiverPolicy.parseHostPort("192.168.1.1:99999"))
        assertNull(ReceiverPolicy.parseHostPort("bad host"))
    }

    @Test
    fun peerCastRequestSpecCapsLegacyDevicesAndPreservesAspectRatio() {
        val legacySpec = ReceiverPolicy.computePeerCastRequestSpec(2560, 1600, 320, 90f, sdkInt = 25)
        assertTrue(legacySpec.width <= 1920)
        assertTrue(legacySpec.height <= 1200)
        assertEquals(60, legacySpec.fps)
        assertEquals(0, legacySpec.width % 2)
        assertEquals(0, legacySpec.height % 2)

        val modernSpec = ReceiverPolicy.computePeerCastRequestSpec(3200, 2136, 400, 120f, sdkInt = 37)
        assertTrue(modernSpec.width <= 2560)
        assertEquals(120, modernSpec.fps)
    }

    @Test
    fun uplinkCoalescerCollapsesAdjacentMovesWithoutDroppingDiscreteEvents() {
        val down = UplinkFrame(UplinkCoalescer.TYPE_INPUT_TOUCH, 0, 1L, byteArrayOf(0, 0, 1))
        val move1 = UplinkFrame(UplinkCoalescer.TYPE_INPUT_TOUCH, 0, 2L, byteArrayOf(2, 0, 1))
        val move2 = UplinkFrame(UplinkCoalescer.TYPE_INPUT_TOUCH, 0, 3L, byteArrayOf(2, 0, 1))
        val up = UplinkFrame(UplinkCoalescer.TYPE_INPUT_TOUCH, 0, 4L, byteArrayOf(1, 0, 1))
        val out = ArrayList<UplinkFrame>()
        UplinkCoalescer.coalesceBatch(listOf(down, move1, move2, up), out)
        assertEquals(3, out.size)
        assertEquals(3L, out[1].tsUs)
    }

    @Test
    fun floatingGeometryAndUsbTetherHealthWorkSafely() {
        val mirrorBounds = FloatingGeometry.computeAspectMatchedWindowSize(1080, 2400, 160, 1920, 1200, 2f)
        assertTrue(mirrorBounds.width > 0 && mirrorBounds.width < 1920)
        assertTrue(mirrorBounds.height > 160 && mirrorBounds.height <= 1200)

        assertTrue(UsbTetherPolicy.isLikelyUsbTetherRoute("usb0", "192.168.42.129"))
        assertFalse(UsbTetherPolicy.isLikelyUsbTetherRoute("wlan0", "192.168.1.1"))
        val health = UsbTetherPolicy.parseCastHealthJson("""{"ok":true,"role":"cast","deviceName":"MiPhone","certShortFp":"abcd1234ef56","protocolVersion":1}""")
        assertNotNull(health)
        assertEquals("MiPhone", health!!.deviceName)
        assertEquals("ABCD-1234-EF56", UsbTetherPolicy.formatShortFingerprint(health.certShortFp))
    }

    @Test
    fun cameraRotationAndSideBackDedupBehaveDeterministically() {
        assertEquals(90, CameraGeometry.computeUprightRotationCw(270, isFront = true, displayRotationDeg = 180))
        assertEquals(0, CameraGeometry.computeUprightRotationCw(90, isFront = false, displayRotationDeg = 90))
        val dedup = InputGuardPolicy.SideBackKeyDeduplicator(160L)
        assertTrue(dedup.tryClaimSideButtonBack(1000L))
        assertTrue(dedup.shouldSuppressDuplicateBackKey(4, 0, 1020L))
        assertTrue(dedup.shouldSuppressDuplicateBackKey(4, 1, 1040L))
        assertFalse(dedup.shouldSuppressDuplicateBackKey(4, 0, 1300L))
    }
}
