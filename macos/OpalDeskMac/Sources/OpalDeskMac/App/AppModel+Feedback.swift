import AppKit
import Foundation
import OpalDeskCore
import UniformTypeIdentifiers

public struct MacManualReportOutcome: Sendable, Equatable {
    public let success: Bool
    public let eventId: String
    public let shortCode: String
    public let errorMessage: String
    public let quota: FeedbackQuotaSnapshot
}

public struct MacFeedbackRefreshOutcome: Sendable, Equatable {
    public let success: Bool
    public let updatedCount: Int
    public let skippedByThrottle: Bool
    public let message: String
}

public struct MacFeedbackLookupOutcome: Sendable, Equatable {
    public let success: Bool
    public let found: Bool
    public let item: FeedbackRemoteItem?
    public let message: String
}

// MARK: - Diagnostics Logs, Manual Bug/Feature Feedback & Resolution Status Tracking

extension AppModel {
    public var appVersionString: String {
        feedbackConfig.appVersion
    }

    public var macDeviceSummary: String {
        let osVer = ProcessInfo.processInfo.operatingSystemVersionString
        #if arch(arm64)
        let arch = "Apple Silicon (arm64)"
        #else
        let arch = "Intel (x86_64)"
        #endif
        return "macOS \(osVer) · \(arch)"
    }

    public var isSentryConfigured: Bool {
        feedbackConfig.isSentryConfigured
    }

    public var isFeedbackStatusSyncConfigured: Bool {
        feedbackConfig.isStatusSyncConfigured
    }

    public func currentDiagnosticContext() -> MacDiagnosticContext {
        let connStr: String
        let transportStr: String
        if let conn = activeConnection {
            connStr = isAuthenticated ? OpalL10n.format("app.feedback.connected_fmt", conn.deviceName) : OpalL10n.format("app.feedback.connecting_fmt", conn.deviceName)
            transportStr = conn.transport.shortBadge
        } else {
            connStr = OpalL10n.text("common.disconnected")
            transportStr = "-"
        }
        let videoStr = isAuthenticated
            ? "\(currentVideoWidth)x\(currentVideoHeight) @\(Int(videoMetrics.currentFPS.rounded()))fps rtt=\(Int(rttMs.rounded()))ms"
            : "-"
        let popoutStr = "\(popoutManager.controllers.count)/\(PopoutWindowManager.maxConcurrentWindows)"
        let discStr = "usb=\(discovery.usbDevices.count) bonjour=\(discovery.bonjourDevices.count) trusted=\(discovery.trustedDevices.count)"
        return MacDiagnosticContext(
            connectionSummary: connStr,
            transportSummary: transportStr,
            videoSummary: videoStr,
            popoutSummary: popoutStr,
            discoverySummary: discStr
        )
    }

    public func refreshFeedbackQuota() {
        feedbackQuota = feedbackRateLimiter.currentQuota()
    }

    public func buildDiagnosticReportText(warningsOnly: Bool = false, query: String = "") -> String {
        let filtered = OpalLogFilter.filterEntries(logEntries, warningsAndErrorsOnly: warningsOnly, query: query)
        return OpalLogFilter.buildDiagnosticReport(
            appVersion: appVersionString,
            deviceSummary: macDeviceSummary,
            context: currentDiagnosticContext(),
            entries: filtered,
            generatedAtText: Self.formatNowDateTime()
        )
    }

    public func copyDiagnosticReport(warningsOnly: Bool = false, query: String = "") {
        let report = buildDiagnosticReportText(warningsOnly: warningsOnly, query: query)
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(report, forType: .string)
        showToast(OpalL10n.text("app.feedback.copied_report"))
    }

    public func exportDiagnosticReport(warningsOnly: Bool = false, query: String = "") {
        let report = buildDiagnosticReportText(warningsOnly: warningsOnly, query: query)
        let panel = NSSavePanel()
        panel.title = OpalL10n.text("app.feedback.export_title")
        panel.nameFieldStringValue = "opaldesk_mac_diag_\(Self.formatFileStamp()).txt"
        panel.allowedContentTypes = [.plainText]
        panel.canCreateDirectories = true
        if panel.runModal() == .OK, let url = panel.url {
            do {
                try report.write(to: url, atomically: true, encoding: .utf8)
                OpalDeskLog.info("feedback", "Exported diagnostic log report to \(url.lastPathComponent)")
                showToast(OpalL10n.format("app.feedback.exported_fmt", url.lastPathComponent))
            } catch {
                OpalDeskLog.warn("feedback", "Export diagnostic report failed: \(error.localizedDescription)")
                bannerError = OpalL10n.format("app.feedback.export_failed_fmt", error.localizedDescription)
            }
        }
    }

