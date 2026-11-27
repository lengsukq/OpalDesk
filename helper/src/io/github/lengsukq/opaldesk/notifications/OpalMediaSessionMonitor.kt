package io.github.lengsukq.opaldesk.notifications

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import io.github.lengsukq.opaldesk.BridgeClient
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class MediaPlaybackSnapshot(
    val active: Boolean = false,
    val pkg: String = "",
    val appLabel: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val artworkBase64: String = "",
)

/**
 * Pure JVM-testable media session selection, position extrapolation, artwork dimension scaling,
 * and exact OPD1 v1.2 {@code 0x1A TYPE_MEDIA_STATE} JSON encoding policy.
 */
object OpalMediaStatePolicy {
    const val MAX_ARTWORK_EDGE_PX = 128
    const val MAX_ARTWORK_BASE64_BYTES = 64 * 1024 // 64 KiB cap

    data class SessionCandidate(
        val id: String,
        val pkg: String,
        val playbackStateCode: Int,
        val hasMetadata: Boolean,
    )

    fun isPlayingState(playbackStateCode: Int): Boolean =
        playbackStateCode == PlaybackState.STATE_PLAYING ||
            playbackStateCode == PlaybackState.STATE_BUFFERING

    fun isActiveState(playbackStateCode: Int): Boolean = when (playbackStateCode) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        -> true
        else -> false
    }

    /**
     * Picks the highest-priority active media controller: actively playing/buffering first,
     * then paused/active with metadata, then any session with metadata.
     */
    fun selectPrimaryCandidateIndex(candidates: List<SessionCandidate>): Int {
        if (candidates.isEmpty()) return -1
        var bestIdx = -1
        var bestScore = -1
        candidates.forEachIndexed { idx, c ->
            if (c.pkg.isBlank() || c.pkg == "io.github.lengsukq.opaldesk") return@forEachIndexed
            val score = when {
                isPlayingState(c.playbackStateCode) && c.hasMetadata -> 100
                isPlayingState(c.playbackStateCode) -> 90
                isActiveState(c.playbackStateCode) && c.hasMetadata -> 60
                c.hasMetadata -> 30
                isActiveState(c.playbackStateCode) -> 20
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }
        return bestIdx
    }

    /**
     * Extrapolates current media playback position from the last [PlaybackState] timestamp and
     * clamps cleanly within `[0, durationMs]` (when `durationMs > 0`).
     */
    fun computeEffectivePositionMs(
        basePositionMs: Long,
        lastUpdateElapsedMs: Long,
        nowElapsedMs: Long,
        playbackSpeed: Float,
        playing: Boolean,
        durationMs: Long,
    ): Long {
        val base = basePositionMs.coerceAtLeast(0L)
        val raw = if (playing && lastUpdateElapsedMs > 0L && nowElapsedMs >= lastUpdateElapsedMs) {
            val speed = if (playbackSpeed.isFinite() && playbackSpeed > 0f) playbackSpeed else 1f
            val delta = ((nowElapsedMs - lastUpdateElapsedMs) * speed).toLong().coerceAtLeast(0L)
            base + delta
        } else {
            base
        }
        return if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
    }

    /** Computes aspect-preserving scaled artwork dimensions bounded to `[1, maxEdgePx]`. */
    fun computeArtworkTargetSize(
        srcWidth: Int,
        srcHeight: Int,
        maxEdgePx: Int = MAX_ARTWORK_EDGE_PX,
    ): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdgePx <= 0) return 0 to 0
        val maxDim = maxOf(srcWidth, srcHeight)
        if (maxDim <= maxEdgePx) return srcWidth to srcHeight
        val scale = maxEdgePx.toFloat() / maxDim.toFloat()
        val w = (srcWidth * scale).toInt().coerceIn(1, maxEdgePx)
        val h = (srcHeight * scale).toInt().coerceIn(1, maxEdgePx)
        return w to h
    }

    fun isValidMediaActionOp(op: String): Boolean = when (op.trim().lowercase(Locale.ROOT)) {
        "play", "pause", "toggle", "next", "previous", "stop", "seek" -> true
        else -> false
    }

    fun encodeJson(state: MediaPlaybackSnapshot): String {
        val safeArtwork = if (state.artworkBase64.length <= MAX_ARTWORK_BASE64_BYTES) {
            state.artworkBase64
        } else {
            ""
        }
        val sb = StringBuilder(256 + safeArtwork.length)
        sb.append("{\"active\":").append(state.active)
        sb.append(",\"pkg\":")
        OpalNotificationJsonCodec.appendJsonString(sb, state.pkg)
        sb.append(",\"appLabel\":")
        OpalNotificationJsonCodec.appendJsonString(sb, state.appLabel)
        sb.append(",\"title\":")
        OpalNotificationJsonCodec.appendJsonString(sb, state.title)
        sb.append(",\"artist\":")
        OpalNotificationJsonCodec.appendJsonString(sb, state.artist)
        sb.append(",\"album\":")
        OpalNotificationJsonCodec.appendJsonString(sb, state.album)
        sb.append(",\"playing\":").append(state.playing)
        sb.append(",\"positionMs\":").append(state.positionMs.coerceAtLeast(0L))
        sb.append(",\"durationMs\":").append(state.durationMs.coerceAtLeast(0L))
        sb.append(",\"artworkBase64\":")
        OpalNotificationJsonCodec.appendJsonString(sb, safeArtwork)
        sb.append('}')
        return sb.toString()
    }
}

