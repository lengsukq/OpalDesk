package io.github.lengsukq.opaldesk.daemon.platform;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VirtualDisplayResizeCoordinatorTest {

    private static final class FakeResizeAdapter implements VirtualDisplayResizeCoordinator.ResizeAdapter {
        final List<String> ops = new ArrayList<>();
        boolean hasSurface = true;
        boolean stateOff = false;
        int currentW = 1156;
        int currentH = 1346;
        int currentDpi = 560;
        long nowMs = 1000L;
        long offDelayMs = 8L;
        long resizeApplyDelayMs = 8L;
        long detachedAtMs = -1L;
        long resizedAtMs = -1L;
        int pendingW = -1;
        int pendingH = -1;
        int pendingDpi = -1;
        boolean throwOnResize = false;

        @Override
        public boolean hasValidAttachedSurface() {
            return hasSurface;
        }

        @Override
        public boolean matchesTargetSpec(int width, int height, int dpi) {
            if (resizedAtMs >= 0 && (nowMs - resizedAtMs) >= resizeApplyDelayMs) {
                currentW = pendingW;
                currentH = pendingH;
                currentDpi = pendingDpi;
            }
            return currentW == width && currentH == height && currentDpi == dpi;
        }

        @Override
        public void detachSurface() {
            ops.add("detach");
            detachedAtMs = nowMs;
        }

        @Override
        public boolean isDisplayStateOff() {
            if (detachedAtMs >= 0 && (nowMs - detachedAtMs) >= offDelayMs) {
                stateOff = true;
            }
            return stateOff;
        }

        @Override
        public void applyResize(int width, int height, int dpi) {
            ops.add("resize:" + width + "x" + height + "@" + dpi);
            if (throwOnResize) {
                throw new IllegalStateException("simulated resize failure");
            }
            pendingW = width;
            pendingH = height;
            pendingDpi = dpi;
            resizedAtMs = nowMs;
        }

        @Override
        public void reattachSurface() {
            ops.add("reattach");
            stateOff = false;
            detachedAtMs = -1L;
        }

        @Override
        public void sleepMs(long ms) {
            nowMs += ms;
        }

        @Override
        public long uptimeMs() {
            return nowMs;
        }
    }

    @Test
    public void api34PlusWithAttachedSurfaceDetachesResizesWhileOffAndReattaches() {
        FakeResizeAdapter adapter = new FakeResizeAdapter();
        VirtualDisplayResizeCoordinator.ResizeOutcome outcome =
                VirtualDisplayResizeCoordinator.performResize(36, 1024, 842, 560, adapter);

        assertTrue(outcome.usedOffSurfacePath);
        assertFalse(outcome.skippedAlreadyMatching);
        assertTrue(outcome.observedStateOff);
        assertTrue(outcome.observedTargetSpec);
        assertEquals(8L, outcome.stateOffElapsedMs);
        assertEquals(8L, outcome.resizeAppliedElapsedMs);
        assertEquals(Arrays.asList("detach", "resize:1024x842@560", "reattach"), adapter.ops);
        assertFalse(adapter.stateOff);
    }

    @Test
    public void api33AndBelowUsesDirectResizeWithoutSurfaceDetach() {
        FakeResizeAdapter adapter = new FakeResizeAdapter();
        VirtualDisplayResizeCoordinator.ResizeOutcome outcome =
                VirtualDisplayResizeCoordinator.performResize(33, 1024, 842, 560, adapter);

        assertFalse(outcome.usedOffSurfacePath);
        assertEquals(Arrays.asList("resize:1024x842@560"), adapter.ops);
    }

    @Test
    public void minimizedDisplayWithoutSurfaceUsesDirectResize() {
        FakeResizeAdapter adapter = new FakeResizeAdapter();
        adapter.hasSurface = false;
        VirtualDisplayResizeCoordinator.ResizeOutcome outcome =
                VirtualDisplayResizeCoordinator.performResize(37, 1024, 842, 560, adapter);

        assertFalse(outcome.usedOffSurfacePath);
        assertEquals(Arrays.asList("resize:1024x842@560"), adapter.ops);
    }

    @Test
    public void alreadyMatchingSpecSkipsNoOpDetachCycle() {
        FakeResizeAdapter adapter = new FakeResizeAdapter();
        VirtualDisplayResizeCoordinator.ResizeOutcome outcome =
                VirtualDisplayResizeCoordinator.performResize(36, 1156, 1346, 560, adapter);

        assertFalse(outcome.usedOffSurfacePath);
        assertTrue(outcome.skippedAlreadyMatching);
        assertTrue(adapter.ops.isEmpty());
    }

    @Test
    public void reattachesSurfaceEvenIfResizeThrows() {
        FakeResizeAdapter adapter = new FakeResizeAdapter();
        adapter.throwOnResize = true;
        try {
            VirtualDisplayResizeCoordinator.performResize(36, 1024, 842, 560, adapter);
            fail("Expected exception to propagate");
        } catch (IllegalStateException expected) {
            assertEquals("simulated resize failure", expected.getMessage());
        }
        assertEquals(Arrays.asList("detach", "resize:1024x842@560", "reattach"), adapter.ops);
        assertFalse(adapter.stateOff);
    }

    @Test
    public void boundsWaitWhenDisplayNeverReportsStateOff() {
        FakeResizeAdapter adapter = new FakeResizeAdapter();
        adapter.offDelayMs = 9999L;
        adapter.resizeApplyDelayMs = 9999L;
        VirtualDisplayResizeCoordinator.ResizeOutcome outcome =
                VirtualDisplayResizeCoordinator.performResize(36, 900, 1200, 480, adapter);

        assertTrue(outcome.usedOffSurfacePath);
        assertFalse(outcome.observedStateOff);
        assertFalse(outcome.observedTargetSpec);
        assertEquals(VirtualDisplayResizeCoordinator.WAIT_STATE_OFF_TIMEOUT_MS, outcome.stateOffElapsedMs);
        assertEquals(VirtualDisplayResizeCoordinator.WAIT_RESIZE_APPLIED_TIMEOUT_MS, outcome.resizeAppliedElapsedMs);
        assertEquals(Arrays.asList("detach", "resize:900x1200@480", "reattach"), adapter.ops);
    }
}
