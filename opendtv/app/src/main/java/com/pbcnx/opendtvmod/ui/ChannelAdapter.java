package com.pbcnx.opendtvmod.ui;

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

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.ts.ServiceInfo;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(ServiceInfo service);
        void onFavoriteToggle(ServiceInfo service, boolean isFavorite);
    }

    public interface OnChannelLongClickListener {
        void onLongPress(int position, ServiceInfo channel);
    }

    /**
     * Favourites are keyed by "frequency:service_id", not by service_id alone: three channels on
     * this bench share SID 64, so a SID-only set put a star on all of them. The rule lives in the
     * channel store; this is the adapter's name for it.
     */
    public static String favoriteKey(ServiceInfo channel) {
        return com.pbcnx.opendtvmod.tuner.ChannelStore.key(channel);
    }

    public static String favoriteKey(int khz, int serviceId) {
        return com.pbcnx.opendtvmod.tuner.ChannelStore.key(khz, serviceId);
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

    /** The tuned channel, as its (frequency, service id) key: SID alone is not unique. */
    private String activeKey;

    public void setActiveService(ServiceInfo service) {
        String key = service == null ? null : com.pbcnx.opendtvmod.tuner.ChannelStore.key(service);
        if (java.util.Objects.equals(activeKey, key)) return;
        activeKey = key;
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
        // Keyed by (frequency, service id), not SID alone: three channels on this bench share
        // SID 64 and 503143/515143 both carry SID 1 (RPTV HD and SolarFlix), so a SID-only match
        // lit two rows at once and read as "selecting RPTV HD selects SolarFlix" (owner, 2026-11-06).
        boolean isActive = activeKey != null && channel != null
                && activeKey.equals(com.pbcnx.opendtvmod.tuner.ChannelStore.key(channel));
        holder.itemView.setSelected(isActive);
        boolean realNumber = channel.hasVirtualNumber();
        holder.tvVirtual.setText(channel.displayNumber());
        holder.tvVirtual.setAlpha(realNumber ? 1f : 0.6f);
        holder.tvVirtual.setContentDescription(realNumber ? null
                : context.getString(R.string.cd_approximate_number, channel.displayNumber()));
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
            // The host owns the store's live set/order invariant: report the intent and let it
            // write through ChannelStore, then rebuild this row from the new state.
            boolean newState = !favorites.contains(favKey);
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
        // displayNumber(), not the raw field: "--" is the "broadcast gave none" sentinel and used
        // to reach the screen as the literal words "Channel --".
        return "Channel " + channel.displayNumber();
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
