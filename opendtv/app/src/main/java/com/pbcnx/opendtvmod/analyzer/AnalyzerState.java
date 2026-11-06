package com.pbcnx.opendtvmod.analyzer;

import com.pbcnx.opendtvmod.ts.pipe.TsPipe;
import com.pbcnx.opendtvmod.ts.pipe.ServiceFilter;

import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.List;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.bml.BmlController;
import com.pbcnx.opendtvmod.bml.RemotePanel;
import com.pbcnx.opendtvmod.source.TsSource;
import com.pbcnx.opendtvmod.ts.AnalyzerSnapshot;
import com.pbcnx.opendtvmod.ts.TsDemux;
import com.pbcnx.opendtvmod.ui.CaptionOverlay;

/**
 * The analyzer's shared model: the source pipeline (hub demux, player, pipe, filter), the selected
 * service and the tab, and the panels themselves. It used to be a 63-field bag with every panel's
 * views in it too; each panel now owns its own views and fields, and only the state they all read
 * and write lives here. The activity owns one instance and hands it to the panels.
 */
public final class AnalyzerState {

    public static final String TAG = "OpenDTV-Analyzer";
public static final int TAB_SIGNAL = 0;
public static final int TAB_SERVICES = 1;
public static final int TAB_PIDS = 2;
public static final int TAB_TABLES = 3;
public static final int TAB_EPG = 4;
public static final int TAB_DATACAST = 5;
public static final int TAB_GRAPH = 6;
public static final int TAB_LOGS = 7;
public static final int TAB_REMOTE = 8;
    /** The app prefs file; the analyzer no longer keeps a second one. */
    public static final String PREFS = com.pbcnx.opendtvmod.App.PREFS;
    public static final int[] TAB_LABEL_IDS = {R.id.tab_signal, R.id.tab_services, R.id.tab_pids,
            R.id.tab_tables, R.id.tab_epg, R.id.tab_datacast, R.id.tab_graph, R.id.tab_logs, R.id.tab_remote};

    public static final class LogEntry {
        public final String time;
        public final String text;
        public final int level;

        public LogEntry(String time, String text, int level) {
            this.time = time;
            this.text = text;
            this.level = level;
        }
    }

    /** A multiplex (one RF channel) found by a scan or tuned by hand. */
    public static final class Mux {
        public final int khz;
        public final String label;

        public Mux(int khz, String label) {
            this.khz = khz;
            this.label = label;
        }
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    final TsPipe pipe = new TsPipe(8 * 1024 * 1024);

    volatile TsDemux demux = new TsDemux();
    TsSource source;
    /** Set by the first packet, so the analyzer's status line stops saying 'starting'. */
    volatile boolean sawFirstData;
    /** The filter feeding the player; it owns the caption probe this screen reports on. */
    ServiceFilter serviceFilter;
    ExoPlayer player;

    PlayerView playerView;
    CaptionOverlay captionOverlay;

    // ---- data broadcast (BML) viewer + on-screen remote ----
    BmlController bml;
    RemotePanel remotePanel;
    int bmlServiceId = -1;
    float[] videoFrac = null;      // null = video fills the frame





    // ---- phone preview + tab swipe ----
    ViewGroup contentFrame;

    final List<Mux> muxes = new java.util.ArrayList<>();
    int currentMuxIndex = -1;
    int currentTunedKhz = 0;

    int currentTab = TAB_SIGNAL;
    int selectedServiceId = -1;
    String serviceListKey = "";
    AnalyzerSnapshot lastSnapshot = new AnalyzerSnapshot();

    /** The Logs tab owns the ring; the other panels report lines through it. */
    /** The activity's per-tick refresh, for panels that change a setting and repaint. */
    Runnable refresh;
    /** Tuning, for the source and remote panels that step the band. */
    TunerControl tuner;

    /** Nothing to initialise: fields are set by the screen as data arrives. */
    public AnalyzerState() {
    }
}
