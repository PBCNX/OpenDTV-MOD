package com.pbcnx.opendtvmod.tuner;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The small integer domains the display settings are stored in: caption style, caption language
 * and video aspect. They were bare literals in MainActivity, SettingsController and MainDialogs
 * (and the dialog arrays' order was the only thing tying the three together); the constants and the
 * normalisers live here so a value's meaning is written once.
 *
 * <p>Video aspect is the owner's five-mode set (2026-11-07): the frame either follows the content
 * (AUTOMATIC), fills the available area (STRETCH / ZOOM), or is pinned to a shape (FORCE_4_3 /
 * FORCE_16_9). {@link #resolve(int, float, boolean)} is the pure geometry decision behind all five,
 * kept free of Android/Media3 types so it can be tested on the JVM.</p>
 */
public final class DisplayModes {

    // Caption style (PREF_CC_STYLE): a simple text line, the styled aribb24 WebView, or Auto.
    public static final int CAPTION_SIMPLE = 0;
    public static final int CAPTION_STYLED = 1;
    public static final int CAPTION_AUTO = 2;

    // Caption language (PREF_CC_LANG): the service's first or second caption language.
    public static final int LANGUAGE_MAIN = 1;
    public static final int LANGUAGE_SECOND = 2;

    // Video aspect (PREF_ASPECT), the owner's five modes, in the order the dialog lists them.
    // 0 = Automatic (frame follows the content: exact 16:9 / exact 4:3)
    // 1 = Stretch (fills the video area in portrait, the screen in landscape; ignores aspect)
    // 2 = Force 4:3 (centred 4:3 box, content fitted inside)
    // 3 = Force 16:9 (centred 16:9 box, content fitted inside)
    // 4 = Zoom (crops to the available area/screen)
    public static final int ASPECT_AUTO = 0;
    public static final int ASPECT_STRETCH = 1;
    public static final int ASPECT_FORCE_4_3 = 2;
    public static final int ASPECT_FORCE_16_9 = 3;
    public static final int ASPECT_ZOOM = 4;

    /** The one SharedPreferences key for the aspect preference; MainActivity aliases this. */
    public static final String PREF_ASPECT = "aspect_mode";
    /** Set once the 0.2.x three-mode value has been translated into the five-mode one. */
    public static final String PREF_ASPECT_MIGRATED = "aspect_mode_migrated";

    // How the player should scale the video inside the frame; MainActivity maps these onto
    // Media3's AspectRatioFrameLayout constants. Kept as local ints so resolve() stays pure.
    public static final int RESIZE_FIT = 0;
    public static final int RESIZE_FILL = 1;
    public static final int RESIZE_ZOOM = 2;

    private static final float ASPECT_16_9 = 16f / 9f;
    private static final float ASPECT_4_3 = 4f / 3f;

    private DisplayModes() {
    }

    /** What a stored caption style means; anything unknown falls back to Auto. */
    public static int captionStyle(int stored) {
        if (stored == CAPTION_STYLED) return CAPTION_STYLED;
        if (stored == CAPTION_SIMPLE) return CAPTION_SIMPLE;
        return CAPTION_AUTO;
    }

    /** What a stored caption language means; anything unknown is the main language. */
    public static int captionLanguage(int stored) {
        return stored == LANGUAGE_SECOND ? LANGUAGE_SECOND : LANGUAGE_MAIN;
    }

    /** What a stored aspect mode means; anything unknown is Automatic. */
    public static int aspectMode(int stored) {
        switch (stored) {
            case ASPECT_STRETCH:
            case ASPECT_FORCE_4_3:
            case ASPECT_FORCE_16_9:
            case ASPECT_ZOOM:
                return stored;
            default:
                return ASPECT_AUTO;
        }
    }

    /**
     * The 0.2.x stored value translated into the five-mode domain: 0 was Stretch, 1 was Fit (now
     * Automatic) and 2 was Zoom. Values that already belong to the five-mode set pass through.
     */
    public static int migrateLegacyAspectMode(int stored) {
        switch (stored) {
            case 0: return ASPECT_STRETCH;   // legacy Stretch (fill 16:9)
            case 1: return ASPECT_AUTO;      // legacy Fit (keep ratio, bars)
            case 2: return ASPECT_ZOOM;      // legacy Zoom (keep ratio, crop)
            default: return aspectMode(stored);
        }
    }

    /**
     * One-time translation of the stored three-mode preference. An install that has never written
     * the preference starts on Automatic (the owner's first mode); an existing one keeps the look
     * it had: Stretch stays Stretch, Fit becomes Automatic, Zoom stays Zoom.
     */
    public static void migrate(Context context) {
        SharedPreferences p = context.getSharedPreferences(
                com.pbcnx.opendtvmod.App.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(PREF_ASPECT_MIGRATED, false)) return;
        int migrated = p.contains(PREF_ASPECT)
                ? migrateLegacyAspectMode(p.getInt(PREF_ASPECT, ASPECT_STRETCH))
                : ASPECT_AUTO;
        p.edit().putInt(PREF_ASPECT, migrated).putBoolean(PREF_ASPECT_MIGRATED, true).apply();
    }

    /** The dialog/settings label for one mode. */
    public static String aspectName(int mode) {
        switch (aspectMode(mode)) {
            case ASPECT_STRETCH: return "Stretch (ignores screen size)";
            case ASPECT_FORCE_4_3: return "Force 4:3";
            case ASPECT_FORCE_16_9: return "Force 16:9";
            case ASPECT_ZOOM: return "Zoom (crop to screen)";
            default: return "Automatic (exact 16:9 / exact 4:3)";
        }
    }

    /**
     * The frame ratio and scaling for one mode. A frame ratio of 0 means "use the whole available
     * area" (portrait video card or the landscape screen); the resize mode says how the content
     * fills that frame.
     *
     * @param contentAspect the playing video's width/height, 16:9 when nothing is known yet
     * @param landscape     true in the landscape layout, where Stretch and Zoom fill the screen
     */
    public static FrameGeometry resolve(int mode, float contentAspect, boolean landscape) {
        switch (aspectMode(mode)) {
            case ASPECT_STRETCH:
                return new FrameGeometry(landscape ? 0f : ASPECT_16_9, RESIZE_FILL);
            case ASPECT_FORCE_4_3:
                return new FrameGeometry(ASPECT_4_3, RESIZE_FIT);
            case ASPECT_FORCE_16_9:
                return new FrameGeometry(ASPECT_16_9, RESIZE_FIT);
            case ASPECT_ZOOM:
                return new FrameGeometry(landscape ? 0f : ASPECT_16_9, RESIZE_ZOOM);
            default:
                float ratio = contentAspect;
                if (!(ratio > 0f) || Float.isNaN(ratio) || Float.isInfinite(ratio)) {
                    ratio = ASPECT_16_9;
                }
                // Clamp so an exotic stream ratio cannot eat the layout: exact 16:9 and exact 4:3
                // both pass through unchanged, anything wider/taller gets bars instead.
                ratio = Math.max(ASPECT_4_3, Math.min(ASPECT_16_9, ratio));
                return new FrameGeometry(ratio, RESIZE_FIT);
        }
    }

    /**
     * Display aspect from the coded size and the container's pixel aspect ratio. ISDB-T streams are
     * routinely anamorphic (720x480 coded, displayed 16:9 or 4:3), so ignoring the pixel ratio would
     * box a 16:9 channel at 3:2 in Automatic.
     */
    public static float displayAspect(int width, int height, float pixelWidthHeightRatio) {
        if (width <= 0 || height <= 0) return ASPECT_16_9;
        float pixelRatio = pixelWidthHeightRatio;
        if (!(pixelRatio > 0f) || Float.isNaN(pixelRatio) || Float.isInfinite(pixelRatio)) {
            pixelRatio = 1f;
        }
        return (float) width * pixelRatio / (float) height;
    }

    /** The pure output of {@link #resolve(int, float, boolean)}. */
    public static final class FrameGeometry {
        /** Player-frame aspect ratio; 0 means fill the available area. */
        public final float frameRatio;
        /** One of the RESIZE_* constants. */
        public final int resizeMode;

        FrameGeometry(float frameRatio, int resizeMode) {
            this.frameRatio = frameRatio;
            this.resizeMode = resizeMode;
        }
    }
}
