use crate::tls_bridge;
use serde::Serialize;
use std::collections::HashSet;
use std::net::Ipv4Addr;

pub const DEFAULT_CAST_PORT: u16 = 19878;
const PROBE_TIMEOUT_MS: u64 = 1_500;

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UsbTetherGatewayCandidate {
    pub interface_alias: String,
    pub interface_description: String,
    pub gateway_ip: String,
    pub local_ip: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredUsbTetherDevice {
    pub id: String,
    pub display_name: String,
    pub host: String,
    pub port: u16,
    pub interface_alias: String,
    pub interface_description: String,
    pub fingerprint: String,
    pub short_fingerprint: String,
}

/// Identifies Windows USB Tethering virtual network adapters (Microsoft RNDIS, USB CDC-NCM,
/// Android USB Ethernet) while excluding VPNs, hypervisors, Wi-Fi, Bluetooth, and iOS tethering.
pub fn is_usb_tethering_adapter(alias: &str, description: &str) -> bool {
    let combined = format!(
        "{} {}",
        alias.trim().to_ascii_lowercase(),
        description.trim().to_ascii_lowercase()
    );
    if combined.trim().is_empty() {
        return false;
    }

    const EXCLUDED_TOKENS: &[&str] = &[
        "hyper-v",
        "vmware",
        "virtualbox",
        "vbox",
        "wintun",
        "wireguard",
        "tailscale",
        "zerotier",
        "tap-windows",
        "openvpn",
        "clash",
        "bluetooth",
        "wi-fi",
        "wifi",
        "wireless",
        "802.11",
        "apple mobile",
        "teredo",
        "loopback",
        "wan miniport",
    ];
    if EXCLUDED_TOKENS.iter().any(|tok| combined.contains(tok)) {
        return false;
    }

    const USB_TETHER_TOKENS: &[&str] = &[
        "rndis",
        "remote ndis",
        "usb ncm",
        "usbncm",
        "cdc ncm",
        "cdc ecm",
        "android",
        "usb ethernet",
        "usb lan",
        "tether",
    ];
    USB_TETHER_TOKENS.iter().any(|tok| combined.contains(tok))
}

pub fn is_private_ipv4(raw: &str) -> bool {
    let Ok(ip) = raw.trim().parse::<Ipv4Addr>() else {
        return false;
    };
    ip.is_private()
}

/// Parses JSON emitted by PowerShell `Get-NetIPConfiguration | ... | ConvertTo-Json -Compress`.
/// Handles both a single JSON object (when only 1 adapter matches) and a JSON array.
pub fn parse_windows_net_config_json(raw: &str) -> Vec<UsbTetherGatewayCandidate> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }
    let Ok(parsed) = serde_json::from_str::<serde_json::Value>(trimmed) else {
        return Vec::new();
    };
    let entries: Vec<&serde_json::Value> = match &parsed {
        serde_json::Value::Array(arr) => arr.iter().collect(),
        obj @ serde_json::Value::Object(_) => vec![obj],
        _ => return Vec::new(),
    };

    let mut seen_gateways = HashSet::new();
    let mut out = Vec::new();
    for item in entries {
        let alias = item
            .get("InterfaceAlias")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let desc = item
            .get("InterfaceDescription")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let gw = item
            .get("Gateway")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let local = item
            .get("LocalIp")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();

        if !is_usb_tethering_adapter(alias, desc) {
            continue;
        }
        if !is_private_ipv4(gw) || gw == local {
            continue;
        }
        if !seen_gateways.insert(gw.to_string()) {
            continue;
        }
        out.push(UsbTetherGatewayCandidate {
            interface_alias: alias.to_string(),
            interface_description: desc.to_string(),
            gateway_ip: gw.to_string(),
            local_ip: local.to_string(),
        });
    }
    out
}

#[cfg(target_os = "windows")]
async fn list_usb_tether_gateway_candidates() -> Result<Vec<UsbTetherGatewayCandidate>, String> {
    use std::os::windows::process::CommandExt;
    use std::process::Stdio;
    use std::time::Duration;

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const PS_SCRIPT: &str = "Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } | Select-Object InterfaceAlias,InterfaceDescription,@{N='Gateway';E={($_.IPv4DefaultGateway | Select-Object -First 1).NextHop}},@{N='LocalIp';E={($_.IPv4Address | Select-Object -First 1).IPAddress}} | ConvertTo-Json -Compress";

    let mut std_cmd = std::process::Command::new("powershell.exe");
    std_cmd
        .args(["-NoProfile", "-NonInteractive", "-Command", PS_SCRIPT])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW);

    let mut cmd = tokio::process::Command::from(std_cmd);
    cmd.kill_on_drop(true);

    let output = tokio::time::timeout(Duration::from_secs(4), cmd.output())
        .await
        .map_err(|_| "查询 Windows USB 网卡信息超时".to_string())?
        .map_err(|e| format!("无法查询 Windows USB 网卡信息: {e}"))?;

    if !output.status.success() {
        return Ok(Vec::new());
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    Ok(parse_windows_net_config_json(&stdout))
}

#[cfg(not(target_os = "windows"))]
async fn list_usb_tether_gateway_candidates() -> Result<Vec<UsbTetherGatewayCandidate>, String> {
    Ok(Vec::new())
}

