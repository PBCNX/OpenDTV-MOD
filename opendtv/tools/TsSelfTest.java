import ph.opendtv.ts.*;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Self-test for the pure-Java TS library using a synthetic ISDB-T-like transport stream.
 * Run:  javac -d out app/src/main/java/ph/opendtv/ts/*.java tools/TsSelfTest.java && java -cp out TsSelfTest
 */
public class TsSelfTest {
    static int failures = 0;

    static void check(boolean ok, String msg) {
        if (!ok) failures++;
        System.out.println((ok ? "ok:   " : "FAIL: ") + msg);
    }

    // ------------------------------------------------------------ section builders
    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    static int bcd(int v) {
        return ((v / 10) << 4) | (v % 10);
    }

    static byte[] longSection(int tid, int ext, byte[] body) {
        int len = 5 + body.length + 4;
        byte[] s = new byte[3 + len];
        s[0] = (byte) tid;
        s[1] = (byte) (0xB0 | (len >> 8));
        s[2] = (byte) len;
        s[3] = (byte) (ext >> 8);
        s[4] = (byte) ext;
        s[5] = (byte) 0xC1;
        System.arraycopy(body, 0, s, 8, body.length);
        int crc = Crc32Mpeg.calc(s, 0, s.length - 4);
        s[s.length - 4] = (byte) (crc >>> 24);
        s[s.length - 3] = (byte) (crc >>> 16);
        s[s.length - 2] = (byte) (crc >>> 8);
        s[s.length - 1] = (byte) crc;
        return s;
    }

    static int mjd(LocalDate d) {
        return (int) (d.toEpochDay() + 40587);
    }

    static byte[] desc(int tag, byte[] payload) {
        return cat(b(tag, payload.length), payload);
    }

    static byte[] serviceDesc(int type, String prov, String name) {
        return desc(0x48, cat(b(type, prov.length()), ascii(prov), b(name.length()), ascii(name)));
    }

    // ------------------------------------------------------------ packetizer
    static class Gen {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Map<Integer, Integer> cc = new HashMap<>();

        int nextCc(int pid) {
            int c = cc.getOrDefault(pid, 0);
            cc.put(pid, (c + 1) & 15);
            return c;
        }

        void section(int pid, byte[] sec) {
            byte[] payload = cat(b(0), sec);
            int off = 0;
            boolean first = true;
            while (off < payload.length) {
                int n = Math.min(184, payload.length - off);
                byte[] pk = new byte[188];
                java.util.Arrays.fill(pk, (byte) 0xFF);
                pk[0] = 0x47;
                pk[1] = (byte) ((first ? 0x40 : 0) | (pid >> 8));
                pk[2] = (byte) pid;
                pk[3] = (byte) (0x10 | nextCc(pid));
                System.arraycopy(payload, off, pk, 4, n);
                out.write(pk, 0, 188);
                off += n;
                first = false;
            }
        }

        void shortSection(int pid, byte[] sec) { // no CRC (TDT)
            byte[] pk = new byte[188];
            java.util.Arrays.fill(pk, (byte) 0xFF);
            pk[0] = 0x47;
            pk[1] = (byte) (0x40 | (pid >> 8));
            pk[2] = (byte) pid;
            pk[3] = (byte) (0x10 | nextCc(pid));
            pk[4] = 0;
            System.arraycopy(sec, 0, pk, 5, sec.length);
            out.write(pk, 0, 188);
        }

        void es(int pid, int n, boolean skipOne) {
            for (int i = 0; i < n; i++) {
                int c = nextCc(pid);
                if (skipOne && i == 1) continue; // simulate a lost packet
                byte[] pk = new byte[188];
                java.util.Arrays.fill(pk, (byte) 0xAB);
                pk[0] = 0x47;
                pk[1] = (byte) (pid >> 8);
                pk[2] = (byte) pid;
                pk[3] = (byte) (0x10 | c);
                out.write(pk, 0, 188);
            }
        }

