package io.github.lengsukq.opaldesk.daemon.stream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure-Java Linux 64-bit /dev/uhid binary struct encoder, USB HID report descriptors,
 * HID input report builders, and Android KeyEvent.KEYCODE_* -> USB HID Usage ID mapping.
 * Has zero android.* runtime dependencies so it executes identically on Android ART and JVM tests.
 *
 * Exact Linux 64-bit struct uhid_event layout (include/uapi/linux/uhid.h):
 *   sizeof(struct uhid_event) == 4380 bytes (little-endian):
 *   - offset 0..3:       __u32 type (UHID_DESTROY=1, UHID_GET_REPORT=9, UHID_GET_REPORT_REPLY=10,
 *                        UHID_CREATE2=11, UHID_INPUT2=12, UHID_SET_REPORT=13, UHID_SET_REPORT_REPLY=14)
 *   - offset 4..4375:    union u (largest packed member uhid_create2_req = 4372 bytes)
 *   - offset 4376..4379: 4-byte trailing union alignment padding (from uhid_start_req __u64)
 */
public final class UhidProtocol {
    public static final int UHID_EVENT_SIZE = 4380;
    public static final int HID_MAX_DESCRIPTOR_SIZE = 4096;
    public static final int UHID_DESTROY = 1;
    public static final int UHID_START = 2;
    public static final int UHID_STOP = 3;
    public static final int UHID_OPEN = 4;
    public static final int UHID_CLOSE = 5;
    public static final int UHID_OUTPUT = 6;
    public static final int UHID_GET_REPORT = 9;
    public static final int UHID_GET_REPORT_REPLY = 10;
    public static final int UHID_CREATE2 = 11;
    public static final int UHID_INPUT2 = 12;
    public static final int UHID_SET_REPORT = 13;
    public static final int UHID_SET_REPORT_REPLY = 14;

    public static final int UHID_FEATURE_REPORT = 0;
    public static final int UHID_OUTPUT_REPORT = 1;
    public static final int UHID_INPUT_REPORT = 2;
    public static final int BUS_VIRTUAL = 0x06;

    // Exact byte offsets inside 64-bit little-endian struct uhid_event
    public static final int OFFSET_TYPE = 0;
    public static final int OFFSET_CREATE2_NAME = 4;       // 128 bytes
    public static final int OFFSET_CREATE2_PHYS = 132;     // 64 bytes
    public static final int OFFSET_CREATE2_UNIQ = 196;     // 64 bytes
    public static final int OFFSET_CREATE2_RD_SIZE = 260;  // uint16 LE
    public static final int OFFSET_CREATE2_BUS = 262;      // uint16 LE
    public static final int OFFSET_CREATE2_VENDOR = 264;   // uint32 LE
    public static final int OFFSET_CREATE2_PRODUCT = 268;  // uint32 LE
    public static final int OFFSET_CREATE2_VERSION = 272;  // uint32 LE
    public static final int OFFSET_CREATE2_COUNTRY = 276;  // uint32 LE
    public static final int OFFSET_CREATE2_RD_DATA = 280;  // 4096 bytes

    public static final int OFFSET_INPUT2_SIZE = 4;        // uint16 LE
    public static final int OFFSET_INPUT2_DATA = 6;        // up to 4096 bytes

    public static final int OFFSET_GET_REPORT_ID = 4;      // uint32 LE
    public static final int OFFSET_GET_REPORT_RNUM = 8;    // uint8
    public static final int OFFSET_GET_REPORT_RTYPE = 9;   // uint8

    public static final int OFFSET_GET_REPORT_REPLY_ID = 4;    // uint32 LE
    public static final int OFFSET_GET_REPORT_REPLY_ERR = 8;   // uint16 LE
    public static final int OFFSET_GET_REPORT_REPLY_SIZE = 10; // uint16 LE
    public static final int OFFSET_GET_REPORT_REPLY_DATA = 12; // up to 4096 bytes

    public static final int OFFSET_SET_REPORT_ID = 4;          // uint32 LE
    public static final int OFFSET_SET_REPORT_REPLY_ID = 4;    // uint32 LE
    public static final int OFFSET_SET_REPORT_REPLY_ERR = 8;   // uint16 LE

