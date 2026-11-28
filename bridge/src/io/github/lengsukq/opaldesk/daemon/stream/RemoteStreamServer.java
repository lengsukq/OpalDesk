package io.github.lengsukq.opaldesk.daemon.stream;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.security.PairingApprovalCoordinator;
import io.github.lengsukq.opaldesk.daemon.security.SelfSignedCertGenerator;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Dual-transport, multi-session OpalCast v1.1 stream server:
 * 1) Always-on ADB LocalServerSocket("opaldesk_stream") with strict peer UID (0/2000) check,
 *    supporting both wired USB (SINK_REMOTE_USB) and wireless ADB (SINK_REMOTE_WIFI_ADB) via
 *    HELLO.transportHint, plus authenticated Wi-Fi TLS zero-PIN upgrade (REQUEST_WIFI_UPGRADE).
 * 2) On-demand wireless SSLServerSocket (OFF by default; TLS 1.2/1.3, TCP_NODELAY, ECDSA P-256,
 *    interactive consent + 32-byte hex authToken trust store).
 * 3) Concurrent multi-session isolation delegated to {@link RemoteSessionRegistry} and frame
 *    routing delegated to {@link RemoteUplinkFrameDispatcher}.
 */
public final class RemoteStreamServer {
    public static final String USB_SOCKET_NAME = "opaldesk_stream";
    public static final int DEFAULT_WIFI_PORT = 19878;
    public static final int MAX_STANDALONE_SESSIONS = 3;
    public static final int MAX_COMPANION_SESSIONS = 2;

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int SOCKET_IO_BUFFER_BYTES = 131_072;
    private static final int USB_SOCKET_SEND_BUF_BYTES = 512 * 1024;
    private static final int WIFI_SOCKET_SEND_BUF_BYTES = 512 * 1024;
    private static final int REALTIME_MEDIA_TRAFFIC_CLASS = 0xB8;

    public interface SessionListener {
        void onSessionConnected(
                int sessionId,
                int sinkType,
                String clientId,
                String clientName,
                OpalCastProtocol.HelloMsg hello);
        void onSessionDisconnected(
                int sessionId,
                int sinkType,
                String sessionRole,
                String reason);
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

    private interface TimeoutClearer {
        void clear() throws IOException;
    }

    private final SessionListener listener;
    private final TrustStoreManager trustStore;
    private final PairingApprovalCoordinator pairingCoordinator = new PairingApprovalCoordinator();
    private final RemoteSessionRegistry sessions = new RemoteSessionRegistry();
    private final File certStoreFile;
    private final String deviceName;

    private final Object lifecycleLock = new Object();
    private volatile boolean stopped = false;

    private LocalServerSocket usbServerSocket;
    private Thread usbAcceptThread;

    private volatile boolean wifiEnabled = false;
    private volatile int wifiPort = DEFAULT_WIFI_PORT;
    private volatile int wifiBindScope = BridgeProtocol.TLS_BIND_SCOPE_ALL;
    private volatile String activeBindHost = "";
    private volatile SelfSignedCertGenerator.ServerIdentity tlsIdentity;
    private SSLServerSocket wifiServerSocket;
    private Thread wifiAcceptThread;

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
        this.certStoreFile = certStoreFile;
        this.deviceName = (deviceName != null && !deviceName.trim().isEmpty()) ? deviceName.trim() : "Android";
    }

    public String getDeviceName() {
        return deviceName;
    }

    public boolean isWifiEnabled() {
        return wifiEnabled;
    }

    public int getWifiPort() {
        return wifiPort;
    }

    public int getWifiBindScope() {
        return wifiEnabled ? wifiBindScope : BridgeProtocol.TLS_BIND_SCOPE_ALL;
    }

    public String getActiveBindHost() {
        return wifiEnabled ? activeBindHost : "";
    }

    public String getCertFingerprint() {
        SelfSignedCertGenerator.ServerIdentity id = tlsIdentity;
        return (id != null && id.certFingerprintSha256 != null) ? id.certFingerprintSha256 : "";
    }

    public PairingApprovalCoordinator getPairingCoordinator() {
        return pairingCoordinator;
    }

    public void setPairingChangeListener(PairingApprovalCoordinator.ChangeListener listener) {
        pairingCoordinator.setChangeListener(listener);
    }

