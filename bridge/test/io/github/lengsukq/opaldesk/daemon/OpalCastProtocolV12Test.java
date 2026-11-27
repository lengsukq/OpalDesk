package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;
import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

public class OpalCastProtocolV12Test {

    private OpalCastProtocol.Frame roundTrip(int type, int flags, long tsUs, byte[] payload) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OpalCastProtocol.writeFrame(baos, type, flags, tsUs, payload, 0, payload.length);
        byte[] wire = baos.toByteArray();
        assertEquals(OpalCastProtocol.HEADER_SIZE + payload.length, wire.length);
        assertEquals(type & 0xFF, wire[4] & 0xFF);
        ByteArrayInputStream bais = new ByteArrayInputStream(wire);
        OpalCastProtocol.Frame f = OpalCastProtocol.readFrame(bais);
        assertNotNull(f);
        assertEquals(type, f.type);
        assertEquals(flags, f.flags);
        assertEquals(tsUs, f.timestampUs);
        assertArrayEquals(payload, f.payload);
        assertNull(OpalCastProtocol.readFrame(bais));
        return f;
    }

    @Test
    public void testCompanionRoleAndFrameTypeConstants() throws Exception {
        assertEquals("companion", OpalCastProtocol.SESSION_ROLE_COMPANION);
        assertEquals(0x19, OpalCastProtocol.TYPE_NOTIFICATION_EVENT);
        assertEquals(0x1A, OpalCastProtocol.TYPE_MEDIA_STATE);
        assertEquals(0x1B, OpalCastProtocol.TYPE_FILE_OP_RESPONSE);
        assertEquals(0x1C, OpalCastProtocol.TYPE_FILE_PULL_CHUNK);
        assertEquals(0x1D, OpalCastProtocol.TYPE_APK_INSTALL_STATUS);
        assertEquals(0x31, OpalCastProtocol.TYPE_NOTIFICATION_ACTION);
        assertEquals(0x32, OpalCastProtocol.TYPE_MEDIA_ACTION);
        assertEquals(0x33, OpalCastProtocol.TYPE_FILE_OP_REQUEST);
        assertEquals(0x34, OpalCastProtocol.TYPE_APK_INSTALL_REQUEST);

        String helloJson = "{\"protocolVersion\":1,\"clientName\":\"MacBook\",\"clientId\":\"mac-1\","
                + "\"sessionRole\":\"companion\",\"transportHint\":\"wifi_tls\",\"clientPlatform\":\"macos\"}";
        OpalCastProtocol.HelloMsg hello = OpalCastProtocol.decodeHello(helloJson.getBytes(StandardCharsets.UTF_8));
        assertTrue(hello.isCompanion());
        assertFalse(hello.isStandaloneApp());
        assertFalse(hello.isAppCatalog());
        assertEquals("companion", hello.sessionRole);
    }

    @Test
    public void testDeviceStateHostTopAppBackwardCompatFields() throws Exception {
        OpalCastProtocol.DeviceState ds = new OpalCastProtocol.DeviceState(
                "Pixel 9", DisplaySink.SINK_REMOTE_WIFI, 0,
                0, 0, 0, 0.0, 0, 0, 88, false, false,
                1, false, 460, "COMPANION", "", "",
                Collections.emptyList(), Collections.emptyList(), "",
                "com.tencent.mm", "微信");
        byte[] payload = OpalCastProtocol.encodeDeviceState(ds);
        OpalCastProtocol.Frame f = roundTrip(OpalCastProtocol.TYPE_DEVICE_STATE, 0, 100L, payload);
        SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(f.payload, StandardCharsets.UTF_8));
        assertEquals("com.tencent.mm", obj.getString("hostTopAppPackage", ""));
        assertEquals("微信", obj.getString("hostTopAppLabel", ""));
        assertEquals("COMPANION", obj.getString("activeLayoutMode", ""));
    }

    @Test
    public void testNotificationEventAndActionRoundTrip() throws Exception {
        OpalCastProtocol.NotificationItem item = new OpalCastProtocol.NotificationItem(
                "0|com.tencent.mm|1|tag|10200",
                "com.tencent.mm",
                "微信",
                "张三",
                "验证码 482910",
                1730000000000L,
                true,
                true,
                "回复",
                "482910");
        OpalCastProtocol.NotificationEventMsg posted = new OpalCastProtocol.NotificationEventMsg(
                "posted", "", Collections.singletonList(item));
        OpalCastProtocol.Frame f1 = roundTrip(
                OpalCastProtocol.TYPE_NOTIFICATION_EVENT, 0, 1001L,
                OpalCastProtocol.encodeNotificationEvent(posted));
        OpalCastProtocol.NotificationEventMsg dec1 = OpalCastProtocol.decodeNotificationEvent(f1.payload);
        assertEquals("posted", dec1.action);
        assertEquals(1, dec1.items.size());
        assertEquals("0|com.tencent.mm|1|tag|10200", dec1.items.get(0).key);
        assertEquals("com.tencent.mm", dec1.items.get(0).pkg);
        assertEquals("微信", dec1.items.get(0).appLabel);
        assertEquals("张三", dec1.items.get(0).title);
        assertEquals("验证码 482910", dec1.items.get(0).text);
        assertEquals(1730000000000L, dec1.items.get(0).postTimeMs);
        assertTrue(dec1.items.get(0).clearable);
        assertTrue(dec1.items.get(0).canReply);
        assertEquals("回复", dec1.items.get(0).replyLabel);
        assertEquals("482910", dec1.items.get(0).otpCode);

        OpalCastProtocol.NotificationEventMsg removed = new OpalCastProtocol.NotificationEventMsg(
                "removed", "0|com.tencent.mm|1|tag|10200", Collections.emptyList());
        OpalCastProtocol.Frame f2 = roundTrip(
                OpalCastProtocol.TYPE_NOTIFICATION_EVENT, 0, 1002L,
                OpalCastProtocol.encodeNotificationEvent(removed));
        OpalCastProtocol.NotificationEventMsg dec2 = OpalCastProtocol.decodeNotificationEvent(f2.payload);
        assertEquals("removed", dec2.action);
        assertEquals("0|com.tencent.mm|1|tag|10200", dec2.key);
        assertTrue(dec2.items.isEmpty());

        OpalCastProtocol.NotificationActionMsg act = new OpalCastProtocol.NotificationActionMsg(
                "reply", "0|com.tencent.mm|1|tag|10200", "收到，马上回复！");
        OpalCastProtocol.Frame f3 = roundTrip(
                OpalCastProtocol.TYPE_NOTIFICATION_ACTION, 0, 1003L,
                OpalCastProtocol.encodeNotificationAction(act));
        OpalCastProtocol.NotificationActionMsg actDec = OpalCastProtocol.decodeNotificationAction(f3.payload);
        assertEquals("reply", actDec.op);
        assertEquals("0|com.tencent.mm|1|tag|10200", actDec.key);
        assertEquals("收到，马上回复！", actDec.replyText);
    }

    @Test
    public void testMediaStateAndActionRoundTrip() throws Exception {
        OpalCastProtocol.MediaStateMsg state = new OpalCastProtocol.MediaStateMsg(
                true, "com.netease.cloudmusic", "网易云音乐",
                "晴天", "周杰伦", "叶惠美",
                true, 45_000L, 269_000L, "AAAA");
        OpalCastProtocol.Frame f1 = roundTrip(
                OpalCastProtocol.TYPE_MEDIA_STATE, 0, 2001L,
                OpalCastProtocol.encodeMediaState(state));
        OpalCastProtocol.MediaStateMsg dec = OpalCastProtocol.decodeMediaState(f1.payload);
        assertTrue(dec.active);
        assertEquals("com.netease.cloudmusic", dec.pkg);
        assertEquals("网易云音乐", dec.appLabel);
        assertEquals("晴天", dec.title);
        assertEquals("周杰伦", dec.artist);
        assertEquals("叶惠美", dec.album);
        assertTrue(dec.playing);
        assertEquals(45_000L, dec.positionMs);
        assertEquals(269_000L, dec.durationMs);
        assertEquals("AAAA", dec.artworkBase64);

        OpalCastProtocol.MediaActionMsg act = new OpalCastProtocol.MediaActionMsg("seek", 120_500L);
        OpalCastProtocol.Frame f2 = roundTrip(
                OpalCastProtocol.TYPE_MEDIA_ACTION, 0, 2002L,
                OpalCastProtocol.encodeMediaAction(act));
        OpalCastProtocol.MediaActionMsg actDec = OpalCastProtocol.decodeMediaAction(f2.payload);
        assertEquals("seek", actDec.op);
        assertEquals(120_500L, actDec.positionMs);
    }

    @Test
    public void testFileOpRequestResponseAndPullChunkWireLayout() throws Exception {
        OpalCastProtocol.FileOpRequestMsg req = new OpalCastProtocol.FileOpRequestMsg(
                42, "rename", "/sdcard/a.txt", "/sdcard/b.txt", 77, 6);
        OpalCastProtocol.Frame rf = roundTrip(
                OpalCastProtocol.TYPE_FILE_OP_REQUEST, 0, 3001L,
                OpalCastProtocol.encodeFileOpRequest(req));
        OpalCastProtocol.FileOpRequestMsg reqDec = OpalCastProtocol.decodeFileOpRequest(rf.payload);
        assertEquals(42, reqDec.requestId);
        assertEquals("rename", reqDec.op);
        assertEquals("/sdcard/a.txt", reqDec.path);
        assertEquals("/sdcard/b.txt", reqDec.newPath);
        assertEquals(77, reqDec.transferId);
        assertEquals(6, reqDec.maxDepth);

        OpalCastProtocol.StorageReport report = new OpalCastProtocol.StorageReport(
                128_000_000_000L, 48_000_000_000L, 80_000_000_000L,
                Arrays.asList(
                        new OpalCastProtocol.StorageCategory("images", 12_000_000L, 120),
                        new OpalCastProtocol.StorageCategory("videos", 50_000_000L, 10)),
                Collections.singletonList(
                        new OpalCastProtocol.LargeFileEntry("movie.mp4", "/sdcard/Movies/movie.mp4", 50_000_000L, 1700000L)));
        OpalCastProtocol.FileOpResponseMsg resp = new OpalCastProtocol.FileOpResponseMsg(
                42, "storage_scan", true, "", "/sdcard",
                Collections.singletonList(new OpalCastProtocol.FileEntry(
                        "DCIM", "/sdcard/DCIM", true, 0L, 1700000L, "inode/directory")),
                report, 80_000_000_000L, "thumbBase64");
        OpalCastProtocol.Frame respf = roundTrip(
                OpalCastProtocol.TYPE_FILE_OP_RESPONSE, 0, 3002L,
                OpalCastProtocol.encodeFileOpResponse(resp));
        OpalCastProtocol.FileOpResponseMsg respDec = OpalCastProtocol.decodeFileOpResponse(respf.payload);
        assertEquals(42, respDec.requestId);
        assertEquals("storage_scan", respDec.op);
        assertTrue(respDec.ok);
        assertEquals("/sdcard", respDec.path);
        assertEquals(1, respDec.entries.size());
        assertEquals("DCIM", respDec.entries.get(0).name);
        assertTrue(respDec.entries.get(0).isDir);
        assertNotNull(respDec.storage);
        assertEquals(128_000_000_000L, respDec.storage.totalBytes);
        assertEquals(2, respDec.storage.categories.size());
        assertEquals(1, respDec.storage.largeFiles.size());
        assertEquals("movie.mp4", respDec.storage.largeFiles.get(0).name);
        assertEquals("thumbBase64", respDec.thumbnailBase64);

        // Verify exact 0x1C binary wire layout: [4B transferId][1B flags][8B totalSize][2B nameLen][UTF-8 name][data]
        byte[] chunkData = new byte[]{0x10, 0x20, 0x30, 0x40};
        String remoteName = "测试.bin";
        byte[] nameUtf8 = remoteName.getBytes(StandardCharsets.UTF_8);
        byte[] pullPayload = OpalCastProtocol.encodeFilePullChunk(
                new OpalCastProtocol.FilePullChunkMsg(
                        99,
                        OpalCastProtocol.FILE_FLAG_START | OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END,
                        4096L,
                        remoteName,
                        chunkData));
        assertEquals(15 + nameUtf8.length + chunkData.length, pullPayload.length);
        ByteBuffer bb = ByteBuffer.wrap(pullPayload).order(ByteOrder.BIG_ENDIAN);
        assertEquals(99, bb.getInt());
        assertEquals(0x07, bb.get() & 0xFF);
        assertEquals(4096L, bb.getLong());
        assertEquals(nameUtf8.length, bb.getShort() & 0xFFFF);

        OpalCastProtocol.Frame pf = roundTrip(OpalCastProtocol.TYPE_FILE_PULL_CHUNK, 0, 3003L, pullPayload);
        OpalCastProtocol.FilePullChunkMsg pDec = OpalCastProtocol.decodeFilePullChunk(pf.payload);
        assertEquals(99, pDec.transferId);
        assertEquals(0x07, pDec.chunkFlags);
        assertEquals(4096L, pDec.totalSize);
        assertEquals(remoteName, pDec.nameOrPath);
        assertArrayEquals(chunkData, pDec.data);
    }

    @Test
    public void testApkInstallRequestAndStatusRoundTrip() throws Exception {
        OpalCastProtocol.ApkInstallRequestMsg req = new OpalCastProtocol.ApkInstallRequestMsg(
                5, "/sdcard/Download/OpalDesk/demo.xapk", "xapk", true, true);
        OpalCastProtocol.Frame f1 = roundTrip(
                OpalCastProtocol.TYPE_APK_INSTALL_REQUEST, 0, 4001L,
                OpalCastProtocol.encodeApkInstallRequest(req));
        OpalCastProtocol.ApkInstallRequestMsg decReq = OpalCastProtocol.decodeApkInstallRequest(f1.payload);
        assertEquals(5, decReq.installId);
        assertEquals("/sdcard/Download/OpalDesk/demo.xapk", decReq.filePath);
        assertEquals("xapk", decReq.format);
        assertTrue(decReq.replaceExisting);
        assertTrue(decReq.allowDowngrade);

        OpalCastProtocol.ApkInstallStatusMsg st = new OpalCastProtocol.ApkInstallStatusMsg(
                5, "installing", 78, "com.example.demo", "Writing split 2/3");
        OpalCastProtocol.Frame f2 = roundTrip(
                OpalCastProtocol.TYPE_APK_INSTALL_STATUS, 0, 4002L,
                OpalCastProtocol.encodeApkInstallStatus(st));
        OpalCastProtocol.ApkInstallStatusMsg decSt = OpalCastProtocol.decodeApkInstallStatus(f2.payload);
        assertEquals(5, decSt.installId);
        assertEquals("installing", decSt.stage);
        assertEquals(78, decSt.progress);
        assertEquals("com.example.demo", decSt.packageName);
        assertEquals("Writing split 2/3", decSt.message);

        try {
            OpalCastProtocol.decodeApkInstallRequest("{\"installId\":1}".getBytes(StandardCharsets.UTF_8));
            fail("Missing filePath must throw ProtocolException");
        } catch (OpalCastProtocol.ProtocolException expected) {
            assertTrue(expected.getMessage().contains("filePath"));
        }
    }
}