    public static final int REPORT_ID_POINTER = 1;
    public static final int REPORT_ID_KEYBOARD = 2;
    public static final int REPORT_ID_TOUCH = 1;
    public static final int REPORT_ID_TOUCH_FEATURE = 3;
    public static final int POINTER_REPORT_SIZE = 8;
    public static final int KEYBOARD_REPORT_SIZE = 9;
    public static final int MAX_TOUCH_CONTACTS = 10;
    public static final int TOUCH_CONTACT_BYTES = 6;
    public static final int TOUCH_REPORT_SIZE = 1 + (MAX_TOUCH_CONTACTS * TOUCH_CONTACT_BYTES) + 1;
    /** Reserved HID contact identifier for cursorless touch-emulated wheel scrolling. */
    public static final int SCROLL_TOUCH_CONTACT_ID = 250;
    public static final int ABS_MAX = 32767;
    public static final int REL_MAX = 32767;

    public static final int VENDOR_ID = 0x18D1;
    public static final int PRODUCT_ID_POINTER = 0xEB01;
    public static final int PRODUCT_ID_KEYBOARD = 0xEB02;
    public static final int PRODUCT_ID_TOUCH = 0xEB03;
    public static final int DEVICE_VERSION = 0x0001;

    // USB HID Modifier bits (Report ID 2, byte 1)
    public static final int MOD_LEFT_CTRL = 0x01;
    public static final int MOD_LEFT_SHIFT = 0x02;
    public static final int MOD_LEFT_ALT = 0x04;
    public static final int MOD_LEFT_GUI = 0x08;
    public static final int MOD_RIGHT_CTRL = 0x10;
    public static final int MOD_RIGHT_SHIFT = 0x20;
    public static final int MOD_RIGHT_ALT = 0x40;
    public static final int MOD_RIGHT_GUI = 0x80;

    // Android KeyEvent numeric constants (matches android.view.KeyEvent 1:1 without runtime stub dependency)
    private static final int KC_BACK = 4;
    private static final int KC_0 = 7;
    private static final int KC_1 = 8;
    private static final int KC_DPAD_UP = 19;
    private static final int KC_DPAD_DOWN = 20;
    private static final int KC_DPAD_LEFT = 21;
    private static final int KC_DPAD_RIGHT = 22;
    private static final int KC_VOLUME_UP = 24;
    private static final int KC_VOLUME_DOWN = 25;
    private static final int KC_A = 29;
    private static final int KC_COMMA = 55;
    private static final int KC_PERIOD = 56;
    private static final int KC_ALT_LEFT = 57;
    private static final int KC_ALT_RIGHT = 58;
    private static final int KC_SHIFT_LEFT = 59;
    private static final int KC_SHIFT_RIGHT = 60;
    private static final int KC_TAB = 61;
    private static final int KC_SPACE = 62;
    private static final int KC_ENTER = 66;
    private static final int KC_DEL = 67;
    private static final int KC_GRAVE = 68;
    private static final int KC_MINUS = 69;
    private static final int KC_EQUALS = 70;
    private static final int KC_LEFT_BRACKET = 71;
    private static final int KC_RIGHT_BRACKET = 72;
    private static final int KC_BACKSLASH = 73;
    private static final int KC_SEMICOLON = 74;
    private static final int KC_APOSTROPHE = 75;
    private static final int KC_SLASH = 76;
    private static final int KC_MENU = 82;
    private static final int KC_PAGE_UP = 92;
    private static final int KC_PAGE_DOWN = 93;
    private static final int KC_ESCAPE = 111;
    private static final int KC_FORWARD_DEL = 112;
    private static final int KC_CTRL_LEFT = 113;
    private static final int KC_CTRL_RIGHT = 114;
    private static final int KC_CAPS_LOCK = 115;
    private static final int KC_SCROLL_LOCK = 116;
    private static final int KC_META_LEFT = 117;
    private static final int KC_META_RIGHT = 118;
    private static final int KC_SYSRQ = 120;
    private static final int KC_BREAK = 121;
    private static final int KC_MOVE_HOME = 122;
    private static final int KC_MOVE_END = 123;
    private static final int KC_INSERT = 124;
    private static final int KC_F1 = 131;
    private static final int KC_NUM_LOCK = 143;
    private static final int KC_NUMPAD_0 = 144;
    private static final int KC_NUMPAD_1 = 145;
    private static final int KC_NUMPAD_DIVIDE = 154;
    private static final int KC_NUMPAD_MULTIPLY = 155;
    private static final int KC_NUMPAD_SUBTRACT = 156;
    private static final int KC_NUMPAD_ADD = 157;
    private static final int KC_NUMPAD_DOT = 158;
    private static final int KC_NUMPAD_COMMA = 159;
    private static final int KC_NUMPAD_ENTER = 160;
    private static final int KC_NUMPAD_EQUALS = 161;
    private static final int KC_VOLUME_MUTE = 164;
    private static final int KC_CUT = 277;
    private static final int KC_COPY = 278;
    private static final int KC_PASTE = 279;

