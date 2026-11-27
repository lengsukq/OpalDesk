import Foundation
import Network
import os
import Security

// MARK: - HTTP/1.1 Connection Stream Parser

enum WebDavHTTPParseError: Error {
    case eof
    case malformedRequest
    case headerTooLarge
    case bodyTooLarge
    case sendFailed(String)
}

struct WebDavParsedRequestHead: Sendable {
    let method: String
    let target: String
    let httpVersion: String
    let headers: [String: String]

    func header(_ lowerName: String) -> String? {
        headers[lowerName.lowercased()]
    }
}

/// Sequential buffered reader/writer over a single `NWConnection` used exclusively by one connection task.
final class WebDavConnectionStream: @unchecked Sendable {
    private static let crlfCrlf = Data([0x0D, 0x0A, 0x0D, 0x0A])
    private static let crlf = Data([0x0D, 0x0A])
    private static let maxLineLength = 8192
    private static let receiveChunkLength = 65_536

    private let connection: NWConnection
    private var buffer = Data()

    init(connection: NWConnection) {
        self.connection = connection
    }

    func readRequestHead(maxHeaderBytes: Int) async throws -> WebDavParsedRequestHead? {
        while true {
            if let range = buffer.range(of: Self.crlfCrlf) {
                let headData = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
                buffer.removeSubrange(buffer.startIndex..<range.upperBound)
                return try parseHead(headData)
            }
            if buffer.count > maxHeaderBytes {
                throw WebDavHTTPParseError.headerTooLarge
            }
            let chunk = try await receiveChunk()
            if chunk.isEmpty {
                if buffer.isEmpty { return nil }
                throw WebDavHTTPParseError.eof
            }
            buffer.append(chunk)
        }
    }

    func readBodyToData(headers: [String: String], maxBytes: Int) async throws -> Data {
        if let te = headers["transfer-encoding"]?.lowercased(), te.contains("chunked") {
            var out = Data()
            try await readChunkedBody(maxBytes: Int64(maxBytes)) { chunk in
                out.append(chunk)
            }
            return out
        }

        guard let clRaw = headers["content-length"], let contentLength = Int(clRaw.trimmingCharacters(in: .whitespaces)) else {
            return Data()
        }
        guard contentLength >= 0 else { throw WebDavHTTPParseError.malformedRequest }
        guard contentLength <= maxBytes else { throw WebDavHTTPParseError.bodyTooLarge }
        if contentLength == 0 { return Data() }

        var out = Data(capacity: contentLength)
        try await readExactBytes(count: Int64(contentLength)) { chunk in
            out.append(chunk)
        }
        return out
    }

