package com.pbcnx.opendtvmod.source.provider;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.pbcnx.opendtvmod.source.driver.CleanRoom9308TunerSource;
import com.pbcnx.opendtvmod.source.TunerProvider;
import com.pbcnx.opendtvmod.source.TunerSource;

/**
 * IT9308 (048D:9308) through the clean-room core: MIT, no {@code jniLibs}, no vendor classes.
 *
 * <p>It is the only 048d path in the build since 2026-11-04, when the vendor bridge
 * ({@code the vendor provider}) was removed with its three .so files - this core claimed 048d:9308 first,
 * so an 9308 never reached the vendor path, and a 9309 was never supported.
 */
public final class CleanRoom9308Provider implements TunerProvider {

    @Override
    public String name() {
        return "IT9308";
    }

    @Override
    public boolean matches(UsbDevice device) {
        return CleanRoom9308TunerSource.matches(device.getVendorId(), device.getProductId());
    }

    @Override
    public TunerSource create(UsbManager manager, Context context, UsbDevice device) {
        // Pass 0 on purpose: the frequency must be applied by hub.tune() (see the reader-order
        // reply of 2026-11-02 - a tune() from inside start() never produced a stream on this core,
        // while the hub's tune(), with the reader restarted around it, streams at 8.28 Mbps).
        return new CleanRoom9308TunerSource(manager, context, device);
    }
}