    public int getTrustedCount() {
        return trustStore.getTrustedCount();
    }

    public String getTrustedPeersJson() {
        return trustStore.toTrustedPeersJson();
    }

    public String getPendingPairingJson() {
        return pairingCoordinator.getActivePendingRequestJson();
    }

    public boolean respondToPairing(String requestId, int decision) {
        return pairingCoordinator.respond(requestId, decision);
    }

    public boolean isActiveSessionPermanent() {
        return sessions.isActiveSessionPermanent();
    }

    public boolean hasDesktopSession() {
        return sessions.hasDesktopSession();
    }

    public boolean hasActiveSession() {
        return sessions.hasActiveSession();
    }

    public boolean hasCompanionSession() {
        return sessions.hasCompanionSession();
    }

    public int getCompanionSessionCount() {
        return sessions.getCompanionSessionCount();
    }

    public int getStandaloneSessionCount() {
        return sessions.getStandaloneSessionCount();
    }

    public List<Integer> getDesktopAndCompanionSessionIds() {
        return sessions.getDesktopAndCompanionSessionIds();
    }

    public List<Integer> getCompanionEventSubscriberSessionIds() {
        return sessions.getCompanionEventSubscriberSessionIds();
    }

    public boolean markSessionCompanionEventsSubscribed(int sessionId) {
        return sessions.markSessionCompanionEventsSubscribed(sessionId);
    }

    public int getActiveSinkType() {
        return sessions.getActiveSinkType();
    }

    public String getActivePeerName() {
        return sessions.getActivePeerName();
    }

    /**
     * Starts the always-on ADB abstract socket listener ("opaldesk_stream").
     */
    public void startUsbListener() {
        synchronized (lifecycleLock) {
            if (stopped || usbServerSocket != null) return;
            try {
                usbServerSocket = new LocalServerSocket(USB_SOCKET_NAME);
                usbAcceptThread = new Thread(this::usbAcceptLoop, "ExtUsbStreamAccept");
                usbAcceptThread.setDaemon(true);
                usbAcceptThread.start();
                System.out.println("OPALDESK_USB_STREAM_LISTENING @" + USB_SOCKET_NAME);
            } catch (Throwable t) {
                System.err.println("OPALDESK_USB_STREAM_BIND_FAILED: " + t);
            }
        }
    }

    public WifiConfigStatus configureWifi(boolean enable, int requestedPort) {
        return configureWifi(enable, requestedPort, BridgeProtocol.TLS_BIND_SCOPE_ALL);
    }

