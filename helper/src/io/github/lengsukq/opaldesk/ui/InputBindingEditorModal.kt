package io.github.lengsukq.opaldesk.ui

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.input.CapturedInputEvent
import io.github.lengsukq.opaldesk.input.InputBindingAction
import io.github.lengsukq.opaldesk.input.InputBindingRule
import io.github.lengsukq.opaldesk.input.LocalInputBindingExecutor
import io.github.lengsukq.opaldesk.input.extractMouseButtonCode
import io.github.lengsukq.opaldesk.input.findConflictingBinding
import io.github.lengsukq.opaldesk.input.formatTriggerBadge
import io.github.lengsukq.opaldesk.input.getReservedShortcutWarning
import io.github.lengsukq.opaldesk.input.isModifierOnlyKeyCode
import io.github.lengsukq.opaldesk.input.normalizeAndroidMetaState
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Click-to-record modal built purely with `androidx.compose.foundation` + `OpalPrimitives.kt`
 * (no Material3). Simultaneously listens for:
 * 1. Remote USB/Wi-Fi cast input captured by daemon via `TX_INPUT_BINDING_CAPTURE` (200ms poll).
 * 2. Local physical keyboard events via `onPreviewKeyEvent`.
 * 3. Local USB/Bluetooth mouse button events (`BUTTON_SECONDARY`, `BUTTON_TERTIARY`, `BUTTON_BACK`,
 *    `BUTTON_FORWARD`, and extended bitmasks `0x20`, `0x40`, ...) via native `MotionEvent` pad.
 */
