import Foundation

/// Uplink `0x2E CAMERA_CONFIG` JSON payload requesting Android (Root-gated) to enable/disable camera mapping.
public struct CameraConfigPayload: Codable, Sendable, Equatable {
    public static let defaultWidth: Int = 640
    public static let defaultHeight: Int = 480
    public static let defaultFps: Int = 15
    public static let mjpegCodec: String = "mjpeg"

    public let enabled: Bool
    public let codec: String
    public let width: Int
    public let height: Int
    public let fps: Int
    public let facing: String

    public init(
        enabled: Bool,
        codec: String = CameraConfigPayload.mjpegCodec,
        width: Int = CameraConfigPayload.defaultWidth,
        height: Int = CameraConfigPayload.defaultHeight,
        fps: Int = CameraConfigPayload.defaultFps,
        facing: String = "front"
    ) {
        self.enabled = enabled
        self.codec = codec.isEmpty ? Self.mjpegCodec : codec
        self.width = min(max(width, 160), 1920)
        self.height = min(max(height, 120), 1080)
        self.fps = min(max(fps, 5), 30)
        self.facing = facing == "back" ? "back" : "front"
    }

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> CameraConfigPayload {
        try JSONDecoder().decode(CameraConfigPayload.self, from: data)
    }
}

/// Downlink `0x18 CAMERA_STATE` JSON payload reporting Android Root virtual camera bridge state.
public struct CameraStatePayload: Codable, Sendable, Equatable {
    public let active: Bool
    public let supported: Bool
    public let rootRequired: Bool
    public let width: Int
    public let height: Int
    public let fps: Int
    public let backend: String
    public let message: String

    public init(
        active: Bool,
        supported: Bool,
        rootRequired: Bool = true,
        width: Int = CameraConfigPayload.defaultWidth,
        height: Int = CameraConfigPayload.defaultHeight,
        fps: Int = CameraConfigPayload.defaultFps,
        backend: String = "",
        message: String = ""
    ) {
        self.active = active
        self.supported = supported
        self.rootRequired = rootRequired
        self.width = width
        self.height = height
        self.fps = fps
        self.backend = backend
        self.message = message
    }

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> CameraStatePayload {
        try JSONDecoder().decode(CameraStatePayload.self, from: data)
    }
}

extension OpalCastFrame {
    public static func cameraConfig(_ payload: CameraConfigPayload) throws -> OpalCastFrame {
        OpalCastFrame(type: .cameraConfig, payload: try payload.encode())
    }

    public static func cameraFrame(_ jpegData: Data) -> OpalCastFrame {
        OpalCastFrame(type: .cameraFrame, payload: jpegData)
    }
}
