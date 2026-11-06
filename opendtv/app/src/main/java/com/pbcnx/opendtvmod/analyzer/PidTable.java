package com.pbcnx.opendtvmod.analyzer;

import android.view.View;

import com.pbcnx.opendtvmod.ui.Dims;

import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.Locale;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.ts.AnalyzerSnapshot;


/** The PIDs tab: one row per PID, rebuilt only when the PID set changes. */
public final class PidTable implements TabPanel {
    private final java.util.List<android.widget.TextView[]> pidCells = new java.util.ArrayList<>();
    private String pidKey = "";
    private android.widget.LinearLayout pidsRows;
    private android.widget.TextView pidsFooter;


    private final AnalyzerActivity a;
    private final AnalyzerState state;
    /** The panel owns its root view; the activity asks for it. */
    private View root;

    public View view() { return root; }

    public PidTable(AnalyzerActivity activity, AnalyzerState state) {
        this.a = activity;
        this.state = state;
    }

    public void bindViews() {
        root = a.findViewById(R.id.pids_panel);
        pidsRows = a.findViewById(R.id.pids_rows);
        pidsFooter = a.findViewById(R.id.pids_footer);
    }

    // ------------------------------------------------------------------ PIDs tab

    /** One mono cell of the PID table: fixed width, or weight 1 for the description. */
    private TextView pidCell(String text, int widthDp, int gravity, boolean accent) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(gravity | android.view.Gravity.CENTER_VERTICAL);
        tv.setTextColor(ContextCompat.getColor(a, accent ? R.color.odtv_accent : R.color.odtv_on_surface));
        LinearLayout.LayoutParams lp;
        if (widthDp == 0) {
            lp = new LinearLayout.LayoutParams(0, Dims.dp(a, 26), 1f);
            lp.leftMargin = Dims.dp(a, 10);
        } else {
            lp = new LinearLayout.LayoutParams(Dims.dp(a, widthDp), Dims.dp(a, 26));
        }
        tv.setLayoutParams(lp);
        return tv;
    }

    /** PIDs tab: one row per PID. Rows keep their first-seen order so the table does not jump. */
    @Override
    public void update() {
        if (state.currentTab == AnalyzerState.TAB_PIDS) updatePidTable();
    }

    public void updatePidTable() {
        AnalyzerSnapshot s = state.lastSnapshot;
        if (s == null || pidsRows == null) return;
        // The card is what has to fit the text, not the screen: the set's card is ~370 dp
        // even though the screen is 960, and "Video H.264 (sid 160)" would be clipped.
        int cardDp = 0;
        if (state.contentFrame != null) {
            cardDp = Math.round((state.contentFrame.getWidth()
                    - state.contentFrame.getPaddingLeft() - state.contentFrame.getPaddingRight())
                    / a.getResources().getDisplayMetrics().density);
        }
        boolean narrow = cardDp > 0 && cardDp < 520;

        StringBuilder key = new StringBuilder(narrow ? "n" : "w");
        for (AnalyzerSnapshot.Row r : s.rows) key.append(r.pid).append(' ');
        if (!key.toString().equals(pidKey)) {
            pidKey = key.toString();
            pidsRows.removeAllViews();
            pidCells.clear();
            for (AnalyzerSnapshot.Row r : s.rows) {
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                boolean psi = isPsiPid(r.label);
                TextView pid = pidCell(String.format(Locale.US, "0x%04X", r.pid), 64, android.view.Gravity.START, false);
                TextView kbps = pidCell("", 64, android.view.Gravity.END, false);
                TextView pkts = pidCell("", 64, android.view.Gravity.END, false);
                TextView cc = pidCell("", 40, android.view.Gravity.END, false);
                TextView label = pidCell(narrow ? stripKind(AnalyzerUtil.safe(r.label)) : AnalyzerUtil.safe(r.label), 0,
                        android.view.Gravity.START, psi);
                row.addView(pid);
                row.addView(kbps);
                row.addView(pkts);
                row.addView(cc);
                row.addView(label);
                row.setPadding(0, Dims.dp(a, 2), 0, Dims.dp(a, 2));
                pidsRows.addView(row);
                pidCells.add(new TextView[]{pid, kbps, pkts, cc, label});
            }
        }

        for (int i = 0; i < pidCells.size() && i < s.rows.size(); i++) {
            AnalyzerSnapshot.Row r = s.rows.get(i);
            TextView[] c = pidCells.get(i);
            c[0].setText(String.format(Locale.US, "0x%04X", r.pid));
            c[1].setText(String.format(Locale.US, "%.1f", r.kbps));
            c[2].setText(String.valueOf(r.packets));
            c[3].setText(String.valueOf(r.ccErrors));
            c[3].setTextColor(ContextCompat.getColor(a,
                    r.ccErrors > 0 ? R.color.odtv_live : R.color.odtv_on_surface_variant));
        }
        if (pidsFooter != null) {
            pidsFooter.setText(String.format(Locale.US,
                    "%d PIDs · %.2f Mbps · null %d · CC %d · TEI %d · sync %d",
                    s.rows.size(), s.totalKbps / 1000.0, s.nullPackets, s.ccErrors, s.teiPackets, s.syncLosses));
        }
    }

    /** PSI/SI tables are the interesting rows: PAT, PMT, NIT, SDT, EIT, TDT, AIT. */
    private static boolean isPsiPid(String label) {
        String l = AnalyzerUtil.safe(label).toUpperCase(Locale.US);
        return l.startsWith("PAT") || l.startsWith("PMT") || l.startsWith("NIT")
                || l.startsWith("SDT") || l.startsWith("EIT") || l.startsWith("TDT")
                || l.startsWith("AIT") || l.startsWith("CAT") || l.startsWith("TOT");
    }

    /** "Video H.264 (sid 160)" -> "H.264 160", only for the narrow layout. */
    private static String stripKind(String label) {
        String s = label;
        for (String prefix : new String[]{"Video ", "Audio ", "Data "}) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length());
                break;
            }
        }
        int i = s.indexOf(" (sid ");
        if (i > 0 && s.endsWith(")")) s = s.substring(0, i) + " " + s.substring(i + 6, s.length() - 1);
        return s.replace("DSM-CC data carousel", "DSM-CC carousel");
    }
}
