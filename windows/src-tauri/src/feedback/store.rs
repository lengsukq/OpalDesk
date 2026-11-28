use crate::logs;
use chrono::Local;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::Path;

use super::models::{
    merge_single_record, normalize_event_id, normalize_record_list, normalize_short_code,
    summarize_problem_description, FeedbackQuotaSnapshot, FeedbackRecord, FeedbackRemoteItem,
    MAX_DAILY_REPORTS,
};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub(super) struct FeedbackQuotaDisk {
    #[serde(default)]
    pub last_report_date: String,
    #[serde(default)]
    pub daily_report_count: u32,
    #[serde(default)]
    pub reporter_name: String,
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

pub(super) struct MutableFeedbackState {
    pub quota: FeedbackQuotaDisk,
    pub records: Vec<FeedbackRecord>,
    pub last_sync_at_ms: u64,
}

pub(super) fn local_date_string() -> String {
    Local::now().format("%Y-%m-%d").to_string()
}

pub(super) fn load_quota_disk(path: &Path) -> FeedbackQuotaDisk {
    fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

pub(super) fn persist_quota_disk(path: &Path, quota: &FeedbackQuotaDisk) {
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

pub(super) fn load_history_disk(path: &Path) -> Vec<FeedbackRecord> {
    let mut records = fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str::<FeedbackHistoryDisk>(&raw).ok())
        .map(|d| d.records)
        .unwrap_or_default();
    normalize_record_list(&mut records);
    records
}

pub(super) fn persist_history_disk(path: &Path, records: &[FeedbackRecord]) {
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

pub(super) fn record_confirmed_upload(
    state: &mut MutableFeedbackState,
    quota_path: &Path,
    history_path: &Path,
    app_version: &str,
    event_id: &str,
    short_code: &str,
    problem_description: &str,
    kind: &str,
) -> FeedbackQuotaSnapshot {
    let today = local_date_string();
    let now_ms = logs::now_unix_ms();
    let prev_used = if state.quota.last_report_date.trim() == today {
        state.quota.daily_report_count.min(MAX_DAILY_REPORTS)
    } else {
        0
    };
    let next_used = (prev_used + 1).min(MAX_DAILY_REPORTS);
    state.quota.last_report_date = today.clone();
    state.quota.daily_report_count = next_used;
    persist_quota_disk(quota_path, &state.quota);

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
        reported_version: app_version.to_string(),
        created_at_ms: now_ms,
        status: "open".to_string(),
        fixed_version: String::new(),
        reply_note: String::new(),
        last_checked_at_ms: 0,
        remote_updated_at: String::new(),
        from_code_lookup: false,
        kind: kind.to_string(),
    };
    state.records.retain(|r| {
        r.event_id != key
            && (norm_code.is_empty() || !r.short_code.eq_ignore_ascii_case(&norm_code))
    });
    state.records.insert(0, rec);
    normalize_record_list(&mut state.records);
    persist_history_disk(history_path, &state.records);

    FeedbackQuotaSnapshot::build(today, next_used, MAX_DAILY_REPORTS)
}

pub(super) fn merge_remote_statuses(
    state: &mut MutableFeedbackState,
    history_path: &Path,
    remote_items: &HashMap<String, FeedbackRemoteItem>,
    checked_at_ms: u64,
) -> Vec<FeedbackRecord> {
    for rec in &mut state.records {
        let remote = remote_items.get(&rec.event_id).or_else(|| {
            remote_items.values().find(|it| {
                !rec.short_code.is_empty() && it.short_code.eq_ignore_ascii_case(&rec.short_code)
            })
        });
        if let Some(rem) = remote {
            merge_single_record(rec, rem, checked_at_ms, rec.from_code_lookup);
        }
    }
    normalize_record_list(&mut state.records);
    state.last_sync_at_ms = checked_at_ms;
    let snap = state.records.clone();
    persist_history_disk(history_path, &snap);
    snap
}

pub(super) fn upsert_looked_up_record(
    state: &mut MutableFeedbackState,
    history_path: &Path,
    remote: FeedbackRemoteItem,
    checked_at_ms: u64,
) -> Vec<FeedbackRecord> {
    let norm_id = normalize_event_id(&remote.event_id);
    let norm_code = normalize_short_code(&remote.short_code);
    if let Some(existing) = state.records.iter_mut().find(|r| {
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
        state.records.insert(
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
    normalize_record_list(&mut state.records);
    state.last_sync_at_ms = checked_at_ms;
    let snap = state.records.clone();
    persist_history_disk(history_path, &snap);
    snap
}
