package com.pbcnx.opendtvmod.tuner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * The service-picker race, reproduced on the bench.
 *
 * <p>PMTs do not arrive together. On the ABS-CBN capture watched on 2026-11-06 the oneseg service
 * 3224 (video + audio, no datacast) had its PMT parsed one multiplex cycle before the main service
 * 3200 (H.264 + three DSM-CC carousels, the BML/EWBS one), and the picker adopted 3224 - chips off,
 * no BML page. A holdback was supposed to cover exactly that, but it could never fire: services are
 * visited in ascending id order, so a lower-id service was always seen before a higher-id video
 * candidate existed. These tests pin the intended behaviour instead.</p>
 */
public class ServiceWaitPickTest {

    private static final int PID_PAT = 0x0000;
    private static final int PID_PMT_3200 = 0x0456;
    private static final int PID_PMT_3224 = 0x1FC8;
    private static final int PID_V_3200 = 0x0457;
    private static final int PID_A_3200 = 0x0458;
    private static final int PID_V_3224 = 0x049D;
    private static final int PID_A_3224 = 0x049E;

    // ---------------------------------------------------------------- synthetic packets

    private static byte[] packet(int pid, int cc, byte[] section) {
        byte[] p = new byte[188];
        java.util.Arrays.fill(p, (byte) 0xFF);
        p[0] = 0x47;
        p[1] = (byte) (0x40 | ((pid >> 8) & 0x1F));   // payload_unit_start_indicator
        p[2] = (byte) pid;
        p[3] = (byte) (0x10 | (cc & 0x0F));           // adaptation_field_control = 1
        p[4] = 0x00;                                  // pointer_field
        System.arraycopy(section, 0, p, 5, section.length);
        return p;
    }

    /** CRC-32/MPEG-2, written independently of the production helper. */
    private static int crc32Mpeg(byte[] b, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : (crc << 1);
            }
        }
        return crc;
    }

    private static byte[] withCrc(byte[] body) {
        byte[] out = new byte[body.length + 4];
        System.arraycopy(body, 0, out, 0, body.length);
        int crc = crc32Mpeg(out, 0, body.length);
        out[body.length] = (byte) (crc >>> 24);
        out[body.length + 1] = (byte) (crc >>> 16);
        out[body.length + 2] = (byte) (crc >>> 8);
        out[body.length + 3] = (byte) crc;
        return out;
    }

    /** PAT with the given (serviceId, pmtPid) pairs. */
    private static byte[] pat(int... sidPmt) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x00);
        int len = 5 + 4 * (sidPmt.length / 2) + 4;
        b.write(0xB0 | (len >> 8));
        b.write(len & 0xFF);
        b.write(0x00);
        b.write(0x01);                                // transport_stream_id
        b.write(0xC1);
        b.write(0x00);
        b.write(0x00);
        for (int i = 0; i < sidPmt.length; i += 2) {
            b.write((sidPmt[i] >> 8) & 0xFF);
            b.write(sidPmt[i] & 0xFF);
            b.write(0xE0 | ((sidPmt[i + 1] >> 8) & 0x1F));
            b.write(sidPmt[i + 1] & 0xFF);
        }
        return withCrc(b.toByteArray());
    }

    /** PMT for one service: [streamType, pid] pairs, the first one also being the PCR PID. */
    private static byte[] pmt(int sid, int[][] streams) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int esLen = 5 * streams.length;
        int len = 9 + esLen + 4;
        b.write(0x02);
        b.write(0xB0 | (len >> 8));
        b.write(len & 0xFF);
        b.write((sid >> 8) & 0xFF);
        b.write(sid & 0xFF);
        b.write(0xC1);
        b.write(0x00);
        b.write(0x00);
        int pcrPid = streams[0][1];
        b.write(0xE0 | ((pcrPid >> 8) & 0x1F));
        b.write(pcrPid & 0xFF);
        b.write(0xF0);                                // program_info_length = 0
        b.write(0x00);
        for (int[] st : streams) {
            b.write(st[0]);
            b.write(0xE0 | ((st[1] >> 8) & 0x1F));
            b.write(st[1] & 0xFF);
            b.write(0xF0);                            // ES_info_length = 0
            b.write(0x00);
        }
        return withCrc(b.toByteArray());
    }

    private static void feed(TsDemux d, int pid, byte[] section) {
        byte[] p = packet(pid, 0, section);
        d.feed(p, 0, p.length);
    }

    /** PAT listing 3200 and 3224, then 3224's PMT only: what the picker sees first, on the bench. */
    private static TsDemux onesegFirst() {
        TsDemux d = new TsDemux();
        feed(d, PID_PAT, pat(3200, PID_PMT_3200, 3224, PID_PMT_3224));
        feed(d, PID_PMT_3224, pmt(3224, new int[][]{{0x1B, PID_V_3224}, {0x11, PID_A_3224}}));
        return d;
    }

    private static byte[] mainServicePmt() {
        return pmt(3200, new int[][]{{0x1B, PID_V_3200}, {0x0F, PID_A_3200},
                {0x0D, 0x386}, {0x0D, 0x387}, {0x0D, 0x388}});
    }

    // ---------------------------------------------------------------- tests

    @Test public void waitsForTheMainServiceWhenALowerPmtIsOnItsWay() throws Exception {
        final TsDemux d = onesegFirst();
        Thread latePmt = new Thread(() -> {
            try {
                Thread.sleep(300);   // one PMT cycle later, like the capture
            } catch (InterruptedException e) {
                return;
            }
            feed(d, PID_PMT_3200, mainServicePmt());
        });
        latePmt.start();
        long t0 = System.currentTimeMillis();
        ServiceInfo picked = ServiceWait.anyPlayable(() -> d, 5_000, () -> false);
        long waited = System.currentTimeMillis() - t0;
        latePmt.join();

        assertNotNull(picked);
        assertEquals("the main service (video + datacast) must win", 3200, picked.serviceId);
        assertTrue("it must have waited for the late PMT, waited " + waited + " ms", waited >= 250);
    }

    @Test public void doesNotWaitWhenNoLowerServiceIsPending() {
        TsDemux d = new TsDemux();
        feed(d, PID_PAT, pat(3224, PID_PMT_3224));
        feed(d, PID_PMT_3224, pmt(3224, new int[][]{{0x1B, PID_V_3224}, {0x11, PID_A_3224}}));

        long t0 = System.currentTimeMillis();
        ServiceInfo picked = ServiceWait.anyPlayable(() -> d, 5_000, () -> false);
        long waited = System.currentTimeMillis() - t0;

        assertNotNull(picked);
        assertEquals(3224, picked.serviceId);
        assertTrue("nothing lower is pending, so no 2 s grace, waited " + waited + " ms", waited < 1_500);
    }

    @Test public void afterTheGraceAForeverMissingPmtDoesNotBlockPlayback() {
        TsDemux d = onesegFirst();   // 3200's PMT never arrives

        long t0 = System.currentTimeMillis();
        ServiceInfo picked = ServiceWait.anyPlayable(() -> d, 5_000, () -> false);
        long waited = System.currentTimeMillis() - t0;

        assertNotNull(picked);
        assertEquals(3224, picked.serviceId);
        assertTrue("waited the grace, then gave up after " + waited + " ms", waited >= 1_900);
    }
}
