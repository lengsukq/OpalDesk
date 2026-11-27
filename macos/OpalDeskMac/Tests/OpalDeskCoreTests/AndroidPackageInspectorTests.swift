import Foundation
import Testing
import zlib
@testable import OpalDeskCore

@Suite("AXML Parser & Android Package Inspector")
struct AndroidPackageInspectorTests {

    // MARK: - AXML Binary Parser Tests

    @Test("Parses UTF-8 AXML AndroidManifest with versionCodeMajor, SDK levels, label, and permissions")
    func parseUtf8AxmlManifest() throws {
        let axml = SyntheticAxmlBuilder.buildManifest(
            utf8: true,
            packageName: "io.github.lengsukq.demo",
            versionName: "2.4.1",
            versionCode: 42,
            versionCodeMajor: 1,
            minSdk: 30,
            targetSdk: 35,
            appLabel: "示例应用",
            iconPath: "res/mipmap-xxxhdpi/ic_launcher.png",
            permissions: [
                "android.permission.CAMERA",
                "android.permission.INTERNET",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.CAMERA", // duplicate should be deduplicated
            ]
        )

        let manifest = try AxmlParser.parseManifest(from: axml)
        #expect(manifest.packageName == "io.github.lengsukq.demo")
        #expect(manifest.versionName == "2.4.1")
        #expect(manifest.versionCode == ((Int64(1) << 32) | 42))
        #expect(manifest.minSdkVersion == 30)
        #expect(manifest.targetSdkVersion == 35)
        #expect(manifest.applicationLabel == "示例应用")
        #expect(manifest.applicationIconPath == "res/mipmap-xxxhdpi/ic_launcher.png")
        #expect(manifest.permissions == [
            "android.permission.CAMERA",
            "android.permission.INTERNET",
            "android.permission.SYSTEM_ALERT_WINDOW",
        ])
    }

    @Test("Parses UTF-16LE AXML string pool and resource-ID mapped attributes")
    func parseUtf16AxmlManifest() throws {
        let axml = SyntheticAxmlBuilder.buildManifest(
            utf8: false,
            packageName: "com.example.utf16app",
            versionName: "1.0.0-beta",
            versionCode: 100,
            versionCodeMajor: nil,
            minSdk: 26,
            targetSdk: 34,
            appLabel: "中文标题 App",
            iconPath: nil,
            permissions: [
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.POST_NOTIFICATIONS",
            ]
        )

        let manifest = try AxmlParser.parseManifest(from: axml)
        #expect(manifest.packageName == "com.example.utf16app")
        #expect(manifest.versionName == "1.0.0-beta")
        #expect(manifest.versionCode == 100)
        #expect(manifest.minSdkVersion == 26)
        #expect(manifest.targetSdkVersion == 34)
        #expect(manifest.applicationLabel == "中文标题 App")
        #expect(manifest.permissions.count == 2)
    }

    @Test("Rejects invalid or truncated AXML buffers safely")
    func rejectMalformedAxml() {
        #expect(throws: AxmlParserError.emptyData) {
            _ = try AxmlParser.parseManifest(from: Data())
        }
        #expect(throws: AxmlParserError.invalidHeader(magic: 0x4B50)) {
            _ = try AxmlParser.parseManifest(from: Data([0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00]))
        }
        // Valid XML header (0x0003) but truncated chunk inside
        let truncated = Data([0x03, 0x00, 0x08, 0x00, 0x40, 0x00, 0x00, 0x00, 0x01, 0x00, 0x1C, 0x00, 0x80, 0x00, 0x00, 0x00])
        #expect(throws: AxmlParserError.truncatedChunk(offset: 8)) {
            _ = try AxmlParser.parseManifest(from: truncated)
        }
    }

    // MARK: - Single APK Inspection Tests

    @Test("Inspects realistic DEFLATED .apk on disk with ABIs, v1+v2/v3 signatures, icon, and permission groups")
    func inspectSingleApkOnDisk() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-apk-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let axml = SyntheticAxmlBuilder.buildManifest(
            utf8: true,
            packageName: "com.opaldesk.sample",
            versionName: "3.1.4",
            versionCode: 30104,
            versionCodeMajor: nil,
            minSdk: 30,
            targetSdk: 36,
            appLabel: "OpalSample",
            iconPath: nil, // force fallback icon scanner to pick highest DPI mipmap PNG
            permissions: [
                "android.permission.CAMERA",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.READ_CONTACTS",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.INTERNET",
            ]
        )

        let fakePng = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D])
        let fakeLowResPng = Data([0x89, 0x50, 0x4E, 0x47, 0x01, 0x02])

        let apkBytes = SyntheticZipBuilder.build(
            entries: [
                .init(name: "AndroidManifest.xml", data: axml, compress: true),
                .init(name: "lib/arm64-v8a/libengine.so", data: Data(repeating: 0xAA, count: 64), compress: true),
                .init(name: "lib/armeabi-v7a/libengine.so", data: Data(repeating: 0xBB, count: 64), compress: false),
                .init(name: "META-INF/CERT.RSA", data: Data([0x30, 0x82, 0x01, 0x00]), compress: false),
                .init(name: "res/mipmap-mdpi/ic_launcher.png", data: fakeLowResPng, compress: false),
                .init(name: "res/mipmap-xxxhdpi/ic_launcher.png", data: fakePng, compress: true),
            ],
            includeApkSigningBlock: true
        )

        let apkURL = tempDir.appendingPathComponent("sample.apk")
        try apkBytes.write(to: apkURL)

        let info = try AndroidPackageInspector.inspect(url: apkURL)
        #expect(info.format == .apk)
        #expect(info.fileName == "sample.apk")
        #expect(info.fileSizeBytes == UInt64(apkBytes.count))
        #expect(info.packageName == "com.opaldesk.sample")
        #expect(info.appName == "OpalSample")
        #expect(info.versionName == "3.1.4")
        #expect(info.versionCode == 30104)
        #expect(info.minSdkVersion == 30)
        #expect(info.targetSdkVersion == 36)
        #expect(info.supportedAbis == ["arm64-v8a", "armeabi-v7a"])
        #expect(info.signature.hasV1JarSignature == true)
        #expect(info.signature.hasApkSigningBlock == true)
        #expect(info.signature.isSigned == true)
        #expect(info.signature.summaryText == "v1 + v2/v3 签名")
        #expect(info.iconPath == "res/mipmap-xxxhdpi/ic_launcher.png")
        #expect(info.iconData == fakePng)
        #expect(info.sensitivePermissionCount == 5)
        #expect(info.permissionGroups.contains(where: { $0.category == .camera }))
        #expect(info.permissionGroups.contains(where: { $0.category == .overlayAndSystem }))
        #expect(info.permissionGroups.contains(where: { $0.category == .notifications }))
        #expect(info.warnings.isEmpty)
    }

    // MARK: - XAPK / APKS / APKM Container Tests

    @Test("Inspects .xapk bundle with manifest.json, STORED base APK, ABI/DPI/language splits, OBB, and outer icon")
    func inspectXapkBundle() throws {
        let baseAxml = SyntheticAxmlBuilder.buildManifest(
            utf8: true,
            packageName: "com.game.legend",
            versionName: "5.2.0",
            versionCode: 520,
            versionCodeMajor: nil,
            minSdk: 29,
            targetSdk: 35,
            appLabel: "LegendBase",
            iconPath: nil,
            permissions: ["android.permission.INTERNET", "android.permission.RECORD_AUDIO"]
        )
        let baseApk = SyntheticZipBuilder.build(
            entries: [
                .init(name: "AndroidManifest.xml", data: baseAxml, compress: true),
                .init(name: "META-INF/CERT.EC", data: Data([0x01, 0x02]), compress: false),
            ],
            includeApkSigningBlock: false
        )

        let manifestJson = """
        {
          "xapk_version": 2,
          "package_name": "com.game.legend",
          "name": "传说之旅",
          "version_code": "520",
          "version_name": "5.2.0",
          "min_sdk_version": "29",
          "target_sdk_version": "35",
          "permissions": ["android.permission.VIBRATE"],
          "icon": "icon.png",
          "split_apks": [
            {"file": "com.game.legend.apk", "id": "base"},
            {"file": "config.arm64_v8a.apk", "id": "config.arm64_v8a"},
            {"file": "config.xxhdpi.apk", "id": "config.xxhdpi"},
            {"file": "config.zh.apk", "id": "config.zh"}
          ],
          "expansions": [
            {"file": "Android/obb/com.game.legend/main.520.com.game.legend.obb", "install_path": "Android/obb/com.game.legend/main.520.com.game.legend.obb"}
          ]
        }
        """

        let xapkIcon = Data([0x89, 0x50, 0x4E, 0x47, 0x99, 0x88])
        let xapkData = SyntheticZipBuilder.build(
            entries: [
                .init(name: "manifest.json", data: Data(manifestJson.utf8), compress: true),
                .init(name: "icon.png", data: xapkIcon, compress: false),
                .init(name: "com.game.legend.apk", data: baseApk, compress: false), // STORED zero-copy inner APK
                .init(name: "config.arm64_v8a.apk", data: Data(repeating: 0x01, count: 128), compress: false),
                .init(name: "config.xxhdpi.apk", data: Data(repeating: 0x02, count: 64), compress: false),
                .init(name: "config.zh.apk", data: Data(repeating: 0x03, count: 32), compress: false),
                .init(name: "Android/obb/com.game.legend/main.520.com.game.legend.obb", data: Data(repeating: 0x04, count: 512), compress: false),
            ],
            includeApkSigningBlock: false
        )

        let info = try AndroidPackageInspector.inspectData(xapkData, fileName: "legend.xapk")
        #expect(info.format == .xapk)
        #expect(info.packageName == "com.game.legend")
        #expect(info.appName == "传说之旅")
        #expect(info.versionName == "5.2.0")
        #expect(info.versionCode == 520)
        #expect(info.supportedAbis == ["arm64-v8a"])
        #expect(info.splits.count == 4)
        #expect(info.splits.first?.isBase == true)
        #expect(info.splits.contains(where: { $0.kind == .abi("arm64-v8a") }))
        #expect(info.splits.contains(where: { $0.kind == .density("xxhdpi") }))
        #expect(info.splits.contains(where: { $0.kind == .language("zh") }))
        #expect(info.obbFiles.count == 1)
        #expect(info.obbFiles[0].sizeBytes == 512)
        #expect(info.obbFiles[0].installPath == "Android/obb/com.game.legend/main.520.com.game.legend.obb")
        #expect(info.iconPath == "icon.png")
        #expect(info.iconData == xapkIcon)
        #expect(info.signature.hasV1JarSignature == true)
        #expect(info.permissions.contains("android.permission.RECORD_AUDIO"))
        #expect(info.permissions.contains("android.permission.VIBRATE"))
    }

    @Test("Inspects .apkm and .apks containers with DEFLATED nested base APK")
    func inspectApkmAndApksContainers() throws {
        let baseAxml = SyntheticAxmlBuilder.buildManifest(
            utf8: true,
            packageName: "org.example.bundle",
            versionName: "8.0",
            versionCode: 800,
            versionCodeMajor: nil,
            minSdk: 31,
            targetSdk: 35,
            appLabel: "BundleApp",
            iconPath: nil,
            permissions: ["android.permission.CAMERA"]
        )
        let innerBaseApk = SyntheticZipBuilder.build(
            entries: [
                .init(name: "AndroidManifest.xml", data: baseAxml, compress: true),
            ],
            includeApkSigningBlock: true
        )

        let infoJson = """
        {
          "pname": "org.example.bundle",
          "app_name": "BundleApp Pro",
          "release_version": "8.0",
          "versioncode": 800,
          "min_api": 31,
          "arches": ["arm64-v8a", "x86_64"]
        }
        """

        let apkmData = SyntheticZipBuilder.build(
            entries: [
                .init(name: "info.json", data: Data(infoJson.utf8), compress: true),
                .init(name: "base.apk", data: innerBaseApk, compress: true), // DEFLATED inner APK path
                .init(name: "split_config.arm64_v8a.apk", data: Data(repeating: 0x11, count: 64), compress: true),
            ],
            includeApkSigningBlock: false
        )

        let apkmInfo = try AndroidPackageInspector.inspectData(apkmData, fileName: "bundle.apkm")
        #expect(apkmInfo.format == .apkm)
        #expect(apkmInfo.packageName == "org.example.bundle")
        #expect(apkmInfo.appName == "BundleApp Pro")
        #expect(apkmInfo.supportedAbis == ["arm64-v8a", "x86_64"])
        #expect(apkmInfo.splits.count == 2)
        #expect(apkmInfo.signature.hasApkSigningBlock == true)

        let apksInfo = try AndroidPackageInspector.inspectData(apkmData, fileName: "bundle.apks")
        #expect(apksInfo.format == .apks)
        #expect(apksInfo.packageName == "org.example.bundle")
    }

    // MARK: - Anti-Zip-Bomb Security Guard

    @Test("Blocks zip-bomb DEFLATED entry whose inflated output exceeds configured limit")
    func zipBombLimitEnforced() {
        // 128 KiB of zeroes compresses down to ~150 bytes with DEFLATE.
        let bombPayload = Data(repeating: 0x00, count: 128 * 1024)
        let bombApk = SyntheticZipBuilder.build(
            entries: [
                .init(name: "AndroidManifest.xml", data: bombPayload, compress: true, spoofedUncompressedSize: 64),
            ],
            includeApkSigningBlock: false
        )

        let strictLimits = AndroidPackageInspectionLimits(maxManifestBytes: 4096)
        #expect(throws: AndroidPackageError.entryTooLarge(entry: "AndroidManifest.xml", limit: 4096)) {
            _ = try AndroidPackageInspector.inspectData(bombApk, fileName: "bomb.apk", limits: strictLimits)
        }
    }
}

