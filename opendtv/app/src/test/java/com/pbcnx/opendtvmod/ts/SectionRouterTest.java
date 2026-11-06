package com.pbcnx.opendtvmod.ts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link SectionRouter}: the per-PID reassembly stage between TsDemux's packet loop and
 * the table parsers.
 *
 * <p>Every section is built here with a real MPEG-2 CRC-32 written independently of
 * {@link Crc32Mpeg}, because {@link SectionAssembler} rejects a long-form section whose CRC does not
 * check out - and a test that shares the production CRC could agree with a bug in it.
 */
public class SectionRouterTest {

    private static final int PID = 0x100;
    private static final byte[] BODY = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'};

    /** Records what the router hands to the assembler's sink, copying the shared buffer. */
    private static final class Collect implements SectionAssembler.Sink {
        final List<Integer> pids = new ArrayList<>();
        final List<byte[]> sections = new ArrayList<>();

        @Override
        public void onSection(int pid, byte[] section, int length) {
            pids.add(pid);
            byte[] copy = new byte[length];
            System.arraycopy(section, 0, copy, 0, length);
            sections.add(copy);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
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

    /** A long-form section (section_syntax_indicator = 1) with a valid MPEG-2 CRC-32. */
    private static byte[] section(int tableId, byte[] body) {
        int len = body.length + 4;
        byte[] head = {(byte) tableId, (byte) (0xB0 | ((len >> 8) & 0x0F)), (byte) len};
        return withCrc(concat(head, body));
    }

    /** The payload of a packet that starts a section: pointer_field 0, then its first n bytes. */
    private static byte[] start(byte[] section, int n) {
        byte[] payload = new byte[n + 1];
        System.arraycopy(section, 0, payload, 1, n);
        return payload;
    }

    private static byte[] tail(byte[] section, int from) {
        byte[] rest = new byte[section.length - from];
        System.arraycopy(section, from, rest, 0, rest.length);
        return rest;
    }

    @Test
    public void reassemblesASectionSplitAcrossTwoPackets() {
        Collect out = new Collect();
        SectionRouter router = new SectionRouter(out);
        router.ensureAssembler(PID);
        byte[] whole = section(0x42, BODY);

        byte[] first = start(whole, 5);
        router.push(PID, first, 0, first.length, true);
        assertTrue("the section is still incomplete", out.sections.isEmpty());

        byte[] rest = tail(whole, 5);
        router.push(PID, rest, 0, rest.length, false);

        assertEquals(1, out.sections.size());
        assertEquals(PID, out.pids.get(0).intValue());
        assertArrayEquals(whole, out.sections.get(0));
    }

    @Test
    public void aNewPusiDiscardsThePartialSectionAndDeliversTheNewOne() {
        Collect out = new Collect();
        SectionRouter router = new SectionRouter(out);
        router.ensureAssembler(PID);
        byte[] abandoned = section(0x42, BODY);
        byte[] complete = section(0x70, new byte[] {'Z'});

        byte[] partial = start(abandoned, 6);
        router.push(PID, partial, 0, partial.length, true);

        byte[] fresh = start(complete, complete.length);
        router.push(PID, fresh, 0, fresh.length, true);

        assertEquals("only the second, complete section is delivered", 1, out.sections.size());
        assertArrayEquals(complete, out.sections.get(0));
    }

    @Test
    public void putReplacesTheReassemblerAndDropsItsPartialSection() {
        Collect out = new Collect();
        SectionRouter router = new SectionRouter(out);
        router.ensureAssembler(PID);
        byte[] whole = section(0x42, BODY);

        byte[] first = start(whole, 6);
        router.push(PID, first, 0, first.length, true);

        router.replaceAssembler(PID);

        byte[] rest = tail(whole, 6);
        router.push(PID, rest, 0, rest.length, false);

        assertTrue("the half section from the old assembler must be gone", out.sections.isEmpty());
    }

    @Test
    public void ensureIsIdempotentAndKeepsAnInProgressSection() {
        Collect out = new Collect();
        SectionRouter router = new SectionRouter(out);
        router.ensureAssembler(PID);
        byte[] whole = section(0x42, BODY);

        byte[] first = start(whole, 6);
        router.push(PID, first, 0, first.length, true);

        router.ensureAssembler(PID);   // must not replace the running assembler

        byte[] rest = tail(whole, 6);
        router.push(PID, rest, 0, rest.length, false);

        assertEquals(1, out.sections.size());
        assertArrayEquals(whole, out.sections.get(0));
    }

    @Test
    public void aPidWithoutAReassemblerIsANoOp() {
        Collect out = new Collect();
        SectionRouter router = new SectionRouter(out);
        assertFalse(router.hasAssembler(0x222));

        byte[] whole = section(0x42, BODY);
        byte[] payload = start(whole, whole.length);
        router.push(0x222, payload, 0, payload.length, true);

        assertTrue("push must not deliver for a PID with no assembler", out.sections.isEmpty());
        assertFalse("push must not create an assembler as a side effect", router.hasAssembler(0x222));
    }

    @Test
    public void containsTracksEnsureAndPut() {
        Collect out = new Collect();
        SectionRouter router = new SectionRouter(out);
        assertFalse(router.hasAssembler(PID));
        router.ensureAssembler(PID);
        assertTrue(router.hasAssembler(PID));
        router.replaceAssembler(PID);
        assertTrue(router.hasAssembler(PID));
        assertFalse(router.hasAssembler(PID + 1));
    }

    /** ODTV-72: one crafted PAT can name every PMT PID; the registry must stop at the cap. */
    @Test
    public void assemblerRegistryIsCapped() {
        Collect out = new Collect();
        SectionRouter router = new SectionRouter(out);
        for (int pid = 0; pid < SectionRouter.MAX_SECTION_PIDS + 10; pid++) router.ensureAssembler(pid);
        assertEquals(SectionRouter.MAX_SECTION_PIDS, router.size());
        assertTrue("the first assemblers stay", router.hasAssembler(0));
        assertFalse("past the cap, no new assembler", router.hasAssembler(SectionRouter.MAX_SECTION_PIDS + 9));
        router.replaceAssembler(SectionRouter.MAX_SECTION_PIDS + 20);
        assertEquals("replaceAssembler must not grow past the cap either", SectionRouter.MAX_SECTION_PIDS, router.size());
    }
}
