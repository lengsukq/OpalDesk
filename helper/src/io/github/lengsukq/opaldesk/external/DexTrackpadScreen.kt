package io.github.lengsukq.opaldesk.external

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalAmbientBackdrop
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassSlider
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTextInput
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

private val CTRL_META = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
private val TRACKPAD_SHORTCUT_KEYS = listOf(
    Triple("Enter", KeyEvent.KEYCODE_ENTER, 0),
    Triple("退格", KeyEvent.KEYCODE_DEL, 0),
    Triple("空格", KeyEvent.KEYCODE_SPACE, 0),
    Triple("Esc", KeyEvent.KEYCODE_ESCAPE, 0),
    Triple("Tab", KeyEvent.KEYCODE_TAB, 0),
    Triple("Ctrl+A", KeyEvent.KEYCODE_A, CTRL_META),
    Triple("Ctrl+C", KeyEvent.KEYCODE_C, CTRL_META),
    Triple("Ctrl+V", KeyEvent.KEYCODE_V, CTRL_META),
    Triple("↑", KeyEvent.KEYCODE_DPAD_UP, 0),
    Triple("↓", KeyEvent.KEYCODE_DPAD_DOWN, 0),
    Triple("←", KeyEvent.KEYCODE_DPAD_LEFT, 0),
    Triple("→", KeyEvent.KEYCODE_DPAD_RIGHT, 0),
    Triple("Home", KeyEvent.KEYCODE_HOME, 0),
)

/**
 * Samsung DeX-style Display 0 Precision Trackpad & External Monitor Control Deck.
 *
 * Shown on the phone/tablet screen while [ExternalMonitorPresentation] renders the full-bleed
 * desktop natively on the connected USB-C / HDMI monitor (e.g. 2560×1440 @ 100Hz).
 */
