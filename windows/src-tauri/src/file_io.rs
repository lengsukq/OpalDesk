use serde::Serialize;
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveBinaryFileResult {
    pub saved_path: String,
    pub bytes_written: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BeginDownloadResult {
    pub session_id: String,
    pub saved_path: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FinishDownloadResult {
    pub saved_path: String,
    pub bytes_written: u64,
}

struct ActiveDownload {
    path: PathBuf,
    file: File,
    bytes_written: u64,
}

pub struct FileDownloadManager {
    seq: AtomicU64,
    active: Mutex<HashMap<String, ActiveDownload>>,
}

impl Default for FileDownloadManager {
    fn default() -> Self {
        Self::new()
    }
}

impl FileDownloadManager {
    pub fn new() -> Self {
        Self {
            seq: AtomicU64::new(1),
            active: Mutex::new(HashMap::new()),
        }
    }

    pub fn begin_download(&self, base_dir: &Path, raw_name: &str) -> Result<BeginDownloadResult, String> {
        fs::create_dir_all(base_dir).map_err(|e| format!("无法创建下载目录: {e}"))?;
        let clean_name = sanitize_filename(raw_name);
        let target_path = resolve_unique_path(base_dir, &clean_name);
        let file = File::create(&target_path).map_err(|e| format!("无法创建本地文件: {e}"))?;
        let id = format!("dl-{}", self.seq.fetch_add(1, Ordering::Relaxed));
        let path_str = target_path.to_string_lossy().into_owned();

        let mut guard = self.active.lock().map_err(|_| "下载会话锁异常".to_string())?;
        guard.insert(
            id.clone(),
            ActiveDownload {
                path: target_path,
                file,
                bytes_written: 0,
            },
        );
        Ok(BeginDownloadResult {
            session_id: id,
            saved_path: path_str,
        })
    }

    pub fn write_chunk(&self, session_id: &str, data: &[u8]) -> Result<u64, String> {
        let mut guard = self.active.lock().map_err(|_| "下载会话锁异常".to_string())?;
        let entry = guard
            .get_mut(session_id)
            .ok_or_else(|| format!("下载会话不存在: {session_id}"))?;
        if !data.is_empty() {
            entry
                .file
                .write_all(data)
                .map_err(|e| format!("写入下载分块失败: {e}"))?;
            entry.bytes_written = entry.bytes_written.saturating_add(data.len() as u64);
        }
        Ok(entry.bytes_written)
    }

    pub fn finish_download(&self, session_id: &str) -> Result<FinishDownloadResult, String> {
        let mut guard = self.active.lock().map_err(|_| "下载会话锁异常".to_string())?;
        let mut entry = guard
            .remove(session_id)
            .ok_or_else(|| format!("下载会话不存在: {session_id}"))?;
        entry.file.flush().map_err(|e| format!("刷新文件缓冲失败: {e}"))?;
        Ok(FinishDownloadResult {
            saved_path: entry.path.to_string_lossy().into_owned(),
            bytes_written: entry.bytes_written,
        })
    }

    pub fn abort_download(&self, session_id: &str) {
        if let Ok(mut guard) = self.active.lock() {
            if let Some(entry) = guard.remove(session_id) {
                drop(entry.file);
                let _ = fs::remove_file(entry.path);
            }
        }
    }
}

/// Strips directory separators, traversal segments, and Windows-illegal filename characters.
pub fn sanitize_filename(raw: &str) -> String {
    let base = raw
        .trim()
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or("opaldesk_file.bin");
    let cleaned: String = base
        .chars()
        .filter(|&c| {
            !matches!(
                c,
                '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' | '\0'..='\x1f'
            )
        })
        .collect();
    let trimmed = cleaned.trim().trim_matches('.');
    if trimmed.is_empty() {
        "opaldesk_file.bin".to_string()
    } else {
        trimmed.chars().take(180).collect()
    }
}

/// Resolves a collision-free file path inside `dir` (`name.ext` -> `name (1).ext`).
pub fn resolve_unique_path(dir: &Path, clean_name: &str) -> PathBuf {
    let initial = dir.join(clean_name);
    if !initial.exists() {
        return initial;
    }
    let stem = Path::new(clean_name)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or(clean_name);
    let ext = Path::new(clean_name)
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("");
    for idx in 1..=999 {
        let candidate_name = if ext.is_empty() {
            format!("{stem} ({idx})")
        } else {
            format!("{stem} ({idx}).{ext}")
        };
        let cand = dir.join(candidate_name);
        if !cand.exists() {
            return cand;
        }
    }
    dir.join(format!(
        "{stem}_{}.{}",
        std::process::id(),
        if ext.is_empty() { "bin" } else { ext }
    ))
}

pub fn save_binary_file_to_dir(
    dir: &Path,
    raw_name: &str,
    data: &[u8],
) -> Result<SaveBinaryFileResult, String> {
    fs::create_dir_all(dir).map_err(|e| format!("无法创建目标目录: {e}"))?;
    let clean = sanitize_filename(raw_name);
    let target = resolve_unique_path(dir, &clean);
    fs::write(&target, data).map_err(|e| format!("写入文件失败: {e}"))?;
    Ok(SaveBinaryFileResult {
        saved_path: target.to_string_lossy().into_owned(),
        bytes_written: data.len() as u64,
    })
}

pub fn reveal_in_explorer(raw_path: &str) -> Result<(), String> {
    let path = PathBuf::from(raw_path.trim());
    if !path.exists() {
        return Err("文件不存在或已被移动".to_string());
    }
    #[cfg(target_os = "windows")]
    {
        let arg = format!("/select,{}", path.to_string_lossy());
        let mut cmd = Command::new("explorer.exe");
        cmd.arg(arg).creation_flags(CREATE_NO_WINDOW);
        cmd.spawn()
            .map_err(|e| format!("无法打开资源管理器: {e}"))?;
        return Ok(());
    }
    #[cfg(target_os = "macos")]
    {
        Command::new("open")
            .arg("-R")
            .arg(&path)
            .spawn()
            .map_err(|e| format!("无法打开 Finder: {e}"))?;
        return Ok(());
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        let dir = path.parent().unwrap_or(&path);
        Command::new("xdg-open")
            .arg(dir)
            .spawn()
            .map_err(|e| format!("无法打开文件管理器: {e}"))?;
        Ok(())
    }
}

pub fn open_file_in_os(raw_path: &str) -> Result<(), String> {
    let path = PathBuf::from(raw_path.trim());
    if !path.exists() {
        return Err("文件不存在或已被移动".to_string());
    }
    #[cfg(target_os = "windows")]
    {
        let mut cmd = Command::new("cmd");
        cmd.args(["/C", "start", ""])
            .arg(&path)
            .creation_flags(CREATE_NO_WINDOW);
        cmd.spawn()
            .map_err(|e| format!("无法调用系统应用打开文件: {e}"))?;
        return Ok(());
    }
    #[cfg(target_os = "macos")]
    {
        Command::new("open")
            .arg(&path)
            .spawn()
            .map_err(|e| format!("无法打开文件: {e}"))?;
        return Ok(());
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        Command::new("xdg-open")
            .arg(&path)
            .spawn()
            .map_err(|e| format!("无法打开文件: {e}"))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_dir(tag: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        std::env::temp_dir().join(format!("opaldesk_file_io_{tag}_{nanos}"))
    }

    #[test]
    fn sanitizes_traversal_and_illegal_chars() {
        assert_eq!(sanitize_filename("../../evil:file?.png"), "evilfile.png");
        assert_eq!(sanitize_filename(r"C:\Temp\my<doc>.pdf"), "mydoc.pdf");
        assert_eq!(sanitize_filename("..."), "opaldesk_file.bin");
    }

    #[test]
    fn deduplicates_colliding_filenames_and_streams_chunks() {
        let dir = temp_dir("stream");
        let first = save_binary_file_to_dir(&dir, "shot.png", b"abc").expect("save first");
        let second = save_binary_file_to_dir(&dir, "shot.png", b"defg").expect("save second");
        assert_ne!(first.saved_path, second.saved_path);
        assert!(second.saved_path.ends_with("shot (1).png"));

        let mgr = FileDownloadManager::new();
        let begun = mgr.begin_download(&dir, "report.txt").expect("begin");
        assert_eq!(mgr.write_chunk(&begun.session_id, b"hello ").expect("w1"), 6);
        assert_eq!(mgr.write_chunk(&begun.session_id, b"world").expect("w2"), 11);
        let done = mgr.finish_download(&begun.session_id).expect("finish");
        assert_eq!(done.bytes_written, 11);
        assert_eq!(fs::read_to_string(&done.saved_path).expect("read"), "hello world");

        let aborted = mgr.begin_download(&dir, "partial.bin").expect("begin abort");
        let _ = mgr.write_chunk(&aborted.session_id, b"123");
        mgr.abort_download(&aborted.session_id);
        assert!(!Path::new(&aborted.saved_path).exists());

        let _ = fs::remove_dir_all(&dir);
    }
}
