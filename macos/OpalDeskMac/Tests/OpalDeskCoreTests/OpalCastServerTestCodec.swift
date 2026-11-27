import Foundation
@testable import OpalDeskCore

/// Test-only server-side (opposite-direction) wire codecs for OpalCast v1.1.
/// Keeps production `OpalDeskCore` lean (client-only encode/decode directions)
/// while preserving 100% roundtrip wire compatibility test coverage.

// MARK: - Downlink Frame Builders (Android -> Mac)

extension OpalCastFrame {
    static func clipboardImagePush(_ payload: ClipboardImagePushPayload) throws -> OpalCastFrame {
        OpalCastFrame(
            type: .clipboardPush,
            flags: OpalCastProtocol.flagClipboardImagePng,
            payload: try payload.encode()
        )
    }

    static func wifiUpgradeReady(_ payload: WifiUpgradeReadyPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .wifiUpgradeReady, payload: try payload.encode())
    }

    static func appList(_ payload: AppListPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .appList, payload: try payload.encode())
    }
}

// MARK: - Downlink Encoders (Android -> Mac)

extension VideoConfigPayload {
    func encode() -> Data {
        var writer = BinaryWriter(capacity: 11 + csd.count)
        writer.writeUInt8(codec.rawValue)
        writer.writeUInt16(width)
        writer.writeUInt16(height)
        writer.writeUInt16(dpi)
        writer.writeUInt8(rotation)
        writer.writeUInt16(fpsX100)
        writer.writeUInt8(displayMode.rawValue)
        writer.writeBytes(csd)
        return writer.data
    }
}

extension AudioConfigPayload {
    func encode() -> Data {
        var writer = BinaryWriter(capacity: 6 + codecSpecificData.count)
        writer.writeUInt8(codec.rawValue)
        writer.writeUInt32(sampleRate)
        writer.writeUInt8(channels)
        writer.writeBytes(codecSpecificData)
        return writer.data
    }
}

extension DeviceStatePayload {
    private struct WireDTO: Encodable {
        let deviceName: String
        let sinkType: Int
        let displayMode: Int
        let width: Int
        let height: Int
        let dpi: Int
        let fps: Double
        let rotation: Int
        let bitrateKbps: Int
        let batteryPct: Int
        let blackout: Bool
        let audioEnabled: Bool
        let injectStatus: Int
        let adaptiveSingleEnabled: Bool
        let defaultSingleWidthDp: Int
        let activeLayoutMode: String
        let topAppPackage: String
        let topAppLabel: String
        let tilingSlots: [String]
        let tilingRatios: [Int]
    }

    func encode() throws -> Data {
        let dto = WireDTO(
            deviceName: deviceName,
            sinkType: sinkType,
            displayMode: displayMode,
            width: width,
            height: height,
            dpi: dpi,
            fps: fps,
            rotation: rotation,
            bitrateKbps: bitrateKbps,
            batteryPct: batteryPct,
            blackout: blackout,
            audioEnabled: audioEnabled,
            injectStatus: injectStatus,
            adaptiveSingleEnabled: adaptiveSingleEnabled,
            defaultSingleWidthDp: defaultSingleWidthDp,
            activeLayoutMode: activeLayoutMode,
            topAppPackage: topAppPackage,
            topAppLabel: topAppLabel,
            tilingSlots: tilingSlots,
            tilingRatios: tilingRatios
        )
        return try OpalCastJSONCodec.encode(dto, context: "DEVICE_STATE")
    }
}

extension AuthResultPayload {
    private struct WireDTO: Encodable {
        let ok: Bool
        let authToken: String
        let message: String
        let deviceName: String
        let certFingerprint: String
    }

    func encode() throws -> Data {
        let dto = WireDTO(
            ok: ok,
            authToken: authToken,
            message: message,
            deviceName: deviceName,
            certFingerprint: certFingerprint
        )
        let jsonData = try OpalCastJSONCodec.encode(dto, context: "AUTH_RESULT")
        var writer = BinaryWriter(capacity: 1 + jsonData.count)
        writer.writeUInt8(ok ? 1 : 0)
        writer.writeBytes(jsonData)
        return writer.data
    }
}

extension WifiUpgradeReadyPayload {
    private struct WireDTO: Encodable {
        let ok: Bool
        let host: String
        let port: Int
        let certFingerprint: String
        let authToken: String
        let message: String
    }

    func encode() throws -> Data {
        let dto = WireDTO(
            ok: ok,
            host: host,
            port: port,
            certFingerprint: certFingerprint,
            authToken: authToken,
            message: message
        )
        return try OpalCastJSONCodec.encode(dto, context: "WIFI_UPGRADE_READY")
    }
}

extension AppListPayload {
    private struct AppDTO: Encodable {
        let pkg: String
        let cls: String
        let label: String
        let runningOnExt: Bool
        let taskId: Int
        let icon: String?
    }

    private struct ListDTO: Encodable {
        let apps: [AppDTO]
    }

