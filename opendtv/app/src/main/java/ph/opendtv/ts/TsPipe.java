package ph.opendtv.ts;

/**
 * Thread-safe byte ring buffer between the tuner thread (writer) and the player (reader).
 * For live TV it never blocks the writer: when full, the oldest data is dropped in whole
 * 188-byte packets so the stream stays packet-aligned.
 */
public final class TsPipe {
    private final byte[] buf;
    private int readPos = 0;
    private int count = 0;
    private boolean closed = false;

    public TsPipe(int capacityBytes) {
        this.buf = new byte[(capacityBytes / 188) * 188];
    }

    public synchronized void write(byte[] src, int off, int len) {
        if (closed || len <= 0) return;
        if (len > buf.length) { // keep only the newest whole packets
            int skip = ((len - buf.length) + 187) / 188 * 188;
            off += skip;
            len -= skip;
        }
        int overflow = count + len - buf.length;
        if (overflow > 0) {
            int drop = (overflow + 187) / 188 * 188;
            readPos = (readPos + drop) % buf.length;
            count -= drop;
        }
        int writePos = (readPos + count) % buf.length;
        int first = Math.min(len, buf.length - writePos);
        System.arraycopy(src, off, buf, writePos, first);
        if (first < len) System.arraycopy(src, off + first, buf, 0, len - first);
        count += len;
        notifyAll();
    }

    /** @return bytes read, 0 on timeout, -1 when closed and drained. */
    public synchronized int read(byte[] dst, int off, int len, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (count == 0 && !closed) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return 0;
            wait(left);
        }
        if (count == 0) return -1;
        int n = Math.min(len, count);
        int first = Math.min(n, buf.length - readPos);
        System.arraycopy(buf, readPos, dst, off, first);
        if (first < n) System.arraycopy(buf, 0, dst, off + first, n - first);
        readPos = (readPos + n) % buf.length;
        count -= n;
        notifyAll(); // wake a writer in awaitSpace()
        return n;
    }

    /**
     * Back-pressure for file playback: waits (up to timeoutMs) until at least {@code bytes} are free.
     * Live sources should not call this; they let write() drop the oldest packets instead.
     */
    public synchronized void awaitSpace(int bytes, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!closed && buf.length - count < bytes) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return;
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public synchronized void clear() {
        readPos = 0;
        count = 0;
        notifyAll();
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized boolean isClosed() {
        return closed;
    }
}
