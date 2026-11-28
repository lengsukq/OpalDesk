import Foundation
import OpalDeskCore
import XCTest

final class MultiUserProfileTests: XCTestCase {
    func testRemoteAppInfoDecodesMultiUserFieldsAndPreservesUniqueIds() throws {
        let json = """
        {
          "apps": [
            {"pkg": "com.tencent.mm", "cls": ".ui.LauncherUI", "label": "微信", "runningOnExt": true, "taskId": 10},
            {"pkg": "com.tencent.mm", "cls": ".ui.LauncherUI", "label": "微信", "runningOnExt": false, "taskId": -1, "userId": 999, "profileType": "clone"},
            {"pkg": "com.slack.app", "label": "Slack", "userId": 10, "profileType": "work"}
          ]
        }
        """
        let payload = try AppListPayload.decode(from: Data(json.utf8))
        XCTAssertEqual(payload.apps.count, 3)

        let primary = payload.apps[0]
        XCTAssertEqual(primary.userId, 0)
        XCTAssertEqual(primary.profileType, "")
        XCTAssertFalse(primary.isSecondaryProfile)
        XCTAssertEqual(primary.appKey, "com.tencent.mm")
        XCTAssertNil(primary.profileBadgeText)

        let clone = payload.apps[1]
        XCTAssertEqual(clone.userId, 999)
        XCTAssertEqual(clone.profileType, "clone")
        XCTAssertTrue(clone.isSecondaryProfile)
        XCTAssertEqual(clone.appKey, "com.tencent.mm#u999")
        XCTAssertNotEqual(primary.id, clone.id)
        XCTAssertNotNil(clone.profileBadgeText)

        let work = payload.apps[2]
        XCTAssertEqual(work.userId, 10)
        XCTAssertEqual(work.profileType, "work")
        XCTAssertEqual(work.appKey, "com.slack.app#u10")

        let parsedPrimary = RemoteAppInfo.parseAppKey("com.tencent.mm")
        XCTAssertEqual(parsedPrimary.pkg, "com.tencent.mm")
        XCTAssertEqual(parsedPrimary.userId, 0)

        let parsedClone = RemoteAppInfo.parseAppKey("com.tencent.mm#u999")
        XCTAssertEqual(parsedClone.pkg, "com.tencent.mm")
        XCTAssertEqual(parsedClone.userId, 999)
    }

    func testHelloAndWindowActionPayloadsEncodeUserIdConditionally() throws {
        let primaryHello = try HelloPayload(
            clientName: "Mac",
            clientId: "cid",
            sessionRole: OpalCastSessionRole.standaloneApp.rawValue,
            appPackage: "com.tencent.mm"
        ).encode()
        let primaryObj = try XCTUnwrap(JSONSerialization.jsonObject(with: primaryHello) as? [String: Any])
        XCTAssertNil(primaryObj["appUserId"])

        let cloneHello = try HelloPayload(
            clientName: "Mac",
            clientId: "cid",
            sessionRole: OpalCastSessionRole.standaloneApp.rawValue,
            appPackage: "com.tencent.mm",
            appUserId: 999
        ).encode()
        let cloneObj = try XCTUnwrap(JSONSerialization.jsonObject(with: cloneHello) as? [String: Any])
        XCTAssertEqual(cloneObj["appUserId"] as? Int, 999)

        let launchPrimary = try WindowActionPayload.launchApp(pkg: "com.tencent.mm").encode()
        let launchPrimaryObj = try XCTUnwrap(JSONSerialization.jsonObject(with: launchPrimary) as? [String: Any])
        XCTAssertNil(launchPrimaryObj["userId"])

        let launchClone = try WindowActionPayload.launchApp(pkg: "com.tencent.mm", userId: 999).encode()
        let launchCloneObj = try XCTUnwrap(JSONSerialization.jsonObject(with: launchClone) as? [String: Any])
        XCTAssertEqual(launchCloneObj["userId"] as? Int, 999)
    }

