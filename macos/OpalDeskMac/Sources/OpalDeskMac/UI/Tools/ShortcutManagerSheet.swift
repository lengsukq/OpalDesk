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

/// Standalone SwiftUI management sheet for Spotlight / Desktop `.app` shortcuts created by OpalDesk.
/// Completely decoupled from `AppModel`; hosts pass candidates and optional action closures.
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
        VStack(alignment: .leading, spacing: 14) {
            headerSection
            directoryBar
            tabAndSearchBar

            if let errorBanner {
                bannerView(text: errorBanner, icon: "exclamationmark.triangle.fill", color: .red)
            } else if let statusBanner {
                bannerView(text: statusBanner, icon: "checkmark.circle.fill", color: .green)
            }

            Group {
                switch selectedTab {
                case .installed:
                    installedShortcutsList
                case .createBatch:
                    batchCreateList
                }
            }
            .frame(minHeight: 270, maxHeight: 340)

            footerSection
        }
        .padding(20)
        .frame(width: 580)
        .onAppear {
            reloadShortcuts()
        }
        .alert("移除当前目录全部快捷方式？", isPresented: $confirmClearAll) {
            Button("取消", role: .cancel) {}
            Button("全部移除", role: .destructive) {
                removeAllInActiveDirectory()
            }
        } message: {
            Text("仅移除由 OpalDesk 生成的 .app 快捷方式（共 \(shortcuts.count) 项），不会影响目录内的其他文件。")
        }
    }

    // MARK: - Header & Controls

    private var headerSection: some View {
        HStack(spacing: 12) {
            Image(systemName: "app.badge.checkmark.fill")
                .font(.system(size: 28))
                .foregroundStyle(Color.accentColor)

            VStack(alignment: .leading, spacing: 2) {
                Text("Spotlight 与桌面快捷方式")
                    .font(.title3.weight(.semibold))
                Text("为手机应用生成原生 macOS .app 入口，支持 Spotlight 聚焦搜索与双击独立窗口打开。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            StatusBadgeView(text: "\(shortcuts.count) 个已创建", color: .accentColor)
        }
    }

    private var directoryBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
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

                Button("选择目录…") {
                    pickCustomDirectory()
                }
                .controlSize(.small)

                Button {
                    NSWorkspace.shared.open(activeDirectory)
                } label: {
                    Image(systemName: "folder")
                }
                .controlSize(.small)
                .help("在 Finder 中打开目录")
            }

            Text(activeDirectory.path)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(11)
        .opalDeskCardContainer(cornerRadius: 14, borderOpacity: 0.16)
    }

    private var tabAndSearchBar: some View {
        HStack(spacing: 10) {
            Picker("视图", selection: $selectedTab) {
                Text("已创建 (\(shortcuts.count))").tag(SheetTab.installed)
                Text("批量创建 (\(availableApps.count))").tag(SheetTab.createBatch)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 240)

            Spacer()

            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                    .font(.caption)
                TextField("搜索应用名或包名…", text: $searchQuery)
                    .textFieldStyle(.plain)
                    .font(.callout)
                if !searchQuery.isEmpty {
                    Button {
                        searchQuery = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .frame(width: 220)
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
            VStack(spacing: 8) {
                Image(systemName: "sparkles.rectangle.stack")
                    .font(.system(size: 32))
                    .foregroundStyle(.tertiary)
                Text(shortcuts.isEmpty ? "当前目录尚未创建快捷方式" : "没有匹配的快捷方式")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.secondary)
                if !availableApps.isEmpty && shortcuts.isEmpty {
                    Button("前往批量创建") {
                        selectedTab = .createBatch
                    }
                    .controlSize(.small)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.primary.opacity(0.03), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        } else {
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(filteredShortcuts) { shortcut in
                        shortcutRow(shortcut)
                    }
                }
                .padding(2)
            }
        }
    }

    private func shortcutRow(_ shortcut: CreatedAppShortcut) -> some View {
        HStack(spacing: 12) {
            iconThumbnail(data: shortcut.iconData)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
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

            Spacer(minLength: 8)

            HStack(spacing: 6) {
                Button {
                    launchShortcut(shortcut)
                } label: {
                    Label("测试启动", systemImage: "play.fill")
                }
                .controlSize(.small)

                Button {
                    revealShortcut(shortcut)
                } label: {
                    Image(systemName: "folder")
                }
                .controlSize(.small)
                .help("在 Finder 中显示")

                Button(role: .destructive) {
                    deleteShortcut(shortcut)
                } label: {
                    Image(systemName: "trash")
                }
                .controlSize(.small)
                .help("删除快捷方式")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
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
            VStack(spacing: 8) {
                Image(systemName: "apps.iphone")
                    .font(.system(size: 32))
                    .foregroundStyle(.tertiary)
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
            .background(Color.primary.opacity(0.03), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        } else {
            VStack(alignment: .leading, spacing: 8) {
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
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(filteredCandidates) { candidate in
                            candidateRow(candidate)
                        }
                    }
                    .padding(2)
                }
            }
        }
    }

    private func candidateRow(_ candidate: ShortcutAppCandidate) -> some View {
        let isSelected = selectedCandidateIds.contains(candidate.id)
        let alreadyExists = existingPackageNames.contains(candidate.packageName)

        return HStack(spacing: 11) {
            Toggle(isOn: Binding(
                get: { isSelected },
                set: { checked in
                    if checked {
                        selectedCandidateIds.insert(candidate.id)
                    } else {
                        selectedCandidateIds.remove(candidate.id)
                    }
                }
            )) {
                EmptyView()
            }
            .toggleStyle(.checkbox)

            iconThumbnail(data: candidate.iconPNGData)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
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
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(
            (isSelected ? Color.accentColor.opacity(0.09) : Color.primary.opacity(0.035)),
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
        .contentShape(Rectangle())
        .onTapGesture {
            if isSelected {
                selectedCandidateIds.remove(candidate.id)
            } else {
                selectedCandidateIds.insert(candidate.id)
            }
        }
    }

    // MARK: - Footer & Shared Helpers

    private var footerSection: some View {
        HStack {
            if selectedTab == .installed {
                Button("清空当前目录", role: .destructive) {
                    confirmClearAll = true
                }
                .disabled(shortcuts.isEmpty)

                Button("刷新") {
                    reloadShortcuts()
                }
            } else {
                Button("批量创建 (\(selectedCandidateIds.count))") {
                    createSelectedShortcuts()
                }
                .buttonStyle(.borderedProminent)
                .disabled(selectedCandidateIds.isEmpty)
            }

            Spacer()

            Button("关闭") {
                onClose()
            }
            .keyboardShortcut(.cancelAction)
        }
    }

    @ViewBuilder
    private func iconThumbnail(data: Data?) -> some View {
        if let data, let img = NSImage(data: data) {
            Image(nsImage: img)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 34, height: 34)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color.accentColor.opacity(0.14))
                Image(systemName: "app.fill")
                    .foregroundStyle(Color.accentColor)
            }
            .frame(width: 34, height: 34)
        }
    }

    private func bannerView(text: String, icon: String, color: Color) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .foregroundStyle(color)
            Text(text)
                .font(.caption)
                .foregroundStyle(color == .red ? .red : .primary)
            Spacer()
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(color.opacity(0.1), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
    }

    // MARK: - Actions

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
