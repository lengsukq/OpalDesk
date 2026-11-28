package io.github.lengsukq.opaldesk.daemon.platform;

import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages primary physical display power state (blackout / wake) and secondary external display
 * EDID mode, refresh rate, forced logical size, and forced scaling overrides.
 */
final class AndroidDisplayPowerHelper {
    private static final int DISPLAY_MODE_FLAG_ANISOTROPY_CORRECTION = 1 << 2;
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;
    private static final int WAKE_REASON_APPLICATION = 2;
    private static final int USER_ACTIVITY_EVENT_OTHER = 0;
    private static final int USER_ACTIVITY_FLAGS = 0;
    private static final String WAKE_DETAILS = "opaldesk:wake";
    private static final long WAKE_KEYEVENT_TIMEOUT_MS = 2500L;

    interface DisplayPowerBackend {
        boolean setSurfaceControlPowerMode(boolean screenOn);
        boolean wakeUpPowerManager(long uptimeMillis);
        boolean triggerUserActivity(long uptimeMillis);
        boolean sendWakeupKeyEvent();
        default DisplayBrightnessRestorer.BrightnessSnapshot captureCurrentBrightness() {
            return DisplayBrightnessRestorer.BrightnessSnapshot.defaultSnapshot();
        }
        default boolean restoreSurfaceControlBrightness(float brightness) {
            return false;
        }
        default boolean refreshDisplayPowerControllerBrightness(
                DisplayBrightnessRestorer.BrightnessSnapshot snapshot) {
            return false;
        }
        default int sdkInt() { return 0; }
        long uptimeMillis();
    }

    private final AndroidServices services;
    private final Object displayPowerLock = new Object();
    private final Set<Integer> forcedExternalSizeDisplays = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Integer> forcedExternalScalingDisplays = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final DisplayPowerBackend powerBackend;
    private final HostScreenWakeMonitor wakeMonitor;
    private volatile boolean hostScreenOff = false;
    private volatile boolean displayControlResolved = false;
    private volatile Class<?> displayControlClass;
    private volatile Method surfaceControlSetPowerModeMethod;
    private volatile Object iPowerManager;
    private volatile DisplayBrightnessRestorer.BrightnessSnapshot savedBrightness =
            DisplayBrightnessRestorer.BrightnessSnapshot.defaultSnapshot();

    AndroidDisplayPowerHelper(AndroidServices services) {
        this(services, null, new HostScreenWakeMonitor());
    }

    AndroidDisplayPowerHelper(
            AndroidServices services,
            DisplayPowerBackend powerBackend,
            HostScreenWakeMonitor wakeMonitor) {
        this.services = services;
        this.powerBackend = (powerBackend != null) ? powerBackend : new DefaultDisplayPowerBackend();
        this.wakeMonitor = (wakeMonitor != null) ? wakeMonitor : new HostScreenWakeMonitor();
    }

    boolean isHostScreenOff() {
        return hostScreenOff;
    }

