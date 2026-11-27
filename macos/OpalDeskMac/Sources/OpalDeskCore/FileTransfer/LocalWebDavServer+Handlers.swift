import Foundation

// MARK: - WebDAV Method Dispatch, Metadata Interception & Response Writers

extension LocalWebDavServer {
    private static let allowedMethodsHeader = "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, PROPFIND, LOCK, UNLOCK"
    private static let lockTimeoutSeconds: TimeInterval = 3600

    func dispatchRequest(
        head: WebDavParsedRequestHead,
        relativePath: String,
        remotePath: String,
        relativeSegments: [String],
        smallBody: Data,
        putTempFileURL: URL?,
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        let method = head.method

        // Handle OPTIONS before metadata filter so Finder/clients can always discover capabilities.
        if method == "OPTIONS" {
            try await sendResponse(
                stream,
                status: 200,
                reason: "OK",
                headers: [
                    ("DAV", "1, 2"),
                    ("Allow", Self.allowedMethodsHeader),
                    ("MS-Author-Via", "DAV"),
                    ("Accept-Ranges", "bytes")
                ],
                body: Data(),
                close: shouldClose
            )
            return !shouldClose
        }

        // Intercept Apple metadata paths in memory before touching Android /sdcard.
        if Self.isIgnoredMacMetadataPath(relativePath) {
            return try await handleIgnoredMetadataRequest(
                head: head,
                relativePath: relativePath,
                relativeSegments: relativeSegments,
                putTempFileURL: putTempFileURL,
                shouldClose: shouldClose,
                stream: stream
            )
        }

        switch method {
        case "PROPFIND":
            return try await handlePropfind(
                head: head,
                remotePath: remotePath,
                relativeSegments: relativeSegments,
                shouldClose: shouldClose,
                stream: stream
            )
        case "GET", "HEAD":
            return try await handleGetOrHead(
                head: head,
                remotePath: remotePath,
                isHead: method == "HEAD",
                shouldClose: shouldClose,
                stream: stream
            )
        case "PUT":
            return try await handlePut(
                remotePath: remotePath,
                putTempFileURL: putTempFileURL,
                shouldClose: shouldClose,
                stream: stream
            )
        case "MKCOL":
            if !smallBody.isEmpty {
                try await sendSimpleResponse(stream, status: 415, reason: "Unsupported Media Type", close: shouldClose)
                return !shouldClose
            }
            return try await handleMkcol(remotePath: remotePath, shouldClose: shouldClose, stream: stream)
        case "DELETE":
            return try await handleDelete(remotePath: remotePath, shouldClose: shouldClose, stream: stream)
        case "MOVE":
            return try await handleMove(
                head: head,
                sourceRemotePath: remotePath,
                shouldClose: shouldClose,
                stream: stream
            )
        case "LOCK":
            return try await handleLock(
                head: head,
                relativeSegments: relativeSegments,
                shouldClose: shouldClose,
                stream: stream
            )
        case "UNLOCK":
            return try await handleUnlock(head: head, shouldClose: shouldClose, stream: stream)
        case "PROPPATCH":
            return try await handleProppatch(
                relativeSegments: relativeSegments,
                shouldClose: shouldClose,
                stream: stream
            )
        default:
            try await sendResponse(
                stream,
                status: 405,
                reason: "Method Not Allowed",
                headers: [("Allow", Self.allowedMethodsHeader)],
                body: Data(),
                close: shouldClose
            )
            return !shouldClose
        }
    }

    // MARK: - In-Memory Apple Metadata Handlers

