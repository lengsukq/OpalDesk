package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.compositor.VirtualDisplayPool;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VirtualDisplayPoolTest {

    private static final class FakeDisplayDriver implements VirtualDisplayPool.DisplayDriver {
        final AtomicInteger nextDisplayId = new AtomicInteger(100);
        final List<Integer> releasedDisplayIds = new ArrayList<>();
        final List<String> surfaceEvents = new ArrayList<>();
        int hardOemLimit = Integer.MAX_VALUE;
        int activeCreated = 0;
        boolean failNextCreate = false;

        @Override
        public CreatedDisplay createVirtualDisplay(
                String name, int width, int height, int dpi, float fps, Object surface, boolean allowSecure) {
            if (failNextCreate) {
                failNextCreate = false;
                return null;
            }
            if (activeCreated >= hardOemLimit) {
                return null;
            }
            activeCreated++;
            int id = nextDisplayId.getAndIncrement();
            return new CreatedDisplay("vd-" + id, id, "unique-" + id);
        }

        @Override
        public void resizeVirtualDisplay(Object handle, int width, int height, int dpi) {}

        @Override
        public void setVirtualDisplaySurface(Object handle, Object surface) {
            surfaceEvents.add(handle + ":" + (surface == null ? "null" : surface.toString()));
        }

        @Override
        public void releaseVirtualDisplay(Object handle, int displayId) {
            activeCreated = Math.max(0, activeCreated - 1);
            releasedDisplayIds.add(displayId);
        }

        @Override
        public byte[] captureTaskSnapshotBytes(int taskId) {
            return ("snap-task-" + taskId).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static WindowSession makeSession(int id, int taskId, long lastActiveMs, String state, boolean focused) {
        WindowSession ws = new WindowSession(
                id,
                "com.example.app" + id,
                "",
                "App " + id,
                WindowProfile.DESKTOP,
                new IntRect(100, 100, 900, 700),
                800,
                560,
                200,
                lastActiveMs);
        ws.taskId = taskId;
        ws.state = state;
        ws.focused = focused;
        return ws;
    }

    @Test
    public void testLruEvictionCapturesSnapshotAndPrefersMinimizedThenOldestBackground() {
        FakeDisplayDriver driver = new FakeDisplayDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 3, false);

        WindowSession w1 = makeSession(1, 501, 1000L, BridgeProtocol.STATE_LIVE, false);
        WindowSession w2 = makeSession(2, 502, 2000L, BridgeProtocol.STATE_MINIMIZED, false);
        WindowSession w3 = makeSession(3, 503, 3000L, BridgeProtocol.STATE_LIVE, true);
        WindowSession w4 = makeSession(4, 504, 4000L, BridgeProtocol.STATE_HIBERNATED, false);
        List<WindowSession> all = Arrays.asList(w1, w2, w3, w4);

        assertNotNull(pool.acquireOrBind(w1, "s1", 800, 560, 200, 60f, all, null));
        assertNotNull(pool.acquireOrBind(w2, "s2", 800, 560, 200, 60f, all, null));
        assertNotNull(pool.acquireOrBind(w3, "s3", 800, 560, 200, 60f, all, null));
        assertEquals(3, pool.getActiveCount());

        // Acquiring for w4 should evict w2 FIRST because w2 is MINIMIZED (even though w1 has older timestamp)
        List<WindowSession> evicted = new ArrayList<>();
        List<byte[]> snapshots = new ArrayList<>();
        VirtualDisplayPool.Lease l4 = pool.acquireOrBind(w4, "s4", 800, 560, 200, 60f, all, (victim, snap) -> {
            evicted.add(victim);
            snapshots.add(snap);
        });
        assertNotNull(l4);
        assertEquals(3, pool.getActiveCount());
        assertEquals(1, evicted.size());
        assertEquals(2, evicted.get(0).windowId);
        assertArrayEquals("snap-task-502".getBytes(StandardCharsets.UTF_8), snapshots.get(0));
        assertNull(pool.getLease(2));

        // Next acquisition for w5 should evict w1 (oldest non-focused LIVE window)
        WindowSession w5 = makeSession(5, 505, 5000L, BridgeProtocol.STATE_HIBERNATED, false);
        List<WindowSession> all5 = Arrays.asList(w1, w2, w3, w4, w5);
        evicted.clear();
        assertNotNull(pool.acquireOrBind(w5, "s5", 800, 560, 200, 60f, all5, (victim, snap) -> evicted.add(victim)));
        assertEquals(1, evicted.size());
        assertEquals(1, evicted.get(0).windowId);
    }

    @Test
    public void testDynamicOemQuotaCeilingAdaptationWhenOsRejectsThirdVirtualDisplay() {
        FakeDisplayDriver driver = new FakeDisplayDriver();
        driver.hardOemLimit = 2; // Simulate strict OEM ROM allowing only 2 active VirtualDisplays per UID
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 6, false);

        WindowSession w1 = makeSession(1, 601, 100L, BridgeProtocol.STATE_LIVE, false);
        WindowSession w2 = makeSession(2, 602, 200L, BridgeProtocol.STATE_LIVE, true);
        WindowSession w3 = makeSession(3, 603, 300L, BridgeProtocol.STATE_HIBERNATED, false);
        List<WindowSession> all = Arrays.asList(w1, w2, w3);

        assertNotNull(pool.acquireOrBind(w1, "s1", 800, 600, 200, 60f, all, null));
        assertNotNull(pool.acquireOrBind(w2, "s2", 800, 600, 200, 60f, all, null));
        assertEquals(6, pool.getEffectiveMaxDisplays());

        // 3rd VirtualDisplay hits hardOemLimit=2 -> pool must dynamically lower ceiling to 2, evict w1, and succeed!
        List<Integer> evictedIds = new ArrayList<>();
        VirtualDisplayPool.Lease l3 = pool.acquireOrBind(
                w3, "s3", 800, 600, 200, 60f, all, (victim, snap) -> evictedIds.add(victim.windowId));
        assertNotNull("Expected retry after dynamic ceiling adaptation to succeed", l3);
        assertEquals(2, pool.getEffectiveMaxDisplays());
        assertEquals(2, pool.getActiveCount());
        assertTrue(evictedIds.contains(1));
    }

    @Test
    public void testRecreateLeaseReplacesDisplayAndRollsBackCleanlyOnFailure() {
        FakeDisplayDriver driver = new FakeDisplayDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);

        WindowSession w1 = makeSession(1, 701, 100L, BridgeProtocol.STATE_LIVE, true);
        List<WindowSession> all = Arrays.asList(w1);

        VirtualDisplayPool.Lease initial = pool.acquireOrBind(w1, "surf-1", 800, 600, 200, 60f, all, null);
        assertNotNull(initial);
        assertEquals(100, initial.displayId);

        // Failure case: detach old surface, fail to create replacement, roll back by reattaching old surface
        driver.surfaceEvents.clear();
        driver.failNextCreate = true;
        assertNull(pool.recreateLease(w1, "surf-1", 800, 600, 200, 60f));
        assertEquals(100, pool.getLease(1).displayId);
        assertEquals(Arrays.asList("vd-100:null", "vd-100:surf-1"), driver.surfaceEvents);
        assertTrue(driver.releasedDisplayIds.isEmpty());

        // Success case: detach old surface, create new display, update active lease, keep old display alive until caller releases
        driver.surfaceEvents.clear();
        VirtualDisplayPool.RebindResult rebind = pool.recreateLease(w1, "surf-1", 800, 600, 200, 60f);
        assertNotNull(rebind);
        assertEquals(100, rebind.oldLease.displayId);
        assertEquals(101, rebind.newLease.displayId);
        assertEquals(101, pool.getLease(1).displayId);
        assertEquals(Arrays.asList("vd-100:null"), driver.surfaceEvents);
        assertTrue("Old display should remain alive until task migration finishes", driver.releasedDisplayIds.isEmpty());

        pool.finishReleaseOldLease(rebind.oldLease);
        assertEquals(Arrays.asList(100), driver.releasedDisplayIds);
    }
}
