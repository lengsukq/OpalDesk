package io.github.lengsukq.opaldesk.daemon.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioSilenceGateTest {

    private static byte[] makePcm16Le(int... samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short s = (short) samples[i];
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
        }
        return out;
    }

    @Test
    public void passesHangoverFramesThenSuppressesSilence() {
        AudioSilenceGate gate = new AudioSilenceGate(16, 4);
        byte[] silence = makePcm16Le(0, 0, 10, -10, 16, -16);

        // First 4 silent frames pass (startup/tail hangover so AUDIO_CONFIG & MDCT tail drain cleanly).
        for (int i = 0; i < 4; i++) {
            assertTrue("Hangover frame " + i + " must transmit", gate.shouldTransmit(silence, silence.length));
            assertFalse(gate.isSuppressed());
        }

        // Frame 5 onward is suppressed by DTX.
        assertFalse(gate.shouldTransmit(silence, silence.length));
        assertTrue(gate.isSuppressed());
        assertFalse(gate.shouldTransmit(silence, silence.length));
        assertTrue(gate.isSuppressed());
    }

    @Test
    public void wakesImmediatelyOnPositiveNegativeAndMinShortSamples() {
        AudioSilenceGate gate = new AudioSilenceGate(16, 2);
        byte[] silence = makePcm16Le(0, 0, 0, 0);

        assertTrue(gate.shouldTransmit(silence, silence.length));
        assertTrue(gate.shouldTransmit(silence, silence.length));
        assertFalse(gate.shouldTransmit(silence, silence.length));
        assertTrue(gate.isSuppressed());

        // Single audible positive sample (> 16) wakes with zero latency.
        byte[] posWake = makePcm16Le(0, 0, 17, 0);
        assertTrue(gate.shouldTransmit(posWake, posWake.length));
        assertFalse(gate.isSuppressed());

        // Tail hangover resets to full 2 frames after audio stops.
        assertTrue(gate.shouldTransmit(silence, silence.length));
        assertTrue(gate.shouldTransmit(silence, silence.length));
        assertFalse(gate.shouldTransmit(silence, silence.length));
        assertTrue(gate.isSuppressed());

        // Negative sample (< -16) and Short.MIN_VALUE (-32768) both wake immediately.
        byte[] negWake = makePcm16Le(0, -17, 0);
        assertTrue(gate.shouldTransmit(negWake, negWake.length));
        assertFalse(gate.isSuppressed());

        // Drive back to suppressed then test Short.MIN_VALUE.
        gate.shouldTransmit(silence, silence.length);
        gate.shouldTransmit(silence, silence.length);
        assertFalse(gate.shouldTransmit(silence, silence.length));

        byte[] minShortWake = makePcm16Le(0, Short.MIN_VALUE);
        assertTrue(gate.shouldTransmit(minShortWake, minShortWake.length));
        assertFalse(gate.isSuppressed());
    }

    @Test
    public void resetClearsSuppressedStateAndHandlesEdgeBuffers() {
        AudioSilenceGate gate = new AudioSilenceGate(16, 1);
        byte[] silence = makePcm16Le(0, 0);
        assertTrue(gate.shouldTransmit(silence, silence.length));
        assertFalse(gate.shouldTransmit(silence, silence.length));
        assertTrue(gate.isSuppressed());

        gate.reset();
        assertFalse(gate.isSuppressed());
        assertTrue(gate.shouldTransmit(silence, silence.length));

        // Edge inputs: null, empty, 1-byte odd buffer, length shorter than array.
        assertFalse(AudioSilenceGate.hasAudibleSignal(null, 10, 16));
        assertFalse(AudioSilenceGate.hasAudibleSignal(new byte[0], 0, 16));
        assertFalse(AudioSilenceGate.hasAudibleSignal(new byte[]{(byte) 0xFF}, 1, 16));

        byte[] mixed = makePcm16Le(0, 5000);
        assertFalse(AudioSilenceGate.hasAudibleSignal(mixed, 2, 16));
        assertTrue(AudioSilenceGate.hasAudibleSignal(mixed, 4, 16));
    }
}
