package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The channel-step arithmetic: wrap both ways, and behaviour with no current row. */
public class ChannelStepTest {

    @Test
    public void wrapsBothWays() {
        assertEquals(0, ChannelStep.nextIndex(5, 4, 1));
        assertEquals(4, ChannelStep.nextIndex(5, 0, -1));
        assertEquals(3, ChannelStep.nextIndex(5, 2, 1));
    }

    @Test
    public void emptyListHasNoStep() {
        assertEquals(-1, ChannelStep.nextIndex(0, -1, 1));
    }

    @Test
    public void missingCurrentRowStepsFromBeforeTheStart() {
        assertEquals(0, ChannelStep.nextIndex(4, -1, 1));
        assertEquals(2, ChannelStep.nextIndex(4, -1, -1));
    }
}
