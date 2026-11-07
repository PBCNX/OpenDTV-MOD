package com.pbcnx.opendtvmod.analyzer;

import com.pbcnx.opendtvmod.ts.pipe.ServiceFilter;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.media3.exoplayer.source.MediaSource;

import java.util.List;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;


/** The Services tab: the service rows and the playback switch they drive. */
public final class ServicesPanel implements TabPanel {
    private final java.util.Map<Integer, android.view.View> serviceChecks = new java.util.HashMap<>();
    private android.widget.LinearLayout servicesList;
    private final List<Integer> spinnerSids = new java.util.ArrayList<>();


    private final AnalyzerActivity a;
    private final AnalyzerState state;
    private final StatusSink status;
    /** The panel owns its root view; the activity asks for it. */
    private View root;

    public View view() { return root; }

    public ServicesPanel(AnalyzerActivity activity, AnalyzerState state, StatusSink status) {
        this.a = activity;
        this.state = state;
        this.status = status;
    }

    public void bindViews() {
        root = a.findViewById(R.id.services_panel);
        servicesList = a.findViewById(R.id.services_list);
    }

    @Override
    public void update() {
        updateServiceList(state.demux.getServices());
    }

    public void updateServiceList(List<ServiceInfo> services) {
        StringBuilder key = new StringBuilder();
        List<ServiceInfo> shown = new java.util.ArrayList<>();
        for (ServiceInfo s : services) {
            if (!s.pmtSeen || s.streams.isEmpty()) continue;
            shown.add(s);
            key.append(s.serviceId).append(':').append(s.name).append(':').append(s.virtualNumber)
                    .append(s.encrypted ? ":CA" : "").append(';');
        }
        spinnerSids.clear();
        for (ServiceInfo s : shown) spinnerSids.add(s.serviceId);

        if (!key.toString().equals(state.serviceListKey)) {
            state.serviceListKey = key.toString();
            if (servicesList != null) {
                servicesList.removeAllViews();
                serviceChecks.clear();
                float dp = a.getResources().getDisplayMetrics().density;
                for (int i = 0; i < shown.size(); i++) {
                    final ServiceInfo s = shown.get(i);
                    View row = a.getLayoutInflater().inflate(R.layout.view_choice_row, servicesList, false);
                    TextView label = row.findViewById(R.id.choice_label);
                    StreamInfo v = s.firstVideo();
                    StreamInfo a = s.firstAudio();
                    label.setText(s.virtualNumber + "  "
                            + com.pbcnx.opendtvmod.ui.ChannelAdapter.displayName(s)
                            + "   " + (v == null ? "no video" : v.shortCodec())
                            + (a == null ? "" : " / " + a.shortCodec())
                            + (s.encrypted ? "  [CA]" : ""));
                    label.setTextSize(14);
                    View check = row.findViewById(R.id.choice_check);
                    serviceChecks.put(s.serviceId, check);
                    check.setVisibility(s.serviceId == state.selectedServiceId ? View.VISIBLE : View.GONE);
                    row.setOnClickListener(v2 -> selectService(s));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    if (i > 0) lp.topMargin = Math.round(6 * dp);
                    servicesList.addView(row, lp);
                }
                if (shown.isEmpty()) {
                    TextView note = new TextView(a);
                    note.setText("No services yet. Wait for the PMT, or scan.");
                    note.setTextColor(ContextCompat.getColor(a, R.color.odtv_on_surface_variant));
                    servicesList.addView(note);
                }
            }
        }

        if (state.selectedServiceId == -1) {
            for (ServiceInfo s : shown) {
                if (s.hasVideo()) { selectService(s); break; }
            }
        }
        // The check mark follows the current service without rebuilding the rows (a rebuild
        // would throw away the TV's focus every time a service is picked).
        for (java.util.Map.Entry<Integer, View> e : serviceChecks.entrySet()) {
            e.getValue().setVisibility(e.getKey() == state.selectedServiceId ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------ playback

    private void selectService(ServiceInfo svc) {
        state.selectedServiceId = svc.serviceId;
        // Auto's per-mux decoder memory is consulted when this frequency opens, exactly as the
        // watch path does it, so a mux that needed software video starts on software video.
        a.applyDecoderMemoryFor(state.currentTunedKhz);
        state.player.stop();
        state.pipe.clear();
        state.captionOverlay.reset();
        state.serviceFilter = new ServiceFilter(state.pipe, state.demux.getTsId(), svc).setCaptionSink(state.captionOverlay);
        a.claimTuner();   // the sink goes on the hub-owned demux, so the analyzer must own it
        state.demux.setPacketSink(state.serviceFilter);
        // The probe is a diagnostic, so its first line appears as soon as there is a service:
        // "not seen · the PMT declares no caption stream" is a useful answer, not an empty one.
        status.logCaptionProbe(true);
        MediaSource ms = com.pbcnx.opendtvmod.player.PlayerFactory.liveSource(state.pipe, () -> state.demux);
        state.player.setMediaSource(ms);
        state.player.prepare();
        state.player.setPlayWhenReady(true);
    }
}
