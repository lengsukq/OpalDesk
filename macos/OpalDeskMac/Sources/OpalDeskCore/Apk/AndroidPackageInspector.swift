import Foundation
import zlib

// MARK: - Public Models

/// Supported Android installer container formats.
public enum AndroidPackageFormat: String, Codable, Sendable, CaseIterable {
    case apk
    case apks
    case apkm
    case xapk

    public var displayBadge: String {
        rawValue.uppercased()
    }

    public var isSplitContainer: Bool {
        self != .apk
    }

    public static func detect(from url: URL) -> AndroidPackageFormat? {
        detect(fileExtension: url.pathExtension)
    }

    public static func detect(fileExtension: String) -> AndroidPackageFormat? {
        switch fileExtension.lowercased() {
        case "apk": return .apk
        case "apks": return .apks
        case "apkm": return .apkm
        case "xapk": return .xapk
        default: return nil
        }
    }
}

/// Errors produced during Android package inspection.
public enum AndroidPackageError: Error, LocalizedError, Sendable, Equatable {
    case fileNotFound(String)
    case unsupportedFormat(String)
    case invalidZipArchive(String)
    case unsupportedCompressionMethod(UInt16, entry: String)
    case decompressionFailed(entry: String, status: Int32)
    case entryTooLarge(entry: String, limit: Int)
    case tooManyArchiveEntries(count: Int, limit: Int)
    case missingManifest
    case manifestParseFailed(String)

    public var errorDescription: String? {
        switch self {
        case let .fileNotFound(path):
            return "找不到安装包文件：\(path)"
        case let .unsupportedFormat(ext):
            return "不支持的安装包格式（.\(ext)），仅支持 .apk / .apks / .apkm / .xapk"
        case let .invalidZipArchive(reason):
            return "安装包 ZIP 结构损坏或无效：\(reason)"
        case let .unsupportedCompressionMethod(method, entry):
            return "不支持的 ZIP 压缩方式（method=\(method)）：\(entry)"
        case let .decompressionFailed(entry, status):
            return "解压条目失败（\(entry)，zlib=\(status)）"
        case let .entryTooLarge(entry, limit):
            return "安装包条目「\(entry)」解压体积超过安全上限（\(limit) 字节），已拒绝读取"
        case let .tooManyArchiveEntries(count, limit):
            return "安装包条目数量过多（\(count)，上限 \(limit)）"
        case .missingManifest:
            return "安装包内未找到 AndroidManifest.xml 或可解析的基础 APK"
        case let .manifestParseFailed(reason):
            return "解析 AndroidManifest.xml 失败：\(reason)"
        }
    }
}

/// Safety caps guarding against malformed archives and zip bombs.
public struct AndroidPackageInspectionLimits: Sendable, Equatable {
    public var maxCentralDirectoryEntries: Int
    public var maxCentralDirectoryBytes: Int
    public var maxManifestBytes: Int
    public var maxMetadataJsonBytes: Int
    public var maxIconBytes: Int
    public var maxDeflatedNestedApkBytes: Int

    public static let `default` = AndroidPackageInspectionLimits(
        maxCentralDirectoryEntries: 50_000,
        maxCentralDirectoryBytes: 32 * 1024 * 1024,
        maxManifestBytes: 8 * 1024 * 1024,
        maxMetadataJsonBytes: 2 * 1024 * 1024,
        maxIconBytes: 4 * 1024 * 1024,
        maxDeflatedNestedApkBytes: 128 * 1024 * 1024
    )

    public init(
        maxCentralDirectoryEntries: Int = 50_000,
        maxCentralDirectoryBytes: Int = 32 * 1024 * 1024,
        maxManifestBytes: Int = 8 * 1024 * 1024,
        maxMetadataJsonBytes: Int = 2 * 1024 * 1024,
        maxIconBytes: Int = 4 * 1024 * 1024,
        maxDeflatedNestedApkBytes: Int = 128 * 1024 * 1024
    ) {
        self.maxCentralDirectoryEntries = maxCentralDirectoryEntries
        self.maxCentralDirectoryBytes = maxCentralDirectoryBytes
        self.maxManifestBytes = maxManifestBytes
        self.maxMetadataJsonBytes = maxMetadataJsonBytes
        self.maxIconBytes = maxIconBytes
        self.maxDeflatedNestedApkBytes = maxDeflatedNestedApkBytes
    }
}

/// APK signature presence detected from JAR `META-INF/*.{RSA,DSA,EC}` and the APK Signing Block (v2/v3).
public struct AndroidSignatureInfo: Sendable, Equatable {
    public let hasV1JarSignature: Bool
    public let hasApkSigningBlock: Bool
    public let v1CertificateFiles: [String]

    public var isSigned: Bool {
        hasV1JarSignature || hasApkSigningBlock
    }

    public var summaryText: String {
        switch (hasV1JarSignature, hasApkSigningBlock) {
        case (true, true): return "v1 + v2/v3 签名"
        case (false, true): return "v2/v3 签名"
        case (true, false): return "v1 JAR 签名"
        case (false, false): return "未检测到签名"
        }
    }

    public init(
        hasV1JarSignature: Bool,
        hasApkSigningBlock: Bool,
        v1CertificateFiles: [String] = []
    ) {
        self.hasV1JarSignature = hasV1JarSignature
        self.hasApkSigningBlock = hasApkSigningBlock
        self.v1CertificateFiles = v1CertificateFiles
    }
}

/// Human-friendly Chinese categories for Android permissions.
public enum AndroidPermissionCategory: String, Sendable, CaseIterable, Identifiable {
    case camera
    case microphone
    case location
    case storage
    case contacts
    case phoneAndSms
    case overlayAndSystem
    case installAndAccessibility
    case notifications
    case networkAndBluetooth
    case other

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .camera: return "相机"
        case .microphone: return "麦克风"
        case .location: return "位置信息"
        case .storage: return "存储与媒体"
        case .contacts: return "通讯录"
        case .phoneAndSms: return "电话与短信"
        case .overlayAndSystem: return "悬浮窗与系统设置"
        case .installAndAccessibility: return "安装应用与敏感控制"
        case .notifications: return "通知"
        case .networkAndBluetooth: return "网络与蓝牙"
        case .other: return "其他权限"
        }
    }

    public var sfSymbol: String {
        switch self {
        case .camera: return "camera.fill"
        case .microphone: return "mic.fill"
        case .location: return "location.fill"
        case .storage: return "externaldrive.fill"
        case .contacts: return "person.crop.circle.fill"
        case .phoneAndSms: return "phone.fill"
        case .overlayAndSystem: return "macwindow.on.rectangle"
        case .installAndAccessibility: return "exclamationmark.shield.fill"
        case .notifications: return "bell.badge.fill"
        case .networkAndBluetooth: return "wifi"
        case .other: return "list.bullet.rectangle"
        }
    }

    public var isSensitive: Bool {
        switch self {
        case .camera, .microphone, .location, .storage, .contacts, .phoneAndSms, .overlayAndSystem, .installAndAccessibility:
            return true
        case .notifications, .networkAndBluetooth, .other:
            return false
        }
    }
}

/// Classified Android permission with Chinese summary label.
public struct AndroidPermissionDetail: Sendable, Equatable, Identifiable {
    public var id: String { name }
    public let name: String
    public let shortName: String
    public let displayName: String
    public let category: AndroidPermissionCategory
    public let isSensitive: Bool

