import AppKit
import Foundation
import os
@preconcurrency import UserNotifications

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
            title: OpalL10n.text("notify.action.copy_otp"),
            options: []
        )
        let inlineReplyAction = UNTextInputNotificationAction(
            identifier: NotificationBridgeIdentifiers.actionInlineReply,
            title: OpalL10n.text("notify.action.reply"),
            options: [],
            textInputButtonTitle: OpalL10n.text("notify.action.send"),
            textInputPlaceholder: OpalL10n.text("notify.action.reply_placeholder")
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

// MARK: - Notification Action Handling & Formatting

extension NotificationBridgeManager {
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
            let otpTag = OpalL10n.format("notify.otp_tag_fmt", item.otpCode)
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
}
