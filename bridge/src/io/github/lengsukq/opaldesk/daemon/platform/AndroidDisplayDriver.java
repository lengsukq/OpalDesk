package io.github.lengsukq.opaldesk.daemon.platform;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.VirtualDisplayPool;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.util.List;
import java.util.Set;

/**
 * Concrete Android implementation of {@link VirtualDisplayPool.DisplayDriver} and
 * {@link CompositorEngine.AppRuntimeBridge}.
 *
 * <p>Allocates isolated {@link VirtualDisplay}s per window and runs every app in pure
 * {@code WINDOWING_MODE_FULLSCREEN (1)} with zero OEM multi-window / freeform hooks.
 */
public final class AndroidDisplayDriver
        implements VirtualDisplayPool.DisplayDriver, CompositorEngine.AppRuntimeBridge {

    /** Optional provider (e.g. AOSP VirtualDeviceManager VirtualCamera host) for VirtualDisplay creation. */
    public interface VirtualDisplayProvider {
        VirtualDisplay createVirtualDisplay(
                String name, int width, int height, int dpi, int flags, float refreshRate, Surface surface);
    }

    private final AndroidServices services;
    private final TaskApiAdapter taskApi;
    private final VirtualDisplayStatusBarInset statusBarInset;
    private volatile PerDisplayInputRouter inputRouter;
    private volatile Runnable allSurfacesDetachedListener;
    private volatile VirtualDisplayProvider customVirtualDisplayProvider;

    public AndroidDisplayDriver(AndroidServices services, TaskApiAdapter taskApi) {
        this.services = services;
        this.taskApi = taskApi;
        this.statusBarInset = new VirtualDisplayStatusBarInset(services.context, null);
    }

    public void setInputRouter(PerDisplayInputRouter inputRouter) {
        this.inputRouter = inputRouter;
    }

    public void setAllSurfacesDetachedListener(Runnable listener) {
        this.allSurfacesDetachedListener = listener;
    }

    public void setCustomVirtualDisplayProvider(VirtualDisplayProvider provider) {
        this.customVirtualDisplayProvider = provider;
    }

    public void shutdown() {
        statusBarInset.detachAll();
    }

    // ---- VirtualDisplayPool.DisplayDriver ----

    @Override
    public CreatedDisplay createVirtualDisplay(
            String name, int width, int height, int dpi, float fps, Object surfaceObj, boolean allowSecure) {
        if (!(surfaceObj instanceof Surface) || services.displayManager == null) return null;
        Surface surface = (Surface) surfaceObj;
        if (!surface.isValid()) return null;

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            } catch (RuntimeException ignored) {
            }
        }

        int secBit = allowSecure ? BridgeProtocol.VD_FLAG_SECURE : 0;
        int dcor = BridgeProtocol.VD_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        // Prefer VD_FLAGS_TRUSTED_FOCUS (0x04449: includes OWN_FOCUS, omits STEAL_TOP_FOCUS_DISABLED)
        // so both Display 0 (host IME bridge / Dock) and each OpalWin-* maintain independent
        // mCurrentFocus simultaneously — opening the host soft keyboard never strips cursor focus
        // from the managed app, while tapping inside the app still registers IMMS client requests.
        int[] candidates = allowSecure
                ? new int[]{
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor | secBit,
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor,
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | secBit,
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS,
                        BridgeProtocol.VD_FLAGS_TRUSTED_IME | dcor | secBit,
                        BridgeProtocol.VD_FLAGS_TRUSTED_IME | dcor,
                        BridgeProtocol.VD_FLAGS_BASE_PUBLIC | dcor | secBit,
                        BridgeProtocol.VD_FLAGS_BASE_PUBLIC | secBit,
                        BridgeProtocol.VD_FLAGS_BASE_PUBLIC
                }
                : new int[]{
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor,
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS,
                        BridgeProtocol.VD_FLAGS_TRUSTED_IME | dcor,
                        BridgeProtocol.VD_FLAGS_TRUSTED_IME,
                        BridgeProtocol.VD_FLAGS_BASE_PUBLIC | dcor,
                        BridgeProtocol.VD_FLAGS_BASE_PUBLIC
                };

        float vdRefreshRate = (fps > 60.5f) ? fps : 0f;
        int[] chosenFlags = new int[1];
        VirtualDisplay vd = null;
        VirtualDisplayProvider customProvider = this.customVirtualDisplayProvider;
        if (customProvider != null) {
            for (int candFlags : candidates) {
                VirtualDisplay customVd = customProvider.createVirtualDisplay(
                        name, width, height, dpi, candFlags, vdRefreshRate, surface);
                if (customVd != null && customVd.getDisplay() != null) {
                    vd = customVd;
                    chosenFlags[0] = candFlags;
                    break;
                }
            }
        }
        if (vd == null) {
            vd = VirtualDisplayFactory.createWithFlagFallback(
                    services.displayManager, name, width, height, dpi, candidates, vdRefreshRate, surface, chosenFlags);
        }
        if (vd == null || vd.getDisplay() == null) {
            System.err.println("OPALDESK_VD_CREATE_FAIL name=" + name
                    + " size=" + width + "x" + height + "@" + dpi + "dpi allowSecure=" + allowSecure);
            return null;
        }

        Display disp = vd.getDisplay();
        int displayId = disp.getDisplayId();
        String uniqueId = VirtualDisplayFactory.getUniqueId(disp);
        System.out.println("OPALDESK_VD_CREATED name=" + name
                + " displayId=" + displayId
                + " size=" + width + "x" + height + "@" + dpi + "dpi"
                + " flags=0x" + Integer.toHexString(chosenFlags[0])
                + " uid=" + (uniqueId != null ? uniqueId : ""));
        // Combined with VD_FLAGS_TRUSTED_IME (no OWN_FOCUS / STEAL_TOP_FOCUS_DISABLED),
        // DISPLAY_IME_POLICY_FALLBACK_DISPLAY routes the app's real InputConnection directly to
        // Display 0's IME (full width, works even with FLAG_SECURE password keyboards without
        // ever blanking the non-secure VirtualDisplay surface!).
        services.configureVirtualDisplayWindowing(displayId, BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY);
        statusBarInset.attach(disp, 1);
        return new CreatedDisplay(vd, displayId, uniqueId);
    }

    @Override
    public void resizeVirtualDisplay(Object handle, int width, int height, int dpi) {
        if (!(handle instanceof VirtualDisplay)) return;
        try {
            ((VirtualDisplay) handle).resize(width, height, dpi);
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_VD_RESIZE_WARN: " + e.getMessage());
        }
    }

    @Override
    public void setVirtualDisplaySurface(Object handle, Object surfaceObj) {
        if (!(handle instanceof VirtualDisplay)) return;
        Surface surf = (surfaceObj instanceof Surface) ? (Surface) surfaceObj : null;
        try {
            ((VirtualDisplay) handle).setSurface(surf);
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_VD_SET_SURF_WARN: " + e.getMessage());
        }
    }

    @Override
    public void setSurfaceFrameRate(Object surfaceObj, float fps) {
        if (Build.VERSION.SDK_INT < 30 || !(surfaceObj instanceof Surface)) return;
        Surface surf = (Surface) surfaceObj;
        if (!surf.isValid()) return;
        try {
            surf.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void releaseVirtualDisplay(Object handle, int displayId) {
        statusBarInset.detach(displayId);
        if (!(handle instanceof VirtualDisplay)) return;
        VirtualDisplay vd = (VirtualDisplay) handle;
        try {
            vd.setSurface(null);
        } catch (RuntimeException ignored) {
        }
        try {
            vd.release();
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_VD_RELEASE_WARN id=" + displayId + ": " + e.getMessage());
        }
    }

    @Override
    public byte[] captureTaskSnapshotBytes(int taskId) {
        return taskApi.captureTaskSnapshotJpeg(taskId);
    }

    // ---- CompositorEngine.AppRuntimeBridge ----

    @Override
    public String resolveAppLabel(String pkg) {
        if (services.context == null || pkg == null || pkg.isEmpty()) return pkg != null ? pkg : "";
        try {
            PackageManager pm = services.context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return pkg;
    }

    @Override
    public int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId) {
        if (displayId <= 0 || PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        Bundle launchOpts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();

        // Fast path: task is already alive on this VirtualDisplay (e.g. waking from minimize).
        // NEVER call startActivityFromRecents() here: on ColorOS / OEM ROMs it invokes
        // moveTaskToFront on Display 0 and hijacks the host phone screen over DesktopActivity!
        int existingOnTarget = taskApi.findTopTaskIdOnDisplay(displayId, pkg);
        if (existingOnTarget > 0) {
            if (taskApi.findTopVisibleTaskIdOnDisplay(displayId, pkg) == existingOnTarget) {
                services.focusDisplay(displayId);
                System.out.println("OPALDESK_LAUNCH_FOCUS_OK pkg=" + pkg
                        + " displayId=" + displayId + " tid=" + existingOnTarget);
                return existingOnTarget;
            }
        }

        ResolvedLaunchIntent resolvedLaunch = resolveLaunchIntentInfo(pkg, cls);
        if (resolvedLaunch == null) {
            System.err.println("OPALDESK_LAUNCH_NO_INTENT pkg=" + pkg + " cls=" + cls + " displayId=" + displayId);
            return existingOnTarget;
        }
        Intent launchIntent = resolvedLaunch.intent;

        // When launching an explicit non-launcher sub-activity (e.g. Wi-Fi / Bluetooth settings),
        // avoid pulling an unrelated background task for the same package (which often has a
        // different taskAffinity, e.g. com.android.settings.root) onto this VirtualDisplay.
        int candidateTid = (existingTaskId > 0)
                ? existingTaskId
                : (resolvedLaunch.isExplicitSubActivity ? -1 : taskApi.findRunningOrRecentTaskForPackage(pkg));
        if (candidateTid > 0 && candidateTid != existingOnTarget) {
            taskApi.moveRootTaskToDisplay(candidateTid, displayId);
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (!startActivityWithAmFallback(launchIntent, launchOpts, displayId, false)) {
            System.err.println("OPALDESK_LAUNCH_START_FAIL pkg=" + pkg + " displayId=" + displayId);
            return existingOnTarget;
        }
        services.focusDisplay(displayId);
        SystemClock.sleep(60L);
        int topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg);
        // On ColorOS / OEM ROMs where a background Display-0 task refuses to reparent via moveRootTaskToDisplay,
        // remove the stale Display-0 task and cold-launch directly into the target VirtualDisplay.
        if (topTid <= 0 && candidateTid > 0 && candidateTid != existingOnTarget) {
            taskApi.removeTask(candidateTid);
            SystemClock.sleep(80L);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivityWithAmFallback(launchIntent, launchOpts, displayId, true);
            services.focusDisplay(displayId);
            SystemClock.sleep(80L);
            topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg);
        }
        int resolvedTid = (topTid > 0) ? topTid
                : (existingOnTarget > 0 ? existingOnTarget : taskApi.findRunningOrRecentTaskForPackage(pkg));
        System.out.println("OPALDESK_LAUNCH_START_OK pkg=" + pkg
                + " displayId=" + displayId + " tid=" + resolvedTid);
        return resolvedTid;
    }

    @Override
    public int relaunchFreshFullscreenApp(String pkg, String cls, int displayId) {
        if (displayId <= 0 || PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        Bundle launchOpts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        Intent launchIntent = resolveSafeLaunchIntent(pkg, cls);
        if (launchIntent == null) {
            System.err.println("OPALDESK_RELOAD_NO_INTENT pkg=" + pkg + " cls=" + cls + " displayId=" + displayId);
            return -1;
        }
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (!startActivityWithAmFallback(launchIntent, launchOpts, displayId, true)) {
            System.err.println("OPALDESK_RELOAD_START_FAIL pkg=" + pkg + " displayId=" + displayId);
            return -1;
        }
        services.focusDisplay(displayId);
        SystemClock.sleep(100L);
        int topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg);
        int resolvedTid = (topTid > 0) ? topTid : taskApi.findRunningOrRecentTaskForPackage(pkg);
        System.out.println("OPALDESK_RELOAD_FRESH_OK pkg=" + pkg
                + " displayId=" + displayId + " tid=" + resolvedTid);
        return resolvedTid;
    }

    @Override
    public int launchExplicitActivityOnDisplay(String pkg, String cls, int displayId) {
        if (displayId <= 0 || PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        Bundle launchOpts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        Intent launchIntent = resolveSafeLaunchIntent(pkg, cls);
        if (launchIntent == null) {
            System.err.println("OPALDESK_LAUNCH_EXPLICIT_NO_INTENT pkg=" + pkg + " cls=" + cls + " displayId=" + displayId);
            return taskApi.findTopTaskIdOnDisplay(displayId, pkg);
        }
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (!startActivityWithAmFallback(launchIntent, launchOpts, displayId, false)) {
            System.err.println("OPALDESK_LAUNCH_EXPLICIT_FAIL pkg=" + pkg + " cls=" + cls + " displayId=" + displayId);
            return taskApi.findTopTaskIdOnDisplay(displayId, pkg);
        }
        services.focusDisplay(displayId);
        SystemClock.sleep(60L);
        int topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg);
        int resolvedTid = (topTid > 0) ? topTid : taskApi.findRunningOrRecentTaskForPackage(pkg);
        System.out.println("OPALDESK_LAUNCH_EXPLICIT_OK pkg=" + pkg
                + " cls=" + cls + " displayId=" + displayId + " tid=" + resolvedTid);
        return resolvedTid;
    }

    /**
     * Launches an Activity via {@code IActivityTaskManager.startActivity} and falls back to
     * {@code am start --display <id> --windowingMode 1} if a future Android/OEM AIDL signature
     * change breaks hidden-API reflection.
     */
    private boolean startActivityWithAmFallback(
            Intent launchIntent, Bundle launchOpts, int displayId, boolean clearTask) {
        if (services.startActivityAsShell(launchIntent, launchOpts)) {
            return true;
        }
        ComponentName cn = launchIntent.getComponent();
        if (cn == null) {
            return false;
        }
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | (clearTask ? Intent.FLAG_ACTIVITY_CLEAR_TASK : 0);
        String out = ShellCommand.runOutput(
                "am", "start",
                "--display", String.valueOf(displayId),
                "--windowingMode", String.valueOf(BridgeProtocol.WINDOWING_MODE_FULLSCREEN),
                "-f", "0x" + Integer.toHexString(flags),
                "-n", cn.flattenToShortString());
        if (out != null && out.contains("Error")) {
            System.err.println("OPALDESK_AM_START_FALLBACK_ERR displayId=" + displayId + ": " + out.trim());
            return false;
        }
        System.out.println("OPALDESK_AM_START_FALLBACK_OK displayId=" + displayId + " comp=" + cn.flattenToShortString());
        return true;
    }

    @Override
    public int findTopTaskIdOnDisplay(int displayId, String expectedPkg) {
        return taskApi.findTopTaskIdOnDisplay(displayId, expectedPkg);
    }

    @Override
    public List<Integer> findAllTaskIdsOnDisplay(int displayId) {
        return taskApi.findAllTaskIdsOnDisplay(displayId);
    }

    @Override
    public int findTopVisibleTaskIdOnDisplay(int displayId, String expectedPkg) {
        return taskApi.findTopVisibleTaskIdOnDisplay(displayId, expectedPkg);
    }

    @Override
    public boolean isTaskRunningWithActivities(int taskId, int displayId) {
        return taskApi.isTaskRunningWithActivities(taskId, displayId);
    }

    @Override
    public Set<Integer> collectAliveTaskIds() {
        return taskApi.collectAliveTaskIds();
    }

    @Override
    public void removeTask(int taskId) {
        taskApi.removeTask(taskId);
    }

    @Override
    public void forceStopPackage(String pkg) {
        if (PackagePolicy.isBlockedWindowPkg(pkg)) return;
        ShellCommand.runOutput("am", "force-stop", pkg);
        SystemClock.sleep(150L);
    }

    @Override
    public void focusDisplay(int displayId) {
        if (displayId > 0) {
            services.focusDisplay(displayId);
        }
    }

    @Override
    public void onAllClientSurfacesDetached() {
        Runnable listener = allSurfacesDetachedListener;
        if (listener != null) {
            listener.run();
        }
    }

    @Override
    public void onDisplayReleased(int displayId) {
        PerDisplayInputRouter router = inputRouter;
        if (router != null && displayId > 0) {
            router.releaseDisplayResources(displayId);
        }
    }

    @Override
    public void releaseSurfaceHandle(Object surface) {
        if (surface instanceof Surface) {
            try {
                ((Surface) surface).release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public int getHostDisplayDpi() {
        int[] metrics = services.getPrimaryDisplayMetrics();
        return (metrics != null && metrics.length >= 3 && metrics[2] > 0) ? metrics[2] : 0;
    }

    private static final class ResolvedLaunchIntent {
        final Intent intent;
        final boolean isExplicitSubActivity;

        ResolvedLaunchIntent(Intent intent, boolean isExplicitSubActivity) {
            this.intent = intent;
            this.isExplicitSubActivity = isExplicitSubActivity;
        }
    }

    private Intent resolveSafeLaunchIntent(String pkg, String cls) {
        ResolvedLaunchIntent info = resolveLaunchIntentInfo(pkg, cls);
        return (info != null) ? info.intent : null;
    }

    private ResolvedLaunchIntent resolveLaunchIntentInfo(String pkg, String cls) {
        if (services.context == null || !PackagePolicy.isValidPackageName(pkg)) return null;
        PackageManager pm = services.context.getPackageManager();
        Intent launcher = pm.getLaunchIntentForPackage(pkg);
        if (cls != null && PackagePolicy.isValidClassName(cls)) {
            ComponentName launcherComp = (launcher != null) ? launcher.getComponent() : null;
            boolean isMainLauncher = launcherComp != null && cls.equals(launcherComp.getClassName());
            Intent explicit = isMainLauncher
                    ? new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(pkg, cls))
                    : new Intent(Intent.ACTION_MAIN)
                            .setComponent(new ComponentName(pkg, cls));
            if (pm.resolveActivity(explicit, 0) != null) {
                return new ResolvedLaunchIntent(explicit, !isMainLauncher);
            }
        }
        return (launcher != null) ? new ResolvedLaunchIntent(launcher, false) : null;
    }
}
