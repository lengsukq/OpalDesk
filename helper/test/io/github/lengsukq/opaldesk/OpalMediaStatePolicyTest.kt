package io.github.lengsukq.opaldesk

import android.media.session.PlaybackState
import io.github.lengsukq.opaldesk.notifications.MediaPlaybackSnapshot
import io.github.lengsukq.opaldesk.notifications.OpalMediaStatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpalMediaStatePolicyTest {

    @Test
    fun `prioritizes actively playing session with metadata and ignores self package`() {
        val candidates = listOf(
            OpalMediaStatePolicy.SessionCandidate(
                id = "0",
                pkg = "io.github.lengsukq.opaldesk",
                playbackStateCode = PlaybackState.STATE_PLAYING,
                hasMetadata = true,
            ),
            OpalMediaStatePolicy.SessionCandidate(
                id = "1",
                pkg = "com.spotify.music",
                playbackStateCode = PlaybackState.STATE_PAUSED,
                hasMetadata = true,
            ),
            OpalMediaStatePolicy.SessionCandidate(
                id = "2",
                pkg = "com.netease.cloudmusic",
                playbackStateCode = PlaybackState.STATE_PLAYING,
                hasMetadata = true,
            ),
        )
        assertEquals(2, OpalMediaStatePolicy.selectPrimaryCandidateIndex(candidates))

        val onlySelf = listOf(candidates[0])
        assertEquals(-1, OpalMediaStatePolicy.selectPrimaryCandidateIndex(onlySelf))
    }

    @Test
    fun `extrapolates playing position and clamps to duration`() {
        assertEquals(
            15_000L,
            OpalMediaStatePolicy.computeEffectivePositionMs(
                basePositionMs = 10_000L,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 6_000L,
                playbackSpeed = 1.0f,
                playing = true,
                durationMs = 180_000L,
            ),
        )
        // Paused does not advance
        assertEquals(
            10_000L,
            OpalMediaStatePolicy.computeEffectivePositionMs(
                basePositionMs = 10_000L,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 6_000L,
                playbackSpeed = 1.0f,
                playing = false,
                durationMs = 180_000L,
            ),
        )
        // Clamped to track duration
        assertEquals(
            180_000L,
            OpalMediaStatePolicy.computeEffectivePositionMs(
                basePositionMs = 175_000L,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 20_000L,
                playbackSpeed = 1.0f,
                playing = true,
                durationMs = 180_000L,
            ),
        )
    }

    @Test
    fun `scales artwork dimensions within 128px cap and enforces 64 KiB base64 ceiling`() {
        assertEquals(128 to 128, OpalMediaStatePolicy.computeArtworkTargetSize(512, 512))
        assertEquals(128 to 64, OpalMediaStatePolicy.computeArtworkTargetSize(400, 200))
        assertEquals(96 to 96, OpalMediaStatePolicy.computeArtworkTargetSize(96, 96))

        val oversizedB64 = "A".repeat(OpalMediaStatePolicy.MAX_ARTWORK_BASE64_BYTES + 1)
        val jsonOversized = OpalMediaStatePolicy.encodeJson(
            MediaPlaybackSnapshot(
                active = true,
                pkg = "com.netease.cloudmusic",
                appLabel = "网易云音乐",
                title = "晴天",
                artist = "周杰伦",
                album = "叶惠美",
                playing = true,
                positionMs = 12_345L,
                durationMs = 269_000L,
                artworkBase64 = oversizedB64,
            ),
        )
        assertTrue(jsonOversized.contains("\"artworkBase64\":\"\""))
        assertTrue(jsonOversized.contains("\"active\":true"))
        assertTrue(jsonOversized.contains("\"playing\":true"))
        assertTrue(jsonOversized.contains("\"durationMs\":269000"))

        assertTrue(OpalMediaStatePolicy.isValidMediaActionOp("play"))
        assertTrue(OpalMediaStatePolicy.isValidMediaActionOp("seek"))
        assertFalse(OpalMediaStatePolicy.isValidMediaActionOp("eject"))
    }
}
