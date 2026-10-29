package ph.opendtv.ui;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ph.opendtv.ts.TsDemux;

/**
 * EWBS banner: a red strip with mirrored warning icons and centred white text,
 * based on TVTest's notification-bar layout. Added to android.R.id.content, so it
 * needs no layout changes and works in every layout variant; it re-attaches itself
 * after setContentView() replaced the content (rotation re-inflation).
 */
public final class EwbsBanner {
    private static final int WARNING_COLOR = 0xFFFFFFFF;
    private static final int ICON_COLOR = 0xFFFFFFFF;
    private static final int INFO_COLOR = 0xFFBBBBBB;

    private final Activity act;
    private LinearLayout view;
    private TextView text;
    private ImageView leftIcon;
    private ImageView rightIcon;

    public EwbsBanner(Activity act) {
        this.act = act;
    }

    /** Told when the strip appears or goes away, so the host can inset its own chrome. */
    public interface Host {
        void onEwbsBannerVisible(boolean visible);
    }

    private Host host;
    private boolean hostNotified;

    public void setHost(Host h) {
        host = h;
    }

    /** The banner floats over the content; a host that owns chrome should make room for it. */
    public void notifyHost(boolean visible) {
        if (host == null || hostNotified == visible) return;
        hostNotified = visible;
        host.onEwbsBannerVisible(visible);
    }

    /** Call periodically on the UI thread. {@code d} may be null. */
    public void update(TsDemux d) {
        update(d, 0);
    }

    /** @param location viewer's Philippine EWBS area code, 0 = show all */
    public void update(TsDemux d, int location) {
        boolean active = d != null && d.isEwbsActive(location);
        boolean past = !active && d != null && location == 0 && d.ewbsEverSeen();
        if (!active && !past) {
            if (view != null) view.setVisibility(View.GONE);
            notifyHost(false);
            return;
        }
        ensureAttached();
        if (view == null || text == null) return;
        notifyHost(true);
        if (active) {
            String why = d.ewbsBannerText(location);
            text.setText("Emergency Warning:" + (why.isEmpty() ? "" : " " + why));
            text.setTextColor(WARNING_COLOR);
            view.setBackground(activeBackground());
            leftIcon.setVisibility(View.VISIBLE);
            rightIcon.setVisibility(View.VISIBLE);
        } else {
            text.setText("Emergency warning has ended");
            text.setTextColor(INFO_COLOR);
            view.setBackground(endedBackground());
            leftIcon.setVisibility(View.GONE);
            rightIcon.setVisibility(View.GONE);
        }
        view.setVisibility(View.VISIBLE);
        view.bringToFront();
    }

    private GradientDrawable activeBackground() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFFB71C1C, 0xFFD32F2F});
        d.setCornerRadius(0);
        return d;
    }

    private GradientDrawable endedBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xF2222222);
        d.setCornerRadius(0);
        return d;
    }

    private void ensureAttached() {
        ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null) return;
        if (view == null) {
            int dp = (int) act.getResources().getDisplayMetrics().density;

            view = new LinearLayout(act);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(48 * dp);
            view.setPadding(10 * dp, 6 * dp, 10 * dp, 6 * dp);
            view.setElevation(100f * dp);
            view.setClickable(false);
            view.setFocusable(false);

            leftIcon = warningIcon();
            rightIcon = warningIcon();
            text = new TextView(act);
            text.setTextSize(15);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setGravity(Gravity.CENTER);
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPadding(8 * dp, 0, 8 * dp, 0);

            view.addView(leftIcon, new LinearLayout.LayoutParams(22 * dp, 22 * dp));
            view.addView(text, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            view.addView(rightIcon, new LinearLayout.LayoutParams(22 * dp, 22 * dp));
        }
        if (view.getParent() != content) {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        }
    }

    private ImageView warningIcon() {
        ImageView icon = new ImageView(act);
        icon.setImageResource(android.R.drawable.ic_dialog_alert);
        icon.setColorFilter(new PorterDuffColorFilter(ICON_COLOR, PorterDuff.Mode.SRC_IN));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(0, 0, 0, 0);
        return icon;
    }
}
