package io.github.lengsukq.opaldesk.daemon.sink;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MediaCodec hardware encoder capability scanner, {@link MediaFormat} tier builder,
 * and Annex-B CSD / parameter-set helper for {@link RemoteStreamSink}.
 */
final class RemoteVideoEncoderConfig {
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";
    static final int DEFAULT_ALIGNMENT = 8;
    private static final int GOP_INTERVAL_SECONDS = 15;
    static final int INTRA_REFRESH_PERIOD_FRAMES = 30;

    enum ConfigTier {
        OPTIMAL,
        LOW_LATENCY_COMPAT,
        MINIMAL
    }

    static final class SelectedEncoder {
        final String codecName; // nullable -> fallback to MediaCodec.createEncoderByType
        final int alignW;
        final int alignH;
        final int maxW;
        final int maxH;
        final float maxFps;
        final int maxBitrateKbps; // 0 = unknown
        final int preferredProfile;
        final int preferredLevel;

        SelectedEncoder(
                String codecName, int alignW, int alignH, int maxW, int maxH, float maxFps,
                int maxBitrateKbps, int preferredProfile, int preferredLevel) {
            this.codecName = codecName;
            this.alignW = alignW;
            this.alignH = alignH;
            this.maxW = maxW;
            this.maxH = maxH;
            this.maxFps = maxFps;
            this.maxBitrateKbps = maxBitrateKbps;
            this.preferredProfile = preferredProfile;
            this.preferredLevel = preferredLevel;
        }
    }

    private RemoteVideoEncoderConfig() {}

