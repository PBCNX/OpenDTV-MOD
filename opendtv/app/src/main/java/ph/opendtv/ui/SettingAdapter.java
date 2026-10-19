package ph.opendtv.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ph.opendtv.R;

public class SettingAdapter extends RecyclerView.Adapter<SettingAdapter.ViewHolder> {

    public static class SettingItem {
        public final String title;
        public final String description;
        public final int iconRes;
        public final Runnable action;

        public SettingItem(String title, String description, int iconRes, Runnable action) {
            this.title = title;
            this.description = description;
            this.iconRes = iconRes;
            this.action = action;
        }
    }

    private final Context context;
    private final List<SettingItem> items;

    public SettingAdapter(Context context, List<SettingItem> items) {
        this.context = context;
        this.items = items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_setting, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SettingItem item = items.get(position);
        holder.tvTitle.setText(item.title);
        holder.tvDesc.setText(item.description);
        holder.ivIcon.setImageResource(item.iconRes);

        holder.itemView.setOnClickListener(v -> {
            if (item.action != null) {
                item.action.run();
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvTitle;
        TextView tvDesc;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_setting_icon);
            tvTitle = itemView.findViewById(R.id.tv_setting_title);
            tvDesc = itemView.findViewById(R.id.tv_setting_desc);
        }
    }
}
