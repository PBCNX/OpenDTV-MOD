package com.pbcnx.opendtvmod.tuner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pbcnx.opendtvmod.ts.ServiceInfo;

/**
 * The channel list's model and persistence. The store takes a {@code Storage} seam, so the format,
 * the legacy-favourite migration and the ordering rules are testable on the JVM.
 */
public class ChannelStoreTest {

    /** An in-memory Storage: same contract as the SharedPreferences implementation. */
    private static final class FakeStorage implements ChannelStore.Storage {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Set<String>> sets = new HashMap<>();
        final Map<String, Integer> ints = new HashMap<>();

        @Override public String string(String key, String fallback) {
            return strings.getOrDefault(key, fallback);
        }

        @Override public Set<String> stringSet(String key, Set<String> fallback) {
            return sets.getOrDefault(key, fallback);
        }

        @Override public int integer(String key, int fallback) {
            return ints.getOrDefault(key, fallback);
        }

        @Override public void putString(String key, String value) {
            strings.put(key, value);
        }

        @Override public void putStringSet(String key, Set<String> value) {
            sets.put(key, value);
        }

        @Override public void putInt(String key, int value) {
            ints.put(key, value);
        }

        @Override public void remove(String... keys) {
            for (String k : keys) {
                strings.remove(k);
                sets.remove(k);
                ints.remove(k);
            }
        }
    }

    private static ServiceInfo service(int khz, int sid, String name) {
        ServiceInfo s = new ServiceInfo(sid);
        s.khz = khz;
        s.name = name;
        s.provider = "Provider";
        s.virtualNumber = sid + ".1";
        s.pmtSeen = true;
        return s;
    }

    private static ChannelEntry entry(int khz, int sid, String name) {
        return new ChannelEntry(khz, service(khz, sid, name));
    }

    @Test
    public void channelsRoundTripThroughTheStoredFormat() {
        ChannelStore store = new ChannelStore();
        store.replaceChannels(List.of(entry(473143, 1, "GMA"), entry(485143, 64, "ALLTV")));
        FakeStorage storage = new FakeStorage();
        store.saveChannels(storage);

        ChannelStore reloaded = new ChannelStore();
        reloaded.load(storage);
        assertEquals(2, reloaded.channelCount());
        assertEquals(473143, reloaded.channels().get(0).khz);
        assertEquals("ALLTV", reloaded.channels().get(1).service.name);
        assertEquals("Provider", reloaded.channels().get(1).service.provider);
    }

    @Test
    public void favouritesKeepTheirArrangement() {
        ChannelStore store = new ChannelStore();
        store.setFavorite(service(485143, 64, "ALLTV"), true);
        store.setFavorite(service(473143, 1, "GMA"), true);
        FakeStorage storage = new FakeStorage();
        store.saveFavorites(storage);
        store.saveOrder(storage);

        ChannelStore reloaded = new ChannelStore();
        reloaded.loadFavorites(storage);
        assertEquals(2, reloaded.favoriteCount());
        assertEquals(List.of("485143:64", "473143:1"), reloaded.order());
    }

    @Test
    public void aLegacyBareServiceIdFavouriteExpandsToEveryMuxThatCarriesIt() {
        ChannelStore store = new ChannelStore();
        store.replaceChannels(List.of(entry(473143, 64, "INCTV HD"), entry(485143, 64, "ALLTV HD")));
        FakeStorage storage = new FakeStorage();
        Set<String> legacy = new HashSet<>();
        legacy.add("64");
        storage.sets.put("favorites", legacy);

        store.loadFavorites(storage);
        assertTrue(store.favorites().contains("473143:64"));
        assertTrue(store.favorites().contains("485143:64"));
        assertEquals("the migration is rewritten once", 2, storage.sets.get("favorites").size());
    }

    @Test
    public void parseKeyRejectsBareOrMalformedEntries() {
        assertNull(ChannelStore.parseKey("64"));
        assertNull(ChannelStore.parseKey("not:a"));
        assertNull(ChannelStore.parseKey("473143:"));
        assertEquals("473143:64", ChannelStore.parseKey("473143:64"));
    }

