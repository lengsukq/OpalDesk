package io.github.lengsukq.opaldesk.daemon.stream;

import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.input.ImmsDumpInspector;
import io.github.lengsukq.opaldesk.daemon.input.InputBindingEngine;
import io.github.lengsukq.opaldesk.daemon.input.InputBindingRule;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Adapts remote OpalCast pointer, touch, keyboard, host-trackpad, IME, and system-action
 * events onto Android per-display input injection and compositor operations.
 */
final class OpalCastInputAdapter {
    private final AndroidServices services;
    private final CompositorEngine compositor;
    private final AndroidDisplayDriver displayDriver;
    private final PerDisplayInputRouter inputRouter;
    private final InputBindingEngine inputBindingEngine;
    private final RemoteStreamServer server;
    private final RemoteMediaDevicesCoordinator mediaDevices;
    private final Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions;
    private final IntSupplier desktopSessionIdSupplier;
    private final BooleanSupplier localHostTextFieldFocusedSupplier;
    private final BooleanSupplier bridgeImeActiveSupplier;
    private final ExecutorService worker;
    private final Consumer<OpalCastCompositorBridge.ActiveRemoteSession> pushDeviceState;
    private final BiConsumer<OpalCastCompositorBridge.ActiveRemoteSession, Integer> switchDesktopDisplayMode;
    private final Predicate<String> helperInputActionDispatcher;

    OpalCastInputAdapter(
            AndroidServices services,
            CompositorEngine compositor,
            AndroidDisplayDriver displayDriver,
            PerDisplayInputRouter inputRouter,
            InputBindingEngine inputBindingEngine,
            RemoteStreamServer server,
            RemoteMediaDevicesCoordinator mediaDevices,
            Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions,
            IntSupplier desktopSessionIdSupplier,
            BooleanSupplier localHostTextFieldFocusedSupplier,
            BooleanSupplier bridgeImeActiveSupplier,
            ExecutorService worker,
            Consumer<OpalCastCompositorBridge.ActiveRemoteSession> pushDeviceState,
            BiConsumer<OpalCastCompositorBridge.ActiveRemoteSession, Integer> switchDesktopDisplayMode,
            Predicate<String> helperInputActionDispatcher) {
        this.services = services;
        this.compositor = compositor;
        this.displayDriver = displayDriver;
        this.inputRouter = inputRouter;
        this.inputBindingEngine = inputBindingEngine;
        this.server = server;
        this.mediaDevices = mediaDevices;
        this.sessions = sessions;
        this.desktopSessionIdSupplier = desktopSessionIdSupplier;
        this.localHostTextFieldFocusedSupplier = localHostTextFieldFocusedSupplier;
        this.bridgeImeActiveSupplier = bridgeImeActiveSupplier;
        this.worker = worker;
        this.pushDeviceState = pushDeviceState;
        this.switchDesktopDisplayMode = switchDesktopDisplayMode;
        this.helperInputActionDispatcher = helperInputActionDispatcher != null ? helperInputActionDispatcher : action -> false;
    }

    void onBridgeImeShowRequested(int imeDisplayId, int rawInputType) {
        if (imeDisplayId <= 0) return;
        int hostType = ImmsDumpInspector.sanitizeHostInputType(rawInputType);
        for (OpalCastCompositorBridge.ActiveRemoteSession s : sessions.values()) {
            if (s != null && !s.closed && s.standalone && s.displayId == imeDisplayId) {
                server.sendImeRequest(s.sessionId, true, hostType);
                return;
            }
        }
        int deskSid = desktopSessionIdSupplier.getAsInt();
        OpalCastCompositorBridge.ActiveRemoteSession desk = (deskSid > 0) ? sessions.get(deskSid) : null;
        if (desk != null && !desk.closed && !desk.isMirror()) {
            WindowSession focused = compositor.getFocusedLiveWindow();
            if ((focused != null && focused.displayId == imeDisplayId) || desk.displayId == imeDisplayId) {
                server.sendImeRequest(deskSid, true, hostType);
            }
        }
    }

