package ph.opendtv.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.Locale;

import ph.opendtv.R;
import ph.opendtv.source.SignalStatus;
import ph.opendtv.ts.ServiceInfo;

/**
 * Portrait-only passive status strip.
 *
 * <p>UHF/frequency on the left, four fixed text chips in the middle, signal on
 * the right. All four chips stay in the layout and are merely dimmed when
 * inactive, so a changing S/Q value cannot shuffle them.</p>
 */
public final class WatchStatusStrip extends LinearLayout {
    private TextView details;
    private TextView signal;
    private TextView chipData;
    private TextView chipCc;
    private TextView chipLock;
    private TextView chipEwbs;

    public WatchStatusStrip(Context context) {
        this(context, null);
    }

    public WatchStatusStrip(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(12), 0, dp(12), 0);
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

    public void bind(ServiceInfo service, int khz, SignalStatus signalStatus,
                     boolean data, boolean caption, boolean encrypted, boolean emergency) {
        if (service == null) {
            details.setText("No channel");
            signal.setText("--");
            setChip(chipData, false, R.color.odtv_ok, "Data broadcast");
            setChip(chipCc, false, R.color.odtv_ok, "Closed captions");
            setChip(chipLock, false, R.color.odtv_warning, "Encrypted");
            setAlertChip(false);
            return;
        }

        String line = "";
        int uhf = phChannel(khz);
        if (uhf >= 0) line = "UHF " + uhf;
        if (khz > 0) {
            if (!line.isEmpty()) line += " · ";
            line += String.format(Locale.US, "%.3f MHz", khz / 1000.0);
        }
        details.setText(line.isEmpty() ? "No channel" : line);

        if (signalStatus == null || !signalStatus.locked) {
            signal.setText("Tuning…");
        } else {
            signal.setText("S " + percent(signalStatus.strengthPercent)
                    + "  Q " + percent(signalStatus.qualityPercent));
        }

        setChip(chipData, data, R.color.odtv_ok, "Data broadcast");
        setChip(chipCc, caption, R.color.odtv_ok, "Closed captions");
        setChip(chipLock, encrypted, R.color.odtv_warning, "Encrypted");
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

    private void setChip(TextView chip, boolean active, int activeColorRes, String description) {
        int color = ContextCompat.getColor(getContext(),
                active ? activeColorRes : R.color.odtv_on_surface_variant);
        chip.setTextColor(color);
        chip.setAlpha(active ? 1f : 0.28f);
        chip.setContentDescription(description + (active ? " active" : " inactive"));
    }

    private static String percent(int value) {
        return value < 0 ? "--" : value + "%";
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
