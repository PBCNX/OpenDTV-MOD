package ph.opendtv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.opendtv.player.PlayerFactory;
import ph.opendtv.player.TsPipeDataSource;
import ph.opendtv.source.FileTsSource;
import ph.opendtv.source.SignalStatus;
import ph.opendtv.source.TsSource;
import ph.opendtv.source.TunerRegistry;
import ph.opendtv.source.TunerSource;
import ph.opendtv.ts.AacTransport;
import ph.opendtv.ts.AnalyzerSnapshot;
import ph.opendtv.ts.CarouselInfo;
import ph.opendtv.ts.CarouselModule;
import ph.opendtv.ts.CarouselResource;
import ph.opendtv.ts.EpgEvent;
import ph.opendtv.bml.BmlController;
import ph.opendtv.bml.RemotePanel;
import ph.opendtv.ui.EwbsBanner;
import ph.opendtv.ts.ServiceFilter;
import ph.opendtv.ui.CaptionOverlay;
import ph.opendtv.ui.SparklineView;
import ph.opendtv.ts.ServiceInfo;
import ph.opendtv.ts.StreamInfo;
import ph.opendtv.ts.TsDemux;
import ph.opendtv.ts.TsExtractorFactory;
import ph.opendtv.ts.TsPipe;
import ph.opendtv.ts.TsUtil;

/**
 * Diagnostic analyzer activity (formerly MainActivity).
 * Top: video (left) + Virtual Number / Channel name / Program ID (right).
 * Bottom: Video ID, Audio ID, Codec, then Analyzer / EPG / Datacast tabs.
 *
 * Data flow:  TsSource thread -> TsDemux (analysis) -> ServiceFilter -> TsPipe -> ExoPlayer.
 */
@OptIn(markerClass = UnstableApi.class)
public class AnalyzerActivity extends AppCompatActivity {

    private static final String TAG = "OpenDTV-Analyzer";
    private static final String ACTION_USB_PERMISSION = "ph.opendtv.USB_PERMISSION";
    private static final int TAB_SIGNAL = 0, TAB_SERVICES = 1, TAB_PIDS = 2, TAB_TABLES = 3,
            TAB_EPG = 4, TAB_DATACAST = 5, TAB_GRAPH = 6, TAB_LOGS = 7, TAB_REMOTE = 8;
    private static final long TICK_MS = 1000;
    /** Level of a status line: the Logs tab colours entries by it. */
    private static final int LOG_INFO = 0, LOG_WARN = 1, LOG_ERROR = 2;
    private static final int LOG_MAX = 300;

    private static final class LogEntry {
        final String time;
        final String text;
        final int level;

        LogEntry(String time, String text, int level) {
            this.time = time;
            this.text = text;
            this.level = level;
        }
    }

    private final List<LogEntry> logEntries = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TsPipe pipe = new TsPipe(8 * 1024 * 1024);

    private volatile TsDemux demux = new TsDemux();
    private TsSource source;
    private ExoPlayer player;
    private UsbManager usbManager;

    private PlayerView playerView;
    private CaptionOverlay captionOverlay;

    // ---- data broadcast (BML) viewer + on-screen remote ----
    private BmlController bml;
    private final EwbsBanner ewbsBanner = new EwbsBanner(this);
    private RemotePanel remotePanel;
    private android.widget.FrameLayout playerFrame;
    private int bmlServiceId = -1;
    private float[] videoFrac = null;      // null = video fills the frame
    private TextView boardState, boardChannel, boardMeta, boardStrengthVal, boardQualityVal, boardBitrate, boardDot;
    private TextView boardChipData, boardChipCc, boardChipLock, boardChipEwbs;
    // Landscape keeps the board out of the layout; these carry its content in the card.
    private TextView sigStrengthVal, sigQualityVal, sigChipData, sigChipCc, sigChipLock, sigChipEwbs;
    private View sigStrengthFill, sigStrengthRest, sigQualityFill, sigQualityRest, sigChips;
    private SparklineView signalSpark;
    /** True when this layout has no status board (both landscapes): the card carries it. */
    private boolean boardGone;
    private TextView muxCurrent, tvContent, tvStatus, stripRight, muxLabel;
    /** Card height the Signal panel was last fitted to; -1 until the first layout. */
    private int lastSignalFit = -1;
    /** Row height the landscape preview was last sized to; -1 until the first layout. */
    private int lastPreviewHeight = -1;
    private View signalPanel, servicesPanel, emptyPanel, pidsPanel, tablesPanel, graphPanel, logsPanel;
    private LinearLayout servicesList;
    private TextView tileBitrate, tilePackets, tileNull, tileCc, tileTei, tileSync;
    private TextView signalLine1, signalLine2, signalLine3, emptyTitle, emptyText, emptyHint;
    private LinearLayout muxChipRow;
    private TextView actionRecord;

    // ---- PIDs tab: one row per PID, rebuilt only when the PID set changes ----
    private LinearLayout pidsRows;
    private TextView pidsFooter;
    private String pidKey = "";
    private final List<TextView[]> pidCells = new ArrayList<>();

    // ---- Tables tab ----
    private TextView nitBody, nitCount, sdtBody, sdtCount, pmtBody, pmtCount, eitBody, eitCount;

    // ---- Graph tab: three 30 s histories, one sparkline ----
    private static final int HIST = 30;
    private static final int METRIC_BITRATE = 0, METRIC_PACKETS = 1, METRIC_CC = 2;
    private final float[] histBitrate = new float[HIST];
    private final float[] histPackets = new float[HIST];
    private final float[] histCc = new float[HIST];
    private int histCount;
    private long prevPackets = -1, prevCc = -1;
    private int graphMetric = METRIC_BITRATE;
    private SparklineView graphSpark;
    private TextView graphValue, graphUnit, graphPeak, graphAvg, graphLine;
    private TextView chipGraphBitrate, chipGraphPackets, chipGraphCc;

    // ---- Logs tab ----
    private TextView logsText, logsCount;
    private android.widget.ScrollView logsScroll;
    private int logVersion;
    private int logsRendered = -1;

    // ---- phone preview + tab swipe ----
    private View previewBox;
    private ViewGroup contentFrame;
    private android.view.GestureDetector tabGestures;
    private final android.graphics.Rect hitRect = new android.graphics.Rect();
    private boolean touchInContent;
    private FileOutputStream wholeStreamOutput;
    private volatile boolean isRecordingWhole;

    /** A multiplex (one RF channel) found by a scan or tuned by hand. */
    private static final class Mux {
        final int khz;
        final String label;

        Mux(int khz, String label) {
            this.khz = khz;
            this.label = label;
        }
    }

    private final List<Mux> muxes = new ArrayList<>();
    private int currentMuxIndex = -1;
    private int currentTunedKhz = 0;
    private static final String PREFS = "opendtv";

    private int currentTab = TAB_SIGNAL;
    private int lastIndicatorTab = -1;
    private View tabIndicator;
    private int selectedServiceId = -1;
    private String serviceListKey = "";
    private final java.util.Map<Integer, View> serviceChecks = new java.util.HashMap<>();
    private final List<Integer> spinnerSids = new ArrayList<>();
    private AnalyzerSnapshot lastSnapshot = new AnalyzerSnapshot();

