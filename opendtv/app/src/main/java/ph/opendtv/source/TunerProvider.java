package ph.opendtv.source;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/**
 * One USB tuner family.
 *
 * Drivers used to be selected inline in {@link ph.opendtv.tv.TunerHub} by vendor and
 * product id, which meant the source tree could not be built without every driver in it.
 * A provider is looked up through {@link TunerRegistry} by name, so a build that leaves a
 * driver out still compiles: the device is simply never claimed and the UI says so.
 */
public interface TunerProvider {

    /** Short name for logs and the "supported" message, e.g. {@code IT9175}. */
    String name();

    boolean matches(UsbDevice device);

    /**
     * Opens the device. The returned source is not started yet; the caller starts it so
     * the blocking firmware/init step stays where it always was.
     *
     * @param startKhz frequency the source should tune to on start
     */
    TunerSource create(UsbManager manager, Context context, UsbDevice device, int startKhz);
}
