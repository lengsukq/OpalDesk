use tauri::WebviewWindow;

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;
#[cfg(target_os = "windows")]
use std::process::Command;

#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Escapes XML special characters before embedding notification text into a WinRT Toast XML payload.
pub fn xml_escape(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len() + 16);
    for ch in raw.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            c if c.is_control() && c != '\n' && c != '\t' => {}
            c => out.push(c),
        }
    }
    out
}

/// Applies native Windows 11 Mica backdrop to the main window (gracefully returns false on Win10 / non-Windows).
pub fn apply_window_mica_effect(win: &WebviewWindow) -> bool {
    #[cfg(target_os = "windows")]
    {
        use tauri::window::{Effect, EffectsBuilder};
        let effects = EffectsBuilder::new().effect(Effect::Mica).build();
        win.set_effects(Some(effects)).is_ok()
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = win;
        false
    }
}

/// Sends a native Windows 10/11 Toast notification asynchronously via WinRT ToastNotificationManager
/// using environment variables (zero shell-injection risk, zero extra crates).
pub fn send_native_notification(title: &str, body: &str, app_label: &str, _tag: &str) -> Result<bool, String> {
    let clean_title = title.trim();
    let clean_body = body.trim();
    if clean_title.is_empty() && clean_body.is_empty() {
        return Ok(false);
    }

    #[cfg(target_os = "windows")]
    {
        let header = if app_label.trim().is_empty() {
            clean_title.to_string()
        } else if clean_title.is_empty() {
            app_label.trim().to_string()
        } else {
            format!("{} · {}", app_label.trim(), clean_title)
        };
        let esc_title = xml_escape(&header.chars().take(120).collect::<String>());
        let esc_body = xml_escape(&clean_body.chars().take(240).collect::<String>());
        let xml = format!(
            "<toast><visual><binding template=\"ToastText02\"><text id=\"1\">{esc_title}</text><text id=\"2\">{esc_body}</text></binding></visual></toast>"
        );

        let script = r#"
try {
  $null = [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime]
  $null = [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType=WindowsRuntime]
  $doc = New-Object Windows.Data.Xml.Dom.XmlDocument
  $doc.LoadXml($env:OPAL_TOAST_XML)
  $toast = [Windows.UI.Notifications.ToastNotification]::new($doc)
  $notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('OpalDesk')
  $notifier.Show($toast)
} catch {}
"#;
        let mut cmd = Command::new("powershell.exe");
        cmd.args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script,
        ])
        .env("OPAL_TOAST_XML", xml)
        .creation_flags(CREATE_NO_WINDOW);
        cmd.spawn()
            .map_err(|e| format!("无法发送 Windows 通知: {e}"))?;
        return Ok(true);
    }

    #[cfg(not(target_os = "windows"))]
    {
        let _ = app_label;
        Ok(false)
    }
}

pub fn format_tray_tooltip(
    connected: bool,
    device_name: &str,
    unread_count: usize,
    call_active: bool,
) -> String {
    if !connected {
        return "OpalDesk · 待机".to_string();
    }
    let peer = if device_name.trim().is_empty() {
        "Android"
    } else {
        device_name.trim()
    };
    let mut parts = vec![format!("OpalDesk · 已连 {peer}")];
    if call_active {
        parts.push("通话中".to_string());
    }
    if unread_count > 0 {
        parts.push(format!("{unread_count} 条未读通知"));
    }
    parts.join(" | ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escapes_toast_xml_safely_and_formats_tray_tooltip() {
        assert_eq!(xml_escape("A <B> & \"C\" 'D'"), "A &lt;B&gt; &amp; &quot;C&quot; &apos;D&apos;");
        assert_eq!(format_tray_tooltip(false, "", 0, false), "OpalDesk · 待机");
        assert_eq!(
            format_tray_tooltip(true, "Xiaomi 15", 3, true),
            "OpalDesk · 已连 Xiaomi 15 | 通话中 | 3 条未读通知"
        );
    }
}
