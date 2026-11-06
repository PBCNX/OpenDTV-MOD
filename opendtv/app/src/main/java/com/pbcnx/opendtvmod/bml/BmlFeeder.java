package com.pbcnx.opendtvmod.bml;

import com.pbcnx.opendtvmod.ts.PacketSink;

import android.os.Handler;
import android.util.Base64;

import java.util.concurrent.atomic.AtomicInteger;

import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;

/**
 * Collects the packets web-bml needs (PAT/SDT/EIT/TDT, the service's PMT, its data-carousel PIDs)
 * and hands them to the page in base64 batches, waiting for the page to finish each batch.
 *
 * <p>Split out of {@link BmlController} without a logic change: the same PIDs are wanted, the same
 * 400-packet buffer is flushed on the same 150 ms clock, the same three-in-flight backpressure
 * applies, and a replay still blocks live packets so the carousel keeps its order.
 */
final class BmlFeeder implements PacketSink {

    /** What the feeder needs from the page: is it able to take a batch, and how to hand it one. */
    interface Page {
        boolean canTake();

        void push(String base64, Runnable done);
    }

    private final Handler ui;
    private final Page page;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final boolean[] want = new boolean[8192];
    private final byte[] buf = new byte[188 * 400];
    private int len;
    private long lastSend;
    private volatile boolean replaying;
    private volatile int generation;

    BmlFeeder(Handler ui, Page page) {
        this.ui = ui;
        this.page = page;
    }

    void configure(ServiceInfo svc) {
        reset();
        for (int pid : new int[] {0x00, 0x10, 0x11, 0x12, 0x14}) want[pid] = true;
        if (svc.pmtPid > 0 && svc.pmtPid < 8192) want[svc.pmtPid] = true;
        for (StreamInfo st : svc.streams) {
            if ((st.isDatacast() || st.dataComponentId == 0x000C) && st.pid < 8192) want[st.pid] = true;
        }
    }

    void reset() {
        generation++;
        replaying = false;
        java.util.Arrays.fill(want, false);
        len = 0;
    }

    void dropPending() {
        generation++;     // aborts a replay in progress
        replaying = false;
        len = 0;
    }

    /** The page was destroyed: no batch is outstanding any more. */
    void clearInFlight() {
        inFlight.set(0);
    }

    boolean[] wanted() { return want; }

    /**
     * Pushes the buffered history to the page in batches, waiting for the page to finish each one (nothing is
     * dropped, unlike live packets). Live packets are ignored meanwhile so the order stays intact; the carousel
     * repeats, so that costs nothing.
     */
    void replay(final byte[] data) {
        if (data == null || data.length < 188) return;
        final int gen = ++generation;
        replaying = true;
        Thread t = new Thread(() -> {
                final int step = 188 * 400;
                try {
                    for (int off = 0; off < data.length; off += step) {
                        int n = Math.min(step, data.length - off);
                        int waited = 0;
                        while (inFlight.get() >= 3 && waited < 8000) {
                            if (gen != generation) return;
                            Thread.sleep(5);
                            waited += 5;
                        }
                        if (gen != generation) return;
                        dispatch(Base64.encodeToString(data, off, n, Base64.NO_WRAP));
                    }
                } catch (InterruptedException ignored) {
                    // interrupted: the finally block still clears the replay flag
                } finally {
                    if (gen == generation) replaying = false;
                }
            }, "BmlReplay");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onPacket(byte[] b, int o) {
        if (replaying) return;
        int pid = ((b[o + 1] & 0x1F) << 8) | (b[o + 2] & 0xFF);
        if (!want[pid]) return;
        if (len + 188 > buf.length) send();
        System.arraycopy(b, o, buf, len, 188);
        len += 188;
    }

    @Override
    public void flush() {
        if (replaying) { len = 0; return; }
        long now = System.currentTimeMillis();
        if (len > 0 && now - lastSend >= 150) send();
    }

    private void send() {
        lastSend = System.currentTimeMillis();
        if (len == 0) return;
        if (inFlight.get() >= 3) {      // page still busy with earlier batches: drop (carousels repeat)
            len = 0;
            return;
        }
        final String b64 = Base64.encodeToString(buf, 0, len, Base64.NO_WRAP);
        len = 0;
        dispatch(b64);
    }

    private void dispatch(final String b64) {
        inFlight.incrementAndGet();
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!page.canTake()) {
                    inFlight.decrementAndGet();
                    return;
                }
                // the callback fires when the page has finished processing the batch: real backpressure
                page.push(b64, new Runnable() {
                    @Override
                    public void run() {
                        inFlight.decrementAndGet();
                    }
                });
            }
        });
    }
}