        void nulls(int n) {
            for (int i = 0; i < n; i++) {
                byte[] pk = new byte[188];
                java.util.Arrays.fill(pk, (byte) 0xFF);
                pk[0] = 0x47;
                pk[1] = 0x1F;
                pk[2] = (byte) 0xFF;
                pk[3] = 0x10;
                out.write(pk, 0, 188);
            }
        }
    }

    static final int TSID = 0x1234, ONID = 0x0F01;
    static final int SID_A = 0x0400, SID_B = 0x0401, SID_C = 0x0408;
    static final LocalDate DAY = LocalDate.of(2026, 10, 9);

    // ------------------------------------------------------------ synthetic DSM-CC carousel (on PID 0x113)
    static final int BLOCK = 64, DOWNLOAD_ID = 0x0FFFFFFF;
    static final byte[] M1_PIC = cat(ascii("JPEGHDR"), ascii("\r\n--VI_BOUNDARY\r\n"), fill(40 - 7 - 17, 0xFF));
    static final byte[] M1_BML = ascii("<bml>hello world</bml>"); // 22 bytes
    static final byte[] M1 = multipart(
            part("application/X-arib-resourceList", "", ascii("RESOURCES!")),
            part("image/jpeg", "pic.jpg", M1_PIC),
            part("text/X-arib-bml; charset=\"euc-jp\"", "main.bml", M1_BML));
    static final byte[] M2_PLAIN = multipart(part("text/X-arib-bml", "zipped.bml", repeat("0123456789", 30)));
    static final byte[] M2 = deflate(M2_PLAIN);
    static final byte[] M3 = fill(500, 0x5A);

    static byte[] fill(int n, int v) {
        byte[] r = new byte[n];
        java.util.Arrays.fill(r, (byte) v);
        return r;
    }

