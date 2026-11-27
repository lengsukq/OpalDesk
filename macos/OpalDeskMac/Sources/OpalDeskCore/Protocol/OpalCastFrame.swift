import Foundation
import os

/// A single OpalCast v1.1 wire frame with fixed 18-byte header and variable payload.
public struct OpalCastFrame: Sendable, Equatable {
    public var type: OpalCastFrameType
    public var flags: UInt8
    public var timestampUs: Int64
    public var payload: Data

    public init(
        type: OpalCastFrameType,
        flags: UInt8 = 0,
        timestampUs: Int64 = OpalCastFrame.currentTimestampUs(),
        payload: Data = Data()
    ) {
        self.type = type
        self.flags = flags
        self.timestampUs = timestampUs
        self.payload = payload
    }

    public static func currentTimestampUs() -> Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000)
    }

    /// Serializes this frame into the exact 18-byte Big-Endian header + payload format.
    public func encode() throws -> Data {
        guard payload.count <= Int(OpalCastProtocol.maxPayloadLength) else {
            throw OpalCastCodecError.payloadTooLarge(UInt32(clamping: payload.count))
        }
        var writer = BinaryWriter(capacity: OpalCastProtocol.headerSize + payload.count)
        for byte in OpalCastProtocol.magicBytes {
            writer.writeUInt8(byte)
        }
        writer.writeUInt8(type.rawValue)
        writer.writeUInt8(flags)
        writer.writeUInt32(UInt32(payload.count))
        writer.writeInt64(timestampUs)
        writer.writeBytes(payload)
        return writer.data
    }

    // MARK: - Uplink Frame Builders (Mac -> Android)

    public static func hello(_ payload: HelloPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .hello, payload: try payload.encode())
    }

    public static func resizeConfig(_ payload: ResizeConfigPayload) -> OpalCastFrame {
        OpalCastFrame(type: .resizeConfig, payload: payload.encode())
    }

    public static func requestIdr() -> OpalCastFrame {
        OpalCastFrame(type: .requestIdr)
    }

    public static func inputMouse(_ payload: InputMousePayload) -> OpalCastFrame {
        OpalCastFrame(type: .inputMouse, payload: payload.encode())
    }

    public static func inputTouch(_ payload: InputTouchPayload) -> OpalCastFrame {
        OpalCastFrame(type: .inputTouch, payload: payload.encode())
    }

    public static func inputKey(_ payload: InputKeyPayload) -> OpalCastFrame {
        OpalCastFrame(type: .inputKey, payload: payload.encode())
    }

    public static func inputText(_ payload: InputTextPayload) -> OpalCastFrame {
        OpalCastFrame(type: .inputText, payload: payload.encode())
    }

    public static func inputImage(_ payload: InputImagePayload) throws -> OpalCastFrame {
        OpalCastFrame(
            type: .inputText,
            flags: OpalCastProtocol.flagClipboardImagePng,
            payload: try payload.encode()
        )
    }

    public static func systemAction(_ payload: SystemActionPayload) -> OpalCastFrame {
        OpalCastFrame(type: .systemAction, payload: payload.encode())
    }

    public static func fileTransferChunk(_ payload: FileTransferChunkPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .fileTransferChunk, payload: try payload.encode())
    }

    public static func requestWifiUpgrade() -> OpalCastFrame {
        OpalCastFrame(type: .requestWifiUpgrade)
    }

    public static func listApps() -> OpalCastFrame {
        OpalCastFrame(type: .listApps)
    }

    public static func windowAction(_ payload: WindowActionPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .windowAction, payload: try payload.encode())
    }

    public static func ping(_ pingId: UInt64) -> OpalCastFrame {
        OpalCastFrame(type: .ping, payload: PingPongPayload(pingId: pingId).encode())
    }

    public static func notificationAction(_ payload: NotificationActionPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .notificationAction, payload: try payload.encode())
    }

    public static func mediaAction(_ payload: MediaActionPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .mediaAction, payload: try payload.encode())
    }

    public static func fileOpRequest(_ payload: FileOpRequestPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .fileOpRequest, payload: try payload.encode())
    }

    public static func apkInstallRequest(_ payload: ApkInstallRequestPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .apkInstallRequest, payload: try payload.encode())
    }
}

/// Streaming ring-buffered decoder for TCP byte streams that handles arbitrary
/// packet fragmentation (half-packets) and coalescing (sticky packets).
public struct OpalCastStreamDecoder: Sendable {
    private static let compactionThresholdBytes = 64 * 1024

    private var storage: [UInt8]
    private var readIndex: Int = 0
    private var writeIndex: Int = 0
    public let skipUnknownFrameTypes: Bool

    public var bufferedByteCount: Int {
        writeIndex - readIndex
    }

    public init(initialCapacity: Int = 256 * 1024, skipUnknownFrameTypes: Bool = false) {
        self.storage = []
        self.storage.reserveCapacity(max(initialCapacity, OpalCastProtocol.headerSize))
        self.skipUnknownFrameTypes = skipUnknownFrameTypes
    }

