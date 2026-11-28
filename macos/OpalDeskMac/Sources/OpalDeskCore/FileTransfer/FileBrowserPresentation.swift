import Foundation

// MARK: - File Types & Models

/// Semantic category for remote Android files, acting as the Single Source of Truth
/// for SF Symbol icon selection, sorting by kind, and visual tinting.
public enum RemoteFileKind: String, Sendable, Equatable, Hashable, CaseIterable {
    case folder
    case image
    case video
    case audio
    case pdf
    case archive
    case androidPackage
    case document
    case other

    public var sfSymbolName: String {
        switch self {
        case .folder: return "folder.fill"
        case .image: return "photo.fill"
        case .video: return "film.fill"
        case .audio: return "music.note"
        case .pdf: return "doc.richtext.fill"
        case .archive: return "doc.zipper"
        case .androidPackage: return "shippingbox.fill"
        case .document: return "doc.text.fill"
        case .other: return "doc.fill"
        }
    }

    public var displayLabel: String {
        switch self {
        case .folder: return "文件夹"
        case .image: return "图片"
        case .video: return "视频"
        case .audio: return "音频"
        case .pdf: return "PDF 文档"
        case .archive: return "压缩包"
        case .androidPackage: return "安装包"
        case .document: return "文稿"
        case .other: return "文件"
        }
    }

    public var colorHint: RemoteStorageColorHint {
        switch self {
        case .folder: return .blue
        case .image: return .pink
        case .video: return .purple
        case .audio: return .teal
        case .pdf: return .orange
        case .archive: return .indigo
        case .androidPackage: return .green
        case .document: return .blue
        case .other: return .gray
        }
    }

    var sortOrder: Int {
        switch self {
        case .folder: return 0
        case .androidPackage: return 1
        case .image: return 2
        case .video: return 3
        case .audio: return 4
        case .pdf: return 5
        case .document: return 6
        case .archive: return 7
        case .other: return 8
        }
    }
}

/// Decoupled presentation model representing an Android file or directory entry.
public struct RemoteFileItem: Identifiable, Sendable, Equatable, Hashable {
    public var id: String { path }

    public let name: String
    public let path: String
    public let isDirectory: Bool
    public let sizeBytes: Int64
    public let modifiedAtMs: Int64
    public let mimeType: String
    public var thumbnailData: Data?

    public init(
        name: String,
        path: String,
        isDirectory: Bool,
        sizeBytes: Int64,
        modifiedAtMs: Int64,
        mimeType: String = "",
        thumbnailData: Data? = nil
    ) {
        self.name = name
        self.path = path
        self.isDirectory = isDirectory
        self.sizeBytes = sizeBytes
        self.modifiedAtMs = modifiedAtMs
        self.mimeType = mimeType
        self.thumbnailData = thumbnailData
    }

    public var fileExtension: String {
        guard !isDirectory else { return "" }
        return URL(fileURLWithPath: name).pathExtension.lowercased()
    }

    public var isHidden: Bool {
        name.hasPrefix(".")
    }

    public var isAndroidPackage: Bool {
        !isDirectory && FileBrowserPresentation.androidPackageExtensions.contains(fileExtension)
    }

    public var kind: RemoteFileKind {
        FileBrowserPresentation.fileKind(for: self)
    }

    public var sfSymbolName: String {
        FileBrowserPresentation.sfSymbolName(for: self)
    }

    public var formattedSize: String {
        isDirectory ? "—" : FileBrowserPresentation.formatByteCount(sizeBytes)
    }

    public var formattedModifiedDate: String {
        FileBrowserPresentation.formatModifiedDate(modifiedAtMs)
    }
}

// MARK: - Storage Analysis Models

/// Semantic color roles mapped to system adaptive colors in the SwiftUI layer.
public enum RemoteStorageColorHint: String, Sendable, Equatable, Hashable, CaseIterable, Codable {
    case blue
    case purple
    case pink
    case orange
    case green
    case teal
    case indigo
    case gray

    public static func defaultHint(for categoryName: String) -> RemoteStorageColorHint {
        let normalized = categoryName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if normalized.contains("图") || normalized.contains("相") || normalized.contains("photo") || normalized.contains("image") {
            return .pink
        }
        if normalized.contains("视频") || normalized.contains("影") || normalized.contains("video") {
            return .purple
        }
        if normalized.contains("音") || normalized.contains("audio") || normalized.contains("music") {
            return .teal
        }
        if normalized.contains("文") || normalized.contains("doc") {
            return .blue
        }
        if normalized.contains("安装") || normalized.contains("apk") || normalized.contains("package") || normalized.contains("app") {
            return .orange
        }
        if normalized.contains("压") || normalized.contains("archive") || normalized.contains("zip") {
            return .indigo
        }
        return .gray
    }
}

