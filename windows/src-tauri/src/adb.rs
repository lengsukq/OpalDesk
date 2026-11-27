use serde::Serialize;
use std::env;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Mutex;
use std::time::Duration;

const HELPER_PACKAGE: &str = "io.github.lengsukq.opaldesk";
const DAEMON_CLASS: &str = "io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon";
const DAEMON_PATTERN_SHORT: &str = "^app_process.*OpalDeskDaemon";
const DAEMON_PATTERN_FULL: &str = "^/system/bin/app_process.*OpalDeskDaemon";
const ABSTRACT_SOCKET_NAME: &str = "opaldesk_stream";

const EXIT_CODE_NO_JAR: i32 = 23;
const EXIT_CODE_UNTRUSTED_APK: i32 = 24;

const QUICK_TIMEOUT: Duration = Duration::from_secs(8);
const BOOTSTRAP_TIMEOUT: Duration = Duration::from_secs(24);

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AdbDevice {
    pub serial: String,
    pub state: String,
    pub product: String,
    pub model: String,
    pub device_code: String,
    pub transport_id: String,
    pub display_name: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdbStatusInfo {
    pub available: bool,
    pub adb_path: Option<String>,
    pub custom_adb_path: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdbForwardResult {
    pub serial: String,
    pub local_port: u16,
    pub local_ws_url: String,
}

#[derive(Default)]
struct MutableAdbState {
    custom_adb_path: Option<String>,
    active_forwards: Vec<(String, u16)>,
}

pub struct AdbManager {
    resource_dir: Option<PathBuf>,
    state: Mutex<MutableAdbState>,
}

pub fn sanitize_custom_adb_path(path: Option<String>) -> Option<String> {
    let raw = path?;
    let mut cleaned = raw.trim();
    loop {
        let stripped = cleaned
            .strip_prefix('"')
            .and_then(|s| s.strip_suffix('"'))
            .or_else(|| cleaned.strip_prefix('\'').and_then(|s| s.strip_suffix('\'')))
            .map(str::trim);
        match stripped {
            Some(inner) => cleaned = inner,
            None => break,
        }
    }
    let cleaned = cleaned.trim_matches(|c| c == '"' || c == '\'').trim();
    if cleaned.is_empty() {
        None
    } else {
        Some(cleaned.to_string())
    }
}

impl AdbManager {
    pub fn new(resource_dir: Option<PathBuf>) -> Self {
        Self {
            resource_dir,
            state: Mutex::new(MutableAdbState::default()),
        }
    }

    pub fn set_custom_adb_path(&self, path: Option<String>) -> AdbStatusInfo {
        let cleaned = sanitize_custom_adb_path(path);
        if let Ok(mut s) = self.state.lock() {
            s.custom_adb_path = cleaned;
        }
        self.status()
    }

    pub fn status(&self) -> AdbStatusInfo {
        let custom = self.state.lock().ok().and_then(|s| s.custom_adb_path.clone());
        let resolved = self.locate_adb().map(|p| p.to_string_lossy().into_owned());
        AdbStatusInfo {
            available: resolved.is_some(),
            adb_path: resolved,
            custom_adb_path: custom,
        }
    }

    pub fn locate_adb(&self) -> Option<PathBuf> {
        let bin_name = if cfg!(windows) { "adb.exe" } else { "adb" };
        let mut candidates: Vec<PathBuf> = Vec::new();

        if let Some(custom) = self.state.lock().ok().and_then(|s| s.custom_adb_path.clone()) {
            let p = PathBuf::from(&custom);
            if p.is_dir() {
                candidates.push(p.join(bin_name));
                candidates.push(p.join("platform-tools").join(bin_name));
                candidates.push(p.join("Sdk").join("platform-tools").join(bin_name));
                candidates.push(p.join("sdk").join("platform-tools").join(bin_name));
            } else if cfg!(windows) && p.extension().is_none() {
                let with_exe = p.with_extension("exe");
                candidates.push(p);
                candidates.push(with_exe);
            } else {
                candidates.push(p);
            }
        }

        if let Some(ref res_dir) = self.resource_dir {
            candidates.push(res_dir.join(bin_name));
            candidates.push(res_dir.join("platform-tools").join(bin_name));
        }

        if let Ok(exe_path) = env::current_exe() {
            if let Some(exe_dir) = exe_path.parent() {
                candidates.push(exe_dir.join(bin_name));
                candidates.push(exe_dir.join("platform-tools").join(bin_name));
            }
        }

        for env_key in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
            if let Ok(val) = env::var(env_key) {
                if !val.trim().is_empty() {
                    candidates.push(PathBuf::from(val.trim()).join("platform-tools").join(bin_name));
                }
            }
        }

        if cfg!(windows) {
            if let Ok(local_app_data) = env::var("LOCALAPPDATA") {
                candidates.push(
                    PathBuf::from(&local_app_data)
                        .join("Android")
                        .join("Sdk")
                        .join("platform-tools")
                        .join(bin_name),
                );
            }
            if let Ok(user_profile) = env::var("USERPROFILE") {
                let up = PathBuf::from(&user_profile);
                candidates.push(
                    up.join("AppData")
                        .join("Local")
                        .join("Android")
                        .join("Sdk")
                        .join("platform-tools")
                        .join(bin_name),
                );
                candidates.push(
                    up.join("scoop")
                        .join("apps")
                        .join("adb")
                        .join("current")
                        .join(bin_name),
                );
            }
            candidates.push(PathBuf::from(r"C:\Android\sdk\platform-tools\adb.exe"));
            candidates.push(PathBuf::from(r"C:\Android\platform-tools\adb.exe"));
            candidates.push(PathBuf::from(r"C:\ProgramData\chocolatey\bin\adb.exe"));
        } else {
            candidates.push(PathBuf::from("/opt/homebrew/share/android-commandlinetools/platform-tools/adb"));
            candidates.push(PathBuf::from("/opt/homebrew/bin/adb"));
            candidates.push(PathBuf::from("/usr/local/bin/adb"));
            if let Ok(home) = env::var("HOME") {
                candidates.push(PathBuf::from(&home).join("Library/Android/sdk/platform-tools/adb"));
                candidates.push(PathBuf::from(&home).join("Android/Sdk/platform-tools/adb"));
            }
        }

        if let Some(path_os) = env::var_os("PATH") {
            for dir in env::split_paths(&path_os) {
                candidates.push(dir.join(bin_name));
            }
        }

        candidates.into_iter().find(|p| p.is_file())
    }

    pub async fn list_devices(&self) -> Result<Vec<AdbDevice>, String> {
        let adb = self.locate_adb().ok_or_else(|| {
            "未检测到 adb 工具：请安装 Android Platform-Tools 或在下方指定 adb.exe 路径".to_string()
        })?;
        let out = run_adb_command(&adb, &["devices", "-l"], QUICK_TIMEOUT).await?;
        if out.exit_code != 0 {
            return Err(format!("adb devices 执行失败: {}", out.combined_detail()));
        }
        Ok(parse_devices_output(&out.stdout))
    }

    pub async fn pair_wireless(&self, host: &str, port: u16, code: &str) -> Result<String, String> {
        let adb = self.locate_adb().ok_or_else(|| "未检测到 adb 工具".to_string())?;
        let target = format!("{}:{}", host.trim(), port);
        let clean_code = code.trim();
        let out = run_adb_command(&adb, &["pair", &target, clean_code], Duration::from_secs(15)).await?;
        let detail = out.combined_detail();
        if out.exit_code != 0 || detail.to_ascii_lowercase().contains("failed") {
            return Err(format!("无线调试配对失败: {detail}"));
        }
        Ok(detail)
    }

    pub async fn connect_wireless(&self, host: &str, port: u16) -> Result<String, String> {
        let adb = self.locate_adb().ok_or_else(|| "未检测到 adb 工具".to_string())?;
        let target = format!("{}:{}", host.trim(), port);
        let out = run_adb_command(&adb, &["connect", &target], Duration::from_secs(12)).await?;
        let detail = out.combined_detail();
        if out.exit_code != 0
            || detail.to_ascii_lowercase().contains("failed")
            || detail.to_ascii_lowercase().contains("cannot connect")
        {
            return Err(format!("无线 ADB 连接失败: {detail}"));
        }
        Ok(detail)
    }

    pub async fn bootstrap_and_forward(
        &self,
        serial: &str,
        preferred_port: Option<u16>,
    ) -> Result<AdbForwardResult, String> {
        let adb = self.locate_adb().ok_or_else(|| {
            "未检测到 adb 工具：请先安装 Android Platform-Tools 或配置 adb.exe 路径".to_string()
        })?;
        let clean_serial = serial.trim();
        if clean_serial.is_empty() {
            return Err("未选择目标 Android 设备序列号".into());
        }

        ensure_daemon_running(&adb, clean_serial).await?;

        // Reuse an already-active forward for the same serial when no explicit port is requested,
        // so desktop + up to 3 standalone floating windows + app catalog cleanly share one tunnel.
        if preferred_port.is_none() {
            let existing_port = self
                .state
                .lock()
                .ok()
                .and_then(|s| {
                    s.active_forwards
                        .iter()
                        .rev()
                        .find(|(ser, _)| ser == clean_serial)
                        .map(|(_, p)| *p)
                });
            if let Some(port) = existing_port {
                if is_forward_listed(&adb, clean_serial, port).await {
                    return Ok(AdbForwardResult {
                        serial: clean_serial.to_string(),
                        local_port: port,
                        local_ws_url: format!("ws://127.0.0.1:{port}/ws"),
                    });
                }
            }
        }

        let local_spec = preferred_port
            .filter(|&p| p > 0)
            .map(|p| format!("tcp:{p}"))
            .unwrap_or_else(|| "tcp:0".to_string());
        let remote_spec = format!("localabstract:{ABSTRACT_SOCKET_NAME}");

        let fwd = run_adb_command(
            &adb,
            &["-s", clean_serial, "forward", &local_spec, &remote_spec],
            QUICK_TIMEOUT,
        )
        .await?;
        if fwd.exit_code != 0 {
            return Err(format!("建立 ADB 端口转发失败: {}", fwd.combined_detail()));
        }

        let resolved_port = match preferred_port.filter(|&p| p > 0) {
            Some(p) => p,
            None => fwd
                .stdout
                .trim()
                .parse::<u16>()
                .ok()
                .filter(|&p| p > 0)
                .ok_or_else(|| format!("无法解析 adb forward 分配端口: {}", fwd.stdout.trim()))?,
        };

        if let Ok(mut s) = self.state.lock() {
            let entry = (clean_serial.to_string(), resolved_port);
            if !s.active_forwards.contains(&entry) {
                s.active_forwards.push(entry);
            }
        }

        Ok(AdbForwardResult {
            serial: clean_serial.to_string(),
            local_port: resolved_port,
            local_ws_url: format!("ws://127.0.0.1:{resolved_port}/ws"),
        })
    }

    pub fn cleanup_all_forwards_sync(&self) {
        let forwards = self
            .state
            .lock()
            .ok()
            .map(|mut s| std::mem::take(&mut s.active_forwards))
            .unwrap_or_default();
        if forwards.is_empty() {
            return;
        }
        let Some(adb) = self.locate_adb() else {
            return;
        };
        for (serial, port) in forwards {
            let mut cmd = std::process::Command::new(&adb);
            cmd.args(["-s", &serial, "forward", "--remove", &format!("tcp:{port}")])
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null());
            #[cfg(windows)]
            {
                use std::os::windows::process::CommandExt;
                const CREATE_NO_WINDOW: u32 = 0x08000000;
                cmd.creation_flags(CREATE_NO_WINDOW);
            }
            let _ = cmd.status();
        }
    }
}

