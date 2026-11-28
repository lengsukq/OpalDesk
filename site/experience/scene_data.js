/* ==========================================================================
   OpalDesk 3D Stage Static Data, Bilingual Dictionary & Three.js Loader
   ========================================================================== */

export const THEME_KEY = 'opaldesk-showcase-theme-v1';
export const LANG_KEY = 'opaldesk-showcase-lang-v1';

const ESM_CANDIDATES = [
  'https://registry.npmmirror.com/three/0.170.0/files/build/three.module.js',
  'https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js'
];
const UMD_CANDIDATES = [
  'https://registry.npmmirror.com/three/0.158.0/files/build/three.min.js',
  'https://cdn.bootcdn.net/ajax/libs/three.js/0.158.0/three.min.js',
  'https://cdnjs.cloudflare.com/ajax/libs/three.js/0.158.0/three.min.js'
];

/* Stage 02: VirtualDisplayPool Live / Hibernated Window Cards (Full 16:10 Unstretched Frames) */
export const POOL_SLOTS = [
  { id: 'win-1', zh: '双窗浅色 · LIVE', en: 'Light Desk · LIVE', titleZh: '晴空浅色工作站', titleEn: 'Light Workstation', sub: '3040×1904 · 120Hz', live: true, accent: '#0071E3', shot: 'dexLight' },
  { id: 'win-2', zh: '双窗深色 · LIVE', en: 'Dark Desk · LIVE', titleZh: '曜石深色工作站', titleEn: 'Dark Workstation', sub: '3040×1904 · 120Hz', live: true, accent: '#22B8CF', shot: 'dexDark' },
  { id: 'win-3', zh: '控制中心 · LIVE', en: 'Control · LIVE', titleZh: '实时桌面控制中心', titleEn: 'Control Center', sub: '120Hz · DPI Hot', live: true, accent: '#5E7CE2', shot: 'dexCtrlDark' },
  { id: 'win-4', zh: '无线投屏 · LIVE', en: 'Wire Cast · LIVE', titleZh: '手机→平板 120Hz 投屏', titleEn: 'Wireless Desktop Cast', sub: '2560×1600 · TLS 1.3', live: true, accent: '#2997FF', shot: 'castDesktop' },
  { id: 'win-5', zh: '主控台 · 快照', en: 'Console · Snap', titleZh: '工作站主控台', titleEn: 'Console', sub: 'TaskSnapshot · 0 VD', live: false, accent: '#30D158', shot: 'console' },
  { id: 'win-6', zh: '设置中心 · 快照', en: 'Settings · Snap', titleZh: '输入与配额设置', titleEn: 'Settings', sub: 'TaskSnapshot · 0 VD', live: false, accent: '#34D399', shot: 'settings' }
];

export const TOPO_PRESETS = {
  cast: {
    zhQuote: '“平板全屏 2560×1600 @ 120Hz 接收手机桌面，手机端同步化为液态毛玻璃触控板并支持一键物理息屏省电。”',
    enQuote: '"Tablet receives a full 2560×1600 @ 120Hz desktop from Phone while Phone turns into a frosted precision trackpad with one-tap screen-off."',
    zhMeta: 'Wi-Fi TLS 1.3 · TOFU 指纹 · OpalPeerCastMutex 单向防环锁',
    enMeta: 'Wi-Fi TLS 1.3 · TOFU Fingerprint · OpalPeerCastMutex Anti-Loop Lock'
  },
  float: {
    zhQuote: '“无需切换桌面，手机端应用直接以 macOS 液态毛玻璃小窗飞跃悬浮于平板之上，1:1 宿主 DPI 超采样清晰锐利。”',
    enQuote: '"Any phone app lifts into a macOS Liquid Glass floating window hovering directly over your tablet at razor-sharp 1:1 host DPI."',
    zhMeta: 'PeerFloatingAppService · TYPE_IME_REQUEST 跨端输入法自动转发',
    enMeta: 'PeerFloatingAppService · TYPE_IME_REQUEST Remote IME Forwarding'
  },
  mac: {
    zhQuote: '“接入 USB-C / HDMI 便携屏即刻满屏 2K/4K 100Hz+；也可通过原生 Swift 6 Mac 伴侣端或免安装 Web 浏览器（WebCodecs + WSS）低延迟接收桌面与声音。”',
    enQuote: '"Plug into USB-C / HDMI for instant 2K/4K 100Hz+, stream to native Swift 6 OpalDeskMac, or open the zero-install Web Cast Receiver (WebCodecs + WSS) in any Chrome/Edge browser."',
    zhMeta: 'ExternalMonitor · OpalDeskMac Swift 6 · GitHub Pages WebCodecs 接收端',
    enMeta: 'ExternalMonitor · OpalDeskMac Swift 6 · GitHub Pages WebCodecs Receiver'
  }
};

