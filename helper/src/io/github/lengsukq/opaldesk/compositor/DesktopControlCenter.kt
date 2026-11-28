package io.github.lengsukq.opaldesk.compositor

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.notifications.OpalNotificationStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassSlider
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalSwitchRow
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlin.math.roundToInt

/**
 * Unified Top-Right macOS Control Center Popover consolidating former top-bar indicators
 * (virtual display pool, active output summary, all tiling layouts), host phone screen-off power
 * control, external keyboard & mouse target routing, appearance, and wallpapers.
 */
@Composable
fun MacControlCenterPopover(
    tray: SystemTrayInfo,
    totalWindows: Int,
    focusedWindowId: Int = -1,
    canvasW: Int = 1920,
    canvasH: Int = 1200,
    displaySummary: String,
    inputBackendLabel: String,
    targetFps: Int,
    actualRefreshHz: Int = 60,
    maxSupportedRefreshHz: Int = 60,
    isRemoteCast: Boolean = false,
    windowDpiScalePct: Int,
    externalUiScale: Float,
    themeMode: Int,
    wallpaperPreset: Int,
    mouseTargetMode: Int,
    isExternalMonitor: Boolean,
    hostScreenOff: Boolean,
    autoBlackoutOnCast: Boolean = false,
    rootEnhanced: Boolean = false,
    showHostScreenToggle: Boolean,
    showRemoteMediaSection: Boolean = false,
    showPhoneTrackpadSection: Boolean = false,
    phoneTrackpadVisible: Boolean = false,
    showAutoTrackpadSwitch: Boolean = false,
    autoShowPhoneTrackpad: Boolean = true,
    dockVisible: Boolean = true,
    dockOpacity: Float = DockAppearancePolicy.DEFAULT_OPACITY,
    dockScale: Float = DockAppearancePolicy.DEFAULT_SCALE,
    globalShortcutsEnabled: Boolean = true,
    disabledShortcutIds: Set<String> = emptySet(),
    dexImeDisplayMode: Int = BridgeProtocol.DexImeDisplayMode.AUTO,
    dexLockedImeId: String = "",
    showDesktopGuideIcon: Boolean = true,
    maxHeight: Dp = 640.dp,
    initialTab: Int = 0,
    onToggleDockVisible: (Boolean) -> Unit = {},
    onChangeDockOpacity: (Float) -> Unit = {},
    onChangeDockScale: (Float) -> Unit = {},
    onResetDockStyle: () -> Unit = {},
    onOpenSystemSettings: ((String) -> Unit)? = null,
    onTogglePhoneTrackpad: () -> Unit = {},
    onToggleAutoPhoneTrackpad: (Boolean) -> Unit = {},
    onSelectTargetFps: (Int) -> Unit,
    onSelectWindowDpiPct: (Int) -> Unit,
    onSelectUiScale: (Float) -> Unit,
    onToggleHostScreenPower: (Boolean) -> Unit,
    onToggleAutoBlackout: (Boolean) -> Unit = {},
    onSelectThemeMode: (Int) -> Unit,
    onSelectWallpaper: (Int) -> Unit,
    onPickCustomWallpaper: () -> Unit,
    onSelectMouseTarget: (Int) -> Unit,
    onOpenDpiSettings: () -> Unit = {},
    onOpenResolutionSettings: (() -> Unit)? = null,
    onToggleExternalImeSync: (Boolean) -> Unit = {},
    onOpenImeSettings: () -> Unit = {},
    onOpenKeyboardShortcuts: () -> Unit = {},
    onOpenFeatureGuide: () -> Unit = {},
    onApplyTilingLayout: (WindowTilingLayout) -> Unit,
    onMinimizeAll: () -> Unit,
    onCloseAll: () -> Unit,
    onDismissPopover: () -> Unit = {},
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val audioMgr = remember(ctx) { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = remember(audioMgr) {
        (try { audioMgr?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { null } ?: 15).coerceAtLeast(1)
    }
    var mediaVolume by remember(audioMgr) {
        mutableIntStateOf(
            (try { audioMgr?.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { null } ?: (maxVolume / 2))
                .coerceIn(0, maxVolume),
        )
    }
    var lastNonZeroVolume by remember { mutableIntStateOf(mediaVolume.coerceAtLeast(1)) }
    var screenBrightness by remember(ctx) { mutableStateOf(ControlCenterActions.readInitialBrightness(ctx)) }
    var brightnessAuto by remember { mutableStateOf(true) }
    var orientationMode by remember(ctx) { mutableIntStateOf(ControlCenterActions.readOrientationMode(ctx)) }
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var showMoreTiling by remember { mutableStateOf(false) }
    var showRemoteAdvanced by remember { mutableStateOf(false) }

    fun applyMediaVolume(target: Int) {
        val clamped = target.coerceIn(0, maxVolume)
        mediaVolume = clamped
        if (clamped > 0) lastNonZeroVolume = clamped
        try {
            audioMgr?.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        } catch (_: Throwable) {
        }
    }

    val shape = RoundedCornerShape(22.dp)
    val infoCardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .widthIn(max = 352.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.SolidPopoverFill,
                rim = OpalPalette.SolidPopoverRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OpalText(text = stringResource(R.string.taskbar_cc_title), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.ChipFill)
                        .padding(horizontal = 7.dp, vertical = 2.5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (totalWindows > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted),
                    )
                    OpalText(
                        text = if (totalWindows > 0) {
                            stringResource(R.string.dash_hero_window_count, totalWindows)
                        } else {
                            stringResource(R.string.common_ready)
                        },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentCyan,
                        maxLines = 1,
                    )
                }
            }
            val batLabel = if (tray.charging) "⚡${tray.batteryPct}%" else "${tray.batteryPct}%"
            val summaryText = if (displaySummary.isNotBlank()) "$displaySummary · $batLabel" else "$inputBackendLabel · $batLabel"
            OpalText(
                text = summaryText,
                fontSize = 10.5.sp,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(8.dp))
        OpalSegmentedRow(
            options = listOf(
                stringResource(R.string.taskbar_cc_tab_quick),
                stringResource(R.string.taskbar_cc_tab_windows),
                stringResource(R.string.taskbar_cc_tab_display),
            ),
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )

        Spacer(Modifier.height(9.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (selectedTab) {
                0 -> ControlCenterQuickTab(
                    tray = tray,
                    selectedTabKey = selectedTab,
                    focusedWindowId = focusedWindowId,
                    audioMgr = audioMgr,
                    mediaVolume = mediaVolume,
                    maxVolume = maxVolume,
                    lastNonZeroVolume = lastNonZeroVolume,
                    screenBrightness = screenBrightness,
                    brightnessAuto = brightnessAuto,
                    orientationMode = orientationMode,
                    isExternalMonitor = isExternalMonitor,
                    hostScreenOff = hostScreenOff,
                    rootEnhanced = rootEnhanced,
                    showHostScreenToggle = showHostScreenToggle,
                    showRemoteMediaSection = showRemoteMediaSection,
                    showPhoneTrackpadSection = showPhoneTrackpadSection,
                    phoneTrackpadVisible = phoneTrackpadVisible,
                    showAutoTrackpadSwitch = showAutoTrackpadSwitch,
                    autoShowPhoneTrackpad = autoShowPhoneTrackpad,
                    dexImeDisplayMode = dexImeDisplayMode,
                    showRemoteAdvanced = showRemoteAdvanced,
                    infoCardShape = infoCardShape,
                    onOpenSystemSettings = onOpenSystemSettings,
                    onChangeOrientationMode = { next ->
                        orientationMode = next
                        ControlCenterActions.applyOrientationMode(ctx, next)
                    },
                    onToggleHostScreenPower = onToggleHostScreenPower,
                    onTogglePhoneTrackpad = onTogglePhoneTrackpad,
                    onToggleExternalImeSync = onToggleExternalImeSync,
                    onApplyMediaVolume = ::applyMediaVolume,
                    onSetBrightnessAuto = {
                        brightnessAuto = true
                        ControlCenterActions.applyWindowBrightness(ctx, -1f)
                    },
                    onChangeBrightness = { v ->
                        brightnessAuto = false
                        screenBrightness = v
                        ControlCenterActions.applyWindowBrightness(ctx, v)
                    },
                    onToggleRemoteAdvanced = { showRemoteAdvanced = !showRemoteAdvanced },
                    onToggleAutoPhoneTrackpad = onToggleAutoPhoneTrackpad,
                    onDismissPopover = onDismissPopover,
                )

                1 -> ControlCenterWindowsTab(
                    totalWindows = totalWindows,
                    canvasW = canvasW,
                    canvasH = canvasH,
                    showMoreTiling = showMoreTiling,
                    onToggleMoreTiling = { showMoreTiling = !showMoreTiling },
                    onApplyTilingLayout = onApplyTilingLayout,
                    onMinimizeAll = onMinimizeAll,
                    onCloseAll = onCloseAll,
                    onOpenDpiSettings = onOpenDpiSettings,
                    onOpenImeSettings = onOpenImeSettings,
                    onOpenKeyboardShortcuts = onOpenKeyboardShortcuts,
                )

                else -> ControlCenterDisplayTab(
                    canvasW = canvasW,
                    canvasH = canvasH,
                    targetFps = targetFps,
                    actualRefreshHz = actualRefreshHz,
                    maxSupportedRefreshHz = maxSupportedRefreshHz,
                    isRemoteCast = isRemoteCast,
                    externalUiScale = externalUiScale,
                    themeMode = themeMode,
                    wallpaperPreset = wallpaperPreset,
                    mouseTargetMode = mouseTargetMode,
                    isExternalMonitor = isExternalMonitor,
                    hostScreenOff = hostScreenOff,
                    autoBlackoutOnCast = autoBlackoutOnCast,
                    showHostScreenToggle = showHostScreenToggle,
                    dockVisible = dockVisible,
                    dockOpacity = dockOpacity,
                    dockScale = dockScale,
                    infoCardShape = infoCardShape,
                    onSelectTargetFps = onSelectTargetFps,
                    onOpenResolutionSettings = onOpenResolutionSettings,
                    onToggleAutoBlackout = onToggleAutoBlackout,
                    onToggleDockVisible = onToggleDockVisible,
                    onChangeDockOpacity = onChangeDockOpacity,
                    onChangeDockScale = onChangeDockScale,
                    onResetDockStyle = onResetDockStyle,
                    onSelectUiScale = onSelectUiScale,
                    onSelectMouseTarget = onSelectMouseTarget,
                    onSelectThemeMode = onSelectThemeMode,
                    onSelectWallpaper = onSelectWallpaper,
                    onPickCustomWallpaper = onPickCustomWallpaper,
                )
            }
        }

        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OpalButton(
                label = stringResource(R.string.taskbar_cc_guide),
                compact = true,
                filled = false,
                accent = OpalPalette.AccentCyan,
                onClick = onOpenFeatureGuide,
                modifier = Modifier.weight(0.95f),
            )
            OpalButton(
                label = stringResource(R.string.taskbar_cc_console),
                compact = true,
                filled = false,
                onClick = onOpenConsole,
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = stringResource(R.string.taskbar_cc_exit_dex),
                compact = true,
                filled = false,
                accent = OpalPalette.AccentDanger,
                onClick = onExitDesktop,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ControlCenterQuickTab(
    tray: SystemTrayInfo,
    selectedTabKey: Int,
    focusedWindowId: Int,
    audioMgr: AudioManager?,
    mediaVolume: Int,
    maxVolume: Int,
    lastNonZeroVolume: Int,
    screenBrightness: Float,
    brightnessAuto: Boolean,
    orientationMode: Int,
    isExternalMonitor: Boolean,
    hostScreenOff: Boolean,
    rootEnhanced: Boolean,
    showHostScreenToggle: Boolean,
    showRemoteMediaSection: Boolean,
    showPhoneTrackpadSection: Boolean,
    phoneTrackpadVisible: Boolean,
    showAutoTrackpadSwitch: Boolean,
    autoShowPhoneTrackpad: Boolean,
    dexImeDisplayMode: Int,
    showRemoteAdvanced: Boolean,
    infoCardShape: RoundedCornerShape,
    onOpenSystemSettings: ((String) -> Unit)?,
    onChangeOrientationMode: (Int) -> Unit,
    onToggleHostScreenPower: (Boolean) -> Unit,
    onTogglePhoneTrackpad: () -> Unit,
    onToggleExternalImeSync: (Boolean) -> Unit,
    onApplyMediaVolume: (Int) -> Unit,
    onSetBrightnessAuto: () -> Unit,
    onChangeBrightness: (Float) -> Unit,
    onToggleRemoteAdvanced: () -> Unit,
    onToggleAutoPhoneTrackpad: (Boolean) -> Unit,
    onDismissPopover: () -> Unit,
) {
    val ctx = LocalContext.current
    val btEnabled = remember(ctx, selectedTabKey) {
        try {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.isEnabled
        } catch (_: Throwable) {
            null
        }
    }
    fun openSystemSettings(action: String) {
        val inDexHandler = onOpenSystemSettings
        if (inDexHandler != null) {
            inDexHandler(action)
            return
        }
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
    }

    val dndEnabled by OpalNotificationStore.doNotDisturbFlow.collectAsState()
    val btOn = btEnabled == true

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlBentoTile(
            title = "Wi-Fi",
            subtitle = when {
                tray.wifiConnected -> stringResource(R.string.common_connected)
                tray.wifiEnabled -> stringResource(R.string.common_disconnected)
                else -> stringResource(R.string.common_disabled)
            },
            active = tray.wifiConnected,
            accent = OpalPalette.AccentBlue,
            iconContent = { tint ->
                OpalGlyphIcon(
                    glyph = if (tray.wifiEnabled || tray.wifiConnected) ChromeGlyph.WIFI else ChromeGlyph.WIFI_OFF,
                    tint = tint,
                    size = 14.dp,
                )
            },
            onClick = { openSystemSettings(Settings.ACTION_WIFI_SETTINGS) },
            modifier = Modifier.weight(1f),
        )
        ControlBentoTile(
            title = stringResource(R.string.taskbar_cc_bluetooth),
            subtitle = when (btEnabled) {
                true -> stringResource(R.string.common_enabled)
                false -> stringResource(R.string.common_disabled)
                null -> stringResource(R.string.common_settings)
            },
            active = btOn,
            accent = OpalPalette.AccentBlue,
            iconContent = { tint ->
                OpalText(
                    text = "B",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            },
            onClick = { openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlBentoTile(
            title = stringResource(R.string.taskbar_cc_dnd),
            subtitle = stringResource(if (dndEnabled) R.string.common_enabled else R.string.common_disabled),
            active = dndEnabled,
            accent = OpalPalette.AccentAmber,
            iconContent = { tint ->
                OpalText(
                    text = "☾",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            },
            onClick = { OpalNotificationStore.toggleDoNotDisturb() },
            modifier = Modifier.weight(1f),
        )

        if (!isExternalMonitor) {
            val orientLabel = when (orientationMode) {
                1 -> stringResource(R.string.taskbar_cc_orient_land)
                2 -> stringResource(R.string.taskbar_cc_orient_port)
                else -> stringResource(R.string.taskbar_cc_orient_auto)
            }
            ControlBentoTile(
                title = stringResource(R.string.taskbar_cc_orientation),
                subtitle = orientLabel,
                active = orientationMode != 0,
                accent = OpalPalette.AccentCyan,
                iconContent = { tint ->
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.ORIENTATION,
                        tint = tint,
                        size = 14.dp,
                    )
                },
                onClick = { onChangeOrientationMode((orientationMode + 1) % 3) },
                modifier = Modifier.weight(1f),
            )
        } else if (showHostScreenToggle || hostScreenOff) {
            ControlBentoTile(
                title = stringResource(R.string.taskbar_cc_host_off),
                subtitle = stringResource(if (hostScreenOff) R.string.taskbar_cc_host_off_sub_on else R.string.taskbar_cc_host_off_sub_off),
                active = hostScreenOff,
                accent = OpalPalette.AccentEmerald,
                iconContent = { tint ->
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.SCREEN_OFF,
                        tint = tint,
                        size = 14.dp,
                    )
                },
                onClick = { onToggleHostScreenPower(hostScreenOff) },
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }

    if (showPhoneTrackpadSection || isExternalMonitor) {
        val imeLocalEnabled = dexImeDisplayMode == BridgeProtocol.DexImeDisplayMode.ALWAYS_LOCAL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showPhoneTrackpadSection) {
                ControlBentoTile(
                    title = stringResource(R.string.taskbar_cc_trackpad),
                    subtitle = stringResource(if (phoneTrackpadVisible) R.string.taskbar_cc_trackpad_on else R.string.taskbar_cc_trackpad_off),
                    active = phoneTrackpadVisible,
                    accent = OpalPalette.AccentBlue,
                    iconContent = { tint ->
                        OpalGlyphIcon(
                            glyph = ChromeGlyph.EXTERNAL_MONITOR,
                            tint = tint,
                            size = 14.dp,
                        )
                    },
                    onClick = onTogglePhoneTrackpad,
                    modifier = Modifier.weight(1f),
                )
            }
            if (isExternalMonitor) {
                ControlBentoTile(
                    title = stringResource(R.string.taskbar_cc_ext_ime),
                    subtitle = stringResource(if (imeLocalEnabled) R.string.taskbar_cc_ext_ime_local else R.string.taskbar_cc_ext_ime_auto),
                    active = imeLocalEnabled,
                    accent = OpalPalette.AccentEmerald,
                    iconContent = { tint ->
                        OpalGlyphIcon(
                            glyph = ChromeGlyph.KEYBOARD,
                            tint = tint,
                            size = 14.dp,
                        )
                    },
                    onClick = { onToggleExternalImeSync(!imeLocalEnabled) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (!showPhoneTrackpadSection || !isExternalMonitor) {
                Spacer(Modifier.weight(1f))
            }
        }
    }

    val volPct = ((mediaVolume * 100f) / maxVolume.toFloat()).roundToInt().coerceIn(0, 100)
    val brightPct = (screenBrightness * 100f).roundToInt().coerceIn(5, 100)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(infoCardShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, infoCardShape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ControlCompactSliderRow(
            label = stringResource(R.string.taskbar_cc_volume),
            valueText = if (mediaVolume == 0) stringResource(R.string.taskbar_cc_muted) else "$volPct%",
            actionLabel = stringResource(if (mediaVolume == 0) R.string.taskbar_cc_unmute else R.string.taskbar_cc_mute),
            actionActive = mediaVolume == 0,
            actionAccent = OpalPalette.AccentAmber,
            onActionClick = {
                if (mediaVolume == 0) onApplyMediaVolume(lastNonZeroVolume) else onApplyMediaVolume(0)
            },
            value = mediaVolume.toFloat(),
            valueRange = 0f..maxVolume.toFloat(),
            onValueChange = { v -> onApplyMediaVolume(v.roundToInt()) },
        )

        if (!isExternalMonitor) {
            Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
            ControlCompactSliderRow(
                label = stringResource(R.string.taskbar_cc_brightness),
                valueText = if (brightnessAuto) stringResource(R.string.common_auto) else "$brightPct%",
                actionLabel = stringResource(if (brightnessAuto) R.string.common_auto_checked else R.string.common_auto),
                actionActive = brightnessAuto,
                actionAccent = OpalPalette.AccentBlue,
                onActionClick = onSetBrightnessAuto,
                value = screenBrightness,
                valueRange = 0.05f..1.0f,
                onValueChange = onChangeBrightness,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpalButton(
                label = "⏮",
                compact = true,
                filled = false,
                onClick = { ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
                modifier = Modifier.weight(0.85f),
            )
            OpalButton(
                label = stringResource(R.string.taskbar_cc_play_pause),
                compact = true,
                filled = true,
                accent = OpalPalette.AccentBlue,
                onClick = { ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
                modifier = Modifier.weight(1.5f),
            )
            OpalButton(
                label = "⏭",
                compact = true,
                filled = false,
                onClick = { ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_NEXT) },
                modifier = Modifier.weight(0.85f),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpalButton(
            label = stringResource(R.string.taskbar_cc_shot_window),
            compact = true,
            filled = false,
            enabled = focusedWindowId > 0,
            accent = OpalPalette.AccentCyan,
            onClick = {
                onDismissPopover()
                ControlCenterActions.captureFocusedWindowScreenshot(ctx, focusedWindowId)
            },
            modifier = Modifier.weight(1f),
        )
        OpalButton(
            label = stringResource(R.string.taskbar_cc_shot_desktop),
            compact = true,
            filled = false,
            accent = OpalPalette.AccentBlue,
            onClick = {
                onDismissPopover()
                Handler(Looper.getMainLooper()).postDelayed({
                    ControlCenterActions.captureDesktopScreenshot(ctx)
                }, 140L)
            },
            modifier = Modifier.weight(1f),
        )
    }

    if (showRemoteMediaSection || showAutoTrackpadSwitch) {
        RemotePeripheralsAccordionCard(
            rootEnhanced = rootEnhanced,
            showRemoteMediaSection = showRemoteMediaSection,
            showAutoTrackpadSwitch = showAutoTrackpadSwitch,
            autoShowPhoneTrackpad = autoShowPhoneTrackpad,
            expanded = showRemoteAdvanced,
            infoCardShape = infoCardShape,
            onToggleExpanded = onToggleRemoteAdvanced,
            onToggleAutoPhoneTrackpad = onToggleAutoPhoneTrackpad,
        )
    }
}

@Composable
private fun RemotePeripheralsAccordionCard(
    rootEnhanced: Boolean,
    showRemoteMediaSection: Boolean,
    showAutoTrackpadSwitch: Boolean,
    autoShowPhoneTrackpad: Boolean,
    expanded: Boolean,
    infoCardShape: RoundedCornerShape,
    onToggleExpanded: () -> Unit,
    onToggleAutoPhoneTrackpad: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val remoteMicEnabled by AppPrefs.remoteMicEnabledFlow.collectAsState()
    val remoteCameraEnabled by AppPrefs.remoteCameraEnabledFlow.collectAsState()
    val audioRouteMode by AppPrefs.audioRouteModeFlow.collectAsState()
    val splitAudioOn = audioRouteMode == BridgeProtocol.AudioRoute.SPLIT_BY_APP
    val effectiveCameraEnabled = rootEnhanced && remoteCameraEnabled
    LaunchedEffect(Unit) {
        AppPrefs.hydrateFlows(ctx)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(infoCardShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, infoCardShape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = stringResource(R.string.taskbar_cc_remote_opts),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val activeSummary = when {
                    showRemoteMediaSection && splitAudioOn -> stringResource(R.string.settings_audio_opt_split)
                    showRemoteMediaSection && (remoteMicEnabled || effectiveCameraEnabled) -> stringResource(R.string.common_enabled)
                    else -> ""
                }
                if (activeSummary.isNotEmpty()) {
                    OpalText(
                        text = activeSummary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = OpalPalette.AccentEmerald,
                    )
                }
                OpalText(
                    text = stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                    fontSize = 10.5.sp,
                    color = OpalPalette.TextSecondary,
                )
            }
        }
        if (expanded) {
            if (showAutoTrackpadSwitch) {
                Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                OpalSwitchRow(
                    title = stringResource(R.string.display_auto_trackpad_title),
                    subtitle = stringResource(R.string.taskbar_cc_auto_trackpad_sub),
                    checked = autoShowPhoneTrackpad,
                    onCheckedChange = onToggleAutoPhoneTrackpad,
                )
            }
            if (showRemoteMediaSection) {
                Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                OpalSegmentedRow(
                    options = listOf(
                        stringResource(R.string.settings_audio_opt_all),
                        stringResource(R.string.settings_audio_opt_split),
                    ),
                    selectedIndex = audioRouteMode.coerceIn(
                        BridgeProtocol.AudioRoute.ALL_TO_DEX,
                        BridgeProtocol.AudioRoute.SPLIT_BY_APP,
                    ),
                    onSelect = { mode ->
                        BridgeClient.setAudioRouteModeAsync(ctx, mode) {
                            val msg = if (mode == BridgeProtocol.AudioRoute.SPLIT_BY_APP) {
                                ctx.getString(R.string.settings_audio_toast_split)
                            } else {
                                ctx.getString(R.string.settings_audio_toast_all)
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                OpalSwitchRow(
                    title = stringResource(R.string.settings_mic_title),
                    subtitle = stringResource(R.string.settings_mic_sub),
                    checked = remoteMicEnabled,
                    onCheckedChange = { enabled ->
                        BridgeClient.setRemoteMediaConfigAsync(ctx, enabled, effectiveCameraEnabled) {
                            Toast.makeText(
                                ctx,
                                ctx.getString(if (enabled) R.string.settings_mic_toast_on else R.string.settings_mic_toast_off),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                OpalSwitchRow(
                    title = stringResource(R.string.settings_cam_title),
                    subtitle = if (!rootEnhanced) {
                        stringResource(R.string.settings_cam_sub_no_root)
                    } else {
                        stringResource(R.string.settings_cam_sub_ready)
                    },
                    checked = effectiveCameraEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !rootEnhanced) {
                            if (remoteCameraEnabled) {
                                AppPrefs.setRemoteCameraEnabled(ctx, false)
                            }
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.settings_cam_toast_no_root),
                                Toast.LENGTH_LONG,
                            ).show()
                            return@OpalSwitchRow
                        }
                        BridgeClient.setRemoteMediaConfigAsync(ctx, remoteMicEnabled, enabled) { ok ->
                            val msg = when {
                                !enabled -> ctx.getString(R.string.settings_cam_toast_off)
                                ok -> ctx.getString(R.string.settings_cam_toast_on)
                                else -> ctx.getString(R.string.settings_cam_toast_no_root)
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun ControlBentoTile(
    title: String,
    subtitle: String,
    active: Boolean,
    accent: Color,
    iconContent: @Composable (tint: Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(tileShape)
            .background(if (active) accent.copy(alpha = 0.15f) else OpalPalette.ChipFill)
            .border(
                width = 0.8.dp,
                color = if (active) accent.copy(alpha = 0.44f) else OpalPalette.BorderHairline,
                shape = tileShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (active) accent else OpalPalette.ChipActiveFill),
            contentAlignment = Alignment.Center,
        ) {
            iconContent(if (active) Color.White else OpalPalette.TextSecondary)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            OpalText(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
            )
            OpalText(
                text = subtitle,
                fontSize = 10.sp,
                color = if (active) accent else OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ControlCompactSliderRow(
    label: String,
    valueText: String,
    actionLabel: String,
    actionActive: Boolean,
    actionAccent: Color,
    onActionClick: () -> Unit,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(text = label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                OpalText(text = valueText, fontSize = 10.5.sp, color = OpalPalette.TextSecondary)
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (actionActive) actionAccent.copy(alpha = 0.16f) else OpalPalette.ChipActiveFill)
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                OpalText(
                    text = actionLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (actionActive) actionAccent else OpalPalette.TextSecondary,
                )
            }
        }
        OpalGlassSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
