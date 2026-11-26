fn main() {
    for key in [
        "OPALDESK_SENTRY_DSN",
        "OPALDESK_SENTRY_TUNNEL_URL",
        "OPALDESK_FEEDBACK_STATUS_URL",
    ] {
        println!("cargo:rerun-if-env-changed={key}");
    }
    tauri_build::build()
}
