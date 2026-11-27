package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SyncFailedException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Path-traversal-hardened chunked file transfer receiver for OpalCast v1.1 (0x28 / 0x13).
 * Writes chunks into sibling {@code .part} files, fsyncs and renames on completion,
 * and triggers timeout-bounded {@code am broadcast} media scanning without ever acquiring
 * MediaStore / MediaScannerConnection ContentProviders from uid=2000 {@code app_process}.
 */
public final class FileTransferReceiver {
    public static final String DEFAULT_DOWNLOAD_DIR = "/sdcard/Download/OpalDesk";
    public static final String DEFAULT_STORAGE_ROOT = "/sdcard";
    public static final String DEFAULT_APK_STAGING_DIR = "/data/local/tmp/opaldesk_apk_stage";
    public static final String APK_STAGE_PREFIX = "@apk_stage/";
    public static final int MAX_CONCURRENT_TRANSFERS = 4;
    public static final long MAX_TRANSFER_FILE_BYTES = 4L * 1024L * 1024L * 1024L; // 4 GiB
    private static final long MEDIA_SCAN_TIMEOUT_MS = 5000L;

    private static final ExecutorService MEDIA_SCAN_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExtMediaScan");
        t.setDaemon(true);
        return t;
    });

    public interface AckListener {
        void sendFileTransferAck(int transferId, int status, String pathOrError);
    }

    private static final class ActiveTransfer {
        final File tempFile;
        final File finalFile;
        final long expectedSize;
        final FileOutputStream out;
        final boolean shouldMediaScan;
        long writtenBytes = 0L;

        ActiveTransfer(
                File tempFile,
                File finalFile,
                long expectedSize,
                FileOutputStream out,
                boolean shouldMediaScan) {
            this.tempFile = tempFile;
            this.finalFile = finalFile;
            this.expectedSize = expectedSize;
            this.out = out;
            this.shouldMediaScan = shouldMediaScan;
        }
    }

    private final AckListener ackListener;
    private final File downloadDir;
    private final File storageRoot;
    private final File apkStagingDir;
    private final boolean triggerMediaScan;
    private final Object transferLock = new Object();
    private final Map<Integer, ActiveTransfer> transfers = new HashMap<>();

    public FileTransferReceiver(AckListener ackListener) {
        this(ackListener, new File(DEFAULT_DOWNLOAD_DIR), new File(DEFAULT_STORAGE_ROOT),
                new File(DEFAULT_APK_STAGING_DIR), true);
    }

    public FileTransferReceiver(AckListener ackListener, File downloadDir) {
        this(ackListener, downloadDir, null, null, true);
    }

    public FileTransferReceiver(AckListener ackListener, File downloadDir, boolean triggerMediaScan) {
        this(ackListener, downloadDir, null, null, triggerMediaScan);
    }

    public FileTransferReceiver(
            AckListener ackListener,
            File downloadDir,
            File storageRoot,
            File apkStagingDir,
            boolean triggerMediaScan) {
        this.ackListener = ackListener;
        this.downloadDir = (downloadDir != null) ? downloadDir : new File(DEFAULT_DOWNLOAD_DIR);
        this.storageRoot = storageRoot;
        this.apkStagingDir = apkStagingDir;
        this.triggerMediaScan = triggerMediaScan;
    }

    public void onSessionEnded() {
        abortAllTransfers();
    }

    public void handleFileTransferChunk(OpalCastProtocol.FileTransferChunkMsg msg) {
        if (msg == null) return;
        int tid = msg.transferId;
        int flags = msg.chunkFlags;

        synchronized (transferLock) {
            if ((flags & OpalCastProtocol.FILE_FLAG_ABORT) != 0) {
                ActiveTransfer tr = transfers.remove(tid);
                cleanupTransferFiles(tr);
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Transfer aborted");
                return;
            }

            if ((flags & OpalCastProtocol.FILE_FLAG_START) != 0) {
                if (!handleStartLocked(tid, msg)) {
                    return;
                }
            }

            if ((flags & OpalCastProtocol.FILE_FLAG_DATA) != 0) {
                if (!handleDataLocked(tid, msg)) {
                    return;
                }
            }

            if ((flags & OpalCastProtocol.FILE_FLAG_END) != 0) {
                handleEndLocked(tid);
            }
        }
    }

    public void abortAllTransfers() {
        synchronized (transferLock) {
            Iterator<ActiveTransfer> it = transfers.values().iterator();
            while (it.hasNext()) {
                cleanupTransferFiles(it.next());
                it.remove();
            }
        }
    }

    /**
     * Triggers an Android media scan asynchronously via timeout-bounded {@code am broadcast}
     * on a single bounded daemon worker (never via MediaScannerConnection / ContentResolver).
     */
    public static void triggerMediaScanAsync(File savedFile) {
        if (savedFile == null) return;
        final String uriStr = "file://" + savedFile.getAbsolutePath();
        MEDIA_SCAN_EXEC.execute(() -> {
            ShellCommand.Result res = ShellCommand.runWithTimeout(
                    new String[]{
                            "am", "broadcast",
                            "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                            "-d", uriStr
                    },
                    MEDIA_SCAN_TIMEOUT_MS);
            if (!res.isSuccess()) {
                System.err.println("OPALDESK_MEDIA_SCAN_WARN uri=" + uriStr
                        + " exit=" + res.exitCode + " timedOut=" + res.timedOut);
            }
        });
    }

    private boolean handleStartLocked(int tid, OpalCastProtocol.FileTransferChunkMsg msg) {
        ActiveTransfer prev = transfers.remove(tid);
        cleanupTransferFiles(prev);

        if (transfers.size() >= MAX_CONCURRENT_TRANSFERS) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Too many concurrent file transfers");
            return false;
        }
        if (msg.totalSize < 0 || msg.totalSize > MAX_TRANSFER_FILE_BYTES) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Invalid or oversized file");
            return false;
        }
        ResolvedUploadTarget resolved = resolveUploadTarget(msg.fileName);
        if (resolved == null) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Rejected unsafe file name");
            return false;
        }
        try {
            if (!resolved.baseDir.exists() && !resolved.baseDir.mkdirs()) {
                throw new IOException("Cannot create destination directory");
            }
            File canonicalBase = resolved.baseDir.getCanonicalFile();
            File targetDir = resolved.subDir.isEmpty()
                    ? canonicalBase
                    : new File(canonicalBase, resolved.subDir);
            FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalBase, targetDir);
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw new IOException("Cannot create destination subdirectory");
            }
            File canonicalTargetDir = targetDir.getCanonicalFile();
            FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalBase, canonicalTargetDir);

            File finalFile = FileTransferPathGuard.resolveUniqueSafeFile(
                    canonicalBase, canonicalTargetDir, resolved.fileName);
            File tempFile = new File(canonicalTargetDir,
                    ".opaldesk_" + Integer.toUnsignedString(tid) + "_" + System.nanoTime() + ".part");
            FileTransferPathGuard.verifyInsideBaseDir(canonicalBase, finalFile);
            FileTransferPathGuard.verifyInsideBaseDir(canonicalBase, tempFile);

            FileOutputStream fos = new FileOutputStream(tempFile, false);
            transfers.put(tid, new ActiveTransfer(
                    tempFile, finalFile, msg.totalSize, fos, triggerMediaScan && resolved.allowMediaScan));
            return true;
        } catch (IOException | RuntimeException t) {
            System.err.println("OPALDESK_XFER_START_ERR tid=" + tid + ": " + t.getMessage());
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Failed to open destination: " + t.getMessage());
            return false;
        }
    }

    private static final class ResolvedUploadTarget {
        final File baseDir;
        final String subDir;
        final String fileName;
        final boolean allowMediaScan;

        ResolvedUploadTarget(File baseDir, String subDir, String fileName, boolean allowMediaScan) {
            this.baseDir = baseDir;
            this.subDir = subDir;
            this.fileName = fileName;
            this.allowMediaScan = allowMediaScan;
        }
    }

    private ResolvedUploadTarget resolveUploadTarget(String rawName) {
        if (rawName == null || rawName.indexOf('\0') >= 0) return null;
        String normalized = rawName.replace('\\', '/').trim();
        if (normalized.isEmpty()) return null;

        // 1. Dedicated APK install staging channel ("@apk_stage/foo.apks")
        if (normalized.startsWith(APK_STAGE_PREFIX)) {
            if (apkStagingDir == null) return null;
            String rest = normalized.substring(APK_STAGE_PREFIX.length());
            if (rest.contains("/")) return null;
            String safeFile = FileTransferPathGuard.sanitizeFileName(rest);
            if (safeFile == null) return null;
            return new ResolvedUploadTarget(apkStagingDir, "", safeFile, false);
        }

        // 2. Explicit storage-root path ("/sdcard/Documents/a.pdf" or "/storage/emulated/0/...")
        if (normalized.startsWith("/")) {
            if (storageRoot == null) return null;
            String remainder = stripAllowedStoragePrefix(normalized, storageRoot);
            if (remainder == null) return null;
            return splitIntoTarget(storageRoot, remainder, true);
        }

        // 3. Relative subpath ("sub/dir/file.png") or plain filename ("note.txt") under downloadDir
        if (normalized.contains("/")) {
            return splitIntoTarget(downloadDir, normalized, true);
        }
        String safeFile = FileTransferPathGuard.sanitizeFileName(normalized);
        if (safeFile == null) return null;
        return new ResolvedUploadTarget(downloadDir, "", safeFile, true);
    }

    private static ResolvedUploadTarget splitIntoTarget(File baseDir, String rawRelative, boolean allowMediaScan) {
        String safeRel = FileTransferPathGuard.sanitizeRelativeSubpath(rawRelative);
        if (safeRel == null) return null;
        int lastSlash = safeRel.lastIndexOf('/');
        String subDir = (lastSlash > 0) ? safeRel.substring(0, lastSlash) : "";
        String fileName = (lastSlash >= 0) ? safeRel.substring(lastSlash + 1) : safeRel;
        return new ResolvedUploadTarget(baseDir, subDir, fileName, allowMediaScan);
    }

    private static String stripAllowedStoragePrefix(String normalizedPath, File storageRoot) {
        if (normalizedPath.startsWith("/sdcard/")) {
            return normalizedPath.substring("/sdcard/".length());
        }
        if (normalizedPath.startsWith("/storage/emulated/0/")) {
            return normalizedPath.substring("/storage/emulated/0/".length());
        }
        try {
            String canon = storageRoot.getCanonicalPath();
            String prefix = canon.endsWith("/") ? canon : (canon + "/");
            if (normalizedPath.startsWith(prefix)) {
                return normalizedPath.substring(prefix.length());
            }
        } catch (IOException ignored) {
        }
        String abs = storageRoot.getAbsolutePath();
        String absPrefix = abs.endsWith("/") ? abs : (abs + "/");
        if (normalizedPath.startsWith(absPrefix)) {
            return normalizedPath.substring(absPrefix.length());
        }
        return null;
    }

    private boolean handleDataLocked(int tid, OpalCastProtocol.FileTransferChunkMsg msg) {
        ActiveTransfer tr = transfers.get(tid);
        if (tr == null) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Unknown transferId");
            return false;
        }
        if (msg.data != null && msg.data.length > 0) {
            long nextSize = tr.writtenBytes + msg.data.length;
            if (nextSize > MAX_TRANSFER_FILE_BYTES
                    || (tr.expectedSize > 0 && nextSize > tr.expectedSize)) {
                transfers.remove(tid);
                cleanupTransferFiles(tr);
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Transfer exceeded declared size");
                return false;
            }
            try {
                tr.out.write(msg.data);
                tr.writtenBytes = nextSize;
            } catch (IOException e) {
                transfers.remove(tid);
                cleanupTransferFiles(tr);
                System.err.println("OPALDESK_XFER_WRITE_ERR tid=" + tid + ": " + e.getMessage());
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Write error: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private void handleEndLocked(int tid) {
        ActiveTransfer tr = transfers.remove(tid);
        if (tr == null) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Unknown transferId on END");
            return;
        }
        try {
            tr.out.flush();
            try {
                tr.out.getFD().sync();
            } catch (SyncFailedException sfe) {
                System.err.println("OPALDESK_XFER_FSYNC_WARN file=" + tr.finalFile.getName()
                        + ": " + sfe.getMessage());
            }
            tr.out.close();

            if (tr.expectedSize > 0 && tr.writtenBytes != tr.expectedSize) {
                deleteQuietly(tr.tempFile);
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR,
                        "Size mismatch: expected " + tr.expectedSize + ", got " + tr.writtenBytes);
                return;
            }
            if (!tr.tempFile.renameTo(tr.finalFile)) {
                deleteQuietly(tr.tempFile);
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Failed to finalize file");
                return;
            }
            if (tr.shouldMediaScan) {
                triggerMediaScanAsync(tr.finalFile);
            }
            sendAck(tid, OpalCastProtocol.FILE_ACK_OK, tr.finalFile.getAbsolutePath());
        } catch (IOException | RuntimeException t) {
            cleanupTransferFiles(tr);
            System.err.println("OPALDESK_XFER_FINALIZE_ERR tid=" + tid + ": " + t.getMessage());
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Finalize error: " + t.getMessage());
        }
    }

    private void sendAck(int transferId, int status, String pathOrError) {
        if (ackListener != null) {
            ackListener.sendFileTransferAck(transferId, status, pathOrError);
        }
    }

    private static void cleanupTransferFiles(ActiveTransfer tr) {
        if (tr == null) return;
        try {
            tr.out.close();
        } catch (IOException e) {
            System.err.println("OPALDESK_XFER_CLOSE_WARN: " + e.getMessage());
        }
        deleteQuietly(tr.tempFile);
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            System.err.println("OPALDESK_XFER_DELETE_WARN failed to delete " + f.getAbsolutePath());
        }
    }
}
