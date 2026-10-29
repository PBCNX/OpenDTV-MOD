package ph.opendtv.ts;

import android.util.Log;
import android.util.SparseArray;

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.PesReader;
import androidx.media3.extractor.ts.TsPayloadReader;

/**
 * TS payload factory with a workaround for broadcasters that declare
 * stream_type 0x11 (AAC LATM) but actually carry ADTS in the PES payload.
 *
 * <p>ALLTV HD (485.143 MHz, service 64) is such a mux: the PMT says
 * "ISO/IEC 14496-3 Audio with LATM transport" and carries descriptor 0x7C,
 * while the PES payload starts with the ADTS sync word FF Fx. Media3's
 * LatmReader then rejects the stream as malformed.
 */
public final class AacTsPayloadReaderFactory implements TsPayloadReader.Factory {
    private final DefaultTsPayloadReaderFactory delegate;

    public AacTsPayloadReaderFactory(int flags) {
        delegate = new DefaultTsPayloadReaderFactory(flags);
    }

    @Override
    public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
        return delegate.createInitialPayloadReaders();
    }

    @Override
    public TsPayloadReader createPayloadReader(int streamType, TsPayloadReader.EsInfo esInfo) {
        Log.i("OpenDTV-AAC", "create streamType=0x" + Integer.toHexString(streamType));
        if (streamType == 0x11 && esInfo != null) {
            return new PesReader(new AacSniffingReader(
                    esInfo.language, esInfo.getRoleFlags()));
        }
        return delegate.createPayloadReader(streamType, esInfo);
    }
}
