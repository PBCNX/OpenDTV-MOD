package com.pbcnx.opendtvmod.ui.watch;




/**
 * The Analyzer hand-off: release the hub sink and the pipe, then start the analyzer with the
 * frequency that is playing, guarded by the handing-over flag until the result comes back.
 */
public final class AnalyzerHandoff {

    /** What the hand-off needs from the screen. */
    public interface Host extends ScreenSeam {
        com.pbcnx.opendtvmod.ts.pipe.TsPipe pipe();
        androidx.media3.exoplayer.ExoPlayer player();
        java.util.concurrent.atomic.AtomicInteger playGen();
        boolean isFinishing();
        int currentKhz();
        void setHandingOver(boolean handingOver);
        void startAnalyzer(int khz, int requestCode);
    }

    private AnalyzerHandoff() {
    }

    /** The hand-off's actual steps, so the coordination can be tested without an Activity. */
    public interface Actions {
        java.util.concurrent.atomic.AtomicInteger playGen();
        boolean isFinishing();
        int currentKhz();
        void setHandingOver(boolean handingOver);
        void setCanTune(boolean canTune);
        void stopPlayer();
        void detachSink();
        void clearPipe();
        void post(Runnable action);
        void startAnalyzer(int khz, int requestCode);
    }

    /** Adapts the screen host to the small action surface. */
    public static void launch(Host host, int requestCode) {
        launch(new Actions() {
            @Override public java.util.concurrent.atomic.AtomicInteger playGen() { return host.playGen(); }
            @Override public boolean isFinishing() { return host.isFinishing(); }
            @Override public int currentKhz() { return host.currentKhz(); }
            @Override public void setHandingOver(boolean value) { host.setHandingOver(value); }
            @Override public void setCanTune(boolean value) { host.setCanTune(value); }
            @Override public void stopPlayer() { host.player().stop(); host.player().clearMediaItems(); }
            @Override public void detachSink() { host.hub().detachSink(host.owner()); }
            @Override public void clearPipe() { host.pipe().clear(); }
            @Override public void post(Runnable action) { host.ui().post(action); }
            @Override public void startAnalyzer(int khz, int rc) { host.startAnalyzer(khz, rc); }
        }, requestCode);
    }

    public static void launch(Actions actions, int requestCode) {
        actions.playGen().incrementAndGet();
        // Set before the hand-off, and held until onActivityResult: the guard in initTuner and
        // onResume reads it, so nothing re-opens the dongle while the analyzer is claiming it.
        actions.setHandingOver(true);
        actions.setCanTune(false);
        actions.stopPlayer();
        actions.detachSink();
        actions.clearPipe();
        // One UI hop, not worker->UI: there is no worker work to schedule. Pending callbacks are
        // left alone; they run against the stopped activity and the generation/flags fence them.
        actions.post(() -> {
            if (actions.isFinishing()) {
                actions.setHandingOver(false);   // never launched: forget the hand-off
                return;
            }
            // The analyzer reads through the hub, so it needs the frequency that is playing.
            actions.startAnalyzer(actions.currentKhz(), requestCode);
        });
    }
}
