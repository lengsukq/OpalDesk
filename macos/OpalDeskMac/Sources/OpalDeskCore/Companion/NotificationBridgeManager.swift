import AppKit
import Foundation
import os
@preconcurrency import UserNotifications

// MARK: - Pure OTP Verification Code Extractor

/// Pure, dependency-free SMS/notification OTP verification code extractor for Chinese & English messages.
/// Serves as a local Mac fallback whenever Android does not supply a pre-extracted `otpCode`.
public enum OtpExtractor: Sendable {
    private static let otpKeywords: [String] = [
        // Chinese
        "验证码", "校验码", "动态码", "安全码", "认证码", "确认码",
        "一次性密码", "动态密码", "短信码", "登录码", "交易码", "提取码", "口令",
        // English (matched case-insensitively)
        "verification code", "verify code", "security code", "auth code",
        "authentication code", "login code", "sign-in code", "signin code",
        "confirmation code", "one-time password", "one time password",
        "passcode", "pin code", "2fa", "otp", "use code", "enter code",
        "your code", "code is", "code:"
    ]

    private static let negativePrefixTokens: [String] = [
        "尾号", "卡号", "账户", "账号", "订单", "单号", "快递", "运单",
        "金额", "余额", "消费", "支付", "扣款", "收款", "人民币", "rmb", "usd", "cny",
        "¥", "￥", "$", "€", "£", "****", "***",
        "ending in", "order", "account", "invoice", "tel:", "phone"
    ]

    private static let negativeSuffixUnits: [String] = [
        "元", "块", "美元", "港币", "欧元", "英镑", "rmb", "usd", "cny",
        "年", "月", "日", "时", "分", "秒", "分钟", "小时", "天",
        "ms", "sec", "min", "mins", "%", "件", "笔", "人", "次"
    ]

    /// Extracts an OTP code from separate notification title and body strings.
    public static func extract(title: String, text: String) -> String? {
        let combined: String
        switch (title.isEmpty, text.isEmpty) {
        case (true, true): return nil
        case (true, false): combined = text
        case (false, true): combined = title
        case (false, false): combined = "\(title)\n\(text)"
        }
        return extract(from: combined)
    }

    /// Extracts a 4–8 digit OTP code from notification text while rejecting amounts,
    /// phone numbers, years/dates, and masked card tails.
    public static func extract(from rawText: String) -> String? {
        let trimmed = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        let lowered = trimmed.lowercased()
        let keywordRanges = findKeywordRanges(in: lowered)
        guard !keywordRanges.isEmpty else { return nil }

        let candidates = findNumericCandidates(in: trimmed)
        guard !candidates.isEmpty else { return nil }

        var bestCandidate: (code: String, score: Int, startOffset: Int)?

        for candidate in candidates {
            guard !isFalsePositive(candidate: candidate, in: trimmed, lowered: lowered, keywordRanges: keywordRanges) else {
                continue
            }
            let score = scoreCandidate(candidate, keywordRanges: keywordRanges)
            if let current = bestCandidate {
                if score < current.score || (score == current.score && candidate.range.location < current.startOffset) {
                    bestCandidate = (candidate.normalizedCode, score, candidate.range.location)
                }
            } else {
                bestCandidate = (candidate.normalizedCode, score, candidate.range.location)
            }
        }

        return bestCandidate?.code
    }

    // MARK: - Candidate Discovery & Filtering

    private struct Candidate {
        let normalizedCode: String
        let range: NSRange
        let isExplicitGooglePrefix: Bool
    }

    private static func findKeywordRanges(in lowered: String) -> [NSRange] {
        let ns = lowered as NSString
        var ranges: [NSRange] = []
        for keyword in otpKeywords {
            var searchRange = NSRange(location: 0, length: ns.length)
            while searchRange.length > 0 {
                let found = ns.range(of: keyword, options: [], range: searchRange)
                if found.location == NSNotFound { break }
                // For short ASCII keywords like "otp" or "2fa", enforce word boundaries so "photo" won't match.
                if isAsciiKeywordBoundaryValid(keyword: keyword, range: found, in: ns) {
                    ranges.append(found)
                }
                let nextLoc = found.location + found.length
                searchRange = NSRange(location: nextLoc, length: ns.length - nextLoc)
            }
        }
        // Also recognize branded English phrases like "your Instagram code" or "your Apple ID code".
        if let brandedRegex = try? NSRegularExpression(pattern: "\\byour\\s+(?:[a-z0-9._-]+\\s+){1,3}code\\b") {
            let fullRange = NSRange(location: 0, length: ns.length)
            for match in brandedRegex.matches(in: lowered, range: fullRange) {
                ranges.append(match.range)
            }
        }
        return ranges
    }

