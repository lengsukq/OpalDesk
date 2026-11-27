package io.github.lengsukq.opaldesk.daemon.stream;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Pure-Java path-traversal defense and safe filename sanitizer for OpalCast file transfers.
 */
public final class FileTransferPathGuard {
    private FileTransferPathGuard() {}

    /**
     * Strictly sanitizes an incoming remote filename to prevent path traversal, null-byte injection,
     * and hidden/special path entries. Returns {@code null} if the name is unsafe or empty.
     */
    public static String sanitizeFileName(String raw) {
        if (raw == null) return null;
        if (raw.indexOf('\0') >= 0) return null;
        String normalized = raw.replace('\\', '/').trim();
        if (normalized.isEmpty()) return null;
        for (String seg : normalized.split("/")) {
            if ("..".equals(seg)) {
                return null;
            }
        }
        int lastSlash = normalized.lastIndexOf('/');
        String base = (lastSlash >= 0) ? normalized.substring(lastSlash + 1) : normalized;
        return sanitizeSingleSegment(base);
    }

    /**
     * Sanitizes a relative nested file path such as {@code "sub/dir/photo.png"} while rejecting
     * null bytes, path traversal ({@code ..}), hidden dot-segments, and excessive nesting.
     */
    public static String sanitizeRelativeSubpath(String raw) {
        if (raw == null || raw.indexOf('\0') >= 0) return null;
        String normalized = raw.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.endsWith("/")) return null;
        String[] parts = normalized.split("/");
        if (parts.length == 0 || parts.length > 16) return null;
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < parts.length; i++) {
            String cleanSeg = sanitizeSingleSegment(parts[i]);
            if (cleanSeg == null) return null;
            if (i > 0) out.append('/');
            out.append(cleanSeg);
        }
        return out.toString();
    }

    private static String sanitizeSingleSegment(String rawSegment) {
        if (rawSegment == null) return null;
        String base = rawSegment.trim();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base) || base.startsWith(".")) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(base.length(), 180));
        for (int i = 0; i < base.length() && sb.length() < 180; i++) {
            char c = base.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '/' || c == '\\' || c == ':'
                    || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned) || cleaned.startsWith(".")) {
            return null;
        }
        return cleaned;
    }

    public static File resolveUniqueSafeFile(File canonicalBase, String safeName) throws IOException {
        return resolveUniqueSafeFile(canonicalBase, canonicalBase, safeName);
    }

    public static File resolveUniqueSafeFile(File canonicalBase, File targetDir, String safeName) throws IOException {
        File candidate = new File(targetDir, safeName);
        verifyInsideBaseDir(canonicalBase, candidate);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = safeName.lastIndexOf('.');
        String stem = (dot > 0) ? safeName.substring(0, dot) : safeName;
        String ext = (dot > 0) ? safeName.substring(dot) : "";
        for (int i = 1; i <= 999; i++) {
            File alt = new File(targetDir, String.format(Locale.US, "%s (%d)%s", stem, i, ext));
            verifyInsideBaseDir(canonicalBase, alt);
            if (!alt.exists()) {
                return alt;
            }
        }
        throw new IOException("Too many duplicate file names for " + safeName);
    }

    public static void verifyInsideBaseDir(File canonicalBase, File target) throws IOException {
        String basePath = canonicalBase.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        String prefix = basePath.endsWith(File.separator) ? basePath : (basePath + File.separator);
        if (!targetPath.startsWith(prefix)) {
            throw new SecurityException("Path traversal blocked: " + targetPath);
        }
    }

    public static void verifyInsideOrEqualBaseDir(File canonicalBase, File target) throws IOException {
        String basePath = canonicalBase.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (basePath.equals(targetPath)) {
            return;
        }
        String prefix = basePath.endsWith(File.separator) ? basePath : (basePath + File.separator);
        if (!targetPath.startsWith(prefix)) {
            throw new SecurityException("Path traversal blocked: " + targetPath);
        }
    }
}
