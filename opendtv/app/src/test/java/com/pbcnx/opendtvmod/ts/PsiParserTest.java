package com.pbcnx.opendtvmod.ts;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link PsiParser}: the PAT/PMT/SDT loops against synthetic tables with a real MPEG-2
 * CRC-32, written independently of {@link Crc32Mpeg}. The parser is driven straight into a fresh
 * TsDemux, so the tables it fills are the same ones the demux's query API reads.
 */
public class PsiParserTest {

    private static final int PID_PMT = 0x100;
    private static final int PID_VIDEO = 0x201;

    /** CRC-32/MPEG-2, written independently of Crc32Mpeg so the test cannot agree with a bug. */
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

    /** One 188-byte packet: sync, PID, payload-only AFC with a continuity counter, pointer_field 0. */
    private static byte[] packet(int pid, int cc, byte[] payload) {
        byte[] p = new byte[188];
        Arrays.fill(p, (byte) 0xFF);
        p[0] = 0x47;
        p[1] = (byte) (0x40 | ((pid >> 8) & 0x1F));
        p[2] = (byte) pid;
        p[3] = (byte) (0x10 | (cc & 0x0F));
        p[4] = 0x00;
        System.arraycopy(payload, 0, p, 5, payload.length);
        return p;
    }

    /** Collects the live packets a tap installed by attachTapWithHistory sees. */
    private static final class RecordingSink implements PacketSink {
        final List<byte[]> packets = new ArrayList<>();

        @Override
        public void onPacket(byte[] buf, int off) {
            byte[] copy = new byte[188];
            System.arraycopy(buf, off, copy, 0, 188);
            packets.add(copy);
        }

        @Override
        public void flush() {
            // the test feed has no buffered tail to flush
        }
    }

