package io.github.lengsukq.opaldesk.settings

import io.github.lengsukq.opaldesk.prefs.DockCustomizationConfig
import io.github.lengsukq.opaldesk.prefs.DockGlassStyle
import io.github.lengsukq.opaldesk.prefs.DockSizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexSettingsModelsTest {

    @Test
    fun computeDexSettingsLayoutModeMatchesThreeTierBreakpoints() {
        assertEquals(DexSettingsLayoutMode.COMPACT_PHONE, computeDexSettingsLayoutMode(0f))
        assertEquals(DexSettingsLayoutMode.COMPACT_PHONE, computeDexSettingsLayoutMode(360f))
        assertEquals(DexSettingsLayoutMode.COMPACT_PHONE, computeDexSettingsLayoutMode(519.9f))

        assertEquals(DexSettingsLayoutMode.MEDIUM_TABLET, computeDexSettingsLayoutMode(520f))
        assertEquals(DexSettingsLayoutMode.MEDIUM_TABLET, computeDexSettingsLayoutMode(600f))
        assertEquals(DexSettingsLayoutMode.MEDIUM_TABLET, computeDexSettingsLayoutMode(719.9f))

        assertEquals(DexSettingsLayoutMode.EXPANDED_PC, computeDexSettingsLayoutMode(720f))
        assertEquals(DexSettingsLayoutMode.EXPANDED_PC, computeDexSettingsLayoutMode(1024f))
        assertEquals(DexSettingsLayoutMode.EXPANDED_PC, computeDexSettingsLayoutMode(1440f))
    }

    @Test
    fun filterDexSettingsCategoriesSupportsChineseAndEnglishKeywords() {
        assertEquals(DexSettingsCategory.entries, filterDexSettingsCategories(""))
        assertEquals(DexSettingsCategory.entries, filterDexSettingsCategories("   "))

        assertEquals(listOf(DexSettingsCategory.DOCK), filterDexSettingsCategories("小白点"))
        assertEquals(listOf(DexSettingsCategory.DOCK), filterDexSettingsCategories("悬浮球"))
        assertEquals(listOf(DexSettingsCategory.DISPLAY_CAST), filterDexSettingsCategories("分辨率"))
        assertEquals(listOf(DexSettingsCategory.INPUT_IME), filterDexSettingsCategories("语燕"))
        assertEquals(listOf(DexSettingsCategory.AUDIO), filterDexSettingsCategories("扬声器"))
        assertEquals(listOf(DexSettingsCategory.APPEARANCE), filterDexSettingsCategories("壁纸"))
        assertEquals(listOf(DexSettingsCategory.WINDOWS), filterDexSettingsCategories("虚拟屏"))
        assertEquals(listOf(DexSettingsCategory.SYSTEM_DIAG), filterDexSettingsCategories("守护进程"))

        assertEquals(listOf(DexSettingsCategory.DOCK), filterDexSettingsCategories("orb"))
        assertEquals(listOf(DexSettingsCategory.DOCK), filterDexSettingsCategories("  DOCK  "))
        assertEquals(listOf(DexSettingsCategory.SYSTEM_DIAG), filterDexSettingsCategories("Shizuku"))
        assertEquals(listOf(DexSettingsCategory.APPEARANCE), filterDexSettingsCategories("Wallpaper"))
        assertEquals(listOf(DexSettingsCategory.AUDIO), filterDexSettingsCategories("speaker"))
        assertEquals(listOf(DexSettingsCategory.WINDOWS), filterDexSettingsCategories("multitask"))

        val trackpadMatches = filterDexSettingsCategories("trackpad")
        assertTrue(trackpadMatches.contains(DexSettingsCategory.DISPLAY_CAST))
        assertTrue(trackpadMatches.contains(DexSettingsCategory.INPUT_IME))

        assertTrue(filterDexSettingsCategories("nonexistent-query-xyz").isEmpty())
    }

    @Test
    fun catalogProvidesMetadataForEveryCategory() {
        assertEquals(DexSettingsCategory.entries.toSet(), DexSettingsCatalog.allMeta.map { it.category }.toSet())
        for (category in DexSettingsCategory.entries) {
            assertTrue(category.displayTitle(isZh = true).isNotBlank())
            assertTrue(category.displayTitle(isZh = false).isNotBlank())
            assertTrue(category.displaySubtitle(isZh = true).isNotBlank())
            assertTrue(category.displaySubtitle(isZh = false).isNotBlank())
        }
    }

    @Test
    fun dockCustomizationConfigDefaultsAreStable() {
        val defaults = DockCustomizationConfig()
        assertEquals(DockSizePreset.STANDARD, defaults.sizePreset)
        assertTrue(defaults.floatingStyle)
        assertFalse(defaults.autoHide)
        assertTrue(defaults.showSearchButton)
        assertTrue(defaults.showSettingsButton)
        assertTrue(defaults.showRunningApps)
        assertTrue(defaults.showRunningDots)
        assertEquals(DockGlassStyle.BALANCED, defaults.glassStyle)
        assertFalse(defaults.orbMode)
        assertTrue(defaults.orbSnapToEdge)
        assertTrue(defaults.orbHoverExpand)
        assertTrue(defaults.orbIdleDim)
        assertTrue(defaults.orbDockedRight)
        assertEquals(0.72f, defaults.orbVerticalFraction, 0.0001f)
        assertEquals(0.86f, defaults.orbOpacity, 0.0001f)
    }

    @Test
    fun dockCustomizationConfigClampsOrbOpacityBoundsAndNonFiniteValues() {
        assertEquals(0.25f, DockCustomizationConfig(orbOpacity = -0.5f).clampedOrbOpacity(), 0.0001f)
        assertEquals(0.25f, DockCustomizationConfig(orbOpacity = 0.0f).clampedOrbOpacity(), 0.0001f)
        assertEquals(0.25f, DockCustomizationConfig(orbOpacity = 0.24f).clampedOrbOpacity(), 0.0001f)
        assertEquals(0.25f, DockCustomizationConfig(orbOpacity = 0.25f).clampedOrbOpacity(), 0.0001f)
        assertEquals(0.60f, DockCustomizationConfig(orbOpacity = 0.60f).clampedOrbOpacity(), 0.0001f)
        assertEquals(0.86f, DockCustomizationConfig(orbOpacity = 0.86f).clampedOrbOpacity(), 0.0001f)
        assertEquals(1.0f, DockCustomizationConfig(orbOpacity = 1.0f).clampedOrbOpacity(), 0.0001f)
        assertEquals(1.0f, DockCustomizationConfig(orbOpacity = 1.5f).clampedOrbOpacity(), 0.0001f)

        assertEquals(0.86f, DockCustomizationConfig(orbOpacity = Float.NaN).clampedOrbOpacity(), 0.0001f)
        assertEquals(0.86f, DockCustomizationConfig(orbOpacity = Float.POSITIVE_INFINITY).clampedOrbOpacity(), 0.0001f)
        assertEquals(0.86f, DockCustomizationConfig(orbOpacity = Float.NEGATIVE_INFINITY).clampedOrbOpacity(), 0.0001f)
    }

    @Test
    fun dockSizePresetAndGlassStyleEnumsMapMonotonically() {
        assertEquals(
            listOf(DockSizePreset.COMPACT, DockSizePreset.STANDARD, DockSizePreset.LARGE),
            DockSizePreset.entries,
        )
        val compactScale = DockCustomizationConfig(sizePreset = DockSizePreset.COMPACT).toDockScale()
        val standardScale = DockCustomizationConfig(sizePreset = DockSizePreset.STANDARD).toDockScale()
        val largeScale = DockCustomizationConfig(sizePreset = DockSizePreset.LARGE).toDockScale()
        assertEquals(0.84f, compactScale, 0.0001f)
        assertEquals(1.00f, standardScale, 0.0001f)
        assertEquals(1.20f, largeScale, 0.0001f)
        assertTrue(compactScale < standardScale && standardScale < largeScale)

        assertEquals(
            listOf(DockGlassStyle.CLEAR, DockGlassStyle.BALANCED, DockGlassStyle.FROSTED),
            DockGlassStyle.entries,
        )
        val clearOpacity = DockCustomizationConfig(glassStyle = DockGlassStyle.CLEAR).toDockOpacity()
        val balancedOpacity = DockCustomizationConfig(glassStyle = DockGlassStyle.BALANCED).toDockOpacity()
        val frostedOpacity = DockCustomizationConfig(glassStyle = DockGlassStyle.FROSTED).toDockOpacity()
        assertEquals(0.35f, clearOpacity, 0.0001f)
        assertEquals(0.65f, balancedOpacity, 0.0001f)
        assertEquals(0.92f, frostedOpacity, 0.0001f)
        assertTrue(clearOpacity < balancedOpacity && balancedOpacity < frostedOpacity)
    }
}