@Composable
fun DexTrackpadScreen(
    monitorSummary: String,
    externalDisplayId: Int,
    cursorX: Int,
    cursorY: Int,
    badgePrefix: String = "独立满屏",
    allowSwitchToLocalDesktop: Boolean = true,
    exitButtonLabel: String = "退出投射",
    showOverscanControl: Boolean = false,
    isWirelessDisplay: Boolean = false,
    onMoveCursor: (Float, Float) -> Unit,
    onSetPrimaryButton: (Boolean) -> Unit,
    onTapPrimary: () -> Unit,
    onScroll: (Float, Float) -> Unit,
    onSecondaryAction: () -> Unit,
    onSendKeyTap: (Int, Int) -> Unit,
    onCommitTextToFocusedWindow: (String) -> Unit,
    onSwitchToLocalDesktop: () -> Unit,
    onOpenConsole: () -> Unit,
    onExitExternal: () -> Unit,
) {
    val ctx = LocalContext.current
    val status by BridgeClient.statusFlow.collectAsState()
    val uiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val overscanPct by AppPrefs.externalOverscanPctFlow.collectAsState()
    val sensitivity by AppPrefs.trackpadSensitivityFlow.collectAsState()
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val mouseTarget by AppPrefs.mouseTargetFlow.collectAsState()
    var oledDimMode by remember { mutableStateOf(false) }
    var leftButtonHeld by remember { mutableStateOf(false) }
    var rightButtonHeld by remember { mutableStateOf(false) }
    var dragLockToggled by remember { mutableStateOf(false) }
    var imeBarVisible by remember { mutableStateOf(false) }
    var imeDraft by remember { mutableStateOf("") }

    LaunchedEffect(status.connected, status.hostScreenOff) {
        if (status.connected && status.hostScreenOff) {
            while (true) {
                delay(2_000L)
                withContext(Dispatchers.IO) {
                    BridgeClient.refreshStatus()
                }
            }
        }
    }

    val scaleOptions = listOf(
        1.0f to "100%",
        1.25f to "125%",
        1.5f to "150%",
        1.75f to "175%",
        2.0f to "200%",
    )
    val overscanOptions = listOf(
        0f to "0%",
        2f to "2%",
        3.5f to "3.5%",
        5f to "5%",
    )

    if (oledDimMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            TrackpadTouchSurface(
                sensitivity = sensitivity,
                onMoveCursor = onMoveCursor,
                onSetPrimaryButton = onSetPrimaryButton,
                onTapPrimary = onTapPrimary,
                onScroll = onScroll,
                onSecondaryAction = onSecondaryAction,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 10.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(0.6.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 6f) oledDimMode = false
                        }
                    }
                    .clickable { oledDimMode = false }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                OpalText(text = "点亮控制栏", fontSize = 11.5.sp, color = Color(0xFFCBD5E1))
            }
        }
        return
    }

    OpalAmbientBackdrop(
        modifier = Modifier.fillMaxSize(),
        wallpaperPreset = wallpaperPreset,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Top Control Bar (horizontally scrollable for compact portrait phones)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpalStatusBadge(
                    label = "$badgePrefix · $monitorSummary",
                    color = OpalPalette.AccentEmerald,
                )
                OpalText(
                    text = "($cursorX, $cursorY)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.TextMuted,
                    maxLines = 1,
                )
                OpalButton(
                    label = if (imeBarVisible) "收起键盘" else "键盘输入",
                    compact = true,
                    filled = imeBarVisible,
                    onClick = { imeBarVisible = !imeBarVisible },
                )
                OpalButton(
                    label = "暗屏触控",
                    compact = true,
                    filled = false,
                    onClick = { oledDimMode = true },
                )
                OpalButton(
                    label = "主控制台",
                    compact = true,
                    filled = false,
                    onClick = onOpenConsole,
                )
                if (allowSwitchToLocalDesktop) {
                    OpalButton(
                        label = "切回本机",
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentCyan,
                        onClick = onSwitchToLocalDesktop,
                    )
                }
                OpalButton(
                    label = exitButtonLabel,
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentDanger,
                    onClick = onExitExternal,
                )
            }

            // External Display Scaling + External Keyboard/Mouse Target + Stepless Pointer Speed (portrait-safe)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlineOptionStrip(
                    title = "缩放",
                    options = scaleOptions,
                    selectedValue = uiScale,
                    onSelect = { next ->
                        AppPrefs.setExternalUiScale(ctx, next)
                    },
                )
                if (showOverscanControl) {
                    InlineOptionStrip(
                        title = if (isWirelessDisplay) "电视边距" else "边距",
                        options = overscanOptions,
                        selectedValue = overscanPct,
                        onSelect = { next ->
                            AppPrefs.setExternalOverscanPct(ctx, next)
                        },
                    )
                }
                val mouseOnExternal = mouseTarget == AppPrefs.MOUSE_TARGET_EXTERNAL
                val mousePillShape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier
                        .clip(mousePillShape)
                        .background(OpalPalette.LiquidDockFill)
                        .border(0.9.dp, OpalPalette.LiquidGlassRim, mousePillShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    OpalText(
                        text = if (status.connectedMouseCount > 0) "外接键鼠(${status.connectedMouseCount})" else "外接键鼠",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                    listOf(
                        AppPrefs.MOUSE_TARGET_LOCAL to "本机",
                        AppPrefs.MOUSE_TARGET_EXTERNAL to "投屏端",
                    ).forEach { (mode, lbl) ->
                        val active = mouseTarget == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (active) {
                                        if (mode == AppPrefs.MOUSE_TARGET_EXTERNAL) OpalPalette.AccentEmerald else OpalPalette.AccentBlue
                                    } else {
                                        OpalPalette.ChipFill
                                    },
                                )
                                .clickable {
                                    AppPrefs.setMouseTarget(ctx, mode)
                                    BridgeClient.setMouseTargetAsync(mode, externalDisplayId)
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            OpalText(
                                text = lbl,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) Color.White else OpalPalette.TextSecondary,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .width(230.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.LiquidDockFill)
                        .border(0.9.dp, OpalPalette.LiquidGlassRim, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OpalText(
                        text = if (mouseOnExternal) "指针速度" else "触控速度",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                        modifier = Modifier.clickable {
                            AppPrefs.setTrackpadSensitivity(ctx, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                            BridgeClient.setPointerSpeedAsync(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                        },
                    )
                    OpalGlassSlider(
                        value = sensitivity,
                        valueRange = AppPrefs.MIN_TRACKPAD_SENSITIVITY..AppPrefs.MAX_TRACKPAD_SENSITIVITY,
                        onValueChange = { next ->
                            AppPrefs.setTrackpadSensitivity(ctx, next)
                            BridgeClient.setPointerSpeedAsync(next)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OpalText(
                        text = String.format(Locale.US, "%.2f×", sensitivity),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = OpalPalette.AccentBlue,
                    )
                }
            }

            // Collapsible On-Screen IME Input & Desktop Shortcut Key Bar
            if (imeBarVisible) {
                val imeShape = RoundedCornerShape(16.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(imeShape)
                        .background(OpalPalette.LiquidDockFill)
                        .border(0.9.dp, OpalPalette.LiquidGlassRim, imeShape)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OpalTextInput(
                            value = imeDraft,
                            onValueChange = { imeDraft = it },
                            placeholder = "输入中英文后点击发送至外接屏当前窗口…",
                            modifier = Modifier.weight(1f),
                        )
                        OpalButton(
                            label = "发送到窗口",
                            compact = true,
                            enabled = imeDraft.isNotEmpty(),
                            onClick = {
                                onCommitTextToFocusedWindow(imeDraft)
                                imeDraft = ""
                            },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TRACKPAD_SHORTCUT_KEYS.forEach { (label, keyCode, meta) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(OpalPalette.ChipFill)
                                    .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(8.dp))
                                    .clickable { onSendKeyTap(keyCode, meta) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                OpalText(text = label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            // Main Liquid Frosted Glass Magic Trackpad Surface
            val padShape = RoundedCornerShape(24.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .liquidGlassSurface(
                        shape = padShape,
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 16.dp,
                    ),
            ) {
                TrackpadTouchSurface(
                    sensitivity = sensitivity,
                    onMoveCursor = onMoveCursor,
                    onSetPrimaryButton = { pressed ->
                        if (!dragLockToggled) onSetPrimaryButton(pressed)
                    },
                    onTapPrimary = {
                        if (dragLockToggled) {
                            dragLockToggled = false
                            onSetPrimaryButton(false)
                        } else {
                            onTapPrimary()
                        }
                    },
                    onScroll = onScroll,
                    onSecondaryAction = onSecondaryAction,
                    modifier = Modifier.fillMaxSize(),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.EXTERNAL_MONITOR,
                        tint = OpalPalette.TextMuted.copy(alpha = 0.36f),
                        size = 36.dp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OpalText(
                        text = "DeX 精密触控板 · 支持蓝牙/USB 键鼠直连",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = OpalPalette.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(5.dp))
                    OpalText(
                        text = "单指滑动移光标 · 轻点左键 · 双击按住拖拽\n双指上下滚动 · 双指轻点返回",
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = OpalPalette.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Apple-inspired Unified Liquid Glass Trackpad Control Deck (adaptive portrait / landscape)
            TrackpadControlDeck(
                leftButtonHeld = leftButtonHeld,
                rightButtonHeld = rightButtonHeld,
                dragLockToggled = dragLockToggled,
                hostScreenOff = status.hostScreenOff,
                onLeftPressChange = { pressed ->
                    leftButtonHeld = pressed
                    if (pressed) {
                        onSetPrimaryButton(true)
                    } else if (!dragLockToggled) {
                        onSetPrimaryButton(false)
                    }
                },
                onRightPressChange = { pressed -> rightButtonHeld = pressed },
                onRightClick = onSecondaryAction,
                onToggleDragLock = {
                    val next = !dragLockToggled
                    dragLockToggled = next
                    onSetPrimaryButton(next)
                },
                onShowDesktop = {
                    BridgeClient.sceneFlow.value.visibleWindows.forEach { win ->
                        BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
                    }
                },
                onToggleHostScreenPower = {
                    toggleTrackpadHostScreenPower(ctx, screenOn = status.hostScreenOff)
                },
            )
        }
    }
}

@Composable
private fun TrackpadControlDeck(
    leftButtonHeld: Boolean,
    rightButtonHeld: Boolean,
    dragLockToggled: Boolean,
    hostScreenOff: Boolean,
    onLeftPressChange: (Boolean) -> Unit,
    onRightPressChange: (Boolean) -> Unit,
    onRightClick: () -> Unit,
    onToggleDragLock: () -> Unit,
    onShowDesktop: () -> Unit,
    onToggleHostScreenPower: () -> Unit,
) {
    val isDark = OpalPalette.isDark
    val curOnLeftPress by rememberUpdatedState(onLeftPressChange)
    val curOnRightPress by rememberUpdatedState(onRightPressChange)
    val curOnRightClick by rememberUpdatedState(onRightClick)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 560.dp
        if (compact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Row 1: Unified Hardware-Style Split Mouse Click Bar (50% Left / 50% Right)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .liquidGlassSurface(
                            shape = RoundedCornerShape(16.dp),
                            fill = OpalPalette.LiquidDockFill,
                            rim = OpalPalette.LiquidGlassRim,
                            elevation = 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackpadPrimaryHalf(
                        held = leftButtonHeld,
                        label = if (leftButtonHeld) "左键按下 · 拖动中" else "左键 · 按住拖拽",
                        onPressChange = { curOnLeftPress(it) },
                        modifier = Modifier.weight(1f),
                    )
                    TrackpadDockDivider()
                    TrackpadSecondaryHalf(
                        held = rightButtonHeld,
                        label = "右键 · 返回",
                        onPressChange = { curOnRightPress(it) },
                        onTrigger = { curOnRightClick() },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Row 2: Unified 3-Way Quick Utility Capsule (Drag Lock | Show Desktop | Quick Screen Off)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .liquidGlassSurface(
                            shape = RoundedCornerShape(14.dp),
                            fill = OpalPalette.LiquidDockFill,
                            rim = OpalPalette.LiquidGlassRim,
                            elevation = 6.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackpadUtilitySegment(
                        glyph = ChromeGlyph.LOCK,
                        label = if (dragLockToggled) "拖拽已锁" else "拖拽锁定",
                        active = dragLockToggled,
                        activeAccent = OpalPalette.AccentAmber,
                        idleIconTint = OpalPalette.TextSecondary,
                        isDark = isDark,
                        onClick = onToggleDragLock,
                        modifier = Modifier.weight(1f),
                    )
                    TrackpadDockDivider()
                    TrackpadUtilitySegment(
                        glyph = ChromeGlyph.EXPOSE,
                        label = "显示桌面",
                        active = false,
                        activeAccent = OpalPalette.AccentBlue,
                        idleIconTint = OpalPalette.TextSecondary,
                        isDark = isDark,
                        onClick = onShowDesktop,
                        modifier = Modifier.weight(1f),
                    )
                    TrackpadDockDivider()
                    TrackpadUtilitySegment(
                        glyph = ChromeGlyph.SCREEN_OFF,
                        label = if (hostScreenOff) "点亮屏幕" else "一键息屏",
                        active = hostScreenOff,
                        activeAccent = OpalPalette.AccentEmerald,
                        idleIconTint = OpalPalette.AccentEmerald,
                        isDark = isDark,
                        onClick = onToggleHostScreenPower,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            // Landscape / Wide: Single sleek unified 5-segment Liquid Glass trackpad dock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackpadPrimaryHalf(
                    held = leftButtonHeld,
                    label = if (leftButtonHeld) "左键按下中" else "左键 · 拖拽",
                    onPressChange = { curOnLeftPress(it) },
                    modifier = Modifier.weight(1.2f),
                )
                TrackpadDockDivider()
                TrackpadSecondaryHalf(
                    held = rightButtonHeld,
                    label = "右键 · 返回",
                    onPressChange = { curOnRightPress(it) },
                    onTrigger = { curOnRightClick() },
                    modifier = Modifier.weight(1.0f),
                )
                TrackpadDockDivider()
                TrackpadUtilitySegment(
                    glyph = ChromeGlyph.LOCK,
                    label = if (dragLockToggled) "拖拽已锁" else "拖拽锁定",
                    active = dragLockToggled,
                    activeAccent = OpalPalette.AccentAmber,
                    idleIconTint = OpalPalette.TextSecondary,
                    isDark = isDark,
                    onClick = onToggleDragLock,
                    modifier = Modifier.weight(0.95f),
                )
                TrackpadDockDivider()
                TrackpadUtilitySegment(
                    glyph = ChromeGlyph.EXPOSE,
                    label = "显示桌面",
                    active = false,
                    activeAccent = OpalPalette.AccentBlue,
                    idleIconTint = OpalPalette.TextSecondary,
                    isDark = isDark,
                    onClick = onShowDesktop,
                    modifier = Modifier.weight(0.9f),
                )
                TrackpadDockDivider()
                TrackpadUtilitySegment(
                    glyph = ChromeGlyph.SCREEN_OFF,
                    label = if (hostScreenOff) "点亮屏幕" else "一键息屏",
                    active = hostScreenOff,
                    activeAccent = OpalPalette.AccentEmerald,
                    idleIconTint = OpalPalette.AccentEmerald,
                    isDark = isDark,
                    onClick = onToggleHostScreenPower,
                    modifier = Modifier.weight(0.95f),
                )
            }
        }
    }
}

@Composable
private fun TrackpadPrimaryHalf(
    held: Boolean,
    label: String,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val curPress by rememberUpdatedState(onPressChange)
    val bgBrush = if (held) {
        Brush.verticalGradient(listOf(OpalPalette.AccentBlue, OpalPalette.AccentBlue.copy(alpha = 0.88f)))
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(bgBrush)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    curPress(true)
                    waitForUpOrCancellation()
                    curPress(false)
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (held) Color.White else OpalPalette.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TrackpadSecondaryHalf(
    held: Boolean,
    label: String,
    onPressChange: (Boolean) -> Unit,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val curPress by rememberUpdatedState(onPressChange)
    val curTrigger by rememberUpdatedState(onTrigger)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (held) OpalPalette.ChipActiveFill else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    curPress(true)
                    val up = waitForUpOrCancellation()
                    curPress(false)
                    if (up != null) {
                        curTrigger()
                    }
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TrackpadUtilitySegment(
    glyph: ChromeGlyph,
    label: String,
    active: Boolean,
    activeAccent: Color,
    idleIconTint: Color,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (active) activeAccent.copy(alpha = if (isDark) 0.22f else 0.14f) else Color.Transparent
    val textColor = if (active) activeAccent else OpalPalette.TextPrimary
    val iconTint = if (active) activeAccent else idleIconTint
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpalGlyphIcon(glyph = glyph, tint = iconTint, size = 14.dp)
        Spacer(Modifier.width(5.5.dp))
        OpalText(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun TrackpadDockDivider() {
    Box(
        modifier = Modifier
            .width(0.8.dp)
            .fillMaxHeight()
            .padding(vertical = 10.dp)
            .background(OpalPalette.DividerColor),
    )
}

private fun toggleTrackpadHostScreenPower(ctx: Context, screenOn: Boolean) {
    BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
        val msg = when {
            !ok -> "当前设备暂不支持物理息屏控制"
            screenOn -> "已点亮本机屏幕"
            else -> "已物理息屏（双击屏幕随时亮屏，断开投屏自动亮屏）"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun InlineOptionStrip(
    title: String,
    options: List<Pair<Float, String>>,
    selectedValue: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(OpalPalette.LiquidDockFill)
            .border(0.9.dp, OpalPalette.LiquidGlassRim, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        OpalText(text = title, fontSize = 11.5.sp, color = OpalPalette.TextSecondary)
        Spacer(Modifier.width(4.dp))
        options.forEach { (value, label) ->
            val selected = abs(selectedValue - value) < 0.08f
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) OpalPalette.AccentBlue else OpalPalette.ChipFill)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                OpalText(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) Color.White else OpalPalette.TextSecondary,
                )
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun TrackpadTouchSurface(
    sensitivity: Float,
    onMoveCursor: (Float, Float) -> Unit,
    onSetPrimaryButton: (Boolean) -> Unit,
    onTapPrimary: () -> Unit,
    onScroll: (Float, Float) -> Unit,
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val curSens by rememberUpdatedState(sensitivity)
    val curMove by rememberUpdatedState(onMoveCursor)
    val curPrimary by rememberUpdatedState(onSetPrimaryButton)
    val curTap by rememberUpdatedState(onTapPrimary)
    val curScroll by rememberUpdatedState(onScroll)
    val curSecondary by rememberUpdatedState(onSecondaryAction)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PrecisionTrackpadView(
                ctx = ctx,
                getSensitivity = { curSens },
                onMove = { dx, dy -> curMove(dx, dy) },
                onPrimaryChanged = { down -> curPrimary(down) },
                onTap = { curTap() },
                onScroll = { hx, vy -> curScroll(hx, vy) },
                onSecondary = { curSecondary() },
            )
        },
    )
}

@SuppressLint("ViewConstructor")
private class PrecisionTrackpadView(
    ctx: Context,
    private val getSensitivity: () -> Float,
    private val onMove: (Float, Float) -> Unit,
    private val onPrimaryChanged: (Boolean) -> Unit,
    private val onTap: () -> Unit,
    private val onScroll: (Float, Float) -> Unit,
    private val onSecondary: () -> Unit,
) : View(ctx) {

    private var downTimeMs = 0L
    private var lastTapUpMs = 0L
    private var maxPointers = 0
    private var totalDistance = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastHoverX = Float.NaN
    private var lastHoverY = Float.NaN
    private var dragLockActive = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        if (isMouse) return handleMouseTouch(ev)

        val masked = ev.actionMasked
        val count = ev.pointerCount
        when (masked) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()
                downTimeMs = now
                maxPointers = 1
                totalDistance = 0f
                lastX = ev.x
                lastY = ev.y
                if (now - lastTapUpMs <= 260L) {
                    dragLockActive = true
                    onPrimaryChanged(true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (count > maxPointers) maxPointers = count
                if (dragLockActive) {
                    dragLockActive = false
                    onPrimaryChanged(false)
                }
                lastX = avgX(ev)
                lastY = avgY(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = if (count >= 2) avgX(ev) else ev.x
                val cy = if (count >= 2) avgY(ev) else ev.y
                val dx = cx - lastX
                val dy = cy - lastY
                lastX = cx
                lastY = cy
                totalDistance += hypot(dx, dy)

                if (count >= 2) {
                    val scrollScale = 0.045f * getSensitivity()
                    onScroll(-dx * scrollScale, dy * scrollScale)
                } else {
                    val speed = hypot(dx, dy)
                    val accel = (1f + (speed / 28f).coerceAtMost(1.35f)) * getSensitivity()
                    onMove(dx * accel, dy * accel)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIdx = if (ev.actionIndex == 0 && count > 1) 1 else 0
                lastX = ev.getX(remainingIdx)
                lastY = ev.getY(remainingIdx)
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = SystemClock.uptimeMillis() - downTimeMs
                if (dragLockActive) {
                    dragLockActive = false
                    onPrimaryChanged(false)
                    lastTapUpMs = 0L
                } else if (elapsed <= 240L && totalDistance < 18f) {
                    if (maxPointers >= 2) {
                        onSecondary()
                    } else {
                        onTap()
                        lastTapUpMs = SystemClock.uptimeMillis()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragLockActive) {
                    dragLockActive = false
                    onPrimaryChanged(false)
                }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!lastHoverX.isNaN() && !lastHoverY.isNaN()) {
                    onMove((ev.x - lastHoverX) * getSensitivity(), (ev.y - lastHoverY) * getSensitivity())
                }
                lastHoverX = ev.x
                lastHoverY = ev.y
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                lastHoverX = Float.NaN
                lastHoverY = Float.NaN
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                onScroll(ev.getAxisValue(MotionEvent.AXIS_HSCROLL), ev.getAxisValue(MotionEvent.AXIS_VSCROLL))
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun handleMouseTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastHoverX = ev.x
                lastHoverY = ev.y
                if ((ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    onSecondary()
                } else {
                    onPrimaryChanged(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!lastHoverX.isNaN() && !lastHoverY.isNaN()) {
                    onMove((ev.x - lastHoverX) * getSensitivity(), (ev.y - lastHoverY) * getSensitivity())
                }
                lastHoverX = ev.x
                lastHoverY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onPrimaryChanged(false)
                lastHoverX = ev.x
                lastHoverY = ev.y
            }
        }
        return true
    }

    private fun avgX(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getX(i)
        return sum / ev.pointerCount.coerceAtLeast(1)
    }

    private fun avgY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount.coerceAtLeast(1)
    }
}
