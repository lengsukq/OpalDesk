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

    fun isSupportedExternalMode(width: Int, height: Int, refreshRate: Float): Boolean =
        width >= 1280 && height >= 720 && refreshRate >= MIN_EXTERNAL_MODE_FPS

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
    ): Boolean = session.isPhysicalDirectExtended && autoShowEnabled && !alreadyVisible

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
