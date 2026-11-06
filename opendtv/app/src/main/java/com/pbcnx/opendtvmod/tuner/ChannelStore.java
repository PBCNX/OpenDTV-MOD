package com.pbcnx.opendtvmod.tuner;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pbcnx.opendtvmod.ts.ServiceInfo;

/**
 * The app's channel list and favourites: the model plus its persistence.
 *
 * <p>It lived in MainActivity, which meant the activity was the only place the channel domain
 * existed and every scan, import, reorder and star had to reach into it. The keys and the stored
 * formats are unchanged ({@code saved_channels} lines, a {@code favorites} set and an ordered
 * {@code favorite_order}), so an installed app keeps its list.</p>
 */
public final class ChannelStore {

    /** The prefs operations the store needs, so the store can be tested without Android. */
    public interface Storage {
        String string(String key, String fallback);

        Set<String> stringSet(String key, Set<String> fallback);

        int integer(String key, int fallback);

        void putString(String key, String value);

        void putStringSet(String key, Set<String> value);

        void putInt(String key, int value);

        void remove(String... keys);
    }

    /** The Android implementation: the app's one preferences file. */
    @NonNull public static Storage prefs(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Storage() {
            @Override public String string(String key, String fallback) { return p.getString(key, fallback); }

            @Override public Set<String> stringSet(String key, Set<String> fallback) {
                return p.getStringSet(key, fallback);
            }

            @Override public int integer(String key, int fallback) { return p.getInt(key, fallback); }

            @Override public void putString(String key, String value) { p.edit().putString(key, value).apply(); }

            @Override public void putStringSet(String key, Set<String> value) {
                p.edit().putStringSet(key, value).apply();
            }

            @Override public void putInt(String key, int value) { p.edit().putInt(key, value).apply(); }

            @Override public void remove(String... keys) {
                SharedPreferences.Editor e = p.edit();
                for (String k : keys) e.remove(k);
                e.apply();
            }
        };
    }

    private static final String PREFS = com.pbcnx.opendtvmod.App.PREFS;
    private static final String PREF_CHANNELS = "saved_channels";
    private static final String PREF_FAV = "favorites";
    private static final String PREF_FAV_ORDER = "favorite_order";
    private static final String PREF_LAST_SID = "last_service_id";
    private static final String PREF_LAST_KHZ = "last_khz";

    private final List<ChannelEntry> channels = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    private final List<String> favoriteOrder = new ArrayList<>();

    /** The saved channels, in list order: a live read-only view for the adapter. */
    @NonNull public List<ChannelEntry> channels() {
        return Collections.unmodifiableList(channels);
    }

    /** The favourite identities ("khz:serviceId"). */
    @NonNull public Set<String> favorites() {
        return Collections.unmodifiableSet(favorites);
    }

    /** The favourite identities in the reader's arrangement. */
    @NonNull public List<String> order() {
        return Collections.unmodifiableList(favoriteOrder);
    }

    public int channelCount() {
        return channels.size();
    }

    public int favoriteCount() {
        return favorites.size();
    }

    /** "khz:serviceId": the identity favourites and saved-channel checks key on. */
    @NonNull public static String key(ServiceInfo channel) {
        return key(channel.khz, channel.serviceId);
    }

    public static String key(int khz, int serviceId) {
        return khz + ":" + serviceId;
    }

