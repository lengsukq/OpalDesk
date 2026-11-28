package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.VirtualDisplayPool;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CompositorEngineTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private static final class RecordingDriver implements VirtualDisplayPool.DisplayDriver {
        final AtomicInteger nextDisplayId = new AtomicInteger(200);
        final List<String> events = new ArrayList<>();
        int resizeCount = 0;
        int detachSurfaceCount = 0;
        int releaseDisplayCount = 0;

        @Override
        public CreatedDisplay createVirtualDisplay(
                String name, int width, int height, int dpi, float fps, Object surface, boolean allowSecure) {
            int id = nextDisplayId.getAndIncrement();
            events.add("createVd:" + id);
            return new CreatedDisplay("vd-" + id, id, "uid-" + id);
        }

        @Override
        public void resizeVirtualDisplay(Object handle, int width, int height, int dpi) {
            resizeCount++;
        }

        @Override
        public void setVirtualDisplaySurface(Object handle, Object surface) {
            if (surface == null) {
                detachSurfaceCount++;
                events.add("detachSurface:" + handle);
            } else {
                events.add("attachSurface:" + handle);
            }
        }

        @Override
        public void releaseVirtualDisplay(Object handle, int displayId) {
            releaseDisplayCount++;
            events.add("releaseVd:" + displayId);
        }

        @Override
        public byte[] captureTaskSnapshotBytes(int taskId) {
            return ("jpeg-" + taskId).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class FakeRuntime implements CompositorEngine.AppRuntimeBridge {
        final AtomicInteger nextTaskId = new AtomicInteger(900);
        final Set<Integer> aliveTasks = new HashSet<>();
        final Set<Integer> runningBgTasks = new HashSet<>();
        final Map<Integer, Integer> topTaskByDisplay = new HashMap<>();
        final Map<Integer, List<Integer>> allTasksByDisplay = new HashMap<>();
        final Map<Integer, Integer> visibleTaskByDisplay = new HashMap<>();
        final Set<Integer> removedTasks = new HashSet<>();
        final List<String> forceStoppedPackages = new ArrayList<>();
        final List<String> events;
        int launchCount = 0;
        int focusDisplayCount = 0;
        int allSurfacesDetachedCount = 0;

        FakeRuntime() {
            this(new ArrayList<>());
        }

        FakeRuntime(List<String> sharedEvents) {
            this.events = sharedEvents;
        }

        @Override
        public String resolveAppLabel(String pkg) {
            return "Label:" + pkg;
        }

        @Override
        public int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId) {
            launchCount++;
            int tid = (existingTaskId > 0) ? existingTaskId : nextTaskId.getAndIncrement();
            aliveTasks.add(tid);
            runningBgTasks.add(tid);
            topTaskByDisplay.put(displayId, tid);
            visibleTaskByDisplay.put(displayId, tid);
            events.add("launch:" + tid + "@d" + displayId);
            return tid;
        }

        @Override
        public int launchExplicitActivityOnDisplay(String pkg, String cls, int displayId) {
            launchCount++;
            int tid = topTaskByDisplay.getOrDefault(displayId, nextTaskId.getAndIncrement());
            aliveTasks.add(tid);
            runningBgTasks.add(tid);
            topTaskByDisplay.put(displayId, tid);
            visibleTaskByDisplay.put(displayId, tid);
            events.add("explicit:" + cls + "@d" + displayId);
            return tid;
        }

        @Override
        public int findTopTaskIdOnDisplay(int displayId, String expectedPkg) {
            return topTaskByDisplay.getOrDefault(displayId, -1);
        }

        @Override
        public List<Integer> findAllTaskIdsOnDisplay(int displayId) {
            List<Integer> extra = allTasksByDisplay.get(displayId);
            if (extra != null) return new ArrayList<>(extra);
            Integer top = topTaskByDisplay.get(displayId);
            if (top != null && top > 0) return java.util.Collections.singletonList(top);
            return java.util.Collections.emptyList();
        }

        @Override
        public int findTopVisibleTaskIdOnDisplay(int displayId, String expectedPkg) {
            Integer top = topTaskByDisplay.get(displayId);
            if (top == null || top <= 0) return -1;
            return visibleTaskByDisplay.getOrDefault(displayId, -1);
        }

        @Override
        public boolean isTaskRunningWithActivities(int taskId, int displayId) {
            if (aliveTasks.contains(taskId) && runningBgTasks.contains(taskId)) {
                return true;
            }
            Integer dispTid = topTaskByDisplay.get(displayId);
            return dispTid != null && aliveTasks.contains(dispTid) && runningBgTasks.contains(dispTid);
        }

        @Override
        public Set<Integer> collectAliveTaskIds() {
            return new HashSet<>(aliveTasks);
        }

        @Override
        public void removeTask(int taskId) {
            aliveTasks.remove(taskId);
            removedTasks.add(taskId);
            events.add("removeTask:" + taskId);
        }

        @Override
        public void forceStopPackage(String pkg) {
            forceStoppedPackages.add(pkg);
            events.add("forceStop:" + pkg);
        }

        @Override
        public void focusDisplay(int displayId) {
            focusDisplayCount++;
            events.add("focusDisplay:" + displayId);
        }

        @Override
        public void onAllClientSurfacesDetached() {
            allSurfacesDetachedCount++;
            events.add("allSurfacesDetached");
        }

        @Override
        public void onDisplayReleased(int displayId) {
            topTaskByDisplay.remove(displayId);
            events.add("onDisplayReleased:" + displayId);
        }

        @Override
        public void releaseSurfaceHandle(Object surface) {}
    }

    @Test
    public void testMoveWithoutSizeChangePerformsZeroVirtualDisplayResizes() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c.conf"), new File(tmpDir.getRoot(), "p.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 1920, 1200, 240);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime();
        AtomicInteger dirtyEvents = new AtomicInteger();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, dirtyEvents::incrementAndGet);

        int w1 = engine.openOrFocusWindow("org.mozilla.firefox", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        assertTrue(w1 > 0);
        int dispId = engine.attachWindowSurface(w1, new Object(), 1000, 700, 200, 120f);
        assertTrue(dispId >= 200);
        assertEquals(0, driver.resizeCount);

        WindowSession before = engine.getWindowSnapshot(w1);
        int width = before.frame.width();
        int height = before.frame.height();

        // Pure translation move (same width & height): MUST NOT resize the underlying VirtualDisplay!
        assertTrue(engine.updateWindowFrame(w1, 240, 160, 240 + width, 160 + height, BridgeProtocol.PRES_FLOATING));
        assertEquals("Pure window translation must trigger 0 VirtualDisplay resizes", 0, driver.resizeCount);

        // Snap left & toggle maximize: MUST resize VirtualDisplay and preserve restoreFrame
        IntRect savedFloating = engine.getWindowSnapshot(w1).frame;
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE, null));
        assertEquals(BridgeProtocol.PRES_MAXIMIZED, engine.getWindowSnapshot(w1).presentation);
        assertEquals(1, driver.resizeCount);

        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE, null));
        assertEquals(BridgeProtocol.PRES_FLOATING, engine.getWindowSnapshot(w1).presentation);
        assertEquals(savedFloating, engine.getWindowSnapshot(w1).frame);

        // Toggle true app fullscreen: fills full canvas edge-to-edge (0, 0, 1920, 1200) with zero header subtraction
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN, null));
        WindowSession fsWin = engine.getWindowSnapshot(w1);
        assertEquals(BridgeProtocol.PRES_FULLSCREEN, fsWin.presentation);
        assertEquals(new IntRect(0, 0, 1920, 1200), fsWin.frame);
        assertEquals(1920, fsWin.contentWidth);
        assertEquals(1200, fsWin.contentHeight);

        // Toggle fullscreen off -> cleanly restores floating frame
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN, null));
        assertEquals(BridgeProtocol.PRES_FLOATING, engine.getWindowSnapshot(w1).presentation);
        assertEquals(savedFloating, engine.getWindowSnapshot(w1).frame);
    }

    @Test
    public void testProfilePersistenceLruHibernationWakeAndDeadTaskCleanup() throws Exception {
        File confF = new File(tmpDir.getRoot(), "main.conf");
        File profF = new File(tmpDir.getRoot(), "profiles.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 2, false);
        FakeRuntime runtime = new FakeRuntime();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        // Reject blocked system packages
        assertEquals(-1, engine.openOrFocusWindow("com.android.systemui", "", 0, 0, 0, 0, 0));
        assertEquals(-1, engine.openOrFocusWindow(BridgeBuildConfig.HELPER_PACKAGE, "", 0, 0, 0, 0, 0));

        int w1 = engine.openOrFocusWindow("com.tiktok.lite", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int w2 = engine.openOrFocusWindow("com.Slack", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        int w3 = engine.openOrFocusWindow("org.telegram.messenger", "", BridgeProtocol.PROFILE_TABLET, 0, 0, 0, 0);

        assertEquals(WindowProfile.PORTRAIT, cfg.getSavedProfile("com.tiktok.lite"));
        assertTrue(engine.attachWindowSurface(w1, "surf1", 540, 1080, 320, 60f) > 0);
        Thread.sleep(5L);
        assertTrue(engine.attachWindowSurface(w2, "surf2", 1200, 800, 200, 60f) > 0);
        Thread.sleep(5L);

        // Attaching 3rd window on maxVd=2 pool hibernates w1 with a cached JPEG snapshot
        assertTrue(engine.attachWindowSurface(w3, "surf3", 1024, 768, 220, 60f) > 0);
        WindowSession s1 = engine.getWindowSnapshot(w1);
        assertEquals(BridgeProtocol.STATE_HIBERNATED, s1.state);
        assertNotNull(engine.getHibernatedSnapshotBytes(w1));

        String json = engine.toSceneJson();
        assertTrue(json.contains("\"pkg\":\"com.tiktok.lite\""));
        assertTrue(json.contains("\"state\":\"HIBERNATED\""));
        assertTrue(json.contains("\"activeVdCount\":2"));

        // Waking hibernated w1 must transition state back to LIVE so client mounts TextureView
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_WAKE, null));
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w1).state);

        // Minimizing and restoring w2 via openOrFocusWindow must also transition back to LIVE
        assertTrue(engine.performWindowAction(w2, BridgeProtocol.WIN_ACTION_MINIMIZE, null));
        assertEquals(BridgeProtocol.STATE_MINIMIZED, engine.getWindowSnapshot(w2).state);
        assertEquals(w2, engine.openOrFocusWindow("com.Slack", "", BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0));
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w2).state);

        // Simulate w3's underlying Android task exiting on its own -> reconcile cleans up window w3
        WindowSession s3 = engine.getWindowSnapshot(w3);
        s3.lastActiveAtMs = System.currentTimeMillis() - 5000L;
        runtime.aliveTasks.remove(s3.taskId);
        runtime.topTaskByDisplay.remove(s3.displayId);
        engine.reconcileWithSystemTasks();
        assertNull("Exited task window should be auto-removed during reconciliation", engine.getWindowSnapshot(w3));
    }

    @Test
    public void testFullscreenMinimizeKeepsVirtualDisplayAliveAndCloseRemovesTaskBeforeDisplay() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c2.conf"), new File(tmpDir.getRoot(), "p2.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 2560, 1600, 360);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(driver.events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int w1 = engine.openOrFocusWindow("tv.danmaku.bili", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int dispId = engine.attachWindowSurface(w1, new Object(), 788, 1078, 306, 60f);
        assertEquals(1, runtime.launchCount);
        WindowSession floating = engine.getWindowSnapshot(w1);
        IntRect savedFrame = floating.frame;
        int tid = floating.taskId;

        // Enter fullscreen, then click "Return to Desktop" (minimizeAllWindows)
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN, null));
        assertEquals(BridgeProtocol.PRES_FULLSCREEN, engine.getWindowSnapshot(w1).presentation);

        engine.minimizeAllWindows();
        WindowSession minWin = engine.getWindowSnapshot(w1);
        assertNotNull("Minimizing from fullscreen must NOT close the window", minWin);
        assertEquals(BridgeProtocol.STATE_MINIMIZED, minWin.state);
        assertEquals(BridgeProtocol.PRES_FLOATING, minWin.presentation);
        assertEquals(savedFrame, minWin.frame);
        assertEquals("VirtualDisplay MUST stay leased on minimize so Android never bounces app to Phone Display 0",
                dispId, minWin.displayId);
        assertEquals(0, driver.releaseDisplayCount);
        assertTrue(driver.detachSurfaceCount >= 1);
        assertTrue(runtime.removedTasks.isEmpty());

        // Restore from Dock: reuses exact VirtualDisplay lease with 0 relaunch
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_FOCUS, null));
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w1).state);
        int resumedDispId = engine.attachWindowSurface(w1, new Object(), minWin.contentWidth, minWin.contentHeight, minWin.contentDpi, 60f);
        assertEquals(dispId, resumedDispId);
        assertEquals("Resuming minimized window must reuse task without relaunch", 1, runtime.launchCount);
        assertEquals(1, runtime.focusDisplayCount);

        // Close window: task MUST be removed BEFORE VirtualDisplay is released
        driver.events.clear();
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_CLOSE, null));
        assertNull(engine.getWindowSnapshot(w1));
        int rmIdx = driver.events.indexOf("removeTask:" + tid);
        int relIdx = driver.events.indexOf("releaseVd:" + dispId);
        assertTrue("removeTask must be called on close", rmIdx >= 0);
        assertTrue("releaseVd must be called on close", relIdx >= 0);
        assertTrue("removeTask MUST execute before releaseVd to prevent Display 0 bounce", rmIdx < relIdx);
    }

    @Test
    public void testBackAtRootPreventsBlankWindowOnMoveToBackAndFinish() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c3.conf"), new File(tmpDir.getRoot(), "p3.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 2560, 1600, 360);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(driver.events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        // 1. Sub-page Back: task remains visible -> window stays LIVE
        int w1 = engine.openOrFocusWindow("com.tencent.mm", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int d1 = engine.attachWindowSurface(w1, new Object(), 720, 1280, 280, 60f);
        engine.reconcileWindowAfterBack(w1);
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w1).state);

        // 2. Root Back with moveTaskToBack(true): display loses visible task while task still runs in bg -> minimize cleanly
        runtime.visibleTaskByDisplay.remove(d1);
        engine.reconcileWindowAfterBack(w1);
        WindowSession minWs = engine.getWindowSnapshot(w1);
        assertNotNull("Backgrounded task should minimize to Dock instead of showing blank white screen", minWs);
        assertEquals(BridgeProtocol.STATE_MINIMIZED, minWs.state);

        // Reopening from Dock must bring the hidden task back via launchOrMoveFullscreenApp (not skip relaunch!)
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_FOCUS, null));
        int launchesBeforeWake = runtime.launchCount;
        engine.attachWindowSurface(w1, new Object(), 720, 1280, 280, 60f);
        assertEquals("Waking a moveTaskToBack window must re-surface it", launchesBeforeWake + 1, runtime.launchCount);

        // 3. Root Back with finish(): task no longer visible AND no running activities (even if still in recent tasks) -> close cleanly
        int tid1 = engine.getWindowSnapshot(w1).taskId;
        runtime.visibleTaskByDisplay.remove(d1);
        runtime.runningBgTasks.remove(tid1);
        engine.reconcileWindowAfterBack(w1);
        assertNull("Exited app must close window cleanly instead of leaving a blank white window",
                engine.getWindowSnapshot(w1));
    }

    @Test
    public void reloadWindowRefreshesLaunchGracePeriodAndPreservesDisplay() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c4.conf"), new File(tmpDir.getRoot(), "p4.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 2560, 1600, 360);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(driver.events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int w1 = engine.openOrFocusWindow("com.tencent.mm", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int d1 = engine.attachWindowSurface(w1, new Object(), 1236, 1738, 480, 60f);
        WindowSession before = engine.getWindowSnapshot(w1);
        before.lastLaunchAtMs = 1L;
        before.emptyDisplayTicks = 2;

        int launchesBefore = runtime.launchCount;
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_RELOAD_APP, null));

        WindowSession after = engine.getWindowSnapshot(w1);
        assertNotNull("Reloading an app must never prematurely close the window", after);
        assertEquals("Reload must keep the same VirtualDisplay alive", d1, after.displayId);
        assertEquals(0, after.emptyDisplayTicks);
        assertTrue("Reload must refresh lastLaunchAtMs grace window", after.lastLaunchAtMs > 1L);
        assertEquals(launchesBefore + 1, runtime.launchCount);
    }

    @Test
    public void transientFullscreenActivityHandoffDoesNotPrematurelyMinimizeOrCloseWindow() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c5.conf"), new File(tmpDir.getRoot(), "p5.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 2560, 1600, 360);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(driver.events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int w1 = engine.openOrFocusWindow("com.limelight", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        int d1 = engine.attachWindowSurface(w1, new Object(), 1920, 1080, 240, 120f);
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN, null));

        WindowSession ws = engine.getWindowSnapshot(w1);
        long t0 = 100_000L;
        ws.lastLaunchAtMs = t0 - 10_000L;
        ws.lastActiveAtMs = t0 - 500L;

        // Simulate Moonlight / UU远程 launching its fullscreen streaming Activity:
        // top visible task momentarily disappears across three rapid 40ms TaskStackObserver callbacks.
        runtime.visibleTaskByDisplay.remove(d1);
        engine.reconcileWithSystemTasks(t0);
        engine.reconcileWithSystemTasks(t0 + 40L);
        engine.reconcileWithSystemTasks(t0 + 180L);

        WindowSession duringTransition = engine.getWindowSnapshot(w1);
        assertNotNull("Rapid TaskStackObserver callbacks must NOT close streaming window", duringTransition);
        assertEquals("Fullscreen streaming window must remain LIVE during sub-900ms Activity handoff",
                BridgeProtocol.STATE_LIVE, duringTransition.state);
        assertEquals(BridgeProtocol.PRES_FULLSCREEN, duringTransition.presentation);

        // Streaming Activity becomes visible at +320ms -> emptySinceMs & emptyDisplayTicks reset cleanly!
        int streamTid = 999;
        runtime.aliveTasks.add(streamTid);
        runtime.runningBgTasks.add(streamTid);
        runtime.topTaskByDisplay.put(d1, streamTid);
        runtime.visibleTaskByDisplay.put(d1, streamTid);
        engine.reconcileWithSystemTasks(t0 + 320L);
        assertEquals(0, engine.getWindowSnapshot(w1).emptyDisplayTicks);
        assertEquals(0L, engine.getWindowSnapshot(w1).emptySinceMs);
        assertEquals(streamTid, engine.getWindowSnapshot(w1).taskId);

        // Simulate lobby task finishing right after spawning NEW_TASK fullscreen stream on same display:
        // aliveTasks no longer has streamTid (999), while new task 1005 is running on d1 but briefly invisible!
        runtime.aliveTasks.remove(streamTid);
        runtime.runningBgTasks.remove(streamTid);
        int newStreamTid = 1005;
        runtime.aliveTasks.add(newStreamTid);
        runtime.runningBgTasks.add(newStreamTid);
        runtime.topTaskByDisplay.put(d1, newStreamTid);
        runtime.visibleTaskByDisplay.remove(d1);
        ws.lastActiveAtMs = t0 - 3_000L;
        engine.reconcileWithSystemTasks(t0 + 400L);
        assertNotNull("Finishing lobby task while new stream task runs on same display must NOT close window",
                engine.getWindowSnapshot(w1));
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w1).state);

        runtime.visibleTaskByDisplay.put(d1, newStreamTid);
        engine.reconcileWithSystemTasks(t0 + 520L);
        assertEquals(newStreamTid, engine.getWindowSnapshot(w1).taskId);

        // Detaching last client surface (e.g. exiting DesktopActivity) triggers Display 0 focus restore callback
        int beforeDetachCb = runtime.allSurfacesDetachedCount;
        assertTrue(engine.detachWindowSurface(w1, false));
        assertEquals(beforeDetachCb + 1, runtime.allSurfacesDetachedCount);

        // Re-attach and verify helper-death detachAllClientSurfaces also triggers Display 0 focus restore
        engine.attachWindowSurface(w1, new Object(), 1920, 1080, 240, 120f);
        ws.lastLaunchAtMs = t0 - 10_000L;
        ws.lastActiveAtMs = t0 - 500L;
        engine.detachAllClientSurfaces();
        assertEquals(beforeDetachCb + 2, runtime.allSurfacesDetachedCount);

        // Later, if the app truly moves itself to background for >= EMPTY_DISPLAY_SETTLE_MS, it minimizes cleanly
        runtime.visibleTaskByDisplay.remove(d1);
        engine.reconcileWithSystemTasks(t0 + 5_000L);
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w1).state);
        engine.reconcileWithSystemTasks(t0 + 5_000L + CompositorEngine.EMPTY_DISPLAY_SETTLE_MS + 50L);
        assertEquals(BridgeProtocol.STATE_MINIMIZED, engine.getWindowSnapshot(w1).state);
    }

    @Test
    public void testCustomGlobalAndPerAppDpiOverridesAndReapply() throws Exception {
        File confF = new File(tmpDir.getRoot(), "dpi_main.conf");
        File profF = new File(tmpDir.getRoot(), "dpi_prof.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 1920, 1200, 240);
        cfg.setGlobalCustomDpi(260);
        cfg.setAppCustomDpi("com.tencent.mm", 340);

        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int w1 = engine.openOrFocusWindow("org.mozilla.firefox", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        assertEquals(260, engine.getWindowSnapshot(w1).contentDpi);

        int w2 = engine.openOrFocusWindow("com.tencent.mm", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        assertEquals(340, engine.getWindowSnapshot(w2).contentDpi);

        engine.attachWindowSurface(w2, new Object(), 0, 0, 0, 60f);
        assertEquals(340, engine.getWindowSnapshot(w2).contentDpi);
        cfg.setAppCustomDpi("com.tencent.mm", 300);
        engine.reapplyAppDpi("com.tencent.mm");
        assertEquals(300, engine.getWindowSnapshot(w2).contentDpi);
        assertTrue(driver.resizeCount >= 1);

        DaemonConfig reloaded = new DaemonConfig(confF, profF);
        reloaded.loadAll();
        assertEquals(260, reloaded.getGlobalCustomDpi());
        assertEquals(300, reloaded.getAppCustomDpi("com.tencent.mm"));
        assertEquals(260, reloaded.resolveEffectiveCustomDpi("org.mozilla.firefox"));
        assertEquals(300, reloaded.resolveEffectiveCustomDpi("com.tencent.mm"));
    }

    @Test
    public void testPerAppLastClosedWindowBoundsPersistenceAndRestore() throws Exception {
        File confF = new File(tmpDir.getRoot(), "bounds_main.conf");
        File profF = new File(tmpDir.getRoot(), "bounds_prof.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 2000, 1000, 240);

        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int w1 = engine.openOrFocusWindow("com.example.notes", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        assertTrue(engine.updateWindowFrame(w1, 200, 120, 1000, 720, BridgeProtocol.PRES_FLOATING));
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_CLOSE, null));
        assertNull(engine.getWindowSnapshot(w1));

        DaemonConfig reloaded = new DaemonConfig(confF, profF);
        reloaded.loadAll();
        CompositorEngine engine2 = new CompositorEngine(reloaded, new VirtualDisplayPool(driver, 4, false), runtime, null);
        int w2 = engine2.openOrFocusWindow("com.example.notes", "", BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
        IntRect restored = engine2.getWindowSnapshot(w2).frame;
        assertEquals(200, restored.left);
        assertEquals(120, restored.top);
        assertEquals(800, restored.width());
        assertEquals(600, restored.height());
    }

    @Test
    public void testOpenOrFocusWindowSwitchesSettingsSubActivityInPlace() throws Exception {
        File confF = new File(tmpDir.getRoot(), "settings_main.conf");
        File profF = new File(tmpDir.getRoot(), "settings_prof.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 1920, 1200, 240);

        List<String> events = new ArrayList<>();
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        String wifiCls = "com.android.settings.Settings$WifiSettingsActivity";
        String btCls = "com.android.settings.Settings$BluetoothSettingsActivity";
        int w1 = engine.openOrFocusWindow("com.android.settings", wifiCls, BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
        int dispId = engine.attachWindowSurface(w1, new Object(), 900, 700, 240, 60f);
        assertTrue(dispId > 0);
        assertEquals(wifiCls, engine.getWindowSnapshot(w1).activityClass);

        // Switching an already-live Settings window to Bluetooth should dispatch explicit activity launch on same display.
        int w2 = engine.openOrFocusWindow("com.android.settings", btCls, BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
        assertEquals(w1, w2);
        assertEquals(btCls, engine.getWindowSnapshot(w1).activityClass);
        assertTrue(events.contains("explicit:" + btCls + "@d" + dispId));

        // Minimize, then open Wi-Fi again: re-attaching surface must launch the updated Wi-Fi activity.
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_MINIMIZE, null));
        int w3 = engine.openOrFocusWindow("com.android.settings", wifiCls, BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
        assertEquals(w1, w3);
        int dispId2 = engine.attachWindowSurface(w1, new Object(), 900, 700, 240, 60f);
        assertEquals(dispId, dispId2);
        assertTrue(events.contains("explicit:" + wifiCls + "@d" + dispId));
    }

    @Test
    public void testCloseRemovesAllTasksOnDisplayBeforeReleaseAndSkipsForceStopForSettings() throws Exception {
        // Guard against AOSP constant mismatch (1 = REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY, 2 = DESTROY)
        assertEquals(2, BridgeProtocol.REMOVE_CONTENT_MODE_DESTROY);

        File confF = new File(tmpDir.getRoot(), "close_all_tasks_main.conf");
        File profF = new File(tmpDir.getRoot(), "close_all_tasks_prof.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 1920, 1200, 240);

        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(driver.events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        // 1. Settings window with TWO tasks hosted on the same VirtualDisplay (e.g. SubSettings + WifiSettings)
        int wSettings = engine.openOrFocusWindow(
                "com.android.settings",
                "com.android.settings.Settings$WifiSettingsActivity",
                BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
        int dSettings = engine.attachWindowSurface(wSettings, new Object(), 900, 700, 240, 60f);
        int primaryTid = engine.getWindowSnapshot(wSettings).taskId;
        int secondaryTid = 8966;
        runtime.aliveTasks.add(secondaryTid);
        runtime.allTasksByDisplay.put(dSettings, java.util.Arrays.asList(primaryTid, secondaryTid));

        driver.events.clear();
        assertTrue(engine.performWindowAction(wSettings, BridgeProtocol.WIN_ACTION_CLOSE, null));
        assertNull(engine.getWindowSnapshot(wSettings));

        int rmPrimary = driver.events.indexOf("removeTask:" + primaryTid);
        int rmSecondary = driver.events.indexOf("removeTask:" + secondaryTid);
        int relSettings = driver.events.indexOf("releaseVd:" + dSettings);
        assertTrue("Primary task must be removed before VirtualDisplay release", rmPrimary >= 0 && rmPrimary < relSettings);
        assertTrue("Secondary task on same VirtualDisplay must ALSO be removed before release",
                rmSecondary >= 0 && rmSecondary < relSettings);
        assertFalse("Closing com.android.settings must NOT call am force-stop",
                runtime.forceStoppedPackages.contains("com.android.settings"));

        // 2. Normal third-party media app MUST still be force-stopped on explicit close
        int wMedia = engine.openOrFocusWindow("tv.danmaku.bili", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        engine.attachWindowSurface(wMedia, new Object(), 1200, 800, 240, 60f);
        assertTrue(engine.performWindowAction(wMedia, BridgeProtocol.WIN_ACTION_CLOSE, null));
        assertTrue("Closing third-party media app should still force-stop background playback",
                runtime.forceStoppedPackages.contains("tv.danmaku.bili"));
    }

    @Test
    public void testToggleOrientationRotatesLandscapeWindowToFullHeightPortraitAndBack() {
        File confF = new File(tmpDir.getRoot(), "orient_main.conf");
        File profF = new File(tmpDir.getRoot(), "orient_prof.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 1920, 1080, 240);

        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int wid = engine.openOrFocusWindow("com.ss.android.ugc.aweme", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        engine.attachWindowSurface(wid, new Object(), 1200, 720, 240, 60f);

        WindowSession before = engine.getWindowSnapshot(wid);
        assertTrue("Desktop window starts landscape", before.contentWidth > before.contentHeight);

        // Rotate landscape -> tall portrait (eliminates side letterboxing for vertical short-video apps)
        assertTrue(engine.performWindowAction(wid, BridgeProtocol.WIN_ACTION_TOGGLE_ORIENTATION, null));
        WindowSession portrait = engine.getWindowSnapshot(wid);
        assertEquals(WindowProfile.PORTRAIT, portrait.profile);
        assertEquals(BridgeProtocol.PRES_FLOATING, portrait.presentation);
        assertTrue("Rotated portrait window must be taller than wide", portrait.contentHeight > portrait.contentWidth);

        // Rotate portrait -> landscape desktop
        assertTrue(engine.performWindowAction(wid, BridgeProtocol.WIN_ACTION_TOGGLE_ORIENTATION, null));
        WindowSession landscape = engine.getWindowSnapshot(wid);
        assertEquals(WindowProfile.DESKTOP, landscape.profile);
        assertTrue("Second toggle restores landscape orientation", landscape.contentWidth > landscape.contentHeight);
    }

    @Test
    public void testUpdateWindowFrameDoesNotChurnZOrderWhenAlreadyTopFocused() {
        File confF = new File(tmpDir.getRoot(), "zorder_main.conf");
        File profF = new File(tmpDir.getRoot(), "zorder_prof.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 1440, 3200, 560);

        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int w1 = engine.openOrFocusWindow("com.example.one", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int w2 = engine.openOrFocusWindow("com.example.two", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        WindowSession s1Before = engine.getWindowSnapshot(w1);
        WindowSession s2Before = engine.getWindowSnapshot(w2);
        assertTrue("Second opened window must start above first", s2Before.zOrder > s1Before.zOrder);

        // Moving or resizing the already-top focused window keeps its zOrder unchanged
        assertTrue(engine.updateWindowFrame(w2, 100, 200, 1000, 1400, BridgeProtocol.PRES_FLOATING));
        WindowSession s2AfterMove = engine.getWindowSnapshot(w2);
        assertEquals("Top window zOrder must not churn on drag/resize release", s2Before.zOrder, s2AfterMove.zOrder);
        assertTrue(s2AfterMove.focused);

        // Moving or focusing the lower window promotes it strictly above w2
        assertTrue(engine.updateWindowFrame(w1, 120, 240, 1020, 1440, BridgeProtocol.PRES_FLOATING));
        WindowSession s1AfterFocus = engine.getWindowSnapshot(w1);
        assertTrue("Lower window must rise strictly above previous top window", s1AfterFocus.zOrder > s2AfterMove.zOrder);
        assertTrue(s1AfterFocus.focused);
        assertFalse(engine.getWindowSnapshot(w2).focused);
    }

    @Test
    public void testSnapFocusedWindowQuickSplitTwoAndSwitchNextWindow() {
        File confF = new File(tmpDir.getRoot(), "snap_main.conf");
        File profF = new File(tmpDir.getRoot(), "snap_prof.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 1920, 1200, 240);

        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        // Empty workspace returns false
        assertFalse(engine.snapFocusedWindow(true));
        assertFalse(engine.applyQuickSplitTwo());
        assertFalse(engine.switchNextWindow());

        // Single window: snap right, snap left, and quick split -> snaps left
        int w1 = engine.openOrFocusWindow("com.example.alpha", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        engine.attachWindowSurface(w1, new Object(), 1000, 700, 240, 60f);

        DisplayGeometryUtil.IntRect expectedLeft =
                WindowSnapGeometry.computeSnapFrame(BridgeProtocol.PRES_SNAP_LEFT, 1920, 1200, 240);
        DisplayGeometryUtil.IntRect expectedRight =
                WindowSnapGeometry.computeSnapFrame(BridgeProtocol.PRES_SNAP_RIGHT, 1920, 1200, 240);

        assertTrue(engine.snapFocusedWindow(false));
        WindowSession s1Right = engine.getWindowSnapshot(w1);
        assertEquals(BridgeProtocol.PRES_SNAP_RIGHT, s1Right.presentation);
        assertEquals(expectedRight.left, s1Right.frame.left);
        assertEquals(expectedRight.right, s1Right.frame.right);

        assertTrue(engine.snapFocusedWindow(true));
        WindowSession s1Left = engine.getWindowSnapshot(w1);
        assertEquals(BridgeProtocol.PRES_SNAP_LEFT, s1Left.presentation);
        assertEquals(expectedLeft.left, s1Left.frame.left);
        assertEquals(expectedLeft.right, s1Left.frame.right);

        // Single window applyQuickSplitTwo snaps to left half
        assertTrue(engine.snapFocusedWindow(false));
        assertTrue(engine.applyQuickSplitTwo());
        assertEquals(BridgeProtocol.PRES_SNAP_LEFT, engine.getWindowSnapshot(w1).presentation);

        // Two windows applyQuickSplitTwo places focused window left and second window right
        int w2 = engine.openOrFocusWindow("com.example.beta", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        engine.attachWindowSurface(w2, new Object(), 1000, 700, 240, 60f);
        assertTrue(engine.applyQuickSplitTwo());
        WindowSession s2Split = engine.getWindowSnapshot(w2);
        WindowSession s1Split = engine.getWindowSnapshot(w1);
        assertEquals(BridgeProtocol.PRES_SNAP_LEFT, s2Split.presentation);
        assertEquals(expectedLeft.left, s2Split.frame.left);
        assertEquals(BridgeProtocol.PRES_SNAP_RIGHT, s1Split.presentation);
        assertEquals(expectedRight.right, s1Split.frame.right);
        assertTrue(s2Split.focused);

        // switchNextWindow cycles focus cleanly between w2 -> w1 -> w2
        assertTrue(engine.switchNextWindow());
        assertTrue(engine.getWindowSnapshot(w1).focused);
        assertFalse(engine.getWindowSnapshot(w2).focused);
        assertTrue(engine.switchNextWindow());
        assertTrue(engine.getWindowSnapshot(w2).focused);
    }
}
