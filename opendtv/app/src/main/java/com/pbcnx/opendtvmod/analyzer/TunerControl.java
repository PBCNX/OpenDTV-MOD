package com.pbcnx.opendtvmod.analyzer;

import com.pbcnx.opendtvmod.ui.Dims;

import com.pbcnx.opendtvmod.analyzer.AnalyzerState.Mux;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;


import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.tuner.TunerHub;


/** Tuning: the band order, the channel picker, the mux chips and the per-tick mux label. */
public final class TunerControl implements TabPanel {
    private android.widget.TextView muxCurrent;
    private android.widget.TextView muxLabel;
    private android.widget.LinearLayout muxChipRow;


    /** The band order the picker shows, as a flat list for the channel keys. */
    private static final int UHF_FIRST = 14;
    private static final int UHF_LAST = 69;
    private static final int VHF_FIRST = 7;
    private static final int VHF_LAST = 13;

    private final AnalyzerActivity a;
    private final AnalyzerState state;
    private final StatusSink status;
    /** Retunes go through the hub so its demux/currentKhz stay the one truth. */
    private TunerHub hub;

    public TunerControl(AnalyzerActivity activity, AnalyzerState state, StatusSink status) {
        this.a = activity;
        this.state = state;
        this.status = status;
        state.tuner = this;   // the panels that change a setting repaint through this
    }

    private TunerHub hub() {
        if (hub == null) hub = com.pbcnx.opendtvmod.App.hub(a.getApplicationContext());
        return hub;
    }

