package ph.opendtv.ts;

import java.util.ArrayList;
import java.util.List;

/** A service (channel) learned from PAT/PMT/SDT/NIT. Instances returned by TsDemux are snapshots. */
public final class ServiceInfo {
    public final int serviceId;          // "Program ID" (program_number / service_id)
    /**
     * Mux frequency in kHz this copy was learned on, -1 until a ChannelEntry stamps it. A
     * service_id is only unique inside one mux: ALLTV HD (485143), IBC HD (491143) and INCTV HD
     * (683143) are all SID 64, and Congress TV HD / INCTV SD are both SID 65. Identity is
     * (frequency, service_id), never the SID alone.
     */
    public int khz = -1;
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
    /** Raw 12-bit area codes from the last 0xFC descriptor. */
    public List<Integer> emergencyAreas = new ArrayList<>();
    /** PMT or ES CA_descriptor (tag 0x09) present: the service is conditional-access. */
    public boolean encrypted = false;

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
        c.khz = khz;
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
        c.emergencyAreas.addAll(emergencyAreas);
        c.encrypted = encrypted;
        for (StreamInfo s : streams) c.streams.add(s.copy());
        return c;
    }
}
