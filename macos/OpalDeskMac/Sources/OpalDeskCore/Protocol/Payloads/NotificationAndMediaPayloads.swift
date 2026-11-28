import Foundation

// MARK: - Notification Payloads (0x19 Downlink / 0x31 Uplink)

/// Action verb inside downlink `0x19 NOTIFICATION_EVENT`.
public enum NotificationEventAction: String, Codable, Sendable, CaseIterable, Equatable {
    case snapshot = "snapshot"
    case posted = "posted"
    case removed = "removed"
}

/// An interactive button action attached to an Android status-bar notification (e.g. "Mark as Read").
public struct RemoteNotificationAction: Codable, Sendable, Equatable, Hashable, Identifiable {
    public var id: Int { index }
    public var index: Int
    public var label: String

    public init(index: Int, label: String) {
        self.index = index
        self.label = label
    }

    private enum CodingKeys: String, CodingKey {
        case index, label
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        index = try c.decodeIfPresent(Int.self, forKey: .index) ?? 0
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
    }
}

/// A single Android status bar notification synced over `0x19 NOTIFICATION_EVENT`.
public struct RemoteNotificationItem: Codable, Sendable, Equatable, Identifiable, Hashable {
    public var id: String { key }
    public var key: String
    public var pkg: String
    public var appLabel: String
    public var title: String
    public var text: String
    public var postTimeMs: Int64
    public var clearable: Bool
    public var canReply: Bool
    public var replyLabel: String
    public var otpCode: String
    public var actions: [RemoteNotificationAction]

    public var displayAppLabel: String {
        appLabel.isEmpty ? pkg : appLabel
    }

    /// Non-blank interactive notification buttons ready for UI rendering (SSOT across Hub & Menu Bar).
    public var displayActions: [RemoteNotificationAction] {
        actions.filter { !$0.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    public init(
        key: String,
        pkg: String,
        appLabel: String = "",
        title: String = "",
        text: String = "",
        postTimeMs: Int64 = 0,
        clearable: Bool = true,
        canReply: Bool = false,
        replyLabel: String = "",
        otpCode: String = "",
        actions: [RemoteNotificationAction] = []
    ) {
        self.key = key
        self.pkg = pkg
        self.appLabel = appLabel
        self.title = title
        self.text = text
        self.postTimeMs = postTimeMs
        self.clearable = clearable
        self.canReply = canReply
        self.replyLabel = replyLabel
        self.otpCode = otpCode
        self.actions = actions
    }

    private enum CodingKeys: String, CodingKey {
        case key, pkg, appLabel, title, text, postTimeMs, clearable, canReply, replyLabel, otpCode, actions
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decodeIfPresent(String.self, forKey: .key) ?? ""
        pkg = try c.decodeIfPresent(String.self, forKey: .pkg) ?? ""
        appLabel = try c.decodeIfPresent(String.self, forKey: .appLabel) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        postTimeMs = try c.decodeIfPresent(Int64.self, forKey: .postTimeMs) ?? 0
        clearable = try c.decodeIfPresent(Bool.self, forKey: .clearable) ?? true
        canReply = try c.decodeIfPresent(Bool.self, forKey: .canReply) ?? false
        replyLabel = try c.decodeIfPresent(String.self, forKey: .replyLabel) ?? ""
        otpCode = try c.decodeIfPresent(String.self, forKey: .otpCode) ?? ""
        actions = try c.decodeIfPresent([RemoteNotificationAction].self, forKey: .actions) ?? []
    }
}

/// Downlink `0x19 NOTIFICATION_EVENT` UTF-8 JSON payload.
public struct NotificationEventPayload: Codable, Sendable, Equatable {
    public var action: NotificationEventAction
    public var key: String?
    public var items: [RemoteNotificationItem]

    public init(
        action: NotificationEventAction,
        key: String? = nil,
        items: [RemoteNotificationItem] = []
    ) {
        self.action = action
        self.key = key
        self.items = items
    }

    private enum CodingKeys: String, CodingKey {
        case action, key, items
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let rawAction = (try c.decodeIfPresent(String.self, forKey: .action) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parsed = NotificationEventAction(rawValue: rawAction) else {
            throw OpalCastCodecError.invalidJSON("NOTIFICATION_EVENT: unsupported action '\(rawAction)'")
        }
        action = parsed
        key = try c.decodeIfPresent(String.self, forKey: .key)
        items = try c.decodeIfPresent([RemoteNotificationItem].self, forKey: .items) ?? []
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "NOTIFICATION_EVENT")
    }

    public static func decode(from data: Data) throws -> NotificationEventPayload {
        try OpalCastJSONCodec.decode(NotificationEventPayload.self, from: data, context: "NOTIFICATION_EVENT")
    }
}

/// Operation verb for uplink `0x31 NOTIFICATION_ACTION`.
public enum NotificationActionOp: String, Codable, Sendable, CaseIterable, Equatable {
    case sync = "sync"
    case dismiss = "dismiss"
    case clearAll = "clear_all"
    case reply = "reply"
    case invokeAction = "invoke_action"
}

/// Uplink `0x31 NOTIFICATION_ACTION` UTF-8 JSON payload.
public struct NotificationActionPayload: Codable, Sendable, Equatable {
    public var op: NotificationActionOp
    public var key: String?
    public var replyText: String?
    public var actionIndex: Int?

    public init(
        op: NotificationActionOp,
        key: String? = nil,
        replyText: String? = nil,
        actionIndex: Int? = nil
    ) {
        self.op = op
        self.key = key
        self.replyText = replyText
        self.actionIndex = actionIndex
    }

    public static func sync() -> NotificationActionPayload {
        NotificationActionPayload(op: .sync)
    }

    public static func dismiss(key: String) -> NotificationActionPayload {
        NotificationActionPayload(op: .dismiss, key: key)
    }

    public static func clearAll() -> NotificationActionPayload {
        NotificationActionPayload(op: .clearAll)
    }

    public static func reply(key: String, text: String) -> NotificationActionPayload {
        NotificationActionPayload(op: .reply, key: key, replyText: text)
    }

    public static func invokeAction(key: String, actionIndex: Int) -> NotificationActionPayload {
        NotificationActionPayload(op: .invokeAction, key: key, actionIndex: actionIndex)
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "NOTIFICATION_ACTION")
    }

    public static func decode(from data: Data) throws -> NotificationActionPayload {
        try OpalCastJSONCodec.decode(NotificationActionPayload.self, from: data, context: "NOTIFICATION_ACTION")
    }
}

// MARK: - Media Control Payloads (0x1A Downlink / 0x32 Uplink)

/// Downlink `0x1A MEDIA_STATE` UTF-8 JSON payload mirroring Android active MediaSession metadata.
public struct MediaStatePayload: Codable, Sendable, Equatable {
    public var active: Bool
    public var pkg: String
    public var appLabel: String
    public var title: String
    public var artist: String
    public var album: String
    public var playing: Bool
    public var positionMs: Int64
    public var durationMs: Int64
    public var artworkBase64: String

