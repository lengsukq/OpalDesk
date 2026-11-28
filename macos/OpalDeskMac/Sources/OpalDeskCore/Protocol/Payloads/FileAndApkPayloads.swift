import Foundation

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
