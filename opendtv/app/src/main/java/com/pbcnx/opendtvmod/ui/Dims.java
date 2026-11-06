package com.pbcnx.opendtvmod.ui;

import android.content.Context;

/**
 * One density conversion for the whole UI. Every screen used to carry its own private {@code dp()},
 * eight of them the same one-liner and one of them truncating instead of rounding - which is how a
 * card could come out a pixel short on exactly one screen.
 */
public final class Dims {

    private Dims() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
