package io.github.lengsukq.opaldesk.daemon.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, thread-safe in-memory ring buffer capturing daemon stdout/stderr lines so the Helper
 * client can pull live daemon diagnostics over cert-pinned Binder IPC without direct filesystem
 * access to {@code /data/local/tmp/opaldesk.log}.
 */
public final class DaemonLogBuffer {
    public static final int DEFAULT_MAX_LINES = 500;
    public static final int MAX_STICKY_LINES = 8;
    public static final int MAX_LINE_CHARS = 1024;
    public static final int MAX_SNAPSHOT_CHARS = 96 * 1024;

    private static final class StickyEntry {
        final String formattedLine;
        final String rawPayload;

        StickyEntry(String formattedLine, String rawPayload) {
            this.formattedLine = formattedLine;
            this.rawPayload = rawPayload;
        }
    }

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> RING = new ArrayDeque<>(DEFAULT_MAX_LINES);
    private static final List<StickyEntry> STICKY_HEADERS = new ArrayList<>(MAX_STICKY_LINES);
    private static final AtomicBoolean TEE_INSTALLED = new AtomicBoolean(false);
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DaemonLogBuffer() {}

    /** Installs stdout/stderr tees once so all daemon prints are both persisted and buffered. */
    public static void installSystemStreamTee() {
        if (!TEE_INSTALLED.compareAndSet(false, true)) return;
        System.setOut(createTeePrintStream(System.out, false));
        System.setErr(createTeePrintStream(System.err, true));
    }

