mod models;
mod store;
mod transport;

use crate::logs::{self, LogStore};
use chrono::Utc;
use reqwest::redirect::Policy;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use models::{is_terminal_status, select_event_ids_for_batch_query, should_auto_refresh};
use store::{
    load_history_disk, load_quota_disk, local_date_string, merge_remote_statuses,
    persist_history_disk, persist_quota_disk, record_confirmed_upload, upsert_looked_up_record,
    MutableFeedbackState,
};
use transport::{extract_event_id, fetch_batch_statuses, generate_event_id, lookup_remote_by_code};

pub use models::{
    format_short_code, normalize_event_id, normalize_kind, normalize_short_code, EnvelopeInput,
    FeedbackBuildConfig, FeedbackLookupOutcome, FeedbackQuotaSnapshot, FeedbackRecord,
    FeedbackRefreshOutcome, FeedbackRemoteItem, FeedbackStateSnapshot, ManualReportOutcome,
    ParsedSentryDsn, AUTO_REFRESH_INTERVAL_MS, LIMIT_REACHED_MESSAGE, MAX_BATCH_QUERY_IDS,
    MAX_DAILY_REPORTS, MAX_STORED_RECORDS, QQ_GROUP_NUMBER,
};
pub use transport::{build_envelope_bytes, parse_sentry_dsn, resolve_status_base_url};

const CONNECT_TIMEOUT_SECS: u64 = 12;
const REQUEST_TIMEOUT_SECS: u64 = 18;

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
        let entries = if include_logs { log_store.snapshot() } else { Vec::new() };
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
            match fetch_batch_statuses(&self.http, &self.installation_id, &base_url, &event_ids).await {
                Ok(items) => merged.extend(items),
                Err(reason) => {
                    log_store.warn("FeedbackSync", &format!("Batch status sync failed: {reason}"));
                    last_err = Some(reason);
                }
            }
        }

        for rec in &lookup_records {
            match lookup_remote_by_code(&self.http, &base_url, &rec.short_code).await {
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
            let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let next_records = merge_remote_statuses(&mut guard, &self.history_path, &merged, checked_at);
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

        match lookup_remote_by_code(&self.http, &base_url, &code).await {
            Ok(Some(item)) => {
                let checked_at = logs::now_unix_ms();
                let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
                let next_records = upsert_looked_up_record(&mut guard, &self.history_path, item.clone(), checked_at);
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

    fn record_confirmed_upload_and_history(
        &self,
        event_id: &str,
        short_code: &str,
        problem_description: &str,
        kind: &str,
    ) -> FeedbackQuotaSnapshot {
        let mut guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        record_confirmed_upload(
            &mut guard,
            &self.quota_path,
            &self.history_path,
            &self.config.app_version,
            event_id,
            short_code,
            problem_description,
            kind,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

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
