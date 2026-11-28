use crate::logs;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

use super::transport::resolve_status_base_url;

pub const MAX_DAILY_REPORTS: u32 = 5;
pub const MAX_STORED_RECORDS: usize = 50;
pub const MAX_BATCH_QUERY_IDS: usize = 20;
pub const AUTO_REFRESH_INTERVAL_MS: u64 = 10 * 60 * 1000;
pub const QQ_GROUP_NUMBER: &str = "1042317917";
pub const LIMIT_REACHED_MESSAGE: &str = "今日反馈与日志上传次数已达上限（5/5），请明天再试";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FeedbackQuotaSnapshot {
    pub date_key: String,
    pub used_today: u32,
    pub max_daily: u32,
    pub remaining: u32,
    pub is_exhausted: bool,
    pub remaining_label: String,
}

impl FeedbackQuotaSnapshot {
    pub fn build(date_key: String, used_today: u32, max_daily: u32) -> Self {
        let used = used_today.min(max_daily);
        let remaining = max_daily.saturating_sub(used);
        Self {
            date_key,
            used_today: used,
            max_daily,
            remaining,
            is_exhausted: remaining == 0,
            remaining_label: format!("今日剩余 {remaining}/{max_daily} 次"),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FeedbackRecord {
    pub event_id: String,
    pub short_code: String,
    pub summary: String,
    #[serde(default)]
    pub reported_version: String,
    #[serde(default)]
    pub created_at_ms: u64,
    #[serde(default = "default_open_status")]
    pub status: String,
    #[serde(default)]
    pub fixed_version: String,
    #[serde(default)]
    pub reply_note: String,
    #[serde(default)]
    pub last_checked_at_ms: u64,
    #[serde(default)]
    pub remote_updated_at: String,
    #[serde(default)]
    pub from_code_lookup: bool,
    #[serde(default = "default_bug_kind")]
    pub kind: String,
}

fn default_open_status() -> String {
    "open".to_string()
}

fn default_bug_kind() -> String {
    "bug".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FeedbackRemoteItem {
    #[serde(default)]
    pub event_id: String,
    #[serde(default)]
    pub short_code: String,
    #[serde(default = "default_open_status")]
    pub status: String,
    #[serde(default)]
    pub fixed_version: String,
    #[serde(default)]
    pub reply_note: String,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub reported_version: String,
    #[serde(default)]
    pub updated_at: String,
    #[serde(default = "default_bug_kind")]
    pub kind: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedbackStateSnapshot {
    pub sentry_configured: bool,
    pub status_sync_configured: bool,
    pub app_version: String,
    pub device_summary: String,
    pub qq_group_number: &'static str,
    pub quota: FeedbackQuotaSnapshot,
    pub reporter_name: String,
    pub records: Vec<FeedbackRecord>,
    pub last_sync_at_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ManualReportOutcome {
    pub success: bool,
    pub event_id: String,
    pub short_code: String,
    pub error_message: String,
    pub quota: FeedbackQuotaSnapshot,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedbackRefreshOutcome {
    pub success: bool,
    pub updated_count: usize,
    pub skipped_by_throttle: bool,
    pub message: String,
    pub records: Vec<FeedbackRecord>,
    pub last_sync_at_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedbackLookupOutcome {
    pub success: bool,
    pub found: bool,
    pub item: Option<FeedbackRemoteItem>,
    pub message: String,
    pub records: Vec<FeedbackRecord>,
    pub last_sync_at_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedSentryDsn {
    pub raw_dsn: String,
    pub public_key: String,
    pub project_id: String,
    pub envelope_endpoint_url: String,
}

#[derive(Debug, Clone)]
pub struct FeedbackBuildConfig {
    pub sentry_dsn: String,
    pub sentry_tunnel_url: String,
    pub feedback_status_url: String,
    pub app_version: String,
}

impl FeedbackBuildConfig {
    pub fn current() -> Self {
        let resolve = |compile_val: Option<&str>, env_keys: &[&str]| -> String {
            for key in env_keys {
                if let Ok(v) = std::env::var(key) {
                    let trimmed = v.trim();
                    if !trimmed.is_empty() {
                        return trimmed.to_string();
                    }
                }
            }
            compile_val
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .unwrap_or("")
                .to_string()
        };

        Self {
            sentry_dsn: resolve(
                option_env!("OPALDESK_SENTRY_DSN"),
                &["OPALDESK_SENTRY_DSN", "SENTRY_DSN"],
            ),
            sentry_tunnel_url: resolve(
                option_env!("OPALDESK_SENTRY_TUNNEL_URL"),
                &["OPALDESK_SENTRY_TUNNEL_URL", "SENTRY_TUNNEL_URL"],
            ),
            feedback_status_url: resolve(
                option_env!("OPALDESK_FEEDBACK_STATUS_URL"),
                &["OPALDESK_FEEDBACK_STATUS_URL", "FEEDBACK_STATUS_URL"],
            ),
            app_version: env!("CARGO_PKG_VERSION").to_string(),
        }
    }

    pub fn is_sentry_configured(&self) -> bool {
        !self.sentry_dsn.trim().is_empty()
    }

    pub fn resolved_status_base_url(&self) -> String {
        resolve_status_base_url(&self.feedback_status_url, &self.sentry_tunnel_url, false)
    }

    pub fn is_status_sync_configured(&self) -> bool {
        !self.resolved_status_base_url().is_empty()
    }
}

pub struct EnvelopeInput<'a> {
    pub event_id: &'a str,
    pub installation_id: &'a str,
    pub reporter_name: &'a str,
    pub problem_description: &'a str,
    pub app_version: &'a str,
    pub environment: &'a str,
    pub device_summary: &'a str,
    pub kind: &'a str,
    pub include_logs: bool,
    pub client_log_count: usize,
    pub attachment_filename: &'a str,
    pub compressed_log_bytes: &'a [u8],
    pub sent_at_iso: &'a str,
}

pub fn normalize_kind(raw: &str) -> &'static str {
    if raw.trim().eq_ignore_ascii_case("feature") {
        "feature"
    } else {
        "bug"
    }
}

pub fn normalize_event_id(raw: &str) -> String {
    let cleaned = raw.trim().replace('-', "").to_ascii_lowercase();
    if cleaned.len() == 32 && cleaned.chars().all(|c| c.is_ascii_hexdigit()) {
        cleaned
    } else {
        String::new()
    }
}

pub fn normalize_short_code(raw: &str) -> String {
    let cleaned = raw.trim().trim_start_matches('#').to_ascii_uppercase();
    if cleaned.len() == 8 && cleaned.chars().all(|c| c.is_ascii_hexdigit()) {
        cleaned
    } else {
        String::new()
    }
}

pub fn format_short_code(event_id: &str) -> String {
    event_id
        .replace('-', "")
        .chars()
        .take(8)
        .collect::<String>()
        .to_ascii_uppercase()
}

pub(super) fn is_terminal_status(status: &str) -> bool {
    matches!(status, "fixed_in_version" | "resolved" | "ignored")
}

pub(super) fn summarize_problem_description(raw: &str, kind: &str) -> String {
    let sanitized = logs::sanitize_text(raw);
    let first = sanitized
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .unwrap_or("");
    if first.is_empty() {
        return if kind == "feature" {
            "功能建议".to_string()
        } else {
            "手动诊断日志上报".to_string()
        };
    }
    let chars: Vec<char> = first.chars().collect();
    if chars.len() > 120 {
        let head: String = chars[..119].iter().collect();
        format!("{}…", head.trim_end())
    } else {
        first.to_string()
    }
}

pub(super) fn select_event_ids_for_batch_query(
    records: &[FeedbackRecord],
    is_lookup: impl Fn(&FeedbackRecord) -> bool,
) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for rec in records {
        if is_lookup(rec) {
            continue;
        }
        let norm = normalize_event_id(&rec.event_id);
        if norm.is_empty() || !seen.insert(norm.clone()) {
            continue;
        }
        out.push(norm);
        if out.len() >= MAX_BATCH_QUERY_IDS {
            break;
        }
    }
    out
}

pub(super) fn should_auto_refresh(records: &[FeedbackRecord], now_ms: u64) -> bool {
    let has_target = records
        .iter()
        .any(|r| !normalize_event_id(&r.event_id).is_empty() && !is_terminal_status(&r.status));
    if !has_target {
        return false;
    }
    let latest = records.iter().map(|r| r.last_checked_at_ms).max().unwrap_or(0);
    latest == 0 || now_ms.saturating_sub(latest) >= AUTO_REFRESH_INTERVAL_MS
}

pub(super) fn merge_single_record(
    rec: &mut FeedbackRecord,
    remote: &FeedbackRemoteItem,
    checked_at_ms: u64,
    mark_as_lookup: bool,
) {
    let is_placeholder = rec.summary.trim().is_empty() || rec.summary.starts_with("通过反馈码 #");
    if is_placeholder && !remote.summary.trim().is_empty() {
        rec.summary = remote.summary.clone();
    }
    if rec.reported_version.trim().is_empty() && !remote.reported_version.trim().is_empty() {
        rec.reported_version = remote.reported_version.clone();
    }
    if remote.kind == "feature" {
        rec.kind = "feature".to_string();
    }
    rec.status = remote.status.clone();
    rec.fixed_version = remote.fixed_version.clone();
    rec.reply_note = remote.reply_note.clone();
    rec.last_checked_at_ms = checked_at_ms;
    rec.remote_updated_at = remote.updated_at.clone();
    rec.from_code_lookup = mark_as_lookup;
}

pub(super) fn normalize_record_list(records: &mut Vec<FeedbackRecord>) {
    records.sort_by(|a, b| b.created_at_ms.cmp(&a.created_at_ms));
    let mut seen = HashSet::new();
    records.retain(|r| seen.insert(r.event_id.clone()));
    if records.len() > MAX_STORED_RECORDS {
        records.truncate(MAX_STORED_RECORDS);
    }
}
