import OpalDeskCore
import SwiftUI

/// Liquid Glass sheet for browsing remote Android apps, viewing recent floating-app history,
/// managing up to 3 concurrent floating windows, and launching apps in desktop or standalone mode.
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

    private var filteredApps: [RemoteAppInfo] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let base = context.apps.filter { app in
            q.isEmpty || app.displayLabel.lowercased().contains(q) || app.pkg.lowercased().contains(q)
        }
        if sortByUsage {
            let localCounts = Dictionary(uniqueKeysWithValues: model.popoutHistory.map { ($0.appPackage, $0.openCount) })
            return base.sorted { lhs, rhs in
                let lc = max(lhs.openCount, localCounts[lhs.pkg] ?? 0)
                let rc = max(rhs.openCount, localCounts[rhs.pkg] ?? 0)
                if lc != rc { return lc > rc }
                return lhs.displayLabel.localizedCaseInsensitiveCompare(rhs.displayLabel) == .orderedAscending
            }
        }
        return base.sorted {
            $0.displayLabel.localizedCaseInsensitiveCompare($1.displayLabel) == .orderedAscending
        }
    }

    private var relevantHistory: [PopoutHistoryEntry] {
        let byDevice = model.popoutHistory.filter { $0.deviceKey == context.deviceKey || $0.deviceName == context.deviceName }
        return byDevice.isEmpty ? Array(model.popoutHistory.prefix(6)) : Array(byDevice.prefix(8))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            headerRow

            if !model.popoutManager.controllers.isEmpty || !relevantHistory.isEmpty {
                quickHistoryStrip
            }

            HStack(spacing: 8) {
                TextField("搜索「\(context.deviceName)」上的应用…", text: $searchQuery)
                    .textFieldStyle(.roundedBorder)

                Button {
                    sortByUsage.toggle()
                } label: {
                    Label(sortByUsage ? "常用排序" : "名称排序", systemImage: sortByUsage ? "flame.fill" : "textformat.abc")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .tint(sortByUsage ? .accentColor : nil)
                .controlSize(.small)

                Button {
                    model.refreshRemoteAppPicker()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(context.isLoading)
                .help("刷新应用列表")
            }

            bodyList
        }
        .padding(20)
        .frame(width: 560, height: 600)
    }

    private var headerRow: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text("远程应用与悬浮小窗")
                        .font(.title3.weight(.semibold))
                    StatusBadgeView(
                        text: "已开小窗 \(activeCount)/\(maxCount)",
                        color: activeCount >= maxCount ? .orange : .accentColor
                    )
                }
                Text("来自「\(context.deviceName)」· 最多可同时打开 \(maxCount) 个独立悬浮窗，支持窗口置顶")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button("关闭", action: onDismiss)
                .keyboardShortcut(.cancelAction)
        }
    }

    private var quickHistoryStrip: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("历史 APP 小窗 & 运行中", systemImage: "clock.arrow.circlepath")
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
                HStack(spacing: 8) {
                    ForEach(model.popoutManager.controllers) { ctrl in
                        HStack(spacing: 5) {
                            Circle().fill(Color.green).frame(width: 6, height: 6)
                            Text(ctrl.appLabel)
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                            Button {
                                ctrl.togglePinnedOnTop()
                            } label: {
                                Image(systemName: ctrl.isPinnedOnTop ? "pin.fill" : "pin")
                                    .font(.system(size: 10))
                                    .foregroundStyle(ctrl.isPinnedOnTop ? .orange : .secondary)
                            }
                            .buttonStyle(.plain)
                            .help(ctrl.isPinnedOnTop ? "取消置顶" : "窗口置顶")

                            Button {
                                model.requestPopoutLaunch(
                                    pkg: ctrl.appPackage,
                                    cls: ctrl.appActivity,
                                    label: ctrl.appLabel,
                                    pickerContext: context
                                )
                            } label: {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 10))
                            }
                            .buttonStyle(.plain)
                            .help("聚焦或重载小窗+应用")

                            Button {
                                model.popoutManager.closeWindow(id: ctrl.id, sendDisconnect: true)
                            } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 9, weight: .bold))
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                            .help("关闭此小窗")
                        }
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(Color.green.opacity(0.14), in: Capsule())
                        .overlay(Capsule().strokeBorder(Color.green.opacity(0.35), lineWidth: 0.8))
                        .onTapGesture {
                            model.popoutManager.focusWindow(id: ctrl.id)
                        }
                    }

                    ForEach(relevantHistory.filter { !model.isPopoutActive(pkg: $0.appPackage) }) { entry in
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
                            .padding(.horizontal, 9)
                            .padding(.vertical, 5)
                            .background(Color.secondary.opacity(0.12), in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(10)
        .opalDeskCardContainer(cornerRadius: 14, borderOpacity: 0.16)
    }

    @ViewBuilder
    private var bodyList: some View {
        if context.isLoading && context.apps.isEmpty {
            VStack(spacing: 12) {
                ProgressView()
                Text(context.statusMessage.isEmpty ? "正在加载手机应用列表…" : context.statusMessage)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = context.errorMessage, context.apps.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(.orange)
                Text(error)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("重试获取") { model.refreshRemoteAppPicker() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if filteredApps.isEmpty {
            ContentUnavailableView("未找到匹配应用", systemImage: "magnifyingglass")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List(filteredApps) { app in
                appRow(app)
            }
            .listStyle(.inset)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }

    private func appRow(_ app: RemoteAppInfo) -> some View {
        let isOpen = model.isPopoutActive(pkg: app.pkg)
        let isLimitHit = !isOpen && activeCount >= maxCount

        return HStack(spacing: 10) {
            appMonogram(app.displayLabel, pkg: app.pkg)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(app.displayLabel)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    if isOpen {
                        StatusBadgeView(text: "小窗运行中", color: .green)
                    }
                }
                Text(app.pkg)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

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
        .padding(.vertical, 3)
    }

    private func appMonogram(_ label: String, pkg: String) -> some View {
        let palette: [Color] = [.blue, .green, .indigo, .orange, .cyan, .pink, .purple]
        let idx = abs(pkg.hashValue) % palette.count
        let tint = palette[idx]
        let initial = String(label.trimmingCharacters(in: .whitespaces).prefix(1))
        return ZStack {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(tint.opacity(0.16))
                .frame(width: 34, height: 34)
            Text(initial.isEmpty ? "A" : initial)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(tint)
        }
    }
}
