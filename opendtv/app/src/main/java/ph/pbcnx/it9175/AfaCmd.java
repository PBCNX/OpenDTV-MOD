package ph.pbcnx.it9175;

/**
 * Command framing for the IT9175, mirroring docs/protocol.md.
 *
 * <pre>
 * [len-1][class<<4][cmd][seq][count][addrlen][addr:4][data...][cs hi][cs lo]
 * </pre>
 */
public final class AfaCmd {
    public static final int CMD_READ = 0;
    public static final int CMD_WRITE = 1;
    public static final int PROC_LINK = 0;
    public static final int PROC_OFDM = 8;

    private AfaCmd() {
    }

    public static int addressLength(int processor, long address) {
        return (processor == 0 && address >= 0 && address <= 0xFF) ? 1 : 2;
    }

    public static int checksum(byte[] frame, int end) {
        int total = 0;
        for (int i = 1; i < end; i++) {
            int value = frame[i] & 0xFF;
            total += ((i - 1) & 1) == 0 ? value << 8 : value;
        }
        return (~total) & 0xFFFF;
    }

    private static void finish(byte[] frame) {
        int sum = checksum(frame, frame.length - 2);
        frame[frame.length - 2] = (byte) (sum >> 8);
        frame[frame.length - 1] = (byte) sum;
        frame[0] = (byte) (frame.length - 1);
    }

    public static byte[] build(int cmd, int seq, int chip, int processor,
                               long address, int count, byte[] data) {
        int payload = data == null ? 0 : data.length;
        byte[] frame = new byte[payload + 12];
        frame[1] = (byte) (((chip + processor) & 0x0F) << 4);
        frame[2] = (byte) cmd;
        frame[3] = (byte) seq;
        frame[4] = (byte) count;
        frame[5] = (byte) addressLength(processor, address);
        frame[6] = (byte) (address >> 24);
        frame[7] = (byte) (address >> 16);
        frame[8] = (byte) (address >> 8);
        frame[9] = (byte) address;
        if (data != null) {
            System.arraycopy(data, 0, frame, 10, data.length);
        }
        finish(frame);
        return frame;
    }

    public static byte[] buildRead(int seq, int processor, long address, int count) {
        return build(CMD_READ, seq, 0, processor, address, count, null);
    }

    public static byte[] buildWrite(int seq, int processor, long address, byte[] data) {
        return build(CMD_WRITE, seq, 0, processor, address, data.length, data);
    }


    public static byte[] buildCommand(int command, int seq, int chip, int processor, byte[] payload) {
        int size = payload == null ? 0 : payload.length;
        byte[] frame = new byte[size + 6];
        int header = (((chip + processor) << 12) + command) & 0xFFFF;
        frame[1] = (byte) (header >> 8);
        frame[2] = (byte) header;
        frame[3] = (byte) seq;
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 4, size);
        }
        finish(frame);
        return frame;
    }

    /** Returns the payload, or null when the response is malformed or reports an error. */
    public static byte[] parseResponse(byte[] response, int expected) {
        if (response.length < 5 || response.length != expected + 5) {
            return null;
        }
        if ((response[0] & 0xFF) != response.length - 1) {
            return null;
        }
        int sum = ((response[response.length - 2] & 0xFF) << 8)
                | (response[response.length - 1] & 0xFF);
        if (sum != checksum(response, response.length - 2) || response[2] != 0) {
            return null;
        }
        byte[] payload = new byte[expected];
        System.arraycopy(response, 3, payload, 0, expected);
        return payload;
    }
}
