package io.github.lengsukq.opaldesk.guide

import java.util.Locale

/**
 * Category filter tabs for the built-in OpalDesk FAQ catalog.
 */
enum class OpalFaqCategory(
    val zhLabel: String,
    val enLabel: String,
) {
    ALL("全部", "All"),
    TOUCH_INPUT("触控与键鼠", "Touch & Input"),
    CAST_CONNECTION("投屏与连接", "Cast & Network"),
    DISPLAY_ACTIVATION("显示与激活", "Display & Setup"),
    ;

    fun label(locale: Locale = Locale.getDefault()): String =
        if (OpalFaqCatalog.isChineseLocale(locale)) zhLabel else enLabel
}

/**
 * Structured, pure-Kotlin FAQ entry supporting bilingual display and keyword search.
 */
data class OpalFaqEntry(
    val id: String,
    val category: OpalFaqCategory,
    val question: String,
    val summary: String,
    val steps: List<String>,
    val keywords: List<String>,
)

/**
 * Pure-JVM testable bilingual FAQ catalog and filtering engine for OpalDesk `:helper`.
 *
 * Keeps the highest-frequency troubleshooting entry (unresponsive touch / USB debugging)
 * pinned at index 0 in both Simplified Chinese and English catalogs.
 */
object OpalFaqCatalog {
    const val ID_TOUCH_UNRESPONSIVE = "touch_unresponsive"
    const val ID_MOUSE_CURSOR_MISSING = "mouse_cursor_missing"
    const val ID_CUSTOM_KEY_MOUSE_HIJACK = "custom_key_mouse_hijack"
    const val ID_YUYAN_IME_KEYBOARD = "yuyan_ime_keyboard"
    const val ID_WIRELESS_CAST_NETWORK = "wireless_cast_network"
    const val ID_ACTIVATION_PAIRING_STUCK = "activation_pairing_stuck"
    const val ID_APP_ASPECT_DPI = "app_aspect_dpi"
    const val ID_SCREEN_OFF_DISCONNECT = "screen_off_disconnect"
    const val ID_AUDIO_ROUTING = "audio_routing"

    fun isChineseLocale(locale: Locale = Locale.getDefault()): Boolean =
        locale.language.equals("zh", ignoreCase = true)

    fun categories(): List<OpalFaqCategory> = OpalFaqCategory.entries

    fun entries(locale: Locale = Locale.getDefault()): List<OpalFaqEntry> =
        if (isChineseLocale(locale)) ZH_ENTRIES else EN_ENTRIES

    fun filterEntries(
        entries: List<OpalFaqEntry>,
        category: OpalFaqCategory = OpalFaqCategory.ALL,
        query: String = "",
    ): List<OpalFaqEntry> {
        val byCategory = if (category == OpalFaqCategory.ALL) {
            entries
        } else {
            entries.filter { it.category == category }
        }
        val tokens = query
            .trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return byCategory
        return byCategory.filter { entry ->
            val haystack = buildSearchableText(entry)
            tokens.all { token -> haystack.contains(token) }
        }
    }

    fun search(
        query: String = "",
        category: OpalFaqCategory = OpalFaqCategory.ALL,
        locale: Locale = Locale.getDefault(),
    ): List<OpalFaqEntry> = filterEntries(entries(locale), category, query)

    private fun buildSearchableText(entry: OpalFaqEntry): String = buildString {
        append(entry.id)
        append(' ')
        append(entry.category.zhLabel)
        append(' ')
        append(entry.category.enLabel)
        append(' ')
        append(entry.question)
        append(' ')
        append(entry.summary)
        for (step in entry.steps) {
            append(' ')
            append(step)
        }
        for (kw in entry.keywords) {
            append(' ')
            append(kw)
        }
    }.lowercase(Locale.ROOT)