    public func clearClientLogs() {
        OpalLogStore.shared.clear()
        logEntries = []
        showToast(OpalL10n.text("app.feedback.cleared_logs"))
    }

    public func copyQqGroupNumber() {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(FeedbackHistoryLogic.qqGroupNumber, forType: .string)
        showToast(OpalL10n.format("app.feedback.copied_qq_fmt", FeedbackHistoryLogic.qqGroupNumber))
    }

    public func submitManualFeedback(
        reporterName: String,
        problemDescription: String,
        kind: FeedbackKind,
        includeLogs: Bool
    ) async -> MacManualReportOutcome {
        let cleanUser = String(reporterName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
        let cleanDesc = String(problemDescription.trimmingCharacters(in: .whitespacesAndNewlines).prefix(2000))
        if !cleanUser.isEmpty {
            feedbackQuotaStorage.setReporterName(cleanUser)
            savedFeedbackReporterName = cleanUser
        }

        let beforeQuota = feedbackRateLimiter.currentQuota()
        feedbackQuota = beforeQuota
        if beforeQuota.isExhausted {
            return MacManualReportOutcome(
                success: false,
                eventId: "",
                shortCode: "",
                errorMessage: feedbackRateLimiter.limitReachedMessage,
                quota: beforeQuota
            )
        }

        let gzipBytes: Data
        let attachedLogCount: Int
        let stamp = Self.formatFileStamp()
        if includeLogs {
            let entries = OpalLogStore.shared.snapshot()
            let sanitized = DiagnosticSanitizer.buildSanitizedReportText(
                appVersion: appVersionString,
                deviceSummary: macDeviceSummary,
                reporterName: cleanUser,
                problemDescription: cleanDesc,
                context: currentDiagnosticContext(),
                entries: entries,
                generatedAtText: Self.formatNowDateTime()
            )
            gzipBytes = DiagnosticSanitizer.gzipCompress(sanitized)
            attachedLogCount = min(entries.count, DiagnosticSanitizer.defaultMaxLogEntries)
        } else {
            gzipBytes = Data()
            attachedLogCount = 0
        }

        let installationId = feedbackRateLimiter.installationId()
        #if DEBUG
        let envName = "debug"
        #else
        let envName = "production"
        #endif

        let payload = ManualReportPayload(
            installationId: installationId,
            reporterName: cleanUser,
            problemDescription: cleanDesc,
            appVersion: appVersionString,
            environment: envName,
            osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            deviceModel: Self.macHardwareModel(),
            connectionActive: isAuthenticated,
            clientLogCount: attachedLogCount,
            attachmentFilename: includeLogs ? "opaldesk_mac_diag_\(stamp).log.gz" : "",
            compressedLogBytes: gzipBytes,
            kind: kind,
            includeLogs: includeLogs
        )

        let uploadResult = await SentryEnvelopeTransport.sendEnvelope(
            rawDsn: feedbackConfig.sentryDsn,
            tunnelUrl: feedbackConfig.sentryTunnelUrl,
            payload: payload
        )

        switch uploadResult {
        case let .success(eventId, shortCode, _):
            let afterQuota = feedbackRateLimiter.recordConfirmedUpload()
            feedbackQuota = afterQuota
            feedbackRecords = feedbackHistoryStore.recordSubmitted(
                eventId: eventId,
                shortCode: shortCode,
                problemDescription: cleanDesc,
                reportedVersion: appVersionString,
                kind: kind
            )
            if includeLogs {
                OpalLogStore.shared.clear()
                logEntries = []
            }
            OpalDeskLog.info("feedback", "Manual \(kind.wireValue) feedback confirmed eventId=\(eventId) logs=\(includeLogs) (\(afterQuota.usedToday)/\(afterQuota.maxDaily))")
            return MacManualReportOutcome(
                success: true,
                eventId: eventId,
                shortCode: shortCode,
                errorMessage: "",
                quota: afterQuota
            )
        case let .failure(reason, _):
            OpalDeskLog.warn("feedback", "Manual \(kind.wireValue) feedback failed: \(reason)")
            let current = feedbackRateLimiter.currentQuota()
            feedbackQuota = current
            return MacManualReportOutcome(
                success: false,
                eventId: "",
                shortCode: "",
                errorMessage: reason,
                quota: current
            )
        }
    }

    public func refreshFeedbackStatuses(force: Bool = false) async -> MacFeedbackRefreshOutcome {
        let baseUrl = feedbackConfig.resolvedStatusBaseUrl
        guard !baseUrl.isEmpty else {
            return MacFeedbackRefreshOutcome(success: false, updatedCount: 0, skippedByThrottle: false, message: OpalL10n.text("app.feedback.unconfigured"))
        }

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let records = feedbackHistoryStore.snapshot()
        if !force && !FeedbackHistoryLogic.shouldAutoRefresh(records: records, nowMs: nowMs) {
            return MacFeedbackRefreshOutcome(success: true, updatedCount: 0, skippedByThrottle: true, message: "")
        }

        let isLookupRecord: (FeedbackRecord) -> Bool = { rec in
            rec.fromCodeLookup || rec.summary.hasPrefix("通过反馈码 #")
        }
        let eventIds = FeedbackHistoryLogic.selectEventIdsForBatchQuery(records.filter { !isLookupRecord($0) })
        let lookupRecords = records.filter { isLookupRecord($0) && (!$0.status.isTerminal || force) }.prefix(5)
        if eventIds.isEmpty && lookupRecords.isEmpty {
            return MacFeedbackRefreshOutcome(success: true, updatedCount: 0, skippedByThrottle: false, message: "")
        }

        var mergedUpdates: [String: FeedbackRemoteItem] = [:]
        var lastError: String?

        if !eventIds.isEmpty {
            let res = await FeedbackStatusClient.fetchBatchStatuses(
                baseUrl: baseUrl,
                installationId: feedbackRateLimiter.installationId(),
                eventIds: eventIds
            )
            switch res {
            case let .success(items, _):
                for (k, v) in items { mergedUpdates[k] = v }
            case let .failure(reason, _):
                OpalDeskLog.warn("feedback", "Batch feedback status sync failed: \(reason)")
                lastError = reason
            }
        }

        for rec in lookupRecords {
            let res = await FeedbackStatusClient.lookupByShortCode(baseUrl: baseUrl, rawCode: rec.shortCode)
            switch res {
            case .found(let item):
                let key = FeedbackHistoryLogic.normalizeEventId(item.eventId)
                mergedUpdates[key.isEmpty ? rec.eventId : key] = item
            case .notFound:
                break
            case let .failure(reason, _):
                OpalDeskLog.warn("feedback", "Lookup refresh failed for #\(rec.shortCode): \(reason)")
                if lastError == nil { lastError = reason }
            }
        }

        if !mergedUpdates.isEmpty {
            let checkedAt = Int64(Date().timeIntervalSince1970 * 1000)
            feedbackRecords = feedbackHistoryStore.mergeRemoteStatuses(mergedUpdates, checkedAtMs: checkedAt)
            lastFeedbackSyncAtMs = checkedAt
            return MacFeedbackRefreshOutcome(success: true, updatedCount: mergedUpdates.count, skippedByThrottle: false, message: "")
        }
        if let lastError {
            return MacFeedbackRefreshOutcome(success: false, updatedCount: 0, skippedByThrottle: false, message: lastError)
        }
        return MacFeedbackRefreshOutcome(success: true, updatedCount: 0, skippedByThrottle: false, message: "")
    }

    public func lookupFeedbackByCode(_ rawCode: String) async -> MacFeedbackLookupOutcome {
        let baseUrl = feedbackConfig.resolvedStatusBaseUrl
        guard !baseUrl.isEmpty else {
            return MacFeedbackLookupOutcome(success: false, found: false, item: nil, message: OpalL10n.text("app.feedback.unconfigured"))
        }
        let res = await FeedbackStatusClient.lookupByShortCode(baseUrl: baseUrl, rawCode: rawCode)
        switch res {
        case .found(let item):
            let checkedAt = Int64(Date().timeIntervalSince1970 * 1000)
            feedbackRecords = feedbackHistoryStore.upsertLookedUpRecord(item, checkedAtMs: checkedAt)
            lastFeedbackSyncAtMs = checkedAt
            return MacFeedbackLookupOutcome(success: true, found: true, item: item, message: "")
        case .notFound:
            return MacFeedbackLookupOutcome(success: true, found: false, item: nil, message: OpalL10n.text("app.feedback.lookup_not_found"))
        case let .failure(reason, _):
            return MacFeedbackLookupOutcome(success: false, found: false, item: nil, message: reason)
        }
    }

    public func removeFeedbackRecord(eventId: String) {
        feedbackRecords = feedbackHistoryStore.removeRecord(eventId: eventId)
    }

    // MARK: - Formatting Helpers

    private static func formatNowDateTime() -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return df.string(from: Date())
    }

    private static func formatFileStamp() -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyyMMdd_HHmmss"
        return df.string(from: Date())
    }

    private static func macHardwareModel() -> String {
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        guard size > 0 else { return "Mac" }
        var buf = [UInt8](repeating: 0, count: size)
        sysctlbyname("hw.model", &buf, &size, nil, 0)
        let bytes = buf.prefix { $0 != 0 }
        return String(decoding: bytes, as: UTF8.self)
    }
}
