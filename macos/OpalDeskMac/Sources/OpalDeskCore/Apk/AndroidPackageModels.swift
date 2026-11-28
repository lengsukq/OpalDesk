import Foundation

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

// MARK: - Container Metadata Decoders (XAPK / APKM)

struct FlexibleScalar: Decodable {
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

struct XapkManifestMetadata: Decodable {
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

struct ApkmInfoMetadata: Decodable {
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
