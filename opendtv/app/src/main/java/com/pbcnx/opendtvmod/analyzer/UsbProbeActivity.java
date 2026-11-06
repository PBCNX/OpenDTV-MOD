package com.pbcnx.opendtvmod.analyzer;

import com.pbcnx.opendtvmod.R;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.TextView;



/**
 * Endpoint diagnostic: find a supported tuner, ask for USB permission, open it and print its
 * interfaces and endpoints. The provider registry decides which sticks are supported and names
 * them; this screen never hardcodes a vendor/product id.
 */
public class UsbProbeActivity extends Activity {

    private UsbManager usbManager;
    private TextView logView;
    private UsbDeviceConnection connection;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!com.pbcnx.opendtvmod.source.UsbPermission.ACTION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                openAndDump(device);
            } else {
                log("USB permission denied.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_usb_probe);
        logView = findViewById(R.id.probe_log);
        findViewById(R.id.probe_back).setOnClickListener(v -> finish());

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        com.pbcnx.opendtvmod.source.UsbPermission.register(this, permissionReceiver);

        findTuner();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(permissionReceiver);
        if (connection != null) connection.close();
    }

    private void findTuner() {
        java.util.Map<String, UsbDevice> devices = usbManager.getDeviceList();
        log("USB devices attached: " + devices.size());
        for (UsbDevice d : devices.values()) {
            log(String.format("  %04x:%04x  %s", d.getVendorId(), d.getProductId(), d.getDeviceName()));
            if (com.pbcnx.opendtvmod.source.TunerRegistry.find(d) != null) {
                requestPermission(d);
                return;
            }
        }
        log("No tuner found for this build (" + com.pbcnx.opendtvmod.source.TunerRegistry.supportedNames()
                    + "). Plug it in (OTG) and reopen the app.");
    }

    private void requestPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            openAndDump(device);
            return;
        }
        com.pbcnx.opendtvmod.source.UsbPermission.request(this, usbManager, device, 2);
    }

    private void openAndDump(UsbDevice device) {
        connection = usbManager.openDevice(device);
        if (connection == null) {
            log("openDevice() failed.");
            return;
        }
        log("Opened. fd=" + connection.getFileDescriptor());
        log("Manufacturer: " + device.getManufacturerName());
        log("Product:      " + device.getProductName());
        log("Interfaces:   " + device.getInterfaceCount());

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            log(String.format("\nInterface %d (class=0x%02x alt=%d)",
                    intf.getId(), intf.getInterfaceClass(), intf.getAlternateSetting()));
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                log(String.format("  EP 0x%02x  %-9s %-3s maxPacket=%d",
                        ep.getAddress(), typeName(ep.getType()),
                        ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT",
                        ep.getMaxPacketSize()));
            }
        }
        com.pbcnx.opendtvmod.source.TunerProvider provider = com.pbcnx.opendtvmod.source.TunerRegistry.find(device);
        String name = provider == null ? "unsupported device" : provider.name();
        log("\n" + name + " recognised. The endpoint map above is the USB layout; this build has no"
                + " command-channel probe.");
    }

    private static String typeName(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_BULK:      return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:   return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_INT:       return "INTERRUPT";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:      return "ISOC";
            default:                                       return "?";
        }
    }

    private void log(String line) {
        runOnUiThread(() -> logView.append(line + "\n"));
    }
}
