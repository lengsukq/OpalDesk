package io.github.lengsukq.opaldesk.daemon.util;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
}
