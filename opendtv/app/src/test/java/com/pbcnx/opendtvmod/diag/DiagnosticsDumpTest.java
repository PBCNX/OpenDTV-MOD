package com.pbcnx.opendtvmod.diag;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What the bundle must contain, and - more importantly - what it must not contain without the
 * user's opt-in. Device model and channel/frequency data are gated twice on the device (the
 * collector leaves them out, the builder refuses them); the tests prove the builder's half.
 */
public class DiagnosticsDumpTest {

    /** Sentinels chosen so a search proves absence, not just a missing section title. */
    private static final String DEVICE_MODEL = "SM-S918B";
    private static final String DEVICE_MAKER = "Samsung";
    private static final String CHANNEL_NAME = "TV5";
    private static final String FREQUENCY = "557000";

    private static DiagnosticsDump.Input input() {
        DiagnosticsDump.Input in = new DiagnosticsDump.Input();
        in.generatedAt = "2026-11-07 21:45:00";
        in.buildStamp = "0.2.0";
        in.versionCode = 1057;
        in.commit = "3e7828e";
        in.androidRelease = "Android 14";
        in.sdkInt = 34;
        in.abi = "arm64-v8a";
        in.tunerName = "SMS Rio";
        in.tunerUsbId = "187f:0600";
        in.tunerFailure = "no lock";
        in.signalSnapshot = "locked=yes strength=72% quality=88% snr=23.4 dB dbm=-34 dBm ber=1.2e-4";
        in.decoderManual = false;
        in.learnedMuxMap = FREQUENCY + ":1,605000:0";
        in.channelLines.add(FREQUENCY + " kHz  21.1  " + CHANNEL_NAME + " (GMA)");
        in.ring = "21:33:10  status: LOCKED on " + FREQUENCY + " kHz";
        // The full-opt-in fixture: the individual tests switch these off to prove the gates.
        in.includeDeviceModel = true;
        in.deviceManufacturer = DEVICE_MAKER;
        in.deviceModel = DEVICE_MODEL;
        in.includeChannelData = true;
        in.frequencyKhz = Integer.parseInt(FREQUENCY);
        return in;
    }

    @Test
    public void everySectionIsPresent() {
        String out = DiagnosticsDump.build(input());
        assertTrue(out, out.contains("== BUILD =="));
        assertTrue(out, out.contains("== PLATFORM =="));
        assertTrue(out, out.contains("== DEVICE =="));
        assertTrue(out, out.contains("== TUNER =="));
        assertTrue(out, out.contains("== SIGNAL =="));
        assertTrue(out, out.contains("== DECODER =="));
        assertTrue(out, out.contains("== CHANNELS =="));
        assertTrue(out, out.contains("== SESSION LOG (redacted) =="));
    }

    @Test
    public void buildPlatformAndTunerFactsArePresent() {
        String out = DiagnosticsDump.build(input());
        assertTrue(out, out.contains("0.2.0"));
        assertTrue(out, out.contains("1057"));
        assertTrue(out, out.contains("3e7828e"));
        assertTrue(out, out.contains("Android 14"));
        assertTrue(out, out.contains("arm64-v8a"));
        assertTrue(out, out.contains("SMS Rio"));
        assertTrue(out, out.contains("187f:0600"));
        assertTrue(out, out.contains("no lock"));
        assertTrue(out, out.contains("manual"));
    }

    @Test
    public void excludedDataStatementIsAlwaysPresent() {
        DiagnosticsDump.Input optedOut = input();
        optedOut.includeDeviceModel = false;
        optedOut.includeChannelData = false;
        for (String out : new String[]{DiagnosticsDump.build(input()), DiagnosticsDump.build(optedOut)}) {
            assertTrue(out, out.contains(DiagnosticsDump.EXCLUDED_STATEMENT));
            assertTrue(out, out.contains(DiagnosticsDump.NO_UPLOAD_STATEMENT));
        }
    }

    @Test
    public void deviceModelIsAbsentUntilOptedIn() {
        DiagnosticsDump.Input optedOut = input();
        optedOut.includeDeviceModel = false;
        String out = DiagnosticsDump.build(optedOut);
        assertFalse(out, out.contains(DEVICE_MODEL));
        assertFalse(out, out.contains(DEVICE_MAKER));
        assertTrue(out, out.contains("withheld (opt-in)"));

        DiagnosticsDump.Input optedIn = input();
        optedIn.includeDeviceModel = true;
        String shown = DiagnosticsDump.build(optedIn);
        assertTrue(shown, shown.contains(DEVICE_MODEL));
        assertTrue(shown, shown.contains(DEVICE_MAKER));
    }

    @Test
    public void channelNamesAndFrequenciesAreAbsentUntilOptedIn() {
        DiagnosticsDump.Input optedOut = input();
        optedOut.includeChannelData = false;
        String out = DiagnosticsDump.build(optedOut);
        assertFalse(out, out.contains(CHANNEL_NAME));
        assertFalse(out, out.contains(FREQUENCY));
        assertFalse(out, out.contains("605000"));

        DiagnosticsDump.Input optedIn = input();
        optedIn.includeChannelData = true;
        String shown = DiagnosticsDump.build(optedIn);
        assertTrue(shown, shown.contains(CHANNEL_NAME));
        assertTrue(shown, shown.contains(FREQUENCY));
        assertTrue(shown, shown.contains("21.1"));
    }

    @Test
    public void learnedMuxMemoryKeepsOnlyCountsWithoutOptIn() {
        DiagnosticsDump.Input optedOut = input();
        optedOut.includeChannelData = false;
        String out = DiagnosticsDump.build(optedOut);
        assertFalse(out, out.contains(FREQUENCY));
        // The count summary still says something useful about the Auto decoder.
        assertTrue(out, out.contains("2 muxes"));
        assertTrue(out, out.contains("software 1"));
        assertTrue(out, out.contains("hardware 1"));
    }

    @Test
    public void ringIsRedactedInTheBundle() {
        DiagnosticsDump.Input in = input();
        in.ring = "contact juan.dela.cruz@example.ph from 192.168.1.7 at +63 917 123 4567 "
                + "mac 00:1A:2B:3C:4D:5E";
        String out = DiagnosticsDump.build(in);
        assertFalse(out, out.contains("juan.dela.cruz"));
        assertFalse(out, out.contains("192.168.1.7"));
        assertFalse(out, out.contains("+63 917 123 4567"));
        assertFalse(out, out.contains("00:1A:2B:3C:4D:5E"));
        assertTrue(out, out.contains(Redactor.REDACTED));
    }

    @Test
    public void collectorAbsolutePathsAreReplacedLiterally() {
        DiagnosticsDump.Input in = input();
        in.appPaths.add("/mnt/diag-test-root");
        in.ring = "saved /mnt/diag-test-root/files/opendtv-diagnostics.txt";
        String out = DiagnosticsDump.build(in);
        assertFalse(out, out.contains("diag-test-root"));
        assertTrue(out, out.contains(Redactor.PATH));
    }

    @Test
    public void emptyInputStillProducesAUsableBundle() {
        String out = DiagnosticsDump.build(new DiagnosticsDump.Input());
        assertTrue(out, out.contains(DiagnosticsDump.EXCLUDED_STATEMENT));
        assertTrue(out, out.contains("(no log lines captured)"));
        assertTrue(out, out.contains("withheld (opt-in)"));
    }
}
