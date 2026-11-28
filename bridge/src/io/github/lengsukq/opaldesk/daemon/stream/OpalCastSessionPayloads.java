package io.github.lengsukq.opaldesk.daemon.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Session lifecycle, device state, app catalog, Wi-Fi upgrade, and ping/pong payload models
 * and codecs delegated by {@link OpalCastProtocol}.
 */
public final class OpalCastSessionPayloads {
    private OpalCastSessionPayloads() {}

    public static class DeviceStateBase {
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
        public final int injectStatus;
        public final boolean adaptiveSingleEnabled;
        public final int defaultSingleWidthDp;
        public final String activeLayoutMode;
        public final String topAppPackage;
        public final String topAppLabel;
        public final List<String> tilingSlots;
        public final List<Integer> tilingRatios;
        public final String fullscreenWindowTitle;
        public final String hostTopAppPackage;
        public final String hostTopAppLabel;

        protected DeviceStateBase(
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

    public static byte[] encodeDeviceState(OpalCastProtocol.DeviceState s) {
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

    public static class AuthResultMsgBase {
        public final boolean ok;
        public final String authToken;
        public final String message;
        public final String deviceName;
        public final String certFingerprint;
        public final boolean permanent;

        protected AuthResultMsgBase(
                boolean ok, String authToken, String message,
                String deviceName, String certFingerprint, boolean permanent) {
            this.ok = ok;
            this.authToken = (authToken != null) ? authToken : "";
            this.message = (message != null) ? message : "";
            this.deviceName = (deviceName != null) ? deviceName : "";
            this.certFingerprint = (certFingerprint != null) ? certFingerprint : "";
            this.permanent = permanent;
        }
    }

    public static byte[] encodeAuthResult(OpalCastProtocol.AuthResultMsg msg) {
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

    public static class WifiUpgradeReadyMsgBase {
        public final boolean ok;
        public final String host;
        public final int port;
        public final String certFingerprint;
        public final String authToken;
        public final String message;

        protected WifiUpgradeReadyMsgBase(
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

    public static byte[] encodeWifiUpgradeReady(OpalCastProtocol.WifiUpgradeReadyMsg msg) {
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

    public static class AppEntryBase {
        public final String pkg;
        public final String cls;
        public final String label;
        public final boolean runningOnExt;
        public final int taskId;
        public final String iconPngBase64;
        public final int openCount;
        public final int userId;
        public final String profileType;

        protected AppEntryBase(
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

    public static class AppListMsgBase {
        public final List<OpalCastProtocol.AppEntry> apps;

        protected AppListMsgBase(List<OpalCastProtocol.AppEntry> apps) {
            this.apps = (apps != null)
                    ? Collections.unmodifiableList(new ArrayList<>(apps))
                    : Collections.emptyList();
        }
    }

    public static byte[] encodeAppList(OpalCastProtocol.AppListMsg msg) {
        SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
        if (msg != null && msg.apps != null) {
            for (OpalCastProtocol.AppEntry e : msg.apps) {
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

    public static byte[] encodePingPong(long pingId) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(pingId);
        return bb.array();
    }

    public static long decodePingPong(byte[] payload) throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 8) {
            throw new OpalCastProtocol.ProtocolException("PING/PONG payload must be 8 bytes");
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    public static class HelloMsgBase {
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
        public final String transportHint;
        public final String sessionRole;
        public final String appPackage;
        public final String appActivity;
        public final int appUserId;
        public final String clientPlatform;
        public final boolean companionEvents;

        protected HelloMsgBase(
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
            this.sessionRole = role.isEmpty() ? OpalCastProtocol.SESSION_ROLE_DESKTOP : role;
            this.appPackage = (appPackage != null) ? appPackage.trim() : "";
            this.appActivity = (appActivity != null) ? appActivity.trim() : "";
            this.appUserId = Math.max(0, appUserId);
            this.clientPlatform = (clientPlatform != null) ? clientPlatform.trim().toLowerCase(Locale.ROOT) : "";
            this.companionEvents = companionEvents;
        }

        public boolean isStandaloneApp() {
            return OpalCastProtocol.SESSION_ROLE_STANDALONE_APP.equalsIgnoreCase(sessionRole);
        }

        public boolean isAppCatalog() {
            return OpalCastProtocol.SESSION_ROLE_APP_CATALOG.equalsIgnoreCase(sessionRole);
        }

        public boolean isCompanion() {
            return OpalCastProtocol.SESSION_ROLE_COMPANION.equalsIgnoreCase(sessionRole);
        }
    }

    public static OpalCastProtocol.HelloMsg decodeHello(byte[] payload) throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("HELLO payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new OpalCastProtocol.HelloMsg(
                    obj.getInt("protocolVersion", 0),
                    obj.getString("clientName", "Mac"),
                    obj.getString("clientId", ""),
                    obj.getString("preferredCodec", "hevc"),
                    obj.getInt("width", 1920),
                    obj.getInt("height", 1080),
                    obj.getInt("dpi", 240),
                    obj.getInt("fps", 60),
                    obj.getInt("bitrateKbps", 12000),
                    obj.getInt("displayMode", OpalCastProtocol.DISPLAY_MODE_DEX),
                    obj.getBoolean("audioEnabled", true),
                    obj.getString("authToken", ""),
                    obj.getString("pinCode", ""),
                    obj.getString("transportHint", ""),
                    obj.getString("sessionRole", OpalCastProtocol.SESSION_ROLE_DESKTOP),
                    obj.getString("appPackage", ""),
                    obj.getString("appActivity", ""),
                    obj.getString("clientPlatform", ""),
                    obj.getBoolean("companionEvents", false),
                    obj.getInt("appUserId", 0));
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid HELLO JSON: " + t.getMessage(), t);
        }
    }

    public static class ResizeConfigBase {
        public final int width;
        public final int height;
        public final int dpi;
        public final int fpsX100;
        public final int bitrateKbps;
        public final int displayMode;
        public final int rotation;

        protected ResizeConfigBase(
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

    public static OpalCastProtocol.ResizeConfig decodeResizeConfig(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 14) {
            throw new OpalCastProtocol.ProtocolException("RESIZE_CONFIG payload must be 14 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int w = bb.getShort() & 0xFFFF;
        int h = bb.getShort() & 0xFFFF;
        int dpi = bb.getShort() & 0xFFFF;
        int fpsX100 = bb.getShort() & 0xFFFF;
        int bitrateKbps = bb.getInt();
        int displayMode = bb.get() & 0xFF;
        int rotation = bb.get() & 0xFF;
        return new OpalCastProtocol.ResizeConfig(w, h, dpi, fpsX100, bitrateKbps, displayMode, rotation);
    }
}
