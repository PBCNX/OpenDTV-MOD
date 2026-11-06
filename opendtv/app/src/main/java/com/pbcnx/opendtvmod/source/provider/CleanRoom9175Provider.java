package com.pbcnx.opendtvmod.source.provider;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.pbcnx.opendtvmod.source.driver.CleanRoom9175TunerSource;
import com.pbcnx.opendtvmod.source.TunerProvider;
import com.pbcnx.opendtvmod.source.TunerSource;

/**
 * IT9175 (048D:275A) through the MIT clean-room core.
 *
 * <p>This is the only IT9175 path: the in-tree {@code It9175Tuner}/{@code It9175Provider} driver was
 * deleted on 2026-11-04, so there is no second provider behind this one. The core is
 * {@code ph.pbcnx.it9175} (github.com/PBCNX/it9175, MIT); this app supplies the Android transport,
 * the demux feed and the firmware asset.</p>
 */
public final class CleanRoom9175Provider implements TunerProvider {

    @Override
    public String name() {
        return "IT9175";
    }

    @Override
    public boolean matches(UsbDevice device) {
        return CleanRoom9175TunerSource.matches(device.getVendorId(), device.getProductId());
    }

    @Override
    public TunerSource create(UsbManager manager, Context context, UsbDevice device) {
        return new CleanRoom9175TunerSource(manager, context, device);
    }
}
