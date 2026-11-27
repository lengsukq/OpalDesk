import Foundation
import Network
import os
import Testing
@testable import OpalDeskCore

// MARK: - In-Memory Mock Remote File System Backend

private final class MockRemoteFileSystemBackend: RemoteFileSystemBackend, Sendable {
    struct Node: Sendable {
        var isDirectory: Bool
        var data: Data
        var modifiedAtMs: Int64
        var mimeType: String
    }

    struct MutableState {
        var nodes: [String: Node] = [:]
        var pullCount: Int = 0
        var uploadCalls: [(directory: String, fileName: String, bytes: Int)] = []
        var deleteCalls: [String] = []
        var mkdirCalls: [String] = []
        var renameCalls: [(oldPath: String, newPath: String)] = []
        var nowMs: Int64 = 1_764_200_000_000
    }

    let state = OSAllocatedUnfairLock(initialState: MutableState())

    init() {
        state.withLock { s in
            s.nodes["/sdcard"] = Node(isDirectory: true, data: Data(), modifiedAtMs: 1_764_200_000_000, mimeType: "")
            s.nodes["/sdcard/DCIM"] = Node(isDirectory: true, data: Data(), modifiedAtMs: 1_764_200_000_000, mimeType: "")
            s.nodes["/sdcard/hello.txt"] = Node(
                isDirectory: false,
                data: Data("0123456789ABCDEF".utf8),
                modifiedAtMs: 1_764_200_001_000,
                mimeType: "text/plain"
            )
            s.nodes["/sdcard/DCIM/my photo & <1>.jpg"] = Node(
                isDirectory: false,
                data: Data([0xFF, 0xD8, 0xFF, 0xE0, 0x11, 0x22, 0x33, 0x44]),
                modifiedAtMs: 1_764_200_002_000,
                mimeType: "image/jpeg"
            )
        }
    }

    func listDirectory(path: String) async throws -> [RemoteFileItem] {
        let normalized = FileBrowserPresentation.normalizePath(path)
        return try state.withLock { s in
            guard let dir = s.nodes[normalized], dir.isDirectory else {
                throw RemoteFileSystemError.notFound(normalized)
            }
            let prefix = normalized == "/" ? "/" : "\(normalized)/"
            var result: [RemoteFileItem] = []
            for (nodePath, node) in s.nodes where nodePath.hasPrefix(prefix) && nodePath != normalized {
                let tail = String(nodePath.dropFirst(prefix.count))
                guard !tail.contains("/"), !tail.isEmpty else { continue }
                result.append(RemoteFileItem(
                    name: tail,
                    path: nodePath,
                    isDirectory: node.isDirectory,
                    sizeBytes: Int64(node.data.count),
                    modifiedAtMs: node.modifiedAtMs,
                    mimeType: node.mimeType
                ))
            }
            return result.sorted { $0.name < $1.name }
        }
    }

    func stat(path: String) async throws -> RemoteFileItem {
        let normalized = FileBrowserPresentation.normalizePath(path)
        return try state.withLock { s in
            guard let node = s.nodes[normalized] else {
                throw RemoteFileSystemError.notFound(normalized)
            }
            let name = normalized == "/" ? "/" : (normalized as NSString).lastPathComponent
            return RemoteFileItem(
                name: name,
                path: normalized,
                isDirectory: node.isDirectory,
                sizeBytes: Int64(node.data.count),
                modifiedAtMs: node.modifiedAtMs,
                mimeType: node.mimeType
            )
        }
    }

    func pullFile(remotePath: String, to localURL: URL) async throws -> URL {
        let normalized = FileBrowserPresentation.normalizePath(remotePath)
        let bytes: Data = try state.withLock { s in
            guard let node = s.nodes[normalized], !node.isDirectory else {
                throw RemoteFileSystemError.notFound(normalized)
            }
            s.pullCount += 1
            return node.data
        }
        try bytes.write(to: localURL, options: .atomic)
        return localURL
    }

