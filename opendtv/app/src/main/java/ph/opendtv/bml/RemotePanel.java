package ph.opendtv.bml;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import ph.opendtv.R;

/**
 * On-screen remote control: DATA, arrows + OK, BACK, the four colour keys and the
 * number keys.
 *
 * Two shapes, same keys: portrait stacks nine rows, landscape/TV puts the arrow
 * pad and the digits side by side in five. The key height is then derived from
 * the height the panel is actually given, so nothing is ever clipped mid-row.
 */
public final class RemotePanel extends LinearLayout {
    public interface Listener {
        void onKey(int aribKeyCode);
        void onExit();
    }

    private static final int KEY_MIN_DP = 24, KEY_MAX_DP = 46;

    private final Listener listener;
    private final List<Button> keys = new ArrayList<>();
    private int rows = 9;
    private int keyHeight;

    public RemotePanel(Context c, Listener l) {
        super(c);
        this.listener = l;
        setOrientation(VERTICAL);
        int pad = dp(2);
        setPadding(pad, pad, pad, pad);

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            buildWide();
        } else {
            buildTall();
        }
    }

    /** Portrait: one column, nine rows. */
    private void buildTall() {
        rows = 9;
        addView(topRow());
        addView(arrowRow("\u25B2", BmlController.UP));
        addView(navRow());
        addView(arrowRow("\u25BC", BmlController.DOWN));
        addView(colourRow());
        addView(digitRow(1, 2, 3));
        addView(digitRow(4, 5, 6));
        addView(digitRow(7, 8, 9));
        addView(lastRow());
    }

    /**
     * Landscape / TV: four rows a side, so the two columns line up - the pad on the left,
     * the digits on the right:
     *
     *   DATA   ^   BACK        1 2 3
     *   &lt;      OK  &gt;           4 5 6
     *          v   EXIT BML    7 8 9
     *   Blue Red Green Yellow  10 0 11
     *
     * The empty cell under DATA keeps OK and the arrows on a true cross.
     */
    private void buildWide() {
        rows = 4;
        LinearLayout columns = new LinearLayout(getContext());
        columns.setOrientation(HORIZONTAL);
        columns.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.25f));
        left.addView(padTopRow());
        left.addView(padNavRow());
        left.addView(padBottomRow());
        left.addView(colourRow());

        LinearLayout right = new LinearLayout(getContext());
        right.setOrientation(VERTICAL);
        LinearLayout.LayoutParams rightLp =
                new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        // A gap between the pad and the digits: with only the keys' own 2 dp margins they
        // read as one twelve-key grid instead of a remote plus a number pad.
        rightLp.setMargins(dp(16), 0, 0, 0);
        right.setLayoutParams(rightLp);
        right.addView(digitRow(1, 2, 3));
        right.addView(digitRow(4, 5, 6));
        right.addView(digitRow(7, 8, 9));
        right.addView(lastRow());

        columns.addView(left);
        columns.addView(right);
        addView(columns);
    }

    /** DATA, the up arrow and BACK on one line. */
    private LinearLayout padTopRow() {
        LinearLayout r = row();
        r.addView(key("DATA", accent(), onAccent(), BmlController.DATA, 1f));
        r.addView(key("\u25B2", Color.TRANSPARENT, onSurface(), BmlController.UP, 1f));
        r.addView(key("BACK", Color.TRANSPARENT, onSurface(), BmlController.BACK, 1f));
        return r;
    }

    /** The left/OK/right line. */
    private LinearLayout padNavRow() {
        LinearLayout r = row();
        r.addView(key("\u25C0", Color.TRANSPARENT, onSurface(), BmlController.LEFT, 1f));
        r.addView(key("OK", high(), onSurface(), BmlController.ENTER, 1f));
        r.addView(key("\u25B6", Color.TRANSPARENT, onSurface(), BmlController.RIGHT, 1f));
        return r;
    }

    /** Empty cell, the down arrow, and the way out of the data broadcast. */
    private LinearLayout padBottomRow() {
        LinearLayout r = row();
        r.addView(space());
        r.addView(key("\u25BC", Color.TRANSPARENT, onSurface(), BmlController.DOWN, 1f));
        Button exit = key("EXIT BML", Color.TRANSPARENT, live(), -1, 1f);
        exit.setTextSize(11);
        exit.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { listener.onExit(); }
        });
        r.addView(exit);
        return r;
    }

    private LinearLayout topRow() {
        LinearLayout top = row();
        top.addView(key("DATA", accent(), onAccent(), BmlController.DATA, 2f));
        top.addView(key("BACK", Color.TRANSPARENT, onSurface(), BmlController.BACK, 1f));
        Button exit = key("EXIT BML", Color.TRANSPARENT, live(), -1, 1f);
        exit.setTextSize(11);
        exit.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { listener.onExit(); }
        });
        top.addView(exit);
        return top;
    }

    private LinearLayout navRow() {
        LinearLayout mid = row();
        mid.addView(key("\u25C0", Color.TRANSPARENT, onSurface(), BmlController.LEFT, 1f));
        mid.addView(key("OK", high(), onSurface(), BmlController.ENTER, 1f));
        mid.addView(key("\u25B6", Color.TRANSPARENT, onSurface(), BmlController.RIGHT, 1f));
        return mid;
    }

    private LinearLayout arrowRow(String glyph, int code) {
        LinearLayout r = row();
        r.addView(space());
        r.addView(key(glyph, Color.TRANSPARENT, onSurface(), code, 1f));
        r.addView(space());
        return r;
    }

    private LinearLayout colourRow() {
        LinearLayout colors = row();
        colors.addView(key("Blue", colour(R.color.odtv_key_blue), Color.WHITE, BmlController.BLUE, 1f));
        colors.addView(key("Red", live(), Color.WHITE, BmlController.RED, 1f));
        colors.addView(key("Green", ok(), Color.BLACK, BmlController.GREEN, 1f));
        colors.addView(key("Yellow", accent(), Color.BLACK, BmlController.YELLOW, 1f));
        return colors;
    }

    private LinearLayout digitRow(int a, int b, int c) {
        LinearLayout r = row();
        r.addView(key(String.valueOf(a), Color.TRANSPARENT, onSurface(), BmlController.DIGIT0 + a, 1f));
        r.addView(key(String.valueOf(b), Color.TRANSPARENT, onSurface(), BmlController.DIGIT0 + b, 1f));
        r.addView(key(String.valueOf(c), Color.TRANSPARENT, onSurface(), BmlController.DIGIT0 + c, 1f));
        return r;
    }

    private LinearLayout lastRow() {
        LinearLayout r = row();
        r.addView(key("10", Color.TRANSPARENT, onSurface(), BmlController.DIGIT0 + 10, 1f));
        r.addView(key("0", Color.TRANSPARENT, onSurface(), BmlController.DIGIT0, 1f));
        r.addView(key("11", Color.TRANSPARENT, onSurface(), BmlController.DIGIT0 + 11, 1f));
        return r;
    }

    /**
     * Take the height the card gives us, divide it by the rows, and keep the keys
     * between 24 and 46 dp, so the panel never overflows its card.
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(heightSpec)
                - getPaddingTop() - getPaddingBottom() - rows * dp(2) * 2;
        int wanted = available > 0 ? available / rows : dp(KEY_MAX_DP);
        int h = Math.max(dp(KEY_MIN_DP), Math.min(dp(KEY_MAX_DP), wanted));
        if (h != keyHeight) {
            keyHeight = h;
            for (Button b : keys) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
                lp.height = h;
                b.setLayoutParams(lp);
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private int dp(int v) {
        return RemoteKeys.dp(getContext(), v);
    }

    private int accent() {
        return RemoteKeys.accent(getContext());
    }

    private int onAccent() {
        return RemoteKeys.onAccent(getContext());
    }

    private int onSurface() {
        return RemoteKeys.onSurface(getContext());
    }

    private int high() {
        return RemoteKeys.high(getContext());
    }

    private int live() {
        return RemoteKeys.live(getContext());
    }

    private int ok() {
        return RemoteKeys.ok(getContext());
    }

    private int divider() {
        return RemoteKeys.divider(getContext());
    }

    private int colour(int res) {
        return RemoteKeys.colour(getContext(), res);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        r.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return r;
    }

    private View space() {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    /** A transparent fill keeps the outlined key; any colour makes a solid key. */
    private Button key(String text, int fill, int textColour, final int code, float weight) {
        Button b = RemoteKeys.button(getContext(), text, 12, textColour);
        b.setBackground(keyBackground(fill, textColour));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(KEY_MAX_DP), weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        keys.add(b);
        if (code >= 0) {
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { listener.onKey(code); }
            });
        }
        return b;
    }

    /** Puts the D-pad on the first key (DATA), for when the pad takes over the arrows. */
    public void focusFirst() {
        if (!keys.isEmpty()) keys.get(0).requestFocus();
    }

    /**
     * A key needs a focused state or the D-pad moves invisibly: a plain shape draws the
     * same whether it has focus or not (same trap as a chip on bg_scan_chip). Focused
     * keys get the 2 dp gold ring the rest of the analyzer uses, plus a faint wash.
     */
    private Drawable keyBackground(int fill, int textColour) {
        boolean outlined = fill == Color.TRANSPARENT;
        int focusFill = outlined ? 0x1FFFC857 : RemoteKeys.blend(fill, RemoteKeys.GOLD, 0.22f);
        int restStroke = outlined ? divider() : RemoteKeys.textEdge(textColour);
        return RemoteKeys.plate(getContext(), fill, focusFill, dp(9), dp(2), restStroke, dp(1));
    }
}
