package ph.opendtv.source;

import android.hardware.usb.UsbDevice;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The drivers this build can use.
 *
 * The IT9175 is compiled in. The private drivers are loaded by name, so a tree without
 * them still builds and simply reports fewer supported devices. Registration order is the
 * order of claiming: Siano first, then the 9308 family, then the built-in 9175.
 *
 * <p>The public fork ships <b>no</b> driver at all - every provider is absent there and this class
 * logs each one instead of failing. Why, and where each driver lives, is in {@code DRIVERS.md} at
 * the root of that tree: someone who meets "driver not in this build" in logcat should have a file
 * to open, not a puzzle.
 */
public final class TunerRegistry {

    private static final String TAG = "TunerRegistry";

    /** Loaded reflectively: absent in the public tree, and that is not an error. */
    private static final String[] OPTIONAL = {
            "ph.opendtv.source.provider.SmsRioProvider",
            "ph.opendtv.source.provider.Sony9308Provider",
    };

    private static final List<TunerProvider> PROVIDERS = new ArrayList<>();

    static {
        for (String className : OPTIONAL) {
            try {
                Class<?> type = Class.forName(className);
                PROVIDERS.add((TunerProvider) type.getDeclaredConstructor().newInstance());
            } catch (Throwable t) {
                Log.i(TAG, "driver not in this build: " + className);
            }
        }
    }

    private TunerRegistry() {
    }

    public static List<TunerProvider> all() {
        return Collections.unmodifiableList(PROVIDERS);
    }

    /** The provider that claims this device, or null when no driver knows it. */
    public static TunerProvider find(UsbDevice device) {
        if (device == null) return null;
        for (TunerProvider p : PROVIDERS) {
            if (p.matches(device)) return p;
        }
        return null;
    }

    /** Human-readable list for the "no tuner found" message. */
    public static String supportedNames() {
        StringBuilder sb = new StringBuilder();
        for (TunerProvider p : PROVIDERS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.name());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