export const WAFER_SPECS = [
  {
    id: 'wafer-mac',
    y: 1.56,
    z: 0.30,
    accent: '#D99B43',
    zh: 'LAYER 04 · OpalDeskMac (Swift 6) / Web 浏览器端 & 2K/4K 外接屏',
    en: 'LAYER 04 · OpalDeskMac (Swift 6) / Web Receiver & 2K/4K Monitor',
    tag: '04 · OUTPUT',
    title: 'Mac · Web 接收端 · 2K/4K 外接屏',
    sub: 'Swift 6 原生端 · GitHub Pages WebCodecs + WSS · 双向剪贴板',
    pill: 'HEVC · WSS'
  },
  {
    id: 'wafer-helper',
    y: 0.52,
    z: 0.10,
    accent: '#2997FF',
    zh: 'LAYER 03 · :helper 客户端 120fps 单 RenderNode GPU 合成器',
    en: 'LAYER 03 · :helper 120fps Single-RenderNode GPU Compositor',
    tag: '03 · CLIENT',
    title: 'OpalCompositorWorkspace',
    sub: '120fps 单 RenderNode 皮肉合一 · 拖拽 0 IPC · GitHub OTA',
    pill: '120fps GPU'
  },
  {
    id: 'wafer-pool',
    y: -0.52,
    z: -0.10,
    accent: '#30D158',
    zh: 'LAYER 02 · VirtualDisplayPool LRU 休眠池 + InputManager / uhid',
    en: 'LAYER 02 · VirtualDisplayPool LRU + InputManager & /dev/uhid',
    tag: '02 · ENGINE',
    title: 'VirtualDisplayPool & InputRouter',
    sub: '标准 FULLSCREEN (1) · TaskSnapshot 150ms 唤醒 · /dev/uhid 回退',
    pill: 'LRU · /dev/uhid'
  },
  {
    id: 'wafer-daemon',
    y: -1.56,
    z: -0.30,
    accent: '#5E7CE2',
    zh: 'LAYER 01 · :bridge Shell 守护进程（强制降权 uid=2000 · SHA-256 鉴权）',
    en: 'LAYER 01 · :bridge Shell Daemon (uid=2000 · SHA-256 Verified)',
    tag: '01 · DAEMON',
    title: 'OpalDeskDaemon · uid=2000',
    sub: '165 KB Java 11 DEX · fail-closed 降权 shell · APK 签名双向鉴权',
    pill: 'SHA-256 IPC'
  }
];

export const STAGE_TAGS = {
  zh: [
    '00 · 双机实拍 · 120fps 工作站',
    '01 · Wayland 虚拟屏 Z 轴分层',
    '02 · LRU 快照休眠窗口廊',
    '03 · 手机↔平板↔Mac 空间互联',
    '04 · 四层立式免 Root 架构',
    '05 · 下载最新版与开源共建'
  ],
  en: [
    '00 · REAL HARDWARE · 120FPS DESK',
    '01 · WAYLAND Z-DEPTH LAYERS',
    '02 · LRU SNAPSHOT CAROUSEL',
    '03 · PHONE ↔ TABLET ↔ MAC CAST',
    '04 · 4-TIER ROOTLESS ARCHITECTURE',
    '05 · DOWNLOAD LATEST & OPEN SOURCE'
  ]
};

