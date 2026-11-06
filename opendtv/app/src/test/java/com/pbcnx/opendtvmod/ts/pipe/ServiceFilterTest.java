package com.pbcnx.opendtvmod.ts.pipe;

import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The single-service filter: it is on the only path from the tuner to the player, so its
 * pass/drop decisions, the PAT it injects and the recording tee are pinned here rather than only
 * exercised on a device.
 */
public class ServiceFilterTest {

    private static final int PKT = 188;
    private static final int SERVICE_ID = 100;
    private static final int PMT_PID = 0x100;
    private static final int VIDEO_PID = 0x201;
    private static final int AUDIO_PID = 0x302;

    private static ServiceInfo service() {
        ServiceInfo svc = new ServiceInfo(SERVICE_ID);
        svc.pmtPid = PMT_PID;
        svc.pcrPid = PMT_PID;
        StreamInfo video = new StreamInfo();
        video.pid = VIDEO_PID;
        video.streamType = 0x1B;                 // H.264
        StreamInfo audio = new StreamInfo();
        audio.pid = AUDIO_PID;
        audio.streamType = 0x0F;                 // AAC ADTS
        svc.streams.add(video);
        svc.streams.add(audio);
        return svc;
    }

    private static byte[] packet(int pid, int cc) {
        byte[] p = new byte[PKT];
        java.util.Arrays.fill(p, (byte) 0xFF);
        p[0] = 0x47;
        p[1] = (byte) (((pid >> 8) & 0x1F) | 0x40);   // PUSI: the payload starts here
        p[2] = (byte) pid;
        p[3] = (byte) (0x10 | (cc & 0x0F));
        return p;
    }

    /** Reads everything currently buffered in the pipe. */
    private static List<byte[]> drain(TsPipe pipe) throws Exception {
        List<byte[]> packets = new ArrayList<>();
        byte[] buf = new byte[PKT * 64];
        while (true) {
            int n = pipe.read(buf, 0, buf.length, 20);
            if (n <= 0) break;
            for (int at = 0; at + PKT <= n; at += PKT) {
                packets.add(java.util.Arrays.copyOfRange(buf, at, at + PKT));
            }
        }
        return packets;
    }

    private static int pidOf(byte[] packet) {
        return ((packet[1] & 0x1F) << 8) | (packet[2] & 0xFF);
    }

    @Test
    public void passesOnlyTheServicesPids() throws Exception {
        TsPipe pipe = new TsPipe(64 * PKT);
        ServiceFilter filter = new ServiceFilter(pipe, 1, service());

        filter.onPacket(packet(VIDEO_PID, 0), 0);
        filter.flush();
        List<byte[]> passed = drain(pipe);
        // The filter injects its PAT before the first packet, so the service's own packet is second.
        assertEquals(2, passed.size());
        assertEquals(0, pidOf(passed.get(0)));
        assertEquals(VIDEO_PID, pidOf(passed.get(1)));

        filter.onPacket(packet(0x999, 0), 0);        // a PID the service does not carry
        filter.flush();
        assertTrue("a foreign PID must not reach the player", drain(pipe).isEmpty());
    }

    @Test
    public void injectsItsOwnSingleProgramPat() throws Exception {
        TsPipe pipe = new TsPipe(128 * PKT);
        ServiceFilter filter = new ServiceFilter(pipe, /* tsId= */ 1, service());
        for (int i = 0; i < 101; i++) filter.onPacket(packet(VIDEO_PID, i), 0);
        filter.flush();

        byte[] pat = null;
        for (byte[] p : drain(pipe)) {
            if (pidOf(p) == 0) {
                pat = p;
                break;
            }
        }
        assertNotNull("the filter must inject a PAT the extractor can lock onto", pat);
        assertEquals("table_id 0 at the pointer payload", 0x00, pat[5] & 0xFF);
        int sid = ((pat[13] & 0xFF) << 8) | (pat[14] & 0xFF);
        int pmt = ((pat[15] & 0x1F) << 8) | (pat[16] & 0xFF);
        assertEquals("the PAT names the selected service", SERVICE_ID, sid);
        assertEquals("and points at its PMT", PMT_PID, pmt);
    }

    @Test
    public void theRecordTeeWritesTheSamePacketsItPasses() throws Exception {
        TsPipe pipe = new TsPipe(64 * PKT);
        ServiceFilter filter = new ServiceFilter(pipe, 1, service());
        File file = File.createTempFile("servicefilter", ".ts");
        //noinspection ResultOfMethodCallIgnored
        file.deleteOnExit();

        filter.startRecord(file);
        assertTrue(filter.isRecording());
        for (int i = 0; i < 3; i++) filter.onPacket(packet(VIDEO_PID, i), 0);
        filter.flush();
        assertTrue(filter.stopRecord());
        assertFalse(filter.isRecording());
        assertEquals("the injected PAT plus three service packets", 4L * PKT, file.length());
        assertEquals(null, filter.recordError());
    }

    @Test
    public void anEmptyRecordingIsReportedAndDeleted() throws Exception {
        TsPipe pipe = new TsPipe(64 * PKT);
        ServiceFilter filter = new ServiceFilter(pipe, 1, service());
        File file = File.createTempFile("empty", ".ts");

        filter.startRecord(file);
        filter.flush();
        assertFalse("nothing was passed, so there is nothing to keep", filter.stopRecord());
        assertFalse(file.exists());
    }
}
