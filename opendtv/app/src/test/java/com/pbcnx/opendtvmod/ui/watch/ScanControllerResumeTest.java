package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The manual-scan dismiss decision (jpdcs#14): after the sheet closes, replay the radio only when it
 * should. The decision is pure; the retune it triggers is tuner-gated and therefore not in here.
 */
public class ScanControllerResumeTest {

    @Test public void restoresWhenTheMeterMovedAndNoScanRan() {
        assertTrue(ScanController.shouldResume(false, true, false));
    }

    @Test public void doesNotRestoreWhenAScanRan() {
        // The scan has its own result to show; restoring over it would fight the scanner.
        assertFalse(ScanController.shouldResume(true, true, false));
    }

    @Test public void doesNotRestoreWhenTheMeterNeverLeft() {
        assertFalse(ScanController.shouldResume(false, false, false));
    }

    @Test public void doesNotRestoreWhileAScanIsRunning() {
        assertFalse(ScanController.shouldResume(false, true, true));
    }
}
