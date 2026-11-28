import AppKit
import OpalDeskCore
import SwiftUI

/// Lightweight value model representing a remote Android app eligible for macOS shortcut creation.
public struct ShortcutAppCandidate: Sendable, Equatable, Hashable, Identifiable {
    public var id: String {
        let dev = deviceKey ?? ""
        return "\(dev)::\(appKey)::\(activityClass ?? "")"
    }

    public let packageName: String
    public let activityClass: String?
    public let appLabel: String
    public let deviceKey: String?
    public let deviceName: String?
    public let iconPNGData: Data?
    public let userId: Int
    public let profileType: String

    public var appKey: String {
        RemoteAppInfo.formatAppKey(pkg: packageName, userId: userId)
    }

    public var profileBadgeText: String? {
        RemoteAppInfo.profileBadgeText(userId: userId, profileType: profileType)
    }

    public init(
        packageName: String,
        activityClass: String? = nil,
        appLabel: String,
        deviceKey: String? = nil,
        deviceName: String? = nil,
        iconPNGData: Data? = nil,
        userId: Int = 0,
        profileType: String = ""
    ) {
        let normalizedUid = max(0, userId)
        self.packageName = packageName
        self.activityClass = activityClass
        self.appLabel = appLabel
        self.deviceKey = deviceKey
        self.deviceName = deviceName
        self.iconPNGData = iconPNGData
        self.userId = normalizedUid
        self.profileType = RemoteAppInfo.normalizeProfileType(profileType, userId: normalizedUid)
    }

