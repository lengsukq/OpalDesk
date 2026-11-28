import CoreGraphics
import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

// MARK: - Models

/// Specification for generating a native macOS `.app` shortcut bundle for an Android app.
public struct AppShortcutSpec: Sendable, Equatable, Hashable {
    public let packageName: String
    public let activityClass: String?
    public let appLabel: String
    public let deviceKey: String?
    public let deviceName: String?
    public let iconPNGData: Data?
    public let userId: Int
    public let profileType: String

    public init(
        packageName: String,
        activityClass: String? = nil,
        appLabel: String,
        deviceKey: String? = nil,
        deviceName: String? = nil,
        iconPNGData: Data? = nil,
        userId: Int = 0,
        profileType: String = ""
    ) {
        let normalizedUid = max(0, userId)
        self.packageName = packageName
        self.activityClass = activityClass
        self.appLabel = appLabel
        self.deviceKey = deviceKey
        self.deviceName = deviceName
        self.iconPNGData = iconPNGData
        self.userId = normalizedUid
        self.profileType = RemoteAppInfo.normalizeProfileType(profileType, userId: normalizedUid)
    }
}

/// Metadata describing an existing OpalDesk-generated macOS `.app` shortcut bundle on disk.
public struct CreatedAppShortcut: Sendable, Equatable, Hashable, Identifiable {
    public var id: String { bundleURL.standardizedFileURL.path }
    public let bundleURL: URL
    public let bundleIdentifier: String
    public let displayName: String
    public let packageName: String
    public let activityClass: String?
    public let deviceKey: String?
    public let deviceName: String?
    public let deepLinkURL: URL
    public let iconData: Data?
    public let createdAt: Date?
    public let userId: Int

    public var appKey: String {
        RemoteAppInfo.formatAppKey(pkg: packageName, userId: userId)
    }

    public var profileBadgeText: String? {
        RemoteAppInfo.profileBadgeText(userId: userId)
    }

    public init(
        bundleURL: URL,
        bundleIdentifier: String,
        displayName: String,
        packageName: String,
        activityClass: String? = nil,
        deviceKey: String? = nil,
        deviceName: String? = nil,
        deepLinkURL: URL,
        iconData: Data? = nil,
        createdAt: Date? = nil,
        userId: Int = 0
    ) {
        self.bundleURL = bundleURL
        self.bundleIdentifier = bundleIdentifier
        self.displayName = displayName
        self.packageName = packageName
        self.activityClass = activityClass
        self.deviceKey = deviceKey
        self.deviceName = deviceName
        self.deepLinkURL = deepLinkURL
        self.iconData = iconData
        self.createdAt = createdAt
        self.userId = max(0, userId)
    }
}

/// Errors thrown while creating, inspecting, updating, or removing macOS `.app` shortcuts.
public enum AppShortcutBuilderError: Error, LocalizedError, Sendable, Equatable {
    case invalidDeepLink(String)
    case destinationAlreadyExists(String)
    case iconEncodingFailed(String)
    case bundleNotManagedByOpalDesk(String)
    case fileSystemError(String)

    public var errorDescription: String? {
        switch self {
        case let .invalidDeepLink(reason):
            return "无法构造快捷方式链接：\(reason)"
        case let .destinationAlreadyExists(path):
            return "快捷方式已存在：\(path)"
        case let .iconEncodingFailed(reason):
            return "生成 macOS .icns 图标失败：\(reason)"
        case let .bundleNotManagedByOpalDesk(path):
            return "目标不是 OpalDesk 创建的快捷方式，已拒绝操作：\(path)"
        case let .fileSystemError(reason):
            return "文件系统操作失败：\(reason)"
        }
    }
}

// MARK: - Builder

/// Generates, lists, updates, and removes lightweight native macOS `.app` bundles that launch
/// standalone Android app popouts via `opaldesk://popout?...` and are natively indexed by Spotlight.
public enum AppShortcutBuilder {
    public static let bundleIdentifierPrefix = "io.github.lengsukq.opaldesk.shortcut."
    public static let iconFileName = "AppIcon"
    public static let executableName = "launch"

    private static let infoKeyVersion = "OpalDeskShortcutVersion"
    private static let infoKeyPackageName = "OpalDeskPackageName"
    private static let infoKeyActivityClass = "OpalDeskActivityClass"
    private static let infoKeyDeviceKey = "OpalDeskDeviceKey"
    private static let infoKeyDeviceName = "OpalDeskDeviceName"
    private static let infoKeyDeepLink = "OpalDeskDeepLink"
    private static let infoKeyUserId = "OpalDeskUserId"

