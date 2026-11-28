use crate::file_io::{self, BeginDownloadResult, FinishDownloadResult, SaveBinaryFileResult};
use crate::shortcuts::{self, AppShortcutRecord, CreateAppShortcutRequest};
use crate::DesktopState;
use std::sync::Arc;
use tauri::Manager;

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
pub fn save_binary_file(
    app: tauri::AppHandle,
    file_name: String,
    data: Vec<u8>,
    target_dir: Option<String>,
) -> Result<SaveBinaryFileResult, String> {
    let dir = resolve_user_target_dir(&app, target_dir.as_deref().unwrap_or("desktop"));
    file_io::save_binary_file_to_dir(&dir, &file_name, &data)
}

#[tauri::command]
pub fn begin_file_download(
    app: tauri::AppHandle,
    state: tauri::State<'_, DesktopState>,
    file_name: String,
    subdir: Option<String>,
) -> Result<BeginDownloadResult, String> {
    let dir = resolve_user_target_dir(&app, subdir.as_deref().unwrap_or("downloads"));
    state.file_downloads.begin_download(&dir, &file_name)
}

#[tauri::command]
pub fn write_file_download_chunk(
    state: tauri::State<'_, DesktopState>,
    session_id: String,
    data: Vec<u8>,
) -> Result<u64, String> {
    state.file_downloads.write_chunk(&session_id, &data)
}

#[tauri::command]
pub fn finish_file_download(
    state: tauri::State<'_, DesktopState>,
    session_id: String,
) -> Result<FinishDownloadResult, String> {
    state.file_downloads.finish_download(&session_id)
}

#[tauri::command]
pub fn abort_file_download(state: tauri::State<'_, DesktopState>, session_id: String) {
    state.file_downloads.abort_download(&session_id);
}

#[tauri::command]
pub fn reveal_in_explorer(path: String) -> Result<(), String> {
    file_io::reveal_in_explorer(&path)
}

#[tauri::command]
pub fn open_file_in_os(path: String) -> Result<(), String> {
    file_io::open_file_in_os(&path)
}

#[tauri::command]
pub async fn create_app_shortcut(
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
pub fn list_app_shortcuts(state: tauri::State<'_, DesktopState>) -> Vec<AppShortcutRecord> {
    state.shortcut_store.list()
}

#[tauri::command]
pub fn remove_app_shortcut(
    state: tauri::State<'_, DesktopState>,
    pkg: String,
) -> Result<bool, String> {
    state.shortcut_store.remove(&pkg)
}

#[tauri::command]
pub async fn register_url_protocol() -> Result<bool, String> {
    tokio::task::spawn_blocking(shortcuts::register_url_protocol)
        .await
        .map_err(|e| format!("注册协议任务异常: {e}"))?
}
