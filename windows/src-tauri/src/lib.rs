pub mod adb;
pub mod ble_nearby;
pub mod call_hud;
pub mod commands;
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

use adb::{AdbManager, AdbStatusInfo};
use call_hud::CallHudManager;
use feedback::FeedbackManager;
use file_io::FileDownloadManager;
use logs::LogStore;
use mdns::MdnsScanner;
use prefs::{ClientPrefs, PrefsStore};
use serde::Serialize;
use shortcuts::ShortcutStore;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, Manager, WindowEvent};
use tls_bridge::TlsBridgeRegistry;
use trust::TrustStore;

pub const MAX_STANDALONE_WINDOWS: usize = 3;
pub(crate) const STANDALONE_LABEL_PREFIX: &str = "standalone-";
pub(crate) const MAIN_TRAY_ID: &str = "main-tray";

#[cfg(target_os = "windows")]
pub(crate) const WEBVIEW2_BROWSER_ARGS: &str = "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --enable-features=PlatformHEVCDecoderSupport";

#[derive(Debug, Clone)]
pub(crate) struct StandaloneMeta {
    pub pkg: String,
    pub app_key: String,
    pub user_id: u32,
    pub title: String,
    pub always_on_top: bool,
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
    pub(crate) pending_deep_link: Mutex<Option<String>>,
    pub(crate) standalone_meta: Mutex<HashMap<String, StandaloneMeta>>,
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

fn focus_main_window(app_handle: &tauri::AppHandle) {
    if let Some(win) = app_handle.get_webview_window("main") {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
    }
}

pub(crate) fn build_tray_menu(
    app: &tauri::AppHandle,
    locale: i18n::Locale,
) -> tauri::Result<Menu<tauri::Wry>> {
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
            commands::prefs::desktop_runtime_info,
            commands::prefs::get_client_prefs,
            commands::prefs::update_client_prefs,
            commands::device::list_mdns_devices,
            commands::device::refresh_mdns_devices,
            commands::device::list_trusted_devices,
            commands::device::upsert_trusted_device,
            commands::device::revoke_trusted_device,
            commands::device::probe_tls_certificate,
            commands::device::start_tls_ws_tunnel,
            commands::device::get_adb_status,
            commands::device::set_custom_adb_path,
            commands::device::list_adb_devices,
            commands::device::scan_usb_tether_devices,
            commands::device::adb_bootstrap_and_forward,
            commands::device::adb_pair_wireless,
            commands::device::adb_connect_wireless,
            commands::window::open_standalone_window,
            commands::window::list_standalone_windows,
            commands::window::focus_standalone_window,
            commands::window::close_standalone_window,
            commands::window::close_all_standalone_windows,
            commands::window::set_current_window_always_on_top,
            commands::window::get_current_window_always_on_top,
            commands::window::set_standalone_window_always_on_top,
            commands::window::close_current_window,
            commands::prefs::append_client_log,
            commands::prefs::get_client_logs,
            commands::prefs::clear_client_logs,
            commands::prefs::build_diagnostic_report,
            commands::prefs::export_diagnostic_report,
            commands::prefs::get_feedback_state,
            commands::prefs::submit_feedback_report,
            commands::prefs::refresh_feedback_statuses,
            commands::prefs::lookup_feedback_by_code,
            commands::prefs::remove_feedback_record,
            commands::prefs::clear_feedback_history,
            commands::files::save_binary_file,
            commands::files::begin_file_download,
            commands::files::write_file_download_chunk,
            commands::files::finish_file_download,
            commands::files::abort_file_download,
            commands::files::reveal_in_explorer,
            commands::files::open_file_in_os,
            commands::window::set_current_window_size,
            commands::window::update_call_hud_state,
            commands::window::get_call_hud_state,
            commands::window::hide_call_hud,
            commands::window::dispatch_call_hud_action,
            commands::window::send_native_notification,
            commands::window::update_tray_status,
            commands::files::create_app_shortcut,
            commands::files::list_app_shortcuts,
            commands::files::remove_app_shortcut,
            commands::files::register_url_protocol,
            commands::device::scan_ble_nearby,
            commands::device::trigger_ble_hotspot,
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
