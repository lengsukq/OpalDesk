package io.github.lengsukq.opaldesk.daemon.stream;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.security.PairingApprovalCoordinator;
import io.github.lengsukq.opaldesk.daemon.security.SelfSignedCertGenerator;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Manages the ADB {@link LocalServerSocket} and on-demand Wi-Fi / USB-tethering
 * {@link SSLServerSocket} accept loops, TLS identity, and bind-scope reconciliation
 * for {@link RemoteStreamServer}.
 */
final class RemoteTlsAcceptor {
    interface ClientDispatch {
        void onUsbClientAccepted(LocalSocket client);
        void onWifiClientAccepted(SSLSocket client);
    }

    private final File certStoreFile;
    private final String deviceName;
    private final RemoteSessionRegistry sessions;
    private final PairingApprovalCoordinator pairingCoordinator;
    private final ClientDispatch dispatch;

    private final Object lifecycleLock = new Object();
    private volatile boolean stopped = false;

    private LocalServerSocket usbServerSocket;
    private Thread usbAcceptThread;

    private volatile boolean wifiEnabled = false;
    private volatile int wifiPort = RemoteStreamServer.DEFAULT_WIFI_PORT;
    private volatile int wifiBindScope = BridgeProtocol.TLS_BIND_SCOPE_ALL;
    private volatile String activeBindHost = "";
    private volatile SelfSignedCertGenerator.ServerIdentity tlsIdentity;
    private SSLServerSocket wifiServerSocket;
    private Thread wifiAcceptThread;

    RemoteTlsAcceptor(
            File certStoreFile,
            String deviceName,
            RemoteSessionRegistry sessions,
            PairingApprovalCoordinator pairingCoordinator,
            ClientDispatch dispatch) {
        this.certStoreFile = certStoreFile;
        this.deviceName = deviceName;
        this.sessions = sessions;
        this.pairingCoordinator = pairingCoordinator;
        this.dispatch = dispatch;
    }

    boolean isStopped() { return stopped; }
    boolean isWifiEnabled() { return wifiEnabled; }
    int getWifiPort() { return wifiPort; }
    int getWifiBindScope() { return wifiEnabled ? wifiBindScope : BridgeProtocol.TLS_BIND_SCOPE_ALL; }
    String getActiveBindHost() { return wifiEnabled ? activeBindHost : ""; }

    String getCertFingerprint() {
        SelfSignedCertGenerator.ServerIdentity id = tlsIdentity;
        return (id != null && id.certFingerprintSha256 != null) ? id.certFingerprintSha256 : "";
    }

    void startUsbListener() {
        synchronized (lifecycleLock) {
            if (stopped || usbServerSocket != null) return;
            try {
                usbServerSocket = new LocalServerSocket(RemoteStreamServer.USB_SOCKET_NAME);
                usbAcceptThread = new Thread(this::usbAcceptLoop, "ExtUsbStreamAccept");
                usbAcceptThread.setDaemon(true);
                usbAcceptThread.start();
                System.out.println("OPALDESK_USB_STREAM_LISTENING @" + RemoteStreamServer.USB_SOCKET_NAME);
            } catch (Throwable t) {
                System.err.println("OPALDESK_USB_STREAM_BIND_FAILED: " + t);
            }
        }
    }

    RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int requestedPort, int requestedBindScope) {
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
                return new RemoteStreamServer.WifiConfigStatus(
                        false, wifiPort, getCertFingerprint(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
            }
            if (stopped) {
                return new RemoteStreamServer.WifiConfigStatus(
                        false, wifiPort, getCertFingerprint(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
            }

            String targetBindHost = "";
            if (effectiveScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) {
                NetworkInterfaceClassifier.Endpoint usbEp = RemoteStreamServer.detectUsbTetherEndpoint();
                if (!usbEp.isPresent()) {
                    System.err.println("OPALDESK_USB_TETHER_BIND_ABORTED reason=no_active_usb_tether_interface");
                    stopWifiListenerLocked();
                    sessions.disconnectMatching(
                            s -> DisplaySink.isTlsNetworkSink(s.sinkType),
                            "No active USB tethering interface");
                    return new RemoteStreamServer.WifiConfigStatus(
                            false, wifiPort, getCertFingerprint(), BridgeProtocol.TLS_BIND_SCOPE_ALL);
                }
                targetBindHost = usbEp.ipv4;
            }

            if (wifiEnabled && wifiServerSocket != null && !wifiServerSocket.isClosed()
                    && wifiServerSocket.getLocalPort() == wifiPort
                    && wifiBindScope == effectiveScope
                    && targetBindHost.equals(activeBindHost)) {
                return new RemoteStreamServer.WifiConfigStatus(true, wifiPort, getCertFingerprint(), wifiBindScope);
            }

            stopWifiListenerLocked();
            if (effectiveScope == BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) {
                sessions.disconnectMatching(
                        s -> s.sinkType == DisplaySink.SINK_REMOTE_WIFI,
                        "Switched to USB tethering only mode");
            }
            try {
                if (tlsIdentity == null) {
                    String lanIp = RemoteStreamServer.detectLanIpv4Address();
                    List<String> extraSans = (lanIp != null && !lanIp.isEmpty())
                            ? Collections.singletonList(lanIp) : null;
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
            return new RemoteStreamServer.WifiConfigStatus(wifiEnabled, wifiPort, getCertFingerprint(), getWifiBindScope());
        }
    }

    void reconcileUsbTetherBindIfNeeded() {
        if (!wifiEnabled || wifiBindScope != BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) return;
        synchronized (lifecycleLock) {
            if (!wifiEnabled || wifiBindScope != BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY) return;
            NetworkInterfaceClassifier.Endpoint cur = RemoteStreamServer.detectUsbTetherEndpoint();
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

    void shutdown() {
        synchronized (lifecycleLock) {
            stopped = true;
            stopWifiListenerLocked();
            if (usbServerSocket != null) {
                ClientSession.closeTransportQuietly(usbServerSocket);
                usbServerSocket = null;
            }
        }
    }

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
                try { cred = client.getPeerCredentials(); } catch (IOException e) {
                    System.err.println("OPALDESK_USB_CRED_ERR: " + e.getMessage());
                }
                int peerUid = (cred != null) ? cred.getUid() : -1;
                if (peerUid != 0 && peerUid != 2000) {
                    System.err.println("OPALDESK_USB_STREAM_REJECT_UID uid=" + peerUid);
                    ClientSession.closeTransportQuietly(client);
                    continue;
                }
                Thread handler = new Thread(() -> dispatch.onUsbClientAccepted(client), "ExtUsbClient");
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
                Thread handler = new Thread(() -> dispatch.onWifiClientAccepted(ssl), "ExtWifiClient");
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

    static void applySecureTlsProtocols(String[] supported, Consumer<String[]> setter) {
        List<String> list = new ArrayList<>();
        if (supported != null) {
            for (String p : supported) {
                if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) list.add(p);
            }
        }
        if (!list.isEmpty()) setter.accept(list.toArray(new String[0]));
    }
}
