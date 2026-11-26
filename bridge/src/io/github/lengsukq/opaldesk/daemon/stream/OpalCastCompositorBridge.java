package io.github.lengsukq.opaldesk.daemon.stream;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.audio.AudioStreamCapturer;
import io.github.lengsukq.opaldesk.daemon.audio.RemoteMicInjector;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.input.ExternalMouseRouter;
import io.github.lengsukq.opaldesk.daemon.input.ImmsDumpInspector;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.TaskApiAdapter;
import io.github.lengsukq.opaldesk.daemon.platform.VirtualDisplayFactory;
import io.github.lengsukq.opaldesk.daemon.security.PairingApprovalCoordinator;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;
import io.github.lengsukq.opaldesk.daemon.sink.RemoteStreamSink;
import io.github.lengsukq.opaldesk.daemon.video.RootCameraBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Clean OpalCast v1.1 MacOS Streaming Bridge wired directly onto the v1.0.0 Universal Compositor.
 *
 * <p>Supports USB ADB ({@code @opaldesk_stream}) and on-demand Wi-Fi TLS ({@code :19878}) with
 * 6-digit PIN + ECDSA P-256 trust store, H.264/HEVC hardware encoding, live resolution/FPS/DPI
 * resize, system audio, bidirectional clipboard, file transfer, and standalone app pop-outs.
 */
public final class OpalCastCompositorBridge implements RemoteStreamServer.SessionListener {
    private static final int DEFAULT_MAX_BITRATE_KBPS = 28_000;
    private static final File TRUST_FILE = new File("/data/local/tmp/opaldesk_trusted_clients.conf");
    private static final File CERT_FILE = new File("/data/local/tmp/opaldesk_tls_cert.p12");

    public static final class RemoteStatusSnapshot {
        public final boolean connected;
        public final int sinkType;
        public final String peerName;
        public final int width;
        public final int height;
        public final int fpsX100;
        public final int bitrateKbps;
        public final boolean wifiEnabled;
        public final int wifiPort;
        public final String certFingerprint;
        public final boolean activeSessionPermanent;
        public final int trustedCount;
        public final String lanIp;
        public final int desktopDisplayId;

        public RemoteStatusSnapshot(
                boolean connected, int sinkType, String peerName,
                int width, int height, int fpsX100, int bitrateKbps,
                boolean wifiEnabled, int wifiPort, String certFingerprint,
                boolean activeSessionPermanent, int trustedCount, String lanIp,
                int desktopDisplayId) {
            this.connected = connected;
            this.sinkType = sinkType;
            this.peerName = peerName != null ? peerName : "";
            this.width = width;
            this.height = height;
            this.fpsX100 = fpsX100;
            this.bitrateKbps = bitrateKbps;
            this.wifiEnabled = wifiEnabled;
            this.wifiPort = wifiPort;
            this.certFingerprint = certFingerprint != null ? certFingerprint : "";
            this.activeSessionPermanent = activeSessionPermanent;
            this.trustedCount = Math.max(0, trustedCount);
            this.lanIp = lanIp != null ? lanIp : "";
            this.desktopDisplayId = desktopDisplayId;
        }
    }

    private static final class ActiveRemoteSession {
        final int sessionId;
        final int sinkType;
        final boolean standalone;
        final String appPackage;
        final String appActivity;
        final Object lock = new Object();

        volatile int width = 1920;
        volatile int height = 1080;
        volatile int dpi = 220;
        volatile float fps = 60f;
        volatile float requestedNativeFps = 60f;
        volatile int bitrateKbps = 12_000;
        volatile int codecPref = RemoteStreamSink.CODEC_PREF_AUTO_HEVC;
        volatile int displayId = -1;
        volatile String vdUniqueId = "";
        volatile VirtualDisplay vd;
        volatile RemoteStreamSink sink;
        volatile ClipboardSyncService clipboard;
        volatile FileTransferReceiver fileReceiver;
        volatile long mouseDownTimeMs = 0L;
        volatile long touchDownTimeMs = 0L;
        volatile int mouseButtons = 0;
        volatile int lastImeShowSeq = -1;
        volatile long imeProbeGen = 0L;
        volatile boolean closed = false;

        ActiveRemoteSession(int sessionId, int sinkType, boolean standalone, String pkg, String cls) {
            this.sessionId = sessionId;
            this.sinkType = sinkType;
            this.standalone = standalone;
            this.appPackage = pkg != null ? pkg : "";
            this.appActivity = cls != null ? cls : "";
        }
    }

