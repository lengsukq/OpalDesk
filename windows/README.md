# OpalDesk Windows Companion Client (`windows/`)

基于 **Tauri v2 (Rust + Edge WebView2)** 的轻量化 Windows 伴侣客户端：零重复维护地复用 `site/cast/` 的全套 **OpalCast v1.1** WebCodecs 硬件解码（H.264 / HEVC + 48kHz AAC-LC）、键鼠/10指触控/中文输入法桥、剪贴板/文件拖拽与远程麦克风/摄像头能力，同时在 Rust 原生层补齐浏览器沙箱缺失的四项桌面核心能力：

1. **局域网 mDNS 自动发现（`src/mdns.rs`）**：监听 `_opaldesk._tcp.local.` 服务，自动解析手机 IPv4、端口、型号与 SHA-256 证书指纹前缀（`fp`）。
2. **TLS 1.3 TOFU 证书固定与本地回环桥接（`src/fingerprint.rs`、`src/trust.rs`、`src/tls_bridge.rs`）**：无需在浏览器手动点击自签名证书警告，由 `rustls` 执行严格 SHA-256 叶子证书指纹固定（防降级持久化至 `%APPDATA%\io.github.lengsukq.opaldesk.desktop\trust-store.json`），并在 `127.0.0.1:<随机端口>` 提供零拷贝 WebSocket 桥接（同时覆盖 `:19878/ws` 投屏与 `:19879/activate` 免线激活）。
3. **静默 ADB 探测、守护进程一键引导与 USB 免证书直连（`src/adb.rs`）**：自动检索系统 / Android SDK / 自定义路径下的 `adb.exe`（全程 `CREATE_NO_WINDOW` 无黑框闪烁），复刻 macOS 端的 APK 签名校验引导脚本（`--verify-helper`）与 `adb forward tcp:0 localabstract:opaldesk_stream`，退出时自动清理转发端口。
4. **原生系统托盘与独立应用窗口（`src/lib.rs`）**：支持将单个手机应用弹出为独立的原生 Windows 窗口（`SESSION_ROLE_STANDALONE_APP`），共享 WebView2 硬件解码参数与 HTML5 文件拖拽支持。

## 本地开发与打包（Windows 10 / 11 x64）

### 环境依赖
- [Node.js 20+](https://nodejs.org/)
- [Rust stable (`x86_64-pc-windows-msvc`)](https://rustup.rs/) + Visual Studio C++ Build Tools
- Windows 10/11 自带 Microsoft Edge WebView2 Runtime（安装包在缺失机器上会自动按需引导安装）
- （可选，用于 USB 有线一键激活/直连）Android SDK Platform-Tools (`adb.exe`)

### 常用命令
```powershell
cd windows

# 1. 同步 site/cast 前端静态资源与 helper/build.gradle.kts 版本号，并运行 Node 单元测试
npm test

# 2. 运行 Rust 核心单元测试（指纹核验、防降级 TrustStore、ADB 设备解析与引导脚本校验）
cargo test --manifest-path src-tauri/Cargo.toml

# 3. 启动桌面端热重载开发模式
npx @tauri-apps/cli@^2.5.0 dev

# 4. 构建正式版 NSIS (.exe) 与 MSI (.msi) 安装包
npx @tauri-apps/cli@^2.5.0 build
```
