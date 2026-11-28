import Foundation

/// A launchable or active Android app entry reported inside `0x15 APP_LIST`.
public struct RemoteAppInfo: Decodable, Sendable, Equatable, Identifiable, Hashable {
    public var pkg: String
    public var cls: String
    public var label: String
    public var runningOnExt: Bool
    public var taskId: Int
    public let iconBase64: String?
    public var openCount: Int
    public var userId: Int
    public var profileType: String

    public var id: String {
        let base = cls.isEmpty ? pkg : "\(pkg)/\(cls)"
        return userId > 0 ? "\(base)#u\(userId)" : base
    }

    public var appKey: String {
        Self.formatAppKey(pkg: pkg, userId: userId)
    }

    public var isSecondaryProfile: Bool {
        userId > 0
    }

    public var normalizedProfileType: String {
        Self.normalizeProfileType(profileType, userId: userId)
    }

    public var profileBadgeText: String? {
        Self.profileBadgeText(userId: userId, profileType: profileType)
    }

    public var displayLabel: String {
        label.isEmpty ? pkg : label
    }

    public var displayLabelWithProfile: String {
        if let badge = profileBadgeText {
            return "\(displayLabel) (\(badge))"
        }
        return displayLabel
    }

    public static func formatAppKey(pkg: String, userId: Int) -> String {
        let clean = pkg.trimmingCharacters(in: .whitespacesAndNewlines)
        return userId > 0 ? "\(clean)#u\(userId)" : clean
    }

    public static func parseAppKey(_ appKey: String) -> (pkg: String, userId: Int) {
        let trimmed = appKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let marker = trimmed.range(of: "#u", options: .backwards) else {
            return (trimmed, 0)
        }
        let rawId = trimmed[marker.upperBound...]
        guard let parsed = Int(rawId), parsed > 0 else {
            return (trimmed, 0)
        }
        return (String(trimmed[..<marker.lowerBound]), parsed)
    }

    public static func normalizeProfileType(_ raw: String, userId: Int) -> String {
        guard userId > 0 else { return "" }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch trimmed {
        case "clone", "work", "private", "profile":
            return trimmed
        default:
            return (userId == 999 || userId == 95 || userId == 128) ? "clone" : "profile"
        }
    }

    public static func profileBadgeText(userId: Int, profileType: String = "") -> String? {
        guard userId > 0 else { return nil }
        switch normalizeProfileType(profileType, userId: userId) {
        case "clone":
            return OpalL10n.text("profile.badge.clone")
        case "work":
            return OpalL10n.text("profile.badge.work")
        case "private":
            return OpalL10n.text("profile.badge.private")
        default:
            return OpalL10n.format("profile.badge.secondary_fmt", userId)
        }
    }

    public static func profileSearchKeywords(userId: Int, profileType: String = "") -> [String] {
        guard userId > 0 else { return [] }
        var words = ["u\(userId)", "user \(userId)", "分身", "多开", "双开", "clone", "dual"]
        switch normalizeProfileType(profileType, userId: userId) {
        case "work":
            words.append(contentsOf: ["工作", "work", "企业"])
        case "private":
            words.append(contentsOf: ["隐私", "私密", "private"])
        default:
            break
        }
        return words
    }

    public init(
        pkg: String,
        cls: String = "",
        label: String = "",
        runningOnExt: Bool = false,
        taskId: Int = -1,
        iconBase64: String? = nil,
        openCount: Int = 0,
        userId: Int = 0,
        profileType: String = ""
    ) {
        let normalizedUid = max(0, userId)
        self.pkg = pkg
        self.cls = cls
        self.label = label
        self.runningOnExt = runningOnExt
        self.taskId = taskId
        self.iconBase64 = iconBase64
        self.openCount = openCount
        self.userId = normalizedUid
        self.profileType = Self.normalizeProfileType(profileType, userId: normalizedUid)
    }

    private enum CodingKeys: String, CodingKey {
        case pkg, cls, label, runningOnExt, taskId, openCount, userId, profileType
        case iconBase64 = "icon"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pkg = try c.decodeIfPresent(String.self, forKey: .pkg) ?? ""
        cls = try c.decodeIfPresent(String.self, forKey: .cls) ?? ""
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        runningOnExt = try c.decodeIfPresent(Bool.self, forKey: .runningOnExt) ?? false
        taskId = try c.decodeIfPresent(Int.self, forKey: .taskId) ?? -1
        iconBase64 = try c.decodeIfPresent(String.self, forKey: .iconBase64)
        openCount = try c.decodeIfPresent(Int.self, forKey: .openCount) ?? 0
        let decodedUid = max(0, try c.decodeIfPresent(Int.self, forKey: .userId) ?? 0)
        userId = decodedUid
        let rawProfile = try c.decodeIfPresent(String.self, forKey: .profileType) ?? ""
        profileType = Self.normalizeProfileType(rawProfile, userId: decodedUid)
    }
}

/// Downlink `0x15 APP_LIST` UTF-8 JSON payload.
public struct AppListPayload: Decodable, Sendable, Equatable {
    public var apps: [RemoteAppInfo]

    public init(apps: [RemoteAppInfo] = []) {
        self.apps = apps
    }

    private enum CodingKeys: String, CodingKey {
        case apps
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        apps = try c.decodeIfPresent([RemoteAppInfo].self, forKey: .apps) ?? []
    }

