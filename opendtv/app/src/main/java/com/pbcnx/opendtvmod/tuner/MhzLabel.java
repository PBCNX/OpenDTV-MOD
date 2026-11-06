package com.pbcnx.opendtvmod.tuner;

import java.util.Locale;

/**
 * Frequency-text primitives. The one user-facing label rule (UHF/VHF/MHz selection) lives in
 * {@link MuxSweep#label}; these methods are the number formatting that rule and the chips share.
 */
public final class MhzLabel {

    private MhzLabel() {
    }

    /** "497.143 MHz" - the form every surface prints. */
    public static String mhz(int khz) {
        return String.format(Locale.US, "%.3f MHz", khz / 1000.0);
    }

    /** "497.143" - the number without the unit, for a line that already names the band. */
    public static String number(int khz) {
        return String.format(Locale.US, "%.3f", khz / 1000.0);
    }

}
