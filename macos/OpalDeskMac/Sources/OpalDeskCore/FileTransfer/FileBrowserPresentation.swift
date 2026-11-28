import AppKit
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

// MARK: - Pure Presentation Logic

public enum FileBrowserPresentation {
    public static let androidPackageExtensions: Set<String> = ["apk", "apks", "apkm", "xapk"]

    /// Default small-file direct-open threshold (10 MB). Set to `0` to always download.
    public static let defaultDirectOpenMaxMB: Int = 10
    public static let maxDirectOpenMaxMB: Int = 200
    public static let directOpenMaxMBOptions: [Int] = [0, 5, 10, 25, 50, 100]

    /// Executable / installer extensions that should never be auto-executed on double-click.
    private static let unsafeDirectOpenExtensions: Set<String> = [
        "app", "command", "tool", "pkg", "dmg", "sh", "zsh", "bash", "csh", "ksh",
        "exe", "msi", "bat", "cmd", "com", "scr", "pif", "vbs", "vbe", "js", "jse",
        "wsf", "wsh", "ps1", "hta", "cpl", "reg"
    ]

    public static func clampDirectOpenMaxMB(_ mb: Int) -> Int {
        min(max(mb, 0), maxDirectOpenMaxMB)
    }

    /// Returns `true` when double-clicking `item` should open it directly via the bounded temp cache
    /// instead of saving a permanent copy into the user's Downloads folder.
    public static func shouldDirectOpen(_ item: RemoteFileItem, maxSizeMB: Int) -> Bool {
        guard !item.isDirectory, !item.isAndroidPackage else { return false }
        let clampedMB = clampDirectOpenMaxMB(maxSizeMB)
        guard clampedMB > 0 else { return false }
        guard !unsafeDirectOpenExtensions.contains(item.fileExtension) else { return false }
        let thresholdBytes = Int64(clampedMB) * 1024 * 1024
        return item.sizeBytes >= 0 && item.sizeBytes <= thresholdBytes
    }

