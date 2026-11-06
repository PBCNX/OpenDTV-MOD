package com.pbcnx.opendtvmod.analyzer;


import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.ts.AnalyzerSnapshot;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;


/** The Signal tab: the board, the card that replaces it in landscape, and the signal meters. */
public final class SignalPanel implements TabPanel {
    private android.widget.TextView boardState;
    private android.widget.TextView boardChannel;
    private android.widget.TextView boardMeta;
    private android.widget.TextView boardStrengthVal;
    private android.widget.TextView boardQualityVal;
    private android.widget.TextView boardBitrate;
    private android.widget.TextView boardDot;
    private android.widget.TextView boardChipData;
    private android.widget.TextView boardChipCc;
    private android.widget.TextView boardChipLock;
    private android.widget.TextView boardChipEwbs;
    private android.widget.TextView sigStrengthVal;
    private android.widget.TextView sigQualityVal;
    private android.widget.TextView sigChipData;
    private android.widget.TextView sigChipCc;
    private android.widget.TextView sigChipLock;
    private android.widget.TextView sigChipEwbs;
    private android.view.View sigStrengthFill;
    private android.view.View sigStrengthRest;
    private android.view.View sigQualityFill;
    private android.view.View sigQualityRest;
    private android.view.View sigChips;
    private com.pbcnx.opendtvmod.ui.SparklineView signalSpark;
    private boolean boardGone;
    private android.widget.TextView stripRight;
    private int lastSignalFit = -1;
    private android.widget.TextView tileBitrate;
    private android.widget.TextView tilePackets;
    private android.widget.TextView tileNull;
    private android.widget.TextView tileCc;
    private android.widget.TextView tileTei;
    private android.widget.TextView tileSync;
    private android.widget.TextView signalLine1;
    private android.widget.TextView signalLine2;
    private android.widget.TextView signalLine3;


    private final AnalyzerActivity a;
    private final AnalyzerState state;
    /** The panel owns its root view; the activity asks for it. */
    private View root;

    public View view() { return root; }
    /** The Graph tab's 30 s window, shown here as the glance-sized sparkline. */
    private final GraphPanel graph;

    public SignalPanel(AnalyzerActivity activity, AnalyzerState state, GraphPanel graph) {
        this.a = activity;
        this.state = state;
        this.graph = graph;
    }

    @Override
    public void update() {
        List<ServiceInfo> services = state.demux.getServices();
        ServiceInfo sel = AnalyzerUtil.findService(services, state.selectedServiceId);
        updateInfoPanel(sel);
        updateSignal();
        updateStatusChips(state.demux, sel);
        if (state.currentTab == AnalyzerState.TAB_SIGNAL) {
            fitSignalPanel();
            if (signalSpark != null && signalSpark.getVisibility() == View.VISIBLE) {
                signalSpark.setSeries(graph.bitrateSeries());
            }
            updateSignalTiles(services, sel);
        }
    }

