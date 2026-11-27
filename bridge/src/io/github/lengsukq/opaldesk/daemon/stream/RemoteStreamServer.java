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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Dual-transport, multi-session OpalCast v1.1 stream server:
 * 1) Always-on ADB LocalServerSocket("opaldesk_stream") with strict peer UID (0/2000) check,
 *    supporting both wired USB (SINK_REMOTE_USB) and wireless ADB (SINK_REMOTE_WIFI_ADB) via
 *    HELLO.transportHint, plus authenticated Wi-Fi TLS zero-PIN upgrade (REQUEST_WIFI_UPGRADE).
 * 2) On-demand wireless SSLServerSocket (OFF by default; TLS 1.2/1.3, TCP_NODELAY, ECDSA P-256,
 *    one-time 6-digit PIN + 32-byte hex authToken trust store).
 * 3) Concurrent multi-session isolation: 1 main "desktop" session + up to 3 independent
 *    "standalone_app" pop-out sessions without kicking each other off.
 */
public final class RemoteStreamServer {
    public static final String USB_SOCKET_NAME = "opaldesk_stream";
    public static final int DEFAULT_WIFI_PORT = 19878;
    public static final int MAX_STANDALONE_SESSIONS = 3;
    public static final int MAX_COMPANION_SESSIONS = 2;

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int SOCKET_IO_BUFFER_BYTES = 131_072;
    private static final int USB_SOCKET_SEND_BUF_BYTES = 512 * 1024;
    // 512 KiB Wi-Fi TCP send buffer absorbs single 2.5K/4K IDR bursts (~180-200ms @ 22 Mbps)
    // without triggering premature sender-queue congestion drops while staying bounded against bufferbloat.
    private static final int WIFI_SOCKET_SEND_BUF_BYTES = 512 * 1024;
    // DSCP EF (46 << 2 = 0xB8) signals low-latency real-time media priority to 802.11e WMM Wi-Fi 6 APs.
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
    private final File certStoreFile;
    private final String deviceName;

    private final Object lifecycleLock = new Object();
    private volatile boolean stopped = false;

    // ADB LocalServerSocket (handles both wired USB and wireless ADB)
    private LocalServerSocket usbServerSocket;
    private Thread usbAcceptThread;

    // Wireless / USB-tether TLS server (OFF by default!)
    private volatile boolean wifiEnabled = false;
    private volatile int wifiPort = DEFAULT_WIFI_PORT;
    private volatile int wifiBindScope = BridgeProtocol.TLS_BIND_SCOPE_ALL;
    private volatile String activeBindHost = "";
    private volatile SelfSignedCertGenerator.ServerIdentity tlsIdentity;
    private SSLServerSocket wifiServerSocket;
    private Thread wifiAcceptThread;

