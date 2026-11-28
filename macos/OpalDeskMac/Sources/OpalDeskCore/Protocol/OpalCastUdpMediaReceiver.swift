import CryptoKit
import Foundation
import Network
import os

/// Wire constants for the OpalCast P3 UDP + AES-128-GCM + Reed-Solomon FEC media channel (`OPU1`).
public enum OpalUdpProtocol {
    public static let magic: UInt32 = 0x4F505531 // "OPU1"
    public static let headerSize: Int = 20
    public static let gcmTagSize: Int = 16
    public static let aes128KeySize: Int = 16
    public static let defaultFecPct: Int = 20
    public static let mediaTypeVideo: UInt8 = 0x02
    public static let mediaTypeAudio: UInt8 = 0x04
    public static let mediaTypePunch: UInt8 = 0x7F
    public static let flagKeyframe: UInt8 = 0x01
    public static let flagParity: UInt8 = 0x02
    public static let flagConfigInline: UInt8 = 0x04
}

public enum OpalUdpMediaError: Error, Sendable, Equatable, LocalizedError {
    case truncatedPacket(actual: Int)
    case invalidMagic(UInt32)
    case payloadLengthMismatch(expected: Int, actual: Int)
    case invalidShardMetadata(shardIndex: UInt8, dataShards: UInt8, parityShards: UInt8)
    case invalidKeyLength(Int)
    case invalidCiphertextLength(Int)
    case corruptFramedPayload

    public var errorDescription: String? {
        switch self {
        case .truncatedPacket(let actual): return "OPU1 UDP packet truncated (\(actual) bytes)"
        case .invalidMagic(let magic): return String(format: "Invalid OPU1 magic: 0x%08X", magic)
        case let .payloadLengthMismatch(e, a): return "OPU1 payload length mismatch: header=\(e), actual=\(a)"
        case let .invalidShardMetadata(i, d, p): return "Invalid OPU1 shard metadata: idx=\(i), data=\(d), parity=\(p)"
        case .invalidKeyLength(let len): return "Invalid AES-128-GCM key length: \(len) bytes"
        case .invalidCiphertextLength(let len): return "AES-GCM ciphertext too short (\(len) bytes)"
        case .corruptFramedPayload: return "OPU1 reassembled media payload length header is corrupt"
        }
    }
}

/// Fixed 20-byte Big-Endian `OPU1` UDP datagram header aligned with `:bridge`.
public struct OpalUdpPacketHeader: Sendable, Equatable {
    public var magic: UInt32
    public var sessionId: UInt32
    public var frameSeq: UInt32
    public var mediaType: UInt8
    public var flags: UInt8
    public var shardIndex: UInt8
    public var dataShards: UInt8
    public var parityShards: UInt8
    public var payloadLen: UInt16
    public var reserved: UInt8

    public init(
        magic: UInt32 = OpalUdpProtocol.magic, sessionId: UInt32, frameSeq: UInt32,
        mediaType: UInt8, flags: UInt8 = 0, shardIndex: UInt8 = 0,
        dataShards: UInt8 = 1, parityShards: UInt8 = 0, payloadLen: UInt16 = 0, reserved: UInt8 = 0
    ) {
        self.magic = magic; self.sessionId = sessionId; self.frameSeq = frameSeq
        self.mediaType = mediaType; self.flags = flags; self.shardIndex = shardIndex
        self.dataShards = dataShards; self.parityShards = parityShards
        self.payloadLen = payloadLen; self.reserved = reserved
    }

    public func encode() -> Data {
        var w = BinaryWriter(capacity: OpalUdpProtocol.headerSize)
        w.writeUInt32(magic); w.writeUInt32(sessionId); w.writeUInt32(frameSeq)
        w.writeUInt8(mediaType); w.writeUInt8(flags); w.writeUInt8(shardIndex)
        w.writeUInt8(dataShards); w.writeUInt8(parityShards)
        w.writeUInt16(payloadLen); w.writeUInt8(reserved)
        return w.data
    }

    public static func decode(from data: Data) throws -> OpalUdpPacketHeader {
        guard data.count >= OpalUdpProtocol.headerSize else { throw OpalUdpMediaError.truncatedPacket(actual: data.count) }
        var r = BinaryReader(data)
        let magic = try r.readUInt32()
        guard magic == OpalUdpProtocol.magic else { throw OpalUdpMediaError.invalidMagic(magic) }
        return OpalUdpPacketHeader(
            magic: magic, sessionId: try r.readUInt32(), frameSeq: try r.readUInt32(),
            mediaType: try r.readUInt8(), flags: try r.readUInt8(), shardIndex: try r.readUInt8(),
            dataShards: try r.readUInt8(), parityShards: try r.readUInt8(),
            payloadLen: try r.readUInt16(), reserved: try r.readUInt8()
        )
    }