struct CmdOutput {
    exit_code: i32,
    stdout: String,
    stderr: String,
}

impl CmdOutput {
    fn combined_detail(&self) -> String {
        let mut parts = Vec::new();
        if !self.stderr.trim().is_empty() {
            parts.push(self.stderr.trim());
        }
        if !self.stdout.trim().is_empty() {
            parts.push(self.stdout.trim());
        }
        if parts.is_empty() {
            format!("exit {}", self.exit_code)
        } else {
            parts.join(" | ")
        }
    }
}

async fn run_adb_command(adb: &Path, args: &[&str], timeout: Duration) -> Result<CmdOutput, String> {
    let mut std_cmd = std::process::Command::new(adb);
    std_cmd
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        std_cmd.creation_flags(CREATE_NO_WINDOW);
    }

    let mut cmd = tokio::process::Command::from(std_cmd);
    cmd.kill_on_drop(true);

    let out = tokio::time::timeout(timeout, cmd.output())
        .await
        .map_err(|_| format!("执行 adb {} 超时", args.first().copied().unwrap_or("")))?
        .map_err(|e| format!("启动 adb 进程失败: {e}"))?;

    Ok(CmdOutput {
        exit_code: out.status.code().unwrap_or(-1),
        stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&out.stderr).into_owned(),
    })
}