    private func handleIgnoredMetadataRequest(
        head: WebDavParsedRequestHead,
        relativePath: String,
        relativeSegments: [String],
        putTempFileURL: URL?,
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        switch head.method {
        case "PROPFIND":
            let stub = state.withLock { $0.metadataStubs[relativePath] }
            guard let stub else {
                try await sendSimpleResponse(stream, status: 404, reason: "Not Found", close: shouldClose)
                return !shouldClose
            }
            let item = RemoteFileItem(
                name: relativeSegments.last ?? "",
                path: relativePath,
                isDirectory: stub.isDirectory,
                sizeBytes: Int64(stub.data.count),
                modifiedAtMs: Int64(stub.modifiedAt.timeIntervalSince1970 * 1000),
                mimeType: stub.isDirectory ? "" : "application/octet-stream"
            )
            let xml = buildMultistatusXML([(item: item, segments: relativeSegments)])
            try await sendResponse(
                stream,
                status: 207,
                reason: "Multi-Status",
                headers: [("Content-Type", "application/xml; charset=utf-8")],
                body: Data(xml.utf8),
                close: shouldClose
            )
            return !shouldClose

        case "GET", "HEAD":
            let stub = state.withLock { $0.metadataStubs[relativePath] }
            guard let stub, !stub.isDirectory else {
                try await sendSimpleResponse(stream, status: 404, reason: "Not Found", close: shouldClose)
                return !shouldClose
            }
            let etag = "\"meta-\(Int64(stub.modifiedAt.timeIntervalSince1970 * 1000))-\(stub.data.count)\""
            let lastMod = WebDavWireCodec.formatRFC1123Date(stub.modifiedAt)
            try await sendResponse(
                stream,
                status: 200,
                reason: "OK",
                headers: [
                    ("Content-Type", "application/octet-stream"),
                    ("Accept-Ranges", "bytes"),
                    ("ETag", etag),
                    ("Last-Modified", lastMod),
                    ("Content-Length", "\(stub.data.count)")
                ],
                body: head.method == "HEAD" ? Data() : stub.data,
                explicitContentLength: stub.data.count,
                close: shouldClose
            )
            return !shouldClose

        case "PUT":
            let data: Data
            if let putTempFileURL, let loaded = try? Data(contentsOf: putTempFileURL) {
                data = loaded.prefix(Self.maxMetadataStubTotalBytes)
            } else {
                data = Data()
            }
            let existed = storeMetadataStub(path: relativePath, isDirectory: false, data: data)
            try await sendSimpleResponse(
                stream,
                status: existed ? 204 : 201,
                reason: existed ? "No Content" : "Created",
                close: shouldClose
            )
            return !shouldClose

        case "MKCOL":
            _ = storeMetadataStub(path: relativePath, isDirectory: true, data: Data())
            try await sendSimpleResponse(stream, status: 201, reason: "Created", close: shouldClose)
            return !shouldClose

        case "DELETE":
            removeMetadataStub(path: relativePath)
            clearLocks(matchingPrefix: davHref(relativeSegments: relativeSegments, isDirectory: false))
            try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
            return !shouldClose

        case "MOVE":
            if let destHeader = head.header("destination"),
               case let .authorized(destRel, _, _) = resolveDestinationHeader(destHeader),
               Self.isIgnoredMacMetadataPath(destRel)
            {
                let moved = state.withLock { s -> Bool in
                    guard let existing = s.metadataStubs.removeValue(forKey: relativePath) else { return false }
                    s.metadataStubs[destRel] = existing
                    return true
                }
                try await sendSimpleResponse(stream, status: moved ? 201 : 404, reason: moved ? "Created" : "Not Found", close: shouldClose)
            } else {
                removeMetadataStub(path: relativePath)
                try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
            }
            return !shouldClose

        case "LOCK":
            return try await handleLock(head: head, relativeSegments: relativeSegments, shouldClose: shouldClose, stream: stream)

        case "UNLOCK":
            return try await handleUnlock(head: head, shouldClose: shouldClose, stream: stream)

        default:
            try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
            return !shouldClose
        }
    }

    private func storeMetadataStub(path: String, isDirectory: Bool, data: Data) -> Bool {
        state.withLock { s in
            let existed = s.metadataStubs[path] != nil
            if let old = s.metadataStubs[path] {
                s.totalMetadataBytes -= old.data.count
            }
            s.metadataSeq &+= 1
            s.metadataStubs[path] = MetadataStub(
                isDirectory: isDirectory,
                data: data,
                modifiedAt: Date(),
                lastAccessSeq: s.metadataSeq
            )
            s.totalMetadataBytes += data.count

            while s.metadataStubs.count > Self.maxMetadataStubEntries || s.totalMetadataBytes > Self.maxMetadataStubTotalBytes {
                guard let victim = s.metadataStubs
                    .filter({ $0.key != path })
                    .min(by: { $0.value.lastAccessSeq < $1.value.lastAccessSeq })
                else { break }
                s.metadataStubs.removeValue(forKey: victim.key)
                s.totalMetadataBytes -= victim.value.data.count
            }
            return existed
        }
    }

    private func removeMetadataStub(path: String) {
        state.withLock { s in
            let prefix = path == "/" ? "/" : "\(path)/"
            let keysToRemove = s.metadataStubs.keys.filter { $0 == path || $0.hasPrefix(prefix) }
            for key in keysToRemove {
                if let removed = s.metadataStubs.removeValue(forKey: key) {
                    s.totalMetadataBytes = max(0, s.totalMetadataBytes - removed.data.count)
                }
            }
        }
    }

    // MARK: - WebDAV Operations Against RemoteFileSystemBackend

