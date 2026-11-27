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
            q.isEmpty || app.displayLabel.lowercased().contains(q) || app.pkg.lowercased().contains(q)
        }
        let favRanks = favoritePackageRank
        let localCounts = Dictionary(uniqueKeysWithValues: model.popoutHistory.map { ($0.appPackage, $0.openCount) })

        return base.sorted { lhs, rhs in
            let lFav = favRanks[lhs.pkg]
            let rFav = favRanks[rhs.pkg]
            if lFav != nil || rFav != nil {
                if let lf = lFav, let rf = rFav { return lf < rf }
                return lFav != nil
            }
            if sortByUsage {
                let lc = max(lhs.openCount, localCounts[lhs.pkg] ?? 0)
                let rc = max(rhs.openCount, localCounts[rhs.pkg] ?? 0)
                if lc != rc { return lc > rc }
            }
            return lhs.displayLabel.localizedCaseInsensitiveCompare(rhs.displayLabel) == .orderedAscending
        }
    }

    private var favoriteFilteredApps: [RemoteAppInfo] {
        let favRanks = favoritePackageRank
        return filteredApps.filter { favRanks[$0.pkg] != nil }
    }

    private var regularFilteredApps: [RemoteAppInfo] {
        let favRanks = favoritePackageRank
        return filteredApps.filter { favRanks[$0.pkg] == nil }
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
                title: "远程应用与悬浮小窗",
                subtitle: "来自「\(context.deviceName)」· 最多同时开启 \(maxCount) 个独立悬浮窗",
                badgeText: "已开 \(activeCount)/\(maxCount)",
                badgeColor: activeCount >= maxCount ? .orange : .accentColor
            ) {
                HStack(spacing: OpalSpacing.sm) {
                    Button {
                        onDismiss()
                        model.openShortcutManager()
                    } label: {
                        Label("快捷方式", systemImage: "sparkles.rectangle.stack")
                            .font(.caption.weight(.medium))
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("管理已创建的 Spotlight / 桌面快捷方式")

                    Button("关闭", action: onDismiss)
                        .keyboardShortcut(.cancelAction)
                }
            }

            if !model.popoutManager.controllers.isEmpty || !relevantHistory.isEmpty {
                quickHistoryStrip
            }

            HStack(spacing: OpalSpacing.sm) {
                OpalSearchField(placeholder: "搜索「\(context.deviceName)」上的应用…", text: $searchQuery)

                Button {
                    sortByUsage.toggle()
                } label: {
                    Label(sortByUsage ? "常用排序" : "名称排序", systemImage: sortByUsage ? "flame.fill" : "textformat.abc")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .tint(sortByUsage ? .accentColor : nil)
                .controlSize(.small)

                OpalGlassIconButton(
                    systemName: "arrow.clockwise",
                    size: 28,
                    iconSize: 12.5,
                    helpText: "刷新应用列表"
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
                Label("运行中与历史小窗", systemImage: "clock.arrow.circlepath")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                if !model.popoutManager.controllers.isEmpty {
                    Button("全部关闭") {
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
                    ForEach(relevantHistory.filter { !model.isPopoutActive(pkg: $0.appPackage) }) { entry in
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

            OpalGlassIconButton(
                systemName: ctrl.isPinnedOnTop ? "pin.fill" : "pin",
                tint: ctrl.isPinnedOnTop ? .orange : .secondary,
                size: 18,
                iconSize: 9.5,
                helpText: ctrl.isPinnedOnTop ? "取消置顶" : "窗口置顶"
            ) { ctrl.togglePinnedOnTop() }

            OpalGlassIconButton(
                systemName: "arrow.clockwise",
                tint: .secondary,
                size: 18,
                iconSize: 9.5,
                helpText: "聚焦或重载小窗+应用"
            ) {
                model.requestPopoutLaunch(
                    pkg: ctrl.appPackage,
                    cls: ctrl.appActivity,
                    label: ctrl.appLabel,
                    pickerContext: context
                )
            }

            OpalGlassIconButton(
                systemName: "xmark",
                tint: .secondary,
                size: 18,
                iconSize: 9,
                helpText: "关闭此小窗"
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
                Text(context.statusMessage.isEmpty ? "正在加载手机应用列表…" : context.statusMessage)
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
                Button("重试获取") { model.refreshRemoteAppPicker() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        } else if filteredApps.isEmpty {
            ContentUnavailableView("未找到匹配应用", systemImage: "magnifyingglass")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
        } else {
            let favorites = favoriteFilteredApps
            let others = regularFilteredApps
            ScrollView {
                LazyVStack(alignment: .leading, spacing: OpalSpacing.xs) {
                    if !favorites.isEmpty {
                        Label("常用置顶 (\(favorites.count))", systemImage: "star.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.orange)
                            .padding(.horizontal, 6)
                            .padding(.top, 2)
                            .padding(.bottom, 2)

                        ForEach(favorites) { app in
                            appRow(app)
                        }

                        if !others.isEmpty {
                            Text("全部应用")
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
        let isOpen = model.isPopoutActive(pkg: app.pkg)
        let isFavorite = model.isFavoriteApp(pkg: app.pkg, deviceKey: context.deviceKey)
        let prefSummary = model.popoutPreferenceSummary(pkg: app.pkg, deviceKey: context.deviceKey)
        let isLimitHit = !isOpen && activeCount >= maxCount

        return HStack(spacing: OpalSpacing.md) {
            OpalAppMonogram(
                label: app.displayLabel,
                packageKey: app.pkg,
                size: 36,
                cornerRadius: 10,
                showActiveIndicator: isOpen
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.xs) {
                    Text(app.displayLabel)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    if isOpen {
                        StatusBadgeView(text: "小窗运行中", color: .green)
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
                Text(app.pkg)
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
                helpText: isFavorite ? "取消常用置顶" : "设为常用置顶应用"
            ) {
                model.toggleFavoriteApp(pkg: app.pkg, deviceKey: context.deviceKey)
            }

            OpalGlassIconButton(
                systemName: "plus.app",
                tint: .secondary,
                size: 26,
                iconSize: 12.5,
                helpText: "创建 Spotlight 快捷方式（~/Applications/OpalDesk Apps/）"
            ) {
                model.createSpotlightShortcut(
                    for: app,
                    deviceKey: context.deviceKey,
                    deviceName: context.deviceName
                )
            }

            if context.allowLaunchInDesktop && model.isAuthenticated {
                Button("大屏打开") {
                    model.launchApp(app)
                    onDismiss()
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }

            Button(isOpen ? "小窗已开" : (isLimitHit ? "已满 \(maxCount)/\(maxCount)" : "独立小窗")) {
                model.requestPopoutLaunch(
                    pkg: app.pkg,
                    cls: app.cls,
                    label: app.displayLabel,
                    pickerContext: context
                )
            }
            .buttonStyle(.borderedProminent)
            .tint(isOpen ? .green : .accentColor)
            .controlSize(.small)
            .disabled(isLimitHit)
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 8)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: isOpen ? .green : nil)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile, tint: isOpen ? .green : nil)
    }
}
