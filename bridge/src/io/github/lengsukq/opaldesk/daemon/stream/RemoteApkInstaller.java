package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Rootless ({@code uid=2000}) wireless APK / APKS / APKM / XAPK installer for OpalCast v1.2
 * ({@code 0x34 APK_INSTALL_REQUEST} / {@code 0x1D APK_INSTALL_STATUS}).
 *
 * <p>Validates input paths against canonical allowed roots, safely extracts multi-APK bundles
 * with Zip Slip and actual-byte decompression-bomb guards, installs via timeout-bounded
 * {@code pm install-create} / {@code pm install-write} / {@code pm install-commit}, and always
 * cleans up temporary work directories in {@code finally}.
 */
public final class RemoteApkInstaller {
    public static final String DEFAULT_WORK_ROOT = "/data/local/tmp/opaldesk_apk_work";
    public static final String DEFAULT_STAGING_ROOT = FileTransferReceiver.DEFAULT_APK_STAGING_DIR;
    public static final long MAX_TOTAL_EXTRACTED_BYTES = 2L * 1024L * 1024L * 1024L; // 2 GiB
    public static final int MAX_ZIP_ENTRIES = 2048;
    public static final long PM_CREATE_TIMEOUT_MS = 30_000L;
    public static final long PM_WRITE_TIMEOUT_MS = 180_000L;
    public static final long PM_COMMIT_TIMEOUT_MS = 180_000L;
    public static final long PM_ABANDON_TIMEOUT_MS = 10_000L;

    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("\\[(\\d+)]");

    public interface CommandRunner {
        ShellCommand.Result run(String[] cmd, long timeoutMs);
    }

    public interface StatusListener {
        void onStatus(OpalCastProtocol.ApkInstallStatusMsg status);
    }

    public static final class ObbEntry {
        public final String packageName;
        public final String fileName;
        public final File extractedFile;

        public ObbEntry(String packageName, String fileName, File extractedFile) {
            this.packageName = packageName;
            this.fileName = fileName;
            this.extractedFile = extractedFile;
        }
    }

    public static final class ExtractedBundle {
        public final List<File> apkFiles;
        public final List<ObbEntry> obbEntries;
        public final String detectedPackageName;
        public final long totalExtractedBytes;

        public ExtractedBundle(
                List<File> apkFiles,
                List<ObbEntry> obbEntries,
                String detectedPackageName,
                long totalExtractedBytes) {
            this.apkFiles = Collections.unmodifiableList(new ArrayList<>(apkFiles));
            this.obbEntries = Collections.unmodifiableList(new ArrayList<>(obbEntries));
            this.detectedPackageName = (detectedPackageName != null) ? detectedPackageName : "";
            this.totalExtractedBytes = totalExtractedBytes;
        }
    }

    private final List<File> allowedRoots;
    private final File workRootDir;
    private final File stagingDir;
    private final File obbStorageRoot;
    private final CommandRunner commandRunner;
    private final long maxExtractedBytes;

    public RemoteApkInstaller() {
        this(
                Arrays.asList(
                        new File(RemoteFileManager.DEFAULT_STORAGE_ROOT),
                        new File("/storage/emulated/0"),
                        new File(DEFAULT_STAGING_ROOT)),
                new File(DEFAULT_WORK_ROOT),
                new File(DEFAULT_STAGING_ROOT),
                new File(RemoteFileManager.DEFAULT_STORAGE_ROOT),
                ShellCommand::runWithTimeout,
                MAX_TOTAL_EXTRACTED_BYTES);
    }

    public RemoteApkInstaller(
            List<File> allowedRoots,
            File workRootDir,
            File stagingDir,
            File obbStorageRoot,
            CommandRunner commandRunner,
            long maxExtractedBytes) {
        this.allowedRoots = (allowedRoots != null) ? new ArrayList<>(allowedRoots) : Collections.emptyList();
        this.workRootDir = (workRootDir != null) ? workRootDir : new File(DEFAULT_WORK_ROOT);
        this.stagingDir = stagingDir;
        this.obbStorageRoot = (obbStorageRoot != null) ? obbStorageRoot : new File(RemoteFileManager.DEFAULT_STORAGE_ROOT);
        this.commandRunner = (commandRunner != null) ? commandRunner : ShellCommand::runWithTimeout;
        this.maxExtractedBytes = (maxExtractedBytes > 0L) ? maxExtractedBytes : MAX_TOTAL_EXTRACTED_BYTES;
    }

