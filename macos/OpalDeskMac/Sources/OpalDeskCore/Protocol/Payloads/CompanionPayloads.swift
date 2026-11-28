import Foundation

// MARK: - Phone Call Bridge Payloads (0x1E Downlink / 0x35 Uplink)

/// Lifecycle state reported by Android telephony in `0x1E CALL_STATE`.
public enum CallLifecycleState: String, Codable, Sendable, CaseIterable, Equatable, Hashable {
    case idle = "idle"
    case ringing = "ringing"
    case offhook = "offhook"
    case dialing = "dialing"
}

/// Downlink `0x1E CALL_STATE` UTF-8 JSON payload representing live Android phone call state.
public struct CallStatePayload: Codable, Sendable, Equatable, Hashable {
    public var state: CallLifecycleState
    public var number: String
    public var contactName: String
    public var startedAtMs: Int64
    public var isMuted: Bool
    public var isSpeakerOn: Bool
    public var canAnswer: Bool
    public var canReject: Bool
    public var canDial: Bool
    public var source: String

    public static let idle = CallStatePayload()

    public var isActive: Bool {
        state != .idle
    }

    public var displayCaller: String {
        if !contactName.isEmpty { return contactName }
        if !number.isEmpty { return number }
        return "未知来电"
    }

    public init(
        state: CallLifecycleState = .idle,
        number: String = "",
        contactName: String = "",
        startedAtMs: Int64 = 0,
        isMuted: Bool = false,
        isSpeakerOn: Bool = false,
        canAnswer: Bool = false,
        canReject: Bool = false,
        canDial: Bool = false,
        source: String = ""
    ) {
        self.state = state
        self.number = number
        self.contactName = contactName
        self.startedAtMs = max(0, startedAtMs)
        self.isMuted = isMuted
        self.isSpeakerOn = isSpeakerOn
        self.canAnswer = canAnswer
        self.canReject = canReject
        self.canDial = canDial
        self.source = source
    }

    private enum CodingKeys: String, CodingKey {
        case state, number, contactName, startedAtMs, isMuted, isSpeakerOn, canAnswer, canReject, canDial, source
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let rawState = try c.decodeIfPresent(String.self, forKey: .state) {
            let trimmed = rawState.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                state = .idle
            } else if let parsed = CallLifecycleState(rawValue: trimmed) {
                state = parsed
            } else {
                throw OpalCastCodecError.invalidJSON("CALL_STATE: unsupported state '\(trimmed)'")
            }
        } else {
            state = .idle
        }
        number = try c.decodeIfPresent(String.self, forKey: .number) ?? ""
        contactName = try c.decodeIfPresent(String.self, forKey: .contactName) ?? ""
        startedAtMs = max(0, try c.decodeIfPresent(Int64.self, forKey: .startedAtMs) ?? 0)
        isMuted = try c.decodeIfPresent(Bool.self, forKey: .isMuted) ?? false
        isSpeakerOn = try c.decodeIfPresent(Bool.self, forKey: .isSpeakerOn) ?? false
        canAnswer = try c.decodeIfPresent(Bool.self, forKey: .canAnswer) ?? false
        canReject = try c.decodeIfPresent(Bool.self, forKey: .canReject) ?? false
        canDial = try c.decodeIfPresent(Bool.self, forKey: .canDial) ?? false
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "CALL_STATE")
    }

    public static func decode(from data: Data) throws -> CallStatePayload {
        try OpalCastJSONCodec.decode(CallStatePayload.self, from: data, context: "CALL_STATE")
    }
}

/// Operation verb for uplink `0x35 CALL_ACTION`.
public enum CallActionOp: String, Codable, Sendable, CaseIterable, Equatable, Hashable {
    case answer = "answer"
    case reject = "reject"
    case hangup = "hangup"
    case toggleMute = "toggle_mute"
    case toggleSpeaker = "toggle_speaker"
    case dial = "dial"
    case rejectSms = "reject_sms"
}

/// Uplink `0x35 CALL_ACTION` UTF-8 JSON payload sent from Mac to control Android phone calls.
public struct CallActionPayload: Codable, Sendable, Equatable, Hashable {
    public var op: CallActionOp
    public var number: String?
    public var smsText: String?

    public init(
        op: CallActionOp,
        number: String? = nil,
        smsText: String? = nil
    ) {
        self.op = op
        self.number = number
        self.smsText = smsText
    }

    public static func answer() -> CallActionPayload { CallActionPayload(op: .answer) }
    public static func reject() -> CallActionPayload { CallActionPayload(op: .reject) }
    public static func hangup() -> CallActionPayload { CallActionPayload(op: .hangup) }
    public static func toggleMute() -> CallActionPayload { CallActionPayload(op: .toggleMute) }
    public static func toggleSpeaker() -> CallActionPayload { CallActionPayload(op: .toggleSpeaker) }
    public static func dial(number: String) -> CallActionPayload {
        CallActionPayload(op: .dial, number: number)
    }
    public static func rejectSms(text: String) -> CallActionPayload {
        CallActionPayload(op: .rejectSms, smsText: text)
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "CALL_ACTION")
    }

    public static func decode(from data: Data) throws -> CallActionPayload {
        try OpalCastJSONCodec.decode(CallActionPayload.self, from: data, context: "CALL_ACTION")
    }
}
