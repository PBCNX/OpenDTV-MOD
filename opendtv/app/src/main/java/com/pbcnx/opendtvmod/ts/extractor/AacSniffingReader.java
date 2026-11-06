package com.pbcnx.opendtvmod.ts.extractor;

import com.pbcnx.opendtvmod.ts.TsDemux;


import android.util.Log;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ts.AdtsReader;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.LatmReader;
import androidx.media3.extractor.ts.TsPayloadReader;

/**
 * Picks ADTS or LATM from the bytes instead of trusting the PMT stream type.
 *
 * <p>Philippine muxes disagree with their own labels: ALLTV declares 0x11 (LATM) and sends ADTS
 * ({@code FF F1 4C 80}), while two other muxes declare 0x0F (ADTS) and their first PES payload starts
 * mid-frame, so the first two bytes are not a sync word at all - measured across six muxes on 1 Nov.
 * A reader that trusts the label gives silence on ALLTV; a reader that trusts byte zero mis-files the
 * two 0x0F muxes as LATM.
 *
 * <p>So it scans <b>the whole first payload</b> for sync words and decides from the count, falling
 * back to the declared type when it finds none. The decision must happen on the first payload and
 * cannot wait for more packets: the extractor has already finished enumerating tracks by the time a
 * later one arrives ("Extractor added new track (id=102) after finishing tracks"), and a track that
 * appears after selection is never selected - which is exactly how the first version of this class
 * silenced ALLTV while getting the codec right.
 */
public final class AacSniffingReader implements ElementaryStreamReader {
    private final String language;
    private final int roleFlags;
    private final int declaredType;      // 0x0F or 0x11, used only when no sync word is found
    private final int hint;              // 1 = ADTS, 0 = LATM, -1 = the demux does not know yet
    private final TsDemux demux;         // the demux this reader belongs to: the choice is recorded there
    private ElementaryStreamReader delegate;
    private ExtractorOutput output;
    private TsPayloadReader.TrackIdGenerator idGenerator;
    private boolean pendingStart;
    private long pendingTimeUs;
    private int pendingFlags;

    AacSniffingReader(String language, int roleFlags, int declaredType, int hint, TsDemux demux) {
        this.language = language;
        this.roleFlags = roleFlags;
        this.declaredType = declaredType;
        this.hint = hint;
        this.demux = demux;
    }

    @Override
    public void createTracks(ExtractorOutput output, TsPayloadReader.TrackIdGenerator idGenerator) {
        this.output = output;
        this.idGenerator = idGenerator;
        // ALWAYS create the track here, while the extractor is still enumerating. Waiting for the
        // first payload is what Media3 ignores ("added new track after finishing tracks"): 557.143
        // stayed silent with the lazy path even after the hint existed, because one of its two AAC
        // readers was created before the demux had seen a payload. With no hint, the declared label
        // is the best available answer - right for 503/557, wrong only for a mux that lies AND has
        // not produced a payload yet, which the log then says out loud.
        if (delegate == null) {
            create(hint >= 0 ? hint == 1 : declaredType != 0x11,
                    hint >= 0 ? "demux hint" : "declared type 0x" + Integer.toHexString(declaredType));
            if (pendingStart) {
                delegate.packetStarted(pendingTimeUs, pendingFlags);
                pendingStart = false;
            }
        }
    }

    /** Builds the delegate, logs why, and registers its track. */
    private void create(boolean adts, String how) {
        Log.i("OpenDTV-AAC", "AAC choice " + (adts ? "ADTS" : "LATM") + " (" + how + ")");
        if (demux != null) demux.noteAacTransport(adts);
        delegate = adts
                ? new AdtsReader(false, language, roleFlags, "video/mp2t")
                : new LatmReader(language, roleFlags, "video/mp2t");
        if (output != null && idGenerator != null) {
            delegate.createTracks(output, idGenerator);
        }
    }

    @Override
    public void seek() {
        if (delegate != null) delegate.seek();
    }

    @Override
    public void packetStarted(long timeUs, int flags) {
        pendingStart = true;
        pendingTimeUs = timeUs;
        pendingFlags = flags;
        if (delegate != null) delegate.packetStarted(timeUs, flags);
    }

    @Override
    public void consume(ParsableByteArray data) throws ParserException {
        if (delegate == null) {
            int[] verdict = decide(data);
            boolean adts;
            String how;
            if (verdict != null) {
                adts = verdict[0] == 1;
                how = "adts=" + verdict[1] + " loas=" + verdict[2] + " in " + verdict[3] + " bytes";
            } else {
                adts = declaredType != 0x11;
                how = "no sync word, declared type 0x" + Integer.toHexString(declaredType);
            }
            create(adts, how);
            if (pendingStart) {
                delegate.packetStarted(pendingTimeUs, pendingFlags);
                pendingStart = false;
            }
        }
        delegate.consume(data);
    }

    /**
     * Counts sync words in this payload only.
     *
     * @return {@code {adts?1:0, adtsCount, loasCount, bytesScanned}}, or null when neither is present.
     */
    private static int[] decide(ParsableByteArray data) {
        byte[] b = data.getData();
        int from = data.getPosition();
        int to = data.limit();
        int adts = 0;
        int loas = 0;
        for (int i = from; i + 1 < to; i++) {
            int x = b[i] & 0xFF;
            int y = b[i + 1] & 0xFF;
            if (x == 0xFF && (y & 0xF6) == 0xF0) adts++;          // ADTS: layer bits 00
            else if (x == 0x56 && (y & 0xE0) == 0xE0) loas++;     // LOAS/LATM syncword
        }
        if (adts == 0 && loas == 0) return null;
        return new int[]{adts >= loas ? 1 : 0, adts, loas, to - from};
    }

    @Override
    public void packetFinished(boolean ended) {
        if (delegate != null) delegate.packetFinished(ended);
    }
}
