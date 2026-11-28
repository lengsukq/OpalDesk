package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.CustomResolutionSpec
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileStoreState
import io.github.lengsukq.opaldesk.protocol.AppProfileKey
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.DexImeRuntimeState
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.search.ConsoleSearchCatalog
import io.github.lengsukq.opaldesk.search.ConsoleSpotlightState
import io.github.lengsukq.opaldesk.search.DexCommandCatalog
import io.github.lengsukq.opaldesk.search.DexSpotlightState
import io.github.lengsukq.opaldesk.search.SpotlightCategory
import io.github.lengsukq.opaldesk.search.SpotlightItem
import io.github.lengsukq.opaldesk.ui.DexImeCatalog
import io.github.lengsukq.opaldesk.ui.WorkstationShortcutCatalog
import io.github.lengsukq.opaldesk.ui.formatCompactResolutionBadge
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure state-aggregation helpers constructing the unified DeX Spotlight index
 * (open windows + installed apps across user profiles + DeX commands + Console deep-links).
 */
internal fun buildWorkspaceConsoleSpotlightState(
    ctx: Context,
    status: DaemonStatus,
    scene: CompositorScene,
    remote: RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    profileState: ProfileStoreState,
    imeState: DexImeRuntimeState,
    themeMode: Int,
    externalUiScale: Float,
    autoCustomResEnabled: Boolean,
    defaultCustomRes: CustomResolutionSpec,
    trackpadSens: Float,
    dexImeDisplayMode: Int,
    dexLockedImeId: String,
    autoBridgeImeOnCast: Boolean,
    globalShortcutsEnabled: Boolean,
    disabledShortcutIds: Set<String>,
    notifAccessGranted: Boolean,
    audioRouteMode: Int,
    usbTetherEnabled: Boolean,
    trustedPeerCount: Int,
): ConsoleSpotlightState {
    val working = profileState.workingConfig
    val activeSlot = profileState.activeSavedSlot
    val activeShortcuts = WorkstationShortcutCatalog.countActiveGlobalShortcuts(
        globalShortcutsEnabled,
        disabledShortcutIds,
    )
    val yuyanActive = imeState.pluginImeActive || DexImeCatalog.isYuyanPluginEnabledForDex(
        pluginInstalled = imeState.pluginImeInstalled,
        lockedImeId = dexLockedImeId,
        autoBridgeOnCast = autoBridgeImeOnCast,
        displayMode = dexImeDisplayMode,
    )
    val backendShort = when (status.inputBackendMode) {
        BridgeProtocol.InputBackend.SYSTEM -> ctx.getString(R.string.settings_backend_system)
        BridgeProtocol.InputBackend.UHID -> "UHID"
        else -> ctx.getString(R.string.common_auto)
    }
    val effectiveQuota = status.maxVdCount.takeIf { it > 0 } ?: working.maxVdCount
    return ConsoleSpotlightState(
        connected = status.connected,
        rootEnhanced = status.rootEnhanced,
        windowCount = scene.windows.size,
        remoteConnected = remote.connected,
        lanShareActive = remote.isLanShareActive,
        lanEndpoint = if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:${if (remote.wifiPort > 0) remote.wifiPort else 19878}" else "",
        extDisplaySummary = extDisplay?.let { "${it.name} · ${it.width}×${it.height} @ ${it.refreshRateHz}Hz" }.orEmpty(),
        themeModeLabel = when (themeMode) {
            AppPrefs.THEME_DARK -> ctx.getString(R.string.common_dark)
            AppPrefs.THEME_SYSTEM -> ctx.getString(R.string.common_follow_system)
            else -> ctx.getString(R.string.common_light)
        },
        targetFpsLabel = if (status.targetFps > 0) "${status.targetFps}Hz" else ctx.getString(R.string.common_auto),
        uiScaleLabel = "${(externalUiScale * 100f).roundToInt()}%",
        resolutionSummary = when {
            extDisplay != null -> formatCompactResolutionBadge(extDisplay.width, extDisplay.height, extDisplay.refreshRateHz)
            remote.connected -> formatCompactResolutionBadge(remote.width, remote.height, (remote.fpsX100 / 100f).roundToInt().coerceAtLeast(24))
            autoCustomResEnabled -> formatCompactResolutionBadge(defaultCustomRes.width, defaultCustomRes.height, defaultCustomRes.fps)
            else -> ctx.getString(R.string.display_res_adaptive_edid)
        },
        perAppDpiSummary = when {
            working.appCustomDpis.isNotEmpty() -> ctx.getString(R.string.display_dpi_custom_count, working.appCustomDpis.size)
            working.globalCustomDpi > 0 -> ctx.getString(R.string.display_dpi_global_val, working.globalCustomDpi)
            else -> ctx.getString(R.string.display_dpi_auto)
        },
        peripheralsSummary = String.format(Locale.US, "%.2f×", trackpadSens),
        imeSummary = when {
            yuyanActive -> ctx.getString(R.string.display_ime_yuyan_badge)
            dexLockedImeId.isNotBlank() -> DexImeCatalog.fallbackLabelForImeId(dexLockedImeId).substringBefore(" (").take(10)
            else -> DexImeCatalog.displayModeTitle(dexImeDisplayMode)
        },
        shortcutsSummary = if (!globalShortcutsEnabled || activeShortcuts == 0) {
            ctx.getString(R.string.common_disabled)
        } else {
            ctx.getString(R.string.display_shortcuts_active_count, activeShortcuts)
        },
        notifAccessGranted = notifAccessGranted,
        slotSummary = ctx.getString(R.string.slot_exit_slot_label, profileState.activeSlotId + 1, activeSlot.name.take(6)),
        audioSummary = ctx.getString(
            if (audioRouteMode == BridgeProtocol.AudioRoute.SPLIT_BY_APP) R.string.settings_audio_opt_split else R.string.settings_audio_opt_all,
        ),
        usbTetherEnabled = usbTetherEnabled,
        inputBackendSummary = ctx.getString(R.string.settings_engine_lowlevel_summary, backendShort, effectiveQuota),
        hostScreenOff = status.hostScreenOff,
        trustedPeerCount = trustedPeerCount,
    )
}

