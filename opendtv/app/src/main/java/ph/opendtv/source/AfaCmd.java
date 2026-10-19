package ph.opendtv.source;

/**
 * Command framing of the ITE/Afatech (IT9135/IT9175 family) USB command channel, as implemented by
 * libpt275aDrv.so (Cmd_addChecksum / Cmd_readRegisters / Cmd_writeRegisters). Pure Java.
 *
 * Request (sent on bulk OUT 0x02):
 *   [0]   total length - 1 (everything after this byte, including the 2 checksum bytes)
 *   [1]   ((chip + processor) & 0x0F) << 4
 *   [2]   command: 0x00 = read registers, 0x01 = write registers
 *   [3]   sequence number (increments per command)
 *   [4]   data length (bytes to read / write)
 *   [5]   register address length (1 if processor == LINK and address <= 0xFF, else 2)
 *   [6..9] register address, 32-bit big endian
 *   [10..] data (write only)
 *   last 2 bytes: checksum, big endian
 *
 * Checksum: bytes [1 .. n-3] are summed as 16-bit big-endian words (an odd trailing byte is the high
 * byte of a last word); the checksum is the bitwise NOT of the 16-bit sum.
 *
 * Response (read from bulk IN 0x81): [0] length - 1, [1] sequence, [2] status (0 = OK),
 * [3..] data, last 2 bytes checksum (same algorithm over [1 .. n-3]).
 */
public final class AfaCmd {
    public static final int PROC_LINK = 0;
    public static final int PROC_OFDM = 8;

    public static final int CMD_READ_REGISTERS = 0x00;
    public static final int CMD_WRITE_REGISTERS = 0x01;

    private AfaCmd() {}

    public static int addrLen(int processor, long addr) {
        return (processor == PROC_LINK && addr >= 0 && addr <= 0xFF) ? 1 : 2;
    }

    public static byte[] buildRead(int seq, int chip, int processor, long addr, int count) {
        return build(CMD_READ_REGISTERS, seq, chip, processor, addr, count, null);
    }

    public static byte[] buildWrite(int seq, int chip, int processor, long addr, byte[] data) {
        return build(CMD_WRITE_REGISTERS, seq, chip, processor, addr, data.length, data);
    }

    /**
     * Generic command (Cmd_sendCommand in the vendor driver): [0] len-1, [1..2] = cmd + ((chip + processor) << 12)
     * big endian, [3] seq, [4..] payload, then checksum. The reply has the same layout as a register read
     * reply: [0] len-1, [1] seq, [2] status, [3..] reply bytes, checksum. Known: 0x22 = get firmware version
     * (payload {1}, 4 reply bytes), 0x23 = boot/start firmware, 0x24/0x25/0x29 = firmware download.
     */
    public static byte[] buildCommand(int cmd, int seq, int chip, int processor, byte[] payload) {
        int n = payload == null ? 0 : payload.length;
        byte[] f = new byte[4 + n + 2];
        int c16 = (cmd + ((chip + processor) << 12)) & 0xFFFF;
        f[1] = (byte) (c16 >> 8);
        f[2] = (byte) c16;
        f[3] = (byte) seq;
        if (n > 0) System.arraycopy(payload, 0, f, 4, n);
        finish(f);
        return f;
    }

    private static byte[] build(int cmd, int seq, int chip, int processor, long addr, int count, byte[] data) {
        int payload = data == null ? 0 : data.length;
        int bodyLen = 10 + payload;              // bytes before the checksum
        byte[] f = new byte[bodyLen + 2];
        f[1] = (byte) (((chip + processor) & 0x0F) << 4);
        f[2] = (byte) cmd;
        f[3] = (byte) seq;
        f[4] = (byte) count;
        f[5] = (byte) addrLen(processor, addr);
        f[6] = (byte) (addr >>> 24);
        f[7] = (byte) (addr >>> 16);
        f[8] = (byte) (addr >>> 8);
        f[9] = (byte) addr;
        if (data != null) System.arraycopy(data, 0, f, 10, data.length);
        finish(f);
        return f;
    }

    /** Writes the length byte and the checksum into a frame whose last two bytes are reserved. */
    static void finish(byte[] f) {
        int cs = checksum(f, f.length - 2);
        f[f.length - 2] = (byte) (cs >> 8);
        f[f.length - 1] = (byte) cs;
        f[0] = (byte) (f.length - 1);
    }

    /** NOT of the 16-bit sum of bytes [1, end) taken as big-endian words. */
    static int checksum(byte[] f, int end) {
        int sum = 0;
        for (int i = 1; i < end; i++) {
            int b = f[i] & 0xFF;
            sum += ((i - 1) & 1) == 0 ? (b << 8) : b;
        }
        return ~sum & 0xFFFF;
    }

    /**
     * Validates a response frame of {@code len} bytes and returns the data bytes, or null when the
     * length, checksum or status is bad. {@code expectData} is the number of data bytes requested.
     */
    public static byte[] parseResponse(byte[] r, int len, int expectData) {
        if (len < 5 || len != expectData + 5) return null;
        if ((r[0] & 0xFF) != len - 1) return null;
        int cs = checksum(r, len - 2);
        if (((r[len - 2] & 0xFF) << 8 | (r[len - 1] & 0xFF)) != cs) return null;
        if (r[2] != 0) return null;
        byte[] out = new byte[expectData];
        System.arraycopy(r, 3, out, 0, expectData);
        return out;
    }

    /** Status byte of a response, or -1 if too short. */
    public static int status(byte[] r, int len) {
        return len >= 3 ? (r[2] & 0xFF) : -1;
    }
}
