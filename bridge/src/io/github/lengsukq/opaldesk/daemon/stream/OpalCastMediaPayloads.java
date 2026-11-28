package io.github.lengsukq.opaldesk.daemon.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Video, audio, remote microphone, remote camera, and media playback payload models and codecs
 * delegated by {@link OpalCastProtocol}.
 */
public final class OpalCastMediaPayloads {
    private OpalCastMediaPayloads() {}

    public static class VideoConfigBase {
        public final int codec;
        public final int width;
        public final int height;
        public final int dpi;
        public final int rotation;
        public final int fpsX100;
        public final int displayMode;
        public final byte[] csdAnnexB;

        protected VideoConfigBase(
                int codec, int width, int height, int dpi,
                int rotation, int fpsX100, int displayMode, byte[] csdAnnexB) {
            this.codec = codec;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.rotation = rotation;
            this.fpsX100 = fpsX100;
            this.displayMode = displayMode;
            this.csdAnnexB = (csdAnnexB != null) ? csdAnnexB : OpalCastProtocol.EMPTY_BYTES;
        }
    }

    public static byte[] encodeVideoConfig(OpalCastProtocol.VideoConfig cfg) {
        byte[] csd = (cfg.csdAnnexB != null) ? cfg.csdAnnexB : OpalCastProtocol.EMPTY_BYTES;
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

    public static class AudioConfigBase {
        public final int codec;
        public final int sampleRate;
        public final int channels;
        public final byte[] audioSpecificConfig;

        protected AudioConfigBase(int codec, int sampleRate, int channels, byte[] audioSpecificConfig) {
            this.codec = codec;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.audioSpecificConfig = (audioSpecificConfig != null) ? audioSpecificConfig : OpalCastProtocol.EMPTY_BYTES;
        }
    }

    public static byte[] encodeAudioConfig(OpalCastProtocol.AudioConfig cfg) {
        byte[] asc = (cfg.audioSpecificConfig != null) ? cfg.audioSpecificConfig : OpalCastProtocol.EMPTY_BYTES;
        ByteBuffer bb = ByteBuffer.allocate(6 + asc.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (cfg.codec & 0xFF));
        bb.putInt(cfg.sampleRate);
        bb.put((byte) (cfg.channels & 0xFF));
        if (asc.length > 0) bb.put(asc);
        return bb.array();
    }

    public static class MicStateMsgBase {
        public final boolean active;
        public final boolean supported;
        public final int sampleRate;
        public final int channels;
        public final String message;

        protected MicStateMsgBase(boolean active, boolean supported, int sampleRate, int channels, String message) {
            this.active = active;
            this.supported = supported;
            this.sampleRate = Math.max(8_000, Math.min(48_000,
                    sampleRate > 0 ? sampleRate : OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE));
            this.channels = Math.max(1, Math.min(2,
                    channels > 0 ? channels : OpalCastProtocol.DEFAULT_MIC_CHANNELS));
            this.message = (message != null) ? message : "";
        }
    }

    public static byte[] encodeMicState(OpalCastProtocol.MicStateMsg msg) {
        OpalCastProtocol.MicStateMsg m = (msg != null)
                ? msg : new OpalCastProtocol.MicStateMsg(false, false,
                OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE, OpalCastProtocol.DEFAULT_MIC_CHANNELS, "");
        String json = new SimpleJson.ObjectBuilder()
                .put("active", m.active)
                .put("supported", m.supported)
                .put("sampleRate", m.sampleRate)
                .put("channels", m.channels)
                .put("message", m.message)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.MicStateMsg decodeMicState(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("MIC_STATE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new OpalCastProtocol.MicStateMsg(
                    obj.getBoolean("active", false),
                    obj.getBoolean("supported", false),
                    obj.getInt("sampleRate", OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE),
                    obj.getInt("channels", OpalCastProtocol.DEFAULT_MIC_CHANNELS),
                    obj.getString("message", ""));
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid MIC_STATE JSON: " + t.getMessage(), t);
        }
    }

    public static class MicConfigMsgBase {
        public final boolean enabled;
        public final String codec;
        public final int sampleRate;
        public final int channels;

        protected MicConfigMsgBase(boolean enabled, String codec, int sampleRate, int channels) {
            this.enabled = enabled;
            String c = (codec != null) ? codec.trim().toLowerCase(Locale.ROOT) : "";
            this.codec = c.isEmpty() ? OpalCastProtocol.MIC_CODEC_PCM16 : c;
            this.sampleRate = Math.max(8_000, Math.min(48_000,
                    sampleRate > 0 ? sampleRate : OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE));
            this.channels = Math.max(1, Math.min(2,
                    channels > 0 ? channels : OpalCastProtocol.DEFAULT_MIC_CHANNELS));
        }
    }

    public static byte[] encodeMicConfig(OpalCastProtocol.MicConfigMsg msg) {
        OpalCastProtocol.MicConfigMsg m = (msg != null)
                ? msg : new OpalCastProtocol.MicConfigMsg(false, OpalCastProtocol.MIC_CODEC_PCM16,
                OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE, OpalCastProtocol.DEFAULT_MIC_CHANNELS);
        String json = new SimpleJson.ObjectBuilder()
                .put("enabled", m.enabled)
                .put("codec", m.codec)
                .put("sampleRate", m.sampleRate)
                .put("channels", m.channels)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.MicConfigMsg decodeMicConfig(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("MIC_CONFIG payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new OpalCastProtocol.MicConfigMsg(
                    obj.getBoolean("enabled", false),
                    obj.getString("codec", OpalCastProtocol.MIC_CODEC_PCM16),
                    obj.getInt("sampleRate", OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE),
                    obj.getInt("channels", OpalCastProtocol.DEFAULT_MIC_CHANNELS));
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid MIC_CONFIG JSON: " + t.getMessage(), t);
        }
    }

    public static class CameraStateMsgBase {
        public final boolean active;
        public final boolean supported;
        public final boolean rootRequired;
        public final int width;
        public final int height;
        public final int fps;
        public final String backend;
        public final String message;
        public final String facing;

        protected CameraStateMsgBase(
                boolean active, boolean supported, boolean rootRequired,
                int width, int height, int fps, String backend, String message, String facing) {
            this.active = active;
            this.supported = supported;
            this.rootRequired = rootRequired;
            this.width = Math.max(160, Math.min(1920,
                    width > 0 ? width : OpalCastProtocol.DEFAULT_CAMERA_WIDTH));
            this.height = Math.max(120, Math.min(1080,
                    height > 0 ? height : OpalCastProtocol.DEFAULT_CAMERA_HEIGHT));
            this.fps = Math.max(5, Math.min(30,
                    fps > 0 ? fps : OpalCastProtocol.DEFAULT_CAMERA_FPS));
            this.backend = (backend != null) ? backend : "";
            this.message = (message != null) ? message : "";
            this.facing = (facing != null) ? facing : "";
        }
    }

    public static byte[] encodeCameraState(OpalCastProtocol.CameraStateMsg msg) {
        OpalCastProtocol.CameraStateMsg m = (msg != null)
                ? msg : new OpalCastProtocol.CameraStateMsg(false, false, true,
                OpalCastProtocol.DEFAULT_CAMERA_WIDTH, OpalCastProtocol.DEFAULT_CAMERA_HEIGHT,
                OpalCastProtocol.DEFAULT_CAMERA_FPS, "", "");
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

    public static OpalCastProtocol.CameraStateMsg decodeCameraState(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("CAMERA_STATE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new OpalCastProtocol.CameraStateMsg(
                    obj.getBoolean("active", false),
                    obj.getBoolean("supported", false),
                    obj.getBoolean("rootRequired", true),
                    obj.getInt("width", OpalCastProtocol.DEFAULT_CAMERA_WIDTH),
                    obj.getInt("height", OpalCastProtocol.DEFAULT_CAMERA_HEIGHT),
                    obj.getInt("fps", OpalCastProtocol.DEFAULT_CAMERA_FPS),
                    obj.getString("backend", ""),
                    obj.getString("message", ""),
                    obj.getString("facing", ""));
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid CAMERA_STATE JSON: " + t.getMessage(), t);
        }
    }

    public static class CameraConfigMsgBase {
        public final boolean enabled;
        public final String codec;
        public final int width;
        public final int height;
        public final int fps;
        public final String facing;

        protected CameraConfigMsgBase(boolean enabled, String codec, int width, int height, int fps, String facing) {
            this.enabled = enabled;
            String c = (codec != null) ? codec.trim().toLowerCase(Locale.ROOT) : "";
            this.codec = c.isEmpty() ? OpalCastProtocol.CAMERA_CODEC_MJPEG : c;
            this.width = Math.max(160, Math.min(1920,
                    width > 0 ? width : OpalCastProtocol.DEFAULT_CAMERA_WIDTH));
            this.height = Math.max(120, Math.min(1080,
                    height > 0 ? height : OpalCastProtocol.DEFAULT_CAMERA_HEIGHT));
            this.fps = Math.max(5, Math.min(30,
                    fps > 0 ? fps : OpalCastProtocol.DEFAULT_CAMERA_FPS));
            String f = (facing != null) ? facing.trim().toLowerCase(Locale.ROOT) : "";
            this.facing = "back".equals(f) ? "back" : "front";
        }
    }

    public static byte[] encodeCameraConfig(OpalCastProtocol.CameraConfigMsg msg) {
        OpalCastProtocol.CameraConfigMsg m = (msg != null)
                ? msg : new OpalCastProtocol.CameraConfigMsg(false, OpalCastProtocol.CAMERA_CODEC_MJPEG,
                OpalCastProtocol.DEFAULT_CAMERA_WIDTH, OpalCastProtocol.DEFAULT_CAMERA_HEIGHT,
                OpalCastProtocol.DEFAULT_CAMERA_FPS, "front");
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

    public static OpalCastProtocol.CameraConfigMsg decodeCameraConfig(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("CAMERA_CONFIG payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new OpalCastProtocol.CameraConfigMsg(
                    obj.getBoolean("enabled", false),
                    obj.getString("codec", OpalCastProtocol.CAMERA_CODEC_MJPEG),
                    obj.getInt("width", OpalCastProtocol.DEFAULT_CAMERA_WIDTH),
                    obj.getInt("height", OpalCastProtocol.DEFAULT_CAMERA_HEIGHT),
                    obj.getInt("fps", OpalCastProtocol.DEFAULT_CAMERA_FPS),
                    obj.getString("facing", "front"));
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid CAMERA_CONFIG JSON: " + t.getMessage(), t);
        }
    }

    public static class MediaStateMsgBase {
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

        protected MediaStateMsgBase(
                boolean active, String pkg, String appLabel, String title, String artist,
                String album, boolean playing, long positionMs, long durationMs, String artworkBase64) {
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

    public static byte[] encodeMediaState(OpalCastProtocol.MediaStateMsg msg) {
        OpalCastProtocol.MediaStateMsg m = (msg != null)
                ? msg : new OpalCastProtocol.MediaStateMsg(false, "", "", "", "", "", false, 0L, 0L, "");
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

    public static OpalCastProtocol.MediaStateMsg decodeMediaState(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("MEDIA_STATE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new OpalCastProtocol.MediaStateMsg(
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
            throw new OpalCastProtocol.ProtocolException("Invalid MEDIA_STATE JSON: " + t.getMessage(), t);
        }
    }

    public static class MediaActionMsgBase {
        public final String op;
        public final long positionMs;

        protected MediaActionMsgBase(String op, long positionMs) {
            this.op = (op != null) ? op.trim() : "";
            this.positionMs = Math.max(0L, positionMs);
        }
    }

    public static byte[] encodeMediaAction(OpalCastProtocol.MediaActionMsg msg) {
        OpalCastProtocol.MediaActionMsg m = (msg != null)
                ? msg : new OpalCastProtocol.MediaActionMsg("toggle", 0L);
        return new SimpleJson.ObjectBuilder()
                .put("op", m.op)
                .put("positionMs", m.positionMs)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.MediaActionMsg decodeMediaAction(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("MEDIA_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = obj.getString("op", "");
            if (op.trim().isEmpty()) {
                throw new OpalCastProtocol.ProtocolException("MEDIA_ACTION missing op");
            }
            return new OpalCastProtocol.MediaActionMsg(op, obj.getLong("positionMs", 0L));
        } catch (OpalCastProtocol.ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid MEDIA_ACTION JSON: " + t.getMessage(), t);
        }
    }
}