    val ZH_ENTRIES: List<OpalFaqEntry> = listOf(
        OpalFaqEntry(
            id = ID_TOUCH_UNRESPONSIVE,
            category = OpalFaqCategory.TOUCH_INPUT,
            question = "无法触控 / 桌面窗口点击无响应",
            summary = "需在系统开发者选项开启 USB 调试及安全输入授权，并确认引擎守护进程处于已连接状态。",
            steps = listOf(
                "打开手机「系统设置 → 开发者选项」，确认已开启「USB 调试」。",
                "小米 / Redmi（HyperOS / MIUI）机型需额外开启「USB 调试（安全设置）」，否则系统会拦截跨屏点击与键鼠注入。",
                "OPPO / 一加 / 真我（ColorOS）机型需在开发者选项最底部开启「禁止权限监控」。",
                "返回 OpalDesk 主控制台顶部确认引擎状态为「引擎就绪 / 已连接」；若个别 ROM 仍限制系统注入，可在「设置 → 底层引擎配置」将输入后端切换为「/dev/uhid 内核通道」。",
            ),
            keywords = listOf(
                "触控", "点击", "无响应", "没反应", "USB调试", "USB 调试", "安全设置",
                "HyperOS", "MIUI", "小米", "OPPO", "ColorOS", "禁止权限监控", "uhid", "守护进程",
            ),
        ),
        OpalFaqEntry(
            id = ID_MOUSE_CURSOR_MISSING,
            category = OpalFaqCategory.TOUCH_INPUT,
            question = "外接鼠标光标消失或无法识别",
            summary = "重新将外接键鼠路由切换为「优先投屏 / 外接端」，并排查第三方投屏工具是否抢占鼠标绑定。",
            steps = listOf(
                "打开控制台「显示 → 外接键鼠与桌面壁纸 → 外接键鼠输出目标」，重新切换为「优先投屏 / 外接端」。",
                "检查蓝牙或 USB-C 扩展坞连接是否稳定，必要时重新插拔鼠标接收器或重连蓝牙。",
                "排查后台是否同时运行 Scrcpy、系统桌面模式或其他第三方屏连工具，它们可能抢占外接鼠标焦点绑定。",
                "在外接屏或投屏触控板顶栏可随时点击「外接键鼠」按钮切换光标作用屏幕。",
            ),
            keywords = listOf(
                "鼠标", "光标", "消失", "无法识别", "外接键鼠", "路由", "优先外接屏", "蓝牙鼠标", "USB鼠标",
            ),
        ),
        OpalFaqEntry(
            id = ID_CUSTOM_KEY_MOUSE_HIJACK,
            category = OpalFaqCategory.TOUCH_INPUT,
            question = "自定义鼠标侧键 / 快捷键劫持与录制",
            summary = "在快捷键设置中可一键录制任意键盘按键与小众鼠标侧键，USB 与无线投屏均通用。",
            steps = listOf(
                "进入控制台「显示 → 键盘快捷键 → 自定义键鼠劫持」，点击「+ 添加劫持映射」。",
                "在录制弹窗内直接按下想绑定的键盘组合键或鼠标侧键（含小众宏侧键），选择目标桌面动作并保存。",
                "录制完成后，USB 有线投屏、局域网无线投屏与本机外接键鼠均会自动生效。",
                "注意：系统级保留组合键（如电源/截屏热键）或浏览器独占热键由系统/浏览器优先截获，无法被应用层完全接管。",
            ),
            keywords = listOf(
                "侧键", "鼠标侧键", "快捷键", "劫持", "录制", "按键映射", "自定义", "热键", "浏览器",
            ),
        ),
        OpalFaqEntry(
            id = ID_YUYAN_IME_KEYBOARD,
            category = OpalFaqCategory.TOUCH_INPUT,
            question = "语燕输入法或实体键盘无法正常打字 / 切换中英文",
            summary = "检查 DeX 输入法插件与显示位置设置，并避免自定义键鼠映射与常用打字快捷键冲突。",
            steps = listOf(
                "进入控制台「显示 → DeX 输入法」，确认已启用语燕输入法插件或选择目标锁定输入法，并检查屏内候选栏显示位置。",
                "连接蓝牙或 USB 实体键盘后，可使用 Shift+Space、Ctrl+Space 或地球键在当前窗口内切换中英文。",
                "若出现按键被吞或无法上屏，前往「显示 → 键盘快捷键」检查是否启用了冲突的自定义键鼠劫持映射或全局热键。",
                "部分应用首次切窗时需点击一次输入框重新获取焦点。",
            ),
            keywords = listOf(
                "语燕", "输入法", "实体键盘", "物理键盘", "打字", "中英文", "候选栏", "IME", "键盘",
            ),
        ),
        OpalFaqEntry(
            id = ID_WIRELESS_CAST_NETWORK,
            category = OpalFaqCategory.CAST_CONNECTION,
            question = "无线投屏搜不到设备 / 连接超时 / 画面卡顿",
            summary = "确保设备同处一个局域网、关闭 AP 隔离与 VPN 干扰，优先使用 5GHz Wi-Fi 或 USB 共享网络。",
            steps = listOf(
                "确认发射端与接收端已连接同一局域网 Wi-Fi，且发射端已在「跨设备互联 → 共享本机」开启局域网共享开关。",
                "检查路由器后台是否开启了「AP 隔离 / 访客网络」，同时暂时关闭手机和电脑上的 VPN 或全局代理分流。",
                "若自动发现仍搜不到设备，可直接在「连接设备 → 手动输入 IP 地址连接」填入对方显示的 IP:端口。",
                "画面卡顿或延迟偏高时，优先切换至 5GHz Wi-Fi，或改用数据线开启「USB 网络共享直连」获得更低延迟。",
            ),
            keywords = listOf(
                "无线投屏", "搜不到", "连接超时", "卡顿", "延迟", "局域网", "AP隔离", "VPN", "5GHz", "USB共享", "IP",
            ),
        ),
        OpalFaqEntry(
            id = ID_ACTIVATION_PAIRING_STUCK,
            category = OpalFaqCategory.DISPLAY_ACTIVATION,
            question = "激活失败 / 无线调试配对一直转圈",
            summary = "核对最新的 6 位配对码与动态端口，重启无线调试开关，或改用 Shizuku / PC ADB 激活。",
            steps = listOf(
                "在系统「开发者选项 → 无线调试 → 使用配对码配对设备」中核对当前 6 位配对码与端口号（每次重新打开弹窗端口都会变化）。",
                "配对时建议用分屏或悬浮通知栏填写，切勿完全关闭无线调试配对弹窗。",
                "若长时间转圈，请先关闭再重新打开系统「无线调试」开关，并确保手机已连接 Wi-Fi。",
                "也可以直接切换使用 Shizuku 一键授权，或通过电脑执行 ADB 激活命令完成启动。",
            ),
            keywords = listOf(
                "激活", "失败", "无线调试", "配对", "转圈", "配对码", "端口", "Shizuku", "ADB",
            ),
        ),
        OpalFaqEntry(
            id = ID_APP_ASPECT_DPI,
            category = OpalFaqCategory.DISPLAY_ACTIVATION,
            question = "部分 App 画面比例异常、黑边或字体过小",
            summary = "通过单应用独立 DPI 调节显示密度，或切换桌面 / 平板 / 竖屏窗口预设与 1:1 点对点输出。",
            steps = listOf(
                "进入「显示 → 单 App 独立 DPI」，或在 DeX 桌面内点击顶栏 DPI 按钮 / 长按应用图标，单独调高或调低该应用的 DPI。",
                "在窗口标题栏或布局菜单中切换「桌面 / 平板 / 竖屏」窗口比例预设，以适配竖屏优先的移动应用。",
                "若 4:3 或异形便携屏两侧出现黑边，前往「显示 → 自定义分辨率与刷新率」开启「点对点原生输出（关闭系统缩放）」。",
                "若电视画面边缘被裁切，可在「外接屏与无线投电视」中微调 0–6% 防裁边安全边距（Overscan）。",
            ),
            keywords = listOf(
                "比例", "黑边", "字体", "过小", "DPI", "分辨率", "竖屏", "平板", "缩放", "Overscan", "便携屏",
            ),
        ),
        OpalFaqEntry(
            id = ID_SCREEN_OFF_DISCONNECT,
            category = OpalFaqCategory.CAST_CONNECTION,
            question = "熄屏投屏黑屏或后台断连",
            summary = "配置电池无限制、锁定后台与防杀白名单；无线投电视优先使用「暗屏触控」。",
            steps = listOf(
                "进入手机「系统设置 → 应用管理 → OpalDesk → 省电策略」，设置为「无限制 / 允许后台高耗电」。",
                "在系统最近任务界面下拉或点击菜单「锁定」OpalDesk，并将其加入系统管家的自启动与防清理白名单。",
                "无线投屏（尤其是 Miracast 投电视）时，部分机型物理息屏会导致画面冻结或断连，建议优先使用触控板顶部的「暗屏触控」。",
                "退出 DeX 自动亮屏；软黑屏期间可双击屏幕、双击音量+或同时按音量±唤醒主机屏幕。",
            ),
            keywords = listOf(
                "熄屏", "息屏", "黑屏", "断连", "后台", "杀后台", "电池", "省电", "白名单", "暗屏触控",
                "自动亮屏", "音量键", "唤醒",
            ),
        ),
        OpalFaqEntry(
            id = ID_AUDIO_ROUTING,
            category = OpalFaqCategory.CAST_CONNECTION,
            question = "投屏无声音或手机与大屏音频路由不对",
            summary = "在设置中切换音频分流模式，并确认系统媒体音量与对端播放设备正常。",
            steps = listOf(
                "进入控制台「设置 → 音频分流与远程媒体」，按需选择「按应用分流」「全部输出至投屏端」或「仅保留在手机」。",
                "检查手机媒体音量与电脑 / 电视端扬声器是否静音或被切到了其他音频输出设备。",
                "切换音频路由模式后，若正在播放的个别应用未立即切换，可暂停后重新播放或重连投屏会话。",
            ),
            keywords = listOf(
                "声音", "音频", "无声", "静音", "分流", "串音", "扬声器", "audio",
            ),
        ),
    )

