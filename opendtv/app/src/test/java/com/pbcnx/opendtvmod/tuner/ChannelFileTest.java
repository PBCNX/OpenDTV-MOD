package com.pbcnx.opendtvmod.tuner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The channel-file round trip: this is the channel list's only integrity path, a hand-editable
 * document that must refuse a bad whole file and still salvage the good rows of a half-edited one.
 */
public class ChannelFileTest {

    private static ChannelFile.Entry entry(int khz, int sid, String number, String name) {
        ChannelFile.Entry e = new ChannelFile.Entry(khz, sid);
        e.virtualNumber = number;
        e.name = name;
        e.provider = "Provider";
        return e;
    }

    @Test
    public void awrittenFileReadsBackTheSameChannelsAndFavourites() throws Exception {
        List<ChannelFile.Entry> channels = Arrays.asList(
                entry(473143, 1, "2.1", "GMA"),
                entry(473143, 2, "2.2", "INCTV"),
                entry(485143, 64, "7.1", "ALLTV"));
        List<String> favourites = Arrays.asList("485143:64", "473143:2");

        String json = ChannelFile.toJson(channels, favourites, new String[][]{{"app", "OpenDTV test"}});
        ChannelFile.Result r = ChannelFile.parse(json);

        assertTrue(r.error, r.ok());
        assertEquals(0, r.skipped);
        assertEquals(3, r.entries.size());
        assertEquals("2.1", r.entries.get(0).virtualNumber);
        assertEquals("ALLTV", r.entries.get(2).name);
        assertEquals(Arrays.asList("485143:64", "473143:2"), Arrays.asList(r.favorites.toArray()));
        assertTrue(r.entries.get(2).favorite);
        assertEquals(0, r.entries.get(2).favoriteOrder);
        assertFalse(r.entries.get(0).favorite);
    }

    @Test
    public void aForeignOrBrokenDocumentIsRefusedWhole() {
        assertFalse(ChannelFile.parse("not json").ok());
        assertFalse(ChannelFile.parse("").ok());
        assertFalse(ChannelFile.parse("{\"format\":\"something-else\",\"version\":1,\"channels\":[]}").ok());
        assertFalse(ChannelFile.parse("{\"format\":\"opendtv-channels\",\"channels\":[]}").ok());
        ChannelFile.Result future = ChannelFile.parse(
                "{\"format\":\"opendtv-channels\",\"version\":2,\"channels\":[]}");
        assertFalse("a newer version must be refused, not half-read", future.ok());
        assertNotNull(future.error);
        assertTrue(future.entries.isEmpty());
    }

    @Test
    public void aHalfEditedFileSalvagesItsGoodRowsAndCountsTheRest() {
        String json = "{\"format\":\"opendtv-channels\",\"version\":1,\"channels\":["
                + "{\"khz\":473143,\"serviceId\":1,\"name\":\"GMA\"},"
                + "{\"khz\":0,\"serviceId\":2,\"name\":\"no frequency\"},"
                + "\"not an object\","
                + "{\"khz\":473143,\"serviceId\":1,\"name\":\"duplicate\"}]}";
        ChannelFile.Result r = ChannelFile.parse(json);
        assertTrue(r.ok());
        assertEquals(1, r.entries.size());
        assertEquals("GMA", r.entries.get(0).name);
        assertEquals("a zero frequency, a non-object and a duplicate", 3, r.skipped);
    }

    @Test
    public void theExplicitFavouriteArrayWinsOverPerRowFlags() {
        String json = "{\"format\":\"opendtv-channels\",\"version\":1,\"channels\":["
                + "{\"khz\":473143,\"serviceId\":1,\"favorite\":true,\"favoriteOrder\":7},"
                + "{\"khz\":473143,\"serviceId\":2}],"
                + "\"favorites\":[{\"khz\":473143,\"serviceId\":2}]}";
        ChannelFile.Result r = ChannelFile.parse(json);
        assertEquals(Collections.singletonList("473143:2"), Arrays.asList(r.favorites.toArray()));
        assertFalse("the per-row flag is overridden", r.entries.get(0).favorite);
        assertTrue(r.entries.get(1).favorite);
    }

    @Test
    public void perRowFavouriteFlagsAreReadInOrderWhenThereIsNoArray() {
        String json = "{\"format\":\"opendtv-channels\",\"version\":1,\"channels\":["
                + "{\"khz\":473143,\"serviceId\":2,\"favorite\":true,\"favoriteOrder\":1},"
                + "{\"khz\":473143,\"serviceId\":1,\"favorite\":true,\"favoriteOrder\":0}]}";
        ChannelFile.Result r = ChannelFile.parse(json);
        assertEquals(Arrays.asList("473143:1", "473143:2"), Arrays.asList(r.favorites.toArray()));
    }

    @Test
    public void namesAreClippedAndKeysAreTheIdentity() throws Exception {
        ChannelFile.Entry e = entry(473143, 1, "2.1", "x".repeat(120));
        assertEquals(80, ChannelFile.parse(ChannelFile.toJson(
                Collections.singletonList(e), Collections.emptyList(), null)).entries.get(0).name.length());
        assertEquals("473143:1", e.key());
    }
}
