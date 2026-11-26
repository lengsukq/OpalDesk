package io.github.lengsukq.opaldesk.daemon.compositor;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Core Wayland-style Universal Window Compositor Engine.
 *
 * <p>Each managed window owns an isolated {@code VirtualDisplay} running standard
 * {@code WINDOWING_MODE_FULLSCREEN (1)}, completely bypassing OEM freeform/multi-window APIs.
 * Pure Java 11 with injected {@link AppRuntimeBridge} and {@link VirtualDisplayPool} so 100% of
 * window lifecycle, Z-order, snap zones, and LRU snapshot hibernation are JVM unit-testable.
 */
public final class CompositorEngine {

    /** Platform bridge for launching/moving/removing fullscreen Android tasks on a displayId. */
    public interface AppRuntimeBridge {
        String resolveAppLabel(String pkg);

        /**
         * Launches or moves {@code pkg}/{@code cls} onto {@code displayId} in standard
         * {@code WINDOWING_MODE_FULLSCREEN (1)} and returns the resolved {@code taskId} (or {@code -1}).
         */
        int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId);

        /**
         * Cold-launches {@code pkg}/{@code cls} directly onto {@code displayId} without reusing
         * a stale Recents task (used after {@link #forceStopPackage(String)} during window reload).
         */
        default int relaunchFreshFullscreenApp(String pkg, String cls, int displayId) {
            return launchOrMoveFullscreenApp(pkg, cls, displayId, -1);
        }

        /** Queries the active user taskId currently running on {@code displayId} (or {@code -1}). */
        int findTopTaskIdOnDisplay(int displayId, String expectedPkg);

        /** Queries every running Android taskId currently hosted on {@code displayId}. */
        default List<Integer> findAllTaskIdsOnDisplay(int displayId) {
            return Collections.emptyList();
        }

        /**
         * Queries the currently visible & active taskId on {@code displayId} (returns {@code -1}
         * when the app finished or moved itself to back on root Back press).
         */
        default int findTopVisibleTaskIdOnDisplay(int displayId, String expectedPkg) {
            return findTopTaskIdOnDisplay(displayId, expectedPkg);
        }

        /**
         * Returns {@code true} if the task is still alive with at least one running Activity
         * (e.g. {@code moveTaskToBack(true)}), {@code false} if the app exited completely.
         */
        default boolean isTaskRunningWithActivities(int taskId, int displayId) {
            Set<Integer> alive = collectAliveTaskIds();
            return alive != null && alive.contains(taskId);
        }

        /** Returns the set of all currently alive Android taskIds across the system. */
        Set<Integer> collectAliveTaskIds();

        /** Removes a task completely when the user closes its desktop window. */
        void removeTask(int taskId);

        /** Force-stops and relaunches an app cleanly inside its VirtualDisplay. */
        void forceStopPackage(String pkg);

        /** Moves Android display focus onto {@code displayId} when resuming an existing lease. */
        default void focusDisplay(int displayId) {}

        /** Launches a specific sub-activity (e.g. Wi-Fi / Bluetooth settings) inside an already-live VirtualDisplay. */
        default int launchExplicitActivityOnDisplay(String pkg, String cls, int displayId) {
            return launchOrMoveFullscreenApp(pkg, cls, displayId, -1);
        }

        /** Invoked when all client TextureView surfaces have detached so Display 0 top focus can be restored. */
        default void onAllClientSurfacesDetached() {}

        /** Releases any per-display UHID or input resources when a VirtualDisplay is torn down. */
        void onDisplayReleased(int displayId);

        /** Releases an Android Surface handle when no longer referenced. */
        void releaseSurfaceHandle(Object surface);

