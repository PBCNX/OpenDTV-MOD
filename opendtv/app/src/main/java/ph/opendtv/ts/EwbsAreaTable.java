package ph.opendtv.ts;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Philippine EWBS 12-bit area codes (TVTest-PHL src/EwbsAreaTable.h, 139 entries). */
public final class EwbsAreaTable {
    public static final int NATIONWIDE = 0x200;
    public static final int ALL_AREAS = 0xFFF;
    private static final int ZONE_MASK = 0xE00;
    private static final int REGION_MASK = 0xFE0;

    private static final int[] CODES = {
            0x200,
            0x400,
            0x420,
            0x421,
            0x422,
            0x423,
            0x424,
            0x425,
            0x426,
            0x427,
            0x428,
            0x429,
            0x42A,
            0x42B,
            0x42C,
            0x42D,
            0x42E,
            0x42F,
            0x430,
            0x431,
            0x440,
            0x441,
            0x442,
            0x443,
            0x444,
            0x445,
            0x446,
            0x447,
            0x460,
            0x461,
            0x462,
            0x463,
            0x464,
            0x480,
            0x481,
            0x482,
            0x483,
            0x484,
            0x485,
            0x486,
            0x4A0,
            0x4A1,
            0x4A2,
            0x4A3,
            0x4A4,
            0x4A5,
            0x4A6,
            0x4A7,
            0x4A8,
            0x4A9,
            0x4C0,
            0x4C1,
            0x4C2,
            0x4C3,
            0x4C4,
            0x4C5,
            0x4C6,
            0x4E0,
            0x4E1,
            0x4E2,
            0x4E3,
            0x4E4,
            0x4E5,
            0x4E6,
            0x500,
            0x501,
            0x502,
            0x503,
            0x504,
            0x505,
            0x506,
            0x507,
            0x600,
            0x620,
            0x621,
            0x622,
            0x623,
            0x624,
            0x625,
            0x626,
            0x627,
            0x628,
            0x640,
            0x641,
            0x642,
            0x643,
            0x644,
            0x645,
            0x646,
            0x660,
            0x661,
            0x662,
            0x663,
            0x664,
            0x665,
            0x666,
            0x667,
            0x668,
            0x800,
            0x820,
            0x821,
            0x822,
            0x823,
            0x824,
            0x825,
            0x840,
            0x841,
            0x842,
            0x843,
            0x844,
            0x845,
            0x846,
            0x847,
            0x860,
            0x861,
            0x862,
            0x863,
            0x864,
            0x865,
            0x880,
            0x881,
            0x882,
            0x883,
            0x884,
            0x885,
            0x886,
            0x8A0,
            0x8A1,
            0x8A2,
            0x8A3,
            0x8A4,
            0x8A5,
            0x8A6,
            0x8C0,
            0x8C1,
            0x8C2,
            0x8C3,
            0x8C4,
            0x8C5,
    };
    private static final String[] NAMES = {
            "Nationwide",
            "Luzon",
            "NCR",
            "Caloocan",
            "Las Piñas",
            "Makati",
            "Malabon",
            "Mandaluyong",
            "Manila",
            "Marikina",
            "Muntinlupa",
            "Navotas",
            "Parañaque",
            "Pasay",
            "Pasig",
            "Pateros",
            "Quezon City",
            "San Juan",
            "Taguig",
            "Valenzuela",
            "CAR",
            "Abra",
            "Apayao",
            "Baguio",
            "Benguet",
            "Ifugao",
            "Kalinga",
            "Mt. Province",
            "Ilocos Region",
            "Ilocos Norte",
            "Ilocos Sur",
            "La Union",
            "Pangasinan",
            "Cagayan Valley",
            "Batanes",
            "Cagayan",
            "Isabela",
            "Nueva Vizcaya",
            "Quirino",
            "Santiago",
            "Central Luzon",
            "Angeles",
            "Aurora",
            "Bataan",
            "Bulacan",
            "Nueva Ecija",
            "Olongapo",
            "Pampanga",
            "Tarlac",
            "Zambales",
            "CALABARZON",
            "Batangas",
            "Cavite",
            "Laguna",
            "Lucena",
            "Quezon",
            "Rizal",
            "MIMAROPA",
            "Marinduque",
            "Occidental Mindoro",
            "Oriental Mindoro",
            "Palawan",
            "Puerto Princesa",
            "Romblon",
            "Bicol Region",
            "Albay",
            "Camarines Sur",
            "Camarines Norte",
            "Catanduanes",
            "Masbate",
            "Naga",
            "Sorsogon",
            "Visayas",
            "Western Visayas",
            "Aklan",
            "Antique",
            "Bacolod",
            "Capiz",
            "Guimaras",
            "Iloilo",
            "Iloilo City",
            "Negros Occidental",
            "Central Visayas",
            "Bohol",
            "Cebu",
            "Cebu City",
            "Lapu Lapu",
            "Negros Oriental",
            "Siquijor",
            "Eastern Visayas",
            "Biliran",
            "Eastern Samar",
            "Leyte",
            "Northern Samar",
            "Ormoc",
            "Samar",
            "Southern Leyte",
            "Tacloban",
            "Mindanao",
            "Zamboanga Peninsula",
            "Isabela City",
            "Zamboanga City",
            "Zamboanga del Norte",
            "Zamboanga del Sur",
            "Zamboanga Sibugay",
            "Northern Mindanao",
            "Bukidnon",
            "Cagayan de Oro",
            "Camiguin",
            "Iligan",
            "Lanao del Norte",
            "Misamis Occidental",
            "Misamis Oriental",
            "Davao Region",
            "Compostela Valley",
            "Davao City",
            "Davao del Norte",
            "Davao del Sur",
            "Davao Oriental",
            "SOCCSKSARGEN",
            "Cotabato",
            "Cotabato City",
            "General Santos",
            "Sarangani",
            "South Cotabato",
            "Sultan Kudarat",
            "CARAGA",
            "Agusan del Norte",
            "Agusan del Sur",
            "Butuan",
            "Dinagat Islands",
            "Surigao del Norte",
            "Surigao del Sur",
            "ARMM",
            "Basilan",
            "Lanao del Sur",
            "Maguindanao",
            "Sulu",
            "Tawi-Tawi",
    };