    public mutating func reset() {
        storage.removeAll(keepingCapacity: true)
        readIndex = 0
        writeIndex = 0
    }

    public mutating func append(_ chunk: Data) {
        guard !chunk.isEmpty else { return }
        compactIfNeeded(incomingCount: chunk.count)
        let start = writeIndex
        let newEnd = start + chunk.count
        if newEnd > storage.count {
            storage.append(contentsOf: repeatElement(0, count: newEnd - storage.count))
        }
        storage.withUnsafeMutableBufferPointer { buffer in
            guard let baseAddress = buffer.baseAddress else { return }
            let destination = UnsafeMutableRawBufferPointer(
                start: baseAddress.advanced(by: start),
                count: chunk.count
            )
            chunk.copyBytes(to: destination)
        }
        writeIndex = newEnd
    }

    /// Extracts all complete frames currently buffered.
    /// Throws immediately on corrupted magic, unknown frame type, or oversized payload.
    public mutating func decodeAvailableFrames() throws -> [OpalCastFrame] {
        var frames: [OpalCastFrame] = []
        while let frame = try decodeNextFrame() {
            frames.append(frame)
        }
        return frames
    }

    /// Decodes a single complete frame if available; returns nil when waiting for more bytes.
    /// When `skipUnknownFrameTypes` is enabled, unrecognized future OPD1 frame types are skipped
    /// cleanly by `payloadLen` once complete so forward-compatible protocol extensions never disconnect sessions.
    public mutating func decodeNextFrame() throws -> OpalCastFrame? {
        while bufferedByteCount >= OpalCastProtocol.headerSize {
            let base = readIndex
            let magic = OpalCastProtocol.magicBytes
            if storage[base] != magic[0]
                || storage[base + 1] != magic[1]
                || storage[base + 2] != magic[2]
                || storage[base + 3] != magic[3]
            {
                let bad = Array(storage[base..<(base + 4)])
                reset()
                throw OpalCastCodecError.invalidMagic(bad)
            }

            let rawType = storage[base + 4]
            let flags = storage[base + 5]
            let payloadLen =
                (UInt32(storage[base + 6]) << 24)
                | (UInt32(storage[base + 7]) << 16)
                | (UInt32(storage[base + 8]) << 8)
                | UInt32(storage[base + 9])

            if payloadLen > OpalCastProtocol.maxPayloadLength {
                reset()
                throw OpalCastCodecError.payloadTooLarge(payloadLen)
            }

            let parsedType = OpalCastFrameType(rawValue: rawType)
            if parsedType == nil && !skipUnknownFrameTypes {
                reset()
                throw OpalCastCodecError.unknownFrameType(rawType)
            }

            let totalFrameLen = OpalCastProtocol.headerSize + Int(payloadLen)
            guard bufferedByteCount >= totalFrameLen else {
                return nil
            }

            let payloadStart = base + OpalCastProtocol.headerSize
            let payloadEnd = payloadStart + Int(payloadLen)

            guard let frameType = parsedType else {
                OpalDeskLog.session.debug(
                    "Skipping unknown OPD1 frame type 0x\(String(rawType, radix: 16), privacy: .public) (\(payloadLen) bytes)"
                )
                readIndex = payloadEnd
                if readIndex == writeIndex {
                    readIndex = 0
                    writeIndex = 0
                }
                continue
            }

            let tsHigh =
                (UInt64(storage[base + 10]) << 24)
                | (UInt64(storage[base + 11]) << 16)
                | (UInt64(storage[base + 12]) << 8)
                | UInt64(storage[base + 13])
            let tsLow =
                (UInt64(storage[base + 14]) << 24)
                | (UInt64(storage[base + 15]) << 16)
                | (UInt64(storage[base + 16]) << 8)
                | UInt64(storage[base + 17])
            let timestampUs = Int64(bitPattern: (tsHigh << 32) | tsLow)

            let payload = payloadLen == 0
                ? Data()
                : Data(storage[payloadStart..<payloadEnd])

            readIndex = payloadEnd
            if readIndex == writeIndex {
                readIndex = 0
                writeIndex = 0
            }

            return OpalCastFrame(
                type: frameType,
                flags: flags,
                timestampUs: timestampUs,
                payload: payload
            )
        }
        return nil
    }

    private mutating func compactIfNeeded(incomingCount: Int) {
        if readIndex == writeIndex {
            readIndex = 0
            writeIndex = 0
            return
        }
        // Compact once consumed prefix exceeds 64 KiB and at least half of written span.
        if readIndex >= Self.compactionThresholdBytes && readIndex * 2 >= writeIndex {
            let unread = writeIndex - readIndex
            storage.replaceSubrange(0..<unread, with: storage[readIndex..<writeIndex])
            readIndex = 0
            writeIndex = unread
            return
        }
        // Also compact if appending would force growth while dead head space exists.
        if readIndex > 0 && writeIndex + incomingCount > storage.count {
            let unread = writeIndex - readIndex
            storage.replaceSubrange(0..<unread, with: storage[readIndex..<writeIndex])
            readIndex = 0
            writeIndex = unread
        }
    }
}
