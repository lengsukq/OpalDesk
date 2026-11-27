package io.github.lengsukq.opaldesk.daemon.input;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.stream.UhidInputDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-Window VirtualDisplay Input Router.
 *
 * <p>Routes multi-touch, stylus, mouse hover/button/scroll, and key events directly into a
 * target window's isolated {@code displayId} via {@code IInputManager.injectInputEvent}, with
 * automatic fallback to Linux kernel {@code /dev/uhid} ({@link UhidInputDevice}) per display
 * when OEM ROMs restrict {@code INJECT_EVENTS}.
 */
public final class PerDisplayInputRouter {
    private static final long UHID_SETTLE_MS = 120L;
    private static final long UHID_RETRY_COOLDOWN_MS = 10_000L;
    private static final float TAP_SLOP_PX = 36f;

    /** Callback notified after a clean short tap (ACTION_UP without drag) inside a live window. */
    public interface TapUpListener {
        void onWindowTapUp(int windowId, int displayId, long tapDownUptimeMs);
    }

    /**
     * Callback invoked synchronously BEFORE {@code ACTION_DOWN} or {@code KeyEvent} is injected
     * into a DeX {@code VirtualDisplay}, allowing {@code DexImeManager} to reclaim
     * {@code OpalDeskImeService} immediately if Display 0 had temporarily borrowed the phone IME.
     */
    public interface DexInteractionListener {
        void onDexDisplayInteraction(int displayId, boolean isTouch);
    }

    /** Callback notified whenever effective input injection status changes (SYSTEM / UHID / BLOCKED). */
    public interface InjectStatusListener {
        void onInjectStatusChanged(int newStatus);
    }

    private final AndroidServices services;
    private final DaemonConfig config;
    private final InputFallbackDispatcher dispatcher;
    private final Object uhidLock = new Object();
    private final Map<Integer, UhidInputDevice> uhidByDisplay = new HashMap<>();
    private final Map<Integer, Long> uhidBlockedUntilMs = new HashMap<>();
    private final Map<Integer, UhidInputDevice> uhidKeyboardByDisplay = new HashMap<>();
    private final Map<Integer, Long> uhidKbdBlockedUntilMs = new HashMap<>();

    private volatile boolean localImeActive = false;
    private volatile boolean physicalKeyboardConnected = false;
    private volatile java.util.function.BooleanSupplier hostMouseAnchoredProvider;
    private volatile int lastKeyTargetDisplayId = -1;
    private volatile TapUpListener tapUpListener;
    private volatile DexInteractionListener dexInteractionListener;

    private int lastDownWinId = -1;
    private float lastDownX = 0f;
    private float lastDownY = 0f;
    private long lastDownUptimeMs = 0L;
    private boolean tapMovedTooFar = false;
    private volatile boolean activeMouseTouchEmulation = false;
    private volatile boolean activeSideNavMouseSuppression = false;

    public PerDisplayInputRouter(AndroidServices services, DaemonConfig config) {
        this.services = services;
        this.config = config;
        this.dispatcher = new InputFallbackDispatcher(config, new InputFallbackDispatcher.BackendAdapter() {
            @Override
            public boolean isSystemInjectorAvailable() {
                return services != null && services.hasSystemInputInjector();
            }

            @Override
            public boolean injectViaSystem(Object ev, int displayId) throws Exception {
                if (services == null || !(ev instanceof InputEvent)) return false;
                InputEvent ie = (InputEvent) ev;
                if (services.setDisplayIdMethod != null) {
                    services.setDisplayIdMethod.invoke(ie, displayId);
                }
                return services.invokeSystemInjectInputEvent(ie, 0);
            }

            @Override
            public boolean injectViaUhid(Object ev, int displayId, String vdUniqueId, int dispW, int dispH) {
                if (!(ev instanceof InputEvent)) return false;
                return PerDisplayInputRouter.this.injectViaUhid((InputEvent) ev, displayId, vdUniqueId, dispW, dispH);
            }
        });
    }

    public void setTapUpListener(TapUpListener listener) {
        this.tapUpListener = listener;
    }