    public static func makePunchPacket(sessionId: UInt32, seq: UInt32 = 0) -> Data {
        OpalUdpPacketHeader(sessionId: sessionId, frameSeq: seq, mediaType: OpalUdpProtocol.mediaTypePunch).encode()
    }
}

/// AES-128-GCM helpers using the exact 12-byte IV `sessionId(4B) || frameSeq(4B) || mediaType(1B) || 0,0,0`.
public enum OpalUdpCrypto {
    public static func makeNonce(sessionId: UInt32, frameSeq: UInt32, mediaType: UInt8) throws -> AES.GCM.Nonce {
        var w = BinaryWriter(capacity: 12)
        w.writeUInt32(sessionId); w.writeUInt32(frameSeq); w.writeUInt8(mediaType)
        w.writeUInt8(0); w.writeUInt8(0); w.writeUInt8(0)
        return try AES.GCM.Nonce(data: w.data)
    }

    public static func encrypt(
        plaintext: Data, key: SymmetricKey, sessionId: UInt32, frameSeq: UInt32, mediaType: UInt8
    ) throws -> Data {
        let nonce = try makeNonce(sessionId: sessionId, frameSeq: frameSeq, mediaType: mediaType)
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        var out = Data(capacity: sealed.ciphertext.count + sealed.tag.count)
        out.append(sealed.ciphertext); out.append(sealed.tag)
        return out
    }

    public static func decrypt(
        ciphertextWithTag: Data, key: SymmetricKey, sessionId: UInt32, frameSeq: UInt32, mediaType: UInt8
    ) throws -> Data {
        guard ciphertextWithTag.count >= OpalUdpProtocol.gcmTagSize else {
            throw OpalUdpMediaError.invalidCiphertextLength(ciphertextWithTag.count)
        }
        let splitIdx = ciphertextWithTag.endIndex - OpalUdpProtocol.gcmTagSize
        let ciphertext = ciphertextWithTag[ciphertextWithTag.startIndex..<splitIdx]
        let tag = ciphertextWithTag[splitIdx..<ciphertextWithTag.endIndex]
        let nonce = try makeNonce(sessionId: sessionId, frameSeq: frameSeq, mediaType: mediaType)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        return try AES.GCM.open(box, using: key)
    }
}

/// Stateful 0-RTT UDP frame reassembler with Reed-Solomon FEC erasure recovery and AES-128-GCM decryption.
public struct OpalUdpFrameAssembler: Sendable {
    private static let maxInFlightBuckets: Int = 64
    private static let maxCompletedHistory: Int = 256

    private struct BucketKey: Hashable, Sendable {
        let mediaType: UInt8
        let frameSeq: UInt32
    }

    private struct FrameBucket: Sendable {
        let dataShards: Int
        let parityShards: Int
        var frameFlags: UInt8
        var shards: [Data?]
        var receivedCount: Int = 0
        var maxShardLen: Int = 0
    }

    public let sessionId: UInt32
    private let key: SymmetricKey
    private var buckets: [BucketKey: FrameBucket] = [:]
    private var bucketOrder: [BucketKey] = []
    private var completedKeys: Set<BucketKey> = []
    private var completedOrder: [BucketKey] = []
    private var codecCache: [UInt16: ReedSolomonFec] = [:]

    public init(sessionId: UInt32, keyData: Data) throws {
        guard keyData.count == OpalUdpProtocol.aes128KeySize else {
            throw OpalUdpMediaError.invalidKeyLength(keyData.count)
        }
        self.sessionId = sessionId
        self.key = SymmetricKey(data: keyData)
    }

