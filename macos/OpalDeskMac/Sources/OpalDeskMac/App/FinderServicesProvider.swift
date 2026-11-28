import AppKit
import Foundation
import OpalDeskCore

/// macOS `NSServices` provider handling Finder right-click / Services menu
/// ("服务 → 发送到 Android 手机") for files, Android APK/XAPK bundles, images, and text.
@MainActor
public final class FinderServicesProvider: NSObject {
    public typealias FilesReceivedHandler = @MainActor ([URL]) -> Void
    public typealias ApkPackageReceivedHandler = @MainActor (URL) -> Void
    public typealias PlainTextReceivedHandler = @MainActor (String) -> Void

    private let temporaryDirectory: URL
    private let onFilesReceived: FilesReceivedHandler
    private let onApkPackageReceived: ApkPackageReceivedHandler?
    private let onPlainTextReceived: PlainTextReceivedHandler?

    public init(
        temporaryDirectory: URL = FileManager.default.temporaryDirectory,
        onFilesReceived: @escaping FilesReceivedHandler,
        onApkPackageReceived: ApkPackageReceivedHandler? = nil,
        onPlainTextReceived: PlainTextReceivedHandler? = nil
    ) {
        self.temporaryDirectory = temporaryDirectory
        self.onFilesReceived = onFilesReceived
        self.onApkPackageReceived = onApkPackageReceived
        self.onPlainTextReceived = onPlainTextReceived
        super.init()
    }

    /// Pure extraction helper exposing valid local file URLs present on an `NSPasteboard`.
    public static func extractFileURLs(from pboard: NSPasteboard) -> [URL] {
        PasteboardFileExtractor.extractFileURLs(from: pboard)
    }

    /// AppKit `NSServices` entry point invoked by macOS when the user triggers
    /// "服务 → 发送到 Android 手机" from Finder or another macOS app.
    @objc public func sendFilesToAndroid(
        _ pboard: NSPasteboard,
        userData: String?,
        error: AutoreleasingUnsafeMutablePointer<NSString>
    ) {
        _ = userData
        let inspection = PasteboardFileExtractor.inspect(pboard)

        if !inspection.fileURLs.isEmpty {
            if inspection.fileURLs.count == 1,
               let singleURL = inspection.fileURLs.first,
               FileBrowserPresentation.isAndroidPackageFile(singleURL),
               let onApkPackageReceived {
                onApkPackageReceived(singleURL)
                return
            }
            onFilesReceived(inspection.fileURLs)
            return
        }

        if !inspection.directoryURLs.isEmpty && inspection.imageData == nil && inspection.plainText == nil {
            let folderName = inspection.directoryURLs[0].lastPathComponent
            error.pointee = OpalL10n.format("app.finder.folder_unsupported_fmt", folderName) as NSString
            return
        }

        if let plainText = inspection.plainText,
           inspection.imageData == nil,
           let onPlainTextReceived {
            onPlainTextReceived(plainText)
            return
        }

        if inspection.imageData != nil || inspection.plainText != nil {
            do {
                let materializedURLs = try PasteboardFileExtractor.materializePayload(
                    from: inspection,
                    into: temporaryDirectory
                )
                guard !materializedURLs.isEmpty else {
                    error.pointee = OpalL10n.text("app.finder.temp_file_failed") as NSString
                    return
                }
                onFilesReceived(materializedURLs)
            } catch let materializeError {
                let reason = materializeError.localizedDescription
                OpalDeskLog.file.error("Finder service failed to materialize pasteboard payload: \(reason, privacy: .public)")
                error.pointee = OpalL10n.format("app.finder.prepare_failed_fmt", reason) as NSString
            }
            return
        }

        error.pointee = OpalL10n.text("app.finder.no_content") as NSString
    }
}
