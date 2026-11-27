import Foundation
import os

// MARK: - Errors & Progress Models

public enum RemoteFileBrowserError: Error, Sendable, Equatable, LocalizedError {
    case notConnected
    case requestTimedOut(op: String, requestId: Int)
    case remoteError(op: String, message: String)
    case invalidResponse(String)
    case transferAborted(transferId: UInt32, reason: String)
    case localFileError(String)
    case cancelled

    public var errorDescription: String? {
        switch self {
        case .notConnected:
            return "未连接到 Android 设备，无法执行文件操作"
        case let .requestTimedOut(op, requestId):
            return "远端文件操作超时（\(op), #\(requestId)）"
        case let .remoteError(op, message):
            let detail = message.isEmpty ? "远端拒绝或执行失败" : message
            return "远端文件操作失败（\(op)）：\(detail)"
        case .invalidResponse(let detail):
            return "远端响应格式无效：\(detail)"
        case let .transferAborted(transferId, reason):
            return "文件拉取已中止（#\(transferId)）：\(reason)"
        case .localFileError(let detail):
            return "本地文件读写失败：\(detail)"
        case .cancelled:
            return "操作已取消"
        }
    }
}

public struct RemoteFilePullProgress: Sendable, Equatable, Identifiable {
    public var id: UInt32 { transferId }
    public let transferId: UInt32
    public let remotePath: String
    public let localURL: URL
    public let totalBytes: Int64
    public let receivedBytes: Int64

    public var fractionCompleted: Double {
        guard totalBytes > 0 else { return receivedBytes > 0 ? 1.0 : 0.0 }
        return min(max(Double(receivedBytes) / Double(totalBytes), 0.0), 1.0)
    }

    public init(
        transferId: UInt32,
        remotePath: String,
        localURL: URL,
        totalBytes: Int64,
        receivedBytes: Int64
    ) {
        self.transferId = transferId
        self.remotePath = remotePath
        self.localURL = localURL
        self.totalBytes = totalBytes
        self.receivedBytes = receivedBytes
    }
}

// MARK: - RemoteFileBrowserClient

