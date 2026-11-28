package io.github.lengsukq.opaldesk

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.RemoteAppPickerModal
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.guide.OpalGuidePolicy
import io.github.lengsukq.opaldesk.notifications.OpalNotificationStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.search.ConsoleSearchCatalog
import io.github.lengsukq.opaldesk.search.ConsoleSpotlightActionIds
import io.github.lengsukq.opaldesk.search.ConsoleSpotlightState
import io.github.lengsukq.opaldesk.search.SpotlightCategory
import io.github.lengsukq.opaldesk.service.QuickEntryPolicy
import io.github.lengsukq.opaldesk.ui.ActivationModal
import io.github.lengsukq.opaldesk.ui.AppUpdateModal
import io.github.lengsukq.opaldesk.ui.ConsoleDashboardTab
import io.github.lengsukq.opaldesk.ui.ConsoleDisplaySettingsTab
import io.github.lengsukq.opaldesk.ui.ConsoleEngineSettingsTab
import io.github.lengsukq.opaldesk.ui.ConsoleFeatureGuideModal
import io.github.lengsukq.opaldesk.ui.ConsoleLogsTab
import io.github.lengsukq.opaldesk.ui.ConsoleSectionKeys
import io.github.lengsukq.opaldesk.ui.DexImeCatalog
import io.github.lengsukq.opaldesk.ui.ExitSaveSlotModal
import io.github.lengsukq.opaldesk.ui.OpalAmbientBackdrop
import io.github.lengsukq.opaldesk.ui.OpalConsoleBottomBar
import io.github.lengsukq.opaldesk.ui.OpalConsoleHeader
import io.github.lengsukq.opaldesk.ui.OpalSpotlightController
import io.github.lengsukq.opaldesk.ui.OpalSpotlightOverlay
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.PairingConsentModal
import io.github.lengsukq.opaldesk.ui.WorkstationShortcutCatalog
import io.github.lengsukq.opaldesk.ui.formatCompactResolutionBadge
import io.github.lengsukq.opaldesk.ui.joinOpalDeskQqGroup
import io.github.lengsukq.opaldesk.ui.openAllowedGuideUrl
import io.github.lengsukq.opaldesk.ui.rememberConsoleNavigationController
import io.github.lengsukq.opaldesk.ui.toggleHostScreenPowerWithToast
import io.github.lengsukq.opaldesk.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Main Jetpack Compose shell for [MainActivity]: hosts the 4 console tabs, bottom navigation bar,
 * auto-enter-DeX startup policy, Spotlight search overlay, pairing/activation/update/guide/exit
 * modals, and the client-side physical-screen blackout wake guard.
 */