    private static final int META_SHIFT_ON = 1;
    private static final int META_ALT_ON = 2;
    private static final int META_ALT_LEFT_ON = 16;
    private static final int META_ALT_RIGHT_ON = 32;
    private static final int META_SHIFT_LEFT_ON = 64;
    private static final int META_SHIFT_RIGHT_ON = 128;
    private static final int META_CTRL_ON = 4096;
    private static final int META_CTRL_LEFT_ON = 8192;
    private static final int META_CTRL_RIGHT_ON = 16384;
    private static final int META_META_ON = 65536;
    private static final int META_META_LEFT_ON = 131072;
    private static final int META_META_RIGHT_ON = 262144;

    /**
     * Standard Relative HID Mouse report descriptor (`BTN_LEFT..BTN_EXTRA`, `REL_X`, `REL_Y`,
     * `REL_WHEEL`, `REL_HWHEEL`) so Android `EventHub` classifies it as {@code InputDeviceClass::CURSOR}.
     * Touch & primary absolute clicks use {@link #TOUCH_REPORT_DESCRIPTOR} instead.
     */
    private static final byte[] POINTER_REPORT_DESCRIPTOR = new byte[]{
            0x05, 0x01,               // Usage Page (Generic Desktop)
            0x09, 0x02,               // Usage (Mouse)
            (byte) 0xA1, 0x01,        // Collection (Application)
            (byte) 0x85, REPORT_ID_POINTER, // Report ID (1)
            0x09, 0x01,               //   Usage (Pointer)
            (byte) 0xA1, 0x00,        //   Collection (Physical)
            // 5 buttons
            0x05, 0x09,               //     Usage Page (Button)
            0x19, 0x01,               //     Usage Minimum (Button 1)
            0x29, 0x05,               //     Usage Maximum (Button 5)
            0x15, 0x00,               //     Logical Minimum (0)
            0x25, 0x01,               //     Logical Maximum (1)
            (byte) 0x95, 0x05,        //     Report Count (5)
            0x75, 0x01,               //     Report Size (1)
            (byte) 0x81, 0x02,        //     Input (Data, Variable, Absolute)
            // 3 bits padding
            (byte) 0x95, 0x01,        //     Report Count (1)
            0x75, 0x03,               //     Report Size (3)
            (byte) 0x81, 0x03,        //     Input (Constant, Variable, Absolute)
            // Relative X, Y (-32767..32767)
            0x05, 0x01,               //     Usage Page (Generic Desktop)
            0x09, 0x30,               //     Usage (X)
            0x09, 0x31,               //     Usage (Y)
            0x16, 0x01, (byte) 0x80,  //     Logical Minimum (-32767)
            0x26, (byte) 0xFF, 0x7F,  //     Logical Maximum (32767)
            0x75, 0x10,               //     Report Size (16)
            (byte) 0x95, 0x02,        //     Report Count (2)
            (byte) 0x81, 0x06,        //     Input (Data, Variable, Relative)
            // Vertical wheel (-127..127)
            0x09, 0x38,               //     Usage (Wheel)
            0x15, (byte) 0x81,        //     Logical Minimum (-127)
            0x25, 0x7F,               //     Logical Maximum (127)
            0x75, 0x08,               //     Report Size (8)
            (byte) 0x95, 0x01,        //     Report Count (1)
            (byte) 0x81, 0x06,        //     Input (Data, Variable, Relative)
            // Horizontal AC Pan (-127..127)
            0x05, 0x0C,               //     Usage Page (Consumer)
            0x0A, 0x38, 0x02,         //     Usage (AC Pan = 0x0238)
            0x15, (byte) 0x81,        //     Logical Minimum (-127)
            0x25, 0x7F,               //     Logical Maximum (127)
            0x75, 0x08,               //     Report Size (8)
            (byte) 0x95, 0x01,        //     Report Count (1)
            (byte) 0x81, 0x06,        //     Input (Data, Variable, Relative)
            (byte) 0xC0,              //   End Collection (Physical)
            (byte) 0xC0               // End Collection (Application)
    };

    private static final byte[] TOUCH_REPORT_DESCRIPTOR = buildTouchReportDescriptor();

