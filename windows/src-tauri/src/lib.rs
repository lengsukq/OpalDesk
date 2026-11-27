pub mod adb;
pub mod feedback;
pub mod fingerprint;
pub mod logs;
pub mod mdns;
pub mod prefs;
pub mod tls_bridge;
pub mod trust;
pub mod usb_tether;

use adb::{AdbDevice, AdbForwardResult, AdbManager, AdbStatusInfo};
use feedback::{
    FeedbackLookupOutcome, FeedbackManager, FeedbackRecord, FeedbackRefreshOutcome,
    FeedbackStateSnapshot, ManualReportOutcome,
};
use logs::{LogEntry, LogStore};
use mdns::{DiscoveredMdnsDevice, MdnsScanner};
use prefs::{ClientPrefs, ClientPrefsPatch, PrefsStore};
use serde::Serialize;
use std::collections::HashMap;
use std::fs;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};
use tls_bridge::{LocalWsTunnelInfo, ProbedCertInfo, TlsBridgeRegistry};
use trust::{TrustStore, TrustedDevice};
use usb_tether::DiscoveredUsbTetherDevice;

pub const MAX_STANDALONE_WINDOWS: usize = 3;
const STANDALONE_LABEL_PREFIX: &str = "standalone-";

#[cfg(target_os = "windows")]
const WEBVIEW2_BROWSER_ARGS: &str = "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --enable-features=PlatformHEVCDecoderSupport";

#[derive(Debug, Clone)]
struct StandaloneMeta {
    pkg: String,
    title: String,
    always_on_top: bool,
}

pub struct DesktopState {
    pub trust_store: Arc<TrustStore>,
    pub prefs_store: Arc<PrefsStore>,
    pub tls_bridges: Arc<TlsBridgeRegistry>,
    pub mdns_scanner: Arc<MdnsScanner>,
    pub adb_manager: Arc<AdbManager>,
    pub log_store: Arc<LogStore>,
    pub feedback_manager: Arc<FeedbackManager>,
    standalone_meta: Mutex<HashMap<String, StandaloneMeta>>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopRuntimeInfo {
    pub platform: &'static str,
    pub arch: &'static str,
    pub version: &'static str,
    pub client_id: String,
    pub adb: AdbStatusInfo,
    pub prefs: ClientPrefs,
    pub max_standalone_windows: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StandaloneWindowInfo {
    pub label: String,
    pub pkg: String,
    pub title: String,
    pub always_on_top: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OpenStandaloneWindowResult {
    pub status: &'static str, // "opened" | "already_open" | "limit_reached"
    pub label: String,
    pub existing_title: String,
    pub active_count: usize,
    pub max_count: usize,
}

fn sanitize_standalone_label(pkg: &str) -> String {
    let sanitized: String = pkg
        .trim()
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '-'
            }
        })
        .collect();
    format!("{STANDALONE_LABEL_PREFIX}{sanitized}")
}

fn collect_live_standalone_windows(
    app: &tauri::AppHandle,
    state: &DesktopState,
) -> Vec<StandaloneWindowInfo> {
    let windows = app.webview_windows();
    let mut live_labels: Vec<String> = windows
        .keys()
        .filter(|lbl| lbl.starts_with(STANDALONE_LABEL_PREFIX))
        .cloned()
        .collect();
    live_labels.sort();

    let mut meta_guard = state
        .standalone_meta
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    meta_guard.retain(|k, _| windows.contains_key(k));

    live_labels
        .into_iter()
        .map(|label| {
            let meta = meta_guard.get(&label);
            let pkg = meta
                .map(|m| m.pkg.clone())
                .unwrap_or_else(|| label.trim_start_matches(STANDALONE_LABEL_PREFIX).to_string());
            let title = meta
                .map(|m| m.title.clone())
                .or_else(|| windows.get(&label).and_then(|w| w.title().ok()))
                .unwrap_or_else(|| pkg.clone());
            let always_on_top = meta
                .map(|m| m.always_on_top)
                .or_else(|| windows.get(&label).and_then(|w| w.is_always_on_top().ok()))
                .unwrap_or(false);
            StandaloneWindowInfo {
                label,
                pkg,
                title,
                always_on_top,
            }
        })
        .collect()
}