    private static func isAsciiKeywordBoundaryValid(keyword: String, range: NSRange, in text: NSString) -> Bool {
        guard keyword.allSatisfy(\.isASCII) else { return true }
        if range.location > 0 {
            let prev = text.character(at: range.location - 1)
            if isAsciiAlphanumeric(prev) { return false }
        }
        let end = range.location + range.length
        if end < text.length {
            let next = text.character(at: end)
            if isAsciiAlphanumeric(next) { return false }
        }
        return true
    }

    private static func findNumericCandidates(in text: String) -> [Candidate] {
        let ns = text as NSString
        let fullRange = NSRange(location: 0, length: ns.length)
        var results: [Candidate] = []
        var occupiedRanges: [NSRange] = []

        // 1. Google-style "G-123456"
        if let googleRegex = try? NSRegularExpression(pattern: "(?<![A-Za-z0-9])[Gg]-(\\d{6})(?!\\d)") {
            for match in googleRegex.matches(in: text, range: fullRange) {
                let code = ns.substring(with: match.range(at: 1))
                results.append(Candidate(normalizedCode: code, range: match.range, isExplicitGooglePrefix: true))
                occupiedRanges.append(match.range)
            }
        }

        // 2. Hyphen-split 6-digit codes like "492-810" (excluding phone segments like 400-800-1234)
        if let splitRegex = try? NSRegularExpression(pattern: "(?<![\\d-])(\\d{3})-(\\d{3})(?![\\d-])") {
            for match in splitRegex.matches(in: text, range: fullRange) {
                let r = match.range
                if occupiedRanges.contains(where: { NSIntersectionRange($0, r).length > 0 }) { continue }
                let code = ns.substring(with: match.range(at: 1)) + ns.substring(with: match.range(at: 2))
                results.append(Candidate(normalizedCode: code, range: r, isExplicitGooglePrefix: false))
                occupiedRanges.append(r)
            }
        }

        // 3. Standalone 4–8 digit codes (not adjacent to other digits, rejecting 11-digit phones & long order IDs)
        if let plainRegex = try? NSRegularExpression(pattern: "(?<!\\d)(\\d{4,8})(?!\\d)") {
            for match in plainRegex.matches(in: text, range: fullRange) {
                let r = match.range
                if occupiedRanges.contains(where: { NSIntersectionRange($0, r).length > 0 }) { continue }
                let code = ns.substring(with: match.range(at: 1))
                results.append(Candidate(normalizedCode: code, range: r, isExplicitGooglePrefix: false))
            }
        }

        return results
    }

