import AppKit
import OpalDeskCore
import SwiftUI

/// Liquid Glass manual issue, diagnostic log & feature request modal.
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
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSheetHeader(
                icon: isFeature ? "lightbulb.fill" : "ant.fill",
                iconTint: isFeature ? .cyan : .accentColor,
                title: isFeature ? OpalL10n.text("report.title.feature") : OpalL10n.text("report.title.bug"),
                subtitle: OpalL10n.text("report.subtitle"),
                badgeText: model.feedbackQuota.remainingLabel,
                badgeColor: model.feedbackQuota.isExhausted ? .red : .cyan
            )

            OpalSegmentedControl(
                selection: $selectedKind,
                options: [
                    OpalSegmentOption(value: FeedbackKind.bug, label: OpalL10n.text("report.kind.bug"), icon: "ant.fill"),
                    OpalSegmentOption(value: FeedbackKind.feature, label: OpalL10n.text("report.kind.feature"), icon: "lightbulb.fill"),
                ]
            )
            .onChange(of: selectedKind) { _, next in
                includeLogs = (next == .bug)
                validationError = ""
            }

            if model.feedbackQuota.isExhausted {
                OpalNoticeBanner(tone: .error, message: FeedbackRateLimiter.limitReachedMessage)
            } else if !model.isSentryConfigured {
                OpalNoticeBanner(
                    tone: .warning,
                    message: OpalL10n.text("report.no_dsn")
                )
            }

            VStack(alignment: .leading, spacing: OpalSpacing.xs) {
                Text(OpalL10n.text("report.name.label")).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                TextField(OpalL10n.text("report.name.placeholder"), text: $reporterName)
                    .opalLiquidInput()
                    .onChange(of: reporterName) { _, newVal in
                        if newVal.count > 64 { reporterName = String(newVal.prefix(64)) }
                        if !validationError.isEmpty { validationError = "" }
                    }
            }

            VStack(alignment: .leading, spacing: OpalSpacing.xs) {
                Text(isFeature ? OpalL10n.text("report.desc.feature") : OpalL10n.text("report.desc.bug")).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                ZStack(alignment: .topLeading) {
                    TextEditor(text: $problemDescription)
                        .font(.body)
                        .frame(minHeight: 96, maxHeight: 140)
                        .scrollContentBackground(.hidden)
                        .padding(8)
                        .background(
                            Color(nsColor: .textBackgroundColor),
                            in: RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous)
                                .strokeBorder(Color.primary.opacity(0.14), lineWidth: 0.9)
                        )
                        .onChange(of: problemDescription) { _, newVal in
                            if newVal.count > 2000 { problemDescription = String(newVal.prefix(2000)) }
                            if !validationError.isEmpty { validationError = "" }
                        }
                    if problemDescription.isEmpty {
                        Text(isFeature ? OpalL10n.text("report.desc.feature_placeholder") : OpalL10n.text("report.desc.bug_placeholder"))
                            .font(.body)
                            .foregroundStyle(.tertiary)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 12)
                            .allowsHitTesting(false)
                    }
                }
            }

            Toggle(isOn: $includeLogs) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(OpalL10n.text("report.logs.toggle")).font(.subheadline.weight(.medium))
                    Text(includeLogs ? OpalL10n.text("report.logs.on_sub") : OpalL10n.text("report.logs.off_sub"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            metadataSummaryCard

            if !validationError.isEmpty {
                OpalNoticeBanner(tone: .warning, message: validationError)
            }

            if let outcome = lastOutcome {
                outcomeView(outcome)
            }

            HStack {
                Button(lastOutcome?.success == true ? OpalL10n.text("common.done") : OpalL10n.text("common.cancel"), action: onDismiss)
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))
                    .keyboardShortcut(.cancelAction)
                    .disabled(submitting)

                Spacer()

                if submitting {
                    ProgressView().controlSize(.small).padding(.trailing, 4)
                }

                Button(submitButtonTitle) { submit() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
                    .environment(\.opalAccent, isFeature ? OpalFluidPalette.brandCyan : OpalFluidPalette.brandBlue)
                    .disabled(!submitAllowed)
            }
        }
        .padding(OpalSpacing.xxl)
        .opalModalSheetChrome(width: 500)
    }

    private var metadataSummaryCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(includeLogs ? OpalL10n.text("report.meta.with_logs") : OpalL10n.text("report.meta.basic"))
                .font(.caption.weight(.semibold))
            Text(OpalL10n.format("report.meta.system_fmt", model.macDeviceSummary))
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(
                includeLogs
                    ? OpalL10n.format("report.meta.ver_logs_fmt", model.appVersionString, min(model.logEntries.count, DiagnosticSanitizer.defaultMaxLogEntries))
                    : OpalL10n.format("report.meta.ver_basic_fmt", model.appVersionString)
            )
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(OpalSpacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    @ViewBuilder
    private func outcomeView(_ outcome: MacManualReportOutcome) -> some View {
        if outcome.success {
            VStack(alignment: .leading, spacing: OpalSpacing.sm) {
                Text(
                    lastSubmittedKind == .feature
                        ? OpalL10n.text("report.success.feature")
                        : OpalL10n.text("report.success.bug")
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(.green)

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(OpalL10n.format("report.code_fmt", outcome.shortCode))
                            .font(.headline.monospaced())
                        Text("Event ID: \(outcome.eventId)")
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(OpalL10n.text("report.copy_code")) {
                        let pb = NSPasteboard.general
                        pb.clearContents()
                        pb.setString("\(outcome.shortCode) (\(outcome.eventId))", forType: .string)
                        model.showToast(OpalL10n.format("feedback.copied_code_fmt", outcome.shortCode))
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                }
            }
            .padding(OpalSpacing.md)
            .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: .green)
        } else if !outcome.errorMessage.isEmpty {
            OpalNoticeBanner(tone: .error, message: outcome.errorMessage)
        }
    }

    private var submitButtonTitle: String {
        if submitting { return OpalL10n.text("report.btn.submitting") }
        if alreadySubmittedSameReport { return OpalL10n.text("report.btn.submitted") }
        if model.feedbackQuota.isExhausted { return OpalL10n.text("diag.btn.quota_reached") }
        return isFeature ? OpalL10n.text("report.btn.submit_feature") : OpalL10n.text("report.btn.submit_bug")
    }

    private func submit() {
        let cleanUser = reporterName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanUser.isEmpty else {
            validationError = OpalL10n.text("report.err.no_name")
            return
        }
        let cleanDesc = problemDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanDesc.isEmpty else {
            validationError = isFeature ? OpalL10n.text("report.err.no_desc_feature") : OpalL10n.text("report.err.no_desc_bug")
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
}
