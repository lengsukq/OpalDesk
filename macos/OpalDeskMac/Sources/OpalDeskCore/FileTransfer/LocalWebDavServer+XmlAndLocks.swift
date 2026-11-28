import Foundation

// MARK: - WebDAV Locks, PROPPATCH, XML & HTTP Response Builders

extension LocalWebDavServer {
    private static let lockTimeoutSeconds: TimeInterval = 3600

    func handleLock(
        head: WebDavParsedRequestHead,
        relativeSegments: [String],
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        let href = davHref(relativeSegments: relativeSegments, isDirectory: false)
        let depth = head.header("depth")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "0"
        let expiresAt = Date().addingTimeInterval(Self.lockTimeoutSeconds)

        // Check if this is a lock refresh via `If: (<opaquelocktoken:...>)`.
        let refreshedToken: String? = {
            guard let ifHeader = head.header("if"),
                  let start = ifHeader.range(of: "opaquelocktoken:")
            else { return nil }
            let tail = ifHeader[start.lowerBound...]
            let end = tail.firstIndex(where: { $0 == ">" || $0 == ")" || $0 == " " }) ?? tail.endIndex
            return String(tail[..<end])
        }()

        let lock: ActiveLock = state.withLock { s in
            let now = Date()
            s.locksByToken = s.locksByToken.filter { $0.value.expiresAt > now }

            if let refreshedToken, var existing = s.locksByToken[refreshedToken] {
                existing.expiresAt = expiresAt
                s.locksByToken[refreshedToken] = existing
                return existing
            }
            let newToken = "opaquelocktoken:\(UUID().uuidString.lowercased())"
            let created = ActiveLock(
                token: newToken,
                path: href,
                depth: depth.isEmpty ? "0" : depth,
                owner: "OpalDesk",
                expiresAt: expiresAt
            )
            s.locksByToken[newToken] = created
            return created
        }

        let xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:prop xmlns:D="DAV:">
          <D:lockdiscovery>
            <D:activelock>
              <D:locktype><D:write/></D:locktype>
              <D:lockscope><D:exclusive/></D:lockscope>
              <D:depth>\(WebDavWireCodec.xmlEscape(lock.depth))</D:depth>
              <D:owner><D:href>\(WebDavWireCodec.xmlEscape(lock.owner))</D:href></D:owner>
              <D:timeout>Second-\(Int(Self.lockTimeoutSeconds))</D:timeout>
              <D:locktoken><D:href>\(WebDavWireCodec.xmlEscape(lock.token))</D:href></D:locktoken>
              <D:lockroot><D:href>\(WebDavWireCodec.xmlEscape(lock.path))</D:href></D:lockroot>
            </D:activelock>
          </D:lockdiscovery>
        </D:prop>
        """

        try await sendResponse(
            stream,
            status: 200,
            reason: "OK",
            headers: [
                ("Content-Type", "application/xml; charset=utf-8"),
                ("Lock-Token", "<\(lock.token)>")
            ],
            body: Data(xml.utf8),
            close: shouldClose
        )
        return !shouldClose
    }

    func handleUnlock(
        head: WebDavParsedRequestHead,
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        if let raw = head.header("lock-token") {
            let trimmed = raw
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "<>"))
            if !trimmed.isEmpty {
                state.withLock { _ = $0.locksByToken.removeValue(forKey: trimmed) }
            }
        }
        try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
        return !shouldClose
    }

    func handleProppatch(
        relativeSegments: [String],
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        let href = davHref(relativeSegments: relativeSegments, isDirectory: false)
        let xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>\(WebDavWireCodec.xmlEscape(href))</D:href>
            <D:propstat>
              <D:prop/>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
        """
        try await sendResponse(
            stream,
            status: 207,
            reason: "Multi-Status",
            headers: [("Content-Type", "application/xml; charset=utf-8")],
            body: Data(xml.utf8),
            close: shouldClose
        )
        return !shouldClose
    }

    func clearLocks(matchingPrefix prefix: String) {
        state.withLock { s in
            s.locksByToken = s.locksByToken.filter { !$0.value.path.hasPrefix(prefix) }
        }
    }

    func sendMappedError(
        _ error: Error,
        stream: WebDavConnectionStream,
        shouldClose: Bool
    ) async throws -> Bool {
        let mapped = WebDavWireCodec.mapErrorToHTTPStatus(error)
        try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
        return !shouldClose
    }

    // MARK: - XML & HTTP Response Builders

