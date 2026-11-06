package com.pbcnx.opendtvmod.analyzer;

import com.pbcnx.opendtvmod.ts.carousel.CarouselInfo;
import com.pbcnx.opendtvmod.ts.carousel.CarouselModule;
import com.pbcnx.opendtvmod.ts.carousel.CarouselResource;

import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/** The Datacast tab's text: DSM-CC carousels, their modules and their files. */
public final class DatacastPanel {

    private DatacastPanel() {
    }

public static String datacastText(TsDemux d, List<ServiceInfo> services, ServiceInfo sel) {
        List<CarouselInfo> carousels = d.getCarousels();
        StringBuilder sb = new StringBuilder();
        sb.append("Datacast: DSM-CC data carousels are decoded into modules and files (names, types, sizes).\n")
                .append("BML pages and images are listed, not rendered.\n\n");
        if (sel != null) {
            sb.append("Selected service ").append(sel.serviceId).append(":\n");
            boolean any = false;
            for (StreamInfo s : sel.streams) {
                if (s.isDatacast()) {
                    any = true;
                    sb.append(String.format(Locale.US, "  PID 0x%04X  %s  data_component_id %s  tag %s\n",
                            s.pid, s.codecName(),
                            s.dataComponentId >= 0 ? String.format(Locale.US, "0x%04X", s.dataComponentId) : "--",
                            s.componentTag >= 0 ? String.format(Locale.US, "0x%02X", s.componentTag) : "--"));
                    appendCarousel(sb, carouselFor(carousels, s.pid), "    ", true);
                }
            }
            if (!any) sb.append("  no data-carousel stream in this service\n");
            for (StreamInfo s : sel.streams) {
                if (s.streamType == 0x06) {
                    sb.append(String.format(Locale.US, "  PID 0x%04X  PES private (captions/text)  data_component_id %s\n",
                            s.pid, s.dataComponentId >= 0 ? String.format(Locale.US, "0x%04X", s.dataComponentId) : "--"));
                }
            }
            sb.append('\n');
        }
        sb.append("Data-broadcast services in this multiplex (service_type 0xC0 / 0x0C):\n");
        boolean found = false;
        for (ServiceInfo s : services) {
            if (!s.isDataBroadcastService()) continue;
            found = true;
            sb.append(String.format(Locale.US, "  sid %d  %s  PMT 0x%04X\n", s.serviceId, s.name, s.pmtPid));
            for (StreamInfo st : s.streams) {
                if (st.isDatacast()) {
                    sb.append(String.format(Locale.US, "      PID 0x%04X  %s\n", st.pid, st.codecName()));
                    appendCarousel(sb, carouselFor(carousels, st.pid), "      ", false);
                }
            }
        }
        if (!found) sb.append("  none seen\n");
        return sb.toString();
    }

    private static CarouselInfo carouselFor(List<CarouselInfo> list, int pid) {
        for (CarouselInfo c : list) if (c.pid == pid) return c;
        return null;
    }

/** @param files list the files of each module (true), or only module id/size/progress (false). */
    private static void appendCarousel(StringBuilder sb, CarouselInfo ci, String indent, boolean files) {
        if (ci == null) {
            sb.append(indent).append("(no carousel data received yet)\n");
            return;
        }
        sb.append(String.format(Locale.US, "%s%d/%d modules complete, %d files, %d KB received (DII %d, DDB %d)\n",
                indent, ci.completeModules(), ci.modules.size(), ci.totalResources(),
                ci.bytesReceived / 1024, ci.diiCount, ci.ddbCount));
        int shownModules = 0;
        for (CarouselModule m : ci.modules) {
            if (shownModules++ >= 30) {
                sb.append(indent).append("  ... ").append(ci.modules.size() - 30).append(" more modules\n");
                break;
            }
            sb.append(String.format(Locale.US, "%s  module 0x%04X v%d  %d B  %s%s%s\n", indent, m.moduleId, m.version,
                    m.size, m.complete ? "complete" : m.percent() + "%", m.compressed ? "  (zlib)" : "",
                    m.error.isEmpty() ? "" : "  " + m.error));
            if (!files) continue;
            int shown = 0;
            for (CarouselResource r : m.resources) {
                if (r.location.isEmpty()) continue; // the resource-list part has no name
                if (shown++ >= 6) {
                    sb.append(indent).append("      ... more files\n");
                    break;
                }
                int semi = r.contentType.indexOf(';');
                String type = semi > 0 ? r.contentType.substring(0, semi) : r.contentType;
                sb.append(String.format(Locale.US, "%s      %s  %s  %d B\n", indent, r.location, type, r.length));
            }
        }
    }
}
