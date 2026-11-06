package com.pbcnx.opendtvmod.firmware;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.widget.Toast;

/** The two firmware file pickers, outside the screen that offers them. */
public final class FirmwarePicker {

    public static final int REQ_IMPORT = 1004;
    public static final int REQ_FOLDER = 1005;

    private FirmwarePicker() {
    }

    public static void chooseFile(Activity activity) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            activity.startActivityForResult(i, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No file picker app on this device · install a Files app, then try again.", Toast.LENGTH_LONG).show();
        }
    }

    public static void chooseFolder(Activity activity) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            activity.startActivityForResult(i, REQ_FOLDER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No file picker app on this device · install a Files app, then try again.", Toast.LENGTH_LONG).show();
        }
    }
}
