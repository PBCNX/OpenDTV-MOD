package ph.pbcnx.usb;

import androidx.annotation.Nullable;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Arrays;

import ph.pbcnx.Transport;

/**
 * The Android USB transport the tuner cores talk through: interface 0, command OUT {@code 0x02},
 * command IN {@code 0x81}, TS IN {@code 0x84}, endpoints selected by address (the stable part of the
 * descriptor), 500 ms command transfers.
 *
 * <p>The two cores had byte-identical copies of this file; the only real difference was the TS batch
 * size, which the readers own now, and the name in the failure messages, which is the {@code owner}
 * argument. A third Transport used to be declared beside each copy.</p>
 */
public final class AndroidTransport implements Transport {

    /** Command timeout used by the drivers on every host. */
    public static final int COMMAND_TIMEOUT_MS = 500;

    public static final int EP_COMMAND_OUT = 0x02;
    public static final int EP_COMMAND_IN = 0x81;
    public static final int EP_TS_IN = 0x84;

    private final String owner;
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint commandOut;
    private final UsbEndpoint commandIn;
    private final UsbEndpoint tsIn;

    private AndroidTransport(String owner, UsbDeviceConnection connection, UsbInterface usbInterface,
                             UsbEndpoint commandOut, UsbEndpoint commandIn, UsbEndpoint tsIn) {
        this.owner = owner;
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.commandOut = commandOut;
        this.commandIn = commandIn;
        this.tsIn = tsIn;
    }

    /**
     * Opens the stick, claims interface 0 and finds the three bulk endpoints.
     *
     * @param owner the driver's name for its failure messages ("IT9175", "IT9308")
     */
    public static AndroidTransport open(String owner, UsbManager manager, UsbDevice device)
            throws IOException {
        if (manager == null || device == null) {
            throw new IOException(owner + ": no USB device");
        }
        UsbInterface usbInterface = device.getInterface(0);
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException(owner + ": openDevice failed - USB permission?");
        }
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            throw new IOException(owner + ": claimInterface(0) failed");
        }
        UsbEndpoint commandOut = null;
        UsbEndpoint commandIn = null;
        UsbEndpoint tsIn = null;
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            switch (endpoint.getAddress()) {
                case EP_COMMAND_OUT:
                    commandOut = endpoint;
                    break;
                case EP_COMMAND_IN:
                    commandIn = endpoint;
                    break;
                case EP_TS_IN:
                    tsIn = endpoint;
                    break;
                default:
                    break;
            }
        }
        if (commandOut == null || commandIn == null || tsIn == null) {
            connection.releaseInterface(usbInterface);
            connection.close();
            throw new IOException(owner + ": missing bulk endpoint(s): need 0x02, 0x81, 0x84");
        }
        return new AndroidTransport(owner, connection, usbInterface, commandOut, commandIn, tsIn);
    }

    @Override
    public void write(byte[] data, int timeoutMs) {
        int sent = connection.bulkTransfer(commandOut, data, data.length, timeoutMs);
        if (sent != data.length) {
            throw new IllegalStateException(owner + " command OUT: " + sent + "/" + data.length);
        }
    }

    @Override
    @Nullable public byte[] read(int maxLength, int timeoutMs) {
        byte[] buffer = new byte[maxLength];
        int received = connection.bulkTransfer(commandIn, buffer, maxLength, timeoutMs);
        if (received <= 0) {
            return null;   // "no reply": the seam's one failure convention, see ph.pbcnx.Transport
        }
        return Arrays.copyOf(buffer, received);
    }

    /** The raw connection, for the TS reader. */
    public UsbDeviceConnection connection() {
        return connection;
    }

    /** The TS bulk-IN endpoint ({@code 0x84}). */
    public UsbEndpoint tsEndpoint() {
        return tsIn;
    }

    public void close() {
        try {
            connection.releaseInterface(usbInterface);
        } catch (RuntimeException ignored) {
            // already released
        }
        connection.close();
    }
}
