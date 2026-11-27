import Foundation
import os
import Testing
@testable import OpalDeskCore

// MARK: - Test Doubles

private final class MockLocalNotificationCenter: LocalNotificationCenterProtocol, Sendable {
    struct MutableState {
        var registerCount: Int = 0
        var authRequestCount: Int = 0
        var posted: [DeliveredLocalNotification] = []
        var removedIds: [[String]] = []
        var removeAllCount: Int = 0
        var actionHandler: (@Sendable (LocalNotificationAction) -> Void)?
    }

    let state = OSAllocatedUnfairLock(initialState: MutableState())

    func registerCategories(onAction: @escaping @Sendable (LocalNotificationAction) -> Void) {
        state.withLock {
            $0.registerCount += 1
            $0.actionHandler = onAction
        }
    }

    func requestAuthorizationIfNeeded() async -> Bool {
        state.withLock { $0.authRequestCount += 1 }
        return true
    }

    func post(_ notification: DeliveredLocalNotification) async {
        state.withLock { $0.posted.append(notification) }
    }

    func removeDelivered(identifiers: [String]) {
        state.withLock { $0.removedIds.append(identifiers) }
    }

    func removeAllDelivered() {
        state.withLock { $0.removeAllCount += 1 }
    }

    func simulateUserAction(_ action: LocalNotificationAction) {
        let handler = state.withLock { $0.actionHandler }
        handler?(action)
    }

    func waitForPostedCount(_ expected: Int, timeoutMs: Int = 1000) async -> [DeliveredLocalNotification] {
        let iterations = max(1, timeoutMs / 10)
        for _ in 0..<iterations {
            let current = state.withLock { $0.posted }
            if current.count >= expected { return current }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return state.withLock { $0.posted }
    }
}

private final class MockClipboardWriter: ClipboardWriterProtocol, Sendable {
    let written = OSAllocatedUnfairLock<[String]>(initialState: [])

    func writeString(_ text: String) {
        written.withLock { $0.append(text) }
    }
}

@Suite("OtpExtractor & NotificationBridgeManager")
struct NotificationBridgeTests {

    @Test("OtpExtractor extracts Chinese & English verification codes across realistic SMS formats")
    func otpExtractorPositiveCases() {
        #expect(OtpExtractor.extract(from: "【支付宝】您的验证码是123456，5分钟内有效，请勿泄露。") == "123456")
        #expect(OtpExtractor.extract(from: "【微信】登录验证码：849201，有效期10分钟。") == "849201")
        #expect(OtpExtractor.extract(from: "【招商银行】您尾号8848的储蓄卡向尾号1234转账动态密码为 654321，金额5000元。") == "654321")
        #expect(OtpExtractor.extract(from: "Your Google verification code is G-482910") == "482910")
        #expect(OtpExtractor.extract(from: "123456 is your Instagram code. Don't share it.") == "123456")
        #expect(OtpExtractor.extract(from: "Your GitHub two-factor code: 912345") == "912345")
        #expect(OtpExtractor.extract(from: "Your Apple ID verification code is: 492-810.") == "492810")
        #expect(OtpExtractor.extract(from: "Use OTP 4928 to complete login within 300 seconds.") == "4928")
        #expect(OtpExtractor.extract(from: "Security code: 98765432") == "98765432")
        #expect(OtpExtractor.extract(title: "登录验证码", text: "739204，请勿泄露给他人") == "739204")
    }