/// Storage breakdown bucket for a single Android file category.
public struct RemoteStorageCategory: Identifiable, Sendable, Equatable, Hashable {
    public var id: String { name }

    public let name: String
    public let bytes: Int64
    public let fileCount: Int
    public let colorHint: RemoteStorageColorHint

    public init(
        name: String,
        bytes: Int64,
        fileCount: Int,
        colorHint: RemoteStorageColorHint? = nil
    ) {
        self.name = name
        self.bytes = max(bytes, 0)
        self.fileCount = max(fileCount, 0)
        self.colorHint = colorHint ?? RemoteStorageColorHint.defaultHint(for: name)
    }
}

/// Overall Android storage report including categories, folder drill-down, app usage, and largest files leaderboard.
public struct RemoteStorageReport: Sendable, Equatable {
    public let totalBytes: Int64
    public let freeBytes: Int64
    public let usedBytes: Int64
    public let categories: [RemoteStorageCategory]
    public let largeFiles: [RemoteFileItem]
    public var folders: [FolderStorageEntry]
    public var apps: [AppStorageEntry]

    public init(
        totalBytes: Int64,
        freeBytes: Int64,
        usedBytes: Int64,
        categories: [RemoteStorageCategory],
        largeFiles: [RemoteFileItem] = [],
        folders: [FolderStorageEntry] = [],
        apps: [AppStorageEntry] = []
    ) {
        self.totalBytes = max(totalBytes, 0)
        self.freeBytes = max(freeBytes, 0)
        self.usedBytes = max(usedBytes, 0)
        self.categories = categories
        self.largeFiles = largeFiles
        self.folders = folders
        self.apps = apps
    }

    public init(summary: RemoteStorageSummaryPayload) {
        self.init(
            totalBytes: summary.totalBytes,
            freeBytes: summary.freeBytes,
            usedBytes: summary.usedBytes,
            categories: summary.categories.map {
                RemoteStorageCategory(name: $0.name, bytes: $0.bytes, fileCount: $0.fileCount)
            },
            largeFiles: summary.largeFiles.map {
                RemoteFileItem(
                    name: $0.name,
                    path: $0.path,
                    isDirectory: false,
                    sizeBytes: $0.size,
                    modifiedAtMs: $0.modifiedMs
                )
            },
            folders: summary.folders,
            apps: summary.apps
        )
    }

    public var usedFractionOfTotal: Double {
        guard totalBytes > 0 else { return 0 }
        return min(max(Double(usedBytes) / Double(totalBytes), 0), 1)
    }

    public var categorizedBytes: Int64 {
        categories.reduce(0) { $0 + max($1.bytes, 0) }
    }

    /// Remaining used bytes not attributed to a specific scanned category (e.g. OS/app data).
    public var uncategorizedUsedBytes: Int64 {
        max(usedBytes - categorizedBytes, 0)
    }
}

/// Computed percentage slice for rendering storage charts and legend rows.
public struct RemoteStorageCategorySlice: Identifiable, Sendable, Equatable {
    public var id: String { category.id }

    public let category: RemoteStorageCategory
    public let fractionOfTotal: Double
    public let fractionOfUsed: Double
    public let percentageText: String

    public init(
        category: RemoteStorageCategory,
        fractionOfTotal: Double,
        fractionOfUsed: Double,
        percentageText: String
    ) {
        self.category = category
        self.fractionOfTotal = fractionOfTotal
        self.fractionOfUsed = fractionOfUsed
        self.percentageText = percentageText
    }
}

// MARK: - Sorting, Navigation & Transfer Models

public enum RemoteFileSortOption: String, Sendable, Equatable, Hashable, CaseIterable, Identifiable {
    case name
    case size
    case modifiedDate
    case kind

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .name: return "名称"
        case .size: return "大小"
        case .modifiedDate: return "修改时间"
        case .kind: return "类型"
        }
    }

    public var defaultAscending: Bool {
        switch self {
        case .name, .kind: return true
        case .size, .modifiedDate: return false
        }
    }
}

