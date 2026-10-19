package ph.opendtv.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.io.InterruptedIOException;

import ph.opendtv.ts.TsPipe;

/** Media3 DataSource that reads the live, already service-filtered TS from a TsPipe. */
@UnstableApi
public final class TsPipeDataSource extends BaseDataSource {
    private final TsPipe pipe;
    private Uri uri;
    private boolean opened;

    public TsPipeDataSource(TsPipe pipe) {
        super(/* isNetwork= */ false);
        this.pipe = pipe;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        transferInitializing(dataSpec);
        opened = true;
        transferStarted(dataSpec);
        return C.LENGTH_UNSET; // live: unknown length, position is ignored
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        try {
            while (true) {
                int n = pipe.read(buffer, offset, length, 200);
                if (n > 0) {
                    bytesTransferred(n);
                    return n;
                }
                if (n < 0) return C.RESULT_END_OF_INPUT;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        if (opened) {
            opened = false;
            transferEnded();
        }
        uri = null;
    }
}
