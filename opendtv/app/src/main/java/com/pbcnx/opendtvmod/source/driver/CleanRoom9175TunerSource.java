package com.pbcnx.opendtvmod.source.driver;



import androidx.annotation.Nullable;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;

import ph.pbcnx.it9175.It9175Device;
import ph.pbcnx.it9175.Status;
import ph.pbcnx.usb.AndroidTransport;
import ph.pbcnx.it9175.android.AndroidTsReader;

/*
 * IT9175 (048D:275A, GMA Now / Pad TV) through the PBCNX clean-room core.
 *
 * <p>This replaces the original in-tree driver as the path the app takes. The protocol lives in
 * {@code ph.pbcnx.it9175} - the MIT core published at github.com/PBCNX/it9175 - and this class owns only
 * the Android half: opening the device, feeding the demux, tuning, and a 1 Hz status poll.
 *
 * <p>The firmware image is <b>not</b> in Java any more. It is read from
 * {@code assets/firmware/it9175-ite-v3046.bin} (9,036 bytes, sha256 451b09a4...c3575c), a private asset
 * that the public generator strips - the core's {@code Firmware} class carries only the chunk table.</p>
 */
import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.source.TunerSource;

public final class CleanRoom9175TunerSource implements TunerSource {
    /** TS read size for this driver: 32768 bytes. */
    private static final int TS_BATCH_BYTES = 32768;


    private static final String TAG = "It9175Clean";
    public static final int VID = 0x048D;
    public static final int PID = 0x275A;

    private final UsbManager manager;
    private final Context context;
    private final UsbDevice device;

    private volatile boolean running;
    private volatile boolean ready;
    private volatile boolean tuning;
    private volatile String lastError;
    private volatile TunerSignal cachedStatus;
    private AndroidTransport transport;
    private It9175Device core;
    private AndroidTsReader reader;
    private Thread readThread;
    private Thread statusThread;
    private Listener listener;

    public CleanRoom9175TunerSource(UsbManager manager, Context context, UsbDevice device) {
        this.manager = manager;
        this.context = context;
        this.device = device;
    }

    public static boolean matches(int vendorId, int productId) {
        return vendorId == VID && productId == PID;
    }

    @Override
    public String name() {
        return "IT9175";
    }

    @Override
    public boolean supportsTuning() {
        return true;
    }

    /**
     * False until the bridge answered. The other three sources already say this; without it a
     * bring-up that failed in start() left this source looking ready, and TunerHub would hand it
     * out as a tuned source.
     */
    @Override
    public boolean canTune() {
        return ready;
    }

    @Override
    public synchronized void start(Listener listener) {
        this.listener = listener;
        try {
            transport = AndroidTransport.open("IT9175", manager, device);
            core = new It9175Device(transport);
            String version = core.bringUp(readFirmware());
            Log.i(TAG, "IT9175 core " + It9175Device.PROVENANCE + " bringUp " + version);
            if (!core.init()) {
                throw new IllegalStateException("init() failed");
            }
            Log.i(TAG, "IT9175 init ok");
            reader = new AndroidTsReader(transport.connection(), transport.tsEndpoint(),
                    (data, length) -> deliver(data, length));
            ready = true;
        } catch (Throwable t) {
            lastError = "IT9175: " + (t.getMessage() == null ? t.toString() : t.getMessage());
            Log.e(TAG, "start failed: " + t);
            listener.onError(lastError);
            closeQuietly();
            return;
        }
        running = true;
        startReader();
        startStatusPoller();
    }

    /* The TS loop: their reader is blocking, so this thread owns it for the life of the source. */
    /** A dead reader is a source failure: stop claiming readiness and tell the listener once. */
    private void reportReaderDeath(String message) {
        running = false;
        Log.w(TAG, message);
        Listener l = listener;
        if (l != null) l.onError(message);
    }

