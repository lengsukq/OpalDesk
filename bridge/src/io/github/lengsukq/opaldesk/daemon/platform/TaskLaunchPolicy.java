package io.github.lengsukq.opaldesk.daemon.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure JVM-testable, 100% OEM-agnostic policy deciding when an existing Display-0 task must be
 * cold-launched cleanly on a target {@code VirtualDisplay} instead of reparented via
 * {@code moveRootTaskToDisplay}.
 *
 * <p>On large-screen Android tablets and foldables, Display-0 tasks running in ActivityEmbedding,
 * letterbox, size-compat, or non-fullscreen multi-window modes carry Display-0 letterbox/input
 * region state inside {@code system_server}. Moving such a task directly onto a VirtualDisplay can
 * leave stale Display-0 input-region bounds attached to its Activity window, causing single taps
 * outside the stale Display-0 region to be cancelled ({@code ACTION_CANCEL}).
 *
 * <p>Uses only standard AOSP {@code TaskInfo} / {@code AppCompatTaskInfo} /
 * {@code WindowConfiguration} reflection fields with zero {@code android.*} compile-time coupling
 * so deterministic JUnit 4 tests run directly on the JVM.
 */
public final class TaskLaunchPolicy {
    public static final int WINDOWING_MODE_UNDEFINED = 0;
    public static final int WINDOWING_MODE_FULLSCREEN = 1;

    /** Minimum standalone horizontal left offset (px) when parent task width is unavailable. */
    private static final int MIN_STANDALONE_LEFT_INSET_PX = 200;
    private static final int MAX_AM_START_SUMMARY_CHARS = 240;
    private static final int MAX_AM_START_SUMMARY_LINES = 3;

    private TaskLaunchPolicy() {}

    /**
     * Inspects a standard Android {@code TaskInfo} (or test stub) and returns a non-null diagnostic
     * reason if the task carries embedded, letterboxed, size-compat, or non-fullscreen state that
     * makes direct reparenting from Display 0 unsafe. Returns {@code null} for clean fullscreen tasks.
     */
    public static String describeCleanLaunchReason(Object taskInfo) {
        if (taskInfo == null) return null;

        if (readBooleanFieldOrMethod(taskInfo, "isEmbedded", "isEmbedded", false)) {
            return "embedded";
        }

        Object cfg = readFieldOrMethod(taskInfo, "configuration", "getConfiguration");
        Object winCfg = readFieldOrMethod(cfg, "windowConfiguration", "getWindowConfiguration");
        int windowingMode = readIntFieldOrMethod(
                winCfg, "mWindowingMode", "getWindowingMode", WINDOWING_MODE_UNDEFINED);
        if (windowingMode != WINDOWING_MODE_UNDEFINED && windowingMode != WINDOWING_MODE_FULLSCREEN) {
            return "windowingMode=" + windowingMode;
        }

        Object taskBounds = readFieldOrMethod(winCfg, "mBounds", "getBounds");
        int taskWidth = readRectWidth(taskBounds);

        // Android 15-17 moved app-compat / letterbox state into TaskInfo.appCompatTaskInfo.
        Object appCompat = readFieldOrMethod(taskInfo, "appCompatTaskInfo", "getAppCompatTaskInfo");
        String compatReason = inspectAppCompatState(appCompat, taskWidth);
        if (compatReason != null) {
            return compatReason;
        }

        // Android 12-14 exposed letterbox / size-compat fields directly on TaskInfo.
        return inspectAppCompatState(taskInfo, taskWidth);
    }

    /**
     * Returns {@code true} when a pre-existing Display-0 candidate task should be removed and
     * cold-launched cleanly on the target VirtualDisplay instead of reparented.
     */
    public static boolean shouldCleanLaunchBeforeReparent(
            int existingTaskId,
            boolean isExplicitSubActivity,
            int candidateTaskId,
            int existingOnTarget,
            int candidateDisplayId,
            String cleanReason) {
        return existingTaskId <= 0
                && !isExplicitSubActivity
                && candidateTaskId > 0
                && candidateTaskId != existingOnTarget
                && candidateDisplayId <= 0
                && cleanReason != null
                && !cleanReason.isEmpty();
    }

