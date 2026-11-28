package io.github.lengsukq.opaldesk.daemon.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

public class OpalCastUdpMediaChannelTest {

    @Test
    public void verifiesHeaderLayoutIvFormatAndVideoLossRecoveryEndToEnd() throws Exception {
        int sessionId = 0x12345678;
        int frameSeq = 42;
        long ptsUs = 987_654_321L;
        int mediaFlags = OpalCastProtocol.FLAG_VIDEO_KEYFRAME | OpalCastProtocol.FLAG_VIDEO_CONFIG_INLINE;
        byte[] key = new byte[16];
        new Random(7).nextBytes(key);
        byte[] rawVideo = new byte[8_500];
        new Random(11).nextBytes(rawVideo);

        // Verify 12-byte AES-GCM IV format: [sessionId(4B) || frameSeq(4B) || mediaType(1B) || 0,0,0]
        byte[] iv = OpalCastUdpMediaChannel.buildGcmIv(sessionId, frameSeq, OpalCastUdpMediaChannel.MEDIA_TYPE_VIDEO);
        assertEquals(12, iv.length);
        ByteBuffer ivBb = ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN);
        assertEquals(sessionId, ivBb.getInt());
        assertEquals(frameSeq, ivBb.getInt());
        assertEquals(OpalCastUdpMediaChannel.MEDIA_TYPE_VIDEO, ivBb.get() & 0xFF);
        assertEquals(0, ivBb.get());
        assertEquals(0, ivBb.get());
        assertEquals(0, ivBb.get());

        byte[][] packets = OpalCastUdpMediaChannel.buildFramePackets(
                sessionId, frameSeq, OpalCastUdpMediaChannel.MEDIA_TYPE_VIDEO,
                mediaFlags, ptsUs, rawVideo, rawVideo.length, key, 20);
        assertNotNull(packets);

        // Inspect 20-byte OPU1 header on first data shard and first parity shard
        ByteBuffer h0 = ByteBuffer.wrap(packets[0]).order(ByteOrder.BIG_ENDIAN);
        assertEquals(OpalCastUdpMediaChannel.MAGIC, h0.getInt());
        assertEquals(sessionId, h0.getInt());
        assertEquals(frameSeq, h0.getInt());
        assertEquals(OpalCastUdpMediaChannel.MEDIA_TYPE_VIDEO, h0.get() & 0xFF);
        int flags0 = h0.get() & 0xFF;
        assertEquals(OpalCastUdpMediaChannel.FLAG_KEYFRAME | OpalCastUdpMediaChannel.FLAG_CONFIG_INLINE, flags0);
        assertEquals(0, h0.get() & 0xFF); // shardIndex
        int dataShards = h0.get() & 0xFF;
        int parityShards = h0.get() & 0xFF;
        int payloadLen = h0.getShort() & 0xFFFF;
        int reserved = h0.get() & 0xFF;
        assertTrue("Multi-shard video expected", dataShards >= 8);
        assertEquals((dataShards * 20 + 99) / 100, parityShards);
        assertEquals(packets[0].length - OpalCastUdpMediaChannel.HEADER_SIZE, payloadLen);
        assertEquals(0, reserved);
        assertEquals(dataShards + parityShards, packets.length);

        int parityFlags = packets[dataShards][13] & 0xFF;
        assertTrue((parityFlags & OpalCastUdpMediaChannel.FLAG_PARITY) != 0);
        assertTrue((parityFlags & OpalCastUdpMediaChannel.FLAG_KEYFRAME) != 0);
        assertTrue((parityFlags & OpalCastUdpMediaChannel.FLAG_CONFIG_INLINE) != 0);

