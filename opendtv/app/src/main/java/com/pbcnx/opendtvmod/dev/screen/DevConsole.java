package com.pbcnx.opendtvmod.dev.screen;


import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One monospaced, selectable, copyable panel: every diagnostic row on the Developers screen shows
 * its answer this way, so the presentation lives here instead of in the activity.
 */
public final class DevConsole {

    private DevConsole() {
    }

    public static void show(Activity act, String title, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        float d = act.getResources().getDisplayMetrics().density;
        tv.setTextSize(11f);
        tv.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));
        ScrollView sv = new ScrollView(act);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(act)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Copy", (dd, ww) -> {
                    ClipboardManager cm =
                            (ClipboardManager) act.getSystemService(Activity.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(title, text));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Wall clock for a stashed snapshot; "--:--:--" when nothing has been taken. */
    public static String timeOf(long ms) {
        return ms <= 0 ? "--:--:--"
                : new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date(ms));
    }
}
