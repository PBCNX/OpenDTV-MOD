package com.pbcnx.opendtvmod.source;

import androidx.annotation.NonNull;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/**
 * One USB tuner family.
 *
 * Drivers used to be selected inline in {@link com.pbcnx.opendtvmod.tuner.TunerHub} by vendor and
 * product id, which meant the source tree could not be built without every driver in it.
 * A provider is looked up through {@link TunerRegistry} by name, so a build that leaves a
 * driver out still compiles: the device is simply never claimed and the UI says so.
 */
public interface TunerProvider {

    /** Short name for logs and the "supported" message, e.g. {@code IT9175}. */
    @NonNull String name();

    boolean matches(@NonNull UsbDevice device);

    /**
     * Opens the device. The returned source is not started yet; the caller starts it so
     * the blocking firmware/init step stays where it always was.
     *
     * <p>{@code start()} only brings the source up; it never tunes. The one tune path is
     * {@link com.pbcnx.opendtvmod.tuner.TunerHub#ensureTuned}, which calls {@link TunerSource#tune} after
     * the source reports ready - so a driver cannot interpret a start frequency on its own.</p>
     */
    @NonNull TunerSource create(UsbManager manager, Context context, UsbDevice device);
}
