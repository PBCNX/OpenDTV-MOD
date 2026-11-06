package com.pbcnx.opendtvmod.dev;

import androidx.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

import com.pbcnx.opendtvmod.bml.BmlController;
import com.pbcnx.opendtvmod.ts.TsDemux;
import com.pbcnx.opendtvmod.ts.caption.CaptionProbe;

/**
 * The developer surface's process-wide state, owned by {@link com.pbcnx.opendtvmod.App} instead of by static
 * fields on the actions class: the door taps, the unlock, the row handlers and the demux hand-off.
 *
 * <p>It is still one instance for the process - the hidden screen is a different activity and the
 * handlers close over the screen that registered them - but it is one typed object a caller asks the
 * Application for, not a pile of mutable statics that any class can read and write. Handlers retire
 * with their owner via {@link #unregister}, so a destroyed activity is never a live target.</p>
 */
public final class DeveloperSurface {

    private final Map<String, Runnable> handlers = new HashMap<>();

    /** Resolves the demux at click time: the hub installs a fresh one on every retune. */
    private volatile java.util.function.Supplier<TsDemux> demuxSource;

    /** Resolves the newest caption probe (owned by the caption filter). */
    private volatile java.util.function.Supplier<CaptionProbe> captionProbeSource;
    /** Resolves the BML page state: the live controller (if any) and the last stashed answer. */
    private volatile java.util.function.Supplier<BmlState> bmlStateSource;

    private boolean unlocked;
    private int doorTaps;

    /** The demux the watch screen is reading right now, or null when nothing is tuned. */
    @Nullable public TsDemux demux() {
        java.util.function.Supplier<TsDemux> source = demuxSource;
        return source == null ? null : source.get();
    }

    /** One row's BML state: a live controller (or null) and the last stashed snapshot. */
    public static final class BmlState {
        public final BmlController controller;
        public final String stashed;
        public final long stashedAtMs;

        public BmlState(BmlController controller, String stashed, long stashedAtMs) {
            this.controller = controller;
            this.stashed = stashed;
            this.stashedAtMs = stashedAtMs;
        }
    }

    /** The newest caption probe, or null when nothing has played. */
    @Nullable public CaptionProbe captionProbe() {
        java.util.function.Supplier<CaptionProbe> source = captionProbeSource;
        return source == null ? null : source.get();
    }

    /** Registered by the watch screen beside its demux; cleared when that screen goes away. */
    public void setCaptionProbe(java.util.function.Supplier<CaptionProbe> source) {
        captionProbeSource = source;
    }

    /** The BML page state the diagnostics row reads, or null when nothing has opened a page. */
    @Nullable public BmlState bmlState() {
        java.util.function.Supplier<BmlState> source = bmlStateSource;
        return source == null ? null : source.get();
    }

    public void setBmlState(java.util.function.Supplier<BmlState> source) {
        bmlStateSource = source;
    }

    /** Set on resume (hub::demux) and cleared with the activity that owned it. */
    public void setDemux(java.util.function.Supplier<TsDemux> source) {
        demuxSource = source;
    }

    /** How many times the About image has been tapped in this process. */
    public synchronized int doorTaps() {
        return doorTaps;
    }

    /**
     * One tap on the About image: how many taps are still needed, or 0 when this tap unlocks (and 0
     * for every tap after that - ask {@link #isUnlocked()} when the difference matters).
     */
    public synchronized int tapDoor() {
        if (unlocked) return 0;
        doorTaps++;
        if (doorTaps >= DeveloperActions.DOOR_TAPS) {
            unlocked = true;
            return 0;
        }
        return DeveloperActions.DOOR_TAPS - doorTaps;
    }

    /** Unlocks without the taps. The developers screen and the tests use the count; this is the door. */
    public synchronized void unlock() {
        unlocked = true;
    }

    /** True once the About image has been tapped enough times in this process. */
    public synchronized boolean isUnlocked() {
        return unlocked;
    }

    /** Makes {@code id} runnable; registering the same id again replaces the handler. */
    public synchronized void register(String id, Runnable action) {
        if (id == null || id.isEmpty()) return;
        if (action == null) handlers.remove(id);
        else handlers.put(id, action);
    }

    /** The handler for {@code id}, or null when nobody registered one. */
    @Nullable public synchronized Runnable handler(String id) {
        return id == null ? null : handlers.get(id);
    }

    /** Retires handlers an owner registered before it goes away. */
    public synchronized void unregister(String... ids) {
        for (String id : ids) {
            if (id != null) handlers.remove(id);
        }
    }

    /** Drops every registered handler. Tests only. */
    public synchronized void clear() {
        handlers.clear();
    }
}
