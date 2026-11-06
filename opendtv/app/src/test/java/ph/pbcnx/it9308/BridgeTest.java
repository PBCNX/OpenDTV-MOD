package ph.pbcnx.it9308;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ph.pbcnx.Transport;

/** The 9308 bridge frame protocol, without a USB device behind it. */
public class BridgeTest {

    /** Records the frame and answers with a canned reply. */
    private static final class FakeTransport implements Transport {
        byte[] lastWrite;
        byte[] reply;

        @Override
        public void write(byte[] data, int timeoutMs) {
            lastWrite = data;
        }

        @Override
        public byte[] read(int maxLength, int timeoutMs) {
            return reply;
        }
    }

    /** A reply frame with the given status byte and payload. */
    private static byte[] reply(int status, byte... payload) {
        byte[] frame = new byte[payload.length + 5];
        frame[0] = (byte) (frame.length - 1);
        frame[1] = 0;
        frame[2] = (byte) status;
        System.arraycopy(payload, 0, frame, 3, payload.length);
        int sum = Bridge.checksum(frame, frame.length - 2);
        frame[frame.length - 2] = (byte) (sum >> 8);
        frame[frame.length - 1] = (byte) sum;
        return frame;
    }

    @Test
    public void checksumIsTheOnesComplementOfTheWordSum() {
        // words 0x0102 + 0x0300 = 0x0402; one's complement = 0xFBFD
        assertEquals(0xFBFD, Bridge.checksum(new byte[]{0, 1, 2, 3}, 4));
    }

    @Test
    public void buildWritesLengthSequenceCommandAndChecksum() {
        FakeTransport transport = new FakeTransport();
        Bridge bridge = new Bridge(transport);
        byte[] frame = bridge.build(Bridge.INFO, 5, new byte[]{1});
        assertNotNull(frame);
        assertEquals(frame.length - 1, frame[0]);
        assertEquals(0, frame[1]);
        assertEquals(0x22, frame[2] & 0xFF);
        assertEquals(5, frame[3]);
        assertEquals(1, frame[4]);
        assertEquals(Bridge.checksum(frame, frame.length - 2),
                ((frame[frame.length - 2] & 0xFF) << 8) | (frame[frame.length - 1] & 0xFF));
    }

    @Test
    public void aShortReplyIsRefusedWithItsReason() {
        FakeTransport transport = new FakeTransport();
        transport.reply = new byte[]{1};
        Bridge bridge = new Bridge(transport);
        assertNull(bridge.exchange(Bridge.INFO, new byte[]{1}, 0));
        assertTrue(bridge.lastError().contains("length"));
    }

    @Test
    public void anAcceptedReplyReturnsItsPayload() {
        FakeTransport transport = new FakeTransport();
        transport.reply = reply(0, (byte) 7);
        Bridge bridge = new Bridge(transport);
        assertArrayEquals(new byte[]{7}, bridge.exchange(Bridge.INFO, new byte[]{1}, 1));
        assertNull(bridge.lastError());
    }

    @Test
    public void aRefusedStatusKeepsTheBridgeReason() {
        FakeTransport transport = new FakeTransport();
        transport.reply = reply(3);
        Bridge bridge = new Bridge(transport);
        assertNull(bridge.exchange(Bridge.INFO, new byte[]{1}, 0));
        assertTrue(bridge.lastError().contains("status"));
    }
}
