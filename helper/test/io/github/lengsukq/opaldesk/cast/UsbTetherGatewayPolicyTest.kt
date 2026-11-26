package io.github.lengsukq.opaldesk.cast

import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTetherGatewayPolicyTest {
    @Test
    fun parsesValidOpalCastHealthResponseAndRejectsForeignServices() {
        val valid = """{"ok":true,"role":"cast","deviceName":"Xiaomi 14 Pro","protocolVersion":1,"certShortFp":"a1b2c3d4e5f6","tls":true,"wsPath":"/ws"}"""
        val parsed = UsbTetherGatewayPolicy.parseCastHealthJson(valid)
        assertNotNull(parsed)
        assertEquals("Xiaomi 14 Pro", parsed!!.deviceName)
        assertEquals("a1b2c3d4e5f6", parsed.certShortFp)
        assertEquals(1, parsed.protocolVersion)

        assertNull(UsbTetherGatewayPolicy.parseCastHealthJson("""{"ok":true,"role":"activate","deviceName":"Phone"}"""))
        assertNull(UsbTetherGatewayPolicy.parseCastHealthJson("""{"ok":false,"role":"cast"}"""))
        assertNull(UsbTetherGatewayPolicy.parseCastHealthJson("<html>router</html>"))
    }

    @Test
    fun validatesUsbTetherRoutesAndRejectsWifiVpnOrPublicIps() {
        assertTrue(UsbTetherGatewayPolicy.isLikelyUsbTetherRoute("eth0", "192.168.42.129"))
        assertTrue(UsbTetherGatewayPolicy.isLikelyUsbTetherRoute("ncm0", "172.16.12.1"))
        assertTrue(UsbTetherGatewayPolicy.isLikelyUsbTetherRoute("usb0", "10.0.0.1"))

        assertFalse(UsbTetherGatewayPolicy.isLikelyUsbTetherRoute("wlan0", "192.168.31.1"))
        assertFalse(UsbTetherGatewayPolicy.isLikelyUsbTetherRoute("tun0", "10.8.0.1"))
        assertFalse(UsbTetherGatewayPolicy.isLikelyUsbTetherRoute("rmnet_data0", "10.1.1.1"))
        assertFalse(UsbTetherGatewayPolicy.isLikelyUsbTetherRoute("eth0", "8.8.8.8"))
        assertFalse(UsbTetherGatewayPolicy.isLikelyUsbTetherRoute("eth0", "169.254.1.1"))
    }

    @Test
    fun resolvesSenderShareStateCleanlyAcrossFeatureAndBindScopes() {
        val idleUsb = RemoteStreamStatus(
            wifiEnabled = false,
            usbTetherIp = "192.168.148.10",
            usbTetherInterface = "ncm0",
        )
        assertEquals(
            UsbTetherGatewayPolicy.SenderShareState.FEATURE_DISABLED,
            UsbTetherGatewayPolicy.resolveSenderShareState(idleUsb, featureEnabled = false),
        )
        assertEquals(
            UsbTetherGatewayPolicy.SenderShareState.USB_READY_IDLE,
            UsbTetherGatewayPolicy.resolveSenderShareState(idleUsb, featureEnabled = true),
        )

        val usbOnly = idleUsb.copy(
            wifiEnabled = true,
            tlsBindScope = BridgeProtocol.TlsBindScope.USB_TETHER_ONLY,
        )
        assertEquals(
            UsbTetherGatewayPolicy.SenderShareState.USB_ONLY_ACTIVE,
            UsbTetherGatewayPolicy.resolveSenderShareState(usbOnly, featureEnabled = true),
        )
        assertTrue(usbOnly.isUsbTetherOnlyMode)
        assertFalse(usbOnly.isLanShareActive)

        val reusingLan = idleUsb.copy(
            wifiEnabled = true,
            tlsBindScope = BridgeProtocol.TlsBindScope.ALL,
        )
        assertEquals(
            UsbTetherGatewayPolicy.SenderShareState.REUSING_LAN_SHARE,
            UsbTetherGatewayPolicy.resolveSenderShareState(reusingLan, featureEnabled = true),
        )

        val noUsb = RemoteStreamStatus(wifiEnabled = false, usbTetherIp = "")
        assertEquals(
            UsbTetherGatewayPolicy.SenderShareState.NO_USB_INTERFACE,
            UsbTetherGatewayPolicy.resolveSenderShareState(noUsb, featureEnabled = true),
        )
    }

    @Test
    fun formatsUsbTetherChannelLabelCorrectly() {
        assertEquals("USB 网络共享直连", RemoteStreamStatus(sinkType = 6).channelLabel)
        assertEquals("Wi-Fi TLS 直连", RemoteStreamStatus(sinkType = 3).channelLabel)
    }
}
