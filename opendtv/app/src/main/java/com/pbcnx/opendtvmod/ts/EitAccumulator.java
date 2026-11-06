package com.pbcnx.opendtvmod.ts;

import com.pbcnx.opendtvmod.ts.util.TsUtil;

import static com.pbcnx.opendtvmod.ts.util.TsUtil.bcd;
import static com.pbcnx.opendtvmod.ts.util.TsUtil.u12;
import static com.pbcnx.opendtvmod.ts.util.TsUtil.u16;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates the EIT (EPG) events of every service into one map keyed by
 * {@code service_id << 16 | event_id}.
 *
 * <p>Split out of {@link TsDemux} without a logic change: the same short_event_descriptor bytes
 * become name and text, and the same 4000-event ceiling clears the whole map exactly as before, so
 * a burst of events still resets the guide the way it always did. Not thread-safe by itself;
 * TsDemux calls it under its own lock.
 */
final class EitAccumulator {
    private static final int MAX_EVENTS = 4000;

    private final Map<Long, EpgEvent> events = new HashMap<>();

    /** Parses one EIT section (table_id 0x4E-0x5F) and stores/updates its events. */
    void parse(byte[] s, int n) {
        int sid = u16(s, 3);
        int p = 14;
        int end = n - 4;
        while (p + 12 <= end) {
            EpgEvent e = new EpgEvent();
            e.serviceId = sid;
            e.eventId = u16(s, p);
            e.startMs = (u16(s, p + 2) == 0xFFFF) ? -1 : TsUtil.mjdToMillis(s, p + 2);
            e.durationSec = bcd(s[p + 7]) * 3600 + bcd(s[p + 8]) * 60 + bcd(s[p + 9]);
            e.runningStatus = (s[p + 10] >> 5) & 7;
            int dEnd = Math.min(p + 12 + u12(s, p + 10), end);
            for (int d = p + 12; d + 2 <= dEnd; ) {
                int tag = s[d] & 0xFF;
                int dl = s[d + 1] & 0xFF;
                if (tag == 0x4D && dl >= 5) { // short_event_descriptor
                    int nl = s[d + 5] & 0xFF;
                    int nameStart = d + 6;
                    int nameEnd = nameStart + nl;
                    // The descriptor length, not the section buffer, bounds the name and text:
                    // an over-long length must truncate, not read the next section's bytes.
                    if (nameEnd <= dEnd) {
                        e.name = TsUtil.text(s, nameStart, nl);
                        int tl = s[nameEnd] & 0xFF;
                        int textStart = nameEnd + 1;
                        int textEnd = Math.min(textStart + tl, dEnd);
                        e.text = TsUtil.text(s, textStart, Math.max(0, textEnd - textStart));
                    }
                }
                d += 2 + dl;
            }
            if (events.size() > MAX_EVENTS) events.clear();
            events.put(((long) sid << 16) | e.eventId, e);
            p = dEnd;
        }
    }

    /** Events of one service, sorted by start time. */
    List<EpgEvent> events(int serviceId) {
        List<EpgEvent> out = new ArrayList<>();
        for (EpgEvent e : events.values()) if (e.serviceId == serviceId) out.add(e);
        Collections.sort(out, new Comparator<EpgEvent>() {
            @Override
            public int compare(EpgEvent a, EpgEvent b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        return out;
    }
}
