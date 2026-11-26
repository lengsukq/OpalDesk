use chrono::{Local, TimeZone};
use flate2::write::GzEncoder;
use flate2::Compression;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

pub const DEFAULT_MAX_ENTRIES: usize = 600;
pub const DEFAULT_MAX_REPORT_ENTRIES: usize = 250;
pub const DEFAULT_MAX_GZIP_BYTES: usize = 512 * 1024;
const MAX_MESSAGE_CHARS: usize = 1200;
const MAX_LOG_FILE_BYTES: u64 = 192 * 1024;
const KEEP_FILE_TAIL_LINES: usize = 350;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct LogEntry {
    pub seq: u64,
    pub timestamp_ms: u64,
    pub time_text: String,
    pub level: String, // "I" | "W" | "E"
    pub tag: String,
    pub message: String,
}

impl LogEntry {
    pub fn format_line(&self) -> String {
        format!("{} {}/{}: {}", self.time_text, self.level, self.tag, self.message)
    }
}

struct RingState {
    entries: VecDeque<LogEntry>,
    next_seq: u64,
}

pub struct LogStore {
    file_path: PathBuf,
    max_entries: usize,
    inner: Mutex<RingState>,
}

impl LogStore {
    pub fn new(file_path: PathBuf) -> Self {
        Self::with_capacity(file_path, DEFAULT_MAX_ENTRIES)
    }

    pub fn with_capacity(file_path: PathBuf, max_entries: usize) -> Self {
        let store = Self {
            file_path: file_path.clone(),
            max_entries: max_entries.max(16),
            inner: Mutex::new(RingState {
                entries: VecDeque::with_capacity(max_entries.max(16)),
                next_seq: 1,
            }),
        };
        store.load_tail_from_disk(&file_path);
        store
    }

    pub fn append(&self, level: &str, tag: &str, message: &str) -> LogEntry {
        let norm_level = normalize_level(level);
        let clean_tag = truncate_chars(tag.trim(), 24);
        let clean_tag = if clean_tag.is_empty() {
            "App".to_string()
        } else {
            clean_tag
        };
        let clean_msg = sanitize_single_line(message);
        let now_ms = now_unix_ms();
        let time_text = format_local_time_ms(now_ms);

        let entry = {
            let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            let entry = LogEntry {
                seq: guard.next_seq,
                timestamp_ms: now_ms,
                time_text,
                level: norm_level.to_string(),
                tag: clean_tag,
                message: clean_msg,
            };
            guard.next_seq += 1;
            while guard.entries.len() >= self.max_entries {
                guard.entries.pop_front();
            }
            guard.entries.push_back(entry.clone());
            entry
        };

        append_line_to_disk(&self.file_path, &format!("{}\n", entry.format_line()));
        entry
    }

    pub fn info(&self, tag: &str, message: &str) {
        self.append("I", tag, message);
    }

    pub fn warn(&self, tag: &str, message: &str) {
        self.append("W", tag, message);
    }

    pub fn error(&self, tag: &str, message: &str) {
        self.append("E", tag, message);
    }

    pub fn snapshot(&self) -> Vec<LogEntry> {
        self.inner
            .lock()
            .map(|g| g.entries.iter().cloned().collect())
            .unwrap_or_default()
    }

    pub fn clear(&self) {
        if let Ok(mut guard) = self.inner.lock() {
            guard.entries.clear();
        }
        if self.file_path.exists() {
            let _ = fs::write(&self.file_path, b"");
        }
    }

    fn load_tail_from_disk(&self, path: &Path) {
        let Ok(content) = fs::read_to_string(path) else {
            return;
        };
        let lines: Vec<&str> = content.lines().filter(|l| !l.trim().is_empty()).collect();
        let start = lines.len().saturating_sub(KEEP_FILE_TAIL_LINES);
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        for raw in &lines[start..] {
            if let Some(entry) = parse_persisted_line(raw, guard.next_seq) {
                guard.next_seq += 1;
                while guard.entries.len() >= self.max_entries {
                    guard.entries.pop_front();
                }
                guard.entries.push_back(entry);
            }
        }
    }
}

pub fn filter_entries(entries: &[LogEntry], warnings_only: bool, query: &str) -> Vec<LogEntry> {
    let q = query.trim().to_lowercase();
    entries
        .iter()
        .filter(|entry| {
            if warnings_only && entry.level == "I" {
                return false;
            }
            if q.is_empty() {
                return true;
            }
            entry.tag.to_lowercase().contains(&q)
                || entry.message.to_lowercase().contains(&q)
                || entry.time_text.contains(&q)
        })
        .cloned()
        .collect()
}

