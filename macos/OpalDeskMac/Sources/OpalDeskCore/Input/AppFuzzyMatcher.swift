import Foundation

/// Pure fuzzy matching and ranking helper for installed/running Android apps (used by Quick Launcher).
public enum AppFuzzyMatcher {
    private enum Score {
        static let exactLabel = 1000
        static let labelPrefix = 850
        static let labelContains = 650
        static let packageContains = 450
        static let labelSubsequence = 300
        static let packageSubsequence = 180
    }

    /// Filters and ranks `apps` by relevance to `query`, preserving original order when `query` is blank.
    public static func filter(_ apps: [RemoteAppInfo], query: String) -> [RemoteAppInfo] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return apps }
        let needle = trimmed.lowercased()
        return apps
            .compactMap { app -> (app: RemoteAppInfo, score: Int)? in
                let value = score(normalizedNeedle: needle, app: app)
                return value > 0 ? (app, value) : nil
            }
            .sorted { lhs, rhs in
                if lhs.score != rhs.score { return lhs.score > rhs.score }
                if lhs.app.runningOnExt != rhs.app.runningOnExt { return lhs.app.runningOnExt }
                return lhs.app.displayLabel.localizedCaseInsensitiveCompare(rhs.app.displayLabel) == .orderedAscending
            }
            .map(\.app)
    }

    public static func score(needle: String, app: RemoteAppInfo) -> Int {
        let normalized = needle.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalized.isEmpty else { return 0 }
        return score(normalizedNeedle: normalized, app: app)
    }

    public static func isSubsequence(_ needle: String, of haystack: String) -> Bool {
        var idx = haystack.startIndex
        for ch in needle {
            guard let found = haystack[idx...].firstIndex(of: ch) else {
                return false
            }
            idx = haystack.index(after: found)
        }
        return true
    }

    private static func score(normalizedNeedle needle: String, app: RemoteAppInfo) -> Int {
        let label = app.displayLabel.lowercased()
        let pkg = app.pkg.lowercased()

        if label == needle { return Score.exactLabel }
        if label.hasPrefix(needle) { return Score.labelPrefix }
        if label.contains(needle) { return Score.labelContains }
        if pkg.contains(needle) { return Score.packageContains }
        if app.isSecondaryProfile {
            if let badge = app.profileBadgeText?.lowercased(), badge.contains(needle) {
                return Score.packageContains
            }
            if RemoteAppInfo.profileSearchKeywords(userId: app.userId, profileType: app.profileType)
                .contains(where: { $0.lowercased().contains(needle) })
            {
                return Score.packageContains
            }
        }
        if isSubsequence(needle, of: label) { return Score.labelSubsequence }
        if isSubsequence(needle, of: pkg) { return Score.packageSubsequence }
        return 0
    }
}
