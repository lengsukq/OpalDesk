package io.github.lengsukq.opaldesk.external

import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Active hosting mode of [io.github.lengsukq.opaldesk.ExternalDesktopActivity].
 */
enum class ExternalDesktopMode {
    NONE,
    PRESENTATION,
    DIRECT_EXTENDED,
}

/**
 * Physical/wireless transport classification of an external display detected by Android DisplayManager.
 */
enum class ExternalDisplayTransport {
    WIRED,
    WIRELESS_TV,
    OTHER_EXTERNAL,
}

/**
 * Unified trackpad input routing target for phone Display 0.
 */
enum class TrackpadTargetMode {
    NONE,
    REMOTE_CAST,
    PHYSICAL_DIRECT_EXTENDED,
    PHYSICAL_PRESENTATION,
}

/**
 * Immutable reactive snapshot of the live external monitor desktop session (SSOT).
 */
data class ExternalDesktopSession(
    val mode: ExternalDesktopMode = ExternalDesktopMode.NONE,
    val displayId: Int = -1,
    val displayName: String = "",
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val refreshRateHz: Int = 60,
    val isRemoteCastDisplay: Boolean = false,
    val isWirelessDisplay: Boolean = false,
) {
    val isActive: Boolean
        get() = mode != ExternalDesktopMode.NONE && displayId > 0

    val isPresentation: Boolean
        get() = mode == ExternalDesktopMode.PRESENTATION && displayId > 0

    val isDirectExtended: Boolean
        get() = mode == ExternalDesktopMode.DIRECT_EXTENDED && displayId > 0

    val isPhysicalDirectExtended: Boolean
        get() = isDirectExtended && !isRemoteCastDisplay

    val isPhysicalActive: Boolean
        get() = isPresentation || isPhysicalDirectExtended

    val summary: String
        get() {
            if (!isActive || widthPx <= 0 || heightPx <= 0) return ""
            val label = displayName.ifBlank { if (isWirelessDisplay) "无线电视" else "外接屏" }
            val hz = refreshRateHz.coerceAtLeast(24)
            return "$label · ${widthPx}×${heightPx} @ ${hz}Hz"
        }
}

/**
 * Pure JVM-testable display filtering and trackpad target resolution policies shared across
 * [io.github.lengsukq.opaldesk.BridgeService], [io.github.lengsukq.opaldesk.ExternalDesktopActivity],
 * [io.github.lengsukq.opaldesk.MainActivity], and [RemoteTrackpadActivity].
 */
object ExternalDisplayPolicy {
    // Public android.view.Display constants mirrored for zero-Android-runtime JVM testing
    const val DEFAULT_DISPLAY_ID = 0
    const val FLAG_PRIVATE = 1 shl 2
    const val FLAG_PRESENTATION = 1 shl 3
    const val STATE_OFF = 1

    // Standard AOSP android.view.Display.TYPE_* integer values
    const val TYPE_UNKNOWN = 0
    const val TYPE_INTERNAL = 1
    const val TYPE_EXTERNAL = 2
    const val TYPE_WIFI = 3
    const val TYPE_OVERLAY = 4
    const val TYPE_VIRTUAL = 5

    // External projection launch strategy
    const val PROJECTION_MODE_AUTO = 0
    const val PROJECTION_MODE_PRESENTATION = 1

    // Supports 24/25/30Hz 4K Miracast/HDMI TVs as well as 60-120Hz monitors
    const val MIN_EXTERNAL_MODE_FPS = 23.5f
    const val MIN_OVERSCAN_PCT = 0f
    const val MAX_OVERSCAN_PCT = 6f
    const val ANISOTROPY_DPI_RATIO_THRESHOLD = 1.05f
    private const val MIRROR_COLLAPSE_SIZE_TOLERANCE_PX = 4
    private const val MIRROR_COLLAPSE_ASPECT_DIFF_THRESHOLD = 0.20f

    private val WIRELESS_DISPLAY_KEYWORDS = listOf(
        "wifi", "wi-fi", "wfd", "miracast", "wireless", "smartview", "smart view",
        "cast", "screen share", "screenshare", "chromecast", "airplay", "dlna", "lebo",
        "bravia", "webos", "tizen", "tv", "电视", "客厅", "卧室", "投影", "无线",
    )

    fun isInternalOpalDisplayName(name: String?): Boolean {
        val clean = name.orEmpty()
        return clean.startsWith("OpalWin-") || clean.startsWith("OpalDesk-")
    }

    fun looksLikeWirelessDisplayName(name: String?): Boolean {
        val clean = name.orEmpty().trim()
        if (clean.isEmpty()) return false
        val lower = clean.lowercase(Locale.ROOT)
        return WIRELESS_DISPLAY_KEYWORDS.any { keyword ->
            when (keyword) {
                "tv" -> Regex("(^|[^a-z0-9])tv($|[^a-z0-9])").containsMatchIn(lower)
                else -> lower.contains(keyword)
            }
        }
    }

