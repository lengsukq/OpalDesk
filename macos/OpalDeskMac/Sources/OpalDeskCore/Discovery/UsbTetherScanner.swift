import Foundation
import Network
import Security
import SystemConfiguration
import os

/// Candidate macOS USB Tethering (CDC-NCM / RNDIS) network gateway discovered from routing table.
public struct UsbTetherGatewayCandidate: Sendable, Equatable, Hashable {
    public let interfaceName: String
    public let interfaceLabel: String
    public let gatewayIp: String

    public init(interfaceName: String, interfaceLabel: String, gatewayIp: String) {
        self.interfaceName = interfaceName
        self.interfaceLabel = interfaceLabel
        self.gatewayIp = gatewayIp
    }
}

/// Verified Android OpalDesk host reachable over a USB Tethering gateway (`:19878` `/health` + leaf cert).
public struct DiscoveredUsbTetherDevice: Sendable, Equatable, Identifiable, Hashable {
    public let id: String
    public let displayName: String
    public let host: String
    public let port: UInt16
    public let interfaceName: String
    public let interfaceLabel: String
    public let certFingerprint: String

    public init(
        displayName: String,
        host: String,
        port: UInt16,
        interfaceName: String,
        interfaceLabel: String,
        certFingerprint: String
    ) {
        self.id = "usb-tether:\(host):\(port)"
        self.displayName = displayName.isEmpty ? host : displayName
        self.host = host
        self.port = port
        self.interfaceName = interfaceName
        self.interfaceLabel = interfaceLabel
        self.certFingerprint = FingerprintUtil.normalize(certFingerprint)
    }

    public var shortFingerprint: String {
        FingerprintUtil.formatShort(certFingerprint)
    }
}

/// Pure, unit-testable parsing & classification rules for macOS USB Tethering discovery (Beta).
public enum UsbTetherRouteParser {
    private static let ignoredInterfacePrefixes: [String] = [
        "lo", "utun", "awdl", "llw", "bridge", "ap", "anpi", "gif", "stf", "ipsec", "ppp"
    ]

    private static let excludedDisplayTokens: [String] = [
        "wi-fi", "wifi", "airport", "thunderbolt", "bluetooth",
        "iphone", "ipad", "apple mobile", "firewire", "bridge",
        "vpn", "wireguard", "tailscale", "zerotier"
    ]

    private static let usbTetherPositiveTokens: [String] = [
        "usb", "ncm", "rndis", "cdc", "android", "tether", "gadget"
    ]

