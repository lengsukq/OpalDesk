package io.github.lengsukq.opaldesk.daemon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.VirtualDisplayPool;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.input.ExternalMouseRouter;
import io.github.lengsukq.opaldesk.daemon.input.ImmsDumpInspector;
import io.github.lengsukq.opaldesk.daemon.input.MouseRoutingPolicy;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeBinderService;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.ipc.DaemonControlSocket;
import io.github.lengsukq.opaldesk.daemon.ipc.HelperClientBridge;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.RootEnhancer;
import io.github.lengsukq.opaldesk.daemon.platform.TaskApiAdapter;
import io.github.lengsukq.opaldesk.daemon.platform.TaskStackObserver;
import io.github.lengsukq.opaldesk.daemon.stream.OpalCastCompositorBridge;
import io.github.lengsukq.opaldesk.daemon.util.DaemonLogBuffer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point and lifecycle coordinator for the OpalDesk v2 Universal Compositor Shell Daemon.
 *
 * <p>100% OEM-agnostic and rootless-first ({@code uid=2000}, {@code u:r:shell:s0}).
 * Preserves exact CLI ({@code --verify-helper}, {@code --stop}, optional {@code --root-enhance}),
 * fail-closed root privilege drop, single-instance {@code @io.github.lengsukq.opaldesk.daemon.ctl}
 * abstract socket, and zero-roundtrip scene state push.
 */
public final class OpalDeskDaemon implements Runnable {
    private static final long RECONCILE_TICK_MS = 2000L;
    private static final long NEED_BINDER_DEBOUNCE_MS = 500L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final Handler mainHandler;
    private final ExecutorService bgExec;
    private final HelperClientBridge helperBridge;
    private final AndroidDisplayDriver displayDriver;
    private final PerDisplayInputRouter inputRouter;
    private final ExternalMouseRouter mouseRouter;
    private final VirtualDisplayPool vdPool;
    private final CompositorEngine compositor;
    private final OpalCastCompositorBridge remoteBridge;
    private final TaskStackObserver taskStackObserver;

    private final AtomicBoolean shutdownDone = new AtomicBoolean(false);
    private final AtomicLong imeProbeGen = new AtomicLong(0L);
    private final AtomicInteger lastHandledImmsShowSeq = new AtomicInteger(-1);
    private volatile long lastNeedBinderRxMs = 0L;

