import Foundation

// MARK: - Notification Payloads (0x19 Downlink / 0x31 Uplink)

/// Action verb inside downlink `0x19 NOTIFICATION_EVENT`.
public enum NotificationEventAction: String, Codable, Sendable, CaseIterable, Equatable {
    case snapshot = "snapshot"
    case posted = "posted"
    case removed = "removed"
}

/// An interactive button action attached to an Android status-bar notification (e.g. "Mark as Read").
public struct RemoteNotificationAction: Codable, Sendable, Equatable, Hashable, Identifiable {
    public var id: Int { index }
    public var index: Int
    public var label: String

    public init(index: Int, label: String) {
        self.index = index
        self.label = label
    }

    private enum CodingKeys: String, CodingKey {
        case index, label
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        index = try c.decodeIfPresent(Int.self, forKey: .index) ?? 0
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
    }
}

/// A single Android status bar notification synced over `0x19 NOTIFICATION_EVENT`.
public struct RemoteNotificationItem: Codable, Sendable, Equatable, Identifiable, Hashable {
    public var id: String { key }
    public var key: String
    public var pkg: String
    public var appLabel: String
    public var title: String
    public var text: String
    public var postTimeMs: Int64
    public var clearable: Bool
    public var canReply: Bool
    public var replyLabel: String
    public var otpCode: String
    public var actions: [RemoteNotificationAction]

    public var displayAppLabel: String {
        appLabel.isEmpty ? pkg : appLabel
    }

    public init(
        key: String,
        pkg: String,
        appLabel: String = "",
        title: String = "",
        text: String = "",
        postTimeMs: Int64 = 0,
        clearable: Bool = true,
        canReply: Bool = false,
        replyLabel: String = "",
        otpCode: String = "",
        actions: [RemoteNotificationAction] = []
    ) {
        self.key = key
        self.pkg = pkg
        self.appLabel = appLabel
        self.title = title
        self.text = text
        self.postTimeMs = postTimeMs
        self.clearable = clearable
        self.canReply = canReply
        self.replyLabel = replyLabel
        self.otpCode = otpCode
        self.actions = actions
    }

    private enum CodingKeys: String, CodingKey {
        case key, pkg, appLabel, title, text, postTimeMs, clearable, canReply, replyLabel, otpCode, actions
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decodeIfPresent(String.self, forKey: .key) ?? ""
        pkg = try c.decodeIfPresent(String.self, forKey: .pkg) ?? ""
        appLabel = try c.decodeIfPresent(String.self, forKey: .appLabel) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        postTimeMs = try c.decodeIfPresent(Int64.self, forKey: .postTimeMs) ?? 0
        clearable = try c.decodeIfPresent(Bool.self, forKey: .clearable) ?? true
        canReply = try c.decodeIfPresent(Bool.self, forKey: .canReply) ?? false
        replyLabel = try c.decodeIfPresent(String.self, forKey: .replyLabel) ?? ""
        otpCode = try c.decodeIfPresent(String.self, forKey: .otpCode) ?? ""
        actions = try c.decodeIfPresent([RemoteNotificationAction].self, forKey: .actions) ?? []
    }
}

/// Downlink `0x19 NOTIFICATION_EVENT` UTF-8 JSON payload.
public struct NotificationEventPayload: Codable, Sendable, Equatable {
    public var action: NotificationEventAction
    public var key: String?
    public var items: [RemoteNotificationItem]

    public init(
        action: NotificationEventAction,
        key: String? = nil,
        items: [RemoteNotificationItem] = []
    ) {
        self.action = action
        self.key = key
        self.items = items
    }

    private enum CodingKeys: String, CodingKey {
        case action, key, items
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let rawAction = (try c.decodeIfPresent(String.self, forKey: .action) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parsed = NotificationEventAction(rawValue: rawAction) else {
            throw OpalCastCodecError.invalidJSON("NOTIFICATION_EVENT: unsupported action '\(rawAction)'")
        }
        action = parsed
        key = try c.decodeIfPresent(String.self, forKey: .key)
        items = try c.decodeIfPresent([RemoteNotificationItem].self, forKey: .items) ?? []
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "NOTIFICATION_EVENT")
    }

    public static func decode(from data: Data) throws -> NotificationEventPayload {
        try OpalCastJSONCodec.decode(NotificationEventPayload.self, from: data, context: "NOTIFICATION_EVENT")
    }
}