        // Simulate losing `parityShards` data shards (worst-case recoverable loss)
        List<byte[]> received = new ArrayList<>();
        for (int i = 0; i < packets.length; i++) {
            if (i < parityShards) continue; // drop first parityShards data packets
            received.add(packets[i]);
        }
        OpalCastUdpMediaChannel.DecodedFrame decoded = OpalCastUdpMediaChannel.decodeFramePackets(received, key);
        assertEquals(sessionId, decoded.sessionId);
        assertEquals(frameSeq, decoded.frameSeq);
        assertEquals(OpalCastUdpMediaChannel.MEDIA_TYPE_VIDEO, decoded.mediaType);
        assertEquals(ptsUs, decoded.ptsUs);
        assertEquals(mediaFlags, decoded.mediaFlags);
        assertArrayEquals(rawVideo, decoded.payload);
    }

    @Test
    public void audioFrameSendsOneDataAndOneRedundantShardAndRecoversSingleDrop() throws Exception {
        byte[] key = new byte[16];
        new Random(3).nextBytes(key);
        byte[] aac = new byte[360];
        new Random(5).nextBytes(aac);

        byte[][] packets = OpalCastUdpMediaChannel.buildFramePackets(
                77, 100, OpalCastUdpMediaChannel.MEDIA_TYPE_AUDIO, 0, 555_000L, aac, aac.length, key, 20);
        assertNotNull(packets);
        assertEquals(2, packets.length);
        assertEquals(1, packets[0][15] & 0xFF); // dataShards
        assertEquals(1, packets[0][16] & 0xFF); // parityShards

        // Drop data shard 0, keep only parity shard 1
        List<byte[]> onlyParity = new ArrayList<>();
        onlyParity.add(packets[1]);
        OpalCastUdpMediaChannel.DecodedFrame decoded = OpalCastUdpMediaChannel.decodeFramePackets(onlyParity, key);
        assertEquals(OpalCastUdpMediaChannel.MEDIA_TYPE_AUDIO, decoded.mediaType);
        assertEquals(555_000L, decoded.ptsUs);
        assertArrayEquals(aac, decoded.payload);
    }

    @Test
    public void oversizedFrameReturnsNullToTriggerTransparentTcpFallback() throws Exception {
        byte[] key = new byte[16];
        byte[] huge = new byte[OpalCastUdpMediaChannel.MAX_DATA_SHARDS * OpalCastUdpMediaChannel.MAX_SHARD_BYTES + 1024];
        byte[][] packets = OpalCastUdpMediaChannel.buildFramePackets(
                1, 1, OpalCastUdpMediaChannel.MEDIA_TYPE_VIDEO,
                OpalCastProtocol.FLAG_VIDEO_KEYFRAME, 1_000L, huge, huge.length, key, 20);
        assertNull(packets);
    }

    @Test
    public void liveLoopbackPunchLocksPeerAndDeliversEncryptedFecVideoFrame() throws Exception {
        try (OpalCastUdpMediaChannel channel = OpalCastUdpMediaChannel.create(20, 128 * 1024, 1_500L);
             DatagramSocket clientSock = new DatagramSocket(0)) {
            channel.startPunchListener();
            assertFalse("Must fall back to TCP/TLS before punch", channel.isReadyForMedia());
            assertFalse(channel.trySendMediaFrame(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 100L, new byte[64], 64));

            // Client sends 20B OPU1 punch packet (mediaType = 0x7F)
            byte[] punch = OpalCastUdpMediaChannel.buildPunchPacket(channel.getSessionId(), 1);
            assertEquals(OpalCastUdpMediaChannel.HEADER_SIZE, punch.length);
            clientSock.setSoTimeout(2_000);
            clientSock.send(new DatagramPacket(
                    punch, punch.length, InetAddress.getLoopbackAddress(), channel.getLocalPort()));

            // Receive server's 20B punch ack
            byte[] ackBuf = new byte[64];
            DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
            clientSock.receive(ackPkt);
            assertEquals(OpalCastUdpMediaChannel.HEADER_SIZE, ackPkt.getLength());
            assertTrue(channel.isReadyForMedia());

            // Send a multi-shard video frame over UDP
            byte[] frameBytes = new byte[4_200];
            new Random(99).nextBytes(frameBytes);
            long ptsUs = 123_456_789L;
            assertTrue(channel.trySendMediaFrame(
                    OpalCastProtocol.TYPE_VIDEO_FRAME,
                    OpalCastProtocol.FLAG_VIDEO_KEYFRAME,
                    ptsUs, frameBytes, frameBytes.length));

            // Collect shards on client socket and drop shard 0 to prove live FEC recovery
            List<byte[]> rx = new ArrayList<>();
            byte[] rxBuf = new byte[1500];
            int expectedTotal = -1;
            while (expectedTotal < 0 || rx.size() < expectedTotal) {
                DatagramPacket dp = new DatagramPacket(rxBuf, rxBuf.length);
                clientSock.receive(dp);
                byte[] copy = new byte[dp.getLength()];
                System.arraycopy(rxBuf, 0, copy, 0, dp.getLength());
                if ((copy[12] & 0xFF) == OpalCastUdpMediaChannel.MEDIA_TYPE_PUNCH) continue;
                if (expectedTotal < 0) {
                    expectedTotal = (copy[15] & 0xFF) + (copy[16] & 0xFF);
                }
                rx.add(copy);
            }
            assertTrue(rx.size() >= 5);
            rx.remove(0); // simulate 1 dropped packet

            byte[] decodedKey = Base64.getDecoder().decode(channel.getKeyBase64());
            OpalCastUdpMediaChannel.DecodedFrame recovered =
                    OpalCastUdpMediaChannel.decodeFramePackets(rx, decodedKey);
            assertEquals(channel.getSessionId(), recovered.sessionId);
            assertEquals(OpalCastUdpMediaChannel.MEDIA_TYPE_VIDEO, recovered.mediaType);
            assertEquals(ptsUs, recovered.ptsUs);
            assertEquals(OpalCastProtocol.FLAG_VIDEO_KEYFRAME, recovered.mediaFlags);
            assertArrayEquals(frameBytes, recovered.payload);
        }
    }
}