    /**
     * Safety net after {@code startActivity}: returns {@code true} only when a pre-existing
     * Display-0 task was recycled onto the VirtualDisplay ({@code landedTaskId == candidateTaskId})
     * and still carries embedded/letterboxed state. Never fires for newly created VirtualDisplay
     * tasks ({@code landedTaskId != candidateTaskId}) or hibernated OpalDesk windows
     * ({@code existingTaskId > 0}).
     */
    public static boolean shouldCleanLaunchAfterRecycle(
            boolean cleanedBeforeLaunch,
            int existingTaskId,
            int candidateTaskId,
            int candidateDisplayId,
            int landedTaskId,
            String landedCleanReason) {
        return !cleanedBeforeLaunch
                && existingTaskId <= 0
                && candidateTaskId > 0
                && candidateDisplayId <= 0
                && landedTaskId == candidateTaskId
                && landedCleanReason != null
                && !landedCleanReason.isEmpty();
    }

    /**
     * Builds an {@code am start} argv list preserving target display, windowing mode, flags, and
     * primitive extras ({@code Integer}, {@code Boolean}, {@code String}) as a shell fallback when
     * direct {@code IActivityTaskManager.startActivity} Binder calls fail on OEM ROMs.
     */
    public static String[] buildAmStartArgs(
            String flattenedComponent,
            int displayId,
            int windowingMode,
            int flags,
            Map<String, Object> extras) {
        return buildAmStartArgs(flattenedComponent, displayId, windowingMode, flags, extras, 0);
    }

    /**
     * Multi-user overload of {@link #buildAmStartArgs(String, int, int, int, Map)}: appends
     * {@code --user <userId>} whenever {@code userId > 0} so cloned / work-profile apps launch
     * cleanly inside their isolated user space on {@code displayId}.
     */
    public static String[] buildAmStartArgs(
            String flattenedComponent,
            int displayId,
            int windowingMode,
            int flags,
            Map<String, Object> extras,
            int userId) {
        List<String> args = new ArrayList<>();
        args.add("am");
        args.add("start");
        if (userId > 0) {
            args.add("--user");
            args.add(String.valueOf(userId));
        }
        args.add("--display");
        args.add(String.valueOf(displayId));
        args.add("--windowingMode");
        args.add(String.valueOf(windowingMode));
        if (flags != 0) {
            args.add("-f");
            args.add("0x" + Integer.toHexString(flags));
        }
        args.add("-n");
        args.add(flattenedComponent);
        if (extras != null && !extras.isEmpty()) {
            for (Map.Entry<String, Object> entry : extras.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();
                if (k == null || k.isEmpty() || v == null) continue;
                if (v instanceof Boolean) {
                    args.add("--ez");
                    args.add(k);
                    args.add(Boolean.toString((Boolean) v));
                } else if (v instanceof Integer) {
                    args.add("--ei");
                    args.add(k);
                    args.add(Integer.toString((Integer) v));
                } else if (v instanceof String) {
                    args.add("--es");
                    args.add(k);
                    args.add((String) v);
                }
            }
        }
        return args.toArray(new String[0]);
    }