    /** The key in {@code s}, or null for a pre-frequency entry (a bare service id). */
    @Nullable public static String parseKey(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1) return null;
        try {
            return key(Integer.parseInt(s.substring(0, colon).trim()),
                    Integer.parseInt(s.substring(colon + 1).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ load

    void load(Storage storage) {
        String raw = storage.string(PREF_CHANNELS, "");
        channels.clear();
        for (String line : raw.split("\n")) {
            String[] p = line.split("\\|");
            if (p.length < 5) continue;
            try {
                int khz = Integer.parseInt(p[0]);
                int sid = Integer.parseInt(p[1]);
                ServiceInfo svc = new ServiceInfo(sid);
                svc.name = unescape(p[2]);
                svc.provider = unescape(p[3]);
                svc.virtualNumber = unescape(p[4]);
                svc.pmtSeen = true;
                channels.add(new ChannelEntry(khz, svc));
            } catch (RuntimeException ignored) {
                // a malformed channel line is skipped; the rest still load
            }
        }
        applyFallbackNumbers();
    }

    /**
     * Numbers the services whose mux broadcasts no virtual channel: "UHF.position" (19.1, 19.2 …),
     * printed as "~19.1". Recomputed on every load/scan/import so the stored five fields never
     * carry a stale fallback. Owner, 2026-11-06.
     */
    private void applyFallbackNumbers() {
        Map<Integer, List<ChannelEntry>> byKhz = new LinkedHashMap<>();
        for (ChannelEntry ce : channels) {
            List<ChannelEntry> group = byKhz.get(ce.khz);
            if (group == null) {
                group = new ArrayList<>();
                byKhz.put(ce.khz, group);
            }
            group.add(ce);
        }
        for (Map.Entry<Integer, List<ChannelEntry>> e : byKhz.entrySet()) {
            for (ChannelEntry ce : e.getValue()) ce.service.fallbackNumber = "";
            int uhf = MuxSweep.planChannel(e.getKey());
            if (uhf <= 0) continue;
            List<ChannelEntry> group = e.getValue();
            group.sort((a, b) -> Integer.compare(a.service.serviceId, b.service.serviceId));
            int position = 0;
            for (ChannelEntry ce : group) {
                position++;
                if (!ce.service.hasVirtualNumber()) ce.service.fallbackNumber = uhf + "." + position;
            }
        }
    }

    /** Hydrates the list and then the favourites; loadFavorites expands legacy SIDs through it. */
    public void loadAll(Storage storage) {
        load(storage);
        loadFavorites(storage);
    }

    /**
     * Favourites are stored as "frequency:serviceId". A stored bare SID is from before that: it
     * cannot say which mux the reader meant, so it becomes every saved channel that carries it - the
     * state they actually saw - and is rewritten once.
     */
    void loadFavorites(Storage storage) {
        favorites.clear();
        favoriteOrder.clear();
        boolean migrated = false;
        for (String s : storage.stringSet(PREF_FAV, new HashSet<>())) {
            String key = parseKey(s);
            if (key != null) favorites.add(key);
            else migrated |= expandLegacy(s);
        }
        for (String part : storage.string(PREF_FAV_ORDER, "").split(",")) {
            if (part.isEmpty()) continue;
            String key = parseKey(part);
            if (key != null) {
                if (!favoriteOrder.contains(key)) favoriteOrder.add(key);
            } else {
                migrated |= expandLegacy(part);
            }
        }
        if (migrated) {
            saveFavorites(storage);
            saveOrder(storage);
        }
    }

    /** Expands one legacy bare SID into the saved channels that carry it. True if it added any. */
    private boolean expandLegacy(String s) {
        int sid;
        try {
            sid = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        boolean added = false;
        for (ChannelEntry ce : channels) {
            if (ce.service.serviceId != sid) continue;
            String key = key(ce.service);
            added |= favorites.add(key);
            if (!favoriteOrder.contains(key)) {
                favoriteOrder.add(key);
                added = true;
            }
        }
        return added;
    }

    // ------------------------------------------------------------------ save

    /** Replaces the whole list in one call, so a scan or import does not mutate the exposed list. */
    public void replaceChannels(List<ChannelEntry> entries) {
        channels.clear();
        channels.addAll(entries);
        applyFallbackNumbers();
    }

    /** Replaces the list and persists it; the scan's completion path. */
    public void replaceChannelsAndSave(List<ChannelEntry> entries, Storage storage) {
        replaceChannels(entries);
        saveChannels(storage);
    }

    /** Replaces the entries on one frequency (a manual scan); the caller persists. */
    /** Appends one imported channel; the caller owns saving/reloading. */
    public void addChannel(ChannelEntry entry) {
        channels.add(entry);
        applyFallbackNumbers();
    }

    public void replaceFrequency(int khz, List<ChannelEntry> entries) {
        channels.removeIf(ce -> ce.khz == khz);
        channels.addAll(entries);
        applyFallbackNumbers();
    }

    /** Empties the list (the replace path of an import); the caller persists. */
    public void clearChannels() {
        channels.clear();
    }

    public void saveChannels(Storage storage) {
        StringBuilder sb = new StringBuilder();
        for (ChannelEntry ce : channels) {
            sb.append(ce.khz).append('|')
                    .append(ce.service.serviceId).append('|')
                    .append(escape(ce.service.name)).append('|')
                    .append(escape(ce.service.provider)).append('|')
                    .append(escape(ce.service.virtualNumber)).append('\n');
        }
        storage.putString(PREF_CHANNELS, sb.toString());
    }

    public void saveFavorites(Storage storage) {
        storage.putStringSet(PREF_FAV, new HashSet<>(favorites));
    }

    public void saveOrder(Storage storage) {
        StringBuilder sb = new StringBuilder();
        for (String key : favoriteOrder) {
            if (sb.length() > 0) sb.append(',');
            sb.append(key);
        }
        storage.putString(PREF_FAV_ORDER, sb.toString());
    }

    /** Replaces the in-memory favourite order with {@code ordered}; the caller saves it. */
    public void setOrderFrom(List<ServiceInfo> ordered) {
        favoriteOrder.clear();
        for (ServiceInfo s : ordered) favoriteOrder.add(key(s));
    }

    /**
     * Stars or un-stars by identity key. The channel-file import brings keys, not ServiceInfo, so
     * it must not reach into the two collections and re-implement the invariant.
     */
    public void setFavoriteKey(String key, boolean on) {
        if (on) {
            if (!favoriteOrder.contains(key)) favoriteOrder.add(key);
            favorites.add(key);
        } else {
            favorites.remove(key);
            favoriteOrder.remove(key);
        }
    }

    /** Stars or un-stars a service, keeping the order list consistent. */
    public void setFavorite(ServiceInfo service, boolean on) {
        setFavoriteKey(key(service), on);
    }

    // ------------------------------------------------------------------ last channel

    /** Last tuned channel if it is still in the list, otherwise the first one. */
    @Nullable public ChannelEntry pickStart(Storage storage) {
        if (channels.isEmpty()) return null;
        int sid = storage.integer(PREF_LAST_SID, -1);
        int khz = storage.integer(PREF_LAST_KHZ, -1);
        for (ChannelEntry ce : channels) {
            if (ce.service.serviceId == sid && (khz < 0 || ce.khz == khz)) return ce;
        }
        return channels.get(0);
    }

    public void saveLast(Storage storage, ChannelEntry entry) {
        storage.putInt(PREF_LAST_SID, entry.service.serviceId);
        storage.putInt(PREF_LAST_KHZ, entry.khz);
    }

    // ------------------------------------------------------------------ reset

    public void clearChannelsAndSave(Storage storage) {
        channels.clear();
        storage.remove(PREF_CHANNELS);
    }

    public void clearFavorites(Storage storage) {
        favorites.clear();
        favoriteOrder.clear();
        storage.remove(PREF_FAV, PREF_FAV_ORDER);
    }


    /**
     * The channel row format is pipe-separated and newline-terminated, so a name that contains a
     * pipe or a line break would shift or drop the row: escape those three characters and the
     * backslash itself. The channel-file JSON path already quotes its fields; this is the prefs one.
     */
    private static String escape(String value) {
        if (value == null) return "";
        if (value.indexOf('|') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '|': out.append("\\p"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char n = value.charAt(++i);
            if (n == 'p') out.append('|');
            else if (n == 'n') out.append('\n');
            else if (n == 'r') out.append('\r');
            else out.append(n);
        }
        return out.toString();
    }
}
