use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;
#[cfg(target_os = "windows")]
use std::process::Command;

#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub const BLE_SERVICE_UUID: &str = "7A4F0001-6F70-616C-6465-736B00000001";
pub const BLE_CHAR_CHALLENGE_UUID: &str = "7A4F0002-6F70-616C-6465-736B00000001";
pub const BLE_CHAR_COMMAND_UUID: &str = "7A4F0003-6F70-616C-6465-736B00000001";
pub const BLE_MANUFACTURER_ID: u16 = 0x504F;
pub const BLE_PROTOCOL_VERSION: u8 = 1;
pub const BLE_MANUFACTURER_DATA_LEN: usize = 12;

pub const CAP_LAN_SHARE: u8 = 0x01;
pub const CAP_HOTSPOT_ACTIVE: u8 = 0x02;
pub const CAP_HOTSPOT_TRIGGER: u8 = 0x04;

const HOTSPOT_HMAC_DOMAIN: &str = "OPAL_HOTSPOT_V1:";

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BleAdvertisementInfo {
    pub version: u8,
    pub cap_flags: u8,
    pub cert_fingerprint_prefix: String,
    pub wifi_port: u16,
    pub battery_pct: i16,
    pub has_lan_share: bool,
    pub hotspot_active: bool,
    pub can_trigger_hotspot: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredBleDevice {
    pub address: String,
    pub name: String,
    pub rssi: i16,
    pub cert_fingerprint_prefix: String,
    pub wifi_port: u16,
    pub battery_pct: i16,
    pub has_lan_share: bool,
    pub hotspot_active: bool,
    pub can_trigger_hotspot: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BleScanResult {
    pub supported: bool,
    pub devices: Vec<DiscoveredBleDevice>,
    pub note: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TriggerBleHotspotRequest {
    pub address: String,
    #[serde(default)]
    pub cert_fingerprint_prefix: String,
    pub client_id: String,
    pub auth_token: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TriggerBleHotspotResult {
    pub ok: bool,
    pub message: String,
}

fn to_hex_lower(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

pub fn decode_hex_bytes(raw: &str) -> Option<Vec<u8>> {
    let cleaned: String = raw
        .chars()
        .filter(|c| c.is_ascii_hexdigit())
        .collect();
    if cleaned.len() % 2 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(cleaned.len() / 2);
    for idx in (0..cleaned.len()).step_by(2) {
        let byte = u8::from_str_radix(&cleaned[idx..idx + 2], 16).ok()?;
        out.push(byte);
    }
    Some(out)
}

/// Decodes the exact 12-byte OpalDesk BLE Manufacturer Specific Data payload
/// (byte-for-byte compatible with Android `BleNearbyProtocol.java` & macOS `BleNearbyProtocol.swift`).
pub fn decode_manufacturer_data(data: &[u8]) -> Option<BleAdvertisementInfo> {
    if data.len() < BLE_MANUFACTURER_DATA_LEN {
        return None;
    }
    let header = data[0];
    let version = (header >> 4) & 0x0F;
    let cap_flags = header & 0x0F;
    let cert_fingerprint_prefix = to_hex_lower(&data[1..9]);
    let wifi_port = u16::from_be_bytes([data[9], data[10]]);
    let raw_batt = data[11];
    let battery_pct = if raw_batt <= 100 { raw_batt as i16 } else { -1 };

    Some(BleAdvertisementInfo {
        version,
        cap_flags,
        cert_fingerprint_prefix,
        wifi_port,
        battery_pct,
        has_lan_share: (cap_flags & CAP_LAN_SHARE) != 0,
        hotspot_active: (cap_flags & CAP_HOTSPOT_ACTIVE) != 0,
        can_trigger_hotspot: (cap_flags & CAP_HOTSPOT_TRIGGER) != 0,
    })
}

pub fn sha256_hex_lower(input: &str) -> String {
    let digest = Sha256::digest(input.as_bytes());
    to_hex_lower(&digest)
}

/// Standard RFC 2104 HMAC-SHA256 built directly on top of `sha2::Sha256` (zero extra crates).
pub fn hmac_sha256_hex_lower(key: &[u8], msg: &[u8]) -> String {
    const BLOCK_SIZE: usize = 64;
    let mut key_block = [0u8; BLOCK_SIZE];
    if key.len() > BLOCK_SIZE {
        let hashed = Sha256::digest(key);
        key_block[..32].copy_from_slice(&hashed);
    } else {
        key_block[..key.len()].copy_from_slice(key);
    }

    let mut ipad = [0x36u8; BLOCK_SIZE];
    let mut opad = [0x5Cu8; BLOCK_SIZE];
    for i in 0..BLOCK_SIZE {
        ipad[i] ^= key_block[i];
        opad[i] ^= key_block[i];
    }

    let mut inner = Sha256::new();
    inner.update(ipad);
    inner.update(msg);
    let inner_hash = inner.finalize();

    let mut outer = Sha256::new();
    outer.update(opad);
    outer.update(inner_hash);
    let final_hash = outer.finalize();
    to_hex_lower(&final_hash)
}

/// Computes `HMAC-SHA256(key = UTF8(sha256HexLower(authToken)),
/// msg = UTF8("OPAL_HOTSPOT_V1:" + clientId.trim() + ":" + nonceHex.trim().toLowerCase()))`.
pub fn compute_hotspot_hmac_hex(auth_token: &str, client_id: &str, nonce_hex: &str) -> String {
    if auth_token.is_empty() {
        return String::new();
    }
    let token_sha256 = sha256_hex_lower(auth_token);
    compute_hotspot_hmac_from_token_hash(&token_sha256, client_id, nonce_hex)
}

pub fn compute_hotspot_hmac_from_token_hash(
    token_sha256: &str,
    client_id: &str,
    nonce_hex: &str,
) -> String {
    let norm_key = token_sha256.trim().to_ascii_lowercase();
    let norm_cid = client_id.trim();
    let norm_nonce = nonce_hex.trim().to_ascii_lowercase();
    if norm_key.is_empty() || norm_cid.is_empty() || norm_nonce.is_empty() {
        return String::new();
    }
    let message = format!("{HOTSPOT_HMAC_DOMAIN}{norm_cid}:{norm_nonce}");
    hmac_sha256_hex_lower(norm_key.as_bytes(), message.as_bytes())
}

#[cfg(target_os = "windows")]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawWinBleItem {
    address: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    rssi: i16,
    #[serde(default)]
    raw_hex: String,
}

/// Scans nearby OpalDesk BLE advertisements using Windows 10/11 WinRT BluetoothLEAdvertisementWatcher.
pub fn scan_ble_nearby() -> BleScanResult {
    #[cfg(target_os = "windows")]
    {
        let script = r#"
$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
  $null = [Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
  $null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime]
  $watcher = New-Object Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher
  $watcher.ScanningMode = [Windows.Devices.Bluetooth.Advertisement.BluetoothLEScanningMode]::Active
  $found = [System.Collections.Concurrent.ConcurrentDictionary[string, object]]::new()
  $handler = [Windows.Foundation.TypedEventHandler[Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher, Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementReceivedEventArgs]] {
    param($s, $e)
    foreach ($md in $e.Advertisement.ManufacturerData) {
      if ($md.CompanyId -eq 0x504F) {
        $reader = [Windows.Storage.Streams.DataReader]::FromBuffer($md.Data)
        $bytes = New-Object byte[] $md.Data.Length
        $reader.ReadBytes($bytes)
        $hex = ([System.BitConverter]::ToString($bytes)).Replace('-', '').ToLower()
        $addr = ('{0:X12}' -f $e.BluetoothAddress)
        $found[$addr] = [pscustomobject]@{
          address = $addr
          name = $e.Advertisement.LocalName
          rssi = [int]$e.RawSignalStrengthInDBm
          rawHex = $hex
        }
      }
    }
  }
  $token = $watcher.add_Received($handler)
  $watcher.Start()
  Start-Sleep -Milliseconds 2200
  $watcher.Stop()
  $watcher.remove_Received($token)
  $items = @($found.Values)
  ConvertTo-Json -Compress -Depth 4 ([pscustomobject]@{ supported = $true; items = $items; note = '' })
} catch {
  ConvertTo-Json -Compress -Depth 3 ([pscustomobject]@{ supported = $false; items = @(); note = $_.Exception.Message })
}
"#;
        let output = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script,
            ])
            .creation_flags(CREATE_NO_WINDOW)
            .output();

        let Ok(out) = output else {
            return BleScanResult {
                supported: false,
                devices: Vec::new(),
                note: "无法启动 Windows 蓝牙扫描器".to_string(),
            };
        };
        let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
        let Ok(val) = serde_json::from_str::<serde_json::Value>(&stdout) else {
            return BleScanResult {
                supported: false,
                devices: Vec::new(),
                note: "当前系统未开启蓝牙适配器或不支持 WinRT BLE".to_string(),
            };
        };
        let supported = val.get("supported").and_then(|v| v.as_bool()).unwrap_or(false);
        let note = val
            .get("note")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let raw_items: Vec<RawWinBleItem> = val
            .get("items")
            .and_then(|v| serde_json::from_value(v.clone()).ok())
            .unwrap_or_default();

        let mut devices = Vec::new();
        for item in raw_items {
            let Some(bytes) = decode_hex_bytes(&item.raw_hex) else {
                continue;
            };
            let Some(info) = decode_manufacturer_data(&bytes) else {
                continue;
            };
            if info.version != BLE_PROTOCOL_VERSION {
                continue;
            }
            devices.push(DiscoveredBleDevice {
                address: item.address,
                name: item.name,
                rssi: item.rssi,
                cert_fingerprint_prefix: info.cert_fingerprint_prefix,
                wifi_port: info.wifi_port,
                battery_pct: info.battery_pct,
                has_lan_share: info.has_lan_share,
                hotspot_active: info.hotspot_active,
                can_trigger_hotspot: info.can_trigger_hotspot,
            });
        }
        devices.sort_by(|a, b| b.rssi.cmp(&a.rssi));
        return BleScanResult {
            supported,
            devices,
            note,
        };
    }

    #[cfg(not(target_os = "windows"))]
    {
        BleScanResult {
            supported: false,
            devices: Vec::new(),
            note: "BLE 靠近发现与一键热点需在 Windows 10/11 桌面客户端中使用".to_string(),
        }
    }
}

/// Triggers remote Android 1-click hotspot via BLE GATT Challenge/HMAC-SHA256 Command.
pub fn trigger_ble_hotspot(req: &TriggerBleHotspotRequest) -> Result<TriggerBleHotspotResult, String> {
    if req.address.trim().is_empty() || req.client_id.trim().is_empty() || req.auth_token.is_empty()
    {
        return Err("缺少 BLE 设备地址、客户端 ID 或信任令牌".to_string());
    }
    let token_sha256 = sha256_hex_lower(&req.auth_token);

    #[cfg(target_os = "windows")]
    {
        let read_challenge_script = r#"
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await-WinRT($op, $type) {
  $m = $asTaskGeneric.MakeGenericMethod($type)
  $t = $m.Invoke($null, @($op))
  $t.Wait(8000) | Out-Null
  return $t.Result
}
$null = [Windows.Devices.Bluetooth.BluetoothLEDevice, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
$null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
$null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
$null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattReadResult, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime]

$addr = [Convert]::ToUInt64($env:OPAL_BLE_ADDR, 16)
$dev = Await-WinRT ([Windows.Devices.Bluetooth.BluetoothLEDevice]::FromBluetoothAddressAsync($addr)) ([Windows.Devices.Bluetooth.BluetoothLEDevice])
if ($null -eq $dev) { throw '无法连接目标蓝牙设备' }
try {
  $svcRes = Await-WinRT ($dev.GetGattServicesForUuidAsync([Guid]$env:OPAL_BLE_SVC)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult])
  if ($svcRes.Services.Count -eq 0) { throw '手机未广播 OpalDesk BLE 热点服务' }
  $svc = $svcRes.Services[0]
  $chRes = Await-WinRT ($svc.GetCharacteristicsForUuidAsync([Guid]$env:OPAL_BLE_CHALLENGE)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult])
  if ($chRes.Characteristics.Count -eq 0) { throw '未找到 BLE 挑战特征值' }
  $rd = Await-WinRT ($chRes.Characteristics[0].ReadValueAsync()) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattReadResult])
  $reader = [Windows.Storage.Streams.DataReader]::FromBuffer($rd.Value)
  $bytes = New-Object byte[] $rd.Value.Length
  $reader.ReadBytes($bytes)
  $nonce = [System.Text.Encoding]::UTF8.GetString($bytes).Trim()
  Write-Output $nonce
} finally {
  $dev.Dispose()
}
"#;
        let challenge_out = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                read_challenge_script,
            ])
            .env("OPAL_BLE_ADDR", req.address.trim())
            .env("OPAL_BLE_SVC", BLE_SERVICE_UUID)
            .env("OPAL_BLE_CHALLENGE", BLE_CHAR_CHALLENGE_UUID)
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(|e| format!("读取 BLE 挑战值失败: {e}"))?;

        if !challenge_out.status.success() {
            let err = String::from_utf8_lossy(&challenge_out.stderr)
                .trim()
                .to_string();
            return Err(if err.is_empty() {
                "读取手机 BLE 挑战值失败".to_string()
            } else {
                err
            });
        }
        let nonce = String::from_utf8_lossy(&challenge_out.stdout)
            .trim()
            .to_string();
        if nonce.is_empty() {
            return Err("手机返回的 BLE 挑战 Nonce 为空".to_string());
        }

        let hmac_hex = compute_hotspot_hmac_from_token_hash(&token_sha256, &req.client_id, &nonce);
        let cmd_json = serde_json::json!({
            "op": "enable_hotspot",
            "clientId": req.client_id.trim(),
            "nonce": nonce,
            "hmac": hmac_hex,
        })
        .to_string();

        let write_cmd_script = r#"
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await-WinRT($op, $type) {
  $m = $asTaskGeneric.MakeGenericMethod($type)
  $t = $m.Invoke($null, @($op))
  $t.Wait(8000) | Out-Null
  return $t.Result
}
$null = [Windows.Devices.Bluetooth.BluetoothLEDevice, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
$null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
$null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
$null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus, Windows.Devices.Bluetooth, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.DataWriter, Windows.Storage.Streams, ContentType=WindowsRuntime]

