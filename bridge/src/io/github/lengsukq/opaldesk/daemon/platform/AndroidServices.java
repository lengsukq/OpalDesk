package io.github.lengsukq.opaldesk.daemon.platform;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Display;
import android.view.InputEvent;
import android.view.MotionEvent;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.input.ImmsDumpInspector;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OEM-agnostic Android system service binder facade for {@code OpalDeskDaemon} ({@code uid=2000}).
 * Resolves {@code IWindowManager}, {@code IActivityTaskManager}, {@code IInputManager},
 * {@code IDisplayManager}, {@code IActivityManager}, and virtual display IME policies.
 */
public final class AndroidServices {
    public static final String SHELL_PACKAGE = "com.android.shell";

    private static volatile Method sGetServiceMethod;
    private static volatile boolean sGetServiceChecked;
    private static volatile Method sSetActionButtonMethod;
    private static volatile boolean sSetActionButtonChecked;

    public final Context context;
    public final Context systemContext;
    public final DisplayManager displayManager;
    public final InputManager inputManager;
    public final Object iWindowManager;
    public final Object iInputManager;
    public final Object iActivityTaskManager;
    public final Object iDisplayManager;
    public final Object iActivityManager;

    public final Method injectInputEventMethod;
    public final SystemInputInjectorResolver.ResolvedInjector systemInputInjector;
    public final Method setDisplayIdMethod;
    public final Method startActivityMethod;
    public final Method startActivityAsUserMethod;
    public final Method focusTopTaskMethod;
    public final Method moveDisplayToTopMethod;
    public final Method setDisplayImePolicyMethod;
    public final Method getDisplayImePolicyMethod;
    public final Method setShouldShowImeMethod;
    public final Method shouldShowImeMethod;
    public final Method shouldShowSystemDecorsMethod;
    public final Method setIgnoreOrientationRequestMethod;
    public final Method setRemoveContentModeMethod;
    public final Method setForcedDisplaySizeMethod;
    public final Method clearForcedDisplaySizeMethod;
    public final Method setForcedDisplayScalingModeMethod;

    private final Object displayPowerLock = new Object();
    private final Set<Integer> forcedExternalSizeDisplays = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Integer> forcedExternalScalingDisplays = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final HostScreenWakeMonitor wakeMonitor = new HostScreenWakeMonitor();
    private volatile boolean hostScreenOff = false;
    private volatile boolean displayControlResolved = false;
    private volatile Class<?> displayControlClass;
    private volatile Method surfaceControlSetPowerModeMethod;