    public init(
        name: String,
        shortName: String,
        displayName: String,
        category: AndroidPermissionCategory,
        isSensitive: Bool
    ) {
        self.name = name
        self.shortName = shortName
        self.displayName = displayName
        self.category = category
        self.isSensitive = isSensitive
    }
}

/// Grouped permissions for collapsible UI display.
public struct AndroidPermissionGroup: Sendable, Equatable, Identifiable {
    public var id: String { category.rawValue }
    public let category: AndroidPermissionCategory
    public let permissions: [AndroidPermissionDetail]

    public var isSensitive: Bool {
        category.isSensitive || permissions.contains(where: \.isSensitive)
    }

    public init(category: AndroidPermissionCategory, permissions: [AndroidPermissionDetail]) {
        self.category = category
        self.permissions = permissions
    }
}

/// Classification of split APK entries inside `.apks` / `.apkm` / `.xapk` bundles.
public enum AndroidSplitKind: Sendable, Equatable {
    case base
    case abi(String)
    case density(String)
    case language(String)
    case feature(String)
    case unknown

    public var displayBadge: String {
        switch self {
        case .base: return "Base"
        case let .abi(abi): return "ABI: \(abi)"
        case let .density(dpi): return "DPI: \(dpi)"
        case let .language(lang): return "语言: \(lang)"
        case let .feature(feat): return "模块: \(feat)"
        case .unknown: return "分包"
        }
    }
}

/// Metadata for a single split APK contained within a bundle archive.
public struct AndroidSplitApkEntry: Sendable, Equatable, Identifiable {
    public var id: String { entryPath }
    public let entryPath: String
    public let fileName: String
    public let compressedSize: UInt64
    public let uncompressedSize: UInt64
    public let isBase: Bool
    public let kind: AndroidSplitKind

    public init(
        entryPath: String,
        fileName: String,
        compressedSize: UInt64,
        uncompressedSize: UInt64,
        isBase: Bool,
        kind: AndroidSplitKind
    ) {
        self.entryPath = entryPath
        self.fileName = fileName
        self.compressedSize = compressedSize
        self.uncompressedSize = uncompressedSize
        self.isBase = isBase
        self.kind = kind
    }
}

/// Metadata for an OBB expansion asset inside an `.xapk` package.
public struct AndroidObbEntry: Sendable, Equatable, Identifiable {
    public var id: String { entryPath }
    public let entryPath: String
    public let installPath: String?
    public let sizeBytes: UInt64

    public init(entryPath: String, installPath: String? = nil, sizeBytes: UInt64) {
        self.entryPath = entryPath
        self.installPath = installPath
        self.sizeBytes = sizeBytes
    }
}

/// Complete inspection result for a local `.apk`, `.apks`, `.apkm`, or `.xapk` installer file.
public struct AndroidPackageInfo: Sendable, Equatable {
    public let fileURL: URL
    public let fileName: String
    public let fileSizeBytes: UInt64
    public let format: AndroidPackageFormat
    public let packageName: String
    public let appName: String
    public let versionName: String?
    public let versionCode: Int64?
    public let minSdkVersion: Int?
    public let targetSdkVersion: Int?
    public let permissions: [String]
    public let permissionDetails: [AndroidPermissionDetail]
    public let permissionGroups: [AndroidPermissionGroup]
    public let supportedAbis: [String]
    public let signature: AndroidSignatureInfo
    public let iconPath: String?
    public let iconData: Data?
    public let splits: [AndroidSplitApkEntry]
    public let obbFiles: [AndroidObbEntry]
    public let warnings: [String]

    public var sensitivePermissionCount: Int {
        permissionDetails.filter(\.isSensitive).count
    }

    public init(
        fileURL: URL,
        fileName: String,
        fileSizeBytes: UInt64,
        format: AndroidPackageFormat,
        packageName: String,
        appName: String,
        versionName: String? = nil,
        versionCode: Int64? = nil,
        minSdkVersion: Int? = nil,
        targetSdkVersion: Int? = nil,
        permissions: [String] = [],
        permissionDetails: [AndroidPermissionDetail]? = nil,
        permissionGroups: [AndroidPermissionGroup]? = nil,
        supportedAbis: [String] = [],
        signature: AndroidSignatureInfo = AndroidSignatureInfo(hasV1JarSignature: false, hasApkSigningBlock: false),
        iconPath: String? = nil,
        iconData: Data? = nil,
        splits: [AndroidSplitApkEntry] = [],
        obbFiles: [AndroidObbEntry] = [],
        warnings: [String] = []
    ) {
        self.fileURL = fileURL
        self.fileName = fileName
        self.fileSizeBytes = fileSizeBytes
        self.format = format
        self.packageName = packageName
        self.appName = appName
        self.versionName = versionName
        self.versionCode = versionCode
        self.minSdkVersion = minSdkVersion
        self.targetSdkVersion = targetSdkVersion
        self.permissions = permissions
        let resolvedDetails = permissionDetails ?? permissions.map { AndroidPackageInspector.classifyPermission($0) }
        self.permissionDetails = resolvedDetails
        self.permissionGroups = permissionGroups ?? AndroidPackageInspector.groupPermissions(permissions)
        self.supportedAbis = supportedAbis
        self.signature = signature
        self.iconPath = iconPath
        self.iconData = iconData
        self.splits = splits
        self.obbFiles = obbFiles
        self.warnings = warnings
    }
}

// MARK: - Inspector Entry Point

/// Pure Swift Android installer inspector supporting `.apk`, `.apks`, `.apkm`, and `.xapk` files.
/// Uses streaming random-access ZIP Central Directory reads and bounded `zlib` inflation so multi-GB
/// game bundles never get buffered wholesale into memory.
public enum AndroidPackageInspector {
    /// Inspects a local `.apk`, `.apks`, `.apkm`, or `.xapk` file on disk.
    public static func inspect(
        url: URL,
        limits: AndroidPackageInspectionLimits = .default
    ) throws -> AndroidPackageInfo {
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw AndroidPackageError.fileNotFound(url.path)
        }
        guard let format = AndroidPackageFormat.detect(from: url) else {
            throw AndroidPackageError.unsupportedFormat(url.pathExtension)
        }

