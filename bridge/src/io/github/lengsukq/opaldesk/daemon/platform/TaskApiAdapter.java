package io.github.lengsukq.opaldesk.daemon.platform;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Clean, 100% OEM-agnostic reflection adapter over Android {@code IActivityTaskManager} and
 * {@code ITaskSnapshotManager}.
 *
 * <p>Unlike legacy desktop hacks, this adapter NEVER invokes {@code WINDOWING_MODE_FREEFORM (5)},
 * {@code WINDOWING_MODE_MULTI_WINDOW (6)}, {@code resizeTask}, or OEM leash-nudge timers.
 * Every app runs naturally in standard {@code WINDOWING_MODE_FULLSCREEN (1)} on its own
 * isolated {@code VirtualDisplay}.
 */
public final class TaskApiAdapter {
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int DEFAULT_MAX_TASKS = 40;
    private static final int SNAPSHOT_RESOLUTION_ANY = 3;

    private static volatile Method sSetLaunchWindowingMode;
    private static volatile boolean sSetLaunchWindowingModeChecked;

    private final Object atm;
    private volatile Object taskSnapshotMgr;
    private volatile boolean taskSnapshotMgrChecked;

    public TaskApiAdapter(Object iActivityTaskManager) {
        this.atm = iActivityTaskManager;
    }

