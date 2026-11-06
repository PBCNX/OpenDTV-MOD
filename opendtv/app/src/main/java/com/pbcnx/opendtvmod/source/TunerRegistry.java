package com.pbcnx.opendtvmod.source;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The drivers this build can use.
 *
 * <p>Every provider is loaded by class name, so a build that carries fewer of them still compiles and
 * simply reports fewer supported devices - the absent ones are logged, never linked. Registration
 * order is the order of claiming: the GPL-2.0 driver (GPL-2.0) first, then the two MIT clean-room cores, then the
 * the GPL-2.0 driver port (GPL-2.0).</p>
 *
 * <p>The public tree carries the two MIT clean-room drivers (IT9175 and IT9308) and the firmware
 * IMPORT path; the GPL-2.0 providers are absent there and this class logs them. What ships
 * where, and why, is {@code DRIVERS.md} at the root of that tree: someone who meets "driver not in
 * this build" in logcat should have a file to open, not a puzzle.</p>
 */
public final class TunerRegistry {

    private static final String TAG = "TunerRegistry";

    /** Loaded reflectively: absent in the public tree, and that is not an error. */
    private static final String[] OPTIONAL = {
            // IT9175 through the MIT clean-room core (the only IT9175 driver since 2026-11-04).
            "com.pbcnx.opendtvmod.source.provider.CleanRoom9175Provider",
                        "com.pbcnx.opendtvmod.source.provider.CleanRoom9308Provider",
    };

    private static final List<TunerProvider> PROVIDERS = new ArrayList<>();

    static {
        for (String className : OPTIONAL) {
            try {
                Class<?> type = Class.forName(className);
                PROVIDERS.add((TunerProvider) type.getDeclaredConstructor().newInstance());
            } catch (ClassNotFoundException absent) {
                Log.i(TAG, "driver not in this build: " + className);
            } catch (Throwable broken) {
                // Present but unusable: say so with the cause, or a real failure reads as an absent
                // driver and the log sends the next reader looking in the wrong build.
                Log.w(TAG, "driver is present but could not be loaded: " + className, broken);
            }
        }
    }

    private TunerRegistry() {
    }

    @NonNull public static List<TunerProvider> all() {
        return Collections.unmodifiableList(PROVIDERS);
    }

    /** The provider that claims this device, or null when no driver knows it. */
    @Nullable public static TunerProvider find(UsbDevice device) {
        if (device == null) return null;
        for (TunerProvider p : PROVIDERS) {
            if (p.matches(device)) return p;
        }
        return null;
    }

    /** Human-readable list for the "no tuner found" message. */
    @NonNull public static String supportedNames() {
        StringBuilder sb = new StringBuilder();
        for (TunerProvider p : PROVIDERS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.name());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