        /** Returns the host device's physical Display 0 densityDpi (or 0 if unavailable in unit tests). */
        default int getHostDisplayDpi() {
            return 0;
        }
    }

    /** Sustained empty-display settle window so fullscreen/streaming Activity transitions never trigger false close/minimize. */
    public static final long EMPTY_DISPLAY_SETTLE_MS = 900L;

    private final Object lock = new Object();
    private final DaemonConfig config;
    private final VirtualDisplayPool vdPool;
    private final AppRuntimeBridge runtimeBridge;
    private final Runnable onSceneDirty;

    private final Map<Integer, WindowSession> sessionsById = new LinkedHashMap<>();
    private final Map<Integer, Object> attachedSurfaces = new LinkedHashMap<>();
    private final AtomicInteger nextWindowId = new AtomicInteger(1);
    private final AtomicLong stateSeq = new AtomicLong(1L);
    private int nextZOrder = 1;
    private volatile IntConsumer backReconcileScheduler;

    public CompositorEngine(
            DaemonConfig config,
            VirtualDisplayPool vdPool,
            AppRuntimeBridge runtimeBridge,
            Runnable onSceneDirty) {
        this.config = config;
        this.vdPool = vdPool;
        this.runtimeBridge = runtimeBridge;
        this.onSceneDirty = onSceneDirty;
    }

    public void setBackReconcileScheduler(IntConsumer scheduler) {
        this.backReconcileScheduler = scheduler;
    }

    /** Notifies the engine that a Back key was dispatched to {@code windowId} so root-Back exit is detected immediately. */
    public void notifyBackSent(int windowId) {
        IntConsumer sched = this.backReconcileScheduler;
        if (sched != null && windowId > 0) {
            sched.accept(windowId);
        }
    }

    public long getStateSeq() {
        return stateSeq.get();
    }

    public int getActiveDisplayCount() {
        return vdPool.getActiveCount();
    }

    public int getEffectiveMaxDisplays() {
        return vdPool.getEffectiveMaxDisplays();
    }

    public void setConfiguredMaxDisplays(int maxDisplays) {
        vdPool.setConfiguredMaxDisplays(maxDisplays);
    }

    /** Recomputes buffer resolution & DPI for all open windows after global/per-app DPI changes. */
    public void reapplyWindowDpiScale() {
        synchronized (lock) {
            int dpiPct = config.getWindowDpiScalePct();
            boolean changed = false;
            for (WindowSession ws : sessionsById.values()) {
                boolean isFs = BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation);
                int[] spec = computeWindowSpecLocked(ws.frame, isFs, ws.profile, ws.pkg);
                if (spec[0] != ws.contentWidth || spec[1] != ws.contentHeight || spec[2] != ws.contentDpi) {
                    ws.contentWidth = spec[0];
                    ws.contentHeight = spec[1];
                    ws.contentDpi = spec[2];
                    vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
                    System.out.println("OPALDESK_WINDOW_DPI_REAPPLIED wid=" + ws.windowId
                            + " pkg=" + ws.pkg + " dpiPct=" + dpiPct
                            + " customDpi=" + config.resolveEffectiveCustomDpi(ws.pkg)
                            + " buf=" + spec[0] + "x" + spec[1] + "@" + spec[2] + "dpi");
                    changed = true;
                }
            }
            if (changed) {
                markDirtyAndNotifyLocked();
            }
        }
    }

    /** Recomputes buffer resolution & DPI for any open window matching {@code pkg}. */
    public void reapplyAppDpi(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        synchronized (lock) {
            boolean changed = false;
            for (WindowSession ws : sessionsById.values()) {
                if (!pkg.equals(ws.pkg)) continue;
                boolean isFs = BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation);
                int[] spec = computeWindowSpecLocked(ws.frame, isFs, ws.profile, ws.pkg);
                if (spec[0] != ws.contentWidth || spec[1] != ws.contentHeight || spec[2] != ws.contentDpi) {
                    ws.contentWidth = spec[0];
                    ws.contentHeight = spec[1];
                    ws.contentDpi = spec[2];
                    vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
                    System.out.println("OPALDESK_APP_DPI_REAPPLIED wid=" + ws.windowId
                            + " pkg=" + ws.pkg + " customDpi=" + config.resolveEffectiveCustomDpi(ws.pkg)
                            + " buf=" + spec[0] + "x" + spec[1] + "@" + spec[2] + "dpi");
                    changed = true;
                }
            }
            if (changed) {
                markDirtyAndNotifyLocked();
            }
        }
    }

    /** Updates live surface frame-rate hints on all currently open windows after target FPS changes. */
    public void reapplyTargetFps(float autoFallbackFps) {
        synchronized (lock) {
            int cfgFps = config.getTargetFps();
            float effFps = DisplayGeometryUtil.clampFps(cfgFps > 0 ? cfgFps : autoFallbackFps, 60f);
            for (WindowSession ws : sessionsById.values()) {
                Object surf = attachedSurfaces.get(ws.windowId);
                if (surf != null && ws.isLive()) {
                    vdPool.updateSurfaceFrameRateIfActive(ws.windowId, surf, effFps);
                }
            }
            System.out.println("OPALDESK_WINDOW_FPS_REAPPLIED cfgFps=" + cfgFps + " effFps=" + effFps);
        }
    }

    public int getWindowCount() {
        synchronized (lock) {
            return sessionsById.size();
        }
    }

    /** Returns package names of all currently open windows hosted inside the DeX compositor. */
    public java.util.Set<String> getOpenWindowPackages() {
        synchronized (lock) {
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            for (WindowSession ws : sessionsById.values()) {
                if (ws != null && ws.pkg != null && !ws.pkg.isEmpty()) {
                    out.add(ws.pkg);
                }
            }
            return out;
        }
    }

    public WindowSession getWindowSnapshot(int windowId) {
        synchronized (lock) {
            return sessionsById.get(windowId);
        }
    }

    /** Returns the currently focused visible live window session, or {@code null} if none. */
    public WindowSession getFocusedLiveWindow() {
        synchronized (lock) {
            WindowSession best = null;
            for (WindowSession ws : sessionsById.values()) {
                if (ws == null || ws.isMinimized() || !ws.isLive() || ws.displayId <= 0) continue;
                if (ws.focused) return ws;
                if (best == null || ws.zOrder > best.zOrder) {
                    best = ws;
                }
            }
            return best;
        }
    }

    /**
     * Opens a new window for {@code pkg} or focuses/restores an existing window for {@code pkg}.
     * Returns positive {@code windowId}, or {@code -1} if {@code pkg} is invalid/blocked.
     */
    public int openOrFocusWindow(
            String pkg,
            String activityClass,
            int requestedProfileCode,
            int reqLeft,
            int reqTop,
            int reqRight,
            int reqBottom) {
        if (PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        String safeCls = PackagePolicy.isValidClassName(activityClass) ? activityClass : "";
        long now = System.currentTimeMillis();

        int existingWinId = -1;
        int liveSwitchDisplayId = -1;
        synchronized (lock) {
            WindowSession existing = findByPackageLocked(pkg);
            if (existing != null) {
                boolean wasHidden = existing.isMinimized() || existing.isHibernated();
                boolean clsChanged = !safeCls.isEmpty() && !safeCls.equals(existing.activityClass);
                if (clsChanged) {
                    existing.activityClass = safeCls;
                    existing.pendingActivitySwitch = true;
                }
                if (requestedProfileCode != BridgeProtocol.PROFILE_AUTO) {
                    WindowProfile nextProf = WindowProfile.fromCode(requestedProfileCode, existing.profile);
                    if (nextProf != existing.profile) {
                        applyProfileChangeLocked(existing, nextProf);
                    }
                }
                if (wasHidden) {
                    existing.state = BridgeProtocol.STATE_LIVE;
                } else if (clsChanged && existing.isLive() && existing.displayId > 0) {
                    liveSwitchDisplayId = existing.displayId;
                    existing.pendingActivitySwitch = false;
                    existing.lastLaunchAtMs = now;
                }
                focusWindowLocked(existing, now);
                markDirtyAndNotifyLocked();
                existingWinId = existing.windowId;
            }
        }
        if (existingWinId > 0) {
            if (liveSwitchDisplayId > 0) {
                int tid = runtimeBridge.launchExplicitActivityOnDisplay(pkg, safeCls, liveSwitchDisplayId);
                synchronized (lock) {
                    WindowSession cur = sessionsById.get(existingWinId);
                    if (cur != null && tid > 0) {
                        cur.taskId = tid;
                    }
                }
            }
            return existingWinId;
        }

        synchronized (lock) {

            int canvasW = config.getCanvasWidth();
            int canvasH = config.getCanvasHeight();
            int canvasDpi = config.getCanvasDpi();
            WindowProfile saved = config.getSavedProfile(pkg);
            WindowProfile profile = (requestedProfileCode == BridgeProtocol.PROFILE_AUTO)
                    ? (saved != null ? saved : WindowProfile.DESKTOP)
                    : WindowProfile.fromCode(requestedProfileCode, WindowProfile.DESKTOP);

            IntRect initialFrame;
            String initialPres = BridgeProtocol.PRES_FLOATING;
            if (reqRight - reqLeft >= DisplayGeometryUtil.MIN_WINDOW_EDGE_PX
                    && reqBottom - reqTop >= DisplayGeometryUtil.MIN_WINDOW_EDGE_PX) {
                initialFrame = DisplayGeometryUtil.clampWindowFrame(
                        new IntRect(reqLeft, reqTop, reqRight, reqBottom), canvasW, canvasH, canvasDpi);
            } else {
                DaemonConfig.SavedWindowBounds remembered = config.getSavedWindowBounds(pkg);
                if (remembered != null) {
                    initialPres = remembered.presentation;
                    if (!BridgeProtocol.PRES_FLOATING.equals(initialPres)
                            && !BridgeProtocol.PRES_FULLSCREEN.equals(initialPres)) {
                        initialFrame = DisplayGeometryUtil.computeSnapFrame(initialPres, canvasW, canvasH, canvasDpi);
                    } else {
                        initialPres = BridgeProtocol.PRES_FLOATING;
                        initialFrame = remembered.toCanvasRect(canvasW, canvasH, canvasDpi);
                    }
                } else {
                    initialFrame = DisplayGeometryUtil.computeInitialWindowFrame(
                            profile, sessionsById.size(), canvasW, canvasH, canvasDpi);
                }
            }

            int[] spec = computeWindowSpecLocked(initialFrame, false, profile, pkg);
            int winId = nextWindowId.getAndIncrement();
            String label = runtimeBridge.resolveAppLabel(pkg);

            WindowSession created = new WindowSession(
                    winId, pkg, safeCls, label, profile, initialFrame,
                    spec[0], spec[1], spec[2], now);
            created.presentation = initialPres;
            if (!BridgeProtocol.PRES_FLOATING.equals(initialPres)) {
                DaemonConfig.SavedWindowBounds remembered = config.getSavedWindowBounds(pkg);
                if (remembered != null) {
                    created.restoreFrame = remembered.toCanvasRect(canvasW, canvasH, canvasDpi);
                }
            }
            sessionsById.put(winId, created);
            focusWindowLocked(created, now);
            config.rememberProfile(pkg, profile);
            config.recordAppLaunch(pkg);
            markDirtyAndNotifyLocked();
            return winId;
        }
    }

    /**
     * Binds a client compositor {@code TextureView} {@code Surface} to {@code windowId},
     * acquiring an isolated {@code VirtualDisplay} from {@link VirtualDisplayPool} (evicting &
     * snapshot-hibernating the oldest background window if needed) and launching/resuming the app
     * in standard {@code WINDOWING_MODE_FULLSCREEN (1)}.
     *
     * @return positive {@code displayId} on success, or {@code -1} on failure.
     */
    public int attachWindowSurface(
            int windowId,
            Object surface,
            int reqContentW,
            int reqContentH,
            int reqDpi,
            float fps) {
        if (surface == null) return -1;
        String pkgToLaunch;
        String clsToLaunch;
        int prevTaskId;
        int assignedDisplayId;
        boolean reusedExistingLease;
        boolean forceExplicitLaunch;

        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) {
                runtimeBridge.releaseSurfaceHandle(surface);
                return -1;
            }

            int cw = DisplayGeometryUtil.alignEven(
                    reqContentW > 0 ? reqContentW : ws.contentWidth, 240, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int ch = DisplayGeometryUtil.alignEven(
                    reqContentH > 0 ? reqContentH : ws.contentHeight, 200, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int customDpi = config.resolveEffectiveCustomDpi(ws.pkg);
            int dpi = (reqDpi > 0)
                    ? DisplayGeometryUtil.clampDpi(reqDpi)
                    : (customDpi > 0
                            ? customDpi
                            : ws.profile.resolveContentDpi(cw, ch, config.getWindowDpiScalePct()));
            int cfgFps = config.getTargetFps();
            float effFps = DisplayGeometryUtil.clampFps(cfgFps > 0 ? cfgFps : fps, 60f);

            reusedExistingLease = vdPool.getLease(windowId) != null;
            VirtualDisplayPool.Lease lease = vdPool.acquireOrBind(
                    ws,
                    surface,
                    cw,
                    ch,
                    dpi,
                    effFps,
                    sessionsById.values(),
                    this::handleWindowEvictedLocked);
            if (lease == null) {
                System.err.println("OPALDESK_ATTACH_VD_FAIL wid=" + windowId + " pkg=" + ws.pkg
                        + " size=" + cw + "x" + ch + "@" + dpi + "dpi");
                runtimeBridge.releaseSurfaceHandle(surface);
                return -1;
            }

            Object prevSurf = attachedSurfaces.put(windowId, surface);
            if (prevSurf != null && prevSurf != surface) {
                runtimeBridge.releaseSurfaceHandle(prevSurf);
            }

            ws.contentWidth = cw;
            ws.contentHeight = ch;
            ws.contentDpi = dpi;
            ws.displayId = lease.displayId;
            ws.vdUniqueId = lease.uniqueId;
            ws.state = BridgeProtocol.STATE_LIVE;
            long nowMs = System.currentTimeMillis();
            ws.lastActiveAtMs = nowMs;
            ws.lastLaunchAtMs = nowMs;
            ws.emptyDisplayTicks = 0;
            ws.emptySinceMs = 0L;

            pkgToLaunch = ws.pkg;
            clsToLaunch = ws.activityClass;
            prevTaskId = ws.taskId;
            assignedDisplayId = lease.displayId;
            forceExplicitLaunch = ws.pendingActivitySwitch;
            ws.pendingActivitySwitch = false;
            markDirtyAndNotifyLocked();
        }

        // If re-attaching a TextureView to an already-active VirtualDisplay whose app is still visible on top,
        // skip relaunching to prevent activity flicker unless an explicit sub-activity switch was requested.
        int existingTopTid = (reusedExistingLease && !forceExplicitLaunch)
                ? runtimeBridge.findTopVisibleTaskIdOnDisplay(assignedDisplayId, pkgToLaunch)
                : -1;
        int launchedTid;
        if (forceExplicitLaunch && !clsToLaunch.isEmpty()) {
            launchedTid = runtimeBridge.launchExplicitActivityOnDisplay(
                    pkgToLaunch, clsToLaunch, assignedDisplayId);
        } else if (existingTopTid > 0) {
            runtimeBridge.focusDisplay(assignedDisplayId);
            launchedTid = existingTopTid;
        } else {
            launchedTid = runtimeBridge.launchOrMoveFullscreenApp(
                    pkgToLaunch, clsToLaunch, assignedDisplayId, prevTaskId);
        }
        if (launchedTid > 0) {
            synchronized (lock) {
                WindowSession ws = sessionsById.get(windowId);
                if (ws != null && ws.displayId == assignedDisplayId && ws.taskId != launchedTid) {
                    ws.taskId = launchedTid;
                    markDirtyAndNotifyLocked();
                }
            }
        }
        return assignedDisplayId;
    }

    /**
     * Hot-migrates every currently LIVE window onto a fresh {@code VirtualDisplay} from the
     * current {@link VirtualDisplayPool.DisplayDriver} (e.g. moving open windows like WeChat onto
     * or off an AOSP {@code VirtualDevice} when VirtualCamera mapping toggles ON/OFF), preserving
     * each running task and its live {@code TextureView} surface without closing the app.
     */
    public int migrateLiveWindowsToCurrentDisplayDriver() {
        List<int[]> candidates = new ArrayList<>();
        synchronized (lock) {
            for (WindowSession ws : sessionsById.values()) {
                if (ws != null && ws.isLive() && ws.displayId > 0 && attachedSurfaces.containsKey(ws.windowId)) {
                    candidates.add(new int[]{ws.windowId, ws.contentWidth, ws.contentHeight, ws.contentDpi});
                }
            }
        }
        int migrated = 0;
        int cfgFps = config.getTargetFps();
        float effFps = DisplayGeometryUtil.clampFps(cfgFps > 0 ? cfgFps : 60f, 60f);

        for (int[] item : candidates) {
            int winId = item[0];
            VirtualDisplayPool.RebindResult rebind;
            String pkg;
            String cls;
            int prevTaskId;
            int newDisplayId;
            synchronized (lock) {
                WindowSession ws = sessionsById.get(winId);
                Object surf = attachedSurfaces.get(winId);
                if (ws == null || !ws.isLive() || surf == null) continue;
                rebind = vdPool.recreateLease(ws, surf, ws.contentWidth, ws.contentHeight, ws.contentDpi, effFps);
                if (rebind == null || rebind.newLease == null) continue;
                ws.displayId = rebind.newLease.displayId;
                ws.vdUniqueId = rebind.newLease.uniqueId;
                ws.lastLaunchAtMs = System.currentTimeMillis();
                ws.emptyDisplayTicks = 0;
                ws.emptySinceMs = 0L;
                pkg = ws.pkg;
                cls = ws.activityClass;
                prevTaskId = ws.taskId;
                newDisplayId = rebind.newLease.displayId;
                markDirtyAndNotifyLocked();
            }

            try {
                int movedTid = runtimeBridge.launchOrMoveFullscreenApp(pkg, cls, newDisplayId, prevTaskId);
                if (movedTid > 0) {
                    synchronized (lock) {
                        WindowSession ws = sessionsById.get(winId);
                        if (ws != null && ws.displayId == newDisplayId) {
                            ws.taskId = movedTid;
                            markDirtyAndNotifyLocked();
                        }
                    }
                }
            } finally {
                vdPool.finishReleaseOldLease(rebind.oldLease);
            }
            migrated++;
            System.out.println("OPALDESK_VD_MIGRATED wid=" + winId + " pkg=" + pkg
                    + " oldDisplay=" + rebind.oldLease.displayId + " newDisplay=" + newDisplayId);
        }
        return migrated;
    }

    /**
     * Detaches the client Surface when a TextureView is destroyed (e.g. minimized or screen off).
     * If {@code releaseDisplay} is true, returns the VirtualDisplay to the pool immediately while
     * caching a snapshot so the session can wake later.
     */
    public boolean detachWindowSurface(int windowId, boolean releaseDisplay) {
        int releasedDisplayId = -1;
        boolean becameZeroAttachedSurfaces;
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;

            boolean hadSurfaces = !attachedSurfaces.isEmpty();
            Object surf = attachedSurfaces.remove(windowId);
            if (releaseDisplay && ws.displayId > 0) {
                releasedDisplayId = ws.displayId;
                vdPool.releaseLease(windowId);
                ws.displayId = -1;
                ws.vdUniqueId = "";
                if (!ws.isMinimized()) {
                    ws.state = BridgeProtocol.STATE_HIBERNATED;
                }
            } else {
                vdPool.detachSurfaceOnly(windowId);
            }
            if (surf != null) {
                runtimeBridge.releaseSurfaceHandle(surf);
            }
            becameZeroAttachedSurfaces = hadSurfaces && attachedSurfaces.isEmpty();
            markDirtyAndNotifyLocked();
        }
        if (releasedDisplayId > 0) {
            runtimeBridge.onDisplayReleased(releasedDisplayId);
        }
        if (becameZeroAttachedSurfaces) {
            runtimeBridge.onAllClientSurfacesDetached();
        }
        return true;
    }

    /**
     * Detaches all currently bound client Surfaces (e.g. when the Helper UI process dies or is
     * swiped away from Recents) while keeping window sessions recoverable in the background.
     */
    public void detachAllClientSurfaces() {
        List<Object> surfacesToRelease = new ArrayList<>();
        boolean hadAny;
        synchronized (lock) {
            hadAny = !attachedSurfaces.isEmpty() || !sessionsById.isEmpty();
            for (WindowSession ws : sessionsById.values()) {
                if (ws.displayId > 0) {
                    vdPool.detachSurfaceOnly(ws.windowId);
                }
            }
            surfacesToRelease.addAll(attachedSurfaces.values());
            attachedSurfaces.clear();
        }
        for (Object s : surfacesToRelease) {
            runtimeBridge.releaseSurfaceHandle(s);
        }
        if (hadAny) {
            runtimeBridge.onAllClientSurfacesDetached();
        }
    }

    /**
     * Commits final outer window frame and presentation (FLOATING / MAXIMIZED / SNAP_*) after drag/snap.
     * Pure translation changes require zero VirtualDisplay mutations!
     */
    public boolean updateWindowFrame(
            int windowId, int left, int top, int right, int bottom, String presentation) {
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            int canvasW = config.getCanvasWidth();
            int canvasH = config.getCanvasHeight();
            int canvasDpi = config.getCanvasDpi();

            String nextPres = (presentation != null && !presentation.isEmpty())
                    ? presentation : BridgeProtocol.PRES_FLOATING;
            if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)
                    && !BridgeProtocol.PRES_FLOATING.equals(nextPres)) {
                ws.restoreFrame = ws.frame;
            }

            IntRect nextFrame;
            if (!BridgeProtocol.PRES_FLOATING.equals(nextPres)) {
                nextFrame = DisplayGeometryUtil.computeSnapFrame(nextPres, canvasW, canvasH, canvasDpi);
            } else {
                nextFrame = DisplayGeometryUtil.clampWindowFrame(
                        new IntRect(left, top, right, bottom), canvasW, canvasH, canvasDpi);
                ws.restoreFrame = nextFrame;
            }

            boolean wasFullscreen = BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation);
            boolean nextFullscreen = BridgeProtocol.PRES_FULLSCREEN.equals(nextPres);
            boolean sizeChanged = nextFrame.width() != ws.frame.width()
                    || nextFrame.height() != ws.frame.height()
                    || wasFullscreen != nextFullscreen;
            ws.frame = nextFrame;
            ws.presentation = nextPres;
            focusWindowLocked(ws, System.currentTimeMillis());

            if (sizeChanged) {
                int[] spec = computeWindowSpecLocked(nextFrame, nextFullscreen, ws.profile, ws.pkg);
                ws.contentWidth = spec[0];
                ws.contentHeight = spec[1];
                ws.contentDpi = spec[2];
                vdPool.resizeIfActive(windowId, spec[0], spec[1], spec[2]);
            }
            rememberWindowBoundsLocked(ws);
            markDirtyAndNotifyLocked();
            return true;
        }
    }

    /**
     * Fast-path throttled VirtualDisplay resize during live edge/corner dragging.
     */
    public boolean resizeWindowDisplay(int windowId, int contentW, int contentH, int reqDpi) {
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            int cw = DisplayGeometryUtil.alignEven(contentW, 240, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int ch = DisplayGeometryUtil.alignEven(contentH, 200, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int customDpi = config.resolveEffectiveCustomDpi(ws.pkg);
            int dpi = (reqDpi > 0)
                    ? DisplayGeometryUtil.clampDpi(reqDpi)
                    : (customDpi > 0
                            ? customDpi
                            : ws.profile.resolveContentDpi(cw, ch, config.getWindowDpiScalePct()));
            if (cw == ws.contentWidth && ch == ws.contentHeight && dpi == ws.contentDpi) {
                return true;
            }
            ws.contentWidth = cw;
            ws.contentHeight = ch;
            ws.contentDpi = dpi;
            vdPool.resizeIfActive(windowId, cw, ch, dpi);
            return true;
        }
    }

    /**
     * Executes lifecycle & profile actions on {@code windowId}.
     */
    public boolean performWindowAction(int windowId, int action, byte[] optionalPreMinimizeSnapshot) {
        return performWindowActionInternal(windowId, action, optionalPreMinimizeSnapshot, true);
    }

    private boolean performWindowActionInternal(
            int windowId, int action, byte[] optionalPreMinimizeSnapshot, boolean forceStopOnClose) {
        int taskToRemove = -1;
        int displayToRelease = -1;
        String pkgToClose = null;
        String pkgToReload = null;
        int reloadOldTaskId = -1;
        int reloadDisplayId = -1;
        String reloadCls = "";
        boolean shouldForceStopPkg = false;
        boolean allSurfacesNowDetached = false;

        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            long now = System.currentTimeMillis();

            switch (action) {
                case BridgeProtocol.WIN_ACTION_FOCUS:
                case BridgeProtocol.WIN_ACTION_WAKE: {
                    if (ws.isMinimized() || ws.isHibernated()) {
                        ws.state = BridgeProtocol.STATE_LIVE;
                    }
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                }
                case BridgeProtocol.WIN_ACTION_MINIMIZE: {
                    if (optionalPreMinimizeSnapshot != null && optionalPreMinimizeSnapshot.length > 0) {
                        ws.hibernatedSnapshotBytes = optionalPreMinimizeSnapshot;
                        ws.snapshotVersion++;
                    }
                    // Detach the client Surface ONLY (setting the VirtualDisplay to STATE_OFF) instead of
                    // destroying the VirtualDisplay: keeps the app cleanly paused inside its own display
                    // so Android NEVER reparents it onto the host phone's Display 0, and allows instant
                    // zero-reload resume from the Dock (LRU eviction still reclaims minimized leases first).
                    if (ws.displayId > 0) {
                        vdPool.detachSurfaceOnly(windowId);
                    }
                    Object surf = attachedSurfaces.remove(windowId);
                    if (surf != null) runtimeBridge.releaseSurfaceHandle(surf);
                    if (BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) {
                        int canvasW = config.getCanvasWidth();
                        int canvasH = config.getCanvasHeight();
                        int canvasDpi = config.getCanvasDpi();
                        ws.presentation = BridgeProtocol.PRES_FLOATING;
                        ws.frame = (ws.restoreFrame != null && !ws.restoreFrame.isEmpty())
                                ? DisplayGeometryUtil.clampWindowFrame(ws.restoreFrame, canvasW, canvasH, canvasDpi)
                                : DisplayGeometryUtil.computeInitialWindowFrame(ws.profile, 0, canvasW, canvasH, canvasDpi);
                        int[] spec = computeWindowSpecLocked(ws.frame, false, ws.profile, ws.pkg);
                        ws.contentWidth = spec[0];
                        ws.contentHeight = spec[1];
                        ws.contentDpi = spec[2];
                    }
                    ws.state = BridgeProtocol.STATE_MINIMIZED;
                    ws.focused = false;
                    promoteTopVisibleFocusLocked(now);
                    allSurfacesNowDetached = attachedSurfaces.isEmpty();
                    markDirtyAndNotifyLocked();
                    break;
                }
                case BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE: {
                    int canvasW = config.getCanvasWidth();
                    int canvasH = config.getCanvasHeight();
                    int canvasDpi = config.getCanvasDpi();
                    if (BridgeProtocol.PRES_MAXIMIZED.equals(ws.presentation)
                            || BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) {
                        ws.presentation = BridgeProtocol.PRES_FLOATING;
                        ws.frame = (ws.restoreFrame != null && !ws.restoreFrame.isEmpty())
                                ? DisplayGeometryUtil.clampWindowFrame(ws.restoreFrame, canvasW, canvasH, canvasDpi)
                                : DisplayGeometryUtil.computeInitialWindowFrame(ws.profile, 0, canvasW, canvasH, canvasDpi);
                    } else {
                        if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)) {
                            ws.restoreFrame = ws.frame;
                        }
                        ws.presentation = BridgeProtocol.PRES_MAXIMIZED;
                        ws.frame = DisplayGeometryUtil.computeSnapFrame(
                                BridgeProtocol.PRES_MAXIMIZED, canvasW, canvasH, canvasDpi);
                    }
                    int[] spec = computeWindowSpecLocked(ws.frame, false, ws.profile, ws.pkg);
                    ws.contentWidth = spec[0];
                    ws.contentHeight = spec[1];
                    ws.contentDpi = spec[2];
                    vdPool.resizeIfActive(windowId, spec[0], spec[1], spec[2]);
                    rememberWindowBoundsLocked(ws);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                }
                case BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN: {
                    int canvasW = config.getCanvasWidth();
                    int canvasH = config.getCanvasHeight();
                    int canvasDpi = config.getCanvasDpi();
                    boolean enterFs = !BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation);
                    if (enterFs) {
                        ws.preFullscreenFrame = ws.frame;
                        ws.preFullscreenPresentation = ws.presentation;
                        if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)) {
                            ws.restoreFrame = ws.frame;
                        }
                        ws.presentation = BridgeProtocol.PRES_FULLSCREEN;
                        ws.frame = DisplayGeometryUtil.computeSnapFrame(
                                BridgeProtocol.PRES_FULLSCREEN, canvasW, canvasH, canvasDpi);
                    } else {
                        restoreFromFullscreenLocked(ws, canvasW, canvasH, canvasDpi);
                    }
                    int[] spec = computeWindowSpecLocked(ws.frame, enterFs, ws.profile, ws.pkg);
                    ws.contentWidth = spec[0];
                    ws.contentHeight = spec[1];
                    ws.contentDpi = spec[2];
                    vdPool.resizeIfActive(windowId, spec[0], spec[1], spec[2]);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                }
                case BridgeProtocol.WIN_ACTION_CLOSE: {
                    rememberWindowBoundsLocked(ws);
                    sessionsById.remove(windowId);
                    Object surf = attachedSurfaces.remove(windowId);
                    if (surf != null) runtimeBridge.releaseSurfaceHandle(surf);
                    if (ws.displayId > 0) {
                        vdPool.detachSurfaceOnly(windowId);
                        displayToRelease = ws.displayId;
                        pkgToClose = ws.pkg;
                    }
                    if (forceStopOnClose
                            && ws.pkg != null
                            && !ws.pkg.isEmpty()
                            && !PackagePolicy.shouldSkipForceStopOnClose(ws.pkg)) {
                        boolean hasOtherWindowSamePkg = false;
                        for (WindowSession other : sessionsById.values()) {
                            if (ws.pkg.equals(other.pkg)) {
                                hasOtherWindowSamePkg = true;
                                break;
                            }
                        }
                        if (!hasOtherWindowSamePkg) {
                            pkgToClose = ws.pkg;
                            shouldForceStopPkg = true;
                        }
                    }
                    taskToRemove = ws.taskId;
                    promoteTopVisibleFocusLocked(now);
                    allSurfacesNowDetached = attachedSurfaces.isEmpty();
                    markDirtyAndNotifyLocked();
                    break;
                }
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_DESKTOP:
                    applyProfileChangeLocked(ws, WindowProfile.DESKTOP);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_PORTRAIT:
                    applyProfileChangeLocked(ws, WindowProfile.PORTRAIT);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_TABLET:
                    applyProfileChangeLocked(ws, WindowProfile.TABLET);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_TOGGLE_ORIENTATION: {
                    boolean currentlyPortrait = ws.contentHeight > ws.contentWidth;
                    boolean toPortrait = !currentlyPortrait;
                    WindowProfile nextProfile = toPortrait ? WindowProfile.PORTRAIT : WindowProfile.DESKTOP;
                    ws.profile = nextProfile;
                    config.rememberProfile(ws.pkg, nextProfile);
                    int canvasW = config.getCanvasWidth();
                    int canvasH = config.getCanvasHeight();
                    int canvasDpi = config.getCanvasDpi();
                    ws.presentation = BridgeProtocol.PRES_FLOATING;
                    ws.frame = computeRotatedWindowFrameLocked(ws.frame, toPortrait, canvasW, canvasH, canvasDpi);
                    ws.restoreFrame = ws.frame;
                    int[] spec = computeWindowSpecLocked(ws.frame, false, nextProfile, ws.pkg);
                    ws.contentWidth = spec[0];
                    ws.contentHeight = spec[1];
                    ws.contentDpi = spec[2];
                    vdPool.resizeIfActive(windowId, spec[0], spec[1], spec[2]);
                    rememberWindowBoundsLocked(ws);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                }
                case BridgeProtocol.WIN_ACTION_RELOAD_APP:
                    pkgToReload = ws.pkg;
                    reloadCls = ws.activityClass;
                    reloadDisplayId = ws.displayId;
                    reloadOldTaskId = ws.taskId;
                    ws.taskId = -1;
                    ws.lastLaunchAtMs = now;
                    ws.lastActiveAtMs = now;
                    ws.emptyDisplayTicks = 0;
                    ws.emptySinceMs = 0L;
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    break;
                default:
                    return false;
            }
        }

        // Remove ALL Android tasks hosted on this VirtualDisplay BEFORE releasing the display so
        // WindowManager never leaves residual/secondary tasks to migrate onto Phone Display 0.
        java.util.LinkedHashSet<Integer> tasksToRemoveOnClose = new java.util.LinkedHashSet<>();
        if (taskToRemove > 0) {
            tasksToRemoveOnClose.add(taskToRemove);
        }
        if (displayToRelease > 0) {
            List<Integer> displayTasks = runtimeBridge.findAllTaskIdsOnDisplay(displayToRelease);
            if (displayTasks != null) {
                for (Integer tid : displayTasks) {
                    if (tid != null && tid > 0) tasksToRemoveOnClose.add(tid);
                }
            }
            if (tasksToRemoveOnClose.isEmpty() && pkgToClose != null) {
                int fallbackTid = runtimeBridge.findTopTaskIdOnDisplay(displayToRelease, pkgToClose);
                if (fallbackTid > 0) tasksToRemoveOnClose.add(fallbackTid);
            }
        }
        for (int tid : tasksToRemoveOnClose) {
            runtimeBridge.removeTask(tid);
        }
        if (displayToRelease > 0) {
            vdPool.releaseLease(windowId);
            runtimeBridge.onDisplayReleased(displayToRelease);
        }
        // When the last window of a package is explicitly closed (not minimized), stop its
        // background playback/media service so video apps never keep playing audio in background.
        if (shouldForceStopPkg && pkgToClose != null && !pkgToClose.isEmpty()) {
            runtimeBridge.forceStopPackage(pkgToClose);
        }
        if (allSurfacesNowDetached) {
            runtimeBridge.onAllClientSurfacesDetached();
        }
        if (pkgToReload != null && !pkgToReload.isEmpty()) {
            if (reloadOldTaskId > 0) {
                runtimeBridge.removeTask(reloadOldTaskId);
            }
            runtimeBridge.forceStopPackage(pkgToReload);
            if (reloadDisplayId > 0) {
                int newTid = runtimeBridge.relaunchFreshFullscreenApp(pkgToReload, reloadCls, reloadDisplayId);
                long relaunchedAt = System.currentTimeMillis();
                synchronized (lock) {
                    WindowSession ws = sessionsById.get(windowId);
                    if (ws != null) {
                        ws.lastLaunchAtMs = relaunchedAt;
                        ws.lastActiveAtMs = relaunchedAt;
                        ws.emptyDisplayTicks = 0;
                        ws.emptySinceMs = 0L;
                        if (newTid > 0) {
                            ws.taskId = newTid;
                        }
                        markDirtyAndNotifyLocked();
                    }
                }
            }
        }
        return true;
    }

    public byte[] getHibernatedSnapshotBytes(int windowId) {
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            return (ws != null) ? ws.hibernatedSnapshotBytes : null;
        }
    }

    public void updateCachedSnapshot(int windowId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws != null) {
                ws.hibernatedSnapshotBytes = bytes;
                ws.snapshotVersion++;
            }
        }
    }

    /**
     * Checks whether a Back key caused the app inside {@code windowId} to exit or move to back
     * (leaving an empty white/blank VirtualDisplay). If the app is still visible, does nothing;
     * if it moved to back (still alive), cleanly minimizes to Dock; if it exited, cleanly closes!
     */
    public void reconcileWindowAfterBack(int windowId) {
        int displayId;
        int taskId;
        String pkg;
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null || !ws.isLive() || ws.displayId <= 0) return;
            displayId = ws.displayId;
            taskId = ws.taskId;
            pkg = ws.pkg;
        }
        int visibleTid = runtimeBridge.findTopVisibleTaskIdOnDisplay(displayId, pkg);
        if (visibleTid > 0) {
            synchronized (lock) {
                WindowSession ws = sessionsById.get(windowId);
                if (ws != null) {
                    ws.emptyDisplayTicks = 0;
                    ws.emptySinceMs = 0L;
                    if (ws.taskId != visibleTid) {
                        ws.taskId = visibleTid;
                        markDirtyAndNotifyLocked();
                    }
                }
            }
            return;
        }
        boolean stillRunningInBg = runtimeBridge.isTaskRunningWithActivities(taskId, displayId);
        if (stillRunningInBg) {
            System.out.println("OPALDESK_BACK_MOVED_TO_BG_MINIMIZE wid=" + windowId + " pkg=" + pkg);
            performWindowAction(windowId, BridgeProtocol.WIN_ACTION_MINIMIZE, null);
        } else {
            System.out.println("OPALDESK_BACK_EXITED_CLOSE wid=" + windowId + " pkg=" + pkg);
            performWindowActionInternal(windowId, BridgeProtocol.WIN_ACTION_CLOSE, null, false);
        }
    }

    /**
     * Reconciles live Android tasks with managed window sessions: binds newly spawned taskIds on
     * active VirtualDisplays and auto-closes/minimizes windows whose underlying Activity exited or
     * moved to background (preventing blank white zombie windows).
     */
    public void reconcileWithSystemTasks() {
        reconcileWithSystemTasks(System.currentTimeMillis());
    }

    /**
     * Deterministic clock-injected reconciliation overload for JVM unit testing of transient
     * fullscreen / landscape Activity handoffs (e.g. Moonlight, UU远程).
     */
    public void reconcileWithSystemTasks(long now) {
        List<Integer> checkWindowIds = new ArrayList<>();
        List<Integer> checkDisplayIds = new ArrayList<>();
        List<Integer> checkTaskIds = new ArrayList<>();
        List<String> checkPkgs = new ArrayList<>();

        synchronized (lock) {
            if (sessionsById.isEmpty()) return;
            for (WindowSession ws : sessionsById.values()) {
                if (ws.isLive() && ws.displayId > 0) {
                    checkWindowIds.add(ws.windowId);
                    checkDisplayIds.add(ws.displayId);
                    checkTaskIds.add(ws.taskId);
                    checkPkgs.add(ws.pkg);
                }
            }
        }

        Set<Integer> aliveTids = runtimeBridge.collectAliveTaskIds();
        Map<Integer, Integer> discoveredVisibleTasks = new LinkedHashMap<>();
        Map<Integer, Boolean> stillRunningBg = new LinkedHashMap<>();
        for (int i = 0; i < checkWindowIds.size(); i++) {
            int wid = checkWindowIds.get(i);
            int dId = checkDisplayIds.get(i);
            int topVisibleTid = runtimeBridge.findTopVisibleTaskIdOnDisplay(dId, checkPkgs.get(i));
            if (topVisibleTid > 0) {
                discoveredVisibleTasks.put(wid, topVisibleTid);
            } else {
                stillRunningBg.put(wid, runtimeBridge.isTaskRunningWithActivities(checkTaskIds.get(i), dId));
            }
        }

        List<Integer> toMinimize = new ArrayList<>();
        List<Integer> toClose = new ArrayList<>();
        synchronized (lock) {
            boolean dirty = false;

            for (WindowSession ws : sessionsById.values()) {
                Integer liveTid = discoveredVisibleTasks.get(ws.windowId);
                if (liveTid != null && liveTid > 0) {
                    ws.emptyDisplayTicks = 0;
                    ws.emptySinceMs = 0L;
                    if (ws.taskId != liveTid) {
                        ws.taskId = liveTid;
                        dirty = true;
                    }
                } else if (ws.taskId > 0
                        && aliveTids != null
                        && !aliveTids.isEmpty()
                        && !aliveTids.contains(ws.taskId)
                        && !Boolean.TRUE.equals(stillRunningBg.get(ws.windowId))
                        && (now - ws.lastActiveAtMs) > 2200L) {
                    toClose.add(ws.windowId);
                } else if (ws.isLive() && ws.displayId > 0 && (now - ws.lastLaunchAtMs) > 2500L) {
                    boolean runningBg = Boolean.TRUE.equals(stillRunningBg.get(ws.windowId));
                    if (ws.emptySinceMs <= 0L) {
                        ws.emptySinceMs = now;
                    }
                    ws.emptyDisplayTicks++;
                    long emptyDurationMs = now - ws.emptySinceMs;
                    if (emptyDurationMs >= EMPTY_DISPLAY_SETTLE_MS) {
                        if (!runningBg && ws.taskId > 0 && (now - ws.lastActiveAtMs) > 1500L) {
                            toClose.add(ws.windowId);
                        } else if (ws.emptyDisplayTicks >= 2) {
                            if (runningBg) {
                                toMinimize.add(ws.windowId);
                            } else {
                                toClose.add(ws.windowId);
                            }
                        }
                    }
                }
            }
            if (dirty) {
                markDirtyAndNotifyLocked();
            }
        }

        for (int wid : toMinimize) {
            performWindowAction(wid, BridgeProtocol.WIN_ACTION_MINIMIZE, null);
        }
        for (int wid : toClose) {
            performWindowActionInternal(wid, BridgeProtocol.WIN_ACTION_CLOSE, null, false);
        }
    }

    /**
     * Returns the display title of the active visible fullscreen window, or an empty string when
     * no window is currently in true fullscreen mode.
     */
    public String getFullscreenWindowTitle() {
        synchronized (lock) {
            for (WindowSession ws : sessionsById.values()) {
                if (ws.isMinimized() || !BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) continue;
                if (ws.title != null && !ws.title.isEmpty()) return ws.title;
                if (ws.pkg != null && !ws.pkg.isEmpty()) return ws.pkg;
                return "Fullscreen";
            }
            return "";
        }
    }

    /**
     * If any visible window is currently in true fullscreen, exits fullscreen and restores it
     * to its exact pre-fullscreen frame & presentation on the desktop (keeping the app LIVE and
     * visible rather than hiding or closing it). Returns {@code true} if a fullscreen window was restored.
     */
    public boolean exitFullscreenIfActive() {
        synchronized (lock) {
            int canvasW = config.getCanvasWidth();
            int canvasH = config.getCanvasHeight();
            int canvasDpi = config.getCanvasDpi();
            long now = System.currentTimeMillis();
            boolean restoredAny = false;
            for (WindowSession ws : sessionsById.values()) {
                if (ws.isMinimized() || !BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) continue;
                restoreFromFullscreenLocked(ws, canvasW, canvasH, canvasDpi);
                int[] spec = computeWindowSpecLocked(ws.frame, false, ws.profile, ws.pkg);
                ws.contentWidth = spec[0];
                ws.contentHeight = spec[1];
                ws.contentDpi = spec[2];
                vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
                focusWindowLocked(ws, now);
                restoredAny = true;
            }
            if (restoredAny) {
                markDirtyAndNotifyLocked();
            }
            return restoredAny;
        }
    }

    private void restoreFromFullscreenLocked(WindowSession ws, int canvasW, int canvasH, int canvasDpi) {
        String prevPres = (ws.preFullscreenPresentation != null
                && !ws.preFullscreenPresentation.isEmpty()
                && !BridgeProtocol.PRES_FULLSCREEN.equals(ws.preFullscreenPresentation))
                ? ws.preFullscreenPresentation : BridgeProtocol.PRES_FLOATING;
        ws.presentation = prevPres;
        if (!BridgeProtocol.PRES_FLOATING.equals(prevPres)) {
            ws.frame = DisplayGeometryUtil.computeSnapFrame(prevPres, canvasW, canvasH, canvasDpi);
        } else {
            IntRect target = (ws.preFullscreenFrame != null && !ws.preFullscreenFrame.isEmpty())
                    ? ws.preFullscreenFrame : ws.restoreFrame;
            ws.frame = (target != null && !target.isEmpty())
                    ? DisplayGeometryUtil.clampWindowFrame(target, canvasW, canvasH, canvasDpi)
                    : DisplayGeometryUtil.computeInitialWindowFrame(ws.profile, 0, canvasW, canvasH, canvasDpi);
        }
        ws.preFullscreenFrame = null;
        ws.preFullscreenPresentation = null;
    }

    /**
     * Minimizes all visible windows cleanly (e.g. remote {@code SYS_HOME} / "Show Desktop")
     * while keeping their isolated VirtualDisplay leases alive so apps stay paused in the Dock
     * and never get closed or reparented onto the host phone's Display 0.
     */
    public void minimizeAllWindows() {
        List<Object> surfacesToRelease = new ArrayList<>();
        synchronized (lock) {
            boolean changed = false;
            int canvasW = config.getCanvasWidth();
            int canvasH = config.getCanvasHeight();
            int canvasDpi = config.getCanvasDpi();
            for (WindowSession ws : sessionsById.values()) {
                if (ws.isMinimized()) continue;
                if (ws.displayId > 0) {
                    vdPool.detachSurfaceOnly(ws.windowId);
                }
                Object surf = attachedSurfaces.remove(ws.windowId);
                if (surf != null) surfacesToRelease.add(surf);
                if (BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) {
                    ws.presentation = BridgeProtocol.PRES_FLOATING;
                    ws.frame = (ws.restoreFrame != null && !ws.restoreFrame.isEmpty())
                            ? DisplayGeometryUtil.clampWindowFrame(ws.restoreFrame, canvasW, canvasH, canvasDpi)
                            : DisplayGeometryUtil.computeInitialWindowFrame(ws.profile, 0, canvasW, canvasH, canvasDpi);
                    int[] spec = computeWindowSpecLocked(ws.frame, false, ws.profile, ws.pkg);
                    ws.contentWidth = spec[0];
                    ws.contentHeight = spec[1];
                    ws.contentDpi = spec[2];
                }
                ws.state = BridgeProtocol.STATE_MINIMIZED;
                ws.focused = false;
                changed = true;
            }
            if (changed) {
                markDirtyAndNotifyLocked();
            }
        }
        for (Object s : surfacesToRelease) {
            runtimeBridge.releaseSurfaceHandle(s);
        }
        runtimeBridge.onAllClientSurfacesDetached();
    }

    public void closeAllWindows(boolean removeUnderlyingTasks) {
        List<Integer> tasksToRemove = new ArrayList<>();
        List<Integer> displaysToClean = new ArrayList<>();
        List<Object> surfacesToRelease = new ArrayList<>();

        synchronized (lock) {
            for (WindowSession ws : sessionsById.values()) {
                rememberWindowBoundsLocked(ws);
                if (ws.displayId > 0) {
                    vdPool.detachSurfaceOnly(ws.windowId);
                    displaysToClean.add(ws.displayId);
                }
                if (removeUnderlyingTasks && ws.taskId > 0) tasksToRemove.add(ws.taskId);
            }
            surfacesToRelease.addAll(attachedSurfaces.values());
            attachedSurfaces.clear();
            sessionsById.clear();
            markDirtyAndNotifyLocked();
        }

        for (Object s : surfacesToRelease) runtimeBridge.releaseSurfaceHandle(s);
        if (removeUnderlyingTasks) {
            java.util.LinkedHashSet<Integer> allTasksToRemove = new java.util.LinkedHashSet<>(tasksToRemove);
            for (int d : displaysToClean) {
                List<Integer> displayTasks = runtimeBridge.findAllTaskIdsOnDisplay(d);
                if (displayTasks != null) {
                    for (Integer tid : displayTasks) {
                        if (tid != null && tid > 0) allTasksToRemove.add(tid);
                    }
                }
            }
            for (int t : allTasksToRemove) runtimeBridge.removeTask(t);
        }
        vdPool.releaseAll();
        for (int d : displaysToClean) runtimeBridge.onDisplayReleased(d);
        runtimeBridge.onAllClientSurfacesDetached();
    }

    public String toSceneJson() {
        synchronized (lock) {
            List<WindowSession> sorted = new ArrayList<>(sessionsById.values());
            sorted.sort(Comparator.comparingInt(w -> w.zOrder));
            SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
            for (WindowSession ws : sorted) {
                arr.addRaw(ws.toJson());
            }
            return new SimpleJson.ObjectBuilder()
                    .put("seq", stateSeq.get())
                    .put("canvasW", config.getCanvasWidth())
                    .put("canvasH", config.getCanvasHeight())
                    .put("canvasDpi", config.getCanvasDpi())
                    .put("activeVdCount", vdPool.getActiveCount())
                    .put("maxVdCount", vdPool.getEffectiveMaxDisplays())
                    .putRaw("windows", arr.build())
                    .build();
        }
    }

    private int[] computeWindowSpecLocked(IntRect frame, boolean fullscreen, WindowProfile profile, String pkg) {
        return DisplayGeometryUtil.resolveWindowBufferSpec(
                frame,
                config.getCanvasDpi(),
                fullscreen,
                profile,
                config.getWindowDpiScalePct(),
                runtimeBridge.getHostDisplayDpi(),
                config.resolveEffectiveCustomDpi(pkg),
                config.getWindowSupersampleMode());
    }

    private void rememberWindowBoundsLocked(WindowSession ws) {
        if (ws == null) return;
        IntRect rectToSave = BridgeProtocol.PRES_FLOATING.equals(ws.presentation)
                ? ws.frame
                : (ws.restoreFrame != null && !ws.restoreFrame.isEmpty() ? ws.restoreFrame : ws.frame);
        String presToSave = BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)
                ? BridgeProtocol.PRES_FLOATING : ws.presentation;
        config.rememberWindowBounds(
                ws.pkg, rectToSave, config.getCanvasWidth(), config.getCanvasHeight(), presToSave);
    }

    private IntRect computeRotatedWindowFrameLocked(
            IntRect currentFrame,
            boolean toPortrait,
            int canvasW,
            int canvasH,
            int canvasDpi) {
        int cw = Math.max(360, canvasW);
        int ch = Math.max(360, canvasH);
        int topSafe = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.TOP_BAR_HEIGHT_DP, canvasDpi);
        int bottomSafe = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.DOCK_RESERVED_BOTTOM_DP, canvasDpi);
        int usableH = Math.max(260, ch - topSafe - bottomSafe - 16);
        int usableW = Math.max(300, cw - 24);
        int targetW;
        int targetH;
        if (toPortrait) {
            // Maximize usable vertical height with a phone-friendly 9:16 aspect ratio so vertical video
            // apps (Douyin / short dramas) fill the frame without side letterbox bars.
            targetH = Math.min(usableH, Math.round(usableH * 0.96f));
            targetW = Math.round(targetH * 9f / 16f);
            if (targetW > usableW) {
                targetW = usableW;
                targetH = Math.min(usableH, Math.round(targetW * 16f / 9f));
            }
            if (targetW < 340) targetW = Math.min(usableW, 340);
        } else {
            // Rotate back to a spacious 16:10 landscape desktop window centered on the canvas.
            targetW = Math.min(usableW, Math.round(usableW * 0.78f));
            targetH = Math.min(usableH, Math.round(targetW * 10f / 16f));
            if (targetH > usableH) {
                targetH = usableH;
                targetW = Math.min(usableW, Math.round(targetH * 16f / 10f));
            }
        }
        int cx = (currentFrame != null && !currentFrame.isEmpty())
                ? (currentFrame.left + currentFrame.right) / 2
                : cw / 2;
        int cy = (currentFrame != null && !currentFrame.isEmpty())
                ? (currentFrame.top + currentFrame.bottom) / 2
                : topSafe + usableH / 2;
        int left = cx - targetW / 2;
        int top = cy - targetH / 2;
        return DisplayGeometryUtil.clampWindowFrame(
                new IntRect(left, top, left + targetW, top + targetH),
                cw, ch, canvasDpi);
    }

    private void applyProfileChangeLocked(WindowSession ws, WindowProfile nextProfile) {
        ws.profile = nextProfile;
        config.rememberProfile(ws.pkg, nextProfile);
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        ws.presentation = BridgeProtocol.PRES_FLOATING;
        ws.frame = DisplayGeometryUtil.computeInitialWindowFrame(nextProfile, 0, canvasW, canvasH, canvasDpi);
        ws.restoreFrame = ws.frame;
        int[] spec = computeWindowSpecLocked(ws.frame, false, nextProfile, ws.pkg);
        ws.contentWidth = spec[0];
        ws.contentHeight = spec[1];
        ws.contentDpi = spec[2];
        vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
    }

    private void handleWindowEvictedLocked(WindowSession victim, byte[] snapshotBytes) {
        int oldDisplayId = victim.displayId;
        victim.displayId = -1;
        victim.vdUniqueId = "";
        if (!victim.isMinimized()) {
            victim.state = BridgeProtocol.STATE_HIBERNATED;
        }
        victim.focused = false;
        if (snapshotBytes != null && snapshotBytes.length > 0) {
            victim.hibernatedSnapshotBytes = snapshotBytes;
            victim.snapshotVersion++;
        }
        Object surf = attachedSurfaces.remove(victim.windowId);
        if (surf != null) {
            runtimeBridge.releaseSurfaceHandle(surf);
        }
        if (oldDisplayId > 0) {
            runtimeBridge.onDisplayReleased(oldDisplayId);
        }
    }

    private WindowSession findByPackageLocked(String pkg) {
        for (WindowSession ws : sessionsById.values()) {
            if (pkg.equals(ws.pkg)) return ws;
        }
        return null;
    }

    private void focusWindowLocked(WindowSession target, long nowMs) {
        for (WindowSession ws : sessionsById.values()) {
            ws.focused = (ws == target);
        }
        target.zOrder = nextZOrder++;
        target.lastActiveAtMs = nowMs;
    }

    private void promoteTopVisibleFocusLocked(long nowMs) {
        WindowSession top = null;
        for (WindowSession ws : sessionsById.values()) {
            if (ws.isMinimized()) {
                ws.focused = false;
                continue;
            }
            if (top == null || ws.zOrder > top.zOrder) {
                top = ws;
            }
        }
        for (WindowSession ws : sessionsById.values()) {
            ws.focused = (top != null && ws == top);
        }
        if (top != null) {
            top.lastActiveAtMs = nowMs;
        }
    }

    private void markDirtyAndNotifyLocked() {
        stateSeq.incrementAndGet();
        if (onSceneDirty != null) {
            onSceneDirty.run();
        }
    }
}
