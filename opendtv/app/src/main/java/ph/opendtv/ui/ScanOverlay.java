package ph.opendtv.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.opendtv.R;
import ph.opendtv.source.SignalStatus;

/**
 * One card for both scans: the full-band sweep and the single-frequency check.
 * The activity keeps the scan loop; this only owns the surface, the picker and
 * the live meters.
 */
public final class ScanOverlay {

    public interface Listener {
        /** Manual mode: scan the chosen frequency. */
        void onScan(int khz);

        /** Band mode: the Stop button. */
        void onStop();

        /** Sheet went away (Close, back or after Scan). */
        void onDismissed();

        /** Manual mode: the picker changed, retune so the meter follows. */
        void onFrequency(int khz);
    }

    private final Activity activity;
    private final Listener listener;
    private final Dialog dialog;
    private final TextView title, subtitle, found, lock, pick, action, close;
    private final ProgressBar bar, strengthBar, qualityBar;
    private final TextView strengthValue, qualityValue;
    private final View manualBlock;
    private final View listBlock;
    private final ScrollView listScroll;
    private final LinearLayout listContainer;
    private final ScrollView consoleScroll;
    private final TextView console;

    private List<Integer> frequencies = new ArrayList<>();
    private int selectedKhz;
    private int barMax = 1;
    private boolean manualMode;
    private boolean picking;
    private long meterSettleAt;

    public ScanOverlay(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;

        View sheet = LayoutInflater.from(activity).inflate(R.layout.view_scan_sheet, null);
        title = sheet.findViewById(R.id.scan_title);
        subtitle = sheet.findViewById(R.id.scan_subtitle);
        found = sheet.findViewById(R.id.scan_found);
        bar = sheet.findViewById(R.id.scan_bar);
        manualBlock = sheet.findViewById(R.id.scan_manual_block);
        listBlock = sheet.findViewById(R.id.scan_list_block);
        listScroll = sheet.findViewById(R.id.scan_list_scroll);
        listContainer = sheet.findViewById(R.id.scan_list);
        consoleScroll = sheet.findViewById(R.id.scan_console_scroll);
        console = sheet.findViewById(R.id.scan_console);
        lock = sheet.findViewById(R.id.scan_lock);
        pick = sheet.findViewById(R.id.scan_pick);
        strengthBar = sheet.findViewById(R.id.scan_strength_bar);
        qualityBar = sheet.findViewById(R.id.scan_quality_bar);
        strengthValue = sheet.findViewById(R.id.scan_strength_value);
        qualityValue = sheet.findViewById(R.id.scan_quality_value);
        action = sheet.findViewById(R.id.scan_action);
        close = sheet.findViewById(R.id.scan_close);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.62f;
            window.setAttributes(lp);
        }
        dialog.setOnDismissListener(d -> listener.onDismissed());

