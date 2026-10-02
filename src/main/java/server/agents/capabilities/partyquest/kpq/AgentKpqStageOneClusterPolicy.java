package server.agents.capabilities.partyquest.kpq;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * Stage 1 of a companion run: the platform clusters its companions camp (L1), the spot a ranged one shoots from
 * (L4), and when a collector leaves its fight for a floor coupon (L6). Pure: the coordinator passes in the map's
 * spawn points and its own ground and platform lookups.
 */
final class AgentKpqStageOneClusterPolicy {
    private static final Comparator<Point> BY_X_THEN_Y =
            Comparator.<Point>comparingInt(point -> point.x).thenComparingInt(point -> point.y);
    private static final Comparator<List<Point>> BY_FIRST_SPAWN =
            (first, second) -> BY_X_THEN_Y.compare(first.getFirst(), second.getFirst());

    private AgentKpqStageOneClusterPolicy() {
    }

    /** A collector as the share-out sees it: its coupon target, and what one of its lines does to a Ligator. */
    record Collector(int characterId, int couponTarget, double lineDamage) {
    }

    /**
     * The spawn points split into clusters a companion can camp. Spawns within {@code linkXPx} and {@code linkYPx} of
     * each other on both axes stand on one platform; then, closest pair first, a cluster with fewer than
     * {@code minSpawns} joins its nearest neighbour (fewest pixels walked plus climbed between their closest spawns,
     * the smaller result on a tie) until none is short. On 103000800 that gives five clusters of three to six.
     */
    static List<List<Point>> clusters(List<Point> spawns, int linkXPx, int linkYPx, int minSpawns) {
        List<Point> points = spawns == null ? List.of()
                : spawns.stream().filter(Objects::nonNull).map(Point::new).sorted(BY_X_THEN_Y).toList();
        int[] parent = new int[points.size()];
        for (int index = 0; index < parent.length; index++) parent[index] = index;
        for (int first = 0; first < points.size(); first++) {
            for (int second = first + 1; second < points.size(); second++) {
                Point a = points.get(first);
                Point b = points.get(second);
                if (Math.abs(a.x - b.x) <= linkXPx && Math.abs(a.y - b.y) <= linkYPx) {
                    parent[root(parent, first)] = root(parent, second);
                }
            }
        }
        Map<Integer, List<Point>> platforms = new LinkedHashMap<>();
        for (int index = 0; index < points.size(); index++) {
            platforms.computeIfAbsent(root(parent, index), key -> new ArrayList<>()).add(points.get(index));
        }
        List<List<Point>> clusters = new ArrayList<>(platforms.values());
        clusters.sort(BY_FIRST_SPAWN);
        while (clusters.size() > 1) {
            int bestFirst = -1;
            int bestSecond = -1;
            long bestDistance = Long.MAX_VALUE;
            int bestSize = Integer.MAX_VALUE;
            for (int first = 0; first < clusters.size(); first++) {
                for (int second = first + 1; second < clusters.size(); second++) {
                    if (clusters.get(first).size() >= minSpawns && clusters.get(second).size() >= minSpawns) continue;
                    long distance = distance(clusters.get(first), clusters.get(second));
                    int size = clusters.get(first).size() + clusters.get(second).size();
                    if (distance < bestDistance || distance == bestDistance && size < bestSize) {
                        bestFirst = first;
                        bestSecond = second;
                        bestDistance = distance;
                        bestSize = size;
                    }
                }
            }
            if (bestFirst < 0) break;
            List<Point> merged = new ArrayList<>(clusters.get(bestFirst));
            merged.addAll(clusters.get(bestSecond));
            merged.sort(BY_X_THEN_Y);
            clusters.remove(bestSecond);
            clusters.remove(bestFirst);
            clusters.add(merged);
            clusters.sort(BY_FIRST_SPAWN);
        }
        return clusters.stream().map(List::copyOf).toList();
    }

