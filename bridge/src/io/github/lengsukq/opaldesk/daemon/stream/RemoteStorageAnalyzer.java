package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Storage analyzer and {@code dumpsys diskstats} parser extracted from {@link RemoteFileManager}
 * to isolate recursive filesystem accounting and per-app disk usage parsing from file CRUD/streaming.
 */
public final class RemoteStorageAnalyzer {
    private static final long DISKSTATS_TIMEOUT_MS = 4_000L;

    @FunctionalInterface
    public interface DisplayPathMapper {
        String toDisplayPath(File target) throws IOException;
    }

    private static final class CategoryAcc {
        long bytes = 0L;
        int count = 0;
    }

    private static final class DirAcc {
        long bytes = 0L;
        int fileCount = 0;
    }

    private RemoteStorageAnalyzer() {}

    /**
     * Creates the default {@code uid=2000} app storage stats provider backed by {@code dumpsys diskstats}.
     */
    public static RemoteFileManager.AppStorageStatsProvider createDefaultAppStorageProvider(
            Function<String, String> labelResolver) {
        return () -> {
            try {
                ShellCommand.Result res = ShellCommand.runWithTimeout(DISKSTATS_TIMEOUT_MS, "dumpsys", "diskstats");
                if (!res.isSuccess() || res.output.isEmpty()) {
                    return Collections.emptyList();
                }
                return parseDumpsysDiskstats(res.output, labelResolver);
            } catch (RuntimeException ignored) {
                return Collections.emptyList();
            }
        };
    }

    /**
     * Walks {@code scanRoot} up to {@code maxDepth} (never following symlinks) and builds an
     * aggregated {@link OpalCastProtocol.StorageReport} including file categories, largest files,
     * top 1-2 level folders, and optional per-app disk usage stats.
     */
    public static OpalCastProtocol.StorageReport scanStorage(
            File canonicalRoot,
            File scanRoot,
            int requestedMaxDepth,
            DisplayPathMapper displayPathMapper,
            RemoteFileManager.AppStorageStatsProvider appStorageStatsProvider,
            BooleanSupplier isCancelled) {
        int maxDepth = (requestedMaxDepth > 0)
                ? Math.min(RemoteFileManager.MAX_SCAN_MAX_DEPTH, requestedMaxDepth)
                : RemoteFileManager.DEFAULT_SCAN_MAX_DEPTH;

        Map<String, CategoryAcc> cats = new LinkedHashMap<>();
        cats.put("images", new CategoryAcc());
        cats.put("videos", new CategoryAcc());
        cats.put("audio", new CategoryAcc());
        cats.put("documents", new CategoryAcc());
        cats.put("apks", new CategoryAcc());
        cats.put("other", new CategoryAcc());

        PriorityQueue<OpalCastProtocol.LargeFileEntry> topHeap = new PriorityQueue<>(
                RemoteFileManager.MAX_LARGE_FILES + 1,
                Comparator.comparingLong(e -> e.size));
        List<OpalCastProtocol.FolderStorageEntry> folderEntries = new ArrayList<>();
        int[] visitedFiles = new int[]{0};

        walkForStorageScan(
                scanRoot, 0, maxDepth, cats, topHeap, folderEntries, visitedFiles, displayPathMapper, isCancelled);

        List<OpalCastProtocol.StorageCategory> catList = new ArrayList<>(cats.size());
        for (Map.Entry<String, CategoryAcc> entry : cats.entrySet()) {
            catList.add(new OpalCastProtocol.StorageCategory(
                    entry.getKey(), entry.getValue().bytes, entry.getValue().count));
        }

        List<OpalCastProtocol.LargeFileEntry> largeList = new ArrayList<>(topHeap);
        largeList.sort((a, b) -> Long.compare(b.size, a.size));

        folderEntries.sort((a, b) -> {
            int cmp = Long.compare(b.bytes, a.bytes);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(b.fileCount, a.fileCount);
            if (cmp != 0) return cmp;
            return a.path.compareTo(b.path);
        });
        List<OpalCastProtocol.FolderStorageEntry> topFolders =
                folderEntries.size() > RemoteFileManager.MAX_TOP_FOLDERS
                        ? new ArrayList<>(folderEntries.subList(0, RemoteFileManager.MAX_TOP_FOLDERS))
                        : folderEntries;

        List<OpalCastProtocol.AppStorageEntry> appList = collectSortedAppStorage(appStorageStatsProvider, isCancelled);

        long totalBytes = Math.max(0L, canonicalRoot.getTotalSpace());
        long freeBytes = Math.max(0L, canonicalRoot.getUsableSpace());
        long usedBytes = Math.max(0L, totalBytes - freeBytes);
        return new OpalCastProtocol.StorageReport(
                totalBytes, freeBytes, usedBytes, catList, largeList, topFolders, appList);
    }