        let source = try FileRandomAccessSource(url: url)
        return try inspectSource(
            source,
            fileURL: url,
            fileName: url.lastPathComponent,
            format: format,
            limits: limits
        )
    }

    /// Inspects an in-memory Android package archive (useful for unit tests and nested streams).
    public static func inspectData(
        _ data: Data,
        fileName: String = "app.apk",
        format: AndroidPackageFormat? = nil,
        limits: AndroidPackageInspectionLimits = .default
    ) throws -> AndroidPackageInfo {
        let resolvedFormat: AndroidPackageFormat
        if let format {
            resolvedFormat = format
        } else if let detected = AndroidPackageFormat.detect(fileExtension: URL(fileURLWithPath: fileName).pathExtension) {
            resolvedFormat = detected
        } else {
            throw AndroidPackageError.unsupportedFormat(URL(fileURLWithPath: fileName).pathExtension)
        }

        let source = MemoryRandomAccessSource(data: data)
        return try inspectSource(
            source,
            fileURL: URL(fileURLWithPath: fileName),
            fileName: fileName,
            format: resolvedFormat,
            limits: limits
        )
    }

    // MARK: - Core Inspection Pipeline

    private static func inspectSource(
        _ source: any RandomAccessByteSource,
        fileURL: URL,
        fileName: String,
        format: AndroidPackageFormat,
        limits: AndroidPackageInspectionLimits
    ) throws -> AndroidPackageInfo {
        let archive = try StreamingZipArchive(source: source, limits: limits)

        if format == .apk {
            return try inspectSingleApk(
                archive: archive,
                fileURL: fileURL,
                fileName: fileName,
                fileSizeBytes: source.totalSize,
                limits: limits
            )
        }

        return try inspectSplitContainer(
            archive: archive,
            source: source,
            fileURL: fileURL,
            fileName: fileName,
            fileSizeBytes: source.totalSize,
            format: format,
            limits: limits
        )
    }

    private static func inspectSingleApk(
        archive: StreamingZipArchive,
        fileURL: URL,
        fileName: String,
        fileSizeBytes: UInt64,
        limits: AndroidPackageInspectionLimits
    ) throws -> AndroidPackageInfo {
        guard let manifestEntry = archive.entry(named: "AndroidManifest.xml") else {
            throw AndroidPackageError.missingManifest
        }
        let manifestBytes = try archive.readEntry(manifestEntry, maxBytes: limits.maxManifestBytes)
        let manifest: AndroidManifestInfo
        do {
            manifest = try AxmlParser.parseManifest(from: manifestBytes)
        } catch {
            throw AndroidPackageError.manifestParseFailed(error.localizedDescription)
        }

        let abis = extractAbis(from: archive.entries)
        let signature = detectSignature(in: archive)
        let (iconPath, iconData) = extractBestIcon(
            from: archive,
            preferredPath: manifest.applicationIconPath,
            maxBytes: limits.maxIconBytes
        )

        var warnings: [String] = []
        if !signature.isSigned {
            warnings.append("未检测到有效的 APK 签名，部分系统可能会拒绝安装")
        }
        if manifest.packageName.isEmpty {
            warnings.append("未能从 AndroidManifest.xml 中解析出有效包名")
        }

        let fallbackName = manifest.applicationLabel
            ?? (manifest.packageName.isEmpty ? fileURL.deletingPathExtension().lastPathComponent : manifest.packageName)

        return AndroidPackageInfo(
            fileURL: fileURL,
            fileName: fileName,
            fileSizeBytes: fileSizeBytes,
            format: .apk,
            packageName: manifest.packageName,
            appName: fallbackName,
            versionName: manifest.versionName,
            versionCode: manifest.versionCode,
            minSdkVersion: manifest.minSdkVersion,
            targetSdkVersion: manifest.targetSdkVersion,
            permissions: manifest.permissions,
            supportedAbis: abis,
            signature: signature,
            iconPath: iconPath,
            iconData: iconData,
            splits: [],
            obbFiles: [],
            warnings: warnings
        )
    }

    private static func inspectSplitContainer(
        archive: StreamingZipArchive,
        source: any RandomAccessByteSource,
        fileURL: URL,
        fileName: String,
        fileSizeBytes: UInt64,
        format: AndroidPackageFormat,
        limits: AndroidPackageInspectionLimits
    ) throws -> AndroidPackageInfo {
        // 1. Parse optional container metadata (`manifest.json` for XAPK, `info.json` for APKM)
        let xapkMeta: XapkManifestMetadata? = archive.entry(named: "manifest.json").flatMap { entry in
            guard let data = try? archive.readEntry(entry, maxBytes: limits.maxMetadataJsonBytes) else { return nil }
            return try? JSONDecoder().decode(XapkManifestMetadata.self, from: data)
        }
        let apkmMeta: ApkmInfoMetadata? = archive.entry(named: "info.json").flatMap { entry in
            guard let data = try? archive.readEntry(entry, maxBytes: limits.maxMetadataJsonBytes) else { return nil }
            return try? JSONDecoder().decode(ApkmInfoMetadata.self, from: data)
        }

        // 2. Discover split APK entries and OBB files inside the outer container
        let apkEntries = archive.entries.filter { entry in
            !entry.isDirectory && entry.name.lowercased().hasSuffix(".apk")
        }

        let baseApkEntry = pickBaseApkEntry(from: apkEntries, xapkMeta: xapkMeta)
        var splits: [AndroidSplitApkEntry] = []
        splits.reserveCapacity(apkEntries.count)
        var abiSet = Set<String>()

        for entry in apkEntries {
            let isBase = (entry.name == baseApkEntry?.name)
            let kind = classifySplitEntry(entry.name, isBase: isBase, xapkMeta: xapkMeta)
            if case let .abi(abi) = kind {
                abiSet.insert(abi)
            }
            splits.append(
                AndroidSplitApkEntry(
                    entryPath: entry.name,
                    fileName: (entry.name as NSString).lastPathComponent,
                    compressedSize: entry.compressedSize,
                    uncompressedSize: entry.uncompressedSize,
                    isBase: isBase,
                    kind: kind
                )
            )
        }
        splits.sort { lhs, rhs in
            if lhs.isBase != rhs.isBase { return lhs.isBase }
            return lhs.fileName.localizedCaseInsensitiveCompare(rhs.fileName) == .orderedAscending
        }

        var obbFiles: [AndroidObbEntry] = []
        var obbInstallPathByFile: [String: String] = [:]
        if let expansions = xapkMeta?.expansions {
            for exp in expansions {
                if let file = exp.file, let installPath = exp.installPath {
                    obbInstallPathByFile[file] = installPath
                }
            }
        }
        for entry in archive.entries where !entry.isDirectory && entry.name.lowercased().hasSuffix(".obb") {
            obbFiles.append(
                AndroidObbEntry(
                    entryPath: entry.name,
                    installPath: obbInstallPathByFile[entry.name],
                    sizeBytes: entry.uncompressedSize
                )
            )
        }

        // 3. Inspect inner base APK without reading the whole multi-GB outer file into memory.
        var baseManifest: AndroidManifestInfo?
        var signature = AndroidSignatureInfo(hasV1JarSignature: false, hasApkSigningBlock: false)
        var innerIconPath: String?
        var innerIconData: Data?
        var warnings: [String] = []

        if let baseApkEntry {
            let nestedArchive: StreamingZipArchive?
            if baseApkEntry.compressionMethod == 0 {
                // Fast zero-copy path: base APK is STORED inside the outer container!
                let dataOffset = try archive.resolveLocalDataOffset(for: baseApkEntry)
                let sliceSource = SliceRandomAccessSource(
                    parent: source,
                    baseOffset: dataOffset,
                    length: baseApkEntry.uncompressedSize
                )
                nestedArchive = try? StreamingZipArchive(source: sliceSource, limits: limits)
            } else {
                // Fallback for DEFLATED inner base APK (bounded by maxDeflatedNestedApkBytes).
                let innerBytes = try archive.readEntry(baseApkEntry, maxBytes: limits.maxDeflatedNestedApkBytes)
                let memSource = MemoryRandomAccessSource(data: innerBytes)
                nestedArchive = try? StreamingZipArchive(source: memSource, limits: limits)
            }

            if let nestedArchive {
                if let manifestEntry = nestedArchive.entry(named: "AndroidManifest.xml") {
                    let manifestBytes = try nestedArchive.readEntry(manifestEntry, maxBytes: limits.maxManifestBytes)
                    do {
                        baseManifest = try AxmlParser.parseManifest(from: manifestBytes)
                    } catch {
                        throw AndroidPackageError.manifestParseFailed(error.localizedDescription)
                    }
                }
                for abi in extractAbis(from: nestedArchive.entries) {
                    abiSet.insert(abi)
                }
                signature = detectSignature(in: nestedArchive)
                let (path, data) = extractBestIcon(
                    from: nestedArchive,
                    preferredPath: baseManifest?.applicationIconPath,
                    maxBytes: limits.maxIconBytes
                )
                innerIconPath = path
                innerIconData = data
            }
        }

        // 4. Prefer outer container icon (`icon.png` in XAPK/APKM) if present, else fall back to base APK icon.
        var iconPath = innerIconPath
        var iconData = innerIconData
        let containerIconCandidates = [xapkMeta?.icon, "icon.png", "icon.webp"].compactMap { $0 }
        for candidate in containerIconCandidates {
            if let iconEntry = archive.entry(named: candidate),
               let data = try? archive.readEntry(iconEntry, maxBytes: limits.maxIconBytes),
               !data.isEmpty
            {
                iconPath = iconEntry.name
                iconData = data
                break
            }
        }

        // 5. Merge metadata from AXML + XAPK manifest.json + APKM info.json
        if let arches = apkmMeta?.arches {
            for arch in arches where !arch.isEmpty {
                abiSet.insert(arch)
            }
        }

        let packageName = [baseManifest?.packageName, xapkMeta?.packageName, apkmMeta?.packageName]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first(where: { !$0.isEmpty }) ?? ""

        if baseManifest == nil && packageName.isEmpty {
            throw AndroidPackageError.missingManifest
        }

        let appName = [xapkMeta?.name, apkmMeta?.appName, baseManifest?.applicationLabel, packageName]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first(where: { !$0.isEmpty }) ?? fileURL.deletingPathExtension().lastPathComponent

        let versionName = baseManifest?.versionName ?? xapkMeta?.versionName ?? apkmMeta?.releaseVersion
        let versionCode = baseManifest?.versionCode ?? xapkMeta?.versionCode?.int64Value ?? apkmMeta?.versionCode?.int64Value
        let minSdk = baseManifest?.minSdkVersion ?? xapkMeta?.minSdkVersion?.intValue ?? apkmMeta?.minApi?.intValue
        let targetSdk = baseManifest?.targetSdkVersion ?? xapkMeta?.targetSdkVersion?.intValue

        var mergedPermissions: [String] = []
        var seenPermissions = Set<String>()
        for perm in (baseManifest?.permissions ?? []) + (xapkMeta?.permissions ?? []) {
            let trimmed = perm.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, seenPermissions.insert(trimmed).inserted {
                mergedPermissions.append(trimmed)
            }
        }

        if baseApkEntry == nil {
            warnings.append("容器内未找到基础 APK，部分信息来自元数据清单")
        } else if !signature.isSigned {
            warnings.append("基础 APK 未检测到有效签名，部分系统可能会拒绝安装")
        }
        if !obbFiles.isEmpty {
            warnings.append("包含 \(obbFiles.count) 个 OBB 扩展数据包，安装时将一并处理")
        }

        return AndroidPackageInfo(
            fileURL: fileURL,
            fileName: fileName,
            fileSizeBytes: fileSizeBytes,
            format: format,
            packageName: packageName,
            appName: appName,
            versionName: versionName,
            versionCode: versionCode,
            minSdkVersion: minSdk,
            targetSdkVersion: targetSdk,
            permissions: mergedPermissions,
            supportedAbis: abiSet.sorted(),
            signature: signature,
            iconPath: iconPath,
            iconData: iconData,
            splits: splits,
            obbFiles: obbFiles,
            warnings: warnings
        )
    }

    // MARK: - Helpers (ABI, Signature, Splits, Icons)

    private static func extractAbis(from entries: [ZipCentralDirectoryEntry]) -> [String] {
        var abis = Set<String>()
        for entry in entries {
            guard entry.name.hasPrefix("lib/") else { continue }
            let parts = entry.name.split(separator: "/")
            if parts.count >= 3 {
                let abi = String(parts[1])
                if !abi.isEmpty {
                    abis.insert(abi)
                }
            }
        }
        return abis.sorted()
    }

    private static func detectSignature(in archive: StreamingZipArchive) -> AndroidSignatureInfo {
        var certFiles: [String] = []
        for entry in archive.entries where !entry.isDirectory {
            let upper = entry.name.uppercased()
            guard upper.hasPrefix("META-INF/"), !upper.dropFirst("META-INF/".count).contains("/") else {
                continue
            }
            if upper.hasSuffix(".RSA") || upper.hasSuffix(".DSA") || upper.hasSuffix(".EC") {
                certFiles.append(entry.name)
            }
        }
        let hasSigningBlock = archive.hasApkSigningBlock()
        return AndroidSignatureInfo(
            hasV1JarSignature: !certFiles.isEmpty,
            hasApkSigningBlock: hasSigningBlock,
            v1CertificateFiles: certFiles.sorted()
        )
    }

    private static func pickBaseApkEntry(
        from apkEntries: [ZipCentralDirectoryEntry],
        xapkMeta: XapkManifestMetadata?
    ) -> ZipCentralDirectoryEntry? {
        guard !apkEntries.isEmpty else { return nil }

        // 1. Check XAPK split_apks with id == "base"
        if let baseSplitFile = xapkMeta?.splitApks?.first(where: { $0.id?.lowercased() == "base" })?.file,
           let match = apkEntries.first(where: { $0.name == baseSplitFile || ($0.name as NSString).lastPathComponent == baseSplitFile })
        {
            return match
        }

        // 2. Check conventional base filenames
        let preferredNames = ["base.apk", "base-master.apk", "base-master_2.apk"]
        for preferred in preferredNames {
            if let match = apkEntries.first(where: { ($0.name as NSString).lastPathComponent.lowercased() == preferred }) {
                return match
            }
        }

        // 3. Check `<package_name>.apk` (classic XAPK layout)
        if let pkg = xapkMeta?.packageName?.lowercased(),
           let match = apkEntries.first(where: { ($0.name as NSString).lastPathComponent.lowercased() == "\(pkg).apk" })
        {
            return match
        }

        // 4. First APK without `config.` prefix
        if let nonConfig = apkEntries.first(where: {
            let lower = ($0.name as NSString).lastPathComponent.lowercased()
            return !lower.hasPrefix("config.") && !lower.hasPrefix("split_config.")
        }) {
            return nonConfig
        }

        return apkEntries.first
    }

    private static func classifySplitEntry(
        _ entryPath: String,
        isBase: Bool,
        xapkMeta: XapkManifestMetadata?
    ) -> AndroidSplitKind {
        if isBase { return .base }

        let fileName = (entryPath as NSString).lastPathComponent.lowercased()
        let stem = (fileName as NSString).deletingPathExtension
        let xapkId = xapkMeta?.splitApks?.first(where: {
            $0.file == entryPath || ($0.file as NSString?)?.lastPathComponent.lowercased() == fileName
        })?.id?.lowercased()

        let token = xapkId ?? stem

        let knownAbis: [(pattern: String, canonical: String)] = [
            ("arm64_v8a", "arm64-v8a"),
            ("arm64-v8a", "arm64-v8a"),
            ("armeabi_v7a", "armeabi-v7a"),
            ("armeabi-v7a", "armeabi-v7a"),
            ("armeabi", "armeabi"),
            ("x86_64", "x86_64"),
            ("x86", "x86"),
            ("riscv64", "riscv64"),
        ]
        for (pattern, canonical) in knownAbis where token.contains(pattern) {
            return .abi(canonical)
        }

        let knownDpis = ["xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "ldpi", "tvdpi", "anydpi", "nodpi"]
        for dpi in knownDpis where token.contains(dpi) {
            return .density(dpi)
        }

        // Language splits typically look like `config.zh`, `split_config.en`, `config.pt_rBR`
        let cleaned = token
            .replacingOccurrences(of: "split_config.", with: "")
            .replacingOccurrences(of: "config.", with: "")
        if cleaned.count == 2, cleaned.allSatisfy({ $0.isLetter }) {
            return .language(cleaned)
        }
        if cleaned.count >= 4, cleaned.count <= 8, cleaned.contains("_") || cleaned.contains("-") {
            let prefix = cleaned.prefix(2)
            if prefix.allSatisfy({ $0.isLetter }) {
                return .language(cleaned)
            }
        }

        if !cleaned.isEmpty, cleaned != stem {
            return .feature(cleaned)
        }
        return .unknown
    }

    private static func extractBestIcon(
        from archive: StreamingZipArchive,
        preferredPath: String?,
        maxBytes: Int
    ) -> (String?, Data?) {
        let rasterExts = [".png", ".webp", ".jpg", ".jpeg"]
        func isRaster(_ name: String) -> Bool {
            let lower = name.lowercased()
            return rasterExts.contains(where: { lower.hasSuffix($0) })
        }

        if let preferredPath, isRaster(preferredPath), let entry = archive.entry(named: preferredPath),
           let data = try? archive.readEntry(entry, maxBytes: maxBytes), !data.isEmpty
        {
            return (entry.name, data)
        }

        // Score candidate launcher icons across `res/mipmap-*` and `res/drawable-*`.
        var bestEntry: ZipCentralDirectoryEntry?
        var bestScore = Int.min

        for entry in archive.entries where !entry.isDirectory && isRaster(entry.name) {
            let lower = entry.name.lowercased()
            guard lower.hasPrefix("res/") else { continue }
            guard entry.uncompressedSize > 0, entry.uncompressedSize <= UInt64(maxBytes) else { continue }

            let baseName = (lower as NSString).lastPathComponent
            var score = 0
            if baseName.contains("ic_launcher") {
                score += 100
            } else if baseName.contains("app_icon") || baseName.contains("icon") {
                score += 40
            } else {
                continue
            }

            if baseName.contains("background") || baseName.contains("foreground") || baseName.contains("monochrome") {
                score -= 60
            }
            if baseName.contains("round") {
                score -= 5
            }
            if lower.contains("mipmap") {
                score += 25
            }
            if lower.contains("xxxhdpi") { score += 50 }
            else if lower.contains("xxhdpi") { score += 45 }
            else if lower.contains("xhdpi") { score += 35 }
            else if lower.contains("hdpi") { score += 25 }
            else if lower.contains("mdpi") { score += 15 }

            if lower.hasSuffix(".png") {
                score += 10
            }

            if score > bestScore {
                bestScore = score
                bestEntry = entry
            }
        }

        if let bestEntry,
           let data = try? archive.readEntry(bestEntry, maxBytes: maxBytes),
           !data.isEmpty
        {
            return (bestEntry.name, data)
        }
        return (nil, nil)
    }

    // MARK: - Chinese Permission Classification

    /// Classifies a single Android permission string into a Chinese human-readable description and category.
    public static func classifyPermission(_ permission: String) -> AndroidPermissionDetail {
        let trimmed = permission.trimmingCharacters(in: .whitespacesAndNewlines)
        let shortName = trimmed.split(separator: ".").last.map(String.init) ?? trimmed

        if let known = knownPermissions[trimmed] {
            return AndroidPermissionDetail(
                name: trimmed,
                shortName: shortName,
                displayName: known.label,
                category: known.category,
                isSensitive: known.sensitive
            )
        }

        let upper = shortName.uppercased()
        let inferredCategory: AndroidPermissionCategory
        if upper.contains("CAMERA") {
            inferredCategory = .camera
        } else if upper.contains("AUDIO") || upper.contains("MICROPHONE") {
            inferredCategory = .microphone
        } else if upper.contains("LOCATION") || upper.contains("GPS") {
            inferredCategory = .location
        } else if upper.contains("STORAGE") || upper.contains("MEDIA_") || upper.contains("PHOTOS") {
            inferredCategory = .storage
        } else if upper.contains("CONTACT") || upper.contains("ACCOUNT") {
            inferredCategory = .contacts
        } else if upper.contains("PHONE") || upper.contains("CALL") || upper.contains("SMS") || upper.contains("MMS") {
            inferredCategory = .phoneAndSms
        } else if upper.contains("OVERLAY") || upper.contains("ALERT_WINDOW") || upper.contains("WRITE_SETTINGS") {
            inferredCategory = .overlayAndSystem
        } else if upper.contains("INSTALL_PACKAGES") || upper.contains("ACCESSIBILITY") || upper.contains("QUERY_ALL_PACKAGES") {
            inferredCategory = .installAndAccessibility
        } else if upper.contains("NOTIFICATION") {
            inferredCategory = .notifications
        } else if upper.contains("INTERNET") || upper.contains("NETWORK") || upper.contains("WIFI") || upper.contains("BLUETOOTH") || upper.contains("NFC") {
            inferredCategory = .networkAndBluetooth
        } else {
            inferredCategory = .other
        }

        return AndroidPermissionDetail(
            name: trimmed,
            shortName: shortName,
            displayName: shortName,
            category: inferredCategory,
            isSensitive: inferredCategory.isSensitive
        )
    }

    /// Groups raw Android permission names by category, ordering sensitive categories first.
    public static func groupPermissions(_ permissions: [String]) -> [AndroidPermissionGroup] {
        var bucket: [AndroidPermissionCategory: [AndroidPermissionDetail]] = [:]
        var seen = Set<String>()
        for raw in permissions {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, seen.insert(trimmed).inserted else { continue }
            let detail = classifyPermission(trimmed)
            bucket[detail.category, default: []].append(detail)
        }

        return AndroidPermissionCategory.allCases.compactMap { category in
            guard let items = bucket[category], !items.isEmpty else { return nil }
            return AndroidPermissionGroup(category: category, permissions: items)
        }
    }

    private struct KnownPermissionEntry: Sendable {
        let label: String
        let category: AndroidPermissionCategory
        let sensitive: Bool
    }

    private static let knownPermissions: [String: KnownPermissionEntry] = [
        "android.permission.CAMERA": .init(label: "拍摄照片和录制视频", category: .camera, sensitive: true),
        "android.permission.RECORD_AUDIO": .init(label: "录制音频 / 使用麦克风", category: .microphone, sensitive: true),
        "android.permission.ACCESS_FINE_LOCATION": .init(label: "访问精确位置 (GPS)", category: .location, sensitive: true),
        "android.permission.ACCESS_COARSE_LOCATION": .init(label: "访问大致位置", category: .location, sensitive: true),
        "android.permission.ACCESS_BACKGROUND_LOCATION": .init(label: "在后台持续访问位置", category: .location, sensitive: true),
        "android.permission.READ_EXTERNAL_STORAGE": .init(label: "读取外部存储文件", category: .storage, sensitive: true),
        "android.permission.WRITE_EXTERNAL_STORAGE": .init(label: "写入外部存储文件", category: .storage, sensitive: true),
        "android.permission.MANAGE_EXTERNAL_STORAGE": .init(label: "访问设备上所有文件", category: .storage, sensitive: true),
        "android.permission.READ_MEDIA_IMAGES": .init(label: "读取相册图片", category: .storage, sensitive: true),
        "android.permission.READ_MEDIA_VIDEO": .init(label: "读取设备视频", category: .storage, sensitive: true),
        "android.permission.READ_MEDIA_AUDIO": .init(label: "读取音频文件", category: .storage, sensitive: true),
        "android.permission.READ_CONTACTS": .init(label: "读取通讯录联系人", category: .contacts, sensitive: true),
        "android.permission.WRITE_CONTACTS": .init(label: "修改通讯录联系人", category: .contacts, sensitive: true),
        "android.permission.READ_PHONE_STATE": .init(label: "读取设备状态与通话信息", category: .phoneAndSms, sensitive: true),
        "android.permission.CALL_PHONE": .init(label: "直接拨打电话", category: .phoneAndSms, sensitive: true),
        "android.permission.READ_CALL_LOG": .init(label: "读取通话记录", category: .phoneAndSms, sensitive: true),
        "android.permission.READ_SMS": .init(label: "读取短信内容", category: .phoneAndSms, sensitive: true),
        "android.permission.RECEIVE_SMS": .init(label: "接收短信", category: .phoneAndSms, sensitive: true),
        "android.permission.SEND_SMS": .init(label: "发送短信", category: .phoneAndSms, sensitive: true),
        "android.permission.SYSTEM_ALERT_WINDOW": .init(label: "在其他应用上层显示悬浮窗", category: .overlayAndSystem, sensitive: true),
        "android.permission.WRITE_SETTINGS": .init(label: "修改系统设置", category: .overlayAndSystem, sensitive: true),
        "android.permission.REQUEST_INSTALL_PACKAGES": .init(label: "请求安装其他应用包", category: .installAndAccessibility, sensitive: true),
        "android.permission.QUERY_ALL_PACKAGES": .init(label: "读取已安装应用列表", category: .installAndAccessibility, sensitive: true),
        "android.permission.BIND_ACCESSIBILITY_SERVICE": .init(label: "无障碍服务控制", category: .installAndAccessibility, sensitive: true),
        "android.permission.POST_NOTIFICATIONS": .init(label: "发送通知提醒", category: .notifications, sensitive: false),
        "android.permission.INTERNET": .init(label: "访问网络", category: .networkAndBluetooth, sensitive: false),
        "android.permission.ACCESS_NETWORK_STATE": .init(label: "查看网络连接状态", category: .networkAndBluetooth, sensitive: false),
        "android.permission.ACCESS_WIFI_STATE": .init(label: "查看 WLAN 状态", category: .networkAndBluetooth, sensitive: false),
        "android.permission.BLUETOOTH": .init(label: "连接蓝牙设备", category: .networkAndBluetooth, sensitive: false),
        "android.permission.BLUETOOTH_CONNECT": .init(label: "连接已配对的蓝牙设备", category: .networkAndBluetooth, sensitive: false),
        "android.permission.BLUETOOTH_SCAN": .init(label: "扫描附近蓝牙设备", category: .networkAndBluetooth, sensitive: false),
        "android.permission.VIBRATE": .init(label: "控制振动", category: .other, sensitive: false),
        "android.permission.WAKE_LOCK": .init(label: "防止设备休眠", category: .other, sensitive: false),
        "android.permission.FOREGROUND_SERVICE": .init(label: "运行前台服务", category: .other, sensitive: false),
        "android.permission.RECEIVE_BOOT_COMPLETED": .init(label: "开机自动启动", category: .other, sensitive: false),
    ]
}

