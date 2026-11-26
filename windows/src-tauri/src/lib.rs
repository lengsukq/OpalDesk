pub mod adb;
pub mod fingerprint;
pub mod mdns;
pub mod tls_bridge;
pub mod trust;
pub mod usb_tether;

use adb::{AdbDevice, AdbForwardResult, AdbManager, AdbStatusInfo};
use mdns::{DiscoveredMdnsDevice, MdnsScanner};
use serde::Serialize;
use std::sync::Arc;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};
use tls_bridge::{LocalWsTunnelInfo, ProbedCertInfo, TlsBridgeRegistry};
use trust::{TrustStore, TrustedDevice};
use usb_tether::DiscoveredUsbTetherDevice;

#[cfg(target_os = "windows")]
const WEBVIEW2_BROWSER_ARGS: &str = "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --enable-features=PlatformHEVCDecoderSupport";

pub struct DesktopState {
    pub trust_store: Arc<TrustStore>,
    pub tls_bridges: Arc<TlsBridgeRegistry>,
    pub mdns_scanner: Arc<MdnsScanner>,
    pub adb_manager: Arc<AdbManager>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopRuntimeInfo {
    pub platform: &'static str,
    pub arch: &'static str,
    pub version: &'static str,
    pub client_id: String,
    pub adb: AdbStatusInfo,
}

#[tauri::command]
fn desktop_runtime_info(state: tauri::State<'_, DesktopState>) -> DesktopRuntimeInfo {
    DesktopRuntimeInfo {
        platform: std::env::consts::OS,
        arch: std::env::consts::ARCH,
        version: env!("CARGO_PKG_VERSION"),
        client_id: state.trust_store.client_id(),
        adb: state.adb_manager.status(),
    }
}

#[tauri::command]
fn list_mdns_devices(state: tauri::State<'_, DesktopState>) -> Vec<DiscoveredMdnsDevice> {
    state.mdns_scanner.list_devices()
}

#[tauri::command]
fn refresh_mdns_devices(state: tauri::State<'_, DesktopState>) -> Vec<DiscoveredMdnsDevice> {
    state.mdns_scanner.refresh();
    state.mdns_scanner.list_devices()
}

#[tauri::command]
fn list_trusted_devices(state: tauri::State<'_, DesktopState>) -> Vec<TrustedDevice> {
    state.trust_store.list_devices()
}

#[tauri::command]
fn upsert_trusted_device(
    state: tauri::State<'_, DesktopState>,
    device: TrustedDevice,
) -> Result<TrustedDevice, String> {
    state.trust_store.upsert(device)
}

#[tauri::command]
fn revoke_trusted_device(
    state: tauri::State<'_, DesktopState>,
    id: String,
) -> Result<(), String> {
    state.trust_store.revoke(&id)
}

#[tauri::command]
async fn probe_tls_certificate(
    host: String,
    port: u16,
    timeout_ms: Option<u64>,
) -> Result<ProbedCertInfo, String> {
    tls_bridge::probe_certificate_fingerprint(&host, port, timeout_ms.unwrap_or(4_500)).await
}

#[tauri::command]
async fn start_tls_ws_tunnel(
    state: tauri::State<'_, DesktopState>,
    host: String,
    port: u16,
    path: String,
    expected_fingerprint: String,
) -> Result<LocalWsTunnelInfo, String> {
    let effective_pin = state
        .trust_store
        .resolve_strongest_pin(&expected_fingerprint, &host, port);
    state
        .tls_bridges
        .ensure_tunnel(&host, port, &path, &effective_pin)
        .await
}

#[tauri::command]
fn get_adb_status(state: tauri::State<'_, DesktopState>) -> AdbStatusInfo {
    state.adb_manager.status()
}

#[tauri::command]
fn set_custom_adb_path(
    state: tauri::State<'_, DesktopState>,
    path: Option<String>,
) -> AdbStatusInfo {
    state.adb_manager.set_custom_adb_path(path)
}

#[tauri::command]
async fn list_adb_devices(state: tauri::State<'_, DesktopState>) -> Result<Vec<AdbDevice>, String> {
    state.adb_manager.list_devices().await
}

#[tauri::command]
async fn scan_usb_tether_devices(
    port: Option<u16>,
) -> Result<Vec<DiscoveredUsbTetherDevice>, String> {
    usb_tether::scan_usb_tether_devices(port.unwrap_or(usb_tether::DEFAULT_CAST_PORT)).await
}

#[tauri::command]
async fn adb_bootstrap_and_forward(
    state: tauri::State<'_, DesktopState>,
    serial: String,
    preferred_port: Option<u16>,
) -> Result<AdbForwardResult, String> {
    state
        .adb_manager
        .bootstrap_and_forward(&serial, preferred_port)
        .await
}

#[tauri::command]
async fn adb_pair_wireless(
    state: tauri::State<'_, DesktopState>,
    host: String,
    port: u16,
    code: String,
) -> Result<String, String> {
    state.adb_manager.pair_wireless(&host, port, &code).await
}

#[tauri::command]
async fn adb_connect_wireless(
    state: tauri::State<'_, DesktopState>,
    host: String,
    port: u16,
) -> Result<String, String> {
    state.adb_manager.connect_wireless(&host, port).await
}

#[tauri::command]
async fn open_standalone_window(
    app: tauri::AppHandle,
    pkg: String,
    title: String,
    query: String,
    width: Option<f64>,
    height: Option<f64>,
) -> Result<String, String> {
    let sanitized_pkg: String = pkg
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '-'
            }
        })
        .collect();
    let label = format!("standalone-{}", sanitized_pkg);

    if let Some(existing) = app.get_webview_window(&label) {
        let _ = existing.unminimize();
        let _ = existing.show();
        let _ = existing.set_focus();
        return Ok(label);
    }

    let clean_query = query.trim_start_matches('?');
    let target_url = if clean_query.is_empty() {
        "index.html".to_string()
    } else {
        format!("index.html?{}", clean_query)
    };

    let win_w = width.unwrap_or(440.0).clamp(360.0, 1920.0);
    let win_h = height.unwrap_or(880.0).clamp(540.0, 1440.0);
    let win_title = if title.trim().is_empty() {
        format!("OpalDesk · {}", pkg)
    } else {
        format!("OpalDesk · {}", title.trim())
    };

    let builder = WebviewWindowBuilder::new(&app, &label, WebviewUrl::App(target_url.into()))
        .title(win_title)
        .inner_size(win_w, win_h)
        .min_inner_size(360.0, 540.0)
        .resizable(true)
        .center()
        .disable_drag_drop_handler()
        .enable_clipboard_access();

    #[cfg(target_os = "windows")]
    let builder = builder.additional_browser_args(WEBVIEW2_BROWSER_ARGS);

    builder
        .build()
        .map_err(|e| format!("创建独立应用窗口失败: {e}"))?;

    Ok(label)
}