    private static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "dng", "raw", "avif"
    ]
    private static let videoExtensions: Set<String> = [
        "mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "flv", "ts", "mts", "wmv"
    ]
    private static let audioExtensions: Set<String> = [
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "aiff", "mid", "midi"
    ]
    private static let archiveExtensions: Set<String> = [
        "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "lz4", "iso"
    ]
    private static let documentExtensions: Set<String> = [
        "txt", "md", "rtf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv",
        "json", "xml", "yaml", "yml", "log", "html", "htm", "swift", "kt", "java", "py", "js", "ts", "sh"
    ]

    public static func isAndroidPackageFile(_ url: URL) -> Bool {
        androidPackageExtensions.contains(url.pathExtension.lowercased())
    }

    public static func isAndroidPackageName(_ name: String) -> Bool {
        let ext = URL(fileURLWithPath: name).pathExtension.lowercased()
        return androidPackageExtensions.contains(ext)
    }

    /// Normalizes a POSIX remote path by collapsing duplicate slashes and stripping trailing slashes (except `/`).
    public static func normalizePath(_ path: String) -> String {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "/" }
        let parts = trimmed.split(separator: "/", omittingEmptySubsequences: true)
        guard !parts.isEmpty else { return "/" }
        return "/" + parts.joined(separator: "/")
    }

    /// Splits a remote POSIX path such as `/sdcard/DCIM/Camera` into clickable breadcrumb segments.
    public static func pathBreadcrumbs(for path: String) -> [RemotePathBreadcrumb] {
        let normalized = normalizePath(path)
        var result: [RemotePathBreadcrumb] = [RemotePathBreadcrumb(title: "/", path: "/")]
        guard normalized != "/" else { return result }

        let segments = normalized.split(separator: "/", omittingEmptySubsequences: true)
        var running = ""
        for segment in segments {
            running += "/\(segment)"
            result.append(RemotePathBreadcrumb(title: String(segment), path: running))
        }
        return result
    }

    /// Returns the parent directory path, or `nil` when already at root `/`.
    public static func parentPath(of path: String) -> String? {
        let normalized = normalizePath(path)
        guard normalized != "/" else { return nil }
        let segments = normalized.split(separator: "/", omittingEmptySubsequences: true)
        guard segments.count > 1 else { return "/" }
        return "/" + segments.dropLast().joined(separator: "/")
    }

    /// Safely joins a single child name onto a remote directory path, rejecting traversal (`..`, `.`),
    /// empty/whitespace names, path separators (`/` or `\`), NUL bytes, and control characters.
    public static func joining(base: String, name: String) -> String? {
        let trimmedBase = base.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedBase.isEmpty, !trimmedBase.contains("\0") else { return nil }
        let baseComponents = trimmedBase.split(separator: "/", omittingEmptySubsequences: true)
        guard !baseComponents.contains("..") && !baseComponents.contains(".") else { return nil }

        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isValidEntryName(cleanName) else { return nil }

        let normalizedBase = normalizePath(trimmedBase)
        return normalizedBase == "/" ? "/\(cleanName)" : "\(normalizedBase)/\(cleanName)"
    }

    public static func isValidEntryName(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        guard trimmed != "." && trimmed != ".." else { return false }
        guard !trimmed.contains("/") && !trimmed.contains("\\") && !trimmed.contains("\0") else { return false }
        guard !trimmed.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            return false
        }
        return true
    }

    /// Classifies an item into its semantic `RemoteFileKind` using directory flag, extension, and MIME type.
    public static func fileKind(for item: RemoteFileItem) -> RemoteFileKind {
        if item.isDirectory { return .folder }

        let ext = item.fileExtension
        let mime = item.mimeType.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        if androidPackageExtensions.contains(ext) || mime == "application/vnd.android.package-archive" {
            return .androidPackage
        }
        if ext == "pdf" || mime == "application/pdf" {
            return .pdf
        }
        if imageExtensions.contains(ext) || mime.hasPrefix("image/") {
            return .image
        }
        if videoExtensions.contains(ext) || mime.hasPrefix("video/") {
            return .video
        }
        if audioExtensions.contains(ext) || mime.hasPrefix("audio/") {
            return .audio
        }
        if archiveExtensions.contains(ext) || mime.contains("zip") || mime.contains("compressed") || mime.contains("archive") {
            return .archive
        }
        if documentExtensions.contains(ext) || mime.hasPrefix("text/") || mime.contains("document") || mime.contains("spreadsheet") || mime.contains("presentation") {
            return .document
        }
        return .other
    }

    public static func sfSymbolName(for item: RemoteFileItem) -> String {
        fileKind(for: item).sfSymbolName
    }

    /// Filters and sorts file items, keeping directories grouped before regular files.
    public static func filteredAndSorted(
        _ items: [RemoteFileItem],
        query: String = "",
        sortBy: RemoteFileSortOption = .name,
        ascending: Bool = true,
        showHidden: Bool = false
    ) -> [RemoteFileItem] {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)

        let filtered = items.filter { item in
            if !showHidden && item.isHidden {
                return false
            }
            guard !trimmedQuery.isEmpty else { return true }
            return item.name.localizedCaseInsensitiveContains(trimmedQuery)
                || item.mimeType.localizedCaseInsensitiveContains(trimmedQuery)
        }

        return filtered.sorted { lhs, rhs in
            if lhs.isDirectory != rhs.isDirectory {
                return lhs.isDirectory && !rhs.isDirectory
            }
            return compareSameGroup(lhs, rhs, sortBy: sortBy, ascending: ascending)
        }
    }

    private static func compareSameGroup(
        _ lhs: RemoteFileItem,
        _ rhs: RemoteFileItem,
        sortBy: RemoteFileSortOption,
        ascending: Bool
    ) -> Bool {
        let nameCompare = {
            lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
        }

        switch sortBy {
        case .name:
            let cmp = lhs.name.localizedStandardCompare(rhs.name)
            if cmp == .orderedSame { return lhs.path < rhs.path }
            return ascending ? (cmp == .orderedAscending) : (cmp == .orderedDescending)

        case .size:
            if lhs.sizeBytes != rhs.sizeBytes {
                return ascending ? (lhs.sizeBytes < rhs.sizeBytes) : (lhs.sizeBytes > rhs.sizeBytes)
            }
            return nameCompare()

        case .modifiedDate:
            if lhs.modifiedAtMs != rhs.modifiedAtMs {
                return ascending ? (lhs.modifiedAtMs < rhs.modifiedAtMs) : (lhs.modifiedAtMs > rhs.modifiedAtMs)
            }
            return nameCompare()

        case .kind:
            let lhsOrder = fileKind(for: lhs).sortOrder
            let rhsOrder = fileKind(for: rhs).sortOrder
            if lhsOrder != rhsOrder {
                return ascending ? (lhsOrder < rhsOrder) : (lhsOrder > rhsOrder)
            }
            if lhs.fileExtension != rhs.fileExtension {
                return ascending ? (lhs.fileExtension < rhs.fileExtension) : (lhs.fileExtension > rhs.fileExtension)
            }
            return nameCompare()
        }
    }

    /// Computes normalized total & used percentage slices for each storage category.
    public static func categoryBreakdownPercentages(for report: RemoteStorageReport) -> [RemoteStorageCategorySlice] {
        let categorizedSum = report.categorizedBytes
        let totalDenominator = report.totalBytes > 0
            ? Double(report.totalBytes)
            : Double(max(report.usedBytes, categorizedSum))
        let usedDenominator = report.usedBytes > 0
            ? Double(report.usedBytes)
            : Double(categorizedSum)

        return report.categories.map { category in
            let safeBytes = Double(max(category.bytes, 0))
            let totalFrac = totalDenominator > 0 ? min(max(safeBytes / totalDenominator, 0), 1) : 0
            let usedFrac = usedDenominator > 0 ? min(max(safeBytes / usedDenominator, 0), 1) : 0
            return RemoteStorageCategorySlice(
                category: category,
                fractionOfTotal: totalFrac,
                fractionOfUsed: usedFrac,
                percentageText: formatPercentage(totalFrac)
            )
        }
    }

    public static func formatByteCount(_ bytes: Int64) -> String {
        let clamped = max(bytes, 0)
        return ByteCountFormatter.string(fromByteCount: clamped, countStyle: .file)
    }

    public static func formatPercentage(_ fraction: Double) -> String {
        let clamped = min(max(fraction, 0), 1)
        let percent = clamped * 100
        if percent == 0 {
            return "0%"
        }
        if percent < 0.1 {
            return "< 0.1%"
        }
        if percent >= 10 {
            return String(format: "%.0f%%", percent)
        }
        return String(format: "%.1f%%", percent)
    }

    public static func formatModifiedDate(_ modifiedAtMs: Int64) -> String {
        guard modifiedAtMs > 0 else { return "—" }
        let date = Date(timeIntervalSince1970: Double(modifiedAtMs) / 1000.0)
        return date.formatted(.dateTime.year().month(.twoDigits).day(.twoDigits).hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
    }

    /// Filters a list of candidate local URLs down to unique, existing regular files suitable for upload.
    public static func filterUploadableLocalFiles(_ urls: [URL]) -> [URL] {
        var seenPaths = Set<String>()
        var result: [URL] = []

        for url in urls where url.isFileURL {
            let standardized = url.standardizedFileURL
            let path = standardized.path
            guard !seenPaths.contains(path) else { continue }

            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: path, isDirectory: &isDir), !isDir.boolValue else {
                continue
            }
            seenPaths.insert(path)
            result.append(standardized)
        }
        return result
    }
}

