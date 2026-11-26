import Foundation
import os
import zlib

/// Diagnostic log severity aligned with Android `OpalLogLevel`.
public enum OpalLogLevel: String, Codable, Sendable, CaseIterable {
    case info = "I"
    case warn = "W"
    case error = "E"

    public var code: String { rawValue }

    public var label: String {
        switch self {
        case .info: return "信息"
        case .warn: return "警告"
        case .error: return "错误"
        }
    }
}

/// Immutable structured client diagnostic log entry.
public struct OpalLogEntry: Identifiable, Sendable, Equatable, Hashable {
    public let seq: Int64
    public let timestampMs: Int64
    public let timeText: String
    public let level: OpalLogLevel
    public let tag: String
    public let message: String

    public var id: Int64 { seq }

    public init(
        seq: Int64,
        timestampMs: Int64,
        timeText: String,
        level: OpalLogLevel,
        tag: String,
        message: String
    ) {
        self.seq = seq
        self.timestampMs = timestampMs
        self.timeText = timeText
        self.level = level
        self.tag = tag
        self.message = message
    }

    public func formatLine() -> String {
        "\(timeText) \(level.code)/\(tag): \(message)"
    }
}

/// Runtime context snapshot included in diagnostic report headers.
public struct MacDiagnosticContext: Sendable, Equatable {
    public var connectionSummary: String
    public var transportSummary: String
    public var videoSummary: String
    public var popoutSummary: String
    public var discoverySummary: String

    public init(
        connectionSummary: String = "未连接",
        transportSummary: String = "-",
        videoSummary: String = "-",
        popoutSummary: String = "0/3",
        discoverySummary: String = "-"
    ) {
        self.connectionSummary = connectionSummary
        self.transportSummary = transportSummary
        self.videoSummary = videoSummary
        self.popoutSummary = popoutSummary
        self.discoverySummary = discoverySummary
    }
}

/// Bounded thread-safe ring buffer for structured diagnostic logs.
public final class OpalLogRingStore: Sendable {
    public static let defaultMaxEntries: Int = 600
    private static let maxMessageChars: Int = 1200

    private struct State: Sendable {
        var ring: [OpalLogEntry] = []
        var nextSeq: Int64 = 1
    }

    private let maxEntries: Int
    private let state = OSAllocatedUnfairLock(initialState: State())

    public init(maxEntries: Int = OpalLogRingStore.defaultMaxEntries) {
        self.maxEntries = max(16, maxEntries)
    }

    @discardableResult
    public func append(
        level: OpalLogLevel,
        tag: String,
        message: String,
        timestampMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) -> OpalLogEntry {
        let trimmedTag = tag.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanTag = String((trimmedTag.isEmpty ? "App" : trimmedTag).prefix(24))
        let cleanMsg = Self.sanitizeMessage(message)
        let timeText = Self.formatTimeText(timestampMs: timestampMs)

        return state.withLock { s in
            let entry = OpalLogEntry(
                seq: s.nextSeq,
                timestampMs: timestampMs,
                timeText: timeText,
                level: level,
                tag: cleanTag,
                message: cleanMsg
            )
            s.nextSeq += 1
            if s.ring.count >= maxEntries {
                s.ring.removeFirst(s.ring.count - maxEntries + 1)
            }
            s.ring.append(entry)
            return entry
        }
    }

    @discardableResult
    public func appendRawPersistedLine(_ rawLine: String) -> OpalLogEntry? {
        let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        // Expected format: "HH:mm:ss.SSS I/Tag: message"
        let chars = Array(trimmed)
        if let firstSpace = chars.firstIndex(of: " "),
           firstSpace >= 5, firstSpace <= 15,
           firstSpace + 2 < chars.count,
           chars[firstSpace + 2] == "/",
           let colonRange = trimmed.range(of: ": ", range: trimmed.index(trimmed.startIndex, offsetBy: firstSpace + 3)..<trimmed.endIndex)
        {
            let timeText = String(chars[0..<firstSpace])
            let level: OpalLogLevel = switch chars[firstSpace + 1] {
            case "E": .error
            case "W": .warn
            default: .info
            }
            let tagStart = trimmed.index(trimmed.startIndex, offsetBy: firstSpace + 3)
            let tag = String(trimmed[tagStart..<colonRange.lowerBound].prefix(24))
            let msg = Self.sanitizeMessage(String(trimmed[colonRange.upperBound...]))
            return state.withLock { s in
                let entry = OpalLogEntry(
                    seq: s.nextSeq,
                    timestampMs: 0,
                    timeText: timeText,
                    level: level,
                    tag: tag,
                    message: msg
                )
                s.nextSeq += 1
                if s.ring.count >= maxEntries {
                    s.ring.removeFirst(s.ring.count - maxEntries + 1)
                }
                s.ring.append(entry)
                return entry
            }
        }
        return append(level: .info, tag: "Saved", message: trimmed, timestampMs: 0)
    }

