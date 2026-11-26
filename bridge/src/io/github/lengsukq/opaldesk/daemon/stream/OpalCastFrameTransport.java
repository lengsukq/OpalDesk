package io.github.lengsukq.opaldesk.daemon.stream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Transport-agnostic OpalCast v1.1 frame channel unifying:
 * <ul>
 *   <li>Raw TLS / ADB LocalSocket binary streams (native macOS & Android clients)</li>
 *   <li>RFC 6455 WebSocket Binary Messages (browser WebCodecs clients)</li>
 * </ul>
 */
public interface OpalCastFrameTransport {
    OpalCastProtocol.Frame readFrame() throws IOException;

    void writeFrame(
            int type,
            int flags,
            long timestampUs,
            byte[] payload,
            int offset,
            int length) throws IOException;

    void flush() throws IOException;

    static OpalCastFrameTransport raw(InputStream in, OutputStream out) {
        return new RawTransport(in, out);
    }

    static OpalCastFrameTransport webSocket(InputStream in, OutputStream out) {
        return new WebSocketTransport(in, out);
    }

    final class RawTransport implements OpalCastFrameTransport {
        private final InputStream in;
        private final OutputStream out;
        private final byte[] headerBuf = new byte[OpalCastProtocol.HEADER_SIZE];

        RawTransport(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public OpalCastProtocol.Frame readFrame() throws IOException {
            if (in == null) {
                throw new IOException("RawTransport has no InputStream");
            }
            return OpalCastProtocol.readFrame(in);
        }

        @Override
        public void writeFrame(
                int type,
                int flags,
                long timestampUs,
                byte[] payload,
                int offset,
                int length) throws IOException {
            OpalCastProtocol.writeFrame(out, headerBuf, type, flags, timestampUs, payload, offset, length);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }

    final class WebSocketTransport implements OpalCastFrameTransport {
        private static final int MAX_WS_MESSAGE_BYTES =
                OpalCastProtocol.HEADER_SIZE + OpalCastProtocol.MAX_PAYLOAD_LEN;

        private final InputStream in;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private final byte[] headerBuf = new byte[OpalCastProtocol.HEADER_SIZE];

        WebSocketTransport(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public OpalCastProtocol.Frame readFrame() throws IOException {
            while (true) {
                WebSocketCodec.Message msg = WebSocketCodec.readMessage(in, out, writeLock, MAX_WS_MESSAGE_BYTES);
                if (msg == null) {
                    return null;
                }
                if (msg.opcode != WebSocketCodec.OPCODE_BINARY) {
                    throw new OpalCastProtocol.ProtocolException(
                            "Expected binary WebSocket frame for OpalCast, got opcode=" + msg.opcode);
                }
                return OpalCastProtocol.decodeFrameBytes(msg.payload);
            }
        }

        @Override
        public void writeFrame(
                int type,
                int flags,
                long timestampUs,
                byte[] payload,
                int offset,
                int length) throws IOException {
            if (length < 0 || length > OpalCastProtocol.MAX_PAYLOAD_LEN) {
                throw new OpalCastProtocol.ProtocolException("Invalid payload length: " + length);
            }
            if (length > 0 && (payload == null || offset < 0 || offset + length > payload.length)) {
                throw new OpalCastProtocol.ProtocolException("Payload bounds out of range");
            }
            synchronized (writeLock) {
                OpalCastProtocol.encodeHeader(headerBuf, type, flags, length, timestampUs);
                WebSocketCodec.writeBinaryFrame(out, writeLock, headerBuf, payload, offset, length);
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (writeLock) {
                out.flush();
            }
        }
    }
}