public enum RemoteFileTransferDirection: String, Sendable, Equatable, Hashable, Codable {
    case upload
    case download

    public var displayName: String {
        switch self {
        case .upload: return "上传"
        case .download: return "下载"
        }
    }

    public var sfSymbolName: String {
        switch self {
        case .upload: return "arrow.up.circle.fill"
        case .download: return "arrow.down.circle.fill"
        }
    }
}

public enum RemoteFileTransferState: Sendable, Equatable, Hashable {
    case queued
    case inProgress
    case completed
    case failed(String)
    case cancelled

    public var isTerminal: Bool {
        switch self {
        case .completed, .failed, .cancelled: return true
        case .queued, .inProgress: return false
        }
    }
}

/// Bidirectional file transfer progress item rendered by the transfer queue bar.
public struct RemoteFileTransferItem: Identifiable, Sendable, Equatable, Hashable {
    public let id: String
    public let direction: RemoteFileTransferDirection
    public let fileName: String
    public let localURL: URL?
    public let remotePath: String
    public var totalBytes: Int64
    public var transferredBytes: Int64
    public var state: RemoteFileTransferState

    public init(
        id: String,
        direction: RemoteFileTransferDirection,
        fileName: String,
        localURL: URL? = nil,
        remotePath: String,
        totalBytes: Int64,
        transferredBytes: Int64 = 0,
        state: RemoteFileTransferState = .inProgress
    ) {
        self.id = id
        self.direction = direction
        self.fileName = fileName
        self.localURL = localURL
        self.remotePath = remotePath
        self.totalBytes = max(totalBytes, 0)
        self.transferredBytes = max(transferredBytes, 0)
        self.state = state
    }

    public var fractionCompleted: Double {
        if state == .completed { return 1.0 }
        guard totalBytes > 0 else { return 0.0 }
        return min(max(Double(transferredBytes) / Double(totalBytes), 0), 1)
    }

    public var statusSummary: String {
        switch state {
        case .queued:
            return "等待\(direction.displayName)…"
        case .inProgress:
            if totalBytes > 0 {
                let pct = Int((fractionCompleted * 100).rounded())
                return "\(FileBrowserPresentation.formatByteCount(transferredBytes)) / \(FileBrowserPresentation.formatByteCount(totalBytes)) (\(pct)%)"
            }
            return "\(direction.displayName)中…"
        case .completed:
            return "\(direction.displayName)完成 · \(remotePath)"
        case .failed(let reason):
            return "\(direction.displayName)失败：\(reason)"
        case .cancelled:
            return "已取消\(direction.displayName)"
        }
    }
}

/// Clickable path segment for breadcrumb navigation.
public struct RemotePathBreadcrumb: Identifiable, Sendable, Equatable, Hashable {
    public var id: String { path }
    public let title: String
    public let path: String

    public init(title: String, path: String) {
        self.title = title
        self.path = path
    }
}

/// Quick-access Android directory entry shown in the browser sidebar.
public struct RemoteQuickLocation: Identifiable, Sendable, Equatable, Hashable {
    public var id: String { path }
    public let title: String
    public let subtitle: String
    public let path: String
    public let sfSymbolName: String

    public init(title: String, subtitle: String = "", path: String, sfSymbolName: String) {
        self.title = title
        self.subtitle = subtitle
        self.path = path
        self.sfSymbolName = sfSymbolName
    }

    public static let defaultLocations: [RemoteQuickLocation] = [
        RemoteQuickLocation(title: "内部存储", subtitle: "/sdcard", path: "/sdcard", sfSymbolName: "internaldrive"),
        RemoteQuickLocation(title: "相册", subtitle: "DCIM", path: "/sdcard/DCIM", sfSymbolName: "photo.on.rectangle.angled"),
        RemoteQuickLocation(title: "下载", subtitle: "Download", path: "/sdcard/Download", sfSymbolName: "arrow.down.circle"),
        RemoteQuickLocation(title: "文档", subtitle: "Documents", path: "/sdcard/Documents", sfSymbolName: "doc.text"),
        RemoteQuickLocation(title: "OpalDesk 接收", subtitle: "Download/OpalDesk", path: "/sdcard/Download/OpalDesk", sfSymbolName: "tray.and.arrow.down"),
        RemoteQuickLocation(title: "微信下载", subtitle: "Download/WeiXin", path: "/sdcard/Download/WeiXin", sfSymbolName: "bubble.left.and.bubble.right")
    ]
}
