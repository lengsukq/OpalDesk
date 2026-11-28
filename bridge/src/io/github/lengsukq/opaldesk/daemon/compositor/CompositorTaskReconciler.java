package io.github.lengsukq.opaldesk.daemon.compositor;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure JVM task reconciliation and VirtualDisplay live-migration coordinator for {@link CompositorEngine}.
 */
final class CompositorTaskReconciler {
    interface ActionDispatcher {
        void markDirtyAndNotifyLocked();
        void performAction(int windowId, int action, boolean forceStopOnClose);
    }

    private final Object lock;
    private final DaemonConfig config;
    private final VirtualDisplayPool vdPool;
    private final CompositorEngine.AppRuntimeBridge runtimeBridge;
    private final Map<Integer, WindowSession> sessionsById;
    private final Map<Integer, Object> attachedSurfaces;
    private final ActionDispatcher dispatcher;

    CompositorTaskReconciler(
            Object lock,
            DaemonConfig config,
            VirtualDisplayPool vdPool,
            CompositorEngine.AppRuntimeBridge runtimeBridge,
            Map<Integer, WindowSession> sessionsById,
            Map<Integer, Object> attachedSurfaces,
            ActionDispatcher dispatcher) {
        this.lock = lock;
        this.config = config;
        this.vdPool = vdPool;
        this.runtimeBridge = runtimeBridge;
        this.sessionsById = sessionsById;
        this.attachedSurfaces = attachedSurfaces;
        this.dispatcher = dispatcher;
    }

    int migrateLiveWindowsToCurrentDisplayDriver() {
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
            String pkg, cls;
            int userId, prevTaskId, newDisplayId;
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
                userId = ws.userId;
                prevTaskId = ws.taskId;
                newDisplayId = rebind.newLease.displayId;
                dispatcher.markDirtyAndNotifyLocked();
            }
            try {
                int movedTid = runtimeBridge.launchOrMoveFullscreenApp(pkg, cls, userId, newDisplayId, prevTaskId);
                if (movedTid > 0) {
                    synchronized (lock) {
                        WindowSession ws = sessionsById.get(winId);
                        if (ws != null && ws.displayId == newDisplayId) {
                            ws.taskId = movedTid;
                            dispatcher.markDirtyAndNotifyLocked();
                        }
                    }
                }
            } finally {
                vdPool.finishReleaseOldLease(rebind.oldLease);
            }
            migrated++;
            System.out.println("OPALDESK_VD_MIGRATED wid=" + winId + " pkg=" + pkg + " user=" + userId
                    + " oldDisplay=" + rebind.oldLease.displayId + " newDisplay=" + newDisplayId);
        }
        return migrated;
    }

    void reconcileWindowAfterBack(int windowId) {
        int displayId, taskId, userId;
        String pkg;
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null || !ws.isLive() || ws.displayId <= 0) return;
            displayId = ws.displayId;
            taskId = ws.taskId;
            pkg = ws.pkg;
            userId = ws.userId;
        }
        int visibleTid = runtimeBridge.findTopVisibleTaskIdOnDisplay(displayId, pkg, userId);
        if (visibleTid > 0) {
            synchronized (lock) {
                WindowSession ws = sessionsById.get(windowId);
                if (ws != null) {
                    ws.emptyDisplayTicks = 0;
                    ws.emptySinceMs = 0L;
                    if (ws.taskId != visibleTid) {
                        ws.taskId = visibleTid;
                        dispatcher.markDirtyAndNotifyLocked();
                    }
                }
            }
            return;
        }
        boolean stillRunningInBg = runtimeBridge.isTaskRunningWithActivities(taskId, displayId);
        if (stillRunningInBg) {
            System.out.println("OPALDESK_BACK_MOVED_TO_BG_MINIMIZE wid=" + windowId + " pkg=" + pkg);
            dispatcher.performAction(windowId, BridgeProtocol.WIN_ACTION_MINIMIZE, true);
        } else {
            System.out.println("OPALDESK_BACK_EXITED_CLOSE wid=" + windowId + " pkg=" + pkg);
            dispatcher.performAction(windowId, BridgeProtocol.WIN_ACTION_CLOSE, false);
        }
    }

    void reconcileWithSystemTasks(long now) {
        List<Integer> checkWindowIds = new ArrayList<>();
        List<Integer> checkDisplayIds = new ArrayList<>();
        List<Integer> checkTaskIds = new ArrayList<>();
        List<String> checkPkgs = new ArrayList<>();
        List<Integer> checkUserIds = new ArrayList<>();

        synchronized (lock) {
            if (sessionsById.isEmpty()) return;
            for (WindowSession ws : sessionsById.values()) {
                if (ws.isLive() && ws.displayId > 0) {
                    checkWindowIds.add(ws.windowId);
                    checkDisplayIds.add(ws.displayId);
                    checkTaskIds.add(ws.taskId);
                    checkPkgs.add(ws.pkg);
                    checkUserIds.add(ws.userId);
                }
            }
        }

        Set<Integer> aliveTids = runtimeBridge.collectAliveTaskIds();
        Map<Integer, Integer> discoveredVisibleTasks = new LinkedHashMap<>();
        Map<Integer, Boolean> stillRunningBg = new LinkedHashMap<>();
        for (int i = 0; i < checkWindowIds.size(); i++) {
            int wid = checkWindowIds.get(i);
            int dId = checkDisplayIds.get(i);
            int topVisibleTid = runtimeBridge.findTopVisibleTaskIdOnDisplay(
                    dId, checkPkgs.get(i), checkUserIds.get(i));
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
                    if (ws.emptySinceMs <= 0L) ws.emptySinceMs = now;
                    ws.emptyDisplayTicks++;
                    long emptyDurationMs = now - ws.emptySinceMs;
                    if (emptyDurationMs >= CompositorEngine.EMPTY_DISPLAY_SETTLE_MS) {
                        if (!runningBg && ws.taskId > 0 && (now - ws.lastActiveAtMs) > 1500L) {
                            toClose.add(ws.windowId);
                        } else if (ws.emptyDisplayTicks >= 2) {
                            if (runningBg) toMinimize.add(ws.windowId);
                            else toClose.add(ws.windowId);
                        }
                    }
                }
            }
            if (dirty) dispatcher.markDirtyAndNotifyLocked();
        }

        for (int wid : toMinimize) {
            dispatcher.performAction(wid, BridgeProtocol.WIN_ACTION_MINIMIZE, true);
        }
        for (int wid : toClose) {
            dispatcher.performAction(wid, BridgeProtocol.WIN_ACTION_CLOSE, false);
        }
    }
}
