import Foundation
import Network
import os
import Security

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

// MARK: - LocalWebDavServer Errors

public enum LocalWebDavServerError: Error, Sendable, Equatable, LocalizedError {
    case listenerFailed(String)
    case listenerCancelled
    case failedToBindPort

    public var errorDescription: String? {
        switch self {
        case .listenerFailed(let detail):
            return "本地 WebDAV 服务启动失败：\(detail)"
        case .listenerCancelled:
            return "本地 WebDAV 服务已取消"
        case .failedToBindPort:
            return "本地 WebDAV 服务未能分配回环端口"
        }
    }
}

// MARK: - Pure-Swift Loopback HTTP/1.1 + WebDAV (RFC 4918) Server

/// Loopback-only (`127.0.0.1`) HTTP/1.1 + WebDAV Class 1/2 server exposing an Android filesystem
/// to macOS Finder (`mount_webdav`) behind a random 128-bit secret path prefix `/<32-hex-token>/`.
public final class LocalWebDavServer: Sendable {
    private static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "webdav")

    public static let defaultMaxCacheBytes: Int64 = 256 * 1024 * 1024 // 256 MiB
    private static let maxHeaderBytes = 64 * 1024
    private static let maxSmallBodyBytes = 2 * 1024 * 1024
    private static let maxPutBodyBytes: Int64 = 4 * 1024 * 1024 * 1024 // 4 GiB
    private static let maxMetadataStubEntries = 256
    private static let maxMetadataStubTotalBytes = 8 * 1024 * 1024
    private static let streamChunkSize = 256 * 1024

    private struct CacheEntry {
        let key: String
        let remotePath: String
        let localURL: URL
        let sizeBytes: Int64
        var lastAccessSeq: UInt64
    }

    private struct MetadataStub {
        let isDirectory: Bool
        var data: Data
        var modifiedAt: Date
        var lastAccessSeq: UInt64
    }

    private struct ActiveLock {
        let token: String
        let path: String
        let depth: String
        let owner: String
        var expiresAt: Date
    }

    private struct MutableState {
        var listener: NWListener?
        var port: UInt16?
        var isRunning: Bool = false
        var nextConnectionId: UInt64 = 1
        var activeConnections: [UInt64: NWConnection] = [:]
        var activeTasks: [UInt64: Task<Void, Never>] = [:]

        // Bounded per-session LRU pull cache
        var cacheAccessSeq: UInt64 = 0
        var cacheByKey: [String: CacheEntry] = [:]
        var totalCachedBytes: Int64 = 0

        // In-memory Apple metadata stubs (never synced to Android)
        var metadataSeq: UInt64 = 0
        var metadataStubs: [String: MetadataStub] = [:]
        var totalMetadataBytes: Int = 0

        // Lightweight RFC 4918 Class 2 in-memory lock table (enables RW mount in macOS webdavfs)
        var locksByToken: [String: ActiveLock] = [:]
    }

    private let backend: any RemoteFileSystemBackend
    public let rootPath: String
    public let token: String
    public let maxCacheBytes: Int64
    private let cacheDirectory: URL
    private let listenerQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.webdav.listener", qos: .userInitiated)
    private let connectionQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.webdav.io", qos: .userInitiated, attributes: .concurrent)
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init(
        backend: any RemoteFileSystemBackend,
        rootPath: String = "/sdcard",
        maxCacheBytes: Int64 = LocalWebDavServer.defaultMaxCacheBytes,
        token: String? = nil
    ) {
        self.backend = backend
        self.rootPath = FileBrowserPresentation.normalizePath(rootPath)
        self.maxCacheBytes = max(1024, maxCacheBytes)
        let resolvedToken = token ?? Self.generateRandomHexToken(byteCount: 16)
        self.token = resolvedToken
        self.cacheDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-webdav-\(resolvedToken.prefix(12))-\(UUID().uuidString.prefix(6))", isDirectory: true)
    }

    deinit {
        stop()
    }

    public var port: UInt16? {
        state.withLock { $0.port }
    }

    public var isRunning: Bool {
        state.withLock { $0.isRunning }
    }

    public var serverURL: URL? {
        guard let boundPort = port else { return nil }
        return URL(string: "http://127.0.0.1:\(boundPort)/\(token)/")
    }

    // MARK: - Apple Metadata Filter

    /// Returns `true` for macOS Finder/Spotlight/FSEvents metadata paths that must be intercepted
    /// in memory and never propagated to Android `/sdcard`.
    public static func isIgnoredMacMetadataPath(_ relativePath: String) -> Bool {
        let components = relativePath.split(separator: "/", omittingEmptySubsequences: true)
        for comp in components {
            let name = String(comp)
            if name == ".DS_Store"
                || name.hasPrefix("._")
                || name == ".Trashes"
                || name == ".Spotlight-V100"
                || name == ".fseventsd"
                || name == ".TemporaryItems"
            {
                return true
            }
        }
        return false
    }

    // MARK: - Lifecycle

    /// Starts the loopback HTTP/WebDAV listener on `127.0.0.1` with an ephemeral port and returns
    /// the authenticated root URL `http://127.0.0.1:<port>/<32-hex-token>/`.
    @discardableResult
    public func start() async throws -> URL {
        if let existing = serverURL, isRunning {
            return existing
        }

        try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: .any)

        let listener: NWListener
        do {
            listener = try NWListener(using: parameters)
        } catch {
            throw LocalWebDavServerError.listenerFailed(error.localizedDescription)
        }

        final class StartGate: Sendable {
            let lock = OSAllocatedUnfairLock<Bool>(initialState: false)
            func claim() -> Bool {
                lock.withLock { done in
                    if done { return false }
                    done = true
                    return true
                }
            }
        }

        let gate = StartGate()

        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            listener.stateUpdateHandler = { [weak self] newState in
                guard let self else { return }
                switch newState {
                case .ready:
                    guard let assignedPort = listener.port?.rawValue, assignedPort > 0 else {
                        if gate.claim() {
                            listener.cancel()
                            continuation.resume(throwing: LocalWebDavServerError.failedToBindPort)
                        }
                        return
                    }
                    self.state.withLock { s in
                        s.listener = listener
                        s.port = assignedPort
                        s.isRunning = true
                    }
                    if gate.claim() {
                        let url = URL(string: "http://127.0.0.1:\(assignedPort)/\(self.token)/")!
                        Self.logger.info("LocalWebDavServer listening on 127.0.0.1:\(assignedPort, privacy: .public)")
                        continuation.resume(returning: url)
                    }

                case .failed(let err):
                    self.state.withLock { s in
                        if s.listener === listener {
                            s.isRunning = false
                            s.port = nil
                            s.listener = nil
                        }
                    }
                    if gate.claim() {
                        continuation.resume(throwing: LocalWebDavServerError.listenerFailed(err.localizedDescription))
                    }

                case .cancelled:
                    self.state.withLock { s in
                        if s.listener === listener {
                            s.isRunning = false
                            s.port = nil
                            s.listener = nil
                        }
                    }
                    if gate.claim() {
                        continuation.resume(throwing: LocalWebDavServerError.listenerCancelled)
                    }

                default:
                    break
                }
            }

            listener.newConnectionHandler = { [weak self] connection in
                self?.acceptConnection(connection)
            }

            state.withLock { s in
                s.listener = listener
            }
            listener.start(queue: listenerQueue)
        }
    }

    /// Stops the listener, closes all active connections, and purges the per-session temporary cache.
    public func stop() {
        let (listener, connections, tasks): (NWListener?, [NWConnection], [Task<Void, Never>]) = state.withLock { s in
            let l = s.listener
            let conns = Array(s.activeConnections.values)
            let t = Array(s.activeTasks.values)
            s.listener = nil
            s.port = nil
            s.isRunning = false
            s.activeConnections.removeAll()
            s.activeTasks.removeAll()
            s.cacheByKey.removeAll()
            s.totalCachedBytes = 0
            s.metadataStubs.removeAll()
            s.totalMetadataBytes = 0
            s.locksByToken.removeAll()
            return (l, conns, t)
        }

        listener?.stateUpdateHandler = nil
        listener?.newConnectionHandler = nil
        listener?.cancel()
        for task in tasks {
            task.cancel()
        }
        for conn in connections {
            conn.cancel()
        }
        try? FileManager.default.removeItem(at: cacheDirectory)
    }

    // MARK: - Connection Loop

    private func acceptConnection(_ connection: NWConnection) {
        let connId: UInt64 = state.withLock { s in
            let id = s.nextConnectionId
            s.nextConnectionId &+= 1
            s.activeConnections[id] = connection
            return id
        }

        connection.start(queue: connectionQueue)

        let task = Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else {
                connection.cancel()
                return
            }
            await self.serveConnection(connection)
            connection.cancel()
            self.state.withLock { s in
                s.activeConnections.removeValue(forKey: connId)
                s.activeTasks.removeValue(forKey: connId)
            }
        }

        state.withLock { $0.activeTasks[connId] = task }
    }

    private func serveConnection(_ connection: NWConnection) async {
        let stream = ConnectionStream(connection: connection)

        while !Task.isCancelled {
            let head: ParsedRequestHead?
            do {
                head = try await stream.readRequestHead(maxHeaderBytes: Self.maxHeaderBytes)
            } catch HTTPParseError.eof {
                return
            } catch HTTPParseError.headerTooLarge {
                try? await sendSimpleResponse(stream, status: 431, reason: "Request Header Fields Too Large", close: true)
                return
            } catch {
                try? await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: true)
                return
            }

            guard let head else { return }

            let connectionHeader = head.header("connection")?.lowercased() ?? ""
            let isHTTP10 = head.httpVersion == "HTTP/1.0"
            var shouldClose = connectionHeader.contains("close") || (isHTTP10 && !connectionHeader.contains("keep-alive"))

            // Authenticate token prefix BEFORE sending 100-continue or reading PUT payload.
            let targetResolution = resolveRequestTarget(head.target)
            guard case let .authorized(relativePath, remotePath, relativeSegments) = targetResolution else {
                switch targetResolution {
                case .forbidden:
                    try? await sendSimpleResponse(stream, status: 403, reason: "Forbidden", close: true)
                case .badRequest:
                    try? await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: true)
                case .authorized:
                    break
                }
                return
            }

            // Honor Expect: 100-continue (used by macOS webdavfs / URLSession / curl on PUT).
            if let expect = head.header("expect")?.lowercased(), expect.contains("100-continue") {
                do {
                    try await stream.send(Data("HTTP/1.1 100 Continue\r\n\r\n".utf8))
                } catch {
                    return
                }
            }

            // Read request body (stream PUT directly to temp file; buffer small bodies for other methods).
            let tempBodyURL: URL?
            let smallBody: Data
            do {
                if head.method == "PUT" {
                    let fileURL = makeTemporaryUploadFileURL()
                    try await stream.readBodyToFile(headers: head.headers, destinationURL: fileURL, maxBytes: Self.maxPutBodyBytes)
                    tempBodyURL = fileURL
                    smallBody = Data()
                } else {
                    tempBodyURL = nil
                    smallBody = try await stream.readBodyToData(headers: head.headers, maxBytes: Self.maxSmallBodyBytes)
                }
            } catch HTTPParseError.bodyTooLarge {
                try? await sendSimpleResponse(stream, status: 413, reason: "Content Too Large", close: true)
                return
            } catch {
                try? await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: true)
                return
            }

            defer {
                if let tempBodyURL {
                    try? FileManager.default.removeItem(at: tempBodyURL)
                }
            }

            do {
                let keepAlive = try await dispatchRequest(
                    head: head,
                    relativePath: relativePath,
                    remotePath: remotePath,
                    relativeSegments: relativeSegments,
                    smallBody: smallBody,
                    putTempFileURL: tempBodyURL,
                    shouldClose: shouldClose,
                    stream: stream
                )
                if !keepAlive {
                    shouldClose = true
                }
            } catch {
                Self.logger.debug("WebDAV connection I/O error: \(error.localizedDescription, privacy: .public)")
                return
            }

            if shouldClose {
                return
            }
        }
    }

    // MARK: - Method Dispatch

    private func dispatchRequest(
        head: ParsedRequestHead,
        relativePath: String,
        remotePath: String,
        relativeSegments: [String],
        smallBody: Data,
        putTempFileURL: URL?,
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        let method = head.method

        // Handle OPTIONS before metadata filter so Finder/clients can always discover capabilities.
        if method == "OPTIONS" {
            try await sendResponse(
                stream,
                status: 200,
                reason: "OK",
                headers: [
                    ("DAV", "1, 2"),
                    ("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, PROPFIND, LOCK, UNLOCK"),
                    ("MS-Author-Via", "DAV"),
                    ("Accept-Ranges", "bytes")
                ],
                body: Data(),
                close: shouldClose
            )
            return !shouldClose
        }

        // Intercept Apple metadata paths in memory before touching Android /sdcard.
        if Self.isIgnoredMacMetadataPath(relativePath) {
            return try await handleIgnoredMetadataRequest(
                head: head,
                relativePath: relativePath,
                relativeSegments: relativeSegments,
                putTempFileURL: putTempFileURL,
                shouldClose: shouldClose,
                stream: stream
            )
        }

        switch method {
        case "PROPFIND":
            return try await handlePropfind(
                head: head,
                remotePath: remotePath,
                relativeSegments: relativeSegments,
                shouldClose: shouldClose,
                stream: stream
            )

        case "GET", "HEAD":
            return try await handleGetOrHead(
                head: head,
                remotePath: remotePath,
                isHead: method == "HEAD",
                shouldClose: shouldClose,
                stream: stream
            )

        case "PUT":
            return try await handlePut(
                remotePath: remotePath,
                putTempFileURL: putTempFileURL,
                shouldClose: shouldClose,
                stream: stream
            )

        case "MKCOL":
            if !smallBody.isEmpty {
                try await sendSimpleResponse(stream, status: 415, reason: "Unsupported Media Type", close: shouldClose)
                return !shouldClose
            }
            return try await handleMkcol(remotePath: remotePath, shouldClose: shouldClose, stream: stream)

        case "DELETE":
            return try await handleDelete(remotePath: remotePath, shouldClose: shouldClose, stream: stream)

        case "MOVE":
            return try await handleMove(
                head: head,
                sourceRemotePath: remotePath,
                shouldClose: shouldClose,
                stream: stream
            )

        case "LOCK":
            return try await handleLock(
                head: head,
                relativeSegments: relativeSegments,
                shouldClose: shouldClose,
                stream: stream
            )

        case "UNLOCK":
            return try await handleUnlock(head: head, shouldClose: shouldClose, stream: stream)

        case "PROPPATCH":
            let href = davHref(relativeSegments: relativeSegments, isDirectory: false)
            let xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>\(Self.xmlEscape(href))</D:href>
                <D:propstat>
                  <D:prop/>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
            """
            try await sendResponse(
                stream,
                status: 207,
                reason: "Multi-Status",
                headers: [("Content-Type", "application/xml; charset=utf-8")],
                body: Data(xml.utf8),
                close: shouldClose
            )
            return !shouldClose

        default:
            try await sendResponse(
                stream,
                status: 405,
                reason: "Method Not Allowed",
                headers: [("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, PROPFIND, LOCK, UNLOCK")],
                body: Data(),
                close: shouldClose
            )
            return !shouldClose
        }
    }

    // MARK: - In-Memory Apple Metadata Handlers

    private func handleIgnoredMetadataRequest(
        head: ParsedRequestHead,
        relativePath: String,
        relativeSegments: [String],
        putTempFileURL: URL?,
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        switch head.method {
        case "PROPFIND":
            let stub = state.withLock { $0.metadataStubs[relativePath] }
            guard let stub else {
                try await sendSimpleResponse(stream, status: 404, reason: "Not Found", close: shouldClose)
                return !shouldClose
            }
            let name = relativeSegments.last ?? ""
            let modifiedMs = Int64(stub.modifiedAt.timeIntervalSince1970 * 1000)
            let item = RemoteFileItem(
                name: name,
                path: relativePath,
                isDirectory: stub.isDirectory,
                sizeBytes: Int64(stub.data.count),
                modifiedAtMs: modifiedMs,
                mimeType: stub.isDirectory ? "" : "application/octet-stream"
            )
            let xml = buildMultistatusXML([(item: item, segments: relativeSegments)])
            try await sendResponse(
                stream,
                status: 207,
                reason: "Multi-Status",
                headers: [("Content-Type", "application/xml; charset=utf-8")],
                body: Data(xml.utf8),
                close: shouldClose
            )
            return !shouldClose

        case "GET", "HEAD":
            let stub = state.withLock { $0.metadataStubs[relativePath] }
            guard let stub, !stub.isDirectory else {
                try await sendSimpleResponse(stream, status: 404, reason: "Not Found", close: shouldClose)
                return !shouldClose
            }
            let etag = "\"meta-\(Int64(stub.modifiedAt.timeIntervalSince1970 * 1000))-\(stub.data.count)\""
            let lastMod = Self.formatRFC1123Date(stub.modifiedAt)
            try await sendResponse(
                stream,
                status: 200,
                reason: "OK",
                headers: [
                    ("Content-Type", "application/octet-stream"),
                    ("Accept-Ranges", "bytes"),
                    ("ETag", etag),
                    ("Last-Modified", lastMod),
                    ("Content-Length", "\(stub.data.count)")
                ],
                body: head.method == "HEAD" ? Data() : stub.data,
                explicitContentLength: stub.data.count,
                close: shouldClose
            )
            return !shouldClose

        case "PUT":
            let data: Data
            if let putTempFileURL, let loaded = try? Data(contentsOf: putTempFileURL) {
                data = loaded.prefix(Self.maxMetadataStubTotalBytes)
            } else {
                data = Data()
            }
            let existed = storeMetadataStub(path: relativePath, isDirectory: false, data: data)
            try await sendSimpleResponse(
                stream,
                status: existed ? 204 : 201,
                reason: existed ? "No Content" : "Created",
                close: shouldClose
            )
            return !shouldClose

        case "MKCOL":
            _ = storeMetadataStub(path: relativePath, isDirectory: true, data: Data())
            try await sendSimpleResponse(stream, status: 201, reason: "Created", close: shouldClose)
            return !shouldClose

        case "DELETE":
            removeMetadataStub(path: relativePath)
            clearLocks(matchingPrefix: davHref(relativeSegments: relativeSegments, isDirectory: false))
            try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
            return !shouldClose

        case "MOVE":
            if let destHeader = head.header("destination"),
               case let .authorized(destRel, _, _) = resolveDestinationHeader(destHeader),
               Self.isIgnoredMacMetadataPath(destRel)
            {
                let moved = state.withLock { s -> Bool in
                    if let existing = s.metadataStubs.removeValue(forKey: relativePath) {
                        s.metadataStubs[destRel] = existing
                        return true
                    }
                    return false
                }
                try await sendSimpleResponse(stream, status: moved ? 201 : 404, reason: moved ? "Created" : "Not Found", close: shouldClose)
            } else {
                removeMetadataStub(path: relativePath)
                try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
            }
            return !shouldClose

        case "LOCK":
            return try await handleLock(head: head, relativeSegments: relativeSegments, shouldClose: shouldClose, stream: stream)

        case "UNLOCK":
            return try await handleUnlock(head: head, shouldClose: shouldClose, stream: stream)

        default:
            try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
            return !shouldClose
        }
    }

    private func storeMetadataStub(path: String, isDirectory: Bool, data: Data) -> Bool {
        state.withLock { s in
            let existed = s.metadataStubs[path] != nil
            if let old = s.metadataStubs[path] {
                s.totalMetadataBytes -= old.data.count
            }
            s.metadataSeq &+= 1
            s.metadataStubs[path] = MetadataStub(
                isDirectory: isDirectory,
                data: data,
                modifiedAt: Date(),
                lastAccessSeq: s.metadataSeq
            )
            s.totalMetadataBytes += data.count

            while s.metadataStubs.count > Self.maxMetadataStubEntries || s.totalMetadataBytes > Self.maxMetadataStubTotalBytes {
                guard let victim = s.metadataStubs
                    .filter({ $0.key != path })
                    .min(by: { $0.value.lastAccessSeq < $1.value.lastAccessSeq })
                else { break }
                s.metadataStubs.removeValue(forKey: victim.key)
                s.totalMetadataBytes -= victim.value.data.count
            }
            return existed
        }
    }

    private func removeMetadataStub(path: String) {
        state.withLock { s in
            let prefix = path == "/" ? "/" : "\(path)/"
            let keysToRemove = s.metadataStubs.keys.filter { $0 == path || $0.hasPrefix(prefix) }
            for key in keysToRemove {
                if let removed = s.metadataStubs.removeValue(forKey: key) {
                    s.totalMetadataBytes = max(0, s.totalMetadataBytes - removed.data.count)
                }
            }
        }
    }

    // MARK: - WebDAV Operations Against RemoteFileSystemBackend

    private func handlePropfind(
        head: ParsedRequestHead,
        remotePath: String,
        relativeSegments: [String],
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        let depthHeader = head.header("depth")?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? "1"
        let depth: Int = (depthHeader == "0") ? 0 : 1

        let targetItem: RemoteFileItem
        do {
            targetItem = try await backend.stat(path: remotePath)
        } catch {
            if relativeSegments.isEmpty && mapErrorToHTTPStatus(error).status == 404 {
                // Always synthesize a valid root collection if backend does not explicitly stat rootPath.
                targetItem = RemoteFileItem(
                    name: "",
                    path: rootPath,
                    isDirectory: true,
                    sizeBytes: 0,
                    modifiedAtMs: Int64(Date().timeIntervalSince1970 * 1000)
                )
            } else {
                let mapped = mapErrorToHTTPStatus(error)
                try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
                return !shouldClose
            }
        }

        var entries: [(item: RemoteFileItem, segments: [String])] = [(targetItem, relativeSegments)]

        if depth >= 1 && targetItem.isDirectory {
            do {
                let children = try await backend.listDirectory(path: remotePath)
                for child in children {
                    guard !child.name.isEmpty, child.name != ".", child.name != ".." else { continue }
                    entries.append((child, relativeSegments + [child.name]))
                }
            } catch {
                let mapped = mapErrorToHTTPStatus(error)
                try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
                return !shouldClose
            }
        }

        let xml = buildMultistatusXML(entries)
        try await sendResponse(
            stream,
            status: 207,
            reason: "Multi-Status",
            headers: [("Content-Type", "application/xml; charset=utf-8")],
            body: Data(xml.utf8),
            close: shouldClose
        )
        return !shouldClose
    }

    private func handleGetOrHead(
        head: ParsedRequestHead,
        remotePath: String,
        isHead: Bool,
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        let item: RemoteFileItem
        do {
            item = try await backend.stat(path: remotePath)
        } catch {
            let mapped = mapErrorToHTTPStatus(error)
            try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
            return !shouldClose
        }

        if item.isDirectory {
            if isHead {
                try await sendResponse(
                    stream,
                    status: 200,
                    reason: "OK",
                    headers: [("Content-Type", "httpd/unix-directory"), ("Accept-Ranges", "bytes")],
                    body: Data(),
                    explicitContentLength: 0,
                    close: shouldClose
                )
            } else {
                try await sendSimpleResponse(stream, status: 405, reason: "Method Not Allowed", close: shouldClose)
            }
            return !shouldClose
        }

        let contentType = item.mimeType.isEmpty ? Self.guessMimeType(forName: item.name) : item.mimeType
        let etag = Self.makeETag(for: item)
        let lastMod = Self.formatRFC1123Date(ms: item.modifiedAtMs)

        // For HEAD we already have authoritative metadata from stat (avoid pulling large files over LAN).
        if isHead {
            let totalSize = max(0, item.sizeBytes)
            try await sendResponse(
                stream,
                status: 200,
                reason: "OK",
                headers: [
                    ("Content-Type", contentType),
                    ("Accept-Ranges", "bytes"),
                    ("ETag", etag),
                    ("Last-Modified", lastMod)
                ],
                body: Data(),
                explicitContentLength: Int(min(totalSize, Int64(Int.max))),
                close: shouldClose
            )
            return !shouldClose
        }

        // GET: resolve from bounded per-session LRU cache or pull from Android backend.
        let cached: (url: URL, sizeBytes: Int64, unlinkAfterServe: Bool)
        do {
            cached = try await fetchIntoCache(item: item, remotePath: remotePath)
        } catch {
            let mapped = mapErrorToHTTPStatus(error)
            try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
            return !shouldClose
        }

        defer {
            if cached.unlinkAfterServe {
                try? FileManager.default.removeItem(at: cached.url)
            }
        }

        let totalSize = cached.sizeBytes
        var startOffset: Int64 = 0
        var endOffset: Int64 = totalSize > 0 ? (totalSize - 1) : -1
        var isPartial = false

        if let rangeHeader = head.header("range") {
            switch Self.parseByteRange(rangeHeader, totalSize: totalSize) {
            case .none:
                break
            case .unsatisfiable:
                try await sendResponse(
                    stream,
                    status: 416,
                    reason: "Range Not Satisfiable",
                    headers: [
                        ("Content-Range", "bytes */\(totalSize)"),
                        ("Accept-Ranges", "bytes")
                    ],
                    body: Data(),
                    close: shouldClose
                )
                return !shouldClose
            case let .range(start, end):
                startOffset = start
                endOffset = end
                isPartial = true
            }
        }

        let responseLength: Int64 = (totalSize == 0 || endOffset < startOffset) ? 0 : (endOffset - startOffset + 1)
        var headers: [(String, String)] = [
            ("Content-Type", contentType),
            ("Accept-Ranges", "bytes"),
            ("ETag", etag),
            ("Last-Modified", lastMod)
        ]
        if isPartial {
            headers.append(("Content-Range", "bytes \(startOffset)-\(endOffset)/\(totalSize)"))
        }

        try await sendFileResponse(
            stream,
            status: isPartial ? 206 : 200,
            reason: isPartial ? "Partial Content" : "OK",
            headers: headers,
            fileURL: cached.url,
            startOffset: startOffset,
            byteCount: responseLength,
            close: shouldClose
        )
        return !shouldClose
    }

    private func handlePut(
        remotePath: String,
        putTempFileURL: URL?,
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        guard let tempURL = putTempFileURL else {
            try await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: shouldClose)
            return !shouldClose
        }
        guard let parentDir = FileBrowserPresentation.parentPath(of: remotePath) else {
            try await sendSimpleResponse(stream, status: 403, reason: "Forbidden", close: shouldClose)
            return !shouldClose
        }
        let fileName = (remotePath as NSString).lastPathComponent
        guard FileBrowserPresentation.isValidEntryName(fileName) else {
            try await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: shouldClose)
            return !shouldClose
        }

        do {
            try await backend.uploadFile(localURL: tempURL, toRemoteDirectory: parentDir, remoteFileName: fileName)
        } catch {
            let mapped = mapErrorToHTTPStatus(error)
            try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
            return !shouldClose
        }

        invalidateCache(forRemotePath: remotePath)
        try await sendSimpleResponse(stream, status: 201, reason: "Created", close: shouldClose)
        return !shouldClose
    }

    private func handleMkcol(
        remotePath: String,
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        do {
            try await backend.makeDirectory(path: remotePath)
        } catch {
            let mapped = mapErrorToHTTPStatus(error)
            try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
            return !shouldClose
        }
        try await sendSimpleResponse(stream, status: 201, reason: "Created", close: shouldClose)
        return !shouldClose
    }

    private func handleDelete(
        remotePath: String,
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        do {
            try await backend.delete(path: remotePath)
        } catch {
            let mapped = mapErrorToHTTPStatus(error)
            try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
            return !shouldClose
        }
        invalidateCache(forRemotePath: remotePath)
        try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
        return !shouldClose
    }

    private func handleMove(
        head: ParsedRequestHead,
        sourceRemotePath: String,
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        guard let destinationHeader = head.header("destination"), !destinationHeader.isEmpty else {
            try await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: shouldClose)
            return !shouldClose
        }

        let destResolution = resolveDestinationHeader(destinationHeader)
        guard case let .authorized(destRel, destRemotePath, _) = destResolution else {
            switch destResolution {
            case .forbidden:
                try await sendSimpleResponse(stream, status: 403, reason: "Forbidden", close: shouldClose)
            case .badRequest:
                try await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: shouldClose)
            case .authorized:
                break
            }
            return !shouldClose
        }

        if Self.isIgnoredMacMetadataPath(destRel) {
            try await sendSimpleResponse(stream, status: 201, reason: "Created", close: shouldClose)
            return !shouldClose
        }

        if sourceRemotePath == destRemotePath {
            try await sendSimpleResponse(stream, status: 403, reason: "Forbidden", close: shouldClose)
            return !shouldClose
        }

        let overwriteAllowed = (head.header("overwrite")?.uppercased() ?? "T") != "F"
        var destExisted = false
        if (try? await backend.stat(path: destRemotePath)) != nil {
            destExisted = true
            if !overwriteAllowed {
                try await sendSimpleResponse(stream, status: 412, reason: "Precondition Failed", close: shouldClose)
                return !shouldClose
            }
        }

        do {
            try await backend.rename(from: sourceRemotePath, to: destRemotePath)
        } catch {
            let mapped = mapErrorToHTTPStatus(error)
            try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
            return !shouldClose
        }

        invalidateCache(forRemotePath: sourceRemotePath)
        invalidateCache(forRemotePath: destRemotePath)
        try await sendSimpleResponse(
            stream,
            status: destExisted ? 204 : 201,
            reason: destExisted ? "No Content" : "Created",
            close: shouldClose
        )
        return !shouldClose
    }

    private func handleLock(
        head: ParsedRequestHead,
        relativeSegments: [String],
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        let href = davHref(relativeSegments: relativeSegments, isDirectory: false)
        let depth = head.header("depth")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "0"
        let expiresAt = Date().addingTimeInterval(3600)

        // Check if this is a lock refresh via `If: (<opaquelocktoken:...>)`.
        let refreshedToken: String? = {
            guard let ifHeader = head.header("if"),
                  let start = ifHeader.range(of: "opaquelocktoken:")
            else { return nil }
            let tail = ifHeader[start.lowerBound...]
            let end = tail.firstIndex(where: { $0 == ">" || $0 == ")" || $0 == " " }) ?? tail.endIndex
            return String(tail[..<end])
        }()

        let lock: ActiveLock = state.withLock { s in
            let now = Date()
            s.locksByToken = s.locksByToken.filter { $0.value.expiresAt > now }

            if let refreshedToken, var existing = s.locksByToken[refreshedToken] {
                existing.expiresAt = expiresAt
                s.locksByToken[refreshedToken] = existing
                return existing
            }
            let newToken = "opaquelocktoken:\(UUID().uuidString.lowercased())"
            let created = ActiveLock(
                token: newToken,
                path: href,
                depth: depth.isEmpty ? "0" : depth,
                owner: "OpalDesk",
                expiresAt: expiresAt
            )
            s.locksByToken[newToken] = created
            return created
        }

        let xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:prop xmlns:D="DAV:">
          <D:lockdiscovery>
            <D:activelock>
              <D:locktype><D:write/></D:locktype>
              <D:lockscope><D:exclusive/></D:lockscope>
              <D:depth>\(Self.xmlEscape(lock.depth))</D:depth>
              <D:owner><D:href>\(Self.xmlEscape(lock.owner))</D:href></D:owner>
              <D:timeout>Second-3600</D:timeout>
              <D:locktoken><D:href>\(Self.xmlEscape(lock.token))</D:href></D:locktoken>
              <D:lockroot><D:href>\(Self.xmlEscape(lock.path))</D:href></D:lockroot>
            </D:activelock>
          </D:lockdiscovery>
        </D:prop>
        """

        try await sendResponse(
            stream,
            status: 200,
            reason: "OK",
            headers: [
                ("Content-Type", "application/xml; charset=utf-8"),
                ("Lock-Token", "<\(lock.token)>")
            ],
            body: Data(xml.utf8),
            close: shouldClose
        )
        return !shouldClose
    }

    private func handleUnlock(
        head: ParsedRequestHead,
        shouldClose: Bool,
        stream: ConnectionStream
    ) async throws -> Bool {
        if let raw = head.header("lock-token") {
            let trimmed = raw
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "<>"))
            if !trimmed.isEmpty {
                state.withLock { _ = $0.locksByToken.removeValue(forKey: trimmed) }
            }
        }
        try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
        return !shouldClose
    }

    private func clearLocks(matchingPrefix prefix: String) {
        state.withLock { s in
            s.locksByToken = s.locksByToken.filter { !$0.value.path.hasPrefix(prefix) }
        }
    }

    // MARK: - Bounded LRU Pull Cache

    private func fetchIntoCache(
        item: RemoteFileItem,
        remotePath: String
    ) async throws -> (url: URL, sizeBytes: Int64, unlinkAfterServe: Bool) {
        let cacheKey = "\(remotePath)|\(item.modifiedAtMs)|\(item.sizeBytes)"

        let hit: CacheEntry? = state.withLock { s in
            guard var entry = s.cacheByKey[cacheKey] else { return nil }
            guard FileManager.default.fileExists(atPath: entry.localURL.path) else {
                s.cacheByKey.removeValue(forKey: cacheKey)
                s.totalCachedBytes = max(0, s.totalCachedBytes - entry.sizeBytes)
                return nil
            }
            s.cacheAccessSeq &+= 1
            entry.lastAccessSeq = s.cacheAccessSeq
            s.cacheByKey[cacheKey] = entry
            return entry
        }

        if let hit {
            return (hit.localURL, hit.sizeBytes, false)
        }

        try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        let tempURL = cacheDirectory.appendingPathComponent("pull-\(UUID().uuidString.lowercased()).bin")
        let downloadedURL = try await backend.pullFile(remotePath: remotePath, to: tempURL)

        let attrs = try FileManager.default.attributesOfItem(atPath: downloadedURL.path)
        let actualSize = (attrs[.size] as? NSNumber)?.int64Value ?? item.sizeBytes

        // If a single file exceeds the entire cache cap, serve it directly and unlink afterward.
        if actualSize > maxCacheBytes {
            return (downloadedURL, actualSize, true)
        }

        let evictedURLs: [URL] = state.withLock { s in
            // Remove older versions of the same remote path first.
            var evicted: [URL] = []
            for (k, existing) in s.cacheByKey where existing.remotePath == remotePath && k != cacheKey {
                s.cacheByKey.removeValue(forKey: k)
                s.totalCachedBytes = max(0, s.totalCachedBytes - existing.sizeBytes)
                evicted.append(existing.localURL)
            }

            if let prev = s.cacheByKey[cacheKey] {
                s.totalCachedBytes = max(0, s.totalCachedBytes - prev.sizeBytes)
                if prev.localURL != downloadedURL {
                    evicted.append(prev.localURL)
                }
            }

            s.cacheAccessSeq &+= 1
            s.cacheByKey[cacheKey] = CacheEntry(
                key: cacheKey,
                remotePath: remotePath,
                localURL: downloadedURL,
                sizeBytes: actualSize,
                lastAccessSeq: s.cacheAccessSeq
            )
            s.totalCachedBytes += actualSize

            while s.totalCachedBytes > maxCacheBytes {
                guard let victim = s.cacheByKey
                    .filter({ $0.key != cacheKey })
                    .min(by: { $0.value.lastAccessSeq < $1.value.lastAccessSeq })
                else { break }
                s.cacheByKey.removeValue(forKey: victim.key)
                s.totalCachedBytes = max(0, s.totalCachedBytes - victim.value.sizeBytes)
                evicted.append(victim.value.localURL)
            }
            return evicted
        }

        for url in evictedURLs {
            try? FileManager.default.removeItem(at: url)
        }
        return (downloadedURL, actualSize, false)
    }

    private func invalidateCache(forRemotePath path: String) {
        let normalized = FileBrowserPresentation.normalizePath(path)
        let prefix = normalized == "/" ? "/" : "\(normalized)/"
        let removedURLs: [URL] = state.withLock { s in
            let keys = s.cacheByKey.filter { $0.value.remotePath == normalized || $0.value.remotePath.hasPrefix(prefix) }.map(\.key)
            var urls: [URL] = []
            for key in keys {
                if let entry = s.cacheByKey.removeValue(forKey: key) {
                    s.totalCachedBytes = max(0, s.totalCachedBytes - entry.sizeBytes)
                    urls.append(entry.localURL)
                }
            }
            return urls
        }
        for url in removedURLs {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func makeTemporaryUploadFileURL() -> URL {
        try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        return cacheDirectory.appendingPathComponent("put-\(UUID().uuidString.lowercased()).tmp")
    }

    // MARK: - Path Resolution & Constant-Time Token Check

    private enum TargetResolution {
        case authorized(relativePath: String, remotePath: String, relativeSegments: [String])
        case forbidden
        case badRequest
    }

    private func resolveRequestTarget(_ rawTarget: String) -> TargetResolution {
        // Strip query string if present; support absolute-form HTTP/1.1 targets too.
        var pathPart = rawTarget
        if rawTarget.lowercased().hasPrefix("http://") || rawTarget.lowercased().hasPrefix("https://") {
            guard let components = URLComponents(string: rawTarget) else { return .badRequest }
            pathPart = components.percentEncodedPath
        } else if let queryIdx = rawTarget.firstIndex(of: "?") {
            pathPart = String(rawTarget[..<queryIdx])
        }

        guard pathPart.hasPrefix("/") else {
            return .forbidden
        }

        let rawSegments = pathPart.split(separator: "/", omittingEmptySubsequences: true)
        guard let firstRaw = rawSegments.first,
              let firstDecoded = String(firstRaw).removingPercentEncoding
        else {
            return .forbidden
        }

        guard Self.constantTimeEqual(firstDecoded, token) else {
            return .forbidden
        }

        var relativeSegments: [String] = []
        relativeSegments.reserveCapacity(max(0, rawSegments.count - 1))
        for rawSeg in rawSegments.dropFirst() {
            guard let decoded = String(rawSeg).removingPercentEncoding else {
                return .badRequest
            }
            if decoded.isEmpty || decoded == "." || decoded == ".."
                || decoded.contains("/") || decoded.contains("\\") || decoded.contains("\0")
            {
                return .badRequest
            }
            relativeSegments.append(decoded)
        }

        let relativePath = relativeSegments.isEmpty ? "/" : "/" + relativeSegments.joined(separator: "/")
        let remotePath: String
        if relativeSegments.isEmpty {
            remotePath = rootPath
        } else if rootPath == "/" {
            remotePath = relativePath
        } else {
            remotePath = rootPath + relativePath
        }
        return .authorized(relativePath: relativePath, remotePath: remotePath, relativeSegments: relativeSegments)
    }

    private func resolveDestinationHeader(_ rawHeader: String) -> TargetResolution {
        let trimmed = rawHeader.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("/") {
            return resolveRequestTarget(trimmed)
        }
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              (scheme == "http" || scheme == "https")
        else {
            return .badRequest
        }
        return resolveRequestTarget(components.percentEncodedPath)
    }

    // MARK: - XML & HTTP Response Builders

    private func buildMultistatusXML(_ items: [(item: RemoteFileItem, segments: [String])]) -> String {
        var xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n"
        for entry in items {
            let item = entry.item
            let href = davHref(relativeSegments: entry.segments, isDirectory: item.isDirectory)
            let displayName = item.name.isEmpty ? (entry.segments.last ?? "") : item.name
            let lastMod = Self.formatRFC1123Date(ms: item.modifiedAtMs)
            let etag = Self.makeETag(for: item)

            xml += "  <D:response>\n"
            xml += "    <D:href>\(Self.xmlEscape(href))</D:href>\n"
            xml += "    <D:propstat>\n"
            xml += "      <D:prop>\n"
            xml += "        <D:displayname>\(Self.xmlEscape(displayName))</D:displayname>\n"
            if item.isDirectory {
                xml += "        <D:resourcetype><D:collection/></D:resourcetype>\n"
            } else {
                xml += "        <D:resourcetype/>\n"
                xml += "        <D:getcontentlength>\(max(0, item.sizeBytes))</D:getcontentlength>\n"
                let mime = item.mimeType.isEmpty ? Self.guessMimeType(forName: item.name) : item.mimeType
                xml += "        <D:getcontenttype>\(Self.xmlEscape(mime))</D:getcontenttype>\n"
            }
            xml += "        <D:getlastmodified>\(Self.xmlEscape(lastMod))</D:getlastmodified>\n"
            xml += "        <D:getetag>\(Self.xmlEscape(etag))</D:getetag>\n"
            xml += "      </D:prop>\n"
            xml += "      <D:status>HTTP/1.1 200 OK</D:status>\n"
            xml += "    </D:propstat>\n"
            xml += "  </D:response>\n"
        }
        xml += "</D:multistatus>\n"
        return xml
    }

    private func davHref(relativeSegments: [String], isDirectory: Bool) -> String {
        var path = "/\(token)"
        for seg in relativeSegments {
            let encoded = seg.addingPercentEncoding(withAllowedCharacters: Self.davPathSegmentAllowed) ?? seg
            path += "/\(encoded)"
        }
        if isDirectory || relativeSegments.isEmpty {
            path += "/"
        }
        return path
    }

    private func sendSimpleResponse(
        _ stream: ConnectionStream,
        status: Int,
        reason: String,
        close: Bool
    ) async throws {
        try await sendResponse(stream, status: status, reason: reason, headers: [], body: Data(), close: close)
    }

    private func sendResponse(
        _ stream: ConnectionStream,
        status: Int,
        reason: String,
        headers: [(String, String)],
        body: Data,
        explicitContentLength: Int? = nil,
        close: Bool
    ) async throws {
        var headerBlock = "HTTP/1.1 \(status) \(reason)\r\n"
        headerBlock += "Date: \(Self.formatRFC1123Date(Date()))\r\n"
        headerBlock += "Server: OpalDesk-WebDAV/1.0\r\n"
        var hasContentLength = false
        for (name, value) in headers {
            if name.lowercased() == "content-length" { hasContentLength = true }
            headerBlock += "\(name): \(value)\r\n"
        }
        if !hasContentLength {
            headerBlock += "Content-Length: \(explicitContentLength ?? body.count)\r\n"
        }
        headerBlock += "Connection: \(close ? "close" : "keep-alive")\r\n\r\n"

        var packet = Data(headerBlock.utf8)
        if !body.isEmpty {
            packet.append(body)
        }
        try await stream.send(packet)
    }

    private func sendFileResponse(
        _ stream: ConnectionStream,
        status: Int,
        reason: String,
        headers: [(String, String)],
        fileURL: URL,
        startOffset: Int64,
        byteCount: Int64,
        close: Bool
    ) async throws {
        var headerBlock = "HTTP/1.1 \(status) \(reason)\r\n"
        headerBlock += "Date: \(Self.formatRFC1123Date(Date()))\r\n"
        headerBlock += "Server: OpalDesk-WebDAV/1.0\r\n"
        for (name, value) in headers {
            headerBlock += "\(name): \(value)\r\n"
        }
        headerBlock += "Content-Length: \(byteCount)\r\n"
        headerBlock += "Connection: \(close ? "close" : "keep-alive")\r\n\r\n"

        try await stream.send(Data(headerBlock.utf8))
        guard byteCount > 0 else { return }

        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        if startOffset > 0 {
            try handle.seek(toOffset: UInt64(startOffset))
        }

        var remaining = byteCount
        while remaining > 0 {
            let chunkLen = Int(min(remaining, Int64(Self.streamChunkSize)))
            guard let chunk = try handle.read(upToCount: chunkLen), !chunk.isEmpty else {
                break
            }
            try await stream.send(chunk)
            remaining -= Int64(chunk.count)
        }
    }

    // MARK: - Error Mapping & Utility Helpers

    private func mapErrorToHTTPStatus(_ error: Error) -> (status: Int, reason: String) {
        if let fsError = error as? RemoteFileSystemError {
            switch fsError {
            case .notFound: return (404, "Not Found")
            case .alreadyExists: return (405, "Method Not Allowed")
            case .permissionDenied: return (403, "Forbidden")
            case .invalidPath: return (400, "Bad Request")
            case .ioError: return (502, "Bad Gateway")
            }
        }
        if let browserErr = error as? RemoteFileBrowserError {
            switch browserErr {
            case .remoteError(_, let msg):
                let lower = msg.lowercased()
                if lower.contains("not found") || lower.contains("no such file") || lower.contains("does not exist")
                    || lower.contains("enoent") || lower.contains("不存在")
                {
                    return (404, "Not Found")
                }
                if lower.contains("exists") || lower.contains("已存在") {
                    return (405, "Method Not Allowed")
                }
                if lower.contains("permission") || lower.contains("denied") || lower.contains("权限") {
                    return (403, "Forbidden")
                }
                return (502, "Bad Gateway")
            case .notConnected:
                return (503, "Service Unavailable")
            case .requestTimedOut:
                return (504, "Gateway Timeout")
            default:
                return (502, "Bad Gateway")
            }
        }
        return (500, "Internal Server Error")
    }

    private enum RangeParseResult {
        case none
        case unsatisfiable
        case range(start: Int64, end: Int64)
    }

    private static func parseByteRange(_ header: String, totalSize: Int64) -> RangeParseResult {
        let trimmed = header.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix("bytes=") else { return .none }
        let specList = String(trimmed.dropFirst(6))
        // Handle single byte range; ignore multi-range by falling back to full content per RFC 9110.
        guard !specList.contains(",") else { return .none }
        let parts = specList.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return .none }
        guard totalSize > 0 else { return .unsatisfiable }

        let startStr = parts[0].trimmingCharacters(in: .whitespaces)
        let endStr = parts[1].trimmingCharacters(in: .whitespaces)

        if startStr.isEmpty {
            // Suffix range: bytes=-N
            guard let suffixLen = Int64(endStr), suffixLen > 0 else { return .unsatisfiable }
            let start = max(0, totalSize - suffixLen)
            return .range(start: start, end: totalSize - 1)
        }

        guard let start = Int64(startStr), start >= 0 else { return .unsatisfiable }
        if start >= totalSize { return .unsatisfiable }

        if endStr.isEmpty {
            return .range(start: start, end: totalSize - 1)
        }

        guard let requestedEnd = Int64(endStr), requestedEnd >= start else { return .unsatisfiable }
        return .range(start: start, end: min(requestedEnd, totalSize - 1))
    }

    private static func makeETag(for item: RemoteFileItem) -> String {
        "\"\(item.modifiedAtMs)-\(item.sizeBytes)\""
    }

    private static let davPathSegmentAllowed: CharacterSet = {
        var cs = CharacterSet.alphanumerics
        cs.insert(charactersIn: "-._~")
        return cs
    }()

    private static func xmlEscape(_ raw: String) -> String {
        var out = ""
        out.reserveCapacity(raw.count)
        for ch in raw {
            switch ch {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            default: out.append(ch)
            }
        }
        return out
    }

    private static func formatRFC1123Date(ms: Int64) -> String {
        let date = ms > 0 ? Date(timeIntervalSince1970: Double(ms) / 1000.0) : Date(timeIntervalSince1970: 0)
        return formatRFC1123Date(date)
    }

    private static func formatRFC1123Date(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        return formatter.string(from: date)
    }

    private static func guessMimeType(forName name: String) -> String {
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "txt", "log", "md": return "text/plain; charset=utf-8"
        case "json": return "application/json"
        case "xml": return "application/xml"
        case "html", "htm": return "text/html"
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "heic": return "image/heic"
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        case "mp3": return "audio/mpeg"
        case "m4a": return "audio/mp4"
        case "pdf": return "application/pdf"
        case "zip": return "application/zip"
        case "apk": return "application/vnd.android.package-archive"
        default: return "application/octet-stream"
        }
    }

    private static func generateRandomHexToken(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        if status != errSecSuccess {
            var rng = SystemRandomNumberGenerator()
            for i in 0..<byteCount {
                bytes[i] = UInt8.random(in: 0...255, using: &rng)
            }
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8)
        let b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }
}

