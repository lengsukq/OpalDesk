package io.github.lengsukq.opaldesk.daemon.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Pure-JVM helpers for Per-App Audio Split ({@code AudioPolicy} + {@code RULE_MATCH_UID}):
 * normalizes DeX app UID sets, filters out core system UIDs ({@code < 10000}) so phone ringtones
 * and system alerts remain on the host phone, and provides a non-matching placeholder UID when
 * DeX currently has zero open windows.
 */
public final class AudioRoutingPolicy {
    /** Regular Android installable app UIDs start at Process.FIRST_APPLICATION_UID (10000). */
    public static final int MIN_APP_UID = 10_000;
    /** Impossible placeholder UID used when DeX has 0 open windows in SPLIT_BY_APP mode. */
    public static final int PLACEHOLDER_EMPTY_UID = 999_999;

    private AudioRoutingPolicy() {}

    /**
     * Returns a sorted, deduplicated list of valid app UIDs ({@code >= 10000}). When no valid
     * app UIDs are present, returns a single-element list containing {@link #PLACEHOLDER_EMPTY_UID}
     * so {@code AudioPolicy} matches no real phone apps while remaining active and hot-updatable.
     */
    public static List<Integer> normalizeTargetUids(Collection<Integer> rawUids) {
        TreeSet<Integer> sorted = new TreeSet<>();
        if (rawUids != null) {
            for (Integer uid : rawUids) {
                if (uid != null && uid >= MIN_APP_UID && uid != PLACEHOLDER_EMPTY_UID) {
                    sorted.add(uid);
                }
            }
        }
        if (sorted.isEmpty()) {
            return Collections.singletonList(PLACEHOLDER_EMPTY_UID);
        }
        return Collections.unmodifiableList(new ArrayList<>(sorted));
    }

    public static boolean uidListsEqual(List<Integer> a, List<Integer> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        return a.equals(b);
    }
}