// MARK: - Synthetic AXML & ZIP Test Fixtures

private enum SyntheticAxmlBuilder {
    static func buildManifest(
        utf8: Bool,
        packageName: String,
        versionName: String?,
        versionCode: Int?,
        versionCodeMajor: Int?,
        minSdk: Int?,
        targetSdk: Int?,
        appLabel: String?,
        iconPath: String?,
        permissions: [String]
    ) -> Data {
        var pool: [String] = []
        var resMap: [UInt32] = []

        func internAttr(_ name: String, resId: UInt32) -> Int32 {
            let idx = pool.count
            pool.append(name)
            resMap.append(resId)
            return Int32(idx)
        }

        // First put attributes that need Android resource IDs so stringPool indices align with resourceMap.
        let idxLabel = internAttr("label", resId: AxmlParser.attrResLabel)
        let idxIcon = internAttr("icon", resId: AxmlParser.attrResIcon)
        let idxName = internAttr("name", resId: AxmlParser.attrResName)
        let idxMinSdk = internAttr("minSdkVersion", resId: AxmlParser.attrResMinSdkVersion)
        let idxVersionCode = internAttr("versionCode", resId: AxmlParser.attrResVersionCode)
        let idxVersionName = internAttr("versionName", resId: AxmlParser.attrResVersionName)
        let idxTargetSdk = internAttr("targetSdkVersion", resId: AxmlParser.attrResTargetSdkVersion)
        let idxVersionCodeMajor = internAttr("versionCodeMajor", resId: AxmlParser.attrResVersionCodeMajor)

        func intern(_ s: String) -> Int32 {
            if let existing = pool.firstIndex(of: s) {
                return Int32(existing)
            }
            pool.append(s)
            return Int32(pool.count - 1)
        }

        let idxManifest = intern("manifest")
        let idxPackageAttr = intern("package")
        let idxPackageVal = intern(packageName)
        let idxUsesSdk = intern("uses-sdk")
        let idxUsesPerm = intern("uses-permission")
        let idxApplication = intern("application")

        let idxVersionNameVal = versionName.map(intern)
        let idxAppLabelVal = appLabel.map(intern)
        let idxIconVal = iconPath.map(intern)
        let permIndices = permissions.map(intern)

        var body = Data()
        body.append(buildStringPoolChunk(strings: pool, utf8: utf8))
        body.append(buildResourceMapChunk(ids: resMap))

        // <manifest package="..." versionName="..." versionCode="..." [versionCodeMajor="..."]>
        var manifestAttrs: [RawAttr] = [
            RawAttr(nameIdx: idxPackageAttr, rawStrIdx: idxPackageVal, type: AxmlParser.typeString, data: UInt32(bitPattern: idxPackageVal)),
        ]
        if let idxVersionNameVal {
            manifestAttrs.append(RawAttr(nameIdx: idxVersionName, rawStrIdx: idxVersionNameVal, type: AxmlParser.typeString, data: UInt32(bitPattern: idxVersionNameVal)))
        }
        if let versionCode {
            manifestAttrs.append(RawAttr(nameIdx: idxVersionCode, rawStrIdx: -1, type: AxmlParser.typeIntDec, data: UInt32(bitPattern: Int32(versionCode))))
        }
        if let versionCodeMajor {
            manifestAttrs.append(RawAttr(nameIdx: idxVersionCodeMajor, rawStrIdx: -1, type: AxmlParser.typeIntDec, data: UInt32(bitPattern: Int32(versionCodeMajor))))
        }
        body.append(buildStartElementChunk(nameIdx: idxManifest, attrs: manifestAttrs))

        // <uses-sdk minSdkVersion="..." targetSdkVersion="..." />
        var sdkAttrs: [RawAttr] = []
        if let minSdk {
            sdkAttrs.append(RawAttr(nameIdx: idxMinSdk, rawStrIdx: -1, type: AxmlParser.typeIntDec, data: UInt32(minSdk)))
        }
        if let targetSdk {
            sdkAttrs.append(RawAttr(nameIdx: idxTargetSdk, rawStrIdx: -1, type: AxmlParser.typeIntDec, data: UInt32(targetSdk)))
        }
        if !sdkAttrs.isEmpty {
            body.append(buildStartElementChunk(nameIdx: idxUsesSdk, attrs: sdkAttrs))
            body.append(buildEndElementChunk(nameIdx: idxUsesSdk))
        }

        // <uses-permission android:name="..." />
        for pIdx in permIndices {
            let attrs = [RawAttr(nameIdx: idxName, rawStrIdx: pIdx, type: AxmlParser.typeString, data: UInt32(bitPattern: pIdx))]
            body.append(buildStartElementChunk(nameIdx: idxUsesPerm, attrs: attrs))
            body.append(buildEndElementChunk(nameIdx: idxUsesPerm))
        }

        // <application android:label="..." android:icon="..." />
        var appAttrs: [RawAttr] = []
        if let idxAppLabelVal {
            appAttrs.append(RawAttr(nameIdx: idxLabel, rawStrIdx: idxAppLabelVal, type: AxmlParser.typeString, data: UInt32(bitPattern: idxAppLabelVal)))
        }
        if let idxIconVal {
            appAttrs.append(RawAttr(nameIdx: idxIcon, rawStrIdx: idxIconVal, type: AxmlParser.typeString, data: UInt32(bitPattern: idxIconVal)))
        } else {
            appAttrs.append(RawAttr(nameIdx: idxIcon, rawStrIdx: -1, type: AxmlParser.typeReference, data: 0x7F08_0001))
        }
        body.append(buildStartElementChunk(nameIdx: idxApplication, attrs: appAttrs))
        body.append(buildEndElementChunk(nameIdx: idxApplication))
        body.append(buildEndElementChunk(nameIdx: idxManifest))

        // Root RES_XML_TYPE header
        var xml = Data()
        appendU16(AxmlParser.chunkTypeXml, to: &xml)
        appendU16(8, to: &xml)
        appendU32(UInt32(8 + body.count), to: &xml)
        xml.append(body)
        return xml
    }

