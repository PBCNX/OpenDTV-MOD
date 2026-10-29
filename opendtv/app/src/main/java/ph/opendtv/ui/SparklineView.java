package ph.opendtv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import ph.opendtv.R;

/**
 * Minimal sparkline for the analyzer Graph tab: one polyline, a soft fill under
 * it and three grid lines. No axes, no labels - the activity prints those.
 *
 * The newest sample is always at the right edge, so a half-filled window grows
 * leftwards instead of stretching.
 */
public final class SparklineView extends View {

    /** One point per analyzer tick: 30 samples = 30 s. */
    private static final int POINTS = 30;

    private final float[] values = new float[POINTS];
    private int count;

    private final float density;
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path area = new Path();
    private Shader fillShader;
    private int shaderHeight = -1;

    public SparklineView(Context context) {
        this(context, null);
    }

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        int accent = ContextCompat.getColor(context, R.color.odtv_accent);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(accent);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(accent);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, density));
        gridPaint.setColor(ContextCompat.getColor(context, R.color.odtv_divider));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(accent);
    }

    /** Replaces the whole window; values older than the newest {@link #POINTS} are dropped. */
    public void setSeries(float[] series) {
        count = 0;
        if (series != null) {
            int from = Math.max(0, series.length - POINTS);
            for (int i = from; i < series.length; i++) values[count++] = Math.max(0f, series[i]);
        }
        invalidate();
    }

    public void reset() {
        count = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad = 2f * density;
        for (int i = 1; i <= 3; i++) {
            float y = pad + (h - 2f * pad) * i / 4f;
            canvas.drawLine(0f, y, w, y, gridPaint);
        }
        if (count == 0) return;

        float max = 0f;
        for (int i = 0; i < count; i++) max = Math.max(max, values[i]);
        if (max <= 0f) max = 1f;
        max *= 1.15f;

        float step = w / (POINTS - 1f);
        float startX = w - (count - 1) * step;
        float lastX = w, lastY = h - pad;

        line.reset();
        area.reset();
        area.moveTo(startX, h - pad);
        for (int i = 0; i < count; i++) {
            float x = startX + i * step;
            float y = pad + (h - 2f * pad) * (1f - values[i] / max);
            if (i == 0) line.moveTo(x, y);
            else line.lineTo(x, y);
            area.lineTo(x, y);
            lastX = x;
            lastY = y;
        }
        area.lineTo(lastX, h - pad);
        area.close();

        if (shaderHeight != h) {
            shaderHeight = h;
            fillShader = new LinearGradient(0f, pad, 0f, h - pad,
                    new int[]{0x40FFC857, 0x00FFC857}, null, Shader.TileMode.CLAMP);
        }
        fillPaint.setShader(fillShader);
        canvas.drawPath(area, fillPaint);
        canvas.drawPath(line, linePaint);
        if (count > 1) canvas.drawCircle(lastX, lastY, 3f * density, dotPaint);
    }
}
