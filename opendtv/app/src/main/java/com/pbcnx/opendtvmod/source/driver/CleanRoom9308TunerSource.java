package com.pbcnx.opendtvmod.source.driver;



import androidx.annotation.Nullable;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;

import ph.pbcnx.it9308.It9308Device;
import ph.pbcnx.usb.AndroidTransport;
import ph.pbcnx.it9308.android.AndroidTsReader;

/*
 * The IT9308 (048D:9308) stick, driven by the clean-room core instead of the vendor bridge.
 *
 * <p>Why this exists: the vendor bridge it replaced needed {@code the vendor bridge} and its
 * {@code jniLibs} (~6.4 MB of vendor binaries), which could never be published - and an 048d:9308 never
 * reached it anyway, because this driver claims that id first. The vendor path was removed entirely on
 * 2026-11-04 (it could only ever have served a 9309, which was never supported); this one is MIT, pure
 * Java over {@code UsbDeviceConnection}, registered in {@link com.pbcnx.opendtvmod.source.TunerRegistry}.
 *
 * <p>What it is, in the note's own terms: interface 0, command endpoints {@code 0x02} OUT and
 * {@code 0x81} IN with 500 ms transfers, TS on {@code 0x84} in 15,040-byte batches, and a 952-step
 * captured bring-up sequence ({@code it9308/sequence.bin}) that already contains the 599.143 MHz
 * tune. {@code bringUp()} uploads the firmware when the bridge is blank and replays that sequence;
 * {@link #tune(int)} replays it and patches the requested frequency where the capture has its tune
 * (599.143 MHz is the frequency the capture was taken on and the only one bench-verified).
 *
 * <p>Known limits, from the same note and left visible here rather than discovered later: 599.143 MHz
 * is the only frequency verified on the bench, and the reader is the blocking 15,040-byte fallback
 * rather than the vendor's ten queued requests.
 */
import com.pbcnx.opendtvmod.source.TunerSignal;
import com.pbcnx.opendtvmod.source.TunerSource;

public final class CleanRoom9308TunerSource implements TunerSource {
    /** TS read size for this driver: 15040 bytes. */
    private static final int TS_BATCH_BYTES = 15040;


    private static final String TAG = "It9308Clean";
    /** The vendored core version this source is built against - see docs/IT9308-CORE.md. */
    private static final String CORE_VERSION = "0.2.1";

    private final UsbManager manager;
    private final UsbDevice device;
    private final Context context;

    private volatile AndroidTransport transport;
    private volatile It9308Device tuner;
    private volatile AndroidTsReader reader;
    /** The TS reader loop condition. */
    private volatile boolean running;
    /** True once start() succeeded: TsSource.canTune() reads this. */
    private volatile boolean ready;
    /** Cached firmware version text. */
    private volatile String version = "";
    /** True while a tune replay is in progress; the status poller stays off the bridge then. */
    private volatile boolean tuning;
    /** TsSource.lastError(): why the last start/tune failed, null on success. */
    private volatile String lastError;
    /** Guards the single status-watcher thread. */
    private volatile boolean statusWatcherStarted;
    /** The latest status the core reported; getTunerSignal() serves it. */
    private volatile TunerSignal cachedStatus;
    /** One raw status read per session, taken between the tune and the gate. */
    private volatile boolean rawDone;


    public CleanRoom9308TunerSource(UsbManager manager, Context context, UsbDevice device) {
        this.manager = manager;
        this.context = context;
        this.device = device;
    }

    /** The 9308 bridge only; the 9309 carries a different demod and is not claimed here. */
    public static boolean matches(int vendorId, int productId) {
        return vendorId == 0x048d && productId == 0x9308;
    }

    @Override
    public String name() {
        // Just the chip: "clean-room" was jargon on a screen the owner must read, and since 2026-11-04
        // there is no vendor path beside it to disambiguate from.
        return "IT9308";
    }

    @Override
    public boolean supportsTuning() {
        return true;
    }

    @Override
    public boolean canTune() {
        return ready;
    }

