package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The analyzer hand-off coordination, tested without an Activity. */
public class AnalyzerHandoffTest {

    private static final class Fake implements AnalyzerHandoff.Actions {
        final AtomicInteger gen = new AtomicInteger(7);
        final List<String> calls = new ArrayList<>();
        Runnable posted;
        boolean finishing;
        int khz = 599143;
        int startedKhz;
        int startedRequest;

        @Override public AtomicInteger playGen() { return gen; }
        @Override public boolean isFinishing() { return finishing; }
        @Override public int currentKhz() { return khz; }
        @Override public void setHandingOver(boolean value) { calls.add("handoff=" + value); }
        @Override public void setCanTune(boolean value) { calls.add("canTune=" + value); }
        @Override public void stopPlayer() { calls.add("stop"); }
        @Override public void detachSink() { calls.add("detach"); }
        @Override public void clearPipe() { calls.add("pipe"); }
        @Override public void post(Runnable action) { posted = action; }
        @Override public void startAnalyzer(int khz, int requestCode) { startedKhz = khz; startedRequest = requestCode; }
    }

    @Test
    public void launchReleasesThenStartsTheAnalyzerWithThePlayingFrequency() {
        Fake fake = new Fake();
        AnalyzerHandoff.launch(fake, 42);
        assertEquals(8, fake.gen.get());
        assertEquals("handoff=true", fake.calls.get(0));
        assertTrue(fake.calls.contains("stop"));
        assertTrue(fake.calls.contains("detach"));
        assertTrue(fake.calls.contains("pipe"));
        fake.posted.run();
        assertEquals(599143, fake.startedKhz);
        assertEquals(42, fake.startedRequest);
    }

    @Test
    public void finishingScreenForgetsTheHandOffInsteadOfStartingTheAnalyzer() {
        Fake fake = new Fake();
        fake.finishing = true;
        AnalyzerHandoff.launch(fake, 42);
        fake.posted.run();
        assertEquals("handoff=false", fake.calls.get(fake.calls.size() - 1));
        assertEquals(0, fake.startedRequest);
        assertFalse(fake.calls.contains("canTune=true"));
    }
}