async fn is_daemon_running(adb: &Path, serial: &str) -> Result<bool, String> {
    let check_cmd = format!(
        "pgrep -f '{DAEMON_PATTERN_SHORT}' || pgrep -f '{DAEMON_PATTERN_FULL}'"
    );
    let res = run_adb_command(adb, &["-s", serial, "shell", &check_cmd], QUICK_TIMEOUT).await?;
    Ok(res.exit_code == 0 && !res.stdout.trim().is_empty())
}

async fn is_forward_listed(adb: &Path, serial: &str, local_port: u16) -> bool {
    let Ok(out) = run_adb_command(adb, &["-s", serial, "forward", "--list"], QUICK_TIMEOUT).await else {
        return false;
    };
    if out.exit_code != 0 {
        return false;
    }
    let needle = format!("tcp:{local_port}");
    out.stdout
        .lines()
        .any(|line| line.contains(&needle) && line.contains(ABSTRACT_SOCKET_NAME))
}

async fn is_helper_installed(adb: &Path, serial: &str) -> Result<bool, String> {
    let res = run_adb_command(
        adb,
        &["-s", serial, "shell", "pm", "path", HELPER_PACKAGE],
        QUICK_TIMEOUT,
    )
    .await?;
    Ok(res.exit_code == 0 && res.stdout.contains("package:"))
}

