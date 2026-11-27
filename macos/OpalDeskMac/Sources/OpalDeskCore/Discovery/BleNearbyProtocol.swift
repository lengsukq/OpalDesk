import CryptoKit
import Foundation

// MARK: - BLE Nearby Advertisement Capability Flags & Payload

/// Capability bitmask encoded in the low 4 bits of byte 0 in OpalDesk BLE manufacturer payloads.
public struct BleCapabilityFlags: OptionSet, Sendable, Hashable {
    public let rawValue: UInt8
    public init(rawValue: UInt8) {
        self.rawValue = rawValue & 0x0F
    }

    /// Device is currently advertising/serving OpalCast on the local LAN.
    public static let lanShareActive = BleCapabilityFlags(rawValue: 0x01)
    /// Device's personal Wi-Fi hotspot is currently active.
    public static let hotspotActive = BleCapabilityFlags(rawValue: 0x02)
    /// Device supports authenticated BLE GATT remote hotspot trigger.
    public static let hotspotTriggerSupported = BleCapabilityFlags(rawValue: 0x04)
}

/// Decoded 12-byte OpalDesk BLE nearby beacon payload.
public struct BleAdvertisementPayload: Sendable, Equatable, Hashable {
    public var version: UInt8
    public var flags: BleCapabilityFlags
    /// Leading 16 lowercase hex characters (8 raw bytes) of the device's SHA-256 TLS certificate fingerprint.
    public var fingerprintPrefix: String
    /// Big-Endian TCP port where OpalCast TLS server is listening (e.g. 19878).
    public var wifiPort: UInt16
    /// Battery percentage (`0...100`), or `nil` when wire byte is `0xFF` (unknown).
    public var batteryPercent: Int?

    public var isLanShareActive: Bool {
        flags.contains(.lanShareActive)
    }

    public var isHotspotActive: Bool {
        flags.contains(.hotspotActive)
    }

    public var supportsHotspotTrigger: Bool {
        flags.contains(.hotspotTriggerSupported)
    }

    public init(
        version: UInt8 = BleNearbyProtocol.protocolVersion,
        flags: BleCapabilityFlags = [],
        fingerprintPrefix: String,
        wifiPort: UInt16 = OpalCastProtocol.defaultWifiPort,
        batteryPercent: Int? = nil
    ) {
        self.version = version & 0x0F
        self.flags = flags
        let normalized = FingerprintUtil.normalize(fingerprintPrefix)
        self.fingerprintPrefix = Self.paddedHex16(normalized)
        self.wifiPort = wifiPort
        if let batteryPercent {
            self.batteryPercent = min(max(batteryPercent, 0), 100)
        } else {
            self.batteryPercent = nil
        }
    }

    private static func paddedHex16(_ normalizedHex: String) -> String {
        if normalizedHex.count >= 16 {
            return String(normalizedHex.prefix(16))
        }
        return normalizedHex + String(repeating: "0", count: 16 - normalizedHex.count)
    }
}

// MARK: - BLE GATT Hotspot Command Payloads

/// Authenticated command written to `commandCharacteristic` (`7A4F0003-...`) after reading a 16-byte nonce
/// from `challengeCharacteristic` (`7A4F0002-...`).
public struct HotspotCommandRequest: Codable, Sendable, Equatable {
    public static let defaultOp = "trigger_hotspot"

    public var op: String
    public var clientId: String
    public var nonceHex: String
    public var hmacHex: String

    public init(
        op: String = HotspotCommandRequest.defaultOp,
        clientId: String,
        nonceHex: String,
        hmacHex: String
    ) {
        self.op = op
        self.clientId = clientId
        self.nonceHex = nonceHex.lowercased()
        self.hmacHex = hmacHex.lowercased()
    }

    /// Builds a signed `trigger_hotspot` request matching Android `BleNearbyProtocol.java`.
    public static func triggerHotspot(
        clientId: String,
        nonceHex: String,
        authToken: String
    ) -> HotspotCommandRequest {
        let cleanClient = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanNonce = nonceHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let hmac = BleNearbyProtocol.computeHotspotHmacHex(
            authToken: authToken,
            clientId: cleanClient,
            nonceHex: cleanNonce
        )
        return HotspotCommandRequest(
            op: defaultOp,
            clientId: cleanClient,
            nonceHex: cleanNonce,
            hmacHex: hmac
        )
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "BLE_HOTSPOT_REQUEST")
    }

    public static func decode(from data: Data) throws -> HotspotCommandRequest {
        try OpalCastJSONCodec.decode(HotspotCommandRequest.self, from: data, context: "BLE_HOTSPOT_REQUEST")
    }
}

