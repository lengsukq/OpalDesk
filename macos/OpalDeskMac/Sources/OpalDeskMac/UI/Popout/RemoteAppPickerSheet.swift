import OpalDeskCore
import SwiftUI

/// Liquid Glass sheet for browsing remote Android apps, viewing recent floating-app history,
/// managing up to 3 concurrent floating windows, creating Spotlight shortcuts, and launching apps.
public struct RemoteAppPickerSheet: View {
    @Bindable public var model: AppModel
    public let context: RemoteAppPickerContext
    public let onDismiss: () -> Void

    @State private var searchQuery: String = ""
    @State private var sortByUsage: Bool = true

    public init(model: AppModel, context: RemoteAppPickerContext, onDismiss: @escaping () -> Void) {
        self.model = model
        self.context = context
        self.onDismiss = onDismiss
    }

    private var activeCount: Int { model.popoutManager.controllers.count }
    private var maxCount: Int { PopoutWindowManager.maxConcurrentWindows }

    private var favoritePackageRank: [String: Int] {
        let ordered = model.favoriteAppPackages(for: context.deviceKey)
        return Dictionary(uniqueKeysWithValues: ordered.enumerated().map { ($0.element, $0.offset) })
    }

    private var filteredApps: [RemoteAppInfo] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let base = context.apps.filter { app in
            if q.isEmpty { return true }
            if app.displayLabel.lowercased().contains(q) || app.pkg.lowercased().contains(q) {
                return true
            }
            if app.isSecondaryProfile {
                if let badge = app.profileBadgeText?.lowercased(), badge.contains(q) { return true }
                if RemoteAppInfo.profileSearchKeywords(userId: app.userId, profileType: app.profileType)
                    .contains(where: { $0.lowercased().contains(q) })
                {
                    return true
                }
            }
            return false
        }
        let favRanks = favoritePackageRank
        let localCounts = Dictionary(model.popoutHistory.map { ($0.appKey, $0.openCount) }, uniquingKeysWith: max)