    private static func isFalsePositive(
        candidate: Candidate,
        in original: String,
        lowered: String,
        keywordRanges: [NSRange]
    ) -> Bool {
        if candidate.isExplicitGooglePrefix { return false }

        let ns = lowered as NSString
        let origNS = original as NSString
        let start = candidate.range.location
        let end = start + candidate.range.length

        // Reject decimal amounts (e.g., 1280.00 or 0.1234) and hyphenated phone/date chains (e.g., 2026-05-01, 400-800-1234)
        if start > 0 {
            let prev = origNS.character(at: start - 1)
            if prev == unichar(0x2E) /* '.' */ && start >= 2 && isAsciiDigit(origNS.character(at: start - 2)) {
                return true
            }
            if prev == unichar(0x2D) /* '-' */ && start >= 2 && isAsciiDigit(origNS.character(at: start - 2)) {
                return true
            }
            if prev == unichar(0x3A) /* ':' */ && start >= 2 && isAsciiDigit(origNS.character(at: start - 2)) {
                return true
            }
            if prev == unichar(0x2F) /* '/' */ && start >= 2 && isAsciiDigit(origNS.character(at: start - 2)) {
                return true
            }
        }
        if end < origNS.length {
            let next = origNS.character(at: end)
            if next == unichar(0x2E) /* '.' */ && end + 1 < origNS.length && isAsciiDigit(origNS.character(at: end + 1)) {
                return true
            }
            if (next == unichar(0x2D) /* '-' */ || next == unichar(0x2F) /* '/' */)
                && end + 1 < origNS.length && isAsciiDigit(origNS.character(at: end + 1))
            {
                return true
            }
            if next == unichar(0x3A) /* ':' */ && end + 1 < origNS.length && isAsciiDigit(origNS.character(at: end + 1)) {
                return true
            }
        }

        // Reject calendar dates formatted as YYYYMMDD (e.g., 20260501)
        if candidate.normalizedCode.count == 8, isLikelyYYYYMMDD(candidate.normalizedCode) {
            return true
        }

        // Reject immediate negative prefix (currency, card tail, order ID) unless an OTP keyword appears closer.
        let prefixWindowStart = max(0, start - 14)
        let prefixSlice = ns.substring(with: NSRange(location: prefixWindowStart, length: start - prefixWindowStart))
        let trimmedPrefix = prefixSlice.trimmingCharacters(in: .whitespacesAndNewlines)
        for neg in negativePrefixTokens {
            if let negRange = trimmedPrefix.range(of: neg, options: .backwards) {
                let tailAfterNeg = String(trimmedPrefix[negRange.upperBound...])
                    .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ":：为是")))
                let hasKeywordBetween = otpKeywords.contains { tailAfterNeg.contains($0) }
                if !hasKeywordBetween && tailAfterNeg.count <= 3 {
                    return true
                }
            }
        }

        // Reject immediate negative suffix (currency, year/date, duration units).
        let suffixWindowLen = min(10, ns.length - end)
        if suffixWindowLen > 0 {
            let suffixSlice = ns.substring(with: NSRange(location: end, length: suffixWindowLen))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            for unit in negativeSuffixUnits {
                if suffixSlice.hasPrefix(unit) {
                    // For ASCII unit suffixes like "min" / "ms", ensure word boundary after unit.
                    if unit.allSatisfy(\.isASCII) {
                        let afterUnit = suffixSlice.dropFirst(unit.count)
                        if let first = afterUnit.first, first.isASCII && (first.isLetter || first.isNumber) {
                            continue
                        }
                    }
                    return true
                }
            }
        }

        return false
    }

    private static func isLikelyYYYYMMDD(_ code: String) -> Bool {
        guard code.count == 8,
              let year = Int(code.prefix(4)),
              let month = Int(code.dropFirst(4).prefix(2)),
              let day = Int(code.suffix(2))
        else { return false }
        return (1990...2099).contains(year) && (1...12).contains(month) && (1...31).contains(day)
    }

    private static func scoreCandidate(_ candidate: Candidate, keywordRanges: [NSRange]) -> Int {
        var bestDistance = Int.max
        let candStart = candidate.range.location
        let candEnd = candStart + candidate.range.length

        for kw in keywordRanges {
            let kwEnd = kw.location + kw.length
            let dist: Int
            if candStart >= kwEnd {
                // Slight preference when code immediately follows the keyword ("验证码：123456").
                dist = (candStart - kwEnd) * 2
            } else if kw.location >= candEnd {
                dist = (kw.location - candEnd) * 2 + 1
            } else {
                dist = 0
            }
            if dist < bestDistance {
                bestDistance = dist
            }
        }

        // Tie-break preference: 6-digit OTP > 4-digit > 5-digit > 8-digit > 7-digit
        let lengthPenalty: Int
        switch candidate.normalizedCode.count {
        case 6: lengthPenalty = 0
        case 4: lengthPenalty = 2
        case 5: lengthPenalty = 4
        case 8: lengthPenalty = 6
        default: lengthPenalty = 8
        }
        return bestDistance * 10 + lengthPenalty
    }

    private static func isAsciiDigit(_ ch: unichar) -> Bool {
        ch >= 0x30 && ch <= 0x39
    }

    private static func isAsciiAlphanumeric(_ ch: unichar) -> Bool {
        isAsciiDigit(ch) || (ch >= 0x41 && ch <= 0x5A) || (ch >= 0x61 && ch <= 0x7A)
    }
}

// MARK: - Local Notification Center & Clipboard Abstractions (Testable & Safe Outside .app Bundles)

public struct DeliveredLocalNotification: Sendable, Equatable {
    public var identifier: String
    public var title: String
    public var subtitle: String
    public var body: String
    public var categoryIdentifier: String
    public var userInfo: [String: String]

    public init(
        identifier: String,
        title: String,
        subtitle: String = "",
        body: String,
        categoryIdentifier: String,
        userInfo: [String: String] = [:]
    ) {
        self.identifier = identifier
        self.title = title
        self.subtitle = subtitle
        self.body = body
        self.categoryIdentifier = categoryIdentifier
        self.userInfo = userInfo
    }
}

public enum LocalNotificationAction: Sendable, Equatable {
    case opened(key: String, pkg: String, appLabel: String)
    case dismissed(key: String)
    case copyOtp(key: String, otpCode: String)
    case inlineReply(key: String, pkg: String, replyText: String)
}

public protocol LocalNotificationCenterProtocol: Sendable {
    func registerCategories(onAction: @escaping @Sendable (LocalNotificationAction) -> Void)
    func requestAuthorizationIfNeeded() async -> Bool
    func post(_ notification: DeliveredLocalNotification) async
    func removeDelivered(identifiers: [String])
    func removeAllDelivered()
}

public protocol ClipboardWriterProtocol: Sendable {
    func writeString(_ text: String)
}

/// Default `NSPasteboard.general` writer safe to call from any thread.
public final class SystemClipboardWriter: ClipboardWriterProtocol, Sendable {
    public init() {}

    public func writeString(_ text: String) {
        guard !text.isEmpty else { return }
        DispatchQueue.main.async {
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(text, forType: .string)
        }
    }
}

