import AppKit
import OpalDeskCore
import SwiftUI

/// In-app feedback history & resolution status tracker styled with unified Liquid Glass surfaces.
public struct FeedbackTrackingView: View {
    private static let defaultCollapsedCount = 3

    @Bindable public var model: AppModel
    @State private var syncing: Bool = false
    @State private var lookupOpen: Bool = false
    @State private var lookupCodeInput: String = ""
    @State private var lookingUp: Bool = false
    @State private var syncNotice: (message: String, isError: Bool)?
    @State private var showAllRecords: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    private var hasSyncableRecords: Bool {
        model.feedbackRecords.contains {
            !FeedbackHistoryLogic.normalizeEventId($0.eventId).isEmpty || $0.fromCodeLookup
        }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            HStack(alignment: .center, spacing: OpalSpacing.sm) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.feedbackRecords.isEmpty ? OpalL10n.text("feedback.title") : OpalL10n.format("feedback.title_count_fmt", model.feedbackRecords.count))
                        .font(.headline)
                    Text(statusSubtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button(lookupOpen ? OpalL10n.text("feedback.hide_lookup") : OpalL10n.text("feedback.lookup_btn")) {
                    lookupOpen.toggle()
                    syncNotice = nil
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: lookupOpen ? .prominent : .secondary, size: .small, isPill: true))

                Button(syncing ? OpalL10n.text("feedback.syncing") : OpalL10n.text("feedback.refresh_status")) { triggerRefresh() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                    .disabled(!model.isFeedbackStatusSyncConfigured || syncing || !hasSyncableRecords)
            }

            if lookupOpen {
                HStack(spacing: OpalSpacing.sm) {
                    TextField(OpalL10n.text("feedback.code_placeholder"), text: $lookupCodeInput)
                        .opalLiquidInput()
                        .onSubmit { triggerLookup() }
                    Button(lookingUp ? OpalL10n.text("feedback.looking_up") : OpalL10n.text("feedback.lookup_go")) { triggerLookup() }
                        .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .small, isPill: true))
                        .disabled(!model.isFeedbackStatusSyncConfigured || lookingUp || lookupCodeInput.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }

            if let notice = syncNotice {
                OpalNoticeBanner(
                    tone: notice.isError ? .error : .success,
                    message: notice.message,
                    onDismiss: { syncNotice = nil }
                )
            }

