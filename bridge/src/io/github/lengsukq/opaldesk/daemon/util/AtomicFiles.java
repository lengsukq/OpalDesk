package io.github.lengsukq.opaldesk.daemon.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SyncFailedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single source of truth for crash-safe atomic file persistence (write temp -> fsync -> chmod -> rename).
 * Pure Java 11 with zero Android runtime dependencies.
 */
public final class AtomicFiles {
    public static final int MODE_DEFAULT = -1;
    public static final int MODE_OWNER_RW = 0600;
    public static final int MODE_WORLD_READABLE = 0644;
    private static final AtomicLong TMP_SEQ = new AtomicLong();

    private AtomicFiles() {}

    public static void writeAtomic(File target, byte[] data) throws IOException {
        writeAtomic(target, data, MODE_OWNER_RW);
    }

    public static void writeAtomicUtf8(File target, String content) throws IOException {
        writeAtomicUtf8(target, content, MODE_OWNER_RW);
    }

    public static void writeAtomicUtf8(File target, String content, int mode) throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        writeAtomic(target, content.getBytes(StandardCharsets.UTF_8), mode);
    }

    /**
     * Atomically writes {@code data} to {@code target} via a sibling temp file, fsync, optional
     * POSIX permission mode, and rename. Propagates {@link IOException} on failure and cleans up
     * the temporary file honestly without silently swallowing errors.
     */
    public static void writeAtomic(File target, byte[] data, int mode) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("target file must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }

        File absTarget = target.getAbsoluteFile();
        File parent = absTarget.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
        }

        File tmp = new File(
                parent,
                "." + absTarget.getName() + "." + System.nanoTime() + "." + TMP_SEQ.incrementAndGet() + ".tmp");
        boolean renamed = false;
        try {
            writeAndSync(tmp, data, absTarget.getAbsolutePath());
            applyMode(tmp, mode);

            IOException moveFailure = null;
            try {
                Files.move(tmp.toPath(), absTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
                renamed = true;
            } catch (IOException moveErr) {
                moveFailure = moveErr;
                if (tmp.renameTo(absTarget)) {
                    renamed = true;
                }
            }

            if (!renamed) {
                // On Android /data/local/tmp (sticky bit 1777), replacing a pre-existing file owned by
                // a different UID (e.g. previous root launch before dropping to uid=2000) can reject
                // rename() even when the target is writable; fall back to direct synced overwrite.
                try {
                    writeAndSync(absTarget, data, absTarget.getAbsolutePath());
                    applyMode(absTarget, mode);
                } catch (IOException directErr) {
                    IOException combined = new IOException(
                            "Failed to rename temp file " + tmp.getName()
                                    + " to " + absTarget.getAbsolutePath()
                                    + " (" + (moveFailure != null ? moveFailure.getMessage() : "renameTo=false")
                                    + "); direct fallback failed: " + directErr.getMessage(),
                            directErr);
                    if (moveFailure != null) {
                        combined.addSuppressed(moveFailure);
                    }
                    throw combined;
                }
            }
        } finally {
            if (!renamed && tmp.exists() && !tmp.delete()) {
                System.err.println("OPALDESK_ATOMIC_TMP_CLEANUP_WARN failed to delete "
                        + tmp.getAbsolutePath());
            }
        }
    }

    private static void writeAndSync(File file, byte[] data, String logicalPath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(data);
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (SyncFailedException sfe) {
                System.err.println("OPALDESK_ATOMIC_FSYNC_WARN file=" + logicalPath
                        + ": " + sfe.getMessage());
            }
        }
    }

    private static void applyMode(File file, int mode) {
        if (mode < 0) {
            return;
        }
        boolean ownerRead = (mode & 0400) != 0;
        boolean ownerWrite = (mode & 0200) != 0;
        boolean ownerExec = (mode & 0100) != 0;
        boolean otherRead = (mode & 0044) != 0;
        boolean otherWrite = (mode & 0022) != 0;
        boolean otherExec = (mode & 0011) != 0;

        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);

        if (otherRead) {
            file.setReadable(true, false);
        } else if (ownerRead) {
            file.setReadable(true, true);
        }
        if (otherWrite) {
            file.setWritable(true, false);
        } else if (ownerWrite) {
            file.setWritable(true, true);
        }
        if (otherExec) {
            file.setExecutable(true, false);
        } else if (ownerExec) {
            file.setExecutable(true, true);
        }
    }
}