    boolean setPrimaryPhysicalDisplayPower(boolean screenOn) {
        boolean surfaceControlOk = false;
        boolean powerManagerOk = false;
        boolean brightnessOk = false;
        boolean wakeUpOk = false;
        boolean userActivityOk = false;
        boolean keyEventOk = false;
        boolean ok;
        int api = powerBackend.sdkInt();
        synchronized (displayPowerLock) {
            if (!screenOn) {
                try {
                    DisplayBrightnessRestorer.BrightnessSnapshot snap = powerBackend.captureCurrentBrightness();
                    if (snap != null) {
                        savedBrightness = snap;
                    }
                } catch (RuntimeException ignored) {
                    savedBrightness = DisplayBrightnessRestorer.BrightnessSnapshot.defaultSnapshot();
                }
            }
            surfaceControlOk = powerBackend.setSurfaceControlPowerMode(screenOn);
            if (screenOn) {
                DisplayBrightnessRestorer.BrightnessSnapshot snap =
                        (savedBrightness != null) ? savedBrightness : DisplayBrightnessRestorer.BrightnessSnapshot.defaultSnapshot();
                boolean scBrightOk = powerBackend.restoreSurfaceControlBrightness(snap.normalized);
                boolean dpcBrightOk = powerBackend.refreshDisplayPowerControllerBrightness(snap);
                brightnessOk = scBrightOk || dpcBrightOk;
                long nowMs = powerBackend.uptimeMillis();
                wakeUpOk = powerBackend.wakeUpPowerManager(nowMs);
                userActivityOk = powerBackend.triggerUserActivity(nowMs);
                if (!wakeUpOk || !userActivityOk) {
                    keyEventOk = powerBackend.sendWakeupKeyEvent();
                }
                powerManagerOk = wakeUpOk || userActivityOk || keyEventOk;
                ok = surfaceControlOk || powerManagerOk;
            } else {
                ok = surfaceControlOk;
            }
            if (ok) {
                hostScreenOff = !screenOn;
            }
        }
        if (ok) {
            if (!screenOn) {
                startWakeMonitor();
            } else {
                wakeMonitor.stop();
            }
        } else if (screenOn) {
            System.err.println("OPALDESK_HOST_SCREEN_WAKE_FAILED surfaceControl=" + surfaceControlOk
                    + " wakeUp=" + wakeUpOk + " userActivity=" + userActivityOk
                    + " keyEvent=" + keyEventOk + " brightOk=" + brightnessOk
                    + " hostScreenOff=" + hostScreenOff);
            if (hostScreenOff && !wakeMonitor.isActive()) {
                startWakeMonitor();
            }
        }
        System.out.println("OPALDESK_HOST_SCREEN_POWER on=" + screenOn
                + " api=" + api
                + " scOk=" + surfaceControlOk
                + " pmOk=" + powerManagerOk
                + " brightOk=" + brightnessOk
                + " ok=" + ok);
        return ok;
    }

    private void startWakeMonitor() {
        wakeMonitor.start(() -> setPrimaryPhysicalDisplayPower(true));
    }

    private final class DefaultDisplayPowerBackend implements DisplayPowerBackend {
        @Override
        public boolean setSurfaceControlPowerMode(boolean screenOn) {
            int powerMode = screenOn ? POWER_MODE_NORMAL : POWER_MODE_OFF;
            IBinder token = resolvePrimaryPhysicalDisplayTokenLocked();
            Method setPowerMode = resolveSetDisplayPowerModeLocked();
            if (token == null || setPowerMode == null) return false;
            try {
                setPowerMode.invoke(null, token, powerMode);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_HOST_SCREEN_POWER_ERR mode=" + powerMode + ": " + AndroidServices.unwrapMessage(e));
                return false;
            }
        }

        @Override
        public boolean wakeUpPowerManager(long uptimeMillis) {
            return invokePowerManagerWakeUp(resolvePowerManagerLocked(), uptimeMillis);
        }

        @Override
        public boolean triggerUserActivity(long uptimeMillis) {
            return invokePowerManagerUserActivity(resolvePowerManagerLocked(), uptimeMillis);
        }

        @Override
        public boolean sendWakeupKeyEvent() {
            ShellCommand.Result res = ShellCommand.runWithTimeout(
                    new String[]{"input", "keyevent", "224"}, WAKE_KEYEVENT_TIMEOUT_MS);
            return isCmdDisplaySuccess(res);
        }

        @Override
        public DisplayBrightnessRestorer.BrightnessSnapshot captureCurrentBrightness() {
            return DisplayBrightnessRestorer.captureCurrentBrightness();
        }

        @Override
        public boolean restoreSurfaceControlBrightness(float brightness) {
            IBinder token = resolvePrimaryPhysicalDisplayTokenLocked();
            if (token == null) return false;
            return DisplayBrightnessRestorer.restoreSurfaceControlBrightness(token, brightness);
        }

        @Override
        public boolean refreshDisplayPowerControllerBrightness(
                DisplayBrightnessRestorer.BrightnessSnapshot snapshot) {
            return DisplayBrightnessRestorer.refreshDisplayPowerControllerBrightness(snapshot);
        }

        @Override
        public int sdkInt() {
            return Build.VERSION.SDK_INT;
        }

        @Override
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }

