import Foundation
import os

/// Persisted record for a recently launched standalone Android floating app window.
public struct PopoutHistoryEntry: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var appPackage: String
    public var appActivity: String
    public var appLabel: String
    public var appUserId: Int
    public var appProfileType: String
    public var deviceKey: String
    public var deviceName: String
    public var transportKind: String // "usb" | "wifi" | "bonjour"
    public var adbSerial: String?
    public var host: String?
    public var port: UInt16?
    public var certFingerprint: String?
    public var bonjourServiceName: String?
    public var bonjourDomain: String?
    public var lastOpenedAtMs: Int64
    public var openCount: Int
    public var pinnedOnTop: Bool

    public init(
        appPackage: String,
        appActivity: String = "",
        appLabel: String,
        deviceKey: String,
        deviceName: String,
        transportKind: String,
        adbSerial: String? = nil,
        host: String? = nil,
        port: UInt16? = nil,
        certFingerprint: String? = nil,
        bonjourServiceName: String? = nil,
        bonjourDomain: String? = nil,
        lastOpenedAtMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        openCount: Int = 1,
        pinnedOnTop: Bool = false,
        appUserId: Int = 0,
        appProfileType: String = ""
    ) {
        let cleanPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDevice = deviceKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedUid = max(0, appUserId)
        let key = RemoteAppInfo.formatAppKey(pkg: cleanPkg, userId: normalizedUid)
        self.id = "\(cleanDevice)::\(key)"
        self.appPackage = cleanPkg
        self.appActivity = appActivity.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanLabel = appLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        self.appLabel = cleanLabel.isEmpty ? cleanPkg : cleanLabel
        self.appUserId = normalizedUid
        self.appProfileType = RemoteAppInfo.normalizeProfileType(appProfileType, userId: normalizedUid)
        self.deviceKey = cleanDevice
        self.deviceName = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        self.transportKind = transportKind
        self.adbSerial = adbSerial
        self.host = host
        self.port = port
        self.certFingerprint = certFingerprint.map { FingerprintUtil.normalize($0) }
        self.bonjourServiceName = bonjourServiceName
        self.bonjourDomain = bonjourDomain
        self.lastOpenedAtMs = lastOpenedAtMs
        self.openCount = max(1, openCount)
        self.pinnedOnTop = pinnedOnTop
    }

    private enum CodingKeys: String, CodingKey {
        case id, appPackage, appActivity, appLabel, appUserId, appProfileType
        case deviceKey, deviceName, transportKind, adbSerial, host, port
        case certFingerprint, bonjourServiceName, bonjourDomain
        case lastOpenedAtMs, openCount, pinnedOnTop
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let pkg = try c.decodeIfPresent(String.self, forKey: .appPackage) ?? ""
        let devKey = try c.decodeIfPresent(String.self, forKey: .deviceKey) ?? ""
        let uid = max(0, try c.decodeIfPresent(Int.self, forKey: .appUserId) ?? 0)
        let rawProfile = try c.decodeIfPresent(String.self, forKey: .appProfileType) ?? ""
        let canonicalKey = RemoteAppInfo.formatAppKey(pkg: pkg, userId: uid)
        self.id = try c.decodeIfPresent(String.self, forKey: .id) ?? "\(devKey)::\(canonicalKey)"
        self.appPackage = pkg
        self.appActivity = try c.decodeIfPresent(String.self, forKey: .appActivity) ?? ""
        self.appLabel = try c.decodeIfPresent(String.self, forKey: .appLabel) ?? pkg
        self.appUserId = uid
        self.appProfileType = RemoteAppInfo.normalizeProfileType(rawProfile, userId: uid)
        self.deviceKey = devKey
        self.deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        self.transportKind = try c.decodeIfPresent(String.self, forKey: .transportKind) ?? "wifi"
        self.adbSerial = try c.decodeIfPresent(String.self, forKey: .adbSerial)
        self.host = try c.decodeIfPresent(String.self, forKey: .host)
        self.port = try c.decodeIfPresent(UInt16.self, forKey: .port)
        self.certFingerprint = try c.decodeIfPresent(String.self, forKey: .certFingerprint)
        self.bonjourServiceName = try c.decodeIfPresent(String.self, forKey: .bonjourServiceName)
        self.bonjourDomain = try c.decodeIfPresent(String.self, forKey: .bonjourDomain)
        self.lastOpenedAtMs = try c.decodeIfPresent(Int64.self, forKey: .lastOpenedAtMs) ?? 0
        self.openCount = max(1, try c.decodeIfPresent(Int.self, forKey: .openCount) ?? 1)
        self.pinnedOnTop = try c.decodeIfPresent(Bool.self, forKey: .pinnedOnTop) ?? false
    }

    public var appKey: String {
        RemoteAppInfo.formatAppKey(pkg: appPackage, userId: appUserId)
    }

    public var isSecondaryProfile: Bool {
        appUserId > 0
    }

    public var profileBadgeText: String? {
        RemoteAppInfo.profileBadgeText(userId: appUserId, profileType: appProfileType)
    }

    public var displayLabel: String {
        appLabel.isEmpty ? appPackage : appLabel
    }
}

/// Pure history list mutation rules for standalone floating app windows.
public enum PopoutHistoryLogic {
    public static let maxEntries: Int = 16
    public static let maxConcurrentFloatingWindows: Int = 3