    void onBridgeImeHideRequested(int imeDisplayId) {
        if (imeDisplayId <= 0) return;
        for (OpalCastCompositorBridge.ActiveRemoteSession s : sessions.values()) {
            if (s != null && !s.closed && s.standalone && s.displayId == imeDisplayId) {
                server.sendImeRequest(s.sessionId, false, 0);
                return;
            }
        }
        int deskSid = desktopSessionIdSupplier.getAsInt();
        OpalCastCompositorBridge.ActiveRemoteSession desk = (deskSid > 0) ? sessions.get(deskSid) : null;
        if (desk != null && !desk.closed && !desk.isMirror()) {
            WindowSession focused = compositor.getFocusedLiveWindow();
            if ((focused != null && focused.displayId == imeDisplayId) || desk.displayId == imeDisplayId) {
                server.sendImeRequest(deskSid, false, 0);
            }
        }
    }

    private void scheduleStandaloneImeProbe(OpalCastCompositorBridge.ActiveRemoteSession s, long tapDownMs) {
        if (s == null || !s.standalone || s.closed || s.displayId <= 0 || bridgeImeActiveSupplier.getAsBoolean()) return;
        final long gen = ++s.imeProbeGen;
        final int targetDisplayId = s.displayId;
        worker.execute(() -> {
            for (long waitMs : new long[]{140L, 200L, 260L}) {
                SystemClock.sleep(waitMs);
                if (s.closed || s.imeProbeGen != gen || s.displayId != targetDisplayId) return;
                ImmsDumpInspector.Snapshot snap = AndroidServices.inspectInputMethodService(targetDisplayId);
                if (snap == null || s.closed || s.imeProbeGen != gen) return;
                long nowMs = SystemClock.uptimeMillis();
                if (ImmsDumpInspector.shouldTriggerRemoteCastIme(
                        snap, targetDisplayId, s.lastImeShowSeq, tapDownMs, nowMs)) {
                    s.lastImeShowSeq = snap.latestShowSeq;
                    s.imeProbeGen = gen + 1L;
                    int rawType = snap.effectiveInputType(targetDisplayId);
                    int hostType = ImmsDumpInspector.sanitizeHostInputType(rawType);
                    System.out.println("OPALDESK_STANDALONE_IME_FORWARD sid=" + s.sessionId
                            + " display=" + targetDisplayId
                            + " seq=" + snap.latestShowSeq
                            + " hostType=0x" + Integer.toHexString(hostType));
                    server.sendImeRequest(s.sessionId, true, hostType);
                    return;
                }
            }
        });
    }

    boolean injectHostTrackpadEvent(
            int action, float x, float y, float hScroll, float vScroll, int buttonState) {
        int sid = desktopSessionIdSupplier.getAsInt();
        OpalCastCompositorBridge.ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        if (s == null || s.closed || s.displayId <= 0) return false;

        float cx = Math.max(0f, Math.min(s.width - 1f, x));
        float cy = Math.max(0f, Math.min(s.height - 1f, y));
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            s.mouseDownTimeMs = now;
        }
        int masked = action & MotionEvent.ACTION_MASK;
        boolean isScroll = (masked == MotionEvent.ACTION_SCROLL);
        boolean isHover = (masked == MotionEvent.ACTION_HOVER_MOVE || masked == MotionEvent.ACTION_HOVER_EXIT);

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[]{new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        if (isScroll && !inputRouter.isUsingUhid()) {
            MotionEvent.PointerCoords hoverCoord = new MotionEvent.PointerCoords();
            hoverCoord.x = cx;
            hoverCoord.y = cy;
            hoverCoord.pressure = 0f;
            hoverCoord.size = 1f;
            MotionEvent hoverPrime = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_HOVER_MOVE,
                    1, props, new MotionEvent.PointerCoords[]{hoverCoord},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            try {
                inputRouter.injectRawEventToDisplay(hoverPrime, s.displayId, s.vdUniqueId, s.width, s.height);
            } finally {
                hoverPrime.recycle();
            }
        }

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[]{new MotionEvent.PointerCoords()};
        coords[0].x = cx;
        coords[0].y = cy;
        coords[0].pressure = (isHover || isScroll || masked == MotionEvent.ACTION_UP) ? 0f : 1f;
        coords[0].size = 1f;
        if (isScroll) {
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        }

        long downTime = (isHover || isScroll || s.mouseDownTimeMs <= 0L) ? now : s.mouseDownTimeMs;
        int effButtons = (isHover || isScroll) ? 0 : buttonState;
        MotionEvent ev = MotionEvent.obtain(
                downTime, now, action, 1, props, coords,
                0, effButtons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            return inputRouter.injectRawEventToDisplay(ev, s.displayId, s.vdUniqueId, s.width, s.height);
        } finally {
            ev.recycle();
        }
    }

