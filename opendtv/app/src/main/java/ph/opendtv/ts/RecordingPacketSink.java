package ph.opendtv.ts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Wraps ServiceFilter to also tee filtered TS packets into a recording FileOutputStream.
 */
public final class RecordingPacketSink implements PacketSink {
    private final ServiceFilter inner;
    private FileOutputStream fos;
    private File recordFile;          // kept so an empty recording can be removed
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

    public void setSplitCaptions(boolean split) {
        inner.setSplitCaptions(split);
    }

    public void setCaptionLanguage(int language) {
        inner.setCaptionLanguage(language);
    }

    public synchronized void startRecord(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        recordFile = file;
        fos = new FileOutputStream(file);
        inner.setRecordStream(fos);
        recording = true;
    }

    /**
     * Stops recording and reports whether anything was written.
     *
     * <p>A service whose PMT never yields a stream opens the file and receives no packets, so
     * stopping used to leave a 0-byte .ts on storage. The test is "no TS packets", not "no
     * rendered frame": ALLTV decodes only on FFmpeg, and a frame test would refuse a good
     * channel.
     *
     * @return true when the file has content and stays, false when it was empty and deleted
     */
    public synchronized boolean stopRecord() {
        recording = false;
        inner.setRecordStream(null);
        if (fos != null) {
            try {
                fos.flush();
                fos.close();
            } catch (IOException ignored) {
                // what reached the disk is the recording; nothing left to recover
            }
            fos = null;
        }
        File file = recordFile;
        recordFile = null;
        if (file != null && file.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return false;
        }
        return file != null;
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
