package com.pbcnx.opendtvmod.analyzer;

import com.pbcnx.opendtvmod.ts.util.TsUtil;

import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.ts.EpgEvent;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/** The EPG tab's text: the events the current service advertises, as broadcast. */
public final class EpgPanel {

    private EpgPanel() {
    }

    public static String epgText(TsDemux d, ServiceInfo sel) {
        if (sel == null) return "Select a channel.";
        List<EpgEvent> events = d.getEvents(sel.serviceId);
        if (events.isEmpty()) return "No EIT data yet for this service (can take several seconds).";
        long now = d.getClockMs();
        StringBuilder sb = new StringBuilder("Times as broadcast (JST per ARIB)\n\n");
        int shown = 0;
        for (EpgEvent e : events) {
            if (shown++ >= 40) break;
            boolean current = now >= 0 && e.startMs >= 0 && now >= e.startMs && now < e.endMs();
            sb.append(current ? "> " : "  ")
                    .append(TsUtil.formatTime(e.startMs)).append(" +")
                    .append(String.format(Locale.US, "%d:%02d", e.durationSec / 3600, e.durationSec / 60 % 60))
                    .append("  ").append(e.name.isEmpty() ? "(no title)" : e.name).append('\n');
            if (!e.text.isEmpty()) sb.append("      ").append(e.text).append('\n');
        }
        return sb.toString();
    }
}
