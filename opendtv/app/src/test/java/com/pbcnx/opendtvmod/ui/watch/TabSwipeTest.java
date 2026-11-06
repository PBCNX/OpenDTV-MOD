package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The tab-swipe thresholds: horizontal drags claim and step, vertical drags stay with the list. */
public class TabSwipeTest {

    @Test
    public void swipeLeftGoesToTheNextTab() {
        TabSwipe swipe = new TabSwipe();
        swipe.down(100, 100);
        assertTrue(swipe.move(140, 102, 24));
        assertEquals(1, swipe.up(20, 104, 64));
        assertFalse(swipe.claimed());
    }

    @Test
    public void swipeRightGoesToThePreviousTab() {
        TabSwipe swipe = new TabSwipe();
        swipe.down(100, 100);
        assertTrue(swipe.move(60, 100, 24));
        assertEquals(-1, swipe.up(180, 100, 64));
    }

    @Test
    public void verticalDragIsNotClaimed() {
        TabSwipe swipe = new TabSwipe();
        swipe.down(100, 100);
        assertFalse(swipe.move(120, 180, 24));
        assertEquals(0, swipe.up(120, 220, 64));
    }

    @Test
    public void shortHorizontalDragDoesNotStep() {
        TabSwipe swipe = new TabSwipe();
        swipe.down(100, 100);
        assertTrue(swipe.move(140, 100, 24));
        assertEquals(0, swipe.up(140, 100, 64));
    }
}
