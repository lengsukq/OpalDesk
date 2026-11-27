import CryptoKit
import Foundation
import os
import Testing
@testable import OpalDeskCore

@Suite("BleNearbyProtocol & BleNearbyScanner")
struct BleNearbyProtocolTests {

    @Test("BLE UUID constants and 12-byte / 14-byte manufacturer data layout match Android BleNearbyProtocol.java byte-for-byte")
    func manufacturerDataExactByteLayout() throws {
        #expect(BleNearbyProtocol.serviceUUIDString == "7A4F0001-6F70-616C-6465-736B00000001")
        #expect(BleNearbyProtocol.challengeCharacteristicUUIDString == "7A4F0002-6F70-616C-6465-736B00000001")
        #expect(BleNearbyProtocol.commandCharacteristicUUIDString == "7A4F0003-6F70-616C-6465-736B00000001")
        #expect(UUID(uuidString: BleNearbyProtocol.serviceUUIDString) != nil)
        #expect(UUID(uuidString: BleNearbyProtocol.challengeCharacteristicUUIDString) != nil)
        #expect(UUID(uuidString: BleNearbyProtocol.commandCharacteristicUUIDString) != nil)
        #expect(BleNearbyProtocol.manufacturerId == 0x504F)

        let payload = BleAdvertisementPayload(
            version: 1,
            flags: [.lanShareActive, .hotspotTriggerSupported], // 0x01 | 0x04 = 0x05
            fingerprintPrefix: "A1B2C3D4E5F607189999", // truncated to first 16 hex chars (8 bytes)
            wifiPort: 19878, // 0x4DA6
            batteryPercent: 87 // 0x57
        )

        let raw12 = BleNearbyProtocol.encodeRawPayload(payload)
        #expect(raw12.count == 12)
        #expect([UInt8](raw12) == [
            0x15, // (version 1 << 4) | 0x05
            0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6, 0x07, 0x18,
            0x4D, 0xA6, // 19878 Big-Endian
            0x57 // 87%
        ])

        let decodedRaw = try #require(BleNearbyProtocol.decodeRawPayload(raw12))
        #expect(decodedRaw == payload)
        #expect(decodedRaw.isLanShareActive == true)
        #expect(decodedRaw.isHotspotActive == false)
        #expect(decodedRaw.supportsHotspotTrigger == true)
        #expect(decodedRaw.fingerprintPrefix == "a1b2c3d4e5f60718")
        #expect(decodedRaw.wifiPort == 19878)
        #expect(decodedRaw.batteryPercent == 87)

        // 14-byte CoreBluetooth manufacturer data includes little-endian company ID [0x4F, 0x50]
        let mfg14 = BleNearbyProtocol.encodeManufacturerData(payload)
        #expect(mfg14.count == 14)
        #expect([UInt8](mfg14.prefix(2)) == [0x4F, 0x50])
        #expect(BleNearbyProtocol.decodeManufacturerData(mfg14) == payload)

        // Unknown battery (nil) encodes as 0xFF and decodes back to nil; short fingerprint zero-pads to 8 bytes
        let unknownBatPayload = BleAdvertisementPayload(
            flags: [.hotspotActive],
            fingerprintPrefix: "deadbeef",
            wifiPort: 8443,
            batteryPercent: nil
        )
        let unknownRaw = BleNearbyProtocol.encodeRawPayload(unknownBatPayload)
        #expect([UInt8](unknownRaw) == [
            0x12,
            0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x00, 0x00, 0x00,
            0x20, 0xFB,
            0xFF
        ])
        let decodedUnknown = try #require(BleNearbyProtocol.decodeRawPayload(unknownRaw))
        #expect(decodedUnknown.batteryPercent == nil)
        #expect(decodedUnknown.isHotspotActive == true)
        #expect(decodedUnknown.fingerprintPrefix == "deadbeef00000000")

        // Reject wrong company ID, truncated buffers, and unsupported protocol versions
        var wrongCompany = mfg14
        wrongCompany[0] = 0x4C
        wrongCompany[1] = 0x00
        #expect(BleNearbyProtocol.decodeManufacturerData(wrongCompany) == nil)
        #expect(BleNearbyProtocol.decodeManufacturerData(mfg14.prefix(13)) == nil)
        #expect(BleNearbyProtocol.decodeRawPayload(raw12.prefix(11)) == nil)

        var badVersion = raw12
        badVersion[0] = 0x25 // version 2
        #expect(BleNearbyProtocol.decodeRawPayload(badVersion) == nil)
    }

    @Test("Hotspot HMAC-SHA256 matches exact Java TrustStoreManager tokenSha256 key derivation and known vector")
    func hotspotHmacKnownVectorAndNormalization() throws {
        let authToken = "opal_perm_token_example_2026"
        let clientId = "mac-client-01"
        let nonceHex = "00112233445566778899aabbccddeeff"

        // Independently derive expected value using standard SHA-256 + HMAC-SHA256:
        // 1. tokenSha256Hex = lowercase hex(SHA256(UTF8(authToken)))
        // 2. key = UTF8(tokenSha256Hex)
        // 3. msg = UTF8("OPAL_HOTSPOT_V1:\(clientId):\(nonceHex)")
        let expectedTokenHash = SHA256.hash(data: Data(authToken.utf8))
            .map { String(format: "%02x", $0) }.joined()
        let expectedMac = HMAC<SHA256>.authenticationCode(
            for: Data("OPAL_HOTSPOT_V1:mac-client-01:00112233445566778899aabbccddeeff".utf8),
            using: SymmetricKey(data: Data(expectedTokenHash.utf8))
        ).map { String(format: "%02x", $0) }.joined()

        #expect(BleNearbyProtocol.tokenSha256Hex(authToken: authToken) == expectedTokenHash)
        #expect(expectedTokenHash.count == 64)
        #expect(expectedMac.count == 64)

        // Direct authToken overload
        let computed = BleNearbyProtocol.computeHotspotHmacHex(
            authToken: authToken,
            clientId: "  \(clientId)  ",
            nonceHex: "  00112233445566778899AABBCCDDEEFF  "
        )
        #expect(computed == expectedMac)

        // Pre-hashed tokenSha256Hex overload matches identically
        let fromHash = BleNearbyProtocol.computeHotspotHmacHex(
            tokenSha256Hex: expectedTokenHash.uppercased(),
            clientId: clientId,
            nonceHex: nonceHex
        )
        #expect(fromHash == expectedMac)

        // Request JSON matches exact wire schema: {"op":"trigger_hotspot","clientId":...,"nonceHex":...,"hmacHex":...}
        let req = HotspotCommandRequest.triggerHotspot(
            clientId: " \(clientId) ",
            nonceHex: "00112233445566778899AABBCCDDEEFF",
            authToken: authToken
        )
        let reqData = try req.encode()
        let obj = try #require(JSONSerialization.jsonObject(with: reqData) as? [String: Any])
        #expect(obj["op"] as? String == "trigger_hotspot")
        #expect(obj["clientId"] as? String == clientId)
        #expect(obj["nonceHex"] as? String == nonceHex)
        #expect(obj["hmacHex"] as? String == expectedMac)
        #expect(try HotspotCommandRequest.decode(from: reqData) == req)
    }

    @Test("HotspotCommandResponse decodes all status variants and defaults omitted fields")
    func hotspotCommandResponseDecoding() throws {
        let startedJSON = Data("""
        {
          "status": "started",
          "ssid": "Xiaomi_15_Hotspot",
          "gatewayIp": "192.168.43.1",
          "port": 19878,
          "message": "热点已开启"
        }
        """.utf8)
        let started = try HotspotCommandResponse.decode(from: startedJSON)
        #expect(started == HotspotCommandResponse(
            status: .started,
            ssid: "Xiaomi_15_Hotspot",
            gatewayIp: "192.168.43.1",
            port: 19878,
            message: "热点已开启"
        ))

        for status in HotspotCommandStatus.allCases {
            let minimal = Data(#"{"status":"\#(status.rawValue)"}"#.utf8)
            let decoded = try HotspotCommandResponse.decode(from: minimal)
            #expect(decoded.status == status)
            #expect(decoded.ssid == "")
            #expect(decoded.gatewayIp == "")
            #expect(decoded.port == 0)
            #expect(decoded.message == "")
        }

        // Unsupported status throws invalidJSON
        #expect(throws: OpalCastCodecError.self) {
            _ = try HotspotCommandResponse.decode(from: Data(#"{"status":"unknown_xyz"}"#.utf8))
        }
    }

    @Test("BleNearbyScanner ignores untrusted devices, tracks trusted beacons, and expires stale devices after 20s")
    func scannerTrustFilteringAndStaleExpiry() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-ble-scanner-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let trustStore = TrustStore(storageURL: tempDir.appendingPathComponent("trust.json"))
        let trustedFp = "1122334455667788" + String(repeating: "ab", count: 24)
        let untrustedFp = "ffeeddccbbaa9988" + String(repeating: "cd", count: 24)
        try trustStore.upsert(TrustedDevice(
            id: "dev-ble-1",
            deviceName: "Xiaomi 15 Ultra",
            certFingerprint: trustedFp,
            authToken: "secret_ble_token"
        ))

        let clock = OSAllocatedUnfairLock<Date>(initialState: Date(timeIntervalSince1970: 1_764_200_000))
        let updates = OSAllocatedUnfairLock<[[DiscoveredBleDevice]]>(initialState: [])

        let scanner = BleNearbyScanner(
            trustStore: trustStore,
            staleIntervalSeconds: 20.0,
            nowProvider: { clock.withLock { $0 } },
            onDevicesChanged: { list in updates.withLock { $0.append(list) } }
        )

        #expect(scanner.authorizationState == .unknown)

        // Untrusted advertisement is silently ignored
        let untrustedMfg = BleNearbyProtocol.encodeManufacturerData(
            BleAdvertisementPayload(flags: [.hotspotTriggerSupported], fingerprintPrefix: untrustedFp, wifiPort: 19878, batteryPercent: 50)
        )
        #expect(scanner.ingestAdvertisement(peripheralId: "P-UNTRUSTED", manufacturerData: untrustedMfg, rssi: -55) == nil)
        #expect(scanner.discoveredDevices.isEmpty)

        // Trusted advertisement is accepted and surfaced
        let trustedMfg = BleNearbyProtocol.encodeManufacturerData(
            BleAdvertisementPayload(
                flags: [.lanShareActive, .hotspotTriggerSupported],
                fingerprintPrefix: trustedFp,
                wifiPort: 19878,
                batteryPercent: 92
            )
        )
        let discovered = try #require(scanner.ingestAdvertisement(peripheralId: "P-TRUSTED", manufacturerData: trustedMfg, rssi: -48))
        #expect(discovered.trustedDevice.deviceName == "Xiaomi 15 Ultra")
        #expect(discovered.batteryPercent == 92)
        #expect(discovered.isLanShareActive == true)
        #expect(discovered.supportsHotspotTrigger == true)
        #expect(scanner.discoveredDevices.count == 1)

        // Still fresh at +19s
        clock.withLock { $0 = $0.addingTimeInterval(19.0) }
        #expect(scanner.pruneStaleDevices() == 0)
        #expect(scanner.discoveredDevices.count == 1)

        // Expires past 20s (+21s total)
        clock.withLock { $0 = $0.addingTimeInterval(2.0) }
        #expect(scanner.pruneStaleDevices() == 1)
        #expect(scanner.discoveredDevices.isEmpty)
        #expect(updates.withLock { $0.last?.isEmpty } == true)
    }
}
