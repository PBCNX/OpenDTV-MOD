package ph.opendtv.bml;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import ph.opendtv.R;

/**
 * Portrait remote, shaped like a handset: one square pad with the arrows on its edges,
 * OK in the middle, a key in each corner and the colour row underneath.
 *
 * <pre>
 *   d    ^    exit
 *   &lt;    OK   &gt;
 *   123  v    &lt;-
 * </pre>
 *
 * The corners are the four things this pad can do over a data broadcast: DATA opens and
 * hides the page (the d of a データ key), EXIT closes it, BACK walks its history, and 123
 * turns the pad into the handset's twelve keys - 1-9, then 10, 0, 11 - with back and
 * close in a footer underneath. The key lives in one footprint, so the panel no longer
 * needs a second column for the digits.
 */
public final class CornerPad extends FrameLayout {
    public interface Listener {
        void onKey(int aribKeyCode);
        void onExit();
        void onData();
    }

    private static final int PAD_DP = 220;
    /** One 3x3 grid: 10 dp in from the square's edge, 64 dp cells, 4 dp between them. */
    private static final int INSET_DP = 10;
    private static final int CELL_DP = 64;
    private static final int STEP_DP = 68;
    private static final int DOT_W_DP = 44;
    private static final int DOT_H_DP = 26;
    private static final int DOT_GAP_DP = 8;
    private static final int GAP_DP = 16;
    /** Height of the digits face's footer, where its two ways out sit. */
    private static final int DIGIT_FOOTER_DP = 40;
    /** A digit key, or one of the two keys in the footer. */
    private static final int KEY_PAD = -1;
    private static final int KEY_EXIT = -2;

    private final List<Button> keys = new ArrayList<>();
    private Listener listener;
    private FrameLayout padKeys;
    private LinearLayout digitFace;
    private Button dataKey;
    private Button toggle;
    private boolean digitsShown;

    public CornerPad(Context c) {
        this(c, null);
    }

    /** Needed by the layout inflater: a view declared in XML is built through this one. */
    public CornerPad(Context c, android.util.AttributeSet attrs) {
        super(c, attrs);
        int p = dp(16);
        setPadding(p, p, p, p);
        setBackground(RemoteKeys.rounded(high(), dp(22), divider(), dp(2)));

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        // The pad is the widest child; centre the rest on it, or a wrap_content row
        // (the colours) sits at the column's left edge and looks shifted.
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout square = new FrameLayout(c);
        square.setLayoutParams(new LinearLayout.LayoutParams(dp(PAD_DP), dp(PAD_DP)));
        square.setBackground(RemoteKeys.rounded(surface(), dp(24), divider(), dp(2)));
        column.addView(square);

        padKeys = new FrameLayout(c);
        padKeys.setLayoutParams(new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        square.addView(padKeys);
        buildPadKeys();

        digitFace = buildDigitFace();
        digitFace.setVisibility(GONE);
        square.addView(digitFace);

        // The face switcher is the bottom-left cell, on both faces.
        toggle = textKey("123", 13);
        toggle.setBackground(cornerKey());
        toggle.setLayoutParams(cell(2, 0));        toggle.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { showDigits(!digitsShown); }
        });
        square.addView(toggle);

        column.addView(buildColours());
        addView(column);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** The DATA key is the page's state: bright while a page is up, dim otherwise. */
    public void setDataActive(boolean active) {
        if (dataKey != null) dataKey.setAlpha(active ? 1f : 0.45f);
    }

    /** Puts the D-pad on the pad's first key (used when the pad takes over the arrows). */
    public void focusFirst() {
        if (!keys.isEmpty()) keys.get(0).requestFocus();
    }

    // ------------------------------------------------------------------ the pad

