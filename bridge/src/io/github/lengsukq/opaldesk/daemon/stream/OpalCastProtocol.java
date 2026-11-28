package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpalCast v1.1 server-side binary framing and payload codecs (Big-Endian network byte order).
 * Byte-for-byte compatible with OpalDeskMac (Swift OpalCastFrame):
 * - Encodes downlink frames (Android -> Mac)
 * - Decodes uplink frames (Mac -> Android)
 */
public final class OpalCastProtocol {
    public static final int PROTOCOL_VERSION = BridgeBuildConfig.OPALCAST_PROTOCOL_VERSION;
    public static final int HEADER_SIZE = 18;
    public static final int MAGIC = 0x4F504431; // "OPD1"
    public static final byte MAGIC_0 = 0x4F; // 'O'
    public static final byte MAGIC_1 = 0x50; // 'P'
    public static final byte MAGIC_2 = 0x44; // 'D'
    public static final byte MAGIC_3 = 0x31; // '1'
    public static final int MAX_PAYLOAD_LEN = 16 * 1024 * 1024; // 16 MiB OOM guard
    public static final int MAX_TOUCH_POINTERS = 16;
    public static final int MAX_CLIPBOARD_IMAGE_BYTES = 8 * 1024 * 1024; // 8 MiB PNG clipboard cap

    // Downlink frame types (Android -> Mac)
    public static final int TYPE_VIDEO_CONFIG = 0x01;
    public static final int TYPE_VIDEO_FRAME = 0x02;
    public static final int TYPE_AUDIO_CONFIG = 0x03;
    public static final int TYPE_AUDIO_FRAME = 0x04;
    public static final int TYPE_DEVICE_STATE = 0x10;
    public static final int TYPE_CLIPBOARD_PUSH = 0x11;
    public static final int TYPE_AUTH_RESULT = 0x12;
    public static final int TYPE_FILE_TRANSFER_ACK = 0x13;
    public static final int TYPE_WIFI_UPGRADE_READY = 0x14;
    public static final int TYPE_APP_LIST = 0x15;
    public static final int TYPE_IME_REQUEST = 0x16;
    public static final int TYPE_MIC_STATE = 0x17;
    public static final int TYPE_CAMERA_STATE = 0x18;
    public static final int TYPE_NOTIFICATION_EVENT = 0x19;
    public static final int TYPE_MEDIA_STATE = 0x1A;
    public static final int TYPE_FILE_OP_RESPONSE = 0x1B;
    public static final int TYPE_FILE_PULL_CHUNK = 0x1C;
    public static final int TYPE_APK_INSTALL_STATUS = 0x1D;
    public static final int TYPE_CALL_STATE = 0x1E;
    public static final int TYPE_PONG = 0x1F;

    // Uplink frame types (Mac / Tablet / Web -> Android)
    public static final int TYPE_HELLO = 0x20;
    public static final int TYPE_RESIZE_CONFIG = 0x21;
    public static final int TYPE_REQUEST_IDR = 0x22;
    public static final int TYPE_INPUT_MOUSE = 0x23;
    public static final int TYPE_INPUT_TOUCH = 0x24;
    public static final int TYPE_INPUT_KEY = 0x25;
    public static final int TYPE_INPUT_TEXT = 0x26;
    public static final int TYPE_SYSTEM_ACTION = 0x27;
    public static final int TYPE_FILE_TRANSFER_CHUNK = 0x28;
    public static final int TYPE_REQUEST_WIFI_UPGRADE = 0x29;
    public static final int TYPE_LIST_APPS = 0x2A;
    public static final int TYPE_WINDOW_ACTION = 0x2B;
    public static final int TYPE_MIC_CONFIG = 0x2C;
    public static final int TYPE_MIC_FRAME = 0x2D;
    public static final int TYPE_CAMERA_CONFIG = 0x2E;
    public static final int TYPE_PING = 0x2F;
    public static final int TYPE_CAMERA_FRAME = 0x30;
    public static final int TYPE_NOTIFICATION_ACTION = 0x31;
    public static final int TYPE_MEDIA_ACTION = 0x32;
    public static final int TYPE_FILE_OP_REQUEST = 0x33;
    public static final int TYPE_APK_INSTALL_REQUEST = 0x34;
    public static final int TYPE_CALL_ACTION = 0x35;

    // Remote microphone uplink defaults
    public static final String MIC_CODEC_PCM16 = "pcm16";
    public static final int DEFAULT_MIC_SAMPLE_RATE = 16_000;
    public static final int DEFAULT_MIC_CHANNELS = 1;
    public static final int MAX_MIC_FRAME_BYTES = 65_536;

    // Root-gated remote camera uplink defaults
    public static final String CAMERA_CODEC_MJPEG = "mjpeg";
    public static final int DEFAULT_CAMERA_WIDTH = 640;
    public static final int DEFAULT_CAMERA_HEIGHT = 480;
    public static final int DEFAULT_CAMERA_FPS = 15;
    public static final int MAX_CAMERA_FRAME_BYTES = 512 * 1024;

    // Video codec IDs
    public static final int VIDEO_CODEC_H264 = 1;
    public static final int VIDEO_CODEC_HEVC = 2;

    // Video frame flags
    public static final int FLAG_VIDEO_KEYFRAME = 0x01;
    public static final int FLAG_VIDEO_CONFIG_INLINE = 0x02;

    // Clipboard / INPUT_TEXT flags
    public static final int FLAG_CLIPBOARD_IMAGE_PNG = 0x01;

    // Session roles & transport hints (OpalCast v1.1 / v1.2 HELLO)
    public static final String SESSION_ROLE_DESKTOP = "desktop";
    public static final String SESSION_ROLE_STANDALONE_APP = "standalone_app";
    public static final String SESSION_ROLE_APP_CATALOG = "app_catalog";
    public static final String SESSION_ROLE_COMPANION = "companion";
    public static final String TRANSPORT_HINT_USB = "usb";
    public static final String TRANSPORT_HINT_USB_TETHER = "usb_tether";
    public static final String TRANSPORT_HINT_WIFI_ADB = "wifi_adb";
    public static final String TRANSPORT_HINT_WIFI_TLS = "wifi_tls";

    public static final String CLIENT_PLATFORM_ANDROID = "android";
    public static final String CLIENT_PLATFORM_MACOS = "macos";
    public static final String CLIENT_PLATFORM_WINDOWS = "windows";
    public static final String CLIENT_PLATFORM_WEB = "web";

    // Audio codec IDs
    public static final int AUDIO_CODEC_AAC_LC = 1;

    // Display modes
    public static final int DISPLAY_MODE_DEX = 0;
    public static final int DISPLAY_MODE_MIRROR = 1;
    public static final int DISPLAY_MODE_UNCHANGED = 0xFF;

    // Mouse actions
    public static final int MOUSE_MOVE_ABS = 0;
    public static final int MOUSE_BUTTON_DOWN = 1;
    public static final int MOUSE_BUTTON_UP = 2;
    public static final int MOUSE_SCROLL = 3;
    public static final int MOUSE_HOVER_EXIT = 4;

    // Mouse button masks (1:1 with Android MotionEvent button masks)
    public static final int BUTTON_PRIMARY = 1;
    public static final int BUTTON_SECONDARY = 2;
    public static final int BUTTON_TERTIARY = 4;
    public static final int BUTTON_BACK = 8;
    public static final int BUTTON_FORWARD = 16;

    // Touch actions
    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_UP = 1;
    public static final int TOUCH_MOVE = 2;
    public static final int TOUCH_CANCEL = 3;
    public static final int TOUCH_POINTER_DOWN = 5;
    public static final int TOUCH_POINTER_UP = 6;

    // Key actions
    public static final int KEY_DOWN = 0;
    public static final int KEY_UP = 1;

    // Text input modes
    public static final int TEXT_MODE_COMMIT_PASTE = 0;
    public static final int TEXT_MODE_SET_CLIPBOARD_ONLY = 1;

    // System actions
    public static final int SYS_HOME = 1;
    public static final int SYS_BACK = 2;
    public static final int SYS_RECENTS = 3;
    public static final int SYS_TOGGLE_DISPLAY_MODE = 4;
    public static final int SYS_TOGGLE_BLACKOUT = 5;
    public static final int SYS_ROTATE_CW = 6;
    public static final int SYS_SET_AUDIO_ENABLED = 7;
    public static final int SYS_DISCONNECT = 8;

    // File transfer chunk flags & ACK statuses
    public static final int FILE_FLAG_START = 0x01;
    public static final int FILE_FLAG_DATA = 0x02;
    public static final int FILE_FLAG_END = 0x04;
    public static final int FILE_FLAG_ABORT = 0x08;
    public static final int FILE_ACK_OK = 0;
    public static final int FILE_ACK_ERR = 1;

    public static final byte[] EMPTY_BYTES = new byte[0];
    static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private OpalCastProtocol() {}