    func encode() throws -> Data {
        let dto = ListDTO(apps: apps.map {
            AppDTO(
                pkg: $0.pkg,
                cls: $0.cls,
                label: $0.label,
                runningOnExt: $0.runningOnExt,
                taskId: $0.taskId,
                icon: $0.iconBase64
            )
        })
        return try OpalCastJSONCodec.encode(dto, context: "APP_LIST")
    }
}

extension ClipboardPushPayload {
    func encode() -> Data {
        var writer = BinaryWriter(capacity: 8 + text.utf8.count)
        writer.writeInt64(seq)
        writer.writeUTF8(text)
        return writer.data
    }
}

extension ClipboardImagePushPayload {
    func encode() throws -> Data {
        guard pngData.count <= OpalCastProtocol.maxClipboardImageBytes else {
            throw OpalCastCodecError.payloadTooLarge(UInt32(clamping: pngData.count))
        }
        var writer = BinaryWriter(capacity: 8 + pngData.count)
        writer.writeInt64(seq)
        writer.writeBytes(pngData)
        return writer.data
    }
}

extension FileTransferAckPayload {
    func encode() -> Data {
        var writer = BinaryWriter(capacity: 5 + pathOrError.utf8.count)
        writer.writeUInt32(transferId)
        writer.writeUInt8(status.rawValue)
        writer.writeUTF8(pathOrError)
        return writer.data
    }
}

// MARK: - Uplink Decoders (Mac -> Android)

extension HelloPayload {
    private struct WireDTO: Decodable {
        let protocolVersion: Int?
        let clientName: String?
        let clientId: String?
        let preferredCodec: String?
        let width: Int?
        let height: Int?
        let dpi: Int?
        let fps: Int?
        let bitrateKbps: Int?
        let displayMode: Int?
        let audioEnabled: Bool?
        let authToken: String?
        let pinCode: String?
        let transportHint: String?
        let sessionRole: String?
        let appPackage: String?
        let appActivity: String?
        let companionEvents: Bool?
    }

    static func decode(from data: Data) throws -> HelloPayload {
        let dto = try OpalCastJSONCodec.decode(WireDTO.self, from: data, context: "HELLO")
        return HelloPayload(
            protocolVersion: dto.protocolVersion ?? OpalCastProtocol.version,
            clientName: dto.clientName ?? "",
            clientId: dto.clientId ?? "",
            preferredCodec: dto.preferredCodec ?? OpalCastVideoCodec.hevc.preferredName,
            width: dto.width ?? OpalCastProtocol.defaultWidth,
            height: dto.height ?? OpalCastProtocol.defaultHeight,
            dpi: dto.dpi ?? DisplayPresets.defaultDPI,
            fps: dto.fps ?? DisplayPresets.defaultFPS,
            bitrateKbps: dto.bitrateKbps ?? DisplayPresets.defaultBitrateKbps,
            displayMode: dto.displayMode ?? Int(OpalCastDisplayMode.dex.rawValue),
            audioEnabled: dto.audioEnabled ?? true,
            authToken: dto.authToken ?? "",
            pinCode: dto.pinCode ?? "",
            transportHint: dto.transportHint ?? OpalCastTransportHint.usb.rawValue,
            sessionRole: dto.sessionRole ?? OpalCastSessionRole.desktop.rawValue,
            appPackage: dto.appPackage ?? "",
            appActivity: dto.appActivity ?? "",
            companionEvents: dto.companionEvents ?? false
        )
    }
}

extension ResizeConfigPayload {
    static func decode(from data: Data) throws -> ResizeConfigPayload {
        var reader = BinaryReader(data)
        return ResizeConfigPayload(
            width: try reader.readUInt16(),
            height: try reader.readUInt16(),
            dpi: try reader.readUInt16(),
            fpsX100: try reader.readUInt16(),
            bitrateKbps: try reader.readUInt32(),
            displayMode: try reader.readUInt8(),
            rotation: try reader.readUInt8()
        )
    }
}

extension SystemActionPayload {
    static func decode(from data: Data) throws -> SystemActionPayload {
        var reader = BinaryReader(data)
        let actionRaw = try reader.readUInt8()
        guard let action = OpalCastSystemActionKind(rawValue: actionRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "systemAction", rawValue: UInt32(actionRaw))
        }
        return SystemActionPayload(action: action, arg: try reader.readInt32())
    }
}

extension InputMousePayload {
    static func decode(from data: Data) throws -> InputMousePayload {
        var reader = BinaryReader(data)
        let actionRaw = try reader.readUInt8()
        guard let action = OpalCastMouseAction(rawValue: actionRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "mouseAction", rawValue: UInt32(actionRaw))
        }
        return InputMousePayload(
            action: action,
            x: try reader.readFloat32(),
            y: try reader.readFloat32(),
            buttons: OpalCastMouseButtons(rawValue: try reader.readUInt32()),
            actionButton: try reader.readUInt32(),
            hScroll: try reader.readFloat32(),
            vScroll: try reader.readFloat32()
        )
    }
}

