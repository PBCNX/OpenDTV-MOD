package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import com.pbcnx.opendtvmod.ts.EpgEvent;

/** The now/next selection: running event wins, earliest future event is next. */
public class EpgNowNextTest {

    private static EpgEvent event(String name, long start, int durationSec) {
        EpgEvent e = new EpgEvent();
        e.name = name;
        e.startMs = start;
        e.durationSec = durationSec;
        return e;
    }

    @Test
    public void picksRunningAndEarliestFuture() {
        List<EpgEvent> events = new ArrayList<>();
        events.add(event("Past", 0, 10));
        events.add(event("Now", 1_000, 20));
        events.add(event("Later", 40_000, 10));
        events.add(event("Next", 25_000, 10));
        String[] names = EpgNowNext.names(events, 5_000);
        assertEquals("Now", names[0]);
        assertEquals("Next", names[1]);
    }

    @Test
    public void missingEitAnswersNulls() {
        String[] names = EpgNowNext.names(new ArrayList<>(), 5_000);
        assertNull(names[0]);
        assertNull(names[1]);
    }
}