    @Override
    public void bindViews() {
        boardState = a.findViewById(R.id.board_state);
        boardDot = a.findViewById(R.id.board_dot);
        boardChannel = a.findViewById(R.id.board_channel);
        boardStrengthVal = a.findViewById(R.id.board_strength_val);
        boardChipData = a.findViewById(R.id.board_chip_data);
        boardChipCc = a.findViewById(R.id.board_chip_cc);
        boardChipLock = a.findViewById(R.id.board_chip_lock);
        boardChipEwbs = a.findViewById(R.id.board_chip_ewbs);
        boardQualityVal = a.findViewById(R.id.board_quality_val);
        boardBitrate = a.findViewById(R.id.board_bitrate);
        boardMeta = a.findViewById(R.id.board_meta);
        stripRight = a.findViewById(R.id.strip_right);
        root = a.findViewById(R.id.signal_panel);
        tileBitrate = a.findViewById(R.id.tile_bitrate);
        tilePackets = a.findViewById(R.id.tile_packets);
        tileNull = a.findViewById(R.id.tile_null);
        tileCc = a.findViewById(R.id.tile_cc);
        tileTei = a.findViewById(R.id.tile_tei);
        tileSync = a.findViewById(R.id.tile_sync);
        signalLine1 = a.findViewById(R.id.signal_line1);
        signalLine2 = a.findViewById(R.id.signal_line2);
        signalLine3 = a.findViewById(R.id.signal_line3);

        // Where the board is not part of the layout (both landscapes), the Signal card
        // takes over its numbers, its bars and its four words.
        sigStrengthVal = a.findViewById(R.id.sig_strength_val);
        sigQualityVal = a.findViewById(R.id.sig_quality_val);
        sigStrengthFill = a.findViewById(R.id.sig_strength_fill);
        sigStrengthRest = a.findViewById(R.id.sig_strength_rest);
        sigQualityFill = a.findViewById(R.id.sig_quality_fill);
        sigQualityRest = a.findViewById(R.id.sig_quality_rest);
        sigChipData = a.findViewById(R.id.sig_chip_data);
        sigChipCc = a.findViewById(R.id.sig_chip_cc);
        sigChipLock = a.findViewById(R.id.sig_chip_lock);
        sigChipEwbs = a.findViewById(R.id.sig_chip_ewbs);
        signalSpark = a.findViewById(R.id.signal_spark);
        boardGone = boardChipData == null;
        showIfPresent(a.findViewById(R.id.sig_meters), boardGone);
        sigChips = a.findViewById(R.id.sig_chips);
        showIfPresent(sigChips, boardGone);
        showIfPresent(signalSpark, boardGone);
    }

    public void updateSignalTiles(List<ServiceInfo> services, ServiceInfo sel) {
        AnalyzerSnapshot s = state.lastSnapshot;
        if (s == null || tileBitrate == null) return;
        tileBitrate.setText(String.format(Locale.US, "%.1f", s.totalKbps / 1000.0));
        tilePackets.setText(String.valueOf(s.totalPackets));
        tileNull.setText(String.valueOf(s.nullPackets));
        tileCc.setText(String.valueOf(s.ccErrors));
        tileTei.setText(String.valueOf(s.teiPackets));
        tileSync.setText(String.valueOf(s.syncLosses));
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        signalLine1.setText("sample " + stamp + " - " + services.size() + " services - " + s.rows.size() + " PIDs");
        signalLine2.setText(sel == null ? "no service selected"
                : "selected " + sel.virtualNumber + " " + com.pbcnx.opendtvmod.ui.ChannelAdapter.displayName(sel));
        if (signalLine3 != null) signalLine3.setText(playerDiag());
    }

    public void updateInfoPanel(ServiceInfo sel) {
        String net = state.demux.getNetworkName();
        if (net == null || net.isEmpty()) net = (sel != null && !sel.provider.isEmpty()) ? sel.provider : "--";

        if (sel == null) {
            AnalyzerUtil.setText(boardChannel, "");
            AnalyzerUtil.setText(boardMeta, "TS -- · NIT " + net + " · no service yet");
            return;
        }
        AnalyzerUtil.setText(boardChannel, sel.virtualNumber + "  "
                + (sel.name.isEmpty() ? "Service " + sel.serviceId : sel.name));

        StreamInfo v = sel.firstVideo();
        StreamInfo a = sel.firstAudio();
        String codec = (v == null ? "no video" : v.shortCodec()) + " / "
                + (a == null ? "--" : a.shortCodec()) + (sel.encrypted ? "  [CA]" : "");
        // Read the demux's own record of what the AAC reader picked; it is per-tune, so a previous
        // mux's transport cannot leak into this one.
        int aacHint = state.demux == null ? -1 : state.demux.aacTransportHint();
        String payload = aacHint == 1 ? "ADTS" : aacHint == 0 ? "LATM" : null;
        if (a != null && a.shortCodec().toUpperCase(Locale.US).contains("AAC") && payload != null) {
            codec += " (" + payload + ")";
        }
        // Same rule as the channel rows: a provider that repeats the network name says
        // nothing, and on the phone's 454 dp board it was what pushed the codecs into
        // an ellipsis. Dropped there, the line fits.
        StringBuilder meta = new StringBuilder(
                String.format(Locale.US, "TS 0x%04X \u00b7 NIT %s", sel.serviceId, net));
        if (!sel.provider.isEmpty() && !sel.provider.equalsIgnoreCase(net)) {
            meta.append(" \u00b7 Provider ").append(sel.provider);
        }
        meta.append(" \u00b7 ").append(codec);
        AnalyzerUtil.setText(boardMeta, meta);
    }

