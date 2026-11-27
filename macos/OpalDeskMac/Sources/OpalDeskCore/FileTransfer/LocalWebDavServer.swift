import Foundation
import Network
import os

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
    static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "webdav")

    public static let defaultMaxCacheBytes: Int64 = 256 * 1024 * 1024 // 256 MiB
    static let maxHeaderBytes = 64 * 1024
    static let maxSmallBodyBytes = 2 * 1024 * 1024
    static let maxPutBodyBytes: Int64 = 4 * 1024 * 1024 * 1024 // 4 GiB
    static let maxMetadataStubEntries = 256
    static let maxMetadataStubTotalBytes = 8 * 1024 * 1024
    static let streamChunkSize = 256 * 1024

    struct CacheEntry {
        let key: String
        let remotePath: String
        let localURL: URL
        let sizeBytes: Int64
        var lastAccessSeq: UInt64
    }

    struct MetadataStub {
        let isDirectory: Bool
        var data: Data
        var modifiedAt: Date
        var lastAccessSeq: UInt64
    }

    struct ActiveLock {
        let token: String
        let path: String
        let depth: String
        let owner: String
        var expiresAt: Date
    }

    struct MutableState {
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

    enum TargetResolution {
        case authorized(relativePath: String, remotePath: String, relativeSegments: [String])
        case forbidden
        case badRequest
    }

    let backend: any RemoteFileSystemBackend
    public let rootPath: String
    public let token: String
    public let maxCacheBytes: Int64
    let cacheDirectory: URL
    private let listenerQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.webdav.listener", qos: .userInitiated)
    private let connectionQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.webdav.io", qos: .userInitiated, attributes: .concurrent)
    let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init(
        backend: any RemoteFileSystemBackend,
        rootPath: String = "/sdcard",
        maxCacheBytes: Int64 = LocalWebDavServer.defaultMaxCacheBytes,
        token: String? = nil
    ) {
        self.backend = backend
        self.rootPath = FileBrowserPresentation.normalizePath(rootPath)
        self.maxCacheBytes = max(1024, maxCacheBytes)
        let resolvedToken = token ?? WebDavWireCodec.generateRandomHexToken(byteCount: 16)
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

    private static let ignoredMacMetadataNames: Set<String> = [
        ".DS_Store",
        ".Trashes",
        ".Spotlight-V100",
        ".fseventsd",
        ".TemporaryItems",
    ]

    /// Returns `true` for macOS Finder/Spotlight/FSEvents metadata paths that must be intercepted
    /// in memory and never propagated to Android `/sdcard`.
    public static func isIgnoredMacMetadataPath(_ relativePath: String) -> Bool {
        relativePath.split(separator: "/", omittingEmptySubsequences: true).contains { comp in
            let name = String(comp)
            return name.hasPrefix("._") || ignoredMacMetadataNames.contains(name)
        }
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

        let startClaimed = OSAllocatedUnfairLock<Bool>(initialState: false)
        let claimOnce: @Sendable () -> Bool = {
            startClaimed.withLock { done in
                if done { return false }
                done = true
                return true
            }
        }

        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            listener.stateUpdateHandler = { [weak self] newState in
                self?.handleListenerStateChange(
                    newState,
                    listener: listener,
                    claimOnce: claimOnce,
                    continuation: continuation
                )
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.acceptConnection(connection)
            }
            state.withLock { $0.listener = listener }
            listener.start(queue: listenerQueue)
        }
    }

    private func handleListenerStateChange(
        _ newState: NWListener.State,
        listener: NWListener,
        claimOnce: @Sendable () -> Bool,
        continuation: CheckedContinuation<URL, Error>
    ) {
        switch newState {
        case .ready:
            guard let assignedPort = listener.port?.rawValue, assignedPort > 0 else {
                if claimOnce() {
                    listener.cancel()
                    continuation.resume(throwing: LocalWebDavServerError.failedToBindPort)
                }
                return
            }
            state.withLock { s in
                s.listener = listener
                s.port = assignedPort
                s.isRunning = true
            }
            if claimOnce() {
                let url = URL(string: "http://127.0.0.1:\(assignedPort)/\(token)/")!
                Self.logger.info("LocalWebDavServer listening on 127.0.0.1:\(assignedPort, privacy: .public)")
                continuation.resume(returning: url)
            }

        case .failed(let err):
            clearStoppedListenerIfCurrent(listener)
            if claimOnce() {
                continuation.resume(throwing: LocalWebDavServerError.listenerFailed(err.localizedDescription))
            }

        case .cancelled:
            clearStoppedListenerIfCurrent(listener)
            if claimOnce() {
                continuation.resume(throwing: LocalWebDavServerError.listenerCancelled)
            }

        default:
            break
        }
    }

    private func clearStoppedListenerIfCurrent(_ listener: NWListener) {
        state.withLock { s in
            if s.listener === listener {
                s.isRunning = false
                s.port = nil
                s.listener = nil
            }
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
        tasks.forEach { $0.cancel() }
        connections.forEach { $0.cancel() }
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
        let stream = WebDavConnectionStream(connection: connection)

        while !Task.isCancelled {
            guard let head = await readNextRequestHead(on: stream) else { return }

            let connectionHeader = head.header("connection")?.lowercased() ?? ""
            var shouldClose = connectionHeader.contains("close")
                || (head.httpVersion == "HTTP/1.0" && !connectionHeader.contains("keep-alive"))

            // Authenticate token prefix BEFORE sending 100-continue or reading PUT payload.
            let targetResolution = resolveRequestTarget(head.target)
            guard case let .authorized(relativePath, remotePath, relativeSegments) = targetResolution else {
                await sendTargetRejection(targetResolution, on: stream)
                return
            }

            if let expect = head.header("expect")?.lowercased(), expect.contains("100-continue") {
                do {
                    try await stream.send(Data("HTTP/1.1 100 Continue\r\n\r\n".utf8))
                } catch {
                    return
                }
            }

            guard let body = await readRequestBody(for: head, on: stream) else { return }
            defer {
                if let tempURL = body.tempFileURL {
                    try? FileManager.default.removeItem(at: tempURL)
                }
            }

            do {
                let keepAlive = try await dispatchRequest(
                    head: head,
                    relativePath: relativePath,
                    remotePath: remotePath,
                    relativeSegments: relativeSegments,
                    smallBody: body.smallData,
                    putTempFileURL: body.tempFileURL,
                    shouldClose: shouldClose,
                    stream: stream
                )
                if !keepAlive { shouldClose = true }
            } catch {
                Self.logger.debug("WebDAV connection I/O error: \(error.localizedDescription, privacy: .public)")
                return
            }

            if shouldClose { return }
        }
    }

    private func readNextRequestHead(on stream: WebDavConnectionStream) async -> WebDavParsedRequestHead? {
        do {
            return try await stream.readRequestHead(maxHeaderBytes: Self.maxHeaderBytes)
        } catch WebDavHTTPParseError.eof {
            return nil
        } catch WebDavHTTPParseError.headerTooLarge {
            try? await sendSimpleResponse(stream, status: 431, reason: "Request Header Fields Too Large", close: true)
            return nil
        } catch {
            try? await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: true)
            return nil
        }
    }

    private func sendTargetRejection(_ resolution: TargetResolution, on stream: WebDavConnectionStream) async {
        switch resolution {
        case .forbidden:
            try? await sendSimpleResponse(stream, status: 403, reason: "Forbidden", close: true)
        case .badRequest:
            try? await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: true)
        case .authorized:
            break
        }
    }

    private func readRequestBody(
        for head: WebDavParsedRequestHead,
        on stream: WebDavConnectionStream
    ) async -> (tempFileURL: URL?, smallData: Data)? {
        do {
            if head.method == "PUT" {
                let fileURL = makeTemporaryUploadFileURL()
                try await stream.readBodyToFile(headers: head.headers, destinationURL: fileURL, maxBytes: Self.maxPutBodyBytes)
                return (fileURL, Data())
            }
            let data = try await stream.readBodyToData(headers: head.headers, maxBytes: Self.maxSmallBodyBytes)
            return (nil, data)
        } catch WebDavHTTPParseError.bodyTooLarge {
            try? await sendSimpleResponse(stream, status: 413, reason: "Content Too Large", close: true)
            return nil
        } catch {
            try? await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: true)
            return nil
        }
    }

    // MARK: - Bounded LRU Pull Cache

    func fetchIntoCache(
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

    func invalidateCache(forRemotePath path: String) {
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

    func resolveRequestTarget(_ rawTarget: String) -> TargetResolution {
        var pathPart = rawTarget
        if rawTarget.lowercased().hasPrefix("http://") || rawTarget.lowercased().hasPrefix("https://") {
            guard let components = URLComponents(string: rawTarget) else { return .badRequest }
            pathPart = components.percentEncodedPath
        } else if let queryIdx = rawTarget.firstIndex(of: "?") {
            pathPart = String(rawTarget[..<queryIdx])
        }

        guard pathPart.hasPrefix("/") else { return .forbidden }

        let rawSegments = pathPart.split(separator: "/", omittingEmptySubsequences: true)
        guard let firstRaw = rawSegments.first,
              let firstDecoded = String(firstRaw).removingPercentEncoding,
              WebDavWireCodec.constantTimeEqual(firstDecoded, token)
        else {
            return .forbidden
        }

        var relativeSegments: [String] = []
        relativeSegments.reserveCapacity(max(0, rawSegments.count - 1))
        for rawSeg in rawSegments.dropFirst() {
            guard let decoded = String(rawSeg).removingPercentEncoding else { return .badRequest }
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

    func resolveDestinationHeader(_ rawHeader: String) -> TargetResolution {
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
}