    /**
     * Classifies a DisplayManager entry into [ExternalDisplayTransport.WIRED],
     * [ExternalDisplayTransport.WIRELESS_TV], [ExternalDisplayTransport.OTHER_EXTERNAL],
     * or `null` if it must be ignored (Display 0, invalid/off, Opal internal, or private).
     */
    fun classifyExternalDisplay(
        displayId: Int,
        isValid: Boolean,
        name: String?,
        flags: Int,
        state: Int = 2,
        displayType: Int = TYPE_UNKNOWN,
        isPresentationCategory: Boolean = false,
        isMediaRouterPresentation: Boolean = false,
        activeRemoteDesktopDisplayId: Int = -1,
    ): ExternalDisplayTransport? {
        if (displayId <= DEFAULT_DISPLAY_ID || !isValid) return null
        if (state == STATE_OFF) return null
        if (isInternalOpalDisplayName(name)) return null
        if (activeRemoteDesktopDisplayId > DEFAULT_DISPLAY_ID && displayId == activeRemoteDesktopDisplayId) return null
        if ((flags and FLAG_PRIVATE) != 0) return null
        if (displayType == TYPE_INTERNAL || displayType == TYPE_OVERLAY) return null

        val presentationCapable = (flags and FLAG_PRESENTATION) != 0 ||
            isPresentationCategory ||
            isMediaRouterPresentation

        val isWireless = displayType == TYPE_WIFI ||
            (presentationCapable && displayType == TYPE_VIRTUAL) ||
            (isMediaRouterPresentation && displayType != TYPE_EXTERNAL) ||
            (presentationCapable && displayType == TYPE_UNKNOWN && looksLikeWirelessDisplayName(name))

        if (isWireless) {
            return ExternalDisplayTransport.WIRELESS_TV
        }
        if (presentationCapable) {
            return ExternalDisplayTransport.WIRED
        }
        // Non-presentation secondary display (only surfaced for manual override if not a generic virtual display)
        return if (displayType != TYPE_VIRTUAL) ExternalDisplayTransport.OTHER_EXTERNAL else null
    }

    fun isAutoProjectable(transport: ExternalDisplayTransport?): Boolean =
        transport == ExternalDisplayTransport.WIRED || transport == ExternalDisplayTransport.WIRELESS_TV

    /**
     * Mirrors AOSP `Display.isPublicPresentation()`: `WindowManagerService.addWindow()` rejects
     * `TYPE_PRESENTATION` with `ADD_INVALID_DISPLAY` (`InvalidDisplayException`) unless
     * `FLAG_PRESENTATION` is set and `FLAG_PRIVATE` is clear.
     */
    fun isPublicPresentationDisplay(flags: Int): Boolean =
        (flags and (FLAG_PRIVATE or FLAG_PRESENTATION)) == FLAG_PRESENTATION

    /**
     * Determines whether a manual "投射" action should force Presentation mode.
     * Never forces Presentation onto displays lacking `FLAG_PRESENTATION`, which would guarantee
     * `InvalidDisplayException` in `WindowManagerService.addWindow()`.
     */
    fun shouldForcePresentationForManualLaunch(
        transport: ExternalDisplayTransport,
        isPresentationCapable: Boolean,
    ): Boolean = transport == ExternalDisplayTransport.WIRELESS_TV && isPresentationCapable

    /**
     * Determines whether [io.github.lengsukq.opaldesk.ExternalDesktopActivity] should open on
     * Display 0 and host a full-bleed [android.app.Presentation] on the target display rather
     * than attempting direct extended Activity launch on [displayId].
     *
     * Wireless Miracast / Wi-Fi Displays strongly prefer standard AOSP [android.app.Presentation]
     * when `FLAG_PRESENTATION` is available; displays lacking `FLAG_PRESENTATION` must always use
     * direct extended launch because WMS rejects `TYPE_PRESENTATION` with `InvalidDisplayException`.
     */
    fun shouldPreferPresentationMode(
        transport: ExternalDisplayTransport,
        projectionModePref: Int,
        isPresentationCapable: Boolean = true,
    ): Boolean {
        if (!isPresentationCapable) return false
        if (projectionModePref == PROJECTION_MODE_PRESENTATION) return true
        return transport == ExternalDisplayTransport.WIRELESS_TV
    }

    /**
     * When the privileged shell daemon is denied direct Activity launch onto [displayId] (for
     * example, OEM smart-glasses or mirroring-only secondary displays rejecting `launchDisplayId`),
     * Presentation-capable displays should fall back directly to Display 0 so
     * [io.github.lengsukq.opaldesk.ExternalDesktopActivity] immediately opens
     * [ExternalMonitorPresentation] instead of retrying an unprivileged cross-display launch.
     */
    fun shouldFallbackToDisplay0AfterShellFailure(isPresentationCapable: Boolean): Boolean =
        isPresentationCapable

    data class DisplayModeCandidate(
        val modeId: Int,
        val width: Int,
        val height: Int,
        val refreshRate: Float,
    )

