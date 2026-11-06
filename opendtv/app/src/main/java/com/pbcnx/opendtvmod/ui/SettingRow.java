package com.pbcnx.opendtvmod.ui;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.pbcnx.opendtvmod.R;

/**
 * The columns every settings row shares ({@code item_setting}): title, description and icon.
 *
 * <p>Two renderers bind that layout - the Settings {@link SettingAdapter} and the Developers screen's
 * hand-built list - and each had its own copy of these three findViewById/set calls plus its own
 * disabled alpha. The parts that differ (switch, chevron, click handling) stay with their owners.</p>
 */
public final class SettingRow {

    /** A gated or handler-less row is dimmed, not hidden: the viewer should see what it would change. */
    public static final float DISABLED_ALPHA = 0.38f;

    private SettingRow() {
    }

    /** Binds the title/description/icon columns; a layout without the icon (a header) is fine. */
    public static void bind(View row, CharSequence title, CharSequence description, int iconRes) {
        TextView tvTitle = row.findViewById(R.id.tv_setting_title);
        if (tvTitle != null) tvTitle.setText(title);
        TextView tvDesc = row.findViewById(R.id.tv_setting_desc);
        if (tvDesc != null) tvDesc.setText(description);
        ImageView icon = row.findViewById(R.id.iv_setting_icon);
        if (icon != null) icon.setImageResource(iconRes);
    }
}
