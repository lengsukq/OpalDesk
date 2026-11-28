package io.github.lengsukq.opalcast.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API 21–37 compatible 16 kHz mono PCM16 microphone capturer for OpalCast voice uplink.
 */
internal class CastMicCapturer(
    private val context: Context,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val onPcmFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "OpalCastMic"
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val DEFAULT_CHANNELS = 1
        private const val FRAME_MS = 20
    }

    private val running = AtomicBoolean(false)
    @Volatile
    private var recorder: AudioRecord? = null
    @Volatile
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val chunkBytes = ((sampleRate * FRAME_MS) / 1000) * 2
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(chunkBytes * 2)
        val bufSize = maxOf(minBuf * 2, chunkBytes * 4)

        val rec = try {
            AndroidApiCompat.createAudioRecord(sampleRate, bufSize)
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init failed", t)
            running.set(false)
            onError(context.getString(R.string.mic_err_init_fmt, t.message ?: t.javaClass.simpleName))
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            running.set(false)
            onError(context.getString(R.string.mic_err_permission))
            return false
        }

        return try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord not recording")
            }
            recorder = rec
            thread = Thread({ captureLoop(rec, chunkBytes) }, "OpalCastMicTx").apply {
                isDaemon = true
                start()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startRecording failed", t)
            try { rec.release() } catch (_: Throwable) {}
            recorder = null
            running.set(false)
            onError(context.getString(R.string.mic_err_start_fmt, t.message ?: t.javaClass.simpleName))
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val rec = recorder
        recorder = null
        if (rec != null) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(250L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun captureLoop(rec: AudioRecord, chunkBytes: Int) {
        val buf = ByteArray(chunkBytes)
        var consecutiveErrors = 0
        while (running.get()) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (t: Throwable) {
                Log.w(TAG, "Mic read error", t)
                break
            }
            if (n < 0) {
                consecutiveErrors++
                if (consecutiveErrors >= 5) {
                    onError(context.getString(R.string.mic_err_read_fmt, n))
                    break
                }
                continue
            }
            consecutiveErrors = 0
            if (n > 0) onPcmFrame(buf.copyOf(n))
        }
        if (running.compareAndSet(true, false)) {
            val r = recorder
            recorder = null
            if (r != null) {
                try { r.stop() } catch (_: Throwable) {}
                try { r.release() } catch (_: Throwable) {}
            }
        }
    }
}
