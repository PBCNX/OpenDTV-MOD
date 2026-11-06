package ph.pbcnx.it9175;

import java.math.BigInteger;

/** IT9175 tuner: script interpreter, tuning maths, lock and status. */
public final class Tuner {
    public static final int LINK = 0;
    public static final int OFDM = 8;
    public static final int BW_KHZ = 6000;

    private static final int[] NV = {48, 32, 24, 16, 12, 8, 6, 4, 2};
    private static final BigInteger MAGIC = new BigInteger("AAAAAAAAAAAAAAAB", 16);

    private final Io io;
    private int clock = -1;
    private int tunerType;
    private int ed03;
    private int mulP;
    private int mulQ = 1;
    private final long[] thr = {53000, 74000, 111000, 148000, 222000, 296000, 445000, 573000, 950000};
    private boolean initDone;
    private boolean flag;
    private int lastFreqKhz;

    public Tuner(Io io) {
        this.io = io;
    }

    // -- io helpers ---------------------------------------------------------
    private int rd(int processor, long address) {
        byte[] value = io.read(processor, address, 1);
        return value == null ? -1 : value[0] & 0xFF;
    }

    private boolean wr(int processor, long address, int value) {
        return io.write(processor, address, new byte[]{(byte) (value & 0xFF)});
    }

    private static byte[] unhex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    // -- script interpreter -------------------------------------------------
    private boolean execOp(String op) {
        char kind = op.charAt(0);
        int processor = op.charAt(1) - '0';
        String[] parts = op.length() > 3 ? op.substring(3).split(":", -1) : new String[]{""};
        switch (kind) {
            case 'I':
                return !flag || io.write(processor, Long.parseLong(parts[0], 16), unhex(parts[1]));
            case 'M': {
                long address = Long.parseLong(parts[0], 16);
                int value = rd(processor, address);
                if (value < 0) {
                    return false;
                }
                int mask = Integer.parseInt(parts[1], 16);
                int set = Integer.parseInt(parts[2], 16);
                return wr(processor, address, (mask & value) | set);
            }
            case 'P': {
                long address = Long.parseLong(parts[0], 16);
                for (int attempt = 0; attempt < 150; attempt++) {
                    int value = rd(processor, address);
                    if (value < 0) {
                        return false;
                    }
                    if (value == 0) {
                        return true;
                    }
                    io.sleep(2);
                }
                return true;
            }
            case 'Q': {
                int value = rd(processor, Long.parseLong(parts[0], 16));
                if (value < 0) {
                    return false;
                }
                flag = value != 0;
                return true;
            }
            case 'V':
                return io.firmwareVersion(processor) != null;
            case 'W':
                return io.write(processor, Long.parseLong(parts[0], 16), unhex(parts[1]));
            default:
                throw new IllegalStateException("bad op " + op);
        }
    }

    private boolean run(String[] ops) {
        for (String op : ops) {
            if (!execOp(op)) {
                return false;
            }
        }
        return true;
    }

    private int clockIndex() {
        return clock == 0 ? 0 : 1;
    }

    // -- init ---------------------------------------------------------------
    public boolean init() {
        initDone = false;
        int d800 = rd(LINK, 0xD800);
        if (d800 < 0) {
            return false;
        }
        clock = d800 & 15;
        if (!run(clockIndex() == 0 ? Tables.INIT_A0 : Tables.INIT_A1)) {
            return false;
        }
        if (!sambaInit()) {
            return false;
        }
        if (!run(clockIndex() == 0 ? Tables.INIT_B0 : Tables.INIT_B1)) {
            return false;
        }
        if (!run(new String[]{"M8:f993:fe:00", "M8:f992:fe:01", "W8:1e7:00"})) {
            return false;
        }
        initDone = true;
        return true;
    }

    private boolean sambaInit() {
        int type = rd(OFDM, 0xEC86);
        int ed03Value = rd(OFDM, 0xED03);
        byte[] pair = io.read(OFDM, 0xED23, 2);
        if (type < 0 || ed03Value < 0 || pair == null) {
            return false;
        }
        tunerType = type;
        ed03 = ed03Value;
        int value = (pair[0] & 0xFF) | ((pair[1] & 0xFF) << 8);
        int ed81;
        if (tunerType == 0) {
            mulP = 2000;
            mulQ = 3;
            ed81 = 16;
        } else if (tunerType == 1) {
            mulP = 20480;
            mulQ = 18;
            ed81 = 6;
        } else {
            mulP = 640;
            mulQ = 1;
            ed81 = 6;
        }

        int tries = 1;
        while (value == 0 && tries <= 49) {
            io.sleep(2);
            pair = io.read(OFDM, 0xED23, 2);
            if (pair == null) {
                return false;
            }
            value = (pair[0] & 0xFF) | ((pair[1] & 0xFF) << 8);
            tries++;
        }
        if (value == 0) {
            return false;
        }

        int nv = getNv(ed03);
        long x5 = (long) value * mulP;
        long x8 = x5 / mulQ;
        long x7 = BigInteger.valueOf(x8).multiply(MAGIC).shiftRight(64).longValue();
        thr[5] = (x8 >> 3) & 0xFFFFFFFFL;
        thr[3] = (x8 >> 4) & 0xFFFFFFFFL;
        thr[7] = (x5 / ((long) mulQ * nv)) & 0xFFFFFFFFL;
        thr[1] = (x8 >> 5) & 0xFFFFFFFFL;
        thr[6] = (x7 >> 2) & 0xFFFFFFFFL;
        thr[4] = (x7 >> 3) & 0xFFFFFFFFL;
        thr[2] = (x7 >> 4) & 0xFFFFFFFFL;

        rd(LINK, 0x1222);
        if (io.read(LINK, 0x1223, 2) == null) {
            return false;
        }
        io.sleep(50);
        if (!wr(OFDM, 0xED81, ed81)) {
            return false;
        }
        int r1dc = rd(OFDM, 0x1DC);
        if (r1dc < 0) {
            return false;
        }
        if (r1dc == 1) {
            return run(new String[]{"M0:d8d0:fe:01", "M0:d8d1:fe:01"});
        }
        return true;
    }

