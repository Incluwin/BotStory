package server.living.population;

import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The price index's one-time reseed (docs/ECONOMY_PLAN.md section 10.9, user 2026-11-24): before the drift guard, unsold
 * stock took rows far under their seed price. Once, at start before any citizen loads, every row under
 * PRICE_INDEX_RESEED_BELOW x its seed goes to PRICE_INDEX_RESEED_TO x it, never under what an NPC pays. One whose seed
 * is the scroll formula's estimate goes to PRICE_INDEX_RESEED_FORMULA_TO x it, and one whose seed is only a multiple of
 * its NPC price stays (option D, user 2026-11-24): the guard alone moves it. Every old price
 * is kept in living_price_reseed, whose rows also stop a second run. Only with the guard on: without it the rows would
 * slide again. The sample count stays: at 0 the next start's Market.reseedUnmoved would take the row for an unmoved one
 * and set it to its full seed.
 */
final class PriceIndexReseed {
    private static final Logger log = LoggerFactory.getLogger(PriceIndexReseed.class);
    private static final int LISTED = 10;

    /** One row the reseed moves: to {@code multiple} x its seed, or the NPC price when that is more. */
    record Move(int itemId, long seed, long oldPrice, long newPrice, int samples, Market.SeedSource source,
                double multiple) {
    }

    /**
     * What the reseed would do: the rows it moves, the fallen rows it skips because their seed is only a multiple of the
     * NPC price, and how many rows have no seed at all.
     */
    record Plan(List<Move> moves, List<Integer> skipped, int noSeed) {
    }

    private PriceIndexReseed() {
    }

    /** Runs the reseed if its switch and the drift guard are on and it never ran; the number of rows it set. */
    static int runOnce(Market market, CitizenStore store, PopulationConfig config) {
        if (!config.PRICE_INDEX_RESEED_ENABLED) {
            return 0;
        }
        if (!config.PRICE_INDEX_DRIFT_GUARD_ENABLED) {
            log.warn("price index reseed refused: PRICE_INDEX_DRIFT_GUARD_ENABLED is off, the rows would slide again");
            return 0;
        }
        Plan plan;
        synchronized (PriceIndexReseed.class) {
            if (store.priceReseedRan()) {
                return 0;
            }
            plan = plan(market, config);
            if (!store.reseedPrices(plan.moves())) {
                log.error("price index reseed failed; every price is unchanged");
                return 0;
            }
            for (Move move : plan.moves()) {
                market.rows().get(move.itemId())[0] = move.newPrice();
            }
        }
        log.info("price index reseed: {} rows under {} x their seed set to {} x it (scroll formula {} x), {} with only an"
                + " NPC-price seed and {} without a seed left alone; old prices in living_price_reseed",
                plan.moves().size(), config.PRICE_INDEX_RESEED_BELOW, config.PRICE_INDEX_RESEED_TO,
                config.PRICE_INDEX_RESEED_FORMULA_TO, plan.skipped().size(), plan.noSeed());
        return plan.moves().size();
    }

    /** The rows that move, the cheapest seed first; reads only. */
    static Plan plan(Market market, PopulationConfig config) {
        List<Move> moves = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        int noSeed = 0;
        for (Map.Entry<Integer, long[]> entry : market.rows().entrySet()) {
            int itemId = entry.getKey();
            long[] row = entry.getValue();
            long seed = market.anchorPrice(itemId);
            if (seed <= Market.MINIMUM_SEED) { // only the fallback minimum: nothing prices it
                noSeed++;
                continue;
            }
            if (row[0] >= seed * config.PRICE_INDEX_RESEED_BELOW) {
                continue;
            }
            Market.SeedSource source = market.seedSource(itemId);
            if (source == Market.SeedSource.NPC_MULTIPLE) {
                skipped.add(itemId);
                continue;
            }
            double multiple = source == Market.SeedSource.SCROLL_FORMULA
                    ? config.PRICE_INDEX_RESEED_FORMULA_TO : config.PRICE_INDEX_RESEED_TO;
            long target = Math.max(market.npcUnit(itemId), Math.round(seed * multiple));
            if (target > row[0]) {
                moves.add(new Move(itemId, seed, row[0], target, (int) row[1], source, multiple));
            }
        }
        moves.sort(Comparator.comparingLong(Move::seed).thenComparingInt(Move::itemId));
        skipped.sort(null);
        return new Plan(moves, skipped, noSeed);
    }

    /** The dry run for the admin port's /runtime/stats/price-reseed: what the reseed would change now; changes nothing. */
    static String dryRunJson(Market market, CitizenStore store, PopulationConfig config) {
        Plan plan = plan(market, config);
        Map<String, Integer> classes = new TreeMap<>();
        Map<String, Integer> sources = new TreeMap<>();
        plan.moves().forEach(move -> {
            classes.merge(PriceArms.ItemClass.of(move.itemId()).name(), 1, Integer::sum);
            sources.merge(move.source().name(), 1, Integer::sum);
        });
        StringBuilder out = new StringBuilder("{\"ok\":true,\"switch_on\":").append(config.PRICE_INDEX_RESEED_ENABLED)
                .append(",\"guard_on\":").append(config.PRICE_INDEX_DRIFT_GUARD_ENABLED)
                .append(",\"already_ran\":").append(store.priceReseedRan())
                .append(",\"below\":").append(config.PRICE_INDEX_RESEED_BELOW)
                .append(",\"to\":").append(config.PRICE_INDEX_RESEED_TO)
                .append(",\"formula_to\":").append(config.PRICE_INDEX_RESEED_FORMULA_TO)
                .append(",\"rows\":").append(market.rows().size())
                .append(",\"would_change\":").append(plan.moves().size())
                .append(",\"no_seed\":").append(plan.noSeed())
                .append(",\"classes\":").append(counts(classes))
                .append(",\"sources\":").append(counts(sources))
                .append(",\"skipped_npc_multiple\":").append(plan.skipped().size())
                .append(",\"skipped_items\":").append(plan.skipped().toString().replace(" ", ""));
        List<Move> largest = plan.moves().stream()
                .sorted(Comparator.comparingLong((Move m) -> m.newPrice() - m.oldPrice()).reversed()).limit(LISTED).toList();
        return out.append(",\"largest\":").append(listed(largest))
                .append(",\"moves\":").append(listed(plan.moves())).append('}').toString();
    }

    private static String counts(Map<String, Integer> counts) {
        StringJoiner out = new StringJoiner(",", "{", "}");
        counts.forEach((name, count) -> out.add("\"" + name + "\":" + count));
        return out.toString();
    }

    private static String listed(List<Move> moves) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (Move move : moves) {
            out.add(String.format(Locale.ROOT,
                    "{\"item\":%d,\"source\":\"%s\",\"multiple\":%s,\"seed\":%d,\"old\":%d,\"new\":%d,\"samples\":%d}",
                    move.itemId(), move.source(), move.multiple(), move.seed(), move.oldPrice(), move.newPrice(),
                    move.samples()));
        }
        return out.toString();
    }
}
