package io.github.lengsukq.opaldesk.compositor

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTopPillChip
import io.github.lengsukq.opaldesk.ui.OpalTopPillDivider
import io.github.lengsukq.opaldesk.ui.OpalTopPillIconButton
import io.github.lengsukq.opaldesk.ui.WindowTrafficLightCluster
import io.github.lengsukq.opaldesk.ui.WindowTrafficLightPolicy
import io.github.lengsukq.opaldesk.ui.TilingLayoutPreviewIcon
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlinx.coroutines.delay

/**
 * Floating Liquid Frosted Glass Window Tiling Preset Popover anchored below the top Menu Bar.
 */
@Composable
fun WindowTilingPopover(
    windowCount: Int,
    onApplyLayout: (WindowTilingLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val layouts = listOf(
        WindowTilingLayout.SPLIT_2_COLS,
        WindowTilingLayout.SPLIT_2_MAIN_SIDE,
        WindowTilingLayout.SPLIT_3_COLS,
        WindowTilingLayout.SPLIT_3_MAIN_STACK,
        WindowTilingLayout.SPLIT_4_COLS,
        WindowTilingLayout.SPLIT_4_GRID,
        WindowTilingLayout.SPLIT_5_COLS,
        WindowTilingLayout.SPLIT_6_GRID,
        WindowTilingLayout.SPLIT_8_GRID,
        WindowTilingLayout.CASCADE,
    )
    Column(
        modifier = modifier
            .width(344.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 26.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = stringResource(R.string.taskbar_tiling_title), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            OpalText(
                text = if (windowCount > 0) {
                    stringResource(R.string.taskbar_tiling_sub_count, windowCount)
                } else {
                    stringResource(R.string.taskbar_tiling_sub_empty)
                },
                fontSize = 11.sp,
                color = if (windowCount > 0) OpalPalette.AccentCyan else OpalPalette.TextMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            layouts.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { layout ->
                        TilingPresetTile(
                            layout = layout,
                            enabled = windowCount > 0,
                            onClick = { onApplyLayout(layout) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TilingPresetTile(
    layout: WindowTilingLayout,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(tileShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, tileShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TilingLayoutPreviewIcon(
            layout = layout,
            tint = if (enabled) OpalPalette.AccentBlue else OpalPalette.TextMuted,
            size = 22.dp,
        )
        OpalText(
            text = layout.shortLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) OpalPalette.TextPrimary else OpalPalette.TextMuted,
            maxLines = 1,
        )
    }
}

/**
 * Floating Rounded Liquid Frosted Glass Context Menu when right-clicking or long-pressing
 * any app in the App Drawer, Desktop Shortcuts, or Bottom Dock.
 */
@Composable
fun TaskbarContextMenuPopover(
    pkg: String,
    label: String,
    window: CompositorWindow?,
    isOnDesktop: Boolean,
    isPinnedToDock: Boolean,
    dockPinnedCount: Int,
    onFocusOrLaunch: () -> Unit,
    onSnapLeft: () -> Unit,
    onSnapRight: () -> Unit,
    onMaximize: () -> Unit,
    onEditAppDpi: () -> Unit = {},
    onToggleDesktopShortcut: () -> Unit,
    onToggleDockPin: () -> Unit,
    onCloseWindow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val profileState by ProfileConfigStore.stateFlow.collectAsState()
    val cropDp = profileState.workingConfig.appTopCropDps[pkg] ?: 0
    val isCropTop = cropDp > 0
    val shape = RoundedCornerShape(18.dp)
    val dockFull = !isPinnedToDock && dockPinnedCount >= AppPrefs.MAX_DOCK_PINNED
    Column(
        modifier = modifier
            .width(244.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 24.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(7.dp),
    ) {
        OpalText(
            text = label.ifBlank { pkg },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
        ContextMenuRow(stringResource(if (window != null) R.string.taskbar_ctx_focus else R.string.taskbar_ctx_launch)) {
            onFocusOrLaunch()
            onDismiss()
        }
        ContextMenuRow(stringResource(R.string.taskbar_ctx_custom_dpi), textColor = OpalPalette.AccentBlue) {
            onDismiss()
            onEditAppDpi()
        }
        ContextMenuRow(
            title = if (isCropTop) {
                stringResource(R.string.taskbar_ctx_crop_top_on, cropDp)
            } else {
                stringResource(R.string.taskbar_ctx_crop_top)
            },
            textColor = if (isCropTop) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
        ) {
            val enabled = ProfileConfigStore.toggleWorkingAppTopCrop(pkg)
            val displayLabel = label.ifBlank { pkg }
            Toast.makeText(
                ctx,
                ctx.getString(
                    if (enabled) R.string.taskbar_ctx_crop_toast_on else R.string.taskbar_ctx_crop_toast_off,
                    displayLabel,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
        if (window != null) {
            ContextMenuRow(stringResource(R.string.taskbar_ctx_snap_left)) {
                onSnapLeft()
                onDismiss()
            }
            ContextMenuRow(stringResource(R.string.taskbar_ctx_snap_right)) {
                onSnapRight()
                onDismiss()
            }
            ContextMenuRow(stringResource(if (window.isMaximized) R.string.taskbar_ctx_restore_size else R.string.taskbar_ctx_maximize)) {
                onMaximize()
                onDismiss()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .height(0.8.dp)
                .background(OpalPalette.DividerColor),
        )
        ContextMenuRow(stringResource(if (isOnDesktop) R.string.taskbar_ctx_unpin_desktop else R.string.taskbar_ctx_pin_desktop)) {
            onToggleDesktopShortcut()
            onDismiss()
        }
        ContextMenuRow(
            title = when {
                isPinnedToDock -> stringResource(R.string.taskbar_ctx_unpin_dock, dockPinnedCount, AppPrefs.MAX_DOCK_PINNED)
                dockFull -> stringResource(R.string.taskbar_ctx_dock_full, AppPrefs.MAX_DOCK_PINNED, AppPrefs.MAX_DOCK_PINNED)
                else -> stringResource(R.string.taskbar_ctx_pin_dock, dockPinnedCount, AppPrefs.MAX_DOCK_PINNED)
            },
            textColor = if (dockFull) OpalPalette.TextMuted else OpalPalette.TextPrimary,
        ) {
            onToggleDockPin()
            onDismiss()
        }
        if (window != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp)
                    .height(0.8.dp)
                    .background(OpalPalette.DividerColor),
            )
            ContextMenuRow(stringResource(R.string.taskbar_ctx_close_win), textColor = OpalPalette.AccentDanger) {
                onCloseWindow()
                onDismiss()
            }
        }
    }
}

/**
 * macOS-style Desktop Wallpaper Right-Click / Long-Press Context Menu allowing free custom
 * gallery wallpaper selection, preset switching, quick tiling, and appearance toggling.
 */
@Composable
fun DesktopWallpaperContextMenu(
    wallpaperPreset: Int,
    hasCustomWallpaper: Boolean,
    showPhoneTrackpadAction: Boolean = false,
    phoneTrackpadVisible: Boolean = false,
    showDesktopGuideIcon: Boolean = true,
    isWidgetEditMode: Boolean = false,
    onOpenWidgetStore: () -> Unit = {},
    onOpenDexSettings: () -> Unit = {},
    onToggleWidgetEditMode: () -> Unit = {},
    onOpenSpotlight: () -> Unit = {},
    onOpenControlCenter: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onTogglePhoneTrackpad: () -> Unit = {},
    onSelectPreset: (Int) -> Unit,
    onPickCustomWallpaper: () -> Unit,
    onClearCustomWallpaper: () -> Unit,
    onOpenTilingMenu: () -> Unit,
    onOpenDockSettings: () -> Unit = {},
    onOpenAppDrawer: () -> Unit,
    onOpenFeatureGuide: () -> Unit = {},
    onRestoreDesktopGuideIcon: () -> Unit = {},
    onToggleLightDark: () -> Unit,
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val isZh = remember(ctx) {
        val lang = runCatching {
            ctx.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language
        }.getOrNull() ?: java.util.Locale.getDefault().language
        lang.equals("zh", ignoreCase = true)
    }
    val isDark = OpalPalette.isDark
    val presets = if (isDark) {
        listOf(
            Color(0xFF142842) to Color(0xFF2A9D8F),
            Color(0xFF2B2752) to Color(0xFFE07A5F),
            Color(0xFF0E293D) to Color(0xFF38BDF8),
            Color(0xFF1C1F26) to Color(0xFF4B5563),
        )
    } else {
        listOf(
            Color(0xFFC8DEFB) to Color(0xFF60A5FA),
            Color(0xFFFBDCE8) to Color(0xFFF472B6),
            Color(0xFFBFE7F4) to Color(0xFF38BDF8),
            Color(0xFFE9EEF6) to Color(0xFFCBD5E1),
        )
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .width(248.dp)
            .heightIn(max = 560.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 24.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        OpalText(
            text = stringResource(R.string.taskbar_desktop_menu_title),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            presets.forEachIndexed { idx, (c1, c2) ->
                val selected = !hasCustomWallpaper && idx == wallpaperPreset
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(c1, c2)))
                        .border(
                            width = if (selected) 1.8.dp else 0.7.dp,
                            color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            onSelectPreset(idx)
                            onDismiss()
                        },
                )
            }
        }
        ContextMenuRow(stringResource(R.string.taskbar_desktop_pick_wp), textColor = OpalPalette.AccentBlue) {
            onDismiss()
            onPickCustomWallpaper()
        }
        if (hasCustomWallpaper) {
            ContextMenuRow(stringResource(R.string.taskbar_desktop_reset_wp)) {
                onClearCustomWallpaper()
                onDismiss()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .height(0.8.dp)
                .background(OpalPalette.DividerColor),
        )
        ContextMenuRow(
            title = if (isZh) "Spotlight 全局搜索" else "Spotlight Search",
            textColor = OpalPalette.AccentBlue,
        ) {
            onDismiss()
            onOpenSpotlight()
        }
        ContextMenuRow(
            title = if (isZh) "控制中心" else "Control Center",
        ) {
            onDismiss()
            onOpenControlCenter()
        }
        ContextMenuRow(
            title = if (isZh) "DeX 设置" else "DeX Settings",
            textColor = OpalPalette.AccentBlue,
        ) {
            onDismiss()
            onOpenDexSettings()
        }
        ContextMenuRow(
            title = if (isZh) "通知中心" else "Notification Center",
        ) {
            onDismiss()
            onOpenNotifications()
        }
        ContextMenuRow(
            title = if (isZh) "组件商店" else "Widget Store",
            textColor = OpalPalette.AccentBlue,
        ) {
            onDismiss()
            onOpenWidgetStore()
        }
        ContextMenuRow(
            title = when {
                isWidgetEditMode && isZh -> "退出组件编辑"
                isWidgetEditMode -> "Exit Widget Edit Mode"
                isZh -> "编辑桌面组件"
                else -> "Edit Desktop Widgets"
            },
        ) {
            onDismiss()
            onToggleWidgetEditMode()
        }
        ContextMenuRow(stringResource(R.string.taskbar_desktop_tiling)) {
            onDismiss()
            onOpenTilingMenu()
        }
        ContextMenuRow(stringResource(R.string.taskbar_desktop_dock)) {
            onDismiss()
            onOpenDockSettings()
        }
        ContextMenuRow(stringResource(R.string.taskbar_desktop_drawer)) {
            onDismiss()
            onOpenAppDrawer()
        }
        ContextMenuRow(stringResource(R.string.taskbar_desktop_guide)) {
            onDismiss()
            onOpenFeatureGuide()
        }
        if (!showDesktopGuideIcon) {
            ContextMenuRow(stringResource(R.string.taskbar_desktop_restore_guide), textColor = OpalPalette.AccentBlue) {
                onRestoreDesktopGuideIcon()
                onDismiss()
            }
        }
        ContextMenuRow(stringResource(if (isDark) R.string.taskbar_desktop_to_light else R.string.taskbar_desktop_to_dark)) {
            onToggleLightDark()
            onDismiss()
        }
        if (showPhoneTrackpadAction) {
            ContextMenuRow(
                title = stringResource(if (phoneTrackpadVisible) R.string.taskbar_desktop_hide_trackpad else R.string.taskbar_desktop_show_trackpad),
                textColor = OpalPalette.AccentBlue,
            ) {
                onDismiss()
                onTogglePhoneTrackpad()
            }
        }
        ContextMenuRow(stringResource(R.string.taskbar_desktop_open_console)) {
            onDismiss()
            onOpenConsole()
        }
        ContextMenuRow(
            title = if (isZh) "退出桌面模式" else "Exit Desktop",
            textColor = OpalPalette.AccentDanger,
        ) {
            onDismiss()
            onExitDesktop()
        }
    }
}

@Composable
private fun ContextMenuRow(
    title: String,
    textColor: Color = OpalPalette.TextPrimary,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        OpalText(text = title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

private const val FULLSCREEN_INITIAL_REVEAL_MS = 2_200L
private const val FULLSCREEN_AUTO_HIDE_MS = 4_500L
private const val FULLSCREEN_COLLAPSE_HOVER_GUARD_MS = 650L

/**
 * Unified Top-Center Pull-Down / Tap / Mouse-Hover Control Pill shown during True App Fullscreen mode
 * (local DeX, USB-C/HDMI external monitor, Miracast, and USB/Wireless cast desktops).
 *
 * Collapses into a minimal top-center grab handle so it never overlaps an app's top-right action bar.
 */
@Composable
fun FullscreenExitOverlay(
    windowTitle: String,
    imeActive: Boolean = false,
    onToggleSoftKeyboard: (() -> Unit)? = null,
    onCloseWindow: () -> Unit,
    onMinimizeWindow: () -> Unit,
    onExitFullscreen: () -> Unit,
    onToggleOrientation: (() -> Unit)? = null,
    onReturnToDesktop: () -> Unit,
    onOpenControlCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    var initialReveal by remember { mutableStateOf(true) }
    var isPointerHovering by remember { mutableStateOf(false) }
    var manualCollapseAtMs by remember { mutableLongStateOf(0L) }
    val swapHeaderButtons by AppPrefs.swapWindowHeaderButtonsFlow.collectAsState()

    LaunchedEffect(expanded, isPointerHovering, initialReveal) {
        if (!expanded || isPointerHovering) return@LaunchedEffect
        delay(if (initialReveal) FULLSCREEN_INITIAL_REVEAL_MS else FULLSCREEN_AUTO_HIDE_MS)
        initialReveal = false
        expanded = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        if (!expanded) {
            // Full-width top-edge mouse hover strip so moving cursor to top-left or any top edge
            // always reveals the fullscreen control bar even with TOP_BAR_DP == 0 (never traps user).
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if ((ev.type == PointerEventType.Enter || ev.type == PointerEventType.Move) &&
                                        ev.changes.any { it.type == PointerType.Mouse }
                                    ) {
                                        val elapsed = SystemClock.uptimeMillis() - manualCollapseAtMs
                                        if (WindowTrafficLightPolicy.shouldRevealFullscreenBarOnMouseHover(elapsed, FULLSCREEN_COLLAPSE_HOVER_GUARD_MS)) {
                                            initialReveal = false
                                            expanded = true
                                        }
                                    }
                                }
                            }
                        },
                )
                Box(
                    modifier = Modifier
                        .width(104.dp)
                        .height(22.dp)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                initialReveal = false
                                expanded = true
                            }
                        }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 6f) {
                                    initialReveal = false
                                    expanded = true
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if ((ev.type == PointerEventType.Enter || ev.type == PointerEventType.Move) &&
                                        ev.changes.any { it.type == PointerType.Mouse }
                                    ) {
                                        val elapsed = SystemClock.uptimeMillis() - manualCollapseAtMs
                                        if (WindowTrafficLightPolicy.shouldRevealFullscreenBarOnMouseHover(elapsed, FULLSCREEN_COLLAPSE_HOVER_GUARD_MS)) {
                                            initialReveal = false
                                            expanded = true
                                        }
                                    }
                                }
                            }
                        }
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(7.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.34f))
                            .border(0.6.dp, Color.White.copy(alpha = 0.34f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(26.dp)
                                .height(2.5.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.82f)),
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp, start = 12.dp, end = 12.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.any { it.type == PointerType.Mouse }) {
                                    when (ev.type) {
                                        PointerEventType.Enter, PointerEventType.Move -> isPointerHovering = true
                                        PointerEventType.Exit -> isPointerHovering = false
                                    }
                                }
                            }
                        }
                    }
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 20.dp,
                    )
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val renderFsTrafficLights: @Composable () -> Unit = {
                    WindowTrafficLightCluster(
                        focused = true,
                        isFullscreen = true,
                        alwaysShowSymbols = true,
                        rightAligned = swapHeaderButtons,
                        onClose = {
                            isPointerHovering = false
                            expanded = false
                            onCloseWindow()
                        },
                        onMinimize = {
                            isPointerHovering = false
                            expanded = false
                            onMinimizeWindow()
                        },
                        onToggleFullscreen = {
                            isPointerHovering = false
                            expanded = false
                            onExitFullscreen()
                        },
                    )
                }
                if (!swapHeaderButtons) {
                    renderFsTrafficLights()
                    OpalTopPillDivider()
                }
                OpalText(
                    text = windowTitle,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 128.dp),
                )
                OpalTopPillDivider()
                OpalTopPillChip(
                    label = stringResource(R.string.taskbar_fs_exit),
                    glyph = ChromeGlyph.EXIT_FULLSCREEN,
                    filled = true,
                    activeColor = OpalPalette.AccentBlue,
                    onClick = {
                        isPointerHovering = false
                        expanded = false
                        onExitFullscreen()
                    },
                )
                if (onToggleOrientation != null) {
                    OpalTopPillChip(
                        label = stringResource(R.string.taskbar_fs_rotate),
                        glyph = ChromeGlyph.ORIENTATION,
                        tint = OpalPalette.AccentEmerald,
                        active = true,
                        activeColor = OpalPalette.AccentEmerald,
                        onClick = {
                            isPointerHovering = false
                            expanded = false
                            onToggleOrientation()
                        },
                    )
                }
                OpalTopPillChip(
                    label = stringResource(R.string.taskbar_fs_desktop),
                    glyph = ChromeGlyph.HOME_HOUSE,
                    tint = OpalPalette.AccentCyan,
                    active = true,
                    activeColor = OpalPalette.AccentCyan,
                    onClick = {
                        isPointerHovering = false
                        expanded = false
                        onReturnToDesktop()
                    },
                )
                if (onToggleSoftKeyboard != null) {
                    OpalTopPillIconButton(
                        glyph = ChromeGlyph.KEYBOARD,
                        contentDescription = stringResource(R.string.taskbar_fs_ime),
                        active = imeActive,
                        activeColor = OpalPalette.AccentBlue,
                        onClick = {
                            isPointerHovering = false
                            expanded = false
                            onToggleSoftKeyboard()
                        },
                    )
                }
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.CONTROL_CENTER,
                    contentDescription = stringResource(R.string.taskbar_cc_title),
                    onClick = {
                        isPointerHovering = false
                        expanded = false
                        onOpenControlCenter()
                    },
                )
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.CHEVRON_UP,
                    contentDescription = stringResource(R.string.taskbar_fs_collapse),
                    tint = OpalPalette.TextSecondary,
                    onClick = {
                        isPointerHovering = false
                        initialReveal = false
                        manualCollapseAtMs = SystemClock.uptimeMillis()
                        expanded = false
                    },
                )
                if (swapHeaderButtons) {
                    OpalTopPillDivider()
                    renderFsTrafficLights()
                }
            }
        }
    }
}