    // -- tuning maths -------------------------------------------------------
    private int getNc(long freq) {
        if (thr[1] >= freq) {
            return 0;
        }
        for (int index = 2; index <= 8; index++) {
            if (freq <= thr[index]) {
                return index - 1;
            }
        }
        return 8;
    }

    private long[] freqCode(long freq) {
        int nc = getNc(freq);
        int nv = getNv(nc);
        long product = (long) mulQ * freq * nv;
        long quotient = product / mulP;
        long remainder = product % mulP;
        if (remainder >= (mulP >> 1)) {
            quotient++;
        }
        return new long[]{nc, nv, quotient & 0xFFFFFFFFL};
    }

    private long loFreq(long freq) {
        long[] code = freqCode(freq);
        return (((code[0] & 7) << 13) + code[2]) & 0xFFFFFFFFL;
    }

    private long calFreqIqik(long freq, int p) {
        long[] code = freqCode(freq);
        long nc = code[0];
        int nv = (int) code[1];
        long fcw = code[2];
        long x;
        if (tunerType == 0) {
            if (p <= 31) {
                x = ((9L * nv * p) >> 5) + fcw;
            } else {
                x = fcw - ((9L * nv * ((64 - p) & 255)) >> 5);
            }
        } else if (p <= 31 && tunerType == 1) {
            x = ((long) p * nv >> 1) + fcw;
        } else {
            x = fcw - ((((64 - p) & 255L) * nv) >> 1);
        }
        return (((7 & nc) << 13) + x) & 0xFFFFFFFFL;
    }

    // -- tune ---------------------------------------------------------------
    public boolean tune(int frequencyKhz) {
        if (!initDone) {
            return false;
        }
        long freq = frequencyKhz & 0xFFFFFFFFL;
        if (!run(clockIndex() == 0 ? Tables.TUNE_PRE0 : Tables.TUNE_PRE1)) {
            return false;
        }
        int v4b;
        if (freq <= 29999) {
            v4b = 255;
        } else if (freq <= 300000) {
            v4b = 0;
        } else if (freq <= 1000000) {
            v4b = 1;
        } else {
            v4b = (freq - 1670000) > 10000 ? 255 : 2;
        }
        if (!wr(OFDM, 0x4B, v4b)) {
            return false;
        }

        int r1c6 = rd(OFDM, 0x1C6);
        int r1dc = rd(OFDM, 0x1DC);
        if (r1c6 < 0 || r1dc < 0) {
            return false;
        }
        if (r1c6 != 0) {
            int[] row = bandRow(r1dc == 1 ? Tables.BANDS1 : Tables.BANDS0, freq);
            int[][] writes = {
                    {0xFB25, row[1]}, {0xFBB5, row[2]}, {0xFBB6, row[3]},
                    {0xFBB7, row[4]}, {0xFBB9, row[5]}, {0xFBB9, row[6]},
                    {0xFBBA, row[7]},
            };
            for (int[] write : writes) {
                if (!wr(OFDM, write[0], write[1])) {
                    return false;
                }
            }
        }
        if (!sambaSetFreq(freq, BW_KHZ)) {
            return false;
        }
        if (!wr(OFDM, 0x00, 0)) {
            return false;
        }
        lastFreqKhz = frequencyKhz;
        return true;
    }

    private static int[] bandRow(int[][] table, long freq) {
        int[] row = table[0];
        for (int[] candidate : table) {
            if (candidate[0] > freq) {
                break;
            }
            row = candidate;
        }
        return row;
    }

