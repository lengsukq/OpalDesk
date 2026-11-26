package io.github.lengsukq.opaldesk.diagnostics

import io.github.lengsukq.opaldesk.update.AppUpdateLogic
import java.net.URI
import java.util.Locale

enum class FeedbackKind(val wireValue: String, val label: String) {
    BUG("bug", "问题反馈"),
    FEATURE("feature", "功能建议"),
    ;

    companion object {
        fun fromWireValue(raw: String?): FeedbackKind {
            val clean = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.wireValue == clean } ?: BUG
        }
    }
}

enum class FeedbackResolutionStatus(val wireValue: String, val label: String) {
    OPEN("open", "排查中"),
    FIXED_PENDING_RELEASE("fixed_pending_release", "已修复 · 待下版发布"),
    FIXED_IN_VERSION("fixed_in_version", "已修复"),
    RESOLVED_OTHER("resolved", "已处理"),
    IGNORED("ignored", "已归档"),
    ;

    val isTerminal: Boolean
        get() = this == FIXED_IN_VERSION || this == RESOLVED_OTHER || this == IGNORED

    companion object {
        fun fromWireValue(raw: String?): FeedbackResolutionStatus {
            val clean = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.wireValue == clean } ?: OPEN
        }
    }
}

enum class FeedbackVersionRelation {
    NOT_FIXED_YET,
    FIXED_IN_CURRENT_OR_OLDER,
    FIXED_IN_NEWER_VERSION,
    FIXED_VERSION_UNKNOWN,
}

data class FeedbackRecord(
    val eventId: String,
    val shortCode: String,
    val summary: String,
    val reportedVersion: String,
    val createdAtMs: Long,
    val status: FeedbackResolutionStatus = FeedbackResolutionStatus.OPEN,
    val fixedVersion: String = "",
    val replyNote: String = "",
    val lastCheckedAtMs: Long = 0L,
    val remoteUpdatedAt: String = "",
    val fromCodeLookup: Boolean = false,
    val kind: FeedbackKind = FeedbackKind.BUG,
)

data class FeedbackRemoteItem(
    val eventId: String = "",
    val shortCode: String,
    val status: FeedbackResolutionStatus,
    val fixedVersion: String = "",
    val replyNote: String = "",
    val summary: String = "",
    val reportedVersion: String = "",
    val updatedAt: String = "",
    val kind: FeedbackKind = FeedbackKind.BUG,
)

data class FeedbackBatchStatusPayload(
    val schema: Int,
    val generatedAt: String,
    val items: Map<String, FeedbackRemoteItem>,
)

data class FeedbackLookupPayload(
    val schema: Int,
    val found: Boolean,
    val item: FeedbackRemoteItem?,
)

/**
 * Pure JVM-testable parser and version comparator for feedback resolution tracking.
 * Reuses [AppUpdateLogic.JsonLite] and [AppUpdateLogic.compareVersions] as single sources of truth.
 */
object FeedbackStatusParser {
    private val SEMVER_REGEX = Regex("""^\d+\.\d+(?:\.\d+)?(?:-[0-9A-Za-z.-]+)?$""")

    fun extractSemverFromSentryRelease(raw: String?): String {
        var candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return ""
        val atIdx = candidate.lastIndexOf('@')
        if (atIdx >= 0 && atIdx + 1 < candidate.length) {
            candidate = candidate.substring(atIdx + 1)
        }
        candidate = candidate.substringBefore('+').trim()
        candidate = AppUpdateLogic.normalizeVersion(candidate)
        return if (SEMVER_REGEX.matches(candidate)) candidate else ""
    }