        return base.sorted { lhs, rhs in
            let lFav = favRanks[lhs.appKey]
            let rFav = favRanks[rhs.appKey]
            if lFav != nil || rFav != nil {
                if let lf = lFav, let rf = rFav { return lf < rf }
                return lFav != nil
            }
            if sortByUsage {
                let lc = max(lhs.openCount, localCounts[lhs.appKey] ?? 0)
                let rc = max(rhs.openCount, localCounts[rhs.appKey] ?? 0)
                if lc != rc { return lc > rc }
            }
            let nameOrder = lhs.displayLabel.localizedCaseInsensitiveCompare(rhs.displayLabel)
            if nameOrder != .orderedSame { return nameOrder == .orderedAscending }
            return lhs.userId < rhs.userId
        }
    }

    private var favoriteFilteredApps: [RemoteAppInfo] {
        let favRanks = favoritePackageRank
        return filteredApps.filter { favRanks[$0.appKey] != nil }
    }

    private var regularFilteredApps: [RemoteAppInfo] {
        let favRanks = favoritePackageRank
        return filteredApps.filter { favRanks[$0.appKey] == nil }
    }

    private var relevantHistory: [PopoutHistoryEntry] {
        let byDevice = model.popoutHistory.filter { $0.deviceKey == context.deviceKey || $0.deviceName == context.deviceName }
        return byDevice.isEmpty ? Array(model.popoutHistory.prefix(6)) : Array(byDevice.prefix(8))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSheetHeader(
                icon: "macwindow.on.rectangle",
                iconTint: .accentColor,
                title: OpalL10n.text("picker.title"),
                subtitle: OpalL10n.format("picker.subtitle_fmt", context.deviceName, maxCount),
                badgeText: OpalL10n.format("hub.history.open_count_fmt", activeCount, maxCount),
                badgeColor: activeCount >= maxCount ? .orange : .accentColor
            ) {
                HStack(spacing: OpalSpacing.sm) {
                    Button {
                        onDismiss()
                        model.openShortcutManager()
                    } label: {
                        Label(OpalL10n.text("picker.shortcuts_btn"), systemImage: "sparkles.rectangle.stack")
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                    .help(OpalL10n.text("picker.shortcuts_help"))

                    Button(OpalL10n.text("common.close"), action: onDismiss)
                        .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                        .keyboardShortcut(.cancelAction)
                }
            }

            if !model.popoutManager.controllers.isEmpty || !relevantHistory.isEmpty {
                quickHistoryStrip
            }

            HStack(spacing: OpalSpacing.sm) {
                OpalSearchField(placeholder: OpalL10n.format("picker.search_placeholder_fmt", context.deviceName), text: $searchQuery)

                Button {
                    sortByUsage.toggle()
                } label: {
                    Label(sortByUsage ? OpalL10n.text("picker.sort.usage") : OpalL10n.text("picker.sort.name"), systemImage: sortByUsage ? "flame.fill" : "textformat.abc")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: sortByUsage ? .prominent : .secondary, size: .small, isPill: true))

                OpalGlassIconButton(
                    systemName: "arrow.clockwise",
                    size: 28,
                    iconSize: 12.5,
                    helpText: OpalL10n.text("picker.refresh_help")
                ) {
                    model.refreshRemoteAppPicker()
                }
                .disabled(context.isLoading)
            }

            bodyList
        }
        .padding(OpalSpacing.xl)
        .opalModalSheetChrome(width: 580, height: 620)
    }

    private var quickHistoryStrip: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            HStack {
                Label(OpalL10n.text("picker.strip.title"), systemImage: "clock.arrow.circlepath")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                if !model.popoutManager.controllers.isEmpty {
                    Button(OpalL10n.text("picker.strip.close_all")) {
                        model.popoutManager.closeAll(sendDisconnect: true)
                    }
                    .buttonStyle(.borderless)
                    .font(.caption)
                    .foregroundStyle(.red)
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: OpalSpacing.sm) {
                    ForEach(model.popoutManager.controllers) { ctrl in
                        activeControllerPill(ctrl)
                    }
                    ForEach(relevantHistory.filter { !model.isPopoutActive(pkg: $0.appPackage, userId: $0.appUserId) }) { entry in
                        historyEntryPill(entry)
                    }
                }
            }
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func activeControllerPill(_ ctrl: PopoutAppController) -> some View {
        HStack(spacing: 5) {
            Circle().fill(Color.green).frame(width: 6, height: 6)
            Text(ctrl.appLabel)
                .font(.caption.weight(.semibold))
                .lineLimit(1)
            if let badge = ctrl.profileBadgeText {
                StatusBadgeView(
                    text: badge,
                    color: .opalProfileTint(ctrl.appProfileType),
                    horizontalPadding: 5,
                    verticalPadding: 1,
                    backgroundOpacity: 0.16
                )
            }

            OpalGlassIconButton(
                systemName: ctrl.isPinnedOnTop ? "pin.fill" : "pin",
                tint: ctrl.isPinnedOnTop ? .orange : .secondary,
                size: 18,
                iconSize: 9.5,
                helpText: ctrl.isPinnedOnTop ? OpalL10n.text("hub.history.unpin_help") : OpalL10n.text("hub.history.pin_help")
            ) { ctrl.togglePinnedOnTop() }

            OpalGlassIconButton(
                systemName: "arrow.clockwise",
                tint: .secondary,
                size: 18,
                iconSize: 9.5,
                helpText: OpalL10n.text("picker.strip.reload_help")
            ) {
                model.requestPopoutLaunch(
                    pkg: ctrl.appPackage,
                    cls: ctrl.appActivity,
                    label: ctrl.appLabel,
                    userId: ctrl.appUserId,
                    profileType: ctrl.appProfileType,
                    pickerContext: context
                )
            }

            OpalGlassIconButton(
                systemName: "xmark",
                tint: .secondary,
                size: 18,
                iconSize: 9,
                helpText: OpalL10n.text("hub.history.close_help")
            ) { model.popoutManager.closeWindow(id: ctrl.id, sendDisconnect: true) }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Color.green.opacity(0.14), in: Capsule())
        .overlay(Capsule().strokeBorder(Color.green.opacity(0.32), lineWidth: 0.8))
        .onTapGesture {
            model.popoutManager.focusWindow(id: ctrl.id)
        }
    }

    private func historyEntryPill(_ entry: PopoutHistoryEntry) -> some View {
        Button {
            model.requestPopoutLaunch(
                pkg: entry.appPackage,
                cls: entry.appActivity,
                label: entry.displayLabel,
                userId: entry.appUserId,
                profileType: entry.appProfileType,
                pickerContext: context
            )
        } label: {
            HStack(spacing: 5) {
                if entry.pinnedOnTop {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 9))
                        .foregroundStyle(.orange)
                }
                Text(entry.displayLabel)
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                if let badge = entry.profileBadgeText {
                    StatusBadgeView(
                        text: badge,
                        color: .opalProfileTint(entry.appProfileType),
                        horizontalPadding: 5,
                        verticalPadding: 1,
                        backgroundOpacity: 0.14
                    )
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color.primary.opacity(0.06), in: Capsule())
            .overlay(Capsule().strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.65))
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var bodyList: some View {
        if context.isLoading && context.apps.isEmpty {
            VStack(spacing: OpalSpacing.md) {
                ProgressView()
                Text(context.statusMessage.isEmpty ? OpalL10n.text("picker.loading_default") : context.statusMessage)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        } else if let error = context.errorMessage, context.apps.isEmpty {
            VStack(spacing: OpalSpacing.md) {
                OpalIconBadge(systemName: "exclamationmark.triangle.fill", tint: .orange, size: 46, iconSize: 22, cornerRadius: 14)
                Text(error)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 360)
                Button(OpalL10n.text("picker.retry_fetch")) { model.refreshRemoteAppPicker() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        } else if filteredApps.isEmpty {
            ContentUnavailableView(OpalL10n.text("picker.empty_search"), systemImage: "magnifyingglass")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        } else {
            let favorites = favoriteFilteredApps
            let others = regularFilteredApps
            ScrollView {
                LazyVStack(alignment: .leading, spacing: OpalSpacing.xs) {
                    if !favorites.isEmpty {
                        Label(OpalL10n.format("picker.section.favorites_fmt", favorites.count), systemImage: "star.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.orange)
                            .padding(.horizontal, 6)
                            .padding(.top, 2)
                            .padding(.bottom, 2)

                        ForEach(favorites) { app in
                            appRow(app)
                        }

                        if !others.isEmpty {
                            Text(OpalL10n.text("picker.section.all_apps"))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 6)
                                .padding(.top, OpalSpacing.sm)
                                .padding(.bottom, 2)
                        }
                    }

                    ForEach(others) { app in
                        appRow(app)
                    }
                }
                .padding(OpalSpacing.sm)
            }
            .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        }
    }

    private func appRow(_ app: RemoteAppInfo) -> some View {
        let isOpen = model.isPopoutActive(pkg: app.pkg, userId: app.userId)
        let isFavorite = model.isFavoriteApp(pkg: app.pkg, userId: app.userId, deviceKey: context.deviceKey)
        let prefSummary = model.popoutPreferenceSummary(pkg: app.pkg, userId: app.userId, deviceKey: context.deviceKey)
        let isLimitHit = !isOpen && activeCount >= maxCount

        return HStack(spacing: OpalSpacing.md) {
            OpalAppMonogram(
                label: app.displayLabel,
                packageKey: app.appKey,
                size: 36,
                cornerRadius: 10,
                showActiveIndicator: isOpen
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.xs) {
                    Text(app.displayLabel)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    if let badge = app.profileBadgeText {
                        StatusBadgeView(
                            text: badge,
                            color: .opalProfileTint(app.profileType),
                            horizontalPadding: 6,
                            verticalPadding: 1.5,
                            backgroundOpacity: 0.16
                        )
                    }
                    if isOpen {
                        StatusBadgeView(text: OpalL10n.text("picker.badge.running"), color: .green)
                    }
                    if let prefSummary {
                        StatusBadgeView(
                            text: prefSummary,
                            color: .accentColor,
                            horizontalPadding: 6,
                            verticalPadding: 1.5,
                            backgroundOpacity: 0.11
                        )
                    }
                }
                Text(app.isSecondaryProfile ? "\(app.pkg) · u\(app.userId)" : app.pkg)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.sm)

            OpalGlassIconButton(
                systemName: isFavorite ? "star.fill" : "star",
                tint: isFavorite ? .orange : .secondary,
                isActive: isFavorite,
                size: 26,
                iconSize: 12.5,
                helpText: isFavorite ? OpalL10n.text("hub.history.unfav_help") : OpalL10n.text("hub.history.fav_help")
            ) {
                model.toggleFavoriteApp(pkg: app.pkg, userId: app.userId, deviceKey: context.deviceKey)
            }

            OpalGlassIconButton(
                systemName: "plus.app",
                tint: .secondary,
                size: 26,
                iconSize: 12.5,
                helpText: OpalL10n.text("picker.shortcut_create_help")
            ) {
                model.createSpotlightShortcut(
                    for: app,
                    deviceKey: context.deviceKey,
                    deviceName: context.deviceName
                )
            }

            if context.allowLaunchInDesktop && model.isAuthenticated {
                Button(OpalL10n.text("picker.open_desktop")) {
                    model.launchApp(app)
                    onDismiss()
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
            }

            Button(isOpen ? OpalL10n.text("picker.window_open") : (isLimitHit ? OpalL10n.format("picker.limit_fmt", maxCount, maxCount) : OpalL10n.text("hub.row.standalone_window"))) {
                model.requestPopoutLaunch(
                    pkg: app.pkg,
                    cls: app.cls,
                    label: app.displayLabel,
                    userId: app.userId,
                    profileType: app.profileType,
                    pickerContext: context
                )
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: isOpen ? .emerald : .prominent, size: .small, isPill: true))
            .disabled(isLimitHit)
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 8)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: isOpen ? .green : nil)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile, tint: isOpen ? .green : nil)
    }
}