    public static func decode(from data: Data) throws -> AppListPayload {
        try OpalCastJSONCodec.decode(AppListPayload.self, from: data, context: "APP_LIST")
    }
}

/// Typed command verbs for uplink `0x2B WINDOW_ACTION` payloads.
public enum WindowActionKind: String, Codable, Sendable, CaseIterable, Equatable {
    case setAdaptiveSingle = "set_adaptive_single"
    case setSingleWidth = "set_single_width"
    case launchApp = "launch_app"
    case setTiling = "set_tiling"
    case reloadAppDpi = "reload_app_dpi"
    case returnToDesktop = "return_to_desktop"
    case setInputBackend = "set_input_backend"
    case focusWindow = "focus_window"
    case minimizeWindow = "minimize_window"
    case closeWindow = "close_window"
}

/// Uplink `0x2B WINDOW_ACTION` UTF-8 JSON payload covering adaptive single-window sizing,
/// app launching, tiling topologies, DPI reload, return-to-desktop, and input backend selection.
public struct WindowActionPayload: Encodable, Sendable, Equatable {
    public var action: WindowActionKind
    public var enabled: Bool?
    public var defaultWidthDp: Int?
    public var pkg: String?
    public var cls: String?
    public var widthPct: Int?
    public var remember: Bool?
    public var mode: String?
    public var slot: Int?
    public var taskId: Int?
    public var topology: String?
    public var pkgs: [String]?
    public var ratios: [Int]?
    public var backend: String?
    public var userId: Int?

    public init(
        action: WindowActionKind,
        enabled: Bool? = nil,
        defaultWidthDp: Int? = nil,
        pkg: String? = nil,
        cls: String? = nil,
        widthPct: Int? = nil,
        remember: Bool? = nil,
        mode: String? = nil,
        slot: Int? = nil,
        taskId: Int? = nil,
        topology: String? = nil,
        pkgs: [String]? = nil,
        ratios: [Int]? = nil,
        backend: String? = nil,
        userId: Int? = nil
    ) {
        self.action = action
        self.enabled = enabled
        self.defaultWidthDp = defaultWidthDp
        self.pkg = pkg
        self.cls = cls
        self.widthPct = widthPct
        self.remember = remember
        self.mode = mode
        self.slot = slot
        self.taskId = taskId
        self.topology = topology
        self.pkgs = pkgs
        self.ratios = ratios
        self.backend = backend
        self.userId = (userId ?? 0) > 0 ? userId : nil
    }

    public static func setAdaptiveSingle(enabled: Bool, defaultWidthDp: Int) -> WindowActionPayload {
        WindowActionPayload(
            action: .setAdaptiveSingle,
            enabled: enabled,
            defaultWidthDp: DisplayPresets.clampSingleWidthDp(defaultWidthDp)
        )
    }

    public static func setSingleWidth(pkg: String, widthPct: Int, remember: Bool = true) -> WindowActionPayload {
        WindowActionPayload(
            action: .setSingleWidth,
            pkg: pkg,
            widthPct: DisplayPresets.clampSingleWidthPct(widthPct),
            remember: remember
        )
    }

    public static func launchApp(
        pkg: String,
        cls: String = "",
        mode: OpalCastLaunchMode = .adaptive,
        slot: Int = 0,
        userId: Int = 0
    ) -> WindowActionPayload {
        WindowActionPayload(
            action: .launchApp,
            pkg: pkg,
            cls: cls,
            mode: mode.rawValue,
            slot: slot,
            userId: userId > 0 ? userId : nil
        )
    }

    public static func setTiling(
        topology: OpalCastTilingTopology,
        pkgs: [String] = [],
        ratios: [Int]? = nil
    ) -> WindowActionPayload {
        WindowActionPayload(
            action: .setTiling,
            topology: topology.rawValue,
            pkgs: pkgs,
            ratios: ratios ?? topology.defaultRatios
        )
    }

    public static func reloadAppDpi(pkg: String, userId: Int = 0) -> WindowActionPayload {
        WindowActionPayload(action: .reloadAppDpi, pkg: pkg, userId: userId > 0 ? userId : nil)
    }

    public static func returnToDesktop(pkg: String, taskId: Int = -1, userId: Int = 0) -> WindowActionPayload {
        WindowActionPayload(action: .returnToDesktop, pkg: pkg, taskId: taskId, userId: userId > 0 ? userId : nil)
    }

    public static func setInputBackend(_ backend: OpalCastInputBackend) -> WindowActionPayload {
        WindowActionPayload(action: .setInputBackend, backend: backend.rawValue)
    }

    public static func focusWindow(taskId: Int, pkg: String? = nil) -> WindowActionPayload {
        WindowActionPayload(action: .focusWindow, pkg: pkg, taskId: taskId)
    }

    public static func minimizeWindow(taskId: Int, pkg: String? = nil) -> WindowActionPayload {
        WindowActionPayload(action: .minimizeWindow, pkg: pkg, taskId: taskId)
    }

    public static func closeWindow(taskId: Int, pkg: String? = nil) -> WindowActionPayload {
        WindowActionPayload(action: .closeWindow, pkg: pkg, taskId: taskId)
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "WINDOW_ACTION")
    }
}
