package io.github.lengsukq.opaldesk.daemon.stream;

import android.hardware.input.InputManager;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Pure-Java Linux /dev/uhid cursorless virtual HID touchscreen and keyboard driver.
 * Delegates binary struct / HID descriptor encoding to {@link UhidProtocol} and report/scroll/FD
 * association helpers to {@link UhidReportHelper}.
 */
public final class UhidInputDevice implements Closeable {
    public static final String UHID_DEVICE_PATH = "/dev/uhid";
    private static final int HID_KEY_V = 0x19;

    private final Object ioLock = new Object();
    private final Object touchWriteLock = new Object();
    private final int displayId;
    private final String vdUniqueId;
    private final boolean keyboardOnly;
    private final String touchPhys;
    private final String kbdPhys;
    private final UhidReportHelper.PortAssociationBinder assocBinder;
    private final UhidReportHelper.TouchScrollSynthesizer scrollSynthesizer;

    private FileDescriptor touchFd;
    private FileDescriptor kbdFd;
    private Thread touchEventThread;
    private volatile boolean userTouchActive = false;
    private volatile boolean closed = false;
    private boolean portAssociated = false;

    private byte[] lastTouchReport = null;
    private boolean activeMouseToTouch = false;
    private int activeModifiers = 0;
    private final LinkedHashSet<Integer> pressedHidKeys = new LinkedHashSet<>();

    private UhidInputDevice(
            int displayId, String vdUniqueId, boolean keyboardOnly,
            InputManager inputManager, Object iInputManager) {
        this.displayId = displayId;
        this.vdUniqueId = vdUniqueId;
        this.keyboardOnly = keyboardOnly;
        this.touchPhys = UhidProtocol.touchPhysForDisplay(displayId);
        this.kbdPhys = UhidProtocol.keyboardPhysForDisplay(displayId);
        this.assocBinder = new UhidReportHelper.PortAssociationBinder(inputManager, iInputManager);
        this.scrollSynthesizer = new UhidReportHelper.TouchScrollSynthesizer(
                displayId, () -> closed, () -> userTouchActive, this::emitSingleScrollContact);
    }

    public static UhidInputDevice open(
            int displayId, String vdUniqueId, InputManager inputManager, Object iInputManager) throws IOException {
        return openInternal(displayId, vdUniqueId, false, inputManager, iInputManager);
    }

    public static UhidInputDevice openKeyboardOnly(
            int displayId, String vdUniqueId, InputManager inputManager, Object iInputManager) throws IOException {
        return openInternal(displayId, vdUniqueId, true, inputManager, iInputManager);
    }

    private static UhidInputDevice openInternal(
            int displayId, String vdUniqueId, boolean keyboardOnly,
            InputManager inputManager, Object iInputManager) throws IOException {
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
                if (!keyboardOnly) touchBound = assocBinder.bindPortAssociation(touchPhys, vdUniqueId);
                kbdBound = assocBinder.bindPortAssociation(kbdPhys, vdUniqueId);
                portAssociated = touchBound || kbdBound;
            }

            if (!keyboardOnly) {
                touchFd = UhidReportHelper.openUhidFd();
                startTouchEventLoopLocked(touchFd);
                byte[] createTouch = UhidProtocol.buildCreate2Event(
                        "OpalDesk Virtual Touch " + displayId,
                        touchPhys, "opaldesk-touch-" + displayId,
                        UhidProtocol.BUS_VIRTUAL, UhidProtocol.VENDOR_ID,
                        UhidProtocol.PRODUCT_ID_TOUCH, UhidProtocol.DEVICE_VERSION,
                        0, UhidProtocol.getTouchReportDescriptor());
                writeTouchEvent(createTouch);
            }

            kbdFd = UhidReportHelper.openUhidFd();
            byte[] createKbd = UhidProtocol.buildCreate2Event(
                    "OpalDesk Virtual Keyboard " + displayId,
                    kbdPhys, "opaldesk-kbd-" + displayId,
                    UhidProtocol.BUS_VIRTUAL, UhidProtocol.VENDOR_ID,
                    UhidProtocol.PRODUCT_ID_KEYBOARD, UhidProtocol.DEVICE_VERSION,
                    0, UhidProtocol.getKeyboardReportDescriptor());
            UhidReportHelper.writeAll(kbdFd, createKbd);

