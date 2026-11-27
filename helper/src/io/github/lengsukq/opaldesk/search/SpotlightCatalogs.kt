package io.github.lengsukq.opaldesk.search

import io.github.lengsukq.opaldesk.ui.ConsoleSectionKeys

/**
 * Pure JVM live state snapshot used by [ConsoleSearchCatalog] to display dynamic status badges
 * and contextual subtitles inside Host Console & DeX Spotlight search results.
 */
data class ConsoleSpotlightState(
    val connected: Boolean = false,
    val rootEnhanced: Boolean = false,
    val windowCount: Int = 0,
    val remoteConnected: Boolean = false,
    val lanShareActive: Boolean = false,
    val lanEndpoint: String = "",
    val extDisplaySummary: String = "",
    val themeModeLabel: String = "浅色",
    val targetFpsLabel: String = "自动",
    val uiScaleLabel: String = "125%",
    val resolutionSummary: String = "自适应 EDID",
    val perAppDpiSummary: String = "自动适配",
    val peripheralsSummary: String = "1.45×",
    val imeSummary: String = "智能自动",
    val shortcutsSummary: String = "已启 12 项",
    val notifAccessGranted: Boolean = false,
    val slotSummary: String = "槽位 1",
    val audioSummary: String = "独立分流",
    val usbTetherEnabled: Boolean = false,
    val inputBackendSummary: String = "自动 · 配额 6",
    val currentVersion: String = "1.0.22",
    val availableReleaseTag: String? = null,
    val hostScreenOff: Boolean = false,
    val trustedPeerCount: Int = 0,
)

/**
 * Pure JVM live state snapshot used by [DexCommandCatalog] inside DeX Desktop Spotlight.
 */
data class DexSpotlightState(
    val openWindowCount: Int = 0,
    val hasFocusedWindow: Boolean = false,
    val isExternalMonitor: Boolean = false,
    val isDarkTheme: Boolean = false,
    val dockVisible: Boolean = true,
    val hostScreenOff: Boolean = false,
    val canControlPhoneTrackpad: Boolean = false,
    val phoneTrackpadVisible: Boolean = false,
    val resolutionEditable: Boolean = false,
    val unreadNotificationCount: Int = 0,
)

object ConsoleSpotlightActionIds {
    const val ENTER_DEX = "console_action_enter_dex"
    const val OPEN_TRACKPAD = "console_action_open_trackpad"
    const val OPEN_ACTIVATION = "console_action_open_activation"
    const val TOGGLE_WIFI_SHARE = "console_action_toggle_wifi_share"
    const val CHECK_UPDATE = "console_action_check_update"
    const val OPEN_GUIDE = "console_action_open_guide"
    const val PICK_WALLPAPER = "console_action_pick_wallpaper"
    const val REPORT_ISSUE = "console_action_report_issue"
    const val TOGGLE_SCREEN_OFF = "console_action_toggle_screen_off"
    const val COMPLETE_EXIT = "console_action_complete_exit"
}

object DexCommandIds {
    const val SPLIT_2 = "dex_cmd_split_2"
    const val SPLIT_2_MAIN_SIDE = "dex_cmd_split_2_main_side"
    const val SPLIT_3 = "dex_cmd_split_3"
    const val SPLIT_4_COLS = "dex_cmd_split_4_cols"
    const val SPLIT_4_GRID = "dex_cmd_split_4_grid"
    const val SPLIT_6_GRID = "dex_cmd_split_6_grid"
    const val CASCADE = "dex_cmd_cascade"
    const val EXPOSE = "dex_cmd_expose"
    const val MINIMIZE_ALL = "dex_cmd_minimize_all"
    const val CLOSE_ALL = "dex_cmd_close_all"
    const val SCREENSHOT_WINDOW = "dex_cmd_screenshot_window"
    const val SCREENSHOT_DESKTOP = "dex_cmd_screenshot_desktop"
    const val TOGGLE_BLACKOUT = "dex_cmd_toggle_blackout"
    const val TOGGLE_TRACKPAD = "dex_cmd_toggle_trackpad"
    const val OPEN_DPI = "dex_cmd_open_dpi"
    const val OPEN_RESOLUTION = "dex_cmd_open_resolution"
    const val OPEN_IME = "dex_cmd_open_ime"
    const val OPEN_SHORTCUTS = "dex_cmd_open_shortcuts"
    const val PICK_WALLPAPER = "dex_cmd_pick_wallpaper"
    const val TOGGLE_THEME = "dex_cmd_toggle_theme"
    const val TOGGLE_DOCK = "dex_cmd_toggle_dock"
    const val OPEN_GUIDE = "dex_cmd_open_guide"
    const val OPEN_NOTIFICATIONS = "dex_cmd_open_notifications"
    const val OPEN_CONTROL_CENTER = "dex_cmd_open_control_center"
    const val OPEN_CONSOLE = "dex_cmd_open_console"
    const val EXIT_DEX = "dex_cmd_exit_dex"
}

/**
 * Single Source of Truth catalog of all Host Console instant actions (10) and settings/section
 * deep-links (23 across Tabs 0..3) with colloquial Chinese synonyms and pinyin initials.
 */