    /// Ingests one raw UDP datagram and returns a decrypted `OpalCastFrame` immediately once
    /// `dataShards` shards have arrived (0-RTT FEC recovery).
    public mutating func ingest(datagram: Data) throws -> OpalCastFrame? {
        let header = try OpalUdpPacketHeader.decode(from: datagram)
        guard header.sessionId == sessionId else { return nil }
        guard header.mediaType == OpalUdpProtocol.mediaTypeVideo
            || header.mediaType == OpalUdpProtocol.mediaTypeAudio
        else {
            return nil
        }

        let payloadEnd = OpalUdpProtocol.headerSize + Int(header.payloadLen)
        guard datagram.count >= payloadEnd else {
            throw OpalUdpMediaError.payloadLengthMismatch(
                expected: Int(header.payloadLen),
                actual: datagram.count - OpalUdpProtocol.headerSize
            )
        }
        let shardPayload = Data(datagram[OpalUdpProtocol.headerSize..<payloadEnd])

        let dataCount = Int(header.dataShards)
        let parityCount = Int(header.parityShards)
        let totalCount = dataCount + parityCount
        let shardIdx = Int(header.shardIndex)
        guard dataCount >= 1, totalCount <= ReedSolomonFec.maxTotalShards, shardIdx < totalCount else {
            throw OpalUdpMediaError.invalidShardMetadata(
                shardIndex: header.shardIndex,
                dataShards: header.dataShards,
                parityShards: header.parityShards
            )
        }

        let bucketKey = BucketKey(mediaType: header.mediaType, frameSeq: header.frameSeq)
        if completedKeys.contains(bucketKey) { return nil }

        // Fast path: unfragmented single-shard frame.
        if dataCount == 1 && parityCount == 0 {
            markCompleted(bucketKey)
            return try decodeFramedPayload(framed: shardPayload, header: header)
        }

        var bucket = buckets[bucketKey] ?? FrameBucket(
            dataShards: dataCount,
            parityShards: parityCount,
            frameFlags: header.flags & ~OpalUdpProtocol.flagParity,
            shards: [Data?](repeating: nil, count: totalCount)
        )
        if buckets[bucketKey] == nil {
            bucketOrder.append(bucketKey)
            evictOldBucketsIfNeeded()
        }
        if bucket.shards[shardIdx] == nil {
            bucket.shards[shardIdx] = shardPayload
            bucket.receivedCount += 1
            if shardPayload.count > bucket.maxShardLen { bucket.maxShardLen = shardPayload.count }
            if (header.flags & OpalUdpProtocol.flagParity) == 0 {
                bucket.frameFlags |= (header.flags & ~OpalUdpProtocol.flagParity)
            }
        }

        guard bucket.receivedCount >= dataCount else {
            buckets[bucketKey] = bucket
            return nil
        }

        buckets.removeValue(forKey: bucketKey)
        markCompleted(bucketKey)

        let normalized: [Data?] = bucket.shards.map { shard in
            guard let shard else { return nil }
            if shard.count == bucket.maxShardLen { return shard }
            var padded = shard
            padded.append(Data(repeating: 0, count: bucket.maxShardLen - shard.count))
            return padded
        }
        let codec = try cachedCodec(dataShards: dataCount, parityShards: parityCount)
        let recovered = try codec.reconstructDataShards(from: normalized)

        var assembled = Data(capacity: dataCount * bucket.maxShardLen)
        for i in 0..<dataCount {
            assembled.append(recovered[i])
        }
        var resolvedHeader = header
        resolvedHeader.flags = bucket.frameFlags
        return try decodeFramedPayload(framed: assembled, header: resolvedHeader)
    }

    private mutating func cachedCodec(dataShards: Int, parityShards: Int) throws -> ReedSolomonFec {
        let key = (UInt16(dataShards) << 8) | UInt16(parityShards)
        if let existing = codecCache[key] { return existing }
        let created = try ReedSolomonFec(dataShards: dataShards, parityShards: parityShards)
        codecCache[key] = created
        return created
    }

    /// Parses `:bridge` `OpalCastUdpMediaChannel` framed format:
    /// `origTotalLen(4B BE) || AES-128-GCM(ptsUs(8B BE) || mediaFlags(1B) || rawFrame)`.
    private func decodeFramedPayload(framed: Data, header: OpalUdpPacketHeader) throws -> OpalCastFrame {
        guard framed.count >= 4 + OpalUdpProtocol.gcmTagSize else {
            throw OpalUdpMediaError.corruptFramedPayload
        }
        var lenReader = BinaryReader(framed)
        let origTotalLen = Int(try lenReader.readUInt32())
        guard origTotalLen >= OpalUdpProtocol.gcmTagSize, 4 + origTotalLen <= framed.count else {
            throw OpalUdpMediaError.corruptFramedPayload
        }
        let ciphertextWithTag = Data(framed[(framed.startIndex + 4)..<(framed.startIndex + 4 + origTotalLen)])
        let plaintext = try OpalUdpCrypto.decrypt(
            ciphertextWithTag: ciphertextWithTag,
            key: key,
            sessionId: header.sessionId,
            frameSeq: header.frameSeq,
            mediaType: header.mediaType
        )
        guard plaintext.count >= 9 else {
            throw OpalUdpMediaError.corruptFramedPayload
        }
        var plainReader = BinaryReader(plaintext)
        let ptsUs = try plainReader.readInt64()
        let mediaFlags = try plainReader.readUInt8()
        let rawFrame = plainReader.readRemainingBytes()
        let frameType: OpalCastFrameType = (header.mediaType == OpalUdpProtocol.mediaTypeAudio)
            ? .audioFrame
            : .videoFrame
        return OpalCastFrame(type: frameType, flags: mediaFlags, timestampUs: ptsUs, payload: rawFrame)
    }