    static byte[] repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(s);
        return ascii(sb.toString());
    }

    static byte[] part(String ct, String loc, byte[] body) {
        return cat(ascii("\r\n--VI_BOUNDARY\r\nContent-Type: " + ct + "\r\nContent-Length: " + body.length
                + "\r\nContent-Location: " + loc + "\r\n\r\n"), body);
    }

    static byte[] multipart(byte[]... parts) {
        byte[] inner = cat(parts);
        return cat(ascii("Content-Type: multipart/mixed;boundary=\"VI_BOUNDARY\"\r\nContent-Length: " + inner.length
                + "\r\nContent-Encoding: none\r\nContent-Location: \r\n\r\n"), inner, ascii("\r\n--VI_BOUNDARY--\r\n"));
    }

    static byte[] deflate(byte[] in) {
        java.util.zip.Deflater df = new java.util.zip.Deflater();
        df.setInput(in);
        df.finish();
        byte[] buf = new byte[in.length + 64];
        int n = df.deflate(buf);
        df.end();
        return java.util.Arrays.copyOf(buf, n);
    }

    static byte[] i32b(int v) {
        return b(v >>> 24, v >>> 16, v >>> 8, v);
    }

    static byte[] dsmccMsg(int msgId, int id, byte[] body) {
        return cat(b(0x11, 0x03, msgId >> 8, msgId), i32b(id), b(0xFF, 0x00, body.length >> 8, body.length), body);
    }

    static byte[] diiSection() {
        byte[] info2 = cat(b(0x09, 5, 0), i32b(M2_PLAIN.length)); // compressed_module descriptor
        byte[] mods = cat(
                b(0, 1), i32b(M1.length), b(0, 0),
                b(0, 2), i32b(M2.length), b(0, info2.length), info2,
                b(0, 3), i32b(M3.length), b(0, 0));
        byte[] body = cat(i32b(DOWNLOAD_ID), b(0, BLOCK, 0, 0), i32b(0), i32b(0), b(0, 2, 0, 0), b(0, 3), mods, b(0, 0));
        return longSection(0x3B, 0x0000, dsmccMsg(0x1002, 0x80000001, body));
    }

    static byte[] ddbSection(int moduleId, int ver, int block, byte[] data) {
        byte[] body = cat(b(moduleId >> 8, moduleId, ver, 0xFF, block >> 8, block), data);
        return longSection(0x3C, moduleId, dsmccMsg(0x1003, DOWNLOAD_ID, body));
    }

    static void emitBlocks(Gen g, int pid, int moduleId, byte[] content, int maxBlocks) {
        int blocks = (content.length + BLOCK - 1) / BLOCK;
        for (int i = 0; i < Math.min(blocks, maxBlocks); i++) {
            int from = i * BLOCK;
            g.section(pid, ddbSection(moduleId, 0, i, java.util.Arrays.copyOfRange(content, from, Math.min(content.length, from + BLOCK))));
        }
    }

    static void emitCarousel(Gen g, int loop) {
        if (loop == 0) { // blocks BEFORE the DII: decoder must hold them until the DII arrives
            emitBlocks(g, 0x0113, 1, M1, 99);
            emitBlocks(g, 0x0113, 2, M2, 99);
            emitBlocks(g, 0x0113, 3, M3, 3);
            g.section(0x0113, diiSection());
        } else {
            g.section(0x0113, diiSection());
            emitBlocks(g, 0x0113, 1, M1, 99);
            emitBlocks(g, 0x0113, 2, M2, 99);
            emitBlocks(g, 0x0113, 3, M3, 3);
        }
    }

    static byte[] buildStream(int loops, boolean corruptPmtA, boolean dropEs) {
        return buildStream(loops, corruptPmtA, dropEs, false);
    }

    /** @param corruptPmtA flip a bit in PMT A; @param dropEs lose one video packet in the loop #3; @param carousel add a DSM-CC carousel on 0x113 */
    static byte[] buildStream(int loops, boolean corruptPmtA, boolean dropEs, boolean carousel) {
        Gen g = new Gen();

        byte[] pat = longSection(0x00, TSID, b(0x00, 0x00, 0xE0, 0x10,
                SID_A >> 8, SID_A, 0xE1, 0x00,
                SID_B >> 8, SID_B, 0xE2, 0x00,
                SID_C >> 8, SID_C, 0xE3, 0x00));

        // PMT A: H.264 video 0x111 (tag 0), AAC-ADTS audio 0x112 (tag 0x10), data carousel 0x113 (FD 0x000C), caption 0x114 (FD 0x0008)
        byte[] pmtA = longSection(0x02, SID_A, cat(
                b(0xE1, 0x11, 0xF0, 0x00),
                b(0x1B, 0xE1, 0x11, 0xF0, 0x03), desc(0x52, b(0x00)),
                b(0x0F, 0xE1, 0x12, 0xF0, 0x03), desc(0x52, b(0x10)),
                b(0x0D, 0xE1, 0x13, 0xF0, 0x08), desc(0x52, b(0x40)), desc(0xFD, b(0x00, 0x0C, 0x00)),
                b(0x06, 0xE1, 0x14, 0xF0, 0x05), desc(0xFD, b(0x00, 0x08, 0x00))));
        if (corruptPmtA) pmtA[12] ^= 0x01;

        byte[] pmtB = longSection(0x02, SID_B, cat(
                b(0xE2, 0x11, 0xF0, 0x00),
                b(0x02, 0xE2, 0x11, 0xF0, 0x00),
                b(0x11, 0xE2, 0x12, 0xF0, 0x00)));
        byte[] pmtC = longSection(0x02, SID_C, cat(
                b(0xE3, 0x11, 0xF0, 0x00),
                b(0x0D, 0xE3, 0x11, 0xF0, 0x00)));

        byte[] sdtLoop = cat(
                b(SID_A >> 8, SID_A, 0xFF), svcLoop(serviceDesc(0x01, "GMA", "GMA 7 Test")),
                b(SID_B >> 8, SID_B, 0xFF), svcLoop(serviceDesc(0x01, "GMA", "GMA 7 Alt")),
                b(SID_C >> 8, SID_C, 0xFF), svcLoop(serviceDesc(0xC0, "GMA", "Data")));
        byte[] sdt = longSection(0x42, TSID, cat(b(ONID >> 8, ONID, 0xFF), sdtLoop));

        // NIT: network name + TS information descriptor with remote_control_key_id 7
        byte[] netDescs = desc(0x40, ascii("Test Network"));
        byte[] tsInfo = desc(0xCD, cat(b(7, (3 << 2) | 1), ascii("GMA"), b(0x0F, 2, SID_A >> 8, SID_A, SID_B >> 8, SID_B)));
        byte[] tsLoopEntry = cat(b(TSID >> 8, TSID, ONID >> 8, ONID, 0xF0, tsInfo.length), tsInfo);
        byte[] nit = longSection(0x40, 0x0001, cat(
                b(0xF0, netDescs.length), netDescs,
                b(0xF0 | (tsLoopEntry.length >> 8), tsLoopEntry.length), tsLoopEntry));

        // EIT (actual, schedule) for service A with two events
        int m = mjd(DAY);
        byte[] ev1 = event(0x0001, m, 12, 0, 0, 1, 30, 0, "News", "Evening news");
        byte[] ev2 = event(0x0002, m, 13, 30, 0, 0, 30, 0, "Weather", "Forecast");
        byte[] eit = longSection(0x50, SID_A, cat(b(TSID >> 8, TSID, ONID >> 8, ONID, 0x00, 0x50), ev1, ev2));

        byte[] tdt = cat(b(0x70, 0x70, 0x05, m >> 8, m, bcd(12), bcd(21), bcd(45)));

        for (int i = 0; i < loops; i++) {
            g.section(0x0000, pat);
            g.section(0x0100, pmtA);
            g.section(0x0200, pmtB);
            g.section(0x0300, pmtC);
            g.section(0x0011, sdt);
            g.section(0x0010, nit);
            g.section(0x0012, eit);
            g.shortSection(0x0014, tdt);
            g.es(0x0111, 12, dropEs && i == 3);
            g.es(0x0112, 3, false);
            if (carousel) emitCarousel(g, i);
            g.es(0x0113, 2, false);
            g.es(0x0211, 8, false);
            g.es(0x0212, 3, false);
            g.nulls(4);
        }
        return g.out.toByteArray();
    }

    static byte[] svcLoop(byte[] descs) {
        int dl = descs.length; // running_status=4, free_CA=0
        return cat(b((4 << 5) | (dl >> 8), dl), descs);
    }

    static byte[] event(int eid, int mjd, int h, int mi, int s, int dh, int dm, int ds, String name, String text) {
        byte[] sed = desc(0x4D, cat(ascii("eng"), b(name.length()), ascii(name), b(text.length()), ascii(text)));
        return cat(b(eid >> 8, eid, mjd >> 8, mjd, bcd(h), bcd(mi), bcd(s), bcd(dh), bcd(dm), bcd(ds),
                (4 << 5) | (sed.length >> 8), sed.length), sed);
    }

    static void feedChunked(TsDemux d, byte[] data, long seed) {
        Random r = new Random(seed);
        int p = 0;
        while (p < data.length) {
            int n = Math.min(1 + r.nextInt(2500), data.length - p);
            d.feed(data, p, n);
            p += n;
        }
    }

    // ------------------------------------------------------------ tests
    public static void main(String[] args) throws Exception {
        System.out.println("== CRC / basic");
        byte[] probe = longSection(0x00, 1, b(0, 1, 0xE0, 0x10));
        check(Crc32Mpeg.calc(probe, 0, probe.length) == 0, "CRC32/MPEG over a whole section is 0");

        System.out.println("== full analysis, random chunking, leading garbage");
        byte[] stream = buildStream(20, false, false);
        byte[] long40 = buildStream(40, false, false); // each loop has a fixed size, so the halves are continuous
        int half = long40.length / 2;
        byte[] garbage = new byte[37]; // no 0x47 inside
        byte[] withGarbage = cat(garbage, java.util.Arrays.copyOfRange(long40, 0, half));
        byte[] secondHalf = java.util.Arrays.copyOfRange(long40, half, long40.length);

        TsDemux d = new TsDemux();
        feedChunked(d, withGarbage, 42);
        List<ServiceInfo> svcs = d.getServices();
        check(svcs.size() == 3, "3 services found (got " + svcs.size() + ")");
        ServiceInfo a = find(svcs, SID_A), bsv = find(svcs, SID_B), c = find(svcs, SID_C);
        check(d.getTsId() == TSID, "transport_stream_id = 0x1234");
        check("Test Network".equals(d.getNetworkName()), "network name from NIT: " + d.getNetworkName());
        check(a != null && "GMA 7 Test".equals(a.name) && "GMA".equals(a.provider), "service A name/provider");
        check(a != null && a.serviceType == 1 && a.pmtSeen && a.pmtPid == 0x100 && a.pcrPid == 0x111, "service A type/PMT/PCR");
        check(a != null && a.streams.size() == 4, "service A has 4 streams");
        check(a != null && a.firstVideo() != null && a.firstVideo().pid == 0x111 && "H.264".equals(a.firstVideo().shortCodec()), "A video 0x111 H.264");
        check(a != null && a.firstAudio() != null && a.firstAudio().pid == 0x112 && "AAC-ADTS".equals(a.firstAudio().shortCodec()), "A audio 0x112 AAC-ADTS");
        check(a != null && a.firstAudio().componentTag == 0x10 && a.firstVideo().componentTag == 0, "component tags parsed");
        boolean dc = false, cap = false;
        if (a != null) for (StreamInfo s : a.streams) {
            if (s.isDatacast() && s.pid == 0x113 && s.dataComponentId == 0x0C) dc = true;
            if (s.streamType == 0x06 && s.dataComponentId == 0x08 && !s.isDatacast()) cap = true;
        }
        check(dc, "datacast stream (type 0x0D, data_component_id 0x000C) detected");
        check(cap, "caption stream (0x06, FD 0x0008) not mistaken for datacast");
        check(bsv != null && bsv.firstVideo() != null && "MPEG-2".equals(bsv.firstVideo().shortCodec()), "B video MPEG-2");
        check(bsv != null && bsv.firstAudio() != null && "AAC-LATM".equals(bsv.firstAudio().shortCodec()), "B audio AAC-LATM");
        check(c != null && c.isDataBroadcastService() && c.virtualNumber.equals("--"), "C is a data-broadcast service without virtual number");
        check(a != null && "7.1".equals(a.virtualNumber) && bsv != null && "7.2".equals(bsv.virtualNumber), "virtual numbers 7.1 / 7.2 (got " + (a == null ? "?" : a.virtualNumber) + " / " + (bsv == null ? "?" : bsv.virtualNumber) + ")");
        check(a != null && a.remoteKey == 7, "remote_control_key_id = 7");

        List<EpgEvent> ev = d.getEvents(SID_A);
        long base = DAY.toEpochDay() * 86400000L;
        check(ev.size() == 2, "2 EPG events (got " + ev.size() + ")");
        check(ev.size() == 2 && "News".equals(ev.get(0).name) && "Evening news".equals(ev.get(0).text)
                && ev.get(0).startMs == base + 12 * 3600000L && ev.get(0).durationSec == 5400
                && ev.get(0).runningStatus == 4, "event 1 name/text/start/duration/status");
        check(ev.size() == 2 && ev.get(1).startMs == base + (13 * 60 + 30) * 60000L && ev.get(1).durationSec == 1800, "event 2 start/duration");
        check(d.getClockMs() == base + (12 * 3600 + 21 * 60 + 45) * 1000L, "TDT clock 12:21:45 -> " + TsUtil.formatTime(d.getClockMs()));

        d.sample(); // baseline
        feedChunked(d, secondHalf, 7);
        AnalyzerSnapshot snap = d.sample();
        check(snap.syncLosses >= 1, "sync loss counted for leading garbage (" + snap.syncLosses + ")");
        check(snap.ccErrors == 0, "no continuity errors in clean stream (" + snap.ccErrors + ")");
        check(snap.nullPackets > 0 && snap.rows.size() >= 10, "per-PID table populated (" + snap.rows.size() + " PIDs)");
        AnalyzerSnapshot.Row top = snap.rows.get(0);
        check(top.pid == 0x111, "highest-rate PID is video 0x111, label '" + top.label + "'");

        System.out.println("== error detection");
        TsDemux bad = new TsDemux();
        feedChunked(bad, buildStream(8, true, false), 3);
        ServiceInfo ba = find(bad.getServices(), SID_A);
        check(ba != null && !ba.pmtSeen && ba.streams.isEmpty(), "corrupted PMT rejected by CRC check");
        TsDemux lost = new TsDemux();
        lost.feed(buildStream(8, false, true), 0, buildStream(8, false, true).length);
        check(lost.sample().ccErrors == 1, "one dropped packet -> exactly 1 continuity error");

        System.out.println("== service filter");
        TsPipe pipe = new TsPipe(4 * 1024 * 1024);
        TsDemux src = new TsDemux();
        src.setPacketSink(new ServiceFilter(pipe, TSID, bsv));
        feedChunked(src, stream, 99);
        byte[] outBuf = new byte[stream.length];
        int total = 0, n;
        while ((n = pipe.read(outBuf, total, outBuf.length - total, 10)) > 0) total += n;
        check(total > 0 && total % 188 == 0, "filtered output is packet-aligned (" + total + " bytes)");
        TsDemux check2 = new TsDemux();
        feedChunked(check2, java.util.Arrays.copyOf(outBuf, total), 5);
        List<ServiceInfo> fs = check2.getServices();
        check(fs.size() == 1 && fs.get(0).serviceId == SID_B && fs.get(0).pmtSeen && fs.get(0).streams.size() == 2,
                "filtered stream contains only service B with its 2 streams");
        AnalyzerSnapshot fsnap = check2.sample();
        boolean leaked = false;
        for (AnalyzerSnapshot.Row r : fsnap.rows) if (r.pid == 0x111 && r.packets > 0 && fs.get(0).pcrPid != 0x211) leaked = true;
        for (AnalyzerSnapshot.Row r : fsnap.rows) if (r.pid == 0x0111 || r.pid == 0x0112 || r.pid == 0x0100) leaked = true;
        check(!leaked, "no packets from service A leaked through");
        check(fsnap.ccErrors == 0 && fsnap.syncLosses == 0, "filtered stream is continuous (cc " + fsnap.ccErrors + ", sync " + fsnap.syncLosses + ")");

        System.out.println("== TsPipe overflow keeps newest whole packets");
        TsPipe small = new TsPipe(188 * 4);
        byte[] w = new byte[188 * 10];
        for (int i = 0; i < 10; i++) java.util.Arrays.fill(w, i * 188, (i + 1) * 188, (byte) i);
        small.write(w, 0, w.length);
        byte[] rd = new byte[188 * 10];
        int got = small.read(rd, 0, rd.length, 10);
        check(got == 188 * 4 && rd[0] == 6 && rd[got - 1] == 9, "kept last 4 packets (got " + got + " bytes, first=" + rd[0] + ", last=" + rd[got - 1] + ")");
        small.write(w, 0, 188 * 3);
        small.write(w, 188 * 3, 188 * 3);
        got = small.read(rd, 0, rd.length, 10);
        check(got == 188 * 4 && rd[0] == 2 && rd[got - 1] == 5, "wrap-around write/drop-oldest correct (first=" + rd[0] + ", last=" + rd[got - 1] + ")");

        System.out.println("== DSM-CC data carousel");
        TsDemux cd = new TsDemux();
        feedChunked(cd, buildStream(6, false, false, true), 11);
        List<CarouselInfo> cars = cd.getCarousels();
        CarouselInfo ci = null;
        for (CarouselInfo x : cars) if (x.pid == 0x113) ci = x;
        check(ci != null && cars.size() == 2, "carousels found: 0x113 (service A) and 0x311 (service C), got " + cars.size());
        check(ci.modules.size() == 3 && ci.diiCount >= 6 && ci.ddbCount > 0, "3 modules announced by repeating DIIs (dii=" + ci.diiCount + ", ddb=" + ci.ddbCount + ")");
        CarouselModule c1 = module(ci, 1), c2 = module(ci, 2), c3 = module(ci, 3);
        check(c1 != null && c1.complete && java.util.Arrays.equals(c1.content, M1), "module 1 reassembled byte-for-byte");
        check(c1 != null && c1.resources.size() == 3 && "pic.jpg".equals(c1.resources.get(1).location)
                && "main.bml".equals(c1.resources.get(2).location), "module 1 resources: " + (c1 == null ? "-" : c1.resources.size()) + " files, names ok");
        check(c1 != null && java.util.Arrays.equals(c1.resources.get(1).body, M1_PIC), "binary part containing the boundary text kept intact (cut by Content-Length)");
        check(c1 != null && java.util.Arrays.equals(c1.resources.get(2).body, M1_BML), "text part body exact");
        check(c2 != null && c2.compressed && c2.complete && java.util.Arrays.equals(c2.content, M2_PLAIN), "zlib-compressed module 2 inflated to the original");
        check(c2 != null && c2.resources.size() == 1 && "zipped.bml".equals(c2.resources.get(0).location) && c2.resources.get(0).length == 300, "compressed module's file listed (300 bytes)");
        check(c3 != null && !c3.complete && c3.blocksReceived == 3 && c3.blocksTotal == 8 && c3.percent() == 37, "incomplete module 3 reports 3/8 blocks (37%)");
        check(ci.completeModules() == 2, "2 of 3 modules complete");

        TsDemux early = new TsDemux();
        feedChunked(early, buildStream(1, false, false, true), 5);
        CarouselInfo eci = null;
        for (CarouselInfo x : early.getCarousels()) if (x.pid == 0x113) eci = x;
        CarouselModule e1 = module(eci, 1);
        check(e1 != null && e1.complete, "blocks that arrive before the DII are kept and used (single carousel cycle)");

        System.out.println("== TsPipe back-pressure");
        TsPipe bp = new TsPipe(188 * 4);
        bp.write(new byte[188 * 4], 0, 188 * 4);
        long t0 = System.currentTimeMillis();
        bp.awaitSpace(188, 120);
        check(System.currentTimeMillis() - t0 >= 100, "awaitSpace waits while the pipe is full");
        bp.read(new byte[188 * 2], 0, 188 * 2, 10);
        t0 = System.currentTimeMillis();
        bp.awaitSpace(188, 500);
        check(System.currentTimeMillis() - t0 < 100, "awaitSpace returns at once when space exists");

        System.out.println("== FileTsSource -> TsDemux");
        final byte[] fileBytes = buildStream(10, false, false);
        final TsDemux fd = new TsDemux();
        final java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);
        ph.opendtv.source.FileTsSource fsrc = new ph.opendtv.source.FileTsSource("test",
                () -> new java.io.ByteArrayInputStream(fileBytes), 0, false);
        fsrc.start(new ph.opendtv.source.TsSource.Listener() {
            public void onData(byte[] buf, int off, int len) { fd.feed(buf, off, len); }
            public void onError(String m) { System.out.println("source error: " + m); ended.countDown(); }
            public void onEnd() { ended.countDown(); }
        });
        check(ended.await(5, java.util.concurrent.TimeUnit.SECONDS), "file source reached end of file");
        check(fd.getServices().size() == 3 && fd.sample().totalPackets == fileBytes.length / 188, "file source delivered every packet (" + fd.sample().totalPackets + ")");
        fsrc.stop();

        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static CarouselModule module(CarouselInfo ci, int id) {
        for (CarouselModule m : ci.modules) if (m.moduleId == id) return m;
        return null;
    }

    static ServiceInfo find(List<ServiceInfo> l, int sid) {
        for (ServiceInfo s : l) if (s.serviceId == sid) return s;
        return null;
    }
}
