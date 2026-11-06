package com.pbcnx.opendtvmod.analyzer;

import com.pbcnx.opendtvmod.ui.Dims;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.pbcnx.opendtvmod.R;


/** The nine tab labels, the underline that follows them and the swipe between panels. */
public final class TabStrip implements TabPanel {
    private android.view.View previewBox;
    private android.view.GestureDetector tabGestures;
    private final android.graphics.Rect hitRect = new android.graphics.Rect();
    private boolean touchInContent;
    private int lastIndicatorTab = -1;
    private int lastPreviewHeight = -1;
    private android.view.View tabIndicator;


    private final AnalyzerActivity a;
    private final AnalyzerState state;
    private final StatusSink status;

    public TabStrip(AnalyzerActivity activity, AnalyzerState state, StatusSink status) {
        this.a = activity;
        this.state = state;
        this.status = status;
    }

    public void bindViews() {
        tabIndicator = a.findViewById(R.id.tab_indicator);
    }

    /** Wires the nine labels; the activity calls this once, after the views exist. */
    public void bindTabs() {
        bindTab(R.id.tab_signal, AnalyzerState.TAB_SIGNAL);
        bindTab(R.id.tab_services, AnalyzerState.TAB_SERVICES);
        bindTab(R.id.tab_pids, AnalyzerState.TAB_PIDS);
        bindTab(R.id.tab_tables, AnalyzerState.TAB_TABLES);
        bindTab(R.id.tab_epg, AnalyzerState.TAB_EPG);
        bindTab(R.id.tab_datacast, AnalyzerState.TAB_DATACAST);
        bindTab(R.id.tab_graph, AnalyzerState.TAB_GRAPH);
        bindTab(R.id.tab_logs, AnalyzerState.TAB_LOGS);
        bindTab(R.id.tab_remote, AnalyzerState.TAB_REMOTE);
    }