    func buildMultistatusXML(_ items: [(item: RemoteFileItem, segments: [String])]) -> String {
        var xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n"
        for entry in items {
            let item = entry.item
            let href = davHref(relativeSegments: entry.segments, isDirectory: item.isDirectory)
            let displayName = item.name.isEmpty ? (entry.segments.last ?? "") : item.name
            let lastMod = WebDavWireCodec.formatRFC1123Date(ms: item.modifiedAtMs)
            let etag = WebDavWireCodec.makeETag(for: item)

            xml += "  <D:response>\n"
            xml += "    <D:href>\(WebDavWireCodec.xmlEscape(href))</D:href>\n"
            xml += "    <D:propstat>\n"
            xml += "      <D:prop>\n"
            xml += "        <D:displayname>\(WebDavWireCodec.xmlEscape(displayName))</D:displayname>\n"
            if item.isDirectory {
                xml += "        <D:resourcetype><D:collection/></D:resourcetype>\n"
            } else {
                xml += "        <D:resourcetype/>\n"
                xml += "        <D:getcontentlength>\(max(0, item.sizeBytes))</D:getcontentlength>\n"
                let mime = item.mimeType.isEmpty ? WebDavWireCodec.guessMimeType(forName: item.name) : item.mimeType
                xml += "        <D:getcontenttype>\(WebDavWireCodec.xmlEscape(mime))</D:getcontenttype>\n"
            }
            xml += "        <D:getlastmodified>\(WebDavWireCodec.xmlEscape(lastMod))</D:getlastmodified>\n"
            xml += "        <D:getetag>\(WebDavWireCodec.xmlEscape(etag))</D:getetag>\n"
            xml += "      </D:prop>\n"
            xml += "      <D:status>HTTP/1.1 200 OK</D:status>\n"
            xml += "    </D:propstat>\n"
            xml += "  </D:response>\n"
        }
        xml += "</D:multistatus>\n"
        return xml
    }

    func davHref(relativeSegments: [String], isDirectory: Bool) -> String {
        var path = "/\(token)"
        for seg in relativeSegments {
            let encoded = seg.addingPercentEncoding(withAllowedCharacters: WebDavWireCodec.pathSegmentAllowed) ?? seg
            path += "/\(encoded)"
        }
        if isDirectory || relativeSegments.isEmpty {
            path += "/"
        }
        return path
    }

    func sendSimpleResponse(
        _ stream: WebDavConnectionStream,
        status: Int,
        reason: String,
        close: Bool
    ) async throws {
        try await sendResponse(stream, status: status, reason: reason, headers: [], body: Data(), close: close)
    }

    func sendResponse(
        _ stream: WebDavConnectionStream,
        status: Int,
        reason: String,
        headers: [(String, String)],
        body: Data,
        explicitContentLength: Int? = nil,
        close: Bool
    ) async throws {
        var headerBlock = "HTTP/1.1 \(status) \(reason)\r\n"
        headerBlock += "Date: \(WebDavWireCodec.formatRFC1123Date(Date()))\r\n"
        headerBlock += "Server: OpalDesk-WebDAV/1.0\r\n"
        var hasContentLength = false
        for (name, value) in headers {
            if name.lowercased() == "content-length" { hasContentLength = true }
            headerBlock += "\(name): \(value)\r\n"
        }
        if !hasContentLength {
            headerBlock += "Content-Length: \(explicitContentLength ?? body.count)\r\n"
        }
        headerBlock += "Connection: \(close ? "close" : "keep-alive")\r\n\r\n"

        var packet = Data(headerBlock.utf8)
        if !body.isEmpty {
            packet.append(body)
        }
        try await stream.send(packet)
    }

    func sendFileResponse(
        _ stream: WebDavConnectionStream,
        status: Int,
        reason: String,
        headers: [(String, String)],
        fileURL: URL,
        startOffset: Int64,
        byteCount: Int64,
        close: Bool
    ) async throws {
        var headerBlock = "HTTP/1.1 \(status) \(reason)\r\n"
        headerBlock += "Date: \(WebDavWireCodec.formatRFC1123Date(Date()))\r\n"
        headerBlock += "Server: OpalDesk-WebDAV/1.0\r\n"
        for (name, value) in headers {
            headerBlock += "\(name): \(value)\r\n"
        }
        headerBlock += "Content-Length: \(byteCount)\r\n"
        headerBlock += "Connection: \(close ? "close" : "keep-alive")\r\n\r\n"

        try await stream.send(Data(headerBlock.utf8))
        guard byteCount > 0 else { return }

        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        if startOffset > 0 {
            try handle.seek(toOffset: UInt64(startOffset))
        }

        var remaining = byteCount
        while remaining > 0 {
            let chunkLen = Int(min(remaining, Int64(Self.streamChunkSize)))
            guard let chunk = try handle.read(upToCount: chunkLen), !chunk.isEmpty else {
                break
            }
            try await stream.send(chunk)
            remaining -= Int64(chunk.count)
        }
    }
}
