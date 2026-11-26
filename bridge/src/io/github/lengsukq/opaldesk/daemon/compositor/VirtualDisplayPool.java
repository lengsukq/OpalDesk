package io.github.lengsukq.opaldesk.daemon.compositor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * LRU VirtualDisplay Pool and Automatic Snapshot Hibernation Manager.
 *
 * <p>Solves Android 14+ {@code VirtualDisplayAdapter.mMaxDevicesPerPackage} quota limits without Root:
 * keeps up to {@code maxActiveDisplays} foreground windows backed by live {@code VirtualDisplay}s,
 * and seamlessly hibernates the oldest background window (capturing its {@code TaskSnapshot} frame
 * first) whenever a new or woken window needs a display slot. Also adapts dynamically if a strict
 * OEM ROM enforces a lower per-UID display ceiling at runtime.
 */
public final class VirtualDisplayPool {

    /** Platform abstraction over Android VirtualDisplay & TaskSnapshot APIs (mockable in JVM tests). */
    public interface DisplayDriver {
        final class CreatedDisplay {
            public final Object handle;
            public final int displayId;
            public final String uniqueId;

            public CreatedDisplay(Object handle, int displayId, String uniqueId) {
                this.handle = handle;
                this.displayId = displayId;
                this.uniqueId = uniqueId != null ? uniqueId : "";
            }
        }

        CreatedDisplay createVirtualDisplay(
                String name, int width, int height, int dpi, float fps, Object surface, boolean allowSecure);

        void resizeVirtualDisplay(Object handle, int width, int height, int dpi);

        void setVirtualDisplaySurface(Object handle, Object surface);

        default void setSurfaceFrameRate(Object surface, float fps) {}

        void releaseVirtualDisplay(Object handle, int displayId);

        byte[] captureTaskSnapshotBytes(int taskId);
    }

    public static final class Lease {
        public final int windowId;
        public final Object handle;
        public final int displayId;
        public final String uniqueId;

        Lease(int windowId, Object handle, int displayId, String uniqueId) {
            this.windowId = windowId;
            this.handle = handle;
            this.displayId = displayId;
            this.uniqueId = uniqueId;
        }
    }

    public static final class RebindResult {
        public final Lease oldLease;
        public final Lease newLease;

        RebindResult(Lease oldLease, Lease newLease) {
            this.oldLease = oldLease;
            this.newLease = newLease;
        }
    }

    /** Callback notified right before a background window's VirtualDisplay is reclaimed by LRU eviction. */
    public interface EvictionListener {
        void onWindowHibernated(WindowSession victim, byte[] capturedSnapshot);
    }

    private final DisplayDriver driver;
    private final Map<Integer, Lease> activeLeases = new HashMap<>();
    private int configuredMaxDisplays;
    private int dynamicRuntimeCeiling;
    private boolean allowSecureFlag;

    public VirtualDisplayPool(DisplayDriver driver, int initialMaxDisplays, boolean allowSecureFlag) {
        this.driver = driver;
        this.configuredMaxDisplays = Math.max(2, initialMaxDisplays);
        this.dynamicRuntimeCeiling = this.configuredMaxDisplays;
        this.allowSecureFlag = allowSecureFlag;
    }

    public synchronized int getActiveCount() {
        return activeLeases.size();
    }

    public synchronized int getEffectiveMaxDisplays() {
        return Math.min(configuredMaxDisplays, dynamicRuntimeCeiling);
    }

    public synchronized void setConfiguredMaxDisplays(int maxDisplays) {
        this.configuredMaxDisplays = Math.max(2, Math.min(16, maxDisplays));
        this.dynamicRuntimeCeiling = this.configuredMaxDisplays;
    }

    public synchronized void setAllowSecureFlag(boolean allowSecure) {
        this.allowSecureFlag = allowSecure;
    }

    public synchronized Lease getLease(int windowId) {
        return activeLeases.get(windowId);
    }

    /**
     * Acquires or updates a live VirtualDisplay lease for {@code target}.
     * Evicts the oldest non-focused background window if the active pool is at capacity or if the
     * underlying OS rejects creation due to a stricter OEM VirtualDisplay limit.
     */
    public synchronized Lease acquireOrBind(
            WindowSession target,
            Object surface,
            int width,
            int height,
            int dpi,
            float fps,
            Collection<WindowSession> allSessions,
            EvictionListener evictionListener) {
        Lease existing = activeLeases.get(target.windowId);
        if (existing != null) {
            driver.resizeVirtualDisplay(existing.handle, width, height, dpi);
            driver.setVirtualDisplaySurface(existing.handle, surface);
            return existing;
        }

        while (activeLeases.size() >= getEffectiveMaxDisplays()) {
            if (!evictOldestBackgroundLocked(target.windowId, allSessions, evictionListener)) {
                break;
            }
        }

        String vdName = "OpalWin-" + target.windowId;
        DisplayDriver.CreatedDisplay created = driver.createVirtualDisplay(
                vdName, width, height, dpi, fps, surface, allowSecureFlag);

        // Dynamic OEM quota adaptation: if creation failed while >= 2 displays are active,
        // tighten dynamicRuntimeCeiling, hibernate one more LRU window, and retry once.
        if (created == null && activeLeases.size() >= 2) {
            dynamicRuntimeCeiling = activeLeases.size();
            System.err.println("OPALDESK_VD_QUOTA_ADAPT wid=" + target.windowId
                    + " newCeiling=" + dynamicRuntimeCeiling
                    + " active=" + activeLeases.size());
            if (evictOldestBackgroundLocked(target.windowId, allSessions, evictionListener)) {
                created = driver.createVirtualDisplay(
                        vdName, width, height, dpi, fps, surface, allowSecureFlag);
            }
        }

        if (created == null || created.displayId <= 0) {
            System.err.println("OPALDESK_VD_LEASE_FAIL wid=" + target.windowId
                    + " active=" + activeLeases.size()
                    + " ceiling=" + getEffectiveMaxDisplays());
            return null;
        }

        Lease lease = new Lease(target.windowId, created.handle, created.displayId, created.uniqueId);
        activeLeases.put(target.windowId, lease);
        return lease;
    }

