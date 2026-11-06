package com.pbcnx.opendtvmod.ui.watch;

import androidx.annotation.Nullable;
import com.pbcnx.opendtvmod.ui.Dims;
import com.pbcnx.opendtvmod.ui.SettingRow;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

import com.pbcnx.opendtvmod.R;

public class SettingAdapter extends RecyclerView.Adapter<SettingAdapter.ViewHolder> {

    /** State for a switch row. */
    public interface SwitchState {
        boolean isOn();
    }

    /** Applies a switch row change. */
    public interface SwitchAction {
        void set(boolean on);
    }

    /** Whether a row is usable right now. Read on every bind, so a gate can disable rows live. */
    public interface EnabledState {
        boolean isEnabled();
    }

    /**
     * A row's description as it stands right now, for a row whose text depends on state the viewer
     * has just changed (the Decoder rows: Auto and Manual say different things, and the Auto line
     * names what this multiplex learned). Read on every bind, like {@link EnabledState}: a static
     * String would go stale the moment a switch is touched, because the list is built once.
     */
    public interface DescriptionState {
        @Nullable String text();
    }

    /**
     * One setting row. Each kind carries only what it uses - the old version was a nine-field union
     * with eight factories whose extra arguments were always null, and the adapter had to know which
     * fields were meaningful for which kind.
     */
    public abstract static class SettingItem {
        public final String title;

        SettingItem(String title) {
            this.title = title;
        }

        /** The line the row shows right now. */
        public abstract String description();

        /** True for a non-focusable section label. */
        public boolean isHeader() {
            return false;
        }

        /** The row's icon, 0 for none. */
        public int iconRes() {
            return 0;
        }

        /** The door action, null on a switch row. */
        @Nullable public Runnable action() {
            return null;
        }

        @Nullable public SwitchState switchState() {
            return null;
        }

        @Nullable public SwitchAction switchAction() {
            return null;
        }

        /** Null means always enabled (the Manual gate supplies one). */
        @Nullable public EnabledState enabledState() {
            return null;
        }

        // ---------------------------------------------------------------- factories

        /** A non-focusable section label ("CHANNELS & ALERTS"). */
        public static SettingItem header(String title) {
            return new Header(title, 0);
        }

        /** A section label with its own icon, for a section a viewer asks about by name. */
        public static SettingItem header(String title, int iconRes) {
            return new Header(title, iconRes);
        }

        /** A door row: it opens something. */
        public static SettingItem action(String title, String description, int iconRes, Runnable action) {
            return new Action(title, description, iconRes, action);
        }

        /** A row whose state is shown by a switch at the end; the row itself toggles it. */
        public static SettingItem switchItem(String title, String description, int iconRes,
                                             SwitchState state, SwitchAction action) {
            return new SwitchRow(title, description, iconRes, state, action, null, null);
        }

        /** The same, but the row is only usable when {@code enabled} says so (the Manual gate). */
        public static SettingItem switchItem(String title, String description, int iconRes,
                                             SwitchState state, SwitchAction action,
                                             EnabledState enabled) {
            return new SwitchRow(title, description, iconRes, state, action, enabled, null);
        }

        /**
         * A switch row whose description is computed per bind, for the rows whose text follows the
         * gate (Manual/Video/Audio).
         */
        public static SettingItem liveSwitchItem(String title, DescriptionState description, int iconRes,
                                                 SwitchState state, SwitchAction action) {
            return new SwitchRow(title, "", iconRes, state, action, null, description);
        }

        /** The same with a gate: disabled while {@code enabled} says so, and readable at the same time. */
        public static SettingItem liveSwitchItem(String title, DescriptionState description, int iconRes,
                                                 SwitchState state, SwitchAction action,
                                                 EnabledState enabled) {
            return new SwitchRow(title, "", iconRes, state, action, enabled, description);
        }
    }

    private static final class Header extends SettingItem {
        private final int iconRes;

        Header(String title, int iconRes) {
            super(title);
            this.iconRes = iconRes;
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public boolean isHeader() {
            return true;
        }

        @Override
        public int iconRes() {
            return iconRes;
        }
    }

