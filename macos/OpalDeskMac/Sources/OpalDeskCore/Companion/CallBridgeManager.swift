import Foundation
import os

/// Thread-safe bridge tracking live Android telephony state (`0x1E CALL_STATE`) and
/// emitting call control commands (`0x35 CALL_ACTION`) over the active companion/primary channel.
public final class CallBridgeManager: Sendable {
    private static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "companion.call")

    private struct MutableState {
        var currentState: CallStatePayload = .idle
        var sendFrame: (@Sendable (OpalCastFrame) -> Bool)?
        var onStateChanged: (@Sendable (CallStatePayload) -> Void)?
    }

    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init(
        sendFrame: (@Sendable (OpalCastFrame) -> Bool)? = nil,
        onStateChanged: (@Sendable (CallStatePayload) -> Void)? = nil
    ) {
        state.withLock { s in
            s.sendFrame = sendFrame
            s.onStateChanged = onStateChanged
        }
    }

    /// Snapshot of the latest call state received from Android (`.idle` when no active call).
    public var currentState: CallStatePayload {
        state.withLock { $0.currentState }
    }

    public func setFrameSender(_ sender: (@Sendable (OpalCastFrame) -> Bool)?) {
        state.withLock { $0.sendFrame = sender }
    }

    public func setOnStateChanged(_ handler: (@Sendable (CallStatePayload) -> Void)?) {
        state.withLock { $0.onStateChanged = handler }
    }

    /// Processes a decoded inbound `0x1E CALL_STATE` payload from Android.
    public func handleCallState(_ payload: CallStatePayload) {
        let onChanged: (@Sendable (CallStatePayload) -> Void)? = state.withLock { s in
            s.currentState = payload
            return s.onStateChanged
        }
        onChanged?(payload)
    }

    /// Decodes raw UTF-8 JSON bytes from a `0x1E CALL_STATE` frame and updates state.
    public func handleCallStateFrameData(_ data: Data) throws {
        let payload = try CallStatePayload.decode(from: data)
        handleCallState(payload)
    }

    /// Resets call state to `.idle` when the companion / cast transport disconnects.
    public func handleSessionDisconnected() {
        let (shouldNotify, onChanged): (Bool, (@Sendable (CallStatePayload) -> Void)?) = state.withLock { s in
            let changed = (s.currentState != .idle)
            s.currentState = .idle
            return (changed, s.onStateChanged)
        }
        if shouldNotify {
            onChanged?(.idle)
        }
    }

    // MARK: - Outbound Call Controls (`0x35 CALL_ACTION`)

    @discardableResult
    public func sendAction(_ payload: CallActionPayload) -> Bool {
        let sender = state.withLock { $0.sendFrame }
        guard let sender else { return false }
        do {
            let frame = try OpalCastFrame.callAction(payload)
            return sender(frame)
        } catch {
            Self.logger.error("Failed to encode CALL_ACTION: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    @discardableResult
    public func answer() -> Bool {
        sendAction(.answer())
    }

    @discardableResult
    public func reject() -> Bool {
        sendAction(.reject())
    }

    @discardableResult
    public func hangup() -> Bool {
        sendAction(.hangup())
    }

    @discardableResult
    public func toggleMute() -> Bool {
        sendAction(.toggleMute())
    }

    @discardableResult
    public func toggleSpeaker() -> Bool {
        sendAction(.toggleSpeaker())
    }

    @discardableResult
    public func dial(number: String) -> Bool {
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return sendAction(.dial(number: trimmed))
    }

    @discardableResult
    public func rejectWithSms(text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return sendAction(.rejectSms(text: trimmed))
    }
}
