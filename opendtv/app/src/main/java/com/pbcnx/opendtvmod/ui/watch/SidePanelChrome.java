package com.pbcnx.opendtvmod.ui.watch;

import android.os.Handler;
import android.view.View;

/** The channel-list side panel: visibility, the 10 s auto-hide and the modal/dialog pauses. */
public final class SidePanelChrome {

    public interface Host {
        Handler ui();
        boolean isFinishing();
        boolean hasWindowFocus();
        boolean watchCardShowing();
        void hideWatchCard();
        void focusSelectedTab();
        void updateButtonPosition();
        void showInfoBanner();
    }

    private static final long AUTO_HIDE_MS = 10_000;

    private final View panel;
    private final View tuning;
    private final Host host;
    private final Runnable autoHide = this::onTimeout;

    public SidePanelChrome(View panel, View tuning, Host host) {
        this.panel = panel;
        this.tuning = tuning;
        this.host = host;
    }

    public void show() {
        if (host.watchCardShowing()) return;   // A+C is modal
        host.hideWatchCard();
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
            // On a TV the strip is the entry point: LEFT/RIGHT moves, OK selects,
            // DOWN drops into the list. In touch mode this request is a no-op.
            host.focusSelectedTab();
            scheduleAutoHide();
        }
        host.updateButtonPosition();
        updateTuningInset();
        host.showInfoBanner();
    }

    public void hide() {
        if (panel != null) {
            panel.setVisibility(View.GONE);
            host.ui().removeCallbacks(autoHide);
        }
        host.updateButtonPosition();
        updateTuningInset();
    }

    /** The screen is going away: drop the pending timer. */
    public void cancelAutoHide() {
        host.ui().removeCallbacks(autoHide);
    }

    public void scheduleAutoHide() {
        host.ui().removeCallbacks(autoHide);
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            host.ui().postDelayed(autoHide, AUTO_HIDE_MS);
        }
    }

    /**
     * The panel is drawn over the window and the centre card is centred on the whole window, so it
     * used to run under the panel. Push the card's centre into the free area left of the panel.
     */
    public void updateTuningInset() {
        if (tuning == null || panel == null) return;
        int inset = 0;
        if (panel.getVisibility() == View.VISIBLE) {
            android.view.ViewGroup.LayoutParams sp = panel.getLayoutParams();
            if (sp instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams m = (android.view.ViewGroup.MarginLayoutParams) sp;
                inset = m.width > 0 ? m.width + m.getMarginEnd() : 0;
            }
        }
        tuning.setTranslationX(-inset / 2f);
    }

    private void onTimeout() {
        if (!host.isFinishing() && !host.hasWindowFocus()) {
            host.ui().postDelayed(autoHide, AUTO_HIDE_MS);
            return;
        }
        hide();
    }
}
