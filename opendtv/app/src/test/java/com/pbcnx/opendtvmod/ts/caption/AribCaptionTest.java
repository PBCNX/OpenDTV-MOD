package com.pbcnx.opendtvmod.ts.caption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Tests for the pure part of {@link AribCaption}: one caption PES in, one decoded statement out.
 * No Android, no player, no clock - the input bytes are the whole story.
 */
public class AribCaptionTest {

    private final AribCaption decoder = new AribCaption();

    private static final int STREAM_PRIVATE_1 = 0xBD;   // PES private_stream_1: normal caption
    private static final int STREAM_PRIVATE_2 = 0xBF;   // EWBS superimposed caption: payload at byte 6

    /**
     * Builds one caption PES around a single statement data unit.
     *
     * @param group data_group_id: 1 = first language's statement, 2 = second language's
     */
    private static byte[] pes(int streamId, int group, String line) {
        byte[] body = line.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x80);                            // data_identifier: caption
        payload.write(0xFF);                            // private_stream_id
        payload.write(0x00);                            // PES_data_packet_header_length
        payload.write((group & 0x1F) << 2);             // data_group_id
        payload.write(0x00);
        payload.write(0x00);
        int dataSize = 1 + 3 + 5 + body.length;         // TMD + loop length + one data unit
        payload.write((dataSize >> 8) & 0xFF);
        payload.write(dataSize & 0xFF);
        payload.write(0x00);                            // TMD = 0: no time field
        int loop = 5 + body.length;
        payload.write((loop >> 16) & 0xFF);
        payload.write((loop >> 8) & 0xFF);
        payload.write(loop & 0xFF);
        payload.write(0x1F);                            // data unit: statement body
        payload.write(0x20);
        payload.write((body.length >> 16) & 0xFF);
        payload.write((body.length >> 8) & 0xFF);
        payload.write(body.length & 0xFF);
        payload.write(body, 0, body.length);
        byte[] payloadBytes = payload.toByteArray();

        boolean private2 = streamId == STREAM_PRIVATE_2;
        int header = private2 ? 6 : 9;
        byte[] out = new byte[header + payloadBytes.length];
        out[0] = 0;
        out[1] = 0;
        out[2] = 1;
        out[3] = (byte) streamId;
        int pesLength = payloadBytes.length + (private2 ? 0 : 3);
        out[4] = (byte) (pesLength >> 8);
        out[5] = (byte) pesLength;
        if (!private2) {
            out[6] = (byte) 0x80;                       // '10' marker + no scrambling
            out[7] = 0x00;
            out[8] = 0x00;                              // PES_header_data_length = 0
        }
        System.arraycopy(payloadBytes, 0, out, header, payloadBytes.length);
        return out;
    }

    private static byte[] pes(String line) {
        return pes(STREAM_PRIVATE_1, 1, line);
    }

    @Test
    public void decodesASingleLatinStatement() {
        byte[] caption = pes("Hello OpenDTV");
        AribCaption.Result r = decoder.decodePes(caption, caption.length);
        assertNotNull(r);
        assertTrue(r.statement);
        assertEquals("Hello OpenDTV", r.text);
        assertNull(r.language);
    }

    @Test
    public void aBlankStatementIsAStatementWithEmptyText() {
        byte[] caption = pes("     ");
        AribCaption.Result r = decoder.decodePes(caption, caption.length);
        assertNotNull(r);
        assertTrue("still a statement, i.e. clear the caption", r.statement);
        assertEquals("", r.text);
    }

    @Test
    public void aLineBreakControlBecomesOneNewline() {
        byte[] caption = pes("Hello\rWorld");
        AribCaption.Result r = decoder.decodePes(caption, caption.length);
        assertNotNull(r);
        assertTrue(r.statement);
        assertEquals("Hello\nWorld", r.text);
    }

    @Test
    public void theSecondLanguageStatementIsOnlyDecodedWhenRequested() {
        byte[] caption = pes(STREAM_PRIVATE_1, 2, "Second language");
        AribCaption.Result first = decoder.decodePes(caption, caption.length);
        assertNotNull(first);
        assertFalse("statement of language 2 is not language 1", first.statement);
        assertEquals("", first.text);

        AribCaption.Result second = decoder.decodePes(caption, caption.length, 2);
        assertNotNull(second);
        assertTrue(second.statement);
        assertEquals("Second language", second.text);
    }

    @Test
    public void anEwbsSuperimposedPesStartsWithThePayloadAtByteSix() {
        byte[] caption = pes(STREAM_PRIVATE_2, 1, "EWBS TEXT");
        AribCaption.Result r = decoder.decodePes(caption, caption.length);
        assertNotNull(r);
        assertTrue(r.statement);
        assertEquals("EWBS TEXT", r.text);
    }

    @Test
    public void malformedInputReturnsNullInsteadOfThrowing() {
        assertNull(decoder.decodePes(null, 0));
        assertNull(decoder.decodePes(new byte[] {0, 0, 1}, 3));
        assertNull("valid PES header, no caption payload",
                decoder.decodePes(new byte[] {0, 0, 1, (byte) 0xBD, 0, 0, (byte) 0x80, 0, 0}, 9));

        byte[] good = pes("Hello");
        assertNull("too short for a PES header", decoder.decodePes(good, 5));
        assertNull("cut inside the data group header", decoder.decodePes(good, 12));

        byte[] badStartCode = good.clone();
        badStartCode[0] = 0x01;
        assertNull(badStartCode.length + " bytes with a bad start code",
                decoder.decodePes(badStartCode, badStartCode.length));

        byte[] badDataIdentifier = good.clone();
        badDataIdentifier[9] = 0x7F;
        assertNull(decoder.decodePes(badDataIdentifier, badDataIdentifier.length));

        byte[] badPrivateStreamId = good.clone();
        badPrivateStreamId[10] = 0x00;
        assertNull(decoder.decodePes(badPrivateStreamId, badPrivateStreamId.length));
    }
}
