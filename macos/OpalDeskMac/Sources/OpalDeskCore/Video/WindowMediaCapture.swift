@preconcurrency import AVFoundation
import AppKit
import CoreGraphics
@preconcurrency import CoreMedia
import Foundation
import ImageIO
import UniformTypeIdentifiers
import os

// MARK: - Errors & Models

public enum WindowMediaCaptureError: Error, Sendable, Equatable, LocalizedError {
    case noVideoFrameAvailable
    case imageEncodingFailed
    case clipboardWriteFailed
    case directoryCreationFailed(String)
    case fileWriteFailed(String)
    case alreadyRecording
    case notRecording
    case noKeyframeReceived
    case assetWriterFailed(String)
    case sampleTimingCreationFailed(OSStatus)

    public var errorDescription: String? {
        switch self {
        case .noVideoFrameAvailable:
            return "No decoded video frame is currently available to capture."
        case .imageEncodingFailed:
            return "Failed to encode captured frame into PNG data."
        case .clipboardWriteFailed:
            return "Failed to write captured screenshot PNG to the macOS clipboard."
        case .directoryCreationFailed(let path):
            return "Failed to create capture output directory at \(path)."
        case .fileWriteFailed(let detail):
            return "Failed to write captured screenshot to disk: \(detail)"
        case .alreadyRecording:
            return "A screen recording session is already in progress."
        case .notRecording:
            return "No screen recording session is currently active."
        case .noKeyframeReceived:
            return "Recording stopped before any video keyframe (IDR) arrived."
        case .assetWriterFailed(let detail):
            return "AVAssetWriter failed during screen recording: \(detail)"
        case .sampleTimingCreationFailed(let status):
            return "Failed to construct timed CMSampleBuffer for recording (OSStatus: \(status))."
        }
    }
}

public enum RecordingFileFormat: String, Sendable, Equatable, CaseIterable {
    case mp4
    case mov

    public var fileExtension: String { rawValue }

    public var avFileType: AVFileType {
        switch self {
        case .mp4: return .mp4
        case .mov: return .mov
        }
    }
}

public enum WindowScreenRecorderState: String, Sendable, Equatable {
    case idle
    case waitingForKeyframe
    case recording
    case finishing
}

public struct ScreenRecordingMetrics: Sendable, Equatable {
    public var state: WindowScreenRecorderState = .idle
    public var outputURL: URL?
    public var durationSeconds: TimeInterval = 0
    public var videoFrameCount: UInt64 = 0
    public var audioSampleCount: UInt64 = 0
    public var droppedPreKeyframeFrames: UInt64 = 0
    public var droppedBackpressureFrames: UInt64 = 0

    public init() {}
}

public struct ScreenRecordingResult: Sendable, Equatable {
    public let url: URL
    public let durationSeconds: TimeInterval
    public let videoFrameCount: UInt64
    public let audioSampleCount: UInt64
    public let fileSizeBytes: Int64

    public init(
        url: URL,
        durationSeconds: TimeInterval,
        videoFrameCount: UInt64,
        audioSampleCount: UInt64,
        fileSizeBytes: Int64
    ) {
        self.url = url
        self.durationSeconds = durationSeconds
        self.videoFrameCount = videoFrameCount
        self.audioSampleCount = audioSampleCount
        self.fileSizeBytes = fileSizeBytes
    }
}

// MARK: - One-Click Window Screenshot Capture

/// On-demand screenshot capture helper backed by `VideoDisplayRenderer`.
/// Performs zero work during normal streaming; decoding occurs only when a screenshot is requested.
public struct WindowMediaCapture: Sendable {
    public let renderer: VideoDisplayRenderer

    public init(renderer: VideoDisplayRenderer) {
        self.renderer = renderer
    }

    /// Captures the current displayed frame synchronously and writes PNG data to `NSPasteboard.general`.
    @discardableResult
    public func captureScreenshotToClipboard(pasteboard: NSPasteboard = .general) throws -> Data {
        try Self.captureScreenshotToClipboard(from: renderer, pasteboard: pasteboard)
    }

