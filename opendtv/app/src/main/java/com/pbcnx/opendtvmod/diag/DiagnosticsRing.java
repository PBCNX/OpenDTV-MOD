package com.pbcnx.opendtvmod.diag;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * The process-wide, bounded session log: the last {@value #MAX_LINES} status lines, each redacted
 * on the way in so nothing sensitive is ever held here, let alone copied or written out.
 *
 * <p>One instance lives on {@link com.pbcnx.opendtvmod.App}; the Logs tab feeds it from
 * {@code LogsPanel.setStatus} and the developers screen reads it for "Export diagnostics" and
 * "Copy log". Every method is synchronized: status lines arrive from source threads as well as
 * the UI thread.</p>
 *
 * <p>Pure Java (no Android types), so the ring's bound and thread-safety are JVM-testable.</p>
 */
public final class DiagnosticsRing {

    /** How many lines a bundle carries: enough for one bug report, small enough to paste. */
    public static final int MAX_LINES = 300;

    private final ArrayDeque<String> lines = new ArrayDeque<>(MAX_LINES);

    /**
     * Adds one line, redacted immediately. A null/blank line is ignored; an embedded newline
     * becomes " | " so one record stays one line.
     */
    public synchronized void record(String tag, String line) {
        if (line == null) return;
        String text = line.replace('\n', ' ').replace('\r', ' ').trim();
        if (text.isEmpty()) return;
        String safeTag = tag == null ? "" : Redactor.redact(tag.trim());
        String safeText = Redactor.redact(text);
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String entry = safeTag.isEmpty() ? stamp + "  " + safeText : stamp + "  " + safeTag + ": " + safeText;
        lines.addLast(entry);
        while (lines.size() > MAX_LINES) lines.removeFirst();
    }

    /** The ring as text, oldest line first, already redacted. Null-safe: "" when empty. */
    public synchronized String snapshot() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    /** How many lines are held right now. */
    public synchronized int size() {
        return lines.size();
    }

    /** Drops every line; used at process start so a restart begins with a clean session. */
    public synchronized void clear() {
        lines.clear();
    }
}
