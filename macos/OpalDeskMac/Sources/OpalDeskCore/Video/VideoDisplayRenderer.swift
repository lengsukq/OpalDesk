@preconcurrency import AVFoundation
import CoreGraphics
import CoreImage
@preconcurrency import CoreMedia
import Foundation
import VideoToolbox
import os

public struct VideoRendererMetrics: Sendable, Equatable {
    public var width: Int = 0
    public var height: Int = 0
    public var rotation: Int = 0
    public var codec: OpalCastVideoCodec?
    public var currentFPS: Double = 0
    public var bitrateKbps: Double = 0
    public var totalFramesDecoded: UInt64 = 0
    public var droppedFrames: UInt64 = 0

    public init() {}
}

/// Encoded video frame snapshot delivered to attached passthrough recorders or on-demand frame taps.
/// Retains the existing `CMSampleBuffer` reference with zero bitstream copying.
public struct VideoEncodedFrameSample: @unchecked Sendable {
    public let sampleBuffer: CMSampleBuffer
    public let formatDescription: CMVideoFormatDescription
    public let timestampUs: Int64
    public let isKeyframe: Bool
    public let rotation: Int
    public let codec: OpalCastVideoCodec

    public init(
        sampleBuffer: CMSampleBuffer,
        formatDescription: CMVideoFormatDescription,
        timestampUs: Int64,
        isKeyframe: Bool,
        rotation: Int = 0,
        codec: OpalCastVideoCodec = .hevc
    ) {
        self.sampleBuffer = sampleBuffer
        self.formatDescription = formatDescription
        self.timestampUs = timestampUs
        self.isKeyframe = isKeyframe
        self.rotation = rotation
        self.codec = codec
    }
}

/// Zero-copy hardware VideoToolbox decoder & renderer backed by `AVSampleBufferDisplayLayer`.
/// Accepts Annex-B `VIDEO_CONFIG` and `VIDEO_FRAME` payloads, automatically handles dynamic
/// resolution/parameter-set changes, requests IDR keyframes on decoder recovery, and exposes
/// zero-overhead passthrough sample taps and on-demand frame capture for screenshots & recording.
public final class VideoDisplayRenderer: @unchecked Sendable {
    public typealias EncodedFrameTap = @Sendable (VideoEncodedFrameSample) -> Void

    private static let maxPendingDecodeFrames: Int = 4
    private static let hudUpdateIntervalNs: UInt64 = 500_000_000 // 0.5s
    private static let idrThrottleIntervalNs: UInt64 = 500_000_000 // 0.5s
    private static let maxGopCacheFrames: Int = 180
    private static let maxGopCacheBytes: Int = 16 * 1024 * 1024 // 16 MiB bounded GOP reference cache

    private struct DecoderState: @unchecked Sendable {
        var codec: OpalCastVideoCodec = .hevc
        var parameterSets = ExtractedParameterSets()
        var formatDescription: CMVideoFormatDescription?
        var awaitingKeyframe: Bool = true
        var pendingDecodeFrames: Int = 0
        var lastIDRRequestNs: UInt64 = 0
        var metrics = VideoRendererMetrics()
        var windowFrameCount: Int = 0
        var windowByteCount: Int = 0
        var windowStartNs: UInt64 = DispatchTime.now().uptimeNanoseconds
        // Bounded GOP reference cache (retains existing CMSampleBuffer refs only; zero copy / zero decode on hot path).
        var cachedGopSamples: [CMSampleBuffer] = []
        var cachedGopByteCount: Int = 0
        var cachedGopFormatDescription: CMVideoFormatDescription?
        var cachedGopComplete: Bool = false
    }

    public let displayLayer: AVSampleBufferDisplayLayer
    private let sampleRenderer: AVSampleBufferVideoRenderer
    private let decodeQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.video-decode", qos: .userInteractive)
    private let state = OSAllocatedUnfairLock(initialState: DecoderState())
    private let callbacks = OSAllocatedUnfairLock<(
        onRequestIDR: (@Sendable () -> Void)?,
        onMetricsUpdate: (@Sendable (VideoRendererMetrics) -> Void)?
    )>(initialState: (nil, nil))
    private let frameTaps = OSAllocatedUnfairLock<[UUID: EncodedFrameTap]>(initialState: [:])

    @MainActor
    public init() {
        let layer = AVSampleBufferDisplayLayer()
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = CGColor.black
        self.displayLayer = layer
        self.sampleRenderer = layer.sampleBufferRenderer
    }