    func uploadFile(localURL: URL, toRemoteDirectory: String, remoteFileName: String) async throws {
        let data = try Data(contentsOf: localURL)
        let dir = FileBrowserPresentation.normalizePath(toRemoteDirectory)
        let dest = dir == "/" ? "/\(remoteFileName)" : "\(dir)/\(remoteFileName)"
        try state.withLock { s in
            guard let parent = s.nodes[dir], parent.isDirectory else {
                throw RemoteFileSystemError.notFound(dir)
            }
            s.nowMs += 1_000
            s.nodes[dest] = Node(
                isDirectory: false,
                data: data,
                modifiedAtMs: s.nowMs,
                mimeType: "application/octet-stream"
            )
            s.uploadCalls.append((dir, remoteFileName, data.count))
        }
    }

    func makeDirectory(path: String) async throws {
        let normalized = FileBrowserPresentation.normalizePath(path)
        try state.withLock { s in
            if s.nodes[normalized] != nil {
                throw RemoteFileSystemError.alreadyExists(normalized)
            }
            s.nowMs += 1_000
            s.nodes[normalized] = Node(isDirectory: true, data: Data(), modifiedAtMs: s.nowMs, mimeType: "")
            s.mkdirCalls.append(normalized)
        }
    }

    func rename(from oldPath: String, to newPath: String) async throws {
        let src = FileBrowserPresentation.normalizePath(oldPath)
        let dst = FileBrowserPresentation.normalizePath(newPath)
        try state.withLock { s in
            guard let node = s.nodes.removeValue(forKey: src) else {
                throw RemoteFileSystemError.notFound(src)
            }
            s.nowMs += 1_000
            var moved = node
            moved.modifiedAtMs = s.nowMs
            s.nodes[dst] = moved
            s.renameCalls.append((src, dst))
        }
    }

    func delete(path: String) async throws {
        let normalized = FileBrowserPresentation.normalizePath(path)
        try state.withLock { s in
            guard s.nodes[normalized] != nil else {
                throw RemoteFileSystemError.notFound(normalized)
            }
            let prefix = "\(normalized)/"
            let toRemove = s.nodes.keys.filter { $0 == normalized || $0.hasPrefix(prefix) }
            for key in toRemove {
                s.nodes.removeValue(forKey: key)
            }
            s.deleteCalls.append(normalized)
        }
    }
}

// MARK: - Minimal XML Element Collector for PROPFIND / LOCK Assertions

private final class SimpleXMLCollector: NSObject, XMLParserDelegate {
    var elementNames: [String] = []
    var hrefs: [String] = []
    var lockTokens: [String] = []
    var parseSucceeded = false

    private var currentElement = ""
    private var currentText = ""
    private var insideLockToken = false

    func parse(_ data: Data) -> Bool {
        let parser = XMLParser(data: data)
        parser.delegate = self
        parseSucceeded = parser.parse()
        return parseSucceeded
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        elementNames.append(elementName)
        currentElement = elementName
        currentText = ""
        if elementName.hasSuffix("locktoken") {
            insideLockToken = true
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let trimmed = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
        if elementName.hasSuffix("href") && !trimmed.isEmpty {
            if insideLockToken {
                lockTokens.append(trimmed)
            } else {
                hrefs.append(trimmed)
            }
        }
        if elementName.hasSuffix("locktoken") {
            insideLockToken = false
        }
        currentText = ""
    }
}

// MARK: - Test Suite

@Suite("LocalWebDavServer & FinderMountManager")
struct LocalWebDavServerTests {

    private func makeEphemeralSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 10
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }

