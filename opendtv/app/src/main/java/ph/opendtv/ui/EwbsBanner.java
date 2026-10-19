package ph.opendtv.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import ph.opendtv.ts.TsDemux;

/**
 * Red EWBS (emergency warning broadcast) strip drawn over the top of an activity.
 * Added to android.R.id.content, so it needs no layout changes and works in every layout variant;
 * it re-attaches itself after setContentView() replaced the content (rotation re-inflation).
 *
 * Active signal  : red, "EWBS ACTIVE" + what signals it.
 * Ended signal   : dark amber, "EWBS was signalled" (so a finished recording still shows it).
 * No signal      : hidden.
 */
public final class EwbsBanner {
    private final Activity act;
    private TextView view;

    public EwbsBanner(Activity act) {
        this.act = act;
    }

    /** Call periodically on the UI thread. {@code d} may be null. */
    public void update(TsDemux d) {
        boolean active = d != null && d.isEwbsActive();
        boolean past = !active && d != null && d.ewbsEverSeen();
        if (!active && !past) {
            if (view != null) view.setVisibility(View.GONE);
            return;
        }
        ensureAttached();
        if (view == null) return;
        if (active) {
            String why = d.ewbsBannerText();
            view.setBackgroundColor(Color.argb(235, 200, 0, 0));
            view.setText("EWBS ACTIVE" + (why.isEmpty() ? "" : "  (" + why + ")"));
        } else {
            view.setBackgroundColor(Color.argb(220, 120, 80, 0));
            view.setText("EWBS was signalled in this stream (see Analyzer log)");
        }
        view.setVisibility(View.VISIBLE);
        view.bringToFront();
    }

    private void ensureAttached() {
        ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null) return;
        if (view == null) {
            view = new TextView(act);
            view.setTextColor(Color.WHITE);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setTextSize(14);
            view.setGravity(Gravity.CENTER);
            int pad = (int) (6 * act.getResources().getDisplayMetrics().density);
            view.setPadding(pad * 2, pad, pad * 2, pad);
            view.setElevation(100f * act.getResources().getDisplayMetrics().density);
            view.setClickable(false);
            view.setFocusable(false);
        }
        if (view.getParent() != content) {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        }
    }
}
