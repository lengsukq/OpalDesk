package io.github.lengsukq.opaldesk.daemon.stream;

import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Display;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.input.ExternalMouseRouter;
import io.github.lengsukq.opaldesk.daemon.input.InputBindingEngine;
import io.github.lengsukq.opaldesk.daemon.input.MouseRoutingPolicy;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.HotspotController;
import io.github.lengsukq.opaldesk.daemon.security.PairingApprovalCoordinator;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;
import io.github.lengsukq.opaldesk.daemon.sink.RemoteStreamSink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Clean OpalCast v1.1 MacOS Streaming Bridge wired directly onto the v1.0.0 Universal Compositor.
 *
 * <p>Coordinates {@link OpalCastInputAdapter}, {@link OpalCastAppCatalogBuilder},
 * {@link OpalCastWifiUpgradeCoordinator}, {@link OpalCastStandaloneSessionHandler}, and
 * {@link OpalCastDisplayModeCoordinator} behind the public bridge facade.
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

    static final class ActiveRemoteSession {
        final int sessionId;
        final int sinkType;
        final boolean standalone;
        final boolean companion;
        final String appPackage;
        final String appActivity;
        final int appUserId;
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
        volatile int lastKeyMetaState = 0;
        volatile int lastImeShowSeq = -1;
        volatile long imeProbeGen = 0L;
        volatile String lastPushedFullscreenTitle = "";
        volatile boolean audioRequested = false;
        volatile boolean closed = false;

        ActiveRemoteSession(
                int sessionId, int sinkType, boolean standalone, boolean companion,
                String pkg, String cls, int appUserId, String clientPlatform) {
            this.sessionId = sessionId;
            this.sinkType = sinkType;
            this.standalone = standalone;
            this.companion = companion;
            this.appPackage = pkg != null ? pkg : "";
            this.appActivity = cls != null ? cls : "";
            this.appUserId = Math.max(0, appUserId);
            this.clientPlatform = clientPlatform != null ? clientPlatform : "";
        }

        boolean isMirror() { return !standalone && !companion && displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR; }
        int inputTargetDisplayId() { return isMirror() ? Display.DEFAULT_DISPLAY : displayId; }
        String inputTargetUniqueId() { return isMirror() ? mirrorTargetUniqueId : vdUniqueId; }
        int inputTargetWidth() { return isMirror() ? Math.max(2, mirrorSourceWidth) : Math.max(2, width); }
        int inputTargetHeight() { return isMirror() ? Math.max(2, mirrorSourceHeight) : Math.max(2, height); }
    }

    public interface DirectImeTextCommitter { boolean tryCommitText(String text); }
    public interface NotificationActionConsumer { void accept(String op, String key, String replyText, int actionIndex); }
    public interface MediaActionConsumer { void accept(String op, long positionMs); }
    public interface CallActionConsumer { boolean dispatch(String op, String number, String smsText); }
    public interface InputActionConsumer { boolean dispatch(String actionId); }

    private final AndroidServices services;
    private final DaemonConfig config;
    private final CompositorEngine compositor;
    private final PerDisplayInputRouter inputRouter;
    private final ExternalMouseRouter mouseRouter;
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
    private final CompanionNotificationRelay notificationRelay;
    private final CompanionCallRelay callRelay;
    private final Map<Integer, ActiveRemoteSession> sessions = new ConcurrentHashMap<>();
    private volatile byte[] lastMediaStatePayload = null;
    private volatile int desktopSessionId = -1;
    private volatile boolean localHostTextFieldFocused = false;
    private final RemoteMediaDevicesCoordinator mediaDevices;
    private final OpalCastAppCatalogBuilder appCatalog;
    private final OpalCastWifiUpgradeCoordinator wifiCoordinator;
    private final OpalCastStandaloneSessionHandler sessionHandler;
    private final OpalCastDisplayModeCoordinator displayModeCoordinator;
    private final InputBindingEngine inputBindingEngine;
    private final OpalCastInputAdapter inputAdapter;

    private volatile Runnable remoteStateChangeListener;
    private volatile DirectImeTextCommitter directImeCommitter;
    private volatile NotificationActionConsumer notificationActionConsumer;
    private volatile MediaActionConsumer mediaActionConsumer;
    private volatile CallActionConsumer callActionConsumer;
    private volatile InputActionConsumer inputActionConsumer;
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
        this.inputRouter = inputRouter;
        this.mouseRouter = mouseRouter;
        TrustStoreManager trustStore = new TrustStoreManager(TRUST_FILE);
        String devName = (Build.MODEL != null && !Build.MODEL.trim().isEmpty())
                ? Build.MODEL.trim() : "Android Device";
        this.server = new RemoteStreamServer(this, trustStore, CERT_FILE, devName);
        this.mediaDevices = new RemoteMediaDevicesCoordinator(
                services, config, compositor, displayDriver, server, sessions, () -> desktopSessionId, worker);
        this.appCatalog = new OpalCastAppCatalogBuilder(services, config, displayDriver);
        this.fileManager.setAppStorageStatsProvider(
                RemoteFileManager.createDefaultAppStorageProvider(appCatalog::resolveCachedHostAppLabel));
        this.notificationRelay = new CompanionNotificationRelay(new CompanionNotificationRelay.OutboundSender() {
            @Override public void sendNotificationEventToSession(int sessionId, byte[] jsonUtf8) {
                server.sendNotificationEvent(sessionId, jsonUtf8);
            }
            @Override public void broadcastNotificationEvent(byte[] jsonUtf8) {
                server.broadcastNotificationEvent(jsonUtf8);
            }
        });
        this.callRelay = new CompanionCallRelay(new CompanionCallRelay.OutboundSender() {
            @Override public void sendCallStateToSession(int sessionId, byte[] jsonUtf8) {
                server.sendCallState(sessionId, jsonUtf8);
            }
            @Override public void broadcastCallState(byte[] jsonUtf8) {
                server.broadcastCallState(jsonUtf8);
            }
        });
        this.wifiCoordinator = new OpalCastWifiUpgradeCoordinator(
                config, trustStore, server, new HotspotController(), sessions,
                () -> desktopSessionId, this::getActiveDesktopDisplayId, this::notifyRemoteStateChanged);
        this.sessionHandler = new OpalCastStandaloneSessionHandler(
                services, config, compositor, displayDriver, inputRouter, mouseRouter,
                server, mediaDevices, sessions, () -> desktopSessionId,
                () -> localHostTextFieldFocused, this::isBridgeImeActive,
                new OpalCastStandaloneSessionHandler.SessionCallbacks() {
                    @Override public void onDesktopSessionAssigned(int sid) { desktopSessionId = sid; }
                    @Override public void onRegisterMirrorListener() { displayModeCoordinator.registerMirrorDisplayListener(); }
                    @Override public void onUnregisterMirrorListener() { displayModeCoordinator.unregisterMirrorDisplayListener(); }
                    @Override public void onRefreshHostTopApp() { refreshHostTopAppIfChanged(); }
                    @Override public void onPushDeviceState(ActiveRemoteSession s) { pushDeviceState(s); }
                    @Override public void onPushCompanionSnapshots(int sid, boolean reqSync) {
                        pushCachedCompanionSnapshotsToSession(sid, reqSync);
                    }
                    @Override public void onCompositorWindowsChanged() {
                        OpalCastCompositorBridge.this.onCompositorWindowsChanged();
                    }
                    @Override public void onRemoteStateChanged() { notifyRemoteStateChanged(); }
                });
        this.displayModeCoordinator = new OpalCastDisplayModeCoordinator(
                services, config, mouseRouter, server, mediaDevices, sessions,
                () -> desktopSessionId, this::isBridgeImeActive, worker,
                this::pushDeviceState, this::notifyRemoteStateChanged, sessionHandler);
        this.inputBindingEngine = new InputBindingEngine();
        if (config != null) {
            try {
                this.inputBindingEngine.setRulesFromJson(config.getInputBindingsJson());
            } catch (RuntimeException ignored) {}
        }
        this.inputAdapter = new OpalCastInputAdapter(
                services, compositor, displayDriver, inputRouter, inputBindingEngine, server, mediaDevices, sessions,
                () -> desktopSessionId, () -> localHostTextFieldFocused, this::isBridgeImeActive,
                worker, this::pushDeviceState, displayModeCoordinator::switchDesktopDisplayMode,
                actionId -> {
                    InputActionConsumer consumer = this.inputActionConsumer;
                    return consumer != null && consumer.dispatch(actionId);
                });
        if (this.inputRouter != null) {
            this.inputRouter.setInjectStatusListener(newStatus -> worker.execute(() -> {
                for (ActiveRemoteSession s : new ArrayList<>(sessions.values())) {
                    if (s != null && !s.closed) pushDeviceState(s);
                }
                notifyRemoteStateChanged();
            }));
        }
    }

    public void setLocalHostTextFieldFocused(boolean focused) { this.localHostTextFieldFocused = focused; }

    public void setPairingEventConsumer(Consumer<String> consumer) {
        if (consumer == null) {
            server.setPairingChangeListener(null);
            return;
        }
        server.setPairingChangeListener((PairingApprovalCoordinator.PendingRequest req) ->
                consumer.accept(req != null ? req.toJson() : ""));
    }

    public void setRemoteStateChangeListener(Runnable listener) { this.remoteStateChangeListener = listener; }
    public void setDirectImeTextCommitter(DirectImeTextCommitter committer) { this.directImeCommitter = committer; }

    public void setNotificationActionConsumer(NotificationActionConsumer consumer) {
        this.notificationActionConsumer = consumer;
        this.notificationRelay.setActionForwarder(consumer != null ? consumer::accept : null);
    }

    public void setMediaActionConsumer(MediaActionConsumer consumer) { this.mediaActionConsumer = consumer; }

    public void setCallActionConsumer(CallActionConsumer consumer) {
        this.callActionConsumer = consumer;
        this.callRelay.setActionDispatcher(consumer != null ? consumer::dispatch : null);
    }

    public void setInputActionConsumer(InputActionConsumer consumer) { this.inputActionConsumer = consumer; }

    public void setBridgeImeActiveSupplier(java.util.function.BooleanSupplier supplier) {
        this.bridgeImeActiveSupplier = supplier != null ? supplier : () -> false;
    }

    public void onNotificationsSyncedFromHelper(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        worker.execute(() -> notificationRelay.onNotificationsSyncedFromHelper(rawJson));
    }

    public void onCallStateSyncedFromHelper(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        worker.execute(() -> callRelay.onCallStateSyncedFromHelper(rawJson));
    }

    public String triggerHotspotForBleClient(String clientId, String nonceHex, String hmacHex) {
        return wifiCoordinator.triggerHotspotForBleClient(clientId, nonceHex, hmacHex);
    }

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

    public void refreshHostTopAppIfChanged() {
        if (!hasDesktopOrCompanionSession()) return;
        if (!appCatalog.refreshHostTopAppIfChanged()) return;
        for (int sid : server.getDesktopAndCompanionSessionIds()) {
            ActiveRemoteSession s = sessions.get(sid);
            if (s != null && !s.closed) pushDeviceState(s);
        }
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
        if (media != null && media.length > 0) server.sendMediaState(sessionId, media);
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

    public String getTrustedPeersJson() { return server.getTrustedPeersJson(); }
    public boolean revokeTrustedClient(String clientId) { return server.revokeTrustedClient(clientId); }
    public String getPendingPairingJson() { return server.getPendingPairingJson(); }
    public boolean respondToPairing(String requestId, int decision) { return server.respondToPairing(requestId, decision); }
    public void start() { server.startUsbListener(); }

    public void shutdown() {
        displayModeCoordinator.unregisterMirrorDisplayListener();
        mediaDevices.shutdown();
        if (mouseRouter != null) mouseRouter.releaseToLocal();
        for (ActiveRemoteSession s : new ArrayList<>(sessions.values())) {
            sessionHandler.closeSessionResources(s);
        }
        sessions.clear();
        desktopSessionId = -1;
        server.shutdown();
    }

    public RemoteStatusSnapshot getStatusSnapshot() { return wifiCoordinator.getStatusSnapshot(); }

    public RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int port) {
        return configureWifi(enable, port, BridgeProtocol.TLS_BIND_SCOPE_ALL);
    }

    public RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int port, int bindScope) {
        return wifiCoordinator.configureWifi(enable, port, bindScope);
    }

    public void disconnectActiveClient() { server.disconnectActiveClient("user_requested"); }

    public boolean reconfigureActiveDesktop(int reqW, int reqH, float reqFps) {
        return displayModeCoordinator.reconfigureActiveDesktop(reqW, reqH, reqFps);
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
                    + " displayId=" + s.displayId + " policy=" + policy
                    + " trusted=" + s.vdTrusted + " bridgeIme=" + bridgeImeActive + " ok=" + ok);
        }
    }

    public int getActiveDesktopDisplayId() {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        return (s != null && !s.closed && !s.isMirror()) ? s.displayId : -1;
    }

    public void sendDesktopImeRequest(boolean show, int inputType) {
        int sid = desktopSessionId;
        ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        if (s != null && !s.closed) server.sendImeRequest(sid, show, inputType);
    }

    public void onBridgeImeShowRequested(int imeDisplayId, String pkg, int rawInputType) {
        inputAdapter.onBridgeImeShowRequested(imeDisplayId, rawInputType);
    }

    public void onBridgeImeHideRequested(int imeDisplayId) {
        inputAdapter.onBridgeImeHideRequested(imeDisplayId);
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

    public boolean injectHostTrackpadEvent(
            int action, float x, float y, float hScroll, float vScroll, int buttonState) {
        return inputAdapter.injectHostTrackpadEvent(action, x, y, hScroll, vScroll, buttonState);
    }

    public InputBindingEngine getInputBindingEngine() {
        return inputBindingEngine;
    }

    public void reloadInputBindings(String rulesJson) {
        inputBindingEngine.setRulesFromJson(rulesJson);
    }

    public void setInputBindingCaptureMode(boolean active) {
        inputBindingEngine.setCaptureMode(active);
    }

    public boolean isInputBindingCaptureMode() {
        return inputBindingEngine.isCaptureMode();
    }

    public String pollLastCapturedInputEventJson() {
        String captured = inputBindingEngine.pollLastCapturedEventJson();
        return captured != null ? captured : "";
    }

    public boolean executeInputBindingAction(String actionId) {
        int sid = desktopSessionId;
        return inputAdapter.executeInputBindingAction(actionId, sid > 0 ? sessions.get(sid) : null);
    }

    // ---- RemoteStreamServer.SessionListener ----

    @Override
    public void onSessionConnected(
            int sessionId, int sinkType, String clientId, String clientName, OpalCastProtocol.HelloMsg hello) {
        worker.execute(() -> sessionHandler.setupSession(sessionId, sinkType, hello));
    }

    @Override
    public void onSessionDisconnected(int sessionId, int sinkType, String sessionRole, String reason) {
        ActiveRemoteSession s = sessions.remove(sessionId);
        boolean wasCompanion = (s != null && s.companion)
                || OpalCastProtocol.SESSION_ROLE_COMPANION.equalsIgnoreCase(sessionRole);
        mediaDevices.onSessionDisconnected(sessionId);
        if (sessionId == desktopSessionId) {
            desktopSessionId = -1;
            localHostTextFieldFocused = false;
            displayModeCoordinator.unregisterMirrorDisplayListener();
            if (mouseRouter != null) mouseRouter.releaseToLocal();
            if (services.isHostScreenOff()) services.setPrimaryPhysicalDisplayPower(true);
        }
        if (s != null) {
            sessionHandler.closeSessionResources(s);
            if (!wasCompanion) {
                final boolean wasStandalone = s.standalone;
                worker.execute(() -> {
                    mediaDevices.reconcileAudioOwner();
                    if (wasStandalone) onCompositorWindowsChanged();
                });
            }
        }
        if (sessions.isEmpty()) {
            inputBindingEngine.clearActiveHeldState();
        }
        if (!hasAnyActiveSession() && services != null && services.isHostScreenOff()) {
            services.setPrimaryPhysicalDisplayPower(true);
        }
        if (!wasCompanion) notifyRemoteStateChanged();
    }

    @Override
    public void onResizeConfig(int sessionId, OpalCastProtocol.ResizeConfig cfg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || s.companion || cfg == null) return;
        worker.execute(() -> displayModeCoordinator.reconfigureSession(s, cfg));
    }

    @Override
    public void onRequestIdr(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && !s.companion && s.sink != null) s.sink.requestKeyFrame();
    }

    @Override
    public void onMouseEvent(int sessionId, OpalCastProtocol.MouseEventMsg msg) {
        inputAdapter.handleMouseEvent(sessions.get(sessionId), msg);
    }

    @Override
    public void onTouchEvent(int sessionId, OpalCastProtocol.TouchEventMsg msg) {
        inputAdapter.handleTouchEvent(sessions.get(sessionId), msg);
    }

    @Override
    public void onKeyEvent(int sessionId, OpalCastProtocol.KeyEventMsg msg) {
        inputAdapter.handleKeyEvent(sessions.get(sessionId), msg);
    }

    @Override
    public void onTextEvent(int sessionId, OpalCastProtocol.TextEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.clipboard == null) return;
        if (!s.companion && msg.mode == OpalCastProtocol.TEXT_MODE_COMMIT_PASTE
                && msg.text != null && !msg.text.isEmpty()) {
            DirectImeTextCommitter committer = this.directImeCommitter;
            if (committer != null && committer.tryCommitText(msg.text)) return;
        }
        s.clipboard.handleText(msg);
    }

    @Override
    public void onImageClipboardEvent(int sessionId, OpalCastProtocol.ImageClipboardEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && msg != null && s.clipboard != null) s.clipboard.handleImageClipboard(msg);
    }

    @Override
    public void onSystemAction(int sessionId, OpalCastProtocol.SystemActionMsg msg) {
        inputAdapter.handleSystemAction(sessionId, sessions.get(sessionId), msg);
    }

    @Override
    public void onFileTransferChunk(int sessionId, OpalCastProtocol.FileTransferChunkMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.fileReceiver != null) s.fileReceiver.handleFileTransferChunk(msg);
    }

    @Override
    public void onListApps(int sessionId) {
        worker.execute(() -> server.sendAppList(sessionId, new OpalCastProtocol.AppListMsg(appCatalog.queryInstalledApps())));
    }

    @Override
    public void onWindowAction(int sessionId, OpalCastProtocol.WindowActionMsg msg) {
        if (msg == null || msg.action.isEmpty()) return;
        String act = msg.action.toLowerCase(Locale.ROOT);
        if ("launch".equals(act) || "focus".equals(act) || "open".equals(act)) {
            if (!msg.pkg.isEmpty()) {
                compositor.openOrFocusWindow(msg.pkg, msg.cls, msg.userId, BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
            }
        } else if ("close_all".equals(act)) {
            compositor.closeAllWindows(true);
        } else {
            inputAdapter.executeInputBindingAction(act, sessions.get(sessionId));
        }
    }

    @Override public void onMicConfig(int sessionId, OpalCastProtocol.MicConfigMsg msg) {
        if (msg != null) worker.execute(() -> mediaDevices.handleMicConfig(sessionId, msg));
    }
    @Override public void onMicFrame(int sessionId, byte[] pcmData, long timestampUs) {
        mediaDevices.feedMicFrame(sessionId, pcmData);
    }
    @Override public void onCameraConfig(int sessionId, OpalCastProtocol.CameraConfigMsg msg) {
        if (msg != null) worker.execute(() -> mediaDevices.handleCameraConfig(sessionId, msg));
    }
    @Override public void onCameraFrame(int sessionId, byte[] jpegData, long timestampUs) {
        mediaDevices.feedCameraFrame(sessionId, jpegData);
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
        if (mac != null) mac.accept(msg.op.toLowerCase(Locale.ROOT), msg.positionMs);
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
                msg, status -> { if (!s.closed) server.sendApkInstallStatus(sessionId, status); }));
    }

    @Override public void onWifiUpgradeEnabled() { wifiCoordinator.onWifiUpgradeEnabled(); }
    @Override public void onVideoSoftCongestion(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) s.sink.onSoftNetworkCongestion();
    }
    @Override public void onVideoCongestion(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) s.sink.onNetworkCongestion();
    }
    @Override public void onVideoQueueHealthy(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) s.sink.maybeRecoverBitrate();
    }

    public static int resolveSessionBitrateKbps(int requestedBitrateKbps, int w, int h, float fps, int codecPref) {
        return resolveSessionBitrateKbps(requestedBitrateKbps, w, h, fps, codecPref, DisplaySink.SINK_REMOTE_WIFI);
    }

    public static int resolveSessionBitrateKbps(
            int requestedBitrateKbps, int w, int h, float fps, int codecPref, int sinkType) {
        return DisplayGeometryUtil.resolveSessionBitrateKbps(
                requestedBitrateKbps, w, h, fps,
                codecPref != RemoteStreamSink.CODEC_PREF_H264,
                resolveMaxBitrateKbpsForSink(sinkType));
    }

    public void onCompositorWindowsChanged() {
        mediaDevices.syncSplitAudioUidsIfActive();
        int deskSid = desktopSessionId;
        if (deskSid > 0) {
            ActiveRemoteSession s = sessions.get(deskSid);
            if (s != null && !s.closed && !s.standalone && !s.isMirror()) {
                String fsTitle = compositor.getFullscreenWindowTitle();
                if (!fsTitle.equals(s.lastPushedFullscreenTitle)) pushDeviceState(s);
            }
        }
    }

    public void applyAudioRouteMode(int mode) { mediaDevices.applyAudioRouteMode(mode); }

    public void applyRemoteMediaPolicy(boolean micEnabled, boolean cameraEnabled) {
        applyRemoteMediaPolicy(micEnabled, cameraEnabled, false, false);
    }

    public void applyRemoteMediaPolicy(
            boolean micEnabled, boolean cameraEnabled, boolean triggerMicStart, boolean triggerCameraStart) {
        mediaDevices.applyRemoteMediaPolicy(micEnabled, cameraEnabled, triggerMicStart, triggerCameraStart);
    }

    private void pushDeviceState(ActiveRemoteSession s) {
        boolean mirror = s.isMirror();
        String fsTitle = (s.standalone || s.companion || mirror) ? "" : compositor.getFullscreenWindowTitle();
        s.lastPushedFullscreenTitle = fsTitle;
        boolean sessionAudioActive = !s.companion && mediaDevices.isAudioActive() && s.audioRequested;
        OpalCastProtocol.DeviceState state = new OpalCastProtocol.DeviceState(
                server.getDeviceName(), s.sinkType, s.displayMode, s.width, s.height, s.dpi, s.fps,
                mirror ? s.mirrorRotation : 0, s.bitrateKbps, 100, services.isHostScreenOff(),
                sessionAudioActive, inputRouter.getLastInjectStatus(), false, 0,
                s.companion ? "COMPANION" : (mirror ? "MIRROR" : "DEX"),
                "", "", Collections.emptyList(), Collections.emptyList(), fsTitle,
                appCatalog.getHostTopAppPackage(), appCatalog.getHostTopAppLabel());
        server.sendDeviceState(s.sessionId, state);
    }
}