async fn ensure_daemon_running(adb: &Path, serial: &str) -> Result<(), String> {
    if is_daemon_running(adb, serial).await? {
        return Ok(());
    }

    let helper_installed = is_helper_installed(adb, serial).await?;
    let script = build_remote_bootstrap_script(helper_installed);
    let boot = run_adb_command(adb, &["-s", serial, "shell", &script], BOOTSTRAP_TIMEOUT).await?;

    if boot.exit_code == EXIT_CODE_NO_JAR {
        return Err(format!(
            "设备 {serial} 尚未安装 OpalDesk Android 客户端，请先在手机安装 APK"
        ));
    }
    if boot.exit_code == EXIT_CODE_UNTRUSTED_APK {
        return Err(format!(
            "设备 {serial} 上的 OpalDesk APK 签名校验未通过，已拒绝启动守护进程"
        ));
    }
    if boot.exit_code != 0 {
        return Err(format!(
            "拉起手机端 OpalDesk 守护进程失败: {}",
            boot.combined_detail()
        ));
    }

    tokio::time::sleep(Duration::from_millis(450)).await;
    if !is_daemon_running(adb, serial).await? {
        return Err("启动命令已执行，但未检测到手机端 OpalDeskDaemon 进程存活".into());
    }
    Ok(())
}

