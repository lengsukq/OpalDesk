package io.github.lengsukq.opaldesk.daemon.stream;

import android.net.LocalSocket;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.security.PairingApprovalCoordinator;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;

import javax.net.ssl.SSLSocket;

/**
 * Handles USB/ADB and Wi-Fi TLS transport negotiation (raw OPD1 or WebSocket HTTP upgrade),
 * HELLO validation, trust-store/pairing consent handshake, and authenticated ADB->Wi-Fi TLS upgrade.
 */
final class RemoteAuthHandshakeHandler {
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int SOCKET_IO_BUFFER_BYTES = 131_072;
    private static final int USB_SOCKET_SEND_BUF_BYTES = 512 * 1024;
    private static final int WIFI_SOCKET_SEND_BUF_BYTES = 512 * 1024;
    private static final int REALTIME_MEDIA_TRAFFIC_CLASS = 0xB8;

    interface TimeoutClearer {
        void clear() throws IOException;
    }

    interface AuthenticatedSessionRunner {
        void run(
                int sinkType,
                boolean isAdbLocalSocket,
                boolean permanentTrust,
                String clientId,
                String clientName,
                OpalCastProtocol.HelloMsg hello,
                Closeable transport,
                OpalCastFrameTransport frameTransport);
    }

    private final String deviceName;
    private final TrustStoreManager trustStore;
    private final PairingApprovalCoordinator pairingCoordinator;
    private final RemoteSessionRegistry sessions;
    private final RemoteTlsAcceptor tlsAcceptor;
    private final RemoteStreamServer.SessionListener listener;
    private final AuthenticatedSessionRunner sessionRunner;

    RemoteAuthHandshakeHandler(
            String deviceName,
            TrustStoreManager trustStore,
            PairingApprovalCoordinator pairingCoordinator,
            RemoteSessionRegistry sessions,
            RemoteTlsAcceptor tlsAcceptor,
            RemoteStreamServer.SessionListener listener,
            AuthenticatedSessionRunner sessionRunner) {
        this.deviceName = deviceName;
        this.trustStore = trustStore;
        this.pairingCoordinator = pairingCoordinator;
        this.sessions = sessions;
        this.tlsAcceptor = tlsAcceptor;
        this.listener = listener;
        this.sessionRunner = sessionRunner;
    }