    @Test("OtpExtractor avoids false positives on amounts, phone numbers, dates, times, and card tails")
    func otpExtractorNegativeCases() {
        #expect(OtpExtractor.extract(from: "【京东】您的订单20260501已发货，预计1030送达，联系电话13800138000") == nil)
        #expect(OtpExtractor.extract(from: "【招商银行】您尾号6688账户于2026年05月01日消费人民币1280.00元，余额25600元。") == nil)
        #expect(OtpExtractor.extract(from: "验证码已发送至手机号 13912345678，请注意查收") == nil)
        #expect(OtpExtractor.extract(from: "【安全提醒】2026年验证码规则升级，将于2026-05-01生效") == nil)
        #expect(OtpExtractor.extract(from: "验证码通知：您尾号 8848 卡片消费 ¥1280.00，余额 25600 元") == nil)
        #expect(OtpExtractor.extract(from: "验证码提示：****1234 登录成功，时间 10:30") == nil)
        #expect(OtpExtractor.extract(from: "验证码相关咨询请拨打客服热线 400-800-1234") == nil)
        #expect(OtpExtractor.extract(from: "   ") == nil)
    }

    @Test("NotificationBridgeManager detects non-.app bundle test runtime and degrades safely without crashing")
    func safeOutsideAppBundle() async {
        #expect(NotificationBridgeManager.isRunningInAppBundle() == false)

        // Default initializer must safely fall back to NoopLocalNotificationCenter under `swift test`.
        let manager = NotificationBridgeManager()
        manager.start()
        let authorized = await manager.requestAuthorization()
        #expect(authorized == false)

        manager.handleEvent(NotificationEventPayload(
            action: .posted,
            items: [
                RemoteNotificationItem(key: "k-safe", pkg: "com.demo", appLabel: "Demo", title: "Hi", text: "Hello", postTimeMs: 100)
            ]
        ))
        #expect(manager.activeNotifications.count == 1)
        manager.stop(clearDelivered: true)
        #expect(manager.activeNotifications.isEmpty)
    }

