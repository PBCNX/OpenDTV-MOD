package com.pbcnx.opendtvmod.analyzer;

import android.hardware.usb.UsbDevice;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

import com.pbcnx.opendtvmod.source.FileTsSource;
import com.pbcnx.opendtvmod.source.TunerRegistry;
import com.pbcnx.opendtvmod.source.TsSource;
import com.pbcnx.opendtvmod.tuner.TunerHub;

/**
 * Where the analyzer gets its transport stream.
 *
 * <p>The dongle belongs to {@link TunerHub}, the process's one tuner owner: the analyzer claims
 * ownership like the watch screen and the Live Channels session do, and reads the hub's source and
 * demux. It used to open the USB device itself - its own permission prompt, firmware bring-up and
 * demux feed - so two pipelines existed and the device's exclusivity depended on activity lifecycle
 * order. A capture file is played through the hub too ({@code playSource}).</p>
 */
public final class SourceManager {
    private volatile int startTuneKhz = -1;


    /** The analyzer's start frequency when the caller knows none: UHF 18. */
    private static final int DEFAULT_UHF = 18;

    private final AnalyzerActivity a;
    private final AnalyzerState state;
    private final StatusSink status;
    private TunerHub hub;

    public SourceManager(AnalyzerActivity activity, AnalyzerState state, StatusSink status) {
        this.a = activity;
        this.state = state;
        this.status = status;
    }

    /** The hub this analyzer reads through. */
    public TunerHub hub() {
        if (hub == null) hub = com.pbcnx.opendtvmod.App.hub(a.getApplicationContext());
        return hub;
    }

    // ------------------------------------------------------------------ sources

    public void startFile(Uri uri) {
        final Uri u = uri;
        FileTsSource.Opener opener = new FileTsSource.Opener() {
            @Override public InputStream open() throws IOException {
                return a.getContentResolver().openInputStream(u);
            }
        };
        // Unthrottled: analysis should finish as fast as the CPU allows.
        play(new FileTsSource("File: " + uri.getLastPathSegment(), opener, 0L, false));
    }

    /** The dev hook: play a capture, paced at the capture's own rate by the source itself. */
    public void startSource(TsSource source) {
        play(source);
    }

    public void connectUsb() {
        if (isTunerSource(state.source)) return;   // already running or initialising
        new Thread(() -> {
            UsbDevice d = hub().findDevice();
            if (d == null) {
                state.ui.post(() -> status.setStatus("No supported tuner found. Supported: "
                        + TunerRegistry.supportedNames() + "."));
                return;
            }
            if (!hub().ensurePermission(60_000)) {
                state.ui.post(() -> status.setStatus(hub().lastMessage() + " - tap USB to retry."));
                return;
            }
            int khz = startTuneKhz;
            if (khz <= 0) khz = Math.max(state.tuner.currentKhz(), 0);
            if (khz <= 0) khz = TunerControl.uhfKhz(DEFAULT_UHF);
            startTuneKhz = khz;
            hub().acquire(a);
            if (!hub().ensureTuned(khz, a::isDestroyed)) {
                state.ui.post(() -> status.setStatus(hub().lastError() != null
                        ? hub().lastError() : hub().lastMessage()));
                return;
            }
            state.ui.post(() -> adopt("tuner"));
        }, "AnalyzerUsb").start();
    }

    /** The permission broadcast's entry point: the device is chosen, the hub does the rest. */
    public static boolean isTuner(UsbDevice d) {
        return TunerRegistry.find(d) != null;
    }

    public static boolean isTunerSource(TsSource s) {
        return com.pbcnx.opendtvmod.tuner.TunerHub.isTunerSource(s);
    }

    // ------------------------------------------------------------------ hub adoption

    private void play(final TsSource source) {
        new Thread(() -> {
            hub().acquire(a);
            if (!hub().playSource(source, -1)) {
                state.ui.post(() -> status.setStatus(hub().lastMessage()));
                return;
            }
            state.ui.post(() -> adopt(source.name()));
        }, "AnalyzerSource").start();
    }

    /** Points the analyzer at the hub's live source and demux and restarts the player view. */
    private void adopt(String what) {
        state.player.stop();
        state.player.clearMediaItems();
        state.pipe.clear();
        state.demux = hub().demux();
        state.source = hub().source();
        state.selectedServiceId = -1;
        state.serviceListKey = "";
        state.sawFirstData = false;
        status.setStatus(what + ": starting ...");
    }

    /** Playback installs a sink on the hub's demux, so the analyzer must own the hub first. */
    public void claim() {
        hub().acquire(a);
    }

    public void stopSource() {
        state.source = null;
        hub().release(a);
    }
}
