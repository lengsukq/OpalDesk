import Foundation
import os

// MARK: - Daily Rate Limiter (5/day local natural day) & Duplicate Submit Guard

public struct FeedbackQuotaSnapshot: Sendable, Equatable {
    public let dateKey: String
    public let usedToday: Int
    public let maxDaily: Int

    public init(dateKey: String, usedToday: Int, maxDaily: Int) {
        self.dateKey = dateKey
        self.usedToday = usedToday
        self.maxDaily = maxDaily
    }

    public var remaining: Int { max(0, maxDaily - usedToday) }
    public var isExhausted: Bool { remaining <= 0 }
    public var remainingLabel: String { "今日剩余 \(remaining)/\(maxDaily) 次" }
}

public protocol FeedbackQuotaStorage: AnyObject, Sendable {
    func getOrCreateInstallationId() -> String
    func getLastReportDate() -> String
    func getDailyReportCount() -> Int
    func saveDailyReportState(dateYyyyMmDd: String, count: Int)
}

public final class UserDefaultsFeedbackQuotaStorage: FeedbackQuotaStorage, @unchecked Sendable {
    private enum Key {
        static let installationId = "opaldesk.feedback.installationId"
        static let lastReportDate = "opaldesk.feedback.lastReportDate"
        static let dailyReportCount = "opaldesk.feedback.dailyReportCount"
        static let reporterName = "opaldesk.feedback.reporterName"
    }

    private let defaults: UserDefaults
    private let lock = NSLock()

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func getOrCreateInstallationId() -> String {
        lock.withLock {
            if let existing = defaults.string(forKey: Key.installationId),
               existing.count >= 8, existing.count <= 80
            {
                return existing
            }
            let generated = "mac-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(24)
            defaults.set(generated, forKey: Key.installationId)
            return generated
        }
    }

    public func getLastReportDate() -> String {
        lock.withLock { defaults.string(forKey: Key.lastReportDate) ?? "" }
    }

    public func getDailyReportCount() -> Int {
        lock.withLock { defaults.integer(forKey: Key.dailyReportCount) }
    }

    public func saveDailyReportState(dateYyyyMmDd: String, count: Int) {
        lock.withLock {
            defaults.set(dateYyyyMmDd, forKey: Key.lastReportDate)
            defaults.set(count, forKey: Key.dailyReportCount)
        }
    }

    public func getReporterName() -> String {
        lock.withLock { defaults.string(forKey: Key.reporterName) ?? "" }
    }

    public func setReporterName(_ name: String) {
        lock.withLock { defaults.set(String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64)), forKey: Key.reporterName) }
    }
}

public final class FeedbackRateLimiter: Sendable {
    public static let maxDailyReports: Int = 5
    public static let limitReachedMessage = "今日反馈与日志上传次数已达上限（5/5），请明天再试"

    private let storage: FeedbackQuotaStorage
    private let maxDaily: Int
    private let dateProvider: @Sendable () -> String
    private let lock = NSLock()

    public init(
        storage: FeedbackQuotaStorage = UserDefaultsFeedbackQuotaStorage(),
        maxDaily: Int = FeedbackRateLimiter.maxDailyReports,
        dateProvider: @escaping @Sendable () -> String = { FeedbackRateLimiter.localDateString() }
    ) {
        self.storage = storage
        self.maxDaily = max(1, maxDaily)
        self.dateProvider = dateProvider
    }

    public func installationId() -> String {
        storage.getOrCreateInstallationId()
    }

    public func currentQuota() -> FeedbackQuotaSnapshot {
        lock.withLock {
            let today = dateProvider()
            let storedDate = storage.getLastReportDate().trimmingCharacters(in: .whitespacesAndNewlines)
            let used = storedDate == today ? min(max(0, storage.getDailyReportCount()), maxDaily) : 0
            return FeedbackQuotaSnapshot(dateKey: today, usedToday: used, maxDaily: maxDaily)
        }
    }