    public func setCallbacks(
        onRequestIDR: (@Sendable () -> Void)?,
        onMetricsUpdate: (@Sendable (VideoRendererMetrics) -> Void)?
    ) {
        callbacks.withLock {
            $0.onRequestIDR = onRequestIDR
            $0.onMetricsUpdate = onMetricsUpdate
        }
    }

    // MARK: - Encoded Frame Tap & On-Demand Frame Capture

    /// Registers a thread-safe passthrough frame tap invoked on `decodeQueue` whenever a valid
    /// `CMSampleBuffer` is submitted for display. Zero overhead when no taps are registered.
    @discardableResult
    public func addEncodedFrameTap(_ handler: @escaping EncodedFrameTap) -> UUID {
        let token = UUID()
        frameTaps.withLock { $0[token] = handler }
        return token
    }

    /// Removes a previously registered encoded frame tap.
    public func removeEncodedFrameTap(_ token: UUID) {
        frameTaps.withLock { _ = $0.removeValue(forKey: token) }
    }

    /// Returns `true` when at least one passthrough frame tap (e.g. screen recorder) is attached.
    public var hasEncodedFrameTaps: Bool {
        frameTaps.withLock { !$0.isEmpty }
    }

    /// Latest active `CMVideoFormatDescription`, if configured.
    public var currentFormatDescription: CMVideoFormatDescription? {
        state.withLock { $0.formatDescription }
    }

    /// Returns `true` when at least one decodable keyframe-anchored frame is cached for screenshot capture.
    public var hasCachedFrame: Bool {
        state.withLock { !$0.cachedGopSamples.isEmpty && $0.cachedGopFormatDescription != nil }
    }

    /// Explicitly requests a fresh IDR keyframe from the remote Android encoder.
    public func requestIDR(bypassThrottle: Bool = false) {
        if bypassThrottle {
            let nowNs = DispatchTime.now().uptimeNanoseconds
            state.withLock { $0.lastIDRRequestNs = nowNs }
            let cb = callbacks.withLock { $0.onRequestIDR }
            cb?()
        } else {
            requestIDRThrottled()
        }
    }

    private struct SendableSampleBuffer: @unchecked Sendable {
        let value: CMSampleBuffer
    }

    private struct GopSnapshot: @unchecked Sendable {
        let samples: [CMSampleBuffer]
        let formatDescription: CMVideoFormatDescription
    }

    private struct PixelBufferBox: @unchecked Sendable {
        var buffer: CVPixelBuffer?
    }

    /// Captures the latest displayed frame on demand into a `CGImage` using an ephemeral
    /// hardware `VTDecompressionSession` over the cached GOP reference buffer.
    /// Consumes zero extra CPU/GPU while streaming when not invoked.
    public func captureCurrentFrameCGImage() -> CGImage? {
        let snapshot: GopSnapshot? = state.withLock { s in
            guard !s.cachedGopSamples.isEmpty, let desc = s.cachedGopFormatDescription else {
                return nil
            }
            return GopSnapshot(samples: s.cachedGopSamples, formatDescription: desc)
        }
        guard let snapshot else { return nil }
        return Self.decodeCGImage(from: snapshot.samples, formatDescription: snapshot.formatDescription)
    }

