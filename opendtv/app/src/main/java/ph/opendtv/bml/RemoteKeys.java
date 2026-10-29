package ph.opendtv.bml;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.Button;

import androidx.core.content.ContextCompat;

import ph.opendtv.R;

/**
 * What every on-screen remote key is made of, for both pads.
 *
 * <p>{@link CornerPad} is the portrait handset - a fixed 220 dp square - and {@link RemotePanel}
 * is the analyzer's rows, which size themselves from the card they are given. The two lay keys
 * out differently on purpose, but a key itself is the same object in both: a button with no
 * mind of its own about size, and a plate that is quiet at rest and takes the gold wash and the
 * accent ring when the D-pad lands on it. That plate is here once, so a change to a key's look
 * is a change in one place.
 */
final class RemoteKeys {

    /** The house gold. Every focused key mixes some of it into its own fill. */
    static final int GOLD = 0xFFFFC857;

    /** Key sizes the caller may ask for: no single key is wider than its own text. */
    private RemoteKeys() {
    }

    /**
     * A key with its text, colour and button-ish defaults set and nothing else - no size, no
     * background, no listener. Both pads add the key to their own layout and keep their own
     * list for focus, so the factory stops at "this looks like a key".
     */
    static Button button(Context c, String text, int textSp, int textColour) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(textSp);
        b.setTextColor(textColour);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setFocusable(true);
        return b;
    }

    /**
     * The plate a key wears. Focus and press look the same - the gold wash and the accent
     * ring - and the rest state is a quiet fill inside a hairline. A plain shape would draw
     * the same with or without focus, which is the trap that once made the pad look dead.
     *
     * @param rest     fill when nobody is pointing at this key
     * @param focus    fill while focused or pressed (a wash of {@link #GOLD} over rest)
     * @param radiusPx corner radius, in pixels
     * @param ringPx   width of the focus ring, in pixels
     */
    static Drawable plate(Context c, int rest, int focus, int radiusPx,
                          int ringPx, int restStroke, int restStrokePx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                rounded(focus, radiusPx, accent(c), ringPx));
        s.addState(new int[]{android.R.attr.state_pressed},
                rounded(focus, radiusPx, accent(c), ringPx));
        s.addState(new int[]{}, rounded(rest, radiusPx, restStroke, restStrokePx));
        return s;
    }

    /** {@link #plate} for the round keys: OK, and the colour keys' pills when given a radius. */
    static Drawable oval(Context c, int rest, int focus, int ringPx,
                         int restStroke, int restStrokePx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, circle(focus, accent(c), ringPx));
        s.addState(new int[]{android.R.attr.state_pressed}, circle(focus, accent(c), ringPx));
        s.addState(new int[]{}, circle(rest, restStroke, restStrokePx));
        return s;
    }

    /** One shape, one state: the pads' own backgrounds are not keys. */
    static GradientDrawable rounded(int fill, int radiusPx, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    static GradientDrawable circle(int fill, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    /** Mix two ARGB colours, so a solid key keeps its identity while focused. */
    static int blend(int a, int b, float t) {
        int aa = (int) ((a >>> 24) * (1 - t) + (b >>> 24) * t);
        int ar = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int ag = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int ab = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int accent(Context c) {
        return ContextCompat.getColor(c, R.color.odtv_accent);
    }

    static int onAccent(Context c) {
        return ContextCompat.getColor(c, R.color.odtv_on_accent);
    }

    static int onSurface(Context c) {
        return ContextCompat.getColor(c, R.color.odtv_on_surface);
    }

    static int high(Context c) {
        return ContextCompat.getColor(c, R.color.odtv_surface_high);
    }

    static int surface(Context c) {
        return ContextCompat.getColor(c, R.color.odtv_surface);
    }

    static int live(Context c) {
        return ContextCompat.getColor(c, R.color.odtv_live);
    }

    static int ok(Context c) {
        return ContextCompat.getColor(c, R.color.odtv_ok);
    }

    static int divider(Context c) {
        return ContextCompat.getColor(c, R.color.odtv_divider);
    }

    /** One of the palette's colours by resource id, for the four colour keys. */
    static int colour(Context c, int res) {
        return ContextCompat.getColor(c, res);
    }

    /** White on a dark key, black on a light one: the hairline follows the text. */
    static int textEdge(int textColour) {
        return textColour == Color.WHITE ? 0x33FFFFFF : 0x33000000;
    }
}
