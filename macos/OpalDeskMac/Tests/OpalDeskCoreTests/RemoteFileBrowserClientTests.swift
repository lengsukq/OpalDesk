import Foundation
import os
import Testing
@testable import OpalDeskCore

@Suite("RemoteFileBrowserClient RPC, Streaming Pull & APK Install")
struct RemoteFileBrowserClientTests {

    @Test("Correlates concurrent fileOp requests (list, rename, mkdir, delete, scanStorage, thumbnail)")
    func concurrentFileOperations() async throws {
        let clientBox = OSAllocatedUnfairLock<RemoteFileBrowserClient?>(initialState: nil)
        let sentRequests = OSAllocatedUnfairLock<[FileOpRequestPayload]>(initialState: [])

        let client = RemoteFileBrowserClient(defaultTimeoutSeconds: 2.0) { frame in
            guard frame.type == .fileOpRequest,
                  let req = try? FileOpRequestPayload.decode(from: frame.payload)
            else { return false }
            sentRequests.withLock { $0.append(req) }

            let targetClient = clientBox.withLock { $0 }
            Task.detached {
                switch req.op {
                case .list:
                    targetClient?.handleFileOpResponse(FileOpResponsePayload(
                        requestId: req.requestId,
                        op: "list",
                        ok: true,
                        path: req.path,
                        entries: [
                            RemoteFileEntryPayload(name: "photo.png", path: "/sdcard/DCIM/photo.png", isDir: false, size: 2048, modifiedMs: 1000, mime: "image/png")
                        ]
                    ))
                case .delete, .rename, .mkdir:
                    targetClient?.handleFileOpResponse(FileOpResponsePayload(
                        requestId: req.requestId,
                        op: req.op.rawValue,
                        ok: true,
                        path: req.path
                    ))
                case .storageScan:
                    targetClient?.handleFileOpResponse(FileOpResponsePayload(
                        requestId: req.requestId,
                        op: "storage_scan",
                        ok: true,
                        path: req.path,
                        storage: RemoteStorageSummaryPayload(
                            totalBytes: 1000,
                            freeBytes: 400,
                            usedBytes: 600,
                            categories: [RemoteStorageCategoryPayload(name: "video", bytes: 300, fileCount: 2)],
                            largeFiles: [RemoteLargeFilePayload(name: "clip.mp4", path: "/sdcard/clip.mp4", size: 200, modifiedMs: 50)]
                        )
                    ))
                case .thumbnail:
                    let thumb = Data([0xFF, 0xD8, 0xFF, 0xD9])
                    targetClient?.handleFileOpResponse(FileOpResponsePayload(
                        requestId: req.requestId,
                        op: "thumbnail",
                        ok: true,
                        path: req.path,
                        thumbnailBase64: thumb.base64EncodedString()
                    ))
                default:
                    break
                }
            }
            return true
        }
        clientBox.withLock { $0 = client }

        async let entries = client.listDirectory(path: "/sdcard/DCIM")
        async let storage = client.scanStorage()
        async let thumb = client.fetchThumbnail(path: "/sdcard/DCIM/photo.png")
        try await client.makeDirectory(path: "/sdcard/NewFolder")
        try await client.rename(from: "/sdcard/a.txt", to: "/sdcard/b.txt")
        try await client.delete(path: "/sdcard/b.txt")

        let resolvedEntries = try await entries
        let resolvedStorage = try await storage
        let resolvedThumb = try await thumb

        #expect(resolvedEntries.count == 1)
        #expect(resolvedEntries[0].name == "photo.png")
        #expect(resolvedStorage.usedBytes == 600)
        #expect(resolvedStorage.largeFiles.first?.name == "clip.mp4")
        #expect(resolvedThumb == Data([0xFF, 0xD8, 0xFF, 0xD9]))
        #expect(sentRequests.withLock { $0.count } == 6)
        #expect(client.pendingRequestCount == 0)
    }

