package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The screen's tuner lifecycle: the hand-off can never leave a contradictory pair. */
public class TunerLifecycleTest {

    @Test
    public void startsNotReady() {
        TunerLifecycle life = new TunerLifecycle();
        assertFalse(life.canTune());
        assertFalse(life.isHandingOver());
    }

    @Test
    public void readyAndHandoffTransitions() {
        TunerLifecycle life = new TunerLifecycle();
        life.setReady(true);
        assertTrue(life.canTune());
        life.setHandingOver(true);
        assertTrue(life.isHandingOver());
        assertFalse(life.canTune());
        life.setReady(false);                 // must not demote the hand-off
        assertTrue(life.isHandingOver());
        life.setHandingOver(false);           // the result came back: re-init next
        assertFalse(life.canTune());
        assertFalse(life.isHandingOver());
        life.setReady(true);
        assertTrue(life.canTune());
    }

    @Test
    public void resetForcesReinitialisation() {
        TunerLifecycle life = new TunerLifecycle();
        life.setReady(true);
        life.reset();
        assertFalse(life.canTune());
    }
}
