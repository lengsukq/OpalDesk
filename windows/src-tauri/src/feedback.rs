use crate::logs::{self, LogStore};
use chrono::{Local, Utc};
use reqwest::redirect::Policy;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub const MAX_DAILY_REPORTS: u32 = 5;
pub const MAX_STORED_RECORDS: usize = 50;
pub const MAX_BATCH_QUERY_IDS: usize = 20;
pub const AUTO_REFRESH_INTERVAL_MS: u64 = 10 * 60 * 1000;
pub const QQ_GROUP_NUMBER: &str = "1042317917";
pub const LIMIT_REACHED_MESSAGE: &str = "今日反馈与日志上传次数已达上限（5/5），请明天再试";

const CONNECT_TIMEOUT_SECS: u64 = 12;
const REQUEST_TIMEOUT_SECS: u64 = 18;

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

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct FeedbackQuotaDisk {
    #[serde(default)]
    last_report_date: String,
    #[serde(default)]
    daily_report_count: u32,
    #[serde(default)]
    reporter_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FeedbackHistoryDisk {
    #[serde(default = "default_schema_version")]
    version: u32,
    #[serde(default)]
    records: Vec<FeedbackRecord>,
}

fn default_schema_version() -> u32 {
    1
}

struct MutableFeedbackState {
    quota: FeedbackQuotaDisk,
    records: Vec<FeedbackRecord>,
    last_sync_at_ms: u64,
}

pub struct FeedbackManager {
    config: FeedbackBuildConfig,
    installation_id: String,
    quota_path: PathBuf,
    history_path: PathBuf,
    http: reqwest::Client,
    state: Mutex<MutableFeedbackState>,
}

impl FeedbackManager {
    pub fn new(config_dir: &Path, installation_id: String) -> Self {
        Self::with_config(config_dir, installation_id, FeedbackBuildConfig::current())
    }

    pub fn with_config(
        config_dir: &Path,
        installation_id: String,
        config: FeedbackBuildConfig,
    ) -> Self {
        let quota_path = config_dir.join("feedback_quota_v1.json");
        let history_path = config_dir.join("feedback_history_v1.json");
        let quota = load_quota_disk(&quota_path);
        let records = load_history_disk(&history_path);
        let last_sync = records.iter().map(|r| r.last_checked_at_ms).max().unwrap_or(0);
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
            .timeout(Duration::from_secs(REQUEST_TIMEOUT_SECS))
            .redirect(Policy::none())
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());

        Self {
            config,
            installation_id,
            quota_path,
            history_path,
            http,
            state: Mutex::new(MutableFeedbackState {
                quota,
                records,
                last_sync_at_ms: last_sync,
            }),
        }
    }

    pub fn device_summary(&self) -> String {
        let os_label = match std::env::consts::OS {
            "windows" => "Windows",
            "macos" => "macOS",
            other => other,
        };
        format!("{os_label} ({}) · Tauri Desktop", std::env::consts::ARCH)
    }

    pub fn current_quota(&self) -> FeedbackQuotaSnapshot {
        let today = local_date_string();
        let guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let used = if guard.quota.last_report_date.trim() == today {
            guard.quota.daily_report_count.min(MAX_DAILY_REPORTS)
        } else {
            0
        };
        FeedbackQuotaSnapshot::build(today, used, MAX_DAILY_REPORTS)
    }

    pub fn state_snapshot(&self) -> FeedbackStateSnapshot {
        let quota = self.current_quota();
        let guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        FeedbackStateSnapshot {
            sentry_configured: self.config.is_sentry_configured(),
            status_sync_configured: self.config.is_status_sync_configured(),
            app_version: self.config.app_version.clone(),
            device_summary: self.device_summary(),
            qq_group_number: QQ_GROUP_NUMBER,
            quota,
            reporter_name: guard.quota.reporter_name.clone(),
            records: guard.records.clone(),
            last_sync_at_ms: guard.last_sync_at_ms,
        }
    }

    pub fn remove_record(&self, event_id: &str) -> Vec<FeedbackRecord> {
        let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        guard.records.retain(|r| r.event_id != event_id);
        let snap = guard.records.clone();
        persist_history_disk(&self.history_path, &snap);
        snap
    }

    pub fn clear_history(&self) -> Vec<FeedbackRecord> {
        let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        guard.records.clear();
        guard.last_sync_at_ms = 0;
        persist_history_disk(&self.history_path, &[]);
        Vec::new()
    }

    pub async fn submit_report(
        &self,
        reporter_name: &str,
        problem_description: &str,
        kind: &str,
        include_logs: bool,
        context_summary: &str,
        log_store: &LogStore,
    ) -> ManualReportOutcome {
        let clean_user: String = reporter_name.trim().chars().take(64).collect();
        let clean_desc: String = problem_description.trim().chars().take(2000).collect();
        let norm_kind = normalize_kind(kind);

        if !clean_user.is_empty() {
            let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
            guard.quota.reporter_name = clean_user.clone();
            persist_quota_disk(&self.quota_path, &guard.quota);
        }

        let before_quota = self.current_quota();
        if before_quota.is_exhausted {
            return ManualReportOutcome {
                success: false,
                event_id: String::new(),
                short_code: String::new(),
                error_message: LIMIT_REACHED_MESSAGE.to_string(),
                quota: before_quota,
            };
        }

        let Some(parsed_dsn) = parse_sentry_dsn(&self.config.sentry_dsn) else {
            return ManualReportOutcome {
                success: false,
                event_id: String::new(),
                short_code: String::new(),
                error_message: "当前构建未配置 Sentry DSN，暂无法上传（本次未消耗次数）".to_string(),
                quota: before_quota,
            };
        };

        let event_id = generate_event_id();
        let sent_at_iso = Utc::now().format("%Y-%m-%dT%H:%M:%SZ").to_string();
        let entries = if include_logs {
            log_store.snapshot()
        } else {
            Vec::new()
        };
        let attached_count = entries.len().min(logs::DEFAULT_MAX_REPORT_ENTRIES);
        let compressed_log_bytes = if include_logs {
            let sanitized = logs::build_sanitized_report_text(
                &self.config.app_version,
                &self.device_summary(),
                &clean_user,
                &clean_desc,
                context_summary,
                &entries,
                &logs::format_now_datetime(),
                logs::DEFAULT_MAX_REPORT_ENTRIES,
            );
            logs::gzip_compress(&sanitized, logs::DEFAULT_MAX_GZIP_BYTES)
        } else {
            Vec::new()
        };
        let attach_name = if include_logs {
            format!("opaldesk_win_diag_{}.log.gz", logs::format_now_filestamp())
        } else {
            String::new()
        };

        let body = build_envelope_bytes(
            &parsed_dsn.raw_dsn,
            EnvelopeInput {
                event_id: &event_id,
                installation_id: &self.installation_id,
                reporter_name: &clean_user,
                problem_description: &clean_desc,
                app_version: &self.config.app_version,
                environment: if cfg!(debug_assertions) { "debug" } else { "production" },
                device_summary: &self.device_summary(),
                kind: norm_kind,
                include_logs,
                client_log_count: attached_count,
                attachment_filename: &attach_name,
                compressed_log_bytes: &compressed_log_bytes,
                sent_at_iso: &sent_at_iso,
            },
        );

        let clean_tunnel = self.config.sentry_tunnel_url.trim();
        let target_url = if clean_tunnel.starts_with("https://") || clean_tunnel.starts_with("http://") {
            clean_tunnel.to_string()
        } else {
            parsed_dsn.envelope_endpoint_url.clone()
        };

        let auth_header = format!(
            "Sentry sentry_version=7, sentry_client=opaldesk-windows-manual/{}, sentry_key={}",
            self.config.app_version, parsed_dsn.public_key
        );

        let resp = self
            .http
            .post(&target_url)
            .header("Content-Type", "application/x-sentry-envelope")
            .header("X-Sentry-Auth", auth_header)
            .header("X-OpalDesk-Installation-Id", &self.installation_id)
            .body(body)
            .send()
            .await;

        match resp {
            Ok(r) => {
                let code = r.status().as_u16();
                let text = r.text().await.unwrap_or_default();
                if (200..=299).contains(&code) {
                    let confirmed_id = extract_event_id(&text).unwrap_or(event_id);
                    let short_code = format_short_code(&confirmed_id);
                    let after_quota = self.record_confirmed_upload_and_history(
                        &confirmed_id,
                        &short_code,
                        &clean_desc,
                        norm_kind,
                    );
                    if include_logs {
                        log_store.clear();
                    }
                    log_store.info(
                        "Feedback",
                        &format!(
                            "Manual {norm_kind} feedback confirmed eventId={confirmed_id} logs={include_logs} ({}/{})",
                            after_quota.used_today, after_quota.max_daily
                        ),
                    );
                    ManualReportOutcome {
                        success: true,
                        event_id: confirmed_id,
                        short_code,
                        error_message: String::new(),
                        quota: after_quota,
                    }
                } else if code == 429 {
                    let msg = "服务端触发频率限制（HTTP 429），请稍后再试；本次未消耗上传次数".to_string();
                    log_store.warn("Feedback", &msg);
                    ManualReportOutcome {
                        success: false,
                        event_id: String::new(),
                        short_code: String::new(),
                        error_message: msg,
                        quota: self.current_quota(),
                    }
                } else {
                    let msg = format!("上报接口返回异常（HTTP {code}），本次未消耗上传次数");
                    log_store.warn("Feedback", &msg);
                    ManualReportOutcome {
                        success: false,
                        event_id: String::new(),
                        short_code: String::new(),
                        error_message: msg,
                        quota: self.current_quota(),
                    }
                }
            }
            Err(err) => {
                let msg = format!("网络连接失败或超时（{err}），本次未消耗上传次数");
                log_store.warn("Feedback", &msg);
                ManualReportOutcome {
                    success: false,
                    event_id: String::new(),
                    short_code: String::new(),
                    error_message: msg,
                    quota: self.current_quota(),
                }
            }
        }
    }

    pub async fn refresh_statuses(&self, force: bool, log_store: &LogStore) -> FeedbackRefreshOutcome {
        let base_url = self.config.resolved_status_base_url();
        let (records, prev_sync) = {
            let guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
            (guard.records.clone(), guard.last_sync_at_ms)
        };
        if base_url.is_empty() {
            return FeedbackRefreshOutcome {
                success: false,
                updated_count: 0,
                skipped_by_throttle: false,
                message: "当前构建未配置状态查询服务".to_string(),
                records,
                last_sync_at_ms: prev_sync,
            };
        }

        let now_ms = logs::now_unix_ms();
        if !force && !should_auto_refresh(&records, now_ms) {
            return FeedbackRefreshOutcome {
                success: true,
                updated_count: 0,
                skipped_by_throttle: true,
                message: String::new(),
                records,
                last_sync_at_ms: prev_sync,
            };
        }

        let is_lookup = |r: &FeedbackRecord| r.from_code_lookup || r.summary.starts_with("通过反馈码 #");
        let event_ids = select_event_ids_for_batch_query(&records, is_lookup);
        let lookup_records: Vec<FeedbackRecord> = records
            .iter()
            .filter(|r| is_lookup(r) && (!is_terminal_status(&r.status) || force))
            .take(5)
            .cloned()
            .collect();

        if event_ids.is_empty() && lookup_records.is_empty() {
            return FeedbackRefreshOutcome {
                success: true,
                updated_count: 0,
                skipped_by_throttle: false,
                message: String::new(),
                records,
                last_sync_at_ms: prev_sync,
            };
        }

        let mut merged: HashMap<String, FeedbackRemoteItem> = HashMap::new();
        let mut last_err: Option<String> = None;

        if !event_ids.is_empty() {
            match self.fetch_batch_statuses(&base_url, &event_ids).await {
                Ok(items) => {
                    merged.extend(items);
                }
                Err(reason) => {
                    log_store.warn("FeedbackSync", &format!("Batch status sync failed: {reason}"));
                    last_err = Some(reason);
                }
            }
        }

        for rec in &lookup_records {
            match self.lookup_remote_by_code(&base_url, &rec.short_code).await {
                Ok(Some(item)) => {
                    let key = normalize_event_id(&item.event_id);
                    let map_key = if key.is_empty() { rec.event_id.clone() } else { key };
                    merged.insert(map_key, item);
                }
                Ok(None) => {}
                Err(reason) => {
                    log_store.warn(
                        "FeedbackSync",
                        &format!("Lookup refresh failed for #{}: {reason}", rec.short_code),
                    );
                    if last_err.is_none() {
                        last_err = Some(reason);
                    }
                }
            }
        }

        if !merged.is_empty() {
            let checked_at = logs::now_unix_ms();
            let updated_count = merged.len();
            let next_records = self.merge_remote_statuses(&merged, checked_at);
            return FeedbackRefreshOutcome {
                success: true,
                updated_count,
                skipped_by_throttle: false,
                message: String::new(),
                records: next_records,
                last_sync_at_ms: checked_at,
            };
        }

        if let Some(err) = last_err {
            return FeedbackRefreshOutcome {
                success: false,
                updated_count: 0,
                skipped_by_throttle: false,
                message: err,
                records,
                last_sync_at_ms: prev_sync,
            };
        }

        FeedbackRefreshOutcome {
            success: true,
            updated_count: 0,
            skipped_by_throttle: false,
            message: String::new(),
            records,
            last_sync_at_ms: prev_sync,
        }
    }

    pub async fn lookup_by_code(&self, raw_code: &str, log_store: &LogStore) -> FeedbackLookupOutcome {
        let base_url = self.config.resolved_status_base_url();
        let (records, prev_sync) = {
            let guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
            (guard.records.clone(), guard.last_sync_at_ms)
        };
        if base_url.is_empty() {
            return FeedbackLookupOutcome {
                success: false,
                found: false,
                item: None,
                message: "当前构建未配置状态查询服务".to_string(),
                records,
                last_sync_at_ms: prev_sync,
            };
        }

        let code = normalize_short_code(raw_code);
        if code.is_empty() {
            return FeedbackLookupOutcome {
                success: false,
                found: false,
                item: None,
                message: "请输入 8 位反馈码（如 A1B2C3D4）".to_string(),
                records,
                last_sync_at_ms: prev_sync,
            };
        }

        match self.lookup_remote_by_code(&base_url, &code).await {
            Ok(Some(item)) => {
                let checked_at = logs::now_unix_ms();
                let next_records = self.upsert_looked_up_record(item.clone(), checked_at);
                FeedbackLookupOutcome {
                    success: true,
                    found: true,
                    item: Some(item),
                    message: String::new(),
                    records: next_records,
                    last_sync_at_ms: checked_at,
                }
            }
            Ok(None) => FeedbackLookupOutcome {
                success: true,
                found: false,
                item: None,
                message: "未查到该反馈码记录，请检查输入是否正确".to_string(),
                records,
                last_sync_at_ms: prev_sync,
            },
            Err(reason) => {
                log_store.warn("FeedbackSync", &format!("Lookup #{code} failed: {reason}"));
                FeedbackLookupOutcome {
                    success: false,
                    found: false,
                    item: None,
                    message: reason,
                    records,
                    last_sync_at_ms: prev_sync,
                }
            }
        }
    }

    async fn fetch_batch_statuses(
        &self,
        base_url: &str,
        event_ids: &[String],
    ) -> Result<HashMap<String, FeedbackRemoteItem>, String> {
        let resp = self
            .http
            .get(format!("{base_url}/v1/feedback/status"))
            .query(&[
                ("installation_id", self.installation_id.as_str()),
                ("event_ids", &event_ids.join(",")),
            ])
            .header("Accept", "application/json")
            .header("User-Agent", "OpalDesk-Windows-FeedbackClient/1.0")
            .send()
            .await
            .map_err(|e| format!("网络请求失败（{e}）"))?;

        let code = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        if code == 429 {
            return Err("状态查询过于频繁，请稍后再试".to_string());
        }
        if !(200..=299).contains(&code) {
            return Err(format!("状态服务响应异常（HTTP {code}）"));
        }
        parse_batch_status_json(&body).ok_or_else(|| "状态服务返回格式异常".to_string())
    }

    async fn lookup_remote_by_code(
        &self,
        base_url: &str,
        short_code: &str,
    ) -> Result<Option<FeedbackRemoteItem>, String> {
        let resp = self
            .http
            .get(format!("{base_url}/v1/feedback/lookup"))
            .query(&[("code", short_code)])
            .header("Accept", "application/json")
            .header("User-Agent", "OpalDesk-Windows-FeedbackClient/1.0")
            .send()
            .await
            .map_err(|e| format!("网络请求失败（{e}）"))?;

        let code = resp.status().as_u16();
        if code == 404 {
            return Ok(None);
        }
        if code == 429 {
            return Err("查询过于频繁，请稍后再试".to_string());
        }
        if !(200..=299).contains(&code) {
            return Err(format!("查询服务响应异常（HTTP {code}）"));
        }
        let body = resp.text().await.unwrap_or_default();
        parse_lookup_json(&body).ok_or_else(|| "查询接口返回格式异常".to_string())
    }

    fn record_confirmed_upload_and_history(
        &self,
        event_id: &str,
        short_code: &str,
        problem_description: &str,
        kind: &str,
    ) -> FeedbackQuotaSnapshot {
        let today = local_date_string();
        let now_ms = logs::now_unix_ms();
        let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let prev_used = if guard.quota.last_report_date.trim() == today {
            guard.quota.daily_report_count.min(MAX_DAILY_REPORTS)
        } else {
            0
        };
        let next_used = (prev_used + 1).min(MAX_DAILY_REPORTS);
        guard.quota.last_report_date = today.clone();
        guard.quota.daily_report_count = next_used;
        persist_quota_disk(&self.quota_path, &guard.quota);

        let norm_id = normalize_event_id(event_id);
        let norm_code = normalize_short_code(short_code);
        let key = if norm_id.is_empty() {
            format!("code:{norm_code}")
        } else {
            norm_id
        };
        let rec = FeedbackRecord {
            event_id: key.clone(),
            short_code: norm_code.clone(),
            summary: summarize_problem_description(problem_description, kind),
            reported_version: self.config.app_version.clone(),
            created_at_ms: now_ms,
            status: "open".to_string(),
            fixed_version: String::new(),
            reply_note: String::new(),
            last_checked_at_ms: 0,
            remote_updated_at: String::new(),
            from_code_lookup: false,
            kind: kind.to_string(),
        };
        guard.records.retain(|r| {
            r.event_id != key && (norm_code.is_empty() || !r.short_code.eq_ignore_ascii_case(&norm_code))
        });
        guard.records.insert(0, rec);
        normalize_record_list(&mut guard.records);
        persist_history_disk(&self.history_path, &guard.records);

        FeedbackQuotaSnapshot::build(today, next_used, MAX_DAILY_REPORTS)
    }

    fn merge_remote_statuses(
        &self,
        remote_items: &HashMap<String, FeedbackRemoteItem>,
        checked_at_ms: u64,
    ) -> Vec<FeedbackRecord> {
        let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        for rec in &mut guard.records {
            let remote = remote_items.get(&rec.event_id).or_else(|| {
                remote_items
                    .values()
                    .find(|it| !rec.short_code.is_empty() && it.short_code.eq_ignore_ascii_case(&rec.short_code))
            });
            if let Some(rem) = remote {
                merge_single_record(rec, rem, checked_at_ms, rec.from_code_lookup);
            }
        }
        normalize_record_list(&mut guard.records);
        guard.last_sync_at_ms = checked_at_ms;
        let snap = guard.records.clone();
        persist_history_disk(&self.history_path, &snap);
        snap
    }

    fn upsert_looked_up_record(
        &self,
        remote: FeedbackRemoteItem,
        checked_at_ms: u64,
    ) -> Vec<FeedbackRecord> {
        let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let norm_id = normalize_event_id(&remote.event_id);
        let norm_code = normalize_short_code(&remote.short_code);
        if let Some(existing) = guard.records.iter_mut().find(|r| {
            (!norm_id.is_empty() && r.event_id == norm_id)
                || (!norm_code.is_empty() && r.short_code.eq_ignore_ascii_case(&norm_code))
        }) {
            if !norm_id.is_empty() {
                existing.event_id = norm_id;
            }
            let keep_flag = existing.from_code_lookup;
            merge_single_record(existing, &remote, checked_at_ms, keep_flag);
        } else {
            let summary = if remote.summary.trim().is_empty() {
                format!("通过反馈码 #{norm_code} 找回的记录")
            } else {
                remote.summary.clone()
            };
            guard.records.insert(
                0,
                FeedbackRecord {
                    event_id: if norm_id.is_empty() {
                        format!("code:{norm_code}")
                    } else {
                        norm_id
                    },
                    short_code: norm_code,
                    summary,
                    reported_version: remote.reported_version,
                    created_at_ms: checked_at_ms,
                    status: remote.status,
                    fixed_version: remote.fixed_version,
                    reply_note: remote.reply_note,
                    last_checked_at_ms: checked_at_ms,
                    remote_updated_at: remote.updated_at,
                    from_code_lookup: true,
                    kind: remote.kind,
                },
            );
        }
        normalize_record_list(&mut guard.records);
        guard.last_sync_at_ms = checked_at_ms;
        let snap = guard.records.clone();
        persist_history_disk(&self.history_path, &snap);
        snap
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

pub fn parse_sentry_dsn(raw_dsn: &str) -> Option<ParsedSentryDsn> {
    let trimmed = raw_dsn.trim();
    if trimmed.is_empty() {
        return None;
    }
    let parsed = reqwest::Url::parse(trimmed).ok()?;
    let scheme = parsed.scheme();
    if scheme != "https" && scheme != "http" {
        return None;
    }
    let host = parsed.host_str()?.trim();
    if host.is_empty() {
        return None;
    }
    let public_key = parsed.username().trim();
    if public_key.is_empty() {
        return None;
    }
    let segments: Vec<&str> = parsed.path().split('/').filter(|s| !s.is_empty()).collect();
    let project_id = (*segments.last()?).to_string();
    let prefix = if segments.len() > 1 {
        format!("/{}", segments[..segments.len() - 1].join("/"))
    } else {
        String::new()
    };
    let port_part = parsed.port().map(|p| format!(":{p}")).unwrap_or_default();
    let endpoint = format!("{scheme}://{host}{port_part}{prefix}/api/{project_id}/envelope/");
    Some(ParsedSentryDsn {
        raw_dsn: trimmed.to_string(),
        public_key: public_key.to_string(),
        project_id,
        envelope_endpoint_url: endpoint,
    })
}

pub fn build_envelope_bytes(dsn: &str, input: EnvelopeInput<'_>) -> Vec<u8> {
    let clean_event_id = input.event_id.replace('-', "").to_ascii_lowercase();
    let sanitized_user = logs::sanitize_text(input.reporter_name.trim());
    let clean_reporter: String = if sanitized_user.is_empty() {
        "anonymous".to_string()
    } else {
        sanitized_user.chars().take(64).collect()
    };
    let clean_desc: String = logs::sanitize_text(input.problem_description.trim())
        .chars()
        .take(2000)
        .collect();
    let is_feature = input.kind == "feature";
    let has_attachment = input.include_logs && !input.compressed_log_bytes.is_empty();
    let first_line: String = clean_desc
        .lines()
        .next()
        .unwrap_or("")
        .chars()
        .take(90)
        .collect();
    let headline = if is_feature {
        if first_line.is_empty() {
            format!("[Feature Request] Feature suggestion from {clean_reporter}")
        } else {
            format!("[Feature Request] {first_line}")
        }
    } else if first_line.is_empty() {
        format!("[User Report] Manual diagnostic log from {clean_reporter}")
    } else {
        format!("[User Report] {first_line}")
    };
    let level = if is_feature { "info" } else { "error" };
    let short_code = format_short_code(&clean_event_id);

    let env_header = serde_json::json!({
        "event_id": clean_event_id,
        "dsn": dsn,
        "sent_at": input.sent_at_iso,
        "sdk": {
            "name": "opaldesk.windows.manual",
            "version": input.app_version,
        }
    });

    let mut event_obj = serde_json::json!({
        "event_id": clean_event_id,
        "timestamp": input.sent_at_iso,
        "level": level,
        "platform": "native",
        "logger": "opaldesk.manual_report",
        "release": format!("io.github.lengsukq.opaldesk.desktop@{}", input.app_version),
        "environment": input.environment,
        "message": { "formatted": headline },
        "user": {
            "id": input.installation_id,
            "username": clean_reporter,
        },
        "tags": {
            "installation_id": input.installation_id,
            "feedback_code": short_code,
            "report_source": "manual_user_report",
            "feedback_kind": input.kind,
            "app_version": input.app_version,
            "client_platform": "windows",
            "device_model": input.device_summary,
        },
        "contexts": {
            "os": {
                "name": std::env::consts::OS,
                "version": std::env::consts::ARCH,
            },
            "app": {
                "app_version": input.app_version,
            }
        },
        "extra": {
            "feedback_kind": input.kind,
            "client_platform": "windows",
            "reporter_name": clean_reporter,
            "user_description": if clean_desc.is_empty() { "(未填写)" } else { clean_desc.as_str() },
            "logs_attached": has_attachment,
            "client_log_count": if has_attachment { input.client_log_count } else { 0 },
            "log_gzip_bytes": if has_attachment { input.compressed_log_bytes.len() } else { 0 },
            "screenshot_attached": false,
        }
    });

    if is_feature {
        if let Some(obj) = event_obj.as_object_mut() {
            obj.insert(
                "fingerprint".to_string(),
                serde_json::json!(["opaldesk-feature", clean_event_id]),
            );
        }
    }

    let env_header_bytes = serde_json::to_vec(&env_header).unwrap_or_default();
    let event_bytes = serde_json::to_vec(&event_obj).unwrap_or_default();
    let event_item_header = format!(
        r#"{{"type":"event","content_type":"application/json","length":{}}}"#,
        event_bytes.len()
    );

    let mut out = Vec::with_capacity(
        env_header_bytes.len()
            + event_bytes.len()
            + if has_attachment { input.compressed_log_bytes.len() } else { 0 }
            + 256,
    );
    out.extend_from_slice(&env_header_bytes);
    out.push(b'\n');
    out.extend_from_slice(event_item_header.as_bytes());
    out.push(b'\n');
    out.extend_from_slice(&event_bytes);
    out.push(b'\n');

    if has_attachment {
        let attach_header = serde_json::json!({
            "type": "attachment",
            "filename": input.attachment_filename,
            "content_type": "application/gzip",
            "length": input.compressed_log_bytes.len(),
        });
        out.extend_from_slice(&serde_json::to_vec(&attach_header).unwrap_or_default());
        out.push(b'\n');
        out.extend_from_slice(input.compressed_log_bytes);
        out.push(b'\n');
    }
    out
}

pub fn resolve_status_base_url(
    feedback_status_url: &str,
    sentry_tunnel_url: &str,
    allow_localhost: bool,
) -> String {
    let clean = clean_base_url(feedback_status_url, allow_localhost);
    if !clean.is_empty() {
        return clean;
    }
    let trimmed = sentry_tunnel_url.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    reqwest::Url::parse(trimmed)
        .ok()
        .and_then(|u| build_origin(&u, allow_localhost))
        .unwrap_or_default()
}

fn clean_base_url(raw: &str, allow_localhost: bool) -> String {
    let trimmed = raw.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return String::new();
    }
    let Ok(url) = reqwest::Url::parse(trimmed) else {
        return String::new();
    };
    let Some(origin) = build_origin(&url, allow_localhost) else {
        return String::new();
    };
    let path = url.path().trim_end_matches('/');
    if path.is_empty() || path == "/" {
        origin
    } else {
        format!("{origin}{path}")
    }
}

fn build_origin(url: &reqwest::Url, allow_localhost: bool) -> Option<String> {
    let scheme = url.scheme();
    let host = url.host_str()?.to_ascii_lowercase();
    let is_local = host == "127.0.0.1" || host == "localhost";
    let valid = scheme == "https" || (allow_localhost && scheme == "http" && is_local);
    if !valid {
        return None;
    }
    let port_part = url.port().map(|p| format!(":{p}")).unwrap_or_default();
    Some(format!("{scheme}://{host}{port_part}"))
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

fn is_terminal_status(status: &str) -> bool {
    matches!(status, "fixed_in_version" | "resolved" | "ignored")
}

fn summarize_problem_description(raw: &str, kind: &str) -> String {
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

fn select_event_ids_for_batch_query(
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

fn should_auto_refresh(records: &[FeedbackRecord], now_ms: u64) -> bool {
    let has_target = records
        .iter()
        .any(|r| !normalize_event_id(&r.event_id).is_empty() && !is_terminal_status(&r.status));
    if !has_target {
        return false;
    }
    let latest = records.iter().map(|r| r.last_checked_at_ms).max().unwrap_or(0);
    latest == 0 || now_ms.saturating_sub(latest) >= AUTO_REFRESH_INTERVAL_MS
}

fn merge_single_record(
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

fn normalize_record_list(records: &mut Vec<FeedbackRecord>) {
    records.sort_by(|a, b| b.created_at_ms.cmp(&a.created_at_ms));
    let mut seen = HashSet::new();
    records.retain(|r| seen.insert(r.event_id.clone()));
    if records.len() > MAX_STORED_RECORDS {
        records.truncate(MAX_STORED_RECORDS);
    }
}

fn parse_batch_status_json(body: &str) -> Option<HashMap<String, FeedbackRemoteItem>> {
    let val: serde_json::Value = serde_json::from_str(body).ok()?;
    val.get("schema")?.as_u64()?;
    let items_obj = val.get("items")?.as_object()?;
    let mut map = HashMap::new();
    for (k, v) in items_obj {
        let norm_id = normalize_event_id(k);
        if norm_id.is_empty() {
            continue;
        }
        if let Some(item) = parse_remote_item_val(v, &norm_id) {
            map.insert(norm_id, item);
        }
    }
    Some(map)
}

fn parse_lookup_json(body: &str) -> Option<Option<FeedbackRemoteItem>> {
    let val: serde_json::Value = serde_json::from_str(body).ok()?;
    val.get("schema")?.as_u64()?;
    let found = val.get("found").and_then(|v| v.as_bool()).unwrap_or(false);
    if !found {
        return Some(None);
    }
    let item = val.get("item").and_then(|v| parse_remote_item_val(v, ""));
    Some(item)
}

fn parse_remote_item_val(v: &serde_json::Value, fallback_id: &str) -> Option<FeedbackRemoteItem> {
    let obj = v.as_object()?;
    let raw_id = obj.get("eventId").and_then(|x| x.as_str()).unwrap_or("");
    let norm_id = normalize_event_id(raw_id);
    let event_id = if norm_id.is_empty() {
        fallback_id.to_string()
    } else {
        norm_id
    };
    let raw_code = obj.get("shortCode").and_then(|x| x.as_str()).unwrap_or("");
    let norm_code = normalize_short_code(raw_code);
    let short_code = if norm_code.is_empty() && event_id.len() >= 8 {
        format_short_code(&event_id)
    } else {
        norm_code
    };
    if short_code.is_empty() && event_id.is_empty() {
        return None;
    }
    let str_field = |k: &str, max: usize| -> String {
        obj.get(k)
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .trim()
            .chars()
            .take(max)
            .collect()
    };
    Some(FeedbackRemoteItem {
        event_id,
        short_code,
        status: match str_field("status", 40).as_str() {
            "fixed_pending_release" => "fixed_pending_release",
            "fixed_in_version" => "fixed_in_version",
            "resolved" => "resolved",
            "ignored" => "ignored",
            _ => "open",
        }
        .to_string(),
        fixed_version: str_field("fixedVersion", 40),
        reply_note: str_field("replyNote", 240),
        summary: str_field("summary", 120),
        reported_version: str_field("reportedVersion", 40),
        updated_at: str_field("updatedAt", 64),
        kind: normalize_kind(&str_field("kind", 16)).to_string(),
    })
}

fn extract_event_id(resp: &str) -> Option<String> {
    let val: serde_json::Value = serde_json::from_str(resp).ok()?;
    let id = val.get("id")?.as_str()?;
    let norm = normalize_event_id(id);
    if norm.len() >= 8 {
        Some(norm)
    } else {
        None
    }
}

fn generate_event_id() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let seed = format!(
        "opaldesk-ev-{nanos}-{}-{:?}",
        std::process::id(),
        std::thread::current().id()
    );
    let digest = Sha256::digest(seed.as_bytes());
    let mut hex = String::with_capacity(32);
    for b in &digest[..16] {
        use std::fmt::Write;
        let _ = write!(&mut hex, "{b:02x}");
    }
    hex
}

fn local_date_string() -> String {
    Local::now().format("%Y-%m-%d").to_string()
}

fn load_quota_disk(path: &Path) -> FeedbackQuotaDisk {
    fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

fn persist_quota_disk(path: &Path, quota: &FeedbackQuotaDisk) {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(bytes) = serde_json::to_vec_pretty(quota) {
        let tmp = path.with_extension("json.tmp");
        if fs::write(&tmp, &bytes).is_ok() {
            if fs::rename(&tmp, path).is_err() {
                let _ = fs::copy(&tmp, path);
                let _ = fs::remove_file(&tmp);
            }
        }
    }
}

fn load_history_disk(path: &Path) -> Vec<FeedbackRecord> {
    let mut records = fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str::<FeedbackHistoryDisk>(&raw).ok())
        .map(|d| d.records)
        .unwrap_or_default();
    normalize_record_list(&mut records);
    records
}

fn persist_history_disk(path: &Path, records: &[FeedbackRecord]) {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let payload = FeedbackHistoryDisk {
        version: 1,
        records: records.to_vec(),
    };
    if let Ok(bytes) = serde_json::to_vec_pretty(&payload) {
        let tmp = path.with_extension("json.tmp");
        if fs::write(&tmp, &bytes).is_ok() {
            if fs::rename(&tmp, path).is_err() {
                let _ = fs::copy(&tmp, path);
                let _ = fs::remove_file(&tmp);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_sentry_dsn_and_resolves_tunnel_origin() {
        let dsn = "https://abc123pub@o12345.ingest.us.sentry.io/450999";
        let parsed = parse_sentry_dsn(dsn).expect("valid dsn");
        assert_eq!(parsed.public_key, "abc123pub");
        assert_eq!(parsed.project_id, "450999");
        assert_eq!(
            parsed.envelope_endpoint_url,
            "https://o12345.ingest.us.sentry.io/api/450999/envelope/"
        );

        assert_eq!(
            resolve_status_base_url("", "https://feedback.example.com/envelope", false),
            "https://feedback.example.com"
        );
    }

    #[test]
    fn builds_feature_and_bug_envelopes_matching_worker_contract() {
        let dsn = "https://abc123pub@o12345.ingest.us.sentry.io/450999";
        let ev_id = "0123456789abcdef0123456789abcdef";

        let feature_bytes = build_envelope_bytes(
            dsn,
            EnvelopeInput {
                event_id: ev_id,
                installation_id: "win-1234567890abcdef",
                reporter_name: "Leo",
                problem_description: "希望支持小窗置顶",
                app_version: "1.0.18",
                environment: "production",
                device_summary: "Windows (x86_64)",
                kind: "feature",
                include_logs: false,
                client_log_count: 0,
                attachment_filename: "",
                compressed_log_bytes: &[],
                sent_at_iso: "2026-11-26T10:00:00Z",
            },
        );
        let text = String::from_utf8(feature_bytes).expect("utf8 envelope without binary attachment");
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 3);
        let event: serde_json::Value = serde_json::from_str(lines[2]).expect("event json");
        assert_eq!(event["level"], "info");
        assert_eq!(event["tags"]["feedback_kind"], "feature");
        assert_eq!(event["tags"]["feedback_code"], "01234567");
        assert_eq!(event["tags"]["report_source"], "manual_user_report");
        assert_eq!(event["tags"]["client_platform"], "windows");
        assert_eq!(event["fingerprint"], serde_json::json!(["opaldesk-feature", ev_id]));
        assert!(event["message"]["formatted"]
            .as_str()
            .unwrap()
            .starts_with("[Feature Request] "));
    }

    #[test]
    fn daily_quota_and_history_persist() {
        let dir = std::env::temp_dir().join(format!("opaldesk-fb-test-{}", logs::now_unix_ms()));
        let cfg = FeedbackBuildConfig {
            sentry_dsn: String::new(),
            sentry_tunnel_url: "https://feedback.example.com/envelope".to_string(),
            feedback_status_url: String::new(),
            app_version: "1.0.18".to_string(),
        };
        let mgr = FeedbackManager::with_config(&dir, "win-112233445566".to_string(), cfg);
        assert_eq!(mgr.current_quota().remaining, 5);
        let q = mgr.record_confirmed_upload_and_history(
            "abcdef0123456789abcdef0123456789",
            "ABCDEF01",
            "新增历史小窗栏",
            "feature",
        );
        assert_eq!(q.used_today, 1);
        assert_eq!(q.remaining, 4);
        let snap = mgr.state_snapshot();
        assert_eq!(snap.records.len(), 1);
        assert_eq!(snap.records[0].kind, "feature");
        assert_eq!(snap.records[0].short_code, "ABCDEF01");
        let _ = fs::remove_dir_all(dir);
    }
}
