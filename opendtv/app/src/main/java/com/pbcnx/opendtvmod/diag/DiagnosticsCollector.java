package com.pbcnx.opendtvmod.diag;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Environment;

import com.pbcnx.opendtvmod.App;
import com.pbcnx.opendtvmod.BuildConfig;
import com.pbcnx.opendtvmod.BuildStamp;
import com.pbcnx.opendtvmod.player.DecoderPrefs;
import com.pbcnx.opendtvmod.source.TunerProvider;
import com.pbcnx.opendtvmod.source.TunerRegistry;
import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.tuner.ChannelEntry;
import com.pbcnx.opendtvmod.tuner.TunerHub;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The only Android-aware part of the diagnostics export: it reads the process's already-existing
 * state (build stamp, tuner hub, decoder prefs, channel store, session ring) into a
 * {@link DiagnosticsDump.Input}. The layout, the redaction and the opt-in gates stay in the pure
 * classes, so this file has nothing to test on the JVM.
 *
 * <p>The two opt-in flags are copied into the input as well as being applied here: the collector
 * leaves the sensitive values out of the input, and {@link DiagnosticsDump#build} refuses to print
 * them unless the flag is set. One switch, checked twice.</p>
 */
public final class DiagnosticsCollector {

    private DiagnosticsCollector() {
    }

    /**
     * @param includeDeviceModel  the user's first opt-in (device model/manufacturer), default off
     * @param includeChannelData  the user's second opt-in (channel names/frequencies), default off
     */
    public static DiagnosticsDump.Input collect(Context context, boolean includeDeviceModel,
                                                boolean includeChannelData) {
        Context ctx = context.getApplicationContext();
        DiagnosticsDump.Input in = new DiagnosticsDump.Input();
        in.generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        in.buildStamp = BuildConfig.VERSION_NAME;
        in.versionCode = BuildConfig.VERSION_CODE;
        in.commit = BuildStamp.commit();
        in.androidRelease = "Android " + Build.VERSION.RELEASE;
        in.sdkInt = Build.VERSION.SDK_INT;
        in.abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";

        in.includeDeviceModel = includeDeviceModel;
        if (includeDeviceModel) {
            in.deviceManufacturer = Build.MANUFACTURER;
            in.deviceModel = Build.MODEL;
        }

        in.includeChannelData = includeChannelData;
        try {
            collectTuner(ctx, in);
        } catch (RuntimeException ignored) {
            // Reading the hub must never be what stops a diagnostics export; the section says so.
        }
        collectDecoderPrefs(ctx, in);

        if (includeChannelData) {
            for (ChannelEntry ce : App.channels(ctx).channels()) {
                in.channelLines.add(channelLine(ce));
            }
        }

        in.ring = App.logRing().snapshot();

        // The paths this process actually uses, so the redactor can replace them literally even
        // when the device's storage layout is unusual.
        addPath(in, ctx.getExternalFilesDir(null));
        addPath(in, ctx.getFilesDir());
        addPath(in, ctx.getCacheDir());
        try {
            addPath(in, new File(Environment.getExternalStorageDirectory(), "OpenDTV"));
        } catch (RuntimeException ignored) {
            // no shared storage on this device; the app-specific paths above still apply
        }
        return in;
    }

    private static void collectTuner(Context ctx, DiagnosticsDump.Input in) {
        TunerHub hub = TunerHub.get(ctx);
        in.tunerName = hub.sourceName() == null ? "" : hub.sourceName();
        UsbDevice device = hub.findDevice();
        if (device != null) {
            in.tunerUsbId = String.format(Locale.US, "%04x:%04x",
                    device.getVendorId(), device.getProductId());
            if (in.tunerName.isEmpty()) {
                TunerProvider provider = TunerRegistry.find(device);
                in.tunerName = provider == null ? "unrecognised USB tuner" : provider.name();
            }
        }
        in.tunerFailure = hub.failure();
        in.signalSnapshot = signalText(hub.signal(), hub.isOpen());
        if (in.includeChannelData) in.frequencyKhz = hub.currentKhz();
    }

    private static void collectDecoderPrefs(Context ctx, DiagnosticsDump.Input in) {
        in.decoderManual = DecoderPrefs.manual(ctx);
        in.decoderVideoSoftware = DecoderPrefs.videoSoftware(ctx);
        in.decoderAudioSoftware = DecoderPrefs.audioSoftware(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences(App.PREFS, Context.MODE_PRIVATE);
        String map = prefs.getString(DecoderPrefs.KEY_PER_MUX, "");
        in.learnedMuxMap = map == null ? "" : map;
    }

    /** "557000 kHz  21.1  TV5 (GMA)": only ever collected when the user opted in. */
    private static String channelLine(ChannelEntry ce) {
        StringBuilder line = new StringBuilder();
        line.append(ce.khz).append(" kHz  ").append(ce.service.displayNumber());
        String name = ce.service.name == null ? "" : ce.service.name.trim();
        String provider = ce.service.provider == null ? "" : ce.service.provider.trim();
        if (!name.isEmpty()) line.append("  ").append(name);
        if (!provider.isEmpty()) line.append(" (").append(provider).append(')');
        return line.toString();
    }

    private static String signalText(TunerSignal signal, boolean sourceOpen) {
        if (signal == null) {
            return sourceOpen ? "no signal report (file or replay source)" : "";
        }
        return "locked=" + (signal.locked ? "yes" : "no")
                + " strength=" + percent(signal.strengthPercent)
                + " quality=" + percent(signal.qualityPercent)
                + " snr=" + (Double.isNaN(signal.snrDb) ? "n/a"
                        : String.format(Locale.US, "%.1f dB", signal.snrDb))
                + " dbm=" + (signal.dbm == Integer.MIN_VALUE ? "n/a" : signal.dbm + " dBm")
                + " ber=" + (Double.isNaN(signal.ber) ? "n/a"
                        : String.format(Locale.US, "%.1e", signal.ber));
    }

    private static String percent(int value) {
        return value < 0 ? "n/a" : value + "%";
    }

    private static void addPath(DiagnosticsDump.Input in, File file) {
        if (file == null) return;
        String path = file.getAbsolutePath();
        if (!in.appPaths.contains(path)) in.appPaths.add(path);
    }
}
