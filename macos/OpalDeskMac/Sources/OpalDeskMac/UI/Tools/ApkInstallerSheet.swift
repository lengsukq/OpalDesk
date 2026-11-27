import AppKit
import OpalDeskCore
import SwiftUI

/// Decoupled installation lifecycle state passed into `ApkInstallerSheet`.
public enum ApkInstallState: Sendable, Equatable {
    case idle
    case uploading(progress: Double, message: String? = nil)
    case extracting(progress: Double, message: String? = nil)
    case installing(progress: Double, message: String? = nil)
    case succeeded(message: String? = nil)
    case failed(message: String)

    public var isInProgress: Bool {
        switch self {
        case .uploading, .extracting, .installing:
            return true
        case .idle, .succeeded, .failed:
            return false
        }
    }

    public var progressFraction: Double? {
        switch self {
        case let .uploading(progress, _),
             let .extracting(progress, _),
             let .installing(progress, _):
            return min(1.0, max(0.0, progress))
        case .succeeded:
            return 1.0
        case .idle, .failed:
            return nil
        }
    }

    public var stageTitle: String {
        switch self {
        case .idle:
            return "就绪"
        case .uploading:
            return "正在传输安装包到手机…"
        case .extracting:
            return "正在手机端解包分包资源…"
        case .installing:
            return "正在调用系统安装器安装…"
        case .succeeded:
            return "安装完成"
        case .failed:
            return "安装失败"
        }
    }

    public var detailMessage: String? {
        switch self {
        case let .uploading(_, message),
             let .extracting(_, message),
             let .installing(_, message),
             let .succeeded(message):
            return message
        case let .failed(message):
            return message
        case .idle:
            return nil
        }
    }
}

/// Standalone SwiftUI preview & installation dialog for `.apk` / `.apks` / `.apkm` / `.xapk` packages.
/// Decoupled from `AppModel` so Wave 2 integration only needs to bind `installState` and callbacks.
public struct ApkInstallerSheet: View {
    public let packageURL: URL
    public let preloadedInfo: AndroidPackageInfo?
    public let installState: ApkInstallState
    public let targetDeviceName: String?
    public let onInstall: (AndroidPackageInfo, Bool, Bool) -> Void
    public let onCancel: () -> Void

    @State private var packageInfo: AndroidPackageInfo?
    @State private var parseError: String?
    @State private var isLoading = true
    @State private var replaceExisting = true
    @State private var allowDowngrade = false
    @State private var showPermissions = false
    @State private var showSplits = false

