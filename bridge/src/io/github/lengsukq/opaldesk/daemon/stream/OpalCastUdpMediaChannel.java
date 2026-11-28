package io.github.lengsukq.opaldesk.daemon.stream;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional low-latency UDP media transport for OpalCast video/audio frames using
 * 20-byte {@code OPU1} headers, AES-128-GCM encryption, and GF(2^8) Reed-Solomon FEC.
 * Transparently falls back to TCP/TLS until the client completes a UDP punch handshake
 * (or whenever a frame exceeds single-generation FEC capacity).
 */
public final class OpalCastUdpMediaChannel implements Closeable {
    public static final int MAGIC = 0x4F505531; // "OPU1"
    public static final int HEADER_SIZE = 20;
    public static final int MEDIA_TYPE_VIDEO = 0x02;
    public static final int MEDIA_TYPE_AUDIO = 0x04;
    public static final int MEDIA_TYPE_PUNCH = 0x7F;

    public static final int FLAG_KEYFRAME = 0x01;
    public static final int FLAG_PARITY = 0x02;
    public static final int FLAG_CONFIG_INLINE = 0x04;

    public static final int MAX_SHARD_BYTES = 1180;
    public static final int MAX_DATA_SHARDS = 64;
    public static final int MAX_PARITY_SHARDS = 16;
    public static final int DEFAULT_FEC_PCT = 20;
    public static final long DEFAULT_PUNCH_TIMEOUT_MS = 1_500L;

    private static final int AES_KEY_BYTES = 16;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int REALTIME_DSCP_EF = 0xB8;
    private static final int PUNCH_POLL_TIMEOUT_MS = 250;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final class DecodedFrame {
        public final int sessionId;
        public final int frameSeq;
        public final int mediaType;
        public final int headerFlags;
        public final int mediaFlags;
        public final long ptsUs;
        public final byte[] payload;

        DecodedFrame(
                int sessionId, int frameSeq, int mediaType,
                int headerFlags, int mediaFlags, long ptsUs, byte[] payload) {
            this.sessionId = sessionId;
            this.frameSeq = frameSeq;
            this.mediaType = mediaType;
            this.headerFlags = headerFlags;
            this.mediaFlags = mediaFlags;
            this.ptsUs = ptsUs;
            this.payload = payload;
        }
    }

    private final int sessionId;
    private final byte[] aesKey;
    private final String keyBase64;
    private final int fecPct;
    private final long punchTimeoutMs;
    private final long createdAtMs;
    private final DatagramSocket socket;
    private final AtomicInteger frameSeqCounter = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile InetSocketAddress peerAddress;
    private volatile long lastPunchMs;
    private Thread punchThread;

    private OpalCastUdpMediaChannel(
            int sessionId, byte[] aesKey, int fecPct, int sendBufBytes, long punchTimeoutMs) throws SocketException {
        this.sessionId = sessionId;
        this.aesKey = Arrays.copyOf(aesKey, AES_KEY_BYTES);
        this.keyBase64 = Base64.getEncoder().encodeToString(this.aesKey);
        this.fecPct = clampFecPct(fecPct);
        this.punchTimeoutMs = Math.max(200L, punchTimeoutMs);
        this.createdAtMs = System.nanoTime() / 1_000_000L;
        this.socket = new DatagramSocket(0);
        try { this.socket.setTrafficClass(REALTIME_DSCP_EF); } catch (SocketException ignored) {}
        if (sendBufBytes > 0) {
            try { this.socket.setSendBufferSize(sendBufBytes); } catch (SocketException ignored) {}
        }
        this.socket.setSoTimeout(PUNCH_POLL_TIMEOUT_MS);
    }

    public static OpalCastUdpMediaChannel create(int fecPct, int sendBufBytes) throws SocketException {
        return create(fecPct, sendBufBytes, DEFAULT_PUNCH_TIMEOUT_MS);
    }

