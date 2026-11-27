import CoreGraphics
import Foundation
import Testing
@testable import OpalDeskCore

@Suite("PopoutAppPreferencesStore & PopoutCompatibilityResolution")
struct PopoutAppPreferencesStoreTests {

    @Test("Favorite pinning, ordering, reordering, and per-device vs package-level fallback lookup")
    func favoritesOrderingAndFallbackLookup() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-popout-prefs-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileURL = tempDir.appendingPathComponent("popout_app_prefs_v1.json")
        let store = PopoutAppPreferencesStore(storageURL: fileURL)

        // Pin favorites on dev-A -> monotonic order 1, 2, 3
        let p1 = store.toggleFavorite(appPackage: "com.tencent.mm", deviceKey: "dev-A")
        let p2 = store.toggleFavorite(appPackage: "com.xingin.xhs", deviceKey: "dev-A")
        let p3 = store.toggleFavorite(appPackage: "com.ss.android.ugc.aweme", deviceKey: "dev-A")
        #expect(p1.isFavorite && p1.favoriteOrder == 1)
        #expect(p2.isFavorite && p2.favoriteOrder == 2)
        #expect(p3.isFavorite && p3.favoriteOrder == 3)
        #expect(store.favoritePackages(forDeviceKey: "dev-A") == [
            "com.tencent.mm",
            "com.xingin.xhs",
            "com.ss.android.ugc.aweme"
        ])

        // Reorder favorites
        store.reorderFavorites(["com.ss.android.ugc.aweme", "com.tencent.mm", "com.xingin.xhs"], forDeviceKey: "dev-A")
        #expect(store.favoritePackages(forDeviceKey: "dev-A") == [
            "com.ss.android.ugc.aweme",
            "com.tencent.mm",
            "com.xingin.xhs"
        ])

        // Unfavorite removes from list and resets order
        let unfav = store.toggleFavorite(appPackage: "com.tencent.mm", deviceKey: "dev-A")
        #expect(unfav.isFavorite == false)
        #expect(unfav.favoriteOrder == 0)
        #expect(store.favoritePackages(forDeviceKey: "dev-A") == [
            "com.ss.android.ugc.aweme",
            "com.xingin.xhs"
        ])

        // Device isolation vs package-level fallback lookup
        store.setPreset("fold", appPackage: "com.tencent.mm", deviceKey: "dev-A")
        store.setPreset("tablet", appPackage: "com.tencent.mm", deviceKey: "dev-B")

        #expect(store.preference(for: "com.tencent.mm", deviceKey: "dev-A")?.preferredPreset == "fold")
        #expect(store.preference(for: "com.tencent.mm", deviceKey: "dev-B")?.preferredPreset == "tablet")
        // Unknown device dev-C falls back to package-level entry (dev-A comes first alphabetically)
        #expect(store.preference(for: "com.tencent.mm", deviceKey: "dev-C")?.preferredPreset == "fold")
        // Explicit package-global entry (deviceKey == "") takes priority over other-device fallback
        store.setPreset("phone", appPackage: "com.tencent.mm", deviceKey: "")
        #expect(store.preference(for: "com.tencent.mm", deviceKey: "dev-C")?.preferredPreset == "phone")
        // Exact device entry still overrides global
        #expect(store.preference(for: "com.tencent.mm", deviceKey: "dev-B")?.preferredPreset == "tablet")
    }

    @Test("Preset, custom size, pin-on-top, DPI clamping, and compatibility resolution persist across reloads")
    func persistenceAcrossReloads() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-popout-persist-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileURL = tempDir.appendingPathComponent("popout_app_prefs_v1.json")
        let store = PopoutAppPreferencesStore(storageURL: fileURL)

        store.setCustomWindowSize(widthPt: 520, heightPt: 910, appPackage: "com.eg.android.AlipayGphone", deviceKey: "dev-1")
        store.setPinnedOnTop(true, appPackage: "com.eg.android.AlipayGphone", deviceKey: "dev-1")
        store.setCustomDpi(999, appPackage: "com.eg.android.AlipayGphone", deviceKey: "dev-1") // clamps to 480
        store.setCompatibilityResolution(true, appPackage: "com.eg.android.AlipayGphone", deviceKey: "dev-1")
        store.toggleFavorite(appPackage: "com.eg.android.AlipayGphone", deviceKey: "dev-1")

        // Reload fresh store from same JSON file on disk
        let reloaded = PopoutAppPreferencesStore(storageURL: fileURL)
        let pref = try #require(reloaded.exactPreference(for: "com.eg.android.AlipayGphone", deviceKey: "dev-1"))
        #expect(pref.isFavorite == true)
        #expect(pref.favoriteOrder == 1)
        #expect(pref.preferredPreset == "custom")
        #expect(pref.customWidthPt == 520)
        #expect(pref.customHeightPt == 910)
        #expect(pref.customWindowSize == CGSize(width: 520, height: 910))
        #expect(pref.pinnedOnTop == true)
        #expect(pref.customDpi == 480)
        #expect(pref.compatibilityResolution == true)

        // Low DPI clamps to 160
        let lowDpi = reloaded.setCustomDpi(80, appPackage: "com.eg.android.AlipayGphone", deviceKey: "dev-1")
        #expect(lowDpi.customDpi == 160)
    }

    @Test("PopoutCompatibilityResolution returns standard phone/fold/tablet geometries and respects DPI overrides")
    func compatibilityResolutionMath() {
        #expect(PopoutCompatibilityResolution.phone.streamGeometry == PopoutStreamGeometry(width: 1080, height: 2340, dpi: 420))
        #expect(PopoutCompatibilityResolution.fold.streamGeometry == PopoutStreamGeometry(width: 1440, height: 1600, dpi: 360))
        #expect(PopoutCompatibilityResolution.tablet.streamGeometry == PopoutStreamGeometry(width: 1600, height: 1000, dpi: 240))

        // Preset matching (including "foldable" alias)
        #expect(PopoutCompatibilityResolution.profile(forPreset: "phone") == .phone)
        #expect(PopoutCompatibilityResolution.profile(forPreset: "fold") == .fold)
        #expect(PopoutCompatibilityResolution.profile(forPreset: "foldable") == .fold)
        #expect(PopoutCompatibilityResolution.profile(forPreset: "tablet") == .tablet)

        // Aspect ratio inference when preset is nil or "custom"
        #expect(PopoutCompatibilityResolution.profile(forPreset: "custom", windowSize: CGSize(width: 420, height: 840)) == .phone)
        #expect(PopoutCompatibilityResolution.profile(forPreset: "custom", windowSize: CGSize(width: 640, height: 800)) == .fold)
        #expect(PopoutCompatibilityResolution.profile(forPreset: nil, windowSize: CGSize(width: 960, height: 640)) == .tablet)

        // resolveStreamGeometry with compatibilityResolution + customDpi override
        let compatPref = PopoutAppPreference(preferredPreset: "tablet", customDpi: 280, compatibilityResolution: true)
        let resolvedCompat = PopoutCompatibilityResolution.resolveStreamGeometry(
            preference: compatPref,
            viewSize: CGSize(width: 900, height: 600)
        )
        #expect(resolvedCompat == PopoutStreamGeometry(width: 1600, height: 1000, dpi: 280))

        // resolveStreamGeometry without compatibilityResolution uses CoordinateMapper + customDpi override
        let adaptivePref = PopoutAppPreference(customDpi: 320, compatibilityResolution: false)
        let expectedAdaptive = CoordinateMapper.popoutStreamGeometry(viewSize: CGSize(width: 420, height: 840), backingScale: 2.0)
        let resolvedAdaptive = PopoutCompatibilityResolution.resolveStreamGeometry(
            preference: adaptivePref,
            viewSize: CGSize(width: 420, height: 840),
            backingScale: 2.0
        )
        #expect(resolvedAdaptive == PopoutStreamGeometry(width: expectedAdaptive.width, height: expectedAdaptive.height, dpi: 320))
    }
}