    /**
     * Evaluates the exit code, timeout flag, and combined stdout/stderr of {@code am start}.
     *
     * <p>Android's {@code am} / {@code cmd activity} prints {@code Exception occurred while
     * executing 'start': java.lang.SecurityException: Permission Denial: ...} (without the word
     * {@code Error}) and exits non-zero when a ROM rejects {@code --display <id>} launches from
     * {@code uid=2000}. Checking both process status and structured error/exception lines prevents
     * false-positive success reports that block Presentation fallback on the client.
     */
    public static boolean isAmStartResultSuccessful(int exitCode, boolean timedOut, String output) {
        if (timedOut || exitCode != 0) {
            return false;
        }
        if (output == null || output.isEmpty()) {
            return true;
        }
        String[] lines = output.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (isAmStartFailureLine(line)) {
                return false;
            }
        }
        return true;
    }

    /** Collapses multi-line {@code am start} diagnostics into a compact log summary. */
    public static String summarizeAmStartOutput(String output) {
        if (output == null) return "";
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (kept > 0) sb.append(" | ");
            sb.append(line);
            kept++;
            if (kept >= MAX_AM_START_SUMMARY_LINES || sb.length() >= MAX_AM_START_SUMMARY_CHARS) {
                break;
            }
        }
        if (sb.length() > MAX_AM_START_SUMMARY_CHARS) {
            return sb.substring(0, MAX_AM_START_SUMMARY_CHARS - 1).trim() + "…";
        }
        return sb.toString();
    }

    private static boolean isAmStartFailureLine(String line) {
        if (line.startsWith("Error:") || line.startsWith("Error type ") || "Error".equals(line)) {
            return true;
        }
        if (line.startsWith("Exception occurred")) {
            return true;
        }
        if (line.startsWith("Status: error") || line.startsWith("Status: failed")) {
            return true;
        }
        if (line.contains("Permission Denial") || line.contains("permission denied")) {
            return true;
        }
        return line.contains("SecurityException")
                || line.contains("ActivityNotFoundException")
                || line.contains("IllegalArgumentException")
                || line.contains("IllegalStateException")
                || line.contains("NullPointerException")
                || line.contains("AndroidException")
                || line.contains("RemoteException");
    }

    private static String inspectAppCompatState(Object target, int taskWidth) {
        if (target == null) return null;

        if (readBooleanFieldOrMethod(
                target, "topActivityBoundsLetterboxed", "isTopActivityBoundsLetterboxed", false)) {
            return "boundsLetterboxed";
        }
        if (readBooleanFieldOrMethod(
                target, "topActivityInSizeCompat", "isTopActivityInSizeCompat", false)) {
            return "sizeCompat";
        }
        Object letterboxBounds = readFieldOrMethod(
                target, "topActivityLetterboxBounds", "getTopActivityLetterboxBounds");
        if (letterboxBounds != null) {
            return "letterboxBounds";
        }

        Object appBounds = readFieldOrMethod(target, "topActivityAppBounds", "getTopActivityAppBounds");
        if (appBounds != null) {
            int left = readIntField(appBounds, "left", Integer.MIN_VALUE);
            int right = readIntField(appBounds, "right", Integer.MIN_VALUE);
            if (left != Integer.MIN_VALUE && right != Integer.MIN_VALUE) {
                int appWidth = right - left;
                if (taskWidth > 0) {
                    // Use >12.5% horizontal shift or <=87.5% horizontal width so small phone camera
                    // cutouts / 3-button nav bars in landscape never trigger false positives, while
                    // centered pillarboxes and split/embedded panes always match.
                    boolean largeLeftOffset = left > (taskWidth / 8);
                    boolean narrowAppWidth = appWidth > 0 && appWidth <= ((taskWidth * 7) / 8);
                    if (largeLeftOffset || narrowAppWidth) {
                        return "insetAppBounds(left=" + left + ",appW=" + appWidth + ",taskW=" + taskWidth + ")";
                    }
                } else if (left >= MIN_STANDALONE_LEFT_INSET_PX) {
                    return "insetAppBounds(left=" + left + ")";
                }
            }
        }
        return null;
    }

    private static int readRectWidth(Object rect) {
        if (rect == null) return -1;
        int left = readIntField(rect, "left", Integer.MIN_VALUE);
        int right = readIntField(rect, "right", Integer.MIN_VALUE);
        if (left == Integer.MIN_VALUE || right == Integer.MIN_VALUE) return -1;
        return Math.max(0, right - left);
    }

    private static Object readFieldOrMethod(Object obj, String fieldName, String methodName) {
        if (obj == null) return null;
        Object val = readObjField(obj, fieldName);
        if (val != null) return val;
        if (methodName != null) {
            try {
                Method m = obj.getClass().getMethod(methodName);
                if (m.getParameterCount() == 0) {
                    return m.invoke(obj);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static boolean readBooleanFieldOrMethod(
            Object obj, String fieldName, String methodName, boolean def) {
        if (obj == null) return def;
        Boolean fieldVal = readBoxedBooleanField(obj, fieldName);
        if (fieldVal != null) return fieldVal;
        if (methodName != null) {
            try {
                Method m = obj.getClass().getMethod(methodName);
                if (m.getParameterCount() == 0) {
                    Object res = m.invoke(obj);
                    if (res instanceof Boolean) return (Boolean) res;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return def;
    }

    private static int readIntFieldOrMethod(
            Object obj, String fieldName, String methodName, int def) {
        if (obj == null) return def;
        if (methodName != null) {
            try {
                Method m = obj.getClass().getMethod(methodName);
                if (m.getParameterCount() == 0) {
                    Object res = m.invoke(obj);
                    if (res instanceof Integer) return (Integer) res;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return readIntField(obj, fieldName, def);
    }

    private static Object readObjField(Object obj, String name) {
        if (obj == null || name == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Boolean readBoxedBooleanField(Object obj, String name) {
        if (obj == null || name == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == boolean.class) {
                    return f.getBoolean(obj);
                }
                Object val = f.get(obj);
                if (val instanceof Boolean) return (Boolean) val;
                return null;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static int readIntField(Object obj, String name, int def) {
        if (obj == null || name == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == int.class) {
                    return f.getInt(obj);
                }
                Object val = f.get(obj);
                if (val instanceof Integer) return (Integer) val;
                return def;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException | RuntimeException e) {
                return def;
            }
        }
        return def;
    }
}
