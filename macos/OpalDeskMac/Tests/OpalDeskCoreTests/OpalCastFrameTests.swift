import Foundation
import Testing
@testable import OpalDeskCore

@Suite("OpalCast v1 Wire Protocol & Stream Decoder")
struct OpalCastFrameTests {

    @Test("18-byte frame header exact Big-Endian wire layout")
    func frameHeaderExactBytes() throws {
        let payload = Data([0xDE, 0xAD, 0xBE, 0xEF])
        let frame = OpalCastFrame(
            type: .videoFrame,
            flags: OpalCastVideoFrameFlags([.keyframe, .configInline]).rawValue,
            timestampUs: 0x0102_0304_0506_0708,
            payload: payload
        )

        let encoded = try frame.encode()
        #expect(encoded.count == 18 + 4)

        let bytes = [UInt8](encoded)
        // [0..3] Magic "OPD1"
        #expect(Array(bytes[0..<4]) == [0x4F, 0x50, 0x44, 0x31])
        // [4] type = 0x02
        #expect(bytes[4] == 0x02)
        // [5] flags = 0x03
        #expect(bytes[5] == 0x03)
        // [6..9] payloadLen = 4 BE
        #expect(Array(bytes[6..<10]) == [0x00, 0x00, 0x00, 0x04])
        // [10..17] timestampUs BE
        #expect(Array(bytes[10..<18]) == [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])
        // [18..21] payload
        #expect(Array(bytes[18..<22]) == [0xDE, 0xAD, 0xBE, 0xEF])
    }

    @Test("Stream decoder handles 1-byte fragmentation and sticky packets")
    func streamingFragmentationAndStickyPackets() throws {
        let f1 = OpalCastFrame.ping(0x1122_3344_5566_7788)
        let f2 = OpalCastFrame.requestIdr()
        let f3 = OpalCastFrame.systemAction(SystemActionPayload(action: .toggleBlackout, arg: 1))
        let f4 = OpalCastFrame(
            type: .clipboardPush,
            timestampUs: 987_654_321,
            payload: ClipboardPushPayload(seq: 42, text: "你好 OpalDesk 🚀").encode()
        )

        var wire = Data()
        for f in [f1, f2, f3, f4] {
            wire.append(try f.encode())
        }

        // Case 1: 1-byte-at-a-time fragmented delivery
        var fragDecoder = OpalCastStreamDecoder(initialCapacity: 32)
        var decodedFrag: [OpalCastFrame] = []
        for byte in wire {
            fragDecoder.append(Data([byte]))
            decodedFrag.append(contentsOf: try fragDecoder.decodeAvailableFrames())
        }
        #expect(decodedFrag == [f1, f2, f3, f4])
        #expect(fragDecoder.bufferedByteCount == 0)

        // Case 2: All 4 frames coalesced in one sticky TCP packet
        var stickyDecoder = OpalCastStreamDecoder()
        stickyDecoder.append(wire)
        let decodedSticky = try stickyDecoder.decodeAvailableFrames()
        #expect(decodedSticky == [f1, f2, f3, f4])
        #expect(stickyDecoder.bufferedByteCount == 0)
    }

    @Test("Stream decoder rejects invalid magic, unknown frame type, and oversized payload")
    func streamDecoderRejectsInvalidHeaders() {
        // Bad magic
        var badMagic = Data([0x00, 0x00, 0x00, 0x00])
        badMagic.append(Data(repeating: 0, count: 14))
        var d1 = OpalCastStreamDecoder()
        d1.append(badMagic)
        #expect(throws: OpalCastCodecError.invalidMagic([0, 0, 0, 0])) {
            _ = try d1.decodeAvailableFrames()
        }