            if model.feedbackRecords.isEmpty {
                Text(OpalL10n.text("feedback.empty"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(OpalSpacing.md)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
            } else {
                let visible = showAllRecords || model.feedbackRecords.count <= Self.defaultCollapsedCount
                    ? model.feedbackRecords
                    : Array(model.feedbackRecords.prefix(Self.defaultCollapsedCount))
                VStack(spacing: OpalSpacing.sm) {
                    ForEach(visible) { rec in
                        recordCard(rec)
                    }
                }
                if model.feedbackRecords.count > Self.defaultCollapsedCount {
                    Button(showAllRecords ? OpalL10n.text("feedback.collapse") : OpalL10n.format("feedback.expand_fmt", model.feedbackRecords.count)) {
                        showAllRecords.toggle()
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel)
        .task {
            if model.isFeedbackStatusSyncConfigured {
                _ = await model.refreshFeedbackStatuses(force: false)
            }
        }
    }

    private var statusSubtitle: String {
        if !model.isFeedbackStatusSyncConfigured {
            return OpalL10n.text("feedback.sub.unconfigured")
        }
        if model.lastFeedbackSyncAtMs > 0 {
            return OpalL10n.format("feedback.sub.synced_fmt", Self.formatTime(model.lastFeedbackSyncAtMs))
        }
        return OpalL10n.text("feedback.sub.default")
    }

    private func recordCard(_ record: FeedbackRecord) -> some View {
        let relation = FeedbackStatusParser.evaluateVersionRelation(
            currentAppVersion: model.appVersionString,
            record: record
        )
        let badge = resolveBadge(record: record, relation: relation)
        let isFeature = record.kind == .feature

        return VStack(alignment: .leading, spacing: OpalSpacing.xs) {
            HStack(spacing: OpalSpacing.xs) {
                Button("#\(record.shortCode)") {
                    let pb = NSPasteboard.general
                    pb.clearContents()
                    let val = FeedbackHistoryLogic.normalizeEventId(record.eventId).isEmpty
                        ? record.shortCode
                        : "\(record.shortCode) (\(record.eventId))"
                    pb.setString(val, forType: .string)
                    model.showToast(OpalL10n.format("feedback.copied_code_fmt", record.shortCode))
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .mini, isPill: true))
                .font(.caption.monospaced().weight(.bold))

                StatusBadgeView(text: isFeature ? OpalL10n.text("feedback.kind.feature") : OpalL10n.text("feedback.kind.bug"), color: isFeature ? .cyan : .accentColor)

                let meta = (record.reportedVersion.isEmpty ? "" : "v\(record.reportedVersion) · ")
                    + Self.formatTime(record.createdAtMs)
                Text(meta)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Spacer()

                StatusBadgeView(text: badge.label, color: badge.color)
            }

            Text(record.summary)
                .font(.callout.weight(.medium))
                .lineLimit(2)

            if !record.replyNote.isEmpty {
                Text(OpalL10n.format("feedback.reply_fmt", record.replyNote))
                    .font(.caption)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .opalGlassSurface(.inset, cornerRadius: OpalRadius.control, tint: .accentColor)
            }

            HStack {
                Text(footerHint(record: record, relation: relation))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer()
                Button(OpalL10n.text("common.remove")) { model.removeFeedbackRecord(eventId: record.eventId) }
                    .buttonStyle(.borderless)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    private func triggerRefresh() {
        guard model.isFeedbackStatusSyncConfigured, !syncing else { return }
        syncing = true
        syncNotice = nil
        Task {
            let outcome = await model.refreshFeedbackStatuses(force: true)
            syncing = false
            if outcome.success {
                syncNotice = (outcome.updatedCount > 0 ? OpalL10n.format("feedback.refresh.updated_fmt", outcome.updatedCount) : OpalL10n.text("feedback.refresh.no_change"), false)
            } else {
                syncNotice = (outcome.message.isEmpty ? OpalL10n.text("feedback.refresh.failed") : outcome.message, true)
            }
        }
    }

    private func triggerLookup() {
        guard model.isFeedbackStatusSyncConfigured, !lookingUp else { return }
        let normalized = FeedbackHistoryLogic.normalizeShortCode(lookupCodeInput)
        guard !normalized.isEmpty else {
            syncNotice = (OpalL10n.text("feedback.lookup.invalid"), true)
            return
        }
        lookingUp = true
        syncNotice = nil
        Task {
            let outcome = await model.lookupFeedbackByCode(normalized)
            lookingUp = false
            if !outcome.success {
                syncNotice = (outcome.message, true)
            } else if outcome.found, let item = outcome.item {
                lookupCodeInput = ""
                lookupOpen = false
                let desc: String
                if item.status == .fixedInVersion && !item.fixedVersion.isEmpty {
                    desc = item.kind == .feature
                        ? OpalL10n.format("feedback.lookup.found_feature_ver_fmt", normalized, item.fixedVersion)
                        : OpalL10n.format("feedback.lookup.found_bug_ver_fmt", normalized, item.fixedVersion)
                } else {
                    desc = OpalL10n.format("feedback.lookup.found_status_fmt", normalized, item.status.displayLabel(for: item.kind))
                }
                syncNotice = (desc, false)
            } else {
                syncNotice = (outcome.message.isEmpty ? OpalL10n.format("feedback.lookup.not_found_fmt", normalized) : outcome.message, true)
            }
        }
    }

    private func resolveBadge(record: FeedbackRecord, relation: FeedbackVersionRelation) -> (label: String, color: Color) {
        let isFeature = record.kind == .feature
        switch record.status {
        case .open:
            return (isFeature ? OpalL10n.text("feedback.status.evaluating") : OpalL10n.text("feedback.status.investigating"), .orange)
        case .fixedPendingRelease:
            return (isFeature ? OpalL10n.text("feedback.status.planned") : OpalL10n.text("feedback.status.fixed_pending"), .cyan)
        case .ignored:
            return (isFeature ? OpalL10n.text("feedback.status.wont_do") : OpalL10n.text("feedback.status.archived"), .secondary)
        case .resolvedOther:
            return (isFeature ? OpalL10n.text("feedback.status.adopted") : OpalL10n.text("feedback.status.resolved"), .green)
        case .fixedInVersion:
            switch relation {
            case .fixedInCurrentOrOlder:
                return (isFeature ? OpalL10n.format("feedback.status.shipped_cur_fmt", record.fixedVersion) : OpalL10n.format("feedback.status.fixed_cur_fmt", record.fixedVersion), .green)
            case .fixedInNewerVersion:
                return (isFeature ? OpalL10n.format("feedback.status.shipped_new_fmt", record.fixedVersion) : OpalL10n.format("feedback.status.fixed_new_fmt", record.fixedVersion), .accentColor)
            default:
                if !record.fixedVersion.isEmpty {
                    return (isFeature ? OpalL10n.format("feedback.status.shipped_ver_fmt", record.fixedVersion) : OpalL10n.format("feedback.status.fixed_ver_fmt", record.fixedVersion), .green)
                }
                return (isFeature ? OpalL10n.text("feedback.status.shipped") : OpalL10n.text("feedback.status.fixed"), .green)
            }
        }
    }

    private func footerHint(record: FeedbackRecord, relation: FeedbackVersionRelation) -> String {
        if relation == .fixedInCurrentOrOlder && !record.fixedVersion.isEmpty {
            return record.kind == .feature
                ? OpalL10n.format("feedback.hint.cur_feature_fmt", record.fixedVersion)
                : OpalL10n.format("feedback.hint.cur_bug_fmt", record.fixedVersion)
        }
        if relation == .fixedInNewerVersion && !record.fixedVersion.isEmpty {
            return OpalL10n.format("feedback.hint.update_fmt", record.fixedVersion)
        }
        if record.lastCheckedAtMs > 0 {
            return OpalL10n.format("feedback.hint.checked_fmt", Self.formatTime(record.lastCheckedAtMs))
        }
        return OpalL10n.text("feedback.hint.refresh")
    }

    private static func formatTime(_ timestampMs: Int64) -> String {
        guard timestampMs > 0 else { return OpalL10n.text("feedback.unknown_time") }
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "MM-dd HH:mm"
        return df.string(from: Date(timeIntervalSince1970: Double(timestampMs) / 1000.0))
    }
}