    public void bindViews() {
        muxCurrent = a.findViewById(R.id.analyzer_mux_current);
        // DOWN from the chip must always land somewhere. What the chip sat above depends on the
        // tab: the Signal panel is empty, the PIDs table is not, so focus search found a candidate
        // on some tabs and nothing on others. Go to the tab that is actually selected instead.
        if (muxCurrent != null) {
            muxCurrent.setOnKeyListener((v, keyCode, ev) -> {
                if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    View tab = a.findViewById(AnalyzerState.TAB_LABEL_IDS[state.currentTab]);
                    if (tab != null) {
                        tab.requestFocus();
                        return true;
                    }
                }
                return false;
            });
        }
        muxChipRow = a.findViewById(R.id.mux_chip_row);
        muxLabel = a.findViewById(R.id.strip_mux);
        // Absent from the rotated phone's layout, where the chip is a status line instead.
        if (muxCurrent != null) muxCurrent.setOnClickListener(v -> showChannelPicker());
    }

    // ------------------------------------------------------------------ tuning: scan / manual

    private static String channelLabel(int khz) {
        return com.pbcnx.opendtvmod.tuner.MuxSweep.label(khz);
    }

    /** The frequency the board is showing: what the user last picked, or the selected mux. */
    public int currentKhz() {
        if (state.currentTunedKhz > 0) return state.currentTunedKhz;
        if (state.currentMuxIndex >= 0 && state.currentMuxIndex < state.muxes.size()) return state.muxes.get(state.currentMuxIndex).khz;
        return -1;
    }

    private boolean canTune() {
        if (state.source == null || !state.source.supportsTuning()) {
            status.setStatus("Connect the USB tuner first (USB button).");
            return false;
        }
        if (!state.source.canTune()) {
            status.setStatus("Tuner is still initialising, please wait ...");
            return false;
        }
        return true;
    }

    /* Switches to a multiplex: fresh demux and player, then tune (off the UI thread; lock wait takes seconds). */
    /* Switches to a multiplex through the hub (off the UI thread; lock wait takes seconds). */

    /**
     * One retune path: reset the player/pipe, tune through TunerHub and adopt the hub's demux. The
     * manual picker additionally remembers a newly locked multiplex.
     */
    private void retune(final int khz, final boolean manual) {
        state.currentTunedKhz = khz;
        state.selectedServiceId = -1;
        state.serviceListKey = "";
        state.player.stop();
        state.player.clearMediaItems();
        state.pipe.clear();
        status.setStatus("Tuning " + channelLabel(khz) + " ...");
        new Thread(() -> {
                hub().acquire(a);
                final boolean ok = hub().ensureTuned(khz, a::isDestroyed);
                if (ok) {
                    state.pipe.clear();
                    state.demux = hub().demux();
                    state.source = hub().source();
                }
                state.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            String why = hub().failure();
                            status.setStatus(why == null
                                    ? "No lock on " + channelLabel(khz)
                                    : "No lock on " + channelLabel(khz) + " - " + why);
                            return;
                        }
                        status.setStatus("Locked " + channelLabel(khz));
                        if (!manual) return;
                        int at = -1;
                        for (int i = 0; i < state.muxes.size(); i++) if (state.muxes.get(i).khz == khz) at = i;
                        if (at < 0) {
                            state.muxes.add(new Mux(khz, channelLabel(khz) + "  (manual)"));
                            at = state.muxes.size() - 1;
                            saveMuxes();
                        }
                        state.currentMuxIndex = at;
                        refreshMuxSpinner(at);
                    }
                });
            }, manual ? "ManualTune" : "Tune").start();
    }

    private void tuneTo(final int khz) {
        if (!canTune()) return;
        retune(khz, false);
    }

    // ------------------------------------------------------------------ channels

    public static int uhfKhz(int channel) {
        return com.pbcnx.opendtvmod.tuner.MuxSweep.khz(channel);
    }

    /** The band order the picker shows, as a flat list for the channel keys. */

    private static int bandCount() {
        return UHF_LAST - UHF_FIRST + 1 + VHF_LAST - VHF_FIRST + 1;
    }

    private static int bandKhz(int index) {
        int uhf = UHF_LAST - UHF_FIRST + 1;
        return index < uhf ? uhfKhz(UHF_FIRST + index) : vhfKhz(VHF_FIRST + index - uhf);
    }

    private static int bandIndex(int khz) {
        for (int i = 0; i < bandCount(); i++) {
            if (bandKhz(i) == khz) return i;
        }
        return -1;
    }

    private static int vhfKhz(int channel) {
        return 177143 + 6000 * (channel - 7);
    }

    private static String pickerLabel(int channel, int khz) {
        return "CH " + channel + " \u00B7 " + com.pbcnx.opendtvmod.tuner.MhzLabel.mhz(khz);
    }

    /**
     * Every channel the tuner can be asked for, in the owner's order: UHF 14-51,
     * a rule, UHF 52-69, a rule, then VHF 7-13. No "seen on this tuner" group -
     * the menu is the same list on every device.
     */
    public void showChannelPicker() {
        if (!canTune()) return;

        View listRoot = a.getLayoutInflater().inflate(R.layout.view_channel_list, null);
        android.widget.ScrollView scroll = listRoot.findViewById(R.id.channel_list_scroll);
        LinearLayout list = listRoot.findViewById(R.id.channel_list);

        final AlertDialog[] holder = new AlertDialog[1];
        final View[] currentRow = new View[1];
        final int now = currentKhz();

        list.addView(sectionHeader("UHF"));
        list.addView(sectionRule());
        addChannelRows(list, 14, 51, holder, now, currentRow);
        list.addView(sectionRule());
        addChannelRows(list, 52, 69, holder, now, currentRow);
        list.addView(sectionRule());
        list.addView(sectionHeader("VHF"));
        list.addView(sectionRule());
        addChannelRows(list, 7, 13, holder, now, currentRow);

        holder[0] = new MaterialAlertDialogBuilder(a)
                .setTitle("Channels")
                .setView(listRoot)
                .setNegativeButton("Close", null)
                .create();
        holder[0].show();
        if (holder[0].getWindow() != null) {
            holder[0].getWindow().setLayout(
                    Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.8f));
        }
        scroll.post(new Runnable() {
            @Override
            public void run() {
                // Instant, not smooth: an animating list swallows the first tap.
                if (currentRow[0] != null) {
                    scroll.scrollTo(0, Math.max(0, currentRow[0].getTop() - Dims.dp(a, 48)));
                }
            }
        });
    }

    private void addChannelRows(LinearLayout list, int from, int to, final AlertDialog[] holder,
                                int now, View[] currentRow) {
        for (int channel = from; channel <= to; channel++) {
            final int khz = channel <= 13 ? vhfKhz(channel) : uhfKhz(channel);
            View row = a.getLayoutInflater().inflate(R.layout.view_choice_row, list, false);
            row.setMinimumHeight(Dims.dp(a, 46));
            TextView label = row.findViewById(R.id.choice_label);
            label.setText(pickerLabel(channel, khz));
            label.setTextSize(14);
            boolean current = khz == now;
            row.findViewById(R.id.choice_check).setVisibility(current ? View.VISIBLE : View.GONE);
            if (current) currentRow[0] = row;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    manualTune(khz);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Dims.dp(a, 4);
            list.addView(row, lp);
        }
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(10);
        tv.setLetterSpacing(0.16f);
        tv.setTextColor(ContextCompat.getColor(a, R.color.odtv_on_surface_variant));
        tv.setPadding(Dims.dp(a, 6), Dims.dp(a, 10), Dims.dp(a, 6), Dims.dp(a, 6));
        return tv;
    }

    private View sectionRule() {
        View v = new View(a);
        v.setBackgroundColor(ContextCompat.getColor(a, R.color.odtv_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dims.dp(a, 1));
        lp.topMargin = Dims.dp(a, 2);
        lp.bottomMargin = Dims.dp(a, 8);
        v.setLayoutParams(lp);
        return v;
    }

    private void manualTune(final int khz) {
        retune(khz, true);
    }

    private void refreshMuxSpinner(int select) {
        updateMuxLabel(select);
        if (muxChipRow == null) return;   // the phone has no chip row: the picker replaces it
        muxChipRow.removeAllViews();
        float d = a.getResources().getDisplayMetrics().density;
        for (int i = 0; i < state.muxes.size(); i++) {
            final int index = i;
            TextView chip = new TextView(a);
            chip.setText(muxShort(state.muxes.get(i).khz));
            chip.setTextSize(13);
            chip.setFocusable(true);
            chip.setClickable(true);
            // Always the focusable shape: a plain chip has no focused state to paint.
            chip.setBackgroundResource(R.drawable.bg_scan_chip_focusable);
            chip.setTextColor(ContextCompat.getColor(a,
                    i == select ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
            int padH = Math.round(14 * d);
            int padV = Math.round(7 * d);
            chip.setPadding(padH, padV, padH, padV);
            chip.setOnClickListener(v -> {
                state.currentMuxIndex = index;
                tuneTo(state.muxes.get(index).khz);
                refreshMuxSpinner(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = Math.round(8 * d);
            muxChipRow.addView(chip, lp);
        }

        // A newly used channel is appended: keep the selected chip in view.
        if (select >= 0 && select < muxChipRow.getChildCount()
                && muxChipRow.getParent() instanceof android.widget.HorizontalScrollView) {
            final View chip = muxChipRow.getChildAt(select);
            final android.widget.HorizontalScrollView hs =
                    (android.widget.HorizontalScrollView) muxChipRow.getParent();
            hs.post(new Runnable() {
                @Override
                public void run() {
                    hs.scrollTo(Math.max(0, chip.getLeft() + chip.getWidth() / 2 - hs.getWidth() / 2), 0);
                }
            });
        }
    }

    /**
     * What the header chip shows: the frequency being watched, not the mux list.
     * The rotated phone has no chip (its width went to the tabs), so there the same
     * label appears at the left of the status line instead.
     */
    @Override
    public void update() {
        updateMuxLabel(state.currentMuxIndex);
    }

    public void updateMuxLabel(int select) {
        int khz = currentKhz();
            if (khz <= 0) {
                // Prefer where the watch screen was: opening on a fixed CH 18 is why the
                // analyzer looked dead whenever that mux was not what the user was watching.
                khz = com.pbcnx.opendtvmod.App.hub(a.getApplicationContext()).currentKhz();
            }
        if (khz <= 0 && select >= 0 && select < state.muxes.size()) khz = state.muxes.get(select).khz;
        if (khz <= 0 && !state.muxes.isEmpty()) khz = state.muxes.get(0).khz;
        String label = khz > 0 ? muxShort(khz) : "Channels";
        if (muxCurrent != null) muxCurrent.setText(label);
        if (muxLabel != null) {
            muxLabel.setText(label);
            muxLabel.setVisibility(muxCurrent == null ? View.VISIBLE : View.GONE);
        }
    }

    /** Short chip label: "UHF 15 \u00B7 479.143". */
    private String muxShort(int khz) {
        return com.pbcnx.opendtvmod.tuner.MuxSweep.compactLabel(khz);
    }

    private void saveMuxes() {
        StringBuilder sb = new StringBuilder();
        for (Mux m : state.muxes) sb.append(m.khz).append('\t').append(m.label.replace('\n', ' ')).append('\n');
        a.getSharedPreferences(AnalyzerState.PREFS, android.content.Context.MODE_PRIVATE).edit().putString("muxes", sb.toString()).apply();
    }

    public void loadMuxes() {
        SharedPreferences sp = a.getSharedPreferences(AnalyzerState.PREFS, android.content.Context.MODE_PRIVATE);
        String raw = sp.getString("muxes", "");
        if (raw.isEmpty()) {
            // one-shot migration from the analyzer's old prefs file ("opendtv")
            SharedPreferences legacy = a.getSharedPreferences("opendtv", android.content.Context.MODE_PRIVATE);
            String old = legacy.getString("muxes", "");
            if (!old.isEmpty()) {
                sp.edit().putString("muxes", old).apply();
                legacy.edit().remove("muxes").apply();
                raw = old;
            }
        }
        state.muxes.clear();
        for (String line : raw.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                state.muxes.add(new Mux(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1)));
            } catch (NumberFormatException ignored) {
                // no leading tab or no numeric field: not a mux line, skip it
            }
        }
        state.currentMuxIndex = state.muxes.isEmpty() ? -1 : 0;   // the spinner shows item 0 without firing a tune
        refreshMuxSpinner(-1);
    }

    /**
     * CH up/down on the remote step the frequency, not the saved mux list: on this
     * screen the band is the menu (the same order the picker shows) and it works with
     * an empty list, which is what the remote's channel keys are for.
     */
    public void stepChannel(int direction) {
        if (!canTune()) return;
        int at = bandIndex(currentKhz());
        if (at < 0) at = 18 - UHF_FIRST;                 // nothing tuned yet: land on CH 18
        int next = at + direction;
        if (next < 0 || next >= bandCount()) {
            status.setStatus(direction > 0 ? "Already at the top of the band."
                    : "Already at the bottom of the band.");
            return;
        }
        manualTune(bandKhz(next));
    }

    public void forcedTune(int khz) {
        if (state.source == null) {
            state.ui.postDelayed(() -> forcedTune(khz), 1000);
            return;
        }
        manualTune(khz);
    }
}
