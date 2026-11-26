package io.github.lengsukq.opaldesk.compositor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTrayNetworkPolicyTest {

    @Test
    fun wifiRadioOffNeverReportsWifiConnectedEvenWithStaleTransportOrEthernet() {
        val staleWifi = resolveTrayNetworkState(
            wifiRadioEnabled = false,
            hasWifiTransport = true,
            hasEthernetTransport = false,
        )
        assertFalse(staleWifi.wifiEnabled)
        assertFalse(staleWifi.wifiConnected)
        assertFalse(staleWifi.ethernetConnected)

        val ethernetOnly = resolveTrayNetworkState(
            wifiRadioEnabled = false,
            hasWifiTransport = false,
            hasEthernetTransport = true,
        )
        assertFalse(ethernetOnly.wifiEnabled)
        assertFalse(ethernetOnly.wifiConnected)
        assertTrue(ethernetOnly.ethernetConnected)
    }

    @Test
    fun wifiRadioOnDistinguishesDisconnectedVsConnected() {
        val disconnected = resolveTrayNetworkState(
            wifiRadioEnabled = true,
            hasWifiTransport = false,
            hasEthernetTransport = false,
        )
        assertTrue(disconnected.wifiEnabled)
        assertFalse(disconnected.wifiConnected)

        val connected = resolveTrayNetworkState(
            wifiRadioEnabled = true,
            hasWifiTransport = true,
            hasEthernetTransport = false,
        )
        assertTrue(connected.wifiEnabled)
        assertTrue(connected.wifiConnected)
    }

    @Test
    fun unknownWifiRadioFallsBackToWifiTransportOnly() {
        val noWifi = resolveTrayNetworkState(
            wifiRadioEnabled = null,
            hasWifiTransport = false,
            hasEthernetTransport = true,
        )
        assertFalse(noWifi.wifiEnabled)
        assertFalse(noWifi.wifiConnected)
        assertTrue(noWifi.ethernetConnected)

        val activeWifi = resolveTrayNetworkState(
            wifiRadioEnabled = null,
            hasWifiTransport = true,
            hasEthernetTransport = false,
        )
        assertTrue(activeWifi.wifiEnabled)
        assertTrue(activeWifi.wifiConnected)
    }

    @Test
    fun defaultSystemTrayInfoDoesNotAssumeWifiConnected() {
        val defaultTray = SystemTrayInfo()
        assertFalse(defaultTray.wifiEnabled)
        assertFalse(defaultTray.wifiConnected)
        assertFalse(defaultTray.ethernetConnected)
    }
}