    public void setDexInteractionListener(DexInteractionListener listener) {
        this.dexInteractionListener = listener;
    }

    private void notifyDexInteraction(int displayId, boolean isTouch) {
        DexInteractionListener listener = this.dexInteractionListener;
        if (listener != null && displayId > 0) {
            try {
                listener.onDexDisplayInteraction(displayId, isTouch);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public void setInjectStatusListener(InjectStatusListener listener) {
        this.dispatcher.setStatusListener(listener != null ? listener::onInjectStatusChanged : null);
    }

    public void setLocalImeActive(boolean active) {
        this.localImeActive = active;
        if (active) {
            releaseAllHeldKeys();
        }
    }

    public boolean isLocalImeActive() {
        return localImeActive;
    }

    public void setPhysicalKeyboardConnected(boolean connected) {
        this.physicalKeyboardConnected = connected;
    }

    public void setHostMouseAnchoredProvider(java.util.function.BooleanSupplier provider) {
        this.hostMouseAnchoredProvider = provider;
    }

    public boolean isPhysicalKeyboardConnected() {
        return physicalKeyboardConnected;
    }

    public int getLastInjectStatus() {
        return dispatcher.getLastInjectStatus();
    }

    public int onInputBackendConfigChanged(int mode) {
        synchronized (uhidLock) {
            uhidBlockedUntilMs.clear();
            uhidKbdBlockedUntilMs.clear();
        }
        return dispatcher.onInputBackendConfigChanged(mode, this::releaseAllDisplays);
    }

    public boolean injectWindowMotion(
            WindowSession ws,
            int action,
            long downTime,
            long eventTime,
            int source,
            int buttonState,
            int actionButton,
            float hScroll,
            float vScroll,
            int[] pointerIds,
            int[] toolTypes,
            float[] xs,
            float[] ys,
            float[] pressures) {
        if (ws == null || !ws.isLive() || ws.displayId <= 0 || pointerIds == null || pointerIds.length == 0) {
            return false;
        }
        int masked = action & MotionEvent.ACTION_MASK;
        if (masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) {
            if (masked == MotionEvent.ACTION_BUTTON_PRESS && actionButton == MotionEvent.BUTTON_BACK) {
                return sendBackToWindow(ws);
            }
            return true;
        }
        boolean isPassiveHover = MouseRoutingPolicy.isPassiveHoverAction(masked);
        if (isPassiveHover && dispatcher.isUsingUhid()) {
            return true;
        }

        boolean isMouse = (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (toolTypes.length > 0 && toolTypes[0] == MotionEvent.TOOL_TYPE_MOUSE);
        if (MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                masked, isMouse, buttonState, activeSideNavMouseSuppression)) {
            if (masked == MotionEvent.ACTION_DOWN) {
                activeSideNavMouseSuppression = true;
                activeMouseTouchEmulation = false;
                lastDownWinId = -1;
            } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                activeSideNavMouseSuppression = false;
            }
            return true;
        }
        if (masked == MotionEvent.ACTION_DOWN
                || masked == MotionEvent.ACTION_UP
                || masked == MotionEvent.ACTION_CANCEL) {
            activeSideNavMouseSuppression = false;
        }

        int count = pointerIds.length;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = pointerIds[i];
            p.toolType = toolTypes[i];
            props[i] = p;

            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.x = xs[i];
            c.y = ys[i];
            c.pressure = pressures[i];
            c.size = 1.0f;
            if (i == 0 && masked == MotionEvent.ACTION_SCROLL) {
                c.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
                c.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
            }
            coords[i] = c;
        }

        boolean triggerTapUpCheck = false;
        long tapDownTs = 0L;
        if (masked == MotionEvent.ACTION_DOWN) {
            ws.lastActiveAtMs = System.currentTimeMillis();
            lastDownWinId = ws.windowId;
            lastDownX = xs[0];
            lastDownY = ys[0];
            lastDownUptimeMs = SystemClock.uptimeMillis();
            tapMovedTooFar = false;
            notifyDexInteraction(ws.displayId, !isMouse);
            if (!localImeActive) {
                // Establish top display focus BEFORE ACTION_DOWN reaches the app window so
                // WindowManagerService.hasInputMethodClientFocus() is 100% true when EditText
                // invokes IMM.showSoftInput() on ACTION_UP! Because all compositor host windows
                // carry FLAG_NOT_FOCUSABLE and physical mice are display-associated, focusing
                // ws.displayId here never hijacks hardware cursors or gets stolen back mid-click.
                services.focusDisplay(ws.displayId);
            }
        } else if (masked == MotionEvent.ACTION_MOVE && ws.windowId == lastDownWinId && !tapMovedTooFar) {
            float dx = xs[0] - lastDownX;
            float dy = ys[0] - lastDownY;
            if ((dx * dx + dy * dy) > (TAP_SLOP_PX * TAP_SLOP_PX)) {
                tapMovedTooFar = true;
            }
        } else if (masked == MotionEvent.ACTION_UP) {
            ws.lastActiveAtMs = System.currentTimeMillis();
            boolean isTap = (ws.windowId == lastDownWinId && !tapMovedTooFar);
            if (isTap) {
                triggerTapUpCheck = !localImeActive;
                tapDownTs = lastDownUptimeMs;
            }
            lastDownWinId = -1;
        } else if (masked == MotionEvent.ACTION_CANCEL) {
            lastDownWinId = -1;
        }
        boolean isScroll = (masked == MotionEvent.ACTION_SCROLL);
        boolean mapMouseClickToTouch = !isScroll && MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                masked, isMouse, buttonState, activeMouseTouchEmulation);
        if (mapMouseClickToTouch) {
            if (masked == MotionEvent.ACTION_DOWN) {
                activeMouseTouchEmulation = true;
            } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                activeMouseTouchEmulation = false;
            }
            props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[0].pressure = (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) ? 0.0f : 1.0f;
        } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            activeMouseTouchEmulation = false;
        }
        int effSource = isScroll
                ? InputDevice.SOURCE_MOUSE
                : (mapMouseClickToTouch
                        ? InputDevice.SOURCE_TOUCHSCREEN
                        : (isMouse ? InputDevice.SOURCE_MOUSE : ((source != 0) ? source : InputDevice.SOURCE_TOUCHSCREEN)));
        int normButtonState = (isScroll || mapMouseClickToTouch)
                ? 0
                : MouseRoutingPolicy.normalizeMouseButtonState(masked, isMouse, buttonState);
        long effEventTime = (eventTime > 0L) ? eventTime : SystemClock.uptimeMillis();
        long effDownTime = isScroll
                ? effEventTime
                : (downTime > 0L ? downTime : effEventTime);

        if (isScroll) {
            props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            coords[0].pressure = 0.0f;
        }
        if (!dispatcher.isUsingUhid()
                && (isScroll || (isMouse && !mapMouseClickToTouch && masked == MotionEvent.ACTION_DOWN))) {
            MotionEvent.PointerProperties hoverProp = new MotionEvent.PointerProperties();
            hoverProp.id = props[0].id;
            hoverProp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
            MotionEvent.PointerCoords hoverCoord = new MotionEvent.PointerCoords();
            hoverCoord.x = coords[0].x;
            hoverCoord.y = coords[0].y;
            hoverCoord.pressure = 0.0f;
            hoverCoord.size = 1.0f;
            MotionEvent hoverPrime = MotionEvent.obtain(
                    effEventTime, effEventTime, MotionEvent.ACTION_HOVER_MOVE,
                    1, new MotionEvent.PointerProperties[]{hoverProp},
                    new MotionEvent.PointerCoords[]{hoverCoord},
                    0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            try {
                dispatchToDisplay(hoverPrime, ws.displayId, ws.vdUniqueId, ws.contentWidth, ws.contentHeight, false);
            } finally {
                hoverPrime.recycle();
            }
        }

        MotionEvent ev = MotionEvent.obtain(
                effDownTime,
                effEventTime,
                action,
                count,
                props,
                coords,
                0,
                normButtonState,
                1.0f,
                1.0f,
                0,
                0,
                effSource,
                0);
        if (actionButton != 0) {
            AndroidServices.setActionButton(ev, actionButton);
        }
        try {
            boolean ok = dispatchToDisplay(
                    ev, ws.displayId, ws.vdUniqueId, ws.contentWidth, ws.contentHeight, !isPassiveHover);
            if (ok && triggerTapUpCheck) {
                TapUpListener listener = tapUpListener;
                if (listener != null) {
                    listener.onWindowTapUp(ws.windowId, ws.displayId, tapDownTs);
                }
            }
            return ok;
        } finally {
            ev.recycle();
        }
    }

    public boolean injectWindowKey(WindowSession ws, int action, int keyCode, int metaState) {
        return injectWindowKey(ws, action, keyCode, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0);
    }

    public boolean injectWindowKey(
            WindowSession ws,
            int action,
            int keyCode,
            int metaState,
            int deviceId,
            int scanCode,
            int repeatCount) {
        if (ws == null || !ws.isLive() || ws.displayId <= 0) return false;
        ws.lastActiveAtMs = System.currentTimeMillis();
        if (action == KeyEvent.ACTION_DOWN) {
            notifyDexInteraction(ws.displayId, false);
        }
        int prevDisplay = lastKeyTargetDisplayId;
        if (prevDisplay > 0 && prevDisplay != ws.displayId) {
            releaseHeldKeysOnDisplay(prevDisplay);
        }
        lastKeyTargetDisplayId = ws.displayId;
        if (localImeActive) {
            services.focusDisplayTaskOnly(ws.displayId);
        } else {
            services.focusDisplay(ws.displayId);
        }
        long now = SystemClock.uptimeMillis();
        int effDeviceId = (deviceId > 0) ? deviceId : KeyCharacterMap.VIRTUAL_KEYBOARD;
        int effRepeat = Math.max(0, repeatCount);
        KeyEvent ev = new KeyEvent(
                now, now, action, keyCode, effRepeat, metaState,
                effDeviceId, Math.max(0, scanCode), KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);

        boolean hasUid = ws.vdUniqueId != null && !ws.vdUniqueId.isEmpty();
        boolean isPhysicalKey = deviceId > 0;
        int backendMode = (config != null) ? config.getInputBackendMode() : 0;
        if (MouseRoutingPolicy.shouldPreferUhidForPhysicalKey(backendMode, isPhysicalKey, hasUid)) {
            if (injectKeyViaUhid(ev, ws.displayId, ws.vdUniqueId)) {
                return true;
            }
        }
        return dispatchToDisplay(ev, ws.displayId, ws.vdUniqueId, ws.contentWidth, ws.contentHeight);
    }

    /** Pre-warms a display-bound kernel UHID keyboard on a background thread so keystroke #1 has zero settle pause. */
    public void prewarmKeyboardForDisplay(int displayId, String vdUniqueId) {
        if (displayId <= 0 || vdUniqueId == null || vdUniqueId.isEmpty()) return;
        int backendMode = (config != null) ? config.getInputBackendMode() : 0;
        if (!MouseRoutingPolicy.shouldPreferUhidForPhysicalKey(backendMode, true, true)) return;
        ensureUhidKeyboardDevice(displayId, vdUniqueId);
    }

    /** Releases held modifier/key state across all open UHID keyboards (prevents stuck Alt/Ctrl on focus switch). */
    public void releaseAllHeldKeys() {
        ArrayList<UhidInputDevice> devs = new ArrayList<>();
        synchronized (uhidLock) {
            devs.addAll(uhidByDisplay.values());
            devs.addAll(uhidKeyboardByDisplay.values());
        }
        for (UhidInputDevice d : devs) {
            if (d != null) {
                d.releaseAllKeys();
            }
        }
    }

    private void releaseHeldKeysOnDisplay(int displayId) {
        UhidInputDevice fullDev;
        UhidInputDevice kbdDev;
        synchronized (uhidLock) {
            fullDev = uhidByDisplay.get(displayId);
            kbdDev = uhidKeyboardByDisplay.get(displayId);
        }
        if (fullDev != null) fullDev.releaseAllKeys();
        if (kbdDev != null) kbdDev.releaseAllKeys();
    }

    public boolean sendBackToWindow(WindowSession ws) {
        if (ws == null || !ws.isLive() || ws.displayId <= 0) return false;
        boolean down = injectWindowKey(ws, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
        boolean up = injectWindowKey(ws, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
        return down || up;
    }

    /** Returns {@code true} when input routing is currently using Linux {@code /dev/uhid}. */
    public boolean isUsingUhid() {
        return dispatcher.isUsingUhid();
    }

    /** Direct display-targeted motion injection for remote OpalCast sessions (supports Display 0 for Mirror mode). */
    public boolean injectRawEventToDisplay(InputEvent ev, int displayId, String vdUniqueId, int dispW, int dispH) {
        if (ev == null || displayId < 0) return false;
        boolean isPassiveHover = false;
        if (ev instanceof MotionEvent) {
            MotionEvent me = (MotionEvent) ev;
            int masked = me.getActionMasked();
            isPassiveHover = MouseRoutingPolicy.isPassiveHoverAction(masked);
            if (isPassiveHover && dispatcher.isUsingUhid()) {
                return true;
            }
            if (displayId > 0 && masked == MotionEvent.ACTION_DOWN) {
                boolean isMouse = (me.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
                notifyDexInteraction(displayId, !isMouse);
            }
        }
        return dispatchToDisplay(ev, displayId, vdUniqueId, dispW, dispH, !isPassiveHover);
    }

    /** Direct display-targeted key injection for remote OpalCast sessions (supports Display 0 for Mirror mode). */
    public boolean injectRawKeyToDisplay(
            int displayId, String vdUniqueId, int dispW, int dispH, int action, int keyCode, int metaState) {
        if (displayId < 0) return false;
        if (displayId > 0 && action == KeyEvent.ACTION_DOWN) {
            notifyDexInteraction(displayId, false);
        }
        services.focusDisplay(displayId);
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(
                now, now, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        return dispatchToDisplay(ev, displayId, vdUniqueId, dispW, dispH);
    }

    public void releaseDisplayResources(int displayId) {
        UhidInputDevice dev;
        UhidInputDevice kbdDev;
        synchronized (uhidLock) {
            dev = uhidByDisplay.remove(displayId);
            uhidBlockedUntilMs.remove(displayId);
            kbdDev = uhidKeyboardByDisplay.remove(displayId);
            uhidKbdBlockedUntilMs.remove(displayId);
        }
        if (dev != null) {
            dev.close();
        }
        if (kbdDev != null) {
            kbdDev.close();
        }
    }

    /** Closes all standalone keyboard-only UHID devices when no physical keyboard remains connected. */
    public void releasePhysicalKeyboardUhidDevices() {
        ArrayList<UhidInputDevice> kbds;
        synchronized (uhidLock) {
            uhidKbdBlockedUntilMs.clear();
            if (uhidKeyboardByDisplay.isEmpty()) return;
            kbds = new ArrayList<>(uhidKeyboardByDisplay.values());
            uhidKeyboardByDisplay.clear();
        }
        for (UhidInputDevice d : kbds) {
            d.close();
        }
    }

    public void releaseAllDisplays() {
        ArrayList<UhidInputDevice> all = new ArrayList<>();
        synchronized (uhidLock) {
            uhidBlockedUntilMs.clear();
            uhidKbdBlockedUntilMs.clear();
            all.addAll(uhidByDisplay.values());
            all.addAll(uhidKeyboardByDisplay.values());
            uhidByDisplay.clear();
            uhidKeyboardByDisplay.clear();
        }
        for (UhidInputDevice d : all) {
            d.close();
        }
    }

    private boolean dispatchToDisplay(InputEvent ev, int displayId, String vdUniqueId, int dispW, int dispH) {
        return dispatcher.dispatch(ev, displayId, vdUniqueId, dispW, dispH, true);
    }

    private boolean dispatchToDisplay(
            InputEvent ev,
            int displayId,
            String vdUniqueId,
            int dispW,
            int dispH,
            boolean allowAutoUhidFallback) {
        return dispatcher.dispatch(ev, displayId, vdUniqueId, dispW, dispH, allowAutoUhidFallback);
    }

    private boolean injectViaUhid(InputEvent ev, int displayId, String vdUniqueId, int dispW, int dispH) {
        UhidInputDevice dev = ensureUhidDevice(displayId, vdUniqueId);
        return dev != null && dev.injectInputEvent(ev, Math.max(2, dispW), Math.max(2, dispH));
    }

    private boolean injectKeyViaUhid(KeyEvent ev, int displayId, String vdUniqueId) {
        UhidInputDevice dev = ensureUhidKeyboardDevice(displayId, vdUniqueId);
        return dev != null && dev.injectInputEvent(ev, 2, 2);
    }

    private UhidInputDevice ensureUhidKeyboardDevice(int displayId, String vdUniqueId) {
        boolean newlyOpened = false;
        UhidInputDevice dev;
        synchronized (uhidLock) {
            UhidInputDevice full = uhidByDisplay.get(displayId);
            if (full != null && full.isOpen() && sameUniqueId(full.getVdUniqueId(), vdUniqueId)) {
                return full;
            }
            dev = uhidKeyboardByDisplay.get(displayId);
            if (dev != null && dev.isOpen()) {
                if (sameUniqueId(dev.getVdUniqueId(), vdUniqueId)) {
                    return dev;
                }
                dev.close();
                uhidKeyboardByDisplay.remove(displayId);
            }
            Long blockedUntil = uhidKbdBlockedUntilMs.get(displayId);
            long nowMs = SystemClock.uptimeMillis();
            if (blockedUntil != null && nowMs < blockedUntil) {
                return null;
            }
            try {
                dev = UhidInputDevice.openKeyboardOnly(displayId, vdUniqueId, services.inputManager, services.iInputManager);
                uhidKeyboardByDisplay.put(displayId, dev);
                uhidKbdBlockedUntilMs.remove(displayId);
                newlyOpened = true;
            } catch (Exception e) {
                uhidKbdBlockedUntilMs.put(displayId, nowMs + UHID_RETRY_COOLDOWN_MS);
                System.err.println("OPALDESK_UHID_KBD_OPEN_WARN displayId=" + displayId + ": " + e.getMessage());
                return null;
            }
        }
        if (newlyOpened && Looper.myLooper() != Looper.getMainLooper()) {
            SystemClock.sleep(UHID_SETTLE_MS);
        }
        return dev;
    }

    private UhidInputDevice ensureUhidDevice(int displayId, String vdUniqueId) {
        boolean newlyOpened = false;
        UhidInputDevice dev;
        synchronized (uhidLock) {
            dev = uhidByDisplay.get(displayId);
            if (dev != null && dev.isOpen()) {
                if (sameUniqueId(dev.getVdUniqueId(), vdUniqueId)) {
                    return dev;
                }
                dev.close();
                uhidByDisplay.remove(displayId);
            }
            // Close any standalone keyboard-only UHID on this display before opening full UHID
            // so kbdPhys is never registered twice for the same displayId.
            UhidInputDevice prevKbd = uhidKeyboardByDisplay.remove(displayId);
            if (prevKbd != null) {
                prevKbd.close();
            }
            Long blockedUntil = uhidBlockedUntilMs.get(displayId);
            long nowMs = SystemClock.uptimeMillis();
            if (blockedUntil != null && nowMs < blockedUntil) {
                return null;
            }
            try {
                dev = UhidInputDevice.open(displayId, vdUniqueId, services.inputManager, services.iInputManager);
                uhidByDisplay.put(displayId, dev);
                uhidBlockedUntilMs.remove(displayId);
                newlyOpened = true;
            } catch (Exception e) {
                uhidBlockedUntilMs.put(displayId, nowMs + UHID_RETRY_COOLDOWN_MS);
                System.err.println("OPALDESK_UHID_OPEN_WARN displayId=" + displayId + ": " + e.getMessage());
                return null;
            }
        }
        if (newlyOpened && Looper.myLooper() != Looper.getMainLooper()) {
            SystemClock.sleep(UHID_SETTLE_MS);
        }
        return dev;
    }

    private static boolean sameUniqueId(String a, String b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }
}