    public void updateSignal() {
        int strength = 0;
        int quality = 0;
        boolean locked = false;
        String detail;
        if (state.source == null) {
            detail = "no source";
        } else if (state.source.getTunerSignal() == null) {
            detail = state.source.supportsTuning() ? "waiting for tuner" : "file input";
        } else {
            TunerSignal st = state.source.getTunerSignal();
            strength = Math.max(st.strengthPercent, 0);
            quality = st.locked ? Math.max(st.qualityPercent, 0) : 0;
            locked = st.locked;
        String dropped = "";
            detail = String.format(Locale.US, "%s · BER %s%s",
                    st.dbm == Integer.MIN_VALUE ? "-- dBm" : st.dbm + " dBm",
                    Double.isNaN(st.ber) ? "n/a" : String.format(Locale.US, "%.1e", st.ber), dropped);
        }
        boolean file = state.source != null && !state.source.supportsTuning();
        AnalyzerUtil.setText(boardState, state.source == null ? "NO TUNER" : file ? "FILE" : locked ? "LOCKED" : "NO LOCK");
        if (boardDot != null) {
            boardDot.setTextColor(ContextCompat.getColor(a,
                    state.source == null ? R.color.odtv_on_surface_variant
                            : file ? R.color.odtv_accent
                            : locked ? R.color.odtv_ok : R.color.odtv_warning));
        }
        AnalyzerUtil.setText(boardStrengthVal, strength + " %");
        AnalyzerUtil.setText(boardQualityVal, quality + " %");
        // Landscape has no board: the same two numbers and bars live in the Signal card.
        if (sigStrengthVal != null) {
            sigStrengthVal.setText(strength + " %");
            sigQualityVal.setText(quality + " %");
            setMeter(sigStrengthFill, sigStrengthRest, strength);
            setMeter(sigQualityFill, sigQualityRest, quality);
        }
        if (stripRight != null) stripRight.setText(detail);
        if (state.lastSnapshot != null && boardBitrate != null) {
            double mbps = state.lastSnapshot.totalKbps / 1000.0;
            boardBitrate.setText(mbps <= 0 ? "—" : String.format(Locale.US, "%.1f", mbps));
        }
    }

    /* Board views only exist where the board is in the layout (portrait). */

    /**
     * The Signal panel does not scroll, so a line that does not fit used to be drawn
     * half a glyph tall at the card's edge (133 dp on the rotated phone). Hide the
     * lines that do not fit instead, from the bottom up: the counters line and the
     * selected-service line matter more than the player diagnostic.
     */
    public void fitSignalPanel() {
        if (state.contentFrame == null || root == null) return;
        if (root.getVisibility() != View.VISIBLE) return;
        int avail = state.contentFrame.getHeight()
                - state.contentFrame.getPaddingTop() - state.contentFrame.getPaddingBottom();
        int width = state.contentFrame.getWidth()
                - state.contentFrame.getPaddingLeft() - state.contentFrame.getPaddingRight();
        if (avail <= 0 || width <= 0 || avail == lastSignalFit) return;
        lastSignalFit = avail;

        // Dropped in this order when the card is short: the sparkline is a glance, the
        // player diag is the least useful line, the four words are one row. The counters
        // and the S/Q meters always stay - they are what the tab is for.
        View[] hideable = boardGone
                ? new View[]{signalSpark, signalLine3, signalLine2, signalLine1, sigChips}
                : new View[]{signalLine3, signalLine2, signalLine1};
        for (View v : hideable) if (v != null) v.setVisibility(View.VISIBLE);
        for (int i = 0; i < hideable.length; i++) {
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (root.getMeasuredHeight() <= avail) return;
            if (hideable[i] != null) hideable[i].setVisibility(View.GONE);
        }
    }