/// Async request-response client for Android remote filesystem browsing (`0x33`/`0x1B`),
/// streaming file pulls (`0x1C`) into atomic `.part` files, and wireless APK installation (`0x34`/`0x1D`).
public final class RemoteFileBrowserClient: Sendable {
    private static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "companion.file")
    private static let maxPullFileBytes: Int64 = 4 * 1024 * 1024 * 1024 // 4 GiB safety cap

    private struct PendingRequest {
        let op: String
        let continuation: CheckedContinuation<FileOpResponsePayload, Error>
        let timeoutTask: Task<Void, Never>
    }

    /// Mutable disk/transfer state for an active pull.
    /// File handle operations execute strictly on `pullIOQueue`; lifecycle completion runs under `state` lock.
    private final class PendingPull: @unchecked Sendable {
        let transferId: UInt32
        let requestId: Int
        let remotePath: String
        let destinationURL: URL
        let partURL: URL
        let onProgress: (@Sendable (RemoteFilePullProgress) -> Void)?

        // Protected by parent `state` lock
        var continuation: CheckedContinuation<URL, Error>?
        var watchdogTask: Task<Void, Never>?
        var lastActivityNs: UInt64
        var isFinished: Bool = false

        // Accessed exclusively on serial `pullIOQueue`
        var expectedTotalSize: Int64 = -1
        var receivedBytes: Int64 = 0
        var fileHandle: FileHandle?
        var hasCreatedPartFile: Bool = false

        init(
            transferId: UInt32,
            requestId: Int,
            remotePath: String,
            destinationURL: URL,
            partURL: URL,
            onProgress: (@Sendable (RemoteFilePullProgress) -> Void)?
        ) {
            self.transferId = transferId
            self.requestId = requestId
            self.remotePath = remotePath
            self.destinationURL = destinationURL
            self.partURL = partURL
            self.onProgress = onProgress
            self.lastActivityNs = DispatchTime.now().uptimeNanoseconds
        }
    }

    private struct MutableState {
        var sendFrame: (@Sendable (OpalCastFrame) -> Bool)?
        var nextRequestId: Int = 1
        var nextTransferId: UInt32 = 1
        var nextInstallId: Int = 1
        var pendingRequests: [Int: PendingRequest] = [:]
        var pullRequestToTransfer: [Int: UInt32] = [:]
        var activePulls: [UInt32: PendingPull] = [:]
        var activeApkInstalls: [Int: AsyncThrowingStream<ApkInstallStatusPayload, Error>.Continuation] = [:]
    }

    private let defaultTimeoutSeconds: Double
    private let storageScanTimeoutSeconds: Double
    private let pullIOQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.file-pull-io", qos: .utility)
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    /// Exposed internally for deterministic leak/eviction verification in unit tests.
    var pendingRequestCount: Int {
        state.withLock { $0.pendingRequests.count }
    }

    var activePullCount: Int {
        state.withLock { $0.activePulls.count }
    }

    var activeApkInstallCount: Int {
        state.withLock { $0.activeApkInstalls.count }
    }

    public init(
        defaultTimeoutSeconds: Double = 30.0,
        storageScanTimeoutSeconds: Double = 120.0,
        sendFrame: (@Sendable (OpalCastFrame) -> Bool)? = nil
    ) {
        self.defaultTimeoutSeconds = max(0.05, defaultTimeoutSeconds)
        self.storageScanTimeoutSeconds = max(self.defaultTimeoutSeconds, storageScanTimeoutSeconds)
        self.state.withLock { $0.sendFrame = sendFrame }
    }

    deinit {
        handleSessionDisconnected(reason: "客户端已释放")
    }

    public func setFrameSender(_ sender: (@Sendable (OpalCastFrame) -> Bool)?) {
        state.withLock { $0.sendFrame = sender }
    }

    // MARK: - Remote File Operations (0x33 -> 0x1B)

    public func listDirectory(path: String = "/sdcard") async throws -> [RemoteFileEntryPayload] {
        let response = try await performFileOp(timeout: defaultTimeoutSeconds) { reqId in
            FileOpRequestPayload.list(requestId: reqId, path: path)
        }
        return response.entries
    }

    public func stat(path: String) async throws -> FileOpResponsePayload {
        try await performFileOp(timeout: defaultTimeoutSeconds) { reqId in
            FileOpRequestPayload.stat(requestId: reqId, path: path)
        }
    }

    public func delete(path: String) async throws {
        _ = try await performFileOp(timeout: defaultTimeoutSeconds) { reqId in
            FileOpRequestPayload.delete(requestId: reqId, path: path)
        }
    }

    public func rename(from oldPath: String, to newPath: String) async throws {
        _ = try await performFileOp(timeout: defaultTimeoutSeconds) { reqId in
            FileOpRequestPayload.rename(requestId: reqId, path: oldPath, newPath: newPath)
        }
    }

    public func makeDirectory(path: String) async throws {
        _ = try await performFileOp(timeout: defaultTimeoutSeconds) { reqId in
            FileOpRequestPayload.mkdir(requestId: reqId, path: path)
        }
    }

    public func scanStorage(path: String = "/sdcard", maxDepth: Int? = nil) async throws -> RemoteStorageSummaryPayload {
        let response = try await performFileOp(timeout: storageScanTimeoutSeconds) { reqId in
            FileOpRequestPayload.storageScan(requestId: reqId, path: path, maxDepth: maxDepth)
        }
        guard let storage = response.storage else {
            throw RemoteFileBrowserError.invalidResponse("storage_scan 响应缺少 storage 字段")
        }
        return storage
    }

    public func fetchThumbnail(path: String) async throws -> Data {
        let response = try await performFileOp(timeout: defaultTimeoutSeconds) { reqId in
            FileOpRequestPayload.thumbnail(requestId: reqId, path: path)
        }
        guard let data = response.thumbnailData else {
            throw RemoteFileBrowserError.invalidResponse("thumbnail 响应缺少有效图片数据")
        }
        return data
    }

    // MARK: - Streaming File Pull (0x33 pull -> 0x1B ack + 0x1C chunks)

    /// Downloads a remote file from Android into a sibling `.part` file and atomically renames it to `destinationURL`.
    @discardableResult
    public func pullFile(
        remotePath: String,
        toLocalURL destinationURL: URL,
        onProgress: (@Sendable (RemoteFilePullProgress) -> Void)? = nil
    ) async throws -> URL {
        guard destinationURL.isFileURL else {
            throw RemoteFileBrowserError.localFileError("目标路径必须是本地文件 URL")
        }
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: destinationURL.path, isDirectory: &isDir), isDir.boolValue {
            throw RemoteFileBrowserError.localFileError("目标路径是目录而非文件：\(destinationURL.lastPathComponent)")
        }

        let (requestId, transferId, sender): (Int, UInt32, (@Sendable (OpalCastFrame) -> Bool)?) = state.withLock { s in
            let reqId = s.nextRequestId
            s.nextRequestId += 1
            let tid = s.nextTransferId
            s.nextTransferId &+= 1
            if s.nextTransferId == 0 { s.nextTransferId = 1 }
            return (reqId, tid, s.sendFrame)
        }

        guard let sender else {
            throw RemoteFileBrowserError.notConnected
        }

        let partName = ".\(destinationURL.lastPathComponent).\(transferId).part"
        let partURL = destinationURL.deletingLastPathComponent().appendingPathComponent(partName)
        let pull = PendingPull(
            transferId: transferId,
            requestId: requestId,
            remotePath: remotePath,
            destinationURL: destinationURL,
            partURL: partURL,
            onProgress: onProgress
        )

        let requestFrame: OpalCastFrame
        do {
            requestFrame = try OpalCastFrame.fileOpRequest(
                .pull(requestId: requestId, path: remotePath, transferId: Int(transferId))
            )
        } catch {
            throw RemoteFileBrowserError.invalidResponse(error.localizedDescription)
        }

        let inactivityTimeoutNs = UInt64(defaultTimeoutSeconds * 1_000_000_000)

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
                let watchdog = Task.detached(priority: .utility) { [weak self] in
                    let pollIntervalNs = min(max(inactivityTimeoutNs / 4, 25_000_000), 1_000_000_000)
                    while !Task.isCancelled {
                        do {
                            try await Task.sleep(nanoseconds: pollIntervalNs)
                        } catch {
                            return
                        }
                        guard let self, !Task.isCancelled else { return }
                        let timedOut: Bool = self.state.withLock { s in
                            guard let active = s.activePulls[transferId], !active.isFinished else { return false }
                            let nowNs = DispatchTime.now().uptimeNanoseconds
                            return nowNs >= active.lastActivityNs && (nowNs - active.lastActivityNs) >= inactivityTimeoutNs
                        }
                        if timedOut {
                            self.failPull(
                                transferId: transferId,
                                error: .requestTimedOut(op: FileOpKind.pull.rawValue, requestId: requestId)
                            )
                            return
                        }
                    }
                }

                let registered: Bool = state.withLock { s in
                    if Task.isCancelled {
                        return false
                    }
                    pull.continuation = continuation
                    pull.watchdogTask = watchdog
                    s.activePulls[transferId] = pull
                    s.pullRequestToTransfer[requestId] = transferId
                    return true
                }

                guard registered else {
                    watchdog.cancel()
                    continuation.resume(throwing: RemoteFileBrowserError.cancelled)
                    return
                }

                if !sender(requestFrame) {
                    failPull(transferId: transferId, error: .notConnected)
                }
            }
        } onCancel: { [weak self] in
            self?.failPull(transferId: transferId, error: .cancelled)
        }
    }

    // MARK: - Wireless APK Installation Status Stream (0x34 -> 0x1D)

    /// Sends `0x34 APK_INSTALL_REQUEST` and streams real-time `0x1D APK_INSTALL_STATUS` updates until
    /// reaching a terminal stage (`succeeded` / `failed`) or transport disconnection.
    public func requestApkInstall(
        filePath: String,
        format: OpalCastApkFormat,
        replaceExisting: Bool = true,
        allowDowngrade: Bool = false
    ) -> AsyncThrowingStream<ApkInstallStatusPayload, Error> {
        AsyncThrowingStream { continuation in
            let (installId, sender): (Int, (@Sendable (OpalCastFrame) -> Bool)?) = state.withLock { s in
                let id = s.nextInstallId
                s.nextInstallId += 1
                s.activeApkInstalls[id] = continuation
                return (id, s.sendFrame)
            }

            continuation.onTermination = { [weak self] _ in
                self?.state.withLock { _ = $0.activeApkInstalls.removeValue(forKey: installId) }
            }

            guard let sender else {
                let removed = state.withLock { $0.activeApkInstalls.removeValue(forKey: installId) }
                removed?.finish(throwing: RemoteFileBrowserError.notConnected)
                return
            }

            let payload = ApkInstallRequestPayload(
                installId: installId,
                filePath: filePath,
                format: format,
                replaceExisting: replaceExisting,
                allowDowngrade: allowDowngrade
            )

            let frame: OpalCastFrame
            do {
                frame = try OpalCastFrame.apkInstallRequest(payload)
            } catch {
                let removed = state.withLock { $0.activeApkInstalls.removeValue(forKey: installId) }
                removed?.finish(throwing: RemoteFileBrowserError.invalidResponse(error.localizedDescription))
                return
            }

            if !sender(frame) {
                let removed = state.withLock { $0.activeApkInstalls.removeValue(forKey: installId) }
                removed?.finish(throwing: RemoteFileBrowserError.notConnected)
            }
        }
    }

    // MARK: - Inbound Downlink Frame Handlers

    /// Processes an inbound `0x1B FILE_OP_RESPONSE` frame from Android.
    public func handleFileOpResponse(_ response: FileOpResponsePayload) {
        // Check if this response belongs to an in-flight pull handshake.
        let pullTransferId: UInt32? = state.withLock { s in
            guard let tid = s.pullRequestToTransfer.removeValue(forKey: response.requestId) else {
                return nil
            }
            if response.ok, let pull = s.activePulls[tid] {
                pull.lastActivityNs = DispatchTime.now().uptimeNanoseconds
            }
            return tid
        }

        if let pullTransferId {
            if !response.ok {
                failPull(
                    transferId: pullTransferId,
                    error: .remoteError(op: response.op.isEmpty ? FileOpKind.pull.rawValue : response.op, message: response.error)
                )
            } else if response.totalSize >= 0 {
                let pull = state.withLock { $0.activePulls[pullTransferId] }
                if let pull {
                    let announcedSize = response.totalSize
                    pullIOQueue.async {
                        if pull.expectedTotalSize < 0 {
                            pull.expectedTotalSize = announcedSize
                        }
                    }
                }
            }
            return
        }

        // Standard request-response continuation resolution.
        let pending = state.withLock { $0.pendingRequests.removeValue(forKey: response.requestId) }
        guard let pending else { return }
        pending.timeoutTask.cancel()
        if response.ok {
            pending.continuation.resume(returning: response)
        } else {
            pending.continuation.resume(throwing: RemoteFileBrowserError.remoteError(
                op: response.op.isEmpty ? pending.op : response.op,
                message: response.error
            ))
        }
    }

    /// Processes an inbound `0x1C FILE_PULL_CHUNK` frame from Android on the serial file I/O queue.
    public func handleFilePullChunk(_ chunk: FilePullChunkPayload) {
        let pull: PendingPull? = state.withLock { s in
            guard let active = s.activePulls[chunk.transferId], !active.isFinished else {
                return nil
            }
            active.lastActivityNs = DispatchTime.now().uptimeNanoseconds
            return active
        }
        guard let pull else { return }

        pullIOQueue.async { [weak self] in
            guard let self else { return }
            self.processPullChunkOnIOQueue(chunk, pull: pull)
        }
    }

    /// Processes an inbound `0x1D APK_INSTALL_STATUS` frame from Android.
    public func handleApkInstallStatus(_ status: ApkInstallStatusPayload) {
        let continuation: AsyncThrowingStream<ApkInstallStatusPayload, Error>.Continuation? = state.withLock { s in
            if status.isTerminal {
                return s.activeApkInstalls.removeValue(forKey: status.installId)
            }
            return s.activeApkInstalls[status.installId]
        }
        guard let continuation else { return }
        continuation.yield(status)
        if status.isTerminal {
            continuation.finish()
        }
    }

    /// Fails all in-flight RPCs, aborts active pulls (cleaning `.part` files), and terminates APK install streams.
    public func handleSessionDisconnected(reason: String = "连接已断开") {
        let (requests, pulls, installs): (
            [PendingRequest],
            [PendingPull],
            [AsyncThrowingStream<ApkInstallStatusPayload, Error>.Continuation]
        ) = state.withLock { s in
            let reqs = Array(s.pendingRequests.values)
            s.pendingRequests.removeAll()
            s.pullRequestToTransfer.removeAll()

            let activePullList = Array(s.activePulls.values)
            s.activePulls.removeAll()
            for pull in activePullList {
                pull.isFinished = true
            }

            let installList = Array(s.activeApkInstalls.values)
            s.activeApkInstalls.removeAll()
            return (reqs, activePullList, installList)
        }

        for req in requests {
            req.timeoutTask.cancel()
            req.continuation.resume(throwing: RemoteFileBrowserError.notConnected)
        }

        for pull in pulls {
            pull.watchdogTask?.cancel()
            let cont = pull.continuation
            pull.continuation = nil
            cont?.resume(throwing: RemoteFileBrowserError.notConnected)
            pullIOQueue.async {
                Self.cleanupPullFileOnIOQueue(pull)
            }
        }

        for install in installs {
            install.finish(throwing: RemoteFileBrowserError.notConnected)
        }
    }

    // MARK: - Private Request & Pull Internals

    private func performFileOp(
        timeout: Double,
        buildPayload: (Int) -> FileOpRequestPayload
    ) async throws -> FileOpResponsePayload {
        let (requestId, sender): (Int, (@Sendable (OpalCastFrame) -> Bool)?) = state.withLock { s in
            let id = s.nextRequestId
            s.nextRequestId += 1
            return (id, s.sendFrame)
        }

        guard let sender else {
            throw RemoteFileBrowserError.notConnected
        }

        let payload = buildPayload(requestId)
        let opName = payload.op.rawValue
        let frame: OpalCastFrame
        do {
            frame = try OpalCastFrame.fileOpRequest(payload)
        } catch {
            throw RemoteFileBrowserError.invalidResponse(error.localizedDescription)
        }

        let timeoutNs = UInt64(max(timeout, 0.05) * 1_000_000_000)

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<FileOpResponsePayload, Error>) in
                let timeoutTask = Task.detached(priority: .utility) { [weak self] in
                    do {
                        try await Task.sleep(nanoseconds: timeoutNs)
                    } catch {
                        return
                    }
                    guard !Task.isCancelled, let self else { return }
                    let expired = self.state.withLock { $0.pendingRequests.removeValue(forKey: requestId) }
                    expired?.continuation.resume(throwing: RemoteFileBrowserError.requestTimedOut(
                        op: opName,
                        requestId: requestId
                    ))
                }

                let inserted: Bool = state.withLock { s in
                    if Task.isCancelled { return false }
                    s.pendingRequests[requestId] = PendingRequest(
                        op: opName,
                        continuation: continuation,
                        timeoutTask: timeoutTask
                    )
                    return true
                }

                guard inserted else {
                    timeoutTask.cancel()
                    continuation.resume(throwing: RemoteFileBrowserError.cancelled)
                    return
                }

                if !sender(frame) {
                    if let removed = state.withLock({ $0.pendingRequests.removeValue(forKey: requestId) }) {
                        removed.timeoutTask.cancel()
                        removed.continuation.resume(throwing: RemoteFileBrowserError.notConnected)
                    }
                }
            }
        } onCancel: { [weak self] in
            guard let self else { return }
            if let cancelled = self.state.withLock({ $0.pendingRequests.removeValue(forKey: requestId) }) {
                cancelled.timeoutTask.cancel()
                cancelled.continuation.resume(throwing: RemoteFileBrowserError.cancelled)
            }
        }
    }

    private func processPullChunkOnIOQueue(_ chunk: FilePullChunkPayload, pull: PendingPull) {
        let stillActive: Bool = state.withLock { s in
            guard let current = s.activePulls[pull.transferId], current === pull, !current.isFinished else {
                return false
            }
            return true
        }
        guard stillActive else { return }

        if chunk.chunkFlags.contains(.abort) {
            let reason = chunk.abortErrorMessage ?? "远端中止文件传输"
            Self.cleanupPullFileOnIOQueue(pull)
            completePull(transferId: pull.transferId, result: .failure(RemoteFileBrowserError.transferAborted(
                transferId: pull.transferId,
                reason: reason
            )))
            return
        }

        if chunk.chunkFlags.contains(.start) {
            if chunk.totalSize >= 0 {
                pull.expectedTotalSize = chunk.totalSize
            }
            if pull.expectedTotalSize > Self.maxPullFileBytes {
                Self.cleanupPullFileOnIOQueue(pull)
                completePull(transferId: pull.transferId, result: .failure(RemoteFileBrowserError.invalidResponse(
                    "文件大小超过安全上限（\(pull.expectedTotalSize) 字节）"
                )))
                return
            }
            do {
                try ensurePartFileOpenOnIOQueue(pull)
            } catch {
                Self.cleanupPullFileOnIOQueue(pull)
                completePull(transferId: pull.transferId, result: .failure(RemoteFileBrowserError.localFileError(
                    error.localizedDescription
                )))
                return
            }
        }

        if chunk.chunkFlags.contains(.data) || (!chunk.data.isEmpty && !chunk.chunkFlags.contains(.abort)) {
            if !chunk.data.isEmpty {
                let projected = pull.receivedBytes + Int64(chunk.data.count)
                if projected > Self.maxPullFileBytes
                    || (pull.expectedTotalSize > 0 && projected > pull.expectedTotalSize)
                {
                    Self.cleanupPullFileOnIOQueue(pull)
                    completePull(transferId: pull.transferId, result: .failure(RemoteFileBrowserError.invalidResponse(
                        "接收字节数（\(projected)）超过声明的文件大小（\(pull.expectedTotalSize)）"
                    )))
                    return
                }
                do {
                    try ensurePartFileOpenOnIOQueue(pull)
                    try pull.fileHandle?.write(contentsOf: chunk.data)
                    pull.receivedBytes = projected
                } catch {
                    Self.cleanupPullFileOnIOQueue(pull)
                    completePull(transferId: pull.transferId, result: .failure(RemoteFileBrowserError.localFileError(
                        error.localizedDescription
                    )))
                    return
                }

                let reportedTotal = pull.expectedTotalSize >= 0 ? pull.expectedTotalSize : pull.receivedBytes
                pull.onProgress?(RemoteFilePullProgress(
                    transferId: pull.transferId,
                    remotePath: pull.remotePath,
                    localURL: pull.destinationURL,
                    totalBytes: reportedTotal,
                    receivedBytes: pull.receivedBytes
                ))
            }
        }

        if chunk.chunkFlags.contains(.end) {
            do {
                try ensurePartFileOpenOnIOQueue(pull)
                if let handle = pull.fileHandle {
                    do {
                        try handle.synchronize()
                    } catch {
                        Self.logger.warning("fsync warning on \(pull.partURL.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)")
                    }
                    try handle.close()
                    pull.fileHandle = nil
                }

                if pull.expectedTotalSize > 0 && pull.receivedBytes != pull.expectedTotalSize {
                    Self.cleanupPullFileOnIOQueue(pull)
                    completePull(transferId: pull.transferId, result: .failure(RemoteFileBrowserError.invalidResponse(
                        "文件大小不匹配：预期 \(pull.expectedTotalSize) 字节，实际接收 \(pull.receivedBytes) 字节"
                    )))
                    return
                }

                let fm = FileManager.default
                if fm.fileExists(atPath: pull.destinationURL.path) {
                    _ = try fm.replaceItemAt(pull.destinationURL, withItemAt: pull.partURL)
                } else {
                    try fm.moveItem(at: pull.partURL, to: pull.destinationURL)
                }

                let finalSize = max(pull.expectedTotalSize, pull.receivedBytes)
                pull.onProgress?(RemoteFilePullProgress(
                    transferId: pull.transferId,
                    remotePath: pull.remotePath,
                    localURL: pull.destinationURL,
                    totalBytes: finalSize,
                    receivedBytes: finalSize
                ))
                completePull(transferId: pull.transferId, result: .success(pull.destinationURL))
            } catch {
                Self.cleanupPullFileOnIOQueue(pull)
                completePull(transferId: pull.transferId, result: .failure(RemoteFileBrowserError.localFileError(
                    error.localizedDescription
                )))
            }
        }
    }

    private func ensurePartFileOpenOnIOQueue(_ pull: PendingPull) throws {
        if pull.fileHandle != nil { return }
        let fm = FileManager.default
        let parentDir = pull.partURL.deletingLastPathComponent()
        try fm.createDirectory(at: parentDir, withIntermediateDirectories: true)
        if fm.fileExists(atPath: pull.partURL.path) {
            try fm.removeItem(at: pull.partURL)
        }
        guard fm.createFile(atPath: pull.partURL.path, contents: nil) else {
            throw RemoteFileBrowserError.localFileError("无法创建临时文件：\(pull.partURL.lastPathComponent)")
        }
        pull.hasCreatedPartFile = true
        pull.fileHandle = try FileHandle(forWritingTo: pull.partURL)
    }

    private func failPull(transferId: UInt32, error: RemoteFileBrowserError) {
        let removed: PendingPull? = state.withLock { s in
            guard let pull = s.activePulls.removeValue(forKey: transferId), !pull.isFinished else {
                return nil
            }
            pull.isFinished = true
            s.pullRequestToTransfer.removeValue(forKey: pull.requestId)
            return pull
        }
        guard let removed else { return }
        removed.watchdogTask?.cancel()
        let cont = removed.continuation
        removed.continuation = nil
        cont?.resume(throwing: error)
        pullIOQueue.async {
            Self.cleanupPullFileOnIOQueue(removed)
        }
    }

    private func completePull(transferId: UInt32, result: Result<URL, Error>) {
        let removed: PendingPull? = state.withLock { s in
            guard let pull = s.activePulls.removeValue(forKey: transferId), !pull.isFinished else {
                return nil
            }
            pull.isFinished = true
            s.pullRequestToTransfer.removeValue(forKey: pull.requestId)
            return pull
        }
        guard let removed else { return }
        removed.watchdogTask?.cancel()
        let cont = removed.continuation
        removed.continuation = nil
        cont?.resume(with: result)
    }

    private static func cleanupPullFileOnIOQueue(_ pull: PendingPull) {
        if let handle = pull.fileHandle {
            do {
                try handle.close()
            } catch {
                logger.warning("Failed to close pull temp handle: \(error.localizedDescription, privacy: .public)")
            }
            pull.fileHandle = nil
        }
        if pull.hasCreatedPartFile, FileManager.default.fileExists(atPath: pull.partURL.path) {
            do {
                try FileManager.default.removeItem(at: pull.partURL)
            } catch {
                logger.warning("Failed to remove pull temp file \(pull.partURL.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }
    }
}
