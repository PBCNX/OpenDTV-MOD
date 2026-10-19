package ph.opendtv.ts;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Keeps only one service of a multi-service ISDB-T transport stream: passes that service's PMT, PCR
 * and elementary streams, drops the original PAT and injects a single-program PAT instead.
 * This lets a stock TS extractor (ExoPlayer) lock onto the chosen service.
 */
public final class ServiceFilter implements PacketSink {
    private static final int PAT_INTERVAL = 100; // passed packets between PAT insertions
    private static final int BATCH_PACKETS = 64;

    private final TsPipe out;
    private final Set<Integer> pass = new HashSet<>();
    private final byte[] patSection = new byte[16];
    private final byte[] batch = new byte[188 * BATCH_PACKETS];
    private int batchLen = 0;
    private int patCc = 0;
    private int sinceLastPat = PAT_INTERVAL;
    private FileOutputStream recordFos;

    // ---- closed captions (ARIB STD-B24 on a data_component_id 0x0008 PES stream) ----
    /** Receives decoded captions on the tuner thread. */
    public interface CaptionSink {
        /**
         * @param dueMs  when to show it, in player-position milliseconds ({@link Long#MIN_VALUE} = unknown, show now)
         * @param text   caption text, empty = clear the caption
         */
        void onCaption(long dueMs, String text);
    }

    /** Receives the raw caption PES payload (for the styled WebView caption display) on the tuner thread. */
    public interface RawCaptionSink {
        /**
         * @param dueMs    player-position ms at which it is due ({@link Long#MIN_VALUE} = unknown, show now)
         * @param streamId PES stream_id: 0xBD = caption, 0xBF = superimpose
         * @param payload  PES payload (data_identifier first), PES header already removed
         */
        void onCaptionPes(long dueMs, int streamId, byte[] payload);
    }

    private volatile CaptionSink captionSink;
    private volatile RawCaptionSink rawCaptionSink;
    private volatile int captionLanguage = 1;     // 1 = first (main) caption language, 2 = second
    private int capPid = -1;
    private final byte[] capBuf = new byte[8192];
    private int capLen;
    private int capNeed;
    private final Set<Integer> mediaPids = new HashSet<>();
    private long basePts = -1;                 // DTS/PTS of the first A/V PES passed = player position 0

    public ServiceFilter(TsPipe out, int tsId, ServiceInfo svc) {
        this.out = out;
        pass.add(svc.pmtPid);
        if (svc.pcrPid >= 0) pass.add(svc.pcrPid);
        for (StreamInfo s : svc.streams) {
            pass.add(s.pid);
            if (s.isVideo() || s.isAudio()) mediaPids.add(s.pid);
            if (s.dataComponentId == 0x0008 && capPid < 0) capPid = s.pid;
        }
        buildPat(tsId < 0 ? 1 : tsId, svc.serviceId, svc.pmtPid);
    }

    public void setCaptionSink(CaptionSink sink) {
        this.captionSink = sink;
    }

    public void setRawCaptionSink(RawCaptionSink sink) {
        this.rawCaptionSink = sink;
    }

    /** 1 = first (main) caption language, 2 = second language. */
    public void setCaptionLanguage(int language) {
        this.captionLanguage = language == 2 ? 2 : 1;
    }

    /** Chainable variant of {@link #setCaptionSink}. */
    public ServiceFilter setCaptionSink2(CaptionSink sink) {
        this.captionSink = sink;
        return this;
    }

    public synchronized void setRecordStream(FileOutputStream fos) {
        this.recordFos = fos;
    }

    private void buildPat(int tsId, int sid, int pmtPid) {
        byte[] s = patSection;
        s[0] = 0x00;                         // table_id
        s[1] = (byte) 0xB0;                  // syntax=1, reserved, length hi = 0
        s[2] = 13;                           // section_length
        s[3] = (byte) (tsId >> 8);
        s[4] = (byte) tsId;
        s[5] = (byte) 0xC1;                  // version 0, current
        s[6] = 0;
        s[7] = 0;
        s[8] = (byte) (sid >> 8);
        s[9] = (byte) sid;
        s[10] = (byte) (0xE0 | ((pmtPid >> 8) & 0x1F));
        s[11] = (byte) pmtPid;
        int crc = Crc32Mpeg.calc(s, 0, 12);
        s[12] = (byte) (crc >>> 24);
        s[13] = (byte) (crc >>> 16);
        s[14] = (byte) (crc >>> 8);
        s[15] = (byte) crc;
    }

    @Override
    public void onPacket(byte[] b, int o) {
        int pid = ((b[o + 1] & 0x1F) << 8) | (b[o + 2] & 0xFF);
        if (pid == TsDemux.PID_PAT || !pass.contains(pid)) return;
        if ((b[o + 1] & 0x80) == 0) {                       // skip corrupt (TEI) packets for the helpers below
            if (pid == capPid && (captionSink != null || rawCaptionSink != null)) captionPacket(b, o);
            else if (basePts < 0 && (b[o + 1] & 0x40) != 0 && mediaPids.contains(pid)) readBasePts(b, o);
        }
        if (sinceLastPat >= PAT_INTERVAL) {
            appendPat();
            sinceLastPat = 0;
        }
        sinceLastPat++;
        append(b, o);
    }

