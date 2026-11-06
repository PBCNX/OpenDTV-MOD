package com.pbcnx.opendtvmod.ui.watch;

import android.content.Context;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.pbcnx.opendtvmod.R;

/** The one chip styling rule: active tint, dimmed inactive alpha and the accessibility wording. */
final class ChipStyle {

    private ChipStyle() {
    }

    static void apply(TextView chip, Context context, boolean active, int activeColorRes,
                      String description) {
        if (chip == null) return;
        int color = ContextCompat.getColor(context,
                active ? activeColorRes : R.color.odtv_on_surface_variant);
        chip.setTextColor(color);
        chip.setAlpha(active ? 1f : 0.28f);
        chip.setContentDescription(context.getString(active ? R.string.cd_chip_active : R.string.cd_chip_inactive,
                description));
    }
}
