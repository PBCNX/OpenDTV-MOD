package com.pbcnx.opendtvmod.tuner;

/**
 * The small integer domains the display settings are stored in: caption style, caption language
 * and video aspect. They were bare literals in MainActivity, SettingsController and MainDialogs
 * (and the dialog arrays' order was the only thing tying the three together); the constants and the
 * normalisers live here so a value's meaning is written once.
 */
public final class DisplayModes {

    // Caption style (PREF_CC_STYLE): a simple text line, the styled aribb24 WebView, or Auto.
    public static final int CAPTION_SIMPLE = 0;
    public static final int CAPTION_STYLED = 1;
    public static final int CAPTION_AUTO = 2;

    // Caption language (PREF_CC_LANG): the service's first or second caption language.
    public static final int LANGUAGE_MAIN = 1;
    public static final int LANGUAGE_SECOND = 2;

    // Video aspect (PREF_ASPECT): fill 16:9, keep the ratio with bars, or keep it and crop.
    public static final int ASPECT_STRETCH = 0;
    public static final int ASPECT_FIT = 1;
    public static final int ASPECT_ZOOM = 2;

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

    /** What a stored aspect mode means; anything unknown is Stretch. */
    public static int aspectMode(int stored) {
        if (stored == ASPECT_FIT) return ASPECT_FIT;
        if (stored == ASPECT_ZOOM) return ASPECT_ZOOM;
        return ASPECT_STRETCH;
    }
}
