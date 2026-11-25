use crate::fingerprint;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

const STORE_SCHEMA_VERSION: u32 = 1;
const MAX_TRUSTED_DEVICES: usize = 32;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TrustedDevice {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub model: String,
    pub host: String,
    pub port: u16,
    pub cert_fingerprint: String,
    #[serde(default)]
    pub auth_token: String,
    #[serde(default)]
    pub permanent: bool,
    #[serde(default)]
    pub last_connected_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TrustStoreDisk {
    version: u32,
    client_id: String,
    devices: Vec<TrustedDevice>,
}

pub struct TrustStore {
    path: PathBuf,
    inner: Mutex<TrustStoreDisk>,
}

impl TrustStore {
    pub fn new(path: PathBuf) -> Self {
        let disk = Self::load_or_init(&path);
        Self {
            path,
            inner: Mutex::new(disk),
        }
    }

    pub fn client_id(&self) -> String {
        self.inner
            .lock()
            .map(|g| g.client_id.clone())
            .unwrap_or_else(|_| generate_client_id())
    }

    pub fn list_devices(&self) -> Vec<TrustedDevice> {
        self.inner
            .lock()
            .map(|g| g.devices.clone())
            .unwrap_or_default()
    }

    /// Resolves the strongest known certificate pin for `(host, port)`:
    /// upgrades a 16-char mDNS prefix to a previously pinned 64-char SHA-256 digest when matching.
    pub fn resolve_strongest_pin(
        &self,
        expected_fingerprint: &str,
        host: &str,
        port: u16,
    ) -> String {
        let clean_host = host.trim();
        let candidate = fingerprint::normalize(expected_fingerprint);
        let Ok(guard) = self.inner.lock() else {
            return candidate;
        };

        if !candidate.is_empty() {
            for dev in &guard.devices {
                let stored = fingerprint::normalize(&dev.cert_fingerprint);
                if stored.len() > candidate.len() && fingerprint::matches(&stored, &candidate) {
                    return stored;
                }
            }
            return candidate;
        }

        for dev in &guard.devices {
            if dev.host == clean_host && dev.port == port {
                let stored = fingerprint::normalize(&dev.cert_fingerprint);
                if stored.len() >= fingerprint::MIN_PREFIX_HEX_LENGTH {
                    return stored;
                }
            }
        }

        String::new()
    }

    /// Inserts or updates a trusted device with anti-downgrade fingerprint protection.
    pub fn upsert(&self, mut incoming: TrustedDevice) -> Result<TrustedDevice, String> {
        let norm_fp = fingerprint::normalize(&incoming.cert_fingerprint);
        if norm_fp.len() < fingerprint::MIN_PREFIX_HEX_LENGTH {
            return Err("无效的设备证书指纹，长度不足 16 位十六进制".into());
        }
        let clean_host = incoming.host.trim().to_string();
        if clean_host.is_empty() {
            return Err("设备主机地址不能为空".into());
        }

        let mut guard = self
            .inner
            .lock()
            .map_err(|_| "信任存储锁异常".to_string())?;

        let existing_idx = guard.devices.iter().position(|d| {
            let stored_fp = fingerprint::normalize(&d.cert_fingerprint);
            let same_cert = if stored_fp.len() == fingerprint::FULL_SHA256_HEX_LENGTH {
                fingerprint::matches(&stored_fp, &norm_fp)
            } else if norm_fp.len() == fingerprint::FULL_SHA256_HEX_LENGTH {
                fingerprint::matches(&norm_fp, &stored_fp)
            } else {
                stored_fp == norm_fp
            };
            same_cert || (d.host == clean_host && d.port == incoming.port)
        });

        let now_ms = now_unix_ms();
        if let Some(idx) = existing_idx {
            let prev = guard.devices.remove(idx);
            let prev_fp = fingerprint::normalize(&prev.cert_fingerprint);

            // Anti-downgrade: never replace a full 64-char SHA-256 digest with a shorter mDNS prefix
            // when both refer to the same certificate.
            let effective_fp = if prev_fp.len() == fingerprint::FULL_SHA256_HEX_LENGTH
                && norm_fp.len() < fingerprint::FULL_SHA256_HEX_LENGTH
                && fingerprint::matches(&prev_fp, &norm_fp)
            {
                prev_fp.clone()
            } else {
                norm_fp.clone()
            };

            let same_cert_lineage = prev_fp == effective_fp
                || (effective_fp.len() == fingerprint::FULL_SHA256_HEX_LENGTH
                    && fingerprint::matches(&effective_fp, &prev_fp));

            if incoming.auth_token.trim().is_empty() && same_cert_lineage {
                incoming.auth_token = prev.auth_token;
                incoming.permanent = incoming.permanent || prev.permanent;
            }
            if incoming.display_name.trim().is_empty() {
                incoming.display_name = prev.display_name;
            }
            if incoming.model.trim().is_empty() {
                incoming.model = prev.model;
            }
            incoming.cert_fingerprint = effective_fp;
        } else {
            incoming.cert_fingerprint = norm_fp;
        }

        incoming.host = clean_host;
        if incoming.display_name.trim().is_empty() {
            incoming.display_name = incoming.host.clone();
        }
        incoming.id = derive_device_id(&incoming.cert_fingerprint, &incoming.host, incoming.port);
        if incoming.last_connected_at_ms == 0 {
            incoming.last_connected_at_ms = now_ms;
        }

        guard.devices.insert(0, incoming.clone());
        if guard.devices.len() > MAX_TRUSTED_DEVICES {
            guard.devices.truncate(MAX_TRUSTED_DEVICES);
        }

        Self::persist_atomic(&self.path, &guard)?;
        Ok(incoming)
    }

    pub fn revoke(&self, id: &str) -> Result<(), String> {
        let target = id.trim();
        if target.is_empty() {
            return Ok(());
        }
        let mut guard = self
            .inner
            .lock()
            .map_err(|_| "信任存储锁异常".to_string())?;
        guard
            .devices
            .retain(|d| d.id != target && d.cert_fingerprint != target);
        Self::persist_atomic(&self.path, &guard)
    }

    fn load_or_init(path: &Path) -> TrustStoreDisk {
        if let Ok(raw) = fs::read_to_string(path) {
            if let Ok(mut parsed) = serde_json::from_str::<TrustStoreDisk>(&raw) {
                if parsed.client_id.trim().len() < 12 {
                    parsed.client_id = generate_client_id();
                }
                return parsed;
            }
        }
        let fresh = TrustStoreDisk {
            version: STORE_SCHEMA_VERSION,
            client_id: generate_client_id(),
            devices: Vec::new(),
        };
        let _ = Self::persist_atomic(path, &fresh);
        fresh
    }

    fn persist_atomic(path: &Path, data: &TrustStoreDisk) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("创建配置目录 {} 失败: {e}", parent.display()))?;
        }
        let serialized = serde_json::to_string_pretty(data)
            .map_err(|e| format!("序列化信任列表失败: {e}"))?;
        let tmp_path = path.with_extension("json.tmp");
        fs::write(&tmp_path, serialized.as_bytes())
            .map_err(|e| format!("写入临时信任文件失败: {e}"))?;

        if fs::rename(&tmp_path, path).is_err() {
            fs::copy(&tmp_path, path).map_err(|e| format!("替换信任存储文件失败: {e}"))?;
            let _ = fs::remove_file(&tmp_path);
        }
        Ok(())
    }
}