    @Test("Handles remote errors, request timeout cleanup, and task cancellation")
    func errorsTimeoutsAndCancellation() async throws {
        // 1. Not connected when sender is nil or returns false
        let disconnectedClient = RemoteFileBrowserClient(defaultTimeoutSeconds: 1.0, sendFrame: nil)
        await #expect(throws: RemoteFileBrowserError.notConnected) {
            _ = try await disconnectedClient.listDirectory(path: "/sdcard")
        }

        // 2. Remote error response
        let errClientBox = OSAllocatedUnfairLock<RemoteFileBrowserClient?>(initialState: nil)
        let errClient = RemoteFileBrowserClient(defaultTimeoutSeconds: 1.0) { frame in
            guard let req = try? FileOpRequestPayload.decode(from: frame.payload) else { return false }
            let c = errClientBox.withLock { $0 }
            Task.detached {
                c?.handleFileOpResponse(FileOpResponsePayload(
                    requestId: req.requestId,
                    op: req.op.rawValue,
                    ok: false,
                    error: "Path outside storage root"
                ))
            }
            return true
        }
        errClientBox.withLock { $0 = errClient }

        await #expect(throws: RemoteFileBrowserError.remoteError(op: "delete", message: "Path outside storage root")) {
            try await errClient.delete(path: "/etc/passwd")
        }
        #expect(errClient.pendingRequestCount == 0)

        // 3. Timeout cleanly evicts pending continuation
        let blackholeClient = RemoteFileBrowserClient(defaultTimeoutSeconds: 0.12) { _ in true }
        await #expect(throws: RemoteFileBrowserError.requestTimedOut(op: "list", requestId: 1)) {
            _ = try await blackholeClient.listDirectory(path: "/sdcard")
        }
        #expect(blackholeClient.pendingRequestCount == 0)

        // 4. Task cancellation removes pending continuation immediately
        let cancelTask = Task {
            try await blackholeClient.listDirectory(path: "/sdcard")
        }
        try await Task.sleep(nanoseconds: 25_000_000)
        cancelTask.cancel()
        await #expect(throws: RemoteFileBrowserError.cancelled) {
            _ = try await cancelTask.value
        }
        #expect(blackholeClient.pendingRequestCount == 0)
    }

    @Test("pullFile streams 0x1C chunks to sibling .part file and atomically renames to destination")
    func pullFileMultiChunkAtomicRename() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-pull-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let destinationURL = tempDir.appendingPathComponent("backup.bin")
        let expectedBytes = Data((0..<10_000).map { UInt8(($0 * 7) & 0xFF) })

        let clientBox = OSAllocatedUnfairLock<RemoteFileBrowserClient?>(initialState: nil)
        let client = RemoteFileBrowserClient(defaultTimeoutSeconds: 2.0) { frame in
            guard frame.type == .fileOpRequest,
                  let req = try? FileOpRequestPayload.decode(from: frame.payload),
                  req.op == .pull,
                  let tid = req.transferId
            else { return false }

            let transferId = UInt32(tid)
            let c = clientBox.withLock { $0 }
            Task.detached {
                c?.handleFileOpResponse(FileOpResponsePayload(
                    requestId: req.requestId,
                    op: "pull",
                    ok: true,
                    path: req.path,
                    totalSize: Int64(expectedBytes.count)
                ))
                c?.handleFilePullChunk(FilePullChunkPayload(
                    transferId: transferId,
                    chunkFlags: .start,
                    totalSize: Int64(expectedBytes.count),
                    nameOrPath: "backup.bin"
                ))
                // Stream in 3 chunks
                let slices = [
                    expectedBytes[0..<4096],
                    expectedBytes[4096..<8192],
                    expectedBytes[8192..<expectedBytes.count]
                ]
                for slice in slices {
                    c?.handleFilePullChunk(FilePullChunkPayload(
                        transferId: transferId,
                        chunkFlags: .data,
                        totalSize: Int64(expectedBytes.count),
                        data: Data(slice)
                    ))
                }
                c?.handleFilePullChunk(FilePullChunkPayload(
                    transferId: transferId,
                    chunkFlags: .end,
                    totalSize: Int64(expectedBytes.count)
                ))
            }
            return true
        }
        clientBox.withLock { $0 = client }

        let progressEvents = OSAllocatedUnfairLock<[RemoteFilePullProgress]>(initialState: [])
        let savedURL = try await client.pullFile(
            remotePath: "/sdcard/Download/backup.bin",
            toLocalURL: destinationURL,
            onProgress: { progress in
                progressEvents.withLock { $0.append(progress) }
            }
        )

        #expect(savedURL == destinationURL)
        #expect(try Data(contentsOf: destinationURL) == expectedBytes)
        #expect(client.activePullCount == 0)

        let events = progressEvents.withLock { $0 }
        #expect(events.count >= 3)
        #expect(events.last?.receivedBytes == Int64(expectedBytes.count))
        #expect(events.last?.fractionCompleted == 1.0)

        // Verify no stray .part files remain in tempDir
        let contents = try FileManager.default.contentsOfDirectory(atPath: tempDir.path)
        #expect(contents == ["backup.bin"])
    }

    @Test("pullFile handles combined START|DATA|END, empty files, remote error, and ABORT cleanup")
    func pullFileEdgeCasesAndAbortCleanup() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-pull-edge-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        // Pre-create target to also verify atomic overwrite works
        let smallDest = tempDir.appendingPathComponent("note.txt")
        try Data("stale".utf8).write(to: smallDest)

        let mode = OSAllocatedUnfairLock<String>(initialState: "combined")
        let clientBox = OSAllocatedUnfairLock<RemoteFileBrowserClient?>(initialState: nil)
        let client = RemoteFileBrowserClient(defaultTimeoutSeconds: 2.0) { frame in
            guard let req = try? FileOpRequestPayload.decode(from: frame.payload),
                  let tid = req.transferId
            else { return false }
            let transferId = UInt32(tid)
            let currentMode = mode.withLock { $0 }
            let c = clientBox.withLock { $0 }

            Task.detached {
                switch currentMode {
                case "combined":
                    let payload = Data("fresh content!".utf8)
                    c?.handleFileOpResponse(FileOpResponsePayload(requestId: req.requestId, op: "pull", ok: true, path: req.path, totalSize: Int64(payload.count)))
                    c?.handleFilePullChunk(FilePullChunkPayload(
                        transferId: transferId,
                        chunkFlags: [.start, .data, .end],
                        totalSize: Int64(payload.count),
                        nameOrPath: "note.txt",
                        data: payload
                    ))
                case "empty":
                    c?.handleFileOpResponse(FileOpResponsePayload(requestId: req.requestId, op: "pull", ok: true, path: req.path, totalSize: 0))
                    c?.handleFilePullChunk(FilePullChunkPayload(
                        transferId: transferId,
                        chunkFlags: [.start, .end],
                        totalSize: 0,
                        nameOrPath: "empty.txt"
                    ))
                case "remoteReject":
                    c?.handleFileOpResponse(FileOpResponsePayload(requestId: req.requestId, op: "pull", ok: false, error: "File not found"))
                case "abort":
                    c?.handleFileOpResponse(FileOpResponsePayload(requestId: req.requestId, op: "pull", ok: true, path: req.path, totalSize: 1000))
                    c?.handleFilePullChunk(FilePullChunkPayload(transferId: transferId, chunkFlags: [.start, .data], totalSize: 1000, nameOrPath: "bad.bin", data: Data(repeating: 0xAA, count: 100)))
                    c?.handleFilePullChunk(FilePullChunkPayload(transferId: transferId, chunkFlags: .abort, totalSize: 0, data: Data("Disk read error".utf8)))
                default:
                    break
                }
            }
            return true
        }
        clientBox.withLock { $0 = client }

        // 1. Combined START|DATA|END atomically overwrites existing file
        _ = try await client.pullFile(remotePath: "/sdcard/note.txt", toLocalURL: smallDest)
        #expect(try String(contentsOf: smallDest, encoding: .utf8) == "fresh content!")

        // 2. Empty 0-byte file
        mode.withLock { $0 = "empty" }
        let emptyDest = tempDir.appendingPathComponent("empty.txt")
        _ = try await client.pullFile(remotePath: "/sdcard/empty.txt", toLocalURL: emptyDest)
        #expect(try Data(contentsOf: emptyDest).isEmpty)

        // 3. Remote 0x1B rejection
        mode.withLock { $0 = "remoteReject" }
        let missingDest = tempDir.appendingPathComponent("missing.bin")
        await #expect(throws: RemoteFileBrowserError.remoteError(op: "pull", message: "File not found")) {
            _ = try await client.pullFile(remotePath: "/sdcard/missing.bin", toLocalURL: missingDest)
        }
        #expect(!FileManager.default.fileExists(atPath: missingDest.path))

        // 4. Mid-stream ABORT removes partial file and throws transferAborted
        mode.withLock { $0 = "abort" }
        let abortDest = tempDir.appendingPathComponent("bad.bin")
        do {
            _ = try await client.pullFile(remotePath: "/sdcard/bad.bin", toLocalURL: abortDest)
            Issue.record("Expected transferAborted error")
        } catch let error as RemoteFileBrowserError {
            if case let .transferAborted(_, reason) = error {
                #expect(reason == "Disk read error")
            } else {
                Issue.record("Unexpected error: \(error)")
            }
        }
        // Wait briefly for IO queue cleanup to finish deleting the sibling .part file
        try await Task.sleep(nanoseconds: 30_000_000)
        let remaining = try FileManager.default.contentsOfDirectory(atPath: tempDir.path).sorted()
        #expect(remaining == ["empty.txt", "note.txt"])
        #expect(client.activePullCount == 0)
    }

    @Test("requestApkInstall streams stages through completion and terminates cleanly on disconnect")
    func apkInstallStatusStreaming() async throws {
        let clientBox = OSAllocatedUnfairLock<RemoteFileBrowserClient?>(initialState: nil)
        let capturedReq = OSAllocatedUnfairLock<ApkInstallRequestPayload?>(initialState: nil)

        let client = RemoteFileBrowserClient(defaultTimeoutSeconds: 2.0) { frame in
            guard frame.type == .apkInstallRequest,
                  let req = try? ApkInstallRequestPayload.decode(from: frame.payload)
            else { return false }
            capturedReq.withLock { $0 = req }
            let c = clientBox.withLock { $0 }
            if req.filePath.contains("disconnect") {
                Task.detached {
                    c?.handleApkInstallStatus(ApkInstallStatusPayload(installId: req.installId, stage: "extracting", progress: 20, message: "解压中"))
                    try? await Task.sleep(nanoseconds: 20_000_000)
                    c?.handleSessionDisconnected(reason: "TCP EOF")
                }
            } else {
                Task.detached {
                    c?.handleApkInstallStatus(ApkInstallStatusPayload(installId: req.installId, stage: "extracting", progress: 25, message: "正在解压"))
                    c?.handleApkInstallStatus(ApkInstallStatusPayload(installId: req.installId, stage: "installing", progress: 70, packageName: "com.demo.app", message: "正在安装"))
                    c?.handleApkInstallStatus(ApkInstallStatusPayload(installId: req.installId, stage: "succeeded", progress: 100, packageName: "com.demo.app", message: "安装成功"))
                }
            }
            return true
        }
        clientBox.withLock { $0 = client }

        // 1. Happy path through terminal succeeded stage
        var stages: [ApkInstallStatusPayload] = []
        for try await status in client.requestApkInstall(
            filePath: "@apk_stage/demo.xapk",
            format: .xapk,
            replaceExisting: true,
            allowDowngrade: false
        ) {
            stages.append(status)
        }
        #expect(stages.map(\.stage) == ["extracting", "installing", "succeeded"])
        #expect(stages.last?.packageName == "com.demo.app")
        #expect(capturedReq.withLock { $0?.format } == .xapk)
        #expect(client.activeApkInstallCount == 0)

        // 2. Session disconnect mid-install throws notConnected
        var disconnectedStages: [String] = []
        await #expect(throws: RemoteFileBrowserError.notConnected) {
            for try await status in client.requestApkInstall(filePath: "@apk_stage/disconnect.apk", format: .apk) {
                disconnectedStages.append(status.stage)
            }
        }
        #expect(disconnectedStages == ["extracting"])
        #expect(client.activeApkInstallCount == 0)
    }
}
