package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The three watch surfaces share one decision; these four cases are the ones the owner can see.
 */
public class WatchSignalTextTest {

    @Test
    public void noTunerAndNothingPlayingSaysNoTunerAvailable() {
        assertEquals(WatchSignalText.State.NO_TUNER,
                WatchSignalText.of(false, false, false, false));
    }

    @Test
    public void noTunerWithStaleRfNumbersStillSaysNoTunerAvailable() {
        // A tuner can disappear between snapshots; a leftover lock number must not resurrect "Tuning…".
        assertEquals(WatchSignalText.State.NO_TUNER,
                WatchSignalText.of(false, false, true, true));
    }

    @Test
    public void capturePlayingWithoutRfStatusSaysNothingAboutTuning() {
        assertEquals(WatchSignalText.State.PLAYING,
                WatchSignalText.of(true, false, false, false));
    }

    @Test
    public void tunerPresentButNotLockedIsTuning() {
        assertEquals(WatchSignalText.State.TUNING,
                WatchSignalText.of(false, true, false, false));
        assertEquals(WatchSignalText.State.TUNING,
                WatchSignalText.of(false, true, true, false));
    }

    @Test
    public void lockedTunerShowsNumbers() {
        assertEquals(WatchSignalText.State.LOCKED,
                WatchSignalText.of(false, true, true, true));
    }
}
