pub mod adb;
pub mod ble_nearby;
pub mod call_hud;
pub mod feedback;
pub mod file_io;
pub mod fingerprint;
pub mod i18n;
pub mod logs;
pub mod mdns;
pub mod prefs;
pub mod shortcuts;
pub mod tls_bridge;
pub mod tray_notify;
pub mod trust;
pub mod usb_tether;

use adb::{AdbDevice, AdbForwardResult, AdbManager, AdbStatusInfo};
use ble_nearby::{BleScanResult, TriggerBleHotspotRequest, TriggerBleHotspotResult};
use call_hud::CallHudManager;
use feedback::{
    FeedbackLookupOutcome, FeedbackManager, FeedbackRecord, FeedbackRefreshOutcome,
    FeedbackStateSnapshot, ManualReportOutcome,
};
use file_io::{
    BeginDownloadResult, FileDownloadManager, FinishDownloadResult, SaveBinaryFileResult,
};
use logs::{LogEntry, LogStore};
use mdns::{DiscoveredMdnsDevice, MdnsScanner};
use prefs::{ClientPrefs, ClientPrefsPatch, PrefsStore};
use serde::Serialize;
use shortcuts::{AppShortcutRecord, CreateAppShortcutRequest, ShortcutStore};
use std::collections::HashMap;
use std::fs;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, LogicalSize, Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tls_bridge::{LocalWsTunnelInfo, ProbedCertInfo, TlsBridgeRegistry};
use trust::{TrustStore, TrustedDevice};
use usb_tether::DiscoveredUsbTetherDevice;

pub const MAX_STANDALONE_WINDOWS: usize = 3;
const STANDALONE_LABEL_PREFIX: &str = "standalone-";
const MAIN_TRAY_ID: &str = "main-tray";

#[cfg(target_os = "windows")]
const WEBVIEW2_BROWSER_ARGS: &str = "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --enable-features=PlatformHEVCDecoderSupport";

#[derive(Debug, Clone)]
struct StandaloneMeta {
    pkg: String,
    app_key: String,
    user_id: u32,
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
    pub file_downloads: Arc<FileDownloadManager>,
    pub call_hud: Arc<CallHudManager>,
    pub shortcut_store: Arc<ShortcutStore>,
    pub mica_applied: bool,
    pending_deep_link: Mutex<Option<String>>,
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
    pub mica_applied: bool,
    pub initial_deep_link: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StandaloneWindowInfo {
    pub label: String,
    pub pkg: String,
    pub app_key: String,
    pub user_id: u32,
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
            let user_id = meta.map(|m| m.user_id).unwrap_or(0);
            let app_key = meta
                .map(|m| m.app_key.clone())
                .unwrap_or_else(|| if user_id > 0 { format!("{pkg}#u{user_id}") } else { pkg.clone() });
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
                app_key,
                user_id,
                title,
                always_on_top,
            }
        })
        .collect()
}

#[tauri::command]
fn desktop_runtime_info(state: tauri::State<'_, DesktopState>) -> DesktopRuntimeInfo {
    let initial_deep_link = state
        .pending_deep_link
        .lock()
        .ok()
        .and_then(|mut g| g.take());
    DesktopRuntimeInfo {
        platform: std::env::consts::OS,
        arch: std::env::consts::ARCH,
        version: env!("CARGO_PKG_VERSION"),
        client_id: state.trust_store.client_id(),
        adb: state.adb_manager.status(),
        prefs: state.prefs_store.get(),
        max_standalone_windows: MAX_STANDALONE_WINDOWS,
        mica_applied: state.mica_applied,
        initial_deep_link,
    }
}

#[tauri::command]
fn get_client_prefs(state: tauri::State<'_, DesktopState>) -> ClientPrefs {
    state.prefs_store.get()
}