    @Test("NotificationBridgeManager manages snapshot, deduplication, OTP auto-copy, categories, and user actions")
    func notificationBridgeLifecycleAndActions() async {
        let center = MockLocalNotificationCenter()
        let clipboard = MockClipboardWriter()

        let openedApps = OSAllocatedUnfairLock<[(String, String)]>(initialState: [])
        let dismissedKeys = OSAllocatedUnfairLock<[String]>(initialState: [])
        let replies = OSAllocatedUnfairLock<[(String, String)]>(initialState: [])
        let copiedOtps = OSAllocatedUnfairLock<[String]>(initialState: [])

        let manager = NotificationBridgeManager(
            notificationCenter: center,
            clipboardWriter: clipboard,
            callbacks: NotificationBridgeManager.Callbacks(
                onOpenApp: { pkg, label in openedApps.withLock { $0.append((pkg, label)) } },
                onDismissNotification: { key in dismissedKeys.withLock { $0.append(key) } },
                onReplyNotification: { key, text in replies.withLock { $0.append((key, text)) } },
                onOtpCopied: { code, _ in copiedOtps.withLock { $0.append(code) } }
            )
        )
        manager.autoCopyOtpToClipboard = true
        manager.suppressedPackages = ["com.muted.app"]
        manager.start()
        #expect(center.state.withLock { $0.registerCount } == 1)

        // 1. Initial snapshot seeds state without spamming banners
        let existing = RemoteNotificationItem(
            key: "old-1",
            pkg: "com.android.mms",
            appLabel: "短信",
            title: "旧消息",
            text: "已读历史通知",
            postTimeMs: 1_000
        )
        manager.handleEvent(NotificationEventPayload(action: .snapshot, items: [existing]))
        #expect(manager.activeNotifications.map(\.key) == ["old-1"])
        #expect(center.state.withLock { $0.posted.isEmpty })

        // 2. Posted OTP SMS (with Android omitting otpCode) -> local OtpExtractor fills it, auto-copies, and uses COPY_OTP category
        let otpSms = RemoteNotificationItem(
            key: "sms-otp",
            pkg: "com.android.mms",
            appLabel: "短信",
            title: "10690001",
            text: "【OpalDesk】您的登录验证码是 583920，5分钟内有效。",
            postTimeMs: 2_000,
            clearable: true,
            canReply: false,
            otpCode: ""
        )
        manager.handleEvent(NotificationEventPayload(action: .posted, items: [otpSms]))
        let postedAfterOtp = await center.waitForPostedCount(1)
        #expect(postedAfterOtp.count == 1)
        #expect(postedAfterOtp[0].categoryIdentifier == NotificationBridgeIdentifiers.categoryOtp)
        #expect(postedAfterOtp[0].userInfo[NotificationBridgeIdentifiers.userInfoOtpCode] == "583920")
        #expect(clipboard.written.withLock { $0 } == ["583920"])
        #expect(copiedOtps.withLock { $0 } == ["583920"])
        #expect(manager.activeNotifications.first?.otpCode == "583920")

        // 3. Re-posting identical notification is deduplicated (no second banner or clipboard write)
        manager.handleEvent(NotificationEventPayload(action: .posted, items: [otpSms]))
        try? await Task.sleep(nanoseconds: 30_000_000)
        #expect(center.state.withLock { $0.posted.count } == 1)
        #expect(clipboard.written.withLock { $0.count } == 1)

        // 4. Posted replyable chat notification -> INLINE_REPLY category; muted package is not bannered
        let wechatMsg = RemoteNotificationItem(
            key: "wx-1",
            pkg: "com.tencent.mm",
            appLabel: "微信",
            title: "产品群",
            text: "发版准备好了吗？",
            postTimeMs: 3_000,
            canReply: true,
            replyLabel: "回复"
        )
        let mutedMsg = RemoteNotificationItem(
            key: "muted-1",
            pkg: "com.muted.app",
            appLabel: "Muted",
            title: "Promo",
            text: "Ad",
            postTimeMs: 3_500
        )
        manager.handleEvent(NotificationEventPayload(action: .posted, items: [wechatMsg, mutedMsg]))
        let postedAfterChat = await center.waitForPostedCount(2)
        #expect(postedAfterChat.count == 2)
        #expect(postedAfterChat[1].categoryIdentifier == NotificationBridgeIdentifiers.categoryReply)
        #expect(manager.activeNotifications.map(\.key) == ["muted-1", "wx-1", "sms-otp", "old-1"])

        // 5. User clicks notification -> opens app popout
        center.simulateUserAction(.opened(key: "wx-1", pkg: "com.tencent.mm", appLabel: "微信"))
        #expect(openedApps.withLock { $0.count } == 1)
        #expect(openedApps.withLock { $0[0].0 } == "com.tencent.mm")
        #expect(openedApps.withLock { $0[0].1 } == "微信")

        // 6. User sends inline reply (whitespace-only ignored)
        center.simulateUserAction(.inlineReply(key: "wx-1", pkg: "com.tencent.mm", replyText: "   "))
        center.simulateUserAction(.inlineReply(key: "wx-1", pkg: "com.tencent.mm", replyText: " 准备好了！ "))
        #expect(replies.withLock { $0.count } == 1)
        #expect(replies.withLock { $0[0].0 } == "wx-1")
        #expect(replies.withLock { $0[0].1 } == "准备好了！")

        // 7. User clicks COPY_OTP manually
        center.simulateUserAction(.copyOtp(key: "sms-otp", otpCode: "583920"))
        #expect(clipboard.written.withLock { $0 } == ["583920", "583920"])

        // 8. User dismisses notification locally -> removes from list & fires dismiss callback
        center.simulateUserAction(.dismissed(key: "wx-1"))
        #expect(dismissedKeys.withLock { $0 } == ["wx-1"])
        #expect(!manager.activeNotifications.contains(where: { $0.key == "wx-1" }))

        // 9. Android sends removed event -> removes from list & clears delivered macOS notification
        manager.handleEvent(NotificationEventPayload(action: .removed, key: "sms-otp"))
        #expect(!manager.activeNotifications.contains(where: { $0.key == "sms-otp" }))
        #expect(center.state.withLock { $0.removedIds.flatMap { $0 } }.contains(NotificationBridgeManager.localIdentifier(forRemoteKey: "sms-otp")))
    }
}
