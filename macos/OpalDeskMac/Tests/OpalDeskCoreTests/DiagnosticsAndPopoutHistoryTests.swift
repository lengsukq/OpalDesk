import Foundation
import Testing
@testable import OpalDeskCore

@Suite("Diagnostics Logs, Feedback Transport & Popout History")
struct DiagnosticsAndPopoutHistoryTests {

    private final class InMemoryQuotaStorage: FeedbackQuotaStorage, @unchecked Sendable {
        var installId = "mac-testinstallation01"
        var lastDate = ""
        var count = 0

        func getOrCreateInstallationId() -> String { installId }
        func getLastReportDate() -> String { lastDate }
        func getDailyReportCount() -> Int { count }
        func saveDailyReportState(dateYyyyMmDd: String, count: Int) {
            self.lastDate = dateYyyyMmDd
            self.count = count
        }
    }

    @Test("DiagnosticSanitizer redacts paths, secrets, LAN IPs and compresses valid GZIP")
    func sanitizerRedactsAndCompresses() {
        let raw = """
        User path /Users/alice/Documents/file.txt and C:\\Users\\bob\\Desktop\\a.log
        Authorization: Bearer secret_token_123456 auth_token=mysecret99
        adb pair 192.168.31.88:37123 654321
        Connected to 10.0.0.15 and 172.16.5.9
        """
        let sanitized = DiagnosticSanitizer.sanitizeText(raw)
        #expect(!sanitized.contains("alice"))
        #expect(!sanitized.contains("bob"))
        #expect(!sanitized.contains("secret_token_123456"))
        #expect(!sanitized.contains("mysecret99"))
        #expect(!sanitized.contains("654321"))
        #expect(!sanitized.contains("192.168.31.88"))
        #expect(!sanitized.contains("10.0.0.15"))
        #expect(!sanitized.contains("172.16.5.9"))
        #expect(sanitized.contains("/Users/<redacted>"))
        #expect(sanitized.contains("192.168.*.*"))

        let gzipped = DiagnosticSanitizer.gzipCompress(String(repeating: "OpalDesk log line\n", count: 80))
        #expect(gzipped.count > 10)
        #expect(gzipped[0] == 0x1F)
        #expect(gzipped[1] == 0x8B)
    }

    @Test("OpalLogRingStore caps capacity and OpalLogFilter filters by severity and query")
    func logRingAndFilter() {
        let ring = OpalLogRingStore(maxEntries: 20)
        for i in 0..<30 {
            let level: OpalLogLevel = i % 3 == 0 ? .error : (i % 2 == 0 ? .warn : .info)
            ring.append(level: level, tag: "Session", message: "Event \(i)", timestampMs: Int64(1_700_000_000_000 + i))
        }
        let all = ring.snapshot()
        #expect(all.count == 20)
        #expect(all.first?.message == "Event 10")

        let warnOnly = OpalLogFilter.filterEntries(all, warningsAndErrorsOnly: true, query: "")
        #expect(warnOnly.allSatisfy { $0.level != .info })

        let queryHit = OpalLogFilter.filterEntries(all, warningsAndErrorsOnly: false, query: "event 29")
        #expect(queryHit.count == 1)
        #expect(queryHit[0].message == "Event 29")

        let report = OpalLogFilter.buildDiagnosticReport(
            appVersion: "1.0.18",
            deviceSummary: "MacBookPro",
            context: MacDiagnosticContext(connectionSummary: "已连接", popoutSummary: "2/3"),
            entries: queryHit,
            generatedAtText: "2026-11-26 19:00:00"
        )
        #expect(report.contains("v1.0.18"))
        #expect(report.contains("2/3"))
        #expect(report.contains("Event 29"))
    }

    @Test("OpalLogStore persists entries to disk and reloads tail")
    func logStorePersistsToDisk() {
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk_log_test_\(UUID().uuidString).log")
        defer { try? FileManager.default.removeItem(at: tempURL) }

        let store1 = OpalLogStore(fileURL: tempURL, maxEntries: 50)
        store1.append(level: .info, tag: "App", message: "Boot ok")
        store1.append(level: .warn, tag: "TLS", message: "Retry handshake")
        store1.flushForTests()

        let store2 = OpalLogStore(fileURL: tempURL, maxEntries: 50)
        let loaded = store2.snapshot()
        #expect(loaded.count == 2)
        #expect(loaded[0].tag == "App")
        #expect(loaded[1].level == .warn)
        #expect(loaded[1].message == "Retry handshake")
    }

