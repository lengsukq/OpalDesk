package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.FileTransferReceiver;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FileTransferReceiverTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private static final class RecordedAck {
        final int transferId;
        final int status;
        final String pathOrError;

        RecordedAck(int transferId, int status, String pathOrError) {
            this.transferId = transferId;
            this.status = status;
            this.pathOrError = pathOrError;
        }
    }

    @Test
    public void testChunkedTransferSuccessDuplicateAndAbortCleanup() throws Exception {
        File downloadDir = tmpDir.newFolder("downloads");
        List<RecordedAck> acks = new ArrayList<>();
        FileTransferReceiver rx = new FileTransferReceiver(
                (id, status, msg) -> acks.add(new RecordedAck(id, status, msg)),
                downloadDir,
                false);

        byte[] part1 = "Hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "OpalDesk!".getBytes(StandardCharsets.UTF_8);
        int total = part1.length + part2.length;

        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                1, OpalCastProtocol.FILE_FLAG_START, "note.txt", total, null));
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                1, OpalCastProtocol.FILE_FLAG_DATA, null, -1, part1));
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                1, OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END, null, -1, part2));

        assertEquals(1, acks.size());
        assertEquals(OpalCastProtocol.FILE_ACK_OK, acks.get(0).status);
        File saved1 = new File(acks.get(0).pathOrError);
        assertTrue(saved1.isFile());
        assertEquals("note.txt", saved1.getName());
        assertEquals("Hello OpalDesk!", new String(Files.readAllBytes(saved1.toPath()), StandardCharsets.UTF_8));

        // Second file with same name gets deduplicated cleanly
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                2, OpalCastProtocol.FILE_FLAG_START, "note.txt", part1.length, null));
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                2, OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END, null, -1, part1));
        assertEquals(2, acks.size());
        assertEquals(OpalCastProtocol.FILE_ACK_OK, acks.get(1).status);
        assertEquals("note (1).txt", new File(acks.get(1).pathOrError).getName());

        // Path traversal rejected
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                3, OpalCastProtocol.FILE_FLAG_START, "../escape.txt", 4, null));
        assertEquals(3, acks.size());
        assertEquals(OpalCastProtocol.FILE_ACK_ERR, acks.get(2).status);

        // Session end aborts in-flight transfer and deletes .part file
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                4, OpalCastProtocol.FILE_FLAG_START, "partial.bin", 1024, null));
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                4, OpalCastProtocol.FILE_FLAG_DATA, null, -1, part1));
        rx.onSessionEnded();
        File[] partFiles = downloadDir.listFiles((d, name) -> name.endsWith(".part"));
        assertEquals(0, partFiles != null ? partFiles.length : 0);
    }

    @Test
    public void testSizeMismatchRejectedAndCleanedUp() throws Exception {
        File downloadDir = tmpDir.newFolder("downloads_mismatch");
        List<RecordedAck> acks = new ArrayList<>();
        FileTransferReceiver rx = new FileTransferReceiver(
                (id, status, msg) -> acks.add(new RecordedAck(id, status, msg)),
                downloadDir,
                false);

        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                9, OpalCastProtocol.FILE_FLAG_START, "trunc.bin", 100, null));
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                9, OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END, null, -1, new byte[]{1, 2, 3}));

        assertEquals(1, acks.size());
        assertEquals(OpalCastProtocol.FILE_ACK_ERR, acks.get(0).status);
        assertTrue(acks.get(0).pathOrError.contains("Size mismatch"));
        File[] remaining = downloadDir.listFiles();
        assertEquals(0, remaining != null ? remaining.length : 0);
    }

    @Test
    public void testSubdirStorageRootAndApkStagingUploadsWithTraversalDefense() throws Exception {
        File storageRoot = tmpDir.newFolder("sdcard_up");
        File downloadDir = new File(storageRoot, "Download/OpalDesk");
        File apkStageDir = tmpDir.newFolder("apk_stage");
        List<RecordedAck> acks = new ArrayList<>();
        FileTransferReceiver rx = new FileTransferReceiver(
                (id, status, msg) -> acks.add(new RecordedAck(id, status, msg)),
                downloadDir,
                storageRoot,
                apkStageDir,
                false);

        byte[] payload = "DATA123".getBytes(StandardCharsets.UTF_8);

        // 1. Safe relative subdirectory under downloadDir
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                10, OpalCastProtocol.FILE_FLAG_START, "sub/dir/file.png", payload.length, null));
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                10, OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END, null, -1, payload));
        assertEquals(OpalCastProtocol.FILE_ACK_OK, acks.get(0).status);
        File savedSub = new File(acks.get(0).pathOrError);
        assertTrue(savedSub.isFile());
        assertTrue(savedSub.getCanonicalPath().endsWith(File.separator + "sub" + File.separator + "dir" + File.separator + "file.png"));

        // 2. Explicit /sdcard/... path targeting storageRoot
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                11, OpalCastProtocol.FILE_FLAG_START, "/sdcard/Documents/report.pdf", payload.length, null));
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                11, OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END, null, -1, payload));
        assertEquals(OpalCastProtocol.FILE_ACK_OK, acks.get(1).status);
        File savedDoc = new File(acks.get(1).pathOrError);
        assertTrue(savedDoc.isFile());
        assertTrue(savedDoc.getCanonicalPath().startsWith(new File(storageRoot, "Documents").getCanonicalPath()));

        // 3. Dedicated @apk_stage/ staging target
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                12, OpalCastProtocol.FILE_FLAG_START, "@apk_stage/demo.apks", payload.length, null));
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                12, OpalCastProtocol.FILE_FLAG_DATA | OpalCastProtocol.FILE_FLAG_END, null, -1, payload));
        assertEquals(OpalCastProtocol.FILE_ACK_OK, acks.get(2).status);
        File savedStage = new File(acks.get(2).pathOrError);
        assertTrue(savedStage.isFile());
        assertTrue(savedStage.getCanonicalPath().startsWith(apkStageDir.getCanonicalPath()));

        // 4. Disallowed absolute path & traversal rejected
        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                13, OpalCastProtocol.FILE_FLAG_START, "/etc/evil.conf", payload.length, null));
        assertEquals(OpalCastProtocol.FILE_ACK_ERR, acks.get(3).status);

        rx.handleFileTransferChunk(new OpalCastProtocol.FileTransferChunkMsg(
                14, OpalCastProtocol.FILE_FLAG_START, "@apk_stage/../escape.apk", payload.length, null));
        assertEquals(OpalCastProtocol.FILE_ACK_ERR, acks.get(4).status);
    }
}
