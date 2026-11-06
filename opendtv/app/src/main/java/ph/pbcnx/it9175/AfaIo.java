package ph.pbcnx.it9175;

import ph.pbcnx.Transport;

/** {@link Io} over the USB command transport. */
public final class AfaIo implements Io {
    private final Transport transport;
    private int sequence;

    public AfaIo(Transport transport) {
        this.transport = transport;
    }

    private int nextSequence() {
        int value = sequence;
        sequence = (sequence + 1) & 0xFF;
        return value;
    }

    private byte[] exchange(byte[] frame, int expected) {
        try {
            transport.write(frame, 1000);
            byte[] response = transport.read(256, 1000);
            return AfaCmd.parseResponse(response, expected);
        } catch (RuntimeException error) {
            // Keep the return contract (null = no valid reply) but leave the cause in the log: the
            // caller can only report "IT9175: null" otherwise, and the USB layer knows the reason.
            android.util.Log.w("OpenDTV-Afa", "command exchange failed (expected " + expected
                    + " bytes): " + error, error);
            return null;
        }
    }

    public byte[] read(int processor, long address, int count) {
        return exchange(AfaCmd.buildRead(nextSequence(), processor, address, count), count);
    }

    public boolean write(int processor, long address, byte[] data) {
        return exchange(AfaCmd.buildWrite(nextSequence(), processor, address, data), 0) != null;
    }

    public byte[] command(int command, int processor, byte[] payload, int expected) {
        return exchange(AfaCmd.buildCommand(command, nextSequence(), 0, processor, payload), expected);
    }

    public void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public byte[] firmwareVersion(int processor) {
        return exchange(AfaCmd.buildCommand(34, nextSequence(), 0, processor, new byte[]{1}), 4);
    }
}
