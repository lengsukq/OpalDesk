package io.github.lengsukq.opaldesk.guide

import android.content.Context
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.update.AppUpdateLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class OpalGuideAnnouncement(
    val badge: String,
    val title: String,
    val body: String,
)

data class OpalGuideItem(
    val badge: String,
    val accent: String,
    val title: String,
    val body: String,
    val action: String = "",
    val actionLabel: String = "",
)

data class OpalGuideSection(
    val id: String,
    val title: String,
    val items: List<OpalGuideItem>,
)

data class OpalGuideLink(
    val label: String,
    val url: String,
    val action: String,
)

data class OpalGuideDocument(
    val schemaVersion: Int,
    val updatedAt: String,
    val headline: String,
    val subtitle: String,
    val announcement: OpalGuideAnnouncement?,
    val sections: List<OpalGuideSection>,
    val links: List<OpalGuideLink>,
)

data class OpalGuideUiState(
    val document: OpalGuideDocument = OpalGuidePolicy.DEFAULT_GUIDE,
    val fromRemote: Boolean = false,
    val refreshing: Boolean = false,
    val lastError: String = "",
)

/**
 * Pure-JVM testable parser, host allowlist, multi-route fetch strategy, and built-in offline
 * fallback for OpalDesk's hot-updateable in-desktop Feature Guide (`site/guide.json`).
 */
object OpalGuidePolicy {
    const val GITHUB_REPO_URL = "https://github.com/lengsukq/OpalDesk"
    const val GITHUB_PAGES_HOME_URL = "https://lengsukq.github.io/OpalDesk/"
    const val GITHUB_PAGES_GUIDE_URL = "https://lengsukq.github.io/OpalDesk/guide.json"
    const val RAW_GITHUB_GUIDE_URL =
        "https://raw.githubusercontent.com/lengsukq/OpalDesk/main/site/guide.json"

    const val ACTION_OPEN_SHORTCUTS = "open_shortcuts"
    const val ACTION_OPEN_DPI = "open_dpi"
    const val ACTION_OPEN_TILING = "open_tiling"
    const val ACTION_OPEN_DOCK_SETTINGS = "open_dock_settings"
    const val ACTION_OPEN_CONTROL_CENTER = "open_control_center"
    const val ACTION_OPEN_CONSOLE = "open_console"
    const val ACTION_JOIN_QQ = "join_qq"
    const val ACTION_OPEN_URL = "open_url"

    private const val MAX_SECTIONS = 6
    private const val MAX_ITEMS_PER_SECTION = 16
    private const val MAX_LINKS = 6

    private val ALLOWED_FETCH_HOSTS = setOf(
        "lengsukq.github.io",
        "raw.githubusercontent.com",
        "gh-proxy.com",
        "ghfast.top",
    )

    private val ALLOWED_EXTERNAL_HOSTS = setOf(
        "github.com",
        "lengsukq.github.io",
    )

