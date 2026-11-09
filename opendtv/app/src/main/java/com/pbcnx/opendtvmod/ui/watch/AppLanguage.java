package com.pbcnx.opendtvmod.ui.watch;

import android.app.Activity;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.LocaleManagerCompat;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

import com.pbcnx.opendtvmod.R;

/**
 * The app's own language, for Settings > App language. It is the per-app language Android 13 and
 * up keep for every app (Settings > Apps > OpenDTV-MOD > Language shows the same choice, both ways);
 * on Android 12 and older AppCompat stores it (autoStoreLocales in the manifest) and applies it when
 * the app starts. Either way AppCompat recreates the screen in the new language.
 *
 * <p>Tagalog is asked for as "fil", never "tl": the system matches the request against the app's
 * languages, where values-tl is reported as "fil", so a "tl" request never matches and the app
 * stays in English (TRANSLATING.md).</p>
 */
public final class AppLanguage {

    /** The dialog's rows: follow the phone, then each language the app ships. */
    private static final String[] TAGS = {"", "en", "fil"};

    private AppLanguage() {
    }

    /** The row that is chosen now; 0 follows the phone. */
    public static int current() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        Locale first = locales.isEmpty() ? null : locales.get(0);
        if (first == null) return 0;
        String language = first.getLanguage();
        if ("en".equals(language)) return 1;
        if ("tl".equals(language) || "fil".equals(language)) return 2;
        return 0;
    }

    /** The dialog's rows. Each language keeps its own name; the first names the phone's language. */
    public static String[] labels(Activity activity) {
        return new String[] {systemLabel(activity), activity.getString(R.string.language_english),
                activity.getString(R.string.language_tagalog)};
    }

    /** The Settings row's summary: what is chosen now. */
    public static String currentLabel(Activity activity) {
        return labels(activity)[current()];
    }

    /** Saves the choice; AppCompat recreates the screen in that language. */
    public static void choose(int which) {
        AppCompatDelegate.setApplicationLocales(which <= 0 || which >= TAGS.length
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(TAGS[which]));
    }

    /**
     * "System default (English)": the phone's own language, named in that language. Read through
     * LocaleManagerCompat, because on Android 13 and up the app's resources already follow the app's
     * language, so asking them would name Tagalog on an English phone.
     */
    private static String systemLabel(Activity activity) {
        LocaleListCompat system = LocaleManagerCompat.getSystemLocales(activity);
        Locale first = system.isEmpty() ? null : system.get(0);
        String name = first == null ? "" : first.getDisplayLanguage(first);
        if (name.isEmpty()) return activity.getString(R.string.language_system);
        return activity.getString(R.string.language_system_current,
                name.substring(0, 1).toUpperCase(first) + name.substring(1));
    }
}
