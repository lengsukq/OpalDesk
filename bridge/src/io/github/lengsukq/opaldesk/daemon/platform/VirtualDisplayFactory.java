package io.github.lengsukq.opaldesk.daemon.platform;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Single source of truth for creating Android {@link VirtualDisplay} instances across API 30-37+.
 *
 * <p>Dispatches cleanly across Android SDK/hidden-API overload changes:
 * <ol>
 *   <li>API 34+ (Android 14+): {@code DisplayManager.createVirtualDisplay(VirtualDisplayConfig)}</li>
 *   <li>API 33 (Android 13): {@code DisplayManager.createVirtualDisplay(VirtualDisplayConfig, Handler, Callback)}</li>
 *   <li>API 30-32 (Android 11-12): {@code DisplayManager.createVirtualDisplay(MediaProjection, VirtualDisplayConfig, Callback, Handler)}</li>
 *   <li>Universal SDK fallback (API 19+): {@code DisplayManager.createVirtualDisplay(String, int, int, int, Surface, int)}</li>
 * </ol>
 */
public final class VirtualDisplayFactory {
    enum CreateMode {
        CONFIG_1_ARG,
        CONFIG_3_ARG,
        CONFIG_4_ARG,
        LEGACY_6_ARG
    }

    static final class ResolvedCreateMethod {
        final CreateMode mode;
        final Method method;

        ResolvedCreateMethod(CreateMode mode, Method method) {
            this.mode = mode;
            this.method = method;
        }
    }

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean sInitialized;
    private static volatile boolean sUniqueIdInitialized;
    // Remember the highest-capability flag mask accepted by this ROM so subsequent windows skip rejected masks.
    private static volatile int sLastAcceptedFlags = -1;

    private static Constructor<?> sBuilderCtor;
    private static Method sSetFlags;
    private static Method sSetHomeSupported;
    private static Method sSetDisplayIdToMirror;
    private static Method sSetRequestedRefreshRate;
    private static Method sSetSurface;
    private static Method sBuild;
    private static ResolvedCreateMethod sResolvedCreate;
    private static Method sGetUniqueId;

    private static final int[] MIRROR_CANDIDATE_FLAGS = new int[]{
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
    };

    private VirtualDisplayFactory() {}

    /**
     * Resolves the best available {@code createVirtualDisplay} overload on {@code dmClass}.
     * Package-private for deterministic JVM unit testing across simulated API 30-37 signatures.
     */
    static ResolvedCreateMethod resolveCreateMethod(Class<?> dmClass, Class<?> configCls) {
        if (dmClass != null && configCls != null) {
            Method oneArg = findOptionalMethod(dmClass, "createVirtualDisplay", configCls);
            if (oneArg != null) {
                return new ResolvedCreateMethod(CreateMode.CONFIG_1_ARG, oneArg);
            }
            // API 33+: createVirtualDisplay(VirtualDisplayConfig, Handler, VirtualDisplay.Callback)
            for (Method m : dmClass.getMethods()) {
                if (!"createVirtualDisplay".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == configCls) {
                    return new ResolvedCreateMethod(CreateMode.CONFIG_3_ARG, m);
                }
            }
            // API 30-32 @hide: createVirtualDisplay(MediaProjection, VirtualDisplayConfig, Callback, Handler)
            for (Method m : dmClass.getMethods()) {
                if (!"createVirtualDisplay".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 4 && pt[1] == configCls) {
                    return new ResolvedCreateMethod(CreateMode.CONFIG_4_ARG, m);
                }
            }
        }
        return new ResolvedCreateMethod(CreateMode.LEGACY_6_ARG, null);
    }

