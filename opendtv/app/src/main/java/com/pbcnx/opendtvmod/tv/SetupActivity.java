package com.pbcnx.opendtvmod.tv;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.tuner.TunerHub;
import com.pbcnx.opendtvmod.tuner.MuxSweep;
import com.pbcnx.opendtvmod.tuner.MhzLabel;


import com.pbcnx.opendtvmod.ui.Dims;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pbcnx.opendtvmod.analyzer.AnalyzerActivity;
import com.pbcnx.opendtvmod.MainActivity;
import com.pbcnx.opendtvmod.ts.EpgEvent;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * Channel setup for Live Channels: scans the UHF band with the USB tuner, then writes the found services
 * into the TV provider. Launched by the TV app ("Set up" / "Rescan") or from the launcher.
 * Plain focusable buttons only, so it works with a remote's D-pad.
 */
public final class SetupActivity extends AppCompatActivity implements TunerHub.Owner {
    private static final String TAG = "OpenDTV-TV";

    /** Quick-scan list of the vendor app (known active Philippine channels), kHz. */
    private static final int[] QUICK_SCAN_KHZ = {479143, 497143, 503143, 509143, 515143, 545143, 551143, 563143,
            569143, 581143, 587143, 593143, 599143, 611143, 617143, 623143, 629143, 641143, 647143, 659143, 683143,
            689143, 695143};

    private static final class Found {
        int khz;
        int tsId;
        int onid;
            final List<ServiceInfo> tv = new ArrayList<>();
        final Map<Integer, List<EpgEvent>> events = new HashMap<>();
    }

