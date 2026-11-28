package io.github.lengsukq.opaldesk.daemon.platform;

/**
 * Pure JVM-testable coordinator for performing {@code VirtualDisplay.resize()} safely while the
 * display is briefly off-surface ({@code Display.STATE_OFF}) on Android 14+ (API 34+).
 *
 * <p>Why: On Android 14–17 Shell Transitions ({@code DeferredDisplayUpdater}), resizing a live
 * ({@code STATE_ON}) VirtualDisplay starts a {@code TRANSIT_CHANGE} ({@code FLAG_IS_DISPLAY})
 * transition and captures a {@code transition snapshot} surface. OEM {@code ScreenRotationAnimation}
 * implementations (notably HyperOS on Android 16 & 17) erroneously reparent that virtual display's
 * snapshot onto physical Display 0 at {@code (0, 0)} for ~130ms, causing a full-frame top-left
 * screen flash outside the app window even when all animation scales are 0.
 *
 * <p>Detaching the surface until {@code Display.STATE_OFF} propagates lets AOSP's
 * {@code Transition.collectVisibleChange()} mark {@code TRANSIT_FLAG_INVISIBLE} (or lets
 * {@code DeferredDisplayUpdater} apply immediately when {@code !mLastHasContent}), bypassing both
 * {@code ScreenshotFreezer} and {@code ScreenRotationAnimation} completely before reattaching the
 * exact same {@code Surface}.
 */
public final class VirtualDisplayResizeCoordinator {
    /** Android 14 (API 34) introduced Shell Transitions for display size changes. */
    public static final int MIN_OFF_SURFACE_RESIZE_SDK = 34;

    public static final long POLL_INTERVAL_MS = 4L;
    public static final long WAIT_STATE_OFF_TIMEOUT_MS = 64L;
    public static final long POST_STATE_OFF_SETTLE_MS = 20L;
    public static final long WAIT_RESIZE_APPLIED_TIMEOUT_MS = 64L;
    public static final long POST_RESIZE_SETTLE_MS = 20L;

    /** Thin testable adapter over {@code android.hardware.display.VirtualDisplay} + {@code Display}. */
    public interface ResizeAdapter {
        boolean hasValidAttachedSurface();

        boolean matchesTargetSpec(int width, int height, int dpi);

        void detachSurface();

        boolean isDisplayStateOff();

        void applyResize(int width, int height, int dpi);

        void reattachSurface();

        void sleepMs(long ms);

        long uptimeMs();
    }

    public static final class ResizeOutcome {
        public final boolean usedOffSurfacePath;
        public final boolean skippedAlreadyMatching;
        public final boolean observedStateOff;
        public final boolean observedTargetSpec;
        public final long stateOffElapsedMs;
        public final long resizeAppliedElapsedMs;

        ResizeOutcome(
                boolean usedOffSurfacePath,
                boolean skippedAlreadyMatching,
                boolean observedStateOff,
                boolean observedTargetSpec,
                long stateOffElapsedMs,
                long resizeAppliedElapsedMs) {
            this.usedOffSurfacePath = usedOffSurfacePath;
            this.skippedAlreadyMatching = skippedAlreadyMatching;
            this.observedStateOff = observedStateOff;
            this.observedTargetSpec = observedTargetSpec;
            this.stateOffElapsedMs = stateOffElapsedMs;
            this.resizeAppliedElapsedMs = resizeAppliedElapsedMs;
        }
    }

    private VirtualDisplayResizeCoordinator() {}

    public static boolean shouldUseOffSurfaceResize(int sdkInt, boolean hasValidAttachedSurface) {
        return sdkInt >= MIN_OFF_SURFACE_RESIZE_SDK && hasValidAttachedSurface;
    }

    /**
     * Executes a VirtualDisplay resize using the off-surface {@code STATE_OFF} path on API 34+
     * when a valid {@code Surface} is attached, always guaranteeing {@link ResizeAdapter#reattachSurface()}
     * in a {@code finally} block if {@link ResizeAdapter#detachSurface()} was invoked.
     */
    public static ResizeOutcome performResize(
            int sdkInt,
            int width,
            int height,
            int dpi,
            ResizeAdapter adapter) {
        if (adapter == null) {
            return new ResizeOutcome(false, false, false, false, 0L, 0L);
        }
        boolean hasSurface = adapter.hasValidAttachedSurface();
        if (hasSurface && adapter.matchesTargetSpec(width, height, dpi)) {
            return new ResizeOutcome(false, true, false, true, 0L, 0L);
        }
        if (!shouldUseOffSurfaceResize(sdkInt, hasSurface)) {
            adapter.applyResize(width, height, dpi);
            return new ResizeOutcome(false, false, false, false, 0L, 0L);
        }

        boolean detached = false;
        boolean observedOff = false;
        boolean observedSpec = false;
        long offElapsedMs = 0L;
        long resizeElapsedMs = 0L;
        try {
            long offStart = adapter.uptimeMs();
            adapter.detachSurface();
            detached = true;
            observedOff = waitUntil(adapter, adapter::isDisplayStateOff, WAIT_STATE_OFF_TIMEOUT_MS);
            offElapsedMs = Math.max(0L, adapter.uptimeMs() - offStart);
            if (POST_STATE_OFF_SETTLE_MS > 0L) {
                adapter.sleepMs(POST_STATE_OFF_SETTLE_MS);
            }

            long resizeStart = adapter.uptimeMs();
            adapter.applyResize(width, height, dpi);
            observedSpec = waitUntil(
                    adapter,
                    () -> adapter.matchesTargetSpec(width, height, dpi),
                    WAIT_RESIZE_APPLIED_TIMEOUT_MS);
            resizeElapsedMs = Math.max(0L, adapter.uptimeMs() - resizeStart);
            if (POST_RESIZE_SETTLE_MS > 0L) {
                adapter.sleepMs(POST_RESIZE_SETTLE_MS);
            }
        } finally {
            if (detached) {
                adapter.reattachSurface();
            }
        }
        return new ResizeOutcome(true, false, observedOff, observedSpec, offElapsedMs, resizeElapsedMs);
    }

    private interface Condition {
        boolean test();
    }

    private static boolean waitUntil(ResizeAdapter adapter, Condition cond, long timeoutMs) {
        if (cond.test()) {
            return true;
        }
        long deadline = adapter.uptimeMs() + Math.max(0L, timeoutMs);
        while (adapter.uptimeMs() < deadline) {
            long remaining = deadline - adapter.uptimeMs();
            long step = Math.min(POLL_INTERVAL_MS, Math.max(1L, remaining));
            adapter.sleepMs(step);
            if (cond.test()) {
                return true;
            }
        }
        return cond.test();
    }
}
