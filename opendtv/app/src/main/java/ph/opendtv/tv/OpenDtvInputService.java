package ph.opendtv.tv;

import android.media.tv.TvInputService;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

/** Live Channels / TV Input Framework entry point for the OpenDTV USB ISDB-T tuner. */
@OptIn(markerClass = UnstableApi.class)
public final class OpenDtvInputService extends TvInputService {
    @Override
    public Session onCreateSession(String inputId) {
        return new TunerSession(this);
    }
}