    public func toShortcutSpec() -> AppShortcutSpec {
        AppShortcutSpec(
            packageName: packageName,
            activityClass: activityClass,
            appLabel: appLabel,
            deviceKey: deviceKey,
            deviceName: deviceName,
            iconPNGData: iconPNGData,
            userId: userId,
            profileType: profileType
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
        case .spotlightApps: return OpalL10n.text("shortcut.dir.spotlight")
        case .desktop: return OpalL10n.text("shortcut.dir.desktop")
        case .custom: return OpalL10n.text("shortcut.dir.custom")
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
                title: OpalL10n.text("shortcut.title"),
                subtitle: OpalL10n.text("shortcut.subtitle"),
                badgeText: OpalL10n.format("shortcut.badge_count_fmt", shortcuts.count),
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
        .alert(OpalL10n.text("shortcut.clear_alert.title"), isPresented: $confirmClearAll) {
            Button(OpalL10n.text("common.cancel"), role: .cancel) {}
            Button(OpalL10n.text("shortcut.clear_alert.confirm"), role: .destructive) { removeAllInActiveDirectory() }
        } message: {
            Text(OpalL10n.format("shortcut.clear_alert.msg_fmt", shortcuts.count))
        }
    }

    // MARK: - Controls

    private var directoryBar: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            HStack(spacing: OpalSpacing.sm) {
                OpalSegmentedControl(
                    selection: $directoryPreset,
                    options: [
                        OpalSegmentOption(value: ShortcutDirectoryPreset.spotlightApps, label: OpalL10n.text("shortcut.dir.spotlight_short")),
                        OpalSegmentOption(value: ShortcutDirectoryPreset.desktop, label: OpalL10n.text("shortcut.dir.desktop_short")),
                        OpalSegmentOption(value: ShortcutDirectoryPreset.custom, label: OpalL10n.text("shortcut.dir.custom_short")),
                    ],
                    compact: true
                )
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

                Spacer(minLength: 4)

                Button(OpalL10n.text("shortcut.choose_dir")) { pickCustomDirectory() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))

                OpalGlassIconButton(
                    systemName: "folder",
                    size: 28,
                    iconSize: 12,
                    helpText: OpalL10n.text("shortcut.open_dir_help")
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
            OpalSegmentedControl(
                selection: $selectedTab,
                options: [
                    OpalSegmentOption(value: SheetTab.installed, label: OpalL10n.format("shortcut.tab.installed_fmt", shortcuts.count)),
                    OpalSegmentOption(value: SheetTab.createBatch, label: OpalL10n.format("shortcut.tab.batch_fmt", availableApps.count)),
                ],
                compact: true
            )

            Spacer()

            OpalSearchField(placeholder: OpalL10n.text("shortcut.search_placeholder"), text: $searchQuery)
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
                Text(shortcuts.isEmpty ? OpalL10n.text("shortcut.empty.none") : OpalL10n.text("shortcut.empty.no_match"))
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.secondary)
                if !availableApps.isEmpty && shortcuts.isEmpty {
                    Button(OpalL10n.text("shortcut.go_batch")) { selectedTab = .createBatch }
                        .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .small, isPill: true))
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
                    Label(OpalL10n.text("shortcut.test_launch"), systemImage: "play.fill")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))

                OpalGlassIconButton(
                    systemName: "folder",
                    tint: .secondary,
                    size: 26,
                    iconSize: 12,
                    helpText: OpalL10n.text("common.reveal_in_finder")
                ) { revealShortcut(shortcut) }

                OpalGlassIconButton(
                    systemName: "trash",
                    tint: .red,
                    size: 26,
                    iconSize: 12,
                    helpText: OpalL10n.text("shortcut.delete_help")
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
                || ($0.profileBadgeText?.lowercased().contains(q) ?? false)
                || RemoteAppInfo.profileSearchKeywords(userId: $0.userId, profileType: $0.profileType)
                    .contains(where: { $0.lowercased().contains(q) })
        }
    }

    private var existingAppKeys: Set<String> {
        Set(shortcuts.map(\.appKey))
    }

    @ViewBuilder
    private var batchCreateList: some View {
        if availableApps.isEmpty {
            VStack(spacing: OpalSpacing.sm) {
                OpalIconBadge(systemName: "apps.iphone", tint: .secondary, size: 46, iconSize: 20, cornerRadius: 14)
                Text(OpalL10n.text("shortcut.batch.empty_title"))
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.secondary)
                Text(OpalL10n.text("shortcut.batch.empty_sub"))
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
                    Button(OpalL10n.text("shortcut.batch.select_all")) {
                        selectedCandidateIds.formUnion(filteredCandidates.map(\.id))
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))

                    Button(OpalL10n.text("common.deselect")) {
                        selectedCandidateIds.removeAll()
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                    .disabled(selectedCandidateIds.isEmpty)

                    Spacer()

                    Text(OpalL10n.format("shortcut.batch.selected_fmt", selectedCandidateIds.count))
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
        let alreadyExists = existingAppKeys.contains(candidate.appKey)

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
                packageKey: candidate.appKey,
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
                    if let badge = candidate.profileBadgeText {
                        StatusBadgeView(
                            text: badge,
                            color: .opalProfileTint(candidate.profileType),
                            horizontalPadding: 6,
                            verticalPadding: 1,
                            backgroundOpacity: 0.16
                        )
                    }
                    if alreadyExists {
                        StatusBadgeView(text: OpalL10n.text("shortcut.batch.exists"), color: .green, horizontalPadding: 6, verticalPadding: 1)
                    }
                }
                Text(candidate.userId > 0 ? "\(candidate.packageName) · u\(candidate.userId)" : candidate.packageName)
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
                Button(OpalL10n.text("shortcut.footer.clear_dir"), role: .destructive) { confirmClearAll = true }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .destructive, size: .regular, isPill: true))
                    .disabled(shortcuts.isEmpty)

                Button(OpalL10n.text("common.refresh")) { reloadShortcuts() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))
            } else {
                Button(OpalL10n.format("shortcut.footer.create_fmt", selectedCandidateIds.count)) { createSelectedShortcuts() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
                    .disabled(selectedCandidateIds.isEmpty)
            }

            Spacer()

            Button(OpalL10n.text("common.close"), action: onClose)
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))
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
            statusBanner = OpalL10n.format("shortcut.status.deleted_fmt", shortcut.displayName)
            errorBanner = nil
            reloadShortcuts()
        } catch {
            errorBanner = error.localizedDescription
        }
    }

    private func removeAllInActiveDirectory() {
        do {
            let removed = try AppShortcutBuilder.removeAllShortcuts(in: activeDirectory)
            statusBanner = OpalL10n.format("shortcut.status.removed_all_fmt", removed)
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
            statusBanner = OpalL10n.format("shortcut.status.created_fmt", created.count, activeDirectory.lastPathComponent)
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
        panel.prompt = OpalL10n.text("shortcut.panel.prompt")
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
