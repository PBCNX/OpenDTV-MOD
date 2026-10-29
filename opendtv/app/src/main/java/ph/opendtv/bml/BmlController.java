package ph.opendtv.bml;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.concurrent.atomic.AtomicInteger;

import ph.opendtv.ts.PacketSink;
import ph.opendtv.ts.ServiceInfo;
import ph.opendtv.ts.StreamInfo;
import ph.opendtv.ts.TsDemux;

/**
 * Full ARIB data-broadcast (BML) display. The page in assets/bml is web-bml (MIT, otya128) bundled with our glue;
 * this class owns the transparent WebView that sits over the video, feeds it the data-carousel packets of the
 * selected service, forwards remote keys, and reports where the video must be placed (BML shrinks/moves it).
 */
public final class BmlController {
    private static final String TAG = "OpenDTV-BML";

    // ARIB key codes (STD-B24 / web-bml AribKeyCode)
    public static final int UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4, DIGIT0 = 5, ENTER = 18, BACK = 19, DATA = 20,
            BLUE = 21, RED = 22, GREEN = 23, YELLOW = 24;

    public interface Host {
        /** Fractions (0..1) of the player frame the video must occupy; invisible = BML hidden, video full screen. */
        void onVideoRect(float l, float t, float r, float b, boolean invisible);
        void onStatus(String text);
        void onClosed();
    }

    /** A hidden (cached) page is kept this long, then destroyed to give the memory back. */
    private static final long CACHE_TTL_MS = 3 * 60_000L;

    private final Activity act;
    private FrameLayout frame;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private volatile BmlSoundPlayer sound;   // native ROM / AIFF beeps, lives as long as the page
    private boolean pageReady;
    private boolean docLoaded;
    private boolean active;          // a WebView exists (shown or cached)
    private boolean visible;         // the WebView is shown and receives keys / packets
    private int serviceId = -1;      // service the cached page belongs to
    private float[] lastRect;        // last video rectangle the page asked for (fractions), null = full screen
    private boolean lastInvisible = true;
    private int pendingDataKeys;
    private final Runnable expire = new Runnable() {
        @Override
        public void run() { if (!visible) close(); }
    };
    private TsDemux demux;
    private final Feeder feeder = new Feeder();
    private final AtomicInteger inFlight = new AtomicInteger();

    /** {@code frame} may be null (portrait: no place for the data broadcast); see {@link #attachTo}. */
    public BmlController(Activity act, FrameLayout frame, Host host) {
        this.act = act;
        this.frame = frame;
        this.host = host;
    }

    /** A page exists (shown or cached). */
    public boolean isActive() { return active; }

    /** The page is on screen: it gets the keys and the data-carousel packets. */
    public boolean isVisible() { return active && visible; }

    /** App went to the background: stop feeding and halt the page (CPU / battery / memory). */
    public void pause() {
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
    }

    public void resume() {
        if (web != null && visible) {
            web.onResume();
            web.resumeTimers();
        }
    }

