package io.github.lengsukq.opaldesk.daemon.audio;

/**
 * Lightweight PCM16-LE silence detector with hangover window for Discontinuous Transmission (DTX).
 *
 * <p>Eliminates continuous software AAC encoding and 47 Hz Wi-Fi TX radio wakeups when no audio is
 * playing, while preserving zero-latency attack on the first audible sample and a smooth tail
 * hangover so AAC MDCT overlap-add drains cleanly to zero without clicks.
 */
public final class AudioSilenceGate {
    /** ~ -66 dBFS peak amplitude threshold for signed 16-bit PCM (0..32767). */
    public static final int DEFAULT_SILENCE_THRESHOLD = 16;
    /** ~341 ms at 48 kHz / 1024-sample frames (guarantees initial AUDIO_CONFIG & click-free tail). */
    public static final int DEFAULT_HANGOVER_FRAMES = 16;

    private final int silenceThreshold;
    private final int hangoverFrames;
    private int consecutiveSilentFrames = 0;

    public AudioSilenceGate() {
        this(DEFAULT_SILENCE_THRESHOLD, DEFAULT_HANGOVER_FRAMES);
    }

    public AudioSilenceGate(int silenceThreshold, int hangoverFrames) {
        this.silenceThreshold = Math.max(0, silenceThreshold);
        this.hangoverFrames = Math.max(1, hangoverFrames);
    }

    public void reset() {
        consecutiveSilentFrames = 0;
    }

    public boolean isSuppressed() {
        return consecutiveSilentFrames > hangoverFrames;
    }

    /**
     * Evaluates one captured PCM16 little-endian buffer and returns {@code true} if the frame
     * should be fed to the AAC encoder and transmitted, or {@code false} if DTX may suppress it.
     */
    public boolean shouldTransmit(byte[] pcm16Le, int lengthBytes) {
        if (hasAudibleSignal(pcm16Le, lengthBytes, silenceThreshold)) {
            consecutiveSilentFrames = 0;
            return true;
        }
        if (consecutiveSilentFrames <= hangoverFrames) {
            consecutiveSilentFrames++;
        }
        return consecutiveSilentFrames <= hangoverFrames;
    }

    public static boolean hasAudibleSignal(byte[] pcm16Le, int lengthBytes, int threshold) {
        if (pcm16Le == null || lengthBytes < 2) {
            return false;
        }
        int limit = Math.min(lengthBytes, pcm16Le.length) & ~1;
        int safeThreshold = Math.max(0, threshold);
        for (int i = 0; i < limit; i += 2) {
            int sample = (pcm16Le[i] & 0xFF) | (pcm16Le[i + 1] << 8);
            if (sample > safeThreshold || sample < -safeThreshold) {
                return true;
            }
        }
        return false;
    }
}
