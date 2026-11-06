package ph.pbcnx.it9308.android;

import ph.pbcnx.usb.AndroidTransport;


import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * MPEG-TS reader for the IT930x 0x84 endpoint.
 *
 * The default path is the vendor's: ten queued {@code UsbRequest}s of 15,040
 * bytes each, so a late consumer does not stall the USB pipe. If queue setup
 * fails the reader falls back to the blocking {@code bulkTransfer}, which is
 * the vendor's own fallback.
 *
 * A 500 ms wait that returns nothing means "no lock yet", not an error. The
 * buffer passed to {@link Listener#onData} is reused between reads: the
 * listener must consume or copy the bytes before it returns.
 */
public final class AndroidTsReader {

    /** Called on the reader thread with a reusable buffer. */
    public interface Listener {
        void onData(byte[] data, int length);
    }

    /** 4 x 3,760 = 80 x 188. */
    public static final int BATCH_BYTES = 15040;

    /** The vendor queues ten requests. */
    public static final int QUEUE_DEPTH = 10;

    private static final int TIMEOUT_MS = 500;
    private static final String TAG = "It9308Ts";

    private final UsbDeviceConnection connection;
    private final UsbEndpoint endpoint;
    private final Listener listener;
    private final List<UsbRequest> requests = new ArrayList<>();
    private final Map<UsbRequest, ByteBuffer> buffers = new IdentityHashMap<>();
    private final Map<UsbRequest, byte[]> arrays = new IdentityHashMap<>();
    private volatile boolean running;
    private volatile int generation;
    private volatile String mode = "none";
    private int timeouts;
    private int unmapped;
    private Thread thread;

    public AndroidTsReader(AndroidTransport transport, Listener listener) {
        this(transport.connection(), transport.tsEndpoint(), listener);
    }

    public AndroidTsReader(UsbDeviceConnection connection, UsbEndpoint endpoint, Listener listener) {
        this.connection = connection;
        this.endpoint = endpoint;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        if (thread != null && thread.isAlive()) {
            return; // the previous loop has not exited; never run two requestWait callers
        }
        int gen = ++generation;
        running = true;
        if (startQueued(gen)) {
            mode = "queued";
            Log.i(TAG, "queued pipeline started");
        } else {
            mode = "blocking";
            Log.w(TAG, "queued pipeline failed; blocking fallback");
            startBlocking(gen);
        }
    }

    /** @return true when the ten-request pipeline is running */
    private boolean startQueued(int gen) {
        requests.clear();
        buffers.clear();
        arrays.clear();
        try {
            for (int i = 0; i < QUEUE_DEPTH; i++) {
                UsbRequest request = new UsbRequest();
                if (!request.initialize(connection, endpoint)) {
                    cancelRequests();
                    clearQueued();
                    return false;
                }
                ByteBuffer buffer = ByteBuffer.allocateDirect(BATCH_BYTES);
                if (!request.queue(buffer)) {
                    request.cancel();
                    cancelRequests();
                    clearQueued();
                    return false;
                }
                requests.add(request);
                buffers.put(request, buffer);
                arrays.put(request, new byte[BATCH_BYTES]);
            }
        } catch (RuntimeException error) {
            cancelRequests();
            clearQueued();
            return false;
        }
        thread = new Thread(() -> queuedLoop(gen), "it9308-ts-queue");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void queuedLoop(int gen) {
        while (running && gen == generation) {
            UsbRequest completed;
            try {
                completed = connection.requestWait(TIMEOUT_MS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                if (++timeouts % 20 == 0) {
                    Log.i(TAG, "requestWait timeouts=" + timeouts);
                }
                continue;
            } catch (RuntimeException error) {
                Log.w(TAG, "requestWait failed: " + error);
                break;
            }
            if (gen != generation) {
                break;
            }
            if (completed == null) {
                continue;
            }
            ByteBuffer buffer = buffers.get(completed);
            byte[] array = arrays.get(completed);
            if (buffer == null || array == null) {
                // A cancelled request from a previous loop can still complete;
                // consume it and keep servicing the current queue.
                if (++unmapped % 10 == 1) {
                    Log.i(TAG, "stale completion skipped, total=" + unmapped);
                }
                continue;
            }
            int length = buffer.position();
            buffer.position(0);
            if (length > 0 && length <= array.length) {
                buffer.get(array, 0, length);
                listener.onData(array, length);
            }
            buffer.clear();
            if (!running || gen != generation) {
                break;
            }
            try {
                if (!completed.queue(buffer)) {
                    Log.w(TAG, "requeue failed");
                    break;
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "requeue failed: " + error);
                break;
            }
        }
        if (gen == generation) {
            running = false;
        }
    }

    /** "queued", "blocking", or "none" - for the app's log. */
    public String mode() {
        return mode;
    }

    private void startBlocking(int gen) {
        thread = new Thread(() -> blockingLoop(gen), "it9308-ts-blocking");
        thread.setDaemon(true);
        thread.start();
    }

    private void blockingLoop(int gen) {
        byte[] buffer = new byte[BATCH_BYTES];
        while (running && gen == generation) {
            int received = connection.bulkTransfer(endpoint, buffer, BATCH_BYTES, TIMEOUT_MS);
            if (received > 0) {
                listener.onData(buffer, received);
            }
            // received <= 0 is a timeout or a detach; keep waiting until stop()
        }
        if (gen == generation) {
            running = false;
        }
    }

    private void cancelRequests() {
        for (UsbRequest request : requests) {
            try {
                request.cancel();
            } catch (RuntimeException ignored) {
                // already cancelled or connection closed
            }
        }
    }

    private void clearQueued() {
        requests.clear();
        buffers.clear();
        arrays.clear();
    }

    /**
     * One blocking read, for an app that owns its own thread.
     *
     * @return bytes read (1..buffer.length), or -1 on timeout/error
     */
    public int readBatch(byte[] buffer) {
        return connection.bulkTransfer(endpoint, buffer, buffer.length, TIMEOUT_MS);
    }

    public synchronized void stop() {
        running = false;
        generation++;
        cancelRequests();
        Thread current = thread;
        if (current == Thread.currentThread()) {
            // called from the reader thread: leave the reference so start()
            // refuses to create a second requestWait caller until it exits
            return;
        }
        thread = null;
        if (current == null) {
            clearQueued();
            return;
        }
        try {
            current.join(1500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (current.isAlive()) {
            thread = current; // start() must not run a second loop
        } else {
            clearQueued();
        }
    }

    /**
     * Re-arm after a tune or retune. A tune resets the TS endpoint; queued
     * requests armed before it never complete, so the app must stop the reader
     * before tuning and restart it afterwards.
     */
    public synchronized void restart() {
        stop();
        start();
    }

    public boolean isRunning() {
        return running;
    }
}
