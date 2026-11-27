import AVFoundation
import Foundation
import os
import Testing
@testable import OpalDeskCore

@Suite("Audio Codec, ADB Device Parser & FileSender")
struct AudioAndFileTests {

    @Test("Parses 2-byte MPEG-4 AudioSpecificConfig for 48kHz stereo AAC-LC and builds ESDS")
    func parseAudioSpecificConfig() {
        // AAC-LC (objectType=2 -> 00010), 48000 Hz (index=3 -> 0011), stereo (channels=2 -> 0010)
        // Binary: 00010_001 1_0010_000 = 0x11 0x90
        let asc = Data([0x11, 0x90])
        let info = AudioSpecificConfigInfo.parse(asc)
        #expect(info == AudioSpecificConfigInfo(audioObjectType: 2, sampleRate: 48_000, channels: 2))

        let esds = AudioSpecificConfigInfo.wrapInESDS(asc)
        #expect(esds.first == 0x03) // ES_Descriptor tag
        #expect(esds.contains(0x05)) // DecoderSpecificInfo tag
        #expect(esds.suffix(5).prefix(2) == asc) // ASC sits right before SLConfigDescriptor (0x06 0x01 0x02)
    }

    @Test("AudioStreamPlayer converter decodes PCM-16 packets cleanly")
    func decodePCM16Packet() {
        let cfg = AudioConfigPayload(codec: .pcm16, sampleRate: 48_000, channels: 2, codecSpecificData: Data())
        let pipeline = AudioStreamPlayer.makeConverter(for: cfg)
        #expect(pipeline != nil)

        if let pipeline {
            // 256 stereo Int16 frames = 256 * 2 * 2 = 1024 bytes
            var pcmBytes = Data(capacity: 1024)
            for i in 0..<512 {
                var sample: Int16 = (i % 2 == 0) ? 1000 : -1000
                withUnsafeBytes(of: &sample) { pcmBytes.append(contentsOf: $0) }
            }
            let decoded = AudioStreamPlayer.decodePacket(
                pcmBytes,
                codec: .pcm16,
                inputFormat: pipeline.inputFormat,
                outputFormat: pipeline.outputFormat,
                converter: pipeline.converter
            )
            #expect(decoded != nil)
            #expect(decoded?.frameLength == 256)
        }
    }

    @Test("AudioStreamPlayer converter creates valid AAC-LC decoder with 2-byte ASC")
    func createAACConverter() {
        let cfg = AudioConfigPayload(
            codec: .aacLC,
            sampleRate: 48_000,
            channels: 2,
            codecSpecificData: Data([0x11, 0x90])
        )
        let pipeline = AudioStreamPlayer.makeConverter(for: cfg)
        #expect(pipeline != nil)
        #expect(pipeline?.outputFormat.sampleRate == 48_000)
        #expect(pipeline?.outputFormat.channelCount == 2)
    }

    @Test("Parses adb devices -l output across ready, unauthorized, and offline devices")
    func parseAdbDevicesList() {
        let sampleOutput = """
        * daemon not running; starting now at tcp:5037
        * daemon started successfully
        List of devices attached
        4a8c1234               device usb:1-1 product:dada model:24129PN74C device:dada transport_id:4
        emulator-5554          offline transport_id:5
        9876543210             unauthorized usb:1-2 transport_id:6

        """
        let devices = AdbManager.parseDevicesOutput(sampleOutput)
        #expect(devices.count == 3)

        #expect(devices[0].serial == "4a8c1234")
        #expect(devices[0].state == .device)
        #expect(devices[0].isReady == true)
        #expect(devices[0].model == "24129PN74C")
        #expect(devices[0].transportId == "4")

        #expect(devices[1].serial == "emulator-5554")
        #expect(devices[1].state == .offline)
        #expect(devices[1].isReady == false)

        #expect(devices[2].serial == "9876543210")
        #expect(devices[2].state == .unauthorized)
        #expect(devices[2].isReady == false)
    }

    @Test("parseDevicesOutput preserves wireless mDNS ADB serials containing spaces")
    func parseWirelessAdbSerialWithSpaces() {
        let sampleOutput = """
        List of devices attached
        adb-7145c462-cZWI2H (3)._adb-tls-connect._tcp device product:shennong model:23116PN5BC device:shennong transport_id:2
        adb-8519b872-2oxyq3._adb-tls-connect._tcp device product:PFJM10 model:PFJM10 device:OP5227 transport_id:1
        0123456789abcdef no permissions (missing udev rules) transport_id:3
        """
        let devices = AdbManager.parseDevicesOutput(sampleOutput)
        #expect(devices.count == 3)

        #expect(devices[0].serial == "adb-7145c462-cZWI2H (3)._adb-tls-connect._tcp")
        #expect(devices[0].state == .device)
        #expect(devices[0].isReady == true)
        #expect(devices[0].isWirelessAdb == true)
        #expect(devices[0].model == "23116PN5BC")
        #expect(devices[0].transportId == "2")

        #expect(devices[1].serial == "adb-8519b872-2oxyq3._adb-tls-connect._tcp")
        #expect(devices[1].state == .device)
        #expect(devices[1].model == "PFJM10")

        #expect(devices[2].serial == "0123456789abcdef")
        #expect(devices[2].state == .unknown)
        #expect(devices[2].isReady == false)
    }

