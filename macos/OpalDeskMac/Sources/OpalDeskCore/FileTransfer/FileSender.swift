import Foundation
import os

public enum FileTransferStatus: Sendable, Equatable {
    case sending
    case awaitingAck
    case completed(remotePath: String)
    case failed(reason: String)
    case cancelled
}

public struct FileTransferProgress: Sendable, Equatable, Identifiable {
    public var id: UInt32 { transferId }
    public let transferId: UInt32
    public let fileName: String
    public let totalBytes: Int64
    public var sentBytes: Int64
    public var status: FileTransferStatus

    public var fractionCompleted: Double {
        guard totalBytes > 0 else { return status == .sending ? 0 : 1 }
        return min(max(Double(sentBytes) / Double(totalBytes), 0), 1)
    }

    public init(
        transferId: UInt32,
        fileName: String,
        totalBytes: Int64,
        sentBytes: Int64 = 0,
        status: FileTransferStatus = .sending
    ) {
        self.transferId = transferId
        self.fileName = fileName
        self.totalBytes = totalBytes
        self.sentBytes = sentBytes
        self.status = status
    }
}

public enum FileSenderError: Error, Sendable, Equatable, LocalizedError {
    case directoryNotSupported(String)
    case unreadableFile(String)
    case fileNameTooLong(String)

    public var errorDescription: String? {
        switch self {
        case .directoryNotSupported(let name):
            return "暂不支持直接发送文件夹：\(name)"
        case .unreadableFile(let name):
            return "无法读取文件：\(name)"
        case .fileNameTooLong(let name):
            return "文件名过长（超过 \(OpalCastProtocol.maxFileNameUTF8Bytes) 字节 UTF-8 上限）：\(name.prefix(64))"
        }
    }
}

/// Streams local macOS files to Android over OpalCast `0x28 FILE_TRANSFER_CHUNK` in 256 KiB slices
/// without loading full files into memory, applying async backpressure pacing and evicting finished transfers.
public final class FileSender: Sendable {
    private static let minimumChunkSize = 4096

    private struct MutableState {
        var nextTransferId: UInt32 = 1
        var activeTransfers: [UInt32: FileTransferProgress] = [:]
        var activeTasks: [UInt32: Task<Void, Never>] = [:]
    }

    private let chunkSize: Int
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    /// Exposed internally for unit tests verifying terminal transfer eviction.
    var trackedTransferCount: Int {
        state.withLock { $0.activeTransfers.count }
    }

    public init(chunkSize: Int = OpalCastProtocol.defaultFileChunkSize) {
        self.chunkSize = max(chunkSize, Self.minimumChunkSize)
    }

    /// Starts streaming a local file URL through `sendFrame` (which may suspend for backpressure),
    /// emitting progress snapshots via `onProgress`.
    /// Pass `remoteFileNameOverride` to target a browsed remote path (e.g. `"/sdcard/Documents/a.pdf"`)
    /// or Android's APK staging directory (`"@apk_stage/app.apk"`).
    @discardableResult
    public func sendFile(
        at url: URL,
        remoteFileNameOverride: String? = nil,
        sendFrame: @escaping @Sendable (OpalCastFrame) async -> Void,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) throws -> UInt32 {
        let resourceValues = try url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
        if resourceValues.isDirectory == true {
            throw FileSenderError.directoryNotSupported(url.lastPathComponent)
        }
        guard FileManager.default.isReadableFile(atPath: url.path) else {
            throw FileSenderError.unreadableFile(url.lastPathComponent)
        }

        let displayName = url.lastPathComponent
        let trimmedOverride = remoteFileNameOverride?.trimmingCharacters(in: .whitespacesAndNewlines)
        let wireFileName = (trimmedOverride?.isEmpty == false) ? trimmedOverride! : displayName
        guard wireFileName.utf8.count <= OpalCastProtocol.maxFileNameUTF8Bytes else {
            throw FileSenderError.fileNameTooLong(wireFileName)
        }
        let totalSize = Int64(resourceValues.fileSize ?? 0)

        let transferId: UInt32 = state.withLock { s in
            let id = s.nextTransferId
            s.nextTransferId &+= 1
            if s.nextTransferId == 0 { s.nextTransferId = 1 }
            s.activeTransfers[id] = FileTransferProgress(
                transferId: id,
                fileName: displayName,
                totalBytes: totalSize,
                sentBytes: 0,
                status: .sending
            )
            return id
        }

        let chunkSize = self.chunkSize
        let task = Task.detached(priority: .utility) { [weak self] in
            guard let self else { return }
            await self.streamFile(
                transferId: transferId,
                url: url,
                fileName: wireFileName,
                totalSize: totalSize,
                chunkSize: chunkSize,
                sendFrame: sendFrame,
                onProgress: onProgress
            )
        }

        state.withLock { $0.activeTasks[transferId] = task }
        return transferId
    }

    /// Cancels an active transfer, evicts it from tracking, and notifies Android with `ABORT`.
    public func cancel(
        transferId: UInt32,
        sendFrame: @escaping @Sendable (OpalCastFrame) -> Void,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) {
        let (task, updated): (Task<Void, Never>?, FileTransferProgress?) = state.withLock { s in
            let t = s.activeTasks.removeValue(forKey: transferId)
            guard var progress = s.activeTransfers.removeValue(forKey: transferId) else {
                return (t, nil)
            }
            progress.status = .cancelled
            return (t, progress)
        }
        task?.cancel()
        sendAbortSync(transferId: transferId, sendFrame: sendFrame)
        if let updated {
            onProgress(updated)
        }
    }

