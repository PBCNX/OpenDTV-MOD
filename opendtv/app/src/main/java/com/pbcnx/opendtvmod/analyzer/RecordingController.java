package com.pbcnx.opendtvmod.analyzer;


import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.pbcnx.opendtvmod.R;


/** Whole-mux recording to a .ts file: the Record action and the storage permission it needs. */
public final class RecordingController {
    private android.widget.TextView actionRecord;
    private FileOutputStream wholeStreamOutput;
    private volatile boolean isRecordingWhole;


    private final AnalyzerActivity a;
    private final AnalyzerState state;
    private final StatusSink status;
    /** The demux the tap is attached to; a retune swaps the hub's demux. */
    private com.pbcnx.opendtvmod.ts.TsDemux tapped;
    private volatile boolean failureQueued;

    /** Writes each raw packet of the mux; the tap, not a source listener, is the recording path. */
    private final com.pbcnx.opendtvmod.ts.PacketSink packetSink = new com.pbcnx.opendtvmod.ts.PacketSink() {
        @Override
        public void onPacket(byte[] b, int o) {
            synchronized (a) {
                if (!isRecordingWhole || wholeStreamOutput == null) return;
                try {
                    wholeStreamOutput.write(b, o, 188);
                } catch (Exception e) {
                    // A full disk must not look like a running recording.
                    Log.e(AnalyzerState.TAG, "Whole stream write error", e);
                    if (failureQueued) return;   // one report per attempt, not per packet
                    failureQueued = true;
                    final String why = describe(e);
                    state.ui.post(() -> {
                        stopWholeStreamRecording();
                        Toast.makeText(a, "Whole-mux recording failed: " + why, Toast.LENGTH_LONG).show();
                        status.setStatus("Whole-mux recording failed: " + why);
                    });
                }
            }
        }

        @Override
        public void flush() {
            if (wholeStreamOutput == null) return;
            synchronized (a) {
                try {
                    wholeStreamOutput.flush();
                } catch (Exception ignored) {
                    // the next write reports it
                }
            }
        }
    };

    public RecordingController(AnalyzerActivity activity, AnalyzerState state, StatusSink status) {
        this.a = activity;
        this.state = state;
        this.status = status;
    }

    public void bindViews() {
        actionRecord = a.findViewById(R.id.action_record);
        actionRecord.setOnClickListener(v -> toggleWholeStreamRecording());
    }

    public synchronized void toggleWholeStreamRecording() {
        if (!isRecordingWhole) {
            if (!com.pbcnx.opendtvmod.ui.StorageAccess.ensure(a, this::toggleWholeStreamRecording)) return;
            try {
                File dir = com.pbcnx.opendtvmod.ui.StorageAccess.recordingsDir(a);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                int freq = state.currentTunedKhz > 0 ? state.currentTunedKhz : (state.currentMuxIndex >= 0 && state.currentMuxIndex < state.muxes.size() ? state.muxes.get(state.currentMuxIndex).khz : 497143);
                String timeStamp = new SimpleDateFormat("MMddyyyy-HHmmss", Locale.US).format(new Date());
                File file = new File(dir, recordingFileName(freq, state.demux.getNetworkName(), timeStamp));
                wholeStreamOutput = new FileOutputStream(file);
                isRecordingWhole = true;
                ensureTap(state.demux);
                actionRecord.setText("Stop rec");
                actionRecord.setTextColor(ContextCompat.getColor(a, R.color.odtv_live));
                Toast.makeText(a, "Recording the whole mux · saved in OpenDTV/wholets; press Stop rec to end.", Toast.LENGTH_SHORT).show();
                status.setStatus("Recording the whole mux to " + file.getAbsolutePath());
            } catch (Exception e) {
                Log.e(AnalyzerState.TAG, "Failed to start whole stream recording", e);
                final String why = describe(e);
                Toast.makeText(a, "Could not start recording: " + why, Toast.LENGTH_SHORT).show();
                status.setStatus("Could not start recording: " + why);
            }
        } else {
            stopWholeStreamRecording();
            Toast.makeText(a, "Whole-mux recording stopped · saved in OpenDTV/wholets.", Toast.LENGTH_SHORT).show();
            status.setStatus("Whole mux recording saved.");
        }
    }

    /** The one reason text for a recording failure: the message, or the exception type when empty. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message;
    }

    /** The one recording-file name rule: frequency - safe network name - timestamp. */
    static String recordingFileName(int freqKhz, String networkName, String timeStamp) {
        String name = networkName == null ? "" : networkName.trim();
        if (name.isEmpty()) name = "Network";
        name = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        return freqKhz + "-" + name + "-" + timeStamp + ".ts";
    }

    /** Re-attaches the tap when a retune swapped the demux; does nothing while not recording. */
    public void ensureTap(com.pbcnx.opendtvmod.ts.TsDemux demux) {
        if (!isRecordingWhole || demux == null || demux == tapped) return;
        if (tapped != null) tapped.removeTapSink(packetSink);
        tapped = demux;
        demux.addTapSink(packetSink);
    }

    public void stopWholeStreamRecording() {
        synchronized (a) {   // the packet writer holds this monitor: never close under a write
        isRecordingWhole = false;
        if (tapped != null) {
            tapped.removeTapSink(packetSink);
            tapped = null;
        }
        if (wholeStreamOutput != null) {
            try {
                wholeStreamOutput.flush();
                wholeStreamOutput.close();
            } catch (Exception ignored) {
                // already closing for teardown; nothing here is still needed
            }
            wholeStreamOutput = null;
        }
        if (actionRecord != null) {
            actionRecord.setText("Record");
            actionRecord.setTextColor(ContextCompat.getColor(a, R.color.odtv_on_surface));
        }
        failureQueued = false;
        }
    }
}
