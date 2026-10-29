package ph.opendtv.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

import ph.opendtv.R;

public class SettingAdapter extends RecyclerView.Adapter<SettingAdapter.ViewHolder> {

    /** State for a switch row. */
    public interface SwitchState {
        boolean get();
    }

    /** Applies a switch row change. */
    public interface SwitchAction {
        void set(boolean on);
    }

    public static class SettingItem {
        public final String title;
        public final String description;
        public final int iconRes;
        public final Runnable action;
        public final boolean header;
        public final SwitchState switchState;
        public final SwitchAction switchAction;

        public SettingItem(String title, String description, int iconRes, Runnable action) {
            this(title, description, iconRes, action, false, null, null);
        }

        private SettingItem(String title, String description, int iconRes, Runnable action,
                            boolean header, SwitchState switchState, SwitchAction switchAction) {
            this.title = title;
            this.description = description;
            this.iconRes = iconRes;
            this.action = action;
            this.header = header;
            this.switchState = switchState;
            this.switchAction = switchAction;
        }

        /** A non-focusable section label ("CHANNELS & ALERTS"). */
        public static SettingItem header(String title) {
            return new SettingItem(title, "", 0, null, true, null, null);
        }

        /** A row whose state is shown by a switch at the end; the row itself toggles it. */
        public static SettingItem switchItem(String title, String description, int iconRes,
                                             SwitchState state, SwitchAction action) {
            return new SettingItem(title, description, iconRes, null, false, state, action);
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
        return items.get(position).header ? TYPE_HEADER : TYPE_SETTING;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_HEADER
                ? R.layout.item_setting_header : R.layout.item_setting;
        View view = LayoutInflater.from(context).inflate(layout, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SettingItem item = items.get(position);
        holder.tvTitle.setText(item.title);

        if (item.header) {
            holder.itemView.setClickable(false);
            holder.itemView.setFocusable(false);
            return;
        }

        holder.tvDesc.setText(item.description);
        holder.ivIcon.setImageResource(item.iconRes);

        if (holder.switchSetting != null && item.switchState != null) {
            holder.switchSetting.setVisibility(View.VISIBLE);
            holder.switchSetting.setOnCheckedChangeListener(null);
            holder.switchSetting.setChecked(item.switchState.get());
            holder.switchSetting.setOnCheckedChangeListener((button, checked) -> {
                if (item.switchAction != null) item.switchAction.set(checked);
            });
            holder.itemView.setOnClickListener(v -> holder.switchSetting.toggle());
        } else {
            if (holder.switchSetting != null) holder.switchSetting.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(v -> {
                if (item.action != null) {
                    item.action.run();
                }
            });
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
        MaterialSwitch switchSetting;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_setting_icon);
            tvTitle = itemView.findViewById(R.id.tv_setting_title);
            tvDesc = itemView.findViewById(R.id.tv_setting_desc);
            switchSetting = itemView.findViewById(R.id.switch_setting);
        }
    }
}