/// Safe fallback used automatically in non-`.app` Bundle environments (such as `swift test` or CLI binaries)
/// where touching `UNUserNotificationCenter.current()` would raise `NSInternalInconsistencyException`.
public final class NoopLocalNotificationCenter: LocalNotificationCenterProtocol, Sendable {
    public init() {}
    public func registerCategories(onAction: @escaping @Sendable (LocalNotificationAction) -> Void) {}
    public func requestAuthorizationIfNeeded() async -> Bool { false }
    public func post(_ notification: DeliveredLocalNotification) async {}
    public func removeDelivered(identifiers: [String]) {}
    public func removeAllDelivered() {}
}

/// Notification category & action identifiers registered with `UNUserNotificationCenter`.
public enum NotificationBridgeIdentifiers {
    public static let categoryPlain = "OPALDESK_REMOTE_PLAIN"
    public static let categoryOtp = "OPALDESK_REMOTE_OTP"
    public static let categoryReply = "OPALDESK_REMOTE_REPLY"
    public static let categoryOtpReply = "OPALDESK_REMOTE_OTP_REPLY"

    public static let actionCopyOtp = "COPY_OTP"
    public static let actionInlineReply = "INLINE_REPLY"

    public static let userInfoKey = "opaldesk.notification.key"
    public static let userInfoPkg = "opaldesk.notification.pkg"
    public static let userInfoAppLabel = "opaldesk.notification.appLabel"
    public static let userInfoOtpCode = "opaldesk.notification.otpCode"
}

