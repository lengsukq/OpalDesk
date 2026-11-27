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
                    Text(model.feedbackRecords.isEmpty ? "我的反馈追踪" : "我的反馈追踪 (\(model.feedbackRecords.count))")
                        .font(.headline)
                    Text(statusSubtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button(lookupOpen ? "收起查询" : "查反馈码") {
                    lookupOpen.toggle()
                    syncNotice = nil
                }
                .buttonStyle(.bordered)
                .tint(lookupOpen ? .accentColor : nil)
                .controlSize(.small)

                Button(syncing ? "同步中…" : "刷新状态") { triggerRefresh() }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .disabled(!model.isFeedbackStatusSyncConfigured || syncing || !hasSyncableRecords)
            }

            if lookupOpen {
                HStack(spacing: OpalSpacing.sm) {
                    TextField("输入 8 位反馈码（如 A1B2C3D4）", text: $lookupCodeInput)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { triggerLookup() }
                    Button(lookingUp ? "查询中…" : "查询") { triggerLookup() }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
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
                Text("暂无本地反馈记录。提交问题或功能建议后会自动在这里追踪处理进度、开发者回复与发布版本；重装或换机可用「查反馈码」找回。")
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
                    Button(showAllRecords ? "收起历史记录" : "展开全部 \(model.feedbackRecords.count) 条反馈") {
                        showAllRecords.toggle()
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
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
            return "当前构建未配置状态查询服务，仅保存本地提交记录"
        }
        if model.lastFeedbackSyncAtMs > 0 {
            return "最近同步：\(Self.formatTime(model.lastFeedbackSyncAtMs))"
        }
        return "可查看问题是否修复、功能建议进度与开发者回复"
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
                    model.showToast("已复制反馈码 #\(record.shortCode)")
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
                .font(.caption.monospaced().weight(.bold))

                StatusBadgeView(text: isFeature ? "需求" : "问题", color: isFeature ? .cyan : .accentColor)

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
                Text("开发者回复：\(record.replyNote)")
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
                Button("移除") { model.removeFeedbackRecord(eventId: record.eventId) }
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
                syncNotice = (outcome.updatedCount > 0 ? "已同步 \(outcome.updatedCount) 条反馈最新进度" : "暂未查询到状态变更", false)
            } else {
                syncNotice = (outcome.message.isEmpty ? "刷新失败，请稍后再试" : outcome.message, true)
            }
        }
    }

    private func triggerLookup() {
        guard model.isFeedbackStatusSyncConfigured, !lookingUp else { return }
        let normalized = FeedbackHistoryLogic.normalizeShortCode(lookupCodeInput)
        guard !normalized.isEmpty else {
            syncNotice = ("请输入有效的 8 位反馈码（如 A1B2C3D4）", true)
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
                        ? "已查到 #\(normalized)：已在 v\(item.fixedVersion) 实现"
                        : "已查到 #\(normalized)：已在 v\(item.fixedVersion) 修复"
                } else {
                    desc = "已查到 #\(normalized)：\(item.status.displayLabel(for: item.kind))"
                }
                syncNotice = (desc, false)
            } else {
                syncNotice = (outcome.message.isEmpty ? "未查到 #\(normalized) 对应的记录" : outcome.message, true)
            }
        }
    }

    private func resolveBadge(record: FeedbackRecord, relation: FeedbackVersionRelation) -> (label: String, color: Color) {
        let isFeature = record.kind == .feature
        switch record.status {
        case .open:
            return (isFeature ? "评估中" : "排查中", .orange)
        case .fixedPendingRelease:
            return (isFeature ? "已规划 · 待下版" : "已修复 · 待下版", .cyan)
        case .ignored:
            return (isFeature ? "暂不考虑" : "已归档", .secondary)
        case .resolvedOther:
            return (isFeature ? "已采纳" : "已处理", .green)
        case .fixedInVersion:
            switch relation {
            case .fixedInCurrentOrOlder:
                return (isFeature ? "当前版已上线 (v\(record.fixedVersion))" : "当前版已修复 (v\(record.fixedVersion))", .green)
            case .fixedInNewerVersion:
                return (isFeature ? "v\(record.fixedVersion) 已实现 · 可更新" : "v\(record.fixedVersion) 已修复 · 可更新", .accentColor)
            default:
                if !record.fixedVersion.isEmpty {
                    return (isFeature ? "已在 v\(record.fixedVersion) 实现" : "已在 v\(record.fixedVersion) 修复", .green)
                }
                return (isFeature ? "已实现" : "已修复", .green)
            }
        }
    }

    private func footerHint(record: FeedbackRecord, relation: FeedbackVersionRelation) -> String {
        if relation == .fixedInCurrentOrOlder && !record.fixedVersion.isEmpty {
            return record.kind == .feature
                ? "当前运行版本已包含 v\(record.fixedVersion) 的功能更新"
                : "当前运行版本已包含 v\(record.fixedVersion) 的修复"
        }
        if relation == .fixedInNewerVersion && !record.fixedVersion.isEmpty {
            return "请前往 GitHub Releases 更新至 v\(record.fixedVersion)"
        }
        if record.lastCheckedAtMs > 0 {
            return "核验于 \(Self.formatTime(record.lastCheckedAtMs))"
        }
        return "点击右上角「刷新状态」获取最新进度"
    }

    private static func formatTime(_ timestampMs: Int64) -> String {
        guard timestampMs > 0 else { return "未知时间" }
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "MM-dd HH:mm"
        return df.string(from: Date(timeIntervalSince1970: Double(timestampMs) / 1000.0))
    }
}
