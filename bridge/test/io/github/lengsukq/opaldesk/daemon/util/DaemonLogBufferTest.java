package io.github.lengsukq.opaldesk.daemon.util;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DaemonLogBufferTest {

    @Before
    public void resetBuffer() {
        DaemonLogBuffer.clear();
    }

    @Test
    public void recordLineRespectsBoundedCapacityAndTailOrdering() {
        int total = DaemonLogBuffer.DEFAULT_MAX_LINES + 35;
        for (int i = 0; i < total; i++) {
            DaemonLogBuffer.recordLine(i % 2 == 1, "event-" + i);
        }

        assertEquals(DaemonLogBuffer.DEFAULT_MAX_LINES, DaemonLogBuffer.size());
        String tail5 = DaemonLogBuffer.snapshotTail(5);
        String[] lines = tail5.split("\n");
        assertEquals(5, lines.length);
        assertTrue(lines[0].contains("event-" + (total - 5)));
        assertTrue(lines[4].contains("event-" + (total - 1)));
        assertFalse(tail5.contains("event-0\n"));
    }

    @Test
    public void teePrintStreamMirrorsToDelegateAndCapturesUtf8Lines() {
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        PrintStream delegate = new PrintStream(rawOut, true, StandardCharsets.UTF_8);
        PrintStream tee = DaemonLogBuffer.createTeePrintStream(delegate, true);

        tee.println("OPALDESK_VD_CREATE_FAIL 中文测试");
        tee.print("partial-");
        tee.println("line");
        tee.flush();

        String delegated = rawOut.toString(StandardCharsets.UTF_8);
        assertTrue(delegated.contains("OPALDESK_VD_CREATE_FAIL 中文测试"));
        assertTrue(delegated.contains("partial-line"));

        String buffered = DaemonLogBuffer.snapshotTail(10);
        assertTrue(buffered.contains("E/Daemon: OPALDESK_VD_CREATE_FAIL 中文测试"));
        assertTrue(buffered.contains("E/Daemon: partial-line"));
    }

    @Test
    public void stickyHeaderSurvivesRingOverflowWithoutDuplicatingWhenStillPresent() {
        String banner = "OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=2000 api=37 injector=IIM_TARGET uhidRw=true";
        DaemonLogBuffer.recordStickyHeader(banner);
        DaemonLogBuffer.recordLine(false, banner);
        DaemonLogBuffer.recordLine(false, "normal-line-1");

        // While startup line is still inside the tail slice, it should appear once (no duplicate).
        String initial = DaemonLogBuffer.snapshotTail(10);
        assertEquals(2, initial.split("\n").length);
        assertTrue(initial.contains(banner));
        assertFalse(initial.contains("[STARTUP]"));

        // After flooding past ring capacity, snapshotTail must prepend the sticky [STARTUP] line.
        int overflow = DaemonLogBuffer.DEFAULT_MAX_LINES + 60;
        for (int i = 0; i < overflow; i++) {
            DaemonLogBuffer.recordLine(false, "noise-" + i);
        }
        String afterFlood = DaemonLogBuffer.snapshotTail(20);
        String[] lines = afterFlood.split("\n");
        assertEquals(20, lines.length);
        assertTrue(lines[0].contains("[STARTUP] " + banner));
        assertTrue(lines[lines.length - 1].contains("noise-" + (overflow - 1)));
    }

    @Test
    public void previousAbnormalTailSkipsCleanStopAndRetainsCrashContext() throws Exception {
        List<String> cleanLog = Arrays.asList(
                "OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=2000 api=31",
                "OPALDESK_VD_CREATED name=OpalWin-1 displayId=2",
                DaemonLogBuffer.CLEAN_SHUTDOWN_MARKER + " reason=ctl_stop"
        );
        assertTrue(DaemonLogBuffer.extractPreviousAbnormalTail(cleanLog, 20).isEmpty());

        List<String> crashedLog = Arrays.asList(
                "OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=2000 api=31",
                "OPALDESK_VD_CREATED name=OpalWin-1 displayId=2",
                "OPALDESK_UNCAUGHT_EXCEPTION thread=main: java.lang.SecurityException: denied",
                "OPALDESK_UNCAUGHT_END"
        );
        List<String> extracted = DaemonLogBuffer.extractPreviousAbnormalTail(crashedLog, 10);
        assertEquals(4, extracted.size());
        assertTrue(extracted.get(0).contains("[PREV_CRASH] OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED"));
        assertTrue(extracted.get(2).contains("OPALDESK_UNCAUGHT_EXCEPTION"));

        File tmpDir = Files.createTempDirectory("opaldesk-logbuf").toFile();
        tmpDir.deleteOnExit();
        File prevFile = new File(tmpDir, "opaldesk.prev.log");
        Files.write(prevFile.toPath(), crashedLog, StandardCharsets.UTF_8);
        prevFile.deleteOnExit();

        int loaded = DaemonLogBuffer.loadPreviousAbnormalLogFile(prevFile, 20);
        assertEquals(4, loaded);
        DaemonLogBuffer.recordLine(false, "OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=2000 api=31");
        String snap = DaemonLogBuffer.snapshotTail(40);
        assertTrue(snap.contains("[PREV_CRASH] OPALDESK_UNCAUGHT_EXCEPTION"));
        assertTrue(snap.contains("OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=2000 api=31"));

        // Symlinked log files must be refused fail-closed.
        DaemonLogBuffer.clear();
        File linkFile = new File(tmpDir, "sym.prev.log");
        linkFile.deleteOnExit();
        Files.createSymbolicLink(linkFile.toPath(), prevFile.toPath());
        assertEquals(0, DaemonLogBuffer.loadPreviousAbnormalLogFile(linkFile, 20));
    }
}
