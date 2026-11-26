package io.github.lengsukq.opaldesk.daemon.stream;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Pure Java 11 RFC 6455 WebSocket handshake & binary/control framing codec.
 * Zero external dependencies; designed for zero-copy server-to-client OpalCast frame writes.
 */
public final class WebSocketCodec {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static final int OPCODE_CONT = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    private static final int MAX_CONTROL_PAYLOAD_LEN = 125;
    private static final int EXTENDED_LEN_16_MARKER = 126;
    private static final int EXTENDED_LEN_64_MARKER = 127;
    private static final int MAX_LEN_7BIT = 125;
    private static final int MAX_LEN_16BIT = 65_535;
    private static final int MASK_KEY_LEN = 4;

    private WebSocketCodec() {}

    public static final class Message {
        public final int opcode;
        public final byte[] payload;

        public Message(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = (payload != null) ? payload : OpalCastProtocol.EMPTY_BYTES;
        }
    }

    /**
     * Computes RFC 6455 §1.3 {@code Sec-WebSocket-Accept} header value from client key.
     */
    public static String computeAcceptKey(String clientKey) {
        if (clientKey == null || clientKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing Sec-WebSocket-Key");
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey.trim() + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /**
     * Reads one complete client-to-server WebSocket data message (binary or text), transparently
     * handling fragmented continuation frames and interleaved PING/PONG/CLOSE control frames.
     *
     * @return decoded message, or {@code null} on clean EOF / CLOSE handshake.
     */
    public static Message readMessage(
            InputStream in,
            OutputStream pongOut,
            Object writeLock,
            int maxPayloadBytes) throws IOException {
        int messageOpcode = -1;
        ByteArrayOutputStream fragmentBuf = null;

        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                if (messageOpcode >= 0) {
                    throw new EOFException("Unexpected EOF mid-fragmented WebSocket message");
                }
                return null;
            }
            int b1 = readByteOrEof(in);
            boolean fin = (b0 & 0x80) != 0;
            int rsv = b0 & 0x70;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            int len7 = b1 & 0x7F;

            if (rsv != 0) {
                throw new OpalCastProtocol.ProtocolException("Unsupported WebSocket RSV bits: " + rsv);
            }
            if (!masked) {
                throw new OpalCastProtocol.ProtocolException("RFC 6455 requires masked client-to-server frames");
            }

            long frameLen = readPayloadLength(in, len7);
            boolean isControl = (opcode & 0x08) != 0;
            if (isControl) {
                if (!fin || frameLen > MAX_CONTROL_PAYLOAD_LEN) {
                    throw new OpalCastProtocol.ProtocolException("Invalid WebSocket control frame");
                }
                byte[] ctrlPayload = readMaskedPayload(in, (int) frameLen);
                if (opcode == OPCODE_CLOSE) {
                    sendCloseBestEffort(pongOut, writeLock, ctrlPayload);
                    return null;
                }
                if (opcode == OPCODE_PING && pongOut != null) {
                    writeControlFrame(pongOut, writeLock, OPCODE_PONG, ctrlPayload);
                }
                // OPCODE_PONG is silently ignored
                continue;
            }

            if (frameLen > maxPayloadBytes) {
                throw new OpalCastProtocol.ProtocolException("WebSocket frame exceeds limit: " + frameLen);
            }
            byte[] chunk = readMaskedPayload(in, (int) frameLen);

            if (opcode == OPCODE_CONT) {
                if (messageOpcode < 0 || fragmentBuf == null) {
                    throw new OpalCastProtocol.ProtocolException("Unexpected WebSocket continuation frame");
                }
                if (fragmentBuf.size() + chunk.length > maxPayloadBytes) {
                    throw new OpalCastProtocol.ProtocolException("Fragmented WebSocket message exceeds limit");
                }
                fragmentBuf.write(chunk);
                if (fin) {
                    return new Message(messageOpcode, fragmentBuf.toByteArray());
                }
                continue;
            }

            if (opcode != OPCODE_BINARY && opcode != OPCODE_TEXT) {
                throw new OpalCastProtocol.ProtocolException("Unsupported WebSocket opcode: " + opcode);
            }
            if (messageOpcode >= 0) {
                throw new OpalCastProtocol.ProtocolException("New data frame received while fragmented message open");
            }
            if (fin) {
                return new Message(opcode, chunk);
            }
            messageOpcode = opcode;
            fragmentBuf = new ByteArrayOutputStream(Math.max(chunk.length, 256));
            fragmentBuf.write(chunk);
        }
    }

    /**
     * Writes an unmasked server-to-client Binary WebSocket frame carrying an 18-byte OpalCast header
     * followed directly by {@code payload[offset .. offset + length)} without intermediate frame copying.
     */
    public static void writeBinaryFrame(
            OutputStream out,
            Object writeLock,
            byte[] header18,
            byte[] payload,
            int offset,
            int length) throws IOException {
        writeBinaryFrame(out, writeLock, null, header18, payload, offset, length);
    }

