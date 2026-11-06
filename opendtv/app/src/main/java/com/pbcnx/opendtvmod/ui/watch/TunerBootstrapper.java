package com.pbcnx.opendtvmod.ui.watch;

import com.pbcnx.opendtvmod.tuner.TunerHub;


/**
 * The one-time tuner bring-up: permission, hub acquisition, and the deferred tune/play of the
 * channel that should be showing. The screen supplies the state it owns through {@link Host}.
 */
public final class TunerBootstrapper {

    /** What the bring-up needs from the screen. */
    public interface Host {
        TunerHub hub();

        TunerHub.Owner owner();

        android.os.Handler ui();

        boolean canTune();

        void setCanTune(boolean value);

        boolean isFinishing();
        boolean handingOver();
        boolean initializing();
        void setInitializing(boolean value);
        boolean noTunableSource();
        /** A debug capture is about to open a file source; the tuner must stay out of it. */
        boolean capturePending();
        void showNoTunerPlaceholder();
        void playFromRunningSource();
        int debugTuneKhz();
        void debugTuneRequest(int khz);
        boolean isScanning();
        boolean hasChannel();
        void tuneAndPlayCurrent();
        void toast(String message);
    }

    private TunerBootstrapper() {
    }

    public static void launch(Host host) {
        if (host.initializing()) return;
        // A source the hub already runs is live on its own terms: a capture pushed with --es
        // ts_path, or a tuner another screen handed over. Looking for a dongle here is exactly what
        // put "Tuner not found" over a capture that was demuxing perfectly.
        if (host.noTunableSource()) {
            host.setCanTune(true);                 // nothing to initialise: the demux is already fed
            host.playFromRunningSource();
            return;
        }
        // The debug capture hook opens a file source a moment after onCreate. Initialising the tuner
        // (and autoplaying a saved channel) in that window is what put "Opening…" over a playing
        // capture and fed the chips from the tuner's empty mux (owner, 2026-11-06). The capture hook
        // plays the source itself, and the next launch takes the noTunableSource() branch above.
        if (host.capturePending()) {
            host.setCanTune(false);
            return;
        }
        if (!host.hub().isTunerPresent()) {
            host.setCanTune(false);
            host.showNoTunerPlaceholder();
            return; // onResume / USB-attach will retry
        }
        host.setInitializing(true);
        new Thread(() -> {
            boolean ok = host.hub().ensurePermission(60_000);
            if (ok) host.hub().acquire(host.owner());
            host.setCanTune(ok);
            host.setInitializing(false);
            host.ui().post(() -> {
                if (host.isFinishing()) return;
                // The init finished after the analyzer had already been asked for: belt and braces.
                // Tuning here would re-open the dongle underneath the analyzer (two native devices).
                if (host.handingOver()) return;
                if (!ok) {
                    host.toast("USB permission needed");
                    return;
                }
                if (host.debugTuneKhz() > 0) {
                    host.debugTuneRequest(host.debugTuneKhz());
                } else if (host.hasChannel() && !host.isScanning()) {
                    host.tuneAndPlayCurrent();
                }
            });
        }, "InitTuner").start();
    }
}