// MARK: - HTTP/1.1 Connection Stream Parser

private enum HTTPParseError: Error {
    case eof
    case malformedRequest
    case headerTooLarge
    case bodyTooLarge
    case sendFailed(String)
}

private struct ParsedRequestHead: Sendable {
    let method: String
    let target: String
    let httpVersion: String
    let headers: [String: String]

    func header(_ lowerName: String) -> String? {
        headers[lowerName.lowercased()]
    }
}

/// Sequential buffered reader/writer over a single `NWConnection` used exclusively by one connection task.
private final class ConnectionStream: @unchecked Sendable {
    private static let crlfCrlf = Data([0x0D, 0x0A, 0x0D, 0x0A])
    private static let crlf = Data([0x0D, 0x0A])

    private let connection: NWConnection
    private var buffer = Data()

    init(connection: NWConnection) {
        self.connection = connection
    }

    func readRequestHead(maxHeaderBytes: Int) async throws -> ParsedRequestHead? {
        while true {
            if let range = buffer.range(of: Self.crlfCrlf) {
                let headData = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
                buffer.removeSubrange(buffer.startIndex..<range.upperBound)
                return try parseHead(headData)
            }
            if buffer.count > maxHeaderBytes {
                throw HTTPParseError.headerTooLarge
            }
            let chunk = try await receiveChunk()
            if chunk.isEmpty {
                if buffer.isEmpty { return nil }
                throw HTTPParseError.eof
            }
            buffer.append(chunk)
        }
    }