export const I18N = {
  zh: {
    ch_0: '序章 · Wayland 式 120fps 多窗工作站',
    ch_1: '一窗一独立虚拟屏 · Z 轴分层拆解',
    ch_2: 'VirtualDisplayPool LRU 快照窗口廊',
    ch_3: '跨设备无线投屏 · 独立小窗与触控板',
    ch_4: '四层立式架构 · 免 Root 降权守护',
    ch_5: '获取最新 APK / Mac 伴侣端',
    nav_overview: '2D 详解',
    nav_web: 'Web 投屏端',
    theme_light: '晴霜浅色',
    theme_dark: '曜石暗色',
    sound_on: '音效',
    sound_off: '静音',
    btn_play: '自动导览',
    btn_pause: '暂停导览',
    nav_get: '下载 APK',
    rail_0: '00 · 双机工作站',
    rail_1: '01 · Z 轴分层拆解',
    rail_2: '02 · 虚拟屏池窗口廊',
    rail_3: '03 · 跨设备空间互投',
    rail_4: '04 · 四层架构晶圆',
    rail_5: '05 · 下载与开源',
    s0_eyebrow: 'ANDROID 11–17 · 免 ROOT 优先 · 120FPS · MIT 开源',
    s0_title: '<span class="title-clause">一窗一独立虚拟屏，</span><span class="title-clause">重塑安卓桌面工作站。</span>',
    s0_desc: '<span class="prose-clause">彻底抛弃厂商私有 Freeform 小窗与撕裂悬浮壳。</span><span class="prose-clause">支持本机 120fps 多窗、手机↔平板↔Mac↔Web 浏览器无线互投，以及扫码局域网免线激活。</span>',
    s0_trigger: '切换 3D 分层爆炸视图',
    s0_web_cta: '打开 Web 投屏端 / 局域网激活',
    s0_overview_cta: '查看 2D 真机画廊与模拟器',
    s0_scroll: '向下滚动或拖动背景旋转机身 · 支持 ↑ / ↓ 与 1–6 快捷键',
    s1_eyebrow: '01 / 05 · EXPLODED WAYLAND COMPOSITOR',
    s1_title: '告别厂商小窗碎片，<br/>一窗一虚拟屏 Z 轴分层。',
    s1_desc: '右侧 3D 平板实时拆解 OpalDesk 渲染管线：每个应用以标准 AOSP FULLSCREEN (1) 运行在专属 VirtualDisplay 中，标题栏、阴影、实时窗口与 Dock 在单一 RenderNode 零撕裂合成。',
    s1_m1: '拖拽仅改 GPU 矩阵，0 Binder IPC',
    s1_m2: '宿主 DPI 超采样，小窗 1:1 清晰',
    s1_m3: '浅 / 深双主题屏幕实时同步切换',
    s1_link: '前往 2D 页面在线试玩多窗分屏模拟器',
    s2_eyebrow: '02 / 05 · VIRTUALDISPLAYPOOL LRU',
    s2_title: '突破系统配额上限，<br/>后台快照休眠 150ms 唤醒。',
    s2_desc: '左侧 3D 弧形窗口廊实时演示 VirtualDisplayPool：蓝框为前台活跃实时虚拟屏，翠绿磨砂为已释放槽位的 TaskSnapshot 快照卡。点击下方任意窗口卡片观察 3D 热唤醒：',
    s2_link: '浏览小米平板 8 Pro & 小米 14 Pro 全套真机实拍',
    s3_eyebrow: '03 / 05 · OPALCAST · MAC · WEB',
    s3_title: '手机、平板、Mac 与浏览器，<br/>全平台零摩擦无线协同。',
    s3_desc: '小米 14 Pro 化作液态毛玻璃精密触控板，支持 2560×1600 @ 120Hz 无线桌面、独立应用悬浮小窗、Mac 原生端以及免安装 GitHub Pages WebCodecs 浏览器被投屏端：',
    t_cast: '手机 → 平板无线桌面 + 手机精密触控板',
    t_float: '跨设备单应用悬浮小窗从手机飞跃至平板',
    t_mac: 'USB-C 2K/4K 外接满屏 + Mac 原生端 & Web 浏览器端',
    s3_web_link: '立即打开 Web 被投屏端（免安装浏览器直连）',
    s3_link: '查看真机投屏、独立小窗与触控板实拍画廊',
    s4_eyebrow: '04 / 05 · FOUR-WAFER ROOTLESS STACK',
    s4_title: '四层解耦架构，<br/>95%+ 免 Root 降权守护。',
    s4_desc: '从底层 uid=2000 降权 Shell 守护进程，到 VirtualDisplayPool 与 /dev/uhid 双通道输入，再到客户端 120fps Compose 合成、Mac 伴侣端与免安装 Web 端，悬停左侧任意晶圆层可查看职责。',
    s4_m1: 'u:r:shell:s0 降权运行，零无障碍',
    s4_m2: 'Linux /dev/uhid 内核级键鼠回退',
    s4_m3: 'APK SHA-256 鉴权 + GitHub OTA',
    s4_link: '查阅四端模块源码与架构检视台',
    s5_eyebrow: 'OPALDESK · ANDROID 11–17 · MACOS 14+ · WEB · MIT',
    s5_title: '<span class="title-clause">全品牌安卓，</span><span class="title-clause">天生桌面级生产力。</span>',
    s5_desc: 'MIT 永久开源免费，内置无线调试与 Web 局域网扫码免线激活，应用内直连 GitHub Releases 自动校验升级。',
    s5_cta_apk: '下载最新 APK & Mac 伴侣端',
    s5_cta_web: '打开 Web 投屏端 & 局域网激活 →',
    s5_cta_home: '进入 2D 实拍画廊与多窗模拟器 →',
    p_web: 'Web 被投屏端 & 局域网激活',
    p_sim: '120fps 多窗模拟器',
    p_gallery: '双机真机实拍画廊',
    p_compare: '架构路线对比',
    p_features: '核心特性总览',
    p_arch: '四端架构蓝图',
    p_dl: '多通道激活指南'
  },
  en: {
    ch_0: 'Prologue · Real Hardware 120fps Workstation',
    ch_1: 'Exploded Wayland Z-Depth Compositor Stack',
    ch_2: 'VirtualDisplayPool LRU Snapshot Carousel',
    ch_3: 'Spatial Phone ↔ Tablet ↔ Mac ↔ Web Casting',
    ch_4: '4-Tier Rootless uid=2000 Architecture',
    ch_5: 'Download Latest APK, Mac App & Web Cast',
    nav_overview: '2D View',
    nav_web: 'Web Cast',
    theme_light: 'Light Frost',
    theme_dark: 'Obsidian',
    sound_on: 'Sound',
    sound_off: 'Muted',
    btn_play: 'Auto Tour',
    btn_pause: 'Pause Tour',
    nav_get: 'Get APK',
    rail_0: '00 · Dual Hardware',
    rail_1: '01 · Z-Depth Stack',
    rail_2: '02 · LRU Carousel',
    rail_3: '03 · Spatial Cast',
    rail_4: '04 · 4-Wafer Arch',
    rail_5: '05 · Download',
    s0_eyebrow: 'ANDROID 11–17 · ROOTLESS-FIRST · 120FPS · MIT OPEN SOURCE',
    s0_title: '<span class="title-clause">One VirtualDisplay per window.</span><span class="title-clause">Android desktop, reborn.</span>',
    s0_desc: '<span class="prose-clause">Zero OEM Freeform hacks, zero split-brain overlay chrome.</span><span class="prose-clause">120fps multi-window desk, Phone ↔ Tablet ↔ Mac ↔ zero-install Web Cast &amp; browser LAN activation.</span>',
    s0_trigger: 'Toggle 3D Exploded Layer View',
    s0_web_cta: 'Open Web Cast & LAN Activation',
    s0_overview_cta: 'Open 2D Gallery & Simulator',
    s0_scroll: 'Scroll or drag stage to inspect hardware · Keys ↑ / ↓ & 1–6',
    s1_eyebrow: '01 / 05 · EXPLODED WAYLAND COMPOSITOR',
    s1_title: 'Zero OEM fragmentation.<br/>Exploded Z-Depth Compositor.',
    s1_desc: 'Watch the 3D tablet unpack OpalDesk’s live pipeline: every app runs in standard AOSP FULLSCREEN (1) inside its own VirtualDisplay, composed with glass chrome, shadows & Dock in one 120fps RenderNode.',
    s1_m1: 'GPU-only matrix drag · 0 Binder IPC',
    s1_m2: 'Host-DPI supersampling · 1:1 sharp',
    s1_m3: 'Live Light / Dark hardware texture sync',
    s1_link: 'Try the interactive 2D Multi-Window Simulator',
    s2_eyebrow: '02 / 05 · VIRTUALDISPLAYPOOL LRU',
    s2_title: 'Beat display quotas.<br/>150ms Snapshot Carousel.',
    s2_desc: 'The 3D spatial window arc visualizes VirtualDisplayPool live: sapphire cards hold active VirtualDisplays while frosted emerald cards hibernate as zero-VD TaskSnapshots. Click any slot to wake:',
    s2_link: 'Browse all 9 real Xiaomi Pad 8 Pro & Xiaomi 14 Pro captures',
    s3_eyebrow: '03 / 05 · OPALCAST · MAC · WEB',
    s3_title: 'Phone, Tablet, Mac & Browser.<br/>Zero-friction synergy.',
    s3_desc: 'Xiaomi 14 Pro becomes a frosted 120Hz trackpad for 2560×1600 @ 120Hz wireless desktop casting, floating apps, native Mac companion, and zero-install GitHub Pages WebCodecs browser casting:',
    t_cast: 'Phone → Tablet Wireless Desktop + Precision Trackpad',
    t_float: 'Standalone Floating App Lifting from Phone to Tablet',
    t_mac: 'USB-C 2K/4K Monitor + Native Mac App & Web Browser Cast',
    s3_web_link: 'Launch Web Cast Receiver (Zero-Install Browser)',
    s3_link: 'Inspect live casting, floating app & trackpad captures',
    s4_eyebrow: '04 / 05 · FOUR-WAFER ROOTLESS STACK',
    s4_title: 'Four decoupled tiers.<br/>95%+ Rootless uid=2000.',
    s4_desc: 'From the fail-closed uid=2000 shell daemon and LRU pool + /dev/uhid input router to the 120fps Compose compositor, Swift 6 Mac app, and WebCodecs browser receiver — hover any wafer on the left.',
    s4_m1: 'Drops to u:r:shell:s0 · 0 Accessibility',
    s4_m2: 'Linux /dev/uhid kernel fallback router',
    s4_m3: 'SHA-256 Binder auth + GitHub OTA',
    s4_link: 'Open Multi-Tier Module Inspector on 2D Page',
    s5_eyebrow: 'OPALDESK · ANDROID 11–17 · MACOS 14+ · WEB · MIT',
    s5_title: '<span class="title-clause">Every Android device,</span><span class="title-clause">a desktop workstation.</span>',
    s5_desc: 'Free MIT open source. Activate with built-in Wireless Debugging or QR-guided Web LAN Activation, with SHA-256 verified GitHub Releases OTA.',
    s5_cta_apk: 'Download Latest APK & macOS App',
    s5_cta_web: 'Open Web Cast & LAN Activation →',
    s5_cta_home: 'Open 2D Real Gallery & Simulator →',
    p_web: 'Web Cast & LAN Activation',
    p_sim: '120fps Simulator',
    p_gallery: 'Real Device Gallery',
    p_compare: 'Architecture Compare',
    p_features: 'Core Capabilities',
    p_arch: 'System Blueprint',
    p_dl: 'Activation Guide'
  }
};

function loadScript(src, timeoutMs = 1500) {
  return new Promise((resolve, reject) => {
    if (window.THREE && window.THREE.WebGLRenderer) return resolve(window.THREE);
    const s = document.createElement('script');
    const timer = setTimeout(() => { s.remove(); reject(new Error('timeout')); }, timeoutMs);
    s.src = src;
    s.async = true;
    s.onload = () => {
      clearTimeout(timer);
      if (window.THREE && window.THREE.WebGLRenderer) resolve(window.THREE);
      else reject(new Error('no THREE'));
    };
    s.onerror = () => { clearTimeout(timer); s.remove(); reject(new Error('err')); };
    document.head.appendChild(s);
  });
}

export async function loadThree() {
  if (window.THREE && window.THREE.WebGLRenderer) return window.THREE;
  for (const url of ESM_CANDIDATES) {
    try {
      const mod = await Promise.race([
        import(url),
        new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), 1600))
      ]);
      if (mod && mod.WebGLRenderer) return mod;
    } catch (_) {}
  }
  for (const url of UMD_CANDIDATES) {
    try {
      const t = await loadScript(url, 1700);
      if (t && t.WebGLRenderer) return t;
    } catch (_) {}
  }
  return null;
}