fn setup_system_tray(app: &tauri::App) -> tauri::Result<()> {
    let show_item = MenuItem::with_id(app, "show_main", "显示 OpalDesk 主控台", true, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, "quit_app", "退出 OpalDesk", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show_item, &quit_item])?;

    let mut tray = TrayIconBuilder::new()
        .menu(&menu)
        .tooltip("OpalDesk")
        .show_menu_on_left_click(false)
        .on_menu_event(|app_handle, event| match event.id.as_ref() {
            "show_main" => {
                if let Some(win) = app_handle.get_webview_window("main") {
                    let _ = win.unminimize();
                    let _ = win.show();
                    let _ = win.set_focus();
                }
            }
            "quit_app" => {
                app_handle.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray_icon, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                if let Some(win) = tray_icon.app_handle().get_webview_window("main") {
                    let _ = win.unminimize();
                    let _ = win.show();
                    let _ = win.set_focus();
                }
            }
        });

    if let Some(icon) = app.default_window_icon().cloned() {
        tray = tray.icon(icon);
    }

    tray.build(app)?;
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .setup(|app| {
            let config_dir = app
                .path()
                .app_config_dir()
                .unwrap_or_else(|_| std::env::temp_dir().join("OpalDesk"));
            let resource_dir = app.path().resource_dir().ok();

            let trust_store = Arc::new(TrustStore::new(config_dir.join("trust-store.json")));
            let tls_bridges = Arc::new(TlsBridgeRegistry::new());
            let mdns_scanner = Arc::new(MdnsScanner::start());
            let adb_manager = Arc::new(AdbManager::new(resource_dir));

            app.manage(DesktopState {
                trust_store,
                tls_bridges,
                mdns_scanner,
                adb_manager,
            });

            let _ = setup_system_tray(app);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            desktop_runtime_info,
            list_mdns_devices,
            refresh_mdns_devices,
            list_trusted_devices,
            upsert_trusted_device,
            revoke_trusted_device,
            probe_tls_certificate,
            start_tls_ws_tunnel,
            get_adb_status,
            set_custom_adb_path,
            list_adb_devices,
            scan_usb_tether_devices,
            adb_bootstrap_and_forward,
            adb_pair_wireless,
            adb_connect_wireless,
            open_standalone_window,
        ])
        .build(tauri::generate_context!())
        .expect("error while building OpalDesk Tauri application");

    app.run(|app_handle, event| {
        if let tauri::RunEvent::Exit = event {
            if let Some(state) = app_handle.try_state::<DesktopState>() {
                state.mdns_scanner.shutdown();
                state.tls_bridges.shutdown_all();
                state.adb_manager.cleanup_all_forwards_sync();
            }
        }
    });
}