/// Operation verb for uplink `0x31 NOTIFICATION_ACTION`.
public enum NotificationActionOp: String, Codable, Sendable, CaseIterable, Equatable {
    case sync = "sync"
    case dismiss = "dismiss"
    case clearAll = "clear_all"
    case reply = "reply"
    case invokeAction = "invoke_action"
}

/// Uplink `0x31 NOTIFICATION_ACTION` UTF-8 JSON payload.
public struct NotificationActionPayload: Codable, Sendable, Equatable {
    public var op: NotificationActionOp
    public var key: String?
    public var replyText: String?
    public var actionIndex: Int?

    public init(
        op: NotificationActionOp,
        key: String? = nil,
        replyText: String? = nil,
        actionIndex: Int? = nil
    ) {
        self.op = op
        self.key = key
        self.replyText = replyText
        self.actionIndex = actionIndex
    }

    public static func sync() -> NotificationActionPayload {
        NotificationActionPayload(op: .sync)
    }

    public static func dismiss(key: String) -> NotificationActionPayload {
        NotificationActionPayload(op: .dismiss, key: key)
    }

    public static func clearAll() -> NotificationActionPayload {
        NotificationActionPayload(op: .clearAll)
    }

    public static func reply(key: String, text: String) -> NotificationActionPayload {
        NotificationActionPayload(op: .reply, key: key, replyText: text)
    }

    public static func invokeAction(key: String, actionIndex: Int) -> NotificationActionPayload {
        NotificationActionPayload(op: .invokeAction, key: key, actionIndex: actionIndex)
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "NOTIFICATION_ACTION")
    }

    public static func decode(from data: Data) throws -> NotificationActionPayload {
        try OpalCastJSONCodec.decode(NotificationActionPayload.self, from: data, context: "NOTIFICATION_ACTION")
    }
}

// MARK: - Media Control Payloads (0x1A Downlink / 0x32 Uplink)

/// Downlink `0x1A MEDIA_STATE` UTF-8 JSON payload mirroring Android active MediaSession metadata.
public struct MediaStatePayload: Codable, Sendable, Equatable {
    public var active: Bool
    public var pkg: String
    public var appLabel: String
    public var title: String
    public var artist: String
    public var album: String
    public var playing: Bool
    public var positionMs: Int64
    public var durationMs: Int64
    public var artworkBase64: String

    public var artworkData: Data? {
        guard !artworkBase64.isEmpty else { return nil }
        return Data(base64Encoded: artworkBase64)
    }

    public init(
        active: Bool = false,
        pkg: String = "",
        appLabel: String = "",
        title: String = "",
        artist: String = "",
        album: String = "",
        playing: Bool = false,
        positionMs: Int64 = 0,
        durationMs: Int64 = 0,
        artworkBase64: String = ""
    ) {
        self.active = active
        self.pkg = pkg
        self.appLabel = appLabel
        self.title = title
        self.artist = artist
        self.album = album
        self.playing = playing
        self.positionMs = max(0, positionMs)
        self.durationMs = max(0, durationMs)
        self.artworkBase64 = artworkBase64
    }

    private enum CodingKeys: String, CodingKey {
        case active, pkg, appLabel, title, artist, album, playing, positionMs, durationMs, artworkBase64
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        active = try c.decodeIfPresent(Bool.self, forKey: .active) ?? false
        pkg = try c.decodeIfPresent(String.self, forKey: .pkg) ?? ""
        appLabel = try c.decodeIfPresent(String.self, forKey: .appLabel) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        artist = try c.decodeIfPresent(String.self, forKey: .artist) ?? ""
        album = try c.decodeIfPresent(String.self, forKey: .album) ?? ""
        playing = try c.decodeIfPresent(Bool.self, forKey: .playing) ?? false
        positionMs = max(0, try c.decodeIfPresent(Int64.self, forKey: .positionMs) ?? 0)
        durationMs = max(0, try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0)
        artworkBase64 = try c.decodeIfPresent(String.self, forKey: .artworkBase64) ?? ""
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "MEDIA_STATE")
    }

    public static func decode(from data: Data) throws -> MediaStatePayload {
        try OpalCastJSONCodec.decode(MediaStatePayload.self, from: data, context: "MEDIA_STATE")
    }
}