/// Scans active USB Tethering gateways on Windows and probes each for a genuine OpalDesk
/// `:19878` TLS cast service (`GET /health` with `role == "cast"`).
pub async fn scan_usb_tether_devices(port: u16) -> Result<Vec<DiscoveredUsbTetherDevice>, String> {
    let target_port = if port == 0 { DEFAULT_CAST_PORT } else { port };
    let candidates = list_usb_tether_gateway_candidates().await?;
    if candidates.is_empty() {
        return Ok(Vec::new());
    }

    let mut handles = Vec::with_capacity(candidates.len());
    for cand in candidates {
        handles.push(tokio::spawn(async move {
            let probed =
                tls_bridge::probe_cast_health(&cand.gateway_ip, target_port, PROBE_TIMEOUT_MS)
                    .await
                    .ok()?;
            Some(DiscoveredUsbTetherDevice {
                id: format!("usb-tether:{}:{}", probed.host, probed.port),
                display_name: probed.device_name,
                host: probed.host,
                port: probed.port,
                interface_alias: cand.interface_alias,
                interface_description: cand.interface_description,
                fingerprint: probed.fingerprint,
                short_fingerprint: probed.short_fingerprint,
            })
        }));
    }

    let mut found = Vec::new();
    for handle in handles {
        if let Ok(Some(dev)) = handle.await {
            found.push(dev);
        }
    }
    found.sort_by(|a, b| a.display_name.cmp(&b.display_name).then(a.host.cmp(&b.host)));
    Ok(found)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_usb_tethering_adapters_and_rejects_vpn_or_wifi() {
        assert!(is_usb_tethering_adapter(
            "以太网 3",
            "Remote NDIS based Internet Sharing Device"
        ));
        assert!(is_usb_tethering_adapter(
            "Ethernet 4",
            "USB NCM Host Device"
        ));
        assert!(is_usb_tethering_adapter(
            "Ethernet 5",
            "SAMSUNG Mobile USB Remote NDIS Network Device"
        ));
        assert!(is_usb_tethering_adapter(
            "USB Ethernet",
            "Android CDC NCM Gadget"
        ));

        assert!(!is_usb_tethering_adapter(
            "WLAN",
            "Intel(R) Wi-Fi 6E AX211 160MHz"
        ));
        assert!(!is_usb_tethering_adapter(
            "以太网",
            "Realtek PCIe 2.5GbE Family Controller"
        ));
        assert!(!is_usb_tethering_adapter("WireGuard", "WireGuard Tunnel"));
        assert!(!is_usb_tethering_adapter(
            "vEthernet (WSL)",
            "Hyper-V Virtual Ethernet Adapter"
        ));
        assert!(!is_usb_tethering_adapter(
            "Ethernet 6",
            "Apple Mobile Device Ethernet"
        ));
    }

    #[test]
    fn parses_single_and_array_powershell_json_outputs() {
        let single = r#"{"InterfaceAlias":"以太网 3","InterfaceDescription":"USB NCM Host Device","Gateway":"192.168.148.10","LocalIp":"192.168.148.99"}"#;
        let parsed_single = parse_windows_net_config_json(single);
        assert_eq!(parsed_single.len(), 1);
        assert_eq!(parsed_single[0].gateway_ip, "192.168.148.10");

        let array = r#"[
            {"InterfaceAlias":"WLAN","InterfaceDescription":"Intel Wi-Fi 6","Gateway":"192.168.31.1","LocalIp":"192.168.31.42"},
            {"InterfaceAlias":"以太网 2","InterfaceDescription":"Remote NDIS Compatible Device","Gateway":"192.168.42.129","LocalIp":"192.168.42.55"},
            {"InterfaceAlias":"以太网 3","InterfaceDescription":"Remote NDIS Compatible Device","Gateway":"192.168.42.129","LocalIp":"192.168.42.56"},
            {"InterfaceAlias":"以太网 4","InterfaceDescription":"USB NCM Host Device","Gateway":"8.8.8.8","LocalIp":"192.168.42.57"}
        ]"#;
        let parsed_array = parse_windows_net_config_json(array);
        assert_eq!(parsed_array.len(), 1);
        assert_eq!(parsed_array[0].gateway_ip, "192.168.42.129");
    }

    #[test]
    fn parses_opalcast_health_response_and_rejects_foreign_http() {
        let ok_resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true,\"role\":\"cast\",\"deviceName\":\"Xiaomi 14 Pro\",\"certShortFp\":\"aabbccddeeff\"}";
        assert_eq!(
            tls_bridge::parse_cast_health_http_response(ok_resp).as_deref(),
            Some("Xiaomi 14 Pro")
        );

        let activate_resp = "HTTP/1.1 200 OK\r\n\r\n{\"ok\":true,\"role\":\"activate\",\"deviceName\":\"Phone\"}";
        assert!(tls_bridge::parse_cast_health_http_response(activate_resp).is_none());

        let router_resp = "HTTP/1.1 404 Not Found\r\n\r\nnot found";
        assert!(tls_bridge::parse_cast_health_http_response(router_resp).is_none());
    }
}