// MARK: - Container Metadata Decoders (XAPK / APKM)

private struct FlexibleScalar: Decodable {
    let stringValue: String

    var intValue: Int? { Int(stringValue) }
    var int64Value: Int64? { Int64(stringValue) }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let intVal = try? container.decode(Int64.self) {
            self.stringValue = String(intVal)
        } else if let strVal = try? container.decode(String.self) {
            self.stringValue = strVal.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            self.stringValue = ""
        }
    }
}

private struct XapkManifestMetadata: Decodable {
    struct SplitItem: Decodable {
        let file: String?
        let id: String?
    }

    struct ExpansionItem: Decodable {
        let file: String?
        let installPath: String?

        private enum CodingKeys: String, CodingKey {
            case file
            case installPath = "install_path"
        }
    }

    let packageName: String?
    let name: String?
    let versionCode: FlexibleScalar?
    let versionName: String?
    let minSdkVersion: FlexibleScalar?
    let targetSdkVersion: FlexibleScalar?
    let permissions: [String]?
    let icon: String?
    let splitApks: [SplitItem]?
    let expansions: [ExpansionItem]?

    private enum CodingKeys: String, CodingKey {
        case packageName = "package_name"
        case name
        case versionCode = "version_code"
        case versionName = "version_name"
        case minSdkVersion = "min_sdk_version"
        case targetSdkVersion = "target_sdk_version"
        case permissions
        case icon
        case splitApks = "split_apks"
        case expansions
    }
}

