package ph.pbcnx.it9308;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** The pure part of the 9308 mode-2 sequence: frequency patching and resealing. */
public class SequenceTest {

    private static byte[] frameWithFrequency() {
        byte[] frame = new byte[32];
        frame[0] = (byte) (frame.length - 1);
        frame[1] = 0x09;
        frame[2] = 0x24;
        frame[3] = 0x67;   // CAPTURED_FREQUENCY at offset 1
        int sum = Bridge.checksum(frame, frame.length - 2);
        frame[frame.length - 2] = (byte) (sum >> 8);
        frame[frame.length - 1] = (byte) sum;
        return frame;
    }

    @Test
    public void patchedReplacesTheCapturedFrequencyAndReseals() {
        byte[] original = frameWithFrequency();
        List<Sequence.Step> steps = List.of(new Sequence.Step(0, original, 0));
        List<Sequence.Step> patched = Sequence.patched(steps, 0x059A63);   // 367,203 kHz
        byte[] frame = patched.get(0).frame;
        assertArrayEquals(new byte[]{0x05, (byte) 0x9A, 0x63},
                new byte[]{frame[1], frame[2], frame[3]});
        int sum = Bridge.checksum(frame, frame.length - 2);
        assertEquals(sum, ((frame[frame.length - 2] & 0xFF) << 8) | (frame[frame.length - 1] & 0xFF));
        assertFalse("the original frame is not mutated", original == frame);
    }

    @Test
    public void patchedLeavesFramesWithoutTheFrequencyAlone() {
        byte[] frame = new byte[]{5, 1, 2, 3, 0, 0};
        int sum = Bridge.checksum(frame, frame.length - 2);
        frame[frame.length - 2] = (byte) (sum >> 8);
        frame[frame.length - 1] = (byte) sum;
        List<Sequence.Step> steps = List.of(new Sequence.Step(10, frame, 1));
        List<Sequence.Step> patched = Sequence.patched(steps, 0x059A63);
        assertArrayEquals(frame, patched.get(0).frame);
        assertEquals(10, patched.get(0).delayMs);
        assertEquals(1, patched.get(0).reply);
    }

    @Test
    public void patchedKeepsNullFrames() {
        List<Sequence.Step> steps = List.of(new Sequence.Step(5, null, 2));
        List<Sequence.Step> patched = Sequence.patched(steps, 0x059A63);
        assertEquals(null, patched.get(0).frame);
        assertEquals(5, patched.get(0).delayMs);
    }

    @Test
    public void frequencyOutOfRangeIsRejected() {
        List<Sequence.Step> steps = List.of(new Sequence.Step(0, frameWithFrequency(), 0));
        assertThrows(IllegalArgumentException.class, () -> Sequence.patched(steps, 0));
        assertThrows(IllegalArgumentException.class, () -> Sequence.patched(steps, 0x1000000));
    }

    @Test
    public void theCapturedFrequencyIsLeftUnchanged() {
        byte[] frame = frameWithFrequency();
        List<Sequence.Step> steps = List.of(new Sequence.Step(0, frame, 0));
        List<Sequence.Step> patched = Sequence.patched(steps, 0x092467);
        assertTrue(patched.get(0).frame == frame);
    }
}
