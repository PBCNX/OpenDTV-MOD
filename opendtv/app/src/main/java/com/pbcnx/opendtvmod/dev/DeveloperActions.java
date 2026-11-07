package com.pbcnx.opendtvmod.dev;

/**
 * The seam between the hidden Developers screen and the code that can actually act: the stable
 * string ids every row answers to, and the door's tap counts.
 *
 * <p>The screen, the rows, their subtitles and the confirm dialogs belong to the UI session. Every
 * row that touches tuner state, storage or preferences is somebody else's handler, and the two
 * halves meet through the {@link DeveloperSurface} instance the {@link com.pbcnx.opendtvmod.App} owns - not
 * through static state. A row whose id has no handler renders disabled, never as a dead row that
 * looks live.</p>
 *
 * <p>The ids are the contract. They are stable strings, they are what the work order names, and
 * they must not be renamed to suit a class name.</p>
 */
public final class DeveloperActions {

    // ------------------------------------------------------------------ V1 ids

    /** Opens {@code UsbProbeActivity}. Handled by the screen itself. */
    public static final String USB_PROBE = "dev_usb_probe";
    /** Opens {@code AnalyzerActivity} through the developers screen. Screen's own. */
    public static final String OPEN_ANALYZER = "dev_open_analyzer";
    /** A storage picker opened at {@code /sdcard/OpenDTV}. Screen's own. */
    public static final String OPEN_FOLDER = "dev_open_folder";
    /** Version, commit, ABI, SDK - copyable. Screen's own. */
    public static final String BUILD_INFO = "dev_build_info";
    /** Writes one redacted diagnostics text file and shows its path. Nothing is uploaded. */
    public static final String DUMP_DIAGNOSTICS = "dev_dump_diagnostics";
    /** Deletes the saved channel list. Needs a handler and a confirm. */
    public static final String CLEAR_CHANNELS = "dev_clear_channels";
    /** Clears every star and the Favorites order. Needs a handler. */
    public static final String RESET_FAVORITES = "dev_reset_favorites";
    /** Every preference, then a clean restart. Needs a handler and a confirm. */
    public static final String RESET_ALL = "dev_reset_all";

    /** Raises the EWBS alert locally, with no broadcast. Live since V3. */
    public static final String TEST_EWBS = "dev_test_ewbs";

    /** The live data-broadcast page's own P1 snapshot, as JSON. */
    public static final String BML_DIAGNOSTICS = "dev_bml_diagnostics";

    /** The caption path's own counters, PIDs and last text: what arrived and what decoded. */
    public static final String CAPTION_PROBE = "dev_caption_probe";

    // ------------------------------------------------------------------ reserved, no row yet

    public static final String ARBITRARY_FREQUENCY = "dev_arbitrary_frequency"; // V2, native
    public static final String DUMP_TS = "dev_dump_ts";                       // V2, native
    public static final String FORCE_SOURCE = "dev_force_source";             // V2, native
    public static final String EWBS_DESCRIPTOR = "dev_ewbs_descriptor";       // V3, native
    public static final String VERBOSE_LOGGING = "dev_verbose_logging";       // V3, native
    /** Retired in 0.1.1: the BML engine A/B closed, the 1.x bundle is gone and there is nothing to
     *  choose. The id stays declared so the string is never re-used for something else. */
    public static final String FORCE_BML = "dev_force_bml";                   // V3, retired

    /** Taps on the About image before the door opens, the same idea as Android's build number. */
    public static final int DOOR_TAPS = 13;
    /** The first taps say nothing; the countdown starts after this many. */
    public static final int DOOR_SILENT_TAPS = 5;

    private DeveloperActions() {
    }
}
