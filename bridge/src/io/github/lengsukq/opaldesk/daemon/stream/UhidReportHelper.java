package io.github.lengsukq.opaldesk.daemon.stream;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * HID report builder, smooth touch-scroll synthesizer, {@code /dev/uhid} FD I/O, and
 * {@code InputManager} port-to-display association helper for {@link UhidInputDevice}.
 */
final class UhidReportHelper {
    private static final int EIO_ERRNO = 5;
    private static final int SCROLL_INTERPOLATION_STEPS = 3;
    private static final long SCROLL_STEP_SLEEP_MS = 4L;
    private static final long SCROLL_IDLE_LIFT_MS = 95L;
    private static final float SCROLL_REANCHOR_DISTANCE_PX = 48f;
    private static final float SCROLL_VIEWPORT_MARGIN_PX = 20f;

    private static final Object ASSOC_CACHE_LOCK = new Object();
    private static volatile boolean sAssocMethodsResolved;
    private static volatile Method sImAddPort;
    private static volatile Method sImAddLegacy;
    private static volatile Method sImRemPort;
    private static volatile Method sImRemLegacy;
    private static volatile Class<?> sIImCachedClass;
    private static volatile Method sIImAddPort;
    private static volatile Method sIImAddLegacy;
    private static volatile Method sIImRemPort;
    private static volatile Method sIImRemLegacy;

    private UhidReportHelper() {}

    static byte[] buildKernelReplyForTouchEvent(byte[] buf, int len) {
        if (len < 4) return null;
        int evType = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        if (evType == UhidProtocol.UHID_GET_REPORT) {
            UhidProtocol.GetReportRequest req = UhidProtocol.parseGetReportRequest(buf, len);
            if (req == null) return null;
            if (req.rtype == UhidProtocol.UHID_FEATURE_REPORT
                    && req.rnum == UhidProtocol.REPORT_ID_TOUCH_FEATURE) {
                return UhidProtocol.buildGetReportReplyEvent(
                        req.id, 0,
                        new byte[]{(byte) UhidProtocol.REPORT_ID_TOUCH_FEATURE, (byte) UhidProtocol.MAX_TOUCH_CONTACTS});
            }
            return UhidProtocol.buildGetReportReplyEvent(req.id, EIO_ERRNO, null);
        }
        if (evType == UhidProtocol.UHID_SET_REPORT) {
            int reqId = UhidProtocol.parseSetReportRequestId(buf, len);
            if (reqId >= 0) return UhidProtocol.buildSetReportReplyEvent(reqId, 0);
        }
        return null;
    }

    static boolean hasActiveTouchTip(byte[] report) {
        if (report == null || report.length < UhidProtocol.TOUCH_REPORT_SIZE) return false;
        int count = Math.min(UhidProtocol.MAX_TOUCH_CONTACTS, report[UhidProtocol.TOUCH_REPORT_SIZE - 1] & 0xFF);
        for (int i = 0; i < count; i++) {
            if (report[1 + (i * UhidProtocol.TOUCH_CONTACT_BYTES)] != 0) return true;
        }
        return false;
    }

    static byte[] buildTouchReleaseReport(byte[] prevTouch) {
        byte[] releaseReport = Arrays.copyOf(prevTouch, prevTouch.length);
        int prevCount = Math.min(UhidProtocol.MAX_TOUCH_CONTACTS, releaseReport[UhidProtocol.TOUCH_REPORT_SIZE - 1] & 0xFF);
        for (int i = 0; i < prevCount; i++) {
            releaseReport[1 + (i * UhidProtocol.TOUCH_CONTACT_BYTES)] = 0x00;
        }
        return releaseReport;
    }

    static FileDescriptor openUhidFd() throws ErrnoException {
        try {
            return Os.open(UhidInputDevice.UHID_DEVICE_PATH, OsConstants.O_RDWR | OsConstants.O_NONBLOCK, 0);
        } catch (ErrnoException e) {
            return Os.open(UhidInputDevice.UHID_DEVICE_PATH, OsConstants.O_RDWR, 0);
        }
    }

