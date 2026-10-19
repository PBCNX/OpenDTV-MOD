package ph.opendtv.ts;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class TsUtil {
    private TsUtil() {}

    public static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    public static int i32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public static int u13(byte[] b, int o) {
        return ((b[o] & 0x1F) << 8) | (b[o + 1] & 0xFF);
    }

    public static int u12(byte[] b, int o) {
        return ((b[o] & 0x0F) << 8) | (b[o + 1] & 0xFF);
    }

    public static int bcd(byte v) {
        return ((v >> 4) & 0xF) * 10 + (v & 0xF);
    }

    /** MJD (16 bit) + BCD hh mm ss at o+2..o+4 to epoch millis of the wall-clock value. */
    public static long mjdToMillis(byte[] b, int o) {
        int mjd = u16(b, o);
        long days = mjd - 40587L; // MJD of 1970-01-01
        return days * 86400000L + bcd(b[o + 2]) * 3600000L + bcd(b[o + 3]) * 60000L + bcd(b[o + 4]) * 1000L;
    }

    /**
     * Simplified ARIB STD-B24 8-bit text decoder: keeps printable ASCII, drops control codes and
     * escape sequences. Kanji/kana (2-byte JIS sets) are not decoded and are skipped.
     */
    public static String text(byte[] b, int off, int len) {
        if (len <= 0 || off < 0 || off + len > b.length) return "";
        StringBuilder sb = new StringBuilder(len);
        int end = off + len;
        for (int i = off; i < end; i++) {
            int c = b[i] & 0xFF;
            if (c == 0x1B) { // ESC: skip intermediates and one final byte
                i++;
                while (i < end && (b[i] & 0xFF) >= 0x24 && (b[i] & 0xFF) <= 0x2F) i++;
                continue;
            }
            if (c >= 0x20 && c <= 0x7E) sb.append((char) c);
        }
        return sb.toString().trim();
    }

    public static String formatTime(long ms) {
        if (ms < 0) return "--";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC")); // value is already a wall-clock
        return f.format(new Date(ms));
    }

    public static String formatClock(long ms) {
        if (ms < 0) return "--";
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}