    val EN_ENTRIES: List<OpalFaqEntry> = listOf(
        OpalFaqEntry(
            id = ID_TOUCH_UNRESPONSIVE,
            category = OpalFaqCategory.TOUCH_INPUT,
            question = "Touch not working / Desktop windows unresponsive to clicks",
            summary = "Enable USB Debugging and vendor input-injection permissions in Developer Options, and verify the engine daemon is connected.",
            steps = listOf(
                "Open Android Settings → Developer Options and make sure \"USB Debugging\" is enabled.",
                "On Xiaomi / Redmi (HyperOS / MIUI), also enable \"USB Debugging (Security Settings)\" so the OS allows cross-display touch and mouse injection.",
                "On OPPO / OnePlus / Realme (ColorOS), enable \"Disable Permission Monitoring\" near the bottom of Developer Options.",
                "Confirm the top status pill in OpalDesk Console shows \"Engine Ready\". If touch is still blocked on your ROM, switch Input Backend to \"/dev/uhid\" under Settings → Engine.",
            ),
            keywords = listOf(
                "touch", "click", "unresponsive", "usb debugging", "security settings",
                "hyperos", "miui", "xiaomi", "oppo", "coloros", "permission monitoring", "uhid", "daemon",
            ),
        ),
        OpalFaqEntry(
            id = ID_MOUSE_CURSOR_MISSING,
            category = OpalFaqCategory.TOUCH_INPUT,
            question = "External mouse cursor missing or not recognized",
            summary = "Switch External Mouse / Keyboard Target back to \"Prefer Cast / Ext\" and check for third-party screen-casting tools seizing pointer focus.",
            steps = listOf(
                "Open Console → Display → Peripherals & Desktop Wallpaper → External Mouse / Keyboard Target and select \"Prefer Cast / Ext\".",
                "Verify your Bluetooth pairing or USB-C hub connection, and replug the receiver if needed.",
                "Close other screen-casting or desktop-mode tools (such as Scrcpy or vendor cast clients) that may hijack mouse pointer binding.",
                "While casting, you can also toggle the pointer target directly from the Phone Trackpad top bar.",
            ),
            keywords = listOf(
                "mouse", "cursor", "pointer", "missing", "external", "routing", "bluetooth", "usb",
            ),
        ),
        OpalFaqEntry(
            id = ID_CUSTOM_KEY_MOUSE_HIJACK,
            category = OpalFaqCategory.TOUCH_INPUT,
            question = "Custom mouse side buttons & shortcut hijack recording",
            summary = "Record any keyboard combo or niche mouse side button in Keyboard Shortcuts; works over both USB and wireless cast.",
            steps = listOf(
                "Go to Console → Display → Keyboard Shortcuts → Custom Key/Mouse Hijack and tap \"+ Add Hijack Mapping\".",
                "Press the desired keyboard key or mouse side button during recording, choose the target action, and save.",
                "Saved mappings apply uniformly across USB cast, wireless LAN cast, and local external peripherals.",
                "Note: OS-reserved hardware hotkeys and browser-reserved shortcuts are intercepted before reaching apps and cannot be overridden.",
            ),
            keywords = listOf(
                "side button", "mouse", "shortcut", "hijack", "record", "remap", "keybinding", "browser",
            ),
        ),
        OpalFaqEntry(
            id = ID_YUYAN_IME_KEYBOARD,
            category = OpalFaqCategory.TOUCH_INPUT,
            question = "Yuyan IME or physical keyboard cannot type / switch language",
            summary = "Check DeX IME placement and Yuyan plugin settings, and ensure custom key mappings do not conflict with typing shortcuts.",
            steps = listOf(
                "Open Console → Display → DeX Input Method to enable the Yuyan IME plugin (or lock your preferred IME) and verify in-display candidate bar placement.",
                "With a Bluetooth or USB keyboard attached, press Shift+Space, Ctrl+Space, or the Globe key to toggle input languages.",
                "If keys are swallowed or do not commit text, check Display → Keyboard Shortcuts for conflicting custom hijack mappings or global hotkeys.",
                "Tap the target text field once after switching windows to ensure input focus.",
            ),
            keywords = listOf(
                "yuyan", "ime", "keyboard", "physical keyboard", "typing", "chinese", "english", "language", "candidate",
            ),
        ),
        OpalFaqEntry(
            id = ID_WIRELESS_CAST_NETWORK,
            category = OpalFaqCategory.CAST_CONNECTION,
            question = "Wireless cast cannot find devices, times out, or lags",
            summary = "Keep both devices on the same LAN, disable AP isolation and VPN routing, and prefer 5GHz Wi-Fi or USB tethering.",
            steps = listOf(
                "Ensure both devices are on the same Wi-Fi LAN and \"Share Host\" is turned on under Workstation → Cross-Device Interconnect.",
                "Disable router AP isolation / guest Wi-Fi mode, and pause VPN or proxy split-tunneling on both phone and PC.",
                "If automatic discovery does not appear, expand \"Connect by Manual IP\" and enter the peer's IP:Port directly.",
                "For smoother frame rates and lower latency, switch to 5GHz Wi-Fi or use \"USB Tethering Direct\" over a cable.",
            ),
            keywords = listOf(
                "wireless", "cast", "discovery", "timeout", "lag", "lan", "ap isolation", "vpn", "5ghz", "usb tether", "ip",
            ),
        ),
        OpalFaqEntry(
            id = ID_ACTIVATION_PAIRING_STUCK,
            category = OpalFaqCategory.DISPLAY_ACTIVATION,
            question = "Activation failed or wireless debugging pairing stuck",
            summary = "Verify the latest 6-digit pairing code and port, toggle Wireless Debugging, or activate via Shizuku / PC ADB.",
            steps = listOf(
                "In Android Developer Options → Wireless Debugging → Pair device with pairing code, double-check the current 6-digit code and port (the port changes each time the dialog reopens).",
                "Keep the pairing dialog visible (use split-screen or the notification reply field) while submitting the code.",
                "If pairing spins indefinitely, toggle Wireless Debugging off and back on and verify Wi-Fi stays connected.",
                "Alternatively, use Shizuku one-tap activation or run the PC ADB activation command.",
            ),
            keywords = listOf(
                "activation", "failed", "wireless debugging", "pairing", "stuck", "code", "port", "shizuku", "adb",
            ),
        ),
        OpalFaqEntry(
            id = ID_APP_ASPECT_DPI,
            category = OpalFaqCategory.DISPLAY_ACTIVATION,
            question = "App aspect ratio issues, black bars, or tiny fonts",
            summary = "Adjust Per-App Custom DPI, switch Desktop / Tablet / Portrait window presets, or enable 1:1 native pixel output.",
            steps = listOf(
                "Open Console → Display → Per-App Custom DPI (or tap the DeX top-bar DPI control / long-press an app icon) to tune density for that specific app.",
                "Switch between Desktop, Tablet, and Portrait window presets to match mobile-first apps.",
                "To fix side black bars on 4:3 or portable monitors, enable \"1:1 Native Pixel Output\" under Display → Custom Resolution & Refresh Rate.",
                "If a TV crops the top bar or dock, adjust TV Safe Margin (Overscan) from 0% to 6%.",
            ),
            keywords = listOf(
                "aspect", "ratio", "black bars", "font", "small", "dpi", "resolution", "portrait", "tablet", "overscan",
            ),
        ),
        OpalFaqEntry(
            id = ID_SCREEN_OFF_DISCONNECT,
            category = OpalFaqCategory.CAST_CONNECTION,
            question = "Black screen on host screen-off or background disconnects",
            summary = "Set unrestricted battery usage, lock OpalDesk in Recents, and prefer Dim Trackpad for wireless TV casting.",
            steps = listOf(
                "In Android Settings → Apps → OpalDesk → Battery, select \"Unrestricted\" to prevent OS background throttling.",
                "Lock OpalDesk in the system Recents switcher and add it to your ROM's autostart / memory cleanup allowlist.",
                "On wireless TV (Miracast) sessions, use \"Dim Pad\" in the phone trackpad toolbar instead of physical screen-off if your ROM drops video on sleep.",
                "Exiting DeX automatically wakes the host screen; during soft screen-off you can double-tap the screen, double-press Volume+, or press Volume+ and Volume- together to wake it.",
            ),
            keywords = listOf(
                "screen off", "black screen", "disconnect", "background", "battery", "unrestricted", "whitelist", "dim pad",
                "auto wake", "volume key", "wake",
            ),
        ),
        OpalFaqEntry(
            id = ID_AUDIO_ROUTING,
            category = OpalFaqCategory.CAST_CONNECTION,
            question = "No audio while casting or sound playing on the wrong device",
            summary = "Check the Audio Split Routing mode in Settings and verify media volume on both devices.",
            steps = listOf(
                "Open Console → Settings → Audio Split Routing & Remote Media and choose Split by App, Cast Audio, or Phone Only.",
                "Verify media volume is not muted on your phone or on the receiving PC / TV speakers.",
                "If an already playing app does not follow a route change immediately, pause and resume playback or reconnect cast.",
            ),
            keywords = listOf(
                "audio", "sound", "mute", "split", "routing", "speaker", "volume",
            ),
        ),
    )
}