#[tauri::command]
fn desktop_runtime_info(state: tauri::State<'_, DesktopState>) -> DesktopRuntimeInfo {
    DesktopRuntimeInfo {
        platform: std::env::consts::OS,
        arch: std::env::consts::ARCH,
        version: env!("CARGO_PKG_VERSION"),
        client_id: state.trust_store.client_id(),
        adb: state.adb_manager.status(),
        prefs: state.prefs_store.get(),
        max_standalone_windows: MAX_STANDALONE_WINDOWS,
    }
}

#[tauri::command]
fn get_client_prefs(state: tauri::State<'_, DesktopState>) -> ClientPrefs {
    state.prefs_store.get()
}

#[tauri::command]
fn update_client_prefs(
    state: tauri::State<'_, DesktopState>,
    patch: ClientPrefsPatch,
) -> Result<ClientPrefs, String> {
    let adb_changed = patch.custom_adb_path.is_some();
    let saved = state.prefs_store.update(patch)?;
    if adb_changed {
        state.adb_manager.set_custom_adb_path(saved.custom_adb_path.clone());
    }
    Ok(saved)
}

#[tauri::command]
fn list_mdns_devices(state: tauri::State<'_, DesktopState>) -> Vec<DiscoveredMdnsDevice> {
    state.mdns_scanner.list_devices()
}

#[tauri::command]
fn refresh_mdns_devices(state: tauri::State<'_, DesktopState>) -> Vec<DiscoveredMdnsDevice> {
    state.mdns_scanner.refresh();
    state.log_store.info("mDNS", "Triggered manual mDNS scan");
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
    let saved = state.trust_store.upsert(device)?;
    state.log_store.info(
        "Trust",
        &format!("Upserted trusted device {} ({}:{})", saved.display_name, saved.host, saved.port),
    );
    Ok(saved)
}

#[tauri::command]
fn revoke_trusted_device(
    state: tauri::State<'_, DesktopState>,
    id: String,
) -> Result<(), String> {
    state.trust_store.revoke(&id)?;
    state.log_store.info("Trust", &format!("Revoked trusted device {id}"));
    Ok(())
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
    let info = state
        .tls_bridges
        .ensure_tunnel(&host, port, &path, &effective_pin)
        .await?;
    state.log_store.info(
        "TLS",
        &format!("TLS tunnel ready {}:{} path={} -> 127.0.0.1:{}", host, port, path, info.local_port),
    );
    Ok(info)
}

#[tauri::command]
fn get_adb_status(state: tauri::State<'_, DesktopState>) -> AdbStatusInfo {
    state.adb_manager.status()
}