    public var artworkData: Data? {
        guard !artworkBase64.isEmpty else { return nil }
        return Data(base64Encoded: artworkBase64)
    }

    public init(
        active: Bool = false,
        pkg: String = "",
        appLabel: String = "",
        title: String = "",
        artist: String = "",
        album: String = "",
        playing: Bool = false,
        positionMs: Int64 = 0,
        durationMs: Int64 = 0,
        artworkBase64: String = ""
    ) {
        self.active = active
        self.pkg = pkg
        self.appLabel = appLabel
        self.title = title
        self.artist = artist
        self.album = album
        self.playing = playing
        self.positionMs = max(0, positionMs)
        self.durationMs = max(0, durationMs)
        self.artworkBase64 = artworkBase64
    }

    private enum CodingKeys: String, CodingKey {
        case active, pkg, appLabel, title, artist, album, playing, positionMs, durationMs, artworkBase64
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        active = try c.decodeIfPresent(Bool.self, forKey: .active) ?? false
        pkg = try c.decodeIfPresent(String.self, forKey: .pkg) ?? ""
        appLabel = try c.decodeIfPresent(String.self, forKey: .appLabel) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        artist = try c.decodeIfPresent(String.self, forKey: .artist) ?? ""
        album = try c.decodeIfPresent(String.self, forKey: .album) ?? ""
        playing = try c.decodeIfPresent(Bool.self, forKey: .playing) ?? false
        positionMs = max(0, try c.decodeIfPresent(Int64.self, forKey: .positionMs) ?? 0)
        durationMs = max(0, try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0)
        artworkBase64 = try c.decodeIfPresent(String.self, forKey: .artworkBase64) ?? ""
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "MEDIA_STATE")
    }

    public static func decode(from data: Data) throws -> MediaStatePayload {
        try OpalCastJSONCodec.decode(MediaStatePayload.self, from: data, context: "MEDIA_STATE")
    }
}

/// Operation verb for uplink `0x32 MEDIA_ACTION`.
public enum MediaActionOp: String, Codable, Sendable, CaseIterable, Equatable {
    case play = "play"
    case pause = "pause"
    case toggle = "toggle"
    case next = "next"
    case previous = "previous"
    case stop = "stop"
    case seek = "seek"
}

/// Uplink `0x32 MEDIA_ACTION` UTF-8 JSON payload.
public struct MediaActionPayload: Codable, Sendable, Equatable {
    public var op: MediaActionOp
    public var positionMs: Int64?

    public init(op: MediaActionOp, positionMs: Int64? = nil) {
        self.op = op
        self.positionMs = positionMs
    }

    public static func play() -> MediaActionPayload { MediaActionPayload(op: .play) }
    public static func pause() -> MediaActionPayload { MediaActionPayload(op: .pause) }
    public static func toggle() -> MediaActionPayload { MediaActionPayload(op: .toggle) }
    public static func next() -> MediaActionPayload { MediaActionPayload(op: .next) }
    public static func previous() -> MediaActionPayload { MediaActionPayload(op: .previous) }
    public static func stop() -> MediaActionPayload { MediaActionPayload(op: .stop) }
    public static func seek(positionMs: Int64) -> MediaActionPayload {
        MediaActionPayload(op: .seek, positionMs: max(0, positionMs))
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "MEDIA_ACTION")
    }

    public static func decode(from data: Data) throws -> MediaActionPayload {
        try OpalCastJSONCodec.decode(MediaActionPayload.self, from: data, context: "MEDIA_ACTION")
    }
}
