package io.github.lengsukq.opaldesk.diagnostics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Thread-safe persistent store for user-submitted feedback records (`filesDir/feedback_history_v1.json`).
 * Keeps up to [FeedbackHistoryLogic.MAX_STORED_RECORDS] records with atomic temp-file writes and
 * reactive Compose observation via [recordsFlow].
 */
object FeedbackHistoryStore {
    private const val STORE_FILE_NAME = "feedback_history_v1.json"

    private val stateLock = Any()
    private val diskExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalFeedbackHistoryDisk").apply { isDaemon = true }
    }

    private val _recordsFlow = MutableStateFlow<List<FeedbackRecord>>(emptyList())
    val recordsFlow: StateFlow<List<FeedbackRecord>> = _recordsFlow.asStateFlow()

    private val _lastSyncAtMsFlow = MutableStateFlow(0L)
    val lastSyncAtMsFlow: StateFlow<Long> = _lastSyncAtMsFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var storeFile: File? = null

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val target = File(ctx.applicationContext.filesDir, STORE_FILE_NAME)
            storeFile = target
            diskExec.execute { loadFromDisk(target) }
        }
    }

    fun snapshot(): List<FeedbackRecord> = synchronized(stateLock) { _recordsFlow.value }

    fun recordSubmitted(
        ctx: Context,
        eventId: String,
        shortCode: String,
        problemDescription: String,
        reportedVersion: String,
        createdAtMs: Long = System.currentTimeMillis(),
        kind: FeedbackKind = FeedbackKind.BUG,
    ): List<FeedbackRecord> {
        init(ctx)
        return mutateAndPersist { current ->
            FeedbackHistoryLogic.recordSubmitted(
                existing = current,
                eventId = eventId,
                shortCode = shortCode,
                problemDescription = problemDescription,
                reportedVersion = reportedVersion,
                createdAtMs = createdAtMs,
                kind = kind,
            )
        }
    }

    fun mergeRemoteStatuses(
        ctx: Context,
        remoteItems: Map<String, FeedbackRemoteItem>,
        checkedAtMs: Long = System.currentTimeMillis(),
    ): List<FeedbackRecord> {
        init(ctx)
        val updated = mutateAndPersist { current ->
            FeedbackHistoryLogic.mergeRemoteStatuses(current, remoteItems, checkedAtMs)
        }
        _lastSyncAtMsFlow.value = checkedAtMs
        return updated
    }

    fun upsertLookedUpRecord(
        ctx: Context,
        remoteItem: FeedbackRemoteItem,
        checkedAtMs: Long = System.currentTimeMillis(),
    ): List<FeedbackRecord> {
        init(ctx)
        val updated = mutateAndPersist { current ->
            FeedbackHistoryLogic.upsertLookedUpRecord(current, remoteItem, checkedAtMs)
        }
        _lastSyncAtMsFlow.value = checkedAtMs
        return updated
    }

    fun removeRecord(ctx: Context, eventId: String): List<FeedbackRecord> {
        init(ctx)
        return mutateAndPersist { current ->
            current.filterNot { it.eventId == eventId }
        }
    }

    fun clearAll(ctx: Context) {
        init(ctx)
        mutateAndPersist { emptyList() }
        _lastSyncAtMsFlow.value = 0L
    }

    private inline fun mutateAndPersist(transform: (List<FeedbackRecord>) -> List<FeedbackRecord>): List<FeedbackRecord> {
        val next = synchronized(stateLock) {
            val updated = transform(_recordsFlow.value)
            _recordsFlow.value = updated
            updated
        }
        val target = storeFile
        if (target != null) {
            val json = FeedbackStatusParser.serializeRecordsJson(next)
            diskExec.execute { writeAtomically(target, json) }
        }
        return next
    }

    private fun loadFromDisk(file: File) {
        if (!file.isFile || file.length() <= 0L) return
        runCatching {
            val text = file.readText(StandardCharsets.UTF_8)
            val diskRecords = FeedbackStatusParser.parseRecordsJson(text)
            synchronized(stateLock) {
                val merged = if (_recordsFlow.value.isEmpty()) {
                    diskRecords
                } else {
                    FeedbackHistoryLogic.normalizeList(_recordsFlow.value + diskRecords)
                }
                _recordsFlow.value = merged
                _lastSyncAtMsFlow.value = merged.maxOfOrNull { it.lastCheckedAtMs } ?: 0L
            }
        }
    }

    private fun writeAtomically(target: File, content: String) {
        runCatching {
            target.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content, StandardCharsets.UTF_8)
            if (target.exists() && !tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            } else if (!target.exists() && !tmp.renameTo(target)) {
                target.writeText(content, StandardCharsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
