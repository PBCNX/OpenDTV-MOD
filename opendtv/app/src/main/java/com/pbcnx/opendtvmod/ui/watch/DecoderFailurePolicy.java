package com.pbcnx.opendtvmod.ui.watch;

/**
 * Decides what the watch path does after a player error: retry the same decoder (today's
 * behaviour), switch the video track to the software decoder exactly once, or give up and show
 * the failure card.
 *
 * <p>Kept out of {@link WatchPlayback} and out of Media3 so the rule runs on the JVM in a plain
 * unit test. The case it exists for: a hardware MediaCodec answers {@code DECODING_FAILED} for an
 * H.264 High 1080p stream ({@code avc1.640028}) whose format it reported supported, on a locked,
 * clean mux. Retrying that decoder cannot help, and the old handler retried it {@code MAX_RETRIES}
 * times and then showed "No signal", blaming the reception. The software decoder handles the same
 * stream, so a decoder failure earns one software attempt; once software video has also failed
 * there is no fallback left to make.</p>
 */
public final class DecoderFailurePolicy {

    /** What the caller should do with this error. */
    public enum Action {
        /** Today's behaviour: retry the same decoder up to {@code MAX_RETRIES}, then the card. */
        RETRY,
        /** Switch the video track to the software decoder now, once. */
        FALLBACK_SOFTWARE,
        /** No fallback left: show the failure card instead of retrying the same decoder. */
        GIVE_UP
    }

    // The PlaybackException codes that mean the decoder refused this stream rather than the mux.
    // Duplicated as plain ints, with the same values as androidx.media3.common.PlaybackException
    // 1.7.1, so this class and its test need neither Android nor Media3 on the classpath.
    public static final int ERROR_CODE_DECODER_INIT_FAILED = 4001;
    public static final int ERROR_CODE_DECODER_QUERY_FAILED = 4002;
    public static final int ERROR_CODE_DECODING_FAILED = 4003;
    public static final int ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004;
    public static final int ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 4005;

    /** The viewer-facing line the Analyzer adds when the decoder failed. */
    public static final String HINT =
            "decoder failed - set Settings > Video decoder to Software";

    private DecoderFailurePolicy() {
    }

    /** True for the decoder-family codes; the transport, parsing, DRM and audio-track codes are not. */
    public static boolean isDecoderError(int errorCode) {
        return errorCode == ERROR_CODE_DECODER_INIT_FAILED
                || errorCode == ERROR_CODE_DECODER_QUERY_FAILED
                || errorCode == ERROR_CODE_DECODING_FAILED
                || errorCode == ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || errorCode == ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
    }

    /** {@code PlaybackException.getErrorCodeName()} form, for a caller that has the name only. */
    public static boolean isDecoderError(String errorCodeName) {
        return isDecoderError(codeFor(errorCodeName));
    }

    /**
     * @param errorCode            {@code PlaybackException.errorCode} of the failure
     * @param hasVideoTrack        whether there is a video track the software decoder could rescue
     * @param manualDecoder        whether the viewer chose the decoder in Settings; never overridden
     * @param alreadyTriedSoftware whether software video is already active or was already tried
     */
    public static Action decide(int errorCode, boolean hasVideoTrack, boolean manualDecoder,
                                boolean alreadyTriedSoftware) {
        if (!isDecoderError(errorCode)) {
            return Action.RETRY;
        }
        // No video track means there is nothing for a software video decoder to rescue.
        if (!hasVideoTrack) {
            return Action.RETRY;
        }
        // Software already failed too: retrying the same decoder would loop, so end on the card.
        if (alreadyTriedSoftware) {
            return Action.GIVE_UP;
        }
        // Manual is the viewer's choice; the app may retry it but must not override it.
        if (manualDecoder) {
            return Action.RETRY;
        }
        return Action.FALLBACK_SOFTWARE;
    }

    /** The same decision from a {@code PlaybackException.getErrorCodeName()} string. */
    public static Action decide(String errorCodeName, boolean hasVideoTrack, boolean manualDecoder,
                                boolean alreadyTriedSoftware) {
        return decide(codeFor(errorCodeName), hasVideoTrack, manualDecoder, alreadyTriedSoftware);
    }

    /** The int behind a code name, or 0 (which {@link #isDecoderError(int)} rejects) if unknown. */
    static int codeFor(String errorCodeName) {
        if (errorCodeName == null) {
            return 0;
        }
        switch (errorCodeName.trim().toUpperCase(java.util.Locale.US)) {
            case "ERROR_CODE_DECODER_INIT_FAILED":
                return ERROR_CODE_DECODER_INIT_FAILED;
            case "ERROR_CODE_DECODER_QUERY_FAILED":
                return ERROR_CODE_DECODER_QUERY_FAILED;
            case "ERROR_CODE_DECODING_FAILED":
                return ERROR_CODE_DECODING_FAILED;
            case "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES":
                return ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES;
            case "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED":
                return ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
            default:
                return 0;
        }
    }
}