    public static boolean isValidPngHeader(byte[] data) {
        if (data == null || data.length < PNG_MAGIC.length) return false;
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (data[i] != PNG_MAGIC[i]) return false;
        }
        return true;
    }

    public static boolean isValidJpegHeader(byte[] data) {
        return data != null && data.length >= 3
                && (data[0] & 0xFF) == 0xFF
                && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF;
    }

    public static final class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Frame {
        public final int type;
        public final int flags;
        public final long timestampUs;
        public final byte[] payload;

        public Frame(int type, int flags, long timestampUs, byte[] payload) {
            this.type = type & 0xFF;
            this.flags = flags & 0xFF;
            this.timestampUs = timestampUs;
            this.payload = (payload != null) ? payload : EMPTY_BYTES;
        }
    }

    // ---- Low-level 18-byte BE frame I/O ----

    public static void writeFrame(
            OutputStream out,
            int type,
            int flags,
            long timestampUs,
            byte[] payload,
            int offset,
            int length) throws IOException {
        writeFrame(out, new byte[HEADER_SIZE], type, flags, timestampUs, payload, offset, length);
    }

    public static void writeFrame(
            OutputStream out,
            byte[] headerBuf,
            int type,
            int flags,
            long timestampUs,
            byte[] payload,
            int offset,
            int length) throws IOException {
        if (length < 0 || length > MAX_PAYLOAD_LEN) {
            throw new ProtocolException("Invalid payload length: " + length);
        }
        if (length > 0 && (payload == null || offset < 0 || offset + length > payload.length)) {
            throw new ProtocolException("Payload bounds out of range");
        }
        byte[] header = (headerBuf != null && headerBuf.length >= HEADER_SIZE)
                ? headerBuf : new byte[HEADER_SIZE];
        encodeHeader(header, type, flags, length, timestampUs);
        out.write(header, 0, HEADER_SIZE);
        if (length > 0) {
            out.write(payload, offset, length);
        }
    }

    public static void encodeHeader(byte[] header, int type, int flags, int payloadLen, long timestampUs) {
        header[0] = MAGIC_0;
        header[1] = MAGIC_1;
        header[2] = MAGIC_2;
        header[3] = MAGIC_3;
        header[4] = (byte) (type & 0xFF);
        header[5] = (byte) (flags & 0xFF);
        header[6] = (byte) ((payloadLen >>> 24) & 0xFF);
        header[7] = (byte) ((payloadLen >>> 16) & 0xFF);
        header[8] = (byte) ((payloadLen >>> 8) & 0xFF);
        header[9] = (byte) (payloadLen & 0xFF);
        header[10] = (byte) ((timestampUs >>> 56) & 0xFF);
        header[11] = (byte) ((timestampUs >>> 48) & 0xFF);
        header[12] = (byte) ((timestampUs >>> 40) & 0xFF);
        header[13] = (byte) ((timestampUs >>> 32) & 0xFF);
        header[14] = (byte) ((timestampUs >>> 24) & 0xFF);
        header[15] = (byte) ((timestampUs >>> 16) & 0xFF);
        header[16] = (byte) ((timestampUs >>> 8) & 0xFF);
        header[17] = (byte) (timestampUs & 0xFF);
    }

    /**
     * Reads one OpalCast v1 frame from {@code in}. Returns {@code null} on clean EOF
     * before the first byte of a header; throws {@link EOFException} on mid-frame EOF;
     * throws {@link ProtocolException} on invalid magic or payloadLen > 16 MiB.
     */
    public static Frame readFrame(InputStream in) throws IOException {
        return readFrame(in, new byte[HEADER_SIZE]);
    }

    /**
     * Reads one OpalCast v1 frame using a caller-owned reusable 18-byte header buffer to avoid
     * per-frame heap allocation on high-frequency uplink input streams.
     */
    public static Frame readFrame(InputStream in, byte[] reusableHeaderBuf) throws IOException {
        byte[] header = (reusableHeaderBuf != null && reusableHeaderBuf.length >= HEADER_SIZE)
                ? reusableHeaderBuf
                : new byte[HEADER_SIZE];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        header[0] = (byte) first;
        readFully(in, header, 1, HEADER_SIZE - 1);

        DecodedHeader dh = parseHeader(header);
        byte[] payload = (dh.payloadLen == 0) ? EMPTY_BYTES : new byte[dh.payloadLen];
        if (dh.payloadLen > 0) {
            readFully(in, payload, 0, dh.payloadLen);
        }
        return new Frame(dh.type, dh.flags, dh.timestampUs, payload);
    }

    /**
     * Decodes a complete in-memory OpalCast v1 frame (18-byte header + exact payload),
     * used when receiving one OpalCast frame per RFC 6455 WebSocket Binary Message.
     */
    public static Frame decodeFrameBytes(byte[] raw) throws ProtocolException {
        if (raw == null || raw.length < HEADER_SIZE) {
            throw new ProtocolException("Truncated OpalCast WebSocket frame: " + (raw == null ? 0 : raw.length));
        }
        DecodedHeader dh = parseHeader(raw);
        if (raw.length != HEADER_SIZE + dh.payloadLen) {
            throw new ProtocolException("OpalCast WebSocket frame length mismatch: expected "
                    + (HEADER_SIZE + dh.payloadLen) + ", got " + raw.length);
        }
        byte[] payload = (dh.payloadLen == 0) ? EMPTY_BYTES : new byte[dh.payloadLen];
        if (dh.payloadLen > 0) {
            System.arraycopy(raw, HEADER_SIZE, payload, 0, dh.payloadLen);
        }
        return new Frame(dh.type, dh.flags, dh.timestampUs, payload);
    }

    private static final class DecodedHeader {
        final int type;
        final int flags;
        final int payloadLen;
        final long timestampUs;

        DecodedHeader(int type, int flags, int payloadLen, long timestampUs) {
            this.type = type;
            this.flags = flags;
            this.payloadLen = payloadLen;
            this.timestampUs = timestampUs;
        }
    }

    private static DecodedHeader parseHeader(byte[] header) throws ProtocolException {
        if (header[0] != MAGIC_0 || header[1] != MAGIC_1
                || header[2] != MAGIC_2 || header[3] != MAGIC_3) {
            throw new ProtocolException(String.format(
                    "Invalid OpalCast magic: %02X %02X %02X %02X",
                    header[0] & 0xFF, header[1] & 0xFF, header[2] & 0xFF, header[3] & 0xFF));
        }

        int type = header[4] & 0xFF;
        int flags = header[5] & 0xFF;
        long payloadLenUnsigned =
                ((long) (header[6] & 0xFF) << 24)
                | ((long) (header[7] & 0xFF) << 16)
                | ((long) (header[8] & 0xFF) << 8)
                | ((long) (header[9] & 0xFF));
        if (payloadLenUnsigned > MAX_PAYLOAD_LEN) {
            throw new ProtocolException("Payload exceeds 16 MiB limit: " + payloadLenUnsigned);
        }
        int payloadLen = (int) payloadLenUnsigned;

        long timestampUs =
                ((long) (header[10] & 0xFF) << 56)
                | ((long) (header[11] & 0xFF) << 48)
                | ((long) (header[12] & 0xFF) << 40)
                | ((long) (header[13] & 0xFF) << 32)
                | ((long) (header[14] & 0xFF) << 24)
                | ((long) (header[15] & 0xFF) << 16)
                | ((long) (header[16] & 0xFF) << 8)
                | ((long) (header[17] & 0xFF));
        return new DecodedHeader(type, flags, payloadLen, timestampUs);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int remaining = len;
        int pos = off;
        while (remaining > 0) {
            int n = in.read(buf, pos, remaining);
            if (n < 0) {
                throw new EOFException("Unexpected EOF while reading OpalCast frame (" + remaining + " bytes remaining)");
            }
            pos += n;
            remaining -= n;
        }
    }

    // ---- Downlink payload structures & encoders (Android -> Mac) ----

    public static final class VideoConfig {
        public final int codec;
        public final int width;
        public final int height;
        public final int dpi;
        public final int rotation;
        public final int fpsX100;
        public final int displayMode;
        public final byte[] csdAnnexB;

        public VideoConfig(
                int codec, int width, int height, int dpi,
                int rotation, int fpsX100, int displayMode, byte[] csdAnnexB) {
            this.codec = codec;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.rotation = rotation;
            this.fpsX100 = fpsX100;
            this.displayMode = displayMode;
            this.csdAnnexB = (csdAnnexB != null) ? csdAnnexB : EMPTY_BYTES;
        }
    }

    public static byte[] encodeVideoConfig(VideoConfig cfg) {
        byte[] csd = (cfg.csdAnnexB != null) ? cfg.csdAnnexB : EMPTY_BYTES;
        ByteBuffer bb = ByteBuffer.allocate(11 + csd.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (cfg.codec & 0xFF));
        bb.putShort((short) (cfg.width & 0xFFFF));
        bb.putShort((short) (cfg.height & 0xFFFF));
        bb.putShort((short) (cfg.dpi & 0xFFFF));
        bb.put((byte) (cfg.rotation & 0xFF));
        bb.putShort((short) (cfg.fpsX100 & 0xFFFF));
        bb.put((byte) (cfg.displayMode & 0xFF));
        if (csd.length > 0) bb.put(csd);
        return bb.array();
    }

    public static final class AudioConfig {
        public final int codec;
        public final int sampleRate;
        public final int channels;
        public final byte[] audioSpecificConfig;

        public AudioConfig(int codec, int sampleRate, int channels, byte[] audioSpecificConfig) {
            this.codec = codec;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.audioSpecificConfig = (audioSpecificConfig != null) ? audioSpecificConfig : EMPTY_BYTES;
        }
    }

    public static byte[] encodeAudioConfig(AudioConfig cfg) {
        byte[] asc = (cfg.audioSpecificConfig != null) ? cfg.audioSpecificConfig : EMPTY_BYTES;
        ByteBuffer bb = ByteBuffer.allocate(6 + asc.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (cfg.codec & 0xFF));
        bb.putInt(cfg.sampleRate);
        bb.put((byte) (cfg.channels & 0xFF));
        if (asc.length > 0) bb.put(asc);
        return bb.array();
    }

    public static final class DeviceState {
        public final String deviceName;
        public final int sinkType;
        public final int displayMode;
        public final int width;
        public final int height;
        public final int dpi;
        public final double fps;
        public final int rotation;
        public final int bitrateKbps;
        public final int batteryPct;
        public final boolean blackout;
        public final boolean audioEnabled;
        // OpalCast v1.1 optional fields
        public final int injectStatus; // 1=INJECT_EVENTS, 2=UHID, -1=denied
        public final boolean adaptiveSingleEnabled;
        public final int defaultSingleWidthDp;
        public final String activeLayoutMode;
        public final String topAppPackage;
        public final String topAppLabel;
        public final List<String> tilingSlots;
        public final List<Integer> tilingRatios;
        public final String fullscreenWindowTitle;
        // OpalCast v1.2 companion handoff fields (Display 0 foreground app)
        public final String hostTopAppPackage;
        public final String hostTopAppLabel;

        public DeviceState(
                String deviceName, int sinkType, int displayMode,
                int width, int height, int dpi, double fps, int rotation,
                int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation,
                    bitrateKbps, batteryPct, blackout, audioEnabled,
                    1, true, 460, "FULLSCREEN", "", "");
        }

        public DeviceState(
                String deviceName, int sinkType, int displayMode,
                int width, int height, int dpi, double fps, int rotation,
                int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation,
                    bitrateKbps, batteryPct, blackout, audioEnabled,
                    injectStatus, adaptiveSingleEnabled, defaultSingleWidthDp,
                    activeLayoutMode, topAppPackage, topAppLabel,
                    Collections.emptyList(), Collections.emptyList(), "", "", "");
        }

        public DeviceState(
                String deviceName, int sinkType, int displayMode,
                int width, int height, int dpi, double fps, int rotation,
                int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel,
                List<String> tilingSlots, List<Integer> tilingRatios) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation,
                    bitrateKbps, batteryPct, blackout, audioEnabled,
                    injectStatus, adaptiveSingleEnabled, defaultSingleWidthDp,
                    activeLayoutMode, topAppPackage, topAppLabel,
                    tilingSlots, tilingRatios, "", "", "");
        }

        public DeviceState(
                String deviceName, int sinkType, int displayMode,
                int width, int height, int dpi, double fps, int rotation,
                int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel,
                List<String> tilingSlots, List<Integer> tilingRatios,
                String fullscreenWindowTitle) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation,
                    bitrateKbps, batteryPct, blackout, audioEnabled,
                    injectStatus, adaptiveSingleEnabled, defaultSingleWidthDp,
                    activeLayoutMode, topAppPackage, topAppLabel,
                    tilingSlots, tilingRatios, fullscreenWindowTitle, "", "");
        }

        public DeviceState(
                String deviceName, int sinkType, int displayMode,
                int width, int height, int dpi, double fps, int rotation,
                int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel,
                List<String> tilingSlots, List<Integer> tilingRatios,
                String fullscreenWindowTitle,
                String hostTopAppPackage, String hostTopAppLabel) {
            this.deviceName = (deviceName != null) ? deviceName : "Android";
            this.sinkType = sinkType;
            this.displayMode = displayMode;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.fps = fps;
            this.rotation = rotation;
            this.bitrateKbps = bitrateKbps;
            this.batteryPct = batteryPct;
            this.blackout = blackout;
            this.audioEnabled = audioEnabled;
            this.injectStatus = injectStatus;
            this.adaptiveSingleEnabled = adaptiveSingleEnabled;
            this.defaultSingleWidthDp = Math.max(320, Math.min(1200, defaultSingleWidthDp));
            this.activeLayoutMode = (activeLayoutMode != null && !activeLayoutMode.isEmpty())
                    ? activeLayoutMode : "FULLSCREEN";
            this.topAppPackage = (topAppPackage != null) ? topAppPackage : "";
            this.topAppLabel = (topAppLabel != null) ? topAppLabel : "";
            if (tilingSlots != null && !tilingSlots.isEmpty()) {
                List<String> slotsCopy = new ArrayList<>(tilingSlots.size());
                for (String slot : tilingSlots) {
                    slotsCopy.add(slot != null ? slot : "");
                }
                this.tilingSlots = Collections.unmodifiableList(slotsCopy);
            } else {
                this.tilingSlots = Collections.emptyList();
            }
            if (tilingRatios != null && !tilingRatios.isEmpty()) {
                List<Integer> ratiosCopy = new ArrayList<>(tilingRatios.size());
                for (Integer ratio : tilingRatios) {
                    ratiosCopy.add(ratio != null ? ratio : 0);
                }
                this.tilingRatios = Collections.unmodifiableList(ratiosCopy);
            } else {
                this.tilingRatios = Collections.emptyList();
            }
            this.fullscreenWindowTitle = (fullscreenWindowTitle != null) ? fullscreenWindowTitle : "";
            this.hostTopAppPackage = (hostTopAppPackage != null) ? hostTopAppPackage : "";
            this.hostTopAppLabel = (hostTopAppLabel != null) ? hostTopAppLabel : "";
        }
    }

    public static byte[] encodeDeviceState(DeviceState s) {
        SimpleJson.ArrayBuilder slotsArr = new SimpleJson.ArrayBuilder();
        for (String slot : s.tilingSlots) {
            slotsArr.add(slot != null ? slot : "");
        }
        SimpleJson.ArrayBuilder ratiosArr = new SimpleJson.ArrayBuilder();
        for (Integer ratio : s.tilingRatios) {
            ratiosArr.add(ratio != null ? ratio : 0);
        }
        String json = new SimpleJson.ObjectBuilder()
                .put("deviceName", s.deviceName)
                .put("sinkType", s.sinkType)
                .put("displayMode", s.displayMode)
                .put("width", s.width)
                .put("height", s.height)
                .put("dpi", s.dpi)
                .put("fps", s.fps)
                .put("rotation", s.rotation)
                .put("bitrateKbps", s.bitrateKbps)
                .put("batteryPct", s.batteryPct)
                .put("blackout", s.blackout)
                .put("audioEnabled", s.audioEnabled)
                .put("injectStatus", s.injectStatus)
                .put("adaptiveSingleEnabled", s.adaptiveSingleEnabled)
                .put("defaultSingleWidthDp", s.defaultSingleWidthDp)
                .put("activeLayoutMode", s.activeLayoutMode)
                .put("topAppPackage", s.topAppPackage)
                .put("topAppLabel", s.topAppLabel)
                .put("fullscreenWindowTitle", s.fullscreenWindowTitle)
                .put("hostTopAppPackage", s.hostTopAppPackage)
                .put("hostTopAppLabel", s.hostTopAppLabel)
                .putRaw("tilingSlots", slotsArr.build())
                .putRaw("tilingRatios", ratiosArr.build())
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeClipboardPush(long seq, String text) {
        byte[] utf8 = (text != null ? text : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(8 + utf8.length).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(seq);
        bb.put(utf8);
        return bb.array();
    }

    public static byte[] encodeClipboardImagePush(long seq, byte[] pngData) throws ProtocolException {
        byte[] data = (pngData != null) ? pngData : EMPTY_BYTES;
        if (data.length > MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new ProtocolException("Clipboard PNG exceeds 8 MiB limit: " + data.length);
        }
        ByteBuffer bb = ByteBuffer.allocate(8 + data.length).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(seq);
        if (data.length > 0) bb.put(data);
        return bb.array();
    }

    public static final class AuthResultMsg {
        public final boolean ok;
        public final String authToken;
        public final String message;
        public final String deviceName;
        public final String certFingerprint;
        public final boolean permanent;

        public AuthResultMsg(boolean ok, String authToken, String message, String deviceName, String certFingerprint) {
            this(ok, authToken, message, deviceName, certFingerprint, authToken != null && !authToken.isEmpty());
        }

        public AuthResultMsg(
                boolean ok,
                String authToken,
                String message,
                String deviceName,
                String certFingerprint,
                boolean permanent) {
            this.ok = ok;
            this.authToken = (authToken != null) ? authToken : "";
            this.message = (message != null) ? message : "";
            this.deviceName = (deviceName != null) ? deviceName : "";
            this.certFingerprint = (certFingerprint != null) ? certFingerprint : "";
            this.permanent = permanent;
        }
    }

    public static byte[] encodeAuthResult(AuthResultMsg msg) {
        String json = new SimpleJson.ObjectBuilder()
                .put("ok", msg.ok)
                .put("authToken", msg.authToken)
                .put("permanent", msg.permanent)
                .put("message", msg.message)
                .put("deviceName", msg.deviceName)
                .put("certFingerprint", msg.certFingerprint)
                .build();
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + utf8.length];
        out[0] = (byte) (msg.ok ? 1 : 0);
        System.arraycopy(utf8, 0, out, 1, utf8.length);
        return out;
    }

    public static byte[] encodeFileTransferAck(int transferId, int status, String pathOrError) {
        byte[] utf8 = (pathOrError != null ? pathOrError : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(5 + utf8.length).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) (status & 0xFF));
        bb.put(utf8);
        return bb.array();
    }

    public static final class WifiUpgradeReadyMsg {
        public final boolean ok;
        public final String host;
        public final int port;
        public final String certFingerprint;
        public final String authToken;
        public final String message;

        public WifiUpgradeReadyMsg(
                boolean ok, String host, int port,
                String certFingerprint, String authToken, String message) {
            this.ok = ok;
            this.host = (host != null) ? host : "";
            this.port = port;
            this.certFingerprint = (certFingerprint != null) ? certFingerprint : "";
            this.authToken = (authToken != null) ? authToken : "";
            this.message = (message != null) ? message : "";
        }
    }

    public static byte[] encodeWifiUpgradeReady(WifiUpgradeReadyMsg msg) {
        String json = new SimpleJson.ObjectBuilder()
                .put("ok", msg.ok)
                .put("host", msg.host)
                .put("port", msg.port)
                .put("certFingerprint", msg.certFingerprint)
                .put("authToken", msg.authToken)
                .put("message", msg.message)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static final class AppEntry {
        public final String pkg;
        public final String cls;
        public final String label;
        public final boolean runningOnExt;
        public final int taskId;
        public final String iconPngBase64;
        public final int openCount;
        public final int userId;
        public final String profileType;

        public AppEntry(String pkg, String cls, String label, boolean runningOnExt, int taskId) {
            this(pkg, cls, label, runningOnExt, taskId, "", 0, 0, "");
        }

        public AppEntry(
                String pkg, String cls, String label, boolean runningOnExt, int taskId, String iconPngBase64) {
            this(pkg, cls, label, runningOnExt, taskId, iconPngBase64, 0, 0, "");
        }

        public AppEntry(
                String pkg, String cls, String label, boolean runningOnExt, int taskId,
                String iconPngBase64, int openCount) {
            this(pkg, cls, label, runningOnExt, taskId, iconPngBase64, openCount, 0, "");
        }

        public AppEntry(
                String pkg, String cls, String label, boolean runningOnExt, int taskId,
                String iconPngBase64, int openCount, int userId, String profileType) {
            this.pkg = (pkg != null) ? pkg : "";
            this.cls = (cls != null) ? cls : "";
            this.label = (label != null) ? label : "";
            this.runningOnExt = runningOnExt;
            this.taskId = taskId;
            this.iconPngBase64 = (iconPngBase64 != null) ? iconPngBase64 : "";
            this.openCount = Math.max(0, openCount);
            this.userId = Math.max(0, userId);
            this.profileType = (this.userId > 0 && profileType != null) ? profileType.trim() : "";
        }
    }

    public static final class AppListMsg {
        public final List<AppEntry> apps;

        public AppListMsg(List<AppEntry> apps) {
            this.apps = (apps != null)
                    ? Collections.unmodifiableList(new ArrayList<>(apps))
                    : Collections.emptyList();
        }
    }

    public static byte[] encodeAppList(AppListMsg msg) {
        SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
        if (msg != null && msg.apps != null) {
            for (AppEntry e : msg.apps) {
                if (e == null) continue;
                SimpleJson.ObjectBuilder item = new SimpleJson.ObjectBuilder()
                        .put("pkg", e.pkg)
                        .put("cls", e.cls)
                        .put("label", e.label)
                        .put("runningOnExt", e.runningOnExt)
                        .put("taskId", e.taskId)
                        .put("openCount", e.openCount);
                if (e.userId > 0) {
                    item.put("userId", e.userId);
                    if (e.profileType != null && !e.profileType.isEmpty()) {
                        item.put("profileType", e.profileType);
                    }
                }
                if (e.iconPngBase64 != null && !e.iconPngBase64.isEmpty()) {
                    item.put("icon", e.iconPngBase64);
                }
                arr.addRaw(item.build());
            }
        }
        String json = new SimpleJson.ObjectBuilder()
                .putRaw("apps", arr.build())
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeImeRequest(boolean show, int inputType) {
        String json = new SimpleJson.ObjectBuilder()
                .put("show", show)
                .put("inputType", inputType)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static final class MicStateMsg {
        public final boolean active;
        public final boolean supported;
        public final int sampleRate;
        public final int channels;
        public final String message;

        public MicStateMsg(boolean active, boolean supported, int sampleRate, int channels, String message) {
            this.active = active;
            this.supported = supported;
            this.sampleRate = Math.max(8_000, Math.min(48_000, sampleRate > 0 ? sampleRate : DEFAULT_MIC_SAMPLE_RATE));
            this.channels = Math.max(1, Math.min(2, channels > 0 ? channels : DEFAULT_MIC_CHANNELS));
            this.message = (message != null) ? message : "";
        }
    }

    public static byte[] encodeMicState(MicStateMsg msg) {
        MicStateMsg m = (msg != null)
                ? msg : new MicStateMsg(false, false, DEFAULT_MIC_SAMPLE_RATE, DEFAULT_MIC_CHANNELS, "");
        String json = new SimpleJson.ObjectBuilder()
                .put("active", m.active)
                .put("supported", m.supported)
                .put("sampleRate", m.sampleRate)
                .put("channels", m.channels)
                .put("message", m.message)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static MicStateMsg decodeMicState(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("MIC_STATE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new MicStateMsg(
                    obj.getBoolean("active", false),
                    obj.getBoolean("supported", false),
                    obj.getInt("sampleRate", DEFAULT_MIC_SAMPLE_RATE),
                    obj.getInt("channels", DEFAULT_MIC_CHANNELS),
                    obj.getString("message", ""));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid MIC_STATE JSON: " + t.getMessage(), t);
        }
    }

    public static final class MicConfigMsg {
        public final boolean enabled;
        public final String codec;
        public final int sampleRate;
        public final int channels;

        public MicConfigMsg(boolean enabled, String codec, int sampleRate, int channels) {
            this.enabled = enabled;
            String c = (codec != null) ? codec.trim().toLowerCase(java.util.Locale.ROOT) : "";
            this.codec = c.isEmpty() ? MIC_CODEC_PCM16 : c;
            this.sampleRate = Math.max(8_000, Math.min(48_000, sampleRate > 0 ? sampleRate : DEFAULT_MIC_SAMPLE_RATE));
            this.channels = Math.max(1, Math.min(2, channels > 0 ? channels : DEFAULT_MIC_CHANNELS));
        }
    }

    public static byte[] encodeMicConfig(MicConfigMsg msg) {
        MicConfigMsg m = (msg != null)
                ? msg : new MicConfigMsg(false, MIC_CODEC_PCM16, DEFAULT_MIC_SAMPLE_RATE, DEFAULT_MIC_CHANNELS);
        String json = new SimpleJson.ObjectBuilder()
                .put("enabled", m.enabled)
                .put("codec", m.codec)
                .put("sampleRate", m.sampleRate)
                .put("channels", m.channels)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static MicConfigMsg decodeMicConfig(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("MIC_CONFIG payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new MicConfigMsg(
                    obj.getBoolean("enabled", false),
                    obj.getString("codec", MIC_CODEC_PCM16),
                    obj.getInt("sampleRate", DEFAULT_MIC_SAMPLE_RATE),
                    obj.getInt("channels", DEFAULT_MIC_CHANNELS));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid MIC_CONFIG JSON: " + t.getMessage(), t);
        }
    }

    public static final class CameraStateMsg {
        public final boolean active;
        public final boolean supported;
        public final boolean rootRequired;
        public final int width;
        public final int height;
        public final int fps;
        public final String backend;
        public final String message;
        public final String facing;

        public CameraStateMsg(
                boolean active, boolean supported, boolean rootRequired,
                int width, int height, int fps, String backend, String message) {
            this(active, supported, rootRequired, width, height, fps, backend, message, "");
        }

        public CameraStateMsg(
                boolean active, boolean supported, boolean rootRequired,
                int width, int height, int fps, String backend, String message, String facing) {
            this.active = active;
            this.supported = supported;
            this.rootRequired = rootRequired;
            this.width = Math.max(160, Math.min(1920, width > 0 ? width : DEFAULT_CAMERA_WIDTH));
            this.height = Math.max(120, Math.min(1080, height > 0 ? height : DEFAULT_CAMERA_HEIGHT));
            this.fps = Math.max(5, Math.min(30, fps > 0 ? fps : DEFAULT_CAMERA_FPS));
            this.backend = (backend != null) ? backend : "";
            this.message = (message != null) ? message : "";
            this.facing = (facing != null) ? facing : "";
        }
    }

    public static byte[] encodeCameraState(CameraStateMsg msg) {
        CameraStateMsg m = (msg != null)
                ? msg : new CameraStateMsg(false, false, true, DEFAULT_CAMERA_WIDTH, DEFAULT_CAMERA_HEIGHT, DEFAULT_CAMERA_FPS, "", "");
        SimpleJson.ObjectBuilder b = new SimpleJson.ObjectBuilder()
                .put("active", m.active)
                .put("supported", m.supported)
                .put("rootRequired", m.rootRequired)
                .put("width", m.width)
                .put("height", m.height)
                .put("fps", m.fps)
                .put("backend", m.backend)
                .put("message", m.message);
        if (!m.facing.isEmpty()) {
            b.put("facing", m.facing);
        }
        return b.build().getBytes(StandardCharsets.UTF_8);
    }

    public static CameraStateMsg decodeCameraState(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("CAMERA_STATE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new CameraStateMsg(
                    obj.getBoolean("active", false),
                    obj.getBoolean("supported", false),
                    obj.getBoolean("rootRequired", true),
                    obj.getInt("width", DEFAULT_CAMERA_WIDTH),
                    obj.getInt("height", DEFAULT_CAMERA_HEIGHT),
                    obj.getInt("fps", DEFAULT_CAMERA_FPS),
                    obj.getString("backend", ""),
                    obj.getString("message", ""),
                    obj.getString("facing", ""));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid CAMERA_STATE JSON: " + t.getMessage(), t);
        }
    }

    public static final class CameraConfigMsg {
        public final boolean enabled;
        public final String codec;
        public final int width;
        public final int height;
        public final int fps;
        public final String facing;

        public CameraConfigMsg(boolean enabled, String codec, int width, int height, int fps, String facing) {
            this.enabled = enabled;
            String c = (codec != null) ? codec.trim().toLowerCase(java.util.Locale.ROOT) : "";
            this.codec = c.isEmpty() ? CAMERA_CODEC_MJPEG : c;
            this.width = Math.max(160, Math.min(1920, width > 0 ? width : DEFAULT_CAMERA_WIDTH));
            this.height = Math.max(120, Math.min(1080, height > 0 ? height : DEFAULT_CAMERA_HEIGHT));
            this.fps = Math.max(5, Math.min(30, fps > 0 ? fps : DEFAULT_CAMERA_FPS));
            String f = (facing != null) ? facing.trim().toLowerCase(java.util.Locale.ROOT) : "";
            this.facing = "back".equals(f) ? "back" : "front";
        }
    }

    public static byte[] encodeCameraConfig(CameraConfigMsg msg) {
        CameraConfigMsg m = (msg != null)
                ? msg : new CameraConfigMsg(false, CAMERA_CODEC_MJPEG, DEFAULT_CAMERA_WIDTH, DEFAULT_CAMERA_HEIGHT, DEFAULT_CAMERA_FPS, "front");
        String json = new SimpleJson.ObjectBuilder()
                .put("enabled", m.enabled)
                .put("codec", m.codec)
                .put("width", m.width)
                .put("height", m.height)
                .put("fps", m.fps)
                .put("facing", m.facing)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static CameraConfigMsg decodeCameraConfig(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("CAMERA_CONFIG payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new CameraConfigMsg(
                    obj.getBoolean("enabled", false),
                    obj.getString("codec", CAMERA_CODEC_MJPEG),
                    obj.getInt("width", DEFAULT_CAMERA_WIDTH),
                    obj.getInt("height", DEFAULT_CAMERA_HEIGHT),
                    obj.getInt("fps", DEFAULT_CAMERA_FPS),
                    obj.getString("facing", "front"));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid CAMERA_CONFIG JSON: " + t.getMessage(), t);
        }
    }

    public static byte[] encodePingPong(long pingId) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(pingId);
        return bb.array();
    }

    public static long decodePingPong(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 8) {
            throw new ProtocolException("PING/PONG payload must be 8 bytes");
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    // ---- Uplink payload structures & decoders (Mac -> Android) ----

    public static final class HelloMsg {
        public final int protocolVersion;
        public final String clientName;
        public final String clientId;
        public final String preferredCodec;
        public final int width;
        public final int height;
        public final int dpi;
        public final int fps;
        public final int bitrateKbps;
        public final int displayMode;
        public final boolean audioEnabled;
        public final String authToken;
        public final String pinCode;
        // OpalCast v1.1 optional fields
        public final String transportHint; // "usb" | "usb_tether" | "wifi_adb" | "wifi_tls" | ""
        public final String sessionRole;   // "desktop" | "standalone_app"
        public final String appPackage;
        public final String appActivity;
        public final int appUserId;
        public final String clientPlatform; // "android" | "macos" | "windows" | "web" | ""
        // OpalCast v1.2 opt-in capability flag for desktop sessions (companion always subscribes)
        public final boolean companionEvents;

        public HelloMsg(
                int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps,
                int displayMode, boolean audioEnabled, String authToken, String pinCode) {
            this(protocolVersion, clientName, clientId, preferredCodec,
                    width, height, dpi, fps, bitrateKbps, displayMode, audioEnabled,
                    authToken, pinCode, "", SESSION_ROLE_DESKTOP, "", "", "", false, 0);
        }

        public HelloMsg(
                int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps,
                int displayMode, boolean audioEnabled, String authToken, String pinCode,
                String transportHint, String sessionRole, String appPackage, String appActivity) {
            this(protocolVersion, clientName, clientId, preferredCodec,
                    width, height, dpi, fps, bitrateKbps, displayMode, audioEnabled,
                    authToken, pinCode, transportHint, sessionRole, appPackage, appActivity, "", false, 0);
        }

        public HelloMsg(
                int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps,
                int displayMode, boolean audioEnabled, String authToken, String pinCode,
                String transportHint, String sessionRole, String appPackage, String appActivity,
                String clientPlatform) {
            this(protocolVersion, clientName, clientId, preferredCodec,
                    width, height, dpi, fps, bitrateKbps, displayMode, audioEnabled,
                    authToken, pinCode, transportHint, sessionRole, appPackage, appActivity,
                    clientPlatform, false, 0);
        }

        public HelloMsg(
                int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps,
                int displayMode, boolean audioEnabled, String authToken, String pinCode,
                String transportHint, String sessionRole, String appPackage, String appActivity,
                String clientPlatform, boolean companionEvents) {
            this(protocolVersion, clientName, clientId, preferredCodec,
                    width, height, dpi, fps, bitrateKbps, displayMode, audioEnabled,
                    authToken, pinCode, transportHint, sessionRole, appPackage, appActivity,
                    clientPlatform, companionEvents, 0);
        }

        public HelloMsg(
                int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps,
                int displayMode, boolean audioEnabled, String authToken, String pinCode,
                String transportHint, String sessionRole, String appPackage, String appActivity,
                String clientPlatform, boolean companionEvents, int appUserId) {
            this.protocolVersion = protocolVersion;
            this.clientName = (clientName != null) ? clientName : "Mac";
            this.clientId = (clientId != null) ? clientId : "";
            this.preferredCodec = (preferredCodec != null) ? preferredCodec : "hevc";
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.displayMode = displayMode;
            this.audioEnabled = audioEnabled;
            this.authToken = (authToken != null) ? authToken : "";
            this.pinCode = (pinCode != null) ? pinCode : "";
            this.transportHint = (transportHint != null) ? transportHint.trim() : "";
            String role = (sessionRole != null) ? sessionRole.trim() : "";
            this.sessionRole = role.isEmpty() ? SESSION_ROLE_DESKTOP : role;
            this.appPackage = (appPackage != null) ? appPackage.trim() : "";
            this.appActivity = (appActivity != null) ? appActivity.trim() : "";
            this.appUserId = Math.max(0, appUserId);
            this.clientPlatform = (clientPlatform != null) ? clientPlatform.trim().toLowerCase(java.util.Locale.ROOT) : "";
            this.companionEvents = companionEvents;
        }

        public boolean isStandaloneApp() {
            return SESSION_ROLE_STANDALONE_APP.equalsIgnoreCase(sessionRole);
        }

        public boolean isAppCatalog() {
            return SESSION_ROLE_APP_CATALOG.equalsIgnoreCase(sessionRole);
        }

        public boolean isCompanion() {
            return SESSION_ROLE_COMPANION.equalsIgnoreCase(sessionRole);
        }
    }

    public static HelloMsg decodeHello(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("HELLO payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new HelloMsg(
                    obj.getInt("protocolVersion", 0),
                    obj.getString("clientName", "Mac"),
                    obj.getString("clientId", ""),
                    obj.getString("preferredCodec", "hevc"),
                    obj.getInt("width", 1920),
                    obj.getInt("height", 1080),
                    obj.getInt("dpi", 240),
                    obj.getInt("fps", 60),
                    obj.getInt("bitrateKbps", 12000),
                    obj.getInt("displayMode", DISPLAY_MODE_DEX),
                    obj.getBoolean("audioEnabled", true),
                    obj.getString("authToken", ""),
                    obj.getString("pinCode", ""),
                    obj.getString("transportHint", ""),
                    obj.getString("sessionRole", SESSION_ROLE_DESKTOP),
                    obj.getString("appPackage", ""),
                    obj.getString("appActivity", ""),
                    obj.getString("clientPlatform", ""),
                    obj.getBoolean("companionEvents", false),
                    obj.getInt("appUserId", 0));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid HELLO JSON: " + t.getMessage(), t);
        }
    }

    public static final class ResizeConfig {
        public final int width;
        public final int height;
        public final int dpi;
        public final int fpsX100;
        public final int bitrateKbps;
        public final int displayMode; // 0=DEX, 1=MIRROR, 0xFF=unchanged
        public final int rotation;    // 0..3, 0xFF=unchanged

        public ResizeConfig(
                int width, int height, int dpi, int fpsX100,
                int bitrateKbps, int displayMode, int rotation) {
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.fpsX100 = fpsX100;
            this.bitrateKbps = bitrateKbps;
            this.displayMode = displayMode & 0xFF;
            this.rotation = rotation & 0xFF;
        }
    }

    public static ResizeConfig decodeResizeConfig(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 14) {
            throw new ProtocolException("RESIZE_CONFIG payload must be 14 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int w = bb.getShort() & 0xFFFF;
        int h = bb.getShort() & 0xFFFF;
        int dpi = bb.getShort() & 0xFFFF;
        int fpsX100 = bb.getShort() & 0xFFFF;
        int bitrateKbps = bb.getInt();
        int displayMode = bb.get() & 0xFF;
        int rotation = bb.get() & 0xFF;
        return new ResizeConfig(w, h, dpi, fpsX100, bitrateKbps, displayMode, rotation);
    }

    public static final class MouseEventMsg {
        public final int action;
        public final float x;
        public final float y;
        public final int buttons;
        public final int actionButton;
        public final float hScroll;
        public final float vScroll;

        public MouseEventMsg(
                int action, float x, float y, int buttons,
                int actionButton, float hScroll, float vScroll) {
            this.action = action;
            this.x = x;
            this.y = y;
            this.buttons = buttons;
            this.actionButton = actionButton;
            this.hScroll = hScroll;
            this.vScroll = vScroll;
        }
    }

    public static MouseEventMsg decodeMouseEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 25) {
            throw new ProtocolException("INPUT_MOUSE payload must be 25 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        float x = bb.getFloat();
        float y = bb.getFloat();
        int buttons = bb.getInt();
        int actionButton = bb.getInt();
        float hScroll = bb.getFloat();
        float vScroll = bb.getFloat();
        return new MouseEventMsg(action, x, y, buttons, actionButton, hScroll, vScroll);
    }

    public static final class TouchPointer {
        public final int pointerId;
        public final float x;
        public final float y;
        public final float pressure;

        public TouchPointer(int pointerId, float x, float y, float pressure) {
            this.pointerId = pointerId;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
        }
    }

    public static final class TouchEventMsg {
        public final int action;
        public final int actionIndex;
        public final TouchPointer[] pointers;

        public TouchEventMsg(int action, int actionIndex, TouchPointer[] pointers) {
            this.action = action;
            this.actionIndex = actionIndex;
            this.pointers = (pointers != null) ? pointers : new TouchPointer[0];
        }
    }

    public static TouchEventMsg decodeTouchEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 3) {
            throw new ProtocolException("INPUT_TOUCH payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int actionIndex = bb.get() & 0xFF;
        int count = bb.get() & 0xFF;
        if (count > MAX_TOUCH_POINTERS) {
            throw new ProtocolException("Excessive touch pointerCount: " + count);
        }
        if (payload.length < 3 + count * 16) {
            throw new ProtocolException("INPUT_TOUCH payload truncated for pointerCount=" + count);
        }
        TouchPointer[] pts = new TouchPointer[count];
        for (int i = 0; i < count; i++) {
            int pid = bb.getInt();
            float x = bb.getFloat();
            float y = bb.getFloat();
            float pressure = bb.getFloat();
            pts[i] = new TouchPointer(pid, x, y, pressure);
        }
        return new TouchEventMsg(action, actionIndex, pts);
    }

    public static final class KeyEventMsg {
        public final int action; // 0=DOWN, 1=UP
        public final int androidKeyCode;
        public final int metaState;
        public final int repeatCount;

        public KeyEventMsg(int action, int androidKeyCode, int metaState, int repeatCount) {
            this.action = action;
            this.androidKeyCode = androidKeyCode;
            this.metaState = metaState;
            this.repeatCount = repeatCount;
        }
    }

    public static KeyEventMsg decodeKeyEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 13) {
            throw new ProtocolException("INPUT_KEY payload must be 13 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int keyCode = bb.getInt();
        int meta = bb.getInt();
        int repeat = bb.getInt();
        return new KeyEventMsg(action, keyCode, meta, repeat);
    }

    public static final class TextEventMsg {
        public final int mode; // 0=COMMIT_TEXT_PASTE, 1=SET_CLIPBOARD_ONLY
        public final long seq;
        public final String text;

        public TextEventMsg(int mode, long seq, String text) {
            this.mode = mode;
            this.seq = seq;
            this.text = (text != null) ? text : "";
        }
    }

    public static TextEventMsg decodeTextEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 9) {
            throw new ProtocolException("INPUT_TEXT payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int mode = bb.get() & 0xFF;
        long seq = bb.getLong();
        String text = new String(payload, 9, payload.length - 9, StandardCharsets.UTF_8);
        return new TextEventMsg(mode, seq, text);
    }

    public static final class ImageClipboardEventMsg {
        public final int mode; // 0=PASTE_IMAGE, 1=SET_CLIPBOARD_IMAGE_ONLY
        public final long seq;
        public final byte[] pngData;

        public ImageClipboardEventMsg(int mode, long seq, byte[] pngData) {
            this.mode = mode;
            this.seq = seq;
            this.pngData = (pngData != null) ? pngData : EMPTY_BYTES;
        }
    }

    public static ImageClipboardEventMsg decodeImageClipboardEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 9) {
            throw new ProtocolException("INPUT_TEXT image payload too short");
        }
        int imgLen = payload.length - 9;
        if (imgLen > MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new ProtocolException("Image clipboard payload exceeds 8 MiB limit: " + imgLen);
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int mode = bb.get() & 0xFF;
        long seq = bb.getLong();
        byte[] data = new byte[imgLen];
        if (imgLen > 0) {
            bb.get(data);
        }
        return new ImageClipboardEventMsg(mode, seq, data);
    }

    public static final class WindowActionMsg {
        public final String action;
        public final boolean hasEnabled;
        public final boolean enabled;
        public final int defaultWidthDp;
        public final String pkg;
        public final String cls;
        public final String mode;
        public final int widthPct;
        public final boolean remember;
        public final int slot;
        public final int taskId;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;
        public final boolean hasBounds;
        public final String topology;
        public final List<String> pkgs;
        public final List<Integer> ratios;
        public final String backend;
        public final int userId;

        public WindowActionMsg(
                String action,
                boolean hasEnabled,
                boolean enabled,
                int defaultWidthDp,
                String pkg,
                String cls,
                String mode,
                int widthPct,
                boolean remember,
                int slot,
                int taskId,
                int left,
                int top,
                int right,
                int bottom,
                boolean hasBounds,
                String topology,
                List<String> pkgs,
                List<Integer> ratios,
                String backend) {
            this(action, hasEnabled, enabled, defaultWidthDp, pkg, cls, mode, widthPct, remember,
                    slot, taskId, left, top, right, bottom, hasBounds, topology, pkgs, ratios, backend, 0);
        }

        public WindowActionMsg(
                String action,
                boolean hasEnabled,
                boolean enabled,
                int defaultWidthDp,
                String pkg,
                String cls,
                String mode,
                int widthPct,
                boolean remember,
                int slot,
                int taskId,
                int left,
                int top,
                int right,
                int bottom,
                boolean hasBounds,
                String topology,
                List<String> pkgs,
                List<Integer> ratios,
                String backend,
                int userId) {
            this.action = (action != null) ? action.trim() : "";
            this.hasEnabled = hasEnabled;
            this.enabled = enabled;
            this.defaultWidthDp = defaultWidthDp;
            this.pkg = (pkg != null) ? pkg.trim() : "";
            this.cls = (cls != null) ? cls.trim() : "";
            this.mode = (mode != null) ? mode.trim() : "";
            this.widthPct = widthPct;
            this.remember = remember;
            this.slot = slot;
            this.taskId = taskId;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.hasBounds = hasBounds;
            this.topology = (topology != null) ? topology.trim() : "";
            this.pkgs = (pkgs != null)
                    ? Collections.unmodifiableList(new ArrayList<>(pkgs))
                    : Collections.emptyList();
            this.ratios = (ratios != null)
                    ? Collections.unmodifiableList(new ArrayList<>(ratios))
                    : Collections.emptyList();
            this.backend = (backend != null) ? backend.trim() : "";
            this.userId = Math.max(0, userId);
        }
    }

    public static WindowActionMsg decodeWindowAction(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("WINDOW_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String action = obj.getString("action", "");
            if (action.trim().isEmpty()) {
                throw new ProtocolException("WINDOW_ACTION missing action");
            }
            boolean hasBounds = obj.has("left") && obj.has("top") && obj.has("right") && obj.has("bottom");
            return new WindowActionMsg(
                    action,
                    obj.has("enabled"),
                    obj.getBoolean("enabled", true),
                    obj.getInt("defaultWidthDp", 0),
                    obj.getString("pkg", ""),
                    obj.getString("cls", ""),
                    obj.getString("mode", ""),
                    obj.getInt("widthPct", 0),
                    obj.getBoolean("remember", false),
                    obj.getInt("slot", -1),
                    obj.getInt("taskId", -1),
                    obj.getInt("left", 0),
                    obj.getInt("top", 0),
                    obj.getInt("right", 0),
                    obj.getInt("bottom", 0),
                    hasBounds,
                    obj.getString("topology", ""),
                    obj.getStringList("pkgs"),
                    obj.getIntList("ratios"),
                    obj.getString("backend", ""),
                    obj.getInt("userId", 0));
        } catch (ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid WINDOW_ACTION JSON: " + t.getMessage(), t);
        }
    }

    public static final class SystemActionMsg {
        public final int action;
        public final int arg;

        public SystemActionMsg(int action, int arg) {
            this.action = action;
            this.arg = arg;
        }
    }

    public static SystemActionMsg decodeSystemAction(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 5) {
            throw new ProtocolException("SYSTEM_ACTION payload must be 5 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int arg = bb.getInt();
        return new SystemActionMsg(action, arg);
    }

    public static final class FileTransferChunkMsg {
        public final int transferId;
        public final int chunkFlags;
        public final String fileName;   // populated when START flag set
        public final long totalSize;    // populated when START flag set
        public final byte[] data;       // populated when DATA flag set

        public FileTransferChunkMsg(
                int transferId, int chunkFlags, String fileName, long totalSize, byte[] data) {
            this.transferId = transferId;
            this.chunkFlags = chunkFlags & 0xFF;
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.data = (data != null) ? data : EMPTY_BYTES;
        }
    }

    public static FileTransferChunkMsg decodeFileTransferChunk(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 5) {
            throw new ProtocolException("FILE_TRANSFER_CHUNK payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int transferId = bb.getInt();
        int chunkFlags = bb.get() & 0xFF;
        String fileName = null;
        long totalSize = -1L;
        if ((chunkFlags & FILE_FLAG_START) != 0) {
            if (bb.remaining() < 2) {
                throw new ProtocolException("FILE_TRANSFER_CHUNK START missing fileNameLen");
            }
            int nameLen = bb.getShort() & 0xFFFF;
            if (bb.remaining() < nameLen + 8) {
                throw new ProtocolException("FILE_TRANSFER_CHUNK START truncated");
            }
            byte[] nameBytes = new byte[nameLen];
            bb.get(nameBytes);
            fileName = new String(nameBytes, StandardCharsets.UTF_8);
            totalSize = bb.getLong();
        }
        byte[] data = EMPTY_BYTES;
        if ((chunkFlags & FILE_FLAG_DATA) != 0 && bb.remaining() > 0) {
            data = new byte[bb.remaining()];
            bb.get(data);
        }
        return new FileTransferChunkMsg(transferId, chunkFlags, fileName, totalSize, data);
    }

    // ---- OPD1 v1.2 Companion, Notification, Media, File Browser & APK Install Codecs ----

    public static final class NotificationActionItem {
        public final int index;
        public final String label;

        public NotificationActionItem(int index, String label) {
            this.index = Math.max(0, index);
            this.label = (label != null) ? label : "";
        }
    }

    public static final class NotificationItem {
        public final String key;
        public final String pkg;
        public final String appLabel;
        public final String title;
        public final String text;
        public final long postTimeMs;
        public final boolean clearable;
        public final boolean canReply;
        public final String replyLabel;
        public final String otpCode;
        public final List<NotificationActionItem> actions;

        public NotificationItem(
                String key,
                String pkg,
                String appLabel,
                String title,
                String text,
                long postTimeMs,
                boolean clearable,
                boolean canReply,
                String replyLabel,
                String otpCode) {
            this(key, pkg, appLabel, title, text, postTimeMs, clearable, canReply, replyLabel, otpCode,
                    Collections.emptyList());
        }

        public NotificationItem(
                String key,
                String pkg,
                String appLabel,
                String title,
                String text,
                long postTimeMs,
                boolean clearable,
                boolean canReply,
                String replyLabel,
                String otpCode,
                List<NotificationActionItem> actions) {
            this.key = (key != null) ? key : "";
            this.pkg = (pkg != null) ? pkg : "";
            this.appLabel = (appLabel != null) ? appLabel : "";
            this.title = (title != null) ? title : "";
            this.text = (text != null) ? text : "";
            this.postTimeMs = Math.max(0L, postTimeMs);
            this.clearable = clearable;
            this.canReply = canReply;
            this.replyLabel = (replyLabel != null) ? replyLabel : "";
            this.otpCode = (otpCode != null) ? otpCode : "";
            this.actions = (actions != null && !actions.isEmpty())
                    ? Collections.unmodifiableList(new ArrayList<>(actions))
                    : Collections.emptyList();
        }
    }

    public static final class NotificationEventMsg {
        public final String action; // "snapshot" | "posted" | "removed"
        public final String key;    // populated for "removed"
        public final List<NotificationItem> items;

        public NotificationEventMsg(String action, String key, List<NotificationItem> items) {
            this.action = (action != null) ? action.trim() : "";
            this.key = (key != null) ? key : "";
            this.items = (items != null)
                    ? Collections.unmodifiableList(new ArrayList<>(items))
                    : Collections.emptyList();
        }
    }

    public static byte[] encodeNotificationEvent(NotificationEventMsg msg) {
        NotificationEventMsg m = (msg != null)
                ? msg : new NotificationEventMsg("snapshot", "", Collections.emptyList());
        SimpleJson.ObjectBuilder root = new SimpleJson.ObjectBuilder()
                .put("action", m.action);
        if ("removed".equals(m.action)) {
            root.put("key", m.key);
        } else {
            SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
            for (NotificationItem it : m.items) {
                if (it == null) continue;
                SimpleJson.ArrayBuilder actionsArr = new SimpleJson.ArrayBuilder();
                for (NotificationActionItem act : it.actions) {
                    if (act == null) continue;
                    actionsArr.addRaw(new SimpleJson.ObjectBuilder()
                            .put("index", act.index)
                            .put("label", act.label)
                            .build());
                }
                arr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("key", it.key)
                        .put("pkg", it.pkg)
                        .put("appLabel", it.appLabel)
                        .put("title", it.title)
                        .put("text", it.text)
                        .put("postTimeMs", it.postTimeMs)
                        .put("clearable", it.clearable)
                        .put("canReply", it.canReply)
                        .put("replyLabel", it.replyLabel)
                        .put("otpCode", it.otpCode)
                        .putRaw("actions", actionsArr.build())
                        .build());
            }
            root.putRaw("items", arr.build());
        }
        return root.build().getBytes(StandardCharsets.UTF_8);
    }

    public static NotificationEventMsg decodeNotificationEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("NOTIFICATION_EVENT payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String action = obj.getString("action", "");
            String key = obj.getString("key", "");
            List<SimpleJson.JsonObjectView> rawItems = obj.getObjectList("items");
            List<NotificationItem> items = new ArrayList<>(rawItems.size());
            for (SimpleJson.JsonObjectView it : rawItems) {
                List<SimpleJson.JsonObjectView> rawActions = it.getObjectList("actions");
                List<NotificationActionItem> actions = new ArrayList<>(rawActions.size());
                for (SimpleJson.JsonObjectView rawAct : rawActions) {
                    actions.add(new NotificationActionItem(
                            rawAct.getInt("index", 0),
                            rawAct.getString("label", "")));
                }
                items.add(new NotificationItem(
                        it.getString("key", ""),
                        it.getString("pkg", ""),
                        it.getString("appLabel", ""),
                        it.getString("title", ""),
                        it.getString("text", ""),
                        it.getLong("postTimeMs", 0L),
                        it.getBoolean("clearable", true),
                        it.getBoolean("canReply", false),
                        it.getString("replyLabel", ""),
                        it.getString("otpCode", ""),
                        actions));
            }
            return new NotificationEventMsg(action, key, items);
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid NOTIFICATION_EVENT JSON: " + t.getMessage(), t);
        }
    }

    public static final class NotificationActionMsg {
        public final String op;        // "sync" | "dismiss" | "clear_all" | "reply" | "invoke_action"
        public final String key;
        public final String replyText;
        public final int actionIndex;

        public NotificationActionMsg(String op, String key, String replyText) {
            this(op, key, replyText, -1);
        }

        public NotificationActionMsg(String op, String key, String replyText, int actionIndex) {
            this.op = (op != null) ? op.trim() : "";
            this.key = (key != null) ? key : "";
            this.replyText = (replyText != null) ? replyText : "";
            this.actionIndex = actionIndex;
        }
    }

    public static byte[] encodeNotificationAction(NotificationActionMsg msg) {
        NotificationActionMsg m = (msg != null) ? msg : new NotificationActionMsg("sync", "", "", -1);
        SimpleJson.ObjectBuilder b = new SimpleJson.ObjectBuilder()
                .put("op", m.op)
                .put("key", m.key)
                .put("replyText", m.replyText);
        if (m.actionIndex >= 0 || "invoke_action".equalsIgnoreCase(m.op)) {
            b.put("actionIndex", m.actionIndex);
        }
        return b.build().getBytes(StandardCharsets.UTF_8);
    }

    public static NotificationActionMsg decodeNotificationAction(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("NOTIFICATION_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = obj.getString("op", "");
            if (op.trim().isEmpty()) {
                throw new ProtocolException("NOTIFICATION_ACTION missing op");
            }
            return new NotificationActionMsg(
                    op,
                    obj.getString("key", ""),
                    obj.getString("replyText", ""),
                    obj.getInt("actionIndex", -1));
        } catch (ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid NOTIFICATION_ACTION JSON: " + t.getMessage(), t);
        }
    }

    public static final class MediaStateMsg {
        public final boolean active;
        public final String pkg;
        public final String appLabel;
        public final String title;
        public final String artist;
        public final String album;
        public final boolean playing;
        public final long positionMs;
        public final long durationMs;
        public final String artworkBase64;

        public MediaStateMsg(
                boolean active,
                String pkg,
                String appLabel,
                String title,
                String artist,
                String album,
                boolean playing,
                long positionMs,
                long durationMs,
                String artworkBase64) {
            this.active = active;
            this.pkg = (pkg != null) ? pkg : "";
            this.appLabel = (appLabel != null) ? appLabel : "";
            this.title = (title != null) ? title : "";
            this.artist = (artist != null) ? artist : "";
            this.album = (album != null) ? album : "";
            this.playing = playing;
            this.positionMs = Math.max(0L, positionMs);
            this.durationMs = Math.max(0L, durationMs);
            this.artworkBase64 = (artworkBase64 != null) ? artworkBase64 : "";
        }
    }

    public static byte[] encodeMediaState(MediaStateMsg msg) {
        MediaStateMsg m = (msg != null)
                ? msg : new MediaStateMsg(false, "", "", "", "", "", false, 0L, 0L, "");
        return new SimpleJson.ObjectBuilder()
                .put("active", m.active)
                .put("pkg", m.pkg)
                .put("appLabel", m.appLabel)
                .put("title", m.title)
                .put("artist", m.artist)
                .put("album", m.album)
                .put("playing", m.playing)
                .put("positionMs", m.positionMs)
                .put("durationMs", m.durationMs)
                .put("artworkBase64", m.artworkBase64)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static MediaStateMsg decodeMediaState(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("MEDIA_STATE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new MediaStateMsg(
                    obj.getBoolean("active", false),
                    obj.getString("pkg", ""),
                    obj.getString("appLabel", ""),
                    obj.getString("title", ""),
                    obj.getString("artist", ""),
                    obj.getString("album", ""),
                    obj.getBoolean("playing", false),
                    obj.getLong("positionMs", 0L),
                    obj.getLong("durationMs", 0L),
                    obj.getString("artworkBase64", ""));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid MEDIA_STATE JSON: " + t.getMessage(), t);
        }
    }

    public static final class MediaActionMsg {
        public final String op; // "play" | "pause" | "toggle" | "next" | "previous" | "stop" | "seek"
        public final long positionMs;

        public MediaActionMsg(String op, long positionMs) {
            this.op = (op != null) ? op.trim() : "";
            this.positionMs = Math.max(0L, positionMs);
        }
    }

    public static byte[] encodeMediaAction(MediaActionMsg msg) {
        MediaActionMsg m = (msg != null) ? msg : new MediaActionMsg("toggle", 0L);
        return new SimpleJson.ObjectBuilder()
                .put("op", m.op)
                .put("positionMs", m.positionMs)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static MediaActionMsg decodeMediaAction(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("MEDIA_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = obj.getString("op", "");
            if (op.trim().isEmpty()) {
                throw new ProtocolException("MEDIA_ACTION missing op");
            }
            return new MediaActionMsg(op, obj.getLong("positionMs", 0L));
        } catch (ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid MEDIA_ACTION JSON: " + t.getMessage(), t);
        }
    }

    public static final class FileOpRequestMsg {
        public final int requestId;
        public final String op; // "list"|"stat"|"delete"|"rename"|"mkdir"|"storage_scan"|"pull"|"thumbnail"
        public final String path;
        public final String newPath;
        public final int transferId;
        public final int maxDepth;

        public FileOpRequestMsg(
                int requestId, String op, String path, String newPath, int transferId, int maxDepth) {
            this.requestId = requestId;
            this.op = (op != null) ? op.trim() : "";
            this.path = (path != null) ? path : "";
            this.newPath = (newPath != null) ? newPath : "";
            this.transferId = transferId;
            this.maxDepth = maxDepth;
        }
    }

    public static byte[] encodeFileOpRequest(FileOpRequestMsg msg) {
        FileOpRequestMsg m = (msg != null) ? msg : new FileOpRequestMsg(0, "list", "", "", 0, 0);
        return new SimpleJson.ObjectBuilder()
                .put("requestId", m.requestId)
                .put("op", m.op)
                .put("path", m.path)
                .put("newPath", m.newPath)
                .put("transferId", m.transferId)
                .put("maxDepth", m.maxDepth)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static FileOpRequestMsg decodeFileOpRequest(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("FILE_OP_REQUEST payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = obj.getString("op", "");
            if (op.trim().isEmpty()) {
                throw new ProtocolException("FILE_OP_REQUEST missing op");
            }
            return new FileOpRequestMsg(
                    obj.getInt("requestId", 0),
                    op,
                    obj.getString("path", ""),
                    obj.getString("newPath", ""),
                    obj.getInt("transferId", 0),
                    obj.getInt("maxDepth", 0));
        } catch (ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid FILE_OP_REQUEST JSON: " + t.getMessage(), t);
        }
    }

    public static final class FileEntry {
        public final String name;
        public final String path;
        public final boolean isDir;
        public final long size;
        public final long modifiedMs;
        public final String mime;

        public FileEntry(String name, String path, boolean isDir, long size, long modifiedMs, String mime) {
            this.name = (name != null) ? name : "";
            this.path = (path != null) ? path : "";
            this.isDir = isDir;
            this.size = Math.max(0L, size);
            this.modifiedMs = Math.max(0L, modifiedMs);
            this.mime = (mime != null) ? mime : "";
        }
    }

    public static final class StorageCategory {
        public final String name;
        public final long bytes;
        public final int fileCount;

        public StorageCategory(String name, long bytes, int fileCount) {
            this.name = (name != null) ? name : "";
            this.bytes = Math.max(0L, bytes);
            this.fileCount = Math.max(0, fileCount);
        }
    }

    public static final class LargeFileEntry {
        public final String name;
        public final String path;
        public final long size;
        public final long modifiedMs;

        public LargeFileEntry(String name, String path, long size, long modifiedMs) {
            this.name = (name != null) ? name : "";
            this.path = (path != null) ? path : "";
            this.size = Math.max(0L, size);
            this.modifiedMs = Math.max(0L, modifiedMs);
        }
    }

    public static final class FolderStorageEntry {
        public final String path;
        public final String name;
        public final long bytes;
        public final int fileCount;

        public FolderStorageEntry(String path, String name, long bytes, int fileCount) {
            this.path = (path != null) ? path : "";
            this.name = (name != null) ? name : "";
            this.bytes = Math.max(0L, bytes);
            this.fileCount = Math.max(0, fileCount);
        }
    }

    public static final class AppStorageEntry {
        public final String pkg;
        public final String appLabel;
        public final long apkBytes;
        public final long dataBytes;
        public final long cacheBytes;
        public final long totalBytes;

        public AppStorageEntry(
                String pkg,
                String appLabel,
                long apkBytes,
                long dataBytes,
                long cacheBytes,
                long totalBytes) {
            this.pkg = (pkg != null) ? pkg : "";
            this.appLabel = (appLabel != null) ? appLabel : "";
            this.apkBytes = Math.max(0L, apkBytes);
            this.dataBytes = Math.max(0L, dataBytes);
            this.cacheBytes = Math.max(0L, cacheBytes);
            this.totalBytes = Math.max(0L, totalBytes);
        }
    }

    public static final class StorageReport {
        public final long totalBytes;
        public final long freeBytes;
        public final long usedBytes;
        public final List<StorageCategory> categories;
        public final List<LargeFileEntry> largeFiles;
        public final List<FolderStorageEntry> folders;
        public final List<AppStorageEntry> apps;

        public StorageReport(
                long totalBytes,
                long freeBytes,
                long usedBytes,
                List<StorageCategory> categories,
                List<LargeFileEntry> largeFiles) {
            this(totalBytes, freeBytes, usedBytes, categories, largeFiles,
                    Collections.emptyList(), Collections.emptyList());
        }

        public StorageReport(
                long totalBytes,
                long freeBytes,
                long usedBytes,
                List<StorageCategory> categories,
                List<LargeFileEntry> largeFiles,
                List<FolderStorageEntry> folders,
                List<AppStorageEntry> apps) {
            this.totalBytes = Math.max(0L, totalBytes);
            this.freeBytes = Math.max(0L, freeBytes);
            this.usedBytes = Math.max(0L, usedBytes);
            this.categories = (categories != null)
                    ? Collections.unmodifiableList(new ArrayList<>(categories))
                    : Collections.emptyList();
            this.largeFiles = (largeFiles != null)
                    ? Collections.unmodifiableList(new ArrayList<>(largeFiles))
                    : Collections.emptyList();
            this.folders = (folders != null && !folders.isEmpty())
                    ? Collections.unmodifiableList(new ArrayList<>(folders))
                    : Collections.emptyList();
            this.apps = (apps != null && !apps.isEmpty())
                    ? Collections.unmodifiableList(new ArrayList<>(apps))
                    : Collections.emptyList();
        }
    }

    public static final class FileOpResponseMsg {
        public final int requestId;
        public final String op;
        public final boolean ok;
        public final String error;
        public final String path;
        public final List<FileEntry> entries;
        public final StorageReport storage;
        public final long totalSize;
        public final String thumbnailBase64;

        public FileOpResponseMsg(
                int requestId,
                String op,
                boolean ok,
                String error,
                String path,
                List<FileEntry> entries,
                StorageReport storage,
                long totalSize,
                String thumbnailBase64) {
            this.requestId = requestId;
            this.op = (op != null) ? op : "";
            this.ok = ok;
            this.error = (error != null) ? error : "";
            this.path = (path != null) ? path : "";
            this.entries = (entries != null)
                    ? Collections.unmodifiableList(new ArrayList<>(entries))
                    : Collections.emptyList();
            this.storage = storage;
            this.totalSize = Math.max(0L, totalSize);
            this.thumbnailBase64 = (thumbnailBase64 != null) ? thumbnailBase64 : "";
        }
    }

    public static byte[] encodeFileOpResponse(FileOpResponseMsg msg) {
        FileOpResponseMsg m = (msg != null)
                ? msg
                : new FileOpResponseMsg(0, "", false, "empty", "", Collections.emptyList(), null, 0L, "");
        SimpleJson.ArrayBuilder entriesArr = new SimpleJson.ArrayBuilder();
        for (FileEntry e : m.entries) {
            if (e == null) continue;
            entriesArr.addRaw(new SimpleJson.ObjectBuilder()
                    .put("name", e.name)
                    .put("path", e.path)
                    .put("isDir", e.isDir)
                    .put("size", e.size)
                    .put("modifiedMs", e.modifiedMs)
                    .put("mime", e.mime)
                    .build());
        }
        SimpleJson.ObjectBuilder root = new SimpleJson.ObjectBuilder()
                .put("requestId", m.requestId)
                .put("op", m.op)
                .put("ok", m.ok)
                .put("error", m.error)
                .put("path", m.path)
                .putRaw("entries", entriesArr.build())
                .put("totalSize", m.totalSize)
                .put("thumbnailBase64", m.thumbnailBase64);
        if (m.storage != null) {
            SimpleJson.ArrayBuilder catArr = new SimpleJson.ArrayBuilder();
            for (StorageCategory c : m.storage.categories) {
                if (c == null) continue;
                catArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("name", c.name)
                        .put("bytes", c.bytes)
                        .put("fileCount", c.fileCount)
                        .build());
            }
            SimpleJson.ArrayBuilder largeArr = new SimpleJson.ArrayBuilder();
            for (LargeFileEntry lf : m.storage.largeFiles) {
                if (lf == null) continue;
                largeArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("name", lf.name)
                        .put("path", lf.path)
                        .put("size", lf.size)
                        .put("modifiedMs", lf.modifiedMs)
                        .build());
            }
            SimpleJson.ArrayBuilder foldersArr = new SimpleJson.ArrayBuilder();
            for (FolderStorageEntry fe : m.storage.folders) {
                if (fe == null) continue;
                foldersArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("path", fe.path)
                        .put("name", fe.name)
                        .put("bytes", fe.bytes)
                        .put("fileCount", fe.fileCount)
                        .build());
            }
            SimpleJson.ArrayBuilder appsArr = new SimpleJson.ArrayBuilder();
            for (AppStorageEntry ae : m.storage.apps) {
                if (ae == null) continue;
                appsArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("pkg", ae.pkg)
                        .put("appLabel", ae.appLabel)
                        .put("apkBytes", ae.apkBytes)
                        .put("dataBytes", ae.dataBytes)
                        .put("cacheBytes", ae.cacheBytes)
                        .put("totalBytes", ae.totalBytes)
                        .build());
            }
            root.putRaw("storage", new SimpleJson.ObjectBuilder()
                    .put("totalBytes", m.storage.totalBytes)
                    .put("freeBytes", m.storage.freeBytes)
                    .put("usedBytes", m.storage.usedBytes)
                    .putRaw("categories", catArr.build())
                    .putRaw("largeFiles", largeArr.build())
                    .putRaw("folders", foldersArr.build())
                    .putRaw("apps", appsArr.build())
                    .build());
        }
        return root.build().getBytes(StandardCharsets.UTF_8);
    }

    public static FileOpResponseMsg decodeFileOpResponse(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("FILE_OP_RESPONSE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            List<SimpleJson.JsonObjectView> rawEntries = obj.getObjectList("entries");
            List<FileEntry> entries = new ArrayList<>(rawEntries.size());
            for (SimpleJson.JsonObjectView e : rawEntries) {
                entries.add(new FileEntry(
                        e.getString("name", ""),
                        e.getString("path", ""),
                        e.getBoolean("isDir", false),
                        e.getLong("size", 0L),
                        e.getLong("modifiedMs", 0L),
                        e.getString("mime", "")));
            }
            StorageReport storage = null;
            SimpleJson.JsonObjectView stObj = obj.getObject("storage");
            if (stObj != null) {
                List<SimpleJson.JsonObjectView> rawCats = stObj.getObjectList("categories");
                List<StorageCategory> cats = new ArrayList<>(rawCats.size());
                for (SimpleJson.JsonObjectView c : rawCats) {
                    cats.add(new StorageCategory(
                            c.getString("name", ""),
                            c.getLong("bytes", 0L),
                            c.getInt("fileCount", 0)));
                }
                List<SimpleJson.JsonObjectView> rawLarge = stObj.getObjectList("largeFiles");
                List<LargeFileEntry> largeFiles = new ArrayList<>(rawLarge.size());
                for (SimpleJson.JsonObjectView lf : rawLarge) {
                    largeFiles.add(new LargeFileEntry(
                            lf.getString("name", ""),
                            lf.getString("path", ""),
                            lf.getLong("size", 0L),
                            lf.getLong("modifiedMs", 0L)));
                }
                List<SimpleJson.JsonObjectView> rawFolders = stObj.getObjectList("folders");
                List<FolderStorageEntry> folders = new ArrayList<>(rawFolders.size());
                for (SimpleJson.JsonObjectView fe : rawFolders) {
                    folders.add(new FolderStorageEntry(
                            fe.getString("path", ""),
                            fe.getString("name", ""),
                            fe.getLong("bytes", 0L),
                            fe.getInt("fileCount", 0)));
                }
                List<SimpleJson.JsonObjectView> rawApps = stObj.getObjectList("apps");
                List<AppStorageEntry> apps = new ArrayList<>(rawApps.size());
                for (SimpleJson.JsonObjectView ae : rawApps) {
                    apps.add(new AppStorageEntry(
                            ae.getString("pkg", ""),
                            ae.getString("appLabel", ""),
                            ae.getLong("apkBytes", 0L),
                            ae.getLong("dataBytes", 0L),
                            ae.getLong("cacheBytes", 0L),
                            ae.getLong("totalBytes", 0L)));
                }
                storage = new StorageReport(
                        stObj.getLong("totalBytes", 0L),
                        stObj.getLong("freeBytes", 0L),
                        stObj.getLong("usedBytes", 0L),
                        cats,
                        largeFiles,
                        folders,
                        apps);
            }
            return new FileOpResponseMsg(
                    obj.getInt("requestId", 0),
                    obj.getString("op", ""),
                    obj.getBoolean("ok", false),
                    obj.getString("error", ""),
                    obj.getString("path", ""),
                    entries,
                    storage,
                    obj.getLong("totalSize", 0L),
                    obj.getString("thumbnailBase64", ""));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid FILE_OP_RESPONSE JSON: " + t.getMessage(), t);
        }
    }

    /**
     * Downlink {@code 0x1C TYPE_FILE_PULL_CHUNK} payload (fixed 15-byte BE header + nameOrPath + raw bytes):
     * {@code [4B transferId][1B chunkFlags][8B totalSize][2B nameLen][UTF-8 nameOrPath][raw chunk bytes]}.
     */
    public static final class FilePullChunkMsg {
        public final int transferId;
        public final int chunkFlags;
        public final long totalSize;
        public final String nameOrPath;
        public final byte[] data;

        public FilePullChunkMsg(int transferId, int chunkFlags, long totalSize, String nameOrPath, byte[] data) {
            this.transferId = transferId;
            this.chunkFlags = chunkFlags & 0xFF;
            this.totalSize = Math.max(0L, totalSize);
            this.nameOrPath = (nameOrPath != null) ? nameOrPath : "";
            this.data = (data != null) ? data : EMPTY_BYTES;
        }
    }

    public static byte[] encodeFilePullChunk(
            int transferId,
            int chunkFlags,
            long totalSize,
            String nameOrPath,
            byte[] data,
            int offset,
            int length) throws ProtocolException {
        byte[] nameBytes = (nameOrPath != null && !nameOrPath.isEmpty())
                ? nameOrPath.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES;
        if (nameBytes.length > 0xFFFF) {
            throw new ProtocolException("FILE_PULL_CHUNK nameOrPath exceeds uint16 length: " + nameBytes.length);
        }
        int safeLen = Math.max(0, length);
        if (safeLen > 0 && (data == null || offset < 0 || offset + safeLen > data.length)) {
            throw new ProtocolException("FILE_PULL_CHUNK data bounds out of range");
        }
        int totalPayloadLen = 15 + nameBytes.length + safeLen;
        if (totalPayloadLen > MAX_PAYLOAD_LEN) {
            throw new ProtocolException("FILE_PULL_CHUNK exceeds MAX_PAYLOAD_LEN: " + totalPayloadLen);
        }
        ByteBuffer bb = ByteBuffer.allocate(totalPayloadLen).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) (chunkFlags & 0xFF));
        bb.putLong(Math.max(0L, totalSize));
        bb.putShort((short) (nameBytes.length & 0xFFFF));
        if (nameBytes.length > 0) {
            bb.put(nameBytes);
        }
        if (safeLen > 0) {
            bb.put(data, offset, safeLen);
        }
        return bb.array();
    }

    public static byte[] encodeFilePullChunk(FilePullChunkMsg msg) throws ProtocolException {
        if (msg == null) {
            throw new ProtocolException("FILE_PULL_CHUNK msg is null");
        }
        byte[] d = (msg.data != null) ? msg.data : EMPTY_BYTES;
        return encodeFilePullChunk(msg.transferId, msg.chunkFlags, msg.totalSize, msg.nameOrPath, d, 0, d.length);
    }

    public static FilePullChunkMsg decodeFilePullChunk(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 15) {
            throw new ProtocolException("FILE_PULL_CHUNK payload must be at least 15 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int transferId = bb.getInt();
        int chunkFlags = bb.get() & 0xFF;
        long totalSize = bb.getLong();
        int nameLen = bb.getShort() & 0xFFFF;
        if (bb.remaining() < nameLen) {
            throw new ProtocolException("FILE_PULL_CHUNK truncated nameOrPath: need " + nameLen);
        }
        String nameOrPath = "";
        if (nameLen > 0) {
            byte[] nameBytes = new byte[nameLen];
            bb.get(nameBytes);
            nameOrPath = new String(nameBytes, StandardCharsets.UTF_8);
        }
        byte[] data = EMPTY_BYTES;
        if (bb.remaining() > 0) {
            data = new byte[bb.remaining()];
            bb.get(data);
        }
        return new FilePullChunkMsg(transferId, chunkFlags, totalSize, nameOrPath, data);
    }

    public static final class ApkInstallRequestMsg {
        public final int installId;
        public final String filePath;
        public final String format; // "apk" | "apks" | "apkm" | "xapk"
        public final boolean replaceExisting;
        public final boolean allowDowngrade;

        public ApkInstallRequestMsg(
                int installId,
                String filePath,
                String format,
                boolean replaceExisting,
                boolean allowDowngrade) {
            this.installId = installId;
            this.filePath = (filePath != null) ? filePath.trim() : "";
            this.format = (format != null) ? format.trim().toLowerCase(java.util.Locale.ROOT) : "";
            this.replaceExisting = replaceExisting;
            this.allowDowngrade = allowDowngrade;
        }
    }

    public static byte[] encodeApkInstallRequest(ApkInstallRequestMsg msg) {
        ApkInstallRequestMsg m = (msg != null) ? msg : new ApkInstallRequestMsg(0, "", "apk", true, false);
        return new SimpleJson.ObjectBuilder()
                .put("installId", m.installId)
                .put("filePath", m.filePath)
                .put("format", m.format)
                .put("replaceExisting", m.replaceExisting)
                .put("allowDowngrade", m.allowDowngrade)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static ApkInstallRequestMsg decodeApkInstallRequest(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("APK_INSTALL_REQUEST payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String filePath = obj.getString("filePath", "");
            if (filePath.trim().isEmpty()) {
                throw new ProtocolException("APK_INSTALL_REQUEST missing filePath");
            }
            return new ApkInstallRequestMsg(
                    obj.getInt("installId", 0),
                    filePath,
                    obj.getString("format", ""),
                    obj.getBoolean("replaceExisting", true),
                    obj.getBoolean("allowDowngrade", false));
        } catch (ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid APK_INSTALL_REQUEST JSON: " + t.getMessage(), t);
        }
    }

    public static final class ApkInstallStatusMsg {
        public final int installId;
        public final String stage; // "uploading"|"extracting"|"installing"|"succeeded"|"failed"
        public final int progress; // 0..100
        public final String packageName;
        public final String message;

        public ApkInstallStatusMsg(
                int installId, String stage, int progress, String packageName, String message) {
            this.installId = installId;
            this.stage = (stage != null) ? stage.trim() : "";
            this.progress = Math.max(0, Math.min(100, progress));
            this.packageName = (packageName != null) ? packageName : "";
            this.message = (message != null) ? message : "";
        }
    }

    public static byte[] encodeApkInstallStatus(ApkInstallStatusMsg msg) {
        ApkInstallStatusMsg m = (msg != null)
                ? msg : new ApkInstallStatusMsg(0, "failed", 0, "", "empty");
        return new SimpleJson.ObjectBuilder()
                .put("installId", m.installId)
                .put("stage", m.stage)
                .put("progress", m.progress)
                .put("packageName", m.packageName)
                .put("message", m.message)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static ApkInstallStatusMsg decodeApkInstallStatus(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("APK_INSTALL_STATUS payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new ApkInstallStatusMsg(
                    obj.getInt("installId", 0),
                    obj.getString("stage", ""),
                    obj.getInt("progress", 0),
                    obj.getString("packageName", ""),
                    obj.getString("message", ""));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid APK_INSTALL_STATUS JSON: " + t.getMessage(), t);
        }
    }

    // ---- OPD1 v1.3 Call Continuity Codecs (0x1E CALL_STATE / 0x35 CALL_ACTION) ----

    public static final class CallStateMsg {
        public final String state; // "idle" | "ringing" | "dialing" | "active" | "holding" | "disconnected"
        public final String number;
        public final String contactName;
        public final long startedAtMs;
        public final boolean isMuted;
        public final boolean isSpeakerOn;
        public final boolean canAnswer;
        public final boolean canReject;
        public final boolean canDial;
        public final String source; // "telecom" | "telephony" | "notification" | ""

        public CallStateMsg(
                String state,
                String number,
                String contactName,
                long startedAtMs,
                boolean isMuted,
                boolean isSpeakerOn,
                boolean canAnswer,
                boolean canReject,
                boolean canDial,
                String source) {
            String cleanState = (state != null) ? state.trim().toLowerCase(java.util.Locale.ROOT) : "";
            this.state = cleanState.isEmpty() ? "idle" : cleanState;
            this.number = (number != null) ? number : "";
            this.contactName = (contactName != null) ? contactName : "";
            this.startedAtMs = Math.max(0L, startedAtMs);
            this.isMuted = isMuted;
            this.isSpeakerOn = isSpeakerOn;
            this.canAnswer = canAnswer;
            this.canReject = canReject;
            this.canDial = canDial;
            this.source = (source != null) ? source.trim() : "";
        }

        public boolean isActiveOrRinging() {
            return !"idle".equals(state) && !"disconnected".equals(state);
        }
    }

    public static byte[] encodeCallState(CallStateMsg msg) {
        CallStateMsg m = (msg != null)
                ? msg : new CallStateMsg("idle", "", "", 0L, false, false, false, false, false, "");
        return new SimpleJson.ObjectBuilder()
                .put("state", m.state)
                .put("number", m.number)
                .put("contactName", m.contactName)
                .put("startedAtMs", m.startedAtMs)
                .put("isMuted", m.isMuted)
                .put("isSpeakerOn", m.isSpeakerOn)
                .put("canAnswer", m.canAnswer)
                .put("canReject", m.canReject)
                .put("canDial", m.canDial)
                .put("source", m.source)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static CallStateMsg decodeCallState(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("CALL_STATE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new CallStateMsg(
                    obj.getString("state", "idle"),
                    obj.getString("number", ""),
                    obj.getString("contactName", ""),
                    obj.getLong("startedAtMs", 0L),
                    obj.getBoolean("isMuted", false),
                    obj.getBoolean("isSpeakerOn", false),
                    obj.getBoolean("canAnswer", false),
                    obj.getBoolean("canReject", false),
                    obj.getBoolean("canDial", false),
                    obj.getString("source", ""));
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid CALL_STATE JSON: " + t.getMessage(), t);
        }
    }

    public static final class CallActionMsg {
        public final String op; // "sync" | "answer" | "reject" | "hangup" | "dial" | "mute" | "unmute" | "speaker_on" | "speaker_off" | "reject_with_sms"
        public final String number;
        public final String smsText;

        public CallActionMsg(String op, String number, String smsText) {
            this.op = (op != null) ? op.trim() : "";
            this.number = (number != null) ? number.trim() : "";
            this.smsText = (smsText != null) ? smsText : "";
        }
    }

    public static byte[] encodeCallAction(CallActionMsg msg) {
        CallActionMsg m = (msg != null) ? msg : new CallActionMsg("sync", "", "");
        return new SimpleJson.ObjectBuilder()
                .put("op", m.op)
                .put("number", m.number)
                .put("smsText", m.smsText)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static CallActionMsg decodeCallAction(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("CALL_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = obj.getString("op", "");
            if (op.trim().isEmpty()) {
                throw new ProtocolException("CALL_ACTION missing op");
            }
            return new CallActionMsg(
                    op,
                    obj.getString("number", ""),
                    obj.getString("smsText", ""));
        } catch (ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid CALL_ACTION JSON: " + t.getMessage(), t);
        }
    }
}
