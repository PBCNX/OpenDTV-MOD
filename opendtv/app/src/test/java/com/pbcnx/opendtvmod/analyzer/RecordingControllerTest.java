package com.pbcnx.opendtvmod.analyzer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The recording file-name rule, extracted so it can be tested without a device. */
public class RecordingControllerTest {

    @Test
    public void nameIsFrequencyNetworkAndTimestamp() {
        assertEquals("497143-GMA-01012026-120000.ts",
                RecordingController.recordingFileName(497143, "GMA", "01012026-120000"));
    }

    @Test
    public void emptyNetworkBecomesNetwork() {
        assertEquals("497143-Network-x.ts", RecordingController.recordingFileName(497143, "  ", "x"));
        assertEquals("497143-Network-x.ts", RecordingController.recordingFileName(497143, null, "x"));
    }

    @Test
    public void unsafeCharactersBecomeUnderscores() {
        assertEquals("497143-ABS_CBN_2-x.ts",
                RecordingController.recordingFileName(497143, "ABS/CBN 2", "x"));
    }
}
