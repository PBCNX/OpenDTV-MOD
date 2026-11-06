package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The scan state transitions the activity and the scan threads rely on. */
public class ScanStateTest {

    @Test
    public void beginOnlyWhenIdleAndFreshStopFlag() {
        ScanState state = new ScanState();
        assertTrue(state.begin());
        state.cancel();
        assertTrue(state.cancelRequested());
        assertFalse(state.begin());          // already scanning
        state.finish();
        assertFalse(state.isScanning());
        assertTrue(state.begin());           // a new scan clears the old stop request
        assertFalse(state.cancelRequested());
    }

    @Test
    public void cancelDoesNotEndTheScanByItself() {
        ScanState state = new ScanState();
        state.begin();
        state.cancel();
        assertTrue(state.isScanning());
        assertTrue(state.cancelRequested());
    }
}
