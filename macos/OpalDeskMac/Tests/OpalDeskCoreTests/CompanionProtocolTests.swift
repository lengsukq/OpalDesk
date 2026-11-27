import Foundation
import os
import Testing
@testable import OpalDeskCore

// MARK: - Test Doubles for Companion & Media Bridges

private final class MockNowPlayingBackend: NowPlayingBackend, Sendable {
    struct Snapshot: Sendable {
        var updates: [(state: MediaStatePayload, artworkData: Data?)] = []
        var clearCount: Int = 0
        var registerCount: Int = 0
        var unregisterCount: Int = 0
        var actionHandler: (@Sendable (MediaActionPayload) -> Void)?
    }

    let state = OSAllocatedUnfairLock(initialState: Snapshot())

    func updateNowPlaying(state mediaState: MediaStatePayload, artworkData: Data?) {
        state.withLock { $0.updates.append((mediaState, artworkData)) }
    }

    func clearNowPlaying() {
        state.withLock { $0.clearCount += 1 }
    }

    func registerCommands(onAction: @escaping @Sendable (MediaActionPayload) -> Void) {
        state.withLock {
            $0.registerCount += 1
            $0.actionHandler = onAction
        }
    }

    func unregisterCommands() {
        state.withLock {
            $0.unregisterCount += 1
            $0.actionHandler = nil
        }
    }

    func simulateCommand(_ action: MediaActionPayload) {
        let handler = state.withLock { $0.actionHandler }
        handler?(action)
    }
}

private final class MockCompanionTransport: CompanionSessionTransport, Sendable {
    struct MutableState {
        var sessionState: OpalCastSessionState = .idle
        var verifiedFp: String?
        var connectHistory: [OpalCastEndpoint] = []
        var sentFrames: [OpalCastFrame] = []
        var disconnectCount: Int = 0
        var eventHandler: (@Sendable (OpalCastSessionEvent) -> Void)?
    }

    let state = OSAllocatedUnfairLock(initialState: MutableState())

    var currentState: OpalCastSessionState {
        state.withLock { $0.sessionState }
    }

    var verifiedPeerFingerprint: String? {
        state.withLock { $0.verifiedFp }
    }

    func connect(to endpoint: OpalCastEndpoint, onEvent: @escaping @Sendable (OpalCastSessionEvent) -> Void) {
        state.withLock { s in
            s.connectHistory.append(endpoint)
            s.sessionState = .connecting
            s.eventHandler = onEvent
        }
        onEvent(.stateChanged(.connecting))
    }

    func disconnect() {
        state.withLock { s in
            s.disconnectCount += 1
            s.sessionState = .disconnected
            s.eventHandler = nil
        }
    }

    func send(_ frame: OpalCastFrame, allowDropOnCongestion: Bool) {
        state.withLock { $0.sentFrames.append(frame) }
    }

    func sendPaced(_ frame: OpalCastFrame) async {
        state.withLock { $0.sentFrames.append(frame) }
    }

    func emit(_ event: OpalCastSessionEvent) {
        if case .stateChanged(let next) = event {
            state.withLock { $0.sessionState = next }
        } else if case .verifiedPeerFingerprint(let fp) = event {
            state.withLock { $0.verifiedFp = fp }
        }
        let handler = state.withLock { $0.eventHandler }
        handler?(event)
    }

    func sentFramesSnapshot() -> [OpalCastFrame] {
        state.withLock { $0.sentFrames }
    }

    func clearSentFrames() {
        state.withLock { $0.sentFrames.removeAll() }
    }
}

private final class MutableClock: Sendable {
    private let current: OSAllocatedUnfairLock<Date>

    init(_ start: Date = Date(timeIntervalSince1970: 1_700_000_000)) {
        self.current = OSAllocatedUnfairLock(initialState: start)
    }

    func now() -> Date {
        current.withLock { $0 }
    }

    func advance(by seconds: TimeInterval) {
        current.withLock { $0 = $0.addingTimeInterval(seconds) }
    }
}

@Suite("OPD1 v1.2 Companion Protocol, MediaRemoteBridge & CompanionConnectionManager")
struct CompanionProtocolTests {

