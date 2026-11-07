package com.pbcnx.opendtvmod.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The pure half of the REC storage gate (port of contributor PR #7, ODTV-128): which settings
 * screen to try and which folder recordings use. Plain JUnit on the JVM - the intents and the
 * actual write are the device test plan.
 */
public class RecStoragePlanTest {

    // ---------------------------------------------------------------- which screen

    @Test
    public void appAllFilesPageWinsWhenTheDeviceHasBoth() {
        // The phone path: the app's own All files access switch, not the app list.
        assertEquals(RecStoragePlan.Screen.APP_ALL_FILES,
                RecStoragePlan.screen(true, true));
    }

    @Test
    public void appListIsTheFallbackWhenThereIsNoPerAppPage() {
        assertEquals(RecStoragePlan.Screen.APP_LIST,
                RecStoragePlan.screen(false, true));
    }

    @Test
    public void neitherScreenMeansNoGrantCanCome() {
        // Some TV boxes: the caller must then record without a grant (Movies/OpenDTV).
        assertEquals(RecStoragePlan.Screen.NONE,
                RecStoragePlan.screen(false, false));
    }

    @Test
    public void appPageAloneStillPrefersTheAppSwitch() {
        assertEquals(RecStoragePlan.Screen.APP_ALL_FILES,
                RecStoragePlan.screen(true, false));
    }

    // ---------------------------------------------------------------- which folder

    @Test
    public void android11WithNoScreenAndNoGrantRecordsToMovies() {
        // The TV-box case: no All files access screen, so Movies/OpenDTV (allowed without a grant).
        assertEquals(RecStoragePlan.Folder.MOVIES_OPENDTV,
                RecStoragePlan.folder(30, false, true));
        assertEquals(RecStoragePlan.Folder.MOVIES_OPENDTV,
                RecStoragePlan.folder(34, false, true));
    }

    @Test
    public void android11OnAPhoneKeepsTheSharedOpenDtVFolder() {
        // A screen exists (or was not needed): the grant path, so the shared OpenDTV/ folder.
        assertEquals(RecStoragePlan.Folder.SHARED_OPENDTV,
                RecStoragePlan.folder(30, false, false));
        assertEquals(RecStoragePlan.Folder.SHARED_OPENDTV,
                RecStoragePlan.folder(34, true, true));
    }

    @Test
    public void api29AlwaysUsesTheAppSpecificDirectory() {
        // Scoped storage's Q special case: the shared path is not writable without SAF.
        assertEquals(RecStoragePlan.Folder.APP_OPENDTV,
                RecStoragePlan.folder(29, false, false));
        assertEquals(RecStoragePlan.Folder.APP_OPENDTV,
                RecStoragePlan.folder(29, false, true));
    }

    @Test
    public void olderAndroidKeepsTheSharedOpenDtVFolder() {
        assertEquals(RecStoragePlan.Folder.SHARED_OPENDTV,
                RecStoragePlan.folder(28, false, false));
        assertEquals(RecStoragePlan.Folder.SHARED_OPENDTV,
                RecStoragePlan.folder(21, false, true));
    }

    @Test
    public void moviesFallbackNeverLeaksIntoApi29OrOlder() {
        // The Movies/ exemption belongs to Android 11+ only: older systems keep OpenDTV/ (and
        // API 29 its app-specific dir) even when a "no screen" flag was somehow set.
        for (int sdk = 21; sdk <= 28; sdk++) {
            assertEquals("sdk " + sdk, RecStoragePlan.Folder.SHARED_OPENDTV,
                    RecStoragePlan.folder(sdk, false, true));
        }
        assertEquals(RecStoragePlan.Folder.APP_OPENDTV,
                RecStoragePlan.folder(29, false, true));
        assertEquals(RecStoragePlan.Folder.MOVIES_OPENDTV,
                RecStoragePlan.folder(30, false, true));
    }
}
