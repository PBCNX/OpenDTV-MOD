package com.pbcnx.opendtvmod.dev.screen;

import com.pbcnx.opendtvmod.dev.DeveloperActions;

import java.util.ArrayList;
import java.util.List;

import com.pbcnx.opendtvmod.R;

/**
 * The Developers screen's row table: one entry per door, diagnostic or reset, in the order the
 * screen draws them. Kept out of the activity so the screen is wiring and the inventory is data.
 */
public final class DevRows {

    /**
     * One V1 row: what it says, and - for the destructive ones - the copy of the confirmation it
     * asks first. The copy travels with the row so a new reset cannot inherit the wrong dialog by
     * falling through a switch default.
     */
    public static final class Row {
        public final String id;
        public final String title;
        public final String subtitle;
        public final int icon;
        public final boolean confirm;
        public final String confirmTitle;
        public final String confirmMessage;
        public final String confirmVerb;

        Row(String id, String title, String subtitle, int icon) {
            this(id, title, subtitle, icon, null, null, null);
        }

        Row(String id, String title, String subtitle, int icon,
            String confirmTitle, String confirmMessage, String confirmVerb) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.confirm = confirmTitle != null;
            this.confirmTitle = confirmTitle;
            this.confirmMessage = confirmMessage;
            this.confirmVerb = confirmVerb;
        }
    }

    private DevRows() {
    }

    public static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        // The four doors are real code in this app already, which is why their subtitles say so:
        // a second way into a screen is only worth a developer row if you know it is a second way.
        rows.add(new Row(DeveloperActions.USB_PROBE, "USB probe",
                "The dongle's USB interfaces and endpoints",
                R.drawable.ic_memory));
        rows.add(new Row(DeveloperActions.OPEN_ANALYZER, "Open Analyzer",
                "Live signal, PIDs, tables and bitrates",
                R.drawable.ic_analytics));
        rows.add(new Row(DeveloperActions.OPEN_FOLDER, "Open app folder",
                "Recordings and whole-TS dumps in OpenDTV",
                R.drawable.ic_folder));
        rows.add(new Row(DeveloperActions.BUILD_INFO, "Build info",
                "Version, commit, ABI and SDK",
                R.drawable.ic_info));
        // The bug-report file: one redacted text, nothing uploaded. It sits with the other
        // diagnostics under TOOLS because it reports state rather than changing it.
        rows.add(new Row(DeveloperActions.DUMP_DIAGNOSTICS, "Export diagnostics",
                "One redacted text file · nothing is uploaded",
                R.drawable.ic_code));
        // Diagnostics live under TOOLS, not under STATE: they report, they do not change anything.
        rows.add(new Row(DeveloperActions.BML_DIAGNOSTICS, "BML diagnostics",
                "The live BML page's state, as JSON",
                R.drawable.ic_code));
        rows.add(new Row(DeveloperActions.CAPTION_PROBE, "Caption probe",
                "Caption PIDs, decoder counters and last text",
                R.drawable.ic_captions));
        rows.add(new Row(DeveloperActions.TEST_EWBS, "Test EWBS alert",
                "A test alert on this screen; nothing is broadcast",
                R.drawable.ic_ewbs_alert));
        rows.add(new Row(DeveloperActions.CLEAR_CHANNELS, "Clear channels",
                "Deletes the saved list · the next scan rebuilds it",
                R.drawable.ic_delete,
                "Clear the channel list?",
                "The saved channels and their numbers go. Run a scan to get them back.",
                "Reset"));
        rows.add(new Row(DeveloperActions.RESET_FAVORITES, "Reset favourites",
                "Clears every star and the Favorites order",
                R.drawable.ic_favorite_border,
                "Reset favourites?",
                "Every star and the Favorites order go. Channels and settings stay.",
                "Reset"));
        rows.add(new Row(DeveloperActions.RESET_ALL, "Reset all app data",
                "Channels, favourites and all settings · restarts the app",
                R.drawable.ic_restore,
                "Reset every setting?",
                "Channels, favourites and every preference go, then the app restarts.",
                "Reset and restart"));
        return rows;
    }

}
