package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.external.CustomResolutionPolicy
import io.github.lengsukq.opaldesk.external.CustomResolutionSpec
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CustomResolutionPolicyTest {

    @Test
    fun parseUserInputValidatesAlignsAndReportsHelpfulErrors() {
        val uw = CustomResolutionPolicy.parseUserInput("3440", "1440", "60", "带鱼屏")
        val uwSpec = uw.spec
        assertNotNull(uwSpec)
        assertEquals(3440, uwSpec!!.width)
        assertEquals(1440, uwSpec.height)
        assertEquals(60, uwSpec.fps)
        assertEquals("", uw.adjustedNote)

        // Odd numbers auto-align to even pixels
        val odd = CustomResolutionPolicy.parseUserInput("2561", "1081", "")
        val oddSpec = odd.spec
        assertNotNull(oddSpec)
        assertEquals(2560, oddSpec!!.width)
        assertEquals(1080, oddSpec.height)
        assertEquals(0, oddSpec.fps)
        assertTrue(odd.adjustedNote.contains("2560×1080"))

        // Portrait external resolution allowed
        val portrait = CustomResolutionPolicy.parseUserInput("1080", "1920", "120")
        val portraitSpec = portrait.spec
        assertNotNull(portraitSpec)
        assertEquals(1080, portraitSpec!!.width)
        assertEquals(1920, portraitSpec.height)
        assertEquals(120, portraitSpec.fps)

        // Error cases
        assertNull(CustomResolutionPolicy.parseUserInput("", "1080", "60").spec)
        assertNull(CustomResolutionPolicy.parseUserInput("abc", "1080", "60").spec)
        assertNull(CustomResolutionPolicy.parseUserInput("320", "240", "60").spec)
        assertNull(CustomResolutionPolicy.parseUserInput("7680", "4320", "60").spec)
        assertNull(CustomResolutionPolicy.parseUserInput("1920", "1080", "240").spec)
        assertNull(CustomResolutionPolicy.parseUserInput("1920", "1080", "10").spec)
    }

    @Test
    fun aspectRatioLabelsRecognizeCommonMonitorsAndVerticalScreens() {
        assertEquals("16:9", CustomResolutionPolicy.formatAspectRatioLabel(1920, 1080))
        assertEquals("16:10", CustomResolutionPolicy.formatAspectRatioLabel(2560, 1600))
        assertEquals("21:9", CustomResolutionPolicy.formatAspectRatioLabel(3440, 1440))
        assertEquals("21:9", CustomResolutionPolicy.formatAspectRatioLabel(2560, 1080))
        assertEquals("4:3", CustomResolutionPolicy.formatAspectRatioLabel(1024, 768))
        assertEquals("3:2", CustomResolutionPolicy.formatAspectRatioLabel(3000, 2000))
        assertEquals("9:16 竖屏", CustomResolutionPolicy.formatAspectRatioLabel(1080, 1920))
    }

    @Test
    fun presetSerializationRoundTripsDeduplicatesSanitizesAndCaps() {
        var list = emptyList<CustomResolutionSpec>()
        list = CustomResolutionPolicy.upsertPreset(list, CustomResolutionSpec(3440, 1440, 60, "带鱼|屏;\n"))
        list = CustomResolutionPolicy.upsertPreset(list, CustomResolutionSpec(1080, 1920, 0, "竖屏副屏"))
        // Duplicate key moves to front with updated label
        list = CustomResolutionPolicy.upsertPreset(list, CustomResolutionSpec(3440, 1440, 60, "主带鱼屏"))
        assertEquals(2, list.size)
        assertEquals("主带鱼屏", list.first().label)

        val raw = CustomResolutionPolicy.serializePresets(list)
        val parsed = CustomResolutionPolicy.parsePresets(raw)
        assertEquals(list, parsed)

        val removed = CustomResolutionPolicy.removePreset(parsed, "3440x1440@60")
        assertEquals(1, removed.size)
        assertEquals("1080x1920@0", removed.first().key)
    }

    @Test
    fun peerCastRequestSpecPreservesTabletAspectRatioAndRoundsRefreshRate() {
        // 3:2 tablet (3000x2000) must scale proportionally to ~2560x1706 (NOT squashed to 2560x1600)
        val tab = CustomResolutionPolicy.computePeerCastRequestSpec(3000, 2000, 320, 119.88f)
        assertEquals(2560, tab.width)
        assertTrue("Expected ~1706 height for 3:2 tablet, got ${tab.height}", abs(tab.height - 1706) <= 4)
        assertEquals(0, tab.width and 1)
        assertEquals(0, tab.height and 1)
        assertEquals(120, tab.fps)

        // Portrait phone (1080x2400) preserved natively with fractional 59.94Hz -> 60Hz
        val phone = CustomResolutionPolicy.computePeerCastRequestSpec(1080, 2400, 440, 59.94f)
        assertEquals(1080, phone.width)
        assertEquals(2400, phone.height)
        assertEquals(60, phone.fps)
    }

    @Test
    fun curateExternalDisplayModesKeepsPortraitRoundsFractionalHzAndNeverCrowdsOut1080p() {
        // Simulate a 4K monitor with dense 24/25/30/50/59.94/60/119.88/144Hz EDID modes
        val denseEdid = listOf(
            Triple(3840, 2160, 60f),
            Triple(3840, 2160, 59.94f),
            Triple(3840, 2160, 50f),
            Triple(3840, 2160, 30f),
            Triple(3840, 2160, 29.97f),
            Triple(3840, 2160, 25f),
            Triple(3840, 2160, 24f),
            Triple(2560, 1440, 119.88f),
            Triple(2560, 1440, 100f),
            Triple(2560, 1440, 60f),
            Triple(2560, 1440, 59.94f),
            Triple(1920, 1080, 144f),
            Triple(1920, 1080, 120f),
            Triple(1920, 1080, 60f),
            Triple(1920, 1080, 59.94f),
            Triple(1920, 1080, 50f),
            Triple(1920, 1080, 30f),
        )
        val curated = ExternalDisplayPolicy.curateExternalDisplayModes(denseEdid)
        // Must include 4K, 2K@120, 2K@60, 1080p@144, 1080p@60 — and NO 59Hz/119Hz truncation!
        assertTrue(curated.any { it.width == 3840 && it.height == 2160 && it.refreshRateHz == 60 })
        assertTrue(curated.any { it.width == 2560 && it.height == 1440 && it.refreshRateHz == 120 })
        assertTrue(curated.any { it.width == 2560 && it.height == 1440 && it.refreshRateHz == 60 })
        assertTrue(curated.any { it.width == 1920 && it.height == 1080 && it.refreshRateHz == 144 })
        assertTrue(curated.any { it.width == 1920 && it.height == 1080 && it.refreshRateHz == 60 })
        assertFalse(curated.any { it.refreshRateHz == 59 || it.refreshRateHz == 119 })

        // Portrait external monitor modes must be recognized!
        val portraitEdid = listOf(Triple(1080, 1920, 59.94f), Triple(720, 1280, 60f))
        val curatedPortrait = ExternalDisplayPolicy.curateExternalDisplayModes(portraitEdid)
        assertEquals(2, curatedPortrait.size)
        assertEquals(1080, curatedPortrait.first().width)
        assertEquals(1920, curatedPortrait.first().height)
        assertEquals(60, curatedPortrait.first().refreshRateHz)

        // Tiny portable 800x480 HDMI screen falls back instead of returning empty list
        val tinyEdid = listOf(Triple(800, 480, 60f))
        val curatedTiny = ExternalDisplayPolicy.curateExternalDisplayModes(tinyEdid)
        assertEquals(1, curatedTiny.size)
        assertEquals(800, curatedTiny.first().width)
        assertEquals(480, curatedTiny.first().height)
    }

    @Test
    fun effectiveDisplaySizeAndCustomOverrideDetectionWorkCorrectly() {
        // Forced custom size (2560x1080) takes precedence over physical EDID (1920x1080)
        assertEquals(
            2560 to 1080,
            ExternalDisplayPolicy.resolveEffectiveDisplaySize(1920, 1080, 2560, 1080),
        )
        assertTrue(ExternalDisplayPolicy.isCustomForcedOverride(2560, 1080, 1920, 1080))
        // Rotated native display is NOT a forced override
        assertFalse(ExternalDisplayPolicy.isCustomForcedOverride(1080, 1920, 1920, 1080))
        // Native matching size is NOT a forced override
        assertFalse(ExternalDisplayPolicy.isCustomForcedOverride(1920, 1080, 1920, 1080))
        // Fallback to mode size when real size unavailable
        assertEquals(
            2560 to 1440,
            ExternalDisplayPolicy.resolveEffectiveDisplaySize(2560, 1440, 0, 0),
        )
    }
}