/// Status string returned by Android in `HotspotCommandResponse`.
public enum HotspotCommandStatus: String, Codable, Sendable, CaseIterable, Equatable {
    case started = "started"
    case alreadyActive = "already_active"
    case needsUserToggle = "needs_user_toggle"
    case unauthorized = "unauthorized"
    case error = "error"
}

/// JSON response returned via GATT notify/read on `commandCharacteristic` (`7A4F0003-...`).
public struct HotspotCommandResponse: Codable, Sendable, Equatable {
    public var status: HotspotCommandStatus
    public var ssid: String
    public var gatewayIp: String
    public var port: UInt16
    public var message: String

    public init(
        status: HotspotCommandStatus,
        ssid: String = "",
        gatewayIp: String = "",
        port: UInt16 = 0,
        message: String = ""
    ) {
        self.status = status
        self.ssid = ssid
        self.gatewayIp = gatewayIp
        self.port = port
        self.message = message
    }

    private enum CodingKeys: String, CodingKey {
        case status, ssid, gatewayIp, port, message
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let rawStatus = (try c.decodeIfPresent(String.self, forKey: .status) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parsed = HotspotCommandStatus(rawValue: rawStatus) else {
            throw OpalCastCodecError.invalidJSON("BLE_HOTSPOT_RESPONSE: unsupported status '\(rawStatus)'")
        }
        status = parsed
        ssid = try c.decodeIfPresent(String.self, forKey: .ssid) ?? ""
        gatewayIp = try c.decodeIfPresent(String.self, forKey: .gatewayIp) ?? ""
        let rawPort = try c.decodeIfPresent(Int.self, forKey: .port) ?? 0
        port = (0...Int(UInt16.max)).contains(rawPort) ? UInt16(rawPort) : 0
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "BLE_HOTSPOT_RESPONSE")
    }

    public static func decode(from data: Data) throws -> HotspotCommandResponse {
        try OpalCastJSONCodec.decode(HotspotCommandResponse.self, from: data, context: "BLE_HOTSPOT_RESPONSE")
    }
}

// MARK: - Wire Codec & Crypto (Exact Parity with Android BleNearbyProtocol.java)

public enum BleNearbyProtocol {
    public static let serviceUUIDString = "7A4F0001-6F70-616C-6465-736B00000001"
    public static let challengeCharacteristicUUIDString = "7A4F0002-6F70-616C-6465-736B00000001"
    public static let commandCharacteristicUUIDString = "7A4F0003-6F70-616C-6465-736B00000001"

    /// 2-byte BLE company identifier `"OP"` (`0x504F`), transmitted little-endian (`[0x4F, 0x50]`).
    public static let manufacturerId: UInt16 = 0x504F
    public static let protocolVersion: UInt8 = 1
    public static let rawPayloadLength: Int = 12
    public static let manufacturerDataLength: Int = 14
    public static let unknownBatteryByte: UInt8 = 0xFF

    /// Encodes the 12-byte raw manufacturer payload (without the 2-byte company ID prefix):
    /// - Byte 0: `((version & 0x0F) << 4) | (capFlags & 0x0F)`
    /// - Bytes 1..8: First 8 raw bytes (16 hex chars) of normalized cert SHA-256 fingerprint (zero-padded if short)
    /// - Bytes 9..10: `wifiPort` Big-Endian `UInt16`
    /// - Byte 11: `batteryPercent` (`0...100`, or `0xFF` when unknown)
    public static func encodeRawPayload(_ payload: BleAdvertisementPayload) -> Data {
        var bytes = [UInt8](repeating: 0, count: rawPayloadLength)
        bytes[0] = ((payload.version & 0x0F) << 4) | (payload.flags.rawValue & 0x0F)

        let fpBytes = decodeHexBytes(payload.fingerprintPrefix, maxBytes: 8)
        for idx in 0..<min(fpBytes.count, 8) {
            bytes[1 + idx] = fpBytes[idx]
        }

        bytes[9] = UInt8((payload.wifiPort >> 8) & 0xFF)
        bytes[10] = UInt8(payload.wifiPort & 0xFF)
        bytes[11] = payload.batteryPercent.map { UInt8(min(max($0, 0), 100)) } ?? unknownBatteryByte

        return Data(bytes)
    }