// MARK: - Pasteboard File Extraction

public struct PasteboardFileInspection: Sendable, Equatable {
    public let fileURLs: [URL]
    public let directoryURLs: [URL]
    public let imageData: Data?
    public let plainText: String?

    public init(
        fileURLs: [URL],
        directoryURLs: [URL],
        imageData: Data?,
        plainText: String?
    ) {
        self.fileURLs = fileURLs
        self.directoryURLs = directoryURLs
        self.imageData = imageData
        self.plainText = plainText
    }
}

/// Pure NSPasteboard inspector shared by Finder `NSServices` and unit tests in `OpalDeskCoreTests`.
public enum PasteboardFileExtractor {
    private static let legacyFilenamesPasteboardType = NSPasteboard.PasteboardType("NSFilenamesPboardType")

    @MainActor
    public static func extractFileURLs(from pboard: NSPasteboard) -> [URL] {
        inspect(pboard).fileURLs
    }

    @MainActor
    public static func inspect(_ pboard: NSPasteboard) -> PasteboardFileInspection {
        var candidateURLs: [URL] = []

        if let objects = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] {
            candidateURLs.append(contentsOf: objects)
        }

        if let legacyPaths = pboard.propertyList(forType: legacyFilenamesPasteboardType) as? [String] {
            for path in legacyPaths where !path.isEmpty {
                candidateURLs.append(URL(fileURLWithPath: path))
            }
        }

