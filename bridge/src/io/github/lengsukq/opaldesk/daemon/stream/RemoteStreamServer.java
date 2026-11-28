package io.github.lengsukq.opaldesk.daemon.stream;

import android.net.LocalSocket;
import android.os.Build;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.security.PairingApprovalCoordinator;
import io.github.lengsukq.opaldesk.daemon.security.SelfSignedCertGenerator;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import java.io.Closeable;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.SSLSocket;

/**
 * Dual-transport, multi-session OpalCast v1.1 stream server facade:
 * delegates socket acceptance & TLS listener lifecycle to {@link RemoteTlsAcceptor},
 * client auth/handshake/upgrade to {@link RemoteAuthHandshakeHandler}, session state to
 * {@link RemoteSessionRegistry}, and frame routing to {@link RemoteUplinkFrameDispatcher}.
 */
public final class RemoteStreamServer {
    public static final String USB_SOCKET_NAME = "opaldesk_stream";
    public static final int DEFAULT_WIFI_PORT = 19878;
    public static final int MAX_STANDALONE_SESSIONS = 3;
    public static final int MAX_COMPANION_SESSIONS = 2;

    public interface SessionListener {
        void onSessionConnected(int sessionId, int sinkType, String clientId, String clientName, OpalCastProtocol.HelloMsg hello);
        void onSessionDisconnected(int sessionId, int sinkType, String sessionRole, String reason);
        void onResizeConfig(int sessionId, OpalCastProtocol.ResizeConfig config);
        void onRequestIdr(int sessionId);
        void onMouseEvent(int sessionId, OpalCastProtocol.MouseEventMsg msg);
        void onTouchEvent(int sessionId, OpalCastProtocol.TouchEventMsg msg);
        void onKeyEvent(int sessionId, OpalCastProtocol.KeyEventMsg msg);
        void onTextEvent(int sessionId, OpalCastProtocol.TextEventMsg msg);
        void onImageClipboardEvent(int sessionId, OpalCastProtocol.ImageClipboardEventMsg msg);
        void onSystemAction(int sessionId, OpalCastProtocol.SystemActionMsg msg);
        void onFileTransferChunk(int sessionId, OpalCastProtocol.FileTransferChunkMsg msg);
        void onListApps(int sessionId);
        void onWindowAction(int sessionId, OpalCastProtocol.WindowActionMsg msg);
        default void onMicConfig(int sessionId, OpalCastProtocol.MicConfigMsg msg) {}
        default void onMicFrame(int sessionId, byte[] pcmData, long timestampUs) {}
        default void onCameraConfig(int sessionId, OpalCastProtocol.CameraConfigMsg msg) {}
        default void onCameraFrame(int sessionId, byte[] jpegData, long timestampUs) {}
        default void onNotificationAction(int sessionId, OpalCastProtocol.NotificationActionMsg msg) {}
        default void onMediaAction(int sessionId, OpalCastProtocol.MediaActionMsg msg) {}
        default void onFileOpRequest(int sessionId, OpalCastProtocol.FileOpRequestMsg msg) {}
        default void onApkInstallRequest(int sessionId, OpalCastProtocol.ApkInstallRequestMsg msg) {}
        default void onCallAction(int sessionId, OpalCastProtocol.CallActionMsg msg) {}
        void onWifiUpgradeEnabled();
        default void onVideoSoftCongestion(int sessionId) {}
        void onVideoCongestion(int sessionId);
        void onVideoQueueHealthy(int sessionId);
    }

    public static final class WifiConfigStatus {
        public final boolean enabled;
        public final int port;
        public final String certFingerprint;
        public final int bindScope;

        public WifiConfigStatus(boolean enabled, int port, String certFingerprint) {
            this(enabled, port, certFingerprint, BridgeProtocol.TLS_BIND_SCOPE_ALL);
        }

        public WifiConfigStatus(boolean enabled, int port, String certFingerprint, int bindScope) {
            this.enabled = enabled;
            this.port = port;
            this.certFingerprint = (certFingerprint != null) ? certFingerprint : "";
            this.bindScope = (bindScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY)
                    ? BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY
                    : BridgeProtocol.TLS_BIND_SCOPE_ALL;
        }
    }

