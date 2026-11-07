package com.pbcnx.opendtvmod.dev.screen;


import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pbcnx.opendtvmod.R;

/**
 * One monospaced, selectable, copyable panel: every diagnostic row on the Developers screen shows
 * its answer this way, so the presentation lives here instead of in the activity.
 *
 * <p>Material dialog with the screen's own pill pair, the same family as Build info. The platform
 * AlertDialog this used to build rendered its button bar without the app's focus plate: reachable
 * with a remote, invisible while focused. The window height is fixed before show for the same
 * reason Build info does it - the scrollable block is a weighted child and a wrap_content window
 * would measure it to nothing.</p>
 */
public final class DevConsole {

    private DevConsole() {
    }

    public static void show(Activity act, String title, String text) {
        View content = act.getLayoutInflater().inflate(R.layout.dialog_console, null);
        final TextView body = content.findViewById(R.id.console_text);
        final View scroll = content.findViewById(R.id.console_scroll);
        body.setText(text);
        body.setTextIsSelectable(true);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(act)
                .setTitle(title)
                .setView(content)
                .create();

        final DisplayMetrics m = act.getResources().getDisplayMetrics();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Math.min(m.widthPixels - Math.round(48 * m.density),
                    Math.round(680 * m.density)), WindowManager.LayoutParams.WRAP_CONTENT);
        }

        content.findViewById(R.id.console_copy).setOnClickListener(v -> {
            ClipboardManager cm =
                    (ClipboardManager) act.getSystemService(Activity.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(title, text));
        });
        content.findViewById(R.id.console_close).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        // The window wraps the real text, so a one-paragraph answer opens a compact card instead of
        // a fixed 70 %-tall panel with an empty scroll area. The cap is applied after the first
        // layout because only then is the dialog's actual content width known (Material clamps the
        // window narrower than the width above, so a pre-measure with our own width cut a line).
        body.post(() -> {
            int cap = Math.round(m.heightPixels * 0.42f);
            if (body.getHeight() > cap) {
                android.view.ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = cap;
                scroll.setLayoutParams(lp);
            }
        });
        if (!hasTouchscreen(act)) content.findViewById(R.id.console_copy).requestFocus();
    }

    /** Digital signage / set-top boxes report no touchscreen; focus then has to be placed by code. */
    private static boolean hasTouchscreen(Activity act) {
        return act.getPackageManager().hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** Wall clock for a stashed snapshot; "--:--:--" when nothing has been taken. */
    public static String timeOf(long ms) {
        return ms <= 0 ? "--:--:--"
                : new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date(ms));
    }
}

