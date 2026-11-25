package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.audio.AudioRoutingPolicy;
import io.github.lengsukq.opaldesk.daemon.audio.RemoteMicInjector;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;
import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;
import io.github.lengsukq.opaldesk.daemon.video.RootCameraBridge;
import io.github.lengsukq.opaldesk.daemon.video.VirtualCameraPolicy;
import io.github.lengsukq.opaldesk.daemon.stream.FileTransferPathGuard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OpalCastProtocolTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private OpalCastProtocol.Frame roundTrip(int type, int flags, long tsUs, byte[] payload) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OpalCastProtocol.writeFrame(baos, type, flags, tsUs, payload, 0, payload.length);
        byte[] wire = baos.toByteArray();

        // Verify exact 18-byte BE header layout
        assertEquals(OpalCastProtocol.HEADER_SIZE + payload.length, wire.length);
        assertEquals('O', wire[0]);
        assertEquals('P', wire[1]);
        assertEquals('D', wire[2]);
        assertEquals('1', wire[3]);
        assertEquals(type & 0xFF, wire[4] & 0xFF);
        assertEquals(flags & 0xFF, wire[5] & 0xFF);
        ByteBuffer hdr = ByteBuffer.wrap(wire, 6, 12).order(ByteOrder.BIG_ENDIAN);
        assertEquals(payload.length, hdr.getInt());
        assertEquals(tsUs, hdr.getLong());

        ByteArrayInputStream bais = new ByteArrayInputStream(wire);
        OpalCastProtocol.Frame frame = OpalCastProtocol.readFrame(bais);
        assertNotNull(frame);
        assertEquals(type, frame.type);
        assertEquals(flags, frame.flags);
        assertEquals(tsUs, frame.timestampUs);
        assertArrayEquals(payload, frame.payload);
        assertNull(OpalCastProtocol.readFrame(bais));
        return frame;
    }

    @Test
    public void testVideoConfigAndFrame() throws Exception {
        byte[] csd = new byte[]{0, 0, 0, 1, 0x40, 0x01, 0x0C, 0, 0, 0, 1, 0x42, 0x01};
        OpalCastProtocol.VideoConfig cfg = new OpalCastProtocol.VideoConfig(
                OpalCastProtocol.VIDEO_CODEC_HEVC, 2560, 1440, 240, 0, 6000, OpalCastProtocol.DISPLAY_MODE_DEX, csd);
        byte[] enc = OpalCastProtocol.encodeVideoConfig(cfg);
        assertEquals(11 + csd.length, enc.length);

        OpalCastProtocol.Frame f = roundTrip(OpalCastProtocol.TYPE_VIDEO_CONFIG, 0, 123_456_789L, enc);
        OpalCastProtocol.VideoConfig dec = OpalCastClientTestCodec.decodeVideoConfig(f.payload);
        assertEquals(OpalCastProtocol.VIDEO_CODEC_HEVC, dec.codec);
        assertEquals(2560, dec.width);
        assertEquals(1440, dec.height);
        assertEquals(240, dec.dpi);
        assertEquals(0, dec.rotation);
        assertEquals(6000, dec.fpsX100);
        assertEquals(OpalCastProtocol.DISPLAY_MODE_DEX, dec.displayMode);
        assertArrayEquals(csd, dec.csdAnnexB);

        byte[] nal = new byte[]{0, 0, 0, 1, 0x26, 0x01, (byte) 0xAF};
        int flags = OpalCastProtocol.FLAG_VIDEO_KEYFRAME | OpalCastProtocol.FLAG_VIDEO_CONFIG_INLINE;
        OpalCastProtocol.Frame vf = roundTrip(OpalCastProtocol.TYPE_VIDEO_FRAME, flags, 987_654_321L, nal);
        assertEquals(flags, vf.flags);
        assertArrayEquals(nal, vf.payload);
    }

    @Test
    public void testAudioConfigAndFrame() throws Exception {
        byte[] asc = new byte[]{0x11, (byte) 0x90};
        OpalCastProtocol.AudioConfig cfg = new OpalCastProtocol.AudioConfig(
                OpalCastProtocol.AUDIO_CODEC_AAC_LC, 48_000, 2, asc);
        byte[] enc = OpalCastProtocol.encodeAudioConfig(cfg);
        assertEquals(8, enc.length);

        OpalCastProtocol.Frame f = roundTrip(OpalCastProtocol.TYPE_AUDIO_CONFIG, 0, 1000L, enc);
        OpalCastProtocol.AudioConfig dec = OpalCastClientTestCodec.decodeAudioConfig(f.payload);
        assertEquals(OpalCastProtocol.AUDIO_CODEC_AAC_LC, dec.codec);
        assertEquals(48_000, dec.sampleRate);
        assertEquals(2, dec.channels);
        assertArrayEquals(asc, dec.audioSpecificConfig);

        byte[] au = new byte[]{0x21, 0x10, 0x04, 0x60};
        roundTrip(OpalCastProtocol.TYPE_AUDIO_FRAME, 0, 2000L, au);
    }

    @Test
    public void testDeviceStateAndAuthResultAndHelloJson() throws Exception {
        OpalCastProtocol.DeviceState st = new OpalCastProtocol.DeviceState(
                "Xiaomi \"Pad\" \\ Pro", 2, 0, 1920, 1080, 240, 60.0, 0, 12000, 85, true, true);
        OpalCastProtocol.Frame f1 = roundTrip(OpalCastProtocol.TYPE_DEVICE_STATE, 0, 3000L,
                OpalCastProtocol.encodeDeviceState(st));
        OpalCastProtocol.DeviceState stDec = OpalCastClientTestCodec.decodeDeviceState(f1.payload);
        assertEquals("Xiaomi \"Pad\" \\ Pro", stDec.deviceName);
        assertEquals(2, stDec.sinkType);
        assertEquals(0, stDec.displayMode);
        assertEquals(1920, stDec.width);
        assertEquals(1080, stDec.height);
        assertEquals(240, stDec.dpi);
        assertEquals(60.0, stDec.fps, 0.01);
        assertEquals(0, stDec.rotation);
        assertEquals(12000, stDec.bitrateKbps);
        assertEquals(85, stDec.batteryPct);
        assertTrue(stDec.blackout);
        assertTrue(stDec.audioEnabled);

        OpalCastProtocol.AuthResultMsg auth = new OpalCastProtocol.AuthResultMsg(
                true, "abcdef0123456789", "OK 中文", "Redmi K Pad", "deadbeef");
        byte[] authBytes = OpalCastProtocol.encodeAuthResult(auth);
        assertEquals(1, authBytes[0]);
        OpalCastProtocol.Frame f2 = roundTrip(OpalCastProtocol.TYPE_AUTH_RESULT, 0, 4000L, authBytes);
        OpalCastProtocol.AuthResultMsg authDec = OpalCastClientTestCodec.decodeAuthResult(f2.payload);
        assertTrue(authDec.ok);
        assertEquals("abcdef0123456789", authDec.authToken);
        assertEquals("OK 中文", authDec.message);
        assertEquals("Redmi K Pad", authDec.deviceName);
        assertEquals("deadbeef", authDec.certFingerprint);

        OpalCastProtocol.HelloMsg hello = new OpalCastProtocol.HelloMsg(
                1, "Leo's MacBook Pro", "mac-uuid-123", "hevc",
                2560, 1440, 220, 60, 16000, 0, true, "tok123", "654321");
        OpalCastProtocol.Frame f3 = roundTrip(OpalCastProtocol.TYPE_HELLO, 0, 5000L,
                OpalCastClientTestCodec.encodeHello(hello));
        OpalCastProtocol.HelloMsg helloDec = OpalCastProtocol.decodeHello(f3.payload);
        assertEquals(1, helloDec.protocolVersion);
        assertEquals("Leo's MacBook Pro", helloDec.clientName);
        assertEquals("mac-uuid-123", helloDec.clientId);
        assertEquals("hevc", helloDec.preferredCodec);
        assertEquals(2560, helloDec.width);
        assertEquals(1440, helloDec.height);
        assertEquals(220, helloDec.dpi);
        assertEquals(60, helloDec.fps);
        assertEquals(16000, helloDec.bitrateKbps);
        assertEquals(0, helloDec.displayMode);
        assertTrue(helloDec.audioEnabled);
        assertEquals("tok123", helloDec.authToken);
        assertEquals("654321", helloDec.pinCode);
    }

    @Test
    public void testResizeAndInputAndControlFrames() throws Exception {
        // RESIZE_CONFIG (14 bytes)
        OpalCastProtocol.ResizeConfig rc = new OpalCastProtocol.ResizeConfig(
                1920, 1080, 240, 6000, 14000, OpalCastProtocol.DISPLAY_MODE_UNCHANGED, 2);
        byte[] rcEnc = OpalCastClientTestCodec.encodeResizeConfig(rc);
        assertEquals(14, rcEnc.length);
        OpalCastProtocol.ResizeConfig rcDec = OpalCastProtocol.decodeResizeConfig(
                roundTrip(OpalCastProtocol.TYPE_RESIZE_CONFIG, 0, 10L, rcEnc).payload);
        assertEquals(1920, rcDec.width);
        assertEquals(1080, rcDec.height);
        assertEquals(240, rcDec.dpi);
        assertEquals(6000, rcDec.fpsX100);
        assertEquals(14000, rcDec.bitrateKbps);
        assertEquals(0xFF, rcDec.displayMode);
        assertEquals(2, rcDec.rotation);

        // REQUEST_IDR (0 bytes)
        OpalCastProtocol.Frame idr = roundTrip(OpalCastProtocol.TYPE_REQUEST_IDR, 0, 11L, OpalCastProtocol.EMPTY_BYTES);
        assertEquals(0, idr.payload.length);

        // INPUT_MOUSE (25 bytes)
        OpalCastProtocol.MouseEventMsg mouse = new OpalCastProtocol.MouseEventMsg(
                OpalCastProtocol.MOUSE_SCROLL, 512.5f, 320.25f,
                OpalCastProtocol.BUTTON_PRIMARY | OpalCastProtocol.BUTTON_BACK,
                OpalCastProtocol.BUTTON_PRIMARY, -1.5f, 3.25f);
        byte[] mEnc = OpalCastClientTestCodec.encodeMouseEvent(mouse);
        assertEquals(25, mEnc.length);
        OpalCastProtocol.MouseEventMsg mDec = OpalCastProtocol.decodeMouseEvent(
                roundTrip(OpalCastProtocol.TYPE_INPUT_MOUSE, 0, 12L, mEnc).payload);
        assertEquals(OpalCastProtocol.MOUSE_SCROLL, mDec.action);
        assertEquals(512.5f, mDec.x, 0.0001f);
        assertEquals(320.25f, mDec.y, 0.0001f);
        assertEquals(9, mDec.buttons);
        assertEquals(1, mDec.actionButton);
        assertEquals(-1.5f, mDec.hScroll, 0.0001f);
        assertEquals(3.25f, mDec.vScroll, 0.0001f);

        // INPUT_TOUCH (3 + 16 * N bytes)
        OpalCastProtocol.TouchPointer[] pts = new OpalCastProtocol.TouchPointer[]{
                new OpalCastProtocol.TouchPointer(0, 100f, 200f, 0.8f),
                new OpalCastProtocol.TouchPointer(1, 300f, 400f, 0.9f)
        };
        OpalCastProtocol.TouchEventMsg touch = new OpalCastProtocol.TouchEventMsg(
                OpalCastProtocol.TOUCH_POINTER_DOWN, 1, pts);
        byte[] tEnc = OpalCastClientTestCodec.encodeTouchEvent(touch);
        assertEquals(3 + 32, tEnc.length);
        OpalCastProtocol.TouchEventMsg tDec = OpalCastProtocol.decodeTouchEvent(
                roundTrip(OpalCastProtocol.TYPE_INPUT_TOUCH, 0, 13L, tEnc).payload);
        assertEquals(OpalCastProtocol.TOUCH_POINTER_DOWN, tDec.action);
        assertEquals(1, tDec.actionIndex);
        assertEquals(2, tDec.pointers.length);
        assertEquals(1, tDec.pointers[1].pointerId);
        assertEquals(300f, tDec.pointers[1].x, 0.0001f);

        // INPUT_KEY (13 bytes)
        OpalCastProtocol.KeyEventMsg key = new OpalCastProtocol.KeyEventMsg(OpalCastProtocol.KEY_DOWN, 29, 4096, 1);
        byte[] kEnc = OpalCastClientTestCodec.encodeKeyEvent(key);
        assertEquals(13, kEnc.length);
        OpalCastProtocol.KeyEventMsg kDec = OpalCastProtocol.decodeKeyEvent(
                roundTrip(OpalCastProtocol.TYPE_INPUT_KEY, 0, 14L, kEnc).payload);
        assertEquals(OpalCastProtocol.KEY_DOWN, kDec.action);
        assertEquals(29, kDec.androidKeyCode);
        assertEquals(4096, kDec.metaState);
        assertEquals(1, kDec.repeatCount);

        // INPUT_TEXT + CLIPBOARD_PUSH
        OpalCastProtocol.TextEventMsg txt = new OpalCastProtocol.TextEventMsg(
                OpalCastProtocol.TEXT_MODE_COMMIT_PASTE, 42L, "你好 OpalDesk 🚀");
        OpalCastProtocol.TextEventMsg txtDec = OpalCastProtocol.decodeTextEvent(
                roundTrip(OpalCastProtocol.TYPE_INPUT_TEXT, 0, 15L, OpalCastClientTestCodec.encodeTextEvent(txt)).payload);
        assertEquals(OpalCastProtocol.TEXT_MODE_COMMIT_PASTE, txtDec.mode);
        assertEquals(42L, txtDec.seq);
        assertEquals("你好 OpalDesk 🚀", txtDec.text);

        OpalCastClientTestCodec.ClipboardPush cpDec = OpalCastClientTestCodec.decodeClipboardPush(
                roundTrip(OpalCastProtocol.TYPE_CLIPBOARD_PUSH, 0, 16L,
                        OpalCastProtocol.encodeClipboardPush(99L, "剪贴板同步")).payload);
        assertEquals(99L, cpDec.seq);
        assertEquals("剪贴板同步", cpDec.text);

        // SYSTEM_ACTION (5 bytes)
        byte[] sysEnc = OpalCastClientTestCodec.encodeSystemAction(OpalCastProtocol.SYS_TOGGLE_BLACKOUT, -1);
        assertEquals(5, sysEnc.length);
        OpalCastProtocol.SystemActionMsg sysDec = OpalCastProtocol.decodeSystemAction(
                roundTrip(OpalCastProtocol.TYPE_SYSTEM_ACTION, 0, 17L, sysEnc).payload);
        assertEquals(OpalCastProtocol.SYS_TOGGLE_BLACKOUT, sysDec.action);
        assertEquals(-1, sysDec.arg);

        // PING / PONG (8 bytes)
        long pingId = 0x1122334455667788L;
        assertEquals(pingId, OpalCastProtocol.decodePingPong(
                roundTrip(OpalCastProtocol.TYPE_PING, 0, 18L, OpalCastProtocol.encodePingPong(pingId)).payload));
        assertEquals(pingId, OpalCastProtocol.decodePingPong(
                roundTrip(OpalCastProtocol.TYPE_PONG, 0, 19L, OpalCastProtocol.encodePingPong(pingId)).payload));
    }

    @Test
    public void testFileTransferFramesAndPathTraversalDefense() throws Exception {
        byte[] startEnc = OpalCastClientTestCodec.encodeFileTransferStart(7, "report_2026.pdf", 4096L);
        OpalCastProtocol.FileTransferChunkMsg startDec = OpalCastProtocol.decodeFileTransferChunk(
                roundTrip(OpalCastProtocol.TYPE_FILE_TRANSFER_CHUNK, 0, 20L, startEnc).payload);
        assertEquals(7, startDec.transferId);
        assertEquals(OpalCastProtocol.FILE_FLAG_START, startDec.chunkFlags);
        assertEquals("report_2026.pdf", startDec.fileName);
        assertEquals(4096L, startDec.totalSize);

        byte[] chunk = new byte[]{1, 2, 3, 4, 5};
        byte[] dataEnc = OpalCastClientTestCodec.encodeFileTransferData(7, chunk, true);
        OpalCastProtocol.FileTransferChunkMsg dataDec = OpalCastProtocol.decodeFileTransferChunk(
                roundTrip(OpalCastProtocol.TYPE_FILE_TRANSFER_CHUNK, 0, 21L, dataEnc).payload);
        assertEquals(OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END, dataDec.chunkFlags);
        assertArrayEquals(chunk, dataDec.data);

        byte[] ackEnc = OpalCastProtocol.encodeFileTransferAck(7, OpalCastProtocol.FILE_ACK_OK,
                "/sdcard/Download/OpalDesk/report_2026.pdf");
        OpalCastClientTestCodec.FileTransferAck ackDec = OpalCastClientTestCodec.decodeFileTransferAck(
                roundTrip(OpalCastProtocol.TYPE_FILE_TRANSFER_ACK, 0, 22L, ackEnc).payload);
        assertEquals(7, ackDec.transferId);
        assertEquals(OpalCastProtocol.FILE_ACK_OK, ackDec.status);
        assertEquals("/sdcard/Download/OpalDesk/report_2026.pdf", ackDec.pathOrError);

        // Path traversal rejections
        assertNull(FileTransferPathGuard.sanitizeFileName("../etc/passwd"));
        assertNull(FileTransferPathGuard.sanitizeFileName("..\\..\\Windows\\win.ini"));
        assertNull(FileTransferPathGuard.sanitizeFileName("foo/../../bar.txt"));
        assertNull(FileTransferPathGuard.sanitizeFileName(".ssh_config"));
        assertNull(FileTransferPathGuard.sanitizeFileName("bad\0name.txt"));
        assertNull(FileTransferPathGuard.sanitizeFileName(""));
        assertEquals("photo.png", FileTransferPathGuard.sanitizeFileName("subdir/photo.png"));
        assertEquals("中文文档.pdf", FileTransferPathGuard.sanitizeFileName("中文文档.pdf"));

        File base = tmpDir.newFolder("OpalDesk").getCanonicalFile();
        File okTarget = FileTransferPathGuard.resolveUniqueSafeFile(base, "demo.txt");
        assertTrue(okTarget.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator));
        try {
            FileTransferPathGuard.verifyInsideBaseDir(base, new File(base, "../escape.txt"));
            fail("Expected SecurityException for path escape");
        } catch (SecurityException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test(expected = OpalCastProtocol.ProtocolException.class)
    public void testRejectInvalidMagic() throws Exception {
        byte[] bad = new byte[OpalCastProtocol.HEADER_SIZE];
        bad[0] = 'B'; bad[1] = 'A'; bad[2] = 'D'; bad[3] = '!';
        OpalCastProtocol.readFrame(new ByteArrayInputStream(bad));
    }

    @Test(expected = OpalCastProtocol.ProtocolException.class)
    public void testRejectOversizedPayloadAbove16MiB() throws Exception {
        byte[] hdr = new byte[OpalCastProtocol.HEADER_SIZE];
        OpalCastProtocol.encodeHeader(hdr, OpalCastProtocol.TYPE_VIDEO_FRAME, 0,
                OpalCastProtocol.MAX_PAYLOAD_LEN + 1, 0L);
        OpalCastProtocol.readFrame(new ByteArrayInputStream(hdr));
    }

    @Test(expected = OpalCastProtocol.ProtocolException.class)
    public void testRejectNegativeSignedUint32PayloadLen() throws Exception {
        byte[] hdr = new byte[OpalCastProtocol.HEADER_SIZE];
        OpalCastProtocol.encodeHeader(hdr, OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 0xFFFFFFFF, 0L);
        OpalCastProtocol.readFrame(new ByteArrayInputStream(hdr));
    }

    @Test(expected = EOFException.class)
    public void testRejectTruncatedFrame() throws Exception {
        byte[] hdr = new byte[OpalCastProtocol.HEADER_SIZE + 2];
        OpalCastProtocol.encodeHeader(hdr, OpalCastProtocol.TYPE_PING, 0, 8, 123L);
        OpalCastProtocol.readFrame(new ByteArrayInputStream(hdr));
    }

    @Test
    public void testV11HelloAndDeviceStateExtensionsAndBackwardCompat() throws Exception {
        assertEquals(4, DisplaySink.SINK_REMOTE_WIFI_ADB);
        assertEquals(BridgeBuildConfig.OPALCAST_PROTOCOL_VERSION, OpalCastProtocol.PROTOCOL_VERSION);

        // Full v1.1 HELLO with standalone_app + wifi_adb
        OpalCastProtocol.HelloMsg h11 = new OpalCastProtocol.HelloMsg(
                1, "Mac Studio", "mac-007", "hevc",
                840, 1680, 320, 120, 14000, 0, false, "tok", "",
                OpalCastProtocol.TRANSPORT_HINT_WIFI_ADB,
                OpalCastProtocol.SESSION_ROLE_STANDALONE_APP,
                "com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
        OpalCastProtocol.Frame hf = roundTrip(OpalCastProtocol.TYPE_HELLO, 0, 100L,
                OpalCastClientTestCodec.encodeHello(h11));
        OpalCastProtocol.HelloMsg hDec = OpalCastProtocol.decodeHello(hf.payload);
        assertEquals("wifi_adb", hDec.transportHint);
        assertEquals("standalone_app", hDec.sessionRole);
        assertTrue(hDec.isStandaloneApp());
        assertEquals("com.tencent.mm", hDec.appPackage);
        assertEquals("com.tencent.mm.ui.LauncherUI", hDec.appActivity);

        // Legacy v1.0 HELLO JSON lacking v1.1 fields defaults safely
        byte[] legacyHello = "{\"protocolVersion\":1,\"clientName\":\"OldMac\",\"clientId\":\"old1\"}".getBytes(StandardCharsets.UTF_8);
        OpalCastProtocol.HelloMsg legacyDec = OpalCastProtocol.decodeHello(legacyHello);
        assertEquals("", legacyDec.transportHint);
        assertEquals("desktop", legacyDec.sessionRole);
        assertFalse(legacyDec.isStandaloneApp());
        assertEquals("", legacyDec.appPackage);

        // Full v1.1 DEVICE_STATE (18-arg backward-compat defaults tiling lists to empty)
        OpalCastProtocol.DeviceState ds11 = new OpalCastProtocol.DeviceState(
                "Xiaomi 15 Ultra", DisplaySink.SINK_REMOTE_WIFI_ADB, 0,
                2560, 1440, 240, 120.0, 0, 18000, 92, false, true,
                2, true, 460, "ADAPTIVE_SINGLE", "com.tencent.mm", "微信");
        OpalCastProtocol.Frame df = roundTrip(OpalCastProtocol.TYPE_DEVICE_STATE, 0, 200L, OpalCastProtocol.encodeDeviceState(ds11));
        OpalCastProtocol.DeviceState dsDec = OpalCastClientTestCodec.decodeDeviceState(df.payload);
        assertEquals(DisplaySink.SINK_REMOTE_WIFI_ADB, dsDec.sinkType);
        assertEquals(2, dsDec.injectStatus);
        assertTrue(dsDec.adaptiveSingleEnabled);
        assertEquals(460, dsDec.defaultSingleWidthDp);
        assertEquals("ADAPTIVE_SINGLE", dsDec.activeLayoutMode);
        assertEquals("com.tencent.mm", dsDec.topAppPackage);
        assertEquals("微信", dsDec.topAppLabel);
        assertTrue(dsDec.tilingSlots.isEmpty());
        assertTrue(dsDec.tilingRatios.isEmpty());

        // Full v1.1 DEVICE_STATE with active split-screen tiling metadata (20-arg)
        OpalCastProtocol.DeviceState dsTile = new OpalCastProtocol.DeviceState(
                "Xiaomi 15 Ultra", DisplaySink.SINK_REMOTE_USB, 0,
                2560, 1440, 240, 60.0, 0, 16000, 88, false, true,
                1, true, 460, "SPLIT_2_H", "com.tencent.mm", "微信",
                Arrays.asList("com.tencent.mm", "com.quark.browser"),
                Arrays.asList(60, 40));
        byte[] tileJsonBytes = OpalCastProtocol.encodeDeviceState(dsTile);
        String tileJson = new String(tileJsonBytes, StandardCharsets.UTF_8);
        assertTrue(tileJson.contains("\"tilingSlots\""));
        assertTrue(tileJson.contains("\"tilingRatios\""));
        OpalCastProtocol.Frame dtf = roundTrip(OpalCastProtocol.TYPE_DEVICE_STATE, 0, 201L, tileJsonBytes);
        OpalCastProtocol.DeviceState dsTileDec = OpalCastClientTestCodec.decodeDeviceState(dtf.payload);
        assertEquals("SPLIT_2_H", dsTileDec.activeLayoutMode);
        assertEquals(Arrays.asList("com.tencent.mm", "com.quark.browser"), dsTileDec.tilingSlots);
        assertEquals(Arrays.asList(60, 40), dsTileDec.tilingRatios);

        // Legacy v1.0 DEVICE_STATE JSON defaults safely
        byte[] legacyDs = "{\"deviceName\":\"Legacy\",\"sinkType\":2}".getBytes(StandardCharsets.UTF_8);
        OpalCastProtocol.DeviceState legacyDsDec = OpalCastClientTestCodec.decodeDeviceState(legacyDs);
        assertEquals(1, legacyDsDec.injectStatus);
        assertTrue(legacyDsDec.adaptiveSingleEnabled);
        assertEquals(460, legacyDsDec.defaultSingleWidthDp);
        assertEquals("FULLSCREEN", legacyDsDec.activeLayoutMode);
        assertEquals("", legacyDsDec.topAppPackage);
        assertTrue(legacyDsDec.tilingSlots.isEmpty());
        assertTrue(legacyDsDec.tilingRatios.isEmpty());
    }

    @Test
    public void testV11WifiUpgradeReadyAppListAndWindowActions() throws Exception {
        // 0x29 REQUEST_WIFI_UPGRADE & 0x2A LIST_APPS empty payload
        assertEquals(0x29, OpalCastProtocol.TYPE_REQUEST_WIFI_UPGRADE);
        assertEquals(0x2A, OpalCastProtocol.TYPE_LIST_APPS);
        assertEquals(0, roundTrip(OpalCastProtocol.TYPE_REQUEST_WIFI_UPGRADE, 0, 300L, OpalCastProtocol.EMPTY_BYTES).payload.length);
        assertEquals(0, roundTrip(OpalCastProtocol.TYPE_LIST_APPS, 0, 301L, OpalCastProtocol.EMPTY_BYTES).payload.length);

        // 0x14 WIFI_UPGRADE_READY
        String fp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String tok = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        OpalCastProtocol.WifiUpgradeReadyMsg upg = new OpalCastProtocol.WifiUpgradeReadyMsg(
                true, "192.168.1.42", 19878, fp, tok, "");
        OpalCastProtocol.Frame uf = roundTrip(OpalCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, 302L,
                OpalCastProtocol.encodeWifiUpgradeReady(upg));
        OpalCastProtocol.WifiUpgradeReadyMsg upgDec = OpalCastClientTestCodec.decodeWifiUpgradeReady(uf.payload);
        assertTrue(upgDec.ok);
        assertEquals("192.168.1.42", upgDec.host);
        assertEquals(19878, upgDec.port);
        assertEquals(fp, upgDec.certFingerprint);
        assertEquals(tok, upgDec.authToken);
        assertEquals("", upgDec.message);

        // 0x15 APP_LIST (with and without base64 PNG icon)
        String sampleIconB64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        OpalCastProtocol.AppListMsg apps = new OpalCastProtocol.AppListMsg(Arrays.asList(
                new OpalCastProtocol.AppEntry("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", "微信", true, 123, sampleIconB64),
                new OpalCastProtocol.AppEntry("com.quark.browser", "com.ucpro.MainActivity", "夸克", false, -1)));
        byte[] appListPayload = OpalCastProtocol.encodeAppList(apps);
        String appListJson = new String(appListPayload, StandardCharsets.UTF_8);
        assertTrue("Non-empty icon must serialize as JSON field \"icon\"", appListJson.contains("\"icon\":\"" + sampleIconB64 + "\""));
        int iconOccurrences = appListJson.split("\"icon\"", -1).length - 1;
        assertEquals("Empty iconPngBase64 must omit \"icon\" field", 1, iconOccurrences);
        OpalCastProtocol.Frame af = roundTrip(OpalCastProtocol.TYPE_APP_LIST, 0, 303L, appListPayload);
        OpalCastProtocol.AppListMsg appsDec = OpalCastClientTestCodec.decodeAppList(af.payload);
        assertEquals(2, appsDec.apps.size());
        assertEquals("com.tencent.mm", appsDec.apps.get(0).pkg);
        assertEquals("微信", appsDec.apps.get(0).label);
        assertTrue(appsDec.apps.get(0).runningOnExt);
        assertEquals(123, appsDec.apps.get(0).taskId);
        assertEquals(sampleIconB64, appsDec.apps.get(0).iconPngBase64);
        assertFalse(appsDec.apps.get(1).runningOnExt);
        assertEquals(-1, appsDec.apps.get(1).taskId);
        assertEquals("", appsDec.apps.get(1).iconPngBase64);

        // 0x2B WINDOW_ACTION: set_tiling with nested pkgs & ratios arrays
        OpalCastProtocol.WindowActionMsg tiling = new OpalCastProtocol.WindowActionMsg(
                "set_tiling", false, false, 0, "", "", "", 0, false, -1, -1,
                0, 0, 0, 0, false, "SPLIT_3_MASTER",
                Arrays.asList("com.tencent.mm", "com.quark.browser", "com.ss.android.ugc.aweme"),
                Arrays.asList(55, 50), "");
        OpalCastProtocol.Frame wf = roundTrip(OpalCastProtocol.TYPE_WINDOW_ACTION, 0, 304L,
                OpalCastClientTestCodec.encodeWindowAction(tiling));
        OpalCastProtocol.WindowActionMsg tDec = OpalCastProtocol.decodeWindowAction(wf.payload);
        assertEquals("set_tiling", tDec.action);
        assertEquals("SPLIT_3_MASTER", tDec.topology);
        assertEquals(3, tDec.pkgs.size());
        assertEquals("com.quark.browser", tDec.pkgs.get(1));
        assertEquals(Arrays.asList(55, 50), tDec.ratios);

        // 0x2B WINDOW_ACTION: update_window with explicit bounds & remember
        OpalCastProtocol.WindowActionMsg upd = OpalCastProtocol.decodeWindowAction(
                ("{\"action\":\"update_window\",\"taskId\":45,\"pkg\":\"com.tencent.mm\",\"mode\":\"freeform\","
                        + "\"left\":100,\"top\":80,\"right\":700,\"bottom\":1000,\"remember\":true}")
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("update_window", upd.action);
        assertEquals(45, upd.taskId);
        assertEquals("com.tencent.mm", upd.pkg);
        assertEquals("freeform", upd.mode);
        assertTrue(upd.hasBounds);
        assertEquals(100, upd.left);
        assertEquals(80, upd.top);
        assertEquals(700, upd.right);
        assertEquals(1000, upd.bottom);
        assertTrue(upd.remember);

        // 0x2B WINDOW_ACTION: set_input_backend, reload_app_dpi, return_to_desktop, set_single_width
        OpalCastProtocol.WindowActionMsg backend = OpalCastProtocol.decodeWindowAction(
                "{\"action\":\"set_input_backend\",\"backend\":\"uhid\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("set_input_backend", backend.action);
        assertEquals("uhid", backend.backend);

        OpalCastProtocol.WindowActionMsg sw = OpalCastProtocol.decodeWindowAction(
                "{\"action\":\"set_single_width\",\"pkg\":\"com.tencent.mm\",\"widthPct\":42,\"remember\":true}"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("set_single_width", sw.action);
        assertEquals(42, sw.widthPct);
        assertTrue(sw.remember);

        OpalCastProtocol.WindowActionMsg ret = new OpalCastProtocol.WindowActionMsg(
                "return_to_desktop", false, false, 0, "com.tencent.mm", "", "", 0, false, -1, 88,
                0, 0, 0, 0, false, "", Collections.emptyList(), Collections.emptyList(), "");
        OpalCastProtocol.WindowActionMsg retDec = OpalCastProtocol.decodeWindowAction(
                OpalCastClientTestCodec.encodeWindowAction(ret));
        assertEquals("return_to_desktop", retDec.action);
        assertEquals("com.tencent.mm", retDec.pkg);
        assertEquals(88, retDec.taskId);
    }

    @Test
    public void testJsonDecodeFailurePreservesCause() {
        try {
            OpalCastProtocol.decodeHello("{bad json".getBytes(StandardCharsets.UTF_8));
            fail("Expected ProtocolException");
        } catch (OpalCastProtocol.ProtocolException expected) {
            assertNotNull("JSON decode failure must preserve cause", expected.getCause());
        }
        try {
            OpalCastProtocol.decodeWindowAction("{bad json".getBytes(StandardCharsets.UTF_8));
            fail("Expected ProtocolException");
        } catch (OpalCastProtocol.ProtocolException expected) {
            assertNotNull("WINDOW_ACTION JSON failure must preserve cause", expected.getCause());
        }
    }

    @Test
    public void testPngClipboardFramesAnd8MiBCap() throws Exception {
        byte[] fakePng = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
        assertTrue(OpalCastProtocol.isValidPngHeader(fakePng));
        assertFalse(OpalCastProtocol.isValidPngHeader(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));

        // Downlink 0x11 CLIPBOARD_PUSH with FLAG_CLIPBOARD_IMAGE_PNG (0x01)
        byte[] pushEnc = OpalCastProtocol.encodeClipboardImagePush(777L, fakePng);
        assertEquals(8 + fakePng.length, pushEnc.length);
        OpalCastProtocol.Frame pf = roundTrip(
                OpalCastProtocol.TYPE_CLIPBOARD_PUSH, OpalCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, 400L, pushEnc);
        assertEquals(OpalCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, pf.flags);
        OpalCastClientTestCodec.ClipboardImagePush pushDec = OpalCastClientTestCodec.decodeClipboardImagePush(pf.payload);
        assertEquals(777L, pushDec.seq);
        assertArrayEquals(fakePng, pushDec.pngData);

        // Uplink 0x26 INPUT_TEXT with FLAG_CLIPBOARD_IMAGE_PNG (0x01)
        OpalCastProtocol.ImageClipboardEventMsg imgEv = new OpalCastProtocol.ImageClipboardEventMsg(
                OpalCastProtocol.TEXT_MODE_COMMIT_PASTE, 888L, fakePng);
        byte[] imgEnc = OpalCastClientTestCodec.encodeImageClipboardEvent(imgEv);
        assertEquals(9 + fakePng.length, imgEnc.length);
        OpalCastProtocol.Frame inf = roundTrip(
                OpalCastProtocol.TYPE_INPUT_TEXT, OpalCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, 401L, imgEnc);
        assertEquals(OpalCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, inf.flags);
        OpalCastProtocol.ImageClipboardEventMsg imgDec = OpalCastProtocol.decodeImageClipboardEvent(inf.payload);
        assertEquals(OpalCastProtocol.TEXT_MODE_COMMIT_PASTE, imgDec.mode);
        assertEquals(888L, imgDec.seq);
        assertArrayEquals(fakePng, imgDec.pngData);

        // Oversized (> 8 MiB) PNG rejected on both encode and decode
        byte[] oversized = new byte[OpalCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES + 1];
        try {
            OpalCastProtocol.encodeClipboardImagePush(1L, oversized);
            fail("Expected ProtocolException for >8 MiB clipboard push PNG");
        } catch (OpalCastProtocol.ProtocolException expected) {
            assertTrue(expected.getMessage().contains("8 MiB"));
        }
        try {
            OpalCastClientTestCodec.encodeImageClipboardEvent(new OpalCastProtocol.ImageClipboardEventMsg(1, 2L, oversized));
            fail("Expected ProtocolException for >8 MiB uplink clipboard PNG");
        } catch (OpalCastProtocol.ProtocolException expected) {
            assertTrue(expected.getMessage().contains("8 MiB"));
        }
    }

    @Test
    public void testRemoteMicProtocolFramesAndInjectorHelpers() throws Exception {
        assertEquals(0x17, OpalCastProtocol.TYPE_MIC_STATE);
        assertEquals(0x2C, OpalCastProtocol.TYPE_MIC_CONFIG);
        assertEquals(0x2D, OpalCastProtocol.TYPE_MIC_FRAME);

        OpalCastProtocol.MicConfigMsg cfg = new OpalCastProtocol.MicConfigMsg(true, "pcm16", 16_000, 1);
        OpalCastProtocol.Frame cf = roundTrip(
                OpalCastProtocol.TYPE_MIC_CONFIG, 0, 500L, OpalCastProtocol.encodeMicConfig(cfg));
        OpalCastProtocol.MicConfigMsg cfgDec = OpalCastProtocol.decodeMicConfig(cf.payload);
        assertTrue(cfgDec.enabled);
        assertEquals("pcm16", cfgDec.codec);
        assertEquals(16_000, cfgDec.sampleRate);
        assertEquals(1, cfgDec.channels);

        OpalCastProtocol.MicStateMsg st = new OpalCastProtocol.MicStateMsg(
                true, true, 16_000, 1, "远端麦克风已启用");
        OpalCastProtocol.Frame sf = roundTrip(
                OpalCastProtocol.TYPE_MIC_STATE, 0, 501L, OpalCastProtocol.encodeMicState(st));
        OpalCastProtocol.MicStateMsg stDec = OpalCastProtocol.decodeMicState(sf.payload);
        assertTrue(stDec.active);
        assertTrue(stDec.supported);
        assertEquals(16_000, stDec.sampleRate);
        assertEquals(1, stDec.channels);
        assertEquals("远端麦克风已启用", stDec.message);

        byte[] pcm = new byte[640];
        pcm[0] = 0x12;
        pcm[639] = 0x34;
        OpalCastProtocol.Frame mf = roundTrip(OpalCastProtocol.TYPE_MIC_FRAME, 0, 502L, pcm);
        assertArrayEquals(pcm, mf.payload);

        assertFalse(RemoteMicInjector.isSupportedSdkInt(32));
        assertTrue(RemoteMicInjector.isSupportedSdkInt(33));
        assertEquals(16_000, RemoteMicInjector.clampSampleRate(0));
        assertEquals(8_000, RemoteMicInjector.clampSampleRate(4_000));
        assertEquals(48_000, RemoteMicInjector.clampSampleRate(96_000));
        assertEquals(1, RemoteMicInjector.clampChannels(0));
        assertEquals(2, RemoteMicInjector.clampChannels(6));
        assertEquals(4096, RemoteMicInjector.computeTrackBufferBytes(16_000, 1, 1024));
        assertEquals(8192, RemoteMicInjector.computeTrackBufferBytes(48_000, 2, 4096));
        assertEquals(7680, RemoteMicInjector.computeTrackBufferBytes(48_000, 2, 0));
        assertEquals("", RemoteMicInjector.formatMixAddressTag(null));
        assertEquals("", RemoteMicInjector.formatMixAddressTag("  "));
        assertEquals("addr=123:ap:1mixr:0", RemoteMicInjector.formatMixAddressTag("123:ap:1mixr:0"));
        assertEquals("addr=123:ap:1mixr:0", RemoteMicInjector.formatMixAddressTag("addr=123:ap:1mixr:0"));
    }

    @Test
    public void testRootCameraProtocolFramesAndRootGate() throws Exception {
        assertEquals(0x18, OpalCastProtocol.TYPE_CAMERA_STATE);
        assertEquals(0x2E, OpalCastProtocol.TYPE_CAMERA_CONFIG);
        assertEquals(0x30, OpalCastProtocol.TYPE_CAMERA_FRAME);

        OpalCastProtocol.CameraConfigMsg cfg = new OpalCastProtocol.CameraConfigMsg(
                true, "mjpeg", 640, 480, 15, "front");
        OpalCastProtocol.Frame cf = roundTrip(
                OpalCastProtocol.TYPE_CAMERA_CONFIG, 0, 600L, OpalCastProtocol.encodeCameraConfig(cfg));
        OpalCastProtocol.CameraConfigMsg cfgDec = OpalCastProtocol.decodeCameraConfig(cf.payload);
        assertTrue(cfgDec.enabled);
        assertEquals("mjpeg", cfgDec.codec);
        assertEquals(640, cfgDec.width);
        assertEquals(480, cfgDec.height);
        assertEquals(15, cfgDec.fps);
        assertEquals("front", cfgDec.facing);

        OpalCastProtocol.CameraStateMsg st = new OpalCastProtocol.CameraStateMsg(
                false, false, true, 640, 480, 15, "", "摄像头映射需要 Root 权限", "back");
        OpalCastProtocol.Frame sf = roundTrip(
                OpalCastProtocol.TYPE_CAMERA_STATE, 0, 601L, OpalCastProtocol.encodeCameraState(st));
        OpalCastProtocol.CameraStateMsg stDec = OpalCastProtocol.decodeCameraState(sf.payload);
        assertFalse(stDec.active);
        assertFalse(stDec.supported);
        assertTrue(stDec.rootRequired);
        assertEquals("摄像头映射需要 Root 权限", stDec.message);
        assertEquals("back", stDec.facing);

        byte[] fakeJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        assertTrue(OpalCastProtocol.isValidJpegHeader(fakeJpeg));
        assertFalse(OpalCastProtocol.isValidJpegHeader(new byte[]{1, 2, 3, 4}));
        OpalCastProtocol.Frame jf = roundTrip(OpalCastProtocol.TYPE_CAMERA_FRAME, 0, 602L, fakeJpeg);
        assertArrayEquals(fakeJpeg, jf.payload);

        // Verify strict Root gate: non-root start must fail closed with rootRequired=true
        RootCameraBridge bridge = new RootCameraBridge(tmpDir.newFolder("cam"));
        RootCameraBridge.StartResult denied = bridge.start(false, 640, 480, 15, "front");
        assertFalse(denied.active);
        assertFalse(denied.supported);
        assertTrue(denied.rootRequired);
        assertTrue(denied.message.contains("Root"));
        assertTrue(RootCameraBridge.buildMetaJson(true, 640, 480, 15, "front", 7L).contains("@opaldesk_cam_feed"));

        // Verify AOSP VirtualCamera pure-Java helpers
        assertFalse(VirtualCameraPolicy.isSupportedSdkInt(34));
        assertTrue(VirtualCameraPolicy.isSupportedSdkInt(35));
        String cdmList = "Max ID: 4\nAssociation ID | Package Name | Mac Address\n"
                + "2 | com.other.app | 11:22:33:44:55:66\n"
                + "4 | com.android.shell | 02:4f:50:41:4c:01\n";
        assertEquals(4, VirtualCameraPolicy.parseAssociationId(cdmList, "com.android.shell", "02:4f:50:41:4c:01"));
        assertEquals(-1, VirtualCameraPolicy.parseAssociationId("", "com.android.shell", "02:4f:50:41:4c:01"));
        assertArrayEquals(new int[]{0, 0, 640, 480}, VirtualCameraPolicy.computeCenterCropSrcRect(640, 480, 640, 480));
        assertArrayEquals(new int[]{160, 0, 1120, 720}, VirtualCameraPolicy.computeCenterCropSrcRect(1280, 720, 640, 480));
        assertArrayEquals(new int[]{0, 60, 640, 420}, VirtualCameraPolicy.computeCenterCropSrcRect(640, 480, 1280, 720));
        assertEquals(270, VirtualCameraPolicy.computePreRotationCwDegrees(VirtualCameraPolicy.BACK_SENSOR_ORIENTATION));
        assertEquals(90, VirtualCameraPolicy.computePreRotationCwDegrees(VirtualCameraPolicy.FRONT_SENSOR_ORIENTATION));
        assertEquals(0, VirtualCameraPolicy.computePreRotationCwDegrees(0));
        assertArrayEquals(new int[]{140, 0, 500, 480},
                VirtualCameraPolicy.computeRotatedCenterCropSrcRect(640, 480, 640, 480, 270));
        assertArrayEquals(new int[]{0, 0, 480, 640},
                VirtualCameraPolicy.computeRotatedCenterCropSrcRect(480, 640, 640, 480, 90));
        assertEquals("back", VirtualCameraPolicy.normalizeFacing("environment"));
        assertEquals("front", VirtualCameraPolicy.normalizeFacing("user"));
        assertEquals(30, VirtualCameraPolicy.resolveVirtualCameraStreamFps(15));
        assertEquals(30, VirtualCameraPolicy.resolveVirtualCameraStreamFps(0));
        assertEquals(30, VirtualCameraPolicy.resolveVirtualCameraStreamFps(30));
        assertEquals(60, VirtualCameraPolicy.resolveVirtualCameraStreamFps(120));
    }

    @Test
    public void testDaemonConfigRemoteMediaDefaultsOffAndPersists() throws Exception {
        File conf = new File(tmpDir.getRoot(), "opaldesk_v2.conf");
        File prof = new File(tmpDir.getRoot(), "opaldesk_profiles.conf");
        DaemonConfig fresh = new DaemonConfig(conf, prof);
        assertFalse(fresh.isRemoteMicEnabled());
        assertFalse(fresh.isRemoteCameraEnabled());

        fresh.setRemoteMediaConfig(true, false);
        assertTrue(fresh.isRemoteMicEnabled());
        assertFalse(fresh.isRemoteCameraEnabled());

        DaemonConfig reloaded = new DaemonConfig(conf, prof);
        reloaded.loadAll();
        assertTrue(reloaded.isRemoteMicEnabled());
        assertFalse(reloaded.isRemoteCameraEnabled());

        // Non-root daemon must fail closed and reject enabling remote camera mapping
        assertFalse(reloaded.setRemoteMediaConfig(false, true));
        assertFalse(reloaded.isRemoteCameraEnabled());
        DaemonConfig reloadedNonRoot = new DaemonConfig(conf, prof);
        reloadedNonRoot.loadAll();
        assertFalse(reloadedNonRoot.isRemoteMicEnabled());
        assertFalse(reloadedNonRoot.isRemoteCameraEnabled());

        // Once Root-enhanced, remote camera mapping can be enabled and persisted
        reloaded.markRootEnhanced(true, true);
        assertTrue(reloaded.setRemoteMediaConfig(false, true));
        assertTrue(reloaded.isRemoteCameraEnabled());

        DaemonConfig reloadedWithRoot = new DaemonConfig(conf, prof);
        reloadedWithRoot.loadAll();
        // Before Root is confirmed on the new instance, camera remains fail-closed
        assertFalse(reloadedWithRoot.isRemoteCameraEnabled());
        reloadedWithRoot.markRootEnhanced(true, true);
        assertTrue(reloadedWithRoot.isRemoteCameraEnabled());

        // Switching back to non-root immediately disables remote camera mapping
        reloadedWithRoot.markRootEnhanced(false, true);
        assertFalse(reloadedWithRoot.isRemoteCameraEnabled());
    }

    @Test
    public void testAudioRoutingPolicyAndDaemonConfigPersistence() throws Exception {
        assertEquals(
                Collections.singletonList(AudioRoutingPolicy.PLACEHOLDER_EMPTY_UID),
                AudioRoutingPolicy.normalizeTargetUids(null));
        assertEquals(
                Collections.singletonList(AudioRoutingPolicy.PLACEHOLDER_EMPTY_UID),
                AudioRoutingPolicy.normalizeTargetUids(Collections.emptyList()));
        assertEquals(
                Collections.singletonList(AudioRoutingPolicy.PLACEHOLDER_EMPTY_UID),
                AudioRoutingPolicy.normalizeTargetUids(Arrays.asList(0, 1000, 2000, 9999)));
        assertEquals(
                Arrays.asList(10200, 10350),
                AudioRoutingPolicy.normalizeTargetUids(Arrays.asList(10350, 2000, 10200, 10350, -1, null)));
        assertTrue(AudioRoutingPolicy.uidListsEqual(Arrays.asList(10200, 10350), Arrays.asList(10200, 10350)));
        assertFalse(AudioRoutingPolicy.uidListsEqual(Arrays.asList(10200), Arrays.asList(10200, 10350)));

        File conf = new File(tmpDir.getRoot(), "opaldesk_audio_v2.conf");
        File prof = new File(tmpDir.getRoot(), "opaldesk_audio_profiles.conf");
        DaemonConfig fresh = new DaemonConfig(conf, prof);
        assertEquals(BridgeProtocol.AUDIO_ROUTE_ALL_TO_DEX, fresh.getAudioRouteMode());

        fresh.setAudioRouteMode(BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP);
        assertEquals(BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP, fresh.getAudioRouteMode());

        DaemonConfig reloaded = new DaemonConfig(conf, prof);
        reloaded.loadAll();
        assertEquals(BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP, reloaded.getAudioRouteMode());

        // Unknown values normalize safely to ALL_TO_DEX
        reloaded.setAudioRouteMode(99);
        assertEquals(BridgeProtocol.AUDIO_ROUTE_ALL_TO_DEX, reloaded.getAudioRouteMode());

        // Legacy 11-field config file without audioRouteMode defaults to ALL_TO_DEX
        File legacyConf = new File(tmpDir.getRoot(), "legacy_11_fields.conf");
        java.nio.file.Files.write(
                legacyConf.toPath(),
                "0,6,1920,1200,240,0,100,1,0,0,0\n".getBytes(StandardCharsets.UTF_8));
        DaemonConfig legacy = new DaemonConfig(legacyConf, prof);
        legacy.loadAll();
        assertEquals(BridgeProtocol.AUDIO_ROUTE_ALL_TO_DEX, legacy.getAudioRouteMode());
    }
}
