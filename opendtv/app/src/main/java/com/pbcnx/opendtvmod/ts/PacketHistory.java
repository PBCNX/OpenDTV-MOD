package com.pbcnx.opendtvmod.ts;

/**
 * Rolling per-PID history of the raw 188-byte packets.
 *
 * <p>Lets a consumer that starts late - the data-broadcast viewer - start from what the carousel
 * already delivered: a recorded file is read once, a live carousel repeats only every N seconds.
 * Grows on demand up to a per-kind maximum, so unused PIDs cost nothing. Split out of
 * {@link TsDemux} unchanged; PSI history is handed out before data history because the viewer needs
 * the tables that describe the carousel first.
 */
final class PacketHistory {
    private static final int PKT = 188;

    /** Rolling history of the raw packets of one PID. */
    private static final class Ring {
        final boolean psi;
        final int maxPkts;
        byte[] buf = new byte[64 * PKT];
        int cap = 64;
        int next = 0;
        int count = 0;

        Ring(int maxPkts, boolean psi) {
            this.maxPkts = maxPkts;
            this.psi = psi;
        }

        void add(byte[] b, int o) {
            if (count == cap && cap < maxPkts) {            // not wrapped yet: grow in place
                int ncap = Math.min(maxPkts, cap * 2);
                byte[] nb = new byte[ncap * PKT];
                System.arraycopy(buf, 0, nb, 0, count * PKT);
                buf = nb;
                cap = ncap;
                next = count;
            }
            System.arraycopy(b, o, buf, next * PKT, PKT);
            next = (next + 1) % cap;
            if (count < cap) count++;
        }

        int copyTo(byte[] out, int o) {
            int first = count < cap ? 0 : next;             // oldest packet
            for (int i = 0; i < count; i++) {
                System.arraycopy(buf, (first + i) % cap * PKT, out, o, PKT);
                o += PKT;
            }
            return o;
        }
    }

    static final int PSI_RING_PKTS = 512;
    static final int EIT_RING_PKTS = 1024;
    static final int DATA_RING_PKTS = 12000;

    private final Ring[] rings = new Ring[0x2000];

    /** Allocates the ring for {@code pid} on first sight (never replaces one). */
    void ensureRing(int pid, int max, boolean psi) {
        if (pid >= 0 && pid < rings.length && rings[pid] == null) rings[pid] = new Ring(max, psi);
    }

    void add(int pid, byte[] b, int o) {
        Ring r = rings[pid];
        if (r != null) r.add(b, o);
    }

    /** Concatenates the buffered history of every PID flagged in want: PSI first, then data PIDs. */
    byte[] snapshot(boolean[] want) {
        int total = 0;
        for (int pid = 0; pid < rings.length && pid < want.length; pid++) {
            if (want[pid] && rings[pid] != null) total += rings[pid].count * PKT;
        }
        byte[] out = new byte[total];
        int o = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int pid = 0; pid < rings.length && pid < want.length; pid++) {
                Ring r = rings[pid];
                if (want[pid] && r != null && r.psi == (pass == 0)) o = r.copyTo(out, o);
            }
        }
        return out;
    }
}
