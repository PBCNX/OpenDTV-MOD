package com.pbcnx.opendtvmod.ui.watch;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.ts.pipe.ServiceFilter;

import com.pbcnx.opendtvmod.ui.ChannelAdapter;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.pbcnx.opendtvmod.ts.ServiceInfo;

/**
 * Owns the channel-recording state and drives the REC view.
 *
 * <p>The writer is the state: {@link ServiceFilter#isRecording()} is the truth, not a flag
 * of our own. The REC key in either layout is its tally - a filled red disc that pulses while the
 * sink is writing and a quiet ring when it is not. The pulse runs on the background drawable's
 * alpha so the word stays solid: a label fading in and out reads as a redraw, a ring that
 * breathes reads as a tally.</p>
 */
public final class ChannelRecorder {

    /** The same tag MainActivity logged the recording under: bench notes are grepped for it. */
    private static final String TAG = "OpenDTV-Main";

    private final Activity activity;
    private final Handler ui;
    private final BooleanSupplier storagePermission;
    private final Supplier<ServiceInfo> currentService;
    /** The live filter is owned by WatchPlayback; the recorder only asks for it when it writes. */
    private final Supplier<ServiceFilter> liveSink;

    private TextView btnRecord;               // portrait: the REC key, which is also the tally light
    private boolean failureReported;
    private ValueAnimator recordPulse;        // its pulse while a recording runs

    private final Runnable recordTick = new Runnable() {
        @Override
        public void run() {
            // The light follows the writer, not the tap: if the sink stopped by itself,
            // the tally has to go out with it.
            updateTally();   // removes this callback and re-posts it while the writer runs
            reportFailureOnce();
        }
    };

    /**
     * @param storagePermission the all-files-access / WRITE_EXTERNAL_STORAGE gate; asks for it and
     *                          returns false when the caller has to wait for the grant
     * @param currentService    the service of the tuned channel, or null when nothing is tuned
     */
    public ChannelRecorder(Activity activity, Handler ui, BooleanSupplier storagePermission,
                           Supplier<ServiceInfo> currentService, Supplier<ServiceFilter> liveSink) {
        this.activity = activity;
        this.ui = ui;
        this.storagePermission = storagePermission;
        this.currentService = currentService;
        this.liveSink = liveSink;
    }

    /**
     * The REC key of whichever layout was just inflated; null when that layout has none. Called on
     * every bind, because a rotation inflates a new view.
     */
    public void setRecordView(TextView view) {
        btnRecord = view;
        if (view != null) {
            view.setOnClickListener(v -> toggle());
        }
    }

    /**
     * The writer can stop by itself (a failed write sets recordError and clears isRecording). The
     * tick used to only follow the light, so that failure was recorded and never delivered; report it
     * once, until the next recording starts.
     */
    private void reportFailureOnce() {
        ServiceFilter s = liveSink.get();
        if (s == null) return;
        java.io.IOException failure = s.recordError();
        if (failure == null) {
            failureReported = false;
            return;
        }
        if (failureReported) return;
        failureReported = true;
        Toast.makeText(activity, activity.getString(R.string.toast_recording_failed_why, failure), Toast.LENGTH_LONG).show();
    }

    /** Recording is the writer's state, not a flag of our own. */
    public boolean isRecording() {
        ServiceFilter s = liveSink.get();
        return s != null && s.isRecording();
    }

    public void toggle() {
        ServiceFilter sink = liveSink.get();
        if (sink == null) {
            Toast.makeText(activity, R.string.toast_tune_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!sink.isRecording()) {
            if (!storagePermission.getAsBoolean()) return;
            try {
                File dir = new File(com.pbcnx.opendtvmod.ui.StorageAccess.appDir(activity), "Record");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                ServiceInfo service = currentService.get();
                String chNum = service != null ? service.displayNumber().replace("~", "") : "0";
                String chName = service != null ? service.name : "Channel";
                chNum = chNum.replaceAll("[^a-zA-Z0-9_-]", "_");
                chName = chName.replaceAll("[^a-zA-Z0-9_-]", "_");
                String timeStamp = new SimpleDateFormat("MMddyyyy-HHmmss", Locale.US).format(new Date());
                String fileName = chNum + "-" + chName + "-" + timeStamp + ".ts";
                File file = new File(dir, fileName);
                sink.startRecord(file);
                failureReported = false;
                Toast.makeText(activity, service != null
                        ? activity.getString(R.string.toast_recording_channel, ChannelAdapter.displayName(service))
                        : activity.getString(R.string.toast_recording), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                Toast.makeText(activity, R.string.toast_recording_failed, Toast.LENGTH_SHORT).show();
            }
        } else {
            boolean wrote = sink.stopRecord();
            java.io.IOException failure = sink.recordError();
            Toast.makeText(activity,
                    failure != null ? activity.getString(R.string.toast_recording_failed_why, failure)
                            : wrote ? activity.getString(R.string.toast_recording_stopped)
                            : activity.getString(R.string.toast_nothing_recorded),
                    Toast.LENGTH_SHORT).show();
        }
        updateTally();
    }

    /**
     * The tally light, from one reading of the writer.
     */
    public void updateTally() {
        boolean on = isRecording();
        // One tick a second while it runs: if the writer dies on its own, the light does too.
        ui.removeCallbacks(recordTick);
        if (on) ui.postDelayed(recordTick, 1000);
        if (btnRecord == null) return;
        btnRecord.setSelected(on);
        btnRecord.setContentDescription(activity.getString(on ? R.string.cd_stop_recording : R.string.cd_record_channel));
        if (on) {
            startPulse();
        } else {
            stopPulse();
        }
    }

    private void startPulse() {
        if (recordPulse != null) return;
        Drawable ring = btnRecord.getBackground();
        if (ring == null) return;
        ring = ring.mutate();
        final Drawable target = ring;
        // Someone who turned animation off gets a steady red disc, not a strobe.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            target.setAlpha(255);
            return;
        }
        recordPulse = ValueAnimator.ofInt(255, 120);
        recordPulse.setDuration(700);
        recordPulse.setRepeatCount(ValueAnimator.INFINITE);
        recordPulse.setRepeatMode(ValueAnimator.REVERSE);
        recordPulse.setInterpolator(new AccelerateDecelerateInterpolator());
        recordPulse.addUpdateListener(a -> target.setAlpha((Integer) a.getAnimatedValue()));
        recordPulse.start();
    }

    public void stopPulse() {
        if (recordPulse != null) {
            recordPulse.cancel();
            recordPulse = null;
        }
        if (btnRecord != null && btnRecord.getBackground() != null) {
            btnRecord.getBackground().setAlpha(255);
        }
    }

    /** onDestroy: the writer goes down with the activity. */
    public void release() {
        // The filter itself belongs to WatchPlayback; only the write is ours to stop.
        ServiceFilter s = liveSink.get();
        if (s != null) s.stopRecord();
    }
}