    /**
     * Picks the optimal hardware display mode for an external monitor:
     * - When [preferHighestResolution] is true, prefers the highest-pixel-area mode that still
     *   delivers >= 59.5Hz (falling back to highest refresh rate if all high-res modes are 30Hz).
     * - Otherwise locks to [currentWidth] x [currentHeight] and picks its peak refresh rate
     *   (e.g., upgrading a 120Hz/144Hz USB-C monitor out of 60Hz default).
     */
    fun selectPreferredDisplayMode(
        modes: List<DisplayModeCandidate>,
        currentWidth: Int,
        currentHeight: Int,
        preferHighestResolution: Boolean = false,
    ): DisplayModeCandidate? {
        if (modes.isEmpty()) return null
        val valid = modes.filter { it.width >= 320 && it.height >= 240 && it.refreshRate >= MIN_EXTERNAL_MODE_FPS }
        if (valid.isEmpty()) return modes.first()

        if (preferHighestResolution) {
            val smooth = valid.filter { it.refreshRate >= 59.5f }
            val pool = smooth.ifEmpty { valid }
            return pool.maxWithOrNull(
                compareBy<DisplayModeCandidate> { it.width.toLong() * it.height.toLong() }
                    .thenBy { it.refreshRate },
            )
        }
        val sameRes = valid.filter { it.width == currentWidth && it.height == currentHeight }
        if (sameRes.isNotEmpty()) {
            return sameRes.maxByOrNull { it.refreshRate }
        }
        return null
    }

    /**
     * Picks the best hardware [DisplayModeCandidate] at [currentWidth] × [currentHeight] matching
     * the user's requested [targetFps] (`0` = auto peak refresh rate).
     *
     * <p>Never cross-switches to a different resolution when [modes] omits `(currentWidth, currentHeight)`
     * (for instance, when Android 15–17 pixel anisotropy correction exposes synthetic modes like
     * `2677×1536` while the active physical mode is `2048×1536`): returns `null` so the caller leaves
     * `preferredDisplayModeId` unset and preserves the display's active resolution.
     */
    fun selectDisplayModeForTargetFps(
        modes: List<DisplayModeCandidate>,
        currentWidth: Int,
        currentHeight: Int,
        targetFps: Int,
    ): DisplayModeCandidate? {
        if (modes.isEmpty()) return null
        val valid = modes.filter { it.width >= 320 && it.height >= 240 && it.refreshRate >= MIN_EXTERNAL_MODE_FPS }
        if (valid.isEmpty()) return null
        val sameRes = valid.filter { it.width == currentWidth && it.height == currentHeight }
        if (sameRes.isEmpty()) return null
        if (targetFps <= 0) {
            return sameRes.maxByOrNull { it.refreshRate }
        }
        val req = targetFps.toFloat()
        val exact = sameRes.filter { kotlin.math.abs(it.refreshRate - req) <= 2.5f }
        if (exact.isNotEmpty()) {
            return exact.minByOrNull { kotlin.math.abs(it.refreshRate - req) }
        }
        val atOrAbove = sameRes.filter { it.refreshRate >= req - 2.5f }
        if (atOrAbove.isNotEmpty()) {
            return atOrAbove.minByOrNull { it.refreshRate }
        }
        return sameRes.maxByOrNull { it.refreshRate }
    }

    /**
     * Formats an intuitive status subtitle explaining the effective display refresh rate vs target.
     */
    fun formatRefreshStatusHint(
        targetFps: Int,
        actualRefreshHz: Int,
        maxSupportedHz: Int,
        isRemoteCast: Boolean,
    ): String {
        val cleanActual = actualRefreshHz.coerceAtLeast(24)
        val cleanMax = maxSupportedHz.coerceAtLeast(cleanActual)
        return when {
            isRemoteCast && targetFps <= 0 ->
                "自动匹配投屏端 · 当前输出 ${cleanActual}Hz"
            isRemoteCast ->
                "目标 ${targetFps}Hz · 当前投屏输出 ${cleanActual}Hz"
            targetFps <= 0 ->
                "自动匹配屏幕最高刷新率 · 当前 ${cleanActual}Hz（上限 ${cleanMax}Hz）"
            targetFps > cleanMax + 2 ->
                "当前屏幕最高支持 ${cleanMax}Hz，已按上限 ${cleanActual}Hz 输出"
            kotlin.math.abs(cleanActual - targetFps) <= 2 ->
                "目标 ${targetFps}Hz 已生效 · 当前 ${cleanActual}Hz"
            else ->
                "已请求 ${targetFps}Hz · 当前面板 ${cleanActual}Hz（上限 ${cleanMax}Hz）"
        }
    }

    enum class Display0ExternalAction {
        USE_PRESENTATION,
        RELAUNCH_DIRECT_VIA_SHELL,
        FALLBACK_LOCAL_ONLY,
    }

