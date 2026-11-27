use crate::adb::sanitize_custom_adb_path;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

const PREFS_SCHEMA_VERSION: u32 = 1;
const MIN_BITRATE_KBPS: u32 = 2_500;
const MAX_BITRATE_KBPS: u32 = 50_000;

fn default_schema_version() -> u32 {
    PREFS_SCHEMA_VERSION
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ClientPrefs {
    #[serde(default = "default_schema_version")]
    pub version: u32,
    #[serde(default)]
    pub custom_adb_path: Option<String>,
    #[serde(default)]
    pub preferred_codec: String,
    #[serde(default)]
    pub target_fps: u16,
    #[serde(default)]
    pub bitrate_kbps: u32,
    #[serde(default = "default_true")]
    pub audio_enabled: bool,
    #[serde(default)]
    pub hide_local_cursor: bool,
}

impl Default for ClientPrefs {
    fn default() -> Self {
        Self {
            version: PREFS_SCHEMA_VERSION,
            custom_adb_path: None,
            preferred_codec: String::new(),
            target_fps: 0,
            bitrate_kbps: 0,
            audio_enabled: true,
            hide_local_cursor: false,
        }
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientPrefsPatch {
    pub custom_adb_path: Option<Option<String>>,
    pub preferred_codec: Option<String>,
    pub target_fps: Option<u16>,
    pub bitrate_kbps: Option<u32>,
    pub audio_enabled: Option<bool>,
    pub hide_local_cursor: Option<bool>,
}

fn sanitize_codec(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "h264" => "h264".to_string(),
        "hevc" => "hevc".to_string(),
        _ => String::new(),
    }
}

fn sanitize_fps(fps: u16) -> u16 {
    if fps == 0 || (30..=165).contains(&fps) {
        fps
    } else {
        0
    }
}

fn sanitize_bitrate(kbps: u32) -> u32 {
    if kbps == 0 {
        0
    } else {
        kbps.clamp(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)
    }
}

fn sanitize_prefs(mut prefs: ClientPrefs) -> ClientPrefs {
    prefs.version = PREFS_SCHEMA_VERSION;
    prefs.custom_adb_path = sanitize_custom_adb_path(prefs.custom_adb_path);
    prefs.preferred_codec = sanitize_codec(&prefs.preferred_codec);
    prefs.target_fps = sanitize_fps(prefs.target_fps);
    prefs.bitrate_kbps = sanitize_bitrate(prefs.bitrate_kbps);
    prefs
}

pub struct PrefsStore {
    path: PathBuf,
    inner: Mutex<ClientPrefs>,
}

impl PrefsStore {
    pub fn new(path: PathBuf) -> Self {
        let loaded = Self::load_or_default(&path);
        Self {
            path,
            inner: Mutex::new(loaded),
        }
    }

    pub fn get(&self) -> ClientPrefs {
        self.inner
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default()
    }

    pub fn set_custom_adb_path(&self, path: Option<String>) -> Result<ClientPrefs, String> {
        self.update(ClientPrefsPatch {
            custom_adb_path: Some(path),
            ..ClientPrefsPatch::default()
        })
    }

    pub fn update(&self, patch: ClientPrefsPatch) -> Result<ClientPrefs, String> {
        let mut guard = self
            .inner
            .lock()
            .map_err(|_| "客户端配置锁异常".to_string())?;

        if let Some(next_adb) = patch.custom_adb_path {
            guard.custom_adb_path = sanitize_custom_adb_path(next_adb);
        }
        if let Some(next_codec) = patch.preferred_codec {
            guard.preferred_codec = sanitize_codec(&next_codec);
        }
        if let Some(next_fps) = patch.target_fps {
            guard.target_fps = sanitize_fps(next_fps);
        }
        if let Some(next_bitrate) = patch.bitrate_kbps {
            guard.bitrate_kbps = sanitize_bitrate(next_bitrate);
        }
        if let Some(next_audio) = patch.audio_enabled {
            guard.audio_enabled = next_audio;
        }
        if let Some(next_hide_cursor) = patch.hide_local_cursor {
            guard.hide_local_cursor = next_hide_cursor;
        }
        guard.version = PREFS_SCHEMA_VERSION;

        let snapshot = guard.clone();
        Self::save_to_disk(&self.path, &snapshot)?;
        Ok(snapshot)
    }

    fn load_or_default(path: &Path) -> ClientPrefs {
        if let Ok(raw) = fs::read_to_string(path) {
            if let Ok(parsed) = serde_json::from_str::<ClientPrefs>(&raw) {
                return sanitize_prefs(parsed);
            }
        }
        ClientPrefs::default()
    }

    fn save_to_disk(path: &Path, prefs: &ClientPrefs) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("无法创建配置目录: {e}"))?;
        }
        let body = serde_json::to_string_pretty(prefs).map_err(|e| format!("序列化配置失败: {e}"))?;
        let tmp_path = path.with_extension(format!("json.{}.tmp", std::process::id()));
        fs::write(&tmp_path, body.as_bytes()).map_err(|e| format!("写入临时配置文件失败: {e}"))?;

        if fs::rename(&tmp_path, path).is_err() {
            // Windows fs::rename does not overwrite an existing destination file; copy over
            // in-place so the previous client-prefs.json is never lost if replacement fails.
            if let Err(err) = fs::copy(&tmp_path, path) {
                let _ = fs::remove_file(&tmp_path);
                return Err(format!("替换配置文件失败: {err}"));
            }
            let _ = fs::remove_file(&tmp_path);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_prefs_path(name: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        std::env::temp_dir().join(format!("opaldesk_prefs_{name}_{nanos}.json"))
    }

    #[test]
    fn persists_codec_fps_bitrate_adb_and_cursor_across_restart() {
        let path = temp_prefs_path("roundtrip");
        let store = PrefsStore::new(path.clone());
        let updated = store
            .update(ClientPrefsPatch {
                custom_adb_path: Some(Some(r#" "C:\Android\platform-tools\adb.exe" "#.to_string())),
                preferred_codec: Some("HEVC".to_string()),
                target_fps: Some(120),
                bitrate_kbps: Some(22_000),
                audio_enabled: Some(false),
                hide_local_cursor: Some(true),
            })
            .expect("save prefs");

        assert_eq!(
            updated.custom_adb_path.as_deref(),
            Some(r"C:\Android\platform-tools\adb.exe")
        );
        assert_eq!(updated.preferred_codec, "hevc");
        assert_eq!(updated.target_fps, 120);
        assert_eq!(updated.bitrate_kbps, 22_000);
        assert!(!updated.audio_enabled);
        assert!(updated.hide_local_cursor);

        // Reload from disk simulating full process restart
        let reloaded = PrefsStore::new(path.clone()).get();
        assert_eq!(reloaded, updated);

        // Clearing custom ADB path persists None without clobbering video prefs
        let after_clear = store.set_custom_adb_path(Some("   ".to_string())).expect("clear adb");
        assert_eq!(after_clear.custom_adb_path, None);
        assert_eq!(after_clear.preferred_codec, "hevc");
        assert_eq!(after_clear.target_fps, 120);

        let _ = fs::remove_file(path);
    }

    #[test]
    fn sanitizes_invalid_values_safely() {
        let path = temp_prefs_path("sanitize");
        let store = PrefsStore::new(path.clone());
        let saved = store
            .update(ClientPrefsPatch {
                custom_adb_path: None,
                preferred_codec: Some("vp9".to_string()),
                target_fps: Some(999),
                bitrate_kbps: Some(100_000),
                audio_enabled: None,
                hide_local_cursor: None,
            })
            .expect("sanitize save");

        assert_eq!(saved.preferred_codec, "");
        assert_eq!(saved.target_fps, 0);
        assert_eq!(saved.bitrate_kbps, MAX_BITRATE_KBPS);
        assert!(saved.audio_enabled);
        let _ = fs::remove_file(path);
    }
}