    /**
     * Zero-allocation overload reusing a caller-owned 10-byte WebSocket framing buffer under {@code writeLock}.
     */
    public static void writeBinaryFrame(
            OutputStream out,
            Object writeLock,
            byte[] wsHeader10,
            byte[] header18,
            byte[] payload,
            int offset,
            int length) throws IOException {
        if (header18 == null || header18.length < OpalCastProtocol.HEADER_SIZE) {
            throw new IllegalArgumentException("header18 must have at least 18 bytes");
        }
        if (length < 0 || length > OpalCastProtocol.MAX_PAYLOAD_LEN) {
            throw new OpalCastProtocol.ProtocolException("Invalid payload length: " + length);
        }
        long totalLen = (long) OpalCastProtocol.HEADER_SIZE + length;
        synchronized (writeLock) {
            writeServerFrameHeader(out, OPCODE_BINARY, totalLen, wsHeader10);
            out.write(header18, 0, OpalCastProtocol.HEADER_SIZE);
            if (length > 0) {
                out.write(payload, offset, length);
            }
        }
    }

    /**
     * Writes an unmasked server-to-client UTF-8 Text WebSocket frame.
     */
    public static void writeTextFrame(OutputStream out, Object writeLock, String text) throws IOException {
        byte[] bytes = (text != null ? text : "").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            writeServerFrameHeader(out, OPCODE_TEXT, bytes.length);
            if (bytes.length > 0) {
                out.write(bytes);
            }
            out.flush();
        }
    }

    public static void writeControlFrame(
            OutputStream out,
            Object writeLock,
            int opcode,
            byte[] payload) throws IOException {
        byte[] data = (payload != null) ? payload : OpalCastProtocol.EMPTY_BYTES;
        int len = Math.min(data.length, MAX_CONTROL_PAYLOAD_LEN);
        synchronized (writeLock) {
            writeServerFrameHeader(out, opcode, len);
            if (len > 0) {
                out.write(data, 0, len);
            }
            out.flush();
        }
    }

    private static void sendCloseBestEffort(OutputStream out, Object writeLock, byte[] clientClosePayload) {
        if (out == null) return;
        byte[] echo = (clientClosePayload != null && clientClosePayload.length >= 2)
                ? new byte[]{clientClosePayload[0], clientClosePayload[1]}
                : OpalCastProtocol.EMPTY_BYTES;
        try {
            writeControlFrame(out, writeLock, OPCODE_CLOSE, echo);
        } catch (IOException ignored) {
            // Peer may have already half-closed its read side after sending CLOSE.
        }
    }

    private static void writeServerFrameHeader(OutputStream out, int opcode, long payloadLen) throws IOException {
        writeServerFrameHeader(out, opcode, payloadLen, null);
    }

    private static void writeServerFrameHeader(
            OutputStream out, int opcode, long payloadLen, byte[] reusableBuf) throws IOException {
        byte[] hdr = (reusableBuf != null && reusableBuf.length >= 10) ? reusableBuf : new byte[10];
        hdr[0] = (byte) (0x80 | (opcode & 0x0F));
        int hdrLen;
        if (payloadLen <= MAX_LEN_7BIT) {
            hdr[1] = (byte) payloadLen;
            hdrLen = 2;
        } else if (payloadLen <= MAX_LEN_16BIT) {
            hdr[1] = (byte) EXTENDED_LEN_16_MARKER;
            hdr[2] = (byte) ((payloadLen >>> 8) & 0xFF);
            hdr[3] = (byte) (payloadLen & 0xFF);
            hdrLen = 4;
        } else {
            hdr[1] = (byte) EXTENDED_LEN_64_MARKER;
            for (int i = 0; i < 8; i++) {
                hdr[2 + i] = (byte) ((payloadLen >>> (56 - i * 8)) & 0xFF);
            }
            hdrLen = 10;
        }
        out.write(hdr, 0, hdrLen);
    }

    private static long readPayloadLength(InputStream in, int len7) throws IOException {
        if (len7 < EXTENDED_LEN_16_MARKER) {
            return len7;
        }
        if (len7 == EXTENDED_LEN_16_MARKER) {
            int hi = readByteOrEof(in);
            int lo = readByteOrEof(in);
            return ((long) hi << 8) | lo;
        }
        long len64 = 0L;
        for (int i = 0; i < 8; i++) {
            len64 = (len64 << 8) | readByteOrEof(in);
        }
        if (len64 < 0) {
            throw new OpalCastProtocol.ProtocolException("Invalid negative 64-bit WebSocket length");
        }
        return len64;
    }

    private static byte[] readMaskedPayload(InputStream in, int len) throws IOException {
        byte[] mask = new byte[MASK_KEY_LEN];
        readFully(in, mask, 0, MASK_KEY_LEN);
        if (len == 0) {
            return OpalCastProtocol.EMPTY_BYTES;
        }
        byte[] payload = new byte[len];
        readFully(in, payload, 0, len);
        for (int i = 0; i < len; i++) {
            payload[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return payload;
    }

    private static int readByteOrEof(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Unexpected EOF while reading WebSocket frame");
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int pos = off;
        int rem = len;
        while (rem > 0) {
            int n = in.read(buf, pos, rem);
            if (n < 0) {
                throw new EOFException("Unexpected EOF while reading WebSocket payload (" + rem + " bytes remaining)");
            }
            pos += n;
            rem -= n;
        }
    }
}