    /// Captures the current displayed frame (requesting an IDR if needed) and writes PNG data to `NSPasteboard.general`.
    @discardableResult
    public func captureScreenshotToClipboardAsync(
        timeoutSeconds: Double = 2.0,
        pasteboard: NSPasteboard = .general
    ) async throws -> Data {
        try await Self.captureScreenshotToClipboardAsync(
            from: renderer,
            timeoutSeconds: timeoutSeconds,
            pasteboard: pasteboard
        )
    }

    /// Captures the current displayed frame synchronously and saves a timestamped PNG file.
    public func captureScreenshotToFile(
        directory: URL? = nil,
        prefix: String = "OpalDesk-Screenshot",
        date: Date = Date()
    ) throws -> URL {
        try Self.captureScreenshotToFile(from: renderer, directory: directory, prefix: prefix, date: date)
    }

    /// Captures the current displayed frame (requesting an IDR if needed) and saves a timestamped PNG file.
    public func captureScreenshotToFileAsync(
        directory: URL? = nil,
        prefix: String = "OpalDesk-Screenshot",
        timeoutSeconds: Double = 2.0,
        date: Date = Date()
    ) async throws -> URL {
        try await Self.captureScreenshotToFileAsync(
            from: renderer,
            directory: directory,
            prefix: prefix,
            timeoutSeconds: timeoutSeconds,
            date: date
        )
    }

    // MARK: - Static Utilities

    public static func defaultScreenshotsDirectory() -> URL {
        FileManager.default.urls(for: .desktopDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Desktop", isDirectory: true)
    }

    public static func defaultRecordingsDirectory() -> URL {
        defaultScreenshotsDirectory()
    }

    /// Generates a filesystem-safe timestamped filename such as `OpalDesk-Screenshot-20261127-162005-123.png`.
    public static func makeTimestampedFileName(
        prefix: String,
        extension ext: String,
        date: Date = Date()
    ) -> String {
        let sanitizedPrefix = prefix
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
        let finalPrefix = sanitizedPrefix.isEmpty ? "OpalDesk" : sanitizedPrefix
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss-SSS"
        let timestamp = formatter.string(from: date)
        let cleanExt = ext.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return "\(finalPrefix)-\(timestamp).\(cleanExt)"
    }

    /// Encodes a `CGImage` into lossless PNG bytes via `ImageIO`.
    public static func encodePNGData(from image: CGImage) throws -> Data {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output as CFMutableData,
            UTType.png.identifier as CFString,
            1,
            nil
        ) else {
            throw WindowMediaCaptureError.imageEncodingFailed
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else {
            throw WindowMediaCaptureError.imageEncodingFailed
        }
        return output as Data
    }

    /// Writes `image` as PNG bytes to `pasteboard` (defaults to `NSPasteboard.general`).
    @discardableResult
    public static func writeImageToClipboard(
        _ image: CGImage,
        pasteboard: NSPasteboard = .general
    ) throws -> Data {
        let pngData = try encodePNGData(from: image)
        pasteboard.clearContents()
        guard pasteboard.setData(pngData, forType: .png) else {
            throw WindowMediaCaptureError.clipboardWriteFailed
        }
        return pngData
    }

    /// Saves `image` as a timestamped PNG file in `directory` (defaults to Desktop) and returns the created URL.
    public static func saveImageToFile(
        _ image: CGImage,
        directory: URL? = nil,
        prefix: String = "OpalDesk-Screenshot",
        date: Date = Date()
    ) throws -> URL {
        let pngData = try encodePNGData(from: image)
        let targetDir = directory ?? defaultScreenshotsDirectory()
        do {
            try FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true)
        } catch {
            throw WindowMediaCaptureError.directoryCreationFailed(targetDir.path)
        }

        let fileURL = makeUniqueFileURL(
            in: targetDir,
            prefix: prefix,
            extension: "png",
            date: date
        )
        do {
            try pngData.write(to: fileURL, options: .atomic)
        } catch {
            throw WindowMediaCaptureError.fileWriteFailed(error.localizedDescription)
        }
        return fileURL
    }

    @discardableResult
    public static func captureScreenshotToClipboard(
        from renderer: VideoDisplayRenderer,
        pasteboard: NSPasteboard = .general
    ) throws -> Data {
        guard let image = renderer.captureCurrentFrameCGImage() else {
            throw WindowMediaCaptureError.noVideoFrameAvailable
        }
        return try writeImageToClipboard(image, pasteboard: pasteboard)
    }

