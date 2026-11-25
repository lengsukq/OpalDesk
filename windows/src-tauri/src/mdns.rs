use crate::fingerprint;
use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::Serialize;
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex};

pub const OPALDESK_MDNS_SERVICE: &str = "_opaldesk._tcp.local.";

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredMdnsDevice {
    pub id: String,
    pub service_name: String,
    pub display_name: String,
    pub model: String,
    pub host: String,
    pub port: u16,
    pub fingerprint_prefix: String,
    pub short_fingerprint: String,
    pub protocol_version: String,
}

pub struct MdnsScanner {
    daemon: Option<ServiceDaemon>,
    devices: Arc<Mutex<HashMap<String, DiscoveredMdnsDevice>>>,
}

impl MdnsScanner {
    pub fn start() -> Self {
        let devices = Arc::new(Mutex::new(HashMap::new()));
        let daemon = ServiceDaemon::new().ok();

        if let Some(ref mdns) = daemon {
            let _ = mdns.set_multicast_loop_v4(false);
            let _ = mdns.set_multicast_loop_v6(false);
            Self::spawn_browse_worker(mdns, &devices);
        }

        Self { daemon, devices }
    }

    fn spawn_browse_worker(
        mdns: &ServiceDaemon,
        devices: &Arc<Mutex<HashMap<String, DiscoveredMdnsDevice>>>,
    ) {
        if let Ok(receiver) = mdns.browse(OPALDESK_MDNS_SERVICE) {
            let devices_ref = devices.clone();
            let _ = std::thread::Builder::new()
                .name("opaldesk-mdns-browser".into())
                .spawn(move || {
                    while let Ok(event) = receiver.recv() {
                        match event {
                            ServiceEvent::ServiceResolved(resolved) => {
                                let mut v4_list: Vec<Ipv4Addr> = resolved
                                    .get_addresses_v4()
                                    .into_iter()
                                    .filter(|ip| !ip.is_loopback() && !ip.is_unspecified())
                                    .collect();
                                v4_list.sort();

                                let Some(primary_ip) = v4_list.first() else {
                                    continue;
                                };

                                let fullname = resolved.get_fullname().to_string();
                                let service_name = fullname
                                    .strip_suffix("._opaldesk._tcp.local.")
                                    .unwrap_or(&fullname)
                                    .to_string();
                                let raw_name =
                                    resolved.get_property_val_str("name").unwrap_or("").trim();
                                let display_name = if raw_name.is_empty() {
                                    service_name.clone()
                                } else {
                                    raw_name.to_string()
                                };
                                let fp_norm = fingerprint::normalize(
                                    resolved.get_property_val_str("fp").unwrap_or(""),
                                );
                                let item = DiscoveredMdnsDevice {
                                    id: fullname.clone(),
                                    service_name,
                                    display_name,
                                    model: resolved
                                        .get_property_val_str("model")
                                        .unwrap_or("")
                                        .trim()
                                        .to_string(),
                                    host: primary_ip.to_string(),
                                    port: resolved.get_port(),
                                    short_fingerprint: fingerprint::format_short(&fp_norm),
                                    fingerprint_prefix: fp_norm,
                                    protocol_version: resolved
                                        .get_property_val_str("ver")
                                        .unwrap_or("")
                                        .trim()
                                        .to_string(),
                                };
                                if let Ok(mut map) = devices_ref.lock() {
                                    map.insert(fullname, item);
                                }
                            }
                            ServiceEvent::ServiceRemoved(_ty, fullname) => {
                                if let Ok(mut map) = devices_ref.lock() {
                                    map.remove(&fullname);
                                }
                            }
                            _ => {}
                        }
                    }
                });
        }
    }

    pub fn list_devices(&self) -> Vec<DiscoveredMdnsDevice> {
        let mut out: Vec<DiscoveredMdnsDevice> = self
            .devices
            .lock()
            .map(|m| m.values().cloned().collect())
            .unwrap_or_default();
        out.sort_by(|a, b| a.display_name.cmp(&b.display_name));
        out
    }

    pub fn refresh(&self) {
        if let Some(ref mdns) = self.daemon {
            let _ = mdns.stop_browse(OPALDESK_MDNS_SERVICE);
            Self::spawn_browse_worker(mdns, &self.devices);
        }
    }

    pub fn shutdown(&self) {
        if let Some(ref mdns) = self.daemon {
            let _ = mdns.shutdown();
        }
    }
}