    /** Moves the cached page to another container (rotation); null = nowhere to show it (page is hidden, kept). */
    public void attachTo(FrameLayout f) {
        if (f == frame) return;
        if (web != null) {
            android.view.ViewParent p = web.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(web);
            if (f != null) {
                f.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
        }
        frame = f;
        if (f == null && visible) hide();
    }

    /** Low-memory callback: a hidden cached page is the first thing to give up. */
    public void trimMemory() {
        if (active && !visible) close();
    }

    public TsDemux demux() { return demux; }

    /**
     * Shows the data broadcast of {@code svc}. If a page for the same service is still cached it is simply shown
     * again (no reload, no re-decoding). Returns true when a NEW page had to be created and is loading.
     */
    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    public boolean open(TsDemux d, final ServiceInfo svc) {
        if (frame == null) return false;
        ui.removeCallbacks(expire);
        if (active && web != null && demux == d && serviceId == svc.serviceId) {
            show();
            return false;
        }
        if (active) close();
        // Create the WebView first: on devices where "Android System WebView" is missing, disabled or being
        // updated this throws, and the data broadcast must then just be unavailable (never crash the app).
        final WebView created;
        try {
            created = new WebView(act);
        } catch (Throwable t) {
            Log.e(TAG, "WebView unavailable", t);
            host.onStatus("Data broadcast unavailable: Android System WebView is missing or disabled. "
                    + "Install / enable it (or Chrome) from the Play Store.");
            return false;
        }
        active = true;
        visible = true;
        pageReady = false;
        docLoaded = false;
        lastRect = null;
        lastInvisible = true;
        serviceId = svc.serviceId;
        demux = d;
        feeder.configure(svc);
        web = created;
        logWebViewVersion();
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) { return true; }   // keys only: no scroll / zoom
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // BML NVRAM / persistent registers
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);
        web.addJavascriptInterface(new Bridge(), "AndroidBml");
        sound = new BmlSoundPlayer(act);
        final int sid = svc.serviceId;
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (!active || pageReady) return;
                pageReady = true;
                // An old WebView that cannot run the page leaves OpenDtvBml undefined: say so instead of failing silently.
                v.evaluateJavascript("typeof OpenDtvBml === 'object' ? 'ok' : 'missing'", new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String r) {
                        if (r != null && r.contains("missing")) {
                            host.onStatus("Data broadcast not supported by this WebView ("
                                    + webViewVersion() + "). Update Android System WebView.");
                        }
                    }
                });
                v.evaluateJavascript("OpenDtvBml.start(" + sid + ")", null);
                if (visible && demux != null) startFeed();   // buffered carousel first, then live packets
            }

            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                // The WebView's renderer died (low memory / crash): drop the page, keep the app alive.
                boolean crashed = Build.VERSION.SDK_INT >= 26 && detail.didCrash();
                Log.e(TAG, "WebView render process gone, crashed=" + crashed);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (web == v) {
                            close();
                            host.onStatus("Data broadcast stopped (WebView crashed)");
                        }
                    }
                });
                return true;
            }
        });
        frame.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/bml/index.html");
        return true;
    }

    /** "package version" of the WebView implementation in use, for logs / status messages. */
    private String webViewVersion() {
        if (Build.VERSION.SDK_INT < 26) return "unknown WebView";
        try {
            android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi != null) return pi.packageName + " " + pi.versionName;
        } catch (Throwable ignored) {
            // no WebView package to read: the caller only logs this
        }
        return "unknown WebView";
    }

    private void logWebViewVersion() { Log.i(TAG, "WebView: " + webViewVersion()); }

    /**
     * Starts feeding the page. The demux keeps the latest packets of the PSI and data-carousel PIDs, so the page
     * gets everything the carousel already delivered (a recorded file is read only once and has usually ended
     * by the time DATA is pressed; a live carousel would otherwise take a full cycle to come round).
     */
    private void startFeed() {
        TsDemux d = demux;
        if (d == null) return;
        byte[] history = d.attachTapWithHistory(feeder, feeder.wanted());
        feeder.replay(history);
    }

    /** Brings a cached page back: instant, nothing is reloaded. */
    public void show() {
        if (!active || web == null || visible) return;
        visible = true;
        ui.removeCallbacks(expire);
        web.setVisibility(View.VISIBLE);
        web.onResume();
        web.resumeTimers();
        if (pageReady && demux != null) demux.setTapSink(feeder);
        if (lastRect != null) host.onVideoRect(lastRect[0], lastRect[1], lastRect[2], lastRect[3], lastInvisible);
        // the page had hidden itself (or never showed anything): ask it to come up again
        if (docLoaded && lastInvisible) web.evaluateJavascript("OpenDtvBml.tap(" + DATA + ")", null);
    }

    /**
     * Takes the data broadcast off the screen but KEEPS the loaded page: no more packets are fed, its timers are
     * paused (no CPU), the video goes back to full screen. Pressing DATA again calls {@link #show()}. If it stays
     * hidden for a few minutes (or memory runs low) the page is destroyed.
     */
    public void hide() {
        if (!active || web == null || !visible) return;
        visible = false;
        TsDemux d = demux;
        if (d != null) d.setTapSink(null);
        feeder.dropPending();
        web.setVisibility(View.GONE);
        web.onPause();
        web.pauseTimers();
        host.onVideoRect(0, 0, 1, 1, true);
        ui.removeCallbacks(expire);
        ui.postDelayed(expire, CACHE_TTL_MS);
    }

    /** Destroys the page (DOM, decoded modules, AudioContext). Use {@link #hide()} to keep it cached. */
    public void close() {
        if (!active) return;
        ui.removeCallbacks(expire);
        active = false;
        visible = false;
        serviceId = -1;
        lastRect = null;
        lastInvisible = true;
        TsDemux d = demux;
        if (d != null) d.setTapSink(null);
        feeder.reset();
        if (web != null) {
            WebView w = web;
            web = null;
            w.onResume();                                // a paused WebView does not tear down cleanly
            w.stopLoading();
            w.removeJavascriptInterface("AndroidBml");
            if (sound != null) { sound.release(); sound = null; }
            w.setWebViewClient(new WebViewClient());
            w.loadUrl("about:blank");                    // drops the page (DOM, decoded modules, AudioContext)
            android.view.ViewParent par = w.getParent();
            if (par instanceof android.view.ViewGroup) ((android.view.ViewGroup) par).removeView(w);
            w.removeAllViews();
            w.destroy();
        }
        inFlight.set(0);
        demux = null;
        host.onVideoRect(0, 0, 1, 1, true);
        host.onClosed();
    }

    /**
     * The page reacts to DATA only once its own onload script has subscribed to the DataButtonPressed event, which can
     * happen a moment AFTER the engine's "load" event, so the first DATA may be lost and is worth one more try.
     * But DATA is a TOGGLE in BML: pressing it again while the page is merely slow to draw hides it again, which is
     * what made one press act as 2-3 presses. So DATA is only repeated when BOTH signals agree the page is still
     * hidden: the engine's own "invisible" state ({@code lastInvisible}, reported by the page) AND the DOM check.
     * At most 2 repeats, spaced out so a slow page has time to report itself.
     */
    private static final int MAX_DATA_RETRIES = 2;

    private void verifyShown(final int attempt) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                final WebView w = web;
                if (!active || !visible || w == null) return;
                if (!lastInvisible) {                       // the page itself says it is visible: done, never press again
                    Log.i(TAG, "page visible after " + attempt + " retries");
                    return;
                }
                w.evaluateJavascript("(function(){var c=document.getElementById('content');"
                        + "if(!c)return 'nocontent';var n=c.childNodes.length;"
                        + "return (n>0&&c.style.visibility!=='hidden')?'shown':'hidden nodes='+n;})()",
                        new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (!active || !visible || web != w) return;
                        if (!lastInvisible || (v != null && v.contains("shown"))) {   // re-check: it may have appeared meanwhile
                            Log.i(TAG, "page visible after " + attempt + " retries");
                            return;
                        }
                        if (attempt >= MAX_DATA_RETRIES) {
                            Log.w(TAG, "page still hidden: " + v);
                            host.onStatus("Data broadcast loaded but the page stays hidden (" + v
                                    + "). Press DATA again; see logcat tag OpenDTV-BML.");
                            return;
                        }
                        Log.i(TAG, "page hidden (" + v + "), pressing DATA again, attempt " + (attempt + 1));
                        tap(DATA);
                        verifyShown(attempt + 1);
                    }
                });
            }
        }, attempt == 0 ? 1800 : 1500);
    }

    /** Remote key press+release (ARIB key code). DATA before the document is loaded is queued. */
    public void tap(int code) {
        if (!active || web == null || !visible) return;
        if (!docLoaded) {
            if (code == DATA) pendingDataKeys = 1;
            return;
        }
        web.evaluateJavascript("OpenDtvBml.tap(" + code + ")", null);
    }

    // ------------------------------------------------------------------ JS -> Java

    private final class Bridge {
        @JavascriptInterface
        public void log(String m) { Log.i(TAG, m); }

        /** ROM sound 0..13 (romsound://N). false = not available, the page falls back to WebAudio. */
        @JavascriptInterface
        public boolean playSound(int id) {
            BmlSoundPlayer p = sound;
            return p != null && p.play(id);
        }

        /** Base64 of an AIFF file from the data broadcast. */
        @JavascriptInterface
        public boolean playAiff(String b64) {
            BmlSoundPlayer p = sound;
            try {
                return p != null && p.playAiff(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void stopAiff() {
            BmlSoundPlayer p = sound;
            if (p != null) p.stopAiff();
        }

        @JavascriptInterface
        public void onVideo(final double l, final double t, final double r, final double b, final boolean invisible) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!active) return;
                    lastRect = new float[] {(float) l, (float) t, (float) r, (float) b};
                    lastInvisible = invisible;
                    if (visible) host.onVideoRect((float) l, (float) t, (float) r, (float) b, invisible);
                }
            });
        }

        @JavascriptInterface
        public void onLoad(int w, int h) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!active) return;
                    docLoaded = true;
                    host.onStatus("Data broadcast loaded");
                    if (pendingDataKeys > 0) {
                        pendingDataKeys = 0;
                        tap(DATA);
                        verifyShown(0);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onState(final String json) {
            if (!json.contains("\"error\"")) return;      // only errors cross to the UI thread
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (active) host.onStatus("Data broadcast error");
                    Log.w(TAG, "BML state error: " + json);
                }
            });
        }
    }

    // ------------------------------------------------------------------ Java -> JS

    /** Collects the packets web-bml needs (PAT/SDT/EIT/TDT, the service's PMT, its data-carousel PIDs). */
    private final class Feeder implements PacketSink {
        private final boolean[] want = new boolean[8192];
        private final byte[] buf = new byte[188 * 400];
        private int len;
        private long lastSend;
        private volatile boolean replaying;
        private volatile int generation;

        void configure(ServiceInfo svc) {
            reset();
            for (int pid : new int[] {0x00, 0x10, 0x11, 0x12, 0x14}) want[pid] = true;
            if (svc.pmtPid > 0 && svc.pmtPid < 8192) want[svc.pmtPid] = true;
            for (StreamInfo st : svc.streams) {
                if ((st.isDatacast() || st.dataComponentId == 0x000C) && st.pid < 8192) want[st.pid] = true;
            }
        }

        void reset() {
            generation++;
            replaying = false;
            java.util.Arrays.fill(want, false);
            len = 0;
        }

        void dropPending() {
            generation++;     // aborts a replay in progress
            replaying = false;
            len = 0;
        }

        boolean[] wanted() { return want; }

        /**
         * Pushes the buffered history to the page in batches, waiting for the page to finish each one (nothing is
         * dropped, unlike live packets). Live packets are ignored meanwhile so the order stays intact; the carousel
         * repeats, so that costs nothing.
         */
        void replay(final byte[] data) {
            if (data == null || data.length < 188) return;
            final int gen = ++generation;
            replaying = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    final int step = 188 * 400;
                    try {
                        for (int off = 0; off < data.length; off += step) {
                            int n = Math.min(step, data.length - off);
                            int waited = 0;
                            while (inFlight.get() >= 3 && waited < 8000) {
                                if (gen != generation) return;
                                Thread.sleep(5);
                                waited += 5;
                            }
                            if (gen != generation) return;
                            dispatch(Base64.encodeToString(data, off, n, Base64.NO_WRAP));
                        }
                    } catch (InterruptedException ignored) {
                        // interrupted: the finally block still clears the replay flag
                    } finally {
                        if (gen == generation) replaying = false;
                    }
                }
            }, "BmlReplay");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void onPacket(byte[] b, int o) {
            if (replaying) return;
            int pid = ((b[o + 1] & 0x1F) << 8) | (b[o + 2] & 0xFF);
            if (!want[pid]) return;
            if (len + 188 > buf.length) send();
            System.arraycopy(b, o, buf, len, 188);
            len += 188;
        }

        @Override
        public void flush() {
            if (replaying) { len = 0; return; }
            long now = System.currentTimeMillis();
            if (len > 0 && now - lastSend >= 150) send();
        }

        private void send() {
            lastSend = System.currentTimeMillis();
            if (len == 0) return;
            if (inFlight.get() >= 3) {      // page still busy with earlier batches: drop (carousels repeat)
                len = 0;
                return;
            }
            final String b64 = Base64.encodeToString(buf, 0, len, Base64.NO_WRAP);
            len = 0;
            dispatch(b64);
        }

        private void dispatch(final String b64) {
            inFlight.incrementAndGet();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    WebView w = web;
                    if (!active || !visible || w == null || !pageReady) {
                        inFlight.decrementAndGet();
                        return;
                    }
                    // the callback fires when the page has finished processing the batch: real backpressure
                    w.evaluateJavascript("OpenDtvBml.push('" + b64 + "')", new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) { inFlight.decrementAndGet(); }
                    });
                }
            });
        }
    }
}