    /// Captures the latest displayed frame asynchronously off the caller's thread.
    /// If the cached GOP is empty or truncated (e.g. before the first IDR), requests an IDR keyframe
    /// and waits up to `timeoutSeconds` for a clean decodable keyframe.
    public func captureCurrentFrameCGImageAsync(timeoutSeconds: Double = 2.0) async -> CGImage? {
        let fastSnapshot: GopSnapshot? = state.withLock { s in
            guard s.cachedGopComplete, !s.cachedGopSamples.isEmpty, let desc = s.cachedGopFormatDescription else {
                return nil
            }
            return GopSnapshot(samples: s.cachedGopSamples, formatDescription: desc)
        }

        if let fastSnapshot {
            return await Task.detached(priority: .userInitiated) {
                Self.decodeCGImage(from: fastSnapshot.samples, formatDescription: fastSnapshot.formatDescription)
            }.value
        }

        // Request an IDR keyframe and wait briefly for a clean single-frame decode.
        let clampedTimeoutNs = UInt64(max(timeoutSeconds, 0.05) * 1_000_000_000)
        let receivedSample: VideoEncodedFrameSample? = await withCheckedContinuation { continuation in
            let gate = OSAllocatedUnfairLock<(resumed: Bool, token: UUID?)>(initialState: (false, nil))

            let finish: @Sendable (VideoEncodedFrameSample?) -> Void = { [weak self] sample in
                let result: (shouldResume: Bool, token: UUID?) = gate.withLock { g in
                    guard !g.resumed else { return (false, nil) }
                    g.resumed = true
                    let tok = g.token
                    g.token = nil
                    return (true, tok)
                }
                if let tokenToRemove = result.token {
                    self?.removeEncodedFrameTap(tokenToRemove)
                }
                if result.shouldResume {
                    continuation.resume(returning: sample)
                }
            }

            let token = self.addEncodedFrameTap { sample in
                guard sample.isKeyframe else { return }
                finish(sample)
            }
            let alreadyResumed: Bool = gate.withLock { g in
                if g.resumed {
                    return true
                }
                g.token = token
                return false
            }
            if alreadyResumed {
                self.removeEncodedFrameTap(token)
                return
            }

            self.requestIDR(bypassThrottle: true)

            Task.detached(priority: .utility) {
                try? await Task.sleep(nanoseconds: clampedTimeoutNs)
                finish(nil)
            }
        }

        if let receivedSample {
            return await Task.detached(priority: .userInitiated) {
                Self.decodeCGImage(from: [receivedSample.sampleBuffer], formatDescription: receivedSample.formatDescription)
            }.value
        }

        // Last-resort fallback to any cached keyframe if IDR request timed out.
        return await Task.detached(priority: .userInitiated) { [weak self] in
            self?.captureCurrentFrameCGImage()
        }.value
    }

    public func reset() {
        decodeQueue.async { [weak self] in
            guard let self else { return }
            self.state.withLock { s in
                s.parameterSets = ExtractedParameterSets()
                s.formatDescription = nil
                s.awaitingKeyframe = true
                s.pendingDecodeFrames = 0
                s.windowFrameCount = 0
                s.windowByteCount = 0
                s.windowStartNs = DispatchTime.now().uptimeNanoseconds
                s.metrics = VideoRendererMetrics()
                s.cachedGopSamples.removeAll(keepingCapacity: false)
                s.cachedGopByteCount = 0
                s.cachedGopFormatDescription = nil
                s.cachedGopComplete = false
            }
            self.sampleRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
        }
    }

    /// Processes an incoming `0x01 VIDEO_CONFIG` payload containing codec metadata and Annex-B CSD.
    public func configure(_ config: VideoConfigPayload) {
        decodeQueue.async { [weak self] in
            guard let self else { return }
            let units = AnnexBParser.splitNALUnits(from: config.csd, codec: config.codec)
            let extracted = AnnexBParser.extractParameterSets(from: units, codec: config.codec)

            let createdFormat: CMVideoFormatDescription?
            if extracted.isComplete(for: config.codec) {
                do {
                    createdFormat = try AnnexBParser.makeFormatDescription(from: extracted, codec: config.codec)
                } catch {
                    OpalDeskLog.video.error("Failed to build format description from VIDEO_CONFIG: \(error.localizedDescription, privacy: .public)")
                    createdFormat = nil
                }
            } else {
                createdFormat = nil
            }

            let metricsSnapshot: VideoRendererMetrics = self.state.withLock { s in
                s.codec = config.codec
                s.parameterSets = extracted
                s.formatDescription = createdFormat
                s.awaitingKeyframe = true
                s.metrics.codec = config.codec
                s.metrics.width = Int(config.width)
                s.metrics.height = Int(config.height)
                s.metrics.rotation = Int(config.rotation)
                s.cachedGopSamples.removeAll(keepingCapacity: false)
                s.cachedGopByteCount = 0
                s.cachedGopFormatDescription = nil
                s.cachedGopComplete = false
                return s.metrics
            }

            // Flush previous stream geometry cleanly so VideoToolbox accepts the new format description.
            self.sampleRenderer.flush()
            self.emitMetrics(metricsSnapshot)
        }
    }