    public func snapshot() -> [OpalLogEntry] {
        state.withLock { $0.ring }
    }

    public func clear() {
        state.withLock { $0.ring.removeAll(keepingCapacity: true) }
    }

    public var count: Int {
        state.withLock { $0.ring.count }
    }

    private static func sanitizeMessage(_ raw: String) -> String {
        let normalized = raw
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.count > maxMessageChars {
            return String(normalized.prefix(maxMessageChars)) + "..."
        }
        return normalized
    }

    private static func formatTimeText(timestampMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestampMs) / 1000.0)
        let cal = Calendar.current
        let comps = cal.dateComponents([.hour, .minute, .second, .nanosecond], from: date)
        let h = comps.hour ?? 0
        let m = comps.minute ?? 0
        let s = comps.second ?? 0
        let ms = (comps.nanosecond ?? 0) / 1_000_000
        return String(format: "%02d:%02d:%02d.%03d", h, m, s, ms)
    }
}

/// Pure filtering and diagnostic report formatting aligned with Android `OpalLogFilter`.
public enum OpalLogFilter {
    public static func filterEntries(
        _ entries: [OpalLogEntry],
        warningsAndErrorsOnly: Bool,
        query: String
    ) -> [OpalLogEntry] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return entries.filter { entry in
            if warningsAndErrorsOnly && entry.level == .info { return false }
            if q.isEmpty { return true }
            return entry.tag.lowercased().contains(q)
                || entry.message.lowercased().contains(q)
                || entry.timeText.contains(q)
        }
    }

    public static func buildDiagnosticReport(
        appVersion: String,
        deviceSummary: String,
        context: MacDiagnosticContext,
        entries: [OpalLogEntry],
        generatedAtText: String
    ) -> String {
        var lines: [String] = []
        lines.reserveCapacity(entries.count + 16)
        lines.append("=== OpalDesk macOS 诊断日志报告 ===")
        lines.append("生成时间: \(generatedAtText)")
        lines.append("应用版本: v\(appVersion)")
        lines.append("设备信息: \(deviceSummary)")
        lines.append("连接状态: \(context.connectionSummary) | 传输: \(context.transportSummary)")
        lines.append("画面指标: \(context.videoSummary) | 悬浮小窗: \(context.popoutSummary)")
        lines.append("设备发现: \(context.discoverySummary)")
        lines.append("")
        lines.append("=== macOS 客户端日志 (\(entries.count) 条) ===")
        if entries.isEmpty {
            lines.append("(无记录)")
        } else {
            for entry in entries {
                lines.append(entry.formatLine())
            }
        }
        return lines.joined(separator: "\n") + "\n"
    }
}

/// Pure diagnostic log sanitizer and GZIP compressor aligned with Android `DiagnosticSanitizer`.
public enum DiagnosticSanitizer {
    public static let defaultMaxLogEntries: Int = 250
    public static let defaultMaxGzipBytes: Int = 512 * 1024

    private struct Rule {
        let regex: NSRegularExpression
        let template: String
    }