pub fn sanitize_text(raw: &str) -> String {
    if raw.is_empty() {
        return String::new();
    }
    struct Rule {
        re: Regex,
        replacement: &'static str,
    }
    static RULES: OnceLock<Vec<Rule>> = OnceLock::new();
    let rules = RULES.get_or_init(|| {
        let defs: &[(&str, &str)] = &[
            (r#"(/(?:Users|home)/)([^/\s"'`]+)"#, "${1}<redacted>"),
            (r#"([A-Za-z]:\\Users\\)([^\\\s"'`]+)"#, "${1}<redacted>"),
            (r#"(/storage/emulated/)\d+"#, "${1}<user>"),
            (r#"(/data/user/)\d+"#, "${1}<uid>"),
            (r#"(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]{6,}"#, "${1}<redacted>"),
            (r#"\bsntry[a-zA-Z0-9_-]{8,}\b"#, "<redacted_sentry_token>"),
            (
                r#"\b(?:gh[pousr]_[A-Za-z0-9_]{10,}|github_pat_[A-Za-z0-9_]{10,})\b"#,
                "<redacted_gh_token>",
            ),
            (r#"(?i)\b(adb\s+pair\s+\S+\s+)(\d{6})\b"#, "${1}<redacted_pin>"),
            (
                r#"(?i)\b(token|auth_?token|access_?token|secret|password|passwd|api_?key|authorization|pairing_?code)\b(\s*[:=]\s*)([^\s,;"'&}\]]+)"#,
                "${1}${2}<redacted>",
            ),
            (r#"\b(192\.168)\.\d{1,3}\.\d{1,3}\b"#, "${1}.*.*"),
            (r#"\b(10)\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"#, "${1}.*.*.*"),
            (r#"\b(172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b"#, "${1}.*.*"),
        ];
        defs.iter()
            .filter_map(|(pat, rep)| {
                Regex::new(pat).ok().map(|re| Rule {
                    re,
                    replacement: *rep,
                })
            })
            .collect()
    });

    let mut out = raw.to_string();
    for rule in rules {
        out = rule.re.replace_all(&out, rule.replacement).into_owned();
    }
    out
}

pub fn build_diagnostic_report(
    app_version: &str,
    device_summary: &str,
    context_summary: &str,
    entries: &[LogEntry],
    generated_at: &str,
) -> String {
    let mut out = String::with_capacity(4096);
    out.push_str("=== OpalDesk Desktop 诊断日志报告 ===\n");
    out.push_str(&format!("生成时间: {generated_at}\n"));
    out.push_str(&format!("应用版本: v{app_version}\n"));
    out.push_str(&format!("设备信息: {device_summary}\n"));
    if !context_summary.trim().is_empty() {
        out.push_str(&format!("运行状态: {}\n", context_summary.trim()));
    }
    out.push('\n');
    out.push_str(&format!("=== 客户端日志 ({} 条) ===\n", entries.len()));
    if entries.is_empty() {
        out.push_str("(无记录)\n");
    } else {
        for e in entries {
            out.push_str(&e.format_line());
            out.push('\n');
        }
    }
    out
}

pub fn build_sanitized_report_text(
    app_version: &str,
    device_summary: &str,
    reporter_name: &str,
    problem_description: &str,
    context_summary: &str,
    entries: &[LogEntry],
    generated_at: &str,
    max_entries: usize,
) -> String {
    let start = entries.len().saturating_sub(max_entries);
    let recent = &entries[start..];
    let base = build_diagnostic_report(app_version, device_summary, context_summary, recent, generated_at);
    let clean_user = reporter_name.trim();
    let clean_desc = problem_description.trim();
    let header = format!(
        "=== 用户反馈信息 ===\n用户名: {}\n问题描述: {}\n\n",
        if clean_user.is_empty() { "(未填写)" } else { clean_user },
        if clean_desc.is_empty() { "(未填写)" } else { clean_desc },
    );
    sanitize_text(&(header + &base))
}

pub fn gzip_compress(text: &str, max_compressed_bytes: usize) -> Vec<u8> {
    let limit = max_compressed_bytes.max(1024);
    let mut candidate = text.to_string();
    let mut compressed = gzip_raw(candidate.as_bytes());
    let mut attempts = 0;
    while compressed.len() > limit && candidate.chars().count() > 512 && attempts < 6 {
        let chars: Vec<char> = candidate.chars().collect();
        let keep = ((chars.len() as f64) * 0.65).round() as usize;
        let keep = keep.max(512).min(chars.len());
        let suffix: String = chars[chars.len() - keep..].iter().collect();
        candidate = format!("[... truncated to fit upload size limit ...]\n{suffix}");
        compressed = gzip_raw(candidate.as_bytes());
        attempts += 1;
    }
    compressed
}

fn gzip_raw(input: &[u8]) -> Vec<u8> {
    let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
    if encoder.write_all(input).is_err() {
        return Vec::new();
    }
    encoder.finish().unwrap_or_default()
}

fn normalize_level(raw: &str) -> &'static str {
    match raw.trim().to_ascii_uppercase().as_str() {
        "E" | "ERR" | "ERROR" => "E",
        "W" | "WARN" | "WARNING" => "W",
        _ => "I",
    }
}

fn sanitize_single_line(raw: &str) -> String {
    let normalized = raw.replace(['\r', '\n'], " ");
    let trimmed = normalized.trim();
    let count = trimmed.chars().count();
    if count > MAX_MESSAGE_CHARS {
        let head: String = trimmed.chars().take(MAX_MESSAGE_CHARS).collect();
        format!("{head}...")
    } else {
        trimmed.to_string()
    }
}

fn truncate_chars(raw: &str, max_len: usize) -> String {
    raw.chars().take(max_len).collect()
}

fn parse_persisted_line(raw: &str, seq: u64) -> Option<LogEntry> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    if let Some((time_part, rest)) = trimmed.split_once(' ') {
        if time_part.len() >= 5 && time_part.len() <= 15 && rest.len() > 4 {
            let level_ch = rest.as_bytes()[0] as char;
            if rest.as_bytes()[1] == b'/' {
                if let Some((tag, msg)) = rest[2..].split_once(": ") {
                    let level = match level_ch {
                        'E' => "E",
                        'W' => "W",
                        _ => "I",
                    };
                    return Some(LogEntry {
                        seq,
                        timestamp_ms: 0,
                        time_text: time_part.to_string(),
                        level: level.to_string(),
                        tag: truncate_chars(tag, 24),
                        message: sanitize_single_line(msg),
                    });
                }
            }
        }
    }
    Some(LogEntry {
        seq,
        timestamp_ms: 0,
        time_text: "00:00:00.000".to_string(),
        level: "I".to_string(),
        tag: "Saved".to_string(),
        message: sanitize_single_line(trimmed),
    })
}

fn append_line_to_disk(path: &Path, line: &str) {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(meta) = fs::metadata(path) {
        if meta.len() > MAX_LOG_FILE_BYTES {
            if let Ok(content) = fs::read_to_string(path) {
                let lines: Vec<&str> = content.lines().collect();
                let start = lines.len().saturating_sub(KEEP_FILE_TAIL_LINES);
                let kept = lines[start..].join("\n") + "\n";
                let _ = fs::write(path, kept.as_bytes());
            }
        }
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = file.write_all(line.as_bytes());
    }
}

pub fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn format_local_time_ms(ts_ms: u64) -> String {
    let secs = (ts_ms / 1000) as i64;
    let nanos = ((ts_ms % 1000) * 1_000_000) as u32;
    Local
        .timestamp_opt(secs, nanos)
        .single()
        .map(|dt| dt.format("%H:%M:%S%.3f").to_string())
        .unwrap_or_else(|| "00:00:00.000".to_string())
}

pub fn format_now_datetime() -> String {
    Local::now().format("%Y-%m-%d %H:%M:%S").to_string()
}

pub fn format_now_filestamp() -> String {
    Local::now().format("%Y%m%d_%H%M%S").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitizes_windows_unix_paths_tokens_and_lan_ips() {
        let raw = r#"User C:\Users\alice\AppData and /Users/bob/app token=abc123456 Bearer xyz987654321 192.168.31.88 adb pair 10.0.0.5:37111 654321"#;
        let clean = sanitize_text(raw);
        assert!(clean.contains(r"C:\Users\<redacted>\AppData"));
        assert!(clean.contains("/Users/<redacted>/app"));
        assert!(clean.contains("token=<redacted>"));
        assert!(clean.contains("Bearer <redacted>"));
        assert!(clean.contains("192.168.*.*"));
        assert!(clean.contains("10.*.*.*"));
        assert!(clean.contains("<redacted_pin>"));
        assert!(!clean.contains("alice"));
        assert!(!clean.contains("bob"));
        assert!(!clean.contains("abc123456"));
    }

    #[test]
    fn gzip_compress_produces_valid_gzip_header() {
        let sample = "OpalDesk log line\n".repeat(200);
        let gz = gzip_compress(&sample, DEFAULT_MAX_GZIP_BYTES);
        assert!(gz.len() > 20);
        assert_eq!(gz[0], 0x1f);
        assert_eq!(gz[1], 0x8b);
    }

    #[test]
    fn log_store_persists_and_filters() {
        let dir = std::env::temp_dir().join(format!("opaldesk-logs-test-{}", now_unix_ms()));
        let path = dir.join("opaldesk_desktop.log");
        {
            let store = LogStore::with_capacity(path.clone(), 20);
            store.info("mdns", "Found Xiaomi 14 Pro");
            store.warn("adb", "Retry forward");
            store.error("tls", "Certificate mismatch");
            let warns = filter_entries(&store.snapshot(), true, "");
            assert_eq!(warns.len(), 2);
            let q = filter_entries(&store.snapshot(), false, "xiaomi");
            assert_eq!(q.len(), 1);
        }
        let reloaded = LogStore::with_capacity(path, 20);
        assert_eq!(reloaded.snapshot().len(), 3);
        let _ = fs::remove_dir_all(dir);
    }
}