    val DEFAULT_GUIDE: OpalGuideDocument = OpalGuideDocument(
        schemaVersion = 1,
        updatedAt = "2026-11-26",
        headline = "OpalDesk 使用指南",
        subtitle = "免 Root · 120fps 多窗合成工作站 · 云端热更新文档",
        announcement = OpalGuideAnnouncement(
            badge = "v1.0.17 亮点",
            title = "物理键盘中文候选栏、快捷键面板与外接屏直投升级",
            body = "接入蓝牙或 USB 键盘后自动在当前应用窗口内显示中文输入法候选栏；新增快捷键速查与按项关闭开关，并强化 Android 16 外接显示器直投稳定性。",
        ),
        sections = listOf(
            OpalGuideSection(
                id = "features",
                title = "核心特性",
                items = listOf(
                    OpalGuideItem(
                        badge = "120fps",
                        accent = "blue",
                        title = "真多窗桌面与一键分屏",
                        body = "支持自由拖拽缩放、边缘八向贴边、2~8 窗一键分屏排版与 5 套持久化桌面配置槽位。",
                        action = ACTION_OPEN_TILING,
                        actionLabel = "分屏布局",
                    ),
                    OpalGuideItem(
                        badge = "实时预览",
                        accent = "cyan",
                        title = "全局与单应用独立 DPI",
                        body = "在 DeX 桌面内直接拖动滑块实时调节全局或单个应用的显示密度，松手即生效且随槽位保存。",
                        action = ACTION_OPEN_DPI,
                        actionLabel = "调节 DPI",
                    ),
                    OpalGuideItem(
                        badge = "键鼠优化",
                        accent = "emerald",
                        title = "物理键盘输入法与快捷键",
                        body = "连接物理键盘时直接显示系统中文候选栏；所有桌面快捷键可随时查看、总开关关闭或单项禁用。",
                        action = ACTION_OPEN_SHORTCUTS,
                        actionLabel = "查看快捷键",
                    ),
                    OpalGuideItem(
                        badge = "外接大屏",
                        accent = "amber",
                        title = "有线显示器 & 无线电视投屏",
                        body = "支持 USB-C / HDMI 外接屏高刷输出与 Miracast 无线电视独立投射，搭配手机精准触控板与一键息屏省电。",
                        action = ACTION_OPEN_CONTROL_CENTER,
                        actionLabel = "控制中心",
                    ),
                    OpalGuideItem(
                        badge = "跨端协同",
                        accent = "blue",
                        title = "Mac / Windows / Web 投屏与音频分流",
                        body = "支持局域网与 USB 跨端投屏桌面或独立应用小窗，默认开启手机与 DeX 按应用独立音频分流。",
                        action = ACTION_OPEN_CONSOLE,
                        actionLabel = "打开控制台",
                    ),
                    OpalGuideItem(
                        badge = "个性化",
                        accent = "cyan",
                        title = "Dock 自定义与下拉快捷磁贴",
                        body = "支持隐藏底部 Dock、调节透明度与缩放比例；系统下拉控制中心支持添加「进入 DeX / 息屏 / 无线投屏」磁贴。",
                        action = ACTION_OPEN_DOCK_SETTINGS,
                        actionLabel = "自定义 Dock",
                    ),
                ),
            ),
            OpalGuideSection(
                id = "tips",
                title = "实用技巧与排障",
                items = listOf(
                    OpalGuideItem(
                        badge = "输入法",
                        accent = "emerald",
                        title = "物理键盘中英文切换与冲突处理",
                        body = "物理键盘模式下可直接用 Shift+Space、Ctrl+Space 或地球键切换中英文；若 Ctrl+W / Alt+Tab / Esc 与浏览器、终端或游戏冲突，可在快捷键面板中单独关闭。",
                        action = ACTION_OPEN_SHORTCUTS,
                        actionLabel = "快捷键设置",
                    ),
                    OpalGuideItem(
                        badge = "外接屏",
                        accent = "blue",
                        title = "外接显示器分辨率与直投提示",
                        body = "有线 USB-C / HDMI 建议保持「自动（推荐）」模式以优先 Shell 直投原生分辨率；电视画面被裁切时可在手机触控板或设置中调节 0–6% 过扫描边距。",
                        action = ACTION_OPEN_CONSOLE,
                        actionLabel = "显示设置",
                    ),
                    OpalGuideItem(
                        badge = "触控注入",
                        accent = "amber",
                        title = "如遇个别机型点击无响应",
                        body = "部分 ROM 限制系统输入注入时，可在主控制台「设置 → 引擎配置」中将输入后端切换为「/dev/uhid 内核通道」，立即恢复多点触控与键鼠响应。",
                        action = ACTION_OPEN_CONSOLE,
                        actionLabel = "引擎设置",
                    ),
                    OpalGuideItem(
                        badge = "问题反馈",
                        accent = "cyan",
                        title = "一键上报诊断日志与交流群",
                        body = "遇到偶发闪退或外接屏适配问题，可在主控制台「日志」页一键上报脱敏日志（不截屏），或加入 QQ 群 1042317917 获取最新版与实时反馈。",
                        action = ACTION_JOIN_QQ,
                        actionLabel = "加入 QQ 群",
                    ),
                ),
            ),
        ),
        links = listOf(
            OpalGuideLink("GitHub 仓库", GITHUB_REPO_URL, ACTION_OPEN_URL),
            OpalGuideLink("项目官网", GITHUB_PAGES_HOME_URL, ACTION_OPEN_URL),
            OpalGuideLink("加入 QQ 群", "", ACTION_JOIN_QQ),
        ),
    )

    fun buildFetchUrls(preferredRoute: AppUpdateLogic.DownloadRoute): List<String> {
        val proxy1 = AppUpdateLogic.DownloadRoute.PROXY_1.proxyPrefix + RAW_GITHUB_GUIDE_URL
        val proxy2 = AppUpdateLogic.DownloadRoute.PROXY_2.proxyPrefix + RAW_GITHUB_GUIDE_URL
        return when (preferredRoute) {
            AppUpdateLogic.DownloadRoute.PROXY_1 ->
                listOf(proxy1, proxy2, GITHUB_PAGES_GUIDE_URL, RAW_GITHUB_GUIDE_URL)
            AppUpdateLogic.DownloadRoute.PROXY_2 ->
                listOf(proxy2, proxy1, GITHUB_PAGES_GUIDE_URL, RAW_GITHUB_GUIDE_URL)
            AppUpdateLogic.DownloadRoute.DIRECT ->
                listOf(GITHUB_PAGES_GUIDE_URL, RAW_GITHUB_GUIDE_URL, proxy1, proxy2)
        }
    }

