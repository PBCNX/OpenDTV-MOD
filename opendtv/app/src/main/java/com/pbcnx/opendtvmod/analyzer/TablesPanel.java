package com.pbcnx.opendtvmod.analyzer;

import android.view.View;

import com.pbcnx.opendtvmod.ts.util.TsUtil;

import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.ts.EpgEvent;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;


/** The Tables tab: NIT, SDT, PMT and EIT as four cards. */
public final class TablesPanel implements TabPanel {
    private android.widget.TextView nitBody;
    private android.widget.TextView nitCount;
    private android.widget.TextView sdtBody;
    private android.widget.TextView sdtCount;
    private android.widget.TextView pmtBody;
    private android.widget.TextView pmtCount;
    private android.widget.TextView eitBody;
    private android.widget.TextView eitCount;


    private final AnalyzerActivity a;
    private final AnalyzerState state;
    /** The panel owns its root view; the activity asks for it. */
    private View root;

    public View view() { return root; }

    public TablesPanel(AnalyzerActivity activity, AnalyzerState state) {
        this.a = activity;
        this.state = state;
    }

    public void bindViews() {
        root = a.findViewById(R.id.tables_panel);
        nitBody = a.findViewById(R.id.nit_body);
        nitCount = a.findViewById(R.id.nit_count);
        sdtBody = a.findViewById(R.id.sdt_body);
        sdtCount = a.findViewById(R.id.sdt_count);
        pmtBody = a.findViewById(R.id.pmt_body);
        pmtCount = a.findViewById(R.id.pmt_count);
        eitBody = a.findViewById(R.id.eit_body);
        eitCount = a.findViewById(R.id.eit_count);
    }

    /** Tables tab: NIT, SDT, PMT and EIT as four cards. */
    @Override
    public void update() {
        if (state.currentTab == AnalyzerState.TAB_TABLES) {
            TsDemux d = state.demux;
            List<ServiceInfo> services = d.getServices();
            updateTables(d, services, AnalyzerUtil.findService(services, state.selectedServiceId));
        }
    }

    public void updateTables(TsDemux d, List<ServiceInfo> services, ServiceInfo sel) {
        if (nitBody == null) return;

        // --- NIT: the multiplex itself ---
        String net = d.getNetworkName();
        nitCount.setText("0x" + AnalyzerUtil.hex4(d.getNetworkId()));
        nitBody.setText("Name      " + (net == null || net.isEmpty() ? "--" : net) + "\n"
                + "TS ID     0x" + AnalyzerUtil.hex4(d.getTsId()) + "\n"
                + "Clock     " + TsUtil.formatTime(d.getClockMs()));

        // --- SDT: every service the mux advertises ---
        StringBuilder sdt = new StringBuilder();
        int sdtN = 0;
        for (ServiceInfo s : services) {
            sdt.append(String.format(Locale.US, "%4s  %-20s %s\n",
                    s.virtualNumber, com.pbcnx.opendtvmod.ui.ChannelAdapter.displayName(s), serviceKind(s)));
            sdtN++;
        }
        sdtCount.setText(sdtN + (sdtN == 1 ? " service" : " services"));
        sdtBody.setText(sdtN == 0 ? "No SDT seen yet." : sdt.toString().trim());

        // --- PMT: the streams of the selected service ---
        if (sel == null) {
            pmtCount.setText("--");
            pmtBody.setText("Select a service in the Services tab.");
        } else {
            StringBuilder pmt = new StringBuilder();
            pmt.append("Service ").append(sel.virtualNumber).append("  ").append(com.pbcnx.opendtvmod.ui.ChannelAdapter.displayName(sel))
                    .append("   PCR 0x").append(AnalyzerUtil.hex4(sel.pcrPid)).append('\n');
            for (StreamInfo st : sel.streams) {
                pmt.append(String.format(Locale.US, "0x%04X  %-24s tag %d%s\n", st.pid, st.codecName(),
                        st.componentTag, AnalyzerUtil.safe(st.language).isEmpty() ? "" : "  " + st.language));
            }
            if (sel.streams.isEmpty()) pmt.append("No PMT seen yet.");
            pmtCount.setText(sel.streams.size() + (sel.streams.size() == 1 ? " stream" : " streams"));
            pmtBody.setText(pmt.toString().trim());
        }

        // --- EIT: the next few events of the selected service ---
        List<EpgEvent> events = sel == null ? null : d.getEvents(sel.serviceId);
        int n = events == null ? 0 : events.size();
        eitCount.setText(n + (n == 1 ? " event" : " events"));
        if (n == 0) {
            eitBody.setText(sel == null ? "Select a service in the Services tab."
                    : "No EIT events yet for this service.");
        } else {
            StringBuilder eit = new StringBuilder();
            for (int i = 0; i < n && i < 4; i++) {
                EpgEvent e = events.get(i);
                eit.append(TsUtil.formatTime(e.startMs)).append("  +")
                        .append(String.format(Locale.US, "%d:%02d", e.durationSec / 3600, e.durationSec / 60 % 60))
                        .append("  ").append(e.name.isEmpty() ? "(no title)" : e.name).append('\n');
            }
            eitBody.setText(eit.toString().trim());
        }
    }

    // ------------------------------------------------------------------ Tables tab

    /** SDT service_type, in the words the rest of the UI uses. */
    private static String serviceKind(ServiceInfo s) {
        if (s.serviceType == 0x01) return s.encrypted ? "TV  [CA]" : "TV";
        if (s.serviceType == 0xC0 || s.serviceType == 0x0C) return "data";
        return s.serviceType < 0 ? "--" : String.format(Locale.US, "0x%02X", s.serviceType);
    }
}
