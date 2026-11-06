package com.pbcnx.opendtvmod;

import com.pbcnx.opendtvmod.analyzer.AnalyzerActivity;
import com.pbcnx.opendtvmod.bml.BmlKeyPad;
import com.pbcnx.opendtvmod.bml.CornerPad;
import com.pbcnx.opendtvmod.dev.DeveloperActions;
import com.pbcnx.opendtvmod.dev.screen.DeveloperDoor;
import com.pbcnx.opendtvmod.dev.DeveloperSurface;
import com.pbcnx.opendtvmod.firmware.FirmwarePicker;
import com.pbcnx.opendtvmod.player.AudioLanguage;
import com.pbcnx.opendtvmod.player.DecoderPrefs;
import com.pbcnx.opendtvmod.ts.caption.CaptionProbe;
import com.pbcnx.opendtvmod.ts.pipe.ServiceFilter;
import com.pbcnx.opendtvmod.tuner.ChannelFile;
import com.pbcnx.opendtvmod.tuner.ChannelStore;
import com.pbcnx.opendtvmod.tuner.MuxSweep;
import com.pbcnx.opendtvmod.ui.watch.TunerBootstrapper;
import com.pbcnx.opendtvmod.ui.ChannelAdapter;
import com.pbcnx.opendtvmod.ui.LogoHelper;
import com.pbcnx.opendtvmod.ui.watch.AnalyzerHandoff;
import com.pbcnx.opendtvmod.ui.watch.CaptionBinder;
import com.pbcnx.opendtvmod.ui.watch.ChannelSnapshot;
import com.pbcnx.opendtvmod.ui.watch.EpgAdapter;
import com.pbcnx.opendtvmod.ui.watch.EwbsLocator;
import com.pbcnx.opendtvmod.ui.watch.FavoritesReorder;
import com.pbcnx.opendtvmod.ui.watch.MainScreen;
import com.pbcnx.opendtvmod.ui.watch.ScanController;
import com.pbcnx.opendtvmod.ui.watch.SettingAdapter;
import com.pbcnx.opendtvmod.ui.watch.SettingsController;
import com.pbcnx.opendtvmod.ui.watch.StatusHud;
import com.pbcnx.opendtvmod.ui.watch.TuningCard;
import com.pbcnx.opendtvmod.ui.watch.WatchTicker;
import com.pbcnx.opendtvmod.ts.pipe.TsPipe;

import com.pbcnx.opendtvmod.tuner.ChannelEntry;

import com.pbcnx.opendtvmod.tuner.DisplayModes;


import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;

