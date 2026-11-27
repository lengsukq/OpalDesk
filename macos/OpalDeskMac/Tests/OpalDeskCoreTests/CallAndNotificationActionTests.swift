import Foundation
import os
import Testing
@testable import OpalDeskCore

// MARK: - Test Double for Companion Transport

private final class CallTestCompanionTransport: CompanionSessionTransport, Sendable {
    struct MutableState {
        var sessionState: OpalCastSessionState = .idle
        var verifiedFp: String?
        var sentFrames: [OpalCastFrame] = []
        var eventHandler: (@Sendable (OpalCastSessionEvent) -> Void)?
    }

    let state = OSAllocatedUnfairLock(initialState: MutableState())

    var currentState: OpalCastSessionState { state.withLock { $0.sessionState } }
    var verifiedPeerFingerprint: String? { state.withLock { $0.verifiedFp } }

    func connect(to endpoint: OpalCastEndpoint, onEvent: @escaping @Sendable (OpalCastSessionEvent) -> Void) {
        state.withLock {
            $0.sessionState = .connecting
            $0.eventHandler = onEvent
        }
        onEvent(.stateChanged(.connecting))
    }

    func disconnect() {
        state.withLock {
            $0.sessionState = .disconnected
            $0.eventHandler = nil
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
        }
        let handler = state.withLock { $0.eventHandler }
        handler?(event)
    }

    func sentFrames() -> [OpalCastFrame] {
        state.withLock { $0.sentFrames }
    }

    func clearSentFrames() {
        state.withLock { $0.sentFrames.removeAll() }
    }
}

@Suite("OPD1 v1.3 Call Bridge, Notification Actions & Storage Breakdown")
struct CallAndNotificationActionTests {

