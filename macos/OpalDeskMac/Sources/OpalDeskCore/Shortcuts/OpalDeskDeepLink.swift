import Foundation

/// Deep link codec & strict input validator for `opaldesk://popout?...` URLs.
public enum OpalDeskDeepLink {
    public static let scheme = "opaldesk"
    public static let popoutHost = "popout"

    public static let maxPackageNameLength = 255
    public static let maxActivityClassLength = 255
    public static let maxLabelLength = 120
    public static let maxDeviceKeyLength = 128
    public static let maxDeviceNameLength = 120

    /// Structured payload decoded from an `opaldesk://popout` URL.
    public struct PopoutRequest: Sendable, Equatable, Hashable {
        public let packageName: String
        public let activityClass: String?
        public let label: String?
        public let deviceKey: String?
        public let deviceName: String?

        public init(
            packageName: String,
            activityClass: String? = nil,
            label: String? = nil,
            deviceKey: String? = nil,
            deviceName: String? = nil
        ) {
            self.packageName = packageName
            self.activityClass = activityClass
            self.label = label
            self.deviceKey = deviceKey
            self.deviceName = deviceName
        }
    }

    /// Supported routes under the `opaldesk://` URL scheme.
    public enum Route: Sendable, Equatable, Hashable {
        case popout(PopoutRequest)
    }

    /// Validation & decoding errors for `opaldesk://` URLs.
    public enum ValidationError: Error, LocalizedError, Sendable, Equatable {
        case invalidURL(String)
        case unsupportedScheme(String?)
        case unsupportedHost(String?)
        case missingPackageName
        case invalidPackageName(String)
        case invalidActivityClass(String)
        case invalidDeviceKey(String)
        case containsForbiddenControlCharacters(field: String)
        case valueTooLong(field: String, maxLength: Int)

        public var errorDescription: String? {
            switch self {
            case let .invalidURL(raw):
                return "无效的 Deep Link URL：\(raw)"
            case let .unsupportedScheme(scheme):
                return "不支持的 URL Scheme（\(scheme ?? "空")），预期 \(OpalDeskDeepLink.scheme)://"
            case let .unsupportedHost(host):
                return "不支持的 Deep Link 动作（\(host ?? "空")）"
            case .missingPackageName:
                return "Deep Link 缺少必需的 pkg 参数"
            case let .invalidPackageName(pkg):
                return "非法的 Android 包名：\(pkg)"
            case let .invalidActivityClass(cls):
                return "非法的 Android Activity 类名：\(cls)"
            case let .invalidDeviceKey(key):
                return "非法的设备标识：\(key)"
            case let .containsForbiddenControlCharacters(field):
                return "参数 \(field) 包含非法的控制字符"
            case let .valueTooLong(field, maxLength):
                return "参数 \(field) 长度超过上限（最多 \(maxLength) 个字符）"
            }
        }
    }

    /// Builds a strictly validated `opaldesk://popout?...` URL.
    public static func buildPopoutURL(
        packageName: String,
        activityClass: String? = nil,
        label: String? = nil,
        deviceKey: String? = nil,
        deviceName: String? = nil
    ) throws -> URL {
        let cleanPkg = try validatePackageName(packageName)
        let cleanCls = try validateOptionalActivityClass(activityClass)
        let cleanLabel = try validateOptionalDisplayText(label, field: "label", maxLength: maxLabelLength)
        let cleanDevice = try validateOptionalDeviceKey(deviceKey)
        let cleanDeviceName = try validateOptionalDisplayText(deviceName, field: "deviceName", maxLength: maxDeviceNameLength)

        var components = URLComponents()
        components.scheme = scheme
        components.host = popoutHost

        var items: [URLQueryItem] = [
            URLQueryItem(name: "pkg", value: cleanPkg)
        ]
        if let cleanCls {
            items.append(URLQueryItem(name: "cls", value: cleanCls))
        }
        if let cleanLabel {
            items.append(URLQueryItem(name: "label", value: cleanLabel))
        }
        if let cleanDevice {
            items.append(URLQueryItem(name: "device", value: cleanDevice))
        }
        if let cleanDeviceName {
            items.append(URLQueryItem(name: "deviceName", value: cleanDeviceName))
        }

        // Encode query items strictly so `&`, `=`, `+`, `#`, `;`, and non-ASCII text round-trip safely.
        components.percentEncodedQuery = items
            .map { "\(percentEncodeQueryComponent($0.name))=\(percentEncodeQueryComponent($0.value ?? ""))" }
            .joined(separator: "&")

        guard let url = components.url else {
            throw ValidationError.invalidURL(cleanPkg)
        }
        return url
    }

