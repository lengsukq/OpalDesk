import AppKit
import OpalDeskCore
import SwiftUI

/// Lightweight value model representing a remote Android app eligible for macOS shortcut creation.
public struct ShortcutAppCandidate: Sendable, Equatable, Hashable, Identifiable {
    public var id: String {
        let dev = deviceKey ?? ""
        return "\(dev)::\(packageName)::\(activityClass ?? "")"
    }

    public let packageName: String
    public let activityClass: String?
    public let appLabel: String
    public let deviceKey: String?
    public let deviceName: String?
    public let iconPNGData: Data?

    public init(
        packageName: String,
        activityClass: String? = nil,
        appLabel: String,
        deviceKey: String? = nil,
        deviceName: String? = nil,
        iconPNGData: Data? = nil
    ) {
        self.packageName = packageName
        self.activityClass = activityClass
        self.appLabel = appLabel
        self.deviceKey = deviceKey
        self.deviceName = deviceName
        self.iconPNGData = iconPNGData
    }

    public func toShortcutSpec() -> AppShortcutSpec {
        AppShortcutSpec(
            packageName: packageName,
            activityClass: activityClass,
            appLabel: appLabel,
            deviceKey: deviceKey,
            deviceName: deviceName,
            iconPNGData: iconPNGData
        )
    }
}

/// Destination preset for macOS `.app` shortcut creation & management.
public enum ShortcutDirectoryPreset: String, CaseIterable, Identifiable, Sendable {
    case spotlightApps
    case desktop
    case custom

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .spotlightApps: return "Spotlight 应用目录"
        case .desktop: return "桌面 (Desktop)"
        case .custom: return "自定义目录"
        }
    }
}

/// Standalone SwiftUI Liquid Glass management sheet for Spotlight / Desktop `.app` shortcuts.
public struct ShortcutManagerSheet: View {
    public let availableApps: [ShortcutAppCandidate]
    public let initialDirectory: URL
    public let refreshSpotlightOnWrite: Bool
    public let onLaunchShortcut: ((CreatedAppShortcut) -> Void)?
    public let onRevealInFinder: ((CreatedAppShortcut) -> Void)?
    public let onShortcutsChanged: (([CreatedAppShortcut]) -> Void)?
    public let onClose: () -> Void

    private enum SheetTab: String, CaseIterable, Identifiable {
        case installed = "已创建"
        case createBatch = "批量创建"
        var id: String { rawValue }
    }

    @State private var selectedTab: SheetTab = .installed
    @State private var directoryPreset: ShortcutDirectoryPreset
    @State private var activeDirectory: URL
    @State private var shortcuts: [CreatedAppShortcut] = []
    @State private var searchQuery = ""
    @State private var selectedCandidateIds: Set<String> = []
    @State private var statusBanner: String?
    @State private var errorBanner: String?
    @State private var confirmClearAll = false