#[tauri::command]
fn set_custom_adb_path(
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
    let res = state
        .adb_manager
        .bootstrap_and_forward(&serial, preferred_port)
        .await?;
    state.log_store.info(
        "ADB",
        &format!("ADB forward ready serial={} -> 127.0.0.1:{}", res.serial, res.local_port),
    );
    Ok(res)
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

// MARK: - Standalone Floating Windows (Max 3 Concurrent + Pin + Reload)

#[tauri::command]
async fn open_standalone_window(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    pkg: String,
    title: String,
    query: String,
    width: Option<f64>,
    height: Option<f64>,
    always_on_top: Option<bool>,
    force_reload: Option<bool>,
) -> Result<OpenStandaloneWindowResult, String> {
    let clean_pkg = pkg.trim().to_string();
    if clean_pkg.is_empty() {
        return Err("应用包名不能为空".into());
    }
    let label = sanitize_standalone_label(&clean_pkg);
    let pinned = always_on_top.unwrap_or(false);
    let should_reload = force_reload.unwrap_or(false);
    let display_title = if title.trim().is_empty() {
        clean_pkg.clone()
    } else {
        title.trim().to_string()
    };

    if let Some(existing) = app.get_webview_window(&label) {
        let live_count = collect_live_standalone_windows(&app, &state).len();
        if !should_reload {
            let existing_title = state
                .standalone_meta
                .lock()
                .ok()
                .and_then(|m| m.get(&label).map(|v| v.title.clone()))
                .unwrap_or_else(|| display_title.clone());
            return Ok(OpenStandaloneWindowResult {
                status: "already_open",
                label,
                existing_title,
                active_count: live_count,
                max_count: MAX_STANDALONE_WINDOWS,
            });
        }
        // Force-reload requested: destroy existing window cleanly before opening a fresh session.
        let _ = existing.destroy();
        if let Ok(mut meta) = state.standalone_meta.lock() {
            meta.remove(&label);
        }
        state.log_store.info("Standalone", &format!("Reloading standalone window pkg={clean_pkg}"));
        tokio::time::sleep(Duration::from_millis(240)).await;
    }

    let current_live = collect_live_standalone_windows(&app, &state);
    if current_live.len() >= MAX_STANDALONE_WINDOWS {
        return Ok(OpenStandaloneWindowResult {
            status: "limit_reached",
            label,
            existing_title: String::new(),
            active_count: current_live.len(),
            max_count: MAX_STANDALONE_WINDOWS,
        });
    }

    let clean_query = query.trim_start_matches('?');
    let target_url = if clean_query.is_empty() {
        "index.html".to_string()
    } else {
        format!("index.html?{clean_query}")
    };

    let win_w = width.unwrap_or(440.0).clamp(360.0, 1920.0);
    let win_h = height.unwrap_or(880.0).clamp(540.0, 1440.0);
    let win_title = format!("OpalDesk · {display_title}");

    let cascade_idx = current_live.len() as f64;
    let mut builder = WebviewWindowBuilder::new(&app, &label, WebviewUrl::App(target_url.into()))
        .title(win_title)
        .inner_size(win_w, win_h)
        .min_inner_size(360.0, 540.0)
        .resizable(true)
        .always_on_top(pinned)
        .disable_drag_drop_handler()
        .enable_clipboard_access();

    if cascade_idx > 0.0 {
        builder = builder.position(120.0 + cascade_idx * 48.0, 72.0 + cascade_idx * 32.0);
    } else {
        builder = builder.center();
    }

    #[cfg(target_os = "windows")]
    let builder = builder.additional_browser_args(WEBVIEW2_BROWSER_ARGS);

    builder
        .build()
        .map_err(|e| format!("创建独立应用窗口失败: {e}"))?;

    if let Ok(mut meta) = state.standalone_meta.lock() {
        meta.insert(
            label.clone(),
            StandaloneMeta {
                pkg: clean_pkg.clone(),
                title: display_title.clone(),
                always_on_top: pinned,
            },
        );
    }

    let next_count = collect_live_standalone_windows(&app, &state).len();
    state.log_store.info(
        "Standalone",
        &format!("Opened standalone window pkg={clean_pkg} pinned={pinned} ({next_count}/{MAX_STANDALONE_WINDOWS})"),
    );

    Ok(OpenStandaloneWindowResult {
        status: "opened",
        label,
        existing_title: display_title,
        active_count: next_count,
        max_count: MAX_STANDALONE_WINDOWS,
    })
}

#[tauri::command]
fn list_standalone_windows(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
) -> Vec<StandaloneWindowInfo> {
    collect_live_standalone_windows(&app, &state)
}

#[tauri::command]
fn focus_standalone_window(app: tauri::AppHandle, pkg_or_label: String) -> Result<bool, String> {
    let label = if pkg_or_label.starts_with(STANDALONE_LABEL_PREFIX) {
        pkg_or_label
    } else {
        sanitize_standalone_label(&pkg_or_label)
    };
    if let Some(win) = app.get_webview_window(&label) {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
        return Ok(true);
    }
    Ok(false)
}

#[tauri::command]
fn close_standalone_window(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    pkg_or_label: String,
) -> Result<(), String> {
    let label = if pkg_or_label.starts_with(STANDALONE_LABEL_PREFIX) {
        pkg_or_label
    } else {
        sanitize_standalone_label(&pkg_or_label)
    };
    if let Some(win) = app.get_webview_window(&label) {
        let _ = win.close();
    }
    if let Ok(mut meta) = state.standalone_meta.lock() {
        meta.remove(&label);
    }
    Ok(())
}

#[tauri::command]
fn close_all_standalone_windows(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
) -> Result<(), String> {
    let live = collect_live_standalone_windows(&app, &state);
    for win_info in live {
        if let Some(win) = app.get_webview_window(&win_info.label) {
            let _ = win.close();
        }
    }
    if let Ok(mut meta) = state.standalone_meta.lock() {
        meta.clear();
    }
    Ok(())
}

#[tauri::command]
fn set_current_window_always_on_top(
    window: tauri::WebviewWindow,
    state: tauri::State<'_, DesktopState>,
    always_on_top: bool,
) -> Result<bool, String> {
    window
        .set_always_on_top(always_on_top)
        .map_err(|e| format!("切换窗口置顶失败: {e}"))?;
    let label = window.label().to_string();
    if let Ok(mut meta) = state.standalone_meta.lock() {
        if let Some(entry) = meta.get_mut(&label) {
            entry.always_on_top = always_on_top;
        }
    }
    state.log_store.info(
        "Standalone",
        &format!("Set always_on_top={always_on_top} for window={label}"),
    );
    Ok(always_on_top)
}

#[tauri::command]
fn get_current_window_always_on_top(
    window: tauri::WebviewWindow,
    state: tauri::State<'_, DesktopState>,
) -> Result<bool, String> {
    if let Ok(val) = window.is_always_on_top() {
        return Ok(val);
    }
    let label = window.label().to_string();
    Ok(state
        .standalone_meta
        .lock()
        .ok()
        .and_then(|m| m.get(&label).map(|v| v.always_on_top))
        .unwrap_or(false))
}

#[tauri::command]
fn set_standalone_window_always_on_top(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    pkg_or_label: String,
    always_on_top: bool,
) -> Result<bool, String> {
    let label = if pkg_or_label.starts_with(STANDALONE_LABEL_PREFIX) {
        pkg_or_label
    } else {
        sanitize_standalone_label(&pkg_or_label)
    };
    let Some(win) = app.get_webview_window(&label) else {
        return Err("未找到目标悬浮小窗".into());
    };
    win.set_always_on_top(always_on_top)
        .map_err(|e| format!("设置小窗置顶失败: {e}"))?;
    if let Ok(mut meta) = state.standalone_meta.lock() {
        if let Some(entry) = meta.get_mut(&label) {
            entry.always_on_top = always_on_top;
        }
    }
    Ok(always_on_top)
}

#[tauri::command]
fn close_current_window(window: tauri::WebviewWindow) -> Result<(), String> {
    window.close().map_err(|e| format!("关闭窗口失败: {e}"))
}

// MARK: - Diagnostics Logs & Feature/Bug Feedback Commands

#[tauri::command]
fn append_client_log(
    state: tauri::State<'_, DesktopState>,
    level: String,
    tag: String,
    message: String,
) -> LogEntry {
    state.log_store.append(&level, &tag, &message)
}

#[tauri::command]
fn get_client_logs(state: tauri::State<'_, DesktopState>) -> Vec<LogEntry> {
    state.log_store.snapshot()
}

#[tauri::command]
fn clear_client_logs(state: tauri::State<'_, DesktopState>) {
    state.log_store.clear();
}

#[tauri::command]
fn build_diagnostic_report(
    state: tauri::State<'_, DesktopState>,
    context_summary: Option<String>,
    warnings_only: Option<bool>,
    query: Option<String>,
) -> String {
    let entries = state.log_store.snapshot();
    let filtered = logs::filter_entries(
        &entries,
        warnings_only.unwrap_or(false),
        query.as_deref().unwrap_or(""),
    );
    logs::build_diagnostic_report(
        env!("CARGO_PKG_VERSION"),
        &state.feedback_manager.device_summary(),
        context_summary.as_deref().unwrap_or(""),
        &filtered,
        &logs::format_now_datetime(),
    )
}

#[tauri::command]
fn export_diagnostic_report(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    content: String,
) -> Result<String, String> {
    let out_dir = app
        .path()
        .download_dir()
        .or_else(|_| app.path().document_dir())
        .or_else(|_| app.path().app_log_dir())
        .unwrap_or_else(|_| std::env::temp_dir());
    fs::create_dir_all(&out_dir).map_err(|e| format!("创建导出目录失败: {e}"))?;
    let file_name = format!("opaldesk_desktop_diag_{}.txt", logs::format_now_filestamp());
    let out_path = out_dir.join(file_name);
    fs::write(&out_path, content.as_bytes()).map_err(|e| format!("写入诊断日志文件失败: {e}"))?;
    let path_str = out_path.to_string_lossy().into_owned();
    state.log_store.info("Logs", &format!("Exported diagnostic report to {path_str}"));
    Ok(path_str)
}

#[tauri::command]
fn get_feedback_state(state: tauri::State<'_, DesktopState>) -> FeedbackStateSnapshot {
    state.feedback_manager.state_snapshot()
}

#[tauri::command]
async fn submit_feedback_report(
    state: tauri::State<'_, DesktopState>,
    reporter_name: String,
    problem_description: String,
    kind: String,
    include_logs: bool,
    context_summary: Option<String>,
) -> Result<ManualReportOutcome, String> {
    Ok(state
        .feedback_manager
        .submit_report(
            &reporter_name,
            &problem_description,
            &kind,
            include_logs,
            context_summary.as_deref().unwrap_or(""),
            &state.log_store,
        )
        .await)
}

#[tauri::command]
async fn refresh_feedback_statuses(
    state: tauri::State<'_, DesktopState>,
    force: Option<bool>,
) -> Result<FeedbackRefreshOutcome, String> {
    Ok(state
        .feedback_manager
        .refresh_statuses(force.unwrap_or(false), &state.log_store)
        .await)
}

#[tauri::command]
async fn lookup_feedback_by_code(
    state: tauri::State<'_, DesktopState>,
    code: String,
) -> Result<FeedbackLookupOutcome, String> {
    Ok(state
        .feedback_manager
        .lookup_by_code(&code, &state.log_store)
        .await)
}

#[tauri::command]
fn remove_feedback_record(
    state: tauri::State<'_, DesktopState>,
    event_id: String,
) -> Vec<FeedbackRecord> {
    state.feedback_manager.remove_record(&event_id)
}

#[tauri::command]
fn clear_feedback_history(state: tauri::State<'_, DesktopState>) -> Vec<FeedbackRecord> {
    state.feedback_manager.clear_history()
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
            let prefs_store = Arc::new(PrefsStore::new(config_dir.join("client-prefs.json")));
            let tls_bridges = Arc::new(TlsBridgeRegistry::new());
            let mdns_scanner = Arc::new(MdnsScanner::start());
            let adb_manager = Arc::new(AdbManager::new(resource_dir));
            let saved_prefs = prefs_store.get();
            if let Some(ref custom_adb) = saved_prefs.custom_adb_path {
                adb_manager.set_custom_adb_path(Some(custom_adb.clone()));
            }
            let log_store = Arc::new(LogStore::new(config_dir.join("logs").join("opaldesk_desktop.log")));
            let feedback_manager = Arc::new(FeedbackManager::new(&config_dir, trust_store.client_id()));

            log_store.info(
                "App",
                &format!(
                    "OpalDesk Desktop v{} initialized ({}/{})",
                    env!("CARGO_PKG_VERSION"),
                    std::env::consts::OS,
                    std::env::consts::ARCH
                ),
            );
            if let Some(ref custom_adb) = saved_prefs.custom_adb_path {
                log_store.info("ADB", &format!("Restored remembered custom ADB path: {custom_adb}"));
            }

            app.manage(DesktopState {
                trust_store,
                prefs_store,
                tls_bridges,
                mdns_scanner,
                adb_manager,
                log_store,
                feedback_manager,
                standalone_meta: Mutex::new(HashMap::new()),
            });

            let _ = setup_system_tray(app);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            desktop_runtime_info,
            get_client_prefs,
            update_client_prefs,
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
            list_standalone_windows,
            focus_standalone_window,
            close_standalone_window,
            close_all_standalone_windows,
            set_current_window_always_on_top,
            get_current_window_always_on_top,
            set_standalone_window_always_on_top,
            close_current_window,
            append_client_log,
            get_client_logs,
            clear_client_logs,
            build_diagnostic_report,
            export_diagnostic_report,
            get_feedback_state,
            submit_feedback_report,
            refresh_feedback_statuses,
            lookup_feedback_by_code,
            remove_feedback_record,
            clear_feedback_history,
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
