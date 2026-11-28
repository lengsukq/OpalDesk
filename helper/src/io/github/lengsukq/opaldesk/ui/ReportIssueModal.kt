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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.diagnostics.DaemonPostmortemCollector
import io.github.lengsukq.opaldesk.diagnostics.DiagnosticSanitizer
import io.github.lengsukq.opaldesk.diagnostics.FeedbackKind
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
 * Liquid Glass manual issue, diagnostic log & feature request modal.
 * Collects reporter username, issue/feature description, optional sanitized+gzipped logs
 * (disabled by default for feature requests; never screenshots), enforcing 5 confirmed uploads per local day.
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
    initialKind: FeedbackKind = FeedbackKind.BUG,
    onQuotaChanged: (FeedbackQuotaSnapshot) -> Unit,
    onOpenUpdateModal: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var selectedKind by remember(initialKind) { mutableStateOf(initialKind) }
    var includeLogs by remember(initialKind) { mutableStateOf(initialKind == FeedbackKind.BUG) }
    var reporterName by remember { mutableStateOf(AppPrefs.getFeedbackUsername(ctx)) }
    var problemDescription by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var awaitingVersionCheck by remember { mutableStateOf(false) }
    var showOutdatedConfirm by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf("") }
    var lastOutcome by remember { mutableStateOf<ManualReportOutcome?>(null) }
    var lastSubmittedDescription by remember { mutableStateOf<String?>(null) }
    var lastSubmittedKind by remember { mutableStateOf<FeedbackKind?>(null) }
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
    val isFeature = selectedKind == FeedbackKind.FEATURE

    // Silently check for latest release when the report modal opens (bypassing ignored-version tag).
    LaunchedEffect(Unit) {
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

    // Only harvest post-mortem daemon logs when disconnected and logs are actually requested.
    LaunchedEffect(includeLogs, status.connected) {
        if (includeLogs && !status.connected && !daemonLogText.contains(PostmortemLogParser.POSTMORTEM_PREFIX)) {
            DaemonPostmortemCollector.collectIfDisconnectedAsync(
                ctx = ctx,
                triggerReason = "report_modal",
            )
        }
    }

    val submitAllowed = ManualReportSubmitGuard.isSubmitAllowed(
        submitting = isBusy,
        quotaExhausted = quota.isExhausted,
        sentryConfigured = sentryConfigured,
        lastUploadSucceeded = lastOutcome?.success == true,
        currentDescription = problemDescription,
        lastSubmittedDescription = lastSubmittedDescription,
        currentKind = selectedKind,
        lastSubmittedKind = lastSubmittedKind,
    )
    val alreadySubmittedSameReport = lastOutcome?.success == true &&
        lastSubmittedDescription != null &&
        problemDescription.trim() == lastSubmittedDescription &&
        lastSubmittedKind == selectedKind

    val helperCount = helperEntries.size.coerceAtMost(DiagnosticSanitizer.DEFAULT_MAX_HELPER_ENTRIES)
    val daemonCount = remember(daemonLogText) {
        daemonLogText.lineSequence().count { it.isNotBlank() }
            .coerceAtMost(DiagnosticSanitizer.DEFAULT_MAX_DAEMON_LINES)
    }

    fun performUploadNow() {
        val trimmedDesc = problemDescription.trim()
        if (reporterName.trim().isEmpty() || trimmedDesc.isEmpty()) return
        val submitKind = selectedKind
        val submitWithLogs = includeLogs
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
            kind = submitKind,
            includeLogs = submitWithLogs,
        ) { outcome ->
            submitting = false
            lastOutcome = outcome
            if (outcome.success) {
                lastSubmittedDescription = trimmedDesc
                lastSubmittedKind = submitKind
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
                if (selectedKind == FeedbackKind.BUG) {
                    showOutdatedConfirm = true
                } else {
                    performUploadNow()
                }
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
            validationError = ctx.getString(R.string.report_err_name)
            return
        }
        val trimmedDesc = problemDescription.trim()
        if (trimmedDesc.isEmpty()) {
            validationError = if (isFeature) {
                ctx.getString(R.string.report_err_desc_feature)
            } else {
                ctx.getString(R.string.report_err_desc_bug)
            }
            return
        }
        validationError = ""
        if (isFeature) {
            performUploadNow()
            return
        }
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
                        text = stringResource(if (isFeature) R.string.report_title_feature else R.string.report_title_bug),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    OpalStatusBadge(
                        label = quota.remainingLabel(),
                        color = if (quota.isExhausted) OpalPalette.AccentDanger else OpalPalette.AccentCyan,
                    )
                }

                OpalSegmentedRow(
                    options = listOf(stringResource(R.string.report_tab_bug), stringResource(R.string.report_tab_feature)),
                    selectedIndex = if (isFeature) 1 else 0,
                    onSelect = { idx ->
                        val nextKind = if (idx == 1) FeedbackKind.FEATURE else FeedbackKind.BUG
                        if (nextKind != selectedKind) {
                            selectedKind = nextKind
                            includeLogs = (nextKind == FeedbackKind.BUG)
                            showOutdatedConfirm = false
                            validationError = ""
                        }
                    },
                )

                if (quota.isExhausted) {
                    StatusNoticeBox(
                        text = FeedbackRateLimiter.LIMIT_REACHED_MESSAGE,
                        accent = OpalPalette.AccentDanger,
                    )
                } else if (!sentryConfigured) {
                    StatusNoticeBox(
                        text = stringResource(R.string.report_no_sentry),
                        accent = OpalPalette.AccentAmber,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OpalText(
                        text = stringResource(R.string.report_name_label),
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
                        placeholder = stringResource(R.string.report_name_placeholder),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OpalText(
                        text = stringResource(if (isFeature) R.string.report_desc_feature_label else R.string.report_desc_bug_label),
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
                        placeholder = stringResource(if (isFeature) R.string.report_desc_feature_placeholder else R.string.report_desc_bug_placeholder),
                        singleLine = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                    )
                }

                OpalSwitchRow(
                    title = stringResource(R.string.report_include_logs_title),
                    subtitle = stringResource(if (includeLogs) R.string.report_include_logs_on else R.string.report_include_logs_off),
                    checked = includeLogs,
                    onCheckedChange = { includeLogs = it },
                )

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
                        text = stringResource(if (includeLogs) R.string.report_meta_with_logs else R.string.report_meta_no_logs),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextPrimary,
                    )
                    OpalText(
                        text = stringResource(R.string.report_meta_device, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                    OpalText(
                        text = if (includeLogs) {
                            stringResource(R.string.report_meta_logs_summary, appVersion, helperCount, daemonCount)
                        } else {
                            stringResource(R.string.report_meta_feature_summary, appVersion)
                        },
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                        lineHeight = 16.sp,
                    )
                }

                if (!isFeature && availableRelease != null && lastOutcome?.success != true) {
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
                            text = stringResource(R.string.report_outdated_title, availableRelease.tagName, appVersion),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                        OpalText(
                            text = stringResource(R.string.report_outdated_body),
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
                                    isReady -> stringResource(R.string.settings_btn_install_ver, availableRelease.tagName)
                                    isDownloading -> stringResource(R.string.settings_btn_view_download)
                                    else -> stringResource(R.string.settings_btn_update_to, availableRelease.tagName)
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
                                    label = stringResource(R.string.report_upload_anyway),
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
                } else if (!isFeature && isCheckingVersion && lastOutcome?.success != true) {
                    OpalText(
                        text = stringResource(R.string.report_checking_ver),
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
                                text = stringResource(if (lastSubmittedKind == FeedbackKind.FEATURE) R.string.report_success_feature else R.string.report_success_bug),
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
                                        text = stringResource(R.string.report_code_label, outcome.shortCode),
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
                                    label = stringResource(R.string.report_copy_code),
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
                        label = stringResource(if (lastOutcome?.success == true) R.string.common_done else R.string.common_cancel),
                        filled = alreadySubmittedSameReport,
                        accent = if (alreadySubmittedSameReport) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                        enabled = !isBusy,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = when {
                            awaitingVersionCheck -> stringResource(R.string.report_btn_checking)
                            submitting -> stringResource(R.string.report_btn_submitting)
                            alreadySubmittedSameReport -> stringResource(R.string.report_btn_submitted)
                            quota.isExhausted -> stringResource(R.string.diag_btn_quota_reached)
                            !isFeature && showOutdatedConfirm && availableRelease != null -> stringResource(R.string.report_upload_anyway)
                            isFeature -> stringResource(R.string.report_btn_submit_feature)
                            else -> stringResource(R.string.report_btn_submit_bug)
                        },
                        enabled = submitAllowed,
                        accent = if (!isFeature && showOutdatedConfirm && availableRelease != null) {
                            OpalPalette.AccentAmber
                        } else if (isFeature) {
                            OpalPalette.AccentCyan
                        } else {
                            OpalPalette.AccentBlue
                        },
                        onClick = { submitReport(forceOutdated = !isFeature && showOutdatedConfirm) },
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
        Toast.makeText(ctx, ctx.getString(R.string.feedback_copied_code, shortCode), Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
        Toast.makeText(ctx, ctx.getString(R.string.common_copy_failed), Toast.LENGTH_SHORT).show()
    }
}
