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

    @Test
    fun curateExternalModesWithNativeDetectsAnisotropyAndRestoresNative2048x1536() {
        // Exact OPALDESK-1A portable 4:3 2048x1536 monitor EDID list on Android 17 (61:35 EDID size)
        val rawModes = listOf(
            ExternalDisplayPolicy.RawExternalMode(2677, 1536, 59.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(2091, 1200, 60.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(1920, 1080, 60.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(1600, 1200, 60.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(1506, 864, 60.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(1339, 768, 60.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(1280, 1024, 60.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(1280, 720, 60.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(1024, 768, 60.0f, isSynthetic = false),
            ExternalDisplayPolicy.RawExternalMode(1046, 600, 60.0f, isSynthetic = false),
        )
        val res = ExternalDisplayPolicy.curateExternalDisplayModesWithNative(
            rawModes = rawModes,
            nativeW = 2048,
            nativeH = 1536,
            nativeRefreshRate = 59.0f,
            xDpi = 85.27f,
            yDpi = 111.47f,
        )
        assertTrue(res.hasAnisotropyDistortion)
        // Native 2048x1536 must be surfaced first and tagged isNativePhysical=true
        val first = res.modes.first()
        assertEquals(2048, first.width)
        assertEquals(1536, first.height)
        assertEquals(59, first.refreshRateHz)
        assertTrue(first.isNativePhysical)
        assertFalse(first.isAnisotropySynthetic)

        // All five 61:35 stretched resolutions are flagged as synthetic anisotropy modes
        for ((w, h) in listOf(2677 to 1536, 2091 to 1200, 1506 to 864, 1339 to 768, 1046 to 600)) {
            val item = res.modes.firstOrNull { it.width == w && it.height == h }
            assertNotNull("Expected $w×$h in curated list", item)
            assertTrue("Expected $w×$h flagged as anisotropy synthetic", item!!.isAnisotropySynthetic)
        }
        // Standard hardware modes stay non-synthetic
        for ((w, h) in listOf(1920 to 1080, 1600 to 1200, 1280 to 1024, 1280 to 720, 1024 to 768)) {
            val item = res.modes.firstOrNull { it.width == w && it.height == h }
            assertNotNull("Expected $w×$h in curated list", item)
            assertFalse("Standard mode $w×$h must not be flagged synthetic", item!!.isAnisotropySynthetic)
        }

        // Standard 16:9 2560x1440 monitor has zero anisotropy distortion
        val normalRes = ExternalDisplayPolicy.curateExternalDisplayModesWithNative(
            rawModes = listOf(
                ExternalDisplayPolicy.RawExternalMode(2560, 1440, 144f),
                ExternalDisplayPolicy.RawExternalMode(1920, 1080, 60f),
            ),
            nativeW = 2560,
            nativeH = 1440,
            nativeRefreshRate = 144f,
            xDpi = 108.8f,
            yDpi = 108.0f,
        )
        assertFalse(normalRes.hasAnisotropyDistortion)
        assertTrue(normalRes.modes.none { it.isAnisotropySynthetic })
    }

    @Test
    fun hyperOsMirrorCollapseIgnoredByEffectiveSizeAndTriggersAutoNormalizeSafely() {
        // HyperOS mirror collapse: external 2048x1536 reports phone portrait 1200x2608 via getRealSize()
        assertTrue(
            ExternalDisplayPolicy.isMirroredPrimaryDisplayCollapse(
                nativeW = 2048,
                nativeH = 1536,
                logicalW = 1200,
                logicalH = 2608,
                primaryW = 1200,
                primaryH = 2608,
            ),
        )
        assertEquals(
            2048 to 1536,
            ExternalDisplayPolicy.resolveEffectiveDisplaySize(
                modeW = 2048,
                modeH = 1536,
                realW = 1200,
                realH = 2608,
                primaryW = 1200,
                primaryH = 2608,
            ),
        )

        // Auto-normalize fires on mirror collapse or native/anisotropy-inflated size
        assertTrue(
            ExternalDisplayPolicy.shouldAutoNormalizeExternalOnEnter(
                nativeW = 2048,
                nativeH = 1536,
                realW = 1200,
                realH = 2608,
                primaryW = 1200,
                primaryH = 2608,
                hasAnisotropy = false,
                userNative1To1Enabled = false,
                autoCustomResolutionEnabled = false,
            ),
        )
        assertTrue(
            ExternalDisplayPolicy.shouldAutoNormalizeExternalOnEnter(
                nativeW = 2048,
                nativeH = 1536,
                realW = 2048,
                realH = 1536,
                primaryW = 1200,
                primaryH = 2608,
                hasAnisotropy = true,
                userNative1To1Enabled = false,
                autoCustomResolutionEnabled = false,
            ),
        )
        assertTrue(
            ExternalDisplayPolicy.shouldAutoNormalizeExternalOnEnter(
                nativeW = 2048,
                nativeH = 1536,
                realW = 2677,
                realH = 1536,
                primaryW = 1200,
                primaryH = 2608,
                hasAnisotropy = true,
                userNative1To1Enabled = false,
                autoCustomResolutionEnabled = false,
            ),
        )
        // Must NOT overwrite user's intentional custom 1920x1080 override
        assertFalse(
            ExternalDisplayPolicy.shouldAutoNormalizeExternalOnEnter(
                nativeW = 2048,
                nativeH = 1536,
                realW = 1920,
                realH = 1080,
                primaryW = 1200,
                primaryH = 2608,
                hasAnisotropy = true,
                userNative1To1Enabled = false,
                autoCustomResolutionEnabled = false,
            ),
        )
        // Must NOT override when user enabled custom auto-apply resolution
        assertFalse(
            ExternalDisplayPolicy.shouldAutoNormalizeExternalOnEnter(
                nativeW = 2048,
                nativeH = 1536,
                realW = 2048,
                realH = 1536,
                primaryW = 1200,
                primaryH = 2608,
                hasAnisotropy = true,
                userNative1To1Enabled = true,
                autoCustomResolutionEnabled = true,
            ),
        )
    }
}
