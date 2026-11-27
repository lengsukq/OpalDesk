package io.github.lengsukq.opaldesk.daemon.stream;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.audio.AudioRoutingPolicy;
import io.github.lengsukq.opaldesk.daemon.audio.AudioStreamCapturer;
import io.github.lengsukq.opaldesk.daemon.audio.RemoteMicInjector;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.input.ExternalMouseRouter;
import io.github.lengsukq.opaldesk.daemon.input.ImmsDumpInspector;
import io.github.lengsukq.opaldesk.daemon.input.MouseRoutingPolicy;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.HotspotController;
import io.github.lengsukq.opaldesk.daemon.platform.TaskApiAdapter;
import io.github.lengsukq.opaldesk.daemon.platform.VirtualDisplayFactory;
import io.github.lengsukq.opaldesk.daemon.security.PairingApprovalCoordinator;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;
import io.github.lengsukq.opaldesk.daemon.sink.RemoteStreamSink;
import io.github.lengsukq.opaldesk.daemon.video.RootCameraBridge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    public static final int WIRELESS_MAX_BITRATE_KBPS = DisplayGeometryUtil.WIRELESS_MAX_BITRATE_KBPS;
    public static final int WIRED_MAX_BITRATE_KBPS = DisplayGeometryUtil.WIRED_MAX_BITRATE_KBPS;
    private static final File TRUST_FILE = new File("/data/local/tmp/opaldesk_trusted_clients.conf");
    private static final File CERT_FILE = new File("/data/local/tmp/opaldesk_tls_cert.p12");

    public static int resolveMaxBitrateKbpsForSink(int sinkType) {
        return DisplayGeometryUtil.resolveMaxBitrateKbpsForSink(sinkType);
    }

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
        public final String usbTetherIp;
        public final String usbTetherInterface;
        public final int tlsBindScope;

        public RemoteStatusSnapshot(
                boolean connected, int sinkType, String peerName,
                int width, int height, int fpsX100, int bitrateKbps,
                boolean wifiEnabled, int wifiPort, String certFingerprint,
                boolean activeSessionPermanent, int trustedCount, String lanIp,
                int desktopDisplayId) {
            this(connected, sinkType, peerName, width, height, fpsX100, bitrateKbps,
                    wifiEnabled, wifiPort, certFingerprint, activeSessionPermanent,
                    trustedCount, lanIp, desktopDisplayId, "", "", BridgeProtocol.TLS_BIND_SCOPE_ALL);
        }

        public RemoteStatusSnapshot(
                boolean connected, int sinkType, String peerName,
                int width, int height, int fpsX100, int bitrateKbps,
                boolean wifiEnabled, int wifiPort, String certFingerprint,
                boolean activeSessionPermanent, int trustedCount, String lanIp,
                int desktopDisplayId, String usbTetherIp, String usbTetherInterface,
                int tlsBindScope) {
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
            this.usbTetherIp = usbTetherIp != null ? usbTetherIp : "";
            this.usbTetherInterface = usbTetherInterface != null ? usbTetherInterface : "";
            this.tlsBindScope = (tlsBindScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY)
                    ? BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY
                    : BridgeProtocol.TLS_BIND_SCOPE_ALL;
        }
    }

    private static final class ActiveRemoteSession {
        final int sessionId;
        final int sinkType;
        final boolean standalone;
        final boolean companion;
        final String appPackage;
        final String appActivity;
        final String clientPlatform;
        final Object lock = new Object();

        volatile int width = 1920;
        volatile int height = 1080;
        volatile int dpi = 220;
        volatile int initialWidth = 1920;
        volatile int initialHeight = 1080;
        volatile int initialDpi = 220;
        volatile int dexWidth = 1920;
        volatile int dexHeight = 1080;
        volatile int dexDpi = 220;
        volatile int dexRequestedBitrateKbps = 0;
        volatile int displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
        volatile int mirrorSourceWidth = 1080;
        volatile int mirrorSourceHeight = 1920;
        volatile int mirrorSourceDpi = 420;
        volatile int mirrorRotation = 0;
        volatile String mirrorTargetUniqueId = "";
        volatile float fps = 60f;
        volatile float requestedNativeFps = 60f;
        volatile int bitrateKbps = 12_000;
        volatile int codecPref = RemoteStreamSink.CODEC_PREF_AUTO_HEVC;
        volatile int displayId = -1;
        volatile String vdUniqueId = "";
        volatile boolean vdTrusted = false;
        volatile VirtualDisplay vd;
        volatile RemoteStreamSink sink;
        volatile ClipboardSyncService clipboard;
        volatile FileTransferReceiver fileReceiver;
        volatile long mouseDownTimeMs = 0L;
        volatile long touchDownTimeMs = 0L;
        volatile int mouseButtons = 0;
        volatile int lastImeShowSeq = -1;
        volatile long imeProbeGen = 0L;
        volatile String lastPushedFullscreenTitle = "";
        volatile boolean audioRequested = false;
        volatile boolean closed = false;

        ActiveRemoteSession(
                int sessionId,
                int sinkType,
                boolean standalone,
                boolean companion,
                String pkg,
                String cls,
                String clientPlatform) {
            this.sessionId = sessionId;
            this.sinkType = sinkType;
            this.standalone = standalone;
            this.companion = companion;
            this.appPackage = pkg != null ? pkg : "";
            this.appActivity = cls != null ? cls : "";
            this.clientPlatform = clientPlatform != null ? clientPlatform : "";
        }

        boolean isMirror() {
            return !standalone && !companion && displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR;
        }

        int inputTargetDisplayId() {
            return isMirror() ? Display.DEFAULT_DISPLAY : displayId;
        }

        String inputTargetUniqueId() {
            return isMirror() ? mirrorTargetUniqueId : vdUniqueId;
        }

        int inputTargetWidth() {
            return isMirror() ? Math.max(2, mirrorSourceWidth) : Math.max(2, width);
        }

        int inputTargetHeight() {
            return isMirror() ? Math.max(2, mirrorSourceHeight) : Math.max(2, height);
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
    private final ExecutorService fileIoWorker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "OpalCastFileIo");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService apkInstallWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OpalCastApkInstall");
        t.setDaemon(true);
        return t;
    });

    private final RemoteFileManager fileManager = new RemoteFileManager();
    private final RemoteApkInstaller apkInstaller = new RemoteApkInstaller();
    private final HotspotController hotspotController = new HotspotController();
    private final CompanionNotificationRelay notificationRelay;
    private final CompanionCallRelay callRelay;

    private final Map<Integer, ActiveRemoteSession> sessions = new ConcurrentHashMap<>();
    private volatile byte[] lastMediaStatePayload = null;
    private final Map<String, String> hostAppLabelCache = new ConcurrentHashMap<>();
    private volatile String hostTopAppPackage = "";
    private volatile String hostTopAppLabel = "";
    private volatile int desktopSessionId = -1;
    private volatile boolean localHostTextFieldFocused = false;
    private final Object audioLock = new Object();
    private volatile AudioStreamCapturer audioCapturer;
    private volatile int audioOwnerSessionId = -1;
    private volatile OpalCastProtocol.AudioConfig lastAudioConfig;
    private final Object micLock = new Object();
    private RemoteMicInjector micInjector;
    private volatile int micOwnerSessionId = -1;
    private final Object cameraLock = new Object();
    private RootCameraBridge cameraBridge;
    private volatile int cameraOwnerSessionId = -1;
    /** Optional zero-clipboard direct commit hook backed by OpalDesk's built-in channel IME. */
    public interface DirectImeTextCommitter {
        boolean tryCommitText(String text);
    }

    public interface NotificationActionConsumer {
        void accept(String op, String key, String replyText, int actionIndex);
    }

    public interface MediaActionConsumer {
        void accept(String op, long positionMs);
    }

    public interface CallActionConsumer {
        boolean dispatch(String op, String number, String smsText);
    }

    private final Object mirrorListenerLock = new Object();
    private DisplayManager.DisplayListener mirrorDisplayListener;
    private volatile Runnable remoteStateChangeListener;
    private volatile DirectImeTextCommitter directImeCommitter;
    private volatile NotificationActionConsumer notificationActionConsumer;
    private volatile MediaActionConsumer mediaActionConsumer;
    private volatile CallActionConsumer callActionConsumer;
    private volatile java.util.function.BooleanSupplier bridgeImeActiveSupplier = () -> false;

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
        this.fileManager.setAppStorageStatsProvider(
                RemoteFileManager.createDefaultAppStorageProvider(this::resolveCachedHostAppLabel));
        this.notificationRelay = new CompanionNotificationRelay(new CompanionNotificationRelay.OutboundSender() {
            @Override
            public void sendNotificationEventToSession(int sessionId, byte[] jsonUtf8) {
                server.sendNotificationEvent(sessionId, jsonUtf8);
            }

            @Override
            public void broadcastNotificationEvent(byte[] jsonUtf8) {
                server.broadcastNotificationEvent(jsonUtf8);
            }
        });
        this.callRelay = new CompanionCallRelay(new CompanionCallRelay.OutboundSender() {
            @Override
            public void sendCallStateToSession(int sessionId, byte[] jsonUtf8) {
                server.sendCallState(sessionId, jsonUtf8);
            }

            @Override
            public void broadcastCallState(byte[] jsonUtf8) {
                server.broadcastCallState(jsonUtf8);
            }
        });
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

    public void setDirectImeTextCommitter(DirectImeTextCommitter committer) {
        this.directImeCommitter = committer;
    }

    public void setNotificationActionConsumer(NotificationActionConsumer consumer) {
        this.notificationActionConsumer = consumer;
        this.notificationRelay.setActionForwarder(
                consumer != null ? consumer::accept : null);
    }

    public void setMediaActionConsumer(MediaActionConsumer consumer) {
        this.mediaActionConsumer = consumer;
    }

    public void setCallActionConsumer(CallActionConsumer consumer) {
        this.callActionConsumer = consumer;
        this.callRelay.setActionDispatcher(consumer != null ? consumer::dispatch : null);
    }

    public void setBridgeImeActiveSupplier(java.util.function.BooleanSupplier supplier) {
        this.bridgeImeActiveSupplier = supplier != null ? supplier : () -> false;
    }

    /**
     * Ingests a notification event JSON pushed from Helper (Binder TX 50), enriches missing OTP
     * verification codes via {@link OtpCodeExtractor}, updates the snapshot cache, and broadcasts
     * {@code 0x19 TYPE_NOTIFICATION_EVENT} to active companion and subscribed desktop sessions.
     */
    public void onNotificationsSyncedFromHelper(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        worker.execute(() -> notificationRelay.onNotificationsSyncedFromHelper(rawJson));
    }

    /**
     * Ingests a call state JSON pushed from Helper (Binder TX 52), caches the normalized payload,
     * and broadcasts {@code 0x1E TYPE_CALL_STATE} exclusively to subscribed companion/desktop sessions.
     */
    public void onCallStateSyncedFromHelper(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        worker.execute(() -> callRelay.onCallStateSyncedFromHelper(rawJson));
    }

    /**
     * Verifies a BLE Instant Hotspot challenge-response HMAC in constant time against
     * {@link TrustStoreManager}'s stored {@code tokenSha256}, ensures the LAN/hotspot TLS listener
     * is bound on wildcard scope, triggers SoftAP via {@link HotspotController}, and returns JSON.
     */
    public String triggerHotspotForBleClient(String clientId, String nonceHex, String hmacHex) {
        int targetPort = (server.getWifiPort() > 0)
                ? server.getWifiPort()
                : RemoteStreamServer.DEFAULT_WIFI_PORT;
        if (!trustStore.verifyHotspotHmac(clientId, nonceHex, hmacHex)) {
            System.err.println("OPALDESK_HOTSPOT_REJECT_UNAUTH clientId="
                    + TrustStoreManager.sanitizeClientId(clientId));
            return HotspotController.HotspotResult.unauthorized(targetPort).toJson();
        }
        if (!server.isWifiEnabled() || server.getWifiBindScope() != BridgeProtocol.TLS_BIND_SCOPE_ALL) {
            RemoteStreamServer.WifiConfigStatus st = server.configureWifi(
                    true, targetPort, BridgeProtocol.TLS_BIND_SCOPE_ALL);
            if (st != null && st.port > 0) {
                targetPort = st.port;
            }
            notifyRemoteStateChanged();
        }
        HotspotController.HotspotResult res = hotspotController.triggerHotspot(targetPort);
        System.out.println("OPALDESK_HOTSPOT_TRIGGER clientId="
                + TrustStoreManager.sanitizeClientId(clientId)
                + " status=" + res.status
                + " gw=" + res.gatewayIp
                + " port=" + res.port);
        return res.toJson();
    }

    /**
     * Ingests a media playback state JSON pushed from Helper (Binder TX 51), caches the normalized
     * payload, and broadcasts {@code 0x1A TYPE_MEDIA_STATE} to active desktop and companion sessions.
     */
    public void onMediaStateSyncedFromHelper(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        worker.execute(() -> {
            try {
                OpalCastProtocol.MediaStateMsg msg =
                        OpalCastProtocol.decodeMediaState(rawJson.getBytes(StandardCharsets.UTF_8));
                byte[] normalized = OpalCastProtocol.encodeMediaState(msg);
                lastMediaStatePayload = normalized;
                server.broadcastMediaState(normalized);
            } catch (OpalCastProtocol.ProtocolException | RuntimeException e) {
                System.err.println("OPALDESK_MEDIA_SYNC_WARN: " + e.getMessage());
            }
        });
    }

    public boolean hasDesktopOrCompanionSession() {
        return hasActiveDesktopSession() || server.hasCompanionSession();
    }

    /**
     * Probes Display 0's current foreground user app for Mac Handoff and broadcasts updated
     * {@code DeviceState (0x10)} to active desktop and companion sessions whenever it changes.
     */
    public void refreshHostTopAppIfChanged() {
        if (!hasDesktopOrCompanionSession() || displayDriver == null) return;
        String pkg = "";
        try {
            pkg = displayDriver.findTopVisibleUserAppPackageOnDisplay(Display.DEFAULT_DISPLAY);
        } catch (RuntimeException ignored) {
        }
        if (pkg == null) pkg = "";
        String label = pkg.isEmpty() ? "" : resolveCachedHostAppLabel(pkg);
        if (pkg.equals(hostTopAppPackage) && label.equals(hostTopAppLabel)) {
            return;
        }
        hostTopAppPackage = pkg;
        hostTopAppLabel = label;
        for (int sid : server.getDesktopAndCompanionSessionIds()) {
            ActiveRemoteSession s = sessions.get(sid);
            if (s != null && !s.closed) {
                pushDeviceState(s);
            }
        }
    }

    private String resolveCachedHostAppLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = hostAppLabelCache.get(pkg);
        if (cached != null) return cached;
        String resolved = (displayDriver != null) ? displayDriver.resolveAppLabel(pkg) : pkg;
        if (resolved == null || resolved.isEmpty()) resolved = pkg;
        if (hostAppLabelCache.size() < 256) {
            hostAppLabelCache.put(pkg, resolved);
        }
        return resolved;
    }

    private void pushCachedCompanionSnapshotsToSession(int sessionId, boolean requestSyncIfEmpty) {
        if (requestSyncIfEmpty) {
            notificationRelay.pushInitialSnapshotOrRequestSync(sessionId);
        } else {
            notificationRelay.pushCachedSnapshotToSession(sessionId);
        }
        pushCachedMediaStateOnly(sessionId);
        callRelay.pushCachedCallStateToSession(sessionId, true);
    }

    private void pushCachedMediaStateOnly(int sessionId) {
        byte[] media = lastMediaStatePayload;
        if (media != null && media.length > 0) {
            server.sendMediaState(sessionId, media);
        }
    }

    private boolean isBridgeImeActive() {
        java.util.function.BooleanSupplier sup = this.bridgeImeActiveSupplier;
        return sup != null && sup.getAsBoolean();
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
        unregisterMirrorDisplayListener();
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
        server.reconcileUsbTetherBindIfNeeded();
        ActiveRemoteSession main = (desktopSessionId > 0) ? sessions.get(desktopSessionId) : null;
        if (main == null) {
            for (ActiveRemoteSession candidate : sessions.values()) {
                if (candidate != null && !candidate.closed && !candidate.companion) {
                    main = candidate;
                    break;
                }
            }
        }
        boolean active = server.hasActiveSession() && main != null;
        int w = active ? main.width : config.getCanvasWidth();
        int h = active ? main.height : config.getCanvasHeight();
        int fpsX100 = active ? Math.round(main.fps * 100f) : Math.round(config.getTargetFps() * 100f);
        int kbps = (active && main.sink != null) ? main.sink.getBitrateKbps() : 0;
        List<NetworkInterfaceClassifier.InterfaceSnapshot> nicSnapshots =
                NetworkInterfaceClassifier.snapshotSystemInterfaces();
        String lanIp = NetworkInterfaceClassifier.selectBestLanIp(nicSnapshots);
        NetworkInterfaceClassifier.Endpoint usbEp =
                NetworkInterfaceClassifier.selectUsbTetherEndpoint(nicSnapshots);
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
                getActiveDesktopDisplayId(),
                usbEp.ipv4,
                usbEp.interfaceName,
                server.getWifiBindScope());
    }

    public RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int port) {
        return configureWifi(enable, port, BridgeProtocol.TLS_BIND_SCOPE_ALL);
    }

    public RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int port, int bindScope) {
        return server.configureWifi(enable, port > 0 ? port : RemoteStreamServer.DEFAULT_WIFI_PORT, bindScope);
    }

    public void disconnectActiveClient() {
        server.disconnectActiveClient("user_requested");
    }

    /**
     * Reconfigures the active remote desktop session (USB / Wi-Fi cast) using the exact same
     * resolution & refresh-rate control surface as physical USB-C / HDMI external monitors.
     * Passing {@code reqW <= 0 && reqH <= 0 && reqFps <= 0f} restores the receiver's original
     * negotiated native resolution and refresh rate.
     */
    public boolean reconfigureActiveDesktop(int reqW, int reqH, float reqFps) {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        if (s == null || s.closed) return false;
        boolean resetToNative = (reqW <= 0 && reqH <= 0 && reqFps <= 0f);
        int targetW = resetToNative ? s.initialWidth : (reqW > 0 ? reqW : s.width);
        int targetH = resetToNative ? s.initialHeight : (reqH > 0 ? reqH : s.height);
        int targetDpi;
        if (resetToNative) {
            targetDpi = s.initialDpi;
        } else if (reqW > 0 && reqH > 0 && (reqW != s.width || reqH != s.height)) {
            int shortEdge = Math.min(targetW, targetH);
            targetDpi = Math.max(160, Math.min(440, Math.round(shortEdge * 160f / 720f)));
        } else {
            targetDpi = s.dpi;
        }
        int fpsX100 = resetToNative
                ? Math.round(s.requestedNativeFps * 100f)
                : (reqFps > 0f ? Math.round(reqFps * 100f) : Math.round(s.fps * 100f));
        OpalCastProtocol.ResizeConfig cfg = new OpalCastProtocol.ResizeConfig(
                targetW, targetH, targetDpi, fpsX100, 0,
                OpalCastProtocol.DISPLAY_MODE_UNCHANGED, 0xFF);
        worker.execute(() -> reconfigureSession(s, cfg));
        return true;
    }

    public boolean hasActiveDesktopSession() {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        return s != null && !s.closed;
    }

    public boolean hasAnyActiveSession() {
        for (ActiveRemoteSession s : sessions.values()) {
            if (s != null && !s.closed && !s.companion) return true;
        }
        return false;
    }

    /** Re-applies the configured DisplayImePolicy across all live non-mirror remote VirtualDisplays. */
    public void syncRemoteDisplayImePolicies() {
        if (services == null || config == null) return;
        int mode = config.getDexImeDisplayMode();
        boolean muteHost = config.isMuteHostImeOnCast();
        boolean bridgeImeActive = isBridgeImeActive();
        for (ActiveRemoteSession s : new ArrayList<>(sessions.values())) {
            if (s == null || s.closed || s.companion || s.isMirror() || s.displayId <= 0) continue;
            int policy = MouseRoutingPolicy.resolveRemoteOuterDisplayImePolicy(
                    mode, muteHost, s.vdTrusted, bridgeImeActive);
            boolean ok = services.setDisplayImePolicy(s.displayId, policy);
            System.out.println("OPALDESK_REMOTE_VD_IME_POLICY sid=" + s.sessionId
                    + " displayId=" + s.displayId
                    + " policy=" + policy
                    + " trusted=" + s.vdTrusted
                    + " bridgeIme=" + bridgeImeActive
                    + " ok=" + ok);
        }
    }

    public int getActiveDesktopDisplayId() {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        return (s != null && !s.closed && !s.isMirror()) ? s.displayId : -1;
    }

    /** Forwards a soft-keyboard show/hide request to the active remote desktop receiver (e.g. Tablet). */
    public void sendDesktopImeRequest(boolean show, int inputType) {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        if (s != null && !s.closed) {
            server.sendImeRequest(sid, show, inputType);
        }
    }

    /**
     * Zero-latency callback invoked directly when {@code OpalDeskImeService.onShowInputRequested}
     * fires on a managed {@code OpalWin-*} or standalone {@code OpalDesk-App-*} VirtualDisplay.
     */
    public void onBridgeImeShowRequested(int imeDisplayId, String pkg, int rawInputType) {
        if (imeDisplayId <= 0) return;
        int hostType = ImmsDumpInspector.sanitizeHostInputType(rawInputType);
        for (ActiveRemoteSession s : sessions.values()) {
            if (s != null && !s.closed && s.standalone && s.displayId == imeDisplayId) {
                server.sendImeRequest(s.sessionId, true, hostType);
                return;
            }
        }
        int deskSid = desktopSessionId;
        ActiveRemoteSession desk = (deskSid > 0) ? sessions.get(deskSid) : null;
        if (desk != null && !desk.closed && !desk.isMirror()) {
            WindowSession focused = compositor.getFocusedLiveWindow();
            if ((focused != null && focused.displayId == imeDisplayId) || desk.displayId == imeDisplayId) {
                server.sendImeRequest(deskSid, true, hostType);
            }
        }
    }

    /** Zero-latency hide callback paired with {@link #onBridgeImeShowRequested(int, String, int)}. */
    public void onBridgeImeHideRequested(int imeDisplayId) {
        if (imeDisplayId <= 0) return;
        for (ActiveRemoteSession s : sessions.values()) {
            if (s != null && !s.closed && s.standalone && s.displayId == imeDisplayId) {
                server.sendImeRequest(s.sessionId, false, 0);
                return;
            }
        }
        int deskSid = desktopSessionId;
        ActiveRemoteSession desk = (deskSid > 0) ? sessions.get(deskSid) : null;
        if (desk != null && !desk.closed && !desk.isMirror()) {
            WindowSession focused = compositor.getFocusedLiveWindow();
            if ((focused != null && focused.displayId == imeDisplayId) || desk.displayId == imeDisplayId) {
                server.sendImeRequest(deskSid, false, 0);
            }
        }
    }

    private void scheduleStandaloneImeProbe(ActiveRemoteSession s, long tapDownMs) {
        if (s == null || !s.standalone || s.closed || s.displayId <= 0 || isBridgeImeActive()) return;
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

    // ---- RemoteStreamServer.SessionListener ----

    @Override
    public void onSessionConnected(
            int sessionId, int sinkType, String clientId, String clientName, OpalCastProtocol.HelloMsg hello) {
        worker.execute(() -> setupSession(sessionId, sinkType, hello));
    }

    @Override
    public void onSessionDisconnected(int sessionId, int sinkType, String sessionRole, String reason) {
        ActiveRemoteSession s = sessions.remove(sessionId);
        boolean wasCompanion = (s != null && s.companion)
                || OpalCastProtocol.SESSION_ROLE_COMPANION.equalsIgnoreCase(sessionRole);
        if (sessionId == micOwnerSessionId) {
            stopRemoteMic(false);
        }
        if (sessionId == cameraOwnerSessionId) {
            stopRemoteCamera(false);
        }
        if (sessionId == desktopSessionId) {
            desktopSessionId = -1;
            localHostTextFieldFocused = false;
            unregisterMirrorDisplayListener();
            if (mouseRouter != null) {
                mouseRouter.releaseToLocal();
            }
            if (services.isHostScreenOff()) {
                services.setPrimaryPhysicalDisplayPower(true);
            }
        }
        if (s != null) {
            closeSessionResources(s);
            if (!wasCompanion) {
                final boolean wasStandalone = s.standalone;
                worker.execute(() -> {
                    reconcileAudioOwner();
                    if (wasStandalone) {
                        onCompositorWindowsChanged();
                    }
                });
            }
        }
        if (!wasCompanion) {
            notifyRemoteStateChanged();
        }
    }

    @Override
    public void onResizeConfig(int sessionId, OpalCastProtocol.ResizeConfig cfg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || s.companion || cfg == null) return;
        worker.execute(() -> reconfigureSession(s, cfg));
    }

    @Override
    public void onRequestIdr(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && !s.companion && s.sink != null) {
            s.sink.requestKeyFrame();
        }
    }

    @Override
    public void onMouseEvent(int sessionId, OpalCastProtocol.MouseEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || s.companion || msg == null) return;
        int targetDisplayId = s.inputTargetDisplayId();
        if (targetDisplayId < 0) return;
        int targetW = s.inputTargetWidth();
        int targetH = s.inputTargetHeight();
        String targetUid = s.inputTargetUniqueId();
        float[] mapped = DisplayGeometryUtil.mapRemotePointToTargetDisplay(
                msg.x, msg.y, s.width, s.height, targetW, targetH);
        float x = mapped[0];
        float y = mapped[1];
        long now = SystemClock.uptimeMillis();

        if (msg.action == OpalCastProtocol.MOUSE_BUTTON_DOWN
                && (msg.actionButton == OpalCastProtocol.BUTTON_SECONDARY
                || msg.actionButton == OpalCastProtocol.BUTTON_BACK)) {
            inputRouter.injectRawKeyToDisplay(targetDisplayId, targetUid, targetW, targetH,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
            inputRouter.injectRawKeyToDisplay(targetDisplayId, targetUid, targetW, targetH,
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
            if ((s.standalone || s.isMirror()) && !localHostTextFieldFocused && services != null) {
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
                0, s.mouseButtons, 1f, 1f, 0, 0, source, 0);
        try {
            inputRouter.injectRawEventToDisplay(ev, targetDisplayId, targetUid, targetW, targetH);
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
            if ((s.standalone || s.isMirror()) && !localHostTextFieldFocused && services != null) {
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

    @Override
    public void onKeyEvent(int sessionId, OpalCastProtocol.KeyEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || s.companion || msg == null) return;
        int targetDisplayId = s.inputTargetDisplayId();
        if (targetDisplayId < 0) return;
        int action = (msg.action == OpalCastProtocol.KEY_DOWN) ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        if (!s.standalone && !s.isMirror() && !localHostTextFieldFocused && !isWorkstationShortcut(msg.androidKeyCode, msg.metaState)) {
            WindowSession focused = compositor.getFocusedLiveWindow();
            if (focused != null) {
                // Route remote physical/Bluetooth keyboard keys with a positive deviceId so
                // PerDisplayInputRouter injects them via the display-bound /dev/uhid kernel HID
                // keyboard (required by third-party Chinese IMEs like Doubao/Sogou/Gboard/WeChat).
                inputRouter.injectWindowKey(focused, action, msg.androidKeyCode, msg.metaState, 1, 0, msg.repeatCount);
                return;
            }
        }
        inputRouter.injectRawKeyToDisplay(
                targetDisplayId, s.inputTargetUniqueId(), s.inputTargetWidth(), s.inputTargetHeight(),
                action, msg.androidKeyCode, msg.metaState, true);
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
        if (!s.companion
                && msg.mode == OpalCastProtocol.TEXT_MODE_COMMIT_PASTE
                && msg.text != null
                && !msg.text.isEmpty()) {
            DirectImeTextCommitter committer = this.directImeCommitter;
            if (committer != null && committer.tryCommitText(msg.text)) {
                return;
            }
        }
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
        if (s.companion) {
            if (msg.action == OpalCastProtocol.SYS_DISCONNECT) {
                server.disconnectSession(sessionId, "client_system_disconnect");
            }
            return;
        }
        switch (msg.action) {
            case OpalCastProtocol.SYS_BACK:
                if (s.isMirror()) {
                    inputRouter.injectRawKeyToDisplay(
                            Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(),
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
                    inputRouter.injectRawKeyToDisplay(
                            Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(),
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
                } else if (!s.standalone) {
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
                if (s.isMirror()) {
                    inputRouter.injectRawKeyToDisplay(
                            Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(),
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0);
                    inputRouter.injectRawKeyToDisplay(
                            Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(),
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0);
                } else if (!s.standalone) {
                    if (!compositor.exitFullscreenIfActive()) {
                        compositor.minimizeAllWindows();
                    }
                }
                break;
            case OpalCastProtocol.SYS_RECENTS:
                if (s.isMirror()) {
                    inputRouter.injectRawKeyToDisplay(
                            Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(),
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_APP_SWITCH, 0);
                    inputRouter.injectRawKeyToDisplay(
                            Display.DEFAULT_DISPLAY, s.mirrorTargetUniqueId,
                            s.inputTargetWidth(), s.inputTargetHeight(),
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APP_SWITCH, 0);
                }
                break;
            case OpalCastProtocol.SYS_TOGGLE_DISPLAY_MODE:
                if (!s.standalone) {
                    int targetMode = DisplayGeometryUtil.resolveTargetDisplayMode(s.displayMode, msg.arg);
                    worker.execute(() -> switchDesktopDisplayMode(s, targetMode));
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
                s.audioRequested = (msg.arg != 0);
                worker.execute(() -> {
                    reconcileAudioOwner();
                    pushDeviceState(s);
                });
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
    public void onNotificationAction(int sessionId, OpalCastProtocol.NotificationActionMsg msg) {
        if (msg == null || msg.op.isEmpty()) return;
        server.markSessionCompanionEventsSubscribed(sessionId);
        String op = msg.op.toLowerCase(Locale.ROOT);
        worker.execute(() -> {
            notificationRelay.handleRemoteAction(sessionId, msg);
            if ("sync".equals(op)) {
                pushCachedMediaStateOnly(sessionId);
                callRelay.pushCachedCallStateToSession(sessionId, true);
            }
        });
    }

    @Override
    public void onMediaAction(int sessionId, OpalCastProtocol.MediaActionMsg msg) {
        if (msg == null || msg.op.isEmpty()) return;
        if (server.markSessionCompanionEventsSubscribed(sessionId)) {
            worker.execute(() -> pushCachedMediaStateOnly(sessionId));
        }
        MediaActionConsumer mac = mediaActionConsumer;
        if (mac != null) {
            mac.accept(msg.op.toLowerCase(Locale.ROOT), msg.positionMs);
        }
    }

    @Override
    public void onCallAction(int sessionId, OpalCastProtocol.CallActionMsg msg) {
        if (msg == null || msg.op.isEmpty()) return;
        server.markSessionCompanionEventsSubscribed(sessionId);
        worker.execute(() -> callRelay.handleRemoteAction(sessionId, msg));
    }

    @Override
    public void onFileOpRequest(int sessionId, OpalCastProtocol.FileOpRequestMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || s.closed || msg == null) return;
        fileIoWorker.execute(() -> fileManager.executeRequest(
                msg,
                resp -> server.sendFileOpResponse(sessionId, resp),
                chunk -> !s.closed && server.sendFilePullChunk(sessionId, chunk),
                () -> s.closed));
    }

    @Override
    public void onApkInstallRequest(int sessionId, OpalCastProtocol.ApkInstallRequestMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || s.closed || msg == null) return;
        apkInstallWorker.execute(() -> apkInstaller.install(
                msg,
                status -> {
                    if (!s.closed) {
                        server.sendApkInstallStatus(sessionId, status);
                    }
                }));
    }

    @Override
    public void onWifiUpgradeEnabled() {
        System.out.println("OPALDESK_REMOTE_WIFI_UPGRADE_ENABLED port=" + server.getWifiPort());
    }

    @Override
    public void onVideoSoftCongestion(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) s.sink.onSoftNetworkCongestion();
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

    private static int[] desktopVdCandidates() {
        int dcor = BridgeProtocol.VD_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        return new int[]{
                BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor,
                BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS,
                BridgeProtocol.VD_FLAGS_BASE_PUBLIC | dcor,
                BridgeProtocol.VD_FLAGS_BASE_PUBLIC
        };
    }

    private static int[] standaloneVdCandidates() {
        int dcor = BridgeProtocol.VD_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        return new int[]{
                BridgeProtocol.VD_FLAGS_TRUSTED_IME | dcor,
                BridgeProtocol.VD_FLAGS_TRUSTED_IME,
                BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor,
                BridgeProtocol.VD_FLAGS_BASE_PUBLIC | dcor,
                BridgeProtocol.VD_FLAGS_BASE_PUBLIC
        };
    }

    private void releaseSessionVirtualDisplayLocked(ActiveRemoteSession s, boolean removeTasks) {
        if (s.vd == null) return;
        int oldId = s.displayId;
        if (removeTasks && oldId > 0) {
            List<Integer> allTids = displayDriver.findAllTaskIdsOnDisplay(oldId);
            if (allTids != null && !allTids.isEmpty()) {
                for (Integer tid : allTids) {
                    if (tid != null && tid > 0) {
                        displayDriver.removeTask(tid);
                    }
                }
            } else {
                String expectedPkg = s.standalone ? s.appPackage : BridgeBuildConfig.HELPER_PACKAGE;
                int topTid = displayDriver.findTopTaskIdOnDisplay(oldId, expectedPkg);
                if (topTid > 0) {
                    displayDriver.removeTask(topTid);
                }
            }
        }
        try { s.vd.setSurface(null); } catch (RuntimeException ignored) {}
        try { s.vd.release(); } catch (RuntimeException ignored) {}
        s.vd = null;
        s.displayId = -1;
        s.vdUniqueId = "";
        if (oldId > 0) {
            inputRouter.releaseDisplayResources(oldId);
        }
    }

    private void bindVirtualDisplayMetadataLocked(ActiveRemoteSession s, VirtualDisplay vd) {
        Display disp = vd.getDisplay();
        s.vd = vd;
        s.displayId = disp.getDisplayId();
        String uid = VirtualDisplayFactory.getUniqueId(disp);
        if (uid == null || uid.isEmpty()) {
            uid = services.getDisplayUniqueId(s.displayId);
        }
        s.vdUniqueId = uid != null ? uid : "";
    }

    private void populatePrimaryMirrorStateLocked(ActiveRemoteSession s, int[] d0State) {
        int d0W = (d0State != null && d0State.length >= 4 && d0State[0] > 0) ? d0State[0] : 1080;
        int d0H = (d0State != null && d0State.length >= 4 && d0State[1] > 0) ? d0State[1] : 1920;
        int d0Dpi = (d0State != null && d0State.length >= 4 && d0State[2] > 0) ? d0State[2] : 420;
        int d0Rot = (d0State != null && d0State.length >= 4) ? DisplayGeometryUtil.normalizeRotation(d0State[3]) : 0;
        s.mirrorSourceWidth = d0W;
        s.mirrorSourceHeight = d0H;
        s.mirrorSourceDpi = d0Dpi;
        s.mirrorRotation = d0Rot;
        String d0Uid = services.getDisplayUniqueId(Display.DEFAULT_DISPLAY);
        s.mirrorTargetUniqueId = (d0Uid != null) ? d0Uid : "";
    }

    private void setupSession(int sessionId, int sinkType, OpalCastProtocol.HelloMsg hello) {
        if (hello != null && hello.isAppCatalog()) {
            // Lightweight app-catalog probe session: no VirtualDisplay or encoder needed.
            System.out.println("OPALDESK_REMOTE_CATALOG_SESSION_READY sid=" + sessionId);
            return;
        }
        if (hello != null && hello.isCompanion()) {
            // Background lightweight companion session: zero VirtualDisplay, zero video/audio encoder!
            ActiveRemoteSession comp = new ActiveRemoteSession(
                    sessionId, sinkType, false, true, "", "", hello.clientPlatform);
            synchronized (comp.lock) {
                comp.width = 0;
                comp.height = 0;
                comp.dpi = 0;
                comp.fps = 0f;
                comp.bitrateKbps = 0;
                comp.audioRequested = false;
                comp.clipboard = new ClipboardSyncService(new ClipboardSyncService.ClipboardHost() {
                    @Override public void sendClipboardPush(long seq, String text) {
                        server.sendClipboardPush(sessionId, seq, text);
                    }
                    @Override public void sendClipboardImagePush(long seq, byte[] pngData) {
                        server.sendClipboardImagePush(sessionId, seq, pngData);
                    }
                    @Override public void triggerPasteKey() {
                        // Companion sessions only update the clipboard; never inject synthetic Ctrl+V keys.
                    }
                }, null, true);
                comp.fileReceiver = new FileTransferReceiver((transferId, status, pathOrError) ->
                        server.sendFileTransferAck(sessionId, transferId, status, pathOrError));
                sessions.put(sessionId, comp);
                comp.clipboard.onSessionStarted();
                refreshHostTopAppIfChanged();
                pushDeviceState(comp);
                pushCachedCompanionSnapshotsToSession(sessionId, true);
                System.out.println("OPALDESK_REMOTE_COMPANION_SESSION_READY sid=" + sessionId
                        + " sinkType=" + sinkType);
            }
            return;
        }
        boolean standalone = hello.isStandaloneApp();
        ActiveRemoteSession s = new ActiveRemoteSession(
                sessionId, sinkType, standalone, false, hello.appPackage, hello.appActivity, hello.clientPlatform);
        synchronized (s.lock) {
            if (standalone) {
                int[] spec = resolveStandaloneStreamSpec(s.appPackage, hello.width, hello.height, hello.dpi);
                s.width = spec[0];
                s.height = spec[1];
                s.dpi = spec[2];
                s.displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
            } else {
                int[] aligned = DisplayGeometryUtil.alignForEncoder(hello.width, hello.height, 8);
                s.dexWidth = aligned[0];
                s.dexHeight = aligned[1];
                s.dexDpi = DisplayGeometryUtil.clampDpi(hello.dpi, s.dexWidth, s.dexHeight);
                s.dexRequestedBitrateKbps = hello.bitrateKbps;
                s.initialWidth = s.dexWidth;
                s.initialHeight = s.dexHeight;
                s.initialDpi = s.dexDpi;
                boolean wantMirror = (hello.displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR);
                if (wantMirror) {
                    int[] d0 = services.getPrimaryDisplayState();
                    populatePrimaryMirrorStateLocked(s, d0);
                    int[] mSpec = DisplayGeometryUtil.computeMirrorStreamSpec(
                            s.mirrorSourceWidth, s.mirrorSourceHeight, s.mirrorSourceDpi,
                            Math.max(s.dexWidth, s.dexHeight), 8);
                    s.width = mSpec[0];
                    s.height = mSpec[1];
                    s.dpi = mSpec[2];
                    s.displayMode = OpalCastProtocol.DISPLAY_MODE_MIRROR;
                } else {
                    s.width = s.dexWidth;
                    s.height = s.dexHeight;
                    s.dpi = s.dexDpi;
                    s.displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
                }
            }
            float nativeFps = DisplayGeometryUtil.clampFps(hello.fps > 0 ? hello.fps : 60f, 60f);
            s.requestedNativeFps = nativeFps;
            int cfgFps = config.getTargetFps();
            s.fps = DisplayGeometryUtil.clampFps(cfgFps > 0 ? cfgFps : nativeFps, nativeFps);
            s.codecPref = "h264".equalsIgnoreCase(hello.preferredCodec)
                    ? RemoteStreamSink.CODEC_PREF_H264 : RemoteStreamSink.CODEC_PREF_AUTO_HEVC;
            int reqBitrate = s.isMirror() ? 0 : hello.bitrateKbps;
            s.bitrateKbps = resolveSessionBitrateKbps(
                    reqBitrate, s.width, s.height, s.fps, s.codecPref, s.sinkType);

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
                public void onVideoFrame(
                        byte[] annexB, int length, int flags, long tsUs, Consumer<byte[]> onRelease) {
                    server.sendVideoFrame(sessionId, annexB, length, flags, tsUs, onRelease);
                }

                @Override
                public void onEncoderError(String reason) {
                    System.err.println("OPALDESK_REMOTE_ENC_ERR sid=" + sessionId + ": " + reason);
                }
            });
            sink.setMaxBitrateKbps(resolveMaxBitrateKbpsForSink(sinkType));
            sink.setRepeatPreviousFrameAfterUs(
                    RemoteStreamSink.resolveRepeatPreviousFrameAfterUs(s.clientPlatform, s.fps));
            int startRot = s.isMirror() ? s.mirrorRotation : 0;
            Surface codecSurf = sink.startOrReconfigure(
                    s.width, s.height, s.dpi, s.fps, s.bitrateKbps,
                    s.codecPref, s.displayMode, startRot);
            if (codecSurf == null) {
                server.disconnectSession(sessionId, "encoder_start_failed");
                return;
            }
            s.sink = sink;
            s.width = sink.getWidth();
            s.height = sink.getHeight();
            s.dpi = sink.getDpi();
            s.fps = sink.getFps();
            server.updateSessionTargetFps(sessionId, s.fps);
            if (standalone) {
                s.initialWidth = s.width;
                s.initialHeight = s.height;
                s.initialDpi = s.dpi;
            } else if (!s.isMirror()) {
                s.dexWidth = s.width;
                s.dexHeight = s.height;
                s.dexDpi = s.dpi;
                s.initialWidth = s.width;
                s.initialHeight = s.height;
                s.initialDpi = s.dpi;
            }

            VirtualDisplay vd = null;
            if (s.isMirror()) {
                vd = VirtualDisplayFactory.createMirrorDisplay(
                        services.displayManager, "OpalDesk-Mirror",
                        s.width, s.height, s.dpi, Display.DEFAULT_DISPLAY, codecSurf);
                if (vd == null || vd.getDisplay() == null) {
                    // Graceful fallback to DeX if ROM rejects AUTO_MIRROR at session start
                    System.err.println("OPALDESK_REMOTE_MIRROR_START_FALLBACK_DEX sid=" + sessionId);
                    s.displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
                    s.width = s.dexWidth;
                    s.height = s.dexHeight;
                    s.dpi = s.dexDpi;
                    s.bitrateKbps = resolveSessionBitrateKbps(
                            s.dexRequestedBitrateKbps, s.width, s.height, s.fps, s.codecPref, s.sinkType);
                    codecSurf = sink.startOrReconfigure(
                            s.width, s.height, s.dpi, s.fps, s.bitrateKbps,
                            s.codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, 0);
                    if (codecSurf == null) {
                        sink.stop();
                        server.disconnectSession(sessionId, "encoder_start_failed");
                        return;
                    }
                    s.width = sink.getWidth();
                    s.height = sink.getHeight();
                    s.dpi = sink.getDpi();
                    s.dexWidth = s.width;
                    s.dexHeight = s.height;
                    s.dexDpi = s.dpi;
                }
            }
            if (!s.isMirror()) {
                String vdName = standalone ? ("OpalDesk-App-" + s.appPackage) : "OpalDesk-Remote";
                int[] candidates = standalone ? standaloneVdCandidates() : desktopVdCandidates();
                int[] chosenFlags = new int[1];
                // Always omit requestedRefreshRate (0f) on the remote VirtualDisplay: on Android 14-16
                // pinning 60Hz causes SurfaceFlinger to stall output to 0 FPS during 120Hz Display 0
                // Touch Boost, while pinning 120Hz causes jitter when Display 0 idles at 60Hz. Leaving
                // it 0f lets SurfaceFlinger pace smoothly across every pacesetter refresh transition.
                if (standalone) {
                    RootCameraBridge cam = this.cameraBridge;
                    if (cam != null && cam.hasAospVirtualCamera()) {
                        for (int candFlags : candidates) {
                            VirtualDisplay customVd = cam.createVirtualDisplay(
                                    vdName, s.width, s.height, s.dpi, candFlags, 0f, codecSurf);
                            if (customVd != null && customVd.getDisplay() != null) {
                                vd = customVd;
                                chosenFlags[0] = candFlags;
                                break;
                            }
                        }
                    }
                }
                if (vd == null) {
                    vd = VirtualDisplayFactory.createWithFlagFallback(
                            services.displayManager, vdName, s.width, s.height, s.dpi, candidates, 0f, codecSurf, chosenFlags);
                }
                s.vdTrusted = (chosenFlags[0] & BridgeProtocol.VD_FLAG_TRUSTED) != 0;
            }
            if (vd == null || vd.getDisplay() == null) {
                sink.stop();
                server.disconnectSession(sessionId, "virtual_display_failed");
                return;
            }
            bindVirtualDisplayMetadataLocked(s, vd);
            if (!s.isMirror()) {
                int remoteImePolicy = MouseRoutingPolicy.resolveRemoteOuterDisplayImePolicy(
                        config.getDexImeDisplayMode(), config.isMuteHostImeOnCast(), s.vdTrusted, isBridgeImeActive());
                services.configureVirtualDisplayWindowing(s.displayId, remoteImePolicy);
            }

            s.clipboard = new ClipboardSyncService(new ClipboardSyncService.ClipboardHost() {
                @Override public void sendClipboardPush(long seq, String text) {
                    server.sendClipboardPush(sessionId, seq, text);
                }
                @Override public void sendClipboardImagePush(long seq, byte[] pngData) {
                    server.sendClipboardImagePush(sessionId, seq, pngData);
                }
                @Override public void triggerPasteKey() {
                    int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                    if (!s.standalone && !s.isMirror() && !localHostTextFieldFocused) {
                        WindowSession focused = compositor.getFocusedLiveWindow();
                        if (focused != null) {
                            inputRouter.injectWindowKey(focused, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl);
                            inputRouter.injectWindowKey(focused, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl);
                            return;
                        }
                    }
                    int targetDisplayId = s.inputTargetDisplayId();
                    if (targetDisplayId < 0) return;
                    inputRouter.injectRawKeyToDisplay(
                            targetDisplayId, s.inputTargetUniqueId(), s.inputTargetWidth(), s.inputTargetHeight(),
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl);
                    inputRouter.injectRawKeyToDisplay(
                            targetDisplayId, s.inputTargetUniqueId(), s.inputTargetWidth(), s.inputTargetHeight(),
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl);
                }
            }, null, !standalone);
            s.fileReceiver = new FileTransferReceiver((transferId, status, pathOrError) ->
                    server.sendFileTransferAck(sessionId, transferId, status, pathOrError));

            s.audioRequested = hello.audioEnabled;
            sessions.put(sessionId, s);
            if (!standalone) {
                desktopSessionId = sessionId;
                if (s.isMirror()) {
                    if (mouseRouter != null) {
                        mouseRouter.releaseToLocal();
                    }
                    registerMirrorDisplayListener();
                } else {
                    launchRemoteDesktopHost(s.displayId);
                    if (mouseRouter != null) {
                        mouseRouter.setMouseTarget(config.getMouseTargetMode(), s.displayId);
                    }
                }
            } else if (!s.appPackage.isEmpty()) {
                config.recordAppLaunch(s.appPackage);
                displayDriver.launchOrMoveFullscreenApp(s.appPackage, s.appActivity, s.displayId, -1);
                onCompositorWindowsChanged();
            }
            reconcileAudioOwner();

            s.clipboard.onSessionStarted();
            if (!standalone) {
                refreshHostTopAppIfChanged();
            }
            pushDeviceState(s);
            if (!standalone && hello.companionEvents) {
                pushCachedCompanionSnapshotsToSession(sessionId, true);
            }
            notifyRemoteStateChanged();
            System.out.println("OPALDESK_REMOTE_SESSION_READY sid=" + sessionId
                    + " role=" + (standalone ? "standalone" : (s.isMirror() ? "mirror" : "desktop"))
                    + " displayId=" + s.displayId + " " + s.width + "x" + s.height + "@" + s.fps
                    + " vdUid=" + s.vdUniqueId);
        }
    }

    private void switchDesktopDisplayMode(ActiveRemoteSession s, int targetMode) {
        if (s == null || s.standalone) return;
        int normalized = (targetMode == OpalCastProtocol.DISPLAY_MODE_MIRROR)
                ? OpalCastProtocol.DISPLAY_MODE_MIRROR
                : OpalCastProtocol.DISPLAY_MODE_DEX;
        synchronized (s.lock) {
            if (s.closed || s.sink == null) return;
            if (s.displayMode == normalized) {
                pushDeviceState(s);
                return;
            }
            if (normalized == OpalCastProtocol.DISPLAY_MODE_MIRROR) {
                switchToMirrorModeLocked(s);
            } else {
                switchToDexModeLocked(s);
            }
        }
    }

    private void switchToMirrorModeLocked(ActiveRemoteSession s) {
        int[] d0 = services.getPrimaryDisplayState();
        populatePrimaryMirrorStateLocked(s, d0);
        int[] mSpec = DisplayGeometryUtil.computeMirrorStreamSpec(
                s.mirrorSourceWidth, s.mirrorSourceHeight, s.mirrorSourceDpi,
                Math.max(s.dexWidth, s.dexHeight), 8);
        int mirrorBitrate = resolveSessionBitrateKbps(0, mSpec[0], mSpec[1], s.fps, s.codecPref, s.sinkType);
        final boolean[] vdSwapped = new boolean[]{false};

        boolean ok = s.sink.reconfigureAndSwap(
                mSpec[0], mSpec[1], mSpec[2], s.fps, mirrorBitrate,
                s.codecPref, OpalCastProtocol.DISPLAY_MODE_MIRROR, s.mirrorRotation,
                (newSurface, alignedW, alignedH, alignedDpi) -> {
                    releaseSessionVirtualDisplayLocked(s, true);
                    VirtualDisplay mirrorVd = VirtualDisplayFactory.createMirrorDisplay(
                            services.displayManager, "OpalDesk-Mirror",
                            alignedW, alignedH, alignedDpi, Display.DEFAULT_DISPLAY, newSurface);
                    if (mirrorVd != null && mirrorVd.getDisplay() != null) {
                        bindVirtualDisplayMetadataLocked(s, mirrorVd);
                        s.displayMode = OpalCastProtocol.DISPLAY_MODE_MIRROR;
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.bitrateKbps = mirrorBitrate;
                        vdSwapped[0] = true;
                    }
                });

        if (!ok || !vdSwapped[0]) {
            System.err.println("OPALDESK_REMOTE_MODE_SWITCH_FAILED sid=" + s.sessionId + " target=MIRROR");
            if (s.vd == null) {
                restoreDexDisplayLocked(s);
            }
            pushDeviceState(s);
            notifyRemoteStateChanged();
            return;
        }

        if (mouseRouter != null) {
            mouseRouter.releaseToLocal();
        }
        services.focusDisplay(Display.DEFAULT_DISPLAY);
        syncAudioRoutingForMode(true);
        registerMirrorDisplayListener();
        pushDeviceState(s);
        notifyRemoteStateChanged();
        System.out.println("OPALDESK_REMOTE_MODE_SWITCH sid=" + s.sessionId
                + " mode=MIRROR stream=" + s.width + "x" + s.height + "@" + s.dpi
                + " d0=" + s.mirrorSourceWidth + "x" + s.mirrorSourceHeight + " rot=" + s.mirrorRotation
                + " vdDisplayId=" + s.displayId);
    }

    private void switchToDexModeLocked(ActiveRemoteSession s) {
        unregisterMirrorDisplayListener();
        restoreDexDisplayLocked(s);
        pushDeviceState(s);
        notifyRemoteStateChanged();
    }

    private void restoreDexDisplayLocked(ActiveRemoteSession s) {
        int targetW = s.dexWidth > 0 ? s.dexWidth : s.initialWidth;
        int targetH = s.dexHeight > 0 ? s.dexHeight : s.initialHeight;
        int targetDpi = s.dexDpi > 0 ? s.dexDpi : s.initialDpi;
        int dexBitrate = resolveSessionBitrateKbps(
                s.dexRequestedBitrateKbps, targetW, targetH, s.fps, s.codecPref, s.sinkType);
        final boolean[] vdSwapped = new boolean[]{false};

        boolean ok = s.sink.reconfigureAndSwap(
                targetW, targetH, targetDpi, s.fps, dexBitrate,
                s.codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, 0,
                (newSurface, alignedW, alignedH, alignedDpi) -> {
                    boolean wasDex = !s.isMirror();
                    releaseSessionVirtualDisplayLocked(s, wasDex);
                    int[] chosenFlags = new int[1];
                    VirtualDisplay dexVd = VirtualDisplayFactory.createWithFlagFallback(
                            services.displayManager, "OpalDesk-Remote",
                            alignedW, alignedH, alignedDpi, desktopVdCandidates(), 0f, newSurface, chosenFlags);
                    if (dexVd != null && dexVd.getDisplay() != null) {
                        s.vdTrusted = (chosenFlags[0] & BridgeProtocol.VD_FLAG_TRUSTED) != 0;
                        bindVirtualDisplayMetadataLocked(s, dexVd);
                        int remoteImePolicy = MouseRoutingPolicy.resolveRemoteOuterDisplayImePolicy(
                                config.getDexImeDisplayMode(), config.isMuteHostImeOnCast(), s.vdTrusted, isBridgeImeActive());
                        services.configureVirtualDisplayWindowing(s.displayId, remoteImePolicy);
                        s.displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.dexWidth = alignedW;
                        s.dexHeight = alignedH;
                        s.dexDpi = alignedDpi;
                        s.bitrateKbps = dexBitrate;
                        vdSwapped[0] = true;
                    }
                });

        if (!ok || !vdSwapped[0]) {
            System.err.println("OPALDESK_REMOTE_MODE_SWITCH_FAILED sid=" + s.sessionId + " target=DEX");
            return;
        }
        launchRemoteDesktopHost(s.displayId);
        if (mouseRouter != null) {
            mouseRouter.setMouseTarget(config.getMouseTargetMode(), s.displayId);
        }
        syncAudioRoutingForMode(false);
        System.out.println("OPALDESK_REMOTE_MODE_SWITCH sid=" + s.sessionId
                + " mode=DEX size=" + s.width + "x" + s.height + "@" + s.dpi
                + " displayId=" + s.displayId);
    }

    private void registerMirrorDisplayListener() {
        if (services.displayManager == null) return;
        synchronized (mirrorListenerLock) {
            if (mirrorDisplayListener != null) return;
            DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) return;
                    int sid = desktopSessionId;
                    ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
                    if (s != null && !s.closed && s.isMirror()) {
                        worker.execute(() -> syncMirrorOrientationIfChanged(s));
                    }
                }
            };
            try {
                Looper mainLooper = Looper.getMainLooper();
                Handler handler = (mainLooper != null) ? new Handler(mainLooper) : null;
                services.displayManager.registerDisplayListener(listener, handler);
                mirrorDisplayListener = listener;
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_MIRROR_LISTENER_WARN: " + e.getMessage());
            }
        }
    }

    private void unregisterMirrorDisplayListener() {
        DisplayManager.DisplayListener old;
        synchronized (mirrorListenerLock) {
            old = mirrorDisplayListener;
            mirrorDisplayListener = null;
        }
        if (old != null && services.displayManager != null) {
            try {
                services.displayManager.unregisterDisplayListener(old);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void syncMirrorOrientationIfChanged(ActiveRemoteSession s) {
        synchronized (s.lock) {
            if (s.closed || !s.isMirror() || s.sink == null || s.vd == null) return;
            int[] d0 = services.getPrimaryDisplayState();
            int d0W = (d0.length >= 4 && d0[0] > 0) ? d0[0] : s.mirrorSourceWidth;
            int d0H = (d0.length >= 4 && d0[1] > 0) ? d0[1] : s.mirrorSourceHeight;
            int d0Dpi = (d0.length >= 4 && d0[2] > 0) ? d0[2] : s.mirrorSourceDpi;
            int d0Rot = (d0.length >= 4) ? DisplayGeometryUtil.normalizeRotation(d0[3]) : s.mirrorRotation;
            if (d0W == s.mirrorSourceWidth && d0H == s.mirrorSourceHeight && d0Rot == s.mirrorRotation) {
                return;
            }
            s.mirrorSourceWidth = d0W;
            s.mirrorSourceHeight = d0H;
            s.mirrorSourceDpi = d0Dpi;
            s.mirrorRotation = d0Rot;
            int[] mSpec = DisplayGeometryUtil.computeMirrorStreamSpec(
                    d0W, d0H, d0Dpi, Math.max(s.dexWidth, s.dexHeight), 8);
            if (mSpec[0] == s.width && mSpec[1] == s.height) {
                pushDeviceState(s);
                return;
            }
            int mirrorBitrate = resolveSessionBitrateKbps(0, mSpec[0], mSpec[1], s.fps, s.codecPref, s.sinkType);
            boolean ok = s.sink.reconfigureAndSwap(
                    mSpec[0], mSpec[1], mSpec[2], s.fps, mirrorBitrate,
                    s.codecPref, OpalCastProtocol.DISPLAY_MODE_MIRROR, d0Rot,
                    (newSurface, alignedW, alignedH, alignedDpi) -> {
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.bitrateKbps = mirrorBitrate;
                        try {
                            s.vd.resize(alignedW, alignedH, alignedDpi);
                            s.vd.setSurface(newSurface);
                        } catch (RuntimeException e) {
                            System.err.println("OPALDESK_MIRROR_ROTATE_WARN: " + e.getMessage());
                        }
                    });
            if (!ok) return;
            server.updateSessionTargetFps(s.sessionId, s.fps);
            pushDeviceState(s);
            notifyRemoteStateChanged();
            System.out.println("OPALDESK_REMOTE_MIRROR_ROTATED sid=" + s.sessionId
                    + " d0=" + d0W + "x" + d0H + " rot=" + d0Rot
                    + " stream=" + s.width + "x" + s.height + "@" + s.dpi);
        }
    }

    private void reconfigureSession(ActiveRemoteSession s, OpalCastProtocol.ResizeConfig cfg) {
        synchronized (s.lock) {
            if (s.closed || s.sink == null || s.vd == null) return;
            float nextFps = (cfg.fpsX100 > 0) ? DisplayGeometryUtil.clampFps(cfg.fpsX100 / 100f, s.fps) : s.fps;

            if (!s.standalone) {
                if (cfg.width > 0 && cfg.height > 0) {
                    int[] alignedDex = DisplayGeometryUtil.alignForEncoder(cfg.width, cfg.height, 8);
                    s.dexWidth = alignedDex[0];
                    s.dexHeight = alignedDex[1];
                    if (cfg.dpi > 0) {
                        s.dexDpi = DisplayGeometryUtil.clampDpi(cfg.dpi, s.dexWidth, s.dexHeight);
                    }
                } else if (cfg.dpi > 0) {
                    s.dexDpi = DisplayGeometryUtil.clampDpi(cfg.dpi, s.dexWidth, s.dexHeight);
                }
                if (cfg.bitrateKbps > 0) {
                    s.dexRequestedBitrateKbps = cfg.bitrateKbps;
                }
                s.fps = nextFps;

                if ((cfg.displayMode == OpalCastProtocol.DISPLAY_MODE_DEX
                        || cfg.displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR)
                        && cfg.displayMode != s.displayMode) {
                    if (cfg.displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR) {
                        switchToMirrorModeLocked(s);
                    } else {
                        switchToDexModeLocked(s);
                    }
                    return;
                }
            }

            int nextW;
            int nextH;
            int nextDpi;
            int nextBitrate;
            int nextMode = s.displayMode;
            int nextRot = 0;

            if (s.standalone) {
                int reqW = cfg.width > 0 ? cfg.width : s.width;
                int reqH = cfg.height > 0 ? cfg.height : s.height;
                int[] spec = resolveStandaloneStreamSpec(s.appPackage, reqW, reqH, cfg.dpi);
                nextW = spec[0];
                nextH = spec[1];
                nextDpi = spec[2];
                nextBitrate = resolveSessionBitrateKbps(cfg.bitrateKbps, nextW, nextH, nextFps, s.codecPref, s.sinkType);
                nextMode = OpalCastProtocol.DISPLAY_MODE_DEX;
            } else if (s.isMirror()) {
                int[] d0 = services.getPrimaryDisplayState();
                populatePrimaryMirrorStateLocked(s, d0);
                int[] mSpec = DisplayGeometryUtil.computeMirrorStreamSpec(
                        s.mirrorSourceWidth, s.mirrorSourceHeight, s.mirrorSourceDpi,
                        Math.max(s.dexWidth, s.dexHeight), 8);
                nextW = mSpec[0];
                nextH = mSpec[1];
                nextDpi = mSpec[2];
                nextBitrate = resolveSessionBitrateKbps(cfg.bitrateKbps, nextW, nextH, nextFps, s.codecPref, s.sinkType);
                nextMode = OpalCastProtocol.DISPLAY_MODE_MIRROR;
                nextRot = s.mirrorRotation;
            } else {
                nextW = s.dexWidth;
                nextH = s.dexHeight;
                nextDpi = s.dexDpi;
                nextBitrate = resolveSessionBitrateKbps(cfg.bitrateKbps, nextW, nextH, nextFps, s.codecPref, s.sinkType);
                nextMode = OpalCastProtocol.DISPLAY_MODE_DEX;
            }

            DisplayGeometryUtil.ReconfigureAction action = DisplayGeometryUtil.planReconfigureAction(
                    s.sink.getWidth(), s.sink.getHeight(), s.sink.getDpi(),
                    s.sink.getFps(), s.bitrateKbps, s.sink.getDisplayMode(), s.sink.getRotation(),
                    nextW, nextH, nextDpi, nextFps, nextBitrate, nextMode, nextRot);
            if (action == DisplayGeometryUtil.ReconfigureAction.NO_OP) {
                return;
            }
            if (action == DisplayGeometryUtil.ReconfigureAction.BITRATE_ONLY) {
                s.sink.updateBitrateKbps(nextBitrate);
                s.bitrateKbps = nextBitrate;
                pushDeviceState(s);
                notifyRemoteStateChanged();
                return;
            }
            if (action == DisplayGeometryUtil.ReconfigureAction.DPI_ONLY) {
                try {
                    s.vd.resize(s.width, s.height, nextDpi);
                    s.dpi = nextDpi;
                    if (!s.standalone && !s.isMirror()) {
                        s.dexDpi = nextDpi;
                    }
                    s.sink.updateDpiOnly(nextDpi);
                    if (nextBitrate != s.bitrateKbps) {
                        s.sink.updateBitrateKbps(nextBitrate);
                        s.bitrateKbps = nextBitrate;
                    }
                    pushDeviceState(s);
                    notifyRemoteStateChanged();
                    return;
                } catch (RuntimeException e) {
                    System.err.println("OPALDESK_REMOTE_DPI_FASTPATH_WARN: " + e.getMessage());
                }
            }

            s.sink.setRepeatPreviousFrameAfterUs(
                    RemoteStreamSink.resolveRepeatPreviousFrameAfterUs(s.clientPlatform, nextFps));
            boolean ok = s.sink.reconfigureAndSwap(
                    nextW, nextH, nextDpi, nextFps, nextBitrate,
                    s.codecPref, nextMode, nextRot,
                    (newSurface, alignedW, alignedH, alignedDpi) -> {
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.fps = nextFps;
                        s.bitrateKbps = nextBitrate;
                        if (!s.standalone && !s.isMirror()) {
                            s.dexWidth = alignedW;
                            s.dexHeight = alignedH;
                            s.dexDpi = alignedDpi;
                        }
                        try {
                            s.vd.resize(alignedW, alignedH, alignedDpi);
                            s.vd.setSurface(newSurface);
                        } catch (RuntimeException e) {
                            System.err.println("OPALDESK_REMOTE_RESIZE_WARN: " + e.getMessage());
                        }
                    });
            if (!ok) return;
            server.updateSessionTargetFps(s.sessionId, s.fps);
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

    public static int resolveSessionBitrateKbps(
            int requestedBitrateKbps, int w, int h, float fps, int codecPref) {
        return resolveSessionBitrateKbps(
                requestedBitrateKbps, w, h, fps, codecPref, DisplaySink.SINK_REMOTE_WIFI);
    }

    public static int resolveSessionBitrateKbps(
            int requestedBitrateKbps, int w, int h, float fps, int codecPref, int sinkType) {
        return DisplayGeometryUtil.resolveSessionBitrateKbps(
                requestedBitrateKbps,
                w,
                h,
                fps,
                codecPref != RemoteStreamSink.CODEC_PREF_H264,
                resolveMaxBitrateKbpsForSink(sinkType));
    }

    private void launchRemoteDesktopHost(int displayId) {
        boolean ok = services.launchExternalDesktopHost(displayId, false, false);
        System.out.println("OPALDESK_REMOTE_HOST_LAUNCH displayId=" + displayId + " ok=" + ok);
    }

    private void reconcileAudioOwner() {
        synchronized (audioLock) {
            List<AudioRoutingPolicy.AudioSessionCandidate> candidates = new ArrayList<>();
            for (ActiveRemoteSession session : sessions.values()) {
                if (session != null && !session.closed && !session.companion) {
                    candidates.add(new AudioRoutingPolicy.AudioSessionCandidate(
                            session.sessionId, session.standalone, session.audioRequested));
                }
            }
            int prevOwner = this.audioOwnerSessionId;
            int nextOwner = AudioRoutingPolicy.selectAudioOwnerSessionId(
                    prevOwner, desktopSessionId, candidates);
            if (nextOwner <= 0) {
                stopAudioLocked();
                return;
            }

            boolean splitByApp = !isSessionInMirrorMode(nextOwner)
                    && (config.getAudioRouteMode() == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP);
            List<Integer> targetUids = splitByApp ? collectDexAppUids() : Collections.emptyList();

            AudioStreamCapturer cap = this.audioCapturer;
            if (cap == null || !cap.isActive()) {
                startAudioForOwnerLocked(nextOwner, splitByApp, targetUids);
                return;
            }

            boolean ownerChanged = (prevOwner != nextOwner);
            this.audioOwnerSessionId = nextOwner;
            cap.setSplitMode(splitByApp, targetUids);
            if (ownerChanged) {
                OpalCastProtocol.AudioConfig cached = this.lastAudioConfig;
                if (cached != null) {
                    server.sendAudioConfig(nextOwner, cached, SystemClock.elapsedRealtimeNanos() / 1000L);
                }
                System.out.println("OPALDESK_REMOTE_AUDIO_OWNER sid=" + nextOwner + " split=" + splitByApp);
            }
        }
    }

    private void startAudioForOwnerLocked(int ownerSessionId, boolean splitByApp, List<Integer> initialUids) {
        stopAudioLocked();
        this.audioOwnerSessionId = ownerSessionId;
        final AudioStreamCapturer[] holder = new AudioStreamCapturer[1];
        AudioStreamCapturer cap = new AudioStreamCapturer(services.context, new AudioStreamCapturer.AudioListener() {
            @Override
            public void onAudioConfig(OpalCastProtocol.AudioConfig cfg, long tsUs) {
                int targetSid;
                synchronized (audioLock) {
                    if (audioCapturer != holder[0]) return;
                    lastAudioConfig = cfg;
                    targetSid = audioOwnerSessionId;
                }
                if (targetSid > 0) {
                    server.sendAudioConfig(targetSid, cfg, tsUs);
                }
            }

            @Override
            public void onAudioFrame(byte[] aacAu, long tsUs) {
                int targetSid = audioOwnerSessionId;
                if (targetSid > 0 && audioCapturer == holder[0]) {
                    server.sendAudioFrame(targetSid, aacAu, tsUs);
                }
            }

            @Override
            public void onAudioStateChanged(boolean active) {
                if (!active) {
                    synchronized (audioLock) {
                        if (audioCapturer == holder[0]) {
                            audioCapturer = null;
                            audioOwnerSessionId = -1;
                            lastAudioConfig = null;
                        }
                    }
                }
                System.out.println("OPALDESK_REMOTE_AUDIO_STATE active=" + active + " owner=" + audioOwnerSessionId);
            }
        });
        holder[0] = cap;
        this.audioCapturer = cap;
        if (!cap.start(splitByApp, initialUids)) {
            if (this.audioCapturer == cap) {
                this.audioCapturer = null;
                this.audioOwnerSessionId = -1;
                this.lastAudioConfig = null;
            }
        } else {
            System.out.println("OPALDESK_REMOTE_AUDIO_OWNER sid=" + ownerSessionId + " split=" + splitByApp);
        }
    }

    private boolean isSessionInMirrorMode(int sessionId) {
        if (sessionId <= 0) return false;
        ActiveRemoteSession s = sessions.get(sessionId);
        return s != null && !s.closed && s.isMirror();
    }

    private boolean isAudioOwnerInMirrorMode() {
        return isSessionInMirrorMode(audioOwnerSessionId);
    }

    private void syncAudioRoutingForMode(boolean mirrorMode) {
        synchronized (audioLock) {
            AudioStreamCapturer cap = audioCapturer;
            if (cap == null || !cap.isActive()) return;
            boolean effectiveMirror = mirrorMode && (audioOwnerSessionId == desktopSessionId);
            boolean splitByApp = !effectiveMirror && (config.getAudioRouteMode() == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP);
            List<Integer> uids = splitByApp ? collectDexAppUids() : Collections.emptyList();
            cap.setSplitMode(splitByApp, uids);
        }
    }

    public void onCompositorWindowsChanged() {
        synchronized (audioLock) {
            AudioStreamCapturer cap = audioCapturer;
            if (cap != null && cap.isActive()
                    && !isAudioOwnerInMirrorMode()
                    && config.getAudioRouteMode() == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP) {
                cap.updateTargetUids(collectDexAppUids());
            }
        }
        int deskSid = desktopSessionId;
        if (deskSid > 0) {
            ActiveRemoteSession s = sessions.get(deskSid);
            if (s != null && !s.closed && !s.standalone && !s.isMirror()) {
                String fsTitle = compositor.getFullscreenWindowTitle();
                if (!fsTitle.equals(s.lastPushedFullscreenTitle)) {
                    pushDeviceState(s);
                }
            }
        }
    }

    public void applyAudioRouteMode(int mode) {
        worker.execute(() -> {
            synchronized (audioLock) {
                AudioStreamCapturer cap = audioCapturer;
                if (cap == null || !cap.isActive()) return;
                boolean splitByApp = !isAudioOwnerInMirrorMode()
                        && (mode == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP);
                List<Integer> uids = splitByApp ? collectDexAppUids() : Collections.emptyList();
                cap.setSplitMode(splitByApp, uids);
            }
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
        synchronized (audioLock) {
            stopAudioLocked();
        }
    }

    private void stopAudioLocked() {
        AudioStreamCapturer cap = audioCapturer;
        audioCapturer = null;
        audioOwnerSessionId = -1;
        lastAudioConfig = null;
        if (cap != null) {
            try { cap.stop(); } catch (RuntimeException ignored) {}
        }
    }

    public void applyRemoteMediaPolicy(boolean micEnabled, boolean cameraEnabled) {
        applyRemoteMediaPolicy(micEnabled, cameraEnabled, false, false);
    }

    public void applyRemoteMediaPolicy(
            boolean micEnabled,
            boolean cameraEnabled,
            boolean triggerMicStart,
            boolean triggerCameraStart) {
        if (!micEnabled) {
            stopRemoteMic(true);
        } else if (triggerMicStart) {
            int deskSid = desktopSessionId;
            ActiveRemoteSession s = (deskSid > 0) ? sessions.get(deskSid) : null;
            if (s != null && !s.closed && micOwnerSessionId != deskSid) {
                server.sendMicState(deskSid, new OpalCastProtocol.MicStateMsg(
                        true,
                        true,
                        OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE,
                        OpalCastProtocol.DEFAULT_MIC_CHANNELS,
                        ""));
            }
        }
        if (!cameraEnabled) {
            stopRemoteCamera(true);
        } else if (triggerCameraStart && config.isRootEnhanced()) {
            int deskSid = desktopSessionId;
            ActiveRemoteSession s = (deskSid > 0) ? sessions.get(deskSid) : null;
            if (s != null && !s.closed && cameraOwnerSessionId != deskSid) {
                server.sendCameraState(deskSid, new OpalCastProtocol.CameraStateMsg(
                        true,
                        true,
                        false,
                        OpalCastProtocol.DEFAULT_CAMERA_WIDTH,
                        OpalCastProtocol.DEFAULT_CAMERA_HEIGHT,
                        OpalCastProtocol.DEFAULT_CAMERA_FPS,
                        "",
                        "",
                        "front"));
            }
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
        if (displayDriver != null) {
            displayDriver.setCustomVirtualDisplayProvider(null);
        }
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
        boolean mirror = s.isMirror();
        String fsTitle = (s.standalone || s.companion || mirror) ? "" : compositor.getFullscreenWindowTitle();
        s.lastPushedFullscreenTitle = fsTitle;
        AudioStreamCapturer cap = audioCapturer;
        boolean sessionAudioActive = !s.companion && cap != null && cap.isActive() && s.audioRequested;
        OpalCastProtocol.DeviceState state = new OpalCastProtocol.DeviceState(
                server.getDeviceName(),
                s.sinkType,
                s.displayMode,
                s.width,
                s.height,
                s.dpi,
                s.fps,
                mirror ? s.mirrorRotation : 0,
                s.bitrateKbps,
                100,
                services.isHostScreenOff(),
                sessionAudioActive,
                inputRouter.getLastInjectStatus(),
                false,
                0,
                s.companion ? "COMPANION" : (mirror ? "MIRROR" : "DEX"),
                "",
                "",
                Collections.emptyList(),
                Collections.emptyList(),
                fsTitle,
                hostTopAppPackage,
                hostTopAppLabel);
        server.sendDeviceState(s.sessionId, state);
    }

    private void closeSessionResources(ActiveRemoteSession s) {
        synchronized (s.lock) {
            if (s.closed) return;
            s.closed = true;
            if (!s.standalone && !s.companion && (desktopSessionId == s.sessionId || desktopSessionId < 0)) {
                unregisterMirrorDisplayListener();
            }
            if (s.clipboard != null) {
                try { s.clipboard.onSessionEnded(); } catch (RuntimeException ignored) {}
                s.clipboard = null;
            }
            if (s.fileReceiver != null) {
                try { s.fileReceiver.abortAllTransfers(); } catch (RuntimeException ignored) {}
                s.fileReceiver = null;
            }
            releaseSessionVirtualDisplayLocked(s, !s.isMirror());
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