    /// Builds a validated `opaldesk://popout` URL from a `PopoutRequest`.
    public static func buildPopoutURL(for request: PopoutRequest) throws -> URL {
        try buildPopoutURL(
            packageName: request.packageName,
            activityClass: request.activityClass,
            label: request.label,
            deviceKey: request.deviceKey,
            deviceName: request.deviceName
        )
    }

    /// Parses and validates an `opaldesk://` URL string.
    public static func parse(_ urlString: String) throws -> Route {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              !containsControlCharacters(trimmed),
              let url = URL(string: trimmed)
        else {
            throw ValidationError.invalidURL(urlString)
        }
        return try parse(url)
    }

    /// Parses and validates an `opaldesk://` URL.
    public static func parse(_ url: URL) throws -> Route {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw ValidationError.invalidURL(url.absoluteString)
        }
        guard components.scheme?.lowercased() == scheme else {
            throw ValidationError.unsupportedScheme(components.scheme)
        }
        guard components.host?.lowercased() == popoutHost else {
            throw ValidationError.unsupportedHost(components.host)
        }

        var params: [String: String] = [:]
        for item in components.queryItems ?? [] {
            if params[item.name] == nil {
                params[item.name] = item.value
            }
        }

        guard let rawPkg = params["pkg"], !rawPkg.isEmpty else {
            throw ValidationError.missingPackageName
        }

        let cleanPkg = try validatePackageName(rawPkg)
        let cleanCls = try validateOptionalActivityClass(params["cls"])
        let cleanLabel = try validateOptionalDisplayText(params["label"], field: "label", maxLength: maxLabelLength)
        let cleanDevice = try validateOptionalDeviceKey(params["device"])
        let cleanDeviceName = try validateOptionalDisplayText(params["deviceName"], field: "deviceName", maxLength: maxDeviceNameLength)