    private void startReader() {
        if (readThread != null) {
            return;
        }
        readThread = new Thread(() -> {
            byte[] buf = new byte[TS_BATCH_BYTES];
            // 188-byte alignment (2026-11-04): the device's reads are 32,768 bytes, which is NOT a
            // multiple of 188, and the first read after a tune can start mid-packet. Handing those
            // batches to the demux whole fractured about one packet per read - the "TV5 glitching"
            // with MediaCodec pts jumps of 133/367 ms. Carry the remainder, emit only whole packets.
            com.pbcnx.opendtvmod.source.TsFramer framer = new com.pbcnx.opendtvmod.source.TsFramer(buf.length + 188);
            // Read-loop heartbeat: DIAGNOSTIC, and only in debug builds. It was added on 2026-11-04 to
            // tell whether the TV5 stutter lived in this loop (timeouts, long gaps between delivers) or
            // downstream (delivers steady, so the player or the air is the suspect) - it answered that,
            // and it stays because the same question comes back with every mux. Release builds carry
            // neither the counters nor the log line: a viewer's log should not narrate the reader.
            final boolean heartbeat = com.pbcnx.opendtvmod.BuildConfig.DEBUG;
            long hbStart = android.os.SystemClock.elapsedRealtime();
            long hbDelivers = 0;
            long hbBytes = 0;
            long hbGaps100 = 0;
            long hbMaxGap = 0;
            long hbTimeouts = 0;
            long lastDeliver = 0;
            while (running) {
                int n;
                try {
                    n = reader.read(buf, 500);
                } catch (Throwable t) {
                    // A reader that dies mid-stream used to look healthy: running stayed true and no
                    // listener was told, so the hub's dead-source restart could never fire.
                    if (running) reportReaderDeath("IT9175 TS reader stopped: " + t);
                    return;
                }
                if (n <= 0) {
                    // timeout is idle time, not the end of the source
                    if (heartbeat) hbTimeouts++;
                    continue;
                }
                final int whole = framer.feed(buf, n, (packets, off, len) -> {
                    Listener target = listener;
                    if (target != null) target.onData(packets, off, len);
                });
                if (whole > 0) {
                    if (heartbeat) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (lastDeliver > 0) {
                            long gap = now - lastDeliver;
                            if (gap > 100) hbGaps100++;
                            if (gap > hbMaxGap) hbMaxGap = gap;
                        }
                        lastDeliver = now;
                        hbDelivers++;
                        hbBytes += whole;
                    }
                }
                if (!heartbeat) continue;
                long elapsed = android.os.SystemClock.elapsedRealtime() - hbStart;
                if (elapsed >= 5000) {
                    Log.i(TAG, "hb " + elapsed + "ms: delivers=" + hbDelivers + " bytes=" + hbBytes
                            + " gaps>100ms=" + hbGaps100 + " maxGapMs=" + hbMaxGap
                            + " timeouts=" + hbTimeouts);
                    hbStart = android.os.SystemClock.elapsedRealtime();
                    hbDelivers = 0; hbBytes = 0; hbGaps100 = 0; hbMaxGap = 0; hbTimeouts = 0;
                }
            }
        }, "it9175-ts");
        readThread.start();
    }

    private void startStatusPoller() {
        if (statusThread != null) {
            return;
        }
        statusThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                // Reads are one register round-trip and were measured safe against a running stream
                // on this part; stay off the bridge only while a tune is replaying.
                if (!tuning) {
                    readStatusOnce();
                }
            }
        }, "it9175-status");
        statusThread.start();
    }

    private void readStatusOnce() {
        It9175Device d = core;
        if (d == null) {
            return;
        }
        try {
            Status s = d.status();
            if (s == null) {
                return;
            }
            TunerSignal out = new TunerSignal();
            out.locked = s.locked;
            out.strengthPercent = s.strengthPercent;
            out.qualityPercent = s.qualityPercent;
            out.rawStrength = s.rawStrength;
            out.rawQuality = s.rawQuality;
            // The core's "nothing read" sentinels are -1000 dBm and -1 dB; the app's are
            // Integer.MIN_VALUE and NaN. Copying them raw rendered an unread register as
            // "-1000 dBm / -1.0 dB" on the analyzer, while the 9308 path translated correctly.
            out.dbm = s.dbm <= -1000 ? Integer.MIN_VALUE : s.dbm;
            out.snrDb = s.snrDb < 0 ? Double.NaN : s.snrDb;
            out.rawSnr = s.rawSnr;
            out.berErrors = s.berErrors;
            out.berWindow = s.berWindow;
            out.ber = s.ber;
            cachedStatus = out;
        } catch (Throwable t) {
            Log.w(TAG, "status read failed: " + t);
        }
    }

    private void deliver(byte[] data, int length) {
        Listener l = listener;
        if (l == null || length <= 0) {
            return;
        }
        l.onData(data, 0, length);
    }

    @Override
    public boolean tune(int frequencyKhz) {
        It9175Device d = core;
        if (d == null) {
            lastError = "IT9175 is not open";
            return false;
        }
        tuning = true;
        try {
            if (!d.tune(frequencyKhz)) {
                lastError = "IT9175 refused " + frequencyKhz + " kHz";
                return false;
            }
            boolean locked = d.waitLock(true);
            lastError = locked ? null : "IT9175 found no lock on " + frequencyKhz + " kHz";
            return locked;
        } finally {
            tuning = false;
        }
    }

    @Override
    @Nullable public String lastError() { return lastError; }

    @Override
    public boolean scanTune(int frequencyKhz) {
        return tune(frequencyKhz);
    }

    @Override
    @Nullable public TunerSignal getTunerSignal() {
        return cachedStatus;
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread r = readThread;
        readThread = null;
        if (r != null) {
            r.interrupt();
        }
        Thread s = statusThread;
        statusThread = null;
        if (s != null) {
            s.interrupt();
        }
        closeQuietly();
        listener = null;   // stop() is the caller's intent; onEnd is only for a source's own end
    }

    private void closeQuietly() {
        if (transport != null) {
            try {
                transport.close();
            } catch (Throwable t) {
                Log.w(TAG, "close: " + t);
            }
            transport = null;
        }
        core = null;
        reader = null;
    }

    /**
     * The image comes from {@link com.pbcnx.opendtvmod.firmware.FirmwareStore}: an imported copy first, then the
     * private build's asset if it has one. 9,036 bytes and the right SHA-256, or it is not the image
     * this driver was written against and the store refuses it.
     */
    private byte[] readFirmware() throws IOException {
        return com.pbcnx.opendtvmod.firmware.FirmwareStore.load(context, com.pbcnx.opendtvmod.firmware.FirmwareStore.IT9175);
    }
}