        sheet.findViewById(R.id.scan_pick_row).setOnClickListener(v -> openPicker());
        action.setOnClickListener(v -> {
            if (manualMode) {
                int khz = selectedKhz;
                dialog.dismiss();
                listener.onScan(khz);
            } else {
                listener.onStop();
            }
        });
        close.setOnClickListener(v -> {
            if (picking) closePicker();
            else dialog.dismiss();
        });
    }

    /** Full band sweep: the bar, the current frequency and the services found. */
    public void showBand(String subtitleText, int max) {
        manualMode = false;
        barMax = Math.max(1, max);
        title.setText("Auto Scan");
        subtitle.setText(subtitleText);
        found.setText("0 services found");
        console.setText("");
        bar.setMax(barMax);
        bar.setProgress(0);
        bar.setVisibility(View.VISIBLE);
        found.setVisibility(View.VISIBLE);
        consoleScroll.setVisibility(View.VISIBLE);
        manualBlock.setVisibility(View.GONE);
        listBlock.setVisibility(View.GONE);
        picking = false;
        action.setText("Stop");
        action.setVisibility(View.VISIBLE);
        close.setVisibility(View.GONE);
        show(false);
        action.requestFocus();
    }

    /**
     * @param status  the current frequency, e.g. "UHF 22 · 521.143 MHz"
     * @param done    channels already swept
     * @param count   services found so far
     * @param lines   one "7.1 | GMA" line per found service
     */
    public void updateBand(String status, int done, int count, String lines) {
        if (!dialog.isShowing() || manualMode) return;
        subtitle.setText(status + "  ·  " + Math.min(done + 1, barMax) + " of " + barMax);
        bar.setProgress(Math.min(done, barMax));
        found.setText(count + (count == 1 ? " service found" : " services found"));
        if (!lines.equals(console.getText().toString())) {
            console.setText(lines);
            // Log behaviour: keep the newest line in view as the scan runs.
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** Manual scan: pick a UHF channel, watch the lock, then scan it. */
    public void showManual(int khz, List<Integer> frequencies, String subtitleText) {
        manualMode = true;
        this.frequencies = frequencies;
        selectedKhz = khz;
        title.setText("Manual Scan");
        subtitle.setText(subtitleText);
        bar.setVisibility(View.GONE);
        found.setVisibility(View.GONE);
        consoleScroll.setVisibility(View.GONE);
        manualBlock.setVisibility(View.VISIBLE);
        listBlock.setVisibility(View.GONE);
        picking = false;
        action.setText("Scan");
        action.setVisibility(View.VISIBLE);
        close.setVisibility(View.VISIBLE);
        setManualFrequency(khz);
        show(true);
        action.requestFocus();
    }

    public void setManualFrequency(int khz) {
        selectedKhz = khz;
        pick.setText(frequencyLabel(khz));
        clearMeter();
        // The tuner keeps reporting the previous mux until it has retuned;
        // ignore the meter briefly so the sheet does not show a stale lock.
        meterSettleAt = android.os.SystemClock.elapsedRealtime() + 1600;
    }

    /** Live lock state; strength is only meaningful once the tuner has locked. */
    public void updateManual(SignalStatus status) {
        if (!dialog.isShowing() || !manualMode) return;
        if (android.os.SystemClock.elapsedRealtime() < meterSettleAt) return;
        boolean locked = status != null && status.locked;
        lock.setText(locked ? "LOCKED" : "NO LOCK");
        lock.setTextColor(activity.getColor(locked ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
        int strength = locked ? Math.max(0, Math.min(100, status.strengthPercent)) : 0;
        int quality = locked ? Math.max(0, Math.min(100, status.qualityPercent)) : 0;
        strengthBar.setProgress(strength);
        qualityBar.setProgress(quality);
        strengthValue.setText(locked ? strength + " %" : "—");
        qualityValue.setText(locked ? quality + " %" : "—");
    }

    public void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    private void show(boolean cancelable) {
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
        width = Math.min(width, dp(560));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void clearMeter() {
        lock.setText("NO LOCK");
        lock.setTextColor(activity.getColor(R.color.odtv_on_surface_variant));
        strengthBar.setProgress(0);
        qualityBar.setProgress(0);
        strengthValue.setText("—");
        qualityValue.setText("—");
    }

    /** The picker lives in the same card: swap the scan body for the channel list. */
    private void openPicker() {
        if (frequencies.isEmpty()) return;
        picking = true;
        manualBlock.setVisibility(View.GONE);
        listBlock.setVisibility(View.VISIBLE);
        title.setText("Pick a channel");
        subtitle.setText(String.format(Locale.US, "UHF %d – %d · %d frequencies",
                14 + (frequencies.get(0) - 473143) / 6000,
                14 + (frequencies.get(frequencies.size() - 1) - 473143) / 6000,
                frequencies.size()));
        action.setVisibility(View.GONE);
        close.setText("Back");
        buildList();
    }

    private void closePicker() {
        picking = false;
        listBlock.setVisibility(View.GONE);
        manualBlock.setVisibility(View.VISIBLE);
        title.setText("Manual scan");
        subtitle.setText("Pick a UHF channel, watch the lock, then scan it");
        action.setVisibility(View.VISIBLE);
        close.setText("Close");
        action.requestFocus();
    }

    private void buildList() {
        listContainer.removeAllViews();
        View selected = null;
        for (int khz : frequencies) {
            TextView row = new TextView(activity);
            row.setText(frequencyLabel(khz));
            row.setTextSize(16);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackgroundResource(R.drawable.bg_item_focusable_selector);
            row.setFocusable(true);
            row.setClickable(true);
            if (khz == selectedKhz) {
                row.setTextColor(activity.getColor(R.color.odtv_accent));
                selected = row;
            } else {
                row.setTextColor(activity.getColor(R.color.odtv_on_surface));
            }
            row.setOnClickListener(v -> {
                int picked = khz;
                setManualFrequency(picked);
                listener.onFrequency(picked);
                closePicker();
            });
            listContainer.addView(row);
        }
        final View target = selected;
        if (target != null) {
            listScroll.post(() -> target.requestFocus());
        }
    }

    private static String frequencyLabel(int khz) {
        int uhf = 14 + (khz - 473143) / 6000;
        return String.format(Locale.US, "UHF %d  ·  %.3f MHz", uhf, khz / 1000.0);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