private struct ApkmInfoMetadata: Decodable {
    let packageName: String?
    let appName: String?
    let releaseVersion: String?
    let versionCode: FlexibleScalar?
    let minApi: FlexibleScalar?
    let arches: [String]?

    private enum CodingKeys: String, CodingKey {
        case packageName = "pname"
        case appName = "app_name"
        case releaseVersion = "release_version"
        case versionCode = "versioncode"
        case minApi = "min_api"
        case arches
    }
}

// MARK: - Streaming Random-Access ZIP Reader

internal protocol RandomAccessByteSource: Sendable {
    var totalSize: UInt64 { get }
    func read(offset: UInt64, length: Int) throws -> Data
}

private final class FileRandomAccessSource: RandomAccessByteSource, @unchecked Sendable {
    private let handle: FileHandle
    private let lock = NSLock()
    let totalSize: UInt64

    init(url: URL) throws {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attrs[.size] as? NSNumber)?.uint64Value ?? 0
        self.handle = try FileHandle(forReadingFrom: url)
        self.totalSize = size
    }

    deinit {
        try? handle.close()
    }

    func read(offset: UInt64, length: Int) throws -> Data {
        guard length > 0 else { return Data() }
        guard offset <= totalSize, UInt64(length) <= totalSize - offset else {
            throw AndroidPackageError.invalidZipArchive("读取范围越界（offset=\(offset), len=\(length), total=\(totalSize)）")
        }
        return try lock.withLock {
            try handle.seek(toOffset: offset)
            return try handle.read(upToCount: length) ?? Data()
        }
    }
}

