package server.living.population;

import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The price index's one-time reseed (docs/ECONOMY_PLAN.md section 10.9, user 2026-11-24): before the drift guard, unsold
 * stock took rows far under their seed price. Once, at start before any citizen loads, every row under
 * PRICE_INDEX_RESEED_BELOW x its seed goes to PRICE_INDEX_RESEED_TO x it, never under what an NPC pays. Every old price
 * is kept in living_price_reseed, whose rows also stop a second run. Only with the guard on: without it the rows would
 * slide again. The sample count stays: at 0 the next start's Market.reseedUnmoved would take the row for an unmoved one
 * and set it to its full seed.
 */
final class PriceIndexReseed {
    private static final Logger log = LoggerFactory.getLogger(PriceIndexReseed.class);
    private static final int LISTED = 10;

    /** One row the reseed moves. */
    record Move(int itemId, long seed, long oldPrice, long newPrice, int samples) {
    }

    /** What the reseed would do: the rows it moves, and how many it leaves because they have no seed of their own. */
    record Plan(List<Move> moves, int noSeed) {
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
        log.info("price index reseed: {} rows under {} x their seed set to {} x it, {} without a seed left alone; old"
                + " prices in living_price_reseed", plan.moves().size(), config.PRICE_INDEX_RESEED_BELOW,
                config.PRICE_INDEX_RESEED_TO, plan.noSeed());
        return plan.moves().size();
    }

    /** The rows that move, the cheapest seed first; reads only. */
    static Plan plan(Market market, PopulationConfig config) {
        List<Move> moves = new ArrayList<>();
        int noSeed = 0;
        for (Map.Entry<Integer, long[]> entry : market.rows().entrySet()) {
            int itemId = entry.getKey();
            long[] row = entry.getValue();
            long seed = market.anchorPrice(itemId);
            if (seed <= Market.MINIMUM_SEED) { // only the fallback minimum: nothing prices it
                noSeed++;
                continue;
            }
            long target = Math.max(market.npcUnit(itemId), Math.round(seed * config.PRICE_INDEX_RESEED_TO));
            if (row[0] < seed * config.PRICE_INDEX_RESEED_BELOW && target > row[0]) {
                moves.add(new Move(itemId, seed, row[0], target, (int) row[1]));
            }
        }
        moves.sort(Comparator.comparingLong(Move::seed).thenComparingInt(Move::itemId));
        return new Plan(moves, noSeed);
    }

    /** The dry run for the admin port's /runtime/stats/price-reseed: what the reseed would change now; changes nothing. */
    static String dryRunJson(Market market, CitizenStore store, PopulationConfig config) {
        Plan plan = plan(market, config);
        Map<String, Integer> classes = new TreeMap<>();
        plan.moves().forEach(move -> classes.merge(PriceArms.ItemClass.of(move.itemId()).name(), 1, Integer::sum));
        StringBuilder out = new StringBuilder("{\"ok\":true,\"switch_on\":").append(config.PRICE_INDEX_RESEED_ENABLED)
                .append(",\"guard_on\":").append(config.PRICE_INDEX_DRIFT_GUARD_ENABLED)
                .append(",\"already_ran\":").append(store.priceReseedRan())
                .append(",\"below\":").append(config.PRICE_INDEX_RESEED_BELOW)
                .append(",\"to\":").append(config.PRICE_INDEX_RESEED_TO)
                .append(",\"rows\":").append(market.rows().size())
                .append(",\"would_change\":").append(plan.moves().size())
                .append(",\"no_seed\":").append(plan.noSeed()).append(",\"classes\":{");
        String comma = "";
        for (Map.Entry<String, Integer> entry : classes.entrySet()) {
            out.append(comma).append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            comma = ",";
        }
        out.append("},\"largest\":[");
        comma = "";
        for (Move move : plan.moves().stream()
                .sorted(Comparator.comparingLong((Move m) -> m.newPrice() - m.oldPrice()).reversed()).limit(LISTED).toList()) {
            out.append(comma).append(String.format(Locale.ROOT,
                    "{\"item\":%d,\"seed\":%d,\"old\":%d,\"new\":%d,\"samples\":%d}",
                    move.itemId(), move.seed(), move.oldPrice(), move.newPrice(), move.samples()));
            comma = ",";
        }
        return out.append("]}").toString();
    }
}
