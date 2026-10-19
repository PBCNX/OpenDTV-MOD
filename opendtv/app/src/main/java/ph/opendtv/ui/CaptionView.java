package ph.opendtv.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

import ph.opendtv.ts.ServiceFilter;

/**
 * "Styled" closed-caption display: the caption PES is rendered by aribb24.js in a transparent WebView laid over the
 * video (ARIB caption plane with its colours, sizes and positions, and Latin / UCS coded captions as used in the
 * Philippines). The simple one-line TextView display is {@link CaptionOverlay}.
 */
@UnstableApi
public final class CaptionView implements ServiceFilter.RawCaptionSink {
    private static final long TICK_MS = 100;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FrameLayout holder;
    private final WebView web;
    private Player player;
    private boolean ready;
    private boolean enabled = true;
    private boolean destroyed;
    private int language = 1;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public CaptionView(PlayerView pv) {
        Context c = pv.getContext();
        holder = new FrameLayout(c) {
            /** Never takes touches: taps go through to the video (info banner etc.). */
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) { return false; }
        };
        holder.setBackgroundColor(Color.TRANSPARENT);
        web = new WebView(c);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setClickable(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "CaptionBridge");
        holder.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/bml/captions.html");
        attachTo(pv);
    }

    private final class Bridge {
        @JavascriptInterface
        public void ready() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    ready = true;
                    eval("CaptionHost.setLanguage(" + language + ")");
                    eval("CaptionHost.setVisible(" + enabled + ")");
                    startTick();
                }
            });
        }
    }

    /** Moves the view into another PlayerView (the layout was re-inflated after a rotation). */
    public void attachTo(PlayerView pv) {
        android.view.ViewParent old = holder.getParent();
        if (old instanceof ViewGroup) ((ViewGroup) old).removeView(holder);
        FrameLayout overlay = pv.getOverlayFrameLayout();
        if (overlay != null) {
            overlay.addView(holder, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        player = pv.getPlayer();
    }

    public void setPlayer(Player p) { player = p; }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) clear();
        eval("CaptionHost.setVisible(" + on + ")");
        if (on) startTick();
    }

    public boolean isEnabled() { return enabled; }

    public void setLanguage(int lang) {
        language = lang == 2 ? 2 : 1;
        eval("CaptionHost.setLanguage(" + language + ")");
        clear();
    }

    /** UI thread: channel change / stop (the player position restarts at 0). */
    public void clear() { eval("CaptionHost.clear()"); }

    /** Frees the WebView. The instance must not be used afterwards. */
    public void destroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        web.removeJavascriptInterface("CaptionBridge");
        web.stopLoading();
        web.loadUrl("about:blank");
        android.view.ViewParent par = holder.getParent();
        if (par instanceof ViewGroup) ((ViewGroup) par).removeView(holder);
        holder.removeAllViews();
        web.destroy();
    }

    /** Tuner thread. */
    @Override
    public void onCaptionPes(final long dueMs, final int streamId, byte[] payload) {
        if (!enabled || payload == null || payload.length == 0) return;
        final String b64 = Base64.encodeToString(payload, Base64.NO_WRAP);
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || !ready || !enabled) return;
                long due = dueMs;
                if (due == Long.MIN_VALUE) {
                    Player p = player;
                    due = p != null ? p.getBufferedPosition() : 0;   // live edge of the buffer
                }
                eval("CaptionHost.push('" + b64 + "'," + due + "," + streamId + ")");
            }
        });
    }

    private boolean ticking;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !ready || !enabled) { ticking = false; return; }
            Player p = player;
            eval("CaptionHost.setTime(" + (p != null ? p.getCurrentPosition() : 0) + ")");
            ui.postDelayed(this, TICK_MS);
        }
    };

    private void startTick() {
        if (ticking || destroyed || !ready || !enabled) return;
        ticking = true;
        ui.post(tick);
    }

    private void eval(String js) {
        if (destroyed || !ready) return;
        web.evaluateJavascript(js, null);
    }
}
