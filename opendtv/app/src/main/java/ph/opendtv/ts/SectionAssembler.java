package ph.opendtv.ts;

/**
 * Reassembles PSI/SI sections for one PID from TS packet payloads.
 * Handles pointer_field, sections spanning packets, several sections per packet,
 * stuffing (0xFF) and CRC validation of long-form sections.
 */
final class SectionAssembler {

    interface Sink {
        void onSection(int pid, byte[] section, int length);
    }

    private final int pid;
    private final Sink sink;
    private final byte[] buf = new byte[4096];
    private int len = 0;
    private int need = -1;

    SectionAssembler(int pid, Sink sink) {
        this.pid = pid;
        this.sink = sink;
    }

    private void reset() {
        len = 0;
        need = -1;
    }

    /** @param p start of the payload in d, end = one past its last byte. */
    void push(byte[] d, int p, int end, boolean pusi) {
        if (pusi) {
            if (p >= end) return;
            int pointer = d[p++] & 0xFF;
            if (pointer > end - p) {
                reset();
                return;
            }
            // Tail of a section started in an earlier packet.
            if (len > 0 || need >= 0) {
                consume(d, p, p + pointer);
                reset(); // anything still incomplete here is broken
            }
            p += pointer;
            reset();
            consume(d, p, end);
        } else {
            if (len == 0 && need < 0) return; // no section in progress
            consume(d, p, end);
        }
    }

    private void consume(byte[] d, int p, int end) {
        while (p < end) {
            if (len == 0 && need < 0 && (d[p] & 0xFF) == 0xFF) return; // stuffing till packet end
            if (need < 0) {
                int take = Math.min(3 - len, end - p);
                System.arraycopy(d, p, buf, len, take);
                len += take;
                p += take;
                if (len < 3) return;
                need = 3 + (((buf[1] & 0x0F) << 8) | (buf[2] & 0xFF));
                if (need > buf.length || need < 4) {
                    reset();
                    return;
                }
            }
            int take = Math.min(need - len, end - p);
            System.arraycopy(d, p, buf, len, take);
            len += take;
            p += take;
            if (len == need) {
                emit();
                reset();
            } else {
                return;
            }
        }
    }

    private void emit() {
        boolean longForm = (buf[1] & 0x80) != 0;
        if (longForm && Crc32Mpeg.calc(buf, 0, len) != 0) return; // corrupted
        sink.onSection(pid, buf, len);
    }
}
