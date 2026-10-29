package ph.opendtv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.media3.common.C;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.Date;
import java.util.TimeZone;
import java.util.function.IntConsumer;
import java.text.SimpleDateFormat;
import java.io.File;
import android.os.Environment;

import ph.opendtv.bml.BmlController;
import ph.opendtv.dev.DeveloperActions;
import ph.opendtv.ui.EwbsBanner;
import ph.opendtv.ui.WatchInfoView;
import ph.opendtv.ui.WatchStatusStrip;
import ph.opendtv.ts.RecordingPacketSink;
import ph.opendtv.ui.CaptionOverlay;
import ph.opendtv.ui.CaptionView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ph.opendtv.player.PlayerFactory;
import ph.opendtv.player.TsPipeDataSource;
import ph.opendtv.source.SignalStatus;
import ph.opendtv.ts.EpgEvent;
import ph.opendtv.ts.EwbsAreaTable;
import ph.opendtv.ts.ServiceInfo;
import ph.opendtv.ts.StreamInfo;
import ph.opendtv.ts.TsDemux;
import ph.opendtv.ts.TsExtractorFactory;
import ph.opendtv.ts.TsPipe;
import ph.opendtv.tv.TunerHub;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity implements TunerHub.Owner {

    private static final String TAG = "OpenDTV-Main";
    private static final String ACTION_USB_PERMISSION = "ph.opendtv.USB_PERMISSION";
    /**
     * Delivered by the Developers screen's "Open Analyzer" row. The Analyzer is entered through
     * {@link #openAnalyzer()} and not by a bare startActivity from that screen, because that
     * method is the one that shuts the tuner hub down first (M5 defect 6).
     */
    public static final String EXTRA_DEV_ANALYZER = "dev_open_analyzer";
    private static final String PREFS = "opendtv_prefs";
    private static final String PREF_FAV = "favorites";
    private static final String PREF_FAV_ORDER = "favorite_order";
    private static final String PREF_CHANNELS = "saved_channels";
    private static final String PREF_ASPECT = "aspect_mode";
    private static final String PREF_LAST_SID = "last_service_id";
    private static final String PREF_LAST_KHZ = "last_khz";
    private static final String PREF_EWBS_LOCATION = "ewbs_location";
    private static final int REQUEST_EWBS_LOCATION = 1002;

    private interface EwbsLocationCallback {
        void onLocation(int code);
    }

    private EwbsLocationCallback pendingEwbsLocation;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TsPipe pipe = new TsPipe(4 * 1024 * 1024);   // service-filtered TS only (a few Mbit/s): ~10 s of video

    private TunerHub hub;
    private UsbManager usbManager;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView tvCurrentChannel;
    private TabLayout tabLayout;
    private RecyclerView recyclerView;
    private RecordingPacketSink currentPacketSink;
    private TextView btnRecord;               // portrait: the REC key, which is also the tally light
    private ValueAnimator recordPulse;        // its pulse while a recording runs
    private final Runnable recordTick = new Runnable() {
        @Override
        public void run() {
            // The light follows the writer, not the tap: if the sink stopped by itself,
            // the tally has to go out with it.
            updateRecordTally();
            if (isRecording()) ui.postDelayed(this, 1000);
        }
    };
    private CaptionOverlay captionOverlay;
    private CaptionView captionView;          // styled (WebView) caption display, null while the simple text line is selected
    private static final String PREF_HW_DECODER = "hardware_decoder";   // false (default) = FFmpeg first, true = MediaCodec first
    private static final String PREF_CC = "closed_captions";
    private static final String PREF_CC_STYLE = "caption_style";        // 0 = simple text line, 1 = styled (aribb24 WebView)
    private static final String PREF_CC_LANG = "caption_language";      // 1 = first (main), 2 = second
    private static final String PREF_AUDIO_LANG = "audio_language";     // ISO 639 code, "" = automatic

    // Data broadcast (BML), landscape and portrait. Landscape: keys only. Portrait: floating remote button + panel.
    private BmlController bml;
    private WatchInfoView watchInfo;
    private WatchStatusStrip watchStatus;
    private View btnWatchInfo;
    private View btnWatchInfoLand;
    private TextView hudStatusLeft;
    private TextView hudTdt;
    private TextView hudSignal;
    private TextView hudChipData;
    private TextView hudChipCc;
    private TextView hudChipLock;
    private TextView hudChipEwbs;
    private final EwbsBanner ewbsBanner = new EwbsBanner(this);
    private final Runnable ewbsTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            TsDemux d = hub != null ? hub.demux() : null;
            ewbsBanner.update(d, ewbsLocation());
            updateWatchInfo(d);
            ui.postDelayed(this, 1000);
        }
    };
    private FrameLayout bmlFrame;
    private ph.opendtv.bml.BmlKeyPad bmlKeys;   // landscape: red/green/yellow/blue on demand
    private boolean centreHeld;                      // OK is down on a BML page (long-press timer)
    private boolean okHeld;                          // OK is down on the watching surface
    private View remotePanel;              // portrait only: the BML pad, if this layout has one
    private View btnRemote;                // the row button that raises it (inverts while open)
    private boolean remotePanelOpen;
    private float[] bmlVideoFrac;          // where the page wants the video (fractions), null = full screen
    private int layoutKey = -1;            // which layout set (portrait / landscape / tablet landscape) is inflated

    // Landscape HUD & Overlay Views
    private View sidePanel;
    /** The panel's own top padding, kept so the EWBS inset can be added and removed cleanly. */
    private int sidePanelBasePaddingTop = -1;
    private View infoBanner;
    private android.widget.ImageView hudIvLogo;
    private TextView hudTvVirtual;
    private TextView hudTvChannelName;
    private TextView hudTvNetworkName;
    private TextView hudTvNowEvent;
    private TextView hudTvNextEvent;
    private android.widget.ImageButton btnOpenSidePanel;

    // Browse surfaces: list footer (label + count) and the shared empty state.
    private View listFooter;
    private TextView listFooterLabel;
    private TextView listFooterCount;
    private View emptyState;
    private android.widget.ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyBody;
    private TextView emptyAction;
    private ph.opendtv.ui.TuningOverlay tuning;

    private final Runnable hideHudRunnable = this::hideInfoBanner;
    private final Runnable hideSidePanelRunnable = this::onPanelAutoHideTimeout;
    /** Long OK on a data broadcast: show the on-screen key pad. */
    private final Runnable showKeyPadRunnable = () -> {
        centreHeld = false;
        if (bmlKeys != null && bml != null && bml.isVisible()) bmlKeys.show();
    };
    /** Long OK on the watching surface: open the data broadcast (no DATA key on the TV remote). */
    private final Runnable openBmlRunnable = () -> {
        okHeld = false;
        if (bml != null && !bml.isVisible()) toggleBml();
    };
    /** Tuning card: appear only if no frame has arrived yet. */
    private final Runnable showTuningCardRunnable = this::showTuningCard;
    /** No frame after the grace period: same card becomes "No signal". */
    private final Runnable showNoSignalCardRunnable = this::showNoSignalCard;

    private volatile TsDemux demux = new TsDemux();
    private int currentTab = 0; // 0: Channels, 1: EPG, 2: Favorites
    /** Which tab to come back to after a recreate (the decoder switch rebuilds the activity). */
    private static final String STATE_TAB = "odtv_tab";
    /** ...and where in that tab's list. */
    private static final String STATE_LIST = "odtv_list";
    /** Touch origin for the horizontal tab swipe on the content surface. */
    private float tabSwipeX, tabSwipeY;
    private boolean tabSwipeClaimed;
    /** The list box (list or empty state) that slides in when the tab changes. */
    private View listContent;
    private ChannelEntry currentChannelEntry;
    private int debugTuneKhz;
    /**
     * True from the moment the Analyzer is asked for until it comes back. The Analyzer opens the
     * USB dongle itself, so Main must not re-arm the tuner in that window: doing it while the
     * Analyzer claims the device gives two native instances on one dongle, and both tunes fail
     * ("device is closed in native_control_request" was the second one). tunerReady alone cannot
     * express this - it is cleared to mean "re-initialise on return", which is the opposite.
     */
    private volatile boolean handingOver;
    /** Request code for the Analyzer hand-off; onActivityResult is where the tuner comes back. */
    private static final int REQ_ANALYZER = 1001;
    private static final int MAX_RETRIES = 3;
    /** Single worker for tune/play: one tune at a time, no thread churn, cancelled in onDestroy. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "PlayChannel"));
    /** Created once and reused for every channel change. */
    private final DataSource.Factory pipeFactory = () -> new TsPipeDataSource(pipe);
    // Custom TS extractor: some muxes declare AAC LATM but send ADTS.
    private final MediaSource.Factory mediaSourceFactory =
            new ProgressiveMediaSource.Factory(pipeFactory, new TsExtractorFactory());
    private int retries;
    private volatile boolean videoRendered;
    private boolean softwareFallbackTried;
    private final AtomicInteger playGen = new AtomicInteger();
    private volatile boolean tunerReady;
    private volatile boolean initializing;

    private final List<ChannelEntry> allChannels = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    /** The same keys as {@link #favorites}, in the order the reader arranged them. */
    private final List<String> favoriteOrder = new ArrayList<>();
    private ph.opendtv.ui.ChannelAdapter favoritesAdapter;
    private androidx.recyclerview.widget.ItemTouchHelper favoritesDrag;
    private int movingPosition = -1;

    private volatile boolean scanning;
    private volatile boolean scanCancel;

    public static final class ChannelEntry {
        public int khz;
        public ServiceInfo service;

        public ChannelEntry(int khz, ServiceInfo service) {
            this.khz = khz;
            this.service = service;
            // The entry is the only place the two are known together, so it stamps the mux on
            // the service: a bare ServiceInfo cannot say which one it came from, and the star
            // (like the saved-channel check) has to tell same-SID services apart.
            service.khz = khz;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // A setting that rebuilds the activity (the decoder switch) must not throw the reader
        // out of the tab they were reading it in.
        if (savedInstanceState != null) currentTab = savedInstanceState.getInt(STATE_TAB, currentTab);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        hub = TunerHub.get(this);

        // Channels first: a favourite is keyed by (frequency, service ID), so migrating the
        // stored set needs the saved channel list to expand a legacy bare SID.
        loadChannels();
        loadFavorites();

        // Live TV buffering, tuned for a fast channel start: playback begins as soon as 25 ms of media are queued
        // (a tiny safety margin over "instantly"; the first keyframe is the real floor), keeps 2-10 s buffered, and
        // after an underrun waits 1 s so a short stall does not turn into a stutter loop. Time (not bytes) decides,
        // with a byte cap so a high-bitrate HD service cannot grow the heap.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 10000, 1500, 3000)
                .setTargetBufferBytes(6 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        // Shared player setup. Hardware decoders first when enabled; FFmpeg
        // (NextLib) is the fallback. The Analyzer uses the same factory.
        player = PlayerFactory.build(this, loadControl, true);
        player.addListener(playerListener);
        applyAudioLanguagePref();

        // Data broadcast engine: created once, survives rotation (the page is cached, see BmlController).
        bml = new BmlController(this, null, new BmlController.Host() {
            @Override
            public void onVideoRect(float l, float t, float r, float b, boolean invisible) {
                bmlVideoFrac = (invisible || (l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f))
                        ? null : new float[] {l, t, r, b};
                applyBmlVideoRect();
            }

            @Override
            public void onStatus(String text) {
                // Failures only. "waiting for data" is not a failure: the page appears when it
                // appears, and toasting it on every open made the normal case sound broken.
                if (text.contains("error") || text.contains("unavailable")
                        || text.contains("not supported") || text.contains("crash") || text.contains("stopped"))
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onClosed() {
                bmlVideoFrac = null;
                applyBmlVideoRect();
                if (bmlKeys != null) bmlKeys.hide();
                updateBmlButton();
            }
        });

        layoutKey = layoutKeyOf(getResources().getConfiguration());
        bindLayout();
        // Come back to the same place in the list too, not just the same tab: the decoder
        // switch sits at the bottom of Settings and a recreate used to drop the reader at
        // the top of it.
        if (savedInstanceState != null) {
            final android.os.Parcelable listState = savedInstanceState.getParcelable(STATE_LIST);
            if (listState != null) {
                recyclerView.post(() -> {
                    if (recyclerView.getLayoutManager() != null) {
                        recyclerView.getLayoutManager().onRestoreInstanceState(listState);
                    }
                });
            }
        }
        if ("settings".equals(getIntent().getStringExtra("tab"))) {
            recyclerView.post(() -> {
                showSidePanel();
                TabLayout.Tab tab = tabLayout != null ? tabLayout.getTabAt(3) : null;
                if (tab != null) tab.select();
            });
        }

        currentChannelEntry = pickStartChannel();
        showCurrentChannel();
        debugTuneKhz = getIntent().getIntExtra("tune_khz", 0);

        initTuner();

        // A cold start that carries the Developers screen's "Open Analyzer" extra: go through the
        // same door as the Settings row once this activity exists, so the hub is not left behind.
        if (getIntent().getBooleanExtra(EXTRA_DEV_ANALYZER, false)) {
            getIntent().removeExtra(EXTRA_DEV_ANALYZER);
            ui.post(this::openAnalyzer);
        }
    }

    /** 0/1 = portrait/landscape, +2 on a tablet-sized screen: the three layout sets we ship. */
    private static int layoutKeyOf(Configuration c) {
        return (c.orientation == Configuration.ORIENTATION_LANDSCAPE ? 1 : 0) | (c.smallestScreenWidthDp >= 600 ? 2 : 0);
    }

    /**
     * The manifest keeps the activity alive on rotation (a recreate would restart the tuner), so Android does NOT
     * pick layout-land by itself: inflate the right layout here. Player, tuner and the cached BML page are untouched.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int key = layoutKeyOf(newConfig);
        if (key == layoutKey) return;       // keyboard / navigation / density change: nothing to redo
        layoutKey = key;
        ui.removeCallbacks(hideHudRunnable);
        ui.removeCallbacks(hideSidePanelRunnable);
        if (playerView != null) playerView.setPlayer(null);   // release the old surface before the views go
        bindLayout();
        showCurrentChannel();
    }

    /** Makes room for the EWBS strip on the side panel; its tab row is what the strip covers. */
    private void insetPanelForEwbsBanner(boolean visible) {
        View panel = findViewById(R.id.side_panel);
        if (panel == null) return;
        if (sidePanelBasePaddingTop < 0) sidePanelBasePaddingTop = panel.getPaddingTop();
        int top = visible
                ? sidePanelBasePaddingTop + Math.round(48f * getResources().getDisplayMetrics().density)
                : sidePanelBasePaddingTop;
        panel.setPadding(panel.getPaddingLeft(), top, panel.getPaddingRight(), panel.getPaddingBottom());
    }

    /** Inflates the layout for the current orientation and wires every view in it (used by onCreate and rotation). */
    private void bindLayout() {
        setContentView(R.layout.activity_main);
        // The EWBS strip floats over the window; inset the side panel so its tab row stays clear.
        ewbsBanner.setHost(this::insetPanelForEwbsBanner);
        // Diagnostics / box setup: jump straight to a tab without the scan wizard.
        if ("settings".equals(getIntent().getStringExtra("tab"))) currentTab = 3;
        else if ("epg".equals(getIntent().getStringExtra("tab"))) currentTab = 1;

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setOverflowIcon(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_more_vert));
        } else if (getSupportActionBar() != null) {
            setSupportActionBar(null);      // landscape has no toolbar: drop the one of the portrait layout
        }

        playerView = findViewById(R.id.player_view);
        tvCurrentChannel = findViewById(R.id.tv_current_channel);
        tabLayout = findViewById(R.id.tab_layout);
        listContent = findViewById(R.id.list_content);
        watchInfo = findViewById(R.id.watch_info);
        watchStatus = findViewById(R.id.watch_status);
        btnWatchInfo = findViewById(R.id.btn_watch_info);
        if (btnWatchInfo != null) {
            btnWatchInfo.setOnClickListener(v -> {
                if (bml != null && bml.isVisible()) return;
                toggleWatchInfo();
            });
        }
        if (watchInfo != null) {
            watchInfo.setOnVisibilityChangedListener(showing -> {
                if (showing) hideSidePanel();
                updateApluscChrome(showing);
                if (btnWatchInfo == null) return;
                btnWatchInfo.setSelected(showing);
                btnWatchInfo.setContentDescription(showing ? "Close watch info" : "Watch info");
            });
        }
        btnWatchInfoLand = findViewById(R.id.btn_watch_info_land);
        if (btnWatchInfoLand != null) {
            btnWatchInfoLand.setOnClickListener(v -> {
                if (bml != null && bml.isVisible()) return;
                toggleWatchInfo();
            });
        }
        btnRemote = findViewById(R.id.btn_remote);
        if (btnRemote != null) btnRemote.setOnClickListener(v -> toggleRemotePad());
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        // Observe the gesture before the rows: a clear horizontal swipe is
        // claimed (cancelling the row/star press) and switches the tab, while
        // vertical drags and taps are left untouched.
        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        tabSwipeX = e.getX();
                        tabSwipeY = e.getY();
                        tabSwipeClaimed = false;
                        // Interaction resets the panel timer: it never closes
                        // while someone is reading or scrolling.
                        if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
                            schedulePanelAutoHide();
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!tabSwipeClaimed) {
                            float dx = e.getX() - tabSwipeX;
                            float dy = e.getY() - tabSwipeY;
                            float slop = 24 * getResources().getDisplayMetrics().density;
                            tabSwipeClaimed = Math.abs(dx) > slop
                                    && Math.abs(dx) > Math.abs(dy) * 1.5f;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        tabSwipeClaimed = false;
                        break;
                    default:
                        break;
                }
                return tabSwipeClaimed;   // true = the row no longer sees this gesture
            }

            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_UP) {
                    if (tabSwipeClaimed) {
                        float dx = e.getX() - tabSwipeX;
                        float dy = e.getY() - tabSwipeY;
                        float min = 64 * getResources().getDisplayMetrics().density;
                        if (Math.abs(dx) > min && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            TabLayout.Tab tab = tabLayout != null
                                    ? tabLayout.getTabAt(currentTab + (dx < 0 ? 1 : -1)) : null;
                            if (tab != null) tab.select();
                        }
                    }
                    tabSwipeClaimed = false;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    tabSwipeClaimed = false;
                }
            }
        });
        recyclerView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && tabLayout != null) {
                tabLayout.clearFocus();
            }
        });
        recyclerView.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                child.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && tabLayout != null) {
                        tabLayout.clearFocus();
                    }
                });
            }
            @Override
            public void onChildViewRemoved(View parent, View child) {}
        });

        sidePanel = findViewById(R.id.side_panel);
        if (sidePanel != null) {
            // Any tap on the panel (including its background) postpones the
            // auto-hide; keys already do this in onKeyDown.
            sidePanel.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) schedulePanelAutoHide();
                return false;
            });
        }
        infoBanner = findViewById(R.id.info_banner);
        listFooter = findViewById(R.id.list_footer);
        listFooterLabel = findViewById(R.id.tv_list_footer);
        listFooterCount = findViewById(R.id.tv_list_footer_count);
        emptyState = findViewById(R.id.empty_state);
        emptyIcon = findViewById(R.id.empty_icon);
        emptyTitle = findViewById(R.id.empty_title);
        emptyBody = findViewById(R.id.empty_body);
        emptyAction = findViewById(R.id.empty_action);
        tuning = findViewById(R.id.tuning_overlay);
        if (infoBanner != null) {
            infoBanner.setOnClickListener(v -> { /* consume taps inside the banner */ });
        }
        hudIvLogo = findViewById(R.id.hud_iv_logo);
        hudTvVirtual = findViewById(R.id.hud_tv_virtual);
        hudTvChannelName = findViewById(R.id.hud_tv_channel_name);
        hudTvNetworkName = findViewById(R.id.hud_tv_network_name);
        hudTvNowEvent = findViewById(R.id.hud_tv_now_event);
        hudTvNextEvent = findViewById(R.id.hud_tv_next_event);
        hudStatusLeft = findViewById(R.id.hud_status_left);
        hudTdt = findViewById(R.id.hud_tdt);
        hudSignal = findViewById(R.id.hud_signal);
        hudChipData = findViewById(R.id.hud_chip_data);
        hudChipCc = findViewById(R.id.hud_chip_cc);
        hudChipLock = findViewById(R.id.hud_chip_lock);
        hudChipEwbs = findViewById(R.id.hud_chip_ewbs);
        btnOpenSidePanel = findViewById(R.id.btn_open_side_panel);

        if (btnOpenSidePanel != null) {
            btnOpenSidePanel.setOnClickListener(v -> toggleSidePanel());
        }

        // The REC key, in whichever layout has it: the channel row in portrait, above the
        // info button in landscape. Same control either way - the tap starts and the next
        // tap stops, so the key never has to lie about which one it will do.
        btnRecord = findViewById(R.id.btn_record);
        if (btnRecord == null) btnRecord = findViewById(R.id.btn_record_land);
        if (btnRecord != null) {
            btnRecord.setOnClickListener(v -> toggleChannelRecording());
        }

        // Data broadcast container: in both layouts (portrait: inside the 16:9 video frame).
        bmlFrame = findViewById(R.id.bml_frame);
        bmlVideoFrac = null;
        if (bmlFrame != null) {
            bmlFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (bmlVideoFrac != null && (r - l != or - ol || b - t != ob - ot)) applyBmlVideoRect();
            });
        }
        bindRemote();
        bml.attachTo(bmlFrame);             // moves the cached page into the new layout's frame

        // Landscape only: the on-screen BML key pad (digits, colours, D, RETURN).
        bmlKeys = null;
        android.view.ViewGroup root = findViewById(R.id.root_layout_land);
        if (root != null) {
            bmlKeys = new ph.opendtv.bml.BmlKeyPad(this);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = Math.round(40 * getResources().getDisplayMetrics().density);
            root.addView(bmlKeys, lp);
            bmlKeys.setListener(new ph.opendtv.bml.BmlKeyPad.Listener() {
                @Override
                public void onBmlKey(int code) {
                    if (bml != null) bml.tap(code);
                }

                @Override
                public void onData() {
                    toggleBml();   // D: the same toggle as the portrait remote's DATA
                }
            });
        }

        hideSystemBars();

        AspectRatioFrameLayout frame = findViewById(R.id.player_frame);
        if (frame != null) {
            frame.setAspectRatio(16f / 9f);   // fixed 16:9 display area in portrait
        }

        playerView.setUseController(false);
        playerView.setKeepContentOnPlayerReset(false);
        // Default: fill the whole 16:9 area (4:3 channels are stretched). Change it in the Settings tab.
        applyAspectMode(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_ASPECT, 0), false);
        playerView.setOnClickListener(v -> {
            if (bml != null && bml.isVisible()) return;
            boolean landscape = (layoutKey & 1) != 0;
            if (landscape && watchInfo != null && watchInfo.isShowing()) {
                return;   // A+C is modal: only X (or BACK) closes it
            }
            if (landscape) {
                if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
                    hideSidePanel();   // retract the channel list first
                    return;
                }
                if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                    hideInfoBanner();
                } else {
                    showInfoBanner();  // banner only; '<' opens the channel list
                }
            } else {
                // Portrait: the banner is the thing a tap should bring back. Tapping the
                // video used to open the A+C card, which left the banner reachable only by
                // tuning again. A+C stays behind its own button, and it is modal.
                if (watchInfo != null && watchInfo.isShowing()) return;
                if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                    hideInfoBanner();
                } else {
                    showInfoBanner();
                }
            }
        });
        playerView.setPlayer(player);
        if (captionOverlay == null) {
            captionOverlay = new CaptionOverlay(playerView);
            captionOverlay.setEnabled(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_CC, true));
        } else {
            captionOverlay.attachTo(playerView);
        }
        if (captionView != null) captionView.attachTo(playerView);
        else applyCaptionStyle();

        tabLayout.addTab(tabLayout.newTab().setText("Channels"));
        tabLayout.addTab(tabLayout.newTab().setText("EPG"));
        tabLayout.addTab(tabLayout.newTab().setText("Favorites"));
        tabLayout.addTab(tabLayout.newTab().setText("Settings"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                final int previous = currentTab;
                currentTab = tab.getPosition();
                updateContentAdapter();
                // The list arrives from the side it was swiped from: a left swipe
                // picks the next tab, whose list slides in from the right.
                if (currentTab != previous) animateContent(currentTab > previous ? 1 : -1);
                schedulePanelAutoHide();
                // Selecting can rebuild the tab views and drop focus; keep the
                // strip focused so LEFT/RIGHT keeps moving on a TV.
                if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE
                        && tabLayout.hasFocus()) {
                    tabLayout.post(MainActivity.this::focusSelectedTab);
                }
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        TabLayout.Tab wanted = tabLayout.getTabAt(currentTab);
        if (wanted != null && !wanted.isSelected()) wanted.select();

        updateContentAdapter();
        updateBmlButton();
    }

    // ------------------------------------------------------------------ data broadcast (BML), landscape

    /** The tuned service as the demux sees it now (saved channels are stubs without streams). */
    private ServiceInfo liveService(TsDemux d) {
        if (d == null || currentChannelEntry == null) return null;
        int sid = currentChannelEntry.service.serviceId;
        for (ServiceInfo s : d.getServices()) {
            if (s.serviceId == sid && s.pmtSeen) return s;
        }
        return null;
    }

    /**
     * DATA: show the data broadcast, or hide it again. A hidden page stays cached, so
     * showing it again is instant. This never touches the pad - d on the pad is the page
     * key, and closing the page should not take the remote away with it (only EXIT does).
     */
    private void toggleBml() {
        if (bml == null || bmlFrame == null) return;
        if (bml.isVisible()) {
            bml.hide();
            if (bmlKeys != null) bmlKeys.hide();
            updateBmlButton();
            return;
        }
        TsDemux d = hub != null ? hub.demux() : null;
        ServiceInfo svc = liveService(d);
        if (svc == null) {
            Toast.makeText(this, "Tune a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean hasData = false;
        for (ph.opendtv.ts.StreamInfo st : svc.streams) hasData |= st.isDatacast();
        if (!hasData) {
            Toast.makeText(this, "This channel has no data broadcast", Toast.LENGTH_SHORT).show();
            return;
        }
        hideSidePanel();
        if (watchInfo != null) watchInfo.hide();
        hideTuningCard();
        ui.removeCallbacks(hideHudRunnable);
        hideInfoBanner();
        if (bml.open(d, svc)) bml.tap(BmlController.DATA);   // brand-new page: ask it to show itself once loaded
        updateBmlButton();
    }

    /** Portrait remote: the pad's DATA key brightens while the page is on screen. */
    private void updateBmlButton() {
        if (remotePanel instanceof ph.opendtv.bml.CornerPad) {
            ((ph.opendtv.bml.CornerPad) remotePanel)
                    .setDataActive(bml != null && bml.isVisible());
        }
    }

    /** Wires the portrait remote pad. Absent in the landscape layouts. */
    private void bindRemote() {
        remotePanel = findViewById(R.id.remote_panel);
        if (!(remotePanel instanceof ph.opendtv.bml.CornerPad)) {
            remotePanel = null;
            return;
        }
        ph.opendtv.bml.CornerPad pad = (ph.opendtv.bml.CornerPad) remotePanel;
        pad.setListener(new ph.opendtv.bml.CornerPad.Listener() {
            @Override
            public void onKey(int code) { padKey(code); }

            @Override
            public void onExit() { closeBml(); }

            @Override
            public void onData() { toggleBml(); }
        });
        setRemotePanelOpen(remotePanelOpen);
        // The strip's DATA chip is the way in now that the floating button is gone: it
        // already knows whether this channel carries a page.
        if (watchStatus != null) {
            watchStatus.setDataClickListener(() -> {
                final boolean wasUp = bml != null && bml.isVisible();
                toggleBml();
                // Opening the page brings the pad with it; closing it leaves the pad be.
                if (!wasUp) setRemotePanelOpen(bml != null && bml.isVisible());
            });
        }
    }

    /**
     * The remote button in the channel row (what the floating button used to do). Pressing
     * it while the pad is up puts everything away - page and pad, exactly like EXIT on the
     * pad. Pressing it while the pad is down raises the pad, opening the page first if this
     * channel has one; `toggleBml` explains any refusal.
     */
    private void toggleRemotePad() {
        if (remotePanelOpen) {
            closeBml();
            return;
        }
        if (bml == null || !bml.isVisible()) toggleBml();
        setRemotePanelOpen(bml != null && bml.isVisible());
    }

    /** A pad key: only meaningful while a page is up, so say so instead of doing nothing. */
    private void padKey(int code) {
        if (bml == null || !bml.isVisible()) {
            Toast.makeText(this, "Open the data broadcast first", Toast.LENGTH_SHORT).show();
            return;
        }
        bml.tap(code);
    }

    /** EXIT on the pad: close the page and put the pad away with it. */
    private void closeBml() {
        if (bml != null && bml.isVisible()) bml.close();
        if (bmlKeys != null) bmlKeys.hide();
        setRemotePanelOpen(false);
        updateBmlButton();
    }

    private void setRemotePanelOpen(boolean open) {
        remotePanelOpen = open;
        if (remotePanel == null) return;
        remotePanel.setVisibility(open ? View.VISIBLE : View.GONE);
        // A fresh pad always starts on the arrows, never on the digits left open last time.
        if (open && remotePanel instanceof ph.opendtv.bml.CornerPad) {
            ((ph.opendtv.bml.CornerPad) remotePanel).resetFace();
        }
        // The row button is the pad's state light: filled while the pad is up, outlined
        // when it is not - the same selected/unselected pair the info button uses.
        if (btnRemote != null) btnRemote.setSelected(open);
        updateBmlButton();
    }

    /** BML moves / shrinks the video: place the PlayerView inside the landscape root accordingly. */
    private void applyBmlVideoRect() {
        if (playerView == null || bmlFrame == null) return;
        int w = bmlFrame.getWidth(), h = bmlFrame.getHeight();
        FrameLayout.LayoutParams lp;
        if (bmlVideoFrac == null || w == 0 || h == 0) {
            lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            lp = new FrameLayout.LayoutParams(Math.max(1, Math.round((bmlVideoFrac[2] - bmlVideoFrac[0]) * w)),
                    Math.max(1, Math.round((bmlVideoFrac[3] - bmlVideoFrac[1]) * h)));
            lp.leftMargin = Math.round(bmlVideoFrac[0] * w);
            lp.topMargin = Math.round(bmlVideoFrac[1] * h);
        }
        playerView.setLayoutParams(lp);
    }

    /**
     * Keys (landscape, or a keyboard / remote in portrait). DATA (remote DATA / teletext key, or F5) always toggles the data broadcast; while it is on
     * screen the keys below go to the page instead of the TV UI:
     * arrows or WASD = move, Enter / OK = select, F6 / Back = BACK, F1-F4 / colour keys = red green yellow blue,
     * 0-9 and numpad 0-9 = number keys, Esc = close.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // Favourites reorder: held OK starts move mode, ▲▼ move, OK places.
        if (movingPosition >= 0 && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    moveFavorite(-1);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    moveFavorite(1);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                case android.view.KeyEvent.KEYCODE_BACK:
                    movingPosition = -1;
                    saveOrderFromAdapter();
                    // No toast here: the rows moved, the mode is over, and a message on BACK
                    // reads like being told off for leaving.
                    return true;
                default:
                    break;
            }
        }
        if (currentTab == 2 && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getRepeatCount() >= 1 && recyclerView.hasFocus()
                && (e.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
            android.view.View focused = getCurrentFocus();
            if (focused != null) {
                int position = recyclerView.getChildAdapterPosition(focused);
                if (position != RecyclerView.NO_POSITION) {
                    enterMoveMode(position);
                    return true;
                }
            }
        }
        // The key pad, when up, owns every key: the page must not see them.
        if (bmlKeys != null && bmlKeys.isShowing()) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                switch (e.getKeyCode()) {
                    case android.view.KeyEvent.KEYCODE_BACK:
                        bmlKeys.hide();
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                        bmlKeys.moveFocus(-1, 0);
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                        bmlKeys.moveFocus(1, 0);
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_UP:
                        bmlKeys.moveFocus(0, -1);
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                        bmlKeys.moveFocus(0, 1);
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                    case android.view.KeyEvent.KEYCODE_ENTER:
                        // The key that raised the pad is still held: its repeat
                        // events must not fire the focused chip over and over.
                        if (e.getRepeatCount() == 0) bmlKeys.sendFocused();
                        return true;
                    case android.view.KeyEvent.KEYCODE_BOOKMARK:
                    case android.view.KeyEvent.KEYCODE_INFO:
                        toggleBml();   // same as D: leave the data broadcast
                        return true;
                    default:
                        return true;
                }
            }
            return true;
        }
        // Long OK on a visible data broadcast shows the key pad; a short press
        // is still ENTER for the page. There is no DATA key on the TV remote,
        // so the ribbon closes the data broadcast.
        if (bml != null && bml.isVisible()) {
            int kc = e.getKeyCode();
            if ((kc == android.view.KeyEvent.KEYCODE_BOOKMARK
                    || kc == android.view.KeyEvent.KEYCODE_INFO)
                    && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && e.getRepeatCount() == 0) {
                toggleBml();
                return true;
            }
            if (kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || kc == android.view.KeyEvent.KEYCODE_ENTER) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        centreHeld = true;
                        ui.postDelayed(showKeyPadRunnable, 500);
                    }
                    return true;
                }
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    ui.removeCallbacks(showKeyPadRunnable);
                    boolean shortPress = centreHeld;
                    centreHeld = false;
                    if (shortPress) bml.tap(ph.opendtv.bml.BmlController.ENTER);
                    return true;
                }
            }
        }
        // GUIDE (the TV button) opens the program guide; TV_INPUT (source)
        // toggles the channel list. Both stay out of the way of a data broadcast.
        if ((bml == null || !bml.isVisible())
                && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0) {
            int kc = e.getKeyCode();
            if (kc == android.view.KeyEvent.KEYCODE_GUIDE) {
                showGuideTab();
                return true;
            }
            if (kc == android.view.KeyEvent.KEYCODE_TV_INPUT && sidePanel != null) {
                if (sidePanel.getVisibility() == View.VISIBLE) hideSidePanel();
                else showSidePanel();
                return true;
            }
        }
        if (bml != null && bmlFrame != null && handleBmlKey(e)) return true;
        if ((bml == null || !bml.isVisible()) && watchInfo != null
                && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0) {
            int keyCode = e.getKeyCode();
            if (keyCode == android.view.KeyEvent.KEYCODE_INFO
                    || keyCode == android.view.KeyEvent.KEYCODE_BOOKMARK) {
                // INFO on most remotes; BOOKMARK is the ribbon key on Skyworth
                // sets, which have no INFO button at all.
                if (watchInfo.isShowing()) {
                    watchInfo.toggleDetails();
                } else {
                    showWatchInfo();
                }
                return true;
            }
            if (watchInfo.isShowing() && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                watchInfo.hide();
                return true;
            }
            boolean center = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
            if (center && watchInfo.isShowing()
                    && (sidePanel == null || sidePanel.getVisibility() != View.VISIBLE)) {
                watchInfo.toggleDetails();
                return true;
            }
        }
        // Long OK on the watching surface opens the data broadcast; a short OK
        // keeps the banner / card behaviour. Landscape only: the portrait remote
        // already has its own DATA button.
        if (sidePanel != null && bml != null && !bml.isVisible()
                && (watchInfo == null || !watchInfo.isShowing())
                && sidePanel.getVisibility() != View.VISIBLE) {
            int kc = e.getKeyCode();
            if (kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || kc == android.view.KeyEvent.KEYCODE_ENTER) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        okHeld = true;
                        ui.postDelayed(openBmlRunnable, 500);
                    }
                    return true;
                }
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    ui.removeCallbacks(openBmlRunnable);
                    boolean shortPress = okHeld;
                    okHeld = false;
                    if (shortPress) {
                        if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                            showWatchInfo();
                        } else {
                            showInfoBanner();
                        }
                    }
                    return true;
                }
            }
        }
        // Landscape: the list must always be reachable, even when the player
        // owns focus. Handling LEFT here keeps it out of the focus search.
        if (sidePanel != null && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0) {
            int keyCode = e.getKeyCode();
            boolean panelOpen = sidePanel.getVisibility() == View.VISIBLE;
            if (!panelOpen && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                showSidePanel();
                return true;
            }
            // The banner's i cannot be reached with the DPAD (the player owns
            // focus), so OK is the TV path: first press shows the banner, the
            // next opens the same card as the i.
            boolean center = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
            if (!panelOpen && center) {
                if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                    showWatchInfo();
                } else {
                    showInfoBanner();
                }
                return true;
            }
            if (panelOpen && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                hideSidePanel();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean handleBmlKey(android.view.KeyEvent e) {
        int kc = e.getKeyCode();
        int scan = e.getScanCode();
        boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() == 0) {
            // diagnostics: adb logcat -s OpenDTV-Keys   (shows what the keyboard / remote really sends)
            Log.d("OpenDTV-Keys", "key=" + android.view.KeyEvent.keyCodeToString(kc) + " (" + kc + ") scan=" + scan
                    + " dev=" + e.getDeviceId() + " bmlVisible=" + bml.isVisible());
        }
        if (kc == android.view.KeyEvent.KEYCODE_F5
                || scan == 63                                                // Linux KEY_F5, whatever keycode the layout maps it to
                || kc == android.view.KeyEvent.KEYCODE_TV_DATA_SERVICE      // the DATA button of a TV remote
                || kc == android.view.KeyEvent.KEYCODE_TV_TELETEXT) {       // remotes without it: the TEXT button
            if (down && e.getRepeatCount() == 0) toggleBml();
            return true;
        }
        if (!bml.isVisible()) return false;
        if (kc == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (down && e.getRepeatCount() == 0) toggleBml();
            return true;
        }
        int code = bmlCodeFor(kc);
        if (code < 0 && scan == 64) code = BmlController.BACK;               // Linux KEY_F6
        if (code < 0) return false;
        boolean arrow = code >= BmlController.UP && code <= BmlController.RIGHT;
        if (down && (e.getRepeatCount() == 0 || arrow)) bml.tap(code);   // holding an arrow keeps moving
        return true;
    }

    private static int bmlCodeFor(int kc) {
        switch (kc) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
            case android.view.KeyEvent.KEYCODE_W: return BmlController.UP;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
            case android.view.KeyEvent.KEYCODE_S: return BmlController.DOWN;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
            case android.view.KeyEvent.KEYCODE_A: return BmlController.LEFT;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
            case android.view.KeyEvent.KEYCODE_D: return BmlController.RIGHT;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER: return BmlController.ENTER;
            case android.view.KeyEvent.KEYCODE_BACK:
            case android.view.KeyEvent.KEYCODE_DEL:                          // Backspace
            case android.view.KeyEvent.KEYCODE_F6: return BmlController.BACK;
            case android.view.KeyEvent.KEYCODE_F1:
            case android.view.KeyEvent.KEYCODE_PROG_RED: return BmlController.RED;
            case android.view.KeyEvent.KEYCODE_F2:
            case android.view.KeyEvent.KEYCODE_PROG_GREEN: return BmlController.GREEN;
            case android.view.KeyEvent.KEYCODE_F3:
            case android.view.KeyEvent.KEYCODE_PROG_YELLOW: return BmlController.YELLOW;
            case android.view.KeyEvent.KEYCODE_F4:
            case android.view.KeyEvent.KEYCODE_PROG_BLUE: return BmlController.BLUE;
            default:
        }
        if (kc >= android.view.KeyEvent.KEYCODE_0 && kc <= android.view.KeyEvent.KEYCODE_9)
            return BmlController.DIGIT0 + (kc - android.view.KeyEvent.KEYCODE_0);
        if (kc >= android.view.KeyEvent.KEYCODE_NUMPAD_0 && kc <= android.view.KeyEvent.KEYCODE_NUMPAD_9)
            return BmlController.DIGIT0 + (kc - android.view.KeyEvent.KEYCODE_NUMPAD_0);
        return -1;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // a hidden (cached) data broadcast page is the first thing to give back when memory gets low
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && bml != null) bml.trimMemory();
    }

    // ------------------------------------------------------------------ no options menu
    //
    // There is deliberately no overflow: six of the ten items it carried were the same
    // callbacks as Settings rows (scan, manual scan, EWBS location, analyzer, record,
    // captions) under a second set of labels, and the labels had already drifted. Every
    // action lives in the Settings tab now, including the decoder switch and About.

    /**
     * Decoder switch. It writes the same preference the player is built from, so the
     * activity is rebuilt for it to take effect - the overflow item's behaviour, without
     * the two labels that had to stay in step ("Hardware decoder (off = FF..)" clipped on
     * a phone screen).
     */
    private void setHardwareDecoder(boolean hardware) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_HW_DECODER, hardware).apply();
        Toast.makeText(this, hardware ? "Hardware decoder selected" : "FFmpeg software decoder selected",
                Toast.LENGTH_SHORT).show();
        recreate();
    }

    /**
     * The Settings row under ABOUT. It used to build its own "Version x - built yyyy-MM-dd HH:mm:ss"
     * while the box said "Version x build [hash]" and "date . time" - the same two facts in two
     * shapes, four inches apart. The box owns the date; this row answers "which build".
     */
    private String aboutSummary() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // version stays "?" when the package info is unavailable
        }
        return BuildStamp.versionLine(version);
    }

    private void showAboutDialog() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // version stays "?" when the package info is unavailable
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_about, null);
        TextView versionView = content.findViewById(R.id.about_version);
        TextView buildView = content.findViewById(R.id.about_build);
        TextView stampView = content.findViewById(R.id.about_stamp);
        versionView.setText(getString(R.string.app_name));
        // One builder for this line: the Settings row under ABOUT prints the same string.
        buildView.setText(BuildStamp.versionLine(version));
        String date = BuildStamp.date();
        String time = BuildStamp.time();
        if (date.isEmpty() || time.isEmpty()) {
            stampView.setVisibility(View.GONE);
        } else {
            stampView.setText(date + "  .  " + time);
        }

        // MaterialAlertDialog, not the platform one: the box is meant to be the same rounded M3
        // panel as the build-info and confirm dialogs, and the layout now paints nothing so this
        // window's shape shows.
        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        // The door the owner defined: the art in the About box, thirteen taps - the same shape as
        // Android's build number - with a countdown so a press that lands by accident says so.
        // The count and the unlock both live in memory, so quitting the app hides the row again
        // and starts the countdown over.
        View door = content.findViewById(R.id.about_door);
        door.setOnClickListener(v -> {
            if (DeveloperActions.isUnlocked()) {
                dialog.dismiss();
                startActivity(new Intent(this, DeveloperOptionsActivity.class));
                return;
            }
            int left = DeveloperActions.tapDoor();
            if (left > 0) {
                // The first few taps are silent, like the build number: an accidental tap on a
                // picture must not advertise a developer door.
                if (DeveloperActions.doorTaps() > DeveloperActions.DOOR_SILENT_TAPS) {
                    doorToast(left + (left == 1 ? " more tap" : " more taps")
                            + " to unlock Developer options");
                }
                return;
            }
            doorToast("Developer options unlocked");
            refreshSettings();
            dialog.dismiss();
            startActivity(new Intent(this, DeveloperOptionsActivity.class));
        });
        View ok = content.findViewById(R.id.about_ok);
        ok.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        // OK is the safe landing spot; the image is a deliberate press up from it.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            ok.requestFocus();
        }
    }

    /** The last countdown toast; cancelled before the next one so fast taps do not queue. */
    private Toast devDoorToast;

    /**
     * One toast at a time for the door. Thirteen taps in a row would otherwise queue thirteen
     * toasts and the countdown would trail the finger by half a minute.
     */
    private void doorToast(String text) {
        if (devDoorToast != null) devDoorToast.cancel();
        devDoorToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        devDoorToast.show();
    }

    // ------------------------------------------------------------------ closed captions / audio language

    private int captionStyle() {
        int style = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_CC_STYLE, 2);
        return style == 1 ? 1 : style == 2 ? 2 : 0;
    }

    /** Creates / frees the styled caption WebView to match the Settings choice, and routes the captions to it. */
    private void applyCaptionStyle() {
        int style = captionStyle();
        boolean styled = style == 1;
        boolean auto = style == 2;
        if ((styled || auto) && captionView == null) {
            captionView = new CaptionView(playerView);
            captionView.setEnabled(captionOverlay == null || captionOverlay.isEnabled());
            captionView.setLanguage(captionLanguage());
        } else if (!styled && !auto && captionView != null) {
            captionView.destroy();
            captionView = null;
        }
        bindCaptionSink();
    }

    /** Sends the channel's captions to whichever display is selected. */
    private void bindCaptionSink() {
        if (currentPacketSink == null) return;
        int style = captionStyle();
        boolean styled = style == 1;
        boolean auto = style == 2;
        currentPacketSink.setCaptionSink(styled ? null : captionOverlay);
        currentPacketSink.setRawCaptionSink((styled || auto) ? captionView : null);
        currentPacketSink.setSplitCaptions(auto);
        currentPacketSink.setCaptionLanguage(captionLanguage());
    }

    private void resetCaptions() {
        if (captionOverlay != null) captionOverlay.reset();
        if (captionView != null) captionView.clear();
    }

    private static String captionStyleName(int style) {
        return style == 1 ? "Styled (broadcast layout)"
                : style == 2 ? "Auto (per broadcast)" : "Simple text line";
    }

    private void showCaptionDisplayDialog() {
        final String[] labels = {
                "Simple text line", "Styled (broadcast layout)", "Auto (per broadcast)"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Caption display")
                .setSingleChoiceItems(labels, captionStyle(), (dlg, which) -> {
                    setCaptionStyle(which);
                    dlg.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setCaptionStyle(int style) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_CC_STYLE, style).apply();
        resetCaptions();
        applyCaptionStyle();
        refreshSettings();
    }

    private void setCaptionsEnabled(boolean on) {
        captionOverlay.setEnabled(on);
        if (captionView != null) captionView.setEnabled(on);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_CC, on).apply();
        Toast.makeText(this, on ? "Captions on" : "Captions off",
                Toast.LENGTH_SHORT).show();
        // The switch shows the state itself: rebuilding the list here would
        // reset the scroll and drop the focus to the video surface.
    }

    private int captionLanguage() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_CC_LANG, 1) == 2 ? 2 : 1;
    }

    private static String captionLanguageName(int language) {
        return language == 2 ? "Language 2 (second, if broadcast)" : "Language 1 (main)";
    }

    private void showCaptionLanguageDialog() {
        final String[] labels = {"Language 1 (main)", "Language 2 (second, if broadcast)"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Caption language")
                .setSingleChoiceItems(labels, captionLanguage() - 1, (dlg, which) -> {
                    setCaptionLanguage(which + 1);
                    dlg.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setCaptionLanguage(int language) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_CC_LANG, language).apply();
        if (currentPacketSink != null) currentPacketSink.setCaptionLanguage(language);
        if (captionView != null) captionView.setLanguage(language);
        resetCaptions();
        refreshSettings();
    }

    /** "English", "Filipino", ... from an ISO 639 code; the upper-cased code when Android does not know it. */
    private static String languageName(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return null;
        try {
            String n = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault());
            if (n != null && !n.isEmpty() && !n.equalsIgnoreCase(code)) return n;
        } catch (RuntimeException ignored) {
            // unknown tag: fall back to the tag in upper case
        }
        return code.toUpperCase(Locale.ROOT);
    }

    /** ISO 639 language the user picked ("" = automatic), shown in the Settings tab. */
    private String audioLanguageSummary() {
        String code = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_AUDIO_LANG, "");
        String n = languageName(code);
        return n == null ? "Automatic (best quality)" : n;
    }

    /** Applies the saved audio language preference to the player (it picks the matching track when a channel starts). */
    private void applyAudioLanguagePref() {
        String code = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_AUDIO_LANG, "");
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(code.isEmpty() ? null : code)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build());
    }

    /** One audio track the channel offers (an elementary stream of the current service). */
    private String audioTrackLabel(int index, androidx.media3.common.Format f) {
        String lang = languageName(f.language);
        if (lang == null && currentChannelEntry != null && currentChannelEntry.service != null && f.id != null) {
            // Format id is "<program>/<pid>": fall back to the language our own PMT parser found for that PID
            try {
                String idPart = f.id.contains("/") ? f.id.substring(f.id.lastIndexOf('/') + 1) : f.id;
                int pid = Integer.parseInt(idPart);
                ServiceInfo live = demux != null ? findLiveService(currentChannelEntry.service.serviceId) : null;
                if (live != null) for (StreamInfo st : live.streams) if (st.pid == pid) { lang = languageName(st.language); break; }
            } catch (RuntimeException ignored) {
                // best effort: an unparseable id only costs the language label
            }
        }
        StringBuilder sb = new StringBuilder(lang != null ? lang : "Audio " + (index + 1));
        String codec = f.sampleMimeType == null ? null
                : f.sampleMimeType.contains("mp4a") ? "AAC" : f.sampleMimeType.contains("ac3") ? "AC-3"
                : f.sampleMimeType.contains("mpeg") ? "MP2" : null;
        if (codec != null || f.channelCount > 0) {
            sb.append("  (");
            if (codec != null) sb.append(codec);
            if (f.channelCount > 0) sb.append(codec != null ? " " : "").append(f.channelCount).append("ch");
            sb.append(')');
        }
        return sb.toString();
    }

    private ServiceInfo findLiveService(int sid) {
        try {
            for (ServiceInfo si : demux.getServices()) if (si.serviceId == sid) return si;
        } catch (RuntimeException ignored) {
            // demux not ready or already released: no live service to match
        }
        return null;
    }

    /**
     * Automatic audio choice = the best quality track of the channel: most channels, then highest sample rate,
     * then highest bitrate (when the stream says), then codec (AAC > AC-3 > MPEG audio). Skipped when the user picked a
     * language / track in Settings (then the saved language or the override decides).
     */
    private androidx.media3.common.TrackGroup autoAudioGroup;   // the group pickBestAudioTrack chose, not a manual pick

    private void pickBestAudioTrack(androidx.media3.common.Tracks tracks) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_AUDIO_LANG, "").isEmpty()) return;
        androidx.media3.common.TrackSelectionParameters tsp = player.getTrackSelectionParameters();
        androidx.media3.common.Tracks.Group best = null, selected = null;
        int audioGroups = 0;
        for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO || g.length == 0 || !g.isTrackSupported(0)) continue;
            audioGroups++;
            if (g.isTrackSelected(0)) selected = g;
            if (tsp.overrides.containsKey(g.getMediaTrackGroup()) && g.getMediaTrackGroup() != autoAudioGroup) return;   // a manual pick
            if (best == null || audioQuality(g.getTrackFormat(0)) > audioQuality(best.getTrackFormat(0))) best = g;
        }
        if (audioGroups < 2 || best == null || best == selected) return;
        autoAudioGroup = best.getMediaTrackGroup();
        player.setTrackSelectionParameters(tsp.buildUpon()
                .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(best.getMediaTrackGroup(), 0))
                .build());
    }

    private static long audioQuality(androidx.media3.common.Format f) {
        long bitrate = f.bitrate > 0 ? Math.min(f.bitrate / 1000, 4095) : 0;       // kbit/s
        long rate = f.sampleRate > 0 ? Math.min(f.sampleRate / 1000, 255) : 0;      // kHz
        long ch = f.channelCount > 0 ? Math.min(f.channelCount, 15) : 0;
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
        long codec = m.contains("mp4a") ? 3 : m.contains("ac3") ? 2 : m.contains("mpeg") ? 1 : 0;
        return (ch << 40) | (rate << 28) | (bitrate << 8) | codec;
    }

    /** Settings > Audio Language: lists the audio streams of the playing channel and switches between them. */
    private void showAudioLanguageDialog() {
        final List<androidx.media3.common.Tracks.Group> groups = new ArrayList<>();
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO && g.length > 0) groups.add(g);
        }
        if (groups.isEmpty()) {
            Toast.makeText(this, "Tune a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[groups.size() + 1];
        labels[0] = "Automatic (best quality)";
        int checked = 0;
        for (int i = 0; i < groups.size(); i++) {
            androidx.media3.common.Tracks.Group g = groups.get(i);
            labels[i + 1] = audioTrackLabel(i, g.getTrackFormat(0)) + (g.isTrackSupported(0) ? "" : "  [not supported]");
            if (g.isTrackSelected(0) && g.getMediaTrackGroup() != autoAudioGroup
                    && player.getTrackSelectionParameters().overrides.containsKey(g.getMediaTrackGroup())) checked = i + 1;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Audio language")
                .setSingleChoiceItems(labels, checked, (dlg, which) -> {
                    android.content.SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                    androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
                    if (which == 0) {
                        ed.putString(PREF_AUDIO_LANG, "");
                        b.setPreferredAudioLanguage(null).clearOverridesOfType(C.TRACK_TYPE_AUDIO);
                    } else {
                        androidx.media3.common.Tracks.Group g = groups.get(which - 1);
                        String lang = g.getTrackFormat(0).language;
                        boolean known = lang != null && !lang.isEmpty() && !lang.equals("und");
                        // remember the language (so it is picked again on other channels), the override switches now
                        ed.putString(PREF_AUDIO_LANG, known ? lang : "");
                        b.setPreferredAudioLanguage(known ? lang : null)
                                .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(g.getMediaTrackGroup(), 0));
                    }
                    ed.apply();
                    player.setTrackSelectionParameters(b.build());
                    dlg.dismiss();
                    refreshSettings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Settings > EWBS Location: choose the Philippine area used to filter emergency warnings. */
    private void showEwbsLocationDialog() {
        new EwbsLocationDialog().show();
    }

    /** Zone codes with the leading "show everything" entry (0). */
    private static int[] ewbsZones() {
        int[] table = EwbsAreaTable.zoneCodes();
        int[] zones = new int[table.length + 1];
        System.arraycopy(table, 0, zones, 1, table.length);
        return zones;
    }

    /** "1 channel" / "3 channels" - no more "channel(s)". */
    private static String channelCount(int n) {
        return n == 1 ? "1 channel" : n + " channels";
    }

    private static String ewbsLabel(int code) {
        return code == 0 ? "Show all alerts" : EwbsAreaTable.name(code);
    }

    /** The zones have a fixed order: Luzon, Visayas, Mindanao. Not alphabetical. */
    private static int zoneRank(int code) {
        String name = EwbsAreaTable.name(code);
        if ("Luzon".equalsIgnoreCase(name)) return 1;
        if ("Visayas".equalsIgnoreCase(name)) return 2;
        if ("Mindanao".equalsIgnoreCase(name)) return 3;
        return 9;
    }

    private void setEwbsRowEnabled(View row, boolean enabled) {
        row.setEnabled(enabled);
        row.setFocusable(enabled);
        row.setClickable(enabled);
        row.setAlpha(enabled ? 1f : 0.38f);
    }

    /**
     * One card with two pages: the three fields, and the list a field opens.
     * The list replaces the page instead of stacking a second dialog on top, the
     * way the scan sheet does it, so everything keeps the app's own chrome.
     */
    private final class EwbsLocationDialog {
        private final AlertDialog dialog;
        private final int[] original = new int[3];
        private final int[] pick = new int[3];
        private final View fieldsPage;
        private final View listPage;
        private final LinearLayout listItems;
        private final ScrollView listScroll;
        private final TextView listTitle;
        private final TextView zoneValue;
        private final TextView regionValue;
        private final TextView cityValue;
        private final TextView codeText;
        private final View zoneRow;
        private final View regionRow;
        private final View cityRow;
        private final Button auto;
        private View lastRow;

        EwbsLocationDialog() {
            int current = ewbsLocation();
            if (current == EwbsAreaTable.NATIONWIDE) current = 0;   // Nationwide is just Show all
            original[0] = pick[0] = current == 0 ? 0 : EwbsAreaTable.zoneOf(current);
            original[1] = pick[1] = current == 0 ? 0 : EwbsAreaTable.regionOf(current);
            original[2] = pick[2] = current;

            View content = getLayoutInflater().inflate(R.layout.dialog_ewbs_location, null, false);
            fieldsPage = content.findViewById(R.id.ewbs_page_fields);
            listPage = content.findViewById(R.id.ewbs_page_list);
            listItems = content.findViewById(R.id.ewbs_list_items);
            listScroll = content.findViewById(R.id.ewbs_list_scroll);
            listTitle = content.findViewById(R.id.ewbs_list_title);
            codeText = content.findViewById(R.id.ewbs_code);
            zoneRow = content.findViewById(R.id.ewbs_row_zone);
            regionRow = content.findViewById(R.id.ewbs_row_region);
            cityRow = content.findViewById(R.id.ewbs_row_city);
            zoneValue = zoneRow.findViewById(R.id.picker_value);
            regionValue = regionRow.findViewById(R.id.picker_value);
            cityValue = cityRow.findViewById(R.id.picker_value);
            auto = content.findViewById(R.id.ewbs_auto);
            ((TextView) zoneRow.findViewById(R.id.picker_label)).setText("Zone");
            ((TextView) regionRow.findViewById(R.id.picker_label)).setText("Region");
            ((TextView) cityRow.findViewById(R.id.picker_label)).setText("City");

            content.findViewById(R.id.ewbs_list_back).setOnClickListener(v -> showFields(zoneRow));

            zoneRow.setOnClickListener(v -> openList(v, "Zone", ewbsZones(), pick[0], which -> {
                int[] zones = ewbsZones();
                pick[0] = zones[which];
                int[] regions = pick[0] == 0 ? new int[0] : EwbsAreaTable.regionsInZone(pick[0]);
                pick[1] = regions.length == 0 ? 0 : regions[0];
                int[] cities = pick[1] == 0 ? new int[0] : EwbsAreaTable.citiesInRegion(pick[1]);
                pick[2] = cities.length == 0 ? 0 : cities[0];
            }));

            regionRow.setOnClickListener(v -> {
                if (pick[0] == 0) return;
                final int[] regions = EwbsAreaTable.regionsInZone(pick[0]);
                openList(v, "Region", regions, pick[1], which -> {
                    pick[1] = regions[which];
                    int[] cities = EwbsAreaTable.citiesInRegion(pick[1]);
                    pick[2] = cities.length == 0 ? 0 : cities[0];
                });
            });

            cityRow.setOnClickListener(v -> {
                if (pick[1] == 0) return;
                final int[] cities = EwbsAreaTable.citiesInRegion(pick[1]);
                openList(v, "City", cities, pick[2], which -> pick[2] = cities[which]);
            });

            dialog = new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("EWBS location")
                    .setView(content)
                    .setPositiveButton("Apply", (d, which) -> saveEwbsLocation(selectedCode()))
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP
                        && listPage.getVisibility() == View.VISIBLE) {
                    showFields(lastRow);
                    return true;
                }
                return false;
            });

            auto.setOnClickListener(v -> detectEwbsLocation(new EwbsLocationCallback() {
                @Override public void onLocation(int code) {
                    if (code == 0) {
                        Toast.makeText(MainActivity.this,
                                "Location not matched · pick the area manually",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    applyDetected(code);
                    Toast.makeText(MainActivity.this,
                            "Current location: " + EwbsAreaTable.name(code), Toast.LENGTH_SHORT).show();
                }
            }));
        }

        void show() {
            dialog.show();
            render();               // the buttons only exist once the dialog is up
            auto.requestFocus();
        }

        /** The most specific chosen level, which is what gets stored. */
        private int selectedCode() {
            if (pick[0] == 0) return 0;
            if (pick[2] != 0) return pick[2];
            if (pick[1] != 0) return pick[1];
            return pick[0];
        }

        private void render() {
            zoneValue.setText(ewbsLabel(pick[0]));
            regionValue.setText(pick[0] == 0 || pick[1] == 0 ? "—" : EwbsAreaTable.name(pick[1]));
            cityValue.setText(pick[1] == 0 || pick[2] == 0 ? "—" : EwbsAreaTable.name(pick[2]));
            setEwbsRowEnabled(regionRow, pick[0] != 0);
            setEwbsRowEnabled(cityRow, pick[1] != 0);
            codeText.setText(String.format(Locale.US, "Code: 0x%03X", selectedCode()));
            if (dialog.isShowing()) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                        pick[0] != original[0] || pick[1] != original[1] || pick[2] != original[2]);
            }
        }

        /** Takes a detected code and rewrites the three fields. Nothing is stored: Apply decides. */
        private void applyDetected(int code) {
            if (code == 0 || code == EwbsAreaTable.NATIONWIDE) {
                pick[0] = 0;
                pick[1] = 0;
                pick[2] = 0;
            } else if (EwbsAreaTable.isZone(code)) {
                pick[0] = code;
                pick[1] = 0;
                pick[2] = 0;
            } else if (EwbsAreaTable.isRegion(code)) {
                pick[0] = EwbsAreaTable.zoneOf(code);
                pick[1] = code;
                pick[2] = 0;
            } else {
                pick[0] = EwbsAreaTable.zoneOf(code);
                pick[1] = EwbsAreaTable.regionOf(code);
                pick[2] = code;
            }
            render();
            zoneRow.requestFocus();
        }

        private void showFields(View row) {
            listPage.setVisibility(View.GONE);
            fieldsPage.setVisibility(View.VISIBLE);
            (row != null ? row : auto).requestFocus();
            render();
        }

        /** Fills the list page with the given codes, alphabetically, current value first in sight. */
        private void openList(View from, String title, int[] codes, int selected, IntConsumer onPick) {
            if (codes.length == 0) return;
            lastRow = from;
            listTitle.setText(title);
            listItems.removeAllViews();

            Integer[] order = new Integer[codes.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> {
                if (codes[a] == 0 && codes[b] != 0) return -1;   // "Show all alerts" stays first
                if (codes[b] == 0 && codes[a] != 0) return 1;
                int rankA = zoneRank(codes[a]);
                int rankB = zoneRank(codes[b]);
                if (rankA != rankB) return Integer.compare(rankA, rankB);
                return ewbsLabel(codes[a]).compareToIgnoreCase(ewbsLabel(codes[b]));
            });

            final int gap = Math.round(6 * getResources().getDisplayMetrics().density);
            View focusRow = null;
            for (int n = 0; n < order.length; n++) {
                final int index = order[n];
                View row = getLayoutInflater().inflate(R.layout.view_choice_row, listItems, false);
                ((TextView) row.findViewById(R.id.choice_label)).setText(ewbsLabel(codes[index]));
                row.findViewById(R.id.choice_check)
                        .setVisibility(codes[index] == selected ? View.VISIBLE : View.GONE);
                row.setOnClickListener(v -> {
                    onPick.accept(index);
                    showFields(lastRow);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (n > 0) lp.topMargin = gap;
                listItems.addView(row, lp);
                if (codes[index] == selected) focusRow = row;

                // TEMP (public build): the "Show all alerts" shortcut and its rule
                // come out before release; the zones then start at Luzon.
                if (codes[index] == 0 && n < order.length - 1) {
                    View rule = new View(MainActivity.this);
                    rule.setBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.odtv_divider));
                    LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            Math.max(1, Math.round(getResources().getDisplayMetrics().density)));
                    ruleParams.topMargin = gap + Math.round(4 * getResources().getDisplayMetrics().density);
                    ruleParams.bottomMargin = gap + Math.round(4 * getResources().getDisplayMetrics().density);
                    listItems.addView(rule, ruleParams);
                }
            }

            final View target = focusRow != null ? focusRow : listItems.getChildAt(0);
            fieldsPage.setVisibility(View.GONE);
            listPage.setVisibility(View.VISIBLE);
            listScroll.post(() -> {
                target.requestFocus();
                listScroll.smoothScrollTo(0, Math.max(0, target.getTop() - listScroll.getHeight() / 2));
            });
        }
    }

    private void saveEwbsLocation(int code) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_EWBS_LOCATION, code).apply();
        updateContentAdapter();
        ewbsBanner.update(hub != null ? hub.demux() : demux, ewbsLocation());
        updateWatchInfo(hub != null ? hub.demux() : demux);
    }

    /** Resolves the phone's current place to a PH EWBS city/region code. */
    private void detectEwbsLocation(EwbsLocationCallback callback) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingEwbsLocation = callback;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_EWBS_LOCATION);
            return;
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            callback.onLocation(0);
            return;
        }
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location location = manager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            } catch (Exception ignored) {
                // provider disabled or not permitted: try the next one
            }
        }
        if (best != null) {
            resolveEwbsLocation(best, callback);
            return;
        }
        String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER
                : manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        ? LocationManager.GPS_PROVIDER : null;
        if (provider == null) {
            callback.onLocation(0);
            return;
        }
        final LocationListener[] ref = new LocationListener[1];
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                try { manager.removeUpdates(ref[0]); } catch (Exception ignored) {
                    // already removed by the 15 s timeout below
                }
                resolveEwbsLocation(location, callback);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };
        ref[0] = listener;
        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            ui.postDelayed(() -> {
                try { manager.removeUpdates(listener); } catch (Exception ignored) {
                    // already removed in onLocationChanged
                }
            }, 15000);
        } catch (Exception e) {
            Log.w(TAG, "Location request failed", e);
            callback.onLocation(0);
        }
    }

    private void resolveEwbsLocation(Location location, EwbsLocationCallback callback) {
        final double latitude = location.getLatitude();
        final double longitude = location.getLongitude();
        new Thread(() -> {
            int code = 0;
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                    List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address address = addresses.get(0);
                        String[] names = {address.getLocality(), address.getSubAdminArea(),
                                address.getAdminArea(), address.getSubLocality()};
                        for (String name : names) {
                            code = EwbsAreaTable.findByName(name);
                            if (code != 0) break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not reverse-geocode EWBS location", e);
            }
            final int result = code;
            ui.post(() -> callback.onLocation(result));
        }, "ewbs-geocode").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_EWBS_LOCATION) return;
        EwbsLocationCallback callback = pendingEwbsLocation;
        pendingEwbsLocation = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && callback != null) {
            detectEwbsLocation(callback);
        } else {
            Toast.makeText(this, "Location permission denied · pick the area manually",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void toggleChannelRecording() {
        if (currentPacketSink == null) {
            Toast.makeText(this, "Tune a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!currentPacketSink.isRecording()) {
            if (!checkAndRequestStoragePermission(this)) return;
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "OpenDTV/Record");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                String chNum = (currentChannelEntry != null && currentChannelEntry.service != null) ? currentChannelEntry.service.virtualNumber : "0";
                String chName = (currentChannelEntry != null && currentChannelEntry.service != null) ? currentChannelEntry.service.name : "Channel";
                chNum = chNum.replaceAll("[^a-zA-Z0-9_-]", "_");
                chName = chName.replaceAll("[^a-zA-Z0-9_-]", "_");
                String timeStamp = new SimpleDateFormat("MMddyyyy-HHmmss", Locale.US).format(new Date());
                String fileName = chNum + "-" + chName + "-" + timeStamp + ".ts";
                File file = new File(dir, fileName);
                currentPacketSink.startRecord(file);
                Toast.makeText(this, currentChannelEntry != null && currentChannelEntry.service != null
                        ? "Recording " + ph.opendtv.ui.ChannelAdapter.displayName(currentChannelEntry.service)
                        : "Recording", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show();
            }
        } else {
            boolean wrote = currentPacketSink.stopRecord();
            Toast.makeText(this,
                    wrote ? "Recording stopped · OpenDTV/Record"
                          : "Nothing recorded · this service sent no stream",
                    Toast.LENGTH_SHORT).show();
        }
        updateRecordTally();
    }

    /** Recording is the writer's state, not a flag of our own. */
    private boolean isRecording() {
        return currentPacketSink != null && currentPacketSink.isRecording();
    }

    /**
     * The tally light, from one reading of the writer: one REC key in either layout (the
     * channel row in portrait, above the info button in landscape), a filled red disc that
     * pulses while the sink is writing and a quiet ring when it is not. The pulse runs on the
     * background drawable's alpha so the word stays solid - a label fading in and out reads as
     * a redraw, a ring that breathes reads as a tally. Before this the only sign a recording
     * was running was a menu item that was not even on screen.
     */
    private void updateRecordTally() {
        boolean on = isRecording();
        // One tick a second while it runs: if the writer dies on its own, the light does too.
        ui.removeCallbacks(recordTick);
        if (on) ui.postDelayed(recordTick, 1000);
        if (btnRecord == null) return;
        btnRecord.setSelected(on);
        btnRecord.setContentDescription(on ? "Stop recording" : "Record channel");
        if (on) {
            startRecordPulse();
        } else {
            stopRecordPulse();
        }
    }

    private void startRecordPulse() {
        if (recordPulse != null) return;
        Drawable ring = btnRecord.getBackground();
        if (ring == null) return;
        ring = ring.mutate();
        final Drawable target = ring;
        // Someone who turned animation off gets a steady red disc, not a strobe.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            target.setAlpha(255);
            return;
        }
        recordPulse = ValueAnimator.ofInt(255, 120);
        recordPulse.setDuration(700);
        recordPulse.setRepeatCount(ValueAnimator.INFINITE);
        recordPulse.setRepeatMode(ValueAnimator.REVERSE);
        recordPulse.setInterpolator(new AccelerateDecelerateInterpolator());
        recordPulse.addUpdateListener(a -> target.setAlpha((Integer) a.getAnimatedValue()));
        recordPulse.start();
    }

    private void stopRecordPulse() {
        if (recordPulse != null) {
            recordPulse.cancel();
            recordPulse = null;
        }
        if (btnRecord != null && btnRecord.getBackground() != null) {
            btnRecord.getBackground().setAlpha(255);
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

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            videoRendered = true;
            retries = 0;
            hideTuningCard();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            // Audio-only services never render a frame: their "tuning" ends when
            // the player is ready.
            if (state == Player.STATE_READY && !hasVideoTrack()) hideTuningCard();
        }

        @Override
        public void onTracksChanged(androidx.media3.common.Tracks tracks) {
            for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
                if (group.length == 0) continue;
                if (group.getType() == C.TRACK_TYPE_VIDEO || group.getType() == C.TRACK_TYPE_AUDIO) {
                    Log.i(TAG, "track type=" + group.getType() + " mime="
                            + group.getTrackFormat(0).sampleMimeType + " selected=" + group.isTrackSelected(0));
                }
            }
            pickBestAudioTrack(tracks);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.w(TAG, "player error: " + error.getErrorCodeName() + " " + error.getMessage());
            final ChannelEntry e = currentChannelEntry;
            if (e != null && !isFinishing() && retries++ < MAX_RETRIES) {
                // Same multiplex: no retune, just a fresh filter + prepare.
                ui.postDelayed(() -> {
                    if (!isFinishing() && !scanning && currentChannelEntry == e) tuneAndPlay(e);
                }, 1000);
            } else {
                // The card says this and stays up; a toast on top of it said the same thing twice.
                showNoSignalCard();
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        // Came back from the background / Analyzer: resume the channel (same multiplex = instant).
        if (tunerReady && currentChannelEntry != null && !scanning) {
            retries = 0;
            tuneAndPlay(currentChannelEntry);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Nothing to watch in the background: stop decoding and stop feeding the pipe (CPU, battery, heap).
        playGen.incrementAndGet();
        ui.removeCallbacksAndMessages(null);
        player.stop();
        player.clearMediaItems();
        hub.detachSink(this);
        pipe.clear();
        resetCaptions();
        if (bml != null) bml.close();        // background: no data-broadcast page kept alive unseen
        if (tuning != null) tuning.hide();
    }

    /**
     * The Analyzer opens the USB dongle on its own (not through TunerHub). Two owners of one device break each
     * other, so release the tuner completely first; onResume re-initialises it when we come back.
     */
    private void openAnalyzer() {
        playGen.incrementAndGet();
        // Set before the shutdown, and held until onActivityResult: the guard in initTuner and
        // onResume reads it, so nothing re-opens the dongle while the Analyzer is claiming it.
        handingOver = true;
        tunerReady = false;
        ui.removeCallbacksAndMessages(null);
        player.stop();
        player.clearMediaItems();
        hub.detachSink(this);
        pipe.clear();
        try {
            worker.execute(() -> {
                hub.shutdown();   // blocks until the dongle is closed (off the UI thread)
                ui.post(() -> {
                    if (isFinishing()) {
                        handingOver = false;   // never launched: forget the hand-off
                        return;
                    }
                    // The Analyzer tunes its own USB source, so it needs the frequency that is
                    // playing. Without it the source starts on currentKhz() = -1 kHz, which is
                    // a failed tune before the TS port opens: a lock on screen, 0 services.
                    Intent i = new Intent(MainActivity.this, AnalyzerActivity.class);
                    ChannelEntry e = currentChannelEntry;
                    if (e != null && e.khz > 0) i.putExtra("tune_khz", e.khz);
                    startActivityForResult(i, REQ_ANALYZER);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // executor already shutting down; the activity is going away
            handingOver = false;
        }
    }

    /**
     * The Analyzer has finished and closed the dongle. Only now is it safe to take the tuner back;
     * clearing the flag here (not in onResume) is what lets the return trip re-initialise while the
     * departure trip does not.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ANALYZER) return;
        handingOver = false;
        tunerReady = false;
        initTuner();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // The Developers screen's "Open Analyzer" row arrives on the live instance (singleTop).
        if (intent.getBooleanExtra(EXTRA_DEV_ANALYZER, false)) {
            intent.removeExtra(EXTRA_DEV_ANALYZER);
            openAnalyzer();
            return;
        }
        // USB_DEVICE_ATTACHED re-delivers to this singleTop activity
        if (!tunerReady) initTuner();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ewbsTick);
        ui.post(ewbsTick);
        hideSystemBars();
        // The Test EWBS row lives on the hidden Developers screen now; the dialog did not move.
        ph.opendtv.dev.DeveloperActions.setDemux(hub != null ? hub.demux() : demux);

        // The three STATE rows on the Developers screen. They run synchronously because the
        // screen restarts the task the moment RESET_ALL returns; anything deferred is cut.
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        ph.opendtv.dev.DeveloperActions.register(ph.opendtv.dev.DeveloperActions.CLEAR_CHANNELS, () -> {
            p.edit().remove(PREF_CHANNELS).commit();
            allChannels.clear();
            loadChannels();
            ui.post(() -> updateWatchInfo(hub != null ? hub.demux() : demux));
        });
        ph.opendtv.dev.DeveloperActions.register(ph.opendtv.dev.DeveloperActions.RESET_FAVORITES, () -> {
            p.edit().remove(PREF_FAV).remove(PREF_FAV_ORDER).commit();
            loadFavorites();
            ui.post(() -> updateWatchInfo(hub != null ? hub.demux() : demux));
        });
        ph.opendtv.dev.DeveloperActions.register(ph.opendtv.dev.DeveloperActions.RESET_ALL,
                () -> p.edit().clear().commit());

        // A recording can outlive a trip to the Analyzer: re-read the writer, do not guess.
        updateRecordTally();
        // While the Analyzer is taking the dongle, none of the paths below may open it - not even
        // "the hub is closed, reopen it", which is exactly right on the way BACK and exactly wrong
        // on the way out. onActivityResult clears handingOver and re-initialises there.
        if (!handingOver) {
            if (!tunerReady && !scanning) initTuner();
            // tunerReady only says "we initialised once". A direct start of the Analyzer takes the
            // dongle away without going through openAnalyzer(), so on return this hub is stale and
            // the flag still claims otherwise - reopen instead of sitting on a dead hub.
            else if (hub != null && !hub.isOpen()) {
                tunerReady = false;
                initTuner();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ewbsTick);
        // A pulsing view nobody can see is a wakeup a second; the state is re-read on resume.
        stopRecordPulse();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
        if (recyclerView != null && recyclerView.getLayoutManager() != null) {
            outState.putParcelable(STATE_LIST, recyclerView.getLayoutManager().onSaveInstanceState());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        if (sidePanel != null || getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            if (getSupportActionBar() != null) {
                getSupportActionBar().hide();
            }
        } else {
            // portrait (also after rotating back from landscape): normal status bar + toolbar
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            if (getSupportActionBar() != null) {
                getSupportActionBar().show();
            }
        }
    }

    /**
     * Favourites are stored as "frequency:serviceId" (see ChannelAdapter.favoriteKey). A stored
     * bare SID is from before that: it cannot say which mux the reader meant, so it becomes every
     * saved channel that carries it - the state they actually saw - and is rewritten once.
     */
    private void loadFavorites() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        favorites.clear();
        favoriteOrder.clear();
        boolean migrated = false;
        for (String s : sp.getStringSet(PREF_FAV, new HashSet<>())) {
            String key = parseFavoriteKey(s);
            if (key != null) favorites.add(key);
            else migrated |= expandLegacyFavorite(s);
        }
        for (String part : sp.getString(PREF_FAV_ORDER, "").split(",")) {
            if (part.isEmpty()) continue;
            String key = parseFavoriteKey(part);
            if (key != null) {
                if (!favoriteOrder.contains(key)) favoriteOrder.add(key);
            } else {
                migrated |= expandLegacyFavorite(part);
            }
        }
        if (migrated) {
            saveFavorites();
            saveFavoriteOrder();
        }
    }

    /** "485143:64" -> the key, or null for a pre-frequency entry (a bare SID). */
    private static String parseFavoriteKey(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1) return null;
        try {
            int khz = Integer.parseInt(s.substring(0, colon).trim());
            int sid = Integer.parseInt(s.substring(colon + 1).trim());
            return ph.opendtv.ui.ChannelAdapter.favoriteKey(khz, sid);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Expands one legacy bare SID into the saved channels that carry it. True if it added any. */
    private boolean expandLegacyFavorite(String s) {
        int sid;
        try {
            sid = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        boolean added = false;
        for (ChannelEntry ce : allChannels) {
            if (ce.service.serviceId != sid) continue;
            String key = ph.opendtv.ui.ChannelAdapter.favoriteKey(ce.service);
            added |= favorites.add(key);
            if (!favoriteOrder.contains(key)) {
                favoriteOrder.add(key);
                added = true;
            }
        }
        return added;
    }

    private void saveFavoriteOrder() {
        StringBuilder sb = new StringBuilder();
        for (String key : favoriteOrder) {
            if (sb.length() > 0) sb.append(',');
            sb.append(key);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_FAV_ORDER, sb.toString()).apply();
    }

    /** Persists the favourites order as the adapter currently shows it. */
    private void saveOrderFromAdapter() {
        if (favoritesAdapter == null) return;
        favoriteOrder.clear();
        for (ServiceInfo s : favoritesAdapter.items()) {
            favoriteOrder.add(ph.opendtv.ui.ChannelAdapter.favoriteKey(s));
        }
        saveFavoriteOrder();
    }

    /** Drag to reorder on touch; the long-press path covers the TV remote. */
    private void attachFavoritesDrag() {
        if (favoritesDrag == null) {
            favoritesDrag = new androidx.recyclerview.widget.ItemTouchHelper(
                    new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                            androidx.recyclerview.widget.ItemTouchHelper.UP
                                    | androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
                        @Override
                        public boolean onMove(@androidx.annotation.NonNull RecyclerView rv,
                                              @androidx.annotation.NonNull RecyclerView.ViewHolder vh,
                                              @androidx.annotation.NonNull RecyclerView.ViewHolder target) {
                            int from = vh.getAdapterPosition();
                            int to = target.getAdapterPosition();
                            if (from < 0 || to < 0 || favoritesAdapter == null) return false;
                            // Belt and braces with detachFavoritesDrag(): a row may only be
                            // picked up while the favourites list is the one on screen.
                            if (rv.getAdapter() != favoritesAdapter) return false;
                            favoritesAdapter.moveItem(from, to);
                            movingPosition = to;
                            saveOrderFromAdapter();
                            return true;
                        }

                        @Override
                        public void onSwiped(@androidx.annotation.NonNull RecyclerView.ViewHolder vh,
                                             int direction) {
                        }
                    });
        }
        favoritesDrag.attachToRecyclerView(recyclerView);
    }

    /**
     * Puts the drag away with the tab. An ItemTouchHelper stays attached to the RecyclerView
     * across adapter swaps, so while this was only ever attached and never removed, a long
     * press on a Channels or Settings row picked the row up and dropped it back where it was:
     * onMove was reordering the stale favourites list behind the one on screen.
     */
    private void detachFavoritesDrag() {
        if (favoritesDrag != null) favoritesDrag.attachToRecyclerView(null);
        favoritesAdapter = null;
    }

    private void enterMoveMode(int position) {
        if (favoritesAdapter == null) return;
        movingPosition = position;
        Toast.makeText(this, "Move with ▲▼ · OK when done", Toast.LENGTH_SHORT).show();
    }

    private void moveFavorite(int delta) {
        if (favoritesAdapter == null || movingPosition < 0) return;
        int from = movingPosition;
        int to = from + delta;
        if (to < 0 || to >= favoritesAdapter.getItemCount()) return;
        favoritesAdapter.moveItem(from, to);
        movingPosition = to;
        saveOrderFromAdapter();
    }

    private void saveFavorites() {
        Set<String> favs = new HashSet<>(favorites);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(PREF_FAV, favs).apply();
    }

    private void saveChannels() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        StringBuilder sb = new StringBuilder();
        for (ChannelEntry ce : allChannels) {
            sb.append(ce.khz).append('|')
                    .append(ce.service.serviceId).append('|')
                    .append(ce.service.name).append('|')
                    .append(ce.service.provider).append('|')
                    .append(ce.service.virtualNumber).append('\n');
        }
        editor.putString(PREF_CHANNELS, sb.toString()).apply();
    }

    private void loadChannels() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString(PREF_CHANNELS, "");
        allChannels.clear();
        for (String line : raw.split("\n")) {
            String[] p = line.split("\\|");
            if (p.length < 5) continue;
            try {
                int khz = Integer.parseInt(p[0]);
                int sid = Integer.parseInt(p[1]);
                ServiceInfo svc = new ServiceInfo(sid);
                svc.name = p[2];
                svc.provider = p[3];
                svc.virtualNumber = p[4];
                svc.pmtSeen = true;
                allChannels.add(new ChannelEntry(khz, svc));
            } catch (Exception ignored) {
                // a malformed channel line is skipped; the rest still load
            }
        }
    }

    /** Last tuned channel if it is still in the list, otherwise the first one. */
    private ChannelEntry pickStartChannel() {
        if (allChannels.isEmpty()) return null;
        int sid = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_LAST_SID, -1);
        int khz = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_LAST_KHZ, -1);
        for (ChannelEntry ce : allChannels) {
            if (ce.service.serviceId == sid && (khz < 0 || ce.khz == khz)) return ce;
        }
        return allChannels.get(0);
    }

    /** 0 = Stretch (fill 16:9), 1 = Fit (keeps ratio, black bars), 2 = Zoom (keeps ratio, crops). */
    /** Aspect ratio as a pick-one list, matching the audio language row. */
    private void showAspectRatioDialog() {
        final int current = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_ASPECT, 0);
        final String[] labels = {
                "Stretch (full 16:9)", "Fit (original ratio)", "Zoom (crop to 16:9)"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Video aspect ratio")
                .setSingleChoiceItems(labels, current, (dlg, which) -> {
                    applyAspectMode(which, false);
                    dlg.dismiss();
                    refreshSettings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String aspectModeName(int mode) {
        switch (mode) {
            case 1: return "Fit (original ratio)";
            case 2: return "Zoom (crop to 16:9)";
            default: return "Stretch (full 16:9)";
        }
    }

    private void applyAspectMode(int mode, boolean announce) {
        int rm;
        String name;
        switch (mode) {
            case 1: rm = AspectRatioFrameLayout.RESIZE_MODE_FIT; name = "Fit (original ratio)"; break;
            case 2: rm = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; name = "Zoom (crop to 16:9)"; break;
            default: mode = 0; rm = AspectRatioFrameLayout.RESIZE_MODE_FILL; name = "Stretch (full 16:9)";
        }
        playerView.setResizeMode(rm);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_ASPECT, mode).apply();
        if (announce) Toast.makeText(this, name, Toast.LENGTH_SHORT).show();
    }

    private void saveLastChannel(ChannelEntry e) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_LAST_SID, e.service.serviceId)
                .putInt(PREF_LAST_KHZ, e.khz).apply();
    }

    private int ewbsLocation() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_EWBS_LOCATION, 0);
    }

    // ------------------------------------------------------------------ tuning / no-signal card

    /** Shown 500 ms into a tune, only if no frame has arrived. */
    private void showTuningCard() {
        if (tuning == null || videoRendered) return;
        if (bml != null && bml.isVisible()) return;
        if (currentChannelEntry == null) {
            tuning.hide();
            return;
        }
        ServiceInfo service = currentChannelEntry.service;
        String number = service.virtualNumber == null || service.virtualNumber.isEmpty()
                ? String.valueOf(service.serviceId) : service.virtualNumber;
        tuning.showTuning(number, ph.opendtv.ui.ChannelAdapter.displayName(service),
                frequencyText());
    }

    /** "UHF 18 · 497.143 MHz", or empty when there is no frequency. */
    private String frequencyText() {
        int khz = currentChannelEntry != null ? currentChannelEntry.khz : 0;
        if (khz <= 0) return "";
        int uhf = phChannel(khz);
        return (uhf >= 0 ? "UHF " + uhf + " · " : "")
                + String.format(Locale.US, "%.3f MHz", khz / 1000.0);
    }

    /** Same card after the grace period: no frame, so say why. */
    private void showNoSignalCard() {
        if (tuning == null || videoRendered) return;
        if (bml != null && bml.isVisible()) return;
        ServiceInfo saved = currentChannelEntry != null ? currentChannelEntry.service : null;
        ServiceInfo live = liveService(hub != null ? hub.demux() : null);
        ServiceInfo service = live != null ? live : saved;
        String name = service != null ? ph.opendtv.ui.ChannelAdapter.displayName(service) : null;
        // A scrambled service never produces a frame, but the tuner is locked and the mux is
        // healthy - telling the viewer to check the antenna would send them after a fault
        // that is not there. The CA flag comes from the PMT, so it only exists once locked.
        boolean scrambled = (live != null && live.encrypted) || (saved != null && saved.encrypted);
        // A service on a healthy mux can also carry no video at all (audio or data only). The
        // PMT tells us that much, so the copy must not blame the aerial either.
        boolean noVideo = live != null && live.pmtSeen && !live.hasVideo();
        // The third case: locked, a video stream declared, and still no frame. The tuner did its
        // job and the decoder has nothing to show yet, which is not a fault either.
        SignalStatus sig = hub != null ? hub.signal() : null;
        boolean locked = sig != null && sig.locked;
        boolean tooWeak = live != null && live.pmtSeen && live.hasVideo() && locked;
        if (scrambled) {
            tuning.showEncrypted(name, frequencyText());
        } else if (noVideo) {
            tuning.showNoVideo(name, frequencyText());
        } else if (tooWeak) {
            tuning.showLockedNoFrame(name, frequencyText());
        } else {
            tuning.showNoSignal(name, frequencyText());
        }
    }

    private void showNoTunerCard() {
        if (tuning == null) return;
        ui.removeCallbacks(showTuningCardRunnable);
        ui.removeCallbacks(showNoSignalCardRunnable);
        tuning.showNoTuner();
    }

    private void hideTuningCard() {
        ui.removeCallbacks(showTuningCardRunnable);
        ui.removeCallbacks(showNoSignalCardRunnable);
        if (tuning != null) tuning.hide();
    }

    private void updateWatchInfo(TsDemux d) {
        WatchInfoView info = watchInfo;
        if (info == null) return;
        ServiceInfo live = liveService(d);
        boolean data = false;
        boolean caption = false;
        if (live != null) {
            for (StreamInfo stream : live.streams) {
                data |= stream.isDatacast();
                // ARIB caption: PES private stream with data_component_id 0x0008, or an EWBS
                // superimposed caption declared by component tag 0x38 / 0x88.
                caption |= stream.streamType == 0x06 && (stream.dataComponentId == 0x0008
                        || stream.componentTag == 0x38 || stream.componentTag == 0x88);
            }
        }
        boolean emergency = d != null && d.isEwbsActive(ewbsLocation());
        caption &= captionOverlay != null && captionOverlay.isEnabled();
        ServiceInfo display = live != null
                ? live
                : (currentChannelEntry != null ? currentChannelEntry.service : null);
        int khz = currentChannelEntry != null ? currentChannelEntry.khz : 0;
        SignalStatus signal = hub != null ? hub.signal() : null;
        long clock = d != null ? d.getClockMs() : -1L;
        String nowName = null;
        String nextName = null;
        if (currentChannelEntry != null) {
            String[] guide = epgNowNext(currentChannelEntry.service.serviceId);
            nowName = guide[0];
            nextName = guide[1];
        }
        info.bind(display, khz, signal, clock, data, caption,
                live != null && live.encrypted, emergency, nowName, nextName,
                d != null ? d.getNetworkName() : "");
        if (watchStatus != null) {
            watchStatus.bind(display, khz, signal, data, caption,
                    live != null && live.encrypted, emergency);
        }
        updateHudStatus(display, khz, signal, clock, data, caption,
                live != null && live.encrypted, emergency);
    }

    /** Landscape banner status row: UHF/MHz, TDT, chips, S/Q. */
    private void updateHudStatus(ServiceInfo service, int khz, SignalStatus signal, long clock,
                                 boolean data, boolean caption, boolean encrypted,
                                 boolean emergency) {
        if (hudStatusLeft == null) return;

        updateNetworkLine(service, hub != null ? hub.demux() : demux);

        String left = "";
        int uhf = phChannel(khz);
        if (uhf >= 0) left = "UHF " + uhf;
        if (khz > 0) {
            if (!left.isEmpty()) left += " · ";
            left += String.format(Locale.US, "%.3f MHz", khz / 1000.0);
        }
        hudStatusLeft.setText(left.isEmpty() ? "—" : left);

        if (clock <= 0) {
            hudTdt.setText("TDT --");
        } else {
            SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.US);
            TimeZone zone = TimeZone.getDefault();   // TOT is UTC: show the device's zone
            format.setTimeZone(zone);
            hudTdt.setText("TDT " + format.format(new Date(clock)) + " " + utcLabel(zone));
        }

        if (signal == null || !signal.locked) {
            hudSignal.setText("Tuning…");
        } else {
            hudSignal.setText("S " + signalPercent(signal.strengthPercent)
                    + "  Q " + signalPercent(signal.qualityPercent));
        }

        setHudChip(hudChipData, data, R.color.odtv_ok, "Data broadcast");
        setHudChip(hudChipCc, caption, R.color.odtv_ok, "Closed captions");
        setHudChip(hudChipLock, encrypted, R.color.odtv_warning, "Encrypted");
        setHudAlertChip(emergency);
    }

    /**
     * EWBS in the landscape HUD: a filled pill when live, so the label has to stay white.
     * setHudChip() would tint it the same red as the fill and the word would vanish -
     * the identical trap the portrait strip had. Background and text both key off
     * state_selected.
     */
    private void setHudAlertChip(boolean active) {
        if (hudChipEwbs == null) return;
        hudChipEwbs.setSelected(active);
        hudChipEwbs.setAlpha(active ? 1f : 0.28f);
        hudChipEwbs.setContentDescription(
                "Emergency broadcast" + (active ? " active" : " inactive"));
    }

    /** NIT name and SDT provider share one marquee line, so they never fight for width. */
    private void updateNetworkLine(ServiceInfo service, TsDemux demux) {
        if (hudTvNetworkName == null) return;
        String nit = demux != null ? demux.getNetworkName() : "";
        String provider = service != null ? service.provider : "";
        String line = "NIT : " + (nit.isEmpty() ? "—" : nit)
                + "   ·   Provider : " + (provider.isEmpty() ? "—" : provider);
        if (!line.contentEquals(hudTvNetworkName.getText())) {
            hudTvNetworkName.setText(line);
        }
        if (!hudTvNetworkName.isSelected()) hudTvNetworkName.setSelected(true);
    }

    private void setHudChip(TextView chip, boolean active, int activeColorRes, String description) {
        int color = ContextCompat.getColor(this,
                active ? activeColorRes : R.color.odtv_on_surface_variant);
        chip.setTextColor(color);
        chip.setAlpha(active ? 1f : 0.28f);
        chip.setContentDescription(description + (active ? " active" : " inactive"));
    }

    private static String signalPercent(int value) {
        return value < 0 ? "--" : value + "%";
    }

    private static int phChannel(int khz) {
        if (khz <= 0) return -1;
        int channel = 14 + Math.round((khz - 473143) / 6000.0f);
        return channel >= 14 && channel <= 51 ? channel : -1;
    }

    /** "UTC+8"/"UTC+9"/"UTC" for the device's zone: the set prints its own offset, not a fixed one. */
    private static String utcLabel(TimeZone zone) {
        int minutes = zone.getOffset(System.currentTimeMillis()) / 60000;
        if (minutes == 0) return "UTC";
        int hours = Math.abs(minutes) / 60;
        int rest = Math.abs(minutes) % 60;
        StringBuilder sb = new StringBuilder("UTC");
        sb.append(minutes > 0 ? '+' : '-').append(hours);
        if (rest != 0) sb.append(':').append(String.format(Locale.US, "%02d", rest));
        return sb.toString();
    }

    private void showCurrentChannel() {
        if (currentChannelEntry == null) {
            if (tvCurrentChannel != null) tvCurrentChannel.setText("No channel tuned");
            if (watchInfo != null) watchInfo.hide();
            hideTuningCard();
            updateChannelSelection();
            updateWatchInfo(hub != null ? hub.demux() : demux);
            return;
        }
        ServiceInfo svc = currentChannelEntry.service;
        if (tvCurrentChannel != null) {
            tvCurrentChannel.setText(svc.virtualNumber + "  "
                    + ph.opendtv.ui.ChannelAdapter.displayName(svc));
        }
        updateChannelSelection();
        if (sidePanel != null) {
            // Landscape/tablet: the original banner is the passive surface.
            // The card is on demand until its landscape button is placed.
            if (watchInfo != null) watchInfo.hide();
            showInfoBanner();
            updateWatchInfo(hub != null ? hub.demux() : demux);
        } else {
            // Portrait: the strip is the passive surface; the card is on demand.
            if (watchInfo != null) watchInfo.hide();
            updateWatchInfo(hub != null ? hub.demux() : demux);
        }
    }

    private void toggleWatchInfo() {
        if (watchInfo == null) return;
        if (watchInfo.isShowing()) watchInfo.hide();
        else showWatchInfo();
    }

    private void showWatchInfo() {
        if (watchInfo == null) return;
        hideInfoBanner();
        updateWatchInfo(hub != null ? hub.demux() : demux);
        watchInfo.showCompact();
    }

    private void updateChannelSelection() {
        RecyclerView.Adapter<?> adapter = recyclerView != null ? recyclerView.getAdapter() : null;
        if (adapter instanceof ph.opendtv.ui.ChannelAdapter) {
            ((ph.opendtv.ui.ChannelAdapter) adapter).setActiveService
                    (currentChannelEntry != null ? currentChannelEntry.service : null);
        }
    }

    /** Finds the tuner, gets USB permission, takes ownership, then starts the (last) channel automatically. */
    private void initTuner() {
        if (initializing) return;
        if (!hub.isTunerPresent()) {
            tunerReady = false;
            showNoTunerCard();
            return; // onResume / USB-attach will retry
        }
        initializing = true;
        new Thread(() -> {
            boolean ok = hub.ensurePermission(60_000);
            if (ok) hub.acquire(MainActivity.this);
            tunerReady = ok;
            initializing = false;
            ui.post(() -> {
                if (isFinishing()) return;
                // The init finished after the Analyzer had already been asked for: belt and braces.
                // Tuning here would re-open the dongle underneath the Analyzer (two native devices).
                if (handingOver) return;
                if (!ok) {
                    Toast.makeText(MainActivity.this, "USB permission needed", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (debugTuneKhz > 0) {
                    debugTune(debugTuneKhz);
                } else if (currentChannelEntry != null && !scanning) {
                    tuneAndPlay(currentChannelEntry);
                }
            });
        }, "InitTuner").start();
    }

    private void debugTune(final int khz) {
        worker.execute(() -> {
            boolean locked = hub.ensureTuned(khz, () -> false);
            Log.i(TAG, "debug tune khz=" + khz + " locked=" + locked);
            ui.postDelayed(() -> {
                SignalStatus s = hub.signal();
                int services = hub.demux().getServices().size();
                Log.i(TAG, "debug tune khz=" + khz + " signal="
                        + (s == null ? "null" : (s.locked + " " + s.dbm + "dBm snr=" + s.snrDb))
                        + " services=" + services);
                for (ServiceInfo svc : hub.demux().getServices()) {
                    Log.i(TAG, "debug service sid=" + svc.serviceId + " name=" + svc.name
                            + " pmt=0x" + Integer.toHexString(svc.pmtPid));
                }
            }, 2500);
        });
    }

    /** Rebuilds the Settings list without losing the scroll position or the focused row. */
    private void refreshSettings() {
        if (currentTab != 3) return;
        RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
        int focusPos = -1;
        if (lm instanceof LinearLayoutManager) {
            View focused = lm.getFocusedChild();
            if (focused != null) focusPos = ((LinearLayoutManager) lm).getPosition(focused);
        }
        android.os.Parcelable scroll = lm != null ? lm.onSaveInstanceState() : null;
        updateContentAdapter();
        if (lm != null && scroll != null) lm.onRestoreInstanceState(scroll);
        if (focusPos >= 0) {
            final int pos = focusPos;
            recyclerView.post(() -> {
                if (recyclerView.getLayoutManager() == null) return;
                View v = recyclerView.getLayoutManager().findViewByPosition(pos);
                if (v != null) v.requestFocus();
            });
        }
    }

    /**
     * The new tab's list slides in from the side the swipe came from, fading up as it
     * lands. The analyzer's tabs move the same way, so the two screens feel alike.
     *
     * @param dir -1 arrives from the left, +1 from the right
     */
    private void animateContent(int dir) {
        if (listContent == null || dir == 0) return;
        float shift = Math.min(64 * getResources().getDisplayMetrics().density,
                getResources().getDisplayMetrics().widthPixels * 0.14f);
        listContent.animate().cancel();
        listContent.setAlpha(0f);
        listContent.setTranslationX(dir * shift);
        listContent.animate().alpha(1f).translationX(0f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void updateContentAdapter() {
        movingPosition = -1;
        // Only the Favorites tab can be reordered; every other tab must let go of the drag.
        detachFavoritesDrag();
        // The BML pad belongs to the channel list: it would float over nothing on EPG
        // and Favorites, so it goes away with the tab.
        if (remotePanel != null && currentTab != 0 && remotePanelOpen) setRemotePanelOpen(false);
        if (currentTab == 1) {
            // EPG tab: start at "now". EIT carries a whole table (often with
            // yesterday's entries); the guide only shows what is still running
            // or upcoming, oldest first, so the current programme is the top row.
            ServiceInfo guideService = currentChannelEntry != null ? currentChannelEntry.service : null;
            long clock = demux.getClockMs();
            long now = clock > 0 ? clock : System.currentTimeMillis();
            List<EpgEvent> events = new ArrayList<>();
            if (guideService != null) {
                for (EpgEvent event : demux.getEvents(guideService.serviceId)) {
                    if (event.startMs > 0 && event.endMs() > now) events.add(event);
                }
                events.sort((a, b) -> Long.compare(a.startMs, b.startMs));
            }
            recyclerView.setAdapter(new ph.opendtv.ui.EpgAdapter(this, events, clock));
            if (guideService == null) {
                setListChrome(0, "", "", R.drawable.ic_schedule,
                        "No channel tuned", "Choose a channel to see its program guide.",
                        "Open Channels", 0);
            } else {
                int programs = events.size();
                setListChrome(programs,
                        ph.opendtv.ui.ChannelAdapter.displayName(guideService).toUpperCase(Locale.US),
                        programs == 1 ? "1 PROGRAM" : programs + " PROGRAMS",
                        R.drawable.ic_schedule,
                        "No program guide yet",
                        "Tune a channel and wait for guide data. Some channels don't broadcast an EPG.",
                        "Open Channels", 0);
            }
        } else if (currentTab == 2) {
            // Favorites tab
            List<ServiceInfo> favServices = new ArrayList<>();
            List<ChannelEntry> favEntries = new ArrayList<>();
            for (ChannelEntry ce : allChannels) {
                if (favorites.contains(ph.opendtv.ui.ChannelAdapter.favoriteKey(ce.service))) {
                    favServices.add(ce.service);
                    favEntries.add(ce);
                }
            }
            favServices.sort((a, b) -> {
                int ia = favoriteOrder.indexOf(ph.opendtv.ui.ChannelAdapter.favoriteKey(a));
                int ib = favoriteOrder.indexOf(ph.opendtv.ui.ChannelAdapter.favoriteKey(b));
                if (ia < 0) ia = Integer.MAX_VALUE;
                if (ib < 0) ib = Integer.MAX_VALUE;
                if (ia != ib) return Integer.compare(ia, ib);
                return Double.compare(virtualKey(a.virtualNumber), virtualKey(b.virtualNumber));
            });
            favoritesAdapter = new ph.opendtv.ui.ChannelAdapter(this, favServices, favorites,
                    new ph.opendtv.ui.ChannelAdapter.OnChannelClickListener() {
                @Override
                public void onChannelClick(ServiceInfo service) {
                    for (ChannelEntry ce : favEntries) {
                        if (ce.service == service) {
                            selectAndPlayChannel(ce);
                            return;
                        }
                    }
                    for (ChannelEntry ce : favEntries) {
                        if (ce.service.serviceId == service.serviceId) {
                            selectAndPlayChannel(ce);
                            return;
                        }
                    }
                }
                @Override
                public void onFavoriteToggle(ServiceInfo service, boolean isFavorite) {
                    String key = ph.opendtv.ui.ChannelAdapter.favoriteKey(service);
                    if (isFavorite) {
                        if (!favoriteOrder.contains(key)) favoriteOrder.add(key);
                    } else {
                        favoriteOrder.remove(key);
                    }
                    saveFavorites();
                    saveFavoriteOrder();
                    updateContentAdapter();
                    Toast.makeText(MainActivity.this,
                            (isFavorite ? "Added to Favorites: " : "Removed from Favorites: ") +
                                    ph.opendtv.ui.ChannelAdapter.displayName(service),
                            Toast.LENGTH_SHORT).show();
                }
            }, (position, channel) -> enterMoveMode(position));
            recyclerView.setAdapter(favoritesAdapter);
            attachFavoritesDrag();
            updateChannelSelection();
            setListChrome(favServices.size(), "FAVORITES", String.valueOf(favServices.size()),
                    R.drawable.ic_favorite_border,
                    "No favorites yet", "Press the star on a channel to add it.",
                    "Open Channels", 0);
        } else if (currentTab == 3) {
            // Settings tab: consumer sections, technical tools last.
            List<ph.opendtv.ui.SettingAdapter.SettingItem> items = new ArrayList<>();

            items.add(ph.opendtv.ui.SettingAdapter.SettingItem.header("CHANNELS & ALERTS"));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Auto Channel Scan",
                    "Perform full UHF band scan for channels",
                    R.drawable.ic_scan,
                    this::confirmFullScan));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Manual Frequency Scan",
                    "Scan specific UHF channel frequency with signal meter",
                    R.drawable.ic_tune,
                    this::showManualScanDialog));
            int location = ewbsLocation();
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "EWBS Location",
                    location == 0 ? "Show all alerts · tap to choose a location"
                            : EwbsAreaTable.name(location) + " · tap to change",
                    R.drawable.ic_ewbs_alert,
                    this::showEwbsLocationDialog));

            items.add(ph.opendtv.ui.SettingAdapter.SettingItem.header("PICTURE & SOUND"));
            int aspect = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_ASPECT, 0);
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Video Aspect Ratio",
                    aspectModeName(aspect) + " · tap to change",
                    android.R.drawable.ic_menu_crop,
                    this::showAspectRatioDialog));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Audio Language",
                    audioLanguageSummary() + " · choose among the channel's audio tracks",
                    R.drawable.ic_audio,
                    this::showAudioLanguageDialog));
            // The decoder was an overflow item with a clipped label ("Hardware decoder (off =
            // FF.."); it is a picture setting, so it sits last here: on = MediaCodec, off =
            // FFmpeg, one line and one gesture instead of a sentence in a menu.
            items.add(ph.opendtv.ui.SettingAdapter.SettingItem.switchItem(
                    "Hardware decoder",
                    "On: the device's MediaCodec · off: FFmpeg software decoding",
                    R.drawable.ic_audio,
                    () -> getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HW_DECODER, false),
                    this::setHardwareDecoder));

            items.add(ph.opendtv.ui.SettingAdapter.SettingItem.header("CAPTIONS"));
            items.add(ph.opendtv.ui.SettingAdapter.SettingItem.switchItem(
                    "Closed Captions (CC)",
                    "Shown when the channel broadcasts captions",
                    R.drawable.ic_captions,
                    () -> captionOverlay != null && captionOverlay.isEnabled(),
                    this::setCaptionsEnabled));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Caption Display",
                    captionStyleName(captionStyle()) + " · tap to change",
                    R.drawable.ic_captions,
                    this::showCaptionDisplayDialog));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Caption Language",
                    captionLanguageName(captionLanguage()) + " · tap to change",
                    R.drawable.ic_captions,
                    this::showCaptionLanguageDialog));

            items.add(ph.opendtv.ui.SettingAdapter.SettingItem.header("ADVANCED"));
            boolean isRecording = currentPacketSink != null && currentPacketSink.isRecording();
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    isRecording ? "Stop Channel Recording" : "Record Channel",
                    isRecording ? "Stop saving transport stream to storage" : "Save TS stream to OpenDTV/Record folder",
                    R.drawable.ic_more_vert,
                    this::toggleChannelRecording));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Signal & TS Analyzer",
                    "Inspect bitrates, PIDs, tables, and raw packets",
                    R.drawable.ic_analytics,
                    this::openAnalyzer));

            items.add(ph.opendtv.ui.SettingAdapter.SettingItem.header("ABOUT"));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    getString(R.string.about_title, getString(R.string.app_name)),
                    aboutSummary(),
                    R.drawable.ic_info,
                    this::showAboutDialog));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Open source licenses",
                    "The licences of everything this app is built from",
                    R.drawable.ic_check,
                    () -> startActivity(new Intent(this, LicensesActivity.class))));
            // No Developers row here, by the owner's decision: the About art is the only door,
            // always. See showAboutDialog.
            recyclerView.setAdapter(new ph.opendtv.ui.SettingAdapter(this, items));
            hideListChrome();
        } else {
            // Channels tab: sorted by virtual number (the scan console keeps discovery order).
            List<ServiceInfo> services = new ArrayList<>();
            for (ChannelEntry ce : allChannels) services.add(ce.service);
            services.sort((a, b) -> Double.compare(
                    virtualKey(a.virtualNumber), virtualKey(b.virtualNumber)));
            recyclerView.setAdapter(new ph.opendtv.ui.ChannelAdapter(this, services, favorites, new ph.opendtv.ui.ChannelAdapter.OnChannelClickListener() {
                @Override
                public void onChannelClick(ServiceInfo service) {
                    // Match the exact entry; SID alone can exist on several muxes.
                    for (ChannelEntry ce : allChannels) {
                        if (ce.service == service) {
                            selectAndPlayChannel(ce);
                            return;
                        }
                    }
                    for (ChannelEntry ce : allChannels) {
                        if (ce.service.serviceId == service.serviceId) {
                            selectAndPlayChannel(ce);
                            return;
                        }
                    }
                }
                @Override
                public void onFavoriteToggle(ServiceInfo service, boolean isFavorite) {
                    String key = ph.opendtv.ui.ChannelAdapter.favoriteKey(service);
                    if (isFavorite) {
                        if (!favoriteOrder.contains(key)) favoriteOrder.add(key);
                    } else {
                        favoriteOrder.remove(key);
                    }
                    saveFavorites();
                    saveFavoriteOrder();
                    updateContentAdapter();
                    Toast.makeText(MainActivity.this,
                            (isFavorite ? "Added to Favorites: " : "Removed from Favorites: ") +
                                    ph.opendtv.ui.ChannelAdapter.displayName(service),
                            Toast.LENGTH_SHORT).show();
                }
            }));
            updateChannelSelection();
            setListChrome(services.size(), "CHANNELS", String.valueOf(services.size()),
                    R.drawable.ic_scan,
                    "No channels yet", "Open Settings and run Auto Channel Scan.",
                    "Open Settings", 3);
        }
    }

    /**
     * Footer + empty state for the browse surfaces. An empty list shows the
     * empty block instead of the footer, so the title is never doubled. The
     * action is a destination (a tab), never a scan.
     */
    private void setListChrome(int count, String label, String countText, int iconRes,
                               String title, String body, String actionLabel, int actionTab) {
        boolean empty = count <= 0;
        if (listFooter != null) {
            listFooter.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (listFooterLabel != null) listFooterLabel.setText(label);
        if (listFooterCount != null) listFooterCount.setText(countText);
        if (emptyState != null) {
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (emptyAction != null) {
            boolean hasAction = empty && actionLabel != null && !actionLabel.isEmpty() && actionTab >= 0;
            emptyAction.setVisibility(hasAction ? View.VISIBLE : View.GONE);
            if (hasAction) {
                emptyAction.setText(actionLabel);
                emptyAction.setOnClickListener(v -> {
                    if (tabLayout == null) return;
                    TabLayout.Tab tab = tabLayout.getTabAt(actionTab);
                    if (tab != null && !tab.isSelected()) tab.select();
                });
            }
        }
        if (empty) {
            if (emptyIcon != null) emptyIcon.setImageResource(iconRes);
            if (emptyTitle != null) emptyTitle.setText(title);
            if (emptyBody != null) emptyBody.setText(body);
        }
    }

    /** Settings has no list footer and no empty state. */
    private void hideListChrome() {
        if (listFooter != null) listFooter.setVisibility(View.GONE);
        if (emptyState != null) emptyState.setVisibility(View.GONE);
    }

    private void selectAndPlayChannel(ChannelEntry entry) {
        if (bml != null) bml.close();        // the cached page belongs to the old channel
        setRemotePanelOpen(false);           // and so did the pad that drove it
        currentChannelEntry = entry;
        saveLastChannel(entry);
        showCurrentChannel();
        if (tunerReady) {
            tuneAndPlay(entry);
        } else if (!hub.isTunerPresent()) {
            Toast.makeText(this, "Tuner not found · plug in the USB dongle", Toast.LENGTH_LONG).show();
        } else {
            initTuner(); // plays currentChannelEntry once ready
        }
    }

    private void tuneAndPlay(final ChannelEntry entry) {
        final int g = playGen.incrementAndGet();
        videoRendered = false;
        ui.removeCallbacks(showTuningCardRunnable);
        ui.removeCallbacks(showNoSignalCardRunnable);
        ui.postDelayed(showTuningCardRunnable, 500);
        ui.postDelayed(showNoSignalCardRunnable, 7500);
        Log.i(TAG, "play sid=" + entry.service.serviceId + " name=" + entry.service.name
                + " khz=" + entry.khz);
        Runnable task = () -> {
            if (g != playGen.get()) return;   // superseded while queued
            if (!hub.isTunerPresent()) {
                tunerReady = false;
                return;
            }
            hub.acquire(MainActivity.this);
            ui.post(() -> {
                if (g != playGen.get()) return;
                hub.detachSink(MainActivity.this);
                player.stop();
                player.clearMediaItems();
                pipe.clear();
            });
            boolean locked = hub.ensureTuned(entry.khz, () -> g != playGen.get());
            if (g != playGen.get()) return;
            if (!locked) {
                Log.w(TAG, "play sid=" + entry.service.serviceId + " name=" + entry.service.name
                        + " -> NO LOCK at " + entry.khz);
                ui.post(() -> Toast.makeText(MainActivity.this,
                        "Could not lock " + (entry.khz / 1000.0) + " MHz", Toast.LENGTH_SHORT).show());
                return;
            }
            // Saved channels are stubs: take the live service (PMT PID, streams) from the demux.
            final ServiceInfo live = waitForService(entry.service.serviceId, g);
            if (live == null) {
                Log.w(TAG, "play sid=" + entry.service.serviceId + " name=" + entry.service.name
                        + " -> NOT FOUND after lock");
                if (g == playGen.get())
                    ui.post(() -> Toast.makeText(MainActivity.this, "Channel not found on this frequency", Toast.LENGTH_SHORT).show());
                return;
            }
            StringBuilder streams = new StringBuilder();
            for (StreamInfo st : live.streams) {
                if (streams.length() > 0) streams.append(", ");
                streams.append(String.format(Locale.US, "pid=0x%04X type=0x%02X comp=0x%04X tag=0x%02X",
                        st.pid, st.streamType, st.dataComponentId, st.componentTag));
            }
            Log.i(TAG, "play sid=" + entry.service.serviceId + " name=" + entry.service.name
                    + " -> LOCKED streams=" + streams);
            final TsDemux d = hub.demux();
            ui.post(() -> {
                if (g != playGen.get() || isFinishing()) return;
                demux = d;
                pipe.clear();
                if (currentPacketSink != null && currentPacketSink.isRecording()) {
                    currentPacketSink.stopRecord();
                    Toast.makeText(MainActivity.this, "Recording stopped · channel changed", Toast.LENGTH_SHORT).show();
                }
                currentPacketSink = new RecordingPacketSink(pipe, d.getTsId(), live);
                updateRecordTally();
                resetCaptions();
                bindCaptionSink();
                applyAudioLanguagePref();
                d.setPacketSink(currentPacketSink);

                MediaSource ms = mediaSourceFactory.createMediaSource(MediaItem.fromUri("tspipe://live"));
                player.setMediaSource(ms);
                player.prepare();
                player.setPlayWhenReady(true);
                armBufferingWatchdog(entry, g);
                if (currentTab == 1) updateContentAdapter();
                showInfoBanner();
            });
        };
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // activity is being destroyed
        }
    }

    /** If nothing is rendered 12 s after prepare (endless "loading"), retry or fall back to software. */
    private void armBufferingWatchdog(final ChannelEntry entry, final int g) {
        ui.postDelayed(() -> {
            if (g != playGen.get() || isFinishing() || scanning || currentChannelEntry != entry) return;
            if (player.getPlaybackState() == Player.STATE_READY) {
                // ALLTV HD (and some other muxes) feeds frames to the Qualcomm AVC
                // decoder but it never renders one. FFmpeg handles the same stream.
                if (!videoRendered && hasVideoTrack()
                        && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HW_DECODER, false)) {
                    fallbackToSoftwareDecoder();
                }
                return;
            }
            Log.w(TAG, "still buffering after 12 s (state " + player.getPlaybackState() + "), retry " + retries);
            // Giving up says nothing here: the card over the video has already escalated to its
            // own sentence, and "check the signal" is the wrong advice when the mux is locked.
            if (retries++ < MAX_RETRIES) tuneAndPlay(entry);
        }, 12_000);
    }

    private boolean hasVideoTrack() {
        for (androidx.media3.common.Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO && group.length > 0) return true;
        }
        return false;
    }

    private void fallbackToSoftwareDecoder() {
        if (softwareFallbackTried || isFinishing()) return;
        softwareFallbackTried = true;
        Log.w(TAG, "hardware decoder rendered no frame; switching to FFmpeg");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_HW_DECODER, false).apply();
        Toast.makeText(this, "Video stalled · switched to software decoding", Toast.LENGTH_LONG).show();
        recreate();
    }

    /** Waits up to 12 s for PAT/PMT of the wanted service to be parsed. */
    private ServiceInfo waitForService(int sid, int g) {
        long start = System.currentTimeMillis();
        while (g == playGen.get() && System.currentTimeMillis() - start < 12_000) {
            for (ServiceInfo s : hub.demux().getServices()) {
                if (s.serviceId == sid && s.pmtSeen && s.hasVideo()) return s;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { return null; }   // poll fast: PMT usually arrives within ~100 ms
        }
        return null;
    }

    private void updateSideMenuButtonPosition() {
        if (btnOpenSidePanel == null) return;
        if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
            btnOpenSidePanel.setVisibility(View.VISIBLE);
            btnOpenSidePanel.setImageResource(R.drawable.ic_chevron_right);
            int sideWidthPx = sidePanel.getWidth();
            if (sideWidthPx <= 0) {
                float density = getResources().getDisplayMetrics().density;
                sideWidthPx = (int) ((getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 420 : 380) * density);
            }
            int marginEndPx = sideWidthPx + (int) (18 * getResources().getDisplayMetrics().density);
            if (btnOpenSidePanel.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) btnOpenSidePanel.getLayoutParams();
                params.setMarginEnd(marginEndPx);
                btnOpenSidePanel.setLayoutParams(params);
            }
        } else {
            btnOpenSidePanel.setImageResource(R.drawable.ic_chevron_left);
            int marginEndPx = (int) (8 * getResources().getDisplayMetrics().density);
            if (btnOpenSidePanel.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) btnOpenSidePanel.getLayoutParams();
                params.setMarginEnd(marginEndPx);
                btnOpenSidePanel.setLayoutParams(params);
            }
            if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                btnOpenSidePanel.setVisibility(View.VISIBLE);
            } else {
                btnOpenSidePanel.setVisibility(View.GONE);
            }
        }
    }

    /** A+C is modal in landscape: its X is the only visible dismiss control. */
    private void updateApluscChrome(boolean showing) {
        if (btnOpenSidePanel == null) return;
        if (showing) {
            btnOpenSidePanel.setVisibility(View.GONE);
        } else {
            updateSideMenuButtonPosition();
        }
    }

    private void toggleSidePanel() {
        if (sidePanel == null) return;
        if (sidePanel.getVisibility() == View.VISIBLE) {
            hideSidePanel();
        } else {
            showSidePanel();
        }
    }

    private void showSidePanel() {
        if (watchInfo != null && watchInfo.isShowing()) return;   // A+C is modal
        if (watchInfo != null) watchInfo.hide();
        if (sidePanel != null) {
            sidePanel.setVisibility(View.VISIBLE);
            // On a TV the strip is the entry point: LEFT/RIGHT moves, OK selects,
            // DOWN drops into the list. In touch mode this request is a no-op.
            focusSelectedTab();
            schedulePanelAutoHide();
        }
        updateSideMenuButtonPosition();
        updateTuningInset();
        showInfoBanner();
    }

    /**
     * The panel is drawn over the window and the centre card ("No Signal", "Scrambled") is
     * centred on the whole window, so it used to run under the panel and the chevron sat on
     * top of the sentence. Push the card's centre into the free area left of the panel for
     * as long as the panel is up.
     */
    private void updateTuningInset() {
        if (tuning == null || sidePanel == null) return;
        int inset = 0;
        if (sidePanel.getVisibility() == View.VISIBLE) {
            ViewGroup.LayoutParams sp = sidePanel.getLayoutParams();
            if (sp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) sp;
                inset = m.width > 0 ? m.width + m.getMarginEnd() : 0;
            }
        }
        // A translation, not a margin: the overlay is centred by its own layout and a
        // marginEnd on it was ignored, while a translation survives the card being
        // hidden and shown again.
        tuning.setTranslationX(-inset / 2f);
    }

    /** Gives the remote a visible anchor on the tab strip; a no-op in touch mode. */
    private void focusSelectedTab() {
        if (tabLayout == null) return;
        TabLayout.Tab tab = tabLayout.getTabAt(currentTab);
        View tabView = tab != null ? tab.view : null;
        if (tabView != null) tabView.requestFocus();
        else tabLayout.requestFocus();
    }

    /** GUIDE key: the program guide, with the side panel opened in landscape. */
    private void showGuideTab() {
        if (sidePanel != null) showSidePanel();
        if (tabLayout == null) return;
        TabLayout.Tab tab = tabLayout.getTabAt(1);
        if (tab != null && !tab.isSelected()) tab.select();
    }

    private void hideSidePanel() {
        if (sidePanel != null) {
            sidePanel.setVisibility(View.GONE);
            ui.removeCallbacks(hideSidePanelRunnable);
        }
        updateSideMenuButtonPosition();
        updateTuningInset();
    }

    /** The 10 s panel timeout. A dialog lives in its own window, so the panel
     *  loses window focus while one is up: hold the timeout until it comes back
     *  rather than closing the panel behind the dialog. */
    private void onPanelAutoHideTimeout() {
        if (!isFinishing() && !hasWindowFocus()) {
            ui.postDelayed(hideSidePanelRunnable, 10000);
            return;
        }
        hideSidePanel();
    }

    private void schedulePanelAutoHide() {
        ui.removeCallbacks(hideSidePanelRunnable);
        if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
            ui.postDelayed(hideSidePanelRunnable, 10000);
        }
    }

    private void showInfoBanner() {
        showInfoBanner(6000);
    }

    private void showInfoBanner(int durationMs) {
        if (watchInfo != null && watchInfo.isShowing()) return;
        ui.removeCallbacks(hideHudRunnable);
        if (infoBanner != null && currentChannelEntry != null) {
            ServiceInfo svc = currentChannelEntry.service;
            if (hudTvVirtual != null) hudTvVirtual.setText(svc.virtualNumber.isEmpty() ? String.valueOf(svc.serviceId) : svc.virtualNumber);
            if (hudTvChannelName != null) {
                hudTvChannelName.setText(ph.opendtv.ui.ChannelAdapter.displayName(svc));
            }
            updateNetworkLine(svc, hub != null ? hub.demux() : demux);

            if (hudIvLogo != null) {
                android.graphics.drawable.Drawable logo = ph.opendtv.ui.LogoHelper.getLogo(this, svc.name, svc.provider);
                if (logo != null) {
                    hudIvLogo.setImageDrawable(logo);
                    hudIvLogo.setVisibility(View.VISIBLE);
                } else {
                    hudIvLogo.setVisibility(View.GONE);
                }
            }

            updateEpgBannerInfo(svc.serviceId);

            infoBanner.setVisibility(View.VISIBLE);
            updateSideMenuButtonPosition();
            updateBmlButton();

            if (durationMs > 0) {
                ui.postDelayed(hideHudRunnable, durationMs);
            }
        }
    }

    private void updateEpgBannerInfo(int serviceId) {
        if (hudTvNowEvent == null || hudTvNextEvent == null) return;
        String[] guide = epgNowNext(serviceId);
        hudTvNowEvent.setText("Now: " + (guide[0] != null ? guide[0] : "No program info"));
        hudTvNextEvent.setText("Next: " + (guide[1] != null ? guide[1] : "No program info"));
    }

    /** {now, next} programme names, or nulls when the EIT has no usable entry. */
    private String[] epgNowNext(int serviceId) {
        TsDemux source = hub != null ? hub.demux() : demux;
        if (source == null) return new String[] {null, null};
        List<EpgEvent> events = source.getEvents(serviceId);
        long nowMs = source.getClockMs() > 0 ? source.getClockMs() : System.currentTimeMillis();

        EpgEvent nowEvent = null;
        EpgEvent nextEvent = null;
        if (events != null) {
            for (EpgEvent ev : events) {
                if (ev.startMs <= nowMs && ev.endMs() > nowMs) {
                    nowEvent = ev;
                } else if (ev.startMs > nowMs) {
                    if (nextEvent == null || ev.startMs < nextEvent.startMs) {
                        nextEvent = ev;
                    }
                }
            }
        }
        return new String[] {
                nowEvent != null && !nowEvent.name.isEmpty() ? nowEvent.name : null,
                nextEvent != null && !nextEvent.name.isEmpty() ? nextEvent.name : null
        };
    }

    private void hideInfoBanner() {
        if (infoBanner != null) {
            infoBanner.setVisibility(View.GONE);
        }
        updateSideMenuButtonPosition();
        updateBmlButton();
    }

    private void tuneNextChannel() {
        if (allChannels.isEmpty()) return;
        int currentIdx = -1;
        if (currentChannelEntry != null) {
            for (int i = 0; i < allChannels.size(); i++) {
                if (allChannels.get(i).service == currentChannelEntry.service) {
                    currentIdx = i;
                    break;
                }
            }
        }
        int nextIdx = (currentIdx + 1) % allChannels.size();
        selectAndPlayChannel(allChannels.get(nextIdx));
    }

    private void tunePreviousChannel() {
        if (allChannels.isEmpty()) return;
        int currentIdx = -1;
        if (currentChannelEntry != null) {
            for (int i = 0; i < allChannels.size(); i++) {
                if (allChannels.get(i).service == currentChannelEntry.service) {
                    currentIdx = i;
                    break;
                }
            }
        }
        int prevIdx = (currentIdx - 1 + allChannels.size()) % allChannels.size();
        selectAndPlayChannel(allChannels.get(prevIdx));
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if ((bml == null || !bml.isVisible()) && watchInfo != null) {
            if (keyCode == android.view.KeyEvent.KEYCODE_INFO) {
                if (watchInfo.isShowing()) {
                    watchInfo.toggleDetails();
                } else {
                    showWatchInfo();
                }
                return true;
            }
            if (watchInfo.isShowing()) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    watchInfo.hide();
                    return true;
                }
                boolean center = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
                if (center && (sidePanel == null || sidePanel.getVisibility() != View.VISIBLE)) {
                    watchInfo.toggleDetails();
                    return true;
                }
            }
        }
        if (sidePanel != null) { // Landscape mode
            schedulePanelAutoHide();
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    if (sidePanel.getVisibility() != View.VISIBLE) {
                        showSidePanel();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (sidePanel.getVisibility() != View.VISIBLE) {
                        showSidePanel();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                case android.view.KeyEvent.KEYCODE_CHANNEL_UP:
                    if (sidePanel.getVisibility() != View.VISIBLE) {
                        tuneNextChannel();
                        return true;
                    } else {
                        // From the first row, UP climbs back to the tab strip.
                        View row = recyclerView != null ? recyclerView.getFocusedChild() : null;
                        if (tabLayout != null && row != null
                                && recyclerView.getChildAdapterPosition(row) == 0) {
                            tabLayout.requestFocus();
                            return true;
                        }
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                case android.view.KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (sidePanel.getVisibility() != View.VISIBLE) {
                        tunePreviousChannel();
                        return true;
                    } else if (tabLayout != null && tabLayout.hasFocus()) {
                        recyclerView.requestFocus();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    if (sidePanel.getVisibility() != View.VISIBLE) {
                        showInfoBanner();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_BACK:
                    if (sidePanel.getVisibility() == View.VISIBLE) {
                        hideSidePanel();
                        return true;
                    } else if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                        hideInfoBanner();
                        return true;
                    }
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private ph.opendtv.ui.ScanOverlay scanOverlay;
    private ScheduledExecutorService manualPoller;

    private final ph.opendtv.ui.ScanOverlay.Listener scanListener =
            new ph.opendtv.ui.ScanOverlay.Listener() {
                @Override
                public void onScan(int khz) {
                    stopManualMeter();
                    ui.post(() -> runManualScanForFrequency(khz));
                }

                @Override
                public void onStop() {
                    scanCancel = true;
                }

                @Override
                public void onDismissed() {
                    stopManualMeter();
                }

                @Override
                public void onFrequency(int khz) {
                    // Follow the picker so the meter shows the chosen frequency.
                    new Thread(() -> {
                        if (hub != null && hub.isTunerPresent()) hub.ensureTuned(khz, () -> false);
                    }).start();
                }
            };

    /** A full scan rebuilds the channel list, so ask before it starts. */
    private void confirmFullScan() {
        int count = allChannels.size();
        String message = "Scans UHF 14 – 51 and replaces the current channel list"
                + (count > 0 ? " (" + count + (count == 1 ? " channel)" : " channels)") : "")
                + ". It can take a few minutes.";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Full scan?")
                .setMessage(message)
                .setPositiveButton("Scan", (d, w) -> startFullScan())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showScanProgress(int max) {
        ui.post(() -> {
            if (scanOverlay == null) scanOverlay = new ph.opendtv.ui.ScanOverlay(this, scanListener);
            scanOverlay.showBand("UHF 14 – 51 · " + max + " channels", max);
        });
    }

    private void updateScanProgress(String status, int done, int count, String foundNumbers) {
        ui.post(() -> {
            if (scanOverlay != null) scanOverlay.updateBand(status, done, count, foundNumbers);
        });
    }

    private void dismissScanDialog() {
        ui.post(() -> {
            if (scanOverlay != null) scanOverlay.dismiss();
        });
    }

    /**
     * Console lines in discovery order: a "== UHF 16 · 485.143 MHz ==" header
     * each time the multiplex changes, then "7.1 | GMA" per service. No sorting
     * here - the channel list is the sorted surface.
     *
     * @param onlyKhz 0 for the whole scan, or one frequency for a manual scan
     */
    private String scanConsole(int onlyKhz) {
        StringBuilder sb = new StringBuilder();
        int lastKhz = -1;
        List<String> seen = new ArrayList<>();
        for (ChannelEntry ce : allChannels) {
            if (onlyKhz > 0 && ce.khz != onlyKhz) continue;
            if (ce.khz != lastKhz) {
                lastKhz = ce.khz;
                if (sb.length() > 0) sb.append('\n');
                sb.append("== ").append(uhfLabel(ce.khz)).append(" ==");
            }
            String v = ce.service.virtualNumber;
            String line = (v == null || v.isEmpty() ? "?" : v) + " | "
                    + ph.opendtv.ui.ChannelAdapter.displayName(ce.service);
            if (seen.contains(line)) continue;
            seen.add(line);
            sb.append('\n').append(line);
        }
        return sb.toString();
    }

    private static double virtualKey(String v) {
        try {
            String[] p = v.split("\\.");
            return Integer.parseInt(p[0]) + (p.length > 1 ? Integer.parseInt(p[1]) / 100.0 : 0);
        } catch (Exception e) {
            return 9999;
        }
    }

    /** "UHF 22 · 521.143 MHz" — the scan sheet's subtitle. */
    private static String uhfLabel(int khz) {
        int uhf = 14 + (khz - 473143) / 6000;
        return String.format(Locale.US, "UHF %d · %.3f MHz", uhf, khz / 1000.0);
    }

    private void startManualMeter() {
        stopManualMeter();
        manualPoller = Executors.newSingleThreadScheduledExecutor();
        manualPoller.scheduleWithFixedDelay(() -> {
            if (hub == null) return;
            SignalStatus st = hub.signal();
            ui.post(() -> {
                if (scanOverlay != null) scanOverlay.updateManual(st);
            });
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopManualMeter() {
        if (manualPoller != null) {
            manualPoller.shutdownNow();
            manualPoller = null;
        }
    }

    private void showManualScanDialog() {
        if (hub == null || !hub.isTunerPresent()) {
            Toast.makeText(this, "Tuner not found · plug in the USB dongle", Toast.LENGTH_LONG).show();
            return;
        }
        List<Integer> freqs = new ArrayList<>();
        for (int ch = 14; ch <= 69; ch++) freqs.add(473143 + (ch - 14) * 6000);
        int khz = currentChannelEntry != null && currentChannelEntry.khz >= 473143
                && currentChannelEntry.khz <= 803143 ? currentChannelEntry.khz : freqs.get(0);
        if (scanOverlay == null) scanOverlay = new ph.opendtv.ui.ScanOverlay(this, scanListener);
        scanOverlay.showManual(khz, freqs, "Pick a UHF channel, watch the lock, then scan it");
        startManualMeter();
    }

    private void runManualScanForFrequency(final int khz) {
        if (bml != null) bml.close();
        if (scanning) return;
        scanning = true;
        playGen.incrementAndGet();
        player.stop();
        player.clearMediaItems();

        showScanProgress(1);
        updateScanProgress(uhfLabel(khz), 0, 0, scanConsole(khz));

        new Thread(() -> {
            if (!hub.ensurePermission(60_000)) {
                scanning = false;
                dismissScanDialog();
                ui.post(() -> Toast.makeText(MainActivity.this, "USB permission needed", Toast.LENGTH_SHORT).show());
                return;
            }
            hub.acquire(MainActivity.this);
            boolean locked = hub.ensureTuned(khz, () -> false);
            if (!locked) {
                scanning = false;
                dismissScanDialog();
                ui.post(() -> Toast.makeText(MainActivity.this, "Could not lock " + (khz/1000.0) + " MHz", Toast.LENGTH_SHORT).show());
                return;
            }

            TsDemux d = hub.demux();
            long start = System.currentTimeMillis();
            long firstSeen = 0;
            while (System.currentTimeMillis() - start < 18000) {
                int n = countServices(d);
                long now = System.currentTimeMillis();
                if (n > 0 && firstSeen == 0) firstSeen = now;
                if (firstSeen != 0 && ((allNamed(d) && d.hasRemoteKey() && now - firstSeen > 1000) || now - firstSeen > 10000)) break;
                updateScanProgress(uhfLabel(khz), 0, n, scanConsole(khz));
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }

            List<ChannelEntry> newEntries = new ArrayList<>();
            for (ServiceInfo s : d.getServices()) {
                if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
                newEntries.add(new ChannelEntry(khz, s.copy()));
            }

            // Remove existing entries for this frequency only, and add new ones
            for (java.util.Iterator<ChannelEntry> it = allChannels.iterator(); it.hasNext(); ) {
                if (it.next().khz == khz) it.remove();
            }
            allChannels.addAll(newEntries);

            scanning = false;
            dismissScanDialog();
            saveChannels();
            ui.post(() -> {
                Toast.makeText(MainActivity.this, "Scan complete · " + channelCount(newEntries.size()) + " channels at " + (khz/1000.0) + " MHz", Toast.LENGTH_LONG).show();
                updateContentAdapter();
                if (!newEntries.isEmpty()) {
                    currentChannelEntry = newEntries.get(0);
                    saveLastChannel(currentChannelEntry);
                    showCurrentChannel();
                    tunerReady = true;
                    tuneAndPlay(currentChannelEntry);
                } else if (currentChannelEntry == null) {
                    currentChannelEntry = pickStartChannel();
                    showCurrentChannel();
                    if (currentChannelEntry != null) {
                        saveLastChannel(currentChannelEntry);
                        tunerReady = true;
                        tuneAndPlay(currentChannelEntry);
                    }
                }
            });
        }, "ManualScanThread").start();
    }

    private void startFullScan() {
        if (bml != null) bml.close();
        if (scanning) {
            scanCancel = true;
            return;
        }
        if (hub == null || !hub.isTunerPresent()) {
            Toast.makeText(this, "Tuner not found · plug in the USB dongle", Toast.LENGTH_LONG).show();
            return;
        }
        scanning = true;
        scanCancel = false;
        playGen.incrementAndGet();
        player.stop();
        player.clearMediaItems();
        allChannels.clear();

        List<Integer> freqs = new ArrayList<>();
        for (int f = 473143; f <= 695143; f += 6000) freqs.add(f);

        showScanProgress(freqs.size());

        new Thread(() -> {
            if (!hub.ensurePermission(60_000)) {
                scanning = false;
                dismissScanDialog();
                ui.post(() -> Toast.makeText(MainActivity.this, "USB permission needed", Toast.LENGTH_SHORT).show());
                return;
            }
            hub.acquire(MainActivity.this);
            for (int i = 0; i < freqs.size() && !scanCancel; i++) {
                int khz = freqs.get(i);
                String freqStr = uhfLabel(khz);
                updateScanProgress(freqStr, i, allChannels.size(), scanConsole(0));

                boolean locked = hub.ensureTuned(khz, () -> scanCancel);
                if (!locked || scanCancel) continue;

                TsDemux d = hub.demux();
                long start = System.currentTimeMillis();
                long firstSeen = 0;
                while (System.currentTimeMillis() - start < 22000 && !scanCancel) {
                    int n = countServices(d);
                    long now = System.currentTimeMillis();
                    if (n > 0 && firstSeen == 0) firstSeen = now;
                    // names (SDT) AND the virtual number (NIT, repeats slowly) must be in before leaving the channel
                    if (firstSeen != 0 && ((allNamed(d) && d.hasRemoteKey() && now - firstSeen > 1500) || now - firstSeen > 12000)) break;
                    updateScanProgress(freqStr, i, allChannels.size(), scanConsole(0));
                    try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                }

                for (ServiceInfo s : d.getServices()) {
                    if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
                    boolean exists = false;
                    for (ChannelEntry ce : allChannels) {
                        // The same service ID can exist on several muxes (for example
                        // INCTV SD and Congress TV HD both use SID 65). Key a saved
                        // channel by (frequency, service ID), not SID alone.
                        if (ce.khz == khz && ce.service.serviceId == s.serviceId) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        allChannels.add(new ChannelEntry(khz, s.copy()));
                    }
                }
            }
            scanning = false;
            dismissScanDialog();
            saveChannels();
            ui.post(() -> {
                Toast.makeText(MainActivity.this, "Scan complete · " + channelCount(allChannels.size()), Toast.LENGTH_LONG).show();
                updateContentAdapter();
                currentChannelEntry = pickStartChannel();
                showCurrentChannel();
                if (currentChannelEntry != null) {
                    saveLastChannel(currentChannelEntry);
                    tunerReady = true;
                    tuneAndPlay(currentChannelEntry);
                }
            });
        }, "FullScanThread").start();
    }

    private static boolean allNamed(TsDemux d) {
        boolean any = false;
        for (ServiceInfo s : d.getServices()) {
            if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
            any = true;
            if (s.name.isEmpty()) return false;
        }
        return any;
    }

    private static int countServices(TsDemux d) {
        int n = 0;
        for (ServiceInfo s : d.getServices()) if (s.pmtSeen && !s.streams.isEmpty()) n++;
        return n;
    }

    @Override
    public void onPreempted() {
        // Live Channels (or another screen) took the tuner: stop cleanly, re-acquire on next start.
        scanCancel = true;
        tunerReady = false;
        playGen.incrementAndGet();
        ui.post(() -> {
            player.stop();
            player.clearMediaItems();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanCancel = true;
        playGen.incrementAndGet();
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (currentPacketSink != null) {
            currentPacketSink.stopRecord();
        }
        if (scanOverlay != null) {
            scanOverlay.dismiss();
            scanOverlay = null;
        }
        stopManualMeter();
        if (bml != null) bml.close();
        if (captionView != null) { captionView.destroy(); captionView = null; }
        player.removeListener(playerListener);
        playerView.setPlayer(null);
        player.release();
        if (hub != null) hub.release(this);   // detaches the sink, powers the dongle down after the idle delay
        recyclerView.setAdapter(null);
        pipe.close();
    }
}