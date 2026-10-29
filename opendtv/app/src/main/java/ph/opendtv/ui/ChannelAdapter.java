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

    public interface OnChannelLongClickListener {
        void onLongPress(int position, ServiceInfo channel);
    }

    /**
     * Favourites are keyed by "frequency:service_id", not by service_id alone: three of the
     * channels on this bench share SID 64, so a SID-only set put a star on all of them. The key
     * is also what is stored, so it reads as the channel it means in the prefs file.
     */
    public static String favoriteKey(ServiceInfo channel) {
        return favoriteKey(channel.khz, channel.serviceId);
    }

    public static String favoriteKey(int khz, int serviceId) {
        return khz + ":" + serviceId;
    }

    private final Context context;
    private final List<ServiceInfo> channels;
    private final Set<String> favorites;
    private final OnChannelClickListener listener;
    private final OnChannelLongClickListener longListener;

    public ChannelAdapter(Context context, List<ServiceInfo> channels, Set<String> favorites,
                          OnChannelClickListener listener) {
        this(context, channels, favorites, listener, null);
    }

    public ChannelAdapter(Context context, List<ServiceInfo> channels, Set<String> favorites,
                          OnChannelClickListener listener,
                          OnChannelLongClickListener longListener) {
        this.context = context;
        this.channels = channels;
        this.favorites = favorites;
        this.listener = listener;
        this.longListener = longListener;
    }

    /** Moves a row, used by the favourites reorder (drag or long-press mode). */
    public void moveItem(int from, int to) {
        if (from < 0 || to < 0 || from >= channels.size() || to >= channels.size()) return;
        channels.add(to, channels.remove(from));
        notifyItemMoved(from, to);
    }

    public List<ServiceInfo> items() {
        return channels;
    }

    private ServiceInfo activeService;

    public void setActiveService(ServiceInfo service) {
        if (activeService == service) return;
        activeService = service;
        notifyDataSetChanged();
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
        boolean isActive = channel == activeService;
        holder.itemView.setSelected(isActive);
        holder.tvVirtual.setText(channel.virtualNumber);
        String displayName = displayName(channel);
        holder.tvChannelName.setText(displayName);
        holder.tvNow.setVisibility(isActive ? View.VISIBLE : View.GONE);

        // The provider is only useful when it adds something: hide it when it
        // repeats the name (GMA / GMA) or when there is nothing to show.
        String provider = channel.provider == null ? "" : channel.provider.trim();
        boolean showProvider = !provider.isEmpty() && !provider.equalsIgnoreCase(displayName.trim());
        holder.tvNetworkName.setText(provider);
        holder.tvNetworkName.setVisibility(showProvider ? View.VISIBLE : View.GONE);

        Drawable logo = LogoHelper.getLogo(context, channel.name, channel.provider);
        if (logo != null) {
            holder.ivLogo.setImageDrawable(logo);
        } else {
            holder.ivLogo.setImageResource(android.R.drawable.ic_menu_slideshow);
        }

        String favKey = favoriteKey(channel);
        boolean isFav = favorites.contains(favKey);
        holder.ivFavorite.setImageResource(isFav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChannelClick(channel);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longListener != null) {
                longListener.onLongPress(position, channel);
                return true;
            }
            return false;
        });

        holder.ivFavorite.setOnClickListener(v -> {
            boolean newState = !favorites.contains(favKey);
            if (newState) {
                favorites.add(favKey);
            } else {
                favorites.remove(favKey);
            }
            notifyItemChanged(position);
            if (listener != null) listener.onFavoriteToggle(channel, newState);
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    /** Saved stubs can have no SDT name; show a channel-style fallback, not "Service 102". */
    public static String displayName(ServiceInfo channel) {
        String name = channel.name == null ? "" : channel.name.trim();
        if (!name.isEmpty()) return name;
        String virtual = channel.virtualNumber == null ? "" : channel.virtualNumber.trim();
        return "Channel " + (virtual.isEmpty() ? String.valueOf(channel.serviceId) : virtual);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivLogo;
        TextView tvVirtual;
        TextView tvChannelName;
        TextView tvNetworkName;
        TextView tvNow;
        ImageView ivFavorite;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivLogo = itemView.findViewById(R.id.iv_logo);
            tvVirtual = itemView.findViewById(R.id.tv_virtual);
            tvChannelName = itemView.findViewById(R.id.tv_channel_name);
            tvNetworkName = itemView.findViewById(R.id.tv_network_name);
            tvNow = itemView.findViewById(R.id.tv_now);
            ivFavorite = itemView.findViewById(R.id.iv_favorite);
        }
    }
}
