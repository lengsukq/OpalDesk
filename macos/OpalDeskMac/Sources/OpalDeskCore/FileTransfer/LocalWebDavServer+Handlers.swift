import Foundation

// MARK: - WebDAV Method Dispatch, Metadata Interception & Response Writers

extension LocalWebDavServer {
    private static let allowedMethodsHeader = "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, PROPFIND, LOCK, UNLOCK"

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
}
