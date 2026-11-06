package com.pbcnx.opendtvmod.ts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Tests for the Philippine EWBS 12-bit area codes ({@link EwbsAreaTable}). */
public class EwbsAreaTableTest {

    private static final int ZONE_MASK = 0xE00;
    private static final int REGION_MASK = 0xFE0;

    @Test
    public void constantsAreTheDescribedSentinels() {
        assertEquals(0x200, EwbsAreaTable.NATIONWIDE);
        assertEquals(0xFFF, EwbsAreaTable.ALL_AREAS);
        assertEquals("All areas", EwbsAreaTable.name(EwbsAreaTable.ALL_AREAS));
    }

    @Test
    public void knownPlacesHaveTheirDocumentedNames() {
        assertEquals("Nationwide", EwbsAreaTable.name(0x200));
        assertEquals("Luzon", EwbsAreaTable.name(0x400));
        assertEquals("NCR", EwbsAreaTable.name(0x420));
        assertEquals("Caloocan", EwbsAreaTable.name(0x421));
        assertEquals("Manila", EwbsAreaTable.name(0x426));
        assertEquals("Quezon City", EwbsAreaTable.name(0x42E));
        assertEquals("Taguig", EwbsAreaTable.name(0x430));
        assertEquals("CAR", EwbsAreaTable.name(0x440));
        assertEquals("Central Visayas", EwbsAreaTable.name(0x640));
        assertEquals("Cebu City", EwbsAreaTable.name(0x643));
        assertEquals("CARAGA", EwbsAreaTable.name(0x8A0));
        assertEquals("ARMM", EwbsAreaTable.name(0x8C0));
    }

    @Test
    public void unknownCodeIsRenderedAsHexAndHighBitsAreMaskedOff() {
        assertEquals("[123]", EwbsAreaTable.name(0x123));
        assertEquals("[520]", EwbsAreaTable.name(0x520));
        // a code carrying frame bits above bit 11 still means the 12-bit area
        assertEquals("Caloocan", EwbsAreaTable.name(0x1421));
        assertTrue(EwbsAreaTable.isPhilippine(0x1421));
    }

    @Test
    public void tableHasTheDocumentedNumberOfAreas() {
        int count = 0;
        for (int code = 0; code <= 0xFFF; code++) if (EwbsAreaTable.isPhilippine(code)) count++;
        assertEquals(139, count);
        assertFalse(EwbsAreaTable.isPhilippine(EwbsAreaTable.ALL_AREAS));
        assertFalse(EwbsAreaTable.isPhilippine(0x123));
    }

    @Test
    public void zoneAndRegionClassificationIsConsistentForEveryCode() {
        int zones = 0;
        for (int code = 0; code <= 0xFFF; code++) {
            boolean zone = EwbsAreaTable.isZone(code);
            boolean region = EwbsAreaTable.isRegion(code);
            if (zone) zones++;
            // isZone is exactly the four table rows that name a zone
            assertEquals("isZone " + hex(code), zone,
                    code == 0x200 || code == 0x400 || code == 0x600 || code == 0x800);
            // isRegion is the documented mask test, not a table-membership test
            assertEquals("isRegion " + hex(code), region,
                    code != EwbsAreaTable.NATIONWIDE && (code & REGION_MASK) == code && !zone);
            // a zone is never a region
            assertFalse("zone is region " + hex(code), zone && region);
            // zoneOf / regionOf are pure masks
            assertEquals("zoneOf " + hex(code), code == 0 ? 0 : code & ZONE_MASK, EwbsAreaTable.zoneOf(code));
            assertEquals("regionOf " + hex(code), code == 0 ? 0 : code & REGION_MASK, EwbsAreaTable.regionOf(code));
            // and they round-trip
            assertEquals("zoneOf(regionOf) " + hex(code), EwbsAreaTable.zoneOf(code),
                    EwbsAreaTable.zoneOf(EwbsAreaTable.regionOf(code)));
        }
        assertEquals("exactly four zones", 4, zones);
    }

