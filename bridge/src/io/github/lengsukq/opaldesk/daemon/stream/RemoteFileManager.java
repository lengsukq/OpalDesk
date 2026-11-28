package io.github.lengsukq.opaldesk.daemon.stream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Canonical-path-confined remote file browser, chunked pull streamer (0x1C), and thumbnail
 * provider for OpalCast v1.2 (0x33 / 0x1B / 0x1C). Storage scanning & {@code dumpsys diskstats}
 * analysis are delegated to {@link RemoteStorageAnalyzer}.
 *
 * <p>Every path is strictly verified via canonicalization against {@link #storageRoot} to prevent
 * {@code ..} traversal and symlink escapes. Pure JVM testable via injectable root and thumbnailer.
 */
public final class RemoteFileManager {
    public static final String DEFAULT_STORAGE_ROOT = "/sdcard";
    public static final int PULL_CHUNK_SIZE = 256 * 1024; // 256 KiB
    public static final int DEFAULT_SCAN_MAX_DEPTH = 8;
    public static final int MAX_SCAN_MAX_DEPTH = 16;
    public static final int MAX_SCAN_FILES = 100_000;
    public static final int MAX_LARGE_FILES = 50;
    public static final int MAX_TOP_FOLDERS = 25;
    public static final int MAX_APP_STORAGE_ENTRIES = 50;
    public static final int MAX_THUMBNAIL_EDGE_PX = 256;
    public static final int MAX_THUMBNAIL_BASE64_BYTES = 64 * 1024;

    private static final Set<String> IMAGE_EXTS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "dng", "raw", "avif");
    private static final Set<String> VIDEO_EXTS = Set.of(
            "mp4", "mkv", "mov", "avi", "webm", "flv", "m4v", "3gp", "ts", "wmv");
    private static final Set<String> AUDIO_EXTS = Set.of(
            "mp3", "flac", "wav", "aac", "ogg", "m4a", "opus", "wma", "alac", "mid", "midi");
    private static final Set<String> DOCUMENT_EXTS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf",
            "epub", "json", "xml", "html", "htm", "log");
    private static final Set<String> APK_EXTS = Set.of(
            "apk", "apks", "apkm", "xapk", "aab");

    public interface PullChunkSender {
        boolean sendChunk(byte[] payload);
    }

    public interface ThumbnailGenerator {
        String generateBase64(File imageFile, int maxEdgePx, int maxBase64Bytes) throws IOException;
    }

    public interface AppStorageStatsProvider {
        List<OpalCastProtocol.AppStorageEntry> collectAppStorage();
    }

    private final File storageRoot;
    private final String displayRootPrefix;
    private final ThumbnailGenerator thumbnailGenerator;
    private volatile AppStorageStatsProvider appStorageStatsProvider;

    public RemoteFileManager() {
        this(new File(DEFAULT_STORAGE_ROOT), DEFAULT_STORAGE_ROOT,
                RemoteFileManager::encodeAndroidThumbnail,
                createDefaultAppStorageProvider(null));
    }

    public RemoteFileManager(File storageRoot) {
        this(storageRoot, null,
                RemoteFileManager::encodeAndroidThumbnail,
                createDefaultAppStorageProvider(null));
    }

    public RemoteFileManager(File storageRoot, ThumbnailGenerator thumbnailGenerator) {
        this(storageRoot, null, thumbnailGenerator, createDefaultAppStorageProvider(null));
    }

    public RemoteFileManager(File storageRoot, String displayRootPrefix, ThumbnailGenerator thumbnailGenerator) {
        this(storageRoot, displayRootPrefix, thumbnailGenerator, createDefaultAppStorageProvider(null));
    }

    public RemoteFileManager(
            File storageRoot,
            String displayRootPrefix,
            ThumbnailGenerator thumbnailGenerator,
            AppStorageStatsProvider appStorageStatsProvider) {
        this.storageRoot = (storageRoot != null) ? storageRoot : new File(DEFAULT_STORAGE_ROOT);
        this.displayRootPrefix = (displayRootPrefix != null && !displayRootPrefix.trim().isEmpty())
                ? trimTrailingSlash(displayRootPrefix.trim())
                : null;
        this.thumbnailGenerator = (thumbnailGenerator != null)
                ? thumbnailGenerator : RemoteFileManager::encodeAndroidThumbnail;
        this.appStorageStatsProvider = (appStorageStatsProvider != null)
                ? appStorageStatsProvider : createDefaultAppStorageProvider(null);
    }

    public void setAppStorageStatsProvider(AppStorageStatsProvider provider) {
        this.appStorageStatsProvider = (provider != null)
                ? provider : createDefaultAppStorageProvider(null);
    }

    /**
     * Creates the default {@code uid=2000} app storage stats provider backed by {@code dumpsys diskstats}.
     */
    public static AppStorageStatsProvider createDefaultAppStorageProvider(Function<String, String> labelResolver) {
        return RemoteStorageAnalyzer.createDefaultAppStorageProvider(labelResolver);
    }

    /**
     * Parses AOSP/OEM {@code dumpsys diskstats} output into sorted {@link OpalCastProtocol.AppStorageEntry} rows.
     */
    public static List<OpalCastProtocol.AppStorageEntry> parseDumpsysDiskstats(String output) {
        return RemoteStorageAnalyzer.parseDumpsysDiskstats(output, null);
    }

    public static List<OpalCastProtocol.AppStorageEntry> parseDumpsysDiskstats(
            String output, Function<String, String> labelResolver) {
        return RemoteStorageAnalyzer.parseDumpsysDiskstats(output, labelResolver);
    }

    /**
     * Resolves {@code rawPath} strictly inside {@link #storageRoot} after rejecting null bytes,
     * {@code ..} segments, disallowed absolute prefixes, and symlink escapes.
     */
    public File resolveSafePath(String rawPath) throws IOException {
        File canonicalRoot = storageRoot.getCanonicalFile();
        if (rawPath == null) {
            return canonicalRoot;
        }
        if (rawPath.indexOf('\0') >= 0) {
            throw new SecurityException("Path contains null byte");
        }
        String normalized = rawPath.replace('\\', '/').trim();
        if (normalized.isEmpty() || "/".equals(normalized)) {
            return canonicalRoot;
        }

        for (String seg : normalized.split("/")) {
            if ("..".equals(seg)) {
                throw new SecurityException("Path traversal '..' blocked: " + rawPath);
            }
        }

        File candidate;
        if (normalized.startsWith("/")) {
            String stripped = stripAllowedRootPrefix(normalized, canonicalRoot);
            if (stripped == null) {
                throw new SecurityException("Path outside storage root: " + rawPath);
            }
            candidate = stripped.isEmpty() ? canonicalRoot : new File(canonicalRoot, stripped);
        } else {
            candidate = new File(canonicalRoot, normalized);
        }

        verifyExistingAncestorsInsideRoot(canonicalRoot, candidate);
        File canonicalTarget = candidate.getCanonicalFile();
        FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalRoot, canonicalTarget);
        return canonicalTarget;
    }

    public String toDisplayPath(File target) throws IOException {
        File canonicalRoot = storageRoot.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalRoot, canonicalTarget);
        String rootPath = canonicalRoot.getPath();
        String targetPath = canonicalTarget.getPath();
        String basePrefix = (displayRootPrefix != null) ? displayRootPrefix : rootPath;
        if (targetPath.equals(rootPath)) {
            return basePrefix;
        }
        String suffix = targetPath.substring(rootPath.length()).replace('\\', '/');
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return basePrefix + suffix;
    }

    /**
     * Executes a single {@code 0x33 FILE_OP_REQUEST}, emitting the {@code 0x1B FILE_OP_RESPONSE}
     * via {@code responseSender} (for {@code pull}, {@code 0x1B} is sent first before streaming
     * {@code 0x1C} chunks via {@code pullSender}).
     */
    public OpalCastProtocol.FileOpResponseMsg executeRequest(
            OpalCastProtocol.FileOpRequestMsg req,
            Consumer<OpalCastProtocol.FileOpResponseMsg> responseSender,
            PullChunkSender pullSender,
            BooleanSupplier isCancelled) {
        if (req == null) {
            OpalCastProtocol.FileOpResponseMsg err = errorResponse(0, "", "", "Empty request");
            if (responseSender != null) responseSender.accept(err);
            return err;
        }
        String op = req.op.toLowerCase(Locale.ROOT);
        if ("pull".equals(op)) {
            return handlePull(req, responseSender, pullSender, isCancelled);
        }
        OpalCastProtocol.FileOpResponseMsg resp;
        switch (op) {
            case "list":
                resp = handleList(req);
                break;
            case "stat":
                resp = handleStat(req);
                break;
            case "delete":
                resp = handleDelete(req);
                break;
            case "rename":
                resp = handleRename(req);
                break;
            case "mkdir":
                resp = handleMkdir(req);
                break;
            case "storage_scan":
                resp = handleStorageScan(req, isCancelled);
                break;
            case "thumbnail":
                resp = handleThumbnail(req);
                break;
            default:
                resp = errorResponse(req.requestId, op, req.path, "Unsupported file op: " + op);
                break;
        }
        if (responseSender != null) {
            responseSender.accept(resp);
        }
        return resp;
    }

    private OpalCastProtocol.FileOpResponseMsg handleList(OpalCastProtocol.FileOpRequestMsg req) {
        try {
            File canonicalRoot = storageRoot.getCanonicalFile();
            File dir = resolveSafePath(req.path);
            if (!dir.exists()) {
                return errorResponse(req.requestId, "list", req.path, "Directory not found");
            }
            if (!dir.isDirectory()) {
                return errorResponse(req.requestId, "list", toDisplayPath(dir), "Path is not a directory");
            }
            File[] children = dir.listFiles();
            List<OpalCastProtocol.FileEntry> entries = new ArrayList<>();
            if (children != null) {
                for (File child : children) {
                    if (child == null) continue;
                    // Skip symlinks pointing outside storage root
                    try {
                        File canonChild = child.getCanonicalFile();
                        FileTransferPathGuard.verifyInsideBaseDir(canonicalRoot, canonChild);
                        entries.add(buildEntry(child, canonChild));
                    } catch (IOException | SecurityException ignored) {
                    }
                }
            }
            entries.sort(Comparator
                    .comparing((OpalCastProtocol.FileEntry e) -> !e.isDir)
                    .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
            return new OpalCastProtocol.FileOpResponseMsg(
                    req.requestId, "list", true, "", toDisplayPath(dir), entries, null, 0L, "");
        } catch (IOException | SecurityException e) {
            return errorResponse(req.requestId, "list", req.path, safeErrorMessage(e));
        }
    }

    private OpalCastProtocol.FileOpResponseMsg handleStat(OpalCastProtocol.FileOpRequestMsg req) {
        try {
            File target = resolveSafePath(req.path);
            if (!target.exists()) {
                return errorResponse(req.requestId, "stat", req.path, "Path not found");
            }
            OpalCastProtocol.FileEntry entry = buildEntry(target, target);
            return new OpalCastProtocol.FileOpResponseMsg(
                    req.requestId, "stat", true, "", entry.path,
                    Collections.singletonList(entry), null, entry.size, "");
        } catch (IOException | SecurityException e) {
            return errorResponse(req.requestId, "stat", req.path, safeErrorMessage(e));
        }
    }

    private OpalCastProtocol.FileOpResponseMsg handleDelete(OpalCastProtocol.FileOpRequestMsg req) {
        try {
            File canonicalRoot = storageRoot.getCanonicalFile();
            Path rawTarget = resolveUnfollowedPath(req.path, canonicalRoot);
            if (rawTarget.equals(canonicalRoot.toPath())) {
                return errorResponse(req.requestId, "delete", toDisplayPath(canonicalRoot), "Cannot delete storage root");
            }
            if (Files.isSymbolicLink(rawTarget)) {
                String displayPath = toUnfollowedDisplayPath(canonicalRoot, rawTarget);
                Files.delete(rawTarget);
                return new OpalCastProtocol.FileOpResponseMsg(
                        req.requestId, "delete", true, "", displayPath, Collections.emptyList(), null, 0L, "");
            }
            File target = rawTarget.toFile().getCanonicalFile();
            FileTransferPathGuard.verifyInsideBaseDir(canonicalRoot, target);
            if (!target.exists()) {
                return errorResponse(req.requestId, "delete", req.path, "Path not found");
            }
            String displayPath = toDisplayPath(target);
            if (!deleteRecursivelySafe(canonicalRoot, target)) {
                return errorResponse(req.requestId, "delete", displayPath, "Failed to delete target");
            }
            return new OpalCastProtocol.FileOpResponseMsg(
                    req.requestId, "delete", true, "", displayPath, Collections.emptyList(), null, 0L, "");
        } catch (IOException | SecurityException e) {
            return errorResponse(req.requestId, "delete", req.path, safeErrorMessage(e));
        }
    }

    /**
     * Resolves {@code rawPath} while canonicalizing ONLY parent directories (keeping the final
     * path segment un-dereferenced) so symlink deletion unlinks the symlink itself rather than
     * following internal symlinks to real files/directories or failing on dangling symlinks.
     */
    private Path resolveUnfollowedPath(String rawPath, File canonicalRoot) throws IOException {
        if (rawPath == null) {
            return canonicalRoot.toPath();
        }
        if (rawPath.indexOf('\0') >= 0) {
            throw new SecurityException("Path contains null byte");
        }
        String normalized = rawPath.replace('\\', '/').trim();
        if (normalized.isEmpty() || "/".equals(normalized)) {
            return canonicalRoot.toPath();
        }
        for (String seg : normalized.split("/")) {
            if ("..".equals(seg)) {
                throw new SecurityException("Path traversal '..' blocked: " + rawPath);
            }
        }
        String relative;
        if (normalized.startsWith("/")) {
            relative = stripAllowedRootPrefix(normalized, canonicalRoot);
            if (relative == null) {
                throw new SecurityException("Path outside storage root: " + rawPath);
            }
        } else {
            relative = normalized;
        }
        List<String> cleanSegs = new ArrayList<>();
        for (String seg : relative.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                throw new SecurityException("Path traversal '..' blocked: " + rawPath);
            }
            cleanSegs.add(seg);
        }
        if (cleanSegs.isEmpty()) {
            return canonicalRoot.toPath();
        }
        File parent = canonicalRoot;
        for (int i = 0; i < cleanSegs.size() - 1; i++) {
            parent = new File(parent, cleanSegs.get(i));
        }
        verifyExistingAncestorsInsideRoot(canonicalRoot, parent);
        File canonicalParent = parent.getCanonicalFile();
        FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalRoot, canonicalParent);
        return canonicalParent.toPath().resolve(cleanSegs.get(cleanSegs.size() - 1));
    }

    private String toUnfollowedDisplayPath(File canonicalRoot, Path rawTarget) throws IOException {
        Path parent = rawTarget.getParent();
        String basePrefix = (parent != null)
                ? toDisplayPath(parent.toFile())
                : toDisplayPath(canonicalRoot);
        String leaf = (rawTarget.getFileName() != null) ? rawTarget.getFileName().toString() : "";
        if (leaf.isEmpty()) return basePrefix;
        return basePrefix.endsWith("/") ? (basePrefix + leaf) : (basePrefix + "/" + leaf);
    }

    private OpalCastProtocol.FileOpResponseMsg handleRename(OpalCastProtocol.FileOpRequestMsg req) {
        try {
            if (req.newPath == null || req.newPath.trim().isEmpty()) {
                return errorResponse(req.requestId, "rename", req.path, "Missing newPath");
            }
            File canonicalRoot = storageRoot.getCanonicalFile();
            File src = resolveSafePath(req.path);
            File dst = resolveSafePath(req.newPath);
            if (src.equals(canonicalRoot) || dst.equals(canonicalRoot)) {
                return errorResponse(req.requestId, "rename", req.path, "Cannot rename storage root");
            }
            if (!src.exists()) {
                return errorResponse(req.requestId, "rename", req.path, "Source path not found");
            }
            if (dst.exists()) {
                return errorResponse(req.requestId, "rename", req.newPath, "Destination already exists");
            }
            File dstParent = dst.getParentFile();
            if (dstParent != null && !dstParent.exists() && !dstParent.mkdirs()) {
                return errorResponse(req.requestId, "rename", req.newPath, "Cannot create destination parent");
            }
            if (dstParent != null) {
                FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalRoot, dstParent.getCanonicalFile());
            }
            if (!src.renameTo(dst)) {
                Files.move(src.toPath(), dst.toPath());
            }
            String displayDst = toDisplayPath(dst);
            return new OpalCastProtocol.FileOpResponseMsg(
                    req.requestId, "rename", true, "", displayDst, Collections.emptyList(), null, 0L, "");
        } catch (IOException | SecurityException e) {
            return errorResponse(req.requestId, "rename", req.path, safeErrorMessage(e));
        }
    }

    private OpalCastProtocol.FileOpResponseMsg handleMkdir(OpalCastProtocol.FileOpRequestMsg req) {
        try {
            File canonicalRoot = storageRoot.getCanonicalFile();
            File target = resolveSafePath(req.path);
            if (target.exists()) {
                if (target.isDirectory()) {
                    return new OpalCastProtocol.FileOpResponseMsg(
                            req.requestId, "mkdir", true, "", toDisplayPath(target),
                            Collections.emptyList(), null, 0L, "");
                }
                return errorResponse(req.requestId, "mkdir", req.path, "A file already exists at that path");
            }
            if (!target.mkdirs()) {
                return errorResponse(req.requestId, "mkdir", req.path, "Failed to create directory");
            }
            FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalRoot, target.getCanonicalFile());
            return new OpalCastProtocol.FileOpResponseMsg(
                    req.requestId, "mkdir", true, "", toDisplayPath(target),
                    Collections.emptyList(), null, 0L, "");
        } catch (IOException | SecurityException e) {
            return errorResponse(req.requestId, "mkdir", req.path, safeErrorMessage(e));
        }
    }

    private OpalCastProtocol.FileOpResponseMsg handleStorageScan(
            OpalCastProtocol.FileOpRequestMsg req, BooleanSupplier isCancelled) {
        try {
            File canonicalRoot = storageRoot.getCanonicalFile();
            File scanRoot = (req.path == null || req.path.trim().isEmpty())
                    ? canonicalRoot : resolveSafePath(req.path);
            if (!scanRoot.exists() || !scanRoot.isDirectory()) {
                return errorResponse(req.requestId, "storage_scan", req.path, "Scan root is not a directory");
            }
            OpalCastProtocol.StorageReport report = RemoteStorageAnalyzer.scanStorage(
                    canonicalRoot, scanRoot, req.maxDepth, this::toDisplayPath,
                    this.appStorageStatsProvider, isCancelled);
            return new OpalCastProtocol.FileOpResponseMsg(
                    req.requestId, "storage_scan", true, "", toDisplayPath(scanRoot),
                    Collections.emptyList(), report, report.usedBytes, "");
        } catch (IOException | SecurityException e) {
            return errorResponse(req.requestId, "storage_scan", req.path, safeErrorMessage(e));
        }
    }

    private OpalCastProtocol.FileOpResponseMsg handlePull(
            OpalCastProtocol.FileOpRequestMsg req,
            Consumer<OpalCastProtocol.FileOpResponseMsg> responseSender,
            PullChunkSender pullSender,
            BooleanSupplier isCancelled) {
        File file;
        String displayPath;
        long totalSize;
        try {
            file = resolveSafePath(req.path);
            if (!file.exists()) {
                OpalCastProtocol.FileOpResponseMsg err = errorResponse(req.requestId, "pull", req.path, "File not found");
                if (responseSender != null) responseSender.accept(err);
                return err;
            }
            if (!file.isFile() || !file.canRead()) {
                OpalCastProtocol.FileOpResponseMsg err = errorResponse(req.requestId, "pull", toDisplayPath(file), "Path is not a readable file");
                if (responseSender != null) responseSender.accept(err);
                return err;
            }
            displayPath = toDisplayPath(file);
            totalSize = Math.max(0L, file.length());
        } catch (IOException | SecurityException e) {
            OpalCastProtocol.FileOpResponseMsg err = errorResponse(req.requestId, "pull", req.path, safeErrorMessage(e));
            if (responseSender != null) responseSender.accept(err);
            return err;
        }

        // First confirm pull start via 0x1B FILE_OP_RESPONSE
        OpalCastProtocol.FileOpResponseMsg okResp = new OpalCastProtocol.FileOpResponseMsg(
                req.requestId, "pull", true, "", displayPath, Collections.emptyList(), null, totalSize, "");
        if (responseSender != null) {
            responseSender.accept(okResp);
        }
        if (pullSender == null) {
            return okResp;
        }

        int transferId = (req.transferId != 0) ? req.transferId : req.requestId;
        streamPullChunks(transferId, file, totalSize, pullSender, isCancelled);
        return okResp;
    }

    private void streamPullChunks(
            int transferId,
            File file,
            long totalSize,
            PullChunkSender pullSender,
            BooleanSupplier isCancelled) {
        String name = file.getName();
        try (FileInputStream fis = new FileInputStream(file)) {
            if (totalSize == 0L) {
                byte[] emptyFrame = OpalCastProtocol.encodeFilePullChunk(
                        transferId,
                        OpalCastProtocol.FILE_FLAG_START | OpalCastProtocol.FILE_FLAG_END,
                        0L, name, OpalCastProtocol.EMPTY_BYTES, 0, 0);
                pullSender.sendChunk(emptyFrame);
                return;
            }

            byte[] buf = new byte[PULL_CHUNK_SIZE];
            boolean isFirst = true;
            long remaining = totalSize;
            while (remaining > 0L) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    sendPullAbort(transferId, "Transfer cancelled", pullSender);
                    return;
                }
                int toRead = (int) Math.min(buf.length, remaining);
                int read = fis.read(buf, 0, toRead);
                if (read < 0) {
                    // File shrank while reading: terminate cleanly with END frame
                    byte[] endFrame = OpalCastProtocol.encodeFilePullChunk(
                            transferId,
                            (isFirst ? OpalCastProtocol.FILE_FLAG_START : 0) | OpalCastProtocol.FILE_FLAG_END,
                            totalSize,
                            isFirst ? name : "",
                            OpalCastProtocol.EMPTY_BYTES, 0, 0);
                    pullSender.sendChunk(endFrame);
                    return;
                }
                remaining -= read;
                int flags = OpalCastProtocol.FILE_FLAG_DATA;
                if (isFirst) flags |= OpalCastProtocol.FILE_FLAG_START;
                if (remaining == 0L) flags |= OpalCastProtocol.FILE_FLAG_END;

                byte[] chunkPayload = OpalCastProtocol.encodeFilePullChunk(
                        transferId, flags, totalSize, isFirst ? name : "", buf, 0, read);
                if (!pullSender.sendChunk(chunkPayload)) {
                    return;
                }
                isFirst = false;
            }
        } catch (IOException | RuntimeException e) {
            sendPullAbort(transferId, "Read error: " + safeErrorMessage(e), pullSender);
        }
    }

    private static void sendPullAbort(int transferId, String reason, PullChunkSender pullSender) {
        try {
            byte[] errBytes = (reason != null ? reason : "aborted").getBytes(StandardCharsets.UTF_8);
            byte[] payload = OpalCastProtocol.encodeFilePullChunk(
                    transferId, OpalCastProtocol.FILE_FLAG_ABORT, 0L, "", errBytes, 0, errBytes.length);
            pullSender.sendChunk(payload);
        } catch (OpalCastProtocol.ProtocolException ignored) {
        }
    }

    private OpalCastProtocol.FileOpResponseMsg handleThumbnail(OpalCastProtocol.FileOpRequestMsg req) {
        try {
            File file = resolveSafePath(req.path);
            if (!file.exists() || !file.isFile() || !file.canRead()) {
                return errorResponse(req.requestId, "thumbnail", req.path, "Image file not found");
            }
            String displayPath = toDisplayPath(file);
            if (!"images".equals(categorizeFileName(file.getName()))) {
                return errorResponse(req.requestId, "thumbnail", displayPath, "File is not a supported image");
            }
            String b64 = thumbnailGenerator.generateBase64(
                    file, MAX_THUMBNAIL_EDGE_PX, MAX_THUMBNAIL_BASE64_BYTES);
            if (b64 == null || b64.isEmpty()) {
                return errorResponse(req.requestId, "thumbnail", displayPath, "Failed to decode image thumbnail");
            }
            return new OpalCastProtocol.FileOpResponseMsg(
                    req.requestId, "thumbnail", true, "", displayPath,
                    Collections.emptyList(), null, file.length(), b64);
        } catch (IOException | RuntimeException e) {
            return errorResponse(req.requestId, "thumbnail", req.path, safeErrorMessage(e));
        }
    }

    private OpalCastProtocol.FileEntry buildEntry(File rawFile, File canonicalFile) throws IOException {
        boolean isDir = canonicalFile.isDirectory();
        long size = isDir ? 0L : Math.max(0L, canonicalFile.length());
        long mod = Math.max(0L, canonicalFile.lastModified());
        String mime = isDir ? "inode/directory" : guessMimeType(rawFile.getName());
        return new OpalCastProtocol.FileEntry(
                rawFile.getName(), toDisplayPath(canonicalFile), isDir, size, mod, mime);
    }

    private static boolean deleteRecursivelySafe(File canonicalRoot, File target) throws IOException {
        Path p = target.toPath();
        if (Files.isSymbolicLink(p)) {
            return target.delete();
        }
        FileTransferPathGuard.verifyInsideBaseDir(canonicalRoot, target.getCanonicalFile());
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child != null && !deleteRecursivelySafe(canonicalRoot, child)) {
                        return false;
                    }
                }
            }
        }
        return target.delete();
    }

    private String stripAllowedRootPrefix(String normalizedPath, File canonicalRoot) {
        String canonRoot = trimTrailingSlash(canonicalRoot.getPath().replace('\\', '/'));
        List<String> prefixes = new ArrayList<>(4);
        prefixes.add("/sdcard");
        prefixes.add("/storage/emulated/0");
        if (displayRootPrefix != null) prefixes.add(displayRootPrefix);
        prefixes.add(canonRoot);
        String absRoot = trimTrailingSlash(storageRoot.getAbsolutePath().replace('\\', '/'));
        prefixes.add(absRoot);

        for (String pref : prefixes) {
            if (pref == null || pref.isEmpty()) continue;
            if (normalizedPath.equals(pref)) {
                return "";
            }
            String withSlash = pref.endsWith("/") ? pref : (pref + "/");
            if (normalizedPath.startsWith(withSlash)) {
                return normalizedPath.substring(withSlash.length());
            }
        }
        return null;
    }

    private static void verifyExistingAncestorsInsideRoot(File canonicalRoot, File candidate) throws IOException {
        File cur = candidate;
        while (cur != null && !cur.exists() && !Files.isSymbolicLink(cur.toPath())) {
            cur = cur.getParentFile();
        }
        if (cur != null) {
            FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalRoot, cur.getCanonicalFile());
        }
    }

    private static String trimTrailingSlash(String s) {
        if (s == null || s.length() <= 1) return s;
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    public static String categorizeFileName(String fileName) {
        String ext = extractExtension(fileName);
        if (IMAGE_EXTS.contains(ext)) return "images";
        if (VIDEO_EXTS.contains(ext)) return "videos";
        if (AUDIO_EXTS.contains(ext)) return "audio";
        if (DOCUMENT_EXTS.contains(ext)) return "documents";
        if (APK_EXTS.contains(ext)) return "apks";
        return "other";
    }

    public static String guessMimeType(String fileName) {
        String ext = extractExtension(fileName);
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "heic":
            case "heif":
                return "image/heif";
            case "mp4":
            case "m4v":
                return "video/mp4";
            case "mkv":
                return "video/x-matroska";
            case "mov":
                return "video/quicktime";
            case "webm":
                return "video/webm";
            case "mp3":
                return "audio/mpeg";
            case "flac":
                return "audio/flac";
            case "wav":
                return "audio/wav";
            case "m4a":
            case "aac":
                return "audio/mp4";
            case "ogg":
            case "opus":
                return "audio/ogg";
            case "pdf":
                return "application/pdf";
            case "txt":
            case "md":
            case "log":
                return "text/plain";
            case "json":
                return "application/json";
            case "xml":
                return "application/xml";
            case "html":
            case "htm":
                return "text/html";
            case "zip":
                return "application/zip";
            case "apk":
            case "apks":
            case "apkm":
            case "xapk":
                return "application/vnd.android.package-archive";
            default:
                return "application/octet-stream";
        }
    }

    private static String extractExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot >= name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static OpalCastProtocol.FileOpResponseMsg errorResponse(
            int requestId, String op, String path, String error) {
        return new OpalCastProtocol.FileOpResponseMsg(
                requestId, op, false, error, path, Collections.emptyList(), null, 0L, "");
    }

    private static String safeErrorMessage(Throwable t) {
        String m = (t != null) ? t.getMessage() : null;
        return (m != null && !m.trim().isEmpty()) ? m.trim() : (t != null ? t.getClass().getSimpleName() : "error");
    }

    private static String encodeAndroidThumbnail(File imageFile, int maxEdgePx, int maxBase64Bytes) {
        Bitmap bmp = null;
        Bitmap scaled = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "";

            int sampleSize = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / (sampleSize * 2) >= maxEdgePx) {
                sampleSize *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            bmp = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
            if (bmp == null) return "";

            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int curMax = Math.max(w, h);
            Bitmap target = bmp;
            if (curMax > maxEdgePx) {
                float ratio = maxEdgePx / (float) curMax;
                int sw = Math.max(1, Math.round(w * ratio));
                int sh = Math.max(1, Math.round(h * ratio));
                scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true);
                target = scaled;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            for (int quality : new int[]{75, 55, 35}) {
                bos.reset();
                target.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                String b64 = Base64.getEncoder().encodeToString(bos.toByteArray());
                if (b64.length() <= maxBase64Bytes) {
                    return b64;
                }
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (scaled != null && scaled != bmp && !scaled.isRecycled()) scaled.recycle();
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
    }
}