    /**
     * Decides what [io.github.lengsukq.opaldesk.ExternalDesktopActivity] should do when it lands on
     * Display 0 (`currentDisplayId == 0`) while targeting a physical/wireless external display.
     */
    fun resolveDisplay0ExternalAction(
        hasTargetDisplay: Boolean,
        isPublicPresentation: Boolean,
        shellLaunchAlreadyTried: Boolean,
        daemonConnected: Boolean,
    ): Display0ExternalAction = when {
        !hasTargetDisplay -> Display0ExternalAction.FALLBACK_LOCAL_ONLY
        isPublicPresentation -> Display0ExternalAction.USE_PRESENTATION
        !shellLaunchAlreadyTried && daemonConnected -> Display0ExternalAction.RELAUNCH_DIRECT_VIA_SHELL
        else -> Display0ExternalAction.FALLBACK_LOCAL_ONLY
    }

    enum class PresentationFailureAction {
        RETRY_PRESENTATION,
        ESCALATE_DIRECT_VIA_SHELL,
        FALLBACK_LOCAL_ONLY,
    }

    /**
     * Decides how [io.github.lengsukq.opaldesk.ExternalDesktopActivity] recovers when
     * `Presentation.show()` fails (e.g. `InvalidDisplayException` during USB-C hot-plug or on
     * HyperOS/OEM ROMs rejecting `TYPE_PRESENTATION` on secondary displays).
     */
    fun resolvePresentationFailureAction(
        retryAttempt: Int,
        isPublicPresentation: Boolean,
        shellLaunchAlreadyTried: Boolean,
        daemonConnected: Boolean,
    ): PresentationFailureAction = when {
        retryAttempt < 1 && isPublicPresentation -> PresentationFailureAction.RETRY_PRESENTATION
        !shellLaunchAlreadyTried && daemonConnected -> PresentationFailureAction.ESCALATE_DIRECT_VIA_SHELL
        else -> PresentationFailureAction.FALLBACK_LOCAL_ONLY
    }

    data class RawExternalMode(
        val width: Int,
        val height: Int,
        val refreshRate: Float,
        val isSynthetic: Boolean = false,
    )

    data class ExternalModeCandidate(
        val width: Int,
        val height: Int,
        val refreshRateHz: Int,
        val isNativePhysical: Boolean = false,
        val isAnisotropySynthetic: Boolean = false,
    )

    data class CuratedExternalModesResult(
        val modes: List<ExternalModeCandidate>,
        val hasAnisotropyDistortion: Boolean,
    )

    fun isSupportedExternalMode(width: Int, height: Int, refreshRate: Float): Boolean {
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        return longEdge >= 1024 && shortEdge >= 600 && refreshRate >= MIN_EXTERNAL_MODE_FPS
    }

    fun isAnisotropicPixelDensity(xDpi: Float, yDpi: Float): Boolean {
        if (!xDpi.isFinite() || !yDpi.isFinite() || xDpi < 20f || yDpi < 20f) return false
        val ratio = if (xDpi >= yDpi) xDpi / yDpi else yDpi / xDpi
        return ratio > ANISOTROPY_DPI_RATIO_THRESHOLD
    }

    fun detectAnisotropyScaleFactor(
        nativeW: Int,
        nativeH: Int,
        xDpi: Float,
        yDpi: Float,
        rawModes: List<RawExternalMode>,
    ): Float {
        if (isAnisotropicPixelDensity(xDpi, yDpi)) {
            return if (xDpi >= yDpi) xDpi / yDpi else yDpi / xDpi
        }
        if (nativeW < 320 || nativeH < 320 || rawModes.isEmpty()) return 1f
        val nativePresent = rawModes.any {
            (it.width == nativeW && it.height == nativeH) || (it.width == nativeH && it.height == nativeW)
        }
        var bestInflatedRatio = 1f
        for (m in rawModes) {
            val widthRatio = if (m.height == nativeH && m.width > (nativeW * ANISOTROPY_DPI_RATIO_THRESHOLD).roundToInt()) {
                m.width.toFloat() / nativeW.toFloat()
            } else {
                1f
            }
            val heightRatio = if (m.width == nativeW && m.height > (nativeH * ANISOTROPY_DPI_RATIO_THRESHOLD).roundToInt()) {
                m.height.toFloat() / nativeH.toFloat()
            } else {
                1f
            }
            val ratio = max(widthRatio, heightRatio)
            if (ratio > ANISOTROPY_DPI_RATIO_THRESHOLD) {
                val oddEdge = ((m.width and 1) != 0) || ((m.height and 1) != 0)
                if (m.isSynthetic || oddEdge || !nativePresent) {
                    if (ratio > bestInflatedRatio) bestInflatedRatio = ratio
                }
            }
        }
        return bestInflatedRatio
    }

    private fun isStandardAspectRatio(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = width.toFloat() / height.toFloat()
        val standardRatios = floatArrayOf(
            16f / 9f, 16f / 10f, 4f / 3f, 5f / 4f, 3f / 2f, 21f / 9f, 43f / 18f, 32f / 9f,
            9f / 16f, 10f / 16f, 3f / 4f, 4f / 5f, 2f / 3f, 1f,
        )
        return standardRatios.any { kotlin.math.abs(ratio - it) <= 0.015f }
    }

