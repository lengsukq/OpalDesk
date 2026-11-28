package io.github.lengsukq.opaldesk.external

import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

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
     * Decides whether Display 0 should display the built-in Presentation trackpad surface
     * (`DexTrackpadScreen`) vs. the lightweight external-display standby card.
     *
     * Respects [autoShowEnabled] on initial entry and preserves explicit manual open/close actions
     * across `onResume()` cycles without forcibly resetting visibility to `true`.
     */
    fun shouldShowPresentationTrackpad(
        autoShowEnabled: Boolean,
        userManuallyOpened: Boolean = false,
        userManuallyClosed: Boolean = false,
    ): Boolean = when {
        userManuallyOpened -> true
        userManuallyClosed -> false
        else -> autoShowEnabled
    }

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
