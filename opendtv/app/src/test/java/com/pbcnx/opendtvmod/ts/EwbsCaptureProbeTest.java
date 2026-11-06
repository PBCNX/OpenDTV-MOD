package com.pbcnx.opendtvmod.ts;

import java.io.File;
import java.io.FileInputStream;

import org.junit.Assume;
import org.junit.Test;

/**
 * Evidence probe for the EWBS false-positive fix (jpdcs#12): feeds a real capture through the demux
 * and prints what the EWBS monitor concluded, plus its own log. Run it with
 * {@code ODTV_CAPTURE=<file.ts>}; it skips otherwise, so it costs the default gate nothing.
 *
 * <p>Used as the A/B witness on 2026-11-06: an ordinary channel's routine BML event messages used to
 * leave the monitor saying "EWBS was signalled" (event_msg_id 0x0031 read as the private text "1",
 * and a shared flag that any service's routine "2" could end). After the fix the same capture says
 * "no signal seen".</p>
 */
public class EwbsCaptureProbeTest {

    @Test public void printsTheEwbsConclusion() throws Exception {
        String path = System.getenv("ODTV_CAPTURE");
        Assume.assumeTrue("set ODTV_CAPTURE=<file.ts> to run this probe",
                path != null && new File(path).isFile());

        TsDemux d = new TsDemux();
        byte[] buf = new byte[188 * 1000];
        long packets = 0;
        try (FileInputStream in = new FileInputStream(path)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                d.feed(buf, 0, n);
                packets += n / 188;
                if (packets >= 200_000) break;
            }
        }
        System.out.println("PROBE capture=" + new File(path).getName() + " packets=" + packets);
        System.out.println("PROBE isEwbsActive=" + d.isEwbsActive()
                + " ewbsEverSeen=" + d.ewbsEverSeen()
                + " banner='" + d.ewbsBannerText() + "'");
        for (String line : d.ewbsReport().split("\n")) {
            System.out.println("PROBE | " + line);
        }
    }
}