    @Test
    public void everyPhilippineCodeHasANameAndAZoneAndRegion() {
        for (int code = 0; code <= 0xFFF; code++) {
            if (!EwbsAreaTable.isPhilippine(code)) continue;
            String name = EwbsAreaTable.name(code);
            assertFalse("named " + hex(code), name.startsWith("["));
            assertFalse("all-areas sentinel is not a table row", "All areas".equals(name));
            assertEquals("region aligned " + hex(code), code & REGION_MASK, EwbsAreaTable.regionOf(code));
            assertEquals("zone aligned " + hex(code), code & ZONE_MASK, EwbsAreaTable.zoneOf(code));
            assertEquals("reads back as its own name", code, EwbsAreaTable.findByName(name));
        }
    }

    @Test
    public void documentedMaskOnlyBehaviourForCodesOutsideTheTable() {
        // 0x520 is region-aligned but is not a row: isRegion is a mask test, isPhilippine is membership.
        assertTrue(EwbsAreaTable.isRegion(0x520));
        assertFalse(EwbsAreaTable.isPhilippine(0x520));
        // 0 is region-aligned by the same mask rule
        assertTrue(EwbsAreaTable.isRegion(0));
        assertFalse(EwbsAreaTable.isPhilippine(0));
    }

    @Test
    public void zonesAreLuzonVisayasMindanao() {
        assertArrayEquals(new int[] {0x400, 0x600, 0x800}, EwbsAreaTable.zoneCodes());
        assertArrayEquals(new int[] {0x420, 0x440, 0x460, 0x480, 0x4A0, 0x4C0, 0x4E0, 0x500},
                EwbsAreaTable.regionsInZone(0x400));
        assertEquals("no regions under the nationwide sentinel", 0,
                EwbsAreaTable.regionsInZone(EwbsAreaTable.NATIONWIDE).length);
    }

    @Test
    public void regionAndZoneContainTheirCities() {
        int[] ncr = EwbsAreaTable.citiesInRegion(0x420);
        assertEquals(17, ncr.length);
        assertEquals(0x421, ncr[0]);
        for (int city : ncr) {
            assertEquals("region of " + hex(city), 0x420, EwbsAreaTable.regionOf(city));
            assertFalse(EwbsAreaTable.isRegion(city));
            assertFalse(EwbsAreaTable.isZone(city));
            assertTrue(EwbsAreaTable.isPhilippine(city));
        }

        int[] luzon = EwbsAreaTable.citiesInZone(0x400);
        assertEquals(62, luzon.length);
        for (int city : luzon) assertEquals("zone of " + hex(city), 0x400, EwbsAreaTable.zoneOf(city));
    }

    @Test
    public void coversFollowsTheDescriptorRules() {
        // sentinels cover everything, in either position
        assertTrue(EwbsAreaTable.covers(EwbsAreaTable.ALL_AREAS, 0x421));
        assertTrue(EwbsAreaTable.covers(EwbsAreaTable.NATIONWIDE, 0x822));
        assertTrue(EwbsAreaTable.covers(0x421, EwbsAreaTable.NATIONWIDE));
        // same area
        assertTrue(EwbsAreaTable.covers(0x421, 0x421));
        // region / zone covers one of their cities, in either position
        assertTrue(EwbsAreaTable.covers(0x420, 0x421));
        assertTrue(EwbsAreaTable.covers(0x400, 0x421));
        assertTrue(EwbsAreaTable.covers(0x421, 0x420));
        assertTrue(EwbsAreaTable.covers(0x643, 0x640));
        assertTrue(EwbsAreaTable.covers(0x643, 0x600));
        // different region or different zone does not
        assertFalse(EwbsAreaTable.covers(0x421, 0x441));
        assertFalse(EwbsAreaTable.covers(0x421, 0x822));
        assertFalse(EwbsAreaTable.covers(0x400, 0x800));
        // two cities of the same region do not cover each other
        assertFalse(EwbsAreaTable.covers(0x642, 0x643));
        // a non-Philippine code cannot be reasoned about: the table lets it through
        assertTrue(EwbsAreaTable.covers(0x520, 0x421));
    }

