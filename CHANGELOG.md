# Changelog

All notable changes to **OpalDesk** will be documented in this file.

> **Provenance**: OpalDesk (`v0.0.1+`) is an independent project forked and evolved from [duppjuzo/ExtBridge](https://github.com/duppjuzo/ExtBridge) (`v3.5.3`, MIT License). To avoid version-line pollution or signing conflicts with upstream ExtBridge, OpalDesk uses an independent package ID (`io.github.lengsukq.opaldesk`), independent runtime namespace, independent signing identity, and an independent version line starting at `v0.0.1`.

---

## [1.0.24] - 2026-11-28

### New Features & UX Enhancements
- **Built-in DeX Settings App & Dock Floating Orb Mode**: Added a full responsive DeX Settings window (single-pane phone / dual-pane tablet & PC) with instant search, Dock size/style/glass customization, Floating Orb mode with edge snapping, hidden-dock space reclamation, and auto-dismissing popovers.
- **Liquid-Glass Desktop Widgets & Widget Store**: Added customizable desktop widgets (Clock + Hitokoto, Weather, System Monitor, Calendar, Quick Tiles, Media, Infinite-Loop Hub Carousel) with backdrop blur and per-slot sync.
- **Global Persisted Window Header Button Swap (All Slots)**: Added a global `AppPrefs` setting (`Swap Window Header Buttons`) accessible in both DeX Settings → Windows & Multitasking and Console Settings, swapping the left 3 traffic-light buttons with the right 5 utility buttons (mirroring Close to the outer top-right corner) across all 5 slots and fullscreen control bars.
- **Host-Side Key/Mouse Bindings, Built-in FAQ & Wireless Streaming Turbo (P0–P3)**: Added customizable key/mouse binding editor, in-app FAQ troubleshooting center, open cast bitrate selector, force phone speaker playback, portrait 4-column desktop grid, fullscreen traffic-light controls, and form-factor profile icons.

### Unified Input, Power/Thermal & Stability Fixes
- **Unified DeX Local-First IME Architecture**: Standardized soft-keyboard and hardware-keyboard routing across local DeX, external monitors, wireless casting, and `OpalCast` / macOS / Windows receivers; eliminated `FLAG_NOT_FOCUSABLE` vs `NativeImeBridgeView.showIme()` focus race and `imeDisplayId <= 0` drops.
- **Android 16 `input_timeout_no_focus` ANR Crash Fix**: Enforced `isForegroundActivityWindow = true` for `DesktopActivity` and direct-extended `ExternalDesktopActivity` so foreground Activity windows never set `FLAG_NOT_FOCUSABLE`, eliminating 5-second `Input dispatching timed out (Application does not have a focused window)` crashes.
- **Power & Thermal Optimizations**: Released LTPO/VRR adaptive downclocking in Auto FPS mode (`preferredDisplayModeId = 0`, `FRAME_RATE_COMPATIBILITY_DEFAULT`), added 45-second idle `VirtualDisplay` auto-release for minimized windows with snapshot preservation, slowed daemon background task reconciliation from 2s to 10s, throttled `ACTION_HOVER_MOVE` IPC to 60Hz (16ms), and gated 5 continuous 120Hz `rememberInfiniteTransition` widget loops & high-frequency timers.
- **Cross-Device Reliability**: Fixed vivo backlight wake restore, excluded external touchscreens from wake-monitor false triggers, prevented local search-box key-up focus theft, and fixed SwiftPM lowercased `zh-hans.lproj` localization lookup on macOS.

---

## [0.0.1] - 2026-11-22

### Brand Independence & Coexistence Isolation
- Rebranded the entire multi-platform stack to **OpalDesk**:
  - Android package ID & namespace migrated to `io.github.lengsukq.opaldesk` (`versionCode = 1`, `versionName = "0.0.1"`).
  - Daemon entry point renamed to `io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon` (`opaldesk.jar`), with isolated Binder descriptor, ContentProvider authority, Intent actions, `SharedPreferences`, and `/data/local/tmp/opaldesk.*` runtime paths — enabling side-by-side coexistence with upstream `ExtBridge`.
  - macOS native Swift 6 client rebranded to `OpalDesk.app` (`io.github.lengsukq.opaldesk.mac`), using `OpalCast` wire protocol (`OPD1`), `_opaldesk._tcp` Bonjour discovery, `opaldesk_stream` USB abstract socket, and `~/Library/Application Support/OpalDesk/` trust storage.
- Redesigned cross-platform **OpalDesk** visual identity:
  - Generated new Liquid Glass / opalescent multi-pane desktop app icons across macOS (`AppIcon.icns`) and Android (`mipmap-{hdpi,xhdpi,xxhdpi,xxxhdpi}` + `mipmap-anydpi-v26` Adaptive Icon + monochrome notification vector `ic_stat_opaldesk.xml`).
- Rewrote `README.md`, `docs/USER_GUIDE.md`, `LICENSE`, and `THIRD_PARTY_NOTICES.md` with clear attribution to `duppjuzo/ExtBridge`.

### Core Platform Capabilities (Initial OpalDesk Baseline)
- **On-Device LocalDeX Mode**: Zero-copy `SurfaceView` standalone desktop directly on the Android display (`LocalDexActivity` + `LocalDexController`), supporting touch, stylus, mouse, keyboard, custom DPI, and app-icon shortcut launch.
- **Liquid Glass × Desktop Window Engine & Workbench Rail**: Rewritten Android desktop shell featuring real-time freeform window dragging/resizing, multi-slot tiling layouts, floating taskbar, quick settings, widget board, and left/top Workbench app rail with live `TaskSnapshot` previews.
- **Native macOS Client (`OpalDesk.app`)**: Pure Swift 6 / SwiftUI remote display canvas with VideoToolbox hardware decode, AudioToolbox AAC-LC playback, TLS 1.3 TOFU + 6-digit PIN pairing, USB & Wi-Fi transport, UHID/System input backends, bidirectional text/PNG clipboard sync, and file transfer.
- **Physical Type-C / HDMI Display & Enhanced Mirror Mode**: Full support for external physical monitors, 21:9 ultrawide EDID auto-fit, 4-way rotation, one-tap main-screen blackout, and customizable mirror info sidebar with AppWidgets.

### Reliability & UX Fixes (Ported from Upstream `v3.5.4`–`v3.5.5` & Extended to macOS)
- **3-Tier Wireless ADB Delivery & Toybox Compatibility**:
  - Upgraded wireless activation to **Tier 1 `adb push` → Tier 2 `adb shell cat >` stdin stream → Tier 3 multi-user/multi-split `pm path` fallback**.
  - Removed all Toybox-incompatible `unzip -tq` calls across Android `DaemonBootstrapScripts`, `start_opaldesk.sh`, and macOS `AdbDaemonBootstrapper.swift` (replaced with `PK` magic + SHA-256 + `--verify-helper`).
  - Added pure-JVM deterministic ZIP header normalization (clearing `d8` streaming `0x0008` bit) in `:bridge:dexBridgeJar` and `androidResources { noCompress += "jar" }` in `:helper`.
- **Shizuku / Sui 4th Activation Channel**:
  - Added `ShizukuBootstrap.kt` (`dev.rikka.shizuku:13.1.5`) alongside Wireless Debugging, Root, and PC ADB in a 4-tab `ActivationSheet`, with stdin jar staging, Sui root privilege drop, and optional auto-activation on launch.
- **Miuix 0.9.4 Overlay Input Cursor Fix**:
  - Added cursor-safe `OpalTextField` (backed by local `TextFieldValue` state and stale-echo deduplication) and replaced all 11 `TextField` instances across sheets, dialogs, search bars, and remote IME dock to eliminate the 1-frame cursor-jump-to-start bug.