    fun isAllowedFetchUrl(url: String): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host in ALLOWED_FETCH_HOSTS
    }

    fun isAllowedExternalLinkUrl(url: String): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host in ALLOWED_EXTERNAL_HOSTS
    }

    fun formatSourceLabel(fromRemote: Boolean, updatedAt: String, refreshing: Boolean): String = when {
        refreshing -> "同步云端…"
        fromRemote && updatedAt.isNotBlank() -> "云端最新 · $updatedAt"
        fromRemote -> "云端已同步"
        else -> "离线内置版"
    }

    fun parseGuideJson(rawJson: String): OpalGuideDocument? {
        if (rawJson.isBlank() || rawJson.length > 128_000) return null
        val root = try {
            AppUpdateLogic.JsonLite.parse(rawJson) as? Map<*, *>
        } catch (_: Throwable) {
            null
        } ?: return null

        val schemaVersion = (root["schemaVersion"] as? Number)?.toInt() ?: 1
        val headline = cleanText(root["headline"], 64).ifEmpty { DEFAULT_GUIDE.headline }
        val subtitle = cleanText(root["subtitle"], 120).ifEmpty { DEFAULT_GUIDE.subtitle }
        val updatedAt = cleanText(root["updatedAt"], 32)

        val annMap = root["announcement"] as? Map<*, *>
        val announcement = if (annMap != null) {
            val aTitle = cleanText(annMap["title"], 96)
            val aBody = cleanText(annMap["body"], 400)
            if (aTitle.isNotEmpty() || aBody.isNotEmpty()) {
                OpalGuideAnnouncement(
                    badge = cleanText(annMap["badge"], 28).ifEmpty { "公告" },
                    title = aTitle,
                    body = aBody,
                )
            } else {
                null
            }
        } else {
            null
        }

        val rawSections = root["sections"] as? List<*> ?: return null
        val sections = ArrayList<OpalGuideSection>()
        for (secAny in rawSections) {
            if (sections.size >= MAX_SECTIONS) break
            val secMap = secAny as? Map<*, *> ?: continue
            val secTitle = cleanText(secMap["title"], 32)
            val rawItems = secMap["items"] as? List<*> ?: continue
            val items = ArrayList<OpalGuideItem>()
            for (itemAny in rawItems) {
                if (items.size >= MAX_ITEMS_PER_SECTION) break
                val itemMap = itemAny as? Map<*, *> ?: continue
                val iTitle = cleanText(itemMap["title"], 64)
                val iBody = cleanText(itemMap["body"], 360)
                if (iTitle.isEmpty() || iBody.isEmpty()) continue
                items.add(
                    OpalGuideItem(
                        badge = cleanText(itemMap["badge"], 24),
                        accent = cleanText(itemMap["accent"], 16).lowercase(Locale.US),
                        title = iTitle,
                        body = iBody,
                        action = cleanText(itemMap["action"], 32),
                        actionLabel = cleanText(itemMap["actionLabel"], 24),
                    ),
                )
            }
            if (secTitle.isNotEmpty() && items.isNotEmpty()) {
                val secId = cleanText(secMap["id"], 32).ifEmpty { "sec_${sections.size}" }
                sections.add(OpalGuideSection(id = secId, title = secTitle, items = items))
            }
        }
        if (sections.isEmpty()) return null

        val rawLinks = root["links"] as? List<*>
        val links = ArrayList<OpalGuideLink>()
        if (rawLinks != null) {
            for (linkAny in rawLinks) {
                if (links.size >= MAX_LINKS) break
                val lm = linkAny as? Map<*, *> ?: continue
                val label = cleanText(lm["label"], 28)
                val action = cleanText(lm["action"], 32).ifEmpty { ACTION_OPEN_URL }
                val url = cleanText(lm["url"], 240)
                if (label.isEmpty()) continue
                if (action == ACTION_OPEN_URL && !isAllowedExternalLinkUrl(url)) continue
                links.add(OpalGuideLink(label = label, url = url, action = action))
            }
        }

        return OpalGuideDocument(
            schemaVersion = schemaVersion,
            updatedAt = updatedAt,
            headline = headline,
            subtitle = subtitle,
            announcement = announcement,
            sections = sections,
            links = links.ifEmpty { DEFAULT_GUIDE.links },
        )
    }

    private fun parseHttpsUri(raw: String): URI? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        return if ("https".equals(uri.scheme, ignoreCase = true)) uri else null
    }

    private fun cleanText(raw: Any?, maxLen: Int): String =
        (raw as? String)?.trim()?.take(maxLen).orEmpty()
}