    public static func isPrivateIPv4(_ raw: String) -> Bool {
        let parts = raw.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ".")
        guard parts.count == 4 else { return false }
        var octets: [Int] = []
        octets.reserveCapacity(4)
        for p in parts {
            guard let v = Int(p), (0...255).contains(v) else { return false }
            octets.append(v)
        }
        if octets[0] == 10 { return true }
        if octets[0] == 172 && (16...31).contains(octets[1]) { return true }
        if octets[0] == 192 && octets[1] == 168 { return true }
        return false
    }

    /// Parses `netstat -rn -f inet` default gateway routes per BSD interface (`UGScg`, `UGScIg`, etc.).
    public static func parseDefaultGateways(fromNetstatOutput output: String) -> [(interfaceName: String, gatewayIp: String)] {
        var results: [(interfaceName: String, gatewayIp: String)] = []
        var seen: Set<String> = []

        for rawLine in output.split(whereSeparator: \.isNewline) {
            let tokens = rawLine.split(whereSeparator: \.isWhitespace).map(String.init)
            guard tokens.count >= 4, tokens[0] == "default" else { continue }
            let gateway = tokens[1]
            let flags = tokens[2]
            let netif = tokens[3]

            guard flags.contains("G"), isPrivateIPv4(gateway) else { continue }
            let lowerIface = netif.lowercased()
            if ignoredInterfacePrefixes.contains(where: { lowerIface.hasPrefix($0) }) {
                continue
            }
            let dedupKey = "\(netif)|\(gateway)"
            if seen.insert(dedupKey).inserted {
                results.append((interfaceName: netif, gatewayIp: gateway))
            }
        }
        return results
    }

    public static func isLikelyUsbTetheringInterface(
        bsdName: String,
        displayName: String,
        interfaceType: String?
    ) -> Bool {
        let lowerIface = bsdName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !lowerIface.isEmpty else { return false }
        if ignoredInterfacePrefixes.contains(where: { lowerIface.hasPrefix($0) }) {
            return false
        }
        if let type = interfaceType?.lowercased(),
           type.contains("ieee80211") || type.contains("bluetooth") || type.contains("firewire")
        {
            return false
        }
        let combined = "\(lowerIface) \(displayName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())"
        if excludedDisplayTokens.contains(where: { combined.contains($0) }) {
            return false
        }
        return usbTetherPositiveTokens.contains(where: { combined.contains($0) })
    }

    public static func parseCastHealthHTTPResponse(_ rawHTTP: String) -> String? {
        let separator: String
        if rawHTTP.contains("\r\n\r\n") {
            separator = "\r\n\r\n"
        } else if rawHTTP.contains("\n\n") {
            separator = "\n\n"
        } else {
            return nil
        }
        guard let splitRange = rawHTTP.range(of: separator) else { return nil }
        let headerBlock = String(rawHTTP[..<splitRange.lowerBound])
        let body = String(rawHTTP[splitRange.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)

        guard let statusLine = headerBlock.split(whereSeparator: \.isNewline).first,
              statusLine.contains(" 200")
        else {
            return nil
        }
        guard let data = body.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (json["ok"] as? Bool) == true,
              (json["role"] as? String) == "cast"
        else {
            return nil
        }
        let name = (json["deviceName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? "Android 设备" : name
    }
}

/// Beta opt-in scanner discovering Android OpalDesk hosts over macOS USB Tethering (CDC-NCM / RNDIS).
public enum UsbTetherScanner {
    public static let defaultProbeTimeoutSeconds: Double = 1.5

    public static func scan(
        port: UInt16 = OpalCastProtocol.defaultWifiPort,
        timeoutSeconds: Double = defaultProbeTimeoutSeconds
    ) async -> [DiscoveredUsbTetherDevice] {
        let candidates = await discoverGatewayCandidates()
        guard !candidates.isEmpty else { return [] }

        return await withTaskGroup(of: DiscoveredUsbTetherDevice?.self) { group in
            for candidate in candidates {
                group.addTask {
                    guard let probed = await probeCastHealth(
                        host: candidate.gatewayIp,
                        port: port,
                        timeoutSeconds: timeoutSeconds
                    ) else {
                        return nil
                    }
                    return DiscoveredUsbTetherDevice(
                        displayName: probed.deviceName,
                        host: candidate.gatewayIp,
                        port: port,
                        interfaceName: candidate.interfaceName,
                        interfaceLabel: candidate.interfaceLabel,
                        certFingerprint: probed.fingerprint
                    )
                }
            }

            var found: [DiscoveredUsbTetherDevice] = []
            for await item in group {
                if let device = item {
                    found.append(device)
                }
            }
            return found.sorted {
                if $0.displayName != $1.displayName { return $0.displayName < $1.displayName }
                return $0.host < $1.host
            }
        }
    }

    private static func discoverGatewayCandidates() async -> [UsbTetherGatewayCandidate] {
        let ifaceMeta = readSystemInterfaceMetadata()
        guard let netstatOutput = await runNetstatIPv4Routes() else { return [] }
        let routes = UsbTetherRouteParser.parseDefaultGateways(fromNetstatOutput: netstatOutput)

        var seenGateways: Set<String> = []
        var candidates: [UsbTetherGatewayCandidate] = []
        for route in routes {
            let meta = ifaceMeta[route.interfaceName]
            let displayName = meta?.displayName ?? ""
            let interfaceType = meta?.interfaceType
            guard UsbTetherRouteParser.isLikelyUsbTetheringInterface(
                bsdName: route.interfaceName,
                displayName: displayName,
                interfaceType: interfaceType
            ) else {
                continue
            }
            if seenGateways.insert(route.gatewayIp).inserted {
                candidates.append(
                    UsbTetherGatewayCandidate(
                        interfaceName: route.interfaceName,
                        interfaceLabel: displayName.isEmpty ? route.interfaceName : displayName,
                        gatewayIp: route.gatewayIp
                    )
                )
            }
        }
        return candidates
    }

    private static func readSystemInterfaceMetadata() -> [String: (displayName: String, interfaceType: String?)] {
        var map: [String: (displayName: String, interfaceType: String?)] = [:]
        guard let all = SCNetworkInterfaceCopyAll() as? [SCNetworkInterface] else {
            return map
        }
        for iface in all {
            guard let bsdName = SCNetworkInterfaceGetBSDName(iface) as String? else { continue }
            let displayName = (SCNetworkInterfaceGetLocalizedDisplayName(iface) as String?) ?? ""
            let type = SCNetworkInterfaceGetInterfaceType(iface) as String?
            map[bsdName] = (displayName: displayName, interfaceType: type)
        }
        return map
    }

    private static func runNetstatIPv4Routes() async -> String? {
        await Task.detached(priority: .utility) {
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: "/usr/sbin/netstat")
            proc.arguments = ["-rn", "-f", "inet"]
            let pipe = Pipe()
            proc.standardOutput = pipe
            proc.standardError = FileHandle.nullDevice
            do {
                try proc.run()
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                proc.waitUntilExit()
                guard proc.terminationStatus == 0 else { return nil }
                return String(data: data, encoding: .utf8)
            } catch {
                return nil
            }
        }.value
    }

    /// Performs a read-only TLS `GET /health` probe to verify an OpalDesk cast host and extract its
    /// leaf X.509 SHA-256 fingerprint. Formal cast sessions still strictly enforce TOFU pinning.
    private static func probeCastHealth(
        host: String,
        port: UInt16,
        timeoutSeconds: Double
    ) async -> (deviceName: String, fingerprint: String)? {
        final class ProbeState: Sendable {
            let lock = OSAllocatedUnfairLock<(
                resumed: Bool,
                connection: NWConnection?,
                timeoutTask: Task<Void, Never>?,
                fingerprint: String,
                buffer: Data
            )>(initialState: (false, nil, nil, "", Data()))
            let queue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.usbtether-probe", qos: .utility)
        }

        let state = ProbeState()
        return await withCheckedContinuation { continuation in
            let finish: @Sendable ((deviceName: String, fingerprint: String)?) -> Void = { result in
                let resources: (NWConnection?, Task<Void, Never>?)? = state.lock.withLock { s in
                    guard !s.resumed else { return nil }
                    s.resumed = true
                    let conn = s.connection
                    let task = s.timeoutTask
                    s.connection = nil
                    s.timeoutTask = nil
                    return (conn, task)
                }
                guard let (conn, task) = resources else { return }
                task?.cancel()
                conn?.cancel()
                continuation.resume(returning: result)
            }

            let tlsOptions = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv12)
            sec_protocol_options_set_verify_block(
                tlsOptions.securityProtocolOptions,
                { _, secTrust, verifyComplete in
                    guard let fp = OpalCastTLSConfiguration.extractLeafCertificateSHA256(from: secTrust) else {
                        verifyComplete(false)
                        return
                    }
                    state.lock.withLock { $0.fingerprint = fp }
                    verifyComplete(true)
                },
                state.queue
            )

            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.noDelay = true
            tcpOptions.connectionTimeout = max(1, Int(ceil(timeoutSeconds)))
            let params = NWParameters(tls: tlsOptions, tcp: tcpOptions)
            let nwPort = NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(integerLiteral: OpalCastProtocol.defaultWifiPort)
            let connection = NWConnection(to: .hostPort(host: NWEndpoint.Host(host), port: nwPort), using: params)

            let timeoutTask = Task.detached(priority: .utility) {
                let ns = UInt64(max(timeoutSeconds, 0.5) * 1_000_000_000)
                try? await Task.sleep(nanoseconds: ns)
                guard !Task.isCancelled else { return }
                finish(nil)
            }

            state.lock.withLock {
                $0.connection = connection
                $0.timeoutTask = timeoutTask
            }

            @Sendable func receiveNext() {
                connection.receive(minimumIncompleteLength: 1, maximumLength: 2048) { data, _, isComplete, error in
                    let snapshot: (raw: String, fp: String, tooLarge: Bool) = state.lock.withLock { s in
                        if let data, !data.isEmpty {
                            s.buffer.append(data)
                        }
                        let raw = String(data: s.buffer, encoding: .utf8) ?? ""
                        return (raw, s.fingerprint, s.buffer.count >= 4096)
                    }
                    if let name = UsbTetherRouteParser.parseCastHealthHTTPResponse(snapshot.raw),
                       !snapshot.fp.isEmpty
                    {
                        finish((deviceName: name, fingerprint: snapshot.fp))
                        return
                    }
                    if isComplete || error != nil || snapshot.tooLarge {
                        finish(nil)
                        return
                    }
                    receiveNext()
                }
            }

            connection.stateUpdateHandler = { connState in
                switch connState {
                case .ready:
                    let req = Data("GET /health HTTP/1.1\r\nHost: \(host):\(port)\r\nConnection: close\r\n\r\n".utf8)
                    connection.send(content: req, completion: .contentProcessed { sendErr in
                        if sendErr != nil {
                            finish(nil)
                            return
                        }
                        receiveNext()
                    })
                case .failed, .waiting:
                    finish(nil)
                default:
                    break
                }
            }

            connection.start(queue: state.queue)
        }
    }
}