private struct MemoryRandomAccessSource: RandomAccessByteSource {
    let data: Data
    var totalSize: UInt64 { UInt64(data.count) }

    func read(offset: UInt64, length: Int) throws -> Data {
        guard length > 0 else { return Data() }
        guard offset <= totalSize, UInt64(length) <= totalSize - offset else {
            throw AndroidPackageError.invalidZipArchive("内存切片范围越界")
        }
        let start = data.startIndex + Int(offset)
        return Data(data[start..<(start + length)])
    }
}

private struct SliceRandomAccessSource: RandomAccessByteSource {
    let parent: any RandomAccessByteSource
    let baseOffset: UInt64
    let length: UInt64

    var totalSize: UInt64 { length }

    func read(offset: UInt64, length: Int) throws -> Data {
        guard length > 0 else { return Data() }
        guard offset <= self.length, UInt64(length) <= self.length - offset else {
            throw AndroidPackageError.invalidZipArchive("嵌套 ZIP 切片越界")
        }
        return try parent.read(offset: baseOffset + offset, length: length)
    }
}

internal struct ZipCentralDirectoryEntry: Sendable {
    let name: String
    let compressionMethod: UInt16
    let crc32: UInt32
    let compressedSize: UInt64
    let uncompressedSize: UInt64
    let localHeaderOffset: UInt64