    private boolean sambaSetFreq(long freq, int bandwidthKhz) {
        int ec4c = rd(OFDM, 0xEC4C);
        int ed81 = rd(OFDM, 0xED81);
        if (ec4c < 0 || ed81 < 0) {
            return false;
        }
        int lna = lnaCapSel(freq);
        int lpf = lpfBw(bandwidthKhz);
        int b5a = (ec4c & 231) | (freq > 311999 ? 8 : 0);
        long iq = calFreqIqik(freq, ed81);
        long lo = loFreq(freq);
        int[][] writes = {
                {0x160, lna}, {0xEC56, lpf}, {0xEC4C, b5a},
                {0xEC4D, (int) (iq & 0xFF)}, {0xEC4E, (int) ((iq >> 8) & 0xFF)},
                {0x15E, (int) (lo & 0xFF)}, {0x15F, (int) ((lo >> 8) & 0xFF)},
        };
        for (int[] write : writes) {
            if (!wr(OFDM, write[0], write[1])) {
                return false;
            }
        }
        int r1dc = rd(OFDM, 0x1DC);
        if (r1dc < 0) {
            return false;
        }
        if (r1dc == 1) {
            int r = rd(LINK, 0xD8CF);
            if (r < 0) {
                return false;
            }
            if (!wr(LINK, 0xD8CF, (r & 254) | (freq > 176142 ? 1 : 0))) {
                return false;
            }
        }
        long half = freq >> 1;
        long hl = loFreq(half);
        return wr(OFDM, 0x1E3, lnaCapSel(half))
                && wr(OFDM, 0x1E1, (int) (hl & 0xFF))
                && wr(OFDM, 0x1E2, (int) ((hl >> 8) & 0xFF));
    }

    // -- lock and status ----------------------------------------------------
    public boolean mpeg2Locked() {
        int a = rd(OFDM, 0xF9F1);
        int b = rd(OFDM, 0xF9F2);
        int c = rd(OFDM, 0xF9F3);
        return a >= 0 && b >= 0 && c >= 0 && ((a | b | c) & 1) != 0;
    }

    public boolean waitLock(boolean quick) {
        int attempts = quick ? 1 : 2;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int poll = quick ? 60 : 40;
            int state = 0;
            for (int index = 0; index < poll; index++) {
                state = rd(OFDM, 0x47);
                if (state == 1 || state == 2 || state < 0) {
                    break;
                }
                io.sleep(25);
            }
            if (state == 1) {
                boolean tps = false;
                for (int index = 0; index < 50; index++) {
                    int value = rd(OFDM, 0xF5A9);
                    tps = value >= 0 && (value & 1) != 0;
                    if (tps) {
                        break;
                    }
                    io.sleep(25);
                }
                if (tps) {
                    for (int index = 0; index < 40; index++) {
                        if (mpeg2Locked()) {
                            return true;
                        }
                        io.sleep(25);
                    }
                }
            }
            if (state == 2 && attempt == 1) {
                break;
            }
            wr(OFDM, 0x47, 0);
            wr(LINK, 0x00, 0);
        }
        return false;
    }

    public Status status() {
        int tps = rd(OFDM, 0xF5A9);
        boolean locked = tps >= 0 && (tps & 1) != 0 && mpeg2Locked();
        Status status = new Status();
        status.locked = locked;
        int strength = rd(OFDM, 0x13E);
        if (strength >= 0) {
            status.rawStrength = strength;
            status.strengthPercent = Math.min(strength, 100);
        }
        int quality = rd(OFDM, 0x49);
        if (quality >= 0) {
            status.rawQuality = quality;
            status.qualityPercent = Math.min(quality, 100);
        }
        int dbm = rd(OFDM, 0x13F);
        if (dbm >= 0) {
            status.dbm = dbm - 100;
        }
        int snr = rd(OFDM, 0x1C9);
        if (snr >= 0) {
            status.rawSnr = snr;
            status.snrDb = snr;
        }
        byte[] ber = io.read(OFDM, 0x32, 7);
        if (ber != null) {
            int window = (ber[5] & 0xFF) | ((ber[6] & 0xFF) << 8);
            int errors = (ber[2] & 0xFF) | ((ber[3] & 0xFF) << 8) | ((ber[4] & 0xFF) << 16);
            status.berErrors = errors;
            status.berWindow = window;
            if (window != 0) {
                status.ber = errors / (window * 204.0 * 8.0);
            }
        }
        return status;
    }

    public static int getNv(int index) {
        return index < 0 || index > 8 ? 2 : NV[index];
    }

    public static int lnaCapSel(long freq) {
        if (freq <= 440000) {
            return 0;
        }
        if (freq <= 484000) {
            return 1;
        }
        if (freq <= 533000) {
            return 2;
        }
        if (freq <= 587000) {
            return 3;
        }
        if (freq <= 645000) {
            return 4;
        }
        if (freq <= 710000) {
            return 5;
        }
        return ((freq - 710001) & 0xFFFFFFFFL) <= 71999 ? 6 : 7;
    }

    public static int lpfBw(int bandwidthKhz) {
        switch (bandwidthKhz) {
            case 5000: return 0;
            case 5500: return 1;
            case 6000: return 2;
            case 6500: return 3;
            case 7000: return 4;
            case 7500: return 5;
            case 8000: return 6;
            case 8500: return 7;
            default: return 6;
        }
    }

    public int lastFrequencyKhz() {
        return lastFreqKhz;
    }
}
