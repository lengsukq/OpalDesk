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
        case .uploading, .extracting, .installing: return true
        case .idle, .succeeded, .failed: return false
        }
    }

    public var progressFraction: Double? {
        switch self {
        case let .uploading(progress, _),
             let .extracting(progress, _),
             let .installing(progress, _):
            return min(1.0, max(0.0, progress))
        case .succeeded: return 1.0
        case .idle, .failed: return nil
        }
    }

    public var stageTitle: String {
        switch self {
        case .idle: return OpalL10n.text("apk.stage.ready")
        case .uploading: return OpalL10n.text("apk.stage.uploading")
        case .extracting: return OpalL10n.text("apk.stage.extracting")
        case .installing: return OpalL10n.text("apk.stage.installing")
        case .succeeded: return OpalL10n.text("apk.stage.succeeded")
        case .failed: return OpalL10n.text("apk.stage.failed")
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

/// Standalone SwiftUI Liquid Glass preview & installation sheet for `.apk` / `.apks` / `.apkm` / `.xapk` packages.
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
        VStack(alignment: .leading, spacing: OpalSpacing.lg) {
            if isLoading {
                OpalNoticeBanner(
                    tone: .loading,
                    title: OpalL10n.text("apk.loading.title"),
                    message: packageURL.lastPathComponent
                )
            } else if let parseError {
                OpalNoticeBanner(
                    tone: .error,
                    title: OpalL10n.text("apk.parse_error.title"),
                    message: parseError
                )
            } else if let info = packageInfo {
                ScrollView {
                    VStack(alignment: .leading, spacing: OpalSpacing.md) {
                        headerCard(info)
                        metadataGrid(info)

                        if !info.warnings.isEmpty {
                            OpalNoticeBanner(
                                tone: .warning,
                                message: info.warnings.joined(separator: "\n")
                            )
                        }

                        if !info.splits.isEmpty || !info.obbFiles.isEmpty {
                            splitsDisclosure(info)
                        }

                        permissionsDisclosure(info)
                        installOptionsCard
                    }
                    .padding(.vertical, 2)
                }
                .frame(maxHeight: 400)
            }

            if installState != .idle {
                installProgressBanner
            }

            footerBar
        }
        .padding(OpalSpacing.xxl)
        .opalModalSheetChrome(width: 520)
        .task(id: packageURL) {
            await loadPackageInfo()
        }
    }

    // MARK: - Subviews

    private func headerCard(_ info: AndroidPackageInfo) -> some View {
        HStack(alignment: .center, spacing: OpalSpacing.md) {
            OpalAppMonogram(
                label: info.appName,
                packageKey: info.packageName.isEmpty ? info.appName : info.packageName,
                iconData: info.iconData,
                fallbackSymbol: "shippingbox.fill",
                size: OpalControlSize.appIconLarge,
                cornerRadius: 14
            )

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: OpalSpacing.sm) {
                    Text(info.appName)
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)

                    StatusBadgeView(
                        text: info.format.displayBadge,
                        color: info.format.isSplitContainer ? .purple : .accentColor
                    )

                    if let targetDeviceName, !targetDeviceName.isEmpty {
                        StatusBadgeView(text: targetDeviceName, color: .secondary, backgroundOpacity: 0.12)
                    }
                }

                Text(info.packageName.isEmpty ? OpalL10n.text("apk.unknown_pkg") : info.packageName)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .lineLimit(1)

                HStack(spacing: OpalSpacing.sm) {
                    Text(versionSummary(info))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("·").foregroundStyle(.tertiary)
                    Text(formattedBytes(info.fileSizeBytes))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func metadataGrid(_ info: AndroidPackageInfo) -> some View {
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: OpalSpacing.sm), GridItem(.flexible(), spacing: OpalSpacing.sm)],
            spacing: OpalSpacing.sm
        ) {
            metadataTile(
                icon: "cpu",
                title: OpalL10n.text("apk.meta.abi"),
                value: info.supportedAbis.isEmpty ? OpalL10n.text("apk.meta.abi_universal") : info.supportedAbis.joined(separator: ", ")
            )
            metadataTile(
                icon: "slider.horizontal.3",
                title: OpalL10n.text("apk.meta.sdk"),
                value: sdkSummary(info)
            )
            metadataTile(
                icon: info.signature.isSigned ? "checkmark.seal.fill" : "exclamationmark.shield.fill",
                title: OpalL10n.text("apk.meta.signature"),
                value: info.signature.summaryText,
                tint: info.signature.isSigned ? .green : .orange
            )
            metadataTile(
                icon: "square.stack.3d.up.fill",
                title: OpalL10n.text("apk.meta.structure"),
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
        HStack(alignment: .top, spacing: OpalSpacing.sm) {
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
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 10)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    private func splitsDisclosure(_ info: AndroidPackageInfo) -> some View {
        DisclosureGroup(isExpanded: $showSplits) {
            VStack(alignment: .leading, spacing: OpalSpacing.xs) {
                ForEach(info.splits) { split in
                    HStack(spacing: OpalSpacing.sm) {
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
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                ForEach(info.obbFiles) { obb in
                    HStack(spacing: OpalSpacing.sm) {
                        StatusBadgeView(text: "OBB", color: .indigo)
                        Text((obb.entryPath as NSString).lastPathComponent)
                            .font(.system(.caption, design: .monospaced))
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Text(formattedBytes(obb.sizeBytes))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.top, OpalSpacing.sm)
        } label: {
            HStack(spacing: OpalSpacing.sm) {
                Label(OpalL10n.text("apk.splits.title"), systemImage: "shippingbox")
                    .font(.callout.weight(.medium))
                Spacer()
                StatusBadgeView(
                    text: "\(info.splits.count) APK" + (info.obbFiles.isEmpty ? "" : " + \(info.obbFiles.count) OBB"),
                    color: .purple
                )
            }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 10)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    private func permissionsDisclosure(_ info: AndroidPackageInfo) -> some View {
        DisclosureGroup(isExpanded: $showPermissions) {
            if info.permissionGroups.isEmpty {
                Text(OpalL10n.text("apk.perms.none"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, OpalSpacing.xs)
            } else {
                VStack(alignment: .leading, spacing: OpalSpacing.sm) {
                    ForEach(info.permissionGroups) { group in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: OpalSpacing.xs) {
                                Image(systemName: group.category.sfSymbol)
                                    .font(.caption)
                                    .foregroundStyle(group.isSensitive ? .orange : .secondary)
                                Text(group.category.displayName)
                                    .font(.caption.weight(.semibold))
                                if group.isSensitive {
                                    StatusBadgeView(text: OpalL10n.text("apk.perms.sensitive"), color: .orange, horizontalPadding: 6, verticalPadding: 1)
                                }
                            }
                            ForEach(group.permissions) { perm in
                                HStack(alignment: .firstTextBaseline, spacing: OpalSpacing.xs) {
                                    Text("• \(perm.displayName)")
                                        .font(.caption)
                                    Spacer()
                                    Text(perm.shortName)
                                        .font(.system(size: 10, design: .monospaced))
                                        .foregroundStyle(.tertiary)
                                        .lineLimit(1)
                                }
                                .padding(.leading, OpalSpacing.lg)
                            }
                        }
                    }
                }
                .padding(.top, OpalSpacing.sm)
            }
        } label: {
            HStack(spacing: OpalSpacing.sm) {
                Label(OpalL10n.format("apk.perms.title_fmt", info.permissions.count), systemImage: "hand.raised.fill")
                    .font(.callout.weight(.medium))
                Spacer()
                if info.sensitivePermissionCount > 0 {
                    StatusBadgeView(text: OpalL10n.format("apk.perms.sensitive_count_fmt", info.sensitivePermissionCount), color: .orange)
                } else {
                    StatusBadgeView(text: OpalL10n.text("apk.perms.no_sensitive"), color: .green)
                }
            }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 10)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    private var installOptionsCard: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            Toggle(isOn: $replaceExisting) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(OpalL10n.text("apk.opt.replace_title"))
                        .font(.callout)
                    Text(OpalL10n.text("apk.opt.replace_sub"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(installState.isInProgress)

            Divider()

            Toggle(isOn: $allowDowngrade) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(OpalL10n.text("apk.opt.downgrade_title"))
                        .font(.callout)
                    Text(OpalL10n.text("apk.opt.downgrade_sub"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(installState.isInProgress)
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    @ViewBuilder
    private var installProgressBanner: some View {
        switch installState {
        case .idle:
            EmptyView()

        case .uploading, .extracting, .installing:
            VStack(alignment: .leading, spacing: OpalSpacing.sm) {
                HStack {
                    ProgressView().controlSize(.small)
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
            .padding(OpalSpacing.md)
            .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: .accentColor)

        case let .succeeded(message):
            OpalNoticeBanner(
                tone: .success,
                title: OpalL10n.text("apk.succeeded.title"),
                message: (message?.isEmpty == false) ? message! : OpalL10n.text("apk.succeeded.default")
            )

        case let .failed(message):
            OpalNoticeBanner(tone: .error, title: OpalL10n.text("apk.stage.failed"), message: message)
        }
    }

    private var footerBar: some View {
        HStack {
            Button(isSucceeded ? OpalL10n.text("common.done") : OpalL10n.text("common.cancel"), role: .cancel, action: onCancel)
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))
                .keyboardShortcut(.cancelAction)

            Spacer()

            if let info = packageInfo {
                Button(primaryActionTitle) {
                    onInstall(info, replaceExisting, allowDowngrade)
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
                .disabled(installState.isInProgress)
                .keyboardShortcut(.defaultAction)
            }
        }
    }

    // MARK: - Formatting & Loading

    private var isSucceeded: Bool {
        if case .succeeded = installState { return true }
        return false
    }

    private var primaryActionTitle: String {
        switch installState {
        case .uploading, .extracting, .installing: return OpalL10n.text("apk.btn.installing")
        case .failed: return OpalL10n.text("apk.btn.reinstall")
        case .succeeded: return OpalL10n.text("apk.btn.install_again")
        case .idle: return OpalL10n.text("apk.btn.install")
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
        let name = info.versionName ?? OpalL10n.text("apk.version.unknown")
        if let code = info.versionCode {
            return "v\(name) (\(code))"
        }
        return "v\(name)"
    }

    private func sdkSummary(_ info: AndroidPackageInfo) -> String {
        let minText = info.minSdkVersion.map { "API \($0)" } ?? "—"
        let targetText = info.targetSdkVersion.map { "API \($0)" } ?? "—"
        return OpalL10n.format("apk.sdk.summary_fmt", minText, targetText)
    }

    private func structureSummary(_ info: AndroidPackageInfo) -> String {
        if info.splits.isEmpty { return OpalL10n.text("apk.struct.single") }
        if info.obbFiles.isEmpty { return OpalL10n.format("apk.struct.splits_fmt", info.splits.count) }
        return OpalL10n.format("apk.struct.splits_obb_fmt", info.splits.count, info.obbFiles.count)
    }

    private func formattedBytes(_ bytes: UInt64) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(min(bytes, UInt64(Int64.max))), countStyle: .file)
    }
}
