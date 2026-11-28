package io.github.lengsukq.opaldesk.settings

import androidx.compose.ui.graphics.Color
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import java.util.Locale

enum class DexSettingsCategory {
    APPEARANCE,
    DOCK,
    DISPLAY_CAST,
    AUDIO,
    INPUT_IME,
    WINDOWS,
    SYSTEM_DIAG,
}

enum class DexSettingsLayoutMode {
    COMPACT_PHONE,
    MEDIUM_TABLET,
    EXPANDED_PC,
}

/**
 * Pure responsive layout breakpoint classifier for DeX Settings:
 * - `< 520.dp`: single-pane phone flow (category list -> detail page)
 * - `520.dp..719.dp`: compact tablet dual-pane (188.dp sidebar)
 * - `>= 720.dp`: full PC / external monitor macOS-style dual-pane (228.dp sidebar)
 */
fun computeDexSettingsLayoutMode(widthDp: Float): DexSettingsLayoutMode = when {
    widthDp < 520f -> DexSettingsLayoutMode.COMPACT_PHONE
    widthDp < 720f -> DexSettingsLayoutMode.MEDIUM_TABLET
    else -> DexSettingsLayoutMode.EXPANDED_PC
}

data class DexSettingsCategoryMeta(
    val category: DexSettingsCategory,
    val titleZh: String,
    val titleEn: String,
    val subtitleZh: String,
    val subtitleEn: String,
    val keywords: List<String>,
    val badgeColor: Color,
    val glyph: ChromeGlyph,
) {
    fun title(isZh: Boolean): String = if (isZh) titleZh else titleEn
    fun subtitle(isZh: Boolean): String = if (isZh) subtitleZh else subtitleEn
}

