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

/**
 * Pure-Java Linux /dev/uhid virtual HID touchscreen, relative mouse, and keyboard driver.
 * Bypasses OEM INJECT_EVENTS restrictions (such as HyperOS SecurityException) by creating genuine
 * kernel HID devices via UHID_CREATE2 / UHID_INPUT2 / UHID_DESTROY and binding all physical ports
 * to the target VirtualDisplay via InputManager.addUniqueIdAssociationByPort.
 *
 * Delegates all binary struct / HID descriptor / key mapping encoding to {@link UhidProtocol}.
 */
public final class UhidInputDevice implements Closeable {
    public static final String UHID_DEVICE_PATH = "/dev/uhid";
    private static final int HID_KEY_V = 0x19;
    private static final int EIO_ERRNO = 5;

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
    private final int displayId;
    private final String vdUniqueId;
    private final boolean keyboardOnly;
    private final String touchPhys;
    private final String ptrPhys;
    private final String kbdPhys;
    private final InputManager inputManager;
    private final Object iInputManager;

    private FileDescriptor touchFd;
    private FileDescriptor ptrFd;
    private FileDescriptor kbdFd;
    private Thread touchEventThread;
    private volatile boolean closed = false;
    private boolean portAssociated = false;
    private volatile String lastAssocError = "";

    private byte[] lastTouchReport = null;
    private boolean activeMouseToTouch = false;
    private float lastMousePxX = -1f;
    private float lastMousePxY = -1f;
    private int lastButtons = 0;
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
        this.ptrPhys = UhidProtocol.pointerPhysForDisplay(displayId);
        this.kbdPhys = UhidProtocol.keyboardPhysForDisplay(displayId);
        this.inputManager = inputManager;
        this.iInputManager = iInputManager;
    }

    /**
     * Opens /dev/uhid for virtual multi-touch screen, relative mouse, and keyboard, registers them
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
            boolean ptrBound = false;
            boolean kbdBound = false;
            if (needAssoc) {
                // Pre-register before UHID_CREATE2 so EventHub binds the display during initial HID probe.
                if (!keyboardOnly) {
                    touchBound = bindPortAssociation(touchPhys, vdUniqueId);
                    ptrBound = bindPortAssociation(ptrPhys, vdUniqueId);
                }
                kbdBound = bindPortAssociation(kbdPhys, vdUniqueId);
                portAssociated = touchBound || ptrBound || kbdBound;
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

                ptrFd = openUhidFd();
                byte[] createPtr = UhidProtocol.buildCreate2Event(
                        "OpalDesk Virtual Pointer " + displayId,
                        ptrPhys,
                        "opaldesk-ptr-" + displayId,
                        UhidProtocol.BUS_VIRTUAL,
                        UhidProtocol.VENDOR_ID,
                        UhidProtocol.PRODUCT_ID_POINTER,
                        UhidProtocol.DEVICE_VERSION,
                        0,
                        UhidProtocol.getPointerReportDescriptor());
                writeAll(ptrFd, createPtr);
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
                    ptrBound |= bindPortAssociation(ptrPhys, vdUniqueId);
                }
                kbdBound |= bindPortAssociation(kbdPhys, vdUniqueId);
                portAssociated = touchBound || ptrBound || kbdBound;
                boolean requiredOk = keyboardOnly ? kbdBound : touchBound;
                if (!requiredOk) {
                    String err = lastAssocError;
                    throw new IOException("UHID port-to-display association rejected for display " + displayId
                            + (err.isEmpty() ? "" : (" (" + err + ")")));
                }
            }
            System.out.println((keyboardOnly ? "OPALDESK_UHID_KBD_CREATED displayId=" : "OPALDESK_UHID_CREATED displayId=")
                    + displayId
                    + (keyboardOnly ? "" : (" touchPhys=" + touchPhys + " ptrPhys=" + ptrPhys))
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
                : (!closed && touchFd != null && ptrFd != null && kbdFd != null);
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

        boolean isMouse = (me.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || me.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
        boolean isHoverOrScroll = action == MotionEvent.ACTION_SCROLL
                || action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_EXIT;
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
        } else if (isMouse && !isHoverOrScroll && isTouchAction) {
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
            int n = Math.min(UhidProtocol.MAX_TOUCH_CONTACTS, count);
            int upIdx = (action == MotionEvent.ACTION_POINTER_UP) ? me.getActionIndex() : -1;
            boolean allUp = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL);
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
            synchronized (ioLock) {
                lastMousePxX = me.getX(0);
                lastMousePxY = me.getY(0);
            }
            return sendTouch(n, contactIds, tipDowns, absXs, absYs, action != MotionEvent.ACTION_MOVE);
        }

        int relX = 0;
        int relY = 0;
        synchronized (ioLock) {
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                lastMousePxX = -1f;
                lastMousePxY = -1f;
            } else {
                float px = me.getX(0);
                float py = me.getY(0);
                if (lastMousePxX >= 0f && lastMousePxY >= 0f) {
                    relX = Math.round(px - lastMousePxX);
                    relY = Math.round(py - lastMousePxY);
                }
                lastMousePxX = px;
                lastMousePxY = py;
            }
        }
        int buttons = (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_HOVER_EXIT
                || action == MotionEvent.ACTION_SCROLL)
                ? 0 : (me.getButtonState() & 0x1F);
        if (action == MotionEvent.ACTION_SCROLL) {
            byte vWheel = UhidProtocol.clampWheelDelta(me.getAxisValue(MotionEvent.AXIS_VSCROLL));
            byte hWheel = UhidProtocol.clampWheelDelta(me.getAxisValue(MotionEvent.AXIS_HSCROLL));
            return sendPointer(relX, relY, 0, vWheel, hWheel, true);
        }
        return sendPointer(relX, relY, buttons, 0, 0, false);
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

    public boolean sendPointer(int relX, int relY, int buttonsMask, int vWheel, int hWheel, boolean force) {
        synchronized (ioLock) {
            if (closed || ptrFd == null) return false;
            int mask = buttonsMask & 0x1F;
            if (!force && vWheel == 0 && hWheel == 0
                    && relX == 0 && relY == 0 && mask == lastButtons) {
                return true;
            }
            byte[] report = UhidProtocol.buildPointerReport(mask, relX, relY, vWheel, hWheel);
            try {
                writeAll(ptrFd, UhidProtocol.buildInput2Event(report));
                lastButtons = mask;
                return true;
            } catch (Throwable t) {
                System.err.println("OPALDESK_UHID_PTR_WRITE_ERR: " + t.getMessage());
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
        synchronized (ioLock) {
            if (closed) return;
            closed = true;
            evtThread = touchEventThread;
            touchEventThread = null;
            activeMouseToTouch = false;
            lastMousePxX = -1f;
            lastMousePxY = -1f;

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
            if (ptrFd != null) {
                try {
                    if (lastButtons != 0) {
                        writeAll(ptrFd, UhidProtocol.buildInput2Event(
                                UhidProtocol.buildPointerReport(0, 0, 0, 0, 0)));
                    }
                } catch (Exception e) {
                    System.err.println("OPALDESK_UHID_PTR_RELEASE_WARN: " + e.getMessage());
                }
                destroyAndCloseFd(ptrFd);
                ptrFd = null;
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
            lastButtons = 0;
            activeModifiers = 0;
            pressedHidKeys.clear();

            if (portAssociated) {
                portAssociated = false;
                if (!keyboardOnly) {
                    unbindPortAssociation(touchPhys);
                    unbindPortAssociation(ptrPhys);
                }
                unbindPortAssociation(kbdPhys);
            }
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
