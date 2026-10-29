package ph.opendtv.ts;

/**
 * Decoder for ARIB STD-B24 closed captions (data_component_id 0x0008), as broadcast by Philippine ISDB-T stations.
 * Input is the payload of one caption PES (after the PES header); output is the plain text of the statement.
 *
 * Supported: management/statement data groups, 8-bit text with Alphanumeric/ASCII in GL (what PH stations send,
 * even without an explicit ESC designation), line breaks (APR/APS row change), clear screen. Colors, sizes,
 * positions and Japanese kanji/kana (2-byte sets) are skipped - the stations here broadcast English.
 */
public final class AribCaption {
    private AribCaption() {}

    /**
     * Text coding declared by the management group's format byte: 0 = ARIB 8-bit (what the stations
     * send), 1 = UCS/UTF-8, which is what an EWBS superimposed caption declares.
     */
    private static int tcs;

    /** Result of decoding one PES. */
    public static final class Result {
        /** true: statement data group (text may be empty = "clear caption"); false: management / unknown. */
        public final boolean statement;
        public final String text;
        /** ISO 639-2 language from the management group, or null. */
        public final String language;

        Result(boolean statement, String text, String language) {
            this.statement = statement;
            this.text = text;
            this.language = language;
        }
    }

    /** @param pes complete PES packet incl. 00 00 01 header */
    public static Result decodePes(byte[] pes, int len) {
        return decodePes(pes, len, 1);
    }

    /** @param language which caption language to decode: 1 = first (main), 2 = second */
    public static Result decodePes(byte[] pes, int len, int language) {
        if (len < 9 || pes[0] != 0 || pes[1] != 0 || pes[2] != 1) return null;
        // private_stream_2 (0xBF, what an EWBS superimposed caption uses) has no optional header:
        // the payload starts right after the 6-byte PES header.
        int p = (pes[3] & 0xFF) == 0xBF ? 6 : 9 + (pes[8] & 0xFF);
        return decodePayload(pes, p, len, language);
    }

    /** @param b data_identifier (0x80 caption, 0x81 superimpose) ... */
    static Result decodePayload(byte[] b, int p, int end) { return decodePayload(b, p, end, 1); }

    static Result decodePayload(byte[] b, int p, int end, int language) {
        if (end - p < 3) return null;
        int id = b[p] & 0xFF;
        if ((id != 0x80 && id != 0x81) || (b[p + 1] & 0xFF) != 0xFF) return null;
        p += 3 + (b[p + 2] & 0x0F);                      // data_identifier, private_stream_id, header
        if (end - p < 5) return null;
        int groupId = (b[p] & 0xFF) >> 2;
        int size = ((b[p + 3] & 0xFF) << 8) | (b[p + 4] & 0xFF);
        int d = p + 5;
        int dEnd = Math.min(d + size, end);
        int g = groupId & 0x1F;                           // A (0x00..) and B (0x20..) groups share numbering
        if (g == 0) return management(b, d, dEnd);
        if (g != language) return new Result(false, "", null);   // statement of the other language
        return statement(b, d, dEnd);
    }

    private static Result management(byte[] b, int p, int end) {
        if (end - p < 2) return new Result(false, "", null);
        int tmd = (b[p] & 0xFF) >> 6;
        p++;
        if (tmd == 2) p += 5;                             // offset time
        if (end - p < 1) return new Result(false, "", null);
        int numLang = b[p++] & 0xFF;
        String lang = null;
        if (numLang > 0 && end - p >= 4) {
            lang = new String(new char[] {(char) (b[p + 1] & 0x7F), (char) (b[p + 2] & 0x7F), (char) (b[p + 3] & 0x7F)});
        }
        if (numLang > 0 && end - p >= 5) {
            tcs = (b[p + 4] & 0x0C) >> 2;                 // 1 = UCS / UTF-8
        }
        return new Result(false, "", lang);
    }

