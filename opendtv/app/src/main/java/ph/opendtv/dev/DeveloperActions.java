package ph.opendtv.dev;

import java.util.HashMap;
import java.util.Map;

/**
 * The seam between the hidden Developers screen and the code that can actually act.
 *
 * <p>The screen, the rows, their subtitles and the confirm dialogs belong to the UI session.
 * Every row that touches tuner state, storage or preferences is somebody else's handler, and
 * the two halves meet here the way they meet at the REC key: a row answers to one string id,
 * and whoever can do the work registers a {@link Runnable} for that id. A row whose id has no
 * handler renders disabled, never as a dead row that looks live.
 *
 * <p>The ids are the contract. They are stable strings, they are what the work order names,
 * and they must not be renamed to suit a class name.
 *
 * <p>Handlers are resolved when the screen opens, not when it is built, so register from the
 * owner's {@code onCreate()} (which runs again on recreate) and keep the Runnable cheap. A
 * handler that closes over an Activity is only valid until that instance is destroyed.
 *
 * <p>An id the screen does not know about is ignored; the screen never throws on one.
 */
public final class DeveloperActions {

    // ------------------------------------------------------------------ V1 ids

    /** Opens {@code UsbProbeActivity}. Handled by the screen itself. */
    public static final String USB_PROBE = "dev_usb_probe";
    /** Opens {@code AnalyzerActivity} through {@code MainActivity.openAnalyzer()}. Screen's own. */
    public static final String OPEN_ANALYZER = "dev_open_analyzer";
    /** A storage picker opened at {@code /sdcard/OpenDTV}. Screen's own. */
    public static final String OPEN_FOLDER = "dev_open_folder";
    /** Version, commit, ABI, SDK - copyable. Screen's own. */
    public static final String BUILD_INFO = "dev_build_info";
    /** Deletes the saved channel list. Needs a handler and a confirm. */
    public static final String CLEAR_CHANNELS = "dev_clear_channels";
    /** Clears every star and the Favorites order. Needs a handler. */
    public static final String RESET_FAVORITES = "dev_reset_favorites";
    /** Every preference, then a clean restart. Needs a handler and a confirm. */
    public static final String RESET_ALL = "dev_reset_all";

    // ------------------------------------------------------------------ reserved, hidden in V1
    //
    // Declared so the work order's ids exist in one place and nothing gets invented later.
    // No screen reads these: V1 has no row for them, by design ("more diagnostics land after
    // 0.1.0"), and a row added here without a handler would only be a grey rectangle.

    public static final String FORCE_RESTART = "dev_force_restart";           // V2, native
    public static final String ARBITRARY_FREQUENCY = "dev_arbitrary_frequency"; // V2, native
    public static final String DUMP_TS = "dev_dump_ts";                       // V2, native
    public static final String FORCE_SOURCE = "dev_force_source";             // V2, native
    public static final String TEST_EWBS = "dev_test_ewbs";                   // V3, native
    public static final String EWBS_DESCRIPTOR = "dev_ewbs_descriptor";       // V3, native
    public static final String CAPTION_PROBE = "dev_caption_probe";           // V3, native
    public static final String VERBOSE_LOGGING = "dev_verbose_logging";       // V3, native

    /**
     * A row is runnable, but the screen itself can still be shut: the owner's door is the About
     * image, and the unlock lasts for this process only - nothing is written to preferences, so
     * quitting the app (which ends the process) hides the row again on the next launch.
     */
    private static boolean unlocked;

    private static final Map<String, Runnable> HANDLERS = new HashMap<>();

    /**
     * The demux the watch screen is currently reading, or null when nothing is tuned.
     * A static hand-off because the Developers screen is a different activity from the one
     * that owns the tuner; it is set in {@code MainActivity.onResume} and cleared with it.
     */
    private static volatile ph.opendtv.ts.TsDemux demux;

    public static void setDemux(ph.opendtv.ts.TsDemux d) {
        demux = d;
    }

    public static ph.opendtv.ts.TsDemux demux() {
        return demux;
    }

    private DeveloperActions() {
    }

    /** Taps on the About image before the door opens, the same idea as Android's build number. */
    public static final int DOOR_TAPS = 13;

    /**
     * The first taps say nothing, exactly like the build number: a tap that lands by accident
     * must not announce a developer door. From the tap after this many, the countdown shows.
     */
    public static final int DOOR_SILENT_TAPS = 5;

    private static int doorTaps;

    /** How many times the About image has been tapped in this process. */
    public static synchronized int doorTaps() {
        return doorTaps;
    }

    /**
     * One tap on the About image. Returns how many taps are still needed, or 0 when this tap is
     * the one that unlocks (and again 0 for every tap after that - ask {@link #isUnlocked()} first
     * if the difference matters). The count lives in memory with the unlock, so quitting the app
     * starts it over at 13.
     */
    public static synchronized int tapDoor() {
        if (unlocked) return 0;
        doorTaps++;
        if (doorTaps >= DOOR_TAPS) {
            unlocked = true;
            return 0;
        }
        return DOOR_TAPS - doorTaps;
    }

    /** Unlocks without the taps. The screen and the tests use the count; this is the door itself. */
    public static synchronized void unlock() {
        unlocked = true;
    }

    /** True once the About image has been tapped {@link #DOOR_TAPS} times in this process. */
    public static synchronized boolean isUnlocked() {
        return unlocked;
    }

    /**
     * Makes {@code id} runnable on the Developers screen. Registering the same id again
     * replaces the handler - that is what a recreated owner does, and it is how a handler
     * that closed over a dead Activity gets retired.
     */
    public static synchronized void register(String id, Runnable action) {
        if (id == null || id.isEmpty()) return;
        if (action == null) HANDLERS.remove(id);
        else HANDLERS.put(id, action);
    }

    /** The handler for {@code id}, or null when nobody has registered one yet. */
    public static synchronized Runnable handler(String id) {
        return id == null ? null : HANDLERS.get(id);
    }

    /** Drops every registered handler. Tests only; the app never needs it. */
    public static synchronized void clear() {
        HANDLERS.clear();
    }
}
