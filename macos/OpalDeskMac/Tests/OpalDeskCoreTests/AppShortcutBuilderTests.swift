import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import OpalDeskCore

@Suite("OpalDesk Deep Link & Native macOS App Shortcut Builder")
struct AppShortcutBuilderTests {

    // MARK: - OpalDeskDeepLink Tests

    @Test("Builds and round-trip parses opaldesk://popout URL with Unicode & special characters")
    func deepLinkRoundTrip() throws {
        let url = try OpalDeskDeepLink.buildPopoutURL(
            packageName: "com.tencent.mm",
            activityClass: ".ui.LauncherUI",
            label: "微信 & 工作=群?",
            deviceKey: "adb-7145c462._adb-tls-connect._tcp",
            deviceName: "Xiaomi 15 Pro"
        )

        #expect(url.scheme == "opaldesk")
        #expect(url.host == "popout")

        let route = try OpalDeskDeepLink.parse(url)
        #expect(
            route == .popout(
                .init(
                    packageName: "com.tencent.mm",
                    activityClass: ".ui.LauncherUI",
                    label: "微信 & 工作=群?",
                    deviceKey: "adb-7145c462._adb-tls-connect._tcp",
                    deviceName: "Xiaomi 15 Pro"
                )
            )
        )
    }

    @Test("Rejects malformed schemes, invalid package/activity names, and injection control characters")
    func deepLinkRejectsIllegalInputs() {
        #expect(throws: OpalDeskDeepLink.ValidationError.unsupportedScheme("https")) {
            _ = try OpalDeskDeepLink.parse("https://popout?pkg=com.example.app")
        }
        #expect(throws: OpalDeskDeepLink.ValidationError.unsupportedHost("exec")) {
            _ = try OpalDeskDeepLink.parse("opaldesk://exec?pkg=com.example.app")
        }
        #expect(throws: OpalDeskDeepLink.ValidationError.missingPackageName) {
            _ = try OpalDeskDeepLink.parse("opaldesk://popout?label=Test")
        }
        #expect(throws: OpalDeskDeepLink.ValidationError.invalidPackageName("com.bad;rm-rf")) {
            _ = try OpalDeskDeepLink.buildPopoutURL(packageName: "com.bad;rm-rf")
        }
        #expect(throws: OpalDeskDeepLink.ValidationError.invalidPackageName("singleword")) {
            _ = try OpalDeskDeepLink.buildPopoutURL(packageName: "singleword")
        }
        #expect(throws: OpalDeskDeepLink.ValidationError.invalidActivityClass("../EscapeActivity")) {
            _ = try OpalDeskDeepLink.buildPopoutURL(packageName: "com.example.app", activityClass: "../EscapeActivity")
        }
        #expect(throws: OpalDeskDeepLink.ValidationError.invalidDeviceKey("dev;whoami")) {
            _ = try OpalDeskDeepLink.buildPopoutURL(packageName: "com.example.app", deviceKey: "dev;whoami")
        }
        #expect(throws: OpalDeskDeepLink.ValidationError.containsForbiddenControlCharacters(field: "label")) {
            _ = try OpalDeskDeepLink.buildPopoutURL(packageName: "com.example.app", label: "Evil\nrm -rf /")
        }
    }

    // MARK: - Shell Escaping & Bundle Creation Tests

    @Test("POSIX single-quote escaping neutralizes shell metacharacters and embedded quotes")
    func shellEscapingSafety() {
        let payload = "opaldesk://popout?pkg=com.a.b&label='; rm -rf /; $(id) `whoami` \"$HOME\""
        let escaped = AppShortcutBuilder.shellSingleQuoteEscape(payload)
        #expect(escaped.hasPrefix("'"))
        #expect(escaped.hasSuffix("'"))
        #expect(escaped.contains("'\\''"))
        // Double-check script structure uses exec /usr/bin/open -u
        let url = URL(string: "opaldesk://popout?pkg=com.a.b&label=hello%27world")!
        let script = AppShortcutBuilder.buildLaunchScript(deepLinkURL: url)
        #expect(script.hasPrefix("#!/bin/sh\n"))
        #expect(script.contains("exec /usr/bin/open -u 'opaldesk://popout?pkg=com.a.b&label=hello%27world'"))
    }

    @Test("Creates valid .app bundle with Info.plist, executable launch script, and CoreGraphics .icns icon")
    func createInspectUpdateAndRemoveShortcut() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-shortcut-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let samplePNG = try makeSamplePNG(width: 128, height: 128, red: 0.12, green: 0.56, blue: 0.94)
        let spec = AppShortcutSpec(
            packageName: "com.eg.android.AlipayGphone",
            activityClass: ".AlipayLogin",
            appLabel: "支付宝 / Alipay",
            deviceKey: "dev-mi15",
            deviceName: "Xiaomi 15",
            iconPNGData: samplePNG
        )

        let created = try AppShortcutBuilder.createShortcut(
            spec: spec,
            in: tempDir,
            overwrite: true,
            refreshSpotlight: false
        )

        // 1. Verify bundle path sanitized `/` out of app label
        #expect(created.bundleURL.lastPathComponent == "支付宝 - Alipay.app")
        #expect(created.bundleIdentifier.hasPrefix(AppShortcutBuilder.bundleIdentifierPrefix))
        #expect(FileManager.default.fileExists(atPath: created.bundleURL.path))

        // 2. Verify Info.plist fields
        let plistURL = created.bundleURL.appendingPathComponent("Contents/Info.plist")
        let plistData = try Data(contentsOf: plistURL)
        let plist = try #require(
            PropertyListSerialization.propertyList(from: plistData, format: nil) as? [String: Any]
        )
        #expect(plist["CFBundleIdentifier"] as? String == created.bundleIdentifier)
        #expect(plist["CFBundleDisplayName"] as? String == "支付宝 / Alipay")
        #expect(plist["CFBundleIconFile"] as? String == "AppIcon")
        #expect(plist["CFBundleExecutable"] as? String == "launch")
        #expect(plist["LSUIElement"] as? Bool == true)
        #expect(plist["OpalDeskPackageName"] as? String == "com.eg.android.AlipayGphone")
        let storedLink = try #require(plist["OpalDeskDeepLink"] as? String)
        let parsedRoute = try OpalDeskDeepLink.parse(storedLink)
        if case let .popout(req) = parsedRoute {
            #expect(req.packageName == "com.eg.android.AlipayGphone")
            #expect(req.activityClass == ".AlipayLogin")
            #expect(req.deviceKey == "dev-mi15")
        }

        // 3. Verify launch script is executable (0o755)
        let launchURL = created.bundleURL.appendingPathComponent("Contents/MacOS/launch")
        let attrs = try FileManager.default.attributesOfItem(atPath: launchURL.path)
        let perms = (attrs[.posixPermissions] as? NSNumber)?.intValue ?? 0
        #expect((perms & 0o111) == 0o111)
        let scriptContent = try String(contentsOf: launchURL, encoding: .utf8)
        #expect(scriptContent.contains("/usr/bin/open -u '"))

        // 4. Verify .icns header and readability via ImageIO
        let icnsURL = created.bundleURL.appendingPathComponent("Contents/Resources/AppIcon.icns")
        let icnsData = try Data(contentsOf: icnsURL)
        #expect(icnsData.prefix(4) == Data("icns".utf8))
        let imgSource = try #require(CGImageSourceCreateWithData(icnsData as CFData, nil))
        #expect(CGImageSourceGetCount(imgSource) >= 1)
        let decodedIcon = try #require(CGImageSourceCreateImageAtIndex(imgSource, 0, nil))
        #expect(decodedIcon.width >= 128)

        // 5. Place an unrelated `.app` bundle in the same directory; listing must ignore it and deletion must refuse it!
        let foreignApp = tempDir.appendingPathComponent("Unrelated.app", isDirectory: true)
        try FileManager.default.createDirectory(
            at: foreignApp.appendingPathComponent("Contents", isDirectory: true),
            withIntermediateDirectories: true
        )
        let foreignPlist: [String: Any] = ["CFBundleIdentifier": "com.other.vendor.app", "CFBundleName": "Unrelated"]
        let foreignPlistData = try PropertyListSerialization.data(fromPropertyList: foreignPlist, format: .xml, options: 0)
        try foreignPlistData.write(to: foreignApp.appendingPathComponent("Contents/Info.plist"))

        let listed = try AppShortcutBuilder.listShortcuts(in: tempDir)
        #expect(listed.count == 1)
        #expect(listed[0].packageName == "com.eg.android.AlipayGphone")

        #expect(throws: AppShortcutBuilderError.bundleNotManagedByOpalDesk(foreignApp.path)) {
            try AppShortcutBuilder.removeShortcut(at: foreignApp)
        }

        // 6. Update icon with new PNG bytes
        let updatedPNG = try makeSamplePNG(width: 64, height: 64, red: 0.95, green: 0.25, blue: 0.20)
        try AppShortcutBuilder.updateIcon(for: created.bundleURL, iconPNGData: updatedPNG, refreshSpotlight: false)
        let updatedIcns = try Data(contentsOf: icnsURL)
        #expect(updatedIcns != icnsData)
        #expect(updatedIcns.prefix(4) == Data("icns".utf8))

        // 7. Remove OpalDesk shortcut cleanly; foreign app stays untouched
        try AppShortcutBuilder.removeShortcut(at: created.bundleURL)
        #expect(!FileManager.default.fileExists(atPath: created.bundleURL.path))
        #expect(FileManager.default.fileExists(atPath: foreignApp.path))
        #expect(try AppShortcutBuilder.listShortcuts(in: tempDir).isEmpty)
    }

    @Test("Supports fallback monogram icon generation and batch create / removeAll")
    func fallbackIconAndBatchLifecycle() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-batch-shortcut-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let specs = [
            AppShortcutSpec(packageName: "com.app.alpha", appLabel: "Alpha", iconPNGData: nil),
            AppShortcutSpec(packageName: "com.app.beta", appLabel: "Beta", iconPNGData: nil),
        ]

        let created = try AppShortcutBuilder.createShortcuts(specs: specs, in: tempDir, refreshSpotlight: false)
        #expect(created.count == 2)

        for item in created {
            let icns = try #require(item.iconData)
            #expect(icns.prefix(4) == Data("icns".utf8))
            let src = try #require(CGImageSourceCreateWithData(icns as CFData, nil))
            #expect(CGImageSourceGetCount(src) >= 1)
        }

        let removedCount = try AppShortcutBuilder.removeAllShortcuts(in: tempDir)
        #expect(removedCount == 2)
        #expect(try AppShortcutBuilder.listShortcuts(in: tempDir).isEmpty)
    }

    // MARK: - Helper

    private func makeSamplePNG(width: Int, height: Int, red: CGFloat, green: CGFloat, blue: CGFloat) throws -> Data {
        let cs = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        let ctx = try #require(
            CGContext(
                data: nil,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: 0,
                space: cs,
                bitmapInfo: bitmapInfo
            )
        )
        ctx.setFillColor(CGColor(red: red, green: green, blue: blue, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let img = try #require(ctx.makeImage())
        let out = NSMutableData()
        let dest = try #require(CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(dest, img, nil)
        #expect(CGImageDestinationFinalize(dest))
        return out as Data
    }
}