        return .popout(
            PopoutRequest(
                packageName: cleanPkg,
                activityClass: cleanCls,
                label: cleanLabel,
                deviceKey: cleanDevice,
                deviceName: cleanDeviceName
            )
        )
    }

    // MARK: - Strict Validation Rules

    /// Validates an Android application package name (`com.example.app`).
    public static func isValidPackageName(_ value: String) -> Bool {
        guard !value.isEmpty, value.count <= maxPackageNameLength else { return false }
        let segments = value.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count >= 2 else { return false }
        for (idx, seg) in segments.enumerated() {
            guard !seg.isEmpty else { return false }
            guard let first = seg.unicodeScalars.first else { return false }
            if idx == 0 {
                guard isAsciiLetter(first) else { return false }
            } else {
                guard isAsciiLetter(first) || first == "_" else { return false }
            }
            for scalar in seg.unicodeScalars {
                guard isAsciiLetter(scalar) || isAsciiDigit(scalar) || scalar == "_" else {
                    return false
                }
            }
        }
        return true
    }

    /// Validates an Android activity class name (`.MainActivity` or `com.example.MainActivity$Inner`).
    public static func isValidActivityClass(_ value: String) -> Bool {
        guard !value.isEmpty, value.count <= maxActivityClassLength else { return false }
        guard !value.contains("..") else { return false }
        guard !value.hasSuffix(".") else { return false }
        let trimmedStart = value.hasPrefix(".") ? String(value.dropFirst()) : value
        guard !trimmedStart.isEmpty else { return false }

        let segments = trimmedStart.split(separator: ".", omittingEmptySubsequences: false)
        for seg in segments {
            guard !seg.isEmpty else { return false }
            guard let first = seg.unicodeScalars.first,
                  isAsciiLetter(first) || first == "_"
            else {
                return false
            }
            for scalar in seg.unicodeScalars {
                guard isAsciiLetter(scalar) || isAsciiDigit(scalar) || scalar == "_" || scalar == "$" else {
                    return false
                }
            }
        }
        return true
    }

    private static func validatePackageName(_ value: String) throws -> String {
        if containsControlCharacters(value) {
            throw ValidationError.containsForbiddenControlCharacters(field: "pkg")
        }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw ValidationError.missingPackageName
        }
        guard trimmed.count <= maxPackageNameLength else {
            throw ValidationError.valueTooLong(field: "pkg", maxLength: maxPackageNameLength)
        }
        guard isValidPackageName(trimmed) else {
            throw ValidationError.invalidPackageName(trimmed)
        }
        return trimmed
    }

    private static func validateOptionalActivityClass(_ value: String?) throws -> String? {
        guard let value else { return nil }
        if containsControlCharacters(value) {
            throw ValidationError.containsForbiddenControlCharacters(field: "cls")
        }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard trimmed.count <= maxActivityClassLength else {
            throw ValidationError.valueTooLong(field: "cls", maxLength: maxActivityClassLength)
        }
        guard isValidActivityClass(trimmed) else {
            throw ValidationError.invalidActivityClass(trimmed)
        }
        return trimmed
    }

    private static func validateOptionalDeviceKey(_ value: String?) throws -> String? {
        guard let value else { return nil }
        if containsControlCharacters(value) {
            throw ValidationError.containsForbiddenControlCharacters(field: "device")
        }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard trimmed.count <= maxDeviceKeyLength else {
            throw ValidationError.valueTooLong(field: "device", maxLength: maxDeviceKeyLength)
        }
        // Reject path separators and shell metacharacters while allowing Bonjour/ADB/TLS keys.
        let forbidden: Set<Unicode.Scalar> = ["/", "\\", ";", "|", "&", "$", "`", "\"", "'", "<", ">", "*"]
        for scalar in trimmed.unicodeScalars {
            if forbidden.contains(scalar) {
                throw ValidationError.invalidDeviceKey(trimmed)
            }
        }
        return trimmed
    }

    private static func validateOptionalDisplayText(
        _ value: String?,
        field: String,
        maxLength: Int
    ) throws -> String? {
        guard let value else { return nil }
        if containsControlCharacters(value) {
            throw ValidationError.containsForbiddenControlCharacters(field: field)
        }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard trimmed.count <= maxLength else {
            throw ValidationError.valueTooLong(field: field, maxLength: maxLength)
        }
        return trimmed
    }

    private static func containsControlCharacters(_ string: String) -> Bool {
        string.unicodeScalars.contains { scalar in
            scalar.value < 0x20 || scalar.value == 0x7F
        }
    }

    private static func isAsciiLetter(_ scalar: Unicode.Scalar) -> Bool {
        (scalar.value >= 0x41 && scalar.value <= 0x5A) || (scalar.value >= 0x61 && scalar.value <= 0x7A)
    }

    private static func isAsciiDigit(_ scalar: Unicode.Scalar) -> Bool {
        scalar.value >= 0x30 && scalar.value <= 0x39
    }

    private static let rfc3986Unreserved: CharacterSet = {
        var set = CharacterSet()
        set.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return set
    }()

    private static func percentEncodeQueryComponent(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: rfc3986Unreserved) ?? value
    }
}
