package com.pbcnx.opendtvmod.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.io.File;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

/**
 * The one external-storage gate for the whole app: recordings write to {@code OpenDTV/} on the shared
 * storage, so the watch screen and the analyzer ask the same question and get the same answer.
 *
 * <p>On Android 11+ that is the all-files-access screen; on 6..10 it is the runtime permission. The
 * gate either returns true (go ahead) or explains itself and returns false, so every caller reads the
 * same way. The activity that owns the request retries the action in
 * {@code onRequestPermissionsResult} when {@link #REQUEST_CODE} comes back granted.
 *
 * <p>Ported from contributor PR #7 (briantofu, ODTV-128). On Android 11+ the gate opens the app's
 * own All files access switch, falls back to the app list where no per-app page exists, and on TV
 * boxes with neither screen records to {@code Movies/OpenDTV/} instead: Android 11+ lets an app
 * write its own files there without a grant.
 */
public final class StorageAccess {

    /** Request code for the runtime permission; the recording action retries when it is granted. */
    public static final int REQUEST_CODE = 1001;

    private static final String TAG = "OpenDTV-Storage";

    /** The action to continue once the user grants access; one pending action at a time. */
    @Nullable private static Runnable pending;

    /** Set once this device proves it has no All files access screen: the Movies/ fallback. */
    private static boolean noAllFilesScreen;

    private StorageAccess() {
    }

    /** Gate with a continuation: it runs as soon as the grant arrives (permission or all-files). */
    public static boolean ensure(Activity activity, @Nullable Runnable retry) {
        if (allowed(activity)) {
            pending = null;
            return true;
        }
        pending = retry;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (openAllFilesScreen(activity) == RecStoragePlan.Screen.NONE) {
                // No screen can grant All files access on this device (some TV boxes). Android 11+
                // allows an app's own files under Movies/, so record there without a grant.
                noAllFilesScreen = true;
                pending = null;
                Log.w(TAG, "no All files access screen on this device; recordings go to Movies/OpenDTV");
                Toast.makeText(activity, "No storage-access screen on this device \u00B7 recordings go to Movies/OpenDTV",
                        Toast.LENGTH_LONG).show();
                return true;
            }
            Toast.makeText(activity, "Allow storage access in Android settings so recordings can be saved.", Toast.LENGTH_LONG).show();
        } else {
            ActivityCompat.requestPermissions(activity, new String[] {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQUEST_CODE);
            Toast.makeText(activity, "Allow storage access in Android settings so recordings can be saved.", Toast.LENGTH_LONG).show();
        }
        return false;
    }

    /**
     * Opens the best All files access screen the device has: the app's own switch first, then the
     * app list. The list intent gets no package - with one, no screen matches it and nothing opens,
     * which is the 0.2.0 bug this port fixes. Returns which screen opened, or NONE if neither did.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private static RecStoragePlan.Screen openAllFilesScreen(Activity activity) {
        boolean appPage = tryStart(activity, new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.getPackageName())),
                "no per-app All files access screen on this device");
        boolean appList = !appPage && tryStart(activity,
                new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                "no All files access screen on this device");
        return RecStoragePlan.screen(appPage, appList);
    }

    /** True when that settings screen opened; an intent no activity matches throws and is caught. */
    private static boolean tryStart(Activity activity, Intent intent, String missing) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, missing, e);
            return false;
        }
    }

    /** True when the app may write its recordings folder right now. */
    private static boolean allowed(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // No grant is needed for the Movies/ folder: the way out on devices with no screen.
            return Environment.isExternalStorageManager() || noAllFilesScreen;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;   // app-specific external dir, no permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * The app's user-visible folder. On API 29 the shared-storage path is not writable without SAF,
     * so the folder lives in the app-specific external dir instead; Android 11+ uses the shared
     * OpenDTV folder behind the All-files grant, or Movies/OpenDTV where no screen can grant it.
     */
    public static File appDir(Activity activity) {
        int sdk = Build.VERSION.SDK_INT;
        boolean granted = sdk >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
        boolean noScreen = sdk >= Build.VERSION_CODES.R && noAllFilesScreen;
        switch (RecStoragePlan.folder(sdk, granted, noScreen)) {
            case APP_OPENDTV: {
                File base = activity.getExternalFilesDir(null);
                return new File(base == null ? activity.getFilesDir() : base, "OpenDTV");
            }
            case MOVIES_OPENDTV:
                return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OpenDTV");
            case SHARED_OPENDTV:
            default:
                return new File(Environment.getExternalStorageDirectory(), "OpenDTV");
        }
    }

    /** The recordings folder under {@link #appDir}. */
    public static File recordingsDir(Activity activity) {
        return new File(appDir(activity), "wholets");
    }

    /** Runs the pending continuation after the user returns from the all-files settings screen. */
    public static void resumePending(Activity activity) {
        if (pending != null && allowed(activity)) runPending();
    }

    /** Activity delegate for the runtime-permission answer; true when this gate consumed it. */
    public static boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CODE) return false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) runPending();
        return true;
    }

    private static void runPending() {
        Runnable action = pending;
        pending = null;
        if (action != null) action.run();
    }

    public static boolean ensure(Activity activity) {
        return ensure(activity, null);
    }

}