fn derive_device_id(norm_fp: &str, host: &str, port: u16) -> String {
    let prefix = &norm_fp[..norm_fp.len().min(16)];
    format!("dev-{prefix}-{host}-{port}")
}

fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn generate_client_id() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let pid = std::process::id();
    let seed = format!("opaldesk-win-{nanos}-{pid}-{:?}", std::thread::current().id());
    let digest = Sha256::digest(seed.as_bytes());
    let mut hex = String::with_capacity(24);
    for b in &digest[..12] {
        use std::fmt::Write;
        let _ = write!(&mut hex, "{b:02x}");
    }
    format!("win-{hex}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prevents_fingerprint_downgrade_and_persists() {
        let dir = std::env::temp_dir().join(format!("opaldesk-trust-test-{}", now_unix_ms()));
        let store_path = dir.join("trust-store.json");
        let store = TrustStore::new(store_path.clone());
        let full_fp = fingerprint::sha256_hex(b"xiaomi-14-pro-self-signed-cert");
        let short_prefix = &full_fp[..16];

        let saved = store
            .upsert(TrustedDevice {
                id: String::new(),
                display_name: "Xiaomi 14 Pro".into(),
                model: "23116PN5BC".into(),
                host: "192.168.31.88".into(),
                port: 19878,
                cert_fingerprint: full_fp.clone(),
                auth_token: "tok_secret_123".into(),
                permanent: true,
                last_connected_at_ms: 1000,
            })
            .expect("upsert full fp");
        assert_eq!(saved.cert_fingerprint, full_fp);

        // Subsequent mDNS update with 16-char prefix must NOT downgrade the 64-char pin or erase auth_token
        let updated = store
            .upsert(TrustedDevice {
                id: String::new(),
                display_name: String::new(),
                model: String::new(),
                host: "192.168.31.88".into(),
                port: 19878,
                cert_fingerprint: short_prefix.into(),
                auth_token: String::new(),
                permanent: false,
                last_connected_at_ms: 2000,
            })
            .expect("upsert prefix");
        assert_eq!(updated.cert_fingerprint, full_fp);
        assert_eq!(updated.auth_token, "tok_secret_123");
        assert_eq!(
            store.resolve_strongest_pin(short_prefix, "192.168.31.88", 19878),
            full_fp
        );

        // Reload from disk and verify persistence + revoke
        let reloaded = TrustStore::new(store_path);
        assert_eq!(reloaded.client_id(), store.client_id());
        assert_eq!(reloaded.list_devices().len(), 1);
        reloaded.revoke(&updated.id).expect("revoke");
        assert!(reloaded.list_devices().is_empty());
        let _ = fs::remove_dir_all(dir);
    }
}