    /**
     * Curates raw EDID modes from an external display so:
     * 1. Fractional rates (59.94Hz / 119.88Hz) round cleanly to 60Hz / 120Hz instead of truncating to 59/119Hz.
     * 2. Portrait monitors (1080×1920) & 4:3 projectors (1024×768) are preserved.
     * 3. Each unique resolution keeps at most 2 representative refresh rates (peak + closest to 60Hz)
     *    so high-res 24/25/30/50/59/60/120Hz EDID tables never crowd out 2K/1080p modes.
     * 4. If all modes are smaller than 1024×600 (e.g. 800×480 portable HDMI screen), falls back to
     *    keeping any valid mode >= 640×480 instead of returning an empty list.
     */
    fun curateExternalDisplayModes(
        rawModes: List<Triple<Int, Int, Float>>,
        maxCount: Int = 18,
    ): List<ExternalModeCandidate> {
        return curateExternalDisplayModesWithNative(
            rawModes = rawModes.map { (w, h, hz) -> RawExternalMode(w, h, hz, isSynthetic = false) },
            nativeW = 0,
            nativeH = 0,
            nativeRefreshRate = 0f,
            maxCount = maxCount,
        ).modes
    }

    /**
     * Anisotropy-aware EDID mode curator:
     * - Always ensures the active physical hardware mode (`nativeW × nativeH`) is selectable even
     *   when Android 15–17 pixel anisotropy correction replaces it with a stretched synthetic mode
     *   (e.g. `2677×1536` for a 4:3 `2048×1536` portable monitor with widescreen EDID dimensions).
     * - Marks synthetic/stretched anisotropy modes with [ExternalModeCandidate.isAnisotropySynthetic]
     *   and keeps native hardware modes prioritized first.
     */
    fun curateExternalDisplayModesWithNative(
        rawModes: List<RawExternalMode>,
        nativeW: Int,
        nativeH: Int,
        nativeRefreshRate: Float,
        xDpi: Float = 0f,
        yDpi: Float = 0f,
        maxCount: Int = 18,
    ): CuratedExternalModesResult {
        if (maxCount <= 0) return CuratedExternalModesResult(emptyList(), false)
        val anisotropyFactor = detectAnisotropyScaleFactor(nativeW, nativeH, xDpi, yDpi, rawModes)
        val hasAnisotropy = anisotropyFactor > ANISOTROPY_DPI_RATIO_THRESHOLD
        val expectedAnisoAspect = if (hasAnisotropy && nativeW > 0 && nativeH > 0) {
            val baseAspect = nativeW.toFloat() / nativeH.toFloat()
            val widerAspect = baseAspect * anisotropyFactor
            val tallerAspect = baseAspect / anisotropyFactor
            widerAspect to tallerAspect
        } else {
            null
        }

        val combined = ArrayList<RawExternalMode>(rawModes.size + 1)
        combined.addAll(rawModes)
        val hasValidNative = nativeW >= CustomResolutionPolicy.MIN_SHORT_EDGE &&
            nativeH >= CustomResolutionPolicy.MIN_SHORT_EDGE
        if (hasValidNative && combined.none { it.width == nativeW && it.height == nativeH && !it.isSynthetic }) {
            val safeNativeHz = if (nativeRefreshRate >= MIN_EXTERNAL_MODE_FPS) nativeRefreshRate else 60f
            combined.add(RawExternalMode(nativeW, nativeH, safeNativeHz, isSynthetic = false))
        }
        if (combined.isEmpty()) return CuratedExternalModesResult(emptyList(), hasAnisotropy)

        val primary = combined.filter { isSupportedExternalMode(it.width, it.height, it.refreshRate) }
        val pool = primary.ifEmpty {
            combined.filter {
                maxOf(it.width, it.height) >= CustomResolutionPolicy.MIN_LONG_EDGE &&
                    minOf(it.width, it.height) >= CustomResolutionPolicy.MIN_SHORT_EDGE &&
                    it.refreshRate >= MIN_EXTERNAL_MODE_FPS
            }
        }

        val byResolution = LinkedHashMap<Pair<Int, Int>, MutableList<Pair<Int, Boolean>>>()
        for (m in pool) {
            val roundedHz = m.refreshRate.roundToInt().coerceAtLeast(24)
            val inferredAniso = if (m.isSynthetic) {
                true
            } else if (hasAnisotropy && expectedAnisoAspect != null) {
                val dimsMatchNative = (m.width == nativeW && m.height == nativeH) ||
                    (m.width == nativeH && m.height == nativeW)
                if (dimsMatchNative) {
                    false
                } else {
                    val aspect = m.width.toFloat() / m.height.toFloat()
                    val matchesWider = kotlin.math.abs(aspect - expectedAnisoAspect.first) / expectedAnisoAspect.first <= 0.025f
                    val matchesTaller = kotlin.math.abs(aspect - expectedAnisoAspect.second) / expectedAnisoAspect.second <= 0.025f
                    val oddEdge = ((m.width and 1) != 0) || ((m.height and 1) != 0)
                    (matchesWider || matchesTaller) && (oddEdge || !isStandardAspectRatio(m.width, m.height))
                }
            } else {
                false
            }
            byResolution.getOrPut(m.width to m.height) { ArrayList() }.add(roundedHz to inferredAniso)
        }

        val curated = ArrayList<ExternalModeCandidate>()
        for ((dims, entries) in byResolution) {
            val hzToSynth = LinkedHashMap<Int, Boolean>()
            for ((hz, synth) in entries) {
                // Non-synthetic hardware mode wins over synthetic mode at the same W×H@Hz
                val prev = hzToSynth[hz]
                hzToSynth[hz] = if (prev == null) synth else (prev && synth)
            }
            val sortedHz = hzToSynth.keys.sortedDescending()
            val chosen = ArrayList<Int>(2)
            val peak = sortedHz.first()
            chosen.add(peak)
            val near60 = sortedHz.firstOrNull { kotlin.math.abs(it - 60) <= 2 && kotlin.math.abs(it - peak) >= 5 }
            if (near60 != null) {
                chosen.add(near60)
            } else if (sortedHz.size > 1) {
                val second = sortedHz.firstOrNull { kotlin.math.abs(it - peak) >= 10 }
                if (second != null) chosen.add(second)
            }
            val isNative = hasValidNative && dims.first == nativeW && dims.second == nativeH
            for (hz in chosen) {
                val isSynth = !isNative && (hzToSynth[hz] == true)
                curated.add(
                    ExternalModeCandidate(
                        width = dims.first,
                        height = dims.second,
                        refreshRateHz = hz,
                        isNativePhysical = isNative,
                        isAnisotropySynthetic = isSynth,
                    ),
                )
            }
        }

        val sorted = curated
            .sortedWith(
                compareByDescending<ExternalModeCandidate> { hasAnisotropy && it.isNativePhysical }
                    .thenBy { it.isAnisotropySynthetic }
                    .thenByDescending { it.width.toLong() * it.height.toLong() }
                    .thenByDescending { it.refreshRateHz },
            )
            .take(maxCount)
        return CuratedExternalModesResult(modes = sorted, hasAnisotropyDistortion = hasAnisotropy)
    }

