import AppKit
import OpalDeskCore
import SwiftUI

/// Liquid Glass manual issue, diagnostic log & feature request modal aligned with Android `ReportIssueModal`.
public struct ReportIssueSheet: View {
    @Bindable public var model: AppModel
    public let initialKind: FeedbackKind
    public let onDismiss: () -> Void

    @State private var selectedKind: FeedbackKind
    @State private var includeLogs: Bool
    @State private var reporterName: String
    @State private var problemDescription: String = ""
    @State private var submitting: Bool = false
    @State private var validationError: String = ""
    @State private var lastOutcome: MacManualReportOutcome?
    @State private var lastSubmittedDescription: String?
    @State private var lastSubmittedKind: FeedbackKind?

    public init(model: AppModel, initialKind: FeedbackKind = .bug, onDismiss: @escaping () -> Void) {
        self.model = model
        self.initialKind = initialKind
        self.onDismiss = onDismiss
        _selectedKind = State(initialValue: initialKind)
        _includeLogs = State(initialValue: initialKind == .bug)
        _reporterName = State(initialValue: model.savedFeedbackReporterName)
    }

    private var isFeature: Bool { selectedKind == .feature }

    private var submitAllowed: Bool {
        ManualReportSubmitGuard.isSubmitAllowed(
            submitting: submitting,
            quotaExhausted: model.feedbackQuota.isExhausted,
            sentryConfigured: model.isSentryConfigured,
            lastUploadSucceeded: lastOutcome?.success == true,
            currentDescription: problemDescription,
            lastSubmittedDescription: lastSubmittedDescription,
            currentKind: selectedKind,
            lastSubmittedKind: lastSubmittedKind
        )
    }

    private var alreadySubmittedSameReport: Bool {
        lastOutcome?.success == true
            && lastSubmittedDescription != nil
            && problemDescription.trimmingCharacters(in: .whitespacesAndNewlines) == lastSubmittedDescription
            && lastSubmittedKind == selectedKind
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(isFeature ? "提交功能建议" : "上报日志与问题")
                    .font(.title3.weight(.bold))
                Spacer()
                StatusBadgeView(
                    text: model.feedbackQuota.remainingLabel,
                    color: model.feedbackQuota.isExhausted ? .red : .cyan
                )
            }

            Picker("反馈类型", selection: $selectedKind) {
                Text("问题反馈").tag(FeedbackKind.bug)
                Text("功能建议").tag(FeedbackKind.feature)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .onChange(of: selectedKind) { _, next in
                includeLogs = (next == .bug)
                validationError = ""
            }

            if model.feedbackQuota.isExhausted {
                noticeBox(FeedbackRateLimiter.limitReachedMessage, tint: .red)
            } else if !model.isSentryConfigured {
                noticeBox("当前本地开发构建未注入 OPALDESK_SENTRY_DSN，打包正式版或配置环境变量后即可上报。", tint: .orange)
            }

            VStack(alignment: .leading, spacing: 5) {
                Text("用户名 / 称呼").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                TextField("例如：张三 / GitHub ID（便于定位联系）", text: $reporterName)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: reporterName) { _, newVal in
                        if newVal.count > 64 { reporterName = String(newVal.prefix(64)) }
                        if !validationError.isEmpty { validationError = "" }
                    }
            }

