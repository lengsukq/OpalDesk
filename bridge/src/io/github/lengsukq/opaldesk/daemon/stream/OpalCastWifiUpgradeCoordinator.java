package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.HotspotController;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Coordinates Wi-Fi TLS listener configuration, BLE Instant Hotspot HMAC trigger,
 * pairing/trust-store queries, and remote status snapshot construction.
 */
final class OpalCastWifiUpgradeCoordinator {
    private final DaemonConfig config;
    private final TrustStoreManager trustStore;
    private final RemoteStreamServer server;
    private final HotspotController hotspotController;
    private final Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions;
    private final IntSupplier desktopSessionIdSupplier;
    private final IntSupplier activeDesktopDisplayIdSupplier;
    private final Runnable notifyRemoteStateChanged;

    OpalCastWifiUpgradeCoordinator(
            DaemonConfig config,
            TrustStoreManager trustStore,
            RemoteStreamServer server,
            HotspotController hotspotController,
            Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions,
            IntSupplier desktopSessionIdSupplier,
            IntSupplier activeDesktopDisplayIdSupplier,
            Runnable notifyRemoteStateChanged) {
        this.config = config;
        this.trustStore = trustStore;
        this.server = server;
        this.hotspotController = hotspotController;
        this.sessions = sessions;
        this.desktopSessionIdSupplier = desktopSessionIdSupplier;
        this.activeDesktopDisplayIdSupplier = activeDesktopDisplayIdSupplier;
        this.notifyRemoteStateChanged = notifyRemoteStateChanged;
    }

    String triggerHotspotForBleClient(String clientId, String nonceHex, String hmacHex) {
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
            notifyRemoteStateChanged.run();
        }
        HotspotController.HotspotResult res = hotspotController.triggerHotspot(targetPort);
        System.out.println("OPALDESK_HOTSPOT_TRIGGER clientId="
                + TrustStoreManager.sanitizeClientId(clientId)
                + " status=" + res.status
                + " gw=" + res.gatewayIp
                + " port=" + res.port);
        return res.toJson();
    }

    OpalCastCompositorBridge.RemoteStatusSnapshot getStatusSnapshot() {
        server.reconcileUsbTetherBindIfNeeded();
        int desktopSessionId = desktopSessionIdSupplier.getAsInt();
        OpalCastCompositorBridge.ActiveRemoteSession main =
                (desktopSessionId > 0) ? sessions.get(desktopSessionId) : null;
        if (main == null) {
            for (OpalCastCompositorBridge.ActiveRemoteSession candidate : sessions.values()) {
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
        return new OpalCastCompositorBridge.RemoteStatusSnapshot(
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
                activeDesktopDisplayIdSupplier.getAsInt(),
                usbEp.ipv4,
                usbEp.interfaceName,
                server.getWifiBindScope());
    }

    RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int port, int bindScope) {
        return server.configureWifi(enable, port > 0 ? port : RemoteStreamServer.DEFAULT_WIFI_PORT, bindScope);
    }

    void onWifiUpgradeEnabled() {
        System.out.println("OPALDESK_REMOTE_WIFI_UPGRADE_ENABLED port=" + server.getWifiPort());
    }
}