    /**
     * Detects when a mirroring-backed external display (e.g. HyperOS wired DP Presentation display
     * after `clearForcedDisplaySize`) reports primary Display 0's portrait dimensions (such as
     * `1200×2608`) instead of its own landscape physical dimensions (`2048×1536`).
     */
    fun isMirroredPrimaryDisplayCollapse(
        nativeW: Int,
        nativeH: Int,
        logicalW: Int,
        logicalH: Int,
        primaryW: Int,
        primaryH: Int,
    ): Boolean {
        if (nativeW < 320 || nativeH < 320 || logicalW < 320 || logicalH < 320 || primaryW < 320 || primaryH < 320) {
            return false
        }
        val logicalMatchesPrimary =
            kotlin.math.abs(logicalW - primaryW) <= MIRROR_COLLAPSE_SIZE_TOLERANCE_PX &&
                kotlin.math.abs(logicalH - primaryH) <= MIRROR_COLLAPSE_SIZE_TOLERANCE_PX
        if (!logicalMatchesPrimary) return false
        val nativeMatchesPrimarySameOrientation =
            kotlin.math.abs(nativeW - primaryW) <= MIRROR_COLLAPSE_SIZE_TOLERANCE_PX &&
                kotlin.math.abs(nativeH - primaryH) <= MIRROR_COLLAPSE_SIZE_TOLERANCE_PX
        if (nativeMatchesPrimarySameOrientation) return false
        val nativeLandscape = nativeW >= nativeH
        val logicalLandscape = logicalW >= logicalH
        if (nativeLandscape != logicalLandscape) return true
        val nativeAspect = nativeW.toFloat() / nativeH.toFloat()
        val logicalAspect = logicalW.toFloat() / logicalH.toFloat()
        return kotlin.math.abs(nativeAspect - logicalAspect) / max(nativeAspect, logicalAspect) >=
            MIRROR_COLLAPSE_ASPECT_DIFF_THRESHOLD
    }

    /**
     * Resolves the currently active logical display dimensions, preferring [realW]×[realH]
     * (which reflects forced custom display sizes via `wm size` and display rotation) over raw
     * physical EDID mode dimensions [modeW]×[modeH] — while ignoring mirror-collapsed primary
     * Display 0 dimensions when [primaryW]×[primaryH] is provided.
     */
    fun resolveEffectiveDisplaySize(
        modeW: Int,
        modeH: Int,
        realW: Int,
        realH: Int,
        primaryW: Int = 0,
        primaryH: Int = 0,
    ): Pair<Int, Int> {
        if (primaryW >= 320 && primaryH >= 320 &&
            isMirroredPrimaryDisplayCollapse(modeW, modeH, realW, realH, primaryW, primaryH)
        ) {
            if (modeW >= 320 && modeH >= 320) return modeW to modeH
        }
        if (realW >= 320 && realH >= 320) return realW to realH
        if (modeW >= 320 && modeH >= 320) return modeW to modeH
        return 1920 to 1080
    }

