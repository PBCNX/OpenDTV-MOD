package ph.pbcnx.it9308;

import ph.pbcnx.Transport;

/** IT930x message framing, per docs/protocol.md. */
public final class Bridge {
    public static final int READ_REGISTER = 0x0000;
    public static final int WRITE_REGISTER = 0x0001;
    public static final int INFO = 0x0022;
    public static final int BOOT = 0x0023;
    public static final int WRITE_FIRMWARE = 0x0029;
    public static final int READ_I2C = 0x002A;
    public static final int WRITE_I2C = 0x002B;

    private final Transport transport;
    private int sequence;

    public Bridge(Transport transport) {
        this.transport = transport;
    }

    public static int checksum(byte[] data, int end) {
        int total = 0;
        for (int i = 1; i < end; i += 2) {
            int high = data[i] & 0xFF;
            int low = (i + 1 < end) ? data[i + 1] & 0xFF : 0;
            total += (high << 8) | low;
        }
        return (~total) & 0xFFFF;
    }

    public byte[] build(int command, int seq, byte[] payload) {
        int size = payload == null ? 0 : payload.length;
        byte[] frame = new byte[size + 6];
        frame[1] = (byte) (command >> 8);
        frame[2] = (byte) command;
        frame[3] = (byte) seq;
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 4, size);
        }
        int sum = checksum(frame, frame.length - 2);
        frame[frame.length - 2] = (byte) (sum >> 8);
        frame[frame.length - 1] = (byte) sum;
        frame[0] = (byte) (frame.length - 1);
        return frame;
    }

    /** Why the last reply was refused, or null when it was accepted. */
    private String lastError;

    public String lastError() { return lastError; }

    public byte[] exchange(int command, byte[] payload, int expected) {
        byte[] frame = build(command, sequence & 0xFF, payload);
        sequence = (sequence + 1) & 0xFF;
        transport.write(frame, 1000);
        byte[] response = transport.read(256, 1000);
        return parse(response, expected);
    }

    private byte[] parse(byte[] response, int expected) {
        if (response == null || response.length < 5 || response.length != expected + 5) {
            lastError = "reply length " + (response == null ? "missing" : response.length)
                    + " is not " + (expected + 5);
            return null;
        }
        int sum = ((response[response.length - 2] & 0xFF) << 8)
                | (response[response.length - 1] & 0xFF);
        if (sum != checksum(response, response.length - 2)) {
            lastError = "reply checksum mismatch";
            return null;
        }
        if (response[2] != 0) {
            lastError = "bridge refused the command (status 0x" + Integer.toHexString(response[2] & 0xFF) + ")";
            return null;
        }
        byte[] payload = new byte[expected];
        System.arraycopy(response, 3, payload, 0, expected);
        lastError = null;
        return payload;
    }


    /** Writes one register with a short (2-byte) or long (4-byte) address. */
    public void writeRegister(int address, int value) {
        int width = address > 0xFFFF ? 4 : 2;
        byte[] payload = {1, (byte) width, (byte) (address >> 24), (byte) (address >> 16),
                (byte) (address >> 8), (byte) address, (byte) value};
        requireReply(exchange(WRITE_REGISTER, payload, 0), "write register 0x"
                + Integer.toHexString(address));
    }

    public long info() {
        byte[] value = exchange(INFO, new byte[]{1}, 4);
        if (value == null) {
            return 0;
        }
        return ((long) (value[0] & 0xFF) << 24) | ((value[1] & 0xFF) << 16)
                | ((value[2] & 0xFF) << 8) | (value[3] & 0xFF);
    }

    /** Generic I2C read: {@code [len, bus, addr << 1]}, response carries len bytes. */
    public byte[] readI2c(int bus, int address, int length) {
        byte[] payload = {(byte) length, (byte) bus, (byte) (address << 1)};
        return exchange(READ_I2C, payload, length);
    }

    /** Generic I2C write: {@code [len, bus, addr << 1, data...]}. */
    public void writeI2c(int bus, int address, byte[] data) {
        byte[] payload = new byte[3 + data.length];
        payload[0] = (byte) data.length;
        payload[1] = (byte) bus;
        payload[2] = (byte) (address << 1);
        System.arraycopy(data, 0, payload, 3, data.length);
        requireReply(exchange(WRITE_I2C, payload, 0),
                "i2c write to 0x" + Integer.toHexString(address));
    }

    /** A write is only done when the bridge answered; a timeout must not look accepted. */
    private static void requireReply(byte[] reply, String what) {
        if (reply == null) {
            throw new IllegalStateException("IT9308 " + what + ": no reply from the bridge");
        }
    }
}