    private static int payloadStart(byte[] b, int o) {
        int afc = (b[o + 3] >> 4) & 3;
        if ((afc & 1) == 0) return -1;
        int p = o + 4;
        if ((afc & 2) != 0) p += 1 + (b[p] & 0xFF);
        return p < o + 188 ? p : -1;
    }

    private static long pts(byte[] b, int q) {
        return (((long) (b[q] >> 1) & 7) << 30) | ((long) (b[q + 1] & 0xFF) << 22) | ((long) ((b[q + 2] & 0xFF) >> 1) << 15)
                | ((long) (b[q + 3] & 0xFF) << 7) | ((b[q + 4] & 0xFF) >> 1);
    }

    /** The player's timeline starts at the first DTS (else PTS) the extractor sees (PesReader adjusts on DTS). */
    private void readBasePts(byte[] b, int o) {
        int p = payloadStart(b, o);
        if (p < 0 || p + 14 > o + 188 || b[p] != 0 || b[p + 1] != 0 || b[p + 2] != 1) return;
        int flags = (b[p + 7] & 0xC0) >> 6;
        if (flags == 2 && p + 14 <= o + 188) basePts = pts(b, p + 9);
        else if (flags == 3 && p + 19 <= o + 188) basePts = pts(b, p + 14);
    }

    private void captionPacket(byte[] b, int o) {
        int p = payloadStart(b, o);
        if (p < 0) return;
        if ((b[o + 1] & 0x40) != 0) {                       // new PES
            capLen = 0;
            capNeed = 0;
        } else if (capLen == 0) {
            return;                                         // joined mid-PES
        }
        int n = Math.min(o + 188 - p, capBuf.length - capLen);
        System.arraycopy(b, p, capBuf, capLen, n);
        capLen += n;
        if (capNeed == 0 && capLen >= 6) {
            int plen = ((capBuf[4] & 0xFF) << 8) | (capBuf[5] & 0xFF);
            capNeed = plen == 0 ? -1 : plen + 6;
        }
        if (capNeed > 0 && capLen >= capNeed) {
            emitCaption(capLen);
            capLen = 0;
            capNeed = 0;
        }
    }

    private void emitCaption(int len) {
        CaptionSink sink = captionSink;
        RawCaptionSink raw = rawCaptionSink;
        if ((sink == null && raw == null) || len < 14 || capBuf[0] != 0 || capBuf[1] != 0 || capBuf[2] != 1) return;
        long due = Long.MIN_VALUE;
        if ((capBuf[7] & 0x80) != 0 && basePts >= 0) {
            long d = (pts(capBuf, 9) - basePts) & 0x1FFFFFFFFL;
            if (d > 0x0FFFFFFFFL) d -= 0x200000000L;        // 33-bit wrap, signed
            long ms = d / 90;
            if (ms > -5_000 && ms < 120_000) due = Math.max(ms, 0);
        }
        if (raw != null) {
            int hdr = 9 + (capBuf[8] & 0xFF);               // PES header: fixed 9 bytes + PES_header_data_length
            if (hdr < len) {
                try {
                    raw.onCaptionPes(due, capBuf[3] & 0xFF, Arrays.copyOfRange(capBuf, hdr, len));
                } catch (RuntimeException e) {
                    // a caption problem must never disturb the video path
                }
            }
        }
        if (sink == null) return;
        AribCaption.Result r;
        try {
            r = AribCaption.decodePes(capBuf, len, captionLanguage);
        } catch (RuntimeException e) {
            return;                                         // malformed caption: never disturb the video path
        }
        if (r == null || !r.statement) return;
        sink.onCaption(due, r.text);
    }

    private void appendPat() {
        byte[] pk = new byte[188];
        Arrays.fill(pk, (byte) 0xFF);
        pk[0] = 0x47;
        pk[1] = 0x40; // payload_unit_start, PID 0
        pk[2] = 0x00;
        pk[3] = (byte) (0x10 | (patCc++ & 0x0F));
        pk[4] = 0; // pointer_field
        System.arraycopy(patSection, 0, pk, 5, patSection.length);
        append(pk, 0);
    }

    private void append(byte[] src, int o) {
        if (batchLen + 188 > batch.length) flush();
        System.arraycopy(src, o, batch, batchLen, 188);
        batchLen += 188;
        if (recordFos != null) {
            try {
                recordFos.write(src, o, 188);
            } catch (IOException ignored) {}
        }
    }

    @Override
    public void flush() {
        if (batchLen > 0) {
            out.write(batch, 0, batchLen);
            batchLen = 0;
        }
    }
}