    private static let rules: [Rule] = {
        let defs: [(String, String)] = [
            (#"(/(?:Users|home)/)([^/\s"'`]+)"#, "$1<redacted>"),
            (#"([A-Za-z]:\\Users\\)([^\\\s"'`]+)"#, "$1<redacted>"),
            (#"(/storage/emulated/)\d+"#, "$1<user>"),
            (#"(/data/user/)\d+"#, "$1<uid>"),
            (#"(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]{6,}"#, "$1<redacted>"),
            (#"\bsntry[a-zA-Z0-9_-]{8,}\b"#, "<redacted_sentry_token>"),
            (#"\b(?:gh[pousr]_[A-Za-z0-9_]{10,}|github_pat_[A-Za-z0-9_]{10,})\b"#, "<redacted_gh_token>"),
            (#"(?i)\b(adb\s+pair\s+\S+\s+)(\d{6})\b"#, "$1<redacted_pin>"),
            (
                #"(?i)\b(token|auth_?token|access_?token|secret|password|passwd|api_?key|authorization|pairing_?code)\b(\s*[:=]\s*)([^\s,;"'&}\]]+)"#,
                "$1$2<redacted>"
            ),
            (#"\b(192\.168)\.\d{1,3}\.\d{1,3}\b"#, "$1.*.*"),
            (#"\b(10)\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"#, "$1.*.*.*"),
            (#"\b(172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b"#, "$1.*.*"),
        ]
        return defs.compactMap { pattern, template in
            guard let regex = try? NSRegularExpression(pattern: pattern, options: []) else { return nil }
            return Rule(regex: regex, template: template)
        }
    }()

    public static func sanitizeText(_ raw: String) -> String {
        guard !raw.isEmpty else { return raw }
        var out = raw
        for rule in rules {
            let range = NSRange(out.startIndex..<out.endIndex, in: out)
            out = rule.regex.stringByReplacingMatches(in: out, options: [], range: range, withTemplate: rule.template)
        }
        return out
    }

    public static func buildSanitizedReportText(
        appVersion: String,
        deviceSummary: String,
        reporterName: String,
        problemDescription: String,
        context: MacDiagnosticContext,
        entries: [OpalLogEntry],
        generatedAtText: String,
        maxEntries: Int = defaultMaxLogEntries
    ) -> String {
        let recent = entries.count > maxEntries ? Array(entries.suffix(maxEntries)) : entries
        let baseReport = OpalLogFilter.buildDiagnosticReport(
            appVersion: appVersion,
            deviceSummary: deviceSummary,
            context: context,
            entries: recent,
            generatedAtText: generatedAtText
        )
        let cleanUser = reporterName.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDesc = problemDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        let header = """
        === 用户反馈信息 ===
        用户名: \(cleanUser.isEmpty ? "(未填写)" : cleanUser)
        问题描述: \(cleanDesc.isEmpty ? "(未填写)" : cleanDesc)

        """
        return sanitizeText(header + baseReport)
    }

    public static func gzipCompress(_ text: String, maxCompressedBytes: Int = defaultMaxGzipBytes) -> Data {
        let limit = max(1024, maxCompressedBytes)
        var candidate = text
        var compressed = gzipRaw(Data(candidate.utf8))
        var attempts = 0
        while compressed.count > limit && candidate.count > 512 && attempts < 6 {
            let keepChars = max(512, Int(Double(candidate.count) * 0.65))
            candidate = "[... truncated to fit upload size limit ...]\n" + String(candidate.suffix(keepChars))
            compressed = gzipRaw(Data(candidate.utf8))
            attempts += 1
        }
        return compressed
    }

    private static func gzipRaw(_ input: Data) -> Data {
        var stream = z_stream()
        let initStatus = deflateInit2_(
            &stream,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            MAX_WBITS + 16,
            MAX_MEM_LEVEL,
            Z_DEFAULT_STRATEGY,
            ZLIB_VERSION,
            Int32(MemoryLayout<z_stream>.size)
        )
        guard initStatus == Z_OK else { return Data() }
        defer { deflateEnd(&stream) }

        let chunkSize = 16_384
        var output = Data(capacity: max(256, input.count / 3))
        var status: Int32 = Z_OK

        input.withUnsafeBytes { rawBuf in
            let basePtr = rawBuf.bindMemory(to: Bytef.self).baseAddress
            stream.next_in = UnsafeMutablePointer(mutating: basePtr)
            stream.avail_in = uInt(input.count)

            var outBuffer = [UInt8](repeating: 0, count: chunkSize)
            repeat {
                outBuffer.withUnsafeMutableBytes { outRaw in
                    stream.next_out = outRaw.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(chunkSize)
                    status = deflate(&stream, Z_FINISH)
                }
                let produced = chunkSize - Int(stream.avail_out)
                if produced > 0 {
                    output.append(contentsOf: outBuffer[0..<produced])
                }
            } while status == Z_OK
        }

        return status == Z_STREAM_END ? output : Data()
    }
}

/// Process-wide macOS diagnostic log store combining an in-memory ring buffer and bounded rolling file persistence.
public final class OpalLogStore: Sendable {
    public static let shared = OpalLogStore()

    private static let maxFileBytes: UInt64 = 192 * 1024
    private static let keepFileTailLines: Int = 350

    private let ring: OpalLogRingStore
    private let fileURL: URL
    private let ioQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.logdisk", qos: .utility)
    private let observerLock = OSAllocatedUnfairLock<(@Sendable ([OpalLogEntry]) -> Void)?>(initialState: nil)

    public static func defaultLogFileURL() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
        return appSupport
            .appendingPathComponent("OpalDesk", isDirectory: true)
            .appendingPathComponent("logs", isDirectory: true)
            .appendingPathComponent("opaldesk_mac.log")
    }

    public init(fileURL: URL = OpalLogStore.defaultLogFileURL(), maxEntries: Int = OpalLogRingStore.defaultMaxEntries) {
        self.fileURL = fileURL
        self.ring = OpalLogRingStore(maxEntries: maxEntries)
        loadTailFromDiskSync()
    }

    public func setEntriesObserver(_ observer: (@Sendable ([OpalLogEntry]) -> Void)?) {
        observerLock.withLock { $0 = observer }
    }

    @discardableResult
    public func append(level: OpalLogLevel, tag: String, message: String) -> OpalLogEntry {
        let entry = ring.append(level: level, tag: tag, message: message)
        let snap = ring.snapshot()
        if let obs = observerLock.withLock({ $0 }) {
            obs(snap)
        }
        let line = entry.formatLine() + "\n"
        ioQueue.async { [fileURL] in
            Self.appendLineToDisk(fileURL: fileURL, line: line)
        }
        return entry
    }

    public func snapshot() -> [OpalLogEntry] {
        ring.snapshot()
    }

    public func clear() {
        ring.clear()
        if let obs = observerLock.withLock({ $0 }) {
            obs([])
        }
        ioQueue.async { [fileURL] in
            try? Data().write(to: fileURL, options: .atomic)
        }
    }

    public func flushForTests() {
        ioQueue.sync {}
    }

    private func loadTailFromDiskSync() {
        guard FileManager.default.fileExists(atPath: fileURL.path),
              let content = try? String(contentsOf: fileURL, encoding: .utf8),
              !content.isEmpty
        else {
            return
        }
        let lines = content.split(whereSeparator: \.isNewline).map(String.init)
        let tail = lines.count > Self.keepFileTailLines ? Array(lines.suffix(Self.keepFileTailLines)) : lines
        for line in tail {
            ring.appendRawPersistedLine(line)
        }
    }

    private static func appendLineToDisk(fileURL: URL, line: String) {
        let fm = FileManager.default
        let dir = fileURL.deletingLastPathComponent()
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        if let attrs = try? fm.attributesOfItem(atPath: fileURL.path),
           let size = attrs[.size] as? UInt64,
           size > maxFileBytes,
           let content = try? String(contentsOf: fileURL, encoding: .utf8)
        {
            let lines = content.split(whereSeparator: \.isNewline).map(String.init)
            let kept = lines.count > keepFileTailLines ? Array(lines.suffix(keepFileTailLines)) : lines
            let trimmed = kept.joined(separator: "\n") + "\n"
            try? trimmed.write(to: fileURL, atomically: true, encoding: .utf8)
        }

        guard let data = line.data(using: .utf8) else { return }
        if !fm.fileExists(atPath: fileURL.path) {
            try? data.write(to: fileURL, options: .atomic)
            return
        }
        if let handle = try? FileHandle(forWritingTo: fileURL) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        }
    }
}