    public init(
        availableApps: [ShortcutAppCandidate] = [],
        initialDirectory: URL = AppShortcutBuilder.defaultShortcutsDirectory,
        refreshSpotlightOnWrite: Bool = true,
        onLaunchShortcut: ((CreatedAppShortcut) -> Void)? = nil,
        onRevealInFinder: ((CreatedAppShortcut) -> Void)? = nil,
        onShortcutsChanged: (([CreatedAppShortcut]) -> Void)? = nil,
        onClose: @escaping () -> Void
    ) {
        self.availableApps = availableApps
        self.initialDirectory = initialDirectory
        self.refreshSpotlightOnWrite = refreshSpotlightOnWrite
        self.onLaunchShortcut = onLaunchShortcut
        self.onRevealInFinder = onRevealInFinder
        self.onShortcutsChanged = onShortcutsChanged
        self.onClose = onClose

        let preset: ShortcutDirectoryPreset
        if initialDirectory.standardizedFileURL == AppShortcutBuilder.defaultShortcutsDirectory.standardizedFileURL {
            preset = .spotlightApps
        } else if initialDirectory.standardizedFileURL == AppShortcutBuilder.desktopDirectory.standardizedFileURL {
            preset = .desktop
        } else {
            preset = .custom
        }
        _directoryPreset = State(initialValue: preset)
        _activeDirectory = State(initialValue: initialDirectory)
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSheetHeader(
                icon: "app.badge.checkmark.fill",
                iconTint: .purple,
                title: "Spotlight 与桌面快捷方式",
                subtitle: "为手机应用生成原生 macOS .app 入口，支持 Spotlight 聚焦搜索与双击打开独立小窗。",
                badgeText: "\(shortcuts.count) 个已创建",
                badgeColor: .accentColor
            )

            directoryBar
            tabAndSearchBar

            if let errorBanner {
                OpalNoticeBanner(tone: .error, message: errorBanner, onDismiss: { self.errorBanner = nil })
            } else if let statusBanner {
                OpalNoticeBanner(tone: .success, message: statusBanner, onDismiss: { self.statusBanner = nil })
            }

            Group {
                switch selectedTab {
                case .installed: installedShortcutsList
                case .createBatch: batchCreateList
                }
            }
            .frame(minHeight: 270, maxHeight: 350)

            footerSection
        }
        .padding(OpalSpacing.xl)
        .opalModalSheetChrome(width: 600)
        .onAppear { reloadShortcuts() }
        .alert("移除当前目录全部快捷方式？", isPresented: $confirmClearAll) {
            Button("取消", role: .cancel) {}
            Button("全部移除", role: .destructive) { removeAllInActiveDirectory() }
        } message: {
            Text("仅移除由 OpalDesk 生成的 .app 快捷方式（共 \(shortcuts.count) 项），不会影响目录内的其他文件。")
        }
    }

    // MARK: - Controls

    private var directoryBar: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            HStack(spacing: OpalSpacing.sm) {
                Picker("目标目录", selection: $directoryPreset) {
                    Text("Spotlight 目录").tag(ShortcutDirectoryPreset.spotlightApps)
                    Text("桌面").tag(ShortcutDirectoryPreset.desktop)
                    Text("自定义").tag(ShortcutDirectoryPreset.custom)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .onChange(of: directoryPreset) { _, newValue in
                    switch newValue {
                    case .spotlightApps:
                        activeDirectory = AppShortcutBuilder.defaultShortcutsDirectory
                        reloadShortcuts()
                    case .desktop:
                        activeDirectory = AppShortcutBuilder.desktopDirectory
                        reloadShortcuts()
                    case .custom:
                        pickCustomDirectory()
                    }
                }

                Button("选择目录…") { pickCustomDirectory() }
                    .controlSize(.small)

                OpalGlassIconButton(
                    systemName: "folder",
                    size: 26,
                    iconSize: 12,
                    helpText: "在 Finder 中打开目录"
                ) {
                    NSWorkspace.shared.open(activeDirectory)
                }
            }

            Text(activeDirectory.path)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var tabAndSearchBar: some View {
        HStack(spacing: OpalSpacing.sm) {
            Picker("视图", selection: $selectedTab) {
                Text("已创建 (\(shortcuts.count))").tag(SheetTab.installed)
                Text("批量创建 (\(availableApps.count))").tag(SheetTab.createBatch)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 240)

            Spacer()

            OpalSearchField(placeholder: "搜索应用名或包名…", text: $searchQuery)
                .frame(width: 230)
        }
    }

    // MARK: - Installed Shortcuts List

    private var filteredShortcuts: [CreatedAppShortcut] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return shortcuts }
        return shortcuts.filter {
            $0.displayName.lowercased().contains(q)
                || $0.packageName.lowercased().contains(q)
                || ($0.deviceName?.lowercased().contains(q) ?? false)
        }
    }

    @ViewBuilder
    private var installedShortcutsList: some View {
        if filteredShortcuts.isEmpty {
            VStack(spacing: OpalSpacing.sm) {
                OpalIconBadge(systemName: "sparkles.rectangle.stack", tint: .secondary, size: 46, iconSize: 20, cornerRadius: 14)
                Text(shortcuts.isEmpty ? "当前目录尚未创建快捷方式" : "没有匹配的快捷方式")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.secondary)
                if !availableApps.isEmpty && shortcuts.isEmpty {
                    Button("前往批量创建") { selectedTab = .createBatch }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        } else {
            ScrollView {
                LazyVStack(spacing: OpalSpacing.xs) {
                    ForEach(filteredShortcuts) { shortcut in
                        shortcutRow(shortcut)
                    }
                }
                .padding(OpalSpacing.sm)
            }
            .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        }
    }

    private func shortcutRow(_ shortcut: CreatedAppShortcut) -> some View {
        HStack(spacing: OpalSpacing.md) {
            OpalAppMonogram(
                label: shortcut.displayName,
                packageKey: shortcut.packageName,
                iconData: shortcut.iconData,
                fallbackSymbol: "app.fill",
                size: 36,
                cornerRadius: 10
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.xs) {
                    Text(shortcut.displayName)
                        .font(.callout.weight(.semibold))
                        .lineLimit(1)
                    if let deviceName = shortcut.deviceName, !deviceName.isEmpty {
                        StatusBadgeView(text: deviceName, color: .secondary, backgroundOpacity: 0.12)
                    }
                }
                Text(shortcut.packageName)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.sm)

            HStack(spacing: OpalSpacing.xs) {
                Button {
                    launchShortcut(shortcut)
                } label: {
                    Label("测试启动", systemImage: "play.fill")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                OpalGlassIconButton(
                    systemName: "folder",
                    tint: .secondary,
                    size: 26,
                    iconSize: 12,
                    helpText: "在 Finder 中显示"
                ) { revealShortcut(shortcut) }

                OpalGlassIconButton(
                    systemName: "trash",
                    tint: .red,
                    size: 26,
                    iconSize: 12,
                    helpText: "删除快捷方式"
                ) { deleteShortcut(shortcut) }
            }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 8)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile)
    }

    // MARK: - Batch Creation List

    private var filteredCandidates: [ShortcutAppCandidate] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return availableApps }
        return availableApps.filter {
            $0.appLabel.lowercased().contains(q)
                || $0.packageName.lowercased().contains(q)
        }
    }

    private var existingPackageNames: Set<String> {
        Set(shortcuts.map(\.packageName))
    }

    @ViewBuilder
    private var batchCreateList: some View {
        if availableApps.isEmpty {
            VStack(spacing: OpalSpacing.sm) {
                OpalIconBadge(systemName: "apps.iphone", tint: .secondary, size: 46, iconSize: 20, cornerRadius: 14)
                Text("暂无可创建的手机应用列表")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.secondary)
                Text("连接手机并加载应用列表后，可在此一键批量生成 Spotlight 或桌面快捷方式。")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 360)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        } else {
            VStack(alignment: .leading, spacing: OpalSpacing.sm) {
                HStack {
                    Button("全选当前列表") {
                        selectedCandidateIds.formUnion(filteredCandidates.map(\.id))
                    }
                    .controlSize(.small)

                    Button("取消选择") {
                        selectedCandidateIds.removeAll()
                    }
                    .controlSize(.small)
                    .disabled(selectedCandidateIds.isEmpty)

                    Spacer()

                    Text("已选 \(selectedCandidateIds.count) 项")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                ScrollView {
                    LazyVStack(spacing: OpalSpacing.xs) {
                        ForEach(filteredCandidates) { candidate in
                            candidateRow(candidate)
                        }
                    }
                    .padding(OpalSpacing.sm)
                }
                .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
            }
        }
    }

    private func candidateRow(_ candidate: ShortcutAppCandidate) -> some View {
        let isSelected = selectedCandidateIds.contains(candidate.id)
        let alreadyExists = existingPackageNames.contains(candidate.packageName)

        return HStack(spacing: OpalSpacing.md) {
            Toggle(isOn: Binding(
                get: { isSelected },
                set: { checked in
                    if checked { selectedCandidateIds.insert(candidate.id) }
                    else { selectedCandidateIds.remove(candidate.id) }
                }
            )) { EmptyView() }
                .toggleStyle(.checkbox)

            OpalAppMonogram(
                label: candidate.appLabel,
                packageKey: candidate.packageName,
                iconData: candidate.iconPNGData,
                fallbackSymbol: "app.fill",
                size: 34,
                cornerRadius: 9
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.xs) {
                    Text(candidate.appLabel)
                        .font(.callout.weight(.medium))
                        .lineLimit(1)
                    if alreadyExists {
                        StatusBadgeView(text: "已存在", color: .green, horizontalPadding: 6, verticalPadding: 1)
                    }
                }
                Text(candidate.packageName)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 7)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: isSelected ? .accentColor : nil)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile)
        .contentShape(RoundedRectangle(cornerRadius: OpalRadius.tile, style: .continuous))
        .onTapGesture {
            if isSelected { selectedCandidateIds.remove(candidate.id) }
            else { selectedCandidateIds.insert(candidate.id) }
        }
    }

    // MARK: - Footer & Actions

    private var footerSection: some View {
        HStack {
            if selectedTab == .installed {
                Button("清空当前目录", role: .destructive) { confirmClearAll = true }
                    .disabled(shortcuts.isEmpty)

                Button("刷新") { reloadShortcuts() }
            } else {
                Button("批量创建 (\(selectedCandidateIds.count))") { createSelectedShortcuts() }
                    .buttonStyle(.borderedProminent)
                    .disabled(selectedCandidateIds.isEmpty)
            }

            Spacer()

            Button("关闭", action: onClose)
                .keyboardShortcut(.cancelAction)
        }
    }

    private func reloadShortcuts() {
        do {
            let loaded = try AppShortcutBuilder.listShortcuts(in: activeDirectory)
            shortcuts = loaded
            errorBanner = nil
            onShortcutsChanged?(loaded)
        } catch {
            errorBanner = error.localizedDescription
        }
    }

    private func launchShortcut(_ shortcut: CreatedAppShortcut) {
        if let onLaunchShortcut {
            onLaunchShortcut(shortcut)
        } else {
            NSWorkspace.shared.open(shortcut.deepLinkURL)
        }
    }

    private func revealShortcut(_ shortcut: CreatedAppShortcut) {
        if let onRevealInFinder {
            onRevealInFinder(shortcut)
        } else {
            NSWorkspace.shared.activateFileViewerSelecting([shortcut.bundleURL])
        }
    }

    private func deleteShortcut(_ shortcut: CreatedAppShortcut) {
        do {
            try AppShortcutBuilder.removeShortcut(at: shortcut.bundleURL)
            statusBanner = "已删除快捷方式「\(shortcut.displayName)」"
            errorBanner = nil
            reloadShortcuts()
        } catch {
            errorBanner = error.localizedDescription
        }
    }

    private func removeAllInActiveDirectory() {
        do {
            let removed = try AppShortcutBuilder.removeAllShortcuts(in: activeDirectory)
            statusBanner = "已移除 \(removed) 个 OpalDesk 快捷方式"
            errorBanner = nil
            reloadShortcuts()
        } catch {
            errorBanner = error.localizedDescription
        }
    }

    private func createSelectedShortcuts() {
        let specs = availableApps
            .filter { selectedCandidateIds.contains($0.id) }
            .map { $0.toShortcutSpec() }
        guard !specs.isEmpty else { return }

        do {
            let created = try AppShortcutBuilder.createShortcuts(
                specs: specs,
                in: activeDirectory,
                overwrite: true,
                refreshSpotlight: refreshSpotlightOnWrite
            )
            selectedCandidateIds.removeAll()
            statusBanner = "已在 \(activeDirectory.lastPathComponent) 创建 \(created.count) 个快捷方式"
            errorBanner = nil
            selectedTab = .installed
            reloadShortcuts()
        } catch {
            errorBanner = error.localizedDescription
        }
    }

    private func pickCustomDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        panel.directoryURL = activeDirectory
        panel.prompt = "选择目录"
        if panel.runModal() == .OK, let url = panel.url {
            activeDirectory = url
            directoryPreset = .custom
            reloadShortcuts()
        } else if directoryPreset == .custom,
                  activeDirectory.standardizedFileURL == AppShortcutBuilder.defaultShortcutsDirectory.standardizedFileURL
        {
            directoryPreset = .spotlightApps
        }
    }
}
