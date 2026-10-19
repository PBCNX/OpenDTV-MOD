package ph.opendtv.ts;

import java.util.ArrayList;
import java.util.List;

/** Snapshot of one data carousel (one PID). */
public final class CarouselInfo {
    public int pid;
    public int blockSize;
    public int diiCount;      // DownloadInfoIndication messages seen
    public int dsiCount;      // DownloadServerInitiate messages seen
    public int ddbCount;      // DownloadDataBlock messages seen
    public long bytesReceived;
    public final List<CarouselModule> modules = new ArrayList<>();

    public int completeModules() {
        int n = 0;
        for (CarouselModule m : modules) if (m.complete) n++;
        return n;
    }

    public int totalResources() {
        int n = 0;
        for (CarouselModule m : modules) n += m.resources.size();
        return n;
    }
}
