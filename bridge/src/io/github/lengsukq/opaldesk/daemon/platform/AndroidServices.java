package io.github.lengsukq.opaldesk.daemon.platform;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
import android.view.InputEvent;
import android.view.MotionEvent;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.input.ImmsDumpInspector;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * OEM-agnostic Android system service binder facade for {@code OpalDeskDaemon} ({@code uid=2000}).
 * Retains public binder/method fields and delegates display power/modes, IME/focus policy,
 * activity launching, clipboard/IME, and multi-user queries to focused package collaborators.
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

    private final AndroidDisplayPowerHelper displayPowerHelper;
    private final AndroidImePolicyHelper imePolicyHelper;
    private final AndroidActivityLauncher activityLauncher;
    private final HostAudioRouteHelper audioRouteHelper;

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
                ? findMethodOrNull(iWindowManager.getClass(), "setDisplayImePolicy", int.class, int.class) : null;
        this.getDisplayImePolicyMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "getDisplayImePolicy", int.class) : null;
        this.setShouldShowImeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setShouldShowIme", int.class, boolean.class) : null;
        this.shouldShowImeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "shouldShowIme", int.class) : null;
        this.shouldShowSystemDecorsMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setShouldShowSystemDecors", int.class, boolean.class) : null;
        this.setIgnoreOrientationRequestMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setIgnoreOrientationRequest", int.class, boolean.class) : null;
        this.setRemoveContentModeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setRemoveContentMode", int.class, int.class) : null;
        this.setForcedDisplaySizeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setForcedDisplaySize", int.class, int.class, int.class) : null;
        this.clearForcedDisplaySizeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "clearForcedDisplaySize", int.class) : null;
        this.setForcedDisplayScalingModeMethod = (iWindowManager != null)
                ? findMethodOrNull(iWindowManager.getClass(), "setForcedDisplayScalingMode", int.class, int.class) : null;

        this.displayPowerHelper = new AndroidDisplayPowerHelper(this);
        this.imePolicyHelper = new AndroidImePolicyHelper(this);
        this.activityLauncher = new AndroidActivityLauncher(this);
        this.audioRouteHelper = new HostAudioRouteHelper();
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

    // ---- IME policy, focus & pointer speed delegates ----

    public int getDisplayImePolicy(int displayId) { return imePolicyHelper.getDisplayImePolicy(displayId); }
    public boolean setDisplayImePolicy(int displayId, int imePolicy) { return imePolicyHelper.setDisplayImePolicy(displayId, imePolicy); }
    public void configureVirtualDisplayWindowing(int displayId, int imePolicy) { imePolicyHelper.configureVirtualDisplayWindowing(displayId, imePolicy); }
    static int resolveRemoveContentModeDestroy() { return AndroidImePolicyHelper.resolveRemoveContentModeDestroy(); }
    public void focusDisplay(int displayId) { imePolicyHelper.focusDisplay(displayId); }
    public void restoreDefaultDisplayTopFocus() { imePolicyHelper.restoreDefaultDisplayTopFocus(); }
    public void ensurePointerIconVisible() { imePolicyHelper.ensurePointerIconVisible(); }
    public void focusDisplayTaskOnly(int displayId) { imePolicyHelper.focusDisplayTaskOnly(displayId); }
    public boolean setHardwarePointerSpeed(int speed) { return imePolicyHelper.setHardwarePointerSpeed(speed); }

    // ---- Display power & external display mode delegates ----

    public boolean isHostScreenOff() { return displayPowerHelper.isHostScreenOff(); }
    public boolean setPrimaryPhysicalDisplayPower(boolean screenOn) { return displayPowerHelper.setPrimaryPhysicalDisplayPower(screenOn); }
    public boolean setExternalDisplayMode(int displayId, int width, int height, float fps) {
        return setExternalDisplayMode(displayId, width, height, fps, BridgeProtocol.EXTERNAL_SCALING_SMART_AUTO);
    }
    public boolean setExternalDisplayMode(int displayId, int width, int height, float fps, int scalingModePref) {
        return displayPowerHelper.setExternalDisplayMode(displayId, width, height, fps, scalingModePref);
    }
    static boolean isSyntheticOrAnisotropyMode(Display.Mode mode) { return AndroidDisplayPowerHelper.isSyntheticOrAnisotropyMode(mode); }
    public boolean setExternalDisplayRefreshRate(int displayId, float fps) { return displayPowerHelper.setExternalDisplayRefreshRate(displayId, fps); }
    public boolean setForcedExternalDisplayScalingMode(int displayId, int mode) { return displayPowerHelper.setForcedExternalDisplayScalingMode(displayId, mode); }
    public boolean setForcedExternalDisplaySize(int displayId, int width, int height) { return displayPowerHelper.setForcedExternalDisplaySize(displayId, width, height); }
    public boolean clearForcedExternalDisplaySize(int displayId) { return displayPowerHelper.clearForcedExternalDisplaySize(displayId); }
    public void clearAllForcedExternalDisplaySizes() { displayPowerHelper.clearAllForcedExternalDisplaySizes(); }

    // ---- Host audio output route delegates ----

    public boolean setForcePhoneSpeaker(boolean enabled) { return audioRouteHelper.setForcePhoneSpeaker(enabled); }
    public boolean reapplyForcePhoneSpeakerIfEnabled() { return audioRouteHelper.reapplyIfEnabled(); }
    public boolean isForcePhoneSpeakerActive() { return audioRouteHelper.isForcePhoneSpeakerActive(); }
    public void restoreHostAudioRouteOnShutdown() { audioRouteHelper.restoreDefaultOnShutdown(); }

    // ---- Activity launch & provider delegates ----

    public boolean startActivityAsShell(Intent intent, Bundle options) {
        return startActivityAsShellForUser(intent, options, UserProfileAppPolicy.PRIMARY_USER_ID);
    }
    public boolean startActivityAsShellForUser(Intent intent, Bundle options, int userId) {
        return activityLauncher.startActivityAsShellForUser(intent, options, userId);
    }
    public boolean startActivityAsShellWithAmFallback(Intent intent, Bundle options, int displayId, boolean clearTask) {
        return startActivityAsShellWithAmFallback(intent, options, displayId, clearTask, UserProfileAppPolicy.PRIMARY_USER_ID);
    }
    public boolean startActivityAsShellWithAmFallback(Intent intent, Bundle options, int displayId, boolean clearTask, int userId) {
        return activityLauncher.startActivityAsShellWithAmFallback(intent, options, displayId, clearTask, userId);
    }
    public boolean launchExternalDesktopHost(int displayId, boolean includeExternalExtras, boolean isWireless) {
        return activityLauncher.launchExternalDesktopHost(displayId, includeExternalExtras, isWireless);
    }
    public boolean launchExternalDesktopOnDisplay(int displayId, boolean isWireless) {
        return activityLauncher.launchExternalDesktopOnDisplay(displayId, isWireless);
    }
    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        return activityLauncher.getRunningAppProcesses();
    }
    public static Bundle callContentProviderExternal(String authority, String method, String arg, Bundle extras) {
        return callContentProviderExternal(authority, 0, method, arg, extras);
    }
    public static Bundle callContentProviderExternal(String authority, int userId, String method, String arg, Bundle extras) {
        return AndroidActivityLauncher.callContentProviderExternal(authority, userId, method, arg, extras);
    }

    public static void setActionButton(MotionEvent ev, int actionButton) {
        if (ev == null || actionButton == 0) return;
        if (!sSetActionButtonChecked) {
            sSetActionButtonMethod = findMethodOrNull(MotionEvent.class, "setActionButton", int.class);
            sSetActionButtonChecked = true;
        }
        Method m = sSetActionButtonMethod;
        if (m == null) return;
        try { m.invoke(ev, actionButton); } catch (ReflectiveOperationException | RuntimeException ignored) {}
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

    public int[] getPrimaryDisplayMetrics() {
        int[] state = getPrimaryDisplayState();
        return new int[]{state[0], state[1], state[2]};
    }

    @SuppressWarnings("deprecation")
    public int[] getPrimaryDisplayState() {
        int w = 0, h = 0, dpi = 0, rotation = 0;
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
                    if (m != null) { w = m.getPhysicalWidth(); h = m.getPhysicalHeight(); }
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

    public String getDisplayUniqueId(int displayId) {
        if (displayId < 0 || displayManager == null) return null;
        try {
            Display disp = displayManager.getDisplay(displayId);
            if (disp == null) return null;
            Method getUid = findMethodOrNull(Display.class, "getUniqueId");
            if (getUid != null) {
                Object uid = getUid.invoke(disp);
                if (uid instanceof String && !((String) uid).isEmpty()) return (String) uid;
            }
            Class<?> infoCls = Class.forName("android.view.DisplayInfo");
            Object info = infoCls.getDeclaredConstructor().newInstance();
            Method getInfo = findMethodOrNull(Display.class, "getDisplayInfo", infoCls);
            if (getInfo != null) {
                getInfo.invoke(disp, info);
                Field fUid = infoCls.getField("uniqueId");
                Object uid = fUid.get(info);
                if (uid instanceof String && !((String) uid).isEmpty()) return (String) uid;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_GET_DISP_UID_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
        return null;
    }

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
                if (portObj instanceof Number) return ((Number) portObj).intValue() & 0xFF;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return -1;
    }

    // ---- Clipboard, IME & system input injector delegates ----

    public static boolean setPrimaryClip(ClipData clip) { return AndroidClipboardAndImeHelper.setPrimaryClip(clip); }
    public static ClipData getPrimaryClip() { return AndroidClipboardAndImeHelper.getPrimaryClip(); }
    public String getDefaultInputMethodId() { return AndroidClipboardAndImeHelper.getDefaultInputMethodId(context); }
    public List<String> listEnabledInputMethodIds() { return AndroidClipboardAndImeHelper.listEnabledInputMethodIds(context); }
    public boolean setDefaultInputMethodId(String imeId) { return AndroidClipboardAndImeHelper.setDefaultInputMethodId(context, imeId); }
    public int getShowImeWithHardKeyboard() { return AndroidClipboardAndImeHelper.getShowImeWithHardKeyboard(context); }
    public boolean setShowImeWithHardKeyboard(int value) { return AndroidClipboardAndImeHelper.setShowImeWithHardKeyboard(context, value); }
    public static ImmsDumpInspector.Snapshot inspectInputMethodService(int targetDisplayId) {
        return AndroidClipboardAndImeHelper.inspectInputMethodService(targetDisplayId);
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

    // ---- Multi-User / Cloned & Work Profile delegates ----

    public List<UserProfileAppPolicy.UserProfileInfo> querySecondaryUserProfiles() {
        return AndroidMultiUserHelper.querySecondaryUserProfiles();
    }
    public String resolveProfileTypeForUser(int userId) {
        return AndroidMultiUserHelper.resolveProfileTypeForUser(userId);
    }
    public List<UserProfileAppPolicy.SecondaryAppEntry> querySecondaryUserLaunchableApps() {
        return AndroidMultiUserHelper.querySecondaryUserLaunchableApps(context);
    }
    public String querySecondaryUserLaunchableAppsJson() {
        return UserProfileAppPolicy.encodeSecondaryAppsJson(querySecondaryUserLaunchableApps());
    }
    public List<ResolveInfo> queryIntentActivitiesForUser(Intent intent, int userId) {
        return AndroidMultiUserHelper.queryIntentActivitiesForUser(intent, userId);
    }
    public ComponentName resolveLaunchComponentForUser(String pkg, int userId) {
        return AndroidMultiUserHelper.resolveLaunchComponentForUser(context, pkg, userId);
    }
    public boolean canResolveActivityForUser(Intent intent, int userId) {
        return AndroidMultiUserHelper.canResolveActivityForUser(context, intent, userId);
    }
    public String resolveAppLabelForUser(String pkg, int userId) {
        return AndroidMultiUserHelper.resolveAppLabelForUser(context, pkg, userId);
    }
    public int getPackageUidForUser(String pkg, int userId) {
        return AndroidMultiUserHelper.getPackageUidForUser(context, pkg, userId);
    }

    static Object buildShellAttributionSource() throws ReflectiveOperationException {
        Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
        Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
        asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, SHELL_PACKAGE);
        return asBuilderCls.getMethod("build").invoke(builder);
    }

    private static void setStringFieldQuietly(Object target, String fieldName, String value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Method resolveStartActivityAsUserMethod(Object atm) {
        if (atm == null) return null;
        Method fallback11 = null;
        for (Method m : atm.getClass().getMethods()) {
            if (!"startActivityAsUser".equals(m.getName())) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 12 && pt[3] == Intent.class && pt[11] == int.class) return m;
            if (pt.length == 11 && pt[2] == Intent.class && pt[10] == int.class) fallback11 = m;
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
