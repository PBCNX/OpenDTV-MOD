package com.pbcnx.opendtvmod.firmware;

import android.app.Activity;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The two ways a vendor image arrives - one file, or a folder of them - and the state the activity
 * keeps around them. Both flows run on the activity's worker and post their result back.
 */
public final class FirmwareImport {

    /** The tag MainActivity logged the import under: bench notes are grepped for it. */
    private static final String TAG = "OpenDTV-Main";

    private FirmwareImport() {
    }

    /** What the activity still owns when an import lands. */
    public interface Host {
        /** The imported images changed: re-bind the Settings image list when that tab is visible. */
        void imagesChanged();

        /** Re-initialise the tuner: a bridge that failed for lack of an image gets another go. */
        void tunerNeedsInit();
    }

    /** Cheap first filter: only a file whose size matches a supported image is read at all. */
    public static boolean isImageSize(long size) {
        if (size <= 0) return false;
        for (FirmwareStore.Image image : FirmwareStore.all()) {
            if (image.size == size) return true;
        }
        return false;
    }

    private static byte[] readBytes(Activity activity, Uri uri) throws IOException {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open the file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IOException("the file is too large");
            }
            return out.toByteArray();
        }
    }

    /**
     * Scans a folder for the supported images. Each file is matched by size first and then by
     * SHA-256, so strangers are skipped cheaply and a renamed image still lands in its slot.
     */
    public static void folderPicked(final Activity activity, final Uri treeUri,
                                    Executor worker, final Host host) {
        worker.execute(() -> {
            final java.util.Set<String> imported = new LinkedHashSet<>();
            final List<String> refused = new ArrayList<>();
            int ignored = 0;
            final List<String> unreadable = new ArrayList<>();
            try {
                final Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                try (android.database.Cursor c = activity.getContentResolver().query(children,
                        new String[]{
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_SIZE},
                        null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        final String docId = c.getString(0);
                        final String name = c.getString(1);
                        final long size = c.isNull(2) ? -1 : c.getLong(2);
                        if (!isImageSize(size)) {
                            ignored++;
                            continue;
                        }
                        try {
                            byte[] data = readBytes(activity, DocumentsContract.buildDocumentUriUsingTree(
                                    treeUri, docId));
                            FirmwareStore.Image image = FirmwareStore.importBytes(activity, data);
                            if (image != null) {
                                imported.add(image.device);
                            } else {
                                refused.add(name + " · " + data.length + " B · "
                                        + FirmwareStore.shorten(FirmwareStore.sha256Hex(data)));
                            }
                        } catch (IOException | RuntimeException e) {
                            unreadable.add(name + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
                        }
                    }
                }
            } catch (RuntimeException e) {
                unreadable.add("the folder" + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
            }
            final List<String> refusedList = refused;
            final int ignoredCount = ignored;
            final List<String> unreadableList = unreadable;
            activity.runOnUiThread(() -> {
                StringBuilder b = new StringBuilder();
                if (!imported.isEmpty()) {
                    b.append("Installed: ").append(TextUtils.join(", ", imported));
                }
                if (!refusedList.isEmpty()) {
                    if (b.length() > 0) b.append("\n\n");
                    b.append("Not a vendor image for any supported stick:\n")
                            .append(TextUtils.join("\n", refusedList));
                }
                if (ignoredCount > 0) {
                    if (b.length() > 0) b.append("\n\n");
                    b.append(ignoredCount).append(ignoredCount == 1
                            ? " other file ignored" : " other files ignored");
                }
                if (!unreadableList.isEmpty()) {
                    if (b.length() > 0) b.append("\n\n");
                    b.append("Could not read:\n").append(TextUtils.join("\n", unreadableList));
                }
                if (b.length() == 0) b.append("No vendor images in that folder.");
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Tuner setup")
                        .setMessage(b.toString())
                        .setPositiveButton("Close", null)
                        .show();
                if (!imported.isEmpty()) {
                    host.imagesChanged();
                    host.tunerNeedsInit();
                }
            });
        });
    }

    /**
     * Reads the picked file and lets its SHA-256 decide what it is: the file name is not trusted, so a
     * renamed download still lands in the right slot and an unrelated file is refused by name and hash
     * rather than uploaded into a bridge.
     */
    public static void filePicked(final Activity activity, final Uri uri,
                                  Executor worker, final Host host) {
        worker.execute(() -> {
            String message;
            boolean installed = false;
            try {
                byte[] data = readBytes(activity, uri);   // the same bounded, closed read as a folder
                FirmwareStore.Image image = FirmwareStore.importBytes(activity, data);
                if (image == null) {
                    message = "Not a vendor image for any supported stick ("
                            + data.length + " bytes, sha256 "
                            + FirmwareStore.shorten(FirmwareStore.sha256Hex(data)) + ")";
                } else {
                    installed = true;
                    message = image.device + " image installed";
                }
            } catch (IOException | RuntimeException e) {
                message = "Could not read that file" + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            final String text = message;
            final boolean ok = installed;
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
                Log.i(TAG, "firmware import: " + text);
                if (ok) {
                    host.imagesChanged();
                    host.tunerNeedsInit();
                }
            });
        });
    }
}