    public init(
        packageURL: URL,
        preloadedInfo: AndroidPackageInfo? = nil,
        installState: ApkInstallState = .idle,
        targetDeviceName: String? = nil,
        onInstall: @escaping (AndroidPackageInfo, Bool, Bool) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.packageURL = packageURL
        self.preloadedInfo = preloadedInfo
        self.installState = installState
        self.targetDeviceName = targetDeviceName
        self.onInstall = onInstall
        self.onCancel = onCancel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if isLoading {
                loadingView
            } else if let parseError {
                parseErrorView(parseError)
            } else if let info = packageInfo {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        headerCard(info)
                        metadataGrid(info)

                        if !info.warnings.isEmpty {
                            warningsBanner(info.warnings)
                        }

                        if !info.splits.isEmpty || !info.obbFiles.isEmpty {
                            splitsDisclosure(info)
                        }

                        permissionsDisclosure(info)
                        installOptionsCard
                    }
                    .padding(.vertical, 2)
                }
                .frame(maxHeight: 380)
            }

            if installState != .idle {
                installProgressBanner
            }

            footerBar
        }
        .padding(22)
        .frame(width: 500)
        .task(id: packageURL) {
            await loadPackageInfo()
        }
    }

    // MARK: - Subviews

    private var loadingView: some View {
        HStack(spacing: 12) {
            ProgressView()
                .controlSize(.regular)
            VStack(alignment: .leading, spacing: 3) {
                Text("正在解析安装包…")
                    .font(.headline)
                Text(packageURL.lastPathComponent)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer()
        }
        .padding(18)
        .opalDeskCardContainer(cornerRadius: 16, borderOpacity: 0.16)
    }

    private func parseErrorView(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.title2)
                .foregroundStyle(.red)
            VStack(alignment: .leading, spacing: 4) {
                Text("无法解析安装包")
                    .font(.headline)
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding(16)
        .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func headerCard(_ info: AndroidPackageInfo) -> some View {
        HStack(alignment: .center, spacing: 14) {
            appIconView(iconData: info.iconData)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(info.appName)
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)

                    StatusBadgeView(
                        text: info.format.displayBadge,
                        color: info.format.isSplitContainer ? .purple : .accentColor
                    )

                    if let targetDeviceName, !targetDeviceName.isEmpty {
                        StatusBadgeView(
                            text: targetDeviceName,
                            color: .secondary,
                            backgroundOpacity: 0.12
                        )
                    }
                }

                Text(info.packageName.isEmpty ? "未知包名" : info.packageName)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .lineLimit(1)

                HStack(spacing: 10) {
                    Text(versionSummary(info))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("·")
                        .foregroundStyle(.tertiary)
                    Text(formattedBytes(info.fileSizeBytes))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(14)
        .opalDeskCardContainer(cornerRadius: 16, borderOpacity: 0.18)
    }

    @ViewBuilder
    private func appIconView(iconData: Data?) -> some View {
        if let iconData, let image = NSImage(data: iconData) {
            Image(nsImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.1), lineWidth: 0.5)
                )
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(Color.accentColor.opacity(0.14))
                Image(systemName: "shippingbox.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(Color.accentColor)
            }
            .frame(width: 56, height: 56)
        }
    }

    private func metadataGrid(_ info: AndroidPackageInfo) -> some View {
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)],
            spacing: 10
        ) {
            metadataTile(
                icon: "cpu",
                title: "原生架构 (ABI)",
                value: info.supportedAbis.isEmpty ? "通用 / 纯 Java" : info.supportedAbis.joined(separator: ", ")
            )

            metadataTile(
                icon: "slider.horizontal.3",
                title: "Android SDK",
                value: sdkSummary(info)
            )

            metadataTile(
                icon: info.signature.isSigned ? "checkmark.seal.fill" : "exclamationmark.shield.fill",
                title: "应用签名",
                value: info.signature.summaryText,
                tint: info.signature.isSigned ? .green : .orange
            )

            metadataTile(
                icon: "square.stack.3d.up.fill",
                title: "安装包结构",
                value: structureSummary(info)
            )
        }
    }

    private func metadataTile(
        icon: String,
        title: String,
        value: String,
        tint: Color = .accentColor
    ) -> some View {
        HStack(alignment: .top, spacing: 9) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 18)
                .padding(.top, 2)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(value)
                    .font(.callout.weight(.medium))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
    }

    private func warningsBanner(_ warnings: [String]) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(warnings, id: \.self) { warning in
                HStack(alignment: .top, spacing: 7) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .padding(.top, 2)
                    Text(warning)
                        .font(.caption)
                        .foregroundStyle(.primary)
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func splitsDisclosure(_ info: AndroidPackageInfo) -> some View {
        DisclosureGroup(isExpanded: $showSplits) {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(info.splits) { split in
                    HStack(spacing: 8) {
                        StatusBadgeView(
                            text: split.kind.displayBadge,
                            color: split.isBase ? .accentColor : .secondary,
                            backgroundOpacity: 0.13
                        )
                        Text(split.fileName)
                            .font(.system(.caption, design: .monospaced))
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Text(formattedBytes(split.uncompressedSize))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                ForEach(info.obbFiles) { obb in
                    HStack(spacing: 8) {
                        StatusBadgeView(text: "OBB", color: .indigo)
                        Text((obb.entryPath as NSString).lastPathComponent)
                            .font(.system(.caption, design: .monospaced))
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Text(formattedBytes(obb.sizeBytes))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.top, 8)
        } label: {
            HStack(spacing: 8) {
                Label("分包与扩展文件", systemImage: "shippingbox")
                    .font(.callout.weight(.medium))
                Spacer()
                StatusBadgeView(
                    text: "\(info.splits.count) APK" + (info.obbFiles.isEmpty ? "" : " + \(info.obbFiles.count) OBB"),
                    color: .purple
                )
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func permissionsDisclosure(_ info: AndroidPackageInfo) -> some View {
        DisclosureGroup(isExpanded: $showPermissions) {
            if info.permissionGroups.isEmpty {
                Text("此安装包未声明特殊系统权限")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 6)
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(info.permissionGroups) { group in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 6) {
                                Image(systemName: group.category.sfSymbol)
                                    .font(.caption)
                                    .foregroundStyle(group.isSensitive ? .orange : .secondary)
                                Text(group.category.displayName)
                                    .font(.caption.weight(.semibold))
                                if group.isSensitive {
                                    StatusBadgeView(text: "敏感", color: .orange, horizontalPadding: 5, verticalPadding: 1)
                                }
                            }
                            ForEach(group.permissions) { perm in
                                HStack(alignment: .firstTextBaseline, spacing: 6) {
                                    Text("• \(perm.displayName)")
                                        .font(.caption)
                                    Spacer()
                                    Text(perm.shortName)
                                        .font(.system(size: 10, design: .monospaced))
                                        .foregroundStyle(.tertiary)
                                        .lineLimit(1)
                                }
                                .padding(.leading, 16)
                            }
                        }
                    }
                }
                .padding(.top, 8)
            }
        } label: {
            HStack(spacing: 8) {
                Label("申请权限（\(info.permissions.count) 项）", systemImage: "hand.raised.fill")
                    .font(.callout.weight(.medium))
                Spacer()
                if info.sensitivePermissionCount > 0 {
                    StatusBadgeView(text: "含 \(info.sensitivePermissionCount) 项敏感权限", color: .orange)
                } else {
                    StatusBadgeView(text: "无敏感权限", color: .green)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var installOptionsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: $replaceExisting) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("覆盖安装已有应用 (-r)")
                        .font(.callout)
                    Text("保留现有应用数据并直接更新")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(installState.isInProgress)

            Toggle(isOn: $allowDowngrade) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("允许降级安装 (-d)")
                        .font(.callout)
                    Text("允许安装版本号低于当前已安装版本的安装包")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(installState.isInProgress)
        }
        .padding(12)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    @ViewBuilder
    private var installProgressBanner: some View {
        switch installState {
        case .idle:
            EmptyView()

        case .uploading, .extracting, .installing:
            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text(installState.stageTitle)
                        .font(.callout.weight(.semibold))
                    Spacer()
                    if let pct = installState.progressFraction {
                        Text("\(Int((pct * 100).rounded()))%")
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }
                if let pct = installState.progressFraction {
                    ProgressView(value: pct, total: 1.0)
                        .progressViewStyle(.linear)
                }
                if let detail = installState.detailMessage, !detail.isEmpty {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(12)
            .background(Color.accentColor.opacity(0.09), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

        case let .succeeded(message):
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.green)
                VStack(alignment: .leading, spacing: 2) {
                    Text("安装成功")
                        .font(.callout.weight(.semibold))
                    if let message, !message.isEmpty {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .padding(12)
            .background(Color.green.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

        case let .failed(message):
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "xmark.octagon.fill")
                    .font(.title3)
                    .foregroundStyle(.red)
                VStack(alignment: .leading, spacing: 2) {
                    Text("安装失败")
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.red)
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
            }
            .padding(12)
            .background(Color.red.opacity(0.09), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }

    private var footerBar: some View {
        HStack {
            Button(installState == .succeeded(message: installState.detailMessage) ? "完成" : "取消", role: .cancel) {
                onCancel()
            }
            .keyboardShortcut(.cancelAction)

            Spacer()

            if let info = packageInfo {
                Button(primaryActionTitle) {
                    onInstall(info, replaceExisting, allowDowngrade)
                }
                .buttonStyle(.borderedProminent)
                .disabled(installState.isInProgress)
                .keyboardShortcut(.defaultAction)
            }
        }
    }

    // MARK: - Formatting & Loading

    private var primaryActionTitle: String {
        switch installState {
        case .uploading, .extracting, .installing:
            return "正在安装…"
        case .failed:
            return "重新安装"
        case .succeeded:
            return "再次安装"
        case .idle:
            return "安装到手机"
        }
    }

    private func loadPackageInfo() async {
        if let preloadedInfo {
            packageInfo = preloadedInfo
            parseError = nil
            isLoading = false
            return
        }

        isLoading = true
        parseError = nil
        let targetURL = packageURL

        let result: Result<AndroidPackageInfo, Error> = await Task.detached(priority: .userInitiated) {
            Result { try AndroidPackageInspector.inspect(url: targetURL) }
        }.value

        switch result {
        case let .success(info):
            packageInfo = info
            parseError = nil
        case let .failure(error):
            packageInfo = nil
            parseError = error.localizedDescription
        }
        isLoading = false
    }

    private func versionSummary(_ info: AndroidPackageInfo) -> String {
        let name = info.versionName ?? "未知版本"
        if let code = info.versionCode {
            return "v\(name) (\(code))"
        }
        return "v\(name)"
    }

    private func sdkSummary(_ info: AndroidPackageInfo) -> String {
        let minText = info.minSdkVersion.map { "API \($0)" } ?? "—"
        let targetText = info.targetSdkVersion.map { "API \($0)" } ?? "—"
        return "最低 \(minText) · 目标 \(targetText)"
    }

    private func structureSummary(_ info: AndroidPackageInfo) -> String {
        if info.splits.isEmpty {
            return "独立单包 APK"
        }
        if info.obbFiles.isEmpty {
            return "\(info.splits.count) 个 Split APK"
        }
        return "\(info.splits.count) 个分包 + \(info.obbFiles.count) 个 OBB"
    }

    private func formattedBytes(_ bytes: UInt64) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(min(bytes, UInt64(Int64.max))), countStyle: .file)
    }
}
