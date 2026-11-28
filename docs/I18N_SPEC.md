# OpalDesk Cross-Client Internationalization (i18n) Specification

## 1. Supported Languages & Default Resolution Policy (SSOT)

All 5 OpalDesk clients (`:helper`, `:receiver`, `macos/OpalDeskMac`, `site/cast`, `windows`) share one deterministic locale resolution policy:

- **Supported locales**:
  - `en` — English (canonical default / fallback for all non-Chinese system locales)
  - `zh-CN` (Android `values-zh`, macOS `zh-Hans.lproj`) — Simplified Chinese
- **Default preference**: `system` (follow OS / browser locale automatically).
- **Optional manual override**: `system` | `zh-CN` (`zh-Hans`) | `en` persisted in each client's existing preferences store.
- **Deterministic resolution rule**:
  1. If explicit preference is `zh`, `zh-CN`, or `zh-Hans` -> resolve to Simplified Chinese.
  2. If explicit preference is `en` or `en-*` -> resolve to English.
  3. Otherwise (`system` or unrecognized) -> inspect system language tag(s):
     - Any tag starting with `zh` (case-insensitive, e.g. `zh-CN`, `zh-Hans`, `zh-SG`, `zh-TW`, `zh-HK`, `zh_CN`) -> Simplified Chinese.
     - Any other locale -> English (`en`).

## 2. Clean Code Engineering Rules

- **Zero Heavy Third-Party Dependencies**:
  - `:helper` stays on `androidx.compose.foundation` + `OpalPrimitives.kt` (no Material3).
  - `:receiver` stays on pure Android Framework Views (API 21–37).
  - `macos/OpalDeskMac` uses native Swift 6 / SwiftPM bundle localization (`defaultLocalization: "en"`).
  - `site/cast` and `windows` share one zero-build ES Module i18n engine (`site/cast/js/i18n.js`).
- **Single Source of Truth (SSOT)**:
  - Never duplicate locale resolution heuristics across views; centralize in one pure, unit-tested helper per client.
  - Never leave hardcoded UI prose inside views/controllers once a module is localized.
- **Concise English Copy**:
  - English strings tend to be 30–60% longer than Chinese; keep pill buttons, HUD labels, taskbar labels, and tray menu items concise to avoid clipping.
- **Proper Nouns (Do Not Translate)**:
  - `OpalDesk`, `OpalCast`, `DeX`, `Wayland`, `WebCodecs`, `VirtualDisplay`, `ADB`, `Shizuku`, `mDNS`, `WebDAV`, `Finder`, `Spotlight`, `H.264`, `HEVC`, `AAC`, `OTP`, `APK`, `USB-C`, `HDMI`, `BLE`.

## 3. Unified Cross-Client Glossary

| Chinese (`zh-CN`) | English (`en`) |
| :--- | :--- |
| 跟随系统 | Follow System |
| 简体中文 | 简体中文 |
| English | English |
| 语言 | Language |
| 控制台 | Console |
| 进入 DeX / 桌面工作站 | Enter DeX / Desktop Workstation |
| 激活引擎 | Activate Engine |
| 无线 / USB 投屏 | Wireless / USB Cast |
| 后台随行 | Companion Mode |
| 独立小窗 / 独立应用窗 | Standalone Window |
| 手机文件管理 | Phone Files |
| 存储空间分析 | Storage Analyzer |
| 快捷拨号 | Quick Dialer |
| 物理息屏 | Host Screen Off |
| 无线共享 | Wireless Share |
| 通话浮窗 | Call HUD |
| 来电响铃 / 正在拨号 / 通话中 | Incoming Call / Dialing / In Call |
| 接听 / 拒接 / 挂断 / 暂隐 | Answer / Decline / Hang Up / Hide |
| 静音 / 已静音 / 扬声器 | Mute / Muted / Speaker |
| 剪贴板同步 | Clipboard Sync |
| 通知同步 | Notification Sync |
| 自动复制验证码 | Auto-Copy OTP |
| 媒体控制 | Media Controls |
| 蓝牙靠近发现 | BLE Nearby Discovery |
| 切换主题 | Switch Theme |
| 诊断日志 | Diagnostics Log |
| 问题反馈 | Report Issue |
| 退出 OpalDesk | Quit OpalDesk |