    private OpalDeskDaemon(AndroidServices services, RootEnhancer.EnhancementReport rootReport) {
        this.services = services;
        this.config = new DaemonConfig();
        this.config.loadAll();
        if (rootReport != null) {
            this.config.markRootEnhanced(rootReport.rootEnhanced, rootReport.secureCaptureHint);
        }

        this.mainHandler = new Handler(Looper.getMainLooper());
        this.bgExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OpalDaemonBg");
            t.setDaemon(true);
            return t;
        });

        TaskApiAdapter taskApi = new TaskApiAdapter(services.iActivityTaskManager);
        this.displayDriver = new AndroidDisplayDriver(services, taskApi);
        this.inputRouter = new PerDisplayInputRouter(services, config);
        this.mouseRouter = new ExternalMouseRouter(services, config, mainHandler);
        this.displayDriver.setInputRouter(this.inputRouter);

        this.helperBridge = new HelperClientBridge(services, config);
        this.vdPool = new VirtualDisplayPool(
                displayDriver,
                config.getMaxActiveDisplays(),
                config.isSecureCaptureEnabled());
        this.compositor = new CompositorEngine(
                config,
                vdPool,
                displayDriver,
                this::pushSceneSnapshotAsync);
        this.compositor.setBackReconcileScheduler(winId -> {
            mainHandler.postDelayed(() -> bgExec.execute(() -> compositor.reconcileWindowAfterBack(winId)), 240L);
            mainHandler.postDelayed(() -> bgExec.execute(() -> compositor.reconcileWindowAfterBack(winId)), 560L);
        });
        this.inputRouter.setTapUpListener(this::scheduleImeFallbackProbe);
        this.remoteBridge = new OpalCastCompositorBridge(
                services,
                config,
                compositor,
                displayDriver,
                inputRouter,
                mouseRouter);
        this.remoteBridge.setPairingEventConsumer(helperBridge::pushPairingEvent);
        this.remoteBridge.setRemoteStateChangeListener(helperBridge::pushRemoteStateChanged);
        this.displayDriver.setAllSurfacesDetachedListener(() -> {
            if (services != null && MouseRoutingPolicy.shouldRestoreDefaultDisplayFocusOnLocalExit(
                    mouseRouter.isLocalDesktopPinned(),
                    mouseRouter.getBoundDisplayId(),
                    remoteBridge.hasActiveDesktopSession())) {
                services.restoreDefaultDisplayTopFocus();
            }
        });
        this.taskStackObserver = new TaskStackObserver(
                services.iActivityTaskManager,
                mainHandler,
                () -> bgExec.execute(compositor::reconcileWithSystemTasks));

        BridgeBinderService binderService = new BridgeBinderService(
                services,
                config,
                helperBridge,
                compositor,
                inputRouter,
                mouseRouter,
                taskApi,
                remoteBridge,
                this::performShutdownCleanup);
        helperBridge.setControlBinder(binderService);
        helperBridge.setOnHelperBound(() -> {
            String pending = remoteBridge.getPendingPairingJson();
            if (pending != null && !pending.isEmpty()) {
                helperBridge.pushPairingEvent(pending);
            }
        });
        helperBridge.setOnHelperDeath(() -> {
            // Detach orphaned surfaces, wake host screen if dimmed, & restore hardware mice + focus to Display 0
            try {
                if (services != null && services.isHostScreenOff()) {
                    services.setPrimaryPhysicalDisplayPower(true);
                }
            } catch (RuntimeException ignored) {
            }
            compositor.detachAllClientSurfaces();
            mouseRouter.releaseToLocal();
            inputRouter.releaseAllDisplays();
            if (services != null && !remoteBridge.hasActiveDesktopSession()) {
                services.restoreDefaultDisplayTopFocus();
            }
        });
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) {
        try {
            RootEnhancer.EnhancementReport report = RootEnhancer.maybeApplyPreDropEnhancements(args);
            dropRootFailClosed();
            if (args != null && args.length > 0) {
                if ("--verify-helper".equals(args[0])) {
                    Looper.prepareMainLooper();
                    int vUid = HelperClientBridge.verifyHelperUid(AndroidServices.createSystemContext());
                    System.exit(vUid > 0 ? 0 : 3);
                    return;
                }
                if ("--stop".equals(args[0])) {
                    DaemonControlSocket.sendShutdown(6500);
                    System.exit(0);
                    return;
                }
            }
            if (!DaemonControlSocket.acquireAndStart()) {
                System.err.println("OPALDESK_LOCK_BUSY another daemon instance holds @" + BridgeProtocol.CTL_SOCKET_NAME);
                System.exit(2);
                return;
            }
            DaemonLogBuffer.installSystemStreamTee();
            Looper.prepareMainLooper();
            installUncaughtExceptionHandler();

            OpalDeskDaemon daemon = new OpalDeskDaemon(AndroidServices.createForDaemon(), report);
            DaemonControlSocket.bindHandlers(
                    () -> daemon.config.shuttingDown,
                    daemon.helperBridge::isTrustedCaller,
                    daemon::performShutdownCleanup,
                    () -> daemon.helperBridge.enqueuePushBinder(false));
            daemon.start();
            Looper.loop();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private void start() {
        taskStackObserver.start();
        mouseRouter.start();
        remoteBridge.start();
        helperBridge.enqueuePushBinder(true);
        for (long delayMs : new long[]{350L, 1000L, 2200L}) {
            mainHandler.postDelayed(() -> {
                if (!config.shuttingDown && !helperBridge.isHelperAlive()) {
                    helperBridge.enqueuePushBinder(true);
                }
            }, delayMs);
        }

        registerNeedBinderReceiver();
        Runtime.getRuntime().addShutdownHook(new Thread(this::performShutdownCleanup, "OpalShutdownHook"));

        System.out.println("OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=" + android.os.Process.myUid()
                + " maxVd=" + config.getMaxActiveDisplays()
                + " rootEnhanced=" + config.isRootEnhanced()
                + " ctx=" + readSeLinuxContextQuietly());
        System.out.flush();

        bgExec.execute(helperBridge::grantHelperPrivileges);
        mainHandler.postDelayed(this, RECONCILE_TICK_MS);
    }

    @Override
    public void run() {
        if (config.shuttingDown) return;
        try {
            long now = SystemClock.uptimeMillis();
            helperBridge.maybePushBinderOnTick(now);
            vdPool.setConfiguredMaxDisplays(config.getMaxActiveDisplays());
            bgExec.execute(compositor::reconcileWithSystemTasks);
        } catch (Throwable t) {
            System.err.println("OPALDESK_TICK_WARN: " + t.getMessage());
        }
        if (!config.shuttingDown) {
            mainHandler.postDelayed(this, RECONCILE_TICK_MS);
        }
    }

    private void pushSceneSnapshotAsync() {
        helperBridge.pushSceneState(compositor.toSceneJson(), compositor.getStateSeq());
        if (remoteBridge != null) {
            bgExec.execute(remoteBridge::onCompositorWindowsChanged);
        }
    }

    private void scheduleImeFallbackProbe(int windowId, int displayId, long tapDownUptimeMs) {
        if (config.shuttingDown || windowId <= 0 || displayId <= 0 || inputRouter.isLocalImeActive()) return;
        final long gen = imeProbeGen.incrementAndGet();
        mainHandler.postDelayed(
                () -> bgExec.execute(() -> runImeFallbackProbe(gen, windowId, displayId, tapDownUptimeMs)),
                110L);
        mainHandler.postDelayed(
                () -> bgExec.execute(() -> runImeFallbackProbe(gen, windowId, displayId, tapDownUptimeMs)),
                260L);
        mainHandler.postDelayed(
                () -> bgExec.execute(() -> runImeFallbackProbe(gen, windowId, displayId, tapDownUptimeMs)),
                460L);
    }

    private void runImeFallbackProbe(long gen, int windowId, int displayId, long tapDownUptimeMs) {
        if (config.shuttingDown || imeProbeGen.get() != gen || inputRouter.isLocalImeActive()) return;
        WindowSession ws = compositor.getWindowSnapshot(windowId);
        if (ws == null || !ws.isLive() || !ws.focused || ws.displayId != displayId) return;

        ImmsDumpInspector.Snapshot snap = AndroidServices.inspectInputMethodService(displayId);
        if (snap == null || imeProbeGen.get() != gen) return;

        long nowUptime = SystemClock.uptimeMillis();
        boolean remoteDesktopActive = remoteBridge != null && remoteBridge.hasActiveDesktopSession();
        if (remoteDesktopActive) {
            // While casting desktop to a remote receiver (e.g. Phone -> Tablet), always summon the
            // receiver's soft keyboard — even if Android 16 opened a fallback IME on the sender phone!
            if (ImmsDumpInspector.shouldTriggerRemoteCastIme(
                    snap, displayId, lastHandledImmsShowSeq.get(), tapDownUptimeMs, nowUptime)) {
                lastHandledImmsShowSeq.set(snap.latestShowSeq);
                imeProbeGen.compareAndSet(gen, gen + 1L);
                int rawType = snap.effectiveInputType(displayId);
                int hostType = ImmsDumpInspector.sanitizeHostInputType(rawType);
                System.out.println("OPALDESK_REMOTE_IME_FORWARD win=" + windowId
                        + " display=" + displayId
                        + " seq=" + snap.latestShowSeq
                        + " inputShown=" + snap.inputShown
                        + " hostType=0x" + Integer.toHexString(hostType));
                remoteBridge.sendDesktopImeRequest(true, hostType);
            }
            return;
        }

        if (snap.inputShown) {
            // Native cross-display fallback IME opened cleanly on Display 0 (e.g. Android 16 local DeX) — do not interfere.
            if (snap.latestShowSeq > lastHandledImmsShowSeq.get()) {
                lastHandledImmsShowSeq.set(snap.latestShowSeq);
            }
            imeProbeGen.compareAndSet(gen, gen + 1L);
            return;
        }

        if (ImmsDumpInspector.shouldTriggerHostImeBridge(
                snap, displayId, lastHandledImmsShowSeq.get(), tapDownUptimeMs, nowUptime)) {
            lastHandledImmsShowSeq.set(snap.latestShowSeq);
            imeProbeGen.compareAndSet(gen, gen + 1L);
            int rawType = snap.effectiveInputType(displayId);
            int hostType = ImmsDumpInspector.sanitizeHostInputType(rawType);
            System.out.println("OPALDESK_IME_AUTO_BRIDGE win=" + windowId
                    + " display=" + displayId
                    + " seq=" + snap.latestShowSeq
                    + " reason=" + snap.latestShowReason
                    + " rawType=0x" + Integer.toHexString(rawType)
                    + " hostType=0x" + Integer.toHexString(hostType));
            helperBridge.pushImeRequest(windowId, true, hostType);
        }
    }

    private void performShutdownCleanup() {
        config.shuttingDown = true;
        if (!shutdownDone.compareAndSet(false, true)) return;
        try {
            if (services != null && services.isHostScreenOff()) {
                services.setPrimaryPhysicalDisplayPower(true);
            }
        } catch (RuntimeException ignored) {
        }
        // Unbind hardware mice FIRST before tearing down VirtualDisplays so InputReader never sees
        // an active UniqueIdAssociation pointing at a destroyed VirtualDisplay.
        try {
            mouseRouter.shutdown();
        } catch (RuntimeException ignored) {
        }
        try {
            remoteBridge.shutdown();
        } catch (RuntimeException ignored) {
        }
        try {
            taskStackObserver.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            compositor.closeAllWindows(false);
        } catch (RuntimeException ignored) {
        }
        try {
            inputRouter.releaseAllDisplays();
        } catch (RuntimeException ignored) {
        }
        try {
            displayDriver.shutdown();
        } catch (RuntimeException ignored) {
        }
        try {
            if (services != null) {
                services.restoreDefaultDisplayTopFocus();
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void registerNeedBinderReceiver() {
        IntentFilter flt = new IntentFilter(BridgeProtocol.ACTION_NEED_BINDER);
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || !BridgeProtocol.ACTION_NEED_BINDER.equals(i.getAction())) return;
                if (Build.VERSION.SDK_INT >= 34 && !helperBridge.isTrustedCaller(getSentFromUid())) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastNeedBinderRxMs < NEED_BINDER_DEBOUNCE_MS) return;
                lastNeedBinderRxMs = now;
                helperBridge.enqueuePushBinder(false);
            }
        };
        registerReceiverOnContext(services.context, rx, flt);
        if (services.systemContext != null && services.systemContext != services.context) {
            registerReceiverOnContext(services.systemContext, rx, flt);
        }
    }

    private static void registerReceiverOnContext(Context ctx, BroadcastReceiver rx, IntentFilter flt) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, flt, Context.RECEIVER_EXPORTED);
            else ctx.registerReceiver(rx, flt);
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static void dropRootFailClosed() {
        int initUid = android.os.Process.myUid();
        int osUid = -1;
        try { osUid = android.system.Os.getuid(); } catch (RuntimeException ignored) {}
        if (initUid != 0 && osUid != 0) return;
        try {
            dropSupplementaryGroupsQuietly();
            android.system.Os.setgid(2000);
            android.system.Os.setuid(2000);
            int curUid = android.system.Os.getuid(), curEuid = android.system.Os.geteuid();
            int curGid = android.system.Os.getgid(), curEgid = android.system.Os.getegid();
            if (android.os.Process.myUid() != 2000 || curUid != 2000 || curEuid != 2000 || curGid != 2000 || curEgid != 2000) {
                System.err.println("OPALDESK_FATAL_DROP_ROOT_INCOMPLETE uid=" + curUid + " gid=" + curGid);
                System.exit(1);
                return;
            }
            try (FileOutputStream out = new FileOutputStream("/proc/thread-self/attr/current")) {
                out.write("u:r:shell:s0\0".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException ignored) {
            }
            System.out.println("OPALDESK_ROOT_DROPPED_TO_SHELL uid=2000 gid=2000");
        } catch (Throwable t) {
            System.err.println("OPALDESK_FATAL_DROP_ROOT_FAILED: " + t);
            System.exit(1);
        }
    }

    private static void dropSupplementaryGroupsQuietly() {
        try {
            Object osObj = Class.forName("libcore.io.Libcore").getField("os").get(null);
            if (osObj == null) return;
            for (Method m : osObj.getClass().getMethods()) {
                if ("setgroups".equals(m.getName()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int[].class) {
                    m.invoke(osObj, (Object) new int[]{2000});
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static String readSeLinuxContextQuietly() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/attr/current")) {
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n <= 0) return "unknown";
            int end = 0;
            while (end < n && buf[end] != 0 && buf[end] != '\n' && buf[end] != '\r') {
                end++;
            }
            String s = new String(buf, 0, end, StandardCharsets.US_ASCII).trim();
            return s.isEmpty() ? "unknown" : s;
        } catch (IOException | RuntimeException e) {
            return "unknown";
        }
    }

    private static void installUncaughtExceptionHandler() {
        Thread mainThread = Thread.currentThread();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("OPALDESK_UNCAUGHT_EXCEPTION thread="
                    + (thread != null ? thread.getName() : "unknown") + ": " + throwable);
            if (throwable != null) throwable.printStackTrace();
            if (thread == mainThread && prev != null) prev.uncaughtException(thread, throwable);
        });
    }
}
