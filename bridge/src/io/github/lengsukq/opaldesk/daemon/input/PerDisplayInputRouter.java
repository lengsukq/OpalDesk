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

    /** Callback notified whenever effective input injection status changes (SYSTEM / UHID / BLOCKED). */
    public interface InjectStatusListener {
        void onInjectStatusChanged(int newStatus);
    }

    private final AndroidServices services;
    private final InputFallbackDispatcher dispatcher;
    private final Object uhidLock = new Object();
    private final Map<Integer, UhidInputDevice> uhidByDisplay = new HashMap<>();
    private final Map<Integer, Long> uhidBlockedUntilMs = new HashMap<>();

    private volatile boolean localImeActive = false;
    private volatile TapUpListener tapUpListener;

    private int lastDownWinId = -1;
    private float lastDownX = 0f;
    private float lastDownY = 0f;
    private long lastDownUptimeMs = 0L;
    private boolean tapMovedTooFar = false;
    private volatile boolean activeMouseTouchEmulation = false;

    public PerDisplayInputRouter(AndroidServices services, DaemonConfig config) {
        this.services = services;
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

    public void setInjectStatusListener(InjectStatusListener listener) {
        this.dispatcher.setStatusListener(listener != null ? listener::onInjectStatusChanged : null);
    }

    public void setLocalImeActive(boolean active) {
        this.localImeActive = active;
    }

    public boolean isLocalImeActive() {
        return localImeActive;
    }

    public int getLastInjectStatus() {
        return dispatcher.getLastInjectStatus();
    }

    public int onInputBackendConfigChanged(int mode) {
        synchronized (uhidLock) {
            uhidBlockedUntilMs.clear();
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

        boolean isMouse = (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (toolTypes.length > 0 && toolTypes[0] == MotionEvent.TOOL_TYPE_MOUSE);
        boolean triggerTapUpCheck = false;
        long tapDownTs = 0L;
        if (masked == MotionEvent.ACTION_DOWN) {
            ws.lastActiveAtMs = System.currentTimeMillis();
            lastDownWinId = ws.windowId;
            lastDownX = xs[0];
            lastDownY = ys[0];
            lastDownUptimeMs = SystemClock.uptimeMillis();
            tapMovedTooFar = false;
            if (!localImeActive) {
                // Focus the task inside its VirtualDisplay WITHOUT calling moveDisplayToTop mid-press,
                // which would steal top display focus from Display 0 and abort external USB mouse clicks!
                services.focusDisplayTaskOnly(ws.displayId);
            }
        } else if (masked == MotionEvent.ACTION_MOVE && ws.windowId == lastDownWinId && !tapMovedTooFar) {
            float dx = xs[0] - lastDownX;
            float dy = ys[0] - lastDownY;
            if ((dx * dx + dy * dy) > (TAP_SLOP_PX * TAP_SLOP_PX)) {
                tapMovedTooFar = true;
            }
        } else if (masked == MotionEvent.ACTION_UP) {
            ws.lastActiveAtMs = System.currentTimeMillis();
            if (ws.windowId == lastDownWinId && !tapMovedTooFar) {
                triggerTapUpCheck = !localImeActive;
                tapDownTs = lastDownUptimeMs;
                if (!localImeActive) {
                    if (isMouse) {
                        // Keep hardware mouse pointer anchored on Display 0 / External Monitor while
                        // TapUpListener still probes IMMS to raise NativeImeBridgeHost if an EditText focused.
                        services.focusDisplayTaskOnly(ws.displayId);
                    } else {
                        services.focusDisplay(ws.displayId);
                    }
                }
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

        if (isScroll || (isMouse && !mapMouseClickToTouch && masked == MotionEvent.ACTION_DOWN)) {
            MotionEvent.PointerProperties hoverProp = new MotionEvent.PointerProperties();
            hoverProp.id = props[0].id;
            hoverProp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
            if (isScroll) {
                props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
                coords[0].pressure = 0.0f;
            }
            MotionEvent.PointerCoords hoverCoord = new MotionEvent.PointerCoords();
            hoverCoord.x = coords[0].x;
            hoverCoord.y = coords[0].y;
            hoverCoord.pressure = 0.0f;
            hoverCoord.size = 1.0f;
            // Prime hover target at (x, y) with 0 buttons BEFORE mouse ACTION_DOWN or ACTION_SCROLL so
            // InputDispatcher / ViewGroup hover state & /dev/uhid cursor position never jump mid-press
            // from an old coordinate (eliminating USB mouse double-click-to-click inside apps!).
            MotionEvent hoverPrime = MotionEvent.obtain(
                    effEventTime, effEventTime, MotionEvent.ACTION_HOVER_MOVE,
                    1, new MotionEvent.PointerProperties[]{hoverProp},
                    new MotionEvent.PointerCoords[]{hoverCoord},
                    0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            try {
                dispatchToDisplay(hoverPrime, ws.displayId, ws.vdUniqueId, ws.contentWidth, ws.contentHeight);
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
            boolean ok = dispatchToDisplay(ev, ws.displayId, ws.vdUniqueId, ws.contentWidth, ws.contentHeight);
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
        if (ws == null || !ws.isLive() || ws.displayId <= 0) return false;
        ws.lastActiveAtMs = System.currentTimeMillis();
        if (localImeActive) {
            services.focusDisplayTaskOnly(ws.displayId);
        } else {
            services.focusDisplay(ws.displayId);
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(
                now, now, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        return dispatchToDisplay(ev, ws.displayId, ws.vdUniqueId, ws.contentWidth, ws.contentHeight);
    }

    public boolean sendBackToWindow(WindowSession ws) {
        if (ws == null || !ws.isLive() || ws.displayId <= 0) return false;
        boolean down = injectWindowKey(ws, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
        boolean up = injectWindowKey(ws, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
        return down || up;
    }

    /** Direct display-targeted motion injection for remote Mac OpalCast sessions. */
    public boolean injectRawEventToDisplay(InputEvent ev, int displayId, String vdUniqueId, int dispW, int dispH) {
        if (ev == null || displayId <= 0) return false;
        return dispatchToDisplay(ev, displayId, vdUniqueId, dispW, dispH);
    }

    /** Direct display-targeted key injection for remote Mac OpalCast sessions. */
    public boolean injectRawKeyToDisplay(
            int displayId, String vdUniqueId, int dispW, int dispH, int action, int keyCode, int metaState) {
        if (displayId <= 0) return false;
        services.focusDisplay(displayId);
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(
                now, now, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        return dispatchToDisplay(ev, displayId, vdUniqueId, dispW, dispH);
    }

    public void releaseDisplayResources(int displayId) {
        UhidInputDevice dev;
        synchronized (uhidLock) {
            dev = uhidByDisplay.remove(displayId);
            uhidBlockedUntilMs.remove(displayId);
        }
        if (dev != null) {
            dev.close();
        }
    }

    public void releaseAllDisplays() {
        ArrayList<UhidInputDevice> all;
        synchronized (uhidLock) {
            uhidBlockedUntilMs.clear();
            if (uhidByDisplay.isEmpty()) return;
            all = new ArrayList<>(uhidByDisplay.values());
            uhidByDisplay.clear();
        }
        for (UhidInputDevice d : all) {
            d.close();
        }
    }

    private boolean dispatchToDisplay(InputEvent ev, int displayId, String vdUniqueId, int dispW, int dispH) {
        return dispatcher.dispatch(ev, displayId, vdUniqueId, dispW, dispH);
    }

    private boolean injectViaUhid(InputEvent ev, int displayId, String vdUniqueId, int dispW, int dispH) {
        UhidInputDevice dev = ensureUhidDevice(displayId, vdUniqueId);
        return dev != null && dev.injectInputEvent(ev, Math.max(2, dispW), Math.max(2, dispH));
    }

    private UhidInputDevice ensureUhidDevice(int displayId, String vdUniqueId) {
        boolean newlyOpened = false;
        UhidInputDevice dev;
        synchronized (uhidLock) {
            dev = uhidByDisplay.get(displayId);
            if (dev != null && dev.isOpen()) {
                String curUid = dev.getVdUniqueId();
                if ((vdUniqueId == null && curUid == null) || (vdUniqueId != null && vdUniqueId.equals(curUid))) {
                    return dev;
                }
                dev.close();
                uhidByDisplay.remove(displayId);
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
}