    private final SessionListener listener;
    private final TrustStoreManager trustStore;
    private final PairingApprovalCoordinator pairingCoordinator = new PairingApprovalCoordinator();
    private final RemoteSessionRegistry sessions = new RemoteSessionRegistry();
    private final String deviceName;
    private final RemoteTlsAcceptor tlsAcceptor;
    private final RemoteAuthHandshakeHandler authHandler;

    public RemoteStreamServer(SessionListener listener, TrustStoreManager trustStore) {
        this(listener, trustStore, new File(SelfSignedCertGenerator.DEFAULT_KEYSTORE_PATH), resolveDeviceName());
    }

    public RemoteStreamServer(
            SessionListener listener,
            TrustStoreManager trustStore,
            File certStoreFile,
            String deviceName) {
        this.listener = listener;
        this.trustStore = (trustStore != null) ? trustStore : new TrustStoreManager();
        this.deviceName = (deviceName != null && !deviceName.trim().isEmpty()) ? deviceName.trim() : "Android";
        this.tlsAcceptor = new RemoteTlsAcceptor(
                certStoreFile, this.deviceName, sessions, pairingCoordinator,
                new RemoteTlsAcceptor.ClientDispatch() {
                    @Override public void onUsbClientAccepted(LocalSocket client) { authHandler.handleUsbClient(client); }
                    @Override public void onWifiClientAccepted(SSLSocket client) { authHandler.handleWifiClient(client); }
                });
        this.authHandler = new RemoteAuthHandshakeHandler(
                this.deviceName, this.trustStore, pairingCoordinator, sessions, tlsAcceptor, listener,
                this::runAuthenticatedSession);
    }

    public String getDeviceName() { return deviceName; }
    public boolean isWifiEnabled() { return tlsAcceptor.isWifiEnabled(); }
    public int getWifiPort() { return tlsAcceptor.getWifiPort(); }
    public int getWifiBindScope() { return tlsAcceptor.getWifiBindScope(); }
    public String getActiveBindHost() { return tlsAcceptor.getActiveBindHost(); }
    public String getCertFingerprint() { return tlsAcceptor.getCertFingerprint(); }
    public PairingApprovalCoordinator getPairingCoordinator() { return pairingCoordinator; }
    public void setPairingChangeListener(PairingApprovalCoordinator.ChangeListener listener) { pairingCoordinator.setChangeListener(listener); }
    public int getTrustedCount() { return trustStore.getTrustedCount(); }
    public String getTrustedPeersJson() { return trustStore.toTrustedPeersJson(); }
    public String getPendingPairingJson() { return pairingCoordinator.getActivePendingRequestJson(); }
    public boolean respondToPairing(String requestId, int decision) { return pairingCoordinator.respond(requestId, decision); }
    public boolean isActiveSessionPermanent() { return sessions.isActiveSessionPermanent(); }
    public boolean hasDesktopSession() { return sessions.hasDesktopSession(); }
    public boolean hasActiveSession() { return sessions.hasActiveSession(); }
    public boolean hasCompanionSession() { return sessions.hasCompanionSession(); }
    public int getCompanionSessionCount() { return sessions.getCompanionSessionCount(); }
    public int getStandaloneSessionCount() { return sessions.getStandaloneSessionCount(); }
    public List<Integer> getDesktopAndCompanionSessionIds() { return sessions.getDesktopAndCompanionSessionIds(); }
    public List<Integer> getCompanionEventSubscriberSessionIds() { return sessions.getCompanionEventSubscriberSessionIds(); }
    public boolean markSessionCompanionEventsSubscribed(int sessionId) { return sessions.markSessionCompanionEventsSubscribed(sessionId); }
    public int getActiveSinkType() { return sessions.getActiveSinkType(); }
    public String getActivePeerName() { return sessions.getActivePeerName(); }

    public void startUsbListener() { tlsAcceptor.startUsbListener(); }

    public WifiConfigStatus configureWifi(boolean enable, int requestedPort) {
        return configureWifi(enable, requestedPort, BridgeProtocol.TLS_BIND_SCOPE_ALL);
    }

    public WifiConfigStatus configureWifi(boolean enable, int requestedPort, int requestedBindScope) {
        return tlsAcceptor.configureWifi(enable, requestedPort, requestedBindScope);
    }