/// Operation verb for uplink `0x32 MEDIA_ACTION`.
public enum MediaActionOp: String, Codable, Sendable, CaseIterable, Equatable {
    case play = "play"
    case pause = "pause"
    case toggle = "toggle"
    case next = "next"
    case previous = "previous"
    case stop = "stop"
    case seek = "seek"
}

/// Uplink `0x32 MEDIA_ACTION` UTF-8 JSON payload.
public struct MediaActionPayload: Codable, Sendable, Equatable {
    public var op: MediaActionOp
    public var positionMs: Int64?

    public init(op: MediaActionOp, positionMs: Int64? = nil) {
        self.op = op
        self.positionMs = positionMs
    }

    public static func play() -> MediaActionPayload { MediaActionPayload(op: .play) }
    public static func pause() -> MediaActionPayload { MediaActionPayload(op: .pause) }
    public static func toggle() -> MediaActionPayload { MediaActionPayload(op: .toggle) }
    public static func next() -> MediaActionPayload { MediaActionPayload(op: .next) }
    public static func previous() -> MediaActionPayload { MediaActionPayload(op: .previous) }
    public static func stop() -> MediaActionPayload { MediaActionPayload(op: .stop) }
    public static func seek(positionMs: Int64) -> MediaActionPayload {
        MediaActionPayload(op: .seek, positionMs: max(0, positionMs))
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "MEDIA_ACTION")
    }

    public static func decode(from data: Data) throws -> MediaActionPayload {
        try OpalCastJSONCodec.decode(MediaActionPayload.self, from: data, context: "MEDIA_ACTION")
    }
}

// MARK: - Remote File Management & Pull Payloads (0x33 Uplink / 0x1B & 0x1C Downlink)

/// Operation verb for uplink `0x33 FILE_OP_REQUEST`.
public enum FileOpKind: String, Codable, Sendable, CaseIterable, Equatable {
    case list = "list"
    case stat = "stat"
    case delete = "delete"
    case rename = "rename"
    case mkdir = "mkdir"
    case storageScan = "storage_scan"
    case pull = "pull"
    case thumbnail = "thumbnail"
}

/// Uplink `0x33 FILE_OP_REQUEST` UTF-8 JSON payload.
public struct FileOpRequestPayload: Codable, Sendable, Equatable {
    public var requestId: Int
    public var op: FileOpKind
    public var path: String
    public var newPath: String?
    public var transferId: Int?
    public var maxDepth: Int?

    public init(
        requestId: Int,
        op: FileOpKind,
        path: String,
        newPath: String? = nil,
        transferId: Int? = nil,
        maxDepth: Int? = nil
    ) {
        self.requestId = requestId
        self.op = op
        self.path = path
        self.newPath = newPath
        self.transferId = transferId
        self.maxDepth = maxDepth
    }

    public static func list(requestId: Int, path: String) -> FileOpRequestPayload {
        FileOpRequestPayload(requestId: requestId, op: .list, path: path)
    }

    public static func stat(requestId: Int, path: String) -> FileOpRequestPayload {
        FileOpRequestPayload(requestId: requestId, op: .stat, path: path)
    }

    public static func delete(requestId: Int, path: String) -> FileOpRequestPayload {
        FileOpRequestPayload(requestId: requestId, op: .delete, path: path)
    }

    public static func rename(requestId: Int, path: String, newPath: String) -> FileOpRequestPayload {
        FileOpRequestPayload(requestId: requestId, op: .rename, path: path, newPath: newPath)
    }

    public static func mkdir(requestId: Int, path: String) -> FileOpRequestPayload {
        FileOpRequestPayload(requestId: requestId, op: .mkdir, path: path)
    }

    public static func storageScan(requestId: Int, path: String = "/sdcard", maxDepth: Int? = nil) -> FileOpRequestPayload {
        FileOpRequestPayload(requestId: requestId, op: .storageScan, path: path, maxDepth: maxDepth)
    }

    public static func pull(requestId: Int, path: String, transferId: Int) -> FileOpRequestPayload {
        FileOpRequestPayload(requestId: requestId, op: .pull, path: path, transferId: transferId)
    }

    public static func thumbnail(requestId: Int, path: String) -> FileOpRequestPayload {
        FileOpRequestPayload(requestId: requestId, op: .thumbnail, path: path)
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "FILE_OP_REQUEST")
    }

    public static func decode(from data: Data) throws -> FileOpRequestPayload {
        try OpalCastJSONCodec.decode(FileOpRequestPayload.self, from: data, context: "FILE_OP_REQUEST")
    }
}

