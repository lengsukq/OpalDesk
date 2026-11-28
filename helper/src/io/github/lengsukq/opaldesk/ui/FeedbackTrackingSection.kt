package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.diagnostics.FeedbackHistoryLogic
import io.github.lengsukq.opaldesk.diagnostics.FeedbackHistoryStore
import io.github.lengsukq.opaldesk.diagnostics.FeedbackKind
import io.github.lengsukq.opaldesk.diagnostics.FeedbackRecord
import io.github.lengsukq.opaldesk.diagnostics.FeedbackResolutionStatus
import io.github.lengsukq.opaldesk.diagnostics.FeedbackStatusParser
import io.github.lengsukq.opaldesk.diagnostics.FeedbackVersionRelation
import io.github.lengsukq.opaldesk.diagnostics.OpalSentry
import io.github.lengsukq.opaldesk.update.AppUpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_COLLAPSED_RECORDS = 3

/**
 * In-app feedback history & resolution tracker rendered inside [ConsoleLogsTab].
 * Lets users monitor whether submitted issues are resolved, inspect maintainer replies,
 * look up reports by 8-char short code, and jump straight to [AppUpdateModal] when fixed in a newer version.
 */
@Composable
fun FeedbackTrackingSection(
    currentAppVersion: String,
    onOpenUpdateModal: () -> Unit,
    cardModifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        FeedbackHistoryStore.init(ctx)
        if (OpalSentry.isStatusSyncConfigured()) {
            OpalSentry.refreshFeedbackStatusesAsync(ctx, force = false)
        }
    }

    val records by FeedbackHistoryStore.recordsFlow.collectAsState()
    val lastSyncAtMs by FeedbackHistoryStore.lastSyncAtMsFlow.collectAsState()
    val syncConfigured = remember { OpalSentry.isStatusSyncConfigured() }

    var syncing by remember { mutableStateOf(false) }
    var lookupOpen by remember { mutableStateOf(false) }
    var lookupCodeInput by remember { mutableStateOf("") }
    var lookingUp by remember { mutableStateOf(false) }
    var syncNotice by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // message to isError
    var showAllRecords by remember { mutableStateOf(false) }

    val hasSyncableRecords = remember(records) {
        records.any { FeedbackHistoryLogic.normalizeEventId(it.eventId).isNotEmpty() || it.fromCodeLookup }
    }

    fun triggerManualRefresh() {
        if (!syncConfigured || syncing) return
        if (!hasSyncableRecords) {
            syncNotice = ctx.getString(R.string.feedback_no_syncable) to false
            return
        }
        syncing = true
        syncNotice = null
        OpalSentry.refreshFeedbackStatusesAsync(ctx, force = true) { outcome ->
            syncing = false
            syncNotice = if (outcome.success) {
                if (outcome.updatedCount > 0) {
                    ctx.getString(R.string.feedback_synced_count, outcome.updatedCount) to false
                } else {
                    ctx.getString(R.string.feedback_no_changes) to false
                }
            } else {
                outcome.message.ifBlank { ctx.getString(R.string.feedback_refresh_failed) } to true
            }
        }
    }

    fun triggerCodeLookup() {
        if (!syncConfigured || lookingUp) return
        val normalized = FeedbackHistoryLogic.normalizeShortCode(lookupCodeInput)
        if (normalized.isEmpty()) {
            syncNotice = ctx.getString(R.string.feedback_invalid_code) to true
            return
        }
        lookingUp = true
        syncNotice = null
        OpalSentry.lookupFeedbackByCodeAsync(ctx, normalized) { outcome ->
            lookingUp = false
            when {
                !outcome.success -> syncNotice = outcome.message to true
                outcome.found && outcome.item != null -> {
                    lookupCodeInput = ""
                    lookupOpen = false
                    val item = outcome.item
                    val detail = when {
                        item.status == FeedbackResolutionStatus.FIXED_IN_VERSION && item.fixedVersion.isNotEmpty() ->
                            if (item.kind == FeedbackKind.FEATURE) {
                                ctx.getString(R.string.feedback_found_feature_ver, normalized, item.fixedVersion)
                            } else {
                                ctx.getString(R.string.feedback_found_bug_ver, normalized, item.fixedVersion)
                            }
                        else -> ctx.getString(R.string.feedback_found_status, normalized, statusLabelFor(ctx, item.kind, item.status))
                    }
                    syncNotice = detail to false
                }
                else -> syncNotice = outcome.message.ifBlank { ctx.getString(R.string.feedback_not_found, normalized) } to true
            }
        }
    }

    ConsoleSectionTitle(stringResource(R.string.feedback_section_title))
    MacGlassSectionCard(modifier = cardModifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                OpalText(
                    text = if (records.isEmpty()) stringResource(R.string.feedback_progress_title) else stringResource(R.string.feedback_progress_title_count, records.size),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                OpalText(
                    text = when {
                        !syncConfigured -> stringResource(R.string.feedback_sync_unconfigured)
                        lastSyncAtMs > 0L -> stringResource(R.string.feedback_last_sync, formatFeedbackTime(ctx, lastSyncAtMs))
                        else -> stringResource(R.string.feedback_sync_hint)
                    },
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (lookupOpen) {
                                OpalPalette.AccentCyan.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.14f)
                            } else {
                                OpalPalette.ChipFill
                            },
                        )
                        .border(
                            0.8.dp,
                            if (lookupOpen) OpalPalette.AccentCyan.copy(alpha = 0.45f) else OpalPalette.BorderHairline,
                            CircleShape,
                        )
                        .clickable {
                            lookupOpen = !lookupOpen
                            syncNotice = null
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    OpalText(
                        text = stringResource(if (lookupOpen) R.string.feedback_hide_lookup else R.string.feedback_lookup_code),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (lookupOpen) OpalPalette.AccentCyan else OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
                OpalButton(
                    label = stringResource(if (syncing) R.string.feedback_syncing else R.string.feedback_refresh_status),
                    compact = true,
                    filled = false,
                    enabled = syncConfigured && !syncing && hasSyncableRecords,
                    accent = OpalPalette.AccentBlue,
                    onClick = ::triggerManualRefresh,
                )
            }
        }

        if (lookupOpen) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OpalTextInput(
                        value = lookupCodeInput,
                        onValueChange = {
                            lookupCodeInput = it.take(12)
                            if (syncNotice != null) syncNotice = null
                        },
                        placeholder = stringResource(R.string.feedback_lookup_placeholder),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OpalButton(
                    label = stringResource(if (lookingUp) R.string.feedback_looking_up else R.string.feedback_search_btn),
                    compact = true,
                    filled = true,
                    enabled = syncConfigured && !lookingUp && lookupCodeInput.isNotBlank(),
                    accent = OpalPalette.AccentCyan,
                    onClick = ::triggerCodeLookup,
                )
            }
        }

        syncNotice?.let { (msg, isError) ->
            Spacer(Modifier.height(8.dp))
            val noticeColor = if (isError) OpalPalette.AccentDanger else OpalPalette.AccentEmerald
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(noticeColor.copy(alpha = if (OpalPalette.isDark) 0.14f else 0.09f))
                    .border(0.75.dp, noticeColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                OpalText(
                    text = msg,
                    fontSize = 11.5.sp,
                    color = noticeColor,
                    lineHeight = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(OpalPalette.ChipFill)
                    .border(0.7.dp, OpalPalette.BorderHairline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                OpalText(
                    text = stringResource(R.string.feedback_empty_hint),
                    fontSize = 12.sp,
                    color = OpalPalette.TextMuted,
                    lineHeight = 17.5.sp,
                )
            }
        } else {
            val visible = if (showAllRecords || records.size <= DEFAULT_COLLAPSED_RECORDS) {
                records
            } else {
                records.take(DEFAULT_COLLAPSED_RECORDS)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEach { rec ->
                    FeedbackRecordCard(
                        record = rec,
                        currentAppVersion = currentAppVersion,
                        onCopyCode = { copyCodeToClipboard(ctx, rec) },
                        onOpenUpdate = {
                            AppUpdateManager.checkForUpdates(ctx, userInitiated = true)
                            onOpenUpdateModal()
                        },
                        onRemove = {
                            FeedbackHistoryStore.removeRecord(ctx, rec.eventId)
                        },
                    )
                }
            }
            if (records.size > DEFAULT_COLLAPSED_RECORDS) {
                Spacer(Modifier.height(8.dp))
                OpalButton(
                    label = if (showAllRecords) stringResource(R.string.feedback_collapse_history) else stringResource(R.string.feedback_expand_all, records.size),
                    compact = true,
                    filled = false,
                    onClick = { showAllRecords = !showAllRecords },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FeedbackRecordCard(
    record: FeedbackRecord,
    currentAppVersion: String,
    onCopyCode: () -> Unit,
    onOpenUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    val relation = remember(currentAppVersion, record) {
        FeedbackStatusParser.evaluateVersionRelation(currentAppVersion, record)
    }
    val (badgeLabel, badgeColor) = resolveStatusBadge(record, relation)
    val cardBg = if (OpalPalette.isDark) Color(0x18FFFFFF) else Color(0xCCFFFFFF)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(OpalPalette.ChipFill)
                        .border(0.7.dp, OpalPalette.BorderHairline, RoundedCornerShape(8.dp))
                        .clickable(onClick = onCopyCode)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    OpalText(
                        text = "#${record.shortCode}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = OpalPalette.TextPrimary,
                        maxLines = 1,
                    )
                }
                val isFeature = record.kind == FeedbackKind.FEATURE
                val kindColor = if (isFeature) OpalPalette.AccentCyan else OpalPalette.AccentBlue
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(kindColor.copy(alpha = if (OpalPalette.isDark) 0.18f else 0.10f))
                        .border(0.6.dp, kindColor.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    OpalText(
                        text = stringResource(if (isFeature) R.string.feedback_kind_feature else R.string.feedback_kind_bug),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = kindColor,
                        maxLines = 1,
                    )
                }
                val meta = buildString {
                    if (record.reportedVersion.isNotBlank()) {
                        append("v")
                        append(record.reportedVersion)
                        append(" · ")
                    }
                    append(formatFeedbackTime(ctx, record.createdAtMs))
                }
                OpalText(
                    text = meta,
                    fontSize = 10.5.sp,
                    color = OpalPalette.TextMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(6.dp))
            OpalStatusBadge(label = badgeLabel, color = badgeColor)
        }

        OpalText(
            text = record.summary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = OpalPalette.TextPrimary,
            maxLines = 2,
            lineHeight = 17.5.sp,
        )

        if (record.replyNote.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.14f else 0.08f))
                    .border(0.7.dp, OpalPalette.AccentBlue.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                OpalText(
                    text = stringResource(R.string.feedback_dev_reply, record.replyNote),
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextPrimary,
                    lineHeight = 16.sp,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (relation == FeedbackVersionRelation.FIXED_IN_NEWER_VERSION && record.fixedVersion.isNotBlank()) {
                OpalButton(
                    label = stringResource(R.string.feedback_check_update_ver, record.fixedVersion),
                    compact = true,
                    filled = true,
                    accent = OpalPalette.AccentBlue,
                    onClick = onOpenUpdate,
                )
            } else {
                val hint = when {
                    relation == FeedbackVersionRelation.FIXED_IN_CURRENT_OR_OLDER && record.fixedVersion.isNotBlank() ->
                        if (record.kind == FeedbackKind.FEATURE) {
                            stringResource(R.string.feedback_included_feature, record.fixedVersion)
                        } else {
                            stringResource(R.string.feedback_included_fix, record.fixedVersion)
                        }
                    record.lastCheckedAtMs > 0L ->
                        stringResource(R.string.feedback_checked_at, formatFeedbackTime(ctx, record.lastCheckedAtMs))
                    else -> stringResource(R.string.feedback_tap_refresh_hint)
                }
                OpalText(
                    text = hint,
                    fontSize = 10.5.sp,
                    color = OpalPalette.TextMuted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            OpalText(
                text = stringResource(R.string.common_remove),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = OpalPalette.TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

private fun statusLabelFor(
    ctx: Context,
    kind: FeedbackKind,
    status: FeedbackResolutionStatus,
): String = if (kind == FeedbackKind.FEATURE) {
    when (status) {
        FeedbackResolutionStatus.OPEN -> ctx.getString(R.string.feedback_status_eval)
        FeedbackResolutionStatus.FIXED_PENDING_RELEASE -> ctx.getString(R.string.feedback_status_planned)
        FeedbackResolutionStatus.FIXED_IN_VERSION -> ctx.getString(R.string.feedback_status_implemented)
        FeedbackResolutionStatus.RESOLVED_OTHER -> ctx.getString(R.string.feedback_status_adopted)
        FeedbackResolutionStatus.IGNORED -> ctx.getString(R.string.feedback_status_declined)
    }
} else {
    when (status) {
        FeedbackResolutionStatus.OPEN -> ctx.getString(R.string.feedback_status_triaging)
        FeedbackResolutionStatus.FIXED_PENDING_RELEASE -> ctx.getString(R.string.feedback_status_fixed_pending)
        FeedbackResolutionStatus.FIXED_IN_VERSION -> ctx.getString(R.string.feedback_status_fixed)
        FeedbackResolutionStatus.RESOLVED_OTHER -> ctx.getString(R.string.feedback_status_resolved)
        FeedbackResolutionStatus.IGNORED -> ctx.getString(R.string.feedback_status_archived)
    }
}

@Composable
private fun resolveStatusBadge(
    record: FeedbackRecord,
    relation: FeedbackVersionRelation,
): Pair<String, Color> {
    val isFeature = record.kind == FeedbackKind.FEATURE
    return when (record.status) {
        FeedbackResolutionStatus.OPEN ->
            stringResource(if (isFeature) R.string.feedback_status_eval else R.string.feedback_status_triaging) to OpalPalette.AccentAmber
        FeedbackResolutionStatus.FIXED_PENDING_RELEASE ->
            stringResource(if (isFeature) R.string.feedback_status_planned else R.string.feedback_status_fixed_pending) to OpalPalette.AccentCyan
        FeedbackResolutionStatus.IGNORED ->
            stringResource(if (isFeature) R.string.feedback_status_declined else R.string.feedback_status_archived) to OpalPalette.TextMuted
        FeedbackResolutionStatus.RESOLVED_OTHER ->
            stringResource(if (isFeature) R.string.feedback_status_adopted else R.string.feedback_status_resolved) to OpalPalette.AccentEmerald
        FeedbackResolutionStatus.FIXED_IN_VERSION -> when (relation) {
            FeedbackVersionRelation.FIXED_IN_CURRENT_OR_OLDER ->
                stringResource(if (isFeature) R.string.feedback_status_live_feature else R.string.feedback_status_live_fix, record.fixedVersion) to OpalPalette.AccentEmerald
            FeedbackVersionRelation.FIXED_IN_NEWER_VERSION ->
                stringResource(if (isFeature) R.string.feedback_status_update_feature else R.string.feedback_status_update_fix, record.fixedVersion) to OpalPalette.AccentBlue
            else ->
                if (record.fixedVersion.isNotBlank()) {
                    stringResource(if (isFeature) R.string.feedback_status_ver_feature else R.string.feedback_status_ver_fix, record.fixedVersion) to OpalPalette.AccentEmerald
                } else {
                    stringResource(if (isFeature) R.string.feedback_status_implemented else R.string.feedback_status_fixed) to OpalPalette.AccentEmerald
                }
        }
    }
}

private fun formatFeedbackTime(ctx: Context, timestampMs: Long): String {
    if (timestampMs <= 0L) return ctx.getString(R.string.common_unknown_time)
    return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(timestampMs))
}

private fun copyCodeToClipboard(ctx: Context, record: FeedbackRecord) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipValue = if (FeedbackHistoryLogic.normalizeEventId(record.eventId).isNotEmpty()) {
            "${record.shortCode} (${record.eventId})"
        } else {
            record.shortCode
        }
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk Feedback Code", clipValue))
        Toast.makeText(ctx, ctx.getString(R.string.feedback_copied_code, record.shortCode), Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
        Toast.makeText(ctx, ctx.getString(R.string.common_copy_failed), Toast.LENGTH_SHORT).show()
    }
}