    private mutating func evictOldBucketsIfNeeded() {
        while bucketOrder.count > Self.maxInFlightBuckets {
            let oldest = bucketOrder.removeFirst()
            buckets.removeValue(forKey: oldest)
        }
    }

    private mutating func markCompleted(_ key: BucketKey) {
        completedKeys.insert(key)
        completedOrder.append(key)
        while completedOrder.count > Self.maxCompletedHistory {
            let oldest = completedOrder.removeFirst()
            completedKeys.remove(oldest)
        }
    }
}

/// Native macOS UDP media receiver using `Network.NWConnection` + `CryptoKit` AES-128-GCM + `ReedSolomonFec`.
/// Sends `0x7F` punch packets to open the server UDP return path and feeds recovered frames into `OpalCastSession`.
public final class OpalCastUdpMediaReceiver: Sendable {
    private static let punchKeepaliveIntervalNs: UInt64 = 5_000_000_000

    private struct MutableState {
        var connection: NWConnection?
        var assembler: OpalUdpFrameAssembler
        var punchTask: Task<Void, Never>?
        var punchSeq: UInt32 = 1
        var isStopped: Bool = false
    }

    private let host: String
    private let port: UInt16
    private let queue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.udp-media", qos: .userInteractive)
    private let onFrame: @Sendable (OpalCastFrame) -> Void
    private let state: OSAllocatedUnfairLock<MutableState>

    public init(
        host: String, port: UInt16, sessionId: UInt32, keyData: Data,
        onFrame: @escaping @Sendable (OpalCastFrame) -> Void
    ) throws {
        let assembler = try OpalUdpFrameAssembler(sessionId: sessionId, keyData: keyData)
        self.host = host; self.port = port
        self.onFrame = onFrame
        self.state = OSAllocatedUnfairLock(initialState: MutableState(assembler: assembler))
    }

    deinit { stop() }

    public func start() {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .udp)
        state.withLock { $0.isStopped = false; $0.connection = connection }
        connection.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            if case .ready = newState {
                self.sendPunchBurst(over: connection)
                self.startPunchKeepalive(over: connection)
                self.receiveNextDatagram(from: connection)
            }
        }
        connection.start(queue: queue)
    }

    public func stop() {
        let (conn, task): (NWConnection?, Task<Void, Never>?) = state.withLock { s in
            s.isStopped = true
            let c = s.connection; let t = s.punchTask
            s.connection = nil; s.punchTask = nil
            return (c, t)
        }
        task?.cancel(); conn?.stateUpdateHandler = nil; conn?.cancel()
    }

    private func sendPunchBurst(over connection: NWConnection) { for _ in 0..<3 { sendSinglePunch(over: connection) } }

    private func sendSinglePunch(over connection: NWConnection) {
        let packet: Data? = state.withLock { s in
            guard !s.isStopped, s.connection === connection else { return nil }
            let seq = s.punchSeq
            s.punchSeq &+= 1
            return OpalUdpPacketHeader.makePunchPacket(sessionId: s.assembler.sessionId, seq: seq)
        }
        guard let packet else { return }
        connection.send(content: packet, completion: .contentProcessed { _ in })
    }

    private func startPunchKeepalive(over connection: NWConnection) {
        let task = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: Self.punchKeepaliveIntervalNs) }
                catch { break }
                guard !Task.isCancelled, let self else { break }
                self.sendSinglePunch(over: connection)
            }
        }
        let prev = state.withLock { s -> Task<Void, Never>? in
            let old = s.punchTask
            s.punchTask = task
            return old
        }
        prev?.cancel()
    }

    private func receiveNextDatagram(from connection: NWConnection) {
        connection.receiveMessage { [weak self] content, _, _, error in
            guard let self else { return }
            let active: Bool = self.state.withLock { !$0.isStopped && $0.connection === connection }
            guard active else { return }

            if let data = content, !data.isEmpty {
                let frameResult: Result<OpalCastFrame?, Error> = self.state.withLock { s in
                    Result { try s.assembler.ingest(datagram: data) }
                }
                if case .success(let frame?) = frameResult {
                    self.onFrame(frame)
                }
            }
            if error == nil {
                self.receiveNextDatagram(from: connection)
            }
        }
    }
}
