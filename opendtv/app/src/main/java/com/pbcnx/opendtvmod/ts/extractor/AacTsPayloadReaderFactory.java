package com.pbcnx.opendtvmod.ts.extractor;

import com.pbcnx.opendtvmod.ts.TsDemux;


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
    private final TsDemux demux;

    public AacTsPayloadReaderFactory(int flags) {
        this(flags, null);
    }

    public AacTsPayloadReaderFactory(int flags, TsDemux demux) {
        delegate = new DefaultTsPayloadReaderFactory(flags);
        this.demux = demux;
    }

    @Override
    public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
        return delegate.createInitialPayloadReaders();
    }

    @Override
    public TsPayloadReader createPayloadReader(int streamType, TsPayloadReader.EsInfo esInfo) {
        Log.i("OpenDTV-AAC", "create streamType=0x" + Integer.toHexString(streamType));
        // ONLY 0x11 is intercepted. 0x0F stays on the default ADTS reader for a measured reason:
        // wrapping it in the sniffing reader delays its track creation past the extractor's
        // endTracks(), and Media3 then logs "Extractor added new track after finishing tracks" and
        // never selects it - no audio at all. Measured 1 Nov: 0x0F + sniffing = video only; 0x0F on
        // the default path = audio selected. The engine session's "0x0F carries raw AAC" reading is
        // also wrong: those payloads contain ADTS sync words (37 / 20 / 21 of them), the first one
        // simply does not sit at byte zero and AdtsReader resyncs to it.
        if (streamType == 0x11 && esInfo != null) {
            // The demux has been reading this stream since the tune, so it usually already knows the
            // transport; passing it lets the reader create its track NOW instead of on the first
            // payload, which is the difference between "audio selected" and "added too late".
            int hint = demux == null ? -1 : demux.aacTransportHint();
            return new PesReader(new AacSniffingReader(
                    esInfo.language, esInfo.getRoleFlags(), streamType, hint, demux));
        }
        return delegate.createPayloadReader(streamType, esInfo);
    }
}
