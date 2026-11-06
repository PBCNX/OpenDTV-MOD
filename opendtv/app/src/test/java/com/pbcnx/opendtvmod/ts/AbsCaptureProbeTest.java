package com.pbcnx.opendtvmod.ts;

import java.io.File;
import java.io.FileInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

/**
 * Evidence probe for the ABS-CBN service pick: feeds a real capture through {@link TsDemux} and
 * records what the playback picker would have believed at each moment - which PMT had arrived,
 * which service looked like video, which looked like video + datacast - plus the stream list of the
 * two services at issue. It answers "why 3224 and not 3200" from data instead of a guess.
 *
 * <p>Not part of the default gate: it needs a capture, so it skips unless {@code ODTV_CAPTURE}
 * points at a file. Run it with the environment variable set and {@code --info} to see the lines.</p>
 */
public class AbsCaptureProbeTest {

    private static final long MAX_PACKETS = 400_000;   // ~75 MB: several PAT/PMT cycles, and fast
    private static final long TRACK_EVERY = 94;        // half a PID cycle: cheap, still fine-grained

    @Test public void printsWhatThePickerBelieved() throws Exception {
        String path = System.getenv("ODTV_CAPTURE");
        Assume.assumeTrue("set ODTV_CAPTURE=<file.ts> to run this probe",
                path != null && new File(path).isFile());

        TsDemux d = new TsDemux();
        byte[] buf = new byte[188 * 1000];
        long packets = 0;
        long nextMark = 0;
        Map<Integer, Long> pmtAt = new LinkedHashMap<>();
        Map<Integer, Long> dataAt = new LinkedHashMap<>();
        try (FileInputStream in = new FileInputStream(path)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                d.feed(buf, 0, n);
                packets += n / 188;
                if (packets >= nextMark) {
                    stamp(d, packets, pmtAt, dataAt);
                    // Dense at the start (where the pick happens), then coarser to the end.
                    nextMark = nextMark == 0 ? TRACK_EVERY : (nextMark < 2_000 ? nextMark + TRACK_EVERY : nextMark * 2);
                }
                if (packets > MAX_PACKETS) break;
            }
        }
        reportStreams(d, 3200);
        reportStreams(d, 3224);
    }

    /** One line per observation: every service's pmt/video/datacast state at this packet count. */
    private static void stamp(TsDemux d, long packets, Map<Integer, Long> pmtAt, Map<Integer, Long> dataAt) {
        StringBuilder sb = new StringBuilder("@").append(packets).append(" pkts");
        for (ServiceInfo s : d.getServices()) {
            int data = 0;
            for (StreamInfo st : s.streams) if (st.isDatacast()) data++;
            if (s.pmtSeen && !pmtAt.containsKey(s.serviceId)) {
                pmtAt.put(s.serviceId, packets);
                System.out.println("PROBE pmt-first sid=" + s.serviceId + " at " + packets + " pkts");
            }
            if (data > 0 && !dataAt.containsKey(s.serviceId)) {
                dataAt.put(s.serviceId, packets);
                System.out.println("PROBE data-first sid=" + s.serviceId + " at " + packets + " pkts");
            }
            sb.append(" | ").append(s.serviceId)
                    .append(" pmt=").append(s.pmtSeen ? 1 : 0)
                    .append(" vid=").append(s.hasVideo() ? 1 : 0)
                    .append(" data=").append(data)
                    .append(" str=").append(s.streams.size());
        }
        System.out.println("PROBE " + sb);
    }

    private static void reportStreams(TsDemux d, int sid) {
        for (ServiceInfo s : d.getServices()) {
            if (s.serviceId != sid) continue;
            System.out.println("PROBE --- sid " + sid + " pmtPid=0x" + Integer.toHexString(s.pmtPid)
                    + " pmtSeen=" + s.pmtSeen + " video=" + s.hasVideo()
                    + " name='" + s.name + "'");
            for (StreamInfo st : s.streams) {
                System.out.println("PROBE       pid=0x" + Integer.toHexString(st.pid)
                        + " type=0x" + Integer.toHexString(st.streamType)
                        + " datacast=" + st.isDatacast() + " codec=" + st.codecName());
            }
        }
    }
}
