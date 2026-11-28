package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

/**
 * OpalCast v1.1/v1.2/v1.3 server-side binary framing and payload codec facade (Big-Endian network byte order).
 * Byte-for-byte compatible with OpalDeskMac (Swift OpalCastFrame):
 * - Holds protocol constants and the 18-byte OPD1 frame header encoder/decoder
 * - Declares canonical public nested message types backed by payload base classes
 * - Delegates payload codecs to {@link OpalCastSessionPayloads}, {@link OpalCastInputPayloads},
 *   {@link OpalCastMediaPayloads}, {@link OpalCastFilePayloads}, and {@link OpalCastCompanionPayloads}
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
        public ProtocolException(String message) { super(message); }
        public ProtocolException(String message, Throwable cause) { super(message, cause); }
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
            OutputStream out, int type, int flags, long timestampUs,
            byte[] payload, int offset, int length) throws IOException {
        writeFrame(out, new byte[HEADER_SIZE], type, flags, timestampUs, payload, offset, length);
    }

    public static void writeFrame(
            OutputStream out, byte[] headerBuf, int type, int flags, long timestampUs,
            byte[] payload, int offset, int length) throws IOException {
        if (length < 0 || length > MAX_PAYLOAD_LEN) {
            throw new ProtocolException("Invalid payload length: " + length);
        }
        if (length > 0 && (payload == null || offset < 0 || offset + length > payload.length)) {
            throw new ProtocolException("Payload bounds out of range");
        }
        byte[] header = (headerBuf != null && headerBuf.length >= HEADER_SIZE) ? headerBuf : new byte[HEADER_SIZE];
        encodeHeader(header, type, flags, length, timestampUs);
        out.write(header, 0, HEADER_SIZE);
        if (length > 0) out.write(payload, offset, length);
    }

    public static void encodeHeader(byte[] header, int type, int flags, int payloadLen, long timestampUs) {
        header[0] = MAGIC_0; header[1] = MAGIC_1; header[2] = MAGIC_2; header[3] = MAGIC_3;
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

    public static Frame readFrame(InputStream in) throws IOException {
        return readFrame(in, new byte[HEADER_SIZE]);
    }

    public static Frame readFrame(InputStream in, byte[] reusableHeaderBuf) throws IOException {
        byte[] header = (reusableHeaderBuf != null && reusableHeaderBuf.length >= HEADER_SIZE)
                ? reusableHeaderBuf : new byte[HEADER_SIZE];
        int first = in.read();
        if (first < 0) return null;
        header[0] = (byte) first;
        readFully(in, header, 1, HEADER_SIZE - 1);
        DecodedHeader dh = parseHeader(header);
        byte[] payload = (dh.payloadLen == 0) ? EMPTY_BYTES : new byte[dh.payloadLen];
        if (dh.payloadLen > 0) readFully(in, payload, 0, dh.payloadLen);
        return new Frame(dh.type, dh.flags, dh.timestampUs, payload);
    }

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
        if (dh.payloadLen > 0) System.arraycopy(raw, HEADER_SIZE, payload, 0, dh.payloadLen);
        return new Frame(dh.type, dh.flags, dh.timestampUs, payload);
    }

    private static final class DecodedHeader {
        final int type, flags, payloadLen;
        final long timestampUs;
        DecodedHeader(int type, int flags, int payloadLen, long timestampUs) {
            this.type = type; this.flags = flags; this.payloadLen = payloadLen; this.timestampUs = timestampUs;
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
        long payloadLenUnsigned = ((long) (header[6] & 0xFF) << 24)
                | ((long) (header[7] & 0xFF) << 16)
                | ((long) (header[8] & 0xFF) << 8)
                | ((long) (header[9] & 0xFF));
        if (payloadLenUnsigned > MAX_PAYLOAD_LEN) {
            throw new ProtocolException("Payload exceeds 16 MiB limit: " + payloadLenUnsigned);
        }
        long timestampUs = ((long) (header[10] & 0xFF) << 56)
                | ((long) (header[11] & 0xFF) << 48)
                | ((long) (header[12] & 0xFF) << 40)
                | ((long) (header[13] & 0xFF) << 32)
                | ((long) (header[14] & 0xFF) << 24)
                | ((long) (header[15] & 0xFF) << 16)
                | ((long) (header[16] & 0xFF) << 8)
                | ((long) (header[17] & 0xFF));
        return new DecodedHeader(type, flags, (int) payloadLenUnsigned, timestampUs);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int remaining = len, pos = off;
        while (remaining > 0) {
            int n = in.read(buf, pos, remaining);
            if (n < 0) {
                throw new EOFException("Unexpected EOF while reading OpalCast frame (" + remaining + " bytes remaining)");
            }
            pos += n;
            remaining -= n;
        }
    }

    // ---- Session payload canonical types & codec delegates ----

    public static final class DeviceState extends OpalCastSessionPayloads.DeviceStateBase {
        public DeviceState(String deviceName, int sinkType, int displayMode, int width, int height, int dpi,
                double fps, int rotation, int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation, bitrateKbps, batteryPct,
                    blackout, audioEnabled, 1, true, 460, "FULLSCREEN", "", "");
        }
        public DeviceState(String deviceName, int sinkType, int displayMode, int width, int height, int dpi,
                double fps, int rotation, int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation, bitrateKbps, batteryPct,
                    blackout, audioEnabled, injectStatus, adaptiveSingleEnabled, defaultSingleWidthDp,
                    activeLayoutMode, topAppPackage, topAppLabel, Collections.emptyList(), Collections.emptyList(), "", "", "");
        }
        public DeviceState(String deviceName, int sinkType, int displayMode, int width, int height, int dpi,
                double fps, int rotation, int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel,
                List<String> tilingSlots, List<Integer> tilingRatios) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation, bitrateKbps, batteryPct,
                    blackout, audioEnabled, injectStatus, adaptiveSingleEnabled, defaultSingleWidthDp,
                    activeLayoutMode, topAppPackage, topAppLabel, tilingSlots, tilingRatios, "", "", "");
        }
        public DeviceState(String deviceName, int sinkType, int displayMode, int width, int height, int dpi,
                double fps, int rotation, int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel,
                List<String> tilingSlots, List<Integer> tilingRatios, String fullscreenWindowTitle) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation, bitrateKbps, batteryPct,
                    blackout, audioEnabled, injectStatus, adaptiveSingleEnabled, defaultSingleWidthDp,
                    activeLayoutMode, topAppPackage, topAppLabel, tilingSlots, tilingRatios, fullscreenWindowTitle, "", "");
        }
        public DeviceState(String deviceName, int sinkType, int displayMode, int width, int height, int dpi,
                double fps, int rotation, int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel,
                List<String> tilingSlots, List<Integer> tilingRatios, String fullscreenWindowTitle,
                String hostTopAppPackage, String hostTopAppLabel) {
            super(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation, bitrateKbps, batteryPct,
                    blackout, audioEnabled, injectStatus, adaptiveSingleEnabled, defaultSingleWidthDp,
                    activeLayoutMode, topAppPackage, topAppLabel, tilingSlots, tilingRatios, fullscreenWindowTitle,
                    hostTopAppPackage, hostTopAppLabel);
        }
    }

    public static final class AuthResultMsg extends OpalCastSessionPayloads.AuthResultMsgBase {
        public AuthResultMsg(boolean ok, String authToken, String message, String deviceName, String certFingerprint) {
            this(ok, authToken, message, deviceName, certFingerprint, authToken != null && !authToken.isEmpty());
        }
        public AuthResultMsg(boolean ok, String authToken, String message, String deviceName, String certFingerprint, boolean permanent) {
            super(ok, authToken, message, deviceName, certFingerprint, permanent);
        }
        public AuthResultMsg(
                boolean ok, String authToken, String message, String deviceName, String certFingerprint,
                boolean permanent, int udpMediaPort, int udpSessionId, String udpKeyBase64, int udpFecPct) {
            super(ok, authToken, message, deviceName, certFingerprint, permanent,
                    udpMediaPort, udpSessionId, udpKeyBase64, udpFecPct);
        }
    }

    public static final class WifiUpgradeReadyMsg extends OpalCastSessionPayloads.WifiUpgradeReadyMsgBase {
        public WifiUpgradeReadyMsg(boolean ok, String host, int port, String certFingerprint, String authToken, String message) {
            super(ok, host, port, certFingerprint, authToken, message);
        }
    }

    public static final class AppEntry extends OpalCastSessionPayloads.AppEntryBase {
        public AppEntry(String pkg, String cls, String label, boolean runningOnExt, int taskId) {
            this(pkg, cls, label, runningOnExt, taskId, "", 0, 0, "");
        }
        public AppEntry(String pkg, String cls, String label, boolean runningOnExt, int taskId, String iconPngBase64) {
            this(pkg, cls, label, runningOnExt, taskId, iconPngBase64, 0, 0, "");
        }
        public AppEntry(String pkg, String cls, String label, boolean runningOnExt, int taskId, String iconPngBase64, int openCount) {
            this(pkg, cls, label, runningOnExt, taskId, iconPngBase64, openCount, 0, "");
        }
        public AppEntry(String pkg, String cls, String label, boolean runningOnExt, int taskId,
                String iconPngBase64, int openCount, int userId, String profileType) {
            super(pkg, cls, label, runningOnExt, taskId, iconPngBase64, openCount, userId, profileType);
        }
    }

    public static final class AppListMsg extends OpalCastSessionPayloads.AppListMsgBase {
        public AppListMsg(List<AppEntry> apps) { super(apps); }
    }

    public static final class HelloMsg extends OpalCastSessionPayloads.HelloMsgBase {
        public HelloMsg(int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps, int displayMode,
                boolean audioEnabled, String authToken, String pinCode) {
            this(protocolVersion, clientName, clientId, preferredCodec, width, height, dpi, fps, bitrateKbps,
                    displayMode, audioEnabled, authToken, pinCode, "", SESSION_ROLE_DESKTOP, "", "", "", false, 0);
        }
        public HelloMsg(int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps, int displayMode,
                boolean audioEnabled, String authToken, String pinCode,
                String transportHint, String sessionRole, String appPackage, String appActivity) {
            this(protocolVersion, clientName, clientId, preferredCodec, width, height, dpi, fps, bitrateKbps,
                    displayMode, audioEnabled, authToken, pinCode, transportHint, sessionRole, appPackage, appActivity, "", false, 0);
        }
        public HelloMsg(int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps, int displayMode,
                boolean audioEnabled, String authToken, String pinCode,
                String transportHint, String sessionRole, String appPackage, String appActivity, String clientPlatform) {
            this(protocolVersion, clientName, clientId, preferredCodec, width, height, dpi, fps, bitrateKbps,
                    displayMode, audioEnabled, authToken, pinCode, transportHint, sessionRole, appPackage, appActivity, clientPlatform, false, 0);
        }
        public HelloMsg(int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps, int displayMode,
                boolean audioEnabled, String authToken, String pinCode, String transportHint, String sessionRole,
                String appPackage, String appActivity, String clientPlatform, boolean companionEvents) {
            this(protocolVersion, clientName, clientId, preferredCodec, width, height, dpi, fps, bitrateKbps,
                    displayMode, audioEnabled, authToken, pinCode, transportHint, sessionRole, appPackage, appActivity,
                    clientPlatform, companionEvents, 0);
        }
        public HelloMsg(int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps, int displayMode,
                boolean audioEnabled, String authToken, String pinCode, String transportHint, String sessionRole,
                String appPackage, String appActivity, String clientPlatform, boolean companionEvents, int appUserId) {
            super(protocolVersion, clientName, clientId, preferredCodec, width, height, dpi, fps, bitrateKbps,
                    displayMode, audioEnabled, authToken, pinCode, transportHint, sessionRole, appPackage, appActivity,
                    clientPlatform, companionEvents, appUserId);
        }
        public HelloMsg(int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps, int displayMode,
                boolean audioEnabled, String authToken, String pinCode, String transportHint, String sessionRole,
                String appPackage, String appActivity, String clientPlatform, boolean companionEvents, int appUserId,
                int udpMediaPort, int udpFecPct) {
            super(protocolVersion, clientName, clientId, preferredCodec, width, height, dpi, fps, bitrateKbps,
                    displayMode, audioEnabled, authToken, pinCode, transportHint, sessionRole, appPackage, appActivity,
                    clientPlatform, companionEvents, appUserId, udpMediaPort, udpFecPct);
        }
    }

    public static final class ResizeConfig extends OpalCastSessionPayloads.ResizeConfigBase {
        public ResizeConfig(int width, int height, int dpi, int fpsX100, int bitrateKbps, int displayMode, int rotation) {
            super(width, height, dpi, fpsX100, bitrateKbps, displayMode, rotation);
        }
    }

    public static byte[] encodeDeviceState(DeviceState s) { return OpalCastSessionPayloads.encodeDeviceState(s); }
    public static byte[] encodeAuthResult(AuthResultMsg msg) { return OpalCastSessionPayloads.encodeAuthResult(msg); }
    public static byte[] encodeWifiUpgradeReady(WifiUpgradeReadyMsg msg) { return OpalCastSessionPayloads.encodeWifiUpgradeReady(msg); }
    public static byte[] encodeAppList(AppListMsg msg) { return OpalCastSessionPayloads.encodeAppList(msg); }
    public static byte[] encodeImeRequest(boolean show, int inputType) { return OpalCastSessionPayloads.encodeImeRequest(show, inputType); }
    public static byte[] encodePingPong(long pingId) { return OpalCastSessionPayloads.encodePingPong(pingId); }
    public static long decodePingPong(byte[] payload) throws ProtocolException { return OpalCastSessionPayloads.decodePingPong(payload); }
    public static HelloMsg decodeHello(byte[] payload) throws ProtocolException { return OpalCastSessionPayloads.decodeHello(payload); }
    public static ResizeConfig decodeResizeConfig(byte[] payload) throws ProtocolException { return OpalCastSessionPayloads.decodeResizeConfig(payload); }

    // ---- Input & clipboard canonical types & codec delegates ----

    public static final class MouseEventMsg extends OpalCastInputPayloads.MouseEventMsgBase {
        public MouseEventMsg(int action, float x, float y, int buttons, int actionButton, float hScroll, float vScroll) {
            super(action, x, y, buttons, actionButton, hScroll, vScroll);
        }
    }
    public static final class TouchPointer extends OpalCastInputPayloads.TouchPointerBase {
        public TouchPointer(int pointerId, float x, float y, float pressure) { super(pointerId, x, y, pressure); }
    }
    public static final class TouchEventMsg extends OpalCastInputPayloads.TouchEventMsgBase {
        public TouchEventMsg(int action, int actionIndex, TouchPointer[] pointers) { super(action, actionIndex, pointers); }
    }
    public static final class KeyEventMsg extends OpalCastInputPayloads.KeyEventMsgBase {
        public KeyEventMsg(int action, int androidKeyCode, int metaState, int repeatCount) { super(action, androidKeyCode, metaState, repeatCount); }
    }
    public static final class TextEventMsg extends OpalCastInputPayloads.TextEventMsgBase {
        public TextEventMsg(int mode, long seq, String text) { super(mode, seq, text); }
    }
    public static final class ImageClipboardEventMsg extends OpalCastInputPayloads.ImageClipboardEventMsgBase {
        public ImageClipboardEventMsg(int mode, long seq, byte[] pngData) { super(mode, seq, pngData); }
    }
    public static final class WindowActionMsg extends OpalCastInputPayloads.WindowActionMsgBase {
        public WindowActionMsg(String action, boolean hasEnabled, boolean enabled, int defaultWidthDp,
                String pkg, String cls, String mode, int widthPct, boolean remember, int slot, int taskId,
                int left, int top, int right, int bottom, boolean hasBounds, String topology,
                List<String> pkgs, List<Integer> ratios, String backend) {
            this(action, hasEnabled, enabled, defaultWidthDp, pkg, cls, mode, widthPct, remember,
                    slot, taskId, left, top, right, bottom, hasBounds, topology, pkgs, ratios, backend, 0);
        }
        public WindowActionMsg(String action, boolean hasEnabled, boolean enabled, int defaultWidthDp,
                String pkg, String cls, String mode, int widthPct, boolean remember, int slot, int taskId,
                int left, int top, int right, int bottom, boolean hasBounds, String topology,
                List<String> pkgs, List<Integer> ratios, String backend, int userId) {
            super(action, hasEnabled, enabled, defaultWidthDp, pkg, cls, mode, widthPct, remember,
                    slot, taskId, left, top, right, bottom, hasBounds, topology, pkgs, ratios, backend, userId);
        }
    }
    public static final class SystemActionMsg extends OpalCastInputPayloads.SystemActionMsgBase {
        public SystemActionMsg(int action, int arg) { super(action, arg); }
    }

    public static byte[] encodeClipboardPush(long seq, String text) { return OpalCastInputPayloads.encodeClipboardPush(seq, text); }
    public static byte[] encodeClipboardImagePush(long seq, byte[] pngData) throws ProtocolException { return OpalCastInputPayloads.encodeClipboardImagePush(seq, pngData); }
    public static MouseEventMsg decodeMouseEvent(byte[] payload) throws ProtocolException { return OpalCastInputPayloads.decodeMouseEvent(payload); }
    public static TouchEventMsg decodeTouchEvent(byte[] payload) throws ProtocolException { return OpalCastInputPayloads.decodeTouchEvent(payload); }
    public static KeyEventMsg decodeKeyEvent(byte[] payload) throws ProtocolException { return OpalCastInputPayloads.decodeKeyEvent(payload); }
    public static TextEventMsg decodeTextEvent(byte[] payload) throws ProtocolException { return OpalCastInputPayloads.decodeTextEvent(payload); }
    public static ImageClipboardEventMsg decodeImageClipboardEvent(byte[] payload) throws ProtocolException { return OpalCastInputPayloads.decodeImageClipboardEvent(payload); }
    public static WindowActionMsg decodeWindowAction(byte[] payload) throws ProtocolException { return OpalCastInputPayloads.decodeWindowAction(payload); }
    public static SystemActionMsg decodeSystemAction(byte[] payload) throws ProtocolException { return OpalCastInputPayloads.decodeSystemAction(payload); }

    // ---- Media, mic & camera canonical types & codec delegates ----

    public static final class VideoConfig extends OpalCastMediaPayloads.VideoConfigBase {
        public VideoConfig(int codec, int width, int height, int dpi, int rotation, int fpsX100, int displayMode, byte[] csdAnnexB) {
            super(codec, width, height, dpi, rotation, fpsX100, displayMode, csdAnnexB);
        }
    }
    public static final class AudioConfig extends OpalCastMediaPayloads.AudioConfigBase {
        public AudioConfig(int codec, int sampleRate, int channels, byte[] audioSpecificConfig) {
            super(codec, sampleRate, channels, audioSpecificConfig);
        }
    }
    public static final class MicStateMsg extends OpalCastMediaPayloads.MicStateMsgBase {
        public MicStateMsg(boolean active, boolean supported, int sampleRate, int channels, String message) {
            super(active, supported, sampleRate, channels, message);
        }
    }
    public static final class MicConfigMsg extends OpalCastMediaPayloads.MicConfigMsgBase {
        public MicConfigMsg(boolean enabled, String codec, int sampleRate, int channels) {
            super(enabled, codec, sampleRate, channels);
        }
    }
    public static final class CameraStateMsg extends OpalCastMediaPayloads.CameraStateMsgBase {
        public CameraStateMsg(boolean active, boolean supported, boolean rootRequired, int width, int height, int fps, String backend, String message) {
            this(active, supported, rootRequired, width, height, fps, backend, message, "");
        }
        public CameraStateMsg(boolean active, boolean supported, boolean rootRequired, int width, int height, int fps, String backend, String message, String facing) {
            super(active, supported, rootRequired, width, height, fps, backend, message, facing);
        }
    }
    public static final class CameraConfigMsg extends OpalCastMediaPayloads.CameraConfigMsgBase {
        public CameraConfigMsg(boolean enabled, String codec, int width, int height, int fps, String facing) {
            super(enabled, codec, width, height, fps, facing);
        }
    }
    public static final class MediaStateMsg extends OpalCastMediaPayloads.MediaStateMsgBase {
        public MediaStateMsg(boolean active, String pkg, String appLabel, String title, String artist,
                String album, boolean playing, long positionMs, long durationMs, String artworkBase64) {
            super(active, pkg, appLabel, title, artist, album, playing, positionMs, durationMs, artworkBase64);
        }
    }
    public static final class MediaActionMsg extends OpalCastMediaPayloads.MediaActionMsgBase {
        public MediaActionMsg(String op, long positionMs) { super(op, positionMs); }
    }

    public static byte[] encodeVideoConfig(VideoConfig cfg) { return OpalCastMediaPayloads.encodeVideoConfig(cfg); }
    public static byte[] encodeAudioConfig(AudioConfig cfg) { return OpalCastMediaPayloads.encodeAudioConfig(cfg); }
    public static byte[] encodeMicState(MicStateMsg msg) { return OpalCastMediaPayloads.encodeMicState(msg); }
    public static MicStateMsg decodeMicState(byte[] payload) throws ProtocolException { return OpalCastMediaPayloads.decodeMicState(payload); }
    public static byte[] encodeMicConfig(MicConfigMsg msg) { return OpalCastMediaPayloads.encodeMicConfig(msg); }
    public static MicConfigMsg decodeMicConfig(byte[] payload) throws ProtocolException { return OpalCastMediaPayloads.decodeMicConfig(payload); }
    public static byte[] encodeCameraState(CameraStateMsg msg) { return OpalCastMediaPayloads.encodeCameraState(msg); }
    public static CameraStateMsg decodeCameraState(byte[] payload) throws ProtocolException { return OpalCastMediaPayloads.decodeCameraState(payload); }
    public static byte[] encodeCameraConfig(CameraConfigMsg msg) { return OpalCastMediaPayloads.encodeCameraConfig(msg); }
    public static CameraConfigMsg decodeCameraConfig(byte[] payload) throws ProtocolException { return OpalCastMediaPayloads.decodeCameraConfig(payload); }
    public static byte[] encodeMediaState(MediaStateMsg msg) { return OpalCastMediaPayloads.encodeMediaState(msg); }
    public static MediaStateMsg decodeMediaState(byte[] payload) throws ProtocolException { return OpalCastMediaPayloads.decodeMediaState(payload); }
    public static byte[] encodeMediaAction(MediaActionMsg msg) { return OpalCastMediaPayloads.encodeMediaAction(msg); }
    public static MediaActionMsg decodeMediaAction(byte[] payload) throws ProtocolException { return OpalCastMediaPayloads.decodeMediaAction(payload); }

    // ---- File transfer & remote file browser canonical types & codec delegates ----

    public static final class FileTransferChunkMsg extends OpalCastFilePayloads.FileTransferChunkMsgBase {
        public FileTransferChunkMsg(int transferId, int chunkFlags, String fileName, long totalSize, byte[] data) {
            super(transferId, chunkFlags, fileName, totalSize, data);
        }
    }
    public static final class FileOpRequestMsg extends OpalCastFilePayloads.FileOpRequestMsgBase {
        public FileOpRequestMsg(int requestId, String op, String path, String newPath, int transferId, int maxDepth) {
            super(requestId, op, path, newPath, transferId, maxDepth);
        }
    }
    public static final class FileEntry extends OpalCastFilePayloads.FileEntryBase {
        public FileEntry(String name, String path, boolean isDir, long size, long modifiedMs, String mime) {
            super(name, path, isDir, size, modifiedMs, mime);
        }
    }
    public static final class StorageCategory extends OpalCastFilePayloads.StorageCategoryBase {
        public StorageCategory(String name, long bytes, int fileCount) { super(name, bytes, fileCount); }
    }
    public static final class LargeFileEntry extends OpalCastFilePayloads.LargeFileEntryBase {
        public LargeFileEntry(String name, String path, long size, long modifiedMs) { super(name, path, size, modifiedMs); }
    }
    public static final class FolderStorageEntry extends OpalCastFilePayloads.FolderStorageEntryBase {
        public FolderStorageEntry(String path, String name, long bytes, int fileCount) { super(path, name, bytes, fileCount); }
    }
    public static final class AppStorageEntry extends OpalCastFilePayloads.AppStorageEntryBase {
        public AppStorageEntry(String pkg, String appLabel, long apkBytes, long dataBytes, long cacheBytes, long totalBytes) {
            super(pkg, appLabel, apkBytes, dataBytes, cacheBytes, totalBytes);
        }
    }
    public static final class StorageReport extends OpalCastFilePayloads.StorageReportBase {
        public StorageReport(long totalBytes, long freeBytes, long usedBytes, List<StorageCategory> categories, List<LargeFileEntry> largeFiles) {
            this(totalBytes, freeBytes, usedBytes, categories, largeFiles, Collections.emptyList(), Collections.emptyList());
        }
        public StorageReport(long totalBytes, long freeBytes, long usedBytes, List<StorageCategory> categories,
                List<LargeFileEntry> largeFiles, List<FolderStorageEntry> folders, List<AppStorageEntry> apps) {
            super(totalBytes, freeBytes, usedBytes, categories, largeFiles, folders, apps);
        }
    }
    public static final class FileOpResponseMsg extends OpalCastFilePayloads.FileOpResponseMsgBase {
        public FileOpResponseMsg(int requestId, String op, boolean ok, String error, String path,
                List<FileEntry> entries, StorageReport storage, long totalSize, String thumbnailBase64) {
            super(requestId, op, ok, error, path, entries, storage, totalSize, thumbnailBase64);
        }
    }
    public static final class FilePullChunkMsg extends OpalCastFilePayloads.FilePullChunkMsgBase {
        public FilePullChunkMsg(int transferId, int chunkFlags, long totalSize, String nameOrPath, byte[] data) {
            super(transferId, chunkFlags, totalSize, nameOrPath, data);
        }
    }

    public static byte[] encodeFileTransferAck(int transferId, int status, String pathOrError) { return OpalCastFilePayloads.encodeFileTransferAck(transferId, status, pathOrError); }
    public static FileTransferChunkMsg decodeFileTransferChunk(byte[] payload) throws ProtocolException { return OpalCastFilePayloads.decodeFileTransferChunk(payload); }
    public static byte[] encodeFileOpRequest(FileOpRequestMsg msg) { return OpalCastFilePayloads.encodeFileOpRequest(msg); }
    public static FileOpRequestMsg decodeFileOpRequest(byte[] payload) throws ProtocolException { return OpalCastFilePayloads.decodeFileOpRequest(payload); }
    public static byte[] encodeFileOpResponse(FileOpResponseMsg msg) { return OpalCastFilePayloads.encodeFileOpResponse(msg); }
    public static FileOpResponseMsg decodeFileOpResponse(byte[] payload) throws ProtocolException { return OpalCastFilePayloads.decodeFileOpResponse(payload); }
    public static byte[] encodeFilePullChunk(int transferId, int chunkFlags, long totalSize, String nameOrPath, byte[] data, int offset, int length) throws ProtocolException {
        return OpalCastFilePayloads.encodeFilePullChunk(transferId, chunkFlags, totalSize, nameOrPath, data, offset, length);
    }
    public static byte[] encodeFilePullChunk(FilePullChunkMsg msg) throws ProtocolException { return OpalCastFilePayloads.encodeFilePullChunk(msg); }
    public static FilePullChunkMsg decodeFilePullChunk(byte[] payload) throws ProtocolException { return OpalCastFilePayloads.decodeFilePullChunk(payload); }

    // ---- Companion notification, APK install & call continuity canonical types & codec delegates ----

    public static final class NotificationActionItem extends OpalCastCompanionPayloads.NotificationActionItemBase {
        public NotificationActionItem(int index, String label) { super(index, label); }
    }
    public static final class NotificationItem extends OpalCastCompanionPayloads.NotificationItemBase {
        public NotificationItem(String key, String pkg, String appLabel, String title, String text,
                long postTimeMs, boolean clearable, boolean canReply, String replyLabel, String otpCode) {
            this(key, pkg, appLabel, title, text, postTimeMs, clearable, canReply, replyLabel, otpCode, Collections.emptyList());
        }
        public NotificationItem(String key, String pkg, String appLabel, String title, String text,
                long postTimeMs, boolean clearable, boolean canReply, String replyLabel, String otpCode, List<NotificationActionItem> actions) {
            super(key, pkg, appLabel, title, text, postTimeMs, clearable, canReply, replyLabel, otpCode, actions);
        }
    }
    public static final class NotificationEventMsg extends OpalCastCompanionPayloads.NotificationEventMsgBase {
        public NotificationEventMsg(String action, String key, List<NotificationItem> items) { super(action, key, items); }
    }
    public static final class NotificationActionMsg extends OpalCastCompanionPayloads.NotificationActionMsgBase {
        public NotificationActionMsg(String op, String key, String replyText) { this(op, key, replyText, -1); }
        public NotificationActionMsg(String op, String key, String replyText, int actionIndex) { super(op, key, replyText, actionIndex); }
    }
    public static final class ApkInstallRequestMsg extends OpalCastCompanionPayloads.ApkInstallRequestMsgBase {
        public ApkInstallRequestMsg(int installId, String filePath, String format, boolean replaceExisting, boolean allowDowngrade) {
            super(installId, filePath, format, replaceExisting, allowDowngrade);
        }
    }
    public static final class ApkInstallStatusMsg extends OpalCastCompanionPayloads.ApkInstallStatusMsgBase {
        public ApkInstallStatusMsg(int installId, String stage, int progress, String packageName, String message) {
            super(installId, stage, progress, packageName, message);
        }
    }
    public static final class CallStateMsg extends OpalCastCompanionPayloads.CallStateMsgBase {
        public CallStateMsg(String state, String number, String contactName, long startedAtMs,
                boolean isMuted, boolean isSpeakerOn, boolean canAnswer, boolean canReject, boolean canDial, String source) {
            super(state, number, contactName, startedAtMs, isMuted, isSpeakerOn, canAnswer, canReject, canDial, source);
        }
    }
    public static final class CallActionMsg extends OpalCastCompanionPayloads.CallActionMsgBase {
        public CallActionMsg(String op, String number, String smsText) { super(op, number, smsText); }
    }

    public static byte[] encodeNotificationEvent(NotificationEventMsg msg) { return OpalCastCompanionPayloads.encodeNotificationEvent(msg); }
    public static NotificationEventMsg decodeNotificationEvent(byte[] payload) throws ProtocolException { return OpalCastCompanionPayloads.decodeNotificationEvent(payload); }
    public static byte[] encodeNotificationAction(NotificationActionMsg msg) { return OpalCastCompanionPayloads.encodeNotificationAction(msg); }
    public static NotificationActionMsg decodeNotificationAction(byte[] payload) throws ProtocolException { return OpalCastCompanionPayloads.decodeNotificationAction(payload); }
    public static byte[] encodeApkInstallRequest(ApkInstallRequestMsg msg) { return OpalCastCompanionPayloads.encodeApkInstallRequest(msg); }
    public static ApkInstallRequestMsg decodeApkInstallRequest(byte[] payload) throws ProtocolException { return OpalCastCompanionPayloads.decodeApkInstallRequest(payload); }
    public static byte[] encodeApkInstallStatus(ApkInstallStatusMsg msg) { return OpalCastCompanionPayloads.encodeApkInstallStatus(msg); }
    public static ApkInstallStatusMsg decodeApkInstallStatus(byte[] payload) throws ProtocolException { return OpalCastCompanionPayloads.decodeApkInstallStatus(payload); }
    public static byte[] encodeCallState(CallStateMsg msg) { return OpalCastCompanionPayloads.encodeCallState(msg); }
    public static CallStateMsg decodeCallState(byte[] payload) throws ProtocolException { return OpalCastCompanionPayloads.decodeCallState(payload); }
    public static byte[] encodeCallAction(CallActionMsg msg) { return OpalCastCompanionPayloads.encodeCallAction(msg); }
    public static CallActionMsg decodeCallAction(byte[] payload) throws ProtocolException { return OpalCastCompanionPayloads.decodeCallAction(payload); }
}