    static OpalCastUdpMediaChannel create(int fecPct, int sendBufBytes, long punchTimeoutMs) throws SocketException {
        int sid = 0;
        while (sid == 0) {
            sid = SECURE_RANDOM.nextInt() & 0x7FFFFFFF;
        }
        byte[] key = new byte[AES_KEY_BYTES];
        SECURE_RANDOM.nextBytes(key);
        return new OpalCastUdpMediaChannel(sid, key, fecPct, sendBufBytes, punchTimeoutMs);
    }

    public static int clampFecPct(int fecPct) {
        if (fecPct < 0) return DEFAULT_FEC_PCT;
        return Math.min(100, fecPct);
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    public int getSessionId() {
        return sessionId;
    }

    public String getKeyBase64() {
        return keyBase64;
    }

    public int getFecPct() {
        return fecPct;
    }

    public boolean isPunched() {
        return peerAddress != null && !closed.get();
    }

    public boolean isReadyForMedia() {
        return !closed.get() && peerAddress != null;
    }

    public InetSocketAddress getPeerAddress() {
        return peerAddress;
    }

    public void updateSendBufferBytes(int sendBufBytes) {
        if (closed.get() || sendBufBytes <= 0) return;
        try { socket.setSendBufferSize(sendBufBytes); } catch (SocketException ignored) {}
    }

    public void startPunchListener() {
        punchThread = new Thread(this::punchListenLoop, "OpalCastUdpPunch-" + sessionId);
        punchThread.setDaemon(true);
        punchThread.start();
    }

    private void punchListenLoop() {
        byte[] buf = new byte[64];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (!closed.get()) {
            try {
                pkt.setLength(buf.length);
                socket.receive(pkt);
                if (pkt.getLength() >= HEADER_SIZE && isValidPunchPacket(buf, pkt.getLength(), sessionId)) {
                    InetSocketAddress remote = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
                    this.peerAddress = remote;
                    this.lastPunchMs = System.nanoTime() / 1_000_000L;
                    byte[] ack = buildPunchPacket(sessionId, 0);
                    try {
                        socket.send(new DatagramPacket(ack, ack.length, remote));
                    } catch (IOException ignored) {}
                }
            } catch (SocketTimeoutException ste) {
                long nowMs = System.nanoTime() / 1_000_000L;
                if (peerAddress == null && (nowMs - createdAtMs) >= punchTimeoutMs) {
                    return;
                }
            } catch (IOException ioe) {
                if (closed.get()) return;
            }
        }
    }

    public boolean trySendMediaFrame(
            int opalCastType, int mediaFlags, long ptsUs, byte[] payload, int payloadLen) {
        InetSocketAddress target = this.peerAddress;
        if (closed.get() || target == null) return false;
        int mediaType;
        if (opalCastType == OpalCastProtocol.TYPE_VIDEO_FRAME) {
            mediaType = MEDIA_TYPE_VIDEO;
        } else if (opalCastType == OpalCastProtocol.TYPE_AUDIO_FRAME) {
            mediaType = MEDIA_TYPE_AUDIO;
        } else {
            return false;
        }
        int seq = frameSeqCounter.incrementAndGet();
        byte[][] packets;
        try {
            packets = buildFramePackets(
                    sessionId, seq, mediaType, mediaFlags, ptsUs, payload, payloadLen, aesKey, fecPct);
        } catch (GeneralSecurityException e) {
            return false;
        }
        if (packets == null) {
            return false;
        }
        try {
            for (byte[] p : packets) {
                socket.send(new DatagramPacket(p, p.length, target));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static byte[] buildPunchPacket(int sessionId, int seq) {
        byte[] pkt = new byte[HEADER_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(MAGIC);
        bb.putInt(sessionId);
        bb.putInt(seq);
        bb.put((byte) MEDIA_TYPE_PUNCH);
        bb.put((byte) 0);
        bb.put((byte) 0);
        bb.put((byte) 1);
        bb.put((byte) 0);
        bb.putShort((short) 0);
        bb.put((byte) 0);
        return pkt;
    }

    static boolean isValidPunchPacket(byte[] buf, int len, int expectedSessionId) {
        if (buf == null || len < HEADER_SIZE) return false;
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        int magic = bb.getInt();
        int sid = bb.getInt();
        bb.getInt();
        int mediaType = bb.get() & 0xFF;
        return magic == MAGIC && sid == expectedSessionId && mediaType == MEDIA_TYPE_PUNCH;
    }

    public static byte[] buildGcmIv(int sessionId, int frameSeq, int mediaType) {
        byte[] iv = new byte[GCM_IV_BYTES];
        iv[0] = (byte) (sessionId >>> 24);
        iv[1] = (byte) (sessionId >>> 16);
        iv[2] = (byte) (sessionId >>> 8);
        iv[3] = (byte) sessionId;
        iv[4] = (byte) (frameSeq >>> 24);
        iv[5] = (byte) (frameSeq >>> 16);
        iv[6] = (byte) (frameSeq >>> 8);
        iv[7] = (byte) frameSeq;
        iv[8] = (byte) (mediaType & 0xFF);
        return iv;
    }

    public static int resolveParityCount(int mediaType, int dataShards, int fecPct) {
        int clamped = Math.max(0, Math.min(100, fecPct));
        if (clamped == 0) return 0;
        if (mediaType == MEDIA_TYPE_AUDIO) {
            return 1;
        }
        if (dataShards >= 2) {
            return Math.min(MAX_PARITY_SHARDS, Math.max(1, (dataShards * clamped + 99) / 100));
        }
        return 0;
    }

    public static byte[][] buildFramePackets(
            int sessionId, int frameSeq, int mediaType, int mediaFlags,
            long ptsUs, byte[] rawFrame, int rawLen, byte[] aesKey, int fecPct)
            throws GeneralSecurityException {
        int safeLen = (rawFrame != null) ? Math.max(0, Math.min(rawLen, rawFrame.length)) : 0;
        byte[] plain = new byte[9 + safeLen];
        ByteBuffer pb = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN);
        pb.putLong(ptsUs);
        pb.put((byte) (mediaFlags & 0xFF));
        if (safeLen > 0) {
            pb.put(rawFrame, 0, safeLen);
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, buildGcmIv(sessionId, frameSeq, mediaType)));
        byte[] cipherAndTag = cipher.doFinal(plain);

        int framedLen = 4 + cipherAndTag.length;
        byte[] framed = new byte[framedLen];
        ByteBuffer.wrap(framed).order(ByteOrder.BIG_ENDIAN).putInt(cipherAndTag.length);
        System.arraycopy(cipherAndTag, 0, framed, 4, cipherAndTag.length);

        int dataShards = (framedLen <= MAX_SHARD_BYTES)
                ? 1 : ((framedLen + MAX_SHARD_BYTES - 1) / MAX_SHARD_BYTES);
        if (dataShards > MAX_DATA_SHARDS) {
            return null;
        }
        int shardSize = (framedLen + dataShards - 1) / dataShards;
        byte[][] data = new byte[dataShards][shardSize];
        for (int i = 0; i < dataShards; i++) {
            int off = i * shardSize;
            int copyLen = Math.min(shardSize, framedLen - off);
            if (copyLen > 0) {
                System.arraycopy(framed, off, data[i], 0, copyLen);
            }
        }

        int parityShards = resolveParityCount(mediaType, dataShards, fecPct);
        byte[][] parity = (parityShards > 0)
                ? ReedSolomonFec.encodeParity(data, parityShards) : new byte[0][];

        int baseFlags = 0;
        if (mediaType == MEDIA_TYPE_VIDEO) {
            if ((mediaFlags & OpalCastProtocol.FLAG_VIDEO_KEYFRAME) != 0) baseFlags |= FLAG_KEYFRAME;
            if ((mediaFlags & OpalCastProtocol.FLAG_VIDEO_CONFIG_INLINE) != 0) baseFlags |= FLAG_CONFIG_INLINE;
        }

        int total = dataShards + parityShards;
        byte[][] packets = new byte[total][HEADER_SIZE + shardSize];
        for (int i = 0; i < total; i++) {
            boolean isParity = (i >= dataShards);
            byte[] shard = isParity ? parity[i - dataShards] : data[i];
            ByteBuffer hb = ByteBuffer.wrap(packets[i]).order(ByteOrder.BIG_ENDIAN);
            hb.putInt(MAGIC);
            hb.putInt(sessionId);
            hb.putInt(frameSeq);
            hb.put((byte) (mediaType & 0xFF));
            hb.put((byte) ((isParity ? (baseFlags | FLAG_PARITY) : baseFlags) & 0xFF));
            hb.put((byte) (i & 0xFF));
            hb.put((byte) (dataShards & 0xFF));
            hb.put((byte) (parityShards & 0xFF));
            hb.putShort((short) (shardSize & 0xFFFF));
            hb.put((byte) 0);
            hb.put(shard);
        }
        return packets;
    }

    public static DecodedFrame decodeFramePackets(List<byte[]> receivedPackets, byte[] aesKey)
            throws GeneralSecurityException {
        if (receivedPackets == null || receivedPackets.isEmpty()) {
            throw new IllegalArgumentException("No packets supplied");
        }
        ByteBuffer first = ByteBuffer.wrap(receivedPackets.get(0)).order(ByteOrder.BIG_ENDIAN);
        if (receivedPackets.get(0).length < HEADER_SIZE || first.getInt() != MAGIC) {
            throw new IllegalArgumentException("Invalid OPU1 magic");
        }
        int sessionId = first.getInt();
        int frameSeq = first.getInt();
        int mediaType = first.get() & 0xFF;
        int headerFlags = first.get() & ~FLAG_PARITY & 0xFF;
        first.get();
        int dataShards = first.get() & 0xFF;
        int parityShards = first.get() & 0xFF;
        int shardSize = first.getShort() & 0xFFFF;
        int total = dataShards + parityShards;
        byte[][] shards = new byte[total][];
        boolean[] present = new boolean[total];

        for (byte[] pkt : receivedPackets) {
            if (pkt == null || pkt.length < HEADER_SIZE + shardSize) continue;
            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN);
            if (bb.getInt() != MAGIC) continue;
            if (bb.getInt() != sessionId || bb.getInt() != frameSeq) continue;
            if ((bb.get() & 0xFF) != mediaType) continue;
            bb.get();
            int shardIdx = bb.get() & 0xFF;
            if ((bb.get() & 0xFF) != dataShards || (bb.get() & 0xFF) != parityShards) continue;
            int payloadLen = bb.getShort() & 0xFFFF;
            if (payloadLen != shardSize || shardIdx >= total) continue;
            bb.get();
            byte[] shard = new byte[shardSize];
            bb.get(shard);
            shards[shardIdx] = shard;
            present[shardIdx] = true;
        }

        byte[][] recovered = ReedSolomonFec.decode(shards, present, dataShards, parityShards);
        byte[] framed = new byte[dataShards * shardSize];
        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(recovered[i], 0, framed, i * shardSize, shardSize);
        }
        int origTotalLen = ByteBuffer.wrap(framed, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (origTotalLen < 16 || 4 + origTotalLen > framed.length) {
            throw new IllegalArgumentException("Corrupt origTotalLen: " + origTotalLen);
        }
        byte[] cipherAndTag = Arrays.copyOfRange(framed, 4, 4 + origTotalLen);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, buildGcmIv(sessionId, frameSeq, mediaType)));
        byte[] plain = cipher.doFinal(cipherAndTag);
        if (plain.length < 9) {
            throw new IllegalArgumentException("Decrypted media payload too short");
        }
        ByteBuffer pb = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN);
        long ptsUs = pb.getLong();
        int mediaFlags = pb.get() & 0xFF;
        byte[] raw = new byte[plain.length - 9];
        pb.get(raw);
        return new DecodedFrame(sessionId, frameSeq, mediaType, headerFlags, mediaFlags, ptsUs, raw);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        peerAddress = null;
        socket.close();
    }
}
