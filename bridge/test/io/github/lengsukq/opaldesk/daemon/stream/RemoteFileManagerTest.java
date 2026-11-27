package io.github.lengsukq.opaldesk.daemon.stream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RemoteFileManagerTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testPathConfinementBlocksTraversalAbsoluteAndSymlinkEscapes() throws Exception {
        File storageRoot = tmpDir.newFolder("sdcard");
        File outsideFile = tmpDir.newFile("secret.txt");
        Files.write(outsideFile.toPath(), "TOP_SECRET".getBytes(StandardCharsets.UTF_8));

        File insideDir = new File(storageRoot, "Documents");
        assertTrue(insideDir.mkdirs());
        File symlinkEscape = new File(insideDir, "evil_link.txt");
        Files.createSymbolicLink(symlinkEscape.toPath(), outsideFile.toPath());

        File symlinkDirEscape = new File(storageRoot, "evil_dir");
        Files.createSymbolicLink(symlinkDirEscape.toPath(), outsideFile.getParentFile().toPath());

        RemoteFileManager fm = new RemoteFileManager(storageRoot, "/sdcard", (f, max, cap) -> "");

        // Valid paths resolve inside root
        assertEquals(storageRoot.getCanonicalFile(), fm.resolveSafePath(""));
        assertEquals(storageRoot.getCanonicalFile(), fm.resolveSafePath("/"));
        assertEquals(storageRoot.getCanonicalFile(), fm.resolveSafePath("/sdcard"));
        assertEquals(insideDir.getCanonicalFile(), fm.resolveSafePath("/sdcard/Documents"));

        // Direct traversal & disallowed absolute paths rejected
        for (String bad : new String[]{
                "../secret.txt",
                "/sdcard/../secret.txt",
                "/sdcard/Documents/../../secret.txt",
                "/etc/passwd",
                "/data/local/tmp/foo",
                "Documents/\0bad.txt",
                "/sdcard/Documents/evil_link.txt",
                "/sdcard/evil_dir/secret.txt"
        }) {
            try {
                fm.resolveSafePath(bad);
                fail("Expected SecurityException for path: " + bad);
            } catch (SecurityException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }

        // Listing Documents silently skips symlink escaping root
        OpalCastProtocol.FileOpResponseMsg listResp = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(1, "list", "/sdcard/Documents", "", 0, 0),
                null, null, () -> false);
        assertTrue(listResp.ok);
        assertTrue("Escaping symlink must not appear in directory listing", listResp.entries.isEmpty());
    }

    @Test
    public void testListStatMkdirRenameDeleteAndThumbnail() throws Exception {
        File storageRoot = tmpDir.newFolder("sdcard_ops");
        RemoteFileManager fm = new RemoteFileManager(
                storageRoot,
                "/sdcard",
                (imageFile, maxEdgePx, maxBase64Bytes) -> "FAKE_THUMB_" + imageFile.getName());

        // mkdir
        OpalCastProtocol.FileOpResponseMsg mk = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(1, "mkdir", "/sdcard/Work/Sub", "", 0, 0),
                null, null, () -> false);
        assertTrue(mk.ok);
        assertEquals("/sdcard/Work/Sub", mk.path);
        assertTrue(new File(storageRoot, "Work/Sub").isDirectory());

        // Create files & test list ordering (dirs first, then alpha)
        File pic = new File(storageRoot, "Work/photo.jpg");
        Files.write(pic.toPath(), new byte[]{1, 2, 3, 4});
        File doc = new File(storageRoot, "Work/alpha.txt");
        Files.write(doc.toPath(), "hello".getBytes(StandardCharsets.UTF_8));

        OpalCastProtocol.FileOpResponseMsg list = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(2, "list", "/sdcard/Work", "", 0, 0),
                null, null, () -> false);
        assertTrue(list.ok);
        assertEquals(3, list.entries.size());
        assertEquals("Sub", list.entries.get(0).name);
        assertTrue(list.entries.get(0).isDir);
        assertEquals("alpha.txt", list.entries.get(1).name);
        assertEquals("photo.jpg", list.entries.get(2).name);
        assertEquals("image/jpeg", list.entries.get(2).mime);

        // stat
        OpalCastProtocol.FileOpResponseMsg st = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(3, "stat", "/sdcard/Work/alpha.txt", "", 0, 0),
                null, null, () -> false);
        assertTrue(st.ok);
        assertEquals(5L, st.totalSize);
        assertEquals(1, st.entries.size());

        // thumbnail
        OpalCastProtocol.FileOpResponseMsg th = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(4, "thumbnail", "/sdcard/Work/photo.jpg", "", 0, 0),
                null, null, () -> false);
        assertTrue(th.ok);
        assertEquals("FAKE_THUMB_photo.jpg", th.thumbnailBase64);

        // rename
        OpalCastProtocol.FileOpResponseMsg rn = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(5, "rename", "/sdcard/Work/alpha.txt", "/sdcard/Work/beta.txt", 0, 0),
                null, null, () -> false);
        assertTrue(rn.ok);
        assertEquals("/sdcard/Work/beta.txt", rn.path);
        assertFalse(doc.exists());
        assertTrue(new File(storageRoot, "Work/beta.txt").isFile());

        // Cannot delete root
        OpalCastProtocol.FileOpResponseMsg delRoot = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(6, "delete", "/sdcard", "", 0, 0),
                null, null, () -> false);
        assertFalse(delRoot.ok);

        // Recursive delete directory
        OpalCastProtocol.FileOpResponseMsg delDir = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(7, "delete", "/sdcard/Work", "", 0, 0),
                null, null, () -> false);
        assertTrue(delDir.ok);
        assertFalse(new File(storageRoot, "Work").exists());
    }

    @Test
    public void testDeleteUnlinksInternalAndDanglingSymlinksWithoutTouchingRealTarget() throws Exception {
        File storageRoot = tmpDir.newFolder("sdcard_symlinks");
        File realDir = new File(storageRoot, "Important");
        assertTrue(realDir.mkdirs());
        File realFile = new File(realDir, "keep.txt");
        Files.write(realFile.toPath(), "KEEP_ME".getBytes(StandardCharsets.UTF_8));

        // 1. Internal symlink pointing to a real directory inside storageRoot
        File internalDirLink = new File(storageRoot, "alias_important");
        Files.createSymbolicLink(internalDirLink.toPath(), realDir.toPath());

        // 2. Internal symlink pointing to a real file inside storageRoot
        File internalFileLink = new File(storageRoot, "alias_keep.txt");
        Files.createSymbolicLink(internalFileLink.toPath(), realFile.toPath());

        // 3. Dangling symlink pointing to a non-existent path inside storageRoot
        File danglingLink = new File(storageRoot, "broken_link");
        Files.createSymbolicLink(danglingLink.toPath(), new File(storageRoot, "does_not_exist").toPath());

        RemoteFileManager fm = new RemoteFileManager(storageRoot, "/sdcard", (f, max, cap) -> "");

        // Deleting internal dir symlink removes ONLY the symlink; realDir + keep.txt stay intact
        OpalCastProtocol.FileOpResponseMsg delDirLink = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(20, "delete", "/sdcard/alias_important", "", 0, 0),
                null, null, () -> false);
        assertTrue(delDirLink.ok);
        assertEquals("/sdcard/alias_important", delDirLink.path);
        assertFalse(Files.exists(internalDirLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue("Real target directory must NOT be deleted", realDir.isDirectory());
        assertTrue("Real file inside target directory must NOT be deleted", realFile.isFile());

        // Deleting internal file symlink removes ONLY the symlink; keep.txt stays intact
        OpalCastProtocol.FileOpResponseMsg delFileLink = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(21, "delete", "/sdcard/alias_keep.txt", "", 0, 0),
                null, null, () -> false);
        assertTrue(delFileLink.ok);
        assertFalse(Files.exists(internalFileLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue("Real target file must NOT be deleted", realFile.isFile());

        // Deleting dangling symlink succeeds cleanly
        OpalCastProtocol.FileOpResponseMsg delDangling = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(22, "delete", "/sdcard/broken_link", "", 0, 0),
                null, null, () -> false);
        assertTrue(delDangling.ok);
        assertEquals("/sdcard/broken_link", delDangling.path);
        assertFalse(Files.exists(danglingLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));

        // Deleting through an escaping parent symlink is still blocked
        File outsideDir = tmpDir.newFolder("outside_del");
        File outsideSecret = new File(outsideDir, "secret.txt");
        Files.write(outsideSecret.toPath(), "SECRET".getBytes(StandardCharsets.UTF_8));
        File escapingParentLink = new File(storageRoot, "escape_dir");
        Files.createSymbolicLink(escapingParentLink.toPath(), outsideDir.toPath());

        OpalCastProtocol.FileOpResponseMsg delEscapeChild = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(23, "delete", "/sdcard/escape_dir/secret.txt", "", 0, 0),
                null, null, () -> false);
        assertFalse(delEscapeChild.ok);
        assertTrue("Outside secret must remain untouched", outsideSecret.isFile());
    }

    @Test
    public void testStorageScanCategorizationAndTop50LargeFiles() throws Exception {
        File storageRoot = tmpDir.newFolder("sdcard_scan");
        File dcim = new File(storageRoot, "DCIM");
        File movies = new File(storageRoot, "Movies");
        File music = new File(storageRoot, "Music");
        File docs = new File(storageRoot, "Documents");
        File apks = new File(storageRoot, "Download");
        assertTrue(dcim.mkdirs() && movies.mkdirs() && music.mkdirs() && docs.mkdirs() && apks.mkdirs());

        // Create 60 files across categories to verify top-50 cap and descending sort
        Files.write(new File(dcim, "img1.png").toPath(), new byte[100]);
        Files.write(new File(movies, "clip.mp4").toPath(), new byte[5000]);
        Files.write(new File(music, "song.flac").toPath(), new byte[300]);
        Files.write(new File(docs, "spec.pdf").toPath(), new byte[200]);
        Files.write(new File(apks, "app.xapk").toPath(), new byte[400]);
        for (int i = 1; i <= 55; i++) {
            Files.write(new File(apks, "blob_" + i + ".bin").toPath(), new byte[i * 10]);
        }

        RemoteFileManager fm = new RemoteFileManager(storageRoot, "/sdcard", (f, max, cap) -> "");
        OpalCastProtocol.FileOpResponseMsg resp = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(10, "storage_scan", "/sdcard", "", 0, 6),
                null, null, () -> false);
        assertTrue(resp.ok);
        assertNotNull(resp.storage);
        assertEquals(RemoteFileManager.MAX_LARGE_FILES, resp.storage.largeFiles.size());
        assertEquals("clip.mp4", resp.storage.largeFiles.get(0).name);
        assertEquals(5000L, resp.storage.largeFiles.get(0).size);
        for (int i = 1; i < resp.storage.largeFiles.size(); i++) {
            assertTrue(resp.storage.largeFiles.get(i - 1).size >= resp.storage.largeFiles.get(i).size);
        }

        // Verify category totals
        long imgBytes = -1, vidBytes = -1, audBytes = -1, docBytes = -1, apkBytes = -1;
        for (OpalCastProtocol.StorageCategory c : resp.storage.categories) {
            if ("images".equals(c.name)) imgBytes = c.bytes;
            if ("videos".equals(c.name)) vidBytes = c.bytes;
            if ("audio".equals(c.name)) audBytes = c.bytes;
            if ("documents".equals(c.name)) docBytes = c.bytes;
            if ("apks".equals(c.name)) apkBytes = c.bytes;
        }
        assertEquals(100L, imgBytes);
        assertEquals(5000L, vidBytes);
        assertEquals(300L, audBytes);
        assertEquals(200L, docBytes);
        assertEquals(400L, apkBytes);

        // Verify top-level folder ranking is populated in the same scan pass
        assertFalse(resp.storage.folders.isEmpty());
        assertEquals("/sdcard/Download", resp.storage.folders.get(0).path);
        assertEquals("Download", resp.storage.folders.get(0).name);
        assertEquals(56, resp.storage.folders.get(0).fileCount);
    }

    @Test
    public void testStorageScanTop25FoldersRecursiveDepth1And2SkipsSymlinksAndIncludesApps() throws Exception {
        File storageRoot = tmpDir.newFolder("sdcard_folders");
        File dcim = new File(storageRoot, "DCIM");
        File dcimCam = new File(dcim, "Camera");
        File dcimDeep = new File(dcimCam, "2026/Trips");
        assertTrue(dcimDeep.mkdirs());
        Files.write(new File(dcim, "cover.jpg").toPath(), new byte[100]);
        Files.write(new File(dcimCam, "shot.jpg").toPath(), new byte[400]);
        Files.write(new File(dcimDeep, "deep.mp4").toPath(), new byte[2000]);

        // Create 30 additional depth-1 directories to verify Top-25 cap & descending sort
        for (int i = 1; i <= 30; i++) {
            File d = new File(storageRoot, String.format("Dir_%02d", i));
            assertTrue(d.mkdirs());
            Files.write(new File(d, "f.bin").toPath(), new byte[i * 50]);
        }

        // Symlink inside scanRoot pointing to an outside dir with huge file must be skipped
        File outsideDir = tmpDir.newFolder("outside_huge");
        Files.write(new File(outsideDir, "huge.bin").toPath(), new byte[99_999]);
        Files.createSymbolicLink(new File(storageRoot, "SymlinkHuge").toPath(), outsideDir.toPath());

        List<OpalCastProtocol.AppStorageEntry> injectedApps = new ArrayList<>();
        injectedApps.add(new OpalCastProtocol.AppStorageEntry("com.small", "Small", 10, 20, 5, 35));
        injectedApps.add(new OpalCastProtocol.AppStorageEntry("com.big", "BigApp", 1000, 5000, 500, 6500));

        RemoteFileManager fm = new RemoteFileManager(
                storageRoot, "/sdcard", (f, max, cap) -> "", () -> injectedApps);
        OpalCastProtocol.FileOpResponseMsg resp = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(12, "storage_scan", "/sdcard", "", 0, 8),
                null, null, () -> false);
        assertTrue(resp.ok);
        assertNotNull(resp.storage);

        // Top 25 folders cap and descending order
        assertEquals(RemoteFileManager.MAX_TOP_FOLDERS, resp.storage.folders.size());
        assertEquals("/sdcard/DCIM", resp.storage.folders.get(0).path);
        assertEquals("DCIM", resp.storage.folders.get(0).name);
        assertEquals(2500L, resp.storage.folders.get(0).bytes);
        assertEquals(3, resp.storage.folders.get(0).fileCount);

        assertEquals("/sdcard/DCIM/Camera", resp.storage.folders.get(1).path);
        assertEquals("Camera", resp.storage.folders.get(1).name);
        assertEquals(2400L, resp.storage.folders.get(1).bytes);
        assertEquals(2, resp.storage.folders.get(1).fileCount);

        for (int i = 1; i < resp.storage.folders.size(); i++) {
            assertTrue(resp.storage.folders.get(i - 1).bytes >= resp.storage.folders.get(i).bytes);
            assertFalse("Symlink dir must never appear in folder report",
                    resp.storage.folders.get(i).name.equals("SymlinkHuge"));
            assertFalse("Depth-3 subdir must not be emitted as standalone folder entry",
                    resp.storage.folders.get(i).path.contains("/2026"));
        }

        // Injected app storage sorted descending by totalBytes
        assertEquals(2, resp.storage.apps.size());
        assertEquals("com.big", resp.storage.apps.get(0).pkg);
        assertEquals("BigApp", resp.storage.apps.get(0).appLabel);
        assertEquals(6500L, resp.storage.apps.get(0).totalBytes);
        assertEquals("com.small", resp.storage.apps.get(1).pkg);
    }

    @Test
    public void testParseDumpsysDiskstatsRealisticAospOemVariantsAndMalformedFallback() {
        String realisticAosp = ""
                + "Latency: 1ms [512B Data Write]\n"
                + "Data-Free: 45123456K / 115234567K total = 39% free\n"
                + "App Size: 12345678900\n"
                + "App Data Size: 23456789012\n"
                + "App Cache Size: 3456789012\n"
                + "Package Names: [\"com.android.chrome\", \"com.tencent.mm\", \"com.ss.android.ugc.aweme\"]\n"
                + "App Sizes: [198000000, 450000000, 312000000]\n"
                + "App Data Sizes: [850000000, 12500000000, 4200000000]\n"
                + "Cache Sizes: [120000000, 1800000000, 650000000]\n";

        List<OpalCastProtocol.AppStorageEntry> parsed = RemoteFileManager.parseDumpsysDiskstats(
                realisticAosp,
                pkg -> "com.tencent.mm".equals(pkg) ? "微信" : pkg);
        assertEquals(3, parsed.size());
        assertEquals("com.tencent.mm", parsed.get(0).pkg);
        assertEquals("微信", parsed.get(0).appLabel);
        assertEquals(450_000_000L, parsed.get(0).apkBytes);
        assertEquals(12_500_000_000L, parsed.get(0).dataBytes);
        assertEquals(1_800_000_000L, parsed.get(0).cacheBytes);
        assertEquals(14_750_000_000L, parsed.get(0).totalBytes);
        assertEquals("com.ss.android.ugc.aweme", parsed.get(1).pkg);
        assertEquals("com.android.chrome", parsed.get(2).pkg);

        // OEM variant: App Cache Sizes + multi-line wrap + negative value clamped to 0
        String oemVariant = ""
                + "Package Names: [\"com.alpha\",\n"
                + "  \"com.beta\"]\n"
                + "App Sizes: [100, -5]\n"
                + "App Data Sizes: [200, 900]\n"
                + "App Cache Sizes: [50, bad_num]\n";
        List<OpalCastProtocol.AppStorageEntry> oemParsed = RemoteFileManager.parseDumpsysDiskstats(oemVariant);
        assertEquals(2, oemParsed.size());
        assertEquals("com.beta", oemParsed.get(0).pkg);
        assertEquals(0L, oemParsed.get(0).apkBytes);
        assertEquals(900L, oemParsed.get(0).dataBytes);
        assertEquals(0L, oemParsed.get(0).cacheBytes);
        assertEquals(900L, oemParsed.get(0).totalBytes);
        assertEquals("com.alpha", oemParsed.get(1).pkg);
        assertEquals(350L, oemParsed.get(1).totalBytes);

        // Top 50 cap check (60 packages -> top 50 descending)
        StringBuilder pkgs = new StringBuilder("Package Names: [");
        StringBuilder apks = new StringBuilder("App Sizes: [");
        StringBuilder datas = new StringBuilder("App Data Sizes: [");
        StringBuilder caches = new StringBuilder("Cache Sizes: [");
        for (int i = 1; i <= 60; i++) {
            if (i > 1) {
                pkgs.append(',');
                apks.append(',');
                datas.append(',');
                caches.append(',');
            }
            pkgs.append('"').append("com.app.").append(i).append('"');
            apks.append(i * 1000L);
            datas.append(i * 2000L);
            caches.append(i * 500L);
        }
        pkgs.append("]\n");
        apks.append("]\n");
        datas.append("]\n");
        caches.append("]\n");
        List<OpalCastProtocol.AppStorageEntry> capped = RemoteFileManager.parseDumpsysDiskstats(
                pkgs.toString() + apks + datas + caches);
        assertEquals(RemoteFileManager.MAX_APP_STORAGE_ENTRIES, capped.size());
        assertEquals("com.app.60", capped.get(0).pkg);
        assertEquals(60L * 3500L, capped.get(0).totalBytes);
        assertEquals("com.app.11", capped.get(49).pkg);

        // Malformed / empty / null fallback returns empty list gracefully
        assertTrue(RemoteFileManager.parseDumpsysDiskstats(null).isEmpty());
        assertTrue(RemoteFileManager.parseDumpsysDiskstats("").isEmpty());
        assertTrue(RemoteFileManager.parseDumpsysDiskstats("Permission Denial: can't dump diskstats").isEmpty());
        assertTrue(RemoteFileManager.parseDumpsysDiskstats("Package Names: []\nApp Sizes: []").isEmpty());
    }

    @Test
    public void testChunkedFilePullEmits0x1BThen256KiB0x1CChunks() throws Exception {
        File storageRoot = tmpDir.newFolder("sdcard_pull");
        File bigFile = new File(storageRoot, "payload.bin");
        byte[] expected = new byte[RemoteFileManager.PULL_CHUNK_SIZE * 2 + 12345];
        new Random(42L).nextBytes(expected);
        Files.write(bigFile.toPath(), expected);

        RemoteFileManager fm = new RemoteFileManager(storageRoot, "/sdcard", (f, max, cap) -> "");
        List<String> eventOrder = new ArrayList<>();
        List<OpalCastProtocol.FilePullChunkMsg> chunks = new ArrayList<>();

        OpalCastProtocol.FileOpResponseMsg resp = fm.executeRequest(
                new OpalCastProtocol.FileOpRequestMsg(101, "pull", "/sdcard/payload.bin", "", 777, 0),
                r -> eventOrder.add("RESP:" + r.ok + ":" + r.totalSize),
                raw -> {
                    eventOrder.add("CHUNK");
                    try {
                        chunks.add(OpalCastProtocol.decodeFilePullChunk(raw));
                        return true;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                () -> false);

        assertTrue(resp.ok);
        assertEquals(expected.length, resp.totalSize);
        assertEquals("RESP:true:" + expected.length, eventOrder.get(0));
        assertEquals(3, chunks.size());

        // Chunk 0: START | DATA with name
        assertEquals(777, chunks.get(0).transferId);
        assertEquals(OpalCastProtocol.FILE_FLAG_START | OpalCastProtocol.FILE_FLAG_DATA, chunks.get(0).chunkFlags);
        assertEquals("payload.bin", chunks.get(0).nameOrPath);
        assertEquals(expected.length, chunks.get(0).totalSize);
        assertEquals(RemoteFileManager.PULL_CHUNK_SIZE, chunks.get(0).data.length);

        // Chunk 1: DATA only with empty name
        assertEquals(OpalCastProtocol.FILE_FLAG_DATA, chunks.get(1).chunkFlags);
        assertEquals("", chunks.get(1).nameOrPath);
        assertEquals(RemoteFileManager.PULL_CHUNK_SIZE, chunks.get(1).data.length);

        // Chunk 2: DATA | END
        assertEquals(OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END, chunks.get(2).chunkFlags);
        assertEquals(12345, chunks.get(2).data.length);

        ByteArrayOutputStream reassembled = new ByteArrayOutputStream(expected.length);
        for (OpalCastProtocol.FilePullChunkMsg c : chunks) {
            reassembled.write(c.data);
        }
        assertArrayEquals(expected, reassembled.toByteArray());
    }
}
