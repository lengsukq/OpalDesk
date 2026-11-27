import AppKit
import OpalDeskCore
import SwiftUI

/// In-app diagnostic logs, issue reporting & feature request tab styled with unified Liquid Glass tokens.
public struct DiagnosticsLogView: View {
    private static let defaultDisplayLimit = 180

    @Bindable public var model: AppModel
    @State private var warningsOnly: Bool = false
    @State private var newestFirst: Bool = true
    @State private var searchQuery: String = ""
    @State private var showAllLines: Bool = false
    @State private var activeModalKind: FeedbackKind?

    public init(model: AppModel) {
        self.model = model
    }

    private var filteredEntries: [OpalLogEntry] {
        let base = OpalLogFilter.filterEntries(
            model.logEntries,
            warningsAndErrorsOnly: warningsOnly,
            query: searchQuery
        )
        return newestFirst ? base.reversed() : base
    }

    private var visibleEntries: [OpalLogEntry] {
        if showAllLines || filteredEntries.count <= Self.defaultDisplayLimit {
            return filteredEntries
        }
        return Array(filteredEntries.prefix(Self.defaultDisplayLimit))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.xl) {
            heroCard
            FeedbackTrackingView(model: model)
            logStreamSection
        }
        .onAppear { model.refreshFeedbackQuota() }
        .sheet(item: $activeModalKind) { kind in
            ReportIssueSheet(model: model, initialKind: kind) {
                activeModalKind = nil
            }
        }
    }

    // MARK: - 1. Diagnostic Hero Card

    private var heroCard: some View {
        let quota = model.feedbackQuota
        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            HStack(alignment: .center, spacing: OpalSpacing.md) {
                OpalIconBadge(systemName: "waveform.path.ecg.rectangle", tint: .accentColor, size: 40, iconSize: 18, cornerRadius: 12)
                VStack(alignment: .leading, spacing: 2) {
                    Text("反馈、需求与诊断")
                        .font(.title3.weight(.semibold))
                    Text("\(model.macDeviceSummary) · v\(model.appVersionString)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                StatusBadgeView(
                    text: model.isAuthenticated ? "投屏已连接" : "未连接投屏",
                    color: model.isAuthenticated ? .green : .orange
                )
            }

            HStack(spacing: OpalSpacing.sm) {
                metricTile(title: "悬浮小窗", value: "\(model.activePopoutCount)/\(PopoutWindowManager.maxConcurrentWindows)")
                metricTile(title: "本地日志", value: "\(model.logEntries.count) 条")
                metricTile(
                    title: "今日配额",
                    value: quota.remainingLabel.replacingOccurrences(of: "今日剩余 ", with: "")
                )
            }

            HStack(spacing: OpalSpacing.sm) {
                Button {
                    model.refreshFeedbackQuota()
                    if !model.feedbackQuota.isExhausted { activeModalKind = .bug }
                } label: {
                    Label(quota.isExhausted ? "今日上报已达上限" : "上报问题与日志", systemImage: "ant.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(quota.isExhausted)

                Button {
                    model.refreshFeedbackQuota()
                    if !model.feedbackQuota.isExhausted { activeModalKind = .feature }
                } label: {
                    Label("提功能需求", systemImage: "lightbulb.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .disabled(quota.isExhausted)
            }

            if quota.isExhausted {
                OpalNoticeBanner(tone: .error, message: FeedbackRateLimiter.limitReachedMessage)
            }

            HStack(spacing: OpalSpacing.md) {
                OpalIconBadge(systemName: "bubble.left.and.bubble.right.fill", tint: .accentColor, size: 34, iconSize: 14, cornerRadius: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text("交流反馈 QQ 群 · \(FeedbackHistoryLogic.qqGroupNumber)")
                        .font(.subheadline.weight(.semibold))
                    Text("问题解答、最新版本与需求改动都会在群内第一时间同步")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("复制群号") { model.copyQqGroupNumber() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            }
            .padding(.horizontal, OpalSpacing.md)
            .padding(.vertical, 10)
            .opalGlassSurface(.inset, cornerRadius: OpalRadius.card, tint: .accentColor)

            HStack(spacing: OpalSpacing.sm) {
                Button {
                    model.copyDiagnosticReport(warningsOnly: warningsOnly, query: searchQuery)
                } label: {
                    Label("复制报告", systemImage: "doc.on.doc").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                Button {
                    model.exportDiagnosticReport(warningsOnly: warningsOnly, query: searchQuery)
                } label: {
                    Label("导出日志文件", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                Button(role: .destructive) {
                    model.clearClientLogs()
                } label: {
                    Label("清空日志", systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
        .padding(OpalSpacing.xl)
        .opalGlassSurface(.panel)
    }

    private func metricTile(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.subheadline.weight(.semibold).monospacedDigit())
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    // MARK: - 2. Real-Time Log Stream

    private var logStreamSection: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(title: "实时日志流 (\(filteredEntries.count))", systemImage: "terminal") {
                Toggle("仅警告/错误", isOn: $warningsOnly)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                Button {
                    newestFirst.toggle()
                } label: {
                    Label(newestFirst ? "最新在前" : "时间正序", systemImage: "arrow.up.arrow.down")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }

            OpalSearchField(
                placeholder: "按关键字筛选（如 ADB / TLS / popout / video / feedback）",
                text: $searchQuery
            )

            VStack(alignment: .leading, spacing: 4) {
                if visibleEntries.isEmpty {
                    Text(
                        searchQuery.isEmpty && !warningsOnly
                            ? "暂无客户端日志，连接设备或打开悬浮窗后将在此实时记录。"
                            : "当前筛选条件下没有匹配的日志记录。"
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(OpalSpacing.md)
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 4) {
                            ForEach(visibleEntries) { entry in
                                Text(entry.formatLine())
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundStyle(color(for: entry.level))
                                    .textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                        .padding(OpalSpacing.md)
                    }
                    .frame(minHeight: 180, maxHeight: 300)

                    if !showAllLines && filteredEntries.count > Self.defaultDisplayLimit {
                        Button("展开全部 \(filteredEntries.count) 条日志") { showAllLines = true }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .padding([.horizontal, .bottom], 10)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.black.opacity(0.84), in: RoundedRectangle(cornerRadius: OpalRadius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: OpalRadius.card, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.14), lineWidth: 0.9)
            )
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel)
    }

    private func color(for level: OpalLogLevel) -> Color {
        switch level {
        case .error: return .red
        case .warn: return .orange
        case .info: return Color.white.opacity(0.88)
        }
    }
}

extension FeedbackKind: Identifiable {
    public var id: String { rawValue }
}
