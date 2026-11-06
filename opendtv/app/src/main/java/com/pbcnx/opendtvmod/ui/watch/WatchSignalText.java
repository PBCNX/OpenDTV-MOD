package com.pbcnx.opendtvmod.ui.watch;

/**
 * The one decision behind the "signal" cell of the three watch surfaces (strip, landscape HUD,
 * channel card).
 *
 * <p>It used to be decided three times, and all three times the same way: a lock that had not
 * happened yet said "Tuning…". With no dongle attached nothing was being tuned, so the card said
 * "Tuner not found" while the strip underneath said "Tuning…" - two surfaces disagreeing about
 * the same fact (owner, 2026-11-06). Keeping the decision here means the surfaces cannot drift
 * apart again, and the phone-less CI can test every branch.</p>
 */
public final class WatchSignalText {

    /** Shown when nothing plays and no tuner is attached: a wait word there would be a lie. */
    public static final String NO_TUNER = "No Tuner Available";

    /** What the cell says. */
    public enum State {
        /** A source is playing and its RF numbers do not exist (a capture). */
        PLAYING,
        /** Nothing is playing and the hub has no supported USB tuner. */
        NO_TUNER,
        /** A tuner exists but the demodulator has not locked yet. */
        TUNING,
        /** Locked: the cell shows S/Q. */
        LOCKED
    }

    private WatchSignalText() {
    }

    /**
     * @param live         a source is playing right now (a capture plays without RF status)
     * @param tunerPresent the hub currently has a supported USB tuner
     * @param hasSignal    the snapshot carried RF numbers
     * @param locked       those numbers report a locked demodulator
     */
    public static State of(boolean live, boolean tunerPresent, boolean hasSignal, boolean locked) {
        // A capture is a source of its own: it has no RF numbers and it is not waiting for a tuner.
        if (live && !hasSignal) return State.PLAYING;
        if (!tunerPresent) return State.NO_TUNER;
        if (!hasSignal || !locked) return State.TUNING;
        return State.LOCKED;
    }
}
