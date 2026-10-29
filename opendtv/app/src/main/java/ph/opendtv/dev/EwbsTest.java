package ph.opendtv.dev;

import android.app.Activity;
import android.view.Gravity;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ph.opendtv.ts.EwbsAreaTable;
import ph.opendtv.ts.TsDemux;

/**
 * The synthetic EWBS alert picker.
 *
 * <p>It lives here rather than in {@code MainActivity} because it is now opened from the
 * Developers screen: a dialog built with a paused activity's context attaches to that
 * activity's window, so it appears behind the screen that asked for it - which is exactly
 * what "the selection goes to the background" was. Taking the activity that is actually in
 * front fixes it without a window token or an overlay.
 */
public final class EwbsTest {

    private static final int[] CODES = {0, 0x200, 0x400, 0x420, 0x42B, 0x426, 0x600};

    private static final String[] LABELS = {
            "Stop test / clear",
            "Nationwide (0x200)",
            "Luzon (0x400)",
            "NCR (0x420)",
            "Pasay (0x42B)",
            "Manila (0x426)",
            "Visayas (0x600)"
    };

    private EwbsTest() {
    }

    /**
     * @param owner  the activity in front; the dialog is attached to its window
     * @param after  what to refresh once the test state changed, or null
     */
    public static void show(Activity owner, final TsDemux demux, final Runnable after) {
        if (owner == null || owner.isFinishing()) return;
        if (demux == null) {
            Toast.makeText(owner, "Tune a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(owner)
                .setTitle("Test EWBS alert")
                .setItems(LABELS, (dlg, which) -> {
                    if (which == 0) {
                        demux.clearTestEwbs();
                        toast(owner, "Test alert cleared");
                    } else {
                        demux.setTestEwbs(CODES[which], true);
                        toast(owner, "Test alert · " + EwbsAreaTable.name(CODES[which]));
                    }
                    if (after != null) after.run();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** The watch screen draws the banner off-screen; make the confirmation readable there too. */
    private static void toast(Activity owner, String text) {
        Toast t = Toast.makeText(owner, text, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.BOTTOM, 0, 0);
        t.show();
    }
}