    private TunerHub hub;
    private String inputId;
    private TextView statusTv;
    private TextView foundTv;
    private ProgressBar bar;
    private Button btnQuick;
    private Button btnFull;
    private Button btnCancel;
    private volatile boolean scanning;
    private volatile boolean scanCancel;
    /** Set when the scan cannot run at all (no tuner, no permission, init failure); shown instead of "no channels". */
    private volatile String scanError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hub = com.pbcnx.opendtvmod.App.hub(this);
        inputId = getIntent().getStringExtra(TvInputInfo.EXTRA_INPUT_ID);
        if (inputId == null) inputId = TvChannels.inputId(this);
        buildUi();
        setResult(Activity.RESULT_CANCELED);
        // Name the sticks this build can actually drive: the two MIT clean-room cores.
        if (!hub.isTunerPresent()) setStatus(getString(R.string.setup_no_tuner));
        else setStatus(getString(R.string.setup_choose));
    }

    private void buildUi() {
        int pad = Dims.dp(this, 48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#14181F"));

        TextView title = text(getString(R.string.setup_title), 28, "#FFFFFF");
        statusTv = text("", 18, "#D0D6E0");
        statusTv.setPadding(0, Dims.dp(this, 16), 0, Dims.dp(this, 16));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1);
        foundTv = text("", 15, "#9FB3C8");
        foundTv.setPadding(0, Dims.dp(this, 16), 0, Dims.dp(this, 16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnQuick = button(getString(R.string.setup_quick));
        btnFull = button(getString(R.string.setup_full));
        btnCancel = button(getString(R.string.close));
        Button btnSkip = button(getString(R.string.setup_skip));
        Button btnAnalyzer = button(getString(R.string.setup_analyzer));
        row.addView(btnQuick);
        row.addView(btnFull);
        row.addView(btnCancel);
        row.addView(btnSkip);
        row.addView(btnAnalyzer);

        root.addView(title);
        root.addView(statusTv);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Dims.dp(this, 12)));
        root.addView(foundTv);
        root.addView(row);
        setContentView(root);

        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(false);
            }
        });
        btnFull.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(true);
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scanning) {
                    scanCancel = true;
                    setStatus(getString(R.string.setup_stopping));
                } else {
                    finish();
                }
            }
        });
        btnSkip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanCancel = true;
                startActivity(new Intent(SetupActivity.this, MainActivity.class)
                        .putExtra("tab", "settings"));
                finish();
            }
        });
        btnAnalyzer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanCancel = true;
                startActivity(new Intent(SetupActivity.this, AnalyzerActivity.class));
                finish();
            }
        });
        btnQuick.requestFocus();
    }

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setFocusable(true);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Dims.dp(this, 16);
        b.setLayoutParams(lp);
        return b;
    }


    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusTv.setText(s);
            }
        });
    }

    @Override
    public void onPreempted() {
        scanCancel = true;   // a viewing session took the tuner
    }

    @Override
    public void onBackPressed() {
        if (scanning) {
            scanCancel = true;
            setStatus(getString(R.string.setup_stopping));
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        scanCancel = true;
        hub.release(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ scan

    private static String channelLabel(int khz) {
        if (MuxSweep.channel(khz) >= 0) {
            return MuxSweep.label(khz);
        }
        return MhzLabel.mhz(khz);
    }

    private void startScan(boolean full) {
        if (scanning) return;
        final List<Integer> freqs = new ArrayList<>();
        if (full) {
            for (int f = MuxSweep.FIRST_KHZ; f <= MuxSweep.LAST_KHZ; f += MuxSweep.STEP_KHZ) freqs.add(f);
        } else {
            for (int f : QUICK_SCAN_KHZ) freqs.add(f);
        }
        scanning = true;
        scanCancel = false;
        scanError = null;
        btnQuick.setEnabled(false);
        btnFull.setEnabled(false);
        btnCancel.setText(R.string.stop);
        btnCancel.requestFocus();
        bar.setMax(freqs.size());
        bar.setProgress(0);
        foundTv.setText("");
        new Thread(() -> {
                List<Found> found = new ArrayList<>();
                int channels = 0;
                try {
                    channels = scan(freqs, found);
                } catch (RuntimeException e) {
                    Log.e(TAG, "scan failed", e);
                    scanError = getString(R.string.setup_failed, String.valueOf(e));
                } finally {
                    hub.release(this);
                }
                finishScan(channels, found.size());
            }, "OpenDtvScan").start();
    }

    /** @return number of channels written */
    private int scan(List<Integer> freqs, List<Found> found) {
        setStatus(getString(R.string.setup_waiting_usb));
        if (!hub.ensurePermission(120_000)) {
            scanError = hub.lastMessage();
            return 0;
        }
        hub.acquire(this);
        final Set<Integer> scanned = new HashSet<>();   // frequencies the scan finished
        for (int i = 0; i < freqs.size() && !scanCancel; i++) {
            final int khz = freqs.get(i);
            final int idx = i;
            String head = getString(R.string.setup_channel_head, i + 1, freqs.size(), channelLabel(khz));
            setStatus(head + getString(R.string.setup_tuning));
            com.pbcnx.opendtvmod.tuner.MuxProbe.Result probed = com.pbcnx.opendtvmod.tuner.MuxProbe.probe(
                    hub, khz, () -> scanCancel,
                    (d, n) -> setStatus(head + getString(R.string.setup_reading_count, n)));
            if (probed == null) {
                if (scanCancel) break;   // stopped while probing: this frequency is not finished
                if (!hub.isTunerPresent() || hub.lastError() != null) {
                    // A real driver failure, not a status line.
                    scanError = getString(R.string.setup_tuner_problem, hub.failure());
                    break;
                }
                scanned.add(khz);
                progress(idx + 1);
                continue;
            }
            setStatus(head + getString(R.string.setup_reading));
            TsDemux d = probed.demux;
            Found f = new Found();
            f.khz = khz;
            f.tsId = d.getTsId();
            f.onid = d.getNetworkId();
            for (ServiceInfo s : probed.services) {
                f.tv.add(s);
                f.events.put(s.serviceId, d.getEvents(s.serviceId));
            }
            Log.i(TAG, "scan " + khz + " kHz: " + f.tv.size() + " TV service(s)");
            scanned.add(khz);
            progress(idx + 1);
            if (!f.tv.isEmpty()) {
                found.add(f);
                final String list = foundText(found);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        foundTv.setText(list);
                    }
                });
            }
        }
        if (found.isEmpty()) return 0;   // nothing locked: leave the list exactly as it was (a stop included)
        List<TvChannels.Spec> carryOver = new ArrayList<>();
        if (scanCancel) {
            // Keep the multiplexes the stopped scan never reached: they were not searched, so they
            // are missing, not gone (jpdcs#9). A finished frequency's new results replace its rows.
            for (TvChannels.Spec old : TvChannels.existingChannels(this, inputId)) {
                if (!scanned.contains(old.khz)) carryOver.add(old);
            }
        }
        return writeChannels(found, carryOver);
    }

    private int writeChannels(List<Found> found, List<TvChannels.Spec> carryOver) {
        setStatus(getString(R.string.setup_saving));
        List<TvChannels.Spec> specs = new ArrayList<>();
        Map<String, Found> byKey = new HashMap<>();
        for (Found f : found) {
            int idx = 0;
            for (ServiceInfo s : f.tv) {
                idx++;
                TvChannels.Spec sp = new TvChannels.Spec();
                sp.khz = f.khz;
                sp.sid = s.serviceId;
                sp.tsId = f.tsId;
                sp.onid = f.onid;
                sp.name = s.name.isEmpty() ? "Service " + s.serviceId : s.name;
                if (s.virtualNumber != null && !"--".equals(s.virtualNumber)) {
                    sp.number = s.virtualNumber;
                } else if (MuxSweep.channel(f.khz) >= 0) {
                    // no remote_control_key_id: 100 + UHF channel, so it never collides with real numbers
                    sp.number = (100 + MuxSweep.uhf(f.khz)) + "." + idx;
                } else {
                    sp.number = (f.khz / 1000) + "." + idx;
                }
                specs.add(sp);
                byKey.put(sp.key(), f);
            }
        }
        // Channels on frequencies the scan did not reach: keep them. Same key wins, so a re-scanned
        // frequency's fresh rows replace the old ones.
        for (TvChannels.Spec old : carryOver) {
            if (!byKey.containsKey(old.key())) specs.add(old);
        }
        Map<String, Long> ids = TvChannels.replaceChannels(this, inputId, specs);
        for (TvChannels.Spec sp : specs) {
            Long id = ids.get(sp.key());
            Found f = byKey.get(sp.key());
            if (id == null || f == null) continue;
            List<EpgEvent> ev = f.events.get(sp.sid);
            // A fresh scan publishes unconditionally: there is no earlier hash for this channel yet.
            if (ev != null) TvChannels.publishPrograms(this, id, ev, 0);
        }
        return ids.size();
    }

    private void progress(final int done) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bar.setProgress(done);
            }
        });
    }

    private void finishScan(final int channels, final int muxes) {
        final boolean cancelled = scanCancel;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                scanning = false;
                btnQuick.setEnabled(true);
                btnFull.setEnabled(true);
                if (channels > 0) {
                    setResult(Activity.RESULT_OK, new Intent());
                    statusTv.setText(getString(cancelled ? R.string.setup_stopped_partial : R.string.setup_done,
                            channels, muxes));
                    btnCancel.setText(R.string.setup_finish);
                } else {
                    btnCancel.setText(R.string.close);
                    String err = scanError;
                    statusTv.setText(err != null ? err : getString(cancelled ? R.string.setup_stopped_nothing
                            : R.string.setup_no_channels));
                }
                btnCancel.requestFocus();
            }
        });
    }

    private static String foundText(List<Found> found) {
        StringBuilder sb = new StringBuilder();
        for (Found f : found) {
            sb.append(channelLabel(f.khz)).append(":  ");
            int n = 0;
            for (ServiceInfo s : f.tv) {
                if (n++ > 0) sb.append(", ");
                sb.append(s.name.isEmpty() ? "sid " + s.serviceId : s.name);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

}