    public AndroidServices(
            Context context,
            Context systemContext,
            DisplayManager displayManager,
            InputManager inputManager,
            Object iWindowManager,
            Object iInputManager,
            Object iActivityTaskManager,
            Object iDisplayManager,
            Object iActivityManager) {
        this.context = context;
        this.systemContext = systemContext;
        this.displayManager = displayManager;
        this.inputManager = inputManager;
        this.iWindowManager = iWindowManager;
        this.iInputManager = iInputManager;
        this.iActivityTaskManager = iActivityTaskManager;
        this.iDisplayManager = iDisplayManager;
        this.iActivityManager = iActivityManager;

        this.systemInputInjector = SystemInputInjectorResolver.resolve(
                iInputManager != null ? iInputManager.getClass() : null,
                inputManager != null ? inputManager.getClass() : null,
                InputEvent.class);
        this.injectInputEventMethod = (systemInputInjector != null)
                ? systemInputInjector.method
                : resolveInjectInputEventMethod(iInputManager);
        if (this.systemInputInjector == null) {
            System.err.println("OPALDESK_INPUT_INJECTOR_MISSING iim=" + (iInputManager != null)
                    + " im=" + (inputManager != null));
        }
        this.setDisplayIdMethod = findMethodOrNull(InputEvent.class, "setDisplayId", int.class);
        this.startActivityMethod = findMethodByName(iActivityTaskManager, "startActivity", 11);
        this.startActivityAsUserMethod = resolveStartActivityAsUserMethod(iActivityTaskManager);
        this.focusTopTaskMethod = findMethodByName(iActivityTaskManager, "focusTopTask", 1);
        this.moveDisplayToTopMethod = findMethodByName(iWindowManager, "moveDisplayToTop", 2);
        this.setDisplayImePolicyMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setDisplayImePolicy", int.class, int.class)
                : null;
        this.getDisplayImePolicyMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "getDisplayImePolicy", int.class)
                : null;
        this.setShouldShowImeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setShouldShowIme", int.class, boolean.class)
                : null;
        this.shouldShowImeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "shouldShowIme", int.class)
                : null;
        this.shouldShowSystemDecorsMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setShouldShowSystemDecors", int.class, boolean.class)
                : null;
        this.setIgnoreOrientationRequestMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setIgnoreOrientationRequest", int.class, boolean.class)
                : null;
        this.setRemoveContentModeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setRemoveContentMode", int.class, int.class)
                : null;
        this.setForcedDisplaySizeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setForcedDisplaySize", int.class, int.class, int.class)
                : null;
        this.clearForcedDisplaySizeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "clearForcedDisplaySize", int.class)
                : null;
        this.setForcedDisplayScalingModeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setForcedDisplayScalingMode", int.class, int.class)
                : null;
    }

    public static Context createSystemContext() throws ReflectiveOperationException {
        Class<?> atCls = Class.forName("android.app.ActivityThread");
        Object at = atCls.getMethod("systemMain").invoke(null);
        return (Context) atCls.getMethod("getSystemContext").invoke(at);
    }

    public static AndroidServices createForDaemon() throws ReflectiveOperationException {
        Context sysCtx = createSystemContext();
        Context shellCtx = sysCtx;
        try {
            shellCtx = sysCtx.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            System.err.println("OPALDESK_SHELL_CTX_WARN: " + t.getMessage());
        }
        shellCtx = fixShellContext(shellCtx);
        DisplayManager dm = (DisplayManager) shellCtx.getSystemService(Context.DISPLAY_SERVICE);
        InputManager im = (InputManager) shellCtx.getSystemService(Context.INPUT_SERVICE);

        Object wm = getServiceInterface("window", "android.view.IWindowManager$Stub");
        Object iim = getServiceInterface("input", "android.hardware.input.IInputManager$Stub");
        Object atm = getServiceInterface("activity_task", "android.app.IActivityTaskManager$Stub");
        Object idm = getServiceInterface("display", "android.hardware.display.IDisplayManager$Stub");
        Object iam = getServiceInterface("activity", "android.app.IActivityManager$Stub");
        disableLocalDisplayInfoCaches();
        return new AndroidServices(shellCtx, sysCtx, dm, im, wm, iim, atm, idm, iam);
    }

    public static IBinder getServiceBinder(String name) {
        if (name == null || name.isEmpty()) return null;
        if (!sGetServiceChecked) {
            try {
                sGetServiceMethod = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            } catch (ReflectiveOperationException e) {
                System.err.println("OPALDESK_SM_ERR: " + e.getMessage());
            } finally {
                sGetServiceChecked = true;
            }
        }
        Method m = sGetServiceMethod;
        if (m == null) return null;
        try {
            return (IBinder) m.invoke(null, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static Object getServiceInterface(String serviceName, String stubClassName) {
        IBinder binder = getServiceBinder(serviceName);
        if (binder == null) return null;
        try {
            Class<?> stubCls = Class.forName(stubClassName);
            return stubCls.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static Context fixShellContext(Context base) {
        if (base == null) return null;
        setStringFieldQuietly(base, "mOpPackageName", SHELL_PACKAGE);
        setStringFieldQuietly(base, "mBasePackageName", SHELL_PACKAGE);
        try {
            Object attrSrc = buildShellAttributionSource();
            if (attrSrc != null) {
                Field fAttr = base.getClass().getDeclaredField("mAttributionSource");
                fAttr.setAccessible(true);
                fAttr.set(base, attrSrc);
            }
        } catch (NoSuchFieldException ignored) {
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_ATTR_WARN: " + unwrapMessage(e));
        }
        return base;
    }

    /**
     * Queries the current {@code DisplayImePolicy} ({@code 0 = LOCAL}, {@code 1 = FALLBACK_DISPLAY})
     * across Android 11–17, or returns {@code -1} if unavailable.
     */
    public int getDisplayImePolicy(int displayId) {
        if (displayId <= 0 || iWindowManager == null) return -1;
        if (getDisplayImePolicyMethod != null) {
            try {
                Object val = getDisplayImePolicyMethod.invoke(iWindowManager, displayId);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (shouldShowImeMethod != null) {
            try {
                Object val = shouldShowImeMethod.invoke(iWindowManager, displayId);
                if (val instanceof Boolean) {
                    return ((Boolean) val)
                            ? BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
                            : BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return -1;
    }

    /**
     * Applies {@code DisplayImePolicy} ({@code DISPLAY_IME_POLICY_LOCAL} or
     * {@code DISPLAY_IME_POLICY_FALLBACK_DISPLAY}) across Android 11–17.
     */
    public boolean setDisplayImePolicy(int displayId, int imePolicy) {
        if (displayId <= 0 || iWindowManager == null) return false;
        if (setDisplayImePolicyMethod != null) {
            try {
                setDisplayImePolicyMethod.invoke(iWindowManager, displayId, imePolicy);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_IME_POLICY_WARN displayId=" + displayId
                        + " policy=" + imePolicy + ": " + unwrapMessage(e));
            }
        }
        if (setShouldShowImeMethod != null) {
            try {
                boolean local = (imePolicy == BridgeProtocol.DISPLAY_IME_POLICY_LOCAL);
                setShouldShowImeMethod.invoke(iWindowManager, displayId, local);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_IME_SHOW_WARN displayId=" + displayId
                        + " policy=" + imePolicy + ": " + unwrapMessage(e));
            }
        }
        return false;
    }

    /**
     * Configures IME policy for an isolated window VirtualDisplay so tapping text fields
     * triggers soft keyboard fallback on Display 0 (or local IME where supported) instead of dropping input.
     */
    public void configureVirtualDisplayWindowing(int displayId, int imePolicy) {
        if (displayId <= 0 || iWindowManager == null) return;
        if (shouldShowSystemDecorsMethod != null) {
            try {
                shouldShowSystemDecorsMethod.invoke(iWindowManager, displayId, false);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        setDisplayImePolicy(displayId, imePolicy);
        if (setIgnoreOrientationRequestMethod != null) {
            try {
                setIgnoreOrientationRequestMethod.invoke(iWindowManager, displayId, true);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (setRemoveContentModeMethod != null) {
            try {
                // WindowManager.REMOVE_CONTENT_MODE_DESTROY = 2 (1 is MOVE_TO_PRIMARY!):
                // never bounce tasks or splash windows onto Phone Display 0 on teardown.
                setRemoveContentModeMethod.invoke(iWindowManager, displayId, resolveRemoveContentModeDestroy());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    private static volatile int sRemoveContentModeDestroy = -1;

    static int resolveRemoveContentModeDestroy() {
        int cached = sRemoveContentModeDestroy;
        if (cached > 0) return cached;
        int resolved = BridgeProtocol.REMOVE_CONTENT_MODE_DESTROY;
        try {
            Field f = android.view.WindowManager.class.getField("REMOVE_CONTENT_MODE_DESTROY");
            int val = f.getInt(null);
            if (val > 0) {
                resolved = val;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        sRemoveContentModeDestroy = resolved;
        return resolved;
    }

    public boolean isHostScreenOff() {
        return hostScreenOff;
    }

    /**
     * Toggles the physical power state of built-in Display 0 via AOSP {@code SurfaceControl.setDisplayPowerMode}
     * without locking or suspending Android, so remote casting and external monitors stay awake at full FPS
     * while the host OLED/LCD panel draws zero power.
     */
    public boolean setPrimaryPhysicalDisplayPower(boolean screenOn) {
        final int powerMode = screenOn ? 2 /* POWER_MODE_NORMAL */ : 0 /* POWER_MODE_OFF */;
        boolean ok = false;
        synchronized (displayPowerLock) {
            IBinder token = resolvePrimaryPhysicalDisplayTokenLocked();
            Method setPowerMode = resolveSetDisplayPowerModeLocked();
            if (token != null && setPowerMode != null) {
                try {
                    setPowerMode.invoke(null, token, powerMode);
                    ok = true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    System.err.println("OPALDESK_HOST_SCREEN_POWER_ERR mode=" + powerMode + ": " + unwrapMessage(e));
                }
            }
            if (!ok && screenOn) {
                // Fail-safe wake fallback on Android 14+
                ShellCommand.runOutput("cmd", "display", "power-reset", "0");
                ok = true;
            }
            if (ok) {
                hostScreenOff = !screenOn;
            }
        }
        if (ok) {
            if (!screenOn) {
                wakeMonitor.start(() -> setPrimaryPhysicalDisplayPower(true));
            } else {
                wakeMonitor.stop();
            }
        }
        System.out.println("OPALDESK_HOST_SCREEN_POWER on=" + screenOn + " ok=" + ok);
        return ok;
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
            System.err.println("OPALDESK_SC_POWER_METHOD_WARN: " + unwrapMessage(e));
            return null;
        }
    }

    private IBinder resolvePrimaryPhysicalDisplayTokenLocked() {
        long preferredPhysicalId = queryDefaultPhysicalDisplayId();
        // 1. Android 14-17: com.android.server.display.DisplayControl inside /system/framework/services.jar
        if (Build.VERSION.SDK_INT >= 34) {
            IBinder tok = resolveTokenFromDisplayControlLocked(preferredPhysicalId);
            if (tok != null) return tok;
        }
        // 2. Android 11-13 (and fallback): android.view.SurfaceControl static token helpers
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
            System.err.println("OPALDESK_SC_TOKEN_WARN: " + unwrapMessage(e));
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
            System.err.println("OPALDESK_DISPLAY_CONTROL_WARN: " + unwrapMessage(e));
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
                    null,
                    "/system/framework/services.jar",
                    null,
                    null,
                    ClassLoader.getSystemClassLoader(),
                    Build.VERSION.SDK_INT,
                    true,
                    null);
            Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
            loadAndroidServersNativeLib(dc);
            displayControlClass = dc;
            return dc;
        } catch (Throwable t) {
            System.err.println("OPALDESK_DISPLAY_CONTROL_INIT_WARN: " + unwrapMessage(t));
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
        if (displayManager == null) return 0L;
        try {
            Display d0 = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (d0 == null) return 0L;
            String uid = VirtualDisplayFactory.getUniqueId(d0);
            if (uid != null && uid.startsWith("local:")) {
                return Long.parseLong(uid.substring("local:".length()).trim());
            }
        } catch (RuntimeException ignored) {
        }
        return 0L;
    }

    public void focusDisplay(int displayId) {
        if (displayId < 0) return;
        focusDisplayTaskOnly(displayId);
        if (moveDisplayToTopMethod != null && iWindowManager != null) {
            try {
                moveDisplayToTopMethod.invoke(iWindowManager, displayId, "OpalDesk");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    /**
     * Restores Android's global {@code mTopFocusedDisplayId} and mouse {@code PointerChoreographer}
     * default target back onto physical {@link Display#DEFAULT_DISPLAY} (0) when OpalDesk exits,
     * unpins Local Desktop, detaches all window surfaces, or tears down an external/cast session.
     *
     * <p>Without this, offscreen {@code OpalWin-*} VirtualDisplays created with {@code OWN_FOCUS}
     * remain at the top of {@code RootWindowContainer} (or retain {@code PointerCapture}), trapping
     * hardware USB/Bluetooth mice on an invisible surface-less VirtualDisplay and hiding the cursor!
     */
    public void restoreDefaultDisplayTopFocus() {
        ensurePointerIconVisible();
        focusDisplay(Display.DEFAULT_DISPLAY);
    }

    /** Ensures Android 14+ {@code IInputManager.setPointerIconVisible(true)} is restored if supported. */
    public void ensurePointerIconVisible() {
        if (iInputManager != null) {
            Method m = findMethodOrNull(iInputManager.getClass(), "setPointerIconVisible", boolean.class);
            if (m != null) {
                try {
                    m.invoke(iInputManager, true);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
    }

    /**
     * Focuses the top task inside {@code displayId} without moving that VirtualDisplay above
     * Display 0, preserving Display 0 window focus whenever a local IME bridge or search box is active.
     */
    public void focusDisplayTaskOnly(int displayId) {
        if (displayId < 0) return;
        if (focusTopTaskMethod != null && iActivityTaskManager != null) {
            try {
                focusTopTaskMethod.invoke(iActivityTaskManager, displayId);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    public boolean startActivityAsShell(Intent intent, Bundle options) {
        return startActivityAsShellForUser(intent, options, UserProfileAppPolicy.PRIMARY_USER_ID);
    }

    /**
     * Launches an Activity as {@code com.android.shell} targeting {@code userId} (using
     * {@code IActivityTaskManager.startActivityAsUser} when {@code userId > 0}).
     */
    public boolean startActivityAsShellForUser(Intent intent, Bundle options, int userId) {
        int safeUserId = Math.max(0, userId);
        if (intent == null || iActivityTaskManager == null) return false;
        if (safeUserId > 0) {
            if (startActivityAsUserMethod == null) return false;
            try {
                Object rc;
                int paramCount = startActivityAsUserMethod.getParameterCount();
                if (paramCount == 12) {
                    rc = startActivityAsUserMethod.invoke(
                            iActivityTaskManager, null, SHELL_PACKAGE, null, intent,
                            null, null, null, 0, 0, null, options, safeUserId);
                } else if (paramCount == 11) {
                    rc = startActivityAsUserMethod.invoke(
                            iActivityTaskManager, null, SHELL_PACKAGE, intent,
                            null, null, null, 0, 0, null, options, safeUserId);
                } else {
                    return false;
                }
                if (rc instanceof Integer && (Integer) rc < 0) {
                    System.err.println("OPALDESK_START_ACTIVITY_USER_RC user=" + safeUserId
                            + " intent=" + intent + " rc=" + rc);
                    return false;
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_START_ACTIVITY_USER_ERR user=" + safeUserId
                        + " intent=" + intent + ": " + unwrapMessage(e));
                return false;
            }
        }
        if (startActivityMethod == null) return false;
        try {
            Object rc = startActivityMethod.invoke(
                    iActivityTaskManager, null, SHELL_PACKAGE, null, intent,
                    null, null, null, 0, 0, null, options);
            if (rc instanceof Integer && (Integer) rc < 0) {
                System.err.println("OPALDESK_START_ACTIVITY_RC intent=" + intent + " rc=" + rc);
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_START_ACTIVITY_ERR intent=" + intent + ": " + unwrapMessage(e));
            return false;
        }
    }

    /**
     * Launches an Activity via {@code IActivityTaskManager.startActivity} and falls back to
     * {@code am start --display <id> --windowingMode 1} if an OEM ROM (e.g. Samsung OneUI) fails
     * Binder {@code startActivity} with a null caller context.
     */
    public boolean startActivityAsShellWithAmFallback(
            Intent intent, Bundle options, int displayId, boolean clearTask) {
        return startActivityAsShellWithAmFallback(
                intent, options, displayId, clearTask, UserProfileAppPolicy.PRIMARY_USER_ID);
    }

    /**
     * Multi-user overload of {@link #startActivityAsShellWithAmFallback(Intent, Bundle, int, boolean)}:
     * targets {@code userId} via {@code startActivityAsUser} with {@code am start --user <userId>}
     * shell fallback.
     */
    public boolean startActivityAsShellWithAmFallback(
            Intent intent, Bundle options, int displayId, boolean clearTask, int userId) {
        int safeUserId = Math.max(0, userId);
        if (startActivityAsShellForUser(intent, options, safeUserId)) {
            return true;
        }
        if (intent == null || displayId <= 0) {
            return false;
        }
        ComponentName cn = intent.getComponent();
        if (cn == null) {
            return false;
        }
        int flags = intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION;
        if (clearTask) {
            flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK;
        }
        java.util.Map<String, Object> extraMap = new java.util.LinkedHashMap<>();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String k : extras.keySet()) {
                @SuppressWarnings("deprecation")
                Object v = extras.get(k);
                if (v instanceof Boolean || v instanceof Integer || v instanceof String) {
                    extraMap.put(k, v);
                }
            }
        }
        String[] cmd = TaskLaunchPolicy.buildAmStartArgs(
                cn.flattenToShortString(),
                displayId,
                BridgeProtocol.WINDOWING_MODE_FULLSCREEN,
                flags,
                extraMap,
                safeUserId);
        ShellCommand.Result res = ShellCommand.runWithTimeout(cmd, ShellCommand.DEFAULT_TIMEOUT_MS);
        if (!TaskLaunchPolicy.isAmStartResultSuccessful(res.exitCode, res.timedOut, res.output)) {
            System.err.println("OPALDESK_AM_START_FALLBACK_ERR user=" + safeUserId
                    + " displayId=" + displayId
                    + " exit=" + res.exitCode
                    + " timedOut=" + res.timedOut
                    + ": " + TaskLaunchPolicy.summarizeAmStartOutput(res.output));
            return false;
        }
        System.out.println("OPALDESK_AM_START_FALLBACK_OK user=" + safeUserId
                + " displayId=" + displayId + " comp=" + cn.flattenToShortString());
        return true;
    }

    /**
     * Launches {@code ExternalDesktopActivity} directly onto a physical, wireless, or OpalCast
     * external display via {@code com.android.shell} ({@code uid=2000}), clearing stale tasks on
     * other displays first so OEM {@code MoveToDisplayItem} transitions never crash.
     */
    public boolean launchExternalDesktopHost(int displayId, boolean includeExternalExtras, boolean isWireless) {
        if (displayId <= Display.DEFAULT_DISPLAY || displayManager == null) return false;
        Display disp = null;
        try {
            disp = displayManager.getDisplay(displayId);
        } catch (RuntimeException ignored) {
        }
        if (disp == null || !disp.isValid()) {
            System.err.println("OPALDESK_EXT_DESKTOP_LAUNCH_SKIP displayId=" + displayId + " invalid_or_missing");
            return false;
        }
        String cls = BridgeBuildConfig.HELPER_PACKAGE + ".ExternalDesktopActivity";
        boolean removedStale = new TaskApiAdapter(iActivityTaskManager)
                .removeStaleComponentTasksNotOnDisplay(BridgeBuildConfig.HELPER_PACKAGE, cls, displayId);
        if (removedStale) {
            android.os.SystemClock.sleep(60L);
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(BridgeBuildConfig.HELPER_PACKAGE, cls));
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION;
        if (includeExternalExtras) {
            flags |= Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
        }
        if (removedStale) {
            flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK;
        }
        intent.addFlags(flags);
        intent.putExtra("targetDisplayId", displayId);
        if (includeExternalExtras) {
            intent.putExtra("preferPresentation", false);
            intent.putExtra("isWirelessDisplay", isWireless);
            intent.putExtra("shellLaunched", true);
        }
        Bundle opts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        boolean ok = startActivityAsShellWithAmFallback(intent, opts, displayId, removedStale);
        if (ok) {
            focusDisplay(displayId);
        }
        return ok;
    }

    /**
     * Launches {@code ExternalDesktopActivity} directly onto a physical or wireless external
     * display via {@code com.android.shell} ({@code uid=2000}), bypassing Android 14–16
     * background-activity-launch (BAL) and unprivileged cross-display launch restrictions.
     */
    public boolean launchExternalDesktopOnDisplay(int displayId, boolean isWireless) {
        if (displayId <= Display.DEFAULT_DISPLAY || displayManager == null) return false;
        Display disp = null;
        try {
            disp = displayManager.getDisplay(displayId);
        } catch (RuntimeException ignored) {
        }
        if (disp == null || !disp.isValid()) {
            System.err.println("OPALDESK_EXT_DESKTOP_LAUNCH_SKIP displayId=" + displayId + " invalid_or_missing");
            return false;
        }
        Display.Mode mode = disp.getMode();
        String modeDesc = (mode != null)
                ? (mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight() + "@" + Math.round(mode.getRefreshRate()) + "Hz")
                : "unknown";
        boolean ok = launchExternalDesktopHost(displayId, true, isWireless);
        System.out.println("OPALDESK_EXT_DESKTOP_LAUNCH displayId=" + displayId
                + " name=" + disp.getName()
                + " flags=0x" + Integer.toHexString(disp.getFlags())
                + " state=" + disp.getState()
                + " mode=" + modeDesc
                + " wireless=" + isWireless
                + " ok=" + ok);
        return ok;
    }

    @SuppressWarnings("unchecked")
    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        Object am = (iActivityManager != null)
                ? iActivityManager
                : getServiceInterface("activity", "android.app.IActivityManager$Stub");
        if (am == null) return null;
        try {
            return (List<ActivityManager.RunningAppProcessInfo>)
                    am.getClass().getMethod("getRunningAppProcesses").invoke(am);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static void setActionButton(MotionEvent ev, int actionButton) {
        if (ev == null || actionButton == 0) return;
        if (!sSetActionButtonChecked) {
            sSetActionButtonMethod = findMethodOrNull(MotionEvent.class, "setActionButton", int.class);
            sSetActionButtonChecked = true;
        }
        Method m = sSetActionButtonMethod;
        if (m == null) return;
        try {
            m.invoke(ev, actionButton);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    public static Bundle callContentProviderExternal(String authority, String method, String arg, Bundle extras) {
        return callContentProviderExternal(authority, 0, method, arg, extras);
    }

    public static Bundle callContentProviderExternal(
            String authority, int userId, String method, String arg, Bundle extras) {
        IBinder tok = new Binder();
        Object am = getServiceInterface("activity", "android.app.IActivityManager$Stub");
        if (am == null) {
            System.err.println("OPALDESK_PROVIDER_EXT_WARN: IActivityManager unavailable");
            return null;
        }
        int targetUserId = Math.max(0, userId);
        try {
            Object holder = am.getClass()
                    .getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                    .invoke(am, authority, targetUserId, tok, "*opaldesk*");
            if (holder == null) {
                System.err.println("OPALDESK_PROVIDER_EXT_WARN: holder=null authority=" + authority + " user=" + targetUserId);
                return null;
            }
            Object prov = holder.getClass().getField("provider").get(holder);
            if (prov == null) {
                System.err.println("OPALDESK_PROVIDER_EXT_WARN: provider=null authority=" + authority + " user=" + targetUserId);
                return null;
            }

            Method callAttr5 = null;
            Method callStr5 = null;
            Method callStr6 = null;
            for (Method m : prov.getClass().getMethods()) {
                if (!"call".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 5 && "android.content.AttributionSource".equals(pts[0].getName())) callAttr5 = m;
                else if (pts.length == 5 && pts[0] == String.class) callStr5 = m;
                else if (pts.length == 6 && pts[0] == String.class) callStr6 = m;
            }
            if (callAttr5 != null) {
                return (Bundle) callAttr5.invoke(prov, buildShellAttributionSource(), authority, method, arg, extras);
            } else if (callStr5 != null) {
                return (Bundle) callStr5.invoke(prov, SHELL_PACKAGE, authority, method, arg, extras);
            } else if (callStr6 != null) {
                return (Bundle) callStr6.invoke(prov, SHELL_PACKAGE, null, authority, method, arg, extras);
            }
            System.err.println("OPALDESK_PROVIDER_EXT_WARN: no matching IContentProvider.call overload");
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_PROVIDER_EXT_WARN: " + unwrapMessage(e));
        } finally {
            removeContentProviderExternalQuietly(am, authority, tok, targetUserId);
        }
        return null;
    }

    public static void disableLocalDisplayInfoCaches() {
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method m = dmgCls.getMethod("disableLocalDisplayInfoCaches");
            if (Modifier.isStatic(m.getModifiers())) {
                m.invoke(null);
            } else {
                Object dmg = dmgCls.getMethod("getInstance").invoke(null);
                if (dmg != null) m.invoke(dmg);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    /**
     * Switches a physical external display's active resolution & refresh rate using default
     * {@link BridgeProtocol#EXTERNAL_SCALING_SMART_AUTO} scaling policy.
     */
    public boolean setExternalDisplayMode(int displayId, int width, int height, float fps) {
        return setExternalDisplayMode(displayId, width, height, fps, BridgeProtocol.EXTERNAL_SCALING_SMART_AUTO);
    }

    /**
     * Switches a physical external display's active resolution, refresh rate, and scaling mode:
     * <ol>
     *   <li>When {@code width <= 0 || height <= 0}: clears forced size, EDID preference, and forced
     *       scaling mode; then verifies the post-reset state so anisotropic panels (e.g. 4:3
     *       2048x1536 portable monitors with widescreen EDID dimensions) stay full-bleed 1:1 when
     *       allowed, and HyperOS mirroring-backed displays never stay collapsed to phone Display 0
     *       portrait dimensions.</li>
     *   <li>When targeting native physical dimensions with 1:1 unscaled output (explicit opt-in or
     *       auto-detected pixel anisotropy): pins native logical size and sets AOSP
     *       {@code IWindowManager.setForcedDisplayScalingMode(displayId, 1)} (strictly for
     *       {@code displayId > 0}).</li>
     *   <li>Otherwise: ensures OS AUTO scaling is restored, attempts hardware EDID mode switching
     *       across Android 13–17 signatures, and falls back to even-aligned
     *       {@code IWindowManager.setForcedDisplaySize(displayId, w, h)}.</li>
     * </ol>
     */
    @SuppressWarnings("deprecation")
    public boolean setExternalDisplayMode(
            int displayId, int width, int height, float fps, int scalingModePref) {
        if (displayId <= 0 || displayManager == null) return false;
        try {
            Display disp = displayManager.getDisplay(displayId);
            if (disp == null) return false;
            Display.Mode curMode = disp.getMode();
            int nativeW = (curMode != null) ? curMode.getPhysicalWidth() : 0;
            int nativeH = (curMode != null) ? curMode.getPhysicalHeight() : 0;
            android.util.DisplayMetrics extMetrics = new android.util.DisplayMetrics();
            try {
                disp.getRealMetrics(extMetrics);
            } catch (RuntimeException ignored) {
            }

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
                boolean clearedForced = clearForcedExternalDisplaySize(displayId);
                boolean clearedEdid = clearUserPreferredDisplayMode(disp, displayId);
                boolean scalingReset = setForcedExternalDisplayScalingMode(displayId, 0);
                boolean appliedNative1To1 = false;
                boolean recoveredMirror = false;
                int afterW = 0;
                int afterH = 0;

                if (scalingModePref == BridgeProtocol.EXTERNAL_SCALING_NATIVE_1TO1
                        && nativeW >= 320 && nativeH >= 320) {
                    int[] alignedNative = DisplayGeometryUtil.alignExternalForcedSize(nativeW, nativeH);
                    if (alignedNative != null) {
                        boolean pinned = setForcedExternalDisplaySize(displayId, alignedNative[0], alignedNative[1]);
                        boolean offOk = setForcedExternalDisplayScalingMode(displayId, 1);
                        appliedNative1To1 = pinned && offOk;
                    }
                } else {
                    android.os.SystemClock.sleep(60L);
                    Display freshDisp = displayManager.getDisplay(displayId);
                    android.graphics.Point afterPt = new android.graphics.Point();
                    if (freshDisp != null) {
                        try {
                            freshDisp.getRealSize(afterPt);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    afterW = afterPt.x;
                    afterH = afterPt.y;
                    int[] primaryState = getPrimaryDisplayState();
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
                        + " scalingOff=" + scalingOffOk
                        + " ok=" + forcedOk);
                return forcedOk;
            }

            // Ensure standard OS scaling is active whenever leaving 1:1 native mode so non-native
            // resolutions scale cleanly across the display rather than shrinking into a centered box.
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
            System.err.println("OPALDESK_SET_DISP_MODE_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
        return false;
    }

    private static final int DISPLAY_MODE_FLAG_ANISOTROPY_CORRECTION = 1 << 2;

    static boolean isSyntheticOrAnisotropyMode(Display.Mode mode) {
        if (mode == null) return false;
        try {
            Method mSynth = findMethodOrNull(Display.Mode.class, "isSynthetic");
            if (mSynth != null) {
                Object val = mSynth.invoke(mode);
                if (Boolean.TRUE.equals(val)) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        try {
            Method mFlags = findMethodOrNull(Display.Mode.class, "getFlags");
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

    /** Returns the active refresh rate (Hz) of {@code displayId}, or {@code 60f} if unavailable. */
    public float getDisplayRefreshRate(int displayId) {
        if (displayManager == null || displayId < 0) return 60f;
        try {
            Display disp = displayManager.getDisplay(displayId);
            if (disp == null) return 60f;
            Display.Mode cur = disp.getMode();
            float hz = (cur != null && cur.getRefreshRate() > 1f) ? cur.getRefreshRate() : disp.getRefreshRate();
            return DisplayGeometryUtil.clampFps(hz, 60f);
        } catch (RuntimeException ignored) {
            return 60f;
        }
    }

    /** Switches an external physical display's EDID refresh rate at its current resolution. */
    public boolean setExternalDisplayRefreshRate(int displayId, float fps) {
        if (displayId <= 0 || displayManager == null || fps <= 1f) return false;
        try {
            Display disp = displayManager.getDisplay(displayId);
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
            System.err.println("OPALDESK_SET_EXT_FPS_WARN id=" + displayId + ": " + unwrapMessage(e));
            return false;
        }
    }

    private boolean applyUserPreferredDisplayMode(Display disp, int displayId, Display.Mode chosen) {
        if (displayId <= 0 || chosen == null) return false;
        if (disp != null) {
            try {
                Method dispSet = findMethodOrNull(Display.class, "setUserPreferredDisplayMode", Display.Mode.class);
                if (dispSet != null) {
                    dispSet.invoke(disp, chosen);
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_SET_EDID_DISP_WARN id=" + displayId + ": " + unwrapMessage(e));
            }
        }
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            Method dmgSet = findMethodOrNull(dmgCls, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
            if (dmg != null && dmgSet != null) {
                dmgSet.invoke(dmg, displayId, chosen);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SET_EDID_DMG_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
        try {
            Method setM = findMethodOrNull(DisplayManager.class, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
            if (setM != null && displayManager != null) {
                setM.invoke(displayManager, displayId, chosen);
                return true;
            }
            if (iDisplayManager != null) {
                for (Method m : iDisplayManager.getClass().getMethods()) {
                    if (!"setUserPreferredDisplayMode".equals(m.getName())) continue;
                    int pc = m.getParameterCount();
                    if (pc == 2) {
                        m.invoke(iDisplayManager, displayId, chosen);
                        return true;
                    } else if (pc == 3 && m.getParameterTypes()[2] == boolean.class) {
                        m.invoke(iDisplayManager, displayId, chosen, true);
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SET_EDID_MODE_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{
                        "/system/bin/cmd", "display", "set-user-preferred-display-mode",
                        Integer.toString(chosen.getPhysicalWidth()),
                        Integer.toString(chosen.getPhysicalHeight()),
                        String.format(java.util.Locale.US, "%.2f", chosen.getRefreshRate()),
                        Integer.toString(displayId)
                },
                2500L);
        return isCmdDisplaySuccess(res);
    }

    private boolean clearUserPreferredDisplayMode(Display disp, int displayId) {
        if (displayId <= 0) return false;
        if (disp != null) {
            try {
                Method dispClear = findMethodOrNull(Display.class, "clearUserPreferredDisplayMode");
                if (dispClear != null) {
                    dispClear.invoke(disp);
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_CLEAR_EDID_DISP_WARN id=" + displayId + ": " + unwrapMessage(e));
            }
        }
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            Method dmgSet = findMethodOrNull(dmgCls, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
            if (dmg != null && dmgSet != null) {
                dmgSet.invoke(dmg, displayId, null);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_CLEAR_EDID_DMG_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
        try {
            Method clearM = findMethodOrNull(DisplayManager.class, "clearUserPreferredDisplayMode", int.class);
            if (clearM != null && displayManager != null) {
                clearM.invoke(displayManager, displayId);
                return true;
            }
            if (iDisplayManager != null) {
                for (Method m : iDisplayManager.getClass().getMethods()) {
                    if ("clearUserPreferredDisplayMode".equals(m.getName()) && m.getParameterCount() == 1) {
                        m.invoke(iDisplayManager, displayId);
                        return true;
                    }
                }
                for (Method m : iDisplayManager.getClass().getMethods()) {
                    if (!"setUserPreferredDisplayMode".equals(m.getName())) continue;
                    int pc = m.getParameterCount();
                    if (pc == 2) {
                        m.invoke(iDisplayManager, displayId, null);
                        return true;
                    } else if (pc == 3 && m.getParameterTypes()[2] == boolean.class) {
                        m.invoke(iDisplayManager, displayId, null, true);
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_CLEAR_EDID_MODE_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{"/system/bin/cmd", "display", "clear-user-preferred-display-mode", Integer.toString(displayId)},
                2500L);
        return isCmdDisplaySuccess(res);
    }

    private static boolean isCmdDisplaySuccess(ShellCommand.Result res) {
        if (res == null || !res.isSuccess()) return false;
        String out = (res.output != null) ? res.output.toLowerCase(java.util.Locale.US) : "";
        return !out.contains("error") && !out.contains("exception") && !out.contains("unknown command");
    }

    /**
     * Applies AOSP {@code IWindowManager.setForcedDisplayScalingMode(displayId, mode)}
     * ({@code 0 = FORCE_SCALING_MODE_AUTO}, {@code 1 = FORCE_SCALING_MODE_DISABLED}) strictly on
     * secondary displays ({@code displayId > 0}). Never touches Display 0.
     */
    public boolean setForcedExternalDisplayScalingMode(int displayId, int mode) {
        if (displayId <= 0) return false;
        int safeMode = (mode == 1) ? 1 : 0;
        if (safeMode == 1) {
            forcedExternalScalingDisplays.add(displayId);
        } else {
            forcedExternalScalingDisplays.remove(displayId);
        }
        if (iWindowManager != null && setForcedDisplayScalingModeMethod != null) {
            try {
                setForcedDisplayScalingModeMethod.invoke(iWindowManager, displayId, safeMode);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_FORCED_SCALING_BINDER_WARN id=" + displayId
                        + " mode=" + safeMode + ": " + unwrapMessage(e));
            }
        }
        String arg = (safeMode == 1) ? "off" : "auto";
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{"/system/bin/cmd", "window", "scaling", arg, "-d", Integer.toString(displayId)},
                2500L);
        return isCmdDisplaySuccess(res);
    }

    /**
     * Applies a logical display size override strictly on secondary displays ({@code displayId > 0}).
     * Never modifies primary Display 0.
     */
    public boolean setForcedExternalDisplaySize(int displayId, int width, int height) {
        if (displayId <= 0 || width <= 0 || height <= 0) return false;
        if (iWindowManager != null && setForcedDisplaySizeMethod != null) {
            try {
                setForcedDisplaySizeMethod.invoke(iWindowManager, displayId, width, height);
                forcedExternalSizeDisplays.add(displayId);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_FORCED_SIZE_BINDER_WARN id=" + displayId + ": " + unwrapMessage(e));
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

    /** Clears any forced display size strictly on secondary displays ({@code displayId > 0}). */
    public boolean clearForcedExternalDisplaySize(int displayId) {
        if (displayId <= 0) return false;
        forcedExternalSizeDisplays.remove(displayId);
        if (iWindowManager != null && clearForcedDisplaySizeMethod != null) {
            try {
                clearForcedDisplaySizeMethod.invoke(iWindowManager, displayId);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_CLEAR_FORCED_SIZE_BINDER_WARN id=" + displayId + ": " + unwrapMessage(e));
            }
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                new String[]{"/system/bin/cmd", "window", "size", "reset", "-d", Integer.toString(displayId)},
                2500L);
        return res.isSuccess();
    }

    /** Restores native sizing & AUTO scaling for all external displays overridden during this daemon lifecycle. */
    public void clearAllForcedExternalDisplaySizes() {
        List<Integer> scalingSnapshot = new ArrayList<>(forcedExternalScalingDisplays);
        for (Integer id : scalingSnapshot) {
            if (id != null && id > 0) {
                setForcedExternalDisplayScalingMode(id, 0);
            }
        }
        List<Integer> snapshot = new ArrayList<>(forcedExternalSizeDisplays);
        for (Integer id : snapshot) {
            if (id != null && id > 0) {
                clearForcedExternalDisplaySize(id);
            }
        }
    }

    /**
     * Resolves physical Display 0 dimensions and density DPI ({@code [widthPx, heightPx, densityDpi]})
     * for host-adapted DPI scaling of remote standalone floating app displays.
     */
    public int[] getPrimaryDisplayMetrics() {
        int[] state = getPrimaryDisplayState();
        return new int[]{state[0], state[1], state[2]};
    }

    /**
     * Resolves physical Display 0 live rotated dimensions, density DPI, and surface rotation
     * ({@code [widthPx, heightPx, densityDpi, rotation 0..3]}).
     */
    @SuppressWarnings("deprecation")
    public int[] getPrimaryDisplayState() {
        int w = 0;
        int h = 0;
        int dpi = 0;
        int rotation = 0;
        try {
            Display d0 = (displayManager != null) ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d0 != null) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                d0.getRealMetrics(dm);
                w = dm.widthPixels;
                h = dm.heightPixels;
                dpi = dm.densityDpi;
                rotation = DisplayGeometryUtil.normalizeRotation(d0.getRotation());
                if (w <= 0 || h <= 0) {
                    Display.Mode m = d0.getMode();
                    if (m != null) {
                        w = m.getPhysicalWidth();
                        h = m.getPhysicalHeight();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        if ((w <= 0 || h <= 0 || dpi <= 0) && context != null) {
            try {
                android.util.DisplayMetrics rdm = context.getResources().getDisplayMetrics();
                if (w <= 0) w = rdm.widthPixels;
                if (h <= 0) h = rdm.heightPixels;
                if (dpi <= 0) dpi = rdm.densityDpi;
            } catch (RuntimeException ignored) {
            }
        }
        return new int[]{w, h, dpi, rotation};
    }

    /** Resolves AOSP Display uniqueId (e.g. {@code local:4622403537125514005}) across Android 11–17. */
    public String getDisplayUniqueId(int displayId) {
        if (displayId < 0 || displayManager == null) return null;
        try {
            Display disp = displayManager.getDisplay(displayId);
            if (disp == null) return null;
            Method getUid = findMethodOrNull(Display.class, "getUniqueId");
            if (getUid != null) {
                Object uid = getUid.invoke(disp);
                if (uid instanceof String && !((String) uid).isEmpty()) {
                    return (String) uid;
                }
            }
            Class<?> infoCls = Class.forName("android.view.DisplayInfo");
            Object info = infoCls.getDeclaredConstructor().newInstance();
            Method getInfo = findMethodOrNull(Display.class, "getDisplayInfo", infoCls);
            if (getInfo != null) {
                getInfo.invoke(disp, info);
                Field fUid = infoCls.getField("uniqueId");
                Object uid = fUid.get(info);
                if (uid instanceof String && !((String) uid).isEmpty()) {
                    return (String) uid;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_GET_DISP_UID_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
        return null;
    }

    /** Resolves physical connector port byte (0..255) for Android 11 port-association fallback, or -1. */
    public int getDisplayPhysicalPort(int displayId) {
        if (displayId < 0 || displayManager == null) return -1;
        try {
            Display disp = displayManager.getDisplay(displayId);
            if (disp == null) return -1;
            Method getAddr = findMethodOrNull(Display.class, "getAddress");
            if (getAddr == null) return -1;
            Object addr = getAddr.invoke(disp);
            if (addr == null) return -1;
            Method getPort = findMethodOrNull(addr.getClass(), "getPort");
            if (getPort != null) {
                Object portObj = getPort.invoke(addr);
                if (portObj instanceof Number) {
                    return ((Number) portObj).intValue() & 0xFF;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return -1;
    }

    /** Applies hardware mouse pointer speed (-7..+7) via {@code IInputManager.tryPointerSpeed}. */
    private volatile boolean tryPointerSpeedDenied = false;

    public boolean setHardwarePointerSpeed(int speed) {
        int clamped = Math.max(-7, Math.min(7, speed));
        boolean ok = false;
        if (iInputManager != null && !tryPointerSpeedDenied) {
            Method tryM = findMethodOrNull(iInputManager.getClass(), "tryPointerSpeed", int.class);
            if (tryM != null) {
                try {
                    tryM.invoke(iInputManager, clamped);
                    ok = true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    String msg = unwrapMessage(e);
                    if (msg != null && (msg.contains("SecurityException") || msg.contains("SET_POINTER_SPEED"))) {
                        tryPointerSpeedDenied = true;
                    }
                    System.err.println("OPALDESK_PTR_SPEED_WARN: " + msg);
                }
            }
        }
        if (inputManager != null) {
            Method setM = findMethodOrNull(InputManager.class, "setPointerSpeed", Context.class, int.class);
            if (setM != null && context != null) {
                try {
                    setM.invoke(inputManager, context, clamped);
                    ok = true;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        if (!ok && context != null) {
            try {
                ok = Settings.System.putInt(context.getContentResolver(), "pointer_speed", clamped);
            } catch (RuntimeException ignored) {
            }
        }
        if (!ok) {
            ShellCommand.Result res = ShellCommand.runWithTimeout(
                    1200L, "settings", "put", "system", "pointer_speed", String.valueOf(clamped));
            ok = res.isSuccess();
        }
        return ok;
    }

    public static boolean setPrimaryClip(ClipData clip) {
        return AndroidClipboardAndImeHelper.setPrimaryClip(clip);
    }

    public static ClipData getPrimaryClip() {
        return AndroidClipboardAndImeHelper.getPrimaryClip();
    }

    /**
     * Queries the current {@code Settings.Secure.DEFAULT_INPUT_METHOD} component ID,
     * preferring direct {@code ContentResolver} access with a timeout-bounded shell fallback.
     */
    public String getDefaultInputMethodId() {
        return AndroidClipboardAndImeHelper.getDefaultInputMethodId(context);
    }

    /**
     * Lists enabled Android IME component IDs using {@code ime list -s} with fallback to
     * {@code Settings.Secure.ENABLED_INPUT_METHODS}.
     */
    public List<String> listEnabledInputMethodIds() {
        return AndroidClipboardAndImeHelper.listEnabledInputMethodIds(context);
    }

    /**
     * Switches the active Android system IME as {@code uid=2000} via {@code ime set}
     * (automatically calling {@code ime enable} first if needed), with {@code cmd input_method}
     * and {@code Settings.Secure} fallbacks.
     */
    public boolean setDefaultInputMethodId(String imeId) {
        return AndroidClipboardAndImeHelper.setDefaultInputMethodId(context, imeId);
    }

    /** Reads {@code Settings.Secure.show_ime_with_hard_keyboard} ({@code 0}, {@code 1}, or {@code -1} if unset). */
    public int getShowImeWithHardKeyboard() {
        return AndroidClipboardAndImeHelper.getShowImeWithHardKeyboard(context);
    }

    /** Writes {@code Settings.Secure.show_ime_with_hard_keyboard} ({@code 0} or {@code 1}). */
    public boolean setShowImeWithHardKeyboard(int value) {
        return AndroidClipboardAndImeHelper.setShowImeWithHardKeyboard(context, value);
    }

    /**
     * Queries the live {@code input_method} system service in-process via {@link IBinder#dump}
     * using an OS pipe drained concurrently so large view-hierarchy dumps never deadlock.
     */
    public static ImmsDumpInspector.Snapshot inspectInputMethodService(int targetDisplayId) {
        return AndroidClipboardAndImeHelper.inspectInputMethodService(targetDisplayId);
    }

    private static Object buildShellAttributionSource() throws ReflectiveOperationException {
        Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
        Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
        asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, SHELL_PACKAGE);
        return asBuilderCls.getMethod("build").invoke(builder);
    }

    private static void removeContentProviderExternalQuietly(Object am, String authority, IBinder tok, int userId) {
        int safeUserId = Math.max(0, userId);
        try {
            am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(am, authority, tok, safeUserId);
            return;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        try {
            am.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class)
                    .invoke(am, authority, tok);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void setStringFieldQuietly(Object target, String fieldName, String value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public boolean hasSystemInputInjector() {
        if (systemInputInjector == null) return false;
        return systemInputInjector.kind == SystemInputInjectorResolver.Kind.INPUT_MANAGER_2_ARG
                ? (inputManager != null)
                : (iInputManager != null);
    }

    public boolean invokeSystemInjectInputEvent(InputEvent ev, int mode) throws ReflectiveOperationException {
        SystemInputInjectorResolver.ResolvedInjector inj = this.systemInputInjector;
        if (inj == null || ev == null) return false;
        Object res;
        switch (inj.kind) {
            case IINPUT_MANAGER_TARGET_3_ARG:
                if (iInputManager == null) return false;
                res = inj.method.invoke(iInputManager, ev, mode, -1);
                break;
            case INPUT_MANAGER_2_ARG:
                if (inputManager == null) return false;
                res = inj.method.invoke(inputManager, ev, mode);
                break;
            case IINPUT_MANAGER_2_ARG:
            default:
                if (iInputManager == null) return false;
                res = inj.method.invoke(iInputManager, ev, mode);
                break;
        }
        return !(res instanceof Boolean) || (Boolean) res;
    }

    // ---- Multi-User / Cloned & Work Profile Introspection (uid=2000 AOSP) ----

    /**
     * Enumerates active Android secondary user profiles (Dual Apps / App Cloner / Work Profile /
     * Clone Profile / Private Space) via {@code IUserManager}, falling back to {@code pm list users}.
     */
    public List<UserProfileAppPolicy.UserProfileInfo> querySecondaryUserProfiles() {
        return AndroidMultiUserHelper.querySecondaryUserProfiles();
    }

    /** Resolves the normalized profile type tag for {@code userId} (e.g. {@code "clone"}, {@code "work"}). */
    public String resolveProfileTypeForUser(int userId) {
        return AndroidMultiUserHelper.resolveProfileTypeForUser(userId);
    }

    /**
     * Scans all active secondary user profiles for user-facing launchable apps (filtering out
     * system-stub noise in clone profiles via {@link UserProfileAppPolicy}).
     */
    public List<UserProfileAppPolicy.SecondaryAppEntry> querySecondaryUserLaunchableApps() {
        return AndroidMultiUserHelper.querySecondaryUserLaunchableApps(context);
    }

    public String querySecondaryUserLaunchableAppsJson() {
        return UserProfileAppPolicy.encodeSecondaryAppsJson(querySecondaryUserLaunchableApps());
    }

    /** Queries {@code IPackageManager.queryIntentActivities} for a specific Android {@code userId}. */
    public List<ResolveInfo> queryIntentActivitiesForUser(Intent intent, int userId) {
        return AndroidMultiUserHelper.queryIntentActivitiesForUser(intent, userId);
    }

    /** Resolves a launchable main Activity {@link ComponentName} for {@code pkg} inside {@code userId}. */
    public ComponentName resolveLaunchComponentForUser(String pkg, int userId) {
        return AndroidMultiUserHelper.resolveLaunchComponentForUser(context, pkg, userId);
    }

    /** Resolves whether an explicit activity intent exists in {@code userId}. */
    public boolean canResolveActivityForUser(Intent intent, int userId) {
        return AndroidMultiUserHelper.canResolveActivityForUser(context, intent, userId);
    }

    /** Resolves the human-readable application label for {@code pkg} in {@code userId}. */
    public String resolveAppLabelForUser(String pkg, int userId) {
        return AndroidMultiUserHelper.resolveAppLabelForUser(context, pkg, userId);
    }

    /**
     * Resolves the Linux UID for {@code (pkg, userId)} (e.g. {@code 99910256} for user 999),
     * preferring {@code IPackageManager.getPackageUid(..., userId)} with deterministic UID-range fallback.
     */
    public int getPackageUidForUser(String pkg, int userId) {
        return AndroidMultiUserHelper.getPackageUidForUser(context, pkg, userId);
    }

    private static Method resolveStartActivityAsUserMethod(Object atm) {
        if (atm == null) return null;
        Method fallback11 = null;
        for (Method m : atm.getClass().getMethods()) {
            if (!"startActivityAsUser".equals(m.getName())) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 12 && pt[3] == Intent.class && pt[11] == int.class) {
                return m;
            }
            if (pt.length == 11 && pt[2] == Intent.class && pt[10] == int.class) {
                fallback11 = m;
            }
        }
        return fallback11;
    }

    private static Method resolveInjectInputEventMethod(Object iim) {
        if (iim == null) return null;
        Method exact = findMethodOrNull(iim.getClass(), "injectInputEvent", InputEvent.class, int.class);
        return (exact != null) ? exact : findMethodByName(iim, "injectInputEvent", -1);
    }

    static Method findMethodOrNull(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) return null;
        try { return cls.getMethod(name, params); } catch (NoSuchMethodException ignored) { return null; }
    }

    private static Method findMethodByName(Object target, String name, int expectedParamCount) {
        if (target == null) return null;
        for (Method m : target.getClass().getMethods()) {
            if (name.equals(m.getName()) && (expectedParamCount < 0 || m.getParameterCount() == expectedParamCount)) {
                return m;
            }
        }
        return null;
    }

    static String unwrapMessage(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return String.valueOf(t.getCause());
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
