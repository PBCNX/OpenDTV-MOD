package com.pbcnx.opendtvmod.dev;

import androidx.annotation.Nullable;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.LongPredicate;

import com.pbcnx.opendtvmod.BuildConfig;
import com.pbcnx.opendtvmod.firmware.FirmwareStore;
import com.pbcnx.opendtvmod.player.DecoderPrefs;
import com.pbcnx.opendtvmod.tuner.ChannelFile;

/**
 * The bench hooks: the flows the Settings rows run, driven by adb extras instead of taps.
 *
 * <p>Each helper takes the activity/context it borrowed and a callback for the state the activity
 * still owns. Everything here is reached from a cold start or from a re-delivered intent (MainActivity
 * is singleTop), and every attempt appends one line to {@code files/bench-last.txt}, because on the
 * bench phone logcat shows none of the app's own log tags.</p>
 */
public final class BenchHooks {

    /** The tag MainActivity logs under: bench notes are grepped for it. */
    private static final String TAG = "OpenDTV-Main";

    private BenchHooks() {
    }

    /** Every privileged adb extra, decoded once. Empty values on release builds. */
    public static final class LaunchExtras {
        @Nullable public final String tsPath;
        public final int tuneKhz;
        public final boolean openAnalyzer;
        public final int tab;           // -1 = no tab override
        public final boolean recordWhole;
        public final boolean sweep;
        public final boolean devOptions;
        @Nullable public final String importChannels;
        @Nullable public final String exportChannels;
        public final boolean replaceChannels;
        @Nullable public final String importFirmware;
        @Nullable public final String importFirmwareFolder;
        @Nullable public final String videoMode;
        @Nullable public final Boolean hwVideo;
        @Nullable public final Boolean hwAudio;

        private LaunchExtras(String tsPath, int tuneKhz, boolean openAnalyzer, int tab,
                             boolean recordWhole, boolean sweep, boolean devOptions,
                             String importChannels, String exportChannels, boolean replaceChannels,
                             String importFirmware, String importFirmwareFolder, String videoMode,
                             Boolean hwVideo, Boolean hwAudio) {
            this.tsPath = tsPath;
            this.tuneKhz = tuneKhz;
            this.openAnalyzer = openAnalyzer;
            this.tab = tab;
            this.recordWhole = recordWhole;
            this.sweep = sweep;
            this.devOptions = devOptions;
            this.importChannels = importChannels;
            this.exportChannels = exportChannels;
            this.replaceChannels = replaceChannels;
            this.importFirmware = importFirmware;
            this.importFirmwareFolder = importFirmwareFolder;
            this.videoMode = videoMode;
            this.hwVideo = hwVideo;
            this.hwAudio = hwAudio;
        }
    }

    /** Reads the debug-only extras in one place; release builds get an inert object. */
    public static LaunchExtras launchExtras(Intent intent) {
        if (!BuildConfig.DEBUG || intent == null) {
            return new LaunchExtras(null, 0, false, -1, false, false, false,
                    null, null, false, null, null, null, null, null);
        }
        String tab = intent.getStringExtra("tab");
        int tabIndex = "settings".equals(tab) ? 3 : "epg".equals(tab) ? 1 : -1;
        return new LaunchExtras(
                intent.getStringExtra("ts_path"),
                intent.getIntExtra("tune_khz", 0),
                intent.getBooleanExtra("dev_open_analyzer", false),
                tabIndex,
                intent.getBooleanExtra("record_whole", false),
                intent.getBooleanExtra("sweep", false),
                intent.getBooleanExtra("dev_options", false),
                intent.getStringExtra("import_channels"),
                intent.getStringExtra("export_channels"),
                intent.getBooleanExtra("replace", false),
                intent.getStringExtra("import_firmware"),
                intent.getStringExtra("import_firmware_folder"),
                intent.hasExtra("video_mode") ? intent.getStringExtra("video_mode") : null,
                intent.hasExtra("hw_video") ? intent.getBooleanExtra("hw_video", false) : null,
                intent.hasExtra("hw_audio") ? intent.getBooleanExtra("hw_audio", false) : null);
    }

    /** Removes every privileged extra this class decoded, so a re-delivered intent cannot re-run it. */
    public static void consume(Intent intent) {
        if (intent == null) return;
        for (String key : KEYS) {
            intent.removeExtra(key);
        }
    }