    /** Creates a UTF-8 {@link PrintStream} that mirrors completed lines into the ring buffer. */
    public static PrintStream createTeePrintStream(PrintStream delegate, boolean isError) {
        try {
            // Use String charset overload (API 1+) instead of Charset overload (API 33+ / Android 13+)
            return new PrintStream(new TeeOutputStream(delegate, isError), true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new PrintStream(new TeeOutputStream(delegate, isError), true);
        }
    }

    /** Records a single log line with wall-clock timestamp and severity marker. */
    public static void recordLine(boolean isError, String rawLine) {
        if (rawLine == null) return;
        String clean = stripTrailingCr(rawLine);
        if (clean.isEmpty()) return;
        if (clean.length() > MAX_LINE_CHARS) {
            clean = clean.substring(0, MAX_LINE_CHARS) + "...";
        }
        String level = isError ? "E" : "I";
        synchronized (LOCK) {
            String ts = TIME_FMT.format(new Date());
            String entry = ts + " " + level + "/Daemon: " + clean;
            while (RING.size() >= DEFAULT_MAX_LINES) {
                RING.pollFirst();
            }
            RING.addLast(entry);
        }
    }

    /**
     * Records a sticky startup capability line that survives ring eviction so long-running daemon
     * snapshots always retain startup environment metadata without duplicating when still in tail.
     */
    public static void recordStickyHeader(String rawLine) {
        if (rawLine == null) return;
        String clean = stripTrailingCr(rawLine);
        if (clean.isEmpty()) return;
        if (clean.length() > MAX_LINE_CHARS) {
            clean = clean.substring(0, MAX_LINE_CHARS) + "...";
        }
        synchronized (LOCK) {
            for (StickyEntry existing : STICKY_HEADERS) {
                if (existing.rawPayload.equals(clean)) return;
            }
            String ts = TIME_FMT.format(new Date());
            String formatted = ts + " I/Daemon: [STARTUP] " + clean;
            if (STICKY_HEADERS.size() >= MAX_STICKY_LINES) {
                STICKY_HEADERS.remove(0);
            }
            STICKY_HEADERS.add(new StickyEntry(formatted, clean));
        }
    }

    /** Returns up to {@code requestedLines} most recent lines joined by newlines. */
    public static String snapshotTail(int requestedLines) {
        int limit = (requestedLines <= 0) ? DEFAULT_MAX_LINES : Math.min(requestedLines, DEFAULT_MAX_LINES);
        String[] tailLines;
        List<StickyEntry> stickyCopy;
        synchronized (LOCK) {
            stickyCopy = STICKY_HEADERS.isEmpty() ? null : new ArrayList<>(STICKY_HEADERS);
            int total = RING.size();
            if (total == 0 && stickyCopy == null) return "";
            int skip = Math.max(0, total - limit);
            tailLines = new String[total - skip];
            int idx = 0;
            int pos = 0;
            for (String s : RING) {
                if (idx++ >= skip) {
                    tailLines[pos++] = s;
                }
            }
        }
        List<String> combined = new ArrayList<>(tailLines.length + (stickyCopy != null ? stickyCopy.size() : 0));
        if (stickyCopy != null) {
            List<String> missingSticky = new ArrayList<>(stickyCopy.size());
            for (StickyEntry sticky : stickyCopy) {
                boolean alreadyInTail = false;
                for (String ln : tailLines) {
                    if (ln != null && ln.endsWith(sticky.rawPayload)) {
                        alreadyInTail = true;
                        break;
                    }
                }
                if (!alreadyInTail) {
                    missingSticky.add(sticky.formattedLine);
                }
            }
            if (!missingSticky.isEmpty()) {
                combined.addAll(missingSticky);
                int tailStart = Math.max(0, tailLines.length - Math.max(0, limit - missingSticky.size()));
                for (int i = tailStart; i < tailLines.length; i++) {
                    if (tailLines[i] != null) combined.add(tailLines[i]);
                }
            } else {
                for (String ln : tailLines) {
                    if (ln != null) combined.add(ln);
                }
            }
        } else {
            for (String ln : tailLines) {
                if (ln != null) combined.add(ln);
            }
        }

        StringBuilder sb = new StringBuilder(Math.min(MAX_SNAPSHOT_CHARS, combined.size() * 80));
        for (int i = 0; i < combined.size(); i++) {
            String ln = combined.get(i);
            if (ln == null) continue;
            if (sb.length() + ln.length() + 1 > MAX_SNAPSHOT_CHARS) {
                // Keep the newest lines when hitting IPC parcel safety cap
                int trimTarget = Math.min(sb.length(), ln.length() + 256);
                int cut = sb.indexOf("\n", trimTarget);
                if (cut > 0 && cut + 1 < sb.length()) {
                    sb.delete(0, cut + 1);
                } else {
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(ln);
        }
        return sb.toString();
    }

    public static int size() {
        synchronized (LOCK) {
            return RING.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
            STICKY_HEADERS.clear();
        }
    }

    private static String stripTrailingCr(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\n')) {
            end--;
        }
        return (end == s.length()) ? s : s.substring(0, end);
    }

    private static final class TeeOutputStream extends OutputStream {
        private final PrintStream delegate;
        private final boolean isError;
        private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);

        TeeOutputStream(PrintStream delegate, boolean isError) {
            this.delegate = delegate;
            this.isError = isError;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (delegate != null) delegate.write(b);
            if (b == '\n') {
                flushBufferedLine();
            } else if (lineBuf.size() < MAX_LINE_CHARS * 2) {
                lineBuf.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (b == null || len <= 0) return;
            if (delegate != null) delegate.write(b, off, len);
            int start = off;
            int end = off + len;
            for (int i = off; i < end; i++) {
                if (b[i] == '\n') {
                    appendChunk(b, start, i - start);
                    flushBufferedLine();
                    start = i + 1;
                }
            }
            if (start < end) {
                appendChunk(b, start, end - start);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (delegate != null) delegate.flush();
        }

        private void appendChunk(byte[] b, int off, int len) {
            if (len <= 0) return;
            int remaining = (MAX_LINE_CHARS * 2) - lineBuf.size();
            if (remaining <= 0) return;
            lineBuf.write(b, off, Math.min(len, remaining));
        }

        private void flushBufferedLine() {
            if (lineBuf.size() == 0) return;
            String line = new String(lineBuf.toByteArray(), StandardCharsets.UTF_8);
            lineBuf.reset();
            recordLine(isError, line);
        }
    }
}
