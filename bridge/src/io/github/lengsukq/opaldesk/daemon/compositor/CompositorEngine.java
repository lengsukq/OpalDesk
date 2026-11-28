package io.github.lengsukq.opaldesk.daemon.compositor;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.UserProfileAppPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Core Wayland-style Universal Window Compositor Engine.
 *
 * <p>Delegates layout/Z-order mutations to {@link CompositorLayoutManager}, task reconciliation
 * and live display migration to {@link CompositorTaskReconciler}, and scene JSON serialization to
 * {@link CompositorSceneSerializer}, remaining 100% pure JVM unit-testable.
 */
public final class CompositorEngine {

    /** Immutable {@code (pkg, userId)} pair identifying a managed window's app instance. */
    public static final class WindowAppTarget {
        public final String pkg;
        public final int userId;

        public WindowAppTarget(String pkg, int userId) {
            this.pkg = (pkg != null) ? pkg : "";
            this.userId = Math.max(0, userId);
        }

        public String appKey() {
            return UserProfileAppPolicy.formatAppKey(pkg, userId);
        }
    }

    /** Platform bridge for launching/moving/removing fullscreen Android tasks on a displayId. */
    public interface AppRuntimeBridge {
        String resolveAppLabel(String pkg);
        default String resolveAppLabel(String pkg, int userId) { return resolveAppLabel(pkg); }
        default String resolveProfileType(int userId) { return UserProfileAppPolicy.classifyProfileType(userId, "", 0, ""); }
        int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId);
        default int launchOrMoveFullscreenApp(String pkg, String cls, int userId, int displayId, int existingTaskId) {
            return launchOrMoveFullscreenApp(pkg, cls, displayId, existingTaskId);
        }
        default int relaunchFreshFullscreenApp(String pkg, String cls, int displayId) {
            return launchOrMoveFullscreenApp(pkg, cls, displayId, -1);
        }
        default int relaunchFreshFullscreenApp(String pkg, String cls, int userId, int displayId) {
            return relaunchFreshFullscreenApp(pkg, cls, displayId);
        }
        int findTopTaskIdOnDisplay(int displayId, String expectedPkg);
        default int findTopTaskIdOnDisplay(int displayId, String expectedPkg, int userId) {
            return findTopTaskIdOnDisplay(displayId, expectedPkg);
        }
        default List<Integer> findAllTaskIdsOnDisplay(int displayId) { return Collections.emptyList(); }
        default int findTopVisibleTaskIdOnDisplay(int displayId, String expectedPkg) {
            return findTopTaskIdOnDisplay(displayId, expectedPkg);
        }
        default int findTopVisibleTaskIdOnDisplay(int displayId, String expectedPkg, int userId) {
            return findTopVisibleTaskIdOnDisplay(displayId, expectedPkg);
        }
        default boolean isTaskRunningWithActivities(int taskId, int displayId) {
            Set<Integer> alive = collectAliveTaskIds();
            return alive != null && alive.contains(taskId);
        }
        Set<Integer> collectAliveTaskIds();
        void removeTask(int taskId);
        void forceStopPackage(String pkg);
        default void forceStopPackage(String pkg, int userId) { forceStopPackage(pkg); }
        default void focusDisplay(int displayId) {}
        default int launchExplicitActivityOnDisplay(String pkg, String cls, int displayId) {
            return launchOrMoveFullscreenApp(pkg, cls, displayId, -1);
        }
        default int launchExplicitActivityOnDisplay(String pkg, String cls, int userId, int displayId) {
            return launchExplicitActivityOnDisplay(pkg, cls, displayId);
        }
        default void onAllClientSurfacesDetached() {}
        void onDisplayReleased(int displayId);
        void releaseSurfaceHandle(Object surface);
        default int getHostDisplayDpi() { return 0; }
    }

    public static final long EMPTY_DISPLAY_SETTLE_MS = 900L;

    private final Object lock = new Object();
    private final DaemonConfig config;
    private final VirtualDisplayPool vdPool;
    private final AppRuntimeBridge runtimeBridge;
    private final Runnable onSceneDirty;
    private final CompositorLayoutManager layoutManager;
    private final CompositorTaskReconciler taskReconciler;

    private final Map<Integer, WindowSession> sessionsById = new LinkedHashMap<>();
    private final Map<Integer, Object> attachedSurfaces = new LinkedHashMap<>();
    private final AtomicInteger nextWindowId = new AtomicInteger(1);
    private final AtomicLong stateSeq = new AtomicLong(1L);
    private volatile IntConsumer backReconcileScheduler;
    private volatile java.util.function.IntSupplier keyboardRoutedDisplayIdSupplier;
    private volatile java.util.function.BooleanSupplier comboKeyboardOuterSupplier;

    public CompositorEngine(
            DaemonConfig config,
            VirtualDisplayPool vdPool,
            AppRuntimeBridge runtimeBridge,
            Runnable onSceneDirty) {
        this.config = config;
        this.vdPool = vdPool;
        this.runtimeBridge = runtimeBridge;
        this.onSceneDirty = onSceneDirty;
        this.layoutManager = new CompositorLayoutManager(config, vdPool, runtimeBridge);
        this.taskReconciler = new CompositorTaskReconciler(
                lock, config, vdPool, runtimeBridge, sessionsById, attachedSurfaces,
                new CompositorTaskReconciler.ActionDispatcher() {
                    @Override public void markDirtyAndNotifyLocked() { CompositorEngine.this.markDirtyAndNotifyLocked(); }
                    @Override public void performAction(int windowId, int action, boolean forceStopOnClose) {
                        performWindowActionInternal(windowId, action, null, forceStopOnClose);
                    }
                });
    }

    public void setBackReconcileScheduler(IntConsumer scheduler) { this.backReconcileScheduler = scheduler; }
    public void setKeyboardRoutedDisplayIdSupplier(java.util.function.IntSupplier supplier) { this.keyboardRoutedDisplayIdSupplier = supplier; }
    public void setComboKeyboardOuterSupplier(java.util.function.BooleanSupplier supplier) { this.comboKeyboardOuterSupplier = supplier; }

    public void notifyBackSent(int windowId) {
        IntConsumer sched = this.backReconcileScheduler;
        if (sched != null && windowId > 0) sched.accept(windowId);
    }

    public long getStateSeq() { return stateSeq.get(); }
    public int getActiveDisplayCount() { return vdPool.getActiveCount(); }
    public int getEffectiveMaxDisplays() { return vdPool.getEffectiveMaxDisplays(); }
    public void setConfiguredMaxDisplays(int maxDisplays) { vdPool.setConfiguredMaxDisplays(maxDisplays); }

    public void reapplyWindowDpiScale() {
        synchronized (lock) {
            if (layoutManager.reapplyWindowDpiScaleLocked(sessionsById.values())) {
                markDirtyAndNotifyLocked();
            }
        }
    }

    public void reapplyAppDpi(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        synchronized (lock) {
            if (layoutManager.reapplyAppDpiLocked(sessionsById.values(), pkg)) {
                markDirtyAndNotifyLocked();
            }
        }
    }

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

    public int getWindowCount() { synchronized (lock) { return sessionsById.size(); } }
    public Set<String> getOpenWindowPackages() { synchronized (lock) { return CompositorSceneSerializer.getOpenWindowPackages(sessionsById.values()); } }
    public List<WindowAppTarget> getOpenWindowAppTargets() { synchronized (lock) { return CompositorSceneSerializer.getOpenWindowAppTargets(sessionsById.values()); } }
    public WindowSession getWindowSnapshot(int windowId) { synchronized (lock) { return sessionsById.get(windowId); } }
    public WindowSession getFocusedLiveWindow() { synchronized (lock) { return CompositorSceneSerializer.getFocusedLiveWindow(sessionsById.values()); } }

    public int openOrFocusWindow(
            String pkg, String activityClass, int requestedProfileCode,
            int reqLeft, int reqTop, int reqRight, int reqBottom) {
        return openOrFocusWindow(pkg, activityClass, UserProfileAppPolicy.PRIMARY_USER_ID,
                requestedProfileCode, reqLeft, reqTop, reqRight, reqBottom);
    }

    public int openOrFocusWindow(
            String pkg, String activityClass, int userId, int requestedProfileCode,
            int reqLeft, int reqTop, int reqRight, int reqBottom) {
        if (PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        int safeUserId = Math.max(0, userId);
        String safeCls = PackagePolicy.isValidClassName(activityClass) ? activityClass : "";
        long now = System.currentTimeMillis();

        int existingWinId = -1;
        int liveSwitchDisplayId = -1;
        synchronized (lock) {
            WindowSession existing = findByPackageAndUserLocked(pkg, safeUserId);
            if (existing != null) {
                boolean wasHidden = existing.isMinimized() || existing.isHibernated();
                boolean clsChanged = !safeCls.isEmpty() && !safeCls.equals(existing.activityClass);
                if (clsChanged) {
                    existing.activityClass = safeCls;
                    existing.pendingActivitySwitch = true;
                }
                if (requestedProfileCode != BridgeProtocol.PROFILE_AUTO) {
                    WindowProfile nextProf = WindowProfile.fromCode(requestedProfileCode, existing.profile);
                    if (nextProf != existing.profile) layoutManager.applyProfileChangeLocked(existing, nextProf);
                }
                if (wasHidden) {
                    existing.state = BridgeProtocol.STATE_LIVE;
                    existing.minimizedAtMs = 0L;
                } else if (clsChanged && existing.isLive() && existing.displayId > 0) {
                    liveSwitchDisplayId = existing.displayId;
                    existing.pendingActivitySwitch = false;
                    existing.lastLaunchAtMs = now;
                }
                layoutManager.focusWindowLocked(sessionsById.values(), existing, now);
                markDirtyAndNotifyLocked();
                existingWinId = existing.windowId;
            }
        }
        if (existingWinId > 0) {
            if (liveSwitchDisplayId > 0) {
                int tid = runtimeBridge.launchExplicitActivityOnDisplay(pkg, safeCls, safeUserId, liveSwitchDisplayId);
                synchronized (lock) {
                    WindowSession cur = sessionsById.get(existingWinId);
                    if (cur != null && tid > 0) cur.taskId = tid;
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

            boolean hasSiblingSamePkg = hasAnyWindowForPackageLocked(pkg);
            IntRect initialFrame;
            String initialPres = BridgeProtocol.PRES_FLOATING;
            if (reqRight - reqLeft >= DisplayGeometryUtil.MIN_WINDOW_EDGE_PX
                    && reqBottom - reqTop >= DisplayGeometryUtil.MIN_WINDOW_EDGE_PX) {
                initialFrame = DisplayGeometryUtil.clampWindowFrame(
                        new IntRect(reqLeft, reqTop, reqRight, reqBottom), canvasW, canvasH, canvasDpi);
            } else if (!hasSiblingSamePkg) {
                DaemonConfig.SavedWindowBounds remembered = config.getSavedWindowBounds(pkg);
                if (remembered != null) {
                    initialPres = remembered.presentation;
                    if (!BridgeProtocol.PRES_FLOATING.equals(initialPres)
                            && !BridgeProtocol.PRES_FULLSCREEN.equals(initialPres)) {
                        initialFrame = DisplayGeometryUtil.computeSnapFrame(
                                initialPres, canvasW, canvasH, canvasDpi, config.getDockReservedBottomDp());
                    } else {
                        initialPres = BridgeProtocol.PRES_FLOATING;
                        initialFrame = remembered.toCanvasRect(canvasW, canvasH, canvasDpi);
                    }
                } else {
                    initialFrame = DisplayGeometryUtil.computeInitialWindowFrame(
                            profile, sessionsById.size(), canvasW, canvasH, canvasDpi, config.getDockReservedBottomDp());
                }
            } else {
                initialFrame = DisplayGeometryUtil.computeInitialWindowFrame(
                        profile, sessionsById.size(), canvasW, canvasH, canvasDpi, config.getDockReservedBottomDp());
            }

            int[] spec = layoutManager.computeWindowSpecLocked(initialFrame, false, profile, pkg);
            int winId = nextWindowId.getAndIncrement();
            String label = runtimeBridge.resolveAppLabel(pkg, safeUserId);
            String profileType = (safeUserId > 0)
                    ? runtimeBridge.resolveProfileType(safeUserId)
                    : UserProfileAppPolicy.PROFILE_PRIMARY;

            WindowSession created = new WindowSession(
                    winId, pkg, safeUserId, profileType, safeCls, label, profile, initialFrame,
                    spec[0], spec[1], spec[2], now);
            created.presentation = initialPres;
            if (!BridgeProtocol.PRES_FLOATING.equals(initialPres)) {
                DaemonConfig.SavedWindowBounds remembered = config.getSavedWindowBounds(pkg);
                if (remembered != null) created.restoreFrame = remembered.toCanvasRect(canvasW, canvasH, canvasDpi);
            }
            sessionsById.put(winId, created);
            layoutManager.focusWindowLocked(sessionsById.values(), created, now);
            config.rememberProfile(pkg, profile);
            config.recordAppLaunch(pkg);
            markDirtyAndNotifyLocked();
            return winId;
        }
    }

    public int attachWindowSurface(
            int windowId, Object surface, int reqContentW, int reqContentH, int reqDpi, float fps) {
        if (surface == null) return -1;
        String pkgToLaunch, clsToLaunch;
        int userIdToLaunch, prevTaskId, assignedDisplayId;
        boolean reusedExistingLease, forceExplicitLaunch;

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
            int dpi = (reqDpi > 0) ? DisplayGeometryUtil.clampDpi(reqDpi)
                    : (customDpi > 0 ? customDpi : ws.profile.resolveContentDpi(cw, ch, config.getWindowDpiScalePct()));
            int cfgFps = config.getTargetFps();
            float effFps = DisplayGeometryUtil.clampFps(cfgFps > 0 ? cfgFps : fps, 60f);

            reusedExistingLease = vdPool.getLease(windowId) != null;
            VirtualDisplayPool.Lease lease = vdPool.acquireOrBind(
                    ws, surface, cw, ch, dpi, effFps, sessionsById.values(), this::handleWindowEvictedLocked);
            if (lease == null) {
                System.err.println("OPALDESK_ATTACH_VD_FAIL wid=" + windowId + " pkg=" + ws.pkg
                        + " user=" + ws.userId + " size=" + cw + "x" + ch + "@" + dpi + "dpi");
                runtimeBridge.releaseSurfaceHandle(surface);
                return -1;
            }
            Object prevSurf = attachedSurfaces.put(windowId, surface);
            if (prevSurf != null && prevSurf != surface) runtimeBridge.releaseSurfaceHandle(prevSurf);

            ws.contentWidth = cw;
            ws.contentHeight = ch;
            ws.contentDpi = dpi;
            ws.displayId = lease.displayId;
            ws.vdUniqueId = lease.uniqueId;
            ws.state = BridgeProtocol.STATE_LIVE;
            long nowMs = System.currentTimeMillis();
            ws.lastActiveAtMs = nowMs;
            ws.lastLaunchAtMs = nowMs;
            ws.minimizedAtMs = 0L;
            ws.emptyDisplayTicks = 0;
            ws.emptySinceMs = 0L;

            pkgToLaunch = ws.pkg;
            clsToLaunch = ws.activityClass;
            userIdToLaunch = ws.userId;
            prevTaskId = ws.taskId;
            assignedDisplayId = lease.displayId;
            forceExplicitLaunch = ws.pendingActivitySwitch;
            ws.pendingActivitySwitch = false;
            markDirtyAndNotifyLocked();
        }

        int existingTopTid = (reusedExistingLease && !forceExplicitLaunch)
                ? runtimeBridge.findTopVisibleTaskIdOnDisplay(assignedDisplayId, pkgToLaunch, userIdToLaunch) : -1;
        int launchedTid;
        if (forceExplicitLaunch && !clsToLaunch.isEmpty()) {
            launchedTid = runtimeBridge.launchExplicitActivityOnDisplay(pkgToLaunch, clsToLaunch, userIdToLaunch, assignedDisplayId);
        } else if (existingTopTid > 0) {
            runtimeBridge.focusDisplay(assignedDisplayId);
            launchedTid = existingTopTid;
        } else {
            launchedTid = runtimeBridge.launchOrMoveFullscreenApp(pkgToLaunch, clsToLaunch, userIdToLaunch, assignedDisplayId, prevTaskId);
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

    public int migrateLiveWindowsToCurrentDisplayDriver() {
        return taskReconciler.migrateLiveWindowsToCurrentDisplayDriver();
    }

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
                if (!ws.isMinimized()) ws.state = BridgeProtocol.STATE_HIBERNATED;
            } else {
                vdPool.detachSurfaceOnly(windowId);
            }
            if (surf != null) runtimeBridge.releaseSurfaceHandle(surf);
            becameZeroAttachedSurfaces = hadSurfaces && attachedSurfaces.isEmpty();
            markDirtyAndNotifyLocked();
        }
        if (releasedDisplayId > 0) runtimeBridge.onDisplayReleased(releasedDisplayId);
        if (becameZeroAttachedSurfaces) runtimeBridge.onAllClientSurfacesDetached();
        return true;
    }

    public void detachAllClientSurfaces() {
        List<Object> surfacesToRelease = new ArrayList<>();
        boolean hadAny;
        synchronized (lock) {
            hadAny = !attachedSurfaces.isEmpty() || !sessionsById.isEmpty();
            for (WindowSession ws : sessionsById.values()) {
                if (ws.displayId > 0) vdPool.detachSurfaceOnly(ws.windowId);
            }
            surfacesToRelease.addAll(attachedSurfaces.values());
            attachedSurfaces.clear();
        }
        for (Object s : surfacesToRelease) runtimeBridge.releaseSurfaceHandle(s);
        if (hadAny) runtimeBridge.onAllClientSurfacesDetached();
    }

    public boolean updateWindowFrame(int windowId, int left, int top, int right, int bottom, String presentation) {
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            layoutManager.applyFrameUpdateLocked(sessionsById.values(), ws, left, top, right, bottom, presentation);
            markDirtyAndNotifyLocked();
            return true;
        }
    }

    public boolean resizeWindowDisplay(int windowId, int contentW, int contentH, int reqDpi) {
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            int cw = DisplayGeometryUtil.alignEven(contentW, 240, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int ch = DisplayGeometryUtil.alignEven(contentH, 200, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int customDpi = config.resolveEffectiveCustomDpi(ws.pkg);
            int dpi = (reqDpi > 0) ? DisplayGeometryUtil.clampDpi(reqDpi)
                    : (customDpi > 0 ? customDpi : ws.profile.resolveContentDpi(cw, ch, config.getWindowDpiScalePct()));
            if (cw == ws.contentWidth && ch == ws.contentHeight && dpi == ws.contentDpi) return true;
            ws.contentWidth = cw;
            ws.contentHeight = ch;
            ws.contentDpi = dpi;
            vdPool.resizeIfActive(windowId, cw, ch, dpi);
            return true;
        }
    }

    public boolean performWindowAction(int windowId, int action, byte[] optionalPreMinimizeSnapshot) {
        return performWindowActionInternal(windowId, action, optionalPreMinimizeSnapshot, true);
    }

    private boolean performWindowActionInternal(
            int windowId, int action, byte[] optionalPreMinimizeSnapshot, boolean forceStopOnClose) {
        int taskToRemove = -1, displayToRelease = -1, closeUserId = 0;
        String pkgToClose = null, pkgToReload = null, reloadCls = "";
        int reloadUserId = 0, reloadOldTaskId = -1, reloadDisplayId = -1;
        boolean shouldForceStopPkg = false, allSurfacesNowDetached = false;

        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            long now = System.currentTimeMillis();
            switch (action) {
                case BridgeProtocol.WIN_ACTION_FOCUS:
                case BridgeProtocol.WIN_ACTION_WAKE:
                    if (ws.isMinimized() || ws.isHibernated()) {
                        ws.state = BridgeProtocol.STATE_LIVE;
                        ws.minimizedAtMs = 0L;
                    }
                    layoutManager.focusWindowLocked(sessionsById.values(), ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_MINIMIZE:
                    if (optionalPreMinimizeSnapshot != null && optionalPreMinimizeSnapshot.length > 0) {
                        ws.hibernatedSnapshotBytes = optionalPreMinimizeSnapshot;
                        ws.snapshotVersion++;
                    }
                    if (ws.displayId > 0) vdPool.detachSurfaceOnly(windowId);
                    Object minSurf = attachedSurfaces.remove(windowId);
                    if (minSurf != null) runtimeBridge.releaseSurfaceHandle(minSurf);
                    layoutManager.restoreFullscreenBeforeMinimizeLocked(ws);
                    ws.state = BridgeProtocol.STATE_MINIMIZED;
                    ws.minimizedAtMs = now;
                    ws.focused = false;
                    layoutManager.promoteTopVisibleFocusLocked(sessionsById.values(), now);
                    allSurfacesNowDetached = attachedSurfaces.isEmpty();
                    markDirtyAndNotifyLocked();
                    break;
                case BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE:
                    layoutManager.applyToggleMaximizeLocked(sessionsById.values(), ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN:
                    layoutManager.applyToggleFullscreenLocked(sessionsById.values(), ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_CLOSE:
                    layoutManager.rememberWindowBoundsLocked(ws);
                    sessionsById.remove(windowId);
                    Object closeSurf = attachedSurfaces.remove(windowId);
                    if (closeSurf != null) runtimeBridge.releaseSurfaceHandle(closeSurf);
                    if (ws.displayId > 0) {
                        vdPool.detachSurfaceOnly(windowId);
                        displayToRelease = ws.displayId;
                        pkgToClose = ws.pkg;
                        closeUserId = ws.userId;
                    }
                    if (forceStopOnClose && ws.pkg != null && !ws.pkg.isEmpty()
                            && !PackagePolicy.shouldSkipForceStopOnClose(ws.pkg)) {
                        boolean hasOther = false;
                        for (WindowSession other : sessionsById.values()) {
                            if (ws.pkg.equals(other.pkg) && ws.userId == other.userId) { hasOther = true; break; }
                        }
                        if (!hasOther) { pkgToClose = ws.pkg; closeUserId = ws.userId; shouldForceStopPkg = true; }
                    }
                    taskToRemove = ws.taskId;
                    layoutManager.promoteTopVisibleFocusLocked(sessionsById.values(), now);
                    allSurfacesNowDetached = attachedSurfaces.isEmpty();
                    markDirtyAndNotifyLocked();
                    break;
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_DESKTOP:
                    layoutManager.applyProfileChangeLocked(ws, WindowProfile.DESKTOP);
                    layoutManager.focusWindowLocked(sessionsById.values(), ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_PORTRAIT:
                    layoutManager.applyProfileChangeLocked(ws, WindowProfile.PORTRAIT);
                    layoutManager.focusWindowLocked(sessionsById.values(), ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_TABLET:
                    layoutManager.applyProfileChangeLocked(ws, WindowProfile.TABLET);
                    layoutManager.focusWindowLocked(sessionsById.values(), ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_TOGGLE_ORIENTATION:
                    layoutManager.applyToggleOrientationLocked(sessionsById.values(), ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_RELOAD_APP:
                    pkgToReload = ws.pkg; reloadUserId = ws.userId; reloadCls = ws.activityClass;
                    reloadDisplayId = ws.displayId; reloadOldTaskId = ws.taskId;
                    ws.taskId = -1; ws.lastLaunchAtMs = now; ws.lastActiveAtMs = now;
                    ws.emptyDisplayTicks = 0; ws.emptySinceMs = 0L;
                    layoutManager.focusWindowLocked(sessionsById.values(), ws, now);
                    markDirtyAndNotifyLocked();
                    break;
                default:
                    return false;
            }
        }

        Set<Integer> tasksToRemoveOnClose = new LinkedHashSet<>();
        if (taskToRemove > 0) tasksToRemoveOnClose.add(taskToRemove);
        if (displayToRelease > 0) {
            List<Integer> displayTasks = runtimeBridge.findAllTaskIdsOnDisplay(displayToRelease);
            if (displayTasks != null) {
                for (Integer tid : displayTasks) { if (tid != null && tid > 0) tasksToRemoveOnClose.add(tid); }
            }
            if (tasksToRemoveOnClose.isEmpty() && pkgToClose != null) {
                int fallbackTid = runtimeBridge.findTopTaskIdOnDisplay(displayToRelease, pkgToClose, closeUserId);
                if (fallbackTid > 0) tasksToRemoveOnClose.add(fallbackTid);
            }
        }
        for (int tid : tasksToRemoveOnClose) runtimeBridge.removeTask(tid);
        if (displayToRelease > 0) {
            vdPool.releaseLease(windowId);
            runtimeBridge.onDisplayReleased(displayToRelease);
        }
        if (shouldForceStopPkg && pkgToClose != null && !pkgToClose.isEmpty()) {
            runtimeBridge.forceStopPackage(pkgToClose, closeUserId);
        }
        if (allSurfacesNowDetached) runtimeBridge.onAllClientSurfacesDetached();
        if (pkgToReload != null && !pkgToReload.isEmpty()) {
            if (reloadOldTaskId > 0) runtimeBridge.removeTask(reloadOldTaskId);
            runtimeBridge.forceStopPackage(pkgToReload, reloadUserId);
            if (reloadDisplayId > 0) {
                int newTid = runtimeBridge.relaunchFreshFullscreenApp(pkgToReload, reloadCls, reloadUserId, reloadDisplayId);
                long relaunchedAt = System.currentTimeMillis();
                synchronized (lock) {
                    WindowSession ws = sessionsById.get(windowId);
                    if (ws != null) {
                        ws.lastLaunchAtMs = relaunchedAt; ws.lastActiveAtMs = relaunchedAt;
                        ws.emptyDisplayTicks = 0; ws.emptySinceMs = 0L;
                        if (newTid > 0) ws.taskId = newTid;
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
            if (ws != null) { ws.hibernatedSnapshotBytes = bytes; ws.snapshotVersion++; }
        }
    }

    public void reconcileWindowAfterBack(int windowId) { taskReconciler.reconcileWindowAfterBack(windowId); }
    public void reconcileWithSystemTasks() { taskReconciler.reconcileWithSystemTasks(System.currentTimeMillis()); }
    public void reconcileWithSystemTasks(long now) { taskReconciler.reconcileWithSystemTasks(now); }

    public String getFullscreenWindowTitle() {
        synchronized (lock) { return CompositorSceneSerializer.getFullscreenWindowTitle(sessionsById.values()); }
    }

    public boolean exitFullscreenIfActive() {
        synchronized (lock) {
            boolean restored = layoutManager.exitFullscreenIfActiveLocked(sessionsById.values());
            if (restored) markDirtyAndNotifyLocked();
            return restored;
        }
    }

    public boolean snapFocusedWindow(boolean leftHalf) {
        synchronized (lock) {
            boolean changed = layoutManager.snapFocusedWindowLocked(
                    sessionsById.values(), leftHalf, System.currentTimeMillis());
            if (changed) markDirtyAndNotifyLocked();
            return changed;
        }
    }

    public boolean applyQuickSplitTwo() {
        synchronized (lock) {
            boolean changed = layoutManager.applyQuickSplitTwoLocked(
                    sessionsById.values(), System.currentTimeMillis());
            if (changed) markDirtyAndNotifyLocked();
            return changed;
        }
    }

    public boolean switchNextWindow() {
        synchronized (lock) {
            boolean changed = layoutManager.switchNextWindowLocked(
                    sessionsById.values(), System.currentTimeMillis());
            if (changed) markDirtyAndNotifyLocked();
            return changed;
        }
    }

    public void minimizeAllWindows() {
        List<Object> surfacesToRelease = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (lock) {
            boolean changed = false;
            for (WindowSession ws : sessionsById.values()) {
                if (ws.isMinimized()) continue;
                if (ws.displayId > 0) vdPool.detachSurfaceOnly(ws.windowId);
                Object surf = attachedSurfaces.remove(ws.windowId);
                if (surf != null) surfacesToRelease.add(surf);
                layoutManager.restoreFullscreenBeforeMinimizeLocked(ws);
                ws.state = BridgeProtocol.STATE_MINIMIZED;
                ws.minimizedAtMs = now;
                ws.focused = false;
                changed = true;
            }
            if (changed) markDirtyAndNotifyLocked();
        }
        for (Object s : surfacesToRelease) runtimeBridge.releaseSurfaceHandle(s);
        runtimeBridge.onAllClientSurfacesDetached();
    }

    public void closeAllWindows(boolean removeUnderlyingTasks) {
        List<Integer> tasksToRemove = new ArrayList<>();
        List<Integer> displaysToClean = new ArrayList<>();
        List<Object> surfacesToRelease = new ArrayList<>();
        synchronized (lock) {
            for (WindowSession ws : sessionsById.values()) {
                layoutManager.rememberWindowBoundsLocked(ws);
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
            Set<Integer> allTasksToRemove = new LinkedHashSet<>(tasksToRemove);
            for (int d : displaysToClean) {
                List<Integer> displayTasks = runtimeBridge.findAllTaskIdsOnDisplay(d);
                if (displayTasks != null) {
                    for (Integer tid : displayTasks) { if (tid != null && tid > 0) allTasksToRemove.add(tid); }
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
            java.util.function.IntSupplier kbdSup = this.keyboardRoutedDisplayIdSupplier;
            int kbdRoutedDisplayId = (kbdSup != null) ? kbdSup.getAsInt() : -1;
            java.util.function.BooleanSupplier comboSup = this.comboKeyboardOuterSupplier;
            boolean comboKbdOuter = comboSup != null && comboSup.getAsBoolean();
            return CompositorSceneSerializer.toSceneJson(
                    sessionsById.values(), stateSeq.get(), config, vdPool, kbdRoutedDisplayId, comboKbdOuter);
        }
    }

    private void handleWindowEvictedLocked(WindowSession victim, byte[] snapshotBytes) {
        int oldDisplayId = victim.displayId;
        victim.displayId = -1;
        victim.vdUniqueId = "";
        if (!victim.isMinimized()) victim.state = BridgeProtocol.STATE_HIBERNATED;
        victim.focused = false;
        if (snapshotBytes != null && snapshotBytes.length > 0) {
            victim.hibernatedSnapshotBytes = snapshotBytes;
            victim.snapshotVersion++;
        }
        Object surf = attachedSurfaces.remove(victim.windowId);
        if (surf != null) runtimeBridge.releaseSurfaceHandle(surf);
        if (oldDisplayId > 0) runtimeBridge.onDisplayReleased(oldDisplayId);
    }

    private WindowSession findByPackageAndUserLocked(String pkg, int userId) {
        int safeUserId = Math.max(0, userId);
        for (WindowSession ws : sessionsById.values()) {
            if (ws.matchesApp(pkg, safeUserId)) return ws;
        }
        return null;
    }

    private boolean hasAnyWindowForPackageLocked(String pkg) {
        for (WindowSession ws : sessionsById.values()) {
            if (pkg.equals(ws.pkg)) return true;
        }
        return false;
    }

    private void markDirtyAndNotifyLocked() {
        stateSeq.incrementAndGet();
        if (onSceneDirty != null) onSceneDirty.run();
    }
}
