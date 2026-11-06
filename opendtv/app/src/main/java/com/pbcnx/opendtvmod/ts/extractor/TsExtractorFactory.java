package com.pbcnx.opendtvmod.ts.extractor;

import com.pbcnx.opendtvmod.ts.TsDemux;


import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;

import java.util.function.Supplier;

/**
 * One TS extractor with the AAC ADTS/LATM fallback.
 *
 * <p>The demux is supplied so the payload factory can ask it for the AAC transport before the
 * extractor finishes enumerating tracks. That ordering is the whole point: a reader that waits for
 * the first payload before creating its track registers it too late, Media3 then reports
 * "Extractor added new track after finishing tracks" and never selects it - silence with the right
 * codec, which is what ALLTV did. See {@link TsDemux#aacTransportHint()}.
 */
public final class TsExtractorFactory implements ExtractorsFactory {
    private static final int READER_FLAGS =
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                    | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS;

    private final Supplier<TsDemux> demux;

    public TsExtractorFactory() {
        this(null);
    }

    public TsExtractorFactory(Supplier<TsDemux> demux) {
        this.demux = demux;
    }

    @Override
    public Extractor[] createExtractors() {
        TsDemux d = demux == null ? null : demux.get();
        return new Extractor[] {
                new TsExtractor(TsExtractor.MODE_MULTI_PMT,
                        new TimestampAdjuster(0),
                        new AacTsPayloadReaderFactory(READER_FLAGS, d))
        };
    }
}