import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.bml.BmlController;
import com.pbcnx.opendtvmod.dev.BenchHooks;
import com.pbcnx.opendtvmod.firmware.FirmwareImport;
import com.pbcnx.opendtvmod.ui.EwbsAlerts;
import com.pbcnx.opendtvmod.ui.watch.WatchCardView;
import com.pbcnx.opendtvmod.ui.watch.WatchStrip;
import com.pbcnx.opendtvmod.ui.watch.ChannelFileActions;
import com.pbcnx.opendtvmod.ui.watch.WatchPlayback;
import com.pbcnx.opendtvmod.ui.watch.ChannelRecorder;
import com.pbcnx.opendtvmod.ui.watch.MainDialogs;
import com.pbcnx.opendtvmod.ui.StorageAccess;
import com.pbcnx.opendtvmod.ui.Dims;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.pbcnx.opendtvmod.player.PlayerFactory;
import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.ts.EpgEvent;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.StreamInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;
import com.pbcnx.opendtvmod.tuner.TunerHub;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity
        implements TunerHub.Owner, MainScreen, WatchPlayback.Host,
        AnalyzerHandoff.Host {

    // ---- host seams: MainScreen, WatchPlayback.Host and TunerHub.Owner --------------------------
    // Every one-line accessor lives here and nowhere else; fields start after this block.

    @Override public Handler ui() { return ui; }

    @Override public ExecutorService worker() { return worker; }

    @Override public TunerHub hub() { return hub; }

    @Override public TsPipe pipe() { return pipe; }

    @Override public ExoPlayer player() { return player; }

    @Override public void setPlayer(ExoPlayer replacement) { player = replacement; }

    @Override public @Nullable PlayerView playerView() { return playerView; }

    @Override public ChannelRecorder recorder() { return recorder; }

    @Override public @Nullable TuningCard tuning() { return tuning; }

    @Override public BmlController bml() { return bml; }

    @Override public AtomicInteger playGen() { return playGen; }

    @Override public int currentTab() { return currentTab; }

    @Override public void setCanTune(boolean ready) { setTunerCanTune(ready); }

    private void setTunerCanTune(boolean ready) { tunerLifecycle.setReady(ready); }

    @Override public @Nullable ChannelEntry currentChannel() { return currentChannelEntry; }

    @Override public void setCurrentChannel(ChannelEntry entry) { currentChannelEntry = entry; }

    @Override public boolean isScanning() { return scan.isScanning(); }

    @Override public boolean firstStart() { return firstStart; }

    @Override public void setFirstStart(boolean first) { firstStart = first; }

    @Override public Runnable showTuningCardRunnable() { return showTuningCardRunnable; }

    @Override public Runnable showNoSignalCardRunnable() { return showNoSignalCardRunnable; }

    @Override public android.content.SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    @Override public String audioLanguageKey() { return AudioLanguage.PREF; }

    // ---- WatchTicker.Host ---------------------------------------------------------------

    public boolean finishingOrDestroyed() {
        return isFinishing() || isDestroyed();
    }
    public TsDemux demux() { return hub.demux(); }
    public void sampleSourceNow(TsDemux d) { sampleSource(d); }
    public void updateEwbsAlerts(TsDemux d) { ewbsAlerts.update(d, ewbsLocation()); }
    public void updateEpgInfo() {
        if (currentChannelEntry != null) updateEpgBannerInfo(currentChannelEntry.service.serviceId);
        updateFooterClockProgress();
    }
    public long pipeDropped() { return pipe.droppedPackets(); }
    public long continuityErrors(TsDemux d) { return d.sample().ccErrors; }
    /** Debug-only: the smoke gate counts these lines to prove the tick repeats (no-op on release). */
    public void benchTick() { com.pbcnx.opendtvmod.dev.BenchHooks.tickMarker(this); }
    // ---- TunerBootstrapper.Host ---------------------------------------------------------

    public boolean initializing() { return initializing; }
    public void setInitializing(boolean value) { initializing = value; }
    public boolean handingOver() { return tunerLifecycle.isHandingOver(); }
    public boolean noTunableSource() { return sourceCannotTune(); }
    public void showNoTunerPlaceholder() { showNoTunerCard(); }
    public void playFromRunningSource() { playback.playFromRunningSource(); }
    public int debugTuneKhz() { return debugTuneKhz; }
    public void debugTuneRequest(int khz) { debugTune(khz); }
    public boolean hasChannel() { return currentChannelEntry != null; }
    public void tuneAndPlayCurrent() { playback.tuneAndPlay(currentChannelEntry); }
    public void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    public @Nullable ServiceFilter liveFilter() { return playback.liveFilter(); }

    public void imagesChanged() { firmwareImagesChanged(); }
    public void tunerNeedsInit() { initTuner(); }

    public void onVideoRect(float l, float t, float r, float b, boolean invisible) {
        bmlVideoRect(l, t, r, b, invisible);
    }
    public void onStatus(String text) { bmlStatus(text); }
    public void onFailed(String reason) { bmlFailed(reason); }
    public void onClosed() { bmlClosed(); }

    public void onBmlKey(int code) { bmlKey(code); }
    public void onData() { bmlData(); }

    public void onKey(int code) { padAribKey(code); }
    public void onExit() { padExit(); }
    @Override public void setHandingOver(boolean value) { tunerLifecycle.setHandingOver(value); }

    @Override public void startAnalyzer(int khz, int requestCode) {
        android.content.Intent i = new android.content.Intent(this,
                AnalyzerActivity.class);
        if (khz > 0) i.putExtra("tune_khz", khz);
        startActivityForResult(i, requestCode);
    }

    @Override public TunerHub.Owner owner() { return this; }

    // ---- the collaborator ports the screen implements directly -------------------------------

    @Override public android.app.Activity activity() { return this; }

    public ChannelStore scanStore() { return channelStore; }

    public ChannelStore.Storage scanPrefs() { return storePrefs(); }

    public void beginScanExclusive() {
        bml.close();
        playGen.incrementAndGet();
        player.stop();
        player.clearMediaItems();
    }

    public void channelsChanged() {
        updateContentAdapter();
        updateChannelSelection();
    }

    public void scanFinished(ChannelEntry entry) {
        if (entry == null) return;
        channelStore.saveLast(storePrefs(), entry);
        showCurrentChannel();
        setTunerCanTune(true);
        playback.tuneAndPlay(entry);
    }

    /**
     * Puts playback back on the channel the screen was showing. The manual scan's meter retunes the
     * one tuner to whatever frequency the picker highlights, so closing that sheet without scanning
     * left the player waiting on a multiplex nobody feeds (jpdcs#14: "it bugs out after changing a
     * channel"). Called by {@link ScanController} on dismiss, only when the meter actually moved.
     */
    public void resumePlayingChannel() {
        if (currentChannelEntry == null || scan.isScanning() || !canTune()) return;
        playback.resetRetries();
        playback.tuneAndPlay(currentChannelEntry);
    }

    public void benchApplyImport(ChannelFile.Result result, boolean replace) {
        channelFileActions.applyImport(result, replace);
    }

    public List<ChannelEntry> benchChannels() { return channelStore.channels(); }

    public List<String> benchFavorites() { return channelStore.order(); }

    public void benchSweep(BenchHooks.LaunchExtras extras) { applySweepHook(extras); }

    public void firmwareTunerNeedsInit() { initTuner(); }

    public void bmlVideoRect(float l, float t, float r, float b, boolean invisible) {
        bmlVideoFrac = (invisible || (l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f))
                ? null : new float[] {l, t, r, b};
        applyBmlVideoRect();
    }

    public void bmlStatus(String text) {
        // Informational only ("page loaded"): the normal case must not sound broken.
    }

    public void bmlFailed(String reason) {
        android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_SHORT).show();
    }

    public void bmlClosed() {
        bmlVideoFrac = null;
        applyBmlVideoRect();
        if (bmlKeys != null) bmlKeys.hide();
        updateBmlButton();
    }

    public void bmlKey(int code) { bml.tap(code); }

    public void bmlData() { toggleBml(); }

    public void padAribKey(int code) { padKey(code); }

    public void padExit() { closeBml(); }

    public void padData() { toggleBml(); }

    @Override public int favoriteCount() { return channelStore.favorites().size(); }

    @Override public boolean canTune() { return tunerLifecycle.canTune(); }

    @Override public void firmwareImagesChanged() { if (currentTab == MainScreen.TAB_SETTINGS) updateContentAdapter(); }

    @Override public int aspectMode() {
        return DisplayModes.aspectMode(
                getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_ASPECT, DisplayModes.ASPECT_AUTO));
    }

    @Override public void setAspectMode(int mode) { applyAspectMode(mode, false); }

    @Override public void refreshAspectMode() { applyAspectMode(aspectMode(), false); }

    @Override public Player audioPlayer() { return player; }

    @Override public @Nullable androidx.media3.common.TrackGroup autoAudioGroup() { return playback.autoAudioGroup(); }

    @Override public void saveAudioLanguage(String language) {
        prefs().edit().putString(AudioLanguage.PREF, language).apply();
    }

    @Override public void confirmFullScan() { scan.confirmFullScan(); }






    @Override public boolean decoderManual() { return DecoderPrefs.manual(this); }

    @Override public boolean decoderVideoSoftware() { return DecoderPrefs.videoSoftware(this); }

    @Override public boolean decoderAudioSoftware() { return DecoderPrefs.audioSoftware(this); }

    @Override public int currentKhz() { return currentChannelEntry != null ? Math.max(currentChannelEntry.khz, 0) : 0; }

    @Override public boolean captionsEnabled() { return captions != null && captions.enabled(); }




    @Override public boolean recording() { return recorder.isRecording(); }

    @Override public void toggleRecording() { recorder.toggle(); }


    @Override public void openLicenses() { startActivity(new Intent(this, LicensesActivity.class)); }


    @Override
    public int channelCount() {
        return channelStore.channelCount();
    }

    private static final String TAG = "OpenDTV-Main";
    /*
     * Delivered by the Developers screen's "Open Analyzer" row. The Analyzer is entered through
     * {@link #openAnalyzer()} and not by a bare startActivity from that screen, because that
     * method is the one that shuts the tuner hub down first (M5 defect 6).
     */
    /** Bench hook: open the Developers screen through the real door, so its rows can be tested. */
    static final String PREFS = App.PREFS;
    static final String PREF_ASPECT = DisplayModes.PREF_ASPECT;
    static final String PREF_EWBS_LOCATION = "ewbs_location";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TsPipe pipe = new TsPipe(4 * 1024 * 1024);   // service-filtered TS only (a few Mbit/s): ~10 s of video

    /** Location -> EWBS region code plumbing. */
    private final EwbsLocator ewbsLocator =
            new EwbsLocator(this, ui);

    private TunerHub hub;
    private UsbManager usbManager;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView tvCurrentChannel;
    private TabLayout tabLayout;
    private RecyclerView recyclerView;
    /** The recording state and the REC tally; owns the packet sink of the tuned channel. */
    private ChannelRecorder recorder;

    /** The playback state machine: attempts, decoder memory and the tuning cards. */
    private final WatchPlayback playback = new WatchPlayback(this);
        /* The dialogs and the Settings rows see the activity's state through this, and hand every change back. */

    /** The Settings-driven dialogs. */
    private final MainDialogs dialogs = new MainDialogs(this, this);

    /** What a finished firmware import hands back to the activity. */
    private FirmwareImport.Host firmwareHost;

    /** The landscape status row; MainActivity only gathers the data it renders. */
    private StatusHud statusHud;

    /** The two caption displays and the rules that pick one. */
    private CaptionBinder captions;
          // styled (WebView) caption display, null while the simple text line is selected
    static final String PREF_CC = "closed_captions";
    static final String PREF_CC_STYLE = "caption_style";        // 0 = simple text line, 1 = styled (aribb24 WebView)
    static final String PREF_CC_LANG = "caption_language";      // 1 = first (main), 2 = second
    static final String PREF_AUDIO_LANG = "audio_language";     // ISO 639 code, "" = automatic

    // Data broadcast (BML), landscape and portrait. Landscape: keys only. Portrait: floating remote button + panel.
    private BmlController bml;
    private WatchCardView watchCard;
    private WatchStrip watchStrip;
    private View btnWatchCard;
    private View btnWatchInfoLand;
    private TextView hudStatusLeft;
    private TextView hudTdt;
    private TextView hudSignal;
    private TextView hudChipData;
    private TextView hudChipCc;
    private TextView hudChipLock;
    private TextView hudChipEwbs;
    private final EwbsAlerts ewbsAlerts = new EwbsAlerts(this);
    private Runnable ewbsTick;
    private final ScanController.Host scanHost = new ScanController.Host() {
        @Override public android.app.Activity activity() { return MainActivity.this; }
        @Override public Handler ui() { return ui; }
        @Override public TunerHub hub() { return MainActivity.this.hub(); }
        @Override public TunerHub.Owner owner() { return MainActivity.this.owner(); }
        @Override public ChannelEntry currentChannel() { return MainActivity.this.currentChannel(); }
        @Override public void setCurrentChannel(ChannelEntry entry) { MainActivity.this.setCurrentChannel(entry); }
        @Override public boolean canTune() { return MainActivity.this.canTune(); }
        @Override public void setCanTune(boolean ready) { MainActivity.this.setCanTune(ready); }
        @Override public android.content.SharedPreferences prefs() { return MainActivity.this.prefs(); }
        public ChannelStore scanStore() { return MainActivity.this.scanStore(); }
        public ChannelStore.Storage scanPrefs() { return MainActivity.this.scanPrefs(); }
        public void beginScanExclusive() { MainActivity.this.beginScanExclusive(); }
        public void channelsChanged() { MainActivity.this.channelsChanged(); }
        public void scanFinished(ChannelEntry entry) { MainActivity.this.scanFinished(entry); }
        public void resumePlayingChannel() { MainActivity.this.resumePlayingChannel(); }
    };

    private final BmlController.Host bmlHost = new BmlController.Host() {
        public void onVideoRect(float l, float t, float r, float b, boolean invisible) {
            MainActivity.this.onVideoRect(l, t, r, b, invisible);
        }
        public void onStatus(String text) { MainActivity.this.onStatus(text); }
        public void onFailed(String reason) { MainActivity.this.onFailed(reason); }
        public void onClosed() { MainActivity.this.onClosed(); }
    };

    private final BmlKeyPad.Listener bmlKeyListener = new BmlKeyPad.Listener() {
        public void onBmlKey(int code) { MainActivity.this.onBmlKey(code); }
        public void onData() { MainActivity.this.onData(); }
    };

    private final CornerPad.Listener cornerListener = new CornerPad.Listener() {
        public void onKey(int aribKeyCode) { MainActivity.this.onKey(aribKeyCode); }
        public void onExit() { MainActivity.this.onExit(); }
        public void onData() { MainActivity.this.onData(); }
    };

    private final WatchTicker.Host tickerHost = new WatchTicker.Host() {
        @Override public Handler ui() { return ui; }
        public boolean finishingOrDestroyed() { return MainActivity.this.finishingOrDestroyed(); }
        public TsDemux demux() { return MainActivity.this.demux(); }
        public void sampleSourceNow(TsDemux demux) { MainActivity.this.sampleSourceNow(demux); }
        public void updateEwbsAlerts(TsDemux demux) { MainActivity.this.updateEwbsAlerts(demux); }
        public void updateInfoBanner(TsDemux demux) { MainActivity.this.updateInfoBanner(demux); }
        public void updateEpgInfo() { MainActivity.this.updateEpgInfo(); }
        public long pipeDropped() { return MainActivity.this.pipeDropped(); }
        public long continuityErrors(TsDemux demux) { return MainActivity.this.continuityErrors(demux); }
        public void benchTick() { MainActivity.this.benchTick(); }
    };

    private final CaptionBinder.Host captionsHost = new CaptionBinder.Host() {
        @Override public android.app.Activity activity() { return MainActivity.this; }
        @Override public Handler ui() { return ui; }
        @Override public TunerHub hub() { return MainActivity.this.hub(); }
        @Override public TunerHub.Owner owner() { return MainActivity.this.owner(); }
        @Override public ChannelEntry currentChannel() { return MainActivity.this.currentChannel(); }
        @Override public void setCurrentChannel(ChannelEntry entry) { MainActivity.this.setCurrentChannel(entry); }
        @Override public boolean canTune() { return MainActivity.this.canTune(); }
        @Override public void setCanTune(boolean ready) { MainActivity.this.setCanTune(ready); }
        @Override public android.content.SharedPreferences prefs() { return MainActivity.this.prefs(); }
        @Override public androidx.media3.ui.PlayerView playerView() { return MainActivity.this.playerView(); }
        @Override public com.pbcnx.opendtvmod.ts.pipe.ServiceFilter liveFilter() { return MainActivity.this.liveFilter(); }
        @Override public void refreshSettings() { MainActivity.this.refreshSettings(); }
    };

    private final TunerBootstrapper.Host bootstrapHost = new TunerBootstrapper.Host() {
        @Override public TunerHub hub() { return MainActivity.this.hub(); }
        @Override public TunerHub.Owner owner() { return MainActivity.this.owner(); }
        @Override public Handler ui() { return ui; }
        @Override public boolean canTune() { return MainActivity.this.canTune(); }
        @Override public void setCanTune(boolean value) { MainActivity.this.setCanTune(value); }
        @Override public boolean isFinishing() { return MainActivity.this.isFinishing(); }
        public boolean handingOver() { return MainActivity.this.handingOver(); }
        public boolean initializing() { return MainActivity.this.initializing(); }
        public void setInitializing(boolean value) { MainActivity.this.setInitializing(value); }
        public boolean noTunableSource() { return MainActivity.this.noTunableSource(); }
        public boolean capturePending() { return MainActivity.this.captureLaunch; }
        public void showNoTunerPlaceholder() { MainActivity.this.showNoTunerPlaceholder(); }
        public void playFromRunningSource() { MainActivity.this.playFromRunningSource(); }
        public int debugTuneKhz() { return MainActivity.this.debugTuneKhz(); }
        public void debugTuneRequest(int khz) { MainActivity.this.debugTuneRequest(khz); }
        @Override public boolean isScanning() { return MainActivity.this.isScanning(); }
        public boolean hasChannel() { return MainActivity.this.hasChannel(); }
        public void tuneAndPlayCurrent() { MainActivity.this.tuneAndPlayCurrent(); }
        public void toast(int message) { MainActivity.this.toast(getString(message)); }
    };

    private WatchTicker watchTicker;
    private FrameLayout bmlFrame;
    private BmlKeyPad bmlKeys;   // landscape: red/green/yellow/blue on demand
    private View remotePanel;              // portrait only: the BML pad, if this layout has one
    private View btnRemote;                // the row button that raises it (inverts while open)
    private boolean remotePanelOpen;
    private float[] bmlVideoFrac;          // where the page wants the video (fractions), null = full screen
    private int layoutKey = -1;            // which layout set (portrait / landscape / tablet landscape) is inflated

    // Landscape HUD & Overlay Views
    private com.pbcnx.opendtvmod.ui.watch.SidePanelChrome sidePanelChrome;

    private final com.pbcnx.opendtvmod.ui.watch.SidePanelChrome.Host sidePanelHost =
            new com.pbcnx.opendtvmod.ui.watch.SidePanelChrome.Host() {
                @Override public Handler ui() { return ui; }
                @Override public boolean isFinishing() { return MainActivity.this.isFinishing(); }
                @Override public boolean hasWindowFocus() { return MainActivity.this.hasWindowFocus(); }
                @Override public boolean watchCardShowing() { return watchCard != null && watchCard.isShowing(); }
                @Override public void hideWatchCard() { if (watchCard != null) watchCard.hide(); }
                @Override public void focusSelectedTab() { MainActivity.this.focusSelectedTab(); }
                @Override public void updateButtonPosition() { updateSideMenuButtonPosition(); }
                @Override public void showInfoBanner() { MainActivity.this.showInfoBanner(); }
            };

    private View sidePanel;
    /** The panel's own top padding, kept so the EWBS inset can be added and removed cleanly. */
    private int sidePanelBasePaddingTop = -1;
    /** Launch-time tab override from the adb extra, decoded once and consumed by bindToolbar. */
    private int launchTab = -1;
    /** Privileged adb extras, decoded once (debug builds only). */
    private BenchHooks.LaunchExtras extras;
    private com.pbcnx.opendtvmod.ui.watch.InfoBannerChrome infoBannerChrome;

    private final com.pbcnx.opendtvmod.ui.watch.InfoBannerChrome.Host infoBannerHost =
            new com.pbcnx.opendtvmod.ui.watch.InfoBannerChrome.Host() {
                @Override public Handler ui() { return ui; }
                @Override public View banner() { return infoBanner; }
                @Override public ServiceInfo channelService() {
                    return currentChannelEntry == null ? null : currentChannelEntry.service;
                }
                @Override public boolean watchCardShowing() { return watchCard != null && watchCard.isShowing(); }
                @Override public void fillContent(ServiceInfo service) { fillBannerContent(service); }
                @Override public void updateButtonPosition() { updateSideMenuButtonPosition(); }
                @Override public void updateBmlButton() { MainActivity.this.updateBmlButton(); }
                @Override public void updateInfoBanner() { MainActivity.this.updateInfoBanner(hub.demux()); }
            };

    private View infoBanner;
    private android.widget.ImageView hudIvLogo;
    private TextView hudTvVirtual;
    private TextView hudTvChannelName;
    private TextView hudTvNetworkName;
    private TextView hudTvNowEvent;
    private TextView hudTvNextEvent;
    private android.widget.ImageButton btnOpenSidePanel;

    // Browse surfaces: the shared empty state and the one per-tab footer line.
    private View emptyState;
    private TextView footerLabel;
    private TextView footerValue;
    private android.widget.ProgressBar footerEpgBar;
    private static final java.text.SimpleDateFormat CLOCK_FORMAT =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
    private android.widget.ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyBody;
    private TextView emptyAction;
    private TuningCard tuning;

    /** Long OK on a data broadcast: show the on-screen key pad; a short OK is ENTER for the page. */
    private final HeldOk keyPadOk = new HeldOk(ui,
            () -> { if (bmlKeys != null && bml.isVisible()) bmlKeys.show(); },
            () -> bml.tap(BmlController.ENTER));
    /** Long OK on the watching surface opens the data broadcast; a short OK shows the info card. */
    private final HeldOk watchingOk = new HeldOk(ui,
            () -> { if (!bml.isVisible()) toggleBml(); },
            this::handleInfoPress);
    /** Tuning card: appear only if no frame has arrived yet. */
    final Runnable showTuningCardRunnable = this::showTuningCardAndRemember;
    /** No frame after the grace period: same card becomes "No signal". */
    final Runnable showNoSignalCardRunnable = this::showNoSignalCardAndRemember;

    /**
     * The card that is on screen right now, as the runnable that put it there (null when none is
     * up). A rotation keeps the activity alive but inflates a new, hidden layout, and nothing on
     * that new view tree is ever told "Tuner not found" or "No Signal" again - so the tuning card
     * vanished on rotation and stayed gone when rotating back (owner: black screen, no text).
     *
     * <p>Ported from PBCNX/OpenDTV-MOD#1 (ODTV-50), which is this bug arriving from outside: replay
     * the remembered card after the new layout is bound instead of re-deriving the state. The
     * no-tuner replay is guarded on the dongle still being missing, so a rotation just after it is
     * plugged in cannot cancel the card the new tune has queued.</p>
     */
    private Runnable tuningCardShown;

    private void showTuningCardAndRemember() {
        hideInfoBannerForTuningCard();
        playback.showTuningCard();
        rememberTuningCard(showTuningCardRunnable);
    }

    private void showNoSignalCardAndRemember() {
        hideInfoBannerForTuningCard();
        playback.showNoSignalCard();
        rememberTuningCard(showNoSignalCardRunnable);
    }

    /**
     * Landscape only: the info banner is the bottom card and the tuning card is centred over the
     * video, so the tuning card's station/frequency line ran behind the banner and both repeated
     * the same identity (owner, 2026-11-06). The tuning card already names the station, so the
     * banner waits until the picture arrives - showCurrentChannel() brings it back.
     */
    private void hideInfoBannerForTuningCard() {
        if (sidePanel == null) return;   // portrait has no bottom banner
        if (infoBannerChrome != null) infoBannerChrome.cancel();
        hideInfoBanner();
    }

    /** A show call that decided not to show (no frame yet, page open, no channel) forgets, not lies. */
    private void rememberTuningCard(Runnable replay) {
        tuningCardShown = (tuning != null && tuning.isShowing()) ? replay : null;
    }

    // ---- source liveness ------------------------------------------------------------------------
    //
    // The screen used to decide it was live by asking for a USB device. That is a fact about the
    // bench, not about the picture: a capture fed into the hub demuxes perfectly while the UI says
    // "Tuner not found" and starts no player. Liveness is now the packet count moving - whatever
    // opened the source (dongle, capture, a session that handed one over) plays the same way, and a
    // dongle that stops producing is not "live" just because it is plugged in.
    /** The demux the sample belongs to: a new source installs a fresh one, so the counts reset. */
    private TsDemux sampledDemux;
    /** Packet count at the previous 1 Hz sample, -1 before the first one. */
    private long packetsAtSample = -1;
    /** Uptime of the last sample that saw the count rise. */
    private long lastPacketMs;

    private int currentTab = MainScreen.TAB_CHANNELS; // Channels, EPG, Favorites, Settings
    /** Which tab to come back to after a recreate (the decoder switch rebuilds the activity). */
    private static final String STATE_TAB = "odtv_tab";
    /** ...and where in that tab's list. */
    private static final String STATE_LIST = "odtv_list";
    /** Touch origin for the horizontal tab swipe on the content surface. */
    private final com.pbcnx.opendtvmod.ui.watch.TabSwipe tabSwipe = new com.pbcnx.opendtvmod.ui.watch.TabSwipe();
    /** The list box (list or empty state) that slides in when the tab changes. */
    private View listContent;
    private ChannelEntry currentChannelEntry;
    private int debugTuneKhz;
    /**
     * A debug capture launch is in flight: the file source opens a moment after onCreate. The tuner
     * must not autoplay a saved channel meanwhile - that is what mixed a tuner demux into a playing
     * capture ("Opening…" over a live file, chips following the empty mux). Cleared once the file
     * source is running; the capture hook plays it itself.
     */
    private boolean captureLaunch;
    /** The screen's tuner state: one value instead of the old tunerLifecycle.isHandingOver()/tunerLifecycle.canTune() pair. */

    /**
     * HANDING_OVER wins over READY/NOT_READY for the window where the Analyzer claims the dongle;
     * READY means "initialised once and usable"; NOT_READY forces a re-init on the next tick.
     */
    private final com.pbcnx.opendtvmod.ui.watch.TunerLifecycle tunerLifecycle = new com.pbcnx.opendtvmod.ui.watch.TunerLifecycle();
    /** Request code for the Analyzer hand-off; onActivityResult is where the tuner comes back. */
    private static final int REQ_ANALYZER = 1001;
    /* The channel-list file pickers (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT). */

    /* The tuner-firmware import picker: any file, identified by its SHA-256. */

    /* The folder picker: scans a folder for the supported vendor images. */

    /* The exported document, built when the picker opens: the file is only created on RESULT_OK. */
    /** Channel-list file flows; only the door and the result dispatch stay here. */
    private ChannelFileActions channelFileActions;
        /** Single worker for tune/play: one tune at a time, no thread churn, cancelled in onDestroy. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "PlayChannel"));
    /* Created once and reused for every channel change. */
    /** The Settings list, so a Decoder toggle can re-bind its own rows without recreating the screen. */
    private SettingAdapter settingsAdapter;
    /* Pipe drops are the jitter question in one number; logged every 5 s when they move. */
    /** Kept so the per-mux video fallback can rebuild the player without the activity. */
    private final AtomicInteger playGen = new AtomicInteger();
    /**
     * The first onStart of a launch must not start playback: onCreate already asked for it (the
     * tuner path posts it from initTuner, the source path calls playFromRunningSource), and the
     * permission check can finish before onStart runs - which is exactly how one channel used to be
     * started twice in the same millisecond. Later onStarts are the return-from-background case.
     */
    private boolean firstStart = true;
    /** Last play attempt: a second request for the same entry within a moment is that same duplicate. */
    private volatile boolean initializing;

    /* The channel list and favourites: the model and its persistence, out of the activity. */
    /** The process-wide channel list, composed by App, not by this activity. */
    private ChannelStore channelStore;

    /** The screen's adapters for the small collaborator ports. */

    /** The two scan engines and their sheet, out of the activity (see ScanController). */
    ScanController scan;

    private BenchHooks.Host benchHost;

    private FavoritesReorder favoritesReorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scan = new ScanController(scanHost);
        watchTicker = new WatchTicker(tickerHost);
        ewbsTick = watchTicker.runnable();
        benchHost = new BenchHooks.Host() {
            @Override public void benchApplyImport(ChannelFile.Result result, boolean replace) {
                MainActivity.this.benchApplyImport(result, replace);
            }
            @Override public List<ChannelEntry> benchChannels() { return channelStore.channels(); }
            @Override public List<String> benchFavorites() { return channelStore.order(); }
            @Override public void benchSweep(BenchHooks.LaunchExtras extras) { applySweepHook(extras); }
        };
        firmwareHost = new FirmwareImport.Host() {
            @Override public void imagesChanged() { firmwareImagesChanged(); }
            @Override public void tunerNeedsInit() { firmwareTunerNeedsInit(); }
        };
        extras = BenchHooks.launchExtras(getIntent());
        channelStore = App.channels(this);
        channelFileActions = new ChannelFileActions(this, channelStore, this::afterChannelImport, this::storePrefs);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // A setting that rebuilds the activity (the decoder switch) must not throw the reader
        // out of the tab they were reading it in.
        if (savedInstanceState != null) currentTab = savedInstanceState.getInt(STATE_TAB, currentTab);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        hub = App.hub(this);

        // A capture instead of air, for the watch screen too. Same contract as the analyzer:
        //
        //   adb shell am start -n <pkg>/com.pbcnx.opendtvmod.MainActivity --es ts_path /path/capture.ts
        //
        // Paced at the capture's OWN rate, measured from its PCR - TV5-BML.ts is 7.19 Mbit/s over
        // 119.1 s, i.e. 899 kB/s. Feeding it slower starves the player (at 400 kB/s it never left
        // STATE_BUFFERING), and feeding it faster makes the pipe drop newest-into-oldest
        // continuously, which is corruption rather than pacing. From here it is an ordinary source:
        // the screen plays it because packets arrive, not because a dongle answered.
        applyCaptureHook(extras);

        // The process already loaded the channel list and favourites in App.onCreate; this screen
        // only reads them (an import reloads them through ChannelFileActions).

        // Live TV buffering, tuned for a fast channel start: playback begins as soon as 25 ms of media are queued
        // (a tiny safety margin over "instantly"; the first keyframe is the real floor), keeps 2-10 s buffered, and
        // after an underrun waits 1 s so a short stall does not turn into a stutter loop. Time (not bytes) decides,
        // with a byte cap so a high-bitrate HD service cannot grow the heap.
        playback.setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 10000, 1500, 3000)
                .setTargetBufferBytes(6 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build());
        // Every bench extra for this launch, in one place (debug builds only; see BenchHooks.applyAll).
        applyBenchHooks(extras);
        // Shared player setup. Video and audio each have their own preference (see PlayerFactory).
        player = PlayerFactory.build(this, playback.loadControl(), true);
        player.addListener(playback.playerListener());
        applyAudioLanguagePref();

        // Data broadcast engine: created once, survives rotation (the page is cached, see BmlController).
        bml = new BmlController(this, null, bmlHost);

        // The REC state: the packet sink is the writer and the REC key is its tally.
        recorder = new ChannelRecorder(this, ui, () -> StorageAccess.ensure(this, () -> { if (recorder != null) recorder.toggle(); }),
                () -> currentChannelEntry != null ? currentChannelEntry.service : null,
                playback::liveFilter);

        layoutKey = layoutKeyOf(getResources().getConfiguration());
        bindLayout();
        favoritesReorder = new FavoritesReorder(recyclerView, channelStore, this::storePrefs);
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

        launchTab = extras.tab;

        currentChannelEntry = channelStore.pickStart(storePrefs());
        showCurrentChannel();
        debugTuneKhz = extras.tuneKhz;

        initTuner();

        // A cold start that carries the Developers screen's "Open Analyzer" extra: go through the
        // same door as the Settings row once this activity exists, so the hub is not left behind.
        if (extras.openAnalyzer) {
            BenchHooks.consume(getIntent());
            ui.post(this::openAnalyzer);
        }
        // Bench hook for the Developers screen itself: it is the one row whose whole job is to ask
        // the live page a question, so it has to be reachable over adb to be tested at all.
        if (extras.devOptions) {
            BenchHooks.consume(getIntent());
            // The bench is the only caller that needs to skip the About taps; the decode in
            // LaunchExtras is already inert in release builds.
            developer().unlock();
            ui.postDelayed(this::openDeveloperOptions, 1500);
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
        if (infoBannerChrome != null) infoBannerChrome.cancel();
        if (sidePanelChrome != null) sidePanelChrome.cancelAutoHide();
        if (infoBannerChrome != null) infoBannerChrome.cancel();
        // The card that was up goes with the old views; nothing asks for it again on the new tree.
        Runnable card = tuningCardShown;
        if (playerView != null) playerView.setPlayer(null);   // release the old surface before the views go
        bindLayout();
        showCurrentChannel();
        // Geometry follows the orientation (portrait card vs landscape screen): apply it to the
        // fresh player frame, then report the current video size if a channel is already playing.
        applyAspectMode(aspectMode(), false);
        if (card != null) card.run();
        // The chevron is a fresh view too: its side, icon and margin were only applied when the
        // panel toggled, so a rotation left it stale (owner: "< stuck in landscape").
        updateSideMenuButtonPosition();
    }

    /** Makes room for the EWBS strip on the side panel; its tab row is what the strip covers. */
    private void insetPanelForEwbsAlerts(boolean visible) {
        View panel = findViewById(R.id.side_panel);
        if (panel == null) return;
        if (sidePanelBasePaddingTop < 0) sidePanelBasePaddingTop = panel.getPaddingTop();
        int top = visible
                ? sidePanelBasePaddingTop + Dims.dp(this, 48)
                : sidePanelBasePaddingTop;
        panel.setPadding(panel.getPaddingLeft(), top, panel.getPaddingRight(), panel.getPaddingBottom());
    }

    /**
     * Inflates the layout and binds it in the order the surfaces stack: the toolbar, the watch
     * chrome, the channel list (and its tab swipe), the landscape panel, the data broadcast, the
     * player surface and finally the tab strip. This was one 292-line block with a swipe recogniser
     * in the middle of it; the regions are named now, so a change lands in the region it belongs to.
     */
    private void bindLayout() {
        bindToolbar();
        bindWatchChrome();
        bindChannelList();
        bindLandscapeChrome();
        bindDataBroadcast();
        bindPlayerSurface();
        bindTabStrip();
    }


    /** Content view, the EWBS inset host, the intent tab override and the toolbar. */
    private void bindToolbar() {
        setContentView(R.layout.activity_main);
        // The EWBS strip floats over the window; inset the side panel so its tab row stays clear.
        ewbsAlerts.setHost(this::insetPanelForEwbsAlerts);
        // Diagnostics / box setup: jump straight to a tab without the scan wizard. The extra is
        // decoded once in onCreate (decodeLaunchTab) and consumed here, so a rotation cannot re-apply it.
        if (launchTab >= 0) {
            currentTab = launchTab;
            launchTab = -1;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setOverflowIcon(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_more_vert));
            // The BENCH carries the region under the title; the release app does not (owner, 2026-11-04).
            // It is a variant difference, not a layout difference, so it lives here instead of in
            // activity_main.xml - a static subtitle there lands in every artefact cut from this branch,
            // which is exactly what was wrong with "Philippines" appearing on the release build.
            if (BuildConfig.DEBUG) toolbar.setSubtitle(R.string.app_region_ph);
        } else if (getSupportActionBar() != null) {
            setSupportActionBar(null);      // landscape has no toolbar: drop the one of the portrait layout
        }

    }

    /** The watch surface controls and the landscape HUD columns. */
    private void bindWatchChrome() {
        playerView = findViewById(R.id.player_view);
        tvCurrentChannel = findViewById(R.id.tv_current_channel);
        tabLayout = findViewById(R.id.tab_layout);
        listContent = findViewById(R.id.list_content);
        watchCard = findViewById(R.id.watch_info);
        watchStrip = findViewById(R.id.watch_status);
        btnWatchCard = findViewById(R.id.btn_watch_info);
        if (btnWatchCard != null) {
            btnWatchCard.setOnClickListener(v -> {
                if (bml.isVisible()) return;
                toggleWatchInfo();
            });
        }
        if (watchCard != null) {
            watchCard.setOnVisibilityChangedListener(showing -> {
                if (showing) hideSidePanel();
                updateApluscChrome(showing);
                if (btnWatchCard == null) return;
                btnWatchCard.setSelected(showing);
                btnWatchCard.setContentDescription(getString(showing ? R.string.cd_close_watch_info : R.string.cd_watch_info));
            });
        }
        btnWatchInfoLand = findViewById(R.id.btn_watch_info_land);
        if (btnWatchInfoLand != null) {
            btnWatchInfoLand.setOnClickListener(v -> {
                if (bml.isVisible()) return;
                toggleWatchInfo();
            });
        }
        btnRemote = findViewById(R.id.btn_remote);
        if (btnRemote != null) btnRemote.setOnClickListener(v -> toggleRemotePad());
    }

    /**
     * The tab swipe: a clear horizontal drag on the list is claimed (cancelling the row/star press)
     * and switches the tab, while vertical drags and taps are left to the list.
     */
    private void installTabSwipe() {
        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        tabSwipe.down(e.getX(), e.getY());
                        // Interaction resets the panel timer: it never closes
                        // while someone is reading or scrolling.
                        if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
                            schedulePanelAutoHide();
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        tabSwipe.move(e.getX(), e.getY(), Dims.dp(MainActivity.this, 24));
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        tabSwipe.cancel();
                        break;
                    default:
                        break;
                }
                return tabSwipe.claimed();   // true = the row no longer sees this gesture
            }

            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_UP) {
                    int step = tabSwipe.up(e.getX(), e.getY(), Dims.dp(MainActivity.this, 64));
                    if (step != 0) {
                        TabLayout.Tab tab = tabLayout != null
                                ? tabLayout.getTabAt(currentTab + step) : null;
                        if (tab != null) tab.select();
                    }
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    tabSwipe.cancel();
                }
            }
        });
    }

    /** The shared list, its tab swipe and the focus hand-off to the tab strip. */
    private void bindChannelList() {
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        // Observe the gesture before the rows: a clear horizontal swipe is
        // claimed (cancelling the row/star press) and switches the tab, while
        // vertical drags and taps are left untouched.
        installTabSwipe();
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
            public void onChildViewRemoved(View parent, View child) {/* intentionally empty */ }
        });

    }

    /** Landscape-only panel, banner, footer, empty state and HUD. */
    private void bindLandscapeChrome() {
        sidePanel = findViewById(R.id.side_panel);
        sidePanelChrome = new com.pbcnx.opendtvmod.ui.watch.SidePanelChrome(sidePanel, tuning, sidePanelHost);
        if (sidePanel != null) {
            // Any tap on the panel (including its background) postpones the
            // auto-hide; keys already do this in onKeyDown.
            sidePanel.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) schedulePanelAutoHide();
                return false;
            });
        }
        infoBanner = findViewById(R.id.info_banner);
        infoBannerChrome = new com.pbcnx.opendtvmod.ui.watch.InfoBannerChrome(infoBannerHost);
        emptyState = findViewById(R.id.empty_state);
        footerLabel = findViewById(R.id.footer_label);
        footerValue = findViewById(R.id.footer_value);
        footerEpgBar = findViewById(R.id.footer_epg_bar);
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
        statusHud = new StatusHud(new StatusHud.Views(this, hudStatusLeft, hudTdt, hudSignal,
                hudChipData, hudChipCc, hudChipLock, hudChipEwbs, hudTvNetworkName));
        btnOpenSidePanel = findViewById(R.id.btn_open_side_panel);

        if (btnOpenSidePanel != null) {
            btnOpenSidePanel.setOnClickListener(v -> toggleSidePanel());
        }

    }

    /** The REC key, the data-broadcast frame and the on-screen key pad. */
    private void bindDataBroadcast() {
        // The REC key, in whichever layout has it: the channel row in portrait, above the
        // info button in landscape. Same control either way - the tap starts and the next
        // tap stops, so the key never has to lie about which one it will do.
        TextView recordKey = findViewById(R.id.btn_record);
        if (recordKey == null) recordKey = findViewById(R.id.btn_record_land);
        recorder.setRecordView(recordKey);

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
            bmlKeys = new BmlKeyPad(this);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = Dims.dp(this, 40);
            root.addView(bmlKeys, lp);
            bmlKeys.setListener(bmlKeyListener);
        }

    }

    /** Player view, its aspect/first-tap behaviour and both caption displays. */
    private void bindPlayerSurface() {
        hideSystemBars();

        // The frame ratio and resize mode come from DisplayModes.applyAspectMode below; this only
        // keeps a sensible 16:9 geometry for the first layout pass before the pref is applied.
        AspectRatioFrameLayout frame = findViewById(R.id.player_frame);
        if (frame != null) {
            frame.setAspectRatio(16f / 9f);
        }

        playerView.setUseController(false);
        playerView.setKeepContentOnPlayerReset(false);
        // The frame geometry is orientation-dependent for Stretch/Zoom (portrait fills the card,
        // landscape fills the screen), so it is re-applied on every rotation, after bindLayout.
        applyAspectMode(aspectMode(), false);
        playerView.setOnClickListener(v -> {
            if (bml.isVisible()) return;
            boolean landscape = (layoutKey & 1) != 0;
            // A+C is modal in both layouts: only X (or BACK) closes it.
            if (watchCard != null && watchCard.isShowing()) return;
            if (landscape && sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
                hideSidePanel();   // retract the channel list first
                return;
            }
            // Both layouts: a tap toggles the banner. In portrait it is the only way back to it;
            // in landscape '<' opens the channel list, so the banner stays a tap toggle.
            if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                hideInfoBanner();
            } else {
                showInfoBanner();
            }
        });
        playerView.setPlayer(player);
        if (captions == null) captions = new CaptionBinder(captionsHost);
        captions.attach();

    }

    /** The four tabs, their selection behaviour and the first paint. */
    private void bindTabStrip() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_channels));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_epg));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_favorites));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_settings));

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
            public void onTabUnselected(TabLayout.Tab tab) {/* intentionally empty */ }
            @Override
            public void onTabReselected(TabLayout.Tab tab) {/* intentionally empty */ }
        });
        TabLayout.Tab wanted = tabLayout.getTabAt(currentTab);
        if (wanted != null && !wanted.isSelected()) wanted.select();

        updateContentAdapter();
        updateBmlButton();
    }

    // ------------------------------------------------------------------ channel list file

    @Override
    public void exportChannelList() {
        channelFileActions.export();
    }

    @Override
    public void importChannelList() {
        channelFileActions.chooseImport();
    }

    @Override
    public void pickFirmwareFile() {
        FirmwarePicker.chooseFile(this);
    }

    @Override
    public void pickFirmwareFolder() {
        FirmwarePicker.chooseFolder(this);
    }

    /** Re-binds the list and the watch surface after a channel-file import lands. */
    private void afterChannelImport() {
        updateContentAdapter();
        updateChannelSelection();
        showCurrentChannel();
    }

    // ------------------------------------------------------------------ data broadcast (BML), landscape

    /** The tuned service as the demux sees it now (saved channels are stubs without streams). */
    @Override
    public @Nullable ServiceInfo liveService(TsDemux d) {
        if (d == null || currentChannelEntry == null) return null;
        // Several muxes here reuse service ids (SID 64 on UHF 18/19/49, SID 1 on 503143/515143).
        // A SID-only match showed another mux's same-SID service as "live" - the banner and the
        // watch card then named SolarFlix while the header already said RPTV HD (owner, 2026-11-06).
        if (currentChannelEntry.khz > 0 && hub.currentKhz() > 0
                && hub.currentKhz() != currentChannelEntry.khz) return null;
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
        if (bmlFrame == null) return;
        if (bml.isVisible()) {
        bml.hide();
            if (bmlKeys != null) bmlKeys.hide();
            updateBmlButton();
            return;
        }
        TsDemux d = hub.demux();
        ServiceInfo svc = liveService(d);
        if (svc == null) {
            Toast.makeText(this, R.string.toast_data_tune_first, Toast.LENGTH_SHORT).show();
            return;
        }
    boolean hasData = false;
        for (StreamInfo st : svc.streams) hasData |= st.isDatacast();
        if (!hasData) {
            Toast.makeText(this, R.string.toast_no_data_broadcast, Toast.LENGTH_SHORT).show();
            return;
        }
        hideSidePanel();
        if (watchCard != null) watchCard.hide();
        hideTuningCard();
        if (infoBannerChrome != null) infoBannerChrome.cancel();
        hideInfoBanner();
        if (bml.open(d, svc)) bml.tap(BmlController.DATA);   // brand-new page: ask it to show itself once loaded
        updateBmlButton();
    }

    /** Portrait remote: the pad's DATA key brightens while the page is on screen. */
    private void updateBmlButton() {
        if (remotePanel instanceof CornerPad) {
            ((CornerPad) remotePanel)
                    .setDataActive(bml.isVisible());
        }
    }

    /** Wires the portrait remote pad. Absent in the landscape layouts. */
    private void bindRemote() {
        remotePanel = findViewById(R.id.remote_panel);
        if (!(remotePanel instanceof CornerPad)) {
            remotePanel = null;
            return;
        }
        CornerPad pad = (CornerPad) remotePanel;
        pad.setListener(cornerListener);
        setRemotePanelOpen(remotePanelOpen);
        // The strip's DATA chip is the way in now that the floating button is gone: it
        // already knows whether this channel carries a page.
        if (watchStrip != null) {
            watchStrip.setDataClickListener(() -> {
                final boolean wasUp = bml.isVisible();
                toggleBml();
                // Opening the page brings the pad with it; closing it leaves the pad be.
                if (!wasUp) setRemotePanelOpen(bml.isVisible());
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
        if (!bml.isVisible()) toggleBml();
        setRemotePanelOpen(bml.isVisible());
    }

    /** A pad key: only meaningful while a page is up, so say so instead of doing nothing. */
    private void padKey(int code) {
        if (!bml.isVisible()) {
            Toast.makeText(this, R.string.toast_open_data_first, Toast.LENGTH_SHORT).show();
            return;
        }
        bml.tap(code);
    }

    /** EXIT on the pad: close the page and put the pad away with it. */
    private void closeBml() {
        if (bml.isVisible()) bml.close();
        if (bmlKeys != null) bmlKeys.hide();
        setRemotePanelOpen(false);
        updateBmlButton();
    }

    private void setRemotePanelOpen(boolean open) {
        remotePanelOpen = open;
        if (remotePanel == null) return;
        remotePanel.setVisibility(open ? View.VISIBLE : View.GONE);
        // A fresh pad always starts on the arrows, never on the digits left open last time.
        if (open && remotePanel instanceof CornerPad) {
            ((CornerPad) remotePanel).resetFace();
        }
        // The row button is the pad's state light: filled while the pad is up, outlined
        // when it is not - the same selected/unselected pair the info button uses.
        if (btnRemote != null) btnRemote.setSelected(open);
        updateBmlButton();
    }

    /** BML moves / shrinks the video: place the PlayerView inside the landscape root accordingly. */
    private void applyBmlVideoRect() {
        if (playerView == null || bmlFrame == null) return;
    int w = bmlFrame.getWidth();
    int h = bmlFrame.getHeight();
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
        // Any remote key brings the drawer handle back before it is routed: the handle hides after
        // 6 s idle like the other controls (owner, 2026-11-06).
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) revealSideChrome();
        // Eight routing regions, in the order the surfaces are stacked: the favourites reorder
        // mode, the data-broadcast key pad, the page itself, the guide/source buttons, the
        // watcher card, the landscape OK hold and the landscape side panel. Each helper returns
        // true when it consumed the key, so the routing stays one readable list instead of a
        // 200-line if-chain.
        if (routeFavoritesReorder(e)) return true;
        if (routeKeyPad(e)) return true;
        if (routeDataBroadcast(e)) return true;
        if (routeGuideAndSource(e)) return true;
        if (bmlFrame != null && routeBmlKey(e)) return true;
        if (routeWatchCard(e)) return true;
        if (routeWatchingOk(e)) return true;
        return routeLandscapePanel(e) || super.dispatchKeyEvent(e);
    }

    /** A touch anywhere brings back the drawer handle the idle timer hid. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) revealSideChrome();
        return super.dispatchTouchEvent(e);
    }

    /** Reorder mode takes held OK as "start moving", arrows as the move, OK as "place". */
    private boolean routeFavoritesReorder(android.view.KeyEvent e) {
        if (favoritesReorder.handleKey(e)) return true;
        if (currentTab == MainScreen.TAB_FAVORITES && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getRepeatCount() >= 1 && recyclerView.hasFocus()
                && (e.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
            favoritesReorder.enterAtFocused();
            return true;
        }
        return false;
    }

    /** The on-screen BML key pad, when up, owns every key. */
    private boolean routeKeyPad(android.view.KeyEvent e) {
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
        return false;
    }

    /** A visible data broadcast: long OK opens the key pad, a short press is ENTER for the page. */
    private boolean routeDataBroadcast(android.view.KeyEvent e) {
        // Long OK on a visible data broadcast shows the key pad; a short press
        // is still ENTER for the page. There is no DATA key on the TV remote,
        // so the ribbon closes the data broadcast.
        if (bml.isVisible()) {
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
                return keyPadOk.handle(e);
            }
        }
        return false;
    }

    /** GUIDE opens the program guide; TV_INPUT toggles the channel list, on the watching surface only. */
    private boolean routeGuideAndSource(android.view.KeyEvent e) {
        // GUIDE (the TV button) opens the program guide; TV_INPUT (source)
        // toggles the channel list. Both stay out of the way of a data broadcast.
        if (!bml.isVisible()
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
        return false;
    }

    /** The watcher card: INFO/BOOKMARK show or toggle it, BACK hides it, OK on it toggles details. */
    private boolean routeWatchCard(android.view.KeyEvent e) {
        if (!bml.isVisible() && watchCard != null
                && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0) {
            int keyCode = e.getKeyCode();
            if (keyCode == android.view.KeyEvent.KEYCODE_INFO
                    || keyCode == android.view.KeyEvent.KEYCODE_BOOKMARK) {
                // INFO on most remotes; BOOKMARK is the ribbon key on Skyworth
                // sets, which have no INFO button at all.
                if (watchCard.isShowing()) {
                    watchCard.toggleDetails();
                } else {
                    showWatchInfo();
                }
                return true;
            }
            if (watchCard.isShowing() && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                watchCard.hide();
                return true;
            }
            boolean center = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
            if (center && watchCard.isShowing()
                    && (sidePanel == null || sidePanel.getVisibility() != View.VISIBLE)) {
                watchCard.toggleDetails();
                return true;
            }
        }
        return false;
    }

    /** Landscape: long OK opens the data broadcast; a short OK keeps the banner/card behaviour. */
    private boolean routeWatchingOk(android.view.KeyEvent e) {
        // Long OK on the watching surface opens the data broadcast; a short OK
        // keeps the banner / card behaviour. Landscape only: the portrait remote
        // already has its own DATA button.
        if (sidePanel != null && !bml.isVisible()
                && (watchCard == null || !watchCard.isShowing())
                && sidePanel.getVisibility() != View.VISIBLE) {
            int kc = e.getKeyCode();
            if (kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || kc == android.view.KeyEvent.KEYCODE_ENTER) {
                return watchingOk.handle(e);
            }
        }
        return false;
    }

    /** Landscape: LEFT opens the list even when the player owns focus, BACK closes it, OK shows the card. */
    private boolean routeLandscapePanel(android.view.KeyEvent e) {
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
            if (panelOpen && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                hideSidePanel();
                return true;
            }
        }
        return false;
    }

    /**
     * One 500 ms held-OK gate: DOWN arms the hold action, UP runs the short action only if the hold
     * never fired. This state machine was written twice, once for the data-broadcast key pad and
     * once for the watching surface, with the same flags under two names.
     */
        private static final class HeldOk {
        private final android.os.Handler ui;
        private final Runnable onHold;
    private final Runnable onShort;
        /** One instance: removeCallbacks compares identity, so a fresh method reference would not remove it. */
        private final Runnable fireHoldTask = this::fireHold;
    private boolean held;

        HeldOk(android.os.Handler ui, Runnable onHold, Runnable onShort) {
        this.ui = ui;
            this.onHold = onHold;
            this.onShort = onShort;
        }

        private void fireHold() {
            held = false;
            onHold.run();
        }

        /** Handles a DOWN/UP of the centre key; always consumed. */
        boolean handle(android.view.KeyEvent e) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    held = true;
        ui.postDelayed(fireHoldTask, 500);
                }
                return true;
            }
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
        ui.removeCallbacks(fireHoldTask);
                boolean shortPress = held;
                held = false;
                if (shortPress) onShort.run();
                return true;
            }
            return true;
        }
    }

    private boolean routeBmlKey(android.view.KeyEvent e) {
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
            return BmlController.DIGIT0 + kc - android.view.KeyEvent.KEYCODE_0;
        if (kc >= android.view.KeyEvent.KEYCODE_NUMPAD_0 && kc <= android.view.KeyEvent.KEYCODE_NUMPAD_9)
            return BmlController.DIGIT0 + kc - android.view.KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // a hidden (cached) data broadcast page is the first thing to give back when memory gets low
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) bml.trimMemory();
    }

    // ------------------------------------------------------------------ no options menu
    //
    // There is deliberately no overflow: six of the ten items it carried were the same
    // callbacks as Settings rows (scan, manual scan, EWBS location, analyzer, record,
    // captions) under a second set of labels, and the labels had already drifted. Every
    // action lives in the Settings tab now, including the decoder switch and About.

    /**
     * Bench hook {@code --ez sweep true}: rank every UHF mux by delivered packets against continuity
     * errors, log the table, then stay on the strongest. For the "is the jitter reception?" question -
     * no tuner here reports a strength that can be compared across sticks, and the clean-room 9308
     * reports none at all.
     */
    private void applySweepHook(BenchHooks.LaunchExtras extras) {
        if (!extras.sweep) return;
        new Thread(() -> {
            try {
                if (!hub.ensurePermission(60_000)) {
                    Log.w(TAG, "sweep: USB permission not granted");
                    return;
                }
        hub.acquire(this);
                MuxSweep.sweep(new MuxSweep.Tuner() {
                    @Override
                    public boolean tune(int khz) {
        return hub.ensureTuned(khz, () -> false);
                    }

                    @Override
                    public TsDemux demux() {
        return hub.demux();
                    }
                }, hub.currentKhz());
            } catch (Throwable t) {
                Log.e(TAG, "sweep failed: " + t, t);
            }
        }, "uhf-sweep").start();
    }

    // ------------------------------------------------------------------ Decoder section
    //
    // One gate and two overrides, all in DecoderPrefs so both players (this activity's and the TV
    // input's) read the same answer. On BOTH switch rows ON means SOFTWARE and OFF means the device's
    // decoder, which is why the descriptions name the decoder and not the switch.
    //
    // A change applies to the channel that is playing by retuning it, because the renderers are fixed
    // when the player is built. The old rows recreated the whole activity to get that, which threw the
    // viewer out of Settings every time they touched a decoder switch.

    @Override
    public void setDecoderManual(boolean manual) {
        DecoderPrefs.setManual(this, manual);
        refreshAfterDecoderChange(getString(manual
                ? R.string.toast_decoding_manual
                : R.string.toast_decoding_auto));
    }

    @Override
    public void setDecoderVideoSoftware(boolean software) {
        DecoderPrefs.setVideoSoftware(this, software);
        refreshAfterDecoderChange(getString(R.string.toast_video_decoder,
                getString(software ? R.string.decoder_software_lower : R.string.decoder_device_lower)));
    }

    @Override
    public void setDecoderAudioSoftware(boolean software) {
        DecoderPrefs.setAudioSoftware(this, software);
        refreshAfterDecoderChange(getString(R.string.toast_audio_decoder,
                getString(software ? R.string.decoder_software_lower : R.string.decoder_device_lower)));
    }

    /** Re-binds the rows (the Manual gate greys two of them) and rebuilds the player if one is live. */
    private void refreshAfterDecoderChange(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        if (settingsAdapter != null) settingsAdapter.notifyDataSetChanged();
        playback.markDecoderRebuildPending();
    final ChannelEntry entry = currentChannelEntry;
        if (entry != null && !scan.isScanning() && !isFinishing() && tunerLifecycle.canTune()) {
            playback.forgetLastPlay();        // a decoder change must never look like a duplicate tune
            playback.tuneAndPlay(entry);
        }
    }

    /**
     * Delivers a bench intent's extras, from a cold start and from a re-delivered intent alike.
     * {@link BenchHooks#applyAll} is debug-only and reaches the sweep through {@link BenchHooks.Host}.
     */
    private void applyBenchHooks(BenchHooks.LaunchExtras extras) {
        BenchHooks.applyAll(this, extras, ui, worker, FirmwareImport::isImageSize, benchHost);
    }

    /**
     * The capture hook, now used from BOTH onCreate and onNewIntent. The screen is singleTop, so
     * re-issuing {@code am start --es ts_path <file>} while it is alive lands in onNewIntent; without
     * that, a bench "channel change" silently kept playing the previous capture, and a chip test built on
     * it measured nothing. A real zap needs a tuner, so switching the file source live is the closest
     * honest simulation: same activity instance, same HUD, new demux content.
     */
    private void applyCaptureHook(BenchHooks.LaunchExtras extras) {
        if (extras == null || extras.tsPath == null) return;
        captureLaunch = true;   // keep the saved-channel autoplay out of the way until the file opens
        BenchHooks.runCapture(this, extras, 900_000L, true, src -> {
            if (!hub.playSource(src)) return;   // the helper's follow-up still runs
            // The file source owns the screen now: ready to play, no tuner init, no saved-channel tune.
            captureLaunch = false;
            setTunerCanTune(true);
            // Start playing now rather than waiting for a tuner to be found: there is none.
            playback.playFromRunningSource();
        }, 6_000, this::toggleBml);
    }

    /** The line under the Video row while Auto is in charge: what it will do on this channel. */
    @Override
    public String decoderVideoSummary(boolean manual, int khz) {
        if (manual) {
            return DecoderPrefs.videoSoftware(this)
                    ? getString(R.string.decoder_software)
                    : getString(R.string.decoder_device);
        }
        Boolean learned = DecoderPrefs.autoVideoSoftware(this, khz);
        if (learned == null) {
            return getString(R.string.decoder_auto_unknown);
        }
        return learned
                ? getString(R.string.decoder_auto_software)
                : getString(R.string.decoder_auto_device);
    }

    /**
     * The Settings row under ABOUT. It used to build its own "Version x - built yyyy-MM-dd HH:mm:ss"
     * while the box said "Version x build [hash]" and "date . time" - the same two facts in two
     * shapes, four inches apart. The box owns the date; this row answers "which build".
     */
    @Override
    public String aboutSummary() {
    String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // version stays "?" when the package info is unavailable
        }
        return BuildStamp.versionLine(this, version);
    }

    // ------------------------------------------------------------------ closed captions / audio language

    @Override public int captionStyle() { return captions == null ? DisplayModes.CAPTION_SIMPLE : captions.style(); }

    /** Creates / frees the styled caption WebView to match the Settings choice, and routes the captions to it. */

    /** Sends the channel's captions to whichever display is selected. */
    @Override public void bindCaptionSink() { if (captions != null) captions.bind(); }

    @Override public void resetCaptions() { if (captions != null) captions.reset(); }

    @Override public void setCaptionStyle(int style) { if (captions != null) captions.setStyle(style); }

    @Override public void setCaptionsEnabled(boolean on) { if (captions != null) captions.setEnabled(on); }

    @Override public int captionLanguage() { return captions == null ? DisplayModes.LANGUAGE_MAIN : captions.language(); }

    @Override public void setCaptionLanguage(int language) { if (captions != null) captions.setLanguage(language); }

    /** "English", "Filipino", ... from an ISO 639 code; the upper-cased code when Android does not know it. */
    private static String languageName(String code) {
        if (code == null || code.isEmpty() || "und".equals(code)) return null;
        try {
            String n = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault());
            if (n != null && !n.isEmpty() && !n.equalsIgnoreCase(code)) return n;
        } catch (RuntimeException ignored) {
            // unknown tag: fall back to the tag in upper case
        }
        return code.toUpperCase(Locale.ROOT);
    }

    /** ISO 639 language the user picked ("" = automatic), shown in the Settings tab. */
    @Override
    public String audioLanguageSummary() {
        String n = languageName(AudioLanguage.code(prefs()));
        return n == null ? getString(R.string.audio_automatic) : n;
    }

    /** Applies the saved audio language preference to the player (it picks the matching track when a channel starts). */
    @Override
    public void applyAudioLanguagePref() {
        AudioLanguage.apply(player, prefs());
    }

    /** One audio track the channel offers (an elementary stream of the current service). */
    @Override
    public String audioTrackLabel(int index, androidx.media3.common.Format f) {
    String lang = languageName(f.language);
        if (lang == null && currentChannelEntry != null && currentChannelEntry.service != null && f.id != null) {
            // Format id is "<program>/<pid>": fall back to the language our own PMT parser found for that PID
            try {
                String idPart = f.id.contains("/") ? f.id.substring(f.id.lastIndexOf('/') + 1) : f.id;
                int pid = Integer.parseInt(idPart);
                ServiceInfo live = findLiveService(currentChannelEntry.service.serviceId);
                if (live != null) for (StreamInfo st : live.streams) if (st.pid == pid) { lang = languageName(st.language); break; }
            } catch (RuntimeException ignored) {
                // best effort: an unparseable id only costs the language label
            }
        }
        StringBuilder sb = new StringBuilder(lang != null ? lang : getString(R.string.audio_track, index + 1));
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
            // Always the hub's demux: the old shadow field could point at one the hub had already
            // replaced on a retune, so a lookup could answer from the previous multiplex.
            for (ServiceInfo si : hub.demux().getServices()) if (si.serviceId == sid) return si;
        } catch (RuntimeException ignored) {
            // demux not ready or already released: no live service to match
        }
        return null;
    }


    @Override
    public void saveEwbsLocation(int code) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_EWBS_LOCATION, code).apply();
        updateContentAdapter();
        ewbsAlerts.update(hub.demux(), ewbsLocation());
        updateInfoBanner(hub.demux());
        // A running data-broadcast page reads the viewer's region through the engine (2.6.0 write side),
        // so a region change takes effect on the page without a reload. 0 = unset, which clears it.
        bml.setReceiverAreaCode(code);
    }

    /** Resolves the phone's current place to a PH EWBS city/region code. */
    @Override
    public void detectEwbsLocation(MainDialogs.LocationCallback callback) {
        ewbsLocator.detect(callback);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (com.pbcnx.opendtvmod.ui.StorageAccess.onRequestPermissionsResult(requestCode, grantResults)) return;
        ewbsLocator.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The first start is covered by onCreate's initTuner / playFromRunningSource; resuming here
        // as well is what started the same channel twice. Every later start is a real return.
        if (firstStart) {
        firstStart = false;
            return;
        }
        // Came back from the background / Analyzer: resume the channel (same multiplex = instant).
        if (tunerLifecycle.canTune() && currentChannelEntry != null && !scan.isScanning()) {
            playback.resetRetries();
            playback.tuneAndPlay(currentChannelEntry);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        cancelSideChromeHide();
        // Nothing to watch in the background: stop decoding and stop feeding the pipe (CPU, battery, heap).
        playGen.incrementAndGet();
        // The player was stopped on purpose, so the next play is a fresh start, never a duplicate.
        playback.resetLastPlayAt();
        ui.removeCallbacksAndMessages(null);
        player.stop();
        player.clearMediaItems();
        hub.detachSink(this);
        pipe.clear();
        resetCaptions();
        bml.close();        // background: no data-broadcast page kept alive unseen
        // Forget the card as well as hiding it: the views it lived on are gone, and onStart asks
        // for a fresh one. A remembered runnable here would replay a card nobody asked for.
        hideTuningCard();
    }

    /**
     * The Developers screen is a different activity, so going there stops this one and its
     * data-broadcast page is closed on the way out (onStop). The screen exists to ask that page
     * questions, which is why the page is asked HERE, while it is still alive: the row then shows
     * the engine's own answer with the time it was taken instead of "the page did not answer".
     */
    @Override
    public void openDeveloperOptions() {
        DeveloperDoor.open(this, bml);
    }

    /**
     * The Analyzer claims the same {@link TunerHub} this screen uses; it does not open
     * the dongle on its own. Release the hub's sink and the pipe first so the claim is uncontested, and
     * hold {@code tunerLifecycle.isHandingOver()} until onActivityResult: the guard in initTuner/onResume reads it, so the
     * dongle is not re-opened while the Analyzer owns it.
     *
     * <p>Tv SetupActivity opens the same analyzer directly for a bench frequency; that second door is
     * intentional (the setup screen is not the watch screen) and lands on the same claim contract.
     */
    @Override
    public void openAnalyzer() {
        AnalyzerHandoff.launch(this, REQ_ANALYZER);
    }

    /**
     * The Analyzer has finished and closed the dongle. Only now is it safe to take the tuner back;
     * clearing the flag here (not in onResume) is what lets the return trip re-initialise while the
     * departure trip does not.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ChannelFileActions.REQ_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                channelFileActions.writeExport(data.getData());
            }
            return;
        }
        if (requestCode == ChannelFileActions.REQ_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                channelFileActions.filePicked(data.getData());
            }
            return;
        }
        if (requestCode == FirmwarePicker.REQ_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                FirmwareImport.filePicked(this, data.getData(), worker, firmwareHost);
            }
            return;
        }
        if (requestCode == FirmwarePicker.REQ_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                FirmwareImport.folderPicked(this, data.getData(), worker, firmwareHost);
            }
            return;
        }
        if (requestCode != REQ_ANALYZER) return;
        setHandingOver(false);
        setTunerCanTune(false);
        initTuner();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // The same bench set as a cold start: the app is singleTop, so an am start lands here.
        applyBenchHooks(BenchHooks.launchExtras(intent));
        // ...and so does a capture SWITCH. A viewer changing channel does not recreate the activity, so
        // ts_path has to be honoured here too or the "channel change" we measure is not one.
        applyCaptureHook(BenchHooks.launchExtras(intent));
        // The Developers screen's "Open Analyzer" row arrives on the live instance (singleTop).
        if (BenchHooks.launchExtras(intent).openAnalyzer) {
            BenchHooks.consume(intent);
            openAnalyzer();
            return;
        }
        // USB_DEVICE_ATTACHED re-delivers to this singleTop activity
        if (!tunerLifecycle.canTune()) initTuner();
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.pbcnx.opendtvmod.ui.StorageAccess.resumePending(this);
        ui.removeCallbacks(ewbsTick);
        ui.post(ewbsTick);
        hideSystemBars();
        // The Test EWBS row lives on the hidden Developers screen now; the dialog did not move.
        developer().setDemux(hub::demux);
        developer().setCaptionProbe(CaptionProbe::last);
        developer().setBmlState(() -> new DeveloperSurface.BmlState(
                com.pbcnx.opendtvmod.App.diagnostics().lastController(),
                com.pbcnx.opendtvmod.App.diagnostics().stashedSnapshot(),
                com.pbcnx.opendtvmod.App.diagnostics().stashedAtMs()));

        // The three STATE rows on the Developers screen. They run synchronously because the
        // screen restarts the task the moment RESET_ALL returns; anything deferred is cut.
    final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        developer().register(DeveloperActions.CLEAR_CHANNELS, () -> {
        channelStore.clearChannelsAndSave(storePrefs());
        ui.post(() -> updateInfoBanner(hub.demux()));
        });
        developer().register(DeveloperActions.RESET_FAVORITES, () -> {
        channelStore.clearFavorites(storePrefs());
        ui.post(() -> updateInfoBanner(hub.demux()));
        });
        developer().register(DeveloperActions.RESET_ALL,
                () -> p.edit().clear().commit());

        // A recording can outlive a trip to the Analyzer: re-read the writer, do not guess.
        recorder.updateTally();
        // While the Analyzer is taking the dongle, none of the paths below may open it - not even
        // "the hub is closed, reopen it", which is exactly right on the way BACK and exactly wrong
        // on the way out. onActivityResult clears tunerLifecycle.isHandingOver() and re-initialises there.
        if (!tunerLifecycle.isHandingOver()) {
            if (!tunerLifecycle.canTune() && !scan.isScanning()) initTuner();
            // tunerLifecycle.canTune() only says "we initialised once". A direct start of the Analyzer takes the
            // dongle away without going through openAnalyzer(), so on return this hub is stale and
            // the flag still claims otherwise - reopen instead of sitting on a dead hub.
            else if (!hub.isOpen()) {
        setTunerCanTune(false);
                initTuner();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ewbsTick);
        // A pulsing view nobody can see is a wakeup a second; the state is re-read on resume.
        recorder.stopPulse();
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
     * The frame shape and the in-frame scaling are two separate decisions (DisplayModes.resolve):
     * Automatic follows the content, Stretch/Zoom fill the available area, and the Force modes pin
     * the frame. Portrait fills the video card; landscape fills the screen unless a Force mode is on,
     * in which case the layout gives it a centred box.
     */
    private void applyAspectMode(int mode, boolean announce) {
        mode = DisplayModes.aspectMode(mode);
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        DisplayModes.FrameGeometry geometry = DisplayModes.resolve(mode, contentAspect(), landscape);
        AspectRatioFrameLayout frame = findViewById(R.id.player_frame);
        if (frame != null) {
            frame.setAspectRatio(geometry.frameRatio);
        }
        int rm;
        switch (geometry.resizeMode) {
            case DisplayModes.RESIZE_FIT:
                rm = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                break;
            case DisplayModes.RESIZE_ZOOM:
                rm = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                break;
            default:
                rm = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                break;
        }
        playerView.setResizeMode(rm);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_ASPECT, mode).apply();
        if (announce) Toast.makeText(this, SettingsController.aspectModeName(this, mode), Toast.LENGTH_SHORT).show();
    }

    /**
     * The playing video's DISPLAY aspect; 16:9 when nothing is playing yet. ISDB-T streams are often
     * anamorphic (720x480 coded, 16:9 or 4:3 displayed), so the pixel aspect ratio has to be applied
     * or Automatic would box a 16:9 channel at 3:2.
     */
    private float contentAspect() {
        if (playerView != null && playerView.getPlayer() instanceof androidx.media3.exoplayer.ExoPlayer) {
            androidx.media3.common.Format video =
                    ((androidx.media3.exoplayer.ExoPlayer) playerView.getPlayer()).getVideoFormat();
            if (video != null) {
                return DisplayModes.displayAspect(video.width, video.height,
                        video.pixelWidthHeightRatio);
            }
        }
        return 16f / 9f;
    }

    @Override
    public int ewbsLocation() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_EWBS_LOCATION, 0);
    }

    // ------------------------------------------------------------------ tuning / no-signal card

    /* Shown 500 ms into a tune, only if no frame has arrived. */
    
    /* "UHF 18 · 497.143 MHz", or empty when there is no frequency. */
    
    /** Same card after the grace period: no frame, so say why. */
    
    private void showNoTunerCard() {
        if (tuning == null) return;
        ui.removeCallbacks(showTuningCardRunnable);
        ui.removeCallbacks(showNoSignalCardRunnable);
        tuning.showNoTuner();
        // Replayed only while the dongle is still missing: a rotation just after it is plugged in
        // must not cancel the cards the new tune has queued.
        tuningCardShown = () -> {
            if (hub == null || !hub.isTunerPresent()) showNoTunerCard();
        };
    }

    @Override
    public void hideTuningCard() {
        ui.removeCallbacks(showTuningCardRunnable);
        ui.removeCallbacks(showNoSignalCardRunnable);
        if (tuning != null) tuning.hide();
        tuningCardShown = null;   // nothing is up, so a rotation has nothing to replay
    }

    public void updateInfoBanner(TsDemux d) {
        WatchCardView info = watchCard;
        if (info == null) return;
        ServiceInfo live = liveService(d);
    boolean data = false;
    boolean caption = false;
        if (live != null) {
            for (StreamInfo stream : live.streams) {
                data |= stream.isDatacast();
                // Same predicate the caption filter routes on, so the card cannot claim a caption
                // stream the decoder never receives.
                caption |= CaptionProbe.isCaptionStream(stream);
            }
        }
    boolean emergency = d != null && d.isEwbsActive(ewbsLocation());
        caption &= captions != null && captions.enabled();
        ServiceInfo display = live != null
                ? live
                : (currentChannelEntry != null ? currentChannelEntry.service : null);
    int khz = currentChannelEntry != null ? currentChannelEntry.khz : 0;
        TunerSignal signal = hub.signal();
    long clock = d != null ? d.getClockMs() : -1L;
    String nowName = null;
    String nextName = null;
        if (currentChannelEntry != null && live != null) {
            // Guide data belongs to the mux the tuner is on: a same-SID service on another mux
            // would put the wrong programme names on the banner.
            String[] guide = epgNowNext(currentChannelEntry.service.serviceId);
            nowName = guide[0];
            nextName = guide[1];
        }
        ChannelSnapshot snap = new ChannelSnapshot();
        snap.service = display;
        snap.khz = khz;
        snap.signal = signal;
        snap.clockMs = clock;
        snap.data = data;
        snap.caption = caption;
        snap.encrypted = live != null && live.encrypted;
        snap.emergency = emergency;
        snap.live = sourceLive();
        snap.tunerPresent = hub.isTunerPresent();
        snap.nowName = nowName;
        snap.nextName = nextName;
        snap.networkName = d != null ? d.getNetworkName() : "";
        snap.demux = d;
        info.bind(snap);
        if (watchStrip != null) {
            // A capture has no frequency: the mux cell says what the source is instead of
            // "No channel", which reads as a fault while channel 160 is playing.
            watchStrip.setMuxFallback(sourceCannotTune() && hub.sourceName() != null
                    ? hub.sourceName() : null);
            watchStrip.bind(snap);
        }
        statusHud.render(snap);
    }

    @Override
    public void showCurrentChannel() {
        if (currentChannelEntry == null) {
            if (tvCurrentChannel != null) tvCurrentChannel.setText(R.string.no_channel_tuned);
            if (watchCard != null) watchCard.hide();
            hideTuningCard();
            // Nothing tuned: the landscape banner has nothing to say, and until it is filled it
            // shows the layout's sample text ("0.0 Unknown Channel") - which read as a channel
            // that existed while "Tuner not found" was the truth (ODTV-50 / jpdcs#13).
            hideInfoBanner();
            updateChannelSelection();
            updateInfoBanner(hub.demux());
            return;
        }
        ServiceInfo svc = currentChannelEntry.service;
        if (tvCurrentChannel != null) {
            // A capture's SDT can arrive without a virtual channel: the sid is then the honest number.
            tvCurrentChannel.setText(svc.displayNumber() + "  "
                    + ChannelAdapter.displayName(svc));
        }
        updateChannelSelection();
        // Both layouts: the card is on demand, so a channel change hides it and refreshes the
        // passive surface; landscape additionally shows the banner strip.
        if (watchCard != null) watchCard.hide();
        if (sidePanel != null) showInfoBanner();
        updateInfoBanner(hub.demux());
    }

    private void toggleWatchInfo() {
        if (watchCard == null) return;
        if (watchCard.isShowing()) watchCard.hide();
        else showWatchInfo();
    }

    private void showWatchInfo() {
        if (watchCard == null) return;
        hideInfoBanner();
        updateInfoBanner(hub.demux());
        watchCard.showCompact();
    }

    private void updateChannelSelection() {
        RecyclerView.Adapter<?> adapter = recyclerView != null ? recyclerView.getAdapter() : null;
        if (adapter instanceof ChannelAdapter) {
            ((ChannelAdapter) adapter).setActiveService
                    (currentChannelEntry != null ? currentChannelEntry.service : null);
        }
    }

    // ------------------------------------------------------------------ source liveness

    /**
     * One 1 Hz sample of the hub's packet count. Liveness is sampled, never remembered: a source
     * that dies stops being live within two ticks, and a capture that starts is live the second its
     * first packet lands - no device poll, no restart.
     */
    private void sampleSource(TsDemux d) {
        if (d == null) return;
        if (d != sampledDemux) {
            sampledDemux = d;
            packetsAtSample = -1;      // a new demux starts at zero: that is not a rise
        }
    long now = SystemClock.elapsedRealtime();
    long count = d.getPacketCount();
        if (packetsAtSample >= 0 && count > packetsAtSample) lastPacketMs = now;
        packetsAtSample = count;
    }

    /** True when the hub's source produced packets within the last two seconds. */
    private boolean sourceLive() {
        return hub.isOpen() && SystemClock.elapsedRealtime() - lastPacketMs < 2000;
    }

    /** True when the hub runs a source that cannot be tuned: a capture, not a dongle. */
    @Override
    public boolean sourceCannotTune() {
        return hub.isOpen() && !hub.isHardwareTuner();
    }

    /** Finds the tuner, gets USB permission, takes ownership, then starts the (last) channel automatically. */
    private void initTuner() {
        TunerBootstrapper.launch(bootstrapHost);
    }

    private void debugTune(final int khz) {
        worker.execute(() -> {
            boolean locked = hub.ensureTuned(khz, () -> false);
            Log.i(TAG, "debug tune khz=" + khz + " locked=" + locked);
            ui.postDelayed(() -> {
                TunerSignal s = hub.signal();
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
    @Override
    public void refreshSettings() {
        if (currentTab != MainScreen.TAB_SETTINGS) return;
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
        float shift = Math.min(Dims.dp(this, 64),
                getResources().getDisplayMetrics().widthPixels * 0.14f);
        listContent.animate().cancel();
        listContent.setAlpha(0f);
        listContent.setTranslationX(dir * shift);
        listContent.animate().alpha(1f).translationX(0f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /**
     * The channel-list click behaviour, shared by the Channels and the Favorites tab: the same
     * two-pass lookup (the exact entry first, then the service id) and the same favourite
     * bookkeeping. They were two copies, and two copies can drift.
     */
    final ChannelAdapter.OnChannelClickListener channelClickListener =
            new ChannelAdapter.OnChannelClickListener() {
                @Override
                public void onChannelClick(ServiceInfo service) {
                    playClickedChannel(service);
                }

                @Override
                public void onFavoriteToggle(ServiceInfo service, boolean isFavorite) {
                    channelStore.setFavorite(service, isFavorite);
        channelStore.saveFavorites(storePrefs());
        channelStore.saveOrder(storePrefs());
                    updateContentAdapter();
                    Toast.makeText(MainActivity.this,
                            getString(isFavorite ? R.string.toast_favorite_added : R.string.toast_favorite_removed,
                                    ChannelAdapter.displayName(service)),
                            Toast.LENGTH_SHORT).show();
                }
            };

    /** Exact entry first: the same service id can exist on several muxes. */
    private void playClickedChannel(ServiceInfo service) {
        for (ChannelEntry ce : channelStore.channels()) {
            if (ce.service == service) {
                selectAndPlayChannel(ce);
                return;
            }
        }
        // Same channel, different object: the (frequency, service id) key decides, never SID alone.
        String key = com.pbcnx.opendtvmod.tuner.ChannelStore.key(service);
        for (ChannelEntry ce : channelStore.channels()) {
            if (com.pbcnx.opendtvmod.tuner.ChannelStore.key(ce.service).equals(key)) {
                selectAndPlayChannel(ce);
                return;
            }
        }
        // Last resort for a caller that lost the frequency: only when the id is unambiguous.
        ChannelEntry only = null;
        int matches = 0;
        for (ChannelEntry ce : channelStore.channels()) {
            if (ce.service.serviceId == service.serviceId) {
                matches++;
                only = ce;
            }
        }
        if (matches == 1) selectAndPlayChannel(only);
    }

    @Override
    public void updateContentAdapter() {
        if (favoritesReorder != null) favoritesReorder.detach();
        // The BML pad belongs to the channel list: it would float over nothing on EPG and
        // Favorites, so it goes away with the tab.
        if (remotePanel != null && currentTab != MainScreen.TAB_CHANNELS && remotePanelOpen) setRemotePanelOpen(false);
        switch (currentTab) {
            case 1: showEpgTab(); break;
            case 2: showFavoritesTab(); break;
            case 3: showSettingsTab(); break;
            default: showChannelsTab(); break;
        }
        updateFooter();
    }

    /**
     * The one line under the list, following the selected tab (owner, 2026-11-06):
     * Channels -> total, EPG -> progress bar + clock, Favorites -> total, Settings -> build stamp.
     * The UHF/chips/S/Q strip moved up under the channel name, so this is only the tab's own line.
     */
    private void updateFooter() {
        if (footerLabel == null || footerValue == null) return;
        switch (currentTab) {
            case 1: // EPG: current programme's progress in the middle, TDT/TOT clock on the right
                footerLabel.setText(R.string.epg_label);
                footerEpgBar.setVisibility(View.VISIBLE);
                footerValue.setText(clockText());
                break;
            case 2: // Favorites
                footerLabel.setText(R.string.favorites_label);
                footerEpgBar.setVisibility(View.GONE);
                footerValue.setText(String.valueOf(channelStore.favorites().size()));
                break;
            case 3: // Settings: the build identity instead of a count
                footerLabel.setText("OPENDTV-MOD");
                footerEpgBar.setVisibility(View.GONE);
                footerValue.setText(buildLine());
                break;
            default: // Channels
                footerLabel.setText(R.string.channels_label);
                footerEpgBar.setVisibility(View.GONE);
                footerValue.setText(String.valueOf(channelStore.channelCount()));
                break;
        }
        updateFooterClockProgress();
    }

    /** "v0.2.0 [6eda55c]" from the same package/build stamp the About box uses. */
    private String buildLine() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // version stays "?" when the package info is unavailable
        }
        String commit = BuildStamp.commit();
        return commit.isEmpty() ? "v" + version : "v" + version + " [" + commit + "]";
    }

    private String clockText() {
        TsDemux d = hub.demux();
        long clock = d != null && d.getClockMs() > 0 ? d.getClockMs() : System.currentTimeMillis();
        return CLOCK_FORMAT.format(new java.util.Date(clock));
    }

    /** Once a second from the watch ticker: EPG progress + the broadcast clock on the EPG tab only. */
    private void updateFooterClockProgress() {
        if (currentTab != 1 || footerEpgBar == null) return;
        TsDemux d = hub.demux();
        long broadcast = d != null ? d.getClockMs() : 0L;
        long clock = broadcast > 0 ? broadcast : System.currentTimeMillis();
        footerEpgBar.setProgress(epgProgress(d, broadcast));
        if (footerValue != null) footerValue.setText(CLOCK_FORMAT.format(new java.util.Date(clock)));
    }

    /** How far the current programme has run, 0..1000 (0 when there is no guide data). */
    private int epgProgress(TsDemux d, long clock) {
        if (d == null || currentChannelEntry == null) return 0;
        long now = clock > 0 ? clock : System.currentTimeMillis();
        EpgEvent current = null;
        for (EpgEvent event : d.getEvents(currentChannelEntry.service.serviceId)) {
            if (event.startMs > 0 && event.startMs <= now && event.endMs() > now) {
                current = event;
                break;
            }
        }
        if (current == null || current.endMs() <= current.startMs) return 0;
        long span = current.endMs() - current.startMs;
        return (int) Math.max(0, Math.min(1000, (now - current.startMs) * 1000 / span));
    }

    /** EPG tab: starts at "now"; entries are the still-running or upcoming programmes. */
    private void showEpgTab() {
        ServiceInfo guideService = currentChannelEntry != null ? currentChannelEntry.service : null;
        ServiceInfo live = liveService(hub.demux());
        long clock = hub.demux().getClockMs();
        long now = clock > 0 ? clock : System.currentTimeMillis();
        List<EpgEvent> events = new ArrayList<>();
        if (guideService != null && live != null) {
            // Only when the tuner is actually on this channel's mux: the guide tables of another
            // mux can carry a same-SID service's programmes (owner, 2026-11-06).
            for (EpgEvent event : hub.demux().getEvents(guideService.serviceId)) {
                if (event.startMs > 0 && event.endMs() > now) events.add(event);
            }
            events.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        }
        recyclerView.setAdapter(new EpgAdapter(this, events, clock));
        if (guideService == null) {
            setListChrome(0, R.drawable.ic_schedule,
                    getString(R.string.no_channel_tuned), getString(R.string.epg_no_channel_body),
                    getString(R.string.open_channels), 0);
        } else {
            int programs = events.size();
            setListChrome(programs,
                    R.drawable.ic_schedule,
                    getString(R.string.epg_empty_title),
                    getString(R.string.epg_empty_body),
                    getString(R.string.open_channels), 0);
        }
    }

    /** Favorites tab: the starred services in the reader's saved order. */
    private void showFavoritesTab() {
        List<ServiceInfo> favServices = new ArrayList<>();
        for (ChannelEntry ce : channelStore.channels()) {
            if (channelStore.favorites().contains(ChannelAdapter.favoriteKey(ce.service))) {
                favServices.add(ce.service);
            }
        }
        // One pass over the saved order, not an indexOf per comparison.
        java.util.Map<String, Integer> orderIndex = new java.util.HashMap<>();
        List<String> order = channelStore.order();
        for (int i = 0; i < order.size(); i++) orderIndex.put(order.get(i), i);
        favServices.sort((a, b) -> {
            Integer ia = orderIndex.get(ChannelAdapter.favoriteKey(a));
            Integer ib = orderIndex.get(ChannelAdapter.favoriteKey(b));
            int oa = ia == null ? Integer.MAX_VALUE : ia;
            int ob = ib == null ? Integer.MAX_VALUE : ib;
            if (oa != ob) return Integer.compare(oa, ob);
            return compareChannels(a, b);
        });
        ChannelAdapter favoritesAdapter = new ChannelAdapter(
                this, favServices, channelStore.favorites(),
                channelClickListener, (position, channel) -> favoritesReorder.enterAtFocused());
        recyclerView.setAdapter(favoritesAdapter);
        favoritesReorder.attach(favoritesAdapter);
        updateChannelSelection();
        setListChrome(favServices.size(), R.drawable.ic_favorite_border,
                getString(R.string.favorites_empty_title), getString(R.string.favorites_empty_body),
                getString(R.string.open_channels), 0);
    }

    /** Settings tab: consumer sections first, technical tools last. */
    private void showSettingsTab() {
        settingsAdapter = new SettingAdapter(this,
                SettingsController.build(this, this, dialogs, scan));
        recyclerView.setAdapter(settingsAdapter);
        hideListChrome();
    }

    /** Channels tab: sorted by virtual number (the scan console keeps discovery order). */
    private void showChannelsTab() {
        List<ServiceInfo> services = new ArrayList<>();
        for (ChannelEntry ce : channelsInListOrder()) services.add(ce.service);
        recyclerView.setAdapter(new ChannelAdapter(
                this, services, channelStore.favorites(), channelClickListener));
        updateChannelSelection();
        setListChrome(services.size(), R.drawable.ic_scan,
                getString(R.string.channels_empty_title), getString(R.string.channels_empty_body),
                getString(R.string.open_settings), 3);
    }

    /**
     * Empty state for the browse surfaces. The item count is on the tab labels now, so this only
     * decides whether the shared empty block shows; the action is a destination (a tab), never a
     * scan.
     */
    private void setListChrome(int count, int iconRes,
                               String title, String body, String actionLabel, int actionTab) {
    boolean empty = count <= 0;
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
        if (emptyState != null) emptyState.setVisibility(View.GONE);
    }

    private void selectAndPlayChannel(ChannelEntry entry) {
        if (sourceCannotTune()) {
            // A capture plays one multiplex and stays on it: there is no demodulator to retune, and
            // tearing the source down to look for one would be the churn this change removes.
            Toast.makeText(this, R.string.toast_capture_cannot_tune,
                    Toast.LENGTH_LONG).show();
            return;
        }
        bml.close();        // the cached page belongs to the old channel
        setRemotePanelOpen(false);           // and so did the pad that drove it
        currentChannelEntry = entry;
        channelStore.saveLast(storePrefs(), entry);
        showCurrentChannel();
        if (tunerLifecycle.canTune()) {
            playback.tuneAndPlay(entry);
        } else if (!hub.isTunerPresent()) {
            Toast.makeText(this, R.string.toast_no_tuner_reopen, Toast.LENGTH_LONG).show();
        } else {
            initTuner(); // plays currentChannelEntry once ready
        }
    }

    
    /**
     * The drawer handle hides after 6 s of idle and comes back on a touch or a key. The drawer
     * itself still auto-hides on its own 10 s timer; while it is open the handle stays put, because
     * it is also the close control.
     */
    private static final long SIDE_CHROME_IDLE_MS = 6_000;

    /** A+C is modal: its own X is the only control, so nothing may bring the handle back. */
    private boolean apluscChrome;

    private final Runnable hideSideChrome = () -> {
        if (btnOpenSidePanel == null) return;
        if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) return;
        btnOpenSidePanel.setVisibility(View.INVISIBLE);
    };

    private void revealSideChrome() {
        if (btnOpenSidePanel == null || sidePanel == null || apluscChrome) return;
        btnOpenSidePanel.setVisibility(View.VISIBLE);
        scheduleSideChromeHide();
    }

    private void scheduleSideChromeHide() {
        cancelSideChromeHide();
        android.view.View root = getWindow() == null ? null : getWindow().getDecorView();
        if (root != null) root.postDelayed(hideSideChrome, SIDE_CHROME_IDLE_MS);
    }

    private void cancelSideChromeHide() {
        android.view.View root = getWindow() == null ? null : getWindow().getDecorView();
        if (root != null) root.removeCallbacks(hideSideChrome);
    }

    private void updateSideMenuButtonPosition() {
        if (btnOpenSidePanel == null) return;
        if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
            btnOpenSidePanel.setVisibility(View.VISIBLE);
            cancelSideChromeHide();   // the drawer is open: the handle is its close control
            btnOpenSidePanel.setImageResource(R.drawable.ic_chevron_right);
            int sideWidthPx = sidePanel.getWidth();
            if (sideWidthPx <= 0) {
                sideWidthPx = Dims.dp(this,
                        getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 420 : 380);
            }
            int marginEndPx = sideWidthPx + Dims.dp(this, 18);
            if (btnOpenSidePanel.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) btnOpenSidePanel.getLayoutParams();
                params.setMarginEnd(marginEndPx);
                btnOpenSidePanel.setLayoutParams(params);
            }
        } else {
            btnOpenSidePanel.setImageResource(R.drawable.ic_chevron_left);
            int marginEndPx = Dims.dp(this, 8);
            if (btnOpenSidePanel.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) btnOpenSidePanel.getLayoutParams();
                params.setMarginEnd(marginEndPx);
                btnOpenSidePanel.setLayoutParams(params);
            }
            // The handle is still the drawer's only control, so it is visible on arrival - but it
            // now hides after the idle timer like every other control instead of sitting on the
            // picture forever (owner, 2026-11-06). A touch or any key brings it back.
            if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                btnOpenSidePanel.setVisibility(View.VISIBLE);
                scheduleSideChromeHide();
            } else if (sidePanel != null) {
                // This layout HAS a side drawer, so the chevron is its handle and the only control that
                // opens channels and Settings. It used to be tied to the info banner, and that banner
                // hides after 6 s - and never appears at all when no channel is tuned or no tuner is
                // attached. The LEAP-S1 (which sits with no dongle most of the time) was therefore a
                // black screen with nothing to press; found 2026-11-04.
                // Tested by the layout, not by screen width: the box reports 540 dp smallest width, so
                // the sw600dp layout is not the one it inflates, and a width test would miss it.
                btnOpenSidePanel.setVisibility(View.VISIBLE);
                scheduleSideChromeHide();
            } else {
                btnOpenSidePanel.setVisibility(View.GONE);
                cancelSideChromeHide();
            }
        }
    }

    /** A+C is modal in landscape: its X is the only visible dismiss control. */
    private void updateApluscChrome(boolean showing) {
        if (btnOpenSidePanel == null) return;
        apluscChrome = showing;
        if (showing) {
            btnOpenSidePanel.setVisibility(View.GONE);
            cancelSideChromeHide();
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
        sidePanelChrome.show();
    }

    /**
     * The panel is drawn over the window and the centre card ("No Signal", "Scrambled") is
     * centred on the whole window, so it used to run under the panel and the chevron sat on
     * top of the sentence. Push the card's centre into the free area left of the panel for
     * as long as the panel is up.
     */
    private void updateTuningInset() {
        if (sidePanelChrome != null) sidePanelChrome.updateTuningInset();
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
        sidePanelChrome.hide();
    }

    private void schedulePanelAutoHide() {
        sidePanelChrome.scheduleAutoHide();
    }

    /** Short OK on the watching surface: the banner first, then the same card the i opens. */
    private void handleInfoPress() {
        if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
            showWatchInfo();
        } else {
            showInfoBanner();
        }
    }

    @Override
    public void showInfoBanner() {
        showInfoBanner(6000);
    }

    void showInfoBanner(int durationMs) {
        infoBannerChrome.show(durationMs);
    }

    /** Fills the banner row for svc; visibility/timing are the chrome's. */
    private void fillBannerContent(ServiceInfo svc) {
        if (hudTvVirtual != null) hudTvVirtual.setText(svc.displayNumber());
        if (hudTvChannelName != null) {
            hudTvChannelName.setText(ChannelAdapter.displayName(svc));
        }
        statusHud.updateNetworkLine(svc, hub.demux());
        if (hudIvLogo != null) {
            android.graphics.drawable.Drawable logo = LogoHelper.getLogo(this, svc.name, svc.provider);
            if (logo != null) {
                hudIvLogo.setImageDrawable(logo);
                hudIvLogo.setVisibility(View.VISIBLE);
            } else {
                hudIvLogo.setVisibility(View.GONE);
            }
        }
        updateEpgBannerInfo(svc.serviceId);
    }

    private void updateEpgBannerInfo(int serviceId) {
        if (hudTvNowEvent == null || hudTvNextEvent == null) return;
    String[] guide = epgNowNext(serviceId);
        hudTvNowEvent.setText(getString(R.string.now_program, guide[0] != null ? guide[0] : getString(R.string.no_program_info)));
        hudTvNextEvent.setText(getString(R.string.next_program, guide[1] != null ? guide[1] : getString(R.string.no_program_info)));
    }

    /** {now, next} programme names, or nulls when the EIT has no usable entry. */
    private String[] epgNowNext(int serviceId) {
        TsDemux source = hub.demux();
        if (source == null) return new String[] {null, null};
        List<EpgEvent> events = source.getEvents(serviceId);
        long nowMs = source.getClockMs() > 0 ? source.getClockMs() : System.currentTimeMillis();
        return com.pbcnx.opendtvmod.ui.watch.EpgNowNext.names(events, nowMs);
    }

    void hideInfoBanner() {
        infoBannerChrome.hide();
    }

    void tuneNextChannel() {
        stepChannel(1);
    }

    void tunePreviousChannel() {
        stepChannel(-1);
    }

    /** One channel step, wrapping both ways; the lookup is the same for both directions. */
    private void stepChannel(int delta) {
        List<ChannelEntry> ordered = channelsInListOrder();
        int count = ordered.size();
        if (count == 0) return;
        int currentIdx = -1;
        if (currentChannelEntry != null) {
            String currentKey = ChannelStore.key(
                    currentChannelEntry.khz, currentChannelEntry.service.serviceId);
            currentIdx = com.pbcnx.opendtvmod.ui.watch.ChannelStep.indexOf(ordered, currentKey);
        }
        int next = com.pbcnx.opendtvmod.ui.watch.ChannelStep.nextIndex(count, currentIdx, delta);
        if (next >= 0) selectAndPlayChannel(ordered.get(next));
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        // Landscape keys that must run AFTER the view hierarchy had its chance: a focused list row
        // may legitimately consume a D-pad key, so the panel timer, the channel zap and the focus
        // climbs live here. Everything that depends on the surface state (INFO/BOOKMARK, BACK on
        // the card or the panel, OK) is routed in dispatchKeyEvent - the two used to route the same
        // keys with different guards, which is how onKeyDown ended up with dead branches.
        if (sidePanel == null) return super.onKeyDown(keyCode, event);
        schedulePanelAutoHide();
    boolean panelOpen = sidePanel.getVisibility() == View.VISIBLE;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!panelOpen) {
                    showSidePanel();
                    return true;
                }
                break;

            case android.view.KeyEvent.KEYCODE_DPAD_UP:
            case android.view.KeyEvent.KEYCODE_CHANNEL_UP:
                if (!panelOpen) {
                    tuneNextChannel();
                    return true;
                }
                // From the first row, UP climbs back to the tab strip.
                View row = recyclerView != null ? recyclerView.getFocusedChild() : null;
                if (tabLayout != null && row != null
                        && recyclerView.getChildAdapterPosition(row) == 0) {
                    tabLayout.requestFocus();
                    return true;
                }
                break;

            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
            case android.view.KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (!panelOpen) {
                    tunePreviousChannel();
                    return true;
                }
                if (tabLayout != null && tabLayout.hasFocus()) {
                    recyclerView.requestFocus();
                    return true;
                }
                break;

            case android.view.KeyEvent.KEYCODE_BACK:
                // The panel's own BACK is routed with the other surface keys; this covers the
                // banner that no view owns.
                if (!panelOpen && infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                    hideInfoBanner();
                    return true;
                }
                break;

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** "2.1" as a sortable number: integer part plus hundredths, out-of-range last. */
    private static double virtualKey(String v) {
        try {
            String[] p = v.split("\\.");
            return Integer.parseInt(p[0]) + (p.length > 1 ? Integer.parseInt(p[1]) / 100.0 : 0);
        } catch (RuntimeException e) {
            return 9999;
        }
    }

    private static final int GROUP_NUMBERED = 0;
    private static final int GROUP_ZERO_SUB = 1;
    private static final int GROUP_FALLBACK = 2;
    private static final int GROUP_UNNUMBERED = 3;

    /**
     * The order the Channels tab shows, and the order CH+/CH- follows. Owner, 2026-11-06:
     * properly numbered channels first (2, 5.1, 7.x ... 45), then the heuristic channel-0
     * subchannels (0.1 ... 0.4), then the fallback numbers derived from the mux ("~19.1" ...),
     * then anything left without a number at all.
     */
    private static int compareChannels(ServiceInfo a, ServiceInfo b) {
        int ga = channelGroup(a);
        int gb = channelGroup(b);
        if (ga != gb) return Integer.compare(ga, gb);
        if (ga == GROUP_UNNUMBERED) return 0;   // stable: discovery order inside that group
        return Double.compare(channelKey(a), channelKey(b));
    }

    private static double channelKey(ServiceInfo s) {
        return s.hasVirtualNumber() ? virtualKey(s.virtualNumber) : virtualKey(s.fallbackNumber);
    }

    private static int channelGroup(ServiceInfo s) {
        if (!s.hasVirtualNumber()) {
            return s.fallbackNumber == null || s.fallbackNumber.isEmpty()
                    ? GROUP_UNNUMBERED : GROUP_FALLBACK;
        }
        return virtualKey(s.virtualNumber) < 1 ? GROUP_ZERO_SUB : GROUP_NUMBERED;
    }

    /** The channel store in the one order the screens and the zap keys use. */
    private List<ChannelEntry> channelsInListOrder() {
        List<ChannelEntry> list = new ArrayList<>(channelStore.channels());
        list.sort((a, b) -> compareChannels(a.service, b.service));
        return list;
    }

    /** The channel store's prefs seam: the app's one preferences file. */
    private ChannelStore.Storage storePrefs() {
        return ChannelStore.prefs(this);
    }

    /** The developer surface's process state (door, handlers, demux hand-off). */
    private DeveloperSurface developer() {
        return App.developer(this);
    }

    @Override
    public void onPreempted() {
        // Live Channels (or another screen) took the tuner: stop cleanly, re-acquire on next start.
        scan.cancel();
        setTunerCanTune(false);
        playGen.incrementAndGet();
        ui.post(() -> {
        player.stop();
        player.clearMediaItems();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scan.cancel();
        playGen.incrementAndGet();
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        recorder.release();
        scan.dismiss();
        bml.close();
        if (captions != null) captions.destroy();
        player.removeListener(playback.playerListener());
        playerView.setPlayer(null);
        player.release();
        // The Developers screen is a different activity and its handlers close over this one:
        // retire them with the instance, and stop pinning the demux the watch screen was reading.
        developer().unregister(
                DeveloperActions.CLEAR_CHANNELS,
                DeveloperActions.RESET_FAVORITES,
                DeveloperActions.RESET_ALL);
        developer().setDemux(null);
        developer().setCaptionProbe(null);
        developer().setBmlState(null);
        hub.release(this);   // detaches the sink, powers the dongle down after the idle delay
        recyclerView.setAdapter(null);
        pipe.close();
    }
}
