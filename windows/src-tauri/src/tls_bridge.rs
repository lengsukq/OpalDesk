use crate::fingerprint;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, Error as RustlsError, SignatureScheme};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio_rustls::TlsConnector;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbedCertInfo {
    pub host: String,
    pub port: u16,
    pub fingerprint: String,
    pub short_fingerprint: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProbedCastHealth {
    pub host: String,
    pub port: u16,
    pub device_name: String,
    pub fingerprint: String,
    pub short_fingerprint: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalWsTunnelInfo {
    pub local_port: u16,
    pub local_ws_url: String,
    pub verified_fingerprint: String,
    pub short_fingerprint: String,
}

fn ring_provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

fn format_host_port(host: &str, port: u16) -> String {
    let trimmed = host.trim();
    if trimmed.contains(':') && !trimmed.starts_with('[') {
        format!("[{trimmed}]:{port}")
    } else {
        format!("{trimmed}:{port}")
    }
}

fn parse_server_name(host: &str) -> Result<ServerName<'static>, String> {
    let clean = host.trim().trim_matches(|c| c == '[' || c == ']').to_string();
    ServerName::try_from(clean).map_err(|e| format!("无效的目标主机地址 ({host}): {e}"))
}

#[derive(Debug)]
struct ProbeCertVerifier {
    captured_hex: Arc<Mutex<Option<String>>>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for ProbeCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, RustlsError> {
        let digest = fingerprint::sha256_hex(end_entity.as_ref());
        if let Ok(mut slot) = self.captured_hex.lock() {
            *slot = Some(digest);
        }
        Err(RustlsError::General("opaldesk_probe_captured".into()))
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[derive(Debug)]
struct PinnedCertVerifier {
    expected_pin: String,
    verified_full_hex: Arc<Mutex<Option<String>>>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, RustlsError> {
        let actual_hex = fingerprint::sha256_hex(end_entity.as_ref());
        if !fingerprint::matches(&actual_hex, &self.expected_pin) {
            return Err(RustlsError::General(format!(
                "TLS 证书指纹不匹配（预期 {}，实际 {}）",
                fingerprint::format_short(&self.expected_pin),
                fingerprint::format_short(&actual_hex)
            )));
        }
        if let Ok(mut slot) = self.verified_full_hex.lock() {
            *slot = Some(actual_hex);
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

fn build_client_config(verifier: Arc<dyn ServerCertVerifier>) -> Result<Arc<ClientConfig>, String> {
    let provider = ring_provider();
    let cfg = ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| format!("初始化 TLS 配置失败: {e}"))?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    Ok(Arc::new(cfg))
}

pub async fn probe_certificate_fingerprint(
    host: &str,
    port: u16,
    timeout_ms: u64,
) -> Result<ProbedCertInfo, String> {
    let addr = format_host_port(host, port);
    let server_name = parse_server_name(host)?;
    let captured = Arc::new(Mutex::new(None));
    let verifier = Arc::new(ProbeCertVerifier {
        captured_hex: captured.clone(),
        provider: ring_provider(),
    });
    let tls_cfg = build_client_config(verifier)?;
    let timeout_dur = Duration::from_millis(timeout_ms.clamp(800, 15_000));

    let tcp = tokio::time::timeout(timeout_dur, TcpStream::connect(&addr))
        .await
        .map_err(|_| format!("连接设备 {addr} 超时，请确认手机与电脑处于同一局域网且已开启共享"))?
        .map_err(|e| format!("无法连接设备 {addr}: {e}"))?;
    let _ = tcp.set_nodelay(true);

    let connector = TlsConnector::from(tls_cfg);
    let _ = tokio::time::timeout(timeout_dur, connector.connect(server_name, tcp)).await;

    let fp = captured
        .lock()
        .ok()
        .and_then(|mut g| g.take())
        .ok_or_else(|| format!("未能从 {addr} 读取有效的 TLS 证书，请检查端口是否正确"))?;

    Ok(ProbedCertInfo {
        host: host.trim().to_string(),
        port,
        short_fingerprint: fingerprint::format_short(&fp),
        fingerprint: fp,
    })
}

/// Captures the server leaf certificate SHA-256 fingerprint for read-only `/health` discovery
/// probes ONLY (never used for actual OpalCast stream tunnels, which strictly use `PinnedCertVerifier`).
#[derive(Debug)]
struct HealthProbeCertVerifier {
    captured_hex: Arc<Mutex<Option<String>>>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for HealthProbeCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, RustlsError> {
        let digest = fingerprint::sha256_hex(end_entity.as_ref());
        if let Ok(mut slot) = self.captured_hex.lock() {
            *slot = Some(digest);
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

pub fn parse_cast_health_http_response(raw_http: &str) -> Option<String> {
    let (head, body) = raw_http
        .split_once("\r\n\r\n")
        .or_else(|| raw_http.split_once("\n\n"))?;
    let status_line = head.lines().next()?;
    if !status_line.contains(" 200") {
        return None;
    }
    let val: serde_json::Value = serde_json::from_str(body.trim()).ok()?;
    if val.get("ok").and_then(|v| v.as_bool()) != Some(true) {
        return None;
    }
    if val.get("role").and_then(|v| v.as_str()) != Some("cast") {
        return None;
    }
    let name = val
        .get("deviceName")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("Android 设备");
    Some(name.to_string())
}

/// Probes an OpalDesk TLS endpoint's `GET /health` metadata and captures its real leaf X.509
/// SHA-256 fingerprint in a single short-timeout roundtrip.
pub async fn probe_cast_health(
    host: &str,
    port: u16,
    timeout_ms: u64,
) -> Result<ProbedCastHealth, String> {
    let clean_host = host.trim();
    let addr = format_host_port(clean_host, port);
    let server_name = parse_server_name(clean_host)?;
    let captured = Arc::new(Mutex::new(None));
    let verifier = Arc::new(HealthProbeCertVerifier {
        captured_hex: captured.clone(),
        provider: ring_provider(),
    });
    let tls_cfg = build_client_config(verifier)?;
    let timeout_dur = Duration::from_millis(timeout_ms.clamp(400, 5_000));

    let tcp = tokio::time::timeout(timeout_dur, TcpStream::connect(&addr))
        .await
        .map_err(|_| format!("探测 {addr} 超时"))?
        .map_err(|e| format!("无法连接 {addr}: {e}"))?;
    let _ = tcp.set_nodelay(true);

    let mut tls = tokio::time::timeout(
        timeout_dur,
        TlsConnector::from(tls_cfg).connect(server_name, tcp),
    )
    .await
    .map_err(|_| format!("TLS 探测 {addr} 超时"))?
    .map_err(|e| format!("TLS 探测 {addr} 失败: {e}"))?;

    let req = format!(
        "GET /health HTTP/1.1\r\nHost: {addr}\r\nConnection: close\r\n\r\n"
    );
    tokio::time::timeout(timeout_dur, tls.write_all(req.as_bytes()))
        .await
        .map_err(|_| "写入 /health 请求超时".to_string())?
        .map_err(|e| format!("写入 /health 请求失败: {e}"))?;
    let _ = tls.flush().await;

    let mut buf = Vec::with_capacity(1024);
    let mut chunk = [0u8; 1024];
    let read_result = tokio::time::timeout(timeout_dur, async {
        while buf.len() < 4096 {
            let n = tls.read(&mut chunk).await?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&chunk[..n]);
        }
        Ok::<(), std::io::Error>(())
    })
    .await;
    let _ = read_result;

    let raw_http = String::from_utf8_lossy(&buf);
    let device_name = parse_cast_health_http_response(&raw_http)
        .ok_or_else(|| format!("{addr} 未返回有效的 OpalDesk 投屏健康响应"))?;

    let fp = captured
        .lock()
        .ok()
        .and_then(|mut g| g.take())
        .ok_or_else(|| format!("未能读取 {addr} 的叶证书指纹"))?;

    Ok(ProbedCastHealth {
        host: clean_host.to_string(),
        port,
        device_name,
        short_fingerprint: fingerprint::format_short(&fp),
        fingerprint: fp,
    })
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct TunnelKey {
    host: String,
    port: u16,
    path: String,
    expected_pin: String,
}

struct ActiveTunnel {
    local_port: u16,
    verified_fingerprint: String,
    shutdown_tx: watch::Sender<bool>,
}

pub struct TlsBridgeRegistry {
    tunnels: Mutex<HashMap<TunnelKey, ActiveTunnel>>,
}

impl TlsBridgeRegistry {
    pub fn new() -> Self {
        Self {
            tunnels: Mutex::new(HashMap::new()),
        }
    }

    pub async fn ensure_tunnel(
        &self,
        host: &str,
        port: u16,
        path: &str,
        expected_pin: &str,
    ) -> Result<LocalWsTunnelInfo, String> {
        let norm_pin = fingerprint::normalize(expected_pin);
        if norm_pin.len() < fingerprint::MIN_PREFIX_HEX_LENGTH {
            return Err("缺少有效的设备证书指纹，请先核验设备指纹".into());
        }
        let clean_host = host.trim().to_string();
        let clean_path = if path.trim().starts_with('/') {
            path.trim().to_string()
        } else {
            format!("/{}", path.trim())
        };

        let probed = probe_certificate_fingerprint(&clean_host, port, 5_000).await?;
        if !fingerprint::matches(&probed.fingerprint, &norm_pin) {
            return Err(format!(
                "TLS 证书指纹不匹配（预期 {}，实际 {}），已阻断连接",
                fingerprint::format_short(&norm_pin),
                probed.short_fingerprint
            ));
        }
        let full_verified_fp = probed.fingerprint;

        let key = TunnelKey {
            host: clean_host.clone(),
            port,
            path: clean_path.clone(),
            expected_pin: full_verified_fp.clone(),
        };

        if let Ok(guard) = self.tunnels.lock() {
            if let Some(existing) = guard.get(&key) {
                return Ok(LocalWsTunnelInfo {
                    local_port: existing.local_port,
                    local_ws_url: format!("ws://127.0.0.1:{}{}", existing.local_port, clean_path),
                    short_fingerprint: fingerprint::format_short(&existing.verified_fingerprint),
                    verified_fingerprint: existing.verified_fingerprint.clone(),
                });
            }
        }

        let listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .map_err(|e| format!("绑定本地回环桥接端口失败: {e}"))?;
        let local_port = listener
            .local_addr()
            .map_err(|e| format!("读取本地桥接端口失败: {e}"))?
            .port();

        let (shutdown_tx, mut shutdown_rx) = watch::channel(false);
        let target_host = clean_host.clone();
        let pin_for_task = full_verified_fp.clone();

        tokio::spawn(async move {
            loop {
                tokio::select! {
                    changed = shutdown_rx.changed() => {
                        if changed.is_err() || *shutdown_rx.borrow() {
                            break;
                        }
                    }
                    accepted = listener.accept() => {
                        let Ok((local_tcp, _peer)) = accepted else {
                            continue;
                        };
                        let _ = local_tcp.set_nodelay(true);
                        let h = target_host.clone();
                        let pin = pin_for_task.clone();
                        tokio::spawn(async move {
                            let _ = relay_single_ws_connection(local_tcp, &h, port, &pin).await;
                        });
                    }
                }
            }
        });

        if let Ok(mut guard) = self.tunnels.lock() {
            guard.insert(
                key,
                ActiveTunnel {
                    local_port,
                    verified_fingerprint: full_verified_fp.clone(),
                    shutdown_tx,
                },
            );
        }

        Ok(LocalWsTunnelInfo {
            local_port,
            local_ws_url: format!("ws://127.0.0.1:{}{}", local_port, clean_path),
            short_fingerprint: fingerprint::format_short(&full_verified_fp),
            verified_fingerprint: full_verified_fp,
        })
    }

    pub fn shutdown_all(&self) {
        if let Ok(mut guard) = self.tunnels.lock() {
            for (_, tunnel) in guard.drain() {
                let _ = tunnel.shutdown_tx.send(true);
            }
        }
    }
}

async fn relay_single_ws_connection(
    mut local_tcp: TcpStream,
    host: &str,
    port: u16,
    expected_pin: &str,
) -> Result<(), String> {
    let _ = local_tcp.set_nodelay(true);
    let addr = format_host_port(host, port);
    let server_name = parse_server_name(host)?;
    let verified_slot = Arc::new(Mutex::new(None));
    let verifier = Arc::new(PinnedCertVerifier {
        expected_pin: expected_pin.to_string(),
        verified_full_hex: verified_slot,
        provider: ring_provider(),
    });
    let tls_cfg = build_client_config(verifier)?;

    let remote_tcp = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(&addr))
        .await
        .map_err(|_| format!("连接远端 {addr} 超时"))?
        .map_err(|e| format!("连接远端 {addr} 失败: {e}"))?;
    let _ = remote_tcp.set_nodelay(true);

    let mut tls_stream = tokio::time::timeout(
        CONNECT_TIMEOUT,
        TlsConnector::from(tls_cfg).connect(server_name, remote_tcp),
    )
    .await
    .map_err(|_| "远端 TLS 握手超时".to_string())?
    .map_err(|e| format!("远端 TLS 握手失败: {e}"))?;

    // Zero-parse byte-stream splice: Android WebSocketHttpHandler directly serves WebView2's
    // HTTP/1.1 Upgrade and RFC 6455 frames inside the pinned TLS tunnel, eliminating double
    // WebSocket deframing/reframing, per-frame Vec<u8> allocation, and extra flushes.
    let _ = tokio::io::copy_bidirectional(&mut local_tcp, &mut tls_stream).await;
    let _ = tls_stream.shutdown().await;
    let _ = local_tcp.shutdown().await;
    Ok(())
}