    @discardableResult
    public static func captureScreenshotToClipboardAsync(
        from renderer: VideoDisplayRenderer,
        timeoutSeconds: Double = 2.0,
        pasteboard: NSPasteboard = .general
    ) async throws -> Data {
        guard let image = await renderer.captureCurrentFrameCGImageAsync(timeoutSeconds: timeoutSeconds) else {
            throw WindowMediaCaptureError.noVideoFrameAvailable
        }
        return try writeImageToClipboard(image, pasteboard: pasteboard)
    }

    public static func captureScreenshotToFile(
        from renderer: VideoDisplayRenderer,
        directory: URL? = nil,
        prefix: String = "OpalDesk-Screenshot",
        date: Date = Date()
    ) throws -> URL {
        guard let image = renderer.captureCurrentFrameCGImage() else {
            throw WindowMediaCaptureError.noVideoFrameAvailable
        }
        return try saveImageToFile(image, directory: directory, prefix: prefix, date: date)
    }

    public static func captureScreenshotToFileAsync(
        from renderer: VideoDisplayRenderer,
        directory: URL? = nil,
        prefix: String = "OpalDesk-Screenshot",
        timeoutSeconds: Double = 2.0,
        date: Date = Date()
    ) async throws -> URL {
        guard let image = await renderer.captureCurrentFrameCGImageAsync(timeoutSeconds: timeoutSeconds) else {
            throw WindowMediaCaptureError.noVideoFrameAvailable
        }
        return try saveImageToFile(image, directory: directory, prefix: prefix, date: date)
    }

    static func makeUniqueFileURL(
        in directory: URL,
        prefix: String,
        extension ext: String,
        date: Date = Date()
    ) -> URL {
        let baseName = makeTimestampedFileName(prefix: prefix, extension: ext, date: date)
        let candidate = directory.appendingPathComponent(baseName)
        guard FileManager.default.fileExists(atPath: candidate.path) else {
            return candidate
        }
        let stem = candidate.deletingPathExtension().lastPathComponent
        let cleanExt = ext.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        for index in 1...999 {
            let suffixed = directory.appendingPathComponent("\(stem)-\(index).\(cleanExt)")
            if !FileManager.default.fileExists(atPath: suffixed.path) {
                return suffixed
            }
        }
        return directory.appendingPathComponent("\(stem)-\(UUID().uuidString.prefix(8)).\(cleanExt)")
    }
}

public extension VideoDisplayRenderer {
    /// Captures the current rendered frame and copies it as PNG to `NSPasteboard.general`.
    @discardableResult
    func captureScreenshotToClipboard(pasteboard: NSPasteboard = .general) throws -> Data {
        try WindowMediaCapture.captureScreenshotToClipboard(from: self, pasteboard: pasteboard)
    }

    /// Captures the current rendered frame and saves a timestamped PNG into `directory` (defaults to Desktop).
    func captureScreenshotToFile(
        directory: URL? = nil,
        prefix: String = "OpalDesk-Screenshot",
        date: Date = Date()
    ) throws -> URL {
        try WindowMediaCapture.captureScreenshotToFile(from: self, directory: directory, prefix: prefix, date: date)
    }
}

// MARK: - Zero-Overhead Passthrough MP4 / MOV Screen Recorder

/// Hardware-passthrough screen recorder powered by `AVAssetWriter` + `AVAssetWriterInput(outputSettings: nil)`.
/// Writes `VideoDisplayRenderer`'s already-encoded H.264/HEVC `CMSampleBuffer`s directly to `.mp4` or `.mov`
/// with near-0% CPU overhead, automatic IDR keyframe alignment, timeline normalization, and optional audio track.
public final class WindowScreenRecorder: @unchecked Sendable {
    private static let microsecondTimescale: CMTimeScale = 1_000_000
    private static let minPTSIncrement = CMTime(value: 1_000, timescale: 1_000_000) // 1ms monotonic floor

    private struct SharedState: @unchecked Sendable {
        var metrics = ScreenRecordingMetrics()
        var tapToken: UUID?
        weak var attachedRenderer: VideoDisplayRenderer?
        var onMetricsUpdate: (@Sendable (ScreenRecordingMetrics) -> Void)?
    }