    private static final String[] KEYS = {"ts_path", "tune_khz", "tab", "record_whole", "sweep",
            "dev_open_analyzer", "dev_options", "import_channels", "export_channels", "replace",
            "import_firmware", "import_firmware_folder", "video_mode", "hw_video", "hw_audio"};

    /** The state MainActivity still owns when an adb-driven channel-file action lands. */
    public interface Host {
        /** Applies a parsed channel file to the activity's channel list. */
        void benchApplyImport(ChannelFile.Result result, boolean replace);

        /** The activity's channel list, for the export hook. */
        List<com.pbcnx.opendtvmod.tuner.ChannelEntry> benchChannels();

        /** The activity's favourite order, for the export hook. */
        List<String> benchFavorites();

        /** The UHF sweep, which needs the activity's own TunerHub ownership. */
        void benchSweep(LaunchExtras extras);
    }

    /**
     * One bench line: the log tag the bench greps, and the file marker that survives a phone whose
     * logcat shows none of our tags. The text exists once, so the two witnesses cannot drift.
     */
    private static void note(Context context, String line) {
        Log.i(TAG, line);
        benchMarker(context, line);
    }

    /**
     * A free-form bench line from anywhere in the app, for the decisions that have to be explained
     * from evidence rather than guessed (first user: the ABS-CBN service pick, 0.2.0). Debug builds
     * only, one line in {@code files/bench-last.txt}, same witness as every other bench path.
     */
    public static void marker(Context context, String line) {
        if (!BuildConfig.DEBUG || context == null) return;
        note(context, line);
    }

    /**
     * A bench path: absolute means the app's external dir or anywhere it may read; a bare name means
     * its own internal files dir, which is the one place {@code adb shell run-as} can read back without a
     * document picker in the way.
     */
    public static File resolveBenchFile(Context context, String path) {
        if (path != null && path.startsWith("/")) return new File(path);
        return new File(context.getFilesDir(), path == null ? "channels.json" : path);
    }

    /** Reads a whole file; the firmware import and the folder import both need exactly this. */
    private static byte[] readFile(File file) throws java.io.IOException {
        return java.nio.file.Files.readAllBytes(file.toPath());
    }

    /**
     * The debug ts_path capture bootstrap, once for both screens: parse the extra, open the file as a
     * paced source after one layout pass, then run the caller's follow-up. Callers pass their own
     * pacing and their own follow-up action, so the two screens cannot drift apart silently.
     */
    public static void runCapture(Activity activity, LaunchExtras extras, long bytesPerSec, boolean looping,
                                  java.util.function.Consumer<com.pbcnx.opendtvmod.source.FileTsSource> open,
                                  long followUpDelayMs, Runnable followUp) {
        if (!BuildConfig.DEBUG || extras.tsPath == null) return;
        String path = extras.tsPath;
        final File f = new File(path);
        android.view.View decor = activity.getWindow().getDecorView();
        decor.postDelayed(() -> {
            com.pbcnx.opendtvmod.source.FileTsSource.Opener opener = () -> new java.io.FileInputStream(f);
            open.accept(new com.pbcnx.opendtvmod.source.FileTsSource("File: " + f.getName(), opener, bytesPerSec, looping));
            decor.postDelayed(followUp, followUpDelayMs);
        }, 800);
    }

    /** Bench hook: the export flow without a picker, to a path the app can write. */
    public static void exportToPath(Context context, String path,
                                    List<com.pbcnx.opendtvmod.tuner.ChannelEntry> allChannels,
                                    List<String> favoriteOrder) {
        File f = resolveBenchFile(context, path);
        try {
            List<ChannelFile.Entry> entries = ChannelFile.entriesFrom(allChannels);
            String json = ChannelFile.toJson(entries, favoriteOrder, new String[][] {
                    {"applicationId", context.getPackageName()}});
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            note(context, "export: " + entries.size() + " channels, " + favoriteOrder.size()
                    + " favourites -> " + f + " (" + f.length() + " B)");
        } catch (Exception e) {
            note(context, "export: FAILED " + e);
        }
    }

