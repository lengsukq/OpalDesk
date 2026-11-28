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
