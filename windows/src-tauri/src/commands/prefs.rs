use crate::feedback::{
    FeedbackLookupOutcome, FeedbackRecord, FeedbackRefreshOutcome, FeedbackStateSnapshot,
    ManualReportOutcome,
};
use crate::i18n;
use crate::logs::{self, LogEntry};
use crate::prefs::{ClientPrefs, ClientPrefsPatch};
use crate::{
    build_tray_menu, DesktopRuntimeInfo, DesktopState, MAIN_TRAY_ID, MAX_STANDALONE_WINDOWS,
};
use std::fs;
use tauri::{Emitter, Manager};

#[tauri::command]
pub fn desktop_runtime_info(state: tauri::State<'_, DesktopState>) -> DesktopRuntimeInfo {
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
pub fn get_client_prefs(state: tauri::State<'_, DesktopState>) -> ClientPrefs {
    state.prefs_store.get()
}

#[tauri::command]
pub fn update_client_prefs(
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
        let _ = app.emit(
            "opaldesk:language-changed",
            serde_json::json!({ "language": saved.language }),
        );
    }
    Ok(saved)
}

#[tauri::command]
pub fn append_client_log(
    state: tauri::State<'_, DesktopState>,
    level: String,
    tag: String,
    message: String,
) -> LogEntry {
    state.log_store.append(&level, &tag, &message)
}

#[tauri::command]
pub fn get_client_logs(state: tauri::State<'_, DesktopState>) -> Vec<LogEntry> {
    state.log_store.snapshot()
}

#[tauri::command]
pub fn clear_client_logs(state: tauri::State<'_, DesktopState>) {
    state.log_store.clear();
}

#[tauri::command]
pub fn build_diagnostic_report(
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
pub fn export_diagnostic_report(
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
    state
        .log_store
        .info("Logs", &format!("Exported diagnostic report to {path_str}"));
    Ok(path_str)
}

#[tauri::command]
pub fn get_feedback_state(state: tauri::State<'_, DesktopState>) -> FeedbackStateSnapshot {
    state.feedback_manager.state_snapshot()
}

#[tauri::command]
pub async fn submit_feedback_report(
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
pub async fn refresh_feedback_statuses(
    state: tauri::State<'_, DesktopState>,
    force: Option<bool>,
) -> Result<FeedbackRefreshOutcome, String> {
    Ok(state
        .feedback_manager
        .refresh_statuses(force.unwrap_or(false), &state.log_store)
        .await)
}

#[tauri::command]
pub async fn lookup_feedback_by_code(
    state: tauri::State<'_, DesktopState>,
    code: String,
) -> Result<FeedbackLookupOutcome, String> {
    Ok(state
        .feedback_manager
        .lookup_by_code(&code, &state.log_store)
        .await)
}

#[tauri::command]
pub fn remove_feedback_record(
    state: tauri::State<'_, DesktopState>,
    event_id: String,
) -> Vec<FeedbackRecord> {
    state.feedback_manager.remove_record(&event_id)
}

#[tauri::command]
pub fn clear_feedback_history(state: tauri::State<'_, DesktopState>) -> Vec<FeedbackRecord> {
    state.feedback_manager.clear_history()
}