    public void reconcileUsbTetherBindIfNeeded() { tlsAcceptor.reconcileUsbTetherBindIfNeeded(); }

    public boolean revokeTrustedClient(String clientId) {
        boolean ok = trustStore.revokeClient(clientId);
        sessions.disconnectMatching(
                s -> DisplaySink.isTlsNetworkSink(s.sinkType)
                        && ("*".equals(clientId) || (s.clientId != null && s.clientId.equals(clientId))),
                "Trusted client revoked");
        return ok;
    }

    public void disconnectSession(int sessionId, String reason) { sessions.disconnectSession(sessionId, reason); }
    public void disconnectActiveClient(String reason) { sessions.disconnectAll(reason); }

    public void shutdown() {
        tlsAcceptor.shutdown();
        pairingCoordinator.cancelAll();
        disconnectActiveClient("Daemon shutting down");
    }

    // ---- Session-addressed outbound frame senders ----

    public void updateSessionTargetFps(int sessionId, float targetFps) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed()) s.setTargetFps(targetFps);
    }

    public void sendVideoConfig(int sessionId, OpalCastProtocol.VideoConfig config, long timestampUs) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && config != null) {
            s.enqueue(OpalCastProtocol.TYPE_VIDEO_CONFIG, 0, timestampUs, OpalCastProtocol.encodeVideoConfig(config));
        }
    }

    public void sendVideoFrame(int sessionId, byte[] annexB, int flags, long timestampUs) {
        sendVideoFrame(sessionId, annexB, (annexB != null) ? annexB.length : 0, flags, timestampUs, null);
    }

    public void sendVideoFrame(
            int sessionId, byte[] annexB, int length, int flags, long timestampUs, Consumer<byte[]> onRelease) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed()) {
            s.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, flags, timestampUs, annexB, length, onRelease);
        } else if (onRelease != null && annexB != null) {
            onRelease.accept(annexB);
        }
    }

    public void sendAudioConfig(int sessionId, OpalCastProtocol.AudioConfig config, long timestampUs) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && config != null) {
            s.enqueue(OpalCastProtocol.TYPE_AUDIO_CONFIG, 0, timestampUs, OpalCastProtocol.encodeAudioConfig(config));
        }
    }

    public void sendAudioFrame(int sessionId, byte[] aacAu, long timestampUs) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed()) s.enqueue(OpalCastProtocol.TYPE_AUDIO_FRAME, 0, timestampUs, aacAu);
    }

    public void sendDeviceState(int sessionId, OpalCastProtocol.DeviceState state) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && state != null) {
            s.enqueue(OpalCastProtocol.TYPE_DEVICE_STATE, 0, nowTimestampUs(), OpalCastProtocol.encodeDeviceState(state));
        }
    }

    public void sendClipboardPush(int sessionId, long seq, String text) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed()) {
            s.enqueue(OpalCastProtocol.TYPE_CLIPBOARD_PUSH, 0, nowTimestampUs(), OpalCastProtocol.encodeClipboardPush(seq, text));
        }
    }

    public void sendClipboardImagePush(int sessionId, long seq, byte[] pngData) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed()) {
            try {
                byte[] payload = OpalCastProtocol.encodeClipboardImagePush(seq, pngData);
                s.enqueue(OpalCastProtocol.TYPE_CLIPBOARD_PUSH, OpalCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, nowTimestampUs(), payload);
            } catch (Throwable t) {
                System.err.println("OPALDESK_CLIP_IMG_SEND_ERR: " + t.getMessage());
            }
        }
    }

    public void sendFileTransferAck(int sessionId, int transferId, int status, String pathOrError) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed()) {
            s.enqueue(OpalCastProtocol.TYPE_FILE_TRANSFER_ACK, 0, nowTimestampUs(),
                    OpalCastProtocol.encodeFileTransferAck(transferId, status, pathOrError));
        }
    }

    public void sendAppList(int sessionId, OpalCastProtocol.AppListMsg appList) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && appList != null) {
            s.enqueue(OpalCastProtocol.TYPE_APP_LIST, 0, nowTimestampUs(), OpalCastProtocol.encodeAppList(appList));
        }
    }

    public void sendImeRequest(int sessionId, boolean show, int inputType) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed()) {
            s.enqueue(OpalCastProtocol.TYPE_IME_REQUEST, 0, nowTimestampUs(), OpalCastProtocol.encodeImeRequest(show, inputType));
        }
    }

    public void sendMicState(int sessionId, OpalCastProtocol.MicStateMsg state) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && state != null) {
            s.enqueue(OpalCastProtocol.TYPE_MIC_STATE, 0, nowTimestampUs(), OpalCastProtocol.encodeMicState(state));
        }
    }

    public void sendCameraState(int sessionId, OpalCastProtocol.CameraStateMsg state) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && state != null) {
            s.enqueue(OpalCastProtocol.TYPE_CAMERA_STATE, 0, nowTimestampUs(), OpalCastProtocol.encodeCameraState(state));
        }
    }

    public void sendNotificationEvent(int sessionId, byte[] jsonUtf8) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && jsonUtf8 != null && jsonUtf8.length > 0) {
            s.enqueue(OpalCastProtocol.TYPE_NOTIFICATION_EVENT, 0, nowTimestampUs(), jsonUtf8);
        }
    }

    public void broadcastNotificationEvent(byte[] jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.length == 0) return;
        for (int sid : getCompanionEventSubscriberSessionIds()) sendNotificationEvent(sid, jsonUtf8);
    }

    public void sendMediaState(int sessionId, byte[] jsonUtf8) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && jsonUtf8 != null && jsonUtf8.length > 0) {
            s.enqueue(OpalCastProtocol.TYPE_MEDIA_STATE, 0, nowTimestampUs(), jsonUtf8);
        }
    }

    public void broadcastMediaState(byte[] jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.length == 0) return;
        for (int sid : getCompanionEventSubscriberSessionIds()) sendMediaState(sid, jsonUtf8);
    }

    public void sendCallState(int sessionId, byte[] jsonUtf8) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && jsonUtf8 != null && jsonUtf8.length > 0) {
            s.enqueue(OpalCastProtocol.TYPE_CALL_STATE, 0, nowTimestampUs(), jsonUtf8);
        }
    }

    public void broadcastCallState(byte[] jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.length == 0) return;
        for (int sid : getCompanionEventSubscriberSessionIds()) sendCallState(sid, jsonUtf8);
    }

    public void sendFileOpResponse(int sessionId, OpalCastProtocol.FileOpResponseMsg response) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && response != null) {
            s.enqueue(OpalCastProtocol.TYPE_FILE_OP_RESPONSE, 0, nowTimestampUs(),
                    OpalCastProtocol.encodeFileOpResponse(response));
        }
    }

    public boolean sendFilePullChunk(int sessionId, byte[] payload) {
        ClientSession s = sessions.getSession(sessionId);
        if (s == null || s.isClosed() || payload == null) return false;
        return s.enqueueBackpressured(OpalCastProtocol.TYPE_FILE_PULL_CHUNK, 0, nowTimestampUs(), payload, 30_000L);
    }

    public void sendApkInstallStatus(int sessionId, OpalCastProtocol.ApkInstallStatusMsg status) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && status != null) {
            s.enqueue(OpalCastProtocol.TYPE_APK_INSTALL_STATUS, 0, nowTimestampUs(),
                    OpalCastProtocol.encodeApkInstallStatus(status));
        }
    }

    // ---- Desktop-session convenience senders ----

    public void sendAudioConfig(OpalCastProtocol.AudioConfig config, long timestampUs) {
        ClientSession s = sessions.getDesktopSession();
        if (s != null) sendAudioConfig(s.sessionId, config, timestampUs);
    }

    public void sendAudioFrame(byte[] aacAu, long timestampUs) {
        ClientSession s = sessions.getDesktopSession();
        if (s != null) sendAudioFrame(s.sessionId, aacAu, timestampUs);
    }

    public void sendDeviceState(OpalCastProtocol.DeviceState state) {
        ClientSession s = sessions.getDesktopSession();
        if (s != null) sendDeviceState(s.sessionId, state);
    }

    public void sendClipboardPush(long seq, String text) {
        ClientSession s = sessions.getDesktopSession();
        if (s != null) sendClipboardPush(s.sessionId, seq, text);
    }

    public void sendClipboardImagePush(long seq, byte[] pngData) {
        ClientSession s = sessions.getDesktopSession();
        if (s != null) sendClipboardImagePush(s.sessionId, seq, pngData);
    }

    public void sendFileTransferAck(int transferId, int status, String pathOrError) {
        ClientSession s = sessions.getDesktopSession();
        if (s != null) sendFileTransferAck(s.sessionId, transferId, status, pathOrError);
    }

    void runAuthenticatedSessionForTest(
            int sinkType, boolean isAdbLocalSocket, boolean permanentTrust,
            String clientId, String clientName, OpalCastProtocol.HelloMsg hello,
            Closeable transport, OpalCastFrameTransport frameTransport) {
        runAuthenticatedSession(sinkType, isAdbLocalSocket, permanentTrust, clientId, clientName, hello, transport, frameTransport);
    }

    private void runAuthenticatedSession(
            int sinkType, boolean isAdbLocalSocket, boolean permanentTrust,
            String clientId, String clientName, OpalCastProtocol.HelloMsg hello,
            Closeable transport, OpalCastFrameTransport frameTransport) {
        int sessionId = sessions.nextSessionId();
        boolean isStandalone = hello.isStandaloneApp();
        boolean isCatalog = hello.isAppCatalog();
        boolean isCompanion = hello.isCompanion();
        ClientSession current = new ClientSession(
                sessionId, sinkType, isAdbLocalSocket, permanentTrust, hello.sessionRole, hello.appPackage,
                clientId, clientName, transport, frameTransport, listener);
        current.setTargetFps(hello.fps > 0 ? hello.fps : 60f);
        current.setCompanionEventsSubscribed(
                isCompanion || (!isStandalone && !isCatalog && hello.companionEvents));

        RemoteSessionRegistry.RegistrationOutcome outcome = sessions.registerSession(current, hello, clientId);
        if (outcome.capacityExceeded) {
            current.close(isCompanion ? "Maximum companion sessions reached" : "Maximum standalone app sessions reached");
            return;
        }
        if (outcome.replacedSession != null) {
            outcome.replacedSession.close("Replaced by new session");
        }

        current.startSenderThread();
        boolean notifiedConnect = false;
        try {
            if (listener != null) {
                listener.onSessionConnected(sessionId, sinkType, clientId, clientName, hello);
                notifiedConnect = true;
            }
            while (!tlsAcceptor.isStopped() && !current.isClosed()) {
                OpalCastProtocol.Frame frame = frameTransport.readFrame();
                if (frame == null) break;
                RemoteUplinkFrameDispatcher.dispatch(
                        current, frame, listener, authHandler::handleWifiUpgradeRequest, RemoteStreamServer::nowTimestampUs);
            }
        } catch (Throwable t) {
            if (!current.isClosed()) {
                System.out.println("OPALDESK_REMOTE_SESSION_END id=" + sessionId
                        + " role=" + current.sessionRole + " peer=" + clientName + " reason=" + t.getMessage());
            }
        } finally {
            current.close("Session closed");
            sessions.unregisterSession(current, isCompanion, isStandalone, isCatalog);
            if (notifiedConnect && listener != null) {
                listener.onSessionDisconnected(sessionId, sinkType, current.sessionRole, current.closeReason);
            }
        }
    }

    public static String detectLanIpv4Address() {
        return NetworkInterfaceClassifier.selectBestLanIp(NetworkInterfaceClassifier.snapshotSystemInterfaces());
    }

    public static NetworkInterfaceClassifier.Endpoint detectUsbTetherEndpoint() {
        return NetworkInterfaceClassifier.selectUsbTetherEndpoint(NetworkInterfaceClassifier.snapshotSystemInterfaces());
    }

    static long nowTimestampUs() {
        try {
            return SystemClock.elapsedRealtimeNanos() / 1000L;
        } catch (Throwable ignored) {
            return System.nanoTime() / 1000L;
        }
    }

    private static String resolveDeviceName() {
        try {
            String model = Build.MODEL;
            if (model != null && !model.trim().isEmpty()) return model.trim();
        } catch (Throwable ignored) {}
        return "Android";
    }
}
