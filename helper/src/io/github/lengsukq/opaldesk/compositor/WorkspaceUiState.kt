package io.github.lengsukq.opaldesk.compositor

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.settings.DexSettingsCategory

/**
 * Consolidated Compose state holder for transient desktop workspace overlays and menus.
 * Keeping these mutable states in a single @Stable holder avoids 80-parameter lambda captures
 * (which can exceed the 255-register DEX threshold under R8 full-mode optimization).
 */
@Stable
class WorkspaceUiState(initialSpotlightPulse: Long) {
    var allApps by mutableStateOf<List<InstalledAppEntry>>(emptyList())
    var lastSpotlightTogglePulse by mutableLongStateOf(initialSpotlightPulse)
    var showSpotlight by mutableStateOf(false)
    var showAppDrawer by mutableStateOf(false)
    var showExposeOverview by mutableStateOf(false)
    var showControlCenter by mutableStateOf(false)
    var controlCenterInitialTab by mutableIntStateOf(0)
    var showDexSettings by mutableStateOf(false)
    var dexSettingsInitialCategory by mutableStateOf(DexSettingsCategory.APPEARANCE)
    var showDpiPopover by mutableStateOf(false)
    var showResolutionModal by mutableStateOf(false)
    var showImePopover by mutableStateOf(false)
    var showShortcutsPopover by mutableStateOf(false)
    var showFeatureGuide by mutableStateOf(false)
    var showFaqModal by mutableStateOf(false)
    var showNotificationCenter by mutableStateOf(false)
    var showWidgetStore by mutableStateOf(false)
    var isWidgetEditMode by mutableStateOf(false)
    var showExitSlotModal by mutableStateOf(false)
    var savingOnExit by mutableStateOf(false)
    var editingAppDpiTarget by mutableStateOf<Pair<String, String>?>(null)
    var autoRestoredOnce by mutableStateOf(false)
    var contextMenuTarget by mutableStateOf<Triple<String, CompositorWindow?, Offset>?>(null)
    var guideIconMenuPos by mutableStateOf<Offset?>(null)
    var wallpaperMenuPos by mutableStateOf<Offset?>(null)
    var snapPreviewRect by mutableStateOf<WindowRect?>(null)
    var orbDockCollapsePulse by mutableLongStateOf(0L)
    val dockLastTapMsByPkg = mutableMapOf<String, Long>()

    fun openDexSettings(category: DexSettingsCategory = DexSettingsCategory.APPEARANCE) {
        dismissAllPopovers()
        dexSettingsInitialCategory = category
        showDexSettings = true
    }

    fun localOverlayNeedsFocus(hasPendingPairing: Boolean): Boolean =
        showSpotlight ||
            showAppDrawer ||
            showControlCenter ||
            showDexSettings ||
            showDpiPopover ||
            showResolutionModal ||
            showImePopover ||
            showShortcutsPopover ||
            showFeatureGuide ||
            showFaqModal ||
            showNotificationCenter ||
            showWidgetStore ||
            showExitSlotModal ||
            editingAppDpiTarget != null ||
            hasPendingPairing

    fun hasAnyOpenOverlay(): Boolean =
        showSpotlight ||
            showAppDrawer ||
            showExposeOverview ||
            showControlCenter ||
            showDexSettings ||
            showDpiPopover ||
            showResolutionModal ||
            showImePopover ||
            showShortcutsPopover ||
            showFeatureGuide ||
            showFaqModal ||
            showNotificationCenter ||
            showWidgetStore ||
            isWidgetEditMode ||
            showExitSlotModal ||
            editingAppDpiTarget != null ||
            contextMenuTarget != null ||
            guideIconMenuPos != null ||
            wallpaperMenuPos != null

    fun dismissAnyOpenOverlay(): Boolean {
        if (!hasAnyOpenOverlay()) return false
        dismissAllPopovers()
        isWidgetEditMode = false
        showExitSlotModal = false
        editingAppDpiTarget = null
        return true
    }

    fun dismissAllPopovers() {
        showSpotlight = false
        showAppDrawer = false
        showExposeOverview = false
        showControlCenter = false
        showDexSettings = false
        showDpiPopover = false
        showResolutionModal = false
        showImePopover = false
        showShortcutsPopover = false
        showFeatureGuide = false
        showFaqModal = false
        showNotificationCenter = false
        showWidgetStore = false
        contextMenuTarget = null
        guideIconMenuPos = null
        wallpaperMenuPos = null
        orbDockCollapsePulse = SystemClock.uptimeMillis()
    }
}