#[tauri::command]
fn update_client_prefs(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    patch: ClientPrefsPatch,
) -> Result<ClientPrefs, String> {
    let adb_changed = patch.custom_adb_path.is_some();
    let lang_changed = patch.language.is_some();
    let saved = state.prefs_store.update(patch)?;
    if adb_changed {
        state.adb_manager.set_custom_adb_path(saved.custom_adb_path.clone());
    }
    if lang_changed {
        let locale = i18n::resolve_active_locale(&saved.language);
        if let Some(tray) = app.tray_by_id(MAIN_TRAY_ID) {
            if let Ok(menu) = build_tray_menu(&app, locale) {
                let _ = tray.set_menu(Some(menu));
            }
        }
        let _ = app.emit("opaldesk:language-changed", serde_json::json!({ "language": saved.language }));
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
    user_id: Option<u32>,
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
    let uid = user_id.unwrap_or(0);
    let app_key = if uid > 0 {
        format!("{clean_pkg}#u{uid}")
    } else {
        clean_pkg.clone()
    };
    let label = sanitize_standalone_label(&app_key);
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
                app_key: app_key.clone(),
                user_id: uid,
                title: display_title.clone(),
                always_on_top: pinned,
            },
        );
    }

    let next_count = collect_live_standalone_windows(&app, &state).len();
    state.log_store.info(
        "Standalone",
        &format!("Opened standalone window appKey={app_key} pinned={pinned} ({next_count}/{MAX_STANDALONE_WINDOWS})"),
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

// ==================== v1.2 / v1.3 Windows Parity Commands ====================

fn resolve_user_target_dir(app: &tauri::AppHandle, target_dir: &str) -> std::path::PathBuf {
    match target_dir.trim().to_ascii_lowercase().as_str() {
        "desktop" => app
            .path()
            .desktop_dir()
            .or_else(|_| app.path().download_dir())
            .unwrap_or_else(|_| std::env::temp_dir()),
        "temp" => std::env::temp_dir().join("OpalDeskPreview"),
        _ => app
            .path()
            .download_dir()
            .or_else(|_| app.path().desktop_dir())
            .unwrap_or_else(|_| std::env::temp_dir()),
    }
}

#[tauri::command]
fn save_binary_file(
    app: tauri::AppHandle,
    file_name: String,
    data: Vec<u8>,
    target_dir: Option<String>,
) -> Result<SaveBinaryFileResult, String> {
    let dir = resolve_user_target_dir(&app, target_dir.as_deref().unwrap_or("desktop"));
    file_io::save_binary_file_to_dir(&dir, &file_name, &data)
}

#[tauri::command]
fn begin_file_download(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    file_name: String,
    subdir: Option<String>,
) -> Result<BeginDownloadResult, String> {
    let dir = resolve_user_target_dir(&app, subdir.as_deref().unwrap_or("downloads"));
    state.file_downloads.begin_download(&dir, &file_name)
}

#[tauri::command]
fn write_file_download_chunk(
    state: tauri::State<'_, DesktopState>,
    session_id: String,
    data: Vec<u8>,
) -> Result<u64, String> {
    state.file_downloads.write_chunk(&session_id, &data)
}

#[tauri::command]
fn finish_file_download(
    state: tauri::State<'_, DesktopState>,
    session_id: String,
) -> Result<FinishDownloadResult, String> {
    state.file_downloads.finish_download(&session_id)
}

#[tauri::command]
fn abort_file_download(state: tauri::State<'_, DesktopState>, session_id: String) {
    state.file_downloads.abort_download(&session_id);
}

#[tauri::command]
fn reveal_in_explorer(path: String) -> Result<(), String> {
    file_io::reveal_in_explorer(&path)
}

#[tauri::command]
fn open_file_in_os(path: String) -> Result<(), String> {
    file_io::open_file_in_os(&path)
}

#[tauri::command]
fn set_current_window_size(
    window: tauri::WebviewWindow,
    width: f64,
    height: f64,
) -> Result<bool, String> {
    let w = width.clamp(340.0, 2560.0);
    let h = height.clamp(420.0, 1600.0);
    window
        .set_size(LogicalSize::new(w, h))
        .map_err(|e| format!("调整窗口尺寸失败: {e}"))?;
    Ok(true)
}

#[tauri::command]
fn update_call_hud_state(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    call_state: serde_json::Value,
) -> Result<bool, String> {
    state.call_hud.update_state(&app, call_state)
}

#[tauri::command]
fn get_call_hud_state(state: tauri::State<'_, DesktopState>) -> Option<serde_json::Value> {
    state.call_hud.current_state()
}

#[tauri::command]
fn hide_call_hud(app: tauri::AppHandle, state: tauri::State<'_, DesktopState>) -> bool {
    state.call_hud.hide(&app)
}

#[tauri::command]
fn dispatch_call_hud_action(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    action: serde_json::Value,
) -> Result<bool, String> {
    state.call_hud.dispatch_action(&app, action)
}

#[tauri::command]
fn send_native_notification(
    title: String,
    body: String,
    app_label: Option<String>,
    tag: Option<String>,
) -> Result<bool, String> {
    tray_notify::send_native_notification(
        &title,
        &body,
        app_label.as_deref().unwrap_or("OpalDesk"),
        tag.as_deref().unwrap_or(""),
    )
}

#[tauri::command]
fn update_tray_status(
    app: tauri::AppHandle,
    connected: bool,
    device_name: Option<String>,
    unread_count: Option<usize>,
    call_active: Option<bool>,
) -> bool {
    let tip = tray_notify::format_tray_tooltip(
        connected,
        device_name.as_deref().unwrap_or(""),
        unread_count.unwrap_or(0),
        call_active.unwrap_or(false),
    );
    if let Some(tray) = app.tray_by_id(MAIN_TRAY_ID) {
        let _ = tray.set_tooltip(Some(tip));
        return true;
    }
    false
}

#[tauri::command]
async fn create_app_shortcut(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    req: CreateAppShortcutRequest,
) -> Result<AppShortcutRecord, String> {
    let desktop_dir = resolve_user_target_dir(&app, "desktop");
    let store = Arc::clone(&state.shortcut_store);
    tokio::task::spawn_blocking(move || shortcuts::create_desktop_shortcut(&desktop_dir, &req, &store))
        .await
        .map_err(|e| format!("创建快捷方式任务异常: {e}"))?
}

#[tauri::command]
fn list_app_shortcuts(state: tauri::State<'_, DesktopState>) -> Vec<AppShortcutRecord> {
    state.shortcut_store.list()
}

#[tauri::command]
fn remove_app_shortcut(
    state: tauri::State<'_, DesktopState>,
    pkg: String,
) -> Result<bool, String> {
    state.shortcut_store.remove(&pkg)
}

#[tauri::command]
async fn register_url_protocol() -> Result<bool, String> {
    tokio::task::spawn_blocking(shortcuts::register_url_protocol)
        .await
        .map_err(|e| format!("注册协议任务异常: {e}"))?
}

#[tauri::command]
async fn scan_ble_nearby() -> Result<BleScanResult, String> {
    tokio::task::spawn_blocking(ble_nearby::scan_ble_nearby)
        .await
        .map_err(|e| format!("蓝牙扫描任务异常: {e}"))
}

#[tauri::command]
async fn trigger_ble_hotspot(req: TriggerBleHotspotRequest) -> Result<TriggerBleHotspotResult, String> {
    tokio::task::spawn_blocking(move || ble_nearby::trigger_ble_hotspot(&req))
        .await
        .map_err(|e| format!("蓝牙热点唤醒任务异常: {e}"))?
}

fn focus_main_window(app_handle: &tauri::AppHandle) {
    if let Some(win) = app_handle.get_webview_window("main") {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
    }
}

fn build_tray_menu(app: &tauri::AppHandle, locale: i18n::Locale) -> tauri::Result<Menu<tauri::Wry>> {
    let labels = i18n::tray_labels(locale);
    let show_item = MenuItem::with_id(app, "show_main", labels.show_main, true, None::<&str>)?;
    let companion_item = MenuItem::with_id(app, "connect_companion", labels.connect_companion, true, None::<&str>)?;
    let files_item = MenuItem::with_id(app, "open_files", labels.open_files, true, None::<&str>)?;
    let storage_item = MenuItem::with_id(app, "open_storage", labels.open_storage, true, None::<&str>)?;
    let dialer_item = MenuItem::with_id(app, "open_dialer", labels.open_dialer, true, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, "quit_app", labels.quit_app, true, None::<&str>)?;
    Menu::with_items(
        app,
        &[
            &show_item,
            &companion_item,
            &files_item,
            &storage_item,
            &dialer_item,
            &quit_item,
        ],
    )
}

fn setup_system_tray(app: &tauri::App, language_pref: &str) -> tauri::Result<()> {
    let locale = i18n::resolve_active_locale(language_pref);
    let labels = i18n::tray_labels(locale);
    let menu = build_tray_menu(app.handle(), locale)?;

    let mut tray = TrayIconBuilder::with_id(MAIN_TRAY_ID)
        .menu(&menu)
        .tooltip(labels.idle_tooltip)
        .show_menu_on_left_click(false)
        .on_menu_event(|app_handle, event| match event.id.as_ref() {
            "show_main" => focus_main_window(app_handle),
            "connect_companion" | "open_files" | "open_storage" | "open_dialer" => {
                focus_main_window(app_handle);
                let _ = app_handle.emit_to(
                    "main",
                    "opaldesk:tray-action",
                    serde_json::json!({ "action": event.id.as_ref() }),
                );
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
                focus_main_window(tray_icon.app_handle());
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
    let cli_args: Vec<String> = std::env::args().collect();
    let initial_deep_link = shortcuts::extract_deep_link_arg(&cli_args);

    let app = tauri::Builder::default()
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    let hide_to_tray = window
                        .app_handle()
                        .try_state::<DesktopState>()
                        .map(|s| s.prefs_store.get().hide_to_tray_on_close)
                        .unwrap_or(true);
                    if hide_to_tray {
                        api.prevent_close();
                        let _ = window.hide();
                    }
                }
            }
        })
        .setup(move |app| {
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
            let file_downloads = Arc::new(FileDownloadManager::new());
            let call_hud = Arc::new(CallHudManager::new());
            let shortcut_store = Arc::new(ShortcutStore::new(config_dir.join("shortcuts.json")));

            let mica_applied = app
                .get_webview_window("main")
                .map(|win| tray_notify::apply_window_mica_effect(&win))
                .unwrap_or(false);

            log_store.info(
                "App",
                &format!(
                    "OpalDesk Desktop v{} initialized ({}/{}) mica={}",
                    env!("CARGO_PKG_VERSION"),
                    std::env::consts::OS,
                    std::env::consts::ARCH,
                    mica_applied
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
                file_downloads,
                call_hud,
                shortcut_store,
                mica_applied,
                pending_deep_link: Mutex::new(initial_deep_link),
                standalone_meta: Mutex::new(HashMap::new()),
            });

            let _ = setup_system_tray(app, &saved_prefs.language);
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
            save_binary_file,
            begin_file_download,
            write_file_download_chunk,
            finish_file_download,
            abort_file_download,
            reveal_in_explorer,
            open_file_in_os,
            set_current_window_size,
            update_call_hud_state,
            get_call_hud_state,
            hide_call_hud,
            dispatch_call_hud_action,
            send_native_notification,
            update_tray_status,
            create_app_shortcut,
            list_app_shortcuts,
            remove_app_shortcut,
            register_url_protocol,
            scan_ble_nearby,
            trigger_ble_hotspot,
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
