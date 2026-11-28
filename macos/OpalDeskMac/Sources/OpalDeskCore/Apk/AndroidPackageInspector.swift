import Foundation

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
