import OpalDeskCore
import Foundation
import Observation

/// UserDefaults-backed preferences for `OpalDeskMac`.
@MainActor
@Observable
public final class PreferencesStore {
    private enum Key {
        static let preferredCodec = "opaldesk.pref.codec"
        static let bitrateKbps = "opaldesk.pref.bitrateKbps"
        static let defaultDisplayMode = "opaldesk.pref.displayMode"
        static let defaultDPI = "opaldesk.pref.dpi"
        static let targetFPS = "opaldesk.pref.fps"
        static let audioEnabled = "opaldesk.pref.audioEnabled"
        static let mapCommandToCtrl = "opaldesk.pref.mapCommandToCtrl"
        static let clipboardSyncEnabled = "opaldesk.pref.clipboardSyncEnabled"
        static let resolutionPreset = "opaldesk.pref.resolutionPreset"
        static let customAdbPath = "opaldesk.pref.customAdbPath"
        static let showHUDStats = "opaldesk.pref.showHUDStats"
        static let adaptiveSingleEnabled = "opaldesk.pref.adaptiveSingleEnabled"
        static let defaultSingleWidthDp = "opaldesk.pref.defaultSingleWidthDp"
        static let inputBackend = "opaldesk.pref.inputBackend"
        static let pinnedDockPackages = "opaldesk.pref.pinnedDockPackages"
        static let dockAutoHide = "opaldesk.pref.dockAutoHide"
        static let usbTetherDiscoveryEnabled = "opaldesk.pref.usbTetherDiscoveryEnabled"
        static let hideLocalCursor = "opaldesk.pref.hideLocalCursor"
        static let companionAutoConnectEnabled = "opaldesk.pref.companionAutoConnectEnabled"
        static let notificationsSyncEnabled = "opaldesk.pref.notificationsSyncEnabled"
        static let autoCopyOtpEnabled = "opaldesk.pref.autoCopyOtpEnabled"
        static let mediaRemoteControlEnabled = "opaldesk.pref.mediaRemoteControlEnabled"
        static let enableEdgeNavigationGestures = "opaldesk.pref.enableEdgeNavigationGestures"
    }

    private let defaults: UserDefaults

    public var preferredCodec: OpalCastVideoCodec {
        didSet { defaults.set(Int(preferredCodec.rawValue), forKey: Key.preferredCodec) }
    }

    public var bitrateKbps: Int {
        didSet { defaults.set(bitrateKbps, forKey: Key.bitrateKbps) }
    }

    public var defaultDisplayMode: OpalCastDisplayMode {
        didSet { defaults.set(Int(defaultDisplayMode.rawValue), forKey: Key.defaultDisplayMode) }
    }

    public var defaultDPI: Int {
        didSet { defaults.set(defaultDPI, forKey: Key.defaultDPI) }
    }

    public var targetFPS: Int {
        didSet { defaults.set(targetFPS, forKey: Key.targetFPS) }
    }

    public var audioEnabled: Bool {
        didSet { defaults.set(audioEnabled, forKey: Key.audioEnabled) }
    }

    public var mapCommandToCtrl: Bool {
        didSet { defaults.set(mapCommandToCtrl, forKey: Key.mapCommandToCtrl) }
    }

    public var clipboardSyncEnabled: Bool {
        didSet { defaults.set(clipboardSyncEnabled, forKey: Key.clipboardSyncEnabled) }
    }

    public var resolutionPreset: DisplayResolutionPreset {
        didSet { defaults.set(resolutionPreset.rawValue, forKey: Key.resolutionPreset) }
    }

    public var customAdbPath: String {
        didSet { defaults.set(customAdbPath, forKey: Key.customAdbPath) }
    }

    public var showHUDStats: Bool {
        didSet { defaults.set(showHUDStats, forKey: Key.showHUDStats) }
    }

    public var adaptiveSingleEnabled: Bool {
        didSet { defaults.set(adaptiveSingleEnabled, forKey: Key.adaptiveSingleEnabled) }
    }

    public var defaultSingleWidthDp: Int {
        didSet { defaults.set(defaultSingleWidthDp, forKey: Key.defaultSingleWidthDp) }
    }

    public var inputBackend: OpalCastInputBackend {
        didSet { defaults.set(inputBackend.rawValue, forKey: Key.inputBackend) }
    }

    public var pinnedDockPackages: [String] {
        didSet { defaults.set(pinnedDockPackages, forKey: Key.pinnedDockPackages) }
    }

    public var dockAutoHide: Bool {
        didSet { defaults.set(dockAutoHide, forKey: Key.dockAutoHide) }
    }

    /// Beta opt-in (default OFF): discovers Android OpalDesk hosts over macOS USB Tethering (CDC-NCM).
    public var usbTetherDiscoveryEnabled: Bool {
        didSet { defaults.set(usbTetherDiscoveryEnabled, forKey: Key.usbTetherDiscoveryEnabled) }
    }

    /// Hides the macOS cursor over the cast canvas when the remote Android host renders its own pointer.
    public var hideLocalCursor: Bool {
        didSet { defaults.set(hideLocalCursor, forKey: Key.hideLocalCursor) }
    }