    static SelectedEncoder selectPreferredEncoder(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaCodecInfo swFallback = null;
            String swMatchedType = null;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                String matchedType = matchSupportedType(info, mime);
                if (matchedType == null) continue;
                if (!isSoftwareCodec(info, info.getName())) {
                    return buildSelectedEncoder(info, matchedType);
                }
                if (swFallback == null) {
                    swFallback = info;
                    swMatchedType = matchedType;
                }
            }
            if (swFallback != null) {
                return buildSelectedEncoder(swFallback, swMatchedType);
            }
        } catch (Throwable t) {
            System.err.println("OPALDESK_ENCODER_SCAN_WARN mime=" + mime + ": " + t.getMessage());
        }
        return new SelectedEncoder(null, DEFAULT_ALIGNMENT, DEFAULT_ALIGNMENT, 0, 0, 0f, 0, 0, 0);
    }

    private static SelectedEncoder buildSelectedEncoder(MediaCodecInfo info, String matchedType) {
        int alignW = DEFAULT_ALIGNMENT;
        int alignH = DEFAULT_ALIGNMENT;
        int maxW = 0, maxH = 0, maxBitrateKbps = 0, preferredProfile = 0, preferredLevel = 0;
        float maxFps = 0f;
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(matchedType);
            MediaCodecInfo.VideoCapabilities vc = (caps != null) ? caps.getVideoCapabilities() : null;
            if (vc != null) {
                alignW = Math.max(DEFAULT_ALIGNMENT, vc.getWidthAlignment());
                alignH = Math.max(DEFAULT_ALIGNMENT, vc.getHeightAlignment());
                android.util.Range<Integer> wRange = vc.getSupportedWidths();
                android.util.Range<Integer> hRange = vc.getSupportedHeights();
                android.util.Range<Integer> fpsRange = vc.getSupportedFrameRates();
                android.util.Range<Integer> brRange = vc.getBitrateRange();
                if (wRange != null && wRange.getUpper() != null) maxW = wRange.getUpper();
                if (hRange != null && hRange.getUpper() != null) maxH = hRange.getUpper();
                if (fpsRange != null && fpsRange.getUpper() != null) maxFps = fpsRange.getUpper();
                if (brRange != null && brRange.getUpper() != null && brRange.getUpper() > 0) {
                    maxBitrateKbps = brRange.getUpper() / 1000;
                }
            }
            if (caps != null && caps.profileLevels != null && caps.profileLevels.length > 0) {
                int[] profiles = new int[caps.profileLevels.length];
                int[] levels = new int[caps.profileLevels.length];
                for (int i = 0; i < caps.profileLevels.length; i++) {
                    MediaCodecInfo.CodecProfileLevel pl = caps.profileLevels[i];
                    if (pl != null) {
                        profiles[i] = pl.profile;
                        levels[i] = pl.level;
                    }
                }
                int[] chosen = DisplayGeometryUtil.selectPreferredProfileLevel(matchedType, profiles, levels);
                preferredProfile = chosen[0];
                preferredLevel = chosen[1];
            }
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_ENCODER_CAPS_WARN codec=" + info.getName() + ": " + e.getMessage());
        }
        return new SelectedEncoder(
                info.getName(), alignW, alignH, maxW, maxH, maxFps, maxBitrateKbps, preferredProfile, preferredLevel);
    }

    private static String matchSupportedType(MediaCodecInfo info, String mime) {
        for (String type : info.getSupportedTypes()) {
            if (mime.equalsIgnoreCase(type)) return type;
        }
        return null;
    }

    private static boolean isSoftwareCodec(MediaCodecInfo info, String name) {
        if (Build.VERSION.SDK_INT >= 29) {
            try { return info.isSoftwareOnly(); } catch (NoSuchMethodError ignored) {}
        }
        String lower = (name != null) ? name.toLowerCase(Locale.US) : "";
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.");
    }

    static int[][] buildDimensionCandidates(
            int reqW, int reqH, int capAlignW, int capAlignH, int maxW, int maxH) {
        List<int[]> list = new ArrayList<>(4);
        addUniqueDims(list, DisplayGeometryUtil.fitDimensionsToMaxBounds(reqW, reqH, maxW, maxH, capAlignW, capAlignH));
        addUniqueDims(list, DisplayGeometryUtil.alignForEncoder(reqW, reqH, capAlignW, capAlignH));
        addUniqueDims(list, DisplayGeometryUtil.fitDimensionsToMaxBounds(reqW, reqH, maxW, maxH, 16, 16));
        addUniqueDims(list, DisplayGeometryUtil.fitDimensionsToMaxBounds(reqW, reqH, 1920, 1920, 16, 16));
        return list.toArray(new int[0][]);
    }

    private static void addUniqueDims(List<int[]> list, int[] dims) {
        for (int[] existing : list) {
            if (existing[0] == dims[0] && existing[1] == dims[1]) return;
        }
        list.add(dims);
    }

    static MediaFormat buildVideoFormat(
            String mime, String codecName, int width, int height, float fps, int bitrateKbps,
            int preferredProfile, int preferredLevel, long repeatAfterUs, ConfigTier tier) {
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        int targetFps = Math.max(15, Math.round(fps));
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_INTERVAL_SECONDS);
        if (repeatAfterUs > 0L) {
            fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatAfterUs);
        }
        if (tier == ConfigTier.OPTIMAL) {
            fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
            if (Build.VERSION.SDK_INT >= 24) {
                fmt.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, INTRA_REFRESH_PERIOD_FRAMES);
            }
            if (preferredProfile > 0 && preferredLevel > 0) {
                fmt.setInteger(MediaFormat.KEY_PROFILE, preferredProfile);
                fmt.setInteger(MediaFormat.KEY_LEVEL, preferredLevel);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                fmt.setFloat(KEY_MAX_FPS_TO_ENCODER, (float) targetFps);
            }
        } else if (tier == ConfigTier.LOW_LATENCY_COMPAT) {
            fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        if (tier != ConfigTier.MINIMAL) {
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps);
            if (Build.VERSION.SDK_INT >= 30) {
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                fmt.setInteger(MediaFormat.KEY_LATENCY, 1);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            }
            boolean useCbr = (tier == ConfigTier.LOW_LATENCY_COMPAT)
                    || DisplayGeometryUtil.shouldPreferCbrMode(codecName);
            fmt.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    useCbr
                            ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                            : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        return fmt;
    }

    static byte[] extractCsdAnnexB(MediaFormat fmt) {
        if (fmt == null) return OpalCastProtocol.EMPTY_BYTES;
        ByteBuffer csd0 = fmt.containsKey("csd-0") ? fmt.getByteBuffer("csd-0") : null;
        ByteBuffer csd1 = fmt.containsKey("csd-1") ? fmt.getByteBuffer("csd-1") : null;
        int len0 = (csd0 != null) ? csd0.remaining() : 0;
        int len1 = (csd1 != null) ? csd1.remaining() : 0;
        if (len0 + len1 <= 0) return OpalCastProtocol.EMPTY_BYTES;
        byte[] out = new byte[len0 + len1];
        if (len0 > 0) csd0.duplicate().get(out, 0, len0);
        if (len1 > 0) csd1.duplicate().get(out, len0, len1);
        return out;
    }

    static boolean hasInlineParameterSets(int codecId, byte[] annexB, int length) {
        if (annexB == null || length < 5 || annexB.length < 5) return false;
        int offset;
        if (annexB[0] == 0 && annexB[1] == 0 && annexB[2] == 0 && annexB[3] == 1) {
            offset = 4;
        } else if (annexB[0] == 0 && annexB[1] == 0 && annexB[2] == 1) {
            offset = 3;
        } else {
            return false;
        }
        int nalByte = annexB[offset] & 0xFF;
        if (codecId == OpalCastProtocol.VIDEO_CODEC_HEVC) {
            int nalType = (nalByte >> 1) & 0x3F;
            return nalType == 32 || nalType == 33 || nalType == 34; // VPS, SPS, PPS
        }
        int nalType = nalByte & 0x1F;
        return nalType == 7 || nalType == 8; // SPS, PPS
    }
}
