package com.pbcnx.opendtvmod.ts;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link EitAccumulator}: synthetic EIT sections (table_id 0x4E) with a real MPEG-2
 * CRC-32, written independently of {@link Crc32Mpeg}, decoded into the same events TsDemux's EPG
 * view reads.
 */
public class EitAccumulatorTest {

    private static final int TS_ID = 5;
    private static final int NETWORK_ID = 1;

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

    private static int bcd(int value) {
        return ((value / 10) << 4) | (value % 10);
    }

    /**
     * One EIT event loop entry: event_id, start_time (MJD + BCD hh mm ss), duration (BCD hh mm ss),
     * running_status = 4 (running) and a short_event_descriptor (0x4D) with an ISO-639 language.
     */
    private static byte[] event(int eventId, int mjd, int hh, int mm, int ss,
            int durH, int durM, int durS, String name, String text) {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream desc = new ByteArrayOutputStream();
        desc.write(0x4D);
        desc.write(5 + n.length + t.length);   // language(3) + name_len(1) + name + text_len(1) + text
        desc.write('e');
        desc.write('n');
        desc.write('g');
        desc.write(n.length);
        desc.write(n, 0, n.length);
        desc.write(t.length);
        desc.write(t, 0, t.length);
        byte[] d = desc.toByteArray();
        int loopLength = d.length;

        ByteArrayOutputStream e = new ByteArrayOutputStream();
        e.write((eventId >> 8) & 0xFF);
        e.write(eventId & 0xFF);
        e.write((mjd >> 8) & 0xFF);
        e.write(mjd & 0xFF);
        e.write(bcd(hh));
        e.write(bcd(mm));
        e.write(bcd(ss));
        e.write(bcd(durH));
        e.write(bcd(durM));
        e.write(bcd(durS));
        e.write(0x80 | ((loopLength >> 8) & 0x0F));   // running_status 4 (running) + loop length high nibble
        e.write(loopLength & 0xFF);
        e.write(d, 0, d.length);
        return e.toByteArray();
    }

    /** An EIT section for one service: 11-byte table header, then the event loop, then the CRC. */
    private static byte[] eit(int serviceId, byte[]... entries) {
        int bodyLen = 0;
        for (byte[] e : entries) bodyLen += e.length;
        int len = 11 + bodyLen + 4;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x4E);
        b.write(0xB0 | ((len >> 8) & 0x0F));
        b.write(len & 0xFF);
        b.write((serviceId >> 8) & 0xFF);
        b.write(serviceId & 0xFF);
        b.write(0xC1);                // version 0, current_next 1
        b.write(0x00);                // section_number
        b.write(0x00);                // last_section_number
        b.write((TS_ID >> 8) & 0xFF);
        b.write(TS_ID & 0xFF);        // transport_stream_id
        b.write((NETWORK_ID >> 8) & 0xFF);
        b.write(NETWORK_ID & 0xFF);   // original_network_id
        b.write(0x00);                // segment_last_section_number
        b.write(0x4E);                // last_table_id
        for (byte[] e : entries) b.write(e, 0, e.length);
        return withCrc(b.toByteArray());
    }

    /** MJD + BCD hh:mm:ss as epoch millis, computed here so the test does not borrow TsUtil. */
    private static long startMs(int mjd, int hh, int mm, int ss) {
        return (mjd - 40587L) * 86400000L + hh * 3600000L + mm * 60000L + ss * 1000L;
    }

    @Test
    public void parsesAnEventAndItsShortEventDescriptor() {
        EitAccumulator eit = new EitAccumulator();
        byte[] s = eit(1, event(0x1234, 60000, 21, 30, 15, 1, 2, 3, "News", "The news at nine"));
        eit.parse(s, s.length);

        List<EpgEvent> events = eit.events(1);
        assertEquals(1, events.size());
        EpgEvent e = events.get(0);
        assertEquals(1, e.serviceId);
        assertEquals(0x1234, e.eventId);
        assertEquals(startMs(60000, 21, 30, 15), e.startMs);
        assertEquals(3723, e.durationSec);
        assertEquals(4, e.runningStatus);
        assertEquals("News", e.name);
        assertEquals("The news at nine", e.text);
        assertTrue("events belong to their service only", eit.events(2).isEmpty());
    }

    @Test
    public void anUndefinedStartTimeDecodesToMinusOne() {
        EitAccumulator eit = new EitAccumulator();
        byte[] s = eit(3, event(9, 0xFFFF, 0, 0, 0, 0, 30, 0, "TBA", ""));
        eit.parse(s, s.length);

        List<EpgEvent> events = eit.events(3);
        assertEquals(1, events.size());
        assertEquals(-1, events.get(0).startMs);
        assertEquals(1800, events.get(0).durationSec);
    }

    @Test
    public void eventsComeBackSortedByStartTime() {
        EitAccumulator eit = new EitAccumulator();
        // deliberately out of order inside the section: 22:00 arrives first, 20:00 second
        byte[] s = eit(1,
                event(2, 60000, 22, 0, 0, 0, 30, 0, "Late", ""),
                event(1, 60000, 20, 0, 0, 0, 30, 0, "Early", ""));
        eit.parse(s, s.length);

        List<EpgEvent> events = eit.events(1);
        assertEquals(2, events.size());
        assertEquals("Early", events.get(0).name);
        assertEquals("Late", events.get(1).name);
    }
}
