package server.living.population;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A starting price for a scroll the player price tables do not list (docs/ECONOMY_PLAN.md E3). Shops pay 1 meso for
 * most scrolls, so the NPC-price seed left them at the 10-meso minimum. A scroll is worth what it is expected to add
 * (its success rate x its stat bonuses, weighted by how players value each stat), at the median mesos per expected
 * point of the scrolls the tables do price, so it follows the tables' price level.
 */
final class ScrollSeeds {
    /** One point of each bonus (the WZ inc* keys) against one point of attack. */
    private static final Map<String, Double> WEIGHTS = Map.ofEntries(
            Map.entry("PAD", 1.0d), Map.entry("MAD", 1.0d),
            Map.entry("STR", 0.6d), Map.entry("DEX", 0.6d), Map.entry("INT", 0.6d), Map.entry("LUK", 0.6d),
            Map.entry("ACC", 0.2d), Map.entry("EVA", 0.2d), Map.entry("Speed", 0.2d), Map.entry("Jump", 0.2d),
            Map.entry("PDD", 0.05d), Map.entry("MDD", 0.05d),
            Map.entry("MHP", 0.005d), Map.entry("MMP", 0.005d));

    private final IntFunction<Map<String, Integer>> stats;
    private final Supplier<Map<Integer, Long>> anchors;
    private volatile double perPoint = -1.0d;

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
        double points = expectedPoints(itemId);
        double rate = perPoint();
        return points <= 0.0d || rate <= 0.0d ? 0L : Math.round(points * rate);
    }

    private static boolean scroll(int itemId) {
        return itemId / 10_000 == 204;
    }

    private double expectedPoints(int itemId) {
        Map<String, Integer> info = stats.apply(itemId);
        if (info == null) {
            return 0.0d;
        }
        double points = 0.0d;
        for (Map.Entry<String, Double> weight : WEIGHTS.entrySet()) {
            points += weight.getValue() * info.getOrDefault(weight.getKey(), 0);
        }
        return info.getOrDefault("success", 0) / 100.0d * points;
    }

    private double perPoint() {
        if (perPoint < 0.0d) {
            List<Double> rates = new ArrayList<>();
            anchors.get().forEach((itemId, price) -> {
                double points = scroll(itemId) ? expectedPoints(itemId) : 0.0d;
                if (points > 0.0d) {
                    rates.add(price / points);
                }
            });
            Collections.sort(rates);
            perPoint = rates.isEmpty() ? 0.0d : rates.get(rates.size() / 2);
        }
        return perPoint;
    }
}
