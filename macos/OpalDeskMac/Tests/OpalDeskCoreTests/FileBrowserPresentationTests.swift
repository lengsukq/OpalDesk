import AppKit
import Foundation
import Testing
@testable import OpalDeskCore

@Suite("File Browser Presentation, Storage Breakdown & Pasteboard Extraction")
struct FileBrowserPresentationTests {

    @Test("Path normalization and breadcrumbs split POSIX paths cleanly")
    func breadcrumbsAndParentPath() {
        #expect(FileBrowserPresentation.normalizePath("") == "/")
        #expect(FileBrowserPresentation.normalizePath("///sdcard//DCIM/Camera/") == "/sdcard/DCIM/Camera")

        let rootCrumbs = FileBrowserPresentation.pathBreadcrumbs(for: "/")
        #expect(rootCrumbs == [RemotePathBreadcrumb(title: "/", path: "/")])
        #expect(FileBrowserPresentation.parentPath(of: "/") == nil)

        let cameraCrumbs = FileBrowserPresentation.pathBreadcrumbs(for: "/sdcard/DCIM/Camera/")
        #expect(cameraCrumbs.map(\.title) == ["/", "sdcard", "DCIM", "Camera"])
        #expect(cameraCrumbs.map(\.path) == ["/", "/sdcard", "/sdcard/DCIM", "/sdcard/DCIM/Camera"])

        #expect(FileBrowserPresentation.parentPath(of: "/sdcard/DCIM/Camera") == "/sdcard/DCIM")
        #expect(FileBrowserPresentation.parentPath(of: "/sdcard") == "/")
    }

