package com.pbcnx.opendtvmod.ui.watch;

/** The watch screen's tuner lifecycle: one state, with the hand-off transitions in one place. */
public final class TunerLifecycle {

    private Phase phase = Phase.NOT_READY;

    private enum Phase { NOT_READY, READY, HANDING_OVER }

    public synchronized boolean canTune() {
        return phase == Phase.READY;
    }

    public synchronized boolean isHandingOver() {
        return phase == Phase.HANDING_OVER;
    }

    /** READY means "initialised once"; clearing it forces a re-init, but never demotes a hand-off. */
    public synchronized void setReady(boolean ready) {
        if (ready) phase = Phase.READY;
        else if (phase != Phase.HANDING_OVER) phase = Phase.NOT_READY;
    }

    /** The Analyzer owns the dongle from launch until the result returns. */
    public synchronized void setHandingOver(boolean handingOver) {
        if (handingOver) phase = Phase.HANDING_OVER;
        else if (phase == Phase.HANDING_OVER) phase = Phase.NOT_READY;
    }

    public synchronized void reset() {
        phase = Phase.NOT_READY;
    }
}