    /**
     * The channel-file bench hooks, taken from a cold start OR a re-delivered intent.
     *
     * <p>They used to be read only in {@code onCreate}, and MainActivity is {@code singleTop}: an
     * {@code am start} against a live app lands in {@code onNewIntent} and the extras were dropped
     * silently. That is why this hook worked on the box (app dead, cold start) and failed on the phone
     * all day (app alive) with no error anywhere. Each attempt now also appends one line to
     * {@code files/bench-last.txt}, because on this phone logcat shows none of our own log tags.</p>
     */
    public static void applyChannelFileBenchHooks(Context context, LaunchExtras extras, Handler ui, Host host) {
        if (!BuildConfig.DEBUG) return;
        if (extras.importChannels != null) {
            final String importPath = extras.importChannels;
            final boolean replace = extras.replaceChannels;
            note(context, "import requested: " + importPath + " replace=" + replace);
            ui.post(() -> importFromPath(context, importPath, replace, host));
        } else if (extras.exportChannels != null) {
            final String exportPath = extras.exportChannels;
            note(context, "export requested: " + exportPath);
            ui.post(() -> exportToPath(context, exportPath, host.benchChannels(), host.benchFavorites()));
        }
    }

    /**
     * One line into {@code files/bench-last.txt}, bench builds only. This phone reports none of our own
     * logcat tags while other apps' logs appear, so a silent bench failure had no witness at all; a file
     * in the app's own storage is readable with {@code run-as <pkg> cat files/bench-last.txt}. Rotated at
     * 64 KB so a long session cannot grow it without bound.
     */
    public static void benchMarker(Context context, String line) {
        if (!com.pbcnx.opendtvmod.BuildConfig.DEBUG) return;
        try {
            File f = new File(context.getFilesDir(), "bench-last.txt");
            if (f.length() > 64 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            String stamp = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new java.util.Date());
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(stamp + "  " + line + "\n");
            }
        } catch (Exception ignored) {
            // a diagnostic that broke a bench action would be its own bug
        }
    }

    /**
     * One line naming the moment of a HUD tick, bench builds only, into {@code files/bench-ticks.txt}.
     * The watch screen's chips, status row and EWBS strip are all refreshed by that one-per-second
     * tick, and a ticker that stopped re-posting itself looks healthy in any single screenshot — 0.1.13
     * shipped exactly that. Two reads of this file five seconds apart settle it without a tuner: the
     * count must grow, roughly once a second. Rotated at 16 KB.
     */
    public static void tickMarker(Context context) {
        if (!com.pbcnx.opendtvmod.BuildConfig.DEBUG) return;
        try {
            File f = new File(context.getFilesDir(), "bench-ticks.txt");
            if (f.length() > 16 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            String stamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new java.util.Date());
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(stamp + "\n");
            }
        } catch (Exception ignored) {
            // a diagnostic that broke the ticker would be its own bug
        }
    }

    /** Bench hook: the import flow without a picker. */
    public static void importFromPath(Context context, String path, boolean replace, Host host) {
        try {
            File f = resolveBenchFile(context, path);
            note(context, "import: reading " + f + " (" + (f.isFile() ? f.length() + " B" : "MISSING") + ")");
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            ChannelFile.Result r = ChannelFile.parse(text);
            if (!r.ok()) {
                note(context, "import: REFUSED " + r.error);
                return;
            }
            note(context, "import: parsed " + r.entries.size() + " entries, " + r.favorites.size()
                    + " favourites, " + r.skipped + " skipped, replace=" + replace);
            host.benchApplyImport(r, replace);
            note(context, "import: OK now " + host.benchChannels().size() + " channels, "
                    + host.benchFavorites().size() + " favourites");
        } catch (Exception e) {
            note(context, "import: FAILED " + e);
        }
    }

    /**
     * The same firmware import without the picker, for the bench: {@code --es import_firmware <absolute path>}.
     * adb can push the file into the app's own external directory, which the app may read without any
     * storage permission, so this works on a device whose document picker cannot be driven remotely.
     */
    public static void applyFirmwareBenchHook(Context context, LaunchExtras extras, Executor worker) {
        if (!BuildConfig.DEBUG || extras.importFirmware == null) return;
        final String path = extras.importFirmware;
        worker.execute(() -> {
            String message;
            try {
                File file = new File(path);
                byte[] data = readFile(file);
                FirmwareStore.Image image = FirmwareStore.importBytes(context, data);
                message = image != null
                        ? "imported " + image.name + " (" + image.device + ")"
                        : "refused: " + data.length + " bytes, sha256 "
                                + FirmwareStore.sha256Hex(data);
            } catch (java.io.IOException | RuntimeException e) {
                message = "failed: " + e;
            }
            Log.i(TAG, "firmware bench import " + path + " -> " + message);
        });
    }

    /**
     * The folder import without the picker, for the bench:
     * {@code --es import_firmware_folder <absolute path>}. Same size filter and SHA-256 match as the
     * folder picker; one log line per file, no UI.
     */
    public static void applyFirmwareFolderBenchHook(Context context, LaunchExtras extras, Executor worker,
                                                    LongPredicate isImageSize) {
        if (!BuildConfig.DEBUG || extras.importFirmwareFolder == null) return;
        final String path = extras.importFirmwareFolder;
        worker.execute(() -> {
            File dir = new File(path);
            File[] files = dir.listFiles();
            if (files == null) {
                Log.i(TAG, "folder bench import " + path + " -> not a folder");
                return;
            }
            int imported = 0;
            int refused = 0;
            int ignored = 0;
            for (File f : files) {
                if (!f.isFile()) continue;
                if (!isImageSize.test(f.length())) {
                    ignored++;
                    continue;
                }
                byte[] data;
                try {
                    data = readFile(f);
                } catch (java.io.IOException e) {
                    Log.i(TAG, "folder bench import " + f.getName() + " -> read failed: " + e);
                    continue;
                }
                try {
                    FirmwareStore.Image image = FirmwareStore.importBytes(context, data);
                    if (image != null) {
                        imported++;
                        Log.i(TAG, "folder bench import " + f.getName() + " -> " + image.device);
                    } else {
                        refused++;
                        Log.i(TAG, "folder bench import " + f.getName() + " -> refused");
                    }
                } catch (java.io.IOException e) {
                    refused++;
                    Log.i(TAG, "folder bench import " + f.getName() + " -> failed: " + e);
                }
            }
            Log.i(TAG, "folder bench import " + path + " -> imported=" + imported
                    + " refused=" + refused + " ignored=" + ignored);
        });
    }

    /**
     * The Decoder section as adb extras, so one launch can test any combination without touching the
     * screen: {@code --es video_mode sw|hw|auto} (a STRING, not a boolean) and
     * {@code --ez hw_video true --ez hw_audio false} for the scripts that already use them. All three
     * write through {@link DecoderPrefs}, which is now the only decoder truth: an extra that names a
     * mode sets the Manual gate, so nothing corrects it behind the caller's back.
     */
    public static void applyDecoderBenchHooks(Context context, LaunchExtras extras) {
        if (!BuildConfig.DEBUG) return;
        if (extras.videoMode == null && extras.hwVideo == null && extras.hwAudio == null) return;
        if (extras.videoMode != null) {
            String m = extras.videoMode;
            if ("hw".equals(m) || "sw".equals(m)) {
                DecoderPrefs.setVideoSoftware(context, "sw".equals(m));
                DecoderPrefs.setManual(context, true);
            } else {
                DecoderPrefs.setManual(context, false);
            }
        }
        if (extras.hwVideo != null) {
            DecoderPrefs.setVideoSoftware(context, !extras.hwVideo);
            DecoderPrefs.setManual(context, true);
        }
        if (extras.hwAudio != null) {
            DecoderPrefs.setAudioSoftware(context, !extras.hwAudio);
            DecoderPrefs.setManual(context, true);
        }
        Log.i(TAG, "decoder bench hooks: manual=" + DecoderPrefs.manual(context)
                + " videoSoftware=" + DecoderPrefs.videoSoftware(context)
                + " audioSoftware=" + DecoderPrefs.audioSoftware(context));
    }

    /**
     * Every bench hook for one intent, so a cold start and a re-delivered intent run the same set.
     *
     * <p>Before this, onCreate called all five and onNewIntent called only the channel-file one, so
     * four extras were dropped silently whenever the app was already alive - the exact class of bug
     * the channel-file hook's javadoc records. Debug builds only: a release app must not act on
     * privileged extras, and {@code resolveBenchFile} accepts absolute paths.</p>
     */
    public static void applyAll(Context context, LaunchExtras extras, Handler ui, Executor worker,
                                LongPredicate isImageSize, Host host) {
        if (!BuildConfig.DEBUG) return;
        applyChannelFileBenchHooks(context, extras, ui, host);
        applyFirmwareBenchHook(context, extras, worker);
        applyFirmwareFolderBenchHook(context, extras, worker, isImageSize);
        applyDecoderBenchHooks(context, extras);
        host.benchSweep(extras);
    }
}