    @Test("FileSender streams file into START, DATA chunks, END and processes ACK")
    func fileSenderStreamingRoundtrip() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-file-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileURL = tempDir.appendingPathComponent("sample-payload.bin")
        let originalData = Data((0..<12_345).map { UInt8($0 & 0xFF) })
        try originalData.write(to: fileURL)

        let collectedFrames = OSAllocatedUnfairLock<[OpalCastFrame]>(initialState: [])
        let latestProgress = OSAllocatedUnfairLock<FileTransferProgress?>(initialState: nil)

        let sender = FileSender(chunkSize: 4096)
        let transferId = try sender.sendFile(
            at: fileURL,
            sendFrame: { frame in
                collectedFrames.withLock { $0.append(frame) }
            },
            onProgress: { progress in
                latestProgress.withLock { $0 = progress }
            }
        )

        // Wait up to 2 seconds for streaming task to emit END
        for _ in 0..<200 {
            let status = latestProgress.withLock { $0?.status }
            if status == .awaitingAck {
                break
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        let frames = collectedFrames.withLock { $0 }
        // Expected: 1 START + 4 DATA chunks (4096 + 4096 + 4096 + 57) + 1 END = 6 frames
        #expect(frames.count == 6)

        let startChunk = try FileTransferChunkPayload.decode(from: frames[0].payload)
        #expect(startChunk.transferId == transferId)
        #expect(startChunk.chunkFlags == .start)
        #expect(startChunk.fileName == "sample-payload.bin")
        #expect(startChunk.totalSize == Int64(originalData.count))

        var reconstructed = Data()
        for frame in frames[1..<(frames.count - 1)] {
            let chunk = try FileTransferChunkPayload.decode(from: frame.payload)
            #expect(chunk.transferId == transferId)
            #expect(chunk.chunkFlags == .data)
            reconstructed.append(chunk.data)
        }
        #expect(reconstructed == originalData)

        let endChunk = try FileTransferChunkPayload.decode(from: frames[frames.count - 1].payload)
        #expect(endChunk.transferId == transferId)
        #expect(endChunk.chunkFlags == .end)

        // Deliver simulated Android ACK
        let ack = FileTransferAckPayload(
            transferId: transferId,
            status: .ok,
            pathOrError: "/sdcard/Download/OpalDesk/sample-payload.bin"
        )
        sender.handleAck(ack) { progress in
            latestProgress.withLock { $0 = progress }
        }

        let finalProgress = latestProgress.withLock { $0 }
        #expect(finalProgress?.status == .completed(remotePath: "/sdcard/Download/OpalDesk/sample-payload.bin"))
        #expect(finalProgress?.fractionCompleted == 1.0)
        // Terminal transfer must be evicted so long sessions do not leak transfer state.
        #expect(sender.trackedTransferCount == 0)
    }

    @Test("AudioStreamPlayer rejects unsupported opus codec cleanly and tears down without deadlock")
    func audioUnsupportedCodecAndSafeDeinit() async throws {
        let opusConfig = AudioConfigPayload(codec: .opus, sampleRate: 48_000, channels: 2, codecSpecificData: Data())
        #expect(AudioStreamPlayer.makeConverter(for: opusConfig) == nil)

        // Queue async work and immediately drop reference to ensure deinit never deadlocks on audioQueue.
        do {
            let player = AudioStreamPlayer()
            player.setMuted(true)
            player.enqueuePacket(Data([0x01, 0x02, 0x03, 0x04]))
            player.stop()
        }
        try await Task.sleep(nanoseconds: 20_000_000)
    }

    @Test("AdbDaemonBootstrapper script mirrors start_opaldesk.sh security & MIUI invariants")
    func adbBootstrapScriptInvariants() {
        let withHelper = AdbDaemonBootstrapper.buildRemoteBootstrapScript(expectHelperInstalled: true)
        #expect(withHelper.contains("appops set io.github.lengsukq.opaldesk 10021 allow"))
        #expect(withHelper.contains("runcon u:r:shell:s0"))
        #expect(withHelper.contains("set -C; : > /data/local/tmp/opaldesk.log"))
        #expect(withHelper.contains("chown -h 2000:2000"))
        #expect(withHelper.contains("|| [ -L /data/local/tmp/opaldesk.jar ]"))
        #expect(withHelper.contains("pkill -KILL -f"))
        #expect(withHelper.contains("pkill -9 -f"))
        #expect(withHelper.contains("exit 24"))

        let withoutHelper = AdbDaemonBootstrapper.buildRemoteBootstrapScript(expectHelperInstalled: false)
        #expect(withoutHelper.contains("exit 23"))
        #expect(withoutHelper.contains("runcon u:r:shell:s0"))
        #expect(AdbError.untrustedApk(serial: "ABC").localizedDescription.contains("io.github.lengsukq.opaldesk"))
    }

    @Test("FileSender cancellation emits ABORT, marks cancelled, and evicts transfer immediately")
    func fileSenderCancelEvictsAndEmitsAbort() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-cancel-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileURL = tempDir.appendingPathComponent("large-sample.bin")
        try Data(repeating: 0x5A, count: 32_768).write(to: fileURL)

        let gateState = OSAllocatedUnfairLock<(
            startSentContinuation: CheckedContinuation<Void, Never>?,
            startObserved: Bool,
            resumeSendContinuation: CheckedContinuation<Void, Never>?,
            released: Bool
        )>(initialState: (nil, false, nil, false))

        let collectedFrames = OSAllocatedUnfairLock<[OpalCastFrame]>(initialState: [])
        let observedStatuses = OSAllocatedUnfairLock<[FileTransferStatus]>(initialState: [])

        let sender = FileSender(chunkSize: 4096)
        let transferId = try sender.sendFile(
            at: fileURL,
            sendFrame: { frame in
                collectedFrames.withLock { $0.append(frame) }
                // Pause right after the START frame so cancellation is 100% deterministic.
                let shouldBlock: Bool = gateState.withLock { s in
                    if !s.startObserved {
                        s.startObserved = true
                        s.startSentContinuation?.resume()
                        s.startSentContinuation = nil
                        return !s.released
                    }
                    return false
                }
                if shouldBlock {
                    await withCheckedContinuation { cont in
                        let alreadyReleased = gateState.withLock { s -> Bool in
                            if s.released { return true }
                            s.resumeSendContinuation = cont
                            return false
                        }
                        if alreadyReleased { cont.resume() }
                    }
                }
            },
            onProgress: { progress in
                observedStatuses.withLock { $0.append(progress.status) }
            }
        )

        // Deterministically wait until START frame is emitted.
        await withCheckedContinuation { cont in
            let alreadyObserved = gateState.withLock { s -> Bool in
                if s.startObserved { return true }
                s.startSentContinuation = cont
                return false
            }
            if alreadyObserved { cont.resume() }
        }

        #expect(sender.trackedTransferCount == 1)

        let abortFrames = OSAllocatedUnfairLock<[OpalCastFrame]>(initialState: [])
        sender.cancel(
            transferId: transferId,
            sendFrame: { frame in abortFrames.withLock { $0.append(frame) } },
            onProgress: { progress in observedStatuses.withLock { $0.append(progress.status) } }
        )

        // Evicted immediately on cancel, ABORT emitted, and cancelled status reported.
        #expect(sender.trackedTransferCount == 0)
        let aborts = abortFrames.withLock { $0 }
        #expect(aborts.count == 1)
        let decodedAbort = try FileTransferChunkPayload.decode(from: aborts[0].payload)
        #expect(decodedAbort.transferId == transferId)
        #expect(decodedAbort.chunkFlags == .abort)
        #expect(observedStatuses.withLock { $0.contains(.cancelled) })

        // Release paused sendFrame so background task finishes cleanly without emitting awaitingAck.
        let contToResume: CheckedContinuation<Void, Never>? = gateState.withLock { s in
            s.released = true
            let c = s.resumeSendContinuation
            s.resumeSendContinuation = nil
            return c
        }
        contToResume?.resume()
        await Task.yield()

        #expect(sender.trackedTransferCount == 0)
        #expect(!observedStatuses.withLock { $0.contains(.awaitingAck) })
    }