    var isDirectory: Bool {
        name.hasSuffix("/")
    }
}

/// Zero-dependency random-access ZIP reader supporting ZIP32, ZIP64, STORED (0), DEFLATED (8),
/// bounded streaming decompression, and APK Signing Block v2/v3 detection.
internal final class StreamingZipArchive: Sendable {
    private static let sigEOCD: UInt32 = 0x0605_4B50
    private static let sigZip64Locator: UInt32 = 0x0706_4B50
    private static let sigZip64EOCD: UInt32 = 0x0606_4B50
    private static let sigCentralDirectory: UInt32 = 0x0201_4B50
    private static let sigLocalFileHeader: UInt32 = 0x0403_4B50
    private static let apkSigBlockMagic = Data("APK Sig Block 42".utf8)

    private let source: any RandomAccessByteSource
    let entries: [ZipCentralDirectoryEntry]
    private let entryLookup: [String: ZipCentralDirectoryEntry]
    let centralDirectoryOffset: UInt64

    init(source: any RandomAccessByteSource, limits: AndroidPackageInspectionLimits) throws {
        self.source = source
        guard source.totalSize >= 22 else {
            throw AndroidPackageError.invalidZipArchive("文件过小，不是有效的 ZIP 归档")
        }

        // Locate End of Central Directory (EOCD) inside the trailing 65,557 bytes.
        let tailLen = Int(min(source.totalSize, 65_557))
        let tailOffset = source.totalSize - UInt64(tailLen)
        let tailData = try source.read(offset: tailOffset, length: tailLen)

        guard let eocdRelative = Self.findEOCD(in: tailData) else {
            throw AndroidPackageError.invalidZipArchive("未找到 ZIP End of Central Directory 标记")
        }

        let eocdReader = LittleEndianReader(data: tailData)
        var entryCount = UInt64(try eocdReader.readUInt16(at: eocdRelative + 10))
        var cdSize = UInt64(try eocdReader.readUInt32(at: eocdRelative + 12))
        var cdOffset = UInt64(try eocdReader.readUInt32(at: eocdRelative + 16))

        // Handle ZIP64 EOCD if sentinel fields are set.
        if entryCount == 0xFFFF || cdSize == 0xFFFF_FFFF || cdOffset == 0xFFFF_FFFF {
            let eocdAbsolute = tailOffset + UInt64(eocdRelative)
            if eocdAbsolute >= 20 {
                let locatorData = try source.read(offset: eocdAbsolute - 20, length: 20)
                let locReader = LittleEndianReader(data: locatorData)
                if (try? locReader.readUInt32(at: 0)) == Self.sigZip64Locator {
                    let zip64EocdOffset = Self.readUInt64LE(locatorData, at: 8)
                    if zip64EocdOffset + 56 <= source.totalSize {
                        let z64Data = try source.read(offset: zip64EocdOffset, length: 56)
                        if Self.readUInt32LE(z64Data, at: 0) == Self.sigZip64EOCD {
                            entryCount = Self.readUInt64LE(z64Data, at: 32)
                            cdSize = Self.readUInt64LE(z64Data, at: 40)
                            cdOffset = Self.readUInt64LE(z64Data, at: 48)
                        }
                    }
                }
            }
        }

        guard entryCount <= UInt64(limits.maxCentralDirectoryEntries) else {
            throw AndroidPackageError.tooManyArchiveEntries(
                count: Int(min(entryCount, UInt64(Int.max))),
                limit: limits.maxCentralDirectoryEntries
            )
        }
        guard cdSize <= UInt64(limits.maxCentralDirectoryBytes) else {
            throw AndroidPackageError.entryTooLarge(
                entry: "ZIP Central Directory",
                limit: limits.maxCentralDirectoryBytes
            )
        }
        guard cdOffset <= source.totalSize, cdSize <= source.totalSize - cdOffset else {
            throw AndroidPackageError.invalidZipArchive("Central Directory 偏移越界")
        }

        self.centralDirectoryOffset = cdOffset
        let cdData = try source.read(offset: cdOffset, length: Int(cdSize))
        let parsed = try Self.parseCentralDirectory(cdData, expectedCount: Int(entryCount), limit: limits.maxCentralDirectoryEntries)
        self.entries = parsed

        var lookup: [String: ZipCentralDirectoryEntry] = [:]
        lookup.reserveCapacity(parsed.count)
        for item in parsed where lookup[item.name] == nil {
            lookup[item.name] = item
        }
        self.entryLookup = lookup
    }

    func entry(named name: String) -> ZipCentralDirectoryEntry? {
        entryLookup[name]
    }

    /// Checks for the standard 16-byte `"APK Sig Block 42"` trailer immediately preceding the ZIP Central Directory.
    func hasApkSigningBlock() -> Bool {
        guard centralDirectoryOffset >= 32 else { return false }
        guard let footer = try? source.read(offset: centralDirectoryOffset - 24, length: 24),
              footer.count == 24
        else {
            return false
        }
        let magicSlice = footer.suffix(16)
        guard magicSlice == Self.apkSigBlockMagic else { return false }
        let blockSize = Self.readUInt64LE(footer, at: 0)
        // Full block occupies `blockSize + 8` bytes before CD and must fit within file bounds.
        return blockSize >= 24 && blockSize + 8 <= centralDirectoryOffset
    }

    /// Resolves the exact byte offset where the entry's compressed/stored payload begins.
    func resolveLocalDataOffset(for entry: ZipCentralDirectoryEntry) throws -> UInt64 {
        guard entry.localHeaderOffset + 30 <= source.totalSize else {
            throw AndroidPackageError.invalidZipArchive("Local File Header 偏移越界：\(entry.name)")
        }
        let header = try source.read(offset: entry.localHeaderOffset, length: 30)
        guard Self.readUInt32LE(header, at: 0) == Self.sigLocalFileHeader else {
            throw AndroidPackageError.invalidZipArchive("Local File Header 魔数不匹配：\(entry.name)")
        }
        let nameLen = UInt64(Self.readUInt16LE(header, at: 26))
        let extraLen = UInt64(Self.readUInt16LE(header, at: 28))
        let dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen
        guard dataOffset <= source.totalSize else {
            throw AndroidPackageError.invalidZipArchive("条目数据偏移越界：\(entry.name)")
        }
        return dataOffset
    }

    /// Reads and (if deflated) stream-inflates an archive entry with a strict anti-zip-bomb ceiling.
    func readEntry(_ entry: ZipCentralDirectoryEntry, maxBytes: Int) throws -> Data {
        if entry.uncompressedSize > UInt64(maxBytes) {
            throw AndroidPackageError.entryTooLarge(entry: entry.name, limit: maxBytes)
        }
        let dataOffset = try resolveLocalDataOffset(for: entry)

        switch entry.compressionMethod {
        case 0: // STORED
            let byteCount = Int(entry.compressedSize)
            guard byteCount <= maxBytes else {
                throw AndroidPackageError.entryTooLarge(entry: entry.name, limit: maxBytes)
            }
            return try source.read(offset: dataOffset, length: byteCount)

        case 8: // DEFLATED (raw RFC 1951 via zlib)
            return try inflateEntry(entry: entry, dataOffset: dataOffset, maxBytes: maxBytes)

        default:
            throw AndroidPackageError.unsupportedCompressionMethod(entry.compressionMethod, entry: entry.name)
        }
    }