    /// Decodes a 12-byte raw manufacturer payload (without company ID prefix).
    public static func decodeRawPayload(_ data: Data) -> BleAdvertisementPayload? {
        guard data.count >= rawPayloadLength else { return nil }
        let bytes = [UInt8](data)
        let version = (bytes[0] >> 4) & 0x0F
        guard version == protocolVersion else { return nil }

        let capFlags = BleCapabilityFlags(rawValue: bytes[0] & 0x0F)
        let fpHex = bytes[1...8].map { String(format: "%02x", $0) }.joined()
        let wifiPort = (UInt16(bytes[9]) << 8) | UInt16(bytes[10])
        let rawBattery = bytes[11]
        let batteryPercent: Int? = (rawBattery <= 100) ? Int(rawBattery) : nil

        return BleAdvertisementPayload(
            version: version,
            flags: capFlags,
            fingerprintPrefix: fpHex,
            wifiPort: wifiPort,
            batteryPercent: batteryPercent
        )
    }

    /// Encodes the 14-byte CoreBluetooth `CBAdvertisementDataManufacturerDataKey` buffer:
    /// `[0x4F, 0x50]` (little-endian `0x504F`) + 12-byte raw payload.
    public static func encodeManufacturerData(_ payload: BleAdvertisementPayload) -> Data {
        var out = Data(capacity: manufacturerDataLength)
        out.append(UInt8(manufacturerId & 0xFF))
        out.append(UInt8((manufacturerId >> 8) & 0xFF))
        out.append(encodeRawPayload(payload))
        return out
    }

    /// Decodes a 14-byte CoreBluetooth `CBAdvertisementDataManufacturerDataKey` buffer,
    /// validating the 2-byte little-endian `0x504F` (`[0x4F, 0x50]`) manufacturer ID prefix.
    public static func decodeManufacturerData(_ data: Data) -> BleAdvertisementPayload? {
        guard data.count >= manufacturerDataLength else { return nil }
        let bytes = [UInt8](data)
        let companyId = UInt16(bytes[0]) | (UInt16(bytes[1]) << 8)
        guard companyId == manufacturerId else { return nil }
        return decodeRawPayload(Data(bytes[2..<manufacturerDataLength]))
    }

    /// Computes lowercase 64-character SHA-256 hex digest of UTF-8 `authToken`, matching Android `TrustStoreManager`.
    public static func tokenSha256Hex(authToken: String) -> String {
        let digest = SHA256.hash(data: Data(authToken.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Computes lowercase 64-char hex `HMAC-SHA256` matching Android `BleNearbyProtocol.java`:
    /// - `key = UTF8(sha256HexLower(UTF8(authToken)))`
    /// - `msg = UTF8("OPAL_HOTSPOT_V1:\(cleanClientId):\(cleanNonceLower)")`
    public static func computeHotspotHmacHex(
        authToken: String,
        clientId: String,
        nonceHex: String
    ) -> String {
        let tokenHash = tokenSha256Hex(authToken: authToken)
        return computeHotspotHmacHex(
            tokenSha256Hex: tokenHash,
            clientId: clientId,
            nonceHex: nonceHex
        )
    }

    /// Computes lowercase 64-char hex `HMAC-SHA256` directly from a pre-hashed 64-char `tokenSha256Hex`.
    public static func computeHotspotHmacHex(
        tokenSha256Hex: String,
        clientId: String,
        nonceHex: String
    ) -> String {
        let cleanTokenHash = tokenSha256Hex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let cleanClientId = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanNonce = nonceHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        let key = SymmetricKey(data: Data(cleanTokenHash.utf8))
        let message = Data("OPAL_HOTSPOT_V1:\(cleanClientId):\(cleanNonce)".utf8)
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: key)
        return mac.map { String(format: "%02x", $0) }.joined()
    }

    /// Formats raw challenge nonce bytes (typically 16 bytes) as lowercase hex.
    public static func hexString(from data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private static func decodeHexBytes(_ rawHex: String, maxBytes: Int) -> [UInt8] {
        let clean = FingerprintUtil.normalize(rawHex)
        let chars = Array(clean.utf8)
        let pairCount = min(chars.count / 2, maxBytes)
        var result: [UInt8] = []
        result.reserveCapacity(pairCount)
        for i in 0..<pairCount {
            guard let hi = hexNibble(chars[i * 2]),
                  let lo = hexNibble(chars[i * 2 + 1])
            else {
                break
            }
            result.append((hi << 4) | lo)
        }
        return result
    }

    private static func hexNibble(_ ascii: UInt8) -> UInt8? {
        switch ascii {
        case 0x30...0x39: return ascii - 0x30
        case 0x61...0x66: return ascii - 0x61 + 10
        case 0x41...0x46: return ascii - 0x41 + 10
        default: return nil
        }
    }
}
