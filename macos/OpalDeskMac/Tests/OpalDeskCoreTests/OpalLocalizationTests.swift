import Foundation
import XCTest
@testable import OpalDeskCore

final class OpalLocalizationTests: XCTestCase {
    func testExplicitPreferenceOverridesSystemLanguages() {
        XCTAssertEqual(
            OpalL10n.resolveLanguage(preference: .en, preferredLanguages: ["zh-Hans-CN", "zh-CN"]),
            .en
        )
        XCTAssertEqual(
            OpalL10n.resolveLanguage(preference: .zhHans, preferredLanguages: ["en-US", "fr-FR"]),
            .zhHans
        )
    }

    func testSystemPreferenceResolvesChineseVariantsToSimplifiedChinese() {
        let chineseTags = ["zh", "zh-CN", "zh-Hans", "zh-Hans-CN", "zh-SG", "zh-TW", "zh-HK", "zh_CN", "ZH-HANS"]
        for tag in chineseTags {
            XCTAssertEqual(
                OpalL10n.resolveLanguage(preference: .system, preferredLanguages: [tag, "en-US"]),
                .zhHans,
                "Expected '\(tag)' to resolve to .zhHans"
            )
        }
    }

    func testSystemPreferenceFallsBackToEnglishForNonChineseLocales() {
        let nonChineseLists: [[String]] = [
            ["en-US"],
            ["en-GB", "zh-Hans-CN"],
            ["ja-JP"],
            ["ko-KR"],
            ["fr-FR"],
            ["de-DE"],
            [],
            ["   "],
        ]
        for langs in nonChineseLists {
            XCTAssertEqual(
                OpalL10n.resolveLanguage(preference: .system, preferredLanguages: langs),
                .en,
                "Expected \(langs) to fall back to .en"
            )
        }
    }

    func testBundleLoadsEnglishAndSimplifiedChineseCopy() {
        XCTAssertEqual(OpalL10n.text("lang.system", language: .en), "Follow System")
        XCTAssertEqual(OpalL10n.text("lang.system", language: .zhHans), "跟随系统")

        XCTAssertEqual(OpalL10n.text("lang.title", language: .en), "Language")
        XCTAssertEqual(OpalL10n.text("lang.title", language: .zhHans), "语言")

        XCTAssertEqual(OpalL10n.text("menu.quit_opaldesk", language: .en), "Quit OpalDesk")
        XCTAssertEqual(OpalL10n.text("menu.quit_opaldesk", language: .zhHans), "退出 OpalDesk")

        XCTAssertEqual(OpalL10n.text("files.tab.files", language: .en), "Phone Files")
        XCTAssertEqual(OpalL10n.text("files.tab.files", language: .zhHans), "手机文件管理")

        XCTAssertEqual(OpalL10n.text("files.tab.storage", language: .en), "Storage Analyzer")
        XCTAssertEqual(OpalL10n.text("files.tab.storage", language: .zhHans), "存储空间分析")

        XCTAssertEqual(OpalL10n.text("call.status.ringing", language: .en), "Incoming Call")
        XCTAssertEqual(OpalL10n.text("call.status.ringing", language: .zhHans), "来电响铃")
    }

    func testFormattedLocalizationAndMissingKeyFallback() {
        XCTAssertEqual(
            OpalL10n.format("hub.history.open_count_fmt", language: .en, 2, 3),
            "Open 2/3"
        )
        XCTAssertEqual(
            OpalL10n.format("hub.history.open_count_fmt", language: .zhHans, 2, 3),
            "已开 2/3"
        )
        XCTAssertEqual(
            OpalL10n.text("nonexistent.test.key", language: .en),
            "nonexistent.test.key"
        )
        XCTAssertEqual(
            OpalL10n.text("nonexistent.test.key", language: .zhHans),
            "nonexistent.test.key"
        )
    }

    func testEnglishAndSimplifiedChineseStringsHave100PercentKeyParity() throws {
        let enBundle = OpalL10n.localizationBundle(for: .en)
        let zhBundle = OpalL10n.localizationBundle(for: .zhHans)
        XCTAssertNotEqual(
            enBundle.bundleURL.standardizedFileURL,
            zhBundle.bundleURL.standardizedFileURL,
            "Simplified Chinese localization bundle must resolve distinctly from English bundle"
        )

        let enURL = try XCTUnwrap(
            enBundle.url(forResource: "Localizable", withExtension: "strings"),
            "Missing Localizable.strings in en.lproj localization bundle"
        )
        let zhURL = try XCTUnwrap(
            zhBundle.url(forResource: "Localizable", withExtension: "strings"),
            "Missing Localizable.strings in zh-Hans/zh-hans.lproj localization bundle"
        )

        let enDict = try XCTUnwrap(NSDictionary(contentsOf: enURL) as? [String: String])
        let zhDict = try XCTUnwrap(NSDictionary(contentsOf: zhURL) as? [String: String])

        XCTAssertGreaterThan(enDict.count, 300, "Expected comprehensive localization catalog")

        let enKeys = Set(enDict.keys)
        let zhKeys = Set(zhDict.keys)
        let missingInZh = enKeys.subtracting(zhKeys).sorted()
        let missingInEn = zhKeys.subtracting(enKeys).sorted()

        XCTAssertTrue(missingInZh.isEmpty, "Keys present in en.lproj but missing in zh-Hans.lproj: \(missingInZh)")
        XCTAssertTrue(missingInEn.isEmpty, "Keys present in zh-Hans.lproj but missing in en.lproj: \(missingInEn)")

        for (key, value) in enDict {
            XCTAssertFalse(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "Empty en value for key '\(key)'")
        }
        for (key, value) in zhDict {
            XCTAssertFalse(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "Empty zh-Hans value for key '\(key)'")
        }
    }
}