    @Override
    public synchronized void start(Listener listener) {
        try {
            transport = AndroidTransport.open("IT9308", manager, device);
            tuner = new It9308Device(transport);
            version = tuner.bringUp(readFirmware());
            int chip = tuner.chipId();
            // Which vendored core this build runs, from a log: support should not have to guess.
            Log.i(TAG, "IT9308 core " + CORE_VERSION + " (MIT, " + It9308Device.PROVENANCE + ")");
            Log.i(TAG, "bringUp " + version + ", chip 0x" + Integer.toHexString(chip)
                    + ", steps " + tuner.stepCount());
            if (chip != 0x6A) {
                Log.w(TAG, "chip id 0x" + Integer.toHexString(chip) + " is not 0x6a - image or replay?");
            }
            long t0 = android.os.SystemClock.elapsedRealtime();
            // THE FREQUENCY IS NOT TUNED HERE. bringUp() leaves the stick on the CAPTURED 599.143, and
            // measured on 2026-11-02 a tune() from inside start() never produced a stream on this core,
            // while the hub's tune() - after start() has returned, with the reader restarted around it -
            // streams at 8.28 Mbps. So start() arms the gate and the reader, and the hub applies the
            // frequency through tune(); see the reader-order reply for the whole matrix.
            Log.i(TAG, "captured tune in place; the hub applies the requested frequency through tune()");
            // One status read before the gate: safe here, and it means a caller that opens the
            // source without tuning (the analyzer does exactly that) still gets a lock/SNR value
            // instead of looking dead while packets flow.
            readStatusOnce();
            tuner.setStreaming(true);
            Log.i(TAG, "setStreaming(true) done in "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
            reader = new AndroidTsReader(transport, (data, length) -> {
                count(data, length);
                listener.onData(data, 0, length);
            });
            long t1 = android.os.SystemClock.elapsedRealtime();
            reader.start();
            Log.i(TAG, "reader started in " + (android.os.SystemClock.elapsedRealtime() - t1) + " ms");
            running = true;
            startStatusWatcher();   // after running=true, or the loop exits on its first check
            ready = true;
            lastError = null;   // a source that came up has no failure
            // TEST 1: exactly one status read, 500 ms after start, while the stream is running. The
            // hub's own polling stays off (getTunerSignal returns null) so this is the only call.
            // No timer probe: the raw capture is triggered by the stream itself (see count()).
        } catch (Throwable t) {
            lastError = "IT9308: " + (t.getMessage() == null ? t.toString() : t.getMessage());
            Log.e(TAG, "start failed: " + t, t);
            stop();
            listener.onError(lastError);
        }
    }



    /**
     * Retunes through the core's patched sequence. The first captured bring-up replays 599.143 (the
     * sticks session's own bench), but {@code Sequence.patched} reseals the frame checksums, so the
     * core's {@code tune(khz)} is the supported way to move elsewhere - it was the older note that
     * called other frequencies unvalidated, and the 18:55 update retires that.
     */
    @Override
    public boolean tune(int frequencyKhz) {
        It9308Device d = tuner;
        if (!running || d == null) {
            lastError = "IT9308 is not running";
            return false;
        }
        try {
            // Stop the reader around the tune: the tune resets the TS endpoint, and requests armed
            // across that reset never complete (the sticks session's finding - a queued reader then
            // delivers nothing at all). The blocking reader survives it, but restarting is what makes
            // both paths behave the same.
            AndroidTsReader r = reader;
            if (r != null) r.stop();
            tuning = true;
            boolean ok = d.tune(frequencyKhz);
            tuning = false;
            // ONE demod status read between the tune and the gate - the only safe place, per the
            // sticks session. Their 9d9417a reads 0x64 plainly (no bank write), so this should be
            // the first honest lock/SNR this stick has reported.
            if (ok && !rawDone) {
                rawDone = true;
                readStatusOnce();
            }
            if (ok) {
                d.setStreaming(true);
                lastError = null;
            } else lastError = d.lastError() != null ? "IT9308: " + d.lastError()
                    : "IT9308 refused " + frequencyKhz + " kHz";
            // RESTART the same reader, never create a second one: two requestWait threads on one
            // UsbDeviceConnection steal each other's completions (measured 2026-11-03 - the first
            // completion arrived with a buffer, every later one mapped to an unknown request).
            if (r != null) r.restart();
            Log.i(TAG, "tune(" + frequencyKhz + ") -> " + ok + " (reader re-armed)");
            return ok;
        } catch (Throwable t) {
            lastError = "IT9308 tune threw: " + t + (d.lastError() == null ? "" : " (" + d.lastError() + ")");
            Log.e(TAG, "tune(" + frequencyKhz + ") threw: " + t, t);
            return false;
        }
    }

    /**
     * Lock, RF level and SNR from the core's status bank (the sticks session's commit dd85bd8).
     *
     * <p>The percent scales stay -1 because they have no calibrated mapping yet, so the UI shows the
     * raw level and the dB rather than inventing a percentage. Until the first {@code known == true}
     * answer this returns null ("no meter yet", like the other drivers); a later unknown answer leaves
     * the previous snapshot in place, because a momentarily missing reading must not blank a meter
     * that is still valid.
     */
    @Override
    @Nullable public String lastError() { return lastError; }

    @Override
    @Nullable public TunerSignal getTunerSignal() {
        // The cached one-shot from start()/tune(). Polling the demod stops the TS path (measured),
        // so this never reads the chip - it only reports the last safe read.
        return cachedStatus;
    }

    @Override
    public synchronized void stop() {
        ready = false;
        running = false;
        AndroidTsReader r = reader;
        reader = null;
        if (r != null) r.stop();
        AndroidTransport t = transport;
        transport = null;
        tuner = null;
        if (t != null) t.close();
    }

    // ------------------------------------------------------------------ throughput counters
    //
    // The vendor path has a periodic stats line and this one had none, which makes the note's
    // "30-60 s packets/sync/bitrate" unanswerable from logcat. Counters only: no behaviour change.

    private long statBytes;
    private long statPackets;
    private long statBadSync;
    private long statWindowMs = android.os.SystemClock.elapsedRealtime();
    private long statWindowBytes;
    private long statLastArrivalMs;
    private long statMaxGapMs;
    private long statShortReads;
    private long statZeroReads;

    private void count(byte[] data, int length) {
        long now0 = android.os.SystemClock.elapsedRealtime();
        if (statLastArrivalMs != 0) {
            long gap = now0 - statLastArrivalMs;
            if (gap > statMaxGapMs) statMaxGapMs = gap;
        }
        statLastArrivalMs = now0;
        statBytes += length;
        statWindowBytes += length;
        // A batch shorter than 15,040 means the bridge returned a partial transfer: that is data
        // lost on the USB side, not on the air, and it is the one loss this code could be causing.
        if (length == 0) statZeroReads++;
        else if (length < TS_BATCH_BYTES) statShortReads++;
        // Packets are counted at the batch's own alignment: a batch that starts mid-packet is not
        // "bad sync", it is one packet late. After a reader restart every batch looked bad because
        // the check started at byte 0 regardless, which made the counter useless. Find the first
        // 0x47 within one packet and count from there; only a batch with no 0x47 is a sync failure.
        int start = -1;
        for (int probe = 0; probe < 188 && probe < length; probe++) {
            if (data[probe] == 0x47) {
                start = probe;
                break;
            }
        }
        if (start < 0) {
            statBadSync++;
            statPackets += length / 188;
        } else {
            for (int off = start; off + 188 <= length; off += 188) {
                statPackets++;
                if (data[off] != 0x47) statBadSync++;
            }
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - statWindowMs >= 5000) {
            double mbps = statWindowBytes * 8.0 / ((now - statWindowMs) / 1000.0) / 1_000_000.0;
            Log.i(TAG, String.format(java.util.Locale.US,
                    "stream %.2f Mbps, %d pkts, %d bad sync, %d B total, max gap %d ms, short reads %d/%d, ver %s",
                    mbps, statPackets, statBadSync, statBytes, statMaxGapMs, statShortReads,
                    statZeroReads, version));
            statMaxGapMs = 0;
            statWindowMs = now;
            statWindowBytes = 0;
        }
    }

    /**
     * The image comes from {@link com.pbcnx.opendtvmod.firmware.FirmwareStore}: an imported copy first, then the
     * private build's asset if it has one. SHA-256-checked, because a wrong byte is how a bridge gets
     * bricked.
     */
    private byte[] readFirmware() throws IOException {
        return com.pbcnx.opendtvmod.firmware.FirmwareStore.load(context, com.pbcnx.opendtvmod.firmware.FirmwareStore.IT9308);
    }
    /**
     * Re-reads the demod once a second so the meter follows the signal instead of freezing at the
     * value it had when playback started.
     *
     * <p>This used to be guarded by "only while the stream is silent", from the era when a read was
     * believed to stop the TS. Measured 2026-11-03 on the corrected 0x64 read: a 1 Hz poll is
     * harmless (60 of 60 reads with the stream at 6.1-6.3 Mbps). The only thing to stay off is a
     * tune that is replaying, which {@code tuning} covers.
     */
    private void startStatusWatcher() {
        if (statusWatcherStarted) return;
        statusWatcherStarted = true;
        new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                // Measured 2026-11-03: on the CORRECTED 0x64 read a 1 Hz poll is harmless -
                // 60 of 60 reads with the stream at 6.1-6.3 Mbps throughout. The old "polling
                // kills the TS" rule came from the broken page-1/bank read. Only stay off the
                // bridge while a tune is replaying, as the 9175 driver does.
                if (!tuning) readStatusOnce();
            }
        }, "it9308-status-poll").start();
    }


    /**
     * The one translation of the core's status into the app's meter. It was written twice in this
     * class; the second copy is how the two could drift.
     */
    private static TunerSignal mapStatus(ph.pbcnx.it9308.SignalStatus s) {
        if (s == null || !s.known) return null;
        TunerSignal mapped = new TunerSignal();
        mapped.locked = s.locked;
        mapped.rawStrength = s.rawStrength;
        // S is a relative meter, like the 9175's raw clamp - not a dBm instrument: the core already
        // clamps to 0..100, so pass it through (GMA 66, empty 7).
        mapped.strengthPercent = Math.min(100, s.strength >= 0 ? s.strength : s.rawStrength);
        mapped.rawSnr = s.rawSnr;
        mapped.snrDb = s.snrDb;
        // The 9175 gets percentages from the vendor bridge; we have SNR. Map it 0..30 dB to 0..100 %
        // so the analyzer's quality bar means something, and leave strength at -1: we never decoded
        // an RF level and will not invent one.
        mapped.qualityPercent = Double.isNaN(s.snrDb) ? -1
                : Math.max(1, Math.min(100, (int) Math.round(s.snrDb / 30.0 * 100.0)));
        return mapped;
    }

    private void readStatusOnce() {
        It9308Device d = tuner;
        if (d == null) return;
        try {
            ph.pbcnx.it9308.SignalStatus s = d.readStatus();
            TunerSignal mapped = mapStatus(s);
            // The 1 Hz watcher calls this for the whole session: log the meter only when it moves,
            // or the log is a per-second repeat that hides the tune/error lines around it.
            if (mapped == null || cachedStatus == null
                    || mapped.locked != cachedStatus.locked
                    || mapped.strengthPercent != cachedStatus.strengthPercent
                    || mapped.qualityPercent != cachedStatus.qualityPercent) {
                Log.i(TAG, "live status: " + s);
            }
            if (mapped != null) {
                cachedStatus = mapped;   // an unknown answer leaves the last good meter in place
            }
        } catch (Throwable t) {
            Log.w(TAG, "one-shot status failed: " + t);
        }
    }

}
