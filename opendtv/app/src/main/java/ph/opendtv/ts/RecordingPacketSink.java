package ph.opendtv.ts;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Wraps ServiceFilter to also tee filtered TS packets into a recording FileOutputStream.
 */
public final class RecordingPacketSink implements PacketSink {
    private static final String TAG = "RecordingPacketSink";
    private final ServiceFilter inner;
    private FileOutputStream fos;
    private boolean recording;

    public RecordingPacketSink(TsPipe out, int tsId, ServiceInfo svc) {
        this.inner = new ServiceFilter(out, tsId, svc);
    }

    public void setCaptionSink(ServiceFilter.CaptionSink sink) {
        inner.setCaptionSink(sink);
    }

    public void setRawCaptionSink(ServiceFilter.RawCaptionSink sink) {
        inner.setRawCaptionSink(sink);
    }

    public void setCaptionLanguage(int language) {
        inner.setCaptionLanguage(language);
    }

    public synchronized void startRecord(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        fos = new FileOutputStream(file);
        inner.setRecordStream(fos);
        recording = true;
    }

    public synchronized void stopRecord() {
        recording = false;
        inner.setRecordStream(null);
        if (fos != null) {
            try {
                fos.flush();
                fos.close();
            } catch (IOException ignored) {}
            fos = null;
        }
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    @Override
    public void onPacket(byte[] b, int o) {
        inner.onPacket(b, o);
    }

    @Override
    public void flush() {
        inner.flush();
    }
}
