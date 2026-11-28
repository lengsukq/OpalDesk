package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.NativeImeBridgeHost
import io.github.lengsukq.opaldesk.ui.NativeImeHostController
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTopPillChip
import io.github.lengsukq.opaldesk.ui.OpalTopPillDivider
import io.github.lengsukq.opaldesk.ui.OpalTopPillIconButton
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface

@SuppressLint("ClickableViewAccessibility")
@Composable
internal fun PeerCastViewerContent(
    peerName: String,
    statusText: String,
    errorText: String,
    connected: Boolean,
    videoW: Int,
    videoH: Int,
    toolbarExpanded: Boolean,
    isMirrorMode: Boolean,
    remoteBlackout: Boolean,
    remoteFullscreenTitle: String,
    micEnabled: Boolean,
    cameraEnabled: Boolean,
    cameraFacing: String,
    imeVisible: Boolean,
    composingText: String,
    imeController: NativeImeHostController,
    onToggleToolbar: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleIme: () -> Unit,
    onImeVisibilityChanged: (Boolean) -> Unit,
    onComposingChanged: (String) -> Unit,
    onSendImeKey: (Int, Int, Int) -> Unit,
    onCommitImeText: (String) -> Unit,
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onDispatchTouch: (MotionEvent, Int, Int) -> Unit,
    onDispatchGenericMotion: (MotionEvent, Int, Int) -> Boolean,
    onOpenFloatingPicker: () -> Unit,
    onRetryConnect: () -> Unit,
    onSendBack: () -> Unit,
    onSendHome: () -> Unit,
    onExitRemoteFullscreen: () -> Unit,
    onToggleDisplayMode: () -> Unit,
    onPopOutMirrorFloating: () -> Unit,
    onToggleBlackout: () -> Unit,
    onRequestIdr: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val ratio = (videoW.toFloat() / videoH.coerceAtLeast(1).toFloat()).coerceIn(0.38f, 3.0f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080D)),
        contentAlignment = Alignment.Center,
    ) {
        val containerRatio = (maxWidth.value / maxHeight.value.coerceAtLeast(1f)).coerceAtLeast(0.1f)
        val fitHeightFirst = containerRatio > ratio

        // Invisible native IME host so the receiver tablet/phone's built-in IME pops up seamlessly
        NativeImeBridgeHost(
            controller = imeController,
            onSendKey = onSendImeKey,
            onCommitText = onCommitImeText,
            onComposingChanged = onComposingChanged,
            onImeVisibilityChanged = onImeVisibilityChanged,
            modifier = Modifier.align(Alignment.BottomStart),
        )

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(ratio, matchHeightConstraintsFirst = fitHeightFirst),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceCreated(holder.surface)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                onSurfaceCreated(holder.surface)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        },
                    )
                    setOnTouchListener { v, ev ->
                        onDispatchTouch(ev, v.width, v.height)
                        true
                    }
                    setOnGenericMotionListener { v, ev ->
                        onDispatchGenericMotion(ev, v.width, v.height)
                    }
                }
            },
        )

        if (!connected) {
            val cardShape = RoundedCornerShape(22.dp)
            Column(
                modifier = Modifier
                    .liquidGlassSurface(
                        shape = cardShape,
                        fill = OpalPalette.LiquidPopoverFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 24.dp,
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OpalText(text = stringResource(R.string.peercast_connecting_to, peerName), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                OpalText(
                    text = if (errorText.isNotBlank()) errorText else statusText.ifBlank { stringResource(R.string.peercast_connecting_tls) },
                    fontSize = 12.5.sp,
                    color = if (errorText.isNotBlank()) OpalPalette.AccentDanger else OpalPalette.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (errorText.isNotBlank()) {
                        OpalButton(
                            label = stringResource(R.string.peercast_reconnect),
                            accent = OpalPalette.AccentBlue,
                            compact = true,
                            onClick = onRetryConnect,
                        )
                    }
                    OpalButton(label = stringResource(R.string.peercast_back_console), filled = errorText.isBlank(), compact = true, onClick = onDisconnect)
                }
            }
        }

        // Live IME composing preview pill just above the tablet/phone soft keyboard
        if (composingText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(bottom = 12.dp)
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidPopoverFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 16.dp,
                    )
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                OpalText(
                    text = composingText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                    maxLines = 1,
                )
            }
        }

        // Unified Top-Center Liquid Frosted Glass remote control pill
        if (toolbarExpanded) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 12.dp, end = 12.dp)
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 18.dp,
                    )
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.KEYBOARD,
                    contentDescription = stringResource(if (imeVisible) R.string.trackpad_hide_ime else R.string.taskbar_cc_ime),
                    active = imeVisible,
                    activeColor = OpalPalette.AccentBlue,
                    onClick = onToggleIme,
                )
                OpalTopPillDivider()
                if (remoteFullscreenTitle.isNotEmpty() && !isMirrorMode) {
                    OpalTopPillChip(
                        label = stringResource(R.string.taskbar_fs_exit),
                        glyph = ChromeGlyph.EXIT_FULLSCREEN,
                        filled = true,
                        activeColor = OpalPalette.AccentBlue,
                        onClick = onExitRemoteFullscreen,
                    )
                }
                OpalTopPillChip(
                    label = stringResource(R.string.peercast_floating_btn),
                    tint = OpalPalette.AccentBlue,
                    active = true,
                    activeColor = OpalPalette.AccentBlue,
                    onClick = onOpenFloatingPicker,
                )
                OpalTopPillChip(
                    label = stringResource(if (isMirrorMode) R.string.peercast_switch_desktop else R.string.picker_mirror_btn),
                    tint = if (isMirrorMode) OpalPalette.AccentCyan else OpalPalette.TextPrimary,
                    active = isMirrorMode,
                    activeColor = OpalPalette.AccentCyan,
                    onClick = onToggleDisplayMode,
                )
                if (isMirrorMode) {
                    OpalTopPillChip(
                        label = stringResource(R.string.peercast_float_mini),
                        tint = OpalPalette.AccentBlue,
                        active = true,
                        activeColor = OpalPalette.AccentBlue,
                        onClick = onPopOutMirrorFloating,
                    )
                }
                OpalTopPillChip(
                    label = stringResource(if (remoteBlackout) R.string.peercast_screen_on else R.string.peercast_screen_off),
                    active = remoteBlackout,
                    activeColor = OpalPalette.AccentAmber,
                    onClick = onToggleBlackout,
                )
                OpalTopPillDivider()
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.CHEVRON_UP,
                    contentDescription = stringResource(R.string.taskbar_fs_collapse),
                    tint = OpalPalette.TextSecondary,
                    onClick = onToggleToolbar,
                )
                OpalTopPillChip(
                    label = stringResource(R.string.peercast_disconnect),
                    danger = true,
                    onClick = onDisconnect,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 3.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 6f) onToggleToolbar()
                        }
                    }
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 12.dp,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (micEnabled) {
                    OpalTopPillIconButton(
                        glyph = ChromeGlyph.MIC,
                        contentDescription = stringResource(R.string.peercast_mute_mic),
                        active = true,
                        activeColor = OpalPalette.AccentEmerald,
                        onClick = onToggleMic,
                    )
                }
                if (cameraEnabled) {
                    OpalTopPillIconButton(
                        glyph = ChromeGlyph.CAMERA,
                        contentDescription = stringResource(R.string.peercast_stop_cam),
                        active = true,
                        activeColor = OpalPalette.AccentEmerald,
                        onClick = onToggleCamera,
                    )
                    OpalTopPillChip(
                        label = stringResource(if (cameraFacing == "back") R.string.peercast_cam_back else R.string.peercast_cam_front),
                        glyph = ChromeGlyph.CAMERA_FLIP,
                        tint = OpalPalette.AccentCyan,
                        active = true,
                        activeColor = OpalPalette.AccentCyan,
                        onClick = onFlipCamera,
                    )
                }
                if (remoteFullscreenTitle.isNotEmpty() && !isMirrorMode) {
                    OpalTopPillIconButton(
                        glyph = ChromeGlyph.EXIT_FULLSCREEN,
                        contentDescription = stringResource(R.string.taskbar_fs_exit),
                        active = true,
                        activeColor = OpalPalette.AccentBlue,
                        onClick = onExitRemoteFullscreen,
                    )
                }
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.KEYBOARD,
                    contentDescription = stringResource(if (imeVisible) R.string.trackpad_hide_ime else R.string.taskbar_cc_ime),
                    active = imeVisible,
                    activeColor = OpalPalette.AccentBlue,
                    onClick = onToggleIme,
                )
                OpalTopPillIconButton(
                    glyph = ChromeGlyph.CHEVRON_DOWN,
                    contentDescription = stringResource(R.string.peercast_expand_controls),
                    tint = OpalPalette.TextSecondary,
                    onClick = onToggleToolbar,
                )
            }
        }
    }
}