/**
 * Monitors active Android [MediaController] sessions via [MediaSessionManager.getActiveSessions]
 * (powered by [OpalNotificationListenerService]'s already-granted Notification Listener component),
 * pushes live metadata/playback state over Binder TX 51, and executes remote `mediaAction` commands.
 */
object OpalMediaSessionMonitor {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val appLabelCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var listenerComponent: ComponentName? = null

    @Volatile
    private var mediaSessionManager: MediaSessionManager? = null

    @Volatile
    private var activeControllers: List<MediaController> = emptyList()

    @Volatile
    private var lastSnapshot: MediaPlaybackSnapshot = MediaPlaybackSnapshot()

    @Volatile
    private var lastSentJson: String = ""

    private var cachedArtworkKey: String = ""
    private var cachedArtworkBase64: String = ""

    private val controllerCallbacks = HashMap<MediaController, MediaController.Callback>()

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onControllersUpdated(controllers.orEmpty())
    }

    fun start(service: OpalNotificationListenerService) {
        val ctx = service.applicationContext
        val cn = ComponentName(ctx, OpalNotificationListenerService::class.java)
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        synchronized(lock) {
            stopLocked()
            appContext = ctx
            listenerComponent = cn
            mediaSessionManager = msm
            runCatching {
                msm.addOnActiveSessionsChangedListener(sessionsChangedListener, cn, mainHandler)
            }
            val initial = runCatching { msm.getActiveSessions(cn) }.getOrNull().orEmpty()
            updateControllersLocked(initial)
        }
        publishSnapshot(force = true)
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    fun pushCurrentStateToDaemon() {
        val snapshot = buildCurrentSnapshotSafe()
        val json = OpalMediaStatePolicy.encodeJson(snapshot)
        lastSnapshot = snapshot
        lastSentJson = json
        sendToDaemonSafe(json)
    }

    /**
     * Executes a remote Mac/companion playback command (`play|pause|toggle|next|previous|stop|seek`)
     * on the currently prioritized active [MediaController].
     */
    fun handleRemoteAction(op: String, positionMs: Long): Boolean {
        val normalizedOp = op.trim().lowercase(Locale.ROOT)
        if (!OpalMediaStatePolicy.isValidMediaActionOp(normalizedOp)) return false
        val controller = resolvePrimaryController() ?: return false
        val controls = controller.transportControls
        val ok = runCatching {
            when (normalizedOp) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> {
                    val stateCode = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                    if (OpalMediaStatePolicy.isPlayingState(stateCode)) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }
                "next" -> controls.skipToNext()
                "previous" -> controls.skipToPrevious()
                "stop" -> controls.stop()
                "seek" -> controls.seekTo(positionMs.coerceAtLeast(0L))
            }
            true
        }.getOrDefault(false)
        if (ok) {
            mainHandler.postDelayed({ publishSnapshot(force = true) }, 160L)
        }
        return ok
    }

    private fun stopLocked() {
        val msm = mediaSessionManager
        if (msm != null) {
            runCatching { msm.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        }
        clearControllerCallbacksLocked()
        activeControllers = emptyList()
        mediaSessionManager = null
        listenerComponent = null
        cachedArtworkKey = ""
        cachedArtworkBase64 = ""
    }

    private fun onControllersUpdated(controllers: List<MediaController>) {
        synchronized(lock) {
            updateControllersLocked(controllers)
        }
        publishSnapshot(force = false)
    }

    private fun updateControllersLocked(controllers: List<MediaController>) {
        clearControllerCallbacksLocked()
        activeControllers = controllers
        for (controller in controllers) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    publishSnapshot(force = false)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    publishSnapshot(force = false)
                }

                override fun onSessionDestroyed() {
                    refreshActiveSessionsFromManager()
                }
            }
            runCatching {
                controller.registerCallback(cb, mainHandler)
                controllerCallbacks[controller] = cb
            }
        }
    }

    private fun clearControllerCallbacksLocked() {
        for ((controller, cb) in controllerCallbacks) {
            runCatching { controller.unregisterCallback(cb) }
        }
        controllerCallbacks.clear()
    }

    private fun refreshActiveSessionsFromManager() {
        val msm = mediaSessionManager ?: return
        val cn = listenerComponent ?: return
        val fresh = runCatching { msm.getActiveSessions(cn) }.getOrNull().orEmpty()
        onControllersUpdated(fresh)
    }

    private fun resolvePrimaryController(): MediaController? {
        var list = activeControllers
        if (list.isEmpty()) {
            val msm = mediaSessionManager
            val cn = listenerComponent
            if (msm != null && cn != null) {
                list = runCatching { msm.getActiveSessions(cn) }.getOrNull().orEmpty()
                if (list.isNotEmpty()) {
                    synchronized(lock) { updateControllersLocked(list) }
                }
            }
        }
        if (list.isEmpty()) return null
        val candidates = list.mapIndexed { idx, c ->
            val pb = runCatching { c.playbackState }.getOrNull()
            val meta = runCatching { c.metadata }.getOrNull()
            val hasTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.isNotBlank() == true ||
                meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.isNotBlank() == true
            OpalMediaStatePolicy.SessionCandidate(
                id = idx.toString(),
                pkg = runCatching { c.packageName }.getOrNull().orEmpty(),
                playbackStateCode = pb?.state ?: PlaybackState.STATE_NONE,
                hasMetadata = hasTitle,
            )
        }
        val chosenIdx = OpalMediaStatePolicy.selectPrimaryCandidateIndex(candidates)
        return if (chosenIdx in list.indices) list[chosenIdx] else null
    }

    private fun buildCurrentSnapshotSafe(): MediaPlaybackSnapshot {
        val controller = resolvePrimaryController() ?: return MediaPlaybackSnapshot(active = false)
        val pkg = runCatching { controller.packageName }.getOrNull().orEmpty()
        val pb = runCatching { controller.playbackState }.getOrNull()
        val meta = runCatching { controller.metadata }.getOrNull()

        val stateCode = pb?.state ?: PlaybackState.STATE_NONE
        val playing = OpalMediaStatePolicy.isPlayingState(stateCode)
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            .ifEmpty { meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim().orEmpty() }
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
            .ifEmpty { meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim().orEmpty() }
            .ifEmpty { meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.trim().orEmpty() }
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val durationMs = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val positionMs = OpalMediaStatePolicy.computeEffectivePositionMs(
            basePositionMs = pb?.position ?: 0L,
            lastUpdateElapsedMs = pb?.lastPositionUpdateTime ?: 0L,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            playbackSpeed = pb?.playbackSpeed ?: 1f,
            playing = playing,
            durationMs = durationMs,
        )
        val active = title.isNotEmpty() || OpalMediaStatePolicy.isActiveState(stateCode)
        if (!active) {
            return MediaPlaybackSnapshot(active = false)
        }

        val artworkBase64 = extractArtworkBase64Cached(pkg, title, album, meta)
        return MediaPlaybackSnapshot(
            active = true,
            pkg = pkg,
            appLabel = resolveAppLabel(pkg),
            title = title,
            artist = artist,
            album = album,
            playing = playing,
            positionMs = positionMs,
            durationMs = durationMs,
            artworkBase64 = artworkBase64,
        )
    }

    private fun extractArtworkBase64Cached(
        pkg: String,
        title: String,
        album: String,
        meta: MediaMetadata?,
    ): String {
        if (meta == null) return ""
        val bmp = runCatching {
            meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull() ?: return ""
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return ""
        val cacheKey = "$pkg|$title|$album|${bmp.width}x${bmp.height}|${bmp.generationId}"
        synchronized(lock) {
            if (cacheKey == cachedArtworkKey) {
                return cachedArtworkBase64
            }
        }
        val encoded = encodeArtworkBitmap(bmp)
        synchronized(lock) {
            cachedArtworkKey = cacheKey
            cachedArtworkBase64 = encoded
        }
        return encoded
    }

    private fun encodeArtworkBitmap(src: Bitmap): String {
        val (tw, th) = OpalMediaStatePolicy.computeArtworkTargetSize(
            src.width,
            src.height,
            OpalMediaStatePolicy.MAX_ARTWORK_EDGE_PX,
        )
        if (tw <= 0 || th <= 0) return ""
        val scaled = if (tw != src.width || th != src.height) {
            runCatching { Bitmap.createScaledBitmap(src, tw, th, true) }.getOrNull() ?: src
        } else {
            src
        }
        return try {
            val out = ByteArrayOutputStream(8192)
            for (quality in intArrayOf(78, 60, 42)) {
                out.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (b64.length <= OpalMediaStatePolicy.MAX_ARTWORK_BASE64_BYTES) {
                    return b64
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        } finally {
            if (scaled !== src && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    private fun publishSnapshot(force: Boolean) {
        val snapshot = buildCurrentSnapshotSafe()
        val json = OpalMediaStatePolicy.encodeJson(snapshot)
        if (!force && json == lastSentJson) {
            return
        }
        lastSnapshot = snapshot
        lastSentJson = json
        sendToDaemonSafe(json)
    }

    private fun sendToDaemonSafe(json: String) {
        runCatching {
            if (BridgeClient.isBinderAliveFast()) {
                BridgeClient.syncMediaStateAsync(json)
            }
        }
    }

    private fun resolveAppLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        return appLabelCache.getOrPut(pkg) {
            val ctx = appContext ?: return@getOrPut pkg
            runCatching {
                val pm = ctx.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString().trim().ifEmpty { pkg }
            }.getOrDefault(pkg)
        }
    }
}