    /// Processes an incoming `0x13 FILE_TRANSFER_ACK` from Android and evicts the completed entry.
    public func handleAck(
        _ ack: FileTransferAckPayload,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) {
        let updated: FileTransferProgress? = state.withLock { s in
            s.activeTasks.removeValue(forKey: ack.transferId)
            guard var progress = s.activeTransfers.removeValue(forKey: ack.transferId) else {
                return nil
            }
            switch ack.status {
            case .ok:
                progress.sentBytes = progress.totalBytes
                progress.status = .completed(remotePath: ack.pathOrError)
            case .error:
                progress.status = .failed(reason: ack.pathOrError)
            }
            return progress
        }
        if let updated {
            onProgress(updated)
        }
    }

    // MARK: - Private Streaming Loop

    private func streamFile(
        transferId: UInt32,
        url: URL,
        fileName: String,
        totalSize: Int64,
        chunkSize: Int,
        sendFrame: @escaping @Sendable (OpalCastFrame) async -> Void,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) async {
        let startFrame: OpalCastFrame
        do {
            let startPayload = FileTransferChunkPayload(
                transferId: transferId,
                chunkFlags: .start,
                fileName: fileName,
                totalSize: totalSize
            )
            startFrame = try OpalCastFrame.fileTransferChunk(startPayload)
        } catch {
            OpalDeskLog.file.error("Failed to encode START chunk for transfer \(transferId): \(error.localizedDescription, privacy: .public)")
            await markFailed(transferId: transferId, reason: error.localizedDescription, sendFrame: sendFrame, onProgress: onProgress)
            return
        }
        await sendFrame(startFrame)

        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            OpalDeskLog.file.error("Failed to open file \(fileName, privacy: .public): \(error.localizedDescription, privacy: .public)")
            await markFailed(transferId: transferId, reason: error.localizedDescription, sendFrame: sendFrame, onProgress: onProgress)
            return
        }
        defer {
            do {
                try handle.close()
            } catch {
                OpalDeskLog.file.warning("Failed to close file handle for \(fileName, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }

        var sentBytes: Int64 = 0
        do {
            while !Task.isCancelled {
                guard let chunk = try handle.read(upToCount: chunkSize), !chunk.isEmpty else {
                    break
                }
                let dataFrame = try OpalCastFrame.fileTransferChunk(FileTransferChunkPayload(
                    transferId: transferId,
                    chunkFlags: .data,
                    data: chunk
                ))
                await sendFrame(dataFrame)
                sentBytes += Int64(chunk.count)
                let currentSentBytes = sentBytes

                let snapshot: FileTransferProgress? = state.withLock { s in
                    guard var p = s.activeTransfers[transferId] else { return nil }
                    p.sentBytes = currentSentBytes
                    s.activeTransfers[transferId] = p
                    return p
                }
                if let snapshot {
                    onProgress(snapshot)
                }

                // Yield between chunks so real-time input/ping frames are never starved.
                await Task.yield()
            }
        } catch {
            OpalDeskLog.file.error("File read/encode failed during transfer \(transferId): \(error.localizedDescription, privacy: .public)")
            await markFailed(transferId: transferId, reason: error.localizedDescription, sendFrame: sendFrame, onProgress: onProgress)
            return
        }

        if Task.isCancelled {
            return
        }

        do {
            let endFrame = try OpalCastFrame.fileTransferChunk(
                FileTransferChunkPayload(transferId: transferId, chunkFlags: .end)
            )
            await sendFrame(endFrame)
        } catch {
            OpalDeskLog.file.error("Failed to encode END chunk for transfer \(transferId): \(error.localizedDescription, privacy: .public)")
            await markFailed(transferId: transferId, reason: error.localizedDescription, sendFrame: sendFrame, onProgress: onProgress)
            return
        }

        let awaiting: FileTransferProgress? = state.withLock { s in
            s.activeTasks.removeValue(forKey: transferId)
            guard var p = s.activeTransfers[transferId] else { return nil }
            p.sentBytes = totalSize
            p.status = .awaitingAck
            s.activeTransfers[transferId] = p
            return p
        }
        if let awaiting {
            onProgress(awaiting)
        }
    }

    private func markFailed(
        transferId: UInt32,
        reason: String,
        sendFrame: @escaping @Sendable (OpalCastFrame) async -> Void,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) async {
        do {
            let abortFrame = try OpalCastFrame.fileTransferChunk(
                FileTransferChunkPayload(transferId: transferId, chunkFlags: .abort)
            )
            await sendFrame(abortFrame)
        } catch {
            OpalDeskLog.file.error("Failed to encode ABORT chunk for transfer \(transferId): \(error.localizedDescription, privacy: .public)")
        }
        let updated: FileTransferProgress? = state.withLock { s in
            s.activeTasks.removeValue(forKey: transferId)
            guard var p = s.activeTransfers.removeValue(forKey: transferId) else { return nil }
            p.status = .failed(reason: reason)
            return p
        }
        if let updated {
            onProgress(updated)
        }
    }

    private func sendAbortSync(
        transferId: UInt32,
        sendFrame: @escaping @Sendable (OpalCastFrame) -> Void
    ) {
        do {
            let abortFrame = try OpalCastFrame.fileTransferChunk(
                FileTransferChunkPayload(transferId: transferId, chunkFlags: .abort)
            )
            sendFrame(abortFrame)
        } catch {
            OpalDeskLog.file.error("Failed to encode ABORT chunk for transfer \(transferId): \(error.localizedDescription, privacy: .public)")
        }
    }
}
