package server.living.roster;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How long a benched companion takes to reach its owner (plan 11.3): a coarse Victoria Island
 * region graph keyed by the map id's first three digits, one hop per road between towns.
 */
public final class RecallTravel {
    private static final long BASE_MS = 20_000L;
    private static final long PER_HOP_MS = 40_000L;
    private static final long FIELD_EXTRA_MS = 20_000L;
    private static final long OFF_ISLAND_MS = 180_000L;
    private static final Map<Integer, List<Integer>> ROADS = Map.ofEntries(
            Map.entry(104, List.of(100, 120, 910)),          // Lith Harbor - Henesys, Nautilus, the Free Market
            Map.entry(100, List.of(104, 101, 102, 103, 105, 120, 910)), // Henesys hub (six path crossway)
            Map.entry(101, List.of(100, 102, 910)),          // Ellinia - Henesys, Perion
            Map.entry(102, List.of(100, 101, 103, 106, 910)), // Perion - Henesys, Ellinia, Kerning, the valleys
            Map.entry(103, List.of(100, 102, 107, 910)),     // Kerning City - Henesys, Perion, the swamp
            Map.entry(105, List.of(100, 106)),               // Sleepywood - Henesys, the valleys
            Map.entry(106, List.of(102, 105)),               // Deep / Dangerous Valley, Burnt Land - Perion, Sleepywood
            Map.entry(107, List.of(103)),                    // Swamp of Despair - Kerning City
            Map.entry(120, List.of(100, 104)),               // Nautilus - Henesys, Lith
            Map.entry(110, List.of(104)),                    // Florina beach - Lith
            Map.entry(910, List.of(100, 101, 102, 103, 104))); // the Free Market: a portal in each Victoria town

    private RecallTravel() {
    }

    static int region(int mapId) {
        return mapId / 1_000_000;
    }

    static boolean isTown(int mapId) {
        return mapId % 1_000_000 == 0 || mapId == 105040300 || mapId >= 910000000 && mapId <= 910000022; // FM rooms
    }

    /** Milliseconds from one map to another; 0 when they are the same map. */
    public static long etaMs(int fromMapId, int toMapId) {
        if (fromMapId == toMapId) {
            return 0L;
        }
        int from = region(fromMapId);
        int to = region(toMapId);
        if (!ROADS.containsKey(from) || !ROADS.containsKey(to)) {
            return OFF_ISLAND_MS;
        }
        int hops = hops(from, to);
        if (hops < 0) {
            return OFF_ISLAND_MS;
        }
        return BASE_MS + PER_HOP_MS * hops + (isTown(toMapId) ? 0L : FIELD_EXTRA_MS);
    }

    /**
     * Decision 7: the road estimate capped at {@code sameContinentCapMs} while both maps are on the island's road graph;
     * an off-island trip keeps its full time.
     */
    public static long etaMs(int fromMapId, int toMapId, long sameContinentCapMs) {
        long eta = etaMs(fromMapId, toMapId);
        int from = region(fromMapId);
        int to = region(toMapId);
        boolean onIsland = ROADS.containsKey(from) && ROADS.containsKey(to) && hops(from, to) >= 0;
        return onIsland ? Math.min(eta, Math.max(0L, sameContinentCapMs)) : eta;
    }

    static int hops(int from, int to) {
        if (from == to) {
            return 0;
        }
        Deque<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> distance = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(from);
        distance.put(from, 0);
        seen.add(from);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            for (int next : ROADS.getOrDefault(current, List.of())) {
                if (seen.add(next)) {
                    distance.put(next, distance.get(current) + 1);
                    if (next == to) {
                        return distance.get(next);
                    }
                    queue.add(next);
                }
            }
        }
        return -1;
    }

    public static String describe(long etaMs) {
        long seconds = Math.round(etaMs / 1000.0d);
        if (seconds < 90) {
            return "~" + seconds + " s";
        }
        return "~" + Math.round(seconds / 60.0d) + " min";
    }
}
