package io.github.lengsukq.opaldesk.daemon.stream;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import io.github.lengsukq.opaldesk.daemon.input.MouseRoutingPolicy;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java Linux /dev/uhid cursorless virtual HID touchscreen and keyboard driver.
 * Bypasses OEM INJECT_EVENTS restrictions (such as HyperOS SecurityException) by creating genuine
 * kernel HID devices via UHID_CREATE2 / UHID_INPUT2 / UHID_DESTROY and binding all physical ports
 * to the target VirtualDisplay via InputManager.addUniqueIdAssociationByPort.
 *
 * <p>Intentionally NEVER registers an {@code InputDeviceClass::CURSOR} HID mouse on managed
 * {@code VirtualDisplay}s: the outer compositor host already owns the sole visible cursor (either
 * the hardware USB/Bluetooth mouse cursor, OpalDesk's virtual trackpad cursor, or the remote PC
 * cursor). Creating a kernel HID mouse inside an embedded {@code OpalWin-*} or cast display would
 * cause Android's {@code PointerChoreographer} to paint a second, drifted system mouse cursor into
 * the framebuffer. Instead, primary clicks/drags and mouse-wheel scroll execute pixel-accurately
 * through the 10-contact absolute HID Digitizer Touch Screen ({@code touchFd}), while right-click
 * / side-back and text/shortcut keys route through {@code kbdFd}.
 *
 * Delegates all binary struct / HID descriptor / key mapping encoding to {@link UhidProtocol}.
 */
public final class UhidInputDevice implements Closeable {
    public static final String UHID_DEVICE_PATH = "/dev/uhid";
    private static final int HID_KEY_V = 0x19;
    private static final int EIO_ERRNO = 5;
    private static final int SCROLL_INTERPOLATION_STEPS = 3;
    private static final long SCROLL_STEP_SLEEP_MS = 4L;
    private static final long SCROLL_IDLE_LIFT_MS = 95L;
    private static final float SCROLL_REANCHOR_DISTANCE_PX = 48f;
    private static final float SCROLL_VIEWPORT_MARGIN_PX = 20f;

    // Cached reflection bindings for port-to-display association across InputManager / IInputManager
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

    private final Object ioLock = new Object();
    private final Object touchWriteLock = new Object();
    private final Object scrollLock = new Object();
    private final int displayId;
    private final String vdUniqueId;
    private final boolean keyboardOnly;
    private final String touchPhys;
    private final String kbdPhys;
    private final InputManager inputManager;
    private final Object iInputManager;

    private FileDescriptor touchFd;
    private FileDescriptor kbdFd;
    private Thread touchEventThread;
    private ScheduledExecutorService scrollExecutor;
    private ScheduledFuture<?> pendingScrollLift;
    private boolean scrollActive = false;
    private int scrollGeneration = 0;
    private float scrollOriginX = -1f;
    private float scrollOriginY = -1f;
    private float scrollCurX = -1f;
    private float scrollCurY = -1f;
    private int scrollDispW = 2;
    private int scrollDispH = 2;
    private volatile boolean userTouchActive = false;
    private volatile boolean closed = false;
    private boolean portAssociated = false;
    private volatile String lastAssocError = "";

    private byte[] lastTouchReport = null;
    private boolean activeMouseToTouch = false;
    private int activeModifiers = 0;
    private final LinkedHashSet<Integer> pressedHidKeys = new LinkedHashSet<>();

    private UhidInputDevice(
            int displayId,
            String vdUniqueId,
            boolean keyboardOnly,
            InputManager inputManager,
            Object iInputManager) {
        this.displayId = displayId;
        this.vdUniqueId = vdUniqueId;
        this.keyboardOnly = keyboardOnly;
        this.touchPhys = UhidProtocol.touchPhysForDisplay(displayId);
        this.kbdPhys = UhidProtocol.keyboardPhysForDisplay(displayId);
        this.inputManager = inputManager;
        this.iInputManager = iInputManager;
    }