    func readBodyToData(headers: [String: String], maxBytes: Int) async throws -> Data {
        if let te = headers["transfer-encoding"]?.lowercased(), te.contains("chunked") {
            var out = Data()
            try await readChunkedBody(maxBytes: Int64(maxBytes)) { chunk in
                out.append(chunk)
            }
            return out
        }

        guard let clRaw = headers["content-length"], let contentLength = Int(clRaw.trimmingCharacters(in: .whitespaces)) else {
            return Data()
        }
        guard contentLength >= 0 else { throw HTTPParseError.malformedRequest }
        guard contentLength <= maxBytes else { throw HTTPParseError.bodyTooLarge }
        if contentLength == 0 { return Data() }

        var out = Data(capacity: contentLength)
        try await readExactBytes(count: Int64(contentLength)) { chunk in
            out.append(chunk)
        }
        return out
    }

    func readBodyToFile(headers: [String: String], destinationURL: URL, maxBytes: Int64) async throws {
        guard FileManager.default.createFile(atPath: destinationURL.path, contents: nil) else {
            throw RemoteFileSystemError.ioError("无法创建 WebDAV 上传临时文件")
        }
        let handle = try FileHandle(forWritingTo: destinationURL)
        defer { try? handle.close() }

        if let te = headers["transfer-encoding"]?.lowercased(), te.contains("chunked") {
            try await readChunkedBody(maxBytes: maxBytes) { chunk in
                if !chunk.isEmpty {
                    try handle.write(contentsOf: chunk)
                }
            }
            return
        }

        guard let clRaw = headers["content-length"], let contentLength = Int64(clRaw.trimmingCharacters(in: .whitespaces)) else {
            return
        }
        guard contentLength >= 0 else { throw HTTPParseError.malformedRequest }
        guard contentLength <= maxBytes else { throw HTTPParseError.bodyTooLarge }
        guard contentLength > 0 else { return }

        try await readExactBytes(count: contentLength) { chunk in
            if !chunk.isEmpty {
                try handle.write(contentsOf: chunk)
            }
        }
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: HTTPParseError.sendFailed(error.localizedDescription))
                } else {
                    continuation.resume(returning: ())
                }
            })
        }
    }

    private func parseHead(_ headData: Data) throws -> ParsedRequestHead {
        guard let raw = String(data: headData, encoding: .utf8) ?? String(data: headData, encoding: .isoLatin1) else {
            throw HTTPParseError.malformedRequest
        }
        let lines = raw.components(separatedBy: "\r\n")
        guard let requestLine = lines.first, !requestLine.isEmpty else {
            throw HTTPParseError.malformedRequest
        }
        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count == 3 else {
            throw HTTPParseError.malformedRequest
        }

        let method = String(parts[0]).uppercased()
        let target = String(parts[1])
        let version = String(parts[2]).uppercased()
        guard version.hasPrefix("HTTP/1.") else {
            throw HTTPParseError.malformedRequest
        }

        var headers: [String: String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = String(line[..<colon]).trimmingCharacters(in: .whitespaces).lowercased()
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            if let existing = headers[name] {
                headers[name] = "\(existing), \(value)"
            } else {
                headers[name] = value
            }
        }

        return ParsedRequestHead(method: method, target: target, httpVersion: version, headers: headers)
    }

    private func readExactBytes(count: Int64, onChunk: (Data) throws -> Void) async throws {
        var remaining = count
        while remaining > 0 {
            if !buffer.isEmpty {
                let take = Int(min(remaining, Int64(buffer.count)))
                let end = buffer.index(buffer.startIndex, offsetBy: take)
                let slice = buffer.subdata(in: buffer.startIndex..<end)
                buffer.removeSubrange(buffer.startIndex..<end)
                try onChunk(slice)
                remaining -= Int64(take)
                continue
            }
            let chunk = try await receiveChunk()
            if chunk.isEmpty {
                throw HTTPParseError.eof
            }
            buffer.append(chunk)
        }
    }

    private func readChunkedBody(maxBytes: Int64, onChunk: (Data) throws -> Void) async throws {
        var totalRead: Int64 = 0
        while true {
            let sizeLine = try await readLine()
            let hexPart = sizeLine.split(separator: ";", maxSplits: 1).first.map(String.init) ?? ""
            guard let chunkSize = Int64(hexPart.trimmingCharacters(in: .whitespaces), radix: 16), chunkSize >= 0 else {
                throw HTTPParseError.malformedRequest
            }
            if chunkSize == 0 {
                // Drain optional trailer lines until empty line.
                while true {
                    let trailer = try await readLine()
                    if trailer.isEmpty { break }
                }
                return
            }
            totalRead += chunkSize
            if totalRead > maxBytes {
                throw HTTPParseError.bodyTooLarge
            }
            try await readExactBytes(count: chunkSize, onChunk: onChunk)
            // Consume trailing CRLF after chunk payload.
            let trailing = try await readLine()
            if !trailing.isEmpty {
                throw HTTPParseError.malformedRequest
            }
        }
    }

    private func readLine() async throws -> String {
        while true {
            if let range = buffer.range(of: Self.crlf) {
                let lineData = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
                buffer.removeSubrange(buffer.startIndex..<range.upperBound)
                guard let line = String(data: lineData, encoding: .utf8) ?? String(data: lineData, encoding: .isoLatin1) else {
                    throw HTTPParseError.malformedRequest
                }
                return line
            }
            if buffer.count > 8192 {
                throw HTTPParseError.malformedRequest
            }
            let chunk = try await receiveChunk()
            if chunk.isEmpty {
                throw HTTPParseError.eof
            }
            buffer.append(chunk)
        }
    }

    private func receiveChunk() async throws -> Data {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, isComplete, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    continuation.resume(returning: data)
                } else if isComplete {
                    continuation.resume(returning: Data())
                } else {
                    continuation.resume(returning: Data())
                }
            }
        }
    }
}
