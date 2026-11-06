package ph.pbcnx.it9175.android;

import ph.pbcnx.usb.AndroidTransport;


import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;


/**
 * MPEG-TS reader for the IT9175 {@code 0x84} endpoint.
 *
 * This is the blocking shape only: one {@code bulkTransfer} of 32,768 bytes
 * (a 512-byte multiple) at a time, on a dedicated thread. The 9308 needed a queued
 * {@code UsbRequest} pipeline because a late consumer could stall its bridge
 * pipe; the 9175 driver has always read in this simple shape on every host, so
 * the queue is not added until a phone shows it is needed.
 *
 * A 500 ms transfer that returns nothing means "no lock yet", not an error.
 * The buffer handed to {@link Listener#onData} is reused between reads: the
 * listener must consume or copy the bytes before it returns.
 *
 * The first read after a tune may start mid-packet; this class does not align.
 * The Python driver's {@code It9175Device._align_ts} is the reference for that.
 */
public final class AndroidTsReader {

    /** Called on the reader thread with a reusable buffer. */
    public interface Listener {
        void onData(byte[] data, int length);
    }

    /** 87 x 188, the same read size the Python driver uses. */
    public static final int BATCH_BYTES = 32768;

    private static final int TIMEOUT_MS = 500;
    private static final String TAG = "It9175Ts";

    private final UsbDeviceConnection connection;
    private final UsbEndpoint endpoint;
    private final Listener listener;
    private volatile boolean running;
    private volatile int generation;
    private Thread thread;

    public AndroidTsReader(AndroidTransport transport, Listener listener) {
        this(transport.connection(), transport.tsEndpoint(), listener);
    }

    public AndroidTsReader(UsbDeviceConnection connection, UsbEndpoint endpoint, Listener listener) {
        this.connection = connection;
        this.endpoint = endpoint;
        this.listener = listener;
    }

    /** One blocking read: bytes read, or -1 on timeout/error. */
    public int read(byte[] dst, int timeoutMs) {
        return connection.bulkTransfer(endpoint, dst, dst.length, timeoutMs);
    }

    /** One blocking read with the driver's 500 ms timeout. */
    public int readBatch(byte[] buffer) {
        return read(buffer, TIMEOUT_MS);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        if (thread != null && thread.isAlive()) {
            return; // a previous loop has not exited
        }
        if (listener == null) {
            throw new IllegalStateException("start() needs a Listener; use readBatch() otherwise");
        }
        int gen = ++generation;
        running = true;
        thread = new Thread(() -> loop(gen), "it9175-ts");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop(int gen) {
        byte[] buffer = new byte[BATCH_BYTES];
        while (running && gen == generation) {
            int received;
            try {
                received = read(buffer, TIMEOUT_MS);
            } catch (RuntimeException error) {
                Log.w(TAG, "bulkTransfer failed: " + error);
                break;
            }
            if (received > 0) {
                listener.onData(buffer, received);
            }
            // received <= 0 is a timeout, a detach, or no lock yet: keep waiting
        }
        if (gen == generation) {
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
        generation++;
        Thread current = thread;
        if (current == Thread.currentThread()) {
            return; // called from the reader thread; let it exit on its own
        }
        thread = null;
        if (current == null) {
            return;
        }
        try {
            current.join(1500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (current.isAlive()) {
            thread = current; // start() must not create a second reader
        }
    }

    /**
     * Re-arm after a tune. A tune resets the TS endpoint, so stop the reader
     * before tuning and restart it after, or the transfer armed on the old
     * state can return a stale or empty batch.
     */
    public synchronized void restart() {
        stop();
        start();
    }

    public boolean isRunning() {
        return running;
    }
}
