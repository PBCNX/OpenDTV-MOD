package ph.opendtv;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import ph.opendtv.dev.DeveloperActions;

/**
 * The hidden Developers screen. One list of diagnostics and state resets, each row answering to
 * a string id (see {@link DeveloperActions}).
 *
 * <p>V1 is deliberately small: four doors that already exist elsewhere in the app, three state
 * resets whose handlers live in whoever owns that state, and one footer line. A row with no
 * handler renders disabled instead of looking live and doing nothing, so the surface can ship
 * before the handlers do.
 *
 * <p>Reached from a row at the bottom of Settings while its unlock gesture is undecided, and
 * over adb meanwhile:
 * <pre>am start -n com.pbcnx.opendtvmod/ph.opendtv.DeveloperOptionsActivity</pre>
 */
public class DeveloperOptionsActivity extends AppCompatActivity {

    private LinearLayout list;

    /** One V1 row: what it says, and whether it asks before it runs. */
    private static final class Row {
        final String id;
        final String title;
        final String subtitle;
        final int icon;
        final boolean confirm;

        Row(String id, String title, String subtitle, int icon, boolean confirm) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.confirm = confirm;
        }
    }

    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        // The four doors are real code in this app already, which is why their subtitles say so:
        // a second way into a screen is only worth a developer row if you know it is a second way.
        rows.add(new Row(DeveloperActions.USB_PROBE, "USB probe",
                "Interfaces and endpoints of the dongle · already available from the Analyzer",
                R.drawable.ic_remote, false));
        rows.add(new Row(DeveloperActions.OPEN_ANALYZER, "Open Analyzer",
                "Signal and TS analyzer · already available from Settings, ADVANCED",
                R.drawable.ic_analytics, false));
        rows.add(new Row(DeveloperActions.OPEN_FOLDER, "Open app folder",
                "Storage picker at /sdcard/OpenDTV · recordings and whole-TS dumps",
                R.drawable.ic_more_vert, false));
        rows.add(new Row(DeveloperActions.BUILD_INFO, "Build info",
                "Version, commit, ABI, SDK · selectable, and copied in one press",
                R.drawable.ic_info, false));
        rows.add(new Row(DeveloperActions.TEST_EWBS, "Test EWBS alert",
                "Fires a synthetic emergency alert · debug only, nothing is broadcast",
                R.drawable.ic_ewbs_alert, false));
        rows.add(new Row(DeveloperActions.CLEAR_CHANNELS, "Clear channels",
                "Deletes the saved channel list; the next scan rebuilds it",
                R.drawable.ic_scan, true));
        rows.add(new Row(DeveloperActions.RESET_FAVORITES, "Reset favourites",
                "Empties every star and the Favorites order",
                R.drawable.ic_favorite_border, true));
        rows.add(new Row(DeveloperActions.RESET_ALL, "Reset all app data",
                "Channels, favourites and every preference, then a clean restart",
                R.drawable.ic_close, true));
        return rows;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_options);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);

        // The dialog must belong to this activity: built from the watch screen's context it
        // attaches behind this window, which is why the test picker seemed to vanish.
        DeveloperActions.register(DeveloperActions.TEST_EWBS,
                () -> ph.opendtv.dev.EwbsTest.show(this, DeveloperActions.demux(), null));

        list.addView(section("TOOLS", false));
        List<Row> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            if (i == 4) list.addView(section("STATE", true));
            addRow(rows.get(i), resolve(rows.get(i).id));
        }

        TextView footer = new TextView(this);
        footer.setText("More diagnostics land after 0.1.0.");
        TextViewCompat.setTextAppearance(footer,
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        footer.setTextColor(colour(com.google.android.material.R.attr.colorOnSurfaceVariant));
        footer.setPadding(dp(8), dp(20), dp(8), 0);
        list.addView(footer);
    }

    /**
     * A row's handler: the screen's own doors first, then whoever registered one for the id.
     * Nothing registered is a normal state, not an error - it is how a row waits for its half.
     */
    private Runnable resolve(String id) {
        switch (id) {
            case DeveloperActions.USB_PROBE:
                return () -> startActivity(new Intent(this, UsbProbeActivity.class));
            case DeveloperActions.OPEN_ANALYZER:
                return this::openAnalyzer;
            case DeveloperActions.OPEN_FOLDER:
                return this::openAppFolder;
            case DeveloperActions.BUILD_INFO:
                return this::showBuildInfo;
            default:
                Runnable registered = DeveloperActions.handler(id);
                if (registered == null) return null;
                if (DeveloperActions.RESET_ALL.equals(id)) {
                    // "Confirm once, then a clean restart": the handler clears what it owns, the
                    // screen owns the restart. A handler that returns before it is done will be cut.
                    return () -> {
                        registered.run();
                        restartApp();
                    };
                }
                return registered;
        }
    }

    private void addRow(final Row row, final Runnable action) {
        View view = getLayoutInflater().inflate(R.layout.item_setting, list, false);
        ((TextView) view.findViewById(R.id.tv_setting_title)).setText(row.title);
        ((TextView) view.findViewById(R.id.tv_setting_desc)).setText(row.subtitle);
        ((ImageView) view.findViewById(R.id.iv_setting_icon)).setImageResource(row.icon);
        if (action == null) {
            // No handler yet. Dim it and take it out of the focus walk: a D-pad stop that does
            // nothing reads as a broken screen, and a row that looks live but is not is worse.
            view.setEnabled(false);
            view.setFocusable(false);
            view.setClickable(false);
            view.setAlpha(0.38f);
        } else {
            view.setOnClickListener(v -> run(row, action));
        }
        list.addView(view);
    }

    private void run(Row row, Runnable action) {
        if (!row.confirm) {
            action.run();
            return;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(confirmTitle(row.id))
                .setMessage(confirmMessage(row.id))
                .setNegativeButton("Cancel", null)
                .setPositiveButton(confirmVerb(row.id), (d, which) -> action.run())
                .show();
        // On the set nothing is focused when a dialog opens, so OK does nothing until the D-pad
        // moves. Start on Cancel: visible, and the safe answer for a row that deletes state.
        if (!hasTouchscreen()) {
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) cancel.requestFocus();
        }
    }

    private static String confirmTitle(String id) {
        switch (id) {
            case DeveloperActions.CLEAR_CHANNELS:
                return "Clear the channel list?";
            case DeveloperActions.RESET_FAVORITES:
                return "Reset favourites?";
            default:
                return "Reset every setting?";
        }
    }

    private static String confirmMessage(String id) {
        switch (id) {
            case DeveloperActions.CLEAR_CHANNELS:
                return "The saved channels and their numbers go. Run a scan to get them back.";
            case DeveloperActions.RESET_FAVORITES:
                return "Every star and the Favorites order go. Channels and settings stay.";
            default:
                return "Channels, favourites and every preference go, then the app restarts.";
        }
    }

    private static String confirmVerb(String id) {
        return DeveloperActions.RESET_ALL.equals(id) ? "Reset and restart" : "Reset";
    }

    // ------------------------------------------------------------------ doors

    /**
     * The Analyzer has to be entered through MainActivity's own door: it shuts the tuner hub down
     * first, and a bare startActivity from here would leave two owners on the dongle and a dead
     * hub behind it (M5 defect 6). MainActivity handles the extra in onCreate and onNewIntent.
     */
    private void openAnalyzer() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_DEV_ANALYZER, true);
        startActivity(intent);
        finish();
    }

    /** A picker opened at the app's folder. The folder is created first so the hint resolves. */
    private void openAppFolder() {
        File dir = new File(Environment.getExternalStorageDirectory(), "OpenDTV");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // On the set the picker intent resolves to a stub (this box offers "Activity Stub" and a
        // file manager that shows nothing) and a file list is not D-pad territory anyway, so the
        // useful half of this row there is the path. Choosing the picker by package name is not an
        // option: package visibility hides DocumentsUI from a query, and on MIUI the intent's
        // default resolution is not the app that opens the folder.
        if (!hasTouchscreen()) {
            Toast.makeText(this, "The app folder is " + dir, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (Build.VERSION.SDK_INT >= 26) {
            // 26 is where EXTRA_INITIAL_URI exists; older systems open at their own last folder.
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:OpenDTV"));
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker here · the folder is " + dir, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ build info

    private void showBuildInfo() {
        final String text = buildInfo();
        View content = getLayoutInflater().inflate(R.layout.dialog_build_info, null);
        TextView body = content.findViewById(R.id.build_info_text);
        body.setText(text);
        // Selection is a touch affordance: on a set it only makes the text a focus stop the D-pad
        // cannot leave, and the copy pill is one press away there.
        body.setTextIsSelectable(hasTouchscreen());

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Build info")
                .setView(content)
                .create();

        // A dialog sized by its content is taller than a 391 dp phone screen in landscape, and the
        // scrolling block needs the window height before it is shown, not after: the content is
        // one weighted child, so a wrap_content window would measure it to zero.
        DisplayMetrics m = getResources().getDisplayMetrics();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Math.min(m.widthPixels - dp(48), dp(680)), (int) (m.heightPixels * 0.78f));
        }

        content.findViewById(R.id.build_info_copy).setOnClickListener(v -> copyBuildInfo(text));
        content.findViewById(R.id.build_info_close).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (!hasTouchscreen()) content.findViewById(R.id.build_info_copy).requestFocus();
    }

    private boolean hasTouchscreen() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    private void copyBuildInfo(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenDTV build info", text));
        Toast.makeText(this, "Build info copied", Toast.LENGTH_SHORT).show();
    }

    private String buildInfo() {
        PackageManager pm = getPackageManager();
        String version = "?";
        long versionCode = -1;
        try {
            PackageInfo info = pm.getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // our own package cannot be missing; the row prints "?" if it is
        }
        int targetSdk = getApplicationInfo().targetSdkVersion;
        DisplayMetrics m = getResources().getDisplayMetrics();
        String[] abis = Build.SUPPORTED_ABIS;
        boolean is64 = abis.length > 0 && Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains(abis[0]);
        // The dialog is width-bounded below; 12 sp monospace advances about 7.2 dp per character.
        int textDp = Math.min((int) (m.widthPixels / m.density) - 48, 680) - 48;
        int columns = Math.max(32, (int) (textDp / 7.2f));

        StringBuilder b = new StringBuilder();
        String[][] rows = {
                {"version", version + "  (" + versionCode + ")"},
                {"commit", commit()},
                {"built", BuildConfig.BUILD_TIME},
                {"package", getPackageName()},
                {"target SDK", String.valueOf(targetSdk)},
                {"device SDK", Build.VERSION.SDK_INT + "  (Android " + Build.VERSION.RELEASE + ")"},
                {"ABI", (abis.length == 0 ? "?" : abis[0]) + (is64 ? "  (64-bit)" : "  (32-bit)")},
                {"all ABIs", android.text.TextUtils.join(", ", abis)},
                {"device", Build.MANUFACTURER + " " + Build.MODEL + "  (" + Build.DEVICE + ")"},
                {"display", (int) (m.widthPixels / m.density) + "x" + (int) (m.heightPixels / m.density)
                        + " dp, " + m.widthPixels + "x" + m.heightPixels + " px @ " + m.densityDpi + " dpi"},
        };
        for (String[] row : rows) line(b, row[0], row[1], columns);
        b.append('\n').append("BuildConfig\n");
        List<Field> fields = new ArrayList<>(Arrays.asList(BuildConfig.class.getFields()));
        fields.sort((x, y) -> x.getName().compareTo(y.getName()));
        for (Field f : fields) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            String value;
            try {
                value = String.valueOf(f.get(null));
            } catch (IllegalAccessException e) {
                value = "?";
            }
            b.append("  ").append(f.getName()).append(" = ").append(value).append('\n');
        }
        return b.toString().trim();
    }

    /**
     * One key/value row in the monospace dialog. A value that does not fit continues in the value
     * column instead of starting at column 0, where a wrapped line reads as a new key.
     */
    private static void line(StringBuilder b, String key, String value, int columns) {
        String prefix = String.format(Locale.ROOT, "%-12s ", key);
        List<String> parts = wrap(value, Math.max(12, columns - prefix.length()));
        for (int i = 0; i < parts.size(); i++) {
            if (i == 0) {
                b.append(prefix);
            } else {
                for (int p = 0; p < prefix.length(); p++) b.append(' ');
            }
            b.append(parts.get(i)).append('\n');
        }
    }

    /** Greedy wrap, breaking at the comma first, then at a space, then anywhere. */
    private static List<String> wrap(String value, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String segment : value.split(", ")) {
            if (current.length() > 0 && current.length() + 2 + segment.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(", ");
            for (String word : segment.split(" ")) {
                int needed = current.length() == 0 ? word.length() : current.length() + 1 + word.length();
                if (current.length() > 0 && needed > width) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append(' ');
                current.append(word);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /**
     * The commit, if the build carries one, so a build without a stamp says so instead of
     * inventing one: native's versioning commit can add {@code GIT_COMMIT} as a buildConfigField
     * and this row follows with no code change here (the BuildConfig block below lists whatever
     * the build actually stamped).
     */
    private static String commit() {
        String commit = BuildStamp.commit();
        return commit.isEmpty() ? "not embedded in this build" : commit;
    }

    // ------------------------------------------------------------------ restart

    /**
     * A clean restart: an empty task with the main activity on it and nothing of the old session
     * behind it, so every preference and the channel list are read again from disk.
     *
     * <p>Not a new process. The usual trick - schedule a launch a moment out, then kill the
     * process - does not come back on this MIUI phone: the process died and the app simply was
     * not there 20 s later, twice, with no alarm or launch in logcat. A restart that cannot
     * silently fail matters more than a new pid, and a handler that needs a new process has to
     * say so.
     */
    private void restartApp() {
        startActivity(Intent.makeRestartActivityTask(new ComponentName(this, MainActivity.class)));
        // The task clear finishes this screen; there is nothing else to do.
    }

    // ------------------------------------------------------------------ small

    private View section(String title, boolean gap) {
        View header = getLayoutInflater().inflate(R.layout.item_setting_header, list, false);
        ((TextView) header).setText(title);
        if (gap) header.setPadding(header.getPaddingLeft(), dp(20), header.getPaddingRight(), dp(4));
        return header;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int colour(int attr) {
        return com.google.android.material.color.MaterialColors.getColor(this, attr, 0xFFA7B0BC);
    }
}