    /** Board status chips, same words as the watch strip: DATA / CC / LOCK / EWBS. */
    public void updateStatusChips(TsDemux d, ServiceInfo sel) {
        boolean data = false;
        boolean encrypted = false;
        if (sel != null) {
            encrypted = sel.encrypted;
            for (StreamInfo s : sel.streams) data |= s.isDatacast();
        }
        boolean captionsEnabled = a.getSharedPreferences(com.pbcnx.opendtvmod.App.PREFS, android.content.Context.MODE_PRIVATE).getBoolean("closed_captions", true);
        // The chip answers "does this service show captions", not "is my setting on": with no
        // service selected the answer is no. Reading the setting alone lit it before a channel
        // existed (owner report, 2026-11-06).
        boolean captions = CaptionChip.active(captionsEnabled, sel == null ? null : sel.streams);
        boolean ewbs = d.isEwbsActive() || d.ewbsEverSeen();
        setChip(boardChipData, data, R.color.odtv_ok);
        setChip(boardChipCc, captions, R.color.odtv_ok);
        setChip(boardChipLock, encrypted, R.color.odtv_warning);
        setChip(boardChipEwbs, ewbs, R.color.odtv_live);
        // The Signal card carries the same four words where the board is out of the layout.
        setChip(sigChipData, data, R.color.odtv_ok);
        setChip(sigChipCc, captions, R.color.odtv_ok);
        setChip(sigChipLock, encrypted, R.color.odtv_warning);
        setChip(sigChipEwbs, ewbs, R.color.odtv_live);
    }

    /** A weight-based bar: the fill takes pct of the track, the rest holds the remainder. */
    private void setMeter(View fill, View rest, int pct) {
        if (fill == null || rest == null) return;
        float f = Math.max(0f, Math.min(100f, pct));
        LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) fill.getLayoutParams();
        LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) rest.getLayoutParams();
        a.weight = Math.max(0.001f, f);
        b.weight = Math.max(0.001f, 100f - f);
        fill.setLayoutParams(a);
        rest.setLayoutParams(b);
    }

    /** Inactive chips stay present and dim, exactly like the watching strip. */
    private void setChip(TextView chip, boolean active, int activeColour) {
        if (chip == null) return;
        chip.setTextColor(ContextCompat.getColor(a, active ? activeColour : R.color.odtv_on_surface_variant));
        chip.setAlpha(active ? 1f : 0.28f);
    }

    /** One line about what ExoPlayer is doing: helps tell "no data" from "no decoder" from "decoder too slow". */
    private String playerDiag() {
        if (state.selectedServiceId == -1) return "";
        String[] states = {"idle", "buffering", "ready", "ended"};
        int ps = state.player.getPlaybackState();
        androidx.media3.common.Format vf = state.player.getVideoFormat();
        androidx.media3.exoplayer.DecoderCounters dc = state.player.getVideoDecoderCounters();
        StringBuilder sb = new StringBuilder("Player ").append(states[Math.max(0, Math.min(3, ps - 1))]);
        if (vf == null) {
            sb.append("  video: none yet");
        } else {
            sb.append("  video: ").append(vf.sampleMimeType).append(' ').append(vf.width).append('x').append(vf.height);
        }
        if (dc != null) {
            dc.ensureUpdated();
            sb.append("  rendered ").append(dc.renderedOutputBufferCount).append(" dropped ").append(dc.droppedBufferCount);
        }
        return sb.toString();
    }

    /**
     * The rotated phone gives the status board a 454 dp column instead of the 920 dp the
     * set gives it. With the bitrate group on that line the service name has to ellipsise,
     * so the group is dropped there: the Signal card already shows the bitrate as tile one.
     * Portrait and the TV keep it (the id is absent from the portrait board).
     */
    public void trimBoardForNarrowColumn() {
        View group = a.findViewById(R.id.board_bitrate_group);
        if (group == null) return;
        android.content.res.Configuration c = a.getResources().getConfiguration();
        boolean shortLandscape =
                c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        && c.screenHeightDp < 500;
        if (shortLandscape) group.setVisibility(View.GONE);
    }

    private void showIfPresent(View v, boolean visible) {
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
