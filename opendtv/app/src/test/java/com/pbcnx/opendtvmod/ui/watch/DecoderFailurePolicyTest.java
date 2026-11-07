package com.pbcnx.opendtvmod.ui.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The decoder-failure decision the watch path uses. Plain JUnit on the JVM: no player, no device.
 *
 * <p>The case these tests pin is the user report: hardware MediaCodec reports
 * {@code ERROR_CODE_DECODING_FAILED} for H.264 High 1080p ({@code avc1.640028}) on a locked clean
 * mux, so the watch path must try the software decoder once instead of retrying the hardware.
 */
public class DecoderFailurePolicyTest {

    @Test
    public void decodingFailedOnAutoHardwareWithVideoFallsBackToSoftware() {
        assertEquals(DecoderFailurePolicy.Action.FALLBACK_SOFTWARE, DecoderFailurePolicy.decide(
                DecoderFailurePolicy.ERROR_CODE_DECODING_FAILED,
                /* hasVideoTrack= */ true, /* manualDecoder= */ false,
                /* alreadyTriedSoftware= */ false));
    }

    @Test
    public void everyDecoderFamilyCodeFallsBackToSoftware() {
        int[] codes = {
                DecoderFailurePolicy.ERROR_CODE_DECODER_INIT_FAILED,
                DecoderFailurePolicy.ERROR_CODE_DECODER_QUERY_FAILED,
                DecoderFailurePolicy.ERROR_CODE_DECODING_FAILED,
                DecoderFailurePolicy.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                DecoderFailurePolicy.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        };
        for (int code : codes) {
            assertEquals("code " + code, DecoderFailurePolicy.Action.FALLBACK_SOFTWARE,
                    DecoderFailurePolicy.decide(code, true, false, false));
        }
    }

    @Test
    public void decodingFailedWithManualDecoderRetriesInsteadOfOverridingTheViewer() {
        // Manual is the viewer's choice: retry it (the caller's MAX_RETRIES ends in the card),
        // but never switch the decoder behind their back.
        assertEquals(DecoderFailurePolicy.Action.RETRY, DecoderFailurePolicy.decide(
                DecoderFailurePolicy.ERROR_CODE_DECODING_FAILED, true, /* manualDecoder= */ true, false));
    }

    @Test
    public void decodingFailedWithNoVideoTrackRetries() {
        // There is no video track, so there is nothing for a software video decoder to rescue.
        assertEquals(DecoderFailurePolicy.Action.RETRY, DecoderFailurePolicy.decide(
                DecoderFailurePolicy.ERROR_CODE_DECODING_FAILED, /* hasVideoTrack= */ false, false, false));
    }

    @Test
    public void nonDecoderErrorsKeepTodaysRetryBehaviour() {
        int[] notDecoderCodes = {
                1000,   // ERROR_CODE_UNSPECIFIED
                2001,   // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                3001,   // ERROR_CODE_PARSING_CONTAINER_MALFORMED
                5001,   // ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        };
        for (int code : notDecoderCodes) {
            assertEquals("code " + code, DecoderFailurePolicy.Action.RETRY,
                    DecoderFailurePolicy.decide(code, true, false, false));
        }
    }

    @Test
    public void decodingFailedAfterSoftwareWasTriedGivesUp() {
        // The software decoder also failed: retrying either decoder only loops, so show the card.
        assertEquals(DecoderFailurePolicy.Action.GIVE_UP, DecoderFailurePolicy.decide(
                DecoderFailurePolicy.ERROR_CODE_DECODING_FAILED, true, false,
                /* alreadyTriedSoftware= */ true));
    }

    @Test
    public void decodingFailedWithManualSoftwareAlreadyActiveGivesUp() {
        assertEquals(DecoderFailurePolicy.Action.GIVE_UP, DecoderFailurePolicy.decide(
                DecoderFailurePolicy.ERROR_CODE_DECODING_FAILED, true, /* manualDecoder= */ true,
                /* alreadyTriedSoftware= */ true));
    }

    @Test
    public void codeNameFormDecidesTheSameAsTheIntForm() {
        assertEquals(DecoderFailurePolicy.Action.FALLBACK_SOFTWARE, DecoderFailurePolicy.decide(
                "ERROR_CODE_DECODING_FAILED", true, false, false));
        assertEquals(DecoderFailurePolicy.Action.RETRY, DecoderFailurePolicy.decide(
                "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", true, false, false));
        assertEquals(DecoderFailurePolicy.Action.GIVE_UP, DecoderFailurePolicy.decide(
                "ERROR_CODE_DECODING_FAILED", true, false, true));
    }

    @Test
    public void unknownOrMissingCodeNameIsNotADecoderError() {
        assertFalse(DecoderFailurePolicy.isDecoderError((String) null));
        assertFalse(DecoderFailurePolicy.isDecoderError("not a code"));
        assertEquals(DecoderFailurePolicy.Action.RETRY, DecoderFailurePolicy.decide(
                (String) null, true, false, false));
    }

    @Test
    public void decoderErrorCodesMatchTheMedia3ValuesThisBuildUses() {
        // The class duplicates these ints to stay JVM-pure; this pins them to Media3 1.7.1's
        // androidx.media3.common.PlaybackException values.
        assertEquals(4001, DecoderFailurePolicy.ERROR_CODE_DECODER_INIT_FAILED);
        assertEquals(4002, DecoderFailurePolicy.ERROR_CODE_DECODER_QUERY_FAILED);
        assertEquals(4003, DecoderFailurePolicy.ERROR_CODE_DECODING_FAILED);
        assertEquals(4004, DecoderFailurePolicy.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES);
        assertEquals(4005, DecoderFailurePolicy.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);
        assertTrue(DecoderFailurePolicy.isDecoderError(DecoderFailurePolicy.ERROR_CODE_DECODING_FAILED));
        assertFalse(DecoderFailurePolicy.isDecoderError(4006));   // resources reclaimed: retry
    }

    @Test
    public void hintIsTheSentenceTheAnalyzerShows() {
        assertEquals("decoder failed - set Settings > Video decoder to Software",
                DecoderFailurePolicy.HINT);
    }
}