    @Test("FeedbackRateLimiter enforces 5/day local natural day cap and resets next day")
    func rateLimiterAndSubmitGuard() {
        let storage = InMemoryQuotaStorage()
        let dayBox = NSLock()
        nonisolated(unsafe) var currentDay = "2026-11-26"
        let limiter = FeedbackRateLimiter(storage: storage, maxDaily: 5) {
            dayBox.withLock { currentDay }
        }

        #expect(limiter.currentQuota().remaining == 5)
        for step in 1...5 {
            let snap = limiter.recordConfirmedUpload()
            #expect(snap.usedToday == step)
            #expect(snap.remaining == 5 - step)
        }
        #expect(limiter.currentQuota().isExhausted)

        dayBox.withLock { currentDay = "2026-11-27" }
        #expect(limiter.currentQuota().remaining == 5)
        #expect(!limiter.currentQuota().isExhausted)

        #expect(!ManualReportSubmitGuard.isSubmitAllowed(
            submitting: false,
            quotaExhausted: false,
            sentryConfigured: true,
            lastUploadSucceeded: true,
            currentDescription: "支持置顶",
            lastSubmittedDescription: "支持置顶",
            currentKind: .feature,
            lastSubmittedKind: .feature
        ))
        #expect(ManualReportSubmitGuard.isSubmitAllowed(
            submitting: false,
            quotaExhausted: false,
            sentryConfigured: true,
            lastUploadSucceeded: true,
            currentDescription: "支持置顶",
            lastSubmittedDescription: "支持置顶",
            currentKind: .bug,
            lastSubmittedKind: .feature
        ))
    }

    @Test("SentryEnvelopeTransport parses DSN and builds worker-compatible BUG and FEATURE envelopes")
    func sentryEnvelopeFormat() throws {
        let dsn = "https://pub12345@o999.ingest.us.sentry.io/4501234"
        let parsed = try #require(SentryEnvelopeTransport.parseDsn(dsn))
        #expect(parsed.publicKey == "pub12345")
        #expect(parsed.projectId == "4501234")
        #expect(parsed.envelopeEndpointUrl == "https://o999.ingest.us.sentry.io/api/4501234/envelope/")

        let evId = "0123456789abcdef0123456789abcdef"
        let featureData = SentryEnvelopeTransport.buildEnvelopeBytes(
            dsn: dsn,
            payload: ManualReportPayload(
                eventId: evId,
                installationId: "mac-1234567890abcdef",
                reporterName: "Leo",
                problemDescription: "增加历史 APP 小窗栏",
                appVersion: "1.0.18",
                osVersion: "15.1",
                deviceModel: "Mac16,1",
                connectionActive: false,
                clientLogCount: 0,
                attachmentFilename: "",
                compressedLogBytes: Data(),
                sentAtIso: "2026-11-26T11:00:00Z",
                kind: .feature,
                includeLogs: false
            )
        )

        let text = try #require(String(data: featureData, encoding: .utf8))
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).filter { !$0.isEmpty }
        #expect(lines.count == 3)

        let eventJson = try #require(
            try JSONSerialization.jsonObject(with: Data(lines[2].utf8)) as? [String: Any]
        )
        #expect(eventJson["level"] as? String == "info")
        let tags = try #require(eventJson["tags"] as? [String: String])
        #expect(tags["report_source"] == "manual_user_report")
        #expect(tags["feedback_kind"] == "feature")
        #expect(tags["feedback_code"] == "01234567")
        #expect(tags["client_platform"] == "macos")
        let fp = try #require(eventJson["fingerprint"] as? [String])
        #expect(fp == ["opaldesk-feature", evId])
        let msg = try #require(eventJson["message"] as? [String: String])
        #expect(msg["formatted"]?.hasPrefix("[Feature Request] ") == true)

        // Bug envelope with GZIP log attachment includes attachment header item
        let gzip = DiagnosticSanitizer.gzipCompress("sample log line")
        let bugData = SentryEnvelopeTransport.buildEnvelopeBytes(
            dsn: dsn,
            payload: ManualReportPayload(
                eventId: evId,
                installationId: "mac-1234567890abcdef",
                reporterName: "Leo",
                problemDescription: "画面卡顿",
                appVersion: "1.0.18",
                osVersion: "15.1",
                deviceModel: "Mac16,1",
                connectionActive: true,
                clientLogCount: 12,
                attachmentFilename: "opaldesk_macos_diag.txt.gz",
                compressedLogBytes: gzip,
                sentAtIso: "2026-11-26T11:00:00Z",
                kind: .bug,
                includeLogs: true
            )
        )
        #expect(bugData.range(of: Data("\"type\":\"attachment\"".utf8)) != nil)
        #expect(bugData.range(of: gzip) != nil)
    }

    @Test("FeedbackStatusParser parses worker JSON and compares semver versions")
    func feedbackStatusParsingAndMerge() throws {
        #expect(FeedbackStatusParser.compareVersions("1.0.19", "1.0.18") > 0)
        #expect(FeedbackStatusParser.extractSemver("io.github.lengsukq.opaldesk.mac@1.2.0+12") == "1.2.0")
        #expect(
            FeedbackHistoryLogic.resolveStatusBaseUrl(
                feedbackStatusUrl: "",
                sentryTunnelUrl: "https://feedback.opaldesk.dev/envelope"
            ) == "https://feedback.opaldesk.dev"
        )

        let evId = "abcdef0123456789abcdef0123456789"
        let batchJson = """
        {
          "schema": 1,
          "generatedAt": "2026-11-26T12:00:00Z",
          "items": {
            "\(evId)": {
              "eventId": "\(evId)",
              "shortCode": "ABCDEF01",
              "status": "fixed_in_version",
              "fixedVersion": "1.0.19",
              "replyNote": "已在新版本支持悬浮窗置顶",
              "summary": "支持置顶",
              "kind": "feature"
            }
          }
        }
        """
        let parsed = try #require(FeedbackStatusParser.parseBatchStatusJson(Data(batchJson.utf8)))
        let item = try #require(parsed.items[evId])
        #expect(item.kind == .feature)
        #expect(item.status == .fixedInVersion)
        #expect(item.fixedVersion == "1.0.19")

        let initial = FeedbackHistoryLogic.recordSubmitted(
            existing: [],
            eventId: evId,
            shortCode: "ABCDEF01",
            problemDescription: "支持置顶",
            reportedVersion: "1.0.18",
            createdAtMs: 1000,
            kind: .feature
        )
        let merged = FeedbackHistoryLogic.mergeRemoteStatuses(
            existing: initial,
            remoteItems: parsed.items,
            checkedAtMs: 2000
        )
        #expect(merged.count == 1)
        #expect(merged[0].replyNote == "已在新版本支持悬浮窗置顶")
        #expect(FeedbackStatusParser.evaluateVersionRelation(currentAppVersion: "1.0.18", record: merged[0]) == .fixedInNewerVersion)
    }

    @Test("PopoutHistoryStore remembers launch counts, pin preference, and caps at 16 entries")
    func popoutHistoryPersistenceAndPinMemory() {
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk_popout_hist_\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: tempURL) }

        #expect(PopoutHistoryLogic.maxConcurrentFloatingWindows == 3)
        let store = PopoutHistoryStore(storageURL: tempURL)

        let e1 = PopoutHistoryEntry(
            appPackage: "com.tencent.mm",
            appLabel: "微信",
            deviceKey: "usb:SER01",
            deviceName: "Xiaomi 15",
            transportKind: "usb",
            adbSerial: "SER01",
            lastOpenedAtMs: 1000
        )
        store.recordLaunch(e1)
        store.updatePinPreference(appPackage: "com.tencent.mm", deviceKey: "usb:SER01", pinnedOnTop: true)

        // Re-opening increments count and preserves remembered pin state
        let e1Again = PopoutHistoryEntry(
            appPackage: "com.tencent.mm",
            appLabel: "微信",
            deviceKey: "usb:SER01",
            deviceName: "Xiaomi 15",
            transportKind: "usb",
            lastOpenedAtMs: 2000,
            pinnedOnTop: false
        )
        let updated = store.recordLaunch(e1Again)
        #expect(updated.count == 1)
        #expect(updated[0].openCount == 2)
        #expect(updated[0].pinnedOnTop == true)
        #expect(updated[0].adbSerial == "SER01")

        // Reload from disk verifies persistence
        let reloaded = PopoutHistoryStore(storageURL: tempURL)
        #expect(reloaded.snapshot().count == 1)
        #expect(reloaded.snapshot()[0].pinnedOnTop == true)

        // Cap at 16 entries
        for i in 0..<20 {
            reloaded.recordLaunch(PopoutHistoryEntry(
                appPackage: "com.demo.app\(i)",
                appLabel: "App \(i)",
                deviceKey: "192.168.1.10:19878",
                deviceName: "Pixel",
                transportKind: "wifi",
                host: "192.168.1.10",
                port: 19878,
                lastOpenedAtMs: Int64(3000 + i)
            ))
        }
        #expect(reloaded.snapshot().count == PopoutHistoryLogic.maxEntries)
        #expect(reloaded.snapshot().first?.appPackage == "com.demo.app19")

        reloaded.clearAll()
        #expect(reloaded.snapshot().isEmpty)
    }

    @Test("AuthResultPayload decodes permanent trust flag for request-to-pair flow")
    func authResultDecodesPermanentFlag() throws {
        var permanentPayload = Data([1])
        permanentPayload.append(Data(#"{"ok":true,"authToken":"tok123","permanent":true,"deviceName":"Xiaomi 15"}"#.utf8))
        let perm = try AuthResultPayload.decode(from: permanentPayload)
        #expect(perm.ok)
        #expect(perm.permanent)
        #expect(perm.authToken == "tok123")

        var oncePayload = Data([1])
        oncePayload.append(Data(#"{"ok":true,"authToken":"","permanent":false,"deviceName":"Xiaomi 15"}"#.utf8))
        let once = try AuthResultPayload.decode(from: oncePayload)
        #expect(once.ok)
        #expect(!once.permanent)
        #expect(once.authToken.isEmpty)
    }
}