    @Test("Safe path joining rejects traversal, empty names, separators, and control characters")
    func safePathJoining() {
        #expect(FileBrowserPresentation.joining(base: "/sdcard/Download", name: "OpalDesk") == "/sdcard/Download/OpalDesk")
        #expect(FileBrowserPresentation.joining(base: "/", name: "sdcard") == "/sdcard")
        #expect(FileBrowserPresentation.joining(base: "/sdcard/DCIM/", name: "  Vacation 2026  ") == "/sdcard/DCIM/Vacation 2026")
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: "report..pdf") == "/sdcard/report..pdf")

        // Rejected inputs
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: "") == nil)
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: "   ") == nil)
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: ".") == nil)
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: "..") == nil)
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: "../etc") == nil)
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: "sub/folder") == nil)
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: "sub\\folder") == nil)
        #expect(FileBrowserPresentation.joining(base: "/sdcard", name: "bad\0name") == nil)
        #expect(FileBrowserPresentation.joining(base: "/sdcard/../etc", name: "passwd") == nil)
        #expect(FileBrowserPresentation.joining(base: "", name: "file.txt") == nil)
    }

    @Test("SF Symbol mapping classifies folders, media, PDF, archives, APK bundles, and documents")
    func sfSymbolClassification() {
        let cases: [(RemoteFileItem, String)] = [
            (RemoteFileItem(name: "DCIM", path: "/sdcard/DCIM", isDirectory: true, sizeBytes: 0, modifiedAtMs: 0), "folder.fill"),
            (RemoteFileItem(name: "photo.HEIC", path: "/sdcard/photo.HEIC", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "photo.fill"),
            (RemoteFileItem(name: "clip.mp4", path: "/sdcard/clip.mp4", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "film.fill"),
            (RemoteFileItem(name: "song.flac", path: "/sdcard/song.flac", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "music.note"),
            (RemoteFileItem(name: "spec.pdf", path: "/sdcard/spec.pdf", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "doc.richtext.fill"),
            (RemoteFileItem(name: "backup.tar.gz", path: "/sdcard/backup.tar.gz", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "doc.zipper"),
            (RemoteFileItem(name: "app-release.apk", path: "/sdcard/app-release.apk", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "shippingbox.fill"),
            (RemoteFileItem(name: "bundle.xapk", path: "/sdcard/bundle.xapk", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "shippingbox.fill"),
            (RemoteFileItem(name: "notes.md", path: "/sdcard/notes.md", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "doc.text.fill"),
            (RemoteFileItem(name: "blob.bin", path: "/sdcard/blob.bin", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 0), "doc.fill")
        ]

        for (item, expectedSymbol) in cases {
            #expect(FileBrowserPresentation.sfSymbolName(for: item) == expectedSymbol)
        }
    }

    @Test("Filtering and sorting keeps folders first, respects hidden toggle, search query, and sort directions")
    func filteringAndSorting() {
        let items = [
            RemoteFileItem(name: ".nomedia", path: "/sdcard/.nomedia", isDirectory: false, sizeBytes: 0, modifiedAtMs: 100),
            RemoteFileItem(name: "file10.txt", path: "/sdcard/file10.txt", isDirectory: false, sizeBytes: 500, modifiedAtMs: 300),
            RemoteFileItem(name: "file2.txt", path: "/sdcard/file2.txt", isDirectory: false, sizeBytes: 2_000, modifiedAtMs: 200),
            RemoteFileItem(name: "Downloads", path: "/sdcard/Downloads", isDirectory: true, sizeBytes: 0, modifiedAtMs: 150),
            RemoteFileItem(name: "DCIM", path: "/sdcard/DCIM", isDirectory: true, sizeBytes: 0, modifiedAtMs: 400)
        ]

        let defaultSorted = FileBrowserPresentation.filteredAndSorted(items, query: "", sortBy: .name, ascending: true, showHidden: false)
        #expect(defaultSorted.map(\.name) == ["DCIM", "Downloads", "file2.txt", "file10.txt"])

        let withHidden = FileBrowserPresentation.filteredAndSorted(items, query: "", sortBy: .name, ascending: true, showHidden: true)
        #expect(withHidden.contains(where: { $0.name == ".nomedia" }))

        let bySizeDesc = FileBrowserPresentation.filteredAndSorted(items, query: "", sortBy: .size, ascending: false, showHidden: false)
        #expect(bySizeDesc.map(\.name) == ["DCIM", "Downloads", "file2.txt", "file10.txt"])

        let queried = FileBrowserPresentation.filteredAndSorted(items, query: "file", sortBy: .modifiedDate, ascending: false, showHidden: false)
        #expect(queried.map(\.name) == ["file10.txt", "file2.txt"])
    }

    @Test("Storage breakdown calculates safe fractions, uncategorized remainder, and formatting")
    func storageBreakdownAndFormatting() {
        let report = RemoteStorageReport(
            totalBytes: 100_000,
            freeBytes: 25_000,
            usedBytes: 75_000,
            categories: [
                RemoteStorageCategory(name: "图片", bytes: 40_000, fileCount: 120),
                RemoteStorageCategory(name: "视频", bytes: 20_000, fileCount: 8),
                RemoteStorageCategory(name: "其他", bytes: 5_000, fileCount: 14)
            ]
        )

        #expect(report.usedFractionOfTotal == 0.75)
        #expect(report.uncategorizedUsedBytes == 10_000)

        let slices = FileBrowserPresentation.categoryBreakdownPercentages(for: report)
        #expect(slices.count == 3)
        #expect(slices[0].category.colorHint == .pink)
        #expect(abs(slices[0].fractionOfTotal - 0.40) < 0.0001)
        #expect(slices[0].percentageText == "40%")
        #expect(slices[2].percentageText == "5.0%")

        let emptyReport = RemoteStorageReport(totalBytes: 0, freeBytes: 0, usedBytes: 0, categories: [
            RemoteStorageCategory(name: "文档", bytes: 0, fileCount: 0)
        ])
        let emptySlices = FileBrowserPresentation.categoryBreakdownPercentages(for: emptyReport)
        #expect(emptySlices[0].fractionOfTotal == 0)
        #expect(emptySlices[0].percentageText == "0%")
        #expect(!FileBrowserPresentation.formatByteCount(0).isEmpty)
        #expect(FileBrowserPresentation.formatByteCount(-42) == FileBrowserPresentation.formatByteCount(0))
        #expect(FileBrowserPresentation.formatPercentage(0.0002) == "< 0.1%")
        #expect(FileBrowserPresentation.formatModifiedDate(0) == "—")
        #expect(FileBrowserPresentation.formatModifiedDate(1_700_000_000_000) != "—")
    }

    @Test("Sorting by kind, extension flags, and quick locations stay consistent")
    func kindSortAndPackageDetection() {
        let items = [
            RemoteFileItem(name: "notes.txt", path: "/sdcard/notes.txt", isDirectory: false, sizeBytes: 100, modifiedAtMs: 1),
            RemoteFileItem(name: "photo.jpg", path: "/sdcard/photo.jpg", isDirectory: false, sizeBytes: 100, modifiedAtMs: 1),
            RemoteFileItem(name: "build.APKM", path: "/sdcard/build.APKM", isDirectory: false, sizeBytes: 100, modifiedAtMs: 1),
            RemoteFileItem(name: "bundle.apks", path: "/sdcard/bundle.apks", isDirectory: false, sizeBytes: 100, modifiedAtMs: 1),
            RemoteFileItem(name: "Apps", path: "/sdcard/Apps", isDirectory: true, sizeBytes: 0, modifiedAtMs: 1)
        ]

        #expect(items[2].isAndroidPackage)
        #expect(items[3].isAndroidPackage)
        #expect(!items[0].isAndroidPackage)
        #expect(!items[4].isAndroidPackage)

        let byKind = FileBrowserPresentation.filteredAndSorted(items, sortBy: .kind, ascending: true)
        #expect(byKind.map(\.name) == ["Apps", "build.APKM", "bundle.apks", "photo.jpg", "notes.txt"])
        #expect(RemoteQuickLocation.defaultLocations.contains(where: { $0.path == "/sdcard/DCIM" }))
        #expect(RemoteQuickLocation.defaultLocations.contains(where: { $0.path == "/sdcard/Download/OpalDesk" }))
    }

    @Test("RemoteFileTransferItem handles zero-byte and terminal states safely")
    func transferItemProgress() {
        let active = RemoteFileTransferItem(
            id: "up-1",
            direction: .upload,
            fileName: "archive.zip",
            remotePath: "/sdcard/Download/archive.zip",
            totalBytes: 1_000,
            transferredBytes: 250,
            state: .inProgress
        )
        #expect(active.fractionCompleted == 0.25)
        #expect(!active.state.isTerminal)

        let zeroByteCompleted = RemoteFileTransferItem(
            id: "dl-1",
            direction: .download,
            fileName: "empty.txt",
            remotePath: "/sdcard/empty.txt",
            totalBytes: 0,
            transferredBytes: 0,
            state: .completed
        )
        #expect(zeroByteCompleted.fractionCompleted == 1.0)
        #expect(zeroByteCompleted.state.isTerminal)
    }

    @Test("PasteboardFileExtractor resolves file URLs, path strings, filters folders, and materializes images")
    @MainActor
    func pasteboardExtractionAndMaterialization() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-pboard-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileA = tempDir.appendingPathComponent("sample.apk")
        let fileB = tempDir.appendingPathComponent("notes.txt")
        let subDir = tempDir.appendingPathComponent("subfolder", isDirectory: true)
        try Data("apk".utf8).write(to: fileA)
        try Data("hello".utf8).write(to: fileB)
        try FileManager.default.createDirectory(at: subDir, withIntermediateDirectories: true)

        let pboard = NSPasteboard.withUniqueName()
        defer { pboard.releaseGlobally() }

        pboard.clearContents()
        pboard.writeObjects([fileA as NSURL, fileB as NSURL, subDir as NSURL])

        let extracted = PasteboardFileExtractor.extractFileURLs(from: pboard)
        #expect(extracted == [fileA.standardizedFileURL, fileB.standardizedFileURL])
        #expect(FileBrowserPresentation.isAndroidPackageFile(extracted[0]))
        #expect(!FileBrowserPresentation.isAndroidPackageFile(extracted[1]))

        // Legacy NSFilenamesPboardType resolves existing files cleanly
        pboard.clearContents()
        pboard.declareTypes([NSPasteboard.PasteboardType("NSFilenamesPboardType")], owner: nil)
        pboard.setPropertyList([fileA.path, subDir.path], forType: NSPasteboard.PasteboardType("NSFilenamesPboardType"))
        #expect(PasteboardFileExtractor.extractFileURLs(from: pboard) == [fileA.standardizedFileURL])

        // Plain-text path lines resolve cleanly while nonexistent paths are ignored
        pboard.clearContents()
        pboard.setString("\(fileB.path)\n/nonexistent/opaldesk-missing.txt", forType: .string)
        let fromText = PasteboardFileExtractor.extractFileURLs(from: pboard)
        #expect(fromText == [fileB.standardizedFileURL])

        // Arbitrary plain text materializes into a UTF-8 .txt file when no paths match
        pboard.clearContents()
        pboard.setString("Send this note to Android", forType: .string)
        let textInspection = PasteboardFileExtractor.inspect(pboard)
        #expect(textInspection.fileURLs.isEmpty)
        #expect(textInspection.plainText == "Send this note to Android")
        let materializedText = try PasteboardFileExtractor.materializePayload(from: textInspection, into: tempDir)
        #expect(materializedText.count == 1)
        #expect(materializedText[0].pathExtension == "txt")
        #expect(try String(contentsOf: materializedText[0], encoding: .utf8) == "Send this note to Android")

        // Raw PNG data without file URLs materializes into a temporary PNG file
        pboard.clearContents()
        let fakePng = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        pboard.setData(fakePng, forType: .png)
        let inspection = PasteboardFileExtractor.inspect(pboard)
        #expect(inspection.fileURLs.isEmpty)
        #expect(inspection.imageData == fakePng)
        let materialized = try PasteboardFileExtractor.materializePayload(from: inspection, into: tempDir)
        #expect(materialized.count == 1)
        #expect(materialized[0].pathExtension == "png")
        #expect(try Data(contentsOf: materialized[0]) == fakePng)
    }

    @Test("Small-file direct-open policy respects configurable MB threshold and blocks executables")
    func smallFileDirectOpenPolicy() {
        let tenMB: Int64 = 10 * 1024 * 1024
        let pdfUnder = RemoteFileItem(name: "report.pdf", path: "/sdcard/Download/report.pdf", isDirectory: false, sizeBytes: tenMB, modifiedAtMs: 1)
        let videoOver = RemoteFileItem(name: "clip.mp4", path: "/sdcard/DCIM/clip.mp4", isDirectory: false, sizeBytes: tenMB + 1, modifiedAtMs: 1)
        let folder = RemoteFileItem(name: "Docs", path: "/sdcard/Docs", isDirectory: true, sizeBytes: 0, modifiedAtMs: 1)
        let apk = RemoteFileItem(name: "app.apk", path: "/sdcard/app.apk", isDirectory: false, sizeBytes: 1024, modifiedAtMs: 1)
        let script = RemoteFileItem(name: "run.command", path: "/sdcard/run.command", isDirectory: false, sizeBytes: 512, modifiedAtMs: 1)
        let winExe = RemoteFileItem(name: "setup.exe", path: "/sdcard/setup.exe", isDirectory: false, sizeBytes: 2048, modifiedAtMs: 1)

        #expect(FileBrowserPresentation.defaultDirectOpenMaxMB == 10)
        #expect(FileBrowserPresentation.clampDirectOpenMaxMB(-5) == 0)
        #expect(FileBrowserPresentation.clampDirectOpenMaxMB(999) == FileBrowserPresentation.maxDirectOpenMaxMB)

        // Default 10 MB threshold: <= 10 MB safe files open directly; > 10 MB downloads
        #expect(FileBrowserPresentation.shouldDirectOpen(pdfUnder, maxSizeMB: 10))
        #expect(!FileBrowserPresentation.shouldDirectOpen(videoOver, maxSizeMB: 10))
        #expect(FileBrowserPresentation.shouldDirectOpen(videoOver, maxSizeMB: 25))

        // Disabled (0 MB) always downloads
        #expect(!FileBrowserPresentation.shouldDirectOpen(pdfUnder, maxSizeMB: 0))

        // Directories, APKs, and executable scripts/binaries never auto direct-open
        #expect(!FileBrowserPresentation.shouldDirectOpen(folder, maxSizeMB: 100))
        #expect(!FileBrowserPresentation.shouldDirectOpen(apk, maxSizeMB: 100))
        #expect(!FileBrowserPresentation.shouldDirectOpen(script, maxSizeMB: 100))
        #expect(!FileBrowserPresentation.shouldDirectOpen(winExe, maxSizeMB: 100))
    }
}