    public WifiConfigStatus configureWifi(boolean enable, int requestedPort, int requestedBindScope) {
        int effectiveScope = (requestedBindScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY)
                ? BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY
                : BridgeProtocol.TLS_BIND_SCOPE_ALL;
        synchronized (lifecycleLock) {
            if (requestedPort >= 1024 && requestedPort <= 65535) {
                wifiPort = requestedPort;
            }
            if (!enable) {
                stopWifiListenerLocked();
                sessions.disconnectMatching(s -> DisplaySink.isTlsNetworkSink(s.sinkType), "TLS stream server disabled");
                return new WifiConfigStatus(false, wifiPort, getCertFingerprint(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
            }

            if (stopped) {
                return new WifiConfigStatus(false, wifiPort, getCertFingerprint(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
            }

            String targetBindHost = "";
            if (effectiveScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) {
                NetworkInterfaceClassifier.Endpoint usbEp = detectUsbTetherEndpoint();
                if (!usbEp.isPresent()) {
                    System.err.println("OPALDESK_USB_TETHER_BIND_ABORTED reason=no_active_usb_tether_interface");
                    stopWifiListenerLocked();
                    sessions.disconnectMatching(
                            s -> DisplaySink.isTlsNetworkSink(s.sinkType),
                            "No active USB tethering interface");
                    return new WifiConfigStatus(false, wifiPort, getCertFingerprint(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
                }
                targetBindHost = usbEp.ipv4;
            }

            if (wifiEnabled && wifiServerSocket != null && !wifiServerSocket.isClosed()
                    && wifiServerSocket.getLocalPort() == wifiPort
                    && wifiBindScope == effectiveScope
                    && targetBindHost.equals(activeBindHost)) {
                return new WifiConfigStatus(true, wifiPort, getCertFingerprint(), wifiBindScope);
            }

            stopWifiListenerLocked();
            if (effectiveScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) {
                sessions.disconnectMatching(
                        s -> s.sinkType == DisplaySink.SINK_REMOTE_WIFI,
                        "Switched to USB tethering only mode");
            }
            try {
                if (tlsIdentity == null) {
                    String lanIp = detectLanIpv4Address();
                    List<String> extraSans = (lanIp != null && !lanIp.isEmpty())
                            ? Collections.singletonList(lanIp)
                            : null;
                    tlsIdentity = SelfSignedCertGenerator.loadOrCreate(certStoreFile, deviceName, extraSans);
                }
                SSLContext sslCtx = SelfSignedCertGenerator.createServerSSLContext(tlsIdentity);
                SSLServerSocket ss = (SSLServerSocket) sslCtx.getServerSocketFactory().createServerSocket();
                ss.setReuseAddress(true);
                InetSocketAddress bindAddr = targetBindHost.isEmpty()
                        ? new InetSocketAddress(wifiPort)
                        : new InetSocketAddress(InetAddress.getByName(targetBindHost), wifiPort);
                ss.bind(bindAddr, 8);
                applySecureTlsProtocols(ss.getSupportedProtocols(), ss::setEnabledProtocols);

                wifiServerSocket = ss;
                wifiBindScope = effectiveScope;
                activeBindHost = targetBindHost;
                wifiEnabled = true;
                wifiAcceptThread = new Thread(() -> wifiAcceptLoop(ss), "ExtWifiStreamAccept");
                wifiAcceptThread.setDaemon(true);
                wifiAcceptThread.start();
                System.out.println("OPALDESK_WIFI_TLS_LISTENING port=" + wifiPort
                        + " scope=" + (effectiveScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY ? "usb_tether_only" : "all")
                        + (targetBindHost.isEmpty() ? "" : " bind=" + targetBindHost)
                        + " fp=" + tlsIdentity.certFingerprintSha256.substring(0, 12));
            } catch (Throwable t) {
                System.err.println("OPALDESK_WIFI_TLS_START_FAILED: " + t);
                stopWifiListenerLocked();
            }
            return new WifiConfigStatus(wifiEnabled, wifiPort, getCertFingerprint(), getWifiBindScope());
        }
    }

    public void reconcileUsbTetherBindIfNeeded() {
        if (!wifiEnabled || wifiBindScope != BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) {
            return;
        }
        synchronized (lifecycleLock) {
            if (!wifiEnabled || wifiBindScope != BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) {
                return;
            }
            NetworkInterfaceClassifier.Endpoint cur = detectUsbTetherEndpoint();
            if (!cur.isPresent()) {
                System.out.println("OPALDESK_USB_TETHER_LOST closing USB-only listener");
                stopWifiListenerLocked();
                sessions.disconnectMatching(
                        s -> DisplaySink.isTlsNetworkSink(s.sinkType),
                        "USB tethering disconnected");
                return;
            }
            if (!cur.ipv4.equals(activeBindHost)) {
                System.out.println("OPALDESK_USB_TETHER_IP_CHANGED " + activeBindHost + "->" + cur.ipv4 + " rebinding");
                configureWifi(true, wifiPort, BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY);
            }
        }
    }

    public boolean revokeTrustedClient(String clientId) {
        boolean ok = trustStore.revokeClient(clientId);
        sessions.disconnectMatching(
                s -> DisplaySink.isTlsNetworkSink(s.sinkType)
                        && ("*".equals(clientId) || (s.clientId != null && s.clientId.equals(clientId))),
                "Trusted client revoked");
        return ok;
    }

    public void disconnectSession(int sessionId, String reason) {
        sessions.disconnectSession(sessionId, reason);
    }

    public void disconnectActiveClient(String reason) {
        sessions.disconnectAll(reason);
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            stopped = true;
            stopWifiListenerLocked();
            if (usbServerSocket != null) {
                ClientSession.closeTransportQuietly(usbServerSocket);
                usbServerSocket = null;
            }
        }
        pairingCoordinator.cancelAll();
        disconnectActiveClient("Daemon shutting down");
    }

    // ---- Session-addressed outbound frame senders ----

    public void updateSessionTargetFps(int sessionId, float targetFps) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed()) {
            s.setTargetFps(targetFps);
        }
    }

    public void sendVideoConfig(int sessionId, OpalCastProtocol.VideoConfig config, long timestampUs) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && config != null) {
            s.enqueue(OpalCastProtocol.TYPE_VIDEO_CONFIG, 0, timestampUs, OpalCastProtocol.encodeVideoConfig(config));
        }
    }

    public void sendVideoFrame(int sessionId, byte[] annexB, int flags, long timestampUs) {
        int len = (annexB != null) ? annexB.length : 0;
        sendVideoFrame(sessionId, annexB, len, flags, timestampUs, null);
    }

    public void sendVideoFrame(
            int sessionId,
            byte[] annexB,
            int length,
            int flags,
            long timestampUs,
            Consumer<byte[]> onRelease) {
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
        if (s != null && !s.isClosed()) {
            s.enqueue(OpalCastProtocol.TYPE_AUDIO_FRAME, 0, timestampUs, aacAu);
        }
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
        for (int sid : getCompanionEventSubscriberSessionIds()) {
            sendNotificationEvent(sid, jsonUtf8);
        }
    }

    public void sendMediaState(int sessionId, byte[] jsonUtf8) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && jsonUtf8 != null && jsonUtf8.length > 0) {
            s.enqueue(OpalCastProtocol.TYPE_MEDIA_STATE, 0, nowTimestampUs(), jsonUtf8);
        }
    }

    public void broadcastMediaState(byte[] jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.length == 0) return;
        for (int sid : getCompanionEventSubscriberSessionIds()) {
            sendMediaState(sid, jsonUtf8);
        }
    }

    public void sendCallState(int sessionId, byte[] jsonUtf8) {
        ClientSession s = sessions.getSession(sessionId);
        if (s != null && !s.isClosed() && jsonUtf8 != null && jsonUtf8.length > 0) {
            s.enqueue(OpalCastProtocol.TYPE_CALL_STATE, 0, nowTimestampUs(), jsonUtf8);
        }
    }

    public void broadcastCallState(byte[] jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.length == 0) return;
        for (int sid : getCompanionEventSubscriberSessionIds()) {
            sendCallState(sid, jsonUtf8);
        }
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

    // ---- Accept loops ----

    private void usbAcceptLoop() {
        while (!stopped) {
            LocalServerSocket srv = this.usbServerSocket;
            if (srv == null) break;
            try {
                LocalSocket client = srv.accept();
                if (stopped) {
                    ClientSession.closeTransportQuietly(client);
                    break;
                }
                Credentials cred = null;
                try {
                    cred = client.getPeerCredentials();
                } catch (IOException e) {
                    System.err.println("OPALDESK_USB_CRED_ERR: " + e.getMessage());
                }
                int peerUid = (cred != null) ? cred.getUid() : -1;
                if (peerUid != 0 && peerUid != 2000) {
                    System.err.println("OPALDESK_USB_STREAM_REJECT_UID uid=" + peerUid);
                    ClientSession.closeTransportQuietly(client);
                    continue;
                }
                Thread handler = new Thread(() -> handleUsbClient(client), "ExtUsbClient");
                handler.setDaemon(true);
                handler.start();
            } catch (Throwable t) {
                if (!stopped) {
                    System.err.println("OPALDESK_USB_ACCEPT_ERR: " + t.getMessage());
                    SystemClock.sleep(200);
                }
            }
        }
    }

    private void wifiAcceptLoop(SSLServerSocket serverSock) {
        while (!stopped && wifiEnabled && !serverSock.isClosed()) {
            try {
                Socket raw = serverSock.accept();
                if (stopped || !wifiEnabled || !(raw instanceof SSLSocket)) {
                    ClientSession.closeTransportQuietly(raw);
                    continue;
                }
                SSLSocket ssl = (SSLSocket) raw;
                Thread handler = new Thread(() -> handleWifiClient(ssl), "ExtWifiClient");
                handler.setDaemon(true);
                handler.start();
            } catch (Throwable t) {
                if (!stopped && wifiEnabled && !serverSock.isClosed()) {
                    System.err.println("OPALDESK_WIFI_ACCEPT_ERR: " + t.getMessage());
                    SystemClock.sleep(150);
                }
            }
        }
    }

    private void stopWifiListenerLocked() {
        wifiEnabled = false;
        wifiBindScope = BridgeProtocol.TLS_BIND_SCOPE_ALL;
        activeBindHost = "";
        pairingCoordinator.cancelAll();
        SSLServerSocket ss = wifiServerSocket;
        wifiServerSocket = null;
        ClientSession.closeTransportQuietly(ss);
    }

    // ---- Shared Handshake & Transport Handlers ----

    private OpalCastFrameTransport negotiateFrameTransport(
            BufferedInputStream in,
            OutputStream out,
            boolean isTls,
            int listenPort) throws IOException {
        in.mark(8);
        byte[] prefix = new byte[4];
        int got = 0;
        while (got < 4) {
            int n = in.read(prefix, got, 4 - got);
            if (n < 0) {
                return null;
            }
            got += n;
        }
        in.reset();

        if (WebSocketHttpHandler.isHttpRequestPrefix(prefix)) {
            String fullFp = getCertFingerprint();
            String shortFp = (fullFp.length() >= 12) ? fullFp.substring(0, 12) : fullFp;
            WebSocketHttpHandler.ServerMeta meta =
                    new WebSocketHttpHandler.ServerMeta(deviceName, shortFp, listenPort, isTls);
            return WebSocketHttpHandler.handle(in, out, meta);
        }
        return OpalCastFrameTransport.raw(in, out);
    }

    private void handleUsbClient(LocalSocket sock) {
        try {
            try { sock.setSendBufferSize(USB_SOCKET_SEND_BUF_BYTES); } catch (IOException ignored) {}
            try { sock.setReceiveBufferSize(SOCKET_IO_BUFFER_BYTES); } catch (IOException ignored) {}
            sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), SOCKET_IO_BUFFER_BYTES);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), SOCKET_IO_BUFFER_BYTES);

            OpalCastFrameTransport frameTransport = negotiateFrameTransport(in, out, false, wifiPort);
            if (frameTransport == null) {
                ClientSession.closeTransportQuietly(sock);
                return;
            }

            OpalCastProtocol.HelloMsg hello = readAndValidateHello(frameTransport, sock);
            if (hello == null) return;

            int sinkType = OpalCastProtocol.TRANSPORT_HINT_WIFI_ADB.equalsIgnoreCase(hello.transportHint)
                    ? DisplaySink.SINK_REMOTE_WIFI_ADB
                    : DisplaySink.SINK_REMOTE_USB;
            String clientId = TrustStoreManager.sanitizeClientId(hello.clientId);
            if (clientId.isEmpty()) clientId = "usb-peer";
            String defaultName = (sinkType == DisplaySink.SINK_REMOTE_WIFI_ADB) ? "Mac (Wi-Fi ADB)" : "Mac (USB)";
            String clientName = TrustStoreManager.sanitizeClientName(hello.clientName, defaultName);
            String authMsg = (sinkType == DisplaySink.SINK_REMOTE_WIFI_ADB)
                    ? "Wireless ADB channel authenticated"
                    : "USB ADB channel authenticated";

            completeHandshakeAndRun(
                    sinkType, true, true, clientId, clientName, "", authMsg, true,
                    hello, sock, () -> sock.setSoTimeout(0), frameTransport);
        } catch (Throwable t) {
            System.err.println("OPALDESK_USB_CLIENT_ERR: " + t.getMessage());
            ClientSession.closeTransportQuietly(sock);
        }
    }

    private void handleWifiClient(SSLSocket sock) {
        try {
            boolean isUsbTetherLink = NetworkInterfaceClassifier.isUsbTetheringLocalAddress(sock.getLocalAddress());
            if (wifiBindScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY && !isUsbTetherLink) {
                System.err.println("OPALDESK_USB_TETHER_REJECT_NON_USB local=" + sock.getLocalAddress());
                ClientSession.closeTransportQuietly(sock);
                return;
            }
            int networkSinkType = isUsbTetherLink
                    ? DisplaySink.SINK_REMOTE_USB_TETHER
                    : DisplaySink.SINK_REMOTE_WIFI;

            sock.setTcpNoDelay(true);
            sock.setKeepAlive(true);
            try { sock.setTrafficClass(REALTIME_MEDIA_TRAFFIC_CLASS); } catch (SocketException ignored) {}
            int sendBufBytes = isUsbTetherLink ? USB_SOCKET_SEND_BUF_BYTES : WIFI_SOCKET_SEND_BUF_BYTES;
            try { sock.setSendBufferSize(sendBufBytes); } catch (SocketException ignored) {}
            try { sock.setReceiveBufferSize(SOCKET_IO_BUFFER_BYTES); } catch (SocketException ignored) {}
            sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            applySecureTlsProtocols(sock.getSupportedProtocols(), sock::setEnabledProtocols);
            sock.startHandshake();

            String peerIp = (sock.getInetAddress() != null) ? sock.getInetAddress().getHostAddress() : "unknown";
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), SOCKET_IO_BUFFER_BYTES);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), SOCKET_IO_BUFFER_BYTES);

            OpalCastFrameTransport frameTransport = negotiateFrameTransport(in, out, true, wifiPort);
            if (frameTransport == null) {
                ClientSession.closeTransportQuietly(sock);
                return;
            }

            OpalCastProtocol.HelloMsg hello = readAndValidateHello(frameTransport, sock);
            if (hello == null) return;

            TrustStoreManager.TokenVerifyResult verify = trustStore.verifyTokenOrCheckRateLimit(
                    hello.clientId, hello.clientName, hello.authToken, peerIp);
            if (verify.status == TrustStoreManager.TokenVerifyResult.STATUS_INVALID_CLIENT
                    || verify.isRateLimited()) {
                sendAuthFailureAndClose(frameTransport, sock, verify.message);
                return;
            }

            String clientId = TrustStoreManager.sanitizeClientId(hello.clientId);
            String clientName = TrustStoreManager.sanitizeClientName(hello.clientName, "OpalDesk 设备");

            if (verify.isAuthenticated()) {
                boolean permanent = verify.isPermanent();
                completeHandshakeAndRun(
                        networkSinkType, false, permanent, clientId, clientName,
                        "", verify.message, permanent,
                        hello, sock, () -> sock.setSoTimeout(0), frameTransport);
                return;
            }

            sock.setSoTimeout((int) PairingApprovalCoordinator.DEFAULT_TIMEOUT_MS + 10_000);
            int decision = pairingCoordinator.requestAndAwait(
                    clientId, clientName, peerIp, PairingApprovalCoordinator.DEFAULT_TIMEOUT_MS);
            if (decision == PairingApprovalCoordinator.DECISION_APPROVE_PERMANENT) {
                String permanentToken = trustStore.grantPermanentTrust(clientId, clientName, peerIp);
                completeHandshakeAndRun(
                        networkSinkType, false, true, clientId, clientName,
                        permanentToken, "已永久允许此设备连接", true,
                        hello, sock, () -> sock.setSoTimeout(0), frameTransport);
            } else if (decision == PairingApprovalCoordinator.DECISION_APPROVE_ONCE) {
                trustStore.clearFailures(peerIp);
                String handoverToken = hello.isAppCatalog()
                        ? trustStore.issueSingleUseHandoverToken(clientId, peerIp)
                        : "";
                completeHandshakeAndRun(
                        networkSinkType, false, false, clientId, clientName,
                        handoverToken, "已临时允许本次连接", false,
                        hello, sock, () -> sock.setSoTimeout(0), frameTransport);
            } else if (decision == PairingApprovalCoordinator.DECISION_REJECT) {
                trustStore.recordRejection(peerIp);
                sendAuthFailureAndClose(frameTransport, sock, "对方已拒绝此次投屏请求");
            } else if (decision == PairingApprovalCoordinator.DECISION_TIMED_OUT) {
                sendAuthFailureAndClose(frameTransport, sock, "等待对方确认超时，请重试");
            } else {
                sendAuthFailureAndClose(frameTransport, sock, "对方正忙或已关闭无线接收服务");
            }
        } catch (Throwable t) {
            System.err.println("OPALDESK_WIFI_CLIENT_ERR: " + t.getMessage());
            ClientSession.closeTransportQuietly(sock);
        }
    }

    private OpalCastProtocol.HelloMsg readAndValidateHello(OpalCastFrameTransport frameTransport, Closeable sock)
            throws IOException {
        OpalCastProtocol.Frame first = frameTransport.readFrame();
        if (first == null || first.type != OpalCastProtocol.TYPE_HELLO) {
            sendAuthFailureAndClose(frameTransport, sock, "Expected HELLO frame");
            return null;
        }
        OpalCastProtocol.HelloMsg hello = OpalCastProtocol.decodeHello(first.payload);
        if (hello.protocolVersion != OpalCastProtocol.PROTOCOL_VERSION) {
            sendAuthFailureAndClose(frameTransport, sock, "Unsupported protocolVersion: " + hello.protocolVersion);
            return null;
        }
        if (hello.isStandaloneApp() && hello.appPackage.isEmpty()) {
            sendAuthFailureAndClose(frameTransport, sock, "Missing appPackage for standalone_app session");
            return null;
        }
        return hello;
    }

    private void completeHandshakeAndRun(
            int sinkType,
            boolean isAdbLocalSocket,
            boolean permanentTrust,
            String clientId,
            String clientName,
            String issuedToken,
            String authMsg,
            boolean permanentTokenFlag,
            OpalCastProtocol.HelloMsg hello,
            Closeable sock,
            TimeoutClearer clearTimeout,
            OpalCastFrameTransport frameTransport) throws IOException {
        if (!sessions.hasCapacityForSession(hello, clientId)) {
            String errMsg = hello.isCompanion()
                    ? ("Maximum " + MAX_COMPANION_SESSIONS + " companion sessions active")
                    : ("Maximum " + MAX_STANDALONE_SESSIONS + " standalone app sessions active");
            sendAuthFailureAndClose(frameTransport, sock, errMsg);
            return;
        }

        OpalCastProtocol.AuthResultMsg okMsg = new OpalCastProtocol.AuthResultMsg(
                true, issuedToken, authMsg, deviceName, getCertFingerprint(), permanentTokenFlag);
        writeAuthResultImmediate(frameTransport, okMsg);
        clearTimeout.clear();
        runAuthenticatedSession(
                sinkType, isAdbLocalSocket, permanentTrust, clientId, clientName, hello, sock, frameTransport);
    }

    void runAuthenticatedSessionForTest(
            int sinkType,
            boolean isAdbLocalSocket,
            boolean permanentTrust,
            String clientId,
            String clientName,
            OpalCastProtocol.HelloMsg hello,
            Closeable transport,
            OpalCastFrameTransport frameTransport) {
        runAuthenticatedSession(
                sinkType, isAdbLocalSocket, permanentTrust, clientId, clientName, hello, transport, frameTransport);
    }

    private void runAuthenticatedSession(
            int sinkType,
            boolean isAdbLocalSocket,
            boolean permanentTrust,
            String clientId,
            String clientName,
            OpalCastProtocol.HelloMsg hello,
            Closeable transport,
            OpalCastFrameTransport frameTransport) {
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
            current.close(isCompanion
                    ? "Maximum companion sessions reached"
                    : "Maximum standalone app sessions reached");
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
            while (!stopped && !current.isClosed()) {
                OpalCastProtocol.Frame frame = frameTransport.readFrame();
                if (frame == null) {
                    break;
                }
                RemoteUplinkFrameDispatcher.dispatch(
                        current, frame, listener, this::handleWifiUpgradeRequest, RemoteStreamServer::nowTimestampUs);
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

    /**
     * Handles OpalCast v1.1 0x29 REQUEST_WIFI_UPGRADE.
     * SECURITY: Strictly gated to authenticated USB/ADB LocalSocket sessions ONLY!
     */
    private void handleWifiUpgradeRequest(ClientSession session) {
        long ts = SystemClock.elapsedRealtimeNanos() / 1000L;
        if (!session.isAdbLocalSocket) {
            System.err.println("OPALDESK_WIFI_UPGRADE_DENIED non-ADB sessionId=" + session.sessionId);
            OpalCastProtocol.WifiUpgradeReadyMsg denied = new OpalCastProtocol.WifiUpgradeReadyMsg(
                    false, "", 0, "", "", "REQUEST_WIFI_UPGRADE is only allowed over authenticated ADB/USB sessions");
            session.enqueue(OpalCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    OpalCastProtocol.encodeWifiUpgradeReady(denied));
            return;
        }

        WifiConfigStatus st = configureWifi(true, wifiPort);
        if (!st.enabled) {
            OpalCastProtocol.WifiUpgradeReadyMsg err = new OpalCastProtocol.WifiUpgradeReadyMsg(
                    false, "", st.port, st.certFingerprint, "", "Failed to start Wi-Fi TLS listener");
            session.enqueue(OpalCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    OpalCastProtocol.encodeWifiUpgradeReady(err));
            return;
        }

        String lanIp = detectLanIpv4Address();
        if (lanIp.isEmpty()) {
            OpalCastProtocol.WifiUpgradeReadyMsg noIp = new OpalCastProtocol.WifiUpgradeReadyMsg(
                    false, "", st.port, st.certFingerprint, "", "No non-loopback LAN IPv4 address detected");
            session.enqueue(OpalCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    OpalCastProtocol.encodeWifiUpgradeReady(noIp));
            return;
        }

        String token = trustStore.issueTokenForAuthenticatedPeer(session.clientId, session.clientName);
        if (token.isEmpty()) {
            OpalCastProtocol.WifiUpgradeReadyMsg noTok = new OpalCastProtocol.WifiUpgradeReadyMsg(
                    false, lanIp, st.port, st.certFingerprint, "", "Failed to issue trusted authToken");
            session.enqueue(OpalCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    OpalCastProtocol.encodeWifiUpgradeReady(noTok));
            return;
        }

        OpalCastProtocol.WifiUpgradeReadyMsg ready = new OpalCastProtocol.WifiUpgradeReadyMsg(
                true, lanIp, st.port, st.certFingerprint, token, "");
        session.enqueue(OpalCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                OpalCastProtocol.encodeWifiUpgradeReady(ready));
        System.out.println("OPALDESK_WIFI_UPGRADE_READY host=" + lanIp + ":" + st.port
                + " clientId=" + session.clientId);
        if (listener != null) {
            listener.onWifiUpgradeEnabled();
        }
    }

    public static String detectLanIpv4Address() {
        return NetworkInterfaceClassifier.selectBestLanIp(
                NetworkInterfaceClassifier.snapshotSystemInterfaces());
    }

    public static NetworkInterfaceClassifier.Endpoint detectUsbTetherEndpoint() {
        return NetworkInterfaceClassifier.selectUsbTetherEndpoint(
                NetworkInterfaceClassifier.snapshotSystemInterfaces());
    }

    private void sendAuthFailureAndClose(OpalCastFrameTransport frameTransport, Closeable sock, String msg) {
        try {
            OpalCastProtocol.AuthResultMsg fail = new OpalCastProtocol.AuthResultMsg(
                    false, "", msg, deviceName, getCertFingerprint());
            writeAuthResultImmediate(frameTransport, fail);
        } catch (IOException e) {
            System.err.println("OPALDESK_AUTH_FAIL_SEND_WARN: " + e.getMessage());
        } finally {
            ClientSession.closeTransportQuietly(sock);
        }
    }

    private static void writeAuthResultImmediate(
            OpalCastFrameTransport frameTransport,
            OpalCastProtocol.AuthResultMsg msg) throws IOException {
        byte[] payload = OpalCastProtocol.encodeAuthResult(msg);
        frameTransport.writeFrame(OpalCastProtocol.TYPE_AUTH_RESULT, 0,
                nowTimestampUs(), payload, 0, payload.length);
        frameTransport.flush();
    }

    private static long nowTimestampUs() {
        try {
            return SystemClock.elapsedRealtimeNanos() / 1000L;
        } catch (Throwable ignored) {
            return System.nanoTime() / 1000L;
        }
    }

    private static void applySecureTlsProtocols(String[] supported, Consumer<String[]> setter) {
        List<String> list = new ArrayList<>();
        if (supported != null) {
            for (String p : supported) {
                if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) {
                    list.add(p);
                }
            }
        }
        if (!list.isEmpty()) {
            setter.accept(list.toArray(new String[0]));
        }
    }

    private static String resolveDeviceName() {
        try {
            String model = Build.MODEL;
            if (model != null && !model.trim().isEmpty()) {
                return model.trim();
            }
        } catch (Throwable ignored) {}
        return "Android";
    }
}
