package com.pbcnx.opendtvmod.ui;

/**
 * The pure part of the recording-storage gate: which settings screen to open and which folder
 * recordings use, given what the device offers. Kept out of {@link StorageAccess} (Android,
 * intents, settings pages) so the decision is unit-tested on the JVM; StorageAccess only carries
 * it out.
 *
 * <p>Ported from contributor PR #7 (briantofu, ODTV-128): on Android 11+ a phone can grant All
 * files access from its own switch, some systems have only the app list, and TV boxes with
 * neither screen cannot grant at all - those record to {@code Movies/OpenDTV/}, which Android 11+
 * allows without a grant.</p>
 */
public final class RecStoragePlan {

    /** The Android 11+ screen that can turn All files access on; NONE records without a grant. */
    public enum Screen {
        /** {@code Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION} - this app's switch. */
        APP_ALL_FILES,
        /** {@code Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION} - the app list. */
        APP_LIST,
        /** This device has neither screen; the grant can never come. */
        NONE
    }

    /** Where recordings are written. */
    public enum Folder {
        /** Shared storage's {@code OpenDTV/}, behind the All files access grant. */
        SHARED_OPENDTV,
        /** Shared storage's {@code Movies/OpenDTV/}: writable on Android 11+ without a grant. */
        MOVIES_OPENDTV,
        /** The app-specific external dir, the only writable shared-storage path on API 29. */
        APP_OPENDTV
    }

    private RecStoragePlan() {
    }

    /**
     * The screen to open, in order of preference: the app's own All files access switch, then the
     * app list, and {@link Screen#NONE} when the device has neither.
     *
     * <p>The list intent must carry no package: with one, no screen matches it and nothing opens,
     * which is the 0.2.0 bug this port fixes.</p>
     */
    public static Screen screen(boolean hasAppAllFilesPage, boolean hasAllFilesList) {
        if (hasAppAllFilesPage) return Screen.APP_ALL_FILES;
        if (hasAllFilesList) return Screen.APP_LIST;
        return Screen.NONE;
    }

    /**
     * The folder recordings use for this device and grant state.
     *
     * @param sdkInt            {@code Build.VERSION.SDK_INT}
     * @param allFilesGranted   {@code Environment.isExternalStorageManager()}
     * @param noAllFilesScreen  true once this device proved it has no screen to grant the access
     */
    public static Folder folder(int sdkInt, boolean allFilesGranted, boolean noAllFilesScreen) {
        if (sdkInt == 29) return Folder.APP_OPENDTV;
        if (sdkInt >= 30 && noAllFilesScreen && !allFilesGranted) return Folder.MOVIES_OPENDTV;
        return Folder.SHARED_OPENDTV;
    }
}
