package ph.opendtv.ts;

import java.util.ArrayList;
import java.util.List;

/** A service (channel) learned from PAT/PMT/SDT/NIT. Instances returned by TsDemux are snapshots. */
public final class ServiceInfo {
    public final int serviceId;          // "Program ID" (program_number / service_id)
    public int pmtPid = -1;
    public int pcrPid = -1;
    public int serviceType = -1;         // SDT service_type: 0x01 digital TV, 0xC0 data broadcast, ...
    public String name = "";
    public String provider = "";
    public boolean pmtSeen = false;
    /** remote_control_key_id from the NIT TS information descriptor, -1 if unknown. */
    public int remoteKey = -1;
    /** Display string for the virtual channel number, see TsDemux.getServices(). */
    public String virtualNumber = "--";
    public List<StreamInfo> streams = new ArrayList<>();
    /** PMT emergency_information_descriptor (0xFC) with start_end_flag = 1 is currently present (EWBS). */
    public boolean emergencyActive = false;
    /** Human readable contents of the last 0xFC descriptor seen ("" = none). */
    public String emergencyInfo = "";

    public ServiceInfo(int serviceId) {
        this.serviceId = serviceId;
    }

    public StreamInfo firstVideo() {
        for (StreamInfo s : streams) if (s.isVideo()) return s;
        return null;
    }

    public StreamInfo firstAudio() {
        for (StreamInfo s : streams) if (s.isAudio()) return s;
        return null;
    }

    public boolean hasVideo() {
        return firstVideo() != null;
    }

    /** 0xC0 = ARIB data service, 0x0C = DVB data broadcast service (the PH multiplex's "ssu OTA" uses this). */
    public boolean isDataBroadcastService() {
        return serviceType == 0xC0 || serviceType == 0x0C;
    }

    public ServiceInfo copy() {
        ServiceInfo c = new ServiceInfo(serviceId);
        c.pmtPid = pmtPid;
        c.pcrPid = pcrPid;
        c.serviceType = serviceType;
        c.name = name;
        c.provider = provider;
        c.pmtSeen = pmtSeen;
        c.remoteKey = remoteKey;
        c.virtualNumber = virtualNumber;
        c.emergencyActive = emergencyActive;
        c.emergencyInfo = emergencyInfo;
        for (StreamInfo s : streams) c.streams.add(s.copy());
        return c;
    }
}