    public func recordConfirmedUpload() -> FeedbackQuotaSnapshot {
        lock.withLock {
            let today = dateProvider()
            let storedDate = storage.getLastReportDate().trimmingCharacters(in: .whitespacesAndNewlines)
            let used = storedDate == today ? min(max(0, storage.getDailyReportCount()), maxDaily) : 0
            let nextUsed = min(used + 1, maxDaily)
            storage.saveDailyReportState(dateYyyyMmDd: today, count: nextUsed)
            return FeedbackQuotaSnapshot(dateKey: today, usedToday: nextUsed, maxDaily: maxDaily)
        }
    }

    public var limitReachedMessage: String {
        maxDaily == Self.maxDailyReports
            ? Self.limitReachedMessage
            : "今日反馈与日志上传次数已达上限（\(maxDaily)/\(maxDaily)），请明天再试"
    }

    public static func localDateString(_ date: Date = Date()) -> String {
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", comps.year ?? 1970, comps.month ?? 1, comps.day ?? 1)
    }
}

public enum ManualReportSubmitGuard {
    public static func isSubmitAllowed(
        submitting: Bool,
        quotaExhausted: Bool,
        sentryConfigured: Bool,
        lastUploadSucceeded: Bool,
        currentDescription: String,
        lastSubmittedDescription: String?,
        currentKind: FeedbackKind = .bug,
        lastSubmittedKind: FeedbackKind? = nil
    ) -> Bool {
        if submitting || quotaExhausted || !sentryConfigured { return false }
        if lastUploadSucceeded,
           let lastDesc = lastSubmittedDescription,
           currentDescription.trimmingCharacters(in: .whitespacesAndNewlines) == lastDesc,
           (lastSubmittedKind == nil || currentKind == lastSubmittedKind)
        {
            return false
        }
        return true
    }
}

// MARK: - Build Config & Persistent History Store

public struct FeedbackBuildConfig: Sendable, Equatable {
    public let sentryDsn: String
    public let sentryTunnelUrl: String
    public let feedbackStatusUrl: String
    public let appVersion: String

    public init(
        sentryDsn: String,
        sentryTunnelUrl: String,
        feedbackStatusUrl: String,
        appVersion: String
    ) {
        self.sentryDsn = sentryDsn.trimmingCharacters(in: .whitespacesAndNewlines)
        self.sentryTunnelUrl = sentryTunnelUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        self.feedbackStatusUrl = feedbackStatusUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanVer = appVersion.trimmingCharacters(in: .whitespacesAndNewlines)
        self.appVersion = cleanVer.isEmpty ? "1.0.24" : cleanVer
    }

    public var isSentryConfigured: Bool { !sentryDsn.isEmpty }

    public var resolvedStatusBaseUrl: String {
        FeedbackHistoryLogic.resolveStatusBaseUrl(
            feedbackStatusUrl: feedbackStatusUrl,
            sentryTunnelUrl: sentryTunnelUrl
        )
    }

    public var isStatusSyncConfigured: Bool { !resolvedStatusBaseUrl.isEmpty }

