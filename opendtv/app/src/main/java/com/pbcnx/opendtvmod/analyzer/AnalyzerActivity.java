package com.pbcnx.opendtvmod.analyzer;



import com.pbcnx.opendtvmod.R;

import com.pbcnx.opendtvmod.ui.Dims;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.ui.AspectRatioFrameLayout;
import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.player.PlayerFactory;
import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.ui.CaptionOverlay;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * Diagnostic analyzer activity (formerly MainActivity).
 * Top: video (left) + Virtual Number / Channel name / Program ID (right).
 * Bottom: Video ID, Audio ID, Codec, then Analyzer / EPG / Datacast tabs.
 *
 * Data flow:  TsSource thread -> TsDemux (analysis) -> ServiceFilter -> TsPipe -> ExoPlayer.
 */
@OptIn(markerClass = UnstableApi.class)
public class AnalyzerActivity extends AppCompatActivity implements com.pbcnx.opendtvmod.tuner.TunerHub.Owner {
    private android.view.View emptyPanel;
    private android.widget.TextView emptyTitle;
    private android.widget.TextView emptyText;
    private android.widget.TextView emptyHint;
    private android.widget.TextView tvContent;
    private final com.pbcnx.opendtvmod.ui.EwbsAlerts ewbsAlerts = new com.pbcnx.opendtvmod.ui.EwbsAlerts(this);


    private static final long TICK_MS = 1000;

    private final AnalyzerState state = new AnalyzerState();
    private final LogsPanel logsTab = new LogsPanel(this, state);
    private final GraphPanel graphTab = new GraphPanel(this, state);
    private final PidTable pidTab = new PidTable(this, state);
    private final TablesPanel tablesTab = new TablesPanel(this, state);
    private final TunerControl tuner = new TunerControl(this, state, logsTab);
    private final RemoteTab remoteTab = new RemoteTab(this, state, logsTab);
    private final SignalPanel signalTab = new SignalPanel(this, state, graphTab);
    private final ServicesPanel servicesTab = new ServicesPanel(this, state, logsTab);

    /** Panels in TAB_* order; EPG/datacast render into tvContent, so they are not here. */
    private final TabPanel[] tabPanels = {signalTab, servicesTab, pidTab, tablesTab, graphTab, logsTab};
    private static final int[] TAB_FOR_PANEL = {
            AnalyzerState.TAB_SIGNAL, AnalyzerState.TAB_SERVICES, AnalyzerState.TAB_PIDS,
            AnalyzerState.TAB_TABLES, AnalyzerState.TAB_GRAPH, AnalyzerState.TAB_LOGS};
    private final TabStrip tabStrip = new TabStrip(this, state, logsTab);
    private final SourceManager sources = new SourceManager(this, state, logsTab);
    private final RecordingController recorder = new RecordingController(this, state, logsTab);
    /** Every tab repaints through the same call; the list is also the bind order. */
    private final List<TabPanel> panels = java.util.Arrays.asList(
            logsTab, graphTab, pidTab, tablesTab, tuner, signalTab, servicesTab, tabStrip);

