package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.media.AudioManager
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
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassSlider
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface

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
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
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