    @Test("OPD1 v1.3 frame types 0x1E (callState) and 0x35 (callAction) roundtrip cleanly through wire decoder")
    func callFrameTypesAndPayloadWireRoundtrip() throws {
        #expect(OpalCastFrameType.callState.rawValue == 0x1E)
        #expect(OpalCastFrameType.callAction.rawValue == 0x35)

        let incomingCall = CallStatePayload(
            state: .ringing,
            number: "+8613800138000",
            contactName: "林晓",
            startedAtMs: 1_764_200_123_456,
            isMuted: false,
            isSpeakerOn: true,
            canAnswer: true,
            canReject: true,
            canDial: true,
            source: "telecom"
        )
        #expect(incomingCall.isActive == true)
        #expect(incomingCall.displayCaller == "林晓")

        let dialAction = CallActionPayload.dial(number: "10086")
        let smsAction = CallActionPayload.rejectSms(text: "正在开会，稍后回电")

        let frames: [OpalCastFrame] = [
            OpalCastFrame(type: .callState, payload: try incomingCall.encode()),
            try OpalCastFrame.callAction(.answer()),
            try OpalCastFrame.callAction(.reject()),
            try OpalCastFrame.callAction(.hangup()),
            try OpalCastFrame.callAction(.toggleMute()),
            try OpalCastFrame.callAction(.toggleSpeaker()),
            try OpalCastFrame.callAction(dialAction),
            try OpalCastFrame.callAction(smsAction)
        ]

        var wire = Data()
        for f in frames {
            wire.append(try f.encode())
        }

        var decoder = OpalCastStreamDecoder()
        decoder.append(wire)
        let decoded = try decoder.decodeAvailableFrames()
        #expect(decoded.count == frames.count)

        #expect(try CallStatePayload.decode(from: decoded[0].payload) == incomingCall)
        #expect(try CallActionPayload.decode(from: decoded[1].payload) == .answer())
        #expect(try CallActionPayload.decode(from: decoded[2].payload) == .reject())
        #expect(try CallActionPayload.decode(from: decoded[3].payload) == .hangup())
        #expect(try CallActionPayload.decode(from: decoded[4].payload) == .toggleMute())
        #expect(try CallActionPayload.decode(from: decoded[5].payload) == .toggleSpeaker())
        #expect(try CallActionPayload.decode(from: decoded[6].payload) == dialAction)
        #expect(try CallActionPayload.decode(from: decoded[7].payload) == smsAction)

        // Verify exact camelCase wire keys for CALL_ACTION
        let smsJSON = try #require(JSONSerialization.jsonObject(with: decoded[7].payload) as? [String: Any])
        #expect(smsJSON["op"] as? String == "reject_sms")
        #expect(smsJSON["smsText"] as? String == "正在开会，稍后回电")

        // Verify minimal CALL_STATE JSON defaults gracefully
        let minimalCall = try CallStatePayload.decode(from: Data(#"{"state":"offhook","number":"12345"}"#.utf8))
        #expect(minimalCall.state == .offhook)
        #expect(minimalCall.number == "12345")
        #expect(minimalCall.displayCaller == "12345")
        #expect(minimalCall.isMuted == false)
        #expect(minimalCall.canAnswer == false)
    }

    @Test("RemoteNotificationItem.actions stays backward compatible and NotificationActionPayload.invokeAction encodes exact JSON")
    func notificationActionsBackwardCompatAndInvoke() throws {
        // Legacy OPD1 v1.2 JSON without `actions` array
        let legacyJSON = Data(#"{"key":"k-old","pkg":"com.demo","title":"Hello","text":"World"}"#.utf8)
        let legacyItem = try JSONDecoder().decode(RemoteNotificationItem.self, from: legacyJSON)
        #expect(legacyItem.key == "k-old")
        #expect(legacyItem.actions.isEmpty)

        // OPD1 v1.3 JSON with interactive `actions`
        let v13JSON = Data("""
        {
          "key": "k-new",
          "pkg": "com.tencent.mm",
          "appLabel": "微信",
          "title": "王敏",
          "text": "收到文件了",
          "actions": [
            {"index": 0, "label": "标为已读"},
            {"index": 1, "label": "点赞"}
          ]
        }
        """.utf8)
        let v13Item = try JSONDecoder().decode(RemoteNotificationItem.self, from: v13JSON)
        #expect(v13Item.actions == [
            RemoteNotificationAction(index: 0, label: "标为已读"),
            RemoteNotificationAction(index: 1, label: "点赞")
        ])

        // NotificationActionPayload.invokeAction wire JSON
        let payload = NotificationActionPayload.invokeAction(key: "k-new", actionIndex: 1)
        let encoded = try payload.encode()
        let obj = try #require(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        #expect(obj["op"] as? String == "invoke_action")
        #expect(obj["key"] as? String == "k-new")
        #expect(obj["actionIndex"] as? Int == 1)
        #expect(try NotificationActionPayload.decode(from: encoded) == payload)

        // NotificationBridgeManager.invokeAction emits 0x31 NOTIFICATION_ACTION
        let sent = OSAllocatedUnfairLock<[OpalCastFrame]>(initialState: [])
        let manager = NotificationBridgeManager(
            notificationCenter: NoopLocalNotificationCenter(),
            sendFrame: { frame in
                sent.withLock { $0.append(frame) }
                return true
            }
        )

        #expect(manager.invokeAction(key: "", actionIndex: 0) == false)
        #expect(manager.invokeAction(key: "k-new", actionIndex: -1) == false)
        #expect(manager.invokeAction(key: "k-new", actionIndex: 0) == true)

        let frames = sent.withLock { $0 }
        #expect(frames.count == 1)
        #expect(frames[0].type == .notificationAction)
        #expect(try NotificationActionPayload.decode(from: frames[0].payload) == .invokeAction(key: "k-new", actionIndex: 0))
    }

    @Test("CallBridgeManager tracks state, emits all 0x35 call actions, and resets to idle on disconnect")
    func callBridgeManagerLifecycleAndActions() throws {
        let sentFrames = OSAllocatedUnfairLock<[OpalCastFrame]>(initialState: [])
        let observedStates = OSAllocatedUnfairLock<[CallStatePayload]>(initialState: [])

        let bridge = CallBridgeManager(
            sendFrame: { frame in
                sentFrames.withLock { $0.append(frame) }
                return true
            },
            onStateChanged: { newState in
                observedStates.withLock { $0.append(newState) }
            }
        )

        #expect(bridge.currentState == .idle)

        let ringing = CallStatePayload(
            state: .ringing,
            number: "13900001111",
            contactName: "产品总监",
            startedAtMs: 1_764_200_500_000,
            canAnswer: true,
            canReject: true,
            source: "telecom"
        )
        bridge.handleCallState(ringing)
        #expect(bridge.currentState == ringing)

        // Emit all actions
        #expect(bridge.answer() == true)
        #expect(bridge.reject() == true)
        #expect(bridge.hangup() == true)
        #expect(bridge.toggleMute() == true)
        #expect(bridge.toggleSpeaker() == true)
        #expect(bridge.dial(number: "  ") == false)
        #expect(bridge.dial(number: " +8613800138000 ") == true)
        #expect(bridge.rejectWithSms(text: "   ") == false)
        #expect(bridge.rejectWithSms(text: " 稍后回复 ") == true)

        let decodedActions = try sentFrames.withLock { $0 }.map { frame -> CallActionPayload in
            #expect(frame.type == .callAction)
            return try CallActionPayload.decode(from: frame.payload)
        }
        #expect(decodedActions == [
            .answer(),
            .reject(),
            .hangup(),
            .toggleMute(),
            .toggleSpeaker(),
            .dial(number: "+8613800138000"),
            .rejectSms(text: "稍后回复")
        ])

