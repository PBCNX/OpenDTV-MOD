package com.pbcnx.opendtvmod.tuner;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * Waiting for one mux to settle to a channel list: the question every scan asks ("is the list in,
 * is it named, has the virtual number arrived?") and the dead-mux shortcut that keeps a sparse band
 * from costing a full per-mux budget. It existed three times with different budgets and one copy
 * missing the dead-mux skip; the budgets are arguments now.
 */
public final class MuxWait {

    private MuxWait() {
    }

    /** True when at least one TV service is listed and every one of them already has its SDT name. */
    public static boolean allNamed(TsDemux d) {
        boolean any = false;
        for (ServiceInfo s : d.getServices()) {
            if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
            any = true;
            if (s.name.isEmpty()) return false;
        }
        return any;
    }

    /** TV services the demux has listed (PMT seen, streams known). */
    public static int countServices(TsDemux d) {
        int n = 0;
        for (ServiceInfo s : d.getServices()) if (s.pmtSeen && !s.streams.isEmpty()) n++;
        return n;
    }

    /** The three timeouts one mux wait needs, named as one budget. */
    public static final class Budget {
        public final long budgetMs;
        public final long settledAfterMs;
        public final long hardStopMs;

        public Budget(long budgetMs, long settledAfterMs, long hardStopMs) {
            this.budgetMs = budgetMs;
            this.settledAfterMs = settledAfterMs;
            this.hardStopMs = hardStopMs;
        }
    }

    /**
     * Waits for one mux to settle, then reports the service count.
     *
     * <p>Returns once the list has names and the virtual number and has been stable for
     * {@code settledAfterMs}, or once the mux proves dead (no packets). A USB tuner either has
     * packets within a second or the mux is dead; a network source can take many seconds to open
     * its TS tap, so it gets the longer window - without that distinction an aggressive skip
     * declares a good network source dead.
     *
     * @param budgetMs       the longest one mux may take
     * @param settledAfterMs how long the list must stay unchanged after it first had services
     * @param hardStopMs     stop even a slow list after this long, once anything was seen
     * @param cancelled      stops the wait when the scan is cancelled
     * @param progress       called on every poll with the current service count
     */
    public static int await(TsDemux d, boolean hardwareTuner, Budget budget,
                            BooleanSupplier cancelled, IntConsumer progress) {
        final long budgetMs = budget.budgetMs;
        final long settledAfterMs = budget.settledAfterMs;
        final long hardStopMs = budget.hardStopMs;
        final long deadAfterMs = hardwareTuner ? 1200 : 8000;
        final long silentForMs = hardwareTuner ? 1500 : 6000;
        final long start = System.currentTimeMillis();
        long firstSeen = 0;
        long lastPkts = -1;
        long lastPktChange = start;
        while (System.currentTimeMillis() - start < budgetMs && !cancelled.getAsBoolean()) {
            int n = countServices(d);
            long now = System.currentTimeMillis();
            long pkts = d.getPacketCount();
            if (pkts != lastPkts) {
                lastPkts = pkts;
                lastPktChange = now;
            }
            if (now - start > deadAfterMs && now - lastPktChange > silentForMs) break;   // dead mux
            if (n > 0 && firstSeen == 0) firstSeen = now;
            // Names from the SDT and the virtual number from the NIT (which repeats slowly) both
            // have to be in before the channel can be left.
            if (firstSeen != 0 && ((allNamed(d) && d.hasRemoteKey() && now - firstSeen > settledAfterMs)
                    || now - firstSeen > hardStopMs)) break;
            if (progress != null) progress.accept(n);
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return countServices(d);
    }
}
