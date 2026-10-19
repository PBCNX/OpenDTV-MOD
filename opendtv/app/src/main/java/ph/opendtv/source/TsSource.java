package ph.opendtv.source;

/** A producer of raw MPEG-TS bytes: a USB tuner or a recorded file. Pure Java. */
public interface TsSource {

    interface Listener {
        /** Called on the source's own thread. The buffer is reused after the call returns. */
        void onData(byte[] buf, int off, int len);

        void onError(String message);

        void onEnd();
    }

    String name();

    void start(Listener listener);

    void stop();

    /** True for hardware tuners; false for files. */
    boolean supportsTuning();

    /** Tune to a centre frequency in kHz (ISDB-T, 6 MHz channel). */
    boolean tune(int frequencyKhz);

    /** False while a hardware source is still starting up (firmware, init) and cannot tune yet. */
    default boolean isReady() {
        return true;
    }

    /** Tune for a channel scan: like {@link #tune} but gives up quickly on an empty channel. */
    default boolean scanTune(int frequencyKhz) {
        return tune(frequencyKhz);
    }

    /** Latest lock/strength/SNR/BER, or null when the source has none (file). */
    SignalStatus getSignalStatus();
}