    static void writeAll(FileDescriptor fd, byte[] data) throws Exception {
        int off = 0, rem = data.length, retries = 0;
        while (rem > 0) {
            try {
                int n = Os.write(fd, data, off, rem);
                if (n <= 0) throw new IOException("Short write to /dev/uhid (wrote " + n + ")");
                off += n;
                rem -= n;
            } catch (ErrnoException ee) {
                if (ee.errno == OsConstants.EINTR && retries++ < 4) continue;
                if (ee.errno == OsConstants.EAGAIN && retries++ < 5) {
                    SystemClock.sleep(2L);
                    continue;
                }
                throw ee;
            }
        }
    }

    static void destroyAndCloseFd(FileDescriptor fd) {
        if (fd == null) return;
        try {
            writeAll(fd, UhidProtocol.buildDestroyEvent());
        } catch (Exception e) {
            System.err.println("OPALDESK_UHID_DESTROY_WARN: " + e.getMessage());
        } finally {
            try { Os.close(fd); } catch (ErrnoException e) {
                System.err.println("OPALDESK_UHID_CLOSE_WARN: " + e.getMessage());
            }
        }
    }

    static final class PortAssociationBinder {
        private final InputManager inputManager;
        private final Object iInputManager;
        private volatile String lastAssocError = "";

        PortAssociationBinder(InputManager inputManager, Object iInputManager) {
            this.inputManager = inputManager;
            this.iInputManager = iInputManager;
        }

        String getLastAssocError() { return lastAssocError; }

        boolean bindPortAssociation(String phys, String uniqueId) {
            if (phys == null || uniqueId == null || uniqueId.isEmpty()) return false;
            ensureAssocMethodsCached();
            if (invokeCached(inputManager, sImAddPort, phys, uniqueId)) return true;
            if (invokeCached(inputManager, sImAddLegacy, phys, uniqueId)) return true;
            if (invokeCached(iInputManager, sIImAddPort, phys, uniqueId)) return true;
            return invokeCached(iInputManager, sIImAddLegacy, phys, uniqueId);
        }

        void unbindPortAssociation(String phys) {
            if (phys == null) return;
            ensureAssocMethodsCached();
            if (invokeCached(inputManager, sImRemPort, phys)) return;
            if (invokeCached(inputManager, sImRemLegacy, phys)) return;
            if (invokeCached(iInputManager, sIImRemPort, phys)) return;
            invokeCached(iInputManager, sIImRemLegacy, phys);
        }

        private void ensureAssocMethodsCached() {
            Class<?> iimCls = (iInputManager != null) ? iInputManager.getClass() : null;
            if (sAssocMethodsResolved && (iimCls == null || iimCls == sIImCachedClass)) return;
            synchronized (ASSOC_CACHE_LOCK) {
                if (!sAssocMethodsResolved) {
                    sImAddPort = findMethodOrNull(InputManager.class, "addUniqueIdAssociationByPort", String.class, String.class);
                    sImAddLegacy = findMethodOrNull(InputManager.class, "addUniqueIdAssociation", String.class, String.class);
                    sImRemPort = findMethodOrNull(InputManager.class, "removeUniqueIdAssociationByPort", String.class);
                    sImRemLegacy = findMethodOrNull(InputManager.class, "removeUniqueIdAssociation", String.class);
                    sAssocMethodsResolved = true;
                }
                if (iimCls != null && iimCls != sIImCachedClass) {
                    sIImAddPort = findMethodOrNull(iimCls, "addUniqueIdAssociationByPort", String.class, String.class);
                    sIImAddLegacy = findMethodOrNull(iimCls, "addUniqueIdAssociation", String.class, String.class);
                    sIImRemPort = findMethodOrNull(iimCls, "removeUniqueIdAssociationByPort", String.class);
                    sIImRemLegacy = findMethodOrNull(iimCls, "removeUniqueIdAssociation", String.class);
                    sIImCachedClass = iimCls;
                }
            }
        }

        private static Method findMethodOrNull(Class<?> cls, String name, Class<?>... params) {
            try { return cls.getMethod(name, params); } catch (NoSuchMethodException ignored) { return null; }
        }

        private boolean invokeCached(Object target, Method method, Object... args) {
            if (target == null || method == null) return false;
            try {
                method.invoke(target, args);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                String detail = UhidProtocol.unwrapThrowableMessage(e);
                lastAssocError = detail;
                System.err.println("OPALDESK_UHID_ASSOC_WARN method=" + method.getName() + ": " + detail);
                return false;
            }
        }
    }