    fun evaluateVersionRelation(
        currentAppVersion: String,
        record: FeedbackRecord,
    ): FeedbackVersionRelation {
        if (record.status != FeedbackResolutionStatus.FIXED_IN_VERSION) {
            return if (record.status == FeedbackResolutionStatus.RESOLVED_OTHER) {
                FeedbackVersionRelation.FIXED_VERSION_UNKNOWN
            } else {
                FeedbackVersionRelation.NOT_FIXED_YET
            }
        }
        val fixed = extractSemverFromSentryRelease(record.fixedVersion)
        if (fixed.isEmpty()) return FeedbackVersionRelation.FIXED_VERSION_UNKNOWN
        val current = extractSemverFromSentryRelease(currentAppVersion)
        if (current.isEmpty()) return FeedbackVersionRelation.FIXED_VERSION_UNKNOWN
        return if (AppUpdateLogic.isNewerVersion(current, fixed)) {
            FeedbackVersionRelation.FIXED_IN_NEWER_VERSION
        } else {
            FeedbackVersionRelation.FIXED_IN_CURRENT_OR_OLDER
        }
    }

    fun parseBatchStatusJson(json: String): FeedbackBatchStatusPayload? {
        val root = AppUpdateLogic.JsonLite.parse(json) as? Map<*, *> ?: return null
        val schema = (root["schema"] as? Number)?.toInt() ?: return null
        val generatedAt = (root["generatedAt"] as? String).orEmpty()
        val rawItems = root["items"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val items = LinkedHashMap<String, FeedbackRemoteItem>(rawItems.size)
        for ((k, v) in rawItems) {
            val eventId = FeedbackHistoryLogic.normalizeEventId(k as? String)
            val obj = v as? Map<*, *> ?: continue
            if (eventId.isEmpty()) continue
            val parsedItem = parseRemoteItemMap(obj, fallbackEventId = eventId) ?: continue
            items[eventId] = parsedItem
        }
        return FeedbackBatchStatusPayload(schema = schema, generatedAt = generatedAt, items = items)
    }

    fun parseLookupJson(json: String): FeedbackLookupPayload? {
        val root = AppUpdateLogic.JsonLite.parse(json) as? Map<*, *> ?: return null
        val schema = (root["schema"] as? Number)?.toInt() ?: return null
        val found = root["found"] == true
        val itemMap = root["item"] as? Map<*, *>
        val item = if (found && itemMap != null) parseRemoteItemMap(itemMap) else null
        return FeedbackLookupPayload(schema = schema, found = found && item != null, item = item)
    }

    fun serializeRecordsJson(records: List<FeedbackRecord>): String = buildString(1024) {
        append("{\"version\":1,\"records\":[")
        records.forEachIndexed { idx, rec ->
            if (idx > 0) append(',')
            append('{')
            append("\"eventId\":").append(SentryEnvelopeTransport.jsonString(rec.eventId)).append(',')
            append("\"shortCode\":").append(SentryEnvelopeTransport.jsonString(rec.shortCode)).append(',')
            append("\"summary\":").append(SentryEnvelopeTransport.jsonString(rec.summary)).append(',')
            append("\"reportedVersion\":").append(SentryEnvelopeTransport.jsonString(rec.reportedVersion)).append(',')
            append("\"createdAtMs\":").append(rec.createdAtMs).append(',')
            append("\"status\":").append(SentryEnvelopeTransport.jsonString(rec.status.wireValue)).append(',')
            append("\"fixedVersion\":").append(SentryEnvelopeTransport.jsonString(rec.fixedVersion)).append(',')
            append("\"replyNote\":").append(SentryEnvelopeTransport.jsonString(rec.replyNote)).append(',')
            append("\"lastCheckedAtMs\":").append(rec.lastCheckedAtMs).append(',')
            append("\"remoteUpdatedAt\":").append(SentryEnvelopeTransport.jsonString(rec.remoteUpdatedAt)).append(',')
            append("\"fromCodeLookup\":").append(rec.fromCodeLookup).append(',')
            append("\"kind\":").append(SentryEnvelopeTransport.jsonString(rec.kind.wireValue))
            append('}')
        }
        append("]}")
    }

    fun parseRecordsJson(json: String): List<FeedbackRecord> {
        if (json.isBlank()) return emptyList()
        val root = AppUpdateLogic.JsonLite.parse(json) as? Map<*, *> ?: return emptyList()
        val rawList = root["records"] as? List<*> ?: return emptyList()
        val parsed = ArrayList<FeedbackRecord>(rawList.size)
        for (entry in rawList) {
            val obj = entry as? Map<*, *> ?: continue
            val eventId = FeedbackHistoryLogic.normalizeEventId(obj["eventId"] as? String)
            val shortCode = FeedbackHistoryLogic.normalizeShortCode(obj["shortCode"] as? String)
                .ifEmpty { if (eventId.length >= 8) SentryEnvelopeTransport.formatShortCode(eventId) else "" }
            if (eventId.isEmpty() && shortCode.isEmpty()) continue
            val resolvedKey = eventId.ifEmpty { "code:$shortCode" }
            parsed.add(
                FeedbackRecord(
                    eventId = resolvedKey,
                    shortCode = shortCode,
                    summary = (obj["summary"] as? String).orEmpty(),
                    reportedVersion = (obj["reportedVersion"] as? String).orEmpty(),
                    createdAtMs = (obj["createdAtMs"] as? Number)?.toLong() ?: 0L,
                    status = FeedbackResolutionStatus.fromWireValue(obj["status"] as? String),
                    fixedVersion = extractSemverFromSentryRelease(obj["fixedVersion"] as? String),
                    replyNote = (obj["replyNote"] as? String).orEmpty(),
                    lastCheckedAtMs = (obj["lastCheckedAtMs"] as? Number)?.toLong() ?: 0L,
                    remoteUpdatedAt = (obj["remoteUpdatedAt"] as? String).orEmpty(),
                    fromCodeLookup = obj["fromCodeLookup"] == true,
                    kind = FeedbackKind.fromWireValue(obj["kind"] as? String),
                ),
            )
        }
        return FeedbackHistoryLogic.normalizeList(parsed)
    }

    private fun parseRemoteItemMap(
        obj: Map<*, *>,
        fallbackEventId: String = "",
    ): FeedbackRemoteItem? {
        val eventId = FeedbackHistoryLogic.normalizeEventId(obj["eventId"] as? String)
            .ifEmpty { fallbackEventId }
        val shortCode = FeedbackHistoryLogic.normalizeShortCode(obj["shortCode"] as? String)
            .ifEmpty { if (eventId.length >= 8) SentryEnvelopeTransport.formatShortCode(eventId) else "" }
        if (shortCode.isEmpty() && eventId.isEmpty()) return null
        return FeedbackRemoteItem(
            eventId = eventId,
            shortCode = shortCode,
            status = FeedbackResolutionStatus.fromWireValue(obj["status"] as? String),
            fixedVersion = extractSemverFromSentryRelease(obj["fixedVersion"] as? String),
            replyNote = (obj["replyNote"] as? String).orEmpty().trim().take(240),
            summary = (obj["summary"] as? String).orEmpty().trim().take(120),
            reportedVersion = extractSemverFromSentryRelease(obj["reportedVersion"] as? String),
            updatedAt = (obj["updatedAt"] as? String).orEmpty().trim(),
            kind = FeedbackKind.fromWireValue(obj["kind"] as? String),
        )
    }
}

/**
 * Pure JVM-testable history list merge, deduplication, throttling, and endpoint resolution rules.
 */
object FeedbackHistoryLogic {
    const val MAX_STORED_RECORDS = 50
    const val MAX_BATCH_QUERY_IDS = 20
    const val AUTO_REFRESH_INTERVAL_MS = 10L * 60L * 1000L
    private val HEX_EVENT_ID_REGEX = Regex("^[0-9a-f]{32}$")
    private val HEX_SHORT_CODE_REGEX = Regex("^[0-9A-F]{8}$")

