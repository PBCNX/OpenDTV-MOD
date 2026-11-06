package com.pbcnx.opendtvmod.ts;

import com.pbcnx.opendtvmod.ts.util.TsUtil;

import static com.pbcnx.opendtvmod.ts.util.TsUtil.u16;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * EWBS / emergency signalling: the TMCC emergency start-up flag (IIP, PID 0x1FF0), the PMT
 * emergency_information_descriptor (0xFC) and the BML DSM-CC stream-event messages, plus the banner
 * and report text the UI asks for.
 *
 * <p>Split out of {@link TsDemux} without a logic change: the same descriptors, the same log, the
 * same banner text. It reads the coordinator's services and broadcast clock because those are what
 * its log, banner and report are about. Not thread-safe by itself; TsDemux calls it under its lock.
 */
final class EwbsMonitor {
    private final TsDemux demux;

    // EWBS / emergency signalling: TMCC start flag (IIP), PMT 0xFC descriptor, BML event messages
    private static final int MAX_EWBS_LOG = 200;
    private long eventSections;
    private long pmtEmergencyDescs;
    private boolean iipFlag = false;
    private String iipLastHex = "";
    private boolean testEwbsActive = false;
    private boolean testEwbsSeen = false;
    private int testEwbsArea = 0;
    /** PIDs whose last BML event message said EWBS start ('1'); each PID's own '2' ends it. */
    private final Set<Integer> bmlEwbsPids = new HashSet<>();
    /**
     * An actual EWBS start was seen (IIP flag, a PMT 0xFC start, or a BML '1'). The log also lists
     * routine event messages, so "the log is not empty" showed "EWBS was signalled" on ordinary
     * channels.
     */
    private boolean ewbsStartSeen = false;
    private long iipPackets = 0;
    private long iipBadCrc = 0;
    private final List<String> ewbsLog = new ArrayList<>();
    private final Map<Integer, String> lastEventMsg = new TreeMap<>();

    EwbsMonitor(TsDemux demux) {
        this.demux = demux;
    }

    private void logEwbs(String what) {
        if (ewbsLog.size() >= MAX_EWBS_LOG) ewbsLog.remove(0);
        ewbsLog.add(String.format(Locale.US, "%s  pkt %d  %s",
                demux.clockMs >= 0 ? TsUtil.formatTime(demux.clockMs) : "--", demux.totalPackets, what));
    }

    /**
     * ISDB-T Information Packet (ARIB STD-B31, PID 0x1FF0). Payload: IIP_packet_pointer(16), then the
     * 16-byte modulation_control_configuration_information and its CRC32. Inside it, byte 2 holds
     * system_identifier(2) count_down_index(4) switch_on_control_flag_for_emergency_alerting(1)
     * partial_reception(1): the emergency start-up flag is bit 1 of payload byte 4.
     */
    void parseIip(byte[] b, int p) {
        if (Crc32Mpeg.calc(b, p + 2, 20) != 0) {   // 16 bytes + CRC32 must give 0
            iipBadCrc++;
            return;
        }
        boolean f = ((b[p + 4] >> 1) & 1) != 0;
        StringBuilder hx = new StringBuilder();
        for (int i = 2; i < 18; i++) hx.append(String.format(Locale.US, "%02X", b[p + i] & 0xFF));
        iipLastHex = hx.toString();
        iipPackets++;
        if (iipPackets == 1) {
            iipFlag = f;
            if (f) logEwbs("IIP: emergency start-up flag already ON at first IIP");
        } else if (f != iipFlag) {
            iipFlag = f;
            logEwbs(f ? "IIP: emergency start-up flag 0 -> 1 (EWBS START)" : "IIP: emergency start-up flag 1 -> 0 (EWBS END)");
        }
        if (iipFlag) ewbsStartSeen = true;
    }

