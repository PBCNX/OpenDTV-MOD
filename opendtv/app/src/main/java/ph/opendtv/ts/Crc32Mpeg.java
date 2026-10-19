package ph.opendtv.ts;

/** CRC-32/MPEG-2 as used by PSI/SI long sections. A section including its CRC field yields 0. */
public final class Crc32Mpeg {
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int j = 0; j < 8; j++) {
                c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : (c << 1);
            }
            TABLE[i] = c;
        }
    }

    private Crc32Mpeg() {}

    public static int calc(byte[] b, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc = (crc << 8) ^ TABLE[((crc >>> 24) ^ (b[i] & 0xFF)) & 0xFF];
        }
        return crc;
    }
}