    // Multi-session registry: 1 desktop + up to 3 standalone_app + up to 2 companion sessions
    private final AtomicInteger nextSessionId = new AtomicInteger(0);
    private final Object sessionLock = new Object();
    private volatile ClientSession desktopSession;
    private final LinkedHashMap<Integer, ClientSession> standaloneSessions = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, ClientSession> companionSessions = new LinkedHashMap<>();
    private final ConcurrentHashMap<Integer, ClientSession> allSessions = new ConcurrentHashMap<>();

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
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed()) {
            return d.permanentTrust;
        }
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) return s.permanentTrust;
            }
        }
        return false;
    }

    public boolean hasDesktopSession() {
        ClientSession s = desktopSession;
        return s != null && !s.isClosed();
    }

    public boolean hasActiveSession() {
        if (hasDesktopSession()) return true;
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) return true;
            }
        }
        return false;
    }

    public boolean hasCompanionSession() {
        synchronized (sessionLock) {
            for (ClientSession s : companionSessions.values()) {
                if (s != null && !s.isClosed()) return true;
            }
        }
        return false;
    }

    public int getCompanionSessionCount() {
        synchronized (sessionLock) {
            int count = 0;
            for (ClientSession s : companionSessions.values()) {
                if (s != null && !s.isClosed()) count++;
            }
            return count;
        }
    }

    public int getStandaloneSessionCount() {
        synchronized (sessionLock) {
            int count = 0;
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) count++;
            }
            return count;
        }
    }

    public List<Integer> getDesktopAndCompanionSessionIds() {
        List<Integer> ids = new ArrayList<>(3);
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed()) {
            ids.add(d.sessionId);
        }
        synchronized (sessionLock) {
            for (ClientSession s : companionSessions.values()) {
                if (s != null && !s.isClosed()) {
                    ids.add(s.sessionId);
                }
            }
        }
        return ids;
    }

    /**
     * Returns all live sessions eligible to receive OPD1 v1.2 {@code 0x19}/{@code 0x1A} pushes:
     * all companion sessions plus any desktop session that declared {@code companionEvents=true}
     * in {@code HELLO} or dynamically subscribed via {@code 0x31}/{@code 0x32}.
     * Protects pre-P1 legacy desktop clients from disconnecting on unknown frame types.
     */
    public List<Integer> getCompanionEventSubscriberSessionIds() {
        List<Integer> ids = new ArrayList<>(3);
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed() && d.isCompanionEventsSubscribed()) {
            ids.add(d.sessionId);
        }
        synchronized (sessionLock) {
            for (ClientSession s : companionSessions.values()) {
                if (s != null && !s.isClosed() && s.isCompanionEventsSubscribed()) {
                    ids.add(s.sessionId);
                }
            }
        }
        return ids;
    }

    public boolean markSessionCompanionEventsSubscribed(int sessionId) {
        ClientSession s = allSessions.get(sessionId);
        if (s == null || s.isClosed()) return false;
        boolean wasSubscribed = s.isCompanionEventsSubscribed();
        s.setCompanionEventsSubscribed(true);
        return !wasSubscribed;
    }

    public int getActiveSinkType() {
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed()) {
            return d.sinkType;
        }
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) return s.sinkType;
            }
        }
        return DisplaySink.SINK_NONE;
    }

    public String getActivePeerName() {
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed() && d.clientName != null) {
            return d.clientName;
        }
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed() && s.clientName != null) return s.clientName;
            }
        }
        return "";
    }

    /**
     * Starts the always-on ADB abstract socket listener ("opaldesk_stream").
     * Does NOT start Wi-Fi TLS server (wireless remains off by default until explicitly enabled
     * via Binder code 45 or authenticated ADB REQUEST_WIFI_UPGRADE).
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

    /**
     * Configures the on-demand TLS 1.2/1.3 server on wildcard {@code 0.0.0.0} (Binder TX 24 or ADB Wi-Fi upgrade).
     */
    public WifiConfigStatus configureWifi(boolean enable, int requestedPort) {
        return configureWifi(enable, requestedPort, BridgeProtocol.TLS_BIND_SCOPE_ALL);
    }

    /**
     * Configures the on-demand TLS 1.2/1.3 server with an explicit bind scope:
     * {@link BridgeProtocol#TLS_BIND_SCOPE_ALL} (default wildcard) or
     * {@link BridgeProtocol#TLS_BIND_SCOPE_USB_TETHER_ONLY} (Beta isolated USB tethering IP bind).
     */
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
                disconnectMatchingSessions(s -> DisplaySink.isTlsNetworkSink(s.sinkType), "TLS stream server disabled");
                return new WifiConfigStatus(false, wifiPort, getCertFingerprint(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
            }

            if (stopped) {
                return new WifiConfigStatus(false, wifiPort, getCertFingerprint(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
            }

            String targetBindHost = "";
            if (effectiveScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) {
                NetworkInterfaceClassifier.Endpoint usbEp = detectUsbTetherEndpoint();
                if (!usbEp.isPresent()) {
                    // Fail closed: never fall back to 0.0.0.0 when user explicitly requested USB-only isolation.
                    System.err.println("OPALDESK_USB_TETHER_BIND_ABORTED reason=no_active_usb_tether_interface");
                    stopWifiListenerLocked();
                    disconnectMatchingSessions(
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
                disconnectMatchingSessions(
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

    /**
     * Reconciles a USB-tethering-only listener whenever Android re-randomizes the USB tethering IP
     * on cable replug or tears down the interface on unplug. Strictly no-op in regular LAN mode.
     */
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
                disconnectMatchingSessions(
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
        disconnectMatchingSessions(
                s -> DisplaySink.isTlsNetworkSink(s.sinkType)
                        && ("*".equals(clientId) || (s.clientId != null && s.clientId.equals(clientId))),
                "Trusted client revoked");
        return ok;
    }

    public void disconnectSession(int sessionId, String reason) {
        ClientSession target = allSessions.get(sessionId);
        if (target != null) {
            target.close(reason);
        }
    }

    public void disconnectActiveClient(String reason) {
        List<ClientSession> toClose = new ArrayList<>(allSessions.values());
        for (ClientSession s : toClose) {
            if (s != null) s.close(reason);
        }
    }

    private void disconnectMatchingSessions(Predicate<ClientSession> pred, String reason) {
        List<ClientSession> toClose = new ArrayList<>();
        for (ClientSession s : allSessions.values()) {
            if (s != null && pred.test(s)) {
                toClose.add(s);
            }
        }
        for (ClientSession s : toClose) {
            s.close(reason);
        }
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
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            s.setTargetFps(targetFps);
        }
    }

    public void sendVideoConfig(int sessionId, OpalCastProtocol.VideoConfig config, long timestampUs) {
        ClientSession s = allSessions.get(sessionId);
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
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            s.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, flags, timestampUs, annexB, length, onRelease);
        } else if (onRelease != null && annexB != null) {
            onRelease.accept(annexB);
        }
    }

    public void sendAudioConfig(int sessionId, OpalCastProtocol.AudioConfig config, long timestampUs) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && config != null) {
            s.enqueue(OpalCastProtocol.TYPE_AUDIO_CONFIG, 0, timestampUs, OpalCastProtocol.encodeAudioConfig(config));
        }
    }

    public void sendAudioFrame(int sessionId, byte[] aacAu, long timestampUs) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            s.enqueue(OpalCastProtocol.TYPE_AUDIO_FRAME, 0, timestampUs, aacAu);
        }
    }

    public void sendDeviceState(int sessionId, OpalCastProtocol.DeviceState state) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && state != null) {
            long ts = nowTimestampUs();
            s.enqueue(OpalCastProtocol.TYPE_DEVICE_STATE, 0, ts, OpalCastProtocol.encodeDeviceState(state));
        }
    }

    public void sendClipboardPush(int sessionId, long seq, String text) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            long ts = nowTimestampUs();
            s.enqueue(OpalCastProtocol.TYPE_CLIPBOARD_PUSH, 0, ts, OpalCastProtocol.encodeClipboardPush(seq, text));
        }
    }

    public void sendClipboardImagePush(int sessionId, long seq, byte[] pngData) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            try {
                byte[] payload = OpalCastProtocol.encodeClipboardImagePush(seq, pngData);
                long ts = nowTimestampUs();
                s.enqueue(OpalCastProtocol.TYPE_CLIPBOARD_PUSH, OpalCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, ts, payload);
            } catch (Throwable t) {
                System.err.println("OPALDESK_CLIP_IMG_SEND_ERR: " + t.getMessage());
            }
        }
    }

    public void sendFileTransferAck(int sessionId, int transferId, int status, String pathOrError) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            long ts = nowTimestampUs();
            s.enqueue(OpalCastProtocol.TYPE_FILE_TRANSFER_ACK, 0, ts,
                    OpalCastProtocol.encodeFileTransferAck(transferId, status, pathOrError));
        }
    }

    public void sendAppList(int sessionId, OpalCastProtocol.AppListMsg appList) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && appList != null) {
            long ts = nowTimestampUs();
            s.enqueue(OpalCastProtocol.TYPE_APP_LIST, 0, ts, OpalCastProtocol.encodeAppList(appList));
        }
    }

    public void sendImeRequest(int sessionId, boolean show, int inputType) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            long ts = nowTimestampUs();
            s.enqueue(OpalCastProtocol.TYPE_IME_REQUEST, 0, ts, OpalCastProtocol.encodeImeRequest(show, inputType));
        }
    }

    public void sendMicState(int sessionId, OpalCastProtocol.MicStateMsg state) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && state != null) {
            long ts = nowTimestampUs();
            s.enqueue(OpalCastProtocol.TYPE_MIC_STATE, 0, ts, OpalCastProtocol.encodeMicState(state));
        }
    }

    public void sendCameraState(int sessionId, OpalCastProtocol.CameraStateMsg state) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && state != null) {
            long ts = nowTimestampUs();
            s.enqueue(OpalCastProtocol.TYPE_CAMERA_STATE, 0, ts, OpalCastProtocol.encodeCameraState(state));
        }
    }

    public void sendNotificationEvent(int sessionId, byte[] jsonUtf8) {
        ClientSession s = allSessions.get(sessionId);
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
        ClientSession s = allSessions.get(sessionId);
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
        ClientSession s = allSessions.get(sessionId);
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
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && response != null) {
            s.enqueue(OpalCastProtocol.TYPE_FILE_OP_RESPONSE, 0, nowTimestampUs(),
                    OpalCastProtocol.encodeFileOpResponse(response));
        }
    }

    public boolean sendFilePullChunk(int sessionId, byte[] payload) {
        ClientSession s = allSessions.get(sessionId);
        if (s == null || s.isClosed() || payload == null) return false;
        return s.enqueueBackpressured(OpalCastProtocol.TYPE_FILE_PULL_CHUNK, 0, nowTimestampUs(), payload, 30_000L);
    }

    public void sendApkInstallStatus(int sessionId, OpalCastProtocol.ApkInstallStatusMsg status) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && status != null) {
            s.enqueue(OpalCastProtocol.TYPE_APK_INSTALL_STATUS, 0, nowTimestampUs(),
                    OpalCastProtocol.encodeApkInstallStatus(status));
        }
    }

    // ---- Desktop-session convenience senders ----

    public void sendAudioConfig(OpalCastProtocol.AudioConfig config, long timestampUs) {
        ClientSession s = desktopSession;
        if (s != null) sendAudioConfig(s.sessionId, config, timestampUs);
    }

    public void sendAudioFrame(byte[] aacAu, long timestampUs) {
        ClientSession s = desktopSession;
        if (s != null) sendAudioFrame(s.sessionId, aacAu, timestampUs);
    }

    public void sendDeviceState(OpalCastProtocol.DeviceState state) {
        ClientSession s = desktopSession;
        if (s != null) sendDeviceState(s.sessionId, state);
    }

    public void sendClipboardPush(long seq, String text) {
        ClientSession s = desktopSession;
        if (s != null) sendClipboardPush(s.sessionId, seq, text);
    }

    public void sendClipboardImagePush(long seq, byte[] pngData) {
        ClientSession s = desktopSession;
        if (s != null) sendClipboardImagePush(s.sessionId, seq, pngData);
    }

    public void sendFileTransferAck(int transferId, int status, String pathOrError) {
        ClientSession s = desktopSession;
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

            // Interactive host-side consent (replaces legacy 6-digit PIN)
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
        if (!hasCapacityForSession(hello, clientId)) {
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

    private boolean hasCapacityForSession(OpalCastProtocol.HelloMsg hello, String clientId) {
        if (hello.isCompanion()) {
            synchronized (sessionLock) {
                if (clientId != null && !clientId.isEmpty()) {
                    for (ClientSession existing : companionSessions.values()) {
                        if (existing != null && !existing.isClosed() && clientId.equals(existing.clientId)) {
                            return true; // Replacing existing companion session for the same clientId
                        }
                    }
                }
                return companionSessions.size() < MAX_COMPANION_SESSIONS;
            }
        }
        if (!hello.isStandaloneApp()) return true;
        synchronized (sessionLock) {
            for (ClientSession existing : standaloneSessions.values()) {
                if (existing != null && !existing.isClosed() && hello.appPackage.equals(existing.appPackage)) {
                    return true; // Replacing existing standalone window for the same app
                }
            }
            return standaloneSessions.size() < MAX_STANDALONE_SESSIONS;
        }
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
        int sessionId = nextSessionId.incrementAndGet();
        boolean isStandalone = hello.isStandaloneApp();
        boolean isCatalog = hello.isAppCatalog();
        boolean isCompanion = hello.isCompanion();
        ClientSession current = new ClientSession(
                sessionId, sinkType, isAdbLocalSocket, permanentTrust, hello.sessionRole, hello.appPackage,
                clientId, clientName, transport, frameTransport, listener);
        current.setTargetFps(hello.fps > 0 ? hello.fps : 60f);
        current.setCompanionEventsSubscribed(
                isCompanion || (!isStandalone && !isCatalog && hello.companionEvents));

        ClientSession replaced = null;
        boolean capacityExceeded = false;
        synchronized (sessionLock) {
            if (isCatalog) {
                allSessions.put(sessionId, current);
            } else if (isCompanion) {
                if (clientId != null && !clientId.isEmpty()) {
                    for (ClientSession existing : companionSessions.values()) {
                        if (existing != null && clientId.equals(existing.clientId)) {
                            replaced = existing;
                            break;
                        }
                    }
                }
                if (replaced != null) {
                    companionSessions.remove(replaced.sessionId);
                } else if (companionSessions.size() >= MAX_COMPANION_SESSIONS) {
                    capacityExceeded = true;
                }
                if (!capacityExceeded) {
                    companionSessions.put(sessionId, current);
                    allSessions.put(sessionId, current);
                }
            } else if (isStandalone) {
                for (ClientSession existing : standaloneSessions.values()) {
                    if (existing != null && hello.appPackage.equals(existing.appPackage)) {
                        replaced = existing;
                        break;
                    }
                }
                if (replaced != null) {
                    standaloneSessions.remove(replaced.sessionId);
                } else if (standaloneSessions.size() >= MAX_STANDALONE_SESSIONS) {
                    capacityExceeded = true;
                }
                if (!capacityExceeded) {
                    standaloneSessions.put(sessionId, current);
                    allSessions.put(sessionId, current);
                }
            } else {
                replaced = desktopSession;
                desktopSession = current;
                allSessions.put(sessionId, current);
            }
        }
        if (capacityExceeded) {
            current.close(isCompanion
                    ? "Maximum companion sessions reached"
                    : "Maximum standalone app sessions reached");
            return;
        }
        if (replaced != null) {
            replaced.close("Replaced by new session");
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
                dispatchUplinkFrame(current, frame);
            }
        } catch (Throwable t) {
            if (!current.isClosed()) {
                System.out.println("OPALDESK_REMOTE_SESSION_END id=" + sessionId
                        + " role=" + current.sessionRole + " peer=" + clientName + " reason=" + t.getMessage());
            }
        } finally {
            current.close("Session closed");
            allSessions.remove(sessionId, current);
            synchronized (sessionLock) {
                if (isCompanion) {
                    if (companionSessions.get(sessionId) == current) {
                        companionSessions.remove(sessionId);
                    }
                } else if (isStandalone) {
                    if (standaloneSessions.get(sessionId) == current) {
                        standaloneSessions.remove(sessionId);
                    }
                } else if (!isCatalog && desktopSession == current) {
                    desktopSession = null;
                }
            }
            if (notifiedConnect && listener != null) {
                listener.onSessionDisconnected(sessionId, sinkType, current.sessionRole, current.closeReason);
            }
        }
    }

    private void dispatchUplinkFrame(ClientSession session, OpalCastProtocol.Frame f) throws IOException {
        int sid = session.sessionId;
        switch (f.type) {
            case OpalCastProtocol.TYPE_PING: {
                long pingId = OpalCastProtocol.decodePingPong(f.payload);
                session.enqueue(OpalCastProtocol.TYPE_PONG, 0,
                        nowTimestampUs(),
                        OpalCastProtocol.encodePingPong(pingId));
                break;
            }
            case OpalCastProtocol.TYPE_REQUEST_IDR: {
                if (listener != null) listener.onRequestIdr(sid);
                break;
            }
            case OpalCastProtocol.TYPE_RESIZE_CONFIG: {
                OpalCastProtocol.ResizeConfig cfg = OpalCastProtocol.decodeResizeConfig(f.payload);
                if (listener != null) listener.onResizeConfig(sid, cfg);
                break;
            }
            case OpalCastProtocol.TYPE_INPUT_MOUSE: {
                OpalCastProtocol.MouseEventMsg msg = OpalCastProtocol.decodeMouseEvent(f.payload);
                if (listener != null) listener.onMouseEvent(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_INPUT_TOUCH: {
                OpalCastProtocol.TouchEventMsg msg = OpalCastProtocol.decodeTouchEvent(f.payload);
                if (listener != null) listener.onTouchEvent(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_INPUT_KEY: {
                OpalCastProtocol.KeyEventMsg msg = OpalCastProtocol.decodeKeyEvent(f.payload);
                if (listener != null) listener.onKeyEvent(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_INPUT_TEXT: {
                if ((f.flags & OpalCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG) != 0) {
                    OpalCastProtocol.ImageClipboardEventMsg imgMsg = OpalCastProtocol.decodeImageClipboardEvent(f.payload);
                    if (listener != null) listener.onImageClipboardEvent(sid, imgMsg);
                } else {
                    OpalCastProtocol.TextEventMsg msg = OpalCastProtocol.decodeTextEvent(f.payload);
                    if (listener != null) listener.onTextEvent(sid, msg);
                }
                break;
            }
            case OpalCastProtocol.TYPE_SYSTEM_ACTION: {
                OpalCastProtocol.SystemActionMsg msg = OpalCastProtocol.decodeSystemAction(f.payload);
                if (listener != null) listener.onSystemAction(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_FILE_TRANSFER_CHUNK: {
                OpalCastProtocol.FileTransferChunkMsg msg = OpalCastProtocol.decodeFileTransferChunk(f.payload);
                if (listener != null) listener.onFileTransferChunk(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_REQUEST_WIFI_UPGRADE: {
                handleWifiUpgradeRequest(session);
                break;
            }
            case OpalCastProtocol.TYPE_LIST_APPS: {
                if (listener != null) listener.onListApps(sid);
                break;
            }
            case OpalCastProtocol.TYPE_WINDOW_ACTION: {
                OpalCastProtocol.WindowActionMsg msg = OpalCastProtocol.decodeWindowAction(f.payload);
                if (listener != null) listener.onWindowAction(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_MIC_CONFIG: {
                OpalCastProtocol.MicConfigMsg msg = OpalCastProtocol.decodeMicConfig(f.payload);
                if (listener != null) listener.onMicConfig(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_MIC_FRAME: {
                if (listener != null && f.payload != null && f.payload.length > 0
                        && f.payload.length <= OpalCastProtocol.MAX_MIC_FRAME_BYTES) {
                    listener.onMicFrame(sid, f.payload, f.timestampUs);
                }
                break;
            }
            case OpalCastProtocol.TYPE_CAMERA_CONFIG: {
                OpalCastProtocol.CameraConfigMsg msg = OpalCastProtocol.decodeCameraConfig(f.payload);
                if (listener != null) listener.onCameraConfig(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_CAMERA_FRAME: {
                if (listener != null && f.payload != null && f.payload.length > 0
                        && f.payload.length <= OpalCastProtocol.MAX_CAMERA_FRAME_BYTES) {
                    listener.onCameraFrame(sid, f.payload, f.timestampUs);
                }
                break;
            }
            case OpalCastProtocol.TYPE_NOTIFICATION_ACTION: {
                session.setCompanionEventsSubscribed(true);
                OpalCastProtocol.NotificationActionMsg msg = OpalCastProtocol.decodeNotificationAction(f.payload);
                if (listener != null) listener.onNotificationAction(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_MEDIA_ACTION: {
                session.setCompanionEventsSubscribed(true);
                OpalCastProtocol.MediaActionMsg msg = OpalCastProtocol.decodeMediaAction(f.payload);
                if (listener != null) listener.onMediaAction(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_FILE_OP_REQUEST: {
                OpalCastProtocol.FileOpRequestMsg msg = OpalCastProtocol.decodeFileOpRequest(f.payload);
                if (listener != null) listener.onFileOpRequest(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_APK_INSTALL_REQUEST: {
                OpalCastProtocol.ApkInstallRequestMsg msg = OpalCastProtocol.decodeApkInstallRequest(f.payload);
                if (listener != null) listener.onApkInstallRequest(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_CALL_ACTION: {
                session.setCompanionEventsSubscribed(true);
                OpalCastProtocol.CallActionMsg msg = OpalCastProtocol.decodeCallAction(f.payload);
                if (listener != null) listener.onCallAction(sid, msg);
                break;
            }
            default:
                break;
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

    /**
     * Detects the phone's best non-loopback LAN IPv4 address (strongly preferring wlan/ap/eth
     * RFC1918 addresses over cellular rmnet or VPN tun interfaces).
     */
    public static String detectLanIpv4Address() {
        return NetworkInterfaceClassifier.selectBestLanIp(
                NetworkInterfaceClassifier.snapshotSystemInterfaces());
    }

    /**
     * Detects the phone's active USB Tethering IPv4 interface ({@code ncm*}/{@code rndis*}/{@code usb*}).
     */
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
