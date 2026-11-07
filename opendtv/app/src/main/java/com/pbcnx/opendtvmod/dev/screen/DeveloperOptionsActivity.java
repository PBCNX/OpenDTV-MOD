package com.pbcnx.opendtvmod.dev.screen;

import com.pbcnx.opendtvmod.dev.DeveloperActions;
import com.pbcnx.opendtvmod.dev.DeveloperSurface;

import com.pbcnx.opendtvmod.App;
import com.pbcnx.opendtvmod.bml.BmlController;
import com.pbcnx.opendtvmod.ts.caption.CaptionProbe;
import com.pbcnx.opendtvmod.ui.SettingRow;
import com.pbcnx.opendtvmod.ui.StorageAccess;
import com.pbcnx.opendtvmod.MainActivity;
import com.pbcnx.opendtvmod.R;


import com.pbcnx.opendtvmod.analyzer.UsbProbeActivity;

import com.pbcnx.opendtvmod.ui.Dims;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.diag.DiagnosticsCollector;
import com.pbcnx.opendtvmod.diag.DiagnosticsDump;


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
 * <pre>am start -n com.pbcnx.opendtvmod/com.pbcnx.opendtvmod.dev.DeveloperOptionsActivity</pre>
 */
public class DeveloperOptionsActivity extends AppCompatActivity {

    private LinearLayout list;

