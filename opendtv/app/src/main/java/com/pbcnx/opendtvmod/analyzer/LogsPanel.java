package com.pbcnx.opendtvmod.analyzer;

import com.pbcnx.opendtvmod.ts.caption.CaptionProbe;

import com.pbcnx.opendtvmod.analyzer.AnalyzerState.LogEntry;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.pbcnx.opendtvmod.R;


/** The Logs tab: the status ring, the status strip it feeds, and the caption probe line. */
public final class LogsPanel implements TabPanel, StatusSink {
    private long captionLogAtMs;
    private String captionLogged = "";
    private final java.util.List<com.pbcnx.opendtvmod.analyzer.AnalyzerState.LogEntry> logEntries = new java.util.ArrayList<>();
    private android.widget.TextView logsText;
    private android.widget.TextView logsCount;
    private android.widget.ScrollView logsScroll;
    private int logVersion;
    private int logsRendered = -1;
    private android.widget.TextView tvStatus;


    /** Level of a status line: the Logs tab colours entries by it. */
    private static final int LOG_INFO = 0;
    private static final int LOG_WARN = 1;
    private static final int LOG_ERROR = 2;
    private static final int LOG_MAX = 300;

    private final AnalyzerActivity a;
    private final AnalyzerState state;
    /** The panel owns its root view; the activity asks for it. */
    private View root;

    public View view() { return root; }

    public LogsPanel(AnalyzerActivity activity, AnalyzerState state) {
        this.a = activity;
        this.state = state;
    }

    public void bindViews() {
        tvStatus = a.findViewById(R.id.strip_text);
        root = a.findViewById(R.id.logs_panel);
        logsText = a.findViewById(R.id.logs_text);
        logsScroll = a.findViewById(R.id.logs_scroll);
        logsCount = a.findViewById(R.id.logs_count);
        // The same process-wide, redacted ring the developers screen exports: the button is this
        // tab's half of "give support something to read".
        android.widget.TextView copy = a.findViewById(R.id.logs_copy);
        if (copy != null) copy.setOnClickListener(v -> copyLog());
    }

    /** Copies the redacted session ring, never the raw status text. */
    private void copyLog() {
        String text = com.pbcnx.opendtvmod.App.logRing().snapshot();
        if (text.isEmpty()) text = "(no log lines captured)";
        Object service = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (service instanceof android.content.ClipboardManager) {
            ((android.content.ClipboardManager) service).setPrimaryClip(
                    android.content.ClipData.newPlainText("OpenDTV session log", text));
        }
        android.widget.Toast.makeText(a, "Copied (redacted)", android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * The strip is a single line and shares it with the signal readout, so the
     * noisiest driver messages are shortened here. The Logs tab still gets the
     * full text - this is presentation only.
     */
    private static String stripText(String line) {
        // The board already says LOCKED in 20 sp; saying it again here was a duplicate.
        if (line.startsWith("LOCKED on ") && line.contains("TS reading continues")) return "Reading TS";
        if (line.startsWith("NO LOCK on ") && line.contains("TS reading continues")) return "No lock on this mux";
        int cut = line.indexOf(" (TS reading continues)");
        if (cut > 0) return line.substring(0, cut);
        return line;
    }

    /**
     * A line for the Logs tab only: no strip, no toast. The tab's count and ring are shared with
     * {@link #setStatus}, so a diagnostic that must not shout on the status strip uses this.
     */
    private void logLine(String text, int level) {
        if (text == null || text.isEmpty()) return;
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logEntries.add(new LogEntry(timeStamp, text, level));
        while (logEntries.size() > LOG_MAX) logEntries.remove(0);
        logVersion++;
        // The tab's own ring is what the screen draws; the process ring is what "Copy log" and
        // "Export diagnostics" read, and it redacts as it stores.
        com.pbcnx.opendtvmod.App.logRing().record("log", text);
        if (state.currentTab == AnalyzerState.TAB_LOGS) renderLogs();
    }

    /**
     * The caption probe's one line, kept current without flooding the ring: it is written when the
     * sentence changes (seen -> not seen, or a new "last seen" minute), and at most every 5 s while
     * the Logs tab is open. The counters and the text are behind the Developers screen's row, which
     * shows {@link CaptionProbe#report()}.
     */
    public void logCaptionProbe(boolean force) {
        CaptionProbe probe = state.serviceFilter != null ? state.serviceFilter.captionProbe() : null;
        if (probe == null) return;
        long now = System.currentTimeMillis();
        String line = probe.summary();
        boolean changed = !probe.stateKey().equals(captionLogged);
        // On a change, and otherwise once a minute while the tab is open: a caption stream delivers
        // several statements a second, and a line per statement would bury every other log entry.
        if (!force && !changed && now - captionLogAtMs < 60_000) return;
        captionLogged = probe.stateKey();
        captionLogAtMs = now;
        logLine(line, probe.seenWithin(30_000) ? LOG_INFO : LOG_WARN);
    }

    @Override public void setStatus(String text) {
        if (text == null) text = "";
        String firstLine = text.trim();
        int newline = firstLine.indexOf('\n');
        if (newline >= 0) {
            firstLine = firstLine.substring(0, newline).trim();
        }
        tvStatus.setText(stripText(firstLine));

        if (!text.isEmpty()) {
            String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            logEntries.add(new LogEntry(timeStamp, text, levelOf(text)));
            while (logEntries.size() > LOG_MAX) logEntries.remove(0);
            logVersion++;
            // Analyzer and player statuses all land here, so this is the one feed the process-wide
            // ring needs; App records the startup line itself.
            com.pbcnx.opendtvmod.App.logRing().record("status", text);
        }
        if (state.currentTab == AnalyzerState.TAB_LOGS) renderLogs();
    }

    /** Coarse level for the Logs tab: red for failures, gold for "not yet", plain for the rest. */
    private static int levelOf(String text) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("error") || t.contains("fail") || t.contains("denied") || t.contains("exception")
                || t.contains("unsupported")) return LOG_ERROR;
        if (t.contains("no lock") || t.contains("no signal") || t.contains("no tuner") || t.contains("no supported")
                || t.contains("unplugged") || t.contains("stopped") || t.contains("waiting")
                || t.contains("still initialising") || t.contains("not found") || t.contains("no service")
                || t.contains("no data")) return LOG_WARN;
        return LOG_INFO;
    }

    @Override
    public void update() {
        if (state.currentTab == AnalyzerState.TAB_LOGS) {
            logCaptionProbe(false);
            renderLogs();
        }
    }

    public void renderLogs() {
        if (logsText == null || logVersion == logsRendered) return;
        logsRendered = logVersion;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int dim = ContextCompat.getColor(a, R.color.odtv_on_surface_variant);
        for (LogEntry e : logEntries) {
            int start = sb.length();
            sb.append(e.time);
            sb.setSpan(new ForegroundColorSpan(dim), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int body = sb.length();
            sb.append("  ").append(e.text.trim()).append('\n');
            int colour = e.level == LOG_ERROR ? R.color.odtv_live
                    : e.level == LOG_WARN ? R.color.odtv_warning : R.color.odtv_on_surface;
            sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(a, colour)), body, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (logEntries.isEmpty()) sb.append("No logs captured yet.");
        logsText.setText(sb);
        logsCount.setText(logEntries.size() + (logEntries.size() == 1 ? " entry" : " entries"));
        if (logsScroll != null) logsScroll.post(new Runnable() {
            @Override
            public void run() {
                logsScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