    private struct SendableSampleBuffer: @unchecked Sendable {
        let value: CMSampleBuffer
    }

    private struct WriterFinishContext: @unchecked Sendable {
        let writer: AVAssetWriter?
        let outputURL: URL?
        let writerError: Error?
    }

    // Queue-confined writer state (accessed exclusively on `writerQueue`).
    private var outputURL: URL?
    private var fileFormat: RecordingFileFormat = .mp4
    private var audioFormatHint: CMAudioFormatDescription?
    private var assetWriter: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var videoFormatDescription: CMVideoFormatDescription?
    private var sessionStartPTS: CMTime = .invalid
    private var lastVideoPTS: CMTime = .invalid
    private var lastAudioPTS: CMTime = .invalid
    private var activeWriterError: Error?

    private let writerQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.screen-recorder", qos: .utility)
    private let shared = OSAllocatedUnfairLock(initialState: SharedState())

    public init() {}

    deinit {
        detachTapFromRenderer()
    }

    public var currentMetrics: ScreenRecordingMetrics {
        shared.withLock { $0.metrics }
    }

    public var isRecording: Bool {
        let state = shared.withLock { $0.metrics.state }
        return state == .waitingForKeyframe || state == .recording
    }

    public var recordingDurationSeconds: TimeInterval {
        shared.withLock { $0.metrics.durationSeconds }
    }