    fun isCustomForcedOverride(
        activeW: Int,
        activeH: Int,
        nativeW: Int,
        nativeH: Int,
    ): Boolean {
        if (activeW <= 0 || activeH <= 0 || nativeW <= 0 || nativeH <= 0) return false
        val sameNormal = (activeW == nativeW && activeH == nativeH)
        val sameRotated = (activeW == nativeH && activeH == nativeW)
        return !sameNormal && !sameRotated
    }

    /**
     * Decides whether entering an external display session should automatically ask the daemon to
     * normalize the display to its native physical resolution (with 1:1 unscaled output when
     * anisotropic or explicitly opted in), without overwriting an intentional user custom override
     * such as `1920×1080` on a `2048×1536` screen.
     */
    fun shouldAutoNormalizeExternalOnEnter(
        nativeW: Int,
        nativeH: Int,
        realW: Int,
        realH: Int,
        primaryW: Int,
        primaryH: Int,
        hasAnisotropy: Boolean,
        userNative1To1Enabled: Boolean,
        autoCustomResolutionEnabled: Boolean,
    ): Boolean {
        if (autoCustomResolutionEnabled || nativeW < 320 || nativeH < 320) return false
        if (isMirroredPrimaryDisplayCollapse(nativeW, nativeH, realW, realH, primaryW, primaryH)) {
            return true
        }
        if (!hasAnisotropy && !userNative1To1Enabled) return false
        val atNativeOrUnset = realW < 320 || realH < 320 ||
            !isCustomForcedOverride(realW, realH, nativeW, nativeH)
        val atAnisotropyInflatedNative = hasAnisotropy && (
            (realH == nativeH && realW > (nativeW * ANISOTROPY_DPI_RATIO_THRESHOLD).roundToInt()) ||
                (realW == nativeW && realH > (nativeH * ANISOTROPY_DPI_RATIO_THRESHOLD).roundToInt())
            )
        return atNativeOrUnset || atAnisotropyInflatedNative
    }

    /**
     * Computes symmetric (horizontalInsetPx, verticalInsetPx) margins for TV overscan compensation,
     * clamping [overscanPct] to `0%..6%` and guaranteeing a minimum 640×360 inner canvas.
     */
    fun computeOverscanInsetsPx(widthPx: Int, heightPx: Int, overscanPct: Float): Pair<Int, Int> {
        val pct = overscanPct.coerceIn(MIN_OVERSCAN_PCT, MAX_OVERSCAN_PCT)
        if (pct <= 0.05f || widthPx <= 640 || heightPx <= 360) return 0 to 0
        val maxH = ((widthPx - 640) / 2).coerceAtLeast(0)
        val maxV = ((heightPx - 360) / 2).coerceAtLeast(0)
        val h = ((widthPx * pct) / 100f).roundToInt().coerceIn(0, maxH)
        val v = ((heightPx * pct) / 100f).roundToInt().coerceIn(0, maxV)
        return h to v
    }

    fun isPhysicalExternalDisplay(
        displayId: Int,
        isValid: Boolean,
        name: String?,
        flags: Int,
        state: Int = 2,
    ): Boolean {
        val transport = classifyExternalDisplay(
            displayId = displayId,
            isValid = isValid,
            name = name,
            flags = flags,
            state = state,
        )
        return isAutoProjectable(transport)
    }

    fun isRemoteCastDisplay(
        displayId: Int,
        displayName: String?,
        remote: RemoteStreamStatus,
    ): Boolean {
        if (displayId <= DEFAULT_DISPLAY_ID) return false
        if (displayName.orEmpty().startsWith("OpalDesk-")) return true
        return remote.hasActiveDesktopCast && remote.desktopDisplayId == displayId
    }
}

object ExternalTrackpadPolicy {
    fun resolveTrackpadTarget(
        remote: RemoteStreamStatus,
        session: ExternalDesktopSession,
    ): TrackpadTargetMode = when {
        session.isPresentation -> TrackpadTargetMode.PHYSICAL_PRESENTATION
        session.isPhysicalDirectExtended -> TrackpadTargetMode.PHYSICAL_DIRECT_EXTENDED
        remote.hasActiveDesktopCast -> TrackpadTargetMode.REMOTE_CAST
        else -> TrackpadTargetMode.NONE
    }

    fun shouldAutoLaunchPhoneTrackpad(
        session: ExternalDesktopSession,
        autoShowEnabled: Boolean,
        alreadyVisible: Boolean,
        autoBlackoutEnabled: Boolean = false,
    ): Boolean = session.isPhysicalDirectExtended && autoShowEnabled && !autoBlackoutEnabled && !alreadyVisible

