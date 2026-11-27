@preconcurrency import AVFoundation
import AppKit
import CoreGraphics
@preconcurrency import CoreMedia
import Foundation
import ImageIO
import Testing
import VideoToolbox
import os
@testable import OpalDeskCore

@Suite("Window Screenshot & Passthrough MP4 Screen Recording")
struct WindowMediaCaptureTests {

    // MARK: - Synthetic H.264 Annex-B Encoder Helper (Real VideoToolbox Frames)

    private struct EncodedAnnexBClip: Sendable {
        let width: Int
        let height: Int
        let csdAnnexB: Data
        let frames: [(payload: Data, isKeyframe: Bool, timestampUs: Int64)]
    }

    private static func encodeSolidColorH264Clip(
        width: Int = 320,
        height: Int = 240,
        frameColorsBGRA: [(b: UInt8, g: UInt8, r: UInt8, a: UInt8)],
        frameDurationUs: Int64 = 33_333
    ) throws -> EncodedAnnexBClip {
        var sessionOut: VTCompressionSession?
        let createStatus = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(width),
            height: Int32(height),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height
            ] as CFDictionary,
            compressedDataAllocator: nil,
            outputCallback: nil,
            refcon: nil,
            compressionSessionOut: &sessionOut
        )
        guard createStatus == noErr, let session = sessionOut else {
            throw WindowMediaCaptureError.assetWriterFailed("VTCompressionSessionCreate failed: \(createStatus)")
        }
        defer { VTCompressionSessionInvalidate(session) }

        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Baseline_AutoLevel)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: NSNumber(value: 60))
        VTCompressionSessionPrepareToEncodeFrames(session)

        let collected = OSAllocatedUnfairLock<(csd: Data?, frames: [(Data, Bool, Int64)])>(initialState: (nil, []))

        for (index, color) in frameColorsBGRA.enumerated() {
            let pixelBuffer = try makeSolidBGRAPixelBuffer(width: width, height: height, color: color)
            let ptsUs = Int64(index + 1) * frameDurationUs
            let pts = CMTime(value: ptsUs, timescale: 1_000_000)
            let frameProps: CFDictionary? = (index == 0)
                ? ([kVTEncodeFrameOptionKey_ForceKeyFrame as String: true] as CFDictionary)
                : nil

            let encodeStatus = VTCompressionSessionEncodeFrame(
                session,
                imageBuffer: pixelBuffer,
                presentationTimeStamp: pts,
                duration: CMTime(value: frameDurationUs, timescale: 1_000_000),
                frameProperties: frameProps,
                infoFlagsOut: nil
            ) { status, _, sampleBuffer in
                guard status == noErr, let sampleBuffer else { return }
                let (csd, annexB, isKey) = Self.extractAnnexB(from: sampleBuffer)
                collected.withLock { box in
                    if box.csd == nil, let csd, !csd.isEmpty {
                        box.csd = csd
                    }
                    if !annexB.isEmpty {
                        box.frames.append((annexB, isKey, ptsUs))
                    }
                }
            }
            guard encodeStatus == noErr else {
                throw WindowMediaCaptureError.assetWriterFailed("VTCompressionSessionEncodeFrame failed: \(encodeStatus)")
            }
        }

        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        let snapshot = collected.withLock { $0 }
        guard let csd = snapshot.csd, !snapshot.frames.isEmpty else {
            throw WindowMediaCaptureError.assetWriterFailed("Encoder produced no Annex-B output")
        }
        return EncodedAnnexBClip(width: width, height: height, csdAnnexB: csd, frames: snapshot.frames)
    }

    private static func makeSolidBGRAPixelBuffer(
        width: Int,
        height: Int,
        color: (b: UInt8, g: UInt8, r: UInt8, a: UInt8)
    ) throws -> CVPixelBuffer {
        var pixelBufferOut: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            nil,
            &pixelBufferOut
        )
        guard status == kCVReturnSuccess, let pixelBuffer = pixelBufferOut else {
            throw WindowMediaCaptureError.imageEncodingFailed
        }
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
        if let base = CVPixelBufferGetBaseAddress(pixelBuffer) {
            let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let ptr = base.assumingMemoryBound(to: UInt8.self)
            for y in 0..<height {
                let row = ptr.advanced(by: y * bytesPerRow)
                for x in 0..<width {
                    let offset = x * 4
                    row[offset] = color.b
                    row[offset + 1] = color.g
                    row[offset + 2] = color.r
                    row[offset + 3] = color.a
                }
            }
        }
        return pixelBuffer
    }

    private static func extractAnnexB(from sampleBuffer: CMSampleBuffer) -> (csd: Data?, frameAnnexB: Data, isKeyframe: Bool) {
        let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]
        let isKeyframe = WindowScreenRecorder.sampleBufferIsSync(sampleBuffer)
        var csdData = Data()

        if let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
            var count: Int = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                formatDesc,
                parameterSetIndex: 0,
                parameterSetPointerOut: nil,
                parameterSetSizeOut: nil,
                parameterSetCountOut: &count,
                nalUnitHeaderLengthOut: nil
            )
            for idx in 0..<count {
                var ptr: UnsafePointer<UInt8>?
                var size: Int = 0
                if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    formatDesc,
                    parameterSetIndex: idx,
                    parameterSetPointerOut: &ptr,
                    parameterSetSizeOut: &size,
                    parameterSetCountOut: nil,
                    nalUnitHeaderLengthOut: nil
                ) == noErr, let ptr, size > 0 {
                    csdData.append(contentsOf: startCode)
                    csdData.append(ptr, count: size)
                }
            }
        }

        var frameData = Data()
        if let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) {
            var totalLength: Int = 0
            var dataPtr: UnsafeMutablePointer<Int8>?
            if CMBlockBufferGetDataPointer(
                blockBuffer,
                atOffset: 0,
                lengthAtOffsetOut: nil,
                totalLengthOut: &totalLength,
                dataPointerOut: &dataPtr
            ) == kCMBlockBufferNoErr, let dataPtr {
                let bytes = UnsafeRawPointer(dataPtr).assumingMemoryBound(to: UInt8.self)
                var offset = 0
                while offset + 4 <= totalLength {
                    let nalLen = (Int(bytes[offset]) << 24)
                        | (Int(bytes[offset + 1]) << 16)
                        | (Int(bytes[offset + 2]) << 8)
                        | Int(bytes[offset + 3])
                    offset += 4
                    guard nalLen > 0, offset + nalLen <= totalLength else { break }
                    frameData.append(contentsOf: startCode)
                    frameData.append(bytes.advanced(by: offset), count: nalLen)
                    offset += nalLen
                }
            }
        }

        return (csdData.isEmpty ? nil : csdData, frameData, isKeyframe)
    }

    private static func makePCM16AudioFormatDescription(sampleRate: Double = 48_000, channels: UInt32 = 2) throws -> CMAudioFormatDescription {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
            mBytesPerPacket: 2 * channels,
            mFramesPerPacket: 1,
            mBytesPerFrame: 2 * channels,
            mChannelsPerFrame: channels,
            mBitsPerChannel: 16,
            mReserved: 0
        )
        var formatDesc: CMAudioFormatDescription?
        let status = CMAudioFormatDescriptionCreate(
            allocator: kCFAllocatorDefault,
            asbd: &asbd,
            layoutSize: 0,
            layout: nil,
            magicCookieSize: 0,
            magicCookie: nil,
            extensions: nil,
            formatDescriptionOut: &formatDesc
        )
        guard status == noErr, let formatDesc else {
            throw WindowMediaCaptureError.assetWriterFailed("CMAudioFormatDescriptionCreate failed: \(status)")
        }
        return formatDesc
    }

    private static func makeSilentPCMAudioSampleBuffer(
        formatDesc: CMAudioFormatDescription,
        frameCount: Int = 1024,
        ptsUs: Int64,
        sampleRate: Int32 = 48_000
    ) throws -> CMSampleBuffer {
        let byteCount = frameCount * 4 // 2ch * 16-bit
        var blockBuffer: CMBlockBuffer?
        let allocStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: byteCount,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: byteCount,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard allocStatus == kCMBlockBufferNoErr, let blockBuffer else {
            throw WindowMediaCaptureError.assetWriterFailed("Audio CMBlockBuffer allocation failed")
        }
        CMBlockBufferFillDataBytes(with: 0, blockBuffer: blockBuffer, offsetIntoDestination: 0, dataLength: byteCount)

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: sampleRate),
            presentationTimeStamp: CMTime(value: ptsUs, timescale: 1_000_000),
            decodeTimeStamp: .invalid
        )
        var sampleSize = 4
        var sampleBuffer: CMSampleBuffer?
        let status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDesc,
            sampleCount: frameCount,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard status == noErr, let sampleBuffer else {
            throw WindowMediaCaptureError.assetWriterFailed("Audio CMSampleBufferCreateReady failed: \(status)")
        }
        return sampleBuffer
    }

    private static func waitUntil(timeoutSeconds: Double = 2.0, _ predicate: @escaping @Sendable () -> Bool) async -> Bool {
        let deadline = DispatchTime.now().uptimeNanoseconds + UInt64(timeoutSeconds * 1_000_000_000)
        while DispatchTime.now().uptimeNanoseconds < deadline {
            if predicate() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return predicate()
    }

    // MARK: - Screenshot Tests

    @Test("Empty VideoDisplayRenderer reports no cached frame and screenshot throws noVideoFrameAvailable")
    @MainActor
    func emptyRendererScreenshotThrows() {
        let renderer = VideoDisplayRenderer()
        #expect(renderer.hasCachedFrame == false)
        #expect(renderer.captureCurrentFrameCGImage() == nil)

        #expect(throws: WindowMediaCaptureError.noVideoFrameAvailable) {
            _ = try WindowMediaCapture.captureScreenshotToFile(from: renderer)
        }
    }

    @Test("Timestamped filename generator sanitizes unsafe characters and formats cleanly")
    func timestampedFileNameFormatting() {
        var components = DateComponents()
        components.year = 2026
        components.month = 11
        components.day = 27
        components.hour = 16
        components.minute = 25
        components.second = 9
        components.nanosecond = 450_000_000
        let date = Calendar(identifier: .gregorian).date(from: components) ?? Date()

        let name = WindowMediaCapture.makeTimestampedFileName(
            prefix: "Opal/Desk:Win",
            extension: ".png",
            date: date
        )
        #expect(name.hasPrefix("Opal-Desk-Win-20261127-162509-"))
        #expect(name.hasSuffix(".png"))
        #expect(!name.contains("/"))
        #expect(!name.contains(":"))
    }

    @Test("On-demand screenshot decodes latest GOP frame via VTDecompressionSession to file and clipboard PNG")
    func screenshotCaptureToFileAndClipboard() async throws {
        let clip = try Self.encodeSolidColorH264Clip(
            width: 320,
            height: 240,
            frameColorsBGRA: [
                (b: 0, g: 0, r: 255, a: 255),   // Frame 0 (IDR): red
                (b: 255, g: 0, r: 0, a: 255),   // Frame 1 (P): blue
                (b: 0, g: 200, r: 0, a: 255)    // Frame 2 (P): green (latest)
            ]
        )

        let renderer = await MainActor.run { VideoDisplayRenderer() }
        let config = VideoConfigPayload(
            codec: .h264,
            width: UInt16(clip.width),
            height: UInt16(clip.height),
            dpi: 320,
            rotation: 0,
            fpsX100: 3000,
            displayMode: .dex,
            csd: clip.csdAnnexB
        )
        renderer.configure(config)
        for frame in clip.frames {
            renderer.enqueueFrame(
                payload: frame.payload,
                flags: frame.isKeyframe ? [.keyframe] : [],
                timestampUs: frame.timestampUs
            )
        }

        let ready = await Self.waitUntil { renderer.hasCachedFrame }
        #expect(ready == true)
        // Wait briefly for all 3 frames on decodeQueue to be cached.
        try? await Task.sleep(nanoseconds: 50_000_000)

        let captured = renderer.captureCurrentFrameCGImage()
        let cgImage = try #require(captured)
        #expect(cgImage.width == 320)
        #expect(cgImage.height == 240)

        // 1. Save to temporary directory as timestamped PNG and verify ImageIO round-trip.
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-screenshot-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let captureHelper = WindowMediaCapture(renderer: renderer)
        let savedURL = try captureHelper.captureScreenshotToFile(directory: tempDir, prefix: "TestWin")
        #expect(FileManager.default.fileExists(atPath: savedURL.path))
        #expect(savedURL.pathExtension == "png")
        #expect(savedURL.lastPathComponent.hasPrefix("TestWin-"))

        let imageSource = try #require(CGImageSourceCreateWithURL(savedURL as CFURL, nil))
        let decodedPNG = try #require(CGImageSourceCreateImageAtIndex(imageSource, 0, nil))
        #expect(decodedPNG.width == 320)
        #expect(decodedPNG.height == 240)

        // 2. Write PNG to an isolated pasteboard (never touches the user's real general pasteboard).
        let testPasteboard = NSPasteboard.withUniqueName()
        defer { testPasteboard.releaseGlobally() }
        let pngData = try captureHelper.captureScreenshotToClipboard(pasteboard: testPasteboard)
        let readBack = try #require(testPasteboard.data(forType: .png))
        #expect(readBack == pngData)
        // Verify PNG magic signature: 89 50 4E 47 0D 0A 1A 0A
        #expect(Array(readBack.prefix(8)) == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    }

    @Test("Async screenshot requests IDR and captures when cache starts empty")
    func asyncScreenshotRequestsIDRWhenEmpty() async throws {
        let clip = try Self.encodeSolidColorH264Clip(
            width: 320,
            height: 240,
            frameColorsBGRA: [(b: 10, g: 180, r: 240, a: 255)]
        )
        let renderer = await MainActor.run { VideoDisplayRenderer() }
        let idrRequested = OSAllocatedUnfairLock<Int>(initialState: 0)

        renderer.setCallbacks(
            onRequestIDR: {
                idrRequested.withLock { $0 += 1 }
                let first = clip.frames[0]
                renderer.enqueueFrame(payload: first.payload, flags: [.keyframe], timestampUs: first.timestampUs)
            },
            onMetricsUpdate: nil
        )
        renderer.configure(VideoConfigPayload(
            codec: .h264,
            width: 320,
            height: 240,
            dpi: 320,
            rotation: 0,
            fpsX100: 3000,
            displayMode: .dex,
            csd: clip.csdAnnexB
        ))
        // Allow configure to run on decodeQueue before requesting async screenshot.
        try? await Task.sleep(nanoseconds: 40_000_000)

        let image = await renderer.captureCurrentFrameCGImageAsync(timeoutSeconds: 2.0)
        let cgImage = try #require(image)
        #expect(cgImage.width == 320)
        #expect(cgImage.height == 240)
        #expect(idrRequested.withLock { $0 } >= 1)
    }

    // MARK: - Passthrough Screen Recorder Tests

    @Test("WindowScreenRecorder requests IDR, ignores pre-IDR P-frames, normalizes PTS, and writes playable MP4")
    func passthroughMP4RecordingEndToEnd() async throws {
        let clip = try Self.encodeSolidColorH264Clip(
            width: 320,
            height: 240,
            frameColorsBGRA: [
                (b: 0, g: 0, r: 220, a: 255),
                (b: 0, g: 220, r: 0, a: 255),
                (b: 220, g: 0, r: 0, a: 255),
                (b: 200, g: 200, r: 0, a: 255),
                (b: 200, g: 0, r: 200, a: 255)
            ],
            frameDurationUs: 50_000 // 20fps -> 200ms span across 5 frames
        )

        let renderer = await MainActor.run { VideoDisplayRenderer() }
        let idrRequests = OSAllocatedUnfairLock<Int>(initialState: 0)
        renderer.setCallbacks(
            onRequestIDR: { idrRequests.withLock { $0 += 1 } },
            onMetricsUpdate: nil
        )
        renderer.configure(VideoConfigPayload(
            codec: .h264,
            width: 320,
            height: 240,
            dpi: 320,
            rotation: 0,
            fpsX100: 2000,
            displayMode: .dex,
            csd: clip.csdAnnexB
        ))
        // Prime decoder with initial IDR so subsequent P-frames pass through VideoDisplayRenderer to the tap.
        renderer.enqueueFrame(payload: clip.frames[0].payload, flags: [.keyframe], timestampUs: 1_000_000)
        _ = await Self.waitUntil { renderer.hasCachedFrame }

        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-rec-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let recorder = WindowScreenRecorder()
        let outputURL = try recorder.start(
            renderer: renderer,
            directory: tempDir,
            prefix: "OpalClip",
            format: .mp4
        )
        #expect(recorder.isRecording == true)
        #expect(renderer.hasEncodedFrameTaps == true)
        #expect(idrRequests.withLock { $0 } >= 1)

        // Feed a non-keyframe first -> recorder must drop it while waiting for IDR!
        renderer.enqueueFrame(payload: clip.frames[1].payload, flags: [], timestampUs: 2_000_000)
        let droppedPreKey = await Self.waitUntil { recorder.currentMetrics.droppedPreKeyframeFrames >= 1 }
        #expect(droppedPreKey == true)
        #expect(recorder.currentMetrics.state == .waitingForKeyframe)

        // Now deliver an IDR + 4 P-frames starting at an arbitrary large Android monotonic timestamp,
        // paced slightly so VideoDisplayRenderer's 4-frame low-latency backlog shedder does not drop frame #5.
        let baseUs: Int64 = 500_000_000
        for (idx, frame) in clip.frames.enumerated() {
            let ts = baseUs + Int64(idx) * 50_000
            renderer.enqueueFrame(
                payload: frame.payload,
                flags: frame.isKeyframe ? [.keyframe] : [],
                timestampUs: ts
            )
            try? await Task.sleep(nanoseconds: 5_000_000)
        }

        let framesRecorded = await Self.waitUntil { recorder.currentMetrics.videoFrameCount == 5 }
        #expect(framesRecorded == true)
        #expect(recorder.currentMetrics.state == .recording)
        #expect(recorder.recordingDurationSeconds >= 0.19)

        let result = try await recorder.stop()
        #expect(result.url == outputURL)
        #expect(result.videoFrameCount == 5)
        #expect(result.audioSampleCount == 0)
        #expect(result.durationSeconds >= 0.19)
        #expect(result.fileSizeBytes > 0)
        #expect(recorder.isRecording == false)
        #expect(renderer.hasEncodedFrameTaps == false)
        #expect(FileManager.default.fileExists(atPath: outputURL.path))

        // Validate container integrity using AVURLAsset.
        let asset = AVURLAsset(url: outputURL)
        let videoTracks = try await asset.loadTracks(withMediaType: .video)
        #expect(videoTracks.count == 1)
        let naturalSize = try await videoTracks[0].load(.naturalSize)
        #expect(Int(naturalSize.width) == 320)
        #expect(Int(naturalSize.height) == 240)
        let assetDuration = try await asset.load(.duration)
        #expect(CMTimeGetSeconds(assetDuration) > 0.15)
    }

    @Test("WindowScreenRecorder writes optional audio track alongside passthrough video into MP4")
    func recordingWithOptionalAudioTrack() async throws {
        let clip = try Self.encodeSolidColorH264Clip(
            width: 320,
            height: 240,
            frameColorsBGRA: [
                (b: 50, g: 100, r: 200, a: 255),
                (b: 60, g: 120, r: 200, a: 255),
                (b: 70, g: 140, r: 200, a: 255)
            ],
            frameDurationUs: 50_000
        )

        let renderer = await MainActor.run { VideoDisplayRenderer() }
        renderer.configure(VideoConfigPayload(
            codec: .h264,
            width: 320,
            height: 240,
            dpi: 320,
            rotation: 0,
            fpsX100: 2000,
            displayMode: .dex,
            csd: clip.csdAnnexB
        ))
        try? await Task.sleep(nanoseconds: 30_000_000)

        let audioFormat = try Self.makePCM16AudioFormatDescription(sampleRate: 48_000, channels: 2)
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-av-rec-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let recorder = WindowScreenRecorder()
        let outURL = try recorder.start(
            renderer: renderer,
            directory: tempDir,
            prefix: "OpalAV",
            format: .mp4,
            audioFormatHint: audioFormat
        )

        let baseUs: Int64 = 10_000_000
        for (idx, frame) in clip.frames.enumerated() {
            renderer.enqueueFrame(
                payload: frame.payload,
                flags: frame.isKeyframe ? [.keyframe] : [],
                timestampUs: baseUs + Int64(idx) * 50_000
            )
        }
        let started = await Self.waitUntil { recorder.currentMetrics.state == .recording }
        #expect(started == true)

        // Append 4 audio buffers (~85ms) aligned to the recording timeline.
        for i in 0..<4 {
            let audioBuf = try Self.makeSilentPCMAudioSampleBuffer(
                formatDesc: audioFormat,
                frameCount: 1024,
                ptsUs: baseUs + Int64(i) * 21_333
            )
            recorder.appendAudioSampleBuffer(audioBuf)
        }
        let audioWritten = await Self.waitUntil { recorder.currentMetrics.audioSampleCount == 4 }
        #expect(audioWritten == true)

        let result = try await recorder.stop()
        #expect(result.videoFrameCount == 3)
        #expect(result.audioSampleCount == 4)

        let asset = AVURLAsset(url: outURL)
        let vTracks = try await asset.loadTracks(withMediaType: .video)
        let aTracks = try await asset.loadTracks(withMediaType: .audio)
        #expect(vTracks.count == 1)
        #expect(aTracks.count == 1)
    }

    @Test("WindowScreenRecorder enforces lifecycle errors and cancel cleanup")
    func recorderLifecycleErrorHandlingAndCancel() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-err-rec-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let recorder = WindowScreenRecorder()
        do {
            _ = try await recorder.stop()
            Issue.record("Expected notRecording error")
        } catch let error as WindowMediaCaptureError {
            #expect(error == .notRecording)
        }

        let url1 = tempDir.appendingPathComponent("first.mp4")
        try recorder.start(outputURL: url1, format: .mp4)

        // Double start must fail cleanly.
        #expect(throws: WindowMediaCaptureError.alreadyRecording) {
            try recorder.start(outputURL: tempDir.appendingPathComponent("second.mp4"), format: .mp4)
        }

        // Stopping before any keyframe arrived throws .noKeyframeReceived and resets to idle.
        do {
            _ = try await recorder.stop()
            Issue.record("Expected noKeyframeReceived error")
        } catch let error as WindowMediaCaptureError {
            #expect(error == .noKeyframeReceived)
        }
        #expect(recorder.isRecording == false)

        // Cancel resets immediately to idle.
        try recorder.start(outputURL: url1, format: .mov)
        #expect(recorder.isRecording == true)
        recorder.cancel()
        #expect(recorder.isRecording == false)
    }
}
