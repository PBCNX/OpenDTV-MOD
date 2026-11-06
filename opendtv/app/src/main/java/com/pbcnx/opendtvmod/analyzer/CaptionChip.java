package com.pbcnx.opendtvmod.analyzer;

import java.util.List;

import com.pbcnx.opendtvmod.ts.StreamInfo;
import com.pbcnx.opendtvmod.ts.caption.CaptionProbe;

/**
 * The analyzer's CC chip: captions are ON only when the viewer has captions enabled AND the selected
 * service actually carries a caption stream.
 *
 * <p>The chip used to read the caption setting alone, so it lit up before a channel was selected (and
 * with no channel at all) - owner report, 2026-11-06. Kept pure so the rule is tested without a device;
 * the watching strip uses the same predicate through CaptionProbe.</p>
 */
public final class CaptionChip {

    private CaptionChip() {
    }

    /** @param streams the selected service's streams, or null when nothing is selected */
    public static boolean active(boolean captionsEnabled, List<StreamInfo> streams) {
        if (!captionsEnabled || streams == null) return false;
        for (StreamInfo s : streams) {
            if (CaptionProbe.isCaptionStream(s)) return true;
        }
        return false;
    }
}
