package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency 16 kHz mono 16-bit PCM microphone capturer for Android Tablet/Phone receivers.
 * Uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION] so platform hardware AEC/NS prevents
 * echo loops when the tablet is also playing back downlink audio.
 */
internal class PeerMicCapturer(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val onPcmFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "OpalPeerMic"
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val DEFAULT_CHANNELS = 1
        // 20 ms at 16 kHz mono 16-bit PCM = 320 samples = 640 bytes
        private const val FRAME_MS = 20
    }

    private val running = AtomicBoolean(false)
    @Volatile
    private var recorder: AudioRecord? = null
    @Volatile
    private var thread: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    @SuppressLint("MissingPermission")
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
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufSize)
                .build()
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "AudioRecord build failed", t)
            running.set(false)
            onError("无法初始化本地麦克风：${t.message ?: t.javaClass.simpleName}")
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            OpalLogStore.w(TAG, "AudioRecord state not initialized: state=${rec.state}")
            try { rec.release() } catch (_: Throwable) {}
            running.set(false)
            onError("本地麦克风初始化失败，请检查录音权限")
            return false
        }

        return try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING")
            }
            recorder = rec
            val t = Thread({ captureLoop(rec, chunkBytes) }, "OpalPeerMicTx").apply {
                isDaemon = true
                start()
            }
            thread = t
            true
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "AudioRecord startRecording failed", t)
            try { rec.release() } catch (_: Throwable) {}
            recorder = null
            running.set(false)
            onError("启动本地麦克风失败：${t.message ?: t.javaClass.simpleName}")
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
                OpalLogStore.w(TAG, "Mic read error", t)
                break
            }
            if (n < 0) {
                consecutiveErrors++
                if (consecutiveErrors >= 5) {
                    OpalLogStore.w(TAG, "Mic capture stopped after consecutive errors: err=$n")
                    onError("麦克风采集异常 (err=$n)")
                    break
                }
                continue
            }
            consecutiveErrors = 0
            if (n > 0) {
                onPcmFrame(buf.copyOf(n))
            }
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