    /// Processes an incoming `0x02 VIDEO_FRAME` Annex-B payload and submits it immediately to VideoToolbox.
    /// Applies low-latency backlog shedding so `decodeQueue` never builds up multi-frame lag.
    public func enqueueFrame(payload: Data, flags: OpalCastVideoFrameFlags, timestampUs: Int64) {
        let isMarkedKey = flags.contains(.keyframe)
        let shouldDropBacklog: Bool = state.withLock { s in
            if s.pendingDecodeFrames >= Self.maxPendingDecodeFrames && !isMarkedKey {
                s.metrics.droppedFrames += 1
                s.awaitingKeyframe = true
                return true
            }
            s.pendingDecodeFrames += 1
            return false
        }
        if shouldDropBacklog {
            requestIDRThrottled()
            return
        }
        decodeQueue.async { [weak self] in
            guard let self else { return }
            defer {
                self.state.withLock { s in
                    s.pendingDecodeFrames = max(0, s.pendingDecodeFrames - 1)
                }
            }
            self.processVideoFrame(payload: payload, flags: flags, timestampUs: timestampUs)
        }
    }

    // MARK: - Decode Pipeline (runs on `decodeQueue`)

    private func processVideoFrame(payload: Data, flags: OpalCastVideoFrameFlags, timestampUs: Int64) {
        guard !payload.isEmpty else { return }

        let renderer = sampleRenderer
        if renderer.status == .failed {
            OpalDeskLog.video.warning("AVSampleBufferVideoRenderer entered failed state; flushing and requesting IDR")
            renderer.flush()
            state.withLock { $0.awaitingKeyframe = true }
            requestIDRThrottled()
        }

        let codec = state.withLock { $0.codec }
        let ranges = AnnexBParser.splitNALRanges(from: payload, codec: codec)
        guard !ranges.isEmpty else { return }

        // Handle inline SPS/PPS/VPS updates only when parameter-set NALs are actually present.
        if flags.contains(.configInline) || AnnexBParser.hasParameterSetNAL(in: ranges, codec: codec) {
            let inlineParams = AnnexBParser.extractParameterSets(from: payload, ranges: ranges, codec: codec)
            if !inlineParams.sps.isEmpty || !inlineParams.pps.isEmpty || !inlineParams.vps.isEmpty {
                updateParameterSetsIfChanged(inlineParams, codec: codec)
            }
        }

        let (vclByteCount, containsKeyframeNAL) = AnnexBParser.inspectLengthPrefixed(ranges: ranges, codec: codec)
        guard vclByteCount > 0 else { return }

        let isKeyframe = flags.contains(.keyframe) || containsKeyframeNAL

        let (formatDesc, shouldDropForIDR): (CMVideoFormatDescription?, Bool) = state.withLock { s in
            guard let desc = s.formatDescription else {
                s.metrics.droppedFrames += 1
                return (nil, true)
            }
            if s.awaitingKeyframe && !isKeyframe {
                s.metrics.droppedFrames += 1
                return (nil, true)
            }
            if isKeyframe {
                s.awaitingKeyframe = false
            }
            return (desc, false)
        }

        guard let formatDescription = formatDesc, !shouldDropForIDR else {
            requestIDRThrottled()
            return
        }

        // Check renderer readiness BEFORE allocating CMBlockBuffer so dropped frames do zero memory copies.
        if !renderer.isReadyForMoreMediaData && !isKeyframe {
            state.withLock { s in
                s.metrics.droppedFrames += 1
                s.awaitingKeyframe = true
            }
            requestIDRThrottled()
            return
        }

        guard let sampleBuffer = makeSampleBuffer(
            sourcePayload: payload,
            ranges: ranges,
            vclByteCount: vclByteCount,
            codec: codec,
            formatDescription: formatDescription,
            timestampUs: timestampUs,
            isKeyframe: isKeyframe
        ) else {
            state.withLock { $0.metrics.droppedFrames += 1 }
            return
        }

        renderer.enqueue(sampleBuffer)
        let boxedSample = SendableSampleBuffer(value: sampleBuffer)
        let currentRotation: Int = state.withLock { s in
            if isKeyframe {
                s.cachedGopSamples = [boxedSample.value]
                s.cachedGopByteCount = vclByteCount
                s.cachedGopFormatDescription = formatDescription
                s.cachedGopComplete = true
            } else if s.cachedGopComplete,
                      s.cachedGopSamples.count < Self.maxGopCacheFrames,
                      (s.cachedGopByteCount + vclByteCount) <= Self.maxGopCacheBytes
            {
                s.cachedGopSamples.append(boxedSample.value)
                s.cachedGopByteCount += vclByteCount
            } else {
                s.cachedGopComplete = false
            }
            return s.metrics.rotation
        }

        let activeTaps: [EncodedFrameTap]? = frameTaps.withLock { taps in
            taps.isEmpty ? nil : Array(taps.values)
        }
        if let activeTaps {
            let encodedSample = VideoEncodedFrameSample(
                sampleBuffer: sampleBuffer,
                formatDescription: formatDescription,
                timestampUs: timestampUs,
                isKeyframe: isKeyframe,
                rotation: currentRotation,
                codec: codec
            )
            for tap in activeTaps {
                tap(encodedSample)
            }
        }

        recordDecodedFrame(byteCount: payload.count, formatDescription: formatDescription)
    }

