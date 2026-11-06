package com.pbcnx.opendtvmod.tuner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The channel list as a file: one versioned JSON document a person can read, diff and hand-edit, and
 * that another device can import without guessing.
 *
 * <p>Why JSON and not the preference string the app stores, CSV or M3U:
 *
 * <ul>
 *   <li>the preference string is positional and unversioned - exporting it would freeze an internal
 *       detail into files in the wild, and any storage change would break them;</li>
 *   <li>a broadcast channel has no URL, so an M3U would have to invent one;</li>
 *   <li>favourites are an <em>ordered</em> relation over channels, which CSV expresses badly.</li>
 * </ul>
 *
 * <p>Identity is {@code (khz, serviceId)} - the same key favourites already use. Names, virtual
 * numbers and providers are display data and may be edited by hand; they never identify a channel.
 *
 * <p>Import is deliberately forgiving about <em>entries</em> and strict about the <em>document</em>:
 * a malformed row is counted and skipped, but a file that is not ours, or is a version we do not
 * know, is refused whole. Half-reading a list is how a channel list disappears.
 */
public final class ChannelFile {

    public static final String FORMAT = "opendtv-channels";
    public static final int VERSION = 1;

    /** One channel as the file describes it. */
    public static final class Entry {
        public final int khz;
        public final int serviceId;
        public String virtualNumber;
        public String name;
        public String provider;
        public boolean favorite;
        public int favoriteOrder;      // 0-based; only meaningful when favorite

        public Entry(int khz, int serviceId) {
            this.khz = khz;
            this.serviceId = serviceId;
        }

        /** The identity every other part of the app keys channels and favourites by. */
        public String key() {
            return ChannelStore.key(khz, serviceId);
        }
    }

    /** What an import produced, for a sentence the reader can trust. */
    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();
        public final Set<String> favorites = new LinkedHashSet<>();
        public int skipped;
        public String error;           // non-null = the document was refused, nothing to import

