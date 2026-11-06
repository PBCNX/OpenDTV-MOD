package com.pbcnx.opendtvmod.ui.watch;

import java.util.List;

import com.pbcnx.opendtvmod.ts.EpgEvent;

/** The now/next programme names for a service's EIT, with the clock fallback in one place. */
public final class EpgNowNext {

    private EpgNowNext() {
    }

    /**
     * @return {now, next} names, null where the EIT has no usable entry
     */
    public static String[] names(List<EpgEvent> events, long nowMs) {
        EpgEvent nowEvent = null;
        EpgEvent nextEvent = null;
        for (EpgEvent ev : events) {
            if (ev.startMs <= nowMs && ev.endMs() > nowMs) {
                nowEvent = ev;
            } else if (ev.startMs > nowMs) {
                if (nextEvent == null || ev.startMs < nextEvent.startMs) {
                    nextEvent = ev;
                }
            }
        }
        return new String[] {
                nowEvent != null && !nowEvent.name.isEmpty() ? nowEvent.name : null,
                nextEvent != null && !nextEvent.name.isEmpty() ? nextEvent.name : null
        };
    }
}