pub fn build_remote_bootstrap_script(expect_helper_installed: bool) -> String {
    let alive_expr = format!(
        "(pgrep -f '{DAEMON_PATTERN_SHORT}' >/dev/null 2>&1 || pgrep -f '{DAEMON_PATTERN_FULL}' >/dev/null 2>&1)"
    );
    let stop_existing = format!(
        "if {alive_expr}; then
  (pkill -f '{DAEMON_PATTERN_SHORT}' 2>/dev/null || true)
  (pkill -f '{DAEMON_PATTERN_FULL}' 2>/dev/null || true)
  sleep 1.0
  (pkill -KILL -f '{DAEMON_PATTERN_SHORT}' 2>/dev/null || pkill -9 -f '{DAEMON_PATTERN_SHORT}' 2>/dev/null || true)
  (pkill -KILL -f '{DAEMON_PATTERN_FULL}' 2>/dev/null || pkill -9 -f '{DAEMON_PATTERN_FULL}' 2>/dev/null || true)
fi"
    );

    let stage_step = if expect_helper_installed {
        format!(
            r#"APK_PATHS=$(pm path {HELPER_PACKAGE} 2>/dev/null | sed -n 's/^package://p' | tr -d '\r')
if [ -n "$APK_PATHS" ]; then
  TMP_JAR=$(mktemp /data/local/tmp/opaldesk.XXXXXX 2>/dev/null)
  if [ -z "$TMP_JAR" ] || [ -L "$TMP_JAR" ]; then
    rm -f "$TMP_JAR" 2>/dev/null
    echo "ERR_STAGE_JAR: failed to create exclusive temp file" >&2
    exit 21
  fi
  for p in $APK_PATHS; do
    if [ -n "$p" ] && [ -f "$p" ] && [ ! -L "$p" ]; then
      unzip -p "$p" assets/opaldesk.jar > "$TMP_JAR" 2>/dev/null
      if [ -s "$TMP_JAR" ] && [ ! -L "$TMP_JAR" ] && [ "$(dd if="$TMP_JAR" bs=1 count=2 2>/dev/null)" = "PK" ]; then
        break
      fi
    fi
  done
  if [ ! -s "$TMP_JAR" ] || [ -L "$TMP_JAR" ] || [ "$(dd if="$TMP_JAR" bs=1 count=2 2>/dev/null)" != "PK" ]; then
    rm -f "$TMP_JAR" 2>/dev/null
    echo "ERR_STAGE_JAR: failed to extract valid assets/opaldesk.jar" >&2
    exit 21
  fi
  chmod 644 "$TMP_JAR" || {{ rm -f "$TMP_JAR" 2>/dev/null; exit 21; }}
  if ! timeout 6 env CLASSPATH="$TMP_JAR" app_process /data/local/tmp {DAEMON_CLASS} --verify-helper >/dev/null 2>&1; then
    rm -f "$TMP_JAR" 2>/dev/null
    echo "ERR_UNTRUSTED_APK: extracted daemon rejected {HELPER_PACKAGE} signing certificate" >&2
    exit 24
  fi
  (timeout 8 env CLASSPATH="$TMP_JAR" /system/bin/app_process /data/local/tmp {DAEMON_CLASS} --stop >/dev/null 2>&1 || true)
  {stop_existing}
  rm -f /data/local/tmp/opaldesk.jar 2>/dev/null
  if ! mv -f "$TMP_JAR" /data/local/tmp/opaldesk.jar || [ -L /data/local/tmp/opaldesk.jar ] || [ ! -s /data/local/tmp/opaldesk.jar ]; then
    rm -f "$TMP_JAR" 2>/dev/null
    echo "ERR_SWAP_JAR: failed to replace /data/local/tmp/opaldesk.jar" >&2
    exit 22
  fi
  appops set {HELPER_PACKAGE} SYSTEM_ALERT_WINDOW allow 2>/dev/null
  appops set {HELPER_PACKAGE} POST_NOTIFICATION allow 2>/dev/null
  appops set {HELPER_PACKAGE} RUN_ANY_IN_BACKGROUND allow 2>/dev/null
  appops set {HELPER_PACKAGE} 10021 allow 2>/dev/null
  appops set {HELPER_PACKAGE} ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
  pm grant {HELPER_PACKAGE} android.permission.POST_NOTIFICATIONS 2>/dev/null
  pm grant {HELPER_PACKAGE} android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null
  dumpsys deviceidle whitelist +{HELPER_PACKAGE} >/dev/null 2>&1
fi"#
        )
    } else {
        format!(
            r#"if [ -L /data/local/tmp/opaldesk.jar ] || [ ! -s /data/local/tmp/opaldesk.jar ] || [ "$(dd if=/data/local/tmp/opaldesk.jar bs=1 count=2 2>/dev/null)" != "PK" ]; then
  echo "ERR_NO_JAR: {HELPER_PACKAGE} not installed and /data/local/tmp/opaldesk.jar missing or invalid" >&2
  exit 23
fi
(CLASSPATH=/data/local/tmp/opaldesk.jar /system/bin/app_process /data/local/tmp {DAEMON_CLASS} --stop >/dev/null 2>&1 || true)
{stop_existing}"#
        )
    };

    format!(
        r#"{stage_step}
LOG_DST=/dev/null
rm -f /data/local/tmp/opaldesk.log 2>/dev/null
if (umask 022; set -C; : > /data/local/tmp/opaldesk.log) 2>/dev/null && [ -f /data/local/tmp/opaldesk.log ] && [ ! -L /data/local/tmp/opaldesk.log ]; then
  chown -h 2000:2000 /data/local/tmp/opaldesk.log 2>/dev/null
  LOG_DST=/data/local/tmp/opaldesk.log
fi
chown -h 2000:2000 /data/local/tmp/opaldesk.jar /data/local/tmp/opaldesk.conf /data/local/tmp/opaldesk_apps.conf /data/local/tmp/opaldesk_healed_v2 2>/dev/null
export CLASSPATH=/data/local/tmp/opaldesk.jar
CUR_CTX=$(id -Z 2>/dev/null || echo "")
case "$CUR_CTX" in
  *:shell:*)
    (setsid /system/bin/app_process /data/local/tmp {DAEMON_CLASS} </dev/null >>"$LOG_DST" 2>&1 &)
    sleep 0.5
    ;;
  *)
    (setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp {DAEMON_CLASS} </dev/null >>"$LOG_DST" 2>&1 &)
    sleep 0.5
    if ! {alive_expr}; then
      (setsid /system/bin/app_process /data/local/tmp {DAEMON_CLASS} </dev/null >>"$LOG_DST" 2>&1 &)
      sleep 0.5
    fi
    ;;
