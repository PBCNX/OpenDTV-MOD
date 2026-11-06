package ph.pbcnx.it9175;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Byte-exact tests for the IT9175 command framing that actually ships ({@link AfaCmd}).
 *
 * <p>The golden frames below are the layout libpt275aDrv.so expects on bulk OUT 0x02; a wrong
 * byte here means the bridge ignores the command or, worse, writes a register it never meant to.
 * This file used to pin a duplicate copy of the framer under com.pbcnx.opendtvmod.source, which no production
 * code called; the duplicate is gone and these tests now guard the class AfaIo uses.
 */
public class AfaCmdTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    /** Writes the length byte and the checksum: AfaCmd seals its own outgoing frames, tests seal replies. */
    private static void seal(byte[] f) {
        int cs = AfaCmd.checksum(f, f.length - 2);
        f[f.length - 2] = (byte) (cs >> 8);
        f[f.length - 1] = (byte) cs;
        f[0] = (byte) (f.length - 1);
    }

    /** The documented checksum: NOT of the 16-bit big-endian word sum of bytes [1, end). */
    private static int wordSum(byte[] f, int end) {
        int sum = 0;
        for (int i = 1; i < end; i++) {
            int b = f[i] & 0xFF;
            sum += ((i - 1) & 1) == 0 ? (b << 8) : b;
        }
        return sum;
    }

    private static int storedChecksum(byte[] f) {
        return ((f[f.length - 2] & 0xFF) << 8) | (f[f.length - 1] & 0xFF);
    }

    // ---------------------------------------------------------------- golden frames

    @Test
    public void readRegistersFrameIsByteExact() {
        byte[] f = AfaCmd.build(AfaCmd.CMD_READ, 0x07, 1, AfaCmd.PROC_OFDM, 0xF001, 8, null);
        assertArrayEquals(
                "length, chip<<4, cmd, seq, count, addrLen, addr(4 BE), checksum",
                bytes(0x0B, 0x90, 0x00, 0x07, 0x08, 0x02, 0x00, 0x00, 0xF0, 0x01, 0x65, 0x07),
                f);
    }

    @Test
    public void writeRegistersFrameIsByteExact() {
        byte[] f = AfaCmd.build(AfaCmd.CMD_WRITE, 2, 1, AfaCmd.PROC_LINK, 0x40, 3,
                new byte[] {(byte) 0xAA, 0x55, 0x00});
        assertArrayEquals(
                bytes(0x0E, 0x10, 0x01, 0x02, 0x03, 0x01, 0x00, 0x00, 0x00, 0x40,
                        (byte) 0xAA, 0x55, 0x00, 0x57, 0x51),
                f);
    }

    @Test
    public void buildReadPinsTheChipByteToZero() {
        // AfaIo drives a single chip address, so the convenience builders always send chip 0: the
        // high nibble is the processor alone, where build(...) with chip 1 would add 0x10.
        byte[] read = AfaCmd.buildRead(7, AfaCmd.PROC_OFDM, 0xF001, 8);
        byte[] write = AfaCmd.buildWrite(7, AfaCmd.PROC_LINK, 0x40, new byte[] {1});
        assertEquals((AfaCmd.PROC_OFDM & 0x0F) << 4, read[1] & 0xFF);
        assertEquals((AfaCmd.PROC_LINK & 0x0F) << 4, write[1] & 0xFF);
        assertEquals(0x10, (AfaCmd.build(AfaCmd.CMD_READ, 7, 1, 0, 0x00, 1, null)[1] & 0xFF)
                ^ (AfaCmd.buildRead(7, AfaCmd.PROC_LINK, 0x00, 1)[1] & 0xFF));
    }

    @Test
    public void genericCommandFrameIsByteExact() {
        byte[] f = AfaCmd.buildCommand(0x22, 3, 1, AfaCmd.PROC_LINK, new byte[] {1});
        assertArrayEquals(bytes(0x06, 0x10, 0x22, 0x03, 0x01, (byte) 0xEC, (byte) 0xDC), f);
    }

    @Test
    public void genericCommandWithoutPayloadIsByteExact() {
        byte[] f = AfaCmd.buildCommand(0x23, 1, 0, AfaCmd.PROC_OFDM, null);
        assertArrayEquals(bytes(0x05, (byte) 0x80, 0x23, 0x01, 0x7E, (byte) 0xDC), f);
    }

    @Test
    public void responseFrameIsAcceptedAndStrippedToData() {
        byte[] r = bytes(0x07, 0x07, 0x00, 0x12, 0x34, 0x56, 0x00, 0x00);
        seal(r);
        assertArrayEquals("length - 1, seq, status 0, data, checksum",
                bytes(0x07, 0x07, 0x00, 0x12, 0x34, 0x56, (byte) 0x90, (byte) 0xCB), r);
        assertArrayEquals(bytes(0x12, 0x34, 0x56), AfaCmd.parseResponse(r, 3));
    }

    // ---------------------------------------------------------------- length / checksum

    @Test
    public void everyFrameCarriesItsOwnLengthAndChecksum() {
        byte[][] frames = {
                AfaCmd.buildRead(1, AfaCmd.PROC_LINK, 0x10, 1),
                AfaCmd.buildRead(2, AfaCmd.PROC_OFDM, 0x10, 1),
                AfaCmd.buildWrite(3, AfaCmd.PROC_LINK, 0x10, new byte[0]),
                AfaCmd.buildWrite(4, AfaCmd.PROC_OFDM, 0x1234, new byte[] {1, 2, 3}),
                AfaCmd.buildCommand(0x22, 5, 2, AfaCmd.PROC_OFDM, new byte[] {1, 2, 3, 4})
        };
        for (byte[] f : frames) {
            assertEquals("byte 0 is the length minus one", f.length - 1, f[0] & 0xFF);
            int expected = wordSum(f, f.length - 2);
            assertEquals("checksum is the NOT of the 16-bit word sum", (~expected) & 0xFFFF, storedChecksum(f));
            assertEquals("stored checksum must equal the class's own checksum()", AfaCmd.checksum(f, f.length - 2), storedChecksum(f));
            assertNotEquals("the checksum is NOT the sum itself (the vendor driver's bug guard)", expected & 0xFFFF, storedChecksum(f));
        }
    }

    @Test
    public void writeFrameWithNoDataHasCountZero() {
        byte[] f = AfaCmd.buildWrite(3, AfaCmd.PROC_LINK, 0x10, new byte[0]);
        assertEquals(12, f.length);
        assertEquals(0, f[4] & 0xFF);
        assertEquals(1, f[5] & 0xFF);
    }

    @Test
    public void checksumIsNotOfTheSum() {
        byte[] f = AfaCmd.build(AfaCmd.CMD_READ, 0x07, 1, AfaCmd.PROC_OFDM, 0xF001, 8, null);
        int sum = wordSum(f, f.length - 2);
        int checksum = AfaCmd.checksum(f, f.length - 2);
        assertEquals((~sum) & 0xFFFF, checksum);
        assertNotEquals(sum & 0xFFFF, checksum);
        assertNotEquals("deliberately not a plain 16-bit sum", 0, checksum);
    }

    @Test
    public void checksumSkipsByteZeroAndTreatsAnOddTrailingByteAsTheHighByte() {
        // bytes[1..3]: 0x1234 as a word, then 0x56 as the high byte of the last word.
        byte[] f = bytes(0xFF, 0x12, 0x34, 0x56);
        assertEquals(0x1234 + 0x5600, wordSum(f, f.length));
        assertEquals((~0x6834) & 0xFFFF, AfaCmd.checksum(f, f.length));
        assertEquals(0x97CB, AfaCmd.checksum(f, f.length));
        // byte 0 is the length of the frame, never part of the sum
        f[0] = 0x00;
        assertEquals(0x97CB, AfaCmd.checksum(f, f.length));
    }

    @Test
    public void chipAndProcessorArePackedIntoTheHighNibble() {
        byte[] f = AfaCmd.build(AfaCmd.CMD_READ, 1, 0x0F, AfaCmd.PROC_OFDM, 0x10, 1, null);
        assertEquals(((0x0F + 0x08) & 0x0F) << 4, f[1] & 0xFF);
    }

    @Test
    public void addressLengthDependsOnProcessorAndAddressWidth() {
        assertEquals(1, AfaCmd.addressLength(AfaCmd.PROC_LINK, 0xFF));
        assertEquals(2, AfaCmd.addressLength(AfaCmd.PROC_LINK, 0x100));
        assertEquals(2, AfaCmd.addressLength(AfaCmd.PROC_LINK, -1));
        assertEquals(2, AfaCmd.addressLength(AfaCmd.PROC_OFDM, 0x10));
        assertEquals(2, AfaCmd.addressLength(AfaCmd.PROC_OFDM, 0));
    }

    // ---------------------------------------------------------------- response parsing

    @Test
    public void parseResponseRejectsWrongLength() {
        byte[] r = goodResponse(3);
        assertNull("expectData 3 needs len 8", AfaCmd.parseResponse(r, 2));
        assertNull("expectData 4 would need len 9", AfaCmd.parseResponse(r, 4));
        assertNull("too short to be a response", AfaCmd.parseResponse(new byte[0], 0));
        byte[] wrongLengthByte = r.clone();
        wrongLengthByte[0] = 0x08;
        assertNull("byte 0 must be len - 1", AfaCmd.parseResponse(wrongLengthByte, 3));
    }

    @Test
    public void parseResponseRejectsBadChecksum() {
        byte[] r = goodResponse(3);
        r[r.length - 1] ^= 0x01;
        assertNull(AfaCmd.parseResponse(r, 3));
    }

    @Test
    public void parseResponseRejectsNonZeroStatus() {
        byte[] r = bytes(0x07, 0x07, 0x05, 0x12, 0x34, 0x56, 0x00, 0x00);
        seal(r);                       // checksum is valid, only the status is bad
        assertNull(AfaCmd.parseResponse(r, 3));
    }

    @Test
    public void parseResponseAcceptsAZeroLengthRead() {
        byte[] r = bytes(0x04, 0x09, 0x00, 0x00, 0x00);
        seal(r);
        assertArrayEquals(bytes(0x04, 0x09, 0x00, (byte) 0xF6, (byte) 0xFF), r);
        assertEquals(0, AfaCmd.parseResponse(r, 0).length);
    }

    private static byte[] goodResponse(int expectData) {
        byte[] r = new byte[expectData + 5];
        r[1] = 0x07;                             // sequence
        r[2] = 0x00;                             // status OK
        for (int i = 0; i < expectData; i++) r[3 + i] = (byte) (0x12 + i);
        seal(r);
        return r;
    }
}