        for item in pboard.pasteboardItems ?? [] {
            if let raw = item.string(forType: .fileURL), let parsed = URL(string: raw), parsed.isFileURL {
                candidateURLs.append(parsed)
            }
            if let text = item.string(forType: .string) {
                candidateURLs.append(contentsOf: parsePathURLsFromString(text))
            }
        }

        var files: [URL] = []
        var directories: [URL] = []
        var seen = Set<String>()

        for url in candidateURLs where url.isFileURL {
            let standardized = url.standardizedFileURL
            let path = standardized.path
            guard !seen.contains(path) else { continue }
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: path, isDirectory: &isDir) else { continue }
            seen.insert(path)
            if isDir.boolValue {
                directories.append(standardized)
            } else {
                files.append(standardized)
            }
        }

        let imageData = extractPNGData(from: pboard)
        let plainText: String? = {
            guard files.isEmpty, directories.isEmpty else { return nil }
            let raw = pboard.string(forType: .string)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return (raw?.isEmpty == false) ? raw : nil
        }()

        return PasteboardFileInspection(
            fileURLs: files,
            directoryURLs: directories,
            imageData: imageData,
            plainText: plainText
        )
    }

    /// Materializes an image or plain-text pasteboard payload to a temporary local file for transfer.
    public static func materializePayload(
        from inspection: PasteboardFileInspection,
        into directory: URL = FileManager.default.temporaryDirectory
    ) throws -> [URL] {
        if !inspection.fileURLs.isEmpty {
            return inspection.fileURLs
        }

        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)

        let timestamp = Int(Date().timeIntervalSince1970)
        let shortId = UUID().uuidString.prefix(6)

        if let imageData = inspection.imageData, !imageData.isEmpty {
            let outURL = directory.appendingPathComponent("OpalDesk-Image-\(timestamp)-\(shortId).png")
            try imageData.write(to: outURL, options: .atomic)
            return [outURL]
        }

        if let text = inspection.plainText, !text.isEmpty {
            let outURL = directory.appendingPathComponent("OpalDesk-Text-\(timestamp)-\(shortId).txt")
            try Data(text.utf8).write(to: outURL, options: .atomic)
            return [outURL]
        }

        return []
    }

    private static func parsePathURLsFromString(_ text: String) -> [URL] {
        var urls: [URL] = []
        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            if line.hasPrefix("file://"), let parsed = URL(string: line), parsed.isFileURL {
                urls.append(parsed)
            } else if line.hasPrefix("/") {
                urls.append(URL(fileURLWithPath: line))
            }
        }
        return urls
    }

    @MainActor
    private static func extractPNGData(from pboard: NSPasteboard) -> Data? {
        if let png = pboard.data(forType: .png), !png.isEmpty {
            return png
        }
        if let tiff = pboard.data(forType: .tiff),
           !tiff.isEmpty,
           let rep = NSBitmapImageRep(data: tiff),
           let png = rep.representation(using: .png, properties: [:]) {
            return png
        }
        return nil
    }
}
