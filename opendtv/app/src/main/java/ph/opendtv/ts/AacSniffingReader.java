package ph.opendtv.ts;

import android.util.Log;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ts.AdtsReader;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.LatmReader;
import androidx.media3.extractor.ts.TsPayloadReader;

/**
 * Picks ADTS or LATM from the first AAC PES payload instead of trusting the
 * PMT stream type. Some Philippine muxes declare 0x11/LATM but send ADTS.
 */
final class AacSniffingReader implements ElementaryStreamReader {
    private final String language;
    private final int roleFlags;
    private ElementaryStreamReader delegate;
    private ExtractorOutput output;
    private TsPayloadReader.TrackIdGenerator idGenerator;
    private boolean pendingStart;
    private long pendingTimeUs;
    private int pendingFlags;

    AacSniffingReader(String language, int roleFlags) {
        this.language = language;
        this.roleFlags = roleFlags;
    }

    @Override
    public void createTracks(ExtractorOutput output, TsPayloadReader.TrackIdGenerator idGenerator) {
        this.output = output;
        this.idGenerator = idGenerator;
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
            int p = data.getPosition();
            byte[] b = data.getData();
            boolean adts = p + 1 < data.limit()
                    && (b[p] & 0xFF) == 0xFF
                    && (b[p + 1] & 0xF0) == 0xF0;
            String first = p + 1 < data.limit()
                    ? Integer.toHexString(b[p] & 0xFF) + " " + Integer.toHexString(b[p + 1] & 0xFF)
                    : "short";
            Log.i("OpenDTV-AAC", "AAC choice " + (adts ? "ADTS" : "LATM") + " first=" + first);
            AacTransport.set(adts ? AacTransport.ADTS : AacTransport.LATM);
            delegate = adts
                    ? new AdtsReader(false, language, roleFlags, "video/mp2t")
                    : new LatmReader(language, roleFlags, "video/mp2t");
            if (output != null && idGenerator != null) {
                delegate.createTracks(output, idGenerator);
            }
            if (pendingStart) {
                delegate.packetStarted(pendingTimeUs, pendingFlags);
                pendingStart = false;
            }
        }
        delegate.consume(data);
    }

    @Override
    public void packetFinished(boolean ended) {
        if (delegate != null) delegate.packetFinished(ended);
    }
}
