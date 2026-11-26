import Foundation
import Testing
@testable import OpalDeskCore

@Suite("USB Tethering Discovery (Beta)")
struct UsbTetherScannerTests {
    @Test("Parses macOS netstat scoped default routes and ignores VPN or loopback")
    func parsesNetstatDefaultRoutes() {
        let sample = """
        Routing tables

        Internet:
        Destination        Gateway            Flags               Netif Expire
        default            192.168.31.1       UGScg                 en0
        default            192.168.148.10     UGScIg                en7
        1                  198.18.0.1         UGSc             utun1024
        default            10.0.111.1         UGScIg              utun0
        default            8.8.8.8            UGScIg                en8
        127                127.0.0.1          UCS                   lo0
        """

        let parsed = UsbTetherRouteParser.parseDefaultGateways(fromNetstatOutput: sample)
        #expect(parsed.count == 2)
        #expect(parsed[0].interfaceName == "en0")
        #expect(parsed[0].gatewayIp == "192.168.31.1")
        #expect(parsed[1].interfaceName == "en7")
        #expect(parsed[1].gatewayIp == "192.168.148.10")
    }

    @Test("Classifies USB CDC-NCM/RNDIS interfaces while excluding Wi-Fi, Thunderbolt, and iOS")
    func classifiesInterfacesCorrectly() {
        #expect(UsbTetherRouteParser.isLikelyUsbTetheringInterface(
            bsdName: "en7",
            displayName: "USB 10/100/1000 LAN",
            interfaceType: "Ethernet"
        ))
        #expect(UsbTetherRouteParser.isLikelyUsbTetheringInterface(
            bsdName: "en8",
            displayName: "Android CDC NCM",
            interfaceType: "Ethernet"
        ))
        #expect(UsbTetherRouteParser.isLikelyUsbTetheringInterface(
            bsdName: "en9",
            displayName: "RNDIS/Ethernet Gadget",
            interfaceType: "Ethernet"
        ))

        #expect(!UsbTetherRouteParser.isLikelyUsbTetheringInterface(
            bsdName: "en0",
            displayName: "Wi-Fi",
            interfaceType: "IEEE80211"
        ))
        #expect(!UsbTetherRouteParser.isLikelyUsbTetheringInterface(
            bsdName: "en1",
            displayName: "Thunderbolt 1",
            interfaceType: "Ethernet"
        ))
        #expect(!UsbTetherRouteParser.isLikelyUsbTetheringInterface(
            bsdName: "en6",
            displayName: "iPhone USB",
            interfaceType: "Ethernet"
        ))
        #expect(!UsbTetherRouteParser.isLikelyUsbTetheringInterface(
            bsdName: "utun0",
            displayName: "WireGuard USB",
            interfaceType: nil
        ))
    }

    @Test("Parses valid OpalDesk /health response and rejects non-cast responses")
    func parsesCastHealthHTTPResponse() {
        let valid = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true,\"role\":\"cast\",\"deviceName\":\"Xiaomi 14 Pro\",\"certShortFp\":\"aabbccddeeff\"}"
        #expect(UsbTetherRouteParser.parseCastHealthHTTPResponse(valid) == "Xiaomi 14 Pro")

        let wrongRole = "HTTP/1.1 200 OK\r\n\r\n{\"ok\":true,\"role\":\"activate\",\"deviceName\":\"Phone\"}"
        #expect(UsbTetherRouteParser.parseCastHealthHTTPResponse(wrongRole) == nil)

        let notFound = "HTTP/1.1 404 Not Found\r\n\r\nNot Found"
        #expect(UsbTetherRouteParser.parseCastHealthHTTPResponse(notFound) == nil)
    }
}
