package io.github.lengsukq.opaldesk.daemon;

import android.view.KeyEvent;

import io.github.lengsukq.opaldesk.daemon.stream.UhidProtocol;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UhidDescriptorTest {

    private static String readCString(byte[] buf, int offset, int maxLen) {
        int end = offset;
        while (end < offset + maxLen && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset, StandardCharsets.UTF_8);
    }

    @Test
    public void testLinux64BitUhidCreate2StructOffsets() {
        byte[] rd = UhidProtocol.getPointerReportDescriptor();
        String phys = UhidProtocol.pointerPhysForDisplay(7);
        assertEquals("opaldesk-uhid-ptr-7", phys);

        byte[] ev = UhidProtocol.buildCreate2Event(
                "OpalDesk Pointer",
                phys,
                "opaldesk-ptr-7",
                UhidProtocol.BUS_VIRTUAL,
                UhidProtocol.VENDOR_ID,
                UhidProtocol.PRODUCT_ID_POINTER,
                UhidProtocol.DEVICE_VERSION,
                0,
                rd);

        assertEquals("64-bit struct uhid_event must be exactly 4380 bytes", 4380, ev.length);
        ByteBuffer bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(UhidProtocol.UHID_CREATE2, bb.getInt(0));
        assertEquals("OpalDesk Pointer", readCString(ev, 4, 128));
        assertEquals("opaldesk-uhid-ptr-7", readCString(ev, 132, 64));
        assertEquals("opaldesk-ptr-7", readCString(ev, 196, 64));
        assertEquals(rd.length, bb.getShort(260) & 0xFFFF);
        assertEquals(UhidProtocol.BUS_VIRTUAL, bb.getShort(262) & 0xFFFF);
        assertEquals(UhidProtocol.VENDOR_ID, bb.getInt(264));
        assertEquals(UhidProtocol.PRODUCT_ID_POINTER, bb.getInt(268));
        assertEquals(UhidProtocol.DEVICE_VERSION, bb.getInt(272));
        assertEquals(0, bb.getInt(276));

        for (int i = 0; i < rd.length; i++) {
            assertEquals("Descriptor byte mismatch at " + i, rd[i], ev[280 + i]);
        }
        assertEquals(0, bb.getInt(4376));
    }

    @Test
    public void testUhidInput2AndDestroyStructOffsets() {
        byte[] ptrReport = UhidProtocol.buildPointerReport(0x05, -120, -45, -3, 2);
        assertEquals(UhidProtocol.POINTER_REPORT_SIZE, ptrReport.length);
        assertEquals(UhidProtocol.REPORT_ID_POINTER, ptrReport[0]);
        assertEquals(0x05, ptrReport[1] & 0xFF);
        ByteBuffer repBb = ByteBuffer.wrap(ptrReport).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(-120, repBb.getShort(2));
        assertEquals(-45, repBb.getShort(4));
        assertEquals(-3, ptrReport[6]);
        assertEquals(2, ptrReport[7]);

        byte[] clampedPtr = UhidProtocol.buildPointerReport(0x01, 50000, -50000, 0, 0);
        ByteBuffer clampedBb = ByteBuffer.wrap(clampedPtr).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(UhidProtocol.REL_MAX, clampedBb.getShort(2));
        assertEquals(-UhidProtocol.REL_MAX, clampedBb.getShort(4));

        byte[] inputEv = UhidProtocol.buildInput2Event(ptrReport);
        assertEquals(4380, inputEv.length);
        ByteBuffer inBb = ByteBuffer.wrap(inputEv).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(UhidProtocol.UHID_INPUT2, inBb.getInt(0));
        assertEquals(ptrReport.length, inBb.getShort(4) & 0xFFFF);
        for (int i = 0; i < ptrReport.length; i++) {
            assertEquals(ptrReport[i], inputEv[6 + i]);
        }

        byte[] destroyEv = UhidProtocol.buildDestroyEvent();
        assertEquals(4380, destroyEv.length);
        assertEquals(UhidProtocol.UHID_DESTROY, ByteBuffer.wrap(destroyEv).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
    }

    @Test
    public void testHidReportDescriptorsWellFormed() {
        byte[] ptrRd = UhidProtocol.getPointerReportDescriptor();
        byte[] touchRd = UhidProtocol.getTouchReportDescriptor();
        byte[] kbdRd = UhidProtocol.getKeyboardReportDescriptor();
        assertTrue(ptrRd.length > 30 && ptrRd.length < UhidProtocol.HID_MAX_DESCRIPTOR_SIZE);
        assertTrue(touchRd.length > 100 && touchRd.length < UhidProtocol.HID_MAX_DESCRIPTOR_SIZE);
        assertTrue(kbdRd.length > 30 && kbdRd.length < UhidProtocol.HID_MAX_DESCRIPTOR_SIZE);

        assertBalancedCollections(ptrRd);
        assertBalancedCollections(touchRd);
        assertBalancedCollections(kbdRd);

        // Relative pointer descriptor: Report ID 1, -32767..32767, Input Relative (0x81 0x06), AC Pan
        assertTrue(containsSequence(ptrRd, new byte[]{(byte) 0x85, 0x01}));
        assertTrue(containsSequence(ptrRd, new byte[]{0x16, 0x01, (byte) 0x80}));
        assertTrue(containsSequence(ptrRd, new byte[]{0x26, (byte) 0xFF, 0x7F}));
        assertTrue(containsSequence(ptrRd, new byte[]{(byte) 0x81, 0x06}));
        assertTrue(containsSequence(ptrRd, new byte[]{0x0A, 0x38, 0x02}));

        // Multitouch touchscreen descriptor: Digitizer Touch Screen, Tip Switch, Contact ID, Count, Feature Max=10
        assertTrue(containsSequence(touchRd, new byte[]{0x05, 0x0D, 0x09, 0x04}));
        assertTrue(containsSequence(touchRd, new byte[]{0x09, 0x42}));
        assertTrue(containsSequence(touchRd, new byte[]{0x09, 0x51}));
        assertTrue(containsSequence(touchRd, new byte[]{0x09, 0x54}));
        assertTrue(containsSequence(touchRd, new byte[]{(byte) 0x85, 0x03, 0x09, 0x55}));

        assertTrue(containsSequence(kbdRd, new byte[]{(byte) 0x85, 0x02}));
        assertTrue(containsSequence(kbdRd, new byte[]{0x19, (byte) 0xE0}));
        assertTrue(containsSequence(kbdRd, new byte[]{0x29, (byte) 0xE7}));
    }

    @Test
    public void testTouchReportAndFeatureReplyEncoding() {
        assertEquals("opaldesk-uhid-touch-7", UhidProtocol.touchPhysForDisplay(7));

        byte[] touchReport = UhidProtocol.buildTouchReport(
                2,
                new int[]{3, 7},
                new boolean[]{true, false},
                new int[]{12000, 40000},
                new int[]{24000, -50});
        assertEquals(UhidProtocol.TOUCH_REPORT_SIZE, touchReport.length);
        assertEquals(UhidProtocol.REPORT_ID_TOUCH, touchReport[0]);
        ByteBuffer tb = ByteBuffer.wrap(touchReport).order(ByteOrder.LITTLE_ENDIAN);
        // Slot 0: tip down, contact id 3, x=12000, y=24000
        assertEquals(0x01, touchReport[1] & 0xFF);
        assertEquals(3, touchReport[2] & 0xFF);
        assertEquals(12000, tb.getShort(3) & 0xFFFF);
        assertEquals(24000, tb.getShort(5) & 0xFFFF);
        // Slot 1: tip up (lifted pointer), contact id 7, clamped to [0, ABS_MAX]
        assertEquals(0x00, touchReport[7] & 0xFF);
        assertEquals(7, touchReport[8] & 0xFF);
        assertEquals(UhidProtocol.ABS_MAX, tb.getShort(9) & 0xFFFF);
        assertEquals(0, tb.getShort(11) & 0xFFFF);
        // Contact Count
        assertEquals(2, touchReport[UhidProtocol.TOUCH_REPORT_SIZE - 1] & 0xFF);

        byte[] emptyTouch = UhidProtocol.buildTouchReport(0, null, null, null, null);
        assertEquals(UhidProtocol.REPORT_ID_TOUCH, emptyTouch[0]);
        assertEquals(0, emptyTouch[UhidProtocol.TOUCH_REPORT_SIZE - 1] & 0xFF);

        byte[] getReqBuf = new byte[UhidProtocol.UHID_EVENT_SIZE];
        ByteBuffer gr = ByteBuffer.wrap(getReqBuf).order(ByteOrder.LITTLE_ENDIAN);
        gr.putInt(UhidProtocol.OFFSET_TYPE, UhidProtocol.UHID_GET_REPORT);
        gr.putInt(UhidProtocol.OFFSET_GET_REPORT_ID, 0x12345678);
        getReqBuf[UhidProtocol.OFFSET_GET_REPORT_RNUM] = (byte) UhidProtocol.REPORT_ID_TOUCH_FEATURE;
        getReqBuf[UhidProtocol.OFFSET_GET_REPORT_RTYPE] = (byte) UhidProtocol.UHID_FEATURE_REPORT;

        UhidProtocol.GetReportRequest parsed = UhidProtocol.parseGetReportRequest(getReqBuf, getReqBuf.length);
        assertTrue(parsed != null);
        assertEquals(0x12345678, parsed.id);
        assertEquals(UhidProtocol.REPORT_ID_TOUCH_FEATURE, parsed.rnum);
        assertEquals(UhidProtocol.UHID_FEATURE_REPORT, parsed.rtype);

        byte[] getReply = UhidProtocol.buildGetReportReplyEvent(
                parsed.id, 0, new byte[]{(byte) UhidProtocol.REPORT_ID_TOUCH_FEATURE, (byte) UhidProtocol.MAX_TOUCH_CONTACTS});
        assertEquals(UhidProtocol.UHID_EVENT_SIZE, getReply.length);
        ByteBuffer replyBb = ByteBuffer.wrap(getReply).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(UhidProtocol.UHID_GET_REPORT_REPLY, replyBb.getInt(UhidProtocol.OFFSET_TYPE));
        assertEquals(0x12345678, replyBb.getInt(UhidProtocol.OFFSET_GET_REPORT_REPLY_ID));
        assertEquals(0, replyBb.getShort(UhidProtocol.OFFSET_GET_REPORT_REPLY_ERR) & 0xFFFF);
        assertEquals(2, replyBb.getShort(UhidProtocol.OFFSET_GET_REPORT_REPLY_SIZE) & 0xFFFF);
        assertEquals(UhidProtocol.REPORT_ID_TOUCH_FEATURE, getReply[UhidProtocol.OFFSET_GET_REPORT_REPLY_DATA] & 0xFF);
        assertEquals(UhidProtocol.MAX_TOUCH_CONTACTS, getReply[UhidProtocol.OFFSET_GET_REPORT_REPLY_DATA + 1] & 0xFF);

        byte[] setReqBuf = new byte[UhidProtocol.UHID_EVENT_SIZE];
        ByteBuffer sr = ByteBuffer.wrap(setReqBuf).order(ByteOrder.LITTLE_ENDIAN);
        sr.putInt(UhidProtocol.OFFSET_TYPE, UhidProtocol.UHID_SET_REPORT);
        sr.putInt(UhidProtocol.OFFSET_SET_REPORT_ID, 0x10203040);
        assertEquals(0x10203040, UhidProtocol.parseSetReportRequestId(setReqBuf, setReqBuf.length));

        byte[] setReply = UhidProtocol.buildSetReportReplyEvent(0x10203040, 0);
        ByteBuffer sReplyBb = ByteBuffer.wrap(setReply).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(UhidProtocol.UHID_SET_REPORT_REPLY, sReplyBb.getInt(UhidProtocol.OFFSET_TYPE));
        assertEquals(0x10203040, sReplyBb.getInt(UhidProtocol.OFFSET_SET_REPORT_REPLY_ID));
        assertEquals(0, sReplyBb.getShort(UhidProtocol.OFFSET_SET_REPORT_REPLY_ERR) & 0xFFFF);
    }

    @Test
    public void testKeyboardReportAndAndroidKeyCodeToHidMapping() {
        assertEquals("opaldesk-uhid-kbd-9", UhidProtocol.keyboardPhysForDisplay(9));

        assertEquals(0x04, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_A));
        assertEquals(0x19, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_V));
        assertEquals(0x1D, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_Z));
        assertEquals(0x1E, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_1));
        assertEquals(0x27, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_0));
        assertEquals(0x28, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_ENTER));
        assertEquals(0x29, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_ESCAPE));
        assertEquals(0x2A, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_DEL));
        assertEquals(0x2B, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_TAB));
        assertEquals(0x2C, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_SPACE));
        assertEquals(0x3A, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_F1));
        assertEquals(0x45, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_F12));
        assertEquals(0x4F, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(0x52, UhidProtocol.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_DPAD_UP));

        int mods = UhidProtocol.androidMetaToHidModifiers(
                KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_SHIFT_RIGHT_ON | KeyEvent.META_ALT_ON);
        assertEquals(UhidProtocol.MOD_LEFT_CTRL | UhidProtocol.MOD_RIGHT_SHIFT | UhidProtocol.MOD_LEFT_ALT, mods);

        byte[] kbdReport = UhidProtocol.buildKeyboardReport(mods, new int[]{0x04, 0x19});
        assertEquals(UhidProtocol.KEYBOARD_REPORT_SIZE, kbdReport.length);
        assertEquals(UhidProtocol.REPORT_ID_KEYBOARD, kbdReport[0]);
        assertEquals(mods, kbdReport[1] & 0xFF);
        assertEquals(0, kbdReport[2]);
        assertEquals(0x04, kbdReport[3] & 0xFF);
        assertEquals(0x19, kbdReport[4] & 0xFF);
        assertEquals(0, kbdReport[5]);

        assertEquals(0, UhidProtocol.toAbsoluteAxis(-10f, 1920));
        assertEquals(0, UhidProtocol.toAbsoluteAxis(Float.NaN, 1920));
        assertEquals(UhidProtocol.ABS_MAX, UhidProtocol.toAbsoluteAxis(1919f, 1920));
        assertEquals(UhidProtocol.ABS_MAX, UhidProtocol.toAbsoluteAxis(5000f, 1920));
        assertEquals(1, UhidProtocol.clampWheelDelta(0.2f));
        assertEquals(-1, UhidProtocol.clampWheelDelta(-0.2f));
        assertEquals(127, UhidProtocol.clampWheelDelta(999f));
        assertEquals(-127, UhidProtocol.clampWheelDelta(-999f));
    }

    @Test
    public void testUhidAssociationRequirementAndThrowableUnwrapping() {
        assertFalse(UhidProtocol.isDisplayAssociationRequired(0, "virtual:com.android.shell,2000,OpalWin-1,0"));
        assertFalse(UhidProtocol.isDisplayAssociationRequired(5, null));
        assertFalse(UhidProtocol.isDisplayAssociationRequired(5, ""));
        assertTrue(UhidProtocol.isDisplayAssociationRequired(5, "virtual:com.android.shell,2000,OpalDesk-Remote,0"));

        InvocationTargetException wrapped = new InvocationTargetException(
                new SecurityException("Requires ASSOCIATE_INPUT_DEVICE_TO_DISPLAY"));
        String detail = UhidProtocol.unwrapThrowableMessage(wrapped);
        assertTrue(detail.contains("SecurityException"));
        assertTrue(detail.contains("ASSOCIATE_INPUT_DEVICE_TO_DISPLAY"));
        assertEquals("NullPointerException", UhidProtocol.unwrapThrowableMessage(new NullPointerException()));
    }

    private static void assertBalancedCollections(byte[] desc) {
        int depth = 0;
        int i = 0;
        while (i < desc.length) {
            int prefix = desc[i] & 0xFF;
            int sizeCode = prefix & 0x03;
            int dataLen = (sizeCode == 3) ? 4 : sizeCode;
            if (prefix == 0xA1) depth++;
            else if (prefix == 0xC0) depth--;
            assertTrue("HID Collection depth must never go negative", depth >= 0);
            i += 1 + dataLen;
        }
        assertEquals("HID descriptor items must end cleanly on boundary", desc.length, i);
        assertEquals("HID Collections must be balanced", 0, depth);
    }

    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