    private struct RawAttr {
        let nameIdx: Int32
        let rawStrIdx: Int32
        let type: UInt8
        let data: UInt32
    }

    private static func buildStringPoolChunk(strings: [String], utf8: Bool) -> Data {
        var offsets = Data()
        var blob = Data()

        for s in strings {
            appendU32(UInt32(blob.count), to: &offsets)
            if utf8 {
                let utf16Len = s.utf16.count
                let bytes = Data(s.utf8)
                blob.append(UInt8(utf16Len & 0x7F))
                blob.append(UInt8(bytes.count & 0x7F))
                blob.append(bytes)
                blob.append(0x00)
            } else {
                let units = Array(s.utf16)
                appendU16(UInt16(units.count), to: &blob)
                for u in units {
                    appendU16(u, to: &blob)
                }
                appendU16(0, to: &blob)
            }
        }
        while blob.count % 4 != 0 {
            blob.append(0)
        }

        let headerSize: UInt16 = 28
        let stringsStart = UInt32(Int(headerSize) + offsets.count)
        let chunkSize = stringsStart + UInt32(blob.count)
        let flags: UInt32 = utf8 ? (1 << 8) : 0

        var chunk = Data()
        appendU16(AxmlParser.chunkTypeStringPool, to: &chunk)
        appendU16(headerSize, to: &chunk)
        appendU32(chunkSize, to: &chunk)
        appendU32(UInt32(strings.count), to: &chunk)
        appendU32(0, to: &chunk) // styleCount
        appendU32(flags, to: &chunk)
        appendU32(stringsStart, to: &chunk)
        appendU32(0, to: &chunk) // stylesStart
        chunk.append(offsets)
        chunk.append(blob)
        return chunk
    }

