package com.pbcnx.opendtvmod.ts;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns the per-PID section reassemblers: the stage that turns the payload of 188-byte TS packets
 * into whole PSI/SI sections.
 *
 * <p>One {@link SectionAssembler} per PID, created on demand when the PAT announces a PMT PID or a
 * PMT announces a data/private PID, and fed from {@link TsDemux}'s packet loop. Whole sections go
 * straight to the sink (the coordinator), which decides which table parser reads them.
 *
 * <p>The count is capped: a real multiplex carries a handful of section PIDs, but one crafted PAT can
 * name all 8000 PMT PIDs and one PMT hundreds of carousels. Every assembler holds a 4 KB section buffer
 * and a map entry, so an uncapped registry is a remote memory-exhaustion path (upstream ODTV-72).
 */
final class SectionRouter {
    /** Real muxes: a PAT names one PMT PID per service, a few dozen at most. 256 is generous. */
    static final int MAX_SECTION_PIDS = 256;

    private final SectionAssembler.Sink sink;
    private final Map<Integer, SectionAssembler> assemblers = new HashMap<>();

    SectionRouter(SectionAssembler.Sink sink) {
        this.sink = sink;
    }

    /** Creates the reassembler for {@code pid} if none is running yet, and if the cap allows it. */
    void ensureAssembler(int pid) {
        if (assemblers.containsKey(pid)) return;
        if (assemblers.size() >= MAX_SECTION_PIDS) return;
        assemblers.put(pid, new SectionAssembler(pid, sink));
    }

    /** Replaces the reassembler for {@code pid}, dropping whatever partial section it held. */
    void replaceAssembler(int pid) {
        if (!assemblers.containsKey(pid) && assemblers.size() >= MAX_SECTION_PIDS) return;
        assemblers.put(pid, new SectionAssembler(pid, sink));
    }

    boolean hasAssembler(int pid) {
        return assemblers.containsKey(pid);
    }

    int size() {
        return assemblers.size();
    }

    /** @param p start of the payload in b, end = one past its last byte. */
    void push(int pid, byte[] b, int p, int end, boolean pusi) {
        SectionAssembler a = assemblers.get(pid);
        if (a != null) a.push(b, p, end, pusi);
    }
}
