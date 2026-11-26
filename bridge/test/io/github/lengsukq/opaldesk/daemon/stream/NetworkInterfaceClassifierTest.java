package io.github.lengsukq.opaldesk.daemon.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NetworkInterfaceClassifierTest {
    @Test
    public void keepsWifiPriorityForLanIpWhileExtractingUsbTetherEndpoint() {
        List<NetworkInterfaceClassifier.InterfaceSnapshot> snaps = Arrays.asList(
                new NetworkInterfaceClassifier.InterfaceSnapshot("rmnet_data0", "10.12.34.56", true),
                new NetworkInterfaceClassifier.InterfaceSnapshot("tun0", "198.18.0.1", false),
                new NetworkInterfaceClassifier.InterfaceSnapshot("ncm0", "192.168.148.10", true),
                new NetworkInterfaceClassifier.InterfaceSnapshot("wlan0", "192.168.31.88", true)
        );

        assertEquals("192.168.31.88", NetworkInterfaceClassifier.selectBestLanIp(snaps));

        NetworkInterfaceClassifier.Endpoint usb = NetworkInterfaceClassifier.selectUsbTetherEndpoint(snaps);
        assertTrue(usb.isPresent());
        assertEquals("ncm0", usb.interfaceName);
        assertEquals("192.168.148.10", usb.ipv4);
    }

    @Test
    public void recognizesLegacyRndisAndUsbInterfacesAndIgnoresVpnOrCellular() {
        List<NetworkInterfaceClassifier.InterfaceSnapshot> snaps = Arrays.asList(
                new NetworkInterfaceClassifier.InterfaceSnapshot("tun0", "10.0.111.100", true),
                new NetworkInterfaceClassifier.InterfaceSnapshot("rmnet0", "10.1.2.3", true),
                new NetworkInterfaceClassifier.InterfaceSnapshot("rndis0", "192.168.42.129", true)
        );

        assertEquals("192.168.42.129", NetworkInterfaceClassifier.selectBestLanIp(snaps));
        NetworkInterfaceClassifier.Endpoint usb = NetworkInterfaceClassifier.selectUsbTetherEndpoint(snaps);
        assertTrue(usb.isPresent());
        assertEquals("rndis0", usb.interfaceName);
        assertEquals("192.168.42.129", usb.ipv4);
    }

    @Test
    public void returnsEmptyUsbEndpointWhenOnlyWifiAndVpnExist() {
        List<NetworkInterfaceClassifier.InterfaceSnapshot> snaps = Arrays.asList(
                new NetworkInterfaceClassifier.InterfaceSnapshot("wlan0", "192.168.1.25", true),
                new NetworkInterfaceClassifier.InterfaceSnapshot("eth0", "192.168.1.30", true),
                new NetworkInterfaceClassifier.InterfaceSnapshot("tun0", "10.8.0.2", true)
        );

        assertFalse(NetworkInterfaceClassifier.selectUsbTetherEndpoint(snaps).isPresent());
        assertFalse(NetworkInterfaceClassifier.selectUsbTetherEndpoint(Collections.emptyList()).isPresent());
    }

    @Test
    public void classifiesTlsNetworkSinksAndInterfaceNamesCorrectly() {
        assertTrue(NetworkInterfaceClassifier.isUsbTetheringInterface("ncm0"));
        assertTrue(NetworkInterfaceClassifier.isUsbTetheringInterface("RNDIS0"));
        assertTrue(NetworkInterfaceClassifier.isUsbTetheringInterface("usb0"));
        assertFalse(NetworkInterfaceClassifier.isUsbTetheringInterface("wlan0"));
        assertFalse(NetworkInterfaceClassifier.isUsbTetheringInterface("eth0"));
        assertFalse(NetworkInterfaceClassifier.isUsbTetheringInterface("tun0"));

        assertTrue(DisplaySink.isTlsNetworkSink(DisplaySink.SINK_REMOTE_WIFI));
        assertTrue(DisplaySink.isTlsNetworkSink(DisplaySink.SINK_REMOTE_USB_TETHER));
        assertFalse(DisplaySink.isTlsNetworkSink(DisplaySink.SINK_REMOTE_USB));
        assertFalse(DisplaySink.isTlsNetworkSink(DisplaySink.SINK_REMOTE_WIFI_ADB));
    }

    @Test
    public void usbTetherOnlyBindFailsClosedWhenNoUsbTetherInterfaceIsActive() throws Exception {
        if (RemoteStreamServer.detectUsbTetherEndpoint().isPresent()) {
            // Host machine happens to have an active ncm/rndis/usb interface; skip negative check.
            return;
        }
        File tmpDir = Files.createTempDirectory("opaldesk_usb_tether_test").toFile();
        File certFile = new File(tmpDir, "tls.p12");
        File trustFile = new File(tmpDir, "trust.conf");
        RemoteStreamServer server = new RemoteStreamServer(null, new TrustStoreManager(trustFile), certFile, "TestPhone");
        try {
            RemoteStreamServer.WifiConfigStatus st =
                    server.configureWifi(true, 0, BridgeProtocol.TLS_BIND_SCOPE_USB_TETHER_ONLY);
            assertFalse(st.enabled);
            assertFalse(server.isWifiEnabled());
            assertEquals(BridgeProtocol.TLS_BIND_SCOPE_ALL, st.bindScope);
        } finally {
            server.shutdown();
            certFile.delete();
            trustFile.delete();
            tmpDir.delete();
        }
    }
}
