package io.github.lengsukq.opaldesk.compositor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowRect

/**
 * Consolidated Compose state holder for transient desktop workspace overlays and menus.
 * Keeping these mutable states in a single @Stable holder avoids 80-parameter lambda captures
 * (which can exceed the 255-register DEX threshold under R8 full-mode optimization).
 */
@Stable
internal class WorkspaceUiState(initialSpotlightPulse: Long) {
    var allApps by mutableStateOf<List<InstalledAppEntry>>(emptyList())
    var lastSpotlightTogglePulse by mutableLongStateOf(initialSpotlightPulse)
    var showSpotlight by mutableStateOf(false)
    var showAppDrawer by mutableStateOf(false)
    var showExposeOverview by mutableStateOf(false)
    var showControlCenter by mutableStateOf(false)
    var controlCenterInitialTab by mutableIntStateOf(0)
    var showDpiPopover by mutableStateOf(false)
    var showResolutionModal by mutableStateOf(false)
    var showImePopover by mutableStateOf(false)
    var showShortcutsPopover by mutableStateOf(false)
    var showFeatureGuide by mutableStateOf(false)
    var showNotificationCenter by mutableStateOf(false)
    var showExitSlotModal by mutableStateOf(false)
    var savingOnExit by mutableStateOf(false)
    var editingAppDpiTarget by mutableStateOf<Pair<String, String>?>(null)
    var autoRestoredOnce by mutableStateOf(false)
    var contextMenuTarget by mutableStateOf<Triple<String, CompositorWindow?, Offset>?>(null)
    var guideIconMenuPos by mutableStateOf<Offset?>(null)
    var wallpaperMenuPos by mutableStateOf<Offset?>(null)
    var snapPreviewRect by mutableStateOf<WindowRect?>(null)
    val dockLastTapMsByPkg = mutableMapOf<String, Long>()

    fun localOverlayNeedsFocus(hasPendingPairing: Boolean): Boolean =
        showSpotlight ||
            showAppDrawer ||
            showControlCenter ||
            showDpiPopover ||
            showResolutionModal ||
            showImePopover ||
            showShortcutsPopover ||
            showFeatureGuide ||
            showNotificationCenter ||
            showExitSlotModal ||
            editingAppDpiTarget != null ||
            hasPendingPairing

    fun dismissAllPopovers() {
        showSpotlight = false
        showAppDrawer = false
        showExposeOverview = false
        showControlCenter = false
        showDpiPopover = false
        showResolutionModal = false
        showImePopover = false
        showShortcutsPopover = false
        showFeatureGuide = false
        showNotificationCenter = false
        contextMenuTarget = null
        guideIconMenuPos = null
        wallpaperMenuPos = null
    }
}
