package io.github.lengsukq.opaldesk.daemon.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Companion notification, APK install, and call continuity payload models and codecs
 * delegated by {@link OpalCastProtocol}.
 */
public final class OpalCastCompanionPayloads {
    private OpalCastCompanionPayloads() {}

    public static class NotificationActionItemBase {
        public final int index;
        public final String label;

        protected NotificationActionItemBase(int index, String label) {
            this.index = Math.max(0, index);
            this.label = (label != null) ? label : "";
        }
    }

    public static class NotificationItemBase {
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
        public final List<OpalCastProtocol.NotificationActionItem> actions;

        protected NotificationItemBase(
                String key, String pkg, String appLabel, String title, String text,
                long postTimeMs, boolean clearable, boolean canReply, String replyLabel,
                String otpCode, List<OpalCastProtocol.NotificationActionItem> actions) {
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

    public static class NotificationEventMsgBase {
        public final String action;
        public final String key;
        public final List<OpalCastProtocol.NotificationItem> items;

        protected NotificationEventMsgBase(
                String action, String key, List<OpalCastProtocol.NotificationItem> items) {
            this.action = (action != null) ? action.trim() : "";
            this.key = (key != null) ? key : "";
            this.items = (items != null)
                    ? Collections.unmodifiableList(new ArrayList<>(items))
                    : Collections.emptyList();
        }
    }

    public static byte[] encodeNotificationEvent(OpalCastProtocol.NotificationEventMsg msg) {
        OpalCastProtocol.NotificationEventMsg m = (msg != null)
                ? msg : new OpalCastProtocol.NotificationEventMsg("snapshot", "", Collections.emptyList());
        SimpleJson.ObjectBuilder root = new SimpleJson.ObjectBuilder().put("action", m.action);
        if ("removed".equals(m.action)) {
            root.put("key", m.key);
        } else {
            SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
            for (OpalCastProtocol.NotificationItem it : m.items) {
                if (it == null) continue;
                SimpleJson.ArrayBuilder actionsArr = new SimpleJson.ArrayBuilder();
                for (OpalCastProtocol.NotificationActionItem act : it.actions) {
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

    public static OpalCastProtocol.NotificationEventMsg decodeNotificationEvent(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("NOTIFICATION_EVENT payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String action = obj.getString("action", "");
            String key = obj.getString("key", "");
            List<SimpleJson.JsonObjectView> rawItems = obj.getObjectList("items");
            List<OpalCastProtocol.NotificationItem> items = new ArrayList<>(rawItems.size());
            for (SimpleJson.JsonObjectView it : rawItems) {
                List<SimpleJson.JsonObjectView> rawActions = it.getObjectList("actions");
                List<OpalCastProtocol.NotificationActionItem> actions = new ArrayList<>(rawActions.size());
                for (SimpleJson.JsonObjectView rawAct : rawActions) {
                    actions.add(new OpalCastProtocol.NotificationActionItem(
                            rawAct.getInt("index", 0),
                            rawAct.getString("label", "")));
                }
                items.add(new OpalCastProtocol.NotificationItem(
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
            return new OpalCastProtocol.NotificationEventMsg(action, key, items);
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid NOTIFICATION_EVENT JSON: " + t.getMessage(), t);
        }
    }

    public static class NotificationActionMsgBase {
        public final String op;
        public final String key;
        public final String replyText;
        public final int actionIndex;

        protected NotificationActionMsgBase(String op, String key, String replyText, int actionIndex) {
            this.op = (op != null) ? op.trim() : "";
            this.key = (key != null) ? key : "";
            this.replyText = (replyText != null) ? replyText : "";
            this.actionIndex = actionIndex;
        }
    }

    public static byte[] encodeNotificationAction(OpalCastProtocol.NotificationActionMsg msg) {
        OpalCastProtocol.NotificationActionMsg m = (msg != null)
                ? msg : new OpalCastProtocol.NotificationActionMsg("sync", "", "", -1);
        SimpleJson.ObjectBuilder b = new SimpleJson.ObjectBuilder()
                .put("op", m.op)
                .put("key", m.key)
                .put("replyText", m.replyText);
        if (m.actionIndex >= 0 || "invoke_action".equalsIgnoreCase(m.op)) {
            b.put("actionIndex", m.actionIndex);
        }
        return b.build().getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.NotificationActionMsg decodeNotificationAction(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("NOTIFICATION_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = obj.getString("op", "");
            if (op.trim().isEmpty()) {
                throw new OpalCastProtocol.ProtocolException("NOTIFICATION_ACTION missing op");
            }
            return new OpalCastProtocol.NotificationActionMsg(
                    op,
                    obj.getString("key", ""),
                    obj.getString("replyText", ""),
                    obj.getInt("actionIndex", -1));
        } catch (OpalCastProtocol.ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid NOTIFICATION_ACTION JSON: " + t.getMessage(), t);
        }
    }

    public static class ApkInstallRequestMsgBase {
        public final int installId;
        public final String filePath;
        public final String format;
        public final boolean replaceExisting;
        public final boolean allowDowngrade;

        protected ApkInstallRequestMsgBase(
                int installId, String filePath, String format,
                boolean replaceExisting, boolean allowDowngrade) {
            this.installId = installId;
            this.filePath = (filePath != null) ? filePath.trim() : "";
            this.format = (format != null) ? format.trim().toLowerCase(Locale.ROOT) : "";
            this.replaceExisting = replaceExisting;
            this.allowDowngrade = allowDowngrade;
        }
    }

    public static byte[] encodeApkInstallRequest(OpalCastProtocol.ApkInstallRequestMsg msg) {
        OpalCastProtocol.ApkInstallRequestMsg m = (msg != null)
                ? msg : new OpalCastProtocol.ApkInstallRequestMsg(0, "", "apk", true, false);
        return new SimpleJson.ObjectBuilder()
                .put("installId", m.installId)
                .put("filePath", m.filePath)
                .put("format", m.format)
                .put("replaceExisting", m.replaceExisting)
                .put("allowDowngrade", m.allowDowngrade)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.ApkInstallRequestMsg decodeApkInstallRequest(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("APK_INSTALL_REQUEST payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String filePath = obj.getString("filePath", "");
            if (filePath.trim().isEmpty()) {
                throw new OpalCastProtocol.ProtocolException("APK_INSTALL_REQUEST missing filePath");
            }
            return new OpalCastProtocol.ApkInstallRequestMsg(
                    obj.getInt("installId", 0),
                    filePath,
                    obj.getString("format", ""),
                    obj.getBoolean("replaceExisting", true),
                    obj.getBoolean("allowDowngrade", false));
        } catch (OpalCastProtocol.ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid APK_INSTALL_REQUEST JSON: " + t.getMessage(), t);
        }
    }

    public static class ApkInstallStatusMsgBase {
        public final int installId;
        public final String stage;
        public final int progress;
        public final String packageName;
        public final String message;

        protected ApkInstallStatusMsgBase(
                int installId, String stage, int progress, String packageName, String message) {
            this.installId = installId;
            this.stage = (stage != null) ? stage.trim() : "";
            this.progress = Math.max(0, Math.min(100, progress));
            this.packageName = (packageName != null) ? packageName : "";
            this.message = (message != null) ? message : "";
        }
    }

    public static byte[] encodeApkInstallStatus(OpalCastProtocol.ApkInstallStatusMsg msg) {
        OpalCastProtocol.ApkInstallStatusMsg m = (msg != null)
                ? msg : new OpalCastProtocol.ApkInstallStatusMsg(0, "failed", 0, "", "empty");
        return new SimpleJson.ObjectBuilder()
                .put("installId", m.installId)
                .put("stage", m.stage)
                .put("progress", m.progress)
                .put("packageName", m.packageName)
                .put("message", m.message)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.ApkInstallStatusMsg decodeApkInstallStatus(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("APK_INSTALL_STATUS payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new OpalCastProtocol.ApkInstallStatusMsg(
                    obj.getInt("installId", 0),
                    obj.getString("stage", ""),
                    obj.getInt("progress", 0),
                    obj.getString("packageName", ""),
                    obj.getString("message", ""));
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid APK_INSTALL_STATUS JSON: " + t.getMessage(), t);
        }
    }

    public static class CallStateMsgBase {
        public final String state;
        public final String number;
        public final String contactName;
        public final long startedAtMs;
        public final boolean isMuted;
        public final boolean isSpeakerOn;
        public final boolean canAnswer;
        public final boolean canReject;
        public final boolean canDial;
        public final String source;

        protected CallStateMsgBase(
                String state, String number, String contactName, long startedAtMs,
                boolean isMuted, boolean isSpeakerOn, boolean canAnswer,
                boolean canReject, boolean canDial, String source) {
            String cleanState = (state != null) ? state.trim().toLowerCase(Locale.ROOT) : "";
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

    public static byte[] encodeCallState(OpalCastProtocol.CallStateMsg msg) {
        OpalCastProtocol.CallStateMsg m = (msg != null)
                ? msg : new OpalCastProtocol.CallStateMsg("idle", "", "", 0L, false, false, false, false, false, "");
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

    public static OpalCastProtocol.CallStateMsg decodeCallState(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("CALL_STATE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new OpalCastProtocol.CallStateMsg(
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
            throw new OpalCastProtocol.ProtocolException("Invalid CALL_STATE JSON: " + t.getMessage(), t);
        }
    }

    public static class CallActionMsgBase {
        public final String op;
        public final String number;
        public final String smsText;

        protected CallActionMsgBase(String op, String number, String smsText) {
            this.op = (op != null) ? op.trim() : "";
            this.number = (number != null) ? number.trim() : "";
            this.smsText = (smsText != null) ? smsText : "";
        }
    }

    public static byte[] encodeCallAction(OpalCastProtocol.CallActionMsg msg) {
        OpalCastProtocol.CallActionMsg m = (msg != null)
                ? msg : new OpalCastProtocol.CallActionMsg("sync", "", "");
        return new SimpleJson.ObjectBuilder()
                .put("op", m.op)
                .put("number", m.number)
                .put("smsText", m.smsText)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.CallActionMsg decodeCallAction(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("CALL_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = obj.getString("op", "");
            if (op.trim().isEmpty()) {
                throw new OpalCastProtocol.ProtocolException("CALL_ACTION missing op");
            }
            return new OpalCastProtocol.CallActionMsg(
                    op,
                    obj.getString("number", ""),
                    obj.getString("smsText", ""));
        } catch (OpalCastProtocol.ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid CALL_ACTION JSON: " + t.getMessage(), t);
        }
    }
}
