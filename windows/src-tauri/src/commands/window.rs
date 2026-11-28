use crate::tray_notify;
#[cfg(target_os = "windows")]
use crate::WEBVIEW2_BROWSER_ARGS;
use crate::{
    DesktopState, OpenStandaloneWindowResult, StandaloneMeta, StandaloneWindowInfo, MAIN_TRAY_ID,
    MAX_STANDALONE_WINDOWS, STANDALONE_LABEL_PREFIX,
};
use std::time::Duration;
use tauri::{LogicalSize, Manager, WebviewUrl, WebviewWindowBuilder};

pub(crate) fn sanitize_standalone_label(pkg: &str) -> String {
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

pub(crate) fn collect_live_standalone_windows(
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
            let app_key = meta.map(|m| m.app_key.clone()).unwrap_or_else(|| {
                if user_id > 0 {
                    format!("{pkg}#u{user_id}")
                } else {
                    pkg.clone()
                }
            });
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
pub async fn open_standalone_window(
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
        let _ = existing.destroy();
        if let Ok(mut meta) = state.standalone_meta.lock() {
            meta.remove(&label);
        }
        state
            .log_store
            .info("Standalone", &format!("Reloading standalone window pkg={clean_pkg}"));
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
pub fn list_standalone_windows(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
) -> Vec<StandaloneWindowInfo> {
    collect_live_standalone_windows(&app, &state)
}

#[tauri::command]
pub fn focus_standalone_window(
    app: tauri::AppHandle,
    pkg_or_label: String,
) -> Result<bool, String> {
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
pub fn close_standalone_window(
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
pub fn close_all_standalone_windows(
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
pub fn set_current_window_always_on_top(
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
pub fn get_current_window_always_on_top(
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
pub fn set_standalone_window_always_on_top(
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
pub fn close_current_window(window: tauri::WebviewWindow) -> Result<(), String> {
    window.close().map_err(|e| format!("关闭窗口失败: {e}"))
}

#[tauri::command]
pub fn set_current_window_size(
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
pub fn update_call_hud_state(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    call_state: serde_json::Value,
) -> Result<bool, String> {
    state.call_hud.update_state(&app, call_state)
}

#[tauri::command]
pub fn get_call_hud_state(state: tauri::State<'_, DesktopState>) -> Option<serde_json::Value> {
    state.call_hud.current_state()
}

#[tauri::command]
pub fn hide_call_hud(app: tauri::AppHandle, state: tauri::State<'_, DesktopState>) -> bool {
    state.call_hud.hide(&app)
}

#[tauri::command]
pub fn dispatch_call_hud_action(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    action: serde_json::Value,
) -> Result<bool, String> {
    state.call_hud.dispatch_action(&app, action)
}

#[tauri::command]
pub fn send_native_notification(
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
pub fn update_tray_status(
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