    private static func buildResourceMapChunk(ids: [UInt32]) -> Data {
        var chunk = Data()
        appendU16(AxmlParser.chunkTypeResourceMap, to: &chunk)
        appendU16(8, to: &chunk)
        appendU32(UInt32(8 + ids.count * 4), to: &chunk)
        for id in ids {
            appendU32(id, to: &chunk)
        }
        return chunk
    }

    private static func buildStartElementChunk(nameIdx: Int32, attrs: [RawAttr]) -> Data {
        let headerSize: UInt16 = 16
        let extSize = 20
        let attrBytes = attrs.count * 20
        let totalSize = UInt32(Int(headerSize) + extSize + attrBytes)

        var chunk = Data()
        appendU16(AxmlParser.chunkTypeStartElement, to: &chunk)
        appendU16(headerSize, to: &chunk)
        appendU32(totalSize, to: &chunk)
        appendU32(1, to: &chunk) // lineNumber
        appendI32(-1, to: &chunk) // comment

        // ResXMLTree_attrExt
        appendI32(-1, to: &chunk) // ns
        appendI32(nameIdx, to: &chunk)
        appendU16(20, to: &chunk) // attributeStart
        appendU16(20, to: &chunk) // attributeSize
        appendU16(UInt16(attrs.count), to: &chunk)
        appendU16(0, to: &chunk) // idIndex
        appendU16(0, to: &chunk) // classIndex
        appendU16(0, to: &chunk) // styleIndex

        for attr in attrs {
            appendI32(-1, to: &chunk) // ns
            appendI32(attr.nameIdx, to: &chunk)
            appendI32(attr.rawStrIdx, to: &chunk)
            appendU16(8, to: &chunk) // Res_value.size
            chunk.append(0) // res0
            chunk.append(attr.type)
            appendU32(attr.data, to: &chunk)
        }
        return chunk
    }

