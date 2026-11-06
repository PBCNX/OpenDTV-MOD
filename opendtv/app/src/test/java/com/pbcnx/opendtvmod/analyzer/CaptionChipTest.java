package com.pbcnx.opendtvmod.analyzer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.pbcnx.opendtvmod.ts.StreamInfo;

/**
 * The CC chip with no channel must be off, whatever the caption setting says (owner report,
 * 2026-11-06: the analyzer showed CC on before anything was tuned).
 */
public class CaptionChipTest {

    private static StreamInfo stream(int type, int tag, int dataComponentId) {
        StreamInfo s = new StreamInfo();
        s.streamType = type;
        s.componentTag = tag;
        s.dataComponentId = dataComponentId;
        return s;
    }

    @Test public void offWhenNothingIsSelected() {
        assertFalse(CaptionChip.active(true, null));
        assertFalse(CaptionChip.active(true, Collections.emptyList()));
    }

    @Test public void offWhenTheServiceHasNoCaptionStream() {
        StreamInfo video = stream(0x1B, -1, -1);
        StreamInfo audio = stream(0x0F, -1, -1);
        assertFalse(CaptionChip.active(true, Arrays.asList(video, audio)));
    }

    @Test public void onForADeclaredCaptionStreamWhenCaptionsAreEnabled() {
        StreamInfo caption = stream(0x06, -1, 0x0008);   // data_component_id 0x08 = captions
        assertTrue(CaptionChip.active(true, Arrays.asList(stream(0x1B, -1, -1), caption)));
    }

    @Test public void onForAComponentTagCaptionStream() {
        StreamInfo caption = stream(0x06, 0x38, -1);     // component tag 0x38 = captions
        assertTrue(CaptionChip.active(true, Collections.singletonList(caption)));
    }

    @Test public void offWhenTheViewerDisabledCaptions() {
        StreamInfo caption = stream(0x06, -1, 0x0008);
        assertFalse(CaptionChip.active(false, Collections.singletonList(caption)));
    }
}
