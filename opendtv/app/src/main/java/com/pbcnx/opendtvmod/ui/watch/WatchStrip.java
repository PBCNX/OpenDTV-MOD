package com.pbcnx.opendtvmod.ui.watch;

import com.pbcnx.opendtvmod.ui.Dims;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;


import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.ts.ServiceInfo;

/**
 * Portrait-only passive status strip.
 *
 * <p>UHF/frequency on the left, four fixed text chips in the middle, signal on
 * the right. All four chips stay in the layout and are merely dimmed when
 * inactive, so a changing S/Q value cannot shuffle them.</p>
 */
public final class WatchStrip extends LinearLayout {
    private TextView details;
    private TextView signal;
    private TextView chipData;
    private TextView chipCc;
    private TextView chipLock;
    private TextView chipEwbs;
    /** What the mux cell says when the source has no frequency at all (a capture, not a tuner). */
    private String muxFallback;

    /** A capture is playing: say so in the mux cell instead of claiming no channel is tuned. */
    public void setMuxFallback(String label) {
        muxFallback = label;
    }

    public WatchStrip(Context context) {
        this(context, null);
    }

    public WatchStrip(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Dims.dp(getContext(), 12), 0, Dims.dp(getContext(), 12), 0);
        setBackgroundColor(ContextCompat.getColor(context, R.color.odtv_surface));
        setClickable(false);
        setFocusable(false);

        LayoutInflater.from(context).inflate(R.layout.view_watch_status, this, true);

        details = findViewById(R.id.strip_details);
        signal = findViewById(R.id.strip_signal);
        chipData = findViewById(R.id.strip_chip_data);
        chipCc = findViewById(R.id.strip_chip_cc);
        chipLock = findViewById(R.id.strip_chip_lock);
        chipEwbs = findViewById(R.id.strip_chip_ewbs);
    }

    /**
     * The DATA chip doubles as the way into the data broadcast: on a channel that
     * carries a page, tapping it opens the page and raises the key pad with it.
     */
    public void setDataClickListener(final Runnable action) {
        if (chipData == null) return;
        chipData.setClickable(action != null);
        chipData.setFocusable(action != null);
        chipData.setOnClickListener(action == null ? null : new View.OnClickListener() {
            @Override
            public void onClick(View v) { action.run(); }
        });
    }

    public void bind(ChannelSnapshot s) {
        ServiceInfo service = s.service;
        int khz = s.khz;
        TunerSignal signalStatus = s.signal;
        boolean data = s.data;
        boolean caption = s.caption;
        boolean encrypted = s.encrypted;
        boolean emergency = s.emergency;
        boolean live = s.live;
        if (service == null) {
            details.setText("No channel");
            signal.setText("--");
            ChipStyle.apply(chipData, getContext(), false, R.color.odtv_ok, "Data broadcast");
            ChipStyle.apply(chipCc, getContext(), false, R.color.odtv_ok, "Closed captions");
            ChipStyle.apply(chipLock, getContext(), false, R.color.odtv_warning, "Encrypted");
            setAlertChip(false);
            return;
        }

        String line = "";
        int uhf = com.pbcnx.opendtvmod.tuner.MuxSweep.planChannel(khz);
        if (uhf >= 0) line = "UHF " + uhf;
        if (khz > 0) {
            if (!line.isEmpty()) line += " · ";
            line += com.pbcnx.opendtvmod.tuner.MhzLabel.mhz(khz);
        }
        details.setText(line.isEmpty()
                ? (muxFallback == null ? "No channel" : muxFallback) : line);

        switch (WatchSignalText.of(live, s.tunerPresent, signalStatus != null,
                signalStatus != null && signalStatus.locked)) {
            case PLAYING:
                signal.setText("Playing");
                break;
            case NO_TUNER:
                signal.setText(WatchSignalText.NO_TUNER);
                break;
            case TUNING:
                signal.setText("Tuning…");
                break;
            default:
                signal.setText("S " + percent(signalStatus.strengthPercent)
                        + "  Q " + percent(signalStatus.qualityPercent));
                break;
        }

        ChipStyle.apply(chipData, getContext(), data, R.color.odtv_ok, "Data broadcast");
        ChipStyle.apply(chipCc, getContext(), caption, R.color.odtv_ok, "Closed captions");
        ChipStyle.apply(chipLock, getContext(), encrypted, R.color.odtv_warning, "Encrypted");
        setAlertChip(emergency);
    }

    /**
     * EWBS wears a filled pill while it is live, so its label has to stay white - the
     * generic setChip() would paint it the same red as the fill and the word vanished.
     * The background and text colour both key off state_selected here.
     */
    private void setAlertChip(boolean active) {
        chipEwbs.setSelected(active);
        chipEwbs.setAlpha(active ? 1f : 0.28f);
        chipEwbs.setContentDescription("Emergency broadcast" + (active ? " active" : " inactive"));
    }

    private static String percent(int value) {
        return value < 0 ? "--" : value + "%";
    }


}