$addr = [Convert]::ToUInt64($env:OPAL_BLE_ADDR, 16)
$dev = Await-WinRT ([Windows.Devices.Bluetooth.BluetoothLEDevice]::FromBluetoothAddressAsync($addr)) ([Windows.Devices.Bluetooth.BluetoothLEDevice])
if ($null -eq $dev) { throw '无法连接目标蓝牙设备' }
try {
  $svcRes = Await-WinRT ($dev.GetGattServicesForUuidAsync([Guid]$env:OPAL_BLE_SVC)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult])
  if ($svcRes.Services.Count -eq 0) { throw '手机未广播 OpalDesk BLE 热点服务' }
  $svc = $svcRes.Services[0]
  $chRes = Await-WinRT ($svc.GetCharacteristicsForUuidAsync([Guid]$env:OPAL_BLE_CMD)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult])
  if ($chRes.Characteristics.Count -eq 0) { throw '未找到 BLE 命令特征值' }
  $writer = New-Object Windows.Storage.Streams.DataWriter
  $writer.WriteBytes([System.Text.Encoding]::UTF8.GetBytes($env:OPAL_BLE_PAYLOAD))
  $st = Await-WinRT ($chRes.Characteristics[0].WriteValueAsync($writer.DetachBuffer())) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus])
  if ($st -ne [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus]::Success) {
    throw "BLE 写入热点指令未成功: $st"
  }
} finally {
  $dev.Dispose()
}
"#;
        let write_out = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                write_cmd_script,
            ])
            .env("OPAL_BLE_ADDR", req.address.trim())
            .env("OPAL_BLE_SVC", BLE_SERVICE_UUID)
            .env("OPAL_BLE_CMD", BLE_CHAR_COMMAND_UUID)
            .env("OPAL_BLE_PAYLOAD", &cmd_json)
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(|e| format!("发送 BLE 热点指令失败: {e}"))?;

        if !write_out.status.success() {
            let err = String::from_utf8_lossy(&write_out.stderr).trim().to_string();
            return Err(if err.is_empty() {
                "手机未接受热点开启指令".to_string()
            } else {
                err
            });
        }
        return Ok(TriggerBleHotspotResult {
            ok: true,
            message: "已通过蓝牙安全校验唤醒手机热点，请稍候连接手机 Wi-Fi".to_string(),
        });
    }

    #[cfg(not(target_os = "windows"))]
    {
        let _ = token_sha256;
        Err("BLE 一键开启热点仅支持 Windows 10/11 桌面客户端".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_12_byte_manufacturer_payload_matching_android() {
        // version=1, capFlags=0x07 (LAN|HOTSPOT_ACTIVE|TRIGGER), fpPrefix=0123456789abcdef, port=19878 (0x4DA6), battery=85
        let payload: [u8; 12] = [
            0x17, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x4d, 0xa6, 85,
        ];
        let info = decode_manufacturer_data(&payload).expect("decode ble payload");
        assert_eq!(info.version, 1);
        assert_eq!(info.cap_flags, 0x07);
        assert_eq!(info.cert_fingerprint_prefix, "0123456789abcdef");
        assert_eq!(info.wifi_port, 19878);
        assert_eq!(info.battery_pct, 85);
        assert!(info.has_lan_share);
        assert!(info.hotspot_active);
        assert!(info.can_trigger_hotspot);

        // Unknown battery (0xFF -> -1)
        let mut unknown_batt = payload;
        unknown_batt[11] = 0xFF;
        assert_eq!(decode_manufacturer_data(&unknown_batt).unwrap().battery_pct, -1);
    }

    #[test]
    fn rfc2104_hmac_sha256_and_hotspot_derivation_match_known_vectors() {
        // RFC 4231 Test Case 2: key="Jefe", data="what do ya want for nothing?"
        let mac = hmac_sha256_hex_lower(b"Jefe", b"what do ya want for nothing?");
        assert_eq!(
            mac,
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );

        let h1 = compute_hotspot_hmac_hex("secret-token", "win-client-1", "AABBCCDD");
        let h2 = compute_hotspot_hmac_hex("secret-token", " win-client-1 ", "aabbccdd");
        assert_eq!(h1.len(), 64);
        assert_eq!(h1, h2);
    }
}