esac
if ! {alive_expr}; then
  nohup app_process /data/local/tmp {DAEMON_CLASS} >>"$LOG_DST" 2>&1 </dev/null &
  sleep 0.5
fi"#
    )
}

const KNOWN_ADB_STATES: &[&str] = &[
    "device",
    "unauthorized",
    "offline",
    "authorizing",
    "recovery",
    "sideload",
    "bootloader",
    "connecting",
];

pub fn parse_devices_output(output: &str) -> Vec<AdbDevice> {
    let mut devices = Vec::new();
    for raw_line in output.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with("List of devices") || line.starts_with('*') {
            continue;
        }
        let tokens: Vec<&str> = line.split_whitespace().collect();
        if tokens.len() < 2 {
            continue;
        }
        let (serial, state, attrs) = split_device_line(line, &tokens);
        if serial.is_empty() {
            continue;
        }

        let mut product = String::new();
        let mut model = String::new();
        let mut device_code = String::new();
        let mut transport_id = String::new();

        for attr in attrs {
            if let Some((k, v)) = attr.split_once(':') {
                match k {
                    "product" => product = v.to_string(),
                    "model" => model = v.replace('_', " "),
                    "device" => device_code = v.to_string(),
                    "transport_id" => transport_id = v.to_string(),
                    _ => {}
                }
            }
        }

        let display_name = if !model.is_empty() {
            model.clone()
        } else if !device_code.is_empty() {
            device_code.clone()
        } else {
            serial.clone()
        };

        devices.push(AdbDevice {
            serial,
            state,
            product,
            model,
            device_code,
            transport_id,
            display_name,
        });
    }
    devices
}