            if (needAssoc) {
                if (!keyboardOnly) touchBound |= assocBinder.bindPortAssociation(touchPhys, vdUniqueId);
                kbdBound |= assocBinder.bindPortAssociation(kbdPhys, vdUniqueId);
                portAssociated = touchBound || kbdBound;
                boolean requiredOk = keyboardOnly ? kbdBound : touchBound;
                if (!requiredOk) {
                    String err = assocBinder.getLastAssocError();
                    throw new IOException("UHID port-to-display association rejected for display " + displayId
                            + (err.isEmpty() ? "" : (" (" + err + ")")));
                }
            }
            System.out.println((keyboardOnly ? "OPALDESK_UHID_KBD_CREATED displayId=" : "OPALDESK_UHID_CREATED displayId=")
                    + displayId + (keyboardOnly ? "" : (" touchPhys=" + touchPhys))
                    + " kbdPhys=" + kbdPhys + " vdUniqueId=" + (vdUniqueId != null ? vdUniqueId : ""));
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
                if ((rev & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) break;
                if (ready <= 0 || (rev & OsConstants.POLLIN) == 0) continue;
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
                    byte[] reply = UhidReportHelper.buildKernelReplyForTouchEvent(buf, n);
                    if (reply != null) writeTouchEventQuietly(reply);
                }
            } catch (ErrnoException ee) {
                if (ee.errno == OsConstants.EINTR) continue;
                break;
            } catch (Throwable ignored) {
                break;
            }
        }
    }

    private void writeTouchEvent(byte[] uhidEvent) throws Exception {
        synchronized (touchWriteLock) {
            if (closed || touchFd == null) throw new IOException("UHID touch device closed");
            UhidReportHelper.writeAll(touchFd, uhidEvent);
        }
    }

    private void writeTouchEventQuietly(byte[] uhidEvent) {
        try {
            synchronized (touchWriteLock) {
                if (!closed && touchFd != null) UhidReportHelper.writeAll(touchFd, uhidEvent);
            }
        } catch (Throwable ignored) {
        }
    }

    public int getDisplayId() { return displayId; }
    public String getVdUniqueId() { return vdUniqueId; }
    public boolean isOpen() { return keyboardOnly ? (!closed && kbdFd != null) : (!closed && touchFd != null && kbdFd != null); }
    public boolean isKeyboardOnly() { return keyboardOnly; }

    public boolean injectInputEvent(InputEvent ev, int displayW, int displayH) {
        if (ev == null || closed) return false;
        if (ev instanceof MotionEvent) return handleMotionEvent((MotionEvent) ev, displayW, displayH);
        if (ev instanceof KeyEvent) return handleKeyEvent((KeyEvent) ev);
        return false;
    }

    private boolean handleMotionEvent(MotionEvent me, int displayW, int displayH) {
        int safeW = Math.max(2, displayW);
        int safeH = Math.max(2, displayH);
        int action = me.getActionMasked();
        int count = me.getPointerCount();
        if (count <= 0) return false;
        if (MouseRoutingPolicy.isPassiveHoverAction(action)) return true;
        if (action == MotionEvent.ACTION_SCROLL) {
            if (closed || keyboardOnly || touchFd == null) return false;
            return scrollSynthesizer.enqueue(
                    me.getX(0), me.getY(0),
                    me.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    me.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    safeW, safeH);
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
            scrollSynthesizer.cancel(true);
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

        int buttons = me.getButtonState();
        if (action == MotionEvent.ACTION_DOWN
                && (buttons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_BACK)) != 0) {
            boolean down = sendKey(true, KeyEvent.KEYCODE_BACK, 0);
            boolean up = sendKey(false, KeyEvent.KEYCODE_BACK, 0);
            return down || up;
        }
        return true;
    }

    private boolean emitSingleScrollContact(float px, float py, int dispW, int dispH, boolean tipDown) {
        int absX = UhidProtocol.toAbsoluteAxis(px, Math.max(2, dispW));
        int absY = UhidProtocol.toAbsoluteAxis(py, Math.max(2, dispH));
        return sendTouch(
                1, new int[]{UhidProtocol.SCROLL_TOUCH_CONTACT_ID},
                new boolean[]{tipDown}, new int[]{absX}, new int[]{absY}, true);
    }

    public boolean sendTouch(
            int count, int[] contactIds, boolean[] tipDowns, int[] absXs, int[] absYs, boolean force) {
        byte[] report = UhidProtocol.buildTouchReport(count, contactIds, tipDowns, absXs, absYs);
        synchronized (touchWriteLock) {
            if (closed || touchFd == null) return false;
            if (!force && lastTouchReport != null && Arrays.equals(report, lastTouchReport)) return true;
            try {
                UhidReportHelper.writeAll(touchFd, UhidProtocol.buildInput2Event(report));
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
                int[] keys = isDown ? new int[]{HID_KEY_V} : new int[0];
                try {
                    UhidReportHelper.writeAll(kbdFd, UhidProtocol.buildInput2Event(UhidProtocol.buildKeyboardReport(mods, keys)));
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
                    if (pressedHidKeys.size() >= 6) pressedHidKeys.remove(pressedHidKeys.iterator().next());
                    pressedHidKeys.add(hidUsage);
                } else {
                    pressedHidKeys.remove(hidUsage);
                }
            }
            int effectiveMods = activeModifiers | (isDown ? UhidProtocol.androidMetaToHidModifiers(metaState) : 0);
            int[] keyArray = new int[pressedHidKeys.size()];
            int idx = 0;
            for (Integer k : pressedHidKeys) keyArray[idx++] = k;
            byte[] report = UhidProtocol.buildKeyboardReport(effectiveMods, keyArray);
            try {
                UhidReportHelper.writeAll(kbdFd, UhidProtocol.buildInput2Event(report));
                return true;
            } catch (Throwable t) {
                System.err.println("OPALDESK_UHID_KBD_WRITE_ERR: " + t.getMessage());
                return false;
            }
        }
    }

    public void releaseAllKeys() {
        synchronized (ioLock) {
            if (closed || kbdFd == null) return;
            if (activeModifiers == 0 && pressedHidKeys.isEmpty()) return;
            activeModifiers = 0;
            pressedHidKeys.clear();
            try {
                UhidReportHelper.writeAll(kbdFd, UhidProtocol.buildInput2Event(UhidProtocol.buildKeyboardReport(0, null)));
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
            scrollExecToStop = scrollSynthesizer.detachAndStopLocked();

            synchronized (touchWriteLock) {
                FileDescriptor tfd = touchFd;
                byte[] prevTouch = lastTouchReport;
                lastTouchReport = null;
                touchFd = null;
                if (tfd != null) {
                    try {
                        if (UhidReportHelper.hasActiveTouchTip(prevTouch)) {
                            byte[] releaseReport = UhidReportHelper.buildTouchReleaseReport(prevTouch);
                            UhidReportHelper.writeAll(tfd, UhidProtocol.buildInput2Event(releaseReport));
                        }
                    } catch (Exception e) {
                        System.err.println("OPALDESK_UHID_TOUCH_RELEASE_WARN: " + e.getMessage());
                    }
                    UhidReportHelper.destroyAndCloseFd(tfd);
                }
            }
            if (kbdFd != null) {
                try {
                    if (activeModifiers != 0 || !pressedHidKeys.isEmpty()) {
                        UhidReportHelper.writeAll(kbdFd, UhidProtocol.buildInput2Event(UhidProtocol.buildKeyboardReport(0, null)));
                    }
                } catch (Exception e) {
                    System.err.println("OPALDESK_UHID_KBD_RELEASE_WARN: " + e.getMessage());
                }
                UhidReportHelper.destroyAndCloseFd(kbdFd);
                kbdFd = null;
            }
            activeModifiers = 0;
            pressedHidKeys.clear();
            if (portAssociated) {
                portAssociated = false;
                if (!keyboardOnly) assocBinder.unbindPortAssociation(touchPhys);
                assocBinder.unbindPortAssociation(kbdPhys);
            }
        }
        if (scrollExecToStop != null) scrollExecToStop.shutdownNow();
        if (evtThread != null && evtThread != Thread.currentThread()) {
            evtThread.interrupt();
            try { evtThread.join(250L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }
}