    /**
     * The cluster each collector camps, by index. The one with the smallest coupon target (the weaker hitter on a tie)
     * takes the cluster nearest Cloto, where its question and hand-in trips are short; the others, strongest Ligator
     * hitter first, take the clusters with the most spawns, the nearer to Cloto on a tie. More collectors than
     * clusters share them again in that order.
     */
    static Map<Integer, Integer> assign(List<List<Point>> clusters, List<Collector> collectors, Point cloto) {
        if (clusters == null || clusters.isEmpty() || collectors == null || collectors.isEmpty()) return Map.of();
        List<Integer> byRichness = new ArrayList<>();
        for (int index = 0; index < clusters.size(); index++) byRichness.add(index);
        byRichness.sort(Comparator.<Integer>comparingInt(index -> -clusters.get(index).size())
                .thenComparingDouble(index -> distanceTo(clusters.get(index), cloto))
                .thenComparingInt(index -> index));
        Map<Integer, Integer> assigned = new LinkedHashMap<>();
        List<Collector> remaining = new ArrayList<>(collectors);
        List<Integer> free = new ArrayList<>(byRichness);
        if (cloto != null) {
            Collector smallest = remaining.stream().min(Comparator.comparingInt(Collector::couponTarget)
                    .thenComparingDouble(Collector::lineDamage)
                    .thenComparingInt(Collector::characterId)).orElseThrow();
            int nearest = 0;
            for (int index = 1; index < clusters.size(); index++) {
                if (distanceTo(clusters.get(index), cloto) < distanceTo(clusters.get(nearest), cloto)) nearest = index;
            }
            assigned.put(smallest.characterId(), nearest);
            remaining.remove(smallest);
            free.remove(Integer.valueOf(nearest));
        }
        remaining.sort(Comparator.comparingDouble(Collector::lineDamage).reversed()
                .thenComparingInt(Collector::characterId));
        for (Collector collector : remaining) {
            if (free.isEmpty()) free = new ArrayList<>(byRichness);
            assigned.put(collector.characterId(), free.removeFirst());
        }
        return Map.copyOf(assigned);
    }

    /**
     * Where a ranged member fights from in its area: a ground spot {@code standoffPx} to the side of one of the area's
     * spawns, on that spawn's own platform, and at least {@code standoffPx} from every spawn of the area, so a Ligator
     * walks into its shots instead of onto it. Anchored there it attacks only what comes into range, so only a
     * platform holding at least {@code minSpawns} of the area's spawns is used. The spot on the platform with the
     * most of the area's spawns wins, then the one nearest a spawn, then the leftmost; null when no platform of the
     * area is rich and long enough for one.
     *
     * @param ground   the foothold spot under a point, or null
     * @param platform the navigation region under a ground point, or a negative id when it is unknown
     */
    static Point firingSpot(List<Point> area, int standoffPx, int minSpawns, UnaryOperator<Point> ground,
                            ToIntFunction<Point> platform) {
        if (area == null || area.isEmpty() || standoffPx <= 0) return null;
        List<Point> spawns = area.stream().filter(Objects::nonNull).sorted(BY_X_THEN_Y).toList();
        Map<Point, Integer> spawnPlatforms = new LinkedHashMap<>();
        spawns.forEach(spawn -> spawnPlatforms.put(spawn, platform.applyAsInt(spawn)));
        Point best = null;
        long bestSpawns = -1L;
        double bestNearest = Double.MAX_VALUE;
        for (Point spawn : spawns) {
            int spawnPlatform = spawnPlatforms.get(spawn);
            if (spawnPlatform < 0) continue;
            long spawnsOnPlatform = spawnPlatforms.values().stream().filter(id -> id == spawnPlatform).count();
            if (spawnsOnPlatform < minSpawns) continue;
            for (int side : new int[] {-1, 1}) {
                Point spot = ground.apply(new Point(spawn.x + side * standoffPx, spawn.y));
                if (spot == null || platform.applyAsInt(spot) != spawnPlatform) continue;
                double nearest = spawns.stream().mapToDouble(other -> other.distance(spot)).min().orElse(0.0);
                if (nearest < standoffPx) continue;
                boolean better = spawnsOnPlatform > bestSpawns
                        || spawnsOnPlatform == bestSpawns && (nearest < bestNearest
                        || nearest == bestNearest && BY_X_THEN_Y.compare(spot, best) < 0);
                if (better) {
                    best = new Point(spot);
                    bestSpawns = spawnsOnPlatform;
                    bestNearest = nearest;
                }
            }
        }
        return best;
    }

    /**
     * Whether a collector goes for a floor coupon now: not while a Ligator lives on the platform it fights on, unless
     * the coupon lies within {@code nearPx} of it, has lain {@code maxAgeMs} (coupons expire at 180 s), or the
     * coupons on the floor would finish its count.
     */
    static boolean lootNow(boolean platformBusy, double distancePx, long ageMs, boolean completesNeed,
                           int nearPx, long maxAgeMs) {
        return !platformBusy || completesNeed || distancePx <= nearPx || ageMs >= maxAgeMs;
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static long distance(List<Point> first, List<Point> second) {
        long best = Long.MAX_VALUE;
        for (Point a : first) {
            for (Point b : second) {
                best = Math.min(best, (long) Math.abs(a.x - b.x) + Math.abs(a.y - b.y));
            }
        }
        return best;
    }

    private static double distanceTo(List<Point> cluster, Point target) {
        if (target == null) return 0.0;
        return cluster.stream().mapToDouble(spawn -> spawn.distance(target)).min().orElse(Double.MAX_VALUE);
    }
}
