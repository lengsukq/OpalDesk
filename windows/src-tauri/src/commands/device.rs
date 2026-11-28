use crate::adb::{AdbDevice, AdbForwardResult, AdbStatusInfo};
use crate::ble_nearby::{self, BleScanResult, TriggerBleHotspotRequest, TriggerBleHotspotResult};
use crate::mdns::DiscoveredMdnsDevice;
use crate::tls_bridge::{self, LocalWsTunnelInfo, ProbedCertInfo};
use crate::trust::TrustedDevice;
use crate::usb_tether::{self, DiscoveredUsbTetherDevice};
use crate::DesktopState;

#[tauri::command]
pub fn list_mdns_devices(state: tauri::State<'_, DesktopState>) -> Vec<DiscoveredMdnsDevice> {
    state.mdns_scanner.list_devices()
}

#[tauri::command]
pub fn refresh_mdns_devices(state: tauri::State<'_, DesktopState>) -> Vec<DiscoveredMdnsDevice> {
    state.mdns_scanner.refresh();
    state.log_store.info("mDNS", "Triggered manual mDNS scan");
    state.mdns_scanner.list_devices()
}

#[tauri::command]
pub fn list_trusted_devices(state: tauri::State<'_, DesktopState>) -> Vec<TrustedDevice> {
    state.trust_store.list_devices()
}

#[tauri::command]
pub fn upsert_trusted_device(
    state: tauri::State<'_, DesktopState>,
    device: TrustedDevice,
) -> Result<TrustedDevice, String> {
    let saved = state.trust_store.upsert(device)?;
    state.log_store.info(
        "Trust",
        &format!(
            "Upserted trusted device {} ({}:{})",
            saved.display_name, saved.host, saved.port
        ),
    );
    Ok(saved)
}

#[tauri::command]
pub fn revoke_trusted_device(
    state: tauri::State<'_, DesktopState>,
    id: String,
) -> Result<(), String> {
    state.trust_store.revoke(&id)?;
    state
        .log_store
        .info("Trust", &format!("Revoked trusted device {id}"));
    Ok(())
}

#[tauri::command]
pub async fn probe_tls_certificate(
    host: String,
    port: u16,
    timeout_ms: Option<u64>,
) -> Result<ProbedCertInfo, String> {
    tls_bridge::probe_certificate_fingerprint(&host, port, timeout_ms.unwrap_or(4_500)).await
}

#[tauri::command]
pub async fn start_tls_ws_tunnel(
    state: tauri::State<'_, DesktopState>,
    host: String,
    port: u16,
    path: String,
    expected_fingerprint: String,
) -> Result<LocalWsTunnelInfo, String> {
    let effective_pin = state
        .trust_store
        .resolve_strongest_pin(&expected_fingerprint, &host, port);
    let info = state
        .tls_bridges
        .ensure_tunnel(&host, port, &path, &effective_pin)
        .await?;
    state.log_store.info(
        "TLS",
        &format!(
            "TLS tunnel ready {}:{} path={} -> 127.0.0.1:{}",
            host, port, path, info.local_port
        ),
    );
    Ok(info)
}

#[tauri::command]
pub fn get_adb_status(state: tauri::State<'_, DesktopState>) -> AdbStatusInfo {
    state.adb_manager.status()
}

#[tauri::command]
pub fn set_custom_adb_path(
    state: tauri::State<'_, DesktopState>,
    path: Option<String>,
) -> Result<AdbStatusInfo, String> {
    let status = state.adb_manager.set_custom_adb_path(path);
    if let Err(err) = state
        .prefs_store
        .set_custom_adb_path(status.custom_adb_path.clone())
    {
        state
            .log_store
            .warn("Prefs", &format!("Failed to persist custom ADB path: {err}"));
        return Err(format!("ADB 路径已临时生效，但持久化保存失败: {err}"));
    }
    let summary = match status.custom_adb_path.as_deref() {
        Some(p) => format!("Saved custom ADB path: {p} (available={})", status.available),
        None => format!("Cleared custom ADB path (available={})", status.available),
    };
    state.log_store.info("ADB", &summary);
    Ok(status)
}

#[tauri::command]
pub async fn list_adb_devices(
    state: tauri::State<'_, DesktopState>,
) -> Result<Vec<AdbDevice>, String> {
    state.adb_manager.list_devices().await
}

#[tauri::command]
pub async fn scan_usb_tether_devices(
    port: Option<u16>,
) -> Result<Vec<DiscoveredUsbTetherDevice>, String> {
    usb_tether::scan_usb_tether_devices(port.unwrap_or(usb_tether::DEFAULT_CAST_PORT)).await
}

#[tauri::command]
pub async fn adb_bootstrap_and_forward(
    state: tauri::State<'_, DesktopState>,
    serial: String,
    preferred_port: Option<u16>,
) -> Result<AdbForwardResult, String> {
    let res = state
        .adb_manager
        .bootstrap_and_forward(&serial, preferred_port)
        .await?;
    state.log_store.info(
        "ADB",
        &format!(
            "ADB forward ready serial={} -> 127.0.0.1:{}",
            res.serial, res.local_port
        ),
    );
    Ok(res)
}

#[tauri::command]
pub async fn adb_pair_wireless(
    state: tauri::State<'_, DesktopState>,
    host: String,
    port: u16,
    code: String,
) -> Result<String, String> {
    state.adb_manager.pair_wireless(&host, port, &code).await
}

#[tauri::command]
pub async fn adb_connect_wireless(
    state: tauri::State<'_, DesktopState>,
    host: String,
    port: u16,
) -> Result<String, String> {
    state.adb_manager.connect_wireless(&host, port).await
}

#[tauri::command]
pub async fn scan_ble_nearby() -> Result<BleScanResult, String> {
    tokio::task::spawn_blocking(ble_nearby::scan_ble_nearby)
        .await
        .map_err(|e| format!("蓝牙扫描任务异常: {e}"))
}

#[tauri::command]
pub async fn trigger_ble_hotspot(
    req: TriggerBleHotspotRequest,
) -> Result<TriggerBleHotspotResult, String> {
    tokio::task::spawn_blocking(move || ble_nearby::trigger_ble_hotspot(&req))
        .await
        .map_err(|e| format!("蓝牙热点唤醒任务异常: {e}"))?
}
