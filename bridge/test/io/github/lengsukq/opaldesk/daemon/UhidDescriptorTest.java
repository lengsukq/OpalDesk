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
        byte[] ptrReport = UhidProtocol.buildPointerReport(0x05, 16384, 32767, -3, 2);
        assertEquals(UhidProtocol.POINTER_REPORT_SIZE, ptrReport.length);
        assertEquals(UhidProtocol.REPORT_ID_POINTER, ptrReport[0]);
        assertEquals(0x05, ptrReport[1] & 0xFF);
        ByteBuffer repBb = ByteBuffer.wrap(ptrReport).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(16384, repBb.getShort(2) & 0xFFFF);
        assertEquals(32767, repBb.getShort(4) & 0xFFFF);
        assertEquals(-3, ptrReport[6]);
        assertEquals(2, ptrReport[7]);

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
        byte[] kbdRd = UhidProtocol.getKeyboardReportDescriptor();
        assertTrue(ptrRd.length > 30 && ptrRd.length < UhidProtocol.HID_MAX_DESCRIPTOR_SIZE);
        assertTrue(kbdRd.length > 30 && kbdRd.length < UhidProtocol.HID_MAX_DESCRIPTOR_SIZE);

        assertBalancedCollections(ptrRd);
        assertBalancedCollections(kbdRd);

        assertTrue(containsSequence(ptrRd, new byte[]{(byte) 0x85, 0x01}));
        assertTrue(containsSequence(ptrRd, new byte[]{0x26, (byte) 0xFF, 0x7F}));
        assertTrue(containsSequence(ptrRd, new byte[]{0x0A, 0x38, 0x02}));

        assertTrue(containsSequence(kbdRd, new byte[]{(byte) 0x85, 0x02}));
        assertTrue(containsSequence(kbdRd, new byte[]{0x19, (byte) 0xE0}));
        assertTrue(containsSequence(kbdRd, new byte[]{0x29, (byte) 0xE7}));
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
