package com.pbcnx.opendtvmod.source;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

/** CLEAR_FEATURE(ENDPOINT_HALT) for a bulk endpoint; UsbDeviceConnection has no clearHalt(). */
public final class UsbHalt {

    private UsbHalt() {
    }

    /** Refusal is not fatal: the transfer still runs without a cleared halt, so stay silent. */
    public static void clear(UsbDeviceConnection connection, UsbEndpoint endpoint, int timeoutMs) {
        if (connection == null || endpoint == null) return;
        try {
            connection.controlTransfer(0x02, 0x01, 0, endpoint.getAddress(), null, 0, timeoutMs);
        } catch (RuntimeException ignored) {
            // a refused CLEAR_FEATURE leaves the transfer usable
        }
    }
}