object ConsoleSearchCatalog {

    val QUICK_CHIPS: List<Pair<String, String>> = listOf(
        "分辨率" to "分辨率",
        "DPI / 字体" to "dpi",
        "输入法" to "输入法",
        "投屏" to "投屏",
        "UHID 触控" to "uhid",
        "快捷键" to "快捷键",
        "通知" to "通知",
        "日志反馈" to "日志",
    )

    fun buildItems(liveState: ConsoleSpotlightState = ConsoleSpotlightState()): List<SpotlightItem> =
        buildActionItems(liveState) + buildSectionItems(liveState)

    fun buildActionItems(liveState: ConsoleSpotlightState = ConsoleSpotlightState()): List<SpotlightItem> = listOf(
        SpotlightItem(
            id = ConsoleSpotlightActionIds.ENTER_DEX,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = "进入本机 DeX 桌面",
            subtitle = if (liveState.windowCount > 0) {
                "工作站运行中（${liveState.windowCount} 窗口）· 立即回到多窗桌面"
            } else {
                "立即进入本机全屏多窗合成工作站"
            },
            badge = if (liveState.connected) "工作站" else "需激活",
            keywords = listOf("dex", "desktop", "桌面", "多窗", "进入桌面", "工作站", "开桌面"),
            pinyinInitials = "jrbjdexzm,dex,zm,gzz",
            pinned = true,
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.OPEN_TRACKPAD,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = "打开本机触控板",
            subtitle = "将手机屏幕变为外接显示器或投屏端的精密多指触控板",
            badge = "外接 / 投屏",
            keywords = listOf("trackpad", "touchpad", "触控板", "触摸板", "鼠标垫", "遥控"),
            pinyinInitials = "dkbjckb,ckb,cmb",
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.OPEN_ACTIVATION,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = if (liveState.connected) "重新激活多窗引擎" else "激活多窗引擎",
            subtitle = "无线调试、Shizuku、PC ADB 或可选 Root 启动后台守护进程",
            badge = when {
                liveState.connected && liveState.rootEnhanced -> "Root 已就绪"
                liveState.connected -> "引擎就绪"
                else -> "未激活"
            },
            keywords = listOf("adb", "shizuku", "root", "daemon", "激活", "无线调试", "引擎", "授权", "重连"),
            pinyinInitials = "jhdcyn,cxjh,wxs,jh,yq",
            pinned = !liveState.connected,
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.TOGGLE_WIFI_SHARE,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = if (liveState.lanShareActive) "关闭无线共享投屏" else "开启无线共享投屏",
            subtitle = if (liveState.lanShareActive && liveState.lanEndpoint.isNotEmpty()) {
                "运行中 · ${liveState.lanEndpoint}（点击停止局域网共享）"
            } else {
                "一键开关局域网 TLS 无线共享，免 PIN 投给电脑、平板或浏览器"
            },
            badge = if (liveState.lanShareActive) "已开启" else "未开启",
            keywords = listOf("wifi", "lan", "cast", "share", "无线", "共享", "投屏", "局域网", "电脑", "mac", "web"),
            pinyinInitials = "kqwxggtp,gbwxggtp,wxgx,tp,jyw",
            pinned = true,
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.CHECK_UPDATE,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = "检查应用更新",
            subtitle = "当前 v${liveState.currentVersion} · 从 GitHub Releases 检查正式版更新",
            badge = liveState.availableReleaseTag ?: "v${liveState.currentVersion}",
            keywords = listOf("update", "release", "github", "upgrade", "更新", "升级", "新版本", "版本"),
            pinyinInitials = "jcyygx,gx,sj,bb",
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.OPEN_GUIDE,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = "打开使用指南与技巧",
            subtitle = "查看特性速览、快捷键说明、排障手册与云端公告",
            badge = "指南",
            keywords = listOf("guide", "help", "docs", "tutorial", "指南", "教程", "帮助", "手册", "说明", "文档"),
            pinyinInitials = "dksyzn,syzn,jc,bz,sm",
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.PICK_WALLPAPER,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = "从相册更换桌面壁纸",
            subtitle = "挑选本地图片作为 DeX 桌面与控制台氛围背景",
            badge = "壁纸",
            keywords = listOf("wallpaper", "photo", "background", "壁纸", "相册", "背景", "换壁纸", "图片"),
            pinyinInitials = "cxcghzmbz,bz,hbz,xc",
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.REPORT_ISSUE,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = "上报问题与诊断日志",
            subtitle = "前往诊断日志页提交 Bug 反馈、功能需求或导出完整报告",
            badge = "日志",
            keywords = listOf("bug", "issue", "report", "feedback", "log", "上报", "反馈", "日志", "报错", "提需求", "qq群"),
            pinyinInitials = "sbwtyzdrz,sb,fk,rz,wt",
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.TOGGLE_SCREEN_OFF,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = if (liveState.hostScreenOff) "点亮本机物理屏幕" else "本机物理息屏省电",
            subtitle = "投屏或外接显示器时熄灭手机 OLED 屏幕降温省电（双击或电源键随时亮屏）",
            badge = if (liveState.hostScreenOff) "已息屏" else "亮屏",
            keywords = listOf("blackout", "screen off", "oled", "power", "息屏", "熄屏", "黑屏", "省电", "亮屏", "防烧屏"),
            pinyinInitials = "bjwlxpsd,dlbjpm,xp,sd,lp",
        ),
        SpotlightItem(
            id = ConsoleSpotlightActionIds.COMPLETE_EXIT,
            category = SpotlightCategory.CONSOLE_ACTION,
            title = "完全退出 OpalDesk",
            subtitle = "保存当前槽位并停止多窗守护进程与后台服务后彻底退出",
            badge = "退出",
            keywords = listOf("exit", "quit", "stop", "kill", "退出", "关闭", "停止引擎", "彻底退出"),
            pinyinInitials = "wqtc,tc,tz,gb",
        ),
    )

