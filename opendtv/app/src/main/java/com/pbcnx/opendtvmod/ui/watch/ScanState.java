package com.pbcnx.opendtvmod.ui.watch;

/** The scan state: running flag and the stop request, independent of the Android loop around it. */
public final class ScanState {

    private volatile boolean scanning;
    private volatile boolean cancel;

    /** Starts a scan when idle; false means one was already running (the caller stops it). */
    public synchronized boolean begin() {
        if (scanning) return false;
        scanning = true;
        cancel = false;
        return true;
    }

    public synchronized void finish() {
        scanning = false;
    }

    public synchronized void cancel() {
        cancel = true;
    }

    public synchronized boolean cancelRequested() {
        return cancel;
    }

    public synchronized boolean isScanning() {
        return scanning;
    }
}