    fun normalizeEventId(raw: String?): String {
        val cleaned = raw?.trim()?.replace("-", "")?.lowercase(Locale.ROOT).orEmpty()
        return if (HEX_EVENT_ID_REGEX.matches(cleaned)) cleaned else ""
    }

    fun normalizeShortCode(raw: String?): String {
        val cleaned = raw?.trim()?.removePrefix("#")?.uppercase(Locale.ROOT).orEmpty()
        return if (HEX_SHORT_CODE_REGEX.matches(cleaned)) cleaned else ""
    }

    fun summarizeProblemDescription(
        rawDescription: String,
        kind: FeedbackKind = FeedbackKind.BUG,
    ): String {
        val clean = DiagnosticSanitizer.sanitizeText(rawDescription)
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        val fallback = if (kind == FeedbackKind.FEATURE) "功能建议" else "手动诊断日志上报"
        return if (clean.length > 120) clean.take(119).trimEnd() + "…" else clean.ifEmpty { fallback }
    }

    fun recordSubmitted(
        existing: List<FeedbackRecord>,
        eventId: String,
        shortCode: String,
        problemDescription: String,
        reportedVersion: String,
        createdAtMs: Long,
        kind: FeedbackKind = FeedbackKind.BUG,
    ): List<FeedbackRecord> {
        val normId = normalizeEventId(eventId)
        val normCode = normalizeShortCode(shortCode)
            .ifEmpty { if (normId.length >= 8) SentryEnvelopeTransport.formatShortCode(normId) else "" }
        if (normId.isEmpty() && normCode.isEmpty()) return normalizeList(existing)
        val key = normId.ifEmpty { "code:$normCode" }
        val newRecord = FeedbackRecord(
            eventId = key,
            shortCode = normCode,
            summary = summarizeProblemDescription(problemDescription, kind),
            reportedVersion = AppUpdateLogic.normalizeVersion(reportedVersion),
            createdAtMs = createdAtMs,
            status = FeedbackResolutionStatus.OPEN,
            kind = kind,
        )
        val filtered = existing.filterNot {
            it.eventId == key || (normCode.isNotEmpty() && it.shortCode.equals(normCode, ignoreCase = true))
        }
        return normalizeList(listOf(newRecord) + filtered)
    }