    fun buildSectionItems(liveState: ConsoleSpotlightState = ConsoleSpotlightState()): List<SpotlightItem> = listOf(
        // === Tab 0: 工作站 ===
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.HERO}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "工作站主控台与会话状态",
            subtitle = "查看引擎连接状态、活跃窗口数、进入桌面或断开当前投屏",
            badge = "工作站",
            keywords = listOf("home", "dashboard", "hero", "主页", "工作站", "首页", "状态", "窗口"),
            pinyinInitials = "gzzzktyhhzt,zy,gzz",
            tabIndex = 0,
            sectionKey = ConsoleSectionKeys.HERO,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.QUICK_TILES}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "外接投屏与无线共享快捷卡片",
            subtitle = "快速进入 USB-C / 电视投屏或一键开关局域网无线共享",
            badge = "工作站",
            keywords = listOf("tile", "bento", "quick", "快捷", "卡片", "外接", "共享", "开关"),
            pinyinInitials = "wjtpywxgxkjp,kj,kp",
            tabIndex = 0,
            sectionKey = ConsoleSectionKeys.QUICK_TILES,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.INTERCONNECT}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "跨设备互联（连接设备 / 共享本机 / 投电视）",
            subtitle = if (liveState.lanShareActive) "无线共享已开启 · 局域网发现、应用小窗与电视 Miracast" else "连接电脑/平板/手机、开启共享本机或扫码 Web 投屏",
            badge = if (liveState.lanShareActive) "共享中" else "工作站",
            keywords = listOf("interconnect", "cast", "peer", "mirror", "miracast", "web", "qr", "互联", "投屏", "连接设备", "共享本机", "扫码", "二维码", "应用小窗", "电视"),
            pinyinInitials = "ksbhl,hl,tp,ljsb,gxbj,tds",
            tabIndex = 0,
            sectionKey = ConsoleSectionKeys.INTERCONNECT,
            pinned = true,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.MANUAL_CONNECT}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "手动输入 IP 地址连接投屏",
            subtitle = "跨网段或未自动发现设备时，手动填入 IP:端口 直连投屏或应用小窗",
            badge = "工作站",
            keywords = listOf("ip", "port", "manual", "host", "19878", "手动", "地址", "端口", "直连", "局域网"),
            pinyinInitials = "sdsripdzljtp,sdip,ip,dz",
            tabIndex = 0,
            sectionKey = ConsoleSectionKeys.MANUAL_CONNECT,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.TRUSTED_PEERS}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "永久信任设备管理",
            subtitle = "查看或一键撤销免弹窗直连的已授权电脑与平板设备",
            badge = if (liveState.trustedPeerCount > 0) "${liveState.trustedPeerCount} 台" else "工作站",
            keywords = listOf("trust", "trusted", "pair", "revoke", "auth", "信任", "授权", "配对", "撤销", "免pin", "设备管理"),
            pinyinInitials = "yjxrsbgl,xr,sq,cx,pd",
            tabIndex = 0,
            sectionKey = ConsoleSectionKeys.TRUSTED_PEERS,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.SLOTS_QUICK}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "配置槽位快捷切换",
            subtitle = "在 1–5 号工作站方案间一键切换本机、外接屏或投屏端配置",
            badge = liveState.slotSummary,
            keywords = listOf("slot", "profile", "preset", "槽位", "方案", "场景", "快捷切换", "布局"),
            pinyinInitials = "pzcwkjqh,cw,fa,cj",
            tabIndex = 0,
            sectionKey = ConsoleSectionKeys.SLOTS_QUICK,
        ),

        // === Tab 1: 显示与外设 ===
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.APPEARANCE}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "液态毛玻璃外观（浅色 / 跟随系统 / 深色）",
            subtitle = "切换控制台与 DeX 桌面的浅色通透玻璃或深色暗夜风格",
            badge = liveState.themeModeLabel,
            keywords = listOf("theme", "dark", "light", "appearance", "glass", "外观", "主题", "深色", "暗黑", "浅色", "夜间", "毛玻璃"),
            pinyinInitials = "ytmblwg,wg,zt,ss,qs,ah",
            tabIndex = 1,
            sectionKey = ConsoleSectionKeys.APPEARANCE,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.FPS_DPI}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "帧率、界面缩放与渲染超采样",
            subtitle = "设置 60/90/120Hz 目标帧率、100%–200% TV 界面缩放、省电超采与自动息屏",
            badge = "${liveState.targetFpsLabel} · ${liveState.uiScaleLabel}",
            keywords = listOf("fps", "hz", "120", "90", "60", "scale", "dpi", "supersample", "帧率", "高刷", "刷新率", "缩放", "字体大", "字体小", "清晰度", "超采", "发热", "息屏", "省电"),
            pinyinInitials = "zljmsfyxrcy,zl,sf,zt,gs,cc",
            tabIndex = 1,
            sectionKey = ConsoleSectionKeys.FPS_DPI,
            pinned = true,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.EXTERNAL_DISPLAY}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "外接屏与无线投电视",
            subtitle = liveState.extDisplaySummary.ifBlank { "自动满屏、手机触控板、Presentation 模式与电视防裁边安全边距" },
            badge = if (liveState.extDisplaySummary.isNotBlank()) "已连接" else "显示",
            keywords = listOf("hdmi", "typec", "usb-c", "dp", "tv", "miracast", "overscan", "presentation", "ar", "glasses", "外接屏", "显示器", "电视", "无线投屏", "眼镜", "便携屏", "裁边", "黑边", "安全边距", "触控板"),
            pinyinInitials = "wjpywxtds,wjp,ds,xsq,hdmi",
            tabIndex = 1,
            sectionKey = ConsoleSectionKeys.EXTERNAL_DISPLAY,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.CUSTOM_RESOLUTION}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "自定义分辨率与刷新率",
            subtitle = "切换 EDID 原生模式、2K/4K/1080p/21:9 预设或手动宽×高×Hz",
            badge = liveState.resolutionSummary,
            keywords = listOf("resolution", "4k", "2k", "1080p", "720p", "21:9", "edid", "hz", "120", "60", "分辨率", "刷新率", "帧率", "带鱼屏", "宽高", "比例", "自定义"),
            pinyinInitials = "zdyfblysxl,fbl,sxl,4k,2k",
            tabIndex = 1,
            sectionKey = ConsoleSectionKeys.CUSTOM_RESOLUTION,
            pinned = true,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.PER_APP_DPI}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "单 App 独立 DPI 与全局 DPI",
            subtitle = "按应用独立调整字体与界面密度，解决微信/浏览器文字过大或过小",
            badge = liveState.perAppDpiSummary,
            keywords = listOf("dpi", "density", "font", "size", "per-app", "字体大", "字体小", "缩放", "密度", "单应用", "独立", "微信字体", "界面大小"),
            pinyinInitials = "dappdldpi,dpi,ztd,ztx,sf,md",
            tabIndex = 1,
            sectionKey = ConsoleSectionKeys.PER_APP_DPI,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.PERIPHERALS_WALLPAPER}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "外接键鼠、指针速度与桌面壁纸",
            subtitle = "蓝牙/USB 键鼠路由目标、指针灵敏度调节与预设/相册壁纸",
            badge = liveState.peripheralsSummary,
            keywords = listOf("mouse", "keyboard", "bluetooth", "pointer", "cursor", "sensitivity", "wallpaper", "键鼠", "鼠标", "键盘", "蓝牙", "指针", "灵敏度", "光标", "壁纸", "速度"),
            pinyinInitials = "wjjszzsdyzmbz,js,sb,zz,bz,ly",
            tabIndex = 1,
            sectionKey = ConsoleSectionKeys.PERIPHERALS_WALLPAPER,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.KEYBOARD_SHORTCUTS}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "外接键盘快捷键",
            subtitle = "管理 Alt+Space/Ctrl+K 搜索、Alt+Tab 切窗、分屏排版与透传开关",
            badge = liveState.shortcutsSummary,
            keywords = listOf("shortcut", "hotkey", "keymap", "alt+tab", "ctrl+w", "ctrl+k", "alt+space", "快捷键", "热键", "组合键", "键盘", "分屏快捷键"),
            pinyinInitials = "wjjpkjj,kjj,rj,zhj",
            tabIndex = 1,
            sectionKey = ConsoleSectionKeys.KEYBOARD_SHORTCUTS,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.DEX_IME}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "DeX 输入法与语燕插件锁定",
            subtitle = "设置输入法在 DeX 屏内显示、语燕跨屏候选框与实体键盘软键盘策略",
            badge = liveState.imeSummary,
            keywords = listOf("ime", "input", "keyboard", "yuyan", "sogou", "gboard", "baidu", "输入法", "打字", "候选框", "语燕", "搜狗", "百度", "软键盘", "拼音", "双拼", "中文"),
            pinyinInitials = "dexsrfyyycj,srf,dz,yy,hxk,ime",
            tabIndex = 1,
            sectionKey = ConsoleSectionKeys.DEX_IME,
            pinned = true,
        ),

        // === Tab 2: 系统设置 ===
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.NOTIFICATIONS_COMPANION}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "DeX 与跨端通知同步",
            subtitle = if (liveState.notifAccessGranted) "已授权 · 手机消息、验证码、来电与蓝牙近场互联开关" else "开启通知使用权，同步手机消息、验证码与跨端伴侣功能",
            badge = if (liveState.notifAccessGranted) "已开启" else "待授权",
            keywords = listOf("notification", "sms", "otp", "code", "call", "phone", "ble", "bluetooth", "nearby", "hotspot", "通知", "消息", "验证码", "来电", "电话", "接听", "挂断", "蓝牙", "热点", "近场", "伴侣", "同步"),
            pinyinInitials = "dexykdtztb,tz,yzm,ld,dh,ly,rd",
            tabIndex = 2,
            sectionKey = ConsoleSectionKeys.NOTIFICATIONS_COMPANION,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.STARTUP_APPS}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "启动行为与应用列表",
            subtitle = "打开 App 自动进 DeX、应用按打开次数排序与桌面指南图标",
            badge = "设置",
            keywords = listOf("startup", "launch", "auto", "sort", "drawer", "启动", "自动进入", "应用列表", "排序", "常用", "桌面图标"),
            pinyinInitials = "qdxwyyylb,qd,px,yylb",
            tabIndex = 2,
            sectionKey = ConsoleSectionKeys.STARTUP_APPS,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.APP_UPDATE_GUIDE}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "应用更新与使用指南",
            subtitle = "自动检测 GitHub 新版本、手动更新与内置使用指南入口",
            badge = liveState.availableReleaseTag ?: "v${liveState.currentVersion}",
            keywords = listOf("update", "version", "guide", "release", "更新", "升级", "版本", "指南", "教程", "新版本"),
            pinyinInitials = "yygxysyzn,gx,bb,zn",
            tabIndex = 2,
            sectionKey = ConsoleSectionKeys.APP_UPDATE_GUIDE,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.SLOTS_AUTO_LAYOUT}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "配置槽位与自动布局恢复",
            subtitle = "管理 5 组场景槽位、重命名、按设备自动匹配与进入桌面恢复布局",
            badge = liveState.slotSummary,
            keywords = listOf("slot", "layout", "restore", "profile", "memory", "槽位", "布局", "自动布局", "恢复", "记忆", "窗口位置", "场景", "重命名"),
            pinyinInitials = "pzcwyzdbjhf,cw,bj,hf,jy",
            tabIndex = 2,
            sectionKey = ConsoleSectionKeys.SLOTS_AUTO_LAYOUT,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.AUDIO_MEDIA}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "音频分流与远端麦摄",
            subtitle = "手机与 DeX 按应用独立发声、远端麦克风回传与 Root 虚拟摄像头",
            badge = liveState.audioSummary,
            keywords = listOf("audio", "sound", "mic", "microphone", "camera", "media", "split", "音频", "声音", "分流", "独立发声", "麦克风", "麦", "摄像头", "视频通话", "远端"),
            pinyinInitials = "ypflyydms,yp,sy,fl,mkf,sxt",
            tabIndex = 2,
            sectionKey = ConsoleSectionKeys.AUDIO_MEDIA,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.USB_TETHER_BETA}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "实验性 USB 网络共享 (Beta)",
            subtitle = "免 ADB 数据线 USB 共享直连与隔离监听实验通道",
            badge = if (liveState.usbTetherEnabled) "Beta · 已开启" else "Beta",
            keywords = listOf("usb", "tether", "rndis", "beta", "hotspot", "wired", "网络共享", "热点", "数据线", "有线", "免调试", "实验"),
            pinyinInitials = "syxusbwlgx,usb,wlgx,rd,yx",
            tabIndex = 2,
            sectionKey = ConsoleSectionKeys.USB_TETHER_BETA,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.ENGINE_LOWLEVEL}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "引擎底层：输入路由与虚拟屏配额",
            subtitle = "切换 IInputManager / Linux /dev/uhid 解决触摸拦截、调节虚拟屏并发数",
            badge = liveState.inputBackendSummary,
            keywords = listOf("uhid", "input", "touch", "miui", "hyperos", "xiaomi", "vd", "quota", "pool", "小米", "澎湃", "点不动", "无法点击", "触摸", "输入路由", "虚拟屏", "配额", "休眠", "底层", "停止引擎"),
            pinyinInitials = "yqdcsrlyyxnppe,uhid,xm,pp,dbd,cm,xnp,pe",
            tabIndex = 2,
            sectionKey = ConsoleSectionKeys.ENGINE_LOWLEVEL,
        ),

        // === Tab 3: 诊断日志 ===
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.DIAGNOSTICS_LOGS}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "诊断日志、问题上报与 QQ 交流群",
            subtitle = "查看客户端与守护进程实时日志、复制导出报告、上报 Bug 或加 QQ 群",
            badge = "日志",
            keywords = listOf("log", "logs", "debug", "diagnostic", "report", "bug", "qq", "export", "日志", "诊断", "上报", "反馈", "排查", "崩溃", "导出", "qq群"),
            pinyinInitials = "zdrzwtsbyqqjlq,rz,zd,sb,fk,qq",
            tabIndex = 3,
            sectionKey = ConsoleSectionKeys.DIAGNOSTICS_LOGS,
        ),
        SpotlightItem(
            id = "section_${ConsoleSectionKeys.FEEDBACK_TRACKING}",
            category = SpotlightCategory.CONSOLE_SECTION,
            title = "我的反馈追踪与修复进度",
            subtitle = "按 8 位反馈码查询开发者回复、修复状态与对应更新版本",
            badge = "反馈",
            keywords = listOf("feedback", "track", "status", "code", "reply", "fix", "反馈", "追踪", "进度", "反馈码", "修复", "回复", "查询"),
            pinyinInitials = "wdfkzzyxfjd,fk,zz,jd,fkm,cx",
            tabIndex = 3,
            sectionKey = ConsoleSectionKeys.FEEDBACK_TRACKING,
        ),
    )
}