    private static Result statement(byte[] b, int p, int end) {
        if (end - p < 4) return new Result(true, "", null);
        int tmd = (b[p] & 0xFF) >> 6;
        p++;
        if (tmd == 1 || tmd == 2) p += 5;                 // STM (real-time) or offset: 36/40 bits
        int loop = ((b[p] & 0xFF) << 16) | ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
        p += 3;
        int loopEnd = Math.min(p + loop, end);
        StringBuilder sb = new StringBuilder();
        while (loopEnd - p >= 5) {
            if ((b[p] & 0xFF) != 0x1F) break;
            int param = b[p + 1] & 0xFF;
            int n = ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 8) | (b[p + 4] & 0xFF);
            p += 5;
            int e = Math.min(p + n, loopEnd);
            if (param == 0x20) {                          // statement body
                // An EWBS caption declares TCS=1 and its control prefix is UTF-8 encoded (C2 9B ...),
                // so the lead byte is enough to tell it apart even if the management group came late.
                boolean utf8 = tcs == 1 || hasUtf8Lead(b, p, e);
                if (utf8) textUtf8(b, p, e, sb);
                else text(b, p, e, sb);
            }
            p = e;
        }
        return new Result(true, tidy(sb), null);
    }

    /**
     * UCS/UTF-8 statement body. An EWBS superimposed caption declares TCS=1 and sends UTF-8 text
     * behind a run of C1 control codes, so the controls are dropped and the newline ends the line.
     */
    /** True when the body carries a UTF-8 lead byte (C2..F4): the EWBS caption's control prefix does. */
    private static boolean hasUtf8Lead(byte[] b, int p, int end) {
        for (int i = p; i < end; i++) {
            int c = b[i] & 0xFF;
            if (c >= 0xC2 && c <= 0xF4) return true;
        }
        return false;
    }

    private static void textUtf8(byte[] b, int p, int end, StringBuilder sb) {
        String s = new String(b, p, Math.max(0, end - p), java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') { nl(sb); continue; }
            if (ch >= 0x80 && ch <= 0x9F) {
                // A C1 control (CSI 0x9B and friends) arrives as its own character, followed by
                // printable parameter bytes and a final byte in 0x40..0x7F. Consume the whole
                // sequence, or the parameters read as text ("S620;480 V30;30").
                i++;
                while (i < s.length()) {
                    char f = s.charAt(i);
                    if (f >= 0x40 && f <= 0x7F) break;
                    i++;
                }
                continue;
            }
            if (ch < 0x20 || ch == 0x7F) continue;
            sb.append(ch);
        }
    }

    /** Collapses blank lines and trims the padding spaces stations use for centering. */
    private static String tidy(StringBuilder sb) {
        String[] lines = sb.toString().split("\n");
        StringBuilder out = new StringBuilder();
        for (String l : lines) {
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < l.length(); i++) {
                char ch = l.charAt(i);
                if (ch == '　' || ch == ' ') ch = ' ';
                if (ch < 0x20 || ch == 0x7F || ch == '□' || ch == '�') continue;   // NUL / control / tofu boxes
                clean.append(ch);
            }
            String t = clean.toString().trim();
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
        }
        return out.toString();
    }

    private static void text(byte[] b, int p, int end, StringBuilder sb) {
        int row = -1;
        int[] g = {1, 1, 0, 0};                           // 0 = 2-byte/other, 1 = alphanumeric (ASCII)
        int gl = 0;
        int ss = -1;                                      // single shift target (G2/G3), applies to next char
        while (p < end) {
            int c = b[p++] & 0xFF;
            if (c <= 0x20 || c == 0x7F || (c >= 0x80 && c <= 0xA0)) {
                switch (c) {
                    case 0x0C: sb.setLength(0); row = -1; break;                  // CS: clear screen
                    case 0x0D: nl(sb); break;                                       // APR
                    case 0x0A: nl(sb); break;                                       // APD
                    case 0x0E: gl = 1; break;                                       // LS1 -> G1
                    case 0x0F: gl = 0; break;                                       // LS0 -> G0
                    case 0x19: ss = 2; break;                                       // SS2
                    case 0x1D: ss = 3; break;                                       // SS3
                    case 0x16: p++; break;                                          // PAPF
                    case 0x1C: {                                                    // APS row, col
                        if (end - p >= 2) {
                            int r = (b[p] & 0x3F);
                            if (row >= 0 && r > row) nl(sb);
                            row = r;
                            p += 2;
                        }
                        break;
                    }
                    case 0x1B: p = esc(b, p, end, g); break;                        // ESC designation
                    case 0x20: sb.append(' '); break;
                    case 0x8B: p++; break;                                          // SZX
                    case 0x90: case 0x92: {                                         // COL / CDC (1 or 2 params)
                        if (p < end && (b[p] & 0xFF) == 0x20) p += 2; else p++;
                        break;
                    }
                    case 0x91: case 0x93: case 0x94: case 0x97: case 0x98: p++; break;  // FLC POL WMM HLC RPC
                    case 0x95: {                                                    // MACRO ... until 0x95 0x4F
                        while (p + 1 < end && !((b[p] & 0xFF) == 0x95 && (b[p + 1] & 0xFF) == 0x4F)) p++;
                        p += 2;
                        break;
                    }
                    case 0x9B: {                                                    // CSI ... final byte 0x40-0x7F
                        while (p < end) {
                            int f = b[p++] & 0xFF;
                            if (f >= 0x40 && f <= 0x7F) break;
                        }
                        break;
                    }
                    case 0x9D: {                                                    // TIME
                        if (p < end && (b[p] & 0xFF) == 0x20) p += 2; else p += 3;
                        break;
                    }
                    default: break;                                                 // other C0/C1: no parameters
                }
                continue;
            }
            int set = ss >= 0 ? g[ss] : (c >= 0xA1 ? 1 : g[gl]);
            ss = -1;
            if (set == 0) {                                                         // 2-byte set (kanji/kana): not rendered
                int c2 = p < end ? b[p++] & 0x7F : 0;
                if ((c & 0x7F) == 0x21 && c2 == 0x21) sb.append(' ');             // ideographic space = "blank caption"
                continue;                                                           // anything else is skipped, never a box
            }
            int ch = c & 0x7F;
            if (ch >= 0x21 && ch <= 0x7E) sb.append((char) ch);
        }
    }

    private static void nl(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
    }

    /** Handles ESC sequences: G-set designations. Returns the new read position. */
    private static int esc(byte[] b, int p, int end, int[] g) {
        if (p >= end) return p;
        int c = b[p++] & 0xFF;
        if (c == 0x28 || c == 0x29 || c == 0x2A || c == 0x2B) {            // 1-byte set into G0..G3
            if (p >= end) return p;
            int f = b[p++] & 0xFF;
            g[c - 0x28] = (f == 0x4A || f == 0x40 || f == 0x42) ? 1 : 1;    // alphanumeric/ASCII variants
            return p;
        }
        if (c == 0x24) {                                                    // 2-byte set
            if (p >= end) return p;
            int f = b[p++] & 0xFF;
            int idx = 0;
            if (f >= 0x28 && f <= 0x2B) {                                   // ESC 24 28 ... DRCS / 2-byte with intermediate
                idx = f - 0x28;
                if (p < end) p++;                                           // final byte
                g[idx] = 0;
            } else {
                g[0] = 0;                                                   // ESC 24 F -> G0
            }
            return p;
        }
        return p;
    }
}
