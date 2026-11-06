package com.pbcnx.opendtvmod.ts.pipe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pipe between the tuner thread and the player: the one seam both sides touch. In particular the
 * drop counter, which is the only feedback across it - it used to be incremented nowhere, so the
 * "stream health" log always read zero drops while packets were being sacrificed.
 */
public class TsPipeTest {

    private static final int PKT = 188;

    private static byte[] packet(int fill) {
        byte[] p = new byte[PKT];
        java.util.Arrays.fill(p, (byte) fill);
        p[0] = 0x47;
        return p;
    }

    @Test
    public void writesAndReadsBackTheSameBytes() throws Exception {
        TsPipe pipe = new TsPipe(4 * PKT);
        byte[] one = packet(0x11);
        byte[] two = packet(0x22);
        pipe.write(one, 0, PKT);
        pipe.write(two, 0, PKT);

        byte[] out = new byte[2 * PKT];
        int n = 0;
        while (n < out.length) {
            int r = pipe.read(out, n, out.length - n, 500);
            if (r <= 0) break;
            n += r;
        }
        assertEquals(2 * PKT, n);
        assertArrayEquals(one, java.util.Arrays.copyOfRange(out, 0, PKT));
        assertArrayEquals(two, java.util.Arrays.copyOfRange(out, PKT, 2 * PKT));
        assertEquals("nothing was sacrificed", 0, pipe.droppedPackets());
    }

    @Test
    public void overflowDropsWholePacketsAndCountsThem() {
        TsPipe pipe = new TsPipe(2 * PKT);
        for (int i = 0; i < 5; i++) {
            pipe.write(packet(0x30 + i), 0, PKT);
        }
        // 5 written into a 2-packet pipe: the oldest 3 were dropped, in whole packets.
        assertEquals(3, pipe.droppedPackets());
    }

    @Test
    public void aReadWithNothingBufferedTimesOutWithZero() throws Exception {
        TsPipe pipe = new TsPipe(PKT);
        assertEquals(0, pipe.read(new byte[PKT], 0, PKT, 50));
    }

    @Test
    public void aClosedAndDrainedPipeReadsMinusOne() throws Exception {
        TsPipe pipe = new TsPipe(PKT);
        pipe.close();
        assertEquals(-1, pipe.read(new byte[PKT], 0, PKT, 50));
        assertTrue(pipe.isClosed());
    }

    @Test
    public void clearThrowsAwayWhatWasBufferedButKeepsTheCounters() throws Exception {
        TsPipe pipe = new TsPipe(2 * PKT);
        for (int i = 0; i < 3; i++) pipe.write(packet(i), 0, PKT);
        int dropped = (int) pipe.droppedPackets();
        pipe.clear();
        assertEquals(0, pipe.read(new byte[2 * PKT], 0, 2 * PKT, 50));
        assertEquals(dropped, pipe.droppedPackets());
    }

    @Test
    public void awaitSpaceReturnsWhenWriteRoomIsEnough() {
        TsPipe pipe = new TsPipe(3 * PKT);
        pipe.write(packet(1), 0, PKT);
        pipe.awaitSpace(2 * PKT, 50);
        // still usable afterwards
        pipe.write(packet(2), 0, PKT);
        assertFalse(pipe.isClosed());
    }
}
