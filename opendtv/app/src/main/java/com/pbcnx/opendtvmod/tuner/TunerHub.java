package com.pbcnx.opendtvmod.tuner;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import com.pbcnx.opendtvmod.source.Sleep;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;


import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.source.TsSource;
import com.pbcnx.opendtvmod.source.TunerProvider;
import com.pbcnx.opendtvmod.source.TunerRegistry;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * Process-wide owner of the one USB tuner. The Live Channels session and the channel-scan screen both go
 * through here, so there is only ever one tuner source, one demux and one "current owner".
 *
 * Threading: all device operations ({@link #ensureTuned}, {@link #ensurePermission}) block and must be called
 * from a background thread. {@link #acquire}, {@link #release} and the getters never block (safe on the UI thread).
 */
public final class TunerHub {
    /** Cancel check that does not require java.util.function (API 24) on minSdk 21. */
    public interface CancelCheck {
        boolean isCancelled();
    }

    private static final String TAG = "OpenDTV-TV";
    /** Keep the dongle powered this long after the last user went away (Live Channels re-opens sessions often). */
    private static final long IDLE_STOP_MS = 30_000;

    /** Whoever currently uses the tuner. Called when somebody else takes it over. */
    public interface Owner {
        void onPreempted();
    }

    private static TunerHub instance;

    @NonNull public static synchronized TunerHub get(Context c) {
        if (instance == null) instance = new TunerHub(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final UsbManager usb;
    private final Object devLock = new Object();
    private final Object idleLock = new Object();
    private final java.util.concurrent.atomic.AtomicReference<Owner> owner =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "TunerHubIdle");
            t.setDaemon(true);
            return t;
        }
    });
    private ScheduledFuture<?> idleStop;

    // guarded by devLock (source/demux are volatile so getters can read them without blocking)
    private volatile TsSource source;
    private String sourceDeviceName;
    private int currentKhz = -1;
    private boolean locked;
    /** True when the current source accepted the tune (write-accepted for replay drivers). */
    private boolean tuned;
    private volatile TsDemux demux = new TsDemux();
    private volatile String lastMessage = "";
    /** The last FAILURE a driver reported; cleared when a source opens. Status lines do not set it. */
    private volatile String lastError;

    private TunerHub(Context app) {
        this.app = app;
        this.usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
    }

    private final TsSource.Listener listener = new TsSource.Listener() {
        @Override
        public void onData(byte[] buf, int off, int len) {
            demux.feed(buf, off, len);   // read the field each time: a retune swaps in a fresh demux
        }

        @Override
        public void onError(String message) {
            lastMessage = message;
            lastError = message;
            Log.i(TAG, "tuner: " + message);
        }

        @Override
        public void onReady(String detail) {
            lastMessage = detail;
            Log.i(TAG, "tuner: " + detail);
        }

        @Override
        public void onEnd() {
            // intentionally empty
        }
    };

    // ------------------------------------------------------------------ device / permission

    @Nullable public UsbDevice findDevice() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (TunerRegistry.find(d) != null) return d;
        }
        return null;
    }

    public boolean isTunerPresent() {
        return findDevice() != null;
    }

    /** Last status line from the tuner driver (init result, lock, errors). */
    @NonNull public String lastMessage() {
        return lastMessage;
    }

    /** The last failure a driver reported, or null when the current source came up cleanly. */
    /** The last real failure reason, or null when nothing failed; status lines stay in lastMessage(). */
    @Nullable public String failure() {
        return lastError;
    }

    @Nullable public String lastError() {
        return lastError;
    }

    /**
     * Makes sure we may open the dongle; shows the system "Allow access to USB device?" dialog if needed and
     * waits for the answer. Blocks, so call it off the UI thread.
     */
    public boolean ensurePermission(long timeoutMs) {
        UsbDevice d = findDevice();
        if (d == null) {
            // The ids this build can drive: the two MIT clean-room cores.
            fail("Tuner not found (" + com.pbcnx.opendtvmod.source.TunerRegistry.supportedNames()
                    + "; plug it in with an OTG adapter).");
            return false;
        }
        if (usb.hasPermission(d)) return true;

        final CountDownLatch done = new CountDownLatch(1);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                done.countDown();
            }
        };
        com.pbcnx.opendtvmod.source.UsbPermission.register(app, r);
        try {
            com.pbcnx.opendtvmod.source.UsbPermission.request(app, usb, d, 0);
            try {
                done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            try {
                app.unregisterReceiver(r);
            } catch (RuntimeException ignored) {
                // receiver already unregistered, e.g. two detach paths ran
            }
        }
        boolean ok = usb.hasPermission(d);
        if (!ok) fail("USB permission for the tuner was not granted.");
        return ok;
    }

    // ------------------------------------------------------------------ ownership (never blocks)

    public void acquire(Owner o) {
        synchronized (idleLock) {
            if (idleStop != null) {
                idleStop.cancel(false);
                idleStop = null;
            }
        }
        Owner old = owner.getAndSet(o);
        if (old != null && old != o) old.onPreempted();
    }

    public void release(Owner o) {
        if (owner.compareAndSet(o, null)) {
            demux.setPacketSink(null);
            synchronized (idleLock) {
                if (idleStop != null) idleStop.cancel(false);
                idleStop = timer.schedule(new Runnable() {
                    @Override
                    public void run() {
                        stopIfIdle();
                    }
                }, IDLE_STOP_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    public boolean isOwner(Owner o) {
        return owner.get() == o;
    }

    /** Detaches the packet sink, but only if {@code o} is still the owner (a newer owner has its own). */
    public void detachSink(Owner o) {
        if (owner.get() == o) demux.setPacketSink(null);
    }

    private void stopIfIdle() {
        synchronized (devLock) {
            if (owner.get() != null) return;
            Log.i(TAG, "tuner idle, powering down");
            teardownLocked();
        }
    }

    // ------------------------------------------------------------------ tuning (blocks)

    @NonNull public TsDemux demux() {
        return demux;
    }

    /**
     * The source the hub is running (a tuner or a played capture), or null. The analyzer needs it
     * for the meter and the source name; ownership and lifecycle stay the hub's.
     */
    @Nullable public TsSource source() {
        return source;
    }

    @Nullable public TunerSignal signal() {
        TsSource s = source;
        return s == null ? null : s.getTunerSignal();
    }

    /**
     * Brings the dongle up if needed (firmware, init) and tunes to {@code khz}. Returns true when the
     * source is open and accepted the tune - lock is NOT the return value: where a driver reports one,
     * it is {@link #signal()} and its {@code locked} field, and a driver may legitimately take a
     * moment longer to raise it. Drivers whose tune() returns write-acceptance (IT9308 replay) fall
     * under the same rule: true means accepted, never lock. Staying on the same multiplex is free (no retune, demux kept), so
     * zapping between services of one multiplex is fast. A fresh demux is installed after every real
     * retune.
     */
    public boolean ensureTuned(int khz, CancelCheck cancelled) {
        synchronized (devLock) {
            if (!openLocked(khz)) return false;
            if (tuned && currentKhz == khz && source.canTune()) return true;
            return tuneLocked(khz, cancelled);
        }
    }

    private boolean openLocked(int khz) {
        UsbDevice d = findDevice();
        if (d == null) {
            teardownLocked();
            fail("Tuner not connected.");
            return false;
        }
        TsSource s = source;
        if (s != null && (!s.canTune() || !d.getDeviceName().equals(sourceDeviceName))) {
            Log.i(TAG, "tuner restarted (dead source or re-plugged)");
            teardownLocked();
        }
        if (source != null) return true;
        if (!usb.hasPermission(d)) {
            fail("No USB permission for the tuner.");
            return false;
        }
        TunerProvider provider = TunerRegistry.find(d);
        if (provider == null) {
            fail("No driver for this tuner (" + TunerRegistry.supportedNames() + ").");
            return false;
        }
        s = provider.create(usb, app, d);
        demux = new TsDemux();
        s.start(listener);                 // blocks: firmware download, init, first tune
        if (!s.canTune()) {
            s.stop();
            if (s.lastError() != null) fail(s.lastError());
            return false;
        }
        source = s;
        sourceDeviceName = d.getDeviceName();
        lastError = null;                  // a source that came up is not a failure
        currentKhz = -1;                   // start() does not tune: ensureTuned() will
        tuned = false;
        locked = false;
        return true;
    }

    private boolean tuneLocked(int khz, CancelCheck cancelled) {
        TsSource s = source;
        boolean ok = s.tune(khz);
        if (!ok && !cancelled.isCancelled()) {
            Sleep.sleep(300);   // the first attempt right after a frequency change sometimes misses a weak signal
            ok = s.tune(khz);
        }
        currentKhz = khz;
        tuned = ok;
        TunerSignal reported = s.getTunerSignal();   // lock comes only from the source signal
        locked = reported != null && reported.locked;
        demux = new TsDemux();
        if (!ok) fail(s.lastError() != null ? s.lastError() : "No lock on " + khz + " kHz");
        Log.i(TAG, "tune " + khz + " kHz -> " + (ok ? "accepted" : "refused")
                + (locked ? ", locked" : ", no lock report"));
        return ok;
    }

    /**
     * Run a source this hub did not create: a captured transport stream, a test, anything that
     * speaks TsSource. The hub keeps owning the demux, so playback and the data broadcast read the
     * same bytes exactly as they do from a tuner - which is what makes an off-air capture a real
     * substitute for air on the bench.
     *
     * <p>It is deliberately not a tuner: locked stays false and there is no frequency, because a
     * file is not a signal and pretending otherwise would light up the LOCK chip on a recording.
     */
    public boolean playSource(TsSource s) {
        return playSource(s, -1);
    }

    /**
     * Runs a source the hub did not create, with the frequency the caller wants remembered.
     *
     * <p>The two-argument form exists because a network source (Baron TCP) really is tuned to a
     * frequency, and reporting {@code currentKhz = -1} made the LOCK chip lie about a locked stream.
     * Pass -1 for a file or a test feed: {@code locked} still comes only from the source's own
     * {@link TsSource#getTunerSignal()}, never from a guess.
     */
    public boolean playSource(TsSource s, int khz) {
        synchronized (devLock) {
            teardownLocked();
            demux = new TsDemux();
            try {
                s.start(listener);
            } catch (RuntimeException e) {
                fail("Source failed: " + e);
                return false;
            }
            if (!s.canTune()) {
                s.stop();
                fail("Source did not start.");
                return false;
            }
            source = s;
            sourceDeviceName = null;      // no USB device behind this one
            currentKhz = khz;
            TunerSignal reported = null;
            try {
                reported = s.getTunerSignal();
            } catch (RuntimeException ignored) {
                // a source that cannot answer is treated as not locked, which is the truth
            }
            tuned = true;
            locked = reported != null && reported.locked;
            lastMessage = s.name();
            return true;
        }
    }


    /** Hub-side failures and driver errors share one channel, so consumers read lastError once. */
    private void fail(String message) {
        lastMessage = message;
        lastError = message;
    }

    private void teardownLocked() {
        lastError = null;   // a torn-down source has no failure of its own; callers set one after this
        TsSource s = source;
        source = null;
        if (s != null) s.stop();
        sourceDeviceName = null;
        currentKhz = -1;
        tuned = false;
        locked = false;
    }

    /**
     * True when the open source is a USB tuner rather than a file, a test feed or a network source.
     *
     * <p>It exists for one timing decision: a tuner delivers PAT/PMT within a second of a good
     * tune, while a network source can take ~15 s to open its TS tap (measured on the Baron box),
     * so the caller waits longer for the latter instead of declaring "no playable service" early.
     */
    public boolean isHardwareTuner() {
        return isTunerSource(source);
    }

    /** The one "is this source a hardware tuner?" predicate; TsSource.supportsTuning is the state. */
    public static boolean isTunerSource(TsSource s) {
        return s != null && s.supportsTuning();
    }

    /** True while a tuner source is open. False after shutdown() or a teardown. */
    public boolean isOpen() {
        return source != null;
    }

    /**
     * Packets the current demux has accepted. Callers sample it and compare: a rising count is the
     * only honest answer to "is the source live?", because a dongle can be plugged in and silent and
     * a capture is a source without being a tuner. {@link #isOpen()} says a source exists, this says
     * it is producing.
     */
    public long packetCount() {
        return demux.getPacketCount();
    }

    /*
     * True when the running source is a hardware tuner, i.e. when a frequency means anything. False
     * for a capture: it is live and must be played, but it cannot be tuned, and asking it to would
     * make {@link #ensureTuned} tear it down (openLocked finds no device).
     */
    /** Name of the running source ("File: TV5-BML.ts", the driver's name), or null when closed. */
    @Nullable public String sourceName() {
        TsSource s = source;
        return s == null ? null : s.name();
    }

    /** Frequency the current source was tuned to, -1 when nothing is tuned or the source is a file. */
    public int currentKhz() {
        return currentKhz;
    }

    /** Powers the dongle down right now (e.g. app exit). */
    public void shutdown() {
        synchronized (devLock) {
            teardownLocked();
        }
    }

}
