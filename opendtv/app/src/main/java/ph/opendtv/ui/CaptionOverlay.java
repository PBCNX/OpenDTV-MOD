package ph.opendtv.ui;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

import java.util.ArrayDeque;

import ph.opendtv.ts.ServiceFilter;

/**
 * Draws ARIB closed captions over a PlayerView. Captions arrive from the tuner thread ahead of the video
 * (the player buffers several seconds), so each one is queued with the player position at which it is due.
 */
@UnstableApi
public final class CaptionOverlay implements ServiceFilter.CaptionSink {
    private static final long TICK_MS = 100;
    private static final long MAX_SHOW_MS = 10_000;     // safety: stations do not always send a clearing statement

    private static final class Item {
        final long dueMs;          // player position, or Long.MIN_VALUE = relative to buffered position at arrival
        final String text;
        Item(long dueMs, String text) { this.dueMs = dueMs; this.text = text; }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView view;
    private FrameLayout.LayoutParams lp;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private Player player;
    private boolean enabled = true;
    private long shownAtUptime;
    private boolean ticking;

    public CaptionOverlay(PlayerView pv) {
        view = new TextView(pv.getContext());
        view.setTextColor(Color.WHITE);
        view.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        view.setBackgroundColor(0x99000000);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        float d = pv.getResources().getDisplayMetrics().density;
        view.setPadding((int) (8 * d), (int) (2 * d), (int) (8 * d), (int) (2 * d));
        view.setVisibility(android.view.View.GONE);
        lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (28 * d);
        attachTo(pv);
    }

    /** Moves the caption line into another PlayerView (the layout was re-inflated after a rotation). */
    public void attachTo(PlayerView pv) {
        android.view.ViewParent old = view.getParent();
        if (old instanceof android.view.ViewGroup) ((android.view.ViewGroup) old).removeView(view);
        FrameLayout overlay = pv.getOverlayFrameLayout();
        if (overlay != null) overlay.addView(view, lp);
        player = pv.getPlayer();
    }

    public void setPlayer(Player p) { player = p; }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) reset();
    }

    public boolean isEnabled() { return enabled; }

    /** Call from the UI thread on channel change / stop. */
    public void reset() {
        synchronized (queue) { queue.clear(); }
        hide();
    }

    /** Tuner thread. */
    @Override
    public void onCaption(final long dueMs, final String text) {
        if (!enabled) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                Player p = player;
                long due = dueMs;
                if (due == Long.MIN_VALUE) due = p != null ? p.getBufferedPosition() : 0;   // live edge of the buffer
                synchronized (queue) { queue.add(new Item(due, text)); }
                if (!ticking) {
                    ticking = true;
                    ui.postDelayed(tick, TICK_MS);
                }
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Player p = player;
            long pos = p != null ? p.getCurrentPosition() : 0;
            boolean any;
            synchronized (queue) {
                Item it;
                while ((it = queue.peek()) != null && it.dueMs <= pos) {
                    queue.poll();
                    show(it.text);
                }
                any = !queue.isEmpty();
            }
            if (view.getVisibility() == android.view.View.VISIBLE
                    && android.os.SystemClock.uptimeMillis() - shownAtUptime > MAX_SHOW_MS) hide();
            if (any || view.getVisibility() == android.view.View.VISIBLE) {
                ui.postDelayed(this, TICK_MS);
            } else {
                ticking = false;
            }
        }
    };

    private void show(String text) {
        if (text == null || text.replace("\u0000", "").trim().isEmpty()) {
            hide();
            return;
        }
        view.setText(text.replace("\u0000", ""));
        view.setVisibility(android.view.View.VISIBLE);
        shownAtUptime = android.os.SystemClock.uptimeMillis();
    }

    private void hide() {
        view.setVisibility(android.view.View.GONE);
    }
}
