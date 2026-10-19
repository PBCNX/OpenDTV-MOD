package ph.opendtv.source;

import java.io.IOException;
import java.io.InputStream;

/**
 * Plays a recorded .ts file as if it were a tuner. Lets you develop and test the UI, analyzer and
 * player without the dongle.
 *
 * Recordings come in several packet sizes; the rest of the app only understands 188-byte packets,
 * so the first 64 KiB is probed and the stream is normalised on the fly:
 *   188  plain TS
 *   192  BDAV / m2ts (4-byte timestamp in front of each packet)
 *   204  TS + 16 bytes Reed-Solomon parity (typical ISDB-T / DVB capture from a modulator or analyser)
 *
 * bytesPerSecond = 0 reads as fast as the consumer accepts it (the analyzer wants this; playback is
 * paced by the TsPipe back-pressure anyway).
 */
public final class FileTsSource implements TsSource {

    public interface Opener {
        InputStream open() throws IOException;
    }

    private static final int OUT_PACKETS = 1024;            // 192 KiB per onData() call
    private static final int PROBE_BYTES = 64 * 1024;

    private final String name;
    private final Opener opener;
    private final long bytesPerSecond;
    private final boolean loop;
    private volatile boolean running;
    private volatile int detectedPacketSize = 188;
    private Thread thread;

    public FileTsSource(String name, Opener opener, long bytesPerSecond, boolean loop) {
        this.name = name;
        this.opener = opener;
        this.bytesPerSecond = bytesPerSecond;
        this.loop = loop;
    }

    @Override
    public String name() {
        return name;
    }

    /** 188, 192 or 204 once the file has been probed (188 before that). */
    public int packetSize() {
        return detectedPacketSize;
    }

    @Override
    public synchronized void start(final Listener l) {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                pump(l);
            }
        }, "FileTsSource");
        thread.start();
    }

    /**
     * Finds (packetSize, offset of the first 0x47) such that the most sync bytes line up.
     * @return {size, offset} or null if nothing looks like TS
     */
    static int[] detect(byte[] b, int n) {
        int[] sizes = {188, 192, 204};
        int bestScore = 0;
        int[] best = null;
        for (int size : sizes) {
            for (int off = 0; off < size && off < n; off++) {
                int hits = 0, tot = 0;
                for (int i = off; i < n; i += size) {
                    tot++;
                    if ((b[i] & 0xFF) == 0x47) hits++;
                }
                // require nearly every slot to be a sync byte
                if (tot >= 8 && hits * 100 >= tot * 95 && hits > bestScore) {
                    bestScore = hits;
                    best = new int[] {size, off};
                }
            }
        }
        return best;
    }

    private void pump(Listener l) {
        try {
            do {
                try (InputStream in = opener.open()) {
                    if (in == null) throw new IOException("cannot open file");
                    streamOnce(in, l);
                }
            } while (running && loop);
            if (running) l.onEnd();
        } catch (IOException e) {
            if (running) l.onError("File error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
        }
    }

    private void streamOnce(InputStream in, Listener l) throws IOException, InterruptedException {
        byte[] probe = new byte[PROBE_BYTES];
        int have = 0;
        while (have < probe.length) {
            int r = in.read(probe, have, probe.length - have);
            if (r <= 0) break;
            have += r;
        }
        int[] fmt = detect(probe, have);
        int size = fmt != null ? fmt[0] : 188;
        int skip = fmt != null ? fmt[1] : 0;
        // 192-byte (m2ts): the TS packet follows a 4-byte header, so the sync byte is at +4.
        int pktOff = (size == 192) ? 4 : 0;
        // If detect() found the sync at offset 'skip' inside a 192 layout, packet starts there anyway.
        if (size == 192) skip = Math.max(0, skip - 4);
        detectedPacketSize = size;

        byte[] raw = new byte[size * OUT_PACKETS];
        byte[] out = new byte[188 * OUT_PACKETS];
        int rawLen = 0;
        long start = System.nanoTime();
        long sent = 0;

        // Seed with the probed bytes, dropping anything before the first packet.
        System.arraycopy(probe, skip, raw, 0, Math.min(have - skip, raw.length));
        rawLen = Math.min(have - skip, raw.length);
        int remainingProbe = have - skip - rawLen;     // bytes of the probe that did not fit (normally 0)
        int probePos = skip + rawLen;

        boolean eof = false;
        while (running) {
            // top up the raw buffer
            while (rawLen < raw.length && !eof) {
                int r;
                if (remainingProbe > 0) {
                    r = Math.min(remainingProbe, raw.length - rawLen);
                    System.arraycopy(probe, probePos, raw, rawLen, r);
                    probePos += r;
                    remainingProbe -= r;
                } else {
                    r = in.read(raw, rawLen, raw.length - rawLen);
                    if (r <= 0) {
                        eof = true;
                        break;
                    }
                }
                rawLen += r;
            }
            int npk = rawLen / size;
            if (npk == 0) break;
            int o = 0;
            for (int i = 0; i < npk; i++) {
                System.arraycopy(raw, i * size + pktOff, out, o, 188);
                o += 188;
            }
            l.onData(out, 0, o);
            sent += o;
            int used = npk * size;
            System.arraycopy(raw, used, raw, 0, rawLen - used);
            rawLen -= used;
            if (bytesPerSecond > 0) {
                long dueNs = sent * 1_000_000_000L / bytesPerSecond;
                long aheadMs = (dueNs - (System.nanoTime() - start)) / 1_000_000L;
                if (aheadMs > 0) Thread.sleep(aheadMs);
            }
            if (eof && rawLen < size) break;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public boolean supportsTuning() {
        return false;
    }

    @Override
    public boolean tune(int frequencyKhz) {
        return false;
    }

    @Override
    public SignalStatus getSignalStatus() {
        return null;
    }
}