extension InputTouchPayload {
    static func decode(from data: Data) throws -> InputTouchPayload {
        var reader = BinaryReader(data)
        let actionRaw = try reader.readUInt8()
        guard let action = OpalCastTouchAction(rawValue: actionRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "touchAction", rawValue: UInt32(actionRaw))
        }
        let actionIndex = try reader.readUInt8()
        let pointerCount = Int(try reader.readUInt8())
        guard pointerCount <= OpalCastProtocol.maxTouchPointers else {
            throw OpalCastCodecError.invalidEnumValue(field: "touchPointerCount", rawValue: UInt32(pointerCount))
        }
        var pointers: [TouchPointer] = []
        pointers.reserveCapacity(pointerCount)
        for _ in 0..<pointerCount {
            pointers.append(TouchPointer(
                pointerId: try reader.readInt32(),
                x: try reader.readFloat32(),
                y: try reader.readFloat32(),
                pressure: try reader.readFloat32()
            ))
        }
        return InputTouchPayload(action: action, actionIndex: actionIndex, pointers: pointers)
    }
}

extension InputKeyPayload {
    static func decode(from data: Data) throws -> InputKeyPayload {
        var reader = BinaryReader(data)
        let actionRaw = try reader.readUInt8()
        guard let action = OpalCastKeyAction(rawValue: actionRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "keyAction", rawValue: UInt32(actionRaw))
        }
        return InputKeyPayload(
            action: action,
            androidKeyCode: try reader.readInt32(),
            metaState: try reader.readInt32(),
            repeatCount: try reader.readInt32()
        )
    }
}

extension InputTextPayload {
    static func decode(from data: Data) throws -> InputTextPayload {
        var reader = BinaryReader(data)
        let modeRaw = try reader.readUInt8()
        guard let mode = OpalCastInputTextMode(rawValue: modeRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "inputTextMode", rawValue: UInt32(modeRaw))
        }
        let seq = try reader.readInt64()
        let text = try reader.readRemainingUTF8(field: "inputText.text")
        return InputTextPayload(mode: mode, seq: seq, text: text)
    }
}

extension InputImagePayload {
    static func decode(from data: Data) throws -> InputImagePayload {
        var reader = BinaryReader(data)
        let modeRaw = try reader.readUInt8()
        guard let mode = OpalCastInputTextMode(rawValue: modeRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "inputImageMode", rawValue: UInt32(modeRaw))
        }
        let seq = try reader.readInt64()
        let pngData = reader.readRemainingBytes()
        guard pngData.count <= OpalCastProtocol.maxClipboardImageBytes else {
            throw OpalCastCodecError.payloadTooLarge(UInt32(clamping: pngData.count))
        }
        return InputImagePayload(mode: mode, seq: seq, pngData: pngData)
    }
}

extension FileTransferChunkPayload {
    static func decode(from raw: Data) throws -> FileTransferChunkPayload {
        var reader = BinaryReader(raw)
        let transferId = try reader.readUInt32()
        let chunkFlags = OpalCastFileChunkFlags(rawValue: try reader.readUInt8())
        var fileName: String?
        var totalSize: Int64?
        if chunkFlags.contains(.start) {
            let nameLen = Int(try reader.readUInt16())
            let nameData = try reader.readBytes(count: nameLen)
            guard let decodedName = String(data: nameData, encoding: .utf8) else {
                throw OpalCastCodecError.invalidUTF8(field: "fileTransferChunk.fileName")
            }
            fileName = decodedName
            totalSize = try reader.readInt64()
        }
        let chunkData = chunkFlags.contains(.data) ? reader.readRemainingBytes() : Data()
        return FileTransferChunkPayload(
            transferId: transferId,
            chunkFlags: chunkFlags,
            fileName: fileName,
            totalSize: totalSize,
            data: chunkData
        )
    }
}

extension WindowActionPayload {
    private struct WireDTO: Decodable {
        let action: WindowActionKind
        let enabled: Bool?
        let defaultWidthDp: Int?
        let pkg: String?
        let cls: String?
        let widthPct: Int?
        let remember: Bool?
        let mode: String?
        let slot: Int?
        let taskId: Int?
        let topology: String?
        let pkgs: [String]?
        let ratios: [Int]?
        let backend: String?
    }

    static func decode(from data: Data) throws -> WindowActionPayload {
        let dto = try OpalCastJSONCodec.decode(WireDTO.self, from: data, context: "WINDOW_ACTION")
        return WindowActionPayload(
            action: dto.action,
            enabled: dto.enabled,
            defaultWidthDp: dto.defaultWidthDp,
            pkg: dto.pkg,
            cls: dto.cls,
            widthPct: dto.widthPct,
            remember: dto.remember,
            mode: dto.mode,
            slot: dto.slot,
            taskId: dto.taskId,
            topology: dto.topology,
            pkgs: dto.pkgs,
            ratios: dto.ratios,
            backend: dto.backend
        )
    }
}
