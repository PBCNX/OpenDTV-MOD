import ph.opendtv.ts.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/** Dumps what TsDemux learns from a recorded .ts file.  Usage: java -cp out TsDump file.ts */
public class TsDump {
    public static void main(String[] args) throws Exception {
        TsDemux d = new TsDemux();
        byte[] buf = new byte[188 * 500];
        long bytes = 0;
        d.sample();
        long t0 = System.nanoTime();
        try (InputStream in = new FileInputStream(args[0])) {
            int n;
            while ((n = in.read(buf)) > 0) {
                d.feed(buf, 0, n);
                bytes += n;
            }
        }
        AnalyzerSnapshot s = d.sample();

        System.out.printf("File bytes: %d (%d packets)%n", bytes, bytes / 188);
        System.out.printf("Network: '%s' (0x%04X)  TS id: 0x%04X  clock: %s%n",
                d.getNetworkName(), Math.max(d.getNetworkId(), 0), Math.max(d.getTsId(), 0), TsUtil.formatTime(d.getClockMs()));
        System.out.printf("Packets %d | null %d | TEI %d | CC errors %d | sync losses %d%n",
                s.totalPackets, s.nullPackets, s.teiPackets, s.ccErrors, s.syncLosses);

        List<ServiceInfo> svcs = d.getServices();
        System.out.println("\nServices (" + svcs.size() + "):");
        for (ServiceInfo si : svcs) {
            System.out.printf("  vn=%-5s sid=%d (0x%04X) type=0x%02X '%s' prov='%s' PMT=0x%04X PCR=0x%04X pmtSeen=%b%n",
                    si.virtualNumber, si.serviceId, si.serviceId, si.serviceType & 0xFF, si.name, si.provider,
                    si.pmtPid, si.pcrPid, si.pmtSeen);
            for (StreamInfo st : si.streams) {
                System.out.printf("      PID 0x%04X  type 0x%02X  %-28s tag=%s dcid=%s%n", st.pid, st.streamType, st.codecName(),
                        st.componentTag >= 0 ? String.format("0x%02X", st.componentTag) : "-",
                        st.dataComponentId >= 0 ? String.format("0x%04X", st.dataComponentId) : "-");
            }
            List<EpgEvent> ev = d.getEvents(si.serviceId);
            int shown = 0;
            for (EpgEvent e : ev) {
                if (shown++ >= 4) break;
                System.out.printf("      EPG %s +%d:%02d  %s%n", TsUtil.formatTime(e.startMs), e.durationSec / 3600, (e.durationSec / 60) % 60, e.name);
            }
            if (ev.size() > 4) System.out.printf("      ... %d events total%n", ev.size());
        }

        for (CarouselInfo ci : d.getCarousels()) {
            System.out.printf("%nCarousel PID 0x%04X: DSI %d, DII %d, DDB %d, %d bytes of blocks, block size %d, modules %d (%d complete), files %d%n",
                    ci.pid, ci.dsiCount, ci.diiCount, ci.ddbCount, ci.bytesReceived, ci.blockSize,
                    ci.modules.size(), ci.completeModules(), ci.totalResources());
            for (CarouselModule m : ci.modules) {
                System.out.printf("  module 0x%04X v%-3d %8d bytes  %3d/%-3d blocks %3d%%  %s%s%s%n",
                        m.moduleId, m.version, m.size, m.blocksReceived, m.blocksTotal, m.percent(),
                        m.complete ? "COMPLETE" : "partial", m.compressed ? " zlib" : "",
                        m.error.isEmpty() ? "" : "  ERROR: " + m.error);
                int shown = 0;
                for (CarouselResource r : m.resources) {
                    if (shown++ >= 6) { System.out.printf("      ... %d files%n", m.resources.size()); break; }
                    System.out.printf("      %-34s %7d  %s%n", r.contentType.length() > 34 ? r.contentType.substring(0, 34) : r.contentType, r.length, r.location);
                }
            }
        }

        System.out.println("\nPID table (packets, CC errors):");
        for (AnalyzerSnapshot.Row r : s.rows) {
            System.out.printf("  0x%04X %8d pkts %5d ccErr  %5.2f%%  %s%n", r.pid, r.packets, r.ccErrors,
                    100.0 * r.packets / s.totalPackets, r.label);
        }
    }
}