/// Wire entry inside `0x1B FILE_OP_RESPONSE.entries`.
public struct RemoteFileEntryPayload: Codable, Sendable, Equatable, Identifiable, Hashable {
    public var id: String { path }
    public var name: String
    public var path: String
    public var isDir: Bool
    public var size: Int64
    public var modifiedMs: Int64
    public var mime: String

    public init(
        name: String,
        path: String,
        isDir: Bool,
        size: Int64 = 0,
        modifiedMs: Int64 = 0,
        mime: String = ""
    ) {
        self.name = name
        self.path = path
        self.isDir = isDir
        self.size = size
        self.modifiedMs = modifiedMs
        self.mime = mime
    }

    private enum CodingKeys: String, CodingKey {
        case name, path, isDir, size, modifiedMs, mime
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? ""
        isDir = try c.decodeIfPresent(Bool.self, forKey: .isDir) ?? false
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        modifiedMs = try c.decodeIfPresent(Int64.self, forKey: .modifiedMs) ?? 0
        mime = try c.decodeIfPresent(String.self, forKey: .mime) ?? ""
    }
}

/// Wire storage category inside `0x1B FILE_OP_RESPONSE.storage.categories`.
public struct RemoteStorageCategoryPayload: Codable, Sendable, Equatable, Identifiable, Hashable {
    public var id: String { name }
    public var name: String
    public var bytes: Int64
    public var fileCount: Int

    public init(name: String, bytes: Int64, fileCount: Int) {
        self.name = name
        self.bytes = bytes
        self.fileCount = fileCount
    }

    private enum CodingKeys: String, CodingKey {
        case name, bytes, fileCount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        bytes = try c.decodeIfPresent(Int64.self, forKey: .bytes) ?? 0
        fileCount = try c.decodeIfPresent(Int.self, forKey: .fileCount) ?? 0
    }
}

/// Wire large-file entry inside `0x1B FILE_OP_RESPONSE.storage.largeFiles`.
public struct RemoteLargeFilePayload: Codable, Sendable, Equatable, Identifiable, Hashable {
    public var id: String { path }
    public var name: String
    public var path: String
    public var size: Int64
    public var modifiedMs: Int64

    public init(name: String, path: String, size: Int64, modifiedMs: Int64 = 0) {
        self.name = name
        self.path = path
        self.size = size
        self.modifiedMs = modifiedMs
    }

    private enum CodingKeys: String, CodingKey {
        case name, path, size, modifiedMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? ""
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        modifiedMs = try c.decodeIfPresent(Int64.self, forKey: .modifiedMs) ?? 0
    }
}

/// Folder-level storage breakdown entry inside `0x1B FILE_OP_RESPONSE.storage.folders`.
public struct FolderStorageEntry: Codable, Sendable, Equatable, Hashable, Identifiable {
    public var id: String { path }
    public var path: String
    public var name: String
    public var bytes: Int64
    public var fileCount: Int

    public init(path: String, name: String, bytes: Int64, fileCount: Int) {
        self.path = path
        self.name = name
        self.bytes = max(0, bytes)
        self.fileCount = max(0, fileCount)
    }

    private enum CodingKeys: String, CodingKey {
        case path, name, bytes, fileCount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        bytes = max(0, try c.decodeIfPresent(Int64.self, forKey: .bytes) ?? 0)
        fileCount = max(0, try c.decodeIfPresent(Int.self, forKey: .fileCount) ?? 0)
    }
}

/// Per-application storage breakdown entry inside `0x1B FILE_OP_RESPONSE.storage.apps`.
public struct AppStorageEntry: Codable, Sendable, Equatable, Hashable, Identifiable {
    public var id: String { pkg }
    public var pkg: String
    public var appLabel: String
    public var apkBytes: Int64
    public var dataBytes: Int64
    public var cacheBytes: Int64
    public var totalBytes: Int64

    public var displayLabel: String {
        appLabel.isEmpty ? pkg : appLabel
    }

