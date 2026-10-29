package ph.opendtv.ts;

import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;

/** One TS extractor with the AAC ADTS/LATM fallback. */
public final class TsExtractorFactory implements ExtractorsFactory {
    private static final int READER_FLAGS =
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                    | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS;

    @Override
    public Extractor[] createExtractors() {
        return new Extractor[] {
                new TsExtractor(TsExtractor.MODE_MULTI_PMT,
                        new TimestampAdjuster(0),
                        new AacTsPayloadReaderFactory(READER_FLAGS))
        };
    }
}