    /** PAT with one program: program_number -> PMT PID. */
    private static byte[] pat(int tsId, int program, int pmtPid) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x00);
        int len = 5 + 4 + 4;
        b.write(0xB0 | ((len >> 8) & 0x0F));
        b.write(len & 0xFF);
        b.write((tsId >> 8) & 0xFF);
        b.write(tsId & 0xFF);
        b.write(0xC1);                                // version 0, current_next 1
        b.write(0x00);
        b.write(0x00);
        b.write((program >> 8) & 0xFF);
        b.write(program & 0xFF);
        b.write(0xE0 | ((pmtPid >> 8) & 0x1F));
        b.write(pmtPid & 0xFF);
        return withCrc(b.toByteArray());
    }

    /** PMT for one service with one elementary stream and no descriptors. */
    private static byte[] pmt(int sid, int streamType, int streamPid) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x02);
        int len = 9 + 5 + 4;
        b.write(0xB0 | ((len >> 8) & 0x0F));
        b.write(len & 0xFF);
        b.write((sid >> 8) & 0xFF);
        b.write(sid & 0xFF);
        b.write(0xC1);
        b.write(0x00);
        b.write(0x00);
        b.write(0xE0 | ((streamPid >> 8) & 0x1F));    // PCR_PID
        b.write(streamPid & 0xFF);
        b.write(0xF0);                                // program_info_length = 0
        b.write(0x00);
        b.write(streamType);
        b.write(0xE0 | ((streamPid >> 8) & 0x1F));
        b.write(streamPid & 0xFF);
        b.write(0xF0);                                // ES_info_length = 0
        b.write(0x00);
        return withCrc(b.toByteArray());
    }

    /** SDT with one service: service_descriptor carries service_type, provider and name. */
    private static byte[] sdt(int tsId, int sid, int serviceType, String provider, String name) {
        byte[] p = provider.getBytes(StandardCharsets.ISO_8859_1);
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        int descLen = 1 + 1 + p.length + 1 + n.length;   // service_type, provider, name
        ByteArrayOutputStream loop = new ByteArrayOutputStream();
        loop.write((sid >> 8) & 0xFF);
        loop.write(sid & 0xFF);
        loop.write(0xFC);                                 // reserved(6) + EIT flags(2)
        loop.write(0x80 | (((descLen + 2) >> 8) & 0x0F)); // running_status=4, CA=0, length high nibble
        loop.write((descLen + 2) & 0xFF);                 // descriptors_loop_length low byte
        loop.write(0x48);                                 // service_descriptor
        loop.write(descLen);
        loop.write(serviceType);
        loop.write(p.length);
        loop.write(p, 0, p.length);
        loop.write(n.length);
        loop.write(n, 0, n.length);
        byte[] services = loop.toByteArray();

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x42);
        int len = 8 + services.length + 4;
        b.write(0xB0 | ((len >> 8) & 0x0F));
        b.write(len & 0xFF);
        b.write((tsId >> 8) & 0xFF);
        b.write(tsId & 0xFF);
        b.write(0xC1);
        b.write(0x00);
        b.write(0x00);
        b.write(0x00);
        b.write(0x01);                                    // original_network_id
        b.write(0xFF);                                    // reserved
        b.write(services, 0, services.length);
        return withCrc(b.toByteArray());
    }

    private static ServiceInfo service(TsDemux demux, int sid) {
        for (ServiceInfo si : demux.getServices()) if (si.serviceId == sid) return si;
        return null;
    }

    @Test
    public void patFillsTheServiceTableAndRegistersThePmtPid() {
        TsDemux demux = new TsDemux();
        PsiParser psi = new PsiParser(demux);
        byte[] s = pat(1, 1, PID_PMT);
        psi.parsePat(s, s.length);

        assertEquals(1, demux.getTsId());
        ServiceInfo one = service(demux, 1);
        assertNotNull("the PAT program must create the service", one);
        assertEquals(PID_PMT, one.pmtPid);
        assertEquals(1, demux.getServices().size());
        assertTrue("the PMT PID must get a section assembler", demux.sections.hasAssembler(PID_PMT));

        // ... and a packet ring, observable through the history the demux hands to a late tap
        byte[] before = packet(PID_PMT, 0, new byte[0]);
        demux.feed(before, 0, before.length);
        boolean[] want = new boolean[0x2000];
        want[PID_PMT] = true;
        RecordingSink tap = new RecordingSink();
        byte[] history = demux.attachTapWithHistory(tap, want);

        assertEquals("the PMT PID's packet must be in the history", 188, history.length);
        assertEquals(0x47, history[0] & 0xFF);
        assertTrue("nothing is live yet", tap.packets.isEmpty());

        byte[] after = packet(PID_PMT, 1, new byte[0]);
        demux.feed(after, 0, after.length);
        assertEquals("packets after attach go to the tap", 1, tap.packets.size());
    }

    @Test
    public void aPmtForAnotherServiceIdIsIgnored() {
        TsDemux demux = new TsDemux();
        PsiParser psi = new PsiParser(demux);
        byte[] pat = pat(1, 1, PID_PMT);
        psi.parsePat(pat, pat.length);

        byte[] foreign = pmt(2, 0x0F, 0x302);
        psi.parsePmt(PID_PMT, foreign, foreign.length);

        ServiceInfo one = service(demux, 1);
        assertFalse("a PMT for service 2 must not mark service 1 as seen", one.pmtSeen);
        assertEquals(-1, one.pcrPid);
        assertTrue(one.streams.isEmpty());
        assertEquals("the ignored PMT must not invent a service", 1, demux.getServices().size());

        byte[] own = pmt(1, 0x02, PID_VIDEO);
        psi.parsePmt(PID_PMT, own, own.length);

        one = service(demux, 1);
        assertTrue(one.pmtSeen);
        assertEquals(PID_VIDEO, one.pcrPid);
        assertEquals(1, one.streams.size());
        assertEquals(PID_VIDEO, one.streams.get(0).pid);
        assertEquals(0x02, one.streams.get(0).streamType);
    }

    @Test
    public void sdtNamesTheServiceAndItsProvider() {
        TsDemux demux = new TsDemux();
        PsiParser psi = new PsiParser(demux);
        byte[] s = sdt(1, 7, 0x01, "PBC", "Test One");
        psi.parseSdt(s, s.length);

        assertEquals(1, demux.getTsId());
        ServiceInfo seven = service(demux, 7);
        assertNotNull(seven);
        assertEquals("Test One", seven.name);
        assertEquals("PBC", seven.provider);
        assertEquals(0x01, seven.serviceType);
    }
}
