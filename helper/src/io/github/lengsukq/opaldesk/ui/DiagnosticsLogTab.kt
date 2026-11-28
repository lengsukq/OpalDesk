package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.diagnostics.DaemonPostmortemCollector
import io.github.lengsukq.opaldesk.diagnostics.FeedbackKind
import io.github.lengsukq.opaldesk.diagnostics.FeedbackRateLimiter
import io.github.lengsukq.opaldesk.diagnostics.OpalLogFilter
import io.github.lengsukq.opaldesk.diagnostics.OpalLogLevel
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.diagnostics.OpalSentry
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.update.AppUpdateManager

private const val DEFAULT_DISPLAY_LIMIT = 150

private data class RenderedLogRow(
    val text: String,
    val level: OpalLogLevel,
)

/**
 * Tab 4: In-App Diagnostic Logs & Troubleshooting Console.
 * Unifies Helper ring/file logs with live Daemon logs pulled over cert-pinned Binder IPC.
 */
@Composable
fun ConsoleLogsTab(
    status: DaemonStatus,
    scene: CompositorScene,
    remote: RemoteStreamStatus,
    onOpenUpdateModal: () -> Unit = {},
    navController: ConsoleNavigationController? = null,
) {
    val ctx = LocalContext.current
    val helperEntries by OpalLogStore.entriesFlow.collectAsState()
    val daemonLogText by OpalLogStore.daemonLogsFlow.collectAsState()
    val appVersion = remember(ctx) { AppUpdateManager.getCurrentVersionName(ctx) }

    var sourceTab by remember { mutableIntStateOf(0) } // 0=全部, 1=客户端, 2=守护进程
    var warningsOnly by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showAllLines by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var activeFeedbackModalKind by remember { mutableStateOf<FeedbackKind?>(null) }
    var feedbackQuota by remember(ctx) { mutableStateOf(OpalSentry.currentQuota(ctx)) }

    fun refreshDaemonLogs() {
        if (!status.connected) {
            refreshing = true
            Toast.makeText(ctx, ctx.getString(R.string.diag_disconnected_postmortem_toast), Toast.LENGTH_SHORT).show()
            DaemonPostmortemCollector.collectIfDisconnectedAsync(
                ctx = ctx,
                triggerReason = "manual_refresh",
                force = true,
            ) {
                refreshing = false
            }
            return
        }
        refreshing = true
        BridgeClient.fetchDaemonLogsAsync(maxLines = 400) {
            refreshing = false
        }
    }

    LaunchedEffect(status.connected) {
        if (status.connected) {
            refreshing = true
            BridgeClient.fetchDaemonLogsAsync(maxLines = 400) {
                refreshing = false
            }
        } else {
            refreshing = true
            DaemonPostmortemCollector.collectIfDisconnectedAsync(
                ctx = ctx,
                triggerReason = "diagnostics_tab",
            ) {
                refreshing = false
            }
        }
    }

    val filteredHelper = remember(helperEntries, warningsOnly, searchQuery) {
        OpalLogFilter.filterHelperEntries(helperEntries, warningsOnly, searchQuery)
    }
    val filteredDaemon = remember(daemonLogText, warningsOnly, searchQuery) {
        OpalLogFilter.filterDaemonLines(daemonLogText, warningsOnly, searchQuery)
    }

    val displayedRows = remember(sourceTab, filteredHelper, filteredDaemon, newestFirst) {
        val rows = buildList {
            if (sourceTab == 0 || sourceTab == 1) {
                filteredHelper.forEach { entry ->
                    add(RenderedLogRow(entry.formatLine(), entry.level))
                }
            }
            if (sourceTab == 0 || sourceTab == 2) {
                filteredDaemon.forEach { line ->
                    val lvl = when {
                        line.contains(" E/Daemon:") -> OpalLogLevel.ERROR
                        OpalLogFilter.isDaemonWarningOrError(line) -> OpalLogLevel.WARN
                        else -> OpalLogLevel.INFO
                    }
                    add(RenderedLogRow(line, lvl))
                }
            }
        }
        if (newestFirst) rows.asReversed() else rows
    }

    val visibleRows = if (showAllLines || displayedRows.size <= DEFAULT_DISPLAY_LIMIT) {
        displayedRows
    } else {
        displayedRows.take(DEFAULT_DISPLAY_LIMIT)
    }

    val isDark = OpalPalette.isDark
    val heroAccent = if (status.connected) OpalPalette.AccentBlue else OpalPalette.AccentAmber
    val heroBrush = Brush.linearGradient(
        colors = if (isDark) {
            listOf(heroAccent.copy(alpha = 0.22f), Color(0xE6171E2E), Color(0xEC111622))
        } else {
            listOf(heroAccent.copy(alpha = 0.14f), Color(0xF2FFFFFF), Color(0xE8F6F9FF))
        },
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Luminous Diagnostic Hero Card (Feedback CTA + Live Telemetry Pills)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .consoleSearchAnchor(
                    controller = navController,
                    sectionKey = ConsoleSectionKeys.DIAGNOSTICS_LOGS,
                    shape = RoundedCornerShape(24.dp),
                )
                .liquidGlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    fill = heroBrush,
                    rim = Brush.verticalGradient(listOf(heroAccent.copy(alpha = 0.60f), heroAccent.copy(alpha = 0.16f))),
                    elevation = if (isDark) 14.dp else 10.dp,
                )
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = stringResource(R.string.diag_hero_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    OpalText(
                        text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · v$appVersion",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OpalStatusBadge(
                    label = stringResource(if (status.connected) R.string.common_connected else R.string.diag_engine_offline),
                    color = if (status.connected) OpalPalette.AccentEmerald else OpalPalette.AccentAmber,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val metricBg = if (isDark) Color(0x22FFFFFF) else Color(0xB8FFFFFF)
                val metricShape = RoundedCornerShape(12.dp)
                listOf(
                    stringResource(R.string.diag_metric_vd) to if (status.connected) "${status.activeVdCount}/${status.maxVdCount}" else "--",
                    stringResource(R.string.diag_metric_windows) to if (status.connected) "${scene.windows.size}" else "0",
                    stringResource(R.string.diag_metric_quota) to feedbackQuota.remainingLabel().removePrefix("今日剩余 ").trim(),
                ).forEach { (k, v) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(metricShape)
                            .background(metricBg)
                            .border(0.7.dp, OpalPalette.BorderSubtle, metricShape)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        OpalText(text = k, fontSize = 10.5.sp, color = OpalPalette.TextMuted, maxLines = 1)
                        Spacer(Modifier.height(1.dp))
                        OpalText(text = v, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpalButton(
                    label = stringResource(if (feedbackQuota.isExhausted) R.string.diag_btn_quota_reached else R.string.diag_btn_report_bug),
                    compact = false,
                    filled = true,
                    enabled = !feedbackQuota.isExhausted,
                    accent = OpalPalette.AccentBlue,
                    onClick = {
                        feedbackQuota = OpalSentry.currentQuota(ctx)
                        if (!feedbackQuota.isExhausted) {
                            activeFeedbackModalKind = FeedbackKind.BUG
                        }
                    },
                    modifier = Modifier.weight(1.25f),
                )
                OpalButton(
                    label = stringResource(R.string.diag_btn_feature_req),
                    compact = false,
                    filled = false,
                    enabled = !feedbackQuota.isExhausted,
                    accent = OpalPalette.AccentCyan,
                    onClick = {
                        feedbackQuota = OpalSentry.currentQuota(ctx)
                        if (!feedbackQuota.isExhausted) {
                            activeFeedbackModalKind = FeedbackKind.FEATURE
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (feedbackQuota.isExhausted) {
                Spacer(Modifier.height(6.dp))
                OpalText(
                    text = FeedbackRateLimiter.LIMIT_REACHED_MESSAGE,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = OpalPalette.AccentDanger,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Compact, eye-catching QQ community entry strip
            val qqShape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(qqShape)
                    .background(
                        Brush.horizontalGradient(
                            if (isDark) {
                                listOf(
                                    OpalPalette.AccentBlue.copy(alpha = 0.22f),
                                    OpalPalette.AccentCyan.copy(alpha = 0.16f),
                                )
                            } else {
                                listOf(
                                    OpalPalette.AccentBlue.copy(alpha = 0.12f),
                                    OpalPalette.AccentCyan.copy(alpha = 0.10f),
                                )
                            },
                        ),
                    )
                    .border(0.95.dp, OpalPalette.AccentBlue.copy(alpha = 0.45f), qqShape)
                    .clickable { joinOpalDeskQqGroup(ctx) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(OpalPalette.AccentBlue),
                        )
                        OpalText(
                            text = stringResource(R.string.diag_qq_title, OPALDESK_QQ_GROUP_NUMBER),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = OpalPalette.TextPrimary,
                            maxLines = 2,
                        )
                    }
                    OpalText(
                        text = stringResource(R.string.diag_qq_sub),
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
                        lineHeight = 15.sp,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.AccentBlue)
                        .padding(horizontal = 11.dp, vertical = 5.5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OpalText(
                        text = stringResource(R.string.diag_qq_join),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OpalButton(
                    label = stringResource(if (refreshing) R.string.diag_refreshing else R.string.diag_refresh_logs),
                    compact = true,
                    filled = false,
                    enabled = !refreshing,
                    onClick = ::refreshDaemonLogs,
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = stringResource(R.string.diag_copy_report),
                    compact = true,
                    filled = false,
                    onClick = {
                        val report = OpalLogStore.buildReport(
                            appVersion = appVersion,
                            status = status,
                            scene = scene,
                            remote = remote,
                            helperEntries = filteredHelper,
                            daemonLogText = filteredDaemon.joinToString("\n"),
                            env = OpalLogStore.captureEnvironmentSnapshot(ctx),
                        )
                        copyReportToClipboard(ctx, report)
                    },
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = stringResource(R.string.diag_export_share),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentCyan,
                    onClick = {
                        val report = OpalLogStore.buildReport(
                            appVersion = appVersion,
                            status = status,
                            scene = scene,
                            remote = remote,
                            helperEntries = filteredHelper,
                            daemonLogText = filteredDaemon.joinToString("\n"),
                            env = OpalLogStore.captureEnvironmentSnapshot(ctx),
                        )
                        if (!OpalLogStore.shareDiagnosticReport(ctx, report)) {
                            Toast.makeText(ctx, ctx.getString(R.string.diag_export_failed), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 1.5 Feedback Tracking & Resolution Status
        FeedbackTrackingSection(
            currentAppVersion = appVersion,
            onOpenUpdateModal = onOpenUpdateModal,
            cardModifier = Modifier.consoleSearchAnchor(
                controller = navController,
                sectionKey = ConsoleSectionKeys.FEEDBACK_TRACKING,
            ),
        )

        // 2. Filters & Log Stream
        ConsoleSectionTitle(stringResource(R.string.diag_live_stream_title))
        MacGlassSectionCard {
            OpalSegmentedRow(
                options = listOf(
                    stringResource(R.string.diag_filter_all, filteredHelper.size + filteredDaemon.size),
                    stringResource(R.string.diag_filter_helper, filteredHelper.size),
                    stringResource(R.string.diag_filter_daemon, filteredDaemon.size),
                ),
                selectedIndex = sourceTab,
                onSelect = {
                    sourceTab = it
                    showAllLines = false
                },
            )

            Spacer(Modifier.height(10.dp))
            OpalTextInput(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    showAllLines = false
                },
                placeholder = stringResource(R.string.diag_search_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterToggleChip(
                        label = stringResource(if (warningsOnly) R.string.diag_warn_only_on else R.string.diag_warn_only_off),
                        active = warningsOnly,
                        accent = OpalPalette.AccentAmber,
                        onClick = { warningsOnly = !warningsOnly },
                    )
                    FilterToggleChip(
                        label = stringResource(if (newestFirst) R.string.diag_newest_first else R.string.diag_oldest_first),
                        active = newestFirst,
                        accent = OpalPalette.AccentBlue,
                        onClick = { newestFirst = !newestFirst },
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.AccentDanger.copy(alpha = if (OpalPalette.isDark) 0.16f else 0.10f))
                        .border(0.7.dp, OpalPalette.AccentDanger.copy(alpha = 0.35f), CircleShape)
                        .clickable {
                            OpalLogStore.clearHelperLogs()
                            Toast.makeText(ctx, ctx.getString(R.string.diag_cleared_toast), Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    OpalText(
                        text = stringResource(R.string.common_clear),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = OpalPalette.AccentDanger,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            val consoleBg = if (OpalPalette.isDark) Color(0xFF070B14) else Color(0xFFF1F5F9)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(consoleBg)
                    .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (visibleRows.isEmpty()) {
                    OpalText(
                        text = when {
                            sourceTab == 2 && !status.connected -> stringResource(R.string.diag_empty_daemon_offline)
                            searchQuery.isNotBlank() || warningsOnly -> stringResource(R.string.diag_empty_filtered)
                            else -> stringResource(R.string.diag_empty_default)
                        },
                        fontSize = 12.sp,
                        color = OpalPalette.TextMuted,
                        lineHeight = 18.sp,
                    )
                } else {
                    visibleRows.forEach { row ->
                        val lineColor = when (row.level) {
                            OpalLogLevel.ERROR -> OpalPalette.AccentDanger
                            OpalLogLevel.WARN -> OpalPalette.AccentAmber
                            OpalLogLevel.INFO -> OpalPalette.TextPrimary
                        }
                        OpalText(
                            text = row.text,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = lineColor,
                            lineHeight = 15.5.sp,
                        )
                    }
                    if (!showAllLines && displayedRows.size > DEFAULT_DISPLAY_LIMIT) {
                        Spacer(Modifier.height(6.dp))
                        OpalButton(
                            label = stringResource(R.string.diag_expand_all_logs, displayedRows.size),
                            compact = true,
                            filled = false,
                            onClick = { showAllLines = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    activeFeedbackModalKind?.let { initialKind ->
        ReportIssueModal(
            appVersion = appVersion,
            status = status,
            scene = scene,
            remote = remote,
            helperEntries = helperEntries,
            daemonLogText = daemonLogText,
            quota = feedbackQuota,
            initialKind = initialKind,
            onQuotaChanged = { updated -> feedbackQuota = updated },
            onOpenUpdateModal = {
                activeFeedbackModalKind = null
                onOpenUpdateModal()
            },
            onDismiss = { activeFeedbackModalKind = null },
        )
    }
}

@Composable
private fun FilterToggleChip(
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val bg = if (active) accent.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.14f) else OpalPalette.ChipFill
    val border = if (active) accent.copy(alpha = 0.45f) else OpalPalette.BorderHairline
    val fg = if (active) accent else OpalPalette.TextSecondary
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        OpalText(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

private fun copyReportToClipboard(ctx: Context, report: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk Logs", report))
        Toast.makeText(ctx, ctx.getString(R.string.diag_copied_report_toast), Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
        Toast.makeText(ctx, ctx.getString(R.string.common_copy_failed), Toast.LENGTH_SHORT).show()
    }
}