    private static final class Action extends SettingItem {
        private final String description;
        private final int iconRes;
        private final Runnable action;

        Action(String title, String description, int iconRes, Runnable action) {
            super(title);
            this.description = description;
            this.iconRes = iconRes;
            this.action = action;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public int iconRes() {
            return iconRes;
        }

        @Override
        public Runnable action() {
            return action;
        }
    }

    private static final class SwitchRow extends SettingItem {
        private final String description;
        private final int iconRes;
        private final SwitchState state;
        private final SwitchAction switchAction;
        private final EnabledState enabled;
        private final DescriptionState liveDescription;

        SwitchRow(String title, String description, int iconRes, SwitchState state,
                  SwitchAction switchAction, EnabledState enabled, DescriptionState liveDescription) {
            super(title);
            this.description = description;
            this.iconRes = iconRes;
            this.state = state;
            this.switchAction = switchAction;
            this.enabled = enabled;
            this.liveDescription = liveDescription;
        }

        @Override
        public String description() {
            return liveDescription != null ? liveDescription.text() : description;
        }

        @Override
        public int iconRes() {
            return iconRes;
        }

        @Override
        public SwitchState switchState() {
            return state;
        }

        @Override
        public SwitchAction switchAction() {
            return switchAction;
        }

        @Override
        public EnabledState enabledState() {
            return enabled;
        }
    }

    private final Context context;
    private final List<SettingItem> items;

    public SettingAdapter(Context context, List<SettingItem> items) {
        this.context = context;
        this.items = items;
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SETTING = 1;



    @Override
    public int getItemViewType(int position) {
        return items.get(position).isHeader() ? TYPE_HEADER : TYPE_SETTING;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_HEADER
                ? R.layout.item_setting_header : R.layout.item_setting;
        View view = LayoutInflater.from(context).inflate(layout, parent, false);
        ViewHolder holder = new ViewHolder(view);
        // A plate per view, never shared: a StateListDrawable carries one state, so a
        // shared instance puts one row's pressed state on every row that shares it -
        // the two middle rows of a section lit up together. Each row gets its own.
        if (viewType == TYPE_SETTING) holder.plates = buildPlates();
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SettingItem item = items.get(position);
        SettingRow.bind(holder.itemView, item.title, item.description(), item.iconRes());

        if (item.isHeader()) {
            holder.itemView.setClickable(false);
            holder.itemView.setFocusable(false);
            if (holder.ivIcon != null) {
                if (item.iconRes() != 0) {
                    holder.ivIcon.setImageResource(item.iconRes());
                    holder.ivIcon.setVisibility(View.VISIBLE);
                } else {
                    holder.ivIcon.setVisibility(View.GONE);
                }
            }
            return;
        }

        // Rows share one card per section: round only the section's own ends.
        boolean firstRow = position == 0 || items.get(position - 1).isHeader();
        boolean lastRow = position == items.size() - 1 || items.get(position + 1).isHeader();
        if (holder.plates != null) {
            holder.itemView.setBackground(
                    firstRow && lastRow ? holder.plates[0]
                            : firstRow ? holder.plates[1]
                            : lastRow ? holder.plates[2]
                            : holder.plates[3]);
        }

        boolean enabled = item.enabledState() == null || item.enabledState().isEnabled();
        holder.itemView.setAlpha(enabled ? 1f : SettingRow.DISABLED_ALPHA);
        holder.itemView.setClickable(enabled);
        holder.itemView.setFocusable(enabled);
        holder.itemView.setEnabled(enabled);

        // A door row gets a chevron; a state row keeps its switch and no chevron.
        if (holder.ivChevron != null) {
            holder.ivChevron.setVisibility(
                    item.switchState() == null && item.action() != null ? View.VISIBLE : View.GONE);
        }

        if (holder.switchSetting != null && item.switchState() != null) {
            holder.switchSetting.setVisibility(View.VISIBLE);
            holder.switchSetting.setEnabled(enabled);
            holder.switchSetting.setOnCheckedChangeListener(null);
            holder.switchSetting.setChecked(item.switchState().isOn());
            holder.switchSetting.setOnCheckedChangeListener((button, checked) -> {
                if (item.switchAction() != null) item.switchAction().set(checked);
            });
            if (enabled) {
                holder.itemView.setOnClickListener(v -> holder.switchSetting.toggle());
            } else {
                holder.itemView.setOnClickListener(null);
            }
        } else {
            if (holder.switchSetting != null) holder.switchSetting.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(enabled ? v -> {
                if (item.action() != null) {
                    item.action().run();
                }
            } : null);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvTitle;
        TextView tvDesc;
        ImageView ivChevron;
        MaterialSwitch switchSetting;
        /** This row's own plates: single / first / last / middle. Never shared with another row. */
        Drawable[] plates;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_setting_icon);
            tvTitle = itemView.findViewById(R.id.tv_setting_title);
            tvDesc = itemView.findViewById(R.id.tv_setting_desc);
            ivChevron = itemView.findViewById(R.id.iv_setting_chevron);
            switchSetting = itemView.findViewById(R.id.switch_setting);
        }
    }

