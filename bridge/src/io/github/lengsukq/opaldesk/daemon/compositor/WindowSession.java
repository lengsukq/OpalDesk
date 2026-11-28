package io.github.lengsukq.opaldesk.daemon.compositor;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.UserProfileAppPolicy;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

/**
 * State container for a single desktop window in the Universal Per-Window Compositor.
 * Pure Java 11 so all compositor state transitions and JSON snapshots are 100% unit-testable.
 */
public final class WindowSession {
    public final int windowId;
    public final String pkg;
    public final int userId;
    public final String profileType;
    public String activityClass;
    public boolean pendingActivitySwitch = false;

    public String title;
    public WindowProfile profile;
    public String state;          // LIVE | HIBERNATED | MINIMIZED
    public String presentation;   // FLOATING | MAXIMIZED | SNAP_*
    public IntRect frame;         // Outer window frame on compositor canvas (includes header chrome)
    public IntRect restoreFrame;  // Saved floating frame prior to maximize/snap
    public IntRect preFullscreenFrame; // Exact frame immediately before entering fullscreen
    public String preFullscreenPresentation; // Exact presentation immediately before entering fullscreen

    public int contentWidth;      // VirtualDisplay internal pixel width
    public int contentHeight;     // VirtualDisplay internal pixel height
    public int contentDpi;        // VirtualDisplay internal densityDpi

    public int displayId = -1;
    public String vdUniqueId = "";
    public int taskId = -1;
    public int zOrder = 0;
    public boolean focused = false;
    public long lastActiveAtMs = 0L;
    public long lastLaunchAtMs = 0L;
    public int emptyDisplayTicks = 0;
    public long emptySinceMs = 0L;
    public long snapshotVersion = 0L;
    public byte[] hibernatedSnapshotBytes;

    public WindowSession(
            int windowId,
            String pkg,
            String activityClass,
            String title,
            WindowProfile profile,
            IntRect initialFrame,
            int contentWidth,
            int contentHeight,
            int contentDpi,
            long nowMs) {
        this(windowId, pkg, UserProfileAppPolicy.PRIMARY_USER_ID, UserProfileAppPolicy.PROFILE_PRIMARY,
                activityClass, title, profile, initialFrame, contentWidth, contentHeight, contentDpi, nowMs);
    }

    public WindowSession(
            int windowId,
            String pkg,
            int userId,
            String profileType,
            String activityClass,
            String title,
            WindowProfile profile,
            IntRect initialFrame,
            int contentWidth,
            int contentHeight,
            int contentDpi,
            long nowMs) {
        this.windowId = windowId;
        this.pkg = pkg != null ? pkg : "";
        this.userId = Math.max(0, userId);
        this.profileType = UserProfileAppPolicy.normalizeProfileType(profileType, this.userId);
        this.activityClass = activityClass != null ? activityClass : "";
        this.title = (title != null && !title.isEmpty()) ? title : this.pkg;
        this.profile = (profile != null) ? profile : WindowProfile.DESKTOP;
        // Start in LIVE so client mounts TextureView and calls attachWindowSurface() immediately;
        // isLive() remains false until displayId > 0 is assigned.
        this.state = BridgeProtocol.STATE_LIVE;
        this.presentation = BridgeProtocol.PRES_FLOATING;
        this.frame = initialFrame;
        this.restoreFrame = initialFrame;
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
        this.contentDpi = contentDpi;
        this.lastActiveAtMs = nowMs;
        this.lastLaunchAtMs = nowMs;
    }

    public boolean matchesApp(String targetPkg, int targetUserId) {
        return targetPkg != null && targetPkg.equals(this.pkg) && this.userId == Math.max(0, targetUserId);
    }

    public String appKey() {
        return UserProfileAppPolicy.formatAppKey(pkg, userId);
    }

    public boolean isLive() {
        return BridgeProtocol.STATE_LIVE.equals(state) && displayId > 0;
    }

    public boolean isMinimized() {
        return BridgeProtocol.STATE_MINIMIZED.equals(state);
    }

    public boolean isHibernated() {
        return BridgeProtocol.STATE_HIBERNATED.equals(state);
    }

    public String toJson() {
        SimpleJson.ObjectBuilder b = new SimpleJson.ObjectBuilder()
                .put("windowId", windowId)
                .put("pkg", pkg)
                .put("userId", userId)
                .put("cls", activityClass)
                .put("title", title)
                .put("profile", profile.name())
                .put("profileCode", profile.code)
                .put("state", state)
                .put("presentation", presentation)
                .put("left", frame.left)
                .put("top", frame.top)
                .put("right", frame.right)
                .put("bottom", frame.bottom)
                .put("contentW", contentWidth)
                .put("contentH", contentHeight)
                .put("contentDpi", contentDpi)
                .put("displayId", displayId)
                .put("taskId", taskId)
                .put("zOrder", zOrder)
                .put("focused", focused)
                .put("snapshotVer", snapshotVersion)
                .put("hasSnapshot", hibernatedSnapshotBytes != null && hibernatedSnapshotBytes.length > 0);
        if (!profileType.isEmpty()) {
            b.put("profileType", profileType);
        }
        return b.build();
    }
}