    /** PMT program-level emergency_information_descriptor (tag 0xFC). */
    void parseEmergencyDescriptor(ServiceInfo si, byte[] s, int from, int to) {
        boolean active = false;
        StringBuilder info = new StringBuilder();
        List<Integer> areas = new ArrayList<>();
        for (int d = from; d + 2 <= to; ) {
            int tag = s[d] & 0xFF;
            int dl = s[d + 1] & 0xFF;
            if (tag == 0xFC && d + 2 + dl <= to) {
                pmtEmergencyDescs++;
                int q = d + 2;
                int qEnd = q + dl;
                while (q + 4 <= qEnd) {
                    int esid = u16(s, q);
                    boolean start = (s[q + 2] & 0x80) != 0;
                    int level = (s[q + 2] >> 6) & 1;
                    int al = s[q + 3] & 0xFF;
                    q += 4;
                    if (start) active = true;
                    if (info.length() > 0) info.append("; ");
                    info.append(String.format(Locale.US, "service 0x%04X %s level %d areas", esid, start ? "START" : "END", level));
                    for (int a = 0; a + 2 <= al && q + a + 2 <= qEnd; a += 2) {
                        int area = u16(s, q + a) >> 4;
                        if (!areas.contains(area)) areas.add(area);
                    }
                    q += al;
                }
            }
            d += 2 + dl;
        }
        if (!areas.isEmpty()) {
            info.append(" (").append(EwbsAreaTable.format(areas)).append(")");
        }
        String now = info.toString();
        if (active != si.emergencyActive || !now.equals(si.emergencyInfo)) {
            if (!now.isEmpty()) logEwbs("PMT " + si.serviceId + " (0x" + Integer.toHexString(si.serviceId).toUpperCase(Locale.ROOT) + "): emergency descriptor 0xFC: " + now);
            else logEwbs("PMT " + si.serviceId + ": emergency descriptor removed");
        }
        si.emergencyActive = active;
        if (active) ewbsStartSeen = true;
        si.emergencyInfo = now;
        si.emergencyAreas = areas;
    }

    /**
     * DSM-CC stream-event section (table_id 0x3D). Looks for the general event message descriptor
     * (0x40): the first 12 bits are event_msg_group_id, the private data is the trailing printable
     * ASCII run (this PTV BML tests only its last character: '1' = EWBS start, '2' = end).
     */
    void parseStreamEvent(int pid, byte[] s, int n) {
        eventSections++;
        for (int d = 8; d + 2 <= n - 4; ) {
            int tag = s[d] & 0xFF;
            int dl = s[d + 1] & 0xFF;
            if (d + 2 + dl > n - 4) break;
            if (tag == 0x40 && dl >= 3) {
                int group = ((s[d + 2] & 0xFF) << 4) | ((s[d + 3] & 0xF0) >> 4);
                int e = d + 2 + dl;
                // private_data_byte only starts after the 11 fixed bytes (group, time_mode, the
                // 5-byte time, event_msg_type, event_msg_id). Scanning into them read event_msg_id
                // 0x0031 as the text "1" and reported a false EWBS start (jpdcs#12).
                int privStart = d + 2 + 11;
                int st = e;
                while (st > privStart && s[st - 1] >= 0x20 && s[st - 1] < 0x7F) st--;
                String priv = new String(s, st, e - st, java.nio.charset.StandardCharsets.US_ASCII);
                String key = group + "|" + priv;
                if (!key.equals(lastEventMsg.get(pid))) {
                    lastEventMsg.put(pid, key);
                    char last = priv.isEmpty() ? ' ' : priv.charAt(priv.length() - 1);
                    // Per PID: another service's routine '2' must not end an alert this one started.
                    if (last == '1') {
                        bmlEwbsPids.add(pid);
                        ewbsStartSeen = true;
                    } else if (last == '2') {
                        bmlEwbsPids.remove(pid);
                    }
                    logEwbs(String.format(Locale.US, "EVENT MSG pid 0x%04X group %d private '%s'%s", pid, group, priv,
                            last == '1' ? "  <- BML EWBS START" : last == '2' ? "  (BML: end / normal)" : ""));
                }
            }
            d += 2 + dl;
        }
    }

    /** Debug/test alert: behaves exactly like a PMT 0xFC descriptor with one area code. */
    void setTestEwbs(int area, boolean active) {
        testEwbsArea = area & 0xFFF;
        testEwbsActive = active;
        if (active) testEwbsSeen = true;
    }

    void clearTestEwbs() {
        testEwbsActive = false;
        testEwbsSeen = false;
        testEwbsArea = 0;
    }

    /** true while any EWBS signal (IIP flag or a PMT start descriptor) is active. */
    boolean isEwbsActive() {
        return isEwbsActive(0);
    }