    private func handlePropfind(
        head: WebDavParsedRequestHead,
        remotePath: String,
        relativeSegments: [String],
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        let depthHeader = head.header("depth")?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? "1"
        let depth: Int = (depthHeader == "0") ? 0 : 1

        let targetItem: RemoteFileItem
        do {
            targetItem = try await backend.stat(path: remotePath)
        } catch {
            if relativeSegments.isEmpty && WebDavWireCodec.mapErrorToHTTPStatus(error).status == 404 {
                targetItem = RemoteFileItem(
                    name: "",
                    path: rootPath,
                    isDirectory: true,
                    sizeBytes: 0,
                    modifiedAtMs: Int64(Date().timeIntervalSince1970 * 1000)
                )
            } else {
                return try await sendMappedError(error, stream: stream, shouldClose: shouldClose)
            }
        }

        var entries: [(item: RemoteFileItem, segments: [String])] = [(targetItem, relativeSegments)]
        if depth >= 1 && targetItem.isDirectory {
            do {
                let children = try await backend.listDirectory(path: remotePath)
                for child in children where !child.name.isEmpty && child.name != "." && child.name != ".." {
                    entries.append((child, relativeSegments + [child.name]))
                }
            } catch {
                return try await sendMappedError(error, stream: stream, shouldClose: shouldClose)
            }
        }

        let xml = buildMultistatusXML(entries)
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

    private func handleGetOrHead(
        head: WebDavParsedRequestHead,
        remotePath: String,
        isHead: Bool,
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        let item: RemoteFileItem
        do {
            item = try await backend.stat(path: remotePath)
        } catch {
            return try await sendMappedError(error, stream: stream, shouldClose: shouldClose)
        }

        if item.isDirectory {
            if isHead {
                try await sendResponse(
                    stream,
                    status: 200,
                    reason: "OK",
                    headers: [("Content-Type", "httpd/unix-directory"), ("Accept-Ranges", "bytes")],
                    body: Data(),
                    explicitContentLength: 0,
                    close: shouldClose
                )
            } else {
                try await sendSimpleResponse(stream, status: 405, reason: "Method Not Allowed", close: shouldClose)
            }
            return !shouldClose
        }

        let contentType = item.mimeType.isEmpty ? WebDavWireCodec.guessMimeType(forName: item.name) : item.mimeType
        let etag = WebDavWireCodec.makeETag(for: item)
        let lastMod = WebDavWireCodec.formatRFC1123Date(ms: item.modifiedAtMs)

        // For HEAD we already have authoritative metadata from stat (avoid pulling large files over LAN).
        if isHead {
            let totalSize = max(0, item.sizeBytes)
            try await sendResponse(
                stream,
                status: 200,
                reason: "OK",
                headers: [
                    ("Content-Type", contentType),
                    ("Accept-Ranges", "bytes"),
                    ("ETag", etag),
                    ("Last-Modified", lastMod)
                ],
                body: Data(),
                explicitContentLength: Int(min(totalSize, Int64(Int.max))),
                close: shouldClose
            )
            return !shouldClose
        }

        let cached: (url: URL, sizeBytes: Int64, unlinkAfterServe: Bool)
        do {
            cached = try await fetchIntoCache(item: item, remotePath: remotePath)
        } catch {
            return try await sendMappedError(error, stream: stream, shouldClose: shouldClose)
        }

        defer {
            if cached.unlinkAfterServe {
                try? FileManager.default.removeItem(at: cached.url)
            }
        }

        let totalSize = cached.sizeBytes
        var startOffset: Int64 = 0
        var endOffset: Int64 = totalSize > 0 ? (totalSize - 1) : -1
        var isPartial = false

        if let rangeHeader = head.header("range") {
            switch WebDavWireCodec.parseByteRange(rangeHeader, totalSize: totalSize) {
            case .none:
                break
            case .unsatisfiable:
                try await sendResponse(
                    stream,
                    status: 416,
                    reason: "Range Not Satisfiable",
                    headers: [
                        ("Content-Range", "bytes */\(totalSize)"),
                        ("Accept-Ranges", "bytes")
                    ],
                    body: Data(),
                    close: shouldClose
                )
                return !shouldClose
            case let .range(start, end):
                startOffset = start
                endOffset = end
                isPartial = true
            }
        }

        let responseLength: Int64 = (totalSize == 0 || endOffset < startOffset) ? 0 : (endOffset - startOffset + 1)
        var headers: [(String, String)] = [
            ("Content-Type", contentType),
            ("Accept-Ranges", "bytes"),
            ("ETag", etag),
            ("Last-Modified", lastMod)
        ]
        if isPartial {
            headers.append(("Content-Range", "bytes \(startOffset)-\(endOffset)/\(totalSize)"))
        }

        try await sendFileResponse(
            stream,
            status: isPartial ? 206 : 200,
            reason: isPartial ? "Partial Content" : "OK",
            headers: headers,
            fileURL: cached.url,
            startOffset: startOffset,
            byteCount: responseLength,
            close: shouldClose
        )
        return !shouldClose
    }

    private func handlePut(
        remotePath: String,
        putTempFileURL: URL?,
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        guard let tempURL = putTempFileURL else {
            try await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: shouldClose)
            return !shouldClose
        }
        guard let parentDir = FileBrowserPresentation.parentPath(of: remotePath) else {
            try await sendSimpleResponse(stream, status: 403, reason: "Forbidden", close: shouldClose)
            return !shouldClose
        }
        let fileName = (remotePath as NSString).lastPathComponent
        guard FileBrowserPresentation.isValidEntryName(fileName) else {
            try await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: shouldClose)
            return !shouldClose
        }