    static final class TouchScrollSynthesizer {
        interface ContactEmitter {
            boolean emit(float px, float py, int dispW, int dispH, boolean tipDown);
        }

        private final int displayId;
        private final BooleanSupplier isClosed;
        private final BooleanSupplier isUserTouchActive;
        private final ContactEmitter emitter;
        private final Object scrollLock = new Object();

        private ScheduledExecutorService scrollExecutor;
        private ScheduledFuture<?> pendingScrollLift;
        private boolean scrollActive = false;
        private int scrollGeneration = 0;
        private float scrollOriginX = -1f, scrollOriginY = -1f;
        private float scrollCurX = -1f, scrollCurY = -1f;
        private int scrollDispW = 2, scrollDispH = 2;

        TouchScrollSynthesizer(
                int displayId,
                BooleanSupplier isClosed,
                BooleanSupplier isUserTouchActive,
                ContactEmitter emitter) {
            this.displayId = displayId;
            this.isClosed = isClosed;
            this.isUserTouchActive = isUserTouchActive;
            this.emitter = emitter;
        }

        boolean enqueue(float anchorX, float anchorY, float hScroll, float vScroll, int safeW, int safeH) {
            if (isUserTouchActive.getAsBoolean()) return true;
            int[] delta = UhidProtocol.computeUhidTouchScrollDeltaPx(hScroll, vScroll, safeW, safeH);
            int dx = delta[0], dy = delta[1];
            if (dx == 0 && dy == 0) return true;
            float clampedX = clampScrollCoord(anchorX, safeW);
            float clampedY = clampScrollCoord(anchorY, safeH);
            ScheduledExecutorService exec;
            int expectedGen;
            synchronized (scrollLock) {
                if (isClosed.getAsBoolean()) return false;
                exec = ensureScrollExecutorLocked();
                expectedGen = scrollGeneration;
            }
            try {
                exec.execute(() -> performStep(clampedX, clampedY, dx, dy, safeW, safeH, expectedGen));
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        void cancel(boolean liftContactIfActive) {
            boolean wasActive;
            float liftX, liftY;
            int liftW, liftH;
            synchronized (scrollLock) {
                scrollGeneration++;
                if (pendingScrollLift != null) {
                    pendingScrollLift.cancel(false);
                    pendingScrollLift = null;
                }
                wasActive = scrollActive;
                scrollActive = false;
                liftX = scrollCurX; liftY = scrollCurY;
                liftW = scrollDispW; liftH = scrollDispH;
            }
            if (liftContactIfActive && wasActive && !isClosed.getAsBoolean()) {
                emitter.emit(liftX, liftY, liftW, liftH, false);
            }
        }

        ScheduledExecutorService detachAndStopLocked() {
            synchronized (scrollLock) {
                scrollGeneration++;
                if (pendingScrollLift != null) {
                    pendingScrollLift.cancel(false);
                    pendingScrollLift = null;
                }
                scrollActive = false;
                ScheduledExecutorService exec = scrollExecutor;
                scrollExecutor = null;
                return exec;
            }
        }

        private ScheduledExecutorService ensureScrollExecutorLocked() {
            if (scrollExecutor == null || scrollExecutor.isShutdown()) {
                scrollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "OpalUhidScroll-" + displayId);
                    t.setDaemon(true);
                    return t;
                });
            }
            return scrollExecutor;
        }