    private func updateParameterSetsIfChanged(_ incoming: ExtractedParameterSets, codec: OpalCastVideoCodec) {
        var merged = state.withLock { $0.parameterSets }
        var changed = false

        if !incoming.vps.isEmpty && incoming.vps != merged.vps {
            merged.vps = incoming.vps
            changed = true
        }
        if !incoming.sps.isEmpty && incoming.sps != merged.sps {
            merged.sps = incoming.sps
            changed = true
        }
        if !incoming.pps.isEmpty && incoming.pps != merged.pps {
            merged.pps = incoming.pps
            changed = true
        }

        let needsCreation = state.withLock { $0.formatDescription == nil }
        guard (changed || needsCreation), merged.isComplete(for: codec) else {
            return
        }

        let finalSets = merged
        do {
            let newFormat = try AnnexBParser.makeFormatDescription(from: finalSets, codec: codec)
            let dims = CMVideoFormatDescriptionGetDimensions(newFormat)
            state.withLock { s in
                s.parameterSets = finalSets
                s.formatDescription = newFormat
                if dims.width > 0 && dims.height > 0 {
                    s.metrics.width = Int(dims.width)
                    s.metrics.height = Int(dims.height)
                }
            }
        } catch {
            OpalDeskLog.video.error("Failed to update inline format description: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func makeSampleBuffer(
        sourcePayload: Data,
        ranges: [AnnexBNALRange],
        vclByteCount: Int,
        codec: OpalCastVideoCodec,
        formatDescription: CMVideoFormatDescription,
        timestampUs: Int64,
        isKeyframe: Bool
    ) -> CMSampleBuffer? {
        let dataLength = vclByteCount
        var blockBuffer: CMBlockBuffer?

        let allocStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: dataLength,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: dataLength,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard allocStatus == kCMBlockBufferNoErr, let blockBuffer else {
            OpalDeskLog.video.warning("CMBlockBufferCreateWithMemoryBlock failed: status=\(allocStatus)")
            return nil
        }

        let assureStatus = CMBlockBufferAssureBlockMemory(blockBuffer)
        guard assureStatus == kCMBlockBufferNoErr else {
            OpalDeskLog.video.warning("CMBlockBufferAssureBlockMemory failed: status=\(assureStatus)")
            return nil
        }

        var totalLength: Int = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        let ptrStatus = CMBlockBufferGetDataPointer(
            blockBuffer,
            atOffset: 0,
            lengthAtOffsetOut: nil,
            totalLengthOut: &totalLength,
            dataPointerOut: &dataPointer
        )
        guard ptrStatus == kCMBlockBufferNoErr, let dataPointer, totalLength >= dataLength else {
            OpalDeskLog.video.warning("CMBlockBufferGetDataPointer failed: status=\(ptrStatus)")
            return nil
        }

        let destBytes = UnsafeMutableRawPointer(dataPointer).assumingMemoryBound(to: UInt8.self)
        let written = AnnexBParser.writeLengthPrefixed(
            from: sourcePayload,
            ranges: ranges,
            codec: codec,
            into: destBytes
        )
        guard written == dataLength else {
            OpalDeskLog.video.warning("Length-prefixed write mismatch: expected=\(dataLength) written=\(written)")
            return nil
        }

        // Use `.invalid` PTS together with `DisplayImmediately` so AVSampleBufferDisplayLayer renders
        // each decoded frame immediately without stalling against mismatched Android monotonic clocks.
        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: .invalid,
            decodeTimeStamp: .invalid
        )
        var sampleSize = dataLength
        var sampleBuffer: CMSampleBuffer?

        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else {
            OpalDeskLog.video.warning("CMSampleBufferCreateReady failed: status=\(sampleStatus)")
            return nil
        }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0
        {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
            if !isKeyframe {
                CFDictionarySetValue(
                    dict,
                    Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                    Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
                )
            }
        }

        return sampleBuffer
    }

    private func recordDecodedFrame(byteCount: Int, formatDescription: CMVideoFormatDescription) {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let dims = CMVideoFormatDescriptionGetDimensions(formatDescription)

        let updatedMetrics: VideoRendererMetrics? = state.withLock { s in
            s.metrics.totalFramesDecoded += 1
            if dims.width > 0 && dims.height > 0 {
                s.metrics.width = Int(dims.width)
                s.metrics.height = Int(dims.height)
            }
            s.windowFrameCount += 1
            s.windowByteCount += byteCount

            let elapsedNs = nowNs > s.windowStartNs ? nowNs - s.windowStartNs : 0
            if elapsedNs >= Self.hudUpdateIntervalNs {
                let seconds = Double(elapsedNs) / 1_000_000_000.0
                s.metrics.currentFPS = Double(s.windowFrameCount) / seconds
                s.metrics.bitrateKbps = (Double(s.windowByteCount) * 8.0) / (seconds * 1000.0)
                s.windowFrameCount = 0
                s.windowByteCount = 0
                s.windowStartNs = nowNs
                return s.metrics
            }
            return nil
        }

        if let updatedMetrics {
            emitMetrics(updatedMetrics)
        }
    }

    private func requestIDRThrottled() {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let shouldFire: Bool = state.withLock { s in
            if nowNs > s.lastIDRRequestNs && (nowNs - s.lastIDRRequestNs) < Self.idrThrottleIntervalNs {
                return false
            }
            s.lastIDRRequestNs = nowNs
            return true
        }
        if shouldFire {
            let cb = callbacks.withLock { $0.onRequestIDR }
            cb?()
        }
    }

    private func emitMetrics(_ metrics: VideoRendererMetrics) {
        let cb = callbacks.withLock { $0.onMetricsUpdate }
        cb?(metrics)
    }

    // MARK: - On-Demand VideoToolbox Decode Helpers

    /// Synchronously decodes a keyframe-anchored sequence of encoded `CMSampleBuffer`s via an ephemeral
    /// `VTDecompressionSession` and converts only the final decoded `CVPixelBuffer` into a `CGImage`.
    public static func decodeCGImage(
        from sampleBuffers: [CMSampleBuffer],
        formatDescription: CMVideoFormatDescription
    ) -> CGImage? {
        guard !sampleBuffers.isEmpty else { return nil }

        var session: VTDecompressionSession?
        let createStatus = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: formatDescription,
            decoderSpecification: nil,
            imageBufferAttributes: nil,
            outputCallback: nil,
            decompressionSessionOut: &session
        )
        guard createStatus == noErr, let decompressionSession = session else {
            OpalDeskLog.video.warning("VTDecompressionSessionCreate failed for screenshot: status=\(createStatus)")
            return nil
        }
        defer {
            VTDecompressionSessionWaitForAsynchronousFrames(decompressionSession)
            VTDecompressionSessionInvalidate(decompressionSession)
        }

        let latestPixelBuffer = OSAllocatedUnfairLock<PixelBufferBox>(initialState: PixelBufferBox())

        for (index, sampleBuffer) in sampleBuffers.enumerated() {
            var infoFlags = VTDecodeInfoFlags()
            let decodeStatus = VTDecompressionSessionDecodeFrame(
                decompressionSession,
                sampleBuffer: sampleBuffer,
                flags: [],
                infoFlagsOut: &infoFlags
            ) { status, _, imageBuffer, _, _ in
                guard status == noErr, let pixelBuffer = imageBuffer else { return }
                let boxed = PixelBufferBox(buffer: pixelBuffer)
                latestPixelBuffer.withLock { $0 = boxed }
            }
            if decodeStatus != noErr {
                OpalDeskLog.video.warning("VTDecompressionSessionDecodeFrame failed at index=\(index): status=\(decodeStatus)")
            }
        }

        VTDecompressionSessionWaitForAsynchronousFrames(decompressionSession)
        guard let finalBuffer = latestPixelBuffer.withLock({ $0 }).buffer else {
            return nil
        }
        return makeCGImage(from: finalBuffer)
    }

    /// Converts a decoded `CVPixelBuffer` into a `CGImage` using VideoToolbox with a CoreImage fallback.
    public static func makeCGImage(from pixelBuffer: CVPixelBuffer) -> CGImage? {
        var cgImage: CGImage?
        let status = VTCreateCGImageFromCVPixelBuffer(pixelBuffer, options: nil, imageOut: &cgImage)
        if status == noErr, let cgImage {
            return cgImage
        }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext(options: nil)
        return context.createCGImage(ciImage, from: ciImage.extent)
    }
}