    @Test("FileSender handles error ACK eviction, empty files, and input validation errors")
    func fileSenderErrorAckEmptyFileAndValidation() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-file-edge-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let sender = FileSender(chunkSize: 4096)

        // Directory throws directoryNotSupported
        #expect(throws: FileSenderError.directoryNotSupported(tempDir.lastPathComponent)) {
            _ = try sender.sendFile(at: tempDir, sendFrame: { _ in }, onProgress: { _ in })
        }
        #expect(sender.trackedTransferCount == 0)

        // Missing file throws
        let missingURL = tempDir.appendingPathComponent("does-not-exist.bin")
        #expect(throws: Error.self) {
            _ = try sender.sendFile(at: missingURL, sendFrame: { _ in }, onProgress: { _ in })
        }
        #expect(sender.trackedTransferCount == 0)

        // 0-byte empty file sends START + END (no DATA chunks), avoids divide-by-zero, and evicts on error ACK.
        let emptyFile = tempDir.appendingPathComponent("empty.txt")
        try Data().write(to: emptyFile)

        let framesBox = OSAllocatedUnfairLock<[OpalCastFrame]>(initialState: [])
        let awaitingGate = OSAllocatedUnfairLock<(
            cont: CheckedContinuation<FileTransferProgress, Never>?,
            value: FileTransferProgress?
        )>(initialState: (nil, nil))

        let transferId = try sender.sendFile(
            at: emptyFile,
            sendFrame: { frame in framesBox.withLock { $0.append(frame) } },
            onProgress: { progress in
                if progress.status == .awaitingAck {
                    let cont = awaitingGate.withLock { s -> CheckedContinuation<FileTransferProgress, Never>? in
                        s.value = progress
                        let c = s.cont
                        s.cont = nil
                        return c
                    }
                    cont?.resume(returning: progress)
                }
            }
        )

        let awaitingProgress = await withCheckedContinuation { cont in
            let existing = awaitingGate.withLock { s -> FileTransferProgress? in
                if let v = s.value { return v }
                s.cont = cont
                return nil
            }
            if let existing { cont.resume(returning: existing) }
        }

        #expect(awaitingProgress.totalBytes == 0)
        #expect(awaitingProgress.fractionCompleted == 1.0)
        #expect(FileTransferProgress(transferId: 1, fileName: "empty.txt", totalBytes: 0, status: .sending).fractionCompleted == 0.0)

        let frames = framesBox.withLock { $0 }
        #expect(frames.count == 2) // START + END only
        #expect(try FileTransferChunkPayload.decode(from: frames[0].payload).chunkFlags == .start)
        #expect(try FileTransferChunkPayload.decode(from: frames[1].payload).chunkFlags == .end)
        #expect(sender.trackedTransferCount == 1)

        // Simulate Android error ACK -> transitions to .failed and evicts entry.
        let finalBox = OSAllocatedUnfairLock<FileTransferProgress?>(initialState: nil)
        sender.handleAck(FileTransferAckPayload(transferId: transferId, status: .error, pathOrError: "storage full")) { p in
            finalBox.withLock { $0 = p }
        }
        #expect(finalBox.withLock { $0?.status } == .failed(reason: "storage full"))
        #expect(sender.trackedTransferCount == 0)
    }

    @Test("FileSender remoteFileNameOverride targets browsed directory or @apk_stage while keeping UI basename")
    func fileSenderRemoteFileNameOverride() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-override-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let apkURL = tempDir.appendingPathComponent("demo.xapk")
        try Data([0x50, 0x4B, 0x03, 0x04]).write(to: apkURL)

        let framesBox = OSAllocatedUnfairLock<[OpalCastFrame]>(initialState: [])
        let progressBox = OSAllocatedUnfairLock<FileTransferProgress?>(initialState: nil)
        let sender = FileSender(chunkSize: 4096)

        let tid = try sender.sendFile(
            at: apkURL,
            remoteFileNameOverride: "@apk_stage/demo.xapk",
            sendFrame: { frame in framesBox.withLock { $0.append(frame) } },
            onProgress: { progress in progressBox.withLock { $0 = progress } }
        )

        for _ in 0..<200 {
            if progressBox.withLock({ $0?.status }) == .awaitingAck { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        let frames = framesBox.withLock { $0 }
        #expect(frames.count == 3) // START + DATA + END
        let startChunk = try FileTransferChunkPayload.decode(from: frames[0].payload)
        #expect(startChunk.transferId == tid)
        #expect(startChunk.fileName == "@apk_stage/demo.xapk")
        #expect(progressBox.withLock { $0?.fileName } == "demo.xapk")

        // Oversized remoteFileNameOverride throws fileNameTooLong
        let oversized = "/" + String(repeating: "a", count: OpalCastProtocol.maxFileNameUTF8Bytes + 1)
        #expect(throws: FileSenderError.fileNameTooLong(oversized)) {
            _ = try sender.sendFile(
                at: apkURL,
                remoteFileNameOverride: oversized,
                sendFrame: { _ in },
                onProgress: { _ in }
            )
        }
    }
}