    /**
     * Opens /dev/uhid for a cursorless virtual multi-touch screen and keyboard, registers them
     * via UHID_CREATE2, and binds all physical port names to {@code vdUniqueId}.
     * On any failure, all opened FDs and associations are rolled back cleanly with zero leaks.
     */
    public static UhidInputDevice open(
            int displayId,
            String vdUniqueId,
            InputManager inputManager,
            Object iInputManager) throws IOException {
        return openInternal(displayId, vdUniqueId, false, inputManager, iInputManager);
    }

    /**
     * Opens a lightweight keyboard-only /dev/uhid HID keyboard bound to {@code vdUniqueId} so
     * physical keyboard typing delivers authentic positive-{@code deviceId} kernel key events to
     * Android Chinese IMEs without creating extra virtual touch or mouse pointers.
     */
    public static UhidInputDevice openKeyboardOnly(
            int displayId,
            String vdUniqueId,
            InputManager inputManager,
            Object iInputManager) throws IOException {
        return openInternal(displayId, vdUniqueId, true, inputManager, iInputManager);
    }

    private static UhidInputDevice openInternal(
            int displayId,
            String vdUniqueId,
            boolean keyboardOnly,
            InputManager inputManager,
            Object iInputManager) throws IOException {
        UhidInputDevice dev = new UhidInputDevice(displayId, vdUniqueId, keyboardOnly, inputManager, iInputManager);
        try {
            dev.init();
            return dev;
        } catch (Throwable t) {
            dev.close();
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("Failed to initialize UHID device for display " + displayId + ": " + t.getMessage(), t);
        }
    }

    private void init() throws Exception {
        synchronized (ioLock) {
            boolean needAssoc = UhidProtocol.isDisplayAssociationRequired(displayId, vdUniqueId);
            boolean touchBound = false;
            boolean kbdBound = false;
            if (needAssoc) {
                // Pre-register before UHID_CREATE2 so EventHub binds the display during initial HID probe.
                if (!keyboardOnly) {
                    touchBound = bindPortAssociation(touchPhys, vdUniqueId);
                }
                kbdBound = bindPortAssociation(kbdPhys, vdUniqueId);
                portAssociated = touchBound || kbdBound;
            }

            if (!keyboardOnly) {
                touchFd = openUhidFd();
                startTouchEventLoopLocked(touchFd);
                byte[] createTouch = UhidProtocol.buildCreate2Event(
                        "OpalDesk Virtual Touch " + displayId,
                        touchPhys,
                        "opaldesk-touch-" + displayId,
                        UhidProtocol.BUS_VIRTUAL,
                        UhidProtocol.VENDOR_ID,
                        UhidProtocol.PRODUCT_ID_TOUCH,
                        UhidProtocol.DEVICE_VERSION,
                        0,
                        UhidProtocol.getTouchReportDescriptor());
                writeTouchEvent(createTouch);
            }

            kbdFd = openUhidFd();
            byte[] createKbd = UhidProtocol.buildCreate2Event(
                    "OpalDesk Virtual Keyboard " + displayId,
                    kbdPhys,
                    "opaldesk-kbd-" + displayId,
                    UhidProtocol.BUS_VIRTUAL,
                    UhidProtocol.VENDOR_ID,
                    UhidProtocol.PRODUCT_ID_KEYBOARD,
                    UhidProtocol.DEVICE_VERSION,
                    0,
                    UhidProtocol.getKeyboardReportDescriptor());
            writeAll(kbdFd, createKbd);

            if (needAssoc) {
                // Re-apply after UHID_CREATE2 for ROMs whose InputReader only attaches associations to live ports.
                if (!keyboardOnly) {
                    touchBound |= bindPortAssociation(touchPhys, vdUniqueId);
                }
                kbdBound |= bindPortAssociation(kbdPhys, vdUniqueId);
                portAssociated = touchBound || kbdBound;
                boolean requiredOk = keyboardOnly ? kbdBound : touchBound;
                if (!requiredOk) {
                    String err = lastAssocError;
                    throw new IOException("UHID port-to-display association rejected for display " + displayId
                            + (err.isEmpty() ? "" : (" (" + err + ")")));
                }
            }
            System.out.println((keyboardOnly ? "OPALDESK_UHID_KBD_CREATED displayId=" : "OPALDESK_UHID_CREATED displayId=")
                    + displayId
                    + (keyboardOnly ? "" : (" touchPhys=" + touchPhys))
                    + " kbdPhys=" + kbdPhys
                    + " vdUniqueId=" + (vdUniqueId != null ? vdUniqueId : ""));
        }
    }

