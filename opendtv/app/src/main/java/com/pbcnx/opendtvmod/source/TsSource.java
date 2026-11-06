package com.pbcnx.opendtvmod.source;

/* A producer of raw MPEG-TS bytes: a USB tuner or a recorded file. Pure Java. */
import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
public interface TsSource {

    interface Listener {
        /** Called on the source's own thread. The buffer is reused after the call returns. */
        void onData(byte[] buf, int off, int len);

        void onError(String message);

        /** A status line that is NOT a failure (e.g. "the tuner bridge ready"); the default drops it. */
        default void onReady(String detail) {
            // intentionally empty
        }

        /**
         * The source ended on its own (a capture file reached EOF). Not called from
         * {@link TsSource#stop()}: stopping is the caller's intent, not a source event.
         */
        void onEnd();
    }

    @NonNull String name();

    void start(Listener listener);

    void stop();

    /** True for hardware tuners; false for files. */
    boolean supportsTuning();

    /**
     * Tune to a centre frequency in kHz (ISDB-T, 6 MHz channel).
     *
     * <p>The boolean means "the request was accepted": the hardware drivers report their lock,
     * the IT9308 replay driver can only report that its captured write was accepted (it has no lock
     * concept), so a caller that needs lock reads {@link #getTunerSignal()} instead. The hub documents
     * the same rule; see {@link com.pbcnx.opendtvmod.tuner.TunerHub#ensureTuned}.</p>
     */
    boolean tune(int frequencyKhz);

    /** False while a hardware source is still starting up (firmware, init) and cannot tune yet. */
    default boolean canTune() {
        return true;
    }

    /** Tune for a channel scan: like {@link #tune} but gives up quickly on an empty channel (same return meaning). */
    default boolean scanTune(int frequencyKhz) {
        return tune(frequencyKhz);
    }

    /** Why the last start/tune failed, or null when it did not. One reason channel for drivers. */
    @Nullable default String lastError() {
        return null;
    }

    /** Latest lock/strength/SNR/BER, or null when the source has none (file). */
    @Nullable TunerSignal getTunerSignal();
}
