import Foundation

public struct ParsedSentryDsn: Sendable, Equatable {
    public let rawDsn: String
    public let publicKey: String
    public let projectId: String
    public let envelopeEndpointUrl: String
}

public struct ManualReportPayload: Sendable {
    public let eventId: String
    public let installationId: String
    public let reporterName: String
    public let problemDescription: String
    public let appVersion: String
    public let environment: String
    public let osVersion: String
    public let deviceModel: String
    public let connectionActive: Bool
    public let clientLogCount: Int
    public let attachmentFilename: String
    public let compressedLogBytes: Data
    public let sentAtIso: String
    public let kind: FeedbackKind
    public let includeLogs: Bool

    public init(
        eventId: String = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
        installationId: String,
        reporterName: String,
        problemDescription: String,
        appVersion: String,
        environment: String = "production",
        osVersion: String,
        deviceModel: String,
        connectionActive: Bool,
        clientLogCount: Int,
        attachmentFilename: String,
        compressedLogBytes: Data,
        sentAtIso: String = ISO8601DateFormatter().string(from: Date()),
        kind: FeedbackKind = .bug,
        includeLogs: Bool = true
    ) {
        self.eventId = eventId
        self.installationId = installationId
        self.reporterName = reporterName
        self.problemDescription = problemDescription
        self.appVersion = appVersion
        self.environment = environment
        self.osVersion = osVersion
        self.deviceModel = deviceModel
        self.connectionActive = connectionActive
        self.clientLogCount = clientLogCount
        self.attachmentFilename = attachmentFilename
        self.compressedLogBytes = compressedLogBytes
        self.sentAtIso = sentAtIso
        self.kind = kind
        self.includeLogs = includeLogs
    }
}

public enum ManualUploadResult: Sendable, Equatable {
    case success(eventId: String, shortCode: String, httpStatus: Int)
    case failure(reason: String, httpStatus: Int? = nil)
}

public enum FeedbackBatchSyncResult: Sendable, Equatable {
    case success(items: [String: FeedbackRemoteItem], generatedAt: String)
    case failure(reason: String, httpStatus: Int? = nil)
}

public enum FeedbackLookupSyncResult: Sendable, Equatable {
    case found(FeedbackRemoteItem)
    case notFound
    case failure(reason: String, httpStatus: Int? = nil)
}