        public boolean ok() {
            return error == null;
        }
    }

    private ChannelFile() {
    }

    // ------------------------------------------------------------------ export

    /** The one ChannelEntry -> Entry mapping; callers must not write their own copy. */
    public static List<Entry> entriesFrom(List<ChannelEntry> channels) {
        List<Entry> out = new ArrayList<>();
        for (ChannelEntry ce : channels) {
            Entry e = new Entry(ce.khz, ce.service.serviceId);
            e.name = ce.service.name;
            e.provider = ce.service.provider;
            e.virtualNumber = ce.service.virtualNumber;
            out.add(e);
        }
        return out;
    }

    /** Writes the document for a live list, so export and the bench copy cannot diverge. */
    public static String toJsonFor(List<ChannelEntry> channels, List<String> favorites, String[][] meta)
            throws JSONException {
        return toJson(entriesFrom(channels), favorites, meta);
    }

    /**
     * @param channels    in list order
     * @param favorites   identity keys ("khz:sid") in the reader's arrangement
     * @param meta        optional informational pairs; never read back as authority
     */
    public static String toJson(List<Entry> channels, List<String> favorites, String[][] meta)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("exportedAt", isoNow());
        if (meta != null) {
            JSONObject app = new JSONObject();
            for (String[] pair : meta) {
                if (pair != null && pair.length == 2 && pair[0] != null) app.put(pair[0], pair[1]);
            }
            root.put("app", app);
        }
        JSONArray arr = new JSONArray();
        Map<String, Integer> favOrder = new LinkedHashMap<>();
        for (int i = 0; favorites != null && i < favorites.size(); i++) favOrder.put(favorites.get(i), i);
        for (Entry e : channels) {
            JSONObject o = new JSONObject();
            o.put("khz", e.khz);
            o.put("serviceId", e.serviceId);
            o.put("virtual", e.virtualNumber == null ? "" : e.virtualNumber);
            o.put("name", e.name == null ? "" : e.name);
            o.put("provider", e.provider == null ? "" : e.provider);
            Integer order = favOrder.get(e.key());
            if (order != null) {
                o.put("favorite", true);
                o.put("favoriteOrder", order);
            }
            arr.put(o);
        }
        root.put("channels", arr);
        // The order is also written on its own, so a hand-edit that drops the per-channel flags
        // still leaves the arrangement intact.
        JSONArray favs = new JSONArray();
        if (favorites != null) {
            for (String key : favorites) {
                int colon = key.indexOf(':');
                if (colon <= 0) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("khz", Integer.parseInt(key.substring(0, colon).trim()));
                    o.put("serviceId", Integer.parseInt(key.substring(colon + 1).trim()));
                    favs.put(o);
                } catch (NumberFormatException ignored) {
                    // a key that cannot be parsed is not a favourite; skip it rather than fail the file
                }
            }
        }
        root.put("favorites", favs);
        return root.toString(2);
    }

    // ------------------------------------------------------------------ import

    /** Parses a document. Never throws: a refused file comes back with {@link Result#error} set. */
    public static Result parse(String text) {
        Result r = new Result();
        if (text == null || text.trim().isEmpty()) {
            r.error = "The file is empty.";
            return r;
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            r.error = "That is not an OpenDTV channel file (it is not JSON).";
            return r;
        }
        String format = root.optString("format", "");
        if (!FORMAT.equals(format)) {
            r.error = "That is not an OpenDTV channel file (format says \""
                    + (format.isEmpty() ? "nothing" : format) + "\").";
            return r;
        }
        int version = root.optInt("version", -1);
        if (version <= 0) {
            r.error = "The file has no version.";
            return r;
        }
        if (version > VERSION) {
            r.error = "This file is version " + version + "; this build reads up to " + VERSION
                    + ". Update the app rather than import half a list.";
            return r;
        }
        JSONArray arr = root.optJSONArray("channels");
        if (arr == null) {
            r.error = "The file has no channels array.";
            return r;
        }
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                r.skipped++;
                continue;
            }
            int khz = o.optInt("khz", -1);
            int sid = o.optInt("serviceId", -1);
            if (khz <= 0 || sid <= 0) {
                r.skipped++;
                continue;
            }
            Entry e = new Entry(khz, sid);
            e.virtualNumber = clip(o.optString("virtual", ""), 16);
            e.name = clip(o.optString("name", ""), 80);
            e.provider = clip(o.optString("provider", ""), 48);
            e.favorite = o.optBoolean("favorite", false);
            e.favoriteOrder = Math.max(0, o.optInt("favoriteOrder", 0));
            // First row wins: a duplicate is counted and ignored, not allowed to overwrite a good
            // channel with a later hand-edit.
            Entry previous = byKey.putIfAbsent(e.key(), e);
            if (previous != null) r.skipped++;
        }
        // The explicit favourites array wins over the per-row flags: it carries the order.
        JSONArray favs = root.optJSONArray("favorites");
        if (favs != null) {
            List<String> order = new ArrayList<>();
            for (int i = 0; i < favs.length(); i++) {
                JSONObject o = favs.optJSONObject(i);
                if (o == null) continue;
                int khz = o.optInt("khz", -1);
                int sid = o.optInt("serviceId", -1);
                if (khz <= 0 || sid <= 0) continue;
                String key = ChannelStore.key(khz, sid);
                order.add(key);
            }
            for (Entry e : byKey.values()) e.favorite = false;
            for (String key : order) {
                Entry e = byKey.get(key);
                if (e == null) continue;            // a favourite for a channel the file does not list
                e.favorite = true;
                e.favoriteOrder = r.favorites.size();
                r.favorites.add(key);
            }
        } else {
            List<Entry> favsOnly = new ArrayList<>();
            for (Entry e : byKey.values()) if (e.favorite) favsOnly.add(e);
            favsOnly.sort((a, b) -> Integer.compare(a.favoriteOrder, b.favoriteOrder));
            for (Entry e : favsOnly) {
                e.favoriteOrder = r.favorites.size();
                r.favorites.add(e.key());
            }
        }
        r.entries.addAll(byKey.values());
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String isoNow() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",
                java.util.Locale.US).format(new java.util.Date());
    }
}
