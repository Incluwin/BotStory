package server.living.population;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * A starting price for a scroll the player price tables do not list (docs/ECONOMY_PLAN.md E3). Shops pay 1 meso for
 * most scrolls, so the NPC-price seed left them at the 10-meso minimum. A scroll is worth what it is expected to add
 * (its success rate x its stat bonuses, weighted by how players value each stat), at the median mesos per expected
 * point of its peers: the scrolls the tables price with the same kind of bonus, then for weapons or armour as it is,
 * then dark (it may destroy the piece) or not, then at the nearest success rate. One rate for every kind made a 60 %
 * weapon accuracy scroll 2.8M beside the tables' 1.5M for 60 % attack (docs/ECONOMY_PLAN.md 10.12). It never goes more
 * than {@link #PEER_BOUND} x outside the range its peers span, so a huge bonus is not extrapolated.
 */
final class ScrollSeeds {
    /** One point of each bonus (the WZ inc* keys) against one point of attack. */
    private static final Map<String, Double> WEIGHTS = Map.ofEntries(
            Map.entry("PAD", 1.0d), Map.entry("MAD", 1.0d),
            Map.entry("STR", 0.6d), Map.entry("DEX", 0.6d), Map.entry("INT", 0.6d), Map.entry("LUK", 0.6d),
            Map.entry("ACC", 0.2d), Map.entry("EVA", 0.2d), Map.entry("Speed", 0.2d), Map.entry("Jump", 0.2d),
            Map.entry("PDD", 0.05d), Map.entry("MDD", 0.05d),
            Map.entry("MHP", 0.005d), Map.entry("MMP", 0.005d));

    /** A judgment, not a price: how far past the cheapest and dearest of its peers a scroll may be priced. */
    private static final double PEER_BOUND = 2.0d;

    private enum Kind {
        ATTACK("PAD", "MAD"), MAIN("STR", "DEX", "INT", "LUK"), ACCURACY("ACC", "EVA"), MOVE("Speed", "Jump"),
        DEFENCE("PDD", "MDD"), HEALTH("MHP", "MMP");

        private final List<String> bonuses;

        Kind(String... bonuses) {
            this.bonuses = List.of(bonuses);
        }

        /** The kind of its largest bonus (HP and MP count a tenth); the first above wins a tie. Null for none. */
        static Kind of(Map<String, Integer> info) {
            Kind lead = null;
            double most = 0.0d;
            for (Kind kind : values()) {
                for (String bonus : kind.bonuses) {
                    double size = info.getOrDefault(bonus, 0) * (kind == HEALTH ? 0.1d : 1.0d);
                    if (size > most) {
                        most = size;
                        lead = kind;
                    }
                }
            }
            return lead;
        }
    }

    /** A scroll a table prices: what it is peered by, its price, and its mesos per expected point. */
    private record Anchor(Kind kind, boolean weapon, boolean dark, int success, long price, double rate) {
    }

    private final IntFunction<Map<String, Integer>> stats;
    private final Supplier<Map<Integer, Long>> anchors;
    private volatile List<Anchor> peers;

    /** {@code stats}: a scroll's WZ info (ItemInformationProvider.getEquipStats); {@code anchors}: the tables' prices. */
    ScrollSeeds(IntFunction<Map<String, Integer>> stats, Supplier<Map<Integer, Long>> anchors) {
        this.stats = stats;
        this.anchors = anchors;
    }

    /** The scroll's seed; 0 for an item that is no scroll, or a scroll these weights do not price (a clean slate). */
    long seed(int itemId) {
        if (!scroll(itemId)) {
            return 0L;
        }
        Map<String, Integer> info = stats.apply(itemId);
        double points = expectedPoints(info);
        Kind kind = info == null ? null : Kind.of(info);
        if (points <= 0.0d || kind == null) {
            return 0L;
        }
        List<Anchor> near = nearest(nearest(nearest(anchors().stream().filter(anchor -> anchor.kind() == kind).toList(),
                anchor -> anchor.weapon() == weapon(itemId) ? 0 : 1),
                anchor -> anchor.dark() == dark(info) ? 0 : 1),
                anchor -> Math.abs(anchor.success() - info.getOrDefault("success", 0)));
        if (near.isEmpty()) { // the tables price no scroll of this kind: every priced scroll's rate
            double rate = median(anchors());
            return rate <= 0.0d ? 0L : Math.round(points * rate);
        }
        long cheapest = near.stream().mapToLong(Anchor::price).min().orElseThrow();
        long dearest = near.stream().mapToLong(Anchor::price).max().orElseThrow();
        return Math.round(Math.clamp(points * median(near), cheapest / PEER_BOUND, dearest * PEER_BOUND));
    }

    /** The anchors at the least {@code distance}. */
    private static List<Anchor> nearest(List<Anchor> anchors, ToIntFunction<Anchor> distance) {
        int least = anchors.stream().mapToInt(distance).min().orElse(0);
        return anchors.stream().filter(anchor -> distance.applyAsInt(anchor) == least).toList();
    }

    private static double median(List<Anchor> anchors) {
        double[] rates = anchors.stream().mapToDouble(Anchor::rate).sorted().toArray();
        int middle = rates.length / 2;
        return rates.length == 0 ? 0.0d : rates.length % 2 == 1 ? rates[middle] : (rates[middle - 1] + rates[middle]) / 2.0d;
    }

    private static boolean scroll(int itemId) {
        return itemId / 10_000 == 204;
    }

    private static boolean weapon(int itemId) {
        return itemId / 1_000 == 2_043 || itemId / 1_000 == 2_044;
    }

    private static boolean dark(Map<String, Integer> info) {
        return info.getOrDefault("cursed", 0) > 0;
    }

    private static double expectedPoints(Map<String, Integer> info) {
        if (info == null) {
            return 0.0d;
        }
        double points = 0.0d;
        for (Map.Entry<String, Double> weight : WEIGHTS.entrySet()) {
            points += weight.getValue() * info.getOrDefault(weight.getKey(), 0);
        }
        return info.getOrDefault("success", 0) / 100.0d * points;
    }

    private List<Anchor> anchors() {
        List<Anchor> known = peers;
        if (known == null) {
            List<Anchor> found = new ArrayList<>();
            anchors.get().forEach((itemId, price) -> {
                Map<String, Integer> info = scroll(itemId) ? stats.apply(itemId) : null;
                double points = expectedPoints(info);
                if (points > 0.0d) {
                    found.add(new Anchor(Kind.of(info), weapon(itemId), dark(info), info.getOrDefault("success", 0), price,
                            price / points));
                }
            });
            peers = known = List.copyOf(found);
        }
        return known;
    }
}
