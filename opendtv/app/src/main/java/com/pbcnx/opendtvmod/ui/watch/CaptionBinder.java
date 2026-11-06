package com.pbcnx.opendtvmod.ui.watch;


import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import com.pbcnx.opendtvmod.tuner.DisplayModes;
import com.pbcnx.opendtvmod.ts.pipe.ServiceFilter;
import com.pbcnx.opendtvmod.ui.CaptionOverlay;

/**
 * The screen's caption wiring: the simple text overlay, the styled (WebView) view, and the
 * binding of whichever one is selected to the live filter.
 *
 * <p>MainActivity keeps the Settings rows; this class owns the two displays and the rules that
 * decide which of them the filter drives.
 */
public final class CaptionBinder {

    /** What the binder needs from the screen. */
    public interface Host extends ScreenSeam {
        PlayerView playerView();
        @Nullable ServiceFilter liveFilter();
        void refreshSettings();
    }

    private final Host host;
    private CaptionOverlay overlay;
    private RawCaptionView view;   // styled (WebView) display, null while simple text is selected

    public CaptionBinder(Host host) {
        this.host = host;
    }

    /** Called once the player surface exists; safe to call again after a rebuild. */
    public void attach() {
        PlayerView playerView = host.playerView();
        if (playerView == null) return;
        if (overlay == null) {
            overlay = new CaptionOverlay(playerView);
            overlay.setEnabled(host.prefs().getBoolean("closed_captions", true));
        } else {
            overlay.attachTo(playerView);
        }
        if (view != null) view.attachTo(playerView);
        else applyStyle();
    }

    /** The player view is going away: the styled WebView must not outlive it. */
    public void destroy() {
        if (view != null) {
            view.destroy();
            view = null;
        }
    }

    public boolean enabled() {
        return overlay != null && overlay.isEnabled();
    }

    public void setEnabled(boolean on) {
        if (overlay != null) overlay.setEnabled(on);
        if (view != null) view.setEnabled(on);
        bind();
    }

    public int style() {
        return DisplayModes.captionStyle(
                host.prefs().getInt("caption_style", DisplayModes.CAPTION_SIMPLE));
    }

    public void setStyle(int style) {
        host.prefs().edit().putInt("caption_style", style).apply();
        reset();
        applyStyle();
        host.refreshSettings();
    }

    public int language() {
        return DisplayModes.captionLanguage(
                host.prefs().getInt("caption_language", DisplayModes.LANGUAGE_MAIN));
    }

    public void setLanguage(int language) {
        host.prefs().edit().putInt("caption_language", language).apply();
        ServiceFilter live = host.liveFilter();
        if (live != null) live.setCaptionLanguage(language);
        if (view != null) view.setLanguage(language);
        reset();
        host.refreshSettings();
    }

    /** Rebuilds the display for the selected style and binds it to the live filter. */
    public void applyStyle() {
        PlayerView playerView = host.playerView();
        int style = style();
        boolean styled = style == DisplayModes.CAPTION_STYLED;
        boolean auto = style == DisplayModes.CAPTION_AUTO;
        if (playerView != null && (styled || auto) && view == null) {
            view = new RawCaptionView(playerView);
            view.setEnabled(overlay == null || overlay.isEnabled());
            view.setLanguage(language());
        } else if (!styled && !auto && view != null) {
            view.destroy();
            view = null;
        }
        bind();
    }

    /** Sends the channel's captions to whichever display is selected. */
    public void bind() {
        ServiceFilter sink = host.liveFilter();
        if (sink == null) return;
        int style = style();
        boolean styled = style == DisplayModes.CAPTION_STYLED;
        boolean auto = style == DisplayModes.CAPTION_AUTO;
        sink.setCaptionSink(styled ? null : overlay);
        sink.setRawCaptionSink((styled || auto) ? view : null);
        sink.setSplitCaptions(auto);
        sink.setCaptionLanguage(language());
    }

    public void reset() {
        if (overlay != null) overlay.reset();
        if (view != null) view.clear();
    }
}
