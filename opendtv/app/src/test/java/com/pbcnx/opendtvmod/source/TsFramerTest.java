package com.pbcnx.opendtvmod.source;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The one 188-byte framing rule used by the raw USB readers. */
public class TsFramerTest {

    private static byte[] packet(int seed) {
        byte[] p = new byte[188];
        p[0] = 0x47;
        p[1] = (byte) seed;
        return p;
    }

    @Test
    public void oneWholePacketIsEmittedImmediately() {
        TsFramer framer = new TsFramer(1024);
        final int[] calls = {0};
        final int[] len = {0};
        int emitted = framer.feed(packet(1), 188, (buf, off, n) -> { calls[0]++; len[0] = n; });
        assertEquals(188, emitted);
        assertEquals(1, calls[0]);
        assertEquals(188, len[0]);
    }

    @Test
    public void aPacketSplitAcrossReadsIsCarried() {
        TsFramer framer = new TsFramer(1024);
        byte[] p = packet(2);
        final int[] total = {0};
        assertEquals(0, framer.feed(java.util.Arrays.copyOf(p, 100), 100, (b, o, n) -> total[0] += n));
        assertEquals(188, framer.feed(java.util.Arrays.copyOfRange(p, 100, 188), 88, (b, o, n) -> total[0] += n));
        assertEquals(188, total[0]);
    }

    @Test
    public void garbageBeforeSyncIsSkipped() {
        TsFramer framer = new TsFramer(1024);
        byte[] input = new byte[10 + 188];
        System.arraycopy(packet(3), 0, input, 10, 188);
        final byte[] seen = new byte[188];
        int emitted = framer.feed(input, input.length, (b, o, n) -> System.arraycopy(b, o, seen, 0, n));
        assertEquals(188, emitted);
        assertArrayEquals(packet(3), seen);
    }

    @Test
    public void resetDropsTheCarriedTail() {
        TsFramer framer = new TsFramer(1024);
        framer.feed(java.util.Arrays.copyOf(packet(4), 100), 100, (b, o, n) -> { });
        framer.reset();
        assertEquals(0, framer.feed(java.util.Arrays.copyOfRange(packet(4), 100, 188), 88, (b, o, n) -> { }));
    }
}