    private func inflateEntry(
        entry: ZipCentralDirectoryEntry,
        dataOffset: UInt64,
        maxBytes: Int
    ) throws -> Data {
        var stream = z_stream()
        let initStatus = inflateInit2_(
            &stream,
            -MAX_WBITS,
            ZLIB_VERSION,
            Int32(MemoryLayout<z_stream>.size)
        )
        guard initStatus == Z_OK else {
            throw AndroidPackageError.decompressionFailed(entry: entry.name, status: initStatus)
        }
        defer { inflateEnd(&stream) }

        var output = Data()
        let reserveHint = Int(min(entry.uncompressedSize, UInt64(min(maxBytes, 1_048_576))))
        output.reserveCapacity(reserveHint)

        let readChunkSize = 65_536
        let outBufferSize = 65_536
        var outBuffer = [UInt8](repeating: 0, count: outBufferSize)
        var remaining = entry.compressedSize
        var cursor = dataOffset

        while true {
            let chunkLen = Int(min(remaining, UInt64(readChunkSize)))
            let compressedChunk: Data
            if chunkLen > 0 {
                compressedChunk = try source.read(offset: cursor, length: chunkLen)
                cursor += UInt64(compressedChunk.count)
                remaining -= UInt64(compressedChunk.count)
            } else {
                compressedChunk = Data()
            }

            var inflateResult: Int32 = Z_OK
            var hitLimit = false

            compressedChunk.withUnsafeBytes { rawIn in
                let inPtr = rawIn.bindMemory(to: Bytef.self).baseAddress
                stream.next_in = UnsafeMutablePointer(mutating: inPtr)
                stream.avail_in = uInt(compressedChunk.count)

                repeat {
                    outBuffer.withUnsafeMutableBytes { rawOut in
                        let outPtr = rawOut.bindMemory(to: Bytef.self).baseAddress
                        stream.next_out = outPtr
                        stream.avail_out = uInt(outBufferSize)
                        inflateResult = inflate(&stream, Z_NO_FLUSH)
                    }

                    let produced = outBufferSize - Int(stream.avail_out)
                    if produced > 0 {
                        if output.count + produced > maxBytes {
                            hitLimit = true
                            return
                        }
                        output.append(outBuffer, count: produced)
                    }
                } while stream.avail_out == 0 && inflateResult == Z_OK
            }

            if hitLimit {
                throw AndroidPackageError.entryTooLarge(entry: entry.name, limit: maxBytes)
            }

            if inflateResult == Z_STREAM_END {
                break
            }
            if inflateResult != Z_OK && inflateResult != Z_BUF_ERROR {
                throw AndroidPackageError.decompressionFailed(entry: entry.name, status: inflateResult)
            }
            if remaining == 0 && compressedChunk.isEmpty {
                break
            }
        }

        return output
    }

    // MARK: - Binary Helpers

    private static func findEOCD(in tail: Data) -> Int? {
        guard tail.count >= 22 else { return nil }
        for idx in stride(from: tail.count - 22, through: 0, by: -1) {
            if readUInt32LE(tail, at: idx) == sigEOCD {
                let commentLen = Int(readUInt16LE(tail, at: idx + 20))
                if idx + 22 + commentLen == tail.count {
                    return idx
                }
            }
        }
        return nil
    }

    private static func parseCentralDirectory(
        _ data: Data,
        expectedCount: Int,
        limit: Int
    ) throws -> [ZipCentralDirectoryEntry] {
        var entries: [ZipCentralDirectoryEntry] = []
        entries.reserveCapacity(min(expectedCount, limit))
        var offset = 0

        while offset + 46 <= data.count {
            guard readUInt32LE(data, at: offset) == sigCentralDirectory else {
                break
            }
            if entries.count >= limit {
                throw AndroidPackageError.tooManyArchiveEntries(count: entries.count + 1, limit: limit)
            }

            let method = readUInt16LE(data, at: offset + 10)
            let crc32 = readUInt32LE(data, at: offset + 16)
            var compressedSize = UInt64(readUInt32LE(data, at: offset + 20))
            var uncompressedSize = UInt64(readUInt32LE(data, at: offset + 24))
            let nameLen = Int(readUInt16LE(data, at: offset + 28))
            let extraLen = Int(readUInt16LE(data, at: offset + 30))
            let commentLen = Int(readUInt16LE(data, at: offset + 32))
            var localHeaderOffset = UInt64(readUInt32LE(data, at: offset + 42))

            let recordEnd = offset + 46 + nameLen + extraLen + commentLen
            guard recordEnd <= data.count else {
                throw AndroidPackageError.invalidZipArchive("Central Directory 记录被截断")
            }

            let nameStart = data.startIndex + offset + 46
            let nameSlice = data[nameStart..<(nameStart + nameLen)]
            let name = String(data: nameSlice, encoding: .utf8)
                ?? String(data: nameSlice, encoding: .isoLatin1)
                ?? ""

            // Parse ZIP64 extra field (header ID 0x0001) when 32-bit fields overflow.
            if uncompressedSize == 0xFFFF_FFFF || compressedSize == 0xFFFF_FFFF || localHeaderOffset == 0xFFFF_FFFF {
                let extraStart = offset + 46 + nameLen
                let extraEnd = extraStart + extraLen
                var cursor = extraStart
                while cursor + 4 <= extraEnd {
                    let headerId = readUInt16LE(data, at: cursor)
                    let fieldLen = Int(readUInt16LE(data, at: cursor + 2))
                    cursor += 4
                    guard cursor + fieldLen <= extraEnd else { break }
                    if headerId == 0x0001 {
                        var fieldPos = cursor
                        if uncompressedSize == 0xFFFF_FFFF, fieldPos + 8 <= cursor + fieldLen {
                            uncompressedSize = readUInt64LE(data, at: fieldPos)
                            fieldPos += 8
                        }
                        if compressedSize == 0xFFFF_FFFF, fieldPos + 8 <= cursor + fieldLen {
                            compressedSize = readUInt64LE(data, at: fieldPos)
                            fieldPos += 8
                        }
                        if localHeaderOffset == 0xFFFF_FFFF, fieldPos + 8 <= cursor + fieldLen {
                            localHeaderOffset = readUInt64LE(data, at: fieldPos)
                        }
                        break
                    }
                    cursor += fieldLen
                }
            }

            entries.append(
                ZipCentralDirectoryEntry(
                    name: name,
                    compressionMethod: method,
                    crc32: crc32,
                    compressedSize: compressedSize,
                    uncompressedSize: uncompressedSize,
                    localHeaderOffset: localHeaderOffset
                )
            )
            offset = recordEnd
        }

        return entries
    }

    private static func readUInt16LE(_ data: Data, at offset: Int) -> UInt16 {
        let base = data.startIndex + offset
        return UInt16(data[base]) | (UInt16(data[base + 1]) << 8)
    }

    private static func readUInt32LE(_ data: Data, at offset: Int) -> UInt32 {
        let base = data.startIndex + offset
        return UInt32(data[base])
            | (UInt32(data[base + 1]) << 8)
            | (UInt32(data[base + 2]) << 16)
            | (UInt32(data[base + 3]) << 24)
    }

    private static func readUInt64LE(_ data: Data, at offset: Int) -> UInt64 {
        let lo = UInt64(readUInt32LE(data, at: offset))
        let hi = UInt64(readUInt32LE(data, at: offset + 4))
        return lo | (hi << 32)
    }
}