        // Disconnect clears active call back to .idle and notifies observer once
        bridge.handleSessionDisconnected()
        #expect(bridge.currentState == .idle)
        bridge.handleSessionDisconnected() // idempotent when already idle

        let history = observedStates.withLock { $0 }
        #expect(history == [ringing, .idle])
    }

    @Test("CompanionConnectionManager routes downlink 0x1E callState and uplink 0x35 callAction")
    func companionManagerCallRouting() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-call-companion-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let trustStore = TrustStore(storageURL: tempDir.appendingPathComponent("trust.json"))
        let fp = String(repeating: "d4", count: 32)
        let trusted = TrustedDevice(
            id: "dev-call",
            deviceName: "Pixel 9 Pro",
            certFingerprint: fp,
            authToken: "tok_call_1"
        )
        try trustStore.upsert(trusted)

        let transport = CallTestCompanionTransport()
        let callEvents = OSAllocatedUnfairLock<[CallStatePayload]>(initialState: [])
        let manager = CompanionConnectionManager(
            trustStore: trustStore,
            transport: transport,
            notificationBridge: NotificationBridgeManager(notificationCenter: NoopLocalNotificationCenter()),
            mediaBridge: MediaRemoteBridge(backend: NoopNowPlayingBackend()),
            callbacks: CompanionConnectionManager.Callbacks(
                onCallStateChanged: { payload in callEvents.withLock { $0.append(payload) } }
            )
        )

        let endpoint = OpalCastEndpoint(target: .hostPort(host: "127.0.0.1", port: 19878), security: .wiredLocalhostPlaintext)
        manager.connect(to: trusted, endpoint: endpoint)
        transport.emit(.stateChanged(.connected))

        // Authenticate
        var authBytes = Data([1])
        authBytes.append(Data(#"{"ok":true,"authToken":"tok_call_1","permanent":true,"deviceName":"Pixel 9 Pro","certFingerprint":"\#(fp)"}"#.utf8))
        transport.emit(.frame(OpalCastFrame(type: .authResult, payload: authBytes)))
        transport.clearSentFrames()

        // Downlink 0x1E -> updates callBridge and callback
        let ringing = CallStatePayload(state: .ringing, number: "10010", contactName: "中国联通", canAnswer: true, canReject: true)
        transport.emit(.frame(OpalCastFrame(type: .callState, payload: try ringing.encode())))
        #expect(manager.callBridge.currentState == ringing)
        #expect(callEvents.withLock { $0 } == [ringing])

        // Uplink 0x35 via callBridge and via companionManager.sendCallAction
        #expect(manager.callBridge.answer() == true)
        #expect(manager.sendCallAction(.toggleMute()) == true)
        #expect(manager.notificationBridge.invokeAction(key: "n-1", actionIndex: 2) == true)

        let sent = transport.sentFrames()
        #expect(sent.count == 3)
        #expect(sent[0].type == .callAction)
        #expect(try CallActionPayload.decode(from: sent[0].payload) == .answer())
        #expect(sent[1].type == .callAction)
        #expect(try CallActionPayload.decode(from: sent[1].payload) == .toggleMute())
        #expect(sent[2].type == .notificationAction)
        #expect(try NotificationActionPayload.decode(from: sent[2].payload) == .invokeAction(key: "n-1", actionIndex: 2))

        // Disconnect clears callBridge to .idle
        manager.disconnect()
        #expect(manager.callBridge.currentState == .idle)
    }

    @Test("RemoteStorageSummaryPayload and RemoteStorageReport decode folders & apps with backward compatibility")
    func storageReportFoldersAndAppsDecoding() throws {
        // Legacy OPD1 v1.2 storage_scan response without folders/apps
        let legacyJSON = Data("""
        {
          "requestId": 1,
          "op": "storage_scan",
          "ok": true,
          "storage": {
            "totalBytes": 256000000000,
            "freeBytes": 100000000000,
            "usedBytes": 156000000000,
            "categories": [{"name": "图片", "bytes": 20000000000, "fileCount": 1500}],
            "largeFiles": []
          }
        }
        """.utf8)
        let legacyResp = try FileOpResponsePayload.decode(from: legacyJSON)
        let legacyStorage = try #require(legacyResp.storage)
        #expect(legacyStorage.folders.isEmpty)
        #expect(legacyStorage.apps.isEmpty)

        // OPD1 v1.3 storage_scan response with folders & apps
        let v13JSON = Data("""
        {
          "requestId": 2,
          "op": "storage_scan",
          "ok": true,
          "storage": {
            "totalBytes": 256000000000,
            "freeBytes": 100000000000,
            "usedBytes": 156000000000,
            "categories": [{"name": "视频", "bytes": 50000000000, "fileCount": 42}],
            "largeFiles": [{"name": "clip.mp4", "path": "/sdcard/Movies/clip.mp4", "size": 4000000000, "modifiedMs": 999}],
            "folders": [
              {"path": "/sdcard/DCIM", "name": "DCIM", "bytes": 32000000000, "fileCount": 2100},
              {"path": "/sdcard/Download", "name": "Download", "bytes": 8500000000, "fileCount": 310}
            ],
            "apps": [
              {"pkg": "com.tencent.mm", "appLabel": "微信", "apkBytes": 300000000, "dataBytes": 25000000000, "cacheBytes": 1200000000, "totalBytes": 26500000000},
              {"pkg": "com.example.tool", "appLabel": "Tool", "apkBytes": 100, "dataBytes": 200, "cacheBytes": 50}
            ]
          }
        }
        """.utf8)
        let v13Resp = try FileOpResponsePayload.decode(from: v13JSON)
        let storage = try #require(v13Resp.storage)
        #expect(storage.folders.count == 2)
        #expect(storage.folders[0] == FolderStorageEntry(path: "/sdcard/DCIM", name: "DCIM", bytes: 32_000_000_000, fileCount: 2100))
        #expect(storage.apps.count == 2)
        #expect(storage.apps[0].id == "com.tencent.mm")
        #expect(storage.apps[0].totalBytes == 26_500_000_000)
        // Missing totalBytes in JSON falls back to apkBytes + dataBytes + cacheBytes
        #expect(storage.apps[1].totalBytes == 350)

        let report = RemoteStorageReport(summary: storage)
        #expect(report.folders == storage.folders)
        #expect(report.apps == storage.apps)
        #expect(report.largeFiles.count == 1)
    }
}