    @Test("Apple metadata path filter matches Finder/Spotlight noise and ignores regular files")
    func metadataPathFilter() {
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/.DS_Store") == true)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/DCIM/.DS_Store") == true)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/DCIM/._photo.jpg") == true)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/.Trashes") == true)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/.Trashes/501/deleted.txt") == true)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/.Spotlight-V100/VolumeConfiguration.plist") == true)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/.fseventsd/no_log") == true)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/.TemporaryItems/folders.501") == true)

        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/") == false)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/DCIM/photo.jpg") == false)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/DCIM/.nomedia") == false)
        #expect(LocalWebDavServer.isIgnoredMacMetadataPath("/Documents/report.pdf") == false)
    }

    @Test("Loopback WebDAV server enforces 128-bit token prefix and handles OPTIONS, PROPFIND, GET/Range, PUT, MKCOL, MOVE, DELETE, LOCK/UNLOCK")
    func fullWebDavProtocolRoundtrip() async throws {
        let backend = MockRemoteFileSystemBackend()
        let server = LocalWebDavServer(backend: backend)
        let rootURL = try await server.start()
        defer { server.stop() }

        #expect(server.token.count == 32)
        #expect(rootURL.host == "127.0.0.1")
        #expect(server.isRunning == true)

        let session = makeEphemeralSession()
        defer { session.invalidateAndCancel() }
        let port = try #require(server.port)

        // 1. Missing or wrong token prefix -> 403 Forbidden
        let noTokenURL = URL(string: "http://127.0.0.1:\(port)/hello.txt")!
        let (_, noTokenResp) = try await session.data(from: noTokenURL)
        #expect((noTokenResp as? HTTPURLResponse)?.statusCode == 403)

        let wrongTokenURL = URL(string: "http://127.0.0.1:\(port)/\(String(repeating: "0", count: 32))/hello.txt")!
        let (_, wrongTokenResp) = try await session.data(from: wrongTokenURL)
        #expect((wrongTokenResp as? HTTPURLResponse)?.statusCode == 403)

        // 2. OPTIONS -> DAV: 1, 2 & MS-Author-Via: DAV
        var optReq = URLRequest(url: rootURL)
        optReq.httpMethod = "OPTIONS"
        let (_, optRespRaw) = try await session.data(for: optReq)
        let optResp = try #require(optRespRaw as? HTTPURLResponse)
        #expect(optResp.statusCode == 200)
        #expect(optResp.value(forHTTPHeaderField: "DAV") == "1, 2")
        #expect(optResp.value(forHTTPHeaderField: "MS-Author-Via") == "DAV")
        let allowHeader = optResp.value(forHTTPHeaderField: "Allow") ?? ""
        #expect(allowHeader.contains("PROPFIND"))
        #expect(allowHeader.contains("LOCK"))
        #expect(allowHeader.contains("UNLOCK"))
        #expect(allowHeader.contains("MOVE"))

        // 3. PROPFIND Depth: 0 vs Depth: 1 (including XML escaping of special chars in DCIM)
        var pf0Req = URLRequest(url: rootURL)
        pf0Req.httpMethod = "PROPFIND"
        pf0Req.setValue("0", forHTTPHeaderField: "Depth")
        let (pf0Data, pf0RespRaw) = try await session.data(for: pf0Req)
        #expect((pf0RespRaw as? HTTPURLResponse)?.statusCode == 207)
        let pf0XML = SimpleXMLCollector()
        #expect(pf0XML.parse(pf0Data) == true)
        #expect(pf0XML.hrefs.count == 1)
        #expect(pf0XML.hrefs[0] == "/\(server.token)/")

        var pf1Req = URLRequest(url: rootURL)
        pf1Req.httpMethod = "PROPFIND"
        pf1Req.setValue("1", forHTTPHeaderField: "Depth")
        let (pf1Data, pf1RespRaw) = try await session.data(for: pf1Req)
        #expect((pf1RespRaw as? HTTPURLResponse)?.statusCode == 207)
        let pf1XML = SimpleXMLCollector()
        #expect(pf1XML.parse(pf1Data) == true)
        #expect(pf1XML.hrefs.contains("/\(server.token)/"))
        #expect(pf1XML.hrefs.contains("/\(server.token)/DCIM/"))
        #expect(pf1XML.hrefs.contains("/\(server.token)/hello.txt"))

        var pfDcimReq = URLRequest(url: rootURL.appendingPathComponent("DCIM/"))
        pfDcimReq.httpMethod = "PROPFIND"
        pfDcimReq.setValue("1", forHTTPHeaderField: "Depth")
        let (pfDcimData, pfDcimRespRaw) = try await session.data(for: pfDcimReq)
        #expect((pfDcimRespRaw as? HTTPURLResponse)?.statusCode == 207)
        let pfDcimXML = SimpleXMLCollector()
        #expect(pfDcimXML.parse(pfDcimData) == true)
        #expect(pfDcimXML.hrefs.count == 2)

        // 4. HEAD + Full GET + Cached Range GET (206 Partial Content) + 416 Unsatisfiable
        let helloURL = rootURL.appendingPathComponent("hello.txt")
        var headReq = URLRequest(url: helloURL)
        headReq.httpMethod = "HEAD"
        let (headData, headRespRaw) = try await session.data(for: headReq)
        let headResp = try #require(headRespRaw as? HTTPURLResponse)
        #expect(headResp.statusCode == 200)
        #expect(headData.isEmpty)
        #expect(headResp.value(forHTTPHeaderField: "Content-Length") == "16")
        #expect(headResp.value(forHTTPHeaderField: "Accept-Ranges") == "bytes")

        let (fullData, fullRespRaw) = try await session.data(from: helloURL)
        #expect((fullRespRaw as? HTTPURLResponse)?.statusCode == 200)
        #expect(String(data: fullData, encoding: .utf8) == "0123456789ABCDEF")
        #expect(backend.state.withLock { $0.pullCount } == 1)

        var rangeReq = URLRequest(url: helloURL)
        rangeReq.setValue("bytes=4-9", forHTTPHeaderField: "Range")
        let (rangeData, rangeRespRaw) = try await session.data(for: rangeReq)
        let rangeResp = try #require(rangeRespRaw as? HTTPURLResponse)
        #expect(rangeResp.statusCode == 206)
        #expect(rangeResp.value(forHTTPHeaderField: "Content-Range") == "bytes 4-9/16")
        #expect(String(data: rangeData, encoding: .utf8) == "456789")
        // Second GET hit the per-session LRU cache without calling pullFile again.
        #expect(backend.state.withLock { $0.pullCount } == 1)

        var badRangeReq = URLRequest(url: helloURL)
        badRangeReq.setValue("bytes=100-200", forHTTPHeaderField: "Range")
        let (_, badRangeResp) = try await session.data(for: badRangeReq)
        #expect((badRangeResp as? HTTPURLResponse)?.statusCode == 416)

        // 5. PUT upload -> 201 Created, updates backend and invalidates cache
        var putReq = URLRequest(url: helloURL)
        putReq.httpMethod = "PUT"
        let updatedBody = Data("UPDATED_CONTENT_12345".utf8)
        let (_, putRespRaw) = try await session.upload(for: putReq, from: updatedBody)
        #expect((putRespRaw as? HTTPURLResponse)?.statusCode == 201)
        #expect(backend.state.withLock { $0.uploadCalls.count } == 1)
        #expect(backend.state.withLock { $0.uploadCalls[0].fileName } == "hello.txt")

        let (refetchedData, _) = try await session.data(from: helloURL)
        #expect(refetchedData == updatedBody)
        #expect(backend.state.withLock { $0.pullCount } == 2)

        // 6. MKCOL -> 201 Created
        let newDirURL = rootURL.appendingPathComponent("Projects")
        var mkcolReq = URLRequest(url: newDirURL)
        mkcolReq.httpMethod = "MKCOL"
        let (_, mkcolResp) = try await session.data(for: mkcolReq)
        #expect((mkcolResp as? HTTPURLResponse)?.statusCode == 201)
        #expect(backend.state.withLock { $0.mkdirCalls } == ["/sdcard/Projects"])

        // 7. MOVE -> rejects bad token Destination with 403, succeeds with matching token -> 201 Created
        let destURL = rootURL.appendingPathComponent("Projects/moved.txt")
        var badMoveReq = URLRequest(url: helloURL)
        badMoveReq.httpMethod = "MOVE"
        badMoveReq.setValue("http://127.0.0.1:\(port)/badtoken/moved.txt", forHTTPHeaderField: "Destination")
        let (_, badMoveResp) = try await session.data(for: badMoveReq)
        #expect((badMoveResp as? HTTPURLResponse)?.statusCode == 403)

        var moveReq = URLRequest(url: helloURL)
        moveReq.httpMethod = "MOVE"
        moveReq.setValue(destURL.absoluteString, forHTTPHeaderField: "Destination")
        let (_, moveResp) = try await session.data(for: moveReq)
        #expect((moveResp as? HTTPURLResponse)?.statusCode == 201)
        #expect(backend.state.withLock { $0.renameCalls.count } == 1)
        #expect(backend.state.withLock { $0.renameCalls[0].oldPath } == "/sdcard/hello.txt")
        #expect(backend.state.withLock { $0.renameCalls[0].newPath } == "/sdcard/Projects/moved.txt")

        // 8. LOCK & UNLOCK -> valid RFC 4918 XML + Lock-Token header
        var lockReq = URLRequest(url: destURL)
        lockReq.httpMethod = "LOCK"
        lockReq.setValue("0", forHTTPHeaderField: "Depth")
        let (lockData, lockRespRaw) = try await session.data(for: lockReq)
        let lockResp = try #require(lockRespRaw as? HTTPURLResponse)
        #expect(lockResp.statusCode == 200)
        let lockTokenHeader = try #require(lockResp.value(forHTTPHeaderField: "Lock-Token"))
        #expect(lockTokenHeader.hasPrefix("<opaquelocktoken:"))
        let lockXML = SimpleXMLCollector()
        #expect(lockXML.parse(lockData) == true)
        #expect(lockXML.lockTokens.first?.hasPrefix("opaquelocktoken:") == true)

        var unlockReq = URLRequest(url: destURL)
        unlockReq.httpMethod = "UNLOCK"
        unlockReq.setValue(lockTokenHeader, forHTTPHeaderField: "Lock-Token")
        let (_, unlockResp) = try await session.data(for: unlockReq)
        #expect((unlockResp as? HTTPURLResponse)?.statusCode == 204)

        // 9. DELETE -> 204 No Content
        var delReq = URLRequest(url: destURL)
        delReq.httpMethod = "DELETE"
        let (_, delResp) = try await session.data(for: delReq)
        #expect((delResp as? HTTPURLResponse)?.statusCode == 204)
        #expect(backend.state.withLock { $0.deleteCalls } == ["/sdcard/Projects/moved.txt"])
    }

    @Test("Apple metadata writes (.DS_Store & ._photo.jpg) succeed in memory without ever touching backend")
    func appleMetadataInterceptedInMemory() async throws {
        let backend = MockRemoteFileSystemBackend()
        let server = LocalWebDavServer(backend: backend)
        let rootURL = try await server.start()
        defer { server.stop() }

        let session = makeEphemeralSession()
        defer { session.invalidateAndCancel() }

        let dsStoreURL = rootURL.appendingPathComponent(".DS_Store")
        let appleDoubleURL = rootURL.appendingPathComponent("DCIM/._photo.jpg")

        // PROPFIND before write -> 404 (in memory)
        var pfBefore = URLRequest(url: dsStoreURL)
        pfBefore.httpMethod = "PROPFIND"
        let (_, pfBeforeResp) = try await session.data(for: pfBefore)
        #expect((pfBeforeResp as? HTTPURLResponse)?.statusCode == 404)

        // PUT .DS_Store and ._photo.jpg -> 201 Created without backend.uploadFile
        var putDS = URLRequest(url: dsStoreURL)
        putDS.httpMethod = "PUT"
        let (_, putDSResp) = try await session.upload(for: putDS, from: Data([0x00, 0x00, 0x00, 0x01]))
        #expect((putDSResp as? HTTPURLResponse)?.statusCode == 201)

        var putAD = URLRequest(url: appleDoubleURL)
        putAD.httpMethod = "PUT"
        let (_, putADResp) = try await session.upload(for: putAD, from: Data([0x00, 0x05, 0x16, 0x07]))
        #expect((putADResp as? HTTPURLResponse)?.statusCode == 201)

        // PROPFIND & GET on in-memory stub succeed
        var pfAfter = URLRequest(url: dsStoreURL)
        pfAfter.httpMethod = "PROPFIND"
        let (_, pfAfterResp) = try await session.data(for: pfAfter)
        #expect((pfAfterResp as? HTTPURLResponse)?.statusCode == 207)

        let (getADData, getADResp) = try await session.data(from: appleDoubleURL)
        #expect((getADResp as? HTTPURLResponse)?.statusCode == 200)
        #expect(getADData == Data([0x00, 0x05, 0x16, 0x07]))

        // LOCK, UNLOCK, DELETE on metadata stub succeed in memory
        var lockDS = URLRequest(url: dsStoreURL)
        lockDS.httpMethod = "LOCK"
        let (_, lockRespRaw) = try await session.data(for: lockDS)
        let lockResp = try #require(lockRespRaw as? HTTPURLResponse)
        #expect(lockResp.statusCode == 200)

        var delDS = URLRequest(url: dsStoreURL)
        delDS.httpMethod = "DELETE"
        let (_, delDSResp) = try await session.data(for: delDS)
        #expect((delDSResp as? HTTPURLResponse)?.statusCode == 204)

        // Backend was NEVER touched for upload or delete!
        #expect(backend.state.withLock { $0.uploadCalls.isEmpty })
        #expect(backend.state.withLock { $0.deleteCalls.isEmpty })
    }

    @Test("FinderMountManager sanitizes device names, cleans stale dirs, mounts via mount_webdav, and falls back to diskutil on unmount")
    func finderMountManagerLifecycle() async throws {
        #expect(FinderMountManager.sanitizeDeviceName("Xiaomi 15 / Pro:2026\\Test\n") == "Xiaomi 15 _ Pro_2026_Test")
        #expect(FinderMountManager.sanitizeDeviceName("...hidden") == "hidden")
        #expect(FinderMountManager.sanitizeDeviceName("   ") == "AndroidDevice")

        let tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-mounts-test-\(UUID().uuidString)", isDirectory: true)
        let staleDir = tempRoot.appendingPathComponent("StaleOldDevice", isDirectory: true)
        try FileManager.default.createDirectory(at: staleDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }

        let commands = OSAllocatedUnfairLock<[(executable: String, args: [String])]>(initialState: [])
        let stateHistory = OSAllocatedUnfairLock<[FinderMountState]>(initialState: [])

        let manager = FinderMountManager(
            mountsRootURL: tempRoot,
            commandExecutor: { executable, args, _ in
                commands.withLock { $0.append((executable, args)) }
                if executable == "/sbin/umount" {
                    // Simulate busy umount to verify fallback to `diskutil unmount force`.
                    return MountCommandResult(exitCode: 1, stdout: "", stderr: "Resource busy")
                }
                return MountCommandResult(exitCode: 0, stdout: "ok", stderr: "")
            },
            onStateChanged: { newState in
                stateHistory.withLock { $0.append(newState) }
            }
        )

        // Stale empty directory was cleaned up during init.
        #expect(FileManager.default.fileExists(atPath: staleDir.path) == false)

        let backend = MockRemoteFileSystemBackend()
        let mountPoint = try await manager.mount(deviceName: "Xiaomi 15/Pro", backend: backend)

        #expect(mountPoint.lastPathComponent == "Xiaomi 15_Pro")
        #expect(FileManager.default.fileExists(atPath: mountPoint.path) == true)
        #expect(manager.currentState.isMounted == true)

        let mountCmd = try #require(commands.withLock { $0.first })
        #expect(mountCmd.executable == "/sbin/mount_webdav")
        #expect(mountCmd.args.contains("-S"))
        #expect(mountCmd.args.contains("-v"))
        #expect(mountCmd.args.contains("Xiaomi 15_Pro"))
        #expect(mountCmd.args.last == mountPoint.path)

        await manager.unmount()
        #expect(manager.currentState == .unmounted)
        #expect(FileManager.default.fileExists(atPath: mountPoint.path) == false)

        let allCmds = commands.withLock { $0 }
        #expect(allCmds.count == 3)
        #expect(allCmds[1].executable == "/sbin/umount")
        #expect(allCmds[2].executable == "/usr/sbin/diskutil")
        #expect(allCmds[2].args == ["unmount", "force", mountPoint.path])
    }

    @Test("LocalWebDavServer handles Transfer-Encoding: chunked PUT and enforces bounded LRU cache eviction")
    func chunkedPutAndLruCacheEviction() async throws {
        let backend = MockRemoteFileSystemBackend()
        // Populate two 1024-byte files and cap LRU cache at 1536 bytes so caching the 2nd evicts the 1st.
        let payloadA = Data(repeating: 0x41, count: 1024)
        let payloadB = Data(repeating: 0x42, count: 1024)
        backend.state.withLock { s in
            s.nodes["/sdcard/a.bin"] = MockRemoteFileSystemBackend.Node(isDirectory: false, data: payloadA, modifiedAtMs: 1000, mimeType: "application/octet-stream")
            s.nodes["/sdcard/b.bin"] = MockRemoteFileSystemBackend.Node(isDirectory: false, data: payloadB, modifiedAtMs: 2000, mimeType: "application/octet-stream")
        }

        let server = LocalWebDavServer(backend: backend, maxCacheBytes: 1536)
        let rootURL = try await server.start()
        defer { server.stop() }
        let port = try #require(server.port)

        let session = makeEphemeralSession()
        defer { session.invalidateAndCancel() }

        // Fetch a.bin (pull #1), then fetch b.bin (pull #2 -> evicts a.bin), then fetch a.bin again (pull #3).
        let (gotA1, _) = try await session.data(from: rootURL.appendingPathComponent("a.bin"))
        #expect(gotA1 == payloadA)
        #expect(backend.state.withLock { $0.pullCount } == 1)

        let (gotB, _) = try await session.data(from: rootURL.appendingPathComponent("b.bin"))
        #expect(gotB == payloadB)
        #expect(backend.state.withLock { $0.pullCount } == 2)

        let (gotA2, _) = try await session.data(from: rootURL.appendingPathComponent("a.bin"))
        #expect(gotA2 == payloadA)
        #expect(backend.state.withLock { $0.pullCount } == 3)

        // Raw chunked HTTP/1.1 PUT with Expect: 100-continue over loopback socket
        let chunkedReq =
            "PUT /\(server.token)/chunked.txt HTTP/1.1\r\n" +
            "Host: 127.0.0.1:\(port)\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Expect: 100-continue\r\n" +
            "Connection: close\r\n\r\n" +
            "5\r\nHello\r\n" +
            "7\r\n WebDAV\r\n" +
            "0\r\n\r\n"

        let respText = try await sendRawHTTP(port: port, payload: Data(chunkedReq.utf8))
        #expect(respText.contains("HTTP/1.1 100 Continue"))
        #expect(respText.contains("HTTP/1.1 201 Created"))

        let (uploaded, _) = try await session.data(from: rootURL.appendingPathComponent("chunked.txt"))
        #expect(String(data: uploaded, encoding: .utf8) == "Hello WebDAV")
    }

    private func sendRawHTTP(port: UInt16, payload: Data) async throws -> String {
        let conn = NWConnection(
            host: NWEndpoint.Host.ipv4(.loopback),
            port: NWEndpoint.Port(rawValue: port)!,
            using: .tcp
        )
        let queue = DispatchQueue(label: "opaldesk.test.raw-http")
        defer { conn.cancel() }

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let done = OSAllocatedUnfairLock<Bool>(initialState: false)
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if done.withLock({ let prev = $0; $0 = true; return !prev }) {
                        cont.resume()
                    }
                case .failed(let err):
                    if done.withLock({ let prev = $0; $0 = true; return !prev }) {
                        cont.resume(throwing: err)
                    }
                default:
                    break
                }
            }
            conn.start(queue: queue)
        }

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.send(content: payload, completion: .contentProcessed { err in
                if let err { cont.resume(throwing: err) } else { cont.resume() }
            })
        }

        var responseData = Data()
        while true {
            let (chunk, isComplete): (Data, Bool) = try await withCheckedThrowingContinuation { cont in
                conn.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, complete, err in
                    if let err {
                        cont.resume(throwing: err)
                    } else {
                        cont.resume(returning: (data ?? Data(), complete))
                    }
                }
            }
            if !chunk.isEmpty {
                responseData.append(chunk)
            }
            if isComplete || chunk.isEmpty {
                break
            }
        }
        return String(data: responseData, encoding: .utf8) ?? ""
    }
}