    private Object resolvePowerManagerLocked() {
        if (iPowerManager != null) return iPowerManager;
        Object pm = AndroidServices.getServiceInterface("power", "android.os.IPowerManager$Stub");
        if (pm != null) {
            iPowerManager = pm;
        }
        return pm;
    }

    static boolean invokePowerManagerWakeUp(Object powerManager, long uptimeMillis) {
        if (powerManager == null) return false;
        Class<?> cls = powerManager.getClass();
        try {
            Method m4 = AndroidServices.findMethodOrNull(cls, "wakeUp", long.class, int.class, String.class, String.class);
            if (m4 != null) {
                m4.invoke(powerManager, uptimeMillis, WAKE_REASON_APPLICATION, WAKE_DETAILS, AndroidServices.SHELL_PACKAGE);
                return true;
            }
            Method m3 = AndroidServices.findMethodOrNull(cls, "wakeUp", long.class, String.class, String.class);
            if (m3 != null) {
                m3.invoke(powerManager, uptimeMillis, WAKE_DETAILS, AndroidServices.SHELL_PACKAGE);
                return true;
            }
            Method m5 = AndroidServices.findMethodOrNull(cls, "wakeUp", long.class, int.class, String.class, String.class, int.class);
            if (m5 != null) {
                m5.invoke(powerManager, uptimeMillis, WAKE_REASON_APPLICATION, WAKE_DETAILS, AndroidServices.SHELL_PACKAGE, Display.DEFAULT_DISPLAY);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_POWER_WAKEUP_WARN: " + AndroidServices.unwrapMessage(e));
        }
        return false;
    }

    static boolean invokePowerManagerUserActivity(Object powerManager, long uptimeMillis) {
        if (powerManager == null) return false;
        Class<?> cls = powerManager.getClass();
        try {
            Method m4 = AndroidServices.findMethodOrNull(cls, "userActivity", int.class, long.class, int.class, int.class);
            if (m4 != null) {
                m4.invoke(powerManager, Display.DEFAULT_DISPLAY, uptimeMillis, USER_ACTIVITY_EVENT_OTHER, USER_ACTIVITY_FLAGS);
                return true;
            }
            Method m3 = AndroidServices.findMethodOrNull(cls, "userActivity", long.class, int.class, int.class);
            if (m3 != null) {
                m3.invoke(powerManager, uptimeMillis, USER_ACTIVITY_EVENT_OTHER, USER_ACTIVITY_FLAGS);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_POWER_USER_ACTIVITY_WARN: " + AndroidServices.unwrapMessage(e));
        }
        return false;
    }

    private Method resolveSetDisplayPowerModeLocked() {
        if (surfaceControlSetPowerModeMethod != null) return surfaceControlSetPowerModeMethod;
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method m = sc.getDeclaredMethod("setDisplayPowerMode", IBinder.class, int.class);
            m.setAccessible(true);
            surfaceControlSetPowerModeMethod = m;
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SC_POWER_METHOD_WARN: " + AndroidServices.unwrapMessage(e));
            return null;
        }
    }

    private IBinder resolvePrimaryPhysicalDisplayTokenLocked() {
        long preferredPhysicalId = queryDefaultPhysicalDisplayId();
        if (Build.VERSION.SDK_INT >= 34) {
            IBinder tok = resolveTokenFromDisplayControlLocked(preferredPhysicalId);
            if (tok != null) return tok;
        }
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            if (preferredPhysicalId != 0L) {
                try {
                    Method getTok = sc.getDeclaredMethod("getPhysicalDisplayToken", long.class);
                    getTok.setAccessible(true);
                    IBinder tok = (IBinder) getTok.invoke(null, preferredPhysicalId);
                    if (tok != null) return tok;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            try {
                Method getIds = sc.getDeclaredMethod("getPhysicalDisplayIds");
                Method getTok = sc.getDeclaredMethod("getPhysicalDisplayToken", long.class);
                getIds.setAccessible(true);
                getTok.setAccessible(true);
                long[] ids = (long[]) getIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    IBinder tok = (IBinder) getTok.invoke(null, ids[0]);
                    if (tok != null) return tok;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Method getInternal = sc.getDeclaredMethod("getInternalDisplayToken");
                getInternal.setAccessible(true);
                IBinder tok = (IBinder) getInternal.invoke(null);
                if (tok != null) return tok;
            } catch (ReflectiveOperationException ignored) {
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SC_TOKEN_WARN: " + AndroidServices.unwrapMessage(e));
        }
        return null;
    }

    private IBinder resolveTokenFromDisplayControlLocked(long preferredPhysicalId) {
        try {
            Class<?> dc = ensureDisplayControlClassLocked();
            if (dc == null) return null;
            Method getTok = dc.getDeclaredMethod("getPhysicalDisplayToken", long.class);
            getTok.setAccessible(true);
            if (preferredPhysicalId != 0L) {
                IBinder tok = (IBinder) getTok.invoke(null, preferredPhysicalId);
                if (tok != null) return tok;
            }
            Method getIds = dc.getDeclaredMethod("getPhysicalDisplayIds");
            getIds.setAccessible(true);
            long[] ids = (long[]) getIds.invoke(null);
            if (ids != null && ids.length > 0) {
                return (IBinder) getTok.invoke(null, ids[0]);
            }
        } catch (ReflectiveOperationException | RuntimeException | UnsatisfiedLinkError e) {
            System.err.println("OPALDESK_DISPLAY_CONTROL_WARN: " + AndroidServices.unwrapMessage(e));
        }
        return null;
    }

    private Class<?> ensureDisplayControlClassLocked() {
        if (displayControlResolved) return displayControlClass;
        displayControlResolved = true;
        try {
            Class<?> clFactoryCls = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method createCl = clFactoryCls.getDeclaredMethod(
                    "createClassLoader",
                    String.class, String.class, String.class,
                    ClassLoader.class, int.class, boolean.class, String.class);
            createCl.setAccessible(true);
            ClassLoader cl = (ClassLoader) createCl.invoke(
                    null, "/system/framework/services.jar", null, null,
                    ClassLoader.getSystemClassLoader(), Build.VERSION.SDK_INT, true, null);
            Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
            loadAndroidServersNativeLib(dc);
            displayControlClass = dc;
            return dc;
        } catch (Throwable t) {
            System.err.println("OPALDESK_DISPLAY_CONTROL_INIT_WARN: " + AndroidServices.unwrapMessage(t));
            return null;
        }
    }

    private static void loadAndroidServersNativeLib(Class<?> displayControlCls) {
        try {
            Method m = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            m.setAccessible(true);
            m.invoke(Runtime.getRuntime(), displayControlCls, "android_servers");
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Method m = Runtime.class.getDeclaredMethod("loadLibrary0", ClassLoader.class, Class.class, String.class);
            m.setAccessible(true);
            m.invoke(Runtime.getRuntime(), displayControlCls.getClassLoader(), displayControlCls, "android_servers");
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            System.loadLibrary("android_servers");
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private long queryDefaultPhysicalDisplayId() {
        if (services.displayManager == null) return 0L;
        try {
            Display d0 = services.displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (d0 == null) return 0L;
            String uid = VirtualDisplayFactory.getUniqueId(d0);
            if (uid != null && uid.startsWith("local:")) {
                return Long.parseLong(uid.substring("local:".length()).trim());
            }
        } catch (RuntimeException ignored) {
        }
        return 0L;
    }

    @SuppressWarnings("deprecation")
    boolean setExternalDisplayMode(int displayId, int width, int height, float fps, int scalingModePref) {
        if (displayId <= 0 || services.displayManager == null) return false;
        try {
            Display disp = services.displayManager.getDisplay(displayId);
            if (disp == null) return false;
            Display.Mode curMode = disp.getMode();
            int nativeW = (curMode != null) ? curMode.getPhysicalWidth() : 0;
            int nativeH = (curMode != null) ? curMode.getPhysicalHeight() : 0;
            android.util.DisplayMetrics extMetrics = new android.util.DisplayMetrics();
            try { disp.getRealMetrics(extMetrics); } catch (RuntimeException ignored) {}

            Display.Mode[] modes = disp.getSupportedModes();
            int modeCount = (modes != null) ? modes.length : 0;
            int[] widths = new int[modeCount];
            int[] heights = new int[modeCount];
            float[] rates = new float[modeCount];
            boolean[] syntheticFlags = new boolean[modeCount];
            for (int i = 0; i < modeCount; i++) {
                widths[i] = modes[i].getPhysicalWidth();
                heights[i] = modes[i].getPhysicalHeight();
                rates[i] = modes[i].getRefreshRate();
                syntheticFlags[i] = isSyntheticOrAnisotropyMode(modes[i]);
            }

            boolean hasAnisotropy = DisplayGeometryUtil.hasExternalPixelAnisotropy(
                    nativeW, nativeH, extMetrics.xdpi, extMetrics.ydpi, widths, heights, syntheticFlags);

            if (width <= 0 || height <= 0) {
                return resetExternalDisplayMode(displayId, disp, nativeW, nativeH, extMetrics, hasAnisotropy, scalingModePref);
            }

            boolean disableScaling = DisplayGeometryUtil.shouldDisableScalingForNativeAnisotropy(
                    width, height, nativeW, nativeH, hasAnisotropy, scalingModePref);
            if (disableScaling) {
                int[] aligned = DisplayGeometryUtil.alignExternalForcedSize(width, height);
                if (aligned == null) return false;
                if (fps > 1f && modeCount > 0) {
                    int fpsIdx = DisplayGeometryUtil.selectBestEdidModeIndex(
                            widths, heights, rates, syntheticFlags, nativeW, nativeH, fps);
                    if (fpsIdx >= 0 && !syntheticFlags[fpsIdx]) {
                        applyUserPreferredDisplayMode(disp, displayId, modes[fpsIdx]);
                    }
                }
                boolean forcedOk = setForcedExternalDisplaySize(displayId, aligned[0], aligned[1]);
                boolean scalingOffOk = setForcedExternalDisplayScalingMode(displayId, 1);
                System.out.println("OPALDESK_SET_DISP_MODE_NATIVE_1TO1 id=" + displayId
                        + " req=" + width + "x" + height + "@" + fps
                        + " native=" + nativeW + "x" + nativeH
                        + " dpi=" + Math.round(extMetrics.xdpi) + "x" + Math.round(extMetrics.ydpi)
                        + " anisotropy=" + hasAnisotropy
                        + " applied=" + aligned[0] + "x" + aligned[1]
                        + " scalingOff=" + scalingOffOk + " ok=" + forcedOk);
                return forcedOk;
            }

            if (forcedExternalScalingDisplays.contains(displayId)
                    || scalingModePref == BridgeProtocol.EXTERNAL_SCALING_FORCE_SYSTEM_AUTO) {
                setForcedExternalDisplayScalingMode(displayId, 0);
            }

            int exactIdx = DisplayGeometryUtil.selectBestEdidModeIndex(
                    widths, heights, rates, syntheticFlags, width, height, fps);
            if (exactIdx >= 0) {
                clearForcedExternalDisplaySize(displayId);
                if (applyUserPreferredDisplayMode(disp, displayId, modes[exactIdx])) {
                    System.out.println("OPALDESK_SET_DISP_MODE_EDID id=" + displayId
                            + " mode=" + widths[exactIdx] + "x" + heights[exactIdx] + "@" + rates[exactIdx]
                            + " synthetic=" + syntheticFlags[exactIdx]);
                    return true;
                }
            }

            int[] aligned = DisplayGeometryUtil.alignExternalForcedSize(width, height);
            if (aligned == null) return false;
            if (fps > 1f && modeCount > 0) {
                int fpsIdx = DisplayGeometryUtil.selectBestEdidModeForRefreshRate(
                        widths, heights, rates, nativeW, nativeH, fps);
                if (fpsIdx >= 0) {
                    applyUserPreferredDisplayMode(disp, displayId, modes[fpsIdx]);
                }
            }
            boolean forcedOk = setForcedExternalDisplaySize(displayId, aligned[0], aligned[1]);
            System.out.println("OPALDESK_SET_DISP_MODE_FORCED id=" + displayId
                    + " req=" + width + "x" + height + "@" + fps
                    + " applied=" + aligned[0] + "x" + aligned[1] + " ok=" + forcedOk);
            return forcedOk;
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_SET_DISP_MODE_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private boolean resetExternalDisplayMode(
            int displayId, Display disp, int nativeW, int nativeH,
            android.util.DisplayMetrics extMetrics, boolean hasAnisotropy, int scalingModePref) {
        boolean clearedForced = clearForcedExternalDisplaySize(displayId);
        boolean clearedEdid = clearUserPreferredDisplayMode(disp, displayId);
        boolean scalingReset = setForcedExternalDisplayScalingMode(displayId, 0);
        boolean appliedNative1To1 = false;
        boolean recoveredMirror = false;
        int afterW = 0;
        int afterH = 0;

        if (scalingModePref == BridgeProtocol.EXTERNAL_SCALING_NATIVE_1TO1 && nativeW >= 320 && nativeH >= 320) {
            int[] alignedNative = DisplayGeometryUtil.alignExternalForcedSize(nativeW, nativeH);
            if (alignedNative != null) {
                boolean pinned = setForcedExternalDisplaySize(displayId, alignedNative[0], alignedNative[1]);
                boolean offOk = setForcedExternalDisplayScalingMode(displayId, 1);
                appliedNative1To1 = pinned && offOk;
            }
        } else {
            android.os.SystemClock.sleep(60L);
            Display freshDisp = services.displayManager.getDisplay(displayId);
            android.graphics.Point afterPt = new android.graphics.Point();
            if (freshDisp != null) {
                try { freshDisp.getRealSize(afterPt); } catch (RuntimeException ignored) {}
            }
            afterW = afterPt.x;
            afterH = afterPt.y;
            int[] primaryState = services.getPrimaryDisplayState();
            boolean mirrorCollapsed = DisplayGeometryUtil.isMirroredPrimaryDisplayCollapse(
                    nativeW, nativeH, afterW, afterH, primaryState[0], primaryState[1]);
            boolean autoDisableScaling = DisplayGeometryUtil.shouldDisableScalingForNativeAnisotropy(
                    nativeW, nativeH, nativeW, nativeH, hasAnisotropy, scalingModePref);
            if (autoDisableScaling && nativeW >= 320 && nativeH >= 320) {
                int[] alignedNative = DisplayGeometryUtil.alignExternalForcedSize(nativeW, nativeH);
                if (alignedNative != null) {
                    boolean pinned = setForcedExternalDisplaySize(displayId, alignedNative[0], alignedNative[1]);
                    boolean offOk = setForcedExternalDisplayScalingMode(displayId, 1);
                    appliedNative1To1 = pinned && offOk;
                }
            } else if (mirrorCollapsed && nativeW >= 320 && nativeH >= 320) {
                int[] alignedNative = DisplayGeometryUtil.alignExternalForcedSize(nativeW, nativeH);
                if (alignedNative != null) {
                    recoveredMirror = setForcedExternalDisplaySize(displayId, alignedNative[0], alignedNative[1]);
                }
            }
        }

        System.out.println("OPALDESK_RESET_DISP_MODE id=" + displayId
                + " native=" + nativeW + "x" + nativeH
                + " dpi=" + Math.round(extMetrics.xdpi) + "x" + Math.round(extMetrics.ydpi)
                + " anisotropy=" + hasAnisotropy
                + " forcedCleared=" + clearedForced
                + " edidCleared=" + clearedEdid
                + " scalingReset=" + scalingReset
                + " after=" + afterW + "x" + afterH
                + " mirrorRecovered=" + recoveredMirror
                + " native1to1=" + appliedNative1To1);
        return clearedForced || clearedEdid || scalingReset || appliedNative1To1 || recoveredMirror;
    }

    static boolean isSyntheticOrAnisotropyMode(Display.Mode mode) {
        if (mode == null) return false;
        try {
            Method mSynth = AndroidServices.findMethodOrNull(Display.Mode.class, "isSynthetic");
            if (mSynth != null && Boolean.TRUE.equals(mSynth.invoke(mode))) return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        try {
            Method mFlags = AndroidServices.findMethodOrNull(Display.Mode.class, "getFlags");
            if (mFlags != null) {
                Object val = mFlags.invoke(mode);
                if (val instanceof Number
                        && ((((Number) val).intValue()) & DISPLAY_MODE_FLAG_ANISOTROPY_CORRECTION) != 0) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }

    boolean setExternalDisplayRefreshRate(int displayId, float fps) {
        if (displayId <= 0 || services.displayManager == null || fps <= 1f) return false;
        try {
            Display disp = services.displayManager.getDisplay(displayId);
            if (disp == null) return false;
            Display.Mode[] modes = disp.getSupportedModes();
            if (modes == null || modes.length == 0) return false;
            int[] widths = new int[modes.length];
            int[] heights = new int[modes.length];
            float[] rates = new float[modes.length];
            for (int i = 0; i < modes.length; i++) {
                widths[i] = modes[i].getPhysicalWidth();
                heights[i] = modes[i].getPhysicalHeight();
                rates[i] = modes[i].getRefreshRate();
            }
            Display.Mode cur = disp.getMode();
            int curW = (cur != null) ? cur.getPhysicalWidth() : 0;
            int curH = (cur != null) ? cur.getPhysicalHeight() : 0;
            int idx = DisplayGeometryUtil.selectBestEdidModeForRefreshRate(widths, heights, rates, curW, curH, fps);
            if (idx < 0) return false;
            boolean ok = applyUserPreferredDisplayMode(disp, displayId, modes[idx]);
            if (ok) {
                System.out.println("OPALDESK_SET_EXT_FPS_EDID id=" + displayId
                        + " mode=" + widths[idx] + "x" + heights[idx] + "@" + rates[idx]);
            }
            return ok;
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_SET_EXT_FPS_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
            return false;
        }
    }

    private boolean applyUserPreferredDisplayMode(Display disp, int displayId, Display.Mode chosen) {
        if (displayId <= 0 || chosen == null) return false;
        if (disp != null) {
            try {
                Method dispSet = AndroidServices.findMethodOrNull(Display.class, "setUserPreferredDisplayMode", Display.Mode.class);
                if (dispSet != null) { dispSet.invoke(disp, chosen); return true; }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_SET_EDID_DISP_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
            }
        }
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            Method dmgSet = AndroidServices.findMethodOrNull(dmgCls, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
            if (dmg != null && dmgSet != null) { dmgSet.invoke(dmg, displayId, chosen); return true; }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SET_EDID_DMG_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
        }
        try {
            Method setM = AndroidServices.findMethodOrNull(DisplayManager.class, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
            if (setM != null && services.displayManager != null) {
                setM.invoke(services.displayManager, displayId, chosen);
                return true;
            }
            if (services.iDisplayManager != null) {
                for (Method m : services.iDisplayManager.getClass().getMethods()) {
                    if (!"setUserPreferredDisplayMode".equals(m.getName())) continue;
                    int pc = m.getParameterCount();
                    if (pc == 2) { m.invoke(services.iDisplayManager, displayId, chosen); return true; }
                    if (pc == 3 && m.getParameterTypes()[2] == boolean.class) {
                        m.invoke(services.iDisplayManager, displayId, chosen, true);
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SET_EDID_MODE_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{
                        "/system/bin/cmd", "display", "set-user-preferred-display-mode",
                        Integer.toString(chosen.getPhysicalWidth()),
                        Integer.toString(chosen.getPhysicalHeight()),
                        String.format(Locale.US, "%.2f", chosen.getRefreshRate()),
                        Integer.toString(displayId)
                }, 2500L);
        return isCmdDisplaySuccess(res);
    }

    private boolean clearUserPreferredDisplayMode(Display disp, int displayId) {
        if (displayId <= 0) return false;
        if (disp != null) {
            try {
                Method dispClear = AndroidServices.findMethodOrNull(Display.class, "clearUserPreferredDisplayMode");
                if (dispClear != null) { dispClear.invoke(disp); return true; }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_CLEAR_EDID_DISP_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
            }
        }
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            Method dmgSet = AndroidServices.findMethodOrNull(dmgCls, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
            if (dmg != null && dmgSet != null) { dmgSet.invoke(dmg, displayId, null); return true; }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_CLEAR_EDID_DMG_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
        }
        try {
            Method clearM = AndroidServices.findMethodOrNull(DisplayManager.class, "clearUserPreferredDisplayMode", int.class);
            if (clearM != null && services.displayManager != null) {
                clearM.invoke(services.displayManager, displayId);
                return true;
            }
            if (services.iDisplayManager != null) {
                for (Method m : services.iDisplayManager.getClass().getMethods()) {
                    if ("clearUserPreferredDisplayMode".equals(m.getName()) && m.getParameterCount() == 1) {
                        m.invoke(services.iDisplayManager, displayId);
                        return true;
                    }
                }
                for (Method m : services.iDisplayManager.getClass().getMethods()) {
                    if (!"setUserPreferredDisplayMode".equals(m.getName())) continue;
                    int pc = m.getParameterCount();
                    if (pc == 2) { m.invoke(services.iDisplayManager, displayId, null); return true; }
                    if (pc == 3 && m.getParameterTypes()[2] == boolean.class) {
                        m.invoke(services.iDisplayManager, displayId, null, true);
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_CLEAR_EDID_MODE_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{"/system/bin/cmd", "display", "clear-user-preferred-display-mode", Integer.toString(displayId)},
                2500L);
        return isCmdDisplaySuccess(res);
    }

    private static boolean isCmdDisplaySuccess(ShellCommand.Result res) {
        if (res == null || !res.isSuccess()) return false;
        String out = (res.output != null) ? res.output.toLowerCase(Locale.US) : "";
        return !out.contains("error") && !out.contains("exception") && !out.contains("unknown command");
    }

    boolean setForcedExternalDisplayScalingMode(int displayId, int mode) {
        if (displayId <= 0) return false;
        int safeMode = (mode == 1) ? 1 : 0;
        if (safeMode == 1) forcedExternalScalingDisplays.add(displayId);
        else forcedExternalScalingDisplays.remove(displayId);
        if (services.iWindowManager != null && services.setForcedDisplayScalingModeMethod != null) {
            try {
                services.setForcedDisplayScalingModeMethod.invoke(services.iWindowManager, displayId, safeMode);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_FORCED_SCALING_BINDER_WARN id=" + displayId
                        + " mode=" + safeMode + ": " + AndroidServices.unwrapMessage(e));
            }
        }
        String arg = (safeMode == 1) ? "off" : "auto";
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{"/system/bin/cmd", "window", "scaling", arg, "-d", Integer.toString(displayId)}, 2500L);
        return isCmdDisplaySuccess(res);
    }

    boolean setForcedExternalDisplaySize(int displayId, int width, int height) {
        if (displayId <= 0 || width <= 0 || height <= 0) return false;
        if (services.iWindowManager != null && services.setForcedDisplaySizeMethod != null) {
            try {
                services.setForcedDisplaySizeMethod.invoke(services.iWindowManager, displayId, width, height);
                forcedExternalSizeDisplays.add(displayId);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_FORCED_SIZE_BINDER_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
            }
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{"/system/bin/cmd", "window", "size", width + "x" + height, "-d", Integer.toString(displayId)},
                2500L);
        if (res.isSuccess()) {
            forcedExternalSizeDisplays.add(displayId);
            return true;
        }
        return false;
    }

    boolean clearForcedExternalDisplaySize(int displayId) {
        if (displayId <= 0) return false;
        forcedExternalSizeDisplays.remove(displayId);
        if (services.iWindowManager != null && services.clearForcedDisplaySizeMethod != null) {
            try {
                services.clearForcedDisplaySizeMethod.invoke(services.iWindowManager, displayId);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_CLEAR_FORCED_SIZE_BINDER_WARN id=" + displayId + ": " + AndroidServices.unwrapMessage(e));
            }
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{"/system/bin/cmd", "window", "size", "reset", "-d", Integer.toString(displayId)},
                2500L);
        return res.isSuccess();
    }

    void clearAllForcedExternalDisplaySizes() {
        List<Integer> scalingSnapshot = new ArrayList<>(forcedExternalScalingDisplays);
        for (Integer id : scalingSnapshot) {
            if (id != null && id > 0) setForcedExternalDisplayScalingMode(id, 0);
        }
        List<Integer> snapshot = new ArrayList<>(forcedExternalSizeDisplays);
        for (Integer id : snapshot) {
            if (id != null && id > 0) clearForcedExternalDisplaySize(id);
        }
    }
}