            VStack(alignment: .leading, spacing: 5) {
                Text(isFeature ? "需求描述" : "问题描述").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                ZStack(alignment: .topLeading) {
                    TextEditor(text: $problemDescription)
                        .font(.body)
                        .frame(minHeight: 96, maxHeight: 140)
                        .scrollContentBackground(.hidden)
                        .padding(6)
                        .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Color.secondary.opacity(0.28), lineWidth: 1))
                        .onChange(of: problemDescription) { _, newVal in
                            if newVal.count > 2000 { problemDescription = String(newVal.prefix(2000)) }
                            if !validationError.isEmpty { validationError = "" }
                        }
                    if problemDescription.isEmpty {
                        Text(isFeature ? "请描述希望新增的功能、适用场景或交互建议…" : "请描述遇到的问题、触发操作或报错现象…")
                            .font(.body)
                            .foregroundStyle(.tertiary)
                            .padding(.horizontal, 11)
                            .padding(.vertical, 10)
                            .allowsHitTesting(false)
                    }
                }
            }

            Toggle(isOn: $includeLogs) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("附带客户端运行日志").font(.subheadline.weight(.medium))
                    Text(includeLogs ? "包含已自动脱敏路径/Token 的 GZIP 日志（不含截图）" : "仅附带版本号与系统信息，不上传也不清空本地日志")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            // Metadata summary
            VStack(alignment: .leading, spacing: 4) {
                Text(includeLogs ? "本次随附诊断信息（不含屏幕截图）" : "本次随附基础信息（不含运行日志与截图）")
                    .font(.caption.weight(.semibold))
                Text("系统：\(model.macDeviceSummary)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(
                    includeLogs
                        ? "版本：v\(model.appVersionString) · 日志：最近 \(min(model.logEntries.count, DiagnosticSanitizer.defaultMaxLogEntries)) 条（自动脱敏并 GZIP 压缩）"
                        : "版本：v\(model.appVersionString) · 类型：功能建议（不会清空当前本地日志）"
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            .padding(11)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.secondary.opacity(0.09), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            if !validationError.isEmpty {
                noticeBox(validationError, tint: .orange)
            }

            if let outcome = lastOutcome {
                if outcome.success {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(
                            lastSubmittedKind == .feature
                                ? "提交成功！已保存至「日志与反馈 → 我的反馈追踪」，后续可查看评估进度、开发者回复与上线版本："
                                : "上传成功！已保存至「日志与反馈 → 我的反馈追踪」，后续可查看修复进度与对应版本："
                        )
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.green)

                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("反馈码：#\(outcome.shortCode)")
                                    .font(.headline.monospaced())
                                Text("Event ID: \(outcome.eventId)")
                                    .font(.caption2.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("复制反馈码") {
                                let pb = NSPasteboard.general
                                pb.clearContents()
                                pb.setString("\(outcome.shortCode) (\(outcome.eventId))", forType: .string)
                                model.showToast("已复制反馈码 #\(outcome.shortCode)")
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                    .padding(12)
                    .background(Color.green.opacity(0.11), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.green.opacity(0.38), lineWidth: 1))
                } else if !outcome.errorMessage.isEmpty {
                    noticeBox(outcome.errorMessage, tint: .red)
                }
            }

            HStack {
                Button(lastOutcome?.success == true ? "完成" : "取消", action: onDismiss)
                    .keyboardShortcut(.cancelAction)
                    .disabled(submitting)

                Spacer()

                if submitting {
                    ProgressView().controlSize(.small).padding(.trailing, 4)
                }

                Button(submitButtonTitle) {
                    submit()
                }
                .buttonStyle(.borderedProminent)
                .tint(isFeature ? .cyan : .accentColor)
                .disabled(!submitAllowed)
            }
        }
        .padding(22)
        .frame(width: 480)
    }

    private var submitButtonTitle: String {
        if submitting { return "提交中…" }
        if alreadySubmittedSameReport { return "已提交" }
        if model.feedbackQuota.isExhausted { return "今日已达上限" }
        return isFeature ? "确认提交需求" : "确认上传"
    }

    private func submit() {
        let cleanUser = reporterName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanUser.isEmpty else {
            validationError = "请先填写你的用户名或称呼，便于后续定位回复"
            return
        }
        let cleanDesc = problemDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanDesc.isEmpty else {
            validationError = isFeature ? "请简单描述希望新增的功能或使用场景" : "请简单描述遇到的问题或复现步骤"
            return
        }
        validationError = ""
        submitting = true
        lastOutcome = nil
        let submitKind = selectedKind
        let submitLogs = includeLogs
        Task {
            let outcome = await model.submitManualFeedback(
                reporterName: cleanUser,
                problemDescription: cleanDesc,
                kind: submitKind,
                includeLogs: submitLogs
            )
            submitting = false
            lastOutcome = outcome
            if outcome.success {
                lastSubmittedDescription = cleanDesc
                lastSubmittedKind = submitKind
            }
        }
    }

    private func noticeBox(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.caption.weight(.medium))
            .foregroundStyle(tint)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tint.opacity(0.11), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(tint.opacity(0.35), lineWidth: 0.9))
    }
}
