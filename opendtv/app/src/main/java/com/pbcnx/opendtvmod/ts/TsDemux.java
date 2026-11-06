package com.pbcnx.opendtvmod.ts;

import com.pbcnx.opendtvmod.ts.util.TsUtil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.pbcnx.opendtvmod.ts.carousel.CarouselDecoder;
import com.pbcnx.opendtvmod.ts.carousel.CarouselInfo;


import static com.pbcnx.opendtvmod.ts.util.TsUtil.u16;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MPEG-TS demultiplexer and ISDB-T (ARIB STD-B10) PSI/SI analyzer.
 *
 * Call feed() with raw bytes in any chunking; it resynchronises on 0x47, tracks per-PID statistics,
 * parses PAT, PMT, SDT, NIT, EIT and TDT/TOT, and forwards good packets to an optional PacketSink.
 *
 * <p>Concurrency: {@link #feed} and the stateful readers are synchronized, so the UI thread may read
 * while a source thread feeds. The tap registry is a copy-on-write list ({@link #addTapSink} /
 * {@link #removeTapSink}) so taps are attached without the lock; the packet counters are updated under
 * the {@link #feed} monitor and read by synchronized getters.
 */
public final class TsDemux {
public static final int PID_PAT = 0x0000;
public static final int PID_NIT = 0x0010;
public static final int PID_SDT = 0x0011;
public static final int PID_EIT = 0x0012;
public static final int PID_TDT = 0x0014;
public static final int PID_IIP = 0x1FF0;
public static final int PID_NULL = 0x1FFF;

    private static final int PKT = 188;

    private static final class PidStats {
        long packets;
        long ccErrors;
        int lastCc = -1;
    }

    final PacketHistory history = new PacketHistory();

    private final byte[] carry = new byte[PKT];
    private int carryLen = 0;

    private final PidStats[] stats = new PidStats[0x2000];   // indexed by PID: no boxing in the per-packet path
    final SectionRouter sections = new SectionRouter(this::onSection);
    private final PsiParser psi = new PsiParser(this);
    final Map<Integer, ServiceInfo> services = new TreeMap<>();
    final Map<Integer, Integer> tsRemoteKey = new HashMap<>();
    private final EitAccumulator eit = new EitAccumulator();
    final Map<Integer, CarouselDecoder> carousels = new TreeMap<>();

    private PacketSink sink;
    /** Every consumer of the packet stream; the BML viewer and the whole-mux recorder coexist. */
    private final List<PacketSink> taps = new java.util.concurrent.CopyOnWriteArrayList<>();
    long totalPackets;
    private long nullPackets;
    private long teiPackets;
    private long syncLosses;
    int tsId = -1;
    int networkId = -1;
    String networkName = "";
    long clockMs = -1;
    private boolean clockIsTot;
    /** TOT local_time_offset descriptor in minutes, or NO_DESCRIPTOR. Diagnostic only. */
    private int descriptorMinutes = NO_DESCRIPTOR;

    /**
     * The AAC transport seen on a 0x11-labelled audio PID, decided from its first PES payload:
     * 1 for ADTS, 0 for LATM, -1 until one arrives.
     *
     * <p>The extractor asks for this BEFORE it finishes enumerating tracks, because a reader that
     * waits for bytes registers its track too late, and Media3 then logs "Extractor added new track
     * after finishing tracks" and never selects it - which is why ALLTV had no audio at all. This
     * demux is already reading the stream when the player is built, so it can answer in time.
     */
    private volatile int aacHint = -1;
    final java.util.Set<Integer> aacPids =
            Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private final EwbsMonitor ewbs = new EwbsMonitor(this);

    private long lastSampleNs = 0;
    private long lastSampleTotal = 0;
    private final Map<Integer, Long> lastSamplePackets = new HashMap<>();

    public TsDemux() {
        for (int pid : new int[]{PID_PAT, PID_NIT, PID_SDT, PID_EIT, PID_TDT}) {
            sections.ensureAssembler(pid);
            history.ensureRing(pid, pid == PID_EIT ? PacketHistory.EIT_RING_PKTS : PacketHistory.PSI_RING_PKTS, true);
        }
    }

    /**
     * Atomically installs {@code t} as the tap and returns the buffered history (PSI first, then data PIDs)
     * of every PID flagged in {@code want}, so a consumer that starts late still sees what already went by.
     * Packets arriving after this call go to the tap as usual.
     */
    public synchronized byte[] attachTapWithHistory(PacketSink t, boolean[] want) {
        byte[] out = history.snapshot(want);
        if (t != null && !taps.contains(t)) taps.add(t);
        return out;
    }

    /** Adds an independent consumer of every good packet; more than one may exist (BML viewer and recorder). */
    public void addTapSink(PacketSink s) {
        if (s != null && !taps.contains(s)) taps.add(s);
    }

    /** Removes one consumer; the others keep receiving. */
    public void removeTapSink(PacketSink s) {
        taps.remove(s);
    }



    public synchronized void setPacketSink(@Nullable PacketSink s) {
        this.sink = s;
    }

    // ------------------------------------------------------------------ packet layer

    public synchronized void feed(byte[] b, int off, int len) {
        // Clamp: a source must never make the demux read past its buffer.
        int p = Math.max(off, 0);
        int end = Math.min(off + len, b.length);
        if (end <= p) return;

        if (carryLen > 0) {
            int take = Math.min(PKT - carryLen, end - p);
            System.arraycopy(b, p, carry, carryLen, take);
            carryLen += take;
            p += take;
            if (carryLen < PKT) return;
            if ((carry[0] & 0xFF) == 0x47) handlePacket(carry, 0);
            else syncLosses++;
            carryLen = 0;
        }

        while (end - p >= PKT) {
            if ((b[p] & 0xFF) != 0x47) {
                syncLosses++;
                int q = p + 1;
                while (q < end && !((b[q] & 0xFF) == 0x47 && (q + PKT >= end || (b[q + PKT] & 0xFF) == 0x47))) q++;
                p = q;
                continue;
            }
            handlePacket(b, p);
            p += PKT;
        }

        if (p < end) {
            carryLen = end - p;
            System.arraycopy(b, p, carry, 0, carryLen);
        }
        // Same order as handlePacket(): taps (recorders) first, then the single optional sink.
        for (PacketSink tp : taps) tp.flush();
        if (sink != null) sink.flush();
    }

    private void handlePacket(byte[] b, int o) {
        boolean tei = (b[o + 1] & 0x80) != 0;
        boolean pusi = (b[o + 1] & 0x40) != 0;
        int pid = ((b[o + 1] & 0x1F) << 8) | (b[o + 2] & 0xFF);
        int afc = (b[o + 3] >> 4) & 3;
        int cc = b[o + 3] & 0x0F;

        totalPackets++;
        if (pid == PID_NULL) {
            nullPackets++;
            return;
        }
        if (tei) {
            teiPackets++;
            return;
        }

        PidStats st = stats[pid];
        if (st == null) {
            st = new PidStats();
            stats[pid] = st;
        }
        st.packets++;

        int p = o + 4;
        int end = o + PKT;
        boolean hasPayload = (afc & 1) != 0;
        boolean discontinuity = false;
        if ((afc & 2) != 0) {
            int afl = b[p] & 0xFF;
            if (afl > 0 && p + 1 < end) discontinuity = (b[p + 1] & 0x80) != 0;
            p += 1 + afl;
        }
        if (hasPayload) {
            if (st.lastCc >= 0 && !discontinuity && cc != st.lastCc && cc != ((st.lastCc + 1) & 0x0F)) {
                st.ccErrors++;
            }
            st.lastCc = cc;
        } else if (discontinuity) {
            st.lastCc = -1;
        }

        if (pid == PID_IIP && hasPayload && pusi && end - p >= 22) ewbs.parseIip(b, p);
        if (hasPayload && pusi && aacHint < 0 && aacPids.contains(pid)) sniffAac(pid, b, p, end);

        if (hasPayload && p < end) sections.push(pid, b, p, end, pusi);
        history.add(pid, b, o);
        for (PacketSink t : taps) t.onPacket(b, o);
        if (sink != null) sink.onPacket(b, o);
    }

    /**
     * Looks at the first PES payload of a 0x11-labelled audio PID and records whether it carries ADTS
     * or LATM, for {@link #aacTransportHint()}. Purely a read: nothing here changes what is played.
     */
    private void sniffAac(int pid, byte[] b, int p, int end) {
        if (end - p < 12) return;
        if (!((b[p] & 0xFF) == 0 && (b[p + 1] & 0xFF) == 0 && (b[p + 2] & 0xFF) == 1)) return;   // PES start
        int body = p + 9 + (b[p + 8] & 0xFF);
        if (body + 1 >= end) return;
        int x = b[body] & 0xFF;
        int y = b[body + 1] & 0xFF;
        int hint;
        if (x == 0xFF && (y & 0xF0) == 0xF0) hint = 1;             // ADTS
        else if (x == 0x56 && (y & 0xE0) == 0xE0) hint = 0;        // LOAS/LATM
        else return;
        if (aacHint < 0) {
            aacHint = hint;
            android.util.Log.i("OpenDTV-AAC", "hint pid=0x" + Integer.toHexString(pid) + " -> "
                    + (hint == 1 ? "ADTS" : "LATM") + " (from the first payload, before the extractor starts)");
        }
    }

    // ------------------------------------------------------------------ section layer

    private void onSection(int pid, byte[] s, int n) {
        int tid = s[0] & 0xFF;
        try {
            if (tid == 0x3D) ewbs.parseStreamEvent(pid, s, n);   // DSM-CC stream-event section (BML event messages)
            CarouselDecoder cd = carousels.get(pid);
            if (cd != null) { // PID announced as DSM-CC data (stream types 0x0B-0x0D)
                cd.onSection(s, n);
                return;
            }
            switch (tid) {
                case 0x00: psi.parsePat(s, n); break;
                case 0x02: psi.parsePmt(pid, s, n); break;
                case 0x40: psi.parseNit(s, n); break;
                case 0x42: psi.parseSdt(s, n); break;
                case 0x70: case 0x73: parseTime(s, n); break;
                default:
                    if (pid == PID_EIT && (tid == 0x4E || (tid >= 0x50 && tid <= 0x5F))) eit.parse(s, n);
            }
        } catch (RuntimeException ignored) {
            // Malformed table (length fields out of range): ignore it, keep running.
        }
    }

    ServiceInfo svc(int sid) {
        ServiceInfo si = services.get(sid);
        if (si == null) {
            si = new ServiceInfo(sid);
            services.put(sid, si);
        }
        return si;
    }

    private void parseTime(byte[] s, int n) {
        // WHERE THE 5-BYTE TIME FIELD STARTS IS NOT SETTLED BY table_id.
        //
        // STD-B24 puts service_id before the time in a TOT (offset 5), and an even older reading
        // here used 6. But the Philippine streams measure differently: GMA's TOT section is
        // `73 70 1a eb 29 15 24 22 ...` - at offset 3 that is MJD 0xEB29 (2023-09-14) and the time
        // 15:24:22, which matches the sweep BMLPLAYER took off air, while offset 6 decodes to
        // 1884-03-21. The old fixed offset 6 therefore left clockMs invalid on every mux that sends
        // only a TOT (GMA has no TDT at all), which in turn broke the EPG Now/Next window - measured
        // 2026-11-02 against GMA-BML.ts, where the guide read "No program info" over a mux carrying
        // "Bride of the Water God".
        //
        // So pick the offset by sanity instead of by table: a date that is not in this century is
        // not a date. 40000 is 1968 and 70000 is 2050 - wide enough for any real multiplex, narrow
        // enough to reject a misaligned field.
        if (n < 8) return;
        int tid = s[0] & 0xFF;
        int timeOffset = 3;
        if (tid == 0x73) {
            for (int candidate : new int[]{3, 5, 6}) {
                if (candidate + 5 > n) continue;
                int mjd = u16(s, candidate);
                if (mjd >= 40000 && mjd <= 70000) {
                    timeOffset = candidate;
                    break;
                }
            }
        }
        if (n < timeOffset + 5) return;
        clockMs = TsUtil.mjdToMillis(s, timeOffset);
        clockIsTot = tid == 0x73;
        // The descriptor is kept for the DIAGNOSTIC only. The receiver (ABS-CBN TVplus) ignores it
        // and adds eight hours to whatever the field holds - measured on hardware by PBCNX-DTV's
        // TimeInjector and swept across eight PH muxes by BMLPLAYER on 31 Oct - so the display
        // follows the receiver and never "corrects" by the descriptor.
        descriptorMinutes = tid == 0x73 ? parseLocalTimeOffset(s, n, timeOffset + 5) : Integer.MIN_VALUE;
    }

    /**
     * The TOT's local_time_offset_descriptor (tag 0x58) in signed minutes, or {@link #NO_DESCRIPTOR}.
     * Diagnostic only: GMA declares PHL +0:00 while sending UTC, TV5 declares +0:00 while sending
     * local time, Solar declares +08:00, so the same byte means different things per mux.
     */
    public static final int NO_DESCRIPTOR = Integer.MIN_VALUE;

    private static int parseLocalTimeOffset(byte[] s, int n, int from) {
        for (int p = from; p + 2 <= n; ) {
            int tag = s[p] & 0xFF;
            int len = s[p + 1] & 0xFF;
            if (p + 2 + len > n) break;
            if (tag == 0x58 && len >= 5) {
                int polarity = (s[p + 2] >> 7) & 1;
                int hours = s[p + 2] & 0x3F;
                int minutes = TsUtil.bcd(s[p + 3]);
                int total = hours * 60 + minutes;
                return polarity != 0 ? -total : total;
            }
            p += 2 + len;
        }
        return NO_DESCRIPTOR;
    }

    void parseEmergencyDescriptor(ServiceInfo si, byte[] s, int from, int to) {
        ewbs.parseEmergencyDescriptor(si, s, from, to);
    }

    /** Debug/test alert: behaves exactly like a PMT 0xFC descriptor with one area code. */
    public synchronized void setTestEwbs(int area, boolean active) {
        ewbs.setTestEwbs(area, active);
    }

    public synchronized void clearTestEwbs() {
        ewbs.clearTestEwbs();
    }

    /** true while any EWBS signal (IIP flag or a PMT start descriptor) is active. */
    public synchronized boolean isEwbsActive() {
        return ewbs.isEwbsActive();
    }

    /** true when an active alert covers the viewer's Philippine location; 0 = show all. */
    public synchronized boolean isEwbsActive(int location) {
        return ewbs.isEwbsActive(location);
    }

    /** true if any EWBS signal has been seen at all (even if it has ended again): useful for finished recordings. */
    public synchronized boolean ewbsEverSeen() {
        return ewbs.ewbsEverSeen();
    }

    /** Short one-line description of what is signalling EWBS right now / last time (for the on-screen banner). */
    public synchronized String ewbsBannerText() {
        return ewbs.ewbsBannerText();
    }

    public synchronized String ewbsBannerText(int location) {
        return ewbs.ewbsBannerText(location);
    }

    /** Multi-line text for the analyzer. Always non-empty so the user can see that EWBS was looked for. */
    public synchronized String ewbsReport() {
        return ewbs.ewbsReport();
    }

    // ------------------------------------------------------------------ queries (snapshots)

    /** true once the NIT's TS-information descriptor (remote_control_key_id = virtual channel number) was received. */
    public synchronized boolean hasRemoteKey() {
        return tsRemoteKey.containsKey(tsId) || tsRemoteKey.size() == 1;
    }

    /** Services sorted by service_id. virtualNumber is remote_control_key_id, with ".N" appended when the TS has several TV services (heuristic). */
    @NonNull public synchronized List<ServiceInfo> getServices() {
        Integer rk = tsRemoteKey.get(tsId);
        if (rk == null && tsRemoteKey.size() == 1) rk = tsRemoteKey.values().iterator().next();

        int tvCount = 0;
        for (ServiceInfo si : services.values()) if (isTv(si)) tvCount++;

        List<ServiceInfo> out = new ArrayList<>();
        int idx = 0;
        for (ServiceInfo si : services.values()) {
            ServiceInfo c = si.copy();
            c.remoteKey = rk == null ? -1 : rk;
            if (rk != null && isTv(si)) {
                idx++;
                c.virtualNumber = tvCount > 1 ? rk + "." + idx : String.valueOf(rk);
            }
            out.add(c);
        }
        return out;
    }

    private static boolean isTv(ServiceInfo si) {
        return si.serviceType == 0x01 || si.hasVideo();
    }

    /** Snapshots of every DSM-CC data carousel found in the PMTs, sorted by PID. */
    @NonNull public synchronized List<CarouselInfo> getCarousels() {
        List<CarouselInfo> out = new ArrayList<>();
        for (CarouselDecoder cd : carousels.values()) out.add(cd.snapshot());
        return out;
    }

    /**
     * Packets accepted since this demux was created. A monotonically rising count is the only
     * honest answer to "is a source live?": a dongle can be plugged in and silent, and a file is a
     * source without being a tuner. Callers sample it and compare, they never read it as a level.
     */
    public synchronized long getPacketCount() { return totalPackets; }

    public synchronized int getTsId() { return tsId; }

    public synchronized int getNetworkId() { return networkId; }

    public synchronized String getNetworkName() { return networkName; }

    /** Broadcast clock from TDT/TOT (ARIB: JST wall-clock), -1 if not received. */
    public synchronized long getClockMs() { return clockMs; }

    /** 1 = ADTS, 0 = LATM, -1 = no 0x11 audio payload seen yet. */
    public synchronized int aacTransportHint() { return aacHint; }

    /** The AAC reader's own choice for this stream, recorded for the analyzer's codec line. */
    public void noteAacTransport(boolean adts) { aacHint = adts ? 1 : 0; }

    /** True when the last clock came from a TOT (0x73) rather than a TDT (0x70). */

    public synchronized boolean clockIsTot() { return clockIsTot; }

    /** The TOT descriptor's offset in minutes, or {@link #NO_DESCRIPTOR}. Diagnostic only. */
    public synchronized int clockDescriptorMinutes() { return descriptorMinutes; }

    /** The EIT events for a service, as detached copies like the other queries. */
    @NonNull public synchronized List<EpgEvent> getEvents(int serviceId) {
        List<EpgEvent> live = eit.events(serviceId);
        List<EpgEvent> out = new ArrayList<>(live.size());
        for (EpgEvent e : live) out.add(e.copy());
        return out;
    }

    /**
     * Snapshot of rates and error counters for the analyzer. Not a pure query: it advances the
     * rate baseline (lastSampleNs/lastSampleTotal) that the next call measures against, so call it
     * from one ticker only.
     */
    @NonNull public synchronized AnalyzerSnapshot sample() {
        long now = System.nanoTime();
        double dt = lastSampleNs == 0 ? 0 : (now - lastSampleNs) / 1e9;
        AnalyzerSnapshot snap = new AnalyzerSnapshot();
        snap.totalPackets = totalPackets;
        snap.nullPackets = nullPackets;
        snap.teiPackets = teiPackets;
        snap.syncLosses = syncLosses;
        snap.totalKbps = dt > 0 ? (totalPackets - lastSampleTotal) * PKT * 8 / dt / 1000.0 : 0;

        for (int pidIdx = 0; pidIdx < stats.length; pidIdx++) {
            PidStats st = stats[pidIdx];
            if (st == null) continue;
            Long prev = lastSamplePackets.get(pidIdx);
            AnalyzerSnapshot.Row r = new AnalyzerSnapshot.Row();
            r.pid = pidIdx;
            r.label = describePidLocked(r.pid);
            r.packets = st.packets;
            r.ccErrors = st.ccErrors;
            r.kbps = (dt > 0 && prev != null) ? (st.packets - prev) * PKT * 8 / dt / 1000.0 : 0;
            snap.ccErrors += st.ccErrors;
            snap.rows.add(r);
            lastSamplePackets.put(pidIdx, st.packets);
        }
        Collections.sort(snap.rows, new Comparator<AnalyzerSnapshot.Row>() {
            @Override
            public int compare(AnalyzerSnapshot.Row a, AnalyzerSnapshot.Row b) {
                int c = Double.compare(b.kbps, a.kbps);
                return c != 0 ? c : Integer.compare(a.pid, b.pid);
            }
        });
        lastSampleNs = now;
        lastSampleTotal = totalPackets;
        return snap;
    }

    public synchronized String describePid(int pid) {
        return describePidLocked(pid);
    }

    private String describePidLocked(int pid) {
        switch (pid) {
            case PID_PAT: return "PAT";
            case PID_NIT: return "NIT";
            case PID_SDT: return "SDT/BAT";
            case PID_EIT: return "EIT";
            case PID_TDT: return "TDT/TOT";
            case PID_IIP: return "IIP (TMCC / EWBS start flag)";
            default:
        }
        for (ServiceInfo si : services.values()) {
            if (si.pmtPid == pid) return "PMT sid " + si.serviceId;
            for (StreamInfo st : si.streams) {
                if (st.pid == pid) {
                    String kind = st.isVideo() ? "Video" : st.isAudio() ? "Audio"
                                                           : st.isDatacast() ? "Datacast" : "Data";
                    return kind + " " + st.shortCodec() + " (sid " + si.serviceId + ")";
                }
            }
        }
        return "";
    }
}