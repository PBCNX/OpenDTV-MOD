package com.pbcnx.opendtvmod.ui.watch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;


import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.tuner.ChannelEntry;
import com.pbcnx.opendtvmod.tuner.ChannelFile;
import com.pbcnx.opendtvmod.tuner.ChannelStore;
import com.pbcnx.opendtvmod.ts.ServiceInfo;

/**
 * The channel-list file flows (export, import, merge/replace) outside the activity.
 *
 * <p>MainActivity keeps only the one-line door and the onActivityResult dispatch; the picker
 * intents, the pending JSON and the merge/replace rules live here.
 */
public final class ChannelFileActions {

    public static final int REQ_EXPORT = 1002;
    public static final int REQ_IMPORT = 1003;

    private final Activity activity;
    private final ChannelStore store;
    private final Runnable afterChange;
    private final java.util.function.Supplier<ChannelStore.Storage> prefs;
    private String pendingExportJson;

    /** @param afterChange re-binds the list and the watch surface after an import lands */
    public ChannelFileActions(Activity activity, ChannelStore store, Runnable afterChange,
                              java.util.function.Supplier<ChannelStore.Storage> prefs) {
        this.activity = activity;
        this.store = store;
        this.afterChange = afterChange;
        this.prefs = prefs;
    }

    public void export() {
        String json;
        try {
            if (store.channels().isEmpty()) {
                Toast.makeText(activity, R.string.toast_no_channels_export, Toast.LENGTH_SHORT).show();
                return;
            }
            String name = "";
            try {
                name = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // a build without a versionName is not a reason to refuse an export
            }
            json = ChannelFile.toJsonFor(store.channels(), store.order(), new String[][] {
                    {"versionName", name},
                    {"applicationId", activity.getPackageName()},
                    {"versionCode", String.valueOf(com.pbcnx.opendtvmod.BuildConfig.VERSION_CODE)}});
        } catch (org.json.JSONException e) {
            Toast.makeText(activity, activity.getString(R.string.toast_file_build_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        pendingExportJson = json;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "opendtv-channels-"
                        + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".json");
        try {
            activity.startActivityForResult(i, REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            // Some TV boxes ship no document picker at all. Say so rather than dying silently.
            Toast.makeText(activity, R.string.toast_no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    public void chooseImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[] {"application/json", "text/plain", "application/octet-stream"});
        try {
            activity.startActivityForResult(i, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.toast_no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    /** Writes the JSON built when the picker opened; the file is only created on RESULT_OK. */
    public void writeExport(Uri uri) {
        if (pendingExportJson == null) return;
        try (java.io.OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("cannot open the destination");
            out.write(pendingExportJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            Toast.makeText(activity, activity.getString(R.string.toast_file_write_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        } finally {
            pendingExportJson = null;
        }
    }

    /** Reads the picked file, then asks merge or replace before touching anything. */
    public void filePicked(Uri uri) {
        final ChannelFile.Result result;
        try {
            result = ChannelFile.parse(readFile(uri));
        } catch (java.io.IOException e) {
            Toast.makeText(activity, activity.getString(R.string.toast_file_read_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        if (!result.ok()) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.import_nothing_title)
                    .setMessage(result.error)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        android.content.res.Resources res = activity.getResources();
        String summary = activity.getString(R.string.import_summary,
                res.getQuantityString(R.plurals.import_channels, result.entries.size(), result.entries.size()),
                res.getQuantityString(R.plurals.import_favourites, result.favorites.size(), result.favorites.size()));
        if (result.skipped > 0) {
            summary = activity.getString(R.string.import_summary_skipped, summary,
                    res.getQuantityString(R.plurals.import_rows_skipped, result.skipped, result.skipped));
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(res.getQuantityString(R.plurals.import_title, result.entries.size(), result.entries.size()))
                .setMessage(activity.getString(R.string.import_message, summary))
                .setPositiveButton(R.string.scan_merge, (d, w) -> applyImport(result, false))
                .setNeutralButton(R.string.scan_replace, (d, w) -> applyImport(result, true))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** The bench hook and the merge/replace dialogs share this one application path. */
    public void applyImport(ChannelFile.Result result, boolean replace) {
        if (replace) {
            store.clearChannels();
            store.clearFavorites(prefs.get());
        }
        int added = 0;
        int updated = 0;
        Map<String, ChannelEntry> byKey = new HashMap<>();
        for (ChannelEntry ce : store.channels()) byKey.put(ChannelStore.key(ce.khz, ce.service.serviceId), ce);
        for (ChannelFile.Entry e : result.entries) {
            String key = ChannelStore.key(e.khz, e.serviceId);
            ChannelEntry existing = byKey.get(key);
            if (existing != null) {
                // Display data from the file, identity from the key: a name edit is safe, a
                // service_id edit is not something an import can do.
                if (!e.name.isEmpty()) existing.service.name = e.name;
                if (!e.provider.isEmpty()) existing.service.provider = e.provider;
                if (!e.virtualNumber.isEmpty()) existing.service.virtualNumber = e.virtualNumber;
                updated++;
            } else {
                ServiceInfo svc = new ServiceInfo(e.serviceId);
                svc.name = e.name;
                svc.provider = e.provider;
                svc.virtualNumber = e.virtualNumber.isEmpty() ? "--" : e.virtualNumber;
                svc.pmtSeen = true;
                ChannelEntry ce = new ChannelEntry(e.khz, svc);
                store.addChannel(ce);
                byKey.put(key, ce);
                added++;
            }
        }
        // Favourites travel as part of the file. Merge unions them after the ones already here, so
        // the reader's own arrangement is not reordered by an import.
        for (String key : result.favorites) store.setFavoriteKey(key, true);
        ChannelStore.Storage p = prefs.get();
        store.saveChannels(p);
        store.saveFavorites(p);
        store.saveOrder(p);
        store.loadAll(p);
        afterChange.run();
        Toast.makeText(activity, activity.getString(R.string.toast_import_done, added, updated, result.favorites.size()),
                Toast.LENGTH_LONG).show();
    }

    private String readFile(Uri uri) throws java.io.IOException {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("cannot open the file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new java.io.IOException("the file is too large");
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }
}
