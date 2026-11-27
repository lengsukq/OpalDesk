import Foundation

// MARK: - Remote File System Backend Abstraction

/// Errors surfaced by `RemoteFileSystemBackend` implementations and mapped to HTTP/WebDAV status codes.
public enum RemoteFileSystemError: Error, Sendable, Equatable, LocalizedError {
    case notFound(String)
    case alreadyExists(String)
    case permissionDenied(String)
    case invalidPath(String)
    case ioError(String)

    public var errorDescription: String? {
        switch self {
        case .notFound(let path):
            return "远端路径不存在：\(path)"
        case .alreadyExists(let path):
            return "远端路径已存在：\(path)"
        case .permissionDenied(let detail):
            return "远端权限不足：\(detail)"
        case .invalidPath(let path):
            return "非法远端路径：\(path)"
        case .ioError(let detail):
            return "远端文件 I/O 错误：\(detail)"
        }
    }
}

/// Asynchronous filesystem backend consumed by `LocalWebDavServer` so Finder mounting can operate
/// over `RemoteFileBrowserClient` + `FileSender` in production and over deterministic in-memory mocks in tests.
public protocol RemoteFileSystemBackend: Sendable {
    func listDirectory(path: String) async throws -> [RemoteFileItem]
    func stat(path: String) async throws -> RemoteFileItem
    func pullFile(remotePath: String, to localURL: URL) async throws -> URL
    func uploadFile(localURL: URL, toRemoteDirectory: String, remoteFileName: String) async throws
    func makeDirectory(path: String) async throws
    func rename(from oldPath: String, to newPath: String) async throws
    func delete(path: String) async throws
}

/// Production `RemoteFileSystemBackend` adapter bridging `RemoteFileBrowserClient` and an injectable file upload handler.
public final class CompanionRemoteFileSystemBackend: RemoteFileSystemBackend, Sendable {
    public typealias UploadHandler = @Sendable (
        _ localURL: URL,
        _ toRemoteDirectory: String,
        _ remoteFileName: String
    ) async throws -> Void

    private let browserClient: RemoteFileBrowserClient
    private let uploadHandler: UploadHandler

    public init(
        browserClient: RemoteFileBrowserClient,
        uploadHandler: @escaping UploadHandler
    ) {
        self.browserClient = browserClient
        self.uploadHandler = uploadHandler
    }

    public func listDirectory(path: String) async throws -> [RemoteFileItem] {
        let entries = try await browserClient.listDirectory(path: path)
        return entries.map { entry in
            RemoteFileItem(
                name: entry.name,
                path: entry.path,
                isDirectory: entry.isDir,
                sizeBytes: entry.size,
                modifiedAtMs: entry.modifiedMs,
                mimeType: entry.mime
            )
        }
    }

    public func stat(path: String) async throws -> RemoteFileItem {
        let response = try await browserClient.stat(path: path)
        if let first = response.entries.first {
            return RemoteFileItem(
                name: first.name,
                path: first.path,
                isDirectory: first.isDir,
                sizeBytes: first.size,
                modifiedAtMs: first.modifiedMs,
                mimeType: first.mime
            )
        }
        let normalized = FileBrowserPresentation.normalizePath(response.path.isEmpty ? path : response.path)
        let name = normalized == "/" ? "/" : (normalized as NSString).lastPathComponent
        let inferredDir = normalized == "/" || normalized == "/sdcard" || response.totalSize < 0
        return RemoteFileItem(
            name: name,
            path: normalized,
            isDirectory: inferredDir,
            sizeBytes: max(0, response.totalSize),
            modifiedAtMs: 0
        )
    }

    public func pullFile(remotePath: String, to localURL: URL) async throws -> URL {
        try await browserClient.pullFile(remotePath: remotePath, toLocalURL: localURL)
    }

    public func uploadFile(localURL: URL, toRemoteDirectory: String, remoteFileName: String) async throws {
        try await uploadHandler(localURL, toRemoteDirectory, remoteFileName)
    }

    public func makeDirectory(path: String) async throws {
        try await browserClient.makeDirectory(path: path)
    }

    public func rename(from oldPath: String, to newPath: String) async throws {
        try await browserClient.rename(from: oldPath, to: newPath)
    }

    public func delete(path: String) async throws {
        try await browserClient.delete(path: path)
    }
}
