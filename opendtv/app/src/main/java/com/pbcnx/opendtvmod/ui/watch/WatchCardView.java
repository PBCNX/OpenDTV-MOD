package com.pbcnx.opendtvmod.ui.watch;


import com.pbcnx.opendtvmod.ui.Dims;
import com.pbcnx.opendtvmod.ui.ChannelAdapter;
import com.pbcnx.opendtvmod.ui.LogoHelper;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;

/**
 * Transient on-screen info card for the watching surface.
 *
 * <p>Compact state answers "what am I watching and how strong is it?".
 * Details state adds the values that used to live in the permanent debug
 * strip: exact frequency, broadcast time, lock state, dBm/BER and codecs.
 * The card is an overlay: it never reserves layout space, and it hides itself
 * after a few seconds.</p>
 */
public final class WatchCardView extends LinearLayout {
    private static final long COMPACT_HIDE_MS = 8_000L;
    private static final long DETAILS_HIDE_MS = 15_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hide;

    private ImageView logo;
    private TextView number;
    private TextView name;
    private TextView provider;
    private TextView frequency;
    private TextView nowNext;
    private TextView signal;
    private TextView network;
    private LinearLayout chips;
    private TextView chipData;
    private TextView chipCc;
    private TextView chipEnc;
    private TextView chipEwbs;
    private MaterialButton detailsToggle;
    private View detailsContainer;
    private TextView detailsText;

    private boolean expanded;
    private OnVisibilityChangedListener visibilityListener;

    /** Lets the portrait i button show whether A+C is open. */
    public interface OnVisibilityChangedListener {
        void onInfoVisibilityChanged(boolean showing);
    }

    public WatchCardView(Context context) {
        this(context, null);
    }