    @Test
    public void addChannelAppendsThroughTheStoreApi() {
        ChannelStore store = new ChannelStore();
        store.addChannel(entry(473143, 1, "GMA"));
        store.addChannel(entry(485143, 64, "ALLTV"));
        assertEquals(2, store.channels().size());
        assertEquals("GMA", store.channels().get(0).service.name);
        assertEquals("ALLTV", store.channels().get(1).service.name);
    }

    @Test
    public void pickStartReturnsTheLastChannelThenTheFirst() {
        ChannelStore store = new ChannelStore();
        store.replaceChannels(List.of(entry(473143, 1, "GMA"), entry(485143, 64, "ALLTV")));
        FakeStorage storage = new FakeStorage();
        assertEquals("GMA", store.pickStart(storage).service.name);

        store.saveLast(storage, store.channels().get(1));
        assertEquals("ALLTV", store.pickStart(storage).service.name);
    }

    @Test
    public void clearingRemovesBothMemoryAndStorage() {
        ChannelStore store = new ChannelStore();
        store.replaceChannels(List.of(entry(473143, 1, "GMA")));
        store.setFavorite(service(473143, 1, "GMA"), true);
        FakeStorage storage = new FakeStorage();
        store.saveChannels(storage);
        store.saveFavorites(storage);
        store.saveOrder(storage);

        store.clearChannelsAndSave(storage);
        store.clearFavorites(storage);
        assertEquals(0, store.channelCount());
        assertEquals(0, store.favoriteCount());
        assertTrue(storage.strings.isEmpty());
        assertTrue(storage.sets.isEmpty());
    }

    @Test
    public void unstarringDropsTheIdentityFromBothTheSetAndTheOrder() {
        ChannelStore store = new ChannelStore();
        ServiceInfo gma = service(473143, 1, "GMA");
        store.setFavorite(gma, true);
        store.setFavorite(gma, false);
        assertEquals(0, store.favoriteCount());
        assertTrue(store.order().isEmpty());
    }


    @Test
    public void setFavoriteKeyKeepsSetAndOrderConsistent() {
        ChannelStore store = new ChannelStore();
        store.setFavoriteKey("473143:1", true);
        store.setFavoriteKey("473143:1", true);
        assertEquals(1, store.favoriteCount());
        assertEquals(1, store.order().size());

        store.setFavoriteKey("473143:1", false);
        assertEquals(0, store.favoriteCount());
        assertTrue(store.order().isEmpty());
    }

    @Test
    public void setOrderFromUsesTheServicesIdentity() {
        ChannelStore store = new ChannelStore();
        List<ServiceInfo> ordered = new ArrayList<>();
        ordered.add(service(485143, 64, "ALLTV"));
        ordered.add(service(473143, 1, "GMA"));
        store.setOrderFrom(ordered);
        assertEquals(2, store.order().size());
        assertEquals(ChannelStore.key(ordered.get(0)), store.order().get(0));
        assertEquals(ChannelStore.key(ordered.get(1)), store.order().get(1));
    }


    @Test
    public void namesWithPipesAndNewlinesRoundTrip() {
        ChannelStore store = new ChannelStore();
        ChannelEntry ce = entry(473143, 1, "GMA | News\nHD");
        ce.service.provider = "Prov|ider";
        ce.service.virtualNumber = "5|1";
        store.replaceChannels(List.of(ce));
        FakeStorage storage = new FakeStorage();
        store.saveChannels(storage);

        ChannelStore reloaded = new ChannelStore();
        reloaded.load(storage);
        assertEquals(1, reloaded.channelCount());
        ChannelEntry got = reloaded.channels().get(0);
        assertEquals("GMA | News\nHD", got.service.name);
        assertEquals("Prov|ider", got.service.provider);
        assertEquals("5|1", got.service.virtualNumber);
    }
}
