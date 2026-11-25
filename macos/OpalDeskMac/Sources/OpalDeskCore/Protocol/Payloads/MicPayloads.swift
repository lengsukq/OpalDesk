import Foundation

/// Uplink `0x2C MIC_CONFIG` JSON payload requesting Android to enable/disable virtual mic injection.
public struct MicConfigPayload: Codable, Sendable, Equatable {
    public static let defaultSampleRate: Int = 16_000
    public static let defaultChannels: Int = 1
    public static let pcm16Codec: String = "pcm16"

    public let enabled: Bool
    public let codec: String
    public let sampleRate: Int
    public let channels: Int

    public init(
        enabled: Bool,
        codec: String = MicConfigPayload.pcm16Codec,
        sampleRate: Int = MicConfigPayload.defaultSampleRate,
        channels: Int = MicConfigPayload.defaultChannels
    ) {
        self.enabled = enabled
        self.codec = codec.isEmpty ? Self.pcm16Codec : codec
        self.sampleRate = min(max(sampleRate, 8_000), 48_000)
        self.channels = min(max(channels, 1), 2)
    }

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> MicConfigPayload {
        try JSONDecoder().decode(MicConfigPayload.self, from: data)
    }
}

/// Downlink `0x17 MIC_STATE` JSON payload reporting Android virtual mic injection state.
public struct MicStatePayload: Codable, Sendable, Equatable {
    public let active: Bool
    public let supported: Bool
    public let sampleRate: Int
    public let channels: Int
    public let message: String

    public init(
        active: Bool,
        supported: Bool,
        sampleRate: Int = MicConfigPayload.defaultSampleRate,
        channels: Int = MicConfigPayload.defaultChannels,
        message: String = ""
    ) {
        self.active = active
        self.supported = supported
        self.sampleRate = sampleRate
        self.channels = channels
        self.message = message
    }

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> MicStatePayload {
        try JSONDecoder().decode(MicStatePayload.self, from: data)
    }
}

extension OpalCastFrame {
    public static func micConfig(_ payload: MicConfigPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .micConfig, payload: try payload.encode())
    }

    public static func micFrame(_ pcmData: Data) -> OpalCastFrame {
        OpalCastFrame(type: .micFrame, payload: pcmData)
    }
}