    public init(
        pkg: String,
        appLabel: String = "",
        apkBytes: Int64 = 0,
        dataBytes: Int64 = 0,
        cacheBytes: Int64 = 0,
        totalBytes: Int64? = nil
    ) {
        self.pkg = pkg
        self.appLabel = appLabel
        self.apkBytes = max(0, apkBytes)
        self.dataBytes = max(0, dataBytes)
        self.cacheBytes = max(0, cacheBytes)
        self.totalBytes = max(0, totalBytes ?? (self.apkBytes + self.dataBytes + self.cacheBytes))
    }

    private enum CodingKeys: String, CodingKey {
        case pkg, appLabel, apkBytes, dataBytes, cacheBytes, totalBytes
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pkg = try c.decodeIfPresent(String.self, forKey: .pkg) ?? ""
        appLabel = try c.decodeIfPresent(String.self, forKey: .appLabel) ?? ""
        apkBytes = max(0, try c.decodeIfPresent(Int64.self, forKey: .apkBytes) ?? 0)
        dataBytes = max(0, try c.decodeIfPresent(Int64.self, forKey: .dataBytes) ?? 0)
        cacheBytes = max(0, try c.decodeIfPresent(Int64.self, forKey: .cacheBytes) ?? 0)
        let decodedTotal = try c.decodeIfPresent(Int64.self, forKey: .totalBytes)
        totalBytes = max(0, decodedTotal ?? (apkBytes + dataBytes + cacheBytes))
    }
}

/// Wire storage analysis summary inside `0x1B FILE_OP_RESPONSE.storage`.
public struct RemoteStorageSummaryPayload: Codable, Sendable, Equatable {
    public var totalBytes: Int64
    public var freeBytes: Int64
    public var usedBytes: Int64
    public var categories: [RemoteStorageCategoryPayload]
    public var largeFiles: [RemoteLargeFilePayload]
    public var folders: [FolderStorageEntry]
    public var apps: [AppStorageEntry]

    public init(
        totalBytes: Int64 = 0,
        freeBytes: Int64 = 0,
        usedBytes: Int64 = 0,
        categories: [RemoteStorageCategoryPayload] = [],
        largeFiles: [RemoteLargeFilePayload] = [],
        folders: [FolderStorageEntry] = [],
        apps: [AppStorageEntry] = []
    ) {
        self.totalBytes = totalBytes
        self.freeBytes = freeBytes
        self.usedBytes = usedBytes
        self.categories = categories
        self.largeFiles = largeFiles
        self.folders = folders
        self.apps = apps
    }

    private enum CodingKeys: String, CodingKey {
        case totalBytes, freeBytes, usedBytes, categories, largeFiles, folders, apps
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        totalBytes = try c.decodeIfPresent(Int64.self, forKey: .totalBytes) ?? 0
        freeBytes = try c.decodeIfPresent(Int64.self, forKey: .freeBytes) ?? 0
        usedBytes = try c.decodeIfPresent(Int64.self, forKey: .usedBytes) ?? 0
        categories = try c.decodeIfPresent([RemoteStorageCategoryPayload].self, forKey: .categories) ?? []
        largeFiles = try c.decodeIfPresent([RemoteLargeFilePayload].self, forKey: .largeFiles) ?? []
        folders = try c.decodeIfPresent([FolderStorageEntry].self, forKey: .folders) ?? []
        apps = try c.decodeIfPresent([AppStorageEntry].self, forKey: .apps) ?? []
    }
}

/// Downlink `0x1B FILE_OP_RESPONSE` UTF-8 JSON payload.
public struct FileOpResponsePayload: Codable, Sendable, Equatable {
    public var requestId: Int
    public var op: String
    public var ok: Bool
    public var error: String
    public var path: String
    public var entries: [RemoteFileEntryPayload]
    public var storage: RemoteStorageSummaryPayload?
    public var totalSize: Int64
    public var thumbnailBase64: String

    public var opKind: FileOpKind? {
        FileOpKind(rawValue: op)
    }

    public var thumbnailData: Data? {
        guard !thumbnailBase64.isEmpty else { return nil }
        return Data(base64Encoded: thumbnailBase64)
    }

    public init(
        requestId: Int,
        op: String,
        ok: Bool,
        error: String = "",
        path: String = "",
        entries: [RemoteFileEntryPayload] = [],
        storage: RemoteStorageSummaryPayload? = nil,
        totalSize: Int64 = 0,
        thumbnailBase64: String = ""
    ) {
        self.requestId = requestId
        self.op = op
        self.ok = ok
        self.error = error
        self.path = path
        self.entries = entries
        self.storage = storage
        self.totalSize = totalSize
        self.thumbnailBase64 = thumbnailBase64
    }

