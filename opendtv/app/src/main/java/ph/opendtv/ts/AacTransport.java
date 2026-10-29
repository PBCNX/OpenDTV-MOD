package ph.opendtv.ts;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Last AAC transport actually seen on the wire (ADTS or LATM), independent of
 * what the PMT claims. Diagnostic UI only; the audio readers use the same
 * decision directly.
 */
public final class AacTransport {
    public static final String ADTS = "ADTS";
    public static final String LATM = "LATM";

    private static final AtomicReference<String> LAST = new AtomicReference<>();

    private AacTransport() {
    }

    public static void set(String mode) {
        LAST.set(mode);
    }

    public static String get() {
        return LAST.get();
    }

    public static void clear() {
        LAST.set(null);
    }
}
