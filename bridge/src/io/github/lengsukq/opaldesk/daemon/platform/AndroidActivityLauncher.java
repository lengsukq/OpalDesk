package io.github.lengsukq.opaldesk.daemon.platform;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Launches Activities as {@code com.android.shell} (including multi-user and {@code am start}
 * fallback), launches {@code ExternalDesktopActivity}, queries running processes, and invokes
 * external ContentProviders via {@code IActivityManager}.
 */
final class AndroidActivityLauncher {
    private final AndroidServices services;

    AndroidActivityLauncher(AndroidServices services) {
        this.services = services;
    }

    boolean startActivityAsShellForUser(Intent intent, Bundle options, int userId) {
        int safeUserId = Math.max(0, userId);
        if (intent == null || services.iActivityTaskManager == null) return false;
        if (safeUserId > 0) {
            if (services.startActivityAsUserMethod == null) return false;
            try {
                Object rc;
                int paramCount = services.startActivityAsUserMethod.getParameterCount();
                if (paramCount == 12) {
                    rc = services.startActivityAsUserMethod.invoke(
                            services.iActivityTaskManager, null, AndroidServices.SHELL_PACKAGE, null, intent,
                            null, null, null, 0, 0, null, options, safeUserId);
                } else if (paramCount == 11) {
                    rc = services.startActivityAsUserMethod.invoke(
                            services.iActivityTaskManager, null, AndroidServices.SHELL_PACKAGE, intent,
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
                        + " intent=" + intent + ": " + AndroidServices.unwrapMessage(e));
                return false;
            }
        }
        if (services.startActivityMethod == null) return false;
        try {
            Object rc = services.startActivityMethod.invoke(
                    services.iActivityTaskManager, null, AndroidServices.SHELL_PACKAGE, null, intent,
                    null, null, null, 0, 0, null, options);
            if (rc instanceof Integer && (Integer) rc < 0) {
                System.err.println("OPALDESK_START_ACTIVITY_RC intent=" + intent + " rc=" + rc);
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_START_ACTIVITY_ERR intent=" + intent + ": " + AndroidServices.unwrapMessage(e));
            return false;
        }
    }

    boolean startActivityAsShellWithAmFallback(
            Intent intent, Bundle options, int displayId, boolean clearTask, int userId) {
        int safeUserId = Math.max(0, userId);
        if (startActivityAsShellForUser(intent, options, safeUserId)) {
            return true;
        }
        if (intent == null || displayId <= 0) return false;
        ComponentName cn = intent.getComponent();
        if (cn == null) return false;
        int flags = intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION;
        if (clearTask) flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK;
        Map<String, Object> extraMap = new LinkedHashMap<>();
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
                cn.flattenToShortString(), displayId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN,
                flags, extraMap, safeUserId);
        ShellCommand.Result res = ShellCommand.runWithTimeout(cmd, ShellCommand.DEFAULT_TIMEOUT_MS);
        if (!TaskLaunchPolicy.isAmStartResultSuccessful(res.exitCode, res.timedOut, res.output)) {
            System.err.println("OPALDESK_AM_START_FALLBACK_ERR user=" + safeUserId
                    + " displayId=" + displayId + " exit=" + res.exitCode + " timedOut=" + res.timedOut
                    + ": " + TaskLaunchPolicy.summarizeAmStartOutput(res.output));
            return false;
        }
        System.out.println("OPALDESK_AM_START_FALLBACK_OK user=" + safeUserId
                + " displayId=" + displayId + " comp=" + cn.flattenToShortString());
        return true;
    }

    boolean launchExternalDesktopHost(int displayId, boolean includeExternalExtras, boolean isWireless) {
        if (displayId <= Display.DEFAULT_DISPLAY || services.displayManager == null) return false;
        Display disp = null;
        try { disp = services.displayManager.getDisplay(displayId); } catch (RuntimeException ignored) {}
        if (disp == null || !disp.isValid()) {
            System.err.println("OPALDESK_EXT_DESKTOP_LAUNCH_SKIP displayId=" + displayId + " invalid_or_missing");
            return false;
        }
        String cls = BridgeBuildConfig.HELPER_PACKAGE + ".ExternalDesktopActivity";
        boolean removedStale = new TaskApiAdapter(services.iActivityTaskManager)
                .removeStaleComponentTasksNotOnDisplay(BridgeBuildConfig.HELPER_PACKAGE, cls, displayId);
        if (removedStale) android.os.SystemClock.sleep(60L);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(BridgeBuildConfig.HELPER_PACKAGE, cls));
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION;
        if (includeExternalExtras) flags |= Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
        if (removedStale) flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK;
        intent.addFlags(flags);
        intent.putExtra("targetDisplayId", displayId);
        if (includeExternalExtras) {
            intent.putExtra("preferPresentation", false);
            intent.putExtra("isWirelessDisplay", isWireless);
            intent.putExtra("shellLaunched", true);
        }
        Bundle opts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        boolean ok = startActivityAsShellWithAmFallback(
                intent, opts, displayId, removedStale, UserProfileAppPolicy.PRIMARY_USER_ID);
        if (ok) services.focusDisplay(displayId);
        return ok;
    }

    boolean launchExternalDesktopOnDisplay(int displayId, boolean isWireless) {
        if (displayId <= Display.DEFAULT_DISPLAY || services.displayManager == null) return false;
        Display disp = null;
        try { disp = services.displayManager.getDisplay(displayId); } catch (RuntimeException ignored) {}
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
    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        Object am = (services.iActivityManager != null)
                ? services.iActivityManager
                : AndroidServices.getServiceInterface("activity", "android.app.IActivityManager$Stub");
        if (am == null) return null;
        try {
            return (List<ActivityManager.RunningAppProcessInfo>)
                    am.getClass().getMethod("getRunningAppProcesses").invoke(am);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static Bundle callContentProviderExternal(
            String authority, int userId, String method, String arg, Bundle extras) {
        IBinder tok = new Binder();
        Object am = AndroidServices.getServiceInterface("activity", "android.app.IActivityManager$Stub");
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
            Method callAttr5 = null, callStr5 = null, callStr6 = null;
            for (Method m : prov.getClass().getMethods()) {
                if (!"call".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 5 && "android.content.AttributionSource".equals(pts[0].getName())) callAttr5 = m;
                else if (pts.length == 5 && pts[0] == String.class) callStr5 = m;
                else if (pts.length == 6 && pts[0] == String.class) callStr6 = m;
            }
            if (callAttr5 != null) {
                return (Bundle) callAttr5.invoke(prov, AndroidServices.buildShellAttributionSource(), authority, method, arg, extras);
            } else if (callStr5 != null) {
                return (Bundle) callStr5.invoke(prov, AndroidServices.SHELL_PACKAGE, authority, method, arg, extras);
            } else if (callStr6 != null) {
                return (Bundle) callStr6.invoke(prov, AndroidServices.SHELL_PACKAGE, null, authority, method, arg, extras);
            }
            System.err.println("OPALDESK_PROVIDER_EXT_WARN: no matching IContentProvider.call overload");
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_PROVIDER_EXT_WARN: " + AndroidServices.unwrapMessage(e));
        } finally {
            removeContentProviderExternalQuietly(am, authority, tok, targetUserId);
        }
        return null;
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
}
