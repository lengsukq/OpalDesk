package io.github.lengsukq.opaldesk.diagnostics

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BuildConfig
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class ManualReportOutcome(
    val success: Boolean,
    val eventId: String = "",
    val shortCode: String = "",
    val errorMessage: String = "",
    val quota: FeedbackQuotaSnapshot,
)

data class FeedbackRefreshOutcome(
    val success: Boolean,
    val updatedCount: Int = 0,
    val skippedByThrottle: Boolean = false,
    val message: String = "",
)

data class FeedbackCodeLookupOutcome(
    val success: Boolean,
    val found: Boolean = false,
    val item: FeedbackRemoteItem? = null,
    val message: String = "",
)

/**
 * Central Sentry runtime coordinator for OpalDesk Android (:helper).
 * - Initializes SentryAndroid SDK with privacy-first defaults (no PII, no screenshots).
 * - Coordinates manual diagnostic log uploads with strict 5/day local natural-day rate limiting
 *   and confirmed HTTP 2xx receipt before incrementing quota.
 * - Persists submitted feedback records locally and syncs resolution status / fixed versions.
 */
object OpalSentry {
    private val uploadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalSentryUpload").apply { isDaemon = true }
    }
    private val syncExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalFeedbackSync").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initialized = false

    fun isConfigured(): Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    fun resolvedStatusBaseUrl(): String =
        FeedbackHistoryLogic.resolveStatusBaseUrl(
            feedbackStatusUrl = BuildConfig.FEEDBACK_STATUS_URL,
            sentryTunnelUrl = BuildConfig.SENTRY_TUNNEL_URL,
        )

    fun isStatusSyncConfigured(): Boolean = resolvedStatusBaseUrl().isNotEmpty()

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val appCtx = ctx.applicationContext
            val dsn = BuildConfig.SENTRY_DSN.trim()
            if (dsn.isEmpty()) {
                OpalLogStore.i("Sentry", "SENTRY_DSN not configured in this build; Sentry SDK disabled")
                return
            }
            val installationId = AppPrefs.getOrCreateInstallationId(appCtx)
            val environment = if (BuildConfig.DEBUG) "debug" else "production"
            val release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            runCatching {
                SentryAndroid.init(appCtx) { options ->
                    options.dsn = dsn
                    options.environment = environment
                    options.release = release
                    options.isSendDefaultPii = false
                    options.isAttachScreenshot = false
                    options.isAttachViewHierarchy = false
                    options.isEnableAutoSessionTracking = true
                    options.isAnrEnabled = true
                    options.isAttachThreads = true
                    options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                        event.setTag("installation_id", installationId)
                        event.message?.let { msg ->
                            msg.formatted = msg.formatted?.let(DiagnosticSanitizer::sanitizeText)
                            msg.message = msg.message?.let(DiagnosticSanitizer::sanitizeText)
                        }
                        runCatching {
                            val st = BridgeClient.statusFlow.value
                            val sc = BridgeClient.sceneFlow.value
                            val ext = ExternalTrackpadBridge.sessionFlow.value
                            val floating = PeerFloatingAppService.activeFlow.value
                            event.setTag("daemon_connected", st.connected.toString())
                            event.setTag("input_mode", OpalLogFilter.inputBackendModeLabel(st.inputBackendMode))
                            event.setTag("inject_status", st.injectStatus.toString())
                            event.setTag("window_count", sc.windows.size.toString())
                            event.setTag("external_mode", ext.mode.name)
                            event.setTag("floating_active", (floating != null).toString())
                            val helperTail = OpalLogStore.recentTailLines(45).joinToString("\n")
                            if (helperTail.isNotBlank()) {
                                event.setExtra("recent_helper_logs", DiagnosticSanitizer.sanitizeText(helperTail))
                            }
                            val daemonTail = OpalLogStore.daemonLogsFlow.value
                                .lineSequence()
                                .filter { it.isNotBlank() }
                                .toList()
                                .takeLast(25)
                                .joinToString("\n")
                            if (daemonTail.isNotBlank()) {
                                event.setExtra("recent_daemon_logs", DiagnosticSanitizer.sanitizeText(daemonTail))
                            }
                        }
                        event
                    }
                }
                Sentry.configureScope { scope ->
                    scope.setTag("installation_id", installationId)
                    scope.user = User().apply {
                        id = installationId
                        val savedName = AppPrefs.getFeedbackUsername(appCtx)
                        if (savedName.isNotBlank()) {
                            username = savedName
                        }
                    }
                }
                OpalLogStore.entryListener = { entry ->
                    val crumb = Breadcrumb().apply {
                        category = entry.tag
                        message = DiagnosticSanitizer.sanitizeText(entry.message)
                        level = when (entry.level) {
                            OpalLogLevel.ERROR -> SentryLevel.ERROR
                            OpalLogLevel.WARN -> SentryLevel.WARNING
                            OpalLogLevel.INFO -> SentryLevel.INFO
                        }
                    }
                    Sentry.addBreadcrumb(crumb)
                }
                OpalLogStore.i("Sentry", "Sentry initialized (env=$environment, release=$release)")
            }.onFailure { t ->
                OpalLogStore.w("Sentry", "Sentry initialization failed", t)
            }
        }
    }

    fun createRateLimiter(ctx: Context): FeedbackRateLimiter =
        FeedbackRateLimiter(storage = AppPrefs.createFeedbackQuotaStorage(ctx))

    fun currentQuota(ctx: Context): FeedbackQuotaSnapshot =
        createRateLimiter(ctx).currentQuota()

    fun submitManualReportAsync(
        ctx: Context,
        reporterName: String,
        problemDescription: String,
        appVersion: String,
        status: DaemonStatus,
        scene: CompositorScene,
        remote: RemoteStreamStatus,
        helperEntries: List<OpalLogEntry>,
        daemonLogText: String,
        kind: FeedbackKind = FeedbackKind.BUG,
        includeLogs: Boolean = (kind == FeedbackKind.BUG),
        onComplete: (ManualReportOutcome) -> Unit,
    ) {
        val appCtx = ctx.applicationContext
        val cleanUser = reporterName.trim().take(64)
        val cleanDesc = problemDescription.trim().take(2000)
        if (cleanUser.isNotEmpty()) {
            AppPrefs.setFeedbackUsername(appCtx, cleanUser)
        }

        uploadExecutor.execute {
            val limiter = createRateLimiter(appCtx)
            val beforeQuota = limiter.currentQuota()
            if (beforeQuota.isExhausted) {
                dispatchMain(
                    onComplete,
                    ManualReportOutcome(
                        success = false,
                        errorMessage = limiter.limitReachedMessage(),
                        quota = beforeQuota,
                    ),
                )
                return@execute
            }

            val now = Date()
            val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
            val gzipBytes: ByteArray
            val helperLogCount: Int
            val daemonLineCount: Int
            if (includeLogs) {
                val deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
                val env = OpalLogStore.captureEnvironmentSnapshot(appCtx)
                val sanitizedReport = DiagnosticSanitizer.buildSanitizedReportText(
                    appVersion = appVersion,
                    deviceSummary = deviceSummary,
                    reporterName = cleanUser,
                    problemDescription = cleanDesc,
                    status = status,
                    scene = scene,
                    remote = remote,
                    helperEntries = helperEntries,
                    daemonLogText = daemonLogText,
                    generatedAtText = generatedAt,
                    env = env,
                )
                gzipBytes = DiagnosticSanitizer.gzipCompress(sanitizedReport)
                helperLogCount = helperEntries.size.coerceAtMost(DiagnosticSanitizer.DEFAULT_MAX_HELPER_ENTRIES)
                daemonLineCount = daemonLogText.lineSequence()
                    .count { it.isNotBlank() }
                    .coerceAtMost(DiagnosticSanitizer.DEFAULT_MAX_DAEMON_LINES)
            } else {
                gzipBytes = ByteArray(0)
                helperLogCount = 0
                daemonLineCount = 0
            }
            val installationId = limiter.installationId()

            val payload = ManualReportPayload(
                installationId = installationId,
                reporterName = cleanUser,
                problemDescription = cleanDesc,
                appVersion = appVersion,
                appVersionCode = BuildConfig.VERSION_CODE,
                environment = if (BuildConfig.DEBUG) "debug" else "production",
                deviceManufacturer = Build.MANUFACTURER.orEmpty(),
                deviceModel = Build.MODEL.orEmpty(),
                androidVersion = Build.VERSION.RELEASE.orEmpty(),
                androidApiLevel = Build.VERSION.SDK_INT,
                daemonConnected = status.connected,
                helperLogCount = helperLogCount,
                daemonLogCount = daemonLineCount,
                attachmentFilename = if (includeLogs) "opaldesk_diag_$fileStamp.log.gz" else "",
                compressedLogBytes = gzipBytes,
                kind = kind,
                includeLogs = includeLogs,
            )

            val uploadResult = SentryEnvelopeTransport.sendEnvelope(
                rawDsn = BuildConfig.SENTRY_DSN,
                tunnelUrl = BuildConfig.SENTRY_TUNNEL_URL,
                payload = payload,
            )

            val outcome = when (uploadResult) {
                is ManualUploadResult.Success -> {
                    val afterQuota = limiter.recordConfirmedUpload()
                    FeedbackHistoryStore.recordSubmitted(
                        ctx = appCtx,
                        eventId = uploadResult.eventId,
                        shortCode = uploadResult.shortCode,
                        problemDescription = cleanDesc,
                        reportedVersion = appVersion,
                        kind = kind,
                    )
                    if (includeLogs) {
                        OpalLogStore.clearAfterConfirmedUpload(daemonLogText)
                    }
                    OpalLogStore.i(
                        "Sentry",
                        "Manual ${kind.wireValue} feedback confirmed: eventId=${uploadResult.eventId} logs=$includeLogs (${afterQuota.usedToday}/${afterQuota.maxDaily})",
                    )
                    ManualReportOutcome(
                        success = true,
                        eventId = uploadResult.eventId,
                        shortCode = uploadResult.shortCode,
                        quota = afterQuota,
                    )
                }
                is ManualUploadResult.Failure -> {
                    OpalLogStore.w("Sentry", "Manual ${kind.wireValue} feedback failed: ${uploadResult.reason}")
                    ManualReportOutcome(
                        success = false,
                        errorMessage = uploadResult.reason,
                        quota = limiter.currentQuota(),
                    )
                }
            }
            dispatchMain(onComplete, outcome)
        }
    }

    fun refreshFeedbackStatusesAsync(
        ctx: Context,
        force: Boolean = false,
        onComplete: (FeedbackRefreshOutcome) -> Unit = {},
    ) {
        val appCtx = ctx.applicationContext
        FeedbackHistoryStore.init(appCtx)
        val baseUrl = resolvedStatusBaseUrl()
        if (baseUrl.isEmpty()) {
            dispatchMain(
                onComplete,
                FeedbackRefreshOutcome(success = false, message = "当前构建未配置状态查询服务"),
            )
            return
        }

        syncExecutor.execute {
            val nowMs = System.currentTimeMillis()
            val records = FeedbackHistoryStore.snapshot()
            if (!force && !FeedbackHistoryLogic.shouldAutoRefresh(records, nowMs)) {
                dispatchMain(
                    onComplete,
                    FeedbackRefreshOutcome(success = true, updatedCount = 0, skippedByThrottle = true),
                )
                return@execute
            }

            val isLookupRecord: (FeedbackRecord) -> Boolean = { rec ->
                rec.fromCodeLookup || rec.summary.startsWith("通过反馈码 #")
            }
            val eventIds = FeedbackHistoryLogic.selectEventIdsForBatchQuery(
                records.filterNot(isLookupRecord),
            )
            val lookupRecords = records.filter { isLookupRecord(it) && (!it.status.isTerminal || force) }.take(5)
            if (eventIds.isEmpty() && lookupRecords.isEmpty()) {
                dispatchMain(
                    onComplete,
                    FeedbackRefreshOutcome(success = true, updatedCount = 0),
                )
                return@execute
            }

            val mergedUpdates = LinkedHashMap<String, FeedbackRemoteItem>()
            var lastFailureMessage: String? = null

            if (eventIds.isNotEmpty()) {
                val installationId = AppPrefs.getOrCreateInstallationId(appCtx)
                when (val res = FeedbackStatusClient.fetchBatchStatuses(baseUrl, installationId, eventIds)) {
                    is FeedbackBatchSyncResult.Success -> mergedUpdates.putAll(res.items)
                    is FeedbackBatchSyncResult.Failure -> {
                        OpalLogStore.w("FeedbackSync", "Batch status sync failed: ${res.reason}")
                        lastFailureMessage = res.reason
                    }
                }
            }

            for (rec in lookupRecords) {
                when (val res = FeedbackStatusClient.lookupByShortCode(baseUrl, rec.shortCode)) {
                    is FeedbackLookupSyncResult.Found -> {
                        val key = FeedbackHistoryLogic.normalizeEventId(res.item.eventId)
                            .ifEmpty { rec.eventId }
                        mergedUpdates[key] = res.item
                    }
                    is FeedbackLookupSyncResult.NotFound -> Unit
                    is FeedbackLookupSyncResult.Failure -> {
                        OpalLogStore.w("FeedbackSync", "Lookup refresh failed for #${rec.shortCode}: ${res.reason}")
                        if (lastFailureMessage == null) lastFailureMessage = res.reason
                    }
                }
            }

            if (mergedUpdates.isNotEmpty()) {
                FeedbackHistoryStore.mergeRemoteStatuses(appCtx, mergedUpdates, System.currentTimeMillis())
                dispatchMain(
                    onComplete,
                    FeedbackRefreshOutcome(success = true, updatedCount = mergedUpdates.size),
                )
            } else if (lastFailureMessage != null) {
                dispatchMain(
                    onComplete,
                    FeedbackRefreshOutcome(success = false, message = lastFailureMessage),
                )
            } else {
                dispatchMain(
                    onComplete,
                    FeedbackRefreshOutcome(success = true, updatedCount = 0),
                )
            }
        }
    }

    fun lookupFeedbackByCodeAsync(
        ctx: Context,
        rawCode: String,
        onComplete: (FeedbackCodeLookupOutcome) -> Unit,
    ) {
        val appCtx = ctx.applicationContext
        FeedbackHistoryStore.init(appCtx)
        val baseUrl = resolvedStatusBaseUrl()
        if (baseUrl.isEmpty()) {
            dispatchMain(
                onComplete,
                FeedbackCodeLookupOutcome(success = false, message = "当前构建未配置状态查询服务"),
            )
            return
        }

        syncExecutor.execute {
            when (val res = FeedbackStatusClient.lookupByShortCode(baseUrl, rawCode)) {
                is FeedbackLookupSyncResult.Found -> {
                    FeedbackHistoryStore.upsertLookedUpRecord(appCtx, res.item, System.currentTimeMillis())
                    dispatchMain(
                        onComplete,
                        FeedbackCodeLookupOutcome(success = true, found = true, item = res.item),
                    )
                }
                is FeedbackLookupSyncResult.NotFound -> {
                    dispatchMain(
                        onComplete,
                        FeedbackCodeLookupOutcome(
                            success = true,
                            found = false,
                            message = "未查到该反馈码记录，请检查输入是否正确",
                        ),
                    )
                }
                is FeedbackLookupSyncResult.Failure -> {
                    dispatchMain(
                        onComplete,
                        FeedbackCodeLookupOutcome(success = false, message = res.reason),
                    )
                }
            }
        }
    }

    private fun <T> dispatchMain(
        callback: (T) -> Unit,
        value: T,
    ) {
        mainHandler.post { callback(value) }
    }
}
