package com.pbcnx.opendtvmod.ui.watch;

import com.pbcnx.opendtvmod.ui.Dims;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.pbcnx.opendtvmod.R;

/**
 * Text-first tuning / no-signal card over the video area.
 *
 * <p>Tuning shows the station identity and a sweeping line (not a spinner: a TV
 * tune is not the app loading). The same card becomes "No signal" with the
 * frequency and S/Q when no frame arrives, so nothing appears or vanishes
 * between the two states. Passive: it never takes a tap.</p>
 */
public final class TuningCard extends LinearLayout {
    private final LinearLayout titleRow;
    private final LayoutParams titleParams;
    private final TextView number;
    private final TextView title;
    private final TextView status;
    private final SweepLine line;
    private final TextView detail;
    private final TextView hint;
    private ValueAnimator sweep;

    public TuningCard(Context context) {
        this(context, null);
    }

    public TuningCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);

        int pad = Dims.dp(getContext(), 16);
        setPadding(pad, pad, pad, pad);

        number = text(30, R.color.odtv_accent, true);
        number.setVisibility(GONE);
        title = text(30, R.color.odtv_on_surface, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        status = text(15, R.color.odtv_on_surface_variant, false);
        line = new SweepLine(context);
        detail = text(12, R.color.odtv_on_surface_variant, false);
        hint = text(12, R.color.odtv_accent_dim, false);

        titleRow = new LinearLayout(context);
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(number);
        titleParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        titleParams.setMarginStart(Dims.dp(getContext(), 12));
        titleRow.addView(title, titleParams);

        LayoutParams rowParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        rowParams.gravity = Gravity.CENTER_HORIZONTAL;
        addView(titleRow, rowParams);
        addView(status, topMargin(8));
        addView(line, lineParams());
        addView(hint, topMargin(10));
        addView(detail, topMargin(16));
    }

    /** A tune is starting: "7.1 GMA" on one line, then the sweep and the frequency. */
    public void showTuning(String channelNumber, String channelName, String frequency) {
        showOpening(channelNumber, channelName, frequency, "Tuning in…");
    }

    /**
     * A source that cannot be tuned is opening: a capture, or a source another screen handed over.
     * Same card, honest word - "Tuning in" over a file names a step that never happens.
     */
    public void showLoading(String channelNumber, String channelName, String frequency) {
        showOpening(channelNumber, channelName, frequency, "Opening…");
    }

    private void showOpening(String channelNumber, String channelName, String frequency,
                             String statusText) {
        number.setVisibility(VISIBLE);
        number.setText(channelNumber);
        titleParams.setMarginStart(Dims.dp(getContext(), 12));
        title.setLayoutParams(titleParams);
        title.setText("— " + channelName);
        status.setVisibility(VISIBLE);
        status.setText(statusText);
        line.setVisibility(VISIBLE);
        detail.setText(join(channelName, frequency));
        detail.setVisibility(detail.getText().length() == 0 ? GONE : VISIBLE);
        hint.setVisibility(GONE);
        show();
    }


    /** The five failure cards share the same reset; only the title, hint and detail line differ. */
    private void showFailure(String titleText, String hintText, String detailLine) {
        number.setVisibility(GONE);
        titleParams.setMarginStart(0);   // alone on the line: centre it exactly
        title.setLayoutParams(titleParams);
        title.setText(titleText);
        status.setVisibility(GONE);
        line.setVisibility(GONE);
        hint.setText(hintText);
        hint.setVisibility(VISIBLE);
        detail.setText(detailLine);
        detail.setVisibility(detailLine.isEmpty() ? GONE : VISIBLE);
        show();
    }

    /** No frame arrived: bigger "No Signal", guidance, then the service/frequency in grey. */
    public void showNoSignal(String channelName, String frequency) {
        showFailure("No Signal", "Check the antenna or run a scan in Settings", join(channelName, frequency));
    }

    /**
     * The mux is locked and the PMT parsed, but the service is scrambled (CA descriptor), so
     * no frame will ever arrive. "No Signal" is a lie here: nothing is wrong with the aerial.
     */
    public void showEncrypted(String channelName, String frequency) {
        showFailure("Scrambled", "The signal is fine - this service is encrypted", join(channelName, frequency));
    }

    /**
     * Locked, PMT parsed, a video stream declared - but no frame ever arrived. The tuner did its
     * job and the decoder is waiting for keyframes, so this is not a fault and must not read as one.
     */
    public void showLockedNoFrame(String channelName, String frequency) {
        showFailure("No picture", "The signal is locked but too weak to decode", join(channelName, frequency));
    }

    /**
     * The mux is locked and the PMT parsed, but the service carries no video stream at all
     * (an audio or data service). Same rule as scrambled: never blame the aerial for it.
     */
    public void showNoVideo(String channelName, String frequency) {
        showFailure("No picture", "The signal is fine - this service carries no video", join(channelName, frequency));
    }

    /**
     * A capture that is producing packets but has no picture for the chosen service. The antenna
     * hint belongs to a tuner: nothing is being received here, so blaming the aerial would send the
     * reader after a fault that cannot exist.
     */
    public void showNoPictureFromSource(String channelName, String sourceName) {
        showFailure("No picture", "This source has no video for that service", join(channelName, sourceName));
    }

    /** No USB tuner at all. */
    public void showNoTuner() {
        number.setVisibility(GONE);
        titleParams.setMarginStart(0);
        title.setLayoutParams(titleParams);
        title.setText("Tuner not found");
        status.setVisibility(VISIBLE);
        status.setText("Plug in the USB dongle");
        line.setVisibility(GONE);
        detail.setVisibility(GONE);
        hint.setVisibility(GONE);
        show();
    }

    public void hide() {
        stopSweep();
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    private void show() {
        setVisibility(VISIBLE);
        startSweep();
    }

    private void startSweep() {
        if (sweep == null) {
            sweep = ValueAnimator.ofFloat(0f, 1f);
            sweep.setDuration(1400);
            sweep.setRepeatCount(ValueAnimator.INFINITE);
            sweep.setInterpolator(new LinearInterpolator());
            sweep.addUpdateListener(a -> line.setPhase((float) a.getAnimatedValue()));
        }
        if (!sweep.isStarted()) sweep.start();
    }

    private void stopSweep() {
        if (sweep != null) {
            sweep.cancel();
            sweep = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSweep();
        super.onDetachedFromWindow();
    }

    private TextView text(int sizeSp, int colorRes, boolean medium) {
        TextView view = new TextView(getContext());
        view.setTextSize(sizeSp);
        view.setTextColor(ContextCompat.getColor(getContext(), colorRes));
        view.setGravity(Gravity.CENTER);
        if (medium) {
            view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        }
        return view;
    }

    private LayoutParams topMargin(int dp) {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Dims.dp(getContext(), dp);
        return lp;
    }

    private LayoutParams lineParams() {
        LayoutParams lp = new LayoutParams(Dims.dp(getContext(), 160), Dims.dp(getContext(), 2));
        lp.topMargin = Dims.dp(getContext(), 14);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " · " + b;
    }


    /** A thin track with one bright segment sweeping across it. */
    private static final class SweepLine extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segment = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase;

        SweepLine(Context context) {
            super(context);
            track.setColor(0x33FFFFFF);
            segment.setColor(0xFFFFC857);
            segment.setAlpha(170);
        }

        void setPhase(float value) {
            phase = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float y = getHeight() / 2f;
            float radius = Math.max(1f, getHeight() / 2f);
            canvas.drawRoundRect(0, y - radius, w, y + radius, radius, radius, track);

            float seg = w * 0.28f;
            float x = (w + seg) * phase - seg;
            float left = Math.max(0, x);
            float right = Math.min(w, x + seg);
            if (right > left) {
                canvas.drawRoundRect(left, y - radius, right, y + radius, radius, radius, segment);
            }
        }
    }
}