    private static List<OpalCastProtocol.AppStorageEntry> collectSortedAppStorage(
            RemoteFileManager.AppStorageStatsProvider provider,
            BooleanSupplier isCancelled) {
        if (provider == null || (isCancelled != null && isCancelled.getAsBoolean())) {
            return Collections.emptyList();
        }
        try {
            List<OpalCastProtocol.AppStorageEntry> rawApps = provider.collectAppStorage();
            if (rawApps == null || rawApps.isEmpty()) {
                return Collections.emptyList();
            }
            List<OpalCastProtocol.AppStorageEntry> sortedApps = new ArrayList<>(rawApps);
            sortedApps.sort((a, b) -> {
                int cmp = Long.compare(b.totalBytes, a.totalBytes);
                return (cmp != 0) ? cmp : a.pkg.compareTo(b.pkg);
            });
            return sortedApps.size() > RemoteFileManager.MAX_APP_STORAGE_ENTRIES
                    ? new ArrayList<>(sortedApps.subList(0, RemoteFileManager.MAX_APP_STORAGE_ENTRIES))
                    : sortedApps;
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private static DirAcc walkForStorageScan(
            File dir,
            int depth,
            int maxDepth,
            Map<String, CategoryAcc> cats,
            PriorityQueue<OpalCastProtocol.LargeFileEntry> topHeap,
            List<OpalCastProtocol.FolderStorageEntry> folderEntries,
            int[] visitedFiles,
            DisplayPathMapper displayPathMapper,
            BooleanSupplier isCancelled) {
        DirAcc self = new DirAcc();
        if (depth > maxDepth || visitedFiles[0] >= RemoteFileManager.MAX_SCAN_FILES) return self;
        if (isCancelled != null && isCancelled.getAsBoolean()) return self;
        File[] children = dir.listFiles();
        if (children == null) return self;
        for (File child : children) {
            if (visitedFiles[0] >= RemoteFileManager.MAX_SCAN_FILES) return self;
            if (isCancelled != null && isCancelled.getAsBoolean()) return self;
            if (child == null) continue;
            Path childPath = child.toPath();
            // Never traverse symlinks during storage scan to avoid loops & escapes
            if (Files.isSymbolicLink(childPath)) continue;
            if (Files.isDirectory(childPath, LinkOption.NOFOLLOW_LINKS)) {
                int childDepth = depth + 1;
                if (depth < maxDepth) {
                    DirAcc sub = walkForStorageScan(
                            child, childDepth, maxDepth, cats, topHeap,
                            folderEntries, visitedFiles, displayPathMapper, isCancelled);
                    self.bytes += sub.bytes;
                    self.fileCount += sub.fileCount;
                    if (childDepth >= 1 && childDepth <= 2 && (sub.bytes > 0L || sub.fileCount > 0)) {
                        try {
                            String dispPath = displayPathMapper.toDisplayPath(child);
                            folderEntries.add(new OpalCastProtocol.FolderStorageEntry(
                                    dispPath, child.getName(), sub.bytes, sub.fileCount));
                        } catch (IOException | SecurityException ignored) {
                            // Skip directory if canonicalization escapes storage root.
                        }
                    }
                }
            } else if (Files.isRegularFile(childPath, LinkOption.NOFOLLOW_LINKS)) {
                visitedFiles[0]++;
                long sz = Math.max(0L, child.length());
                self.bytes += sz;
                self.fileCount++;
                String catKey = RemoteFileManager.categorizeFileName(child.getName());
                CategoryAcc acc = cats.get(catKey);
                if (acc != null) {
                    acc.bytes += sz;
                    acc.count++;
                }
                if (sz > 0L && (topHeap.size() < RemoteFileManager.MAX_LARGE_FILES || sz > topHeap.peek().size)) {
                    String dispPath;
                    try {
                        dispPath = displayPathMapper.toDisplayPath(child);
                    } catch (IOException | SecurityException ignored) {
                        continue;
                    }
                    topHeap.offer(new OpalCastProtocol.LargeFileEntry(
                            child.getName(), dispPath, sz, child.lastModified()));
                    if (topHeap.size() > RemoteFileManager.MAX_LARGE_FILES) {
                        topHeap.poll();
                    }
                }
            }
        }
        return self;
    }

    /**
     * Parses AOSP/OEM {@code dumpsys diskstats} output ({@code Package Names}, {@code App Sizes},
     * {@code App Data Sizes}, {@code Cache Sizes}) into up to
     * {@link RemoteFileManager#MAX_APP_STORAGE_ENTRIES} entries sorted descending by {@code totalBytes}.
     */
    public static List<OpalCastProtocol.AppStorageEntry> parseDumpsysDiskstats(
            String output, Function<String, String> labelResolver) {
        if (output == null || output.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String pkgArray = extractBracketArray(output, "package names:");
        if (pkgArray == null) {
            return Collections.emptyList();
        }
        List<String> pkgs = parseStringArrayTokens(pkgArray);
        if (pkgs.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> appSizes = parseLongArrayTokens(extractBracketArray(output, "app sizes:"));
        List<Long> dataSizes = parseLongArrayTokens(extractBracketArray(output, "app data sizes:"));
        String cacheRaw = extractBracketArray(output, "cache sizes:");
        if (cacheRaw == null) {
            cacheRaw = extractBracketArray(output, "app cache sizes:");
        }
        List<Long> cacheSizes = parseLongArrayTokens(cacheRaw);

        List<OpalCastProtocol.AppStorageEntry> entries = new ArrayList<>(pkgs.size());
        for (int i = 0; i < pkgs.size(); i++) {
            String pkg = pkgs.get(i);
            if (pkg == null || pkg.isEmpty()) continue;
            long apk = (i < appSizes.size()) ? Math.max(0L, appSizes.get(i)) : 0L;
            long data = (i < dataSizes.size()) ? Math.max(0L, dataSizes.get(i)) : 0L;
            long cache = (i < cacheSizes.size()) ? Math.max(0L, cacheSizes.get(i)) : 0L;
            long total = safeAddNonNegative(safeAddNonNegative(apk, data), cache);
            String label = resolveLabelSafe(pkg, labelResolver);
            entries.add(new OpalCastProtocol.AppStorageEntry(pkg, label, apk, data, cache, total));
        }

        entries.sort((a, b) -> {
            int cmp = Long.compare(b.totalBytes, a.totalBytes);
            return (cmp != 0) ? cmp : a.pkg.compareTo(b.pkg);
        });
        if (entries.size() > RemoteFileManager.MAX_APP_STORAGE_ENTRIES) {
            return new ArrayList<>(entries.subList(0, RemoteFileManager.MAX_APP_STORAGE_ENTRIES));
        }
        return entries;
    }

    private static String extractBracketArray(String text, String lowerKeyWithColon) {
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(lowerKeyWithColon)) continue;
            int openIdx = trimmed.indexOf('[', lowerKeyWithColon.length());
            if (openIdx < 0) continue;
            StringBuilder acc = new StringBuilder(trimmed.substring(openIdx));
            int closeIdx = acc.indexOf("]");
            int j = i + 1;
            while (closeIdx < 0 && j < lines.length) {
                acc.append(' ').append(lines[j].trim());
                closeIdx = acc.indexOf("]");
                j++;
            }
            if (closeIdx > 0) {
                return acc.substring(1, closeIdx);
            }
            return acc.substring(1);
        }
        return null;
    }

    private static List<String> parseStringArrayTokens(String inner) {
        if (inner == null || inner.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        int len = inner.length();
        int i = 0;
        while (i < len) {
            while (i < len && (inner.charAt(i) == ' ' || inner.charAt(i) == '\t' || inner.charAt(i) == ',')) {
                i++;
            }
            if (i >= len) break;
            if (inner.charAt(i) == '"') {
                i++;
                StringBuilder tok = new StringBuilder();
                while (i < len) {
                    char c = inner.charAt(i++);
                    if (c == '\\' && i < len) {
                        tok.append(inner.charAt(i++));
                    } else if (c == '"') {
                        break;
                    } else {
                        tok.append(c);
                    }
                }
                out.add(tok.toString().trim());
            } else {
                int start = i;
                while (i < len && inner.charAt(i) != ',') {
                    i++;
                }
                String raw = inner.substring(start, i).trim();
                if (!raw.isEmpty() && !"null".equalsIgnoreCase(raw)) {
                    out.add(raw);
                }
            }
        }
        return out;
    }

    private static List<Long> parseLongArrayTokens(String inner) {
        if (inner == null || inner.trim().isEmpty()) return Collections.emptyList();
        String[] parts = inner.split(",");
        List<Long> out = new ArrayList<>(parts.length);
        for (String raw : parts) {
            String tok = raw.trim();
            if (tok.length() >= 2 && tok.startsWith("\"") && tok.endsWith("\"")) {
                tok = tok.substring(1, tok.length() - 1).trim();
            }
            if (tok.isEmpty()) continue;
            try {
                out.add(Math.max(0L, Long.parseLong(tok)));
            } catch (NumberFormatException ignored) {
                out.add(0L);
            }
        }
        return out;
    }

    private static long safeAddNonNegative(long a, long b) {
        long safeA = Math.max(0L, a);
        long safeB = Math.max(0L, b);
        long sum = safeA + safeB;
        return (sum < safeA) ? Long.MAX_VALUE : sum;
    }

    private static String resolveLabelSafe(String pkg, Function<String, String> labelResolver) {
        if (labelResolver != null) {
            try {
                String resolved = labelResolver.apply(pkg);
                if (resolved != null && !resolved.trim().isEmpty()) {
                    return resolved.trim();
                }
            } catch (RuntimeException ignored) {
                // Fall back to package name if label lookup fails.
            }
        }
        return pkg;
    }
}