    /// Default Spotlight-indexed directory (`~/Applications/OpalDesk Apps`).
    public static var defaultShortcutsDirectory: URL {
        URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
            .appendingPathComponent("Applications", isDirectory: true)
            .appendingPathComponent("OpalDesk Apps", isDirectory: true)
    }

    /// User's macOS Desktop directory (`~/Desktop`).
    public static var desktopDirectory: URL {
        FileManager.default.urls(for: .desktopDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true).appendingPathComponent("Desktop", isDirectory: true)
    }

    /// Creates a native macOS `.app` bundle shortcut for the specified Android app.
    @discardableResult
    public static func createShortcut(
        spec: AppShortcutSpec,
        in directory: URL = defaultShortcutsDirectory,
        overwrite: Bool = true,
        refreshSpotlight: Bool = false
    ) throws -> CreatedAppShortcut {
        let deepLinkURL: URL
        do {
            deepLinkURL = try OpalDeskDeepLink.buildPopoutURL(
                packageName: spec.packageName,
                activityClass: spec.activityClass,
                label: spec.appLabel,
                deviceKey: spec.deviceKey,
                deviceName: spec.deviceName,
                userId: spec.userId
            )
        } catch {
            throw AppShortcutBuilderError.invalidDeepLink(error.localizedDescription)
        }

        let baseDisplayName = sanitizeDisplayName(spec.appLabel, fallback: spec.packageName)
        let displayName: String = {
            if let badge = RemoteAppInfo.profileBadgeText(userId: spec.userId, profileType: spec.profileType),
               !baseDisplayName.contains(badge)
            {
                return sanitizeDisplayName("\(baseDisplayName) (\(badge))", fallback: spec.packageName)
            }
            return baseDisplayName
        }()
        let bundleFileName = sanitizeFileName(displayName, fallback: spec.packageName) + ".app"
        let bundleId = makeBundleIdentifier(
            packageName: spec.packageName,
            activityClass: spec.activityClass,
            deviceKey: spec.deviceKey,
            userId: spec.userId
        )

        let fm = FileManager.default
        do {
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            throw AppShortcutBuilderError.fileSystemError(error.localizedDescription)
        }

        let destinationURL = directory.appendingPathComponent(bundleFileName, isDirectory: true)
        if fm.fileExists(atPath: destinationURL.path) {
            if !overwrite {
                throw AppShortcutBuilderError.destinationAlreadyExists(destinationURL.path)
            }
            // Refuse to overwrite an unrelated user `.app` bundle!
            guard isManagedShortcutBundle(at: destinationURL) else {
                throw AppShortcutBuilderError.bundleNotManagedByOpalDesk(destinationURL.path)
            }
        }

        // Build inside a sibling staging folder, then atomically swap into place.
        let stagingURL = directory.appendingPathComponent(".\(UUID().uuidString).opaldesk-staging.app", isDirectory: true)
        defer { try? fm.removeItem(at: stagingURL) }

        let contentsURL = stagingURL.appendingPathComponent("Contents", isDirectory: true)
        let macOSURL = contentsURL.appendingPathComponent("MacOS", isDirectory: true)
        let resourcesURL = contentsURL.appendingPathComponent("Resources", isDirectory: true)

        do {
            try fm.createDirectory(at: macOSURL, withIntermediateDirectories: true)
            try fm.createDirectory(at: resourcesURL, withIntermediateDirectories: true)
        } catch {
            throw AppShortcutBuilderError.fileSystemError(error.localizedDescription)
        }

        // 1. Write Contents/Info.plist
        let infoPlist: [String: Any] = [
            "CFBundleDevelopmentRegion": "zh_CN",
            "CFBundleDisplayName": displayName,
            "CFBundleExecutable": executableName,
            "CFBundleIconFile": iconFileName,
            "CFBundleIdentifier": bundleId,
            "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleName": displayName,
            "CFBundlePackageType": "APPL",
            "CFBundleShortVersionString": "1.0",
            "CFBundleVersion": "1",
            "LSUIElement": true,
            "NSHighResolutionCapable": true,
            infoKeyVersion: 1,
            infoKeyPackageName: spec.packageName,
            infoKeyActivityClass: spec.activityClass ?? "",
            infoKeyDeviceKey: spec.deviceKey ?? "",
            infoKeyDeviceName: spec.deviceName ?? "",
            infoKeyDeepLink: deepLinkURL.absoluteString,
            infoKeyUserId: spec.userId,
        ]
        do {
            let plistData = try PropertyListSerialization.data(
                fromPropertyList: infoPlist,
                format: .xml,
                options: 0
            )
            try plistData.write(to: contentsURL.appendingPathComponent("Info.plist"), options: [.atomic])
        } catch {
            throw AppShortcutBuilderError.fileSystemError("写入 Info.plist 失败：\(error.localizedDescription)")
        }

        // 2. Render & write Contents/Resources/AppIcon.icns using CoreGraphics + ImageIO
        let icnsData = try buildIcnsData(from: spec.iconPNGData, fallbackLabel: displayName)
        do {
            try icnsData.write(to: resourcesURL.appendingPathComponent("\(iconFileName).icns"), options: [.atomic])
        } catch {
            throw AppShortcutBuilderError.fileSystemError("写入 AppIcon.icns 失败：\(error.localizedDescription)")
        }

        // 3. Write executable Contents/MacOS/launch POSIX shell script
        let launchScript = buildLaunchScript(deepLinkURL: deepLinkURL)
        let launchURL = macOSURL.appendingPathComponent(executableName)
        do {
            try Data(launchScript.utf8).write(to: launchURL, options: [.atomic])
            try fm.setAttributes([.posixPermissions: NSNumber(value: Int16(0o755))], ofItemAtPath: launchURL.path)
        } catch {
            throw AppShortcutBuilderError.fileSystemError("写入启动脚本失败：\(error.localizedDescription)")
        }

        // 4. Move staged bundle into destination
        do {
            if fm.fileExists(atPath: destinationURL.path) {
                try fm.removeItem(at: destinationURL)
            }
            try fm.moveItem(at: stagingURL, to: destinationURL)
        } catch {
            throw AppShortcutBuilderError.fileSystemError("安装快捷方式失败：\(error.localizedDescription)")
        }

        if refreshSpotlight {
            triggerSpotlightImport(for: destinationURL)
        }

        let createdAt = (try? fm.attributesOfItem(atPath: destinationURL.path)[.creationDate] as? Date) ?? Date()
        return CreatedAppShortcut(
            bundleURL: destinationURL,
            bundleIdentifier: bundleId,
            displayName: displayName,
            packageName: spec.packageName,
            activityClass: spec.activityClass?.isEmpty == false ? spec.activityClass : nil,
            deviceKey: spec.deviceKey?.isEmpty == false ? spec.deviceKey : nil,
            deviceName: spec.deviceName?.isEmpty == false ? spec.deviceName : nil,
            deepLinkURL: deepLinkURL,
            iconData: icnsData,
            createdAt: createdAt,
            userId: spec.userId
        )
    }