    /** true when an active alert covers the viewer's Philippine location; 0 = show all. */
    boolean isEwbsActive(int location) {
        if (testEwbsActive
                && (location == 0 || testEwbsArea == 0
                        || EwbsAreaTable.covers(testEwbsArea, location))) {
            return true;
        }
        if (location == 0) {
            if (iipFlag || !bmlEwbsPids.isEmpty()) return true;
            for (ServiceInfo si : demux.services.values()) if (si.emergencyActive) return true;
            return false;
        }
        if (iipFlag || !bmlEwbsPids.isEmpty()) return true;
        for (ServiceInfo si : demux.services.values()) {
            if (!si.emergencyActive) continue;
            if (si.emergencyAreas.isEmpty()) return true;
            for (int area : si.emergencyAreas) {
                if (EwbsAreaTable.covers(area, location)) return true;
            }
        }
        return false;
    }

    /** true if any EWBS signal has been seen at all (even if it has ended again): useful for finished recordings. */
    boolean ewbsEverSeen() {
        // Not "the log is not empty": the log also lists routine event messages, which made an
        // ordinary channel claim EWBS was signalled once (jpdcs#12).
        return ewbsStartSeen || testEwbsSeen;
    }

    /** Short one-line description of what is signalling EWBS right now / last time (for the on-screen banner). */
    String ewbsBannerText() {
        return ewbsBannerText(0);
    }

    String ewbsBannerText(int location) {
        StringBuilder sb = new StringBuilder();
        if (testEwbsActive
                && (location == 0 || testEwbsArea == 0
                        || EwbsAreaTable.covers(testEwbsArea, location))) {
            sb.append("Test: ").append(EwbsAreaTable.format(java.util.Collections.singletonList(testEwbsArea)));
        }
        if (iipFlag) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("IIP start flag");
        }
        List<Integer> areas = new ArrayList<>();
        int noAreaService = -1;
        for (ServiceInfo si : demux.services.values()) {
            if (!si.emergencyActive) continue;
            if (location != 0 && !si.emergencyAreas.isEmpty()) {
                boolean covers = false;
                for (int area : si.emergencyAreas) {
                    if (EwbsAreaTable.covers(area, location)) {
                        covers = true;
                        break;
                    }
                }
                if (!covers) continue;
            }
            if (si.emergencyAreas.isEmpty()) {
                if (noAreaService < 0) noAreaService = si.serviceId;
            } else {
                for (int area : si.emergencyAreas) {
                    if (!areas.contains(area)) areas.add(area);
                }
            }
        }
        if (!areas.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(EwbsAreaTable.format(areas));
        }
        if (noAreaService >= 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("PMT ").append(noAreaService);
        }
        if (!bmlEwbsPids.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("BML event");
        }
        return sb.toString();
    }

    /** Multi-line text for the analyzer. Always non-empty so the user can see that EWBS was looked for. */
    String ewbsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(isEwbsActive() ? "!! EWBS ACTIVE !!\n" : !ewbsStartSeen ? "EWBS: no signal seen\n" : "EWBS: idle (was signalled, see log)\n");
        sb.append(String.format(Locale.US, "  IIP 0x1FF0: %d ok, %d bad CRC, emergency start-up flag %s\n",
                iipPackets, iipBadCrc, iipPackets == 0 ? "n/a (no IIP in this TS)" : iipFlag ? "ON" : "off"));
        if (!iipLastHex.isEmpty()) sb.append("  last IIP MCCI: ").append(iipLastHex).append('\n');
        sb.append(String.format(Locale.US, "  PMT 0xFC descriptors seen: %d | DSM-CC event sections (0x3D): %d | BML EWBS flag: %s\n",
                pmtEmergencyDescs, eventSections, !bmlEwbsPids.isEmpty() ? "ON" : "off"));
        for (ServiceInfo si : demux.services.values()) {
            if (!si.emergencyInfo.isEmpty()) sb.append("  PMT ").append(si.serviceId).append(": ").append(si.emergencyInfo).append('\n');
        }
        for (Map.Entry<Integer, String> e : lastEventMsg.entrySet()) {
            sb.append(String.format(Locale.US, "  event msg on 0x%04X: %s\n", e.getKey(), e.getValue().replace("|", " / ")));
        }
        int from = Math.max(0, ewbsLog.size() - 12);
        for (int i = from; i < ewbsLog.size(); i++) sb.append("  ").append(ewbsLog.get(i)).append('\n');
        return sb.toString();
    }
}
