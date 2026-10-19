package ph.opendtv.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Set;

import ph.opendtv.R;
import ph.opendtv.ts.ServiceInfo;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(ServiceInfo service);
        void onFavoriteToggle(ServiceInfo service, boolean isFavorite);
    }

    private final Context context;
    private final List<ServiceInfo> channels;
    private final Set<Integer> favorites;
    private final OnChannelClickListener listener;

    public ChannelAdapter(Context context, List<ServiceInfo> channels, Set<Integer> favorites, OnChannelClickListener listener) {
        this.context = context;
        this.channels = channels;
        this.favorites = favorites;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_channel, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ServiceInfo channel = channels.get(position);
        holder.tvVirtual.setText(channel.virtualNumber);
        holder.tvChannelName.setText(channel.name.isEmpty() ? "Service " + channel.serviceId : channel.name);
        holder.tvNetworkName.setText(channel.provider.isEmpty() ? "ISDB-T Broadcast" : channel.provider);

        Drawable logo = LogoHelper.getLogo(context, channel.name, channel.provider);
        if (logo != null) {
            holder.ivLogo.setImageDrawable(logo);
        } else {
            holder.ivLogo.setImageResource(android.R.drawable.ic_menu_slideshow);
        }

        boolean isFav = favorites.contains(channel.serviceId);
        holder.ivFavorite.setImageResource(isFav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChannelClick(channel);
        });

        holder.itemView.setOnLongClickListener(v -> {
            boolean newState = !favorites.contains(channel.serviceId);
            if (newState) {
                favorites.add(channel.serviceId);
            } else {
                favorites.remove(channel.serviceId);
            }
            notifyItemChanged(position);
            if (listener != null) listener.onFavoriteToggle(channel, newState);
            return true;
        });

        holder.ivFavorite.setOnClickListener(v -> {
            boolean newState = !favorites.contains(channel.serviceId);
            if (newState) {
                favorites.add(channel.serviceId);
            } else {
                favorites.remove(channel.serviceId);
            }
            notifyItemChanged(position);
            if (listener != null) listener.onFavoriteToggle(channel, newState);
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivLogo;
        TextView tvVirtual;
        TextView tvChannelName;
        TextView tvNetworkName;
        ImageView ivFavorite;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivLogo = itemView.findViewById(R.id.iv_logo);
            tvVirtual = itemView.findViewById(R.id.tv_virtual);
            tvChannelName = itemView.findViewById(R.id.tv_channel_name);
            tvNetworkName = itemView.findViewById(R.id.tv_network_name);
            ivFavorite = itemView.findViewById(R.id.iv_favorite);
        }
    }
}
