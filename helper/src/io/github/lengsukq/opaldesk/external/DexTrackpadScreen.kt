package io.github.lengsukq.opaldesk.external

import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
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

private val CTRL_META = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

/**
 * Samsung DeX-style Display 0 Precision Trackpad & External Monitor Control Deck.
 *
 * Shown on the phone/tablet screen while [ExternalMonitorPresentation] renders the full-bleed
 * desktop natively on the connected USB-C / HDMI monitor (e.g. 2560×1440 @ 100Hz).
 * Touch surface & bottom hardware-style click deck live in [DexTrackpadDeckAndSurface.kt].
 */
@Composable
fun DexTrackpadScreen(
    monitorSummary: String,
    externalDisplayId: Int,
    cursorX: Int,
    cursorY: Int,
    badgePrefix: String = "",
    allowSwitchToLocalDesktop: Boolean = true,
    exitButtonLabel: String = "",
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
    val resolvedBadgePrefix = badgePrefix.ifBlank { stringResource(R.string.trackpad_badge_default) }
    val resolvedExitLabel = exitButtonLabel.ifBlank { stringResource(R.string.trackpad_exit_default) }
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
                OpalText(text = stringResource(R.string.trackpad_wake_toolbar), fontSize = 11.5.sp, color = Color(0xFFCBD5E1))
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpalStatusBadge(
                    label = "$resolvedBadgePrefix · $monitorSummary",
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
                    label = stringResource(if (imeBarVisible) R.string.trackpad_hide_ime else R.string.trackpad_show_ime),
                    compact = true,
                    filled = imeBarVisible,
                    onClick = { imeBarVisible = !imeBarVisible },
                )
                OpalButton(
                    label = stringResource(R.string.trackpad_dim_mode),
                    compact = true,
                    filled = false,
                    onClick = { oledDimMode = true },
                )
                OpalButton(
                    label = stringResource(R.string.taskbar_cc_console),
                    compact = true,
                    filled = false,
                    onClick = onOpenConsole,
                )
                if (allowSwitchToLocalDesktop) {
                    OpalButton(
                        label = stringResource(R.string.trackpad_switch_local),
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentCyan,
                        onClick = onSwitchToLocalDesktop,
                    )
                }
                OpalButton(
                    label = resolvedExitLabel,
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentDanger,
                    onClick = onExitExternal,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlineOptionStrip(
                    title = stringResource(R.string.trackpad_scale),
                    options = scaleOptions,
                    selectedValue = uiScale,
                    onSelect = { next -> AppPrefs.setExternalUiScale(ctx, next) },
                )
                if (showOverscanControl) {
                    InlineOptionStrip(
                        title = stringResource(if (isWirelessDisplay) R.string.trackpad_tv_overscan else R.string.trackpad_overscan),
                        options = overscanOptions,
                        selectedValue = overscanPct,
                        onSelect = { next -> AppPrefs.setExternalOverscanPct(ctx, next) },
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
                        text = if (status.connectedMouseCount > 0) stringResource(R.string.trackpad_ext_mouse_count, status.connectedMouseCount) else stringResource(R.string.trackpad_ext_mouse),
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                    listOf(
                        AppPrefs.MOUSE_TARGET_LOCAL to stringResource(R.string.trackpad_target_local),
                        AppPrefs.MOUSE_TARGET_EXTERNAL to stringResource(R.string.trackpad_target_cast),
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
                        text = stringResource(if (mouseOnExternal) R.string.trackpad_pointer_speed else R.string.trackpad_touch_speed),
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

            if (imeBarVisible) {
                TrackpadImeBar(
                    imeDraft = imeDraft,
                    onDraftChange = { imeDraft = it },
                    onCommitText = {
                        onCommitTextToFocusedWindow(imeDraft)
                        imeDraft = ""
                    },
                    onSendKeyTap = onSendKeyTap,
                )
            }

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
                        text = stringResource(R.string.trackpad_surface_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = OpalPalette.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(5.dp))
                    OpalText(
                        text = stringResource(R.string.trackpad_surface_gestures),
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = OpalPalette.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

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
private fun TrackpadImeBar(
    imeDraft: String,
    onDraftChange: (String) -> Unit,
    onCommitText: () -> Unit,
    onSendKeyTap: (Int, Int) -> Unit,
) {
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
                onValueChange = onDraftChange,
                placeholder = stringResource(R.string.trackpad_ime_placeholder),
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = stringResource(R.string.trackpad_send_to_window),
                compact = true,
                enabled = imeDraft.isNotEmpty(),
                onClick = onCommitText,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val shortcutKeys = listOf(
                Triple("Enter", KeyEvent.KEYCODE_ENTER, 0),
                Triple(stringResource(R.string.trackpad_key_backspace), KeyEvent.KEYCODE_DEL, 0),
                Triple(stringResource(R.string.trackpad_key_space), KeyEvent.KEYCODE_SPACE, 0),
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
            shortcutKeys.forEach { (label, keyCode, meta) ->
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

private fun toggleTrackpadHostScreenPower(ctx: Context, screenOn: Boolean) {
    BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
        val msg = when {
            !ok -> ctx.getString(R.string.toast_host_screen_unsupported)
            screenOn -> ctx.getString(R.string.toast_host_screen_on)
            else -> ctx.getString(R.string.toast_host_screen_off_hint)
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