        // Unknown frame type 0xFE
        var badType = Data([0x4F, 0x50, 0x44, 0x31, 0xFE, 0x00])
        badType.append(Data(repeating: 0, count: 12))
        var d2 = OpalCastStreamDecoder()
        d2.append(badType)
        #expect(throws: OpalCastCodecError.unknownFrameType(0xFE)) {
            _ = try d2.decodeAvailableFrames()
        }

        // Payload > 16 MiB
        let tooBigLen: UInt32 = OpalCastProtocol.maxPayloadLength + 1
        var tooBig = Data([0x4F, 0x50, 0x44, 0x31, 0x02, 0x00])
        var lenBE = tooBigLen.bigEndian
        withUnsafeBytes(of: &lenBE) { tooBig.append(contentsOf: $0) }
        tooBig.append(Data(repeating: 0, count: 8))
        var d3 = OpalCastStreamDecoder()
        d3.append(tooBig)
        #expect(throws: OpalCastCodecError.payloadTooLarge(tooBigLen)) {
            _ = try d3.decodeAvailableFrames()
        }
    }

    @Test("IME_REQUEST 0x16 from Android decodes instead of aborting the session")
    func imeRequestFrameDecodes() throws {
        let payload = Data(#"{"show":true,"inputType":1}"#.utf8)
        var wire = Data([0x4F, 0x50, 0x44, 0x31, 0x16, 0x00])
        var lenBE = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: &lenBE) { wire.append(contentsOf: $0) }
        wire.append(Data(repeating: 0, count: 8))
        wire.append(payload)

        var decoder = OpalCastStreamDecoder()
        decoder.append(wire)
        let frames = try decoder.decodeAvailableFrames()
        #expect(frames.map(\.type) == [.imeRequest])
        #expect(frames.first?.payload == payload)
    }

    @Test("All downlink and uplink payloads roundtrip cleanly")
    func allPayloadsRoundtrip() throws {
        // 0x01 VIDEO_CONFIG
        let vc = VideoConfigPayload(
            codec: .hevc,
            width: 2560,
            height: 1440,
            dpi: 280,
            rotation: 1,
            fpsX100: 6000,
            displayMode: .dex,
            csd: Data([0x00, 0x00, 0x00, 0x01, 0x40, 0x01])
        )
        #expect(try VideoConfigPayload.decode(from: vc.encode()) == vc)

        // 0x03 AUDIO_CONFIG
        let ac = AudioConfigPayload(
            codec: .aacLC,
            sampleRate: 48_000,
            channels: 2,
            codecSpecificData: Data([0x11, 0x90])
        )
        #expect(try AudioConfigPayload.decode(from: ac.encode()) == ac)

        // 0x10 DEVICE_STATE
        let ds = DeviceStatePayload(
            deviceName: "Xiaomi 15 Pro",
            sinkType: 2,
            displayMode: 0,
            width: 1920,
            height: 1080,
            dpi: 240,
            fps: 60.0,
            rotation: 0,
            bitrateKbps: 16000,
            batteryPct: 87,
            blackout: true,
            audioEnabled: true
        )
        #expect(try DeviceStatePayload.decode(from: try ds.encode()) == ds)

        // 0x11 CLIPBOARD_PUSH
        let cp = ClipboardPushPayload(seq: 101, text: "OpalCast clipboard 中文")
        #expect(try ClipboardPushPayload.decode(from: cp.encode()) == cp)

        // 0x12 AUTH_RESULT (prefix uint8 ok + UTF-8 JSON)
        let ar = AuthResultPayload(
            ok: true,
            authToken: "tok_abc123",
            message: "paired",
            deviceName: "Pixel 9",
            certFingerprint: String(repeating: "a", count: 64)
        )
        let arBytes = try ar.encode()
        #expect(arBytes.first == 1)
        #expect(try AuthResultPayload.decode(from: arBytes) == ar)

        // 0x13 FILE_TRANSFER_ACK
        let ack = FileTransferAckPayload(transferId: 9, status: .ok, pathOrError: "/sdcard/Download/OpalDesk/demo.pdf")
        #expect(try FileTransferAckPayload.decode(from: ack.encode()) == ack)

        // 0x1F / 0x2F PING/PONG
        let ping = PingPongPayload(pingId: 0xCAFE_BABE_1234_5678)
        #expect(try PingPongPayload.decode(from: ping.encode()) == ping)

        // 0x20 HELLO
        let hello = HelloPayload(
            protocolVersion: 1,
            clientName: "MacBook Pro",
            clientId: "client-uuid-1",
            preferredCodec: "hevc",
            width: 1920,
            height: 1080,
            dpi: 240,
            fps: 60,
            bitrateKbps: 16000,
            displayMode: 0,
            audioEnabled: true,
            authToken: "tok_abc",
            pinCode: "123456"
        )
        #expect(try HelloPayload.decode(from: try hello.encode()) == hello)

        // 0x21 RESIZE_CONFIG
        let resize = ResizeConfigPayload(
            width: 2560,
            height: 1080,
            dpi: 240,
            fpsX100: 6000,
            bitrateKbps: 20000,
            displayMode: ResizeConfigPayload.unchangedByte,
            rotation: 0
        )
        let resizeBytes = resize.encode()
        #expect(resizeBytes.count == 14)
        #expect(try ResizeConfigPayload.decode(from: resizeBytes) == resize)

        // 0x23 INPUT_MOUSE
        let mouse = InputMousePayload(
            action: .scroll,
            x: 640.5,
            y: 360.25,
            buttons: [.primary, .secondary],
            actionButton: 1,
            hScroll: -1.5,
            vScroll: 2.25
        )
        let mouseBytes = mouse.encode()
        #expect(mouseBytes.count == 25)
        #expect(try InputMousePayload.decode(from: mouseBytes) == mouse)

        // 0x24 INPUT_TOUCH
        let touch = InputTouchPayload(
            action: .move,
            actionIndex: 1,
            pointers: [
                TouchPointer(pointerId: 1, x: 500.0, y: 400.0, pressure: 0.9),
                TouchPointer(pointerId: 2, x: 780.0, y: 400.0, pressure: 0.85)
            ]
        )
        let touchBytes = touch.encode()
        #expect(touchBytes.count == 3 + 2 * 16)
        #expect(try InputTouchPayload.decode(from: touchBytes) == touch)

        // 0x25 INPUT_KEY
        let key = InputKeyPayload(action: .down, androidKeyCode: 31, metaState: AndroidMetaState.ctrlOn, repeatCount: 0)
        let keyBytes = key.encode()
        #expect(keyBytes.count == 13)
        #expect(try InputKeyPayload.decode(from: keyBytes) == key)

        // 0x26 INPUT_TEXT
        let text = InputTextPayload(mode: .commitTextPaste, seq: 77, text: "中文输入法测试")
        #expect(try InputTextPayload.decode(from: text.encode()) == text)

        // 0x27 SYSTEM_ACTION
        let sys = SystemActionPayload(action: .setAudioEnabled, arg: 1)
        let sysBytes = sys.encode()
        #expect(sysBytes.count == 5)
        #expect(try SystemActionPayload.decode(from: sysBytes) == sys)

        // 0x28 FILE_TRANSFER_CHUNK (START + DATA + END)
        let startChunk = FileTransferChunkPayload(
            transferId: 5,
            chunkFlags: .start,
            fileName: "测试报告.pdf",
            totalSize: 1_048_576
        )
        #expect(try FileTransferChunkPayload.decode(from: try startChunk.encode()) == startChunk)

        let dataChunk = FileTransferChunkPayload(
            transferId: 5,
            chunkFlags: .data,
            data: Data([1, 2, 3, 4, 5, 6, 7, 8])
        )
        #expect(try FileTransferChunkPayload.decode(from: try dataChunk.encode()) == dataChunk)

        let endChunk = FileTransferChunkPayload(transferId: 5, chunkFlags: .end)
        #expect(try FileTransferChunkPayload.decode(from: try endChunk.encode()) == endChunk)
    }

    @Test("FileTransferChunkPayload throws fieldTooLong when UTF-8 filename exceeds 65535 bytes")
    func fileTransferChunkRejectsOversizedFileName() {
        let oversizedName = String(repeating: "a", count: OpalCastProtocol.maxFileNameUTF8Bytes + 1)
        let chunk = FileTransferChunkPayload(
            transferId: 99,
            chunkFlags: .start,
            fileName: oversizedName,
            totalSize: 10
        )
        #expect(throws: OpalCastCodecError.fieldTooLong(
            field: "fileTransferChunk.fileName",
            length: OpalCastProtocol.maxFileNameUTF8Bytes + 1,
            maxLength: OpalCastProtocol.maxFileNameUTF8Bytes
        )) {
            _ = try chunk.encode()
        }
    }
}