    public WatchCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_watch_info);
        setElevation(Dims.dp(getContext(), 12));
        setPadding(Dims.dp(getContext(), 16), Dims.dp(getContext(), 14), Dims.dp(getContext(), 16), Dims.dp(getContext(), 14));
        setVisibility(GONE);
        setClickable(true);
        setOnClickListener(v -> toggleDetails());

        LayoutInflater.from(context).inflate(R.layout.view_watch_info, this, true);

        logo = findViewById(R.id.watch_logo);
        number = findViewById(R.id.watch_number);
        name = findViewById(R.id.watch_name);
        provider = findViewById(R.id.watch_provider);
        frequency = findViewById(R.id.watch_frequency);
        nowNext = findViewById(R.id.watch_now_next);
        signal = findViewById(R.id.watch_signal);
        network = findViewById(R.id.watch_network);
        chips = findViewById(R.id.watch_chips);
        chipData = findViewById(R.id.watch_chip_data);
        chipCc = findViewById(R.id.watch_chip_cc);
        chipEnc = findViewById(R.id.watch_chip_enc);
        chipEwbs = findViewById(R.id.watch_chip_ewbs);
        detailsToggle = findViewById(R.id.watch_details_toggle);
        detailsContainer = findViewById(R.id.watch_details_container);
        detailsText = findViewById(R.id.watch_details_text);

        detailsToggle.setOnClickListener(v -> toggleDetails());
        ImageButton close = findViewById(R.id.watch_close);
        close.setOnClickListener(v -> hide());
    }

    /** Updates the values while the card is visible or hidden. */
    public void bind(ChannelSnapshot s) {
        ServiceInfo service = s.service;
        int khz = s.khz;
        TunerSignal signalStatus = s.signal;
        long clockMs = s.clockMs;
        boolean data = s.data;
        boolean caption = s.caption;
        boolean encrypted = s.encrypted;
        boolean emergency = s.emergency;
        boolean live = s.live;
        boolean tunerPresent = s.tunerPresent;
        String nowName = s.nowName;
        String nextName = s.nextName;
        String networkName = s.networkName;
        if (service == null) {
            number.setText("--");
            name.setText(R.string.no_channel_tuned);
            provider.setText("");
            frequency.setText(R.string.watch_tune_for_details);
            nowNext.setVisibility(GONE);
            signal.setText("");
            network.setVisibility(GONE);
            setChips(false, false, false, false);
            detailsText.setText(R.string.watch_no_channel_tuned);
            return;
        }

        number.setText(service.displayNumber());
        name.setText(ChannelAdapter.displayName(service));
        provider.setText(service.provider.isEmpty() ? getContext().getString(R.string.watch_isdbt_broadcast) : service.provider);

        Drawable logoDrawable = LogoHelper.getLogo(getContext(), service.name, service.provider);
        if (logoDrawable != null) logo.setImageDrawable(logoDrawable);
        else logo.setImageResource(android.R.drawable.ic_menu_slideshow);

        String frequencyText = khz > 0
                ? com.pbcnx.opendtvmod.tuner.MhzLabel.mhz(khz)
                : "--";
        int uhf = com.pbcnx.opendtvmod.tuner.MuxSweep.planChannel(khz);
        String tot = formatTime(clockMs, false);
        String line = frequencyText;
        if (uhf >= 0) line += " · UHF " + uhf;
        if (clockMs > 0) line += " · TOT " + tot;
        frequency.setText(line);

        String guide = nowNextText(nowName, nextName);
        if (guide.isEmpty()) {
            nowNext.setVisibility(GONE);
        } else {
            nowNext.setVisibility(VISIBLE);
            nowNext.setText(guide);
        }

        switch (WatchSignalText.of(live, tunerPresent, signalStatus != null,
                signalStatus != null && signalStatus.locked)) {
            case PLAYING:
                // A capture is a source of its own: it is playing, not waiting on a tuner.
                signal.setText(R.string.watch_playing);
                break;
            case NO_TUNER:
                signal.setText(WatchSignalText.NO_TUNER);
                break;
            case TUNING:
                signal.setText(signalStatus == null
                        ? getContext().getString(R.string.tuning)
                        : getContext().getString(R.string.watch_tuning_strength, percent(signalStatus.strengthPercent)));
                break;
            default:
                signal.setText(getContext().getString(R.string.watch_locked_signal,
                        percent(signalStatus.strengthPercent), percent(signalStatus.qualityPercent)));
                break;
        }

        // Long NIT/provider names scroll instead of wrapping, and the text is
        // only replaced when it changes so the marquee does not restart.
        String nit = networkName == null || networkName.trim().isEmpty() ? "—" : networkName.trim();
        String networkLine = getContext().getString(R.string.network_line, nit,
                service.provider.isEmpty() ? "—" : service.provider);
        if (!networkLine.contentEquals(network.getText())) {
            network.setText(networkLine);
        }
        if (!network.isSelected()) network.setSelected(true);
        network.setVisibility(VISIBLE);

        setChips(data, caption, encrypted, emergency);
        detailsText.setText(buildDetails(service, khz, uhf, signalStatus, clockMs, live));
    }

    public void showCompact() {
        expanded = false;
        detailsContainer.setVisibility(GONE);
        detailsToggle.setText(R.string.watch_signal_details);
        setVisibility(VISIBLE);
        notifyVisibility();
        scheduleHide(COMPACT_HIDE_MS);
    }

    public void showDetails() {
        expanded = true;
        detailsContainer.setVisibility(VISIBLE);
        detailsToggle.setText(R.string.watch_hide_details);
        setVisibility(VISIBLE);
        notifyVisibility();
        scheduleHide(DETAILS_HIDE_MS);
    }

    public void toggleDetails() {
        if (expanded) {
            expanded = false;
            detailsContainer.setVisibility(GONE);
            detailsToggle.setText(R.string.watch_signal_details);
            scheduleHide(COMPACT_HIDE_MS);
        } else {
            showDetails();
        }
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void hide() {
        handler.removeCallbacks(hideRunnable);
        setVisibility(GONE);
        notifyVisibility();
    }

    public void setOnVisibilityChangedListener(OnVisibilityChangedListener listener) {
        visibilityListener = listener;
        if (listener != null) listener.onInfoVisibilityChanged(isShowing());
    }

    private void notifyVisibility() {
        if (visibilityListener != null) visibilityListener.onInfoVisibilityChanged(isShowing());
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(hideRunnable);
        super.onDetachedFromWindow();
    }

    private void scheduleHide(long delayMs) {
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, delayMs);
    }

    private void setChips(boolean data, boolean caption, boolean encrypted, boolean emergency) {
        chipData.setVisibility(data ? VISIBLE : GONE);
        chipCc.setVisibility(caption ? VISIBLE : GONE);
        chipEnc.setVisibility(encrypted ? VISIBLE : GONE);
        chipEwbs.setVisibility(emergency ? VISIBLE : GONE);
        chips.setVisibility(data || caption || encrypted || emergency ? VISIBLE : GONE);
    }

    private String buildDetails(ServiceInfo service, int khz, int uhf, TunerSignal signalStatus,
                                long clockMs, boolean live) {
        String frequencyText = khz > 0
                ? com.pbcnx.opendtvmod.tuner.MhzLabel.mhz(khz)
                : "--";
        if (uhf >= 0) frequencyText += " · UHF " + uhf;

        StringBuilder text = new StringBuilder();
        appendRow(text, getContext().getString(R.string.watch_row_frequency), frequencyText);
        appendRow(text, "TOT", formatTime(clockMs, true));
        if (signalStatus == null) {
            appendRow(text, getContext().getString(R.string.watch_row_signal), live ? getContext().getString(R.string.watch_playing_no_rf) : "--");
        } else {
            StringBuilder signalText = new StringBuilder(getContext().getString(signalStatus.locked
                    ? R.string.watch_locked : R.string.watch_unlocked));
            signalText.append(" · ").append(percent(signalStatus.strengthPercent));
            signalText.append(" / ").append(percent(signalStatus.qualityPercent));
            appendRow(text, getContext().getString(R.string.watch_row_signal), signalText.toString());

            StringBuilder rf = new StringBuilder();
            if (signalStatus.dbm != Integer.MIN_VALUE) {
                rf.append(signalStatus.dbm).append(" dBm");
            }
            if (!Double.isNaN(signalStatus.ber)) {
                if (rf.length() > 0) rf.append(" · ");
                rf.append("BER ").append(String.format(Locale.US, "%.1e", signalStatus.ber));
            }
            if (rf.length() > 0) appendRow(text, "RF", rf.toString());
        }

        StreamInfo video = service.firstVideo();
        appendRow(text, getContext().getString(R.string.watch_row_video), video == null ? "--"
                : video.shortCodec() + " · PID " + hex(video.pid));
        StreamInfo audio = service.firstAudio();
        appendRow(text, getContext().getString(R.string.watch_row_audio), audio == null ? "--"
                : audio.shortCodec() + " · PID " + hex(audio.pid));
        String pmt = service.pmtPid > 0 ? hex(service.pmtPid) : "--";
        appendRow(text, getContext().getString(R.string.watch_row_service), "SID " + service.serviceId + " · PMT " + pmt);
        return text.toString().trim();
    }

    private static void appendRow(StringBuilder text, String label, String value) {
        text.append(String.format(Locale.US, "%-9s %s", label, value)).append('\n');
    }

    private String nowNextText(String now, String next) {
        StringBuilder text = new StringBuilder();
        if (now != null && !now.isEmpty()) text.append(getContext().getString(R.string.now_program, now));
        if (next != null && !next.isEmpty()) {
            if (text.length() > 0) text.append(" · ");
            text.append(getContext().getString(R.string.next_program, next));
        }
        return text.toString();
    }

    private static String formatTime(long clockMs, boolean withDate) {
        if (clockMs <= 0) return "--";
        SimpleDateFormat format = new SimpleDateFormat(
                withDate ? "yyyy-MM-dd HH:mm:ss" : "HH:mm:ss", Locale.US);
        // Follow the receiver: the set adds eight hours to the TOT field whatever its descriptor says
        // (BMLPLAYER's sweep, 31 Oct). The device's zone is not consulted - the set does not consult it
        // either - so this is the fixed receiver offset, not TimeZone.getDefault().
        clockMs = com.pbcnx.opendtvmod.ts.util.TsUtil.receiverClock(clockMs);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(clockMs));
    }

    private static String percent(int value) {
        return value < 0 ? "--" : value + "%";
    }

    private static String hex(int value) {
        return String.format(Locale.US, "0x%04X", value);
    }


}
