package com.pbcnx.opendtvmod.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The ring's three promises: bounded, thread-safe, and redacted before anything is stored.
 */
public class DiagnosticsRingTest {

    @Test
    public void keepsOnlyTheNewestLines() {
        DiagnosticsRing ring = new DiagnosticsRing();
        for (int i = 0; i < DiagnosticsRing.MAX_LINES + 5; i++) {
            ring.record("t", "line " + i);
        }
        assertEquals(DiagnosticsRing.MAX_LINES, ring.size());
        String snapshot = ring.snapshot();
        assertTrue(snapshot, snapshot.contains("line " + (DiagnosticsRing.MAX_LINES + 4)));
        assertTrue(snapshot, snapshot.contains("line 5"));
        assertFalse(snapshot, snapshot.contains("line 4\n"));
        assertFalse(snapshot, snapshot.contains("line 0\n"));
    }

    @Test
    public void redactsBeforeStoring() {
        DiagnosticsRing ring = new DiagnosticsRing();
        ring.record("net", "ip 10.1.2.3 mail bob@example.com path /home/bob/x.ts");
        String snapshot = ring.snapshot();
        assertFalse(snapshot, snapshot.contains("10.1.2.3"));
        assertFalse(snapshot, snapshot.contains("bob@example.com"));
        assertFalse(snapshot, snapshot.contains("bob"));
        assertTrue(snapshot, snapshot.contains(Redactor.REDACTED));
        assertTrue(snapshot, snapshot.contains(Redactor.PATH));
    }

    @Test
    public void oneRecordStaysOneLine() {
        DiagnosticsRing ring = new DiagnosticsRing();
        ring.record("status", "first\nsecond");
        assertEquals(1, ring.size());
        assertEquals(1, ring.snapshot().split("\n").length);
    }

    @Test
    public void blankRecordsAreIgnored() {
        DiagnosticsRing ring = new DiagnosticsRing();
        ring.record("t", null);
        ring.record("t", "");
        ring.record("t", "   ");
        assertEquals(0, ring.size());
    }

    @Test
    public void concurrentWritersDoNotCorruptTheBound() throws Exception {
        final DiagnosticsRing ring = new DiagnosticsRing();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            final int id = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) ring.record("t" + id, "line " + i);
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();
        assertTrue(ring.size() <= DiagnosticsRing.MAX_LINES);
        assertTrue(ring.snapshot().split("\n").length <= DiagnosticsRing.MAX_LINES);
    }
}
