package com.pbcnx.opendtvmod.tuner;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * The one tune-wait-collect recipe: tune one frequency, wait for its tables, and return the
 * playable services. The watch screen's scans and the TV-input setup both call this, so their
 * budgets and the "playable service" predicate cannot drift apart.
 */
public final class MuxProbe {

    /** The scan wait budget, shared by every caller. */
    public static final int WAIT_MS = 20000;
    public static final int POLL_MS = 1200;
    public static final int SETTLE_MS = 11000;

    /** The one scan budget every caller uses. */
    private static final MuxWait.Budget BUDGET = new MuxWait.Budget(WAIT_MS, POLL_MS, SETTLE_MS);

    /** @return the mux and its playable services, or null when the tune was refused/cancelled */
    public interface Progress {
        void onServices(TsDemux demux, int count);
    }

    public static final class Result {
        public final TsDemux demux;
        public final List<ServiceInfo> services;

        Result(TsDemux demux, List<ServiceInfo> services) {
            this.demux = demux;
            this.services = services;
        }
    }

    private MuxProbe() {
    }

    @Nullable public static Result probe(TunerHub hub, int khz, TunerHub.CancelCheck cancel, @Nullable Progress progress) {
        if (!hub.ensureTuned(khz, cancel)) return null;
        if (cancel.isCancelled()) return null;
        TsDemux d = hub.demux();
        MuxWait.await(d, hub.isHardwareTuner(), BUDGET, () -> cancel.isCancelled(),
                progress == null ? null : n -> progress.onServices(d, n));
        List<ServiceInfo> playable = new ArrayList<>();
        for (ServiceInfo s : d.getServices()) {
            if (isPlayable(s)) playable.add(s);
        }
        return new Result(d, playable);
    }

    /** The one "worth saving/showing" predicate: seen PMT, some streams, video present. */
    public static boolean isPlayable(ServiceInfo s) {
        return s.pmtSeen && !s.streams.isEmpty() && s.hasVideo();
    }
}