    void handleMouseEvent(OpalCastCompositorBridge.ActiveRemoteSession s, OpalCastProtocol.MouseEventMsg msg) {
        if (s == null || s.companion || msg == null) return;
        int targetDisplayId = s.inputTargetDisplayId();
        if (targetDisplayId < 0) return;

        if (inputBindingEngine != null
                && (msg.action == OpalCastProtocol.MOUSE_BUTTON_DOWN
                || msg.action == OpalCastProtocol.MOUSE_BUTTON_UP)) {
            boolean isDown = (msg.action == OpalCastProtocol.MOUSE_BUTTON_DOWN);
            int buttonMask = (msg.actionButton != 0)
                    ? msg.actionButton
                    : (isDown ? msg.buttons : (s.mouseButtons & ~msg.buttons));
            InputBindingEngine.MatchResult match =
                    inputBindingEngine.onMouseButtonEvent(isDown, buttonMask, s.lastKeyMetaState);
            if (match.consumed) {
                if (match.actionId != null) {
                    executeInputBindingAction(match.actionId, s);
                }
                return;
            }
        }

        int targetW = s.inputTargetWidth();
        int targetH = s.inputTargetHeight();
        String targetUid = s.inputTargetUniqueId();
        float[] mapped = DisplayGeometryUtil.mapRemotePointToTargetDisplay(
                msg.x, msg.y, s.width, s.height, targetW, targetH);
        float x = mapped[0];
        float y = mapped[1];
        long now = SystemClock.uptimeMillis();

        int androidAction;
        if (msg.action == OpalCastProtocol.MOUSE_SCROLL) {
            androidAction = MotionEvent.ACTION_SCROLL;
        } else if (msg.action == OpalCastProtocol.MOUSE_BUTTON_DOWN) {
            s.mouseButtons = msg.buttons != 0
                    ? msg.buttons
                    : (msg.actionButton != 0 ? msg.actionButton : MotionEvent.BUTTON_PRIMARY);
            s.mouseDownTimeMs = now;
            androidAction = MotionEvent.ACTION_DOWN;
            if ((s.standalone || s.isMirror()) && !localHostTextFieldFocusedSupplier.getAsBoolean() && services != null) {
                services.focusDisplay(targetDisplayId);
            }
        } else if (msg.action == OpalCastProtocol.MOUSE_BUTTON_UP) {
            s.mouseButtons = msg.buttons;
            androidAction = MotionEvent.ACTION_UP;
        } else if (msg.action == OpalCastProtocol.MOUSE_HOVER_EXIT) {
            androidAction = MotionEvent.ACTION_HOVER_EXIT;
        } else {
            androidAction = (s.mouseButtons != 0) ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;
        }

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[]{new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[]{new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (s.mouseButtons != 0) ? 1.0f : 0.0f;
        coords[0].size = 1.0f;
        if (androidAction == MotionEvent.ACTION_SCROLL) {
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, msg.hScroll);
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, msg.vScroll);
        }

        MotionEvent ev = MotionEvent.obtain(
                s.mouseDownTimeMs > 0L ? s.mouseDownTimeMs : now,
                now, androidAction, 1, props, coords,
                0, s.mouseButtons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            inputRouter.injectRawEventToDisplay(ev, targetDisplayId, targetUid, targetW, targetH);
        } finally {
            ev.recycle();
        }
        if (s.standalone && msg.action == OpalCastProtocol.MOUSE_BUTTON_UP) {
            scheduleStandaloneImeProbe(s, s.mouseDownTimeMs > 0L ? s.mouseDownTimeMs : now);
        }
    }