    /**
     * Decides whether the thermal-saving auto screen-off timer should trigger on entering an
     * external monitor or remote cast workstation.
     *
     * <p>In Presentation mode Display 0 hosts the trackpad shell by default, so [phoneTrackpadVisible]
     * must not block auto-blackout when the user explicitly opted in to auto screen-off.
     */
    fun shouldTriggerAutoBlackout(
        isExternalMonitor: Boolean,
        autoBlackoutEnabled: Boolean,
        daemonConnected: Boolean,
        hostScreenOff: Boolean,
        phoneTrackpadVisible: Boolean,
        isPresentationMode: Boolean,
        alreadyTriggeredForSession: Boolean,
    ): Boolean {
        if (!isExternalMonitor || !autoBlackoutEnabled || !daemonConnected || hostScreenOff || alreadyTriggeredForSession) {
            return false
        }
        return isPresentationMode || !phoneTrackpadVisible
    }

    fun canControlPhoneTrackpad(
        isExternalMonitor: Boolean,
        remote: RemoteStreamStatus,
        session: ExternalDesktopSession,
    ): Boolean = isExternalMonitor && resolveTrackpadTarget(remote, session) != TrackpadTargetMode.NONE

    fun clampCursorPosition(
        x: Float,
        y: Float,
        widthPx: Int,
        heightPx: Int,
    ): Pair<Float, Float> {
        val maxX = max(1f, (widthPx - 1).toFloat())
        val maxY = max(1f, (heightPx - 1).toFloat())
        return min(maxX, max(0f, x)) to min(maxY, max(0f, y))
    }
}

/**
 * In-process sink hosted by [io.github.lengsukq.opaldesk.ExternalDesktopActivity] in Direct Extended
 * Display mode so [RemoteTrackpadActivity] on Display 0 drives cursor, taps, drags, and scroll at
 * zero-IPC latency on the physical external monitor.
 */
interface DirectExternalTrackpadSink {
    val displayId: Int
    fun currentSurfaceWidthPx(): Int
    fun currentSurfaceHeightPx(): Int
    fun currentCursorX(): Float
    fun currentCursorY(): Float
    fun moveCursorBy(dxPx: Float, dyPx: Float)
    fun setPrimaryButton(pressed: Boolean)
    fun tapPrimary()
    fun scrollAtCursor(hScroll: Float, vScroll: Float)
    fun hideCursor()
    fun closeExternalDesktop()
}

/**
 * Single source of truth for reactive external desktop state, phone trackpad visibility, and
 * cross-activity trackpad sink / close signaling.
 */
object ExternalTrackpadBridge {
    private val _sessionFlow = MutableStateFlow(ExternalDesktopSession())
    val sessionFlow: StateFlow<ExternalDesktopSession> = _sessionFlow.asStateFlow()

    private val _phoneTrackpadVisibleFlow = MutableStateFlow(false)
    val phoneTrackpadVisibleFlow: StateFlow<Boolean> = _phoneTrackpadVisibleFlow.asStateFlow()

    private val _directCursorFlow = MutableStateFlow(0f to 0f)
    val directCursorFlow: StateFlow<Pair<Float, Float>> = _directCursorFlow.asStateFlow()

    @Volatile
    private var ownerToken: Any? = null

    @Volatile
    private var directSink: DirectExternalTrackpadSink? = null

    @Volatile
    private var closeTrackpadAction: (() -> Unit)? = null

    fun publishSession(owner: Any, session: ExternalDesktopSession) {
        ownerToken = owner
        _sessionFlow.value = session
    }

    fun clearSessionIfOwner(owner: Any) {
        if (ownerToken === owner) {
            ownerToken = null
            _sessionFlow.value = ExternalDesktopSession()
        }
    }

    fun registerDirectSink(sink: DirectExternalTrackpadSink) {
        directSink = sink
        _directCursorFlow.value = sink.currentCursorX() to sink.currentCursorY()
    }

    fun unregisterDirectSink(sink: DirectExternalTrackpadSink) {
        if (directSink === sink) {
            directSink = null
        }
    }

    fun getDirectSink(): DirectExternalTrackpadSink? = directSink

    fun updateDirectCursor(x: Float, y: Float) {
        _directCursorFlow.value = x to y
    }

    fun setPhoneTrackpadVisible(visible: Boolean, closer: (() -> Unit)? = null) {
        if (visible) {
            if (closer != null) {
                closeTrackpadAction = closer
            }
            _phoneTrackpadVisibleFlow.value = true
        } else if (closer == null || closeTrackpadAction == null || closeTrackpadAction === closer) {
            closeTrackpadAction = null
            _phoneTrackpadVisibleFlow.value = false
        }
    }

    fun requestClosePhoneTrackpad(): Boolean {
        val action = closeTrackpadAction ?: return false
        action.invoke()
        return true
    }

    /** Test-only reset helper. */
    internal fun resetForTest() {
        ownerToken = null
        directSink = null
        closeTrackpadAction = null
        _sessionFlow.value = ExternalDesktopSession()
        _phoneTrackpadVisibleFlow.value = false
        _directCursorFlow.value = 0f to 0f
    }
}