    func readBodyToFile(headers: [String: String], destinationURL: URL, maxBytes: Int64) async throws {
        guard FileManager.default.createFile(atPath: destinationURL.path, contents: nil) else {
            throw RemoteFileSystemError.ioError("无法创建 WebDAV 上传临时文件")
        }
        let handle = try FileHandle(forWritingTo: destinationURL)
        defer { try? handle.close() }

        if let te = headers["transfer-encoding"]?.lowercased(), te.contains("chunked") {
            try await readChunkedBody(maxBytes: maxBytes) { chunk in
                if !chunk.isEmpty {
                    try handle.write(contentsOf: chunk)
                }
            }
            return
        }

        guard let clRaw = headers["content-length"], let contentLength = Int64(clRaw.trimmingCharacters(in: .whitespaces)) else {
            return
        }
        guard contentLength >= 0 else { throw WebDavHTTPParseError.malformedRequest }
        guard contentLength <= maxBytes else { throw WebDavHTTPParseError.bodyTooLarge }
        guard contentLength > 0 else { return }

        try await readExactBytes(count: contentLength) { chunk in
            if !chunk.isEmpty {
                try handle.write(contentsOf: chunk)
            }
        }
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: WebDavHTTPParseError.sendFailed(error.localizedDescription))
                } else {
                    continuation.resume(returning: ())
                }
            })
        }
    }

    private func parseHead(_ headData: Data) throws -> WebDavParsedRequestHead {
        guard let raw = String(data: headData, encoding: .utf8) ?? String(data: headData, encoding: .isoLatin1) else {
            throw WebDavHTTPParseError.malformedRequest
        }
        let lines = raw.components(separatedBy: "\r\n")
        guard let requestLine = lines.first, !requestLine.isEmpty else {
            throw WebDavHTTPParseError.malformedRequest
        }
        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count == 3 else {
            throw WebDavHTTPParseError.malformedRequest
        }

        let method = String(parts[0]).uppercased()
        let target = String(parts[1])
        let version = String(parts[2]).uppercased()
        guard version.hasPrefix("HTTP/1.") else {
            throw WebDavHTTPParseError.malformedRequest
        }

        var headers: [String: String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = String(line[..<colon]).trimmingCharacters(in: .whitespaces).lowercased()
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            if let existing = headers[name] {
                headers[name] = "\(existing), \(value)"
            } else {
                headers[name] = value
            }
        }

        return WebDavParsedRequestHead(method: method, target: target, httpVersion: version, headers: headers)
    }

    private func readExactBytes(count: Int64, onChunk: (Data) throws -> Void) async throws {
        var remaining = count
        while remaining > 0 {
            if !buffer.isEmpty {
                let take = Int(min(remaining, Int64(buffer.count)))
                let end = buffer.index(buffer.startIndex, offsetBy: take)
                let slice = buffer.subdata(in: buffer.startIndex..<end)
                buffer.removeSubrange(buffer.startIndex..<end)
                try onChunk(slice)
                remaining -= Int64(take)
                continue
            }
            let chunk = try await receiveChunk()
            if chunk.isEmpty {
                throw WebDavHTTPParseError.eof
            }
            buffer.append(chunk)
        }
    }

    private func readChunkedBody(maxBytes: Int64, onChunk: (Data) throws -> Void) async throws {
        var totalRead: Int64 = 0
        while true {
            let sizeLine = try await readLine()
            let hexPart = sizeLine.split(separator: ";", maxSplits: 1).first.map(String.init) ?? ""
            guard let chunkSize = Int64(hexPart.trimmingCharacters(in: .whitespaces), radix: 16), chunkSize >= 0 else {
                throw WebDavHTTPParseError.malformedRequest
            }
            if chunkSize == 0 {
                while true {
                    let trailer = try await readLine()
                    if trailer.isEmpty { break }
                }
                return
            }
            totalRead += chunkSize
            if totalRead > maxBytes {
                throw WebDavHTTPParseError.bodyTooLarge
            }
            try await readExactBytes(count: chunkSize, onChunk: onChunk)
            let trailing = try await readLine()
            if !trailing.isEmpty {
                throw WebDavHTTPParseError.malformedRequest
            }
        }
    }

    private func readLine() async throws -> String {
        while true {
            if let range = buffer.range(of: Self.crlf) {
                let lineData = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
                buffer.removeSubrange(buffer.startIndex..<range.upperBound)
                guard let line = String(data: lineData, encoding: .utf8) ?? String(data: lineData, encoding: .isoLatin1) else {
                    throw WebDavHTTPParseError.malformedRequest
                }
                return line
            }
            if buffer.count > Self.maxLineLength {
                throw WebDavHTTPParseError.malformedRequest
            }
            let chunk = try await receiveChunk()
            if chunk.isEmpty {
                throw WebDavHTTPParseError.eof
            }
            buffer.append(chunk)
        }
    }

    private func receiveChunk() async throws -> Data {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: Self.receiveChunkLength) { data, _, _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(returning: Data())
                }
            }
        }
    }
}

// MARK: - Pure WebDAV / HTTP Wire Utilities

enum WebDavByteRangeResult {
    case none
    case unsatisfiable
    case range(start: Int64, end: Int64)
}