    private fun isPlaceholderSummary(summary: String): Boolean =
        summary.isBlank() || summary.startsWith("通过反馈码 #")

    private fun mergeRecordWithRemote(
        current: FeedbackRecord,
        remote: FeedbackRemoteItem,
        checkedAtMs: Long,
        markAsCodeLookup: Boolean = current.fromCodeLookup || isPlaceholderSummary(current.summary),
    ): FeedbackRecord {
        val nextSummary = if (isPlaceholderSummary(current.summary) && remote.summary.isNotBlank()) {
            remote.summary
        } else {
            current.summary
        }
        val nextReportedVersion = current.reportedVersion.ifBlank { remote.reportedVersion }
        // Preserve locally recorded FEATURE kind even if an older Worker omits `kind` (defaulting to BUG).
        val nextKind = if (remote.kind != FeedbackKind.BUG) remote.kind else current.kind
        return current.copy(
            summary = nextSummary,
            reportedVersion = nextReportedVersion,
            status = remote.status,
            fixedVersion = remote.fixedVersion,
            replyNote = remote.replyNote,
            lastCheckedAtMs = checkedAtMs,
            remoteUpdatedAt = remote.updatedAt,
            fromCodeLookup = markAsCodeLookup,
            kind = nextKind,
        )
    }

    fun mergeRemoteStatuses(
        existing: List<FeedbackRecord>,
        remoteItems: Map<String, FeedbackRemoteItem>,
        checkedAtMs: Long,
    ): List<FeedbackRecord> {
        if (existing.isEmpty()) return emptyList()
        val updated = existing.map { rec ->
            val remote = remoteItems[rec.eventId]
                ?: remoteItems.values.firstOrNull {
                    rec.shortCode.isNotEmpty() && it.shortCode.equals(rec.shortCode, ignoreCase = true)
                }
            if (remote == null) rec else mergeRecordWithRemote(rec, remote, checkedAtMs)
        }
        return normalizeList(updated)
    }

