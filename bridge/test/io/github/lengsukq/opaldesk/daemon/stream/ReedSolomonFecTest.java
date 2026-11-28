package io.github.lengsukq.opaldesk.daemon.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Random;

public class ReedSolomonFecTest {

    @Test
    public void recoversAllArbitraryLossCombinationsUpToParityCount() {
        int dataCount = 6;
        int parityCount = 3;
        int shardLen = 257;
        byte[][] data = randomShards(dataCount, shardLen, 42L);
        byte[][] parity = ReedSolomonFec.encodeParity(data, parityCount);
        assertEquals(parityCount, parity.length);

        int total = dataCount + parityCount;
        byte[][] all = combine(data, parity);

        // Exhaustively test every subset of size 1..parityCount lost shards (data and/or parity).
        for (int mask = 1; mask < (1 << total); mask++) {
            int lost = Integer.bitCount(mask);
            if (lost > parityCount) continue;
            byte[][] rx = new byte[total][];
            boolean[] present = new boolean[total];
            for (int i = 0; i < total; i++) {
                if ((mask & (1 << i)) == 0) {
                    rx[i] = all[i];
                    present[i] = true;
                }
            }
            byte[][] recovered = ReedSolomonFec.decode(rx, present, dataCount, parityCount);
            assertEquals(dataCount, recovered.length);
            for (int d = 0; d < dataCount; d++) {
                assertArrayEquals("Mismatch for loss mask=" + mask + " shard=" + d, data[d], recovered[d]);
            }
        }
    }

    @Test
    public void recoversLargeVideoFrameShardsAndAudioSingleShardDuplication() {
        // 16 data shards + 4 parity shards (typical ~18 KiB video frame at 1180B/shard)
        byte[][] videoData = randomShards(16, OpalCastUdpMediaChannel.MAX_SHARD_BYTES, 99L);
        byte[][] videoParity = ReedSolomonFec.encodeParity(videoData, 4);
        byte[][] all = combine(videoData, videoParity);
        boolean[] present = new boolean[20];
        for (int i = 0; i < 20; i++) present[i] = true;
        // Drop 4 data shards (including first and last)
        present[0] = false;
        present[5] = false;
        present[11] = false;
        present[15] = false;
        all[0] = null;
        all[5] = null;
        all[11] = null;
        all[15] = null;
        byte[][] recovered = ReedSolomonFec.decode(all, present, 16, 4);
        for (int d = 0; d < 16; d++) {
            assertArrayEquals(videoData[d], recovered[d]);
        }

        // 1 data shard + 1 parity shard (audio redundancy)
        byte[][] audioData = randomShards(1, 320, 7L);
        byte[][] audioParity = ReedSolomonFec.encodeParity(audioData, 1);
        byte[][] audioAll = new byte[][]{null, audioParity[0]};
        boolean[] audioPresent = new boolean[]{false, true};
        byte[][] audioRecovered = ReedSolomonFec.decode(audioAll, audioPresent, 1, 1);
        assertArrayEquals(audioData[0], audioRecovered[0]);
    }

    @Test
    public void fastPathWhenAllDataShardsPresentAndRejectsInsufficientShards() {
        byte[][] data = randomShards(4, 128, 123L);
        byte[][] parity = ReedSolomonFec.encodeParity(data, 2);
        byte[][] all = combine(data, parity);
        boolean[] onlyData = new boolean[]{true, true, true, true, false, false};
        byte[][] out = ReedSolomonFec.decode(all, onlyData, 4, 2);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(data[i], out[i]);
        }

        // Lose 3 shards when parityCount is 2 -> must throw IllegalArgumentException
        boolean[] tooFew = new boolean[]{false, false, false, true, true, true};
        try {
            ReedSolomonFec.decode(all, tooFew, 4, 2);
            fail("Expected IllegalArgumentException when fewer than dataCount shards are present");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void zeroParityReturnsEmptyParityAndDecodesIdentity() {
        byte[][] data = randomShards(3, 64, 55L);
        byte[][] parity = ReedSolomonFec.encodeParity(data, 0);
        assertEquals(0, parity.length);
        byte[][] out = ReedSolomonFec.decode(data, new boolean[]{true, true, true}, 3, 0);
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(data[i], out[i]);
        }
    }

    private static byte[][] randomShards(int count, int len, long seed) {
        Random rng = new Random(seed);
        byte[][] out = new byte[count][len];
        for (int i = 0; i < count; i++) {
            rng.nextBytes(out[i]);
        }
        return out;
    }

    private static byte[][] combine(byte[][] data, byte[][] parity) {
        byte[][] all = new byte[data.length + parity.length][];
        System.arraycopy(data, 0, all, 0, data.length);
        System.arraycopy(parity, 0, all, data.length, parity.length);
        return all;
    }
}
