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

    /** Lightweight snapshot of an active remote session for single-owner audio arbitration. */
    public static final class AudioSessionCandidate {
        public final int sessionId;
        public final boolean standalone;
        public final boolean audioRequested;

        public AudioSessionCandidate(int sessionId, boolean standalone, boolean audioRequested) {
            this.sessionId = sessionId;
            this.standalone = standalone;
            this.audioRequested = audioRequested;
        }
    }

    /**
     * Selects the single remote session that owns the shared {@link AudioStreamCapturer} stream,
     * preventing duplicate playback echo when Desktop and 1–3 Standalone app windows are open
     * concurrently while ensuring Standalone-only sessions seamlessly receive audio.
     *
     * <p>Priority order:
     * <ol>
     *   <li>Active desktop session ({@code desktopSessionId}) when it requests audio.</li>
     *   <li>Current audio owner ({@code currentOwnerSessionId}) when still live and requesting audio
     *       (sticky among standalone windows to avoid glitches when opening additional windows).</li>
     *   <li>Oldest live session (smallest positive {@code sessionId}) requesting audio.</li>
     *   <li>{@code -1} when no session requests audio (caller stops capture).</li>
     * </ol>
     */
    public static int selectAudioOwnerSessionId(
            int currentOwnerSessionId,
            int desktopSessionId,
            Collection<AudioSessionCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return -1;
        }
        int stickyOwner = -1;
        int fallbackSmallest = Integer.MAX_VALUE;
        for (AudioSessionCandidate c : candidates) {
            if (c == null || c.sessionId <= 0 || !c.audioRequested) {
                continue;
            }
            if (!c.standalone && desktopSessionId > 0 && c.sessionId == desktopSessionId) {
                return c.sessionId;
            }
            if (currentOwnerSessionId > 0 && c.sessionId == currentOwnerSessionId) {
                stickyOwner = c.sessionId;
            }
            if (c.sessionId < fallbackSmallest) {
                fallbackSmallest = c.sessionId;
            }
        }
        if (stickyOwner > 0) {
            return stickyOwner;
        }
        return (fallbackSmallest != Integer.MAX_VALUE) ? fallbackSmallest : -1;
    }

    public static boolean uidListsEqual(List<Integer> a, List<Integer> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        return a.equals(b);
    }
}
