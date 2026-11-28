package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.CustomResolutionPolicy
import io.github.lengsukq.opaldesk.external.CustomResolutionSpec
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class PendingExternalRevert(
    val displayId: Int,
    val appliedWidth: Int,
    val appliedHeight: Int,
    val token: Long,
    val revertScalingMode: Int = BridgeProtocol.ExternalScaling.SMART_AUTO,
    val revertNative1To1Pref: Boolean = false,
)

/**
 * Unified output resolution & refresh-rate switcher with EDID mode selection, built-in & saved
 * presets, manual Width×Height×Hz customization, auto-apply preference, and 15-second black-screen
 * auto-revert protection for forced external monitor overrides.
 */
@Composable
fun OutputResolutionSectionContent(
    extDisplay: BridgeService.ExternalDisplayInfo?,
    remote: RemoteStreamStatus,
    compactMode: Boolean = false,
) {
    val ctx = LocalContext.current
    val savedPresets by AppPrefs.customResolutionPresetsFlow.collectAsState()
    val autoCustomEnabled by AppPrefs.autoCustomResolutionEnabledFlow.collectAsState()
    val native1To1Enabled by AppPrefs.externalNative1To1Flow.collectAsState()
    val defaultCustom by AppPrefs.defaultCustomResolutionFlow.collectAsState()

    val isConnected = extDisplay != null || remote.connected
    val curActiveW = extDisplay?.width ?: if (remote.connected) remote.width else defaultCustom.width
    val curActiveH = extDisplay?.height ?: if (remote.connected) remote.height else defaultCustom.height
    val curActiveFps = extDisplay?.refreshRateHz
        ?: if (remote.connected) (remote.fpsX100 / 100f).roundToInt().coerceAtLeast(24) else defaultCustom.fps

    var widthInput by remember { mutableStateOf(curActiveW.toString()) }
    var heightInput by remember { mutableStateOf(curActiveH.toString()) }
    var fpsInput by remember { mutableStateOf(if (curActiveFps > 0) curActiveFps.toString() else "") }
    var labelInput by remember { mutableStateOf("") }
    var userEditedInputs by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf("") }
    var editorExpanded by remember(extDisplay?.displayId, extDisplay?.supportedModes?.isEmpty(), remote.connected) {
        mutableStateOf(
            compactMode ||
                !isConnected ||
                (extDisplay != null && extDisplay.supportedModes.isEmpty()),
        )
    }

    var pendingRevert by remember { mutableStateOf<PendingExternalRevert?>(null) }
    var revertSecondsLeft by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        AppPrefs.hydrateFlows(ctx)
    }

    LaunchedEffect(
        extDisplay?.displayId,
        extDisplay?.width,
        extDisplay?.height,
        extDisplay?.refreshRateHz,
        remote.connected,
        remote.width,
        remote.height,
        remote.fpsX100,
    ) {
        if (!userEditedInputs) {
            widthInput = curActiveW.toString()
            heightInput = curActiveH.toString()
            fpsInput = if (curActiveFps > 0) curActiveFps.toString() else ""
        }
    }

    LaunchedEffect(pendingRevert?.token) {
        val guard = pendingRevert ?: return@LaunchedEffect
        for (sec in CustomResolutionPolicy.REVERT_CONFIRM_SECONDS downTo 1) {
            revertSecondsLeft = sec
            delay(1_000L)
        }
        if (pendingRevert?.token == guard.token) {
            pendingRevert = null
            revertSecondsLeft = 0
            if (guard.revertNative1To1Pref) {
                AppPrefs.setExternalNative1To1Enabled(ctx, false)
            }
            BridgeClient.setExternalDisplayModeAsync(
                guard.displayId,
                0,
                0,
                0f,
                scalingMode = guard.revertScalingMode,
            ) { _ ->
                Toast.makeText(ctx, ctx.getString(R.string.res_auto_reverted_toast), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun syncEditorFields(spec: CustomResolutionSpec) {
        widthInput = spec.width.toString()
        heightInput = spec.height.toString()
        fpsInput = if (spec.fps > 0) spec.fps.toString() else ""
        if (spec.label.isNotBlank()) labelInput = spec.label
        userEditedInputs = false
        inputError = ""
    }

    fun applyResolutionNow(spec: CustomResolutionSpec, isKnownEdidMode: Boolean = false) {
        syncEditorFields(spec)
        when {
            extDisplay != null -> {
                val targetId = extDisplay.displayId
                val matchesEdid = isKnownEdidMode || extDisplay.supportedModes.any {
                    it.width == spec.width && it.height == spec.height
                }
                val isNativeTarget = (spec.width == extDisplay.nativeWidth && spec.height == extDisplay.nativeHeight) ||
                    (spec.width == extDisplay.nativeHeight && spec.height == extDisplay.nativeWidth)
                val scalingMode = if (native1To1Enabled && isNativeTarget) {
                    BridgeProtocol.ExternalScaling.NATIVE_1TO1
                } else {
                    BridgeProtocol.ExternalScaling.SMART_AUTO
                }
                BridgeClient.setExternalDisplayModeAsync(
                    targetId,
                    spec.width,
                    spec.height,
                    spec.fps.toFloat(),
                    scalingMode = scalingMode,
                ) { ok ->
                    if (ok) {
                        if (!matchesEdid) {
                            pendingRevert = PendingExternalRevert(
                                displayId = targetId,
                                appliedWidth = spec.width,
                                appliedHeight = spec.height,
                                token = System.currentTimeMillis(),
                            )
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.res_forced_confirm_toast, spec.width, spec.height),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            pendingRevert = null
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.res_switched_ext_toast, spec.displayText),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.res_rejected_ext_toast),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            remote.connected -> {
                pendingRevert = null
                BridgeClient.setExternalDisplayModeAsync(
                    -1,
                    spec.width,
                    spec.height,
                    spec.fps.toFloat(),
                ) { ok ->
                    val msg = if (ok) ctx.getString(R.string.res_switched_cast_toast, spec.displayText) else ctx.getString(R.string.res_switch_cast_failed)
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                AppPrefs.setDefaultCustomResolution(ctx, spec)
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.res_set_default_toast, spec.displayText),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun resetToNativeNow(forceSystemAutoScaling: Boolean = false) {
        val guard = pendingRevert
        pendingRevert = null
        revertSecondsLeft = 0
        if (forceSystemAutoScaling || guard?.revertNative1To1Pref == true) {
            AppPrefs.setExternalNative1To1Enabled(ctx, false)
        }
        val scalingMode = when {
            forceSystemAutoScaling -> BridgeProtocol.ExternalScaling.FORCE_SYSTEM_AUTO
            guard != null -> guard.revertScalingMode
            native1To1Enabled -> BridgeProtocol.ExternalScaling.NATIVE_1TO1
            else -> BridgeProtocol.ExternalScaling.SMART_AUTO
        }
        when {
            extDisplay != null -> {
                BridgeClient.setExternalDisplayModeAsync(
                    extDisplay.displayId,
                    0,
                    0,
                    0f,
                    scalingMode = scalingMode,
                ) { ok ->
                    Toast.makeText(
                        ctx,
                        ctx.getString(if (ok) R.string.res_restored_native_ext else R.string.res_restore_native_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            remote.connected -> {
                BridgeClient.setExternalDisplayModeAsync(-1, 0, 0, 0f) { ok ->
                    Toast.makeText(
                        ctx,
                        ctx.getString(if (ok) R.string.res_restored_cast_negotiated else R.string.res_restore_cast_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // 1. Active Output Status + Quick Reset Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val statusLine = when {
                    extDisplay != null -> {
                        val prefix = stringResource(if (extDisplay.isWirelessDisplay) R.string.res_prefix_wireless_tv else R.string.res_prefix_wired_ext)
                        "$prefix：${extDisplay.name} · ${extDisplay.width}×${extDisplay.height} @ ${extDisplay.refreshRateHz}Hz"
                    }
                    remote.connected -> {
                        val fps = (remote.fpsX100 / 100f).roundToInt().coerceAtLeast(24)
                        "${remote.channelLabel}：${remote.peerName.ifBlank { stringResource(R.string.common_peer_device) }} · ${remote.width}×${remote.height} @ ${fps}Hz"
                    }
                    autoCustomEnabled ->
                        stringResource(R.string.res_idle_auto_apply, defaultCustom.displayText)
                    else ->
                        stringResource(R.string.res_idle_hint)
                }
                OpalText(
                    text = statusLine,
                    fontSize = 12.sp,
                    color = if (isConnected) OpalPalette.AccentCyan else OpalPalette.TextSecondary,
                )
                if (extDisplay != null && extDisplay.isCustomSizeOverride) {
                    OpalText(
                        text = stringResource(R.string.res_override_active, extDisplay.nativeWidth, extDisplay.nativeHeight),
                        fontSize = 11.sp,
                        color = OpalPalette.AccentAmber,
                    )
                }
            }
            if (isConnected) {
                Spacer(Modifier.width(8.dp))
                OpalButton(
                    label = stringResource(R.string.res_restore_native_btn),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentAmber,
                    onClick = { resetToNativeNow() },
                )
            }
        }

        // 2. 15-Second Black-Screen Auto-Revert Safety Banner
        pendingRevert?.let { guard ->
            val warnShape = RoundedCornerShape(13.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(warnShape)
                    .background(OpalPalette.AccentAmber.copy(alpha = if (OpalPalette.isDark) 0.18f else 0.11f))
                    .border(1.dp, OpalPalette.AccentAmber.copy(alpha = 0.55f), warnShape)
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OpalText(
                    text = stringResource(R.string.res_revert_prompt, guard.appliedWidth, guard.appliedHeight, revertSecondsLeft),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = OpalPalette.TextPrimary,
                    lineHeight = 16.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpalButton(
                        label = stringResource(R.string.res_keep_btn),
                        compact = true,
                        filled = true,
                        accent = OpalPalette.AccentEmerald,
                        onClick = {
                            pendingRevert = null
                            revertSecondsLeft = 0
                            Toast.makeText(ctx, ctx.getString(R.string.res_kept_toast, guard.appliedWidth, guard.appliedHeight), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = stringResource(R.string.res_revert_now_btn),
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentAmber,
                        onClick = {
                            resetToNativeNow(
                                forceSystemAutoScaling = guard.revertScalingMode == BridgeProtocol.ExternalScaling.FORCE_SYSTEM_AUTO,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 3. Recognized Monitor EDID Modes (when physical/wireless display connected)
        if (extDisplay != null) {
            EdidModesSection(
                extDisplay = extDisplay,
                onApplySpec = { spec, known -> applyResolutionNow(spec, isKnownEdidMode = known) },
            )
        }

        // 4. Saved User Custom Presets
        if (savedPresets.isNotEmpty()) {
            SavedResolutionPresetsSection(
                savedPresets = savedPresets,
                isConnected = isConnected,
                curActiveW = curActiveW,
                curActiveH = curActiveH,
                onApplySpec = { applyResolutionNow(it) },
                onRemovePreset = { key -> AppPrefs.removeCustomResolutionPreset(ctx, key) },
            )
        }

        // 5. Built-in Quick Presets (1080p / 2K / 4K / 16:10 / 21:9 / 720p / 9:16 Portrait)
        BuiltinResolutionPresetsSection(
            hasExternalDisplay = extDisplay != null,
            isConnected = isConnected,
            curActiveW = curActiveW,
            curActiveH = curActiveH,
            curActiveFps = curActiveFps,
            editorExpanded = editorExpanded,
            onToggleEditor = { editorExpanded = !editorExpanded },
            onApplySpec = { applyResolutionNow(it) },
        )

        // 6. Manual Custom Resolution Editor (Width × Height × Hz + Swap Orientation + Save Preset)
        if (editorExpanded) {
            ManualResolutionEditorCard(
                widthInput = widthInput,
                heightInput = heightInput,
                fpsInput = fpsInput,
                labelInput = labelInput,
                inputError = inputError,
                isConnected = isConnected,
                onWidthChange = { next ->
                    widthInput = next
                    userEditedInputs = true
                    inputError = ""
                },
                onHeightChange = { next ->
                    heightInput = next
                    userEditedInputs = true
                    inputError = ""
                },
                onFpsChange = { next ->
                    fpsInput = next
                    userEditedInputs = true
                    inputError = ""
                },
                onSwapOrientation = {
                    val oldW = widthInput
                    widthInput = heightInput
                    heightInput = oldW
                    userEditedInputs = true
                    inputError = ""
                },
                onApplyClick = {
                    val res = CustomResolutionPolicy.parseUserInput(widthInput, heightInput, fpsInput, labelInput)
                    if (res.spec == null) {
                        inputError = res.error
                        return@ManualResolutionEditorCard
                    }
                    AppPrefs.setDefaultCustomResolution(ctx, res.spec)
                    applyResolutionNow(res.spec)
                },
                onSavePresetClick = {
                    val res = CustomResolutionPolicy.parseUserInput(widthInput, heightInput, fpsInput, labelInput)
                    if (res.spec == null) {
                        inputError = res.error
                        return@ManualResolutionEditorCard
                    }
                    val ratio = CustomResolutionPolicy.formatAspectRatioLabel(res.spec.width, res.spec.height)
                    val labeled = if (res.spec.label.isBlank()) res.spec.copy(label = ratio) else res.spec
                    AppPrefs.addCustomResolutionPreset(ctx, labeled)
                    AppPrefs.setDefaultCustomResolution(ctx, labeled)
                    Toast.makeText(ctx, ctx.getString(R.string.res_saved_preset_toast, labeled.displayText), Toast.LENGTH_SHORT).show()
                },
            )
        }

        // 7. 1:1 Native Pixel Output (Disable OS Scaling for 4:3 / Anisotropic Portable Monitors)
        OpalSwitchRow(
            title = stringResource(R.string.res_native_1to1_title),
            subtitle = stringResource(R.string.res_native_1to1_sub),
            checked = native1To1Enabled,
            onCheckedChange = { enabled ->
                AppPrefs.setExternalNative1To1Enabled(ctx, enabled)
                if (extDisplay != null) {
                    val targetId = extDisplay.displayId
                    if (enabled) {
                        val nativeW = extDisplay.nativeWidth
                        val nativeH = extDisplay.nativeHeight
                        BridgeClient.setExternalDisplayModeAsync(
                            targetId,
                            nativeW,
                            nativeH,
                            0f,
                            scalingMode = BridgeProtocol.ExternalScaling.NATIVE_1TO1,
                        ) { ok ->
                            if (ok) {
                                pendingRevert = PendingExternalRevert(
                                    displayId = targetId,
                                    appliedWidth = nativeW,
                                    appliedHeight = nativeH,
                                    token = System.currentTimeMillis(),
                                    revertScalingMode = BridgeProtocol.ExternalScaling.FORCE_SYSTEM_AUTO,
                                    revertNative1To1Pref = true,
                                )
                                Toast.makeText(
                                    ctx,
                                    ctx.getString(R.string.res_native_1to1_confirm_toast, nativeW, nativeH),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                AppPrefs.setExternalNative1To1Enabled(ctx, false)
                                Toast.makeText(ctx, ctx.getString(R.string.res_rejected_ext_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        pendingRevert = null
                        revertSecondsLeft = 0
                        BridgeClient.setExternalDisplayModeAsync(
                            targetId,
                            0,
                            0,
                            0f,
                            scalingMode = BridgeProtocol.ExternalScaling.FORCE_SYSTEM_AUTO,
                        ) { ok ->
                            val msg = if (ok) R.string.res_native_1to1_off_toast else R.string.res_restore_native_failed
                            Toast.makeText(ctx, ctx.getString(msg), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )

        // 8. Auto-Apply Saved Custom Resolution Toggle
        OpalSwitchRow(
            title = stringResource(R.string.res_auto_apply_title),
            subtitle = stringResource(R.string.res_auto_apply_sub, defaultCustom.displayText),
            checked = autoCustomEnabled,
            onCheckedChange = { enabled ->
                AppPrefs.setAutoCustomResolutionEnabled(ctx, enabled)
            },
        )
    }
}

/**
 * Centered Liquid Glass Custom Resolution floating window inside DeX Desktop.
 */
@Composable
fun DexCustomResolutionWindow(
    extDisplay: BridgeService.ExternalDisplayInfo?,
    remote: RemoteStreamStatus,
    maxHeight: Dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .widthIn(max = 580.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.SolidPopoverFill,
                rim = OpalPalette.SolidPopoverRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                OpalText(text = stringResource(R.string.display_res_section_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                OpalText(
                    text = stringResource(R.string.display_res_section_sub),
                    fontSize = 11.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            OpalButton(
                label = stringResource(R.string.common_close),
                compact = true,
                filled = false,
                onClick = onDismiss,
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            OutputResolutionSectionContent(
                extDisplay = extDisplay,
                remote = remote,
                compactMode = true,
            )
        }
    }
}