/// Pure Sentry Envelope v7 builder and async HTTPS uploader aligned byte-for-byte with
/// Android `SentryEnvelopeTransport.kt` and `infra/opaldesk-feedback-worker`.
public enum SentryEnvelopeTransport {
    private static let responseIdRegex = try! NSRegularExpression(pattern: #""id"\s*:\s*"([0-9a-fA-F-]+)""#)

    public static func parseDsn(_ rawDsn: String) -> ParsedSentryDsn? {
        let trimmed = rawDsn.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              (scheme == "https" || scheme == "http"),
              let host = components.host, !host.isEmpty,
              let user = components.user?.trimmingCharacters(in: .whitespacesAndNewlines), !user.isEmpty
        else { return nil }

        let segments = components.path.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        guard let projectId = segments.last else { return nil }
        let prefix = segments.count > 1 ? "/" + segments.dropLast().joined(separator: "/") : ""
        let portPart = components.port.map { ":\($0)" } ?? ""
        let endpoint = "\(scheme)://\(host)\(portPart)\(prefix)/api/\(projectId)/envelope/"
        return ParsedSentryDsn(
            rawDsn: trimmed,
            publicKey: user,
            projectId: projectId,
            envelopeEndpointUrl: endpoint
        )
    }

    public static func buildEnvelopeBytes(dsn: String, payload: ManualReportPayload) -> Data {
        let cleanEventId = payload.eventId.replacingOccurrences(of: "-", with: "").lowercased()
        let rawReporter = DiagnosticSanitizer.sanitizeText(payload.reporterName.trimmingCharacters(in: .whitespacesAndNewlines))
        let cleanReporter = String((rawReporter.isEmpty ? "anonymous" : rawReporter).prefix(64))
        let cleanDesc = String(
            DiagnosticSanitizer.sanitizeText(payload.problemDescription.trimmingCharacters(in: .whitespacesAndNewlines)).prefix(2000)
        )
        let isFeature = payload.kind == .feature
        let hasAttachment = payload.includeLogs && !payload.compressedLogBytes.isEmpty
        let firstLine = cleanDesc.split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
        let headline: String
        if isFeature {
            headline = firstLine.isEmpty
                ? "[Feature Request] Feature suggestion from \(cleanReporter)"
                : "[Feature Request] \(String(firstLine.prefix(90)))"
        } else {
            headline = firstLine.isEmpty
                ? "[User Report] Manual diagnostic log from \(cleanReporter)"
                : "[User Report] \(String(firstLine.prefix(90)))"
        }
        let eventLevel = isFeature ? "info" : "error"
        let shortCode = FeedbackHistoryLogic.formatShortCode(cleanEventId)

        let envelopeHeaderObj: [String: Any] = [
            "event_id": cleanEventId,
            "dsn": dsn,
            "sent_at": payload.sentAtIso,
            "sdk": [
                "name": "opaldesk.macos.manual",
                "version": payload.appVersion,
            ],
        ]

        var eventObj: [String: Any] = [
            "event_id": cleanEventId,
            "timestamp": payload.sentAtIso,
            "level": eventLevel,
            "platform": "cocoa",
            "logger": "opaldesk.manual_report",
            "release": "io.github.lengsukq.opaldesk.mac@\(payload.appVersion)",
            "environment": payload.environment,
            "message": ["formatted": headline],
            "user": [
                "id": payload.installationId,
                "username": cleanReporter,
            ],
            "tags": [
                "installation_id": payload.installationId,
                "feedback_code": shortCode,
                "report_source": "manual_user_report",
                "feedback_kind": payload.kind.wireValue,
                "app_version": payload.appVersion,
                "client_platform": "macos",
                "device_model": payload.deviceModel,
                "connection_active": payload.connectionActive ? "true" : "false",
            ],
            "contexts": [
                "device": [
                    "manufacturer": "Apple",
                    "model": payload.deviceModel,
                    "name": payload.deviceModel,
                ],
                "os": [
                    "name": "macOS",
                    "version": payload.osVersion,
                ],
                "app": [
                    "app_version": payload.appVersion,
                ],
            ],
            "extra": [
                "feedback_kind": payload.kind.wireValue,
                "client_platform": "macos",
                "reporter_name": cleanReporter,
                "user_description": cleanDesc.isEmpty ? "(未填写)" : cleanDesc,
                "logs_attached": hasAttachment,
                "client_log_count": hasAttachment ? payload.clientLogCount : 0,
                "log_gzip_bytes": hasAttachment ? payload.compressedLogBytes.count : 0,
                "screenshot_attached": false,
            ],
        ]
        if isFeature {
            eventObj["fingerprint"] = ["opaldesk-feature", cleanEventId]
        }

        let headerBytes = (try? JSONSerialization.data(withJSONObject: envelopeHeaderObj, options: [.sortedKeys])) ?? Data()
        let eventBytes = (try? JSONSerialization.data(withJSONObject: eventObj, options: [.sortedKeys])) ?? Data()
        let itemHeader = "{\"type\":\"event\",\"content_type\":\"application/json\",\"length\":\(eventBytes.count)}"

        var out = Data(capacity: headerBytes.count + eventBytes.count + (hasAttachment ? payload.compressedLogBytes.count : 0) + 512)
        out.append(headerBytes)
        out.append(0x0A)
        out.append(Data(itemHeader.utf8))
        out.append(0x0A)
        out.append(eventBytes)
        out.append(0x0A)

        if hasAttachment {
            let attachObj: [String: Any] = [
                "type": "attachment",
                "filename": payload.attachmentFilename,
                "content_type": "application/gzip",
                "length": payload.compressedLogBytes.count,
            ]
            if let attachHeader = try? JSONSerialization.data(withJSONObject: attachObj, options: [.sortedKeys]) {
                out.append(attachHeader)
                out.append(0x0A)
                out.append(payload.compressedLogBytes)
                out.append(0x0A)
            }
        }
        return out
    }

    public static func sendEnvelope(
        rawDsn: String,
        tunnelUrl: String,
        payload: ManualReportPayload,
        session: URLSession = .shared
    ) async -> ManualUploadResult {
        guard let parsed = parseDsn(rawDsn) else {
            return .failure(reason: "当前构建未配置 Sentry DSN，暂无法上传（本次未消耗次数）")
        }
        let cleanTunnel = tunnelUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let targetStr = (cleanTunnel.hasPrefix("https://") || cleanTunnel.hasPrefix("http://"))
            ? cleanTunnel
            : parsed.envelopeEndpointUrl
        guard let targetURL = URL(string: targetStr) else {
            return .failure(reason: "上报目标地址无效，本次未消耗上传次数")
        }

        let body = buildEnvelopeBytes(dsn: parsed.rawDsn, payload: payload)
        var request = URLRequest(url: targetURL, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 18)
        request.httpMethod = "POST"
        request.setValue("application/x-sentry-envelope", forHTTPHeaderField: "Content-Type")
        request.setValue(
            "Sentry sentry_version=7, sentry_client=opaldesk-macos-manual/\(payload.appVersion), sentry_key=\(parsed.publicKey)",
            forHTTPHeaderField: "X-Sentry-Auth"
        )
        request.setValue(payload.installationId, forHTTPHeaderField: "X-OpalDesk-Installation-Id")
        request.httpBody = body

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return .failure(reason: "上报响应异常，本次未消耗上传次数")
            }
            let code = http.statusCode
            let respText = String(data: data, encoding: .utf8) ?? ""
            if (200...299).contains(code) {
                let confirmedId = extractEventId(from: respText)
                    ?? payload.eventId.replacingOccurrences(of: "-", with: "").lowercased()
                return .success(
                    eventId: confirmedId,
                    shortCode: FeedbackHistoryLogic.formatShortCode(confirmedId),
                    httpStatus: code
                )
            }
            if code == 429 {
                return .failure(reason: "服务端触发频率限制（HTTP 429），请稍后再试；本次未消耗上传次数", httpStatus: code)
            }
            return .failure(reason: "上报接口返回异常（HTTP \(code)），本次未消耗上传次数", httpStatus: code)
        } catch {
            return .failure(reason: "网络连接失败或超时（\(error.localizedDescription)），本次未消耗上传次数")
        }
    }

    public static func extractEventId(from responseJson: String) -> String? {
        let range = NSRange(responseJson.startIndex..<responseJson.endIndex, in: responseJson)
        guard let match = responseIdRegex.firstMatch(in: responseJson, range: range),
              let idRange = Range(match.range(at: 1), in: responseJson)
        else { return nil }
        let cleaned = responseJson[idRange].replacingOccurrences(of: "-", with: "").lowercased()
        return cleaned.count >= 8 ? cleaned : nil
    }
}

