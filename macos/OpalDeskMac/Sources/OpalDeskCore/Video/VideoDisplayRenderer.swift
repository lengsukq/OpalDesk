import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation
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

/// Zero-copy hardware VideoToolbox decoder & renderer backed by `AVSampleBufferDisplayLayer`.
/// Accepts Annex-B `VIDEO_CONFIG` and `VIDEO_FRAME` payloads, automatically handles dynamic
/// resolution/parameter-set changes, and requests IDR keyframes on decoder recovery.
public final class VideoDisplayRenderer: @unchecked Sendable {
    private static let maxPendingDecodeFrames: Int = 4
    private static let hudUpdateIntervalNs: UInt64 = 500_000_000 // 0.5s
    private static let idrThrottleIntervalNs: UInt64 = 500_000_000 // 0.5s

    private struct DecoderState {
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
    }

    public let displayLayer: AVSampleBufferDisplayLayer
    private let sampleRenderer: AVSampleBufferVideoRenderer
    private let decodeQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.video-decode", qos: .userInteractive)
    private let state = OSAllocatedUnfairLock(initialState: DecoderState())
    private let callbacks = OSAllocatedUnfairLock<(
        onRequestIDR: (@Sendable () -> Void)?,
        onMetricsUpdate: (@Sendable (VideoRendererMetrics) -> Void)?
    )>(initialState: (nil, nil))

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
}