fn split_device_line<'a>(line: &'a str, tokens: &[&'a str]) -> (String, String, Vec<&'a str>) {
    for idx in 1..tokens.len() {
        let candidate = tokens[idx];
        if KNOWN_ADB_STATES.contains(&candidate)
            && tokens[idx + 1..].iter().all(|t| t.contains(':'))
        {
            if let Some(pos) = line.find(candidate) {
                let serial = line[..pos].trim().to_string();
                if !serial.is_empty() {
                    return (
                        serial,
                        candidate.to_string(),
                        tokens[idx + 1..].to_vec(),
                    );
                }
            }
        }
        if candidate == "no" && tokens.get(idx + 1) == Some(&"permissions") {
            if let Some(pos) = line.find("no") {
                let serial = line[..pos].trim().to_string();
                return (
                    serial,
                    "no_permissions".to_string(),
                    tokens[idx + 2..].to_vec(),
                );
            }
        }
    }
    (
        tokens[0].to_string(),
        tokens[1].to_string(),
        tokens[2..].to_vec(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_usb_and_wireless_mdns_adb_devices() {
        let sample = "List of devices attached
            * daemon started successfully
            f8a92b11               device usb:1-2 product:shennong model:Xiaomi_14_Pro device:shennong transport_id:4
            adb-R52X10 (2)._adb-tls-connect._tcp device product:gts9p model:SM_X810 transport_id:9
            192.168.31.88:41555    unauthorized transport_id:11
";

        let parsed = parse_devices_output(sample);
        assert_eq!(parsed.len(), 3);
        assert_eq!(parsed[0].serial, "f8a92b11");
        assert_eq!(parsed[0].state, "device");
        assert_eq!(parsed[0].model, "Xiaomi 14 Pro");
        assert_eq!(parsed[1].serial, "adb-R52X10 (2)._adb-tls-connect._tcp");
        assert_eq!(parsed[1].state, "device");
        assert_eq!(parsed[2].state, "unauthorized");
    }

    #[test]
    fn bootstrap_script_contains_helper_signature_verification() {
        let script = build_remote_bootstrap_script(true);
        assert!(script.contains("--verify-helper"));
        assert!(script.contains("exit 24"));
        let fallback = build_remote_bootstrap_script(false);
        assert!(fallback.contains("exit 23"));
    }

    #[test]
    fn sanitizes_quoted_custom_adb_paths_and_locates_from_dir() {
        assert_eq!(
            sanitize_custom_adb_path(Some(r#"  "C:\Android\platform-tools\adb.exe"  "#.into())).as_deref(),
            Some(r"C:\Android\platform-tools\adb.exe")
        );
        assert_eq!(
            sanitize_custom_adb_path(Some(" 'D:\\Tools\\adb.exe' ".into())).as_deref(),
            Some(r"D:\Tools\adb.exe")
        );
        assert_eq!(sanitize_custom_adb_path(Some(r#"  ""  "#.into())), None);
        assert_eq!(sanitize_custom_adb_path(None), None);

        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let root = std::env::temp_dir().join(format!("opaldesk_adb_loc_{nanos}"));
        let pt_dir = root.join("platform-tools");
        std::fs::create_dir_all(&pt_dir).expect("create temp platform-tools dir");
        let bin_name = if cfg!(windows) { "adb.exe" } else { "adb" };
        let fake_bin = pt_dir.join(bin_name);
        std::fs::write(&fake_bin, b"fake-adb").expect("write fake adb binary");

        let mgr = AdbManager::new(None);
        let status = mgr.set_custom_adb_path(Some(format!("\"{}\"", root.display())));
        assert_eq!(
            status.custom_adb_path.as_deref(),
            Some(&*root.to_string_lossy())
        );
        assert!(status.available);
        assert_eq!(
            status.adb_path.as_deref(),
            Some(&*fake_bin.to_string_lossy())
        );

        let _ = std::fs::remove_dir_all(&root);
    }
}
