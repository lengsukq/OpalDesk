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

    struct MutableState {
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

    static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "companion.notifications")
    let notificationCenter: any LocalNotificationCenterProtocol
    let clipboardWriter: any ClipboardWriterProtocol
    let state = OSAllocatedUnfairLock(initialState: MutableState())

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

    static func sortedItems(from map: [String: RemoteNotificationItem]) -> [RemoteNotificationItem] {
        map.values.sorted { lhs, rhs in
            if lhs.postTimeMs != rhs.postTimeMs {
                return lhs.postTimeMs > rhs.postTimeMs
            }
            return lhs.key < rhs.key
        }
    }
}