    private static func buildEndElementChunk(nameIdx: Int32) -> Data {
        var chunk = Data()
        appendU16(AxmlParser.chunkTypeEndElement, to: &chunk)
        appendU16(16, to: &chunk)
        appendU32(24, to: &chunk)
        appendU32(1, to: &chunk)
        appendI32(-1, to: &chunk)
        appendI32(-1, to: &chunk)
        appendI32(nameIdx, to: &chunk)
        return chunk
    }
}

private enum SyntheticZipBuilder {
    struct EntrySpec {
        let name: String
        let data: Data
        let compress: Bool
        var spoofedUncompressedSize: UInt32? = nil
    }

    static func build(entries: [EntrySpec], includeApkSigningBlock: Bool) -> Data {
        var archive = Data()
        var centralDir = Data()

        for entry in entries {
            let localOffset = UInt32(archive.count)
            let nameBytes = Data(entry.name.utf8)
            let crc = computeCrc32(entry.data)
            let method: UInt16 = entry.compress ? 8 : 0
            let payload = entry.compress ? rawDeflate(entry.data) : entry.data
            let declaredUncompressed = entry.spoofedUncompressedSize ?? UInt32(entry.data.count)

            // Local File Header
            appendU32(0x0403_4B50, to: &archive)
            appendU16(20, to: &archive) // version needed
            appendU16(0, to: &archive) // flags
            appendU16(method, to: &archive)
            appendU16(0, to: &archive) // mod time
            appendU16(0, to: &archive) // mod date
            appendU32(crc, to: &archive)
            appendU32(UInt32(payload.count), to: &archive)
            appendU32(declaredUncompressed, to: &archive)
            appendU16(UInt16(nameBytes.count), to: &archive)
            appendU16(0, to: &archive) // extra len
            archive.append(nameBytes)
            archive.append(payload)

            // Central Directory Header
            appendU32(0x0201_4B50, to: &centralDir)
            appendU16(20, to: &centralDir) // version made by
            appendU16(20, to: &centralDir) // version needed
            appendU16(0, to: &centralDir) // flags
            appendU16(method, to: &centralDir)
            appendU16(0, to: &centralDir)
            appendU16(0, to: &centralDir)
            appendU32(crc, to: &centralDir)
            appendU32(UInt32(payload.count), to: &centralDir)
            appendU32(declaredUncompressed, to: &centralDir)
            appendU16(UInt16(nameBytes.count), to: &centralDir)
            appendU16(0, to: &centralDir) // extra len
            appendU16(0, to: &centralDir) // comment len
            appendU16(0, to: &centralDir) // disk start
            appendU16(0, to: &centralDir) // internal attrs
            appendU32(0, to: &centralDir) // external attrs
            appendU32(localOffset, to: &centralDir)
            centralDir.append(nameBytes)
        }

        if includeApkSigningBlock {
            // Minimal valid APK Signing Block immediately before Central Directory:
            // [size_of_block (8B)] [pair payload (16B)] [size_of_block (8B)] ["APK Sig Block 42" (16B)]
            let dummyPair = Data(repeating: 0x01, count: 16)
            let blockSize = UInt64(dummyPair.count + 8 + 16)
            appendU64(blockSize, to: &archive)
            archive.append(dummyPair)
            appendU64(blockSize, to: &archive)
            archive.append(contentsOf: "APK Sig Block 42".utf8)
        }

        let cdOffset = UInt32(archive.count)
        archive.append(centralDir)

        // End of Central Directory (EOCD)
        appendU32(0x0605_4B50, to: &archive)
        appendU16(0, to: &archive)
        appendU16(0, to: &archive)
        appendU16(UInt16(entries.count), to: &archive)
        appendU16(UInt16(entries.count), to: &archive)
        appendU32(UInt32(centralDir.count), to: &archive)
        appendU32(cdOffset, to: &archive)
        appendU16(0, to: &archive) // comment len

        return archive
    }

