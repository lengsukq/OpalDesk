use serde_json::Value;
use std::sync::Mutex;
use tauri::{
    AppHandle, Emitter, Manager, PhysicalPosition, WebviewUrl, WebviewWindow, WebviewWindowBuilder,
};

pub const CALL_HUD_LABEL: &str = "call-hud";
/// Visible iOS pill banner is ~384×84 (expands cleanly up to ~134 when SMS chips open)
/// inside a 432×184 transparent canvas (24px margin on all sides) with native DWM window
/// shadow disabled (`shadow(false)`) so the CSS rounded pill shadow feathers cleanly with
/// zero square corners or rectangular clipping.
const CALL_HUD_WIDTH: f64 = 432.0;
const CALL_HUD_HEIGHT: f64 = 184.0;
const CALL_HUD_SHADOW_MARGIN_X: f64 = 24.0;
const CALL_HUD_SHADOW_MARGIN_TOP: f64 = 18.0;

#[cfg(target_os = "windows")]
const CALL_HUD_BROWSER_ARGS: &str =
    "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required";

#[derive(Default)]
pub struct CallHudManager {
    latest_state: Mutex<Option<Value>>,
}

impl CallHudManager {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn current_state(&self) -> Option<Value> {
        self.latest_state
            .lock()
            .ok()
            .and_then(|guard| guard.clone())
    }

    pub fn update_state(&self, app: &AppHandle, state: Value) -> Result<bool, String> {
        if let Ok(mut guard) = self.latest_state.lock() {
            *guard = Some(state.clone());
        }
        let win = ensure_call_hud_window(app)?;
        position_top_right(&win);
        let _ = win.show();
        let _ = win.emit("opaldesk:call-state", &state);
        Ok(true)
    }

    pub fn hide(&self, app: &AppHandle) -> bool {
        if let Ok(mut guard) = self.latest_state.lock() {
            *guard = None;
        }
        if let Some(win) = app.get_webview_window(CALL_HUD_LABEL) {
            let _ = win.hide();
            return true;
        }
        false
    }

    pub fn dispatch_action(&self, app: &AppHandle, action: Value) -> Result<bool, String> {
        app.emit_to("main", "opaldesk:call-hud-action", action)
            .map_err(|e| format!("无法转发通话控制指令: {e}"))?;
        Ok(true)
    }
}

fn ensure_call_hud_window(app: &AppHandle) -> Result<WebviewWindow, String> {
    if let Some(existing) = app.get_webview_window(CALL_HUD_LABEL) {
        return Ok(existing);
    }

    let builder = WebviewWindowBuilder::new(
        app,
        CALL_HUD_LABEL,
        WebviewUrl::App("call-hud.html".into()),
    )
    .title("OpalDesk Call")
    .inner_size(CALL_HUD_WIDTH, CALL_HUD_HEIGHT)
    .decorations(false)
    .shadow(false)
    .transparent(true)
    .always_on_top(true)
    .skip_taskbar(true)
    .resizable(false)
    .focused(false)
    .visible(false)
    .disable_drag_drop_handler();

    #[cfg(target_os = "windows")]
    let builder = builder.additional_browser_args(CALL_HUD_BROWSER_ARGS);

    let win = builder
        .build()
        .map_err(|e| format!("无法创建来电悬浮控制窗: {e}"))?;
    position_top_right(&win);
    Ok(win)
}

fn position_top_right(win: &WebviewWindow) {
    if let Ok(Some(monitor)) = win.primary_monitor() {
        let scale = monitor.scale_factor();
        let mon_size = monitor.size();
        let mon_pos = monitor.position();
        let hud_w = (CALL_HUD_WIDTH * scale).round() as i32;
        let visible_margin_x = ((16.0 - CALL_HUD_SHADOW_MARGIN_X) * scale).round() as i32;
        let visible_margin_y = ((18.0 - CALL_HUD_SHADOW_MARGIN_TOP) * scale).round() as i32;
        let x = mon_pos.x + (mon_size.width as i32) - hud_w - visible_margin_x;
        let y = mon_pos.y + visible_margin_y.max(0);
        let _ = win.set_position(PhysicalPosition::new(x.max(mon_pos.x), y));
    }
}
