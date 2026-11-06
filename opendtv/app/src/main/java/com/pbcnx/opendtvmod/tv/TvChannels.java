package com.pbcnx.opendtvmod.tv;

import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.pbcnx.opendtvmod.ts.EpgEvent;

/** Everything that talks to the TV provider (channels and programs) for the Live Channels input. */
final class TvChannels {
    private static final String TAG = "OpenDTV-TV";
    private static final int MAX_PROGRAMS = 200;

    private TvChannels() {
    }

    static String inputId(Context c) {
        return TvContract.buildInputId(new ComponentName(c, OpenDtvInputService.class));
    }

    /** A channel to write. */
    static final class Spec {
        int khz;
        int sid;
        int tsId;
        int onid;
        String number = "";
        String name = "";

        String key() {
            return com.pbcnx.opendtvmod.tuner.ChannelStore.key(khz, sid);
        }
    }

    /** What a stored channel URI points at. */
    static final class ChannelRef {
        long channelId;
        int khz;
        int sid;
        int tsId = -1;
    }

    // ------------------------------------------------------------------ channels

    private static byte[] encode(Spec s) {
        return ("v1," + s.khz + "," + s.sid + "," + s.tsId).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The input's channels as {@link Spec}s, for the list operation that must not lose rows it did
     * not touch: a stopped scan keeps the multiplexes it never reached (jpdcs#9).
     */
    static List<Spec> existingChannels(Context c, String inputId) {
        List<Spec> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.buildChannelsUriForInput(inputId),
                new String[]{TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                        TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                        TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                        TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                        TvContract.Channels.COLUMN_SERVICE_ID},
                null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                byte[] data = cur.getBlob(0);
                if (data == null) continue;
                String[] p = new String(data, StandardCharsets.UTF_8).split(",");
                if (p.length < 4 || !"v1".equals(p[0])) continue;
                Spec s = new Spec();
                try {
                    s.khz = Integer.parseInt(p[1]);
                    s.sid = Integer.parseInt(p[2]);
                    s.tsId = Integer.parseInt(p[3]);
                } catch (NumberFormatException e) {
                    continue;   // a row this version cannot read is left alone rather than rewritten
                }
                s.onid = cur.getInt(3);
                String number = cur.getString(1);
                String name = cur.getString(2);
                s.number = number == null ? "" : number;
                s.name = name == null ? "" : name;
                out.add(s);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot list the input's channels: " + e);
        }
        return out;
    }

    /** Reads frequency / service id back from a channel URI that Live Channels passed to onTune(). */
    static ChannelRef resolve(Context c, Uri channelUri) {
        try (Cursor cur = c.getContentResolver().query(channelUri,
                new String[]{TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA}, null, null, null)) {
            if (cur == null || !cur.moveToFirst()) return null;
            byte[] data = cur.getBlob(0);
            if (data == null) return null;
            String[] p = new String(data, StandardCharsets.UTF_8).split(",");
            if (p.length < 4 || !"v1".equals(p[0])) return null;
            ChannelRef r = new ChannelRef();
            r.channelId = ContentUris.parseId(channelUri);
            r.khz = Integer.parseInt(p[1]);
            r.sid = Integer.parseInt(p[2]);
            r.tsId = Integer.parseInt(p[3]);
            return r;
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot resolve " + channelUri + ": " + e);
            return null;
        }
    }

    /**
     * Makes the provider's channel list for this input equal to {@code specs}: existing channels are updated in
     * place (keeps the user's favourites / hidden flags), new ones inserted, vanished ones deleted.
     *
     * @return channel id per Spec.key()
     */
    static Map<String, Long> replaceChannels(Context c, String inputId, List<Spec> specs) {
        ContentResolver cr = c.getContentResolver();
        Map<String, Long> existing = new HashMap<>();
        try (Cursor cur = cr.query(TvContract.buildChannelsUriForInput(inputId),
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID},
                null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String key = cur.getString(1);
                if (key != null) existing.put(key, cur.getLong(0));
            }
        }
        Map<String, Long> result = new HashMap<>();
        for (Spec s : specs) {
            ContentValues v = new ContentValues();
            v.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
            v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_ISDB_T);
            v.put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO);
            v.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, s.number);
            v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, s.name);
            v.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, Math.max(s.onid, 0));
            v.put(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, Math.max(s.tsId, 0));
            v.put(TvContract.Channels.COLUMN_SERVICE_ID, s.sid);
            v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, s.key());
            v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, encode(s));
            v.put(TvContract.Channels.COLUMN_SEARCHABLE, 1);
            Long id = existing.remove(s.key());
            if (id != null) {
                cr.update(TvContract.buildChannelUri(id), v, null, null);
                result.put(s.key(), id);
            } else {
                Uri u = cr.insert(TvContract.Channels.CONTENT_URI, v);
                if (u != null) result.put(s.key(), ContentUris.parseId(u));
            }
        }
        for (long stale : existing.values()) cr.delete(TvContract.buildChannelUri(stale), null, null);
        return result;
    }

    // ------------------------------------------------------------------ programs (EPG)

    /**
     * EIT event times are ARIB JST wall-clock, independent of the TOT field; the +8 descriptor in
     * TOT made every PH programme 9 hours off when it was derived from there.
     */
    private static final long EIT_JST_OFFSET_MS = 9L * 3600_000L;

    private static long hash(List<EpgEvent> events) {
        long h = EIT_JST_OFFSET_MS;
        for (EpgEvent e : events) {
            h = h * 31 + e.eventId;
            h = h * 31 + e.startMs;
            h = h * 31 + e.durationSec;
            h = h * 31 + e.name.hashCode();
            h = h * 31 + e.text.hashCode();
        }
        return h;
    }

    /**
     * Publishes a channel's EPG when it changed since {@code lastHash}, and returns the hash now
     * published. The read-offset-hash-sync protocol used to be replayed by every caller (read the
     * clock offset, hash, compare, sync, remember); it lives here once, so a caller only stores the
     * returned value.
     */
    static long publishPrograms(Context c, long channelId, List<EpgEvent> events, long lastHash) {
        if (events.isEmpty()) return lastHash;
        long h = hash(events);
        if (h == lastHash) return lastHash;
        syncPrograms(c, channelId, events);
        return h;
    }

    /** Replaces the channel's programs with the events the demux has seen. No-op when there is nothing usable. */
    private static void syncPrograms(Context c, long channelId, List<EpgEvent> events) {
        List<ContentProviderOperation> ops = new ArrayList<>();
        int n = 0;
        for (EpgEvent e : events) {
            if (e.startMs < 0 || e.durationSec <= 0) continue;
            if (n++ >= MAX_PROGRAMS) break;
            long start = e.startMs - EIT_JST_OFFSET_MS;
            ContentValues v = new ContentValues();
            v.put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId);
            v.put(TvContract.Programs.COLUMN_TITLE, e.name.isEmpty() ? "(no title)" : e.name);
            if (!e.text.isEmpty()) v.put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, e.text);
            v.put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, start);
            v.put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, start + e.durationSec * 1000L);
            v.put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_FLAG1, e.eventId);
            ops.add(ContentProviderOperation.newInsert(TvContract.Programs.CONTENT_URI).withValues(v).build());
        }
        if (ops.isEmpty()) return;
        ops.add(0, ContentProviderOperation.newDelete(TvContract.buildProgramsUriForChannel(channelId)).build());
        try {
            c.getContentResolver().applyBatch(TvContract.AUTHORITY, new ArrayList<>(ops));
        } catch (Exception e) {
            Log.w(TAG, "EPG sync failed for channel " + channelId + ": " + e);
        }
    }
}