    func testPopoutHistoryAndPreferencesIsolatePrimaryAndClonedApp() throws {
        let primary = PopoutHistoryEntry(
            appPackage: "com.tencent.mm",
            appLabel: "微信",
            deviceKey: "adb:dev1",
            deviceName: "Xiaomi",
            transportKind: "usb",
            lastOpenedAtMs: 1000
        )
        let clone = PopoutHistoryEntry(
            appPackage: "com.tencent.mm",
            appLabel: "微信",
            deviceKey: "adb:dev1",
            deviceName: "Xiaomi",
            transportKind: "usb",
            lastOpenedAtMs: 2000,
            appUserId: 999,
            appProfileType: "clone"
        )
        XCTAssertNotEqual(primary.id, clone.id)
        XCTAssertEqual(primary.id, "adb:dev1::com.tencent.mm")
        XCTAssertEqual(clone.id, "adb:dev1::com.tencent.mm#u999")

        var list = PopoutHistoryLogic.recordLaunch(existing: [], incoming: primary)
        list = PopoutHistoryLogic.recordLaunch(existing: list, incoming: clone)
        XCTAssertEqual(list.count, 2)

        list = PopoutHistoryLogic.updatePinPreference(
            existing: list,
            appPackage: "com.tencent.mm",
            deviceKey: "adb:dev1",
            pinnedOnTop: true,
            appUserId: 999
        )
        XCTAssertFalse(list.first(where: { $0.appUserId == 0 })!.pinnedOnTop)
        XCTAssertTrue(list.first(where: { $0.appUserId == 999 })!.pinnedOnTop)
    }

    func testDeepLinkAndShortcutBuilderSupportUserIdRoundTrip() throws {
        let url = try OpalDeskDeepLink.buildPopoutURL(
            packageName: "com.tencent.mm",
            label: "微信",
            deviceKey: "adb:dev1",
            userId: 999
        )
        XCTAssertTrue(url.absoluteString.contains("userId=999"))
        guard case let .popout(parsed) = try OpalDeskDeepLink.parse(url) else {
            return XCTFail("Expected popout route")
        }
        XCTAssertEqual(parsed.packageName, "com.tencent.mm")
        XCTAssertEqual(parsed.userId, 999)

        let id0 = AppShortcutBuilder.makeBundleIdentifier(packageName: "com.tencent.mm", deviceKey: "adb:dev1", userId: 0)
        let id999 = AppShortcutBuilder.makeBundleIdentifier(packageName: "com.tencent.mm", deviceKey: "adb:dev1", userId: 999)
        XCTAssertNotEqual(id0, id999)

        let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent("opaldesk-multiuser-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let primaryShortcut = try AppShortcutBuilder.createShortcut(
            spec: AppShortcutSpec(packageName: "com.tencent.mm", appLabel: "WeChat", deviceKey: "adb:dev1", userId: 0),
            in: tmpDir
        )
        let cloneShortcut = try AppShortcutBuilder.createShortcut(
            spec: AppShortcutSpec(packageName: "com.tencent.mm", appLabel: "WeChat", deviceKey: "adb:dev1", userId: 999, profileType: "clone"),
            in: tmpDir
        )
        XCTAssertNotEqual(primaryShortcut.bundleURL, cloneShortcut.bundleURL)
        let listed = try AppShortcutBuilder.listShortcuts(in: tmpDir)
        XCTAssertEqual(listed.count, 2)
        XCTAssertTrue(listed.contains { $0.appKey == "com.tencent.mm" && $0.userId == 0 })
        XCTAssertTrue(listed.contains { $0.appKey == "com.tencent.mm#u999" && $0.userId == 999 })
    }

    func testFuzzyMatcherMatchesSecondaryProfileKeywords() {
        let primary = RemoteAppInfo(pkg: "com.tencent.mm", label: "WeChat", userId: 0)
        let clone = RemoteAppInfo(pkg: "com.tencent.mm", label: "WeChat", userId: 999, profileType: "clone")
        let matched = AppFuzzyMatcher.filter([primary, clone], query: "分身")
        XCTAssertEqual(matched.map(\.appKey), ["com.tencent.mm#u999"])
    }
}
