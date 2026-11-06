package com.pbcnx.opendtvmod.ts.caption;

import androidx.annotation.Nullable;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What the caption path has actually seen, in one place.
 *
 * <p>Captions are the app's most-used accessibility path and the one thing nobody can check without a
 * caption stream on air, which is why this exists rather than a guess. It answers three questions,
 * in the order a reader needs them:
 *
 * <ol>
 *   <li><b>seen or not</b>, and when last - {@link #summary()}, one line, for the Logs tab;</li>
 *   <li><b>which PIDs and component tags</b> the PMT declares as captions - {@link #report()};</li>
 *   <li><b>what the decoder got</b>: packets, PES, statements, clears, malformed payloads, caption
 *       PID continuity errors, the last PTS and the last text - {@link #report()}.</li>
 * </ol>
 *
 * <p>Fed by {@link com.pbcnx.opendtvmod.ts.pipe.ServiceFilter} on the tuner thread, read from the UI thread, so every counter is
 * {@code volatile} or written under the filter's own single-writer discipline. Nothing here allocates
 * per packet except the last text.
 *
 * <p>{@link #last()} is a weak reference to the newest probe: the Developers screen lives in another
 * activity and must not keep a dead service's filter alive.
 */
public final class CaptionProbe {

    /** A caption PES is declared either by data_component_id 0x0008 or by an EWBS component tag. */
    public static boolean isCaptionStream(StreamInfo s) {
        return s != null && (s.dataComponentId == 0x0008
                || (s.streamType == 0x06 && (s.componentTag == 0x38 || s.componentTag == 0x88)));
    }


    private final int serviceId;
    private final String serviceName;
    /** "pid 0x0141 · type 0x06 · tag 0x40 · dci 0x0008" per declared caption stream, or "". */
    private final String declared;

    private volatile long packets;
    private volatile long pes;
    private volatile long statements;
    private volatile long clears;
    private volatile long otherGroups;      // management groups and the other language
    private volatile long malformed;
    private volatile long ccErrors;
    private volatile long lastDueMs = Long.MIN_VALUE;
    private volatile long lastPts;          // 90 kHz, as the PES carried it (0 = none seen)
    private volatile boolean lastHadPts;
    private volatile long lastAtMs;         // wall clock of the last decoded statement
    private volatile String lastText = "";
    private volatile String language = "";

    private int lastCc = -1;

    public CaptionProbe(ServiceInfo service) {
        this.serviceId = service == null ? -1 : service.serviceId;
        this.serviceName = service == null ? "" : service.name;
        StringBuilder sb = new StringBuilder();
        if (service != null) {
            for (StreamInfo s : service.streams) {
                if (!isCaptionStream(s)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(String.format(Locale.US, "pid 0x%04X · type 0x%02X · tag 0x%02X · dci 0x%04X",
                        s.pid, s.streamType, s.componentTag, s.dataComponentId));
            }
        }
        this.declared = sb.toString();
        synchronized (CaptionProbe.class) {
            com.pbcnx.opendtvmod.App.diagnostics().rememberProbe(this);
        }
    }

    /** The newest probe in this process, or null when nothing has played a service yet. */
    @Nullable public static CaptionProbe last() {
        return com.pbcnx.opendtvmod.App.diagnostics().lastProbe();
    }

    // ------------------------------------------------------------------ fed by ServiceFilter

    /** One TS packet of the caption PID: counts it and watches continuity. */
    public void notePacket(int cc) {
        packets++;
        if (lastCc >= 0 && cc != lastCc && cc != ((lastCc + 1) & 0x0F)) ccErrors++;
        lastCc = cc;
    }

    /** A caption PID packet that could not even become a PES (no PES header, truncated). */
    public void noteMalformed() {
        malformed++;
    }

    /** One complete caption PES assembled (before it is decoded). */
    public void notePes(long dueMs) {
        pes++;
        if (dueMs != Long.MIN_VALUE) lastDueMs = dueMs;
    }

    /** The decoder's verdict on one PES; {@code pesPts} is its 90 kHz PTS, or -1 when it carried none. */
    public void noteDecode(AribCaption.Result r, long pesPts) {
        if (r == null) {
            malformed++;
            return;
        }
        if (r.language != null && !r.language.isEmpty()) language = r.language;
        if (!r.statement) {
            otherGroups++;
            return;
        }
        statements++;
        if (pesPts >= 0) {
            lastPts = pesPts;
            lastHadPts = true;
        }
        lastAtMs = System.currentTimeMillis();
        if (r.text == null || r.text.isEmpty()) {
            clears++;
            lastText = "";
        } else {
            lastText = r.text;
        }
    }

    // ------------------------------------------------------------------ read by the UI

    /** True when a statement has been decoded at all (not necessarily recently). */
    public boolean seen() {
        return statements > 0;
    }

    /** True when a caption statement was decoded in the last {@code windowMs}. */
    public boolean seenWithin(long windowMs) {
        return statements > 0 && System.currentTimeMillis() - lastAtMs <= windowMs;
    }

    public String declaredText() {
        return declared;
    }

    /**
     * One line, for the Logs tab: seen / not seen, last at HH:MM:SS.
     *
     * <pre>
     * Captions: seen · last 12:34:56 · 47 statements · pid 0x0141
     * Captions: not seen · pid 0x0141 declared, nothing decoded yet
     * Captions: not seen · the PMT declares no caption stream
     * </pre>
     */
    public String summary() {
        String pid = firstCaptionPid();
        if (declared.isEmpty()) {
            return "Captions: not seen · the PMT declares no caption stream";
        }
        if (statements == 0) {
            return "Captions: not seen · " + pid + " declared, " + pes + " PES, nothing decoded yet";
        }
        String at = timeOfDay(lastAtMs);
        if (System.currentTimeMillis() - lastAtMs > 30_000) {
            return "Captions: not seen · last " + at + " · " + statements + " statements · " + pid;
        }
        return "Captions: seen · last " + at + " · " + statements + " statements · " + pid;
    }

    /**
     * A cheap identity for "the current state of this probe", for a caller that wants to log only on
     * change: seen/not seen, the minute the last statement landed in, and which streams are declared.
     * The counters move on every caption, so comparing the summary text would log a line per caption.
     */
    public String stateKey() {
        return (statements > 0 ? "seen" : "not") + "|" + (statements > 0 ? lastAtMs / 60_000 : 0)
                + "|" + declared.hashCode();
    }

    /**
     * Everything, for the detail dialog: what the PMT declares, what arrived, what the decoder made of
     * it, and the last text. Monospaced, copyable, one fact per line.
     */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("service   ").append(serviceId);
        if (!serviceName.isEmpty()) sb.append(" · ").append(serviceName);
        sb.append('\n');
        sb.append("declared  ").append(declared.isEmpty() ? "no caption stream in the PMT" : declared).append('\n');
        sb.append("received  ").append(packets).append(" packets · ").append(pes).append(" PES");
        if (ccErrors > 0) sb.append(" · ").append(ccErrors).append(" CC errors");
        sb.append('\n');
        sb.append("decoded   ").append(statements).append(" statements · ").append(clears).append(" clears")
                .append(" · ").append(otherGroups).append(" management/other language")
                .append(" · ").append(malformed).append(" malformed").append('\n');
        if (lastHadPts) {
            sb.append("last PES  PTS ").append(ptsText(lastPts))
                    .append(" · due ").append(msText(lastDueMs)).append('\n');
        } else {
            sb.append("last PES  no PTS on the caption stream\n");
        }
        sb.append("last seen ").append(statements > 0 ? timeOfDay(lastAtMs) : "never")
                .append(language.isEmpty() ? "" : " · language " + language).append('\n');
        sb.append("text      ").append(lastText.isEmpty() ? "(none)" : oneLine(lastText));
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private String firstCaptionPid() {
        int nl = declared.indexOf('\n');
        String first = nl < 0 ? declared : declared.substring(0, nl);
        int sp = first.indexOf(' ');
        int end = first.indexOf(' ', sp + 1);
        return end < 0 ? first : first.substring(0, end);
    }

    private static String oneLine(String text) {
        String t = text.replace("\r", "").replace("\n", " / ");
        return t.length() > 240 ? t.substring(0, 240) + "…" : t;
    }

    private static String timeOfDay(long ms) {
        if (ms <= 0) return "--:--:--";
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    /** 90 kHz PTS as HH:MM:SS.mmm, which is what a caption's timing is written in everywhere else. */
    private static String ptsText(long pts) {
        return msText(pts / 90);
    }

    private static String msText(long ms) {
        if (ms == Long.MIN_VALUE || ms < 0) return "--";
        long h = ms / 3_600_000;
        long m = ms / 60_000 % 60;
        long s = ms / 1000 % 60;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, ms % 1000);
    }
}
