package io.github.lengsukq.opaldesk.daemon.audio;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class AudioRoutingPolicyTest {

    @Test
    public void standaloneOnlySessionAcquiresAudioOwnership() {
        List<AudioRoutingPolicy.AudioSessionCandidate> sessions = Collections.singletonList(
                new AudioRoutingPolicy.AudioSessionCandidate(11, true, true));

        assertEquals(11, AudioRoutingPolicy.selectAudioOwnerSessionId(-1, -1, sessions));
    }

    @Test
    public void multipleStandaloneSessionsKeepSingleStickyOwnerAndHandoffOnClose() {
        List<AudioRoutingPolicy.AudioSessionCandidate> twoStandalones = Arrays.asList(
                new AudioRoutingPolicy.AudioSessionCandidate(11, true, true),
                new AudioRoutingPolicy.AudioSessionCandidate(12, true, true));

        // First standalone stays sticky owner so PC never gets double-echo audio.
        assertEquals(11, AudioRoutingPolicy.selectAudioOwnerSessionId(11, -1, twoStandalones));

        // Closing session 11 seamlessly hands off audio ownership to remaining standalone session 12.
        List<AudioRoutingPolicy.AudioSessionCandidate> afterClose = Collections.singletonList(
                new AudioRoutingPolicy.AudioSessionCandidate(12, true, true));
        assertEquals(12, AudioRoutingPolicy.selectAudioOwnerSessionId(11, -1, afterClose));
    }

    @Test
    public void desktopTakesPriorityWhenUnmutedAndFallsBackToStandaloneWhenMutedOrClosed() {
        List<AudioRoutingPolicy.AudioSessionCandidate> desktopAndStandalone = Arrays.asList(
                new AudioRoutingPolicy.AudioSessionCandidate(10, true, true),
                new AudioRoutingPolicy.AudioSessionCandidate(20, false, true));

        // Desktop prefers ownership when audio is enabled.
        assertEquals(20, AudioRoutingPolicy.selectAudioOwnerSessionId(10, 20, desktopAndStandalone));

        // If desktop is muted, standalone window takes over audio playback.
        List<AudioRoutingPolicy.AudioSessionCandidate> mutedDesktop = Arrays.asList(
                new AudioRoutingPolicy.AudioSessionCandidate(10, true, true),
                new AudioRoutingPolicy.AudioSessionCandidate(20, false, false));
        assertEquals(10, AudioRoutingPolicy.selectAudioOwnerSessionId(20, 20, mutedDesktop));

        // If desktop disconnects while standalone remains open, standalone regains ownership.
        List<AudioRoutingPolicy.AudioSessionCandidate> standaloneRemaining = Collections.singletonList(
                new AudioRoutingPolicy.AudioSessionCandidate(10, true, true));
        assertEquals(10, AudioRoutingPolicy.selectAudioOwnerSessionId(20, -1, standaloneRemaining));
    }

    @Test
    public void returnsNegativeOneWhenAllSessionsMuteOrDisconnect() {
        List<AudioRoutingPolicy.AudioSessionCandidate> allMuted = Arrays.asList(
                new AudioRoutingPolicy.AudioSessionCandidate(10, true, false),
                new AudioRoutingPolicy.AudioSessionCandidate(20, false, false));

        assertEquals(-1, AudioRoutingPolicy.selectAudioOwnerSessionId(20, 20, allMuted));
        assertEquals(-1, AudioRoutingPolicy.selectAudioOwnerSessionId(10, -1, Collections.emptyList()));
    }
}
