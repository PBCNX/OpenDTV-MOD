package ph.opendtv.ui;

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

import ph.opendtv.R;
import ph.opendtv.source.SignalStatus;
import ph.opendtv.ts.ServiceInfo;
import ph.opendtv.ts.StreamInfo;

/**
 * Transient on-screen info card for the watching surface.
 *
 * <p>Compact state answers "what am I watching and how strong is it?".
 * Details state adds the values that used to live in the permanent debug
 * strip: exact frequency, broadcast time, lock state, dBm/BER and codecs.
 * The card is an overlay: it never reserves layout space, and it hides itself
 * after a few seconds.</p>
 */
public final class WatchInfoView extends LinearLayout {
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

    public WatchInfoView(Context context) {
        this(context, null);
    }

    public WatchInfoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_watch_info);
        setElevation(dp(12));
        setPadding(dp(16), dp(14), dp(16), dp(14));
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
    public void bind(ServiceInfo service, int khz, SignalStatus signalStatus, long clockMs,
                     boolean data, boolean caption, boolean encrypted, boolean emergency,
                     String nowName, String nextName, String networkName) {
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

        String serviceNumber = service.virtualNumber;
        if (serviceNumber == null || serviceNumber.isEmpty() || "--".equals(serviceNumber)) {
            serviceNumber = String.valueOf(service.serviceId);
        }
        number.setText(serviceNumber);
        name.setText(ChannelAdapter.displayName(service));
        provider.setText(service.provider.isEmpty()
                ? getContext().getString(R.string.watch_isdbt_broadcast) : service.provider);

        Drawable logoDrawable = LogoHelper.getLogo(getContext(), service.name, service.provider);
        if (logoDrawable != null) logo.setImageDrawable(logoDrawable);
        else logo.setImageResource(android.R.drawable.ic_menu_slideshow);

        String frequencyText = khz > 0
                ? String.format(Locale.US, "%.3f MHz", khz / 1000.0)
                : "--";
        int uhf = phChannel(khz);
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

        if (signalStatus == null) {
            signal.setText(R.string.tuning);
        } else if (!signalStatus.locked) {
            signal.setText(getContext().getString(R.string.watch_tuning_strength,
                    percent(signalStatus.strengthPercent)));
        } else {
            signal.setText(getContext().getString(R.string.watch_locked_signal,
                    percent(signalStatus.strengthPercent), percent(signalStatus.qualityPercent)));
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
        detailsText.setText(buildDetails(service, khz, uhf, signalStatus, clockMs));
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

    private String buildDetails(ServiceInfo service, int khz, int uhf, SignalStatus signalStatus,
                                long clockMs) {
        String frequencyText = khz > 0
                ? String.format(Locale.US, "%.3f MHz", khz / 1000.0)
                : "--";
        if (uhf >= 0) frequencyText += " · UHF " + uhf;

        StringBuilder text = new StringBuilder();
        Context c = getContext();
        appendRow(text, c.getString(R.string.watch_row_frequency), frequencyText);
        appendRow(text, "TOT", formatTime(clockMs, true));
        if (signalStatus == null) {
            appendRow(text, c.getString(R.string.watch_row_signal), "--");
        } else {
            StringBuilder signalText = new StringBuilder(
                    c.getString(signalStatus.locked ? R.string.watch_locked : R.string.watch_unlocked));
            signalText.append(" · ").append(percent(signalStatus.strengthPercent));
            signalText.append(" / ").append(percent(signalStatus.qualityPercent));
            appendRow(text, c.getString(R.string.watch_row_signal), signalText.toString());

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
        appendRow(text, c.getString(R.string.watch_row_video), video == null ? "--"
                : video.shortCodec() + " · PID " + hex(video.pid));
        StreamInfo audio = service.firstAudio();
        appendRow(text, c.getString(R.string.watch_row_audio), audio == null ? "--"
                : audio.shortCodec() + " · PID " + hex(audio.pid));
        String pmt = service.pmtPid > 0 ? hex(service.pmtPid) : "--";
        appendRow(text, c.getString(R.string.watch_row_service), "SID " + service.serviceId + " · PMT " + pmt);
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
        // TOT arrives as UTC and is shown in the device's zone (PH +08, JP +09) - the same
        // call the mod branch made for its BmlStatusBar clock.
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(clockMs));
    }

    private static String percent(int value) {
        return value < 0 ? "--" : value + "%";
    }

    private static String hex(int value) {
        return String.format(Locale.US, "0x%04X", value);
    }

    private static int phChannel(int khz) {
        if (khz <= 0) return -1;
        int channel = 14 + Math.round((khz - 473143) / 6000.0f);
        return channel >= 14 && channel <= 51 ? channel : -1;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
