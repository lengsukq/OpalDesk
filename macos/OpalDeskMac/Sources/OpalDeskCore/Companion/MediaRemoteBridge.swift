import AppKit
import CoreGraphics
import Foundation
import ImageIO
@preconcurrency import MediaPlayer
import os

// MARK: - Testable NowPlaying & Remote Command Backend Protocol

/// Abstraction over macOS `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`,
/// allowing `MediaRemoteBridge` to be unit-tested deterministically under `swift test`.
public protocol NowPlayingBackend: Sendable {
    func updateNowPlaying(state: MediaStatePayload, artworkData: Data?)
    func clearNowPlaying()
    func registerCommands(onAction: @escaping @Sendable (MediaActionPayload) -> Void)
    func unregisterCommands()
}

/// No-op backend for headless/test environments or when system media bridge is disabled.
public final class NoopNowPlayingBackend: NowPlayingBackend, Sendable {
    public init() {}
    public func updateNowPlaying(state: MediaStatePayload, artworkData: Data?) {}
    public func clearNowPlaying() {}
    public func registerCommands(onAction: @escaping @Sendable (MediaActionPayload) -> Void) {}
    public func unregisterCommands() {}
}

/// Production macOS backend bridging Android `MediaSession` state into `MPNowPlayingInfoCenter`
/// and forwarding hardware media keys / Control Center actions from `MPRemoteCommandCenter`.
public final class SystemMediaPlayerBackend: NowPlayingBackend, @unchecked Sendable {
    @MainActor
    private final class MainThreadState {
        var commandTokens: [(command: MPRemoteCommand, token: Any)] = []
        var cachedArtworkBase64: String = ""
        var cachedArtwork: MPMediaItemArtwork?

        nonisolated init() {}
    }

    private let mainState = MainThreadState()

    public init() {}

    public func updateNowPlaying(state: MediaStatePayload, artworkData: Data?) {
        // Pre-decode CGImage off main thread (CGImage is immutable and Sendable).
        let decodedCGImage: CGImage? = artworkData.flatMap(Self.decodeCGImage(from:))
        let artworkKey = state.artworkBase64

        DispatchQueue.main.async { [mainState] in
            MainActor.assumeIsolated {
                let infoCenter = MPNowPlayingInfoCenter.default()
                var info: [String: Any] = [:]

                if !state.title.isEmpty {
                    info[MPMediaItemPropertyTitle] = state.title
                }
                if !state.artist.isEmpty {
                    info[MPMediaItemPropertyArtist] = state.artist
                } else if !state.appLabel.isEmpty {
                    info[MPMediaItemPropertyArtist] = state.appLabel
                }
                if !state.album.isEmpty {
                    info[MPMediaItemPropertyAlbumTitle] = state.album
                }
                if state.durationMs > 0 {
                    info[MPMediaItemPropertyPlaybackDuration] = Double(state.durationMs) / 1000.0
                }
                if state.positionMs >= 0 {
                    info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(state.positionMs) / 1000.0
                }
                info[MPNowPlayingInfoPropertyPlaybackRate] = state.playing ? 1.0 : 0.0
                info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0

                if !artworkKey.isEmpty {
                    if mainState.cachedArtworkBase64 != artworkKey {
                        mainState.cachedArtworkBase64 = artworkKey
                        if let cgImage = decodedCGImage {
                            let size = CGSize(width: cgImage.width, height: cgImage.height)
                            mainState.cachedArtwork = MPMediaItemArtwork(boundsSize: size) { _ in
                                NSImage(cgImage: cgImage, size: size)
                            }
                        } else {
                            mainState.cachedArtwork = nil
                        }
                    }
                    if let artwork = mainState.cachedArtwork {
                        info[MPMediaItemPropertyArtwork] = artwork
                    }
                } else {
                    mainState.cachedArtworkBase64 = ""
                    mainState.cachedArtwork = nil
                }

                infoCenter.nowPlayingInfo = info
                infoCenter.playbackState = state.playing ? .playing : .paused
            }
        }
    }

    public func clearNowPlaying() {
        DispatchQueue.main.async { [mainState] in
            MainActor.assumeIsolated {
                mainState.cachedArtworkBase64 = ""
                mainState.cachedArtwork = nil
                let infoCenter = MPNowPlayingInfoCenter.default()
                infoCenter.nowPlayingInfo = nil
                infoCenter.playbackState = .stopped
            }
        }
    }

    public func registerCommands(onAction: @escaping @Sendable (MediaActionPayload) -> Void) {
        DispatchQueue.main.async { [mainState] in
            MainActor.assumeIsolated {
                Self.unregisterTokensLocked(mainState)
                let center = MPRemoteCommandCenter.shared()

                @MainActor
                func bind(_ command: MPRemoteCommand, payload: MediaActionPayload) {
                    command.isEnabled = true
                    let token = command.addTarget { _ in
                        onAction(payload)
                        return .success
                    }
                    mainState.commandTokens.append((command, token))
                }

                bind(center.playCommand, payload: .play())
                bind(center.pauseCommand, payload: .pause())
                bind(center.togglePlayPauseCommand, payload: .toggle())
                bind(center.nextTrackCommand, payload: .next())
                bind(center.previousTrackCommand, payload: .previous())
                bind(center.stopCommand, payload: .stop())

                let seekCommand = center.changePlaybackPositionCommand
                seekCommand.isEnabled = true
                let seekToken = seekCommand.addTarget { event in
                    guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else {
                        return .commandFailed
                    }
                    let ms = max(0, Int64((posEvent.positionTime * 1000.0).rounded()))
                    onAction(.seek(positionMs: ms))
                    return .success
                }
                mainState.commandTokens.append((seekCommand, seekToken))
            }
        }
    }

