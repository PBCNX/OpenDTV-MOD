package com.pbcnx.opendtvmod.ui.watch;

import android.os.SystemClock;
import android.util.Log;

import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * The once-a-second watch refresh: sample the source, update the EWBS strip and the info banner,
 * follow the EIT for the guide line, and log stream health every five seconds.
 */
public final class WatchTicker {

    private static final String TAG = "OpenDTV-Main";

    /** What the refresh needs from the screen. */
    public interface Host {
        android.os.Handler ui();
        boolean finishingOrDestroyed();
        TsDemux demux();
        void sampleSourceNow(TsDemux demux);
        void updateEwbsAlerts(TsDemux demux);
        void updateInfoBanner(TsDemux demux);
        void updateEpgInfo();
        long pipeDropped();
        long continuityErrors(TsDemux demux);
        /** Debug-only witness for the device smoke gate: one line per tick. No-op on release. */
        void benchTick();
    }

    private final Host host;
    private long lastLogMs;
    private long lastDrops;
    private long lastCc;

    public WatchTicker(Host host) {
        this.host = host;
    }

    public Runnable runnable() {
        return this::tick;
    }

    public void tick() {
        if (host.finishingOrDestroyed()) return;
        TsDemux d = host.demux();
        host.sampleSourceNow(d);
        host.updateEwbsAlerts(d);
        host.updateInfoBanner(d);
        // The guide line must follow the EIT, not the moment the banner was shown: on GMA the
        // present/following section arrives a second or two after the first picture.
        host.updateEpgInfo();
        long now = SystemClock.elapsedRealtime();
        if (now - lastLogMs > 5000) {
            lastLogMs = now;
            long drops = host.pipeDropped();
            // Continuity errors are the other half of the jitter question: a burst lost on the air
            // never shows up as a USB stall or a pipe drop, but it does reset the audio sink.
            long cc = d != null ? host.continuityErrors(d) : 0L;
            if (drops != lastDrops || cc > lastCc) {
                Log.i(TAG, "stream health: pipe drops " + (drops - lastDrops) + " in 5 s (total "
                        + drops + "), continuity errors +" + (cc - lastCc) + " (total " + cc + ")");
                lastDrops = drops;
                lastCc = cc;
            }
        }
        // The tick is the once-a-second heartbeat: without re-posting it the screen would update
        // exactly once and then freeze (the regression behind stale chips / missing EWBS strip).
        host.benchTick();
        host.ui().postDelayed(this::tick, 1000);
    }
}
