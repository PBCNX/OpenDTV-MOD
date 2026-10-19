package ph.opendtv.bml;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** On-screen remote control: DATA, arrows + OK, BACK, the four colour keys and the number keys. */
public final class RemotePanel extends LinearLayout {
    public interface Listener {
        void onKey(int aribKeyCode);
        void onExit();
    }

    private final Listener listener;

    public RemotePanel(Context c, Listener l) {
        super(c);
        this.listener = l;
        setOrientation(VERTICAL);
        int pad = dp(8);
        setPadding(pad, pad, pad, pad);

        LinearLayout top = row();
        top.addView(btn("DATA", 0xFF1565C0, BmlController.DATA, 2f));
        top.addView(btn("BACK", 0, BmlController.BACK, 1f));
        Button exit = btn("EXIT BML", 0xFF8E2424, -1, 1f);
        exit.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { listener.onExit(); }
        });
        top.addView(exit);
        addView(top);

        LinearLayout up = row();
        up.addView(space());
        up.addView(btn("▲", 0, BmlController.UP, 1f));
        up.addView(space());
        addView(up);
        LinearLayout mid = row();
        mid.addView(btn("◀", 0, BmlController.LEFT, 1f));
        mid.addView(btn("OK", 0, BmlController.ENTER, 1f));
        mid.addView(btn("▶", 0, BmlController.RIGHT, 1f));
        addView(mid);
        LinearLayout down = row();
        down.addView(space());
        down.addView(btn("▼", 0, BmlController.DOWN, 1f));
        down.addView(space());
        addView(down);

        LinearLayout colors = row();
        colors.addView(btn("Blue", 0xFF1E5BD8, BmlController.BLUE, 1f));
        colors.addView(btn("Red", 0xFFC62828, BmlController.RED, 1f));
        colors.addView(btn("Green", 0xFF2E7D32, BmlController.GREEN, 1f));
        colors.addView(btn("Yellow", 0xFFF9A825, BmlController.YELLOW, 1f));
        addView(colors);

        int[][] keys = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};
        for (int[] r : keys) {
            LinearLayout row = row();
            for (int n : r) row.addView(btn(String.valueOf(n), 0, BmlController.DIGIT0 + n, 1f));
            addView(row);
        }
        LinearLayout last = row();
        last.addView(btn("10", 0, BmlController.DIGIT0 + 10, 1f));
        last.addView(btn("0", 0, BmlController.DIGIT0, 1f));
        last.addView(btn("11", 0, BmlController.DIGIT0 + 11, 1f));
        addView(last);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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

    private Button btn(String text, int color, final int code, float weight) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        if (color != 0) {
            b.setBackgroundColor(color);
            b.setTextColor(color == 0xFFF9A825 ? Color.BLACK : Color.WHITE);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        if (code >= 0) {
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { listener.onKey(code); }
            });
        }
        return b;
    }
}