    private EwbsAreaTable() {}

    public static String name(int code) {
        if (code == ALL_AREAS) return "All areas";
        int i = indexOf(code);
        return i >= 0 ? NAMES[i] : String.format(Locale.US, "[%03X]", code & 0xFFF);
    }

    /**
     * Best-effort match for a place name from Android's Geocoder.
     * Returns 0 when nothing matches, so the caller can keep the manual picker.
     */
    public static int findByName(String text) {
        String name = normalizePlace(text);
        if (name.isEmpty()) return 0;
        if ("metro manila".equals(name) || "national capital region".equals(name)) return 0x420;
        if ("cordillera administrative region".equals(name)) return 0x440;
        if ("autonomous region in muslim mindanao".equals(name)) return 0x8C0;

        for (int i = 0; i < CODES.length; i++) {
            if (normalizePlace(NAMES[i]).equals(name)) return CODES[i];
        }
        String stripped = stripPlaceSuffix(name);
        if (!stripped.equals(name)) {
            for (int i = 0; i < CODES.length; i++) {
                if (stripPlaceSuffix(normalizePlace(NAMES[i])).equals(stripped)) return CODES[i];
            }
        }
        return 0;
    }

    private static String normalizePlace(String text) {
        if (text == null) return "";
        String n = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        for (String prefix : new String[] {"city of ", "municipality of ", "province of ",
                "region of ", "capital of "}) {
            if (n.startsWith(prefix)) return n.substring(prefix.length()).trim();
        }
        return n;
    }

    private static String stripPlaceSuffix(String name) {
        if (name.endsWith(" city")) return name.substring(0, name.length() - 5).trim();
        if (name.endsWith(" municipality")) return name.substring(0, name.length() - 13).trim();
        return name;
    }