    public static func recordLaunch(
        existing: [PopoutHistoryEntry],
        incoming: PopoutHistoryEntry
    ) -> [PopoutHistoryEntry] {
        guard !incoming.appPackage.isEmpty, !incoming.deviceKey.isEmpty else {
            return normalize(existing)
        }
        var updated = incoming
        if let prev = existing.first(where: { $0.id == incoming.id }) {
            updated.openCount = prev.openCount + 1
            // Preserve user's remembered always-on-top preference unless explicitly overridden.
            updated.pinnedOnTop = prev.pinnedOnTop || incoming.pinnedOnTop
            if updated.appActivity.isEmpty { updated.appActivity = prev.appActivity }
            if updated.appProfileType.isEmpty { updated.appProfileType = prev.appProfileType }
            if updated.host == nil { updated.host = prev.host }
            if updated.port == nil { updated.port = prev.port }
            if (updated.certFingerprint ?? "").isEmpty { updated.certFingerprint = prev.certFingerprint }
            if updated.adbSerial == nil { updated.adbSerial = prev.adbSerial }
            if updated.bonjourServiceName == nil { updated.bonjourServiceName = prev.bonjourServiceName }
            if updated.bonjourDomain == nil { updated.bonjourDomain = prev.bonjourDomain }
        }
        let filtered = existing.filter { $0.id != updated.id }
        return normalize([updated] + filtered)
    }

    public static func updatePinPreference(
        existing: [PopoutHistoryEntry],
        appPackage: String,
        deviceKey: String?,
        pinnedOnTop: Bool,
        appUserId: Int = 0
    ) -> [PopoutHistoryEntry] {
        let cleanPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedUid = max(0, appUserId)
        guard !cleanPkg.isEmpty else { return existing }
        return existing.map { entry in
            let matchesPkg = entry.appPackage == cleanPkg && entry.appUserId == normalizedUid
            let matchesDevice = deviceKey == nil || entry.deviceKey == deviceKey
            guard matchesPkg && matchesDevice else { return entry }
            var copy = entry
            copy.pinnedOnTop = pinnedOnTop
            return copy
        }
    }

    public static func remove(existing: [PopoutHistoryEntry], id: String) -> [PopoutHistoryEntry] {
        existing.filter { $0.id != id }
    }

    public static func normalize(_ entries: [PopoutHistoryEntry]) -> [PopoutHistoryEntry] {
        var seen = Set<String>()
        return entries
            .sorted { $0.lastOpenedAtMs > $1.lastOpenedAtMs }
            .filter { !$0.appPackage.isEmpty && seen.insert($0.id).inserted }
            .prefix(maxEntries)
            .map { $0 }
    }
}

private struct PopoutHistoryDisk: Codable, Sendable {
    var version: Int = 1
    var entries: [PopoutHistoryEntry]
}

/// Thread-safe persistent store for recently launched standalone floating apps (`popout_history_v1.json`).
public final class PopoutHistoryStore: Sendable {
    private let storageURL: URL
    private let state: OSAllocatedUnfairLock<[PopoutHistoryEntry]>
    private let writeLock = NSLock()

    public static func defaultStorageURL() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
        return appSupport
            .appendingPathComponent("OpalDesk", isDirectory: true)
            .appendingPathComponent("popout_history_v1.json")
    }

    public init(storageURL: URL = PopoutHistoryStore.defaultStorageURL()) {
        self.storageURL = storageURL
        let loaded = Self.loadFromDisk(url: storageURL)
        self.state = OSAllocatedUnfairLock(initialState: loaded)
    }

    public func snapshot() -> [PopoutHistoryEntry] {
        state.withLock { $0 }
    }

    @discardableResult
    public func recordLaunch(_ entry: PopoutHistoryEntry) -> [PopoutHistoryEntry] {
        mutateAndPersist { current in
            PopoutHistoryLogic.recordLaunch(existing: current, incoming: entry)
        }
    }

    @discardableResult
    public func updatePinPreference(
        appPackage: String,
        deviceKey: String? = nil,
        pinnedOnTop: Bool,
        appUserId: Int = 0
    ) -> [PopoutHistoryEntry] {
        mutateAndPersist { current in
            PopoutHistoryLogic.updatePinPreference(
                existing: current,
                appPackage: appPackage,
                deviceKey: deviceKey,
                pinnedOnTop: pinnedOnTop,
                appUserId: appUserId
            )
        }
    }

    @discardableResult
    public func remove(id: String) -> [PopoutHistoryEntry] {
        mutateAndPersist { current in
            PopoutHistoryLogic.remove(existing: current, id: id)
        }
    }

    public func clearAll() {
        _ = mutateAndPersist { _ in [] }
    }

    private func mutateAndPersist(_ transform: ([PopoutHistoryEntry]) -> [PopoutHistoryEntry]) -> [PopoutHistoryEntry] {
        writeLock.withLock {
            let current = state.withLock { $0 }
            let next = transform(current)
            persist(next)
            state.withLock { $0 = next }
            return next
        }
    }

    private static func loadFromDisk(url: URL) -> [PopoutHistoryEntry] {
        guard FileManager.default.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(PopoutHistoryDisk.self, from: data)
        else {
            return []
        }
        return PopoutHistoryLogic.normalize(decoded.entries)
    }

    private func persist(_ entries: [PopoutHistoryEntry]) {
        let dir = storageURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? encoder.encode(PopoutHistoryDisk(version: 1, entries: entries)) {
            try? data.write(to: storageURL, options: [.atomic])
        }
    }
}