        do {
            try await backend.uploadFile(localURL: tempURL, toRemoteDirectory: parentDir, remoteFileName: fileName)
        } catch {
            return try await sendMappedError(error, stream: stream, shouldClose: shouldClose)
        }

        invalidateCache(forRemotePath: remotePath)
        try await sendSimpleResponse(stream, status: 201, reason: "Created", close: shouldClose)
        return !shouldClose
    }

    private func handleMkcol(
        remotePath: String,
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        do {
            try await backend.makeDirectory(path: remotePath)
        } catch {
            return try await sendMappedError(error, stream: stream, shouldClose: shouldClose)
        }
        try await sendSimpleResponse(stream, status: 201, reason: "Created", close: shouldClose)
        return !shouldClose
    }

    private func handleDelete(
        remotePath: String,
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        do {
            try await backend.delete(path: remotePath)
        } catch {
            return try await sendMappedError(error, stream: stream, shouldClose: shouldClose)
        }
        invalidateCache(forRemotePath: remotePath)
        try await sendSimpleResponse(stream, status: 204, reason: "No Content", close: shouldClose)
        return !shouldClose
    }

    private func handleMove(
        head: WebDavParsedRequestHead,
        sourceRemotePath: String,
        shouldClose: Bool,
        stream: WebDavConnectionStream
    ) async throws -> Bool {
        guard let destinationHeader = head.header("destination"), !destinationHeader.isEmpty else {
            try await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: shouldClose)
            return !shouldClose
        }

        let destResolution = resolveDestinationHeader(destinationHeader)
        guard case let .authorized(destRel, destRemotePath, _) = destResolution else {
            switch destResolution {
            case .forbidden:
                try await sendSimpleResponse(stream, status: 403, reason: "Forbidden", close: shouldClose)
            case .badRequest:
                try await sendSimpleResponse(stream, status: 400, reason: "Bad Request", close: shouldClose)
            case .authorized:
                break
            }
            return !shouldClose
        }

        if Self.isIgnoredMacMetadataPath(destRel) {
            try await sendSimpleResponse(stream, status: 201, reason: "Created", close: shouldClose)
            return !shouldClose
        }

        if sourceRemotePath == destRemotePath {
            try await sendSimpleResponse(stream, status: 403, reason: "Forbidden", close: shouldClose)
            return !shouldClose
        }

        let overwriteAllowed = (head.header("overwrite")?.uppercased() ?? "T") != "F"
        var destExisted = false
        if (try? await backend.stat(path: destRemotePath)) != nil {
            destExisted = true
            if !overwriteAllowed {
                try await sendSimpleResponse(stream, status: 412, reason: "Precondition Failed", close: shouldClose)
                return !shouldClose
            }
        }

        do {
            try await backend.rename(from: sourceRemotePath, to: destRemotePath)
        } catch {
            return try await sendMappedError(error, stream: stream, shouldClose: shouldClose)
        }

        invalidateCache(forRemotePath: sourceRemotePath)
        invalidateCache(forRemotePath: destRemotePath)
        try await sendSimpleResponse(
            stream,
            status: destExisted ? 204 : 201,
            reason: destExisted ? "No Content" : "Created",
            close: shouldClose
        )
        return !shouldClose
    }

    private func handleLock(
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

    private func handleUnlock(
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

    private func handleProppatch(
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

    private func clearLocks(matchingPrefix prefix: String) {
        state.withLock { s in
            s.locksByToken = s.locksByToken.filter { !$0.value.path.hasPrefix(prefix) }
        }
    }

    private func sendMappedError(
        _ error: Error,
        stream: WebDavConnectionStream,
        shouldClose: Bool
    ) async throws -> Bool {
        let mapped = WebDavWireCodec.mapErrorToHTTPStatus(error)
        try await sendSimpleResponse(stream, status: mapped.status, reason: mapped.reason, close: shouldClose)
        return !shouldClose
    }

    // MARK: - XML & HTTP Response Builders

    private func buildMultistatusXML(_ items: [(item: RemoteFileItem, segments: [String])]) -> String {
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

    private func davHref(relativeSegments: [String], isDirectory: Bool) -> String {
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

    private func sendResponse(
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

    private func sendFileResponse(
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