enum WebDavWireCodec {
    private static let rfc1123FormatterLock: OSAllocatedUnfairLock<DateFormatter> = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        return OSAllocatedUnfairLock(initialState: formatter)
    }()

    static let pathSegmentAllowed: CharacterSet = {
        var cs = CharacterSet.alphanumerics
        cs.insert(charactersIn: "-._~")
        return cs
    }()

    static func mapErrorToHTTPStatus(_ error: Error) -> (status: Int, reason: String) {
        if let fsError = error as? RemoteFileSystemError {
            switch fsError {
            case .notFound: return (404, "Not Found")
            case .alreadyExists: return (405, "Method Not Allowed")
            case .permissionDenied: return (403, "Forbidden")
            case .invalidPath: return (400, "Bad Request")
            case .ioError: return (502, "Bad Gateway")
            }
        }
        if let browserErr = error as? RemoteFileBrowserError {
            switch browserErr {
            case .remoteError(_, let msg):
                let lower = msg.lowercased()
                if lower.contains("not found") || lower.contains("no such file") || lower.contains("does not exist")
                    || lower.contains("enoent") || lower.contains("不存在")
                {
                    return (404, "Not Found")
                }
                if lower.contains("exists") || lower.contains("已存在") {
                    return (405, "Method Not Allowed")
                }
                if lower.contains("permission") || lower.contains("denied") || lower.contains("权限") {
                    return (403, "Forbidden")
                }
                return (502, "Bad Gateway")
            case .notConnected:
                return (503, "Service Unavailable")
            case .requestTimedOut:
                return (504, "Gateway Timeout")
            default:
                return (502, "Bad Gateway")
            }
        }
        return (500, "Internal Server Error")
    }

    static func parseByteRange(_ header: String, totalSize: Int64) -> WebDavByteRangeResult {
        let trimmed = header.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix("bytes=") else { return .none }
        let specList = String(trimmed.dropFirst(6))
        // Handle single byte range; ignore multi-range by falling back to full content per RFC 9110.
        guard !specList.contains(",") else { return .none }
        let parts = specList.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return .none }
        guard totalSize > 0 else { return .unsatisfiable }

        let startStr = parts[0].trimmingCharacters(in: .whitespaces)
        let endStr = parts[1].trimmingCharacters(in: .whitespaces)

        if startStr.isEmpty {
            guard let suffixLen = Int64(endStr), suffixLen > 0 else { return .unsatisfiable }
            let start = max(0, totalSize - suffixLen)
            return .range(start: start, end: totalSize - 1)
        }

        guard let start = Int64(startStr), start >= 0 else { return .unsatisfiable }
        if start >= totalSize { return .unsatisfiable }

        if endStr.isEmpty {
            return .range(start: start, end: totalSize - 1)
        }

        guard let requestedEnd = Int64(endStr), requestedEnd >= start else { return .unsatisfiable }
        return .range(start: start, end: min(requestedEnd, totalSize - 1))
    }

    static func makeETag(for item: RemoteFileItem) -> String {
        "\"\(item.modifiedAtMs)-\(item.sizeBytes)\""
    }

    static func xmlEscape(_ raw: String) -> String {
        var out = ""
        out.reserveCapacity(raw.count)
        for ch in raw {
            switch ch {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            default: out.append(ch)
            }
        }
        return out
    }

    static func formatRFC1123Date(ms: Int64) -> String {
        let date = ms > 0 ? Date(timeIntervalSince1970: Double(ms) / 1000.0) : Date(timeIntervalSince1970: 0)
        return formatRFC1123Date(date)
    }

    static func formatRFC1123Date(_ date: Date) -> String {
        rfc1123FormatterLock.withLock { $0.string(from: date) }
    }

    static func guessMimeType(forName name: String) -> String {
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "txt", "log", "md": return "text/plain; charset=utf-8"
        case "json": return "application/json"
        case "xml": return "application/xml"
        case "html", "htm": return "text/html"
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "heic": return "image/heic"
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        case "mp3": return "audio/mpeg"
        case "m4a": return "audio/mp4"
        case "pdf": return "application/pdf"
        case "zip": return "application/zip"
        case "apk": return "application/vnd.android.package-archive"
        default: return "application/octet-stream"
        }
    }

    static func generateRandomHexToken(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        if status != errSecSuccess {
            var rng = SystemRandomNumberGenerator()
            for i in 0..<byteCount {
                bytes[i] = UInt8.random(in: 0...255, using: &rng)
            }
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8)
        let b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }
}