    public static ActivityOptions makeFullscreenLaunchOptions(int displayId) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId);
        if (!sSetLaunchWindowingModeChecked) {
            try {
                sSetLaunchWindowingMode = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            } catch (NoSuchMethodException ignored) {
                sSetLaunchWindowingMode = null;
            } finally {
                sSetLaunchWindowingModeChecked = true;
            }
        }
        Method m = sSetLaunchWindowingMode;
        if (m != null) {
            try {
                m.invoke(opts, WINDOWING_MODE_FULLSCREEN);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return opts;
    }

    public List<?> getTasks() {
        return invokeTaskQuery("getTasks", DEFAULT_MAX_TASKS);
    }

    public List<?> getRecentTasks() {
        return invokeTaskQuery("getRecentTasks", DEFAULT_MAX_TASKS);
    }

    public Set<Integer> collectAliveTaskIds() {
        Set<Integer> alive = new HashSet<>();
        List<?> running = getTasks();
        if (running != null) {
            for (Object t : running) {
                int tid = extractTaskId(t);
                if (tid > 0) alive.add(tid);
            }
        }
        List<?> recents = getRecentTasks();
        if (recents != null) {
            for (Object t : recents) {
                int tid = extractTaskId(t);
                if (tid > 0) alive.add(tid);
            }
        }
        return alive;
    }

    public int findRunningOrRecentTaskForPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return -1;
        List<?> running = getTasks();
        if (running != null) {
            for (Object t : running) {
                if (pkg.equals(extractTaskPkg(t))) {
                    int tid = extractTaskId(t);
                    if (tid > 0) return tid;
                }
            }
        }
        List<?> recents = getRecentTasks();
        if (recents != null) {
            for (Object t : recents) {
                if (pkg.equals(extractTaskPkg(t))) {
                    int tid = extractTaskId(t);
                    if (tid > 0) return tid;
                }
            }
        }
        return -1;
    }

    public int findTopTaskIdOnDisplay(int displayId, String expectedPkg) {
        if (displayId <= 0) return -1;
        List<?> running = getTasks();
        if (running == null) return -1;
        int fallbackTid = -1;
        for (Object t : running) {
            if (t == null || readIntField(t, "displayId", -1) != displayId) continue;
            int tid = extractTaskId(t);
            if (tid <= 0) continue;
            String p = extractTaskPkg(t);
            if (expectedPkg != null && expectedPkg.equals(p)) return tid;
            if (fallbackTid <= 0) fallbackTid = tid;
        }
        return fallbackTid;
    }

    /** Returns every currently running taskId hosted on {@code displayId} (deduplicated). */
    public List<Integer> findAllTaskIdsOnDisplay(int displayId) {
        if (displayId <= 0) return Collections.emptyList();
        List<?> running = getTasks();
        if (running == null) return Collections.emptyList();
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (Object t : running) {
            if (t == null || readIntField(t, "displayId", -1) != displayId) continue;
            int tid = extractTaskId(t);
            if (tid > 0) ids.add(tid);
        }
        return ids.isEmpty() ? Collections.emptyList() : new ArrayList<>(ids);
    }

    /**
     * Returns the taskId on {@code displayId} ONLY if the task is currently visible, running,
     * and still hosts at least one active Activity. Returns {@code -1} when the app has finished
     * or called {@code moveTaskToBack(true)} (which otherwise leaves a blank VirtualDisplay).
     */
    public int findTopVisibleTaskIdOnDisplay(int displayId, String expectedPkg) {
        if (displayId <= 0) return -1;
        List<?> running = getTasks();
        if (running == null) return -1;
        int fallbackTid = -1;
        for (Object t : running) {
            if (t == null || readIntField(t, "displayId", -1) != displayId) continue;
            if (!isTaskVisibleAndActive(t)) continue;
            int tid = extractTaskId(t);
            if (tid <= 0) continue;
            String p = extractTaskPkg(t);
            if (expectedPkg != null && expectedPkg.equals(p)) return tid;
            if (fallbackTid <= 0) fallbackTid = tid;
        }
        return fallbackTid;
    }

    /**
     * Returns {@code true} if {@code taskId} (or another task on {@code displayId}) is still in
     * {@code getTasks()} with {@code isRunning && numActivities > 0} (e.g. moved to back rather than
     * exited). Ignores {@code getRecentTasks()}, which retains finished/dead tasks indefinitely.
     */
    public boolean isTaskRunningWithActivities(int taskId, int displayId) {
        List<?> running = getTasks();
        if (running == null) return false;
        for (Object t : running) {
            if (t == null) continue;
            int tid = extractTaskId(t);
            int dId = readIntField(t, "displayId", -1);
            boolean matches = (taskId > 0 && tid == taskId) || (displayId > 0 && dId == displayId);
            if (!matches) continue;
            if (readBooleanField(t, "isRunning", true) && readIntField(t, "numActivities", 1) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTaskVisibleAndActive(Object taskInfo) {
        if (!readBooleanField(taskInfo, "isVisible", true)) return false;
        if (!readBooleanField(taskInfo, "isRunning", true)) return false;
        if (readIntField(taskInfo, "numActivities", 1) <= 0) return false;
        return readObjField(taskInfo, "topActivity") != null;
    }

    public boolean moveRootTaskToDisplay(int taskId, int targetDisplayId) {
        if (taskId <= 0 || targetDisplayId <= 0 || atm == null) return false;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if ("moveRootTaskToDisplay".equals(m.getName()) && m.getParameterCount() == 2) {
                    m.invoke(atm, taskId, targetDisplayId);
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_MOVE_TASK_WARN tid=" + taskId + " dst=" + targetDisplayId + ": " + e.getMessage());
        }
        return false;
    }

    public boolean startActivityFromRecents(int taskId, Bundle options) {
        if (taskId <= 0 || atm == null) return false;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if ("startActivityFromRecents".equals(m.getName()) && m.getParameterCount() == 2) {
                    Object rc = m.invoke(atm, taskId, options);
                    return !(rc instanceof Integer) || (Integer) rc >= 0;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_START_RECENTS_WARN tid=" + taskId + ": " + e.getMessage());
        }
        return false;
    }

    public boolean removeTask(int taskId) {
        if (taskId <= 0 || atm == null) return false;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if ("removeTask".equals(m.getName()) && m.getParameterCount() == 1) {
                    Object r = m.invoke(atm, taskId);
                    return !(r instanceof Boolean) || (Boolean) r;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_REMOVE_TASK_WARN tid=" + taskId + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Captures a compressed JPEG snapshot of {@code taskId} via {@code HardwareBuffer} so
     * hibernated windows display their exact frozen UI frame with minimal RAM footprint.
     */
    public byte[] captureTaskSnapshotJpeg(int taskId) {
        Object rawSnap = captureTaskSnapshotRaw(taskId, true);
        if (rawSnap == null) rawSnap = captureTaskSnapshotRaw(taskId, false);
        if (rawSnap == null) return null;

        HardwareBuffer hb = null;
        Bitmap hwBmp = null;
        Bitmap swBmp = null;
        try {
            hb = (HardwareBuffer) rawSnap.getClass().getMethod("getHardwareBuffer").invoke(rawSnap);
            if (hb == null || hb.isClosed() || hb.getWidth() <= 0 || hb.getHeight() <= 0) return null;
            ColorSpace cs = null;
            try {
                cs = (ColorSpace) rawSnap.getClass().getMethod("getColorSpace").invoke(rawSnap);
            } catch (ReflectiveOperationException ignored) {
            }
            hwBmp = Bitmap.wrapHardwareBuffer(hb, cs);
            if (hwBmp == null) return null;
            swBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
            if (swBmp == null) return null;

            // Cap hibernate preview long edge to 960px for fast sub-15ms IPC transfer
            int w = swBmp.getWidth();
            int h = swBmp.getHeight();
            int maxEdge = Math.max(w, h);
            if (maxEdge > 960) {
                float scale = 960f / maxEdge;
                Bitmap scaled = Bitmap.createScaledBitmap(
                        swBmp, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
                if (scaled != swBmp) {
                    swBmp.recycle();
                    swBmp = scaled;
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
            swBmp.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            System.err.println("OPALDESK_SNAPSHOT_ENCODE_WARN tid=" + taskId + ": " + t.getMessage());
            return null;
        } finally {
            if (swBmp != null) swBmp.recycle();
            if (hwBmp != null) hwBmp.recycle();
            if (hb != null) {
                try { hb.close(); } catch (RuntimeException ignored) {}
            }
        }
    }

    private Object captureTaskSnapshotRaw(int taskId, boolean live) {
        if (taskId <= 0 || atm == null) return null;
        Object mgr = getTaskSnapshotManager();
        if (mgr != null) {
            Object snap = invokeSnapshotOnTarget(mgr, taskId, live);
            if (snap != null) return snap;
        }
        return invokeSnapshotOnTarget(atm, taskId, live);
    }

    private Object getTaskSnapshotManager() {
        if (taskSnapshotMgr != null) return taskSnapshotMgr;
        if (taskSnapshotMgrChecked || atm == null) return null;
        taskSnapshotMgrChecked = true;
        try {
            taskSnapshotMgr = atm.getClass().getMethod("getTaskSnapshotManager").invoke(atm);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return taskSnapshotMgr;
    }

    private static Object invokeSnapshotOnTarget(Object target, int tid, boolean live) {
        if (target == null || tid <= 0) return null;
        if (live) {
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (!"takeTaskSnapshot".equals(m.getName())) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    Object s = null;
                    if (pt.length == 4 && pt[0] == int.class && pt[1] == boolean.class
                            && pt[2] == boolean.class && pt[3] == boolean.class) {
                        s = m.invoke(target, tid, true, true, false);
                    } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class
                            && pt[2] == boolean.class) {
                        s = m.invoke(target, tid, true, true);
                    } else if (pt.length == 2 && pt[0] == int.class && pt[1] == boolean.class) {
                        s = m.invoke(target, tid, true);
                    }
                    if (s != null) return s;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!"getTaskSnapshot".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object s = null;
                if (pt.length == 3 && pt[0] == int.class && pt[1] == long.class && pt[2] == int.class) {
                    s = m.invoke(target, tid, 0L, SNAPSHOT_RESOLUTION_ANY);
                } else if (pt.length == 2 && pt[0] == int.class && pt[1] == boolean.class) {
                    s = m.invoke(target, tid, true);
                } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class
                        && pt[2] == boolean.class) {
                    s = m.invoke(target, tid, true, live);
                } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class
                        && pt[2] == int.class) {
                    s = m.invoke(target, tid, true, SNAPSHOT_RESOLUTION_ANY);
                }
                if (s != null) return s;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private List<?> invokeTaskQuery(String name, int maxNum) {
        if (atm == null) return null;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object res = null;
                if ("getTasks".equals(name)) {
                    if (pt.length == 1) res = m.invoke(atm, maxNum);
                    else if (pt.length == 2) res = m.invoke(atm, maxNum, false);
                    else if (pt.length == 3) res = m.invoke(atm, maxNum, false, true);
                    else if (pt.length == 4) res = m.invoke(atm, maxNum, false, true, -1);
                } else if ("getRecentTasks".equals(name) && pt.length == 3) {
                    res = m.invoke(atm, maxNum, 0, 0);
                }
                if (res instanceof List<?>) return (List<?>) res;
                if (res != null) {
                    try {
                        Object list = res.getClass().getMethod("getList").invoke(res);
                        if (list instanceof List<?>) return (List<?>) list;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_TASK_QUERY_WARN method=" + name + ": " + e.getMessage());
        }
        return null;
    }

    public static int extractTaskId(Object taskInfo) {
        return readIntField(taskInfo, "taskId",
                readIntField(taskInfo, "persistentId", readIntField(taskInfo, "id", -1)));
    }

    public static String extractTaskPkg(Object info) {
        String real = readComponentPkg(info, "realActivity");
        if (real != null && !real.isEmpty()) return real;
        Object bi = readObjField(info, "baseIntent");
        if (bi instanceof Intent) {
            Intent intent = (Intent) bi;
            if (intent.getComponent() != null) {
                String p = intent.getComponent().getPackageName();
                if (p != null && !p.isEmpty()) return p;
            }
            if (intent.getPackage() != null && !intent.getPackage().isEmpty()) {
                return intent.getPackage();
            }
        }
        String base = readComponentPkg(info, "baseActivity");
        if (base != null && !base.isEmpty()) return base;
        return readComponentPkg(info, "topActivity");
    }

    private static String readComponentPkg(Object info, String fieldName) {
        Object comp = readObjField(info, fieldName);
        if (comp instanceof ComponentName) {
            return ((ComponentName) comp).getPackageName();
        }
        return null;
    }

    public static int readIntField(Object obj, String name, int def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException | IllegalArgumentException e) {
                return def;
            }
        }
        return def;
    }

    public static boolean readBooleanField(Object obj, String name, boolean def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException | IllegalArgumentException e) {
                return def;
            }
        }
        return def;
    }

    public static Object readObjField(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
