package ph.opendtv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The app's own license plus the licenses of everything it is built from. Texts live in assets/licenses.
 *
 * <p>Grouped by what the component is and who it comes from, because the list is read by people who
 * want to know one of two things: what this build is obliged to say (the GPL parts), or where a piece
 * of it came from. Our own repositories are named as ours - the engine in particular is our fork, not
 * the upstream project the old sheet credited.
 *
 * <p>Nothing is expanded on entry: fourteen cards open at once is a wall of text, and the header line
 * already says what each one is.
 */
public class LicensesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_licenses);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout list = findViewById(R.id.list);

        // ---------------------------------------------------------------- this app
        list.addView(header("OpenDTV-MOD", false));
        // The link has to be one a recipient can open. The development repo
        // (PBCNX/OpenDTV-MOD-prv) is private: outside it the URL is a 404, which reads as a licence
        // pointing nowhere. PBCNX/OpenDTV-MOD is the public source tree this app is built from.
        list.addView(card("OpenDTV-MOD", "MIT  (own code)", "https://github.com/PBCNX/OpenDTV-MOD",
                "app.txt", false, list));
        list.addView(note("This build is a combined work: it bundles NextLib/FFmpeg (GPL-3.0) and the "
                + "SmsRio driver (GPL-2.0). Their terms are below with the full texts, and they cover the "
                + "combination. The OpenDTV-MOD code itself is MIT."));
        list.addView(divider());

        // ---------------------------------------------------------------- what it is a fork of
        list.addView(header("OpenDTV", true));
        list.addView(card("OpenDTV  (upstream base)", "MIT", "https://github.com/jpdcs/OpenDTV",
                "opendtv-upstream.txt", false, list));
        list.addView(divider());

        // ---------------------------------------------------------------- tuners
        list.addView(header("Tuners", true));
        list.addView(card("IT9175 driver  (PBCNX/it9175)", "MIT", "https://github.com/PBCNX/it9175",
                "it9175.txt", false, list));
        list.addView(card("SmsRio driver  (PBCNX/smsrio)", "GPL-2.0", "https://github.com/PBCNX/smsrio",
                "gpl-2.0.txt", false, list));
        list.addView(card("Sony 9308 / Endeavour", "vendor binary", null,
                "sony9308.txt", false, list));
        list.addView(divider());

        // ---------------------------------------------------------------- ISDB-T specific
        list.addView(header("Others - ISDB-T specific", true));
        list.addView(note("The data-broadcast viewer (web-bml and its dependencies) is bundled in assets/bml/bml.js."));
        list.addView(card("web-bml  (PBCNX/web-bml-PHL)", "MIT  (faf4d96, engine and host in one bundle)",
                "https://github.com/PBCNX/web-bml-PHL", "web-bml.txt", false, list));
        list.addView(card("arib-mmt-tlv-ts  2.0.2", "MIT", "https://github.com/otya128/arib-mmt-tlv-ts",
                "arib-mmt-tlv-ts.txt", false, list));
        list.addView(card("aribb24.js  (styled captions)", "MIT", "https://github.com/monyone/aribb24.js",
                "aribb24-js.txt", false, list));
        list.addView(card("css  (reworkcss, otya128 fork)  3.0.2", "MIT", "https://github.com/otya128/reworkcss-css",
                "css.txt", false, list));

        // the divider the owner asked for: ISDB-T specific above, everything generic below
        list.addView(divider());

        // ---------------------------------------------------------------- general
        list.addView(header("Others - general", true));
        list.addView(card("NextLib + FFmpeg  (software decoders)  0.9.0", "GPL-3.0",
                "https://github.com/anilbeesetti/nextlib", "nextlib.txt", false, list));
        list.addView(card("GNU General Public License v3.0", "full text", "https://www.gnu.org/licenses/gpl-3.0.html",
                "gpl-3.0.txt", false, list));
        list.addView(card("AndroidX Media3  (ExoPlayer, UI)  1.7.1", "Apache-2.0", "https://github.com/androidx/media",
                "apache-2.0.txt", false, list));
        list.addView(card("Material Components for Android  1.12.0", "Apache-2.0",
                "https://github.com/material-components/material-components-android", "apache-2.0.txt", false, list));
        list.addView(card("AndroidX AppCompat / RecyclerView / WebKit", "Apache-2.0",
                "https://developer.android.com/jetpack/androidx", "apache-2.0.txt", false, list));
        list.addView(card("Kotlin standard library  2.1.21", "Apache-2.0", "https://kotlinlang.org",
                "apache-2.0.txt", false, list));
        list.addView(card("crc-32  1.2.2", "Apache-2.0", "https://github.com/SheetJS/js-crc32",
                "crc-32.txt", false, list));
        list.addView(card("fflate  0.8.3", "MIT", "https://github.com/101arrowz/fflate",
                "fflate.txt", false, list));
        list.addView(card("fast-xml-parser  4.5.7", "MIT", "https://github.com/NaturalIntelligence/fast-xml-parser",
                "fast-xml-parser.txt", false, list));
        list.addView(card("Kosugi / Kosugi Maru fonts  (BML, captions)", "Apache-2.0",
                "https://fonts.google.com/specimen/Kosugi", "apache-2.0.txt", false, list));
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private int color(int attr) {
        return MaterialColors.getColor(this, attr, 0xFF888888);
    }

    /** Material 3 section label (primary colour, label style). */
    private TextView header(String text, boolean topGap) {
        TextView t = new TextView(this);
        t.setText(text);
        TextViewCompat.setTextAppearance(t, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
        t.setPadding(dp(4), dp(topGap ? 20 : 8), dp(4), dp(8));
        return t;
    }

    /** Small print under a section label: why the section exists, not a licence text. */
    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        TextViewCompat.setTextAppearance(t, com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        t.setPadding(dp(4), 0, dp(4), dp(8));
        return t;
    }

    /** A hairline between groups. */
    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(2);
        v.setLayoutParams(lp);
        return v;
    }

    /** One filled M3 card: name + license line, tap to expand the project link and the full license text. */
    private View card(String name, String license, final String url, String file, boolean expanded, final ViewGroup parent) {
        final View v = getLayoutInflater().inflate(R.layout.item_license, parent, false);
        ((TextView) v.findViewById(R.id.name)).setText(name);
        ((TextView) v.findViewById(R.id.license)).setText(license);
        MaterialButton link = v.findViewById(R.id.link);
        if (url == null) {
            link.setVisibility(View.GONE);
        } else {
            link.setText(url.replace("https://", ""));
            link.setOnClickListener(x -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                    // no browser to open it: the row still shows the URL as text
                }
            });
        }
        ((TextView) v.findViewById(R.id.body)).setText(read(file));
        final View details = v.findViewById(R.id.details);
        final View chevron = v.findViewById(R.id.chevron);
        setExpanded(details, chevron, expanded, false, parent);
        v.findViewById(R.id.header).setOnClickListener(x ->
                setExpanded(details, chevron, details.getVisibility() != View.VISIBLE, true, parent));
        return v;
    }

    private void setExpanded(View details, View chevron, boolean open, boolean animate, ViewGroup parent) {
        if (animate) TransitionManager.beginDelayedTransition(parent, new AutoTransition().setDuration(180));
        details.setVisibility(open ? View.VISIBLE : View.GONE);
        chevron.setRotation(open ? 180f : 0f);
    }

    private String read(String file) {
        try (InputStream in = getAssets().open("licenses/" + file)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(license text not found: " + file + ")";
        }
    }
}