    /**
     * Recreates an active window's {@code VirtualDisplay} on the current {@link DisplayDriver}
     * (e.g. migrating between default {@code DisplayManager} and an AOSP {@code VirtualDevice}
     * providing a {@code VirtualCamera}). Detaches {@code surface} from the old display first so
     * {@code BufferQueue} handoff never fails, keeps the old display alive until the caller finishes
     * moving the app task, and rolls back cleanly if new display creation fails.
     */
    public synchronized RebindResult recreateLease(
            WindowSession target,
            Object surface,
            int width,
            int height,
            int dpi,
            float fps) {
        if (target == null || surface == null) return null;
        Lease oldLease = activeLeases.get(target.windowId);
        if (oldLease == null) return null;

        driver.setVirtualDisplaySurface(oldLease.handle, null);
        String vdName = "OpalWin-" + target.windowId;
        DisplayDriver.CreatedDisplay created = driver.createVirtualDisplay(
                vdName, width, height, dpi, fps, surface, allowSecureFlag);
        if (created == null || created.displayId <= 0) {
            System.err.println("OPALDESK_VD_RECREATE_ROLLBACK wid=" + target.windowId
                    + " oldDisplayId=" + oldLease.displayId);
            driver.setVirtualDisplaySurface(oldLease.handle, surface);
            return null;
        }
        Lease newLease = new Lease(target.windowId, created.handle, created.displayId, created.uniqueId);
        activeLeases.put(target.windowId, newLease);
        return new RebindResult(oldLease, newLease);
    }

    public void finishReleaseOldLease(Lease oldLease) {
        if (oldLease != null) {
            driver.releaseVirtualDisplay(oldLease.handle, oldLease.displayId);
        }
    }

    public synchronized void resizeIfActive(int windowId, int width, int height, int dpi) {
        Lease lease = activeLeases.get(windowId);
        if (lease != null) {
            driver.resizeVirtualDisplay(lease.handle, width, height, dpi);
        }
    }

    public synchronized void updateSurfaceFrameRateIfActive(int windowId, Object surface, float fps) {
        Lease lease = activeLeases.get(windowId);
        if (lease != null && surface != null) {
            driver.setSurfaceFrameRate(surface, fps);
            driver.setVirtualDisplaySurface(lease.handle, surface);
        }
    }

    public synchronized void detachSurfaceOnly(int windowId) {
        Lease lease = activeLeases.get(windowId);
        if (lease != null) {
            driver.setVirtualDisplaySurface(lease.handle, null);
        }
    }

    public synchronized void releaseLease(int windowId) {
        Lease removed = activeLeases.remove(windowId);
        if (removed != null) {
            driver.releaseVirtualDisplay(removed.handle, removed.displayId);
        }
    }

    public synchronized void releaseAll() {
        for (Lease lease : activeLeases.values()) {
            driver.releaseVirtualDisplay(lease.handle, lease.displayId);
        }
        activeLeases.clear();
    }

    private boolean evictOldestBackgroundLocked(
            int protectedWindowId,
            Collection<WindowSession> candidates,
            EvictionListener listener) {
        if (candidates == null || activeLeases.isEmpty()) return false;
        WindowSession victim = null;
        for (WindowSession ws : candidates) {
            if (ws == null || ws.windowId == protectedWindowId) continue;
            if (!activeLeases.containsKey(ws.windowId)) continue;
            if (victim == null || compareEvictionPriority(ws, victim) < 0) {
                victim = ws;
            }
        }
        if (victim == null) return false;

        byte[] snap = null;
        if (victim.taskId > 0) {
            try {
                snap = driver.captureTaskSnapshotBytes(victim.taskId);
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_VD_HIBERNATE_SNAP_WARN wid=" + victim.windowId + ": " + e.getMessage());
            }
        }
        Lease lease = activeLeases.remove(victim.windowId);
        if (lease != null) {
            driver.releaseVirtualDisplay(lease.handle, lease.displayId);
        }
        System.out.println("OPALDESK_VD_LRU_HIBERNATE victimWid=" + victim.windowId
                + " pkg=" + victim.pkg
                + " displayId=" + (lease != null ? lease.displayId : -1)
                + " snapBytes=" + (snap != null ? snap.length : 0));
        if (listener != null) {
            listener.onWindowHibernated(victim, snap);
        }
        return true;
    }

    /** Minimized windows are evicted first; otherwise oldest {@code lastActiveAtMs} wins. */
    private static int compareEvictionPriority(WindowSession a, WindowSession b) {
        if (a.isMinimized() != b.isMinimized()) {
            return a.isMinimized() ? -1 : 1;
        }
        if (a.focused != b.focused) {
            return a.focused ? 1 : -1;
        }
        return Long.compare(a.lastActiveAtMs, b.lastActiveAtMs);
    }
}