    private final AndroidServices services;
    private final DaemonConfig config;
    private final CompositorEngine compositor;
    private final AndroidDisplayDriver displayDriver;
    private final PerDisplayInputRouter inputRouter;
    private final ExternalMouseRouter mouseRouter;
    private final TrustStoreManager trustStore;
    private final RemoteStreamServer server;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "OpalCastWorker");
        t.setDaemon(true);
        return t;
    });

    private final Map<Integer, ActiveRemoteSession> sessions = new ConcurrentHashMap<>();
    private volatile int desktopSessionId = -1;
    private volatile boolean localHostTextFieldFocused = false;
    private volatile AudioStreamCapturer audioCapturer;
    private final Object micLock = new Object();
    private RemoteMicInjector micInjector;
    private volatile int micOwnerSessionId = -1;
    private final Object cameraLock = new Object();
    private RootCameraBridge cameraBridge;
    private volatile int cameraOwnerSessionId = -1;
    private volatile Runnable remoteStateChangeListener;

    public OpalCastCompositorBridge(
            AndroidServices services,
            DaemonConfig config,
            CompositorEngine compositor,
            AndroidDisplayDriver displayDriver,
            PerDisplayInputRouter inputRouter,
            ExternalMouseRouter mouseRouter) {
        this.services = services;
        this.config = config;
        this.compositor = compositor;
        this.displayDriver = displayDriver;
        this.inputRouter = inputRouter;
        this.mouseRouter = mouseRouter;
        this.trustStore = new TrustStoreManager(TRUST_FILE);
        String devName = (Build.MODEL != null && !Build.MODEL.trim().isEmpty())
                ? Build.MODEL.trim() : "Android Device";
        this.server = new RemoteStreamServer(this, trustStore, CERT_FILE, devName);
        if (this.inputRouter != null) {
            this.inputRouter.setInjectStatusListener(newStatus -> worker.execute(() -> {
                for (ActiveRemoteSession s : new ArrayList<>(sessions.values())) {
                    if (s != null && !s.closed) {
                        pushDeviceState(s);
                    }
                }
                notifyRemoteStateChanged();
            }));
        }
    }

    public void setLocalHostTextFieldFocused(boolean focused) {
        this.localHostTextFieldFocused = focused;
    }

    public void setPairingEventConsumer(Consumer<String> consumer) {
        if (consumer == null) {
            server.setPairingChangeListener(null);
            return;
        }
        server.setPairingChangeListener((PairingApprovalCoordinator.PendingRequest req) ->
                consumer.accept(req != null ? req.toJson() : ""));
    }

    public void setRemoteStateChangeListener(Runnable listener) {
        this.remoteStateChangeListener = listener;
    }

    private void notifyRemoteStateChanged() {
        Runnable r = remoteStateChangeListener;
        if (r != null) {
            try { r.run(); } catch (RuntimeException ignored) {}
        }
    }

    public String getTrustedPeersJson() {
        return server.getTrustedPeersJson();
    }

    public boolean revokeTrustedClient(String clientId) {
        return server.revokeTrustedClient(clientId);
    }

    public String getPendingPairingJson() {
        return server.getPendingPairingJson();
    }

    public boolean respondToPairing(String requestId, int decision) {
        return server.respondToPairing(requestId, decision);
    }

    public void start() {
        server.startUsbListener();
    }

    public void shutdown() {
        stopAudio();
        stopRemoteMic(false);
        stopRemoteCamera(false, true);
        if (mouseRouter != null) {
            mouseRouter.releaseToLocal();
        }
        for (ActiveRemoteSession s : new ArrayList<>(sessions.values())) {
            closeSessionResources(s);
        }
        sessions.clear();
        desktopSessionId = -1;
        server.shutdown();
    }

    public RemoteStatusSnapshot getStatusSnapshot() {
        ActiveRemoteSession main = (desktopSessionId > 0) ? sessions.get(desktopSessionId) : null;
        if (main == null && !sessions.isEmpty()) {
            main = sessions.values().iterator().next();
        }
        boolean active = server.hasActiveSession() && main != null;
        int w = active ? main.width : config.getCanvasWidth();
        int h = active ? main.height : config.getCanvasHeight();
        int fpsX100 = active ? Math.round(main.fps * 100f) : Math.round(config.getTargetFps() * 100f);
        int kbps = (active && main.sink != null) ? main.sink.getBitrateKbps() : 0;
        String lanIp = RemoteStreamServer.detectLanIpv4Address();
        return new RemoteStatusSnapshot(
                active,
                active ? server.getActiveSinkType() : DisplaySink.SINK_NONE,
                active ? server.getActivePeerName() : "",
                w, h, fpsX100, kbps,
                server.isWifiEnabled(),
                server.getWifiPort(),
                server.getCertFingerprint(),
                active && server.isActiveSessionPermanent(),
                server.getTrustedCount(),
                lanIp != null ? lanIp : "",
                getActiveDesktopDisplayId());
    }

    public RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int port) {
        return server.configureWifi(enable, port > 0 ? port : RemoteStreamServer.DEFAULT_WIFI_PORT);
    }

    public void disconnectActiveClient() {
        server.disconnectActiveClient("user_requested");
    }

    /**
     * Reconfigures the active remote desktop session (USB / Wi-Fi cast) using the exact same
     * resolution & refresh-rate control surface as physical USB-C / HDMI external monitors.
     */
    public boolean reconfigureActiveDesktop(int reqW, int reqH, float reqFps) {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        if (s == null || s.closed) return false;
        int targetW = reqW > 0 ? reqW : s.width;
        int targetH = reqH > 0 ? reqH : s.height;
        int fpsX100 = reqFps > 0f ? Math.round(reqFps * 100f) : Math.round(s.fps * 100f);
        OpalCastProtocol.ResizeConfig cfg = new OpalCastProtocol.ResizeConfig(
                targetW, targetH, s.dpi, fpsX100, s.bitrateKbps,
                OpalCastProtocol.DISPLAY_MODE_UNCHANGED, 0xFF);
        worker.execute(() -> reconfigureSession(s, cfg));
        return true;
    }

    public boolean hasActiveDesktopSession() {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        return s != null && !s.closed;
    }

    public int getActiveDesktopDisplayId() {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        return (s != null && !s.closed) ? s.displayId : -1;
    }

    /** Forwards a soft-keyboard show/hide request to the active remote desktop receiver (e.g. Tablet). */
    public void sendDesktopImeRequest(boolean show, int inputType) {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        if (s != null && !s.closed) {
            server.sendImeRequest(sid, show, inputType);
        }
    }

    private void scheduleStandaloneImeProbe(ActiveRemoteSession s, long tapDownMs) {
        if (s == null || !s.standalone || s.closed || s.displayId <= 0) return;
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

    public float getActiveNativeFps() {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        return (s != null && !s.closed && s.requestedNativeFps > 0f) ? s.requestedNativeFps : 60f;
    }

    public void applyTargetFpsIfActive(int targetFps) {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        if (s == null || s.closed) return;
        float nextFps = (targetFps > 0) ? (float) targetFps : s.requestedNativeFps;
        if (Math.abs(nextFps - s.fps) < 0.5f) return;
        reconfigureActiveDesktop(0, 0, nextFps);
    }

    /**
     * Injects Display 0 host trackpad pointer/click/drag/scroll events directly onto the active
     * remote OpalCast desktop VirtualDisplay so the host phone works as a live DeX touchpad
     * while casting wirelessly or over USB.
     */
    public boolean injectHostTrackpadEvent(
            int action, float x, float y, float hScroll, float vScroll, int buttonState) {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
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

        if (isScroll) {
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

    // ---- RemoteStreamServer.SessionListener ----

    @Override
    public void onSessionConnected(
            int sessionId, int sinkType, String clientId, String clientName, OpalCastProtocol.HelloMsg hello) {
        worker.execute(() -> setupSession(sessionId, sinkType, hello));
    }

    @Override
    public void onSessionDisconnected(int sessionId, int sinkType, String sessionRole, String reason) {
        ActiveRemoteSession s = sessions.remove(sessionId);
        if (sessionId == micOwnerSessionId) {
            stopRemoteMic(false);
        }
        if (sessionId == cameraOwnerSessionId) {
            stopRemoteCamera(false);
        }
        if (sessionId == desktopSessionId) {
            desktopSessionId = -1;
            localHostTextFieldFocused = false;
            stopAudio();
            if (mouseRouter != null) {
                mouseRouter.releaseToLocal();
            }
            if (services.isHostScreenOff()) {
                services.setPrimaryPhysicalDisplayPower(true);
            }
        }
        if (s != null) {
            closeSessionResources(s);
            if (s.standalone) {
                worker.execute(this::onCompositorWindowsChanged);
            }
        }
        notifyRemoteStateChanged();
    }

    @Override
    public void onResizeConfig(int sessionId, OpalCastProtocol.ResizeConfig cfg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || cfg == null) return;
        worker.execute(() -> reconfigureSession(s, cfg));
    }

    @Override
    public void onRequestIdr(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) {
            s.sink.requestKeyFrame();
        }
    }

    @Override
    public void onMouseEvent(int sessionId, OpalCastProtocol.MouseEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.displayId <= 0) return;
        float x = Math.max(0f, Math.min(s.width - 1f, msg.x));
        float y = Math.max(0f, Math.min(s.height - 1f, msg.y));
        long now = SystemClock.uptimeMillis();

        if (msg.action == OpalCastProtocol.MOUSE_BUTTON_DOWN
                && (msg.actionButton == OpalCastProtocol.BUTTON_SECONDARY
                || msg.actionButton == OpalCastProtocol.BUTTON_BACK)) {
            inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
            inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
            return;
        }

        int androidAction;
        int source = InputDevice.SOURCE_MOUSE;
        if (msg.action == OpalCastProtocol.MOUSE_SCROLL) {
            androidAction = MotionEvent.ACTION_SCROLL;
        } else if (msg.action == OpalCastProtocol.MOUSE_BUTTON_DOWN) {
            s.mouseButtons = msg.buttons != 0 ? msg.buttons : MotionEvent.BUTTON_PRIMARY;
            s.mouseDownTimeMs = now;
            androidAction = MotionEvent.ACTION_DOWN;
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
                0, s.mouseButtons, 1f, 1f, 0, 0, source, 0);
        try {
            inputRouter.injectRawEventToDisplay(ev, s.displayId, s.vdUniqueId, s.width, s.height);
        } finally {
            ev.recycle();
        }
        if (s.standalone && msg.action == OpalCastProtocol.MOUSE_BUTTON_UP) {
            scheduleStandaloneImeProbe(s, s.mouseDownTimeMs > 0L ? s.mouseDownTimeMs : now);
        }
    }

    @Override
    public void onTouchEvent(int sessionId, OpalCastProtocol.TouchEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || msg.pointers.length == 0 || s.displayId <= 0) return;
        int count = Math.min(10, msg.pointers.length);
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = msg.pointers[i].pointerId;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = Math.max(0f, Math.min(s.width - 1f, msg.pointers[i].x));
            coords[i].y = Math.max(0f, Math.min(s.height - 1f, msg.pointers[i].y));
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
        if (msg.action == OpalCastProtocol.TOUCH_DOWN) s.touchDownTimeMs = now;
        MotionEvent ev = MotionEvent.obtain(
                s.touchDownTimeMs > 0L ? s.touchDownTimeMs : now,
                now, action, count, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            inputRouter.injectRawEventToDisplay(ev, s.displayId, s.vdUniqueId, s.width, s.height);
        } finally {
            ev.recycle();
        }
        if (s.standalone && msg.action == OpalCastProtocol.TOUCH_UP) {
            scheduleStandaloneImeProbe(s, s.touchDownTimeMs > 0L ? s.touchDownTimeMs : now);
        }
    }

    @Override
    public void onKeyEvent(int sessionId, OpalCastProtocol.KeyEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.displayId <= 0) return;
        int action = (msg.action == OpalCastProtocol.KEY_DOWN) ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        if (!s.standalone && !localHostTextFieldFocused && !isWorkstationShortcut(msg.androidKeyCode, msg.metaState)) {
            WindowSession focused = compositor.getFocusedLiveWindow();
            if (focused != null) {
                inputRouter.injectWindowKey(focused, action, msg.androidKeyCode, msg.metaState);
                return;
            }
        }
        inputRouter.injectRawKeyToDisplay(
                s.displayId, s.vdUniqueId, s.width, s.height, action, msg.androidKeyCode, msg.metaState);
    }

    private static boolean isWorkstationShortcut(int keyCode, int metaState) {
        boolean alt = (metaState & (KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON)) != 0;
        boolean ctrl = (metaState & (KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON)) != 0;
        if (alt && (keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_2
                || keyCode == KeyEvent.KEYCODE_3 || keyCode == KeyEvent.KEYCODE_4
                || keyCode == KeyEvent.KEYCODE_G)) {
            return true;
        }
        return ctrl && keyCode == KeyEvent.KEYCODE_W;
    }

    @Override
    public void onTextEvent(int sessionId, OpalCastProtocol.TextEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.clipboard == null) return;
        s.clipboard.handleText(msg);
    }

    @Override
    public void onImageClipboardEvent(int sessionId, OpalCastProtocol.ImageClipboardEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.clipboard == null) return;
        s.clipboard.handleImageClipboard(msg);
    }

    @Override
    public void onSystemAction(int sessionId, OpalCastProtocol.SystemActionMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null) return;
        switch (msg.action) {
            case OpalCastProtocol.SYS_BACK:
                if (!s.standalone) {
                    // Forward Back exclusively to the focused live OpalWin window (never exit ExternalDesktopActivity
                    // onto a blank remote display when no app window is open!).
                    WindowSession focused = compositor.getFocusedLiveWindow();
                    if (focused != null) {
                        if (inputRouter.sendBackToWindow(focused)) {
                            compositor.notifyBackSent(focused.windowId);
                        }
                    }
                } else {
                    inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
                    inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
                    final int checkDisplayId = s.displayId;
                    final String checkPkg = s.appPackage;
                    worker.execute(() -> {
                        for (long waitMs : new long[]{260L, 320L}) {
                            SystemClock.sleep(waitMs);
                            if (s.closed || s.displayId != checkDisplayId) return;
                            int visibleTid = displayDriver.findTopVisibleTaskIdOnDisplay(checkDisplayId, checkPkg);
                            if (visibleTid <= 0) {
                                System.out.println("OPALDESK_STANDALONE_BACK_EXITED sid=" + sessionId + " pkg=" + checkPkg);
                                server.disconnectSession(sessionId, "app_exited");
                                return;
                            }
                        }
                    });
                }
                break;
            case OpalCastProtocol.SYS_HOME:
                if (!s.standalone) {
                    if (!compositor.exitFullscreenIfActive()) {
                        compositor.minimizeAllWindows();
                    }
                }
                break;
            case OpalCastProtocol.SYS_TOGGLE_BLACKOUT:
                if (!s.standalone) {
                    boolean powerOn = (msg.arg == 0);
                    services.setPrimaryPhysicalDisplayPower(powerOn);
                    pushDeviceState(s);
                }
                break;
            case OpalCastProtocol.SYS_SET_AUDIO_ENABLED:
                if (!s.standalone) {
                    if (msg.arg != 0) startAudioForDesktop(sessionId); else stopAudio();
                }
                break;
            case OpalCastProtocol.SYS_DISCONNECT:
                server.disconnectSession(sessionId, "client_system_disconnect");
                break;
            default:
                break;
        }
    }

    @Override
    public void onFileTransferChunk(int sessionId, OpalCastProtocol.FileTransferChunkMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.fileReceiver != null) {
            s.fileReceiver.handleFileTransferChunk(msg);
        }
    }

    @Override
    public void onListApps(int sessionId) {
        worker.execute(() -> {
            List<OpalCastProtocol.AppEntry> entries = queryInstalledApps();
            server.sendAppList(sessionId, new OpalCastProtocol.AppListMsg(entries));
        });
    }

    @Override
    public void onWindowAction(int sessionId, OpalCastProtocol.WindowActionMsg msg) {
        if (msg == null || msg.action.isEmpty()) return;
        String act = msg.action.toLowerCase(Locale.ROOT);
        if ("launch".equals(act) || "focus".equals(act) || "open".equals(act)) {
            if (!msg.pkg.isEmpty()) {
                compositor.openOrFocusWindow(msg.pkg, msg.cls, BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
            }
        } else if ("close_all".equals(act)) {
            compositor.closeAllWindows(true);
        }
    }

    @Override
    public void onMicConfig(int sessionId, OpalCastProtocol.MicConfigMsg msg) {
        if (msg == null) return;
        worker.execute(() -> handleMicConfig(sessionId, msg));
    }

    @Override
    public void onMicFrame(int sessionId, byte[] pcmData, long timestampUs) {
        if (sessionId != micOwnerSessionId || pcmData == null || pcmData.length == 0) return;
        RemoteMicInjector inj = micInjector;
        if (inj != null) {
            inj.feedPcm(pcmData);
        }
    }

    @Override
    public void onCameraConfig(int sessionId, OpalCastProtocol.CameraConfigMsg msg) {
        if (msg == null) return;
        worker.execute(() -> handleCameraConfig(sessionId, msg));
    }

    @Override
    public void onCameraFrame(int sessionId, byte[] jpegData, long timestampUs) {
        if (sessionId != cameraOwnerSessionId || jpegData == null || jpegData.length == 0) return;
        RootCameraBridge bridge = cameraBridge;
        if (bridge != null) {
            bridge.feedJpeg(jpegData);
        }
    }

    @Override
    public void onWifiUpgradeEnabled() {
        System.out.println("OPALDESK_REMOTE_WIFI_UPGRADE_ENABLED port=" + server.getWifiPort());
    }

    @Override
    public void onVideoCongestion(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) s.sink.onNetworkCongestion();
    }

    @Override
    public void onVideoQueueHealthy(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) s.sink.maybeRecoverBitrate();
    }

    // ---- Internal lifecycle ----

    private void setupSession(int sessionId, int sinkType, OpalCastProtocol.HelloMsg hello) {
        if (hello != null && hello.isAppCatalog()) {
            // Lightweight app-catalog probe session: no VirtualDisplay or encoder needed.
            System.out.println("OPALDESK_REMOTE_CATALOG_SESSION_READY sid=" + sessionId);
            return;
        }
        boolean standalone = hello.isStandaloneApp();
        ActiveRemoteSession s = new ActiveRemoteSession(
                sessionId, sinkType, standalone, hello.appPackage, hello.appActivity);
        synchronized (s.lock) {
            if (standalone) {
                int[] spec = resolveStandaloneStreamSpec(s.appPackage, hello.width, hello.height, hello.dpi);
                s.width = spec[0];
                s.height = spec[1];
                s.dpi = spec[2];
            } else {
                int[] aligned = DisplayGeometryUtil.alignForEncoder(hello.width, hello.height, 8);
                s.width = aligned[0];
                s.height = aligned[1];
                s.dpi = DisplayGeometryUtil.clampDpi(hello.dpi, s.width, s.height);
            }
            float nativeFps = DisplayGeometryUtil.clampFps(hello.fps > 0 ? hello.fps : 60f, 60f);
            s.requestedNativeFps = nativeFps;
            int cfgFps = config.getTargetFps();
            s.fps = DisplayGeometryUtil.clampFps(cfgFps > 0 ? cfgFps : nativeFps, nativeFps);
            int recommendedBitrate = computeRecommendedBitrateKbps(s.width, s.height, s.fps);
            s.bitrateKbps = DisplayGeometryUtil.clampBitrateKbps(
                    Math.max(hello.bitrateKbps, recommendedBitrate), 2500, DEFAULT_MAX_BITRATE_KBPS);
            s.codecPref = "h264".equalsIgnoreCase(hello.preferredCodec)
                    ? RemoteStreamSink.CODEC_PREF_H264 : RemoteStreamSink.CODEC_PREF_AUTO_HEVC;

            RemoteStreamSink sink = new RemoteStreamSink(sinkType, new RemoteStreamSink.FrameListener() {
                @Override
                public void onVideoConfig(OpalCastProtocol.VideoConfig cfg, long tsUs) {
                    server.sendVideoConfig(sessionId, cfg, tsUs);
                }

                @Override
                public void onVideoFrame(byte[] annexB, int flags, long tsUs) {
                    server.sendVideoFrame(sessionId, annexB, flags, tsUs);
                }

                @Override
                public void onEncoderError(String reason) {
                    System.err.println("OPALDESK_REMOTE_ENC_ERR sid=" + sessionId + ": " + reason);
                }
            });
            sink.setMaxBitrateKbps(DEFAULT_MAX_BITRATE_KBPS);
            Surface codecSurf = sink.startOrReconfigure(
                    s.width, s.height, s.dpi, s.fps, s.bitrateKbps,
                    s.codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, 0);
            if (codecSurf == null) {
                server.disconnectSession(sessionId, "encoder_start_failed");
                return;
            }
            s.sink = sink;
            s.width = sink.getWidth();
            s.height = sink.getHeight();
            s.dpi = sink.getDpi();

            String vdName = standalone ? ("OpalDesk-App-" + s.appPackage) : "OpalDesk-Remote";
            int dcor = BridgeProtocol.VD_FLAG_DESTROY_CONTENT_ON_REMOVAL;
            int[] candidates = standalone
                    ? new int[]{
                            BridgeProtocol.VD_FLAGS_TRUSTED_IME | dcor,
                            BridgeProtocol.VD_FLAGS_TRUSTED_IME,
                            BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor,
                            BridgeProtocol.VD_FLAGS_BASE_PUBLIC | dcor,
                            BridgeProtocol.VD_FLAGS_BASE_PUBLIC
                    }
                    : new int[]{
                            BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor,
                            BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS,
                            BridgeProtocol.VD_FLAGS_BASE_PUBLIC | dcor,
                            BridgeProtocol.VD_FLAGS_BASE_PUBLIC
                    };
            // Always omit requestedRefreshRate (0f) on the remote VirtualDisplay: on Android 14-16
            // pinning 60Hz causes SurfaceFlinger to stall output to 0 FPS during 120Hz Display 0
            // Touch Boost, while pinning 120Hz causes jitter when Display 0 idles at 60Hz. Leaving
            // it 0f lets SurfaceFlinger pace smoothly across every pacesetter refresh transition.
            VirtualDisplay vd = null;
            if (standalone) {
                RootCameraBridge cam = this.cameraBridge;
                if (cam != null && cam.hasAospVirtualCamera()) {
                    for (int candFlags : candidates) {
                        VirtualDisplay customVd = cam.createVirtualDisplay(
                                vdName, s.width, s.height, s.dpi, candFlags, 0f, codecSurf);
                        if (customVd != null && customVd.getDisplay() != null) {
                            vd = customVd;
                            break;
                        }
                    }
                }
            }
            if (vd == null) {
                vd = VirtualDisplayFactory.createWithFlagFallback(
                        services.displayManager, vdName, s.width, s.height, s.dpi, candidates, 0f, codecSurf, null);
            }
            if (vd == null || vd.getDisplay() == null) {
                sink.stop();
                server.disconnectSession(sessionId, "virtual_display_failed");
                return;
            }
            Display disp = vd.getDisplay();
            s.vd = vd;
            s.displayId = disp.getDisplayId();
            String uid = VirtualDisplayFactory.getUniqueId(disp);
            if (uid == null || uid.isEmpty()) {
                uid = services.getDisplayUniqueId(s.displayId);
            }
            s.vdUniqueId = uid != null ? uid : "";
            services.configureVirtualDisplayWindowing(s.displayId, BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY);

            s.clipboard = new ClipboardSyncService(new ClipboardSyncService.ClipboardHost() {
                @Override public void sendClipboardPush(long seq, String text) {
                    server.sendClipboardPush(sessionId, seq, text);
                }
                @Override public void sendClipboardImagePush(long seq, byte[] pngData) {
                    server.sendClipboardImagePush(sessionId, seq, pngData);
                }
                @Override public void triggerPasteKey() {
                    int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                    if (!s.standalone && !localHostTextFieldFocused) {
                        WindowSession focused = compositor.getFocusedLiveWindow();
                        if (focused != null) {
                            inputRouter.injectWindowKey(focused, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl);
                            inputRouter.injectWindowKey(focused, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl);
                            return;
                        }
                    }
                    inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl);
                    inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl);
                }
            }, null, !standalone);
            s.fileReceiver = new FileTransferReceiver((transferId, status, pathOrError) ->
                    server.sendFileTransferAck(sessionId, transferId, status, pathOrError));

            sessions.put(sessionId, s);
            if (!standalone) {
                desktopSessionId = sessionId;
                launchRemoteDesktopHost(s.displayId);
                if (mouseRouter != null) {
                    mouseRouter.setMouseTarget(config.getMouseTargetMode(), s.displayId);
                }
                if (hello.audioEnabled) {
                    startAudioForDesktop(sessionId);
                }
            } else if (!s.appPackage.isEmpty()) {
                config.recordAppLaunch(s.appPackage);
                displayDriver.launchOrMoveFullscreenApp(s.appPackage, s.appActivity, s.displayId, -1);
                onCompositorWindowsChanged();
            }

            s.clipboard.onSessionStarted();
            pushDeviceState(s);
            notifyRemoteStateChanged();
            System.out.println("OPALDESK_REMOTE_SESSION_READY sid=" + sessionId
                    + " role=" + (standalone ? "standalone" : "desktop")
                    + " displayId=" + s.displayId + " " + s.width + "x" + s.height + "@" + s.fps
                    + " vdUid=" + s.vdUniqueId);
        }
    }

    private void reconfigureSession(ActiveRemoteSession s, OpalCastProtocol.ResizeConfig cfg) {
        synchronized (s.lock) {
            if (s.closed || s.sink == null || s.vd == null) return;
            int reqW = cfg.width > 0 ? cfg.width : s.width;
            int reqH = cfg.height > 0 ? cfg.height : s.height;
            int nextW;
            int nextH;
            int nextDpi;
            if (s.standalone) {
                int[] spec = resolveStandaloneStreamSpec(s.appPackage, reqW, reqH, cfg.dpi);
                nextW = spec[0];
                nextH = spec[1];
                nextDpi = spec[2];
            } else {
                int[] aligned = DisplayGeometryUtil.alignForEncoder(reqW, reqH, 8);
                nextW = aligned[0];
                nextH = aligned[1];
                nextDpi = (cfg.dpi > 0) ? DisplayGeometryUtil.clampDpi(cfg.dpi, nextW, nextH) : s.dpi;
            }
            float nextFps = (cfg.fpsX100 > 0) ? DisplayGeometryUtil.clampFps(cfg.fpsX100 / 100f, s.fps) : s.fps;
            int nextBitrate = (cfg.bitrateKbps > 0)
                    ? DisplayGeometryUtil.clampBitrateKbps(
                            Math.max(cfg.bitrateKbps, computeRecommendedBitrateKbps(nextW, nextH, nextFps)),
                            2500, DEFAULT_MAX_BITRATE_KBPS)
                    : computeRecommendedBitrateKbps(nextW, nextH, nextFps);

            boolean ok = s.sink.reconfigureAndSwap(
                    nextW, nextH, nextDpi, nextFps, nextBitrate,
                    s.codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, 0,
                    (newSurface, alignedW, alignedH, alignedDpi) -> {
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.fps = nextFps;
                        s.bitrateKbps = nextBitrate;
                        try {
                            s.vd.resize(alignedW, alignedH, alignedDpi);
                            s.vd.setSurface(newSurface);
                        } catch (RuntimeException e) {
                            System.err.println("OPALDESK_REMOTE_RESIZE_WARN: " + e.getMessage());
                        }
                    });
            if (!ok) return;
            pushDeviceState(s);
            notifyRemoteStateChanged();
        }
    }

    private int[] resolveStandaloneStreamSpec(String appPkg, int reqW, int reqH, int requestedDpi) {
        int customDpi = config.resolveEffectiveCustomDpi(appPkg);
        if (customDpi > 0) {
            int[] aligned = DisplayGeometryUtil.alignForEncoder(reqW, reqH, 8);
            return new int[]{aligned[0], aligned[1], customDpi};
        }
        int[] hostMetrics = services.getPrimaryDisplayMetrics();
        int hostW = (hostMetrics != null && hostMetrics.length >= 3 && hostMetrics[0] > 0)
                ? hostMetrics[0] : config.getCanvasWidth();
        int hostH = (hostMetrics != null && hostMetrics.length >= 3 && hostMetrics[1] > 0)
                ? hostMetrics[1] : config.getCanvasHeight();
        int hostDpi = (hostMetrics != null && hostMetrics.length >= 3 && hostMetrics[2] > 0)
                ? hostMetrics[2] : config.getCanvasDpi();
        return DisplayGeometryUtil.resolveStandaloneStreamSpec(
                reqW, reqH, requestedDpi, hostW, hostH, hostDpi, config.getWindowDpiScalePct());
    }

    private static int computeRecommendedBitrateKbps(int w, int h, float fps) {
        long pixels = (long) Math.max(640, w) * Math.max(480, h);
        boolean highRes = pixels >= 2_800_000L; // >= 2560x1100
        boolean highFps = fps >= 85f;
        if (highRes && highFps) return 22_000;
        if (highRes) return 16_000;
        if (highFps) return 15_000;
        return 11_000;
    }

    private void launchRemoteDesktopHost(int displayId) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                BridgeBuildConfig.HELPER_PACKAGE,
                BridgeBuildConfig.HELPER_PACKAGE + ".ExternalDesktopActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("targetDisplayId", displayId);
        Bundle opts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        boolean ok = services.startActivityAsShell(intent, opts);
        if (ok) {
            services.focusDisplay(displayId);
        }
        System.out.println("OPALDESK_REMOTE_HOST_LAUNCH displayId=" + displayId + " ok=" + ok);
    }

    private void startAudioForDesktop(int sessionId) {
        stopAudio();
        boolean splitByApp = config.getAudioRouteMode() == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP;
        List<Integer> initialUids = splitByApp ? collectDexAppUids() : Collections.emptyList();
        AudioStreamCapturer cap = new AudioStreamCapturer(services.context, new AudioStreamCapturer.AudioListener() {
            @Override
            public void onAudioConfig(OpalCastProtocol.AudioConfig cfg, long tsUs) {
                server.sendAudioConfig(sessionId, cfg, tsUs);
            }

            @Override
            public void onAudioFrame(byte[] aacAu, long tsUs) {
                server.sendAudioFrame(sessionId, aacAu, tsUs);
            }

            @Override
            public void onAudioStateChanged(boolean active) {
                System.out.println("OPALDESK_REMOTE_AUDIO_STATE active=" + active);
            }
        });
        if (cap.start(splitByApp, initialUids)) {
            audioCapturer = cap;
        }
    }

    public void onCompositorWindowsChanged() {
        AudioStreamCapturer cap = audioCapturer;
        if (cap == null || !cap.isActive()) return;
        if (config.getAudioRouteMode() != BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP) return;
        cap.updateTargetUids(collectDexAppUids());
    }

    public void applyAudioRouteMode(int mode) {
        final AudioStreamCapturer cap = audioCapturer;
        if (cap == null || !cap.isActive()) return;
        final boolean splitByApp = (mode == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP);
        worker.execute(() -> {
            List<Integer> uids = splitByApp ? collectDexAppUids() : Collections.emptyList();
            cap.setSplitMode(splitByApp, uids);
        });
    }

    private List<Integer> collectDexAppUids() {
        Set<String> pkgs = compositor.getOpenWindowPackages();
        for (ActiveRemoteSession s : sessions.values()) {
            if (s != null && !s.closed && s.standalone && !s.appPackage.isEmpty()) {
                pkgs.add(s.appPackage);
            }
        }
        List<Integer> uids = new ArrayList<>();
        if (pkgs.isEmpty() || services.context == null) {
            return uids;
        }
        PackageManager pm;
        try {
            pm = services.context.getPackageManager();
        } catch (RuntimeException ignored) {
            return uids;
        }
        if (pm == null) {
            return uids;
        }
        for (String pkg : pkgs) {
            if (pkg == null || pkg.isEmpty()) continue;
            try {
                int uid = pm.getPackageUid(pkg, 0);
                if (uid >= io.github.lengsukq.opaldesk.daemon.audio.AudioRoutingPolicy.MIN_APP_UID) {
                    uids.add(uid);
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
        return uids;
    }

    private void stopAudio() {
        AudioStreamCapturer cap = audioCapturer;
        audioCapturer = null;
        if (cap != null) {
            try { cap.stop(); } catch (RuntimeException ignored) {}
        }
    }

    public void applyRemoteMediaPolicy(boolean micEnabled, boolean cameraEnabled) {
        if (!micEnabled) {
            stopRemoteMic(true);
        }
        if (!cameraEnabled) {
            stopRemoteCamera(true);
        }
    }

    private void handleMicConfig(int sessionId, OpalCastProtocol.MicConfigMsg msg) {
        synchronized (micLock) {
            if (!msg.enabled) {
                if (micOwnerSessionId == sessionId) {
                    stopRemoteMicLocked(false);
                }
                server.sendMicState(sessionId, new OpalCastProtocol.MicStateMsg(
                        false, config.isRemoteMicEnabled(), msg.sampleRate, msg.channels, "远端麦克风已关闭"));
                return;
            }
            if (!config.isRemoteMicEnabled()) {
                server.sendMicState(sessionId, new OpalCastProtocol.MicStateMsg(
                        false, false, msg.sampleRate, msg.channels,
                        "主机端未开启远端麦克风（可在主应用设置或控制中心开启）"));
                return;
            }
            if (!OpalCastProtocol.MIC_CODEC_PCM16.equalsIgnoreCase(msg.codec)) {
                server.sendMicState(sessionId, new OpalCastProtocol.MicStateMsg(
                        false, false, msg.sampleRate, msg.channels, "不支持的麦克风音频格式：" + msg.codec));
                return;
            }
            if (micOwnerSessionId > 0 && micOwnerSessionId != sessionId) {
                int prevSid = micOwnerSessionId;
                stopRemoteMicLocked(false);
                server.sendMicState(prevSid, new OpalCastProtocol.MicStateMsg(
                        false, true, OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE,
                        OpalCastProtocol.DEFAULT_MIC_CHANNELS, "远端麦克风已被其他会话接管"));
            }
            if (micInjector == null) {
                micInjector = new RemoteMicInjector(services.context);
            }
            RemoteMicInjector.StartResult res = micInjector.start(msg.sampleRate, msg.channels);
            if (res.active) {
                micOwnerSessionId = sessionId;
            } else {
                micOwnerSessionId = -1;
            }
            server.sendMicState(sessionId, new OpalCastProtocol.MicStateMsg(
                    res.active, res.supported, res.sampleRate, res.channels, res.message));
        }
    }

    private void stopRemoteMic(boolean notifyOwner) {
        synchronized (micLock) {
            stopRemoteMicLocked(notifyOwner);
        }
    }

    private void stopRemoteMicLocked(boolean notifyOwner) {
        int prevSid = micOwnerSessionId;
        micOwnerSessionId = -1;
        if (micInjector != null) {
            try { micInjector.stop(); } catch (RuntimeException ignored) {}
        }
        if (notifyOwner && prevSid > 0) {
            server.sendMicState(prevSid, new OpalCastProtocol.MicStateMsg(
                    false, config.isRemoteMicEnabled(), OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE,
                    OpalCastProtocol.DEFAULT_MIC_CHANNELS,
                    config.isRemoteMicEnabled() ? "远端麦克风已关闭" : "主机端已关闭远端麦克风开关"));
        }
    }

    private void handleCameraConfig(int sessionId, OpalCastProtocol.CameraConfigMsg msg) {
        synchronized (cameraLock) {
            if (!msg.enabled) {
                if (cameraOwnerSessionId == sessionId) {
                    stopRemoteCameraLocked(false);
                }
                server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                        false, config.isRemoteCameraEnabled() && config.isRootEnhanced(), !config.isRootEnhanced(),
                        msg.width, msg.height, msg.fps, "", "远端摄像头映射已关闭"));
                return;
            }
            if (!config.isRootEnhanced()) {
                server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                        false, false, true,
                        msg.width, msg.height, msg.fps, "",
                        "摄像头映射需要 Root 权限（请使用 Root 增强模式启动 OpalDesk 引擎）"));
                return;
            }
            if (!config.isRemoteCameraEnabled()) {
                server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                        false, false, false,
                        msg.width, msg.height, msg.fps, "",
                        "主机端未开启 Root 摄像头映射（可在主应用设置或控制中心开启）"));
                return;
            }
            if (!OpalCastProtocol.CAMERA_CODEC_MJPEG.equalsIgnoreCase(msg.codec)) {
                server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                        false, false, false, msg.width, msg.height, msg.fps, "",
                        "不支持的摄像头编码格式：" + msg.codec));
                return;
            }
            if (cameraOwnerSessionId > 0 && cameraOwnerSessionId != sessionId) {
                int prevSid = cameraOwnerSessionId;
                stopRemoteCameraLocked(false);
                server.sendCameraState(prevSid, new OpalCastProtocol.CameraStateMsg(
                        false, true, false,
                        OpalCastProtocol.DEFAULT_CAMERA_WIDTH, OpalCastProtocol.DEFAULT_CAMERA_HEIGHT,
                        OpalCastProtocol.DEFAULT_CAMERA_FPS, "", "远端摄像头映射已被其他会话接管"));
            }
            if (cameraBridge == null) {
                cameraBridge = new RootCameraBridge(services.context);
                cameraBridge.setFacingChangeListener(this::onVirtualCameraActiveFacingChanged);
            }
            boolean wasAospActive = cameraBridge.hasAospVirtualCamera();
            RootCameraBridge.StartResult res = cameraBridge.start(
                    config.isRootEnhanced(), msg.width, msg.height, msg.fps, msg.facing);
            cameraOwnerSessionId = res.active ? sessionId : -1;
            if (res.active && cameraBridge.hasAospVirtualCamera()) {
                final RootCameraBridge activeBridge = cameraBridge;
                displayDriver.setCustomVirtualDisplayProvider(activeBridge::createVirtualDisplay);
                if (!wasAospActive) {
                    int migrated = compositor.migrateLiveWindowsToCurrentDisplayDriver();
                    System.out.println("OPALDESK_AOSP_VCAM_MIGRATED_ON count=" + migrated);
                }
            }
            server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                    res.active, res.supported, res.rootRequired,
                    res.width, res.height, res.fps, res.backend, res.message,
                    msg.facing));
        }
    }

    private void onVirtualCameraActiveFacingChanged(String facing) {
        if (facing == null || facing.isEmpty()) return;
        int ownerSid = cameraOwnerSessionId;
        RootCameraBridge bridge = cameraBridge;
        if (ownerSid <= 0 || bridge == null || !bridge.isActive()) return;
        System.out.println("OPALDESK_AOSP_VCAM_FACING_SYNC sid=" + ownerSid + " facing=" + facing);
        server.sendCameraState(ownerSid, new OpalCastProtocol.CameraStateMsg(
                true, true, false,
                bridge.getActiveWidth(), bridge.getActiveHeight(), bridge.getActiveFps(),
                bridge.getActiveBackend(), "", facing));
    }

    private void stopRemoteCamera(boolean notifyOwner) {
        stopRemoteCamera(notifyOwner, false);
    }

    private void stopRemoteCamera(boolean notifyOwner, boolean disassociateCdm) {
        synchronized (cameraLock) {
            stopRemoteCameraLocked(notifyOwner, disassociateCdm);
        }
    }

    private void stopRemoteCameraLocked(boolean notifyOwner) {
        stopRemoteCameraLocked(notifyOwner, false);
    }

    private void stopRemoteCameraLocked(boolean notifyOwner, boolean disassociateCdm) {
        int prevSid = cameraOwnerSessionId;
        cameraOwnerSessionId = -1;
        boolean hadAosp = cameraBridge != null && cameraBridge.hasAospVirtualCamera();
        displayDriver.setCustomVirtualDisplayProvider(null);
        if (hadAosp && !disassociateCdm) {
            // Migrate live windows back to default DisplayManager BEFORE closing VirtualDevice so
            // apps regain the phone's physical cameras and are never killed by VD teardown.
            int migrated = compositor.migrateLiveWindowsToCurrentDisplayDriver();
            System.out.println("OPALDESK_AOSP_VCAM_MIGRATED_OFF count=" + migrated);
        }
        if (cameraBridge != null) {
            try { cameraBridge.stop(disassociateCdm); } catch (RuntimeException ignored) {}
        }
        if (notifyOwner && prevSid > 0) {
            server.sendCameraState(prevSid, new OpalCastProtocol.CameraStateMsg(
                    false, config.isRemoteCameraEnabled() && config.isRootEnhanced(), !config.isRootEnhanced(),
                    OpalCastProtocol.DEFAULT_CAMERA_WIDTH, OpalCastProtocol.DEFAULT_CAMERA_HEIGHT,
                    OpalCastProtocol.DEFAULT_CAMERA_FPS, "",
                    config.isRemoteCameraEnabled() ? "远端摄像头映射已关闭" : "主机端已关闭 Root 摄像头映射开关"));
        }
    }

    private void pushDeviceState(ActiveRemoteSession s) {
        OpalCastProtocol.DeviceState state = new OpalCastProtocol.DeviceState(
                server.getDeviceName(),
                s.sinkType,
                OpalCastProtocol.DISPLAY_MODE_DEX,
                s.width,
                s.height,
                s.dpi,
                s.fps,
                0,
                s.bitrateKbps,
                100,
                services.isHostScreenOff(),
                audioCapturer != null,
                inputRouter.getLastInjectStatus(),
                false,
                0,
                "DEX",
                "",
                "");
        server.sendDeviceState(s.sessionId, state);
    }

    private void closeSessionResources(ActiveRemoteSession s) {
        synchronized (s.lock) {
            if (s.closed) return;
            s.closed = true;
            if (s.clipboard != null) {
                try { s.clipboard.onSessionEnded(); } catch (RuntimeException ignored) {}
                s.clipboard = null;
            }
            if (s.fileReceiver != null) {
                try { s.fileReceiver.abortAllTransfers(); } catch (RuntimeException ignored) {}
                s.fileReceiver = null;
            }
            if (s.vd != null) {
                int oldId = s.displayId;
                if (oldId > 0) {
                    String expectedPkg = s.standalone ? s.appPackage : BridgeBuildConfig.HELPER_PACKAGE;
                    int topTid = displayDriver.findTopTaskIdOnDisplay(oldId, expectedPkg);
                    if (topTid > 0) {
                        displayDriver.removeTask(topTid);
                    }
                }
                try { s.vd.setSurface(null); } catch (RuntimeException ignored) {}
                try { s.vd.release(); } catch (RuntimeException ignored) {}
                s.vd = null;
                if (oldId > 0) inputRouter.releaseDisplayResources(oldId);
            }
            if (s.sink != null) {
                try { s.sink.stop(); } catch (RuntimeException ignored) {}
                s.sink = null;
            }
        }
    }

    private List<OpalCastProtocol.AppEntry> queryInstalledApps() {
        List<OpalCastProtocol.AppEntry> out = new ArrayList<>();
        if (services.context == null) return out;
        try {
            PackageManager pm = services.context.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(q, 0);
            Set<String> seenPkgs = new HashSet<>();
            for (ResolveInfo ri : resolved) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (DaemonConfig.PackagePolicy.isBlockedWindowPkg(pkg)) continue;
                if (!seenPkgs.add(pkg)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                String label = (lbl != null && lbl.length() > 0) ? lbl.toString().trim() : pkg;
                int openCount = config.getAppLaunchCount(pkg);
                out.add(new OpalCastProtocol.AppEntry(pkg, ri.activityInfo.name, label, false, -1, "", openCount));
            }
            out.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        } catch (RuntimeException ignored) {
        }
        return out;
    }
}