    public func unregisterCommands() {
        DispatchQueue.main.async { [mainState] in
            MainActor.assumeIsolated {
                Self.unregisterTokensLocked(mainState)
            }
        }
    }

    @MainActor
    private static func unregisterTokensLocked(_ mainState: MainThreadState) {
        for entry in mainState.commandTokens {
            entry.command.removeTarget(entry.token)
            entry.command.isEnabled = false
        }
        mainState.commandTokens.removeAll()
    }

    private static func decodeCGImage(from data: Data) -> CGImage? {
        guard !data.isEmpty,
              let source = CGImageSourceCreateWithData(data as CFData, nil)
        else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}

// MARK: - MediaRemoteBridge

/// Mirrors Android active media playback (`0x1A MEDIA_STATE`) into macOS Now Playing
/// and forwards remote control commands back to Android as `0x32 MEDIA_ACTION`.
public final class MediaRemoteBridge: Sendable {
    private struct MutableState {
        var isEnabled: Bool = true
        var isStarted: Bool = false
        var currentState: MediaStatePayload?
        var onMediaAction: (@Sendable (MediaActionPayload) -> Void)?
        var onStateChanged: (@Sendable (MediaStatePayload?) -> Void)?
    }

    private let backend: any NowPlayingBackend
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    /// Uses `SystemMediaPlayerBackend` inside a real `.app` bundle and falls back safely to
    /// `NoopNowPlayingBackend` in non-bundle contexts unless an explicit `backend` is injected.
    public init(backend: (any NowPlayingBackend)? = nil) {
        if let backend {
            self.backend = backend
        } else if NotificationBridgeManager.isRunningInAppBundle() {
            self.backend = SystemMediaPlayerBackend()
        } else {
            self.backend = NoopNowPlayingBackend()
        }
    }

    deinit {
        stop()
    }

    public var isEnabled: Bool {
        get { state.withLock { $0.isEnabled } }
        set {
            let shouldClear: Bool = state.withLock { s in
                s.isEnabled = newValue
                return !newValue
            }
            if shouldClear {
                backend.clearNowPlaying()
            } else if let current = currentState, current.active {
                backend.updateNowPlaying(state: current, artworkData: current.artworkData)
            }
        }
    }

    public var currentState: MediaStatePayload? {
        state.withLock { $0.currentState }
    }

    public func setOnStateChanged(_ handler: (@Sendable (MediaStatePayload?) -> Void)?) {
        state.withLock { $0.onStateChanged = handler }
    }

    /// Registers `MPRemoteCommandCenter` handlers and routes emitted `MediaActionPayload` values to `onMediaAction`.
    public func start(onMediaAction: @escaping @Sendable (MediaActionPayload) -> Void) {
        let needsRegister: Bool = state.withLock { s in
            s.onMediaAction = onMediaAction
            let wasStarted = s.isStarted
            s.isStarted = true
            return !wasStarted
        }
        if needsRegister {
            backend.registerCommands { [weak self] action in
                self?.dispatchCommandAction(action)
            }
        }
    }

    /// Unregisters all remote command handlers and clears macOS Now Playing metadata.
    public func stop() {
        let (wasStarted, onChanged): (Bool, (@Sendable (MediaStatePayload?) -> Void)?) = state.withLock { s in
            let started = s.isStarted
            s.isStarted = false
            s.onMediaAction = nil
            s.currentState = nil
            return (started, s.onStateChanged)
        }
        if wasStarted {
            backend.unregisterCommands()
        }
        backend.clearNowPlaying()
        onChanged?(nil)
    }

    /// Applies an inbound `0x1A MEDIA_STATE` update from Android.
    public func applyRemoteMediaState(_ payload: MediaStatePayload) {
        let (enabled, publishedState, onChanged): (
            Bool,
            MediaStatePayload?,
            (@Sendable (MediaStatePayload?) -> Void)?
        ) = state.withLock { s in
            let next: MediaStatePayload? = payload.active ? payload : nil
            s.currentState = next
            return (s.isEnabled, next, s.onStateChanged)
        }

        if enabled, let publishedState {
            backend.updateNowPlaying(state: publishedState, artworkData: publishedState.artworkData)
        } else {
            backend.clearNowPlaying()
        }
        onChanged?(publishedState)
    }

    /// Triggers a playback control action (from Mac UI buttons or unit tests).
    public func sendAction(_ action: MediaActionPayload) {
        dispatchCommandAction(action)
    }

    private func dispatchCommandAction(_ action: MediaActionPayload) {
        let handler: (@Sendable (MediaActionPayload) -> Void)? = state.withLock { s in
            guard s.isEnabled else { return nil }
            return s.onMediaAction
        }
        handler?(action)
    }
}