    private void startTouchEventLoopLocked(FileDescriptor fd) {
        Thread t = new Thread(() -> runTouchEventLoop(fd), "OpalUhidEvt-" + displayId);
        t.setDaemon(true);
        touchEventThread = t;
        t.start();
    }

    private void runTouchEventLoop(FileDescriptor fd) {
        byte[] buf = new byte[UhidProtocol.UHID_EVENT_SIZE];
        StructPollfd pollFd = new StructPollfd();
        pollFd.fd = fd;
        pollFd.events = (short) OsConstants.POLLIN;
        StructPollfd[] pfds = new StructPollfd[]{pollFd};

        while (!closed) {
            try {
                pollFd.revents = 0;
                int ready = Os.poll(pfds, 200);
                if (closed) break;
                int rev = pollFd.revents;
                if ((rev & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) {
                    break;
                }
                if (ready <= 0 || (rev & OsConstants.POLLIN) == 0) {
                    continue;
                }
                while (!closed) {
                    int n;
                    try {
                        n = Os.read(fd, buf, 0, buf.length);
                    } catch (ErrnoException ee) {
                        if (ee.errno == OsConstants.EAGAIN) break;
                        if (ee.errno == OsConstants.EINTR) continue;
                        return;
                    }
                    if (n <= 0) break;
                    handleTouchKernelEvent(buf, n);
                }
            } catch (ErrnoException ee) {
                if (ee.errno == OsConstants.EINTR) continue;
                break;
            } catch (Throwable ignored) {
                break;
            }
        }
    }

    private void handleTouchKernelEvent(byte[] buf, int len) {
        if (len < 4 || closed) return;
        int evType = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        if (evType == UhidProtocol.UHID_GET_REPORT) {
            UhidProtocol.GetReportRequest req = UhidProtocol.parseGetReportRequest(buf, len);
            if (req == null) return;
            byte[] reply;
            if (req.rtype == UhidProtocol.UHID_FEATURE_REPORT
                    && req.rnum == UhidProtocol.REPORT_ID_TOUCH_FEATURE) {
                reply = UhidProtocol.buildGetReportReplyEvent(
                        req.id,
                        0,
                        new byte[]{
                                (byte) UhidProtocol.REPORT_ID_TOUCH_FEATURE,
                                (byte) UhidProtocol.MAX_TOUCH_CONTACTS
                        });
            } else {
                reply = UhidProtocol.buildGetReportReplyEvent(req.id, EIO_ERRNO, null);
            }
            writeTouchEventQuietly(reply);
        } else if (evType == UhidProtocol.UHID_SET_REPORT) {
            int reqId = UhidProtocol.parseSetReportRequestId(buf, len);
            if (reqId >= 0) {
                writeTouchEventQuietly(UhidProtocol.buildSetReportReplyEvent(reqId, 0));
            }
        }
    }

    private void writeTouchEvent(byte[] uhidEvent) throws Exception {
        synchronized (touchWriteLock) {
            if (closed || touchFd == null) {
                throw new IOException("UHID touch device closed");
            }
            writeAll(touchFd, uhidEvent);
        }
    }

    private void writeTouchEventQuietly(byte[] uhidEvent) {
        try {
            synchronized (touchWriteLock) {
                if (!closed && touchFd != null) {
                    writeAll(touchFd, uhidEvent);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public int getDisplayId() {
        return displayId;
    }

    public String getVdUniqueId() {
        return vdUniqueId;
    }

    public boolean isOpen() {
        return keyboardOnly
                ? (!closed && kbdFd != null)
                : (!closed && touchFd != null && kbdFd != null);
    }

    public boolean isKeyboardOnly() {
        return keyboardOnly;
    }

    public boolean injectInputEvent(InputEvent ev, int displayW, int displayH) {
        if (ev == null || closed) return false;
        if (ev instanceof MotionEvent) {
            return handleMotionEvent((MotionEvent) ev, displayW, displayH);
        }
        if (ev instanceof KeyEvent) {
            return handleKeyEvent((KeyEvent) ev);
        }
        return false;
    }

    private boolean handleMotionEvent(MotionEvent me, int displayW, int displayH) {
        int safeW = Math.max(2, displayW);
        int safeH = Math.max(2, displayH);
        int action = me.getActionMasked();
        int count = me.getPointerCount();
        if (count <= 0) return false;

        if (MouseRoutingPolicy.isPassiveHoverAction(action)) {
            // Passive hover never spawns or drives a kernel mouse cursor on VirtualDisplays.
            return true;
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            return enqueueTouchScroll(
                    me.getX(0),
                    me.getY(0),
                    me.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    me.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    safeW,
                    safeH);
        }

        boolean isMouse = (me.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || me.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
        boolean isTouchAction = action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL;

        boolean routeToTouch;
        if (!isMouse && isTouchAction) {
            routeToTouch = true;
            activeMouseToTouch = false;
        } else if (isMouse && isTouchAction) {
            routeToTouch = MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                    action, true, me.getButtonState(), activeMouseToTouch);
            if (routeToTouch) {
                activeMouseToTouch = (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activeMouseToTouch = false;
            }
        } else {
            routeToTouch = false;
        }

        if (routeToTouch) {
            cancelActiveTouchScroll(true);
            boolean allUp = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL);
            userTouchActive = !allUp;
            int n = Math.min(UhidProtocol.MAX_TOUCH_CONTACTS, count);
            int upIdx = (action == MotionEvent.ACTION_POINTER_UP) ? me.getActionIndex() : -1;
            int[] contactIds = new int[n];
            boolean[] tipDowns = new boolean[n];
            int[] absXs = new int[n];
            int[] absYs = new int[n];
            for (int i = 0; i < n; i++) {
                contactIds[i] = me.getPointerId(i) & 0xFF;
                tipDowns[i] = !allUp && (i != upIdx);
                absXs[i] = UhidProtocol.toAbsoluteAxis(me.getX(i), safeW);
                absYs[i] = UhidProtocol.toAbsoluteAxis(me.getY(i), safeH);
            }
            return sendTouch(n, contactIds, tipDowns, absXs, absYs, action != MotionEvent.ACTION_MOVE);
        }

        // Secondary / back mouse button in cursorless UHID mode maps cleanly to KEYCODE_BACK
        // without opening a cursor-spawning HID mouse device.
        int buttons = me.getButtonState();
        if (action == MotionEvent.ACTION_DOWN
                && (buttons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_BACK)) != 0) {
            boolean down = sendKey(true, KeyEvent.KEYCODE_BACK, 0);
            boolean up = sendKey(false, KeyEvent.KEYCODE_BACK, 0);
            return down || up;
        }
        return true;
    }

    private boolean enqueueTouchScroll(
            float anchorX,
            float anchorY,
            float hScroll,
            float vScroll,
            int safeW,
            int safeH) {
        if (closed || keyboardOnly || touchFd == null) return false;
        if (userTouchActive) return true;
        int[] delta = UhidProtocol.computeUhidTouchScrollDeltaPx(hScroll, vScroll, safeW, safeH);
        final int dx = delta[0];
        final int dy = delta[1];
        if (dx == 0 && dy == 0) return true;

        final float clampedX = clampScrollCoord(anchorX, safeW);
        final float clampedY = clampScrollCoord(anchorY, safeH);
        final ScheduledExecutorService exec;
        final int expectedGen;
        synchronized (scrollLock) {
            if (closed) return false;
            exec = ensureScrollExecutorLocked();
            expectedGen = scrollGeneration;
        }
        try {
            exec.execute(() -> performTouchScrollStep(clampedX, clampedY, dx, dy, safeW, safeH, expectedGen));
            return true;
        } catch (RuntimeException ignored) {
            return false;
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

    private void performTouchScrollStep(
            float anchorX,
            float anchorY,
            int dx,
            int dy,
            int safeW,
            int safeH,
            int expectedGen) {
        float startX;
        float startY;
        float endX;
        float endY;
        boolean needLiftBeforeAnchor = false;
        boolean needTouchDown = false;
        float prevLiftX = 0f;
        float prevLiftY = 0f;
        int prevLiftW = safeW;
        int prevLiftH = safeH;

        synchronized (scrollLock) {
            if (closed || userTouchActive || expectedGen != scrollGeneration) return;
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
                    prevLiftX = scrollCurX;
                    prevLiftY = scrollCurY;
                    prevLiftW = scrollDispW;
                    prevLiftH = scrollDispH;
                }
                scrollOriginX = anchorX;
                scrollOriginY = anchorY;
                scrollCurX = anchorX;
                scrollCurY = anchorY;
                scrollDispW = safeW;
                scrollDispH = safeH;
                scrollActive = true;
                needTouchDown = true;
            }
            startX = scrollCurX;
            startY = scrollCurY;
            endX = Math.max(minX, Math.min(maxX, startX + dx));
            endY = Math.max(minY, Math.min(maxY, startY + dy));
            scrollCurX = endX;
            scrollCurY = endY;
        }

        if (needLiftBeforeAnchor) {
            emitSingleScrollContact(prevLiftX, prevLiftY, prevLiftW, prevLiftH, false);
            SystemClock.sleep(SCROLL_STEP_SLEEP_MS);
        }
        if (closed || userTouchActive || isScrollGenerationStale(expectedGen)) return;

        if (needTouchDown) {
            if (!emitSingleScrollContact(startX, startY, safeW, safeH, true)) {
                cancelActiveTouchScroll(false);
                return;
            }
        }
        for (int step = 1; step <= SCROLL_INTERPOLATION_STEPS; step++) {
            if (closed || userTouchActive || isScrollGenerationStale(expectedGen)) return;
            SystemClock.sleep(SCROLL_STEP_SLEEP_MS);
            if (closed || userTouchActive || isScrollGenerationStale(expectedGen)) return;
            float t = (float) step / (float) SCROLL_INTERPOLATION_STEPS;
            float ix = startX + (endX - startX) * t;
            float iy = startY + (endY - startY) * t;
            if (!emitSingleScrollContact(ix, iy, safeW, safeH, true)) {
                cancelActiveTouchScroll(false);
                return;
            }
        }

        synchronized (scrollLock) {
            if (closed || userTouchActive || expectedGen != scrollGeneration || !scrollActive) return;
            if (scrollExecutor != null && !scrollExecutor.isShutdown()) {
                final int liftGen = expectedGen;
                pendingScrollLift = scrollExecutor.schedule(
                        () -> liftTouchScrollIfCurrent(liftGen),
                        SCROLL_IDLE_LIFT_MS,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    private void liftTouchScrollIfCurrent(int expectedGen) {
        float liftX;
        float liftY;
        int liftW;
        int liftH;
        synchronized (scrollLock) {
            if (closed || expectedGen != scrollGeneration || !scrollActive) return;
            pendingScrollLift = null;
            scrollActive = false;
            liftX = scrollCurX;
            liftY = scrollCurY;
            liftW = scrollDispW;
            liftH = scrollDispH;
        }
        emitSingleScrollContact(liftX, liftY, liftW, liftH, false);
    }

    private void cancelActiveTouchScroll(boolean liftContactIfActive) {
        boolean wasActive;
        float liftX;
        float liftY;
        int liftW;
        int liftH;
        synchronized (scrollLock) {
            scrollGeneration++;
            if (pendingScrollLift != null) {
                pendingScrollLift.cancel(false);
                pendingScrollLift = null;
            }
            wasActive = scrollActive;
            scrollActive = false;
            liftX = scrollCurX;
            liftY = scrollCurY;
            liftW = scrollDispW;
            liftH = scrollDispH;
        }
        if (liftContactIfActive && wasActive && !closed) {
            emitSingleScrollContact(liftX, liftY, liftW, liftH, false);
        }
    }

    private boolean isScrollGenerationStale(int expectedGen) {
        synchronized (scrollLock) {
            return expectedGen != scrollGeneration;
        }
    }

    private boolean emitSingleScrollContact(float px, float py, int dispW, int dispH, boolean tipDown) {
        int absX = UhidProtocol.toAbsoluteAxis(px, Math.max(2, dispW));
        int absY = UhidProtocol.toAbsoluteAxis(py, Math.max(2, dispH));
        return sendTouch(
                1,
                new int[]{UhidProtocol.SCROLL_TOUCH_CONTACT_ID},
                new boolean[]{tipDown},
                new int[]{absX},
                new int[]{absY},
                true);
    }

    private static float clampScrollCoord(float coord, int dim) {
        if (!Float.isFinite(coord)) return dim * 0.5f;
        float margin = Math.min(SCROLL_VIEWPORT_MARGIN_PX, dim * 0.15f);
        float maxCoord = Math.max(margin + 1f, dim - 1f - margin);
        return Math.max(margin, Math.min(maxCoord, coord));
    }

    public boolean sendTouch(
            int count,
            int[] contactIds,
            boolean[] tipDowns,
            int[] absXs,
            int[] absYs,
            boolean force) {
        byte[] report = UhidProtocol.buildTouchReport(count, contactIds, tipDowns, absXs, absYs);
        synchronized (touchWriteLock) {
            if (closed || touchFd == null) return false;
            if (!force && lastTouchReport != null && Arrays.equals(report, lastTouchReport)) {
                return true;
            }
            try {
                writeAll(touchFd, UhidProtocol.buildInput2Event(report));
                lastTouchReport = report;
                return true;
            } catch (Throwable t) {
                System.err.println("OPALDESK_UHID_TOUCH_WRITE_ERR: " + t.getMessage());
                return false;
            }
        }
    }

    private boolean handleKeyEvent(KeyEvent ke) {
        int action = ke.getAction();
        boolean isDown = (action == KeyEvent.ACTION_DOWN);
        boolean isUp = (action == KeyEvent.ACTION_UP);
        if (!isDown && !isUp) return false;
        if (isDown && ke.getRepeatCount() > 0) {
            int keyCode = ke.getKeyCode();
            synchronized (ioLock) {
                int modBit = UhidProtocol.androidModifierKeyToBit(keyCode);
                if (modBit != 0 && (activeModifiers & modBit) != 0) return true;
                int hidUsage = UhidProtocol.androidKeyCodeToHidUsage(keyCode);
                if (hidUsage != 0 && pressedHidKeys.contains(hidUsage)) return true;
            }
        }
        return sendKey(isDown, ke.getKeyCode(), ke.getMetaState());
    }

    public boolean sendKey(boolean isDown, int androidKeyCode, int metaState) {
        synchronized (ioLock) {
            if (closed || kbdFd == null) return false;

            if (androidKeyCode == KeyEvent.KEYCODE_PASTE) {
                int mods = isDown ? UhidProtocol.MOD_LEFT_CTRL : 0;
                int[] keys = isDown ? new int[]{HID_KEY_V} : new int[0]; // Ctrl+V
                try {
                    writeAll(kbdFd, UhidProtocol.buildInput2Event(UhidProtocol.buildKeyboardReport(mods, keys)));
                    return true;
                } catch (Throwable t) {
                    System.err.println("OPALDESK_UHID_KBD_PASTE_ERR: " + t.getMessage());
                    return false;
                }
            }

            int modBit = UhidProtocol.androidModifierKeyToBit(androidKeyCode);
            if (modBit != 0) {
                if (isDown) activeModifiers |= modBit;
                else activeModifiers &= ~modBit;
            } else {
                int hidUsage = UhidProtocol.androidKeyCodeToHidUsage(androidKeyCode);
                if (hidUsage == 0) return false;
                if (isDown) {
                    if (pressedHidKeys.size() >= 6) {
                        Integer first = pressedHidKeys.iterator().next();
                        pressedHidKeys.remove(first);
                    }
                    pressedHidKeys.add(hidUsage);
                } else {
                    pressedHidKeys.remove(hidUsage);
                }
            }

            int effectiveMods = activeModifiers | (isDown ? UhidProtocol.androidMetaToHidModifiers(metaState) : 0);
            int[] keyArray = new int[pressedHidKeys.size()];
            int idx = 0;
            for (Integer k : pressedHidKeys) {
                keyArray[idx++] = k;
            }
            byte[] report = UhidProtocol.buildKeyboardReport(effectiveMods, keyArray);
            try {
                writeAll(kbdFd, UhidProtocol.buildInput2Event(report));
                return true;
            } catch (Throwable t) {
                System.err.println("OPALDESK_UHID_KBD_WRITE_ERR: " + t.getMessage());
                return false;
            }
        }
    }

    /** Releases any held modifiers or keys (e.g. when focus switches away mid-Alt+Tab). */
    public void releaseAllKeys() {
        synchronized (ioLock) {
            if (closed || kbdFd == null) return;
            if (activeModifiers == 0 && pressedHidKeys.isEmpty()) return;
            activeModifiers = 0;
            pressedHidKeys.clear();
            try {
                writeAll(kbdFd, UhidProtocol.buildInput2Event(UhidProtocol.buildKeyboardReport(0, null)));
            } catch (Throwable t) {
                System.err.println("OPALDESK_UHID_KBD_RESET_WARN: " + t.getMessage());
            }
        }
    }

    @Override
    public void close() {
        Thread evtThread;
        ScheduledExecutorService scrollExecToStop;
        synchronized (ioLock) {
            if (closed) return;
            closed = true;
            evtThread = touchEventThread;
            touchEventThread = null;
            activeMouseToTouch = false;
            userTouchActive = false;
            synchronized (scrollLock) {
                scrollGeneration++;
                if (pendingScrollLift != null) {
                    pendingScrollLift.cancel(false);
                    pendingScrollLift = null;
                }
                scrollActive = false;
                scrollExecToStop = scrollExecutor;
                scrollExecutor = null;
            }

            synchronized (touchWriteLock) {
                FileDescriptor tfd = touchFd;
                byte[] prevTouch = lastTouchReport;
                lastTouchReport = null;
                touchFd = null;
                if (tfd != null) {
                    try {
                        if (hasActiveTouchTip(prevTouch)) {
                            byte[] releaseReport = Arrays.copyOf(prevTouch, prevTouch.length);
                            int prevCount = Math.min(
                                    UhidProtocol.MAX_TOUCH_CONTACTS,
                                    releaseReport[UhidProtocol.TOUCH_REPORT_SIZE - 1] & 0xFF);
                            for (int i = 0; i < prevCount; i++) {
                                releaseReport[1 + (i * UhidProtocol.TOUCH_CONTACT_BYTES)] = 0x00;
                            }
                            writeAll(tfd, UhidProtocol.buildInput2Event(releaseReport));
                        }
                    } catch (Exception e) {
                        System.err.println("OPALDESK_UHID_TOUCH_RELEASE_WARN: " + e.getMessage());
                    }
                    destroyAndCloseFd(tfd);
                }
            }
            if (kbdFd != null) {
                try {
                    if (activeModifiers != 0 || !pressedHidKeys.isEmpty()) {
                        writeAll(kbdFd, UhidProtocol.buildInput2Event(UhidProtocol.buildKeyboardReport(0, null)));
                    }
                } catch (Exception e) {
                    System.err.println("OPALDESK_UHID_KBD_RELEASE_WARN: " + e.getMessage());
                }
                destroyAndCloseFd(kbdFd);
                kbdFd = null;
            }
            activeModifiers = 0;
            pressedHidKeys.clear();

            if (portAssociated) {
                portAssociated = false;
                if (!keyboardOnly) {
                    unbindPortAssociation(touchPhys);
                }
                unbindPortAssociation(kbdPhys);
            }
        }
        if (scrollExecToStop != null) {
            scrollExecToStop.shutdownNow();
        }
        if (evtThread != null && evtThread != Thread.currentThread()) {
            evtThread.interrupt();
            try {
                evtThread.join(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean hasActiveTouchTip(byte[] report) {
        if (report == null || report.length < UhidProtocol.TOUCH_REPORT_SIZE) return false;
        int count = Math.min(
                UhidProtocol.MAX_TOUCH_CONTACTS,
                report[UhidProtocol.TOUCH_REPORT_SIZE - 1] & 0xFF);
        for (int i = 0; i < count; i++) {
            if (report[1 + (i * UhidProtocol.TOUCH_CONTACT_BYTES)] != 0) {
                return true;
            }
        }
        return false;
    }

    private static FileDescriptor openUhidFd() throws ErrnoException {
        try {
            return Os.open(UHID_DEVICE_PATH, OsConstants.O_RDWR | OsConstants.O_NONBLOCK, 0);
        } catch (ErrnoException e) {
            return Os.open(UHID_DEVICE_PATH, OsConstants.O_RDWR, 0);
        }
    }

    private static void writeAll(FileDescriptor fd, byte[] data) throws Exception {
        int off = 0;
        int rem = data.length;
        int retries = 0;
        while (rem > 0) {
            try {
                int n = Os.write(fd, data, off, rem);
                if (n <= 0) {
                    throw new IOException("Short write to /dev/uhid (wrote " + n + ")");
                }
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

    private static void destroyAndCloseFd(FileDescriptor fd) {
        if (fd == null) return;
        try {
            writeAll(fd, UhidProtocol.buildDestroyEvent());
        } catch (Exception e) {
            System.err.println("OPALDESK_UHID_DESTROY_WARN: " + e.getMessage());
        } finally {
            try {
                Os.close(fd);
            } catch (ErrnoException e) {
                System.err.println("OPALDESK_UHID_CLOSE_WARN: " + e.getMessage());
            }
        }
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
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private boolean bindPortAssociation(String phys, String uniqueId) {
        if (phys == null || uniqueId == null || uniqueId.isEmpty()) return false;
        ensureAssocMethodsCached();
        if (invokeCached(inputManager, sImAddPort, phys, uniqueId)) return true;
        if (invokeCached(inputManager, sImAddLegacy, phys, uniqueId)) return true;
        if (invokeCached(iInputManager, sIImAddPort, phys, uniqueId)) return true;
        return invokeCached(iInputManager, sIImAddLegacy, phys, uniqueId);
    }

    private void unbindPortAssociation(String phys) {
        if (phys == null) return;
        ensureAssocMethodsCached();
        if (invokeCached(inputManager, sImRemPort, phys)) return;
        if (invokeCached(inputManager, sImRemLegacy, phys)) return;
        if (invokeCached(iInputManager, sIImRemPort, phys)) return;
        invokeCached(iInputManager, sIImRemLegacy, phys);
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
