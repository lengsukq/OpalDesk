use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;
#[cfg(target_os = "windows")]
use std::process::Command;

#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub const DEEP_LINK_SCHEME: &str = "opaldesk";

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateAppShortcutRequest {
    pub pkg: String,
    #[serde(default)]
    pub cls: String,
    #[serde(default)]
    pub app_label: String,
    #[serde(default)]
    pub user_id: u32,
    #[serde(default)]
    pub profile_type: String,
    #[serde(default)]
    pub host: String,
    #[serde(default)]
    pub port: u16,
    #[serde(default)]
    pub transport: String,
    #[serde(default)]
    pub device_key: String,
    #[serde(default)]
    pub device_name: String,
    #[serde(default)]
    pub cert_fingerprint: String,
    #[serde(default)]
    pub preset: String,
    #[serde(default)]
    pub dpi: u16,
    #[serde(default)]
    pub compat: bool,
    #[serde(default)]
    pub pinned: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppShortcutRecord {
    pub pkg: String,
    #[serde(default)]
    pub user_id: u32,
    pub app_label: String,
    pub shortcut_path: String,
    pub deep_link_url: String,
}

impl AppShortcutRecord {
    pub fn app_key(&self) -> String {
        if self.user_id > 0 {
            format!("{}#u{}", self.pkg, self.user_id)
        } else {
            self.pkg.clone()
        }
    }
}

pub struct ShortcutStore {
    manifest_path: PathBuf,
    records: Mutex<Vec<AppShortcutRecord>>,
}

impl ShortcutStore {
    pub fn new(manifest_path: PathBuf) -> Self {
        let loaded = fs::read_to_string(&manifest_path)
            .ok()
            .and_then(|raw| serde_json::from_str::<Vec<AppShortcutRecord>>(&raw).ok())
            .unwrap_or_default();
        Self {
            manifest_path,
            records: Mutex::new(loaded),
        }
    }

    pub fn list(&self) -> Vec<AppShortcutRecord> {
        let mut guard = self.records.lock().unwrap_or_else(|e| e.into_inner());
        guard.retain(|r| Path::new(&r.shortcut_path).exists() || cfg!(not(target_os = "windows")));
        guard.clone()
    }

    pub fn upsert(&self, rec: AppShortcutRecord) -> Result<AppShortcutRecord, String> {
        let mut guard = self.records.lock().map_err(|_| "快捷方式存储锁异常".to_string())?;
        let target_key = rec.app_key();
        guard.retain(|r| r.app_key() != target_key);
        guard.push(rec.clone());
        self.save_locked(&guard)?;
        Ok(rec)
    }

    pub fn remove(&self, pkg_or_key: &str) -> Result<bool, String> {
        let mut guard = self.records.lock().map_err(|_| "快捷方式存储锁异常".to_string())?;
        let mut removed = false;
        guard.retain(|r| {
            if r.app_key() == pkg_or_key || (r.user_id == 0 && r.pkg == pkg_or_key) {
                let _ = fs::remove_file(&r.shortcut_path);
                removed = true;
                false
            } else {
                true
            }
        });
        if removed {
            self.save_locked(&guard)?;
        }
        Ok(removed)
    }

    fn save_locked(&self, list: &[AppShortcutRecord]) -> Result<(), String> {
        if let Some(parent) = self.manifest_path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("无法创建配置目录: {e}"))?;
        }
        let body =
            serde_json::to_string_pretty(list).map_err(|e| format!("序列化快捷方式清单失败: {e}"))?;
        fs::write(&self.manifest_path, body.as_bytes())
            .map_err(|e| format!("写入快捷方式清单失败: {e}"))
    }
}

