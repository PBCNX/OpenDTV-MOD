package ph.opendtv.ts;

import java.util.ArrayList;
import java.util.List;

/** Result of TsDemux.sample(): rates since the previous sample plus running error counters. */
public final class AnalyzerSnapshot {
    public static final class Row {
        public int pid;
        public String label;
        public double kbps;
        public long packets;
        public long ccErrors;
    }

    public double totalKbps;
    public long totalPackets;
    public long nullPackets;
    public long teiPackets;
    public long ccErrors;
    public long syncLosses;
    public final List<Row> rows = new ArrayList<>();
}