    private enum CodingKeys: String, CodingKey {
        case requestId, op, ok, error, path, entries, storage, totalSize, thumbnailBase64
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        requestId = try c.decodeIfPresent(Int.self, forKey: .requestId) ?? 0
        op = try c.decodeIfPresent(String.self, forKey: .op) ?? ""
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        error = try c.decodeIfPresent(String.self, forKey: .error) ?? ""
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? ""
        entries = try c.decodeIfPresent([RemoteFileEntryPayload].self, forKey: .entries) ?? []
        storage = try c.decodeIfPresent(RemoteStorageSummaryPayload.self, forKey: .storage)
        totalSize = try c.decodeIfPresent(Int64.self, forKey: .totalSize) ?? 0
        thumbnailBase64 = try c.decodeIfPresent(String.self, forKey: .thumbnailBase64) ?? ""
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "FILE_OP_RESPONSE")
    }

    public static func decode(from data: Data) throws -> FileOpResponsePayload {
        try OpalCastJSONCodec.decode(FileOpResponsePayload.self, from: data, context: "FILE_OP_RESPONSE")
    }
}

/// Downlink `0x1C FILE_PULL_CHUNK` fixed 15-byte Big-Endian binary header + name + chunk bytes:
/// `[4B uint32 BE transferId][1B uint8 chunkFlags (START=1/DATA=2/END=4/ABORT=8)]`
/// `[8B int64 BE totalSize][2B uint16 BE nameLen][UTF-8 nameOrPath (nameLen bytes)][raw chunk bytes]`.
public struct FilePullChunkPayload: Sendable, Equatable {
    public static let fixedHeaderSize = 15

    public var transferId: UInt32
    public var chunkFlags: OpalCastFileChunkFlags
    public var totalSize: Int64
    public var nameOrPath: String
    public var data: Data

    /// Optional UTF-8 reason carried in trailing bytes when `chunkFlags` contains `.abort`.
    public var abortErrorMessage: String? {
        guard chunkFlags.contains(.abort), !data.isEmpty else { return nil }
        let decoded = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (decoded?.isEmpty == false) ? decoded : nil
    }

    public init(
        transferId: UInt32,
        chunkFlags: OpalCastFileChunkFlags,
        totalSize: Int64 = 0,
        nameOrPath: String = "",
        data: Data = Data()
    ) {
        self.transferId = transferId
        self.chunkFlags = chunkFlags
        self.totalSize = totalSize
        self.nameOrPath = nameOrPath
        self.data = data
    }

    public func encode() throws -> Data {
        let nameBytes = Data(nameOrPath.utf8)
        guard nameBytes.count <= OpalCastProtocol.maxFileNameUTF8Bytes else {
            throw OpalCastCodecError.fieldTooLong(
                field: "filePullChunk.nameOrPath",
                length: nameBytes.count,
                maxLength: OpalCastProtocol.maxFileNameUTF8Bytes
            )
        }
        var writer = BinaryWriter(capacity: Self.fixedHeaderSize + nameBytes.count + data.count)
        writer.writeUInt32(transferId)
        writer.writeUInt8(chunkFlags.rawValue)
        writer.writeInt64(totalSize)
        writer.writeUInt16(UInt16(nameBytes.count))
        if !nameBytes.isEmpty {
            writer.writeBytes(nameBytes)
        }
        if !data.isEmpty {
            writer.writeBytes(data)
        }
        return writer.data
    }

    public static func decode(from raw: Data) throws -> FilePullChunkPayload {
        var reader = BinaryReader(raw)
        let transferId = try reader.readUInt32()
        let chunkFlags = OpalCastFileChunkFlags(rawValue: try reader.readUInt8())
        let totalSize = try reader.readInt64()
        let nameLen = Int(try reader.readUInt16())
        let nameBytes = nameLen > 0 ? try reader.readBytes(count: nameLen) : Data()
        guard let nameOrPath = String(data: nameBytes, encoding: .utf8) else {
            throw OpalCastCodecError.invalidUTF8(field: "filePullChunk.nameOrPath")
        }
        let chunkData = reader.readRemainingBytes()
        return FilePullChunkPayload(
            transferId: transferId,
            chunkFlags: chunkFlags,
            totalSize: totalSize,
            nameOrPath: nameOrPath,
            data: chunkData
        )
    }
}