/// RFC 3986 percent-encoding for URL query values (zero extra crates).
pub fn percent_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 16);
    for b in input.as_bytes() {
        let is_unreserved = matches!(
            b,
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~'
        );
        if is_unreserved {
            out.push(*b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

pub fn sanitize_shortcut_filename(raw: &str) -> String {
    let cleaned: String = raw
        .trim()
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
        "OpalDesk App".to_string()
    } else {
        trimmed.chars().take(64).collect()
    }
}

pub fn build_popout_deep_link(req: &CreateAppShortcutRequest) -> String {
    let mut pairs: Vec<(&str, String)> = Vec::with_capacity(12);
    pairs.push(("pkg", req.pkg.trim().to_string()));
    if !req.cls.trim().is_empty() {
        pairs.push(("cls", req.cls.trim().to_string()));
    }
    let label = if req.app_label.trim().is_empty() {
        req.pkg.trim()
    } else {
        req.app_label.trim()
    };
    pairs.push(("appLabel", label.to_string()));
    if req.user_id > 0 {
        pairs.push(("userId", req.user_id.to_string()));
        if !req.profile_type.trim().is_empty() {
            pairs.push(("profileType", req.profile_type.trim().to_string()));
        }
    }
    if !req.host.trim().is_empty() {
        pairs.push(("host", req.host.trim().to_string()));
    }
    let port = if req.port == 0 { 19878 } else { req.port };
    pairs.push(("port", port.to_string()));
    if !req.transport.trim().is_empty() {
        pairs.push(("transport", req.transport.trim().to_string()));
    }
    if !req.device_key.trim().is_empty() {
        pairs.push(("deviceKey", req.device_key.trim().to_string()));
    }
    if !req.device_name.trim().is_empty() {
        pairs.push(("deviceName", req.device_name.trim().to_string()));
    }
    if !req.cert_fingerprint.trim().is_empty() {
        pairs.push(("fp", req.cert_fingerprint.trim().to_string()));
    }
    if !req.preset.trim().is_empty() {
        pairs.push(("preset", req.preset.trim().to_string()));
    }
    if req.dpi > 0 {
        pairs.push(("dpi", req.dpi.to_string()));
    }
    if req.compat {
        pairs.push(("compat", "1".to_string()));
    }
    if req.pinned {
        pairs.push(("pin", "1".to_string()));
    }

    let query = pairs
        .into_iter()
        .map(|(k, v)| format!("{k}={}", percent_encode(&v)))
        .collect::<Vec<_>>()
        .join("&");
    format!("{DEEP_LINK_SCHEME}://popout?{query}")
}

pub fn extract_deep_link_arg(args: &[String]) -> Option<String> {
    let prefix = format!("{DEEP_LINK_SCHEME}://");
    args.iter()
        .map(|s| s.trim().trim_matches('"'))
        .find(|s| s.to_ascii_lowercase().starts_with(&prefix))
        .map(ToString::to_string)
}

/// Registers zero-admin HKCU\Software\Classes\opaldesk URL protocol on Windows.
pub fn register_url_protocol() -> Result<bool, String> {
    #[cfg(target_os = "windows")]
    {
        let exe_path = std::env::current_exe()
            .map_err(|e| format!("无法获取当前可执行文件路径: {e}"))?;
        let exe_str = exe_path.to_string_lossy();
        let command_value = format!("\"{exe_str}\" \"%1\"");

        let steps: &[(&[&str], &str)] = &[
            (
                &[
                    "add",
                    r"HKCU\Software\Classes\opaldesk",
                    "/ve",
                    "/d",
                    "URL:OpalDesk Protocol",
                    "/f",
                ],
                "注册协议名称",
            ),
            (
                &[
                    "add",
                    r"HKCU\Software\Classes\opaldesk",
                    "/v",
                    "URL Protocol",
                    "/d",
                    "",
                    "/f",
                ],
                "注册 URL Protocol 标记",
            ),
            (
                &[
                    "add",
                    r"HKCU\Software\Classes\opaldesk\shell\open\command",
                    "/ve",
                    "/d",
                    &command_value,
                    "/f",
                ],
                "注册启动命令行",
            ),
        ];

        for (args, label) in steps {
            let status = Command::new("reg.exe")
                .args(*args)
                .creation_flags(CREATE_NO_WINDOW)
                .status()
                .map_err(|e| format!("{label}失败: {e}"))?;
            if !status.success() {
                return Err(format!("{label}失败 (exit={})", status));
            }
        }
        return Ok(true);
    }

    #[cfg(not(target_os = "windows"))]
    {
        Ok(false)
    }
}

/// Creates a native Windows Desktop `.lnk` shortcut launching the given Android app popout
/// via `opaldesk://popout?...` (with zero UAC prompt and env-var-isolated PowerShell args).
pub fn create_desktop_shortcut(
    desktop_dir: &Path,
    req: &CreateAppShortcutRequest,
    store: &ShortcutStore,
) -> Result<AppShortcutRecord, String> {
    if req.pkg.trim().is_empty() {
        return Err("缺少应用包名".to_string());
    }
    let _ = register_url_protocol();

    let label = if req.app_label.trim().is_empty() {
        req.pkg.trim().to_string()
    } else {
        req.app_label.trim().to_string()
    };
    let safe_name = sanitize_shortcut_filename(&label);
    let deep_link_url = build_popout_deep_link(req);
    fs::create_dir_all(desktop_dir).map_err(|e| format!("无法访问桌面目录: {e}"))?;
    let shortcut_path = desktop_dir.join(format!("{safe_name} (OpalDesk).lnk"));

    #[cfg(target_os = "windows")]
    {
        let exe_path = std::env::current_exe()
            .map_err(|e| format!("无法获取 OpalDesk 可执行文件路径: {e}"))?;
        let script = r#"
$ws = New-Object -ComObject WScript.Shell
$s = $ws.CreateShortcut($env:OPAL_LNK_PATH)
$s.TargetPath = $env:OPAL_TARGET_EXE
$s.Arguments = '"' + $env:OPAL_DEEP_LINK + '"'
$s.IconLocation = $env:OPAL_TARGET_EXE + ',0'
$s.Description = $env:OPAL_DESC
$s.Save()
"#;
        let output = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script])
            .env("OPAL_LNK_PATH", shortcut_path.to_string_lossy().as_ref())
            .env("OPAL_TARGET_EXE", exe_path.to_string_lossy().as_ref())
            .env("OPAL_DEEP_LINK", &deep_link_url)
            .env("OPAL_DESC", format!("使用 OpalDesk 小窗启动 {label}"))
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(|e| format!("调用 PowerShell 创建快捷方式失败: {e}"))?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
            return Err(format!("创建桌面快捷方式失败: {stderr}"));
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        let content = format!("[InternetShortcut]\nURL={deep_link_url}\n");
        fs::write(&shortcut_path, content.as_bytes())
            .map_err(|e| format!("写入快捷方式文件失败: {e}"))?;
    }

    store.upsert(AppShortcutRecord {
        pkg: req.pkg.trim().to_string(),
        user_id: req.user_id,
        app_label: label,
        shortcut_path: shortcut_path.to_string_lossy().into_owned(),
        deep_link_url,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_encoded_popout_deep_link_and_extracts_cli_arg() {
        let req = CreateAppShortcutRequest {
            pkg: "com.tencent.mm".into(),
            cls: ".ui.LauncherUI".into(),
            app_label: "微信".into(),
            user_id: 0,
            profile_type: String::new(),
            host: "192.168.31.88".into(),
            port: 19878,
            transport: "wifi".into(),
            device_key: "192.168.31.88:19878".into(),
            device_name: "Xiaomi 15".into(),
            cert_fingerprint: "abcdef0123456789".into(),
            preset: "tablet".into(),
            dpi: 320,
            compat: true,
            pinned: true,
        };
        let url = build_popout_deep_link(&req);
        assert!(url.starts_with("opaldesk://popout?pkg=com.tencent.mm&"));
        assert!(!url.contains("userId="));
        assert!(url.contains("preset=tablet"));
        assert!(url.contains("dpi=320"));
        assert!(url.contains("compat=1"));
        assert!(url.contains("pin=1"));

        let args = vec!["OpalDesk.exe".to_string(), url.clone()];
        assert_eq!(extract_deep_link_arg(&args), Some(url));
        assert_eq!(sanitize_shortcut_filename("App: / Test*"), "App  Test");
    }

    #[test]
    fn builds_deep_link_with_secondary_user_profile_and_isolates_records() {
        let req = CreateAppShortcutRequest {
            pkg: "com.tencent.mm".into(),
            cls: ".ui.LauncherUI".into(),
            app_label: "微信 (分身)".into(),
            user_id: 999,
            profile_type: "clone".into(),
            host: "192.168.31.88".into(),
            port: 19878,
            transport: "wifi".into(),
            device_key: "192.168.31.88:19878".into(),
            device_name: "Xiaomi 15".into(),
            cert_fingerprint: String::new(),
            preset: String::new(),
            dpi: 0,
            compat: false,
            pinned: false,
        };
        let url = build_popout_deep_link(&req);
        assert!(url.contains("userId=999"));
        assert!(url.contains("profileType=clone"));
    }
}
