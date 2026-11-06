package com.pbcnx.opendtvmod.ts;

import com.pbcnx.opendtvmod.ts.util.TsUtil;

import com.pbcnx.opendtvmod.ts.carousel.CarouselDecoder;

import static com.pbcnx.opendtvmod.ts.util.TsUtil.u12;
import static com.pbcnx.opendtvmod.ts.util.TsUtil.u13;
import static com.pbcnx.opendtvmod.ts.util.TsUtil.u16;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the PSI tables of one multiplex: PAT, PMT, SDT and NIT.
 *
 * <p>Split out of {@link TsDemux} without touching a line of logic: the byte-level loops are the
 * same, and the results are written into the coordinator's tables (services, network identity,
 * section routings, carousel decoders) because those are the state its query API exposes. Nothing
 * here decides policy - the same table sections that arrived before arrive now, and land in the
 * same fields.
 */
final class PsiParser {
    /** Real muxes carry one or two data carousels; 16 leaves room without giving a crafted PMT the heap. */
    private static final int MAX_DATA_PIDS = 16;

    private final TsDemux demux;

    PsiParser(TsDemux demux) {
        this.demux = demux;
    }

    void parsePat(byte[] s, int n) {
        demux.tsId = u16(s, 3);
        for (int p = 8; p + 4 <= n - 4; p += 4) {
            int program = u16(s, p);
            int pid = u13(s, p + 2);
            if (program == 0) continue; // NIT PID entry
            demux.svc(program).pmtPid = pid;
            demux.history.ensureRing(pid, PacketHistory.PSI_RING_PKTS, true);
            demux.sections.ensureAssembler(pid);
        }
    }

    void parsePmt(int pid, byte[] s, int n) {
        int sid = u16(s, 3);
        ServiceInfo si = demux.services.get(sid);
        if (si == null || si.pmtPid != pid) return;
        si.pcrPid = u13(s, 8);
        si.encrypted = false;
        int p = 12 + u12(s, 10);
        int end = n - 4;
        int programEnd = Math.min(p, end);
        for (int d = 12; d + 2 <= programEnd; ) {
            int tag = s[d] & 0xFF;
            int dl = s[d + 1] & 0xFF;
            if (tag == 0x09) si.encrypted = true;   // CA_descriptor at program level
            d += 2 + dl;
        }
        demux.parseEmergencyDescriptor(si, s, 12, programEnd);
        List<StreamInfo> list = new ArrayList<>();
        while (p + 5 <= end) {
            StreamInfo st = new StreamInfo();
            st.streamType = s[p] & 0xFF;
            st.pid = u13(s, p + 1);
            if (st.streamType == 0x11) demux.aacPids.add(st.pid);   // the label ALLTV lies about
            int dEnd = Math.min(p + 5 + u12(s, p + 3), end);
            int d = p + 5;
            while (d + 2 <= dEnd) {
                int tag = s[d] & 0xFF;
                int dl = s[d + 1] & 0xFF;
                if (tag == 0x09) si.encrypted = true;               // CA_descriptor at ES level
                else if (tag == 0x52 && dl >= 1) st.componentTag = s[d + 2] & 0xFF;
                else if (tag == 0xFD && dl >= 2) st.dataComponentId = u16(s, d + 2);
                else if (tag == 0x0A && dl >= 3) st.language = iso639(s, d + 2);            // ISO_639_language_descriptor
                else if (tag == 0xC4 && dl >= 9) {                                           // audio_component_descriptor
                    st.language = iso639(s, d + 2 + 6);
                    if ((s[d + 2 + 5] & 0x80) != 0 && dl >= 12) st.language2 = iso639(s, d + 2 + 9);
                }
                d += 2 + dl;
            }
            list.add(st);
            // A carousel never takes over a PID that already carries tables (PAT, EIT, a PMT ...): those would
            // stop being parsed for the rest of the tune. And one crafted PMT can name hundreds of data PIDs,
            // so the decoder and its history ring are capped (upstream ODTV-72).
            boolean existingCarousel = demux.carousels.containsKey(st.pid);
            boolean newCarousel = st.isDatacast() && !existingCarousel && st.pid >= 0x20
                    && !demux.sections.hasAssembler(st.pid) && demux.carousels.size() < MAX_DATA_PIDS;
            if (existingCarousel || newCarousel) {
                demux.history.ensureRing(st.pid, PacketHistory.DATA_RING_PKTS, false);
            }
            if (newCarousel) {
                demux.carousels.put(st.pid, new CarouselDecoder(st.pid));
                demux.sections.ensureAssembler(st.pid);
            } else if (st.streamType == 0x05 && !demux.sections.hasAssembler(st.pid)) {
                // private sections: could carry DSM-CC stream events (EWBS event messages) on their own PID
                demux.sections.ensureAssembler(st.pid);
            }
            p = dEnd;
        }
        si.streams = list;
        si.pmtSeen = true;
    }

    private static String iso639(byte[] s, int p) {
        String l = new String(new char[] {(char) (s[p] & 0x7F), (char) (s[p + 1] & 0x7F), (char) (s[p + 2] & 0x7F)}).trim();
        return l.length() == 3 ? l.toLowerCase(java.util.Locale.ROOT) : null;
    }

    void parseSdt(byte[] s, int n) {
        demux.tsId = u16(s, 3);
        int p = 11;
        int end = n - 4;
        while (p + 5 <= end) {
            int sid = u16(s, p);
            int dEnd = Math.min(p + 5 + u12(s, p + 3), end);
            ServiceInfo si = demux.svc(sid);
            int d = p + 5;
            while (d + 2 <= dEnd) {
                int tag = s[d] & 0xFF;
                int dl = s[d + 1] & 0xFF;
                if (tag == 0x48 && dl >= 3) { // service_descriptor
                    si.serviceType = s[d + 2] & 0xFF;
                    int provLen = s[d + 3] & 0xFF;
                    si.provider = TsUtil.text(s, d + 4, provLen);
                    int nameLen = s[d + 4 + provLen] & 0xFF;
                    si.name = TsUtil.text(s, d + 5 + provLen, nameLen);
                }
                d += 2 + dl;
            }
            p = dEnd;
        }
    }

    void parseNit(byte[] s, int n) {
        demux.networkId = u16(s, 3);
        int p = 8;
        int ndl = u12(s, p);
        p += 2;
        int dEnd = p + ndl;
        for (int d = p; d + 2 <= dEnd; ) {
            int tag = s[d] & 0xFF;
            int dl = s[d + 1] & 0xFF;
            if (tag == 0x40) demux.networkName = TsUtil.text(s, d + 2, dl);
            d += 2 + dl;
        }
        p = dEnd;
        int loopEnd = Math.min(p + 2 + u12(s, p), n - 4);
        p += 2;
        while (p + 6 <= loopEnd) {
            int tid = u16(s, p);
            int tdEnd = Math.min(p + 6 + u12(s, p + 4), loopEnd);
            for (int d = p + 6; d + 2 <= tdEnd; ) {
                int tag = s[d] & 0xFF;
                int dl = s[d + 1] & 0xFF;
                if (tag == 0xCD && dl >= 2) { // TS information descriptor
                    demux.tsRemoteKey.put(tid, s[d + 2] & 0xFF);
                }
                d += 2 + dl;
            }
            p = tdEnd;
        }
    }
}