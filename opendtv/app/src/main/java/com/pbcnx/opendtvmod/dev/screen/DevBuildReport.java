package com.pbcnx.opendtvmod.dev.screen;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.BuildConfig;
import com.pbcnx.opendtvmod.BuildStamp;

/**
 * The text behind the Developers screen's Build info row: package identity, ABI, device, display
 * and every static BuildConfig field, laid out for a monospaced dialog.
 */
public final class DevBuildReport {

    private DevBuildReport() {
    }

    public static String build(Context ctx) {
        String version = "?";
        long versionCode = -1;
        try {
            android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            version = info.versionName;
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // our own package cannot be missing; the row prints "?" if it is
        }
        int targetSdk = ctx.getApplicationInfo().targetSdkVersion;
        DisplayMetrics m = ctx.getResources().getDisplayMetrics();
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
                {"package", ctx.getPackageName()},
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

    public static void copy(Context ctx, String text) {
        ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenDTV build info", text));
        Toast.makeText(ctx, "Build info copied to the clipboard.", Toast.LENGTH_SHORT).show();
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
}