    /// Creates shortcuts for multiple Android apps in a target directory.
    @discardableResult
    public static func createShortcuts(
        specs: [AppShortcutSpec],
        in directory: URL = defaultShortcutsDirectory,
        overwrite: Bool = true,
        refreshSpotlight: Bool = false
    ) throws -> [CreatedAppShortcut] {
        var created: [CreatedAppShortcut] = []
        created.reserveCapacity(specs.count)
        for spec in specs {
            let item = try createShortcut(
                spec: spec,
                in: directory,
                overwrite: overwrite,
                refreshSpotlight: false
            )
            created.append(item)
        }
        if refreshSpotlight, !created.isEmpty {
            triggerSpotlightImport(for: directory)
        }
        return created
    }

    /// Lists all OpalDesk-managed `.app` shortcuts inside `directory`.
    public static func listShortcuts(
        in directory: URL = defaultShortcutsDirectory
    ) throws -> [CreatedAppShortcut] {
        let fm = FileManager.default
        guard fm.fileExists(atPath: directory.path) else {
            return []
        }

        let entries: [URL]
        do {
            entries = try fm.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.isDirectoryKey, .creationDateKey],
                options: [.skipsHiddenFiles]
            )
        } catch {
            throw AppShortcutBuilderError.fileSystemError(error.localizedDescription)
        }

        var shortcuts: [CreatedAppShortcut] = []
        for url in entries where url.pathExtension.lowercased() == "app" {
            if let shortcut = readManagedShortcut(at: url) {
                shortcuts.append(shortcut)
            }
        }
        return shortcuts.sorted {
            $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
    }

    /// Updates the `.icns` icon of an existing OpalDesk shortcut bundle.
    public static func updateIcon(
        for bundleURL: URL,
        iconPNGData: Data,
        refreshSpotlight: Bool = false
    ) throws {
        guard let existing = readManagedShortcut(at: bundleURL) else {
            throw AppShortcutBuilderError.bundleNotManagedByOpalDesk(bundleURL.path)
        }
        let icnsData = try buildIcnsData(from: iconPNGData, fallbackLabel: existing.displayName)
        let iconURL = bundleURL
            .appendingPathComponent("Contents", isDirectory: true)
            .appendingPathComponent("Resources", isDirectory: true)
            .appendingPathComponent("\(iconFileName).icns")
        do {
            try icnsData.write(to: iconURL, options: [.atomic])
            // Bump bundle modification date so Finder & LaunchServices refresh the icon cache.
            try FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: bundleURL.path)
        } catch {
            throw AppShortcutBuilderError.fileSystemError("更新图标失败：\(error.localizedDescription)")
        }

        if refreshSpotlight {
            triggerSpotlightImport(for: bundleURL)
        }
    }

    /// Safely deletes an OpalDesk-managed `.app` shortcut bundle.
    public static func removeShortcut(at bundleURL: URL) throws {
        guard isManagedShortcutBundle(at: bundleURL) else {
            throw AppShortcutBuilderError.bundleNotManagedByOpalDesk(bundleURL.path)
        }
        do {
            try FileManager.default.removeItem(at: bundleURL)
        } catch {
            throw AppShortcutBuilderError.fileSystemError("删除快捷方式失败：\(error.localizedDescription)")
        }
    }

    /// Removes all OpalDesk-managed `.app` shortcuts in `directory` and returns how many were removed.
    @discardableResult
    public static func removeAllShortcuts(in directory: URL = defaultShortcutsDirectory) throws -> Int {
        let existing = try listShortcuts(in: directory)
        for item in existing {
            try removeShortcut(at: item.bundleURL)
        }
        return existing.count
    }

    /// Triggers macOS Spotlight (`mdimport`) asynchronously so newly created bundles appear immediately.
    public static func triggerSpotlightImport(for url: URL) {
        let mdimportPath = "/usr/bin/mdimport"
        guard FileManager.default.isExecutableFile(atPath: mdimportPath) else { return }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: mdimportPath)
        process.arguments = [url.path]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try? process.run()
    }

    // MARK: - Deterministic Identifiers & Safe Escaping

    /// Escapes a string for safe embedding inside POSIX single quotes (`'...'`).
    /// Any embedded `'` becomes `'\''`, neutralizing `$()`, backticks, variables, globs, and quotes.
    public static func shellSingleQuoteEscape(_ raw: String) -> String {
        "'" + raw.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    /// Builds the minimal POSIX launch script placed at `Contents/MacOS/launch`.
    public static func buildLaunchScript(deepLinkURL: URL) -> String {
        let escapedURL = shellSingleQuoteEscape(deepLinkURL.absoluteString)
        return """
        #!/bin/sh
        exec /usr/bin/open -u \(escapedURL)
        """
    }

    /// Produces a deterministic bundle identifier `io.github.lengsukq.opaldesk.shortcut.<12-hex>`.
    public static func makeBundleIdentifier(
        packageName: String,
        activityClass: String? = nil,
        deviceKey: String? = nil,
        userId: Int = 0
    ) -> String {
        let baseSeed = "\(packageName)|\(deviceKey ?? "")|\(activityClass ?? "")"
        let seed = userId > 0 ? "\(baseSeed)|u\(userId)" : baseSeed
        let digest = SHA256.hash(data: Data(seed.utf8))
        let shortHex = digest.prefix(6).map { String(format: "%02x", $0) }.joined()
        return "\(bundleIdentifierPrefix)\(shortHex)"
    }

    /// Renders a rounded macOS squircle `.icns` icon container using CoreGraphics + ImageIO (no external CLI).
    public static func buildIcnsData(from iconPNGData: Data?, fallbackLabel: String) throws -> Data {
        let sourceImage: CGImage? = iconPNGData.flatMap { data in
            guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
            return CGImageSourceCreateImageAtIndex(src, 0, nil)
        }

        // Standard modern macOS ICNS PNG-backed icon types (`ic07`=128, `ic08`=256, `ic09`=512).
        let targets: [(chunkType: String, pixelSize: Int)] = [
            ("ic07", 128),
            ("ic08", 256),
            ("ic09", 512),
        ]

        var chunks: [(type: String, png: Data)] = []
        chunks.reserveCapacity(targets.count)

        for target in targets {
            let png = try renderSquircleIconPNG(
                sourceImage: sourceImage,
                fallbackLabel: fallbackLabel,
                pixelSize: target.pixelSize
            )
            chunks.append((target.chunkType, png))
        }

        let totalSize = 8 + chunks.reduce(0) { $0 + 8 + $1.png.count }
        var icns = Data(capacity: totalSize)
        icns.append(contentsOf: "icns".utf8)
        appendUInt32BE(UInt32(totalSize), to: &icns)

        for chunk in chunks {
            icns.append(contentsOf: chunk.type.utf8)
            appendUInt32BE(UInt32(8 + chunk.png.count), to: &icns)
            icns.append(chunk.png)
        }

        return icns
    }

    // MARK: - Private Helpers

    private static func readManagedShortcut(at bundleURL: URL) -> CreatedAppShortcut? {
        let plistURL = bundleURL
            .appendingPathComponent("Contents", isDirectory: true)
            .appendingPathComponent("Info.plist")
        guard let data = try? Data(contentsOf: plistURL),
              let dict = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              let bundleId = dict["CFBundleIdentifier"] as? String,
              bundleId.hasPrefix(bundleIdentifierPrefix)
        else {
            return nil
        }

        let displayName = (dict["CFBundleDisplayName"] as? String)
            ?? (dict["CFBundleName"] as? String)
            ?? bundleURL.deletingPathExtension().lastPathComponent
        let deepLinkString = (dict[infoKeyDeepLink] as? String) ?? ""
        guard let deepLinkURL = URL(string: deepLinkString),
              case let .popout(parsed)? = try? OpalDeskDeepLink.parse(deepLinkURL)
        else {
            return nil
        }

        let pkg = (dict[infoKeyPackageName] as? String)?.isEmpty == false
            ? (dict[infoKeyPackageName] as! String)
            : parsed.packageName
        let clsRaw = (dict[infoKeyActivityClass] as? String) ?? parsed.activityClass
        let devKeyRaw = (dict[infoKeyDeviceKey] as? String) ?? parsed.deviceKey
        let devNameRaw = (dict[infoKeyDeviceName] as? String) ?? parsed.deviceName
        let parsedUserId = max(0, (dict[infoKeyUserId] as? Int) ?? parsed.userId)

        let iconURL = bundleURL
            .appendingPathComponent("Contents", isDirectory: true)
            .appendingPathComponent("Resources", isDirectory: true)
            .appendingPathComponent("\(iconFileName).icns")
        let iconData = try? Data(contentsOf: iconURL)
        let createdAt = (try? FileManager.default.attributesOfItem(atPath: bundleURL.path)[.creationDate]) as? Date

        return CreatedAppShortcut(
            bundleURL: bundleURL,
            bundleIdentifier: bundleId,
            displayName: displayName,
            packageName: pkg,
            activityClass: clsRaw?.isEmpty == false ? clsRaw : nil,
            deviceKey: devKeyRaw?.isEmpty == false ? devKeyRaw : nil,
            deviceName: devNameRaw?.isEmpty == false ? devNameRaw : nil,
            deepLinkURL: deepLinkURL,
            iconData: iconData,
            createdAt: createdAt,
            userId: parsedUserId
        )
    }

    private static func isManagedShortcutBundle(at bundleURL: URL) -> Bool {
        readManagedShortcut(at: bundleURL) != nil
    }

    private static func sanitizeDisplayName(_ raw: String, fallback: String) -> String {
        let stripped = raw.unicodeScalars
            .filter { $0.value >= 0x20 && $0.value != 0x7F }
            .map(String.init)
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return stripped.isEmpty ? fallback : String(stripped.prefix(80))
    }

    private static func sanitizeFileName(_ raw: String, fallback: String) -> String {
        let forbidden: Set<Character> = ["/", ":", "\\", "\0", "*", "?", "\"", "<", ">", "|"]
        var cleaned = String(raw.map { forbidden.contains($0) ? "-" : $0 })
            .trimmingCharacters(in: .whitespacesAndNewlines)
        while cleaned.hasPrefix(".") {
            cleaned.removeFirst()
        }
        cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty {
            return fallback
        }
        return String(cleaned.prefix(80))
    }

    private static func renderSquircleIconPNG(
        sourceImage: CGImage?,
        fallbackLabel: String,
        pixelSize: Int
    ) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue

        guard let ctx = CGContext(
            data: nil,
            width: pixelSize,
            height: pixelSize,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else {
            throw AppShortcutBuilderError.iconEncodingFailed("无法创建 CGContext")
        }

        ctx.interpolationQuality = .high
        let size = CGFloat(pixelSize)
        // Follow Apple HIG macOS icon grid: ~9.8% transparent margin around rounded icon tile.
        let inset = size * 0.098
        let tileRect = CGRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
        let cornerRadius = tileRect.width * 0.225
        let clipPath = CGPath(
            roundedRect: tileRect,
            cornerWidth: cornerRadius,
            cornerHeight: cornerRadius,
            transform: nil
        )

        // Subtle drop shadow beneath icon tile
        ctx.saveGState()
        ctx.setShadow(
            offset: CGSize(width: 0, height: -size * 0.018),
            blur: size * 0.036,
            color: CGColor(red: 0, green: 0, blue: 0, alpha: 0.26)
        )
        ctx.addPath(clipPath)
        ctx.setFillColor(CGColor(red: 0.14, green: 0.16, blue: 0.22, alpha: 1.0))
        ctx.fillPath()
        ctx.restoreGState()

        // Clipped icon body
        ctx.saveGState()
        ctx.addPath(clipPath)
        ctx.clip()

        if let sourceImage {
            // White backdrop prevents dark fringe on transparent Android adaptive PNG icons.
            ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
            ctx.fill(tileRect)
            ctx.draw(sourceImage, in: tileRect)
        } else {
            // Deterministic gradient tile when no PNG icon was provided.
            let hash = SHA256.hash(data: Data(fallbackLabel.utf8))
            let hueByte: UInt8 = hash.withUnsafeBytes { $0.first ?? 128 }
            let hue = CGFloat(hueByte) / 255.0
            let (r1, g1, b1) = hsbToRGB(h: hue, s: 0.68, b: 0.90)
            let (r2, g2, b2) = hsbToRGB(h: fmod(hue + 0.08, 1.0), s: 0.78, b: 0.62)
            let colors = [
                CGColor(red: r1, green: g1, blue: b1, alpha: 1),
                CGColor(red: r2, green: g2, blue: b2, alpha: 1),
            ] as CFArray
            if let gradient = CGGradient(colorsSpace: colorSpace, colors: colors, locations: [0.0, 1.0]) {
                ctx.drawLinearGradient(
                    gradient,
                    start: CGPoint(x: tileRect.minX, y: tileRect.maxY),
                    end: CGPoint(x: tileRect.maxX, y: tileRect.minY),
                    options: []
                )
            }

            // Crisp geometric inner badge glyph
            let ringInset = tileRect.width * 0.26
            let ringRect = tileRect.insetBy(dx: ringInset, dy: ringInset)
            ctx.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.88))
            ctx.setLineWidth(max(2.0, size * 0.045))
            ctx.addPath(CGPath(roundedRect: ringRect, cornerWidth: ringRect.width * 0.26, cornerHeight: ringRect.width * 0.26, transform: nil))
            ctx.strokePath()
        }

        // Specular rim border for native macOS icon polish
        ctx.addPath(clipPath)
        ctx.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.24))
        ctx.setLineWidth(max(1.0, size * 0.012))
        ctx.strokePath()
        ctx.restoreGState()

        guard let rendered = ctx.makeImage() else {
            throw AppShortcutBuilderError.iconEncodingFailed("CGContext.makeImage() 失败")
        }

        let mutableData = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            mutableData,
            UTType.png.identifier as CFString,
            1,
            nil
        ) else {
            throw AppShortcutBuilderError.iconEncodingFailed("无法创建 PNG ImageIO 目标")
        }
        CGImageDestinationAddImage(dest, rendered, nil)
        guard CGImageDestinationFinalize(dest) else {
            throw AppShortcutBuilderError.iconEncodingFailed("PNG 编码失败")
        }
        return mutableData as Data
    }

    private static func hsbToRGB(h: CGFloat, s: CGFloat, b: CGFloat) -> (CGFloat, CGFloat, CGFloat) {
        let sector = h * 6.0
        let i = Int(floor(sector)) % 6
        let f = sector - floor(sector)
        let p = b * (1.0 - s)
        let q = b * (1.0 - f * s)
        let t = b * (1.0 - (1.0 - f) * s)
        switch i {
        case 0: return (b, t, p)
        case 1: return (q, b, p)
        case 2: return (p, b, t)
        case 3: return (p, q, b)
        case 4: return (t, p, b)
        default: return (b, p, q)
        }
    }

    private static func appendUInt32BE(_ value: UInt32, to data: inout Data) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }
}
