package com.pbcnx.opendtvmod.analyzer;

import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.Locale;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.ts.AnalyzerSnapshot;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;


/** The Graph tab: three 30 s histories and the sparkline that draws one of them. */
public final class GraphPanel implements TabPanel {
    private final float[] histBitrate = new float[30];
    private final float[] histPackets = new float[30];
    private final float[] histCc = new float[30];
    private int histCount;
    private long prevPackets = -1;
    private long prevCc = -1;
    private int graphMetric = 0;
    private com.pbcnx.opendtvmod.ui.SparklineView graphSpark;
    private android.widget.TextView graphValue;
    private android.widget.TextView graphUnit;
    private android.widget.TextView graphPeak;
    private android.widget.TextView graphAvg;
    private android.widget.TextView graphLine;
    private android.widget.TextView chipGraphBitrate;
    private android.widget.TextView chipGraphPackets;
    private android.widget.TextView chipGraphCc;


    // ---- Graph tab: three 30 s histories, one sparkline ----
    private static final int HIST = 30;
    private static final int METRIC_BITRATE = 0;
    private static final int METRIC_PACKETS = 1;
    private static final int METRIC_CC = 2;

    private final AnalyzerActivity a;
    private final AnalyzerState state;
    /** The panel owns its root view; the activity asks for it. */
    private View root;

    public View view() { return root; }

        public GraphPanel(AnalyzerActivity activity, AnalyzerState state) {
        this.a = activity;
        this.state = state;
    }

        public void bindViews() {
        root = a.findViewById(R.id.graph_panel);
        graphSpark = a.findViewById(R.id.graph_spark);
        graphValue = a.findViewById(R.id.graph_value);
        graphUnit = a.findViewById(R.id.graph_unit);
        graphPeak = a.findViewById(R.id.graph_peak);
        graphAvg = a.findViewById(R.id.graph_avg);
        graphLine = a.findViewById(R.id.graph_line);
        chipGraphBitrate = a.findViewById(R.id.chip_graph_bitrate);
        chipGraphPackets = a.findViewById(R.id.chip_graph_packets);
        chipGraphCc = a.findViewById(R.id.chip_graph_cc);
        chipGraphBitrate.setOnClickListener(v -> setGraphMetric(METRIC_BITRATE));
        chipGraphPackets.setOnClickListener(v -> setGraphMetric(METRIC_PACKETS));
        chipGraphCc.setOnClickListener(v -> setGraphMetric(METRIC_CC));
    }

        /** The same 30 s of bitrate the Signal tab draws, as a glance-sized sparkline. */
    public float[] bitrateSeries() {
        float[] series = new float[histCount];
        if (histCount > 0) System.arraycopy(histBitrate, HIST - histCount, series, 0, histCount);
        return series;
    }

    // ------------------------------------------------------------------ Graph tab

    /** One sample per tick, whatever tab is open, so the window has history when you switch. */
    @Override
    public void update() {
        pushHistory();
        if (state.currentTab == AnalyzerState.TAB_GRAPH) {
            TsDemux d = state.demux;
            updateGraphPanel(d, AnalyzerUtil.findService(d.getServices(), state.selectedServiceId));
        }
    }

    public void pushHistory() {
        AnalyzerSnapshot s = state.lastSnapshot;
        if (s == null) return;
        float ratePackets = prevPackets < 0 ? 0f : Math.max(0f, s.totalPackets - prevPackets);
        float rateCc = prevCc < 0 ? 0f : Math.max(0f, s.ccErrors - prevCc);
        prevPackets = s.totalPackets;
        prevCc = s.ccErrors;
        shift(histBitrate, (float) (s.totalKbps / 1000.0));
        shift(histPackets, ratePackets);
        shift(histCc, rateCc);
        if (histCount < HIST) histCount++;
    }

    private static void shift(float[] window, float value) {
        System.arraycopy(window, 1, window, 0, window.length - 1);
        window[window.length - 1] = value;
    }

    private void setGraphMetric(int metric) {
        if (graphMetric == metric) return;
        graphMetric = metric;
        updateGraphChips();
        state.refresh.run();
    }

    public void updateGraphChips() {
        if (chipGraphBitrate == null) return;
        chipGraphBitrate.setTextColor(ContextCompat.getColor(a,
                graphMetric == METRIC_BITRATE ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
        chipGraphPackets.setTextColor(ContextCompat.getColor(a,
                graphMetric == METRIC_PACKETS ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
        chipGraphCc.setTextColor(ContextCompat.getColor(a,
                graphMetric == METRIC_CC ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
    }

    public void updateGraphPanel(TsDemux d, ServiceInfo sel) {
        AnalyzerSnapshot s = state.lastSnapshot;
        if (graphSpark == null || s == null) return;

        float[] window;
        String unit;
        switch (graphMetric) {
            case METRIC_PACKETS:
                window = histPackets;
                unit = "PACKETS / S";
                break;
            case METRIC_CC:
                window = histCc;
                unit = "CC ERRORS / S";
                break;
            default:
                window = histBitrate;
                unit = "MBPS";
                break;
        }
        float[] series = new float[histCount];
        System.arraycopy(window, HIST - histCount, series, 0, histCount);
        graphSpark.setSeries(series);
        graphUnit.setText(unit);

        if (histCount == 0) {
            graphValue.setText("—");
            graphPeak.setText("");
            graphAvg.setText("");
        } else {
            float last = series[series.length - 1];
            float peak = 0f;
            float sum = 0f;
            for (float v : series) {
                peak = Math.max(peak, v);
                sum += v;
            }
            String fmt = graphMetric == METRIC_BITRATE ? "%.2f" : "%.0f";
            graphValue.setText(String.format(Locale.US, fmt, last));
            graphPeak.setText(String.format(Locale.US, "peak " + fmt, peak));
            graphAvg.setText(String.format(Locale.US, "avg " + fmt, sum / series.length));
        }

        String net = d.getNetworkName();
        String picked = sel == null ? "no service" : sel.virtualNumber + " " + com.pbcnx.opendtvmod.ui.ChannelAdapter.displayName(sel);
        graphLine.setText((net == null || net.isEmpty() ? "--" : net) + " · TS 0x" + AnalyzerUtil.hex4(d.getTsId())
                + " · " + picked + "\n"
                + "null " + s.nullPackets + " · TEI " + s.teiPackets + " · sync " + s.syncLosses
                + " · CC " + s.ccErrors);
    }
}
