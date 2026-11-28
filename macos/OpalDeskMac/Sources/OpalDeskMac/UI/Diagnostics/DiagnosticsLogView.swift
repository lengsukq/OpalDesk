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
                    Text(OpalL10n.text("diag.title"))
                        .font(.title3.weight(.semibold))
                    Text("\(model.macDeviceSummary) · v\(model.appVersionString)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                StatusBadgeView(
                    text: model.isAuthenticated ? OpalL10n.text("diag.badge.connected") : OpalL10n.text("diag.badge.disconnected"),
                    color: model.isAuthenticated ? .green : .orange
                )
            }

            HStack(spacing: OpalSpacing.sm) {
                metricTile(title: OpalL10n.text("diag.metric.popouts"), value: "\(model.activePopoutCount)/\(PopoutWindowManager.maxConcurrentWindows)")
                metricTile(title: OpalL10n.text("diag.metric.logs"), value: OpalL10n.format("diag.metric.logs_fmt", model.logEntries.count))
                metricTile(
                    title: OpalL10n.text("diag.metric.quota"),
                    value: quota.remainingLabel.replacingOccurrences(of: "今日剩余 ", with: "")
                )
            }

            HStack(spacing: OpalSpacing.sm) {
                Button {
                    model.refreshFeedbackQuota()
                    if !model.feedbackQuota.isExhausted { activeModalKind = .bug }
                } label: {
                    Label(quota.isExhausted ? OpalL10n.text("diag.btn.quota_reached") : OpalL10n.text("diag.btn.report_bug"), systemImage: "ant.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .large, isPill: true))
                .disabled(quota.isExhausted)

                Button {
                    model.refreshFeedbackQuota()
                    if !model.feedbackQuota.isExhausted { activeModalKind = .feature }
                } label: {
                    Label(OpalL10n.text("diag.btn.request_feature"), systemImage: "lightbulb.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .large, isPill: true))
                .disabled(quota.isExhausted)
            }

            if quota.isExhausted {
                OpalNoticeBanner(tone: .error, message: FeedbackRateLimiter.limitReachedMessage)
            }

            HStack(spacing: OpalSpacing.md) {
                OpalIconBadge(systemName: "bubble.left.and.bubble.right.fill", tint: .accentColor, size: 34, iconSize: 14, cornerRadius: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(OpalL10n.format("diag.qq.title_fmt", FeedbackHistoryLogic.qqGroupNumber))
                        .font(.subheadline.weight(.semibold))
                    Text(OpalL10n.text("diag.qq.subtitle"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button(OpalL10n.text("diag.qq.copy")) { model.copyQqGroupNumber() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .small, isPill: true))
            }
            .padding(.horizontal, OpalSpacing.md)
            .padding(.vertical, 10)
            .opalGlassSurface(.inset, cornerRadius: OpalRadius.card, tint: .accentColor)

            HStack(spacing: OpalSpacing.sm) {
                Button {
                    model.copyDiagnosticReport(warningsOnly: warningsOnly, query: searchQuery)
                } label: {
                    Label(OpalL10n.text("diag.btn.copy_report"), systemImage: "doc.on.doc").frame(maxWidth: .infinity)
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small))

                Button {
                    model.exportDiagnosticReport(warningsOnly: warningsOnly, query: searchQuery)
                } label: {
                    Label(OpalL10n.text("diag.btn.export_logs"), systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small))

                Button(role: .destructive) {
                    model.clearClientLogs()
                } label: {
                    Label(OpalL10n.text("diag.btn.clear_logs"), systemImage: "trash")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .destructive, size: .small))
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
            OpalSectionHeader(title: OpalL10n.format("diag.stream.title_fmt", filteredEntries.count), systemImage: "terminal") {
                Toggle(OpalL10n.text("diag.stream.warnings_only"), isOn: $warningsOnly)
                    .toggleStyle(.switch)
                    .controlSize(.mini)
                    .font(.caption)
                Button {
                    newestFirst.toggle()
                } label: {
                    Label(newestFirst ? OpalL10n.text("diag.stream.newest_first") : OpalL10n.text("diag.stream.oldest_first"), systemImage: "arrow.up.arrow.down")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
            }

            OpalSearchField(
                placeholder: OpalL10n.text("diag.stream.search"),
                text: $searchQuery
            )

            VStack(alignment: .leading, spacing: 4) {
                if visibleEntries.isEmpty {
                    Text(
                        searchQuery.isEmpty && !warningsOnly
                            ? OpalL10n.text("diag.stream.empty")
                            : OpalL10n.text("diag.stream.empty_filtered")
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
                        Button(OpalL10n.format("diag.stream.expand_fmt", filteredEntries.count)) { showAllLines = true }
                            .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
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