        private void performStep(float anchorX, float anchorY, int dx, int dy, int safeW, int safeH, int expectedGen) {
            float startX, startY, endX, endY;
            boolean needLiftBeforeAnchor = false, needTouchDown = false;
            float prevLiftX = 0f, prevLiftY = 0f;
            int prevLiftW = safeW, prevLiftH = safeH;

            synchronized (scrollLock) {
                if (isClosed.getAsBoolean() || isUserTouchActive.getAsBoolean() || expectedGen != scrollGeneration) return;
                if (pendingScrollLift != null) {
                    pendingScrollLift.cancel(false);
                    pendingScrollLift = null;
                }
                boolean sameViewport = (scrollDispW == safeW && scrollDispH == safeH);
                boolean cursorMoved = !scrollActive
                        || Math.hypot(anchorX - scrollOriginX, anchorY - scrollOriginY) > SCROLL_REANCHOR_DISTANCE_PX;
                float proposedEndX = scrollCurX + dx;
                float proposedEndY = scrollCurY + dy;
                float minX = Math.min(SCROLL_VIEWPORT_MARGIN_PX, safeW * 0.15f);
                float maxX = Math.max(minX + 1f, safeW - 1f - minX);
                float minY = Math.min(SCROLL_VIEWPORT_MARGIN_PX, safeH * 0.15f);
                float maxY = Math.max(minY + 1f, safeH - 1f - minY);
                boolean hitsEdge = scrollActive
                        && ((dx < 0 && proposedEndX < minX) || (dx > 0 && proposedEndX > maxX)
                                || (dy < 0 && proposedEndY < minY) || (dy > 0 && proposedEndY > maxY));
                if (!scrollActive || !sameViewport || cursorMoved || hitsEdge) {
                    if (scrollActive) {
                        needLiftBeforeAnchor = true;
                        prevLiftX = scrollCurX; prevLiftY = scrollCurY;
                        prevLiftW = scrollDispW; prevLiftH = scrollDispH;
                    }
                    scrollOriginX = anchorX; scrollOriginY = anchorY;
                    scrollCurX = anchorX; scrollCurY = anchorY;
                    scrollDispW = safeW; scrollDispH = safeH;
                    scrollActive = true;
                    needTouchDown = true;
                }
                startX = scrollCurX; startY = scrollCurY;
                endX = Math.max(minX, Math.min(maxX, startX + dx));
                endY = Math.max(minY, Math.min(maxY, startY + dy));
                scrollCurX = endX; scrollCurY = endY;
            }

            if (needLiftBeforeAnchor) {
                emitter.emit(prevLiftX, prevLiftY, prevLiftW, prevLiftH, false);
                SystemClock.sleep(SCROLL_STEP_SLEEP_MS);
            }
            if (isClosed.getAsBoolean() || isUserTouchActive.getAsBoolean() || isStale(expectedGen)) return;
            if (needTouchDown && !emitter.emit(startX, startY, safeW, safeH, true)) {
                cancel(false);
                return;
            }
            for (int step = 1; step <= SCROLL_INTERPOLATION_STEPS; step++) {
                if (isClosed.getAsBoolean() || isUserTouchActive.getAsBoolean() || isStale(expectedGen)) return;
                SystemClock.sleep(SCROLL_STEP_SLEEP_MS);
                if (isClosed.getAsBoolean() || isUserTouchActive.getAsBoolean() || isStale(expectedGen)) return;
                float t = (float) step / (float) SCROLL_INTERPOLATION_STEPS;
                if (!emitter.emit(startX + (endX - startX) * t, startY + (endY - startY) * t, safeW, safeH, true)) {
                    cancel(false);
                    return;
                }
            }
            synchronized (scrollLock) {
                if (isClosed.getAsBoolean() || isUserTouchActive.getAsBoolean() || expectedGen != scrollGeneration || !scrollActive) return;
                if (scrollExecutor != null && !scrollExecutor.isShutdown()) {
                    int liftGen = expectedGen;
                    pendingScrollLift = scrollExecutor.schedule(
                            () -> liftIfCurrent(liftGen), SCROLL_IDLE_LIFT_MS, TimeUnit.MILLISECONDS);
                }
            }
        }

        private void liftIfCurrent(int expectedGen) {
            float liftX, liftY;
            int liftW, liftH;
            synchronized (scrollLock) {
                if (isClosed.getAsBoolean() || expectedGen != scrollGeneration || !scrollActive) return;
                pendingScrollLift = null;
                scrollActive = false;
                liftX = scrollCurX; liftY = scrollCurY;
                liftW = scrollDispW; liftH = scrollDispH;
            }
            emitter.emit(liftX, liftY, liftW, liftH, false);
        }

        private boolean isStale(int expectedGen) {
            synchronized (scrollLock) { return expectedGen != scrollGeneration; }
        }

        private static float clampScrollCoord(float coord, int dim) {
            if (!Float.isFinite(coord)) return dim * 0.5f;
            float margin = Math.min(SCROLL_VIEWPORT_MARGIN_PX, dim * 0.15f);
            float maxCoord = Math.max(margin + 1f, dim - 1f - margin);
            return Math.max(margin, Math.min(maxCoord, coord));
        }
    }
}