/// Async read-only HTTPS client for querying feedback status & developer replies from Cloudflare Worker.
public enum FeedbackStatusClient {
    public static func fetchBatchStatuses(
        baseUrl: String,
        installationId: String,
        eventIds: [String],
        allowLocalhostForTests: Bool = false,
        session: URLSession = .shared
    ) async -> FeedbackBatchSyncResult {
        let cleanBase = FeedbackHistoryLogic.resolveStatusBaseUrl(
            feedbackStatusUrl: baseUrl,
            sentryTunnelUrl: "",
            allowLocalhostForTests: allowLocalhostForTests
        )
        guard !cleanBase.isEmpty else {
            return .failure(reason: "当前构建未配置合法的 HTTPS 状态同步地址")
        }
        let cleanInstall = installationId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanInstall.isEmpty else {
            return .failure(reason: "缺少设备安装标识，暂无法查询")
        }
        var seen = Set<String>()
        let normalizedIds = eventIds
            .map { FeedbackHistoryLogic.normalizeEventId($0) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
            .prefix(FeedbackHistoryLogic.maxBatchQueryIds)
        guard !normalizedIds.isEmpty else {
            return .success(items: [:], generatedAt: "")
        }

        guard var comps = URLComponents(string: "\(cleanBase)/v1/feedback/status") else {
            return .failure(reason: "状态查询地址格式异常")
        }
        comps.queryItems = [
            URLQueryItem(name: "installation_id", value: cleanInstall),
            URLQueryItem(name: "event_ids", value: normalizedIds.joined(separator: ",")),
        ]
        guard let url = comps.url else {
            return .failure(reason: "状态查询地址构造失败")
        }

        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 12)
        req.httpMethod = "GET"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("OpalDesk-macOS-FeedbackClient/1.0", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                return .failure(reason: "状态服务返回无效响应")
            }
            let code = http.statusCode
            if (200...299).contains(code) {
                guard let parsed = FeedbackStatusParser.parseBatchStatusJson(data) else {
                    return .failure(reason: "状态服务返回格式异常", httpStatus: code)
                }
                return .success(items: parsed.items, generatedAt: parsed.generatedAt)
            }
            if code == 429 {
                return .failure(reason: "状态查询过于频繁，请稍后再试", httpStatus: code)
            }
            return .failure(reason: "状态服务响应异常（HTTP \(code)）", httpStatus: code)
        } catch {
            return .failure(reason: "网络请求失败（\(error.localizedDescription)）")
        }
    }

    public static func lookupByShortCode(
        baseUrl: String,
        rawCode: String,
        allowLocalhostForTests: Bool = false,
        session: URLSession = .shared
    ) async -> FeedbackLookupSyncResult {
        let cleanBase = FeedbackHistoryLogic.resolveStatusBaseUrl(
            feedbackStatusUrl: baseUrl,
            sentryTunnelUrl: "",
            allowLocalhostForTests: allowLocalhostForTests
        )
        guard !cleanBase.isEmpty else {
            return .failure(reason: "当前构建未配置合法的 HTTPS 状态查询地址")
        }
        let code = FeedbackHistoryLogic.normalizeShortCode(rawCode)
        guard !code.isEmpty else {
            return .failure(reason: "请输入 8 位反馈码（如 A1B2C3D4）")
        }
        guard var comps = URLComponents(string: "\(cleanBase)/v1/feedback/lookup") else {
            return .failure(reason: "查询地址格式异常")
        }
        comps.queryItems = [URLQueryItem(name: "code", value: code)]
        guard let url = comps.url else {
            return .failure(reason: "查询地址构造失败")
        }

        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 12)
        req.httpMethod = "GET"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("OpalDesk-macOS-FeedbackClient/1.0", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                return .failure(reason: "查询服务返回无效响应")
            }
            let status = http.statusCode
            if status == 404 { return .notFound }
            if (200...299).contains(status) {
                guard let parsed = FeedbackStatusParser.parseLookupJson(data) else {
                    return .failure(reason: "查询接口返回格式异常", httpStatus: status)
                }
                if parsed.found, let item = parsed.item {
                    return .found(item)
                }
                return .notFound
            }
            if status == 429 {
                return .failure(reason: "查询过于频繁，请稍后再试", httpStatus: status)
            }
            return .failure(reason: "查询服务响应异常（HTTP \(status)）", httpStatus: status)
        } catch {
            return .failure(reason: "网络请求失败（\(error.localizedDescription)）")
        }
    }
}
