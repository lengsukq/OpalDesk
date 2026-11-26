package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.external.CustomResolutionPolicy
import io.github.lengsukq.opaldesk.external.CustomResolutionSpec
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private data class PendingExternalRevert(
    val displayId: Int,
    val appliedWidth: Int,
    val appliedHeight: Int,
    val token: Long,
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
            BridgeClient.setExternalDisplayModeAsync(guard.displayId, 0, 0, 0f) { _ ->
                Toast.makeText(ctx, "15 秒未确认，已自动恢复外接屏原生分辨率", Toast.LENGTH_LONG).show()
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
                BridgeClient.setExternalDisplayModeAsync(
                    targetId,
                    spec.width,
                    spec.height,
                    spec.fps.toFloat(),
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
                                "已强制应用自定义分辨率 ${spec.width}×${spec.height}，请在 15 秒内确认",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            pendingRevert = null
                            Toast.makeText(
                                ctx,
                                "已切换外接屏至 ${spec.displayText}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            ctx,
                            "外接屏拒绝了该分辨率（请确认多窗引擎已激活）",
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
                    val msg = if (ok) "已动态切换投屏至 ${spec.displayText}" else "切换投屏分辨率失败"
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                AppPrefs.setDefaultCustomResolution(ctx, spec)
                Toast.makeText(
                    ctx,
                    "已设为默认目标 ${spec.displayText}（连接投屏或外接屏后生效）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun resetToNativeNow() {
        pendingRevert = null
        when {
            extDisplay != null -> {
                BridgeClient.setExternalDisplayModeAsync(extDisplay.displayId, 0, 0, 0f) { ok ->
                    Toast.makeText(
                        ctx,
                        if (ok) "已恢复外接屏原生分辨率" else "恢复原生分辨率失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            remote.connected -> {
                BridgeClient.setExternalDisplayModeAsync(-1, 0, 0, 0f) { ok ->
                    Toast.makeText(
                        ctx,
                        if (ok) "已恢复对端原始协商分辨率" else "恢复原始分辨率失败",
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
                        val prefix = if (extDisplay.isWirelessDisplay) "无线电视" else "物理外接屏"
                        "$prefix：${extDisplay.name} · ${extDisplay.width}×${extDisplay.height} @ ${extDisplay.refreshRateHz}Hz"
                    }
                    remote.connected -> {
                        val fps = (remote.fpsX100 / 100f).roundToInt().coerceAtLeast(24)
                        "${remote.channelLabel}：${remote.peerName.ifBlank { "对端设备" }} · ${remote.width}×${remote.height} @ ${fps}Hz"
                    }
                    autoCustomEnabled ->
                        "未连接外接屏/投屏 · 已开启自动套用：${defaultCustom.displayText}"
                    else ->
                        "未连接外接屏或投屏 · 可提前自定义分辨率或收藏常用预设"
                }
                OpalText(
                    text = statusLine,
                    fontSize = 12.sp,
                    color = if (isConnected) OpalPalette.AccentCyan else OpalPalette.TextSecondary,
                )
                if (extDisplay != null && extDisplay.isCustomSizeOverride) {
                    OpalText(
                        text = "自定义逻辑覆写生效中（物理原生 ${extDisplay.nativeWidth}×${extDisplay.nativeHeight}）",
                        fontSize = 11.sp,
                        color = OpalPalette.AccentAmber,
                    )
                }
            }
            if (isConnected) {
                Spacer(Modifier.width(8.dp))
                OpalButton(
                    label = "恢复原生",
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
                    text = "已应用自定义分辨率 ${guard.appliedWidth}×${guard.appliedHeight}，外接屏显示是否正常？（${revertSecondsLeft}s 后自动恢复原生）",
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
                        label = "保留此分辨率",
                        compact = true,
                        filled = true,
                        accent = OpalPalette.AccentEmerald,
                        onClick = {
                            pendingRevert = null
                            revertSecondsLeft = 0
                            Toast.makeText(ctx, "已确认保留 ${guard.appliedWidth}×${guard.appliedHeight}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "立即恢复原生",
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentAmber,
                        onClick = { resetToNativeNow() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 3. Recognized Monitor EDID Modes (when physical/wireless display connected)
        if (extDisplay != null) {
            if (extDisplay.supportedModes.isNotEmpty()) {
                OpalText(
                    text = "已识别的显示器原生模式（${extDisplay.supportedModes.size} 项）",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    extDisplay.supportedModes.chunked(2).forEach { pair ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            pair.forEach { m ->
                                val isCurrent = !extDisplay.isCustomSizeOverride &&
                                    m.width == extDisplay.width &&
                                    m.height == extDisplay.height &&
                                    abs(m.refreshRateHz - extDisplay.refreshRateHz) <= 2
                                OpalButton(
                                    label = "${m.width}×${m.height} · ${m.refreshRateHz}Hz",
                                    compact = true,
                                    filled = isCurrent,
                                    accent = if (isCurrent) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                                    onClick = {
                                        applyResolutionNow(
                                            CustomResolutionSpec(m.width, m.height, m.refreshRateHz),
                                            isKnownEdidMode = true,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                OpalText(
                    text = "当前显示器未上报标准 EDID 模式列表，可直接使用下方预设或手动输入宽 × 高强制生效。",
                    fontSize = 11.5.sp,
                    color = OpalPalette.AccentAmber,
                    lineHeight = 16.sp,
                )
            }
        }

        // 4. Saved User Custom Presets
        if (savedPresets.isNotEmpty()) {
            OpalText(
                text = "我的自定义预设",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextSecondary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                savedPresets.forEach { preset ->
                    val active = isConnected && curActiveW == preset.width && curActiveH == preset.height
                    val chipShape = CircleShape
                    Row(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(if (active) OpalPalette.ChipActiveFill else OpalPalette.ChipFill)
                            .border(
                                0.8.dp,
                                if (active) OpalPalette.AccentBlue.copy(alpha = 0.7f) else OpalPalette.BorderHairline,
                                chipShape,
                            )
                            .clickable { applyResolutionNow(preset) }
                            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val title = if (preset.label.isNotBlank()) {
                            "${preset.label} (${preset.shortResolutionText})"
                        } else {
                            preset.displayText
                        }
                        OpalText(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                            maxLines = 1,
                        )
                        OpalText(
                            text = "×",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = OpalPalette.TextMuted,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    AppPrefs.removeCustomResolutionPreset(ctx, preset.key)
                                }
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        // 5. Built-in Quick Presets (1080p / 2K / 4K / 16:10 / 21:9 / 720p / 9:16 Portrait)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = if (extDisplay != null) "常用分辨率与异形比例快捷切换" else "常用投屏与异形比例预设",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextSecondary,
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(OpalPalette.ChipActiveFill)
                    .clickable { editorExpanded = !editorExpanded }
                    .padding(horizontal = 8.dp, vertical = 2.5.dp),
            ) {
                OpalText(
                    text = if (editorExpanded) "收起手动输入" else "自定义宽 × 高",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CustomResolutionPolicy.BUILTIN_PRESETS.forEach { preset ->
                val isCurrent = isConnected &&
                    curActiveW == preset.width &&
                    curActiveH == preset.height &&
                    (preset.fps <= 0 || abs(curActiveFps - preset.fps) <= 3)
                val chipShape = RoundedCornerShape(11.dp)
                Column(
                    modifier = Modifier
                        .clip(chipShape)
                        .background(if (isCurrent) OpalPalette.ChipActiveFill else OpalPalette.ChipFill)
                        .border(
                            0.8.dp,
                            if (isCurrent) OpalPalette.AccentBlue.copy(alpha = 0.75f) else OpalPalette.BorderHairline,
                            chipShape,
                        )
                        .clickable { applyResolutionNow(preset) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    OpalText(
                        text = preset.label.ifBlank { preset.shortResolutionText },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCurrent) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        maxLines = 1,
                    )
                    OpalText(
                        text = preset.displayText,
                        fontSize = 9.5.sp,
                        color = if (isCurrent) OpalPalette.AccentBlue.copy(alpha = 0.85f) else OpalPalette.TextMuted,
                        maxLines = 1,
                    )
                }
            }
        }

        // 6. Manual Custom Resolution Editor (Width × Height × Hz + Swap Orientation + Save Preset)
        if (editorExpanded) {
            val editorShape = RoundedCornerShape(14.dp)
            val parsedPreview = remember(widthInput, heightInput, fpsInput, labelInput) {
                CustomResolutionPolicy.parseUserInput(widthInput, heightInput, fpsInput, labelInput)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(editorShape)
                    .background(OpalPalette.ChipFill)
                    .border(0.75.dp, OpalPalette.BorderHairline, editorShape)
                    .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OpalText(
                        text = "手动自定义分辨率",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val previewSpec = parsedPreview.spec
                    if (previewSpec != null) {
                        val ratioTag = CustomResolutionPolicy.formatAspectRatioLabel(
                            previewSpec.width,
                            previewSpec.height,
                        )
                        OpalStatusBadge(
                            label = "$ratioTag · ${previewSpec.displayText}",
                            color = OpalPalette.AccentBlue,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        OpalText(text = "宽 (px)", fontSize = 10.sp, color = OpalPalette.TextSecondary)
                        OpalTextInput(
                            value = widthInput,
                            onValueChange = { raw ->
                                widthInput = raw.filter { it.isDigit() }.take(4)
                                userEditedInputs = true
                                inputError = ""
                            },
                            placeholder = "如 2560",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .clip(CircleShape)
                            .background(OpalPalette.ChipActiveFill)
                            .clickable {
                                val oldW = widthInput
                                widthInput = heightInput
                                heightInput = oldW
                                userEditedInputs = true
                                inputError = ""
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpalText(
                            text = "⇄",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = OpalPalette.AccentBlue,
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        OpalText(text = "高 (px)", fontSize = 10.sp, color = OpalPalette.TextSecondary)
                        OpalTextInput(
                            value = heightInput,
                            onValueChange = { raw ->
                                heightInput = raw.filter { it.isDigit() }.take(4)
                                userEditedInputs = true
                                inputError = ""
                            },
                            placeholder = "如 1440",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(modifier = Modifier.weight(0.75f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        OpalText(text = "刷新率", fontSize = 10.sp, color = OpalPalette.TextSecondary)
                        OpalTextInput(
                            value = fpsInput,
                            onValueChange = { raw ->
                                fpsInput = raw.filter { it.isDigit() }.take(3)
                                userEditedInputs = true
                                inputError = ""
                            },
                            placeholder = "自动",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Quick Hz selector pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OpalText(text = "快捷帧率", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
                    listOf("" to "自动", "60" to "60Hz", "90" to "90Hz", "120" to "120Hz").forEach { (valStr, lbl) ->
                        val sel = fpsInput.trim() == valStr
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (sel) OpalPalette.ChipActiveFill else LocalOpalColors.current.inputBg)
                                .border(
                                    0.7.dp,
                                    if (sel) OpalPalette.AccentBlue.copy(alpha = 0.7f) else OpalPalette.BorderHairline,
                                    CircleShape,
                                )
                                .clickable {
                                    fpsInput = valStr
                                    userEditedInputs = true
                                    inputError = ""
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            OpalText(
                                text = lbl,
                                fontSize = 10.5.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (sel) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                            )
                        }
                    }
                }

                if (inputError.isNotBlank()) {
                    OpalText(text = inputError, fontSize = 11.sp, color = OpalPalette.AccentDanger)
                } else if (parsedPreview.adjustedNote.isNotBlank()) {
                    OpalText(text = parsedPreview.adjustedNote, fontSize = 10.5.sp, color = OpalPalette.AccentCyan)
                } else {
                    OpalText(
                        text = "支持 640×480 ～ 4096×2560（横屏/竖屏自动偶数对齐）；外接屏如 EDID 未列出该分辨率将自动使用 AOSP 强制覆写，绝不影响手机主屏。",
                        fontSize = 10.5.sp,
                        color = OpalPalette.TextMuted,
                        lineHeight = 14.5.sp,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpalButton(
                        label = if (isConnected) "立即应用" else "设为默认分辨率",
                        compact = true,
                        filled = true,
                        accent = OpalPalette.AccentBlue,
                        onClick = {
                            val res = CustomResolutionPolicy.parseUserInput(widthInput, heightInput, fpsInput, labelInput)
                            if (res.spec == null) {
                                inputError = res.error
                                return@OpalButton
                            }
                            AppPrefs.setDefaultCustomResolution(ctx, res.spec)
                            applyResolutionNow(res.spec)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "保存为预设",
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentEmerald,
                        onClick = {
                            val res = CustomResolutionPolicy.parseUserInput(widthInput, heightInput, fpsInput, labelInput)
                            if (res.spec == null) {
                                inputError = res.error
                                return@OpalButton
                            }
                            val ratio = CustomResolutionPolicy.formatAspectRatioLabel(res.spec.width, res.spec.height)
                            val labeled = if (res.spec.label.isBlank()) res.spec.copy(label = ratio) else res.spec
                            AppPrefs.addCustomResolutionPreset(ctx, labeled)
                            AppPrefs.setDefaultCustomResolution(ctx, labeled)
                            Toast.makeText(ctx, "已保存自定义预设 ${labeled.displayText}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 7. Auto-Apply Saved Custom Resolution Toggle
        OpalSwitchRow(
            title = "连接外接屏 / 投屏时自动套用自定义分辨率",
            subtitle = if (autoCustomEnabled) {
                "已开启 · 下次连接时自动应用 ${defaultCustom.displayText}"
            } else {
                "默认关闭（按外接屏 EDID / 对端屏幕自动识别）"
            },
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
                OpalText(text = "分辨率与刷新率自定义", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                OpalText(
                    text = "支持外接显示器 EDID / 强制覆写与投屏 1:1 自定义输出",
                    fontSize = 11.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            OpalButton(
                label = "关闭",
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
