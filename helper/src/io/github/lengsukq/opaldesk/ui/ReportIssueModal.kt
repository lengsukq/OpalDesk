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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lengsukq.opaldesk.diagnostics.DaemonPostmortemCollector
import io.github.lengsukq.opaldesk.diagnostics.DiagnosticSanitizer
import io.github.lengsukq.opaldesk.diagnostics.FeedbackQuotaSnapshot
import io.github.lengsukq.opaldesk.diagnostics.FeedbackRateLimiter
import io.github.lengsukq.opaldesk.diagnostics.ManualReportOutcome
import io.github.lengsukq.opaldesk.diagnostics.ManualReportSubmitGuard
import io.github.lengsukq.opaldesk.diagnostics.OpalLogEntry
import io.github.lengsukq.opaldesk.diagnostics.OpalSentry
import io.github.lengsukq.opaldesk.diagnostics.PostmortemLogParser
import io.github.lengsukq.opaldesk.diagnostics.ReportVersionCheckPolicy
import io.github.lengsukq.opaldesk.diagnostics.ReportVersionDecision
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.update.AppUpdateManager
import kotlinx.coroutines.delay

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
    onOpenUpdateModal: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var reporterName by remember { mutableStateOf(AppPrefs.getFeedbackUsername(ctx)) }
    var problemDescription by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var awaitingVersionCheck by remember { mutableStateOf(false) }
    var showOutdatedConfirm by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf("") }
    var lastOutcome by remember { mutableStateOf<ManualReportOutcome?>(null) }
    var lastSubmittedDescription by remember { mutableStateOf<String?>(null) }
    val sentryConfigured = remember { OpalSentry.isConfigured() }
    val updateState by AppUpdateManager.stateFlow.collectAsState()

    val availableRelease = when (val st = updateState) {
        is AppUpdateManager.UpdateState.UpdateAvailable -> st.release
        is AppUpdateManager.UpdateState.Downloading -> st.release
        is AppUpdateManager.UpdateState.ReadyToInstall -> st.release
        else -> null
    }
    val isCheckingVersion = updateState is AppUpdateManager.UpdateState.Checking
    val isBusy = submitting || awaitingVersionCheck

    // Silently trigger post-mortem daemon log harvest when disconnected & check for latest release.
    LaunchedEffect(Unit) {
        if (!status.connected && !daemonLogText.contains(PostmortemLogParser.POSTMORTEM_PREFIX)) {
            DaemonPostmortemCollector.collectIfDisconnectedAsync(
                ctx = ctx,
                triggerReason = "report_modal",
            )
        }
        val st = AppUpdateManager.stateFlow.value
        val hasKnownUpdate = st is AppUpdateManager.UpdateState.UpdateAvailable ||
            st is AppUpdateManager.UpdateState.Downloading ||
            st is AppUpdateManager.UpdateState.ReadyToInstall
        val checkedAtMs = (st as? AppUpdateManager.UpdateState.UpToDate)?.checkedAtMs
        if (ReportVersionCheckPolicy.shouldTriggerPreCheck(
                hasKnownUpdate = hasKnownUpdate,
                isChecking = st is AppUpdateManager.UpdateState.Checking,
                upToDateCheckedAtMs = checkedAtMs,
                nowMs = System.currentTimeMillis(),
            )
        ) {
            AppUpdateManager.checkForUpdates(ctx, userInitiated = false, bypassIgnoredTag = true)
        }
    }

    val submitAllowed = ManualReportSubmitGuard.isSubmitAllowed(
        submitting = isBusy,
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

    fun performUploadNow() {
        val trimmedDesc = problemDescription.trim()
        if (reporterName.trim().isEmpty() || trimmedDesc.isEmpty()) return
        validationError = ""
        showOutdatedConfirm = false
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

    // If user clicked Upload while pre-check was still running, settle quickly (or fail open after 3.5s).
    LaunchedEffect(awaitingVersionCheck, updateState) {
        if (!awaitingVersionCheck) return@LaunchedEffect
        when (updateState) {
            is AppUpdateManager.UpdateState.Checking -> {
                delay(ReportVersionCheckPolicy.SUBMIT_WAIT_TIMEOUT_MS)
                if (awaitingVersionCheck) {
                    awaitingVersionCheck = false
                    performUploadNow()
                }
            }
            is AppUpdateManager.UpdateState.UpdateAvailable,
            is AppUpdateManager.UpdateState.Downloading,
            is AppUpdateManager.UpdateState.ReadyToInstall,
            -> {
                awaitingVersionCheck = false
                showOutdatedConfirm = true
            }
            else -> {
                awaitingVersionCheck = false
                performUploadNow()
            }
        }
    }

    fun submitReport(forceOutdated: Boolean = false) {
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
        when (
            ReportVersionCheckPolicy.decideOnSubmit(
                hasKnownUpdate = availableRelease != null,
                isChecking = isCheckingVersion,
                confirmedOutdatedUpload = forceOutdated,
            )
        ) {
            ReportVersionDecision.PROMPT_UPDATE_OPTIONAL -> {
                showOutdatedConfirm = true
            }
            ReportVersionDecision.WAIT_FOR_CHECK -> {
                awaitingVersionCheck = true
            }
            ReportVersionDecision.PROCEED_UPLOAD -> {
                performUploadNow()
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isBusy,
            dismissOnClickOutside = !isBusy,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { if (!isBusy) onDismiss() },
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

                if (availableRelease != null && lastOutcome?.success != true) {
                    val isDark = OpalPalette.isDark
                    val isReady = updateState is AppUpdateManager.UpdateState.ReadyToInstall
                    val isDownloading = updateState is AppUpdateManager.UpdateState.Downloading
                    val accent = if (showOutdatedConfirm) OpalPalette.AccentAmber else OpalPalette.AccentBlue
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent.copy(alpha = if (isDark) 0.16f else 0.10f))
                            .border(
                                width = if (showOutdatedConfirm) 1.1.dp else 0.85.dp,
                                color = accent.copy(alpha = if (showOutdatedConfirm) 0.65f else 0.42f),
                                shape = RoundedCornerShape(14.dp),
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OpalText(
                            text = "检测到新版本 ${availableRelease.tagName}（当前 v$appVersion）",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                        OpalText(
                            text = "最新版本可能已修复你遇到的问题，建议先更新体验；如果不方便更新，也可以直接上传当前版本日志。",
                            fontSize = 11.5.sp,
                            color = OpalPalette.TextPrimary,
                            lineHeight = 16.5.sp,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OpalButton(
                                label = when {
                                    isReady -> "立即安装 ${availableRelease.tagName}"
                                    isDownloading -> "查看下载进度"
                                    else -> "更新到 ${availableRelease.tagName}"
                                },
                                compact = true,
                                filled = true,
                                accent = if (isReady) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                                enabled = !isBusy,
                                onClick = onOpenUpdateModal,
                                modifier = Modifier.weight(1.2f),
                            )
                            if (showOutdatedConfirm) {
                                OpalButton(
                                    label = "仍要上传日志",
                                    compact = true,
                                    filled = false,
                                    accent = OpalPalette.AccentAmber,
                                    enabled = submitAllowed,
                                    onClick = { submitReport(forceOutdated = true) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else if (isCheckingVersion && lastOutcome?.success != true) {
                    OpalText(
                        text = "正在自动检查客户端是否为最新版本…",
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
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
                                text = "上传成功！已自动保存至「日志 → 我的反馈追踪」，后续可在 APP 内查看是否修复及对应版本：",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpalPalette.AccentEmerald,
                                lineHeight = 17.sp,
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
                        enabled = !isBusy,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = when {
                            awaitingVersionCheck -> "检查版本中…"
                            submitting -> "上传中…"
                            alreadySubmittedSameReport -> "已上传"
                            quota.isExhausted -> "今日已达上限"
                            showOutdatedConfirm && availableRelease != null -> "仍要上传"
                            else -> "确认上传"
                        },
                        enabled = submitAllowed,
                        accent = if (showOutdatedConfirm && availableRelease != null) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
                        onClick = { submitReport(forceOutdated = showOutdatedConfirm) },
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
