use crate::logs;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

use super::models::{
    format_short_code, normalize_event_id, normalize_kind, normalize_short_code, EnvelopeInput,
    FeedbackRemoteItem, ParsedSentryDsn,
};

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

pub(super) fn extract_event_id(resp: &str) -> Option<String> {
    let val: serde_json::Value = serde_json::from_str(resp).ok()?;
    let id = val.get("id")?.as_str()?;
    let norm = normalize_event_id(id);
    if norm.len() >= 8 {
        Some(norm)
    } else {
        None
    }
}

pub(super) fn generate_event_id() -> String {
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

pub(super) fn parse_batch_status_json(body: &str) -> Option<HashMap<String, FeedbackRemoteItem>> {
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

pub(super) fn parse_lookup_json(body: &str) -> Option<Option<FeedbackRemoteItem>> {
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

pub(super) async fn fetch_batch_statuses(
    http: &reqwest::Client,
    installation_id: &str,
    base_url: &str,
    event_ids: &[String],
) -> Result<HashMap<String, FeedbackRemoteItem>, String> {
    let resp = http
        .get(format!("{base_url}/v1/feedback/status"))
        .query(&[
            ("installation_id", installation_id),
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

pub(super) async fn lookup_remote_by_code(
    http: &reqwest::Client,
    base_url: &str,
    short_code: &str,
) -> Result<Option<FeedbackRemoteItem>, String> {
    let resp = http
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