    private static byte[] buildTouchReportDescriptor() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(420);
        // Usage Page (Digitizers), Usage (Touch Screen), Collection (Application), Report ID (1)
        writeBytes(out,
                0x05, 0x0D,
                0x09, 0x04,
                0xA1, 0x01,
                0x85, REPORT_ID_TOUCH);
        for (int i = 0; i < MAX_TOUCH_CONTACTS; i++) {
            writeBytes(out,
                    0x05, 0x0D,               //   Usage Page (Digitizers)
                    0x09, 0x22,               //   Usage (Finger)
                    0xA1, 0x02,               //   Collection (Logical)
                    0x09, 0x42,               //     Usage (Tip Switch)
                    0x15, 0x00,               //     Logical Minimum (0)
                    0x25, 0x01,               //     Logical Maximum (1)
                    0x75, 0x01,               //     Report Size (1)
                    0x95, 0x01,               //     Report Count (1)
                    0x81, 0x02,               //     Input (Data, Variable, Absolute)
                    0x95, 0x07,               //     Report Count (7) padding
                    0x81, 0x03,               //     Input (Constant, Variable, Absolute)
                    0x09, 0x51,               //     Usage (Contact Identifier)
                    0x15, 0x00,               //     Logical Minimum (0)
                    0x26, 0xFF, 0x00,         //     Logical Maximum (255)
                    0x75, 0x08,               //     Report Size (8)
                    0x95, 0x01,               //     Report Count (1)
                    0x81, 0x02,               //     Input (Data, Variable, Absolute)
                    0x05, 0x01,               //     Usage Page (Generic Desktop)
                    0x09, 0x30,               //     Usage (X)
                    0x09, 0x31,               //     Usage (Y)
                    0x15, 0x00,               //     Logical Minimum (0)
                    0x26, 0xFF, 0x7F,         //     Logical Maximum (32767)
                    0x75, 0x10,               //     Report Size (16)
                    0x95, 0x02,               //     Report Count (2)
                    0x81, 0x02,               //     Input (Data, Variable, Absolute)
                    0xC0);                    //   End Collection (Logical)
        }
        writeBytes(out,
                0x05, 0x0D,                   //   Usage Page (Digitizers)
                0x09, 0x54,                   //   Usage (Contact Count)
                0x15, 0x00,                   //   Logical Minimum (0)
                0x25, MAX_TOUCH_CONTACTS,     //   Logical Maximum (10)
                0x75, 0x08,                   //   Report Size (8)
                0x95, 0x01,                   //   Report Count (1)
                0x81, 0x02,                   //   Input (Data, Variable, Absolute)
                0x85, REPORT_ID_TOUCH_FEATURE,//   Report ID (3)
                0x09, 0x55,                   //   Usage (Contact Count Maximum)
                0x15, 0x00,                   //   Logical Minimum (0)
                0x25, MAX_TOUCH_CONTACTS,     //   Logical Maximum (10)
                0x75, 0x08,                   //   Report Size (8)
                0x95, 0x01,                   //   Report Count (1)
                0xB1, 0x02,                   //   Feature (Data, Variable, Absolute)
                0xC0);                        // End Collection (Application)
        return out.toByteArray();
    }

    private static void writeBytes(ByteArrayOutputStream out, int... bytes) {
        for (int b : bytes) {
            out.write(b & 0xFF);
        }
    }

    private static final byte[] KEYBOARD_REPORT_DESCRIPTOR = new byte[]{
            0x05, 0x01,               // Usage Page (Generic Desktop)
            0x09, 0x06,               // Usage (Keyboard)
            (byte) 0xA1, 0x01,        // Collection (Application)
            (byte) 0x85, REPORT_ID_KEYBOARD, // Report ID (2)
            // 8 modifier bits
            0x05, 0x07,               //   Usage Page (Keyboard/Keypad)
            0x19, (byte) 0xE0,        //   Usage Minimum (Left Control)
            0x29, (byte) 0xE7,        //   Usage Maximum (Right GUI)
            0x15, 0x00,               //   Logical Minimum (0)
            0x25, 0x01,               //   Logical Maximum (1)
            0x75, 0x01,               //   Report Size (1)
            (byte) 0x95, 0x08,        //   Report Count (8)
            (byte) 0x81, 0x02,        //   Input (Data, Variable, Absolute)
            // 1 reserved byte
            (byte) 0x95, 0x01,        //   Report Count (1)
            0x75, 0x08,               //   Report Size (8)
            (byte) 0x81, 0x01,        //   Input (Constant)
            // 6 keycode array
            (byte) 0x95, 0x06,        //   Report Count (6)
            0x75, 0x08,               //   Report Size (8)
            0x15, 0x00,               //   Logical Minimum (0)
            0x26, (byte) 0xFF, 0x00,  //   Logical Maximum (255)
            0x05, 0x07,               //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,               //   Usage Minimum (0)
            0x2A, (byte) 0xFF, 0x00,  //   Usage Maximum (255)
            (byte) 0x81, 0x00,        //   Input (Data, Array)
            (byte) 0xC0               // End Collection (Application)
    };

    private static final Map<Integer, Integer> KEYCODE_TO_HID = buildKeycodeMap();

    private UhidProtocol() {}

    private static Map<Integer, Integer> buildKeycodeMap() {
        Map<Integer, Integer> m = new HashMap<>(128);
        for (int i = 0; i < 26; i++) m.put(KC_A + i, 0x04 + i);
        for (int i = 0; i < 9; i++) m.put(KC_1 + i, 0x1E + i);
        m.put(KC_0, 0x27);
        m.put(KC_ENTER, 0x28);
        m.put(KC_ESCAPE, 0x29);
        m.put(KC_BACK, 0x29);
        m.put(KC_DEL, 0x2A);
        m.put(KC_TAB, 0x2B);
        m.put(KC_SPACE, 0x2C);
        m.put(KC_MINUS, 0x2D);
        m.put(KC_EQUALS, 0x2E);
        m.put(KC_LEFT_BRACKET, 0x2F);
        m.put(KC_RIGHT_BRACKET, 0x30);
        m.put(KC_BACKSLASH, 0x31);
        m.put(KC_SEMICOLON, 0x33);
        m.put(KC_APOSTROPHE, 0x34);
        m.put(KC_GRAVE, 0x35);
        m.put(KC_COMMA, 0x36);
        m.put(KC_PERIOD, 0x37);
        m.put(KC_SLASH, 0x38);
        m.put(KC_CAPS_LOCK, 0x39);
        for (int i = 0; i < 12; i++) m.put(KC_F1 + i, 0x3A + i);
        m.put(KC_SYSRQ, 0x46);
        m.put(KC_SCROLL_LOCK, 0x47);
        m.put(KC_BREAK, 0x48);
        m.put(KC_INSERT, 0x49);
        m.put(KC_MOVE_HOME, 0x4A);
        m.put(KC_PAGE_UP, 0x4B);
        m.put(KC_FORWARD_DEL, 0x4C);
        m.put(KC_MOVE_END, 0x4D);
        m.put(KC_PAGE_DOWN, 0x4E);
        m.put(KC_DPAD_RIGHT, 0x4F);
        m.put(KC_DPAD_LEFT, 0x50);
        m.put(KC_DPAD_DOWN, 0x51);
        m.put(KC_DPAD_UP, 0x52);
        m.put(KC_NUM_LOCK, 0x53);
        m.put(KC_NUMPAD_DIVIDE, 0x54);
        m.put(KC_NUMPAD_MULTIPLY, 0x55);
        m.put(KC_NUMPAD_SUBTRACT, 0x56);
        m.put(KC_NUMPAD_ADD, 0x57);
        m.put(KC_NUMPAD_ENTER, 0x58);
        for (int i = 0; i < 9; i++) m.put(KC_NUMPAD_1 + i, 0x59 + i);
        m.put(KC_NUMPAD_0, 0x62);
        m.put(KC_NUMPAD_DOT, 0x63);
        m.put(KC_MENU, 0x65);
        m.put(KC_NUMPAD_EQUALS, 0x67);
        m.put(KC_NUMPAD_COMMA, 0x85);
        m.put(KC_CUT, 0x7B);
        m.put(KC_COPY, 0x7C);
        m.put(KC_PASTE, 0x7D);
        m.put(KC_VOLUME_MUTE, 0x7F);
        m.put(KC_VOLUME_UP, 0x80);
        m.put(KC_VOLUME_DOWN, 0x81);
        return m;
    }

    public static byte[] getPointerReportDescriptor() {
        return POINTER_REPORT_DESCRIPTOR.clone();
    }

    public static byte[] getTouchReportDescriptor() {
        return TOUCH_REPORT_DESCRIPTOR.clone();
    }

    public static byte[] getKeyboardReportDescriptor() {
        return KEYBOARD_REPORT_DESCRIPTOR.clone();
    }

    public static String touchPhysForDisplay(int displayId) {
        return "opaldesk-uhid-touch-" + displayId;
    }

    public static String pointerPhysForDisplay(int displayId) {
        return "opaldesk-uhid-ptr-" + displayId;
    }

    public static String keyboardPhysForDisplay(int displayId) {
        return "opaldesk-uhid-kbd-" + displayId;
    }

    public static byte[] buildCreate2Event(
            String name,
            String phys,
            String uniq,
            int bus,
            int vendor,
            int product,
            int version,
            int country,
            byte[] reportDescriptor) {
        if (reportDescriptor == null || reportDescriptor.length == 0
                || reportDescriptor.length > HID_MAX_DESCRIPTOR_SIZE) {
            throw new IllegalArgumentException("Invalid HID report descriptor length");
        }
        byte[] buf = new byte[UHID_EVENT_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(OFFSET_TYPE, UHID_CREATE2);
        writeCString(buf, OFFSET_CREATE2_NAME, 128, name);
        writeCString(buf, OFFSET_CREATE2_PHYS, 64, phys);
        writeCString(buf, OFFSET_CREATE2_UNIQ, 64, uniq);
        bb.putShort(OFFSET_CREATE2_RD_SIZE, (short) (reportDescriptor.length & 0xFFFF));
        bb.putShort(OFFSET_CREATE2_BUS, (short) (bus & 0xFFFF));
        bb.putInt(OFFSET_CREATE2_VENDOR, vendor);
        bb.putInt(OFFSET_CREATE2_PRODUCT, product);
        bb.putInt(OFFSET_CREATE2_VERSION, version);
        bb.putInt(OFFSET_CREATE2_COUNTRY, country);
        System.arraycopy(reportDescriptor, 0, buf, OFFSET_CREATE2_RD_DATA, reportDescriptor.length);
        return buf;
    }

    public static byte[] buildInput2Event(byte[] reportData) {
        if (reportData == null || reportData.length == 0 || reportData.length > HID_MAX_DESCRIPTOR_SIZE) {
            throw new IllegalArgumentException("Invalid UHID_INPUT2 report length");
        }
        byte[] buf = new byte[UHID_EVENT_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(OFFSET_TYPE, UHID_INPUT2);
        bb.putShort(OFFSET_INPUT2_SIZE, (short) (reportData.length & 0xFFFF));
        System.arraycopy(reportData, 0, buf, OFFSET_INPUT2_DATA, reportData.length);
        return buf;
    }

    public static byte[] buildDestroyEvent() {
        byte[] buf = new byte[UHID_EVENT_SIZE];
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).putInt(OFFSET_TYPE, UHID_DESTROY);
        return buf;
    }

    public static int toAbsoluteAxis(float coord, int displaySize) {
        if (!Float.isFinite(coord) || displaySize <= 1) return 0;
        double norm = coord / (double) (displaySize - 1);
        int val = (int) Math.round(norm * ABS_MAX);
        return Math.max(0, Math.min(ABS_MAX, val));
    }

    public static byte clampWheelDelta(float delta) {
        if (!Float.isFinite(delta) || delta == 0f) return 0;
        int rounded = Math.round(delta);
        if (rounded == 0) {
            rounded = (delta > 0f) ? 1 : -1;
        }
        return (byte) Math.max(-127, Math.min(127, rounded));
    }

    public static final class GetReportRequest {
        public final int id;
        public final int rnum;
        public final int rtype;

        public GetReportRequest(int id, int rnum, int rtype) {
            this.id = id;
            this.rnum = rnum;
            this.rtype = rtype;
        }
    }

    public static GetReportRequest parseGetReportRequest(byte[] buf, int len) {
        if (buf == null || len < OFFSET_GET_REPORT_RTYPE + 1) return null;
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(OFFSET_TYPE) != UHID_GET_REPORT) return null;
        int id = bb.getInt(OFFSET_GET_REPORT_ID);
        int rnum = buf[OFFSET_GET_REPORT_RNUM] & 0xFF;
        int rtype = buf[OFFSET_GET_REPORT_RTYPE] & 0xFF;
        return new GetReportRequest(id, rnum, rtype);
    }

    public static int parseSetReportRequestId(byte[] buf, int len) {
        if (buf == null || len < OFFSET_SET_REPORT_ID + 4) return -1;
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(OFFSET_TYPE) != UHID_SET_REPORT) return -1;
        return bb.getInt(OFFSET_SET_REPORT_ID);
    }

    public static byte[] buildGetReportReplyEvent(int id, int err, byte[] data) {
        int dataLen = (data != null) ? Math.min(HID_MAX_DESCRIPTOR_SIZE, data.length) : 0;
        byte[] buf = new byte[UHID_EVENT_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(OFFSET_TYPE, UHID_GET_REPORT_REPLY);
        bb.putInt(OFFSET_GET_REPORT_REPLY_ID, id);
        bb.putShort(OFFSET_GET_REPORT_REPLY_ERR, (short) (err & 0xFFFF));
        bb.putShort(OFFSET_GET_REPORT_REPLY_SIZE, (short) (dataLen & 0xFFFF));
        if (dataLen > 0) {
            System.arraycopy(data, 0, buf, OFFSET_GET_REPORT_REPLY_DATA, dataLen);
        }
        return buf;
    }

    public static byte[] buildSetReportReplyEvent(int id, int err) {
        byte[] buf = new byte[UHID_EVENT_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(OFFSET_TYPE, UHID_SET_REPORT_REPLY);
        bb.putInt(OFFSET_SET_REPORT_REPLY_ID, id);
        bb.putShort(OFFSET_SET_REPORT_REPLY_ERR, (short) (err & 0xFFFF));
        return buf;
    }

    public static byte[] buildPointerReport(
            int buttonsMask, int relX, int relY, int vWheel, int hWheel) {
        int clampedX = Math.max(-REL_MAX, Math.min(REL_MAX, relX));
        int clampedY = Math.max(-REL_MAX, Math.min(REL_MAX, relY));
        byte[] report = new byte[POINTER_REPORT_SIZE];
        report[0] = REPORT_ID_POINTER;
        report[1] = (byte) (buttonsMask & 0x1F);
        report[2] = (byte) (clampedX & 0xFF);
        report[3] = (byte) ((clampedX >>> 8) & 0xFF);
        report[4] = (byte) (clampedY & 0xFF);
        report[5] = (byte) ((clampedY >>> 8) & 0xFF);
        report[6] = (byte) Math.max(-127, Math.min(127, vWheel));
        report[7] = (byte) Math.max(-127, Math.min(127, hWheel));
        return report;
    }

    public static byte[] buildTouchReport(
            int count,
            int[] contactIds,
            boolean[] tipDowns,
            int[] absXs,
            int[] absYs) {
        byte[] report = new byte[TOUCH_REPORT_SIZE];
        report[0] = REPORT_ID_TOUCH;
        int n = Math.max(0, Math.min(MAX_TOUCH_CONTACTS, count));
        if (contactIds == null || tipDowns == null || absXs == null || absYs == null) {
            n = 0;
        } else {
            n = Math.min(n, Math.min(contactIds.length,
                    Math.min(tipDowns.length, Math.min(absXs.length, absYs.length))));
        }
        for (int i = 0; i < n; i++) {
            int off = 1 + (i * TOUCH_CONTACT_BYTES);
            int x = Math.max(0, Math.min(ABS_MAX, absXs[i]));
            int y = Math.max(0, Math.min(ABS_MAX, absYs[i]));
            report[off] = (byte) (tipDowns[i] ? 0x01 : 0x00);
            report[off + 1] = (byte) (contactIds[i] & 0xFF);
            report[off + 2] = (byte) (x & 0xFF);
            report[off + 3] = (byte) ((x >>> 8) & 0xFF);
            report[off + 4] = (byte) (y & 0xFF);
            report[off + 5] = (byte) ((y >>> 8) & 0xFF);
        }
        report[TOUCH_REPORT_SIZE - 1] = (byte) n;
        return report;
    }

    public static byte[] buildKeyboardReport(int modifiersMask, int[] activeHidUsages) {
        byte[] report = new byte[KEYBOARD_REPORT_SIZE];
        report[0] = REPORT_ID_KEYBOARD;
        report[1] = (byte) (modifiersMask & 0xFF);
        report[2] = 0;
        if (activeHidUsages != null) {
            int count = Math.min(6, activeHidUsages.length);
            for (int i = 0; i < count; i++) {
                report[3 + i] = (byte) (activeHidUsages[i] & 0xFF);
            }
        }
        return report;
    }

    public static int androidKeyCodeToHidUsage(int androidKeyCode) {
        Integer usage = KEYCODE_TO_HID.get(androidKeyCode);
        return (usage != null) ? usage : 0;
    }

    public static int androidModifierKeyToBit(int androidKeyCode) {
        switch (androidKeyCode) {
            case KC_CTRL_LEFT:   return MOD_LEFT_CTRL;
            case KC_SHIFT_LEFT:  return MOD_LEFT_SHIFT;
            case KC_ALT_LEFT:    return MOD_LEFT_ALT;
            case KC_META_LEFT:   return MOD_LEFT_GUI;
            case KC_CTRL_RIGHT:  return MOD_RIGHT_CTRL;
            case KC_SHIFT_RIGHT: return MOD_RIGHT_SHIFT;
            case KC_ALT_RIGHT:   return MOD_RIGHT_ALT;
            case KC_META_RIGHT:  return MOD_RIGHT_GUI;
            default: return 0;
        }
    }

    public static int androidMetaToHidModifiers(int metaState) {
        int mods = 0;
        if ((metaState & META_CTRL_LEFT_ON) != 0) mods |= MOD_LEFT_CTRL;
        if ((metaState & META_CTRL_RIGHT_ON) != 0) mods |= MOD_RIGHT_CTRL;
        if ((metaState & META_CTRL_ON) != 0 && (mods & (MOD_LEFT_CTRL | MOD_RIGHT_CTRL)) == 0) {
            mods |= MOD_LEFT_CTRL;
        }
        if ((metaState & META_SHIFT_LEFT_ON) != 0) mods |= MOD_LEFT_SHIFT;
        if ((metaState & META_SHIFT_RIGHT_ON) != 0) mods |= MOD_RIGHT_SHIFT;
        if ((metaState & META_SHIFT_ON) != 0 && (mods & (MOD_LEFT_SHIFT | MOD_RIGHT_SHIFT)) == 0) {
            mods |= MOD_LEFT_SHIFT;
        }
        if ((metaState & META_ALT_LEFT_ON) != 0) mods |= MOD_LEFT_ALT;
        if ((metaState & META_ALT_RIGHT_ON) != 0) mods |= MOD_RIGHT_ALT;
        if ((metaState & META_ALT_ON) != 0 && (mods & (MOD_LEFT_ALT | MOD_RIGHT_ALT)) == 0) {
            mods |= MOD_LEFT_ALT;
        }
        if ((metaState & META_META_LEFT_ON) != 0) mods |= MOD_LEFT_GUI;
        if ((metaState & META_META_RIGHT_ON) != 0) mods |= MOD_RIGHT_GUI;
        if ((metaState & META_META_ON) != 0 && (mods & (MOD_LEFT_GUI | MOD_RIGHT_GUI)) == 0) {
            mods |= MOD_LEFT_GUI;
        }
        return mods;
    }

    /**
     * Converts Android {@code MotionEvent.AXIS_HSCROLL} / {@code AXIS_VSCROLL} deltas into
     * proportional pixel-space finger drag deltas {@code [dxPx, dyPx]} for cursorless UHID
     * touch-scroll synthesis.
     *
     * <p>Sign convention matches Android's scroll axes:
     * <ul>
     *   <li>{@code vScroll > 0} (wheel up / 2-finger down) -&gt; {@code dyPx > 0} (drag finger down)</li>
     *   <li>{@code hScroll > 0} (scroll right) -&gt; {@code dxPx < 0} (drag finger left)</li>
     * </ul>
     */
    public static int[] computeUhidTouchScrollDeltaPx(float hScroll, float vScroll, int displayW, int displayH) {
        float hs = Float.isFinite(hScroll) ? hScroll : 0f;
        float vs = Float.isFinite(vScroll) ? vScroll : 0f;
        if (Math.abs(hs) < 0.001f && Math.abs(vs) < 0.001f) {
            return new int[]{0, 0};
        }
        int minDim = Math.max(200, Math.min(Math.max(2, displayW), Math.max(2, displayH)));
        float pxPerUnit = Math.max(48f, Math.min(180f, minDim * 0.11f));
        float maxTravel = Math.max(80f, Math.min(480f, minDim * 0.42f));
        int dx = scaleScrollAxis(-hs, pxPerUnit, maxTravel);
        int dy = scaleScrollAxis(vs, pxPerUnit, maxTravel);
        return new int[]{dx, dy};
    }

    private static int scaleScrollAxis(float signedUnits, float pxPerUnit, float maxTravel) {
        if (Math.abs(signedUnits) < 0.001f) return 0;
        float raw = signedUnits * pxPerUnit;
        float mag = Math.max(4f, Math.min(maxTravel, Math.abs(raw)));
        int px = Math.round(mag);
        return (raw >= 0f) ? px : -px;
    }

    /**
     * Secondary VirtualDisplays ({@code displayId > 0} with a non-empty {@code vdUniqueId}) must
     * successfully bind their UHID physical port to {@code vdUniqueId}; otherwise Android's
     * {@code InputReader} routes virtual pointer clicks blindly to Display 0 instead of the
     * target VirtualDisplay.
     */
    public static boolean isDisplayAssociationRequired(int displayId, String vdUniqueId) {
        return displayId > 0 && vdUniqueId != null && !vdUniqueId.isEmpty();
    }

    public static String unwrapThrowableMessage(Throwable t) {
        if (t == null) return "unknown";
        Throwable cur = t;
        int depth = 0;
        while (cur instanceof java.lang.reflect.InvocationTargetException && cur.getCause() != null && depth++ < 4) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg != null && !msg.isEmpty())
                ? (cur.getClass().getSimpleName() + ": " + msg)
                : cur.getClass().getSimpleName();
    }

    private static void writeCString(byte[] dst, int offset, int maxBytes, String text) {
        if (text == null || text.isEmpty() || maxBytes <= 1) return;
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(utf8.length, maxBytes - 1);
        System.arraycopy(utf8, 0, dst, offset, len);
        dst[offset + len] = 0;
    }
}
