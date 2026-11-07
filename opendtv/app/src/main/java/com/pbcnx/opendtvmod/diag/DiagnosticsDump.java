package com.pbcnx.opendtvmod.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds the one text file a user can send with a bug report. Pure Java: the platform values are
 * gathered by {@link DiagnosticsCollector} into an {@link Input}, and this class only lays them
 * out, so the whole bundle's content and its opt-in gates are testable on the JVM.
 *
 * <p>Two rules shape the output:</p>
 * <ul>
 *   <li><b>Nothing is uploaded.</b> The file is written on the device; there is no network call
 *       anywhere on this path.</li>
 *   <li><b>Redaction is the default.</b> The finished text goes through {@link Redactor} once, so
 *       a line that reached the ring before a pattern was known still cannot carry an address, a
 *       number or a path out. Device model and channel/frequency data are gated twice: the
 *       collector only fills them in when the user opted in, and {@link #build} refuses to print
 *       them unless the same flag is set.</li>
 * </ul>
 */
public final class DiagnosticsDump {

    /**
     * The sentence every bundle carries. The tests assert it by exact value, so the promise the
     * screen makes and the promise the file makes cannot drift apart.
     */
    public static final String EXCLUDED_STATEMENT =
            "Deliberately excluded: transport-stream and recording contents, the clipboard, account "
                    + "identifiers, saved channel names and the tuned frequency (unless you opted in), "
                    + "and the device model/manufacturer (unless you opted in). Every IP address, MAC "
                    + "address, e-mail address, phone number and absolute path below has been replaced.";

    /** The line above {@link #EXCLUDED_STATEMENT}: the file is local and manual. */
    public static final String NO_UPLOAD_STATEMENT =
            "Nothing in this file is uploaded automatically. It was written on this device, and only "
                    + "you decide who receives it.";

    /** A centre frequency in a log line, e.g. "LOCKED on 557000 kHz"; hidden unless opted in. */
    private static final Pattern RING_FREQUENCY = Pattern.compile("(?<!\\d)\\d{5,6}(?=\\s*kHz)");

    private DiagnosticsDump() {
    }

    /**
     * Everything the bundle can contain. Defaults are the privacy-safe ones: empty strings, no
     * opt-ins. The collector fills it on a device; the tests fill it directly.
     */
    public static final class Input {
        /** "2026-11-07 21:45:00"; the collector stamps it, the tests set it. */
        public String generatedAt = "";
        /** The build's versionName, e.g. "0.2.0". */
        public String buildStamp = "";
        /** The build's versionCode; a negative value omits it. */
        public long versionCode = -1;
        /** The short commit, or "" when the build carries none. */
        public String commit = "";
        /** "Android 14". */
        public String androidRelease = "";
        /** The SDK level, e.g. 34; negative omits it. */
        public int sdkInt = -1;
        /** The primary ABI, e.g. "arm64-v8a". */
        public String abi = "";

        /** Opt-in gate and the values it governs. */
        public boolean includeDeviceModel;
        public String deviceManufacturer = "";
        public String deviceModel = "";

        /** Opt-in gate and the values it governs. */
        public boolean includeChannelData;
        /** Centre frequency of the current tune in kHz, 0 when not tuned. */
        public int frequencyKhz;

        public String tunerName = "";
        public String tunerUsbId = "";
        public String tunerFailure = "";

        /** One line of signal words/numbers, already free of the tuned frequency. */
        public String signalSnapshot = "";

        public boolean decoderManual;
        public boolean decoderVideoSoftware;
        public boolean decoderAudioSoftware;
        /** The Auto decoder memory: "557000:1,605000:0" (frequency:1 = software won). */
        public String learnedMuxMap = "";

        /** One line per saved channel, with its name and frequency; only printed when opted in. */
        public final List<String> channelLines = new ArrayList<>();

        /** The redacted ring snapshot ({@link DiagnosticsRing#snapshot()}). */
        public String ring = "";

        /** Absolute paths this process writes to; replaced with {@code <path>} wherever they appear. */
        public final List<String> appPaths = new ArrayList<>();
    }

    /** The bundle text for {@code input}, redacted. Never null. */
    public static String build(Input input) {
        Input in = input == null ? new Input() : input;
        StringBuilder b = new StringBuilder(4096);

        b.append("OpenDTV-MOD diagnostics bundle\n");
        b.append("=============================\n");
        if (notEmpty(in.generatedAt)) b.append("Generated: ").append(in.generatedAt.trim()).append('\n');
        b.append('\n').append(NO_UPLOAD_STATEMENT).append('\n');
        b.append(EXCLUDED_STATEMENT).append('\n');

        section(b, "BUILD");
        key(b, "version", notEmpty(in.buildStamp) ? in.buildStamp.trim() : "?");
        if (in.versionCode >= 0) key(b, "version code", String.valueOf(in.versionCode));
        key(b, "commit", notEmpty(in.commit) ? in.commit.trim() : "not embedded in this build");

        section(b, "PLATFORM");
        key(b, "android", notEmpty(in.androidRelease)
                ? in.androidRelease.trim() + (in.sdkInt >= 0 ? " (SDK " + in.sdkInt + ")" : "")
                : (in.sdkInt >= 0 ? "SDK " + in.sdkInt : "?"));
        key(b, "abi", notEmpty(in.abi) ? in.abi.trim() : "?");

        section(b, "DEVICE");
        if (in.includeDeviceModel && notEmpty(in.deviceModel)) {
            key(b, "manufacturer", notEmpty(in.deviceManufacturer) ? in.deviceManufacturer.trim() : "?");
            key(b, "model", in.deviceModel.trim());
        } else {
            key(b, "model", "withheld (opt-in)");
        }

        section(b, "TUNER");
        key(b, "source", notEmpty(in.tunerName) ? in.tunerName.trim() : "no source open");
        key(b, "usb id", notEmpty(in.tunerUsbId) ? in.tunerUsbId.trim() : "not found");
        key(b, "failure", notEmpty(in.tunerFailure) ? in.tunerFailure.trim() : "none");

        section(b, "SIGNAL");
        key(b, "state", notEmpty(in.signalSnapshot)
                ? in.signalSnapshot.trim()
                : "no signal (no tuner open, or a file source)");
        if (in.includeChannelData) {
            key(b, "frequency", in.frequencyKhz > 0 ? in.frequencyKhz + " kHz" : "not tuned");
        } else {
            key(b, "frequency", "withheld (opt-in)");
        }

        section(b, "DECODER");
        key(b, "manual", in.decoderManual ? "on" : "off (auto)");
        if (in.decoderManual) {
            key(b, "video", in.decoderVideoSoftware ? "software" : "hardware");
            key(b, "audio", in.decoderAudioSoftware ? "software" : "hardware");
        } else {
            key(b, "start", "hardware first, software when no frame or on a decoder error");
        }
        key(b, "learned muxes", learnedMuxes(in));

        section(b, "CHANNELS");
        if (!in.includeChannelData) {
            b.append("  withheld (opt-in): the saved channel names and frequencies are not in this file\n");
        } else if (in.channelLines.isEmpty()) {
            b.append("  (none saved)\n");
        } else {
            for (String line : in.channelLines) b.append("  ").append(line.trim()).append('\n');
        }

        section(b, "SESSION LOG (redacted)");
        String ring = in.ring == null ? "" : in.ring.trim();
        if (ring.isEmpty()) {
            b.append("  (no log lines captured)\n");
        } else {
            // Frequencies in driver status lines ("LOCKED on 557000 kHz") are channel data too, so
            // they are hidden from the log unless the same opt-in was given.
            String shown = in.includeChannelData ? ring : RING_FREQUENCY.matcher(ring).replaceAll(Redactor.REDACTED);
            for (String line : shown.split("\n")) b.append("  ").append(line.trim()).append('\n');
        }

        String text = b.toString().trim() + "\n";
        return Redactor.redact(text, in.appPaths);
    }

    /** The Auto decoder memory: frequencies only when opted in, a count summary otherwise. */
    private static String learnedMuxes(Input in) {
        String map = in.learnedMuxMap == null ? "" : in.learnedMuxMap.trim();
        if (map.isEmpty()) return "none";
        if (in.includeChannelData) return map;
        int total = 0;
        int software = 0;
        for (String entry : map.split(",")) {
            int colon = entry.indexOf(':');
            if (colon <= 0) continue;
            total++;
            if ("1".equals(entry.substring(colon + 1).trim())) software++;
        }
        if (total == 0) return "none";
        return total + " muxes (software " + software + ", hardware " + (total - software) + ")";
    }

    private static void section(StringBuilder b, String title) {
        b.append('\n').append("== ").append(title).append(" ==\n");
    }

    private static void key(StringBuilder b, String label, String value) {
        b.append("  ").append(String.format(Locale.ROOT, "%-14s", label)).append(": ").append(value).append('\n');
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