    fun upsertLookedUpRecord(
        existing: List<FeedbackRecord>,
        remoteItem: FeedbackRemoteItem,
        checkedAtMs: Long,
    ): List<FeedbackRecord> {
        val normId = normalizeEventId(remoteItem.eventId)
        val normCode = normalizeShortCode(remoteItem.shortCode)
        if (normId.isEmpty() && normCode.isEmpty()) return normalizeList(existing)

        val matchIndex = existing.indexOfFirst {
            (normId.isNotEmpty() && it.eventId == normId) ||
                (normCode.isNotEmpty() && it.shortCode.equals(normCode, ignoreCase = true))
        }
        if (matchIndex >= 0) {
            val current = existing[matchIndex]
            val resolvedId = normId.ifEmpty { current.eventId }
            val merged = mergeRecordWithRemote(
                current = current.copy(eventId = resolvedId),
                remote = remoteItem,
                checkedAtMs = checkedAtMs,
                markAsCodeLookup = current.fromCodeLookup,
            )
            val mutable = existing.toMutableList()
            mutable[matchIndex] = merged
            return normalizeList(mutable)
        }

        val synthetic = FeedbackRecord(
            eventId = normId.ifEmpty { "code:$normCode" },
            shortCode = normCode,
            summary = remoteItem.summary.ifBlank { "通过反馈码 #$normCode 找回的记录" },
            reportedVersion = remoteItem.reportedVersion,
            createdAtMs = checkedAtMs,
            status = remoteItem.status,
            fixedVersion = remoteItem.fixedVersion,
            replyNote = remoteItem.replyNote,
            lastCheckedAtMs = checkedAtMs,
            remoteUpdatedAt = remoteItem.updatedAt,
            fromCodeLookup = true,
            kind = remoteItem.kind,
        )
        return normalizeList(listOf(synthetic) + existing)
    }

    fun selectEventIdsForBatchQuery(records: List<FeedbackRecord>): List<String> =
        records.asSequence()
            .map { normalizeEventId(it.eventId) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_BATCH_QUERY_IDS)
            .toList()

    fun shouldAutoRefresh(
        records: List<FeedbackRecord>,
        nowMs: Long,
        intervalMs: Long = AUTO_REFRESH_INTERVAL_MS,
    ): Boolean {
        val hasQueryTargets = records.any { normalizeEventId(it.eventId).isNotEmpty() && !it.status.isTerminal }
        if (!hasQueryTargets) return false
        val latestCheck = records.maxOfOrNull { it.lastCheckedAtMs } ?: 0L
        return latestCheck <= 0L || (nowMs - latestCheck) >= intervalMs
    }

    fun normalizeList(records: List<FeedbackRecord>): List<FeedbackRecord> {
        val seenKeys = HashSet<String>(records.size)
        return records.sortedByDescending { it.createdAtMs }
            .filter { rec -> seenKeys.add(rec.eventId) }
            .take(MAX_STORED_RECORDS)
    }

    /**
     * Resolves the HTTPS origin base URL for feedback status lookups.
     * Falls back to deriving the origin from [sentryTunnelUrl] (e.g. `https://host/envelope` -> `https://host`)
     * when [feedbackStatusUrl] is blank.
     */
    fun resolveStatusBaseUrl(
        feedbackStatusUrl: String,
        sentryTunnelUrl: String,
        allowLocalhostForTests: Boolean = false,
    ): String {
        val explicit = cleanBaseUrl(feedbackStatusUrl, allowLocalhostForTests)
        if (explicit.isNotEmpty()) return explicit
        val tunnelUri = runCatching { URI(sentryTunnelUrl.trim()) }.getOrNull() ?: return ""
        val origin = buildOriginFromUri(tunnelUri, allowLocalhostForTests) ?: return ""
        return origin
    }

    fun isAllowedEndpointUrl(url: String, allowLocalhostForTests: Boolean = false): Boolean =
        cleanBaseUrl(url, allowLocalhostForTests).isNotEmpty()

    private fun cleanBaseUrl(raw: String, allowLocalhostForTests: Boolean): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return ""
        return buildOriginFromUri(uri, allowLocalhostForTests)?.let { origin ->
            val path = uri.rawPath?.trimEnd('/').orEmpty()
            if (path.isEmpty() || path == "/") origin else "$origin$path"
        }.orEmpty()
    }

    private fun buildOriginFromUri(uri: URI, allowLocalhostForTests: Boolean): String? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val isLocal = host == "127.0.0.1" || host == "localhost"
        val schemeValid = scheme == "https" || (allowLocalhostForTests && scheme == "http" && isLocal)
        if (!schemeValid) return null
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$portPart"
    }
}