    void handleUsbClient(LocalSocket sock) {
        try {
            try { sock.setSendBufferSize(USB_SOCKET_SEND_BUF_BYTES); } catch (IOException ignored) {}
            try { sock.setReceiveBufferSize(SOCKET_IO_BUFFER_BYTES); } catch (IOException ignored) {}
            sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), SOCKET_IO_BUFFER_BYTES);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), SOCKET_IO_BUFFER_BYTES);
            OpalCastFrameTransport frameTransport = negotiateFrameTransport(in, out, false, tlsAcceptor.getWifiPort());
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

    void handleWifiClient(SSLSocket sock) {
        try {
            boolean isUsbTetherLink = NetworkInterfaceClassifier.isUsbTetheringLocalAddress(sock.getLocalAddress());
            if (tlsAcceptor.getWifiBindScope() == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY && !isUsbTetherLink) {
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
            RemoteTlsAcceptor.applySecureTlsProtocols(sock.getSupportedProtocols(), sock::setEnabledProtocols);
            sock.startHandshake();

            String peerIp = (sock.getInetAddress() != null) ? sock.getInetAddress().getHostAddress() : "unknown";
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), SOCKET_IO_BUFFER_BYTES);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), SOCKET_IO_BUFFER_BYTES);
            OpalCastFrameTransport frameTransport = negotiateFrameTransport(in, out, true, tlsAcceptor.getWifiPort());
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
                        ? trustStore.issueSingleUseHandoverToken(clientId, peerIp) : "";
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

    void handleWifiUpgradeRequest(ClientSession session) {
        long ts = SystemClock.elapsedRealtimeNanos() / 1000L;
        if (!session.isAdbLocalSocket) {
            System.err.println("OPALDESK_WIFI_UPGRADE_DENIED non-ADB sessionId=" + session.sessionId);
            OpalCastProtocol.WifiUpgradeReadyMsg denied = new OpalCastProtocol.WifiUpgradeReadyMsg(
                    false, "", 0, "", "", "REQUEST_WIFI_UPGRADE is only allowed over authenticated ADB/USB sessions");
            session.enqueue(OpalCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    OpalCastProtocol.encodeWifiUpgradeReady(denied));
            return;
        }
        RemoteStreamServer.WifiConfigStatus st = tlsAcceptor.configureWifi(
                true, tlsAcceptor.getWifiPort(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
        if (!st.enabled) {
            OpalCastProtocol.WifiUpgradeReadyMsg err = new OpalCastProtocol.WifiUpgradeReadyMsg(
                    false, "", st.port, st.certFingerprint, "", "Failed to start Wi-Fi TLS listener");
            session.enqueue(OpalCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    OpalCastProtocol.encodeWifiUpgradeReady(err));
            return;
        }
        String lanIp = RemoteStreamServer.detectLanIpv4Address();
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
        if (listener != null) listener.onWifiUpgradeEnabled();
    }

    private OpalCastFrameTransport negotiateFrameTransport(
            BufferedInputStream in, OutputStream out, boolean isTls, int listenPort) throws IOException {
        in.mark(8);
        byte[] prefix = new byte[4];
        int got = 0;
        while (got < 4) {
            int n = in.read(prefix, got, 4 - got);
            if (n < 0) return null;
            got += n;
        }
        in.reset();
        if (WebSocketHttpHandler.isHttpRequestPrefix(prefix)) {
            String fullFp = tlsAcceptor.getCertFingerprint();
            String shortFp = (fullFp.length() >= 12) ? fullFp.substring(0, 12) : fullFp;
            WebSocketHttpHandler.ServerMeta meta =
                    new WebSocketHttpHandler.ServerMeta(deviceName, shortFp, listenPort, isTls);
            return WebSocketHttpHandler.handle(in, out, meta);
        }
        return OpalCastFrameTransport.raw(in, out);
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
            int sinkType, boolean isAdbLocalSocket, boolean permanentTrust,
            String clientId, String clientName, String issuedToken, String authMsg,
            boolean permanentTokenFlag, OpalCastProtocol.HelloMsg hello,
            Closeable sock, TimeoutClearer clearTimeout, OpalCastFrameTransport frameTransport) throws IOException {
        if (!sessions.hasCapacityForSession(hello, clientId)) {
            String errMsg = hello.isCompanion()
                    ? ("Maximum " + RemoteStreamServer.MAX_COMPANION_SESSIONS + " companion sessions active")
                    : ("Maximum " + RemoteStreamServer.MAX_STANDALONE_SESSIONS + " standalone app sessions active");
            sendAuthFailureAndClose(frameTransport, sock, errMsg);
            return;
        }
        OpalCastProtocol.AuthResultMsg okMsg = new OpalCastProtocol.AuthResultMsg(
                true, issuedToken, authMsg, deviceName, tlsAcceptor.getCertFingerprint(), permanentTokenFlag);
        writeAuthResultImmediate(frameTransport, okMsg);
        clearTimeout.clear();
        sessionRunner.run(sinkType, isAdbLocalSocket, permanentTrust, clientId, clientName, hello, sock, frameTransport);
    }

    private void sendAuthFailureAndClose(OpalCastFrameTransport frameTransport, Closeable sock, String msg) {
        try {
            OpalCastProtocol.AuthResultMsg fail = new OpalCastProtocol.AuthResultMsg(
                    false, "", msg, deviceName, tlsAcceptor.getCertFingerprint());
            writeAuthResultImmediate(frameTransport, fail);
        } catch (IOException e) {
            System.err.println("OPALDESK_AUTH_FAIL_SEND_WARN: " + e.getMessage());
        } finally {
            ClientSession.closeTransportQuietly(sock);
        }
    }

    private static void writeAuthResultImmediate(
            OpalCastFrameTransport frameTransport, OpalCastProtocol.AuthResultMsg msg) throws IOException {
        byte[] payload = OpalCastProtocol.encodeAuthResult(msg);
        frameTransport.writeFrame(OpalCastProtocol.TYPE_AUTH_RESULT, 0,
                RemoteStreamServer.nowTimestampUs(), payload, 0, payload.length);
        frameTransport.flush();
    }
}