internal fun buildWorkspaceSpotlightItems(
    ctx: Context,
    scene: CompositorScene,
    allApps: List<InstalledAppEntry>,
    launchCounts: Map<String, Int>,
    dexSpotlightState: DexSpotlightState,
    consoleSpotlightState: ConsoleSpotlightState,
): List<SpotlightItem> {
    val out = ArrayList<SpotlightItem>(scene.windows.size + allApps.size + 52)
    scene.windows.sortedByDescending { it.zOrder }.forEach { win ->
        val stateTag = when {
            win.focused && !win.isMinimized -> ctx.getString(R.string.workspace_win_active)
            win.isMinimized -> ctx.getString(R.string.workspace_win_minimized)
            win.isHibernated -> ctx.getString(R.string.workspace_win_hibernated)
            else -> ctx.getString(R.string.workspace_win_live)
        }
        val profileBadge = AppCatalogRepository.formatProfileBadge(ctx, win.profileType, win.userId)
        val titleWithProfile = if (profileBadge.isNotEmpty()) "${win.title} · $profileBadge" else win.title
        out.add(
            SpotlightItem(
                id = "win:${win.windowId}",
                category = SpotlightCategory.RUNNING_WINDOW,
                title = titleWithProfile,
                subtitle = "$stateTag · ${win.contentWidth}×${win.contentHeight} · ${win.pkg}",
                badge = if (profileBadge.isNotEmpty()) profileBadge else stateTag,
                keywords = listOf(win.pkg, "窗口", "切换") +
                    AppProfileKey.searchKeywords(win.profileType, win.userId),
                usageBoost = if (win.focused) 40 else 20,
                payload = win.appKey,
            ),
        )
    }
    allApps.forEach { app ->
        val count = launchCounts[app.appKey] ?: if (app.userId == 0) (launchCounts[app.pkg] ?: 0) else 0
        val profileBadge = AppCatalogRepository.formatProfileBadge(ctx, app.profileType, app.userId)
        val titleWithProfile = if (profileBadge.isNotEmpty()) "${app.label} · $profileBadge" else app.label
        out.add(
            SpotlightItem(
                id = "app:${app.appKey}",
                category = SpotlightCategory.APP,
                title = titleWithProfile,
                subtitle = app.pkg,
                badge = if (profileBadge.isNotEmpty()) {
                    profileBadge
                } else {
                    ctx.getString(if (count > 0) R.string.workspace_app_frequent else R.string.workspace_app_badge)
                },
                keywords = listOf(app.pkg, app.activityClass.substringAfterLast('.')) +
                    AppProfileKey.searchKeywords(app.profileType, app.userId),
                usageBoost = (count * 4).coerceAtMost(55),
                payload = app.appKey,
            ),
        )
    }
    out.addAll(DexCommandCatalog.buildItems(dexSpotlightState))
    out.addAll(ConsoleSearchCatalog.buildSectionItems(consoleSpotlightState))
    return out
}
