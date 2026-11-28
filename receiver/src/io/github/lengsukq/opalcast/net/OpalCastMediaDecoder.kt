package io.github.lengsukq.opalcast.net

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.policy.UplinkCoalescer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware-accelerated video (`MediaCodec` HEVC/AVC → `Surface`) and low-latency audio
 * (`MediaCodec` AAC → `AudioTrack`) decoding pipeline for [OpalCastClient].
 */
internal class OpalCastMediaDecoder(
    initialWidth: Int,
    initialHeight: Int,
    initialFps: Int,
    private val isActive: () -> Boolean,
    private val onDisplayModeChanged: (Int) -> Unit,
    private val onVideoSizeChanged: (width: Int, height: Int, fps: Int) -> Unit,
    private val onRequestKeyFrame: () -> Unit,
    private val onRequestKeyFrameThrottled: () -> Unit,
) {
    companion object {
        private const val TAG = "OpalCastMediaDecoder"
        private const val CODEC_HEVC = 2
        private const val FLAG_VIDEO_KEYFRAME = 0x01
        private const val DECODER_RETRY_COOLDOWN_MS = 1_000L
        private const val AUDIO_PREFILL_FRAMES = 1
        private const val AUDIO_IDLE_PAUSE_MS = 260L
    }

    @Volatile
    var remoteWidth: Int = initialWidth
        private set
    @Volatile
    var remoteHeight: Int = initialHeight
        private set
    @Volatile
    var remoteFps: Int = initialFps
        private set

    private val videoLock = Any()
    @Volatile
    private var outputSurface: Surface? = null
    @Volatile
    private var videoCodec: MediaCodec? = null
    private var videoMime: String = MediaFormat.MIMETYPE_VIDEO_HEVC
    private var pendingCsd: ByteArray? = null
    private var configuredVideoW = 0
    private var configuredVideoH = 0
    private var videoConfigured = false
    @Volatile
    private var awaitingInitialKeyFrame = true
    private var lastDecoderConfigureFailMs = 0L
    @Volatile
    private var videoDrainThread: Thread? = null
    private val lastVideoDecodeErrLogMs = AtomicLong(0L)

    private val audioLock = Any()
    private val audioQueue = LinkedBlockingQueue<Pair<ByteArray, Long>>(6)
    private val audioOutBufInfo = MediaCodec.BufferInfo()
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var audioTrackPlaying = false
    private var audioPrefillFrames = 0

    fun attachSurface(surface: Surface?) {
        var needsIdr = false
        synchronized(videoLock) {
            val surfaceChanged = outputSurface !== surface
            outputSurface = surface
            lastDecoderConfigureFailMs = 0L
            if (surface == null || !surface.isValid) {
                releaseVideoDecoderLocked()
                return
            }
            if (surfaceChanged && videoConfigured) {
                releaseVideoDecoderLocked()
            }
            val csd = pendingCsd
            if (csd != null && !videoConfigured) {
                configureVideoDecoderLocked(videoMime, remoteWidth, remoteHeight, csd)
                needsIdr = true
            } else if (surfaceChanged || !videoConfigured) {
                needsIdr = true
            }
        }
        if (needsIdr) onRequestKeyFrame()
    }

    fun startAudioThread(): Thread = Thread(::audioDecodeLoop, "OpalCastAudioDec").apply {
        isDaemon = true
        start()
    }

    fun enqueueAudioFrame(payload: ByteArray, tsUs: Long) {
        if (payload.isEmpty()) return
        if (audioQueue.size >= 4) {
            while (audioQueue.size > 2) {
                if (audioQueue.poll() == null) break
            }
        }
        if (!audioQueue.offer(payload to tsUs)) {
            audioQueue.poll()
            audioQueue.offer(payload to tsUs)
        }
    }

    fun handleVideoConfig(payload: ByteArray) {
        if (payload.size < 11) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val codecId = bb.get().toInt() and 0xFF
        val w = bb.short.toInt() and 0xFFFF
        val h = bb.short.toInt() and 0xFFFF
        bb.short
        bb.get()
        val fpsX100 = bb.short.toInt() and 0xFFFF
        val displayMode = bb.get().toInt() and 0xFF
        val csd = if (payload.size > 11) payload.copyOfRange(11, payload.size) else ByteArray(0)
        val mime = if (codecId == CODEC_HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val fps = (fpsX100 / 100).coerceIn(30, 165)
        remoteWidth = w.coerceAtLeast(2)
        remoteHeight = h.coerceAtLeast(2)
        remoteFps = fps
        if (displayMode == OpalCastClient.DISPLAY_MODE_DEX || displayMode == OpalCastClient.DISPLAY_MODE_MIRROR) {
            onDisplayModeChanged(displayMode)
        }
        onVideoSizeChanged(remoteWidth, remoteHeight, fps)

        synchronized(videoLock) {
            if (videoConfigured && videoMime == mime &&
                configuredVideoW == remoteWidth && configuredVideoH == remoteHeight &&
                pendingCsd?.contentEquals(csd) == true
            ) {
                return
            }
            videoMime = mime
            pendingCsd = csd
            lastDecoderConfigureFailMs = 0L
            configureVideoDecoderLocked(mime, remoteWidth, remoteHeight, csd)
        }
    }

    fun handleVideoFrame(payload: ByteArray, length: Int, flags: Int, tsUs: Long) {
        if (length <= 0 || length > payload.size) return
        val codec = synchronized(videoLock) {
            if (!videoConfigured && pendingCsd != null) {
                val now = SystemClock.uptimeMillis()
                if (lastDecoderConfigureFailMs == 0L || now - lastDecoderConfigureFailMs >= DECODER_RETRY_COOLDOWN_MS) {
                    configureVideoDecoderLocked(videoMime, remoteWidth, remoteHeight, pendingCsd!!)
                }
            }
            videoCodec
        } ?: return

        val isKey = (flags and FLAG_VIDEO_KEYFRAME) != 0
        if (awaitingInitialKeyFrame) {
            if (!isKey) {
                onRequestKeyFrameThrottled()
                return
            }
            awaitingInitialKeyFrame = false
        }

        try {
            val inIdx = codec.dequeueInputBuffer(12_000L)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)
                if (buf != null && buf.capacity() >= length) {
                    buf.clear()
                    buf.put(payload, 0, length)
                    val mcFlags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(inIdx, 0, length, tsUs, mcFlags)
                } else {
                    codec.queueInputBuffer(inIdx, 0, 0, tsUs, 0)
                }
            } else {
                onRequestKeyFrameThrottled()
            }
        } catch (t: Throwable) {
            val nowMs = SystemClock.uptimeMillis()
            val prevMs = lastVideoDecodeErrLogMs.get()
            if (prevMs == 0L || nowMs - prevMs >= 5_000L) {
                if (lastVideoDecodeErrLogMs.compareAndSet(prevMs, nowMs)) {
                    Log.w(TAG, "Video decode frame error", t)
                }
            }
            synchronized(videoLock) {
                if (videoCodec === codec) releaseVideoDecoderLocked()
            }
            onRequestKeyFrame()
        }
    }

    fun handleAudioConfig(payload: ByteArray) {
        if (payload.size < 6) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        bb.get()
        val sampleRate = bb.int.coerceIn(16_000, 96_000)
        val channels = (bb.get().toInt() and 0xFF).coerceIn(1, 2)
        val asc = if (payload.size > 6) payload.copyOfRange(6, payload.size) else ByteArray(0)

        synchronized(audioLock) {
            releaseAudioDecoderLocked()
            try {
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                if (asc.isNotEmpty()) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                codec.configure(fmt, null, null, 0)
                codec.start()

                val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
                    .coerceAtLeast(4096)
                val trackBufBytes = ReceiverPolicy.computeAudioTrackBufferBytes(minBuf, sampleRate, channels)
                val track = AndroidApiCompat.createAudioTrack(sampleRate, channels, trackBufBytes)
                audioCodec = codec
                audioTrack = track
                audioTrackPlaying = false
                audioPrefillFrames = 0
            } catch (t: Throwable) {
                Log.w(TAG, "Audio decoder init failed", t)
                releaseAudioDecoderLocked()
            }
        }
    }

    fun releaseAll() {
        synchronized(videoLock) { releaseVideoDecoderLocked() }
        synchronized(audioLock) { releaseAudioDecoderLocked() }
    }

    private fun configureVideoDecoderLocked(mime: String, width: Int, height: Int, csd: ByteArray) {
        val surf = outputSurface
        if (surf == null || !surf.isValid) {
            videoConfigured = false
            return
        }
        releaseVideoDecoderLocked()
        AndroidApiCompat.configureSurfaceFrameRate(surf, remoteFps)

        val preferred = AndroidApiCompat.selectPreferredDecoder(mime)
        val candidates = ArrayList<Pair<String?, Boolean>>(3).apply {
            if (preferred.hwName != null) add(preferred.hwName to preferred.supportsLowLatency)
            add(null to false)
            if (preferred.swName != null && preferred.swName != preferred.hwName) {
                add(preferred.swName to false)
            }
        }
        var lastErr: Throwable? = null
        for ((codecName, supportsLowLatency) in candidates) {
            val plans = UplinkCoalescer.buildDecoderConfigPlans(remoteFps, supportsLowLatency, Build.VERSION.SDK_INT)
            for (plan in plans) {
                var codec: MediaCodec? = null
                try {
                    codec = if (codecName != null) {
                        MediaCodec.createByCodecName(codecName)
                    } else {
                        MediaCodec.createDecoderByType(mime)
                    }
                    val fmt = MediaFormat.createVideoFormat(mime, width, height)
                    AndroidApiCompat.applyVideoDecoderPlan(fmt, plan)
                    if (csd.isNotEmpty()) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                    codec.configure(fmt, surf, null, 0)
                    codec.start()
                    videoCodec = codec
                    configuredVideoW = width
                    configuredVideoH = height
                    videoConfigured = true
                    awaitingInitialKeyFrame = true
                    lastDecoderConfigureFailMs = 0L
                    startVideoDrainThreadLocked(codec)
                    return
                } catch (t: Throwable) {
                    lastErr = t
                    try { codec?.release() } catch (_: Throwable) {}
                }
            }
        }
        lastDecoderConfigureFailMs = SystemClock.uptimeMillis()
        Log.w(TAG, "Video decoder configure failed ($mime ${width}x${height})", lastErr)
        releaseVideoDecoderLocked()
    }

    private fun startVideoDrainThreadLocked(codec: MediaCodec) {
        videoDrainThread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
            val info = MediaCodec.BufferInfo()
            while (isActive() && videoCodec === codec) {
                try {
                    var latestIdx = codec.dequeueOutputBuffer(info, 12_000L)
                    if (latestIdx >= 0) {
                        while (true) {
                            val nextIdx = codec.dequeueOutputBuffer(info, 0L)
                            if (nextIdx >= 0) {
                                codec.releaseOutputBuffer(latestIdx, false)
                                latestIdx = nextIdx
                            } else {
                                break
                            }
                        }
                        codec.releaseOutputBuffer(latestIdx, true)
                    }
                } catch (_: Throwable) {
                    break
                }
            }
        }, "OpalCastVideoOut").apply {
            isDaemon = true
            start()
        }
    }

    private fun audioDecodeLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        while (isActive()) {
            try {
                val item = audioQueue.poll(AUDIO_IDLE_PAUSE_MS, TimeUnit.MILLISECONDS)
                if (item == null) {
                    pauseAudioTrackOnIdle()
                    continue
                }
                handleAudioFrame(item.first, item.second)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
            }
        }
    }

    private fun pauseAudioTrackOnIdle() {
        synchronized(audioLock) {
            val track = audioTrack ?: return
            if (audioTrackPlaying) {
                try {
                    track.pause()
                    track.flush()
                } catch (_: Throwable) {
                }
                audioTrackPlaying = false
                audioPrefillFrames = 0
            }
        }
    }

    private fun handleAudioFrame(payload: ByteArray, tsUs: Long) {
        if (payload.isEmpty()) return
        synchronized(audioLock) {
            val codec = audioCodec ?: return
            val track = audioTrack ?: return
            try {
                val inIdx = codec.dequeueInputBuffer(8_000L)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf != null && buf.capacity() >= payload.size) {
                        buf.clear()
                        buf.put(payload)
                        codec.queueInputBuffer(inIdx, 0, payload.size, tsUs, 0)
                    }
                }
                val info = audioOutBufInfo
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (!audioTrackPlaying) {
                                writePcmBlocking(track, outBuf, info.size)
                                audioPrefillFrames++
                                if (audioPrefillFrames >= AUDIO_PREFILL_FRAMES) {
                                    track.play()
                                    audioTrackPlaying = true
                                }
                            } else {
                                writePcmBlocking(track, outBuf, info.size)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    } else {
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun writePcmBlocking(track: AudioTrack, src: ByteBuffer, totalBytes: Int) {
        var remaining = totalBytes
        while (remaining > 0 && isActive()) {
            val written = track.write(src, remaining, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            remaining -= written
        }
    }

    private fun releaseVideoDecoderLocked() {
        videoConfigured = false
        val c = videoCodec
        videoCodec = null
        val t = videoDrainThread
        videoDrainThread = null
        t?.interrupt()
        if (c != null) {
            try { c.stop() } catch (_: Throwable) {}
            try { c.release() } catch (_: Throwable) {}
        }
    }

    private fun releaseAudioDecoderLocked() {
        audioQueue.clear()
        audioTrackPlaying = false
        audioPrefillFrames = 0
        val t = audioTrack
        audioTrack = null
        if (t != null) {
            try { t.stop() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
        }
        val c = audioCodec
        audioCodec = null
        if (c != null) {
            try { c.stop() } catch (_: Throwable) {}
            try { c.release() } catch (_: Throwable) {}
        }
    }
}
