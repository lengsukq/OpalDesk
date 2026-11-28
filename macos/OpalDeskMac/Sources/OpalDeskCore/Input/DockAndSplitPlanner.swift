import Foundation

/// Grouped app items rendered inside the macOS-style floating Dock:
/// user-pinned favorites on the left and unpinned active desktop apps on the right.
public struct DockPartition: Sendable, Equatable {
    public let pinned: [RemoteAppInfo]
    public let running: [RemoteAppInfo]

    public init(pinned: [RemoteAppInfo], running: [RemoteAppInfo]) {
        self.pinned = pinned
        self.running = running
    }

    public var isEmpty: Bool {
        pinned.isEmpty && running.isEmpty
    }
}

/// Candidate app groups surfaced inside an unassigned or replacing split-screen slot.
public struct SplitCandidateGroups: Sendable, Equatable {
    public let recommended: [RemoteAppInfo]
    public let allMatches: [RemoteAppInfo]

    public init(recommended: [RemoteAppInfo], allMatches: [RemoteAppInfo]) {
        self.recommended = recommended
        self.allMatches = allMatches
    }
}

/// Pure, testable planning logic for macOS Dock item partitioning and split-screen candidate ranking.
public enum DockAndSplitPlanner {
    private static let maxRecommendedCandidates = 8

    /// Partitions Dock items into ordered pinned favorites (with live running metadata merged)
    /// and unpinned running desktop apps (excluding standalone popout apps).
    public static func partitionDockApps(
        remoteApps: [RemoteAppInfo],
        pinnedPackages: [String],
        runningDesktopApps: [RemoteAppInfo],
        poppedOutPackages: Set<String>
    ) -> DockPartition {
        var metadataByKey: [String: RemoteAppInfo] = [:]
        for app in remoteApps where !app.pkg.isEmpty {
            metadataByKey[app.appKey] = app
        }
        var runningByKey: [String: RemoteAppInfo] = [:]
        for app in runningDesktopApps where !app.pkg.isEmpty && !poppedOutPackages.contains(app.appKey) {
            runningByKey[app.appKey] = app
            if metadataByKey[app.appKey] == nil {
                metadataByKey[app.appKey] = app
            }
        }

        var pinned: [RemoteAppInfo] = []
        var pinnedSeen = Set<String>()
        for rawKey in pinnedPackages {
            let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, pinnedSeen.insert(key).inserted else { continue }
            let parsed = RemoteAppInfo.parseAppKey(key)
            var resolved = metadataByKey[key] ?? RemoteAppInfo(
                pkg: parsed.pkg,
                label: parsed.pkg,
                runningOnExt: false,
                userId: parsed.userId
            )
            if let live = runningByKey[key] {
                resolved.runningOnExt = true
                if live.taskId > 0 { resolved.taskId = live.taskId }
            } else if poppedOutPackages.contains(key) {
                resolved.runningOnExt = false
            }
            pinned.append(resolved)
        }

        var running: [RemoteAppInfo] = []
        var runningSeen = Set<String>()
        for app in runningDesktopApps {
            let key = app.appKey.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty,
                  !pinnedSeen.contains(key),
                  !poppedOutPackages.contains(key),
                  runningSeen.insert(key).inserted else {
                continue
            }
            running.append(app)
        }

        return DockPartition(pinned: pinned, running: running)
    }

    /// Computes quick-pick recommended candidates (running & pinned apps not already occupying
    /// another split slot) alongside fuzzy-filtered installed apps.
    public static func splitCandidateGroups(
        remoteApps: [RemoteAppInfo],
        pinnedPackages: [String],
        occupiedSlots: [String],
        query: String
    ) -> SplitCandidateGroups {
        let occupiedSet = Set(
            occupiedSlots
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
        )
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let pinnedRank = Dictionary(
            uniqueKeysWithValues: pinnedPackages.enumerated().compactMap { idx, raw -> (String, Int)? in
                let pkg = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                return pkg.isEmpty ? nil : (pkg, idx)
            }
        )

        guard trimmedQuery.isEmpty else {
            let filtered = AppFuzzyMatcher.filter(remoteApps, query: trimmedQuery)
            let prioritized = filtered.sorted { lhs, rhs in
                let lhsOccupied = occupiedSet.contains(lhs.pkg)
                let rhsOccupied = occupiedSet.contains(rhs.pkg)
                if lhsOccupied != rhsOccupied { return !lhsOccupied }
                return false
            }
            return SplitCandidateGroups(recommended: [], allMatches: prioritized)
        }

        var recommended: [RemoteAppInfo] = []
        var recSeen = Set<String>()

        // 1. Running desktop apps not yet assigned to any split slot
        for app in remoteApps where app.runningOnExt && !occupiedSet.contains(app.appKey) && !occupiedSet.contains(app.pkg) {
            if recSeen.insert(app.appKey).inserted {
                recommended.append(app)
            }
        }
        // 2. Pinned Dock favorites not yet assigned
        let pinnedCandidates = remoteApps
            .filter { pinnedRank[$0.appKey] != nil && !occupiedSet.contains($0.appKey) && !recSeen.contains($0.appKey) }
            .sorted { (pinnedRank[$0.appKey] ?? Int.max) < (pinnedRank[$1.appKey] ?? Int.max) }
        for app in pinnedCandidates {
            if recSeen.insert(app.appKey).inserted {
                recommended.append(app)
            }
        }
        if recommended.count > maxRecommendedCandidates {
            recommended = Array(recommended.prefix(maxRecommendedCandidates))
        }

        let orderedAll = remoteApps.sorted { lhs, rhs in
            let lhsOccupied = occupiedSet.contains(lhs.appKey) || (lhs.userId == 0 && occupiedSet.contains(lhs.pkg))
            let rhsOccupied = occupiedSet.contains(rhs.appKey) || (rhs.userId == 0 && occupiedSet.contains(rhs.pkg))
            if lhsOccupied != rhsOccupied { return !lhsOccupied }
            if lhs.runningOnExt != rhs.runningOnExt { return lhs.runningOnExt }
            let lhsPinned = pinnedRank[lhs.appKey] != nil
            let rhsPinned = pinnedRank[rhs.appKey] != nil
            if lhsPinned != rhsPinned { return lhsPinned }
            return lhs.displayLabel.localizedCaseInsensitiveCompare(rhs.displayLabel) == .orderedAscending
        }

        return SplitCandidateGroups(recommended: recommended, allMatches: orderedAll)
    }
}
