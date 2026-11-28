import CryptoKit
import Foundation
import Testing
@testable import OpalDeskCore

@Suite("Reed-Solomon GF(2^8) FEC, OPU1 Packet Header & AES-128-GCM UDP Media Recovery")
struct ReedSolomonFecTests {

    @Test("GF(2^8) 0x11D multiplication, inverse, and division identities hold across all non-zero bytes")
    func galoisFieldIdentities() {
        for raw in 1...255 {
            let a = UInt8(raw)
            let inv = ReedSolomonFec.gfInv(a)
            #expect(ReedSolomonFec.gfMul(a, inv) == 1)
            #expect(ReedSolomonFec.gfDiv(a, a) == 1)
            #expect(ReedSolomonFec.gfMul(a, 0) == 0)
            #expect(ReedSolomonFec.gfDiv(0, a) == 0)
        }
    }

    @Test("ReedSolomonFec recovers 100% bit-exact data under multi-shard erasure loss")
    func multiPacketLossBitExactRecovery() throws {
        let dataCount = 10
        let parityCount = 4
        let shardSize = 1200
        let codec = try ReedSolomonFec(dataShards: dataCount, parityShards: parityCount)

        let originalData: [Data] = (0..<dataCount).map { shardIdx in
            Data((0..<shardSize).map { byteIdx in
                UInt8((shardIdx * 73 + byteIdx * 19 + 41) & 0xFF)
            })
        }
        let parityShards = try codec.encodeParity(data: originalData)
        #expect(parityShards.count == parityCount)

        let allShards = originalData + parityShards

        // Drop 4 data shards (maximum recoverable loss = parityCount)
        var dropped4Data: [Data?] = allShards.map { Optional($0) }
        for lost in [0, 3, 6, 9] {
            dropped4Data[lost] = nil
        }
        let recovered4Data = try codec.reconstructDataShards(from: dropped4Data)
        #expect(recovered4Data == originalData)

        // Drop mixed data + parity shards (2 data + 2 parity)
        var droppedMixed: [Data?] = allShards.map { Optional($0) }
        for lost in [1, 8, 10, 13] {
            droppedMixed[lost] = nil
        }
        let recoveredMixed = try codec.reconstructDataShards(from: droppedMixed)
        #expect(recoveredMixed == originalData)

        // Dropping 5 shards (> parityCount) must fail cleanly
        var dropped5: [Data?] = allShards.map { Optional($0) }
        for lost in [0, 2, 4, 6, 8] {
            dropped5[lost] = nil
        }
        #expect(throws: ReedSolomonFecError.insufficientShards(received: 9, required: 10)) {
            _ = try codec.reconstructDataShards(from: dropped5)
        }
    }

    @Test("OPU1 20-byte Big-Endian header round-trip and 0x7F punch packet layout")
    func opu1HeaderAndPunchRoundTrip() throws {
        let header = OpalUdpPacketHeader(
            sessionId: 0x12345678,
            frameSeq: 0x00ABCDEF,
            mediaType: OpalUdpProtocol.mediaTypeVideo,
            flags: OpalUdpProtocol.flagKeyframe | OpalUdpProtocol.flagConfigInline,
            shardIndex: 3,
            dataShards: 8,
            parityShards: 2,
            payloadLen: 1180,
            reserved: 0
        )
        let encoded = header.encode()
        #expect(encoded.count == 20)
        #expect(Array(encoded.prefix(4)) == [0x4F, 0x50, 0x55, 0x31]) // "OPU1"

        let decoded = try OpalUdpPacketHeader.decode(from: encoded)
        #expect(decoded == header)

        let punch = OpalUdpPacketHeader.makePunchPacket(sessionId: 0xCAFEBABE, seq: 7)
        #expect(punch.count == 20)
        let decodedPunch = try OpalUdpPacketHeader.decode(from: punch)
        #expect(decodedPunch.mediaType == OpalUdpProtocol.mediaTypePunch)
        #expect(decodedPunch.sessionId == 0xCAFEBABE)
        #expect(decodedPunch.frameSeq == 7)
        #expect(decodedPunch.payloadLen == 0)
    }

    @Test("OpalUdpFrameAssembler performs 0-RTT FEC recovery and AES-128-GCM decryption")
    func udpAssemblerEndToEndFecAndAesGcm() throws {
        let sessionId: UInt32 = 0xA1B2C3D4
        let frameSeq: UInt32 = 42
        let ptsUs: Int64 = 987_654_321
        let mediaFlags = OpalCastVideoFrameFlags.keyframe.rawValue | OpalCastVideoFrameFlags.configInline.rawValue
        let keyData = Data((0..<16).map { UInt8($0 + 1) })
        let key = SymmetricKey(data: keyData)

        // Build exact :bridge wire plaintext: ptsUs(8B BE) || mediaFlags(1B) || rawFrame
        let rawFrame = Data((0..<1003).map { UInt8(($0 * 13 + 7) & 0xFF) })
        var plainWriter = BinaryWriter(capacity: 9 + rawFrame.count)
        plainWriter.writeInt64(ptsUs)
        plainWriter.writeUInt8(mediaFlags)
        plainWriter.writeBytes(rawFrame)

        let encrypted = try OpalUdpCrypto.encrypt(
            plaintext: plainWriter.data,
            key: key,
            sessionId: sessionId,
            frameSeq: frameSeq,
            mediaType: OpalUdpProtocol.mediaTypeVideo
        )

        // Prefix 4-byte Big-Endian origTotalLen and split into 4 zero-padded shards
        var framedWriter = BinaryWriter(capacity: 4 + encrypted.count)
        framedWriter.writeUInt32(UInt32(encrypted.count))
        framedWriter.writeBytes(encrypted)
        let framed = framedWriter.data
        let shardSize = (framed.count + 3) / 4
        let dataShards: [Data] = (0..<4).map { i in
            let start = i * shardSize
            let end = min(framed.count, start + shardSize)
            var shard = start < end ? Data(framed[start..<end]) : Data()
            if shard.count < shardSize {
                shard.append(Data(repeating: 0, count: shardSize - shard.count))
            }
            return shard
        }
        let codec = try ReedSolomonFec(dataShards: 4, parityShards: 2)
        let parityShards = try codec.encodeParity(data: dataShards)
        let allShards = dataShards + parityShards

        // Build OPU1 datagrams and drop data shards 0 and 2; deliver shards 1, 3, 4 (parity), 5 (parity)
        var assembler = try OpalUdpFrameAssembler(sessionId: sessionId, keyData: keyData)
        var deliveredFrame: OpalCastFrame?
        let arrivalOrder = [1, 4, 3, 5]
        for (step, shardIdx) in arrivalOrder.enumerated() {
            let isParity = shardIdx >= 4
            let flags: UInt8 = isParity
                ? OpalUdpProtocol.flagParity
                : (OpalUdpProtocol.flagKeyframe | OpalUdpProtocol.flagConfigInline)
            let hdr = OpalUdpPacketHeader(
                sessionId: sessionId,
                frameSeq: frameSeq,
                mediaType: OpalUdpProtocol.mediaTypeVideo,
                flags: flags,
                shardIndex: UInt8(shardIdx),
                dataShards: 4,
                parityShards: 2,
                payloadLen: UInt16(allShards[shardIdx].count)
            )
            var datagram = hdr.encode()
            datagram.append(allShards[shardIdx])
            let maybeFrame = try assembler.ingest(datagram: datagram)
            if step < 3 {
                #expect(maybeFrame == nil)
            } else {
                deliveredFrame = maybeFrame
            }
        }

        let frame = try #require(deliveredFrame)
        #expect(frame.type == .videoFrame)
        #expect(frame.flags == mediaFlags)
        #expect(frame.timestampUs == ptsUs)
        #expect(frame.payload == rawFrame)
    }

    @Test("HELLO and AUTH_RESULT optional UDP negotiation fields serialize and decode cleanly")
    func sessionPayloadUdpNegotiationFields() throws {
        let hello = HelloPayload(
            clientName: "Mac",
            clientId: "mac-udp-1",
            bitrateKbps: 0,
            udpMediaPort: 0,
            udpFecPct: 20
        )
        let helloJson = try JSONSerialization.jsonObject(with: try hello.encode()) as? [String: Any]
        #expect(helloJson?["bitrateKbps"] as? Int == 0)
        #expect(helloJson?["udpMediaPort"] as? Int == 0)
        #expect(helloJson?["udpFecPct"] as? Int == 20)

        let keyB64 = Data(repeating: 0x5A, count: 16).base64EncodedString()
        let authJson = """
        {"ok":true,"authToken":"tok","permanent":true,"message":"ok","deviceName":"Phone","certFingerprint":"aa","udpMediaPort":19880,"udpSessionId":305419896,"udpKeyBase64":"\(keyB64)","udpFecPct":25}
        """
        var authBytes = Data([1])
        authBytes.append(Data(authJson.utf8))
        let auth = try AuthResultPayload.decode(from: authBytes)
        #expect(auth.ok == true)
        #expect(auth.udpMediaPort == 19880)
        #expect(auth.udpSessionId == 0x12345678)
        #expect(auth.udpKeyBase64 == keyB64)
        #expect(auth.udpFecPct == 25)
    }
}
