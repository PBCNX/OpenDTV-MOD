package com.pbcnx.opendtvmod.player;

import androidx.annotation.Nullable;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The Decoder settings: one gate, two overrides, and a per-mux memory.
 *
 * <pre>
 *   MANUAL off  (Auto)    hardware first, software when the hardware produces no frame;
 *                         the winning choice is remembered per multiplex frequency
 *   MANUAL on   (Manual)  the viewer decides, independently for video and audio
 *                           videoSoftware / audioSoftware: false = hardware, true = software
 * </pre>
 *
 * <p>The per-mux memory exists because a decoder that fails on one broadcaster can be right on the
 * next: TV5's stream is rejected outright by the software decoder while GMA and IBC are fine, so the
 * choice belongs to the multiplex, not to the app.</p>
 *
 * <p>THIS IS THE ONLY PLACE THE DECODER CHOICE IS READ FROM. Both players go through it: the app's own
 * player ({@link PlayerFactory#renderersFor(Context, int, int)}) and the TV input session
 * ({@code TunerSession}). The three older switches ({@code video_mode}, {@code hardware_video},
 * {@code hardware_decoder}, {@code hardware_audio}) are still read ONCE, by {@link #migrate}, so an
 * update keeps whatever the viewer had chosen.</p>
 */
public final class DecoderPrefs {

    private static final String PREFS = com.pbcnx.opendtvmod.App.PREFS;
    public static final String KEY_MANUAL = "decoder_manual";
    public static final String KEY_VIDEO_SOFTWARE = "decoder_video_software";
    public static final String KEY_AUDIO_SOFTWARE = "decoder_audio_software";
    /** "frequency:0|1,..." - 0 = hardware won on that mux, 1 = software won. */
    public static final String KEY_PER_MUX = "decoder_per_mux";

    /** The old keys, read only by {@link #migrate}. */
    private static final String OLD_VIDEO_MODE = "video_mode";
    private static final String OLD_HW_VIDEO = "hardware_video";
    private static final String OLD_HW_DECODER = "hardware_decoder";
    private static final String OLD_HW_AUDIO = "hardware_audio";

    /**
     * How many muxes the Auto memory keeps. It is written once per channel whose decoder had to be
     * corrected, so this only ever grows on the bad ones; 24 is several evenings of zapping.
     */
    private static final int MAX_MUX_ENTRIES = 24;

    private DecoderPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Brings the three old switches into the one gate, once. {@code video_mode} wins over the older
     * booleans because it is the most recent thing the viewer touched; the audio boolean is
     * independent of the video one and migrates on its own.
     */
    public static void migrate(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains(KEY_MANUAL)) {
            return;
        }
        SharedPreferences.Editor e = p.edit();
        String oldMode = p.getString(OLD_VIDEO_MODE, null);
        if (oldMode != null) {
            if ("hw".equals(oldMode)) {
                e.putBoolean(KEY_MANUAL, true).putBoolean(KEY_VIDEO_SOFTWARE, false);
            } else if ("sw".equals(oldMode)) {
                e.putBoolean(KEY_MANUAL, true).putBoolean(KEY_VIDEO_SOFTWARE, true);
            } else {
                e.putBoolean(KEY_MANUAL, false);
            }
        } else if (p.contains(OLD_HW_VIDEO)) {
            e.putBoolean(KEY_MANUAL, true).putBoolean(KEY_VIDEO_SOFTWARE, !p.getBoolean(OLD_HW_VIDEO, false));
        } else if (p.contains(OLD_HW_DECODER)) {
            e.putBoolean(KEY_MANUAL, true).putBoolean(KEY_VIDEO_SOFTWARE, !p.getBoolean(OLD_HW_DECODER, false));
        } else {
            // Nothing was ever chosen: Auto, hardware first. That is also what a fresh install gets.
            e.putBoolean(KEY_MANUAL, false);
        }
        if (p.contains(OLD_HW_AUDIO)) {
            e.putBoolean(KEY_AUDIO_SOFTWARE, !p.getBoolean(OLD_HW_AUDIO, false));
        }
        e.apply();
    }

    public static boolean manual(Context c) {
        return prefs(c).getBoolean(KEY_MANUAL, false);
    }

    public static void setManual(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_MANUAL, value).apply();
    }

    public static boolean videoSoftware(Context c) {
        return prefs(c).getBoolean(KEY_VIDEO_SOFTWARE, false);
    }

    public static void setVideoSoftware(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_VIDEO_SOFTWARE, value).apply();
    }

    public static boolean audioSoftware(Context c) {
        return prefs(c).getBoolean(KEY_AUDIO_SOFTWARE, false);
    }

    public static void setAudioSoftware(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_AUDIO_SOFTWARE, value).apply();
    }

    /** What Auto learned on this mux, or null when nothing is known yet. */
    @Nullable public static Boolean autoVideoSoftware(Context c, int frequencyKhz) {
        if (frequencyKhz <= 0) {
            return null;
        }
        String map = prefs(c).getString(KEY_PER_MUX, "");
        if (map.isEmpty()) {
            return null;
        }
        for (String entry : map.split(",")) {
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                if (Integer.parseInt(entry.substring(0, colon).trim()) == frequencyKhz) {
                    return "1".equals(entry.substring(colon + 1).trim());
                }
            } catch (NumberFormatException ignored) {
                // a malformed entry must never break tuning
            }
        }
        return null;
    }

    /** Records what worked, so the next tune on this mux starts there. */
    public static void rememberAuto(Context c, int frequencyKhz, boolean softwareWorked) {
        if (frequencyKhz <= 0) {
            return;
        }
        String map = prefs(c).getString(KEY_PER_MUX, "");
        java.util.List<String> kept = new java.util.ArrayList<>();
        if (map != null && !map.isEmpty()) {
            for (String entry : map.split(",")) {
                int colon = entry.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                try {
                    if (Integer.parseInt(entry.substring(0, colon).trim()) == frequencyKhz) {
                        continue;                       // the new value replaces the old one
                    }
                } catch (NumberFormatException ignored) {
                    continue;
                }
                kept.add(entry.trim());
            }
        }
        // The map is oldest-first: keep its tail so the most recently corrected muxes survive.
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, kept.size() - (MAX_MUX_ENTRIES - 1)); i < kept.size(); i++) {
            out.append(kept.get(i)).append(',');
        }
        out.append(frequencyKhz).append(':').append(softwareWorked ? '1' : '0');
        prefs(c).edit().putString(KEY_PER_MUX, out.toString()).apply();
    }

    /** Forgets what Auto learned, so every mux starts on the device's decoder again. */
    public static void forgetAuto(Context c) {
        prefs(c).edit().remove(KEY_PER_MUX).apply();
    }

    /** How many muxes Auto has a memory for, for the row that offers to forget them. */
    public static int autoMuxCount(Context c) {
        String map = prefs(c).getString(KEY_PER_MUX, "");
        if (map.trim().isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String entry : map.split(",")) {
            if (entry.indexOf(':') > 0) n++;
        }
        return n;
    }

    /**
     * Renderer modes for this tune.
     *
     * <p>Extension renderer modes in Media3: {@code EXTENSION_RENDERER_MODE_ON} = use the platform
     * decoder first and the extension only when it cannot be used; {@code _PREFER} = extension first.
     * Hardware therefore maps to ON and software to PREFER, which is also why a <em>preferred</em>
     * software decoder can never fall back: Media3 only falls back on an init failure. The first-frame
     * watchdog in {@code TunerSession} is what closes that gap for Auto.</p>
     */
    public static int videoMode(Context c, int frequencyKhz) {
        boolean software;
        if (manual(c)) {
            software = videoSoftware(c);
        } else {
            Boolean learned = autoVideoSoftware(c, frequencyKhz);
            software = learned != null
                    ? learned
                    // Auto starts on hardware: it is the right answer on every mux measured except
                    // where its software peer is the only working one, and the watchdog corrects it.
                    : false;
        }
        return software ? PlayerFactory.MODE_PREFER : PlayerFactory.MODE_PLATFORM_FIRST;
    }

    /**
     * The video mode without the per-mux memory, for a caller that applies the memory itself (the
     * app's own player rebuilds in place before setting the source, so it asks for "hardware or
     * software" and not for a frequency).
     */
    public static int videoModeForced(Context c, boolean forceSoftwareVideo) {
        boolean software = manual(c) ? videoSoftware(c) : forceSoftwareVideo;
        return software ? PlayerFactory.MODE_PREFER : PlayerFactory.MODE_PLATFORM_FIRST;
    }

    public static int audioMode(Context c) {
        boolean software = manual(c) ? audioSoftware(c) : false;
        return software ? PlayerFactory.MODE_PREFER : PlayerFactory.MODE_PLATFORM_FIRST;
    }

    /** One line for the Settings rows, so the mode says what it does rather than what it is called. */
    public static String videoSummary(Context c, int frequencyKhz) {
        if (manual(c)) {
            return videoSoftware(c) ? "Software · FFmpeg for video" : "Hardware · the device's decoder";
        }
        Boolean learned = autoVideoSoftware(c, frequencyKhz);
        if (learned == null) {
            return "Auto · the device's decoder, the other one if no picture";
        }
        return learned
                ? "Auto · software was the one that worked here"
                : "Auto · the device's decoder worked here";
    }
}
