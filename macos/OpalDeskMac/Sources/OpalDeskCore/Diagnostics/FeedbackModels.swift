import Foundation
import os

/// Feedback category aligned with Android `FeedbackKind` & Cloudflare Worker `sentry_mapper.mjs`.
public enum FeedbackKind: String, Codable, Sendable, CaseIterable {
    case bug = "bug"
    case feature = "feature"

    public var wireValue: String { rawValue }

    public var label: String {
        switch self {
        case .bug: return "问题反馈"
        case .feature: return "功能建议"
        }
    }

    public static func fromWireValue(_ raw: String?) -> FeedbackKind {
        let clean = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return FeedbackKind(rawValue: clean) ?? .bug
    }
}

/// Resolution status aligned with Android `FeedbackResolutionStatus`.
public enum FeedbackResolutionStatus: String, Codable, Sendable, CaseIterable {
    case open = "open"
    case fixedPendingRelease = "fixed_pending_release"
    case fixedInVersion = "fixed_in_version"
    case resolvedOther = "resolved"
    case ignored = "ignored"

    public var wireValue: String { rawValue }

    public var isTerminal: Bool {
        self == .fixedInVersion || self == .resolvedOther || self == .ignored
    }

    public func displayLabel(for kind: FeedbackKind) -> String {
        if kind == .feature {
            switch self {
            case .open: return "评估中"
            case .fixedPendingRelease: return "已规划 · 待下版"
            case .fixedInVersion: return "已实现"
            case .resolvedOther: return "已采纳"
            case .ignored: return "暂不考虑"
            }
        }
        switch self {
        case .open: return "排查中"
        case .fixedPendingRelease: return "已修复 · 待下版发布"
        case .fixedInVersion: return "已修复"
        case .resolvedOther: return "已处理"
        case .ignored: return "已归档"
        }
    }

    public static func fromWireValue(_ raw: String?) -> FeedbackResolutionStatus {
        let clean = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return FeedbackResolutionStatus(rawValue: clean) ?? .open
    }
}

public enum FeedbackVersionRelation: Sendable, Equatable {
    case notFixedYet
    case fixedInCurrentOrOlder
    case fixedInNewerVersion
    case fixedVersionUnknown
}

public struct FeedbackRecord: Codable, Sendable, Equatable, Identifiable {
    public var eventId: String
    public var shortCode: String
    public var summary: String
    public var reportedVersion: String
    public var createdAtMs: Int64
    public var status: FeedbackResolutionStatus
    public var fixedVersion: String
    public var replyNote: String
    public var lastCheckedAtMs: Int64
    public var remoteUpdatedAt: String
    public var fromCodeLookup: Bool
    public var kind: FeedbackKind

    public var id: String { eventId }

    public init(
        eventId: String,
        shortCode: String,
        summary: String,
        reportedVersion: String,
        createdAtMs: Int64,
        status: FeedbackResolutionStatus = .open,
        fixedVersion: String = "",
        replyNote: String = "",
        lastCheckedAtMs: Int64 = 0,
        remoteUpdatedAt: String = "",
        fromCodeLookup: Bool = false,
        kind: FeedbackKind = .bug
    ) {
        self.eventId = eventId
        self.shortCode = shortCode
        self.summary = summary
        self.reportedVersion = reportedVersion
        self.createdAtMs = createdAtMs
        self.status = status
        self.fixedVersion = fixedVersion
        self.replyNote = replyNote
        self.lastCheckedAtMs = lastCheckedAtMs
        self.remoteUpdatedAt = remoteUpdatedAt
        self.fromCodeLookup = fromCodeLookup
        self.kind = kind
    }