    // ------------------------------------------------------------------ section plates

    /**
     * Four plates per row view: a section reads as one card, and only its first and
     * last rows round. Built in code because the individual corner radii in a shape
     * drawable need API 24 and this app starts at 21. One set per view, so no row can
     * inherit another row's state.
     */
    private Drawable[] buildPlates() {
        int radius = context.getResources().getDimensionPixelSize(R.dimen.odtv_row_radius);
        return new Drawable[]{
                rowPlate(radius, radius), // single
                rowPlate(radius, 0),      // first
                rowPlate(0, radius),      // last
                rowPlate(0, 0)};          // middle
    }

    private Drawable rowPlate(int topRadius, int bottomRadius) {
        float[] radii = {
                topRadius, topRadius,
                topRadius, topRadius,
                bottomRadius, bottomRadius,
                bottomRadius, bottomRadius};
        int raised = ContextCompat.getColor(context, R.color.odtv_surface_high);
        int focus = ContextCompat.getColor(context, R.color.odtv_focus);
        int edge = ContextCompat.getColor(context, R.color.odtv_on_surface);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                highlightPlate(radii, raised, focus, 2));
        states.addState(new int[]{android.R.attr.state_pressed},
                highlightPlate(radii, raised, edge, 1));
        states.addState(new int[]{},
                plate(radii, Color.argb(0x0F, 0xFF, 0xFF, 0xFF),
                        Color.argb(0x14, 0xFF, 0xFF, 0xFF), 1));
        return states;
    }

    /**
     * A focused row fills with the raised surface and shows a rounded ring INSIDE the
     * row, not around it. A middle row of a section is square-edged by design, and a
     * ring that followed that edge drew a hard box across the shared card - the odd
     * highlight on "Manual Frequency Scan" and "EWBS Location". Inset and uniformly
     * rounded, every row in a section highlights the same way.
     */
    private Drawable highlightPlate(float[] radii, int fillColor, int ringColor, int ringDp) {
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.RECTANGLE);
        fill.setColor(fillColor);
        fill.setCornerRadii(radii);

        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(Dims.dp(context, 9));
        ring.setStroke(Math.max(1, Dims.dp(context, ringDp)), ringColor);

        int inset = Dims.dp(context, 4);
        return new LayerDrawable(new Drawable[]{
                fill, new InsetDrawable(ring, inset, inset, inset, inset)});
    }


    private GradientDrawable plate(float[] radii, int fill, int stroke, int strokeDp) {
        GradientDrawable plate = new GradientDrawable();
        plate.setShape(GradientDrawable.RECTANGLE);
        plate.setColor(fill);
        plate.setCornerRadii(radii);
        plate.setStroke(Math.max(1,
                Math.round(strokeDp * context.getResources().getDisplayMetrics().density)), stroke);
        return plate;
    }
}