    /**
     * Validates that {@code rawPath} points to an existing readable regular file inside one of the
     * canonical {@link #allowedRoots}, rejecting traversal and symlink escapes.
     */
    public File validateAllowedPackageFile(String rawPath) throws IOException {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new SecurityException("Package filePath is empty");
        }
        if (rawPath.indexOf('\0') >= 0) {
            throw new SecurityException("Package filePath contains null byte");
        }
        String normalized = rawPath.replace('\\', '/').trim();
        for (String seg : normalized.split("/")) {
            if ("..".equals(seg)) {
                throw new SecurityException("Path traversal '..' blocked in filePath");
            }
        }

        File candidate = new File(normalized);
        File canonicalTarget = candidate.getCanonicalFile();
        boolean allowed = false;
        for (File root : allowedRoots) {
            if (root == null) continue;
            try {
                File canonicalRoot = root.getCanonicalFile();
                String rootPath = canonicalRoot.getPath();
                String prefix = rootPath.endsWith(File.separator) ? rootPath : (rootPath + File.separator);
                if (canonicalTarget.getPath().startsWith(prefix)) {
                    allowed = true;
                    break;
                }
            } catch (IOException ignored) {
            }
        }
        if (!allowed) {
            throw new SecurityException("Package path outside allowed directories: " + rawPath);
        }
        if (!canonicalTarget.exists() || !canonicalTarget.isFile() || !canonicalTarget.canRead()) {
            throw new IOException("Package file not found or unreadable: " + canonicalTarget.getName());
        }
        return canonicalTarget;
    }

    public static String resolveFormat(String requestedFormat, String fileName) {
        String fmt = (requestedFormat != null) ? requestedFormat.trim().toLowerCase(Locale.ROOT) : "";
        if (fmt.isEmpty() && fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot > 0 && dot < fileName.length() - 1) {
                fmt = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        if ("apk".equals(fmt) || "apks".equals(fmt) || "apkm".equals(fmt) || "xapk".equals(fmt)) {
            return fmt;
        }
        return "";
    }

    /**
     * Safely extracts a multi-APK bundle ({@code .apks}/{@code .apkm}/{@code .xapk}) into
     * {@code extractDir} with strict Zip Slip protection and actual decompressed byte caps.
     */
    public ExtractedBundle safeExtractBundle(File archiveFile, File extractDir, String format) throws IOException {
        if (!extractDir.exists() && !extractDir.mkdirs()) {
            throw new IOException("Cannot create extraction directory");
        }
        File canonicalExtractDir = extractDir.getCanonicalFile();
        List<File> apkFiles = new ArrayList<>();
        List<ObbEntry> obbEntries = new ArrayList<>();
        String detectedPackageName = "";
        long totalBytes = 0L;
        int entryCount = 0;
        byte[] buf = new byte[COPY_BUFFER_BYTES];

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(archiveFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new SecurityException("Archive exceeds max entry limit (" + MAX_ZIP_ENTRIES + ")");
                }
                String rawName = entry.getName();
                if (rawName == null || rawName.isEmpty()) continue;
                if (rawName.indexOf('\0') >= 0) {
                    throw new SecurityException("Zip entry contains null byte");
                }
                String normalized = rawName.replace('\\', '/');
                if (normalized.startsWith("__MACOSX/") || normalized.contains("/__MACOSX/")) {
                    continue;
                }
                if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
                    throw new SecurityException("Zip Slip blocked (absolute entry): " + rawName);
                }
                for (String seg : normalized.split("/")) {
                    if ("..".equals(seg)) {
                        throw new SecurityException("Zip Slip blocked (.. segment): " + rawName);
                    }
                }

                File outFile = new File(canonicalExtractDir, normalized);
                FileTransferPathGuard.verifyInsideBaseDir(canonicalExtractDir, outFile);

                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Failed to create directory: " + normalized);
                    }
                    continue;
                }

                String lower = normalized.toLowerCase(Locale.ROOT);
                boolean isApk = lower.endsWith(".apk");
                boolean isManifest = "xapk".equals(format) && "manifest.json".equals(lower);
                ObbSpec obbSpec = "xapk".equals(format) ? parseXapkObbPath(normalized) : null;
                if (!isApk && !isManifest && obbSpec == null) {
                    // Skip irrelevant metadata/icons inside bundles while still verifying entry names for Zip Slip
                    continue;
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create parent directory for " + normalized);
                }
                if (parent != null) {
                    FileTransferPathGuard.verifyInsideOrEqualBaseDir(canonicalExtractDir, parent.getCanonicalFile());
                }

                try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
                    int n;
                    while ((n = zis.read(buf)) >= 0) {
                        if (n == 0) continue;
                        totalBytes += n;
                        if (totalBytes > maxExtractedBytes) {
                            throw new SecurityException("Archive exceeds maximum extracted size limit (" + maxExtractedBytes + " bytes)");
                        }
                        fos.write(buf, 0, n);
                    }
                }

                if (isApk) {
                    apkFiles.add(outFile.getCanonicalFile());
                } else if (isManifest && outFile.length() <= 256 * 1024) {
                    detectedPackageName = parseXapkManifestPackageName(outFile);
                } else if (obbSpec != null) {
                    obbEntries.add(new ObbEntry(obbSpec.packageName, obbSpec.fileName, outFile.getCanonicalFile()));
                }
            }
        }

        if (apkFiles.isEmpty()) {
            throw new IOException("No .apk files found inside " + format.toUpperCase(Locale.ROOT) + " archive");
        }
        apkFiles.sort(Comparator
                .comparing((File f) -> !"base.apk".equalsIgnoreCase(f.getName()))
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return new ExtractedBundle(apkFiles, obbEntries, detectedPackageName, totalBytes);
    }

    public static String[] buildInstallCreateCommand(
            long totalApkBytes, boolean replaceExisting, boolean allowDowngrade) {
        List<String> cmd = new ArrayList<>(6);
        cmd.add("pm");
        cmd.add("install-create");
        if (replaceExisting) {
            cmd.add("-r");
        }
        if (allowDowngrade) {
            cmd.add("-d");
        }
        if (totalApkBytes > 0L) {
            cmd.add("-S");
            cmd.add(String.valueOf(totalApkBytes));
        }
        return cmd.toArray(new String[0]);
    }

    public static String[] buildInstallWriteCommand(
            int sessionId, long apkBytes, String splitName, String apkPath) {
        return new String[]{
                "pm", "install-write",
                "-S", String.valueOf(Math.max(0L, apkBytes)),
                String.valueOf(sessionId),
                splitName,
                apkPath
        };
    }

    public static String[] buildInstallCommitCommand(int sessionId) {
        return new String[]{"pm", "install-commit", String.valueOf(sessionId)};
    }

    public static String[] buildInstallAbandonCommand(int sessionId) {
        return new String[]{"pm", "install-abandon", String.valueOf(sessionId)};
    }

    public static int parseSessionId(String pmOutput) {
        if (pmOutput == null || pmOutput.isEmpty()) return -1;
        Matcher m = SESSION_ID_PATTERN.matcher(pmOutput);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    public boolean install(OpalCastProtocol.ApkInstallRequestMsg req, StatusListener listener) {
        if (req == null) {
            emit(listener, 0, "failed", 100, "", "Empty install request");
            return false;
        }
        final int installId = req.installId;
        File packageFile = null;
        File extractDir = null;
        int sessionId = -1;
        String pkgName = "";
        boolean committed = false;

        try {
            packageFile = validateAllowedPackageFile(req.filePath);
            String format = resolveFormat(req.format, packageFile.getName());
            if (format.isEmpty()) {
                emit(listener, installId, "failed", 100, "", "Unsupported package format: " + req.format);
                return false;
            }

            List<File> apksToInstall;
            List<ObbEntry> obbsToCopy = Collections.emptyList();

            if ("apk".equals(format)) {
                apksToInstall = Collections.singletonList(packageFile);
            } else {
                emit(listener, installId, "extracting", 15, "", "Extracting " + format.toUpperCase(Locale.ROOT) + " bundle...");
                if (!workRootDir.exists() && !workRootDir.mkdirs()) {
                    throw new IOException("Cannot create installer work directory");
                }
                extractDir = new File(
                        workRootDir.getCanonicalFile(),
                        "install_" + Integer.toUnsignedString(installId) + "_" + System.nanoTime());
                ExtractedBundle bundle = safeExtractBundle(packageFile, extractDir, format);
                apksToInstall = bundle.apkFiles;
                obbsToCopy = bundle.obbEntries;
                pkgName = bundle.detectedPackageName;
                emit(listener, installId, "extracting", 40, pkgName,
                        "Extracted " + apksToInstall.size() + " APK split(s)");
            }

            long totalApkBytes = 0L;
            for (File apk : apksToInstall) {
                totalApkBytes += Math.max(0L, apk.length());
            }
            if (totalApkBytes <= 0L) {
                throw new IOException("APK payload is empty");
            }

            emit(listener, installId, "installing", 45, pkgName, "Creating install session...");
            ShellCommand.Result createRes = commandRunner.run(
                    buildInstallCreateCommand(totalApkBytes, req.replaceExisting, req.allowDowngrade),
                    PM_CREATE_TIMEOUT_MS);
            if (!createRes.isSuccess()) {
                throw new IOException("pm install-create failed: " + summarizeOutput(createRes));
            }
            sessionId = parseSessionId(createRes.output);
            if (sessionId < 0) {
                throw new IOException("Unable to parse pm session ID from: " + summarizeOutput(createRes));
            }

            int totalSplits = apksToInstall.size();
            for (int i = 0; i < totalSplits; i++) {
                File apk = apksToInstall.get(i);
                String safeSplitName = buildSafeSplitName(i, apk.getName());
                int pct = 50 + Math.round((38f * i) / Math.max(1, totalSplits));
                emit(listener, installId, "installing", pct, pkgName,
                        "Writing split " + (i + 1) + "/" + totalSplits + ": " + apk.getName());
                ShellCommand.Result writeRes = commandRunner.run(
                        buildInstallWriteCommand(sessionId, apk.length(), safeSplitName, apk.getCanonicalPath()),
                        PM_WRITE_TIMEOUT_MS);
                if (!writeRes.isSuccess()) {
                    throw new IOException("pm install-write failed (" + apk.getName() + "): " + summarizeOutput(writeRes));
                }
            }

            emit(listener, installId, "installing", 92, pkgName, "Committing installation...");
            ShellCommand.Result commitRes = commandRunner.run(
                    buildInstallCommitCommand(sessionId), PM_COMMIT_TIMEOUT_MS);
            if (!commitRes.isSuccess() || !commitRes.output.contains("Success")) {
                throw new IOException("pm install-commit failed: " + summarizeOutput(commitRes));
            }
            committed = true;

            if (!obbsToCopy.isEmpty()) {
                installXapkObbsSafe(obbsToCopy);
            }

            emit(listener, installId, "succeeded", 100, pkgName, "Installation succeeded");
            return true;
        } catch (IOException | RuntimeException e) {
            if (sessionId >= 0 && !committed) {
                try {
                    commandRunner.run(buildInstallAbandonCommand(sessionId), PM_ABANDON_TIMEOUT_MS);
                } catch (RuntimeException ignored) {
                }
            }
            String errMsg = (e.getMessage() != null && !e.getMessage().trim().isEmpty())
                    ? e.getMessage().trim() : e.getClass().getSimpleName();
            emit(listener, installId, "failed", 100, pkgName, errMsg);
            return false;
        } finally {
            if (extractDir != null) {
                deleteDirectoryQuietly(extractDir);
            }
            cleanupStagedFileIfApplicable(packageFile);
        }
    }

    private void installXapkObbsSafe(List<ObbEntry> obbs) throws IOException {
        if (obbStorageRoot == null) return;
        File obbBase = new File(obbStorageRoot, "Android/obb");
        if (!obbBase.exists() && !obbBase.mkdirs()) {
            throw new IOException("Cannot create Android/obb directory");
        }
        File canonicalObbBase = obbBase.getCanonicalFile();
        for (ObbEntry obb : obbs) {
            if (!DaemonConfig.PackagePolicy.isValidPackageName(obb.packageName)) continue;
            String safeFile = FileTransferPathGuard.sanitizeFileName(obb.fileName);
            if (safeFile == null || !safeFile.toLowerCase(Locale.ROOT).endsWith(".obb")) continue;
            File pkgDir = new File(canonicalObbBase, obb.packageName);
            FileTransferPathGuard.verifyInsideBaseDir(canonicalObbBase, pkgDir);
            if (!pkgDir.exists() && !pkgDir.mkdirs()) {
                throw new IOException("Cannot create OBB directory for " + obb.packageName);
            }
            File dst = new File(pkgDir.getCanonicalFile(), safeFile);
            FileTransferPathGuard.verifyInsideBaseDir(canonicalObbBase, dst);
            copyFile(obb.extractedFile, dst);
        }
    }

    private void cleanupStagedFileIfApplicable(File packageFile) {
        if (packageFile == null || stagingDir == null) return;
        try {
            File canonicalStage = stagingDir.getCanonicalFile();
            File canonicalPkg = packageFile.getCanonicalFile();
            String prefix = canonicalStage.getPath().endsWith(File.separator)
                    ? canonicalStage.getPath()
                    : (canonicalStage.getPath() + File.separator);
            if (canonicalPkg.getPath().startsWith(prefix) && canonicalPkg.isFile()) {
                if (!canonicalPkg.delete()) {
                    System.err.println("OPALDESK_APK_STAGE_CLEANUP_WARN " + canonicalPkg.getName());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static final class ObbSpec {
        final String packageName;
        final String fileName;

        ObbSpec(String packageName, String fileName) {
            this.packageName = packageName;
            this.fileName = fileName;
        }
    }

    private static ObbSpec parseXapkObbPath(String normalizedEntry) {
        String lower = normalizedEntry.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("android/obb/") || !lower.endsWith(".obb")) return null;
        String[] parts = normalizedEntry.split("/");
        if (parts.length != 4) return null;
        String pkg = parts[2].trim();
        String fileName = FileTransferPathGuard.sanitizeFileName(parts[3]);
        if (!DaemonConfig.PackagePolicy.isValidPackageName(pkg) || fileName == null) return null;
        return new ObbSpec(pkg, fileName);
    }

    private static String parseXapkManifestPackageName(File manifestFile) {
        try {
            byte[] bytes = Files.readAllBytes(manifestFile.toPath());
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
            String pkg = obj.getString("package_name", "").trim();
            return DaemonConfig.PackagePolicy.isValidPackageName(pkg) ? pkg : "";
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static String buildSafeSplitName(int index, String rawName) {
        String clean = FileTransferPathGuard.sanitizeFileName(rawName);
        if (clean == null) {
            return "split_" + index + ".apk";
        }
        StringBuilder sb = new StringBuilder(clean.length() + 12);
        sb.append(index).append('_');
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] buf = new byte[COPY_BUFFER_BYTES];
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst, false)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static void deleteDirectoryQuietly(File dir) {
        if (dir == null || (!dir.exists() && !Files.isSymbolicLink(dir.toPath()))) return;
        Path p = dir.toPath();
        if (!Files.isSymbolicLink(p) && dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoryQuietly(child);
                }
            }
        }
        if (!dir.delete() && dir.exists()) {
            System.err.println("OPALDESK_APK_TMP_DELETE_WARN " + dir.getAbsolutePath());
        }
    }

    private static String summarizeOutput(ShellCommand.Result res) {
        if (res == null) return "no result";
        if (res.timedOut) return "command timed out";
        String out = res.output != null ? res.output.trim() : "";
        if (out.isEmpty()) return "exitCode=" + res.exitCode;
        return out.length() > 300 ? out.substring(0, 300) : out;
    }

    private static void emit(
            StatusListener listener,
            int installId,
            String stage,
            int progress,
            String packageName,
            String message) {
        if (listener != null) {
            listener.onStatus(new OpalCastProtocol.ApkInstallStatusMsg(
                    installId, stage, progress, packageName, message));
        }
    }
}