    void handleTouchEvent(OpalCastCompositorBridge.ActiveRemoteSession s, OpalCastProtocol.TouchEventMsg msg) {
        if (s == null || s.companion || msg == null || msg.pointers.length == 0) return;
        int targetDisplayId = s.inputTargetDisplayId();
        if (targetDisplayId < 0) return;
        int targetW = s.inputTargetWidth();
        int targetH = s.inputTargetHeight();
        String targetUid = s.inputTargetUniqueId();
        int count = Math.min(10, msg.pointers.length);
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = msg.pointers[i].pointerId;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            float[] mapped = DisplayGeometryUtil.mapRemotePointToTargetDisplay(
                    msg.pointers[i].x, msg.pointers[i].y, s.width, s.height, targetW, targetH);
            coords[i].x = mapped[0];
            coords[i].y = mapped[1];
            coords[i].pressure = msg.pointers[i].pressure > 0f ? msg.pointers[i].pressure : 1f;
            coords[i].size = 1f;
        }
        long now = SystemClock.uptimeMillis();
        int action = msg.action;
        if (action == OpalCastProtocol.TOUCH_POINTER_DOWN) {
            action = MotionEvent.ACTION_POINTER_DOWN | (msg.actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        } else if (action == OpalCastProtocol.TOUCH_POINTER_UP) {
            action = MotionEvent.ACTION_POINTER_UP | (msg.actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        if (msg.action == OpalCastProtocol.TOUCH_DOWN) {
            s.touchDownTimeMs = now;
            if ((s.standalone || s.isMirror()) && !localHostTextFieldFocusedSupplier.getAsBoolean() && services != null) {
                services.focusDisplay(targetDisplayId);
            }
        }
        MotionEvent ev = MotionEvent.obtain(
                s.touchDownTimeMs > 0L ? s.touchDownTimeMs : now,
                now, action, count, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            inputRouter.injectRawEventToDisplay(ev, targetDisplayId, targetUid, targetW, targetH);
        } finally {
            ev.recycle();
        }
        if (s.standalone && msg.action == OpalCastProtocol.TOUCH_UP) {
            scheduleStandaloneImeProbe(s, s.touchDownTimeMs > 0L ? s.touchDownTimeMs : now);
        }
    }

    void handleKeyEvent(OpalCastCompositorBridge.ActiveRemoteSession s, OpalCastProtocol.KeyEventMsg msg) {
        if (s == null || s.companion || msg == null) return;
        int targetDisplayId = s.inputTargetDisplayId();
        if (targetDisplayId < 0) return;
        s.lastKeyMetaState = msg.metaState;
        int action = (msg.action == OpalCastProtocol.KEY_DOWN) ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

        if (inputBindingEngine != null) {
            InputBindingEngine.MatchResult match =
                    inputBindingEngine.onKeyEvent(action, msg.androidKeyCode, msg.metaState, msg.repeatCount);
            if (match.consumed) {
                if (match.actionId != null) {
                    executeInputBindingAction(match.actionId, s);
                }
                return;
            }
        }

        if (!s.standalone && !s.isMirror() && !localHostTextFieldFocusedSupplier.getAsBoolean()
                && !isWorkstationShortcut(msg.androidKeyCode, msg.metaState)) {
            WindowSession focused = compositor.getFocusedLiveWindow();
            if (focused != null) {
                inputRouter.injectWindowKey(focused, action, msg.androidKeyCode, msg.metaState, 1, 0, msg.repeatCount);
                return;
            }
        }
        inputRouter.injectRawKeyToDisplay(
                targetDisplayId, s.inputTargetUniqueId(), s.inputTargetWidth(), s.inputTargetHeight(),
                action, msg.androidKeyCode, msg.metaState, true);
    }

    boolean executeInputBindingAction(
            String actionId, OpalCastCompositorBridge.ActiveRemoteSession sourceSession) {
        if (actionId == null || actionId.trim().isEmpty()) return false;
        String act = actionId.trim().toLowerCase(Locale.ROOT);
        OpalCastCompositorBridge.ActiveRemoteSession s = sourceSession;
        if (s == null) {
            int deskSid = desktopSessionIdSupplier.getAsInt();
            s = (deskSid > 0) ? sessions.get(deskSid) : null;
        }
        switch (act) {
            case InputBindingRule.ACTION_BACK:
                if (s != null && !s.closed && (s.isMirror() || s.standalone)) {
                    injectKeyClick(s.inputTargetDisplayId(), s.inputTargetUniqueId(),
                            s.inputTargetWidth(), s.inputTargetHeight(), KeyEvent.KEYCODE_BACK);
                    return true;
                }
                WindowSession focusedBack = compositor.getFocusedLiveWindow();
                if (focusedBack != null && inputRouter.sendBackToWindow(focusedBack)) {
                    compositor.notifyBackSent(focusedBack.windowId);
                    return true;
                }
                if (s != null && !s.closed && s.inputTargetDisplayId() >= 0) {
                    injectKeyClick(s.inputTargetDisplayId(), s.inputTargetUniqueId(),
                            s.inputTargetWidth(), s.inputTargetHeight(), KeyEvent.KEYCODE_BACK);
                    return true;
                }
                return false;
            case InputBindingRule.ACTION_TOGGLE_MAXIMIZE: {
                WindowSession focused = compositor.getFocusedLiveWindow();
                return focused != null && compositor.performWindowAction(
                        focused.windowId, BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE, null);
            }
            case InputBindingRule.ACTION_TOGGLE_FULLSCREEN: {
                WindowSession focused = compositor.getFocusedLiveWindow();
                return focused != null && compositor.performWindowAction(
                        focused.windowId, BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN, null);
            }
            case InputBindingRule.ACTION_SNAP_LEFT:
                return compositor.snapFocusedWindow(true);
            case InputBindingRule.ACTION_SNAP_RIGHT:
                return compositor.snapFocusedWindow(false);
            case InputBindingRule.ACTION_TILE_2_SPLIT:
                return compositor.applyQuickSplitTwo();
            case InputBindingRule.ACTION_CLOSE_WINDOW: {
                WindowSession focused = compositor.getFocusedLiveWindow();
                if (focused == null) return false;
                inputRouter.releaseAllHeldKeys();
                return compositor.performWindowAction(
                        focused.windowId, BridgeProtocol.WIN_ACTION_CLOSE, null);
            }
            case InputBindingRule.ACTION_MINIMIZE_WINDOW: {
                WindowSession focused = compositor.getFocusedLiveWindow();
                if (focused == null) return false;
                inputRouter.releaseAllHeldKeys();
                return compositor.performWindowAction(
                        focused.windowId, BridgeProtocol.WIN_ACTION_MINIMIZE, null);
            }
            case InputBindingRule.ACTION_SWITCH_WINDOW:
                inputRouter.releaseAllHeldKeys();
                return compositor.switchNextWindow();
            case InputBindingRule.ACTION_SPOTLIGHT:
                if (helperInputActionDispatcher.test(InputBindingRule.ACTION_SPOTLIGHT)) {
                    return true;
                }
                if (s != null && !s.closed && s.inputTargetDisplayId() >= 0) {
                    injectKeyClick(s.inputTargetDisplayId(), s.inputTargetUniqueId(),
                            s.inputTargetWidth(), s.inputTargetHeight(), KeyEvent.KEYCODE_SEARCH);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    static boolean isWorkstationShortcut(int keyCode, int metaState) {
        boolean alt = (metaState & (KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON)) != 0;
        boolean ctrl = (metaState & (KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON)) != 0;
        if (alt && (keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_2
                || keyCode == KeyEvent.KEYCODE_3 || keyCode == KeyEvent.KEYCODE_4
                || keyCode == KeyEvent.KEYCODE_G)) {
            return true;
        }
        return ctrl && keyCode == KeyEvent.KEYCODE_W;
    }

    void handleSystemAction(int sessionId, OpalCastCompositorBridge.ActiveRemoteSession s, OpalCastProtocol.SystemActionMsg msg) {
        if (s == null || msg == null) return;
        if (s.companion) {
            if (msg.action == OpalCastProtocol.SYS_DISCONNECT) {
                server.disconnectSession(sessionId, "client_system_disconnect");
            }
            return;
        }
        switch (msg.action) {
            case OpalCastProtocol.SYS_BACK:
                if (s.isMirror()) {
                    injectKeyClick(Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(), KeyEvent.KEYCODE_BACK);
                } else if (!s.standalone) {
                    WindowSession focused = compositor.getFocusedLiveWindow();
                    if (focused != null && inputRouter.sendBackToWindow(focused)) {
                        compositor.notifyBackSent(focused.windowId);
                    }
                } else {
                    injectKeyClick(s.displayId, s.vdUniqueId, s.width, s.height, KeyEvent.KEYCODE_BACK);
                    final int checkDisplayId = s.displayId;
                    final String checkPkg = s.appPackage;
                    final int checkUserId = s.appUserId;
                    worker.execute(() -> {
                        for (long waitMs : new long[]{260L, 320L}) {
                            SystemClock.sleep(waitMs);
                            if (s.closed || s.displayId != checkDisplayId) return;
                            int visibleTid = displayDriver.findTopVisibleTaskIdOnDisplay(checkDisplayId, checkPkg, checkUserId);
                            if (visibleTid <= 0) {
                                System.out.println("OPALDESK_STANDALONE_BACK_EXITED sid=" + sessionId
                                        + " pkg=" + checkPkg + " user=" + checkUserId);
                                server.disconnectSession(sessionId, "app_exited");
                                return;
                            }
                        }
                    });
                }
                break;
            case OpalCastProtocol.SYS_HOME:
                if (s.isMirror()) {
                    injectKeyClick(Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(), KeyEvent.KEYCODE_HOME);
                } else if (!s.standalone) {
                    if (!compositor.exitFullscreenIfActive()) {
                        compositor.minimizeAllWindows();
                    }
                }
                break;
            case OpalCastProtocol.SYS_RECENTS:
                if (s.isMirror()) {
                    injectKeyClick(Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(), KeyEvent.KEYCODE_APP_SWITCH);
                }
                break;
            case OpalCastProtocol.SYS_TOGGLE_DISPLAY_MODE:
                if (!s.standalone) {
                    int targetMode = DisplayGeometryUtil.resolveTargetDisplayMode(s.displayMode, msg.arg);
                    worker.execute(() -> switchDesktopDisplayMode.accept(s, targetMode));
                }
                break;
            case OpalCastProtocol.SYS_TOGGLE_BLACKOUT:
                if (!s.standalone) {
                    boolean powerOn = (msg.arg == 0);
                    services.setPrimaryPhysicalDisplayPower(powerOn);
                    pushDeviceState.accept(s);
                }
                break;
            case OpalCastProtocol.SYS_SET_AUDIO_ENABLED:
                s.audioRequested = (msg.arg != 0);
                worker.execute(() -> {
                    mediaDevices.reconcileAudioOwner();
                    pushDeviceState.accept(s);
                });
                break;
            case OpalCastProtocol.SYS_DISCONNECT:
                server.disconnectSession(sessionId, "client_system_disconnect");
                break;
            default:
                break;
        }
    }

    private void injectKeyClick(int displayId, String uniqueId, int width, int height, int keyCode) {
        inputRouter.injectRawKeyToDisplay(displayId, uniqueId, width, height, KeyEvent.ACTION_DOWN, keyCode, 0);
        inputRouter.injectRawKeyToDisplay(displayId, uniqueId, width, height, KeyEvent.ACTION_UP, keyCode, 0);
    }
}
