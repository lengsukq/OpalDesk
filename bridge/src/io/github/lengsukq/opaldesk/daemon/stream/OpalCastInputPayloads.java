package io.github.lengsukq.opaldesk.daemon.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Input, clipboard, window-action, and system-action payload models and codecs
 * delegated by {@link OpalCastProtocol}.
 */
public final class OpalCastInputPayloads {
    private OpalCastInputPayloads() {}

    public static byte[] encodeClipboardPush(long seq, String text) {
        byte[] utf8 = (text != null ? text : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(8 + utf8.length).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(seq);
        bb.put(utf8);
        return bb.array();
    }

    public static byte[] encodeClipboardImagePush(long seq, byte[] pngData)
            throws OpalCastProtocol.ProtocolException {
        byte[] data = (pngData != null) ? pngData : OpalCastProtocol.EMPTY_BYTES;
        if (data.length > OpalCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new OpalCastProtocol.ProtocolException("Clipboard PNG exceeds 8 MiB limit: " + data.length);
        }
        ByteBuffer bb = ByteBuffer.allocate(8 + data.length).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(seq);
        if (data.length > 0) bb.put(data);
        return bb.array();
    }

    public static class MouseEventMsgBase {
        public final int action;
        public final float x;
        public final float y;
        public final int buttons;
        public final int actionButton;
        public final float hScroll;
        public final float vScroll;

        protected MouseEventMsgBase(
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

    public static OpalCastProtocol.MouseEventMsg decodeMouseEvent(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 25) {
            throw new OpalCastProtocol.ProtocolException("INPUT_MOUSE payload must be 25 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        float x = bb.getFloat();
        float y = bb.getFloat();
        int buttons = bb.getInt();
        int actionButton = bb.getInt();
        float hScroll = bb.getFloat();
        float vScroll = bb.getFloat();
        return new OpalCastProtocol.MouseEventMsg(action, x, y, buttons, actionButton, hScroll, vScroll);
    }

    public static class TouchPointerBase {
        public final int pointerId;
        public final float x;
        public final float y;
        public final float pressure;

        protected TouchPointerBase(int pointerId, float x, float y, float pressure) {
            this.pointerId = pointerId;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
        }
    }

    public static class TouchEventMsgBase {
        public final int action;
        public final int actionIndex;
        public final OpalCastProtocol.TouchPointer[] pointers;

        protected TouchEventMsgBase(int action, int actionIndex, OpalCastProtocol.TouchPointer[] pointers) {
            this.action = action;
            this.actionIndex = actionIndex;
            this.pointers = (pointers != null) ? pointers : new OpalCastProtocol.TouchPointer[0];
        }
    }

    public static OpalCastProtocol.TouchEventMsg decodeTouchEvent(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 3) {
            throw new OpalCastProtocol.ProtocolException("INPUT_TOUCH payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int actionIndex = bb.get() & 0xFF;
        int count = bb.get() & 0xFF;
        if (count > OpalCastProtocol.MAX_TOUCH_POINTERS) {
            throw new OpalCastProtocol.ProtocolException("Excessive touch pointerCount: " + count);
        }
        if (payload.length < 3 + count * 16) {
            throw new OpalCastProtocol.ProtocolException("INPUT_TOUCH payload truncated for pointerCount=" + count);
        }
        OpalCastProtocol.TouchPointer[] pts = new OpalCastProtocol.TouchPointer[count];
        for (int i = 0; i < count; i++) {
            int pid = bb.getInt();
            float x = bb.getFloat();
            float y = bb.getFloat();
            float pressure = bb.getFloat();
            pts[i] = new OpalCastProtocol.TouchPointer(pid, x, y, pressure);
        }
        return new OpalCastProtocol.TouchEventMsg(action, actionIndex, pts);
    }

    public static class KeyEventMsgBase {
        public final int action;
        public final int androidKeyCode;
        public final int metaState;
        public final int repeatCount;

        protected KeyEventMsgBase(int action, int androidKeyCode, int metaState, int repeatCount) {
            this.action = action;
            this.androidKeyCode = androidKeyCode;
            this.metaState = metaState;
            this.repeatCount = repeatCount;
        }
    }

    public static OpalCastProtocol.KeyEventMsg decodeKeyEvent(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 13) {
            throw new OpalCastProtocol.ProtocolException("INPUT_KEY payload must be 13 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int keyCode = bb.getInt();
        int meta = bb.getInt();
        int repeat = bb.getInt();
        return new OpalCastProtocol.KeyEventMsg(action, keyCode, meta, repeat);
    }

    public static class TextEventMsgBase {
        public final int mode;
        public final long seq;
        public final String text;

        protected TextEventMsgBase(int mode, long seq, String text) {
            this.mode = mode;
            this.seq = seq;
            this.text = (text != null) ? text : "";
        }
    }

    public static OpalCastProtocol.TextEventMsg decodeTextEvent(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 9) {
            throw new OpalCastProtocol.ProtocolException("INPUT_TEXT payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int mode = bb.get() & 0xFF;
        long seq = bb.getLong();
        String text = new String(payload, 9, payload.length - 9, StandardCharsets.UTF_8);
        return new OpalCastProtocol.TextEventMsg(mode, seq, text);
    }

    public static class ImageClipboardEventMsgBase {
        public final int mode;
        public final long seq;
        public final byte[] pngData;

        protected ImageClipboardEventMsgBase(int mode, long seq, byte[] pngData) {
            this.mode = mode;
            this.seq = seq;
            this.pngData = (pngData != null) ? pngData : OpalCastProtocol.EMPTY_BYTES;
        }
    }

    public static OpalCastProtocol.ImageClipboardEventMsg decodeImageClipboardEvent(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 9) {
            throw new OpalCastProtocol.ProtocolException("INPUT_TEXT image payload too short");
        }
        int imgLen = payload.length - 9;
        if (imgLen > OpalCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new OpalCastProtocol.ProtocolException("Image clipboard payload exceeds 8 MiB limit: " + imgLen);
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int mode = bb.get() & 0xFF;
        long seq = bb.getLong();
        byte[] data = new byte[imgLen];
        if (imgLen > 0) {
            bb.get(data);
        }
        return new OpalCastProtocol.ImageClipboardEventMsg(mode, seq, data);
    }

    public static class WindowActionMsgBase {
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

        protected WindowActionMsgBase(
                String action, boolean hasEnabled, boolean enabled, int defaultWidthDp,
                String pkg, String cls, String mode, int widthPct, boolean remember,
                int slot, int taskId, int left, int top, int right, int bottom,
                boolean hasBounds, String topology, List<String> pkgs, List<Integer> ratios,
                String backend, int userId) {
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

    public static OpalCastProtocol.WindowActionMsg decodeWindowAction(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("WINDOW_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String action = obj.getString("action", "");
            if (action.trim().isEmpty()) {
                throw new OpalCastProtocol.ProtocolException("WINDOW_ACTION missing action");
            }
            boolean hasBounds = obj.has("left") && obj.has("top") && obj.has("right") && obj.has("bottom");
            return new OpalCastProtocol.WindowActionMsg(
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
        } catch (OpalCastProtocol.ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid WINDOW_ACTION JSON: " + t.getMessage(), t);
        }
    }

    public static class SystemActionMsgBase {
        public final int action;
        public final int arg;

        protected SystemActionMsgBase(int action, int arg) {
            this.action = action;
            this.arg = arg;
        }
    }

    public static OpalCastProtocol.SystemActionMsg decodeSystemAction(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 5) {
            throw new OpalCastProtocol.ProtocolException("SYSTEM_ACTION payload must be 5 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int arg = bb.getInt();
        return new OpalCastProtocol.SystemActionMsg(action, arg);
    }
}