    /** The developer surface's process state (door, handlers, demux hand-off). */
    private DeveloperSurface dev() {
        return App.developer(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The About door is the only way in. The debug manifest exports this screen for the bench,
        // so the guard cannot be compiled out of debug builds: an adb start must go through the
        // same unlock as the About art.
        if (!dev().isUnlocked()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_developer_options);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);

        // The dialog must belong to this activity: built from the watch screen's context it
        // attaches behind this window, which is why the test picker seemed to vanish.
        dev().register(DeveloperActions.TEST_EWBS,
                () -> EwbsTest.show(this, dev().demux(), null));
        dev().register(DeveloperActions.BML_DIAGNOSTICS, this::showBmlDiagnostics);
        dev().register(DeveloperActions.CAPTION_PROBE, this::showCaptionProbe);

        list.addView(section("TOOLS", false));
        List<DevRows.Row> rows = DevRows.rows();
        // The STATE section starts at the first row that changes state, wherever that is in the list.
        for (int i = 0; i < rows.size(); i++) {
            if (DeveloperActions.TEST_EWBS.equals(rows.get(i).id)) list.addView(section("STATE", true));
            addRow(rows.get(i), resolve(rows.get(i).id));
        }
        // No footer: the old one promised "more diagnostics land after 0.1.0" and 0.1.0 has shipped.
        // A screen that says what it is beats a screen that advertises what it will be.
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
            case DeveloperActions.DUMP_DIAGNOSTICS:
                return this::exportDiagnostics;
            default:
                Runnable registered = dev().handler(id);
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

    /**
     * Piece 2: what the caption path has actually seen for the playing service. One line is already
     * on the analyzer's Logs tab; this is the whole picture - the PMT's declared caption streams,
     * the decoder's counters, the last PTS and the last text - because that is what a bug report needs.
     */
    private void showCaptionProbe() {
        CaptionProbe probe = dev().captionProbe();
        if (probe == null) {
            DevConsole.show(this, "Caption probe", "Nothing has played a service since this app started.\n\n"
                    + "Tune a channel on the watch screen (or feed a capture), then come back.");
            return;
        }
        DevConsole.show(this, "Caption probe", probe.summary() + "\n\n" + probe.report());
    }

    /**
     * The engine's own snapshot of the live page. The page belongs to another activity, so this
     * answers from three places in order: the page if it is still alive, the last snapshot the watch
     * screen stashed on its way here, and - if there is neither - a sentence that says which.
     */
    private void showBmlDiagnostics() {
        final DeveloperSurface.BmlState state = dev().bmlState();
        final BmlController c = state == null ? null : state.controller;
        final String stashed = state == null ? null : state.stashed;
        final long stashedAt = state == null ? 0L : state.stashedAtMs;
        if (c == null) {
            DevConsole.show(this, "BML diagnostics", stashed != null
                    ? "The page is closed. Last snapshot, taken " + DevConsole.timeOf(stashedAt) + ":\n\n" + stashed
                    : "No data broadcast has been opened since this app started.\n\n"
                      + "Open one on the watch screen (DATA, or a long OK), then come straight back.");
            return;
        }
        c.diagnostics(json -> {
            if ("no-page".equals(json) || "no-engine".equals(json) || json == null || "null".equals(json)) {
                String why = "no-engine".equals(json)
                        ? "the page is still loading, or the engine did not start"
                        : "the page is closed";
                DevConsole.show(this, "BML diagnostics", stashed != null
                        ? "The page cannot answer (" + why + ").\nLast snapshot, taken "
                          + DevConsole.timeOf(stashedAt) + ":\n\n" + stashed
                        : "The page cannot answer (" + why + "), and nothing has been captured yet.\n\n"
                          + "Open the data broadcast and keep it open, then tap this row again.");
                return;
            }
            DevConsole.show(this, "BML diagnostics", "Taken " + DevConsole.timeOf(System.currentTimeMillis()) + "\n\n" + json);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        com.pbcnx.opendtvmod.ui.StorageAccess.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.pbcnx.opendtvmod.ui.StorageAccess.resumePending(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The rows' handlers close over this screen; the ids outlive it only as strings.
        dev().unregister(DeveloperActions.TEST_EWBS,
                DeveloperActions.BML_DIAGNOSTICS, DeveloperActions.CAPTION_PROBE);
    }

    private void addRow(final DevRows.Row row, final Runnable action) {
        View view = getLayoutInflater().inflate(R.layout.item_setting, list, false);
        // The shared Settings row carries no background of its own: SettingAdapter installs a
        // per-view plate that draws the focused/pressed state, and this screen has no adapter. So a
        // D-pad stop here drew nothing and a tap had no press feedback (owner, 2026-11-06). The
        // app's standard row background carries the same states every other list uses:
        // default plate, gold 2 dp ring when focused, light 1 dp ring when pressed.
        view.setBackgroundResource(R.drawable.bg_item_focusable_selector);
        SettingRow.bind(view, row.title, row.subtitle, row.icon);
        if (action == null) {
            // No handler yet. Dim it and take it out of the focus walk: a D-pad stop that does
            // nothing reads as a broken screen, and a row that looks live but is not is worse.
            view.setEnabled(false);
            view.setFocusable(false);
            view.setClickable(false);
            view.setAlpha(SettingRow.DISABLED_ALPHA);
        } else {
            view.setOnClickListener(v -> run(row, action));
        }
        list.addView(view);
    }

    private void run(DevRows.Row row, Runnable action) {
        if (!row.confirm) {
            action.run();
            return;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(row.confirmTitle)
                .setMessage(row.confirmMessage)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(row.confirmVerb, (d, which) -> action.run())
                .show();
        // On the set nothing is focused when a dialog opens, so OK does nothing until the D-pad
        // moves. Start on Cancel: visible, and the safe answer for a row that deletes state.
        if (!hasTouchscreen()) {
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) cancel.requestFocus();
        }
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
        intent.putExtra(DeveloperActions.OPEN_ANALYZER, true);
        startActivity(intent);
        finish();
    }

    /** A picker opened at the app's folder. The folder is created first so the hint resolves. */
    private void openAppFolder() {
        if (!StorageAccess.ensure(this, this::openAppFolder)) return;
        File dir = com.pbcnx.opendtvmod.ui.StorageAccess.appDir(this);
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
        final String text = DevBuildReport.build(this);
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
            window.setLayout(Math.min(m.widthPixels - Dims.dp(this, 48), Dims.dp(this, 680)), (int) (m.heightPixels * 0.78f));
        }

        content.findViewById(R.id.build_info_copy).setOnClickListener(v -> DevBuildReport.copy(this, text));
        content.findViewById(R.id.build_info_close).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (!hasTouchscreen()) content.findViewById(R.id.build_info_copy).requestFocus();
    }

    // ------------------------------------------------------------------ diagnostics export

    /**
     * The one-press bug report: two opt-in checkboxes, one small text file, nothing uploaded.
     * This is deliberately not a share sheet - sending it stays a separate, deliberate act.
     */
    private void exportDiagnostics() {
        View content = getLayoutInflater().inflate(R.layout.dialog_export_diagnostics, null);
        CheckBox deviceModel = content.findViewById(R.id.export_include_device);
        CheckBox channelData = content.findViewById(R.id.export_include_channels);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Export diagnostics")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Export", (d, which) ->
                        writeDiagnostics(deviceModel.isChecked(), channelData.isChecked()))
                .show();
        // On the set nothing is focused when a dialog opens; start on the first opt-in, still off.
        if (!hasTouchscreen()) {
            CheckBox first = dialog.findViewById(R.id.export_include_device);
            if (first != null) first.requestFocus();
        }
    }

    /**
     * Builds the text first, then writes exactly that string: the file is the redactor's output,
     * not a second rendering of the same facts. The write happens off the UI thread because it
     * touches external storage; the toast is the only UI.
     */
    private void writeDiagnostics(final boolean includeDeviceModel, final boolean includeChannelData) {
        final String text = DiagnosticsDump.build(
                DiagnosticsCollector.collect(this, includeDeviceModel, includeChannelData));
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            File base = appContext.getExternalFilesDir(null);
            if (base == null) base = appContext.getFilesDir();
            File dir = new File(base, "diagnostics");
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File out = new File(dir, "opendtv-diagnostics-" + stamp + ".txt");
            for (int i = 2; out.exists() && i < 100; i++) {
                out = new File(dir, "opendtv-diagnostics-" + stamp + "-" + i + ".txt");
            }
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("cannot create " + dir);
                }
                FileOutputStream stream = new FileOutputStream(out);
                try {
                    stream.write(text.getBytes(StandardCharsets.UTF_8));
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
                final String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> Toast.makeText(DeveloperOptionsActivity.this,
                        "Could not write diagnostics: " + reason, Toast.LENGTH_LONG).show());
                return;
            }
            final File written = out;
            runOnUiThread(() -> Toast.makeText(DeveloperOptionsActivity.this,
                    "Saved " + written.getAbsolutePath(), Toast.LENGTH_LONG).show());
        }, "diag-export").start();
    }

    private boolean hasTouchscreen() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
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
        // The rows carry their own 16 dp side margins (item_setting), and the list no longer pads
        // the sides, so the section label gets the same margin to line up with the row cards.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(Dims.dp(this, 16));
        params.setMarginEnd(Dims.dp(this, 16));
        header.setLayoutParams(params);
        // The header layout's ROOT is a LinearLayout since 2026-11-04 (an optional section icon sits
        // before the label), so the title is a CHILD. This used to cast the root to TextView and crashed
        // on launch the moment that shared layout changed - a shared layout's root type is part of its
        // contract, and a runtime cast was the only place that contract was written down. Handle both
        // shapes, so it cannot break again whichever way the layout goes.
        TextView text = header instanceof TextView
                ? (TextView) header
                : header.findViewById(R.id.tv_setting_title);
        if (text != null) text.setText(title);
        if (gap) header.setPadding(header.getPaddingLeft(), Dims.dp(this, 20), header.getPaddingRight(), Dims.dp(this, 4));
        return header;
    }


}