    private final ActivityResultLauncher<String[]> openFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), new androidx.activity.result.ActivityResultCallback<Uri>() {
                @Override
                public void onActivityResult(Uri uri) {
                    if (uri != null) startFile(uri);
                }
            });

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && dev != null) {
                startSourceFor(dev);
            } else {
                setStatus("USB permission denied.");
            }
        }
    };

    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (dev != null && isTuner(dev) && isTunerSource(source)) {
                stopSource();
                player.stop();
                setStatus("Tuner unplugged.");
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    /** Makes room for the EWBS strip while it is visible: it is drawn over the window. */
    private void insetForEwbsBanner(boolean visible) {
        ViewGroup root = findViewById(android.R.id.content);
        if (root == null || root.getChildCount() == 0) return;
        View content = root.getChildAt(0);
        int top = visible ? Math.round(48f * getResources().getDisplayMetrics().density) : 0;
        content.setPadding(content.getPaddingLeft(), top, content.getPaddingRight(), content.getPaddingBottom());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analyzer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // never sleep while watching

        // The EWBS strip floats over the window; while it is up, push the whole analyzer
        // down by its height so the title and the nine tabs stay readable.
        ewbsBanner.setHost(this::insetForEwbsBanner);

        playerView = findViewById(R.id.player_view);
        AspectRatioFrameLayout frame = findViewById(R.id.player_frame);
        if (frame != null) frame.setAspectRatio(16f / 9f);

        boardState = findViewById(R.id.board_state);
        boardDot = findViewById(R.id.board_dot);
        boardChannel = findViewById(R.id.board_channel);
        boardStrengthVal = findViewById(R.id.board_strength_val);
        boardChipData = findViewById(R.id.board_chip_data);
        boardChipCc = findViewById(R.id.board_chip_cc);
        boardChipLock = findViewById(R.id.board_chip_lock);
        boardChipEwbs = findViewById(R.id.board_chip_ewbs);
        boardQualityVal = findViewById(R.id.board_quality_val);
        boardBitrate = findViewById(R.id.board_bitrate);
        boardMeta = findViewById(R.id.board_meta);
        muxCurrent = findViewById(R.id.analyzer_mux_current);
        // DOWN from the chip must always land somewhere. What the chip sat above depends on the
        // tab: the Signal panel is empty, the PIDs table is not, so focus search found a candidate
        // on some tabs and nothing on others. Go to the tab that is actually selected instead.
        if (muxCurrent != null) {
            muxCurrent.setOnKeyListener((v, keyCode, ev) -> {
                if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    View tab = findViewById(TAB_LABEL_IDS[currentTab]);
                    if (tab != null) {
                        tab.requestFocus();
                        return true;
                    }
                }
                return false;
            });
        }
        muxChipRow = findViewById(R.id.mux_chip_row);
        tvContent = findViewById(R.id.tv_content);
        tvStatus = findViewById(R.id.strip_text);
        stripRight = findViewById(R.id.strip_right);
        muxLabel = findViewById(R.id.strip_mux);
        signalPanel = findViewById(R.id.signal_panel);
        servicesPanel = findViewById(R.id.services_panel);
        emptyPanel = findViewById(R.id.empty_panel);
        servicesList = findViewById(R.id.services_list);
        tileBitrate = findViewById(R.id.tile_bitrate);
        tilePackets = findViewById(R.id.tile_packets);
        tileNull = findViewById(R.id.tile_null);
        tileCc = findViewById(R.id.tile_cc);
        tileTei = findViewById(R.id.tile_tei);
        tileSync = findViewById(R.id.tile_sync);
        signalLine1 = findViewById(R.id.signal_line1);
        signalLine2 = findViewById(R.id.signal_line2);
        signalLine3 = findViewById(R.id.signal_line3);
        emptyTitle = findViewById(R.id.empty_title);
        emptyText = findViewById(R.id.empty_text);
        emptyHint = findViewById(R.id.empty_hint);

        // Where the board is not part of the layout (both landscapes), the Signal card
        // takes over its numbers, its bars and its four words.
        sigStrengthVal = findViewById(R.id.sig_strength_val);
        sigQualityVal = findViewById(R.id.sig_quality_val);
        sigStrengthFill = findViewById(R.id.sig_strength_fill);
        sigStrengthRest = findViewById(R.id.sig_strength_rest);
        sigQualityFill = findViewById(R.id.sig_quality_fill);
        sigQualityRest = findViewById(R.id.sig_quality_rest);
        sigChipData = findViewById(R.id.sig_chip_data);
        sigChipCc = findViewById(R.id.sig_chip_cc);
        sigChipLock = findViewById(R.id.sig_chip_lock);
        sigChipEwbs = findViewById(R.id.sig_chip_ewbs);
        signalSpark = findViewById(R.id.signal_spark);
        boardGone = boardChipData == null;
        showIfPresent(findViewById(R.id.sig_meters), boardGone);
        sigChips = findViewById(R.id.sig_chips);
        showIfPresent(sigChips, boardGone);
        showIfPresent(signalSpark, boardGone);

        pidsPanel = findViewById(R.id.pids_panel);
        pidsRows = findViewById(R.id.pids_rows);
        pidsFooter = findViewById(R.id.pids_footer);

        tablesPanel = findViewById(R.id.tables_panel);
        nitBody = findViewById(R.id.nit_body);
        nitCount = findViewById(R.id.nit_count);
        sdtBody = findViewById(R.id.sdt_body);
        sdtCount = findViewById(R.id.sdt_count);
        pmtBody = findViewById(R.id.pmt_body);
        pmtCount = findViewById(R.id.pmt_count);
        eitBody = findViewById(R.id.eit_body);
        eitCount = findViewById(R.id.eit_count);

        graphPanel = findViewById(R.id.graph_panel);
        graphSpark = findViewById(R.id.graph_spark);
        graphValue = findViewById(R.id.graph_value);
        graphUnit = findViewById(R.id.graph_unit);
        graphPeak = findViewById(R.id.graph_peak);
        graphAvg = findViewById(R.id.graph_avg);
        graphLine = findViewById(R.id.graph_line);
        chipGraphBitrate = findViewById(R.id.chip_graph_bitrate);
        chipGraphPackets = findViewById(R.id.chip_graph_packets);
        chipGraphCc = findViewById(R.id.chip_graph_cc);
        chipGraphBitrate.setOnClickListener(v -> setGraphMetric(METRIC_BITRATE));
        chipGraphPackets.setOnClickListener(v -> setGraphMetric(METRIC_PACKETS));
        chipGraphCc.setOnClickListener(v -> setGraphMetric(METRIC_CC));

        logsPanel = findViewById(R.id.logs_panel);
        logsText = findViewById(R.id.logs_text);
        logsScroll = findViewById(R.id.logs_scroll);
        logsCount = findViewById(R.id.logs_count);

        tabIndicator = findViewById(R.id.tab_indicator);
        contentFrame = findViewById(R.id.content_frame);
        // The card's text scroller is the only focusable thing between the tab row and the
        // real targets, so DOWN landed on it and the focus ring vanished. ScrollView sets
        // itself focusable in its own constructor, which overrides android:focusable=false
        // in the layout - it has to be turned off here, after inflation.
        if (contentFrame != null && contentFrame.getChildCount() > 0) {
            contentFrame.getChildAt(0).setFocusable(false);
        }
        sizePreview();
        trimBoardForNarrowColumn();
        setUpTabGestures();

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Live TV: start after ~1.5 s of data, keep at most 15 s, and refill 2.5 s after an underrun.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, 15000, 1500, 2500)
                .build();
        player = PlayerFactory.build(this, loadControl, false);
        playerView.setPlayer(player);
        captionOverlay = new CaptionOverlay(playerView);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                setStatus("Player: " + error.getErrorCodeName() + " - " + error.getMessage());
            }
        });

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(this, usbPermissionReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        // Tuner unplugged: stop cleanly. (Plugging in is handled through onNewIntent / the launch intent.)
        IntentFilter detach = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbDetachReceiver, detach);

        findViewById(R.id.action_export).setOnClickListener(v -> openFile.launch(new String[]{"*/*"}));
        findViewById(R.id.action_usb).setOnClickListener(v -> connectUsb());
        findViewById(R.id.action_probe).setOnClickListener(v ->
                startActivity(new Intent(AnalyzerActivity.this, UsbProbeActivity.class)));
        actionRecord = findViewById(R.id.action_record);
        actionRecord.setOnClickListener(v -> toggleWholeStreamRecording());
        findViewById(R.id.action_channels).setOnClickListener(v -> showChannelPicker());
        // Absent from the rotated phone's layout, where the chip is a status line instead.
        if (muxCurrent != null) muxCurrent.setOnClickListener(v -> showChannelPicker());

        loadMuxes();
        bindTab(R.id.tab_signal, TAB_SIGNAL);
        bindTab(R.id.tab_services, TAB_SERVICES);
        bindTab(R.id.tab_pids, TAB_PIDS);
        bindTab(R.id.tab_tables, TAB_TABLES);
        bindTab(R.id.tab_epg, TAB_EPG);
        bindTab(R.id.tab_datacast, TAB_DATACAST);
        bindTab(R.id.tab_graph, TAB_GRAPH);
        bindTab(R.id.tab_logs, TAB_LOGS);
        bindTab(R.id.tab_remote, TAB_REMOTE);
        // Diagnostic hook: adb ... --ei tab 6 opens the Graph tab directly.
        final int forcedTab = getIntent().getIntExtra("tab", -1);
        if (forcedTab >= TAB_SIGNAL && forcedTab <= TAB_REMOTE) currentTab = forcedTab;
        updateTabVisuals();
        // Widths are 0 until the first layout, while a row restored from another
        // orientation can come back with the current tab half off-screen: re-check once.
        ui.post(new Runnable() {
            @Override
            public void run() {
                keepSelectedTabVisible();
            }
        });
        updateGraphChips();
        // Start on the tab, not on a random chip, when the screen is driven by a remote.
        findViewById(TAB_LABEL_IDS[currentTab]).requestFocus();
        setupRemote();

        ui.post(tick);

        // The frequency the caller was already showing. This has to be set BEFORE connectUsb(),
        // because the source tunes the frequency it is constructed with: without it currentKhz()
        // answers -1 ("nothing tuned"), the tuner is handed -1, and it fails before its TS port
        // opens - on the 9308 that is the "tune failed at -1 kHz" with a lock but no services.
        final int forcedKhz = getIntent().getIntExtra("tune_khz", 0);
        if (forcedKhz > 0) currentTunedKhz = forcedKhz;

        // The tuner is the main source: start USB initialisation as soon as the app opens.
        connectUsb();

        // No delayed retune here: the seeded frequency above is what the source starts on. The
        // adb hook (--ei tune_khz 617143) therefore lands immediately rather than 2.5 s later.
        if (getIntent().getBooleanExtra("record_whole", false)) {
            ui.postDelayed(this::toggleWholeStreamRecording, 5000);
        }
    }

    private void forcedTune(int khz) {
        if (source == null) {
            ui.postDelayed(() -> forcedTune(khz), 1000);
            return;
        }
        manualTune(khz);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) connectUsb();
        int khz = intent.getIntExtra("tune_khz", 0);
        if (khz > 0) forcedTune(khz);
        int tab = intent.getIntExtra("tab", -1);
        if (tab >= TAB_SIGNAL && tab <= TAB_REMOTE) {
            currentTab = tab;
            updateTabVisuals();
            refresh();
        }
        if (intent.getBooleanExtra("record_whole", false)) {
            ui.postDelayed(this::toggleWholeStreamRecording, 5000);
        }
    }

    /** Close the dongle BEFORE the previous screen resumes, so the main player can re-open it cleanly. */
    @Override
    public void finish() {
        stopWholeStreamRecording();
        stopSource();
        super.finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bml != null) bml.close();      // background: no data-broadcast engine running unseen
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bml != null) bml.close();
        ui.removeCallbacks(tick);
        stopWholeStreamRecording();
        stopSource();
        unregisterReceiver(usbPermissionReceiver);
        unregisterReceiver(usbDetachReceiver);
        player.release();
        pipe.close();
    }

    private synchronized void toggleWholeStreamRecording() {
        if (!isRecordingWhole) {
            if (!checkAndRequestStoragePermission(this)) return;
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "OpenDTV/wholets");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                int freq = currentTunedKhz > 0 ? currentTunedKhz : (currentMuxIndex >= 0 && currentMuxIndex < muxes.size() ? muxes.get(currentMuxIndex).khz : 497143);
                String netName = demux.getNetworkName();
                if (netName == null || netName.trim().isEmpty()) {
                    netName = "Network";
                }
                netName = netName.replaceAll("[^a-zA-Z0-9_-]", "_");
                String timeStamp = new SimpleDateFormat("MMddyyyy-HHmmss", Locale.US).format(new Date());
                String fileName = freq + "-" + netName + "-" + timeStamp + ".ts";
                File file = new File(dir, fileName);
                wholeStreamOutput = new FileOutputStream(file);
                isRecordingWhole = true;
                actionRecord.setText("Stop rec");
                actionRecord.setTextColor(ContextCompat.getColor(this, R.color.odtv_live));
                Toast.makeText(this, "Recording the whole mux", Toast.LENGTH_SHORT).show();
                setStatus("Recording the whole mux to " + file.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to start whole stream recording", e);
                Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show();
            }
        } else {
            stopWholeStreamRecording();
            Toast.makeText(this, "Recording stopped · OpenDTV/wholets", Toast.LENGTH_SHORT).show();
            setStatus("Whole mux recording saved.");
        }
    }

    public static boolean checkAndRequestStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // The all-files screen is not on every box. The old code retried the same intent
                // in the catch and let the second throw out, so tapping Record on a device
                // without that Settings screen crashed the app instead of saying why.
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "no all-files access screen on this device", e);
                }
                Toast.makeText(activity, "Allow storage access to record", Toast.LENGTH_LONG).show();
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                }, 1001);
                Toast.makeText(activity, "Allow storage access to record", Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    private synchronized void stopWholeStreamRecording() {
        isRecordingWhole = false;
        if (wholeStreamOutput != null) {
            try {
                wholeStreamOutput.flush();
                wholeStreamOutput.close();
            } catch (Exception ignored) {
                // already closing for teardown; nothing here is still needed
            }
            wholeStreamOutput = null;
        }
        if (actionRecord != null) {
            actionRecord.setText("Record");
            actionRecord.setTextColor(ContextCompat.getColor(this, R.color.odtv_on_surface));
        }
    }

    // ------------------------------------------------------------------ data broadcast + remote

    private void setupRemote() {
        playerFrame = (android.widget.FrameLayout) findViewById(R.id.player_frame);
        bml = new BmlController(this, playerFrame, new BmlController.Host() {
            @Override
            public void onVideoRect(float l, float t, float r, float b, boolean invisible) {
                videoFrac = (invisible || (l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f))
                        ? null : new float[] {l, t, r, b};
                applyVideoRect();
            }

            @Override
            public void onStatus(String text) { setStatus(text); }

            @Override
            public void onClosed() {
                videoFrac = null;
                applyVideoRect();
            }
        });
        playerFrame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (videoFrac != null && (r - l != or - ol || b - t != ob - ot)) applyVideoRect();
            }
        });



        remotePanel = new RemotePanel(this, new RemotePanel.Listener() {
            @Override
            public void onKey(int code) { remoteKey(code); }

            @Override
            public void onExit() { bml.close(); }
        });
        remotePanel.setVisibility(View.GONE);
        android.view.ViewGroup holder = (android.view.ViewGroup) findViewById(R.id.content_frame);
        // Last child = top of the z-order, full size: the panel divides its height by its nine rows.
        holder.addView(remotePanel, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void remoteKey(int code) {
        if (!bml.isActive()) {
            if (code != BmlController.DATA) {
                setStatus("Press DATA first to open the data broadcast.");
                return;
            }
            ServiceInfo svc = findService(demux.getServices(), selectedServiceId);
            if (svc == null || !svc.pmtSeen) {
                setStatus("No service selected yet.");
                return;
            }
            boolean hasData = false;
            for (ph.opendtv.ts.StreamInfo st : svc.streams) hasData |= st.isDatacast();
            if (!hasData) {
                setStatus("This service has no data broadcast.");
                return;
            }
            bmlServiceId = svc.serviceId;
            bml.open(demux, svc);
            // The page takes the keys now; on the Remote tab the pad keeps the D-pad, so
            // put focus on it rather than leaving the remote off-screen for the arrows.
            if (remotePanel != null && currentTab == TAB_REMOTE) remotePanel.focusFirst();
        }
        bml.tap(code);
    }

    /** BML moves/shrinks the video: place the PlayerView inside the frame accordingly. */
    private void applyVideoRect() {
        android.widget.FrameLayout.LayoutParams lp;
        int w = playerFrame.getWidth(), h = playerFrame.getHeight();
        if (videoFrac == null || w == 0 || h == 0) {
            lp = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            lp = new android.widget.FrameLayout.LayoutParams(Math.max(1, Math.round((videoFrac[2] - videoFrac[0]) * w)),
                    Math.max(1, Math.round((videoFrac[3] - videoFrac[1]) * h)));
            lp.leftMargin = Math.round(videoFrac[0] * w);
            lp.topMargin = Math.round(videoFrac[1] * h);
        }
        playerView.setLayoutParams(lp);
    }

    /**
     * The framework resolves BACK on key UP, so consuming the DOWN in dispatchKeyEvent
     * never stopped the activity from finishing - with a data broadcast on screen the
     * only way out was quitting the app. Closing it here catches every BACK, from a
     * remote or a gesture.
     */
    @Override
    public void onBackPressed() {
        boolean bmlUp = bml != null && bml.isActive();
        Log.d(TAG, "back: bml active=" + bmlUp + " tab=" + currentTab);
        if (bmlUp) {
            bml.close();
            View tab = findViewById(TAB_LABEL_IDS[currentTab]);
            if (tab != null) tab.requestFocus();
            setStatus("Data broadcast closed.");
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            View focus = getCurrentFocus();
            String id;
            try {
                id = focus == null || focus.getId() == View.NO_ID ? "?" : getResources().getResourceEntryName(focus.getId());
            } catch (RuntimeException ignored) {
                id = "?";
            }
            Log.d(TAG, "key " + e.getKeyCode() + " focus=" + (focus == null ? "null" : focus.getClass().getSimpleName() + "/" + id));
        }
        // A live data broadcast usually owns the D-pad so the page can be driven. The one
        // exception is the Remote tab: the on-screen pad is the remote there, so the D-pad
        // moves between its keys - otherwise the colour keys, which this set's remote does
        // not have, were unreachable. Only the D-pad steps aside; digits and the colour
        // keys still reach the page directly, and the pad's arrow/OK keys send theirs.
        int kc = e.getKeyCode();
        boolean dPadKey = kc == android.view.KeyEvent.KEYCODE_DPAD_UP
                || kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                || kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                || kc == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || kc == android.view.KeyEvent.KEYCODE_ENTER;
        boolean padOwnsDpad = currentTab == TAB_REMOTE && dPadKey;
        if (bml != null && bml.isActive() && !padOwnsDpad
                && e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int code = -1;
            switch (kc) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP: code = BmlController.UP; break;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN: code = BmlController.DOWN; break;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT: code = BmlController.LEFT; break;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: code = BmlController.RIGHT; break;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER: code = BmlController.ENTER; break;
                case android.view.KeyEvent.KEYCODE_PROG_RED: code = BmlController.RED; break;
                case android.view.KeyEvent.KEYCODE_PROG_GREEN: code = BmlController.GREEN; break;
                case android.view.KeyEvent.KEYCODE_PROG_YELLOW: code = BmlController.YELLOW; break;
                case android.view.KeyEvent.KEYCODE_PROG_BLUE: code = BmlController.BLUE; break;
                case 230: code = BmlController.DATA; break;   // KEYCODE_TV_DATA_SERVICE
                default:
                    if (kc >= android.view.KeyEvent.KEYCODE_0 && kc <= android.view.KeyEvent.KEYCODE_9)
                        code = BmlController.DIGIT0 + (kc - android.view.KeyEvent.KEYCODE_0);
            }
            if (code >= 0) {
                bml.tap(code);
                return true;
            }
        }
        // CH up/down on the remote step the multiplex: on this screen a "channel" is a
        // frequency, not a service. Deliberately after the BML block, so a data
        // broadcast keeps first refusal on its own keys.
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0
                && (e.getKeyCode() == android.view.KeyEvent.KEYCODE_CHANNEL_UP
                || e.getKeyCode() == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            stepChannel(e.getKeyCode() == android.view.KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * CH up/down on the remote step the frequency, not the saved mux list: on this
     * screen the band is the menu (the same order the picker shows) and it works with
     * an empty list, which is what the remote's channel keys are for.
     */
    private void stepChannel(int direction) {
        if (!tunerReady()) return;
        int at = bandIndex(currentKhz());
        if (at < 0) at = 18 - UHF_FIRST;                 // nothing tuned yet: land on CH 18
        int next = at + direction;
        if (next < 0 || next >= bandCount()) {
            setStatus(direction > 0 ? "Already at the top of the band."
                    : "Already at the bottom of the band.");
            return;
        }
        manualTune(bandKhz(next));
    }

    private void bindTab(int id, final int tab) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(tab, tab > currentTab ? 1 : -1);
            }
        });
    }

    /**
     * Portrait preview: 16:9 of the card width (the shell gives the box a fallback
     * height). In landscape the preview is a weighted column, so leave it alone.
     */
    private void sizePreview() {
        previewBox = findViewById(R.id.preview_box);
        if (previewBox == null) return;
        if (getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            int width = getResources().getDisplayMetrics().widthPixels - dpi(32);
            ViewGroup.LayoutParams lp = previewBox.getLayoutParams();
            lp.height = Math.round(width * 9f / 16f);
            previewBox.setLayoutParams(lp);
            return;
        }
        // Landscape: give the column the width of a 16:9 picture at the row's height, so
        // a 16:9 service fills the frame instead of letterboxing inside a taller box.
        // A listener, not a one-shot post: the row's height is not final on the first
        // pass, and reading it too early left the phone's column 24 dp narrow.
        final View row = (View) previewBox.getParent();
        if (row == null) return;
        row.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int oldL, int oldT, int oldR, int oldB) {
                fitPreviewAspect(v);
            }
        });
        previewBox.post(new Runnable() {
            @Override
            public void run() {
                View r = (View) previewBox.getParent();
                if (r != null) fitPreviewAspect(r);
            }
        });
    }

    /** Sets the landscape preview column to a 16:9 box for the row's current height. */
    private void fitPreviewAspect(View row) {
        int height = row.getHeight();
        if (height <= 0 || height == lastPreviewHeight) return;
        lastPreviewHeight = height;
        int width = Math.round(height * 16f / 9f);
        // 16:9, but never at the cost of a card narrower than the PID table needs.
        int cap = row.getWidth() - dpi(360) - dpi(12);
        if (cap > 0) width = Math.min(width, cap);
        ViewGroup.LayoutParams lp = previewBox.getLayoutParams();
        if (lp.width == width) return;
        lp.width = width;
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).weight = 0f;
        }
        previewBox.setLayoutParams(lp);
    }

    private void showIfPresent(View v, boolean visible) {
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * The rotated phone gives the status board a 454 dp column instead of the 920 dp the
     * set gives it. With the bitrate group on that line the service name has to ellipsise,
     * so the group is dropped there: the Signal card already shows the bitrate as tile one.
     * Portrait and the TV keep it (the id is absent from the portrait board).
     */
    private void trimBoardForNarrowColumn() {
        View group = findViewById(R.id.board_bitrate_group);
        if (group == null) return;
        android.content.res.Configuration c = getResources().getConfiguration();
        boolean shortLandscape =
                c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        && c.screenHeightDp < 500;
        if (shortLandscape) group.setVisibility(View.GONE);
    }

    private void setUpTabGestures() {
        tabGestures = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float vx, float vy) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        // Horizontal, deliberate: a vertical scroll must stay a scroll.
                        if (Math.abs(dx) < dpi(56) || Math.abs(dx) < Math.abs(dy) * 1.6f) return false;
                        boolean previous = dx > 0;
                        return switchTab(previous ? currentTab - 1 : currentTab + 1, previous ? -1 : 1);
                    }
                });
    }

    /** Swipe left/right anywhere in the tab content. Only touch is claimed; the D-pad is untouched. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (tabGestures != null) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                touchInContent = contentFrame != null && contentFrame.getGlobalVisibleRect(hitRect)
                        && hitRect.contains((int) e.getX(), (int) e.getY());
            }
            if (touchInContent) tabGestures.onTouchEvent(e);
        }
        return super.dispatchTouchEvent(e);
    }

    /** @param dir -1 slides the new panel in from the left, +1 from the right. */
    private boolean switchTab(int tab, int dir) {
        if (tab < TAB_SIGNAL || tab > TAB_REMOTE || tab == currentTab) return false;
        currentTab = tab;
        updateTabVisuals();
        refresh();
        animateContent(dir);
        return true;
    }

    private void animateContent(int dir) {
        View panel = visibleContentPanel();
        if (panel == null) return;
        float shift = Math.min(dpi(64), getResources().getDisplayMetrics().widthPixels * 0.14f);
        panel.animate().cancel();
        panel.setAlpha(0f);
        panel.setTranslationX(dir * shift);
        panel.animate().alpha(1f).translationX(0f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** The card's visible child is the current tab: last one wins (the blocking card is last). */
    private View visibleContentPanel() {
        if (contentFrame == null) return null;
        for (int i = contentFrame.getChildCount() - 1; i >= 0; i--) {
            View child = contentFrame.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) return child;
        }
        return null;
    }

    private static final int[] TAB_LABEL_IDS = {R.id.tab_signal, R.id.tab_services, R.id.tab_pids,
            R.id.tab_tables, R.id.tab_epg, R.id.tab_datacast, R.id.tab_graph, R.id.tab_logs, R.id.tab_remote};

    /** Nine tabs: gold label for the current one, and one indicator that follows it. */
    private void updateTabVisuals() {
        for (int i = 0; i < TAB_LABEL_IDS.length; i++) {
            TextView label = findViewById(TAB_LABEL_IDS[i]);
            label.setTextColor(ContextCompat.getColor(this,
                    i == currentTab ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
        }
        moveTabIndicator(lastIndicatorTab >= 0 && lastIndicatorTab != currentTab);
        lastIndicatorTab = currentTab;
        keepSelectedTabVisible();
    }

    /**
     * The nine tabs do not fit a phone, so the row can be scrolled. If the gold tab
     * is not completely in view, nudge it back by the smallest amount that shows it -
     * never re-centre, or the row would jump on every selection. Testing the edges
     * (not "entirely out of view") matters after a rotation, when the restored scroll
     * position can leave the current tab half clipped, which reads as "nothing selected".
     */
    private void keepSelectedTabVisible() {
        final TextView label = findViewById(TAB_LABEL_IDS[currentTab]);
        final android.widget.HorizontalScrollView row = findViewById(R.id.analyzer_tab_scroll);
        if (label == null || row == null || label.getWidth() == 0) return;
        int left = label.getLeft();
        int right = left + label.getWidth();
        int viewLeft = row.getScrollX();
        int viewRight = viewLeft + row.getWidth();
        int pad = dpi(8);
        if (left < viewLeft) {
            row.scrollTo(Math.max(0, left - pad), 0);
        } else if (right > viewRight) {
            row.scrollTo(Math.max(0, right - row.getWidth() + pad), 0);
        }
    }

    /**
     * Slides the underline to the current tab: same x/width animation the watch
     * tabs have. Width cannot go through ViewPropertyAnimator, hence the
     * ValueAnimator setting the layout params each frame.
     */
    private void moveTabIndicator(final boolean animate) {
        if (tabIndicator == null) return;
        final View label = findViewById(TAB_LABEL_IDS[currentTab]);
        if (label == null) return;
        if (label.getWidth() == 0) {
            tabIndicator.post(new Runnable() {
                @Override
                public void run() { moveTabIndicator(false); }
            });
            return;
        }
        final android.view.ViewGroup.LayoutParams lp = tabIndicator.getLayoutParams();
        final int fromX = Math.round(tabIndicator.getTranslationX());
        final int fromW = lp.width;
        final int toX = label.getLeft();
        final int toW = label.getWidth();
        if (!animate || fromW <= 0) {
            lp.width = toW;
            tabIndicator.setLayoutParams(lp);
            tabIndicator.setTranslationX(toX);
            return;
        }
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            lp.width = Math.round(fromW + (toW - fromW) * f);
            tabIndicator.setLayoutParams(lp);
            tabIndicator.setTranslationX(fromX + (toX - fromX) * f);
        });
        anim.start();
    }

    // ------------------------------------------------------------------ sources

    private void startFile(Uri uri) {
        final Uri u = uri;
        FileTsSource.Opener opener = new FileTsSource.Opener() {
            @Override
            public java.io.InputStream open() throws java.io.IOException {
                return getContentResolver().openInputStream(u);
            }
        };
        // Unthrottled: analysis should finish as fast as the CPU allows. When a service is playing,
        // back-pressure from the player (TsPipe.awaitSpace) still paces the file. Stops at EOF so the
        // counters and EWBS log stay readable. FileTsSource auto-detects 188/192/204-byte packets.
        startSource(new FileTsSource("File: " + uri.getLastPathSegment(), opener, 0L, false), true);
    }

    private void connectUsb() {
        if (isTunerSource(source)) return;   // already running or initialising
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (!isTuner(d)) continue;
            if (usbManager.hasPermission(d)) {
                startSourceFor(d);
            } else {
                int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
                usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
            }
            return;
        }
        setStatus("No supported tuner found. Supported: " + TunerRegistry.supportedNames() + ".");
    }

    private static boolean isTuner(UsbDevice d) {
        return TunerRegistry.find(d) != null;
    }

    private static boolean isTunerSource(TsSource s) {
        return s instanceof TunerSource;
    }

    private void startSourceFor(UsbDevice d) {
        ph.opendtv.source.TunerProvider provider = TunerRegistry.find(d);
        if (provider != null) {
            int khz = currentKhz();
            // A source tunes exactly what it is given, so -1 is not "no opinion", it is a failed
            // tune before the TS port opens. With nothing tuned yet, land on CH 18 - the same
            // channel the band keys fall back to (stepChannel) when no frequency is known.
            if (khz <= 0) khz = uhfKhz(18);
            startSource(provider.create(usbManager, this, d, khz), false);
        } else {
            setStatus("Unsupported tuner.");
        }
    }

    /** @param backpressure true for files (pace by the player), false for live sources (drop oldest). */
    private void startSource(TsSource s, final boolean backpressure) {
        stopSource();
        AacTransport.clear();
        final TsDemux d = new TsDemux();
        demux = d;
        selectedServiceId = -1;
        serviceListKey = "";
        player.stop();
        player.clearMediaItems();
        pipe.clear();
        source = s;
        setStatus(s.name() + ": starting ...");
        final TsSource starting = s;
        // Tuner bring-up (firmware download, init, first tune) takes seconds: never on the UI thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                starting.start(sourceListener(backpressure));
            }
        }, "SourceStart").start();
    }

    private TsSource.Listener sourceListener(final boolean backpressure) {
        return new TsSource.Listener() {
            @Override
            public void onData(byte[] buf, int off, int len) {
                if (backpressure) pipe.awaitSpace(len, 500);
                demux.feed(buf, off, len);   // read the field each time: a retune swaps in a fresh demux
                if (isRecordingWhole && wholeStreamOutput != null) {
                    synchronized (AnalyzerActivity.this) {
                        if (wholeStreamOutput != null) {
                            try {
                                wholeStreamOutput.write(buf, off, len);
                            } catch (Exception e) {
                                Log.e(TAG, "Whole stream write error", e);
                            }
                        }
                    }
                }
            }

            @Override
            public void onError(final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(message);
                    }
                });
            }

            @Override
            public void onEnd() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("End of file.");
                    }
                });
            }
        };
    }

    private void stopSource() {
        if (source != null) {
            source.stop();
            source = null;
        }
    }

    // ------------------------------------------------------------------ playback

    private void selectService(ServiceInfo svc) {
        selectedServiceId = svc.serviceId;
        player.stop();
        pipe.clear();
        captionOverlay.reset();
        demux.setPacketSink(new ServiceFilter(pipe, demux.getTsId(), svc).setCaptionSink2(captionOverlay));

        DataSource.Factory factory = new DataSource.Factory() {
            @Override
            public DataSource createDataSource() {
                return new TsPipeDataSource(pipe);
            }
        };
        MediaSource ms = new ProgressiveMediaSource.Factory(factory, new TsExtractorFactory())
                .createMediaSource(MediaItem.fromUri("tspipe://live"));
        player.setMediaSource(ms);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    // ------------------------------------------------------------------ periodic UI refresh

    private void refresh() {
        TsDemux d = demux;
        List<ServiceInfo> services = d.getServices();
        lastSnapshot = d.sample();
        updateServiceList(services);

        ServiceInfo sel = findService(services, selectedServiceId);
        updateInfoPanel(sel);
        updateSignal();
        updateStatusChips(d, sel);
        updateMuxLabel(currentMuxIndex);
        ewbsBanner.update(d);
        pushHistory();

        boolean remote = currentTab == TAB_REMOTE;
        if (remotePanel != null) remotePanel.setVisibility(remote ? View.VISIBLE : View.GONE);
        if (bml != null && bml.isActive() && (bml.demux() != d || bmlServiceId != selectedServiceId)) bml.close();

        boolean haveSource = source != null;
        SignalStatus st = haveSource ? source.getSignalStatus() : null;
        // A tuner without a lock is blocked. A file has no lock to offer and still demuxes,
        // so it is a data source, not a blocked one.
        boolean tuner = haveSource && source.supportsTuning();
        boolean blocked = !haveSource || (tuner && (st == null || !st.locked));
        // The logs and the on-screen remote are useful without a signal: never cover them.
        boolean standalone = currentTab == TAB_LOGS || remote;

        if (emptyPanel != null) {
            emptyPanel.setVisibility(blocked && !standalone ? View.VISIBLE : View.GONE);
            if (blocked) {
                if (!haveSource) {
                    emptyTitle.setText("The analyzer needs a tuner");
                    emptyText.setText("Connect the USB dongle and the signal board; muxes, services and tables appear here.");
                    emptyHint.setText("IT9175 · Sony 9308 · Siano Rio");
                } else {
                    emptyTitle.setText("No signal on this mux");
                    emptyText.setText("Check the antenna, or pick another channel from the Channels action.");
                    emptyHint.setText("0 % strength · 0 % quality");
                }
            }
        }

        boolean signal = !blocked && currentTab == TAB_SIGNAL;
        boolean servs = !blocked && currentTab == TAB_SERVICES;
        boolean pids = !blocked && currentTab == TAB_PIDS;
        boolean tables = !blocked && currentTab == TAB_TABLES;
        boolean graph = !blocked && currentTab == TAB_GRAPH;
        boolean logs = currentTab == TAB_LOGS;
        boolean textTab = !blocked && (currentTab == TAB_EPG || currentTab == TAB_DATACAST);

        if (signalPanel != null) signalPanel.setVisibility(signal ? View.VISIBLE : View.GONE);
        if (signal) {
            fitSignalPanel();
            // The same 30 s of bitrate the Graph tab draws, as a glance-sized sparkline.
            if (signalSpark != null && signalSpark.getVisibility() == View.VISIBLE) {
                float[] series = new float[histCount];
                if (histCount > 0) System.arraycopy(histBitrate, HIST - histCount, series, 0, histCount);
                signalSpark.setSeries(series);
            }
        }
        if (servicesPanel != null) servicesPanel.setVisibility(servs ? View.VISIBLE : View.GONE);
        if (pidsPanel != null) pidsPanel.setVisibility(pids ? View.VISIBLE : View.GONE);
        if (tablesPanel != null) tablesPanel.setVisibility(tables ? View.VISIBLE : View.GONE);
        if (graphPanel != null) graphPanel.setVisibility(graph ? View.VISIBLE : View.GONE);
        if (logsPanel != null) logsPanel.setVisibility(logs ? View.VISIBLE : View.GONE);
        if (tvContent != null) {
            tvContent.setVisibility(textTab ? View.VISIBLE : View.GONE);
            if (textTab) tvContent.setText(tabText(d, services, sel));
        }
        if (pids) updatePidTable();
        if (tables) updateTables(d, services, sel);
        if (graph) updateGraphPanel(d, sel);
        if (logs) renderLogs();
        if (signal) updateSignalTiles(services, sel);
        updateTabVisuals();
    }

    private String tabText(TsDemux d, List<ServiceInfo> services, ServiceInfo sel) {
        switch (currentTab) {
            case TAB_EPG: return epgText(d, sel);
            case TAB_DATACAST: return datacastText(d, services, sel);
            default: return "";
        }
    }

    // ------------------------------------------------------------------ PIDs tab

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private int dpi(float v) {
        return Math.round(v * density());
    }

    /** One mono cell of the PID table: fixed width, or weight 1 for the description. */
    private TextView pidCell(String text, int widthDp, int gravity, boolean accent) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(gravity | android.view.Gravity.CENTER_VERTICAL);
        tv.setTextColor(ContextCompat.getColor(this, accent ? R.color.odtv_accent : R.color.odtv_on_surface));
        LinearLayout.LayoutParams lp;
        if (widthDp == 0) {
            lp = new LinearLayout.LayoutParams(0, dpi(26), 1f);
            lp.leftMargin = dpi(10);
        } else {
            lp = new LinearLayout.LayoutParams(dpi(widthDp), dpi(26));
        }
        tv.setLayoutParams(lp);
        return tv;
    }

    /** PIDs tab: one row per PID. Rows keep their first-seen order so the table does not jump. */
    private void updatePidTable() {
        AnalyzerSnapshot s = lastSnapshot;
        if (s == null || pidsRows == null) return;
        // The card is what has to fit the text, not the screen: the set's card is ~370 dp
        // even though the screen is 960, and "Video H.264 (sid 160)" would be clipped.
        int cardDp = 0;
        if (contentFrame != null) {
            cardDp = Math.round((contentFrame.getWidth()
                    - contentFrame.getPaddingLeft() - contentFrame.getPaddingRight())
                    / getResources().getDisplayMetrics().density);
        }
        boolean narrow = cardDp > 0 && cardDp < 520;

        StringBuilder key = new StringBuilder(narrow ? "n" : "w");
        for (AnalyzerSnapshot.Row r : s.rows) key.append(r.pid).append(' ');
        if (!key.toString().equals(pidKey)) {
            pidKey = key.toString();
            pidsRows.removeAllViews();
            pidCells.clear();
            for (AnalyzerSnapshot.Row r : s.rows) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                boolean psi = isPsiPid(r.label);
                TextView pid = pidCell(String.format(Locale.US, "0x%04X", r.pid), 64, android.view.Gravity.START, false);
                TextView kbps = pidCell("", 64, android.view.Gravity.END, false);
                TextView pkts = pidCell("", 64, android.view.Gravity.END, false);
                TextView cc = pidCell("", 40, android.view.Gravity.END, false);
                TextView label = pidCell(narrow ? stripKind(safe(r.label)) : safe(r.label), 0,
                        android.view.Gravity.START, psi);
                row.addView(pid);
                row.addView(kbps);
                row.addView(pkts);
                row.addView(cc);
                row.addView(label);
                row.setPadding(0, dpi(2), 0, dpi(2));
                pidsRows.addView(row);
                pidCells.add(new TextView[]{pid, kbps, pkts, cc, label});
            }
        }

        for (int i = 0; i < pidCells.size() && i < s.rows.size(); i++) {
            AnalyzerSnapshot.Row r = s.rows.get(i);
            TextView[] c = pidCells.get(i);
            c[0].setText(String.format(Locale.US, "0x%04X", r.pid));
            c[1].setText(String.format(Locale.US, "%.1f", r.kbps));
            c[2].setText(String.valueOf(r.packets));
            c[3].setText(String.valueOf(r.ccErrors));
            c[3].setTextColor(ContextCompat.getColor(this,
                    r.ccErrors > 0 ? R.color.odtv_live : R.color.odtv_on_surface_variant));
        }
        if (pidsFooter != null) {
            pidsFooter.setText(String.format(Locale.US,
                    "%d PIDs · %.2f Mbps · null %d · CC %d · TEI %d · sync %d",
                    s.rows.size(), s.totalKbps / 1000.0, s.nullPackets, s.ccErrors, s.teiPackets, s.syncLosses));
        }
    }

    /** PSI/SI tables are the interesting rows: PAT, PMT, NIT, SDT, EIT, TDT, AIT. */
    private static boolean isPsiPid(String label) {
        String l = safe(label).toUpperCase(Locale.US);
        return l.startsWith("PAT") || l.startsWith("PMT") || l.startsWith("NIT")
                || l.startsWith("SDT") || l.startsWith("EIT") || l.startsWith("TDT")
                || l.startsWith("AIT") || l.startsWith("CAT") || l.startsWith("TOT");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** "Video H.264 (sid 160)" -> "H.264 160", only for the narrow layout. */
    private static String stripKind(String label) {
        String s = label;
        for (String prefix : new String[]{"Video ", "Audio ", "Data "}) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length());
                break;
            }
        }
        int i = s.indexOf(" (sid ");
        if (i > 0 && s.endsWith(")")) s = s.substring(0, i) + " " + s.substring(i + 6, s.length() - 1);
        return s.replace("DSM-CC data carousel", "DSM-CC carousel");
    }

    private void updateSignalTiles(List<ServiceInfo> services, ServiceInfo sel) {
        AnalyzerSnapshot s = lastSnapshot;
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
                : "selected " + sel.virtualNumber + " " + channelName(sel));
        if (signalLine3 != null) signalLine3.setText(playerDiag());
    }
    private void updateServiceList(List<ServiceInfo> services) {
        StringBuilder key = new StringBuilder();
        List<ServiceInfo> shown = new ArrayList<>();
        for (ServiceInfo s : services) {
            if (!s.pmtSeen || s.streams.isEmpty()) continue;
            shown.add(s);
            key.append(s.serviceId).append(':').append(s.name).append(':').append(s.virtualNumber)
                    .append(s.encrypted ? ":CA" : "").append(';');
        }
        spinnerSids.clear();
        for (ServiceInfo s : shown) spinnerSids.add(s.serviceId);

        if (!key.toString().equals(serviceListKey)) {
            serviceListKey = key.toString();
            if (servicesList != null) {
                servicesList.removeAllViews();
                serviceChecks.clear();
                float dp = getResources().getDisplayMetrics().density;
                for (int i = 0; i < shown.size(); i++) {
                    final ServiceInfo s = shown.get(i);
                    View row = getLayoutInflater().inflate(R.layout.view_choice_row, servicesList, false);
                    TextView label = row.findViewById(R.id.choice_label);
                    StreamInfo v = s.firstVideo(), a = s.firstAudio();
                    label.setText(s.virtualNumber + "  "
                            + channelName(s)
                            + "   " + (v == null ? "no video" : v.shortCodec())
                            + (a == null ? "" : " / " + a.shortCodec())
                            + (s.encrypted ? "  [CA]" : ""));
                    label.setTextSize(14);
                    View check = row.findViewById(R.id.choice_check);
                    serviceChecks.put(s.serviceId, check);
                    check.setVisibility(s.serviceId == selectedServiceId ? View.VISIBLE : View.GONE);
                    row.setOnClickListener(v2 -> selectService(s));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    if (i > 0) lp.topMargin = Math.round(6 * dp);
                    servicesList.addView(row, lp);
                }
                if (shown.isEmpty()) {
                    TextView note = new TextView(this);
                    note.setText("No services yet. Wait for the PMT, or scan.");
                    note.setTextColor(ContextCompat.getColor(this, R.color.odtv_on_surface_variant));
                    servicesList.addView(note);
                }
            }
        }

        if (selectedServiceId == -1) {
            for (ServiceInfo s : shown) {
                if (s.hasVideo()) { selectService(s); break; }
            }
        }
        // The check mark follows the current service without rebuilding the rows (a rebuild
        // would throw away the TV's focus every time a service is picked).
        for (java.util.Map.Entry<Integer, View> e : serviceChecks.entrySet()) {
            e.getValue().setVisibility(e.getKey() == selectedServiceId ? View.VISIBLE : View.GONE);
        }
    }
    private void updateInfoPanel(ServiceInfo sel) {
        String net = demux.getNetworkName();
        if (net == null || net.isEmpty()) net = (sel != null && !sel.provider.isEmpty()) ? sel.provider : "--";

        if (sel == null) {
            setText(boardChannel, "");
            setText(boardMeta, "TS -- · NIT " + net + " · no service yet");
            return;
        }
        setText(boardChannel, sel.virtualNumber + "  "
                + (sel.name.isEmpty() ? "Service " + sel.serviceId : sel.name));

        StreamInfo v = sel.firstVideo(), a = sel.firstAudio();
        String codec = (v == null ? "no video" : v.shortCodec()) + " / "
                + (a == null ? "--" : a.shortCodec()) + (sel.encrypted ? "  [CA]" : "");
        String payload = AacTransport.get();
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
        setText(boardMeta, meta);
    }

    private void updateSignal() {
        int strength = 0, quality = 0;
        boolean locked = false;
        String detail;
        if (source == null) {
            detail = "no source";
        } else if (source.getSignalStatus() == null) {
            detail = source.supportsTuning() ? "waiting for tuner" : "file input";
        } else {
            SignalStatus st = source.getSignalStatus();
            strength = Math.max(st.strengthPercent, 0);
            quality = st.locked ? Math.max(st.qualityPercent, 0) : 0;
            locked = st.locked;
            String dropped = "";
            detail = String.format(Locale.US, "%s · BER %s%s",
                    st.dbm == Integer.MIN_VALUE ? "-- dBm" : st.dbm + " dBm",
                    Double.isNaN(st.ber) ? "n/a" : String.format(Locale.US, "%.1e", st.ber), dropped);
        }
        boolean file = source != null && !source.supportsTuning();
        setText(boardState, source == null ? "NO TUNER" : file ? "FILE" : locked ? "LOCKED" : "NO LOCK");
        if (boardDot != null) {
            boardDot.setTextColor(ContextCompat.getColor(AnalyzerActivity.this,
                    source == null ? R.color.odtv_on_surface_variant
                            : file ? R.color.odtv_accent
                            : locked ? R.color.odtv_ok : R.color.odtv_warning));
        }
        setText(boardStrengthVal, strength + " %");
        setText(boardQualityVal, quality + " %");
        // Landscape has no board: the same two numbers and bars live in the Signal card.
        if (sigStrengthVal != null) {
            sigStrengthVal.setText(strength + " %");
            sigQualityVal.setText(quality + " %");
            setMeter(sigStrengthFill, sigStrengthRest, strength);
            setMeter(sigQualityFill, sigQualityRest, quality);
        }
        if (stripRight != null) stripRight.setText(detail);
        if (lastSnapshot != null && boardBitrate != null) {
            double mbps = lastSnapshot.totalKbps / 1000.0;
            boardBitrate.setText(mbps <= 0 ? "—" : String.format(Locale.US, "%.1f", mbps));
        }
    }

    /** Board views only exist where the board is in the layout (portrait). */
    private static void setText(TextView v, CharSequence s) {
        if (v != null) v.setText(s);
    }

    /**
     * The Signal panel does not scroll, so a line that does not fit used to be drawn
     * half a glyph tall at the card's edge (133 dp on the rotated phone). Hide the
     * lines that do not fit instead, from the bottom up: the counters line and the
     * selected-service line matter more than the player diagnostic.
     */
    private void fitSignalPanel() {
        if (contentFrame == null || signalPanel == null) return;
        if (signalPanel.getVisibility() != View.VISIBLE) return;
        int avail = contentFrame.getHeight()
                - contentFrame.getPaddingTop() - contentFrame.getPaddingBottom();
        int width = contentFrame.getWidth()
                - contentFrame.getPaddingLeft() - contentFrame.getPaddingRight();
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
            signalPanel.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (signalPanel.getMeasuredHeight() <= avail) return;
            if (hideable[i] != null) hideable[i].setVisibility(View.GONE);
        }
    }

    /** Board status chips, same words as the watch strip: DATA / CC / LOCK / EWBS. */
    private void updateStatusChips(TsDemux d, ServiceInfo sel) {
        boolean data = false;
        boolean encrypted = false;
        if (sel != null) {
            encrypted = sel.encrypted;
            for (StreamInfo s : sel.streams) data |= s.isDatacast();
        }
        boolean captions = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("closed_captions", true);
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
        chip.setTextColor(ContextCompat.getColor(this, active ? activeColour : R.color.odtv_on_surface_variant));
        chip.setAlpha(active ? 1f : 0.28f);
    }

    /** One line about what ExoPlayer is doing: helps tell "no data" from "no decoder" from "decoder too slow". */
    private String playerDiag() {
        if (selectedServiceId == -1) return "";
        String[] states = {"idle", "buffering", "ready", "ended"};
        int ps = player.getPlaybackState();
        androidx.media3.common.Format vf = player.getVideoFormat();
        androidx.media3.exoplayer.DecoderCounters dc = player.getVideoDecoderCounters();
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

    // ------------------------------------------------------------------ tuning: scan / manual

    private static String channelLabel(int khz) {
        if (khz >= 473143 && (khz - 473143) % 6000 == 0) {
            return "UHF " + (14 + (khz - 473143) / 6000) + " · " + String.format(Locale.US, "%.3f", khz / 1000.0);
        }
        if (khz >= 177143 && khz <= 177143 + 6 * 6000 && (khz - 177143) % 6000 == 0) {
            return "VHF " + (7 + (khz - 177143) / 6000) + " · " + String.format(Locale.US, "%.3f", khz / 1000.0);
        }
        return String.format(Locale.US, "%.3f MHz", khz / 1000.0);
    }

    /** The frequency the board is showing: what the user last picked, or the selected mux. */
    private int currentKhz() {
        if (currentTunedKhz > 0) return currentTunedKhz;
        if (currentMuxIndex >= 0 && currentMuxIndex < muxes.size()) return muxes.get(currentMuxIndex).khz;
        return -1;
    }

    private boolean tunerReady() {
        if (source == null || !source.supportsTuning()) {
            setStatus("Connect the USB tuner first (USB button).");
            return false;
        }
        if (!source.isReady()) {
            setStatus("Tuner is still initialising, please wait ...");
            return false;
        }
        return true;
    }

    /** Switches to a multiplex: fresh demux and player, then tune (off the UI thread; lock wait takes seconds). */
    private void tuneTo(final int khz) {
        if (!tunerReady()) return;
        AacTransport.clear();
        currentTunedKhz = khz;
        final TsSource src = source;
        selectedServiceId = -1;
        serviceListKey = "";
        player.stop();
        player.clearMediaItems();
        pipe.clear();
        demux = new TsDemux();
        setStatus("Tuning " + channelLabel(khz) + " ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = src.tune(khz);
                if (ok) {
                    pipe.clear();
                    demux = new TsDemux();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(ok ? "Locked " + channelLabel(khz) : "No lock on " + channelLabel(khz));
                    }
                });
            }
        }, "Tune").start();
    }

    // ------------------------------------------------------------------ channels

    private static int uhfKhz(int channel) {
        return 473143 + 6000 * (channel - 14);
    }

    /** The band order the picker shows, as a flat list for the channel keys. */
    private static final int UHF_FIRST = 14, UHF_LAST = 69, VHF_FIRST = 7, VHF_LAST = 13;

    private static int bandCount() {
        return (UHF_LAST - UHF_FIRST + 1) + (VHF_LAST - VHF_FIRST + 1);
    }

    private static int bandKhz(int index) {
        int uhf = UHF_LAST - UHF_FIRST + 1;
        return index < uhf ? uhfKhz(UHF_FIRST + index) : vhfKhz(VHF_FIRST + (index - uhf));
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
        return String.format(Locale.US, "CH %d \u00B7 %.3f MHz", channel, khz / 1000.0);
    }

    /**
     * Every channel the tuner can be asked for, in the owner's order: UHF 14-51,
     * a rule, UHF 52-69, a rule, then VHF 7-13. No "seen on this tuner" group -
     * the menu is the same list on every device.
     */
    private void showChannelPicker() {
        if (!tunerReady()) return;

        View listRoot = getLayoutInflater().inflate(R.layout.view_channel_list, null);
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

        holder[0] = new MaterialAlertDialogBuilder(this)
                .setTitle("Channels")
                .setView(listRoot)
                .setNegativeButton("Close", null)
                .create();
        holder[0].show();
        if (holder[0].getWindow() != null) {
            holder[0].getWindow().setLayout(
                    Math.round(getResources().getDisplayMetrics().widthPixels * 0.9f),
                    Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f));
        }
        scroll.post(new Runnable() {
            @Override
            public void run() {
                // Instant, not smooth: an animating list swallows the first tap.
                if (currentRow[0] != null) {
                    scroll.scrollTo(0, Math.max(0, currentRow[0].getTop() - dpi(48)));
                }
            }
        });
    }

    private void addChannelRows(LinearLayout list, int from, int to, final AlertDialog[] holder,
                                int now, View[] currentRow) {
        for (int channel = from; channel <= to; channel++) {
            final int khz = channel <= 13 ? vhfKhz(channel) : uhfKhz(channel);
            View row = getLayoutInflater().inflate(R.layout.view_choice_row, list, false);
            row.setMinimumHeight(dpi(46));
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
            lp.topMargin = dpi(4);
            list.addView(row, lp);
        }
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10);
        tv.setLetterSpacing(0.16f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.odtv_on_surface_variant));
        tv.setPadding(dpi(6), dpi(10), dpi(6), dpi(6));
        return tv;
    }

    private View sectionRule() {
        View v = new View(this);
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.odtv_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpi(1));
        lp.topMargin = dpi(2);
        lp.bottomMargin = dpi(8);
        v.setLayoutParams(lp);
        return v;
    }

    private void manualTune(final int khz) {
        // reuse the multiplex list: remember a manually tuned channel once it locks
        AacTransport.clear();
        currentTunedKhz = khz;
        selectedServiceId = -1;
        serviceListKey = "";
        final TsSource src = source;
        player.stop();
        player.clearMediaItems();
        pipe.clear();
        demux = new TsDemux();
        setStatus("Tuning " + channelLabel(khz) + " ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = src.tune(khz);
                if (ok) {
                    pipe.clear();
                    demux = new TsDemux();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            setStatus("No lock on " + channelLabel(khz));
                            return;
                        }
                        setStatus("Locked " + channelLabel(khz));
                        int at = -1;
                        for (int i = 0; i < muxes.size(); i++) if (muxes.get(i).khz == khz) at = i;
                        if (at < 0) {
                            muxes.add(new Mux(khz, channelLabel(khz) + "  (manual)"));
                            at = muxes.size() - 1;
                            saveMuxes();
                        }
                        currentMuxIndex = at;
                        refreshMuxSpinner(at);
                    }
                });
            }
        }, "ManualTune").start();
    }

    private void refreshMuxSpinner(int select) {
        updateMuxLabel(select);
        if (muxChipRow == null) return;   // the phone has no chip row: the picker replaces it
        muxChipRow.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < muxes.size(); i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(muxShort(muxes.get(i).khz));
            chip.setTextSize(13);
            chip.setFocusable(true);
            chip.setClickable(true);
            // Always the focusable shape: a plain chip has no focused state to paint.
            chip.setBackgroundResource(R.drawable.bg_scan_chip_focusable);
            chip.setTextColor(ContextCompat.getColor(this,
                    i == select ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
            int padH = Math.round(14 * d), padV = Math.round(7 * d);
            chip.setPadding(padH, padV, padH, padV);
            chip.setOnClickListener(v -> {
                currentMuxIndex = index;
                tuneTo(muxes.get(index).khz);
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
    private void updateMuxLabel(int select) {
        int khz = currentKhz();
        if (khz <= 0 && select >= 0 && select < muxes.size()) khz = muxes.get(select).khz;
        if (khz <= 0 && !muxes.isEmpty()) khz = muxes.get(0).khz;
        String label = khz > 0 ? muxShort(khz) : "Channels";
        if (muxCurrent != null) muxCurrent.setText(label);
        if (muxLabel != null) {
            muxLabel.setText(label);
            muxLabel.setVisibility(muxCurrent == null ? View.VISIBLE : View.GONE);
        }
    }

    /** Short chip label: "UHF 15 \u00B7 479.143". */
    private String muxShort(int khz) {
        int ch = 14 + Math.round((khz - 473143) / 6000f);
        return "UHF " + ch + " \u00B7 " + String.format(Locale.US, "%.3f", khz / 1000.0);
    }

    // ------------------------------------------------------------------ Tables tab

    private static String hex4(int v) {
        return String.format(Locale.US, "%04X", Math.max(v, 0));
    }

    private static String channelName(ServiceInfo s) {
        return ph.opendtv.ui.ChannelAdapter.displayName(s);
    }

    /** SDT service_type, in the words the rest of the UI uses. */
    private static String serviceKind(ServiceInfo s) {
        if (s.serviceType == 0x01) return s.encrypted ? "TV  [CA]" : "TV";
        if (s.serviceType == 0xC0 || s.serviceType == 0x0C) return "data";
        return s.serviceType < 0 ? "--" : String.format(Locale.US, "0x%02X", s.serviceType);
    }

    /** Tables tab: NIT, SDT, PMT and EIT as four cards. */
    private void updateTables(TsDemux d, List<ServiceInfo> services, ServiceInfo sel) {
        if (nitBody == null) return;

        // --- NIT: the multiplex itself ---
        String net = d.getNetworkName();
        nitCount.setText("0x" + hex4(d.getNetworkId()));
        nitBody.setText("Name      " + (net == null || net.isEmpty() ? "--" : net) + "\n"
                + "TS ID     0x" + hex4(d.getTsId()) + "\n"
                + "Clock     " + TsUtil.formatTime(d.getClockMs()));

        // --- SDT: every service the mux advertises ---
        StringBuilder sdt = new StringBuilder();
        int sdtN = 0;
        for (ServiceInfo s : services) {
            sdt.append(String.format(Locale.US, "%4s  %-20s %s\n",
                    s.virtualNumber, channelName(s), serviceKind(s)));
            sdtN++;
        }
        sdtCount.setText(sdtN + (sdtN == 1 ? " service" : " services"));
        sdtBody.setText(sdtN == 0 ? "No SDT seen yet." : sdt.toString().trim());

        // --- PMT: the streams of the selected service ---
        if (sel == null) {
            pmtCount.setText("--");
            pmtBody.setText("Select a service in the Services tab.");
        } else {
            StringBuilder pmt = new StringBuilder();
            pmt.append("Service ").append(sel.virtualNumber).append("  ").append(channelName(sel))
                    .append("   PCR 0x").append(hex4(sel.pcrPid)).append('\n');
            for (StreamInfo st : sel.streams) {
                pmt.append(String.format(Locale.US, "0x%04X  %-24s tag %d%s\n", st.pid, st.codecName(),
                        st.componentTag, safe(st.language).isEmpty() ? "" : "  " + st.language));
            }
            if (sel.streams.isEmpty()) pmt.append("No PMT seen yet.");
            pmtCount.setText(sel.streams.size() + (sel.streams.size() == 1 ? " stream" : " streams"));
            pmtBody.setText(pmt.toString().trim());
        }

        // --- EIT: the next few events of the selected service ---
        List<EpgEvent> events = sel == null ? null : d.getEvents(sel.serviceId);
        int n = events == null ? 0 : events.size();
        eitCount.setText(n + (n == 1 ? " event" : " events"));
        if (n == 0) {
            eitBody.setText(sel == null ? "Select a service in the Services tab."
                    : "No EIT events yet for this service.");
        } else {
            StringBuilder eit = new StringBuilder();
            for (int i = 0; i < n && i < 4; i++) {
                EpgEvent e = events.get(i);
                eit.append(TsUtil.formatTime(e.startMs)).append("  +")
                        .append(String.format(Locale.US, "%d:%02d", e.durationSec / 3600, (e.durationSec / 60) % 60))
                        .append("  ").append(e.name.isEmpty() ? "(no title)" : e.name).append('\n');
            }
            eitBody.setText(eit.toString().trim());
        }
    }

    private void saveMuxes() {
        StringBuilder sb = new StringBuilder();
        for (Mux m : muxes) sb.append(m.khz).append('\t').append(m.label.replace('\n', ' ')).append('\n');
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("muxes", sb.toString()).apply();
    }

    private void loadMuxes() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString("muxes", "");
        muxes.clear();
        for (String line : raw.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                muxes.add(new Mux(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1)));
            } catch (NumberFormatException ignored) {
                // no leading tab or no numeric field: not a mux line, skip it
            }
        }
        currentMuxIndex = muxes.isEmpty() ? -1 : 0;   // the spinner shows item 0 without firing a tune
        refreshMuxSpinner(-1);
    }

    // ------------------------------------------------------------------ tab content

    private String epgText(TsDemux d, ServiceInfo sel) {
        if (sel == null) return "Select a channel.";
        List<EpgEvent> events = d.getEvents(sel.serviceId);
        if (events.isEmpty()) return "No EIT data yet for this service (can take several seconds).";
        long now = d.getClockMs();
        StringBuilder sb = new StringBuilder("Times as broadcast (JST per ARIB)\n\n");
        int shown = 0;
        for (EpgEvent e : events) {
            if (shown++ >= 40) break;
            boolean current = now >= 0 && e.startMs >= 0 && now >= e.startMs && now < e.endMs();
            sb.append(current ? "> " : "  ")
                    .append(TsUtil.formatTime(e.startMs)).append(" +")
                    .append(String.format(Locale.US, "%d:%02d", e.durationSec / 3600, (e.durationSec / 60) % 60))
                    .append("  ").append(e.name.isEmpty() ? "(no title)" : e.name).append('\n');
            if (!e.text.isEmpty()) sb.append("      ").append(e.text).append('\n');
        }
        return sb.toString();
    }

    private String datacastText(TsDemux d, List<ServiceInfo> services, ServiceInfo sel) {
        List<CarouselInfo> carousels = d.getCarousels();
        StringBuilder sb = new StringBuilder();
        sb.append("Datacast: DSM-CC data carousels are decoded into modules and files (names, types, sizes).\n")
                .append("BML pages and images are listed, not rendered.\n\n");
        if (sel != null) {
            sb.append("Selected service ").append(sel.serviceId).append(":\n");
            boolean any = false;
            for (StreamInfo s : sel.streams) {
                if (s.isDatacast()) {
                    any = true;
                    sb.append(String.format(Locale.US, "  PID 0x%04X  %s  data_component_id %s  tag %s\n",
                            s.pid, s.codecName(),
                            s.dataComponentId >= 0 ? String.format(Locale.US, "0x%04X", s.dataComponentId) : "--",
                            s.componentTag >= 0 ? String.format(Locale.US, "0x%02X", s.componentTag) : "--"));
                    appendCarousel(sb, carouselFor(carousels, s.pid), "    ", true);
                }
            }
            if (!any) sb.append("  no data-carousel stream in this service\n");
            for (StreamInfo s : sel.streams) {
                if (s.streamType == 0x06) {
                    sb.append(String.format(Locale.US, "  PID 0x%04X  PES private (captions/text)  data_component_id %s\n",
                            s.pid, s.dataComponentId >= 0 ? String.format(Locale.US, "0x%04X", s.dataComponentId) : "--"));
                }
            }
            sb.append('\n');
        }
        sb.append("Data-broadcast services in this multiplex (service_type 0xC0 / 0x0C):\n");
        boolean found = false;
        for (ServiceInfo s : services) {
            if (!s.isDataBroadcastService()) continue;
            found = true;
            sb.append(String.format(Locale.US, "  sid %d  %s  PMT 0x%04X\n", s.serviceId, s.name, s.pmtPid));
            for (StreamInfo st : s.streams) {
                if (st.isDatacast()) {
                    sb.append(String.format(Locale.US, "      PID 0x%04X  %s\n", st.pid, st.codecName()));
                    appendCarousel(sb, carouselFor(carousels, st.pid), "      ", false);
                }
            }
        }
        if (!found) sb.append("  none seen\n");
        return sb.toString();
    }

    private static CarouselInfo carouselFor(List<CarouselInfo> list, int pid) {
        for (CarouselInfo c : list) if (c.pid == pid) return c;
        return null;
    }

    /** @param files list the files of each module (true), or only module id/size/progress (false). */
    private static void appendCarousel(StringBuilder sb, CarouselInfo ci, String indent, boolean files) {
        if (ci == null) {
            sb.append(indent).append("(no carousel data received yet)\n");
            return;
        }
        sb.append(String.format(Locale.US, "%s%d/%d modules complete, %d files, %d KB received (DII %d, DDB %d)\n",
                indent, ci.completeModules(), ci.modules.size(), ci.totalResources(),
                ci.bytesReceived / 1024, ci.diiCount, ci.ddbCount));
        int shownModules = 0;
        for (CarouselModule m : ci.modules) {
            if (shownModules++ >= 30) {
                sb.append(indent).append("  ... ").append(ci.modules.size() - 30).append(" more modules\n");
                break;
            }
            sb.append(String.format(Locale.US, "%s  module 0x%04X v%d  %d B  %s%s%s\n", indent, m.moduleId, m.version,
                    m.size, m.complete ? "complete" : m.percent() + "%", m.compressed ? "  (zlib)" : "",
                    m.error.isEmpty() ? "" : "  " + m.error));
            if (!files) continue;
            int shown = 0;
            for (CarouselResource r : m.resources) {
                if (r.location.isEmpty()) continue; // the resource-list part has no name
                if (shown++ >= 6) {
                    sb.append(indent).append("      ... more files\n");
                    break;
                }
                int semi = r.contentType.indexOf(';');
                String type = semi > 0 ? r.contentType.substring(0, semi) : r.contentType;
                sb.append(String.format(Locale.US, "%s      %s  %s  %d B\n", indent, r.location, type, r.length));
            }
        }
    }

    // ------------------------------------------------------------------ Graph tab

    /** One sample per tick, whatever tab is open, so the window has history when you switch. */
    private void pushHistory() {
        AnalyzerSnapshot s = lastSnapshot;
        if (s == null) return;
        float ratePackets = prevPackets < 0 ? 0f : Math.max(0f, s.totalPackets - prevPackets);
        float rateCc = prevCc < 0 ? 0f : Math.max(0f, s.ccErrors - prevCc);
        prevPackets = s.totalPackets;
        prevCc = s.ccErrors;
        shift(histBitrate, (float) (s.totalKbps / 1000.0));
        shift(histPackets, ratePackets);
        shift(histCc, rateCc);
        if (histCount < HIST) histCount++;
    }

    private static void shift(float[] window, float value) {
        System.arraycopy(window, 1, window, 0, window.length - 1);
        window[window.length - 1] = value;
    }

    private void setGraphMetric(int metric) {
        if (graphMetric == metric) return;
        graphMetric = metric;
        updateGraphChips();
        refresh();
    }

    private void updateGraphChips() {
        if (chipGraphBitrate == null) return;
        chipGraphBitrate.setTextColor(ContextCompat.getColor(this,
                graphMetric == METRIC_BITRATE ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
        chipGraphPackets.setTextColor(ContextCompat.getColor(this,
                graphMetric == METRIC_PACKETS ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
        chipGraphCc.setTextColor(ContextCompat.getColor(this,
                graphMetric == METRIC_CC ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
    }

    private void updateGraphPanel(TsDemux d, ServiceInfo sel) {
        AnalyzerSnapshot s = lastSnapshot;
        if (graphSpark == null || s == null) return;

        float[] window;
        String unit;
        switch (graphMetric) {
            case METRIC_PACKETS:
                window = histPackets;
                unit = "PACKETS / S";
                break;
            case METRIC_CC:
                window = histCc;
                unit = "CC ERRORS / S";
                break;
            default:
                window = histBitrate;
                unit = "MBPS";
                break;
        }
        float[] series = new float[histCount];
        System.arraycopy(window, HIST - histCount, series, 0, histCount);
        graphSpark.setSeries(series);
        graphUnit.setText(unit);

        if (histCount == 0) {
            graphValue.setText("—");
            graphPeak.setText("");
            graphAvg.setText("");
        } else {
            float last = series[series.length - 1];
            float peak = 0f, sum = 0f;
            for (float v : series) {
                peak = Math.max(peak, v);
                sum += v;
            }
            String fmt = graphMetric == METRIC_BITRATE ? "%.2f" : "%.0f";
            graphValue.setText(String.format(Locale.US, fmt, last));
            graphPeak.setText(String.format(Locale.US, "peak " + fmt, peak));
            graphAvg.setText(String.format(Locale.US, "avg " + fmt, sum / series.length));
        }

        String net = d.getNetworkName();
        String picked = sel == null ? "no service" : sel.virtualNumber + " " + channelName(sel);
        graphLine.setText((net == null || net.isEmpty() ? "--" : net) + " · TS 0x" + hex4(d.getTsId())
                + " · " + picked + "\n"
                + "null " + s.nullPackets + " · TEI " + s.teiPackets + " · sync " + s.syncLosses
                + " · CC " + s.ccErrors);
    }

    // ------------------------------------------------------------------ helpers

    private static ServiceInfo findService(List<ServiceInfo> list, int sid) {
        for (ServiceInfo s : list) if (s.serviceId == sid) return s;
        return null;
    }

    /**
     * The strip is a single line and shares it with the signal readout, so the
     * noisiest driver messages are shortened here. The Logs tab still gets the
     * full text - this is presentation only.
     */
    private static String stripText(String line) {
        // The board already says LOCKED in 20 sp; saying it again here was a duplicate.
        if (line.startsWith("LOCKED on ") && line.contains("TS reading continues")) return "Reading TS";
        if (line.startsWith("NO LOCK on ") && line.contains("TS reading continues")) return "No lock on this mux";
        int cut = line.indexOf(" (TS reading continues)");
        if (cut > 0) return line.substring(0, cut);
        return line;
    }

    private void setStatus(String text) {
        if (text == null) text = "";
        String firstLine = text.trim();
        int newline = firstLine.indexOf('\n');
        if (newline >= 0) {
            firstLine = firstLine.substring(0, newline).trim();
        }
        tvStatus.setText(stripText(firstLine));

        if (!text.isEmpty()) {
            String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            logEntries.add(new LogEntry(timeStamp, text, levelOf(text)));
            while (logEntries.size() > LOG_MAX) logEntries.remove(0);
            logVersion++;
        }
        if (currentTab == TAB_LOGS) renderLogs();
    }

    /** Coarse level for the Logs tab: red for failures, gold for "not yet", plain for the rest. */
    private static int levelOf(String text) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("error") || t.contains("fail") || t.contains("denied") || t.contains("exception")
                || t.contains("unsupported")) return LOG_ERROR;
        if (t.contains("no lock") || t.contains("no signal") || t.contains("no tuner") || t.contains("no supported")
                || t.contains("unplugged") || t.contains("stopped") || t.contains("waiting")
                || t.contains("still initialising") || t.contains("not found") || t.contains("no service")
                || t.contains("no data")) return LOG_WARN;
        return LOG_INFO;
    }

    private void renderLogs() {
        if (logsText == null || logVersion == logsRendered) return;
        logsRendered = logVersion;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int dim = ContextCompat.getColor(this, R.color.odtv_on_surface_variant);
        for (LogEntry e : logEntries) {
            int start = sb.length();
            sb.append(e.time);
            sb.setSpan(new ForegroundColorSpan(dim), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int body = sb.length();
            sb.append("  ").append(e.text.trim()).append('\n');
            int colour = e.level == LOG_ERROR ? R.color.odtv_live
                    : e.level == LOG_WARN ? R.color.odtv_warning : R.color.odtv_on_surface;
            sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, colour)), body, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (logEntries.isEmpty()) sb.append("No logs captured yet.");
        logsText.setText(sb);
        logsCount.setText(logEntries.size() + (logEntries.size() == 1 ? " entry" : " entries"));
        if (logsScroll != null) logsScroll.post(new Runnable() {
            @Override
            public void run() {
                logsScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}