/// Production `UNUserNotificationCenter` adapter guarded so it is ONLY constructed inside a real `.app` bundle.
final class SystemUserNotificationCenterAdapter: NSObject, LocalNotificationCenterProtocol, UNUserNotificationCenterDelegate, @unchecked Sendable {
    private static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "companion.notifications")

    private struct MutableState {
        var onAction: (@Sendable (LocalNotificationAction) -> Void)?
        var hasRequestedAuth: Bool = false
        var authorized: Bool = false
    }

    private let center: UNUserNotificationCenter
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    /// Returns a live adapter only when running inside a proper `.app` bundle; otherwise `nil`.
    static func createIfSupported(bundle: Bundle = .main) -> SystemUserNotificationCenterAdapter? {
        guard NotificationBridgeManager.isRunningInAppBundle(bundle) else {
            return nil
        }
        return SystemUserNotificationCenterAdapter(center: UNUserNotificationCenter.current())
    }

    private init(center: UNUserNotificationCenter) {
        self.center = center
        super.init()
    }

    func registerCategories(onAction: @escaping @Sendable (LocalNotificationAction) -> Void) {
        state.withLock { $0.onAction = onAction }
        center.delegate = self

        let copyOtpAction = UNNotificationAction(
            identifier: NotificationBridgeIdentifiers.actionCopyOtp,
            title: "复制验证码",
            options: []
        )
        let inlineReplyAction = UNTextInputNotificationAction(
            identifier: NotificationBridgeIdentifiers.actionInlineReply,
            title: "回复",
            options: [],
            textInputButtonTitle: "发送",
            textInputPlaceholder: "输入回复内容..."
        )

        let plainCategory = UNNotificationCategory(
            identifier: NotificationBridgeIdentifiers.categoryPlain,
            actions: [],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let otpCategory = UNNotificationCategory(
            identifier: NotificationBridgeIdentifiers.categoryOtp,
            actions: [copyOtpAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let replyCategory = UNNotificationCategory(
            identifier: NotificationBridgeIdentifiers.categoryReply,
            actions: [inlineReplyAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let otpReplyCategory = UNNotificationCategory(
            identifier: NotificationBridgeIdentifiers.categoryOtpReply,
            actions: [copyOtpAction, inlineReplyAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )

        center.setNotificationCategories([plainCategory, otpCategory, replyCategory, otpReplyCategory])
    }

    func requestAuthorizationIfNeeded() async -> Bool {
        let cached: Bool? = state.withLock { s in
            s.hasRequestedAuth ? s.authorized : nil
        }
        if let cached { return cached }

        do {
            let granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
            state.withLock { s in
                s.hasRequestedAuth = true
                s.authorized = granted
            }
            return granted
        } catch {
            Self.logger.warning("UNUserNotificationCenter authorization failed: \(error.localizedDescription, privacy: .public)")
            state.withLock { s in
                s.hasRequestedAuth = true
                s.authorized = false
            }
            return false
        }
    }

    func post(_ notification: DeliveredLocalNotification) async {
        let content = UNMutableNotificationContent()
        content.title = notification.title
        if !notification.subtitle.isEmpty {
            content.subtitle = notification.subtitle
        }
        content.body = notification.body
        content.categoryIdentifier = notification.categoryIdentifier
        content.sound = .default
        content.userInfo = notification.userInfo

        let request = UNNotificationRequest(
            identifier: notification.identifier,
            content: content,
            trigger: nil
        )
        do {
            try await center.add(request)
        } catch {
            Self.logger.warning("Failed to post local notification \(notification.identifier, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }

    func removeDelivered(identifiers: [String]) {
        guard !identifiers.isEmpty else { return }
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    func removeAllDelivered() {
        center.removeAllDeliveredNotifications()
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let action = Self.parseAction(from: response)
        completionHandler()
        if let action {
            let callback = state.withLock { $0.onAction }
            callback?(action)
        }
    }

    private static func parseAction(from response: UNNotificationResponse) -> LocalNotificationAction? {
        let info = response.notification.request.content.userInfo
        let key = (info[NotificationBridgeIdentifiers.userInfoKey] as? String)
            ?? response.notification.request.identifier
        let pkg = (info[NotificationBridgeIdentifiers.userInfoPkg] as? String) ?? ""
        let appLabel = (info[NotificationBridgeIdentifiers.userInfoAppLabel] as? String) ?? pkg
        let otpCode = (info[NotificationBridgeIdentifiers.userInfoOtpCode] as? String) ?? ""

        switch response.actionIdentifier {
        case UNNotificationDefaultActionIdentifier:
            return .opened(key: key, pkg: pkg, appLabel: appLabel)
        case UNNotificationDismissActionIdentifier:
            return .dismissed(key: key)
        case NotificationBridgeIdentifiers.actionCopyOtp:
            guard !otpCode.isEmpty else { return nil }
            return .copyOtp(key: key, otpCode: otpCode)
        case NotificationBridgeIdentifiers.actionInlineReply:
            guard let textResponse = response as? UNTextInputNotificationResponse else { return nil }
            return .inlineReply(key: key, pkg: pkg, replyText: textResponse.userText)
        default:
            return nil
        }
    }
}

// MARK: - NotificationBridgeManager

/// Bridges Android status-bar notifications (`0x19`) to native macOS Notification Center banners,
/// maintains a deduplicated active notification list, performs local OTP extraction/auto-copy,
/// and routes inline reply / open-app / dismiss actions back to the companion session.
public final class NotificationBridgeManager: Sendable {
    public struct Callbacks: Sendable {
        public var onNotificationsChanged: (@Sendable ([RemoteNotificationItem]) -> Void)?
        public var onOpenApp: (@Sendable (_ packageName: String, _ appLabel: String) -> Void)?
        public var onDismissNotification: (@Sendable (_ key: String) -> Void)?
        public var onReplyNotification: (@Sendable (_ key: String, _ replyText: String) -> Void)?
        public var onOtpCopied: (@Sendable (_ code: String, _ item: RemoteNotificationItem) -> Void)?
        public var onInvokeAction: (@Sendable (_ key: String, _ actionIndex: Int) -> Void)?

        public init(
            onNotificationsChanged: (@Sendable ([RemoteNotificationItem]) -> Void)? = nil,
            onOpenApp: (@Sendable (_ packageName: String, _ appLabel: String) -> Void)? = nil,
            onDismissNotification: (@Sendable (_ key: String) -> Void)? = nil,
            onReplyNotification: (@Sendable (_ key: String, _ replyText: String) -> Void)? = nil,
            onOtpCopied: (@Sendable (_ code: String, _ item: RemoteNotificationItem) -> Void)? = nil,
            onInvokeAction: (@Sendable (_ key: String, _ actionIndex: Int) -> Void)? = nil
        ) {
            self.onNotificationsChanged = onNotificationsChanged
            self.onOpenApp = onOpenApp
            self.onDismissNotification = onDismissNotification
            self.onReplyNotification = onReplyNotification
            self.onOtpCopied = onOtpCopied
            self.onInvokeAction = onInvokeAction
        }
    }

    private struct MutableState {
        var isEnabled: Bool = true
        var autoCopyOtpToClipboard: Bool = false
        var suppressedPackages: Set<String> = []
        var itemsByKey: [String: RemoteNotificationItem] = [:]
        var deliveredFingerprints: [String: String] = [:]
        var copiedOtpByKey: [String: String] = [:]
        var callbacks = Callbacks()
        var sendFrame: (@Sendable (OpalCastFrame) -> Bool)?
        var isStarted: Bool = false
    }

    private static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "companion.notifications")
    private let notificationCenter: any LocalNotificationCenterProtocol
    private let clipboardWriter: any ClipboardWriterProtocol
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    /// Returns `true` only when executing inside a real macOS `.app` bundle with a valid bundle identifier.
    /// Prevents `UNUserNotificationCenter.current()` crashes under `swift test` or standalone CLI binaries.
    public static func isRunningInAppBundle(_ bundle: Bundle = .main) -> Bool {
        let url = bundle.bundleURL
        guard url.pathExtension.lowercased() == "app" else { return false }
        guard let bundleId = bundle.bundleIdentifier, !bundleId.isEmpty else { return false }
        return true
    }

    public init(
        notificationCenter: (any LocalNotificationCenterProtocol)? = nil,
        clipboardWriter: any ClipboardWriterProtocol = SystemClipboardWriter(),
        callbacks: Callbacks = Callbacks(),
        sendFrame: (@Sendable (OpalCastFrame) -> Bool)? = nil
    ) {
        self.notificationCenter = notificationCenter
            ?? SystemUserNotificationCenterAdapter.createIfSupported()
            ?? NoopLocalNotificationCenter()
        self.clipboardWriter = clipboardWriter
        self.state.withLock {
            $0.callbacks = callbacks
            $0.sendFrame = sendFrame
        }
    }

    public func setFrameSender(_ sender: (@Sendable (OpalCastFrame) -> Bool)?) {
        state.withLock { $0.sendFrame = sender }
    }

    public var isEnabled: Bool {
        get { state.withLock { $0.isEnabled } }
        set { state.withLock { $0.isEnabled = newValue } }
    }

    public var autoCopyOtpToClipboard: Bool {
        get { state.withLock { $0.autoCopyOtpToClipboard } }
        set { state.withLock { $0.autoCopyOtpToClipboard = newValue } }
    }

    public var suppressedPackages: Set<String> {
        get { state.withLock { $0.suppressedPackages } }
        set { state.withLock { $0.suppressedPackages = newValue } }
    }

    /// Snapshot of currently active remote notifications sorted newest-first.
    public var activeNotifications: [RemoteNotificationItem] {
        state.withLock { Self.sortedItems(from: $0.itemsByKey) }
    }

    public func setCallbacks(_ callbacks: Callbacks) {
        state.withLock { $0.callbacks = callbacks }
    }

    public func updateCallbacks(_ mutate: @Sendable (inout Callbacks) -> Void) {
        state.withLock { mutate(&$0.callbacks) }
    }

    public func start() {
        let shouldRegister: Bool = state.withLock { s in
            guard !s.isStarted else { return false }
            s.isStarted = true
            return true
        }
        guard shouldRegister else { return }
        notificationCenter.registerCategories { [weak self] action in
            self?.handleLocalAction(action)
        }
    }

    public func stop(clearDelivered: Bool = false) {
        state.withLock { $0.isStarted = false }
        if clearDelivered {
            clearLocalState(removeDelivered: true)
        }
    }

    @discardableResult
    public func requestAuthorization() async -> Bool {
        await notificationCenter.requestAuthorizationIfNeeded()
    }

    /// Clears cached notification state (e.g. when the companion session disconnects).
    public func clearLocalState(removeDelivered: Bool = true) {
        let (removedIds, onChanged): ([String], (@Sendable ([RemoteNotificationItem]) -> Void)?) = state.withLock { s in
            let ids = Array(s.itemsByKey.keys).map(Self.localIdentifier(forRemoteKey:))
            s.itemsByKey.removeAll()
            s.deliveredFingerprints.removeAll()
            s.copiedOtpByKey.removeAll()
            return (ids, s.callbacks.onNotificationsChanged)
        }
        if removeDelivered && !removedIds.isEmpty {
            notificationCenter.removeDelivered(identifiers: removedIds)
        }
        onChanged?([])
    }

    /// Processes an inbound `0x19 NOTIFICATION_EVENT` frame from Android.
    public func handleEvent(_ event: NotificationEventPayload) {
        switch event.action {
        case .snapshot:
            handleSnapshot(event.items)
        case .posted:
            handlePosted(event.items)
        case .removed:
            if let key = event.key, !key.isEmpty {
                handleRemoved(keys: [key])
            } else if !event.items.isEmpty {
                handleRemoved(keys: event.items.map(\.key).filter { !$0.isEmpty })
            }
        }
    }

    /// Handles a macOS Notification Center user interaction (or simulated action in unit tests).
    public func handleLocalAction(_ action: LocalNotificationAction) {
        switch action {
        case let .opened(_, pkg, appLabel):
            guard !pkg.isEmpty else { return }
            let callback = state.withLock { $0.callbacks.onOpenApp }
            callback?(pkg, appLabel.isEmpty ? pkg : appLabel)

        case let .dismissed(key):
            guard !key.isEmpty else { return }
            let (updated, onChanged, onDismiss): (
                [RemoteNotificationItem]?,
                (@Sendable ([RemoteNotificationItem]) -> Void)?,
                (@Sendable (String) -> Void)?
            ) = state.withLock { s in
                let existed = s.itemsByKey.removeValue(forKey: key) != nil
                s.deliveredFingerprints.removeValue(forKey: key)
                s.copiedOtpByKey.removeValue(forKey: key)
                let sorted = existed ? Self.sortedItems(from: s.itemsByKey) : nil
                return (sorted, s.callbacks.onNotificationsChanged, s.callbacks.onDismissNotification)
            }
            if let updated {
                onChanged?(updated)
            }
            onDismiss?(key)

        case let .copyOtp(key, otpCode):
            guard !otpCode.isEmpty else { return }
            clipboardWriter.writeString(otpCode)
            let (item, onOtpCopied): (RemoteNotificationItem?, (@Sendable (String, RemoteNotificationItem) -> Void)?) = state.withLock { s in
                s.copiedOtpByKey[key] = otpCode
                return (s.itemsByKey[key], s.callbacks.onOtpCopied)
            }
            if let item {
                onOtpCopied?(otpCode, item)
            }

        case let .inlineReply(key, _, replyText):
            let trimmed = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, !trimmed.isEmpty else { return }
            let onReply = state.withLock { $0.callbacks.onReplyNotification }
            onReply?(key, trimmed)
        }
    }

    /// Invokes a quick action button on a remote Android notification by emitting
    /// `0x31 NOTIFICATION_ACTION` (`op: "invoke_action"`).
    @discardableResult
    public func invokeAction(key: String, actionIndex: Int) -> Bool {
        guard !key.isEmpty, actionIndex >= 0 else { return false }
        let (onInvoke, sender): (
            (@Sendable (String, Int) -> Void)?,
            (@Sendable (OpalCastFrame) -> Bool)?
        ) = state.withLock { s in
            (s.callbacks.onInvokeAction, s.sendFrame)
        }
        if let onInvoke {
            onInvoke(key, actionIndex)
            return true
        }
        guard let sender else { return false }
        do {
            let frame = try OpalCastFrame.notificationAction(.invokeAction(key: key, actionIndex: actionIndex))
            return sender(frame)
        } catch {
            Self.logger.error("Failed to encode invoke_action NOTIFICATION_ACTION: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    /// Enriches a notification item with a locally extracted OTP code if Android omitted `otpCode`.
    public static func enrichWithOtp(_ item: RemoteNotificationItem) -> RemoteNotificationItem {
        guard item.otpCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return item
        }
        var enriched = item
        if let extracted = OtpExtractor.extract(title: item.title, text: item.text) {
            enriched.otpCode = extracted
        }
        return enriched
    }

    /// Builds the native macOS notification payload for a remote Android notification item.
    public static func makeLocalNotification(for item: RemoteNotificationItem) -> DeliveredLocalNotification {
        let hasOtp = !item.otpCode.isEmpty
        let category: String
        switch (hasOtp, item.canReply) {
        case (true, true): category = NotificationBridgeIdentifiers.categoryOtpReply
        case (true, false): category = NotificationBridgeIdentifiers.categoryOtp
        case (false, true): category = NotificationBridgeIdentifiers.categoryReply
        case (false, false): category = NotificationBridgeIdentifiers.categoryPlain
        }

        let appTitle = item.displayAppLabel
        let displayTitle = item.title.isEmpty ? appTitle : item.title
        let displaySubtitle = item.title.isEmpty ? "" : appTitle
        var displayBody = item.text
        if hasOtp {
            let otpTag = "验证码：\(item.otpCode)"
            if displayBody.isEmpty {
                displayBody = otpTag
            } else if !displayBody.contains(item.otpCode) {
                displayBody = "\(otpTag) · \(displayBody)"
            }
        }

        return DeliveredLocalNotification(
            identifier: localIdentifier(forRemoteKey: item.key),
            title: displayTitle,
            subtitle: displaySubtitle,
            body: displayBody,
            categoryIdentifier: category,
            userInfo: [
                NotificationBridgeIdentifiers.userInfoKey: item.key,
                NotificationBridgeIdentifiers.userInfoPkg: item.pkg,
                NotificationBridgeIdentifiers.userInfoAppLabel: appTitle,
                NotificationBridgeIdentifiers.userInfoOtpCode: item.otpCode
            ]
        )
    }

    public static func localIdentifier(forRemoteKey key: String) -> String {
        "opaldesk.remote.\(key)"
    }

    // MARK: - Private Event Handlers

    private func handleSnapshot(_ rawItems: [RemoteNotificationItem]) {
        let enriched = rawItems
            .filter { !$0.key.isEmpty }
            .map(Self.enrichWithOtp)

        let (sorted, staleIdentifiers, onChanged): (
            [RemoteNotificationItem],
            [String],
            (@Sendable ([RemoteNotificationItem]) -> Void)?
        ) = state.withLock { s in
            let incomingKeys = Set(enriched.map(\.key))
            let removedKeys = Set(s.itemsByKey.keys).subtracting(incomingKeys)
            for removed in removedKeys {
                s.deliveredFingerprints.removeValue(forKey: removed)
                s.copiedOtpByKey.removeValue(forKey: removed)
            }

            var nextMap: [String: RemoteNotificationItem] = [:]
            nextMap.reserveCapacity(enriched.count)
            for item in enriched {
                nextMap[item.key] = item
                // Seed fingerprints for existing snapshot notifications so initial connect never spams old banners.
                s.deliveredFingerprints[item.key] = Self.contentFingerprint(for: item)
            }
            s.itemsByKey = nextMap

            return (
                Self.sortedItems(from: nextMap),
                removedKeys.map(Self.localIdentifier(forRemoteKey:)),
                s.callbacks.onNotificationsChanged
            )
        }

        if !staleIdentifiers.isEmpty {
            notificationCenter.removeDelivered(identifiers: staleIdentifiers)
        }
        onChanged?(sorted)
    }

    private func handlePosted(_ rawItems: [RemoteNotificationItem]) {
        let enriched = rawItems
            .filter { !$0.key.isEmpty }
            .map(Self.enrichWithOtp)
        guard !enriched.isEmpty else { return }

        struct PostPlan {
            let sorted: [RemoteNotificationItem]
            let notificationsToPost: [DeliveredLocalNotification]
            let otpsToAutoCopy: [(code: String, item: RemoteNotificationItem)]
            let onChanged: (@Sendable ([RemoteNotificationItem]) -> Void)?
            let onOtpCopied: (@Sendable (String, RemoteNotificationItem) -> Void)?
        }

        let plan: PostPlan = state.withLock { s in
            var toPost: [DeliveredLocalNotification] = []
            var toCopy: [(code: String, item: RemoteNotificationItem)] = []

            for item in enriched {
                let fp = Self.contentFingerprint(for: item)
                let isDuplicate = (s.deliveredFingerprints[item.key] == fp)
                s.itemsByKey[item.key] = item
                s.deliveredFingerprints[item.key] = fp

                guard s.isEnabled, !s.suppressedPackages.contains(item.pkg), !isDuplicate else {
                    continue
                }
                toPost.append(Self.makeLocalNotification(for: item))

                if s.autoCopyOtpToClipboard,
                   !item.otpCode.isEmpty,
                   s.copiedOtpByKey[item.key] != item.otpCode
                {
                    s.copiedOtpByKey[item.key] = item.otpCode
                    toCopy.append((item.otpCode, item))
                }
            }

            return PostPlan(
                sorted: Self.sortedItems(from: s.itemsByKey),
                notificationsToPost: toPost,
                otpsToAutoCopy: toCopy,
                onChanged: s.callbacks.onNotificationsChanged,
                onOtpCopied: s.callbacks.onOtpCopied
            )
        }

        plan.onChanged?(plan.sorted)

        for entry in plan.otpsToAutoCopy {
            clipboardWriter.writeString(entry.code)
            plan.onOtpCopied?(entry.code, entry.item)
        }

        if !plan.notificationsToPost.isEmpty {
            let center = notificationCenter
            let batch = plan.notificationsToPost
            Task.detached(priority: .userInitiated) {
                for notification in batch {
                    await center.post(notification)
                }
            }
        }
    }

    private func handleRemoved(keys: [String]) {
        guard !keys.isEmpty else { return }

        let (sorted, removedIds, onChanged): (
            [RemoteNotificationItem],
            [String],
            (@Sendable ([RemoteNotificationItem]) -> Void)?
        ) = state.withLock { s in
            var removed: [String] = []
            for key in keys {
                if s.itemsByKey.removeValue(forKey: key) != nil {
                    removed.append(Self.localIdentifier(forRemoteKey: key))
                }
                s.deliveredFingerprints.removeValue(forKey: key)
                s.copiedOtpByKey.removeValue(forKey: key)
            }
            return (Self.sortedItems(from: s.itemsByKey), removed, s.callbacks.onNotificationsChanged)
        }

        if !removedIds.isEmpty {
            notificationCenter.removeDelivered(identifiers: removedIds)
        }
        onChanged?(sorted)
    }

    private static func contentFingerprint(for item: RemoteNotificationItem) -> String {
        let actionsSig = item.actions.map { "\($0.index):\($0.label)" }.joined(separator: ",")
        return "\(item.pkg)|\(item.title)|\(item.text)|\(item.postTimeMs)|\(item.otpCode)|\(item.canReply)|\(actionsSig)"
    }

    private static func sortedItems(from map: [String: RemoteNotificationItem]) -> [RemoteNotificationItem] {
        map.values.sorted { lhs, rhs in
            if lhs.postTimeMs != rhs.postTimeMs {
                return lhs.postTimeMs > rhs.postTimeMs
            }
            return lhs.key < rhs.key
        }
    }
}
