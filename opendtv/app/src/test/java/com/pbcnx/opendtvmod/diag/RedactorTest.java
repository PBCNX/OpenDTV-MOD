package com.pbcnx.opendtvmod.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * One case per pattern the redactor promises, plus the lines it must NOT touch. The product is a
 * bug-report file: a false negative leaks something, a false positive removes the evidence that
 * would have found the bug, so both directions are asserted.
 */
public class RedactorTest {

    @Test
    public void ipv4IsReplaced() {
        assertEquals("host <redacted> connected",
                Redactor.redact("host 192.168.1.5 connected"));
        assertEquals("peer <redacted>:8080 up",
                Redactor.redact("peer 10.0.0.42:8080 up"));
    }

    @Test
    public void ipv6IsReplaced() {
        assertEquals("peer <redacted> up", Redactor.redact("peer 2001:db8::1 up"));
        assertEquals("link <redacted> down", Redactor.redact("link fe80::1 down"));
        assertEquals("<redacted>", Redactor.redact("2606:4700:4700::1111"));
    }

    @Test
    public void macAddressesAreReplaced() {
        assertEquals("mac <redacted> seen", Redactor.redact("mac 00:1A:2B:3C:4D:5E seen"));
        assertEquals("mac <redacted> seen", Redactor.redact("mac 00-1a-2b-3c-4d-5e seen"));
    }

    @Test
    public void emailAddressesAreReplaced() {
        assertEquals("write to <redacted> now",
                Redactor.redact("write to bob.smith+tv@example.com now"));
    }

    @Test
    public void internationalPhoneNumberIsReplaced() {
        assertEquals("call <redacted> now", Redactor.redact("call +63 917 123 4567 now"));
    }

    @Test
    public void localPhoneNumbersAreReplaced() {
        assertEquals("mobile <redacted>", Redactor.redact("mobile 0917-123-4567"));
        assertEquals("mobile <redacted>", Redactor.redact("mobile 0917 123 4567"));
        assertEquals("landline <redacted>", Redactor.redact("landline 8123-4567"));
        assertEquals("mobile <redacted>", Redactor.redact("mobile 09171234567"));
    }

    @Test
    public void windowsHomePathLosesTheUserName() {
        String out = Redactor.redact("open C:\\Users\\Bob\\AppData\\Local\\log.txt");
        assertTrue(out, out.contains(Redactor.PATH));
        assertFalse(out, out.contains("Bob"));
    }

    @Test
    public void unixHomePathLosesTheUserName() {
        String out = Redactor.redact("read /home/bob/OpenDTV/a.ts");
        assertTrue(out, out.contains(Redactor.PATH));
        assertFalse(out, out.contains("bob"));

        String mac = Redactor.redact("read /Users/carol/OpenDTV/a.ts");
        assertTrue(mac, mac.contains(Redactor.PATH));
        assertFalse(mac, mac.contains("carol"));
    }

    @Test
    public void appExternalFilesPathIsReplaced() {
        String out = Redactor.redact(
                "saved /storage/emulated/0/Android/data/com.pbcnx.opendtvmod.ph/files/diagnostics/a.txt");
        assertEquals("saved <path>", out);
    }

    @Test
    public void appUserVisibleFolderIsReplaced() {
        String out = Redactor.redact("record /sdcard/OpenDTV/wholets/a.ts");
        assertEquals("record <path>", out);
        assertFalse(out, out.contains("wholets"));
    }

    @Test
    public void appInternalDataPathIsReplaced() {
        String out = Redactor.redact("prefs /data/user/0/com.pbcnx.opendtvmod.ph/shared_prefs/x.xml");
        assertEquals("prefs <path>", out);
    }

    @Test
    public void extraAbsoluteAppPathsAreReplacedLiterally() {
        String root = "/mnt/diag-test-root";
        String out = Redactor.redact("wrote " + root + "/files/log.txt",
                Collections.singletonList(root));
        assertEquals("wrote <path>/files/log.txt", out);
        assertFalse(out, out.contains("diag-test-root"));
    }

    @Test
    public void realLogLineLosesEverySensitiveTokenWithoutLosingTheLine() {
        String line = "21:33:10  status: tuner LOCKED on 557000 kHz - ip 192.168.1.77 "
                + "mac 00:1A:2B:3C:4D:5E mail juan.dela.cruz@example.ph phone +63 917 123 4567 "
                + "file C:\\Users\\Juan\\OpenDTV\\wholets\\a.ts";
        String out = Redactor.redact(line);

        assertFalse(out, out.contains("192.168.1.77"));
        assertFalse(out, out.contains("00:1A:2B:3C:4D:5E"));
        assertFalse(out, out.contains("juan.dela.cruz@example.ph"));
        assertFalse(out, out.contains("+63 917 123 4567"));
        assertFalse(out, out.contains("Juan"));

        // The words a bug report lives on survive.
        assertTrue(out, out.contains("LOCKED on 557000 kHz"));
        assertTrue(out, out.contains("21:33:10"));
        assertTrue(out, out.contains("status:"));
        assertTrue(out, out.contains(Redactor.REDACTED));
        assertTrue(out, out.contains(Redactor.PATH));
    }

    @Test
    public void timestampsVersionsAndFrequenciesAreNotMistakenForAddresses() {
        String line = "21:33:10  LOCKED on 557000 kHz  version 1.7.1-0.9.0  sample 3/4";
        assertEquals(line, Redactor.redact(line));
    }

    @Test
    public void datesAreNotMistakenForPhoneNumbers() {
        String line = "built 2026-11-07 21:33:10";
        assertEquals(line, Redactor.redact(line));
    }

    @Test
    public void redactionIsIdempotent() {
        String once = Redactor.redact(
                "192.168.1.5 bob@example.com +63 917 123 4567 C:\\Users\\Bob\\x.ts");
        assertEquals(once, Redactor.redact(once));
    }

    @Test
    public void nullBecomesEmpty() {
        assertEquals("", Redactor.redact(null));
        assertEquals("", Redactor.redact(null, Arrays.asList("/x")));
    }
}