    private static func computeCrc32(_ data: Data) -> UInt32 {
        data.withUnsafeBytes { raw in
            let base = raw.bindMemory(to: Bytef.self).baseAddress
            return UInt32(crc32(0, base, uInt(data.count)))
        }
    }

    private static func rawDeflate(_ input: Data) -> Data {
        var stream = z_stream()
        let status = deflateInit2_(
            &stream,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            -MAX_WBITS,
            8,
            Z_DEFAULT_STRATEGY,
            ZLIB_VERSION,
            Int32(MemoryLayout<z_stream>.size)
        )
        precondition(status == Z_OK)
        defer { deflateEnd(&stream) }

        var output = Data()
        let outChunkSize = 16_384
        var outChunk = [UInt8](repeating: 0, count: outChunkSize)

        input.withUnsafeBytes { rawIn in
            stream.next_in = UnsafeMutablePointer(mutating: rawIn.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(input.count)

            var res: Int32 = Z_OK
            repeat {
                outChunk.withUnsafeMutableBytes { rawOut in
                    stream.next_out = rawOut.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(outChunkSize)
                    res = deflate(&stream, Z_FINISH)
                }
                let written = outChunkSize - Int(stream.avail_out)
                if written > 0 {
                    output.append(outChunk, count: written)
                }
            } while res == Z_OK
        }
        return output
    }
}

private func appendU16(_ v: UInt16, to data: inout Data) {
    var le = v.littleEndian
    withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
}

private func appendU32(_ v: UInt32, to data: inout Data) {
    var le = v.littleEndian
    withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
}

private func appendI32(_ v: Int32, to data: inout Data) {
    appendU32(UInt32(bitPattern: v), to: &data)
}

private func appendU64(_ v: UInt64, to data: inout Data) {
    var le = v.littleEndian
    withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
}