/**
 * Runtime hot-update store for OpalDesk's built-in Feature Guide.
 * Immediately renders cached/bundled content (0ms) and refreshes asynchronously in the background.
 */
object OpalGuideStore {
    private const val CACHE_FILENAME = "opaldesk_guide_cache_v1.json"
    private const val REFRESH_TTL_MS = 30L * 60L * 1000L
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 7_000

    private val ioExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalGuideFetch").apply { isDaemon = true }
    }
    private val cacheHydrated = AtomicBoolean(false)
    private val refreshInFlight = AtomicBoolean(false)

    @Volatile
    private var lastSuccessFetchMs = 0L

    private val _stateFlow = MutableStateFlow(OpalGuideUiState())
    val stateFlow: StateFlow<OpalGuideUiState> = _stateFlow.asStateFlow()

    fun hydrateCache(ctx: Context?) {
        val appCtx = ctx?.applicationContext ?: return
        if (!cacheHydrated.compareAndSet(false, true)) return
        ioExec.execute {
            val file = File(appCtx.filesDir, CACHE_FILENAME)
            if (!file.isFile) return@execute
            val parsed = runCatching {
                val raw = file.readText(StandardCharsets.UTF_8)
                OpalGuidePolicy.parseGuideJson(raw)
            }.getOrNull() ?: return@execute
            val cur = _stateFlow.value
            if (!cur.fromRemote) {
                _stateFlow.value = cur.copy(document = parsed, fromRemote = true)
            }
        }
    }

    fun refreshIfStale(ctx: Context?, force: Boolean = false) {
        val appCtx = ctx?.applicationContext ?: return
        hydrateCache(appCtx)
        val now = System.currentTimeMillis()
        if (!force && lastSuccessFetchMs > 0L && (now - lastSuccessFetchMs) < REFRESH_TTL_MS) {
            return
        }
        if (!refreshInFlight.compareAndSet(false, true)) return
        _stateFlow.value = _stateFlow.value.copy(refreshing = true, lastError = "")

        ioExec.execute {
            try {
                val route = AppUpdateLogic.DownloadRoute.fromIndex(AppPrefs.getUpdateDownloadRoute(appCtx))
                val candidates = OpalGuidePolicy.buildFetchUrls(route)
                var fetchedRaw: String? = null
                var fetchedDoc: OpalGuideDocument? = null
                var lastErr = ""

                for (urlStr in candidates) {
                    if (!OpalGuidePolicy.isAllowedFetchUrl(urlStr)) continue
                    val attempt = fetchUrlText(urlStr)
                    if (attempt.isSuccess) {
                        val raw = attempt.getOrNull().orEmpty()
                        val parsed = OpalGuidePolicy.parseGuideJson(raw)
                        if (parsed != null) {
                            fetchedRaw = raw
                            fetchedDoc = parsed
                            break
                        } else {
                            lastErr = "指南格式解析失败"
                        }
                    } else {
                        lastErr = attempt.exceptionOrNull()?.message ?: "网络连接超时"
                    }
                }

                if (fetchedDoc != null && fetchedRaw != null) {
                    lastSuccessFetchMs = System.currentTimeMillis()
                    runCatching {
                        val tmp = File(appCtx.filesDir, "$CACHE_FILENAME.tmp")
                        val dst = File(appCtx.filesDir, CACHE_FILENAME)
                        tmp.writeText(fetchedRaw, StandardCharsets.UTF_8)
                        if (!tmp.renameTo(dst)) {
                            dst.writeText(fetchedRaw, StandardCharsets.UTF_8)
                            tmp.delete()
                        }
                    }
                    _stateFlow.value = OpalGuideUiState(
                        document = fetchedDoc,
                        fromRemote = true,
                        refreshing = false,
                        lastError = "",
                    )
                    OpalLogStore.i("Guide", "Synced remote guide (updatedAt=${fetchedDoc.updatedAt})")
                } else {
                    _stateFlow.value = _stateFlow.value.copy(
                        refreshing = false,
                        lastError = if (force) "暂未连上云端指南（当前展示离线版）" else "",
                    )
                    if (force && lastErr.isNotBlank()) {
                        OpalLogStore.w("Guide", "Remote guide refresh failed: $lastErr")
                    }
                }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private fun fetchUrlText(urlStr: String): Result<String> = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpalDesk-Android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            if (!OpalGuidePolicy.isAllowedFetchUrl(conn.url.toString())) {
                throw SecurityException("Disallowed redirect host")
            }
            val maxBytes = 128_000
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            conn.inputStream.use { input ->
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        throw IllegalStateException("Guide payload too large")
                    }
                    out.write(buf, 0, read)
                }
            }
            out.toString(StandardCharsets.UTF_8.name())
        } finally {
            conn.disconnect()
        }
    }
}
