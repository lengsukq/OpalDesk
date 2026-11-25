package io.github.lengsukq.opaldesk.daemon.stream;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import io.github.lengsukq.opaldesk.daemon.input.MouseRoutingPolicy;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;

/**
 * Pure-Java Linux /dev/uhid virtual HID pointer & keyboard driver for OpalDesk v4.1.
 * Bypasses OEM INJECT_EVENTS restrictions (such as HyperOS 3 SecurityException) by creating
 * genuine kernel HID devices via UHID_CREATE2 / UHID_INPUT2 / UHID_DESTROY and binding them
 * to the target VirtualDisplay via InputManager.addUniqueIdAssociationByPort.
 *
 * Delegates all binary struct / HID descriptor / key mapping encoding to {@link UhidProtocol}.
 */
public final class UhidInputDevice implements Closeable {
    public static final String UHID_DEVICE_PATH = "/dev/uhid";
    private static final int HID_KEY_V = 0x19;

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
    private final int displayId;
    private final String vdUniqueId;
    private final String ptrPhys;
    private final String kbdPhys;
    private final InputManager inputManager;
    private final Object iInputManager;

    private FileDescriptor ptrFd;
    private FileDescriptor kbdFd;
    private volatile boolean closed = false;
    private boolean portAssociated = false;
    private volatile String lastAssocError = "";

    private int lastAbsX = -1;
    private int lastAbsY = -1;
    private int lastButtons = 0;
    private int activeModifiers = 0;
    private final LinkedHashSet<Integer> pressedHidKeys = new LinkedHashSet<>();

    private UhidInputDevice(
            int displayId,
            String vdUniqueId,
            InputManager inputManager,
            Object iInputManager) {
        this.displayId = displayId;
        this.vdUniqueId = vdUniqueId;
        this.ptrPhys = UhidProtocol.pointerPhysForDisplay(displayId);
        this.kbdPhys = UhidProtocol.keyboardPhysForDisplay(displayId);
        this.inputManager = inputManager;
        this.iInputManager = iInputManager;
    }

    /**
     * Opens /dev/uhid for both virtual pointer and virtual keyboard, registers them via
     * UHID_CREATE2, and binds both physical port names to {@code vdUniqueId}.
     * On any failure, all opened FDs and associations are rolled back cleanly with zero leaks.
     */
    public static UhidInputDevice open(
            int displayId,
            String vdUniqueId,
            InputManager inputManager,
            Object iInputManager) throws IOException {
        UhidInputDevice dev = new UhidInputDevice(displayId, vdUniqueId, inputManager, iInputManager);
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
            boolean ptrBound = false;
            boolean kbdBound = false;
            if (needAssoc) {
                // Pre-register before UHID_CREATE2 so EventHub binds the display during initial HID probe.
                ptrBound = bindPortAssociation(ptrPhys, vdUniqueId);
                kbdBound = bindPortAssociation(kbdPhys, vdUniqueId);
                portAssociated = ptrBound || kbdBound;
            }

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
                ptrBound |= bindPortAssociation(ptrPhys, vdUniqueId);
                kbdBound |= bindPortAssociation(kbdPhys, vdUniqueId);
                portAssociated = ptrBound || kbdBound;
                if (!ptrBound) {
                    String err = lastAssocError;
                    throw new IOException("UHID port-to-display association rejected for display " + displayId
                            + (err.isEmpty() ? "" : (" (" + err + ")")));
                }
            }
            System.out.println("OPALDESK_UHID_CREATED displayId=" + displayId
                    + " ptrPhys=" + ptrPhys + " kbdPhys=" + kbdPhys
                    + " vdUniqueId=" + (vdUniqueId != null ? vdUniqueId : ""));
        }
    }

    public int getDisplayId() {
        return displayId;
    }

    public String getVdUniqueId() {
        return vdUniqueId;
    }

    public boolean isOpen() {
        return !closed && ptrFd != null && kbdFd != null;
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
        int action = me.getActionMasked();
        int absX = UhidProtocol.toAbsoluteAxis(me.getX(), Math.max(2, displayW));
        int absY = UhidProtocol.toAbsoluteAxis(me.getY(), Math.max(2, displayH));
        boolean isTouch = (me.getSource() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                && (me.getSource() & InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE;

        int buttons;
        if (isTouch) {
            buttons = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    ? 0 : MotionEvent.BUTTON_PRIMARY;
        } else {
            buttons = me.getButtonState() & 0x1F;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_HOVER_EXIT) {
                buttons = 0;
            }
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            byte vWheel = UhidProtocol.clampWheelDelta(me.getAxisValue(MotionEvent.AXIS_VSCROLL));
            byte hWheel = UhidProtocol.clampWheelDelta(me.getAxisValue(MotionEvent.AXIS_HSCROLL));
            return sendPointer(absX, absY, buttons, vWheel, hWheel, true);
        }
        synchronized (ioLock) {
            if (MouseRoutingPolicy.needsPrePressPositionReport(
                    lastButtons, buttons & 0x1F, lastAbsX, lastAbsY, absX, absY)) {
                // Move virtual pointer cleanly to (absX, absY) with 0 buttons BEFORE pressing
                // so InputReader never treats a first click at a new spot as a drag from lastAbsX/Y!
                sendPointer(absX, absY, 0, 0, 0, false);
                return sendPointer(absX, absY, buttons, 0, 0, true);
            }
            return sendPointer(absX, absY, buttons, 0, 0, false);
        }
    }

    public boolean sendPointer(int absX, int absY, int buttonsMask, int vWheel, int hWheel, boolean force) {
        synchronized (ioLock) {
            if (closed || ptrFd == null) return false;
            int mask = buttonsMask & 0x1F;
            if (!force && vWheel == 0 && hWheel == 0
                    && absX == lastAbsX && absY == lastAbsY && mask == lastButtons) {
                return true;
            }
            byte[] report = UhidProtocol.buildPointerReport(mask, absX, absY, vWheel, hWheel);
            try {
                writeAll(ptrFd, UhidProtocol.buildInput2Event(report));
                lastAbsX = absX;
                lastAbsY = absY;
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

    @Override
    public void close() {
        synchronized (ioLock) {
            if (closed) return;
            closed = true;

            if (ptrFd != null) {
                try {
                    if (lastButtons != 0) {
                        writeAll(ptrFd, UhidProtocol.buildInput2Event(
                                UhidProtocol.buildPointerReport(0, Math.max(0, lastAbsX), Math.max(0, lastAbsY), 0, 0)));
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
                unbindPortAssociation(ptrPhys);
                unbindPortAssociation(kbdPhys);
            }
        }
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
