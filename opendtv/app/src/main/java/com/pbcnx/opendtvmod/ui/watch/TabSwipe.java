package com.pbcnx.opendtvmod.ui.watch;

/** The horizontal tab-swipe gesture: claim a clear drag, step one tab on release. */
public final class TabSwipe {

    private float startX;
    private float startY;
    private boolean claimed;

    public void down(float x, float y) {
        startX = x;
        startY = y;
        claimed = false;
    }

    /** True once the drag is claimed (the row no longer sees it). */
    public boolean move(float x, float y, float slop) {
        if (!claimed) {
            float dx = x - startX;
            float dy = y - startY;
            claimed = Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f;
        }
        return claimed;
    }

    /** On release: 0 = no step, +1 = next tab (swipe left), -1 = previous tab. Resets the claim. */
    public int up(float x, float y, float min) {
        int step = 0;
        if (claimed) {
            float dx = x - startX;
            float dy = y - startY;
            if (Math.abs(dx) > min && Math.abs(dx) > Math.abs(dy) * 1.5f) step = dx < 0 ? 1 : -1;
        }
        claimed = false;
        return step;
    }

    public void cancel() {
        claimed = false;
    }

    public boolean claimed() {
        return claimed;
    }
}
