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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<Integer, Boolean> trustedByDisplayId = new ConcurrentHashMap<>();
    private volatile int desiredWindowImePolicy = BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
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

    /**
     * Dynamically updates the {@code DisplayImePolicy} ({@code LOCAL}, {@code FALLBACK_DISPLAY},
     * or {@code HIDE}) across all live trusted {@code OpalWin-*} VirtualDisplays whenever a
     * physical keyboard connects/disconnects, remote casting toggles, or the user updates DeX IME prefs.
     */
    public void updateWindowImePolicy(int desiredPolicy) {
        int clamped = normalizeImePolicy(desiredPolicy);
        this.desiredWindowImePolicy = clamped;
        for (Map.Entry<Integer, Boolean> entry : trustedByDisplayId.entrySet()) {
            int dispId = entry.getKey();
            boolean trusted = Boolean.TRUE.equals(entry.getValue());
            int effective = trusted ? clamped : BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
            boolean ok = services.setDisplayImePolicy(dispId, effective);
            System.out.println("OPALDESK_VD_IME_POLICY displayId=" + dispId
                    + " policy=" + imePolicyName(effective)
                    + " trusted=" + trusted
                    + " ok=" + ok);
        }
    }

    public int getDesiredWindowImePolicy() {
        return desiredWindowImePolicy;
    }

    private static int normalizeImePolicy(int policy) {
        if (policy == BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
                || policy == BridgeProtocol.DISPLAY_IME_POLICY_HIDE) {
            return policy;
        }
        return BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
    }

    private static String imePolicyName(int policy) {
        if (policy == BridgeProtocol.DISPLAY_IME_POLICY_LOCAL) return "LOCAL";
        if (policy == BridgeProtocol.DISPLAY_IME_POLICY_HIDE) return "HIDE";
        return "FALLBACK";
    }

    public void shutdown() {
        trustedByDisplayId.clear();
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
        boolean isTrusted = (chosenFlags[0] & BridgeProtocol.VD_FLAG_TRUSTED) != 0;
        int effectiveImePolicy = isTrusted
                ? desiredWindowImePolicy
                : BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
        trustedByDisplayId.put(displayId, isTrusted);
        System.out.println("OPALDESK_VD_CREATED name=" + name
                + " displayId=" + displayId
                + " size=" + width + "x" + height + "@" + dpi + "dpi"
                + " flags=0x" + Integer.toHexString(chosenFlags[0])
                + " imePolicy=" + imePolicyName(effectiveImePolicy)
                + " uid=" + (uniqueId != null ? uniqueId : ""));
        // Use DISPLAY_IME_POLICY_LOCAL when a physical keyboard is connected (so native CJK candidate
        // bars render directly inside the window on external monitors/local DeX instead of hiding on
        // Display 0), and DISPLAY_IME_POLICY_FALLBACK_DISPLAY otherwise (so phone trackpad / touch
        // taps summon the full-width Display 0 soft keyboard cleanly).
        services.configureVirtualDisplayWindowing(displayId, effectiveImePolicy);
        statusBarInset.attach(disp, 1);
        return new CreatedDisplay(vd, displayId, uniqueId);
    }

    @Override
    public void resizeVirtualDisplay(Object handle, int width, int height, int dpi) {
        if (!(handle instanceof VirtualDisplay)) return;
        VirtualDisplay vd = (VirtualDisplay) handle;
        Display disp = vd.getDisplay();
        Surface savedSurface = vd.getSurface();
        int displayId = (disp != null) ? disp.getDisplayId() : -1;
        try {
            VirtualDisplayResizeCoordinator.ResizeOutcome outcome =
                    VirtualDisplayResizeCoordinator.performResize(
                            Build.VERSION.SDK_INT,
                            width,
                            height,
                            dpi,
                            new VirtualDisplayResizeCoordinator.ResizeAdapter() {
                                @Override
                                public boolean hasValidAttachedSurface() {
                                    return savedSurface != null && savedSurface.isValid();
                                }

                                @Override
                                public boolean matchesTargetSpec(int targetW, int targetH, int targetDpi) {
                                    return displayMatchesSpec(disp, targetW, targetH, targetDpi);
                                }

                                @Override
                                public void detachSurface() {
                                    vd.setSurface(null);
                                }

                                @Override
                                public boolean isDisplayStateOff() {
                                    return disp != null && disp.getState() == Display.STATE_OFF;
                                }

                                @Override
                                public void applyResize(int targetW, int targetH, int targetDpi) {
                                    vd.resize(targetW, targetH, targetDpi);
                                }

                                @Override
                                public void reattachSurface() {
                                    if (savedSurface != null && savedSurface.isValid()) {
                                        vd.setSurface(savedSurface);
                                    }
                                }

                                @Override
                                public void sleepMs(long ms) {
                                    if (ms > 0L) {
                                        SystemClock.sleep(ms);
                                    }
                                }

                                @Override
                                public long uptimeMs() {
                                    return SystemClock.uptimeMillis();
                                }
                            });
            if (outcome.usedOffSurfacePath) {
                System.out.println("OPALDESK_VD_RESIZE_OFF_SURFACE displayId=" + displayId
                        + " size=" + width + "x" + height + "@" + dpi + "dpi"
                        + " off=" + outcome.observedStateOff + "(" + outcome.stateOffElapsedMs + "ms)"
                        + " spec=" + outcome.observedTargetSpec + "(" + outcome.resizeAppliedElapsedMs + "ms)");
            }
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_VD_RESIZE_WARN: " + e.getMessage());
        }
    }

    private static boolean displayMatchesSpec(Display disp, int width, int height, int dpi) {
        if (disp == null) return false;
        try {
            Display.Mode mode = disp.getMode();
            if (mode == null
                    || mode.getPhysicalWidth() != width
                    || mode.getPhysicalHeight() != height) {
                return false;
            }
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            disp.getRealMetrics(dm);
            return dm.densityDpi == dpi;
        } catch (RuntimeException ignored) {
            return false;
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
            int compat = (Build.VERSION.SDK_INT >= 31 && fps > 60.5f)
                    ? Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                    : Surface.FRAME_RATE_COMPATIBILITY_DEFAULT;
            surf.setFrameRate(fps, compat);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void releaseVirtualDisplay(Object handle, int displayId) {
        trustedByDisplayId.remove(displayId);
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
        return resolveAppLabel(pkg, UserProfileAppPolicy.PRIMARY_USER_ID);
    }

    @Override
    public String resolveAppLabel(String pkg, int userId) {
        return services.resolveAppLabelForUser(pkg, userId);
    }

    @Override
    public String resolveProfileType(int userId) {
        return services.resolveProfileTypeForUser(userId);
    }

    @Override
    public int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId) {
        return launchOrMoveFullscreenApp(pkg, cls, UserProfileAppPolicy.PRIMARY_USER_ID, displayId, existingTaskId);
    }

    @Override
    public int launchOrMoveFullscreenApp(String pkg, String cls, int userId, int displayId, int existingTaskId) {
        if (displayId <= 0 || PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        int safeUserId = Math.max(0, userId);
        Bundle launchOpts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();

        // Fast path: task is already alive on this VirtualDisplay (e.g. waking from minimize).
        // NEVER call startActivityFromRecents() here: on ColorOS / OEM ROMs it invokes
        // moveTaskToFront on Display 0 and hijacks the host phone screen over DesktopActivity!
        int existingOnTarget = taskApi.findTopTaskIdOnDisplay(displayId, pkg, safeUserId);
        if (existingOnTarget > 0) {
            if (taskApi.findTopVisibleTaskIdOnDisplay(displayId, pkg, safeUserId) == existingOnTarget) {
                services.focusDisplay(displayId);
                System.out.println("OPALDESK_LAUNCH_FOCUS_OK pkg=" + pkg + " user=" + safeUserId
                        + " displayId=" + displayId + " tid=" + existingOnTarget);
                return existingOnTarget;
            }
        }

        ResolvedLaunchIntent resolvedLaunch = resolveLaunchIntentInfo(pkg, cls, safeUserId);
        if (resolvedLaunch == null) {
            System.err.println("OPALDESK_LAUNCH_NO_INTENT pkg=" + pkg + " user=" + safeUserId
                    + " cls=" + cls + " displayId=" + displayId);
            return existingOnTarget;
        }
        Intent launchIntent = resolvedLaunch.intent;

        // When launching an explicit non-launcher sub-activity (e.g. Wi-Fi / Bluetooth settings),
        // avoid pulling an unrelated background task for the same package (which often has a
        // different taskAffinity, e.g. com.android.settings.root) onto this VirtualDisplay.
        TaskApiAdapter.TaskCandidate candidate = (existingTaskId > 0 || resolvedLaunch.isExplicitSubActivity)
                ? null
                : taskApi.findTaskCandidateForPackage(pkg, safeUserId);
        int candidateTid = (existingTaskId > 0)
                ? existingTaskId
                : (candidate != null ? candidate.taskId : -1);
        int candidateDisplayId = (candidate != null) ? candidate.displayId : -1;
        String preCleanReason = (candidate != null) ? candidate.cleanLaunchReason : null;
        boolean cleanedBeforeLaunch = false;
        if (TaskLaunchPolicy.shouldCleanLaunchBeforeReparent(
                existingTaskId,
                resolvedLaunch.isExplicitSubActivity,
                candidateTid,
                existingOnTarget,
                candidateDisplayId,
                preCleanReason)) {
            // Tasks running in ActivityEmbedding or letterbox/size-compat mode on Display 0 can
            // retain Display-0 input-region / letterbox detector state if reparented directly onto
            // a VirtualDisplay, causing single taps outside the stale Display-0 strip to be
            // rewritten to ACTION_CANCEL. Remove the stale Display-0 task and cold-launch cleanly.
            System.out.println("OPALDESK_LAUNCH_CLEAN_EMBEDDED_TASK pkg=" + pkg + " user=" + safeUserId
                    + " oldTid=" + candidateTid + " srcDisplay=" + candidateDisplayId
                    + " dstDisplay=" + displayId + " reason=" + preCleanReason);
            taskApi.removeTask(candidateTid);
            SystemClock.sleep(80L);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            cleanedBeforeLaunch = true;
        } else if (candidateTid > 0 && candidateTid != existingOnTarget) {
            taskApi.moveRootTaskToDisplay(candidateTid, displayId);
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (!startActivityWithAmFallback(launchIntent, launchOpts, displayId, cleanedBeforeLaunch, safeUserId)) {
            System.err.println("OPALDESK_LAUNCH_START_FAIL pkg=" + pkg + " user=" + safeUserId
                    + " displayId=" + displayId);
            return existingOnTarget;
        }
        services.focusDisplay(displayId);
        SystemClock.sleep(60L);
        int topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg, safeUserId);
        // On ColorOS / OEM ROMs where a background Display-0 task refuses to reparent via moveRootTaskToDisplay,
        // remove the stale Display-0 task and cold-launch directly into the target VirtualDisplay.
        if (topTid <= 0 && candidateTid > 0 && candidateTid != existingOnTarget && !cleanedBeforeLaunch) {
            taskApi.removeTask(candidateTid);
            SystemClock.sleep(80L);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivityWithAmFallback(launchIntent, launchOpts, displayId, true, safeUserId);
            services.focusDisplay(displayId);
            SystemClock.sleep(80L);
            topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg, safeUserId);
        } else if (topTid > 0 && !cleanedBeforeLaunch && existingTaskId <= 0 && candidateTid > 0 && candidateDisplayId <= 0) {
            TaskApiAdapter.TaskCandidate landed = taskApi.inspectTaskById(topTid);
            String landedReason = (landed != null) ? landed.cleanLaunchReason : null;
            if (TaskLaunchPolicy.shouldCleanLaunchAfterRecycle(
                    cleanedBeforeLaunch, existingTaskId, candidateTid, candidateDisplayId, topTid, landedReason)) {
                System.out.println("OPALDESK_LAUNCH_CLEAN_RECYCLED_TASK pkg=" + pkg + " user=" + safeUserId
                        + " oldTid=" + topTid + " dstDisplay=" + displayId + " reason=" + landedReason);
                taskApi.removeTask(topTid);
                SystemClock.sleep(80L);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivityWithAmFallback(launchIntent, launchOpts, displayId, true, safeUserId);
                services.focusDisplay(displayId);
                SystemClock.sleep(80L);
                topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg, safeUserId);
            }
        }
        int resolvedTid = (topTid > 0) ? topTid
                : (existingOnTarget > 0 ? existingOnTarget : taskApi.findRunningOrRecentTaskForPackage(pkg, safeUserId));
        System.out.println("OPALDESK_LAUNCH_START_OK pkg=" + pkg + " user=" + safeUserId
                + " displayId=" + displayId + " tid=" + resolvedTid);
        return resolvedTid;
    }

    @Override
    public int relaunchFreshFullscreenApp(String pkg, String cls, int displayId) {
        return relaunchFreshFullscreenApp(pkg, cls, UserProfileAppPolicy.PRIMARY_USER_ID, displayId);
    }

    @Override
    public int relaunchFreshFullscreenApp(String pkg, String cls, int userId, int displayId) {
        if (displayId <= 0 || PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        int safeUserId = Math.max(0, userId);
        Bundle launchOpts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        Intent launchIntent = resolveSafeLaunchIntent(pkg, cls, safeUserId);
        if (launchIntent == null) {
            System.err.println("OPALDESK_RELOAD_NO_INTENT pkg=" + pkg + " user=" + safeUserId
                    + " cls=" + cls + " displayId=" + displayId);
            return -1;
        }
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (!startActivityWithAmFallback(launchIntent, launchOpts, displayId, true, safeUserId)) {
            System.err.println("OPALDESK_RELOAD_START_FAIL pkg=" + pkg + " user=" + safeUserId
                    + " displayId=" + displayId);
            return -1;
        }
        services.focusDisplay(displayId);
        SystemClock.sleep(100L);
        int topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg, safeUserId);
        int resolvedTid = (topTid > 0) ? topTid : taskApi.findRunningOrRecentTaskForPackage(pkg, safeUserId);
        System.out.println("OPALDESK_RELOAD_FRESH_OK pkg=" + pkg + " user=" + safeUserId
                + " displayId=" + displayId + " tid=" + resolvedTid);
        return resolvedTid;
    }

    @Override
    public int launchExplicitActivityOnDisplay(String pkg, String cls, int displayId) {
        return launchExplicitActivityOnDisplay(pkg, cls, UserProfileAppPolicy.PRIMARY_USER_ID, displayId);
    }

    @Override
    public int launchExplicitActivityOnDisplay(String pkg, String cls, int userId, int displayId) {
        if (displayId <= 0 || PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        int safeUserId = Math.max(0, userId);
        Bundle launchOpts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        Intent launchIntent = resolveSafeLaunchIntent(pkg, cls, safeUserId);
        if (launchIntent == null) {
            System.err.println("OPALDESK_LAUNCH_EXPLICIT_NO_INTENT pkg=" + pkg + " user=" + safeUserId
                    + " cls=" + cls + " displayId=" + displayId);
            return taskApi.findTopTaskIdOnDisplay(displayId, pkg, safeUserId);
        }
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (!startActivityWithAmFallback(launchIntent, launchOpts, displayId, false, safeUserId)) {
            System.err.println("OPALDESK_LAUNCH_EXPLICIT_FAIL pkg=" + pkg + " user=" + safeUserId
                    + " cls=" + cls + " displayId=" + displayId);
            return taskApi.findTopTaskIdOnDisplay(displayId, pkg, safeUserId);
        }
        services.focusDisplay(displayId);
        SystemClock.sleep(60L);
        int topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg, safeUserId);
        int resolvedTid = (topTid > 0) ? topTid : taskApi.findRunningOrRecentTaskForPackage(pkg, safeUserId);
        System.out.println("OPALDESK_LAUNCH_EXPLICIT_OK pkg=" + pkg + " user=" + safeUserId
                + " cls=" + cls + " displayId=" + displayId + " tid=" + resolvedTid);
        return resolvedTid;
    }

    /**
     * Launches an Activity via {@code IActivityTaskManager.startActivity[AsUser]} and falls back to
     * {@code am start [--user <userId>] --display <id> --windowingMode 1} if an OEM ROM rejects
     * direct hidden-API Binder calls.
     */
    private boolean startActivityWithAmFallback(
            Intent launchIntent, Bundle launchOpts, int displayId, boolean clearTask, int userId) {
        return services.startActivityAsShellWithAmFallback(launchIntent, launchOpts, displayId, clearTask, userId);
    }

    @Override
    public int findTopTaskIdOnDisplay(int displayId, String expectedPkg) {
        return taskApi.findTopTaskIdOnDisplay(displayId, expectedPkg);
    }

    @Override
    public int findTopTaskIdOnDisplay(int displayId, String expectedPkg, int userId) {
        return taskApi.findTopTaskIdOnDisplay(displayId, expectedPkg, Math.max(0, userId));
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
    public int findTopVisibleTaskIdOnDisplay(int displayId, String expectedPkg, int userId) {
        return taskApi.findTopVisibleTaskIdOnDisplay(displayId, expectedPkg, Math.max(0, userId));
    }

    /**
     * Returns the foreground user app package on {@code displayId} (e.g. Display 0 for Handoff),
     * filtering out the current Home launcher and blocked system/self packages.
     */
    public String findTopVisibleUserAppPackageOnDisplay(int displayId) {
        String pkg = taskApi.findTopVisiblePackageOnDisplay(displayId);
        if (pkg == null || pkg.isEmpty() || PackagePolicy.isBlockedWindowPkg(pkg)) {
            return "";
        }
        String homePkg = resolveDefaultHomePackage();
        if (!homePkg.isEmpty() && homePkg.equals(pkg)) {
            return "";
        }
        return pkg;
    }

    private String resolveDefaultHomePackage() {
        if (services.context == null) return "";
        try {
            PackageManager pm = services.context.getPackageManager();
            Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                return ri.activityInfo.packageName;
            }
        } catch (RuntimeException ignored) {
        }
        return "";
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
        forceStopPackage(pkg, UserProfileAppPolicy.PRIMARY_USER_ID);
    }

    @Override
    public void forceStopPackage(String pkg, int userId) {
        if (PackagePolicy.isBlockedWindowPkg(pkg)) return;
        int safeUserId = Math.max(0, userId);
        if (safeUserId > 0) {
            ShellCommand.runOutput("am", "force-stop", "--user", String.valueOf(safeUserId), pkg);
        } else {
            ShellCommand.runOutput("am", "force-stop", pkg);
        }
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

    private Intent resolveSafeLaunchIntent(String pkg, String cls, int userId) {
        ResolvedLaunchIntent info = resolveLaunchIntentInfo(pkg, cls, userId);
        return (info != null) ? info.intent : null;
    }

    private ResolvedLaunchIntent resolveLaunchIntentInfo(String pkg, String cls, int userId) {
        if (services.context == null || !PackagePolicy.isValidPackageName(pkg)) return null;
        int safeUserId = Math.max(0, userId);
        ComponentName launcherComp = services.resolveLaunchComponentForUser(pkg, safeUserId);
        Intent launcher = (launcherComp != null)
                ? new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(launcherComp)
                : services.context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (cls != null && PackagePolicy.isValidClassName(cls)) {
            boolean isMainLauncher = launcherComp != null && cls.equals(launcherComp.getClassName());
            Intent explicit = isMainLauncher
                    ? new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(pkg, cls))
                    : new Intent(Intent.ACTION_MAIN)
                            .setComponent(new ComponentName(pkg, cls));
            if (services.canResolveActivityForUser(explicit, safeUserId)) {
                return new ResolvedLaunchIntent(explicit, !isMainLauncher);
            }
            if (safeUserId > 0) {
                // Allow explicit component launch in secondary profile even if ROM hides queryIntentActivities.
                return new ResolvedLaunchIntent(
                        new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LAUNCHER)
                                .setComponent(new ComponentName(pkg, cls)),
                        false);
            }
        }
        return (launcher != null) ? new ResolvedLaunchIntent(launcher, false) : null;
    }
}
