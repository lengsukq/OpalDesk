package io.github.lengsukq.opaldesk.daemon.platform;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import java.util.Arrays;
import java.util.List;
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
    private static final Object CLIPBOARD_LOCK = new Object();
    private static final ExecutorService IMMS_DUMP_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OpalImmsDumpWriter");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean sClipboardInitAttempted;
    private static volatile Object sIClipboard;
    private static volatile Method sSetPrimaryClipMethod;
    private static volatile int sSetPrimaryClipArgCount;
    private static volatile Method sGetPrimaryClipMethod;
    private static volatile int sGetPrimaryClipArgCount;

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
    public final Method focusTopTaskMethod;
    public final Method moveDisplayToTopMethod;
    public final Method setDisplayImePolicyMethod;
    public final Method getDisplayImePolicyMethod;
    public final Method setShouldShowImeMethod;
    public final Method shouldShowImeMethod;
    public final Method shouldShowSystemDecorsMethod;
    public final Method setIgnoreOrientationRequestMethod;
    public final Method setRemoveContentModeMethod;

    private final Object displayPowerLock = new Object();
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
        if (intent == null || iActivityTaskManager == null || startActivityMethod == null) return false;
        try {
            Object rc = startActivityMethod.invoke(
                    iActivityTaskManager, null, SHELL_PACKAGE, null, intent,
                    null, null, null, 0, 0, null, options);
            return !(rc instanceof Integer) || (Integer) rc >= 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_START_ACTIVITY_ERR intent=" + intent + ": " + unwrapMessage(e));
            return false;
        }
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
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                BridgeBuildConfig.HELPER_PACKAGE,
                BridgeBuildConfig.HELPER_PACKAGE + ".ExternalDesktopActivity"));
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("targetDisplayId", displayId);
        intent.putExtra("preferPresentation", false);
        intent.putExtra("isWirelessDisplay", isWireless);
        intent.putExtra("shellLaunched", true);
        Bundle opts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        boolean ok = startActivityAsShell(intent, opts);
        if (ok) {
            focusDisplay(displayId);
        }
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
     * Switches a physical external display's active EDID resolution & refresh rate via
     * {@code DisplayManager.setUserPreferredDisplayMode} (or resets when {@code width <= 0}).
     */
    public boolean setExternalDisplayMode(int displayId, int width, int height, float fps) {
        if (displayId <= 0 || displayManager == null) return false;
        try {
            Display disp = displayManager.getDisplay(displayId);
            if (disp == null) return false;
            if (width <= 0 || height <= 0) {
                Method clearM = findMethodOrNull(DisplayManager.class, "clearUserPreferredDisplayMode", int.class);
                if (clearM != null) {
                    clearM.invoke(displayManager, displayId);
                    return true;
                }
                return false;
            }
            Display.Mode[] modes = disp.getSupportedModes();
            if (modes == null || modes.length == 0) return false;
            Display.Mode chosen = null;
            float bestDiff = Float.MAX_VALUE;
            for (Display.Mode m : modes) {
                if (m.getPhysicalWidth() == width && m.getPhysicalHeight() == height) {
                    float diff = (fps > 1f) ? Math.abs(m.getRefreshRate() - fps) : -m.getRefreshRate();
                    if (chosen == null || diff < bestDiff) {
                        chosen = m;
                        bestDiff = diff;
                    }
                }
            }
            if (chosen == null) return false;
            Method setM = findMethodOrNull(DisplayManager.class, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
            if (setM != null) {
                setM.invoke(displayManager, displayId, chosen);
                return true;
            }
            if (iDisplayManager != null) {
                for (Method m : iDisplayManager.getClass().getMethods()) {
                    if ("setUserPreferredDisplayMode".equals(m.getName()) && m.getParameterCount() == 2) {
                        m.invoke(iDisplayManager, displayId, chosen);
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SET_DISP_MODE_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
        return false;
    }

    /**
     * Resolves physical Display 0 dimensions and density DPI ({@code [widthPx, heightPx, densityDpi]})
     * for host-adapted DPI scaling of remote standalone floating app displays.
     */
    @SuppressWarnings("deprecation")
    public int[] getPrimaryDisplayMetrics() {
        int w = 0;
        int h = 0;
        int dpi = 0;
        try {
            Display d0 = (displayManager != null) ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d0 != null) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                d0.getRealMetrics(dm);
                w = dm.widthPixels;
                h = dm.heightPixels;
                dpi = dm.densityDpi;
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
        return new int[]{w, h, dpi};
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
        if (clip == null) return false;
        ensureClipboardInit();
        Object cb = sIClipboard;
        Method m = sSetPrimaryClipMethod;
        if (cb == null || m == null) return false;
        try {
            switch (sSetPrimaryClipArgCount) {
                case 5: m.invoke(cb, clip, SHELL_PACKAGE, null, 0, 0); return true;
                case 4: m.invoke(cb, clip, SHELL_PACKAGE, null, 0); return true;
                case 3: m.invoke(cb, clip, SHELL_PACKAGE, 0); return true;
                case 2: m.invoke(cb, clip, SHELL_PACKAGE); return true;
                default: return false;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_CLIPBOARD_SET_ERR: " + unwrapMessage(e));
            return false;
        }
    }

    public static ClipData getPrimaryClip() {
        ensureClipboardInit();
        Object cb = sIClipboard;
        Method m = sGetPrimaryClipMethod;
        if (cb == null || m == null) return null;
        try {
            Object res;
            switch (sGetPrimaryClipArgCount) {
                case 4: res = m.invoke(cb, SHELL_PACKAGE, null, 0, 0); break;
                case 3: res = m.invoke(cb, SHELL_PACKAGE, null, 0); break;
                case 2: res = m.invoke(cb, SHELL_PACKAGE, 0); break;
                case 1: res = m.invoke(cb, SHELL_PACKAGE); break;
                default: return null;
            }
            return (res instanceof ClipData) ? (ClipData) res : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_CLIPBOARD_GET_ERR: " + unwrapMessage(e));
            return null;
        }
    }

    private static void ensureClipboardInit() {
        if (sClipboardInitAttempted) return;
        synchronized (CLIPBOARD_LOCK) {
            if (sClipboardInitAttempted) return;
            sClipboardInitAttempted = true;
            Object cb = getServiceInterface("clipboard", "android.content.IClipboard$Stub");
            if (cb == null) return;
            sIClipboard = cb;
            for (Method m : cb.getClass().getMethods()) {
                String name = m.getName();
                Class<?>[] pt = m.getParameterTypes();
                if ("setPrimaryClip".equals(name) && sSetPrimaryClipMethod == null) {
                    if (pt.length == 5 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == String.class && pt[3] == int.class && pt[4] == int.class) {
                        sSetPrimaryClipMethod = m; sSetPrimaryClipArgCount = 5;
                    } else if (pt.length == 4 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == String.class && pt[3] == int.class) {
                        sSetPrimaryClipMethod = m; sSetPrimaryClipArgCount = 4;
                    } else if (pt.length == 3 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == int.class) {
                        sSetPrimaryClipMethod = m; sSetPrimaryClipArgCount = 3;
                    } else if (pt.length == 2 && pt[0] == ClipData.class && pt[1] == String.class) {
                        sSetPrimaryClipMethod = m; sSetPrimaryClipArgCount = 2;
                    }
                } else if ("getPrimaryClip".equals(name) && sGetPrimaryClipMethod == null) {
                    if (pt.length == 4 && pt[0] == String.class && pt[1] == String.class
                            && pt[2] == int.class && pt[3] == int.class) {
                        sGetPrimaryClipMethod = m; sGetPrimaryClipArgCount = 4;
                    } else if (pt.length == 3 && pt[0] == String.class && pt[1] == String.class
                            && pt[2] == int.class) {
                        sGetPrimaryClipMethod = m; sGetPrimaryClipArgCount = 3;
                    } else if (pt.length == 2 && pt[0] == String.class && pt[1] == int.class) {
                        sGetPrimaryClipMethod = m; sGetPrimaryClipArgCount = 2;
                    } else if (pt.length == 1 && pt[0] == String.class) {
                        sGetPrimaryClipMethod = m; sGetPrimaryClipArgCount = 1;
                    }
                }
            }
        }
    }

    /**
     * Queries the live {@code input_method} system service in-process via {@link IBinder#dump}
     * using an OS pipe drained concurrently so large view-hierarchy dumps never deadlock.
     */
    public static ImmsDumpInspector.Snapshot inspectInputMethodService(int targetDisplayId) {
        if (targetDisplayId <= 0) return null;
        IBinder immsBinder = getServiceBinder("input_method");
        if (immsBinder == null) return null;
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readEnd = pipe[0];
            final ParcelFileDescriptor writeEnd = pipe[1];
            IMMS_DUMP_WRITER.execute(() -> {
                try {
                    immsBinder.dump(writeEnd.getFileDescriptor(), new String[0]);
                } catch (RemoteException | RuntimeException ignored) {
                } finally {
                    try {
                        writeEnd.close();
                    } catch (IOException ignored) {
                    }
                }
            });
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(readEnd), StandardCharsets.UTF_8),
                    8192)) {
                return ImmsDumpInspector.parseReader(br, targetDisplayId);
            }
        } catch (IOException | RuntimeException e) {
            if (pipe != null) {
                try { pipe[0].close(); } catch (IOException ignored) {}
                try { pipe[1].close(); } catch (IOException ignored) {}
            }
            return null;
        }
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

    private static Method resolveInjectInputEventMethod(Object iim) {
        if (iim == null) return null;
        Method exact = findMethodOrNull(iim.getClass(), "injectInputEvent", InputEvent.class, int.class);
        return (exact != null) ? exact : findMethodByName(iim, "injectInputEvent", -1);
    }

    private static Method findMethodOrNull(Class<?> cls, String name, Class<?>... params) {
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

    private static String unwrapMessage(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return String.valueOf(t.getCause());
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