    @Test("OPD1 v1.2 frame type constants and companion session role match frozen contract")
    func frameTypeConstantsAndRole() {
        #expect(OpalCastSessionRole.companion.rawValue == "companion")

        // Downlink 0x19..0x1D
        #expect(OpalCastFrameType.notificationEvent.rawValue == 0x19)
        #expect(OpalCastFrameType.mediaState.rawValue == 0x1A)
        #expect(OpalCastFrameType.fileOpResponse.rawValue == 0x1B)
        #expect(OpalCastFrameType.filePullChunk.rawValue == 0x1C)
        #expect(OpalCastFrameType.apkInstallStatus.rawValue == 0x1D)

        // Uplink 0x31..0x34
        #expect(OpalCastFrameType.notificationAction.rawValue == 0x31)
        #expect(OpalCastFrameType.mediaAction.rawValue == 0x32)
        #expect(OpalCastFrameType.fileOpRequest.rawValue == 0x33)
        #expect(OpalCastFrameType.apkInstallRequest.rawValue == 0x34)
    }

    @Test("DeviceStatePayload decodes hostTopAppPackage/hostTopAppLabel and stays backward compatible")
    func deviceStateHostTopAppBackwardCompat() throws {
        // Legacy v1.1 JSON without hostTopApp* fields
        let legacyJSON = Data(#"{"deviceName":"Xiaomi 15","topAppPackage":"com.tencent.mm","topAppLabel":"微信"}"#.utf8)
        let legacy = try DeviceStatePayload.decode(from: legacyJSON)
        #expect(legacy.topAppPackage == "com.tencent.mm")
        #expect(legacy.hostTopAppPackage == "")
        #expect(legacy.hostTopAppLabel == "")

        // v1.2 JSON with hostTopApp* fields for Handoff
        let v12JSON = Data(#"{"deviceName":"Xiaomi 15","hostTopAppPackage":"com.xingin.xhs","hostTopAppLabel":"小红书"}"#.utf8)
        let v12 = try DeviceStatePayload.decode(from: v12JSON)
        #expect(v12.hostTopAppPackage == "com.xingin.xhs")
        #expect(v12.hostTopAppLabel == "小红书")
    }

    @Test("Notification, Media, FileOp, and APK JSON payloads roundtrip through wire stream decoder")
    func companionJsonFramesWireRoundtrip() throws {
        // 0x19 NOTIFICATION_EVENT (snapshot & removed)
        let notifEvent = NotificationEventPayload(
            action: .posted,
            items: [
                RemoteNotificationItem(
                    key: "0|com.tencent.mm|1001|null|10234",
                    pkg: "com.tencent.mm",
                    appLabel: "微信",
                    title: "张三",
                    text: "晚上一起吃饭吗？",
                    postTimeMs: 1_764_200_000_000,
                    clearable: true,
                    canReply: true,
                    replyLabel: "回复",
                    otpCode: ""
                )
            ]
        )
        let notifRemovedJSON = Data(#"{"action":"removed","key":"0|com.tencent.mm|1001|null|10234"}"#.utf8)
        let decodedRemoved = try NotificationEventPayload.decode(from: notifRemovedJSON)
        #expect(decodedRemoved.action == .removed)
        #expect(decodedRemoved.key == "0|com.tencent.mm|1001|null|10234")
        #expect(decodedRemoved.items.isEmpty)

        // 0x31 NOTIFICATION_ACTION
        let notifAction = NotificationActionPayload.reply(key: "k1", text: "好的，7点见")
        let notifActionFrame = try OpalCastFrame.notificationAction(notifAction)
        #expect(notifActionFrame.type == .notificationAction)

        // 0x1A MEDIA_STATE
        let tinyPNG = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        let mediaState = MediaStatePayload(
            active: true,
            pkg: "com.netease.cloudmusic",
            appLabel: "网易云音乐",
            title: "晴天",
            artist: "周杰伦",
            album: "叶惠美",
            playing: true,
            positionMs: 42_500,
            durationMs: 269_000,
            artworkBase64: tinyPNG.base64EncodedString()
        )
        #expect(mediaState.artworkData == tinyPNG)

        // 0x32 MEDIA_ACTION
        let seekFrame = try OpalCastFrame.mediaAction(.seek(positionMs: 120_000))
        #expect(seekFrame.type == .mediaAction)

        // 0x33 FILE_OP_REQUEST
        let renameFrame = try OpalCastFrame.fileOpRequest(.rename(requestId: 7, path: "/sdcard/a.txt", newPath: "/sdcard/b.txt"))
        #expect(renameFrame.type == .fileOpRequest)
        let pullReqFrame = try OpalCastFrame.fileOpRequest(.pull(requestId: 8, path: "/sdcard/DCIM/1.jpg", transferId: 99))
        let pullReqJSON = try JSONSerialization.jsonObject(with: pullReqFrame.payload) as? [String: Any]
        #expect(pullReqJSON?["requestId"] as? Int == 8)
        #expect(pullReqJSON?["op"] as? String == "pull")
        #expect(pullReqJSON?["path"] as? String == "/sdcard/DCIM/1.jpg")
        #expect(pullReqJSON?["transferId"] as? Int == 99)

        // 0x1B FILE_OP_RESPONSE
        let fileResp = FileOpResponsePayload(
            requestId: 9,
            op: "storage_scan",
            ok: true,
            error: "",
            path: "/sdcard",
            entries: [
                RemoteFileEntryPayload(name: "DCIM", path: "/sdcard/DCIM", isDir: true, size: 0, modifiedMs: 12345, mime: "")
            ],
            storage: RemoteStorageSummaryPayload(
                totalBytes: 256_000_000_000,
                freeBytes: 120_000_000_000,
                usedBytes: 136_000_000_000,
                categories: [
                    RemoteStorageCategoryPayload(name: "images", bytes: 40_000_000_000, fileCount: 3200)
                ],
                largeFiles: [
                    RemoteLargeFilePayload(name: "movie.mp4", path: "/sdcard/Movies/movie.mp4", size: 2_000_000_000, modifiedMs: 99999)
                ]
            ),
            totalSize: 0,
            thumbnailBase64: tinyPNG.base64EncodedString()
        )

        // 0x34 APK_INSTALL_REQUEST & 0x1D APK_INSTALL_STATUS
        let apkReq = ApkInstallRequestPayload(
            installId: 3,
            filePath: "@apk_stage/demo.xapk",
            format: .xapk,
            replaceExisting: true,
            allowDowngrade: false
        )
        let apkReqFrame = try OpalCastFrame.apkInstallRequest(apkReq)
        #expect(apkReqFrame.type == .apkInstallRequest)
        #expect(OpalCastApkFormat.infer(fromFilePath: "/tmp/App.APKM") == .apkm)
        #expect(OpalCastApkFormat.infer(fromFilePath: "/tmp/readme.txt") == nil)

        let apkStatus = ApkInstallStatusPayload(
            installId: 3,
            stage: "installing",
            progress: 75,
            packageName: "com.example.demo",
            message: "正在写入分包..."
        )
        #expect(apkStatus.knownStage == .installing)
        #expect(apkStatus.isTerminal == false)

        // Coalesce all frames through OpalCastStreamDecoder
        let wireFrames: [OpalCastFrame] = [
            OpalCastFrame(type: .notificationEvent, payload: try notifEvent.encode()),
            notifActionFrame,
            OpalCastFrame(type: .mediaState, payload: try mediaState.encode()),
            seekFrame,
            renameFrame,
            OpalCastFrame(type: .fileOpResponse, payload: try fileResp.encode()),
            apkReqFrame,
            OpalCastFrame(type: .apkInstallStatus, payload: try apkStatus.encode())
        ]
        var wire = Data()
        for f in wireFrames {
            wire.append(try f.encode())
        }
        var decoder = OpalCastStreamDecoder()
        decoder.append(wire)
        let decodedFrames = try decoder.decodeAvailableFrames()
        #expect(decodedFrames.count == wireFrames.count)

        #expect(try NotificationEventPayload.decode(from: decodedFrames[0].payload) == notifEvent)
        #expect(try NotificationActionPayload.decode(from: decodedFrames[1].payload) == notifAction)
        #expect(try MediaStatePayload.decode(from: decodedFrames[2].payload) == mediaState)
        #expect(try MediaActionPayload.decode(from: decodedFrames[3].payload) == .seek(positionMs: 120_000))
        #expect(try FileOpRequestPayload.decode(from: decodedFrames[4].payload) == .rename(requestId: 7, path: "/sdcard/a.txt", newPath: "/sdcard/b.txt"))
        #expect(try FileOpResponsePayload.decode(from: decodedFrames[5].payload) == fileResp)
        #expect(try ApkInstallRequestPayload.decode(from: decodedFrames[6].payload) == apkReq)
        #expect(try ApkInstallStatusPayload.decode(from: decodedFrames[7].payload) == apkStatus)
    }

    @Test("0x1C FILE_PULL_CHUNK exact 15-byte Big-Endian binary layout matches Android contract")
    func filePullChunkExactWireLayout() throws {
        let name = "photo.jpg"
        let bytes = Data([0xFF, 0xD8, 0xFF, 0xE0])
        let chunk = FilePullChunkPayload(
            transferId: 0x0102_0304,
            chunkFlags: [.start, .data, .end],
            totalSize: 0x0000_0000_0000_0004,
            nameOrPath: name,
            data: bytes
        )

        let raw = try chunk.encode()
        let arr = [UInt8](raw)
        #expect(arr.count == 15 + name.utf8.count + bytes.count)
        // [0..3] transferId BE
        #expect(Array(arr[0..<4]) == [0x01, 0x02, 0x03, 0x04])
        // [4] chunkFlags = START(1) | DATA(2) | END(4) = 0x07
        #expect(arr[4] == 0x07)
        // [5..12] totalSize BE = 4
        #expect(Array(arr[5..<13]) == [0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04])
        // [13..14] nameLen BE = 9
        #expect(Array(arr[13..<15]) == [0x00, 0x09])
        // [15..23] "photo.jpg"
        #expect(String(bytes: arr[15..<24], encoding: .utf8) == name)
        // [24..27] chunk data
        #expect(Array(arr[24..<28]) == [0xFF, 0xD8, 0xFF, 0xE0])

        let decoded = try FilePullChunkPayload.decode(from: raw)
        #expect(decoded == chunk)

        // ABORT carries optional UTF-8 error message in trailing bytes
        let abortChunk = FilePullChunkPayload(
            transferId: 42,
            chunkFlags: .abort,
            totalSize: 0,
            nameOrPath: "",
            data: Data("I/O read failed".utf8)
        )
        let decodedAbort = try FilePullChunkPayload.decode(from: try abortChunk.encode())
        #expect(decodedAbort.chunkFlags == .abort)
        #expect(decodedAbort.abortErrorMessage == "I/O read failed")

        // Truncated header (< 15 bytes) throws truncatedPayload
        #expect(throws: OpalCastCodecError.truncatedPayload(expected: 15, actual: 13)) {
            _ = try FilePullChunkPayload.decode(from: Data(repeating: 0, count: 13))
        }
    }

    @Test("MediaRemoteBridge syncs NowPlaying metadata, artwork, and routes remote commands")
    func mediaRemoteBridgeSyncAndCommands() {
        let backend = MockNowPlayingBackend()
        let bridge = MediaRemoteBridge(backend: backend)
        let receivedActions = OSAllocatedUnfairLock<[MediaActionPayload]>(initialState: [])

        bridge.start { action in
            receivedActions.withLock { $0.append(action) }
        }
        #expect(backend.state.withLock { $0.registerCount } == 1)

        let pngBytes = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        let playingState = MediaStatePayload(
            active: true,
            pkg: "com.spotify.music",
            appLabel: "Spotify",
            title: "Bohemian Rhapsody",
            artist: "Queen",
            album: "A Night at the Opera",
            playing: true,
            positionMs: 12_000,
            durationMs: 354_000,
            artworkBase64: pngBytes.base64EncodedString()
        )
        bridge.applyRemoteMediaState(playingState)

        let updates = backend.state.withLock { $0.updates }
        #expect(updates.count == 1)
        #expect(updates[0].state == playingState)
        #expect(updates[0].artworkData == pngBytes)
        #expect(bridge.currentState == playingState)

        // Simulate macOS hardware media keys / Control Center commands
        backend.simulateCommand(.toggle())
        backend.simulateCommand(.next())
        backend.simulateCommand(.seek(positionMs: 65_000))
        #expect(receivedActions.withLock { $0 } == [.toggle(), .next(), .seek(positionMs: 65_000)])

        // Inactive media state clears NowPlaying
        bridge.applyRemoteMediaState(MediaStatePayload(active: false))
        #expect(bridge.currentState == nil)
        #expect(backend.state.withLock { $0.clearCount } == 1)

        // Disabling suppresses further commands
        bridge.isEnabled = false
        backend.simulateCommand(.play())
        #expect(receivedActions.withLock { $0.count } == 3)

        // Stop unregisters remote commands
        bridge.stop()
        #expect(backend.state.withLock { $0.unregisterCount } == 1)
    }

    @Test("CompanionConnectionManager auto-connects trusted devices, prevents reconnect storms, and handles handoff/clipboard")
    func companionManagerAutoConnectBackoffAndDispatch() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-companion-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let trustStore = TrustStore(storageURL: tempDir.appendingPathComponent("trust.json"))
        let fp1 = String(repeating: "a1", count: 32)
        let fp2 = String(repeating: "b2", count: 32)
        let untrustedFp = String(repeating: "c3", count: 32)

        try trustStore.upsert(TrustedDevice(
            id: "dev-1",
            deviceName: "Xiaomi 15 Pro",
            model: "24129PN74C",
            certFingerprint: fp1,
            authToken: "perm_token_1"
        ))
        try trustStore.upsert(TrustedDevice(
            id: "dev-2",
            deviceName: "Pixel 9",
            model: "Pixel 9",
            certFingerprint: fp2,
            authToken: "perm_token_2"
        ))

        let transport = MockCompanionTransport()
        let clock = MutableClock()
        let mediaBackend = MockNowPlayingBackend()
        let notifBridge = NotificationBridgeManager(notificationCenter: NoopLocalNotificationCenter())
        let mediaBridge = MediaRemoteBridge(backend: mediaBackend)

        let topApps = OSAllocatedUnfairLock<[HostTopAppState]>(initialState: [])
        let receivedClipTexts = OSAllocatedUnfairLock<[(Int64, String)]>(initialState: [])
        let receivedApps = OSAllocatedUnfairLock<[[RemoteAppInfo]]>(initialState: [])

        let manager = CompanionConnectionManager(
            trustStore: trustStore,
            transport: transport,
            notificationBridge: notifBridge,
            mediaBridge: mediaBridge,
            clientName: "TestMac",
            baseBackoffSeconds: 5.0,
            maxBackoffSeconds: 60.0,
            nowProvider: { clock.now() },
            callbacks: CompanionConnectionManager.Callbacks(
                onHostTopAppChanged: { state in topApps.withLock { $0.append(state) } },
                onAppListReceived: { apps in receivedApps.withLock { $0.append(apps) } },
                onClipboardTextReceived: { seq, text in receivedClipTexts.withLock { $0.append((seq, text)) } }
            )
        )

        let untrustedBonjour = DiscoveredBonjourDevice(
            serviceName: "StrangerPhone",
            domain: "local.",
            displayName: "Stranger",
            model: "Unknown",
            fingerprintPrefix: String(untrustedFp.prefix(16)),
            protocolVersion: "1"
        )
        let bonjour1 = DiscoveredBonjourDevice(
            serviceName: "Xiaomi15",
            domain: "local.",
            displayName: "Xiaomi 15 Pro",
            model: "24129PN74C",
            fingerprintPrefix: String(fp1.prefix(16)),
            protocolVersion: "1"
        )
        let bonjour2 = DiscoveredBonjourDevice(
            serviceName: "Pixel9",
            domain: "local.",
            displayName: "Pixel 9",
            model: "Pixel 9",
            fingerprintPrefix: String(fp2.prefix(16)),
            protocolVersion: "1"
        )

        // Untrusted device alone does not trigger auto-connect
        manager.updateDiscoveredDevices([untrustedBonjour])
        #expect(transport.state.withLock { $0.connectHistory.count } == 0)

        // Trusted device triggers silent auto-connect
        manager.updateDiscoveredDevices([untrustedBonjour, bonjour1])
        #expect(transport.state.withLock { $0.connectHistory.count } == 1)
        #expect(manager.connectionState == .connecting(deviceName: "Xiaomi 15 Pro"))

        // Transport becomes connected -> sends lightweight companion HELLO
        transport.emit(.verifiedPeerFingerprint(fp1))
        transport.emit(.stateChanged(.connected))
        #expect(manager.connectionState == .authenticating(deviceName: "Xiaomi 15 Pro"))

        let helloFrame = try #require(transport.sentFramesSnapshot().first)
        #expect(helloFrame.type == .hello)
        let helloJSON = try #require(JSONSerialization.jsonObject(with: helloFrame.payload) as? [String: Any])
        #expect(helloJSON["sessionRole"] as? String == "companion")
        #expect(helloJSON["companionEvents"] as? Bool == true)
        #expect(helloJSON["authToken"] as? String == "perm_token_1")
        #expect(helloJSON["width"] as? Int == 0)
        #expect(helloJSON["audioEnabled"] as? Bool == false)

        // Android responds AUTH_RESULT ok=true -> connected + automatic notification sync
        let authData = try makeAuthResultBytes(ok: true, authToken: "perm_token_1_rotated", deviceName: "Xiaomi 15 Pro", certFingerprint: fp1)
        transport.emit(.frame(OpalCastFrame(type: .authResult, payload: authData)))
        #expect(manager.isAuthenticated == true)
        #expect(manager.connectionState == .connected(deviceName: "Xiaomi 15 Pro"))
        #expect(trustStore.find(byFingerprint: fp1)?.authToken == "perm_token_1_rotated")

        let sentAfterAuth = transport.sentFramesSnapshot()
        #expect(sentAfterAuth.count == 2)
        #expect(sentAfterAuth[1].type == .notificationAction)
        #expect(try NotificationActionPayload.decode(from: sentAfterAuth[1].payload) == .sync())

        // Host top app handoff deduplicates unchanged values and emits on change
        let ds1 = Data(#"{"deviceName":"Xiaomi 15 Pro","hostTopAppPackage":"com.tencent.mm","hostTopAppLabel":"微信"}"#.utf8)
        transport.emit(.frame(OpalCastFrame(type: .deviceState, payload: ds1)))
        transport.emit(.frame(OpalCastFrame(type: .deviceState, payload: ds1)))
        #expect(topApps.withLock { $0 } == [HostTopAppState(packageName: "com.tencent.mm", appLabel: "微信")])

        // Clipboard sync works while main desktop session is inactive
        transport.clearSentFrames()
        var clipPayload = Data()
        var seqBE = Int64(10).bigEndian
        withUnsafeBytes(of: &seqBE) { clipPayload.append(contentsOf: $0) }
        clipPayload.append(Data("hello from phone".utf8))
        transport.emit(.frame(OpalCastFrame(type: .clipboardPush, payload: clipPayload)))
        #expect(receivedClipTexts.withLock { $0.count } == 1)
        #expect(receivedClipTexts.withLock { $0[0].1 } == "hello from phone")

        // Echo back of identical text is suppressed
        #expect(manager.sendClipboardText(seq: 1, text: "hello from phone") == false)
        // New Mac text succeeds
        #expect(manager.sendClipboardText(seq: 2, text: "hello from mac") == true)
        #expect(transport.sentFramesSnapshot().last?.type == .inputText)

        // When main desktop session is active, companion suppresses BOTH inbound and outbound clipboard sync
        manager.setMainSessionActive(true)
        var clipPayload2 = Data()
        var seqBE2 = Int64(11).bigEndian
        withUnsafeBytes(of: &seqBE2) { clipPayload2.append(contentsOf: $0) }
        clipPayload2.append(Data("ignored while casting".utf8))
        transport.emit(.frame(OpalCastFrame(type: .clipboardPush, payload: clipPayload2)))
        #expect(receivedClipTexts.withLock { $0.count } == 1)
        #expect(manager.sendClipboardText(seq: 3, text: "mac while casting") == false)
        manager.setMainSessionActive(false)

        // Media state & notification event dispatch
        let media = MediaStatePayload(active: true, title: "Track 1", artist: "Artist", playing: true)
        transport.emit(.frame(OpalCastFrame(type: .mediaState, payload: try media.encode())))
        #expect(mediaBridge.currentState == media)

        // Switching target device connects to Pixel 9
        #expect(manager.switchTargetDevice(to: bonjour2) == true)
        #expect(manager.preferredDeviceFingerprint == fp2)
        #expect(transport.state.withLock { $0.connectHistory.count } == 2)

        // Simulate failure -> anti-reconnect-storm backoff suppresses immediate retries
        transport.emit(.stateChanged(.failed("network down")))
        #expect(manager.connectionState == .failed("network down"))
        // Immediately re-receiving Bonjour updates must NOT reconnect during 5s backoff
        manager.updateDiscoveredDevices([bonjour2])
        #expect(transport.state.withLock { $0.connectHistory.count } == 2)

        // Advance clock past 5s backoff -> reconnects once
        clock.advance(by: 6.0)
        manager.updateDiscoveredDevices([bonjour2])
        #expect(transport.state.withLock { $0.connectHistory.count } == 3)

        // Auth rejection suppresses auto-reconnect until token rotates
        transport.emit(.stateChanged(.connected))
        let rejectedAuth = try makeAuthResultBytes(ok: false, authToken: "", deviceName: "Pixel 9", certFingerprint: fp2)
        transport.emit(.frame(OpalCastFrame(type: .authResult, payload: rejectedAuth)))
        clock.advance(by: 600.0)
        manager.updateDiscoveredDevices([bonjour2])
        #expect(transport.state.withLock { $0.connectHistory.count } == 3)

        // Manual disconnect suppresses auto-connect until re-enabled
        manager.disconnect()
        #expect(manager.connectionState == .disconnected)
        manager.updateDiscoveredDevices([bonjour1])
        #expect(transport.state.withLock { $0.connectHistory.count } == 3)

        manager.setAutoConnectEnabled(true)
        #expect(transport.state.withLock { $0.connectHistory.count } == 4)

        // Verify APP_LIST frame routing
        let appListData = Data(#"{"apps":[{"pkg":"com.tencent.mm","label":"微信"}]}"#.utf8)
        transport.emit(.frame(OpalCastFrame(type: .appList, payload: appListData)))
        #expect(receivedApps.withLock { $0.first?.first?.pkg } == "com.tencent.mm")
    }

    @Test("CompanionConnectionManager externalFrameRouter prioritizes primary channel and preserves subcomponents when main is active")
    func companionExternalFrameRouterDualChannelPreservation() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-dual-router-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let trustStore = TrustStore(storageURL: tempDir.appendingPathComponent("trust.json"))
        let transport = MockCompanionTransport()
        let mediaBackend = MockNowPlayingBackend()
        let mediaBridge = MediaRemoteBridge(backend: mediaBackend)
        let manager = CompanionConnectionManager(
            trustStore: trustStore,
            transport: transport,
            notificationBridge: NotificationBridgeManager(notificationCenter: NoopLocalNotificationCenter()),
            mediaBridge: mediaBridge
        )

        let routedFrames = OSAllocatedUnfairLock<[OpalCastFrame]>(initialState: [])
        manager.setExternalFrameRouter { frame in
            routedFrames.withLock { $0.append(frame) }
            return true
        }

        // Even while companion transport is idle, control actions route through external primary channel.
        #expect(manager.sendNotificationAction(.clearAll()) == true)
        #expect(manager.sendMediaAction(.next()) == true)
        let captured = routedFrames.withLock { $0 }
        #expect(captured.count == 2)
        #expect(captured[0].type == .notificationAction)
        #expect(captured[1].type == .mediaAction)

        // When main desktop session is active, companion disconnect must NOT clear active NowPlaying state.
        let activeMedia = MediaStatePayload(active: true, title: "Active Song", playing: true)
        mediaBridge.applyRemoteMediaState(activeMedia)
        manager.setMainSessionActive(true)
        manager.disconnect()
        #expect(mediaBridge.currentState == activeMedia)
    }

    private func makeAuthResultBytes(
        ok: Bool,
        authToken: String,
        deviceName: String,
        certFingerprint: String
    ) throws -> Data {
        struct WireAuth: Encodable {
            let ok: Bool
            let authToken: String
            let permanent: Bool
            let message: String
            let deviceName: String
            let certFingerprint: String
        }
        let json = try JSONEncoder().encode(WireAuth(
            ok: ok,
            authToken: authToken,
            permanent: !authToken.isEmpty,
            message: ok ? "ok" : "revoked",
            deviceName: deviceName,
            certFingerprint: certFingerprint
        ))
        var out = Data([ok ? 1 : 0])
        out.append(json)
        return out
    }
}