    /// Automatically maintains a lightweight background `.companion` link to trusted LAN devices.
    public var companionAutoConnectEnabled: Bool {
        didSet { defaults.set(companionAutoConnectEnabled, forKey: Key.companionAutoConnectEnabled) }
    }

    /// Mirrors Android status bar notifications into macOS Notification Center and the Menu Bar.
    public var notificationsSyncEnabled: Bool {
        didSet { defaults.set(notificationsSyncEnabled, forKey: Key.notificationsSyncEnabled) }
    }

    /// Automatically copies detected SMS/app OTP verification codes to the macOS clipboard.
    public var autoCopyOtpEnabled: Bool {
        didSet { defaults.set(autoCopyOtpEnabled, forKey: Key.autoCopyOtpEnabled) }
    }

    /// Mirrors active Android media playback to `MPNowPlayingInfoCenter` and forwards media keys.
    public var mediaRemoteControlEnabled: Bool {
        didSet { defaults.set(mediaRemoteControlEnabled, forKey: Key.mediaRemoteControlEnabled) }
    }

    /// Enables two-finger trackpad edge swipe navigation (Predictive Back, Home, Notification shade) and rotation.
    public var enableEdgeNavigationGestures: Bool {
        didSet { defaults.set(enableEdgeNavigationGestures, forKey: Key.enableEdgeNavigationGestures) }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        let codecRaw = defaults.object(forKey: Key.preferredCodec) as? Int
        self.preferredCodec = codecRaw.flatMap { OpalCastVideoCodec(rawValue: UInt8($0)) } ?? .hevc

        let storedBitrate = defaults.integer(forKey: Key.bitrateKbps)
        self.bitrateKbps = storedBitrate > 0
            ? DisplayPresets.clampBitrateKbps(storedBitrate)
            : DisplayPresets.defaultBitrateKbps

        let modeRaw = defaults.object(forKey: Key.defaultDisplayMode) as? Int
        let parsedMode = modeRaw.flatMap { OpalCastDisplayMode(rawValue: UInt8($0)) }
        self.defaultDisplayMode = (parsedMode == .mirror) ? .mirror : .dex

        let storedDPI = defaults.integer(forKey: Key.defaultDPI)
        self.defaultDPI = DisplayPresets.dpiOptions.contains(storedDPI)
            ? storedDPI
            : DisplayPresets.defaultDPI

        let storedFPS = defaults.integer(forKey: Key.targetFPS)
        self.targetFPS = DisplayPresets.fpsOptions.contains(storedFPS)
            ? storedFPS
            : DisplayPresets.defaultFPS

        self.audioEnabled = defaults.object(forKey: Key.audioEnabled) as? Bool ?? true
        self.mapCommandToCtrl = defaults.object(forKey: Key.mapCommandToCtrl) as? Bool ?? true
        self.clipboardSyncEnabled = defaults.object(forKey: Key.clipboardSyncEnabled) as? Bool ?? true

        let presetRaw = defaults.string(forKey: Key.resolutionPreset) ?? DisplayResolutionPreset.matchWindow.rawValue
        self.resolutionPreset = DisplayResolutionPreset(rawValue: presetRaw) ?? .matchWindow

        self.customAdbPath = defaults.string(forKey: Key.customAdbPath) ?? ""
        self.showHUDStats = defaults.object(forKey: Key.showHUDStats) as? Bool ?? false

        self.adaptiveSingleEnabled = defaults.object(forKey: Key.adaptiveSingleEnabled) as? Bool ?? false
        let storedWidthDp = defaults.integer(forKey: Key.defaultSingleWidthDp)
        self.defaultSingleWidthDp = storedWidthDp > 0
            ? DisplayPresets.clampSingleWidthDp(storedWidthDp)
            : DisplayPresets.defaultSingleWidthDp

        let backendRaw = defaults.string(forKey: Key.inputBackend) ?? OpalCastInputBackend.auto.rawValue
        self.inputBackend = OpalCastInputBackend(rawValue: backendRaw) ?? .auto

        self.pinnedDockPackages = defaults.stringArray(forKey: Key.pinnedDockPackages) ?? []
        self.dockAutoHide = defaults.object(forKey: Key.dockAutoHide) as? Bool ?? false
        self.usbTetherDiscoveryEnabled = defaults.object(forKey: Key.usbTetherDiscoveryEnabled) as? Bool ?? false
        self.hideLocalCursor = defaults.object(forKey: Key.hideLocalCursor) as? Bool ?? false
        self.companionAutoConnectEnabled = defaults.object(forKey: Key.companionAutoConnectEnabled) as? Bool ?? true
        self.notificationsSyncEnabled = defaults.object(forKey: Key.notificationsSyncEnabled) as? Bool ?? true
        self.autoCopyOtpEnabled = defaults.object(forKey: Key.autoCopyOtpEnabled) as? Bool ?? false
        self.mediaRemoteControlEnabled = defaults.object(forKey: Key.mediaRemoteControlEnabled) as? Bool ?? true
        self.enableEdgeNavigationGestures = defaults.object(forKey: Key.enableEdgeNavigationGestures) as? Bool ?? true
    }
}
