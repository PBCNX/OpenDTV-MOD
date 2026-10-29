package ph.opendtv.bml;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import ph.opendtv.R;

/**
 * On-screen BML key pad for remotes without a DATA key, colour keys or a number
 * pad (Skyworth among them).
 *
 * <p>Shown by holding OK on a visible data broadcast. The colour row sits on top
 * of the digits, and both rows are exactly the same width, so neither leaves an
 * empty strip at the end.</p>
 */
public final class BmlKeyPad extends LinearLayout {
    public interface Listener {
        /** ARIB key code for the page ({@link BmlController} constants). */
        void onBmlKey(int code);

        /** D: show/hide the data broadcast itself. */
        void onData();
    }

    private static final String[] COLOURS = {"RED", "GREEN", "YELLOW", "BLUE"};
    private static final int[] COLOUR_CODES = {
            BmlController.RED, BmlController.GREEN, BmlController.YELLOW, BmlController.BLUE};
    private static final int[] COLOUR_FILL = {0xFFC62828, 0xFF2E7D32, 0xFFF9A825, 0xFF1E5BD8};

    // Both rows add up to 356 dp including the 4 dp gaps.
    private static final int GAP = 4;
    private static final int DIGIT_WIDTH = 32;          // 10 * 32 + 9 * 4 = 356
    private static final int[] COLOUR_WIDTH = {46, 46, 58, 66, 54, 66};   // + 5 * 4 = 356
    private static final String[] DIGITS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    private final TextView[][] rows = new TextView[2][];
    private int row;
    private int col;
    private Listener listener;

    public BmlKeyPad(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_frosted_hud);
        setElevation(dp(16));
        setPadding(dp(8), dp(8), dp(8), dp(8));
        setVisibility(GONE);

        // Row 1: DATA, the four ARIB colour keys, BACK.
        LinearLayout actionRow = row(0);
        rows[0] = new TextView[COLOURS.length + 2];
        rows[0][0] = chip("DATA", 0xFF2A333D, 0xFFFFC857, 10);
        rows[0][0].setContentDescription("Data broadcast");
        rows[0][0].setOnClickListener(v -> {
            if (listener != null) listener.onData();
        });
        actionRow.addView(rows[0][0], chipParams(COLOUR_WIDTH[0], false));

        for (int i = 0; i < COLOURS.length; i++) {
            final int code = COLOUR_CODES[i];
            boolean yellow = i == 2;
            rows[0][i + 1] = chip(COLOURS[i], COLOUR_FILL[i],
                    yellow ? 0xFF1A1200 : Color.WHITE, 10);
            rows[0][i + 1].setOnClickListener(v -> send(code));
            actionRow.addView(rows[0][i + 1], chipParams(COLOUR_WIDTH[i + 1], false));
        }

        rows[0][COLOURS.length + 1] = chip("BACK", 0xFF2A333D, Color.WHITE, 10);
        rows[0][COLOURS.length + 1].setOnClickListener(v -> send(BmlController.BACK));
        actionRow.addView(rows[0][COLOURS.length + 1],
                chipParams(COLOUR_WIDTH[COLOURS.length + 1], true));

        // Row 2: the digits.
        LinearLayout digitRow = row(8);
        rows[1] = new TextView[DIGITS.length];
        for (int i = 0; i < DIGITS.length; i++) {
            final int code = BmlController.DIGIT0 + (i == DIGITS.length - 1 ? 0 : i + 1);
            rows[1][i] = chip(DIGITS[i], 0xFF232B35, Color.WHITE, 11);
            rows[1][i].setOnClickListener(v -> send(code));
            digitRow.addView(rows[1][i], chipParams(DIGIT_WIDTH, i == DIGITS.length - 1));
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void show() {
        row = 0;
        col = 1;   // start on RED, not on DATA: OK must not close the page straight away
        setVisibility(VISIBLE);
        post(() -> rows[0][col].requestFocus());
    }

    public void hide() {
        setVisibility(GONE);
        clearFocus();
    }

    /** LEFT/RIGHT within the row, UP/DOWN between rows; the column is clamped. */
    public void moveFocus(int dx, int dy) {
        if (dy != 0) {
            row = Math.max(0, Math.min(rows.length - 1, row + dy));
            col = Math.min(col, rows[row].length - 1);
        }
        if (dx != 0) {
            col = Math.max(0, Math.min(rows[row].length - 1, col + dx));
        }
        rows[row][col].requestFocus();
    }

    public void sendFocused() {
        rows[row][col].performClick();
    }

    private void send(int code) {
        if (listener != null) listener.onBmlKey(code);
    }

    private LinearLayout row(int topMarginDp) {
        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        addView(line, lp);
        return line;
    }

    private TextView chip(String label, int fill, int textColour, int textSize) {
        TextView chip = new TextView(getContext());
        chip.setText(label);
        chip.setTextSize(textSize);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setLetterSpacing(0.04f);
        chip.setTextColor(textColour);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(chipBackground(fill));
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setContentDescription(label + " key");
        return chip;
    }

    private LayoutParams chipParams(int widthDp, boolean lastInRow) {
        LayoutParams lp = new LayoutParams(dp(widthDp), dp(34));
        if (!lastInRow) lp.setMarginEnd(dp(GAP));
        return lp;
    }

    private StateListDrawable chipBackground(int colour) {
        GradientDrawable normal = rounded(colour, 0);
        GradientDrawable pressed = rounded(colour, dp(2));
        GradientDrawable focused = rounded(colour, dp(2));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {android.R.attr.state_pressed}, pressed);
        states.addState(new int[] {}, normal);
        return states;
    }

    private GradientDrawable rounded(int colour, int strokeWidth) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(8));
        shape.setColor(colour);
        if (strokeWidth > 0) shape.setStroke(strokeWidth, Color.WHITE);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