    private void buildPadKeys() {
        place(0, 1, arrow("\u25B2", BmlController.UP));      // up
        place(1, 0, arrow("\u25C0", BmlController.LEFT));    // left
        place(1, 2, arrow("\u25B6", BmlController.RIGHT));   // right
        place(2, 1, arrow("\u25BC", BmlController.DOWN));    // down

        Button ok = textKey("OK", 15);
        ok.setBackground(solidKey(high()));
        ok.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { send(BmlController.ENTER); }
        });
        place(1, 1, ok);

        dataKey = textKey("d", 20);
        dataKey.setBackground(cornerKey());
        dataKey.setContentDescription("Data broadcast");
        dataKey.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onData();
            }
        });
        place(0, 0, dataKey);

        Button exit = textKey("\u00D7", 22);
        exit.setBackground(cornerKey());
        exit.setContentDescription("Close the data broadcast");
        exit.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onExit();
            }
        });
        place(0, 2, exit);

        Button back = textKey("\u2190", 20);
        back.setBackground(cornerKey());
        back.setContentDescription("Back inside the page");
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { send(BmlController.BACK); }
        });
        place(2, 2, back);
    }

    /** Adds a key at one cell of the 3x3 grid: row and col 0..2, every key one size. */
    private void place(int row, int col, Button b) {
        padKeys.addView(b, cell(row, col));
    }

    private FrameLayout.LayoutParams cell(int row, int col) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(CELL_DP), dp(CELL_DP), Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(INSET_DP + col * STEP_DP);
        lp.topMargin = dp(INSET_DP + row * STEP_DP);
        return lp;
    }

    /** Blue, red, green, yellow - the order on the handset and on the analyzer's pad. */
    private LinearLayout buildColours() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        // Full square width, so the four pills line up with the grid's own edges.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(PAD_DP), LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(GAP_DP);
        row.setLayoutParams(lp);

        pill(row, R.color.odtv_key_blue, BmlController.BLUE);
        pill(row, R.color.odtv_live, BmlController.RED);
        pill(row, R.color.odtv_ok, BmlController.GREEN);
        pill(row, R.color.odtv_accent, BmlController.YELLOW);
        return row;
    }

    /** A colour key: a small pill, all four the same size, in one row. */
    private void pill(LinearLayout row, int colourRes, final int code) {
        Button b = new Button(getContext());
        b.setBackground(pillKey(RemoteKeys.colour(getContext(), colourRes)));
        b.setContentDescription(colourName(code));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dp(DOT_W_DP), dp(DOT_H_DP));
        lp.setMargins(dp(DOT_GAP_DP / 2), 0, dp(DOT_GAP_DP / 2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { send(code); }
        });
        keys.add(b);
        row.addView(b);
    }

    // ------------------------------------------------------------------ the digits

    private LinearLayout buildDigitFace() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        column.setPadding(dp(8), dp(8), dp(8), dp(8));

        column.addView(digitRow(new int[]{1, 2, 3}));
        column.addView(digitRow(new int[]{4, 5, 6}));
        column.addView(digitRow(new int[]{7, 8, 9}));
        // Twelve keys, as the handset has them: 10 and 11 flank the 0.
        column.addView(digitRow(new int[]{10, 0, 11}));
        column.addView(digitFooter());
        return column;
    }

    /** One row of the keypad; 0 is DIGIT0, and 10/11 come off the same base. */
    private LinearLayout digitRow(int[] values) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        for (final int n : values) row.addView(digitKey(String.valueOf(n), BmlController.DIGIT0 + n));
        return row;
    }

    /**
     * The two ways out of the keypad. The 123 key that opened this face is underneath it,
     * so the face carries its own back and close - one at each end, where the thumbs are,
     * and light like the pad's corner keys so they do not read as digits.
     */
    private LinearLayout digitFooter() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(DIGIT_FOOTER_DP));
        lp.topMargin = dp(2);
        row.setLayoutParams(lp);
        row.addView(digitKey("\u25C0", KEY_PAD, cornerKey()));
        row.addView(digitKey("\u00D7", KEY_EXIT, cornerKey()));
        return row;
    }

    /** @param code a BML key, or KEY_PAD / KEY_EXIT for the face's own two keys */
    private Button digitKey(String text, final int code) {
        return digitKey(text, code, tileKey());
    }

    private Button digitKey(String text, final int code, android.graphics.drawable.Drawable plate) {
        Button b = textKey(text, code < 0 ? 17 : 15);
        b.setBackground(plate);
        if (code == KEY_PAD) b.setContentDescription("Back to the arrow pad");
        if (code == KEY_EXIT) b.setContentDescription("Close the data broadcast");
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (code == KEY_PAD) {
                    showDigits(false);
                    return;
                }
                if (code == KEY_EXIT) {
                    if (listener != null) listener.onExit();
                    return;
                }
                send(code);
                // Stay on the digits: a page that wants "12" must not be interrupted by
                // the pad snapping back after the first key.
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    /** Back to the D-pad face: the pad always opens on it. */
    public void resetFace() {
        if (digitsShown) showDigits(false);
    }

    private void showDigits(boolean show) {
        digitsShown = show;
        padKeys.setVisibility(show ? GONE : VISIBLE);
        digitFace.setVisibility(show ? VISIBLE : GONE);
        if (toggle != null) toggle.setVisibility(show ? GONE : VISIBLE);
    }

    // ------------------------------------------------------------------ keys

    private Button arrow(String glyph, final int code) {
        Button b = textKey(glyph, 20);
        b.setBackground(arrowKey());
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { send(code); }
        });
        return b;
    }

    private Button textKey(String text, int textSp) {
        Button b = RemoteKeys.button(getContext(), text, textSp, onSurface());
        keys.add(b);
        return b;
    }

    private void send(int code) {
        if (listener != null) listener.onKey(code);
    }

    private String colourName(int code) {
        switch (code) {
            case BmlController.RED: return "Red";
            case BmlController.GREEN: return "Green";
            case BmlController.YELLOW: return "Yellow";
            default: return "Blue";
        }
    }

    // ------------------------------------------------------------------ drawables

    /**
     * Arrows ride a faint plate so they read as keys rather than as loose glyphs; the
     * plate is what takes the gold ring when the D-pad lands on one.
     */
    private Drawable arrowKey() {
        return plate(0x1AFFFFFF, 14);
    }

    /** A corner key: a plate, like the handset's own corner buttons. */
    private Drawable cornerKey() {
        return plate(high(), 14);
    }

    /** A digit: the same plate, slightly quieter. */
    private Drawable tileKey() {
        return plate(0x14FFFFFF, 12);
    }

    /**
     * One plate shape for every key: a quiet fill and a hairline edge at rest, swapped
     * for a gold wash and the 2 dp ring while focused.
     */
    private Drawable plate(int fill, int radiusDp) {
        return RemoteKeys.plate(getContext(), fill,
                RemoteKeys.blend(fill, RemoteKeys.GOLD, 0.30f),
                dp(radiusDp), dp(2), divider(), dp(1));
    }

    /** OK: a solid circle that keeps its fill and takes a gold ring when focused. */
    private Drawable solidKey(int fill) {
        return RemoteKeys.oval(getContext(), fill,
                RemoteKeys.blend(fill, RemoteKeys.GOLD, 0.22f), dp(2), divider(), dp(1));
    }

    /** A colour pill: keeps its fill, takes the gold ring while focused. */
    private Drawable pillKey(int fill) {
        return RemoteKeys.plate(getContext(), fill,
                RemoteKeys.blend(fill, RemoteKeys.GOLD, 0.25f),
                dp(DOT_H_DP / 2), dp(3),
                RemoteKeys.blend(fill, 0xFF000000, 0.35f), dp(2));
    }

    private int dp(int v) {
        return RemoteKeys.dp(getContext(), v);
    }

    private int onSurface() {
        return RemoteKeys.onSurface(getContext());
    }

    private int high() {
        return RemoteKeys.high(getContext());
    }

    private int surface() {
        return RemoteKeys.surface(getContext());
    }

    private int divider() {
        return RemoteKeys.divider(getContext());
    }
}