// MARK: - Wireless APK Installation Payloads (0x34 Uplink / 0x1D Downlink)

/// Supported Android installer package container formats for `0x34 APK_INSTALL_REQUEST`.
public enum OpalCastApkFormat: String, Codable, Sendable, CaseIterable, Equatable {
    case apk = "apk"
    case apks = "apks"
    case apkm = "apkm"
    case xapk = "xapk"

    public static func infer(fromFilePath path: String) -> OpalCastApkFormat? {
        let ext = (path as NSString).pathExtension.lowercased()
        return OpalCastApkFormat(rawValue: ext)
    }
}

/// Uplink `0x34 APK_INSTALL_REQUEST` UTF-8 JSON payload.
public struct ApkInstallRequestPayload: Codable, Sendable, Equatable {
    public var installId: Int
    public var filePath: String
    public var format: OpalCastApkFormat
    public var replaceExisting: Bool
    public var allowDowngrade: Bool

    public init(
        installId: Int,
        filePath: String,
        format: OpalCastApkFormat,
        replaceExisting: Bool = true,
        allowDowngrade: Bool = false
    ) {
        self.installId = installId
        self.filePath = filePath
        self.format = format
        self.replaceExisting = replaceExisting
        self.allowDowngrade = allowDowngrade
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "APK_INSTALL_REQUEST")
    }

    public static func decode(from data: Data) throws -> ApkInstallRequestPayload {
        try OpalCastJSONCodec.decode(ApkInstallRequestPayload.self, from: data, context: "APK_INSTALL_REQUEST")
    }
}

/// Known installation progress stages reported in `0x1D APK_INSTALL_STATUS`.
public enum ApkInstallStage: String, Codable, Sendable, CaseIterable, Equatable {
    case uploading = "uploading"
    case extracting = "extracting"
    case installing = "installing"
    case succeeded = "succeeded"
    case failed = "failed"

    public var isTerminal: Bool {
        self == .succeeded || self == .failed
    }
}

/// Downlink `0x1D APK_INSTALL_STATUS` UTF-8 JSON payload.
public struct ApkInstallStatusPayload: Codable, Sendable, Equatable {
    public var installId: Int
    public var stage: String
    public var progress: Int
    public var packageName: String
    public var message: String

    public var knownStage: ApkInstallStage? {
        ApkInstallStage(rawValue: stage)
    }

    public var isTerminal: Bool {
        knownStage?.isTerminal ?? false
    }

    public init(
        installId: Int,
        stage: String,
        progress: Int = 0,
        packageName: String = "",
        message: String = ""
    ) {
        self.installId = installId
        self.stage = stage
        self.progress = min(max(progress, 0), 100)
        self.packageName = packageName
        self.message = message
    }

    private enum CodingKeys: String, CodingKey {
        case installId, stage, progress, packageName, message
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        installId = try c.decodeIfPresent(Int.self, forKey: .installId) ?? 0
        stage = try c.decodeIfPresent(String.self, forKey: .stage) ?? ""
        let rawProgress = try c.decodeIfPresent(Int.self, forKey: .progress) ?? 0
        progress = min(max(rawProgress, 0), 100)
        packageName = try c.decodeIfPresent(String.self, forKey: .packageName) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "APK_INSTALL_STATUS")
    }

    public static func decode(from data: Data) throws -> ApkInstallStatusPayload {
        try OpalCastJSONCodec.decode(ApkInstallStatusPayload.self, from: data, context: "APK_INSTALL_STATUS")
    }
}

// MARK: - Phone Call Bridge Payloads (0x1E Downlink / 0x35 Uplink)

/// Lifecycle state reported by Android telephony in `0x1E CALL_STATE`.
public enum CallLifecycleState: String, Codable, Sendable, CaseIterable, Equatable, Hashable {
    case idle = "idle"
    case ringing = "ringing"
    case offhook = "offhook"
    case dialing = "dialing"
}

/// Downlink `0x1E CALL_STATE` UTF-8 JSON payload representing live Android phone call state.
public struct CallStatePayload: Codable, Sendable, Equatable, Hashable {
    public var state: CallLifecycleState
    public var number: String
    public var contactName: String
    public var startedAtMs: Int64
    public var isMuted: Bool
    public var isSpeakerOn: Bool
    public var canAnswer: Bool
    public var canReject: Bool
    public var canDial: Bool
    public var source: String