    public static boolean isPhilippine(int code) {
        return indexOf(code) >= 0;
    }

    public static boolean isZone(int code) {
        return code == 0x200 || code == 0x400 || code == 0x600 || code == 0x800;
    }

    public static boolean isRegion(int code) {
        return code != NATIONWIDE && (code & REGION_MASK) == code && !isZone(code);
    }

    /** PH zones, excluding Nationwide and the "show all" sentinel. */
    public static int[] zoneCodes() {
        return new int[] {0x400, 0x600, 0x800};
    }

    public static int zoneOf(int code) {
        return code == 0 ? 0 : code & ZONE_MASK;
    }

    public static int regionOf(int code) {
        return code == 0 ? 0 : code & REGION_MASK;
    }

    public static int[] regionsInZone(int zone) {
        List<Integer> out = new ArrayList<>();
        if (zone != NATIONWIDE) {
            for (int code : CODES) {
                if ((code & ZONE_MASK) == zone && isRegion(code)) out.add(code);
            }
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < out.size(); i++) result[i] = out.get(i);
        return result;
    }

    public static int[] citiesInRegion(int region) {
        List<Integer> out = new ArrayList<>();
        for (int code : CODES) {
            if ((code & REGION_MASK) == region && !isRegion(code) && !isZone(code)) out.add(code);
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < out.size(); i++) result[i] = out.get(i);
        return result;
    }

    public static int[] citiesInZone(int zone) {
        List<Integer> out = new ArrayList<>();
        for (int code : CODES) {
            if ((code & ZONE_MASK) == zone && !isRegion(code) && !isZone(code)) out.add(code);
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < out.size(); i++) result[i] = out.get(i);
        return result;
    }

    /** True when an alert area covers the viewer location (TVTest-PHL DoesEwbsAreaCover). */
    public static boolean covers(int alert, int location) {
        if (alert == ALL_AREAS || alert == location || alert == NATIONWIDE
                || location == NATIONWIDE) return true;
        if (!isPhilippine(alert) || !isPhilippine(location)) return true;
        if ((alert & ZONE_MASK) != (location & ZONE_MASK)) return false;
        if (isZone(alert) || isZone(location)) return true;
        if ((alert & REGION_MASK) != (location & REGION_MASK)) return false;
        if (isRegion(alert) || isRegion(location)) return true;
        return false;
    }

    /** Compact human text for one descriptor entry. */
    public static String format(List<Integer> input) {
        if (input == null || input.isEmpty()) return "";
        Set<Integer> seen = new LinkedHashSet<>();
        List<Integer> areas = new ArrayList<>();
        for (Integer code : input) {
            if (code != null && seen.add(code & 0xFFF)) areas.add(code & 0xFFF);
        }
        for (int area : areas) {
            if (area == ALL_AREAS) return "All areas";
            if (isZone(area)) return name(area) + countSuffix(countLocations(ZONE_MASK, area));
        }
        for (int area : areas) {
            int region = area & REGION_MASK;
            int cities = 0;
            boolean complete = true;
            for (int code : CODES) {
                if ((code & REGION_MASK) != region || isRegion(code)) continue;
                cities++;
                if (!areas.contains(code)) {
                    complete = false;
                    break;
                }
            }
            if ((isRegion(area) || (complete && cities > 0)) && isPhilippine(region)) {
                return name(region) + countSuffix(countLocations(REGION_MASK, region));
            }
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(3, areas.size());
        for (int i = 0; i < shown; i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name(areas.get(i)));
        }
        if (areas.size() > shown) sb.append(" +").append(areas.size() - shown).append(" areas");
        return sb.toString();
    }

    private static int countLocations(int mask, int value) {
        int count = 0;
        for (int code : CODES) {
            if ((code & mask) == value && !isRegion(code) && !isZone(code)) count++;
        }
        return count;
    }

    private static String countSuffix(int count) {
        return count > 1 ? " + " + count + " areas" : "";
    }

    private static int indexOf(int code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i] == (code & 0xFFF)) return i;
        return -1;
    }
}