    private static void ensureInitialized() {
        if (sInitialized) return;
        synchronized (INIT_LOCK) {
            if (sInitialized) return;
            ResolvedCreateMethod resolved = new ResolvedCreateMethod(CreateMode.LEGACY_6_ARG, null);
            try {
                Class<?> builderCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
                Class<?> configCls = Class.forName("android.hardware.display.VirtualDisplayConfig");
                Constructor<?> ctor = builderCls.getConstructor(String.class, int.class, int.class, int.class);
                Method setFlags = builderCls.getMethod("setFlags", int.class);
                Method setSurface = builderCls.getMethod("setSurface", Surface.class);
                Method build = builderCls.getMethod("build");
                ResolvedCreateMethod candidate = resolveCreateMethod(DisplayManager.class, configCls);
                if (candidate.mode != CreateMode.LEGACY_6_ARG && candidate.method != null) {
                    sBuilderCtor = ctor;
                    sSetFlags = setFlags;
                    sSetHomeSupported = findOptionalMethod(builderCls, "setHomeSupported", boolean.class);
                    sSetDisplayIdToMirror = findOptionalMethod(builderCls, "setDisplayIdToMirror", int.class);
                    sSetRequestedRefreshRate = findOptionalMethod(builderCls, "setRequestedRefreshRate", float.class);
                    sSetSurface = setSurface;
                    sBuild = build;
                    resolved = candidate;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_VD_CONFIG_FALLBACK_6ARG: " + e.getMessage());
            }
            sResolvedCreate = resolved;
            System.out.println("OPALDESK_VD_FACTORY_MODE mode=" + resolved.mode.name());
            sInitialized = true;
        }
    }

    /**
     * Creates a VirtualDisplay configured with the exact flags, refresh rate, and output Surface.
     */
    public static VirtualDisplay create(
            DisplayManager dm,
            String name,
            int width,
            int height,
            int dpi,
            int flags,
            float refreshRate,
            Surface surface) throws ReflectiveOperationException {
        if (dm == null) {
            throw new IllegalArgumentException("DisplayManager must not be null");
        }
        ensureInitialized();
        ResolvedCreateMethod resolved = sResolvedCreate;
        if (resolved == null || resolved.mode == CreateMode.LEGACY_6_ARG || resolved.method == null) {
            return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
        }

        Object builder = sBuilderCtor.newInstance(name, width, height, dpi);
        sSetFlags.invoke(builder, flags);
        if (sSetHomeSupported != null) {
            try {
                sSetHomeSupported.invoke(builder, false);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_VD_HOME_FLAG_WARN: " + e.getMessage());
            }
        }
        if (sSetRequestedRefreshRate != null && Float.isFinite(refreshRate) && refreshRate > 0f) {
            try {
                sSetRequestedRefreshRate.invoke(builder, refreshRate);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_VD_REFRESH_FLAG_WARN: " + e.getMessage());
            }
        }
        sSetSurface.invoke(builder, surface);
        Object config = sBuild.invoke(builder);

        switch (resolved.mode) {
            case CONFIG_1_ARG:
                return (VirtualDisplay) resolved.method.invoke(dm, config);
            case CONFIG_3_ARG:
                return (VirtualDisplay) resolved.method.invoke(dm, config, null, null);
            case CONFIG_4_ARG:
                return (VirtualDisplay) resolved.method.invoke(dm, null, config, null, null);
            case LEGACY_6_ARG:
            default:
                return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
        }
    }

    /**
     * Attempts to create a VirtualDisplay using an ordered list of fallback flag bitmasks
     * (e.g. trying {@code STEAL_TOP_FOCUS_DISABLED} or {@code SECURE} first, then degrading).
     */
    public static VirtualDisplay createWithFlagFallback(
            DisplayManager dm,
            String name,
            int width,
            int height,
            int dpi,
            int[] candidateFlags,
            float refreshRate,
            Surface surface,
            int[] chosenFlagsOut) {
        if (dm == null || candidateFlags == null || candidateFlags.length == 0) {
            return null;
        }
        int cached = sLastAcceptedFlags;
        if (cached != -1 && containsFlag(candidateFlags, cached)) {
            VirtualDisplay fast = tryCreateOnce(dm, name, width, height, dpi, cached, refreshRate, surface);
            if (fast != null) {
                if (chosenFlagsOut != null && chosenFlagsOut.length > 0) {
                    chosenFlagsOut[0] = cached;
                }
                return fast;
            }
            sLastAcceptedFlags = -1;
        }
        for (int flags : candidateFlags) {
            if (flags == cached) continue;
            VirtualDisplay vd = tryCreateOnce(dm, name, width, height, dpi, flags, refreshRate, surface);
            if (vd != null) {
                sLastAcceptedFlags = flags;
                if (chosenFlagsOut != null && chosenFlagsOut.length > 0) {
                    chosenFlagsOut[0] = flags;
                }
                return vd;
            }
        }
        return null;
    }

    /**
     * Creates a rootless AOSP mirror {@link VirtualDisplay} capturing {@code displayIdToMirror}
     * (normally {@link Display#DEFAULT_DISPLAY}) onto {@code surface} via
     * {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR}, without polluting the DeX flag cache.
     */
    public static VirtualDisplay createMirrorDisplay(
            DisplayManager dm,
            String name,
            int width,
            int height,
            int dpi,
            int displayIdToMirror,
            Surface surface) {
        if (dm == null || surface == null) {
            return null;
        }
        ensureInitialized();
        ResolvedCreateMethod resolved = sResolvedCreate;
        for (int flags : MIRROR_CANDIDATE_FLAGS) {
            // 1. Prefer VirtualDisplayConfig.Builder with explicit setDisplayIdToMirror (API 34+)
            if (resolved != null && resolved.mode != CreateMode.LEGACY_6_ARG && resolved.method != null) {
                try {
                    Object builder = sBuilderCtor.newInstance(name, width, height, dpi);
                    sSetFlags.invoke(builder, flags);
                    if (sSetDisplayIdToMirror != null && displayIdToMirror >= 0) {
                        try {
                            sSetDisplayIdToMirror.invoke(builder, displayIdToMirror);
                        } catch (ReflectiveOperationException | RuntimeException e) {
                            System.err.println("OPALDESK_VD_MIRROR_ID_WARN: " + e.getMessage());
                        }
                    }
                    sSetSurface.invoke(builder, surface);
                    Object config = sBuild.invoke(builder);
                    VirtualDisplay vd;
                    switch (resolved.mode) {
                        case CONFIG_1_ARG:
                            vd = (VirtualDisplay) resolved.method.invoke(dm, config);
                            break;
                        case CONFIG_3_ARG:
                            vd = (VirtualDisplay) resolved.method.invoke(dm, config, null, null);
                            break;
                        case CONFIG_4_ARG:
                            vd = (VirtualDisplay) resolved.method.invoke(dm, null, config, null, null);
                            break;
                        default:
                            vd = null;
                            break;
                    }
                    if (vd != null && vd.getDisplay() != null) {
                        return vd;
                    }
                    if (vd != null) {
                        vd.release();
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Throwable root = (e instanceof InvocationTargetException && e.getCause() != null)
                            ? e.getCause() : e;
                    System.err.println("OPALDESK_VD_MIRROR_CONFIG_RETRY flags=0x"
                            + Integer.toHexString(flags) + ": " + root);
                }
            }
            // 2. Universal API 19+ DisplayManager.createVirtualDisplay 6-arg fallback
            try {
                VirtualDisplay vd = dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
                if (vd != null && vd.getDisplay() != null) {
                    return vd;
                }
                if (vd != null) {
                    vd.release();
                }
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_VD_MIRROR_LEGACY_RETRY flags=0x"
                        + Integer.toHexString(flags) + ": " + e);
            }
        }
        return null;
    }

    private static VirtualDisplay tryCreateOnce(
            DisplayManager dm,
            String name,
            int width,
            int height,
            int dpi,
            int flags,
            float refreshRate,
            Surface surface) {
        try {
            VirtualDisplay vd = create(dm, name, width, height, dpi, flags, refreshRate, surface);
            if (vd != null && vd.getDisplay() != null) {
                return vd;
            }
            if (vd != null) {
                vd.release();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable root = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            System.err.println("OPALDESK_VD_FLAG_RETRY name=" + name
                    + " flags=0x" + Integer.toHexString(flags) + ": " + root);
        }
        return null;
    }

    private static boolean containsFlag(int[] arr, int target) {
        for (int v : arr) {
            if (v == target) return true;
        }
        return false;
    }

    public static String getUniqueId(Display display) {
        if (display == null) return null;
        if (!sUniqueIdInitialized) {
            synchronized (INIT_LOCK) {
                if (!sUniqueIdInitialized) {
                    sGetUniqueId = findOptionalMethod(Display.class, "getUniqueId");
                    sUniqueIdInitialized = true;
                }
            }
        }
        Method m = sGetUniqueId;
        if (m != null) {
            try {
                Object uid = m.invoke(display);
                if (uid instanceof String && !((String) uid).isEmpty()) {
                    return (String) uid;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_VD_UNIQUE_ID_WARN: " + e.getMessage());
            }
        }
        try {
            Class<?> infoCls = Class.forName("android.view.DisplayInfo");
            Object info = infoCls.getDeclaredConstructor().newInstance();
            Method getInfo = findOptionalMethod(Display.class, "getDisplayInfo", infoCls);
            if (getInfo != null) {
                getInfo.invoke(display, info);
                Object uid = infoCls.getField("uniqueId").get(info);
                if (uid instanceof String && !((String) uid).isEmpty()) {
                    return (String) uid;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static Method findOptionalMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