@Composable
fun InputBindingEditorModal(
    existingRules: List<InputBindingRule> = emptyList(),
    editingRule: InputBindingRule? = null,
    onDismiss: () -> Unit,
    onSave: (InputBindingRule) -> Unit,
) {
    var daemonCaptureActive by remember { mutableStateOf(false) }
    var capturedEvent by remember(editingRule?.id) {
        mutableStateOf(
            editingRule?.let { rule ->
                CapturedInputEvent(
                    triggerType = rule.triggerType,
                    code = rule.code,
                    metaMask = rule.metaMask,
                    label = formatTriggerBadge(rule.triggerType, rule.code, rule.metaMask),
                    timestampMs = 0L,
                )
            },
        )
    }
    var selectedAction by remember(editingRule?.id) {
        mutableStateOf(
            editingRule?.let { InputBindingAction.fromId(it.actionId) } ?: InputBindingAction.BACK,
        )
    }
    val focusRequester = remember { FocusRequester() }
    val isEditing = editingRule != null

    fun recordCapture(ev: CapturedInputEvent, source: String) {
        if (!ev.isValid) return
        capturedEvent = ev
        LocalInputBindingExecutor.recordRecentCapturedEvent(ev, source)
        OpalLogStore.i(
            "InputBinding",
            "InputBinding CAPTURED source=$source trigger=${ev.triggerType} " +
                "code=${ev.hexCode} meta=0x${ev.metaMask.toString(16)} label=${ev.displayLabel}",
        )
    }

    val latestRecordCapture by rememberUpdatedState(::recordCapture)

    DisposableEffect(Unit) {
        BridgeClient.controlInputBindingCaptureAsync(BridgeProtocol.INPUT_CAPTURE_OP_START) { active, _ ->
            daemonCaptureActive = active
        }
        onDispose {
            BridgeClient.controlInputBindingCaptureAsync(BridgeProtocol.INPUT_CAPTURE_OP_STOP) { _, _ -> }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        while (true) {
            delay(200L)
            BridgeClient.controlInputBindingCaptureAsync(BridgeProtocol.INPUT_CAPTURE_OP_POLL) { active, ev ->
                daemonCaptureActive = active
                if (ev != null && ev.isValid) {
                    latestRecordCapture(ev, "daemon_cast")
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 430.dp)
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(22.dp),
                        fill = OpalPalette.SolidPopoverFill,
                        rim = OpalPalette.SolidPopoverRim,
                        elevation = 28.dp,
                    )
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEv ->
                        val native = keyEv.nativeKeyEvent
                        if (native.action == KeyEvent.ACTION_DOWN) {
                            val code = native.keyCode
                            if (code > 0 && !isModifierOnlyKeyCode(code)) {
                                val meta = normalizeAndroidMetaState(native.metaState)
                                val badge = formatTriggerBadge(InputBindingRule.TRIGGER_KEY, code, meta)
                                latestRecordCapture(
                                    CapturedInputEvent(
                                        triggerType = InputBindingRule.TRIGGER_KEY,
                                        code = code,
                                        metaMask = meta,
                                        label = badge,
                                        timestampMs = System.currentTimeMillis(),
                                    ),
                                    "local_key",
                                )
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OpalText(
                        text = if (isEditing) "编辑键鼠劫持映射" else "录制键鼠劫持映射",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    OpalStatusBadge(
                        label = if (daemonCaptureActive) "远程+本机监听中" else "本机监听中",
                        color = if (daemonCaptureActive) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                    )
                }

                // Capture Pad: supports both remote daemon polling & local Android MotionEvent mouse capture
                val padShape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(102.dp)
                        .clip(padShape)
                        .background(
                            if (capturedEvent != null) {
                                OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.18f else 0.10f)
                            } else {
                                OpalPalette.ChipFill
                            },
                        )
                        .border(
                            width = 1.dp,
                            color = if (capturedEvent != null) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            shape = padShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            View(ctx).apply {
                                isClickable = true
                                isFocusable = true
                                var lastBtnState = 0
                                val handleMouseBtn: (MotionEvent) -> Boolean = { ev ->
                                    val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                                        (ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)
                                    val code = extractMouseButtonCode(ev.actionButton, ev.buttonState, lastBtnState)
                                    lastBtnState = ev.buttonState
                                    if (isMouse && code > 0 && code != MotionEvent.BUTTON_PRIMARY) {
                                        val meta = normalizeAndroidMetaState(ev.metaState)
                                        val badge = formatTriggerBadge(InputBindingRule.TRIGGER_MOUSE, code, meta)
                                        latestRecordCapture(
                                            CapturedInputEvent(
                                                triggerType = InputBindingRule.TRIGGER_MOUSE,
                                                code = code,
                                                metaMask = meta,
                                                label = badge,
                                                timestampMs = System.currentTimeMillis(),
                                            ),
                                            "local_mouse",
                                        )
                                        true
                                    } else {
                                        false
                                    }
                                }
                                setOnGenericMotionListener { _, ev ->
                                    if (ev != null && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
                                        handleMouseBtn(ev)
                                    } else {
                                        if (ev != null && ev.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
                                            lastBtnState = ev.buttonState
                                        }
                                        false
                                    }
                                }
                                setOnTouchListener { _, ev ->
                                    if (ev != null && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                                        handleMouseBtn(ev)
                                    } else {
                                        if (ev != null && ev.actionMasked == MotionEvent.ACTION_UP) {
                                            lastBtnState = ev.buttonState
                                        }
                                        false
                                    }
                                }
                            }
                        },
                    )

                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val cur = capturedEvent
                        if (cur == null) {
                            OpalText(
                                text = "请在此处点击鼠标右键/中键/任意侧键，或按下键盘按键",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpalPalette.TextPrimary,
                            )
                            OpalText(
                                text = "USB / 无线投屏端点击同样会自动捕获 · 支持 0x20/0x40 等扩展键码",
                                fontSize = 11.sp,
                                color = OpalPalette.TextMuted,
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(OpalPalette.AccentBlue)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    OpalText(
                                        text = cur.displayLabel,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White,
                                    )
                                }
                                OpalText(
                                    text = "${cur.triggerType.uppercase(Locale.US)} ${cur.hexCode}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = OpalPalette.AccentBlue,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            OpalText(
                                text = "已捕获 · 再次按下可重新录制",
                                fontSize = 11.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                    }
                }

                val curEvent = capturedEvent
                val conflictingRule = curEvent?.let { ev ->
                    findConflictingBinding(
                        rules = existingRules,
                        triggerType = ev.triggerType,
                        code = ev.code,
                        metaMask = ev.metaMask,
                        excludeRuleId = editingRule?.id,
                    )
                }
                if (conflictingRule != null) {
                    val conflictAction = InputBindingAction.fromId(conflictingRule.actionId)?.labelZh
                        ?: conflictingRule.actionId
                    val noticeShape = RoundedCornerShape(10.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(noticeShape)
                            .background(OpalPalette.AccentAmber.copy(alpha = if (OpalPalette.isDark) 0.18f else 0.11f))
                            .border(0.8.dp, OpalPalette.AccentAmber.copy(alpha = 0.55f), noticeShape)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        OpalText(
                            text = "触发键冲突：已存在绑定「$conflictAction」的规则，保存时将自动替换旧规则。",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = OpalPalette.AccentAmber,
                        )
                    }
                }

                val reservedWarning = curEvent?.let { ev ->
                    getReservedShortcutWarning(ev.triggerType, ev.code, ev.metaMask, isChinese = true)
                }
                if (reservedWarning != null) {
                    val warnShape = RoundedCornerShape(10.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(warnShape)
                            .background(OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.15f else 0.08f))
                            .border(0.75.dp, OpalPalette.AccentBlue.copy(alpha = 0.35f), warnShape)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        OpalText(text = reservedWarning, fontSize = 11.sp, color = OpalPalette.TextSecondary)
                    }
                }

                OpalText(
                    text = "选择触发后的桌面动作",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InputBindingAction.entries.forEach { action ->
                        val selected = action == selectedAction
                        val rowShape = RoundedCornerShape(11.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .background(
                                    if (selected) OpalPalette.ChipActiveFill else OpalPalette.ChipFill,
                                )
                                .border(
                                    width = if (selected) 1.dp else 0.7.dp,
                                    color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderHairline,
                                    shape = rowShape,
                                )
                                .clickable { selectedAction = action }
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                OpalText(
                                    text = action.labelZh,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                                )
                                OpalText(
                                    text = "${action.labelEn} (${action.actionId})",
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = OpalPalette.TextMuted,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (selected) 5.dp else 1.5.dp,
                                        color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextMuted,
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OpalButton(
                        label = "取消",
                        filled = false,
                        accent = OpalPalette.TextSecondary,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = when {
                            conflictingRule != null -> "替换并保存"
                            isEditing -> "保存修改"
                            else -> "保存映射"
                        },
                        enabled = capturedEvent != null,
                        accent = if (conflictingRule != null) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
                        onClick = {
                            val ev = capturedEvent ?: return@OpalButton
                            val ruleId = editingRule?.id?.takeIf { it.isNotBlank() }
                                ?: "custom_${ev.triggerType}_${ev.code.toString(16)}_${ev.metaMask}_${System.currentTimeMillis()}"
                            val rule = InputBindingRule(
                                id = ruleId,
                                enabled = editingRule?.enabled ?: true,
                                triggerType = ev.triggerType,
                                code = ev.code,
                                metaMask = ev.metaMask,
                                actionId = selectedAction.actionId,
                                label = "${ev.displayLabel} -> ${selectedAction.labelEn}",
                            )
                            onSave(rule)
                        },
                        modifier = Modifier.weight(1.3f),
                    )
                }
            }
        }
    }
}
