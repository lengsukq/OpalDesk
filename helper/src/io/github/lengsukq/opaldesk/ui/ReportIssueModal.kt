package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lengsukq.opaldesk.diagnostics.DiagnosticSanitizer
import io.github.lengsukq.opaldesk.diagnostics.FeedbackQuotaSnapshot
import io.github.lengsukq.opaldesk.diagnostics.FeedbackRateLimiter
import io.github.lengsukq.opaldesk.diagnostics.ManualReportOutcome
import io.github.lengsukq.opaldesk.diagnostics.ManualReportSubmitGuard
import io.github.lengsukq.opaldesk.diagnostics.OpalLogEntry
import io.github.lengsukq.opaldesk.diagnostics.OpalSentry
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus

/**
 * Liquid Glass manual log & issue reporting modal.
 * Collects reporter username, issue description, device/OS/app metadata, and recent
 * sanitized+gzipped logs (never screenshots), enforcing 5 confirmed uploads per local day.
 */
@Composable
fun ReportIssueModal(
    appVersion: String,
    status: DaemonStatus,
    scene: CompositorScene,
    remote: RemoteStreamStatus,
    helperEntries: List<OpalLogEntry>,
    daemonLogText: String,
    quota: FeedbackQuotaSnapshot,
    onQuotaChanged: (FeedbackQuotaSnapshot) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var reporterName by remember { mutableStateOf(AppPrefs.getFeedbackUsername(ctx)) }
    var problemDescription by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf("") }
    var lastOutcome by remember { mutableStateOf<ManualReportOutcome?>(null) }
    var lastSubmittedDescription by remember { mutableStateOf<String?>(null) }
    val sentryConfigured = remember { OpalSentry.isConfigured() }

    val submitAllowed = ManualReportSubmitGuard.isSubmitAllowed(
        submitting = submitting,
        quotaExhausted = quota.isExhausted,
        sentryConfigured = sentryConfigured,
        lastUploadSucceeded = lastOutcome?.success == true,
        currentDescription = problemDescription,
        lastSubmittedDescription = lastSubmittedDescription,
    )
    val alreadySubmittedSameReport = lastOutcome?.success == true &&
        lastSubmittedDescription != null &&
        problemDescription.trim() == lastSubmittedDescription

    val helperCount = helperEntries.size.coerceAtMost(DiagnosticSanitizer.DEFAULT_MAX_HELPER_ENTRIES)
    val daemonCount = remember(daemonLogText) {
        daemonLogText.lineSequence().count { it.isNotBlank() }
            .coerceAtMost(DiagnosticSanitizer.DEFAULT_MAX_DAEMON_LINES)
    }

    fun submitReport() {
        if (!submitAllowed) return
        if (reporterName.trim().isEmpty()) {
            validationError = "请先填写你的用户名或称呼，便于后续定位回复"
            return
        }
        val trimmedDesc = problemDescription.trim()
        if (trimmedDesc.isEmpty()) {
            validationError = "请简单描述遇到的问题或复现步骤"
            return
        }
        validationError = ""
        submitting = true
        lastOutcome = null
        OpalSentry.submitManualReportAsync(
            ctx = ctx,
            reporterName = reporterName,
            problemDescription = trimmedDesc,
            appVersion = appVersion,
            status = status,
            scene = scene,
            remote = remote,
            helperEntries = helperEntries,
            daemonLogText = daemonLogText,
        ) { outcome ->
            submitting = false
            lastOutcome = outcome
            if (outcome.success) {
                lastSubmittedDescription = trimmedDesc
            }
            onQuotaChanged(outcome.quota)
        }
    }

    Dialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !submitting,
            dismissOnClickOutside = !submitting,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { if (!submitting) onDismiss() },
                )
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(24.dp),
                        fill = OpalPalette.LiquidPopoverFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 28.dp,
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpalText(
                        text = "上报日志与问题",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    OpalStatusBadge(
                        label = quota.remainingLabel(),
                        color = if (quota.isExhausted) OpalPalette.AccentDanger else OpalPalette.AccentCyan,
                    )
                }

                if (quota.isExhausted) {
                    StatusNoticeBox(
                        text = FeedbackRateLimiter.LIMIT_REACHED_MESSAGE,
                        accent = OpalPalette.AccentDanger,
                    )
                } else if (!sentryConfigured) {
                    StatusNoticeBox(
                        text = "当前构建未配置 Sentry DSN，提交前请先在构建配置中注入 SENTRY_DSN。",
                        accent = OpalPalette.AccentAmber,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OpalText(
                        text = "用户名 / 称呼",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextSecondary,
                    )
                    OpalTextInput(
                        value = reporterName,
                        onValueChange = {
                            reporterName = it.take(64)
                            if (validationError.isNotEmpty()) validationError = ""
                        },
                        placeholder = "例如：张三 / GitHub ID（便于定位联系）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OpalText(
                        text = "问题描述",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextSecondary,
                    )
                    OpalTextInput(
                        value = problemDescription,
                        onValueChange = {
                            problemDescription = it.take(2000)
                            if (validationError.isNotEmpty()) validationError = ""
                        },
                        placeholder = "请描述遇到的问题、触发操作或报错现象…",
                        singleLine = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                    )
                }

                // Attached diagnostic metadata summary (explicitly no screenshots)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.ChipFill)
                        .border(0.8.dp, OpalPalette.BorderHairline, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OpalText(
                        text = "本次随附诊断信息（不含屏幕截图）",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextPrimary,
                    )
                    OpalText(
                        text = "设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                    OpalText(
                        text = "版本：v$appVersion · 日志：客户端 $helperCount 条 + 守护进程 $daemonCount 行（已自动脱敏路径/Token 并 GZIP 压缩）",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                        lineHeight = 16.sp,
                    )
                }

                if (validationError.isNotEmpty()) {
                    StatusNoticeBox(
                        text = validationError,
                        accent = OpalPalette.AccentAmber,
                    )
                }

                val outcome = lastOutcome
                if (outcome != null) {
                    if (outcome.success) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(OpalPalette.AccentEmerald.copy(alpha = if (OpalPalette.isDark) 0.16f else 0.11f))
                                .border(0.9.dp, OpalPalette.AccentEmerald.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OpalText(
                                text = "上传成功，感谢反馈！请保留以下反馈码便于排错定位：",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpalPalette.AccentEmerald,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(
                                        text = "反馈码：${outcome.shortCode}",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.TextPrimary,
                                    )
                                    OpalText(
                                        text = "Event ID: ${outcome.eventId}",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.TextSecondary,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                OpalButton(
                                    label = "复制反馈码",
                                    compact = true,
                                    filled = false,
                                    accent = OpalPalette.AccentEmerald,
                                    onClick = { copyEventIdToClipboard(ctx, outcome.eventId, outcome.shortCode) },
                                )
                            }
                        }
                    } else if (outcome.errorMessage.isNotEmpty()) {
                        StatusNoticeBox(
                            text = outcome.errorMessage,
                            accent = OpalPalette.AccentDanger,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OpalButton(
                        label = if (lastOutcome?.success == true) "完成" else "取消",
                        filled = alreadySubmittedSameReport,
                        accent = if (alreadySubmittedSameReport) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                        enabled = !submitting,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = when {
                            submitting -> "上传中…"
                            alreadySubmittedSameReport -> "已上传"
                            quota.isExhausted -> "今日已达上限"
                            else -> "确认上传"
                        },
                        enabled = submitAllowed,
                        accent = OpalPalette.AccentBlue,
                        onClick = ::submitReport,
                        modifier = Modifier.weight(1.3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusNoticeBox(
    text: String,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (OpalPalette.isDark) 0.16f else 0.10f))
            .border(0.8.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        OpalText(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            lineHeight = 17.sp,
        )
    }
}

private fun copyEventIdToClipboard(ctx: Context, eventId: String, shortCode: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk Sentry Event ID", eventId))
        Toast.makeText(ctx, "已复制反馈码 $shortCode", Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
        Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show()
    }
}