    public static let idle = CallStatePayload()

    public var isActive: Bool {
        state != .idle
    }

    public var displayCaller: String {
        if !contactName.isEmpty { return contactName }
        if !number.isEmpty { return number }
        return "未知来电"
    }

    public init(
        state: CallLifecycleState = .idle,
        number: String = "",
        contactName: String = "",
        startedAtMs: Int64 = 0,
        isMuted: Bool = false,
        isSpeakerOn: Bool = false,
        canAnswer: Bool = false,
        canReject: Bool = false,
        canDial: Bool = false,
        source: String = ""
    ) {
        self.state = state
        self.number = number
        self.contactName = contactName
        self.startedAtMs = max(0, startedAtMs)
        self.isMuted = isMuted
        self.isSpeakerOn = isSpeakerOn
        self.canAnswer = canAnswer
        self.canReject = canReject
        self.canDial = canDial
        self.source = source
    }

    private enum CodingKeys: String, CodingKey {
        case state, number, contactName, startedAtMs, isMuted, isSpeakerOn, canAnswer, canReject, canDial, source
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let rawState = try c.decodeIfPresent(String.self, forKey: .state) {
            let trimmed = rawState.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                state = .idle
            } else if let parsed = CallLifecycleState(rawValue: trimmed) {
                state = parsed
            } else {
                throw OpalCastCodecError.invalidJSON("CALL_STATE: unsupported state '\(trimmed)'")
            }
        } else {
            state = .idle
        }
        number = try c.decodeIfPresent(String.self, forKey: .number) ?? ""
        contactName = try c.decodeIfPresent(String.self, forKey: .contactName) ?? ""
        startedAtMs = max(0, try c.decodeIfPresent(Int64.self, forKey: .startedAtMs) ?? 0)
        isMuted = try c.decodeIfPresent(Bool.self, forKey: .isMuted) ?? false
        isSpeakerOn = try c.decodeIfPresent(Bool.self, forKey: .isSpeakerOn) ?? false
        canAnswer = try c.decodeIfPresent(Bool.self, forKey: .canAnswer) ?? false
        canReject = try c.decodeIfPresent(Bool.self, forKey: .canReject) ?? false
        canDial = try c.decodeIfPresent(Bool.self, forKey: .canDial) ?? false
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "CALL_STATE")
    }

    public static func decode(from data: Data) throws -> CallStatePayload {
        try OpalCastJSONCodec.decode(CallStatePayload.self, from: data, context: "CALL_STATE")
    }
}

/// Operation verb for uplink `0x35 CALL_ACTION`.
public enum CallActionOp: String, Codable, Sendable, CaseIterable, Equatable, Hashable {
    case answer = "answer"
    case reject = "reject"
    case hangup = "hangup"
    case toggleMute = "toggle_mute"
    case toggleSpeaker = "toggle_speaker"
    case dial = "dial"
    case rejectSms = "reject_sms"
}

/// Uplink `0x35 CALL_ACTION` UTF-8 JSON payload sent from Mac to control Android phone calls.
public struct CallActionPayload: Codable, Sendable, Equatable, Hashable {
    public var op: CallActionOp
    public var number: String?
    public var smsText: String?

    public init(
        op: CallActionOp,
        number: String? = nil,
        smsText: String? = nil
    ) {
        self.op = op
        self.number = number
        self.smsText = smsText
    }

    public static func answer() -> CallActionPayload { CallActionPayload(op: .answer) }
    public static func reject() -> CallActionPayload { CallActionPayload(op: .reject) }
    public static func hangup() -> CallActionPayload { CallActionPayload(op: .hangup) }
    public static func toggleMute() -> CallActionPayload { CallActionPayload(op: .toggleMute) }
    public static func toggleSpeaker() -> CallActionPayload { CallActionPayload(op: .toggleSpeaker) }
    public static func dial(number: String) -> CallActionPayload {
        CallActionPayload(op: .dial, number: number)
    }
    public static func rejectSms(text: String) -> CallActionPayload {
        CallActionPayload(op: .rejectSms, smsText: text)
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "CALL_ACTION")
    }

    public static func decode(from data: Data) throws -> CallActionPayload {
        try OpalCastJSONCodec.decode(CallActionPayload.self, from: data, context: "CALL_ACTION")
    }
}