    private enum CodingKeys: String, CodingKey {
        case eventId, shortCode, summary, reportedVersion, createdAtMs
        case status, fixedVersion, replyNote, lastCheckedAtMs, remoteUpdatedAt
        case fromCodeLookup, kind
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let rawEvent = FeedbackHistoryLogic.normalizeEventId(try c.decodeIfPresent(String.self, forKey: .eventId))
        let rawCode = FeedbackHistoryLogic.normalizeShortCode(try c.decodeIfPresent(String.self, forKey: .shortCode))
        let resolvedCode = rawCode.isEmpty && rawEvent.count >= 8 ? FeedbackHistoryLogic.formatShortCode(rawEvent) : rawCode
        let resolvedKey = rawEvent.isEmpty ? "code:\(resolvedCode)" : rawEvent
        self.eventId = resolvedKey
        self.shortCode = resolvedCode
        self.summary = try c.decodeIfPresent(String.self, forKey: .summary) ?? ""
        self.reportedVersion = try c.decodeIfPresent(String.self, forKey: .reportedVersion) ?? ""
        self.createdAtMs = try c.decodeIfPresent(Int64.self, forKey: .createdAtMs) ?? 0
        self.status = FeedbackResolutionStatus.fromWireValue(try c.decodeIfPresent(String.self, forKey: .status))
        self.fixedVersion = FeedbackStatusParser.extractSemver(try c.decodeIfPresent(String.self, forKey: .fixedVersion))
        self.replyNote = try c.decodeIfPresent(String.self, forKey: .replyNote) ?? ""
        self.lastCheckedAtMs = try c.decodeIfPresent(Int64.self, forKey: .lastCheckedAtMs) ?? 0
        self.remoteUpdatedAt = try c.decodeIfPresent(String.self, forKey: .remoteUpdatedAt) ?? ""
        self.fromCodeLookup = try c.decodeIfPresent(Bool.self, forKey: .fromCodeLookup) ?? false
        self.kind = FeedbackKind.fromWireValue(try c.decodeIfPresent(String.self, forKey: .kind))
    }
}

public struct FeedbackRemoteItem: Sendable, Equatable {
    public var eventId: String
    public var shortCode: String
    public var status: FeedbackResolutionStatus
    public var fixedVersion: String
    public var replyNote: String
    public var summary: String
    public var reportedVersion: String
    public var updatedAt: String
    public var kind: FeedbackKind

    public init(
        eventId: String = "",
        shortCode: String,
        status: FeedbackResolutionStatus,
        fixedVersion: String = "",
        replyNote: String = "",
        summary: String = "",
        reportedVersion: String = "",
        updatedAt: String = "",
        kind: FeedbackKind = .bug
    ) {
        self.eventId = eventId
        self.shortCode = shortCode
        self.status = status
        self.fixedVersion = fixedVersion
        self.replyNote = replyNote
        self.summary = summary
        self.reportedVersion = reportedVersion
        self.updatedAt = updatedAt
        self.kind = kind
    }
}

public struct FeedbackBatchStatusPayload: Sendable, Equatable {
    public let schema: Int
    public let generatedAt: String
    public let items: [String: FeedbackRemoteItem]
}

public struct FeedbackLookupPayload: Sendable, Equatable {
    public let schema: Int
    public let found: Bool
    public let item: FeedbackRemoteItem?
}