    @Test
    public void findByNameMatchesGeocoderNames() {
        assertEquals(0x42E, EwbsAreaTable.findByName("Quezon City"));
        assertEquals(0x42E, EwbsAreaTable.findByName("quezon city"));
        assertEquals(0x426, EwbsAreaTable.findByName("City of Manila"));
        assertEquals(0x426, EwbsAreaTable.findByName("Manila"));
        assertEquals(0x420, EwbsAreaTable.findByName("Metro Manila"));
        assertEquals(0x420, EwbsAreaTable.findByName("National Capital Region"));
        assertEquals(0x440, EwbsAreaTable.findByName("Cordillera Administrative Region"));
        assertEquals(0x8C0, EwbsAreaTable.findByName("Autonomous Region in Muslim Mindanao"));
        assertEquals(0x643, EwbsAreaTable.findByName("Cebu City"));
        assertEquals(0x642, EwbsAreaTable.findByName("Cebu"));
        assertEquals(0x642, EwbsAreaTable.findByName("Province of Cebu"));
        assertEquals(0x862, EwbsAreaTable.findByName("Davao City"));
        assertEquals("Davao City", EwbsAreaTable.name(0x862));
        assertEquals(0, EwbsAreaTable.findByName("Atlantis"));
        assertEquals(0, EwbsAreaTable.findByName(""));
        assertEquals(0, EwbsAreaTable.findByName(null));
    }

    @Test
    public void formatSummarisesALocationList() {
        assertEquals("", EwbsAreaTable.format(null));
        assertEquals("", EwbsAreaTable.format(Collections.<Integer>emptyList()));
        assertEquals("All areas", EwbsAreaTable.format(Arrays.asList(EwbsAreaTable.ALL_AREAS)));
        assertEquals("All areas", EwbsAreaTable.format(Arrays.asList(EwbsAreaTable.ALL_AREAS, 0x421)));

        assertEquals("Luzon + " + EwbsAreaTable.citiesInZone(0x400).length + " areas",
                EwbsAreaTable.format(Arrays.asList(0x400)));
        assertEquals("NCR + " + EwbsAreaTable.citiesInRegion(0x420).length + " areas",
                EwbsAreaTable.format(Arrays.asList(0x420)));
        // all cities of a region collapse to the region
        assertEquals("NCR + " + EwbsAreaTable.citiesInRegion(0x420).length + " areas",
                EwbsAreaTable.format(toList(EwbsAreaTable.citiesInRegion(0x420))));

        assertEquals(EwbsAreaTable.name(0x421) + ", " + EwbsAreaTable.name(0x422) + ", "
                        + EwbsAreaTable.name(0x423),
                EwbsAreaTable.format(Arrays.asList(0x421, 0x422, 0x423)));
        assertEquals(EwbsAreaTable.name(0x421) + ", " + EwbsAreaTable.name(0x422) + ", "
                        + EwbsAreaTable.name(0x423) + " +3 areas",
                EwbsAreaTable.format(Arrays.asList(0x421, 0x422, 0x423, 0x424, 0x425, 0x426)));

        // duplicates and null slots are dropped, one area is just its name
        assertEquals("Manila", EwbsAreaTable.format(Arrays.asList(0x426, 0x426)));
        assertEquals("Caloocan", EwbsAreaTable.format(Arrays.asList(0x421, null)));
        // high frame bits are masked off before matching
        assertEquals("All areas", EwbsAreaTable.format(Arrays.asList(0x1FFF)));
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> out = new java.util.ArrayList<>();
        for (int v : values) out.add(v);
        return out;
    }

    private static String hex(int code) {
        return String.format("0x%03X", code & 0xFFF);
    }
}
