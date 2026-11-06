package com.pbcnx.opendtvmod.ts;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link PacketHistory}: the rolling per-PID packet buffers behind
 * {@link TsDemux#attachTapWithHistory}. Packets are synthetic 188-byte blocks whose second byte
 * carries a sequence number, so the order and the wrap are visible in the bytes that come back.
 */
public class PacketHistoryTest {

    private static final int PKT = 188;
    private static final int PID_PSI = 0x100;
    private static final int PID_DATA = 0x200;
    private static final int PID_LOW = 0x0050;
    private static final int PID_HIGH = 0x0900;

    /** One 188-byte packet tagged with its sequence number. */
    private static byte[] packet(int seq) {
        byte[] p = new byte[PKT];
        p[0] = 0x47;
        p[1] = (byte) seq;
        p[2] = (byte) (seq >> 8);
        return p;
    }

    private static int marker(byte[] history, int index) {
        return history[index * PKT + 1] & 0xFF;
    }

    /** Collects the live packets a tap installed by attachTapWithHistory sees. */
    private static final class RecordingSink implements PacketSink {
        final List<byte[]> packets = new ArrayList<>();

        @Override
        public void onPacket(byte[] buf, int off) {
            byte[] copy = new byte[PKT];
            System.arraycopy(buf, off, copy, 0, PKT);
            packets.add(copy);
        }

        @Override
        public void flush() {
            // the test feed has no buffered tail to flush
        }
    }

    @Test
    public void snapshotReturnsThePacketsInOrder() {
        PacketHistory history = new PacketHistory();
        history.ensureRing(PID_PSI, 64, true);
        for (int seq = 1; seq <= 3; seq++) {
            byte[] p = packet(seq);
            history.add(PID_PSI, p, 0);
        }
        boolean[] want = new boolean[0x2000];
        want[PID_PSI] = true;

        byte[] out = history.snapshot(want);
        assertEquals(3 * PKT, out.length);
        assertEquals(1, marker(out, 0));
        assertEquals(2, marker(out, 1));
        assertEquals(3, marker(out, 2));
    }

    @Test
    public void theRingStopsAtItsMaximumAndOverwritesTheOldest() {
        PacketHistory history = new PacketHistory();
        history.ensureRing(PID_PSI, 128, true);          // grows 64 -> 128, then wraps
        for (int seq = 1; seq <= 200; seq++) {
            byte[] p = packet(seq);
            history.add(PID_PSI, p, 0);
        }
        boolean[] want = new boolean[0x2000];
        want[PID_PSI] = true;

        byte[] out = history.snapshot(want);
        assertEquals(128 * PKT, out.length);
        assertEquals("the oldest kept packet is 200 - 128 + 1", 73, marker(out, 0));
        assertEquals(200, marker(out, 127));
    }

    @Test
    public void psiHistoryComesBeforeDataHistory() {
        PacketHistory history = new PacketHistory();
        history.ensureRing(PID_HIGH, 64, true);           // PSI, higher PID
        history.ensureRing(PID_LOW, 64, false);           // data, lower PID
        byte[] psi = packet(1);
        byte[] data = packet(2);
        history.add(PID_HIGH, psi, 0);
        history.add(PID_LOW, data, 0);

        boolean[] want = new boolean[0x2000];
        want[PID_HIGH] = true;
        want[PID_LOW] = true;

        byte[] out = history.snapshot(want);
        assertEquals(2 * PKT, out.length);
        assertEquals("PSI history is handed out before data history", 1, marker(out, 0));
        assertEquals(2, marker(out, 1));
    }

    @Test
    public void onlyWantedPidsAreIncluded() {
        PacketHistory history = new PacketHistory();
        history.ensureRing(PID_PSI, 64, true);
        history.ensureRing(PID_DATA, 64, false);
        byte[] psi = packet(3);
        byte[] data = packet(4);
        history.add(PID_PSI, psi, 0);
        history.add(PID_DATA, data, 0);

        boolean[] want = new boolean[0x2000];
        want[PID_DATA] = true;

        byte[] out = history.snapshot(want);
        assertEquals(PKT, out.length);
        assertEquals(4, marker(out, 0));
    }

    @Test
    public void packetsOnPidsWithoutARingAreIgnored() {
        PacketHistory history = new PacketHistory();
        byte[] p = packet(5);
        history.add(0x777, p, 0);                     // no ensure(): must not throw or register

        boolean[] want = new boolean[0x2000];
        want[0x777] = true;
        assertEquals(0, history.snapshot(want).length);
    }

    @Test
    public void attachTapWithHistoryHandsOutTheBufferedPacketsThenLiveOnes() {
        TsDemux demux = new TsDemux();
        byte[] first = new byte[PKT];
        Arrays.fill(first, (byte) 0xFF);
        first[0] = 0x47;                              // PID 0 (PAT), payload-only, stuffing after the pointer
        first[1] = 0x40;
        first[2] = 0x00;
        first[3] = 0x10;
        first[4] = 0x00;
        demux.feed(first, 0, first.length);
        demux.feed(first, 0, first.length);
        assertEquals(2, demux.getPacketCount());

        boolean[] want = new boolean[0x2000];
        want[0] = true;                               // the PAT PID has a ring from construction
        RecordingSink tap = new RecordingSink();
        byte[] history = demux.attachTapWithHistory(tap, want);

        assertEquals("both packets fed before the attach are in the history", 2 * PKT, history.length);
        assertTrue("nothing is live before the next feed", tap.packets.isEmpty());

        demux.feed(first, 0, first.length);
        assertEquals(1, tap.packets.size());
    }
}