/// Pure semver comparison and Cloudflare Worker JSON parsers aligned with Android `FeedbackStatusParser`.
public enum FeedbackStatusParser {
    private static let semverRegex = try! NSRegularExpression(pattern: #"^\d+\.\d+(?:\.\d+)?(?:-[0-9A-Za-z.-]+)?$"#)

    public static func normalizeVersion(_ raw: String?) -> String {
        var s = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("v") || s.hasPrefix("V") {
            s.removeFirst()
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public static func extractSemver(_ raw: String?) -> String {
        var candidate = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !candidate.isEmpty else { return "" }
        if let atIdx = candidate.lastIndex(of: "@") {
            let next = candidate.index(after: atIdx)
            if next < candidate.endIndex {
                candidate = String(candidate[next...])
            }
        }
        if let plusIdx = candidate.firstIndex(of: "+") {
            candidate = String(candidate[..<plusIdx])
        }
        candidate = normalizeVersion(candidate)
        let range = NSRange(candidate.startIndex..<candidate.endIndex, in: candidate)
        return semverRegex.firstMatch(in: candidate, range: range) != nil ? candidate : ""
    }

    public static func isNewerVersion(current: String, candidate: String) -> Bool {
        compareVersions(candidate, current) > 0
    }

    public static func compareVersions(_ lhs: String, _ rhs: String) -> Int {
        let l = parseSemverParts(normalizeVersion(lhs))
        let r = parseSemverParts(normalizeVersion(rhs))
        let count = max(l.nums.count, r.nums.count)
        for i in 0..<count {
            let a = i < l.nums.count ? l.nums[i] : 0
            let b = i < r.nums.count ? r.nums[i] : 0
            if a != b { return a < b ? -1 : 1 }
        }
        if l.pre.isEmpty != r.pre.isEmpty {
            return l.pre.isEmpty ? 1 : -1
        }
        if l.pre == r.pre { return 0 }
        return l.pre < r.pre ? -1 : 1
    }

    public static func evaluateVersionRelation(
        currentAppVersion: String,
        record: FeedbackRecord
    ) -> FeedbackVersionRelation {
        guard record.status == .fixedInVersion else {
            return record.status == .resolvedOther ? .fixedVersionUnknown : .notFixedYet
        }
        let fixed = extractSemver(record.fixedVersion)
        guard !fixed.isEmpty else { return .fixedVersionUnknown }
        let current = extractSemver(currentAppVersion)
        guard !current.isEmpty else { return .fixedVersionUnknown }
        return isNewerVersion(current: current, candidate: fixed) ? .fixedInNewerVersion : .fixedInCurrentOrOlder
    }

    public static func parseBatchStatusJson(_ data: Data) -> FeedbackBatchStatusPayload? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let schema = root["schema"] as? Int
        else { return nil }
        let generatedAt = (root["generatedAt"] as? String) ?? ""
        let rawItems = (root["items"] as? [String: Any]) ?? [:]
        var parsed: [String: FeedbackRemoteItem] = [:]
        for (k, v) in rawItems {
            let eventId = FeedbackHistoryLogic.normalizeEventId(k)
            guard !eventId.isEmpty, let obj = v as? [String: Any] else { continue }
            if let item = parseRemoteItem(obj, fallbackEventId: eventId) {
                parsed[eventId] = item
            }
        }
        return FeedbackBatchStatusPayload(schema: schema, generatedAt: generatedAt, items: parsed)
    }

    public static func parseLookupJson(_ data: Data) -> FeedbackLookupPayload? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let schema = root["schema"] as? Int
        else { return nil }
        let found = (root["found"] as? Bool) == true
        let itemMap = root["item"] as? [String: Any]
        let item = found ? itemMap.flatMap { parseRemoteItem($0) } : nil
        return FeedbackLookupPayload(schema: schema, found: found && item != nil, item: item)
    }

    private static func parseRemoteItem(_ obj: [String: Any], fallbackEventId: String = "") -> FeedbackRemoteItem? {
        let rawId = FeedbackHistoryLogic.normalizeEventId(obj["eventId"] as? String)
        let eventId = rawId.isEmpty ? fallbackEventId : rawId
        let rawCode = FeedbackHistoryLogic.normalizeShortCode(obj["shortCode"] as? String)
        let shortCode = rawCode.isEmpty && eventId.count >= 8 ? FeedbackHistoryLogic.formatShortCode(eventId) : rawCode
        guard !shortCode.isEmpty || !eventId.isEmpty else { return nil }
        return FeedbackRemoteItem(
            eventId: eventId,
            shortCode: shortCode,
            status: FeedbackResolutionStatus.fromWireValue(obj["status"] as? String),
            fixedVersion: extractSemver(obj["fixedVersion"] as? String),
            replyNote: String(((obj["replyNote"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(240)),
            summary: String(((obj["summary"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(120)),
            reportedVersion: extractSemver(obj["reportedVersion"] as? String),
            updatedAt: ((obj["updatedAt"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
            kind: FeedbackKind.fromWireValue(obj["kind"] as? String)
        )
    }

    private static func parseSemverParts(_ version: String) -> (nums: [Int], pre: String) {
        let parts = version.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        let core = String(parts[0])
        let pre = parts.count > 1 ? String(parts[1]) : ""
        let nums = core.split(separator: ".").map { Int($0) ?? 0 }
        return (nums, pre)
    }
}

/// Pure history merging, throttling, and URL normalization logic aligned with Android `FeedbackHistoryLogic`.
public enum FeedbackHistoryLogic {
    public static let maxStoredRecords: Int = 50
    public static let maxBatchQueryIds: Int = 20
    public static let autoRefreshIntervalMs: Int64 = 10 * 60 * 1000
    public static let qqGroupNumber: String = "1042317917"

    private static let hexEventIdRegex = try! NSRegularExpression(pattern: "^[0-9a-f]{32}$")
    private static let hexShortCodeRegex = try! NSRegularExpression(pattern: "^[0-9A-F]{8}$")

    public static func formatShortCode(_ eventId: String) -> String {
        String(eventId.replacingOccurrences(of: "-", with: "").prefix(8)).uppercased()
    }

    public static func normalizeEventId(_ raw: String?) -> String {
        let cleaned = (raw ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "")
            .lowercased()
        let range = NSRange(cleaned.startIndex..<cleaned.endIndex, in: cleaned)
        return hexEventIdRegex.firstMatch(in: cleaned, range: range) != nil ? cleaned : ""
    }

    public static func normalizeShortCode(_ raw: String?) -> String {
        var cleaned = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("#") { cleaned.removeFirst() }
        cleaned = cleaned.uppercased()
        let range = NSRange(cleaned.startIndex..<cleaned.endIndex, in: cleaned)
        return hexShortCodeRegex.firstMatch(in: cleaned, range: range) != nil ? cleaned : ""
    }

    public static func summarizeProblemDescription(_ rawDescription: String, kind: FeedbackKind = .bug) -> String {
        let firstLine = DiagnosticSanitizer.sanitizeText(rawDescription)
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty } ?? ""
        let fallback = kind == .feature ? "功能建议" : "手动诊断日志上报"
        if firstLine.isEmpty { return fallback }
        if firstLine.count > 120 {
            return String(firstLine.prefix(119)).trimmingCharacters(in: .whitespaces) + "…"
        }
        return firstLine
    }

    public static func recordSubmitted(
        existing: [FeedbackRecord],
        eventId: String,
        shortCode: String,
        problemDescription: String,
        reportedVersion: String,
        createdAtMs: Int64,
        kind: FeedbackKind = .bug
    ) -> [FeedbackRecord] {
        let normId = normalizeEventId(eventId)
        let normCode = normalizeShortCode(shortCode).isEmpty && normId.count >= 8
            ? formatShortCode(normId)
            : normalizeShortCode(shortCode)
        guard !normId.isEmpty || !normCode.isEmpty else { return normalizeList(existing) }
        let key = normId.isEmpty ? "code:\(normCode)" : normId
        let record = FeedbackRecord(
            eventId: key,
            shortCode: normCode,
            summary: summarizeProblemDescription(problemDescription, kind: kind),
            reportedVersion: FeedbackStatusParser.normalizeVersion(reportedVersion),
            createdAtMs: createdAtMs,
            status: .open,
            kind: kind
        )
        let filtered = existing.filter {
            $0.eventId != key && (normCode.isEmpty || $0.shortCode.caseInsensitiveCompare(normCode) != .orderedSame)
        }
        return normalizeList([record] + filtered)
    }

    public static func mergeRemoteStatuses(
        existing: [FeedbackRecord],
        remoteItems: [String: FeedbackRemoteItem],
        checkedAtMs: Int64
    ) -> [FeedbackRecord] {
        guard !existing.isEmpty else { return [] }
        let updated = existing.map { rec -> FeedbackRecord in
            let remote = remoteItems[rec.eventId] ?? remoteItems.values.first {
                !rec.shortCode.isEmpty && $0.shortCode.caseInsensitiveCompare(rec.shortCode) == .orderedSame
            }
            guard let remote else { return rec }
            return mergeRecordWithRemote(rec, remote: remote, checkedAtMs: checkedAtMs)
        }
        return normalizeList(updated)
    }

    public static func upsertLookedUpRecord(
        existing: [FeedbackRecord],
        remoteItem: FeedbackRemoteItem,
        checkedAtMs: Int64
    ) -> [FeedbackRecord] {
        let normId = normalizeEventId(remoteItem.eventId)
        let normCode = normalizeShortCode(remoteItem.shortCode)
        guard !normId.isEmpty || !normCode.isEmpty else { return normalizeList(existing) }

        if let idx = existing.firstIndex(where: {
            (!normId.isEmpty && $0.eventId == normId)
                || (!normCode.isEmpty && $0.shortCode.caseInsensitiveCompare(normCode) == .orderedSame)
        }) {
            var mutable = existing
            var current = existing[idx]
            if !normId.isEmpty { current.eventId = normId }
            mutable[idx] = mergeRecordWithRemote(
                current,
                remote: remoteItem,
                checkedAtMs: checkedAtMs,
                markAsCodeLookup: current.fromCodeLookup
            )
            return normalizeList(mutable)
        }

        let synthetic = FeedbackRecord(
            eventId: normId.isEmpty ? "code:\(normCode)" : normId,
            shortCode: normCode,
            summary: remoteItem.summary.isEmpty ? "通过反馈码 #\(normCode) 找回的记录" : remoteItem.summary,
            reportedVersion: remoteItem.reportedVersion,
            createdAtMs: checkedAtMs,
            status: remoteItem.status,
            fixedVersion: remoteItem.fixedVersion,
            replyNote: remoteItem.replyNote,
            lastCheckedAtMs: checkedAtMs,
            remoteUpdatedAt: remoteItem.updatedAt,
            fromCodeLookup: true,
            kind: remoteItem.kind
        )
        return normalizeList([synthetic] + existing)
    }

    public static func selectEventIdsForBatchQuery(_ records: [FeedbackRecord]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for rec in records {
            let norm = normalizeEventId(rec.eventId)
            guard !norm.isEmpty, seen.insert(norm).inserted else { continue }
            out.append(norm)
            if out.count >= maxBatchQueryIds { break }
        }
        return out
    }

    public static func shouldAutoRefresh(
        records: [FeedbackRecord],
        nowMs: Int64,
        intervalMs: Int64 = autoRefreshIntervalMs
    ) -> Bool {
        let hasTargets = records.contains { !normalizeEventId($0.eventId).isEmpty && !$0.status.isTerminal }
        guard hasTargets else { return false }
        let latest = records.map(\.lastCheckedAtMs).max() ?? 0
        return latest <= 0 || (nowMs - latest) >= intervalMs
    }

    public static func normalizeList(_ records: [FeedbackRecord]) -> [FeedbackRecord] {
        var seen = Set<String>()
        return records
            .sorted { $0.createdAtMs > $1.createdAtMs }
            .filter { seen.insert($0.eventId).inserted }
            .prefix(maxStoredRecords)
            .map { $0 }
    }

    public static func resolveStatusBaseUrl(
        feedbackStatusUrl: String,
        sentryTunnelUrl: String,
        allowLocalhostForTests: Bool = false
    ) -> String {
        let explicit = cleanBaseUrl(feedbackStatusUrl, allowLocalhostForTests: allowLocalhostForTests)
        if !explicit.isEmpty { return explicit }
        guard let url = URL(string: sentryTunnelUrl.trimmingCharacters(in: .whitespacesAndNewlines)),
              let origin = buildOrigin(url, allowLocalhostForTests: allowLocalhostForTests)
        else { return "" }
        return origin
    }

    private static func isPlaceholderSummary(_ summary: String) -> Bool {
        summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || summary.hasPrefix("通过反馈码 #")
    }

    private static func mergeRecordWithRemote(
        _ current: FeedbackRecord,
        remote: FeedbackRemoteItem,
        checkedAtMs: Int64,
        markAsCodeLookup: Bool? = nil
    ) -> FeedbackRecord {
        let nextSummary = (isPlaceholderSummary(current.summary) && !remote.summary.isEmpty)
            ? remote.summary
            : current.summary
        let nextReported = current.reportedVersion.isEmpty ? remote.reportedVersion : current.reportedVersion
        let nextKind = remote.kind != .bug ? remote.kind : current.kind
        var copy = current
        copy.summary = nextSummary
        copy.reportedVersion = nextReported
        copy.status = remote.status
        copy.fixedVersion = remote.fixedVersion
        copy.replyNote = remote.replyNote
        copy.lastCheckedAtMs = checkedAtMs
        copy.remoteUpdatedAt = remote.updatedAt
        copy.fromCodeLookup = markAsCodeLookup ?? (current.fromCodeLookup || isPlaceholderSummary(current.summary))
        copy.kind = nextKind
        return copy
    }

    private static func cleanBaseUrl(_ raw: String, allowLocalhostForTests: Bool) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !trimmed.isEmpty,
              let url = URL(string: trimmed),
              let origin = buildOrigin(url, allowLocalhostForTests: allowLocalhostForTests)
        else { return "" }
        let path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return path.isEmpty ? origin : "\(origin)/\(path)"
    }

    private static func buildOrigin(_ url: URL, allowLocalhostForTests: Bool) -> String? {
        guard let scheme = url.scheme?.lowercased(),
              let host = url.host?.lowercased(), !host.isEmpty
        else { return nil }
        let isLocal = host == "127.0.0.1" || host == "localhost"
        guard scheme == "https" || (allowLocalhostForTests && scheme == "http" && isLocal) else { return nil }
        let portPart = url.port.map { ":\($0)" } ?? ""
        return "\(scheme)://\(host)\(portPart)"
    }
}

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
        self.appVersion = cleanVer.isEmpty ? "1.0.23" : cleanVer
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
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? "1.0.23"
        return FeedbackBuildConfig(
            sentryDsn: dsn,
            sentryTunnelUrl: tunnel,
            feedbackStatusUrl: status,
            appVersion: version.isEmpty ? "1.0.23" : version
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
