package com.pbcnx.opendtvmod.ts.util;

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
     * The broadcast clock, and the only way it may be shown.
     *
     * <p>A TDT/TOT carries the broadcaster's own wall clock - the engine session's rule, 31 Oct:
     * "TOT is broadcast local time, PH always +8:00" - so the value is already local and must never
     * be converted again. {@link #mjdToMillis} therefore stores it as an epoch whose UTC fields ARE
     * that wall clock, {@link #formatTime} prints it in UTC, and the label says which offset the
     * broadcaster was on. Converting it to the device's zone added a second eight hours in the
     * Philippines (22:32 on air read as 06:32 the next day) and would shift again on a device set to
     * another zone.
     */
    public static final String BROADCAST_ZONE_LABEL = "UTC+8";

    /**
     * The receiver's rule, which is the one this app follows.
     *
     * <p>Measured, not inferred (BMLPLAYER swept eight PH muxes off air, 31 Oct 22:31-22:35; PBCNX-DTV
     * explains the hardware behaviour from its TimeInjector, test 2026-10-06): the ABS-CBN TVplus
     * ignores the TOT's local_time_offset descriptor and <b>adds eight hours to whatever the field
     * holds</b>. So the same box shows GMA correctly (its field is UTC) and TV5 eight hours ahead (its
     * field is already local), and the descriptor decides nothing: GMA declares PHL +0:00, TV5 also
     * declares +0:00, Solar declares +08:00 and is eighteen minutes slow.
     *
     * <p>A host that "corrects" by the descriptor disagrees with the receiver the viewer is watching,
     * which is the failure this app spent a day chasing. So: display the field plus eight hours,
     * always, and treat field-versus-descriptor disagreement as a diagnostic only.
     */
    public static final long RECEIVER_CLOCK_OFFSET_MS = 8L * 3600L * 1000L;

    /** The offset the display is in: the receiver's, so the field plus this. */
    public static String broadcastZoneLabel() {
        return BROADCAST_ZONE_LABEL;
    }

    /** The clock as the set shows it: the field plus eight hours, whatever field or descriptor says. */
    public static long receiverClock(long fieldMs) {
        return fieldMs < 0 ? -1 : fieldMs + RECEIVER_CLOCK_OFFSET_MS;
    }

    /** Date and time as the set shows it. */
    public static String formatReceiverTime(long fieldMs) {
        return formatTime(receiverClock(fieldMs));
    }

    /** Time of day as the set shows it. */
    public static String formatReceiverClock(long fieldMs) {
        return formatClock(receiverClock(fieldMs));
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
        f.setTimeZone(TimeZone.getTimeZone("UTC")); // the value IS the broadcast wall clock
        return f.format(new Date(ms));
    }

    public static String formatClock(long ms) {
        if (ms < 0) return "--";
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}