/**
 * Single Source of Truth catalog of DeX Desktop instant commands surfaced inside DeX Spotlight
 * alongside running windows, installed apps, and Host Console deep-links.
 */
object DexCommandCatalog {

    val QUICK_CHIPS: List<Pair<String, String>> = listOf(
        "分屏" to "分屏",
        "截屏" to "截屏",
        "DPI" to "dpi",
        "调度中心" to "调度",
        "息屏" to "息屏",
        "输入法" to "输入法",
        "深浅主题" to "外观",
        "控制台" to "控制台",
    )

    fun buildItems(liveState: DexSpotlightState = DexSpotlightState()): List<SpotlightItem> = listOf(
        SpotlightItem(
            id = DexCommandIds.SPLIT_2,
            category = SpotlightCategory.DEX_ACTION,
            title = "左右双分屏 (2 列)",
            subtitle = "将当前可见窗口按两列等宽自动排布",
            badge = "Alt+2",
            keywords = listOf("split", "tile", "2", "双分屏", "分屏", "两列", "左右", "排版"),
            pinyinInitials = "zysfp,sfp,fp,ll",
            pinned = true,
        ),
        SpotlightItem(
            id = DexCommandIds.SPLIT_2_MAIN_SIDE,
            category = SpotlightCategory.DEX_ACTION,
            title = "主副双分屏 (64/36)",
            subtitle = "左侧大主窗配合右侧边栏辅窗排布",
            badge = "排版",
            keywords = listOf("split", "main", "side", "主副", "分屏", "侧栏", "排版"),
            pinyinInitials = "zfsfp,zffp,fp",
        ),
        SpotlightItem(
            id = DexCommandIds.SPLIT_3,
            category = SpotlightCategory.DEX_ACTION,
            title = "三列并排分屏 (3 列)",
            subtitle = "将当前可见窗口按三列等宽并排",
            badge = "Alt+3",
            keywords = listOf("split", "3", "三分屏", "三列", "分屏", "并排", "排版"),
            pinyinInitials = "slbpfp,sl,sfp,fp",
        ),
        SpotlightItem(
            id = DexCommandIds.SPLIT_4_COLS,
            category = SpotlightCategory.DEX_ACTION,
            title = "四列并排分屏 (4 列)",
            subtitle = "适合带鱼屏或 4K 大屏四窗横向并列",
            badge = "Alt+4",
            keywords = listOf("split", "4", "四列", "四分屏", "分屏", "带鱼屏", "排版"),
            pinyinInitials = "slbpfp4,sll,fp",
        ),
        SpotlightItem(
            id = DexCommandIds.SPLIT_4_GRID,
            category = SpotlightCategory.DEX_ACTION,
            title = "2×2 四宫格分屏",
            subtitle = "四窗口田字网格高效同屏处理",
            badge = "Alt+G",
            keywords = listOf("split", "grid", "4", "四宫格", "四分屏", "田字", "网格", "分屏"),
            pinyinInitials = "sggfp,sgg,fp,wg",
            pinned = true,
        ),
        SpotlightItem(
            id = DexCommandIds.SPLIT_6_GRID,
            category = SpotlightCategory.DEX_ACTION,
            title = "3×2 六宫格分屏",
            subtitle = "六窗口 3×2 网格平铺（适合大屏多任务）",
            badge = "Alt+6",
            keywords = listOf("split", "grid", "6", "六宫格", "六分屏", "网格", "分屏"),
            pinyinInitials = "lggfp,lgg,fp",
        ),
        SpotlightItem(
            id = DexCommandIds.CASCADE,
            category = SpotlightCategory.DEX_ACTION,
            title = "窗口层叠排列",
            subtitle = "将当前窗口按经典阶梯层叠错落展开",
            badge = "排版",
            keywords = listOf("cascade", "stack", "层叠", "阶梯", "排列", "整理窗口"),
            pinyinInitials = "ckcdpl,cd,pl",
        ),
        SpotlightItem(
            id = DexCommandIds.EXPOSE,
            category = SpotlightCategory.DEX_ACTION,
            title = "调度中心 / 多窗总览 (Exposé)",
            subtitle = "平铺预览全部 ${liveState.openWindowCount} 个窗口缩略图并快速切换",
            badge = "${liveState.openWindowCount} 窗",
            keywords = listOf("expose", "mission control", "overview", "multitask", "调度中心", "总览", "多任务", "预览", "切换窗口"),
            pinyinInitials = "ddzxdczl,ddzx,zl,drw",
            pinned = true,
        ),
        SpotlightItem(
            id = DexCommandIds.MINIMIZE_ALL,
            category = SpotlightCategory.DEX_ACTION,
            title = "全部最小化 / 显示桌面",
            subtitle = "一键收起所有可见应用窗口，回到清爽桌面",
            badge = "桌面",
            keywords = listOf("minimize", "desktop", "hide", "show desktop", "最小化", "显示桌面", "收起全部", "清屏"),
            pinyinInitials = "qbzxhxszm,zxh,xszm,sq",
        ),
        SpotlightItem(
            id = DexCommandIds.CLOSE_ALL,
            category = SpotlightCategory.DEX_ACTION,
            title = "关闭全部应用窗口",
            subtitle = "关闭当前桌面所有已打开的应用窗口并释放虚拟屏",
            badge = "清理",
            keywords = listOf("close all", "clear", "kill", "关闭全部", "全关", "清空窗口", "释放"),
            pinyinInitials = "gbqbyyck,gbqb,qg",
        ),
        SpotlightItem(
            id = DexCommandIds.SCREENSHOT_WINDOW,
            category = SpotlightCategory.DEX_ACTION,
            title = "截取当前焦点窗口",
            subtitle = "高清截取当前聚焦应用窗口，保存相册并复制剪贴板",
            badge = if (liveState.hasFocusedWindow) "焦点窗" else "无焦点",
            keywords = listOf("screenshot", "capture", "snap", "window", "截图", "截屏", "窗口截图", "抓图"),
            pinyinInitials = "jqdqjdck,jt,jp,ckjt",
            pinned = true,
        ),
        SpotlightItem(
            id = DexCommandIds.SCREENSHOT_DESKTOP,
            category = SpotlightCategory.DEX_ACTION,
            title = "截取整个 DeX 桌面",
            subtitle = "截取完整工作站全屏画面并保存到 Pictures/OpalDesk",
            badge = "全屏截图",
            keywords = listOf("screenshot", "full", "desktop", "capture", "全屏截图", "截屏", "桌面截图", "截图"),
            pinyinInitials = "jqzgdexzm,qpjt,zmjt,jp",
        ),
        SpotlightItem(
            id = DexCommandIds.TOGGLE_BLACKOUT,
            category = SpotlightCategory.DEX_ACTION,
            title = if (liveState.hostScreenOff) "点亮本机手机屏幕" else "本机物理息屏省电",
            subtitle = "外接或投屏时熄灭原手机屏幕省电降温，随时双击点亮",
            badge = if (liveState.hostScreenOff) "已息屏" else "亮屏",
            keywords = listOf("blackout", "screen off", "sleep", "oled", "息屏", "熄屏", "黑屏", "省电", "亮屏"),
            pinyinInitials = "bjwlxpsd,dlbjsjpm,xp,sd,lp",
        ),
        SpotlightItem(
            id = DexCommandIds.TOGGLE_TRACKPAD,
            category = SpotlightCategory.DEX_ACTION,
            title = if (liveState.phoneTrackpadVisible) "关闭手机触控板" else "打开手机触控板",
            subtitle = if (liveState.canControlPhoneTrackpad) {
                "在外接显示器模式下将手机屏幕切换为精密多指触控板"
            } else {
                "连接外接显示器或桌面投屏时可用手机屏幕作触控板"
            },
            badge = if (liveState.phoneTrackpadVisible) "已开启" else "触控板",
            keywords = listOf("trackpad", "touchpad", "phone", "触控板", "触摸板", "手机触控板", "遥控"),
            pinyinInitials = "dksjckb,gbsjckb,ckb,cmb",
        ),
        SpotlightItem(
            id = DexCommandIds.OPEN_DPI,
            category = SpotlightCategory.DEX_ACTION,
            title = "实时 DPI 调节面板",
            subtitle = "边看窗口边拖动调整全局 DPI 与各应用独立字体缩放",
            badge = "实时预览",
            keywords = listOf("dpi", "scale", "font", "zoom", "字体", "字体大", "缩放", "密度", "清晰度"),
            pinyinInitials = "ssdpitjmb,dpi,zt,sf",
            pinned = true,
        ),
        SpotlightItem(
            id = DexCommandIds.OPEN_RESOLUTION,
            category = SpotlightCategory.DEX_ACTION,
            title = "自定义分辨率与刷新率",
            subtitle = if (liveState.resolutionEditable) {
                "打开分辨率弹窗切换 EDID、2K/4K 或自定义宽高与刷新率"
            } else {
                "外接显示器或投屏会话中可实时切换输出分辨率与帧率"
            },
            badge = "分辨率",
            keywords = listOf("resolution", "4k", "2k", "1080p", "120", "hz", "edid", "分辨率", "刷新率", "帧率"),
            pinyinInitials = "zdyfblysxl,fbl,sxl,4k,2k",
        ),
        SpotlightItem(
            id = DexCommandIds.OPEN_IME,
            category = SpotlightCategory.DEX_ACTION,
            title = "DeX 输入法与语燕插件设置",
            subtitle = "调整输入法显示位置、语燕跨屏候选栏与锁定输入法",
            badge = "输入法",
            keywords = listOf("ime", "input", "yuyan", "sogou", "keyboard", "输入法", "打字", "语燕", "候选框", "键盘"),
            pinyinInitials = "dexsrfyyycjsz,srf,dz,yy,ime",
        ),
        SpotlightItem(
            id = DexCommandIds.OPEN_SHORTCUTS,
            category = SpotlightCategory.DEX_ACTION,
            title = "键盘快捷键速查与开关",
            subtitle = "查看全部外接键盘快捷键并按需单独禁用冲突按键",
            badge = "快捷键",
            keywords = listOf("shortcuts", "hotkey", "keymap", "keyboard", "快捷键", "热键", "组合键", "速查"),
            pinyinInitials = "jpkjjscyk,kjj,rj",
        ),
        SpotlightItem(
            id = DexCommandIds.PICK_WALLPAPER,
            category = SpotlightCategory.DEX_ACTION,
            title = "更换桌面壁纸",
            subtitle = "从系统相册选择图片立即设为当前 DeX 桌面壁纸",
            badge = "壁纸",
            keywords = listOf("wallpaper", "background", "photo", "壁纸", "换壁纸", "背景", "相册"),
            pinyinInitials = "ghzmbz,bz,hbz",
        ),
        SpotlightItem(
            id = DexCommandIds.TOGGLE_THEME,
            category = SpotlightCategory.DEX_ACTION,
            title = if (liveState.isDarkTheme) "切换为浅色液态玻璃" else "切换为深色液态玻璃",
            subtitle = "立即在浅色通透玻璃与深色暗夜玻璃主题之间切换",
            badge = if (liveState.isDarkTheme) "当前深色" else "当前浅色",
            keywords = listOf("theme", "dark", "light", "appearance", "外观", "主题", "深色", "浅色", "暗黑", "夜间"),
            pinyinInitials = "qhwqsytbl,qhwssytbl,zt,wg,ss,qs",
        ),
        SpotlightItem(
            id = DexCommandIds.TOGGLE_DOCK,
            category = SpotlightCategory.DEX_ACTION,
            title = if (liveState.dockVisible) "隐藏底部程序坞 (Dock)" else "显示底部程序坞 (Dock)",
            subtitle = "切换底部 Dock 栏可见性以获取最大纵向工作区",
            badge = if (liveState.dockVisible) "显示中" else "已隐藏",
            keywords = listOf("dock", "taskbar", "bar", "程序坞", "任务栏", "底栏", "隐藏", "显示"),
            pinyinInitials = "ycdbcxw,xsdbcxw,dock,cxw,rwl",
        ),
        SpotlightItem(
            id = DexCommandIds.OPEN_GUIDE,
            category = SpotlightCategory.DEX_ACTION,
            title = "打开 OpalDesk 使用指南",
            subtitle = "查看多窗技巧、键鼠操作说明与云端最新公告",
            badge = "指南",
            keywords = listOf("guide", "help", "manual", "指南", "帮助", "教程", "说明"),
            pinyinInitials = "dksyzn,zn,bz,jc",
        ),
        SpotlightItem(
            id = DexCommandIds.OPEN_NOTIFICATIONS,
            category = SpotlightCategory.DEX_ACTION,
            title = "打开桌面通知中心",
            subtitle = "查看手机同步消息、复制验证码或一键跳转对应应用",
            badge = if (liveState.unreadNotificationCount > 0) "${liveState.unreadNotificationCount} 条" else "通知",
            keywords = listOf("notification", "message", "sms", "otp", "通知", "消息", "验证码", "通知中心"),
            pinyinInitials = "dkzmtzzx,tz,xx,yzm",
        ),
        SpotlightItem(
            id = DexCommandIds.OPEN_CONTROL_CENTER,
            category = SpotlightCategory.DEX_ACTION,
            title = "打开桌面控制中心",
            subtitle = "亮度、音量、帧率、分屏排版、程序坞外观与快捷开关",
            badge = "控制中心",
            keywords = listOf("control center", "settings", "brightness", "volume", "控制中心", "亮度", "音量", "快捷设置"),
            pinyinInitials = "dkzmkzzx,kzzx,ld,yl",
        ),
        SpotlightItem(
            id = DexCommandIds.OPEN_CONSOLE,
            category = SpotlightCategory.DEX_ACTION,
            title = "返回主机控制台",
            subtitle = "回到手机控制台管理投屏互联、显示配置与诊断日志",
            badge = "控制台",
            keywords = listOf("console", "host", "main", "home", "控制台", "主机", "设置", "返回"),
            pinyinInitials = "fhzjkzt,kzt,zj,fh",
        ),
        SpotlightItem(
            id = DexCommandIds.EXIT_DEX,
            category = SpotlightCategory.DEX_ACTION,
            title = "退出 DeX 桌面",
            subtitle = "打开槽位保存弹窗并安全退出当前 DeX 桌面会话",
            badge = "退出",
            keywords = listOf("exit", "quit", "leave", "退出", "离开", "保存退出", "结束桌面"),
            pinyinInitials = "tcdexzm,tc,lk",
        ),
    )
}