    /// Starts passthrough screen recording attached directly to `renderer`, auto-generating a timestamped file URL.
    /// Immediately requests an IDR keyframe from `renderer` so recording begins cleanly with zero macroblock artifacts.
    @discardableResult
    public func start(
        renderer: VideoDisplayRenderer,
        directory: URL? = nil,
        prefix: String = "OpalDesk-Recording",
        format: RecordingFileFormat = .mp4,
        audioFormatHint: CMAudioFormatDescription? = nil,
        date: Date = Date(),
        onMetricsUpdate: (@Sendable (ScreenRecordingMetrics) -> Void)? = nil
    ) throws -> URL {
        let targetDir = directory ?? WindowMediaCapture.defaultRecordingsDirectory()
        do {
            try FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true)
        } catch {
            throw WindowMediaCaptureError.directoryCreationFailed(targetDir.path)
        }
        let fileURL = WindowMediaCapture.makeUniqueFileURL(
            in: targetDir,
            prefix: prefix,
            extension: format.fileExtension,
            date: date
        )
        try start(
            renderer: renderer,
            outputURL: fileURL,
            format: format,
            audioFormatHint: audioFormatHint,
            onMetricsUpdate: onMetricsUpdate
        )
        return fileURL
    }

    /// Starts passthrough screen recording attached directly to `renderer` writing to `outputURL`.
    public func start(
        renderer: VideoDisplayRenderer,
        outputURL: URL,
        format: RecordingFileFormat = .mp4,
        audioFormatHint: CMAudioFormatDescription? = nil,
        onMetricsUpdate: (@Sendable (ScreenRecordingMetrics) -> Void)? = nil
    ) throws {
        try start(
            outputURL: outputURL,
            format: format,
            audioFormatHint: audioFormatHint,
            onRequestIDR: nil,
            onMetricsUpdate: onMetricsUpdate
        )

        let token = renderer.addEncodedFrameTap { [weak self] sample in
            self?.appendVideoSample(sample)
        }
        shared.withLock { s in
            s.attachedRenderer = renderer
            s.tapToken = token
        }
        renderer.requestIDR(bypassThrottle: true)
    }

    /// Starts passthrough screen recording using manually pushed `VideoEncodedFrameSample` / `CMSampleBuffer` inputs.
    public func start(
        outputURL: URL,
        format: RecordingFileFormat = .mp4,
        audioFormatHint: CMAudioFormatDescription? = nil,
        onRequestIDR: (@Sendable () -> Void)? = nil,
        onMetricsUpdate: (@Sendable (ScreenRecordingMetrics) -> Void)? = nil
    ) throws {
        let parentDir = outputURL.deletingLastPathComponent()
        do {
            try FileManager.default.createDirectory(at: parentDir, withIntermediateDirectories: true)
        } catch {
            throw WindowMediaCaptureError.directoryCreationFailed(parentDir.path)
        }

        let initialMetrics: ScreenRecordingMetrics? = shared.withLock { s in
            guard s.metrics.state == .idle else {
                return nil
            }
            var m = ScreenRecordingMetrics()
            m.state = .waitingForKeyframe
            m.outputURL = outputURL
            s.metrics = m
            s.onMetricsUpdate = onMetricsUpdate
            return m
        }
        guard let initialMetrics else {
            throw WindowMediaCaptureError.alreadyRecording
        }

        writerQueue.sync {
            self.resetWriterObjectsLocked()
            self.outputURL = outputURL
            self.fileFormat = format
            self.audioFormatHint = audioFormatHint
        }

        emitMetrics(initialMetrics)
        onRequestIDR?()
    }

    /// Feeds an encoded video sample from `VideoDisplayRenderer`'s frame tap into the passthrough writer.
    public func appendVideoSample(_ sample: VideoEncodedFrameSample) {
        let currentState = shared.withLock { $0.metrics.state }
        guard currentState == .waitingForKeyframe || currentState == .recording else { return }

        writerQueue.async { [weak self] in
            self?.handleVideoSampleOnQueue(sample)
        }
    }

    /// Feeds a raw `CMSampleBuffer` directly into the video track (useful for custom pipelines or testing).
    public func appendVideoSampleBuffer(
        _ sampleBuffer: CMSampleBuffer,
        timestampUs: Int64? = nil,
        isKeyframe: Bool? = nil
    ) {
        guard let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
        let resolvedKey = isKeyframe ?? Self.sampleBufferIsSync(sampleBuffer)
        let resolvedTimestampUs: Int64
        if let timestampUs {
            resolvedTimestampUs = timestampUs
        } else {
            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            if pts.isValid && pts.isNumeric {
                resolvedTimestampUs = Int64((CMTimeGetSeconds(pts) * 1_000_000.0).rounded())
            } else {
                resolvedTimestampUs = Int64(DispatchTime.now().uptimeNanoseconds / 1_000)
            }
        }
        let sample = VideoEncodedFrameSample(
            sampleBuffer: sampleBuffer,
            formatDescription: formatDesc,
            timestampUs: resolvedTimestampUs,
            isKeyframe: resolvedKey
        )
        appendVideoSample(sample)
    }

    /// Optionally feeds an audio `CMSampleBuffer` into the recording's audio track (requires `audioFormatHint` at `start`).
    public func appendAudioSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
        let currentState = shared.withLock { $0.metrics.state }
        guard currentState == .recording else { return }

        let boxed = SendableSampleBuffer(value: sampleBuffer)
        writerQueue.async { [weak self] in
            self?.handleAudioSampleOnQueue(boxed.value)
        }
    }

    /// Safely stops recording, flushes and finalizes the container file on disk, and returns recording metadata.
    public func stop() async throws -> ScreenRecordingResult {
        detachTapFromRenderer()

        let canFinish: Bool = shared.withLock { s in
            guard s.metrics.state == .waitingForKeyframe || s.metrics.state == .recording else {
                return false
            }
            s.metrics.state = .finishing
            return true
        }
        guard canFinish else {
            throw WindowMediaCaptureError.notRecording
        }

        let finishContext: WriterFinishContext = await withCheckedContinuation { continuation in
            writerQueue.async {
                self.videoInput?.markAsFinished()
                self.audioInput?.markAsFinished()
                let ctx = WriterFinishContext(
                    writer: self.assetWriter,
                    outputURL: self.outputURL,
                    writerError: self.activeWriterError
                )
                continuation.resume(returning: ctx)
            }
        }

        defer {
            writerQueue.sync {
                self.resetWriterObjectsLocked()
            }
            let idleMetrics: ScreenRecordingMetrics = shared.withLock { s in
                s.metrics.state = .idle
                s.onMetricsUpdate = nil
                return s.metrics
            }
            emitMetrics(idleMetrics)
        }

        guard let writer = finishContext.writer, let finalURL = finishContext.outputURL else {
            if let error = finishContext.writerError {
                throw error
            }
            throw WindowMediaCaptureError.noKeyframeReceived
        }

        await writer.finishWriting()

        if writer.status != .completed {
            let reason = writer.error?.localizedDescription ?? "AVAssetWriter ended with status \(writer.status.rawValue)"
            try? FileManager.default.removeItem(at: finalURL)
            throw WindowMediaCaptureError.assetWriterFailed(reason)
        }

        let fileSize: Int64
        if let attrs = try? FileManager.default.attributesOfItem(atPath: finalURL.path),
           let sizeNumber = attrs[.size] as? NSNumber {
            fileSize = sizeNumber.int64Value
        } else {
            fileSize = 0
        }

        let snapshot = shared.withLock { $0.metrics }
        return ScreenRecordingResult(
            url: finalURL,
            durationSeconds: snapshot.durationSeconds,
            videoFrameCount: snapshot.videoFrameCount,
            audioSampleCount: snapshot.audioSampleCount,
            fileSizeBytes: fileSize
        )
    }

    /// Immediately aborts any active recording and deletes the partial output file.
    public func cancel() {
        detachTapFromRenderer()
        shared.withLock { s in
            s.metrics.state = .idle
            s.onMetricsUpdate = nil
        }
        writerQueue.async { [weak self] in
            guard let self else { return }
            let partialURL = self.outputURL
            self.assetWriter?.cancelWriting()
            self.resetWriterObjectsLocked()
            if let partialURL {
                try? FileManager.default.removeItem(at: partialURL)
            }
        }
    }

    // MARK: - Queue-Confined Writer Implementation

    private func handleVideoSampleOnQueue(_ sample: VideoEncodedFrameSample) {
        let state = shared.withLock { $0.metrics.state }
        guard state == .waitingForKeyframe || state == .recording else { return }
        guard activeWriterError == nil else { return }

        if assetWriter == nil {
            guard sample.isKeyframe else {
                let updated: ScreenRecordingMetrics = shared.withLock { s in
                    s.metrics.droppedPreKeyframeFrames += 1
                    return s.metrics
                }
                emitMetrics(updated)
                return
            }

            do {
                try startAssetWriterOnQueue(firstSample: sample)
            } catch {
                activeWriterError = error
                OpalDeskLog.video.error("WindowScreenRecorder failed to start AVAssetWriter: \(error.localizedDescription, privacy: .public)")
                return
            }
        }

        guard let writer = assetWriter,
              let vInput = videoInput,
              writer.status == .writing
        else {
            return
        }

        // Guard against mid-recording geometry or codec switches that passthrough tracks cannot mix.
        if let expectedDesc = videoFormatDescription,
           !Self.areCompatibleVideoFormats(expectedDesc, sample.formatDescription)
        {
            OpalDeskLog.video.warning("WindowScreenRecorder skipped video frame after mid-recording format change")
            return
        }

        guard vInput.isReadyForMoreMediaData else {
            let updated: ScreenRecordingMetrics = shared.withLock { s in
                s.metrics.droppedBackpressureFrames += 1
                return s.metrics
            }
            emitMetrics(updated)
            return
        }

        let rawPTS = resolveVideoPTS(sample: sample)
        let pts: CMTime
        if lastVideoPTS.isValid && CMTimeCompare(rawPTS, lastVideoPTS) <= 0 {
            pts = CMTimeAdd(lastVideoPTS, Self.minPTSIncrement)
        } else {
            pts = rawPTS
        }

        guard let timedBuffer = try? Self.makeTimedSampleBuffer(
            from: sample.sampleBuffer,
            presentationTimeStamp: pts,
            isKeyframe: sample.isKeyframe
        ) else {
            return
        }

        guard vInput.append(timedBuffer) else {
            if let err = writer.error {
                activeWriterError = WindowMediaCaptureError.assetWriterFailed(err.localizedDescription)
                OpalDeskLog.video.error("WindowScreenRecorder video append failed: \(err.localizedDescription, privacy: .public)")
            }
            return
        }

        lastVideoPTS = pts
        let duration = max(0, CMTimeGetSeconds(CMTimeSubtract(pts, sessionStartPTS)))
        let updated: ScreenRecordingMetrics = shared.withLock { s in
            s.metrics.state = .recording
            s.metrics.videoFrameCount += 1
            s.metrics.durationSeconds = duration
            return s.metrics
        }
        emitMetrics(updated)
    }

    private func handleAudioSampleOnQueue(_ sampleBuffer: CMSampleBuffer) {
        guard let writer = assetWriter,
              let aInput = audioInput,
              writer.status == .writing,
              sessionStartPTS.isValid,
              aInput.isReadyForMoreMediaData
        else {
            return
        }

        let rawPTS = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let targetPTS: CMTime
        if rawPTS.isValid && rawPTS.isNumeric && CMTimeCompare(rawPTS, sessionStartPTS) >= 0 {
            targetPTS = rawPTS
        } else if lastAudioPTS.isValid {
            let duration = CMSampleBufferGetDuration(sampleBuffer)
            let step = (duration.isValid && duration.isNumeric && CMTimeGetSeconds(duration) > 0)
                ? duration
                : Self.minPTSIncrement
            targetPTS = CMTimeAdd(lastAudioPTS, step)
        } else {
            targetPTS = lastVideoPTS.isValid ? lastVideoPTS : sessionStartPTS
        }

        let bufferToAppend: CMSampleBuffer
        if rawPTS.isValid && CMTimeCompare(rawPTS, targetPTS) == 0 {
            bufferToAppend = sampleBuffer
        } else if let retimed = try? Self.makeTimedSampleBuffer(
            from: sampleBuffer,
            presentationTimeStamp: targetPTS,
            isKeyframe: nil
        ) {
            bufferToAppend = retimed
        } else {
            return
        }

        guard aInput.append(bufferToAppend) else {
            return
        }

        lastAudioPTS = targetPTS
        let updated: ScreenRecordingMetrics = shared.withLock { s in
            s.metrics.audioSampleCount += 1
            return s.metrics
        }
        emitMetrics(updated)
    }

    private func startAssetWriterOnQueue(firstSample: VideoEncodedFrameSample) throws {
        guard let url = outputURL else {
            throw WindowMediaCaptureError.assetWriterFailed("Missing output URL")
        }
        if FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.removeItem(at: url)
        }

        let writer: AVAssetWriter
        do {
            writer = try AVAssetWriter(outputURL: url, fileType: fileFormat.avFileType)
        } catch {
            throw WindowMediaCaptureError.assetWriterFailed(error.localizedDescription)
        }
        writer.shouldOptimizeForNetworkUse = true

        // Passthrough mode: `outputSettings: nil` writes H.264/HEVC CMSampleBuffers verbatim (0% re-encode CPU).
        let vInput = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: nil,
            sourceFormatHint: firstSample.formatDescription
        )
        vInput.expectsMediaDataInRealTime = true
        guard writer.canAdd(vInput) else {
            throw WindowMediaCaptureError.assetWriterFailed("Cannot add passthrough video input to AVAssetWriter")
        }
        writer.add(vInput)

        var aInput: AVAssetWriterInput?
        if let audioHint = audioFormatHint {
            let audioSettings = Self.resolvedAudioOutputSettings(for: audioHint, fileFormat: fileFormat)
            let candidateAudio = AVAssetWriterInput(
                mediaType: .audio,
                outputSettings: audioSettings,
                sourceFormatHint: audioHint
            )
            candidateAudio.expectsMediaDataInRealTime = true
            if writer.canAdd(candidateAudio) {
                writer.add(candidateAudio)
                aInput = candidateAudio
            }
        }

        guard writer.startWriting() else {
            let reason = writer.error?.localizedDescription ?? "startWriting() returned false"
            throw WindowMediaCaptureError.assetWriterFailed(reason)
        }

        let firstPTS = resolveVideoPTS(sample: firstSample)
        writer.startSession(atSourceTime: firstPTS)

        self.assetWriter = writer
        self.videoInput = vInput
        self.audioInput = aInput
        self.videoFormatDescription = firstSample.formatDescription
        self.sessionStartPTS = firstPTS
        self.lastVideoPTS = .invalid
        self.lastAudioPTS = .invalid
    }

    private func resolveVideoPTS(sample: VideoEncodedFrameSample) -> CMTime {
        if sample.timestampUs > 0 {
            return CMTime(value: sample.timestampUs, timescale: Self.microsecondTimescale)
        }
        let existing = CMSampleBufferGetPresentationTimeStamp(sample.sampleBuffer)
        if existing.isValid && existing.isNumeric {
            return existing
        }
        let uptimeUs = Int64(DispatchTime.now().uptimeNanoseconds / 1_000)
        return CMTime(value: uptimeUs, timescale: Self.microsecondTimescale)
    }

    private func resetWriterObjectsLocked() {
        assetWriter = nil
        videoInput = nil
        audioInput = nil
        videoFormatDescription = nil
        outputURL = nil
        audioFormatHint = nil
        sessionStartPTS = .invalid
        lastVideoPTS = .invalid
        lastAudioPTS = .invalid
        activeWriterError = nil
    }

    private func detachTapFromRenderer() {
        let detached: (renderer: VideoDisplayRenderer?, token: UUID?) = shared.withLock { s in
            let pair = (s.attachedRenderer, s.tapToken)
            s.attachedRenderer = nil
            s.tapToken = nil
            return pair
        }
        if let renderer = detached.renderer, let token = detached.token {
            renderer.removeEncodedFrameTap(token)
        }
    }

    private func emitMetrics(_ metrics: ScreenRecordingMetrics) {
        let cb = shared.withLock { $0.onMetricsUpdate }
        cb?(metrics)
    }

    // MARK: - Static CMSampleBuffer Helpers

    /// Creates a zero-copy `CMSampleBuffer` referencing the same underlying `CMBlockBuffer` with valid timing
    /// suitable for `AVAssetWriterInput.append(_:)`.
    public static func makeTimedSampleBuffer(
        from source: CMSampleBuffer,
        presentationTimeStamp: CMTime,
        duration: CMTime = .invalid,
        isKeyframe: Bool? = nil
    ) throws -> CMSampleBuffer {
        var timing = CMSampleTimingInfo(
            duration: duration,
            presentationTimeStamp: presentationTimeStamp,
            decodeTimeStamp: .invalid
        )
        var copy: CMSampleBuffer?
        let status = CMSampleBufferCreateCopyWithNewTiming(
            allocator: kCFAllocatorDefault,
            sampleBuffer: source,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleBufferOut: &copy
        )
        guard status == noErr, let copy else {
            throw WindowMediaCaptureError.sampleTimingCreationFailed(status)
        }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(copy, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0
        {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            // Remove DisplayImmediately (display-layer-only hint) so AVAssetWriter handles timing cleanly.
            CFDictionaryRemoveValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque()
            )
            if let isKeyframe {
                if isKeyframe {
                    CFDictionaryRemoveValue(
                        dict,
                        Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque()
                    )
                } else {
                    CFDictionarySetValue(
                        dict,
                        Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                        Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
                    )
                }
            }
        }
        return copy
    }

    static func sampleBufferIsSync(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false),
              CFArrayGetCount(attachments) > 0
        else {
            return true
        }
        let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFDictionary.self)
        let key = Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque()
        guard let rawVal = CFDictionaryGetValue(dict, key) else {
            return true
        }
        let cfBool = unsafeBitCast(rawVal, to: CFBoolean.self)
        return !CFBooleanGetValue(cfBool)
    }

    static func areCompatibleVideoFormats(
        _ lhs: CMVideoFormatDescription,
        _ rhs: CMVideoFormatDescription
    ) -> Bool {
        if CMFormatDescriptionGetMediaSubType(lhs) != CMFormatDescriptionGetMediaSubType(rhs) {
            return false
        }
        let lDims = CMVideoFormatDescriptionGetDimensions(lhs)
        let rDims = CMVideoFormatDescriptionGetDimensions(rhs)
        return lDims.width == rDims.width && lDims.height == rDims.height
    }

    static func resolvedAudioOutputSettings(
        for audioHint: CMAudioFormatDescription,
        fileFormat: RecordingFileFormat
    ) -> [String: Any]? {
        let subType = CMFormatDescriptionGetMediaSubType(audioHint)
        // Use pure passthrough (`nil`) for compressed AAC/MP4-native streams and for `.mov` containers.
        if fileFormat == .mov || subType != kAudioFormatLinearPCM {
            return nil
        }
        // `.mp4` containers require compressed audio (AAC) rather than raw LPCM.
        let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(audioHint)?.pointee
        let sampleRate = (asbd?.mSampleRate ?? 48_000) > 0 ? (asbd?.mSampleRate ?? 48_000) : 48_000
        let channels = max(Int(asbd?.mChannelsPerFrame ?? 2), 1)
        return [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: 128_000
        ]
    }
}