    public static func current(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> FeedbackBuildConfig {
        func resolve(plistKey: String, envKeys: [String]) -> String {
            for k in envKeys {
                if let val = environment[k]?.trimmingCharacters(in: .whitespacesAndNewlines), !val.isEmpty {
                    return val
                }
            }
            if let val = bundle.object(forInfoDictionaryKey: plistKey) as? String {
                let trimmed = val.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { return trimmed }
            }
            return ""
        }

        let dsn = resolve(plistKey: "OpalDeskSentryDSN", envKeys: ["OPALDESK_SENTRY_DSN", "SENTRY_DSN"])
        let tunnel = resolve(plistKey: "OpalDeskSentryTunnelURL", envKeys: ["OPALDESK_SENTRY_TUNNEL_URL", "SENTRY_TUNNEL_URL"])
        let status = resolve(plistKey: "OpalDeskFeedbackStatusURL", envKeys: ["OPALDESK_FEEDBACK_STATUS_URL", "FEEDBACK_STATUS_URL"])
        let version = (bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? "1.0.24"
        return FeedbackBuildConfig(
            sentryDsn: dsn,
            sentryTunnelUrl: tunnel,
            feedbackStatusUrl: status,
            appVersion: version.isEmpty ? "1.0.24" : version
        )
    }
}

private struct FeedbackHistoryDisk: Codable, Sendable {
    var version: Int = 1
    var records: [FeedbackRecord]
}

/// Thread-safe persistent store for user-submitted feedback history (`feedback_history_v1.json`).
public final class FeedbackHistoryStore: Sendable {
    private let storageURL: URL
    private let state: OSAllocatedUnfairLock<[FeedbackRecord]>
    private let writeLock = NSLock()

    public static func defaultStorageURL() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
        return appSupport
            .appendingPathComponent("OpalDesk", isDirectory: true)
            .appendingPathComponent("feedback_history_v1.json")
    }

    public init(storageURL: URL = FeedbackHistoryStore.defaultStorageURL()) {
        self.storageURL = storageURL
        let loaded = Self.loadFromDisk(url: storageURL)
        self.state = OSAllocatedUnfairLock(initialState: loaded)
    }

    public func snapshot() -> [FeedbackRecord] {
        state.withLock { $0 }
    }

    public var lastCheckedAtMs: Int64 {
        state.withLock { $0.map(\.lastCheckedAtMs).max() ?? 0 }
    }

    @discardableResult
    public func recordSubmitted(
        eventId: String,
        shortCode: String,
        problemDescription: String,
        reportedVersion: String,
        createdAtMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        kind: FeedbackKind = .bug
    ) -> [FeedbackRecord] {
        mutateAndPersist { current in
            FeedbackHistoryLogic.recordSubmitted(
                existing: current,
                eventId: eventId,
                shortCode: shortCode,
                problemDescription: problemDescription,
                reportedVersion: reportedVersion,
                createdAtMs: createdAtMs,
                kind: kind
            )
        }
    }

    @discardableResult
    public func mergeRemoteStatuses(
        _ remoteItems: [String: FeedbackRemoteItem],
        checkedAtMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) -> [FeedbackRecord] {
        mutateAndPersist { current in
            FeedbackHistoryLogic.mergeRemoteStatuses(existing: current, remoteItems: remoteItems, checkedAtMs: checkedAtMs)
        }
    }

    @discardableResult
    public func upsertLookedUpRecord(
        _ remoteItem: FeedbackRemoteItem,
        checkedAtMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) -> [FeedbackRecord] {
        mutateAndPersist { current in
            FeedbackHistoryLogic.upsertLookedUpRecord(existing: current, remoteItem: remoteItem, checkedAtMs: checkedAtMs)
        }
    }

    @discardableResult
    public func removeRecord(eventId: String) -> [FeedbackRecord] {
        mutateAndPersist { current in
            current.filter { $0.eventId != eventId }
        }
    }

    public func clearAll() {
        _ = mutateAndPersist { _ in [] }
    }

    private func mutateAndPersist(_ transform: ([FeedbackRecord]) -> [FeedbackRecord]) -> [FeedbackRecord] {
        writeLock.withLock {
            let current = state.withLock { $0 }
            let next = transform(current)
            persist(next)
            state.withLock { $0 = next }
            return next
        }
    }

    private static func loadFromDisk(url: URL) -> [FeedbackRecord] {
        guard FileManager.default.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(FeedbackHistoryDisk.self, from: data)
        else {
            return []
        }
        return FeedbackHistoryLogic.normalizeList(decoded.records)
    }

    private func persist(_ records: [FeedbackRecord]) {
        let dir = storageURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? encoder.encode(FeedbackHistoryDisk(version: 1, records: records)) {
            try? data.write(to: storageURL, options: [.atomic])
        }
    }
}