    private void bindTab(int id, final int tab) {
        a.findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(tab, tab > state.currentTab ? 1 : -1);
            }
        });
    }

    /**
     * Portrait preview: 16:9 of the card width (the shell gives the box a fallback
     * height). In landscape the preview is a weighted column, so leave it alone.
     */
    public void sizePreview() {
        previewBox = a.findViewById(R.id.preview_box);
        if (previewBox == null) return;
        if (a.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            int width = a.getResources().getDisplayMetrics().widthPixels - Dims.dp(a, 32);
            ViewGroup.LayoutParams lp = previewBox.getLayoutParams();
            lp.height = Math.round(width * 9f / 16f);
            previewBox.setLayoutParams(lp);
            return;
        }
        // Landscape: give the column the width of a 16:9 picture at the row's height, so
        // a 16:9 service fills the frame instead of letterboxing inside a taller box.
        // A listener, not a one-shot post: the row's height is not final on the first
        // pass, and reading it too early left the phone's column 24 dp narrow.
        final View row = (View) previewBox.getParent();
        if (row == null) return;
        row.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int oldL, int oldT, int oldR, int oldB) {
                fitPreviewAspect(v);
            }
        });
        previewBox.post(new Runnable() {
            @Override
            public void run() {
                View r = (View) previewBox.getParent();
                if (r != null) fitPreviewAspect(r);
            }
        });
    }

    /** Sets the landscape preview column to a 16:9 box for the row's current height. */
    public void fitPreviewAspect(View row) {
        int height = row.getHeight();
        if (height <= 0 || height == lastPreviewHeight) return;
        lastPreviewHeight = height;
        int width = Math.round(height * 16f / 9f);
        // 16:9, but never at the cost of a card narrower than the PID table needs.
        int cap = row.getWidth() - Dims.dp(a, 360) - Dims.dp(a, 12);
        if (cap > 0) width = Math.min(width, cap);
        ViewGroup.LayoutParams lp = previewBox.getLayoutParams();
        if (lp.width == width) return;
        lp.width = width;
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).weight = 0f;
        }
        previewBox.setLayoutParams(lp);
    }

    public void setUpTabGestures() {
        tabGestures = new android.view.GestureDetector(a,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float vx, float vy) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        // Horizontal, deliberate: a vertical scroll must stay a scroll.
                        if (Math.abs(dx) < Dims.dp(a, 56) || Math.abs(dx) < Math.abs(dy) * 1.6f) return false;
                        boolean previous = dx > 0;
                        return switchTab(previous ? state.currentTab - 1 : state.currentTab + 1, previous ? -1 : 1);
                    }
                });
    }

    /** @param dir -1 slides the new panel in from the left, +1 from the right. */
    public boolean switchTab(int tab, int dir) {
        if (tab < AnalyzerState.TAB_SIGNAL || tab > AnalyzerState.TAB_REMOTE || tab == state.currentTab) return false;
        state.currentTab = tab;
        updateTabVisuals();
        state.refresh.run();
        // Opening the Logs tab is the moment the reader wants the caption line to be current, so it
        // is written once on the switch instead of being throttled like the periodic call.
        if (tab == AnalyzerState.TAB_LOGS) status.logCaptionProbe(true);
        animateContent(dir);
        return true;
    }

    private void animateContent(int dir) {
        View panel = visibleContentPanel();
        if (panel == null) return;
        float shift = Math.min(Dims.dp(a, 64), a.getResources().getDisplayMetrics().widthPixels * 0.14f);
        panel.animate().cancel();
        panel.setAlpha(0f);
        panel.setTranslationX(dir * shift);
        panel.animate().alpha(1f).translationX(0f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** The card's visible child is the current tab: last one wins (the blocking card is last). */
    private View visibleContentPanel() {
        if (state.contentFrame == null) return null;
        for (int i = state.contentFrame.getChildCount() - 1; i >= 0; i--) {
            View child = state.contentFrame.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) return child;
        }
        return null;
    }

    /** Nine tabs: gold label for the current one, and one indicator that follows it. */
    @Override
    public void update() {
        updateTabVisuals();
    }

    public void updateTabVisuals() {
        for (int i = 0; i < AnalyzerState.TAB_LABEL_IDS.length; i++) {
            TextView label = a.findViewById(AnalyzerState.TAB_LABEL_IDS[i]);
            label.setTextColor(ContextCompat.getColor(a,
                    i == state.currentTab ? R.color.odtv_accent : R.color.odtv_on_surface_variant));
        }
        moveTabIndicator(lastIndicatorTab >= 0 && lastIndicatorTab != state.currentTab);
        lastIndicatorTab = state.currentTab;
        keepSelectedTabVisible();
    }

    /**
     * The nine tabs do not fit a phone, so the row can be scrolled. If the gold tab
     * is not completely in view, nudge it back by the smallest amount that shows it -
     * never re-centre, or the row would jump on every selection. Testing the edges
     * (not "entirely out of view") matters after a rotation, when the restored scroll
     * position can leave the current tab half clipped, which reads as "nothing selected".
     */
    public void keepSelectedTabVisible() {
        final TextView label = a.findViewById(AnalyzerState.TAB_LABEL_IDS[state.currentTab]);
        final android.widget.HorizontalScrollView row = a.findViewById(R.id.analyzer_tab_scroll);
        if (label == null || row == null || label.getWidth() == 0) return;
        int left = label.getLeft();
        int right = left + label.getWidth();
        int viewLeft = row.getScrollX();
        int viewRight = viewLeft + row.getWidth();
        int pad = Dims.dp(a, 8);
        if (left < viewLeft) {
            row.scrollTo(Math.max(0, left - pad), 0);
        } else if (right > viewRight) {
            row.scrollTo(Math.max(0, right - row.getWidth() + pad), 0);
        }
    }

    /**
     * Slides the underline to the current tab: same x/width animation the watch
     * tabs have. Width cannot go through ViewPropertyAnimator, hence the
     * ValueAnimator setting the layout params each frame.
     */
    private void moveTabIndicator(final boolean animate) {
        if (tabIndicator == null) return;
        final View label = a.findViewById(AnalyzerState.TAB_LABEL_IDS[state.currentTab]);
        if (label == null) return;
        if (label.getWidth() == 0) {
            tabIndicator.post(new Runnable() {
                @Override
                public void run() { moveTabIndicator(false); }
            });
            return;
        }
        final android.view.ViewGroup.LayoutParams lp = tabIndicator.getLayoutParams();
        final int fromX = Math.round(tabIndicator.getTranslationX());
        final int fromW = lp.width;
        final int toX = label.getLeft();
        final int toW = label.getWidth();
        if (!animate || fromW <= 0) {
            lp.width = toW;
            tabIndicator.setLayoutParams(lp);
            tabIndicator.setTranslationX(toX);
            return;
        }
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            lp.width = Math.round(fromW + (toW - fromW) * f);
            tabIndicator.setLayoutParams(lp);
            tabIndicator.setTranslationX(fromX + (toX - fromX) * f);
        });
        anim.start();
    }

    /** Swipe left/right anywhere in the tab content. Only touch is claimed; the D-pad is untouched. */
    public void handleTouch(android.view.MotionEvent e) {
        if (tabGestures != null) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                touchInContent = state.contentFrame != null && state.contentFrame.getGlobalVisibleRect(hitRect)
                        && hitRect.contains((int) e.getX(), (int) e.getY());
            }
            if (touchInContent) tabGestures.onTouchEvent(e);
        }
    }
}