    private final ActivityResultLauncher<String[]> openFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), new androidx.activity.result.ActivityResultCallback<Uri>() {
                @Override
                public void onActivityResult(Uri uri) {
                    if (uri != null) sources.startFile(uri);
                }
            });

    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (dev != null && SourceManager.isTuner(dev) && SourceManager.isTunerSource(state.source)) {
                sources.stopSource();
                state.player.stop();
                setStatus("Tuner unplugged.");
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            state.ui.postDelayed(this, TICK_MS);
        }
    };

    /** Playback installs a sink on the hub's demux, so claim the hub first. */
    void claimTuner() {
        sources.claim();
    }

    /** The Logs tab owns the ring; the activity keeps the entry point its own call sites use. */
    void setStatus(String text) {
        logsTab.setStatus(text);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Makes room for the EWBS strip while it is visible: it is drawn over the window. */
    private void insetForEwbsAlerts(boolean visible) {
        ViewGroup root = findViewById(android.R.id.content);
        if (root == null || root.getChildCount() == 0) return;
        View content = root.getChildAt(0);
        int top = visible ? Dims.dp(this, 48) : 0;
        content.setPadding(content.getPaddingLeft(), top, content.getPaddingRight(), content.getPaddingBottom());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analyzer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // never sleep while watching
        
        // A capture instead of a dongle: the analyzer can already play a TS file, and so can the BML
        // engine behind it. That is how the engine gets exercised with no tuner on the bench:
        //
        //   adb shell am start -n com.pbcnx.opendtvmod.ph/com.pbcnx.opendtvmod.analyzer.AnalyzerActivity `
        //       --es ts_path /sdcard/Download/capture.ts
        //
        // Delayed one layout pass so the board, the player and the BML controller exist first.
        com.pbcnx.opendtvmod.dev.BenchHooks.runCapture(this, com.pbcnx.opendtvmod.dev.BenchHooks.launchExtras(getIntent()), 400_000L, true,
                sources::startSource, 4_000, () -> {
                    if (state.demux == null || state.bml == null) return;
                    for (ServiceInfo svc : state.demux.getServices()) {
                        boolean hasData = false;
                        for (com.pbcnx.opendtvmod.ts.StreamInfo st : svc.streams) hasData |= st.isDatacast();
                        if (!hasData) continue;
                        state.selectedServiceId = svc.serviceId;
                        state.bmlServiceId = svc.serviceId;
                        state.bml.open(state.demux, svc);
                        setStatus("dev: BML open on service " + svc.serviceId);
                        return;
                    }
                    setStatus("dev: no service with data broadcast");
                });

        // The EWBS strip floats over the window; while it is up, push the whole analyzer
        // down by its height so the title and the nine tabs stay readable.
        ewbsAlerts.setHost(this::insetForEwbsAlerts);
        // One explicit wiring point: a row that changes a setting repaints through this.
        state.refresh = this::refresh;

        state.playerView = findViewById(R.id.player_view);
        AspectRatioFrameLayout frame = findViewById(R.id.player_frame);
        if (frame != null) frame.setAspectRatio(16f / 9f);

        bindPanelsAndTabStrip();


        // Live TV: start after ~1.5 s of data, keep at most 15 s, and refill 2.5 s after an underrun.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, 15000, 1500, 2500)
                .build();
        state.player = PlayerFactory.build(this, loadControl, false);
        state.playerView.setPlayer(state.player);
        state.captionOverlay = new CaptionOverlay(state.playerView);
        state.player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                setStatus("Player: " + error.getErrorCodeName() + " - " + error.getMessage());
            }
        });

        // Tuner unplugged: stop cleanly. (Plugging in is handled through onNewIntent / the launch intent.)
        IntentFilter detach = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        androidx.core.content.ContextCompat.registerReceiver(this, usbDetachReceiver, detach,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);

        findViewById(R.id.action_export).setOnClickListener(v -> openFile.launch(new String[]{"*/*"}));
        findViewById(R.id.action_usb).setOnClickListener(v -> sources.connectUsb());
        findViewById(R.id.action_probe).setOnClickListener(v ->
                startActivity(new Intent(this, UsbProbeActivity.class)));
        recorder.bindViews();
        findViewById(R.id.action_channels).setOnClickListener(v -> tuner.showChannelPicker());

        tuner.loadMuxes();
        tabStrip.bindTabs();
        // Diagnostic hook: adb ... --ei tab 6 opens the Graph tab directly (debug builds only).
        final int forcedTab = com.pbcnx.opendtvmod.BuildConfig.DEBUG ? getIntent().getIntExtra("tab", -1) : -1;
        if (forcedTab >= AnalyzerState.TAB_SIGNAL && forcedTab <= AnalyzerState.TAB_REMOTE) state.currentTab = forcedTab;
        tabStrip.updateTabVisuals();
        // Widths are 0 until the first layout, while a row restored from another
        // orientation can come back with the current tab half off-screen: re-check once.
        state.ui.post(new Runnable() {
            @Override
            public void run() {
                tabStrip.keepSelectedTabVisible();
            }
        });
        graphTab.updateGraphChips();
        // Start on the tab, not on a random chip, when the screen is driven by a remote.
        findViewById(AnalyzerState.TAB_LABEL_IDS[state.currentTab]).requestFocus();
        remoteTab.setupRemote();

        state.ui.post(tick);

        seedSourceAndStart();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) sources.connectUsb();
        int khz = com.pbcnx.opendtvmod.dev.BenchHooks.launchExtras(intent).tuneKhz;
        if (khz > 0) tuner.forcedTune(khz);
        int tab = com.pbcnx.opendtvmod.BuildConfig.DEBUG ? intent.getIntExtra("tab", -1) : -1;
        if (tab >= AnalyzerState.TAB_SIGNAL && tab <= AnalyzerState.TAB_REMOTE) {
            state.currentTab = tab;
            tabStrip.updateTabVisuals();
            refresh();
        }
        if (com.pbcnx.opendtvmod.dev.BenchHooks.launchExtras(intent).recordWhole) {
            state.ui.postDelayed(recorder::toggleWholeStreamRecording, 5000);
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        tabStrip.handleTouch(e);
        return super.dispatchTouchEvent(e);
    }

    /** Close the dongle BEFORE the previous screen resumes, so the main player can re-open it cleanly. */
    @Override
    public void finish() {
        recorder.stopWholeStreamRecording();
        sources.stopSource();
        super.finish();
    }

    @Override
    public void onBackPressed() {
        if (!remoteTab.handleBack()) super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        Boolean handled = remoteTab.dispatchKeyEvent(e);
        return handled != null ? handled : super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (state.bml != null) state.bml.close();      // background: no data-broadcast engine running unseen
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        com.pbcnx.opendtvmod.ui.StorageAccess.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.pbcnx.opendtvmod.ui.StorageAccess.resumePending(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (state.bml != null) state.bml.close();
        state.ui.removeCallbacks(tick);
        recorder.stopWholeStreamRecording();
        sources.stopSource();
        unregisterReceiver(usbDetachReceiver);
        state.player.release();
        state.pipe.close();
    }

    @Override
    public void onPreempted() {
        // Another owner (the watch screen) took the tuner: stop the local player, keep the screen.
        state.ui.post(() -> {
            state.player.stop();
            state.player.clearMediaItems();
            setStatus("Tuner handed to another screen");
        });
    }

    // ------------------------------------------------------------------ periodic UI refresh

    private void refresh() {
        // The hub owns the live source: a retune swaps its demux, so read both every tick.
        state.demux = sources.hub().demux();
        state.source = sources.hub().source();
        TsDemux d = state.demux;
        if (!state.sawFirstData && d.getPacketCount() > 0) {
            state.sawFirstData = true;
            setStatus((state.source != null ? state.source.name() : "source") + ": running");
        }
        recorder.ensureTap(d);
        List<ServiceInfo> services = d.getServices();
        ServiceInfo sel = AnalyzerUtil.findService(services, state.selectedServiceId);
        state.lastSnapshot = d.sample();

        boolean remote = state.currentTab == AnalyzerState.TAB_REMOTE;
        show(state.remotePanel, remote);
        if (state.bml != null && state.bml.isActive()
                && (state.bml.demux() != d || state.bmlServiceId != state.selectedServiceId)) {
            state.bml.close();
        }

        boolean haveSource = state.source != null;
        TunerSignal st = haveSource ? state.source.getTunerSignal() : null;
        // A tuner without a lock is blocked. A file has no lock to offer and still demuxes,
        // so it is a data source, not a blocked one.
        boolean tuner = haveSource && state.source.supportsTuning();
        boolean blocked = !haveSource || (tuner && (st == null || !st.locked));
        // The logs and the on-screen remote are useful without a signal: never cover them.

        renderEmptyState(blocked, haveSource, st);
        renderPanels(blocked, remote);
        show(tvContent, !blocked && isTextTab());
        if (!blocked && isTextTab()) setText(tvContent, tabText(d, services, sel));

        // One per-tick call per panel: each repaints from the shared state and skips when hidden.
        for (TabPanel panel : panels) panel.update();
        ewbsAlerts.update(d);
    }

    /** The empty-state copy: which failure it is and what the meter actually reported. */
    private void renderEmptyState(boolean blocked, boolean haveSource, TunerSignal st) {
        show(emptyPanel, blocked && state.currentTab != AnalyzerState.TAB_LOGS
                && state.currentTab != AnalyzerState.TAB_REMOTE);
        if (!blocked) return;
        if (!haveSource) {
            setText(emptyTitle, "The analyzer needs a tuner");
            setText(emptyText, "Connect the USB dongle and the signal board; muxes, services and tables appear here.");
            // The same list the probe and the registry use, so a stick is named one way everywhere.
            setText(emptyHint, com.pbcnx.opendtvmod.source.TunerRegistry.supportedNames());
        } else {
            setText(emptyTitle, "No signal on this mux");
            setText(emptyText, "Check the antenna, or pick another channel from the Channels action.");
            setText(emptyHint, st == null
                    ? "the source reports no status"
                    : (st.locked ? "locked" : "no lock")
                    + " \u00b7 SNR " + (Double.isNaN(st.snrDb) ? "--" : String.format(Locale.US, "%.1f dB", st.snrDb))
                    + " \u00b7 level " + (st.strengthPercent < 0 ? "not decoded" : st.strengthPercent + " %"));
        }
    }

    /** Visibility of every tab surface, in TAB_FOR_PANEL order, plus the text tab. */
    private void renderPanels(boolean blocked, boolean remote) {
        for (int i = 0; i < tabPanels.length; i++) {
            show(tabPanels[i].view(), !blocked && state.currentTab == TAB_FOR_PANEL[i]);
        }
        show(state.remotePanel, remote);
    }

    private boolean isTextTab() {
        return state.currentTab == AnalyzerState.TAB_EPG || state.currentTab == AnalyzerState.TAB_DATACAST;
    }

    private String tabText(TsDemux d, List<ServiceInfo> services, ServiceInfo sel) {
        switch (state.currentTab) {
            case AnalyzerState.TAB_EPG: return EpgPanel.epgText(d, sel);
            case AnalyzerState.TAB_DATACAST: return DatacastPanel.datacastText(d, services, sel);
            default: return "";
        }
    }



    /** One binding pass for every panel and the tab strip. */
    private void bindPanelsAndTabStrip() {
        signalTab.bindViews();
        tuner.bindViews();
        tvContent = findViewById(R.id.tv_content);
        emptyPanel = findViewById(R.id.empty_panel);
        servicesTab.bindViews();
        emptyTitle = findViewById(R.id.empty_title);
        emptyText = findViewById(R.id.empty_text);
        emptyHint = findViewById(R.id.empty_hint);

        pidTab.bindViews();

        tablesTab.bindViews();

        graphTab.bindViews();

        logsTab.bindViews();

        tabStrip.bindViews();
        state.contentFrame = findViewById(R.id.content_frame);
        // The card's text scroller is the only focusable thing between the tab row and the
        // real targets, so DOWN landed on it and the focus ring vanished. ScrollView sets
        // itself focusable in its own constructor, which overrides android:focusable=false
        // in the layout - it has to be turned off here, after inflation.
        if (state.contentFrame != null && state.contentFrame.getChildCount() > 0) {
            state.contentFrame.getChildAt(0).setFocusable(false);
        }
        tabStrip.sizePreview();
        signalTab.trimBoardForNarrowColumn();
        tabStrip.setUpTabGestures();
    }

    /** Seeds the hub frequency before the source starts, then starts USB setup. */
    private void seedSourceAndStart() {
        // The frequency the caller was already showing. This has to be set BEFORE sources.connectUsb(),
        // because the source tunes the frequency it is constructed with: without it currentKhz()
        // answers -1 ("nothing tuned"), the tuner is handed -1, and it fails before its TS port
        // opens - on the 9308 that is the "tune failed at -1 kHz" with a lock but no services.
        final com.pbcnx.opendtvmod.dev.BenchHooks.LaunchExtras extras =
                com.pbcnx.opendtvmod.dev.BenchHooks.launchExtras(getIntent());
        final int debugKhz = extras.tuneKhz;
        if (debugKhz > 0) {
            state.currentTunedKhz = debugKhz;
        } else {
            // The regular hand-off: the hub is still on the channel the watch screen was showing,
            // so start there. This used to depend on the tune_khz extra, which release builds
            // (correctly) ignore, leaving the first saved mux instead.
            int hubKhz = com.pbcnx.opendtvmod.App.hub(this).currentKhz();
            if (hubKhz > 0) state.currentTunedKhz = hubKhz;
        }

        // The tuner is the main source: start USB initialisation as soon as the app opens.
        sources.connectUsb();

        // No delayed retune here: the seeded frequency above is what the source starts on. The
        // adb hook (--ei tune_khz 617143) therefore lands immediately rather than 2.5 s later.
        if (extras.recordWhole) {
            state.ui.postDelayed(recorder::toggleWholeStreamRecording, 5000);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The tab views are optional by layout: the landscape and tablet variants simply do not contain
     * some of them (the empty-state block, the status board, the text tab). Those go through
     * {@link #show} and {@link #setText}, which skip a missing view; every other view bound in
     * {@code bindViews()} is required and is dereferenced directly, so a missing one fails loudly
     * instead of going quiet.
     */
    private static void show(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static void setText(android.widget.TextView view, String text) {
        if (view != null) view.setText(text);
    }
}