@Composable
internal fun OpalConsoleScreen(
    needsLocalNetworkPerm: Boolean,
    autoEnterDexArmed: Boolean,
    openActivationPulse: Int,
    pendingFocusTab: Int,
    pendingFocusSectionKey: String?,
    pendingFocusPulse: Int,
    showSpotlight: Boolean,
    spotlightController: OpalSpotlightController,
    onSetShowSpotlight: (Boolean) -> Unit,
    onDisarmAutoEnterDex: () -> Unit,
    onRequestLocalNetworkPerm: () -> Unit,
    onEnterDesktop: () -> Unit,
    onEnterLandscapeMirrorDesktop: () -> Unit,
    onOpenRemoteTrackpad: () -> Unit,
    onProjectExternalOrTrackpad: (Int) -> Unit,
    onProjectSpecificDisplay: (Int, ExternalDisplayTransport, Boolean) -> Unit,
    onConnectPeerCast: (String, Int, String) -> Unit,
    onPickCustomWallpaper: () -> Unit,
    onStopEngine: () -> Unit,
    onCompleteExitApp: () -> Unit,
) {
    val ctx = LocalContext.current
    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
    val pendingPairing by BridgeClient.pendingPairingFlow.collectAsState()
    val extDisplay by BridgeService.externalDisplayFlow.collectAsState()
    val detectedExtDisplays by BridgeService.detectedExternalDisplaysFlow.collectAsState()
    val trustedPeers by BridgeClient.trustedPeersFlow.collectAsState()
    val imeState by BridgeClient.imeStateFlow.collectAsState()
    val peerRole by OpalPeerCastMutex.roleFlow.collectAsState()

    val navController = rememberConsoleNavigationController(initialTab = 0)
    val selectedTab = navController.selectedTab
    var showActivationModal by remember { mutableStateOf(false) }
    var showUpdateModal by remember { mutableStateOf(false) }
    var showGuideModal by remember { mutableStateOf(false) }
    var showExitConfirmModal by remember { mutableStateOf(false) }
    var exitingApp by remember { mutableStateOf(false) }
    var floatingPickerTarget by remember { mutableStateOf<Triple<String, Int, String>?>(null) }

    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val extUiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val trackpadSens by AppPrefs.trackpadSensitivityFlow.collectAsState()
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    val autoCustomResEnabled by AppPrefs.autoCustomResolutionEnabledFlow.collectAsState()
    val defaultCustomRes by AppPrefs.defaultCustomResolutionFlow.collectAsState()
    val globalShortcutsEnabled by AppPrefs.globalShortcutsEnabledFlow.collectAsState()
    val disabledShortcutIds by AppPrefs.disabledShortcutIdsFlow.collectAsState()
    val dexImeDisplayMode by AppPrefs.dexImeDisplayModeFlow.collectAsState()
    val dexLockedImeId by AppPrefs.dexLockedImeIdFlow.collectAsState()
    val autoBridgeImeOnCast by AppPrefs.autoBridgeImeOnCastFlow.collectAsState()
    val audioRouteMode by AppPrefs.audioRouteModeFlow.collectAsState()
    val usbTetherEnabled by AppPrefs.usbTetherCastEnabledFlow.collectAsState()
    val callControlEnabled by AppPrefs.callControlSyncEnabledFlow.collectAsState()
    val bleNearbyEnabled by AppPrefs.bleNearbyEnabledFlow.collectAsState()
    val spotlightRecents by AppPrefs.spotlightRecentsFlow.collectAsState()
    val notifAccessGranted by OpalNotificationStore.accessGrantedFlow.collectAsState()
    val profileState by ProfileConfigStore.stateFlow.collectAsState()
    val updateState by AppUpdateManager.stateFlow.collectAsState()
    val currentVersion = remember(ctx) { AppUpdateManager.getCurrentVersionName(ctx) }

    LaunchedEffect(Unit) {
        AppUpdateManager.maybeSilentCheckOnStartup(ctx)
    }

    LaunchedEffect(openActivationPulse) {
        if (openActivationPulse > 0) {
            onDisarmAutoEnterDex()
            showActivationModal = true
        }
    }

    LaunchedEffect(pendingFocusPulse) {
        if (pendingFocusPulse > 0) {
            onDisarmAutoEnterDex()
            val targetTab = if (pendingFocusTab in 0..3) pendingFocusTab else navController.selectedTab
            if (!pendingFocusSectionKey.isNullOrBlank()) {
                navController.jumpToSection(targetTab, pendingFocusSectionKey)
            } else if (pendingFocusTab in 0..3) {
                navController.selectedTab = pendingFocusTab
            }
        }
    }

    LaunchedEffect(autoEnterDexArmed) {
        if (!autoEnterDexArmed) return@LaunchedEffect
        val autoShizukuCandidate = AppPrefs.isAutoShizukuEnabled(ctx) && ShizukuBootstrap.isShizukuInstalled(ctx)
        val autoRootCandidate = AppPrefs.isAutoRootEnabled(ctx) && AdbBootstrap.hasSuBinaryHint()
        val waitBudgetMs = QuickEntryPolicy.computeStartupWaitBudgetMs(
            autoShizukuCandidate = autoShizukuCandidate,
            autoRootCandidate = autoRootCandidate,
        )
        delay(waitBudgetMs)
        onDisarmAutoEnterDex()
    }

    LaunchedEffect(autoEnterDexArmed, status.connected) {
        if (!autoEnterDexArmed || !status.connected) return@LaunchedEffect
        val freshRemote = withContext(Dispatchers.IO) {
            BridgeClient.refreshRemoteStatus()
        }
        val decision = QuickEntryPolicy.evaluateAutoEnterDex(
            QuickEntryPolicy.AutoEnterDexInput(
                autoEnterEnabled = AppPrefs.isAutoEnterDexEnabled(ctx),
                isColdLaunch = true,
                launchedFromDesktop = false,
                bypassAutoEnter = false,
                userManuallyCancelled = !autoEnterDexArmed,
                userStopping = BridgeService.sUserStopping,
                engineConnected = status.connected,
                remoteCastConnected = freshRemote.connected,
                physicalExternalActive = ExternalTrackpadBridge.sessionFlow.value.isPhysicalActive,
                hasPendingPairing = BridgeClient.pendingPairingFlow.value != null,
            ),
        )
        onDisarmAutoEnterDex()
        if (decision == QuickEntryPolicy.AutoEnterDecision.ENTER_DEX) {
            onEnterDesktop()
        }
    }

    val availableReleaseTag = when (val st = updateState) {
        is AppUpdateManager.UpdateState.UpdateAvailable -> st.release.tagName
        is AppUpdateManager.UpdateState.Downloading -> st.release.tagName
        is AppUpdateManager.UpdateState.ReadyToInstall -> st.release.tagName
        else -> null
    }

    OpalAmbientBackdrop(
        modifier = Modifier.fillMaxSize(),
        wallpaperPreset = wallpaperPreset,
        consoleScrim = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            key(selectedTab) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .widthIn(max = 800.dp)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OpalConsoleHeader(
                        selectedTab = selectedTab,
                        currentVersion = currentVersion,
                        connected = status.connected,
                        rootEnhanced = status.rootEnhanced,
                        availableReleaseTag = availableReleaseTag,
                        readyToInstallUpdate = updateState is AppUpdateManager.UpdateState.ReadyToInstall,
                        onOpenActivation = {
                            onDisarmAutoEnterDex()
                            showActivationModal = true
                        },
                        onOpenUpdate = {
                            onDisarmAutoEnterDex()
                            showUpdateModal = true
                        },
                        onOpenSearch = {
                            onDisarmAutoEnterDex()
                            spotlightController.resetSelection()
                            onSetShowSpotlight(true)
                        },
                    )

                    when (selectedTab) {
                        0 -> ConsoleDashboardTab(
                            status = status,
                            scene = scene,
                            remote = remote,
                            extDisplay = extDisplay,
                            detectedExtDisplays = detectedExtDisplays,
                            needsLocalNetworkPerm = needsLocalNetworkPerm,
                            onRequestLocalNetworkPerm = onRequestLocalNetworkPerm,
                            onEnterLocalDesktop = {
                                onDisarmAutoEnterDex()
                                onEnterDesktop()
                            },
                            onEnterLandscapeMirrorDesktop = {
                                onDisarmAutoEnterDex()
                                onEnterLandscapeMirrorDesktop()
                            },
                            onOpenRemoteTrackpad = onOpenRemoteTrackpad,
                            onOpenExternalOrTrackpad = onProjectExternalOrTrackpad,
                            onProjectSpecificDisplay = onProjectSpecificDisplay,
                            onConnectPeerCast = onConnectPeerCast,
                            onOpenFloatingAppPicker = { host, port, label ->
                                val check = OpalPeerCastMutex.validateStartReceiving(
                                    daemonSending = remote.connected,
                                    sendingPeerName = remote.peerName,
                                    targetHost = host,
                                    selfLanIp = remote.lanIp,
                                )
                                if (!check.allowed) {
                                    Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                                } else {
                                    floatingPickerTarget = Triple(host, port, label)
                                }
                            },
                            onOpenActivation = {
                                onDisarmAutoEnterDex()
                                showActivationModal = true
                            },
                            onRequestCompleteExit = {
                                onDisarmAutoEnterDex()
                                showExitConfirmModal = true
                            },
                            navController = navController,
                        )
                        1 -> ConsoleDisplaySettingsTab(
                            status = status,
                            remote = remote,
                            extDisplay = extDisplay,
                            detectedExtDisplays = detectedExtDisplays,
                            onProjectSpecificDisplay = onProjectSpecificDisplay,
                            onPickCustomWallpaper = onPickCustomWallpaper,
                            navController = navController,
                        )
                        2 -> ConsoleEngineSettingsTab(
                            status = status,
                            scene = scene,
                            onOpenActivation = {
                                onDisarmAutoEnterDex()
                                showActivationModal = true
                            },
                            onOpenUpdateModal = {
                                onDisarmAutoEnterDex()
                                showUpdateModal = true
                            },
                            onOpenFeatureGuide = {
                                onDisarmAutoEnterDex()
                                showGuideModal = true
                            },
                            onStopEngine = onStopEngine,
                            navController = navController,
                        )
                        else -> ConsoleLogsTab(
                            status = status,
                            scene = scene,
                            remote = remote,
                            onOpenUpdateModal = {
                                onDisarmAutoEnterDex()
                                showUpdateModal = true
                            },
                            navController = navController,
                        )
                    }
                }
            }

            OpalConsoleBottomBar(
                selectedTab = selectedTab,
                onSelectTab = {
                    onDisarmAutoEnterDex()
                    navController.selectedTab = it
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (showActivationModal) {
            ActivationModal(
                needsLocalNetworkPerm = needsLocalNetworkPerm,
                onRequestLocalNetworkPerm = onRequestLocalNetworkPerm,
                onDismiss = { showActivationModal = false },
                onActivated = { showActivationModal = false },
            )
        }

        if (showUpdateModal) {
            AppUpdateModal(
                onDismiss = { showUpdateModal = false },
            )
        }

        if (showGuideModal) {
            ConsoleFeatureGuideModal(
                onAction = { action, url ->
                    when (action) {
                        OpalGuidePolicy.ACTION_OPEN_URL -> openAllowedGuideUrl(ctx, url)
                        OpalGuidePolicy.ACTION_JOIN_QQ -> joinOpalDeskQqGroup(ctx)
                        OpalGuidePolicy.ACTION_OPEN_SHORTCUTS -> {
                            showGuideModal = false
                            navController.jumpToSection(1, ConsoleSectionKeys.KEYBOARD_SHORTCUTS)
                        }
                        OpalGuidePolicy.ACTION_OPEN_DPI -> {
                            showGuideModal = false
                            navController.jumpToSection(1, ConsoleSectionKeys.PER_APP_DPI)
                        }
                        OpalGuidePolicy.ACTION_OPEN_TILING,
                        OpalGuidePolicy.ACTION_OPEN_DOCK_SETTINGS,
                        OpalGuidePolicy.ACTION_OPEN_CONTROL_CENTER -> {
                            showGuideModal = false
                            navController.selectedTab = 1
                        }
                        OpalGuidePolicy.ACTION_OPEN_CONSOLE -> {
                            showGuideModal = false
                        }
                    }
                },
                onDismiss = { showGuideModal = false },
            )
        }

        if (showExitConfirmModal) {
            val detailText = buildString {
                append(ctx.getString(R.string.exit_app_subtitle_base))
                if (scene.windows.isNotEmpty()) {
                    append(ctx.getString(R.string.exit_app_subtitle_windows, scene.windows.size))
                }
            }
            ExitSaveSlotModal(
                title = stringResource(R.string.exit_app_title),
                subtitle = detailText,
                confirmLabel = stringResource(R.string.exit_app_save_and_exit),
                openWindowCount = scene.windows.size,
                busy = exitingApp,
                onDismiss = { if (!exitingApp) showExitConfirmModal = false },
                onExitWithoutSaving = {
                    if (!exitingApp) {
                        exitingApp = true
                        onCompleteExitApp()
                    }
                },
                onSaveToSlotAndExit = { slotId, saveLayout ->
                    if (!exitingApp) {
                        exitingApp = true
                        Thread({
                            BridgeClient.pullDaemonWindowBoundsSync()
                            val curTarget = ProfileConfigStore.resolveDisplayTarget(
                                isExternalMonitor = extDisplay != null,
                                remoteConnected = remote.connected,
                                remotePeerName = remote.peerName,
                                externalDisplayName = extDisplay?.name.orEmpty(),
                                externalWidth = extDisplay?.width ?: 0,
                                externalHeight = extDisplay?.height ?: 0,
                            )
                            ProfileConfigStore.saveWorkingToSlot(
                                ctx = ctx,
                                targetSlotId = slotId,
                                scene = scene,
                                saveCurrentLayout = saveLayout,
                                bindTargetKey = curTarget.key,
                            )
                            Handler(Looper.getMainLooper()).post {
                                onCompleteExitApp()
                            }
                        }, "OpalSaveAndExit").start()
                    }
                },
            )
        }

        floatingPickerTarget?.let { (host, port, label) ->
            RemoteAppPickerModal(
                host = host,
                port = port,
                peerLabel = label,
                onDismiss = { floatingPickerTarget = null },
                onFloatingLaunched = { floatingPickerTarget = null },
            )
        }

        pendingPairing?.let { req ->
            PairingConsentModal(request = req)
        }

        if (showSpotlight) {
            val spotlightState = buildConsoleSpotlightState(
                status = status,
                scene = scene,
                remote = remote,
                extDisplay = extDisplay,
                trustedPeerCount = trustedPeers.size,
                imeState = imeState,
                peerRole = peerRole,
                profileState = profileState,
                themeMode = themeMode,
                extUiScale = extUiScale,
                trackpadSens = trackpadSens,
                customWallpaperPath = customWallpaperPath,
                autoCustomResEnabled = autoCustomResEnabled,
                defaultCustomRes = defaultCustomRes,
                globalShortcutsEnabled = globalShortcutsEnabled,
                disabledShortcutIds = disabledShortcutIds,
                dexImeDisplayMode = dexImeDisplayMode,
                dexLockedImeId = dexLockedImeId,
                autoBridgeImeOnCast = autoBridgeImeOnCast,
                audioRouteMode = audioRouteMode,
                usbTetherEnabled = usbTetherEnabled,
                callControlEnabled = callControlEnabled,
                bleNearbyEnabled = bleNearbyEnabled,
                notifAccessGranted = notifAccessGranted,
                currentVersion = currentVersion,
                availableReleaseTag = availableReleaseTag,
            )
            val spotlightItems = remember(spotlightState) { ConsoleSearchCatalog.buildItems(spotlightState) }
            OpalSpotlightOverlay(
                items = spotlightItems,
                recentIds = spotlightRecents,
                placeholder = stringResource(R.string.spotlight_console_placeholder),
                quickChips = ConsoleSearchCatalog.QUICK_CHIPS,
                controller = spotlightController,
                onSelectItem = { chosen ->
                    AppPrefs.recordSpotlightRecent(ctx, chosen.id)
                    onSetShowSpotlight(false)
                    onDisarmAutoEnterDex()
                    if (chosen.category == SpotlightCategory.CONSOLE_SECTION &&
                        chosen.tabIndex != null && !chosen.sectionKey.isNullOrBlank()
                    ) {
                        navController.jumpToSection(chosen.tabIndex, chosen.sectionKey)
                    } else {
                        when (chosen.id) {
                            ConsoleSpotlightActionIds.ENTER_DEX -> {
                                if (status.connected) onEnterDesktop() else showActivationModal = true
                            }
                            ConsoleSpotlightActionIds.OPEN_TRACKPAD -> onOpenRemoteTrackpad()
                            ConsoleSpotlightActionIds.OPEN_ACTIVATION -> showActivationModal = true
                            ConsoleSpotlightActionIds.TOGGLE_WIFI_SHARE -> {
                                if (!status.connected) {
                                    showActivationModal = true
                                } else {
                                    val enable = !remote.isLanShareActive
                                    if (enable) {
                                        val check = OpalPeerCastMutex.validateEnableSending(peerRole.isReceiving, peerRole.peerName)
                                        if (!check.allowed) {
                                            Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                                            return@OpalSpotlightOverlay
                                        }
                                    }
                                    BridgeClient.setWifiStreamEnabledAsync(enable, 19878, BridgeProtocol.TlsBindScope.ALL) {
                                        val latest = BridgeClient.remoteStatusFlow.value
                                        OpalPeerDiscovery.syncAdvertise(
                                            ctx,
                                            latest.isLanShareActive,
                                            latest.wifiPort,
                                            latest.shortCertFingerprint,
                                        )
                                        Toast.makeText(
                                            ctx,
                                            ctx.getString(if (latest.isLanShareActive) R.string.toast_lan_share_on else R.string.toast_lan_share_off),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                            ConsoleSpotlightActionIds.CHECK_UPDATE -> {
                                when (updateState) {
                                    is AppUpdateManager.UpdateState.UpdateAvailable,
                                    is AppUpdateManager.UpdateState.Downloading,
                                    is AppUpdateManager.UpdateState.ReadyToInstall,
                                    -> showUpdateModal = true
                                    else -> {
                                        AppUpdateManager.checkForUpdates(ctx, userInitiated = true)
                                        showUpdateModal = true
                                    }
                                }
                            }
                            ConsoleSpotlightActionIds.OPEN_GUIDE -> showGuideModal = true
                            ConsoleSpotlightActionIds.PICK_WALLPAPER -> onPickCustomWallpaper()
                            ConsoleSpotlightActionIds.REPORT_ISSUE -> {
                                navController.jumpToSection(3, ConsoleSectionKeys.DIAGNOSTICS_LOGS)
                            }
                            ConsoleSpotlightActionIds.TOGGLE_SCREEN_OFF -> {
                                if (status.connected) {
                                    toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff)
                                } else {
                                    showActivationModal = true
                                }
                            }
                            ConsoleSpotlightActionIds.COMPLETE_EXIT -> showExitConfirmModal = true
                        }
                    }
                },
                onDismiss = { onSetShowSpotlight(false) },
            )
        }

        // Fullscreen client-side double-tap wake guard while physical screen power is off:
        // guarantees double-tap wake even on Android 15/16 OEMs (e.g. HyperOS) where SELinux blocks
        // rootless /dev/input/event* evdev reads inside HostScreenWakeMonitor.
        if (status.hostScreenOff) {
            ConsoleBlackoutWakeOverlay()
        }
    }
}

@Composable
private fun buildConsoleSpotlightState(
    status: io.github.lengsukq.opaldesk.protocol.DaemonStatus,
    scene: io.github.lengsukq.opaldesk.protocol.CompositorScene,
    remote: io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    trustedPeerCount: Int,
    imeState: io.github.lengsukq.opaldesk.protocol.DexImeRuntimeState,
    peerRole: OpalPeerCastMutex.CastRoleState,
    profileState: io.github.lengsukq.opaldesk.prefs.ProfileStoreState,
    themeMode: Int,
    extUiScale: Float,
    trackpadSens: Float,
    customWallpaperPath: String,
    autoCustomResEnabled: Boolean,
    defaultCustomRes: io.github.lengsukq.opaldesk.external.CustomResolutionSpec,
    globalShortcutsEnabled: Boolean,
    disabledShortcutIds: Set<String>,
    dexImeDisplayMode: Int,
    dexLockedImeId: String,
    autoBridgeImeOnCast: Boolean,
    audioRouteMode: Int,
    usbTetherEnabled: Boolean,
    callControlEnabled: Boolean,
    bleNearbyEnabled: Boolean,
    notifAccessGranted: Boolean,
    currentVersion: String,
    availableReleaseTag: String?,
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
        BridgeProtocol.InputBackend.SYSTEM -> stringResource(R.string.settings_backend_system)
        BridgeProtocol.InputBackend.UHID -> "UHID"
        else -> stringResource(R.string.common_auto)
    }
    val effectiveQuota = status.maxVdCount.takeIf { it > 0 } ?: working.maxVdCount
    return ConsoleSpotlightState(
        connected = status.connected,
        rootEnhanced = status.rootEnhanced,
        windowCount = scene.windows.size,
        remoteConnected = remote.connected,
        lanShareActive = remote.isLanShareActive && !peerRole.isReceiving,
        lanEndpoint = if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:${if (remote.wifiPort > 0) remote.wifiPort else 19878}" else "",
        extDisplaySummary = extDisplay?.let { "${it.name} · ${it.width}×${it.height} @ ${it.refreshRateHz}Hz" }.orEmpty(),
        themeModeLabel = when (themeMode) {
            AppPrefs.THEME_DARK -> stringResource(R.string.common_dark)
            AppPrefs.THEME_SYSTEM -> stringResource(R.string.common_follow_system)
            else -> stringResource(R.string.common_light)
        },
        targetFpsLabel = if (status.targetFps > 0) "${status.targetFps}Hz" else stringResource(R.string.common_auto),
        uiScaleLabel = "${(extUiScale * 100f).roundToInt()}%",
        resolutionSummary = when {
            extDisplay != null -> formatCompactResolutionBadge(extDisplay.width, extDisplay.height, extDisplay.refreshRateHz)
            remote.connected -> formatCompactResolutionBadge(remote.width, remote.height, (remote.fpsX100 / 100f).roundToInt().coerceAtLeast(24))
            autoCustomResEnabled -> formatCompactResolutionBadge(defaultCustomRes.width, defaultCustomRes.height, defaultCustomRes.fps)
            else -> stringResource(R.string.display_res_adaptive_edid)
        },
        perAppDpiSummary = when {
            working.appCustomDpis.isNotEmpty() -> stringResource(R.string.display_dpi_custom_count, working.appCustomDpis.size)
            working.globalCustomDpi > 0 -> stringResource(R.string.display_dpi_global_val, working.globalCustomDpi)
            else -> stringResource(R.string.display_dpi_auto)
        },
        peripheralsSummary = if (customWallpaperPath.isNotBlank()) {
            stringResource(R.string.display_album_wallpaper_short, String.format(Locale.US, "%.2f×", trackpadSens))
        } else {
            String.format(Locale.US, "%.2f×", trackpadSens)
        },
        imeSummary = when {
            yuyanActive -> stringResource(R.string.display_ime_yuyan_badge)
            dexLockedImeId.isNotBlank() -> DexImeCatalog.fallbackLabelForImeId(dexLockedImeId).substringBefore(" (").take(10)
            else -> DexImeCatalog.displayModeTitle(dexImeDisplayMode)
        },
        shortcutsSummary = if (!globalShortcutsEnabled || activeShortcuts == 0) {
            stringResource(R.string.common_disabled)
        } else {
            stringResource(R.string.display_shortcuts_active_count, activeShortcuts)
        },
        notifAccessGranted = notifAccessGranted,
        callControlEnabled = callControlEnabled,
        bleNearbyEnabled = bleNearbyEnabled,
        slotSummary = stringResource(R.string.settings_slot_named, profileState.activeSlotId + 1, activeSlot.name.take(6)),
        audioSummary = stringResource(if (audioRouteMode == BridgeProtocol.AudioRoute.SPLIT_BY_APP) R.string.settings_audio_sum_split else R.string.settings_audio_sum_all),
        usbTetherEnabled = usbTetherEnabled,
        inputBackendSummary = stringResource(R.string.settings_engine_lowlevel_summary, backendShort, effectiveQuota),
        currentVersion = currentVersion,
        availableReleaseTag = availableReleaseTag,
        hostScreenOff = status.hostScreenOff,
        trustedPeerCount = trustedPeerCount,
    )
}

@Composable
private fun ConsoleBlackoutWakeOverlay() {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        BridgeClient.setHostScreenPowerAsync(true) { ok ->
                            if (ok) {
                                Toast.makeText(ctx, ctx.getString(R.string.toast_host_screen_on), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpalText(
                text = stringResource(R.string.blackout_overlay_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.28f),
            )
            OpalText(
                text = stringResource(R.string.blackout_overlay_hint),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.20f),
            )
        }
    }
}
