package com.pbcnx.opendtvmod.diag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the identifying parts of a diagnostic line before it is shown, copied or written to a
 * file: IP addresses, MAC addresses, e-mail addresses, phone-like numbers, the user's home folder
 * and the app's own absolute storage paths.
 *
 * <p>Pure Java (no Android types), so the JVM tests can prove each pattern without a device.</p>
 *
 * <p>The two replacement tokens are deliberately distinct: {@link #REDACTED} for a person-shaped
 * value, {@link #PATH} for a location. A reader of a bundle can therefore still tell "the log knew
 * an address here" from "the log knew a folder here" without learning what either was.</p>
 */
public final class Redactor {

    /** What a person-shaped value (address, number, identifier) becomes. */
    public static final String REDACTED = "<redacted>";
    /** What a filesystem location becomes. */
    public static final String PATH = "<path>";

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** {@code C:\Users\Bob} and the pre-Vista {@code C:\Documents and Settings\Bob}. */
    private static final Pattern WINDOWS_HOME = Pattern.compile(
            "(?i)\\b[A-Za-z]:[\\\\/](?:Users|Documents and Settings)[\\\\/][^\\\\/\\s\"';,]+");

    /** {@code /home/bob} and macOS {@code /Users/bob}. */
    private static final Pattern UNIX_HOME = Pattern.compile(
            "(?<![A-Za-z0-9_.\\-])/(?:home|Users)/[^/\\s\"';,]+");

    /** The one account with no name in the path, so there is nothing to keep. */
    private static final Pattern ROOT_HOME = Pattern.compile(
            "(?<![A-Za-z0-9_.\\-])/root(?=[/\\s\"';,]|$)");

    /**
     * The app's shared/external files directory: {@code .../Android/data/com.pbcnx.opendtvmod...}
     * (the debug build's id carries a {@code .ph} suffix). Matched anywhere a path token starts, so
     * a different volume or a Windows-side pull path still loses the package and everything after it.
     */
    private static final Pattern APP_EXTERNAL = Pattern.compile(
            "(?:[A-Za-z]:[\\\\/])?[^\\s\"';,]*Android[\\\\/](?:data|media)[\\\\/]"
                    + "com\\.pbcnx\\.opendtvmod(?:\\.[A-Za-z0-9]+)?[^\\s\"';,]*");

    /** The app's internal data dir: {@code /data/data/...} or {@code /data/user/0/...}. */
    private static final Pattern APP_INTERNAL = Pattern.compile(
            "/data/(?:data|user/\\d+)/com\\.pbcnx\\.opendtvmod(?:\\.[A-Za-z0-9]+)?[^\\s\"';,]*");

    /** The app's user-visible {@code OpenDTV} folder on shared storage, e.g. {@code /sdcard/OpenDTV}. */
    private static final Pattern APP_FOLDER = Pattern.compile(
            "(?:/storage/[^/\\s\"';,]+|/sdcard\\d*|/mnt/sdcard)/OpenDTV[^\\s\"';,]*");

    /** Six pairs of hex digits, colon- or dash-separated. */
    private static final Pattern MAC = Pattern.compile(
            "(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])");

    /** Four dot-separated octets, each 0..255. */
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9A-Fa-f.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
                    + "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?![0-9A-Fa-f.])");

    /**
     * A candidate IPv6 token. Every candidate is validated by {@link #isIpv6(String)}: a bare
     * {@code 21:33:10} clock stamp must stay readable, so neither two colons without {@code ::}
     * nor fewer than eight groups qualify.
     */
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:])");

    /**
     * A candidate phone number: at least two digit groups separated by a space, dot, dash,
     * parenthesis or the leading {@code +}. Validated by {@link #isPhoneLike(String)} so builds,
     * dates and versions keep their numbers.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\d+])\\+?\\d{1,5}(?:[\\s.\\-()]\\d{1,5}){1,4}(?!\\d)");

    /** A bare PH-style mobile number: 10 or 11 digits starting with 0 (0900…, 0917…). */
    private static final Pattern PHONE_BARE = Pattern.compile("(?<![\\d])0\\d{9,10}(?![\\d])");

    /** {@code 2026-11-07} / {@code 2026/11/07}: a date, not a phone number. */
    private static final Pattern DATE_LIKE = Pattern.compile("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}");

    private Redactor() {
    }

    /** Redacts a line with the built-in patterns only. Null becomes "". */
    public static String redact(String text) {
        return redact(text, null);
    }

    /**
     * Redacts a line, also replacing every literal absolute path in {@code appAbsolutePaths} with
     * {@link #PATH}. The caller passes the paths this process actually uses (its external files
     * dir, its internal files dir), because those are absolute and device-specific in a way a
     * pattern cannot know.
     */
    public static String redact(String text, Collection<String> appAbsolutePaths) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = text;
        if (appAbsolutePaths != null && !appAbsolutePaths.isEmpty()) {
            // Longest first: a nested path must not be half-replaced by its parent prefix.
            List<String> paths = new ArrayList<>();
            for (String p : appAbsolutePaths) if (p != null && !p.trim().isEmpty()) paths.add(p.trim());
            paths.sort(Comparator.comparingInt(String::length).reversed());
            for (String p : paths) out = out.replace(p, PATH);
        }
        out = replaceAll(out, EMAIL, REDACTED);
        out = replaceAll(out, WINDOWS_HOME, PATH);
        out = replaceAll(out, UNIX_HOME, PATH);
        out = replaceAll(out, ROOT_HOME, PATH);
        out = replaceAll(out, APP_EXTERNAL, PATH);
        out = replaceAll(out, APP_INTERNAL, PATH);
        out = replaceAll(out, APP_FOLDER, PATH);
        out = replaceAll(out, MAC, REDACTED);
        out = replaceAll(out, IPV4, REDACTED);
        out = replaceValidated(out, IPV6, Redactor::isIpv6, REDACTED);
        out = replaceValidated(out, PHONE, Redactor::isPhoneLike, REDACTED);
        out = replaceValidated(out, PHONE_BARE, s -> true, REDACTED);
        return out;
    }

    // ------------------------------------------------------------------ validation

    /**
     * True for a real IPv6 token: it must either contain {@code ::} or spell out all eight groups.
     * The clock stamp {@code 21:33:10} has neither and stays.
     */
    static boolean isIpv6(String s) {
        if (s == null || s.indexOf(':') < 0) return false;
        int doubleColon = s.indexOf("::");
        if (doubleColon >= 0 && s.indexOf("::", doubleColon + 2) >= 0) return false;
        String[] parts = s.split(":", -1);
        int nonEmpty = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!p.matches("[0-9A-Fa-f]{1,4}")) return false;
            nonEmpty++;
        }
        int colons = s.length() - s.replace(":", "").length();
        if (doubleColon >= 0) {
            // "::" stands for one or more zero groups, so the address cannot be only "::".
            return nonEmpty >= 1 && nonEmpty <= 7 && colons >= 2;
        }
        return nonEmpty == 8;
    }

    /**
     * True for a phone-like number: 7..15 digits, at least one separating character (the pattern
     * guarantees it) and not a date. Frequencies are six digits and version strings fewer still,
     * so neither can reach the lower bound.
     */
    static boolean isPhoneLike(String s) {
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) digits++;
        }
        if (digits < 7 || digits > 15) return false;
        return !DATE_LIKE.matcher(s).find();
    }

    // ------------------------------------------------------------------ mechanics

    private interface Validator {
        boolean valid(String candidate);
    }

    private static String replaceAll(String text, Pattern pattern, String token) {
        return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(token));
    }

    private static String replaceValidated(String text, Pattern pattern, Validator validator, String token) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        boolean replaced = false;
        while (m.find()) {
            if (!validator.valid(m.group())) continue;
            out.append(text, last, m.start()).append(token);
            last = m.end();
            replaced = true;
        }
        if (!replaced) return text;
        out.append(text, last, text.length());
        return out.toString();
    }
}
