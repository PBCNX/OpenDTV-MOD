package com.pbcnx.opendtvmod.ts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import com.pbcnx.opendtvmod.ts.pipe.ServiceFilter;
import com.pbcnx.opendtvmod.ts.pipe.TsPipe;

/**
 * One stream through the real chain: TsDemux.feed -> tap -> ServiceFilter -> TsPipe. The unit tests
 * cover each stage; this pins their hand-off (sync, tap order, filter PAT injection, pipe bytes).
 */
public class StreamIntegrationTest {

    private static final int PKT = 188;
    private static final int SERVICE_ID = 100;
    private static final int PMT_PID = 0x100;
    private static final int VIDEO_PID = 0x201;

    private static ServiceInfo service() {
        ServiceInfo svc = new ServiceInfo(SERVICE_ID);
        svc.pmtPid = PMT_PID;
        svc.pcrPid = PMT_PID;
        StreamInfo video = new StreamInfo();
        video.pid = VIDEO_PID;
        video.streamType = 0x1B;
        svc.streams.add(video);
        return svc;
    }

    private static byte[] packet(int pid, int cc, int fill) {
        byte[] p = new byte[PKT];
        java.util.Arrays.fill(p, (byte) fill);
        p[0] = 0x47;
        p[1] = (byte) (((pid >> 8) & 0x1F) | 0x40);
        p[2] = (byte) pid;
        p[3] = (byte) (0x10 | (cc & 0x0F));
        return p;
    }

    private static int pidOf(byte[] p) {
        return ((p[1] & 0x1F) << 8) | (p[2] & 0xFF);
    }

    @Test
    public void demuxTapFilterPipeDeliversTheSelectedService() throws Exception {
        TsPipe pipe = new TsPipe(64 * PKT);
        ServiceFilter filter = new ServiceFilter(pipe, /* tsId= */ 1, service());
        TsDemux demux = new TsDemux();
        demux.addTapSink(filter);

        byte[] stream = new byte[3 * PKT];
        System.arraycopy(packet(VIDEO_PID, 0, 0x11), 0, stream, 0, PKT);
        System.arraycopy(packet(VIDEO_PID, 1, 0x22), 0, stream, PKT, PKT);
        System.arraycopy(packet(0x999, 0, 0x33), 0, stream, 2 * PKT, PKT);
        demux.feed(stream, 0, stream.length);
        filter.flush();

        List<byte[]> passed = new ArrayList<>();
        byte[] buf = new byte[PKT * 64];
        while (true) {
            int n = pipe.read(buf, 0, buf.length, 20);
            if (n <= 0) break;
            for (int at = 0; at + PKT <= n; at += PKT) {
                passed.add(java.util.Arrays.copyOfRange(buf, at, at + PKT));
            }
        }
        assertTrue("the filter must inject its PAT and pass the service packets", passed.size() >= 3);
        assertEquals("first is the injected PAT", 0, pidOf(passed.get(0)));
        assertEquals(VIDEO_PID, pidOf(passed.get(1)));
        assertEquals(VIDEO_PID, pidOf(passed.get(2)));
        for (byte[] p : passed) {
            assertTrue("a foreign PID never reaches the player", pidOf(p) != 0x999);
        }
    }
}
