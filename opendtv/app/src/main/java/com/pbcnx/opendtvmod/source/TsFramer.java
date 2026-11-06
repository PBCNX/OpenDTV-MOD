package com.pbcnx.opendtvmod.source;

/** 188-byte TS framing for raw USB readers: skip to 0x47, emit whole packets, carry the remainder. */
public final class TsFramer {

    public interface Sink {
        void onPackets(byte[] buf, int off, int len);
    }

    private final byte[] pending;
    private int pendingLen;

    public TsFramer(int capacity) {
        this.pending = new byte[Math.max(capacity, 188 * 2)];
    }

    /** Feeds one read; returns the number of whole bytes emitted (0 when no full run yet). */
    public int feed(byte[] src, int n, Sink sink) {
        int emitted = 0;
        System.arraycopy(src, 0, pending, pendingLen, n);
        pendingLen += n;
        int start = 0;
        while (start < pendingLen && pending[start] != 0x47) start++;
        int whole = (pendingLen - start) / 188 * 188;
        if (whole > 0) {
            sink.onPackets(pending, start, whole);
            emitted = whole;
            start += whole;
        }
        int rem = pendingLen - start;
        if (rem > 0 && start > 0) System.arraycopy(pending, start, pending, 0, rem);
        pendingLen = rem;
        return emitted;
    }

    public void reset() {
        pendingLen = 0;
    }
}