object DexSettingsCatalog {
    val allMeta: List<DexSettingsCategoryMeta> = listOf(
        DexSettingsCategoryMeta(
            category = DexSettingsCategory.APPEARANCE,
            titleZh = "外观与桌面",
            titleEn = "Appearance & Desktop",
            subtitleZh = "主题、壁纸、桌面插槽、组件与语言",
            subtitleEn = "Theme, wallpaper, desktop slots, widgets & language",
            keywords = listOf(
                "外观", "主题", "浅色", "深色", "暗色", "壁纸", "插槽", "方案", "组件", "商店", "语言", "中文", "英文",
                "appearance", "theme", "light", "dark", "wallpaper", "slot", "profile", "widget", "language", "locale",
            ),
            badgeColor = Color(0xFF0A84FF),
            glyph = ChromeGlyph.HOME_DESKTOP,
        ),
        DexSettingsCategoryMeta(
            category = DexSettingsCategory.DOCK,
            titleZh = "Dock 底栏自定义",
            titleEn = "Dock Customization",
            subtitleZh = "尺寸、悬浮样式、玻璃质感、小白点悬浮球",
            subtitleEn = "Size, floating bar, glass style & floating orb mode",
            keywords = listOf(
                "dock", "底栏", "任务栏", "尺寸", "紧凑", "标准", "大号", "悬浮", "贴边", "自动隐藏", "搜索", "设置",
                "玻璃", "磨砂", "通透", "指示点", "小白点", "悬浮球", "吸附", "半透明",
                "taskbar", "size", "floating", "autohide", "glass", "frosted", "orb", "snap", "hover", "dim",
            ),
            badgeColor = Color(0xFF5856D6),
            glyph = ChromeGlyph.APPS_GRID,
        ),
        DexSettingsCategoryMeta(
            category = DexSettingsCategory.DISPLAY_CAST,
            titleZh = "显示与投屏",
            titleEn = "Display & Casting",
            subtitleZh = "清晰度、DPI、帧率、无线投屏与自动息屏",
            subtitleEn = "Clarity, DPI, FPS, wireless cast & auto screen-off",
            keywords = listOf(
                "显示", "分辨率", "清晰度", "dpi", "缩放", "帧率", "fps", "投屏", "无线", "码率", "息屏", "黑屏", "触控板", "外接",
                "display", "resolution", "scale", "refresh", "cast", "wifi", "bitrate", "blackout", "trackpad", "external",
            ),
            badgeColor = Color(0xFF0284C7),
            glyph = ChromeGlyph.EXTERNAL_MONITOR,
        ),
        DexSettingsCategoryMeta(
            category = DexSettingsCategory.AUDIO,
            titleZh = "音频与媒体",
            titleEn = "Audio & Media",
            subtitleZh = "本机播放、投屏分流静音与媒体控制权限",
            subtitleEn = "Phone speaker, cast audio routing & media permissions",
            keywords = listOf(
                "音频", "声音", "扬声器", "本机播放", "便携屏", "静音", "分流", "麦克风", "媒体", "通知使用权",
                "audio", "sound", "speaker", "mute", "route", "split", "mic", "media", "notification",
            ),
            badgeColor = Color(0xFFFF375F),
            glyph = ChromeGlyph.MIC,
        ),
        DexSettingsCategoryMeta(
            category = DexSettingsCategory.INPUT_IME,
            titleZh = "键鼠与输入法",
            titleEn = "Input & Keyboard",
            subtitleZh = "DeX 输入法、语燕协同、键鼠映射与灵敏度",
            subtitleEn = "DeX IME, Yuyan synergy, key bindings & pointer speed",
            keywords = listOf(
                "输入法", "键盘", "语燕", "锁定", "键鼠", "映射", "鼠标", "触控板", "灵敏度", "快捷键",
                "ime", "keyboard", "yuyan", "lock", "binding", "mouse", "pointer", "trackpad", "sensitivity", "shortcut",
            ),
            badgeColor = Color(0xFFFF9F0A),
            glyph = ChromeGlyph.KEYBOARD,
        ),
        DexSettingsCategoryMeta(
            category = DexSettingsCategory.WINDOWS,
            titleZh = "窗口与多任务",
            titleEn = "Windows & Multitasking",
            subtitleZh = "布局记忆、虚拟屏配额、输入后端与分屏辅助",
            subtitleEn = "Layout restore, VD quota, input backend & multitasking",
            keywords = listOf(
                "窗口", "多任务", "分屏", "缩放", "布局", "恢复", "虚拟屏", "配额", "uhid", "应用列表", "按钮", "互换", "红绿灯", "标题栏",
                "window", "multitask", "snap", "layout", "restore", "quota", "vd", "backend", "drawer", "swap", "header", "traffic",
            ),
            badgeColor = Color(0xFF30D158),
            glyph = ChromeGlyph.EXPOSE,
        ),
        DexSettingsCategoryMeta(
            category = DexSettingsCategory.SYSTEM_DIAG,
            titleZh = "主机引擎与诊断",
            titleEn = "Engine & Diagnostics",
            subtitleZh = "Shizuku/守护进程状态、日志上传、FAQ 与版本",
            subtitleEn = "Shizuku/daemon status, log upload, FAQ & version",
            keywords = listOf(
                "引擎", "守护进程", "shizuku", "root", "诊断", "日志", "上传", "faq", "帮助", "版本", "关于",
                "engine", "daemon", "diagnostics", "logs", "upload", "help", "version", "about",
            ),
            badgeColor = Color(0xFF64748B),
            glyph = ChromeGlyph.CONTROL_CENTER,
        ),
    )

    private val byCategory: Map<DexSettingsCategory, DexSettingsCategoryMeta> =
        allMeta.associateBy { it.category }

    fun metaOf(category: DexSettingsCategory): DexSettingsCategoryMeta =
        byCategory[category] ?: allMeta.first()

    fun filterCategories(query: String): List<DexSettingsCategory> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return DexSettingsCategory.entries
        return allMeta.filter { meta ->
            meta.titleZh.lowercase(Locale.ROOT).contains(needle) ||
                meta.titleEn.lowercase(Locale.ROOT).contains(needle) ||
                meta.subtitleZh.lowercase(Locale.ROOT).contains(needle) ||
                meta.subtitleEn.lowercase(Locale.ROOT).contains(needle) ||
                meta.keywords.any { it.lowercase(Locale.ROOT).contains(needle) }
        }.map { it.category }
    }
}

fun DexSettingsCategory.meta(): DexSettingsCategoryMeta = DexSettingsCatalog.metaOf(this)

fun DexSettingsCategory.displayTitle(isZh: Boolean): String = meta().title(isZh)

fun DexSettingsCategory.displaySubtitle(isZh: Boolean): String = meta().subtitle(isZh)

fun filterDexSettingsCategories(query: String): List<DexSettingsCategory> =
    DexSettingsCatalog.filterCategories(query)

internal fun isDexSettingsZh(languagePref: String): Boolean {
    val effective = OpalLocalePolicy.resolveEffectiveLanguageTag(
        pref = languagePref,
        systemLanguageTags = OpalLocalePolicy.readSystemLanguageTags(),
    )
    return effective == OpalLocalePolicy.LANG_ZH_CN
}
