package com.pbcnx.opendtvmod.ts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link TsDemux} against synthetic MPEG-TS packets: every packet is built here with a
 * real sync byte, PID, continuity counter and MPEG-2 CRC-32, so a PSI table that the demux would
 * reject on a device is rejected here too.
 */
public class TsDemuxTest {

    private static final int PID_PAT = 0x0000;
    private static final int PID_SDT = 0x0011;
    private static final int PID_PMT1 = 0x100;
    private static final int PID_PMT2 = 0x101;
    private static final int PID_VIDEO = 0x201;
    private static final int PID_AUDIO = 0x302;

    // ---------------------------------------------------------------- synthetic stream

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    /** One 188-byte packet: sync, PID, payload-only AFC with a continuity counter, then a section. */
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

    /** PAT: two programs, service 1 -> PMT 0x100, service 2 -> PMT 0x101, TS id 1. */
    private static byte[] pat() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x00);
        int len = 5 + 8 + 4;
        b.write(0xB0 | (len >> 8));
        b.write(len & 0xFF);
        b.write(0x00);
        b.write(0x01);                                // transport_stream_id = 1
        b.write(0xC1);                                // version 0, current_next 1
        b.write(0x00);
        b.write(0x00);
        b.write(0x00);
        b.write(0x01);
        b.write(0xE0 | ((PID_PMT1 >> 8) & 0x1F));
        b.write(PID_PMT1 & 0xFF);
        b.write(0x00);
        b.write(0x02);
        b.write(0xE0 | ((PID_PMT2 >> 8) & 0x1F));
        b.write(PID_PMT2 & 0xFF);
        return withCrc(b.toByteArray());
    }

    /** PMT for one service with one elementary stream and no descriptors. */
    private static byte[] pmt(int sid, int streamType, int streamPid) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x02);
        int len = 9 + 5 + 4;
        b.write(0xB0 | (len >> 8));
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

    /** SDT: service 1 "Test One" and service 2 "Second", both digital TV (service_type 0x01). */
    private static byte[] sdt() {
        ByteArrayOutputStream loop = new ByteArrayOutputStream();
        appendService(loop, 1, "PBC", "Test One");
        appendService(loop, 2, "XYZ", "Second");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x42);
        int len = 8 + loop.size() + 4;
        b.write(0xB0 | (len >> 8));
        b.write(len & 0xFF);
        b.write(0x00);
        b.write(0x01);                                // transport_stream_id = 1
        b.write(0xC1);
        b.write(0x00);
        b.write(0x00);
        b.write(0x00);
        b.write(0x01);                                // original_network_id
        b.write(0xFF);                                // reserved
        byte[] loopBytes = loop.toByteArray();
        b.write(loopBytes, 0, loopBytes.length);
        return withCrc(b.toByteArray());
    }

    private static void appendService(ByteArrayOutputStream loop, int sid, String provider, String name) {
        byte[] p = provider.getBytes(StandardCharsets.ISO_8859_1);
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        int descLen = 1 + 1 + p.length + 1 + n.length;    // service_type, provider, name
        loop.write((sid >> 8) & 0xFF);
        loop.write(sid & 0xFF);
        loop.write(0xFC);                                 // reserved(6) + EIT flags(2)
        loop.write(0x80 | (((descLen + 2) >> 8) & 0x0F)); // running_status=4, CA=0, length high nibble
        loop.write((descLen + 2) & 0xFF);                 // descriptors_loop_length low byte
        loop.write(0x48);                                 // service_descriptor
        loop.write(descLen);
        loop.write(0x01);                                 // service_type: digital TV
        loop.write(p.length);
        loop.write(p, 0, p.length);
        loop.write(n.length);
        loop.write(n, 0, n.length);
    }

    private static ServiceInfo service(List<ServiceInfo> services, int sid) {
        for (ServiceInfo si : services) if (si.serviceId == sid) return si;
        fail("service " + sid + " was not found");
        return null;
    }

    // ---------------------------------------------------------------- tests

    @Test
    public void parsesPatPmtAndSdt() {
        TsDemux demux = new TsDemux();
        byte[] stream = concat(
                packet(PID_PAT, 0, pat()),
                packet(PID_PMT1, 0, pmt(1, 0x02, PID_VIDEO)),
                packet(PID_SDT, 0, sdt()));
        demux.feed(stream, 0, stream.length);

        assertEquals(3, demux.getPacketCount());
        assertEquals(1, demux.getTsId());

        List<ServiceInfo> services = demux.getServices();
        assertEquals(2, services.size());

        ServiceInfo one = service(services, 1);
        assertEquals("Test One", one.name);
        assertEquals("PBC", one.provider);
        assertEquals(0x01, one.serviceType);
        assertEquals(PID_PMT1, one.pmtPid);
        assertEquals(PID_VIDEO, one.pcrPid);
        assertTrue(one.pmtSeen);
        assertEquals(1, one.streams.size());
        StreamInfo video = one.streams.get(0);
        assertEquals(PID_VIDEO, video.pid);
        assertEquals(0x02, video.streamType);
        assertTrue(video.isVideo());
        assertSame(video, one.firstVideo());
        assertNull(one.firstAudio());

        ServiceInfo two = service(services, 2);
        assertEquals("Second", two.name);
        assertEquals(0x01, two.serviceType);
        assertEquals(PID_PMT2, two.pmtPid);
        assertFalse("no PMT was fed for service 2", two.pmtSeen);
        assertTrue(two.streams.isEmpty());
    }

    @Test
    public void aSecondServicesPmtIsPickedUp() {
        TsDemux demux = new TsDemux();
        byte[] stream = concat(
                packet(PID_PAT, 0, pat()),
                packet(PID_SDT, 0, sdt()),
                packet(PID_PMT2, 0, pmt(2, 0x0F, PID_AUDIO)));
        demux.feed(stream, 0, stream.length);

        assertEquals(3, demux.getPacketCount());
        ServiceInfo two = service(demux.getServices(), 2);
        assertEquals("Second", two.name);
        assertTrue(two.pmtSeen);
        assertEquals(PID_AUDIO, two.pcrPid);
        assertNotNull(two.firstAudio());
        assertEquals(PID_AUDIO, two.firstAudio().pid);
        assertEquals(0x0F, two.firstAudio().streamType);
    }

    @Test
    public void aSectionWithABadCrcIsIgnoredButThePacketIsCounted() {
        byte[] corrupt = pat();
        corrupt[corrupt.length - 1] ^= 0xFF;              // only the CRC changes
        TsDemux demux = new TsDemux();
        byte[] stream = packet(PID_PAT, 0, corrupt);
        demux.feed(stream, 0, stream.length);

        assertEquals(1, demux.getPacketCount());
        assertEquals(-1, demux.getTsId());
        assertTrue(demux.getServices().isEmpty());
    }

    @Test
    public void feedingTheSameStreamInArbitraryChunksGivesTheSameTables() {
        TsDemux demux = new TsDemux();
        byte[] stream = concat(
                packet(PID_PAT, 0, pat()),
                packet(PID_PMT1, 0, pmt(1, 0x02, PID_VIDEO)),
                packet(PID_SDT, 0, sdt()));
        demux.feed(stream, 0, 100);                       // less than one packet: all carried over
        demux.feed(stream, 100, stream.length - 100);

        assertEquals(3, demux.getPacketCount());
        assertEquals(1, demux.getTsId());
        assertEquals(2, demux.getServices().size());
        assertEquals("Test One", service(demux.getServices(), 1).name);
    }


    @Test
    public void multipleTapSinksAllReceiveTheSamePackets() {
        TsDemux demux = new TsDemux();
        final int[] first = new int[1];
        final int[] second = new int[1];
        PacketSink a = new PacketSink() {
            @Override public void onPacket(byte[] buf, int off) { first[0]++; }
            @Override public void flush() { /* no-op: test feed */ }
        };
        PacketSink b = new PacketSink() {
            @Override public void onPacket(byte[] buf, int off) { second[0]++; }
            @Override public void flush() { /* no-op: test feed */ }
        };
        demux.addTapSink(a);
        demux.addTapSink(b);
        byte[] pkt = packet(PID_VIDEO, 0, new byte[] {0x00, 0x00, 0x01, (byte) 0xE0});
        demux.feed(pkt, 0, pkt.length);
        assertEquals(1, first[0]);
        assertEquals(1, second[0]);

        // Removing one consumer leaves the other one working: BML closing must not stop a recording.
        demux.removeTapSink(a);
        byte[] next = packet(PID_VIDEO, 1, new byte[] {0x00, 0x00, 0x01, (byte) 0xE0});
        demux.feed(next, 0, next.length);
        assertEquals(1, first[0]);
        assertEquals(2, second[0]);
    }

    // ---------------------------------------------------------------- packet health paths

    /** Raw packet with explicit TEI/AFC/adaptation; payload follows the adaptation field. */
    private static byte[] raw(int pid, int cc, boolean tei, int afc, boolean discontinuity, byte[] payload) {
        byte[] p = new byte[188];
        java.util.Arrays.fill(p, (byte) 0xFF);
        p[0] = 0x47;
        p[1] = (byte) ((tei ? 0x80 : 0x00) | ((pid >> 8) & 0x1F));
        p[2] = (byte) pid;
        p[3] = (byte) ((afc << 4) | (cc & 0x0F));
        int o = 4;
        if ((afc & 2) != 0) {
            p[o++] = 1;   // adaptation_field_length
            p[o++] = (byte) (discontinuity ? 0x80 : 0x00);
        }
        if (payload != null) System.arraycopy(payload, 0, p, o, Math.min(payload.length, p.length - o));
        return p;
    }

    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i + 1);
        return b;
    }

    @Test public void teiPacketsAreDropped() {
        TsDemux d = new TsDemux();
        d.feed(raw(PID_VIDEO, 0, true, 1, false, payload(184)), 0, 188);
        AnalyzerSnapshot s = d.sample();
        assertEquals(1, s.teiPackets);
        assertEquals(1, s.totalPackets);
    }

    @Test public void nullPidIsCountedButNotDelivered() {
        final int[] packets = {0};
        PacketSink sink = new PacketSink() {
            @Override public void onPacket(byte[] b, int o) { packets[0]++; }
            @Override public void flush() { /* no-op: test feed */ }
        };
        TsDemux d = new TsDemux();
        d.setPacketSink(sink);
        d.feed(raw(0x1FFF, 0, false, 1, false, payload(184)), 0, 188);
        AnalyzerSnapshot s = d.sample();
        assertEquals(1, s.nullPackets);
        assertEquals(0, packets[0]);
    }

    @Test public void discontinuityFlagExemptsContinuityCheck() {
        TsDemux d = new TsDemux();
        d.feed(raw(0x0123, 0, false, 3, false, payload(178)), 0, 188);
        d.feed(raw(0x0123, 5, false, 3, true, payload(178)), 0, 188);
        assertEquals(0, d.sample().ccErrors);
    }

    @Test public void continuityGapWithoutDiscontinuityIsCounted() {
        TsDemux d = new TsDemux();
        d.feed(raw(0x0124, 0, false, 3, false, payload(178)), 0, 188);
        d.feed(raw(0x0124, 5, false, 3, false, payload(178)), 0, 188);
        assertTrue(d.sample().ccErrors >= 1);
    }

    @Test public void garbageBeforeAValidPacketIsResynced() {
        TsDemux d = new TsDemux();
        byte[] garbage = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] body = concat(garbage, packet(PID_PAT, 0, pat()));
        d.feed(body, 0, body.length);
        AnalyzerSnapshot s = d.sample();
        assertTrue(s.syncLosses >= 1);
        assertEquals(1, s.totalPackets);
    }

    // ---------------------------------------------------------------- EWBS event messages (jpdcs#12)

    /**
     * A DSM-CC stream-event section (table_id 0x3D) with one general event message descriptor (0x40):
     * 11 fixed bytes (group, time_mode, the 5-byte time, event_msg_type, event_msg_id), then the
     * private data that carries the BML EWBS flag.
     */
    private static byte[] streamEvent(byte[] fixed11, byte[] priv) {
        int dl = fixed11.length + priv.length;
        int len = 5 + 2 + dl + 4;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x3D);
        b.write(0xB0 | (len >> 8));
        b.write(len & 0xFF);
        b.write(0x00);
        b.write(0x00);                                // table_id_extension
        b.write(0xC1);                                // version 0, current_next 1
        b.write(0x00);
        b.write(0x00);                                // section_number, last_section_number
        b.write(0x40);                                // general event message descriptor
        b.write(dl);
        b.write(fixed11, 0, fixed11.length);
        b.write(priv, 0, priv.length);
        return withCrc(b.toByteArray());
    }

    /** group 0, time_mode 0, a zero 5-byte time, event_msg_type 0, then the two event_msg_id bytes. */
    private static byte[] eventFixed(int eventMsgId) {
        return new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                (byte) ((eventMsgId >> 8) & 0xFF), (byte) (eventMsgId & 0xFF)};
    }

    @Test public void routineEventMessageIsNotAnEwbsStart() {
        // event_msg_id 0x0031 ends in the ASCII byte '1'. The old scan started at the descriptor
        // payload, so it read that id as the private data "1" and reported an EWBS start on an
        // ordinary channel. Here the private data is empty: nothing was signalled.
        EwbsMonitor ewbs = new EwbsMonitor(new TsDemux());
        byte[] s = streamEvent(eventFixed(0x0031), new byte[0]);
        ewbs.parseStreamEvent(0x0300, s, s.length);
        assertFalse(ewbs.isEwbsActive());
        assertFalse(ewbs.ewbsEverSeen());
    }

    @Test public void eventMessageWhosePrivateDataEndsInOneIsAnEwbsStart() {
        EwbsMonitor ewbs = new EwbsMonitor(new TsDemux());
        byte[] s = streamEvent(eventFixed(0x0030), new byte[]{0x31});   // private data "1"
        ewbs.parseStreamEvent(0x0300, s, s.length);
        assertTrue(ewbs.isEwbsActive());
        assertTrue(ewbs.ewbsEverSeen());
    }

    @Test public void anotherPidsEndDoesNotEndThisPidsAlert() {
        EwbsMonitor ewbs = new EwbsMonitor(new TsDemux());
        byte[] start = streamEvent(eventFixed(0x0030), new byte[]{0x31});
        ewbs.parseStreamEvent(0x0300, start, start.length);
        assertTrue(ewbs.isEwbsActive());
        // A second PID's routine "end" must not end an alert the first PID started.
        byte[] end = streamEvent(eventFixed(0x0030), new byte[]{0x32});
        ewbs.parseStreamEvent(0x0301, end, end.length);
        assertTrue(ewbs.isEwbsActive());
    }
}

